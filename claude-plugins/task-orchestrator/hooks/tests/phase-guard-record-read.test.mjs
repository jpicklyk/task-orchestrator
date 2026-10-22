// Coverage for the read path of phase-guard-record.mjs (b15ac560): a phase-owner seat that is
// already in its phase never sends `start`, so its first item-mode context read (PostToolUse on
// items_read, select 'context') is what records "entered role" for phase-guard.mjs.
// Test author actor id: test-author:b15ac560:r-202610171326-b15ac560.
//
// Oracles (frozen in the item's task-scope / test-plan notes, never read from the hook source):
//   O1 = task-scope AC1-AC7 (recording rule, first-read-only, phase-owner gating, marker flag).
//   O2 = subagent-start.mjs:80-82 (an in-phase seat reads context and never starts; a gate_blocked
//        only means a required note is missing).
//   O3 = TransitionPolicy.kt:273-279 (a gate_blocked's detail.role is the item's role BEFORE the
//        transition, not a role the seat entered).
//   O4 = SEAT_ROLE (implementer -> work, reviewer -> review).
// Every scenario is EXISTING-SURFACE. Scenarios are labelled S1..S15 as in the frozen test-plan;
// S16 is an addition for AC5 (readPhaseGuardMarker strictness).
//
// Harness: standalone node hook scripts fed hook-input JSON on stdin. State isolation as in
// phase-guard-record.test.mjs: TEMP/TMP/TMPDIR are redirected to a fresh mkdtempSync dir per child,
// and the marker is read back through the exported phaseGuardMarkerPath/readPhaseGuardMarker. The
// record hook does no network I/O (spawnSync is safe); the guard hook fetches a stub gate over HTTP
// and so runs through async spawn (spawnSync would starve the stub server's event loop).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { phaseGuardMarkerPath, readPhaseGuardMarker, writePhaseGuardMarker } from '../phase-guard-record.mjs';
import { orchestratorToolMatcher } from '../registration.mjs';

const RECORD_HOOK = fileURLToPath(new URL('../phase-guard-record.mjs', import.meta.url));
const GUARD_HOOK = fileURLToPath(new URL('../phase-guard.mjs', import.meta.url));
const HOOKS_CONFIG_PATH = fileURLToPath(new URL('../hooks-config.json', import.meta.url));
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';
const READ_TOOL = 'mcp__mcp-task-orchestrator__items_read';
const ADVANCE_TOOL = 'mcp__mcp-task-orchestrator__advance';
const IMPL = 'task-orchestrator:implementer';

const U1 = 'c0ffee00-1111-4111-8111-000000000001';
const U2 = 'c0ffee00-2222-4222-8222-000000000002';
const U3 = 'c0ffee00-3333-4333-8333-000000000003';

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-read-'));
}

function childEnv(tempDir, apiUrl, modeOverride) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
  delete env.AGENT_CONFIG_DIR;
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_MODE;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  if (modeOverride) env.TASK_ORCHESTRATOR_MODE = modeOverride;
  return env;
}

function spawnRecord(payload, tempDir, apiUrl = UNREACHABLE_API_URL, modeOverride) {
  return spawnSync(process.execPath, [RECORD_HOOK], { input: JSON.stringify(payload), env: childEnv(tempDir, apiUrl, modeOverride), cwd: tempDir, encoding: 'utf-8' });
}

