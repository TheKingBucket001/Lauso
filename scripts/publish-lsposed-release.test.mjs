import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { publishLsposedRelease } from './publish-lsposed-release.mjs';

// Tests use injected clients only; no GitHub or device commands run in this process.
delete process.env.GITHUB_ACTIONS;

function fixture({ existing = false, badDownload = false, badUpload = false, badMetadata = false,
    unsignedMirror = false, movedMirror = false, wrongNotes = false, changedSource = false,
    historical = false, draft = false, uploadedDraft = false, draftPage = 1 } = {}) {
    const apk = Buffer.from('cloud-built APK fixture');
    const asset = { name: 'LauSo-v1.2.3.apk', size: apk.length,
        digest: `sha256:${createHash('sha256').update(apk).digest('hex')}` };
    const source = { id: 11, tag_name: 'v1.2.3', name: 'LauSo v1.2.3', body: '## Changes\n\n- Current change.\n',
        assets: [asset], draft: false, prerelease: false };
    let mirror = existing || draft ? { ...source, id: 7, tag_name: '9-1.2.3', draft,
        assets: draft && !uploadedDraft ? [] : [asset],
        body: wrongNotes ? 'other release notes' : source.body, html_url: 'https://example.test/mirror' } : null;
    const writes = [];
    let uploads = 0;
    let mirrorChecks = 0;
    let sourceReads = 0;
    const listPages = [];
    const dependencies = {
        verifyTag: async options => {
            if (options.repository.startsWith('Xposed-Modules-Repo')) {
                mirrorChecks++;
                if (unsignedMirror || (movedMirror && mirrorChecks >= 3)) throw new Error('Unverified/moved tag.');
            }
            return { tagObject: 'signed-tag-object', commit: 'signed-commit' };
        },
        download: async () => badDownload ? Buffer.from('corrupt download') : apk,
        inspectApk: async () => { if (badMetadata) throw new Error('APK metadata mismatch.'); },
        upload: async () => {
            uploads++;
            const uploaded = badUpload ? { ...asset, digest: 'wrong digest' } : asset;
            mirror.assets = [uploaded];
            return uploaded;
        },
        api: async (path, options = {}) => {
            if (options.method) writes.push([options.method, structuredClone(options.body)]);
            if (path.includes('/TheKingBucket001/')) {
                if (historical && path.endsWith('/latest')) return { id: 99 };
                sourceReads++;
                return structuredClone(changedSource && sourceReads > 1 ? { ...source, body: 'edited notes' } : source);
            }
            if (path.includes('?per_page=100&page=')) {
                const page = Number(path.split('page=').at(-1));
                listPages.push(page);
                if (draft && page < draftPage) {
                    return Array.from({ length: 100 }, (_, index) => ({ tag_name: `other-${page}-${index}` }));
                }
                return mirror ? [structuredClone(mirror)] : [];
            }
            if (path.includes('/tags/') && mirror?.draft) return null;
            if (options.method === 'POST') {
                mirror = { ...options.body, id: 7, assets: [], prerelease: false, html_url: 'https://example.test/mirror' };
            } else if (options.method === 'PATCH') {
                mirror = { ...mirror, ...options.body };
            }
            return structuredClone(mirror);
        },
    };
    return { options: { sourceTag: 'v1.2.3', targetTag: '9-1.2.3', dependencies },
        writes, listPages, uploads: () => uploads, mirror: () => mirror };
}

test('creates a draft, checks its asset and tags, then publishes the matching release', async () => {
    const f = fixture();
    const result = await publishLsposedRelease(f.options);
    assert.equal(result.alreadyPublished, false);
    assert.deepEqual(f.writes.map(([method]) => method), ['POST', 'PATCH']);
    assert.equal(f.writes[0][1].draft, true);
    assert.equal(f.writes[0][1].target_commitish, 'signed-commit');
    assert.equal(f.uploads(), 1);
    assert.equal(f.mirror().draft, false);
});

test('rerunning an identical published release checks it without remote writes', async () => {
    const f = fixture({ existing: true });
    assert.equal((await publishLsposedRelease(f.options)).alreadyPublished, true);
    assert.deepEqual(f.writes, []);
    assert.equal(f.uploads(), 0);
});

test('publishing a historical source release preserves the newer latest selection', async () => {
    const f = fixture({ historical: true });
    await publishLsposedRelease(f.options);
    assert.equal(f.writes.at(-1)[1].make_latest, 'false');
});

test('finds a draft on later list pages when the tag endpoint returns 404, and resumes it', async () => {
    const f = fixture({ draft: true, draftPage: 2 });
    const result = await publishLsposedRelease(f.options);
    assert.equal(result.alreadyPublished, false);
    assert.deepEqual(f.listPages, [1, 2]);
    assert.deepEqual(f.writes.map(([method]) => method), ['PATCH']);
    assert.equal(f.uploads(), 1);
    assert.equal(f.mirror().id, 7);
    assert.equal(f.mirror().draft, false);
});

test('resumes a matching uploaded draft without uploading the APK a second time', async () => {
    const f = fixture({ draft: true, uploadedDraft: true });
    await publishLsposedRelease(f.options);
    assert.deepEqual(f.writes.map(([method]) => method), ['PATCH']);
    assert.equal(f.uploads(), 0);
});

for (const [label, change] of [
    ['unsigned mirror tag', { unsignedMirror: true }],
    ['corrupt downloaded APK', { badDownload: true }],
    ['APK package/version mismatch', { badMetadata: true }],
    ['different existing release notes', { existing: true, wrongNotes: true }],
    ['different existing draft notes', { draft: true, wrongNotes: true }],
]) {
    test(`refuses ${label} before writing GitHub`, async () => {
        const f = fixture(change);
        await assert.rejects(publishLsposedRelease(f.options));
        assert.deepEqual(f.writes, []);
        assert.equal(f.uploads(), 0);
    });
}

for (const [label, change] of [
    ['an uploaded APK digest mismatch', { badUpload: true }],
    ['a tag that moves before publication', { movedMirror: true }],
    ['source release notes edited during synchronization', { changedSource: true }],
]) {
    test(`keeps the release draft after ${label}`, async () => {
        const f = fixture(change);
        await assert.rejects(publishLsposedRelease(f.options));
        assert.equal(f.mirror().draft, true);
        assert.equal(f.writes.some(([method]) => method === 'PATCH'), false);
    });
}
