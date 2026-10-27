// Tests for hooks/director-context.mjs (item 8aee9676, S11 / AC8). Oracle: task-scope Build 2 and Amendment A1
// context-hook oracle. The hook runs as a subprocess against a synthetic temp root.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';
import { DIRECTOR_RULE_TEXT } from '../director-context.mjs';

const HOOK = fileURLToPath(new URL('../director-context.mjs', import.meta.url));
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

const tracked = [];
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});

function fixture() {
  return { dir: tmp('to-dctx-'), tmpDir: tmp('to-dctx-tmp-') };
}

function plant(fx, sessionId) {
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = fx.tmpDir;
  }
  try {
    const p = directorMarkerPath(sessionId);
    mkdirSync(dirname(p), { recursive: true });
    writeFileSync(p, '');
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
}

function exec(fx, stdin, extraEnv = {}) {
  const r = spawnSync(process.execPath, [HOOK], {
    env: { ...process.env, TASK_ORCHESTRATOR_MODE: '', TEMP: fx.tmpDir, TMP: fx.tmpDir, TMPDIR: fx.tmpDir, ...extraEnv },
    encoding: 'utf-8',
    cwd: fx.dir,
    input: typeof stdin === 'string' ? stdin : JSON.stringify(stdin),
  });
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

test('S11: DIRECTOR_RULE_TEXT is two sentences carrying the rule', () => {
  assert.equal(typeof DIRECTOR_RULE_TEXT, 'string');
  assert.equal(DIRECTOR_RULE_TEXT.trim().split(/(?<=[.!?])\s+/).length, 2);
  assert.ok(DIRECTOR_RULE_TEXT.includes('never opens a file or reads a note body'));
  assert.ok(DIRECTOR_RULE_TEXT.includes('/task-orchestrator:director'));
});

test('S11: marker + compact source -> additionalContext is the rule text', () => {
  const fx = fixture();
  plant(fx, 's');
  const out = exec(fx, { session_id: 's', source: 'compact' });
  assert.deepEqual(out, { hookSpecificOutput: { hookEventName: 'SessionStart', additionalContext: DIRECTOR_RULE_TEXT } });
});

test('S11: no marker -> {}', () => {
  const fx = fixture();
  assert.deepEqual(exec(fx, { session_id: 's', source: 'compact' }), {});
});

test('S11: marker for another session -> {}', () => {
  const fx = fixture();
  plant(fx, 's-a');
  assert.deepEqual(exec(fx, { session_id: 's-b', source: 'compact' }), {});
});

test('S11: agent_id -> {} even under the marker', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, { session_id: 's', source: 'compact', agent_id: 'a1' }), {});
});

test('S11: headless iteration -> {} even under the marker', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, { session_id: 's', source: 'compact' }, { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
});

test('S11: the hook does not test source (matcher does): marker + startup injects', () => {
  const fx = fixture();
  plant(fx, 's');
  const out = exec(fx, { session_id: 's', source: 'startup' });
  assert.equal(out.hookSpecificOutput.additionalContext, DIRECTOR_RULE_TEXT);
});

test('probe: empty and non-JSON stdin -> {}', () => {
  const fx = fixture();
  assert.deepEqual(exec(fx, ''), {});
  assert.deepEqual(exec(fx, 'not json'), {});
});

test('probe: replay gives the same output', () => {
  const fx = fixture();
  plant(fx, 's');
  const a = exec(fx, { session_id: 's', source: 'compact' });
  assert.deepEqual(exec(fx, { session_id: 's', source: 'compact' }), a);
});

test('S11: hooks-config registers a compact SessionStart group running director-context.mjs once', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const groups = cfg.hooks.SessionStart.filter((g) => g.hooks.some((h) => h.command.includes('director-context.mjs')));
  assert.equal(groups.length, 1);
  assert.equal(groups[0].matcher, 'compact');
  assert.equal(groups[0].hooks.length, 1);
  assert.equal((JSON.stringify(cfg).match(/director-context\.mjs/g) || []).length, 1);
});
