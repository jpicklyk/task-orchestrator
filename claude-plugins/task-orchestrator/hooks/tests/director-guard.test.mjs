// Tests for hooks/director-guard.mjs (item 8aee9676, scenarios S1-S10 and probes). Oracles: task-scope
// Build 1 and AC1-AC7 plus the test-plan; the hook is only ever run as a subprocess against a synthetic
// temp root, so no real marker path is touched.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';
import { DIRECTOR_SKILL_NAMES, DIRECTOR_BASH_ALLOW, REASON_PREFIX, isDirectorSkillCall, bashAllowed, guardDecision } from '../director-guard.mjs';

const HOOK = fileURLToPath(new URL('../director-guard.mjs', import.meta.url));
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
  return { dir: tmp('to-dguard-'), tmpDir: tmp('to-dguard-tmp-') };
}

// Run fn with the process temp dir pointed at fx.tmpDir so directorMarkerPath resolves inside the fixture.
function withTmp(fx, fn) {
  const saved = {};
  for (const k of ['TMPDIR', 'TEMP', 'TMP']) {
    saved[k] = process.env[k];
    process.env[k] = fx.tmpDir;
  }
  try {
    return fn();
  } finally {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete process.env[k];
      else process.env[k] = saved[k];
    }
  }
}
const markerPath = (fx, sid) => withTmp(fx, () => directorMarkerPath(sid));
function plant(fx, sid, content = '') {
  const p = markerPath(fx, sid);
  mkdirSync(dirname(p), { recursive: true });
  writeFileSync(p, content);
  return p;
}
function listFiles(dir) {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) out.push(...listFiles(p));
    else out.push(p);
  }
  return out;
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

const call = (tool_name, tool_input = {}, extra = {}) => ({ tool_name, tool_input, session_id: 's', ...extra });
const bash = (command, extra) => call('Bash', { command }, extra);

function reasonOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  return out.hookSpecificOutput.permissionDecisionReason;
}

test('exports: frozen constants', () => {
  assert.deepEqual([...DIRECTOR_SKILL_NAMES], ['director', 'task-orchestrator:director']);
  assert.deepEqual([...DIRECTOR_BASH_ALLOW], ['git status', 'git log', 'git branch', 'gh pr view', 'gh pr list', 'docker ps']);
  assert.equal(REASON_PREFIX, 'Director altitude rule:');
  assert.ok(Object.isFrozen(DIRECTOR_SKILL_NAMES) && Object.isFrozen(DIRECTOR_BASH_ALLOW));
});

// S1: no marker, nothing denied. Fails without the fix if the guard denied without consulting the marker.
test('S1: no marker -> {} for Read/Edit/Write/Bash/foreground Agent', () => {
  const fx = fixture();
  for (const c of [call('Read', { file_path: 'f' }), call('Edit', {}), call('Write', {}), bash('cat f'), call('Agent', { prompt: 'p' })]) {
    assert.deepEqual(exec(fx, c), {}, c.tool_name);
  }
});

// S2
test('S2: director Skill call writes a per-session marker; args off removes it', () => {
  for (const skill of ['task-orchestrator:director', 'director']) {
    const fx = fixture();
    const p = markerPath(fx, 's');
    assert.equal(existsSync(p), false);
    assert.deepEqual(exec(fx, call('Skill', { skill })), {});
    assert.equal(existsSync(p), true, 'marker written for ' + skill);
    const m = JSON.parse(readFileSync(p, 'utf-8'));
    assert.equal(m.skill, skill);
    assert.equal(typeof m.since, 'string');
    assert.ok(!Number.isNaN(Date.parse(m.since)));
    assert.deepEqual(exec(fx, call('Skill', { skill, args: 'off' })), {});
    assert.equal(existsSync(p), false, 'off removes marker');
    assert.deepEqual(exec(fx, call('Read', {})), {});
  }
});

test('S2: a non-director Skill call writes no marker', () => {
  const fx = fixture();
  assert.deepEqual(exec(fx, call('Skill', { skill: 'task-orchestrator:init' })), {});
  assert.equal(existsSync(markerPath(fx, 's')), false);
});

