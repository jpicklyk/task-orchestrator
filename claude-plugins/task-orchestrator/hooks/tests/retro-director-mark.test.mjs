// Director-mode retrospective marking (item 0effb9ab, scenarios S5-S10 of its test-plan). BLIND author:
// written from the frozen diagnosis (D4, D6) and test-plan notes, the supplied declarations and the
// existing hook tests. Oracle: D4 (in a director session directorConfig yields {mode: dispatch,
// dispatchThreshold: 0}, so the REST path marks every run it dispatches; a lost mark race stays silent; a
// failed mark still dispatches; director-watch skips a run whose closedAt is more than 24h old and treats
// an absent or unparseable closedAt as today) and D6 (buildDispatch emits ONE invocation with repeated
// --run; buildNudge is unchanged). api-reference runs_write mark: first write wins, replay marked:false.

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
} from './retro-fake-runs.mjs';
import { directorConfig, buildDispatch, buildNudge } from '../retro-lib.mjs';
import { run as watchRun } from '../director-watch.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });
const stopPayload = (session) => ({ session_id: session });

// ---- S5: a director session marks the run it dispatches, below the threshold ---------------------

test('S5: a director trigger close of a 1-terminal run dispatches with --run <id> and marks it once', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, trigger, fake }) => {
    const session = sid('s5t');
    markDirector(sbx, session);
    const res = await trigger(closePayload(session, rid(1)));
    assert.equal(res.status, 0, res.stderr);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(`--run ${rid(1)}`), text);
    assert.equal(fake.marks.length, 1, JSON.stringify(fake.requests));
    assert.deepEqual(fake.marks[0].body.ops[0], { op: 'mark', runId: rid(1), key: 'retro' });
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

test('S5: a director Stop (backstop) of a 1-terminal run blocks with --run <id> and marks it once', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, stop, fake }) => {
    const session = sid('s5b');
    markDirector(sbx, session);
    const res = await stop(stopPayload(session));
    assert.equal(res.status, 0, res.stderr);
    assert.equal(res.json?.decision, 'block', res.stdout);
    assert.ok(res.json.reason.includes(DISPATCH), res.json.reason);
    assert.ok(res.json.reason.includes(`--run ${rid(1)}`), res.json.reason);
    assert.equal(fake.marks.length, 1, JSON.stringify(fake.requests));
    assert.deepEqual(fake.marks[0].body.ops[0], { op: 'mark', runId: rid(1), key: 'retro' });
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

test('S5: a director session in nudge mode also marks the run it dispatches', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, trigger, fake }) => {
    const session = sid('s5n');
    markDirector(sbx, session);
    const text = contextOf(await trigger(closePayload(session, rid(1))));
    assert.ok(text?.includes(DISPATCH), text);
    assert.ok(!text.includes(NUDGE), text);
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

// ---- S6: directorConfig ---------------------------------------------------------------------------

test('S6: directorConfig of {nudge, 3} and of {dispatch, 3} both deep-equal {mode: dispatch, dispatchThreshold: 0}', () => {
  assert.deepEqual(directorConfig({ mode: 'nudge', dispatchThreshold: 3 }), { mode: 'dispatch', dispatchThreshold: 0 });
  assert.deepEqual(directorConfig({ mode: 'dispatch', dispatchThreshold: 3 }), { mode: 'dispatch', dispatchThreshold: 0 });
});

test('S6: directorConfig leaves mode off unchanged', () => {
  assert.deepEqual(directorConfig({ mode: 'off', dispatchThreshold: 3 }), { mode: 'off', dispatchThreshold: 3 });
});

// ---- S7: a lost race stays silent -----------------------------------------------------------------

test('S7: director session 1 marks and dispatches; session 2 on a stale list gets marked:false and no directive', async () => {
  await withFixture(
    { config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })], behavior: { stale: true } },
    async ({ sbx, trigger, fake }) => {
      const one = sid('s7a');
      const two = sid('s7b');
      markDirector(sbx, one);
      markDirector(sbx, two);
      const first = await trigger(closePayload(one, rid(1)));
      assert.ok(contextOf(first)?.includes(DISPATCH), first.stdout);
      const second = await trigger(closePayload(two, rid(1)));
      assert.equal(second.status, 0, second.stderr);
      assert.deepEqual(second.json, {}, 'the loser neither dispatches nor nudges: ' + second.stdout);
      assert.deepEqual(fake.markOutcomes.map((o) => o.marked), [true, false]);
    },
  );
});

// ---- S8: a failed mark still dispatches; the non-director control nudges unmarked ------------------

test('S8: a director session whose mark fails (http500) still dispatches', async () => {
  await withFixture(
    { config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })], behavior: { mark: 'http500' } },
    async ({ sbx, trigger, fake }) => {
      const session = sid('s8d');
      markDirector(sbx, session);
      const res = await trigger(closePayload(session, rid(1)));
      assert.equal(res.status, 0, res.stderr);
      const text = contextOf(res);
      assert.ok(text?.includes(DISPATCH), res.stdout);
      assert.ok(text.includes(`--run ${rid(1)}`), text);
      assert.equal(fake.marks.length, 1, 'the mark was attempted');
      assert.equal(fake.isMarked(rid(1)), false);
    },
  );
});

