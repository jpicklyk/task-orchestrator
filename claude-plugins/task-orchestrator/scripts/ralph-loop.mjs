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

import { parseArgs } from "node:util";
import { spawn } from "node:child_process";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import path from "node:path";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PROMPT_PATH = path.resolve(HERE, "../skills/ralph/iteration-prompt.md");

const DEFAULTS = {
    max: 10,
    gateBudget: 3,
    errorBudget: 2,
    budget: 5, // USD per iteration
    ttl: 1800, // seconds (30 min)
    model: "sonnet",
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
        "cleanup-on-terminal": { type: "boolean", default: false },
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
    cleanupOnTerminal: values["cleanup-on-terminal"],
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

// ── Loop state ─────────────────────────────────────────────────────────────
const stats = {
    startedAt: new Date(),
    iterations: 0,
    terminal: 0,
    gateBlocked: 0,
    errored: 0,
    skipped: 0,
    noItem: 0,
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

    // Worktree name uses iteration index — the iteration agent will know its own
    // claimed item ID after step 2, but the worktree is created up-front by claude.
    const worktreeName = `ralph-${cfg.actor.split("-").slice(-1)[0].slice(0, 8)}-${iterIndex}`;

    const prompt = renderPrompt(promptTemplate, {
        filter: cfg.filter,
        actor_id: cfg.actor,
        actor_kind: "orchestrator",
        ttl: String(cfg.ttl),
    });

    const args = [
        "-p",
        `--worktree=${worktreeName}`,
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

    console.log(formatIterStart(iterIndex, cfg.max, worktreeName));

    const { exitCode, outcome, error } = await runIteration(args);
    stats.outcomes.push({ iter: iterIndex, exitCode, outcome, error });

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
process.exit(stats.errored > 0 || stats.consecutiveErrors > 0 ? 2 : 0);

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

function runIteration(args) {
    return new Promise((resolve) => {
        const stdoutChunks = [];
        const stderrChunks = [];

        const child = spawn("claude", args, {
            stdio: ["ignore", "pipe", "pipe"],
            shell: false,
        });

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
            resolve({
                exitCode: -1,
                outcome: { status: "error", reason: `spawn error: ${err.message}` },
                error: err.message,
            });
        });

        child.on("close", (code) => {
            const stdout = Buffer.concat(stdoutChunks).toString("utf8");
            const outcome = parseOutcome(stdout, code);
            resolve({ exitCode: code, outcome, error: null });
        });
    });
}

/**
 * Parse the iteration agent's outcome from claude's JSON output.
 * Expected: a `RALPH_OUTCOME: {...}` marker line in the agent's final message.
 * Falls back to inferring outcome from claude's exit code / stop_reason.
 */
function parseOutcome(stdout, exitCode) {
    // claude --output-format json returns a single JSON object on stdout.
    let claudeResult;
    try {
        claudeResult = JSON.parse(stdout.trim().split("\n").filter(Boolean).pop() || "{}");
    } catch {
        claudeResult = {};
    }

    const finalText = claudeResult.result || "";
    const markerMatch = finalText.match(/RALPH_OUTCOME:\s*(\{[^\n]*\})/);
    if (markerMatch) {
        try {
            return JSON.parse(markerMatch[1]);
        } catch {
            // fall through to inference
        }
    }

    // Inference fallback — claude exit code or stop_reason gives us a hint.
    if (exitCode === 0) {
        // No marker but successful run — assume terminal, but flag it.
        return {
            status: "error",
            reason: "iteration agent exited cleanly without RALPH_OUTCOME marker",
        };
    }
    if (claudeResult.stop_reason === "max_budget") {
        return { status: "error", reason: "iteration hit --max-budget-usd cap" };
    }
    return {
        status: "error",
        reason: `iteration failed (exit ${exitCode}, stop_reason: ${claudeResult.stop_reason || "unknown"})`,
    };
}

// ── Output formatting ──────────────────────────────────────────────────────

function formatPreflight(cfg) {
    return `\n◆ Ralph Loop — Pre-flight
  Filter:        ${cfg.filter || "(any claimable queue item)"}
  Actor:         ${cfg.actor}
  Model:         ${cfg.model}
  Max iter:      ${cfg.max}
  Budgets:       ${cfg.gateBudget} consecutive gate failures | ${cfg.errorBudget} consecutive errors
  Per iter:      $${cfg.budget} USD cap | ${cfg.ttl}s claim TTL
  Cleanup:       ${cfg.cleanupOnTerminal ? "auto-remove worktree on terminal" : "leave worktree for review"}
`;
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
                const ref = o.outcome.itemId ? ` (item ${o.outcome.itemId.slice(0, 8)})` : "";
                lines.push(`    iter ${o.iter}${ref}: ${o.outcome.reason || "see iteration log"}`);
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
  --cleanup-on-terminal      Auto-remove worktree after terminal outcome
                             (default: leave for inspection)
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
  error          Tool error, build failure, budget cap hit
  skip           Already-terminal, claim contention, or filter mismatch
  no-item        No claimable items match filter -- queue drained

Compose with /loop for autonomous cadence:
  /loop 30m node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix"

Exit codes:
  0    Loop completed normally (queue empty or iteration cap reached)
  2    Loop stopped due to consecutive errors
  64   CLI argument error
  70   Could not read iteration prompt
`);
}
