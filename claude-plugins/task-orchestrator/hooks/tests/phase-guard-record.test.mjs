// Drives phase-guard-record.mjs as a subprocess with fixture JSON on stdin. State isolation per
// the specification's harness rule: redirect TEMP/TMP/TMPDIR to a fresh mkdtempSync dir for each
// test's child process (Node resolves os.tmpdir() from TEMP first on Windows, TMPDIR first on
// POSIX), then read back <dir>/task-orchestrator/phase-guard-*.json directly — never the real
// os.tmpdir(). `markerFor` mirrors that redirection in THIS process just long enough to compute
// the same path phaseGuardMarkerPath would give the child, so the parent can read it back.
//
// This hook does no network I/O (it only reads stdin and writes a local state file), so spawnSync
// is safe here — no in-process stub server needs the event loop back (contrast phase-guard.mjs's
// own test file, which spawns async because it DOES run a stub server in this process).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import {
  phaseGuardMarkerPath,
  readPhaseGuardMarker,
  extractRecordableItemIds,
  extractEnteredRoles,
  buildActorMap,
  actorForResult,
  isWorkflowSeatActor,
  isFullUuid,
} from '../phase-guard-record.mjs';

const HOOK = fileURLToPath(new URL('../phase-guard-record.mjs', import.meta.url));
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-record-'));
}

function spawnHook(payload, tempDir, apiUrl, modeOverride) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_MODE;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  if (modeOverride) env.TASK_ORCHESTRATOR_MODE = modeOverride;
  return spawnSync(process.execPath, [HOOK], { input: JSON.stringify(payload), env, encoding: 'utf-8' });
}

function spawnHookRaw(rawInput, tempDir, apiUrl = UNREACHABLE_API_URL) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir };
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return spawnSync(process.execPath, [HOOK], { input: rawInput, env, encoding: 'utf-8' });
}

/** Resolves phaseGuardMarkerPath the same way the child did, by redirecting THIS process's
 * TEMP/TMP/TMPDIR to the same tempDir for the duration of the call. */
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

function readMarker(tempDir, sessionId, agentId) {
  return readPhaseGuardMarker(markerFor(tempDir, sessionId, agentId));
}

// ── S1: recording a successful/gate-blocked start result ──────────────────────────────────────

