// Contract tests for hooks/lib/markers.mjs (fc131c71, scenarios S4-S6). Oracle: the frozen task-scope
// signatures and decision D1/D7. markerRoot() reads os.tmpdir() on every call, so each test redirects
// TMPDIR/TEMP/TMP to a fresh directory; no marker is ever written under the real temp root.

import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import {
  mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, statSync, rmSync, existsSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname, basename, relative, isAbsolute } from 'node:path';
import {
  markerRoot, sanitizeKey, sessionMarkerPath, sharedMarkerPath, readMarker, writeMarker, createMarkerOnce,
} from '../lib/markers.mjs';

const TMP_VARS = ['TMPDIR', 'TEMP', 'TMP'];
let saved;
let fakeTmp;

beforeEach(() => {
  saved = {};
  for (const k of TMP_VARS) saved[k] = process.env[k];
  fakeTmp = mkdtempSync(join(tmpdir(), 'to-lib-markers-'));
  for (const k of TMP_VARS) process.env[k] = fakeTmp;
});

afterEach(() => {
  for (const k of TMP_VARS) {
    if (saved[k] === undefined) delete process.env[k];
    else process.env[k] = saved[k];
  }
  rmSync(fakeTmp, { recursive: true, force: true });
});

function underRoot(p) {
  const rel = relative(markerRoot(), p);
  return rel !== '' && !rel.startsWith('..') && !isAbsolute(rel);
}

// ---- markerRoot / sanitizeKey ----

test('S5: markerRoot is <os.tmpdir()>/task-orchestrator, read per call', () => {
  assert.equal(markerRoot(), join(tmpdir(), 'task-orchestrator'));
  assert.ok(markerRoot().startsWith(fakeTmp), `${markerRoot()} should be under ${fakeTmp}`);
  const other = mkdtempSync(join(tmpdir(), 'to-lib-markers-other-'));
  try {
    for (const k of TMP_VARS) process.env[k] = other;
    assert.equal(markerRoot(), join(other, 'task-orchestrator'));
  } finally {
    rmSync(other, { recursive: true, force: true });
  }
});

test('S5: sanitizeKey keeps [a-zA-Z0-9-] and replaces every other character with _', () => {
  assert.equal(sanitizeKey('abc-XYZ-019'), 'abc-XYZ-019');
  assert.equal(sanitizeKey('a b.c/d\\e:f'), 'a_b_c_d_e_f');
  assert.equal(sanitizeKey('a_b'), 'a_b');
  assert.equal(sanitizeKey('../x'), '___x');
  assert.equal(sanitizeKey('\u00fc\u4e2d'), '__');
});

test('S5 probe: sanitizeKey coerces non-strings with String()', () => {
  assert.equal(sanitizeKey(42), '42');
  assert.equal(sanitizeKey(null), 'null');
  assert.equal(sanitizeKey(undefined), 'undefined');
});

// ---- S5 sessionMarkerPath / sharedMarkerPath ----

test('S5: sessionMarkerPath(a/../b, phase-guard, x:y) = <root>/a____b/phase-guard-x_y.json', () => {
  assert.equal(
    sessionMarkerPath('a/../b', 'phase-guard', 'x:y'),
    join(markerRoot(), 'a____b', 'phase-guard-x_y.json'),
  );
});

test('S5: a falsy session id maps to the unknown directory', () => {
  for (const sid of [null, undefined, '']) {
    assert.equal(sessionMarkerPath(sid, 'skill-enforce'), join(markerRoot(), 'unknown', 'skill-enforce.json'));
  }
});

test('S5: no agentId (absent or empty) -> no suffix; agentId adds -<sanitized>', () => {
  assert.equal(sessionMarkerPath('s1', 'phase-guard'), join(markerRoot(), 's1', 'phase-guard.json'));
  assert.equal(sessionMarkerPath('s1', 'phase-guard', ''), join(markerRoot(), 's1', 'phase-guard.json'));
  assert.equal(sessionMarkerPath('s1', 'phase-guard', 'agent-7'), join(markerRoot(), 's1', 'phase-guard-agent-7.json'));
});

test('S5 probe: the marker name is sanitized too', () => {
  assert.equal(sessionMarkerPath('s1', 'a/b'), join(markerRoot(), 's1', 'a_b.json'));
});

