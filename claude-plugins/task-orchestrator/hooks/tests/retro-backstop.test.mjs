// retro-backstop.mjs (the Stop hook) against a fake REST server (c7e43c6d). Scenario ids are those
// of the item's test-plan; oracle = task-scope AC4/AC5/AC3 and current/docs/api-reference.md.
// Chains with retro-trigger.mjs through the per-session state both hooks share; every test has its
// own TEMP so no state outlives it.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  HOOKS,
  ROOT,
  rid,
  retroConfig,
  withFixture,
  runFixture,
  closePayload,
  listTree,
  runHook,
  makeSandbox,
} from './retro-fake-runs.mjs';

const sid = (p) => `${p}-${randomUUID()}`;
const DISPATCH = 'Retrospective dispatch';
const NUDGE = 'Retrospective suggested';
const cfg = (mode = 'dispatch', threshold = 3) => retroConfig({ mode, threshold });
const stopPayload = (session, extra = {}) => ({ session_id: session, ...extra });

function assertBlock(res, includes = [], excludes = []) {
  assert.equal(res.status, 0);
  assert.equal(res.json?.decision, 'block', res.stdout);
  assert.equal(typeof res.json.reason, 'string');
  for (const s of includes) assert.ok(res.json.reason.includes(s), `missing ${JSON.stringify(s)} in: ${res.json.reason}`);
  for (const s of excludes) assert.ok(!res.json.reason.includes(s), `unexpected ${JSON.stringify(s)} in: ${res.json.reason}`);
}

function assertSilent(res, fake) {
  assert.equal(res.status, 0);
  assert.deepEqual(res.json, {}, res.stdout);
  if (fake) assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
}

// ---- loop guard ------------------------------------------------------------------------------

test('AC4: stop_hook_active short-circuits to {} before any REST call', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake }) => {
    assertSilent(await stop(stopPayload(sid('loop'), { stop_hook_active: true })), fake);
  });
});

test('AC4: stop_hook_active wins over a recorded close too (no-REST path)', async () => {
  await withFixture({ config: cfg(), runs: [] }, async ({ trigger, stop }) => {
    const session = sid('loop2');
    await trigger(closePayload(session, rid(1)), { apiUrl: undefined });
    const res = await stop(stopPayload(session, { stop_hook_active: true }), { apiUrl: undefined });
    assert.deepEqual(res.json, {});
    // the guard did not consume the record: a genuine Stop still nudges
    assertBlock(await stop(stopPayload(session), { apiUrl: undefined }), [`--run ${rid(1)}`]);
  });
});

// ---- S9 REST path ----------------------------------------------------------------------------

test('S9: REST path, run with 3 terminal members: block with the dispatch, one mark; the next Stop is {}', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake }) => {
    const first = await stop(stopPayload(sid('s9a')));
    assertBlock(first, [DISPATCH, `--run ${rid(1)}`, ROOT]);
    assert.equal(fake.marks.length, 1);
    assert.deepEqual(fake.marks[0].body.ops[0], { op: 'mark', runId: rid(1), key: 'retro' });
    assert.equal(fake.isMarked(rid(1)), true);
    // another session, same root: the server mark removes the run from the unmarked list
    const second = await stop(stopPayload(sid('s9b')));
    assert.deepEqual(second.json, {});
    assert.equal(fake.marks.length, 1, 'no second mark');
    // the same session again
    const third = await stop(stopPayload(sid('s9c')));
    assert.deepEqual(third.json, {});
  });
});

test('S9: the list request is scoped to the project root and unmarked retro', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake }) => {
    await stop(stopPayload(sid('s9body')));
    assert.equal(fake.lists.length, 1);
    assert.equal(fake.lists[0].body.rootId, ROOT);
    assert.equal(fake.lists[0].body.unmarked, 'retro');
    assert.equal(fake.lists[0].body.limit, 20);
  });
});

test('S9: mode nudge on the REST path blocks with a nudge and never marks', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [runFixture(1, { terminal: 5 })] }, async ({ stop, fake }) => {
    assertBlock(await stop(stopPayload(sid('s9nudge'))), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
    assert.equal(fake.marks.length, 0);
  });
});

test('S9: nothing unmarked -> {} after one list read', async () => {
  await withFixture({ config: cfg(), runs: [] }, async ({ stop, fake }) => {
    const res = await stop(stopPayload(sid('s9none')));
    assert.deepEqual(res.json, {});
    assert.equal(fake.lists.length, 1);
  });
});

// ---- S10 below threshold: nudged once per session, never marked -----------------------------------

