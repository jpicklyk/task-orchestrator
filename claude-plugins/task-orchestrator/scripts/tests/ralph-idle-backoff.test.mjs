// Blind test-author coverage for c39fe915 (ralph-lib.mjs: decideIdleBackoff, the pure helper that
// decides whether ralph-loop.mjs backs off and retries on a "none_eligible" claim outcome, or
// gives up and exits after its idle budget). Test author actor id: test-author:c39fe915.
//
// Oracle (O-D): dispatch DECLARATIONS block --
//   "consecutiveIdle >= idleBudget -> {exit:true, waitMs:0} (including the very first idle when
//    idleBudget is 0). Otherwise waitMs = retryAfterMs when it is a non-negative finite number,
//    else DEFAULT_IDLE_BACKOFF_MS; then clamped to [MIN_IDLE_BACKOFF_MS, MAX_IDLE_BACKOFF_MS];
//    returns {exit:false, waitMs}."
//   DEFAULT_IDLE_BACKOFF_MS = 30_000; MIN_IDLE_BACKOFF_MS = 1_000; MAX_IDLE_BACKOFF_MS = 300_000.
// Direct match to test-plan S16's worked values.
//
// Scenario S16 (test-plan, section "node --test"): NEW-SURFACE (decideIdleBackoff is a function
// this fix introduces -- there is nothing to revert it back to). Per test-plan: "no behavioral
// revert (new fn): reviewer confirms ralph-loop routes 'idle' through it; iteration-prompt maps
// none_eligible->idle, queue_empty->no-item." Substitute verification recorded in test-manifest.
//
// A pure function with no I/O -- imported and called directly, matching ralph-lib.test.mjs's
// existing style (no subprocess needed).

import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  decideIdleBackoff,
  DEFAULT_IDLE_BACKOFF_MS,
  MIN_IDLE_BACKOFF_MS,
  MAX_IDLE_BACKOFF_MS,
  NEXT_EMPTY_WAIT_MS,
  TRANSIENT_SKIP_CODES,
  addSkip,
  isSkipped,
  decideSelection,
} from '../ralph-lib.mjs';

// ── Sanity: the three constants match the oracle's literal values ────────────────────────────

test('S16: exported backoff constants match the declared literal values', () => {
  assert.equal(DEFAULT_IDLE_BACKOFF_MS, 30000);
  assert.equal(MIN_IDLE_BACKOFF_MS, 1000);
  assert.equal(MAX_IDLE_BACKOFF_MS, 300000);
});

// ── S16: ordinary retry — consecutiveIdle below budget, retryAfterMs passed through ───────────

test('S16: retryAfterMs 30000 with consecutiveIdle 1 under budget 3 continues with waitMs 30000', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 30000, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: 30000 });
});

// ── S16: budget exhausted — consecutiveIdle equal to idleBudget exits ─────────────────────────

test('S16: consecutiveIdle 3 at idleBudget 3 exits with waitMs 0', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 30000, consecutiveIdle: 3, idleBudget: 3 });
  assert.deepEqual(decision, { exit: true, waitMs: 0 });
});

test('S16: consecutiveIdle exceeding idleBudget also exits with waitMs 0', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 5000, consecutiveIdle: 4, idleBudget: 3 });
  assert.deepEqual(decision, { exit: true, waitMs: 0 });
});

// ── S16: idleBudget 0 exits on the very first idle ────────────────────────────────────────────

test('S16: idleBudget 0 exits on the first idle (consecutiveIdle 1)', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 30000, consecutiveIdle: 1, idleBudget: 0 });
  assert.deepEqual(decision, { exit: true, waitMs: 0 });
});

// ── S16: retryAfterMs fallback to DEFAULT_IDLE_BACKOFF_MS for non-numeric/invalid values ──────

test('S16: retryAfterMs undefined falls back to DEFAULT_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: undefined, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: DEFAULT_IDLE_BACKOFF_MS });
});

test('S16: retryAfterMs NaN falls back to DEFAULT_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: NaN, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: DEFAULT_IDLE_BACKOFF_MS });
});

test('S16: retryAfterMs -5 (negative) falls back to DEFAULT_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: -5, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: DEFAULT_IDLE_BACKOFF_MS });
});

