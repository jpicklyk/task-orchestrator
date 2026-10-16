// Contract tests for hooks/lib/stdin.mjs (fc131c71, scenarios S1-S2). Oracle: the frozen task-scope
// signatures - parseHookInput(text) strips one leading U+FEFF and returns a plain object or null;
// readHookInput() is the parse of fd 0, null on a read error. readHookInput reads fd 0, so it is driven
// through a throwaway subprocess whose stdin the test controls.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { parseHookInput } from '../lib/stdin.mjs';

const STDIN_LIB = pathToFileURL(fileURLToPath(new URL('../lib/stdin.mjs', import.meta.url))).href;

const scratch = mkdtempSync(join(tmpdir(), 'to-lib-stdin-'));
after(() => rmSync(scratch, { recursive: true, force: true }));

const RUNNER = join(scratch, 'read-hook-input.mjs');
writeFileSync(
  RUNNER,
  `import { readHookInput } from ${JSON.stringify(STDIN_LIB)};\n` +
    `process.stdout.write(JSON.stringify({ result: readHookInput() }));\n`,
);

function runReader(stdio, input) {
  return spawnSync(process.execPath, [RUNNER], { input, stdio, encoding: 'utf-8', timeout: 15000 });
}

// ---- S1 parseHookInput ----

test('S1: a JSON object parses to that object', () => {
  assert.deepEqual(parseHookInput('{"a":1}'), { a: 1 });
  assert.deepEqual(parseHookInput('{"a":{"b":[1,2,{"c":null}]},"s":"x"}'), { a: { b: [1, 2, { c: null }] }, s: 'x' });
});

test('S1: one leading U+FEFF is stripped before parsing', () => {
  assert.deepEqual(parseHookInput('﻿{"a":1}'), { a: 1 });
});

test('S1 probe: only ONE leading BOM is stripped; a second makes the text invalid JSON -> null', () => {
  assert.equal(parseHookInput('﻿﻿{"a":1}'), null);
});

test('S1 probe: a BOM alone is empty input -> null', () => {
  assert.equal(parseHookInput('﻿'), null);
});

test('S1: empty, whitespace-only and invalid JSON text -> null', () => {
  for (const text of ['', '   ', '\n', 'x', '{', '{"a":', '{a:1}']) {
    assert.equal(parseHookInput(text), null, JSON.stringify(text));
  }
});

test('S1: JSON that is not a plain object (array, null, string, number, boolean) -> null', () => {
  for (const text of ['[1]', '[]', '[{"a":1}]', 'null', '"s"', '42', 'true']) {
    assert.equal(parseHookInput(text), null, text);
  }
});

test('S1 probe: surrounding whitespace around an object is tolerated', () => {
  assert.deepEqual(parseHookInput('  {"a":1}\n'), { a: 1 });
});

test('S1 probe: a BOM inside a key is data, not stripped', () => {
  assert.deepEqual(parseHookInput('{"﻿k":1}'), { '﻿k': 1 });
});

// ---- S2 readHookInput (subprocess) ----

test('S2: stdin carrying an object -> that object, exit 0', () => {
  const res = runReader(['pipe', 'pipe', 'pipe'], '{"session_id":"s1","tool_input":{"x":[1,2]}}');
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(JSON.parse(res.stdout), { result: { session_id: 's1', tool_input: { x: [1, 2] } } });
});

test('S2: stdin with a leading BOM -> the object', () => {
  const res = runReader(['pipe', 'pipe', 'pipe'], '﻿{"a":1}');
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(JSON.parse(res.stdout), { result: { a: 1 } });
});

test('S2: empty stdin -> null, exit 0 (no throw)', () => {
  const res = runReader(['pipe', 'pipe', 'pipe'], '');
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(JSON.parse(res.stdout), { result: null });
});

test('S2: stdin that is not valid JSON, or is an array or null -> null, exit 0', () => {
  for (const input of ['not json', '[1,2]', 'null']) {
    const res = runReader(['pipe', 'pipe', 'pipe'], input);
    assert.equal(res.status, 0, `${input}: ${res.stderr}`);
    assert.deepEqual(JSON.parse(res.stdout), { result: null }, input);
  }
});

test('S2 probe: stdin ignored (null device, nothing to read) -> null, exit 0', () => {
  const res = runReader(['ignore', 'pipe', 'pipe'], undefined);
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(JSON.parse(res.stdout), { result: null });
});

test('S2 probe: a payload larger than one pipe buffer is read whole', () => {
  const big = 'x'.repeat(400 * 1024);
  const res = runReader(['pipe', 'pipe', 'pipe'], JSON.stringify({ big }));
  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(res.stdout).result.big.length, big.length);
});
