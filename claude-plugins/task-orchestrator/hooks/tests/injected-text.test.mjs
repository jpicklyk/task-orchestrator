// Tests for the text the context hooks inject (item 8cd79bc0, scenarios S10, S11 and S13 of the frozen test-plan).
//
// S10 oracle: task-scope AC8 - the retrospective directive no longer carries the 'process-global ... at
//   depth 0 OUTSIDE any project root' clause (AR-73 hook half); the rest of the directive stays.
// S11 oracle: task-scope AC9 - no bare 3.x tool name (RETIRED_3X_TOOLS, except the 4.0 op complete_tree) in
//   the four context hooks or the dispatch guard, and the three pendingPort rows for
//   orchestration-context.mjs, session-start.mjs and dispatch-model-guard.mjs are gone from
//   scripts/lib/contract-names-baseline.json.
// S13 oracle: DRIFT DETECTOR ONLY. Each fixture under fixtures/injected/ is a normalized capture of what a
//   hook emitted when the fixture was recorded; the test fails when the text changes so a reviewer sees the
//   wording change in the diff. The fixtures were run-derived (UPDATE_INJECTED_FIXTURES=1 rewrites them);
//   they are NOT an oracle for correctness - the independent content checks live in S4-S6 and S8-S9 of
//   orchestration-context.test.mjs, session-start.test.mjs, plan-hooks.test.mjs and
//   dispatch-model-guard.test.mjs. The captures were reviewed against task-scope AC4-AC6 when recorded.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync, existsSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { buildDispatch, buildNudge } from '../retro-lib.mjs';
import { RETIRED_3X_TOOLS } from '../../scripts/lib/contract-checks.mjs';

const HOOKS_DIR = fileURLToPath(new URL('../', import.meta.url));
const PLUGIN = fileURLToPath(new URL('../../', import.meta.url));
const FIXTURES = fileURLToPath(new URL('./fixtures/injected/', import.meta.url));
const UPDATE = process.env.UPDATE_INJECTED_FIXTURES === '1';
const RETIRED = RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree');

const tracked = [];
function tmp(prefix) {
  const d = mkdtempSync(join(tmpdir(), prefix));
  tracked.push(d);
  return d;
}
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});

// ---- S10 ------------------------------------------------------------------------------------------

test('S10: the retrospective dispatch directive no longer carries the process-global depth-0 clause', () => {
  for (const text of [buildDispatch(['aaa'], 'root-1'), buildDispatch([], 'root-1'), buildDispatch(['aaa', 'bbb'], 'root-2')]) {
    assert.ok(!/depth 0/i.test(text), 'depth 0 clause still present');
    assert.ok(!/OUTSIDE any project root/i.test(text), 'OUTSIDE-any-project-root clause still present');
  }
});

test('S10: the rest of the directive stays - roots and the ancestor rootId are still rendered', () => {
  const text = buildDispatch(['aaa'], 'root-1');
  assert.ok(text.includes('aaa'));
  assert.ok(text.includes('root-1'));
  assert.ok(text.trim().length > 100, 'the directive is not gutted');
});

test('S10: the nudge text has no depth-0 wording either', () => {
  for (const text of [buildNudge([]), buildNudge(['aaa'])]) assert.ok(!/depth 0/i.test(text));
});

// ---- S11 ------------------------------------------------------------------------------------------

const GATED_SCRIPTS = ['session-start.mjs', 'orchestration-context.mjs', 'pre-plan.mjs', 'post-plan.mjs', 'dispatch-model-guard.mjs'];

test('S11: none of the context hooks or the dispatch guard names a bare 3.x tool (file names only are reported)', () => {
  const offenders = [];
  for (const name of GATED_SCRIPTS) {
    const text = readFileSync(join(HOOKS_DIR, name), 'utf8');
    for (const tool of RETIRED) {
      if (new RegExp('\\b' + tool + '\\b').test(text)) offenders.push(name + ': ' + tool);
    }
  }
  assert.deepEqual(offenders, []);
});

test('S11: the contract-names baseline has no pendingPort rows for the three ported hooks', () => {
  const baseline = JSON.parse(readFileSync(join(PLUGIN, 'scripts', 'lib', 'contract-names-baseline.json'), 'utf8'));
  assert.ok(Array.isArray(baseline.pendingPort), 'pendingPort stays an array');
  const files = baseline.pendingPort.map((r) => String(r.file).replace(/\\/g, '/'));
  for (const hook of ['orchestration-context.mjs', 'session-start.mjs', 'dispatch-model-guard.mjs']) {
    assert.ok(!files.some((f) => f.endsWith('/' + hook) || f === hook), `pendingPort row for ${hook} still present`);
  }
});

// ---- S13: snapshots (drift detector) --------------------------------------------------------------

