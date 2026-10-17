// phase-guard.mjs (SubagentStop) seat-bucket mode (item 2bf68690): when the server reports
// gateStatus.missingBySeat, the context view lists the current-phase `seats`, and the stopping
// agent maps to a seat named in that list, the hook blocks only on missingBySeat[seat]. Anything
// else falls back to the pre-existing flat filter.
//
// Oracles (frozen in the item test-plan, written before the implementation existed):
//   O1 item acceptance criterion: only missingBySeat[seat] when present and the seat is known,
//      otherwise the current filter.
//   O2 task-scope Rule: "known" = named (exact, case-sensitive) in the current-phase `seats`;
//      an absent bucket is empty; `unowned` and other seats keys never block; server ownership
//      beats TEST_AUTHOR_OWNED_KEYS; a malformed missingBySeat or bucket falls back.
//   O3 api-reference items_read context shape, including the reserved `unowned` bucket.
//   O4 phase-guard.mjs header: the skill hint is shown only when missing[0] is the first raw key.
//
// Harness: same shape as phase-guard.test.mjs (async spawn, in-process http stub playing
// POST /api/v1/ops/items_read, markers seeded directly). spawnSync would block the stub event
// loop and produce a false green, so it is not used.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { phaseGuardMarkerPath, writePhaseGuardMarker, readPhaseGuardMarker } from '../phase-guard-record.mjs';

const HOOK = fileURLToPath(new URL('../phase-guard.mjs', import.meta.url));

const IMPL = 'task-orchestrator:implementer';
const REVIEWER = 'task-orchestrator:reviewer';
const TEST_AUTHOR = 'task-orchestrator:test-author';

/** Work-phase seats of the dogfood feature-task schema. */
const W = [
  { name: 'implementer', phase: 'work', enters: true },
  { name: 'orchestrator', phase: 'work', after: ['implementer'] },
  { name: 'test-author', phase: 'work', after: ['implementer'] },
];

const REVIEW_SEATS = [
  { name: 'reviewer', phase: 'review' },
  { name: 'security-reviewer', phase: 'review' },
];

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-mbs-'));
}

