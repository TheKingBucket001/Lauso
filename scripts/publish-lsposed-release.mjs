import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { githubToken, verifyReleaseTag } from './verify-release-tag.mjs';

const sourceRepository = 'TheKingBucket001/Lauso';
const mirrorRepository = 'Xposed-Modules-Repo/dev.bucket.launcherslogan';
const releaseCertificateSha256 = 'ff14ab0eb57ec01d1c5092f4326d45b23cc363053b6e210e3382236a58c224e7';

/** Check the signer identity after apksigner verify has completed successfully. */
export function verifyReleaseCertificate(signature) {
    const certificates = [...signature.matchAll(/^Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})\r?$/gm)]
        .map(match => match[1].toLowerCase());
    if (certificates.length !== 1 || certificates[0] !== releaseCertificateSha256) {
        throw new Error('Release APK must have exactly the established LauSo signing certificate.');
    }
    return certificates[0];
}

function clients(token) {
    const headers = { Authorization: `Bearer ${token}`, Accept: 'application/vnd.github+json',
        'X-GitHub-Api-Version': '2022-11-28' };
    return {
        async api(path, { method = 'GET', body, missing = false } = {}) {
            const response = await fetch(`https://api.github.com${path}`, {
                method, headers: { ...headers, 'Content-Type': 'application/json' },
                body: body ? JSON.stringify(body) : undefined,
                signal: AbortSignal.timeout(30_000),
            });
            if (missing && response.status === 404) return null;
            if (!response.ok) throw new Error(`GitHub ${path}: HTTP ${response.status}`);
            return response.json();
        },
        async download(asset) {
            const response = await fetch(asset.browser_download_url, { signal: AbortSignal.timeout(30_000) });
            if (!response.ok) throw new Error(`APK download: HTTP ${response.status}`);
            return Buffer.from(await response.arrayBuffer());
        },
        async upload(releaseId, asset, apk) {
            const response = await fetch(`https://uploads.github.com/repos/${mirrorRepository}/releases/${releaseId}/assets?name=${encodeURIComponent(asset.name)}`, {
                method: 'POST', headers: { ...headers, 'Content-Type': 'application/vnd.android.package-archive' },
                body: apk, signal: AbortSignal.timeout(30_000),
            });
            if (!response.ok) throw new Error(`Mirror upload: HTTP ${response.status}`);
            return response.json();
        },
        verifyTag: options => verifyReleaseTag({ ...options, token }),
        inspectApk(apk, { sdk, sourceTag, targetTag }) {
            if (!sdk) throw new Error('Provide the Android SDK path or set ANDROID_HOME.');
            const temporary = mkdtempSync(join(tmpdir(), 'lauso-mirror-apk-'));
            try {
                const file = join(temporary, 'release.apk');
                writeFileSync(file, apk);
                const buildTools = join(sdk, 'build-tools/35.0.0');
                const options = { encoding: 'utf8', timeout: 30_000 };
                const signature = execFileSync('java', ['-jar', join(buildTools, 'lib/apksigner.jar'),
                    'verify', '--verbose', '--print-certs', file], options);
                verifyReleaseCertificate(signature);
                const badging = execFileSync(join(buildTools, process.platform === 'win32' ? 'aapt.exe' : 'aapt'),
                    ['dump', 'badging', file], options);
                const metadata = /^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/m.exec(badging);
                if (!metadata || metadata[1] !== 'dev.bucket.launcherslogan'
                        || sourceTag !== `v${metadata[3]}` || targetTag !== `${metadata[2]}-${metadata[3]}`) {
                    throw new Error('APK package/version does not match the signed source and mirror tags.');
                }
            } finally {
                rmSync(temporary, { recursive: true, force: true });
            }
        },
    };
}

function sameAsset(actual, expected) {
    return actual?.name === expected.name && actual.size === expected.size && actual.digest === expected.digest;
}

function checkMirror(release, source, sourceTag, targetTag, { assetRequired }) {
    if (release.tag_name !== targetTag || release.name !== `LauSo ${sourceTag}`
            || release.body?.trimEnd() !== source.body.trimEnd() || release.prerelease
            || release.assets.length > 1 || (assetRequired && release.assets.length !== 1)
            || (release.assets.length && !sameAsset(release.assets[0], source.assets[0]))) {
        throw new Error('Existing mirror release differs from the source; refusing to overwrite it.');
    }
}