function run(script, { config, stdin, env = {}, withUserConfig } = {}) {
  const fx = { dir: tmp('to-inj-'), toHome: tmp('to-inj-home-'), tmpDir: tmp('to-inj-tmp-') };
  if (config) {
    mkdirSync(join(fx.dir, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(fx.dir, '.taskorchestrator', 'config.yaml'), config, 'utf-8');
  }
  if (withUserConfig) {
    mkdirSync(join(fx.toHome, '.taskorchestrator'), { recursive: true });
    writeFileSync(join(fx.toHome, '.taskorchestrator', 'config.yaml'), withUserConfig, 'utf-8');
  }
  const r = spawnSync(process.execPath, [join(HOOKS_DIR, script)], {
    env: {
      ...process.env,
      AGENT_CONFIG_DIR: fx.dir,
      HOME: fx.dir,
      USERPROFILE: fx.dir,
      CLAUDE_CONFIG_DIR: '',
      TASK_ORCHESTRATOR_HOME: fx.toHome,
      TASK_ORCHESTRATOR_MODE: '',
      TASK_ORCHESTRATOR_API_URL: '',
      TASK_ORCHESTRATOR_SETUP_HINT: '',
      TEMP: fx.tmpDir,
      TMP: fx.tmpDir,
      TMPDIR: fx.tmpDir,
      ...env,
    },
    input: stdin === undefined ? '' : JSON.stringify(stdin),
    encoding: 'utf-8',
    cwd: fx.dir,
  });
  assert.equal(r.status, 0, r.stderr);
  const out = JSON.parse(r.stdout);
  return { out, fx };
}

// Replace the fixture-specific directories with stable placeholders and normalize line endings.
function normalize(text, fx) {
  let t = String(text).replace(/\r\n/g, '\n');
  const variants = (d) => {
    const set = new Set([d, d.replace(/\\/g, '/')]);
    try {
      const real = realpathSync(d);
      set.add(real);
      set.add(real.replace(/\\/g, '/'));
    } catch {
      /* directory already gone */
    }
    return [...set].sort((a, b) => b.length - a.length);
  };
  for (const [d, token] of [[fx.toHome, '<HOME>'], [fx.dir, '<DIR>'], [fx.tmpDir, '<TMP>']]) {
    for (const v of variants(d)) t = t.split(v).join(token);
  }
  return t.replace(/\\/g, '/');
}

// Injected text must be real text: a double-encoded UTF-8 sequence (for example an em dash read as cp1252
// and written back as UTF-8) is corruption, never intended wording. Item 8cd79bc0 regression: the base
// commit's pre-plan/post-plan headings used a real em dash.
const MOJIBAKE = /\u00e2\u20ac|\u00c3[\u0080-\u00bf]|\u00c2[\u0080-\u00bf]/;

function snapshot(name, text) {
  assert.ok(!MOJIBAKE.test(text), `${name}: injected text contains double-encoded UTF-8 (mojibake)`);
  const file = join(FIXTURES, name + '.txt');
  if (UPDATE) {
    mkdirSync(FIXTURES, { recursive: true });
    writeFileSync(file, text, 'utf8');
    return;
  }
  assert.ok(existsSync(file), `missing fixture ${name}.txt (record with UPDATE_INJECTED_FIXTURES=1)`);
  const want = readFileSync(file, 'utf8').replace(/\r\n/g, '\n');
  assert.equal(text, want, `${name}: injected text drifted from fixtures/injected/${name}.txt`);
}

const PROJECT = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n  name: "Snapshot"\n';
const ext = (mode) => `${PROJECT}extensions:\n  claude-code:\n    orchestration:\n      mode: ${mode}\n`;
const ctx = (r) => normalize(r.out.hookSpecificOutput.additionalContext, r.fx);

test('S13: orchestration-context workflow variant matches its snapshot', () => {
  snapshot('orchestration-context.workflow', ctx(run('orchestration-context.mjs', { config: ext('workflow'), stdin: { session_id: 's1' } })));
});

test('S13: orchestration-context schema variant matches its snapshot', () => {
  snapshot('orchestration-context.schema', ctx(run('orchestration-context.mjs', { config: ext('schema'), stdin: { session_id: 's1' } })));
});

test('S13: session-start guidance for a project, a personal root and an unconfigured directory match their snapshots', () => {
  snapshot('session-start.project', ctx(run('session-start.mjs', { config: PROJECT, stdin: { session_id: 's1' } })));
  snapshot(
    'session-start.personal',
    ctx(run('session-start.mjs', { withUserConfig: 'project:\n  rootId: 22222222-2222-2222-2222-222222222222\n  name: "Me"\n', stdin: { session_id: 's1' } })),
  );
  snapshot('session-start.none', ctx(run('session-start.mjs', { stdin: { session_id: 's1' } })));
});

test('S13: session-start Config Shape notice matches its snapshot', () => {
  const r = run('session-start.mjs', { config: PROJECT + 'orchestration:\n  mode: off\nretrospective:\n  mode: nudge\n', stdin: { session_id: 's1' } });
  snapshot('session-start.config-shape', ctx(r));
});

test('S13: pre-plan and post-plan directives match their snapshots', () => {
  const pre = run('pre-plan.mjs', { config: ext('workflow'), stdin: { tool_name: 'EnterPlanMode', session_id: 's1' } });
  snapshot('pre-plan', ctx(pre));
  const post = run('post-plan.mjs', { config: ext('workflow'), stdin: { tool_name: 'ExitPlanMode', session_id: 's1', tool_input: { plan: '# plan' } } });
  snapshot('post-plan', ctx(post));
});

test('S13: dispatch-model-guard deny reasons (workflow and schema) match their snapshots', () => {
  const call = { tool_name: 'Agent', tool_input: { subagent_type: 'task-orchestrator:implementer', description: 'd', prompt: 'p' } };
  for (const mode of ['workflow', 'schema']) {
    const r = run('dispatch-model-guard.mjs', { config: ext(mode), stdin: call });
    snapshot('dispatch-model-guard.' + mode, normalize(r.out.hookSpecificOutput.permissionDecisionReason, r.fx));
  }
});