function withRedirectedTmp(tempDir, fn) {
  const saved = { TEMP: process.env.TEMP, TMP: process.env.TMP, TMPDIR: process.env.TMPDIR };
  process.env.TEMP = tempDir;
  process.env.TMP = tempDir;
  process.env.TMPDIR = tempDir;
  try {
    return fn();
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

function seedMarker(tempDir, sessionId, agentId, marker) {
  withRedirectedTmp(tempDir, () => {
    writePhaseGuardMarker(phaseGuardMarkerPath(sessionId, agentId), marker);
  });
}

function readMarker(tempDir, sessionId, agentId) {
  return withRedirectedTmp(tempDir, () => readPhaseGuardMarker(phaseGuardMarkerPath(sessionId, agentId)));
}

/** http stub for POST /api/v1/ops/items_read; `routes[itemId]` is a `{status, body}` reply. */
function startStub(routes) {
  return new Promise((resolveListen) => {
    const requests = [];
    const server = createServer((req, res) => {
      let raw = '';
      req.on('data', (d) => {
        raw += d;
      });
      req.on('end', () => {
        requests.push({ method: req.method, url: req.url, body: raw });
        let id = null;
        if (req.method === 'POST' && req.url === '/api/v1/ops/items_read') {
          try {
            id = JSON.parse(raw).itemId ?? null;
          } catch {
            id = null;
          }
        }
        const route = id ? routes[id] : undefined;
        if (route === undefined) {
          res.writeHead(404, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ ok: false, error: { code: 'not_found', kind: 'permanent', message: `Item ${id} not found` } }));
          return;
        }
        res.writeHead(route.status ?? 200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(route.body ?? {}));
      });
    });
    server.requests = requests;
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

function runHook(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = {
      ...process.env,
      TEMP: tempDir,
      TMP: tempDir,
      TMPDIR: tempDir,
      TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'),
      TASK_ORCHESTRATOR_CEILING: tempDir,
    };
    delete env.AGENT_CONFIG_DIR;
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [HOOK], { env, cwd: tempDir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

/** items_read context reply. `missingBySeat` and `seats` are included only when the key is
 * present on `over` (so an explicit `null` / non-array value is sent verbatim). */
function gate(over) {
  const gateStatus = { canAdvance: false, missing: over.missing ?? [], violations: [] };
  if ('missingBySeat' in over) gateStatus.missingBySeat = over.missingBySeat;
  const data = {
    item: { id: over.itemId, title: over.title ?? 'Widget frobnicator', role: over.role ?? 'work' },
    gateStatus,
  };
  if ('seats' in over) data.seats = over.seats;
  if (over.skillPointer) data.skillPointer = over.skillPointer;
  return { status: 200, body: { ok: true, data, meta: { reqId: 'req00001' } } };
}

/** Seeds a marker for the given per-item gate overrides, runs the hook as `agentType`, and
 * returns the parsed outcome. Each entry of `items` is passed to gate() (itemId is assigned). */
async function run({ agentType, items, blocks = 0 }) {
  const tempDir = freshTempDir();
  const sessionId = `mbs-${randomUUID()}`;
  const agentId = 'agent-1';
  const ids = items.map(() => randomUUID());
  const routes = {};
  items.forEach((over, i) => {
    routes[ids[i]] = gate({ ...over, itemId: ids[i] });
  });
  seedMarker(tempDir, sessionId, agentId, { items: ids, blocks });
  const server = await startStub(routes);
  try {
    const payload = { session_id: sessionId, agent_id: agentId };
    if (agentType !== undefined) payload.agent_type = agentType;
    const res = await runHook(payload, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    return { out, ids, marker: readMarker(tempDir, sessionId, agentId), requests: server.requests };
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
}

function assertAllow(r) {
  assert.deepEqual(r.out, {}, `expected allow, got ${JSON.stringify(r.out)}`);
  assert.deepEqual(r.marker.items, []);
}

function assertBlock(r) {
  assert.equal(r.out.decision, 'block', `expected block, got ${JSON.stringify(r.out)}`);
  assert.equal(typeof r.out.reason, 'string');
}

// -- Happy ------------------------------------------------------------------------------------

test('S1: implementer, only another seat note missing (orchestrator:[session-tracking]) -> allow', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['session-tracking'], missingBySeat: { orchestrator: ['session-tracking'] }, seats: W }],
  });
  assertAllow(r);
});

test('S2: implementer, own and another seat note missing -> blocks naming only its own, blocks=1', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['implementation-notes', 'session-tracking'],
        missingBySeat: { implementer: ['implementation-notes'], orchestrator: ['session-tracking'] },
        seats: W,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.ok(!r.out.reason.includes('session-tracking'), r.out.reason);
  assert.equal(r.marker.blocks, 1);
});

test('S3: reviewer sharing the review phase with security-reviewer, only the other seat note missing -> allow', async () => {
  const r = await run({
    agentType: REVIEWER,
    items: [
      {
        role: 'review',
        missing: ['security-review'],
        missingBySeat: { 'security-reviewer': ['security-review'] },
        seats: REVIEW_SEATS,
      },
    ],
  });
  assertAllow(r);
});

test('S3b: reviewer with its own note also missing blocks naming its own, not the other reviewer note', async () => {
  const r = await run({
    agentType: REVIEWER,
    items: [
      {
        role: 'review',
        missing: ['security-review', 'review-checklist'],
        missingBySeat: { reviewer: ['review-checklist'], 'security-reviewer': ['security-review'] },
        seats: REVIEW_SEATS,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('review-checklist'), r.out.reason);
  assert.ok(!r.out.reason.includes('security-review'), r.out.reason);
});

test('S6: test-author blocks on its own test-manifest only, not the implementer note', async () => {
  const r = await run({
    agentType: TEST_AUTHOR,
    items: [
      {
        missing: ['implementation-notes', 'test-manifest'],
        missingBySeat: { implementer: ['implementation-notes'], 'test-author': ['test-manifest'] },
        seats: W,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('test-manifest'), r.out.reason);
  assert.ok(!r.out.reason.includes('implementation-notes'), r.out.reason);
});

// -- Edge -------------------------------------------------------------------------------------

test('S4: a note in the `unowned` bucket never blocks the implementer', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['trait-note'], missingBySeat: { unowned: ['trait-note'] }, seats: W }],
  });
  assertAllow(r);
});

test('S10: filtered first key is not the raw first key -> skill hint is not shown', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['session-tracking', 'implementation-notes'],
        missingBySeat: { implementer: ['implementation-notes'], orchestrator: ['session-tracking'] },
        seats: W,
        skillPointer: 'review-quality',
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.ok(!r.out.reason.includes('session-tracking'), r.out.reason);
  assert.ok(!r.out.reason.includes('review-quality'), r.out.reason);
});

test('S10b: filtered first key IS the raw first key -> skill hint is shown', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['implementation-notes', 'session-tracking'],
        missingBySeat: { implementer: ['implementation-notes'], orchestrator: ['session-tracking'] },
        seats: W,
        skillPointer: 'review-quality',
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.ok(r.out.reason.includes('review-quality'), r.out.reason);
});

test('S11: server ownership beats TEST_AUTHOR_OWNED_KEYS -> implementer-owned test-manifest blocks the implementer', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['test-manifest'], missingBySeat: { implementer: ['test-manifest'] }, seats: W }],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('test-manifest'), r.out.reason);
});

