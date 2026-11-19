// Retrospective dispatch label and session folder (item 56094202, scenarios S1-S13 of its test-plan).
// BLIND author: written from the frozen task-scope and test-plan notes, the supplied declarations and the
// existing hook test harness (retro-fake-runs.mjs, retro-director-mark.test.mjs). Oracle (task-scope change
// 1-2): the dispatch directive carries description "retrospective:<run8>" (run8 = first 8 chars of the first
// run id, lower-cased, whole id if shorter, "unknown" for an empty list) right after the general-purpose
// subagent type, and, for a non-empty transcript path ending in the case-sensitive suffix .jsonl,
// ` --session-dir "<dir>"` (backslashes -> /, ONE trailing .jsonl stripped) after the last --run. Without a
// usable transcript path nothing is added. buildNudge is unchanged. The hooks pass hook-input
// transcript_path through and stay fail-open.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  rid,
  retroConfig,
  withFixture,
  runFixture,
  closePayload,
  contextOf,
  markDirector,
  HOOKS_CONFIG,
  ROOT,
  SCOPE,
  MIN,
  HOUR,
  DAY,
  ago,
  iid,
  CLEAN_NOTE,
  scopeFixture,
  mergeFixtures,
  readRetroState,
  writeRetroState,
  writeTranscript,
} from './retro-fake-runs.mjs';
import { buildDispatch, buildNudge } from '../retro-lib.mjs';
import { run as watchRun } from '../director-watch.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });
const stopPayload = (session, extra = {}) => ({ session_id: session, ...extra });

const ID1 = '1b056448-3504-462e-82fe-5e4549095427';
const ID2 = 'aaaaaaaa-3504-462e-82fe-5e4549095427';
const ID3 = 'bbbbbbbb-3504-462e-82fe-5e4549095427';
const count = (text, needle) => text.split(needle).length - 1;
const NL = String.fromCharCode(10);
const desc = (run8) => `description "retrospective:${run8}"`;
const dirFlag = (dir) => `--session-dir "${dir}"`;

// ---- S1: label and session folder ----------------------------------------------------------------

test('S1: buildDispatch carries description retrospective:<run8> and --session-dir for a .jsonl transcript path', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:/p/899f5f7e.jsonl' });
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
  assert.equal(count(text, '--session-dir'), 1, text);
});

test('S1: the description sits right after the general-purpose subagent type', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:/p/899f5f7e.jsonl' });
  const at = text.indexOf(desc('1b056448'));
  assert.ok(at > 0, text);
  const before = text.slice(0, at);
  assert.ok(/subagent_type "general-purpose",\s*$/.test(before), JSON.stringify(before.slice(-80)));
});

test('S1: --session-dir comes after the last --run, inside the one invocation', () => {
  const text = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const lastRun = text.lastIndexOf('--run');
  const flag = text.indexOf('--session-dir');
  assert.ok(lastRun > 0 && flag > lastRun, text);
  assert.ok(text.includes(`--run ${ID3} ${dirFlag('/p/x')}`), text);
});

// ---- S2: retro-trigger.mjs ------------------------------------------------------------------------

test('S2: retro-trigger passes stdin transcript_path: the directive carries the label and --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s2'), rid(1), { extra: { transcript_path: 'C:/p/899f5f7e.jsonl' } }));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(desc('00000000')), text);
    assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
  });
});

test('S2 control: retro-trigger without transcript_path has the label and no --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const text = contextOf(await trigger(closePayload(sid('s2c'), rid(1))));
    assert.ok(text?.includes(desc('00000000')), text);
    assert.ok(!text.includes('--session-dir'), text);
  });
});

test('S2 probe: retro-trigger with a non-string transcript_path (42) still dispatches without --session-dir', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s2n'), rid(1), { extra: { transcript_path: 42 } }));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(desc('00000000')), text);
    assert.ok(!text.includes('--session-dir'), text);
  });
});

// ---- S3: retro-backstop.mjs, REST path and the no-REST director path ------------------------------

test('S3: retro-backstop REST path carries the label and --session-dir from the Stop input', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop }) => {
    const res = await stop(stopPayload(sid('s3r'), { transcript_path: 'C:/p/899f5f7e.jsonl' }));
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(res.json.reason.includes(dirFlag('C:/p/899f5f7e')), res.json.reason);
  });
});

test('S3: retro-backstop no-REST director path (recorded close) carries the label and --session-dir', async () => {
  await withFixture({ config: cfg('nudge'), runs: [] }, async ({ sbx, trigger, stop }) => {
    const session = sid('s3d');
    markDirector(sbx, session);
    await trigger(closePayload(session, rid(1)), { apiUrl: undefined });
    const res = await stop(stopPayload(session, { transcript_path: 'C:/p/899f5f7e.jsonl' }), { apiUrl: undefined });
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
    assert.ok(res.json.reason.includes(`--run ${rid(1)}`), res.json.reason);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(res.json.reason.includes(dirFlag('C:/p/899f5f7e')), res.json.reason);
  });
});

test('S3 control: the no-REST director path without transcript_path has the label and no --session-dir', async () => {
  await withFixture({ config: cfg('nudge'), runs: [] }, async ({ sbx, trigger, stop }) => {
    const session = sid('s3c');
    markDirector(sbx, session);
    await trigger(closePayload(session, rid(1)), { apiUrl: undefined });
    const res = await stop(stopPayload(session), { apiUrl: undefined });
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(desc('00000000')), res.json.reason);
    assert.ok(!res.json.reason.includes('--session-dir'), res.json.reason);
  });
});

