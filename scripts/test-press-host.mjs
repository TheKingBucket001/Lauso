import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { homedir, tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Run after writeModuleBoundaryReport. It consumes already resolved artifacts and never downloads.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const reportPath = resolve(root, process.env.LAUSO_BOUNDARY_REPORT || 'build/reports/module-boundaries.json');
if (!existsSync(reportPath)) throw new Error('Run writeModuleBoundaryReport before the press Host audit.');
const report = JSON.parse(readFileSync(reportPath, 'utf8'));
const localProperties = join(root, 'local.properties');
const localSdk = existsSync(localProperties)
    ? /^sdk\.dir\s*=\s*(.+)$/m.exec(readFileSync(localProperties, 'utf8'))?.[1]
        ?.trim().replace(/\\([\\:= ])/g, '$1') : null;
const sdk = process.argv[2] || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || localSdk;
const platform = /compileSdk\s+(\d+)/.exec(readFileSync(join(root, 'app/build.gradle'), 'utf8'))?.[1];
const platformDir = sdk && readdirSync(join(sdk, 'platforms')).find(name =>
    name === `android-${platform}` || name === `android-${platform}.0`);
if (!platformDir) throw new Error(`Android platform ${platform} is required; pass the SDK path or set ANDROID_HOME.`);
// Android interfaces are linked from the SDK; the test never constructs an Android View.
const android = join(sdk, 'platforms', platformDir, 'android.jar');
const variant = report.variant;
const configurations = report.projects?.[':app']?.configurations;
const compile = configurations?.[`${variant}CompileClasspath`]?.filter(item => !item.projectPath);
const runtime = configurations?.[`${variant}RuntimeClasspath`]?.filter(item => !item.projectPath);
if (!compile?.length || !runtime?.length) throw new Error('The boundary report has no resolved app classpaths.');
for (const item of [...compile, ...runtime]) {
    if (!existsSync(item.file)) throw new Error(`Resolved artifact is missing: ${item.component}. Rebuild the boundary report.`);
}

const gradleUser = process.env.GRADLE_USER_HOME || join(homedir(), '.gradle');
const cache = join(gradleUser, 'caches/modules-2/files-2.1');
const compilerVersion = /id\s+'org\.jetbrains\.kotlin\.plugin\.compose'\s+version\s+'([^']+)'/
    .exec(readFileSync(join(root, 'build.gradle'), 'utf8'))?.[1];
if (!compilerVersion) throw new Error('Cannot determine the project Kotlin compiler version.');
function cachedArtifact(group, name, version, extension = 'jar') {
    const directory = join(cache, group, name, version);
    if (!existsSync(directory)) return null;
    for (const hash of readdirSync(directory)) {
        const candidate = join(directory, hash, `${name}-${version}.${extension}`);
        if (existsSync(candidate)) return candidate;
    }
    return null;
}
function compilerReflectVersion() {
    const pom = cachedArtifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', compilerVersion, 'pom');
    if (pom) {
        for (const [dependency] of readFileSync(pom, 'utf8').matchAll(/<dependency>[^]*?<\/dependency>/g)) {
            if (/<groupId>org\.jetbrains\.kotlin<\/groupId>/.test(dependency)
                && /<artifactId>kotlin-reflect<\/artifactId>/.test(dependency)) {
                const version = /<version>([^<]+)<\/version>/.exec(dependency)?.[1]?.trim();
                if (version && /^\d[\w.-]*$/.test(version)) return version;
            }
        }
        throw new Error('The cached Kotlin compiler POM has no concrete kotlin-reflect dependency version.');
    }
    // Gradle may retain only binary descriptors. This exact compiler's published POM declares
    // reflect 1.6.10: https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-compiler-embeddable/2.4.10/kotlin-compiler-embeddable-2.4.10.pom
    if (compilerVersion === '2.4.10') return '1.6.10';
    throw new Error('The compiler POM is not cached and its reflection dependency is unknown. Cache its published POM or review the compiler upgrade.');
}
const resolved = prefix => runtime.find(item => item.component.startsWith(prefix))?.file;
const compiler = cachedArtifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', compilerVersion);
const reflect = cachedArtifact('org.jetbrains.kotlin', 'kotlin-reflect', compilerReflectVersion());
const stdlib = resolved('org.jetbrains.kotlin:kotlin-stdlib:');
const coroutines = resolved('org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:');
const annotations = resolved('org.jetbrains:annotations:');
if (![compiler, reflect, stdlib, coroutines, annotations].every(Boolean)) {
    throw new Error('Kotlin compiler/reflection or resolved JVM dependencies are not cached. Build the project with its Kotlin compiler first.');
}

const javaName = process.platform === 'win32' ? 'java.exe' : 'java';
const candidates = new Set(['JAVA_HOME_21_X64', 'JAVA_HOME', 'JDK_HOME']
    .map(key => process.env[key] && join(process.env[key], 'bin', javaName)).filter(Boolean));
candidates.add('java');
function javaInfo(command) {
    const result = spawnSync(command, ['-XshowSettings:properties', '-version'], { encoding: 'utf8', timeout: 10_000 });
    if (result.status !== 0) return null;
    const text = `${result.stdout}\n${result.stderr}`;
    const home = /java\.home\s*=\s*([^\r\n]+)/.exec(text)?.[1]?.trim();
    const major = Number(/(?:java|openjdk) version "(\d+)/.exec(text)?.[1]);
    return home && Number.isFinite(major) ? { command, home, major } : null;
}
// A Windows machine may keep JAVA_HOME on 17 while a compatible 21 lives beside it.
for (const command of [...candidates]) {
    const info = javaInfo(command);
    if (!info) continue;
    const parent = dirname(info.home);
    for (const entry of readdirSync(parent, { withFileTypes: true })) {
        if (entry.isDirectory()) {
            const sibling = join(parent, entry.name, 'bin', javaName);
            if (existsSync(sibling)) candidates.add(sibling);
        }
    }
}
const java = [...candidates].map(javaInfo).filter(Boolean).find(info => info.major >= 21)?.command;
if (!java) throw new Error('JDK 21 or newer is required to run the actual MIUIX/Compose JVM classes. Set JAVA_HOME to an existing compatible JDK.');

const work = mkdtempSync(join(tmpdir(), 'lauso-press-host-'));
try {
    const classes = join(work, 'classes');
    const argsFile = join(work, 'compile.args');
    // JVM argument files keep Windows command lines below their length limit.
    const quote = value => `"${String(value).replaceAll('\\', '/').replaceAll('"', '\\"')}"`;
    const args = ['-cp', [compiler, stdlib, reflect, coroutines, annotations].join(delimiter),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-classpath', [...new Set(compile.map(item => item.file)), android].join(delimiter), '-d', classes,
        join(root, 'app/src/main/kotlin/dev/bucket/launcherslogan/RulePressIndication.kt'),
        join(root, 'scripts/host-tests/RulePressFeedbackHostAudit.kt')];
    writeFileSync(argsFile, args.map(quote).join('\n'), 'utf8');
    execFileSync(java, [`@${argsFile}`], { stdio: 'inherit', timeout: 60_000 });
    const runArgs = join(work, 'run.args');
    writeFileSync(runArgs, ['-cp', [classes, ...new Set(runtime.map(item => item.file)), android].join(delimiter),
        'dev.bucket.launcherslogan.RulePressFeedbackHostAuditKt'].map(quote).join('\n'), 'utf8');
    execFileSync(java, [`@${runArgs}`], { stdio: 'inherit', timeout: 30_000 });
} finally {
    rmSync(work, { recursive: true, force: true });
}
