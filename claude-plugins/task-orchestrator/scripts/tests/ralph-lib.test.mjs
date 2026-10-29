// Unit coverage for ralph-lib.mjs — the pure helpers factored out of ralph-loop.mjs so they
// can be tested directly (ralph-loop.mjs runs CLI parsing and its main loop at module top
// level, including a top-level await and process.exit calls, so it isn't importable).
//
// Scenario ids (S1-S10) match the item's `specification` note's Verify section.

import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import {
    parseClaudeJson,
    extractLastBalancedJson,
    scanBalancedObject,
    extractOutcomeMarker,
    parseOutcome,
    updateSpend,
    decideContinuation,
    buildResumeArgs,
    buildIterationArgs,
    ITERATION_SYSTEM_PROMPT_PATH,
    MIN_CONTINUATION_BUDGET_USD,
    buildIterationEnv,
    HEADLESS_ITERATION_MODE,
    worktreeNameForOutcome,
    resumeCwd,
    renameWorktree,
    maybeCleanupWorktree,
    SEAT_TOKEN_FILE_ENV,
    DEFAULT_ITERATION_TIMEOUT_SECONDS,
    KILL_GRACE_MS,
    NEXT_EMPTY_WAIT_MS,
    RALPH_SEAT,
    REDACTED_TOKEN,
    FILTER_KEYS,
    parseFilter,
    buildNextQuery,
    buildRunOpenOp,
    TRANSIENT_SKIP_CODES,
    OUTCOME_TABLE,
    renderOutcomeTable,
    endSeatOutcome,
    loopEffect,
    buildUsageOps,
    redactTokens,
    seatTokenFilePath,
    firstOpResult,
} from "../ralph-lib.mjs";
import { ERROR_KINDS } from "../../hooks/lib/tool-names.mjs";

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
    assert.deepEqual(JSON.parse(args[settingsIdx + 1]), { outputStyle: "default" });
    assert.equal(args[args.indexOf("--append-system-prompt-file") + 1], ITERATION_SYSTEM_PROMPT_PATH);
    assert.ok(!args.some((a) => String(a).includes("ralph-iteration")));

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

// ── buildIterationArgs ──────────────────────────────────────────────────────

const iterCfg = { budget: 5, model: "sonnet" };
const iterArgs = () => buildIterationArgs({ worktreeName: "wt-1", cfg: iterCfg, prompt: "do it" });
const resumeArgs = () => buildResumeArgs({ sessionId: "s", cfg: iterCfg, remainingBudget: 2, message: "m" });
const flagVal = (args, flag) => args[args.indexOf(flag) + 1];

test("G1: buildIterationArgs shape", () => {
    const args = iterArgs();
    assert.equal(args[0], "-p");
    assert.ok(args.includes("--worktree=wt-1"));
    assert.deepEqual(JSON.parse(flagVal(args, "--settings")), { outputStyle: "default" });
    const p = flagVal(args, "--append-system-prompt-file");
    assert.ok(p.endsWith("iteration-system-prompt.md"));
    assert.ok(fs.existsSync(p));
    assert.ok(path.isAbsolute(p));
    assert.equal(flagVal(args, "--max-budget-usd"), "5");
    assert.equal(flagVal(args, "--model"), "sonnet");
    assert.equal(flagVal(args, "--output-format"), "json");
    assert.equal(flagVal(args, "--permission-mode"), "bypassPermissions");
    assert.equal(args[args.length - 1], "do it");
    assert.ok(!args.some((a) => String(a).includes("ralph-iteration")));
});

test("G3: initial and resume argv carry identical system-prompt and settings values", () => {
    for (const flag of ["--append-system-prompt-file", "--settings"]) {
        assert.equal(flagVal(iterArgs(), flag), flagVal(resumeArgs(), flag));
    }
});

test("G4: supplied systemPromptPath is passed through verbatim", () => {
    const a = buildIterationArgs({ worktreeName: "w", cfg: iterCfg, prompt: "p", systemPromptPath: "/x/y.md" });
    const r = buildResumeArgs({ sessionId: "s", cfg: iterCfg, remainingBudget: 1, message: "m", systemPromptPath: "/x/y.md" });
    assert.equal(flagVal(a, "--append-system-prompt-file"), "/x/y.md");
    assert.equal(flagVal(r, "--append-system-prompt-file"), "/x/y.md");
});

test("G5: iteration-system-prompt.md has no frontmatter and carries the key rules", () => {
    const body = fs.readFileSync(ITERATION_SYSTEM_PROMPT_PATH, "utf8");
    assert.ok(!body.startsWith("---"));
    assert.ok(body.includes("RALPH_OUTCOME"));
    assert.ok(body.includes("How your turn ends"));
});

// ── 004d65fd S3: buildIterationEnv ────────────────────────────────────────────────────────────
// Oracle: item 004d65fd's specification, "Design" section 1 — `buildIterationEnv(base)` returns
// `{...base, TASK_ORCHESTRATOR_MODE: 'headless-iteration'}`, never mutating `base`.