test('S10: terminal 1 of threshold 3: nudge, then {} in the same session, a nudge again in a new session, never a mark', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 1 })] }, async ({ stop, fake }) => {
    const session = sid('s10');
    assertBlock(await stop(stopPayload(session)), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
    const frontiersAfterFirst = fake.frontiers.length;
    assert.equal(frontiersAfterFirst, 1);
    const again = await stop(stopPayload(session));
    assert.deepEqual(again.json, {});
    assert.equal(fake.frontiers.length, frontiersAfterFirst, 'a run already nudged this session is skipped before its frontier read');
    assertBlock(await stop(stopPayload(sid('s10new'))), [NUDGE, `--run ${rid(1)}`]);
    assert.equal(fake.marks.length, 0);
    assert.equal(fake.isMarked(rid(1)), false);
  });
});

test('S10: a run nudged by the trigger is not nudged again by the Stop in the same session', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 1 })] }, async ({ trigger, stop }) => {
    const session = sid('s10chain');
    const t = await trigger(closePayload(session, rid(1)));
    assert.ok(t.json?.hookSpecificOutput?.additionalContext?.includes(NUDGE), t.stdout);
    assert.deepEqual((await stop(stopPayload(session))).json, {});
  });
});

test('S10: a failed mark nudges once; the Stop in the same session does not repeat it', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { mark: 'http500' } }, async ({ stop, fake }) => {
    const session = sid('s10fail');
    assertBlock(await stop(stopPayload(session)), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
    assert.deepEqual((await stop(stopPayload(session))).json, {});
    assert.equal(fake.marks.length, 1);
  });
});

// ---- S7 no REST: record, then one nudge per session --------------------------------------------

test('S7: no API URL: a recorded close is nudged once at Stop (never dispatched, never marked), then {}', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop, fake }) => {
    const session = sid('s7');
    const noApi = { apiUrl: undefined };
    assert.deepEqual((await stop(stopPayload(session), noApi)).json, {}, 'nothing recorded yet');
    assert.deepEqual((await trigger(closePayload(session, rid(1)), noApi)).json, {});
    assertBlock(await stop(stopPayload(session), noApi), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
    assert.deepEqual((await stop(stopPayload(session), noApi)).json, {}, 'at most once');
    // a close recorded after the nudge is not nudged again: at most once per session
    await trigger(closePayload(session, rid(2)), noApi);
    assert.deepEqual((await stop(stopPayload(session), noApi)).json, {});
    assert.equal(fake.requests.length, 0);
  });
});

test('S7: the record is per session: another session with no close gets {}', async () => {
  await withFixture({ config: cfg(), runs: [] }, async ({ trigger, stop }) => {
    const noApi = { apiUrl: undefined };
    await trigger(closePayload(sid('s7a'), rid(1)), noApi);
    assert.deepEqual((await stop(stopPayload(sid('s7b')), noApi)).json, {});
  });
});

test('S7: several closes recorded in one session are all named by the single nudge', async () => {
  await withFixture({ config: cfg(), runs: [] }, async ({ trigger, stop }) => {
    const session = sid('s7many');
    const noApi = { apiUrl: undefined };
    await trigger(closePayload(session, rid(1)), noApi);
    await trigger(closePayload(session, rid(2)), noApi);
    assertBlock(await stop(stopPayload(session), noApi), [`--run ${rid(1)}`, `--run ${rid(2)}`]);
  });
});

// ---- S11 REST configured but no project rootId / no config / user scope ---------------------------

const NO_ROOT_CASES = [
  { name: 'config without project.rootId', opts: { config: retroConfig({ rootId: null, mode: 'dispatch', threshold: 3 }) } },
  { name: 'no config.yaml', opts: { config: null } },
  { name: 'user-scope config carrying a personal root', opts: { config: cfg(), scope: 'user' } },
];

for (const c of NO_ROOT_CASES) {
  test(`S11: API URL set, ${c.name}: zero requests, one nudge for a recorded close, then {}`, async () => {
    await withFixture({ ...c.opts, runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop, fake }) => {
      const session = sid('s11');
      assert.deepEqual((await trigger(closePayload(session, rid(1)))).json, {});
      assertBlock(await stop(stopPayload(session)), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
      assert.deepEqual((await stop(stopPayload(session))).json, {});
      assert.equal(fake.requests.length, 0, JSON.stringify(fake.requests));
    });
  });

  test(`S11: API URL set, ${c.name}: a Stop with nothing recorded is {} with zero requests`, async () => {
    await withFixture({ ...c.opts, runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake }) => {
      assertSilent(await stop(stopPayload(sid('s11none'))), fake);
    });
  });
}

