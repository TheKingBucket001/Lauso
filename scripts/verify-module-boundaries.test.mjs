import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { deflateRawSync } from 'node:zlib';
import { readZip, verifyModuleBoundaries } from './verify-module-boundaries.mjs';

const ENTRY = 'dev/bucket/launcherslogan/hook/LauncherSloganModule';
const SHARED = 'dev/bucket/launcherslogan/VerificationContract';
const API = 'io/github/libxposed/api/XposedModule';

// Small binary fixtures exercise the same archive, constant-pool and DEX paths
// as real artifacts. They are parser fixtures, not claims of an AGP build.
function classFile(name, references = []) {
    const utf8 = text => {
        const bytes = Buffer.from(text);
        const head = Buffer.alloc(3);
        head[0] = 1;
        head.writeUInt16BE(bytes.length, 1);
        return Buffer.concat([head, bytes]);
    };
    const constants = [];
    for (const [index, text] of [name, 'java/lang/Object', ...references].entries()) {
        const ref = Buffer.alloc(3);
        ref[0] = 7;
        ref.writeUInt16BE(index * 2 + 1, 1);
        constants.push(utf8(text), ref);
    }
    const header = Buffer.alloc(10);
    header.writeUInt32BE(0xcafebabe);
    header.writeUInt16BE(61, 6);
    header.writeUInt16BE(constants.length + 1, 8);
    const body = Buffer.alloc(14);
    body.writeUInt16BE(0x21);
    body.writeUInt16BE(2, 2);
    body.writeUInt16BE(4, 4);
    return Buffer.concat([header, ...constants, body]);
}

function zip(entries) {
    const local = [];
    const central = [];
    let offset = 0;
    for (const [name, value] of entries) {
        const nameBytes = Buffer.from(name);
        const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
        const packed = deflateRawSync(bytes);
        let crc = 0xffffffff;
        for (const byte of bytes) {
            crc ^= byte;
            for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0);
        }
        crc = (crc ^ 0xffffffff) >>> 0;
        const header = Buffer.alloc(30);
        header.writeUInt32LE(0x04034b50);
        header.writeUInt16LE(20, 4);
        header.writeUInt16LE(8, 8);
        header.writeUInt32LE(crc, 14);
        header.writeUInt32LE(packed.length, 18);
        header.writeUInt32LE(bytes.length, 22);
        header.writeUInt16LE(nameBytes.length, 26);
        local.push(header, nameBytes, packed);
        const directory = Buffer.alloc(46);
        directory.writeUInt32LE(0x02014b50);
        directory.writeUInt16LE(20, 4);
        directory.writeUInt16LE(20, 6);
        directory.writeUInt16LE(8, 10);
        directory.writeUInt32LE(crc, 16);
        directory.writeUInt32LE(packed.length, 20);
        directory.writeUInt32LE(bytes.length, 24);
        directory.writeUInt16LE(nameBytes.length, 28);
        directory.writeUInt32LE(offset, 42);
        central.push(directory, nameBytes);
        offset += header.length + nameBytes.length + packed.length;
    }
    const directory = Buffer.concat(central);
    const end = Buffer.alloc(22);
    end.writeUInt32LE(0x06054b50);
    end.writeUInt16LE(entries.length, 8);
    end.writeUInt16LE(entries.length, 10);
    end.writeUInt32LE(directory.length, 12);
    end.writeUInt32LE(offset, 16);
    return Buffer.concat([...local, directory, end]);
}

function dex(names) {
    const descriptors = names.map(name => Buffer.from(`L${name};\0`));
    const stringsOffset = 112;
    const typesOffset = stringsOffset + names.length * 4;
    const classesOffset = typesOffset + names.length * 4;
    const dataOffset = classesOffset + names.length * 32;
    const bytes = Buffer.alloc(dataOffset + descriptors.reduce((sum, string) => sum + string.length + 2, 0));
    bytes.write('dex\n039\0', 0, 'ascii');
    bytes.writeUInt32LE(bytes.length, 32);
    bytes.writeUInt32LE(112, 36);
    bytes.writeUInt32LE(0x12345678, 40);
    for (const [position, offset] of [[56, stringsOffset], [64, typesOffset], [96, classesOffset]]) {
        bytes.writeUInt32LE(names.length, position);
        bytes.writeUInt32LE(offset, position + 4);
    }
    let cursor = dataOffset;
    descriptors.forEach((descriptor, index) => {
        bytes.writeUInt32LE(cursor, stringsOffset + index * 4);
        bytes.writeUInt32LE(index, typesOffset + index * 4);
        bytes.writeUInt32LE(index, classesOffset + index * 32);
        bytes[cursor++] = (descriptor.length - 1) | 0x80;
        bytes[cursor++] = 0;
        descriptor.copy(bytes, cursor);
        cursor += descriptor.length;
    });
    return bytes;
}