test("S3: buildIterationEnv preserves existing keys and sets the headless-iteration sentinel", () => {
    const input = { PATH: "x" };
    const result = buildIterationEnv(input);
    assert.equal(result.PATH, "x");
    assert.equal(result.TASK_ORCHESTRATOR_MODE, HEADLESS_ITERATION_MODE);
    assert.equal(HEADLESS_ITERATION_MODE, "headless-iteration");
});

test("S3: buildIterationEnv does not mutate the input object", () => {
    const input = { PATH: "x" };
    buildIterationEnv(input);
    assert.deepEqual(input, { PATH: "x" });
});

test("S3: buildIterationEnv overrides a pre-existing TASK_ORCHESTRATOR_MODE on the base", () => {
    const input = { TASK_ORCHESTRATOR_MODE: "something-else" };
    const result = buildIterationEnv(input);
    assert.equal(result.TASK_ORCHESTRATOR_MODE, HEADLESS_ITERATION_MODE);
});

// ─── Worktree naming / rename / cleanup (F-020, F-045) ───────────────────────

const GOOD_ID = "943f729d-0490-4baa-97d7-5b06ab9890f4";
const WT = path.posix.join(".claude", "worktrees", "ralph-x");

// Recording fake io.git: `script` maps an argv prefix key (leading `-C <path>` dropped) to a stdout
// string, or to an Error / {stderr,message} object to reject with.
function fakeIo(script) {
    const calls = [];
    return {
        calls,
        git: async (args) => {
            assert.ok(Array.isArray(args), "io.git must receive an argv array");
            calls.push(args);
            const key = args.slice(args[0] === "-C" ? 2 : 0).join(" ");
            const hit = Object.entries(script).find(([k]) => key.startsWith(k));
            const v = hit ? hit[1] : "";
            if (v && typeof v === "object") throw v;
            return { stdout: v };
        },
    };
}

test("worktreeNameForOutcome: valid UUID gives ralph-<8>-<iter>", () => {
    assert.equal(worktreeNameForOutcome(GOOD_ID, 3, "tmp"), "ralph-943f729d-3");
});

test("worktreeNameForOutcome: non-string itemId keeps tempName without throwing", () => {
    for (const bad of [12345678, {}, null, undefined, ["a"]]) {
        assert.equal(worktreeNameForOutcome(bad, 1, "tmp"), "tmp");
    }
});

test("worktreeNameForOutcome: hostile strings keep tempName", () => {
    for (const bad of ['";id;#', '"&calc', GOOD_ID + "x", GOOD_ID + '";id', "", "943f729"]) {
        assert.equal(worktreeNameForOutcome(bad, 1, "tmp"), "tmp");
    }
});

test("resumeCwd: absolute path under .claude/worktrees", () => {
    assert.equal(resumeCwd("tmp-1"), path.resolve(".claude", "worktrees", "tmp-1"));
});

test("renameWorktree: same name is ok with no git call", async () => {
    const io = fakeIo({});
    assert.deepEqual(await renameWorktree(io, "a", "a"), { ok: true });
    assert.equal(io.calls.length, 0);
});

test("renameWorktree: success issues one worktree-move argv array", async () => {
    const io = fakeIo({});
    assert.deepEqual(await renameWorktree(io, "a", "b"), { ok: true });
    assert.deepEqual(io.calls, [["worktree", "move", ".claude/worktrees/a", ".claude/worktrees/b"]]);
});

test("renameWorktree: failure surfaces first non-blank stderr line, else 'unknown error'", async () => {
    const io = fakeIo({ "worktree move": { stderr: "\n  fatal: destination exists\nmore\n", message: "m" } });
    assert.deepEqual(await renameWorktree(io, "a", "b"), { ok: false, reason: "fatal: destination exists" });
    const io2 = fakeIo({ "worktree move": { stderr: "", message: "" } });
    assert.deepEqual(await renameWorktree(io2, "a", "b"), { ok: false, reason: "unknown error" });
});

test("maybeCleanupWorktree: absent when list lacks the path; posix and native separator forms both count as present", async () => {
    assert.deepEqual(
        await maybeCleanupWorktree(fakeIo({ "worktree list": "worktree /r/other\n" }), "ralph-x", "origin/main"),
        { action: "absent" },
    );
    const native = WT.split("/").join(path.sep);
    for (const form of [WT, native]) {
        const io = fakeIo({ "worktree list": `worktree /r/${form}\n`, "rev-list": "0\n" });
        assert.deepEqual(await maybeCleanupWorktree(io, "ralph-x", "origin/main"), { action: "removed" });
    }
});

test("maybeCleanupWorktree: list failure gives failed", async () => {
    const io = fakeIo({ "worktree list": new Error("boom") });
    const r = await maybeCleanupWorktree(io, "ralph-x", "origin/main");
    assert.equal(r.action, "failed");
    assert.match(r.reason, /git worktree list failed: boom/);
});

