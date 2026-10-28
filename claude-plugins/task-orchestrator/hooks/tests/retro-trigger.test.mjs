// retro-trigger.mjs against a fake REST server (c7e43c6d). Scenario ids S1..S14 are those of the
// item's test-plan; oracle = task-scope AC n and current/docs/api-reference.md (runs_read list /
// frontier, runs_write mark: first write wins, marked false on replay). The hooks are spawned as
// node child processes with an isolated TEMP, home and config dir (see retro-fake-runs.mjs).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import {
  HOOKS,
  HOOKS_CONFIG,
  ROOT,
  RUNS_WRITE,
  rid,
  retroConfig,
  withFixture,
  runFixture,
  closePayload,
  writePayload,
  closeResultOk,
  openResultOk,
  failedResult,
  contextOf,
  listTree,
  okEnvelope,
  wrapResponse,
  runHook,
  makeSandbox,
  markDirector,
} from './retro-fake-runs.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });

function assertSilent(res, fake) {
  assert.equal(res.status, 0);
  assert.deepEqual(res.json, {}, res.stdout);
  if (fake) assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
}

// ---- S1 happy path: dispatch -------------------------------------------------------------------

test('S1: dispatch mode, threshold 3, a successful close with 3 terminal members dispatches and marks the run', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s1'), rid(1)));
    assert.equal(res.status, 0);
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(`/session-retrospective --run ${rid(1)}`), text);
    assert.ok(text.includes(ROOT), 'the dispatch names the project root as ancestor: ' + text);
    assert.equal(fake.lists.length, 1);
    assert.equal(fake.frontiers.length, 1);
    assert.deepEqual(fake.frontiers[0].body, { select: 'frontier', runId: rid(1) });
    assert.equal(fake.marks.length, 1);
    assert.equal(fake.marks[0].body.ops.length, 1);
    assert.deepEqual(fake.marks[0].body.ops[0], { op: 'mark', runId: rid(1), key: 'retro' });
    assert.equal(fake.isMarked(rid(1)), true);
    // order: list -> frontier -> mark
    const order = fake.requests.map((q) => q.body?.select ?? q.body?.ops?.[0]?.op);
    assert.deepEqual(order, ['list', 'frontier', 'mark']);
  });
});

test('S5: the list request asks for closed runs unmarked retro on the project root, window 24 h, limit 20', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const before = Date.now();
    await trigger(closePayload(sid('s5body'), rid(1)));
    const after = Date.now();
    assert.equal(fake.lists.length, 1);
    const body = fake.lists[0].body;
    assert.equal(body.select, 'list');
    assert.equal(body.rootId, ROOT);
    assert.equal(body.unmarked, 'retro');
    assert.equal(body.limit, 20);
    assert.equal(typeof body.endedSince, 'string');
    assert.match(body.endedSince, /Z$/, 'ISO 8601 UTC');
    const since = Date.parse(body.endedSince);
    assert.ok(Number.isFinite(since), body.endedSince);
    const day = 24 * 60 * 60 * 1000;
    assert.ok(since >= before - day - 60000 && since <= after - day + 60000, `endedSince ${body.endedSince} is not now minus 24 h`);
  });
});

// ---- S2 threshold counts the run's terminal members, not its member total -----------------------

const THRESHOLD_CASES = [
  { name: 'thr 3, 2 terminal of 10 members -> nudge (members total must not count)', thr: 3, terminal: 2, members: 10, expect: 'nudge' },
  { name: 'thr 3, 3 terminal of 3 -> dispatch', thr: 3, terminal: 3, members: 3, expect: 'dispatch' },
  { name: 'thr 3, 1 terminal of 1 -> nudge (AC10)', thr: 3, terminal: 1, members: 1, expect: 'nudge' },
  { name: 'thr 0, 0 terminal -> dispatch (>= 0)', thr: 0, terminal: 0, members: 0, expect: 'dispatch' },
  { name: 'thr 2, 2 terminal -> dispatch (boundary)', thr: 2, terminal: 2, members: 2, expect: 'dispatch' },
  { name: 'thr 4, 3 terminal of 9 -> nudge (one below the boundary)', thr: 4, terminal: 3, members: 9, expect: 'nudge' },
  { name: 'thr 4, 4 terminal -> dispatch (boundary)', thr: 4, terminal: 4, members: 4, expect: 'dispatch' },
];