function runGuard(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [GUARD_HOOK], { env: childEnv(tempDir, apiUrl), cwd: tempDir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

function markerFor(tempDir, sessionId, agentId) {
  const saved = { TEMP: process.env.TEMP, TMP: process.env.TMP, TMPDIR: process.env.TMPDIR };
  process.env.TEMP = tempDir;
  process.env.TMP = tempDir;
  process.env.TMPDIR = tempDir;
  try {
    return phaseGuardMarkerPath(sessionId, agentId);
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

const readMarker = (tempDir, sessionId, agentId) => readPhaseGuardMarker(markerFor(tempDir, sessionId, agentId));
const markerExists = (tempDir, sessionId, agentId) => existsSync(markerFor(tempDir, sessionId, agentId));

function seedMarker(tempDir, sessionId, agentId, marker) {
  const saved = { TEMP: process.env.TEMP, TMP: process.env.TMP, TMPDIR: process.env.TMPDIR };
  process.env.TEMP = tempDir;
  process.env.TMP = tempDir;
  process.env.TMPDIR = tempDir;
  try {
    writePhaseGuardMarker(phaseGuardMarkerPath(sessionId, agentId), marker);
  } finally {
    for (const [k, v] of Object.entries(saved)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
  }
}

// ── fixtures ──────────────────────────────────────────────────────────────────────────────────

/** items_read context response: 4.0 envelope. data.item.id is the 8-hex short id of a compact view
 * (api-reference.md:446), so the hook can only learn the full id from the request. */
function contextResponse(role, { shortId = 'c0ffee00' } = {}) {
  return {
    content: [{ type: 'text', text: 'ok' }],
    structuredContent: {
      ok: true,
      data: {
        item: { id: shortId, title: 'Widget frobnicator', role },
        gateStatus: { canAdvance: false, missing: ['implementation-notes'], violations: [] },
      },
      meta: { reqId: 'req00001' },
    },
  };
}

/** Full hook stdin for one items_read call. */
function readCall(sessionId, { agentId = 'agent-1', agentType = IMPL, itemId = U1, role = 'work', inputExtra = {}, input, response, rest = {} } = {}) {
  return {
    session_id: sessionId,
    ...(agentId ? { agent_id: agentId } : {}),
    ...(agentType == null ? {} : { agent_type: agentType }),
    tool_name: READ_TOOL,
    tool_input: input ?? { select: 'context', itemId, ...inputExtra },
    tool_response: response ?? contextResponse(role),
    ...rest,
  };
}

function advanceCall(sessionId, itemId, newRole = 'work', agentId = 'agent-1') {
  return {
    session_id: sessionId,
    agent_id: agentId,
    tool_name: ADVANCE_TOOL,
    tool_input: { ops: [{ op: 'transition', itemId, trigger: 'start' }] },
    tool_response: {
      content: [{ type: 'text', text: 'ok' }],
      structuredContent: { ok: true, data: { results: [{ ok: true, itemId, newRole }], summary: { ok: 1, failed: 0 } }, meta: { reqId: 'req00002' } },
    },
  };
}

function withTemp(name, fn) {
  const tempDir = freshTempDir();
  const sessionId = `${name}-${randomUUID()}`;
  try {
    return fn(tempDir, sessionId);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

// ── S1 / S2: the first read of an item in the seat's own phase records it (AC2, O4) ───────────

test('S1: an implementer reading item U in work records U with entered role work', () => {
  withTemp('s1', (tempDir, sessionId) => {
    const res = spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
    assert.equal(marker.blocks, 0);
  });
});

test('S2: a reviewer reading item U in review records U with entered role review (bare and plugin-qualified agent types)', () => {
  for (const [agentType, role] of [['reviewer', 'review'], ['task-orchestrator:reviewer', 'review'], ['implementer', 'work']]) {
    withTemp('s2', (tempDir, sessionId) => {
      const res = spawnRecord(readCall(sessionId, { agentType, itemId: U1, role }), tempDir);
      assert.equal(res.status, 0, agentType);
      assert.equal(res.stdout.trim(), '{}', agentType);
      const marker = readMarker(tempDir, sessionId, 'agent-1');
      assert.deepEqual(marker.items, [U1], agentType);
      assert.deepEqual(marker.enteredRoles, { [U1]: role }, agentType);
    });
  }
});

// ── S3 chained: the read record feeds the Stop guard (AC2, O2) ────────────────────────────────

/** Gate stub: POST /api/v1/ops/items_read -> the 4.0 context envelope for the requested itemId. */
function startGateStub(role, missing, requests) {
  return new Promise((resolveListen) => {
    const server = createServer((req, res) => {
      let raw = '';
      req.on('data', (d) => {
        raw += d;
      });
      req.on('end', () => {
        requests.push({ method: req.method, url: req.url, body: raw });
        let body = {};
        try {
          body = JSON.parse(raw);
        } catch {
          /* leave empty */
        }
        res.writeHead(200, { 'Content-Type': 'application/json' });
        if (req.method === 'POST' && req.url === '/api/v1/ops/items_read') {
          res.end(
            JSON.stringify({
              ok: true,
              data: { item: { id: body.itemId, title: 'Widget frobnicator', role }, gateStatus: { canAdvance: false, missing, violations: [] } },
              meta: { reqId: 'req00001' },
            }),
          );
        } else {
          res.end(JSON.stringify({ ok: false, error: { code: 'not_found', kind: 'permanent', message: 'no such route' } }));
        }
      });
    });
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

const stopStub = (server) => new Promise((res) => server.close(res));

test('S3 chained: an in-phase implementer reads U (work) then Stops with a missing note -> the guard blocks naming the note', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s3-${randomUUID()}`;
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    const recordRes = spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    assert.equal(recordRes.status, 0);
    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: 'agent-1', agent_type: IMPL }, tempDir, apiUrl);
    assert.equal(guardRes.status, 0);
    const out = JSON.parse(guardRes.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
    assert.ok(out.reason.includes(U1.slice(0, 8)), out.reason);
    assert.ok(requests.length >= 1);
    assert.ok(requests.every((r) => r.method === 'POST' && r.url === '/api/v1/ops/items_read'), JSON.stringify(requests));
    assert.deepEqual(JSON.parse(requests[0].body), { select: 'context', itemId: U1 });
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3 control: the same Stop with no recorded read emits {} and makes zero gate requests', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s3c-${randomUUID()}`;
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: 'agent-1', agent_type: IMPL }, tempDir, apiUrl);
    assert.equal(guardRes.status, 0);
    assert.deepEqual(JSON.parse(guardRes.stdout), {});
    assert.equal(requests.length, 0);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3 chained: a sibling read after the first is not recorded, so the guard fetches only the first item', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s3d-${randomUUID()}`;
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir); // e.g. the parent, also in work
    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: 'agent-1', agent_type: IMPL }, tempDir, apiUrl);
    assert.equal(JSON.parse(guardRes.stdout).decision, 'block');
    assert.ok(requests.length >= 1);
    assert.ok(requests.every((r) => JSON.parse(r.body).itemId === U1), JSON.stringify(requests));
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S4: gate_blocked never records (AC1) -- unit and mixed-batch cases live in phase-guard-record*.test.mjs;
// the chained half (S5) is in phase-guard-chained.test.mjs. ──────────────────────────────────────

// ── S6: a role that is not the seat's own phase records nothing (AC2) ─────────────────────────

test('S6: no record when the item role differs from the seat-owned role', () => {
  const cases = [
    ['implementer', 'queue'],
    ['implementer', 'review'],
    ['implementer', 'terminal'],
    ['implementer', 'blocked'],
    ['reviewer', 'work'],
    ['reviewer', 'queue'],
    ['task-orchestrator:reviewer', 'work'],
  ];
  for (const [agentType, role] of cases) {
    withTemp('s6', (tempDir, sessionId) => {
      const res = spawnRecord(readCall(sessionId, { agentType, itemId: U1, role }), tempDir);
      assert.equal(res.status, 0, `${agentType}/${role}`);
      assert.equal(res.stdout.trim(), '{}', `${agentType}/${role}`);
      const marker = readMarker(tempDir, sessionId, 'agent-1');
      assert.deepEqual(marker.items, [], `${agentType}/${role}`);
      assert.deepEqual(marker.enteredRoles, {}, `${agentType}/${role}`);
      // Control: the same agent type reading an item in its OWN phase (another agent_id, so its first
      // read is still unspent) records, so the mismatch -- not the fixture -- is what recorded nothing.
      const owned = agentType.endsWith('reviewer') ? 'review' : 'work';
      spawnRecord(readCall(sessionId, { agentId: 'agent-ctl', agentType, itemId: U1, role: owned }), tempDir);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-ctl').enteredRoles, { [U1]: owned }, `${agentType}/${role} control`);
    });
  }
});

// ── S7: only phase-owner agent types are recorded (AC4) ───────────────────────────────────────

test('S7: a non-phase-owner agent_type writes no marker file on the read path', () => {
  const types = [null, '', 'general-purpose', 'Explore', 'test-author', 'x:test-author', 'task-orchestrator:implementer-helper', 'task-orchestrator:implementer2'];
  for (const agentType of types) {
    withTemp('s7', (tempDir, sessionId) => {
      const label = JSON.stringify(agentType);
      const res = spawnRecord(readCall(sessionId, { agentType, itemId: U1, role: 'work' }), tempDir);
      assert.equal(res.status, 0, label);
      assert.equal(res.stdout.trim(), '{}', label);
      assert.equal(markerExists(tempDir, sessionId, 'agent-1'), false, label);
      // Control: the same call as a phase owner does write a marker and record.
      spawnRecord(readCall(sessionId, { agentType: IMPL, itemId: U1, role: 'work' }), tempDir);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U1], label);
    });
  }
});

test('S7 probe: a mixed-case phase-owner type (task-orchestrator:Implementer) is not a phase owner', () => {
  withTemp('s7b', (tempDir, sessionId) => {
    spawnRecord(readCall(sessionId, { agentType: 'task-orchestrator:Implementer', itemId: U1, role: 'work' }), tempDir);
    assert.equal(markerExists(tempDir, sessionId, 'agent-1'), false);
  });
});

// ── S8: the existing early exits still apply on the read path (AC2) ───────────────────────────

test('S8: no record with a non-empty seatToken, no agent_id, no API URL, headless mode or to_mod_active', () => {
  const variants = [
    ['seatToken', { inputExtra: { seatToken: 'seat-token-abc' } }, {}],
    ['no agent_id', { agentId: null }, {}],
    ['no API URL', {}, { apiUrl: null }],
    ['headless', {}, { mode: 'headless-iteration' }],
    ['to_mod_active', { rest: { to_mod_active: true } }, {}],
  ];
  for (const [label, callOpts, runOpts] of variants) {
    withTemp('s8', (tempDir, sessionId) => {
      const apiUrl = 'apiUrl' in runOpts ? runOpts.apiUrl : UNREACHABLE_API_URL;
      const res = spawnRecord(readCall(sessionId, { itemId: U1, role: 'work', ...callOpts }), tempDir, apiUrl, runOpts.mode);
      assert.equal(res.status, 0, label);
      assert.equal(res.stdout.trim(), '{}', label);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [], label);
      assert.deepEqual(readMarker(tempDir, sessionId, 'unknown').items, [], label);
      // Control: the unmodified call records, so the variant (not the fixture) is what suppressed it.
      spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U1], label);
    });
  }
});

test('S8 probe: an empty, null or non-string seatToken does not exclude the read', () => {
  const tokens = ['', null, 5];
  tokens.forEach((seatToken, i) => {
    withTemp(`s8t${i}`, (tempDir, sessionId) => {
      spawnRecord(readCall(sessionId, { itemId: U1, role: 'work', inputExtra: { seatToken } }), tempDir);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U1], JSON.stringify(seatToken));
    });
  });
});