test("maybeCleanupWorktree: dirty status is preserved", async () => {
    const io = fakeIo({ "worktree list": WT, "status": " M file\n" });
    assert.deepEqual(await maybeCleanupWorktree(io, "ralph-x", "origin/main"), {
        action: "preserved",
        reason: "uncommitted changes present",
    });
});

test("maybeCleanupWorktree: status failure gives failed", async () => {
    const io = fakeIo({ "worktree list": WT, "status": new Error("nope") });
    const r = await maybeCleanupWorktree(io, "ralph-x", "origin/main");
    assert.equal(r.action, "failed");
    assert.match(r.reason, /git status failed: nope/);
});

test("maybeCleanupWorktree: commits ahead are preserved", async () => {
    const io = fakeIo({ "worktree list": WT, "rev-list": "2\n" });
    assert.deepEqual(await maybeCleanupWorktree(io, "ralph-x", "origin/main"), {
        action: "preserved",
        reason: "2 commit(s) ahead of origin/main",
    });
});

test("maybeCleanupWorktree: unresolvable baseRef is preserved, never removed", async () => {
    const io = fakeIo({ "worktree list": WT, "rev-list": new Error("bad ref") });
    assert.deepEqual(await maybeCleanupWorktree(io, "ralph-x", "origin/nope"), {
        action: "preserved",
        reason: "could not compare against origin/nope",
    });
    assert.ok(!io.calls.some((c) => c[1] === "remove"));
});

test("maybeCleanupWorktree: clean worktree is removed via argv arrays", async () => {
    const io = fakeIo({ "worktree list": WT, "rev-list": "0\n" });
    assert.deepEqual(await maybeCleanupWorktree(io, "ralph-x", "origin/main"), { action: "removed" });
    assert.deepEqual(io.calls, [
        ["worktree", "list", "--porcelain"],
        ["-C", WT, "status", "--porcelain"],
        ["-C", WT, "rev-list", "--count", "origin/main..HEAD"],
        ["worktree", "remove", WT],
    ]);
});

test("maybeCleanupWorktree: remove failure gives failed", async () => {
    const io = fakeIo({ "worktree list": WT, "rev-list": "0\n", "worktree remove": new Error("busy") });
    const r = await maybeCleanupWorktree(io, "ralph-x", "origin/main");
    assert.equal(r.action, "failed");
    assert.match(r.reason, /git worktree remove failed: busy/);
});

test("maybeCleanupWorktree: shell metacharacters in baseRef stay a single argv element", async () => {
    const evil = 'main"; echo pwned #';
    const io = fakeIo({ "worktree list": WT, "rev-list": "0\n" });
    await maybeCleanupWorktree(io, "ralph-x", evil);
    const rl = io.calls.find((c) => c.includes("rev-list"));
    assert.deepEqual(rl, ["-C", WT, "rev-list", "--count", `${evil}..HEAD`]);
});

// === cae5d141: ralph on runs =====================================================================
// Test author actor id: test-author:cae5d141:r-202610170015-cae5d141. Scenario ids are the item's test-plan
// ids (S1, S8, S10, S11); oracles are task-scope (TS) D1-D11 / AC1-AC9 and current/docs/api-reference.md
// ("Response envelope", "Error catalog", runs_write open, advance claim/heartbeat/release). Every export
// below is NEW-SURFACE except the buildIterationEnv key (extension of the existing S3 contract).

const SEAT_FILE = "/scratch/tokens/3.implementer.token";

// -- 004d65fd S3 extension (TS D3): buildIterationEnv carries the token FILE path, never a value --

test("S3 (cae5d141 D3): SEAT_TOKEN_FILE_ENV names TASK_ORCHESTRATOR_SEAT_TOKEN_FILE", () => {
    assert.equal(SEAT_TOKEN_FILE_ENV, "TASK_ORCHESTRATOR_SEAT_TOKEN_FILE");
});

test("S3 (cae5d141 D3): buildIterationEnv(base, {seatTokenFile}) adds the path under TASK_ORCHESTRATOR_SEAT_TOKEN_FILE and keeps the base and the headless sentinel", () => {
    const base = { PATH: "x" };
    const env = buildIterationEnv(base, { seatTokenFile: SEAT_FILE });
    assert.equal(env.TASK_ORCHESTRATOR_SEAT_TOKEN_FILE, SEAT_FILE);
    assert.equal(env.PATH, "x");
    assert.equal(env.TASK_ORCHESTRATOR_MODE, HEADLESS_ITERATION_MODE);
    assert.deepEqual(base, { PATH: "x" }, "base is not mutated");
});

test("S3 (cae5d141 D3): without {seatTokenFile} the env has no TASK_ORCHESTRATOR_SEAT_TOKEN_FILE key", () => {
    assert.equal("TASK_ORCHESTRATOR_SEAT_TOKEN_FILE" in buildIterationEnv({ PATH: "x" }), false);
    assert.equal("TASK_ORCHESTRATOR_SEAT_TOKEN_FILE" in buildIterationEnv({ PATH: "x" }, {}), false);
});

