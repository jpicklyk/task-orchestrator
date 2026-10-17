#!/usr/bin/env node
// ralph-loop.mjs - Ralph-style queue drain for MCP Task Orchestrator 4.0.
//
// One server run per loop. Each iteration: select the oldest ready queue item with
// `items_read next` (minus a per-loop skip list), add it to the run, assign it an `implementer`
// seat, claim it under that seat token, hand the token to a fresh `claude -p` process (in its own
// git worktree) through a 0o600 file, wait for the iteration's `RALPH_OUTCOME:` marker, report
// usage, and end the seat. Every exit path ends any active seat first, then closes the run.
//
// Every coordinator call goes over REST (hooks/lib/api-client.mjs callOp): reads token-less,
// update/assign/close under the coordinator token, claim/heartbeat/release/end_seat under the seat
// token. Token values never reach argv, env, prompt text or console output.
//
// Stdlib-only. Node 18+.

import { parseArgs, promisify } from "node:util";
import { spawn, execFile } from "node:child_process";
import { readFile, writeFile, mkdir, rm } from "node:fs/promises";
import { existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import os from "node:os";
import path from "node:path";
import {
    parseClaudeJson,
    parseOutcome,
    updateSpend,
    decideContinuation,
    buildResumeArgs,
    buildIterationArgs,
    decideIdleBackoff,
    buildIterationEnv,
    worktreeNameForOutcome,
    resumeCwd,
    renameWorktree,
    maybeCleanupWorktree,
    DEFAULT_IDLE_BACKOFF_MS,
    DEFAULT_ITERATION_TIMEOUT_SECONDS,
    KILL_GRACE_MS,
    RALPH_SEAT,
    parseFilter,
    buildNextQuery,
    buildRunOpenOp,
    addSkip,
    isBackoffError,
    decideSelection,
    endSeatOutcome,
    loopEffect,
    buildUsageOps,
    redactTokens,
    seatTokenFilePath,
    firstOpResult,
} from "./ralph-lib.mjs";
import { callOp, apiBaseUrl } from "../hooks/lib/api-client.mjs";
import { locateConfig } from "../hooks/config-locator.mjs";

const execFileAsync = promisify(execFile);
// Injected git runner for ralph-lib worktree helpers: argv array, no shell.
const gitIo = { git: (args) => execFileAsync("git", args) };

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROMPT_PATH = path.resolve(HERE, "../skills/ralph/iteration-prompt.md");

const WRITE_TIMEOUT_MS = 10_000; // open, update, assign, close, advance, end_seat
const READ_TIMEOUT_MS = 5_000; // items_read next, runs_read list
const MAX_CAPACITY_RETRIES = 10; // run_at_capacity assign retries per iteration

// Sent to a resumed iteration (`claude -p --resume <session_id>`) when the prior run exited
// cleanly without a RALPH_OUTCOME marker. Keep in sync with the "How your turn ends" operating
// principle in skills/ralph/iteration-system-prompt.md.
const RESUME_MESSAGE =
    "You ended your turn without a RALPH_OUTCOME marker, which ends this iteration. " +
    "Continue any outstanding work on the item now, still passing the seat token from the token file on every " +
    "task-orchestrator call. If something blocks you, emit RALPH_OUTCOME with status gate-blocked and the reason. " +
    "If the item already reached terminal, emit RALPH_OUTCOME terminal.";

const DEFAULTS = {
    max: 10,
    gateBudget: 3,
    errorBudget: 2,
    budget: 5, // USD per iteration
    ttl: 1800, // seconds (30 min)
    model: "sonnet",
    maxContinuations: 2, // resume attempts for a marker-less clean exit; 0 disables
    idleBudget: 3, // consecutive idle selections tolerated before exiting
    iterationTimeout: DEFAULT_ITERATION_TIMEOUT_SECONDS,
};

// -- CLI parsing --------------------------------------------------------------
let values;
try {
    ({ values } = parseArgs({
        options: {
            filter: { type: "string", default: "" },
            max: { type: "string", default: String(DEFAULTS.max) },
            "gate-budget": { type: "string", default: String(DEFAULTS.gateBudget) },
            "error-budget": { type: "string", default: String(DEFAULTS.errorBudget) },
            budget: { type: "string", default: String(DEFAULTS.budget) },
            ttl: { type: "string", default: String(DEFAULTS.ttl) },
            model: { type: "string", default: DEFAULTS.model },
            "base-ref": { type: "string", default: "origin/main" },
            "cleanup-on-terminal": { type: "boolean", default: true },
            "no-cleanup": { type: "boolean", default: false },
            "max-continuations": { type: "string", default: String(DEFAULTS.maxContinuations) },
            "idle-budget": { type: "string", default: String(DEFAULTS.idleBudget) },
            "iteration-timeout": { type: "string", default: String(DEFAULTS.iterationTimeout) },
            "run-budget-tokens": { type: "string" },
            "run-budget-usd": { type: "string" },
            "max-attempts": { type: "string" },
            root: { type: "string", default: "" },
            items: { type: "string", default: "" },
            "scratch-dir": { type: "string", default: "" },
            "claude-bin": { type: "string", default: "claude" },
            "dry-run": { type: "boolean", default: false },
            help: { type: "boolean", default: false, short: "h" },
        },
    }));
} catch (err) {
    console.error(`error: ${err.message}`);
    process.exit(64);
}

if (values.help) {
    printHelp();
    process.exit(0);
}

const pid = process.pid;
const startedMs = Date.now();
const label = `ralph-${pid}-${startedMs}`.slice(0, 64);

const cfg = {
    filter: values.filter,
    max: parseIntStrict(values.max, "max"),
    gateBudget: parseIntStrict(values["gate-budget"], "gate-budget"),
    errorBudget: parseIntStrict(values["error-budget"], "error-budget"),
    budget: parseFloatStrict(values.budget, "budget"),
    ttl: parseIntStrict(values.ttl, "ttl"),
    model: values.model,
    baseRef: values["base-ref"],
    // --no-cleanup overrides --cleanup-on-terminal (compatibility with both forms)
    cleanupOnTerminal: values["no-cleanup"] ? false : values["cleanup-on-terminal"],
    maxContinuations: parseIntStrict(values["max-continuations"], "max-continuations"),
    idleBudget: parseIntStrict(values["idle-budget"], "idle-budget"),
    iterationTimeout: parseIntStrict(values["iteration-timeout"], "iteration-timeout"),
    runBudgetTokens: values["run-budget-tokens"] === undefined ? undefined : parseIntStrict(values["run-budget-tokens"], "run-budget-tokens"),
    runBudgetUsd: values["run-budget-usd"] === undefined ? undefined : parseFloatStrict(values["run-budget-usd"], "run-budget-usd"),
    maxAttempts: values["max-attempts"] === undefined ? undefined : parseIntStrict(values["max-attempts"], "max-attempts"),
    items: [...new Set(values.items.split(",").map((s) => s.trim().toLowerCase()).filter(Boolean))],
    claudeBin: values["claude-bin"] || "claude",
    dryRun: values["dry-run"],
};
cfg.scratchDir = values["scratch-dir"]
    ? path.resolve(values["scratch-dir"])
    : path.join(os.tmpdir(), "task-orchestrator", "ralph", label);

if (cfg.ttl < 60 || cfg.ttl > 86400) usageError("--ttl must be between 60 and 86400 seconds");
if (cfg.budget <= 0) usageError("--budget must be > 0");
if (cfg.iterationTimeout < 1) usageError("--iteration-timeout must be at least 1 second");

const { filter, errors: filterErrors } = parseFilter(cfg.filter);
if (filterErrors.length) usageError(filterErrors.join("; "));

const baseUrl = apiBaseUrl();
if (!baseUrl) usageError("ralph on 4.0 needs the REST API: set TASK_ORCHESTRATOR_API_URL (or apiUrl in .taskorchestrator/client.json)");

let located = null;
try {
    located = locateConfig();
} catch {
    located = null;
}
const rootId = values.root || filter.ancestorId || located?.rootId || null;
if (!rootId) usageError("no project root: pass --root <rootId>, put ancestorId=<rootId> in --filter, or set project.rootId in .taskorchestrator/config.yaml");

// -- Load iteration prompt ----------------------------------------------------
let promptTemplate;
try {
    promptTemplate = await readFile(PROMPT_PATH, "utf8");
} catch (err) {
    console.error(`error: could not read iteration prompt at ${PROMPT_PATH}`);
    console.error(err.message);
    process.exit(70);
}

const openOp = buildRunOpenOp({
    rootId,
    items: cfg.items,
    label,
    title: `ralph drain: ${cfg.filter || "any"}`,
    filter: cfg.filter,
    pid,
    runBudgetTokens: cfg.runBudgetTokens,
    runBudgetUsd: cfg.runBudgetUsd,
    maxAttempts: cfg.maxAttempts,
});

if (cfg.dryRun) {
    const placeholderItem = "<item-id>";
    const prompt = renderPrompt(promptTemplate, {
        item_id: placeholderItem,
        run_id: "<run-id>",
        seat_token_file: seatTokenFilePath(cfg.scratchDir, 1),
        ttl: String(cfg.ttl),
    });
    const args = buildIterationArgs({ worktreeName: `ralph-${pid}-<ts>-1`, cfg, prompt });
    const shown = args.slice(0, -1).map(quoteArg);
    shown.push(`<iteration prompt: ${prompt.length} chars rendered from skills/ralph/iteration-prompt.md>`);
    console.log(formatPreflight());
    console.log("\n[dry-run] would open the run with: runs_write");
    console.log(`  ${JSON.stringify(openOp)}`);
    console.log(`\n[dry-run] iteration 1 (item ${placeholderItem}):`);
    console.log(`  ${quoteArg(cfg.claudeBin)} ${shown.join(" ")}`);
    console.log(`  env TASK_ORCHESTRATOR_SEAT_TOKEN_FILE=${seatTokenFilePath(cfg.scratchDir, 1)}`);
    console.log("\n[dry-run] no request sent; exiting.");
    process.exit(0);
}

// -- Output helpers -----------------------------------------------------------
// Every known token value is masked on everything this process writes, child output included.
const knownTokens = [];
function scrub(text) {
    return redactTokens(text, knownTokens).replace(/\b(seat|coordinator)Token\b/g, "$1 token");
}
function say(line = "") {
    process.stdout.write(scrub(line) + "\n");
}
function warn(line) {
    process.stderr.write(scrub(line) + "\n");
}

// -- Signals ------------------------------------------------------------------
// SIGINT/SIGTERM: stop the iteration child (SIGTERM, then SIGKILL after the grace), wake any wait,
// and let the main flow end the active seat and close the run cancelled. A backstop timer forces
// the exit if cleanup hangs.
let currentChild = null;
let shuttingDown = null; // { sig, code } once a signal arrived
let wake = null;
function installSignalHandler(sig, exitCode) {
    process.on(sig, () => {
        if (shuttingDown) return;
        shuttingDown = { sig, code: exitCode };
        warn(`\n[ralph-loop] received ${sig}; stopping the iteration, ending the seat and closing the run...`);
        stopChild(currentChild);
        if (wake) wake();
        setTimeout(() => process.exit(exitCode), KILL_GRACE_MS + 20_000).unref();
    });
}
installSignalHandler("SIGINT", 130);
installSignalHandler("SIGTERM", 143);

function stopChild(child) {
    if (!child || child.exitCode !== null || child.signalCode !== null) return;
    try {
        child.kill("SIGTERM");
    } catch {
        // best effort
    }
    const t = setTimeout(() => {
        if (child.exitCode === null && child.signalCode === null) {
            try {
                child.kill("SIGKILL");
            } catch {
                // best effort
            }
        }
    }, KILL_GRACE_MS);
    t.unref();
}

function sleep(ms) {
    return new Promise((resolve) => {
        if (shuttingDown) return resolve();
        const t = setTimeout(() => {
            wake = null;
            resolve();
        }, ms);
        wake = () => {
            clearTimeout(t);
            wake = null;
            resolve();
        };
    });
}

// -- REST calls ---------------------------------------------------------------
async function op(tool, body, timeoutMs) {
    return firstOpResult(await callOp(tool, body, { timeoutMs, baseUrl }));
}
const coordinatorCall = (tool, ops) => op(tool, { ops, seatToken: run.coordinatorToken }, WRITE_TIMEOUT_MS);
const seatCall = (tool, ops, token) => op(tool, { ops, seatToken: token }, WRITE_TIMEOUT_MS);
const errText = (e) => `${e?.code || "error"}${e?.message ? `: ${e.message}` : ""}`;

// -- Loop state ---------------------------------------------------------------
const stats = {
    startedAt: new Date(startedMs),
    iterations: 0,
    terminal: 0,
    gateBlocked: 0,
    errored: 0,
    skipped: 0,
    idle: 0,
    continued: 0, // iterations that needed >=1 resume continuation before their final outcome
    consecutiveGateFailures: 0,
    consecutiveErrors: 0,
    consecutiveIdle: 0,
    exitReason: null,
    exitCode: 0,
    closeReason: "completed",
    hintsShown: new Set(),
    outcomes: [],
};
const run = { runId: null, coordinatorToken: null };
let activeSeat = null; // { itemId, token, tokenFile }
let skipList = [];
const addedMembers = new Set(cfg.items);
const doneMembers = new Set();

say(formatPreflight());
await warnOpenRalphRuns();

// -- Open the run -------------------------------------------------------------
{
    const r = await op("runs_write", { ops: [openOp] }, WRITE_TIMEOUT_MS);
    if (!r.ok) {
        warn(`error: runs_write open failed (${errText(r.error)})`);
        if (r.error?.code === "unauthenticated") warn("  the REST credential (TASK_ORCHESTRATOR_API_TOKEN) was refused");
        process.exit(2);
    }
    run.runId = r.value?.runId;
    run.coordinatorToken = r.value?.coordinatorToken;
    if (typeof run.coordinatorToken === "string") knownTokens.push(run.coordinatorToken);
    if (!run.runId || !run.coordinatorToken) {
        warn("error: runs_write open answered without a run id or a coordinator credential");
        process.exit(2);
    }
    say(`  Run:           ${run.runId} (label ${label})`);
}

// -- Main loop ----------------------------------------------------------------
while (stats.iterations < cfg.max && !shuttingDown && !stats.exitReason) {
    // ---- select
    const sel = await op("items_read", buildNextQuery({ filter, rootId, skipCount: skipList.length, members: cfg.items.length ? cfg.items : null }), READ_TIMEOUT_MS);
    if (shuttingDown) break;
    if (!sel.ok) {
        if (sel.error?.kind === "transient" || sel.error?.kind === "shedding") {
            if (await idleWait(sel.error.retryAfterMs, `items_read next: ${sel.error.code}`)) break;
            continue;
        }
        endLoop(`items_read next failed (${errText(sel.error)})`, 2);
        break;
    }
    const decision = decideSelection({
        candidates: sel.value?.items,
        reason: sel.value?.reason,
        skipList,
        members: cfg.items.length ? cfg.items : null,
        now: Date.now(),
    });
    if (decision.drained || (cfg.items.length && cfg.items.every((m) => doneMembers.has(m)))) {
        endLoop("queue drained (no ready items match the filter)");
        break;
    }
    if (decision.idle) {
        if (await idleWait(decision.waitMs, decision.reason)) break;
        continue;
    }
    stats.consecutiveIdle = 0;
    const itemId = String(decision.pick.id).toLowerCase();
    const title = decision.pick.title ? ` ${decision.pick.title}` : "";

    // ---- membership
    if (!addedMembers.has(itemId)) {
        const u = await coordinatorCall("runs_write", [{ op: "update", runId: run.runId, addItems: [itemId] }]);
        if (!u.ok) {
            if (handleCoordinatorStop(u.error, "update")) break;
            if (isBackoffError(u.error)) {
                if (await idleWait(u.error.retryAfterMs, `update addItems: ${u.error.code}`)) break;
                continue;
            }
            skipList = addSkip(skipList, { itemId, code: u.error?.code || "error", retryAfterMs: u.error?.retryAfterMs, now: Date.now() });
            say(`  - ${itemId.slice(0, 8)} skipped: update addItems ${errText(u.error)}`);
            continue;
        }
        addedMembers.add(itemId);
    }

    // ---- assign
    if (shuttingDown) break; // G1: a signal during update addItems; no seat exists yet
    const seat = await assignSeat(itemId);
    if (!seat) {
        if (stats.exitReason || shuttingDown) break;
        continue;
    }
    activeSeat = { itemId, token: seat.token, tokenFile: null };
    if (shuttingDown) {
        // G2: a signal during assign
        await endActiveSeat("abandoned", "ralph: interrupted before claim");
        break;
    }

    // ---- claim under the seat token
    const claim = await seatCall("advance", [{ op: "claim", itemId, ttlSeconds: cfg.ttl }], seat.token);
    if (!claim.ok) {
        const code = claim.error?.code || "error";
        if (isBackoffError(claim.error)) {
            say(`  - ${itemId.slice(0, 8)} claim ${errText(claim.error)}: backing off`);
            await endActiveSeat("abandoned", `ralph: claim ${code}, backing off`);
            if (await idleWait(claim.error.retryAfterMs, `claim: ${code}`)) break;
            continue;
        }
        skipList = addSkip(skipList, { itemId, code, retryAfterMs: claim.error?.retryAfterMs, now: Date.now() });
        say(`  - ${itemId.slice(0, 8)} skipped: claim ${errText(claim.error)}`);
        await endActiveSeat("abandoned", `ralph: claim failed (${code})`);
        if (code === "unauthenticated") {
            endLoop("the seat token was refused on claim (unauthenticated)", 2);
            break;
        }
        continue;
    }

    if (shuttingDown) {
        // G3: a signal during the claim; end_seat releases the claim (D6)
        await endActiveSeat("abandoned", "ralph: interrupted before the iteration started");
        break;
    }

    // ---- iteration
    stats.iterations++;
    const iterIndex = stats.iterations;
    const tokenFile = seatTokenFilePath(cfg.scratchDir, iterIndex);
    try {
        await writeTokenFile(tokenFile, seat.token);
        activeSeat.tokenFile = tokenFile;
    } catch (err) {
        warn(`  token file write failed: ${err.message}`);
        await endActiveSeat("failed", "ralph: token file write failed");
        endLoop(`could not write the seat token file under ${cfg.scratchDir}`, 2);
        break;
    }

    const result = await runOneIteration({ iterIndex, itemId, title, tokenFile });
    const outcome = JSON.parse(redactTokens(JSON.stringify(result.outcome), knownTokens));
    outcome.itemId = itemId;

    // ---- usage (best effort, never blocks end_seat)
    const usageOps = buildUsageOps({ itemId, ttlSeconds: cfg.ttl, envelope: result.envelope });
    if (usageOps) {
        const ur = await callOp("advance", { ops: usageOps, seatToken: seat.token }, { timeoutMs: WRITE_TIMEOUT_MS, baseUrl });
        if (!ur || !ur.ok) say(`  usage-report-failed (${ur?.error?.code || "no answer"})`);
    }

    // ---- end the seat
    const seatOutcome = shuttingDown ? "failed" : endSeatOutcome(outcome.status);
    await endActiveSeat(seatOutcome, `ralph iteration ${iterIndex}: ${outcome.status}${outcome.code ? ` (${outcome.code})` : ""}${outcome.summary || outcome.reason ? `: ${outcome.summary || outcome.reason}` : ""}`);

    // ---- bookkeeping
    if (result.continuations > 0) stats.continued++;
    const record = { iter: iterIndex, exitCode: result.exitCode, outcome, error: result.error, continuations: result.continuations, worktree: result.worktree };
    stats.outcomes.push(record);
    const effect = loopEffect(outcome);
    applyEffect(effect, outcome, itemId);
    say(formatIterEnd(iterIndex, outcome));
    if (effect.hint && !stats.hintsShown.has(effect.hint)) {
        stats.hintsShown.add(effect.hint);
        say(`  hint: ${effect.hint}`);
    }

    if (cfg.cleanupOnTerminal && outcome.status === "terminal" && !shuttingDown && existsSync(resumeCwd(record.worktree))) {
        const cleanupResult = await maybeCleanupWorktree(gitIo, record.worktree, cfg.baseRef);
        const formatted = formatCleanupResult(record.worktree, cleanupResult);
        if (formatted) say(formatted);
        record.cleanup = cleanupResult;
    }

    if (stats.exitReason || shuttingDown) break;
    if (stats.consecutiveGateFailures >= cfg.gateBudget) {
        endLoop(`gate failure budget exhausted (${cfg.gateBudget} consecutive)`);
        break;
    }
    if (stats.consecutiveErrors >= cfg.errorBudget) {
        endLoop(`error budget exhausted (${cfg.errorBudget} consecutive)`, 2);
        break;
    }
}

if (shuttingDown) {
    stats.exitReason = stats.exitReason || `interrupted (${shuttingDown.sig})`;
    stats.closeReason = "cancelled";
    stats.exitCode = shuttingDown.code;
}
if (!stats.exitReason && stats.iterations >= cfg.max) stats.exitReason = `iteration cap reached (${cfg.max})`;
if (!stats.exitReason) stats.exitReason = "loop ended";

await finish();
say(formatSummary());
process.exit(stats.exitCode);

// -- Loop pieces --------------------------------------------------------------

function endLoop(reason, exitCode = 0, closeReason = "completed") {
    if (stats.exitReason) return;
    stats.exitReason = reason;
    stats.exitCode = exitCode;
    stats.closeReason = closeReason;
}

/** Idle wait through decideIdleBackoff. Returns true when the loop must stop. */
async function idleWait(retryAfterMs, why) {
    stats.idle++;
    stats.consecutiveIdle++;
    const d = decideIdleBackoff({ retryAfterMs, consecutiveIdle: stats.consecutiveIdle, idleBudget: cfg.idleBudget });
    if (d.exit) {
        endLoop(`idle budget exhausted (${stats.consecutiveIdle} consecutive idle selections)`);
        return true;
    }
    say(`  idle (${why}): waiting ${d.waitMs}ms before selecting again`);
    await sleep(d.waitMs);
    return Boolean(shuttingDown);
}

/** run_paused / run_closed / unauthenticated from a coordinator call end the loop; true when they did. */
function handleCoordinatorStop(error, what) {
    const code = error?.code;
    if (code === "run_paused") {
        const why = error.detail?.pauseReason ? ` (${error.detail.pauseReason})` : "";
        const dec = error.detail?.pauseDecisionId ? `, decision ${error.detail.pauseDecisionId}` : "";
        endLoop(`run paused${why}${dec}: a director or human must answer the pause decision`, 0, "cancelled");
        return true;
    }
    if (code === "run_closed") {
        endLoop(`run closed elsewhere (on ${what})`);
        return true;
    }
    if (code === "unauthenticated") {
        endLoop(`the coordinator credential was refused on ${what} (unauthenticated)`, 2);
        return true;
    }
    return false;
}

/** Assign the implementer seat; null when the item was skipped or the loop must stop. */
async function assignSeat(itemId) {
    for (let attempt = 0; ; attempt++) {
        const a = await coordinatorCall("runs_write", [{ op: "assign", runId: run.runId, itemId, seat: RALPH_SEAT }]);
        if (a.ok) {
            const token = a.value?.seatToken;
            if (typeof token !== "string" || !token) {
                endLoop("runs_write assign answered without a seat credential", 2);
                return null;
            }
            knownTokens.push(token);
            return { token, assignmentId: a.value?.assignmentId, attempt: a.value?.attempt };
        }
        if (handleCoordinatorStop(a.error, "assign")) return null;
        if (a.error?.code === "run_at_capacity" && attempt < MAX_CAPACITY_RETRIES) {
            say(`  run at capacity: waiting ${DEFAULT_IDLE_BACKOFF_MS}ms, then assigning again`);
            await sleep(DEFAULT_IDLE_BACKOFF_MS);
            if (shuttingDown) return null;
            continue;
        }
        if (a.error?.code === "run_at_capacity") {
            endLoop(`run at capacity after ${MAX_CAPACITY_RETRIES} assign retries`);
            return null;
        }
        if (a.error?.kind === "transient" || a.error?.kind === "shedding") {
            await idleWait(a.error.retryAfterMs, `assign: ${a.error.code}`);
            return null;
        }
        skipList = addSkip(skipList, { itemId, code: a.error?.code || "error", now: Date.now() });
        say(`  - ${itemId.slice(0, 8)} skipped: assign ${errText(a.error)}`);
        return null;
    }
}

async function endActiveSeat(outcome, summary) {
    if (!activeSeat) return;
    const seatNow = activeSeat;
    activeSeat = null;
    const r = await seatCall("runs_write", [{ op: "end_seat", outcome, summary: String(redactTokens(summary, knownTokens)).slice(0, 500) }], seatNow.token);
    if (!r.ok) warn(`  end_seat failed (${errText(r.error)})`);
    if (seatNow.tokenFile) await rm(seatNow.tokenFile, { force: true }).catch(() => {});
}

function applyEffect(effect, outcome, itemId) {
    switch (effect.effect) {
        case "terminal":
            stats.terminal++;
            stats.consecutiveGateFailures = 0;
            stats.consecutiveErrors = 0;
            doneMembers.add(itemId);
            break;
        case "gate":
            stats.gateBlocked++;
            stats.consecutiveGateFailures++;
            stats.consecutiveErrors = 0;
            break;
        case "skip":
            stats.skipped++;
            skipList = addSkip(skipList, { itemId, code: effect.skipCode, retryAfterMs: effect.retryAfterMs, now: Date.now() });
            break;
        case "exit":
            stats.errored++;
            endLoop(effect.exitReason, 0, effect.closeReason || "cancelled");
            break;
        default:
            stats.errored++;
            stats.consecutiveErrors++;
            stats.consecutiveGateFailures = 0;
            break;
    }
}

async function runOneIteration({ iterIndex, itemId, title, tokenFile }) {
    // The worktree is created by `claude --worktree=<name>` at spawn. A unique temp name avoids
    // collisions under concurrent loops; it is renamed to ralph-<short-uuid>-<iter> afterwards.
    const tempWorktreeName = `ralph-${pid}-${Date.now()}-${iterIndex}`;
    const prompt = renderPrompt(promptTemplate, {
        item_id: itemId,
        run_id: run.runId,
        seat_token_file: tokenFile,
        ttl: String(cfg.ttl),
    });
    const env = buildIterationEnv(process.env, { seatTokenFile: tokenFile });
    const deadline = Date.now() + cfg.iterationTimeout * 1000;

    say(`\n-- Iteration ${iterIndex}/${cfg.max} - item ${itemId.slice(0, 8)}${title} - worktree: ${tempWorktreeName} --`);

    let currentArgs = buildIterationArgs({ worktreeName: tempWorktreeName, cfg, prompt });
    let currentCwd; // undefined for the initial spawn (--worktree=... creates it)
    let continuationsUsed = 0;
    let spentUsd = 0;
    let exitCode, envelope, error, parsed, timedOut;

    for (;;) {
        if (shuttingDown) {
            // G4: a signal during the token-file write, or between a child exit and its resume
            parsed = { outcome: { status: "error", code: "interrupted", reason: `interrupted (${shuttingDown.sig})` }, markerFound: false };
            break;
        }
        ({ exitCode, envelope, error, timedOut } = await spawnClaude(currentArgs, { cwd: currentCwd, env, timeoutMs: deadline - Date.now() }));
        spentUsd = updateSpend(spentUsd, envelope);
        if (timedOut) {
            parsed = { outcome: { status: "error", code: "iteration_timeout", reason: `iteration exceeded --iteration-timeout ${cfg.iterationTimeout}s` }, markerFound: false };
            break;
        }
        if (error) {
            parsed = { outcome: { status: "error", code: "spawn_error", reason: `spawn error: ${error}` }, markerFound: false };
            break;
        }
        parsed = parseOutcome(envelope, exitCode);
        if (shuttingDown) break;

        const decision = decideContinuation({
            exitCode,
            markerFound: parsed.markerFound,
            sessionId: envelope?.session_id,
            continuationsUsed,
            maxContinuations: cfg.maxContinuations,
            budget: cfg.budget,
            spentUsd,
        });
        if (!decision.continue || deadline - Date.now() <= 0) {
            if (!parsed.markerFound && exitCode === 0 && continuationsUsed > 0) {
                parsed.outcome = { ...parsed.outcome, reason: `${parsed.outcome.reason} (after ${continuationsUsed} continuation(s))` };
            }
            break;
        }
        continuationsUsed++;
        say(`  -> no RALPH_OUTCOME marker; resuming session ${envelope.session_id} (continuation ${continuationsUsed}/${cfg.maxContinuations}, $${decision.remainingUsd.toFixed(2)} remaining)`);
        currentCwd = resumeCwd(tempWorktreeName);
        currentArgs = buildResumeArgs({ sessionId: envelope.session_id, cfg, remainingBudget: decision.remainingUsd, message: RESUME_MESSAGE });
    }
    if (shuttingDown && !timedOut && !error && !parsed.markerFound) {
        parsed.outcome = { status: "error", code: "interrupted", reason: `interrupted (${shuttingDown.sig})` };
    }

    // Rename the worktree to ralph-<short-uuid>-<iter> so a preserved one is traceable to its item.
    // The child has exited, so file locks are not a concern. A failed rename keeps the temp name.
    let worktree = tempWorktreeName;
    const uuidName = worktreeNameForOutcome(itemId, iterIndex, tempWorktreeName);
    if (uuidName !== tempWorktreeName && !error && existsSync(resumeCwd(tempWorktreeName))) {
        const renameResult = await renameWorktree(gitIo, tempWorktreeName, uuidName);
        if (renameResult.ok) {
            worktree = uuidName;
            say(`  -> worktree renamed: ${tempWorktreeName} -> ${uuidName}`);
        } else {
            say(`  -> worktree rename failed (${renameResult.reason}); kept ${tempWorktreeName}`);
        }
    }
    return { outcome: parsed.outcome, envelope, exitCode, error, continuations: continuationsUsed, worktree };
}

/**
 * Spawn one claude process. A `--claude-bin` ending in .mjs or .js runs under this Node binary (the
 * test seam); a missing script path is a spawn error, as a missing executable is. The child's
 * stdout/stderr stream through line-buffered token redaction.
 */
function spawnClaude(args, { cwd, env, timeoutMs }) {
    return new Promise((resolve) => {
        const bin = cfg.claudeBin;
        const isScript = /\.(mjs|js)$/i.test(bin);
        if (isScript && !existsSync(bin)) {
            resolve({ exitCode: -1, envelope: {}, error: `${bin} not found`, timedOut: false });
            return;
        }
        const command = isScript ? process.execPath : bin;
        const argv = isScript ? [bin, ...args] : args;
        const stdoutChunks = [];
        let settled = false;
        let timedOut = false;
        let child;
        try {
            child = spawn(command, argv, { stdio: ["ignore", "pipe", "pipe"], shell: false, env, ...(cwd ? { cwd } : {}) });
        } catch (err) {
            resolve({ exitCode: -1, envelope: {}, error: err.message, timedOut: false });
            return;
        }
        currentChild = child;
        const outLines = lineRedactor((s) => process.stdout.write(s));
        const errLines = lineRedactor((s) => process.stderr.write(s));
        const timer = setTimeout(() => {
            timedOut = true;
            warn(`  iteration timed out after ${cfg.iterationTimeout}s; stopping it`);
            stopChild(child);
        }, Math.max(1, timeoutMs));
        timer.unref();

        child.stdout.on("data", (chunk) => {
            stdoutChunks.push(chunk);
            outLines.push(chunk);
        });
        child.stderr.on("data", (chunk) => errLines.push(chunk));
        child.on("error", (err) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            currentChild = null;
            outLines.flush();
            errLines.flush();
            resolve({ exitCode: -1, envelope: {}, error: err.message, timedOut: false });
        });
        child.on("close", (code) => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            currentChild = null;
            outLines.flush();
            errLines.flush();
            const envelope = parseClaudeJson(Buffer.concat(stdoutChunks).toString("utf8"));
            resolve({ exitCode: code ?? -1, envelope, error: null, timedOut });
        });
    });
}

