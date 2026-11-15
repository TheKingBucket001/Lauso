import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';

export function githubToken() {
    return process.env.GITHUB_TOKEN || process.env.GH_TOKEN
        || execFileSync('gh', ['auth', 'token'], { encoding: 'utf8' }).trim();
}

/** Refuse unsigned, non-SSH, unverified, moved, or unexpected release tags. */
export async function verifyReleaseTag({
    repository, tag, token, expectedCommit, expectedObject, fetchImpl = fetch,
}) {
    if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repository || '') || !tag) {
        throw new Error('A GitHub owner/repository and a tag are required.');
    }
    const prefix = `/repos/${repository}/git`;
    const refPath = `${prefix}/ref/tags/${encodeURIComponent(tag)}`;
    async function get(path) {
        const response = await fetchImpl(`https://api.github.com${path}`, {
            headers: {
                Accept: 'application/vnd.github+json',
                ...(token ? { Authorization: `Bearer ${token}` } : {}),
                'X-GitHub-Api-Version': '2022-11-28',
            },
            signal: AbortSignal.timeout(15_000),
        });
        if (!response.ok) throw new Error(`GitHub ${path}: HTTP ${response.status}`);
        return response.json();
    }
    const ref = await get(refPath);
    if (ref.object?.type !== 'tag') {
        throw new Error(`${repository}@${tag}: release tag must be annotated and SSH-signed.`);
    }
    if (expectedObject && ref.object.sha !== expectedObject) {
        throw new Error(`${repository}@${tag}: tag object differs from the pushed tag.`);
    }
    const object = await get(`${prefix}/tags/${ref.object.sha}`);
    if (object.tag !== tag || object.object?.type !== 'commit') {
        throw new Error(`${repository}@${tag}: unexpected tag name or target type.`);
    }
    const verification = object.verification;
    if (verification?.verified !== true || verification.reason !== 'valid'
            || !verification.signature?.startsWith('-----BEGIN SSH SIGNATURE-----')) {
        throw new Error(`${repository}@${tag}: GitHub must verify an SSH signature (reason: ${verification?.reason || 'missing'}).`);
    }
    if (expectedCommit && object.object.sha !== expectedCommit) {
        throw new Error(`${repository}@${tag}: tag points to a different commit.`);
    }
    const current = await get(refPath);
    if (current.object?.type !== 'tag' || current.object.sha !== ref.object.sha) {
        throw new Error(`${repository}@${tag}: tag moved during verification.`);
    }
    return {
        repository, tag, tagObject: ref.object.sha, commit: object.object.sha,
        ssh: true, verified: true, reason: verification.reason,
    };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const options = {};
    const names = { '--repo': 'repository', '--tag': 'tag', '--commit': 'expectedCommit', '--object': 'expectedObject' };
    for (let i = 2; i < process.argv.length; i += 2) {
        const name = names[process.argv[i]];
        if (!name || !process.argv[i + 1]) throw new Error('Usage: verify-release-tag.mjs --repo owner/repo --tag tag [--commit sha] [--object sha]');
        options[name] = process.argv[i + 1];
    }
    console.log(JSON.stringify(await verifyReleaseTag({ ...options, token: githubToken() })));
}