test("S3 probe (cae5d141 D3): a passed seatTokenFile overrides a stale TASK_ORCHESTRATOR_SEAT_TOKEN_FILE inherited from the parent environment", () => {
    const env = buildIterationEnv({ TASK_ORCHESTRATOR_SEAT_TOKEN_FILE: "/old/other.token" }, { seatTokenFile: SEAT_FILE });
    assert.equal(env.TASK_ORCHESTRATOR_SEAT_TOKEN_FILE, SEAT_FILE);
});

// -- declared constants (TS D3, D7, D9, AC5) --

test("constants: RALPH_SEAT, DEFAULT_ITERATION_TIMEOUT_SECONDS (45 min), KILL_GRACE_MS, NEXT_EMPTY_WAIT_MS, REDACTED_TOKEN, FILTER_KEYS, TRANSIENT_SKIP_CODES", () => {
    assert.equal(RALPH_SEAT, "implementer");
    assert.equal(DEFAULT_ITERATION_TIMEOUT_SECONDS, 2700);
    assert.equal(KILL_GRACE_MS, 10000);
    assert.equal(NEXT_EMPTY_WAIT_MS, 30000);
    assert.equal(REDACTED_TOKEN, "[redacted-token]");
    assert.deepEqual([...FILTER_KEYS], ["tag", "type", "priority", "parentId", "ancestorId"]);
    assert.equal(Object.isFrozen(FILTER_KEYS), true);
    assert.deepEqual([...TRANSIENT_SKIP_CODES].sort(), ["claim_held", "resource_unavailable"]);
    assert.equal(Object.isFrozen(TRANSIENT_SKIP_CODES), true);
});

// -- S1: buildRunOpenOp (TS D4, D5; plan S15 budgets are opt-in) --

const ROOT_ID = "11111111-2222-4333-8444-555555555555";
const openBase = { rootId: ROOT_ID, label: "ralph-4242-1760000000", title: "ralph drain: any", filter: "", pid: 4242 };

test("S1: buildRunOpenOp with no budget flag emits no budget key, no items key, and the open envelope", () => {
    const op = buildRunOpenOp({ ...openBase });
    assert.equal(op.op, "open");
    assert.equal(op.rootId, ROOT_ID);
    assert.equal(op.label, "ralph-4242-1760000000");
    assert.equal(op.title, "ralph drain: any");
    assert.equal(op.hints.tool, "ralph");
    assert.equal("budget" in op, false, "S15: no default cap");
    assert.equal("items" in op, false, "items only when --items is given");
});

test("S1: hints are a map of string to string (api-reference runs_write open), so a numeric pid is stringified", () => {
    const op = buildRunOpenOp({ ...openBase });
    for (const [k, v] of Object.entries(op.hints)) assert.equal(typeof v, "string", `hints.${k}`);
    assert.equal(op.hints.pid, "4242");
});

test("S1: --run-budget-usd 12.5 alone sends budget {costUsd: 12.5}", () => {
    assert.deepEqual(buildRunOpenOp({ ...openBase, runBudgetUsd: 12.5 }).budget, { costUsd: 12.5 });
});

test("S1: --run-budget-tokens alone sends budget {tokens}, --max-attempts alone sends budget {attemptsPerSeat}", () => {
    assert.deepEqual(buildRunOpenOp({ ...openBase, runBudgetTokens: 1000 }).budget, { tokens: 1000 });
    assert.deepEqual(buildRunOpenOp({ ...openBase, maxAttempts: 3 }).budget, { attemptsPerSeat: 3 });
});

test("S1: all three budget flags together, and never maxActiveSeats", () => {
    const budget = buildRunOpenOp({ ...openBase, runBudgetTokens: 1000, runBudgetUsd: 12.5, maxAttempts: 3 }).budget;
    assert.deepEqual(budget, { tokens: 1000, costUsd: 12.5, attemptsPerSeat: 3 });
    assert.equal("maxActiveSeats" in budget, false);
});

test("S1: items are carried through when given", () => {
    assert.deepEqual(buildRunOpenOp({ ...openBase, items: ["aaaa1111", "bbbb2222"] }).items, ["aaaa1111", "bbbb2222"]);
});

// -- parseFilter / buildNextQuery (TS D4, D7, AC1 "the filter gains ancestorId=") --

test("parseFilter: the empty filter parses clean with no keys", () => {
    const { filter, errors } = parseFilter("");
    assert.deepEqual(errors, []);
    assert.deepEqual(filter, {});
});

test("parseFilter: key=value pairs for type, priority, parentId, ancestorId and tag", () => {
    assert.deepEqual(parseFilter("type=feature-task").filter.type, "feature-task");
    assert.deepEqual(parseFilter("priority=high").filter.priority, "high");
    assert.deepEqual(parseFilter("parentId=abc12345").filter.parentId, "abc12345");
    assert.deepEqual(parseFilter("ancestorId=abc12345").filter.ancestorId, "abc12345");
    assert.deepEqual(parseFilter("tag=v4").filter.tags, ["v4"]);
    for (const e of ["type=feature-task", "priority=high", "parentId=abc12345", "ancestorId=abc12345", "tag=v4"]) {
        assert.deepEqual(parseFilter(e).errors, [], e);
    }
});