for (const c of THRESHOLD_CASES) {
  test(`S2: ${c.name}`, async () => {
    await withFixture(
      { config: cfg('dispatch', c.thr), runs: [runFixture(1, { terminal: c.terminal, members: c.members })] },
      async ({ trigger, fake }) => {
        const res = await trigger(closePayload(sid('s2'), rid(1)));
        assert.equal(res.status, 0);
        const text = contextOf(res);
        assert.ok(text, res.stdout);
        assert.ok(text.includes(`/session-retrospective --run ${rid(1)}`), text);
        assert.equal(fake.frontiers.length, 1);
        if (c.expect === 'dispatch') {
          assert.ok(text.includes(DISPATCH), text);
          assert.equal(fake.marks.length, 1);
          assert.equal(fake.isMarked(rid(1)), true);
        } else {
          assert.ok(text.includes(NUDGE), text);
          assert.ok(!text.includes(DISPATCH), text);
          assert.equal(fake.marks.length, 0, 'a nudge never marks');
          assert.equal(fake.isMarked(rid(1)), false);
        }
      },
    );
  });
}

test('S2: mode nudge never marks, however many terminal members the run has', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 10 })] }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s2nudge'), rid(1)));
    const text = contextOf(res);
    assert.ok(text?.includes(NUDGE), res.stdout);
    assert.ok(!text.includes(DISPATCH), text);
    assert.equal(fake.marks.length, 0);
  });
});

test('S2/S12: no dispatchThreshold configured -> default 3: 3 terminal dispatches, 2 nudges', async () => {
  const config = retroConfig({ mode: 'dispatch' });
  await withFixture({ config, runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    assert.ok(contextOf(await trigger(closePayload(sid('s2d'), rid(1))))?.includes(DISPATCH));
  });
  await withFixture({ config, runs: [runFixture(1, { terminal: 2 })] }, async ({ trigger }) => {
    const text = contextOf(await trigger(closePayload(sid('s2d'), rid(1))));
    assert.ok(text?.includes(NUDGE), text);
  });
});

// ---- S3 cross-session dedupe: the server mark, first write wins ---------------------------------

test('S3: two sessions racing on one closed run produce exactly one dispatch', async () => {
  await withFixture(
    { config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { stale: true } },
    async ({ trigger, fake }) => {
      const [a, b] = await Promise.all([trigger(closePayload(sid('s3a'), rid(1))), trigger(closePayload(sid('s3b'), rid(1)))]);
      const dispatched = [a, b].filter((r) => contextOf(r)?.includes(DISPATCH));
      const silent = [a, b].filter((r) => r.stdout.trim() === '{}');
      assert.equal(dispatched.length, 1, `${a.stdout} / ${b.stdout}`);
      assert.equal(silent.length, 1, `${a.stdout} / ${b.stdout}`);
      assert.equal(fake.marks.length, 2, 'both sessions tried to mark; the server decided');
      assert.deepEqual(fake.markOutcomes.map((o) => o.marked).sort(), [false, true]);
    },
  );
});

test('S3: a stale list entry already marked by another session -> marked:false, no directive at all (replay)', async () => {
  await withFixture(
    { config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { stale: true } },
    async ({ trigger, fake }) => {
      const first = await trigger(closePayload(sid('s3first'), rid(1)));
      assert.ok(contextOf(first)?.includes(DISPATCH), first.stdout);
      const second = await trigger(closePayload(sid('s3second'), rid(1)));
      assert.equal(second.status, 0);
      assert.deepEqual(second.json, {}, 'the loser neither dispatches nor nudges');
      assert.deepEqual(fake.markOutcomes.map((o) => o.marked), [true, false]);
    },
  );
});

test('S3: the same session replaying after a win does not dispatch twice', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const session = sid('s3replay');
    assert.ok(contextOf(await trigger(closePayload(session, rid(1))))?.includes(DISPATCH));
    const again = await trigger(closePayload(session, rid(1)));
    assert.deepEqual(again.json, {});
    assert.equal(fake.markOutcomes.filter((o) => o.marked).length, 1);
  });
});

// ---- S4 a failed mark never dispatches ----------------------------------------------------------