// ---- S4: director-watch run() ---------------------------------------------------------------------

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function watchOpts() {
  const dir = mkdtempSync(join(tmpdir(), 'to-sesscov-'));
  tracked.push(dir);
  mkdirSync(dir, { recursive: true });
  const statePath = join(dir, 'state.json');
  writeFileSync(statePath, JSON.stringify({ pushed: [], retro: [] }));
  return { director: true, statePath, rest: null, retroMode: 'dispatch' };
}
function getInput(session, runId, extra = {}) {
  const env = { ok: true, data: { runId, status: 'closed', marks: {} }, meta: { reqId: 'k7f3q9ab' } };
  return {
    tool_name: 'mcp__mcp-task-orchestrator__runs_read',
    tool_input: { select: 'get', runId },
    tool_response: { content: [{ type: 'text', text: 'ok' }], structuredContent: env, isError: false },
    session_id: session,
    ...extra,
  };
}
const textOf = (out) => out.hookSpecificOutput.additionalContext;

test('S4: director-watch run() with input.transcript_path carries the label and --session-dir', async () => {
  const out = await watchRun(getInput(sid('s4'), ID1, { transcript_path: 'C:/p/899f5f7e.jsonl' }), {}, watchOpts());
  const text = textOf(out);
  assert.ok(text.includes('--run ' + ID1), text);
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
});

test('S4 control: director-watch run() without transcript_path has the label and no --session-dir', async () => {
  const text = textOf(await watchRun(getInput(sid('s4c'), ID1), {}, watchOpts()));
  assert.ok(text.includes(desc('1b056448')), text);
  assert.ok(!text.includes('--session-dir'), text);
});

// ---- S5: merged dispatch keeps the first run's label ----------------------------------------------

test('S5: two runs [B, A] give one description (from B, the first) and one --session-dir', () => {
  const text = buildDispatch([ID3, ID2], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.equal(count(text, 'description "retrospective:'), 1, text);
  assert.ok(text.includes(desc('bbbbbbbb')), text);
  assert.ok(!text.includes(desc('aaaaaaaa')), text);
  assert.equal(count(text, '--session-dir'), 1, text);
});

// ---- S6: absent or unusable transcript path -------------------------------------------------------

test('S6: no transcriptPath -> description present, no --session-dir, equal to "" and 42', () => {
  const none = buildDispatch([ID1], 'root-1');
  assert.ok(none.includes(desc('1b056448')), none);
  assert.ok(!none.includes('--session-dir'), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '' }), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 42 }), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, {}), none);
  assert.equal(buildDispatch([ID1], 'root-1', undefined, { transcriptPath: undefined }), none);
});

test('S6: a transcriptPath makes the text differ from the bare call by exactly the session-dir flag', () => {
  const bare = buildDispatch([ID1], 'root-1');
  const withDir = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.notEqual(withDir, bare);
  assert.equal(withDir.replace(` ${dirFlag('/p/x')}`, ''), bare);
});

// ---- S7: nudge unchanged --------------------------------------------------------------------------

test('S7: buildNudge has no description label and no --session-dir', () => {
  const text = buildNudge([ID1]);
  assert.ok(!text.includes('description "retrospective:'), text);
  assert.ok(!text.includes('--session-dir'), text);
});

test('S7: a nudge-mode hook run with transcript_path nudges with --session-dir and no label', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s7'), rid(1), { extra: { transcript_path: 'C:/p/899f5f7e.jsonl' } }));
    const text = contextOf(res);
    assert.ok(text?.includes(NUDGE), res.stdout);
    assert.ok(!text.includes(DISPATCH), text);
    assert.ok(!text.includes('description "retrospective:'), text);
    assert.ok(text.includes(dirFlag('C:/p/899f5f7e')), text);
  });
});

// ---- S8: fail-open --------------------------------------------------------------------------------

test('S8: retro-trigger with malformed, empty or run-less stdin exits 0 with {}', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    for (const input of ['not json {', null, { session_id: sid('s8'), transcript_path: 'C:/p/899f5f7e.jsonl' }]) {
      const res = await trigger(input);
      assert.equal(res.status, 0, res.stderr);
      assert.deepEqual(res.json, {}, JSON.stringify(input) + ' -> ' + res.stdout);
    }
  });
});

test('S8: retro-backstop with malformed stdin exits 0 with {}', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [] }, async ({ stop }) => {
    for (const input of ['not json {', null]) {
      const res = await stop(input);
      assert.equal(res.status, 0, res.stderr);
      assert.deepEqual(res.json, {}, String(input) + ' -> ' + res.stdout);
    }
  });
});

test('S8: director-watch run() on malformed input answers {} and does not throw', async () => {
  const out = await watchRun({ tool_name: 'mcp__mcp-task-orchestrator__runs_read', transcript_path: 'C:/p/x.jsonl' }, {}, watchOpts());
  assert.deepEqual(out, {});
});

// ---- S9-S11: path normalisation ------------------------------------------------------------------

test('S9: a backslash Windows path becomes forward slashes', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:\\p\\x.jsonl' });
  assert.ok(text.includes(dirFlag('C:/p/x')), text);
  assert.ok(!text.includes('C:\\p'), text);
});

test('S10: only the case-sensitive .jsonl suffix qualifies', () => {
  for (const p of ['/p/x.txt', '/p/x.JSONL', '/p/x.Jsonl', '/p/x', '/p/x.jsonl ', '/p/x.jsonl/y']) {
    const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: p });
    assert.ok(!text.includes('--session-dir'), `${p} -> ${text}`);
    assert.ok(text.includes(desc('1b056448')), text);
  }
});

test('S11: exactly one trailing .jsonl is stripped', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl.jsonl' });
  assert.ok(text.includes(dirFlag('/p/x.jsonl')), text);
  assert.ok(!text.includes(dirFlag('/p/x')), text);
});