test("parseFilter probe: a key outside FILTER_KEYS is reported in errors, not silently dropped", () => {
    const { errors } = parseFilter("bogus=1");
    assert.ok(errors.length >= 1);
});

test("buildNextQuery (D7): select next, role queue, orderBy oldest, ancestorId = rootId, limit = skipCount + 1", () => {
    const q = buildNextQuery({ filter: {}, rootId: ROOT_ID });
    assert.equal(q.select, "next");
    assert.equal(q.role, "queue");
    assert.equal(q.orderBy, "oldest");
    assert.equal(q.ancestorId, ROOT_ID);
    assert.equal(q.limit, 1);
    assert.equal(buildNextQuery({ filter: {}, rootId: ROOT_ID, skipCount: 3 }).limit, 4);
});

test("buildNextQuery boundary: limit is min(200, skipCount + 1) - 199 -> 200, 200 -> 200, 500 -> 200", () => {
    assert.equal(buildNextQuery({ filter: {}, rootId: ROOT_ID, skipCount: 198 }).limit, 199);
    assert.equal(buildNextQuery({ filter: {}, rootId: ROOT_ID, skipCount: 199 }).limit, 200);
    assert.equal(buildNextQuery({ filter: {}, rootId: ROOT_ID, skipCount: 200 }).limit, 200);
    assert.equal(buildNextQuery({ filter: {}, rootId: ROOT_ID, skipCount: 500 }).limit, 200);
});

test("buildNextQuery: tags, type, priority and parentId from the filter are carried and an absent one is absent", () => {
    const q = buildNextQuery({ filter: { tags: ["v4"], type: "feature-task", priority: "high", parentId: "abc12345" }, rootId: ROOT_ID });
    assert.deepEqual(q.tags, ["v4"]);
    assert.equal(q.type, "feature-task");
    assert.equal(q.priority, "high");
    assert.equal(q.parentId, "abc12345");
    const bare = buildNextQuery({ filter: {}, rootId: ROOT_ID });
    for (const k of ["tags", "type", "priority", "parentId"]) assert.equal(bare[k], undefined, k);
});

// -- seatTokenFilePath (TS D3) --

test("seatTokenFilePath: <scratchDir>/tokens/<iter>.implementer.token", () => {
    const scratch = path.join(path.sep, "tmp", "ralph", "x");
    assert.equal(path.normalize(seatTokenFilePath(scratch, 3)), path.join(scratch, "tokens", "3.implementer.token"));
});

test("seatTokenFilePath: distinct iterations get distinct files", () => {
    assert.notEqual(seatTokenFilePath("/s", 1), seatTokenFilePath("/s", 2));
});

// -- firstOpResult (api-reference "Response envelope": per-element result, partial_failure means look per element) --

function callResult(over) {
    const results = over.results;
    return {
        ok: over.ok,
        legacy: false,
        httpStatus: over.httpStatus ?? 200,
        data: results ? { results, summary: { ok: results.filter((r) => r.ok).length, failed: results.filter((r) => !r.ok).length } } : undefined,
        results,
        summary: undefined,
        reqId: "k7f3q9ab",
        error: over.error ?? null,
    };
}

test("firstOpResult: an ok batch yields {ok: true, value} carrying the first element's fields", () => {
    const r = firstOpResult(callResult({ ok: true, results: [{ ok: true, itemId: "aaaa1111", claimed: true }] }));
    assert.equal(r.ok, true);
    assert.equal(r.value.itemId, "aaaa1111");
    assert.equal(r.value.claimed, true);
});

test("firstOpResult: a partial_failure call reports the FIRST ELEMENT's error code, kind and retryAfterMs, not partial_failure", () => {
    const el = { ok: false, error: { code: "claim_held", kind: "transient", message: "held", retryAfterMs: 60000, detail: { itemId: "aaaa1111", retryAfterMs: 60000 } } };
    const r = firstOpResult(callResult({ ok: false, httpStatus: 207, error: { code: "partial_failure", kind: "permanent", message: "partial" }, results: [el] }));
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "claim_held");
    assert.equal(r.error.kind, "transient");
    assert.equal(r.error.retryAfterMs, 60000);
    assert.equal(r.error.detail.itemId, "aaaa1111");
});

test("firstOpResult: a call-level error with no results (401 unauthenticated) is {ok: false, error.code}", () => {
    const r = firstOpResult(callResult({ ok: false, httpStatus: 401, error: { code: "unauthenticated", kind: "permanent", message: "The seat token is not accepted." } }));
    assert.equal(r.ok, false);
    assert.equal(r.error.code, "unauthenticated");
    assert.equal(r.error.kind, "permanent");
});