/** Buffers child output to whole lines so a token split across chunks is still masked. */
function lineRedactor(write) {
    let pending = "";
    return {
        push(chunk) {
            pending += chunk.toString("utf8");
            const cut = pending.lastIndexOf("\n");
            if (cut < 0) return;
            write(scrub(pending.slice(0, cut + 1)));
            pending = pending.slice(cut + 1);
        },
        flush() {
            if (pending) write(scrub(pending));
            pending = "";
        },
    };
}

async function writeTokenFile(file, token) {
    await mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
    await writeFile(file, token + "\n", { mode: 0o600 });
}

async function warnOpenRalphRuns() {
    const r = await op("runs_read", { select: "list", rootId, status: "open", limit: 200 }, READ_TIMEOUT_MS);
    if (!r.ok) return;
    const stale = (r.value?.items || []).filter((x) => x?.hints?.tool === "ralph");
    for (const x of stale) {
        warn(`  warning: run ${x.runId} (${x.label || "no label"}) from an earlier ralph is still open; its members are skipped as duplicate. If its driver is gone, close it from a director or human credential: runs_write close {runId, reason: abandoned}.`);
    }
}

async function finish() {
    if (activeSeat) await endActiveSeat("failed", `ralph: loop ended (${stats.exitReason})`);
    if (run.runId && run.coordinatorToken) {
        let r = await coordinatorCall("runs_write", [{ op: "close", runId: run.runId, reason: stats.closeReason }]);
        if (!r.ok && r.error?.code === "invalid_run_state" && stats.closeReason === "completed") {
            stats.closeReason = "cancelled";
            r = await coordinatorCall("runs_write", [{ op: "close", runId: run.runId, reason: "cancelled" }]);
        }
        if (!r.ok && r.error?.code !== "run_closed") warn(`  runs_write close failed (${errText(r.error)})`);
    }
    await rm(path.join(cfg.scratchDir, "tokens"), { recursive: true, force: true }).catch(() => {});
}

