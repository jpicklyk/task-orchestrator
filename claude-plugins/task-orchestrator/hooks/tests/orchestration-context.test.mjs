// Tests for hooks/orchestration-context.mjs (item 8cd79bc0, scenarios S4 and S5 of the frozen test-plan).
// The hook is a stdin-driven script, so every case runs it as a child process.
//
// Oracles (never "what the hook prints today"):
//   - S4 gating, task-scope AC5/AC3: gates in order, each answering `{}`: suppression (headless, then
//     subagent via stdin agent_id, then director marker), then no located config, then mode `off`.
//     Config is read per fire (a hot-reloaded config.yaml changes the next fire). Plugin keys live under
//     extensions.claude-code; a top-level `orchestration:` is ignored.
//   - S5 content, task-scope AC5/AC9 and the carry-ins: no bare 3.x tool names; advance `transition`
//     `start` by the entry seat only; `seatToken` top-level; `resource_unavailable` transient with
//     `retryAfterMs`; blocked items carry `blockedReason` (`awaiting_decision` + `decisionId`, decisions
//     via `runs_write` `raise`); `items_write` `create_tree`; `items_read` `context`; cascade lifecycle
//     rules (auto parents only; a parent in review is never cascaded, reported `review-pending`; blocked
//     items never move); last numbered principle is the P36 feature-closure principle; at least one fenced
//     to-call block and every block passes checkToCall against the tool catalog; each variant < 9000 chars.
//   - tool names and arg shapes: hooks/lib/tool-catalog.json and current/docs/api-reference.md.

import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { directorMarkerPath } from '../execution-mode.mjs';
import { RETIRED_3X_TOOLS, parseToCallBlocks, checkToCall } from '../../scripts/lib/contract-checks.mjs';

const HOOK = fileURLToPath(new URL('../orchestration-context.mjs', import.meta.url));
const HOOKS_CONFIG = fileURLToPath(new URL('../hooks-config.json', import.meta.url));
const CATALOG = JSON.parse(readFileSync(fileURLToPath(new URL('../lib/tool-catalog.json', import.meta.url)), 'utf-8'));

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

// A hermetic fixture: its own project dir, user home and temp root (the marker root follows TEMP).
function fixture(config) {
  const fx = { dir: tmp('to-orch-ctx-'), toHome: tmp('to-orch-ctx-home-'), tmpDir: tmp('to-orch-ctx-tmp-') };
  if (config !== null && config !== undefined) writeConfig(fx.dir, config);
  return fx;
}

// Plant a director marker where the hook (running with this fixture's TEMP) will look for it.
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
    input: stdin === undefined ? '' : typeof stdin === 'string' ? stdin : JSON.stringify(stdin),
    encoding: 'utf-8',
    cwd: fx.dir,
  });
  assert.equal(r.status, 0, r.stderr);
  return JSON.parse(r.stdout);
}

function run(config, stdin, extraEnv) {
  return exec(fixture(config), stdin, extraEnv);
}

function ctxOf(out) {
  assert.equal(out.hookSpecificOutput.hookEventName, 'SessionStart');
  const c = out.hookSpecificOutput.additionalContext;
  assert.equal(typeof c, 'string');
  assert.ok(c.length < 9000, `additionalContext too long: ${c.length}`);
  return c;
}

const BASE = 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n';
const ext = (mode) => `${BASE}extensions:\n  claude-code:\n    orchestration:\n      mode: ${mode}\n`;
const SCHEMA_CFG = ext('schema');
const WORKFLOW_CFG = ext('workflow');

// ---- S4: gating --------------------------------------------------------------------------------

test('S4: no config -> {}', () => {
  assert.deepEqual(run(null, { session_id: 's1' }), {});
  assert.deepEqual(run(null), {});
});

test('S4: headless iteration -> {}, whatever stdin says', () => {
  const env = { TASK_ORCHESTRATOR_MODE: 'headless-iteration' };
  assert.deepEqual(run(BASE, { session_id: 's1' }, env), {});
  assert.deepEqual(run(BASE, '', env), {});
  assert.deepEqual(run(SCHEMA_CFG, { session_id: 's1' }, env), {});
});

