import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { writeFileSync, mkdtempSync, mkdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { githubToken, verifyReleaseTag } from './verify-release-tag.mjs';

const root = resolve(process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), '../../verification-temp/release-audit'));
const sdk = process.argv[3] || process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
if (!sdk) throw new Error('Provide the Android SDK path as the second argument or set ANDROID_HOME.');
const buildTools = join(sdk, 'build-tools/35.0.0');
mkdirSync(root, { recursive: true });
const token = githubToken();
const repos = ['TheKingBucket001/Lauso', 'Xposed-Modules-Repo/dev.bucket.launcherslogan'];
async function api(path) {
    const response = await fetch(`https://api.github.com${path}`, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json' },
        signal: AbortSignal.timeout(20_000),
    });
    if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
    return response.json();
}
const evidence = { checkedAt: new Date().toISOString(), repositories: [] };
let releaseCertificate;
const temporary = mkdtempSync(join(tmpdir(), 'lauso-release-check-'));
try {
    for (const repository of repos) {
        const refs = await api(`/repos/${repository}/git/matching-refs/tags`);
        const releases = [];
        for (let page = 1; ; page++) {
            const items = await api(`/repos/${repository}/releases?per_page=100&page=${page}`);
            releases.push(...items);
            if (items.length < 100) break;
        }
        const head = await api(`/repos/${repository}/branches/main`);
        const readme = await api(`/repos/${repository}/contents/README.md?ref=main`);
        const report = { repository, head: head.commit.sha, readmeBlob: readme.sha, tags: [], releases: [] };
        for (const ref of refs) {
            const tag = ref.ref.slice('refs/tags/'.length);
            const verified = await verifyReleaseTag({ repository, tag, token, expectedObject: ref.object.sha });
            report.tags.push({ ...verified, details: await api(`/repos/${repository}/git/tags/${verified.tagObject}`) });
        }
        for (const release of releases) {
            const row = { id: release.id, tag: release.tag_name, body: release.body,
                publishedAt: release.published_at, draft: release.draft, assets: [] };
            if (!report.tags.some(tag => tag.tag === row.tag)) throw new Error(`Unverified release ${repository}@${row.tag}`);
            for (const asset of release.assets) {
                if (!asset.name.endsWith('.apk')) continue;
                const response = await fetch(asset.browser_download_url, { signal: AbortSignal.timeout(30_000) });
                if (!response.ok) throw new Error(`${asset.name}: HTTP ${response.status}`);
                const buffer = Buffer.from(await response.arrayBuffer());
                const sha256 = createHash('sha256').update(buffer).digest('hex');
                if (buffer.length !== asset.size || `sha256:${sha256}` !== asset.digest) {
                    throw new Error(`Asset mismatch: ${repository}/${asset.name}`);
                }
                const apk = join(temporary, `${asset.id}.apk`);
                writeFileSync(apk, buffer);
                const signature = execFileSync('java', ['-jar', join(buildTools, 'lib/apksigner.jar'),
                    'verify', '--verbose', '--print-certs', apk], { encoding: 'utf8' });
                if (signature.includes('Android Debug')) throw new Error('Debug APK in formal release.');
                const certificate = /Signer #1 certificate SHA-256 digest: (\w+)/.exec(signature)?.[1];
                if (!certificate || (releaseCertificate && certificate !== releaseCertificate)) {
                    throw new Error('Release APK signing certificate mismatch.');
                }
                releaseCertificate = certificate;
                const badging = execFileSync(join(buildTools, process.platform === 'win32' ? 'aapt.exe' : 'aapt'), ['dump', 'badging', apk], { encoding: 'utf8' });
                const metadata = /^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/m.exec(badging);
                if (!metadata || metadata[1] !== 'dev.bucket.launcherslogan'
                        || asset.name !== `LauSo-v${metadata[3]}.apk`
                        || row.tag !== (repository === repos[0] ? `v${metadata[3]}` : `${metadata[2]}-${metadata[3]}`)) {
                    throw new Error(`APK metadata mismatch: ${repository}@${row.tag}`);
                }
                row.assets.push({ id: asset.id, name: asset.name, size: asset.size, sha256,
                    packageName: metadata[1], versionCode: metadata[2], versionName: metadata[3], signature });
            }
            if (row.assets.length !== 1) throw new Error(`Expected one APK in ${repository}@${row.tag}`);
            report.releases.push(row);
        }
        evidence.repositories.push(report);
    }
    const [source, mirror] = evidence.repositories;
    for (const release of mirror.releases) {
        const original = source.releases.find(item => item.assets[0]?.versionName === release.assets[0]?.versionName);
        if (!original || original.body.trimEnd() !== release.body.trimEnd()
                || original.assets[0]?.sha256 !== release.assets[0]?.sha256) {
            throw new Error(`Mirror mismatch: ${release.tag}`);
        }
    }
    writeFileSync(join(root, 'github-release-evidence.json'), JSON.stringify(evidence, null, 2) + '\n');
    console.log(JSON.stringify(evidence.repositories.map(repo => ({
        repository: repo.repository, head: repo.head, readmeBlob: repo.readmeBlob,
        tags: repo.tags.map(({ tag, tagObject, ssh, verified }) => ({ tag, tagObject, ssh, verified })),
        releases: repo.releases.map(({ tag, assets }) => ({ tag, assets: assets.map(({ name, sha256, versionCode }) => ({ name, sha256, versionCode })) })),
    })), null, 2));
} finally {
    rmSync(temporary, { recursive: true, force: true });
}
