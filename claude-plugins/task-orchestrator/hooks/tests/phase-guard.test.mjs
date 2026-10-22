// Drives phase-guard.mjs as a subprocess while an in-process http stub server plays the
// POST /api/v1/ops/items_read (select context) role that phase-guard.mjs's Promise.all fetches from
// (0bbdc4cc P10: the 4.0 gate read; the 3.x GET /api/v1/items/{id}/gate route is never requested).
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
import { mkdtempSync, rmSync, mkdirSync, writeFileSync } from 'node:fs';
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

/** Starts an http stub playing the 4.0 gate read: POST /api/v1/ops/items_read with body
 * {select:'context', itemId}. The reply is looked up from `routes[body.itemId]`, either a
 * `{status, body}` pair or a `(req, res) => void` function for custom behavior (e.g. a delay, or
 * never responding). Any id not in `routes` gets a 404 not_found error envelope. `legacyGate`, when
 * given, serves the 3.x GET /api/v1/items/{id}/gate route from it (id -> {status, body}) so a test
 * can prove the hook never falls back to it. Every request is recorded on `server.requests` as
 * {method, url, body}. Resolves once listening. */
function startStub(routes, { legacyGate } = {}) {
  return new Promise((resolveListen) => {
    const requests = [];
    const server = createServer((req, res) => {
      let raw = '';
      req.on('data', (d) => {
        raw += d;
      });
      req.on('end', () => {
        requests.push({ method: req.method, url: req.url, body: raw });
        const legacyMatch = req.url.match(/^\/api\/v1\/items\/([^/]+)\/gate$/);
        if (req.method === 'GET' && legacyMatch && legacyGate && legacyGate[legacyMatch[1]]) {
          const { status = 200, body = {} } = legacyGate[legacyMatch[1]];
          res.writeHead(status, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify(body));
          return;
        }
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
        if (typeof route === 'function') {
          route(req, res);
          return;
        }
        const { status = 200, body = {} } = route;
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(body));
      });
    });
    server.requests = requests;
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

/** Spawns phase-guard.mjs asynchronously, feeds it `payload` on stdin, and resolves with its
 * exit status + stdout once it closes. */
function runHook(payload, tempDir, apiUrl, modeOverride, extraEnv) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
    delete env.AGENT_CONFIG_DIR;
    delete env.TASK_ORCHESTRATOR_API_URL;
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    if (modeOverride) env.TASK_ORCHESTRATOR_MODE = modeOverride;
    if (extraEnv) Object.assign(env, extraEnv);
    const child = spawn(process.execPath, [HOOK], { env, cwd: tempDir });
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
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
    delete env.AGENT_CONFIG_DIR;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [HOOK], { env, cwd: tempDir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(rawInput);
  });
}

/** The 4.0 items_read context reply (api-reference "items_read" select context): the item view,
 * its gateStatus, and the optional guidanceKey / skillPointer. Returns {status, body}. */
const gateOk = (over = {}) => ({
  status: 200,
  body: {
    ok: true,
    data: {
      item: { id: over.itemId, title: over.title ?? 'Widget frobnicator', role: over.role ?? 'work' },
      gateStatus: { canAdvance: over.canAdvance ?? false, missing: over.missing ?? [], violations: [] },
      ...(over.guidanceKey ? { guidanceKey: over.guidanceKey } : {}),
      ...(over.skillPointer ? { skillPointer: over.skillPointer } : {}),
    },
    meta: { reqId: 'req00001' },
  },
});

/** A 4.0 error envelope reply at the given HTTP status. */
const gateErr = (status, code, kind = 'permanent') => ({
  status,
  body: { ok: false, error: { code, kind, message: `${code} fixture` }, meta: { reqId: 'req00002' } },
});