// ---- S12: run8 rule -------------------------------------------------------------------------------

test('S12: an upper-case run id is lower-cased and cut to 8 characters', () => {
  const text = buildDispatch(['ABCDEF12-3504-462E-82FE-5E4549095427'], 'root-1');
  assert.ok(text.includes(desc('abcdef12')), text);
});

test('S12: a short id is used whole; an empty list reads unknown', () => {
  assert.ok(buildDispatch(['abc'], 'root-1').includes(desc('abc')));
  const none = buildDispatch([], 'root-1');
  assert.ok(none.includes(desc('unknown')), none);
  assert.ok(!none.includes('--run'), none);
});

test('S12 probe: an id of exactly 8 characters is whole, one of 9 is cut', () => {
  assert.ok(buildDispatch(['abcdefgh'], 'root-1').includes(desc('abcdefgh')));
  const nine = buildDispatch(['abcdefghi'], 'root-1');
  assert.ok(nine.includes(desc('abcdefgh')), nine);
  assert.ok(!nine.includes('retrospective:abcdefghi'), nine);
});

// ---- S13: existing pins hold with a transcriptPath ------------------------------------------------

test('S13: with a transcriptPath there is still one /session-retrospective and one --run per run', () => {
  for (const ids of [[], [ID1], [ID2, ID3], [ID1, ID2, ID3]]) {
    const text = buildDispatch(ids, 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
    assert.equal(count(text, '/session-retrospective'), 1, text);
    assert.equal(count(text, '--run'), ids.length, text);
    assert.ok(text.includes('ONE retrospective'), text);
  }
});

// ---- probes ---------------------------------------------------------------------------------------

test('probe: a path with spaces stays inside the quotes', () => {
  const text = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: 'C:\\My Docs\\a b.jsonl' });
  assert.ok(text.includes(dirFlag('C:/My Docs/a b')), text);
});

test('probe: the same call twice gives identical text', () => {
  const a = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const b = buildDispatch([ID2, ID3], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  assert.equal(a, b);
});

test('probe: a fixCycle map plus transcriptPath keeps both the base-run sentence and the session folder', () => {
  const plain = buildDispatch([ID1], 'root-1', undefined, { transcriptPath: '/p/x.jsonl' });
  const text = buildDispatch([ID1], 'root-1', { [ID1]: 'r-base-run' }, { transcriptPath: '/p/x.jsonl' });
  assert.ok(!plain.includes('r-base-run'), plain);
  assert.ok(text.includes('r-base-run'), text);
  assert.ok(text.includes(dirFlag('/p/x')), text);
  assert.ok(text.includes(desc('1b056448')), text);
});

// ======================================================================================================
// W1 (item 20fa8b17): retrospective firing policy. BLIND author: written from the frozen task-scope and
// test-plan notes (steps 1-12, D1-D11, AC1-AC10), the supplied declarations and the existing harness. Oracle:
// task-scope steps and acceptance criteria, cited per scenario. Scenario ids Sn are those of the test-plan.
// Fixtures: scopeFixture in retro-fake-runs.mjs (a scope item, closed runs of it, implementation items with
// session-tracking notes); the anchor is the earliest-opened run, rid(1). Timing probes sit 2 s (claim age)
// or 1 min / 1 h (hourly limit, abandonment) either side of their limit.
// ======================================================================================================

const CR = String.fromCharCode(13);
const SCOPE_CFG = (o = {}) => retroConfig({ mode: 'dispatch', boundary: 'scope', ...o });
const invocations = (text) => count(text, '/session-retrospective');
const runFlags = (text) => count(text, '--run ');
const fixtureOpts = (sf, config, behavior) => ({ config, runs: sf.runs, items: sf.items, notes: sf.notes, behavior });
const note = (...lines) => [...lines, ''].join(NL);

/** Asserts a Stop answer is a dispatch block naming every id; returns the reason. */
function assertDispatchBlock(res, ids = []) {
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.json?.decision, 'block', res.stdout);
  const reason = res.json.reason;
  assert.ok(reason.includes(DISPATCH), reason);
  for (const id of ids) assert.ok(reason.includes(`--run ${id}`), `${id} missing from: ${reason}`);
  return reason;
}
/** Asserts {} on stdout, exit 0. */
function assertSilentRun(res) {
  assert.equal(res.status, 0, res.stderr);
  assert.deepEqual(res.json, {}, res.stdout);
}
/** No mark of any kind was written. */
function assertNoMarks(fake) {
  assert.equal(fake.markOutcomes.length, 0, JSON.stringify(fake.markOutcomes));
}
/** The run carries retro-skipped and retro, written in that order. */
function assertSkipped(fake, runIds) {
  for (const id of runIds) {
    const keys = fake.markOutcomes.filter((o) => o.runId === id && o.marked).map((o) => o.key);
    assert.ok(keys.includes('retro-skipped') && keys.includes('retro'), `${id}: ${JSON.stringify(keys)}`);
    assert.ok(keys.indexOf('retro-skipped') < keys.indexOf('retro'), `${id}: retro-skipped before retro: ${JSON.stringify(keys)}`);
    assert.ok(!keys.includes('retro-claim'), `${id} claimed: ${JSON.stringify(keys)}`);
  }
}

// ---- S1 (AC1, TS1-TS2): one directive per completed scope, naming every run -------------------------

/** 12 runs of one scope: 10 plain, one fix-cycle run with the scope, one fix-cycle run without a scopeId. */
function bigGroup() {
  const sf = scopeFixture({ runs: 12, labels: { 0: 'wave', 10: 'wave-a1' } });
  sf.runs[11].label = 'wave-a2';
  delete sf.runs[11].scopeId;
  return sf;
}