test('S4: only the exact headless sentinel suppresses', () => {
  assert.ok(ctxOf(run(BASE, { session_id: 's1' }, { TASK_ORCHESTRATOR_MODE: 'HEADLESS-ITERATION' })).length > 0);
  assert.ok(ctxOf(run(BASE, { session_id: 's1' }, { TASK_ORCHESTRATOR_MODE: 'interactive' })).length > 0);
});

test('S4: a subagent (non-empty agent_id on stdin) gets {} in every mode', () => {
  for (const cfg of [BASE, WORKFLOW_CFG, SCHEMA_CFG]) {
    assert.deepEqual(run(cfg, { session_id: 's1', agent_id: 'agent-7' }), {});
  }
});

test('S4: an empty or non-string agent_id is not a subagent', () => {
  for (const agent_id of ['', 123, null]) {
    ctxOf(run(BASE, { session_id: 's1', agent_id }));
  }
});

test('S4: a director session (marker at directorMarkerPath) gets {}', () => {
  const fx = fixture(BASE);
  plantDirector(fx, 'dir-session');
  assert.deepEqual(exec(fx, { session_id: 'dir-session' }), {});
  // The same fixture without the marker for this session injects, so the marker is what suppressed it.
  ctxOf(exec(fx, { session_id: 'someone-else' }));
});

test('S4: the director gate also holds after /compact (SessionStart source compact) and for every mode', () => {
  for (const cfg of [BASE, SCHEMA_CFG]) {
    const fx = fixture(cfg);
    plantDirector(fx, 'dir-session');
    assert.deepEqual(exec(fx, { session_id: 'dir-session', source: 'compact' }), {});
    assert.deepEqual(exec(fx, { session_id: 'dir-session', source: 'resume' }), {});
  }
});

test('S4: missing, empty, malformed or non-object stdin is a main session and injects', () => {
  for (const stdin of [undefined, '', 'not json', '[]', 'null', '42']) {
    ctxOf(run(BASE, stdin));
  }
});

test('S4: a session_id without a marker, or empty/absent, injects', () => {
  for (const stdin of [{ session_id: 'plain' }, { session_id: '' }, {}, { source: 'startup' }]) {
    ctxOf(run(BASE, stdin));
  }
});

test('S4: mode off in the extensions block and inline forms -> {}', () => {
  assert.deepEqual(run(ext('off'), { session_id: 's1' }), {});
  assert.deepEqual(run(`${BASE}extensions:\n  claude-code:\n    orchestration: { mode: off }\n`, { session_id: 's1' }), {});
  assert.deepEqual(run(`${BASE}extensions:\n  claude-code:\n    orchestration:\n      mode: OFF\n`, { session_id: 's1' }), {});
});

test('S4: a top-level orchestration block is ignored - mode off there does NOT silence the hook', () => {
  ctxOf(run(`${BASE}orchestration:\n  mode: off\n`, { session_id: 's1' }));
  ctxOf(run(`${BASE}orchestration: { mode: off }\n`, { session_id: 's1' }));
});

test('S4: the extensions value wins when both placements are present', () => {
  assert.deepEqual(run(`${BASE}orchestration:\n  mode: schema\n${ext('off').slice(BASE.length)}`, { session_id: 's1' }), {});
  const out = ctxOf(run(`${BASE}orchestration:\n  mode: off\n${ext('schema').slice(BASE.length)}`, { session_id: 's1' }));
  assert.match(out, /task-orchestrator:schema-workflow/);
});

test('S4: absent block and invalid mode -> workflow variant', () => {
  assert.match(ctxOf(run(BASE, { session_id: 's1' })), /task-orchestrator:orchestrate/);
  assert.match(ctxOf(run(ext('bogus'), { session_id: 's1' })), /task-orchestrator:orchestrate/);
  assert.equal(ctxOf(run(ext('bogus'), { session_id: 's1' })), ctxOf(run(BASE, { session_id: 's1' })));
});

test('S4: config without project.rootId still injects', () => {
  const cfg = 'extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n';
  assert.match(ctxOf(run(cfg, { session_id: 's1' })), /task-orchestrator:orchestrate/);
});

