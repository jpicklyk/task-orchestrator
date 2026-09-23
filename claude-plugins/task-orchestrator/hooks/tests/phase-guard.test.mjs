// Drives phase-guard.mjs as a subprocess while an in-process http stub server plays the
// GET /api/v1/items/{id}/gate role that phase-guard.mjs's Promise.all fetches from.
//
// MUST use async `spawn` (not `spawnSync`): spawnSync blocks THIS process's event loop for the
// duration of the child, so the stub server — which runs in this same process — could never
// accept or answer a connection from the child. The child's fetch would simply hang until its
// own 2s timeout and the hook would fail open, producing a false green: "the hook always emits
// {}" would trivially pass without the stub ever actually being hit. See the specification's
// harness rule 1.
//
// State isolation matches phase-guard-record.test.mjs: TEMP/TMP/TMPDIR redirected to a fresh
// mkdtempSync dir per test. This file tests the STOP side in isolation, so markers are seeded
// directly via writePhaseGuardMarker/phaseGuardMarkerPath rather than going through the record
// hook.

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

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-'));
}

/** Redirects THIS process's TEMP/TMP/TMPDIR just long enough to resolve/seed/read the same
 * marker path the child (spawned with the same tempDir) will use. */
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

/** Starts an http stub answering GET /api/v1/items/{id}/gate from `routes[id]`, either a
 * `{status, body}` pair or a `(req, res) => void` function for custom behavior (e.g. a delay, or
 * never responding). Any id not in `routes` gets 404. Resolves once listening. */
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
      if (typeof route === 'function') {
        route(req, res);
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

/** Spawns phase-guard.mjs asynchronously, feeds it `payload` on stdin, and resolves with its
 * exit status + stdout once it closes. */
function runHook(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
    delete env.TASK_ORCHESTRATOR_API_URL;
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

function runHookRaw(rawInput, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [HOOK], { env });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(rawInput);
  });
}

const gateOk = (over = {}) => ({
  status: 200,
  body: {
    itemId: over.itemId,
    title: over.title ?? 'Widget frobnicator',
    role: over.role ?? 'work',
    gateStatus: { canAdvance: over.canAdvance ?? false, phase: over.role ?? 'work', missing: over.missing ?? [] },
    ...(over.guidanceKey ? { guidanceKey: over.guidanceKey } : {}),
    ...(over.skillPointer ? { skillPointer: over.skillPointer } : {}),
  },
});

// ── S4: block on missing required notes (string-key shape) ──────────────────────────────────

test('S4: role work with non-empty missing (string keys) blocks, names the key + uuid8, sets blocks=1', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s4-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '4a468f6c-9434-4cbf-97e8-b6c546c1b112';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'], title: 'Widget frobnicator' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
    assert.ok(out.reason.includes(itemId.slice(0, 8)), out.reason);
    assert.ok(out.reason.includes('manage_notes(upsert)'), out.reason);
    assert.equal(readMarker(tempDir, sessionId, agentId).blocks, 1);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S4b: missing normalized from an object shape ([{key: ...}]) blocks the same way', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s4b-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '5b579f7d-a545-4dae-a869-c7d657d205c3';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: {
      status: 200,
      body: {
        itemId,
        title: 'Object-shape item',
        role: 'work',
        gateStatus: { canAdvance: false, phase: 'work', missing: [{ key: 'implementation-notes' }] },
      },
    },
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S4: guidanceKey/skillPointer, when present, are surfaced in the block reason', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s4c-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '6c68a08e-b656-4ebf-b97a-d8e768d316d4';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'review', missing: ['test-manifest'], skillPointer: 'test-author' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('test-author'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S5: allow when nothing is missing ────────────────────────────────────────────────────────

