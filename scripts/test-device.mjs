import { execFileSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sdk = process.argv[2] || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Provide the Android SDK path or set ANDROID_HOME.');
const platform = /compileSdk\s+(\d+)/.exec(readFileSync(join(root, 'app/build.gradle'), 'utf8'))?.[1];
const platformDir = readdirSync(join(sdk, 'platforms')).find(name => name === `android-${platform}` || name === `android-${platform}.0`);
if (!platformDir) throw new Error(`Install Android platform ${platform} first.`);
const androidJar = join(sdk, 'platforms', platformDir, 'android.jar');
const d8 = join(sdk, 'build-tools/35.0.0/lib/d8.jar');
const source = join(root, 'app/src/main/java/dev/bucket/launcherslogan');
const tests = join(root, 'scripts/device-tests');
const work = mkdtempSync(join(tmpdir(), 'lauso-code-audit-'));
const deviceJar = `/data/local/tmp/lauso-code-audit-${process.pid}.jar`;
const run = (command, args, options = {}) => execFileSync(command, args, {
    cwd: work, stdio: 'inherit', timeout: 60_000, ...options,
});
let uploaded = false;
try {
    run('adb', ['get-state']);
    run('jar', ['xf', join(root, 'local-libs/libxposed-api-102.0.0.aar'), 'classes.jar']);
    const api = join(work, 'classes.jar');
    const classes = join(work, 'compiled');
    const files = directory => readdirSync(directory).filter(name => name.endsWith('.java')).map(name => join(directory, name));
    run('javac', ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp',
        [androidJar, api].join(process.platform === 'win32' ? ';' : ':'),
        '-d', classes, ...files(source), ...files(tests)]);
    const testClasses = join(work, 'test-classes.jar');
    const dex = join(work, 'device-tests.jar');
    run('jar', ['--create', '--file', testClasses, '-C', classes, '.']);
    run('java', ['-cp', d8, 'com.android.tools.r8.D8', '--min-api', '26',
        '--lib', androidJar, '--output', dex, testClasses, api]);
    run('adb', ['push', dex, deviceJar]);
    uploaded = true;
    run('adb', ['shell', `su -c 'CLASSPATH=${deviceJar} app_process /system/bin dev.bucket.launcherslogan.ModuleAudit'`]);
} finally {
    try {
        if (uploaded) run('adb', ['shell', 'rm', deviceJar]);
    } finally {
        rmSync(work, { recursive: true, force: true });
    }
}