test('S1: bare {results,summary} shape — successful start result stores the itemId', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aaaaaaaa-0000-0000-0000-000000000001';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_name: 'mcp__mcp-task-orchestrator__advance_item',
        tool_response: {
          results: [{ itemId, newRole: 'work', applied: true }],
          summary: { total: 1, succeeded: 1, failed: 0 },
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1: CallToolResult content[] wrapper shape (JSON text block) stores the itemId', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1b-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'bbbbbbbb-0000-0000-0000-000000000002';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          content: [{ type: 'text', text: JSON.stringify({ results: [{ itemId, newRole: 'work', applied: true }] }) }],
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1: production shape (human-text content[] + structuredContent) reads structuredContent, not content', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1c-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'cccccccc-0000-0000-0000-000000000003';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          content: [{ type: 'text', text: 'Advanced 1 item to work.' }], // human summary — NOT valid JSON
          structuredContent: {
            results: [{ itemId, newRole: 'work', applied: true }],
            summary: { total: 1, succeeded: 1, failed: 0 },
          },
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1b: a gate-blocked failure with no errorCode ("already in phase") is still recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1d-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'dddddddd-0000-0000-0000-000000000004';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          results: [
            {
              itemId,
              trigger: 'start',
              applied: false,
              error: 'Item is already in work',
              errorCode: 'gate_blocked',
              missingNotes: ['implementation-notes'],
              previousRole: 'work',
              targetRole: 'work',
            },
          ],
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S2: skip conditions on individual results ──────────────────────────────────────────────────

test('S2: a result carrying errorCode is not recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `s2-${randomUUID()}`;
  const agentId = 'agent-1';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          results: [
            {
              itemId: 'eeeeeeee-0000-0000-0000-000000000005',
              errorCode: 'resource_unavailable',
              errorKind: 'transient',
            },
          ],
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S2b: a non-UUID itemId (hex-prefix resolve failure) is not recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `s2b-${randomUUID()}`;
  const agentId = 'agent-1';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: { results: [{ itemId: 'ef07', applied: false, error: 'ambiguous prefix' }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S3: no-op gates ────────────────────────────────────────────────────────────────────────────

test('S3: no agent_id (main session) records nothing', () => {
  const tempDir = freshTempDir();
  const sessionId = `s3-${randomUUID()}`;
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        tool_response: { results: [{ itemId: 'ffffffff-0000-0000-0000-000000000006', newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'unknown').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3: API URL unset records nothing even with agent_id present', () => {
  const tempDir = freshTempDir();
  const sessionId = `s3b-${randomUUID()}`;
  const agentId = 'agent-1';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: { results: [{ itemId: '11111111-0000-0000-0000-000000000007', newRole: 'work', applied: true }] },
      },
      tempDir,
      undefined, // no TASK_ORCHESTRATOR_API_URL
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── 004d65fd: S11 — headless iteration records nothing, even with agent_id + API URL + applied ──

test('S11: headless iteration records nothing even with agent_id, API URL, and an applied result', () => {
  const tempDir = freshTempDir();
  const sessionId = `s11-${randomUUID()}`;
  const agentId = 'agent-1';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: { results: [{ itemId: '22222222-0000-0000-0000-000000000008', newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
      'headless-iteration',
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Fail-open ──────────────────────────────────────────────────────────────────────────────────

test('fail-open: malformed stdin yields {} and exit 0', () => {
  const tempDir = freshTempDir();
  try {
    const res = spawnHookRaw('{not valid json', tempDir);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('fail-open: empty stdin yields {} and exit 0', () => {
  const tempDir = freshTempDir();
  try {
    const res = spawnHookRaw('', tempDir);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('fail-open: unrecognized tool_response shape yields {} and exit 0, records nothing', () => {
  const tempDir = freshTempDir();
  const sessionId = `failopen-${randomUUID()}`;
  const agentId = 'agent-1';
  try {
    const res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { totally: 'unexpected shape' } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Accumulation across calls ─────────────────────────────────────────────────────────────────

test('accumulates itemIds across multiple advance_item calls for the same agent, deduping re-recorded items', () => {
  const tempDir = freshTempDir();
  const sessionId = `accum-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemA = '22222222-0000-0000-0000-000000000008';
  const itemB = '33333333-0000-0000-0000-000000000009';
  try {
    let res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId: itemA, newRole: 'work', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);

    res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId: itemB, newRole: 'review', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);

    // A gate-blocked re-entry of itemA (e.g. a bounced retry) must not duplicate it in the list.
    res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId: itemA, applied: false, error: 'already in work', errorCode: 'gate_blocked' }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);

    const items = readMarker(tempDir, sessionId, agentId).items;
    assert.deepEqual([...items].sort(), [itemA, itemB].sort());
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('distinct agent_ids in the same session get separate marker files', () => {
  const tempDir = freshTempDir();
  const sessionId = `distinct-${randomUUID()}`;
  const itemA = '44444444-0000-0000-0000-00000000000a';
  const itemB = '55555555-0000-0000-0000-00000000000b';
  try {
    let res = spawnHook(
      { session_id: sessionId, agent_id: 'agent-A', tool_response: { results: [{ itemId: itemA, newRole: 'work', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    res = spawnHook(
      { session_id: sessionId, agent_id: 'agent-B', tool_response: { results: [{ itemId: itemB, newRole: 'work', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);

    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-A').items, [itemA]);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-B').items, [itemB]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Workflow-seat actor exclusion (036420aa) ─────────────────────────────────────────────────

test('workflow actor (batch transitions[] shape): a result whose transition actor.parent starts with "workflow:" is not recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `wf1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000000-0000-0000-0000-000000000001';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: {
          transitions: [{ itemId, trigger: 'start', actor: { id: 'wf-runner', kind: 'orchestrator', parent: 'workflow:release-flow' } }],
        },
        tool_response: { results: [{ itemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('workflow actor (singular sugar shape): a top-level actor.parent starting with "workflow:" is not recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `wf2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000000-0000-0000-0000-000000000002';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: { itemId, trigger: 'start', actor: { id: 'wf-runner', kind: 'orchestrator', parent: 'workflow:release-flow' } },
        tool_response: { results: [{ itemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('non-workflow actor (parent is an ordinary dispatching agent id) is still recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `wf3-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000000-0000-0000-0000-000000000003';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: {
          transitions: [{ itemId, trigger: 'start', actor: { id: 'implementer-1', kind: 'subagent', parent: 'orchestrator-main' } }],
        },
        tool_response: { results: [{ itemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('mixed batch: only the workflow-seat item is excluded, the ordinary one is recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `wf4-${randomUUID()}`;
  const agentId = 'agent-1';
  const wfItem = 'aa000000-0000-0000-0000-000000000004';
  const normalItem = 'aa000000-0000-0000-0000-000000000005';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: {
          transitions: [
            { itemId: wfItem, trigger: 'start', actor: { id: 'wf-runner', kind: 'orchestrator', parent: 'workflow:x' } },
            { itemId: normalItem, trigger: 'start', actor: { id: 'implementer-1', kind: 'subagent', parent: 'orchestrator-main' } },
          ],
        },
        tool_response: {
          results: [
            { itemId: wfItem, newRole: 'work', applied: true },
            { itemId: normalItem, newRole: 'work', applied: true },
          ],
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [normalItem]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('no actor at all on the transition is still recorded (absence of actor is not a workflow actor)', () => {
  const tempDir = freshTempDir();
  const sessionId = `wf5-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'aa000000-0000-0000-0000-000000000006';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: { transitions: [{ itemId, trigger: 'start' }] },
        tool_response: { results: [{ itemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, agentId).items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Entered-role tracking (036420aa) ─────────────────────────────────────────────────────────

test('entered role: an applied:true result records newRole into marker.enteredRoles', () => {
  const tempDir = freshTempDir();
  const sessionId = `role1-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'bb000000-0000-0000-0000-000000000001';
  try {
    const res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId, newRole: 'work', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.items, [itemId]);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'work' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('entered role: a gate_blocked ("already in phase") result records previousRole (the item\'s current role) into marker.enteredRoles, not targetRole', () => {
  const tempDir = freshTempDir();
  const sessionId = `role2-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'bb000000-0000-0000-0000-000000000002';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_response: {
          results: [
            {
              itemId,
              applied: false,
              error: 'Item is already in work',
              errorCode: 'gate_blocked',
              // Realistic server shape: previousRole is the item's CURRENT role (the phase the
              // agent is already sitting in); targetRole is the phase the blocked transition
              // tried to reach. This agent entered `work`, not `review`.
              previousRole: 'work',
              targetRole: 'review',
            },
          ],
        },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.items, [itemId]);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'work' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('entered role: a later call for the same item updates enteredRoles to the newer role', () => {
  const tempDir = freshTempDir();
  const sessionId = `role3-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'bb000000-0000-0000-0000-000000000003';
  try {
    let res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId, newRole: 'work', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    res = spawnHook(
      { session_id: sessionId, agent_id: agentId, tool_response: { results: [{ itemId, newRole: 'review', applied: true }] } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'review' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('entered role: a workflow-seat-excluded result does not add an enteredRoles entry', () => {
  const tempDir = freshTempDir();
  const sessionId = `role4-${randomUUID()}`;
  const agentId = 'agent-1';
  const itemId = 'bb000000-0000-0000-0000-000000000004';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        tool_input: {
          transitions: [{ itemId, trigger: 'start', actor: { id: 'wf-runner', kind: 'orchestrator', parent: 'workflow:x' } }],
        },
        tool_response: { results: [{ itemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Direct unit coverage of the exported pure helpers ────────────────────────────────────────

test('isWorkflowSeatActor: true only for a string actor.parent starting with "workflow:"', () => {
  assert.equal(isWorkflowSeatActor({ id: 'x', parent: 'workflow:release' }), true);
  assert.equal(isWorkflowSeatActor({ id: 'x', parent: 'workflow:' }), true);
  assert.equal(isWorkflowSeatActor({ id: 'x', parent: 'orchestrator-main' }), false);
  assert.equal(isWorkflowSeatActor({ id: 'x', parent: 'not-workflow:release' }), false);
  assert.equal(isWorkflowSeatActor({ id: 'x' }), false);
  assert.equal(isWorkflowSeatActor(undefined), false);
  assert.equal(isWorkflowSeatActor(null), false);
  assert.equal(isWorkflowSeatActor('workflow:release'), false);
});

test('buildActorMap: batch transitions[] shape maps each itemId to its own actor', () => {
  const map = buildActorMap({
    transitions: [
      { itemId: 'id-1', actor: { id: 'a', parent: 'workflow:x' } },
      { itemId: 'id-2', actor: { id: 'b', parent: 'orchestrator-main' } },
      { itemId: 'id-3' },
    ],
  });
  assert.deepEqual(map.get('id-1'), { id: 'a', parent: 'workflow:x' });
  assert.deepEqual(map.get('id-2'), { id: 'b', parent: 'orchestrator-main' });
  assert.equal(map.get('id-3'), undefined);
  assert.equal(map.has('id-4'), false);
});

test('buildActorMap: singular sugar shape maps the one top-level itemId to the top-level actor', () => {
  const map = buildActorMap({ itemId: 'id-1', trigger: 'start', actor: { id: 'a', parent: 'workflow:x' } });
  assert.deepEqual(map.get('id-1'), { id: 'a', parent: 'workflow:x' });
});

test('buildActorMap: malformed/missing tool_input yields an empty map', () => {
  assert.equal(buildActorMap(undefined).size, 0);
  assert.equal(buildActorMap(null).size, 0);
  assert.equal(buildActorMap({}).size, 0);
  assert.equal(buildActorMap({ transitions: 'not-an-array' }).size, 0);
});

test('extractEnteredRoles: newRole on applied:true, previousRole on gate_blocked (not targetRole), omitted otherwise', () => {
  const payload = {
    results: [
      { itemId: 'aaaaaaaa-0000-0000-0000-000000000001', applied: true, newRole: 'work' },
      // Realistic server shape: previousRole = item's current role (work), targetRole = the
      // phase the blocked transition tried to reach (review). Entered role is `work`.
      { itemId: 'bbbbbbbb-0000-0000-0000-000000000002', applied: false, errorCode: 'gate_blocked', previousRole: 'work', targetRole: 'review' },
      { itemId: 'cccccccc-0000-0000-0000-000000000003', applied: false, errorCode: 'dependency_blocked' },
      { itemId: 'ef07', applied: true, newRole: 'work' }, // non-UUID — omitted regardless of role
    ],
  };
  assert.deepEqual(extractEnteredRoles(payload), {
    'aaaaaaaa-0000-0000-0000-000000000001': 'work',
    'bbbbbbbb-0000-0000-0000-000000000002': 'work',
  });
});

// ── O2: actorForResult prefix/positional matching (036420aa) ────────────────────────────────────

test('actorForResult: exact match wins over prefix match', () => {
  const map = buildActorMap({ itemId: 'aaaaaaaa-0000-0000-0000-000000000001', actor: { id: 'exact' } });
  const actor = actorForResult(map, 'aaaaaaaa-0000-0000-0000-000000000001', 0, 1);
  assert.deepEqual(actor, { id: 'exact' });
});

test('actorForResult: a hex-prefix input itemId matches the full-UUID result via prefix comparison', () => {
  // Mirrors the real singular-sugar shape: the caller's raw tool_input.itemId can be a short hex
  // prefix (4+ hex chars), but the server resolves it and echoes the FULL UUID back in results[].
  // A plain exact-key Map.get would miss this and let the workflow-seat filter fail open.
  const map = buildActorMap({ itemId: 'bb00', trigger: 'start', actor: { id: 'wf', parent: 'workflow:x' } });
  const actor = actorForResult(map, 'bb000000-0000-0000-0000-000000000009', 0, 1);
  assert.deepEqual(actor, { id: 'wf', parent: 'workflow:x' });
});

test('actorForResult: positional fallback when sizes match and no textual relation exists', () => {
  const map = buildActorMap({
    transitions: [
      { itemId: 'unrelated-key-1', actor: { id: 'first' } },
      { itemId: 'unrelated-key-2', actor: { id: 'second' } },
    ],
  });
  assert.deepEqual(actorForResult(map, 'cccccccc-0000-0000-0000-000000000001', 0, 2), { id: 'first' });
  assert.deepEqual(actorForResult(map, 'dddddddd-0000-0000-0000-000000000002', 1, 2), { id: 'second' });
});

test('actorForResult: no match when sizes differ and nothing textually relates', () => {
  const map = buildActorMap({ itemId: 'unrelated', actor: { id: 'x' } });
  assert.equal(actorForResult(map, 'aaaaaaaa-0000-0000-0000-000000000001', 0, 2), undefined);
});

test('actorForResult: malformed inputs yield undefined', () => {
  assert.equal(actorForResult(undefined, 'a', 0, 1), undefined);
  assert.equal(actorForResult(new Map(), null, 0, 1), undefined);
});

test('O2 end-to-end: a hex-prefix singular-sugar workflow-seat transition is still excluded from recording', () => {
  const tempDir = freshTempDir();
  const sessionId = `o2-${randomUUID()}`;
  const agentId = 'agent-1';
  const fullItemId = 'bb000000-0000-0000-0000-000000000099';
  try {
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: agentId,
        // Raw tool_input carries a hex-prefix itemId (as a caller might type), while the
        // response echoes the resolved full UUID — the mismatch this fix addresses.
        tool_input: { itemId: 'bb00', trigger: 'start', actor: { id: 'wf-runner', kind: 'orchestrator', parent: 'workflow:release' } },
        tool_response: { results: [{ itemId: fullItemId, newRole: 'work', applied: true }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, agentId);
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('extractEnteredRoles: empty/missing results yields an empty object', () => {
  assert.deepEqual(extractEnteredRoles({}), {});
  assert.deepEqual(extractEnteredRoles(null), {});
  assert.deepEqual(extractEnteredRoles({ results: [] }), {});
});



test('isFullUuid: accepts a full UUID, rejects a hex prefix / non-string / empty', () => {
  assert.equal(isFullUuid('aaaaaaaa-0000-0000-0000-000000000001'), true);
  assert.equal(isFullUuid('ef07'), false);
  assert.equal(isFullUuid(''), false);
  assert.equal(isFullUuid(undefined), false);
  assert.equal(isFullUuid(123), false);
});

test('extractRecordableItemIds: filters out errorCode results and non-UUID itemIds, keeps the rest', () => {
  const payload = {
    results: [
      { itemId: 'aaaaaaaa-0000-0000-0000-000000000001', newRole: 'work', applied: true },
      { itemId: 'ef07', applied: false, error: 'ambiguous prefix' },
      { itemId: 'bbbbbbbb-0000-0000-0000-000000000002', errorCode: 'resource_unavailable' },
      { itemId: 'cccccccc-0000-0000-0000-000000000003', applied: false, error: 'already in phase', errorCode: 'gate_blocked' },
    ],
  };
  assert.deepEqual(extractRecordableItemIds(payload), [
    'aaaaaaaa-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000003',
  ]);
});

test('extractRecordableItemIds: empty/missing results yields an empty array', () => {
  assert.deepEqual(extractRecordableItemIds({}), []);
  assert.deepEqual(extractRecordableItemIds(null), []);
  assert.deepEqual(extractRecordableItemIds({ results: [] }), []);
});