test('S1: 10 runs + 2 fix-cycle runs of one scope: nothing while the scope is in work; once terminal ONE directive naming all 12', async () => {
  const sf = bigGroup();
  sf.items[0].role = 'work';
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    const s = sid('s1');
    assertSilentRun(await stop(stopPayload(s)));
    assertNoMarks(fake);
    fake.setItem(SCOPE, { role: 'terminal', roleChangedAt: ago(2 * HOUR) });
    const reason = assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    assert.equal(invocations(reason), 1, reason);
    assert.equal(runFlags(reason), 12, reason);
    assert.deepEqual([...fake.won('retro-claim')].sort(), [...sf.runIds].sort());
    assert.deepEqual(fake.won('retro'), [], 'a claim is not a done mark');
    for (const id of sf.runIds) assert.equal(fake.hasMark(id, 'retro-claim'), true, id);
  });
});

test('S1 probe: a replayed Stop pass does not claim again (no second write of retro-claim, no second directive)', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    const s = sid('s1r');
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    const writes = fake.markOutcomes.length;
    assertSilentRun(await stop(stopPayload(s)));
    assert.equal(fake.markOutcomes.length, writes, JSON.stringify(fake.markOutcomes));
    assert.equal(fake.markOutcomes.filter((o) => o.key === 'retro-claim').length, 2);
  });
});

test('S1 probe: a run the list answers twice is named once and claimed once', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG(), { dup: true }), async ({ stop, fake }) => {
    const reason = assertDispatchBlock(await stop(stopPayload(sid('s1d'))), sf.runIds);
    for (const id of sf.runIds) assert.equal(count(reason, `--run ${id}`), 1, reason);
    for (const id of sf.runIds) assert.equal(fake.markOutcomes.filter((o) => o.runId === id && o.key === 'retro-claim').length, 1, id);
  });
});

// ---- S2 (AC1): a scope is not due while anything of it is open --------------------------------------

const blockers = {
  'an open run of the scope': {
    apply: (sf) => sf.runs.push({ runId: rid(50), scopeId: SCOPE, status: 'open', members: 3, terminal: 0, marks: {}, openedAt: ago(HOUR) }),
    clear: (fake) => Object.assign(fake.runs.find((r) => r.runId === rid(50)), { status: 'closed', closedAt: ago(30 * MIN) }),
  },
  'a paused run of the scope': {
    apply: (sf) => sf.runs.push({ runId: rid(51), scopeId: SCOPE, status: 'paused', members: 3, terminal: 0, marks: {}, openedAt: ago(HOUR) }),
    clear: (fake) => Object.assign(fake.runs.find((r) => r.runId === rid(51)), { status: 'closed', closedAt: ago(30 * MIN) }),
  },
  'a subtree item in review': {
    apply: (sf) => {
      sf.items.find((i) => i.id === sf.implIds[0]).role = 'review';
    },
    clear: (fake, sf) => fake.setItem(sf.implIds[0], { role: 'terminal' }),
  },
  'a subtree item in work': {
    apply: (sf) => {
      sf.items.find((i) => i.id === sf.implIds[1]).role = 'work';
    },
    clear: (fake, sf) => fake.setItem(sf.implIds[1], { role: 'terminal' }),
  },
};
for (const [name, b] of Object.entries(blockers)) {
  test(`S2: a terminal scope with ${name} is not due: no directive, no mark; clearing it makes the scope due`, async () => {
    const sf = scopeFixture({ runs: 2 });
    b.apply(sf);
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      const s = sid('s2');
      assertSilentRun(await stop(stopPayload(s)));
      assertNoMarks(fake);
      b.clear(fake, sf);
      assertDispatchBlock(await stop(stopPayload(s)), [rid(1), rid(2)]);
    });
  });
}

// ---- S3 (D3): scopeId == rootId is no scope: due at close ---------------------------------------------

for (const [label, scopeId] of [['the root id', ROOT], ['an empty string', ''], ['absent', undefined]]) {
  test(`S3: a run whose scopeId is ${label} is due at its close: the trigger dispatches`, async () => {
    const sf = scopeFixture({ runs: 1, impl: [{}, {}, {}] });
    const run = sf.runs[0];
    if (scopeId === undefined) delete run.scopeId;
    else run.scopeId = scopeId;
    run.seats = sf.implIds.map((itemId) => ({ itemId }));
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ trigger, fake }) => {
      const res = await trigger(closePayload(sid('s3'), rid(1)));
      assert.equal(res.status, 0, res.stderr);
      const text = contextOf(res);
      assert.ok(text?.includes(DISPATCH), res.stdout);
      assert.ok(text.includes(`--run ${rid(1)}`), text);
      assert.deepEqual(fake.won('retro-claim'), [rid(1)]);
    });
  });
}

// ---- S4, S5 (AC2, TS4): the triage gate ---------------------------------------------------------------

const CLEAN2 = () => scopeFixture({ runs: 2, impl: [{}, {}] });

test('S4: a clean 2-item group: retro-skipped then retro on every run, nothing written to the session', async () => {
  const sf = CLEAN2();
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    const res = await stop(stopPayload(sid('s4')));
    assertSilentRun(res);
    assert.equal(res.stdout.trim(), '{}');
    assertSkipped(fake, sf.runIds);
  });
});