// -- Small helpers ------------------------------------------------------------

function usageError(msg) {
    console.error(`error: ${msg}`);
    process.exit(64);
}

function parseIntStrict(s, name) {
    const n = Number(s);
    if (!Number.isInteger(n) || n < 0) usageError(`--${name} must be a non-negative integer (got: ${s})`);
    return n;
}

function parseFloatStrict(s, name) {
    const n = Number(s);
    if (!Number.isFinite(n) || n < 0) usageError(`--${name} must be a non-negative number (got: ${s})`);
    return n;
}

function renderPrompt(template, vars) {
    return template.replace(/\$\{(\w+)\}/g, (_, k) => (Object.hasOwn(vars, k) ? vars[k] : `\${${k}}`));
}

function quoteArg(arg) {
    if (/^[\w@\-=.,/:]+$/.test(arg)) return arg;
    return `"${String(arg).replace(/"/g, '\\"')}"`;
}

// -- Output formatting --------------------------------------------------------

function formatPreflight() {
    const cleanupDesc = cfg.cleanupOnTerminal
        ? `smart (remove worktrees with no uncommitted changes / no commits ahead of ${cfg.baseRef}; preserve all others)`
        : "off (--no-cleanup)";
    const budget = openOp.budget
        ? Object.entries(openOp.budget).map(([k, v]) => `${k}=${v}`).join(" ")
        : "none (no run cap; --budget per iteration is the hard stop)";
    return `\n* Ralph Loop - Pre-flight
  API:           ${baseUrl}
  Root:          ${rootId}
  Filter:        ${cfg.filter || "(any ready queue item)"}
  Members:       ${cfg.items.length ? cfg.items.join(", ") : "(added as picked)"}
  Model:         ${cfg.model}
  Max iter:      ${cfg.max}
  Breakers:      ${cfg.gateBudget} consecutive gate failures | ${cfg.errorBudget} consecutive errors | ${cfg.idleBudget} consecutive idles
  Per iter:      $${cfg.budget} USD cap | ${cfg.ttl}s claim TTL | ${cfg.iterationTimeout}s timeout
  Run budget:    ${budget}
  Continuations: ${cfg.maxContinuations} per iteration (0 disables; resumes a clean exit with no RALPH_OUTCOME marker)
  Scratch dir:   ${cfg.scratchDir}
  Base ref:      ${cfg.baseRef}
  Cleanup:       ${cleanupDesc}
`;
}