test('S4: schema variant differs: no tier sizing route, but phase-owner dispatch still routes to orchestrate', () => {
  const schema = ctxOf(run(SCHEMA_CFG, { session_id: 's1' }));
  const workflow = ctxOf(run(WORKFLOW_CFG, { session_id: 's1' }));
  assert.match(schema, /task-orchestrator:schema-workflow/);
  assert.ok(!schema.includes('Before sizing or dispatching implementation work'));
  assert.ok(!schema.includes('Direct work you implement yourself'));
  assert.match(schema, /phase owner[^\n]*`task-orchestrator:orchestrate`/);
  assert.match(workflow, /Before sizing or dispatching implementation work \| `task-orchestrator:orchestrate`/);
  assert.notEqual(schema, workflow);
});

test('S4: config is read per fire - editing config.yaml between runs changes the next output', () => {
  const fx = fixture(WORKFLOW_CFG);
  const first = ctxOf(exec(fx, { session_id: 's1' }));
  writeConfig(fx.dir, SCHEMA_CFG);
  const second = ctxOf(exec(fx, { session_id: 's1' }));
  assert.notEqual(first, second);
  assert.match(second, /task-orchestrator:schema-workflow/);
  writeConfig(fx.dir, ext('off'));
  assert.deepEqual(exec(fx, { session_id: 's1' }), {});
  writeConfig(fx.dir, WORKFLOW_CFG);
  assert.equal(ctxOf(exec(fx, { session_id: 's1' })), first);
});

test('S4: replay - the same input twice gives the same output', () => {
  const fx = fixture(SCHEMA_CFG);
  assert.equal(ctxOf(exec(fx, { session_id: 's1' })), ctxOf(exec(fx, { session_id: 's1' })));
});

test('S4: hooks-config.json lists orchestration-context.mjs exactly once in SessionStart', () => {
  const cfg = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const cmds = cfg.hooks.SessionStart.flatMap((g) => g.hooks.map((h) => h.command));
  assert.equal(cmds.filter((c) => c.includes('orchestration-context.mjs')).length, 1);
  assert.equal(cfg.hooks.SessionStart.length, 1);
});

// ---- S5: content, both variants ----------------------------------------------------------------

const VARIANTS = [
  ['workflow', WORKFLOW_CFG],
  ['schema', SCHEMA_CFG],
];
const textOf = (cfg) => ctxOf(run(cfg, { session_id: 's1' }));

test('S5: both variants carry the core rules and omit excluded sections', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    for (const s of ['resource_unavailable', 'orchestrator-confirmation', 'task-orchestrator:run-wave', 'task-orchestrator:create-item', 'explicitly']) {
      assert.ok(c.includes(s), `${name}: missing ${s}`);
    }
    assert.ok(!c.includes('## Project Scope'), name);
    assert.ok(!c.includes('| Criteria | Tier |'), name);
    assert.ok(!c.includes('GENERATED:tier-classification'), name);
  }
});

test('S5: no bare 3.x tool name in either variant (the 4.0 op complete_tree is allowed)', () => {
  const retired = RETIRED_3X_TOOLS.filter((n) => n !== 'complete_tree');
  assert.ok(retired.length >= 16, 'the retired-name list is the 17 names minus complete_tree');
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    for (const tool of retired) {
      assert.ok(!new RegExp('\\b' + tool + '\\b').test(c), `${name}: bare retired name ${tool}`);
    }
  }
});

test('S5: 4.0 vocabulary - advance transition start by the entry seat only, seatToken top-level, items_read context, create_tree', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    assert.match(c, /`advance`/, name);
    assert.match(c, /`transition`/, name);
    assert.match(c, /`start`/, name);
    assert.match(c, /entry seat/i, name);
    assert.match(c, /in-phase/i, name);
    assert.match(c, /read-only/i, name);
    assert.match(c, /seatToken[^.\n]*top-level|top-level[^.\n]*seatToken/i, name);
    assert.match(c, /`items_read`/, name);
    assert.match(c, /`context`/, name);
    assert.match(c, /`items_write`/, name);
    assert.match(c, /`create_tree`/, name);
  }
});

