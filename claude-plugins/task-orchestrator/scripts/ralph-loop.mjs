#!/usr/bin/env node
// ralph-loop.mjs — Ralph-style queue drain for MCP Task Orchestrator.
//
// Each iteration spawns a fresh `claude -p` process in its own git worktree
// to claim one item from the TO queue and work it to terminal per the item's
// schema. The iteration agent signals outcome via a `RALPH_OUTCOME:` JSON
// marker on stdout, which this script parses to drive loop control and
// circuit breakers.
//
// Stdlib-only. Node 18+.

import { parseArgs, promisify } from "node:util";
import { spawn, exec } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";
import {
    parseClaudeJson,
    parseOutcome,
    updateSpend,
    decideContinuation,
    buildResumeArgs,
} from "./ralph-lib.mjs";

const execAsync = promisify(exec);

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROMPT_PATH = path.resolve(HERE, "../skills/ralph/iteration-prompt.md");

// Sent to a resumed iteration (`claude -p --resume <session_id>`) when the prior
// run exited cleanly without a RALPH_OUTCOME marker. Keep in sync with the
// "How your turn ends" operating principle in output-styles/ralph-iteration.md.
const RESUME_MESSAGE =
    "You ended your turn without a RALPH_OUTCOME marker, which ends this iteration. " +
    "Continue any outstanding work on the claimed item now. If something blocks you, emit " +
    "RALPH_OUTCOME with status gate-blocked and the reason. If the item already reached " +
    "terminal, emit RALPH_OUTCOME terminal.";

const DEFAULTS = {
    max: 10,
    gateBudget: 3,
    errorBudget: 2,
    budget: 5, // USD per iteration
    ttl: 1800, // seconds (30 min)
    model: "sonnet",
    maxContinuations: 2, // resume attempts for a marker-less clean exit; 0 disables
};

// ── CLI parsing ────────────────────────────────────────────────────────────
const { values } = parseArgs({
    options: {
        filter: { type: "string", default: "" },
        max: { type: "string", default: String(DEFAULTS.max) },
        "gate-budget": { type: "string", default: String(DEFAULTS.gateBudget) },
        "error-budget": { type: "string", default: String(DEFAULTS.errorBudget) },
        budget: { type: "string", default: String(DEFAULTS.budget) },
        ttl: { type: "string", default: String(DEFAULTS.ttl) },
        actor: { type: "string", default: "" },
        model: { type: "string", default: DEFAULTS.model },
        "base-ref": { type: "string", default: "origin/main" },
        "cleanup-on-terminal": { type: "boolean", default: true },
        "no-cleanup": { type: "boolean", default: false },
        "max-continuations": { type: "string", default: String(DEFAULTS.maxContinuations) },
        "dry-run": { type: "boolean", default: false },
        help: { type: "boolean", default: false, short: "h" },
    },
});

if (values.help) {
    printHelp();
    process.exit(0);
}

const cfg = {
    filter: values.filter,
    max: parseIntStrict(values.max, "max"),
    gateBudget: parseIntStrict(values["gate-budget"], "gate-budget"),
    errorBudget: parseIntStrict(values["error-budget"], "error-budget"),
    budget: parseFloatStrict(values.budget, "budget"),
    ttl: parseIntStrict(values.ttl, "ttl"),
    actor: values.actor || `ralph-${process.pid}-${Date.now()}`,
    model: values.model,
    baseRef: values["base-ref"],
    // --no-cleanup overrides --cleanup-on-terminal (compatibility with both forms)
    cleanupOnTerminal: values["no-cleanup"] ? false : values["cleanup-on-terminal"],
    maxContinuations: parseIntStrict(values["max-continuations"], "max-continuations"),
    dryRun: values["dry-run"],
};

if (cfg.ttl < 60 || cfg.ttl > 86400) {
    console.error("error: --ttl must be between 60 and 86400 seconds");
    process.exit(64);
}
if (cfg.budget <= 0) {
    console.error("error: --budget must be > 0");
    process.exit(64);
}

