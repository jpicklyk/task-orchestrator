// Blind test-author coverage for c39fe915 (phase-guard-record.mjs: extractRecordableItemIds
// keeps recording only applied:true or errorCode "gate_blocked" -- any other errorCode, or a
// codeless applied:false, is no longer recorded). Test author actor id: test-author:c39fe915.
//
// Oracle (O-D): dispatch DECLARATIONS block for phase-guard-record.mjs --
//   "Records an item iff r.itemId is a full UUID AND (r.applied === true OR
//    r.errorCode === 'gate_blocked'). Any other errorCode, or a codeless applied:false, is NOT
//    recorded."
//
// Scenario S14 (test-plan, section "node --test"): EXISTING-SURFACE -- extractRecordableItemIds
// already existed; this fix tightens its recording rule. Narrowest-revert recipe per test-plan:
// revert the rule back to "record unless errorCode is present" and this file goes red on the
// codeless-applied-false and gate_blocked cases (they swap expected membership).
//
// This uses direct import of the pure helper, matching the existing "Direct unit coverage of the
// exported pure helpers" section of phase-guard-record.test.mjs -- no subprocess needed since
// extractRecordableItemIds does no I/O.

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractRecordableItemIds, isFullUuid } from '../phase-guard-record.mjs';

const UUID_A = 'aaaaaaaa-1111-1111-1111-000000000001';
const UUID_B = 'bbbbbbbb-2222-2222-2222-000000000002';
const UUID_C = 'cccccccc-3333-3333-3333-000000000003';
const UUID_D = 'dddddddd-4444-4444-4444-000000000004';
const UUID_E = 'eeeeeeee-5555-5555-5555-000000000005';
const UUID_F = 'ffffffff-6666-6666-6666-000000000006';

// ── S14: applied:true is always recorded, regardless of any other field ─────────────────────

test('S14: applied:true with no errorCode at all is recorded', () => {
  const payload = { results: [{ itemId: UUID_A, applied: true, newRole: 'work' }] };
  assert.deepEqual(extractRecordableItemIds(payload), [UUID_A]);
});

test('S14: applied:true is recorded even if an errorCode happens to be present alongside it', () => {
  // Defensive: applied:true always wins, the errorCode field is irrelevant on a success result.
  const payload = { results: [{ itemId: UUID_A, applied: true, errorCode: 'gate_blocked' }] };
  assert.deepEqual(extractRecordableItemIds(payload), [UUID_A]);
});

// ── S14: applied:false with errorCode "gate_blocked" is recorded (already-in-phase) ──────────

test('S14: applied:false with errorCode gate_blocked is recorded', () => {
  const payload = {
    results: [
      {
        itemId: UUID_B,
        applied: false,
        error: 'Item is already in work',
        errorCode: 'gate_blocked',
        errorKind: 'permanent',
        previousRole: 'work',
        targetRole: 'work',
      },
    ],
  };
  assert.deepEqual(extractRecordableItemIds(payload), [UUID_B]);
});

// ── S14: every other errorCode is NOT recorded ────────────────────────────────────────────────

test('S14: applied:false with errorCode dependency_blocked is not recorded', () => {
  const payload = {
    results: [{ itemId: UUID_C, applied: false, errorCode: 'dependency_blocked', errorKind: 'permanent' }],
  };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

test('S14: applied:false with errorCode item_not_found is not recorded', () => {
  const payload = {
    results: [{ itemId: UUID_D, applied: false, errorCode: 'item_not_found', errorKind: 'permanent' }],
  };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

test('S14: applied:false with errorCode resource_unavailable is not recorded', () => {
  const payload = {
    results: [{ itemId: UUID_E, applied: false, errorCode: 'resource_unavailable', errorKind: 'transient' }],
  };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

test('S14: applied:false with errorCode invalid_transition is not recorded', () => {
  const payload = {
    results: [{ itemId: UUID_F, applied: false, errorCode: 'invalid_transition', errorKind: 'permanent' }],
  };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

// ── S14: a codeless applied:false is no longer recorded (the rule this fix tightens) ─────────

test('S14: a codeless applied:false result is NOT recorded (tightened from the pre-fix rule)', () => {
  const payload = {
    results: [{ itemId: UUID_A, applied: false, error: 'already in phase, no code' }],
  };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

// ── S14: a non-UUID itemId is never recorded, applied:true or not ────────────────────────────

test('S14: a hex-prefix itemId is not recorded even when applied:true', () => {
  const payload = { results: [{ itemId: 'ef07', applied: true, newRole: 'work' }] };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

test('S14: a hex-prefix itemId with errorCode gate_blocked is still not recorded', () => {
  const payload = { results: [{ itemId: 'ef07', applied: false, errorCode: 'gate_blocked' }] };
  assert.deepEqual(extractRecordableItemIds(payload), []);
});

// ── S14: a mixed batch keeps exactly the applied:true and gate_blocked entries, in order ─────

test('S14: a mixed batch of six results keeps only applied:true and gate_blocked entries', () => {
  const payload = {
    results: [
      { itemId: UUID_A, applied: true, newRole: 'work' },
      { itemId: UUID_B, applied: false, errorCode: 'gate_blocked', errorKind: 'permanent' },
      { itemId: UUID_C, applied: false, errorCode: 'dependency_blocked', errorKind: 'permanent' },
      { itemId: UUID_D, applied: false, errorCode: 'item_not_found', errorKind: 'permanent' },
      { itemId: UUID_E, applied: false, errorCode: 'resource_unavailable', errorKind: 'transient' },
      { itemId: UUID_F, applied: false, error: 'codeless, no errorCode' },
    ],
  };
  assert.deepEqual(extractRecordableItemIds(payload), [UUID_A, UUID_B]);
});

// ── Sanity: isFullUuid still gates every case above the same way it always has ────────────────

test('isFullUuid: still accepts a full UUID and rejects a hex prefix (unchanged by this fix)', () => {
  assert.equal(isFullUuid(UUID_A), true);
  assert.equal(isFullUuid('ef07'), false);
});
