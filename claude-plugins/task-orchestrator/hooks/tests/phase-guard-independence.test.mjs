// Independently authored against the frozen `task-scope` / `test-plan` / `task-scope-addendum`
// notes on item 09cd604f (stage A2b) -- S13: "phase-guard blocks on its seat's non-waived
// violations only when canAdvance false" (addendum "Scenario detail" S13). This file is the
// plugin-hook half of A2's independence-attestation gate; the REST half lives in
// IndependenceGateRestTest.kt (same item, same dispatch).
//
// NEW-SURFACE: the `violations` field on the /gate stub response, and phase-guard.mjs's handling
// of it, are introduced by this item. Per the dispatch contract's Test author protocol rule 7,
// red-proof is orchestrator-run against the addendum's M15 mutation recipe ("phase-guard ignores
// violations -> S13 red"); this file keeps every declaration new to this item (the `violations`
// array on the stub body) and attempts no revert of its own.
//
// Oracle: task-scope "Consumers" ("... and the phase-guard hook") and task-scope-addendum's S13
// "Scenario detail" line, applied by hand below -- never read from phase-guard.mjs's own source,
// which is banned reading for this dispatch (blind test author, task-scope-addendum banned file).
//
// HARNESS: identical subprocess-plus-stub-server harness as phase-guard.test.mjs (learned by
// reading that file, which is explicitly permitted -- src/test is not banned, only src/main and
// phase-guard.mjs's own source). Helpers (startStub/stopStub/runHook/freshTempDir/seedMarker) are
// duplicated locally rather than imported: this file owns no shared fixture with
// phase-guard.test.mjs, per the dispatch contract's file ownership (NEW files only). `gateOk` is
// extended locally with an optional `violations` array and an explicit `canAdvance` override, since
// the upstream helper's `canAdvance` defaults to `false` and has no violations parameter at all.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { phaseGuardMarkerPath, writePhaseGuardMarker } from '../phase-guard-record.mjs';

const HOOK = fileURLToPath(new URL('../phase-guard.mjs', import.meta.url));

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-indep-'));
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

function startStub(routes) {
  return new Promise((resolveListen) => {
    const server = createServer((req, res) => {
      const match = req.url.match(/^\/api\/v1\/items\/([^/]+)\/gate$/);
      const id = match ? match[1] : null;
      const route = id ? routes[id] : undefined;
      if (route === undefined) {
        res.writeHead(404, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'not_found', message: `Item ${id} not found` }));
        return;
      }
      const { status = 200, body = {} } = route;
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    });
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

function runHook(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [HOOK], { env });
    let stdout = '';
    let stderr = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.stderr.on('data', (d) => {
      stderr += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout, stderr }));
    child.stdin.end(JSON.stringify(payload));
  });
}

/** Builds a /gate stub body. `violations`, when passed, is the raw array (each entry
 * {key, seat?, constraint, conflictingSeat?, waived?} per task-scope-addendum's frozen JSON
 * shape); omitting it entirely (undefined) means the response has NO `violations` key at all --
 * the pre-A2 shape, distinct from an empty array. */
const gateOk = (over = {}) => ({
  status: 200,
  body: {
    itemId: over.itemId,
    title: over.title ?? 'Widget frobnicator',
    role: over.role ?? 'work',
    gateStatus: {
      canAdvance: over.canAdvance ?? false,
      phase: over.role ?? 'work',
      missing: over.missing ?? [],
      ...(over.violations !== undefined ? { violations: over.violations } : {}),
    },
  },
});

// ── S13-T1: canAdvance false + a non-waived violation naming the caller's own seat -> blocks ──