/** Consume a cloud-built release only. Tags must already have been signed on the laptop. */
export async function publishLsposedRelease({ sourceTag, targetTag, sdk, token, dependencies = clients(token) }) {
    if (process.env.GITHUB_ACTIONS === 'true') throw new Error('Run mirror publication on the local laptop.');
    if (!/^v\d+(?:\.\d+)*$/.test(sourceTag || '') || !/^\d+-\d+(?:\.\d+)*$/.test(targetTag || '')
            || targetTag.slice(targetTag.indexOf('-') + 1) !== sourceTag.slice(1)) {
        throw new Error('Usage: publish-lsposed-release.mjs vVersion VersionCode-VersionName [Android-SDK-path]');
    }
    const { api, download, upload, inspectApk, verifyTag } = dependencies;
    const sourceTagResult = await verifyTag({ repository: sourceRepository, tag: sourceTag });
    const mirrorTagResult = await verifyTag({ repository: mirrorRepository, tag: targetTag });
    const source = await api(`/repos/${sourceRepository}/releases/tags/${sourceTag}`);
    const asset = source.assets?.[0];
    if (source.tag_name !== sourceTag || source.draft || source.prerelease || !source.body?.trim()
            || source.assets?.length !== 1 || asset.name !== `LauSo-${sourceTag}.apk`
            || !/^sha256:[a-f0-9]{64}$/.test(asset.digest || '')) {
        throw new Error('Source must be a published formal release with notes and exactly one digested APK.');
    }
    const apk = await download(asset);
    if (apk.length !== asset.size || `sha256:${createHash('sha256').update(apk).digest('hex')}` !== asset.digest) {
        throw new Error('Downloaded source APK does not match its GitHub digest.');
    }
    await inspectApk(apk, { sdk, sourceTag, targetTag });
    const prefix = `/repos/${mirrorRepository}/releases`;
    let mirror = await api(`${prefix}/tags/${targetTag}`, { missing: true });
    // GitHub's tag endpoint only returns published releases. Include drafts when resuming.
    for (let page = 1; !mirror; page++) {
        const releases = await api(`${prefix}?per_page=100&page=${page}`);
        mirror = releases.find(release => release.tag_name === targetTag);
        if (mirror || releases.length < 100) break;
    }
    if (mirror) {
        checkMirror(mirror, source, sourceTag, targetTag, { assetRequired: !mirror.draft });
        if (!mirror.draft) return { url: mirror.html_url, alreadyPublished: true, digest: asset.digest };
    } else {
        await verifyTag({ repository: mirrorRepository, tag: targetTag, expectedObject: mirrorTagResult.tagObject });
        mirror = await api(prefix, { method: 'POST', body: {
            tag_name: targetTag, target_commitish: mirrorTagResult.commit,
            name: `LauSo ${sourceTag}`, body: source.body, draft: true,
        } });
    }
    if (!mirror.assets.length) {
        const uploaded = await upload(mirror.id, asset, apk);
        if (!sameAsset(uploaded, asset)) throw new Error('Mirror upload mismatch; release remains a draft.');
    }
    const draft = await api(`${prefix}/${mirror.id}`);
    checkMirror(draft, source, sourceTag, targetTag, { assetRequired: true });
    const currentSource = await api(`/repos/${sourceRepository}/releases/tags/${sourceTag}`);
    if (currentSource.draft || currentSource.prerelease || currentSource.body !== source.body
            || currentSource.assets?.length !== 1 || !sameAsset(currentSource.assets[0], asset)) {
        throw new Error('Source release changed during synchronization; mirror remains a draft.');
    }
    await verifyTag({ repository: sourceRepository, tag: sourceTag, expectedObject: sourceTagResult.tagObject });
    await verifyTag({ repository: mirrorRepository, tag: targetTag, expectedObject: mirrorTagResult.tagObject });
    const latestSource = await api(`/repos/${sourceRepository}/releases/latest`);
    await api(`${prefix}/${mirror.id}`, { method: 'PATCH', body: {
        draft: false, make_latest: latestSource.id === source.id ? 'true' : 'false',
    } });
    const result = await api(`${prefix}/tags/${targetTag}`);
    checkMirror(result, source, sourceTag, targetTag, { assetRequired: true });
    if (result.draft) throw new Error('Mirror release is still a draft.');
    return { url: result.html_url, alreadyPublished: false, digest: asset.digest };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const [, , sourceTag, targetTag, sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT] = process.argv;
    console.log(JSON.stringify(await publishLsposedRelease({ sourceTag, targetTag, sdk, token: githubToken() })));
}
