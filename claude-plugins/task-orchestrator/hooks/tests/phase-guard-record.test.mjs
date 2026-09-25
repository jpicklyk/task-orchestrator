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

// ── Direct unit coverage of the exported pure helpers ────────────────────────────────────────

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