// FINDING (arbitration case, recorded in test-manifest): this probe is NOT one of test-plan
// S16's declared worked values (undefined/NaN/-5/1e9/10/budget-0) -- it is an adversarial
// boundary probe (Infinity as a non-finite "number") added under the test-authoring skill's
// probe catalog. Observed: decideIdleBackoff({retryAfterMs: Infinity, ...}) returns
// {exit:false, waitMs: MAX_IDLE_BACKOFF_MS}, not the DEFAULT_IDLE_BACKOFF_MS the oracle text
// ("non-negative finite number, else DEFAULT_IDLE_BACKOFF_MS") states for a non-finite value.
// This assertion is left as written and RED, per the test-author protocol's rule against
// weakening a failing assertion -- it was not derived from reading the implementation, only
// from the oracle text, and the implementation was executed (never read) to observe this.
test('S16: retryAfterMs Infinity (not finite) falls back to DEFAULT_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: Infinity, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: DEFAULT_IDLE_BACKOFF_MS });
});

// ── S16: clamping to [MIN_IDLE_BACKOFF_MS, MAX_IDLE_BACKOFF_MS] ───────────────────────────────

test('S16: retryAfterMs 1e9 is clamped down to MAX_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 1e9, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: MAX_IDLE_BACKOFF_MS });
});

test('S16: retryAfterMs 10 is clamped up to MIN_IDLE_BACKOFF_MS', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 10, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: MIN_IDLE_BACKOFF_MS });
});

test('S16: retryAfterMs exactly MIN_IDLE_BACKOFF_MS passes through unclamped', () => {
  const decision = decideIdleBackoff({ retryAfterMs: MIN_IDLE_BACKOFF_MS, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: MIN_IDLE_BACKOFF_MS });
});

test('S16: retryAfterMs exactly MAX_IDLE_BACKOFF_MS passes through unclamped', () => {
  const decision = decideIdleBackoff({ retryAfterMs: MAX_IDLE_BACKOFF_MS, consecutiveIdle: 1, idleBudget: 3 });
  assert.deepEqual(decision, { exit: false, waitMs: MAX_IDLE_BACKOFF_MS });
});

// ── S16: retryAfterMs 0 is a valid non-negative finite number, not a fallback trigger ─────────

test('S16: retryAfterMs 0 is treated as a valid value (clamped up to MIN_IDLE_BACKOFF_MS), not a fallback trigger', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 0, consecutiveIdle: 1, idleBudget: 3 });
  // 0 is non-negative and finite, so per the oracle it is NOT replaced by the default; it is
  // then clamped up to MIN_IDLE_BACKOFF_MS same as any other too-small positive value.
  assert.deepEqual(decision, { exit: false, waitMs: MIN_IDLE_BACKOFF_MS });
});

// ── S16: the exit path ignores retryAfterMs entirely and always reports waitMs 0 ──────────────

test('S16: when the budget is exhausted, waitMs is 0 regardless of how large retryAfterMs is', () => {
  const decision = decideIdleBackoff({ retryAfterMs: 1e9, consecutiveIdle: 5, idleBudget: 3 });
  assert.deepEqual(decision, { exit: true, waitMs: 0 });
});

// === cae5d141: per-loop skip list and item selection (S4, S5) ========================================
// Test author actor id: test-author:cae5d141:r-202610170015-cae5d141. Oracle: task-scope D7 (AR-76 skip list,
// AR-66 "an empty page with a reason is waiting, never drained") and api-reference items_read select next
// ("Empty with reason claim_held or dependency_unmet when matches exist but none is ready"; nothing matched
// at all has no reason and a drain worker should stop). NEW-SURFACE: addSkip / isSkipped / decideSelection.
// Pure functions, called directly.

const NOW = 1_700_000_000_000;
const cand = (id) => ({ id, type: 'feature-task', title: id, role: 'queue', depth: 2, version: 1 });
const kindOfSelection = (r) => ['pick', 'idle', 'drained'].filter((k) => r[k] !== undefined && r[k] !== false && r[k] !== null);
// the declaration names `pick` without saying whether it is the candidate or its id: accept either shape
const pickedId = (r) => (typeof r.pick === 'string' ? r.pick : r.pick.id);

// -- S5: addSkip --

test('S5: the transient skip codes are claim_held and resource_unavailable', () => {
  assert.deepEqual([...TRANSIENT_SKIP_CODES].sort(), ['claim_held', 'resource_unavailable']);
});

test('S5: claim_held with retryAfterMs 60000 skips until now + 60000, as a {itemId, code, until} row', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  assert.deepEqual(list, [{ itemId: 'aaaa1111', code: 'claim_held', until: NOW + 60000 }]);
});

test('S5: resource_unavailable honours retryAfterMs too', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'resource_unavailable', retryAfterMs: 45000, now: NOW });
  assert.equal(list[0].until, NOW + 45000);
});

test('S5: an absent, NaN, negative or non-numeric retryAfterMs falls back to 30000 for a transient code', () => {
  for (const retryAfterMs of [undefined, NaN, -5, 'soon', null]) {
    const list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs, now: NOW });
    assert.equal(list[0].until, NOW + 30000, String(retryAfterMs));
  }
});