// ---- S8 gating ------------------------------------------------------------------------------------

test('S8: to_mod_retro true -> {} and zero requests; a recorded close is left for a later normal Stop', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop, fake }) => {
    const session = sid('s8mod');
    const noApi = { apiUrl: undefined };
    await trigger(closePayload(session, rid(1)), noApi);
    assert.deepEqual((await stop(stopPayload(session, { to_mod_retro: true }))).json, {});
    assert.equal(fake.requests.length, 0);
    assertBlock(await stop(stopPayload(session), noApi), [`--run ${rid(1)}`]);
  });
});

test('S8: to_mod_retro true on the REST path -> {} and no state written', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake, sbx }) => {
    assertSilent(await stop(stopPayload(sid('s8modrest'), { to_mod_retro: true })), fake);
    assert.deepEqual(listTree(sbx.tmp), []);
  });
});

test('S8: headless iteration -> {} and zero requests; the recorded close survives for a later interactive Stop', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop, fake }) => {
    const session = sid('s8head');
    const noApi = { apiUrl: undefined };
    await trigger(closePayload(session, rid(1)), noApi);
    const headless = await stop(stopPayload(session), { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } });
    assert.deepEqual(headless.json, {});
    assert.equal(fake.requests.length, 0);
    assertBlock(await stop(stopPayload(session), noApi), [`--run ${rid(1)}`]);
  });
});

test('S8: headless iteration on the REST path -> {} with zero requests and no state', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake, sbx }) => {
    const res = await stop(stopPayload(sid('s8headrest')), { env: { TASK_ORCHESTRATOR_MODE: 'headless-iteration' } });
    assertSilent(res, fake);
    assert.deepEqual(listTree(sbx.tmp), []);
  });
});

test('S8: mode off -> {}; the record is untouched, so switching back on nudges', async () => {
  await withFixture({ config: cfg('nudge', 3), runs: [] }, async ({ trigger, stop, sbx }) => {
    const session = sid('s8off');
    const noApi = { apiUrl: undefined };
    await trigger(closePayload(session, rid(1)), noApi);
    sbx.setConfig(cfg('off', 3));
    assert.deepEqual((await stop(stopPayload(session), noApi)).json, {});
    sbx.setConfig(cfg('nudge', 3));
    assertBlock(await stop(stopPayload(session), noApi), [`--run ${rid(1)}`]);
  });
});

test('S8: mode off on the REST path -> {} with zero requests', async () => {
  await withFixture({ config: cfg('off', 3), runs: [runFixture(1, { terminal: 3 })] }, async ({ stop, fake }) => {
    assertSilent(await stop(stopPayload(sid('s8offrest'))), fake);
  });
});

test('S8: a close made by a subagent (agent_id) is recorded and surfaces at the main session Stop', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, stop }) => {
    const session = sid('s8sub');
    const noApi = { apiUrl: undefined };
    assert.deepEqual((await trigger(closePayload(session, rid(1), { extra: { agent_id: 'sub-1' } }), noApi)).json, {});
    assertBlock(await stop(stopPayload(session), noApi), [NUDGE, `--run ${rid(1)}`], [DISPATCH]);
  });
});

test('S8: a subagent close with the REST path available makes no request', async () => {
  await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })] }, async ({ trigger, fake }) => {
    assertSilent(await trigger(closePayload(sid('s8subrest'), rid(1), { extra: { agent_id: 'sub-2' } })), fake);
  });
});

// ---- fail-open --------------------------------------------------------------------------------------

test('probe: malformed stdin -> {} and exit 0', async () => {
  const sbx = makeSandbox({ config: cfg() });
  try {
    const res = await runHook(HOOKS.backstop, '{not valid json', sbx);
    assert.equal(res.status, 0);
    assert.deepEqual(res.json, {});
  } finally {
    sbx.cleanup();
  }
});

for (const failure of ['http500', 'badjson', 'hang']) {
  test(`probe: runs_read list answers '${failure}' -> {} and exit 0`, async () => {
    await withFixture({ config: cfg(), runs: [runFixture(1, { terminal: 3 })], behavior: { list: failure } }, async ({ stop, fake }) => {
      const res = await stop(stopPayload(sid('failopen')));
      assert.equal(res.status, 0);
      assert.deepEqual(res.json, {}, res.stdout);
      assert.equal(fake.marks.length, 0);
    });
  });
}