// ── S9: requests that are not an ok:true item-mode context read with a full UUID record nothing (AC2, AC3) ──

test('S9: ok:false, select get/schema, a context read without itemId and an 8-hex itemId record nothing', () => {
  const notFound = { content: [{ type: 'text', text: 'not_found' }], isError: true, structuredContent: { ok: false, error: { code: 'not_found', kind: 'permanent', message: 'no such item' } } };
  const cases = [
    ['ok:false not_found', { response: notFound }],
    ['select get', { input: { select: 'get', itemId: U1 } }],
    ['select schema', { input: { select: 'schema', itemId: U1 } }],
    ['no itemId', { input: { select: 'context' } }],
    ['empty itemId', { input: { select: 'context', itemId: '' } }],
    ['8-hex itemId', { itemId: 'c0ffee00' }],
    ['numeric itemId', { input: { select: 'context', itemId: 12345678 } }],
  ];
  for (const [label, opts] of cases) {
    withTemp('s9', (tempDir, sessionId) => {
      const res = spawnRecord(readCall(sessionId, { itemId: U1, role: 'work', ...opts }), tempDir);
      assert.equal(res.status, 0, label);
      assert.equal(res.stdout.trim(), '{}', label);
      const marker = readMarker(tempDir, sessionId, 'agent-1');
      assert.deepEqual(marker.items, [], label);
      assert.deepEqual(marker.enteredRoles, {}, label);
      // None of these is a counted "first read": a later valid read still records.
      spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U2], label);
    });
  }
});

