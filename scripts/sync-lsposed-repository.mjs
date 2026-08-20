import { cpSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const targetUrl = process.env.LSPOSED_REPOSITORY_SSH_URL
    || 'git@github.com:Xposed-Modules-Repo/dev.bucket.launcherslogan.git';
const sourceUrl = process.env.LSPOSED_SOURCE_URL || 'https://github.com/TheKingBucket001/Lauso';
const displayTitle = process.env.LSPOSED_DISPLAY_TITLE
    || 'LauSo-为 ColorOS Launcher 图标长按菜单添加自定义标语';
const workRoot = mkdtempSync(join(tmpdir(), 'lauso-lsposed-'));
const targetRoot = join(workRoot, 'repository');

function git(args, cwd = targetRoot) {
    execFileSync('git', args, { cwd, stdio: 'inherit' });
}

function writeText(relativePath, value) {
    writeFileSync(join(targetRoot, relativePath), value, 'utf8');
}

try {
    git(['clone', targetUrl, targetRoot], workRoot);

    // README is copied byte-for-byte from the source repository. The referenced assets travel
    // with it so the LSPosed listing renders exactly the same document.
    writeText('README.md', readFileSync(join(projectRoot, 'README.md'), 'utf8'));
    writeText('SUMMARY', `${displayTitle}\n`);
    writeText('SOURCE_URL', `${sourceUrl}\n`);

    const targetAssets = join(targetRoot, 'assets');
    if (existsSync(targetAssets)) rmSync(targetAssets, { recursive: true, force: true });
    cpSync(join(projectRoot, 'assets'), targetAssets, { recursive: true });

    git(['add', 'README.md', 'SUMMARY', 'SOURCE_URL', 'assets']);
    try {
        git(['diff', '--cached', '--quiet']);
        console.log('LSPosed repository is already synchronized.');
    } catch {
        git(['-c', 'user.name=LauSo Release Sync',
            '-c', 'user.email=186387631+TheKingBucket001@users.noreply.github.com',
            '-c', 'commit.gpgsign=false',
            'commit', '-m', `同步 LauSo ${process.env.GITHUB_REF_NAME || 'documentation'}`]);
        git(['push', 'origin', 'HEAD:main']);
    }
} finally {
    rmSync(workRoot, { recursive: true, force: true });
}
