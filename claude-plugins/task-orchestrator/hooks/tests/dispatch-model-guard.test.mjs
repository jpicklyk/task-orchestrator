import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../dispatch-model-guard.mjs', import.meta.url));
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

function writeConfig(dir, content) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), content, 'utf-8');
}

function run(config, stdin, extraEnv = {}) {
  const dir = tmp('to-model-guard-');
  if (config !== null) writeConfig(dir, config);
  const toHome = tmp('to-model-guard-home-');
  const tmpDir = tmp('to-model-guard-tmp-');
  const r = spawnSync(process.execPath, [HOOK], {
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: dir,
      HOME: dir,
      USERPROFILE: dir,
      CLAUDE_CONFIG_DIR: '',
      TASK_ORCHESTRATOR_HOME: toHome,
      TASK_ORCHESTRATOR_MODE: '',
      TEMP: tmpDir,
      TMP: tmpDir,
      TMPDIR: tmpDir,
      ...extraEnv,
    },
    encoding: 'utf-8',
    cwd: dir,
    input: typeof stdin === 'string' ? stdin : JSON.stringify(stdin),
  });
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

const BASE = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n';
const agentCall = (toolInput = {}) => ({
  tool_name: 'Agent',
  tool_input: { subagent_type: 'task-orchestrator:implementer', description: 'd', prompt: 'p', ...toolInput },
});

function reasonOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  return out.hookSpecificOutput.permissionDecisionReason;
}

test('workflow mode, no model -> deny naming the table', () => {
  const r = reasonOf(run(BASE, agentCall()));
  for (const s of ['model', 'haiku', 'sonnet', 'opus', 'orchestrate']) assert.ok(r.includes(s), s);
});

test('model present -> {}', () => {
  assert.deepEqual(run(BASE, agentCall({ model: 'sonnet' })), {});
});

test('empty, whitespace, null, number model -> deny', () => {
  for (const m of ['', '   ', null, 5]) {
    reasonOf(run(BASE, agentCall({ model: m })));
  }
});

test('schema mode, no model -> deny mentioning dispatch profile, no table', () => {
  const r = reasonOf(run(`${BASE}orchestration:\n  mode: schema\n`, agentCall()));
  assert.match(r, /dispatch/);
  assert.ok(!r.includes('haiku'));
});

test('mode off (block and inline) -> {}', () => {
  assert.deepEqual(run(`${BASE}orchestration:\n  mode: off\n`, agentCall()), {});
  assert.deepEqual(run(`${BASE}orchestration: { mode: off }\n`, agentCall()), {});
});

test('invalid mode -> deny with workflow reason', () => {
  const r = reasonOf(run(`${BASE}orchestration:\n  mode: bogus\n`, agentCall()));
  assert.ok(r.includes('haiku'));
});

test('headless iteration -> {}', () => {
  assert.deepEqual(run(BASE, agentCall(), { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
});

test('no config located -> {}', () => {
  assert.deepEqual(run(null, agentCall()), {});
});

test('non-Agent tool -> {}', () => {
  assert.deepEqual(run(BASE, { tool_name: 'TaskCreate', tool_input: {} }), {});
});

test('malformed and empty stdin -> {}', () => {
  assert.deepEqual(run(BASE, 'not json'), {});
  assert.deepEqual(run(BASE, ''), {});
});

test('hooks-config registers exactly one Agent matcher group for the guard', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const pre = cfg.hooks.PreToolUse;
  assert.equal(pre.length, 4);
  const agentGroups = pre.filter((g) => g.matcher === 'Agent');
  assert.equal(agentGroups.length, 1);
  const cmds = JSON.stringify(cfg).match(/dispatch-model-guard\.mjs/g) || [];
  assert.equal(cmds.length, 1);
  assert.match(agentGroups[0].hooks[0].command, /dispatch-model-guard\.mjs/);
});