for (const failure of ['http500', 'forbidden', 'itemfail', 'badjson', 'hang']) {
  test(`S4: mark answer '${failure}' -> nudge naming the run, never a dispatch`, async () => {
    await withFixture(
      { config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { mark: failure } },
      async ({ trigger, fake }) => {
        const session = sid('s4');
        const res = await trigger(closePayload(session, rid(1)));
        assert.equal(res.status, 0);
        const text = contextOf(res);
        assert.ok(text?.includes(NUDGE), res.stdout);
        assert.ok(!text.includes(DISPATCH), text);
        assert.ok(text.includes(`/session-retrospective --run ${rid(1)}`), text);
        assert.equal(fake.marks.length, 1, 'one mark attempt');
        assert.equal(fake.isMarked(rid(1)), false);
        // the run is now in this session's nudged set: the same list entry is not nudged again
        const again = await trigger(closePayload(session, rid(1)));
        assert.deepEqual(again.json, {}, again.stdout);
      },
    );
  });
}

// ---- S5 the unmarked filter, and a defensive marks.retro skip -----------------------------------

test('S5: a listed run whose view already carries marks.retro is skipped: no frontier read, no mark, {}', async () => {
  const runs = [runFixture(1, { terminal: 3, marks: { retro: '2026-10-16T11:30:00Z' } })];
  await withFixture({ config: cfg(), runs, behavior: { unfiltered: true } }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s5marked'), rid(1)));
    assert.deepEqual(res.json, {});
    assert.equal(fake.lists.length, 1);
    assert.equal(fake.frontiers.length, 0);
    assert.equal(fake.marks.length, 0);
  });
});

test('S5 probe: marks {} / absent / null / other key are all unmarked; only marks.retro skips', async () => {
  const runs = [
    runFixture(1, { terminal: 3, marks: {} }),
    runFixture(2, { terminal: 3, marks: undefined }),
    runFixture(3, { terminal: 3, marks: { other: '2026-10-16T11:30:00Z' } }),
    runFixture(4, { terminal: 3, marks: { retro: '2026-10-16T11:30:00Z' } }),
    runFixture(5, { terminal: 3, marks: null }),
  ];
  await withFixture({ config: cfg(), runs, behavior: { unfiltered: true } }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s5probe'), rid(1)));
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.deepEqual(fake.frontiers.map((q) => q.body.runId).sort(), [rid(1), rid(2), rid(3), rid(5)]);
    assert.deepEqual(fake.marks.map((q) => q.body.ops[0].runId).sort(), [rid(1), rid(2), rid(3), rid(5)]);
    for (const n of [1, 2, 3, 5]) assert.ok(text.includes(`--run ${rid(n)}`), text);
    assert.ok(!text.includes(rid(4)), 'the already-marked run is not named: ' + text);
  });
});

// ---- the call cap, a failed frontier, one directive per invocation ------------------------------

test('S5 probe: 7 unmarked runs -> at most 5 frontier reads and 5 marks, first five (list order) named', async () => {
  const runs = [1, 2, 3, 4, 5, 6, 7].map((n) => runFixture(n, { terminal: 3 }));
  await withFixture({ config: cfg(), runs }, async ({ trigger, fake }) => {
    const text = contextOf(await trigger(closePayload(sid('cap'), rid(1))));
    assert.ok(text?.includes(DISPATCH));
    assert.equal(fake.frontiers.length, 5);
    assert.equal(fake.marks.length, 5);
    for (const n of [1, 2, 3, 4, 5]) assert.ok(text.includes(`--run ${rid(n)}`), `run ${n}: ${text}`);
    for (const n of [6, 7]) assert.ok(!text.includes(rid(n)), `run ${n} past the cap must not be named: ${text}`);
    assert.equal(fake.isMarked(rid(6)), false);
    assert.equal(fake.isMarked(rid(7)), false);
  });
});