const friction = (text) => note('Outcome: success', `Friction: ${text}`, 'Deviations: none');
const signals = {
  'Friction: x': (sf) => (sf.notes[sf.implIds[0]]['session-tracking'] = friction('slow gate')),
  'Deviations: x': (sf) => (sf.notes[sf.implIds[1]]['session-tracking'] = note('Outcome: success', 'Friction: none', 'Deviations: changed the schema')),
  'Outcome: partial': (sf) => (sf.notes[sf.implIds[0]]['session-tracking'] = note('Outcome: partial', 'Friction: none', 'Deviations: none')),
  'a fix-cycle run': (sf) => {
    sf.runs[0].label = 'wave';
    sf.runs[1].label = 'wave-a1';
  },
  'an agent-observation created after the first openedAt': (sf) =>
    sf.items.push({ id: iid(700), type: 'agent-observation', role: 'queue', parent: ROOT, createdAt: ago(HOUR) }),
  'a third implementation item (minItems 3)': (sf) => {
    sf.items.push({ id: iid(701), type: 'feature-task', role: 'terminal', parent: SCOPE, createdAt: ago(10 * DAY), roleChangedAt: ago(3 * HOUR) });
    sf.notes[iid(701)] = { 'session-tracking': CLEAN_NOTE };
  },
  'a missing session-tracking note': (sf) => delete sf.notes[sf.implIds[1]],
  'a note without a Deviations line': (sf) => (sf.notes[sf.implIds[0]]['session-tracking'] = note('Outcome: success', 'Friction: none')),
};
for (const [name, mutate] of Object.entries(signals)) {
  test(`S5: ${name} alone makes the clean group dispatch (not skipped)`, async () => {
    const sf = CLEAN2();
    mutate(sf);
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      assertDispatchBlock(await stop(stopPayload(sid('s5'))), sf.runIds);
      assert.deepEqual(fake.won('retro-skipped'), []);
      assert.deepEqual(fake.won('retro'), []);
      assert.deepEqual([...fake.won('retro-claim')].sort(), [...sf.runIds].sort());
    });
  });
}

test('S5: still skipped: 2 implementation items beside excluded-type items (container, proposal, trend, an old observation)', async () => {
  const sf = CLEAN2();
  const kid = (n, type, createdAt) => sf.items.push({ id: iid(710 + n), type, role: 'terminal', parent: SCOPE, createdAt, roleChangedAt: ago(3 * HOUR) });
  kid(0, 'container', ago(10 * DAY));
  kid(1, 'improvement-proposal', ago(10 * DAY));
  kid(2, 'retrospective-trend', ago(10 * DAY));
  kid(3, 'agent-observation', ago(10 * DAY));
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s5x'))));
    assertSkipped(fake, sf.runIds);
  });
});

test('S5 probe: Friction / Deviations none in any casing, one trailing period, padded, with CRLF line ends: still skipped', async () => {
  const bodies = [
    note('Outcome: success', 'Friction: NONE', 'Deviations: None'),
    note('Outcome: implemented', 'Friction: none.', 'Deviations: none.'),
    note('Outcome: success', 'Friction:   none  ', 'Deviations: none'),
    ['Outcome: success', 'Friction: None.', 'Deviations: none', ''].join(CR + NL),
  ];
  for (const body of bodies) {
    const sf = CLEAN2();
    sf.notes[sf.implIds[0]]['session-tracking'] = body;
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      assertSilentRun(await stop(stopPayload(sid('s5p'))));
      assertSkipped(fake, sf.runIds);
    });
  }
});

test('S5 probe: two trailing periods are not "none": the group dispatches', async () => {
  const sf = CLEAN2();
  sf.notes[sf.implIds[0]]['session-tracking'] = friction('none..');
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop }) => {
    assertDispatchBlock(await stop(stopPayload(sid('s5pp'))), sf.runIds);
  });
});

test('S5 probe: a run labelled -a0 is not a fix-cycle run: the clean group is still skipped', async () => {
  const sf = scopeFixture({ runs: 2, impl: [{}, {}], labels: { 0: 'wave', 1: 'wave-a0' } });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s5a0'))));
    assertSkipped(fake, sf.runIds);
  });
});

test('S5 probe: the minItems boundary: 3 clean items dispatch at minItems 3 and skip at 4; 2 dispatch at 2', async () => {
  for (const [impl, minItems, dispatches] of [[3, 3, true], [3, 4, false], [2, 2, true], [2, 3, false]]) {
    const sf = scopeFixture({ runs: 2, impl: Array.from({ length: impl }, () => ({})) });
    await withFixture(fixtureOpts(sf, SCOPE_CFG({ minItems })), async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('s5m')));
      if (dispatches) assertDispatchBlock(res, sf.runIds);
      else {
        assertSilentRun(res);
        assertSkipped(fake, sf.runIds);
      }
    });
  }
});

// ---- S6 (hook level): minItems and the older dispatchThreshold ----------------------------------------

test('S6: minItems 0 makes the clean 2-item group dispatch', async () => {
  const sf = CLEAN2();
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ minItems: 0 })), async ({ stop }) => {
    assertDispatchBlock(await stop(stopPayload(sid('s60'))), sf.runIds);
  });
});

test('S6: dispatchThreshold 5 alone acts as minItems 5 (3 clean items skip); with minItems 2 beside it, minItems wins', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ threshold: 5 })), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s65'))));
    assertSkipped(fake, sf.runIds);
  });
  const both = scopeFixture({ runs: 2, impl: [{}, {}] });
  await withFixture(fixtureOpts(both, SCOPE_CFG({ threshold: 5, minItems: 2 })), async ({ stop }) => {
    assertDispatchBlock(await stop(stopPayload(sid('s66'))), both.runIds);
  });
});

test('S6: an invalid boundary acts as scope; an omitted boundary acts as scope', async () => {
  for (const boundary of ['bogus', null]) {
    const sf = scopeFixture({ runs: 2 });
    sf.items[0].role = 'work';
    await withFixture(fixtureOpts(sf, SCOPE_CFG({ boundary })), async ({ stop, fake }) => {
      assertSilentRun(await stop(stopPayload(sid('s6b'))));
      assertNoMarks(fake);
      fake.setItem(SCOPE, { role: 'terminal' });
      assertDispatchBlock(await stop(stopPayload(sid('s6c'))), sf.runIds);
    });
  }
});