test('S5: missing [] allows — emits {} and deletes the state file', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s5-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '7d79b19f-c767-5fc0-c08b-e9f879e4275e';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({ [itemId]: gateOk({ itemId, role: 'work', missing: [] }) });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S6: block cap ─────────────────────────────────────────────────────────────────────────────

test('S6: blocks already at the cap (2) short-circuits to {} without a network call, deletes the file', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s6-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '8e8ac2b0-d878-6ad1-d19c-fa989af5386f';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 2 });
  // Route through a stub that records whether it was ever hit, to prove the cap check runs
  // BEFORE any fetch (not merely that a fetch failure happens to also read as fail-open).
  let wasHit = false;
  const server = await startStub({
    [itemId]: (req, res) => {
      wasHit = true;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(gateOk({ itemId, role: 'work', missing: ['implementation-notes'] }).body));
    },
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(wasHit, false, 'phase-guard.mjs fetched the gate despite the block cap already being reached');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S7: non-blocking roles / non-2xx responses ──────────────────────────────────────────────

test('S7: queue role with non-empty missing does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s7-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '9f9bd3c1-e989-7be2-e2ad-0b09ab06497a';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'queue', missing: ['feature-summary'] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S7: terminal role with non-empty missing does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s7t-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'a0acd4d2-f09a-8cf3-f3be-1c1abc17580b';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'terminal', missing: ['resolution'] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S7b: a 403 (token lacking READ) for the item does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s7b-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'b1bde5e3-019b-9d04-0409-2d2bcd28691c';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: { status: 403, body: { error: 'scope_forbidden', message: 'no READ capability' } },
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S7b: a 404 (item not found) for the item does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s7b2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c2cef6f4-12ac-0e15-151a-3e3cde39702d';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({}); // no route -> 404 for everything
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S8: fail-open everywhere ─────────────────────────────────────────────────────────────────

test('S8: no API URL configured yields {} without attempting any fetch', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s8-${randomUUID()}`;
  const agentId = 'agent-1';
  seedMarker(tempDir, sessionId, agentId, { items: ['d3d007a5-23bd-1f26-2621-4f4dfe4a814e'], blocks: 0 });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, undefined);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: server unreachable (connection refused) yields {} and clears the marker', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s8b-${randomUUID()}`;
  const agentId = 'agent-1';
  seedMarker(tempDir, sessionId, agentId, { items: ['e4e118b6-34ce-2037-3732-5a5eaf5b925f'], blocks: 0 });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, 'http://127.0.0.1:1');
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: a stub that never responds times out per-item and still yields {} (fail-open, not a hang)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s8c-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f5f229c7-45df-3148-4843-6b6fb06ca360';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: () => {
      // Never call res.end() — the request hangs until phase-guard.mjs's own 2s per-item timeout.
    },
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: malformed stdin yields {} and exit 0', async () => {
  const tempDir = freshTempDir();
  try {
    const res = await runHookRaw('{not valid json', tempDir, 'http://127.0.0.1:1');
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('no state file for this agent (e.g. an internal Claude Code agent) yields {} without a fetch', async () => {
  const tempDir = freshTempDir();
  const sessionId = `nostate-${randomUUID()}`;
  const agentId = 'internal-agent';
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, 'http://127.0.0.1:1');
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Parallelism: N recorded items are fetched concurrently, not sequentially ────────────────

test('recorded items are fetched in parallel — total time is close to one request, not the sum', async () => {
  const tempDir = freshTempDir();
  const sessionId = `parallel-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemA = '06061829-56f0-4259-5954-7c7cd17db471';
  const itemB = '17172930-67f1-536a-6a65-8d8de28ec582';
  const DELAY_MS = 300;
  seedMarker(tempDir, sessionId, agentId, { items: [itemA, itemB], blocks: 0 });
  const delayedGate = (id) => (req, res) => {
    setTimeout(() => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(gateOk({ itemId: id, role: 'work', missing: [] }).body));
    }, DELAY_MS);
  };
  const server = await startStub({ [itemA]: delayedGate(itemA), [itemB]: delayedGate(itemB) });
  try {
    const start = Date.now();
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const elapsed = Date.now() - start;
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    // Sequential would be >= 2 * DELAY_MS (600ms); parallel stays close to one request's delay.
    assert.ok(elapsed < DELAY_MS * 2, `expected parallel fetch (< ${DELAY_MS * 2}ms), took ${elapsed}ms`);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Multiple recorded items: only the blocking ones are named ──────────────────────────────

test('multiple recorded items — only the ones with missing work/review notes are named in the reason', async () => {
  const tempDir = freshTempDir();
  const sessionId = `multi-${randomUUID()}`;
  const agentId = 'agent-1';
  const blockedItem = '28283a41-78f2-647b-7b76-9e9ef39fd693';
  const cleanItem = '393949b2-89a3-758c-8c87-a0a0049ae7a4';
  seedMarker(tempDir, sessionId, agentId, { items: [blockedItem, cleanItem], blocks: 0 });
  const server = await startStub({
    [blockedItem]: gateOk({ itemId: blockedItem, role: 'work', missing: ['implementation-notes'], title: 'Blocked one' }),
    [cleanItem]: gateOk({ itemId: cleanItem, role: 'work', missing: [] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes(blockedItem.slice(0, 8)), out.reason);
    assert.ok(!out.reason.includes(cleanItem.slice(0, 8)), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});
