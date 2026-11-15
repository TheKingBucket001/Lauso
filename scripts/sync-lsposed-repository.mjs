import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { githubToken, verifyReleaseTag } from './verify-release-tag.mjs';

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
if (process.env.GITHUB_ACTIONS === 'true') {
    throw new Error('Repository synchronization and SSH tag signing must run on the local laptop.');
}
const targetUrl = process.env.LSPOSED_REPOSITORY_SSH_URL
    || 'git@github.com:Xposed-Modules-Repo/dev.bucket.launcherslogan.git';
const sourceUrl = process.env.LSPOSED_SOURCE_URL || 'https://github.com/TheKingBucket001/Lauso';
const displayTitle = process.env.LSPOSED_DISPLAY_TITLE
    || 'LauSo-为 ColorOS Launcher 图标长按菜单添加自定义标语';
const releaseTag = process.env.LSPOSED_RELEASE_TAG || process.env.GITHUB_REF_NAME || '';

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

const signingKey = readGitConfig('user.signingkey');
const signingProgram = readGitConfig('gpg.ssh.program') || 'ssh-keygen';
const pushReleaseTag = process.env.LSPOSED_PUSH_RELEASE_TAG === 'true';
if (!signingKey) throw new Error('SSH signing key is required; unsigned sync is not allowed.');
const targetRepository = /^git@github\.com:([^/]+\/[^/]+?)\.git$/.exec(targetUrl)?.[1];
if (!targetRepository) throw new Error('LSPOSED_REPOSITORY_SSH_URL must be a GitHub SSH repository URL.');
const sourceRepository = /^https:\/\/github\.com\/([^/]+\/[^/]+)\/?$/.exec(sourceUrl)?.[1];
if (!sourceRepository) throw new Error('LSPOSED_SOURCE_URL must identify a GitHub repository.');
let normalizedTag;
if (pushReleaseTag) {
    const gradle = readFileSync(join(projectRoot, 'app/build.gradle'), 'utf8');
    const code = /versionCode\s+(\d+)/.exec(gradle)?.[1];
    const version = /versionName\s+'([^']+)'/.exec(gradle)?.[1];
    if (!code || !version || releaseTag !== `v${version}`) {
        throw new Error('Source release tag must match app/build.gradle.');
    }
    normalizedTag = `${code}-${version}`;
    await verifyReleaseTag({ repository: sourceRepository, tag: releaseTag, token: githubToken() });
}
const workRoot = mkdtempSync(join(tmpdir(), 'lauso-lsposed-'));
const targetRoot = join(workRoot, 'repository');

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
    writeText('.github/scripts/verify-release-tag.mjs',
        readFileSync(join(projectRoot, 'scripts/verify-release-tag.mjs'), 'utf8'));

    const targetAssets = join(targetRoot, 'assets');
    if (existsSync(targetAssets)) rmSync(targetAssets, { recursive: true, force: true });
    cpSync(join(projectRoot, 'assets'), targetAssets, { recursive: true });

    git(['add', 'README.md', 'SUMMARY', 'SOURCE_URL', 'assets', '.github/workflows', '.github/scripts/verify-release-tag.mjs']);
    try {
        git(['diff', '--cached', '--quiet']);
        console.log('LSPosed repository is already synchronized.');
    } catch {
        const commitArgs = [
            '-c', 'user.name=TheKingBucket001',
            '-c', 'user.email=186387631+TheKingBucket001@users.noreply.github.com',
            '-c', 'commit.gpgsign=true',
            '-c', 'gpg.format=ssh', '-c', `user.signingkey=${signingKey}`,
            '-c', `gpg.ssh.program=${signingProgram}`,
        ];
        commitArgs.push('commit', '-m', `同步 LauSo ${releaseTag || 'documentation'}`);
        git(commitArgs);
        git(['push', 'origin', 'HEAD:main']);
    }

    // Sign the canonical tag before publication so normalization cannot replace the signature.
    if (normalizedTag) {
        const remoteRef = execFileSync('git', [
            'ls-remote', '--tags', 'origin', `refs/tags/${normalizedTag}`,
        ], { cwd: targetRoot, encoding: 'utf8' }).trim();
        const tagExists = remoteRef.length > 0;
        if (!tagExists) {
            const tagArgs = [
                '-c', 'user.name=TheKingBucket001',
                '-c', 'user.email=186387631+TheKingBucket001@users.noreply.github.com',
                '-c', 'tag.gpgSign=true',
                '-c', 'gpg.format=ssh', '-c', `user.signingkey=${signingKey}`,
                '-c', `gpg.ssh.program=${signingProgram}`,
            ];
            tagArgs.push('tag', '-s', normalizedTag, '-m', `LauSo ${releaseTag}`);
            git(tagArgs);
            git(['push', 'origin', `refs/tags/${normalizedTag}`]);
        }
        console.log(await verifyReleaseTag({
            repository: targetRepository, tag: normalizedTag, token: githubToken(),
            expectedObject: tagExists ? remoteRef.split(/\s+/)[0]
                : execFileSync('git', ['rev-parse', normalizedTag], { cwd: targetRoot, encoding: 'utf8' }).trim(),
        }));
    }
} finally {
    rmSync(workRoot, { recursive: true, force: true });
}