test('S9 probe: select "Context" (wrong case), role "WORK" (wrong case) and an unwrapped item role record nothing', () => {
  const upperRole = readCall('placeholder', { itemId: U1, role: 'WORK' });
  const cases = [
    ['select Context', { input: { select: 'Context', itemId: U1 } }],
    ['role WORK', { response: upperRole.tool_response }],
    ['no data.item', { response: { structuredContent: { ok: true, data: {} } } }],
    ['role not a string', { response: { structuredContent: { ok: true, data: { item: { id: 'c0ffee00', role: 7 } } } } }],
  ];
  for (const [label, opts] of cases) {
    withTemp('s9p', (tempDir, sessionId) => {
      const res = spawnRecord(readCall(sessionId, { itemId: U1, role: 'work', ...opts }), tempDir);
      assert.equal(res.status, 0, label);
      assert.equal(res.stdout.trim(), '{}', label);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [], label);
    });
  }
});

// ── S10 - S13: only the first read counts (AC3, AC5) ──────────────────────────────────────────

test('S10: a first read of U1 in queue is the first read -> a later read of U2 in work is not recorded', () => {
  withTemp('s10', (tempDir, sessionId) => {
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'queue' }), tempDir);
    spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
    // The advance path is unaffected by the read marker: an ok:true advance still records.
    spawnRecord(advanceCall(sessionId, U3, 'work'), tempDir);
    const after = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(after.items, [U3]);
    assert.deepEqual(after.enteredRoles, { [U3]: 'work' });
  });
});