function formatCleanupResult(worktreeName, result) {
    switch (result.action) {
        case "removed":
            return `  -> worktree ${worktreeName} removed (no commits to keep)`;
        case "preserved":
            return `  -> worktree ${worktreeName} preserved - ${result.reason}`;
        case "failed":
            return `  -> worktree ${worktreeName} cleanup failed - ${result.reason}`;
        default:
            return null;
    }
}

function formatIterEnd(i, outcome) {
    const sym = { terminal: "+", "gate-blocked": "#", error: "x", skip: "-" };
    const code = outcome.code ? ` (${outcome.code})` : "";
    const summary = outcome.summary || outcome.reason || "";
    return `${sym[outcome.status] || "?"} Iter ${i}: ${outcome.status}${code} [${String(outcome.itemId).slice(0, 8)}]${summary ? ` - ${summary}` : ""}`;
}

function formatSummary() {
    const elapsedMs = Date.now() - stats.startedAt.getTime();
    const minutes = Math.floor(elapsedMs / 60000);
    const seconds = Math.floor((elapsedMs % 60000) / 1000);
    const lines = [
        "",
        "* Ralph Loop - Final Summary",
        `  Started:       ${stats.startedAt.toISOString()}`,
        `  Duration:      ${minutes}m ${seconds}s`,
        `  Run:           ${run.runId || "(not opened)"} closed ${stats.closeReason}`,
        `  Iterations:    ${stats.iterations}`,
        `  Continued:     ${stats.continued} iteration(s) resumed at least once`,
        `  Exit reason:   ${stats.exitReason}`,
        "",
        "  Outcomes:",
        `    + ${stats.terminal} terminal`,
        `    # ${stats.gateBlocked} gate-blocked`,
        `    x ${stats.errored} errored`,
        `    - ${stats.skipped} skipped`,
        `    . ${stats.idle} idle selections`,
    ];
    const kept = stats.outcomes.filter((o) => o.outcome.status === "gate-blocked" || o.outcome.status === "error");
    if (kept.length) {
        lines.push("", "  Worktrees preserved for inspection:");
        for (const o of kept) {
            lines.push(`    iter ${o.iter} [${o.worktree}] (item ${String(o.outcome.itemId).slice(0, 8)}): ${o.outcome.reason || o.outcome.code || "see iteration log"}`);
        }
    }
    return lines.join("\n");
}