// ── Load iteration prompt ──────────────────────────────────────────────────
let promptTemplate;
try {
    promptTemplate = await readFile(PROMPT_PATH, "utf8");
} catch (err) {
    console.error(`error: could not read iteration prompt at ${PROMPT_PATH}`);
    console.error(err.message);
    process.exit(70);
}

// ── Signal forwarding ──────────────────────────────────────────────────────
// Forward SIGINT/SIGTERM to the currently running iteration child so a Ctrl+C
// at the loop driver doesn't leave an orphaned `claude -p` process running
// to budget cap. Track the active child in a module-level handle that
// runIteration() updates.
let currentChild = null;
let shuttingDown = false;
function installSignalHandler(sig, exitCode) {
    process.on(sig, () => {
        if (shuttingDown) return;
        shuttingDown = true;
        process.stderr.write(`\n[ralph-loop] received ${sig}; forwarding to iteration child...\n`);
        if (currentChild && !currentChild.killed) {
            try {
                currentChild.kill(sig);
            } catch {
                // best-effort
            }
        }
        // Give the child a moment to flush before exiting; if it lingers, the
        // 'close' handler in runIteration will trigger first and we'll fall
        // through to a normal summary. This timer is the hard backstop.
        setTimeout(() => process.exit(exitCode), 3000).unref();
    });
}
installSignalHandler("SIGINT", 130);
installSignalHandler("SIGTERM", 143);

// ── Loop state ─────────────────────────────────────────────────────────────
const stats = {
    startedAt: new Date(),
    iterations: 0,
    terminal: 0,
    gateBlocked: 0,
    errored: 0,
    skipped: 0,
    noItem: 0,
    continued: 0, // iterations that needed >=1 resume continuation before their final outcome
    consecutiveGateFailures: 0,
    consecutiveErrors: 0,
    exitReason: null,
    outcomes: [],
};

console.log(formatPreflight(cfg));