test('S5 probe: hostile session/name/agent values stay exactly <root>/<dir>/<file>', () => {
  const hostile = ['..', '../..', '../../etc/passwd', '..\\..\\x', 'C:\\Windows', '/abs/path', 'a/b/c', '\u0000x', '.'];
  for (const v of hostile) {
    for (const p of [sessionMarkerPath(v, 'n'), sessionMarkerPath('s', v), sessionMarkerPath('s', 'n', v)]) {
      assert.ok(underRoot(p), `${JSON.stringify(v)} -> ${p}`);
      assert.equal(dirname(dirname(p)), markerRoot(), `${JSON.stringify(v)} -> ${p}`);
      assert.ok(p.endsWith('.json'), p);
    }
  }
});

test('S5 probe: session ids differing only by case yield different path strings', () => {
  assert.notEqual(sessionMarkerPath('Abc', 'n'), sessionMarkerPath('abc', 'n'));
});

test('S5: sharedMarkerPath(name) = <root>/<sanitized name>.json; ext override; empty ext', () => {
  assert.equal(sharedMarkerPath('retro-abc'), join(markerRoot(), 'retro-abc.json'));
  assert.equal(sharedMarkerPath('retro-abc', '.txt'), join(markerRoot(), 'retro-abc.txt'));
  assert.equal(sharedMarkerPath('setup-hint-h-2000-01-01', ''), join(markerRoot(), 'setup-hint-h-2000-01-01'));
  assert.equal(sharedMarkerPath('a b'), join(markerRoot(), 'a_b.json'));
});

test('S5 probe: sharedMarkerPath cannot escape the root', () => {
  for (const v of ['../x', '..\\x', '../../etc', '/abs', 'C:\\x']) {
    const p = sharedMarkerPath(v);
    assert.ok(underRoot(p), `${v} -> ${p}`);
    assert.equal(dirname(p), markerRoot(), `${v} -> ${p}`);
  }
});

// ---- S4 readMarker / writeMarker ----

test('S4: writeMarker then readMarker round-trips a nested object; writeMarker returns true', () => {
  const p = join(fakeTmp, 'm', 'one.json');
  const obj = { a: 1, nested: { list: [1, 'two', null, { z: true }] }, s: 'caf\u00e9' };
  assert.equal(writeMarker(p, obj), true);
  assert.deepEqual(readMarker(p), obj);
});

test('S4: writeMarker creates missing parent directories (mkdir -p)', () => {
  const p = join(fakeTmp, 'deep', 'er', 'still', 'm.json');
  assert.equal(writeMarker(p, { k: 'v' }), true);
  assert.deepEqual(readMarker(p), { k: 'v' });
});

test('S4: after a write the directory holds only the final file (no leftover *.tmp)', () => {
  const dir = join(fakeTmp, 'clean');
  const p = join(dir, 'final.json');
  assert.equal(writeMarker(p, { a: 1 }), true);
  assert.equal(writeMarker(p, { a: 2 }), true);
  assert.deepEqual(readdirSync(dir), [basename(p)]);
});

test('S4: overwriting with a smaller object reads back exactly the smaller object', () => {
  const p = join(fakeTmp, 'shrink', 'm.json');
  const big = { items: Array.from({ length: 200 }, (_, i) => ({ i, pad: 'x'.repeat(20) })) };
  assert.equal(writeMarker(p, big), true);
  assert.equal(writeMarker(p, { a: 1 }), true);
  assert.deepEqual(readMarker(p), { a: 1 });
  assert.ok(statSync(p).size < 40, 'file content was replaced, not overlaid');
  // the content is exactly one JSON document
  assert.deepEqual(JSON.parse(readFileSync(p, 'utf-8')), { a: 1 });
});

test('S4 probe: replaying the same write is idempotent', () => {
  const p = join(fakeTmp, 'replay', 'm.json');
  for (let i = 0; i < 3; i++) assert.equal(writeMarker(p, { same: true }), true);
  assert.deepEqual(readMarker(p), { same: true });
  assert.deepEqual(readdirSync(dirname(p)), ['m.json']);
});

