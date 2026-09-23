// Unit coverage for ralph-lib.mjs — the pure helpers factored out of ralph-loop.mjs so they
// can be tested directly (ralph-loop.mjs runs CLI parsing and its main loop at module top
// level, including a top-level await and process.exit calls, so it isn't importable).
//
// Scenario ids (S1-S10) match the item's `specification` note's Verify section.

import { test } from "node:test";
import assert from "node:assert/strict";
import {
    parseClaudeJson,
    extractLastBalancedJson,
    scanBalancedObject,
    extractOutcomeMarker,
    parseOutcome,
    updateSpend,
    decideContinuation,
    buildResumeArgs,
    MIN_CONTINUATION_BUDGET_USD,
} from "../ralph-lib.mjs";

// ── parseOutcome / marker parsing ──────────────────────────────────────────

test("S1: parseOutcome extracts a multi-line RALPH_OUTCOME marker, markerFound true", () => {
    const envelope = {
        result: [
            "Some reasoning text before the marker.",
            "RALPH_OUTCOME: {",
            '  "status": "terminal",',
            '  "itemId": "44abe365-aaaa-bbbb-cccc-000000000000",',
            '  "summary": "done"',
            "}",
        ].join("\n"),
    };
    const { outcome, markerFound } = parseOutcome(envelope, 0);
    assert.equal(markerFound, true);
    assert.deepEqual(outcome, {
        status: "terminal",
        itemId: "44abe365-aaaa-bbbb-cccc-000000000000",
        summary: "done",
    });
});

test("S1: parseOutcome picks the LAST marker when one appears earlier in reasoning text (lastIndexOf)", () => {
    const envelope = {
        result: [
            'I might emit RALPH_OUTCOME: {"status": "error", "reason": "false start"} but let me keep going.',
            'RALPH_OUTCOME: {"status": "terminal", "itemId": "abc", "summary": "final"}',
        ].join("\n"),
    };
    const { outcome, markerFound } = parseOutcome(envelope, 0);
    assert.equal(markerFound, true);
    assert.equal(outcome.status, "terminal");
    assert.equal(outcome.summary, "final");
});

test("S1b: marker present with status error is markerFound true, and is NOT continuation-eligible", () => {
    const envelope = { result: 'RALPH_OUTCOME: {"status": "error", "itemId": "abc", "reason": "boom"}' };
    const { outcome, markerFound } = parseOutcome(envelope, 0);
    assert.equal(markerFound, true);
    assert.equal(outcome.status, "error");

    // decideContinuation must key on markerFound, never on outcome.status — a
    // deliberate RALPH_OUTCOME error marker must not be resumed even though
    // sessionId/budget/count all have headroom.
    const decision = decideContinuation({
        exitCode: 0,
        markerFound,
        sessionId: "sess-1",
        continuationsUsed: 0,
        maxContinuations: 2,
        budget: 5,
        spentUsd: 1,
    });
    assert.equal(decision.continue, false);
});

test("S10: malformed marker JSON counts as absent (markerFound false, continuation-eligible)", () => {
    const envelope = { result: "RALPH_OUTCOME: {not valid json}" };
    const { outcome, markerFound } = parseOutcome(envelope, 0);
    assert.equal(markerFound, false);
    assert.equal(outcome.status, "error");
    assert.match(outcome.reason, /without RALPH_OUTCOME marker/);
});

test("parseOutcome: no marker, exit 0 -> error/markerFound false with the exact reason text SKILL.md matches on", () => {
    const { outcome, markerFound } = parseOutcome({ result: "all done, no marker" }, 0);
    assert.equal(markerFound, false);
    assert.deepEqual(outcome, {
        status: "error",
        reason: "iteration agent exited cleanly without RALPH_OUTCOME marker",
    });
});

test("parseOutcome: non-zero exit with max_budget stop_reason", () => {
    const { outcome, markerFound } = parseOutcome({ result: "", stop_reason: "max_budget" }, 1);
    assert.equal(markerFound, false);
    assert.equal(outcome.status, "error");
    assert.match(outcome.reason, /max-budget-usd cap/);
});

test("parseOutcome: non-zero exit, unknown stop_reason", () => {
    const { outcome, markerFound } = parseOutcome({}, 17);
    assert.equal(markerFound, false);
    assert.match(outcome.reason, /exit 17/);
    assert.match(outcome.reason, /unknown/);
});

test("parseOutcome: missing/undefined envelope does not throw", () => {
    assert.doesNotThrow(() => parseOutcome({}, 0));
    assert.doesNotThrow(() => parseOutcome(undefined, 1));
    const { markerFound } = parseOutcome(undefined, 1);
    assert.equal(markerFound, false);
});

// ── parseClaudeJson / balanced-object scanning (moved, byte-identical logic) ──

test("parseClaudeJson: direct parse of compact JSON", () => {
    assert.deepEqual(parseClaudeJson('{"result":"hi","session_id":"s1"}'), {
        result: "hi",
        session_id: "s1",
    });
});

test("parseClaudeJson: falls back to the last balanced object after log noise", () => {
    const stdout = 'some log line\n{"a":1}\nmore noise {"result":"final","session_id":"s2"}';
    assert.deepEqual(parseClaudeJson(stdout), { result: "final", session_id: "s2" });
});

test("parseClaudeJson: empty/blank stdout returns {}", () => {
    assert.deepEqual(parseClaudeJson(""), {});
    assert.deepEqual(parseClaudeJson("   "), {});
});

test("parseClaudeJson: unparseable stdout returns {}", () => {
    assert.deepEqual(parseClaudeJson("not json at all"), {});
});