test('S2/AC1: after the Skill call the same session is guarded', () => {
  const fx = fixture();
  exec(fx, call('Skill', { skill: 'director' }));
  assert.ok(reasonOf(exec(fx, call('Read', {}))).startsWith(REASON_PREFIX));
});

// S3
test('S3: Skill call with agent_id or without session_id writes nothing', () => {
  const fx = fixture();
  const cases = [
    call('Skill', { skill: 'director' }, { agent_id: 'a1' }),
    { tool_name: 'Skill', tool_input: { skill: 'director' } },
    call('Skill', { skill: 'director' }, { session_id: '' }),
    call('Skill', { skill: 'director' }, { session_id: 42 }),
  ];
  for (const c of cases) assert.deepEqual(exec(fx, c), {});
  assert.deepEqual(listFiles(fx.tmpDir), []);
});

// S4
test('S4: marker -> Read/Edit/Write denied with prefix, tool name and remedies', () => {
  const fx = fixture();
  plant(fx, 's');
  for (const tool of ['Read', 'Edit', 'Write']) {
    const reason = reasonOf(exec(fx, call(tool, { file_path: 'f' })));
    assert.ok(reason.startsWith(REASON_PREFIX), reason);
    assert.ok(reason.includes(tool), 'names ' + tool);
    for (const needle of ['SendMessage', 'Agent', '/task-orchestrator:director off']) assert.ok(reason.includes(needle), 'remedy ' + needle);
  }
});

test('S4: Bash and Agent denials also name their tool', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.ok(reasonOf(exec(fx, bash('cat f'))).includes('Bash'));
  assert.ok(reasonOf(exec(fx, call('Agent', {}))).includes('Agent'));
});

// S5
test('S5: marker -> allow-listed Bash commands pass', () => {
  const fx = fixture();
  plant(fx, 's');
  for (const cmd of ['git status', 'git log --oneline -5', '  git branch', 'gh pr view 12', 'gh pr list', 'docker ps']) {
    assert.deepEqual(exec(fx, bash(cmd)), {}, cmd);
  }
});

// S6
test('S6: marker -> non-allow-listed, chained, substituted, redirected Bash denied with reason', () => {
  const fx = fixture();
  plant(fx, 's');
  const bad = ['cat a.kt', 'git diff', 'git status && cat f', 'git status; cat f', 'git status || cat f', 'git log | head', 'git status\ncat f',
    '$(cat f)', 'git status `cat f`', 'git status $(cat f)', 'git status > f', 'git status < f', 'GIT_PAGER=cat git log', 'git statusx', 'GIT STATUS'];
  for (const cmd of bad) assert.ok(reasonOf(exec(fx, bash(cmd))).startsWith(REASON_PREFIX), cmd);
  for (const c of [call('Bash', {}), call('Bash', { command: 5 }), call('Bash', { command: null })]) {
    assert.ok(reasonOf(exec(fx, c)).startsWith(REASON_PREFIX));
  }
});

test('S6: bashAllowed unit', () => {
  assert.equal(bashAllowed('git status'), true);
  assert.equal(bashAllowed('git status && git log'), true);
  assert.equal(bashAllowed('git status && cat f'), false);
  assert.equal(bashAllowed('cat f && git status'), false);
  assert.equal(bashAllowed('git status | cat'), false);
  assert.equal(bashAllowed(undefined), false);
  assert.equal(bashAllowed(7), false);
});

// S7
test('S7: Agent run_in_background strictly boolean true', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, call('Agent', { run_in_background: true })), {});
  for (const v of [undefined, false, 'true', 1]) {
    const input = v === undefined ? {} : { run_in_background: v };
    assert.ok(reasonOf(exec(fx, call('Agent', input))).startsWith(REASON_PREFIX), String(v));
  }
});

