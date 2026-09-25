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
