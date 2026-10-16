// Drives phase-guard-record.mjs as a subprocess with fixture JSON on stdin. State isolation per
// the specification's harness rule: redirect TEMP/TMP/TMPDIR to a fresh mkdtempSync dir for each
// test's child process (Node resolves os.tmpdir() from TEMP first on Windows, TMPDIR first on
// POSIX), then read back <dir>/task-orchestrator/phase-guard-*.json directly -- never the real
// os.tmpdir(). `markerFor` mirrors that redirection in THIS process just long enough to compute
// the same path phaseGuardMarkerPath would give the child, so the parent can read it back.
//
// Fixtures are 4.0 envelopes (api-reference.md "Response envelope": ok / data.results[] / error
// {code,kind,message,detail}) and ops[] tool_input (advance op "transition"). Oracle: 0bbdc4cc
// task-scope AC2, AC3 and D1; test-plan S1, S2, S8, S11, S13.
//
// This hook does no network I/O (it only reads stdin and writes a local state file), so spawnSync
// is safe here -- no in-process stub server needs the event loop back.

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
  isFullUuid,
} from '../phase-guard-record.mjs';
import { parseToolResult } from '../lib/envelope.mjs';

const HOOK = fileURLToPath(new URL('../phase-guard-record.mjs', import.meta.url));
const UNREACHABLE_API_URL = 'http://127.0.0.1:1';
const TOOL = 'mcp__mcp-task-orchestrator__advance';

function freshTempDir() {
  return mkdtempSync(join(tmpdir(), 'to-phase-guard-record-'));
}

function spawnHook(payload, tempDir, apiUrl, modeOverride) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
  delete env.AGENT_CONFIG_DIR;
  delete env.TASK_ORCHESTRATOR_API_URL;
  delete env.TASK_ORCHESTRATOR_MODE;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  if (modeOverride) env.TASK_ORCHESTRATOR_MODE = modeOverride;
  return spawnSync(process.execPath, [HOOK], { input: JSON.stringify(payload), env, cwd: tempDir, encoding: 'utf-8' });
}

function spawnHookRaw(rawInput, tempDir, apiUrl = UNREACHABLE_API_URL) {
  const env = { ...process.env, TEMP: tempDir, TMP: tempDir, TMPDIR: tempDir, TASK_ORCHESTRATOR_HOME: join(tempDir, 'to-home-empty'), TASK_ORCHESTRATOR_CEILING: tempDir };
  delete env.AGENT_CONFIG_DIR;
  if (apiUrl) env.TASK_ORCHESTRATOR_API_URL = apiUrl;
  return spawnSync(process.execPath, [HOOK], { input: rawInput, env, cwd: tempDir, encoding: 'utf-8' });
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

// ── 4.0 fixtures ──────────────────────────────────────────────────────────────────────────────

const okResult = (itemId, newRole = 'work') => ({ ok: true, itemId, newRole });
const errResult = (code, kind = 'permanent', detail) => ({
  ok: false,
  error: { code, kind, message: `${code} fixture`, ...(detail ? { detail } : {}) },
});
const gateBlocked = (itemId, role = 'work') => errResult('gate_blocked', 'permanent', { itemId, role });

/** The tool_response of an advance call: a full-success envelope, or a partial_failure envelope
 * (top-level ok:false, full data) when any element failed -- the shape the server returns. */
function advanceResponse(results) {
  const failed = results.filter((r) => r.ok !== true).length;
  const data = { results, summary: { ok: results.length - failed, failed } };
  const structuredContent =
    failed === 0
      ? { ok: true, data, meta: { reqId: 'req00001' } }
      : { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: `${failed} of ${results.length} failed` }, data, meta: { reqId: 'req00001' } };
  return { content: [{ type: 'text', text: failed === 0 ? 'ok' : 'partial_failure' }], isError: failed !== 0, structuredContent };
}

function transitionInput(results, extra = {}) {
  const ids = results.map((r) => r.itemId ?? r.error?.detail?.itemId ?? 'unknown');
  return { ops: ids.map((itemId) => ({ op: 'transition', itemId, trigger: 'start' })), ...extra };
}