test("extractLastBalancedJson: returns null when no braces are present", () => {
    assert.equal(extractLastBalancedJson("no braces here"), null);
});

test("scanBalancedObject: tolerates braces inside strings", () => {
    const text = '{"a": "{ not a brace }", "b": 1}';
    const closeIdx = scanBalancedObject(text, 0);
    assert.equal(closeIdx, text.length - 1);
});

test("extractOutcomeMarker: returns null when the marker text is absent", () => {
    assert.equal(extractOutcomeMarker("nothing to see here"), null);
});

// ── updateSpend ─────────────────────────────────────────────────────────────

test("S9: spend is the max of latest total_cost_usd across resumes, never a sum (1.0, 1.6 -> 1.6, not 2.6)", () => {
    let spent = 0;
    spent = updateSpend(spent, { total_cost_usd: 1.0 });
    assert.equal(spent, 1.0);
    spent = updateSpend(spent, { total_cost_usd: 1.6 });
    assert.equal(spent, 1.6);
    assert.notEqual(spent, 2.6);
});

test("S9: missing or non-numeric total_cost_usd leaves spend unchanged", () => {
    let spent = 1.6;
    spent = updateSpend(spent, {});
    assert.equal(spent, 1.6);
    spent = updateSpend(spent, { total_cost_usd: "not-a-number" });
    assert.equal(spent, 1.6);
    spent = updateSpend(spent, undefined);
    assert.equal(spent, 1.6);
});

test("updateSpend: a stray lower reading never decreases tracked spend", () => {
    const spent = updateSpend(1.6, { total_cost_usd: 0.9 });
    assert.equal(spent, 1.6);
});

// ── decideContinuation ──────────────────────────────────────────────────────

function baseDecisionArgs(overrides = {}) {
    return {
        exitCode: 0,
        markerFound: false,
        sessionId: "sess-1",
        continuationsUsed: 0,
        maxContinuations: 2,
        budget: 5,
        spentUsd: 1,
        ...overrides,
    };
}

test("S2: eligible marker-less clean exit continues, remainingUsd = budget - spent", () => {
    const decision = decideContinuation(baseDecisionArgs());
    assert.equal(decision.continue, true);
    assert.equal(decision.remainingUsd, 4);
});

test("S3: continuationsUsed at the cap refuses, reason mentions continuations", () => {
    const decision = decideContinuation(baseDecisionArgs({ continuationsUsed: 2, maxContinuations: 2 }));
    assert.equal(decision.continue, false);
    assert.match(decision.reason, /continuation/);
});

test("S4: remaining below the $0.25 floor refuses", () => {
    const decision = decideContinuation(baseDecisionArgs({ budget: 5, spentUsd: 4.8 }));
    assert.ok(decision.remainingUsd < MIN_CONTINUATION_BUDGET_USD);
    assert.equal(decision.continue, false);
});

test("S4: remaining exactly at the $0.25 floor is still eligible", () => {
    const decision = decideContinuation(baseDecisionArgs({ budget: 5, spentUsd: 4.75 }));
    assert.equal(decision.remainingUsd, 0.25);
    assert.equal(decision.continue, true);
});

test("S5: missing session_id refuses regardless of budget/count headroom", () => {
    const decision = decideContinuation(baseDecisionArgs({ sessionId: undefined }));
    assert.equal(decision.continue, false);
    assert.match(decision.reason, /session_id/);
});

test("S6: non-zero exit refuses (existing error mapping, never continuation-eligible)", () => {
    const decision = decideContinuation(baseDecisionArgs({ exitCode: 1 }));
    assert.equal(decision.continue, false);
});

test("S7: maxContinuations 0 never continues, even on the very first check", () => {
    const decision = decideContinuation(baseDecisionArgs({ continuationsUsed: 0, maxContinuations: 0 }));
    assert.equal(decision.continue, false);
});

test("decideContinuation: markerFound true short-circuits every other condition", () => {
    const decision = decideContinuation(
        baseDecisionArgs({ markerFound: true, sessionId: undefined, exitCode: 1, spentUsd: 100 })
    );
    assert.equal(decision.continue, false);
});

// ── buildResumeArgs ─────────────────────────────────────────────────────────

test("S8: buildResumeArgs shape — resume flag, no --worktree, same settings/model/output-format/permission-mode, message last", () => {
    const cfg = { model: "sonnet" };
    const args = buildResumeArgs({
        sessionId: "sess-123",
        cfg,
        remainingBudget: 3.5,
        message: "keep going",
    });

    assert.equal(args[0], "-p");
    assert.deepEqual(args.slice(1, 3), ["--resume", "sess-123"]);
    assert.ok(!args.some((a) => typeof a === "string" && a.startsWith("--worktree")));

    const settingsIdx = args.indexOf("--settings");
    assert.ok(settingsIdx >= 0);
    assert.deepEqual(JSON.parse(args[settingsIdx + 1]), {
        outputStyle: "task-orchestrator:ralph-iteration",
    });

    const permIdx = args.indexOf("--permission-mode");
    assert.equal(args[permIdx + 1], "bypassPermissions");

    const budgetIdx = args.indexOf("--max-budget-usd");
    assert.equal(args[budgetIdx + 1], "3.5");

    const formatIdx = args.indexOf("--output-format");
    assert.equal(args[formatIdx + 1], "json");

    const modelIdx = args.indexOf("--model");
    assert.equal(args[modelIdx + 1], "sonnet");

    assert.equal(args[args.length - 1], "keep going");
});