// ── Main loop ──────────────────────────────────────────────────────────────
while (stats.iterations < cfg.max) {
    stats.iterations++;
    const iterIndex = stats.iterations;

    // The worktree is created up-front by `claude --worktree=<name>`, before
    // the iteration agent has claimed an item. Use a fully unique temp name
    // (pid + full timestamp + iter) here to avoid collisions under concurrent
    // ralph-loops; we'll rename it to `ralph-<short-uuid>-<iter>` after the
    // iteration completes and we know the claimed UUID from the outcome.
    const tempWorktreeName = `ralph-${process.pid}-${Date.now()}-${iterIndex}`;
    let worktreeName = tempWorktreeName;

    const prompt = renderPrompt(promptTemplate, {
        filter: cfg.filter,
        actor_id: cfg.actor,
        actor_kind: "orchestrator",
        ttl: String(cfg.ttl),
    });

    const args = [
        "-p",
        `--worktree=${tempWorktreeName}`,
        "--settings",
        JSON.stringify({ outputStyle: "task-orchestrator:ralph-iteration" }),
        // Ralph runs autonomously; in -p mode there's no interactive prompt for MCP/tool
        // permissions, so unpermitted tools auto-deny and the iteration aborts. Bypass
        // permission prompts so the iteration agent can complete its work. Risk surface is
        // bounded by: the worktree boundary (file edits stay inside), the MCP server's own
        // ACL, the --max-budget-usd cap, and the schema-driven scope of the iteration.
        "--permission-mode",
        "bypassPermissions",
        "--max-budget-usd",
        String(cfg.budget),
        "--output-format",
        "json",
        "--model",
        cfg.model,
        prompt,
    ];

    if (cfg.dryRun) {
        console.log(`\n[dry-run] iteration ${iterIndex}:`);
        console.log(`  claude ${args.map(quoteArg).join(" ")}`);
        console.log("\n[dry-run] exiting after first iteration preview.");
        process.exit(0);
    }

    console.log(formatIterStart(iterIndex, cfg.max, tempWorktreeName));

    // Run the iteration, then — while it exited cleanly with no RALPH_OUTCOME
    // marker — resume it in place with `claude -p --resume <session_id>` rather
    // than treating a stray progress-report turn as a failed iteration. This
    // must happen BEFORE the worktree rename below: `git worktree move`
    // relocates the directory a resumed session's tools would run inside.
    let currentArgs = args;
    let currentCwd; // undefined for the initial spawn (--worktree=... creates it)
    let continuationsUsed = 0;
    let spentUsd = 0;
    let exitCode, envelope, error, parsed;

    for (;;) {
        ({ exitCode, envelope, error } = await runIteration(currentArgs, { cwd: currentCwd }));
        spentUsd = updateSpend(spentUsd, envelope);
        parsed = error
            ? { outcome: { status: "error", reason: `spawn error: ${error}` }, markerFound: false }
            : parseOutcome(envelope, exitCode);

        const decision = decideContinuation({
            exitCode,
            markerFound: parsed.markerFound,
            sessionId: envelope?.session_id,
            continuationsUsed,
            maxContinuations: cfg.maxContinuations,
            budget: cfg.budget,
            spentUsd,
        });

        if (!decision.continue) {
            if (!parsed.markerFound && exitCode === 0 && continuationsUsed > 0) {
                parsed.outcome = {
                    ...parsed.outcome,
                    reason: `${parsed.outcome.reason} (after ${continuationsUsed} continuation(s))`,
                };
            }
            break;
        }

        continuationsUsed++;
        console.log(
            `  ↳ no RALPH_OUTCOME marker; resuming session ${envelope.session_id} ` +
                `(continuation ${continuationsUsed}/${cfg.maxContinuations}, $${decision.remainingUsd.toFixed(2)} remaining)`
        );
        currentCwd = path.resolve(".claude", "worktrees", tempWorktreeName);
        currentArgs = buildResumeArgs({
            sessionId: envelope.session_id,
            cfg,
            remainingBudget: decision.remainingUsd,
            message: RESUME_MESSAGE,
        });
    }

    const outcome = parsed.outcome;
    if (continuationsUsed > 0) stats.continued++;
    stats.outcomes.push({ iter: iterIndex, exitCode, outcome, error, continuations: continuationsUsed });

    // Post-iteration: if the iteration claimed an item, rename the worktree to
    // `ralph-<short-uuid>-<iter>` so preserved worktrees are traceable to the
    // item they were working on. The iteration's claude process has exited by
    // now, so file locks aren't a concern (incl. on Windows). On rename
    // failure, we keep the temp name and continue — purely cosmetic.
    if (outcome.itemId) {
        const uuidWorktreeName = `ralph-${outcome.itemId.slice(0, 8)}-${iterIndex}`;
        if (uuidWorktreeName !== tempWorktreeName) {
            const renameResult = await renameWorktree(tempWorktreeName, uuidWorktreeName);
            if (renameResult.ok) {
                worktreeName = uuidWorktreeName;
                console.log(`  ↳ worktree renamed: ${tempWorktreeName} → ${uuidWorktreeName}`);
            } else {
                console.log(`  ↳ worktree rename failed (${renameResult.reason}); kept ${tempWorktreeName}`);
            }
        }
    }
    // Annotate the outcome record so the final summary can reference the
    // effective name even when no rename happened.
    stats.outcomes[stats.outcomes.length - 1].worktree = worktreeName;

    // Update counters based on outcome
    switch (outcome.status) {
        case "terminal":
            stats.terminal++;
            stats.consecutiveGateFailures = 0;
            stats.consecutiveErrors = 0;
            break;
        case "gate-blocked":
            stats.gateBlocked++;
            stats.consecutiveGateFailures++;
            stats.consecutiveErrors = 0;
            break;
        case "error":
            stats.errored++;
            stats.consecutiveErrors++;
            stats.consecutiveGateFailures = 0;
            break;
        case "skip":
            stats.skipped++;
            // skip doesn't reset or increment circuit breakers
            break;
        case "no-item":
            stats.noItem++;
            stats.exitReason = "queue empty (no claimable items match filter)";
            break;
    }

    console.log(formatIterEnd(iterIndex, outcome));

    // Cleanup decision: only act on terminal/no-item outcomes; preserve worktrees
    // for gate-blocked/error/skip so the user can inspect/debug. Within terminal,
    // preserve if the worktree has uncommitted changes or unpushed commits — those
    // are the cases where the iteration produced something worth pushing or reviewing.
    if (cfg.cleanupOnTerminal && (outcome.status === "terminal" || outcome.status === "no-item")) {
        const cleanupResult = await maybeCleanupWorktree(worktreeName, cfg.baseRef);
        const formatted = formatCleanupResult(worktreeName, cleanupResult);
        if (formatted) console.log(formatted);
        // Annotate the outcome record so the final summary reflects what happened
        stats.outcomes[stats.outcomes.length - 1].cleanup = cleanupResult;
    }

    if (stats.exitReason) break;
    if (stats.consecutiveGateFailures >= cfg.gateBudget) {
        stats.exitReason = `gate failure budget exhausted (${cfg.gateBudget} consecutive)`;
        break;
    }
    if (stats.consecutiveErrors >= cfg.errorBudget) {
        stats.exitReason = `error budget exhausted (${cfg.errorBudget} consecutive)`;
        break;
    }
}