test('S8 control: the same 1-terminal run without the director marker nudges and sends no mark', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ trigger, fake }) => {
    const text = contextOf(await trigger(closePayload(sid('s8c'), rid(1))));
    assert.ok(text?.includes(NUDGE), text);
    assert.ok(!text.includes(DISPATCH), text);
    assert.equal(fake.marks.length, 0, JSON.stringify(fake.requests));
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

// ---- S9: director-watch age window ----------------------------------------------------------------

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function watchOpts(seedRetro = []) {
  const dir = mkdtempSync(join(tmpdir(), 'to-dirmark-'));
  tracked.push(dir);
  mkdirSync(dir, { recursive: true });
  const statePath = join(dir, 'state.json');
  writeFileSync(statePath, JSON.stringify({ pushed: [], retro: seedRetro }));
  return { director: true, statePath, rest: null, retroMode: 'dispatch' };
}
const stateOf = (o) => JSON.parse(readFileSync(o.statePath, 'utf8'));
const HOUR = 60 * 60 * 1000;
/** ISO-8601 for `msAgo` before now, written with a +02:00 offset (same instant). */
function isoPlus2(msAgo) {
  const shifted = new Date(Date.now() - msAgo + 2 * HOUR).toISOString().replace(/\.\d+Z$/, '');
  return `${shifted}+02:00`;
}
const isoZ = (msAgo) => new Date(Date.now() - msAgo).toISOString();
function getInput(session, runId, data) {
  const env = { ok: true, data: { runId, status: 'closed', marks: {}, ...data }, meta: { reqId: 'k7f3q9ab' } };
  return {
    tool_name: 'mcp__mcp-task-orchestrator__runs_read',
    tool_input: { select: 'get', runId },
    tool_response: { content: [{ type: 'text', text: 'ok' }], structuredContent: env, isError: false },
    session_id: session,
  };
}
const dispatchTextOf = (out) => out.hookSpecificOutput.additionalContext;

test('S9: a closed unmarked run closed 25h ago yields {} and leaves state.retro unchanged', async () => {
  const o = watchOpts(['run-seed']);
  const out = await watchRun(getInput(sid('s9a'), 'run-old', { closedAt: isoZ(25 * HOUR) }), {}, o);
  assert.deepEqual(out, {});
  assert.deepEqual(stateOf(o).retro, ['run-seed']);
});

test('S9: a closed unmarked run closed 1h ago dispatches and is recorded', async () => {
  const o = watchOpts();
  const out = await watchRun(getInput(sid('s9b'), 'run-new', { closedAt: isoZ(1 * HOUR) }), {}, o);
  assert.ok(dispatchTextOf(out).includes('--run run-new'), JSON.stringify(out));
  assert.deepEqual(stateOf(o).retro, ['run-new']);
});

test('S9: a closed unmarked run closed 23h ago still dispatches (inside the 24h window)', async () => {
  const o = watchOpts();
  const out = await watchRun(getInput(sid('s9c'), 'run-edge', { closedAt: isoZ(23 * HOUR) }), {}, o);
  assert.ok(dispatchTextOf(out).includes('--run run-edge'), JSON.stringify(out));
});

test('S9: a view with no closedAt dispatches as before', async () => {
  const o = watchOpts();
  const input = getInput(sid('s9d'), 'run-none', {});
  assert.equal('closedAt' in input.tool_response.structuredContent.data, false, 'fixture carries no closedAt');
  const out = await watchRun(input, {}, o);
  assert.ok(dispatchTextOf(out).includes('--run run-none'), JSON.stringify(out));
});

test('S9 probe: closedAt written with a +02:00 offset is parsed as that instant (25h old -> {}, 1h old -> dispatch)', async () => {
  const old = watchOpts(['run-seed']);
  assert.deepEqual(await watchRun(getInput(sid('s9e'), 'run-off-old', { closedAt: isoPlus2(25 * HOUR) }), {}, old), {});
  assert.deepEqual(stateOf(old).retro, ['run-seed']);
  const fresh = watchOpts();
  const out = await watchRun(getInput(sid('s9f'), 'run-off-new', { closedAt: isoPlus2(1 * HOUR) }), {}, fresh);
  assert.ok(dispatchTextOf(out).includes('--run run-off-new'), JSON.stringify(out));
});

test('S9 probe: a numeric closedAt or an unparseable string behaves as absent (dispatches)', async () => {
  const numeric = watchOpts();
  const a = await watchRun(getInput(sid('s9g'), 'run-num', { closedAt: Date.now() - 25 * HOUR }), {}, numeric);
  assert.ok(dispatchTextOf(a).includes('--run run-num'), JSON.stringify(a));
  const garbage = watchOpts();
  const b = await watchRun(getInput(sid('s9h'), 'run-junk', { closedAt: 'not-a-date' }), {}, garbage);
  assert.ok(dispatchTextOf(b).includes('--run run-junk'), JSON.stringify(b));
});

// ---- S10: one repeated --run invocation -----------------------------------------------------------

test('S10: buildDispatch names both runs in ONE /session-retrospective invocation with repeated --run', () => {
  const text = buildDispatch(['run-aaa', 'run-bbb'], 'root-1');
  assert.ok(text.includes('/session-retrospective --run run-aaa --run run-bbb'), text);
  assert.equal((text.match(/\/session-retrospective/g) ?? []).length, 1, text);
  assert.ok(text.includes('ONE retrospective'), text);
});

test('S10: buildNudge is unchanged: one command per run', () => {
  const text = buildNudge(['run-aaa', 'run-bbb']);
  assert.ok(text.includes('/session-retrospective --run run-aaa'), text);
  assert.ok(text.includes('/session-retrospective --run run-bbb'), text);
});

test('S10 probe: a single run and an empty list keep the one-invocation shape', () => {
  const one = buildDispatch(['run-aaa'], 'root-1');
  assert.ok(one.includes('/session-retrospective --run run-aaa'), one);
  assert.equal((one.match(/--run/g) ?? []).length, 1, one);
  const none = buildDispatch([], 'root-1');
  assert.ok(none.includes('/session-retrospective'), none);
  assert.ok(!none.includes('--run'), none);
});
