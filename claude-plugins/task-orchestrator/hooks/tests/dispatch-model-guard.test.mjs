// Tests for hooks/dispatch-model-guard.mjs. Existing-surface cases keep their oracle (the guard denies an
// Agent dispatch without a model (and, since the model-effort policy, without an effort) unless the mode is off / headless / no config). Item 8cd79bc0 scenario S9
// adds, from task-scope AC1 and AC7: the mode is read from extensions.claude-code.orchestration (a
// top-level `orchestration:` block is ignored); the deny reasons name 4.0 tools instead of 3.x ones (no
// bare RETIRED_3X_TOOLS name); gating and deny shape are otherwise unchanged - in particular a director
// session is still guarded (it must pass `model` too).

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';
import { RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';

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

function fixture(config) {
  const fx = { dir: tmp('to-model-guard-'), toHome: tmp('to-model-guard-home-'), tmpDir: tmp('to-model-guard-tmp-') };
  if (config !== null) writeConfig(fx.dir, config);
  return fx;
}

function plantDirector(fx, sessionId) {
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
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: fx.dir,
      HOME: fx.dir,
      USERPROFILE: fx.dir,
      CLAUDE_CONFIG_DIR: '',
      TASK_ORCHESTRATOR_HOME: fx.toHome,
      TASK_ORCHESTRATOR_MODE: '',
      TEMP: fx.tmpDir,
      TMP: fx.tmpDir,
      TMPDIR: fx.tmpDir,
      ...extraEnv,
    },
    encoding: 'utf-8',
    cwd: fx.dir,
    input: typeof stdin === 'string' ? stdin : JSON.stringify(stdin),
  });
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

function run(config, stdin, extraEnv) {
  return exec(fixture(config), stdin, extraEnv);
}

const BASE = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n';
const ext = (mode) => `${BASE}extensions:\n  claude-code:\n    orchestration:\n      mode: ${mode}\n`;
const agentCall = (toolInput = {}, extra = {}) => ({
  tool_name: 'Agent',
  tool_input: { subagent_type: 'task-orchestrator:implementer', description: 'd', prompt: 'p', ...toolInput },
  ...extra,
});

function reasonOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'PreToolUse');
  assert.equal(out.hookSpecificOutput.permissionDecision, 'deny');
  return out.hookSpecificOutput.permissionDecisionReason;
}

function assertNoRetiredNames(reason, label) {
  for (const tool of RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree')) {
    assert.ok(!new RegExp('\\b' + tool + '\\b').test(reason), `${label}: bare retired name ${tool} in: ${reason}`);
  }
}

test('workflow mode, no model -> deny pointing at the dispatch policy skill', () => {
  const r = reasonOf(run(BASE, agentCall({ effort: 'e' })));
  for (const s of ['model', 'effort', 'model-effort']) assert.ok(r.includes(s), s);
});

test('model and effort present -> {}', () => {
  assert.deepEqual(run(BASE, agentCall({ model: 'm', effort: 'e' })), {});
});

test('empty, whitespace, null, number model -> deny', () => {
  for (const m of ['', '   ', null, 5]) {
    reasonOf(run(BASE, agentCall({ model: m, effort: 'e' })));
  }
});

test('AC4: a model without effort -> deny naming effort as missing', () => {
  const r = reasonOf(run(BASE, agentCall({ model: 'm' })));
  assert.match(r, /missing `effort`/);
  assert.doesNotMatch(r, /missing `model`/);
});

test('AC4: empty, whitespace, null, number effort -> deny', () => {
  for (const e of ['', '   ', null, 5]) {
    assert.match(reasonOf(run(BASE, agentCall({ model: 'm', effort: e }))), /missing `effort`/);
  }
});

test('AC4: neither model nor effort -> one deny naming both', () => {
  assert.match(reasonOf(run(BASE, agentCall())), /missing `model` and `effort`/);
});