test('S5 probe: an Infinity retryAfterMs is not a usable number and falls back to 30000', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'resource_unavailable', retryAfterMs: Infinity, now: NOW });
  assert.equal(list[0].until, NOW + 30000);
});

test('S5 probe: a huge retryAfterMs (1e12) is kept as given - only decideSelection clamps', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1e12, now: NOW });
  assert.equal(list[0].until, NOW + 1e12);
});

// NOT asserted (ambiguity escalated in test-manifest): retryAfterMs 0. TS D7 says "absent/invalid -> 30000" without
// saying whether 0 is valid; the test-plan lists the probe without an oracle.

test('S5: invalid_transition, not_found and duplicate skip forever (until = Infinity), whatever retryAfterMs says', () => {
  for (const code of ['invalid_transition', 'not_found', 'duplicate']) {
    for (const retryAfterMs of [undefined, 5000]) {
      const list = addSkip([], { itemId: 'aaaa1111', code, retryAfterMs, now: NOW });
      assert.equal(list[0].until, Infinity, `${code} ${retryAfterMs}`);
      assert.equal(list[0].code, code);
    }
  }
});

test('S5: the skipped item id is lowercased', () => {
  const list = addSkip([], { itemId: 'AAAA1111', code: 'claim_held', retryAfterMs: 1000, now: NOW });
  assert.equal(list[0].itemId, 'aaaa1111');
});

test('S5: addSkip does not mutate its input list and appends the new row', () => {
  const first = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1000, now: NOW });
  const snapshot = JSON.parse(JSON.stringify(first));
  const second = addSkip(first, { itemId: 'bbbb2222', code: 'duplicate', now: NOW });
  assert.deepEqual(first, snapshot, 'the input list is untouched');
  assert.equal(second.length, 2);
  assert.equal(second[0].itemId, 'aaaa1111');
  assert.equal(second[1].itemId, 'bbbb2222');
});

test('S5 probe: a permanent skip added after a transient one for the same item keeps it skipped far into the future', () => {
  let list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1000, now: NOW });
  list = addSkip(list, { itemId: 'aaaa1111', code: 'invalid_transition', now: NOW });
  assert.equal(isSkipped(list, 'aaaa1111', NOW + 1e9), true);
});

// -- S5: isSkipped --

test('S5: isSkipped is true while until > now, false at until == now and after it', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  assert.equal(isSkipped(list, 'aaaa1111', NOW), true);
  assert.equal(isSkipped(list, 'aaaa1111', NOW + 59999), true);
  assert.equal(isSkipped(list, 'aaaa1111', NOW + 60000), false, 'until == now is free');
  assert.equal(isSkipped(list, 'aaaa1111', NOW + 60001), false);
});

test('S5: isSkipped is false for an item that was never skipped and for an empty list', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  assert.equal(isSkipped(list, 'bbbb2222', NOW), false);
  assert.equal(isSkipped([], 'aaaa1111', NOW), false);
});

test('S5: an Infinity skip holds at any finite time', () => {
  const list = addSkip([], { itemId: 'aaaa1111', code: 'duplicate', now: NOW });
  assert.equal(isSkipped(list, 'aaaa1111', NOW + 1e15), true);
});

test('S5 probe (case): ids match regardless of case', () => {
  const list = addSkip([], { itemId: 'ABCDEF12', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  assert.equal(isSkipped(list, 'abcdef12', NOW), true);
  assert.equal(isSkipped(list, 'ABCDEF12', NOW), true);
});

// -- S4: decideSelection --

test('S4: the first candidate that is not skipped is picked', () => {
  const skipList = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222'), cand('cccc3333')], reason: undefined, skipList, now: NOW });
  assert.deepEqual(kindOfSelection(r), ['pick']);
  assert.equal(pickedId(r), 'bbbb2222');
});

test('S4: with nothing skipped the first (oldest) candidate is picked', () => {
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222')], skipList: [], now: NOW });
  assert.equal(pickedId(r), 'aaaa1111');
});

test('S4: a skip that has expired no longer hides its item', () => {
  const skipList = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1000, now: NOW });
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222')], skipList, now: NOW + 5000 });
  assert.equal(pickedId(r), 'aaaa1111');
});

test('S4: an empty page with no reason is drained (nothing matched at all)', () => {
  for (const reason of [undefined, null]) {
    const r = decideSelection({ candidates: [], reason, skipList: [], now: NOW });
    assert.deepEqual(kindOfSelection(r), ['drained'], String(reason));
  }
});