// ---- S7, S8 (AC3): claim and done are separate; a retrospective that stops without finishing -----------

const retrospectiveTranscript = (sbx, name, anchor, ids) =>
  writeTranscript(sbx, name, `/session-retrospective ${ids.map((i) => `--run ${i}`).join(' ')} (anchor ${anchor})`);
const subagentInput = (session, transcriptPath) => ({ session_id: session, agent_id: 'agent-1', agent_type: 'general-purpose', agent_transcript_path: transcriptPath });

test('S7: a retrospective that stops without retro: the next Stop retries at once; its second failure fails the group with one nudge, then silence', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, subagentStop, fake }) => {
    const s = sid('s7');
    const anchor = rid(1);
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    const claimed = readRetroState(sbx, s);
    assert.equal(claimed.claims.length, 1, JSON.stringify(claimed));
    assert.equal(claimed.claims[0].anchor, anchor);
    assert.equal(claimed.claims[0].key, 'retro-claim');
    assert.equal(claimed.claims[0].failed, false);
    assert.ok(Date.now() - Date.parse(claimed.lastDispatchAt) < MIN, 'the hourly limit is armed: ' + claimed.lastDispatchAt);

    const transcript = retrospectiveTranscript(sbx, 'sub1.jsonl', anchor, sf.runIds);
    assertSilentRun(await subagentStop(subagentInput(s, transcript)));
    assert.equal(readRetroState(sbx, s).claims[0].failed, true);

    const retry = await stop(stopPayload(s));
    assertDispatchBlock(retry, sf.runIds);
    assert.deepEqual([...fake.won('retro-retry')].sort(), [...sf.runIds].sort(), 'retro-retry on every run, though the hourly limit was just armed');

    assertSilentRun(await subagentStop(subagentInput(s, retrospectiveTranscript(sbx, 'sub2.jsonl', anchor, sf.runIds))));
    const failed = await stop(stopPayload(s));
    assert.equal(failed.status, 0, failed.stderr);
    assert.equal(failed.json?.decision, 'block', failed.stdout);
    assert.equal(count(failed.json.reason, 'Retrospective suggested'), 1, failed.json.reason);
    assert.ok(!failed.json.reason.includes(DISPATCH), failed.json.reason);
    assert.deepEqual([...fake.won('retro-failed')].sort(), [...sf.runIds].sort());
    assertSilentRun(await stop(stopPayload(s)));
    assert.deepEqual(fake.won('retro'), []);
  });
});

test('S7 probe: a SubagentStop whose transcript lacks the command, or the anchor id, is not a failed retrospective', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, subagentStop }) => {
    const s = sid('s7n');
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    const other = writeTranscript(sbx, 'other.jsonl', `/session-retrospective --run ${rid(77)} (some other anchor)`);
    assertSilentRun(await subagentStop(subagentInput(s, other)));
    assert.equal(readRetroState(sbx, s).claims[0].failed, false, 'a retrospective of another anchor');
  });
});

test('S8: a SubagentStop with retro present on the anchor drops the claim; the next Stop is {}', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, subagentStop, fake }) => {
    const s = sid('s8a');
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    for (const id of sf.runIds) fake.setMark(id, 'retro');
    const transcript = retrospectiveTranscript(sbx, 'done.jsonl', rid(1), sf.runIds);
    assertSilentRun(await subagentStop(subagentInput(s, transcript)));
    assert.deepEqual(readRetroState(sbx, s).claims, []);
    assertSilentRun(await stop(stopPayload(s)));
  });
});

test('S8: a transcript without the command keeps the claim; the next Stop is {}', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, subagentStop }) => {
    const s = sid('s8b');
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    const unrelated = writeTranscript(sbx, 'unrelated.jsonl', 'implement the item');
    assertSilentRun(await subagentStop(subagentInput(s, unrelated)));
    const claim = readRetroState(sbx, s).claims[0];
    assert.equal(claim.failed, false);
    assert.equal(claim.anchor, rid(1));
    assertSilentRun(await stop(stopPayload(s)));
  });
});

test('S8 probe: a missing transcript path (absent, or a file that does not exist) keeps the claim unfailed', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, subagentStop }) => {
    const s = sid('s8c');
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    assertSilentRun(await subagentStop({ session_id: s, agent_id: 'agent-1', agent_type: 'general-purpose' }));
    assertSilentRun(await subagentStop(subagentInput(s, join(sbx.base, 'does-not-exist.jsonl'))));
    assert.equal(readRetroState(sbx, s).claims[0].failed, false);
  });
});

test('S8 probe: a SubagentStop does no REST work with no claim, in mode off, or under boundary run', async () => {
  const sf = scopeFixture({ runs: 2 });
  const claim = { anchor: rid(1), runs: sf.runIds, key: 'retro-claim', at: new Date().toISOString(), failed: false };
  const cases = [
    ['no claim', SCOPE_CFG(), false],
    ['mode off', SCOPE_CFG({ mode: 'off' }), true],
    ['boundary run', SCOPE_CFG({ boundary: 'run' }), true],
  ];
  for (const [label, config, withClaim] of cases) {
    await withFixture(fixtureOpts(sf, config), async ({ sbx, subagentStop, fake }) => {
      const s = sid('s8d');
      const transcript = retrospectiveTranscript(sbx, 'x.jsonl', rid(1), sf.runIds);
      if (withClaim) writeRetroState(sbx, s, { claims: [claim] });
      assertSilentRun(await subagentStop(subagentInput(s, transcript)));
      assert.equal(fake.requests.length, 0, `${label}: ${JSON.stringify(fake.requests)}`);
      if (withClaim) assert.equal(readRetroState(sbx, s).claims[0].failed, false, label);
    });
  }
});

