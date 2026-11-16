import { execFileSync } from 'node:child_process';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const work = mkdtempSync(join(tmpdir(), 'lauso-ui-host-'));
try {
    execFileSync('javac', ['-encoding', 'UTF-8', '--release', '8', '-d', work,
        join(root, 'app/src/main/java/dev/bucket/launcherslogan/TextInputLimit.java'),
        join(root, 'scripts/host-tests/TextInputLimitHostAudit.java')], { stdio: 'inherit' });
    execFileSync('java', ['-cp', work, 'dev.bucket.launcherslogan.TextInputLimitHostAudit'],
        { stdio: 'inherit', timeout: 10_000 });
} finally {
    rmSync(work, { recursive: true, force: true });
}