test('AC3c: a failed frontier read skips that run this time: not marked, not named; the others proceed', async () => {
  const runs = [runFixture(1, { terminal: 3 }), runFixture(2, { terminal: 3 })];
  await withFixture({ config: cfg(), runs, behavior: { frontier: { [rid(1)]: 'http500' } } }, async ({ trigger, fake }) => {
    const text = contextOf(await trigger(closePayload(sid('frontfail'), rid(2))));
    assert.ok(text?.includes(DISPATCH), text);
    assert.ok(text.includes(`--run ${rid(2)}`));
    assert.ok(!text.includes(rid(1)), text);
    assert.deepEqual(fake.marks.map((q) => q.body.ops[0].runId), [rid(2)]);
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

test('AC3f: one directive per invocation: a won run dispatches, a below-threshold run is not named alongside it', async () => {
  const runs = [runFixture(1, { terminal: 3 }), runFixture(2, { terminal: 1 })];
  await withFixture({ config: cfg(), runs }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('mixed'), rid(1)));
    const text = contextOf(res);
    assert.ok(text?.includes(DISPATCH), res.stdout);
    assert.ok(text.includes(`--run ${rid(1)}`));
    assert.ok(!text.includes(rid(2)), text);
    assert.deepEqual(fake.marks.map((q) => q.body.ops[0].runId), [rid(1)]);
  });
});

test('AC3f: two below-threshold runs -> one nudge naming both, no mark', async () => {
  const runs = [runFixture(1, { terminal: 1 }), runFixture(2, { terminal: 2 })];
  await withFixture({ config: cfg(), runs }, async ({ trigger, fake }) => {
    const text = contextOf(await trigger(closePayload(sid('twonudge'), rid(1))));
    assert.ok(text?.includes(NUDGE), text);
    assert.ok(text.includes(`--run ${rid(1)}`) && text.includes(`--run ${rid(2)}`), text);
    assert.equal(fake.marks.length, 0);
  });
});

test('AC3: nothing eligible (empty list) -> {} after one list read', async () => {
  await withFixture({ config: cfg(), runs: [] }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('empty'), rid(1)));
    assert.deepEqual(res.json, {});
    assert.equal(fake.lists.length, 1);
    assert.equal(fake.frontiers.length, 0);
  });
});

// ---- S6 only a successful close acts ------------------------------------------------------------

const INERT = [
  {
    name: 'advance with terminal results',
    payload: (s) => ({
      session_id: s,
      tool_name: 'mcp__mcp-task-orchestrator__advance',
      tool_input: { ops: [{ op: 'transition', itemId: 'aaaa1111', trigger: 'complete' }] },
      tool_response: wrapResponse(okEnvelope({ results: [{ ok: true, itemId: 'aaaa1111', newRole: 'terminal' }], summary: { ok: 1, failed: 0 } })),
    }),
  },
  {
    name: 'items_write create',
    payload: (s) => ({
      session_id: s,
      tool_name: 'mcp__mcp-task-orchestrator__items_write',
      tool_input: { ops: [{ op: 'create', title: 'x' }] },
      tool_response: wrapResponse(okEnvelope({ results: [{ ok: true, itemId: 'bbbb2222' }], summary: { ok: 1, failed: 0 } })),
    }),
  },
  {
    name: 'runs_write open (ok)',
    payload: (s) => writePayload({ sessionId: s, ops: [{ op: 'open', rootId: ROOT }], results: [openResultOk(rid(1))] }),
  },
  {
    name: 'runs_write update / pause / resume / assign (ok)',
    payload: (s) =>
      writePayload({
        sessionId: s,
        ops: [{ op: 'update', runId: rid(1) }, { op: 'pause', runId: rid(1) }, { op: 'resume', runId: rid(1) }, { op: 'assign', runId: rid(1) }],
        results: [openResultOk(rid(1)), openResultOk(rid(1)), openResultOk(rid(1)), openResultOk(rid(1))],
      }),
  },
  {
    name: 'runs_write mark (ok)',
    payload: (s) => writePayload({ sessionId: s, ops: [{ op: 'mark', runId: rid(1), key: 'retro' }], results: [{ ...closeResultOk(rid(1)), marked: true }] }),
  },
  {
    name: 'runs_write close that failed',
    payload: (s) => writePayload({ sessionId: s, ops: [{ op: 'close', runId: rid(1), reason: 'completed' }], results: [failedResult('invalid_run_state')] }),
  },
  {
    name: 'ops [close (failed), open (ok)]: the ok result is at the open index',
    payload: (s) =>
      writePayload({
        sessionId: s,
        ops: [{ op: 'close', runId: rid(1), reason: 'completed' }, { op: 'open', rootId: ROOT }],
        results: [failedResult('run_closed'), openResultOk(rid(2))],
      }),
  },
  {
    name: 'a runs_write with no ops in tool_input',
    payload: (s) => ({ session_id: s, tool_name: RUNS_WRITE, tool_input: {}, tool_response: wrapResponse(okEnvelope({ results: [], summary: { ok: 0, failed: 0 } })) }),
  },
  {
    name: 'a plain-text tool_response',
    payload: (s) => ({ session_id: s, tool_name: RUNS_WRITE, tool_input: { ops: [{ op: 'close', runId: rid(1) }] }, tool_response: { content: [{ type: 'text', text: 'ok' }] } }),
  },
  {
    name: 'a legacy success-shaped tool_response',
    payload: (s) => ({
      session_id: s,
      tool_name: RUNS_WRITE,
      tool_input: { ops: [{ op: 'close', runId: rid(1) }] },
      tool_response: { success: true, message: 'closed', data: { results: [{ ok: true }] } },
    }),
  },
];