// ---- S9 (AC4): another session's claim is retried once it is old enough -----------------------------

test('S9: another session\'s retro-claim 19m58s old: {}; 20m02s old: a directive with retro-retry on every run', async () => {
  for (const [ageMs, retries] of [[20 * MIN - 2000, false], [20 * MIN + 2000, true]]) {
    const sf = scopeFixture({ runs: 2 });
    for (const r of sf.runs) r.marks = { 'retro-claim': ago(ageMs) };
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('s9')));
      if (retries) {
        assertDispatchBlock(res, sf.runIds);
        assert.deepEqual([...fake.won('retro-retry')].sort(), [...sf.runIds].sort());
      } else {
        assertSilentRun(res);
        assertNoMarks(fake);
      }
    });
  }
});

test('S9 probe: a second failure seen by another session: retro-retry 21 min old -> retro-failed and one nudge; 19m58s old -> {}', async () => {
  for (const [retryAge, fails] of [[21 * MIN, true], [20 * MIN - 2000, false]]) {
    const sf = scopeFixture({ runs: 2 });
    for (const r of sf.runs) r.marks = { 'retro-claim': ago(45 * MIN), 'retro-retry': ago(retryAge) };
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('s9f')));
      if (fails) {
        assert.equal(res.json?.decision, 'block', res.stdout);
        assert.equal(count(res.json.reason, 'Retrospective suggested'), 1, res.json.reason);
        assert.deepEqual([...fake.won('retro-failed')].sort(), [...sf.runIds].sort());
        assertSilentRun(await stop(stopPayload(sid('s9g'))));
      } else {
        assertSilentRun(res);
        assertNoMarks(fake);
      }
    });
  }
});

// ---- S10 (AC5): an abandoned scope -------------------------------------------------------------------

test('S10: a scope still in work, no open run, newest close 8 d old: one [stale] directive, then {} for another session', async () => {
  const sf = scopeFixture({ runs: 2, scopeRole: 'work', closedMs: 8 * DAY });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop }) => {
    const reason = assertDispatchBlock(await stop(stopPayload(sid('s10a'))), sf.runIds);
    assert.ok(reason.includes('[stale]'), reason);
    assert.ok(reason.includes(SCOPE.slice(0, 8)), reason);
    assertSilentRun(await stop(stopPayload(sid('s10b'))));
  });
});

test('S10: newest close 6 d old: {} and no mark; probe 7 d + 1 h: dispatches; 7 d - 1 h: {}', async () => {
  for (const [closedMs, dispatches] of [[6 * DAY, false], [7 * DAY + HOUR, true], [7 * DAY - HOUR, false]]) {
    const sf = scopeFixture({ runs: 2, scopeRole: 'work', closedMs });
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('s10c')));
      if (dispatches) assert.ok(assertDispatchBlock(res, sf.runIds).includes('[stale]'));
      else {
        assertSilentRun(res);
        assertNoMarks(fake);
      }
    });
  }
});

test('S10: a scope that is not terminal and has an open run is not abandoned however old its last close', async () => {
  const sf = scopeFixture({ runs: 2, scopeRole: 'work', closedMs: 9 * DAY });
  sf.runs.push({ runId: rid(60), scopeId: SCOPE, status: 'open', members: 3, terminal: 0, marks: {}, openedAt: ago(HOUR) });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s10o'))));
    assertNoMarks(fake);
  });
});

// ---- S11 (AC9): at most one first dispatch an hour; two due groups share a directive -----------------

test('S11: lastDispatchAt 10 min ago: a newly due group waits ({}); 61 min ago: it dispatches; probe 59 min: waits', async () => {
  for (const [lastMs, dispatches] of [[10 * MIN, false], [61 * MIN, true], [59 * MIN, false]]) {
    const sf = scopeFixture({ runs: 2 });
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop, fake }) => {
      const s = sid('s11');
      writeRetroState(sbx, s, { lastDispatchAt: ago(lastMs) });
      const res = await stop(stopPayload(s));
      if (dispatches) assertDispatchBlock(res, sf.runIds);
      else {
        assertSilentRun(res);
        assertNoMarks(fake);
      }
    });
  }
});

test('S11: two groups due in one pass share ONE directive naming every run of both', async () => {
  const a = scopeFixture({ runs: 2 });
  const b = scopeFixture({ scopeId: iid(901), runs: 2, runStart: 3, itemStart: 10 });
  const sf = mergeFixtures(a, b);
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    const reason = assertDispatchBlock(await stop(stopPayload(sid('s11b'))), sf.runIds);
    assert.equal(invocations(reason), 1, reason);
    assert.equal(runFlags(reason), 4, reason);
    assert.equal(fake.won('retro-claim').length, 4);
  });
});

// ---- S12 (AC8): ownership holds in both modes ---------------------------------------------------------

test('S12: nudge mode: a session that closed no run of the group stays silent; the session whose trigger saw a close gets one nudge, one invocation', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ mode: 'nudge' })), async ({ stop, trigger }) => {
    assertSilentRun(await stop(stopPayload(sid('s12a'))));
    const owner = sid('s12b');
    const res = await trigger(closePayload(owner, rid(1)));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes('Retrospective suggested'), res.stdout);
    assert.ok(!text.includes(DISPATCH), text);
    assert.equal(invocations(text), 1, text);
    for (const id of sf.runIds) assert.ok(text.includes(`--run ${id}`), text);
    assertSilentRun(await stop(stopPayload(owner)));
  });
});

