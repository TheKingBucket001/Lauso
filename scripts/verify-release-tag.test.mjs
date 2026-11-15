import test from 'node:test';
import assert from 'node:assert/strict';
import { verifyReleaseTag } from './verify-release-tag.mjs';

function fixture(change = {}) {
    const ref = { object: { type: 'tag', sha: 'tag-object' } };
    const object = {
        tag: 'v1', object: { type: 'commit', sha: 'commit' },
        verification: { verified: true, reason: 'valid', signature: '-----BEGIN SSH SIGNATURE-----\nsignature' },
    };
    change.mutate?.(ref, object);
    let calls = 0;
    return {
        repository: 'owner/repo', tag: 'v1', expectedCommit: 'commit', expectedObject: 'tag-object',
        fetchImpl: async () => {
            calls++;
            if (change.httpError) return { ok: false, status: 403 };
            const result = calls === 2 ? object : calls === 3 && change.moved
                ? { object: { type: 'tag', sha: 'replaced-object' } } : ref;
            return { ok: true, json: async () => result };
        },
    };
}

test('accepts a GitHub-verified SSH tag for the expected commit and object', async () => {
    const result = await verifyReleaseTag(fixture());
    assert.equal(result.ssh, true);
    assert.equal(result.verified, true);
    assert.equal(result.commit, 'commit');
});

for (const [name, change, message] of [
    ['lightweight tag', { mutate: ref => { ref.object.type = 'commit'; } }, /annotated/],
    ['unsigned tag', { mutate: (_, obj) => { obj.verification = { verified: false, reason: 'unsigned' }; } }, /SSH signature/],
    ['verified GPG tag', { mutate: (_, obj) => { obj.verification.signature = '-----BEGIN PGP SIGNATURE-----'; } }, /SSH signature/],
    ['unverified SSH tag', { mutate: (_, obj) => { obj.verification.verified = false; } }, /SSH signature/],
    ['wrong commit', { mutate: (_, obj) => { obj.object.sha = 'other-commit'; } }, /different commit/],
    ['wrong object', { mutate: ref => { ref.object.sha = 'other-tag'; } }, /differs from/],
    ['wrong tag name', { mutate: (_, obj) => { obj.tag = 'v2'; } }, /unexpected tag/],
    ['tag changed during verification', { moved: true }, /moved during/],
    ['GitHub unavailable', { httpError: true }, /HTTP 403/],
]) {
    test(`rejects ${name}`, async () => {
        await assert.rejects(verifyReleaseTag(fixture(change)), message);
    });
}