// -- S8: OUTCOME_TABLE (TS AC2; carry-in p17-redproof-m3: the retry classification is pinned in full) --

const AC2_CATALOG_CODES = [
    "claim_held", "resource_unavailable", "not_claim_holder", "gate_blocked", "dependency_unmet",
    "invalid_transition", "duplicate", "run_paused", "run_at_capacity", "run_closed", "unauthenticated", "unavailable", "internal",
];
const AC2_LOCAL_CODES = ["iteration_timeout", "spawn_error"];
const OUTCOME_STATUSES = ["terminal", "gate-blocked", "error", "skip", "idle", "no-item"];
// loop buckets that mean "the driver tries again later" (backoff / wait-and-retry); everything else ends, counts or skips
const RETRIED_BUCKETS = new Set(["backoff", "wait-retry"]);
const rowOf = (code) => OUTCOME_TABLE.find((r) => r.code === code);

test("S8 AC2: OUTCOME_TABLE is frozen and every row has code, kind, by, status, action and loop", () => {
    assert.equal(Object.isFrozen(OUTCOME_TABLE), true);
    assert.ok(OUTCOME_TABLE.length >= AC2_CATALOG_CODES.length + AC2_LOCAL_CODES.length + OUTCOME_STATUSES.length);
    for (const row of OUTCOME_TABLE) {
        for (const k of ["code", "kind", "by", "status", "action", "loop"]) {
            assert.equal(typeof row[k], "string", `${row.code}.${k} is a string`);
            assert.ok(row[k].length > 0, `${row.code}.${k} is not empty`);
        }
        assert.ok(["driver", "iteration"].includes(row.by), `${row.code}.by`);
    }
});

test("S8 AC2: every code appears in exactly one row", () => {
    const codes = OUTCOME_TABLE.map((r) => r.code);
    assert.equal(new Set(codes).size, codes.length, "no duplicate code");
    for (const code of [...AC2_CATALOG_CODES, ...AC2_LOCAL_CODES, ...OUTCOME_STATUSES]) {
        assert.equal(codes.filter((c) => c === code).length, 1, `${code} appears exactly once`);
    }
});

test("S8 AC2: each error-catalog code's kind equals ERROR_KINDS[code]; the two local codes are not catalog codes", () => {
    for (const code of AC2_CATALOG_CODES) {
        assert.ok(ERROR_KINDS[code], `${code} is in the error catalog`);
        assert.equal(rowOf(code).kind, ERROR_KINDS[code], code);
    }
    for (const code of AC2_LOCAL_CODES) assert.equal(ERROR_KINDS[code], undefined, `${code} is driver-local`);
    for (const row of OUTCOME_TABLE.filter((r) => ERROR_KINDS[r.code])) assert.equal(row.kind, ERROR_KINDS[row.code], row.code);
});

test("S8 AC2/D8: the RALPH_OUTCOME statuses are rows; terminal, gate-blocked, error and skip are iteration outcomes, idle and no-item are driver-side", () => {
    for (const s of ["terminal", "gate-blocked", "error", "skip"]) assert.equal(rowOf(s).by, "iteration", s);
    for (const s of ["idle", "no-item"]) assert.equal(rowOf(s).by, "driver", s);
    for (const s of OUTCOME_STATUSES) assert.equal(rowOf(s).status, s, `${s} row status`);
});

test("S8 AC2/D7/D9: the skip codes are skip rows, the local failure codes are error rows", () => {
    for (const c of ["claim_held", "resource_unavailable", "invalid_transition", "duplicate"]) {
        assert.equal(rowOf(c).status, "skip", c);
        assert.equal(rowOf(c).loop, "skip", c);
    }
    for (const c of AC2_LOCAL_CODES) assert.equal(rowOf(c).status, "error", c);
    for (const c of ["not_claim_holder", "unauthenticated"]) assert.equal(rowOf(c).status, "error", c);
    assert.equal(rowOf("dependency_unmet").status, "idle", "AR-66: dependency_unmet is transient waiting, never drained");
});

test("S8 carry-in m3: run_paused waits for the run to be resumed and run_closed ends the loop; neither sits in a retried or backed-off bucket", () => {
    assert.equal(rowOf("run_paused").loop, "pause-wait", "run_paused no longer ends or closes the run");
    assert.equal(rowOf("run_closed").loop, "exit", "run_closed ends the loop");
    for (const c of ["run_paused", "run_closed"]) {
        assert.equal(RETRIED_BUCKETS.has(rowOf(c).loop), false, `${c} is not retried`);
    }
});

test("S8 A2 B2: the internal row is a driver-side transient backoff", () => {
    assert.equal(rowOf("internal").by, "driver");
    assert.equal(rowOf("internal").loop, "backoff");
    assert.equal(rowOf("internal").kind, ERROR_KINDS.internal);
});