for (const c of INERT) {
  test(`S6: ${c.name} -> {} and zero REST requests`, async () => {
    await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
      assertSilent(await trigger(c.payload(sid('s6'))), fake);
    });
  });
}

test('S6 probe: ops [open (failed), close (ok)] -> the close at index 1 acts', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const res = await trigger(
      writePayload({
        sessionId: sid('s6align'),
        ops: [{ op: 'open', rootId: ROOT }, { op: 'close', runId: rid(1), reason: 'completed' }],
        results: [failedResult('duplicate'), closeResultOk(rid(1))],
      }),
    );
    assert.ok(contextOf(res)?.includes(DISPATCH), res.stdout);
    assert.equal(fake.lists.length, 1);
  });
});

test('S6 probe: the close is recognised under another server-name prefix and under every response encoding', async () => {
  const variants = [
    { toolName: 'mcp__plugin_task-orchestrator_mcp-task-orchestrator__runs_write', via: 'structured' },
    { toolName: RUNS_WRITE, via: 'text' },
    { toolName: RUNS_WRITE, via: 'bare' },
  ];
  for (const v of variants) {
    await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
      const res = await trigger(closePayload(sid('s6enc'), rid(1), v));
      assert.ok(contextOf(res)?.includes(DISPATCH), `${v.toolName} via ${v.via}: ${res.stdout}`);
      assert.equal(fake.marks.length, 1);
    });
  }
});

test('S6 probe: two successful closes in one call -> one directive, the run marked once', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 }), runFixture(2, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const res = await trigger(
      writePayload({
        sessionId: sid('s6two'),
        ops: [{ op: 'close', runId: rid(1), reason: 'completed' }, { op: 'close', runId: rid(2), reason: 'completed' }],
        results: [closeResultOk(rid(1)), closeResultOk(rid(2))],
      }),
    );
    assert.ok(contextOf(res)?.includes(DISPATCH), res.stdout);
    assert.equal(fake.marks.filter((q) => q.body.ops[0].runId === rid(1)).length, 1);
    assert.equal(fake.marks.filter((q) => q.body.ops[0].runId === rid(2)).length, 1);
  });
});

// ---- S7/S11 no REST path: the trigger records, never calls ---------------------------------------

test('S7: no API URL -> the close yields {} (the record is for the backstop)', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s7'), rid(1)), { apiUrl: undefined });
    assertSilent(res, fake);
  });
});

test('S11: API URL set but no project rootId -> zero requests and {}', async () => {
  await withFixture({ config: retroConfig({ rootId: null, mode: 'dispatch', threshold: 3 }), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    assertSilent(await trigger(closePayload(sid('s11root'), rid(1))), fake);
  });
});

test('S11: API URL set but no config.yaml at all -> zero requests and {}', async () => {
  await withFixture({ config: null, runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    assertSilent(await trigger(closePayload(sid('s11none'), rid(1))), fake);
  });
});

test('S11: a user-scope config (personal root in the home config) never starts the REST path', async () => {
  await withFixture({ config: cfg(), scope: 'user', runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    assertSilent(await trigger(closePayload(sid('s11user'), rid(1))), fake);
  });
});

// ---- S8 gating -----------------------------------------------------------------------------------

test('S8: to_mod_retro true -> {}, zero requests, no state written', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake, sbx }) => {
    const res = await trigger(closePayload(sid('s8mod'), rid(1), { extra: { to_mod_retro: true } }));
    assertSilent(res, fake);
    assert.deepEqual(listTree(sbx.tmp), []);
  });
});

test('S8: to_mod_retro false or absent does not step aside', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger }) => {
    const res = await trigger(closePayload(sid('s8modfalse'), rid(1), { extra: { to_mod_retro: false } }));
    assert.ok(contextOf(res)?.includes(DISPATCH), res.stdout);
  });
});

test('S8: headless iteration -> {}, zero requests, no state written', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake, sbx }) => {
    const res = await trigger(closePayload(sid('s8head'), rid(1)), { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } });
    assertSilent(res, fake);
    assert.deepEqual(listTree(sbx.tmp), []);
  });
});

test('S8: mode off -> {}, zero requests, no state written', async () => {
  await withFixture({ config: cfg('off', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake, sbx }) => {
    assertSilent(await trigger(closePayload(sid('s8off'), rid(1))), fake);
    assert.deepEqual(listTree(sbx.tmp), []);
  });
});