function fixture(context) {
    const root = mkdtempSync(join(tmpdir(), 'lauso-boundary-'));
    context.after(() => rmSync(root, { recursive: true, force: true }));
    function archive(name, entries) {
        const file = join(root, name);
        writeFileSync(file, zip(entries));
        return file;
    }
    const sharedJar = archive('shared.jar', [[`${SHARED}.class`, classFile(SHARED)]]);
    const apiJar = archive('api.jar', [[`${API}.class`, classFile(API)]]);
    const hookEntries = [
        ['AndroidManifest.xml', '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><uses-sdk android:minSdkVersion="26" /></manifest>'],
        ['R.txt', ''],
        ['classes.jar', zip([[`${ENTRY}.class`, classFile(ENTRY, [API, SHARED])]])],
    ];
    const hookAar = archive('hook.aar', hookEntries);
    const rewriteHook = () => writeFileSync(hookAar, zip(hookEntries));
    const sharedArtifact = { file: sharedJar, projectPath: ':shared', component: 'project :shared' };
    const apiArtifact = { file: apiJar, projectPath: null, component: 'opaque local artifact' };
    const hookArtifact = { file: hookAar, projectPath: ':hook', component: 'project :hook' };
    const configs = (compile, runtime) => ({ releaseCompileClasspath: compile, releaseRuntimeClasspath: runtime });
    const report = { schemaVersion: 1, variant: 'release', projects: {
        ':app': { declared: [{ configuration: 'runtimeOnly', projectPath: ':hook' }],
            configurations: configs([sharedArtifact], [sharedArtifact, hookArtifact]) },
        ':hook': { declared: [{ configuration: 'implementation', projectPath: ':shared' }],
            configurations: configs([sharedArtifact, apiArtifact], [sharedArtifact]) },
        ':shared': { declared: [], configurations: configs([], []) },
    } };
    const apkEntries = [
        ['META-INF/xposed/java_init.list', ENTRY.replaceAll('/', '.')],
        ['META-INF/xposed/scope.list', 'com.android.launcher\n'],
        ['META-INF/xposed/module.prop', 'minApiVersion=101\ntargetApiVersion=102\nstaticScope=true\n'],
        ['classes.dex', dex([ENTRY, SHARED])],
    ];
    const apk = join(root, 'app.apk');
    const rewriteApk = () => writeFileSync(apk, zip(apkEntries));
    rewriteApk();
    return { root, report, hookAar, hookEntries, rewriteHook, apk, archive, apkEntries, rewriteApk, apiArtifact, sharedArtifact,
        verify() { return verifyModuleBoundaries({ report, hookAar, apk }); } };
}

test('passes genuine runtime-only graph and compressed nested class archives', context => {
    const result = fixture(context).verify();
    assert.equal(result.passed, true);
    assert.equal(result.hookClasses, 1);
    assert.equal(result.apkClasses, 2);
    assert.match(result.apkSha256, /^[a-f0-9]{64}$/);
});

test('rejects an implementation declaration even when artifact graph looks isolated', context => {
    const f = fixture(context);
    f.report.projects[':app'].declared[0].configuration = 'implementation';
    assert.throws(f.verify, /app must declare hook exactly once, using runtimeOnly/);
});

test('rejects libxposed compile visibility by class definition, without trusting coordinates', context => {
    const f = fixture(context);
    f.report.projects[':app'].configurations.releaseCompileClasspath.push(f.apiArtifact);
    assert.throws(f.verify, /app compile classpath must not expose libxposed API/);
});

test('rejects hook classes on app compile classpath', context => {
    const f = fixture(context);
    f.report.projects[':app'].configurations.releaseCompileClasspath.push({ file: f.hookAar, projectPath: ':hook' });
    assert.throws(f.verify, /app compile classpath must not expose hook classes/);
});

