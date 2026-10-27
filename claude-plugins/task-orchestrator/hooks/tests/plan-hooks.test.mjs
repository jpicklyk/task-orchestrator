// Tests for hooks/pre-plan.mjs and hooks/post-plan.mjs (item 8cd79bc0, scenario S8 of the frozen test-plan).
// Both hooks are stdin-driven scripts, so every case runs them as child processes.
//
// Oracle: task-scope AC6 - each hook emits `{}` when the shared suppression gate fires (headless
// iteration, subagent agent_id, director marker) or when the located config's orchestration mode is
// `off` (config-format: `off` silences every orchestration hook); otherwise it emits its directive,
// which names its skill (pre-plan -> task-orchestrator:pre-plan-workflow, post-plan ->
// task-orchestrator:post-plan-workflow; the names are the plugin's skills/ directory names). With no
// located config the directive is still emitted. Plugin keys are read from extensions.claude-code only.
// Event names come from the hooks-config.json registration (PreToolUse EnterPlanMode, PostToolUse
// ExitPlanMode).

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';

const PLUGIN = fileURLToPath(new URL('../../', import.meta.url));
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));

const HOOKS = [
  {
    name: 'pre-plan',
    script: fileURLToPath(new URL('../pre-plan.mjs', import.meta.url)),
    event: 'PreToolUse',
    matcher: 'EnterPlanMode',
    skill: 'task-orchestrator:pre-plan-workflow',
    skillDir: 'pre-plan-workflow',
    stdin: { tool_name: 'EnterPlanMode', session_id: 's1' },
  },
  {
    name: 'post-plan',
    script: fileURLToPath(new URL('../post-plan.mjs', import.meta.url)),
    event: 'PostToolUse',
    matcher: 'ExitPlanMode',
    skill: 'task-orchestrator:post-plan-workflow',
    skillDir: 'post-plan-workflow',
    stdin: { tool_name: 'ExitPlanMode', session_id: 's1', tool_input: { plan: '# plan' } },
  },
];

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
  const fx = { dir: tmp('to-plan-hook-'), toHome: tmp('to-plan-hook-home-'), tmpDir: tmp('to-plan-hook-tmp-') };
  if (config !== null && config !== undefined) writeConfig(fx.dir, config);
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

function exec(hook, fx, stdin, extraEnv = {}) {
  const r = spawnSync(process.execPath, [hook.script], {
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
    input: stdin === undefined ? '' : typeof stdin === 'string' ? stdin : JSON.stringify(stdin),
    encoding: 'utf-8',
    cwd: fx.dir,
  });
  assert.equal(r.status, 0, `${hook.name}: ${r.stderr}`);
  return JSON.parse(r.stdout);
}

function run(hook, config, stdin = hook.stdin, extraEnv) {
  return exec(hook, fixture(config), stdin, extraEnv);
}

// The hook injected its directive: a context output for this hook's event that names its skill.
function assertDirective(hook, out, label = '') {
  const tag = `${hook.name} ${label}`.trim();
  assert.ok(out && out.hookSpecificOutput, `${tag}: expected a directive, got ${JSON.stringify(out)}`);
  assert.equal(out.hookSpecificOutput.hookEventName, hook.event, tag);
  const ctx = out.hookSpecificOutput.additionalContext;
  assert.equal(typeof ctx, 'string', tag);
  assert.ok(ctx.includes(hook.skill), `${tag}: directive must name ${hook.skill}`);
}

const BASE = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n';
const ext = (mode) => `${BASE}extensions:\n  claude-code:\n    orchestration:\n      mode: ${mode}\n`;