if (!stats.exitReason && stats.iterations >= cfg.max) {
    stats.exitReason = `iteration cap reached (${cfg.max})`;
}

console.log(formatSummary(stats));
// Exit 2 only when the loop itself was aborted by exhausting the consecutive
// error budget. Individual errored iterations during an otherwise healthy
// drain (queue-empty / iteration-cap / gate-budget exit) do not flip the
// loop-level exit code — the per-iteration outcomes are already in the
// summary and individual error counts are visible to callers via stdout.
const abortedOnErrorBudget = stats.exitReason && stats.exitReason.startsWith("error budget");
process.exit(abortedOnErrorBudget ? 2 : 0);

// ── Helpers ────────────────────────────────────────────────────────────────

function parseIntStrict(s, name) {
    const n = parseInt(s, 10);
    if (Number.isNaN(n) || n < 0) {
        console.error(`error: --${name} must be a non-negative integer (got: ${s})`);
        process.exit(64);
    }
    return n;
}

function parseFloatStrict(s, name) {
    const n = parseFloat(s);
    if (Number.isNaN(n) || n < 0) {
        console.error(`error: --${name} must be a non-negative number (got: ${s})`);
        process.exit(64);
    }
    return n;
}

function renderPrompt(template, vars) {
    return template.replace(/\$\{(\w+)\}/g, (_, k) =>
        Object.hasOwn(vars, k) ? vars[k] : `\${${k}}`
    );
}

function quoteArg(arg) {
    if (/^[\w@\-=.,/:]+$/.test(arg)) return arg;
    return `"${arg.replace(/"/g, '\\"')}"`;
}

function runIteration(args, { cwd } = {}) {
    return new Promise((resolve) => {
        const stdoutChunks = [];
        const stderrChunks = [];

        const child = spawn("claude", args, {
            stdio: ["ignore", "pipe", "pipe"],
            shell: false,
            ...(cwd ? { cwd } : {}),
        });
        // Expose for SIGINT/SIGTERM forwarding from the loop driver.
        currentChild = child;

        child.stdout.on("data", (chunk) => {
            stdoutChunks.push(chunk);
            // Stream live output so the user sees progress; mark it for clarity.
            process.stdout.write(chunk);
        });
        child.stderr.on("data", (chunk) => {
            stderrChunks.push(chunk);
            process.stderr.write(chunk);
        });

        child.on("error", (err) => {
            currentChild = null;
            resolve({ exitCode: -1, envelope: {}, error: err.message });
        });

        child.on("close", (code) => {
            currentChild = null;
            const stdout = Buffer.concat(stdoutChunks).toString("utf8");
            const envelope = parseClaudeJson(stdout);
            resolve({ exitCode: code, envelope, error: null });
        });
    });
}

