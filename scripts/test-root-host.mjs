import { execFileSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { delimiter, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sdk = process.argv[2] || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Provide the Android SDK path or set ANDROID_HOME.');
const compileSdk = /compileSdk\s+(\d+)/.exec(readFileSync(join(root, 'app/build.gradle'), 'utf8'))?.[1];
if (!compileSdk) throw new Error('Cannot read compileSdk from app/build.gradle.');
const platform = readdirSync(join(sdk, 'platforms')).find(name =>
    name === `android-${compileSdk}` || name === `android-${compileSdk}.0`);
if (!platform) throw new Error(`Android platform ${compileSdk} is required for host compilation.`);
const androidJar = join(sdk, 'platforms', platform, 'android.jar');
const work = mkdtempSync(join(tmpdir(), 'lauso-root-host-'));
const classes = join(work, 'classes');
const javaDir = join(root, 'app/src/main/java/dev/bucket/launcherslogan');
const sharedDir = join(root, 'shared/src/main/java/dev/bucket/launcherslogan');
try {
    const sources = ['RuleStore.java', 'SloganRule.java',
        'MenuMaterialSettings.java', 'MenuContentSettings.java'].map(name => join(sharedDir, name));
    sources.push(join(javaDir, 'RootAccess.java'));
    sources.push(join(root, 'scripts/device-tests/RootCleanupAudit.java'));
    execFileSync('javac', ['-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', androidJar,
        '-d', classes, ...sources], { stdio: 'inherit' });
    execFileSync('javac', ['-encoding', 'UTF-8', '-cp', [classes, androidJar].join(delimiter),
        '-d', classes, join(root, 'scripts/host-tests/RootProcessHostAudit.java')], { stdio: 'inherit' });
    for (const test of ['RootCleanupAudit', 'RootProcessHostAudit']) {
        execFileSync('java', ['-cp', [classes, androidJar].join(delimiter), `dev.bucket.launcherslogan.${test}`],
            { stdio: 'inherit', timeout: 30_000 });
    }
} finally {
    if (existsSync(work)) rmSync(work, { recursive: true, force: true });
}