// S8
test('S8: agent_id exempt, SendMessage/ListAgents never denied, headless inert', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, call('Read', {}, { agent_id: 'a1' })), {});
  assert.deepEqual(exec(fx, bash('cat f', { agent_id: 'a1' })), {});
  assert.deepEqual(exec(fx, call('Agent', {}, { agent_id: 'a1' })), {});
  assert.deepEqual(exec(fx, call('SendMessage', {})), {});
  assert.deepEqual(exec(fx, call('ListAgents', {})), {});
  assert.deepEqual(exec(fx, call('Read', {}), { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
  // control: without the exemptions the same call is denied
  reasonOf(exec(fx, call('Read', {})));
});

// S9
test('S9: marker is per session; empty file counts; hostile session id stays under the root', () => {
  const fx = fixture();
  plant(fx, 's-a');
  assert.deepEqual(exec(fx, call('Read', {}, { session_id: 's-b' })), {});
  reasonOf(exec(fx, call('Read', {}, { session_id: 's-a' })));
  assert.equal(readFileSync(markerPath(fx, 's-a'), 'utf-8'), '');
  const fx2 = fixture();
  exec(fx2, call('Skill', { skill: 'director' }, { session_id: '../x' }));
  const p = markerPath(fx2, '../x');
  assert.ok(existsSync(p));
  const root = join(fx2.tmpDir, 'task-orchestrator');
  assert.ok(p.startsWith(root), p);
  assert.equal(listFiles(fx2.tmpDir).every((f) => f.startsWith(root)), true);
});

// S10
test('S10: hooks-config registers the guard with fail-closed onFailure', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const groups = cfg.hooks.PreToolUse.filter((g) => g.hooks.some((h) => h.command.includes('director-guard.mjs')));
  assert.equal(groups.length, 1);
  assert.equal(groups[0].matcher, 'Skill|Read|Edit|Write|Bash|Agent|Grep|Glob|NotebookEdit');
  assert.equal(groups[0].hooks.length, 1);
  assert.equal(groups[0].hooks[0].onFailure, 'block');
  assert.equal((JSON.stringify(cfg).match(/director-guard\.mjs/g) || []).length, 1);
});

test('S10: under the marker an unusable tool_input denies (fail closed)', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.ok(reasonOf(exec(fx, { tool_name: 'Bash', tool_input: 'x', session_id: 's' })).startsWith(REASON_PREFIX));
  assert.ok(reasonOf(exec(fx, { tool_name: 'Bash', tool_input: null, session_id: 's' })).startsWith(REASON_PREFIX));
});

// Probes
test('probe: leading space allowed, CRLF-chained denied', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, bash(' git status')), {});
  reasonOf(exec(fx, bash('git status\r\ncat f')));
});

test('probe: tool_name case mismatch is not matched', () => {
  const fx = fixture();
  plant(fx, 's');
  assert.deepEqual(exec(fx, call('read', {})), {});
});

test('probe: skill name is trimmed; other names do not match', () => {
  const fx = fixture();
  assert.equal(isDirectorSkillCall({ tool_name: 'Skill', tool_input: { skill: ' director ' } }), true);
  assert.equal(isDirectorSkillCall({ tool_name: 'Skill', tool_input: { skill: 'directors' } }), false);
  assert.equal(isDirectorSkillCall({ tool_name: 'Read', tool_input: { skill: 'director' } }), false);
  exec(fx, call('Skill', { skill: ' director ' }));
  assert.ok(existsSync(markerPath(fx, 's')));
});

test('probe: empty, non-JSON, BOM stdin -> {}', () => {
  const fx = fixture();
  assert.deepEqual(exec(fx, ''), {});
  assert.deepEqual(exec(fx, 'not json'), {});
  assert.deepEqual(exec(fx, '﻿' + JSON.stringify(call('Read', {}))), {});
});

test('probe: replay of marker writes and denials is idempotent', () => {
  const fx = fixture();
  exec(fx, call('Skill', { skill: 'director' }));
  exec(fx, call('Skill', { skill: 'director' }));
  assert.equal(listFiles(fx.tmpDir).length, 1);
  assert.equal(reasonOf(exec(fx, call('Read', {}))), reasonOf(exec(fx, call('Read', {}))));
  exec(fx, call('Skill', { skill: 'director', args: ' off ' }));
  exec(fx, call('Skill', { skill: 'director', args: 'off' }));
  assert.equal(existsSync(markerPath(fx, 's')), false);
});

test('guardDecision: null for allow, string for deny', () => {
  assert.equal(guardDecision(null, { env: {}, director: true }), null);
  assert.equal(guardDecision(call('Read', {}), { env: {}, director: false }), null);
  assert.equal(typeof guardDecision(call('Read', {}), { env: {}, director: true }), 'string');
  assert.equal(guardDecision(call('Read', {}), { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }, director: true }), null);
});
