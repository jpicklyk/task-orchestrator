import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const HOOK = fileURLToPath(new URL('../dispatch-hint.mjs', import.meta.url));
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
  const dir = tmp('to-dispatch-hint-');
  if (config !== null) writeConfig(dir, config);
  const toHome = tmp('to-dispatch-hint-home-');
  const tmpDir = tmp('to-dispatch-hint-tmp-');
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
const TOOL = 'mcp__mcp-task-orchestrator__advance_item';
const A = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const B = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const C = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const D = 'dddddddd-dddd-dddd-dddd-dddddddddddd';

const call = (results, extra = {}) => ({
  tool_name: TOOL,
  tool_input: {},
  tool_response: {
    content: [{ type: 'text', text: `Transitioned ${results.length} item(s)` }],
    structuredContent: { results, summary: { total: results.length, succeeded: results.length, failed: 0 } },
  },
  ...extra,
});
const ok = (itemId, newRole, dispatch) => ({
  itemId,
  newRole,
  applied: true,
  ...(dispatch ? { dispatch } : {}),
});

function ctx(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'PostToolUse');
  return out.hookSpecificOutput.additionalContext;
}

test('single work transition with agent+model -> one line', () => {
  const out = run(BASE, call([ok(A, 'work', { agent: 'task-orchestrator:implementer', model: 'sonnet' })]));
  assert.equal(
    ctx(out),
    `↳ ${A} now in work; dispatch profile: agent=task-orchestrator:implementer model=sonnet - pass model explicitly`,
  );
});

test('batch -> only work and review lines, in result order', () => {
  const out = run(
    BASE,
    call([
      ok(A, 'work', { agent: 'x', model: 'haiku' }),
      ok(B, 'terminal'),
      ok(C, 'review', { agent: 'y' }),
      ok(D, 'queue'),
    ]),
  );
  const lines = ctx(out).split('\n');
  assert.equal(lines.length, 2);
  assert.ok(lines[0].includes(A) && lines[0].includes('now in work'));
  assert.ok(lines[1].includes(C) && lines[1].includes('now in review'));
});

test('terminal-only -> {}', () => {
  assert.deepEqual(run(BASE, call([ok(A, 'terminal')])), {});
});

test('applied:false and tool-level error -> {}', () => {
  const failed = { itemId: A, applied: false, errorCode: 'gate_blocked' };
  assert.deepEqual(run(BASE, call([failed])), {});
  const errResp = {
    tool_name: TOOL,
    tool_response: { content: [{ type: 'text', text: 'boom' }], isError: true, structuredContent: { error: { code: 'x' } } },
  };
  assert.deepEqual(run(BASE, errResp), {});
});

test('no dispatch key -> agent=none and mode-aware model fallback', () => {
  const wf = ctx(run(BASE, call([ok(A, 'work')])));
  assert.match(wf, /agent=none model=table default - pass model explicitly$/);
  const sc = ctx(run(`${BASE}orchestration:\n  mode: schema\n`, call([ok(A, 'work')])));
  assert.match(sc, /agent=none model=unset - pass model explicitly$/);
  assert.ok(!sc.includes('table default'));
});

test('effort token only when present', () => {
  const withE = ctx(run(BASE, call([ok(A, 'review', { agent: 'r', model: 'opus', effort: 'high' })])));
  assert.match(withE, /model=opus effort=high - pass model explicitly$/);
  const without = ctx(run(BASE, call([ok(A, 'review', { agent: 'r', model: 'opus' })])));
  assert.ok(!without.includes('effort='));
});

test('mode off, headless, no config -> {}', () => {
  const c = call([ok(A, 'work')]);
  assert.deepEqual(run(`${BASE}orchestration:\n  mode: off\n`, c), {});
  assert.deepEqual(run(BASE, c, { TASK_ORCHESTRATOR_MODE: 'headless-iteration' }), {});
  assert.deepEqual(run(null, c), {});
});

test('agent_id present (subagent/seat) -> {}', () => {
  assert.deepEqual(run(BASE, call([ok(A, 'work')], { agent_id: 'agent-123' })), {});
});

test('malformed stdin and non-JSON content-only response -> {}', () => {
  assert.deepEqual(run(BASE, 'not json'), {});
  assert.deepEqual(run(BASE, ''), {});
  const textOnly = {
    tool_name: TOOL,
    tool_response: { content: [{ type: 'text', text: 'Transitioned 1 item(s)' }] },
  };
  assert.deepEqual(run(BASE, textOnly), {});
});

test('non-advance_item tool -> {}', () => {
  assert.deepEqual(run(BASE, { ...call([ok(A, 'work')]), tool_name: 'mcp__x__manage_notes' }), {});
});

test('hooks-config registers a PostToolUse advance_item group running dispatch-hint', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const groups = cfg.hooks.PostToolUse.filter((g) => g.matcher === '^mcp__.*task-orchestrator.*__advance_item$');
  assert.ok(groups.some((g) => g.hooks.some((h) => /dispatch-hint\.mjs/.test(h.command))));
  const n = (JSON.stringify(cfg).match(/dispatch-hint\.mjs/g) || []).length;
  assert.equal(n, 1);
});