test('S5: resource_unavailable is transient with retryAfterMs; blocked items carry blockedReason awaiting_decision + decisionId; decisions via runs_write raise', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    assert.match(c, /resource_unavailable/, name);
    assert.match(c, /transient/i, name);
    assert.match(c, /retryAfterMs/, name);
    assert.match(c, /blockedReason/, name);
    assert.match(c, /awaiting_decision/, name);
    assert.match(c, /decisionId/, name);
    assert.match(c, /`runs_write`/, name);
    assert.match(c, /`raise`/, name);
  }
});

test('S5: lifecycle - cascade applies to auto parents only; manual, permanent, none never cascade; review parent is review-pending; blocked never moves', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    assert.match(c, /cascade/i, name);
    assert.match(c, /\bauto\b/, name);
    assert.match(c, /\bmanual\b/, name);
    assert.match(c, /\bpermanent\b/, name);
    assert.match(c, /\bnone\b/, name);
    assert.match(c, /review-pending/, name);
    // a sentence ties blocked items to the cascade never moving them
    const sentences = c.split(/(?<=[.!?])\s+|\n/);
    assert.ok(sentences.some((s) => /blocked/i.test(s) && /cascade/i.test(s) && /\b(never|not|no)\b/i.test(s)), `${name}: no sentence says a cascade never moves a blocked item`);
    // a sentence ties the review-pending report to a parent in review
    assert.ok(sentences.some((s) => /review-pending/.test(s) && /review/.test(s.replace('review-pending', ''))), `${name}: review-pending is not tied to a parent in review`);
  }
});

test('S5: schema resolution is by type only - no tag resolution wording', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    assert.ok(!/first matching tag/i.test(c), name);
  }
});

test('S5: no sentence says trait order selects the dispatch model (model-selecting traits outrank delegated whatever the order)', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    const sentences = c.split(/(?<=[.!?])\s+|\n/);
    const offenders = sentences.filter((s) => /trait/i.test(s) && /\border\b/i.test(s) && /(model|dispatch)/i.test(s));
    assert.deepEqual(offenders, [], `${name}: ${offenders.join(' | ')}`);
  }
});

test('S5: the last numbered principle is the P36 feature-closure principle, in both variants', () => {
  for (const [name, cfg] of VARIANTS) {
    const c = textOf(cfg);
    const starts = [...c.matchAll(/^[ \t]*\d+\.[ \t]/gm)].map((m) => m.index);
    assert.ok(starts.length >= 2, `${name}: expected a numbered list of principles`);
    const last = starts[starts.length - 1];
    const tail = c.slice(last);
    for (const needle of ['follow-up <item-id>', 'N of N disposed', 'orchestrator-confirmation', 'accepted: <reason>', 'fixed']) {
      assert.ok(tail.includes(needle), `${name}: last numbered principle lacks "${needle}"`);
    }
    assert.match(tail, /blocking/i, name);
    assert.match(tail, /feature-level review|feature review|review of the feature/i, name);
    assert.match(tail, /terminal/i, name);
    assert.match(tail, /same reviewer/i, name);
    assert.match(tail, /fresh/i, name);
    assert.match(tail, /non-entry seat/i, name);
    assert.match(tail, /explicit/i, name);
    assert.match(tail, /no cascade|not cascade|never cascade|does not cascade|without cascad/i, name);
    assert.match(tail, /existing item/i, name);
  }
});

test('S5: at least one fenced to-call block, and every block passes checkToCall against the tool catalog', () => {
  for (const [name, cfg] of VARIANTS) {
    const blocks = parseToCallBlocks(textOf(cfg));
    assert.ok(blocks.length >= 1, `${name}: no to-call block`);
    for (const b of blocks) {
      assert.ok(!('error' in b), `${name}:${b.line} ${b.error}`);
      assert.deepEqual(checkToCall(b.value, CATALOG), [], `${name}:${b.line}`);
    }
  }
});

test('S5: each variant stays under 9000 characters (ctxOf asserts it); the two variants are distinct', () => {
  const [w, s] = VARIANTS.map(([, cfg]) => textOf(cfg));
  assert.ok(w.length < 9000 && s.length < 9000);
  assert.notEqual(w, s);
});