import { readFileSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { inflateRawSync } from 'node:zlib';

const ENTRY = 'dev.bucket.launcherslogan.hook.LauncherSloganModule';
const HOOK_PREFIX = 'dev/bucket/launcherslogan/hook/';
const OWN_PREFIX = 'dev/bucket/launcherslogan/';
const API_PREFIX = 'io/github/libxposed/api/';
const UI_PREFIXES = ['androidx/activity/', 'androidx/compose/', 'androidx/appcompat/',
    'androidx/fragment/', 'miuix/', 'top/yukonga/miuix/'];
const isUi = name => name === 'android/app/Activity'
    || UI_PREFIXES.some(prefix => name.startsWith(prefix));

// Inspect the built archives instead of treating Gradle text or source imports as
// proof of isolation. APK/JAR/AAR all use the same ordinary ZIP container format.
export function readZip(bytes, label = 'ZIP') {
    let end = -1;
    for (let offset = bytes.length - 22; offset >= Math.max(0, bytes.length - 65_557); offset--) {
        if (bytes.readUInt32LE(offset) === 0x06054b50
            && offset + 22 + bytes.readUInt16LE(offset + 20) === bytes.length) {
            end = offset;
            break;
        }
    }
    if (end < 0) throw new Error(`${label}: invalid ZIP directory`);
    const count = bytes.readUInt16LE(end + 10);
    if (bytes.readUInt16LE(end + 4) !== 0 || bytes.readUInt16LE(end + 6) !== 0
        || count !== bytes.readUInt16LE(end + 8) || count === 0xffff) {
        throw new Error(`${label}: split/ZIP64 archives are unsupported`);
    }
    const directoryEnd = bytes.readUInt32LE(end + 16) + bytes.readUInt32LE(end + 12);
    if (directoryEnd > end) throw new Error(`${label}: invalid ZIP directory bounds`);
    const entries = [];
    let cursor = bytes.readUInt32LE(end + 16);
    for (let index = 0; index < count; index++) {
        if (cursor + 46 > directoryEnd || bytes.readUInt32LE(cursor) !== 0x02014b50) {
            throw new Error(`${label}: invalid ZIP entry`);
        }
        const flags = bytes.readUInt16LE(cursor + 8);
        const method = bytes.readUInt16LE(cursor + 10);
        const packed = bytes.readUInt32LE(cursor + 20);
        const unpacked = bytes.readUInt32LE(cursor + 24);
        const nameLength = bytes.readUInt16LE(cursor + 28);
        const next = cursor + 46 + nameLength + bytes.readUInt16LE(cursor + 30)
            + bytes.readUInt16LE(cursor + 32);
        if (next > directoryEnd) throw new Error(`${label}: invalid ZIP entry bounds`);
        const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
        const local = bytes.readUInt32LE(cursor + 42);
        if (local + 30 > bytes.length || bytes.readUInt32LE(local) !== 0x04034b50) {
            throw new Error(`${label}: invalid ZIP local entry ${name}`);
        }
        const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
        if ((flags & 1) || start + packed > bytes.length || unpacked > 128 * 1024 * 1024) {
            throw new Error(`${label}: unsupported ZIP entry ${name}`);
        }
        entries.push({ name, read() {
            const input = bytes.subarray(start, start + packed);
            const result = method === 0 ? input : method === 8
                ? inflateRawSync(input, { maxOutputLength: 128 * 1024 * 1024 }) : null;
            if (!result || result.length !== unpacked) {
                throw new Error(`${label}: invalid ZIP content ${name}`);
            }
            return result;
        } });
        cursor = next;
    }
    return entries;
}

// JVM symbolic class references and descriptors survive normal Java compilation;
// unlike searching UTF-8 source text, they also cover generated/transitive code.
function classReferences(bytes, label) {
    if (bytes.readUInt32BE(0) !== 0xcafebabe) throw new Error(`${label}: invalid class`);
    const strings = new Map();
    const indices = [];
    let offset = 10;
    for (let index = 1, count = bytes.readUInt16BE(8); index < count; index++) {
        const tag = bytes.readUInt8(offset++);
        if (tag === 1) {
            const length = bytes.readUInt16BE(offset);
            offset += 2;
            if (offset + length > bytes.length) throw new Error(`${label}: truncated class`);
            strings.set(index, bytes.subarray(offset, offset + length).toString('utf8'));
            offset += length;
        } else if (tag === 7) {
            indices.push(bytes.readUInt16BE(offset));
            offset += 2;
        } else if ([3, 4, 9, 10, 11, 12, 17, 18].includes(tag)) offset += 4;
        else if ([5, 6].includes(tag)) { offset += 8; index++; }
        else if ([8, 16, 19, 20].includes(tag)) offset += 2;
        else if (tag === 15) offset += 3;
        else throw new Error(`${label}: unsupported constant pool tag ${tag}`);
    }
    const refs = new Set(indices.map(index => strings.get(index)).filter(Boolean));
    // Method/field descriptors can refer to classes without a CONSTANT_Class.
    for (const value of strings.values()) {
        if (/^[([L]/.test(value)) {
            for (const match of value.matchAll(/L([A-Za-z0-9_$/]+)[;<]/g)) refs.add(match[1]);
        }
    }
    return refs;
}

function archiveClasses(file, withReferences = false) {
    const definitions = new Set();
    const references = new Set();
    function visit(bytes, label, nested) {
        for (const entry of readZip(bytes, label)) {
            if (entry.name.endsWith('.class') && !entry.name.startsWith('META-INF/versions/')) {
                const name = entry.name.slice(0, -6);
                definitions.add(name);
                if (withReferences) {
                    for (const ref of classReferences(entry.read(), `${label}!${entry.name}`)) references.add(ref);
                }
            } else if (!nested && /^(classes\.jar|libs\/[^/]+\.jar)$/.test(entry.name)) {
                visit(entry.read(), `${label}!${entry.name}`, true);
            }
        }
    }
    visit(readFileSync(file), file, false);
    return { definitions, references };
}

function resourceFreeHookViolations(file) {
    const entries = readZip(readFileSync(file), file);
    const violations = [];
    for (const entry of entries) {
        if ((/^(res|assets)\//.test(entry.name) && !entry.name.endsWith('/')) || entry.name === 'resources.arsc'
                || (/^(R|public)\.txt$/.test(entry.name) && entry.read().toString('utf8').trim())) {
            violations.push(`hook archive must not contain module resources: ${entry.name}`);
        }
    }
    const manifests = entries.filter(entry => entry.name === 'AndroidManifest.xml');
    if (manifests.length !== 1) {
        violations.push('hook archive must contain exactly one AndroidManifest.xml');
    } else {
        // AGP stores a text manifest in an AAR. Comments must not be mistaken for
        // declarations, while application attributes/components must not leak into app.
        const manifest = manifests[0].read().toString('utf8').replace(/<!--[\s\S]*?-->/g, '');
        if (!/<manifest\b/.test(manifest) || /<(?:[\w.-]+:)?(?:application|activity|activity-alias|service|receiver|provider|instrumentation)\b/.test(manifest)) {
            violations.push('hook manifest must not declare application attributes or Android components');
        }
    }
    return violations;
}

export function dexClasses(bytes, label = 'DEX') {
    if (bytes.length < 112 || !/^dex\n0\d\d\0$/.test(bytes.subarray(0, 8).toString('ascii'))
        || bytes.readUInt32LE(40) !== 0x12345678) throw new Error(`${label}: invalid DEX`);
    const section = (countOffset, width) => {
        const count = bytes.readUInt32LE(countOffset);
        const offset = bytes.readUInt32LE(countOffset + 4);
        if (offset + count * width > bytes.length) throw new Error(`${label}: invalid DEX section`);
        return { count, offset };
    };
    const strings = section(56, 4);
    const types = section(64, 4);
    const definitions = section(96, 32);
    const result = [];
    for (let index = 0; index < definitions.count; index++) {
        const type = bytes.readUInt32LE(definitions.offset + index * 32);
        if (type >= types.count) throw new Error(`${label}: invalid DEX class index`);
        const string = bytes.readUInt32LE(types.offset + type * 4);
        if (string >= strings.count) throw new Error(`${label}: invalid DEX type index`);
        let offset = bytes.readUInt32LE(strings.offset + string * 4);
        let byte;
        for (let length = 0; ; length++) {
            if (length === 5 || offset >= bytes.length) throw new Error(`${label}: invalid DEX string`);
            byte = bytes[offset++];
            if (!(byte & 0x80)) break;
        }
        const end = bytes.indexOf(0, offset);
        if (end < 0) throw new Error(`${label}: unterminated DEX string`);
        const descriptor = bytes.subarray(offset, end).toString('utf8');
        if (!/^L[^;]+;$/.test(descriptor)) throw new Error(`${label}: invalid DEX class descriptor`);
        result.push(descriptor.slice(1, -1));
    }
    return result;
}

export function verifyModuleBoundaries({ report, hookAar, apk }) {
    if (report.schemaVersion !== 1 || !/^(debug|release)$/.test(report.variant)) {
        throw new Error('Expected a Gradle-resolved schemaVersion=1 debug/release report');
    }
    const violations = [];
    const require = (condition, message) => { if (!condition) violations.push(message); };
    const variant = report.variant;
    const cache = new Map();
    const inspect = file => {
        if (!cache.has(file)) cache.set(file, archiveClasses(file));
        return cache.get(file);
    };
    const configurations = {};
    for (const project of [':app', ':hook', ':shared']) {
        const data = report.projects?.[project];
        if (!data || !Array.isArray(data.declared)) throw new Error(`Missing resolved project ${project}`);
        configurations[project] = {};
        for (const kind of ['Compile', 'Runtime']) {
            const name = `${variant}${kind}Classpath`;
            const artifacts = data.configurations?.[name];
            if (!Array.isArray(artifacts)) throw new Error(`Missing resolved ${project}:${name}`);
            const definitions = new Set();
            for (const artifact of artifacts) {
                if (typeof artifact.file !== 'string' || !statSync(artifact.file).isFile()) {
                    throw new Error(`Missing resolved artifact in ${project}:${name}`);
                }
                for (const definition of inspect(artifact.file).definitions) definitions.add(definition);
            }
            configurations[project][kind] = { artifacts, definitions };
        }
    }
    const app = configurations[':app'];
    const hook = configurations[':hook'];
    const shared = configurations[':shared'];
    const hookDeclarations = report.projects[':app'].declared.filter(dep => dep.projectPath === ':hook');
    require(hookDeclarations.length === 1 && hookDeclarations[0].configuration === 'runtimeOnly',
        'app must declare hook exactly once, using runtimeOnly');
    require(!app.Compile.artifacts.some(a => a.projectPath === ':hook')
        && ![...app.Compile.definitions].some(name => name.startsWith(HOOK_PREFIX)),
    'app compile classpath must not expose hook classes');
    require(![...app.Compile.definitions].some(name => name.startsWith(API_PREFIX)),
        'app compile classpath must not expose libxposed API');
    require(app.Runtime.artifacts.some(a => a.projectPath === ':hook'),
        'app runtime classpath must contain the hook project');
    require(hook.Compile.definitions.has(`${API_PREFIX}XposedModule`),
        'hook compile classpath must resolve the libxposed API');
    for (const [name, data] of [['hook', hook], ['shared', shared]]) {
        for (const [kind, config] of Object.entries(data)) {
            require(!config.artifacts.some(a => a.projectPath === ':app')
                && ![...config.definitions].some(isUi), `${name} ${kind} classpath must not expose UI libraries`);
            if (kind === 'Runtime' || name === 'shared') {
                require(![...config.definitions].some(type => type.startsWith(API_PREFIX)),
                    `${name} ${kind} classpath must not package libxposed API`);
            }
            if (name === 'shared') {
                require(!config.artifacts.some(a => a.projectPath === ':hook'),
                    'shared must not depend on hook');
            }
        }
    }
    violations.push(...resourceFreeHookViolations(hookAar));
    const builtHook = archiveClasses(hookAar, true);
    require(builtHook.definitions.has(ENTRY.replaceAll('.', '/')), 'built hook archive must contain its module entry');
    require([...builtHook.definitions].every(name => name.startsWith(HOOK_PREFIX)),
        'hook archive must only define classes in its own hook package');
    const builtShared = hook.Compile.artifacts.filter(a => a.projectPath === ':shared')
        .map(a => archiveClasses(a.file, true));
    const sharedClasses = new Set(builtShared.flatMap(data => [...data.definitions]));
    require(sharedClasses.size > 0, 'hook must resolve compiled shared project classes');
    for (const reference of new Set(builtShared.flatMap(data => [...data.references]))) {
        require(!isUi(reference) && !(reference.startsWith(OWN_PREFIX) && !sharedClasses.has(reference)),
            `shared bytecode references app/UI class ${reference}`);
    }
    for (const reference of builtHook.references) {
        require(!isUi(reference) && !(reference.startsWith(OWN_PREFIX)
            && !reference.startsWith(HOOK_PREFIX) && !sharedClasses.has(reference)),
        `hook bytecode references app/UI class ${reference}`);
    }
    const entries = readZip(readFileSync(apk), apk);
    const readOnly = name => {
        const matching = entries.filter(entry => entry.name === name);
        require(matching.length === 1, `APK must contain exactly one ${name}`);
        return matching.length === 1 ? matching[0].read().toString('utf8').trim() : '';
    };
    const lines = text => text.split(/\r?\n/).map(line => line.trim())
        .filter(line => line && !line.startsWith('#'));
    require(JSON.stringify(lines(readOnly('META-INF/xposed/java_init.list'))) === JSON.stringify([ENTRY]),
        'APK Xposed registration must name only the isolated hook entry');
    require(JSON.stringify(lines(readOnly('META-INF/xposed/scope.list'))) === JSON.stringify(['com.android.launcher']),
        'APK static scope must contain only com.android.launcher');
    const props = new Map(lines(readOnly('META-INF/xposed/module.prop')).map(line => {
        const separator = line.indexOf('=');
        return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
    }));
    require(props.get('minApiVersion') === '101' && props.get('targetApiVersion') === '102'
        && props.get('staticScope') === 'true', 'APK modern Xposed API/static-scope contract changed');
    require(!entries.some(entry => entry.name === 'assets/xposed_init'),
        'APK must not add a legacy xposed_init entry');
    const definitions = entries.filter(entry => /^classes\d*\.dex$/.test(entry.name))
        .flatMap(entry => dexClasses(entry.read(), entry.name));
    require(definitions.filter(name => name === ENTRY.replaceAll('.', '/')).length === 1,
        'APK DEX must define the registered hook entry exactly once');
    require(!definitions.some(name => name.startsWith(API_PREFIX)), 'APK DEX must not bundle libxposed API definitions');
    if (violations.length) {
        const error = new Error(violations.join('\n'));
        error.violations = violations;
        throw error;
    }
    const digest = file => createHash('sha256').update(readFileSync(file)).digest('hex');
    return { passed: true, variant, entry: ENTRY, hookClasses: builtHook.definitions.size,
        apkClasses: definitions.length, resolvedArtifacts: cache.size,
        hookSha256: digest(hookAar), apkSha256: digest(apk) };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        const args = process.argv.slice(2);
        const options = {};
        for (let index = 0; index < args.length; index += 2) {
            if (!['--report', '--hook-aar', '--apk'].includes(args[index]) || !args[index + 1]) {
                throw new Error('Usage: node scripts/verify-module-boundaries.mjs --report report.json --hook-aar hook.aar --apk app.apk');
            }
            options[args[index].slice(2)] = resolve(args[index + 1]);
        }
        if (!options.report || !options['hook-aar'] || !options.apk) throw new Error('Required: --report, --hook-aar, --apk');
        const result = verifyModuleBoundaries({ report: JSON.parse(readFileSync(options.report, 'utf8')),
            hookAar: options['hook-aar'], apk: options.apk });
        process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
    } catch (error) {
        process.stderr.write(`Module boundary verification failed:\n${error.message}\n`);
        process.exitCode = 1;
    }
}