for (const hook of HOOKS) {
  test(`S8 ${hook.name}: registered under ${hook.event} with matcher ${hook.matcher}, and its skill exists`, () => {
    const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
    const groups = cfg.hooks[hook.event].filter((g) => g.matcher === hook.matcher);
    assert.equal(groups.length, 1);
    assert.ok(groups[0].hooks.some((h) => h.command.includes(`${hook.name}.mjs`)));
    assert.ok(existsSync(join(PLUGIN, 'skills', hook.skillDir, 'SKILL.md')), 'skill file for the directive');
  });

  test(`S8 ${hook.name}: a main session with an ordinary config gets the directive naming its skill`, () => {
    for (const cfg of [BASE, ext('workflow'), ext('schema')]) {
      assertDirective(hook, run(hook, cfg), JSON.stringify(cfg));
    }
  });

  test(`S8 ${hook.name}: the directive is clean text - no double-encoded UTF-8, and its heading keeps the real em dash`, () => {
    const out = run(hook, BASE);
    assertDirective(hook, out);
    const ctx = out.hookSpecificOutput.additionalContext;
    assert.ok(!/\u00e2\u20ac|\u00c3[\u0080-\u00bf]|\u00c2[\u0080-\u00bf]/.test(ctx), `mojibake in directive: ${JSON.stringify(ctx.split('\n')[0])}`);
    assert.ok(ctx.split('\n')[0].startsWith('## '), 'directive opens with a heading');
    assert.ok(ctx.split('\n')[0].includes('\u2014'), 'heading separator is an em dash (U+2014), as at the base commit');
  });
  test(`S8 ${hook.name}: no located config -> the directive (no-config behaviour is unchanged)`, () => {
    assertDirective(hook, run(hook, null), 'no config');
  });

  test(`S8 ${hook.name}: headless iteration -> {}`, () => {
    const env = { TASK_ORCHESTRATOR_MODE: 'headless-iteration' };
    assert.deepEqual(run(hook, BASE, hook.stdin, env), {});
    assert.deepEqual(run(hook, null, hook.stdin, env), {});
    assert.deepEqual(run(hook, BASE, '', env), {});
  });

  test(`S8 ${hook.name}: only the exact headless sentinel suppresses`, () => {
    assertDirective(hook, run(hook, BASE, hook.stdin, { TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' }), 'upper-case sentinel');
  });

  test(`S8 ${hook.name}: a subagent (non-empty agent_id) -> {}; an empty or non-string agent_id is not a subagent`, () => {
    assert.deepEqual(run(hook, BASE, { ...hook.stdin, agent_id: 'agent-7' }), {});
    assert.deepEqual(run(hook, null, { ...hook.stdin, agent_id: 'agent-7' }), {});
    for (const agent_id of ['', 5, null]) {
      assertDirective(hook, run(hook, BASE, { ...hook.stdin, agent_id }), `agent_id=${JSON.stringify(agent_id)}`);
    }
  });

  test(`S8 ${hook.name}: a director session (marker present) -> {}; another session still gets the directive`, () => {
    const fx = fixture(BASE);
    plantDirector(fx, 'dir-session');
    assert.deepEqual(exec(hook, fx, { ...hook.stdin, session_id: 'dir-session' }), {});
    assertDirective(hook, exec(hook, fx, { ...hook.stdin, session_id: 'other-session' }), 'other session');
  });

  test(`S8 ${hook.name}: orchestration mode off (block, inline, any case) -> {}`, () => {
    assert.deepEqual(run(hook, ext('off')), {});
    assert.deepEqual(run(hook, `${BASE}extensions:\n  claude-code:\n    orchestration: { mode: off }\n`), {});
    assert.deepEqual(run(hook, ext('OFF')), {});
  });

  test(`S8 ${hook.name}: a top-level orchestration block is ignored - off there does not silence the hook`, () => {
    assertDirective(hook, run(hook, `${BASE}orchestration:\n  mode: off\n`), 'top-level off');
    assertDirective(hook, run(hook, `${BASE}orchestration: { mode: off }\n`), 'top-level inline off');
  });

  test(`S8 ${hook.name}: the extensions value wins when both placements are present`, () => {
    assert.deepEqual(run(hook, `${BASE}orchestration:\n  mode: schema\n${ext('off').slice(BASE.length)}`), {});
    assertDirective(hook, run(hook, `${BASE}orchestration:\n  mode: off\n${ext('schema').slice(BASE.length)}`), 'ext schema beats top-level off');
  });

  test(`S8 ${hook.name}: empty, malformed or non-object stdin is a main session and gets the directive`, () => {
    for (const stdin of [undefined, '', 'not json', '[]', 'null']) {
      assertDirective(hook, run(hook, BASE, stdin), JSON.stringify(stdin));
    }
  });

  test(`S8 ${hook.name}: config is read per fire - flipping the mode between runs flips the output`, () => {
    const fx = fixture(ext('workflow'));
    assertDirective(hook, exec(hook, fx, hook.stdin), 'workflow');
    writeConfig(fx.dir, ext('off'));
    assert.deepEqual(exec(hook, fx, hook.stdin), {});
    writeConfig(fx.dir, ext('schema'));
    assertDirective(hook, exec(hook, fx, hook.stdin), 'schema');
  });

  test(`S8 ${hook.name}: replay - the same input twice gives the same output`, () => {
    const fx = fixture(BASE);
    assert.deepEqual(exec(hook, fx, hook.stdin), exec(hook, fx, hook.stdin));
  });
}