function printHelp() {
    console.log(`Usage: node ralph-loop.mjs [options]

Drain the TO queue Ralph-loop style over the 4.0 REST API. The loop opens one
run, and per iteration assigns an implementer seat, claims the item under that
seat, and spawns a fresh 'claude -p' in its own git worktree to work it per the
item's schema. Needs TASK_ORCHESTRATOR_API_URL (or apiUrl in client.json).

Options:
  --filter <expr>            Selection filter, keys: tag, type, priority,
                             parentId, ancestorId. Example: "tag=bug-fix priority=high"
  --root <rootId>            Project root the run opens on (default: the filter's
                             ancestorId, else project.rootId from config.yaml)
  --items <id,id,...>        Fixed run members; only these are drained
                             (default: each picked item is added as it is picked)
  --max <n>                  Max iterations (default: ${DEFAULTS.max})
  --gate-budget <n>          Consecutive gate failures before stopping (default: ${DEFAULTS.gateBudget})
  --error-budget <n>         Consecutive errors before stopping (default: ${DEFAULTS.errorBudget})
  --budget <usd>             Max USD per iteration, the hard stop (default: $${DEFAULTS.budget})
  --run-budget-tokens <n>    Run budget in tokens (default: none)
  --run-budget-usd <usd>     Run budget in US dollars (default: none)
  --max-attempts <n>         Attempts per seat before the run pauses (default: none)
  --ttl <seconds>            Claim TTL per iteration (default: ${DEFAULTS.ttl}s, range 60-86400)
  --iteration-timeout <s>    Kill an iteration after this many seconds: SIGTERM,
                             then SIGKILL 10s later (default: ${DEFAULTS.iterationTimeout})
  --model <name>             Model for the iteration agent (default: ${DEFAULTS.model})
  --base-ref <ref>           Upstream ref for the cleanup "ahead of base" check
                             (default: origin/main)
  --cleanup-on-terminal      Smart-cleanup worktrees after terminal outcomes (default: true)
  --no-cleanup               Preserve every worktree
  --max-continuations <n>    Resume attempts for an iteration that exits cleanly
                             without a RALPH_OUTCOME marker (default: ${DEFAULTS.maxContinuations}; 0 disables)
  --idle-budget <n>          Consecutive idle selections tolerated before stopping
                             (default: ${DEFAULTS.idleBudget})
  --scratch-dir <dir>        Private directory for the per-iteration seat token
                             files (default: <tmpdir>/task-orchestrator/ralph/<label>)
  --claude-bin <path>        claude executable (default: claude); a .mjs/.js path
                             runs under this Node binary
  --dry-run                  Print the run open op and the iteration command; no request
  -h, --help                 Show this message

The seat token reaches the iteration only as a file path
(TASK_ORCHESTRATOR_SEAT_TOKEN_FILE); token values never appear in argv, env
values, prompt text or this script's output.

Outcomes: see the generated outcome table in skills/ralph/SKILL.md.

Exit codes:
  0    Loop ended normally (drained, iteration cap, gate budget, idle budget,
       run paused or closed)
  2    Error budget exhausted, or a REST call the loop depends on failed
       (open, coordinator credential refused)
  64   CLI argument error, no REST API URL, or no project root
  70   Could not read iteration prompt
  130  Interrupted (SIGINT): iteration stopped, seat ended, run closed cancelled
  143  Terminated (SIGTERM): same as 130
`);
}