test('S4: writeMarker returns false (no throw) when the parent path is a regular file', () => {
  const blocker = join(fakeTmp, 'blocker');
  writeFileSync(blocker, 'i am a file');
  const p = join(blocker, 'sub', 'm.json');
  let result;
  assert.doesNotThrow(() => { result = writeMarker(p, { a: 1 }); });
  assert.equal(result, false);
  assert.equal(readFileSync(blocker, 'utf-8'), 'i am a file', 'the blocking file is untouched');
});

test('S4: writeMarker returns false when the target is an existing directory, and removes its temp file', () => {
  const dir = join(fakeTmp, 'holder');
  const target = join(dir, 'target.json');
  mkdirSync(target, { recursive: true });
  writeFileSync(join(target, 'keep.txt'), 'x');
  let result;
  assert.doesNotThrow(() => { result = writeMarker(target, { a: 1 }); });
  assert.equal(result, false);
  assert.deepEqual(readdirSync(dir), ['target.json'], 'no stray *.tmp left next to the target');
  assert.ok(statSync(target).isDirectory());
  assert.equal(readFileSync(join(target, 'keep.txt'), 'utf-8'), 'x');
});

test('S4 probe: a value JSON cannot serialise (circular) -> false, no throw, nothing written', () => {
  const p = join(fakeTmp, 'circ', 'm.json');
  const circ = {};
  circ.self = circ;
  let result;
  assert.doesNotThrow(() => { result = writeMarker(p, circ); });
  assert.equal(result, false);
  assert.ok(!existsSync(p));
  if (existsSync(dirname(p))) assert.deepEqual(readdirSync(dirname(p)), []);
});

test('S4: readMarker returns the fallback for a missing file (default {} and a custom fallback)', () => {
  const p = join(fakeTmp, 'nope.json');
  assert.deepEqual(readMarker(p), {});
  assert.deepEqual(readMarker(p, { dflt: 1 }), { dflt: 1 });
});

test('S4: readMarker returns the fallback for invalid JSON, empty, array, null, string and number content', () => {
  const p = join(fakeTmp, 'bad.json');
  for (const content of ['{not json', '', '[1,2]', 'null', '"str"', '42', 'true']) {
    writeFileSync(p, content);
    assert.deepEqual(readMarker(p, { fb: true }), { fb: true }, JSON.stringify(content));
    assert.deepEqual(readMarker(p), {}, JSON.stringify(content));
  }
});

test('S4 probe: readMarker on a directory path -> fallback, no throw', () => {
  const d = join(fakeTmp, 'adir');
  mkdirSync(d);
  assert.deepEqual(readMarker(d, { fb: 1 }), { fb: 1 });
});

test('S4 probe: default fallback is not shared between calls', () => {
  const p = join(fakeTmp, 'nope2.json');
  const first = readMarker(p);
  first.mutated = true;
  assert.deepEqual(readMarker(p), {});
});

// ---- S6 createMarkerOnce ----

test('S6: createMarkerOnce is true on the first call and false afterwards; the file is empty', () => {
  const dir = join(fakeTmp, 'once');
  mkdirSync(dir);
  const p = join(dir, 'flag');
  assert.equal(createMarkerOnce(p), true);
  assert.equal(createMarkerOnce(p), false);
  assert.equal(createMarkerOnce(p), false);
  assert.equal(statSync(p).size, 0);
});

test('S6: createMarkerOnce never overwrites an existing file', () => {
  const p = join(fakeTmp, 'existing');
  writeFileSync(p, 'precious');
  assert.equal(createMarkerOnce(p), false);
  assert.equal(readFileSync(p, 'utf-8'), 'precious');
});

test('S6: createMarkerOnce is false (no throw) when the path is a directory', () => {
  const d = join(fakeTmp, 'adir');
  mkdirSync(d);
  let result;
  assert.doesNotThrow(() => { result = createMarkerOnce(d); });
  assert.equal(result, false);
  assert.ok(statSync(d).isDirectory());
});

test('S6: createMarkerOnce is false (no throw) when the parent is a regular file', () => {
  const blocker = join(fakeTmp, 'blocker');
  writeFileSync(blocker, 'x');
  let result;
  assert.doesNotThrow(() => { result = createMarkerOnce(join(blocker, 'flag')); });
  assert.equal(result, false);
});
