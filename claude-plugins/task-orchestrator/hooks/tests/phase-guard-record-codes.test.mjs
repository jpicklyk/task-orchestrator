// Coverage for the recording rule of phase-guard-record.mjs on the 4.0 envelope (0bbdc4cc AC3;
// supersedes the 3.x c39fe915 payloads). Test author actor id: test-author:0bbdc4cc.
//
// Oracle: task-scope AC3 -- a results[] element is recordable iff its id is a full UUID AND
//   (r.ok === true with a string r.newRole, id = r.itemId)
//   OR (r.ok === false AND r.error.code === 'gate_blocked', id = r.error.detail.itemId).
// Any other error code, or ok:false without a code, is NOT recorded; no applied/errorCode field is
// read. Scenario S14 (EXISTING-SURFACE): extractRecordableItemIds already existed; this file keeps
// its S14 numbering and re-expresses each case as a Parsed (lib/envelope.mjs parseToolResult).
//
// Direct import of the pure helper -- no subprocess needed since extractRecordableItemIds does no I/O.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractRecordableItemIds, isFullUuid } from '../phase-guard-record.mjs';
import { parseToolResult } from '../lib/envelope.mjs';

const UUID_A = 'aaaaaaaa-1111-1111-1111-000000000001';
const UUID_B = 'bbbbbbbb-2222-2222-2222-000000000002';
const UUID_C = 'cccccccc-3333-3333-3333-000000000003';
const UUID_D = 'dddddddd-4444-4444-4444-000000000004';
const UUID_E = 'eeeeeeee-5555-5555-5555-000000000005';
const UUID_F = 'ffffffff-6666-6666-6666-000000000006';

/** A Parsed (lib/envelope.mjs) for an advance envelope carrying these results[] elements. */
function parsed(results) {
  const p = parseToolResult({ structuredContent: { ok: true, data: { results } } });
  assert.ok(p, 'fixture must parse as an envelope');
  assert.equal(p.legacy, false);
  return p;
}

const okR = (itemId, newRole = 'work') => ({ ok: true, itemId, newRole });
const failR = (code, kind, detail) => ({
  ok: false,
  error: { ...(code === null ? {} : { code }), kind, message: 'fixture', ...(detail ? { detail } : {}) },
});

// ── S14: ok:true is always recorded, whatever else the element carries ───────────────────────

test('S14: ok:true with a newRole and no error at all is recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([okR(UUID_A)])), [UUID_A]);
});

test('S14: ok:true is recorded even if an error object happens to be present alongside it', () => {
  // Defensive: ok:true wins, an error field on a success element is irrelevant.
  const el = { ok: true, itemId: UUID_A, newRole: 'work', error: { code: 'dependency_unmet', kind: 'permanent', message: 'm' } };
  assert.deepEqual(extractRecordableItemIds(parsed([el])), [UUID_A]);
});

// ── S14: ok:false with error.code gate_blocked is recorded (already-in-phase) ────────────────

test('S14: ok:false with error.code gate_blocked is recorded, id from error.detail.itemId', () => {
  const el = failR('gate_blocked', 'permanent', { itemId: UUID_B, role: 'work' });
  assert.deepEqual(extractRecordableItemIds(parsed([el])), [UUID_B]);
});

test('S14: gate_blocked without detail.itemId has no id to record', () => {
  const el = failR('gate_blocked', 'permanent', { role: 'work' });
  assert.deepEqual(extractRecordableItemIds(parsed([el])), []);
});

// ── S14: every other error code is NOT recorded ───────────────────────────────────────────────

test('S14: ok:false with error.code dependency_unmet is not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('dependency_unmet', 'permanent', { itemId: UUID_C, role: 'queue' })])), []);
});

test('S14: ok:false with error.code not_found is not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('not_found', 'permanent', { itemId: UUID_D })])), []);
});

test('S14: ok:false with error.code resource_unavailable is not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('resource_unavailable', 'transient', { itemId: UUID_E, role: 'work' })])), []);
});

test('S14: ok:false with error.code invalid_transition is not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('invalid_transition', 'permanent', { itemId: UUID_F, role: 'work' })])), []);
});

test('S14: a code that merely contains gate_blocked (gate_blocked_x, Gate_Blocked) is not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('gate_blocked_x', 'permanent', { itemId: UUID_A, role: 'work' })])), []);
  assert.deepEqual(extractRecordableItemIds(parsed([failR('Gate_Blocked', 'permanent', { itemId: UUID_A, role: 'work' })])), []);
});

// ── S14: a codeless ok:false is not recorded ──────────────────────────────────────────────────

test('S14: a codeless ok:false element is NOT recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR(null, 'permanent', { itemId: UUID_A, role: 'work' })])), []);
  assert.deepEqual(extractRecordableItemIds(parsed([{ ok: false, itemId: UUID_A }])), []);
});

// ── S14: a non-UUID itemId is never recorded, ok:true or not ─────────────────────────────────

test('S14: a hex-prefix itemId is not recorded even when ok:true', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([okR('ef07')])), []);
});

test('S14: a hex-prefix detail.itemId with error.code gate_blocked is still not recorded', () => {
  assert.deepEqual(extractRecordableItemIds(parsed([failR('gate_blocked', 'permanent', { itemId: 'ef07', role: 'work' })])), []);
});

// ── S14: a mixed batch keeps exactly the ok:true and gate_blocked entries, in order ──────────

test('S14: a mixed batch of six results keeps only ok:true and gate_blocked entries', () => {
  const results = [
    okR(UUID_A),
    failR('gate_blocked', 'permanent', { itemId: UUID_B, role: 'work' }),
    failR('dependency_unmet', 'permanent', { itemId: UUID_C, role: 'queue' }),
    failR('not_found', 'permanent', { itemId: UUID_D }),
    failR('resource_unavailable', 'transient', { itemId: UUID_E, role: 'work' }),
    failR(null, 'permanent', { itemId: UUID_F, role: 'work' }),
  ];
  assert.deepEqual(extractRecordableItemIds(parsed(results)), [UUID_A, UUID_B]);
});

// ── Sanity: isFullUuid still gates every case above the same way it always has ────────────────

test('isFullUuid: still accepts a full UUID and rejects a hex prefix (unchanged)', () => {
  assert.equal(isFullUuid(UUID_A), true);
  assert.equal(isFullUuid('ef07'), false);
});