/** Full hook stdin for one advance call. */
function advanceCall(sessionId, agentId, results, { inputExtra = {}, ...rest } = {}) {
  return {
    session_id: sessionId,
    ...(agentId ? { agent_id: agentId } : {}),
    tool_name: TOOL,
    tool_input: transitionInput(results, inputExtra),
    tool_response: advanceResponse(results),
    ...rest,
  };
}

// ── S1: recording successful / gate-blocked results (AC3) ─────────────────────────────────────

test('S1: structuredContent envelope with an ok:true result stores the itemId and the entered role', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1-${randomUUID()}`;
  const itemId = 'aaaaaaaa-0000-0000-0000-000000000001';
  try {
    const res = spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId)]), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [itemId]);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'work' });
    assert.equal(marker.blocks, 0);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1: the envelope delivered only as a JSON text block (content[0].text) is recorded identically', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1b-${randomUUID()}`;
  const itemId = 'bbbbbbbb-0000-0000-0000-000000000002';
  try {
    const envelope = { ok: true, data: { results: [okResult(itemId, 'review')], summary: { ok: 1, failed: 0 } } };
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: 'agent-1',
        tool_name: TOOL,
        tool_response: { content: [{ type: 'text', text: JSON.stringify(envelope) }] },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [itemId]);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'review' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1: a batch of two ok:true results records BOTH ids and BOTH entered roles (every results[] element is considered)', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1c-${randomUUID()}`;
  const u1 = 'cccccccc-0000-0000-0000-000000000001';
  const u2 = 'cccccccc-0000-0000-0000-000000000002';
  try {
    const res = spawnHook(advanceCall(sessionId, 'agent-1', [okResult(u1, 'work'), okResult(u2, 'review')]), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual([...marker.items].sort(), [u1, u2].sort());
    assert.deepEqual(marker.enteredRoles, { [u1]: 'work', [u2]: 'review' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S1 probe: failing leading elements do not hide a later ok:true element (position is irrelevant)', () => {
  const tempDir = freshTempDir();
  const sessionId = `s1d-${randomUUID()}`;
  const late = 'cccccccc-0000-0000-0000-000000000003';
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [errResult('not_found'), errResult('invalid_transition'), okResult(late, 'work')]),
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [late]);
    assert.deepEqual(marker.enteredRoles, { [late]: 'work' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S2: gate_blocked is the other recordable outcome (AC3) ────────────────────────────────────

test('S2: ok:false with error.code gate_blocked is recorded with id and role taken from error.detail', () => {
  const tempDir = freshTempDir();
  const sessionId = `s2-${randomUUID()}`;
  const itemId = 'dddddddd-0000-0000-0000-000000000004';
  try {
    // detail.role is the item's CURRENT role (the phase the agent already sits in), not the
    // phase the blocked transition tried to reach: the marker records work, never review.
    const res = spawnHook(advanceCall(sessionId, 'agent-1', [gateBlocked(itemId, 'work')]), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, [itemId]);
    assert.deepEqual(marker.enteredRoles, { [itemId]: 'work' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S2: gate_blocked recorded from a mixed batch next to an ok:true element', () => {
  const tempDir = freshTempDir();
  const sessionId = `s2b-${randomUUID()}`;
  const blocked = 'dddddddd-0000-0000-0000-000000000005';
  const advanced = 'dddddddd-0000-0000-0000-000000000006';
  try {
    const res = spawnHook(advanceCall(sessionId, 'agent-1', [gateBlocked(blocked, 'review'), okResult(advanced, 'work')]), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual([...marker.items].sort(), [blocked, advanced].sort());
    assert.deepEqual(marker.enteredRoles, { [blocked]: 'review', [advanced]: 'work' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S8: everything else records nothing (AC3) ─────────────────────────────────────────────────

test('S8: not_claim_holder, seat_forbidden, resource_unavailable, dependency_unmet, invalid_transition, not_found are not recorded', () => {
  const codes = [
    ['not_claim_holder', 'permanent'],
    ['seat_forbidden', 'permanent'],
    ['resource_unavailable', 'transient'],
    ['dependency_unmet', 'permanent'],
    ['invalid_transition', 'permanent'],
    ['not_found', 'permanent'],
  ];
  for (const [code, kind] of codes) {
    const tempDir = freshTempDir();
    const sessionId = `s8-${randomUUID()}`;
    // The detail carries a full UUID itemId and a role, exactly like gate_blocked does: only the
    // code can tell these apart from a recordable block.
    const results = [errResult(code, kind, { itemId: 'eeeeeeee-0000-0000-0000-000000000007', role: 'work' })];
    try {
      const res = spawnHook(advanceCall(sessionId, 'agent-1', results), tempDir, UNREACHABLE_API_URL);
      assert.equal(res.status, 0, code);
      const marker = readMarker(tempDir, sessionId, 'agent-1');
      assert.deepEqual(marker.items, [], code);
      assert.deepEqual(marker.enteredRoles, {}, code);
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  }
});

test('S8: ok:false without any error code is not recorded; neither is ok:false with no error object', () => {
  const tempDir = freshTempDir();
  const sessionId = `s8b-${randomUUID()}`;
  try {
    const results = [
      { ok: false, itemId: 'eeeeeeee-0000-0000-0000-000000000008', newRole: 'work' },
      { ok: false, error: { kind: 'permanent', message: 'no code', detail: { itemId: 'eeeeeeee-0000-0000-0000-000000000009', role: 'work' } } },
    ];
    const res = spawnHook(advanceCall(sessionId, 'agent-1', results), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: 3.x result fields (applied / errorCode / previousRole) are not read on a 4.0 element', () => {
  const tempDir = freshTempDir();
  const sessionId = `s8c-${randomUUID()}`;
  try {
    const results = [
      // no ok field at all: the 3.x applied flag alone must not make this recordable
      { itemId: 'eeeeeeee-0000-0000-0000-00000000000a', applied: true, newRole: 'work' },
      // ok:false element dressed with a 3.x errorCode / previousRole instead of error.code / detail
      { ok: false, itemId: 'eeeeeeee-0000-0000-0000-00000000000b', errorCode: 'gate_blocked', previousRole: 'work' },
    ];
    const res = spawnHook(
      {
        session_id: sessionId,
        agent_id: 'agent-1',
        tool_name: TOOL,
        tool_response: { structuredContent: { ok: false, error: { code: 'partial_failure', kind: 'permanent', message: 'm' }, data: { results } } },
      },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: ok:true without a string newRole is not recorded', () => {
  const tempDir = freshTempDir();
  const sessionId = `s8d-${randomUUID()}`;
  try {
    const results = [
      { ok: true, itemId: 'eeeeeeee-0000-0000-0000-00000000000c' },
      { ok: true, itemId: 'eeeeeeee-0000-0000-0000-00000000000d', newRole: 7 },
    ];
    const res = spawnHook(advanceCall(sessionId, 'agent-1', results), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S8: a hex-prefix id is not recorded, as ok:true itemId or as gate_blocked detail.itemId', () => {
  const tempDir = freshTempDir();
  const sessionId = `s8e-${randomUUID()}`;
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [okResult('ef07', 'work'), gateBlocked('ef07a1b2', 'work')]),
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S11: legacy 3.x shape (AC2) ───────────────────────────────────────────────────────────────

test('S11: a 3.x {success} payload records nothing and yields the legacy-shape warning once per session, then {}', () => {
  const tempDir = freshTempDir();
  const sessionId = `s11-${randomUUID()}`;
  const itemId = 'ffffffff-0000-0000-0000-000000000001';
  const legacy = () => ({
    session_id: sessionId,
    agent_id: 'agent-1',
    tool_name: 'mcp__mcp-task-orchestrator__advance_item',
    tool_input: { transitions: [{ itemId, trigger: 'start' }] },
    tool_response: {
      content: [{ type: 'text', text: 'Transitioned 1 item(s)' }],
      structuredContent: {
        success: true,
        message: 'Transitioned 1 item(s)',
        data: { results: [{ itemId, newRole: 'work', applied: true }], summary: { total: 1, succeeded: 1, failed: 0 } },
      },
    },
  });
  try {
    const first = spawnHook(legacy(), tempDir, UNREACHABLE_API_URL);
    assert.equal(first.status, 0);
    const out = JSON.parse(first.stdout);
    assert.deepEqual(Object.keys(out), ['systemMessage']);
    assert.equal(typeof out.systemMessage, 'string');
    assert.ok(out.systemMessage.length > 0);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);

    const second = spawnHook(legacy(), tempDir, UNREACHABLE_API_URL);
    assert.equal(second.status, 0);
    assert.equal(second.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S13: workflow-seat exclusion by seatToken (D1) ────────────────────────────────────────────

test('S13: a non-empty top-level tool_input.seatToken records nothing (ids and entered roles)', () => {
  const tempDir = freshTempDir();
  const sessionId = `s13-${randomUUID()}`;
  const itemId = 'aa000000-0000-0000-0000-000000000001';
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [okResult(itemId)], { inputExtra: { seatToken: 'seat-token-abc' } }),
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual(marker.items, []);
    assert.deepEqual(marker.enteredRoles, {});
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S13: seatToken also suppresses a gate_blocked record', () => {
  const tempDir = freshTempDir();
  const sessionId = `s13b-${randomUUID()}`;
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [gateBlocked('aa000000-0000-0000-0000-000000000002', 'work')], { inputExtra: { seatToken: 'seat-token-abc' } }),
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S13 probe: an empty seatToken, an absent seatToken and a non-string seatToken do not exclude', () => {
  const variants = [{ seatToken: '' }, {}, { seatToken: null }, { seatToken: 5 }];
  variants.forEach((inputExtra, i) => {
    const tempDir = freshTempDir();
    const sessionId = `s13c-${i}-${randomUUID()}`;
    const itemId = `aa000000-0000-0000-0000-00000000010${i}`;
    try {
      const res = spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId)], { inputExtra }), tempDir, UNREACHABLE_API_URL);
      assert.equal(res.status, 0);
      assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [itemId], JSON.stringify(inputExtra));
    } finally {
      rmSync(tempDir, { recursive: true, force: true });
    }
  });
});

test('S13: a seatToken on one call does not stop the next token-less call from recording', () => {
  const tempDir = freshTempDir();
  const sessionId = `s13d-${randomUUID()}`;
  const seatItem = 'aa000000-0000-0000-0000-000000000003';
  const plainItem = 'aa000000-0000-0000-0000-000000000004';
  try {
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(seatItem)], { inputExtra: { seatToken: 't' } }), tempDir, UNREACHABLE_API_URL);
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(plainItem)]), tempDir, UNREACHABLE_API_URL);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [plainItem]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── S3: no-op gates (unchanged) ───────────────────────────────────────────────────────────────

test('S3: no agent_id (main session) records nothing', () => {
  const tempDir = freshTempDir();
  const sessionId = `s3-${randomUUID()}`;
  try {
    const res = spawnHook(advanceCall(sessionId, undefined, [okResult('ffffffff-0000-0000-0000-000000000006')]), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'unknown').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3 / S12: API URL unset records nothing and stays silent ({}) even with agent_id present', () => {
  const tempDir = freshTempDir();
  const sessionId = `s3b-${randomUUID()}`;
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [okResult('11111111-0000-0000-0000-000000000007')]),
      tempDir,
      undefined, // no TASK_ORCHESTRATOR_API_URL
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('S3: headless iteration records nothing even with agent_id, API URL and an ok:true result', () => {
  const tempDir = freshTempDir();
  const sessionId = `s3c-${randomUUID()}`;
  try {
    const res = spawnHook(
      advanceCall(sessionId, 'agent-1', [okResult('22222222-0000-0000-0000-000000000008')]),
      tempDir,
      UNREACHABLE_API_URL,
      'headless-iteration',
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('to_mod_active on stdin: emits {} and writes no marker even with agent_id, API URL and an ok:true result', () => {
  const tempDir = freshTempDir();
  const sessionId = `mod-${randomUUID()}`;
  const itemId = '22222222-0000-0000-0000-000000000009';
  try {
    const res = spawnHook(
      { ...advanceCall(sessionId, 'agent-1', [okResult(itemId)]), to_mod_active: true },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
    // Control: the same payload without the flag does record.
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId)]), tempDir, UNREACHABLE_API_URL);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Fail-open ─────────────────────────────────────────────────────────────────────────────────

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
  try {
    const res = spawnHook(
      { session_id: sessionId, agent_id: 'agent-1', tool_response: { totally: 'unexpected shape' } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(res.status, 0);
    assert.equal(res.stdout.trim(), '{}');
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('fail-open: plain-text tool_response and a results-less envelope record nothing', () => {
  const tempDir = freshTempDir();
  const sessionId = `failopen2-${randomUUID()}`;
  try {
    const plain = spawnHook(
      { session_id: sessionId, agent_id: 'agent-1', tool_response: { content: [{ type: 'text', text: 'gate_blocked: boom' }], isError: true } },
      tempDir,
      UNREACHABLE_API_URL,
    );
    assert.equal(plain.status, 0);
    assert.equal(plain.stdout.trim(), '{}');
    for (const data of [undefined, {}, { results: null }, { results: [] }]) {
      const res = spawnHook(
        { session_id: sessionId, agent_id: 'agent-1', tool_response: { structuredContent: { ok: true, ...(data === undefined ? {} : { data }) } } },
        tempDir,
        UNREACHABLE_API_URL,
      );
      assert.equal(res.status, 0);
      assert.equal(res.stdout.trim(), '{}');
    }
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, []);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Accumulation across calls ─────────────────────────────────────────────────────────────────

test('accumulates itemIds across multiple advance calls for the same agent, deduping re-recorded items', () => {
  const tempDir = freshTempDir();
  const sessionId = `accum-${randomUUID()}`;
  const itemA = '22222222-0000-0000-0000-000000000008';
  const itemB = '33333333-0000-0000-0000-000000000009';
  try {
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemA, 'work')]), tempDir, UNREACHABLE_API_URL);
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemB, 'review')]), tempDir, UNREACHABLE_API_URL);
    // A gate-blocked re-entry of itemA (e.g. a bounced retry) must not duplicate it in the list.
    spawnHook(advanceCall(sessionId, 'agent-1', [gateBlocked(itemA, 'work')]), tempDir, UNREACHABLE_API_URL);
    const marker = readMarker(tempDir, sessionId, 'agent-1');
    assert.deepEqual([...marker.items].sort(), [itemA, itemB].sort());
    assert.deepEqual(marker.enteredRoles, { [itemA]: 'work', [itemB]: 'review' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('the same id twice inside one results[] is stored once', () => {
  const tempDir = freshTempDir();
  const sessionId = `dup-${randomUUID()}`;
  const itemId = '44444444-0000-0000-0000-00000000000c';
  try {
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId, 'work'), okResult(itemId, 'work')]), tempDir, UNREACHABLE_API_URL);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').items, [itemId]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('a later call for the same item updates enteredRoles to the newer role', () => {
  const tempDir = freshTempDir();
  const sessionId = `role3-${randomUUID()}`;
  const itemId = 'bb000000-0000-0000-0000-000000000003';
  try {
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId, 'work')]), tempDir, UNREACHABLE_API_URL);
    spawnHook(advanceCall(sessionId, 'agent-1', [okResult(itemId, 'review')]), tempDir, UNREACHABLE_API_URL);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-1').enteredRoles, { [itemId]: 'review' });
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

test('the marker caps at 50 recorded items', () => {
  const tempDir = freshTempDir();
  const sessionId = `cap-${randomUUID()}`;
  try {
    const results = Array.from({ length: 55 }, (_, i) => okResult(`cc000000-0000-0000-0000-${String(i).padStart(12, '0')}`));
    const res = spawnHook(advanceCall(sessionId, 'agent-1', results), tempDir, UNREACHABLE_API_URL);
    assert.equal(res.status, 0);
    assert.equal(readMarker(tempDir, sessionId, 'agent-1').items.length, 50);
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
    spawnHook(advanceCall(sessionId, 'agent-A', [okResult(itemA)]), tempDir, UNREACHABLE_API_URL);
    spawnHook(advanceCall(sessionId, 'agent-B', [okResult(itemB)]), tempDir, UNREACHABLE_API_URL);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-A').items, [itemA]);
    assert.deepEqual(readMarker(tempDir, sessionId, 'agent-B').items, [itemB]);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
});

// ── Direct unit coverage of the exported pure helpers (take a Parsed, lib/envelope.mjs) ───────

const parsedOf = (results) => parseToolResult(advanceResponse(results));

test('isFullUuid: accepts a full UUID, rejects a hex prefix / non-string / empty', () => {
  assert.equal(isFullUuid('aaaaaaaa-0000-0000-0000-000000000001'), true);
  assert.equal(isFullUuid('AAAAAAAA-0000-0000-0000-000000000001'), true);
  assert.equal(isFullUuid('ef07'), false);
  assert.equal(isFullUuid(''), false);
  assert.equal(isFullUuid(undefined), false);
  assert.equal(isFullUuid(123), false);
});

test('extractRecordableItemIds: ok:true and gate_blocked recorded; other codes, hex ids and codeless failures dropped, in order', () => {
  const parsed = parsedOf([
    okResult('aaaaaaaa-0000-0000-0000-000000000001'),
    okResult('ef07'),
    errResult('resource_unavailable', 'transient', { itemId: 'bbbbbbbb-0000-0000-0000-000000000002', role: 'work' }),
    gateBlocked('cccccccc-0000-0000-0000-000000000003', 'work'),
    { ok: false, error: { message: 'codeless', detail: { itemId: 'dddddddd-0000-0000-0000-000000000004', role: 'work' } } },
  ]);
  assert.deepEqual(extractRecordableItemIds(parsed), [
    'aaaaaaaa-0000-0000-0000-000000000001',
    'cccccccc-0000-0000-0000-000000000003',
  ]);
});

test('extractRecordableItemIds: reads results past index 0', () => {
  const parsed = parsedOf([errResult('not_found'), errResult('not_found'), okResult('aaaaaaaa-0000-0000-0000-00000000000f')]);
  assert.deepEqual(extractRecordableItemIds(parsed), ['aaaaaaaa-0000-0000-0000-00000000000f']);
});

test('extractRecordableItemIds: empty / missing results yields an empty array', () => {
  assert.deepEqual(extractRecordableItemIds(parseToolResult({ ok: true })), []);
  assert.deepEqual(extractRecordableItemIds(parseToolResult({ ok: true, data: { results: [] } })), []);
  assert.deepEqual(extractRecordableItemIds(null), []);
});

test('extractEnteredRoles: newRole on ok:true, detail.role on gate_blocked (never a requested target), omitted otherwise', () => {
  const parsed = parsedOf([
    okResult('aaaaaaaa-0000-0000-0000-000000000001', 'work'),
    // detail.role = the item's current role; a requested review target is not on the wire at all
    gateBlocked('bbbbbbbb-0000-0000-0000-000000000002', 'work'),
    errResult('dependency_unmet', 'permanent', { itemId: 'cccccccc-0000-0000-0000-000000000003', role: 'queue' }),
    okResult('ef07', 'work'), // non-UUID -- omitted regardless of role
  ]);
  assert.deepEqual(extractEnteredRoles(parsed), {
    'aaaaaaaa-0000-0000-0000-000000000001': 'work',
    'bbbbbbbb-0000-0000-0000-000000000002': 'work',
  });
});

test('extractEnteredRoles: empty / missing results yields an empty object', () => {
  assert.deepEqual(extractEnteredRoles(parseToolResult({ ok: true })), {});
  assert.deepEqual(extractEnteredRoles(parseToolResult({ ok: true, data: { results: [] } })), {});
  assert.deepEqual(extractEnteredRoles(null), {});
});

test('AC1: the removed actor helpers are no longer exported', async () => {
  const mod = await import('../phase-guard-record.mjs');
  for (const name of ['buildActorMap', 'actorForResult', 'isWorkflowSeatActor']) {
    assert.equal(name in mod, false, name);
  }
});