test('S12: two recorded items, only the second has a note owned by the implementer -> reason names the second only', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      { title: 'Item alpha', missing: ['session-tracking'], missingBySeat: { orchestrator: ['session-tracking'] }, seats: W },
      { title: 'Item bravo', missing: ['implementation-notes'], missingBySeat: { implementer: ['implementation-notes'] }, seats: W },
    ],
  });
  assertBlock(r);
  const [a, b] = r.ids;
  assert.ok(r.out.reason.includes(b.slice(0, 8)), r.out.reason);
  assert.ok(!r.out.reason.includes(a.slice(0, 8)), r.out.reason);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.ok(!r.out.reason.includes('session-tracking'), r.out.reason);
});

// -- Fallback (current flat filter) -----------------------------------------------------------

test('S5: no missingBySeat / seats -> flat filter: implementer blocks on session-tracking', async () => {
  const r = await run({ agentType: IMPL, items: [{ missing: ['session-tracking'] }] });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('S5b: no missingBySeat / seats -> flat filter: implementer is not blocked by test-manifest', async () => {
  const r = await run({ agentType: IMPL, items: [{ missing: ['test-manifest'] }] });
  assertAllow(r);
});

test('S7: implementer not named in seats (custom seat `coder`) -> flat filter, every non-test-author key blocks', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['implementation-notes', 'session-tracking'],
        missingBySeat: { coder: ['implementation-notes'], orchestrator: ['session-tracking'] },
        seats: [{ name: 'coder', phase: 'work', enters: true }],
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('S8: no agent_type -> flat filter even with missingBySeat + seats: blocks on session-tracking, not test-manifest', async () => {
  const r = await run({
    agentType: undefined,
    items: [
      {
        missing: ['session-tracking', 'test-manifest'],
        missingBySeat: { orchestrator: ['session-tracking'] },
        seats: W,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
  assert.ok(!r.out.reason.includes('test-manifest'), r.out.reason);
});

for (const [label, bySeat] of [
  ['null', null],
  ['an array ([])', []],
  ['a string ("x")', 'x'],
]) {
  test(`S9: missingBySeat is ${label} (malformed) -> flat filter, implementer blocks on session-tracking`, async () => {
    const r = await run({
      agentType: IMPL,
      items: [{ missing: ['session-tracking'], missingBySeat: bySeat, seats: W }],
    });
    assertBlock(r);
    assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
  });
}

// -- Adversarial probes -----------------------------------------------------------------------

test('probe casing: seat name in `seats` differs only by case -> exact match fails, flat filter', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['session-tracking'],
        missingBySeat: { orchestrator: ['session-tracking'] },
        seats: [
          { name: 'Implementer', phase: 'work', enters: true },
          { name: 'orchestrator', phase: 'work' },
        ],
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('probe casing: bucket key differs only by case -> bucket for `implementer` is absent, so allow', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['implementation-notes'], missingBySeat: { Implementer: ['implementation-notes'] }, seats: W }],
  });
  assertAllow(r);
});

test('probe: bucket entries in object shape ([{key}]) are normalized', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['implementation-notes'], missingBySeat: { implementer: [{ key: 'implementation-notes' }] }, seats: W }],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
});

test('probe: own bucket value is a non-array (malformed) -> flat filter', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['session-tracking'],
        missingBySeat: { implementer: 'implementation-notes', orchestrator: ['session-tracking'] },
        seats: W,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('probe: empty missingBySeat object {} -> seat mode, absent bucket is empty -> allow', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['session-tracking'], missingBySeat: {}, seats: W }],
  });
  assertAllow(r);
});

test('probe: missingBySeat present but `seats` absent -> flat filter', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['session-tracking'], missingBySeat: { orchestrator: ['session-tracking'] } }],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('probe: `seats` is not an array -> flat filter', async () => {
  const r = await run({
    agentType: IMPL,
    items: [{ missing: ['session-tracking'], missingBySeat: { orchestrator: ['session-tracking'] }, seats: 'implementer' }],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('session-tracking'), r.out.reason);
});

test('probe: duplicate key in the own bucket -> still one block, key named', async () => {
  const r = await run({
    agentType: IMPL,
    items: [
      {
        missing: ['implementation-notes'],
        missingBySeat: { implementer: ['implementation-notes', 'implementation-notes'] },
        seats: W,
      },
    ],
  });
  assertBlock(r);
  assert.ok(r.out.reason.includes('implementation-notes'), r.out.reason);
  assert.equal(r.marker.blocks, 1);
});

test('probe replay: seat-mode block with blocks=1 still blocks and counts to 2; blocks=2 (cap) allows', async () => {
  const item = {
    missing: ['implementation-notes'],
    missingBySeat: { implementer: ['implementation-notes'] },
    seats: W,
  };
  const second = await run({ agentType: IMPL, items: [item], blocks: 1 });
  assertBlock(second);
  assert.equal(second.marker.blocks, 2);
  const capped = await run({ agentType: IMPL, items: [item], blocks: 2 });
  assert.deepEqual(capped.out, {});
});