test('S8: a subagent (agent_id) close makes no REST call and emits no directive', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    const res = await trigger(closePayload(sid('s8sub'), rid(1), { extra: { agent_id: 'sub-1' } }));
    assertSilent(res, fake);
  });
});

// ---- fail-open -----------------------------------------------------------------------------------

for (const failure of ['http500', 'badjson', 'hang']) {
  test(`probe: runs_read list answers '${failure}' -> {} and exit 0, no mark`, async () => {
    await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { list: failure } }, async ({ trigger, fake }) => {
      const res = await trigger(closePayload(sid('failopen'), rid(1)));
      assert.equal(res.status, 0);
      assert.deepEqual(res.json, {}, res.stdout);
      assert.equal(fake.lists.length, 1);
      assert.equal(fake.marks.length, 0);
    });
  });
}

for (const [name, input] of [
  ['malformed stdin', '{not valid json'],
  ['empty stdin', null],
]) {
  test(`probe: ${name} -> {} and exit 0`, async () => {
    const sbx = makeSandbox({ config: cfg() });
    try {
      const res = await runHook(HOOKS.trigger, input, sbx);
      assert.equal(res.status, 0);
      assert.deepEqual(res.json, {});
    } finally {
      sbx.cleanup();
    }
  });
}

// ---- S14 no cross-session tmp marker -------------------------------------------------------------

test('S14: after a dispatch flow and a nudge flow no retro-* entry exists under <tmp>/task-orchestrator', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 }), runFixture(2, { terminal: 1 })] }, async ({ trigger, sbx }) => {
    await trigger(closePayload(sid('s14a'), rid(1)));
    await trigger(closePayload(sid('s14b'), rid(2)));
    const tree = listTree(sbx.tmp);
    const top = tree.filter((p) => p.split('/').length === 2 && p.startsWith('task-orchestrator/'));
    for (const p of top) {
      const name = p.split('/')[1];
      assert.ok(!name.startsWith('retro-'), `cross-session marker left behind: ${p}`);
      assert.ok(!name.endsWith('.json'), `a top-level json file is a shared marker: ${p}`);
    }
  });
});

// ---- registration: only runs_write reaches the trigger -------------------------------------------

test('AC2: hooks-config.json registers retro-trigger on the runs_write matcher only', () => {
  const cfgJson = JSON.parse(readFileSync(HOOKS_CONFIG, 'utf-8'));
  const found = [];
  const walk = (node) => {
    if (Array.isArray(node)) return node.forEach(walk);
    if (!node || typeof node !== 'object') return;
    if (Array.isArray(node.hooks) && node.hooks.some((h) => typeof h?.command === 'string' && h.command.includes('retro-trigger.mjs'))) {
      found.push(node.matcher);
    }
    Object.values(node).forEach(walk);
  };
  walk(cfgJson);
  assert.deepEqual(found, ['^mcp__.*task-orchestrator.*__runs_write$']);
});

// ---- director session: always dispatch mode -----------------------------------------------------

test('director: mode nudge in a director session dispatches and marks a run at the threshold', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ sbx, trigger, fake }) => {
    const session = sid('dir-nudge');
    markDirector(sbx, session);
    const text = contextOf(await trigger(closePayload(session, rid(1))));
    assert.ok(text?.includes(DISPATCH), text);
    assert.ok(!text.includes(NUDGE), text);
    assert.equal(fake.marks.length, 1);
  });
});

test('director: a below-threshold run is dispatched, never nudged; the same input without the marker nudges', async () => {
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ sbx, trigger }) => {
    const session = sid('dir-low');
    markDirector(sbx, session);
    const text = contextOf(await trigger(closePayload(session, rid(1))));
    assert.ok(text?.includes(DISPATCH) && text.includes(`--run ${rid(1)}`), text);
    assert.ok(!text.includes(NUDGE), text);
  });
  await withFixture({ config: cfg('dispatch', 3), runs: [runFixture(1, { terminal: 1 })] }, async ({ trigger }) => {
    const text = contextOf(await trigger(closePayload(sid('dir-ctl'), rid(1))));
    assert.ok(text?.includes(NUDGE), text);
  });
});

test('director: mode off stays off in a director session', async () => {
  await withFixture({ config: cfg('off'), runs: [runFixture(1, { terminal: 3 })] }, async ({ sbx, trigger, fake }) => {
    const session = sid('dir-off');
    markDirector(sbx, session);
    assertSilent(await trigger(closePayload(session, rid(1))), fake);
  });
});