test('rejects UI binaries in hook dependencies under an opaque component name', context => {
    const f = fixture(context);
    const type = 'androidx/compose/runtime/Composer';
    const file = f.archive('opaque.jar', [[`${type}.class`, classFile(type)]]);
    f.report.projects[':hook'].configurations.releaseCompileClasspath.push({ file });
    assert.throws(f.verify, /hook Compile classpath must not expose UI libraries/);
});

test('rejects module UI references from compiled hook bytecode', context => {
    const f = fixture(context);
    f.hookEntries.find(([name]) => name === 'classes.jar')[1] = zip([[`${ENTRY}.class`,
        classFile(ENTRY, [SHARED, 'dev/bucket/launcherslogan/MainActivity'])]]);
    f.rewriteHook();
    assert.throws(f.verify, /hook bytecode references app\/UI class dev\/bucket\/launcherslogan\/MainActivity/);
});

test('allows framework resources needed to inspect the external Launcher', context => {
    const f = fixture(context);
    f.hookEntries.find(([name]) => name === 'classes.jar')[1] = zip([[`${ENTRY}.class`,
        classFile(ENTRY, [SHARED, 'android/content/res/Resources'])]]);
    f.rewriteHook();
    assert.equal(f.verify().passed, true);
});

for (const [name, content] of [
    ['res/values/strings.xml', '<resources><string name="unused">test</string></resources>'],
    ['assets/a', 'not needed by hook'],
    ['R.txt', 'int string unused 0x0'],
]) {
    test(`rejects hook module resources in ${name}`, context => {
        const f = fixture(context);
        if (name === 'R.txt') f.hookEntries.find(([entry]) => entry === name)[1] = content;
        else f.hookEntries.push([name, content]);
        f.rewriteHook();
        assert.throws(f.verify, /hook archive must not contain module resources/);
    });
}

test('rejects Android components declared only in the hook manifest', context => {
    const f = fixture(context);
    f.hookEntries[0][1] = '<manifest><application><activity android:name=".HiddenActivity" /></application></manifest>';
    f.rewriteHook();
    assert.throws(f.verify, /hook manifest must not declare/);
});

test('allows an empty hook manifest and ignores commented component examples', context => {
    const f = fixture(context);
    f.hookEntries[0][1] = '<manifest><!-- <application><activity android:name=".Example" /></application> --></manifest>';
    f.rewriteHook();
    assert.equal(f.verify().passed, true);
});

test('rejects UI references hidden inside compiled shared classes', context => {
    const f = fixture(context);
    writeFileSync(f.sharedArtifact.file, zip([[`${SHARED}.class`, classFile(SHARED, ['android/app/Activity'])]]));
    assert.throws(f.verify, /shared bytecode references app\/UI class android\/app\/Activity/);
});

test('rejects shared depending on hook even through runtime configuration', context => {
    const f = fixture(context);
    f.report.projects[':shared'].configurations.releaseRuntimeClasspath.push({ file: f.hookAar, projectPath: ':hook' });
    assert.throws(f.verify, /shared must not depend on hook/);
});

test('rejects duplicate Xposed registration entries in the actual APK', context => {
    const f = fixture(context);
    f.apkEntries.push(f.apkEntries[0]);
    f.rewriteApk();
    assert.throws(f.verify, /APK must contain exactly one META-INF\/xposed\/java_init.list/);
});

test('rejects a changed scope and legacy bootstrap', context => {
    const f = fixture(context);
    f.apkEntries[1][1] = 'com.android.launcher\nandroid';
    f.apkEntries.push(['assets/xposed_init', ENTRY]);
    f.rewriteApk();
    assert.throws(f.verify, error => /static scope/.test(error.message) && /legacy/.test(error.message));
});

test('rejects missing registered entry in the APK DEX', context => {
    const f = fixture(context);
    f.apkEntries[3][1] = dex([SHARED]);
    f.rewriteApk();
    assert.throws(f.verify, /APK DEX must define the registered hook entry exactly once/);
});

test('rejects libxposed definitions packaged in a second DEX', context => {
    const f = fixture(context);
    f.apkEntries.push(['classes2.dex', dex([API])]);
    f.rewriteApk();
    assert.throws(f.verify, /APK DEX must not bundle libxposed API/);
});

test('fails closed for missing resolved configurations or invalid archives', context => {
    const f = fixture(context);
    delete f.report.projects[':hook'].configurations.releaseRuntimeClasspath;
    assert.throws(f.verify, /Missing resolved :hook:releaseRuntimeClasspath/);
    assert.throws(() => readZip(Buffer.from('not a ZIP')), /invalid ZIP directory/);
});
