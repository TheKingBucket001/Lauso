import { readFileSync, existsSync } from 'node:fs';
import { dirname, relative, resolve, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { verifyReleaseTag } from './verify-release-tag.mjs';

if (process.env.GITHUB_ACTIONS === 'true') {
  throw new Error('Git commits and SSH release tags must be created on the local laptop.');
}

const projectRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const owner = process.env.GITHUB_OWNER || 'TheKingBucket001';
const repo = process.env.GITHUB_REPOSITORY_NAME || 'Lauso';
const configuredVersion = /versionName\s+'([^']+)'/.exec(
  readFileSync(resolve(projectRoot, 'app/build.gradle'), 'utf8'),
)?.[1];
const releaseVersion = process.env.RELEASE_VERSION || configuredVersion;
if (!configuredVersion || releaseVersion !== configuredVersion) {
  throw new Error('发行版本必须与 app/build.gradle 的 versionName 一致。');
}
const releaseTag = `v${releaseVersion}`;
const fullRepo = `${owner}/${repo}`;
const notes = resolve(projectRoot, 'release-notes', `${releaseTag}.md`);
if (!existsSync(notes) || !readFileSync(notes, 'utf8').trim()) {
  throw new Error(`缺少本版本的独立更新说明：release-notes/${releaseTag}.md`);
}
if (existsSync(resolve(projectRoot, '.git'))) {
  let existingTag = false;
  try {
    execFileSync('git', ['rev-parse', '--verify', `refs/tags/${releaseTag}`], {
      cwd: projectRoot, stdio: 'ignore',
    });
    existingTag = true;
  } catch {
    // A fresh version must not already have a local tag.
  }
  if (existingTag) throw new Error(`${releaseTag} 已存在；拒绝修改仓库或发行 Secrets。`);
}
let token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
if (!token) {
  try {
    token = execFileSync('gh', ['auth', 'token'], { encoding: 'utf8' }).trim();
  } catch {
    throw new Error('缺少 GITHUB_TOKEN/GH_TOKEN，且 gh 未登录。');
  }
}

function readProperties(file) {
  const values = {};
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const match = line.match(/^([^#:=]+)=(.*)$/);
    if (match) values[match[1].trim()] = match[2].trim();
  }
  return values;
}

const propertiesFile = resolve(projectRoot, 'local.properties');
const properties = readProperties(propertiesFile);
const keyPath = resolve(projectRoot, properties.RELEASE_STORE_FILE || 'lauso-release.keystore');
const relativeKeyPath = relative(projectRoot, keyPath);
if (!relativeKeyPath || relativeKeyPath.startsWith('..') || isAbsolute(relativeKeyPath) || !existsSync(keyPath)) {
  throw new Error('正式 keystore 必须存在于项目目录内，且路径由 local.properties 指定。');
}
for (const name of ['RELEASE_STORE_PASSWORD', 'RELEASE_KEY_ALIAS', 'RELEASE_KEY_PASSWORD']) {
  if (!properties[name]) throw new Error(`local.properties 缺少 ${name}`);
}

async function github(path, options = {}) {
  const response = await fetch(`https://api.github.com${path}`, {
    ...options,
    signal: AbortSignal.timeout(30_000),
    headers: {
      Accept: 'application/vnd.github+json',
      Authorization: `Bearer ${token}`,
      'X-GitHub-Api-Version': '2022-11-28',
      ...(options.headers || {}),
    },
  });
  const body = await response.text();
  let data;
  try { data = body ? JSON.parse(body) : {}; } catch { data = { raw: body }; }
  if (!response.ok) {
    throw new Error(`GitHub API ${response.status}: ${data.message || body}`);
  }
  return data;
}

function git(args) {
  execFileSync('git', args, { cwd: projectRoot, stdio: 'inherit' });
}

async function ensureRepository() {
  try {
    return await github(`/repos/${owner}/${repo}`);
  } catch (error) {
    if (!String(error.message).startsWith('GitHub API 404')) throw error;
    return github('/user/repos', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        name: repo,
        description: 'LauSo：为 ColorOS Launcher 图标长按菜单添加自定义标语。',
        private: false,
        has_issues: true,
        has_projects: false,
        has_wiki: false,
      }),
    });
  }
}

function ensureGitPush() {
  if (!existsSync(resolve(projectRoot, '.git'))) git(['init']);
  git(['branch', '-M', 'main']);
  const sshRemote = `git@github.com:${owner}/${repo}.git`;
  try { git(['remote', 'set-url', 'origin', sshRemote]); }
  catch { git(['remote', 'add', 'origin', sshRemote]); }
  git(['config', 'user.name', 'TheKingBucket001']);
  git(['config', 'user.email', '186387631+TheKingBucket001@users.noreply.github.com']);
  git(['config', 'gpg.format', 'ssh']);
  git(['config', 'user.signingkey', `${process.env.USERPROFILE}/.ssh/id_ed25519.pub`]);
  git(['config', 'gpg.ssh.program', 'R:/Git/usr/bin/ssh-keygen.exe']);
  git(['config', 'commit.gpgsign', 'true']);
  git(['config', 'tag.gpgSign', 'true']);
  git(['add', '-A']);
  try { git(['diff', '--cached', '--quiet']); }
  catch { git(['commit', '-m', `发布 LauSo ${releaseTag}`]); }
  git(['push', '-u', 'origin', 'main']);
}

function setSecret(name, value) {
  execFileSync('gh', ['secret', 'set', name, '--repo', fullRepo], {
    cwd: projectRoot,
    input: `${value}\n`,
    stdio: ['pipe', 'inherit', 'inherit'],
  });
}

async function publishReleaseTag() {
  git(['tag', '-s', releaseTag, '-m', `LauSo ${releaseTag}`]);
  git(['push', 'origin', releaseTag]);
  const expectedObject = execFileSync('git', ['rev-parse', releaseTag], {
    cwd: projectRoot, encoding: 'utf8',
  }).trim();
  const expectedCommit = execFileSync('git', ['rev-parse', `${releaseTag}^{}`], {
    cwd: projectRoot, encoding: 'utf8',
  }).trim();
  console.log(await verifyReleaseTag({
    repository: fullRepo, tag: releaseTag, token, expectedObject, expectedCommit,
  }));
}

try {
  await github(`/repos/${fullRepo}/git/ref/tags/${encodeURIComponent(releaseTag)}`);
  throw new Error(`${releaseTag} 远端标签已存在；拒绝推送或更新发行 Secrets。`);
} catch (error) {
  if (!String(error.message).startsWith('GitHub API 404')) throw error;
}
await ensureRepository();
ensureGitPush();
const keyBase64 = readFileSync(keyPath).toString('base64');
setSecret('RELEASE_KEYSTORE_BASE64', keyBase64);
setSecret('RELEASE_STORE_PASSWORD', properties.RELEASE_STORE_PASSWORD);
setSecret('RELEASE_KEY_ALIAS', properties.RELEASE_KEY_ALIAS);
setSecret('RELEASE_KEY_PASSWORD', properties.RELEASE_KEY_PASSWORD);
await publishReleaseTag();
console.log(`已推送 ${fullRepo}，并更新 RELEASE_* Secrets。`);