test("S8 carry-in m3: the retried buckets hold exactly idle, dependency_unmet, run_at_capacity and unavailable; every other permanent code is outside them", () => {
    const retried = OUTCOME_TABLE.filter((r) => RETRIED_BUCKETS.has(r.loop)).map((r) => r.code).sort();
    assert.deepEqual(retried, ["dependency_unmet", "idle", "internal", "run_at_capacity", "unavailable"]);
    for (const row of OUTCOME_TABLE.filter((r) => r.kind === "permanent" && r.code !== "dependency_unmet")) {
        assert.equal(RETRIED_BUCKETS.has(row.loop), false, `${row.code} (permanent) must not be retried`);
    }
    assert.notEqual(rowOf("run_at_capacity").loop, rowOf("run_paused").loop);
});

test("S8: renderOutcomeTable() is a markdown table with the declared header, a separator and one line per row", () => {
    const md = renderOutcomeTable();
    const lines = md.split("\n").filter((l) => l.trim() !== "");
    assert.equal(lines[0].trim(), "| Code | Kind | By | Status | Action | Loop |");
    assert.match(lines[1], /^\|[\s:|-]+\|$/, "separator row");
    assert.equal(lines.length, 2 + OUTCOME_TABLE.length);
    OUTCOME_TABLE.forEach((row, i) => {
        const line = lines[2 + i];
        assert.ok(line.includes(row.code), `row ${i} names ${row.code}`);
        assert.ok(line.includes(row.kind), `row ${i} names its kind`);
        assert.ok(line.includes(row.loop), `row ${i} names its loop bucket`);
    });
});

test("S8: renderOutcomeTable(rows) renders only the rows it is given", () => {
    const md = renderOutcomeTable([{ code: "only_code", kind: "k", by: "driver", status: "error", action: "do a thing", loop: "exit" }]);
    const lines = md.split("\n").filter((l) => l.trim() !== "");
    assert.equal(lines.length, 3);
    assert.ok(lines[2].includes("only_code"));
});

test("S8 probe (GFM): a pipe inside a cell is escaped so the row keeps its six columns", () => {
    const md = renderOutcomeTable([{ code: "c", kind: "k", by: "driver", status: "error", action: "a | b", loop: "exit" }]);
    const row = md.split("\n").filter((l) => l.trim() !== "")[2];
    const bs = String.fromCharCode(92);
    let cells = 0;
    for (let i = 0; i < row.length; i++) if (row[i] === "|" && row[i - 1] !== bs) cells++;
    assert.equal(cells, 7, "six cells are bounded by seven unescaped pipes");
});

// -- S10: endSeatOutcome / loopEffect (TS D9, AC8) --

test("S10 D9: endSeatOutcome maps terminal -> done, gate-blocked and skip -> abandoned, error -> failed", () => {
    assert.equal(endSeatOutcome("terminal"), "done");
    assert.equal(endSeatOutcome("gate-blocked"), "abandoned");
    assert.equal(endSeatOutcome("skip"), "abandoned");
    assert.equal(endSeatOutcome("error"), "failed");
});

test("S10 probe: an unknown or missing status never ends a seat as done, and the answer is always a legal end_seat outcome", () => {
    for (const bad of [undefined, null, "", "weird", "TERMINAL"]) {
        const out = endSeatOutcome(bad);
        assert.ok(["failed", "abandoned"].includes(out), `${String(bad)} -> ${out}`);
    }
});

test("S10 AC8: loopEffect keys on code first - run_paused is a pause effect that never asks for the run to be closed", () => {
    const e = loopEffect({ code: "run_paused", status: "error", pauseReason: "budget" });
    assert.equal(e.effect, "pause");
    assert.equal(e.closeReason, undefined, "a paused run is not closed by the driver (extend / allow_one / skip_item / resume must stay possible)");
});

test("S10 AC8: loopEffect run_closed ends the loop", () => {
    const e = loopEffect({ code: "run_closed", status: "error" });
    assert.equal(e.effect, "exit");
    assert.equal(typeof e.exitReason, "string");
    assert.ok(e.exitReason.length > 0);
});

test("S10 AC8: loopEffect puts unauthenticated and not_claim_holder on the error counter with a hint", () => {
    for (const code of ["unauthenticated", "not_claim_holder"]) {
        const e = loopEffect({ code, status: "error" });
        assert.equal(e.effect, "error", code);
        assert.equal(typeof e.hint, "string", `${code} carries a hint`);
        assert.ok(e.hint.length > 0, `${code} hint is not empty`);
    }
});

test("S10 AC8: the code wins over the status - a terminal-looking run_paused is still a pause, a skip-looking run_closed still exits", () => {
    assert.equal(loopEffect({ code: "run_paused", status: "terminal" }).effect, "pause");
    assert.equal(loopEffect({ code: "run_paused", status: "terminal" }).closeReason, undefined);
    assert.equal(loopEffect({ code: "run_closed", status: "skip" }).effect, "exit");
});