test('S12: dispatch mode, unowned: the scope delivered 30 min ago waits ({}); 61 min ago it dispatches; probe 59 min: waits', async () => {
  for (const [changedMs, dispatches] of [[30 * MIN, false], [61 * MIN, true], [59 * MIN, false]]) {
    const sf = scopeFixture({ runs: 2, scopeChangedMs: changedMs });
    await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('s12c')));
      if (dispatches) assertDispatchBlock(res, sf.runIds);
      else {
        assertSilentRun(res);
        assertNoMarks(fake);
      }
    });
  }
});

test('S12: dispatch mode, owned: a session that closed a run of the group dispatches at once, scope delivered 30 min ago', async () => {
  const sf = scopeFixture({ runs: 2, scopeChangedMs: 30 * MIN });
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ sbx, stop }) => {
    const s = sid('s12d');
    writeRetroState(sbx, s, { closes: [rid(2)], sawClose: true });
    assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
  });
});

test('S12: nudge mode, unowned, however old the scope: {}', async () => {
  const sf = scopeFixture({ runs: 2, scopeChangedMs: 3 * HOUR });
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ mode: 'nudge' })), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s12e'))));
    assertNoMarks(fake);
  });
});

// ---- S13 (TS10): a director session ---------------------------------------------------------------------

test('S13: a director session with REST skips the clean 2-item group: retro-skipped and retro, no directive', async () => {
  const sf = CLEAN2();
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ mode: 'nudge' })), async ({ sbx, stop, fake }) => {
    const s = sid('s13a');
    markDirector(sbx, s);
    assertSilentRun(await stop(stopPayload(s)));
    assertSkipped(fake, sf.runIds);
  });
});

test('S13: a director session is forced to dispatch even when the configured mode is nudge', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG({ mode: 'nudge' })), async ({ sbx, stop }) => {
    const s = sid('s13b');
    markDirector(sbx, s);
    const reason = assertDispatchBlock(await stop(stopPayload(s)), sf.runIds);
    assert.ok(!reason.includes('Retrospective suggested'), reason);
  });
});

// ---- S16 (AC10): every REST failure fails open --------------------------------------------------------

const failures = {
  'the run list answers 500': { sf: () => scopeFixture({ runs: 2 }), behavior: { list: 'http500' } },
  'the run list never answers': { sf: () => scopeFixture({ runs: 2 }), behavior: { list: 'hang' } },
  'the run list answers malformed JSON': { sf: () => scopeFixture({ runs: 2 }), behavior: { list: 'badjson' } },
  'the scope item read answers 500': { sf: () => scopeFixture({ runs: 2 }), behavior: { itemsGet: 'http500' } },
  'the subtree list answers 500': { sf: () => scopeFixture({ runs: 2 }), behavior: { itemsList: 'http500' } },
  'the notes read answers 500 (clean 2-item group)': { sf: CLEAN2, behavior: { notes: 'http500' } },
  'the scope item is unknown to the server': { sf: () => scopeFixture({ runs: 2 }), mutate: (sf) => sf.items.shift() },
};
for (const [name, f] of Object.entries(failures)) {
  test(`S16: ${name}: exit 0, {}, no mark`, async () => {
    const sf = f.sf();
    f.mutate?.(sf);
    await withFixture(fixtureOpts(sf, SCOPE_CFG(), f.behavior), async ({ stop, fake }) => {
      assertSilentRun(await stop(stopPayload(sid('s16'))));
      assertNoMarks(fake);
    });
  });
}

test('S16: a lost anchor claim (another session wrote retro-claim after this list) gives no directive', async () => {
  const sf = scopeFixture({ runs: 2 });
  await withFixture(fixtureOpts(sf, SCOPE_CFG(), { stale: true }), async ({ stop, fake }) => {
    fake.setMark(rid(1), 'retro-claim');
    assertSilentRun(await stop(stopPayload(sid('s16l'))));
    assert.ok(fake.markOutcomes.some((o) => o.runId === rid(1) && o.key === 'retro-claim' && o.marked === false), JSON.stringify(fake.markOutcomes));
    assert.ok(!fake.won('retro-claim').includes(rid(1)), 'the anchor claim was lost');
  });
});

test('S16: an anchor claimed by another session a minute ago: no directive, no write', async () => {
  const sf = scopeFixture({ runs: 2 });
  for (const r of sf.runs) r.marks = { 'retro-claim': ago(MIN) };
  await withFixture(fixtureOpts(sf, SCOPE_CFG()), async ({ stop, fake }) => {
    assertSilentRun(await stop(stopPayload(sid('s16c'))));
    assertNoMarks(fake);
  });
});

// ---- S17: the hook is registered ---------------------------------------------------------------------

test('S17: hooks-config.json SubagentStop "" group runs retro-subagent-stop.mjs (timeout 5) beside phase-guard.mjs', () => {
  const cfgJson = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf8'));
  const groups = cfgJson.hooks.SubagentStop.filter((g) => g.matcher === '');
  assert.equal(groups.length, 1, JSON.stringify(cfgJson.hooks.SubagentStop));
  const commands = groups[0].hooks.map((h) => h.command);
  assert.ok(commands.some((c) => c.includes('phase-guard.mjs')), commands.join(' | '));
  const retro = groups[0].hooks.find((h) => h.command.includes('retro-subagent-stop.mjs'));
  assert.ok(retro, commands.join(' | '));
  assert.equal(retro.type, 'command');
  assert.equal(retro.timeout, 5);
  assert.ok(retro.command.includes('${CLAUDE_PLUGIN_ROOT}/hooks/retro-subagent-stop.mjs'), retro.command);
});