test('S11: two reads in work: only the first (U1) is recorded', () => {
  withTemp('s11', (tempDir, sessionId) => {
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
  });
});

test('S11 probe: replaying the identical read leaves the marker unchanged (one entry, same role)', () => {
  withTemp('s11b', (tempDir, sessionId) => {
    for (let i = 0; i < 3; i++) spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
  });
});

test('S12: an ok:true advance records U1, then a read of U2 in work is not recorded', () => {
  withTemp('s12', (tempDir, sessionId) => {
    spawnRecord(advanceCall(sessionId, U1, 'work'), tempDir);
    spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
  });
});

test('S12 probe: reads are tracked per agent -- another agent_id in the same session still records its own first read', () => {
  withTemp('s12b', (tempDir, sessionId) => {
    spawnRecord(readCall(sessionId, { agentId: 'agent-A', itemId: U1, role: 'work' }), tempDir);
    spawnRecord(readCall(sessionId, { agentId: 'agent-B', itemId: U2, role: 'work' }), tempDir);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-A').items, [U1]);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-B').items, [U2]);
  });
});

test('S13: a read, then a guard Stop that blocks, then a read of U2 in work -> U2 is still not recorded', async () => {
  const tempDir = freshTempDir();
  const sessionId = `s13-${randomUUID()}`;
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: 'agent-1', agent_type: IMPL }, tempDir, apiUrl);
    assert.equal(JSON.parse(guardRes.stdout).decision, 'block');
    // The block rewrote the marker (blocks + 1); the read marker must have survived that rewrite.
    assert.equal(readMarker(tempDir, sessionId, 'agent-1').blocks, 1);

    spawnRecord(readCall(sessionId, { itemId: U2, role: 'work' }), tempDir);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
    assert.equal(marker.blocks, 1);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S14: the key is the requested id, never the short id in the response (AC2, api-reference.md:446) ──

test('S14: a response item.id of c0ffee00 is ignored -- the marker key is the requested full UUID', () => {
  withTemp('s14', (tempDir, sessionId) => {
    const res = spawnRecord(readCall(sessionId, { itemId: U1, response: contextResponse('work', { shortId: 'c0ffee00' }) }), tempDir);
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [U1]);
    assert.deepEqual(Object.keys(marker.enteredRoles), [U1]);
    assert.deepEqual(marker.enteredRoles, { [U1]: 'work' });
  });
});

test('S14 probe: a response whose item.id is a different full UUID still keys on the requested id', () => {
  withTemp('s14b', (tempDir, sessionId) => {
    spawnRecord(readCall(sessionId, { itemId: U1, response: contextResponse('work', { shortId: U2 }) }), tempDir);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U1]);
  });
});

// ── Response shapes and fail-open (AC6) ───────────────────────────────────────────────────────

test('probe: the envelope delivered only as a JSON text block (content[0].text) records identically', () => {
  withTemp('textblock', (tempDir, sessionId) => {
    const envelope = { ok: true, data: { item: { id: 'c0ffee00', role: 'work' } } };
    const res = spawnRecord(readCall(sessionId, { itemId: U1, response: { content: [{ type: 'text', text: JSON.stringify(envelope) }] } }), tempDir);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').enteredRoles, { [U1]: 'work' });
  });
});

test('AC6 probe: a 3.x {success} read payload emits {} (no legacy-shape warning) and records nothing', () => {
  withTemp('legacy', (tempDir, sessionId) => {
    const legacy = {
      content: [{ type: 'text', text: 'ok' }],
      structuredContent: { success: true, message: 'ok', data: { item: { id: U1, role: 'work' } } },
    };
    for (let i = 0; i < 2; i++) {
      const res = spawnRecord(readCall(sessionId, { itemId: U1, response: legacy }), tempDir);
      assert.equal(res.status, 0);
      assert.equal(res.stdout.trim(), '{}');
    }
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  });
});

