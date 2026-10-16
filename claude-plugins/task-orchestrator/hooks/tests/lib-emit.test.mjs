// Contract tests for hooks/lib/emit.mjs (fc131c71, scenario S3). Oracle: the frozen task-scope
// signatures. Builders return plain objects; writers perform one process.stdout.write of
// JSON.stringify(x) with no trailing newline. Writers are driven through a subprocess so the test
// observes the real stdout bytes (patching stdout in-process would collide with the test reporter).

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { contextOutput, denyOutput, blockOutput, systemMessageOutput } from '../lib/emit.mjs';

const EMIT_LIB = pathToFileURL(fileURLToPath(new URL('../lib/emit.mjs', import.meta.url))).href;

const scratch = mkdtempSync(join(tmpdir(), 'to-lib-emit-'));
after(() => rmSync(scratch, { recursive: true, force: true }));

const RUNNER = join(scratch, 'emit-runner.mjs');
writeFileSync(
  RUNNER,
  `import * as e from ${JSON.stringify(EMIT_LIB)};\n` +
    `e[process.argv[2]](...JSON.parse(process.argv[3] || '[]'));\n`,
);

function run(fn, args) {
  return spawnSync(process.execPath, [RUNNER, fn, JSON.stringify(args)], { encoding: 'utf-8', timeout: 15000 });
}

// ---- builders ----

test('S3: contextOutput builds hookSpecificOutput with the event name and additionalContext', () => {
  assert.deepEqual(contextOutput('SessionStart', 'hello'), {
    hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: 'hello' },
  });
});

test('S3: contextOutput merges extra keys into hookSpecificOutput (watchPaths example)', () => {
  assert.deepEqual(contextOutput('SessionStart', 't', { watchPaths: ['/a', '/b'] }), {
    hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: 't', watchPaths: ['/a', '/b'] },
  });
});

test('S3 probe: contextOutput keeps empty-string text (empty is not absent)', () => {
  assert.deepEqual(contextOutput('PostToolUse', ''), {
    hookSpecificOutput: { hookEventName: 'PostToolUse', additionalContext: '' },
  });
});

test('S3: denyOutput is a PreToolUse deny decision carrying the reason', () => {
  assert.deepEqual(denyOutput('nope'), {
    hookSpecificOutput: { hookEventName: 'PreToolUse', permissionDecision: 'deny', permissionDecisionReason: 'nope' },
  });
});

test('S3: blockOutput is a top-level block decision', () => {
  assert.deepEqual(blockOutput('stop it'), { decision: 'block', reason: 'stop it' });
});

test('S3: systemMessageOutput is a top-level systemMessage', () => {
  assert.deepEqual(systemMessageOutput('hi'), { systemMessage: 'hi' });
});

// ---- writers (exact stdout bytes) ----

test('S3: emitEmpty writes exactly {} with no newline', () => {
  const res = run('emitEmpty', []);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '{}');
});

test('S3: emit(obj) writes JSON.stringify(obj) exactly, no newline', () => {
  const obj = { a: 1, b: ['x', { c: null }] };
  const res = run('emit', [obj]);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, JSON.stringify(obj));
});

test('S3: emitContext writes the exact bytes of contextOutput, key order included', () => {
  const res = run('emitContext', ['SessionStart', 'ctx', { watchPaths: ['/p'] }]);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(
    res.stdout,
    '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"ctx","watchPaths":["/p"]}}',
  );
  assert.deepEqual(JSON.parse(res.stdout), contextOutput('SessionStart', 'ctx', { watchPaths: ['/p'] }));
});

test('S3: emitDeny stdout parses to denyOutput and has the documented key order, no newline', () => {
  const res = run('emitDeny', ['blocked: reason']);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(
    res.stdout,
    '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"deny","permissionDecisionReason":"blocked: reason"}}',
  );
  assert.deepEqual(JSON.parse(res.stdout), denyOutput('blocked: reason'));
});

test('S3: emitBlock writes {"decision":"block","reason":...} exactly', () => {
  const res = run('emitBlock', ['r']);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '{"decision":"block","reason":"r"}');
});

test('S3: emitSystemMessage writes {"systemMessage":...} exactly', () => {
  const res = run('emitSystemMessage', ['m']);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.stdout, '{"systemMessage":"m"}');
});

test('S3 probe: text with quotes, newlines and non-ASCII round-trips through emitContext', () => {
  const text = 'line1\n"quoted" \\ back é中文 — end';
  const res = run('emitContext', ['SessionStart', text]);
  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(res.stdout).hookSpecificOutput.additionalContext, text);
  assert.ok(!res.stdout.includes('\n'), 'the JSON line itself carries no raw newline');
});

test('S3 probe: a payload larger than one pipe buffer is emitted whole', () => {
  // The text is built inside the child: 300 KiB does not fit on a Windows command line.
  const big = join(scratch, 'emit-big.mjs');
  writeFileSync(
    big,
    `import { emitContext } from ${JSON.stringify(EMIT_LIB)};\n` +
      `emitContext('SessionStart', 'y'.repeat(300 * 1024));\n`,
  );
  const res = spawnSync(process.execPath, [big], { encoding: 'utf-8', timeout: 15000, maxBuffer: 8 * 1024 * 1024 });
  assert.equal(res.status, 0, res.stderr);
  assert.equal(JSON.parse(res.stdout).hookSpecificOutput.additionalContext.length, 300 * 1024);
});