test('S4 AR-66: an empty page with reason claim_held or dependency_unmet is idle for 30000 ms, never drained', () => {
  assert.equal(NEXT_EMPTY_WAIT_MS, 30000);
  for (const reason of ['claim_held', 'dependency_unmet']) {
    const r = decideSelection({ candidates: [], reason, skipList: [], now: NOW });
    assert.deepEqual(kindOfSelection(r), ['idle'], reason);
    assert.equal(r.waitMs, 30000, reason);
    assert.equal(r.reason, reason, 'the idle answer names why');
  }
});

test('S4: when every candidate is skipped the loop idles until the EARLIEST skip expires', () => {
  let skipList = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 90000, now: NOW });
  skipList = addSkip(skipList, { itemId: 'bbbb2222', code: 'resource_unavailable', retryAfterMs: 60000, now: NOW });
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222')], skipList, now: NOW });
  assert.deepEqual(kindOfSelection(r), ['idle']);
  assert.equal(r.waitMs, 60000);
});

test('S4 boundary: the all-skipped wait is clamped to [MIN_IDLE_BACKOFF_MS, MAX_IDLE_BACKOFF_MS]', () => {
  const soon = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 200, now: NOW });
  assert.equal(decideSelection({ candidates: [cand('aaaa1111')], skipList: soon, now: NOW }).waitMs, MIN_IDLE_BACKOFF_MS);
  const far = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1e7, now: NOW });
  assert.equal(decideSelection({ candidates: [cand('aaaa1111')], skipList: far, now: NOW }).waitMs, MAX_IDLE_BACKOFF_MS);
  const exact = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 1000, now: NOW });
  assert.equal(decideSelection({ candidates: [cand('aaaa1111')], skipList: exact, now: NOW }).waitMs, 1000);
});

test('S4: a finite skip beside an Infinity one sets the wait (the earliest until wins)', () => {
  const forever = addSkip([], { itemId: 'aaaa1111', code: 'duplicate', now: NOW });
  const mixed = addSkip(forever, { itemId: 'bbbb2222', code: 'claim_held', retryAfterMs: 20000, now: NOW });
  assert.equal(decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222')], skipList: mixed, now: NOW }).waitMs, 20000);
});

// FINDING (arbitration, recorded in test-manifest): TS D7 states the all-skipped rule as one formula - idle with
// waitMs = earliest until - now clamped to [1000, 300000]. For skips that never expire (until = Infinity) that
// formula gives MAX_IDLE_BACKOFF_MS. This assertion is left as the oracle states it and is RED if the
// implementation answers anything else (observed by execution only: {drained: true}). Whether the spec or the
// implementation is right is decided elsewhere; a permanently skipped page that ends the drain is arguably
// the better behaviour, but that is a spec change, not a test change.
test('S4 D7 formula: an all-skipped page whose skips never expire is idle for MAX_IDLE_BACKOFF_MS', () => {
  const forever = addSkip([], { itemId: 'aaaa1111', code: 'duplicate', now: NOW });
  const r = decideSelection({ candidates: [cand('aaaa1111')], skipList: forever, now: NOW });
  assert.deepEqual(kindOfSelection(r), ['idle']);
  assert.equal(r.waitMs, MAX_IDLE_BACKOFF_MS);
});

test('S4: members restrict the pick to the --items set', () => {
  // a Set that also answers .includes, so the test holds whichever collection interface the helper uses
  class Members extends Set {
    includes(x) {
      return this.has(x);
    }
  }
  const members = new Members(['bbbb2222']);
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222')], skipList: [], members, now: NOW });
  assert.deepEqual(kindOfSelection(r), ['pick']);
  assert.equal(pickedId(r), 'bbbb2222');
});

test('S4: the skip list is respected inside the members set too', () => {
  class Members extends Set {
    includes(x) {
      return this.has(x);
    }
  }
  const members = new Members(['bbbb2222', 'cccc3333']);
  const skipList = addSkip([], { itemId: 'bbbb2222', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  const r = decideSelection({ candidates: [cand('aaaa1111'), cand('bbbb2222'), cand('cccc3333')], skipList, members, now: NOW });
  assert.equal(pickedId(r), 'cccc3333');
});

test('S4 probe: decideSelection does not mutate the candidate page or the skip list', () => {
  const candidates = [cand('aaaa1111'), cand('bbbb2222')];
  const skipList = addSkip([], { itemId: 'aaaa1111', code: 'claim_held', retryAfterMs: 60000, now: NOW });
  const snap = JSON.stringify({ candidates, skipList });
  decideSelection({ candidates, skipList, now: NOW });
  assert.equal(JSON.stringify({ candidates, skipList }), snap);
});