const INERT_PHASE_GUARD =
  'task-orchestrator: phase-guard.mjs is inert: no REST API URL resolves (set TASK_ORCHESTRATOR_API_URL or client.json apiUrl); in 4.0 schema facts and gate state come from the server.';

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
    assert.ok(out.reason.includes('notes_write'), out.reason);
    assert.ok(!out.reason.includes('manage_notes'), out.reason);
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
    [itemId]: gateOk({ itemId, title: 'Object-shape item', role: 'work', missing: [{ key: 'implementation-notes' }] }),
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
    [itemId]: gateOk({ itemId, role: 'review', missing: ['review-checklist'], skillPointer: 'review-quality' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('review-quality'), out.reason);
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
    [itemId]: gateErr(403, 'forbidden'),
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

// ── 004d65fd: S12 — headless iteration never fetches the gate, even with a recorded marker ───

// ── Seat awareness (8b4afacc / 8c6170d6 / 2c90be3d): agent_type-driven seat filtering ──────────

test('S1: implementer, work, missing [test-manifest] -> {}, marker deleted', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '10101010-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['test-manifest'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S2: implementer, work, missing [session-tracking, test-manifest] -> blocks on session-tracking only', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '20202020-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['session-tracking', 'test-manifest'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('session-tracking'), out.reason);
    assert.ok(!out.reason.includes('test-manifest'), out.reason);
    assert.equal(readMarker(tempDir, sessionId, agentId).blocks, 1);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3: implementer, work, missing [session-tracking] (non-test-author item) -> blocks (regression check)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s3-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '30303030-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['session-tracking'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('session-tracking'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S4-seat: reviewer, review, missing [test-independence-audit] -> blocks, naming it', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s4-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '40404040-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'review', missing: ['test-independence-audit'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:reviewer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('test-independence-audit'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S5: reviewer seat, item in work, missing [session-tracking] -> {} (seat/role mismatch, never blocks)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s5-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '50505050-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['session-tracking'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:reviewer' },
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

test('S6: implementer seat, item in review, missing [review-checklist] -> {} (seat/role mismatch, never blocks)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s6-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '60606060-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'review', missing: ['review-checklist'] }),
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

test('S7-seat: agent_type absent, work, missing [session-tracking, test-manifest] -> blocks on session-tracking only', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s7-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '70707070-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['session-tracking', 'test-manifest'] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('session-tracking'), out.reason);
    assert.ok(!out.reason.includes('test-manifest'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8-seat: agent_type absent, work, missing [test-manifest] -> {}', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s8-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '80808080-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['test-manifest'] }),
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

test('S9: test-author and x:test-author, work, missing [test-manifest] -> blocks', async () => {
  const tempDir = freshTempDir();
  for (const agentType of ['test-author', 'x:test-author']) {
    const sessionId = `seat-s9-${randomUUID()}`;
    const agentId = 'agent-1';
    const itemId = '90909090-1111-2222-3333-444444444444';
    seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
    const server = await startStub({
      [itemId]: gateOk({ itemId, role: 'work', missing: ['test-manifest'] }),
    });
    try {
      const res = await runHook(
        { session_id: sessionId, agent_id: agentId, agent_type: agentType },
        tempDir,
        `http://127.0.0.1:${server.address().port}`
      );
      const out = JSON.parse(res.stdout);
      assert.equal(out.decision, 'block', `expected block for agent_type=${agentType}`);
      assert.ok(out.reason.includes('test-manifest'), out.reason);
    } finally {
      await stopStub(server);
    }
  }
  rmSync(tempDir, { recursive: true, force: true });
});

test('S10: bare "implementer" behaves exactly like S1', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s10-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'a0a0a0a0-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['test-manifest'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'implementer' },
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

test('S11: task-orchestrator:implementer-helper, review, missing [x] -> blocks (fallback applies, not a seat)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s11-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'b0b0b0b0-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'review', missing: ['x'] }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer-helper' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('x'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S12-seat: two items, A filters to empty (test-manifest only), B has its own missing key -> reason names only B', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s12-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemA = 'c0c0c0c0-1111-2222-3333-444444444444';
  const itemB = 'd0d0d0d0-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemA, itemB], blocks: 0 });
  const server = await startStub({
    [itemA]: gateOk({ itemId: itemA, role: 'work', missing: ['test-manifest'], title: 'A' }),
    [itemB]: gateOk({ itemId: itemB, role: 'work', missing: ['session-tracking'], title: 'B' }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(!out.reason.includes(itemA.slice(0, 8)), out.reason);
    assert.ok(out.reason.includes(itemB.slice(0, 8)), out.reason);
    assert.ok(out.reason.includes('session-tracking'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S13: raw missing [test-manifest, session-tracking] with skillPointer test-author -> reason omits the hint', async () => {
  const tempDir = freshTempDir();
  const sessionId = `seat-s13-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'e0e0e0e0-1111-2222-3333-444444444444';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['test-manifest', 'session-tracking'], skillPointer: 'test-author' }),
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`
    );
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('session-tracking'), out.reason);
    assert.ok(!out.reason.includes('test-author'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Entered-role gating (036420aa): block only on the role actually entered ────────────────────

test('entered-role: item moved past the entered role (gate.role differs) -> {} even with missing notes on the new role', async () => {
  const tempDir = freshTempDir();
  const sessionId = `role1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'e1e1e1e1-0000-0000-0000-000000000001';
  // The implementer entered "work"; a second seat has since advanced the item to "review", which
  // now has its own missing notes. The implementer must not be blocked on those review notes.
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0, enteredRoles: { [itemId]: 'work' } });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'review', missing: ['review-checklist'] }),
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

test('entered-role: item still in the entered role with missing notes -> blocks as before', async () => {
  const tempDir = freshTempDir();
  const sessionId = `role2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'e1e1e1e1-0000-0000-0000-000000000002';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0, enteredRoles: { [itemId]: 'work' } });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('entered-role: no recorded role for the item (older marker) falls back to current-role behavior and blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `role3-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'e1e1e1e1-0000-0000-0000-000000000003';
  // No enteredRoles field at all — mirrors a marker written before this feature existed.
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('entered-role: two items, one moved past its entered role and one still in it -> reason names only the latter', async () => {
  const tempDir = freshTempDir();
  const sessionId = `role4-${randomUUID()}`;
  const agentId = 'agent-1';
  const movedItem = 'e1e10004-0000-0000-0000-000000000004';
  const stillItem = 'e1e10005-0000-0000-0000-000000000005';
  seedMarker(tempDir, sessionId, agentId, {
    items: [movedItem, stillItem],
    blocks: 0,
    enteredRoles: { [movedItem]: 'work', [stillItem]: 'work' },
  });
  const server = await startStub({
    [movedItem]: gateOk({ itemId: movedItem, role: 'review', missing: ['review-checklist'], title: 'Moved on' }),
    [stillItem]: gateOk({ itemId: stillItem, role: 'work', missing: ['implementation-notes'], title: 'Still here' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(!out.reason.includes(movedItem.slice(0, 8)), out.reason);
    assert.ok(out.reason.includes(stillItem.slice(0, 8)), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('B2: two Stops — first blocks on work notes and preserves enteredRoles, second (after the item moves to review) does not block', async () => {
  const tempDir = freshTempDir();
  const sessionId = `b2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'b2b2b2b2-0000-0000-0000-000000000001';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0, enteredRoles: { [itemId]: 'work' } });
  // The gate route is mutable so the second Stop observes the item having since moved to review —
  // this is what a real run looks like: the agent enters work, gets blocked, the item is later
  // advanced to review by another seat, and the SAME agent's Stop fires again.
  let role = 'work';
  const server = await startStub({
    [itemId]: (req, res) => {
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify(
          gateOk({ itemId, role, missing: role === 'work' ? ['implementation-notes'] : ['review-checklist'] }).body,
        ),
      );
    },
  });
  try {
    const apiUrl = `http://127.0.0.1:${server.address().port}`;

    // First Stop: item still in work, missing notes -> blocks.
    const first = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, apiUrl);
    const firstOut = JSON.parse(first.stdout);
    assert.equal(firstOut.decision, 'block');
    assert.ok(firstOut.reason.includes('implementation-notes'), firstOut.reason);

    // B2 regression: the block path must carry enteredRoles through into the rewritten marker,
    // not drop it — otherwise the second Stop below would fall back to role-agnostic behavior
    // and block again on the review-phase notes it never owned.
    const afterFirst = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(afterFirst.enteredRoles, { [itemId]: 'work' });
    assert.equal(afterFirst.blocks, 1);

    // Item now moves to review (simulating another seat advancing it).
    role = 'review';

    // Second Stop: same agent, same marker. gate.role ("review") now differs from the recorded
    // enteredRole ("work"), so this item must be skipped entirely rather than blocking on
    // review-checklist.
    const second = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, apiUrl);
    assert.equal(second.status, 0);
    assert.equal(second.stdout.trim(), '{}');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S12: headless iteration with an existing marker and a missing-notes gate -> {} with zero fetches', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s12-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '5c5c5c5c-0000-0000-0000-0000000000ab';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  let hitCount = 0;
  const server = await startStub({
    [itemId]: (req, res) => {
      hitCount++;
      const { status = 200, body = {} } = gateOk({ itemId, role: 'work', missing: ['implementation-notes'] });
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    },
  });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId },
      tempDir,
      `http://127.0.0.1:${server.address().port}`,
      'headless-iteration'
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(hitCount, 0, 'expected zero gate fetches in headless mode');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── T6: the task-orchestrator-mod plugin already guarded this stop ─────────────────────────────

test('to_mod_active on stdin: emits {} without blocking or fetching, marker untouched', async () => {
  const tempDir = freshTempDir();
  const sessionId = `mod-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = '4a468f6c-9434-4cbf-97e8-b6c546c1b199';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({ [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'] }) });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId, to_mod_active: true }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(readMarker(tempDir, sessionId, agentId).blocks, 0);
    // Control: without the flag the same setup blocks.
    const control = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(JSON.parse(control.stdout).decision, 'block');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ══ 0bbdc4cc (P10): the gate is read from the server over POST /api/v1/ops/items_read ══════════

function writeBootstrapConfig(dir) {
  const cfgDir = join(dir, '.taskorchestrator');
  mkdirSync(cfgDir, { recursive: true });
  writeFileSync(join(cfgDir, 'config.yaml'), 'project:\n  rootId: 11111111-1111-1111-1111-111111111111\n', 'utf-8');
}

test('S3: the gate read is exactly POST /api/v1/ops/items_read with body {select:"context", itemId}, once per recorded item', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s3req-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemA = 'f1f1f1f1-0000-0000-0000-00000000000a';
  const itemB = 'f1f1f1f1-0000-0000-0000-00000000000b';
  seedMarker(tempDir, sessionId, agentId, { items: [itemA, itemB], blocks: 0 });
  const server = await startStub({
    [itemA]: gateOk({ itemId: itemA, role: 'work', missing: ['implementation-notes'] }),
    [itemB]: gateOk({ itemId: itemB, role: 'work', missing: [] }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(JSON.parse(res.stdout).decision, 'block');
    assert.equal(server.requests.length, 2);
    for (const r of server.requests) {
      assert.equal(r.method, 'POST');
      assert.equal(r.url, '/api/v1/ops/items_read');
    }
    const bodies = server.requests.map((r) => JSON.parse(r.body)).sort((x, y) => x.itemId.localeCompare(y.itemId));
    assert.deepEqual(bodies, [
      { select: 'context', itemId: itemA },
      { select: 'context', itemId: itemB },
    ]);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('D2: the gate is keyed by the marker id that was requested, not by the id the view returns', async () => {
  const tempDir = freshTempDir();
  const sessionId = `d2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f2f2f2f2-0000-0000-0000-000000000001';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  // the compact view id is a short form; the block reason must still name the MARKER id's uuid8
  const server = await startStub({
    [itemId]: gateOk({ itemId: 'zz-compact-id', role: 'work', missing: ['implementation-notes'], title: 'Keyed by request' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes(itemId.slice(0, 8)), out.reason);
    assert.ok(out.reason.includes('Keyed by request'), out.reason);
    assert.ok(!out.reason.includes('zz-compact-id'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3: the block reason keeps the sentence shape and names no retired 3.x tool', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s3sent-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f3f3f3f3-0000-0000-0000-000000000001';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes', 'session-tracking'], title: 'Sentence check' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    const prefix = `Item ${itemId.slice(0, 8)} "Sentence check" is in work with required notes still missing: `;
    assert.ok(out.reason.includes(prefix), out.reason);
    assert.ok(out.reason.includes('implementation-notes') && out.reason.includes('session-tracking'), out.reason);
    for (const retired of ['manage_notes', 'advance_item', 'get_context']) {
      assert.ok(!out.reason.includes(retired), `${retired} in: ${out.reason}`);
    }
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S10: a non-ok context envelope (forbidden, not_found, unauthenticated, unavailable, internal) never blocks that item', async () => {
  const cases = [
    gateErr(403, 'forbidden'),
    gateErr(404, 'not_found'),
    gateErr(401, 'unauthenticated'),
    gateErr(503, 'unavailable', 'shedding'),
    gateErr(500, 'internal'),
    gateErr(200, 'internal'), // ok:false even with HTTP 200
  ];
  for (const reply of cases) {
    const tempDir = freshTempDir();
    const sessionId = `s10-${randomUUID()}`;
    const agentId = 'agent-1';
    const itemId = 'f4f4f4f4-0000-0000-0000-000000000001';
    seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
    // The legacy GET route WOULD block if it were ever consulted as a fallback.
    const server = await startStub(
      { [itemId]: reply },
      { legacyGate: { [itemId]: { status: 200, body: { itemId, title: 'legacy', role: 'work', gateStatus: { canAdvance: false, missing: ['implementation-notes'] } } } } },
    );
    try {
      const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
      assert.equal(res.status, 0, reply.body.error.code);
      assert.equal(res.stdout.trim(), '{}', `${reply.status} ${reply.body.error.code}: ${res.stdout}`);
      assert.ok(server.requests.every((r) => !r.url.includes('/gate')), JSON.stringify(server.requests));
    } finally {
      await stopStub(server);
      rmSync(tempDir, { recursive: true, force: true });
    }
  }
});

test('S10: one item answering with an error envelope does not hide the other item that blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s10mix-${randomUUID()}`;
  const agentId = 'agent-1';
  const okItem = 'f5f5f5f5-0000-0000-0000-000000000001';
  const badItem = 'f6f6f6f6-0000-0000-0000-000000000002';
  seedMarker(tempDir, sessionId, agentId, { items: [badItem, okItem], blocks: 0 });
  const server = await startStub({
    [badItem]: gateErr(404, 'not_found'),
    [okItem]: gateOk({ itemId: okItem, role: 'work', missing: ['implementation-notes'], title: 'Still blocking' }),
  });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    const out = JSON.parse(res.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes(okItem.slice(0, 8)), out.reason);
    assert.ok(!out.reason.includes(badItem.slice(0, 8)), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S9: a server that only serves the 3.x GET /items/{id}/gate route -> {}, the GET is never requested, the marker is cleared', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s9-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f7f7f7f7-0000-0000-0000-000000000001';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub(
    {}, // ops route: 404 not_found envelope for every id
    { legacyGate: { [itemId]: { status: 200, body: { itemId, title: 'legacy', role: 'work', gateStatus: { canAdvance: false, missing: ['implementation-notes'] } } } } },
  );
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.ok(server.requests.length >= 1, 'the ops route must have been tried');
    assert.ok(server.requests.every((r) => r.method === 'POST' && r.url === '/api/v1/ops/items_read'), JSON.stringify(server.requests));
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.items, []);
    assert.equal(marker.blocks, 0);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S9 probe: a 3.x-shaped body ({success}) from the ops route is not a gate and never blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s9legacy-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f7f7f7f7-0000-0000-0000-000000000002';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({
    [itemId]: {
      status: 200,
      body: { success: true, data: { item: { id: itemId, title: 't', role: 'work' }, gateStatus: { canAdvance: false, missing: ['implementation-notes'] } } },
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

test('S13: agent_type workflow-subagent -> {} before any fetch, even with a blocking marker', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13wf-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'f8f8f8f8-0000-0000-0000-000000000001';
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({ [itemId]: gateOk({ itemId, role: 'work', missing: ['implementation-notes'] }) });
  try {
    const res = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'workflow-subagent' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(server.requests.length, 0, 'no gate request for a workflow subagent');
    // Control: any other agent_type with the same marker and gate does block.
    const control = await runHook(
      { session_id: sessionId, agent_id: agentId, agent_type: 'task-orchestrator:implementer' },
      tempDir,
      `http://127.0.0.1:${server.address().port}`,
    );
    assert.equal(JSON.parse(control.stdout).decision, 'block');
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S12: located config + no API URL -> exactly the inert systemMessage once per session, then {}; exit 0 every time', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s12-inert-${randomUUID()}`;
  const agentId = 'agent-1';
  writeBootstrapConfig(tempDir);
  seedMarker(tempDir, sessionId, agentId, { items: ['f9f9f9f9-0000-0000-0000-000000000001'], blocks: 0 });
  try {
    const first = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, undefined, undefined, { AGENT_CONFIG_DIR: tempDir });
    assert.equal(first.status, 0);
    assert.deepEqual(JSON.parse(first.stdout), { systemMessage: INERT_PHASE_GUARD });
    const second = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, undefined, undefined, { AGENT_CONFIG_DIR: tempDir });
    assert.equal(second.status, 0);
    assert.equal(second.stdout.trim(), '{}');
    // a different session gets its own inert line
    const otherSession = `s12-inert-other-${randomUUID()}`;
    seedMarker(tempDir, otherSession, agentId, { items: ['f9f9f9f9-0000-0000-0000-000000000002'], blocks: 0 });
    const third = await runHook({ session_id: otherSession, agent_id: agentId }, tempDir, undefined, undefined, { AGENT_CONFIG_DIR: tempDir });
    assert.equal(third.status, 0);
    assert.deepEqual(JSON.parse(third.stdout), { systemMessage: INERT_PHASE_GUARD });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S12: no config located + no API URL stays silent ({}) with no inert line', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s12-noconfig-${randomUUID()}`;
  const agentId = 'agent-1';
  seedMarker(tempDir, sessionId, agentId, { items: ['fafafafa-0000-0000-0000-000000000001'], blocks: 0 });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, undefined);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S12: with an API URL set the inert line is never emitted (config located, gate read normally)', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s12-configured-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'fbfbfbfb-0000-0000-0000-000000000001';
  writeBootstrapConfig(tempDir);
  seedMarker(tempDir, sessionId, agentId, { items: [itemId], blocks: 0 });
  const server = await startStub({ [itemId]: gateOk({ itemId, role: 'work', missing: [] }) });
  try {
    const res = await runHook({ session_id: sessionId, agent_id: agentId }, tempDir, `http://127.0.0.1:${server.address().port}`, undefined, { AGENT_CONFIG_DIR: tempDir });
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.equal(server.requests.length, 1);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});