test('AC4: the effort requirement has the same exemptions as model (off, headless, no config)', () => {
  const call = agentCall({ model: 'm' });
  assert.deepEqual(run(ext('off'), call), {});
  assert.deepEqual(run(BASE, call, { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
  assert.deepEqual(run(null, call), {});
  reasonOf(run(ext('schema'), call));
});

test('S9: schema mode (extensions.claude-code.orchestration), no model -> deny mentioning dispatch profile, no table', () => {
  const r = reasonOf(run(ext('schema'), agentCall()));
  assert.match(r, /dispatch/);
  assert.ok(!r.includes('model-effort'));
});

test('S9: mode off (block, inline, any case) under extensions.claude-code -> {}', () => {
  assert.deepEqual(run(ext('off'), agentCall()), {});
  assert.deepEqual(run(`${BASE}extensions:\n  claude-code:\n    orchestration: { mode: off }\n`, agentCall()), {});
  assert.deepEqual(run(ext('OFF'), agentCall()), {});
});

test('S9: a top-level orchestration block is ignored - off or schema there leaves the workflow guard in force', () => {
  const workflow = reasonOf(run(BASE, agentCall()));
  assert.equal(reasonOf(run(`${BASE}orchestration:\n  mode: off\n`, agentCall())), workflow);
  assert.equal(reasonOf(run(`${BASE}orchestration: { mode: off }\n`, agentCall())), workflow);
  assert.equal(reasonOf(run(`${BASE}orchestration:\n  mode: schema\n`, agentCall())), workflow);
});

test('S9: the extensions value wins when both placements are present', () => {
  assert.deepEqual(run(`${BASE}orchestration:\n  mode: schema\n${ext('off').slice(BASE.length)}`, agentCall()), {});
  const schemaReason = reasonOf(run(ext('schema'), agentCall()));
  assert.equal(reasonOf(run(`${BASE}orchestration:\n  mode: off\n${ext('schema').slice(BASE.length)}`, agentCall())), schemaReason);
});

test('invalid mode -> deny with workflow reason', () => {
  const r = reasonOf(run(ext('bogus'), agentCall()));
  assert.ok(r.includes('model-effort'));
  assert.equal(r, reasonOf(run(BASE, agentCall())));
});

test('S9: neither deny reason names a bare 3.x tool, and together they name the 4.0 tools', () => {
  const workflow = reasonOf(run(BASE, agentCall()));
  const schema = reasonOf(run(ext('schema'), agentCall()));
  assertNoRetiredNames(workflow, 'workflow reason');
  assertNoRetiredNames(schema, 'schema reason');
  const both = workflow + '\n' + schema;
  assert.match(both, /\badvance\b/);
  assert.match(both, /\bitems_read\b/);
});

test('headless iteration -> {}', () => {
  assert.deepEqual(run(BASE, agentCall(), { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
});

test('S9: a director session is still guarded - no model is denied, a model and effort pass', () => {
  const fx = fixture(BASE);
  plantDirector(fx, 'dir-session');
  const input = (toolInput) => agentCall(toolInput, { session_id: 'dir-session' });
  reasonOf(exec(fx, input()));
  assert.deepEqual(exec(fx, input({ model: 'm', effort: 'e' })), {});
  reasonOf(exec(fx, input({ model: 'm' })));
  const schemaFx = fixture(ext('schema'));
  plantDirector(schemaFx, 'dir-session');
  reasonOf(exec(schemaFx, input()));
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

test('S9: config is read per fire - flipping the mode between runs flips the guard', () => {
  const fx = fixture(BASE);
  reasonOf(exec(fx, agentCall()));
  writeConfig(fx.dir, ext('off'));
  assert.deepEqual(exec(fx, agentCall()), {});
  writeConfig(fx.dir, ext('schema'));
  assert.match(reasonOf(exec(fx, agentCall())), /dispatch/);
});

test('hooks-config registers exactly one Agent matcher group for the guard', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const pre = cfg.hooks.PreToolUse;
  assert.equal(pre.length, 5);
  const agentGroups = pre.filter((g) => g.matcher === 'Agent');
  assert.equal(agentGroups.length, 1);
  const cmds = JSON.stringify(cfg).match(/dispatch-model-guard\.mjs/g) || [];
  assert.equal(cmds.length, 1);
  assert.match(agentGroups[0].hooks[0].command, /dispatch-model-guard\.mjs/);
});