test('S13-T1: implementer, canAdvance false, non-waived violation seat=implementer -> blocks, reason names key + constraint', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000001-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      missing: [],
      canAdvance: false,
      violations: [{ key: 'test-manifest', seat: 'implementer', constraint: 'same_actor', conflictingSeat: 'test-author', waived: false }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block', `expected block, got: ${res.stdout}`);
    assert.ok(out.reason.includes('test-manifest'), out.reason);
    assert.ok(out.reason.includes('same_actor'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T2: same violation, but canAdvance true (WARN mode) -> never blocks ─────────────────

test('S13-T2: canAdvance true (WARN) with the same non-waived violation present -> does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000002-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      missing: [],
      canAdvance: true,
      violations: [{ key: 'test-manifest', seat: 'implementer', constraint: 'same_actor', conflictingSeat: 'test-author', waived: false }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}', `expected {} (WARN mode never blocks), got: ${res.stdout}`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T3: canAdvance false, but the ONLY violation is waived -> does not block ─────────────

test('S13-T3: canAdvance false with a waived-only violation for the caller\'s seat -> does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t3-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000003-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      missing: [],
      canAdvance: false,
      violations: [{ key: 'test-manifest', seat: 'implementer', constraint: 'same_actor', conflictingSeat: 'test-author', waived: true }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}', `expected {} (waived entries never block), got: ${res.stdout}`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T4: violation names a DIFFERENT seat than the caller (test-manifest / test-author) ──
// -- not demanded of the implementer -> does not block, even with canAdvance false.

test('S13-T4: violation seat=test-author, caller is implementer -> does not block (key not demanded of this seat)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t4-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000004-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      missing: [],
      canAdvance: false,
      violations: [{ key: 'test-manifest', seat: 'test-author', constraint: 'same_actor', conflictingSeat: 'implementer', waived: false }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}', `expected {} (test-manifest is test-author's key, not implementer's), got: ${res.stdout}`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T5: general seat mismatch -- a review-phase violation for the reviewer seat, caller is
// implementer -> does not block (distinct fixture from T4: different role/seat pairing).

test('S13-T5: violation seat=reviewer, caller is implementer -> does not block (seat mismatch)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t5-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000005-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      missing: [],
      canAdvance: false,
      violations: [{ key: 'review-checklist', seat: 'reviewer', constraint: 'missing_actor' }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}', `expected {} (reviewer's violation is not the implementer's concern), got: ${res.stdout}`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T6: no `violations` key on the gate body at all (pre-A2 / independence off shape) --
// prior behavior is unchanged: blocking is driven purely by `missing`.

test('S13-T6: gate body has no violations key at all -- blocks on missing exactly as before A2', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t6-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000006-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    // over.violations left undefined -> gateOk emits gateStatus with NO `violations` key.
    [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'], canAdvance: false }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block', `expected block, got: ${res.stdout}`);
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13-T7: no `violations` key at all AND missing [] -> {} (regression sanity: an absent
// violations key must never itself be treated as a block condition).

test('S13-T7: no violations key and missing [] -> {} (an absent violations key is not itself a block condition)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13t7-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000007-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: [], canAdvance: true }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── SEC2 (orchestrator review follow-up, 2026-09-28, HEAD 0b2633ac): missing notes AND a
// non-waived violation for the caller's own seat present TOGETHER, canAdvance false -- the block
// must be driven purely by the missing-notes gate (existing pre-A2 behavior, keyed off `missing`),
// and the independence violation must NOT be surfaced as an independent block reason. This is
// distinct from S13-T1 (violation alone, missing []) and S13-T6 (missing alone, no violations key
// at all): here BOTH are present on the same response, and the oracle (orchestrator dispatch,
// citing task-scope-addendum's frozen semantics) says the violation must not double up into the
// reason text -- only the missing key belongs there.

test('SEC2: missing notes AND a non-waived violation for the caller\'s seat together -- reason names only the missing key, never the violation', async () => {
  const tempDir = freshTempDir();
  const sessionId = `sec2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000008-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({
      itemId,
      role: 'work',
      // Distinct key from the violation's own key, so the reason's provenance is unambiguous.
      missing: ['implementation-notes'],
      canAdvance: false,
      violations: [{ key: 'test-manifest', seat: 'implementer', constraint: 'same_actor', conflictingSeat: 'test-author', waived: false }],
    }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block', `expected block on the missing note, got: ${res.stdout}`);
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
    assert.ok(!out.reason.includes('test-manifest'), `violation's own key must not be surfaced: ${out.reason}`);
    assert.ok(!out.reason.includes('same_actor'), `violation constraint must not be surfaced: ${out.reason}`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});
