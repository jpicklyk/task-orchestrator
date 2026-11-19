// Retrospective session scope (observation f30d8252). Oracle: a closed run owes a retrospective only to
// the session that opened or closed it through runs_write (its main thread or a subagent, both carrying
// its session_id). Any other session, a director session included, stays silent and leaves the run
// unmarked for the session that drove it. A coordinator running in a session of its own handles its own
// run's retrospective.

import './hermetic-claude-config.mjs';
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  rid,
  ROOT,
  retroConfig,
  withFixture,
  runFixture,
  closePayload,
  writePayload,
  openResultOk,
  failedResult,
  contextOf,
  markDirector,
  ownRuns,
} from './retro-fake-runs.mjs';
import { ownsRun } from '../retro-lib.mjs';
import { run as watchRun } from '../director-watch.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });
const stopPayload = (session) => ({ session_id: session });
const openPayload = (session, runId, extra = {}) =>
  writePayload({ sessionId: session, ops: [{ op: 'open', rootId: ROOT, items: ['x'] }], results: [openResultOk(runId)], extra });

function assertBlock(res, includes, excludes = []) {
  assert.equal(res.status, 0, res.stderr);
  assert.equal(res.json?.decision, 'block', res.stdout);
  for (const s of includes) assert.ok(res.json.reason.includes(s), `missing ${s}: ${res.json.reason}`);
  for (const s of excludes) assert.ok(!res.json.reason.includes(s), `unexpected ${s}: ${res.json.reason}`);
}

test('ownsRun: true for an opened or closed run, false otherwise and on a malformed state', () => {
  assert.equal(ownsRun({ opened: ['a'], closes: [] }, 'a'), true);
  assert.equal(ownsRun({ opened: [], closes: ['b'] }, 'b'), true);
  assert.equal(ownsRun({ opened: ['a'], closes: ['b'] }, 'c'), false);
  assert.equal(ownsRun({}, 'a'), false);
  assert.equal(ownsRun(null, 'a'), false);
  assert.equal(ownsRun({ opened: ['a'] }, undefined), false);
});

test('a Stop in a session that never touched the run is silent and leaves it unmarked; the owner then dispatches', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ sbx, stop, fake }) => {
    const bystander = sid('bystander');
    assert.deepEqual((await stop(stopPayload(bystander))).json, {});
    assert.equal(fake.frontiers.length, 0, 'no frontier read for a run the session does not own');
    assert.equal(fake.marks.length, 0);
    assert.equal(fake.isMarked(rid(1)), false);

    const owner = sid('owner');
    ownRuns(sbx, owner, [rid(1)]);
    assertBlock(await stop(stopPayload(owner)), [DISPATCH, `--run ${rid(1)}`]);
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

test('nudge mode: a bystander session is never nudged for another session\'s run', async () => {
  await withFixture({ config: cfg('nudge'), runs: [runFixture(1, { terminal: 1 }), runFixture(2, { terminal: 1 })] }, async ({ sbx, stop }) => {
    const owner = sid('nudge-owner');
    ownRuns(sbx, owner, [rid(2)]);
    assertBlock(await stop(stopPayload(owner)), [NUDGE, `--run ${rid(2)}`], [rid(1)]);
    assert.deepEqual((await stop(stopPayload(sid('nudge-other')))).json, {});
  });
});

test('a recorded open makes the run this session\'s: its Stop dispatches after the run closes elsewhere', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop }) => {
    const session = sid('opener');
    assert.deepEqual((await trigger(openPayload(session, rid(1)))).json, {}, 'an open alone emits nothing');
    assertBlock(await stop(stopPayload(session)), [DISPATCH, `--run ${rid(1)}`]);
  });
});

test('an open made by a subagent is recorded under the parent session', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop }) => {
    const session = sid('parent');
    await trigger(openPayload(session, rid(1), { agent_id: 'coordinator-1' }));
    assertBlock(await stop(stopPayload(session)), [DISPATCH, `--run ${rid(1)}`]);
  });
});

test('a failed open is not recorded', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop, fake }) => {
    const session = sid('failopen');
    const payload = writePayload({ sessionId: session, ops: [{ op: 'open', rootId: ROOT, items: ['x'] }], results: [failedResult('duplicate')] });
    await trigger(payload);
    assert.deepEqual((await stop(stopPayload(session))).json, {});
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

test('a close names only this session\'s runs: another session\'s unmarked run is neither named nor marked', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 }), runFixture(2, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const text = contextOf(await trigger(closePayload(sid('closer'), rid(1))));
    assert.ok(text?.includes(DISPATCH), text);
    assert.ok(text.includes(`--run ${rid(1)}`), text);
    assert.ok(!text.includes(rid(2)), text);
    assert.equal(fake.isMarked(rid(2)), false);
  });
});

test('director: a child session\'s run (tier 1, its own session) is not the director\'s: silent, unmarked', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, stop, fake }) => {
    const director = sid('director');
    markDirector(sbx, director);
    assert.deepEqual((await stop(stopPayload(director))).json, {});
    assert.equal(fake.marks.length, 0);
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

test('director: a run its in-session coordinator opened (subagent call) is dispatched at any terminal count', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, trigger, stop, fake }) => {
    const director = sid('director-own');
    markDirector(sbx, director);
    await trigger(openPayload(director, rid(1), { agent_id: 'coordinator-2' }));
    assertBlock(await stop(stopPayload(director)), [DISPATCH, `--run ${rid(1)}`], [NUDGE]);
    assert.equal(fake.isMarked(rid(1)), true);
  });
});

// ---- director-watch (no-REST director path) --------------------------------------------------------

const tracked = [];
after(() => {
  for (const d of tracked) rmSync(d, { recursive: true, force: true });
});
function watchOpts(retroState) {
  const dir = mkdtempSync(join(tmpdir(), 'to-scope-'));
  tracked.push(dir);
  const statePath = join(dir, 'state.json');
  writeFileSync(statePath, JSON.stringify({ pushed: [], retro: [] }));
  return { director: true, statePath, rest: null, retroMode: 'dispatch', retroState };
}
function getInput(session, runId) {
  const env = { ok: true, data: { runId, status: 'closed', marks: {} }, meta: { reqId: 'k7f3q9ab' } };
  return {
    tool_name: 'mcp__mcp-task-orchestrator__runs_read',
    tool_input: { select: 'get', runId },
    tool_response: { content: [{ type: 'text', text: 'ok' }], structuredContent: env, isError: false },
    session_id: session,
  };
}

test('director-watch: reading a closed run this session never touched yields {} and records nothing', async () => {
  const o = watchOpts({ opened: [], closes: [] });
  assert.deepEqual(await watchRun(getInput(sid('w1'), 'run-child'), {}, o), {});
  assert.deepEqual(JSON.parse(readFileSync(o.statePath, 'utf8')).retro, []);
});

test('director-watch: reading a closed run this session opened dispatches it', async () => {
  const o = watchOpts({ opened: ['run-mine'], closes: [] });
  const out = await watchRun(getInput(sid('w2'), 'run-mine'), {}, o);
  assert.ok(out.hookSpecificOutput.additionalContext.includes('--run run-mine'), JSON.stringify(out));
});