test('AC6: malformed or unrecognized read responses fail open: {} and exit 0, nothing recorded', () => {
  const responses = [undefined, null, 'garbage', { totally: 'unexpected shape' }, { content: [{ type: 'text', text: '{not json' }] }, { structuredContent: { ok: true } }];
  responses.forEach((response, i) => {
    withTemp(`failopen${i}`, (tempDir, sessionId) => {
      const payload = readCall(sessionId, { itemId: U1, role: 'work' });
      payload.tool_response = response;
      const res = spawnRecord(payload, tempDir);
      assert.equal(res.status, 0, String(i));
      assert.equal(res.stdout.trim(), '{}', String(i));
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [], String(i));
    });
  });
});

test('AC6: the read path never emits a systemMessage, whether it records or not', () => {
  withTemp('quiet', (tempDir, sessionId) => {
    for (const role of ['work', 'queue', 'work']) {
      const res = spawnRecord(readCall(sessionId, { itemId: U1, role }), tempDir);
      assert.equal(res.status, 0);
      assert.deepEqual(JSON.parse(res.stdout), {});
    }
  });
});

// ── S16: readPhaseGuardMarker strictness on contextReadSeen (AC5) ─────────────────────────────

test('S16: only a stored contextReadSeen === true counts as seen; a truthy non-boolean does not', () => {
  withTemp('s16', (tempDir, sessionId) => {
    // Missing marker file: the flag reads false.
    assert.equal(readMarker(tempDir, sessionId, 'agent-1').contextReadSeen, false);
    for (const stored of ['true', 1, 'yes', {}, null]) {
      seedMarker(tempDir, sessionId, 'agent-S', { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: stored });
      assert.equal(readMarker(tempDir, sessionId, 'agent-S').contextReadSeen, false, JSON.stringify(stored));
    }
    seedMarker(tempDir, sessionId, 'agent-S', { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: true });
    assert.equal(readMarker(tempDir, sessionId, 'agent-S').contextReadSeen, true);
  });
});

test('S16: a marker with a truthy non-boolean contextReadSeen still records the next read; a true one does not', () => {
  withTemp('s16b', (tempDir, sessionId) => {
    seedMarker(tempDir, sessionId, 'agent-1', { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: 'true' });
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [U1]);
  });
  withTemp('s16c', (tempDir, sessionId) => {
    seedMarker(tempDir, sessionId, 'agent-1', { items: [], blocks: 0, enteredRoles: {}, contextReadSeen: true });
    spawnRecord(readCall(sessionId, { itemId: U1, role: 'work' }), tempDir);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  });
});

// ── S15: registration (D4) ────────────────────────────────────────────────────────────────────

test('S15: phase-guard-record.mjs is registered once, on advance and items_read; dispatch-hint stays on advance only', () => {
  const config = JSON.parse(readFileSync(HOOKS_CONFIG_PATH, 'utf8'));
  const hits = [];
  const dispatchHits = [];
  for (const entries of Object.values(config.hooks)) {
    for (const entry of entries) {
      for (const hook of entry.hooks) {
        if (hook.command.includes('phase-guard-record.mjs')) hits.push(entry);
        if (hook.command.includes('dispatch-hint.mjs')) dispatchHits.push(entry);
      }
    }
  }
  assert.equal(hits.length, 1, 'exactly one phase-guard-record entry');
  assert.equal(hits[0].matcher, orchestratorToolMatcher(['advance', 'items_read']));
  const re = new RegExp(hits[0].matcher);
  assert.ok(re.test('mcp__mcp-task-orchestrator__items_read'));
  assert.ok(re.test('mcp__mcp-task-orchestrator__advance'));
  assert.ok(!re.test('mcp__mcp-task-orchestrator__items_write'));
  assert.ok(!re.test('mcp__mcp-task-orchestrator__items_read_all'));
  assert.equal(dispatchHits.length, 1);
  assert.equal(dispatchHits[0].matcher, orchestratorToolMatcher(['advance']));
  assert.ok(!new RegExp(dispatchHits[0].matcher).test('mcp__mcp-task-orchestrator__items_read'));
});

test('S15: the record script source still contains the literal seatToken (run-planner-lib probes for it, AC7)', () => {
  assert.ok(readFileSync(RECORD_HOOK, 'utf8').includes('seatToken'));
});
