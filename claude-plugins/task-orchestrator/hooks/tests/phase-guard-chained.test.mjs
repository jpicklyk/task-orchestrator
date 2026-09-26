// Chained coverage (036420aa B1/B2 fix round): runs phase-guard-record.mjs (PostToolUse:advance_item)
// against a REALISTIC advance_item tool_response, then feeds the marker it writes into
// phase-guard.mjs (SubagentStop) against a stubbed gate, asserting the end-to-end block/no-block
// outcome. Neither unit test file exercises this seam: phase-guard-record.test.mjs never invokes
// phase-guard.mjs, and phase-guard.test.mjs seeds its marker directly via writePhaseGuardMarker
// rather than producing it from phase-guard-record.mjs's own extraction logic.
//
// This specifically catches the B1 regression: phase-guard-record.mjs's extractEnteredRoles used
// to record `targetRole` for the gate_blocked ("already in phase") case, but the server's
// AdvanceService.checkGate sets `targetRole` to the phase the blocked transition tried to REACH,
// not the phase the agent is actually sitting in (`previousRole`, i.e. `item.role`). Recording the
// wrong role meant phase-guard.mjs's entered-role gate (`gate.role !== enteredRole`) would compare
// the item's real current role ("work") against a bogus recorded role ("review") and silently skip
// the item — a false negative, never blocking a subagent that should have been sent back for
// missing work-phase notes.
//
// Confirmed red on pre-fix code: temporarily reverting extractEnteredRoles's gate_blocked branch
// to `r.targetRole` (the B1 bug) makes the second scenario below fail, because phase-guard.mjs then
// sees enteredRole "review" != gate.role "work" and treats the item as moved-on, emitting `{}`
// instead of a block. Restored immediately after confirming; see session-tracking for the observed
// failure output.

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

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-chained-'));
}

/** Runs phase-guard-record.mjs synchronously (it does no network I/O). */
function runRecord(payload, tempDir, apiUrl) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_MODE;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return spawnSync(process.execPath, [RECORD_HOOK], {
    input: JSON.stringify(payload),
    env,
    encoding: 'utf-8',
  });
}

/** Runs phase-guard.mjs asynchronously (it fetches the stub gate server over HTTP, which shares
 * this process's event loop — spawnSync would starve it, per phase-guard.test.mjs's own harness
 * rule). */
function runGuard(payload, tempDir, apiUrl) {
  return new Promise((resolvePromise, rejectPromise) => {
    const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
    delete env.TASK_ORCHESTRATOR_MODE;
    if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
    const child = spawn(process.execPath, [GUARD_HOOK], { env });
    let stdout = '';
    child.stdout.on('data', (d) => {
      stdout += d;
    });
    child.on('error', rejectPromise);
    child.on('close', (status) => resolvePromise({ status, stdout }));
    child.stdin.end(JSON.stringify(payload));
  });
}

function startGateStub(role, missing) {
  return new Promise((resolveListen) => {
    const server = createServer((req, res) => {
      const match = req.url.match(/^\/api\/v1\/items\/([^/]+)\/gate$/);
      const id = match ? match[1] : null;
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(
        JSON.stringify({
          itemId: id,
          title: 'Widget frobnicator',
          role,
          gateStatus: { canAdvance: false, phase: role, missing },
        }),
      );
    });
    server.listen(0, '127.0.0.1', () => resolveListen(server));
  });
}

function stopStub(server) {
  return new Promise((res) => server.close(res));
}

test('chained: applied:true advance_item -> recorded newRole "work" -> gate still work with missing notes -> blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `chain-applied-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c0ffee00-0000-0000-0000-000000000001';
  const server = await startGateStub('work', ['implementation-notes']);
  try {
    const recordRes = runRecord(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: { results: [{ itemId, trigger: 'start', applied: true, newRole: 'work' }] },
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
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('chained: gate_blocked ("already in phase") advance_item -> recorded previousRole "work" (not targetRole "review") -> gate still work -> blocks', async () => {
  const tempDir = freshTempDir();
  const sessionId = `chain-blocked-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'c0ffee00-0000-0000-0000-000000000002';
  // Realistic AdvanceService.checkGate shape: previousRole = item's CURRENT role ("work", the
  // phase this agent already entered); targetRole = the phase the (redundant) advance attempt
  // tried to reach next ("review"). Regression: pre-fix code recorded targetRole here.
  const server = await startGateStub('work', ['implementation-notes']);
  try {
    const recordRes = runRecord(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          results: [
            {
              itemId,
              trigger: 'complete',
              applied: false,
              error: 'Gate check failed: required notes not filled for work phase: implementation-notes',
              errorCode: 'gate_blocked',
              missingNotes: ['implementation-notes'],
              previousRole: 'work',
              targetRole: 'review',
            },
          ],
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
    // This is the assertion that fails on the pre-fix (targetRole-recording) code: it would have
    // recorded enteredRole "review", seen gate.role "work" != "review", skipped the item, and
    // emitted {} instead of blocking.
    assert.equal(out.decision, 'block');
    assert.ok(out.reason.includes('implementation-notes'), out.reason);
  } finally {
    await stopStub(server);
    rmSync(tempDir, { recursive: true, force: true });
  }
});