// ── Output formatting ──────────────────────────────────────────────────────

function formatPreflight(cfg) {
    const cleanupDesc = cfg.cleanupOnTerminal
        ? `smart (remove worktrees with no uncommitted changes / no commits ahead of ${cfg.baseRef}; preserve all others)`
        : "off (--no-cleanup)";
    return `\n◆ Ralph Loop — Pre-flight
  Filter:        ${cfg.filter || "(any claimable queue item)"}
  Actor:         ${cfg.actor}
  Model:         ${cfg.model}
  Max iter:      ${cfg.max}
  Budgets:       ${cfg.gateBudget} consecutive gate failures | ${cfg.errorBudget} consecutive errors
  Per iter:      $${cfg.budget} USD cap | ${cfg.ttl}s claim TTL
  Continuations: ${cfg.maxContinuations} per iteration (0 disables; resumes a clean exit with no RALPH_OUTCOME marker)
  Base ref:      ${cfg.baseRef}
  Cleanup:       ${cleanupDesc}
`;
}

/**
 * Rename an existing worktree directory via `git worktree move`. Used after
 * an iteration completes so preserved worktrees carry the claimed item's
 * UUID prefix in their name (e.g., `ralph-44abe365-1`) instead of the
 * pid+timestamp temp name. Returns { ok: true } on success or
 * { ok: false, reason } on failure (e.g., destination exists, ref mismatch).
 */
async function renameWorktree(oldName, newName) {
    if (oldName === newName) return { ok: true };
    const oldPath = path.posix.join(".claude", "worktrees", oldName);
    const newPath = path.posix.join(".claude", "worktrees", newName);
    try {
        await execAsync(`git worktree move "${oldPath}" "${newPath}"`);
        return { ok: true };
    } catch (err) {
        // Surface a single-line reason; git's stderr is multi-line.
        const reason = (err.stderr || err.message || "")
            .split("\n")
            .map((s) => s.trim())
            .filter(Boolean)[0] || "unknown error";
        return { ok: false, reason };
    }
}

/**
 * Smart-cleanup decision for a finished iteration's worktree.
 * Returns one of:
 *   { action: "removed" }
 *   { action: "preserved", reason: <string> }
 *   { action: "absent" }    — worktree never existed (e.g., no-item iteration didn't reach spawn)
 *   { action: "failed", reason: <string> }
 *
 * Preserves the worktree if it has uncommitted changes OR commits ahead of
 * the configured base ref (default `origin/main`, override with --base-ref).
 * Removes it otherwise. This way, smoke-test-style runs that produce no diffs
 * get cleaned up automatically, while real-work iterations with commits to
 * push are preserved for review.
 */