test("S10 AC8: without a loop-ending code the effect follows the status (terminal, gate-blocked -> gate, error, skip)", () => {
    assert.equal(loopEffect({ status: "terminal" }).effect, "terminal");
    assert.equal(loopEffect({ status: "gate-blocked" }).effect, "gate");
    assert.equal(loopEffect({ status: "error" }).effect, "error");
    assert.equal(loopEffect({ status: "error", code: "iteration_timeout" }).effect, "error");
    assert.equal(loopEffect({ status: "error", code: "some_future_code" }).effect, "error");
});

test("S10 D7: a skip effect carries skipCode and retryAfterMs for the skip list", () => {
    const e = loopEffect({ status: "skip", code: "resource_unavailable", retryAfterMs: 45000 });
    assert.equal(e.effect, "skip");
    assert.equal(e.skipCode, "resource_unavailable");
    assert.equal(e.retryAfterMs, 45000);
});

// -- S11: buildUsageOps (TS D11) --

const FULL_USAGE = { total_cost_usd: 1.25, usage: { input_tokens: 100, output_tokens: 50, cache_creation_input_tokens: 10, cache_read_input_tokens: 5 } };

test("S11 D11: buildUsageOps -> [claim, heartbeat{usage}, release] for the item, claim carrying ttlSeconds", () => {
    const ops = buildUsageOps({ itemId: "aaaa1111", ttlSeconds: 120, envelope: FULL_USAGE });
    assert.equal(ops.length, 3);
    assert.deepEqual(ops[0], { op: "claim", itemId: "aaaa1111", ttlSeconds: 120 });
    assert.equal(ops[1].op, "heartbeat");
    assert.equal(ops[1].itemId, "aaaa1111");
    assert.equal(ops[1].usage.costUsd, 1.25);
    assert.deepEqual(ops[2], { op: "release", itemId: "aaaa1111" });
});

test("S11 D11: heartbeat usage.tokens is a whole count between input+output and all four counters summed", () => {
    const tokens = buildUsageOps({ itemId: "aaaa1111", ttlSeconds: 120, envelope: FULL_USAGE })[1].usage.tokens;
    assert.ok(Number.isInteger(tokens));
    assert.ok(tokens >= 150 && tokens <= 165, `tokens ${tokens}`);
});

test("S11 D11: usage without total_cost_usd leaves costUsd out of the heartbeat usage", () => {
    const ops = buildUsageOps({ itemId: "aaaa1111", ttlSeconds: 120, envelope: { usage: FULL_USAGE.usage } });
    assert.equal(ops[1].usage.costUsd, undefined);
    assert.ok(ops[1].usage.tokens >= 150);
});

test("S11 D11: an envelope with neither total_cost_usd nor usage reports nothing", () => {
    for (const envelope of [{}, { result: "x" }]) {
        const ops = buildUsageOps({ itemId: "aaaa1111", ttlSeconds: 120, envelope });
        assert.ok(ops == null || ops.length === 0, "nothing to report -> no ops");
    }
});

// -- redactTokens (TS AC5) --

test("AC5: redactTokens masks every occurrence of every known token as [redacted-token]", () => {
    const out = redactTokens("a SEATTOK1 b SEATTOK1 c COORDTOK2 d", ["SEATTOK1", "COORDTOK2"]);
    assert.equal(out, "a [redacted-token] b [redacted-token] c [redacted-token] d");
});

test("AC5: redactTokens leaves text without a token, and text with an empty or missing token list, unchanged", () => {
    assert.equal(redactTokens("nothing here", ["SEATTOK1"]), "nothing here");
    assert.equal(redactTokens("nothing here", []), "nothing here");
    assert.equal(redactTokens("nothing here", undefined), "nothing here");
    assert.equal(redactTokens("nothing here", null), "nothing here");
});

test("AC5 probe: an empty-string token is ignored (it must not insert the marker between every character)", () => {
    assert.equal(redactTokens("abc", ["", "zzz"]), "abc");
    assert.equal(redactTokens("abc TOK", ["", "TOK"]), "abc [redacted-token]");
});

test("AC5 probe: a token that is a prefix of another does not leave the longer token's tail behind", () => {
    const out = redactTokens("x tok-longer-tail y", ["tok", "tok-longer-tail"]);
    assert.equal(out.includes("longer"), false, out);
    assert.equal(out.includes("tail"), false, out);
});

test("AC5 probe: regex metacharacters in a token are matched literally", () => {
    const tok = "a+b.c*(d)[e]?^$|";
    assert.equal(redactTokens(`pre ${tok} post`, [tok]), "pre [redacted-token] post");
    assert.equal(redactTokens("aab.c", ["a+b.c"]), "aab.c", "a+b.c must not behave as a pattern");
});

test("AC5 probe: a token inside JSON and across newlines-in-context is masked", () => {
    const text = JSON.stringify({ result: "token=SEATTOK1", other: ["SEATTOK1"] });
    const out = redactTokens(text, ["SEATTOK1"]);
    assert.equal(out.includes("SEATTOK1"), false);
    assert.equal(JSON.parse(out).other[0], "[redacted-token]");
});
