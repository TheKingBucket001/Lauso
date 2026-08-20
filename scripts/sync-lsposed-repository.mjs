import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const targetUrl = process.env.LSPOSED_REPOSITORY_SSH_URL
    || 'git@github.com:Xposed-Modules-Repo/dev.bucket.launcherslogan.git';
const sourceUrl = process.env.LSPOSED_SOURCE_URL || 'https://github.com/TheKingBucket001/Lauso';
const displayTitle = process.env.LSPOSED_DISPLAY_TITLE
    || 'LauSo-为 ColorOS Launcher 图标长按菜单添加自定义标语';
const releaseTag = process.env.LSPOSED_RELEASE_TAG || process.env.GITHUB_REF_NAME || '';
const workRoot = mkdtempSync(join(tmpdir(), 'lauso-lsposed-'));
const targetRoot = join(workRoot, 'repository');

function readGitConfig(name) {
    try {
        return execFileSync('git', ['config', '--get', name], {
            cwd: projectRoot,
            encoding: 'utf8',
        }).trim();
    } catch {
        return '';
    }
}

const signingKey = process.env.LSPOSED_SYNC_SIGNING_KEY || readGitConfig('user.signingkey');
const signingProgram = process.env.LSPOSED_SYNC_GPG_SSH_PROGRAM
    || readGitConfig('gpg.ssh.program') || 'ssh-keygen';
const pushReleaseTag = process.env.LSPOSED_PUSH_RELEASE_TAG === 'true';

function git(args, cwd = targetRoot) {
    execFileSync('git', args, { cwd, stdio: 'inherit' });
}

function writeText(relativePath, value) {
    const file = join(targetRoot, relativePath);
    mkdirSync(dirname(file), { recursive: true });
    writeFileSync(file, value, 'utf8');
}

try {
    git(['clone', targetUrl, targetRoot], workRoot);

    // README is copied byte-for-byte from the source repository. The referenced assets travel
    // with it so the LSPosed listing renders exactly the same document.
    writeText('README.md', readFileSync(join(projectRoot, 'README.md'), 'utf8'));
    writeText('SUMMARY', `${displayTitle}\n`);
    writeText('SOURCE_URL', `${sourceUrl}\n`);
    writeText(
        '.github/workflows/sync-source-release.yml',
        readFileSync(join(projectRoot, 'scripts/lsposed-release-sync-workflow.yml'), 'utf8'),
    );

    const targetAssets = join(targetRoot, 'assets');
    if (existsSync(targetAssets)) rmSync(targetAssets, { recursive: true, force: true });
    cpSync(join(projectRoot, 'assets'), targetAssets, { recursive: true });

    git(['add', 'README.md', 'SUMMARY', 'SOURCE_URL', 'assets', '.github/workflows']);
    try {
        git(['diff', '--cached', '--quiet']);
        console.log('LSPosed repository is already synchronized.');
    } catch {
        const commitArgs = [
            '-c', 'user.name=TheKingBucket001',
            '-c', 'user.email=186387631+TheKingBucket001@users.noreply.github.com',
            '-c', `commit.gpgsign=${signingKey ? 'true' : 'false'}`,
        ];
        if (signingKey) {
            commitArgs.push('-c', 'gpg.format=ssh', '-c', `user.signingkey=${signingKey}`,
                '-c', `gpg.ssh.program=${signingProgram}`);
        }
        commitArgs.push('commit', '-m', `同步 LauSo ${releaseTag || 'documentation'}`);
        git(commitArgs);
        git(['push', 'origin', 'HEAD:main']);
    }

    // LSPosed normalizes release tags (for example, v0.6.1 -> 5-0.6.1). Do not
    // create a duplicate source-style tag unless the target workflow explicitly needs it.
    if (releaseTag && pushReleaseTag) {
        let tagExists = false;
        try {
            execFileSync('git', ['ls-remote', '--exit-code', '--tags', 'origin', `refs/tags/${releaseTag}`], {
                cwd: targetRoot,
                stdio: 'ignore',
            });
            tagExists = true;
        } catch {
            // The target has no release tag yet.
        }
        if (!tagExists) {
            const tagArgs = [
                '-c', 'user.name=TheKingBucket001',
                '-c', 'user.email=186387631+TheKingBucket001@users.noreply.github.com',
                '-c', `tag.gpgSign=${signingKey ? 'true' : 'false'}`,
            ];
            if (signingKey) {
                tagArgs.push('-c', 'gpg.format=ssh', '-c', `user.signingkey=${signingKey}`,
                    '-c', `gpg.ssh.program=${signingProgram}`);
            }
            tagArgs.push('tag', signingKey ? '-s' : '-a', releaseTag, '-m', `LauSo ${releaseTag}`);
            git(tagArgs);
            git(['push', 'origin', `refs/tags/${releaseTag}`]);
        }
    }
} finally {
    rmSync(workRoot, { recursive: true, force: true });
}
