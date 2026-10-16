// Chained coverage (036420aa B1/B2, re-expressed on the 4.0 envelope by 0bbdc4cc S14): runs
// phase-guard-record.mjs (PostToolUse:advance) against a REALISTIC advance tool_response, then
// feeds the marker it writes into phase-guard.mjs (SubagentStop) against a stubbed gate
// (POST /api/v1/ops/items_read, select context), asserting the end-to-end block/no-block outcome.
// Neither unit test file exercises this seam: phase-guard-record.test.mjs never invokes
// phase-guard.mjs, and phase-guard.test.mjs seeds its marker directly via writePhaseGuardMarker
// rather than producing it from phase-guard-record.mjs's own extraction logic.
//
// The B1 regression this guards: a gate_blocked ("already in phase") result carries the item's
// CURRENT role (4.0: error.detail.role; 3.x: previousRole), not the phase the blocked transition
// tried to reach. Recording the wrong role makes phase-guard.mjs's entered-role gate
// (gate.role !== enteredRole) compare the item's real role ("work") with a bogus recorded one
// and silently skip the item -- a false negative.
//
// Oracle: 0bbdc4cc task-scope AC3 (detail.role is the phase the agent already sits in) and AC4
// (gate = res.data of the items_read context call, block sentence unchanged).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { createServer } from 'node:http';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';

const RECORD_HOOK = fileURLToPath(new URL('../phase-guard-record.mjs', import.meta.url));
const GUARD_HOOK = fileURLToPath(new URL('../phase-guard.mjs', import.meta.url));
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';
const ADVANCE_TOOL = 'mcp__mcp-task-orchestrator__advance';

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-chained-'));
}

/** Runs phase-guard-record.mjs synchronously (it does no network I/O). */
function runRecord(payload, tempDir, apiUrl) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
  delete env.AGENT_CONFIG_DIR;
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_MODE;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return spawnSync(process.execPath, [RECORD_HOOK], {
    input: JSON.stringify(payload),
    env,
    cwd: tempDir,
    encoding: 'utf-8',
  });
}

/** Runs phase-guard.mjs asynchronously (it fetches the stub gate server over HTTP, which shares
 * this process's event loop -- spawnSync would starve it, per phase-guard.test.mjs's own harness
 * rule). */
function runGuard(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
    delete env.AGENT_CONFIG_DIR;
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [GUARD_HOOK], { env, cwd: tempDir });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

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
              data: {
                item: { id: body.itemId, title: 'Widget frobnicator', role },
                gateStatus: { canAdvance: false, missing, violations: [] },
              },
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

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

test('chained: ok:true advance -> recorded newRole "work" -> gate still work with missing notes -> blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `chain-applied-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c0ffee00-0000-0000-0000-000000000001';
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    const recordRes = runRecord(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_name: ADVANCE_TOOL,
        tool_input: { ops: [{ op: 'transition', itemId, trigger: 'start' }] },
        tool_response: {
          content: [{ type: 'text', text: 'ok' }],
          structuredContent: {
            ok: true,
            data: { results: [{ ok: true, itemId, newRole: 'work' }], summary: { ok: 1, failed: 0 } },
            meta: { reqId: 'req00002' },
          },
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(recordRes.status, 0);

    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: agentId }, tempDir, apiUrl);
    assert.equal(guardRes.status, 0);
    const out = JSON.parse(guardRes.stdout);
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
    assert.ok(out.reason.includes(itemId.slice(0, 8)), out.reason);
    assert.ok(requests.length >= 1);
    assert.ok(requests.every((r) => r.method === 'POST' && r.url === '/api/v1/ops/items_read'), JSON.stringify(requests));
    assert.deepEqual(JSON.parse(requests[0].body), { select: 'context', itemId });
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('chained: gate_blocked ("already in phase") advance -> recorded detail.role "work" -> gate still work -> blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `chain-blocked-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c0ffee00-0000-0000-0000-000000000002';
  // Realistic 4.0 shape: the failed element carries error.detail {itemId, role}; role is the
  // current role of the item ("work", the phase this agent already entered), bound by the
  // server's TransitionPolicy to item.role, so a redundant advance toward review still records "work".
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    const recordRes = runRecord(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_name: ADVANCE_TOOL,
        tool_input: { ops: [{ op: 'transition', itemId, trigger: 'complete' }] },
        tool_response: {
          content: [{ type: 'text', text: 'partial_failure' }],
          isError: true,
          structuredContent: {
            ok: false,
            error: { code: 'partial_failure', kind: 'permanent', message: '1 of 1 failed' },
            data: {
              results: [
                {
                  ok: false,
                  error: {
                    code: 'gate_blocked',
                    kind: 'permanent',
                    message: 'required notes not filled for work phase: implementation-notes',
                    detail: { itemId, role: 'work', missing: ['implementation-notes'] },
                  },
                },
              ],
              summary: { ok: 0, failed: 1 },
            },
            meta: { reqId: 'req00003' },
          },
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(recordRes.status, 0);

    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: agentId }, tempDir, apiUrl);
    assert.equal(guardRes.status, 0);
    const out = JSON.parse(guardRes.stdout);
    // The assertion that fails if the recorded role were the transition's target ("review"):
    // gate.role "work" != entered "review" would skip the item and emit {} instead of blocking.
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
    assert.deepEqual(JSON.parse(requests[0].body), { select: 'context', itemId });
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('chained: a seatToken advance records nothing, so the guard has no item to fetch or block on', async () => {
  const tempDir = freshTempDir();
  const sessionId = `chain-seat-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c0ffee00-0000-0000-0000-000000000003';
  const requests = [];
  const server = await startGateStub('work', ['implementation-notes'], requests);
  try {
    const recordRes = runRecord(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_name: ADVANCE_TOOL,
        tool_input: { ops: [{ op: 'transition', itemId, trigger: 'start' }], seatToken: 'seat-token-abc' },
        tool_response: {
          structuredContent: { ok: true, data: { results: [{ ok: true, itemId, newRole: 'work' }], summary: { ok: 1, failed: 0 } } },
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(recordRes.status, 0);

    const apiUrl = `http://127.0.0.1:${server.address().port}`;
    const guardRes = await runGuard({ session_id: sessionId, agent_id: agentId }, tempDir, apiUrl);
    assert.equal(guardRes.status, 0);
    assert.deepEqual(JSON.parse(guardRes.stdout), {});
    assert.equal(requests.length, 0);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});