async function maybeCleanupWorktree(worktreeName, baseRef) {
    const worktreePath = path.posix.join(".claude", "worktrees", worktreeName);

    // Check that the worktree actually exists in git's registry
    try {
        const { stdout: listOut } = await execAsync("git worktree list --porcelain");
        if (!listOut.includes(worktreePath) && !listOut.includes(worktreePath.replace(/\//g, path.sep))) {
            return { action: "absent" };
        }
    } catch (err) {
        return { action: "failed", reason: `git worktree list failed: ${err.message}` };
    }

    // Check for uncommitted changes (any porcelain output = dirty)
    try {
        const { stdout: statusOut } = await execAsync(`git -C "${worktreePath}" status --porcelain`);
        if (statusOut.trim().length > 0) {
            return { action: "preserved", reason: "uncommitted changes present" };
        }
    } catch (err) {
        return { action: "failed", reason: `git status failed: ${err.message}` };
    }

    // Check for commits ahead of the configured base ref. If the ref isn't
    // resolvable, preserve the worktree — that's the safer error than
    // accidentally removing one with real commits.
    try {
        const { stdout: aheadOut } = await execAsync(
            `git -C "${worktreePath}" rev-list --count ${baseRef}..HEAD`
        );
        const ahead = parseInt(aheadOut.trim(), 10);
        if (Number.isFinite(ahead) && ahead > 0) {
            return { action: "preserved", reason: `${ahead} commit(s) ahead of ${baseRef}` };
        }
    } catch {
        return { action: "preserved", reason: `could not compare against ${baseRef}` };
    }

    // Clean — remove the worktree
    try {
        await execAsync(`git worktree remove "${worktreePath}"`);
        return { action: "removed" };
    } catch (err) {
        return { action: "failed", reason: `git worktree remove failed: ${err.message}` };
    }
}

function formatCleanupResult(worktreeName, result) {
    switch (result.action) {
        case "removed":
            return `  ↳ worktree ${worktreeName} removed (no commits to keep)`;
        case "preserved":
            return `  ↳ worktree ${worktreeName} preserved — ${result.reason}`;
        case "absent":
            return null; // nothing to report
        case "failed":
            return `  ↳ worktree ${worktreeName} cleanup failed — ${result.reason}`;
        default:
            return null;
    }
}

function formatIterStart(i, max, worktree) {
    return `\n── Iteration ${i}/${max} ─ worktree: ${worktree} ──`;
}

function formatIterEnd(i, outcome) {
    const sym = {
        terminal: "✓",
        "gate-blocked": "⊘",
        error: "✗",
        skip: "—",
        "no-item": "◯",
    };
    const itemRef = outcome.itemId ? ` [${outcome.itemId.slice(0, 8)}]` : "";
    const summary = outcome.summary || outcome.reason || "";
    return `${sym[outcome.status] || "?"} Iter ${i}: ${outcome.status}${itemRef}${summary ? ` — ${summary}` : ""}`;
}

function formatSummary(stats) {
    const elapsedMs = Date.now() - stats.startedAt.getTime();
    const minutes = Math.floor(elapsedMs / 60000);
    const seconds = Math.floor((elapsedMs % 60000) / 1000);
    const lines = [
        "",
        "◆ Ralph Loop — Final Summary",
        `  Started:       ${stats.startedAt.toISOString()}`,
        `  Duration:      ${minutes}m ${seconds}s`,
        `  Iterations:    ${stats.iterations}`,
        `  Continued:     ${stats.continued} iteration(s) resumed at least once`,
        `  Exit reason:   ${stats.exitReason}`,
        "",
        "  Outcomes:",
        `    ✓ ${stats.terminal} terminal`,
        `    ⊘ ${stats.gateBlocked} gate-blocked`,
        `    ✗ ${stats.errored} errored`,
        `    — ${stats.skipped} skipped`,
        `    ◯ ${stats.noItem} no-item`,
    ];
    if (stats.gateBlocked > 0 || stats.errored > 0) {
        lines.push("");
        lines.push("  Worktrees preserved for inspection:");
        for (const o of stats.outcomes) {
            if (o.outcome.status === "gate-blocked" || o.outcome.status === "error") {
                const wt = o.worktree ? ` [${o.worktree}]` : "";
                const ref = o.outcome.itemId ? ` (item ${o.outcome.itemId.slice(0, 8)})` : "";
                lines.push(`    iter ${o.iter}${wt}${ref}: ${o.outcome.reason || "see iteration log"}`);
            }
        }
    }
    return lines.join("\n");
}

function printHelp() {
    console.log(`Usage: node ralph-loop.mjs [options]

Drain the TO queue Ralph-loop style. Each iteration spawns a fresh
'claude -p' process in its own git worktree to claim and work one
item from the queue per the item's schema.

Options:
  --filter <expr>            Filter for claimable items
                             Keys: tag, type, priority, parentId
                             Example: "tag=bug-fix priority=high"
  --max <n>                  Max iterations (default: ${DEFAULTS.max})
  --gate-budget <n>          Consecutive gate failures before stopping
                             (default: ${DEFAULTS.gateBudget})
  --error-budget <n>         Consecutive errors before stopping
                             (default: ${DEFAULTS.errorBudget})
  --budget <usd>             Max USD per iteration (default: $${DEFAULTS.budget})
  --ttl <seconds>            claim_item TTL per iteration
                             (default: ${DEFAULTS.ttl}s, range 60-86400)
  --actor <id>               Actor id for claim_item
                             (default: ralph-<pid>-<timestamp>)
  --model <name>             Model for the iteration agent
                             (default: ${DEFAULTS.model}; e.g., sonnet, opus)
  --base-ref <ref>           Upstream ref to compare against for cleanup
                             "ahead of base" detection (default: origin/main).
                             Set to e.g. origin/master, origin/develop, or
                             upstream/main for projects whose default
                             branch is not main on origin.
  --cleanup-on-terminal      Smart-cleanup worktrees after terminal/no-item
                             outcomes (default: true). Removes worktrees with
                             no uncommitted changes AND no commits ahead of
                             --base-ref; preserves all others. gate-blocked
                             and error outcomes always preserve their worktrees.
  --no-cleanup               Disable smart cleanup; preserve all worktrees
                             regardless of state. Equivalent to passing
                             --cleanup-on-terminal=false.
  --max-continuations <n>    Resume attempts for an iteration that exits
                             cleanly without a RALPH_OUTCOME marker, via
                             'claude -p --resume <session_id>' in the same
                             worktree (no --worktree on a resume). Stops
                             resuming once the cap is hit or remaining
                             per-iteration budget drops below $0.25.
                             (default: ${DEFAULTS.maxContinuations}; 0 disables)
  --dry-run                  Print iteration command and exit
  -h, --help                 Show this message

Each iteration runs under the 'task-orchestrator:ralph-iteration' output style
(passed via 'claude --settings'). That style suppresses orchestrator-mode chrome
(tier classification, workflow-analyst footer, plan mode) and encodes iteration
discipline (schema is contract, no auto-memory, no further dispatch).

Iterations run with --permission-mode bypassPermissions because there is no
interactive prompt in 'claude -p' mode; unpermitted MCP/tool calls would
auto-deny and the iteration would abort. Risk is bounded by the worktree, the
MCP ACL, the --max-budget-usd cap, and the schema-scoped iteration prompt.

Outcomes (signaled by RALPH_OUTCOME marker in iteration agent stdout):
  terminal       Item reached terminal role per its schema
  gate-blocked   Required notes couldn't be filled autonomously
  error          Tool error, build failure, budget cap hit, or a marker-less
                 clean exit whose resume continuations (see
                 --max-continuations) were exhausted without ever producing
                 a RALPH_OUTCOME marker
  skip           Already-terminal, claim contention, resource-lease contention
                 (advance_item errorCode=resource_unavailable -- claim released,
                 iteration moves to a different item), or filter mismatch
  no-item        No claimable items match filter -- queue drained

Compose with /loop for autonomous cadence:
  /loop 30m node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix"

Exit codes:
  0    Loop completed normally (queue empty, iteration cap, or
       gate-failure budget exhausted). Individual errored iterations
       during an otherwise healthy drain do NOT change the loop
       exit code -- they are visible in the summary instead.
  2    Loop aborted because the consecutive-error budget was exhausted.
  64   CLI argument error
  70   Could not read iteration prompt
  130  Interrupted (SIGINT received and forwarded to iteration)
  143  Terminated (SIGTERM received and forwarded to iteration)
`);
}
