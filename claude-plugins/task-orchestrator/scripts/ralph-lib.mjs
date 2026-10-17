// ralph-lib.mjs - pure helpers extracted from ralph-loop.mjs for unit testing.
//
// ralph-loop.mjs runs its CLI parsing and main loop at module top level (including a
// top-level `await` and `process.exit` calls), so it isn't importable by tests. These are
// the pure, side-effect-free pieces factored out so they can be exercised in isolation:
// claude's JSON envelope parsing, the RALPH_OUTCOME marker scan, the resume-continuation
// decision, and the 4.0 run plumbing (run open op, selection over items_read next, the
// per-loop skip list, the outcome table shared by the three prompt sources, seat-outcome and
// loop-effect mapping, usage ops, token redaction).
//
// Stdlib-only. Node 18+.

import path from "node:path";
import { fileURLToPath } from "node:url";
import { ERROR_KINDS } from "../hooks/lib/tool-names.mjs";

/**
 * Absolute path to the iteration system prompt appended to every iteration via
 * `--append-system-prompt-file`. Resolved from this module's own location so it
 * works from the plugin cache as well as the source tree.
 */
export const ITERATION_SYSTEM_PROMPT_PATH = path.resolve(
    path.dirname(fileURLToPath(import.meta.url)),
    "../skills/ralph/iteration-system-prompt.md",
);

/**
 * Settings JSON shared by the initial and resume spawns. Pins the output style
 * to `default` so a user's own configured style never leaks into an iteration.
 */
const ITERATION_SETTINGS = JSON.stringify({ outputStyle: "default" });

/**
 * Build the argv for the initial iteration spawn. The prompt is the last element.
 */
export function buildIterationArgs({
    worktreeName,
    cfg,
    prompt,
    systemPromptPath = ITERATION_SYSTEM_PROMPT_PATH,
}) {
    return [
        "-p",
        `--worktree=${worktreeName}`,
        "--append-system-prompt-file",
        systemPromptPath,
        "--settings",
        ITERATION_SETTINGS,
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
}

/**
 * Parse claude's stdout (--output-format json) into the result envelope.
 * Tries a direct parse first (handles both compact and pretty-printed JSON),
 * then falls back to extracting the last balanced top-level object - which
 * tolerates streamed log lines preceding the JSON envelope.
 */
export function parseClaudeJson(stdout) {
    const trimmed = stdout.trim();
    if (!trimmed) return {};
    try {
        return JSON.parse(trimmed);
    } catch {
        // fall through
    }
    const lastObj = extractLastBalancedJson(trimmed);
    if (lastObj) {
        try {
            return JSON.parse(lastObj);
        } catch {
            // fall through
        }
    }
    return {};
}

/**
 * Find the last top-level balanced JSON object in `text`.
 * Walks forward from each `{`, tracking depth + string state, and returns the
 * substring of the latest fully-closed object. Returns null if none.
 */
export function extractLastBalancedJson(text) {
    let lastStart = -1;
    let lastEnd = -1;
    let i = 0;
    while (i < text.length) {
        if (text[i] !== "{") {
            i++;
            continue;
        }
        const closeIdx = scanBalancedObject(text, i);
        if (closeIdx >= 0) {
            lastStart = i;
            lastEnd = closeIdx;
            i = closeIdx + 1;
        } else {
            i++;
        }
    }
    return lastStart >= 0 ? text.slice(lastStart, lastEnd + 1) : null;
}

/**
 * Locate the `RALPH_OUTCOME:` marker in `text` and return the JSON substring
 * that follows. Uses balanced-brace scanning so the JSON body may contain
 * newlines, nested objects, or escaped quotes - all of which broke the
 * original single-line regex. Returns null if no valid marker is found.
 *
 * Uses lastIndexOf so a stray "RALPH_OUTCOME:" appearing earlier in the
 * agent's reasoning text doesn't shadow the real final-message marker.
 */
export function extractOutcomeMarker(text) {
    const markerIdx = text.lastIndexOf("RALPH_OUTCOME:");
    if (markerIdx === -1) return null;
    const startBrace = text.indexOf("{", markerIdx);
    if (startBrace === -1) return null;
    const closeIdx = scanBalancedObject(text, startBrace);
    if (closeIdx < 0) return null;
    return text.slice(startBrace, closeIdx + 1);
}

/**
 * Scan forward from `start` (must point at `{`), tracking string state and
 * brace depth, and return the index of the matching closing `}` - or -1 if
 * the object isn't balanced before end-of-string.
 */
export function scanBalancedObject(text, start) {
    if (text[start] !== "{") return -1;
    let depth = 0;
    let inString = false;
    let escape = false;
    for (let j = start; j < text.length; j++) {
        const c = text[j];
        if (escape) {
            escape = false;
            continue;
        }
        if (c === "\\") {
            escape = true;
            continue;
        }
        if (c === '"') {
            inString = !inString;
            continue;
        }
        if (inString) continue;
        if (c === "{") depth++;
        else if (c === "}") {
            depth--;
            if (depth === 0) return j;
        }
    }
    return -1;
}

/**
 * Parse the iteration agent's outcome from an already-parsed claude result
 * envelope (see `parseClaudeJson`). Expected: a `RALPH_OUTCOME: {...}` marker
 * in the agent's final message (`envelope.result`). Falls back to inferring
 * outcome from claude's exit code / stop_reason when no marker is found, or
 * when the marker's JSON body fails to parse - a malformed marker counts as
 * absent, not as an error result.
 *
 * Returns `{ outcome, markerFound }`. Callers decide resume-continuation
 * eligibility on `markerFound` alone, never on `outcome.status` - an
 * agent-emitted `RALPH_OUTCOME {"status":"error"}` HAS a marker and must not
 * be continued, while a marker-less clean exit is what's continuation-eligible.
 */
export function parseOutcome(envelope, exitCode) {
    const finalText = envelope?.result || "";
    const markerJson = extractOutcomeMarker(finalText);
    if (markerJson) {
        try {
            return { outcome: JSON.parse(markerJson), markerFound: true };
        } catch {
            // Malformed marker JSON counts as absent - fall through to inference.
        }
    }

    // Inference fallback - claude exit code or stop_reason gives us a hint.
    if (exitCode === 0) {
        // No marker but a clean process exit - this is the case ralph-loop.mjs's
        // continuation loop resumes (see decideContinuation). If no continuation
        // is available or all continuations are exhausted, this stands as the
        // final outcome.
        return {
            outcome: {
                status: "error",
                reason: "iteration agent exited cleanly without RALPH_OUTCOME marker",
            },
            markerFound: false,
        };
    }
    if (envelope?.stop_reason === "max_budget") {
        return {
            outcome: { status: "error", reason: "iteration hit --max-budget-usd cap" },
            markerFound: false,
        };
    }
    return {
        outcome: {
            status: "error",
            reason: `iteration failed (exit ${exitCode}, stop_reason: ${envelope?.stop_reason || "unknown"})`,
        },
        markerFound: false,
    };
}

/**
 * Track cumulative iteration spend across resumes. A resumed run's
 * `total_cost_usd` reports the WHOLE conversation total (earlier runs
 * included, per claude's headless-mode JSON output), so spend is the latest
 * reported total, not a sum across runs - summing would double-count.
 * Guards against a stray lower reading and a missing/non-numeric field by
 * taking `max(prev, latest)`, treating a missing or non-numeric latest value
 * as absent (spend stays unchanged).
 */
export function updateSpend(prevSpent, envelope) {
    const latest = envelope?.total_cost_usd;
    if (typeof latest !== "number" || Number.isNaN(latest)) return prevSpent;
    return Math.max(prevSpent, latest);
}

/**
 * USD floor below which a resume continuation is not attempted, even if the
 * continuation-count budget would otherwise allow one - resuming for pennies
 * of headroom isn't worth the process spin-up.
 */
export const MIN_CONTINUATION_BUDGET_USD = 0.25;

/**
 * Decide whether a marker-less clean exit should be resumed with
 * `claude -p --resume <session_id>`.
 *
 * Keys ONLY on marker absence (`markerFound`) - never on `outcome.status`.
 * An agent that emits `RALPH_OUTCOME {"status":"error"}` has a marker and
 * must not be continued; only a clean exit with no marker at all is
 * continuation-eligible, regardless of what fallback status `parseOutcome`
 * inferred for it.
 */
export function decideContinuation({
    exitCode,
    markerFound,
    sessionId,
    continuationsUsed,
    maxContinuations,
    budget,
    spentUsd,
}) {
    const remainingUsd = budget - spentUsd;

    if (markerFound) {
        return { continue: false, remainingUsd, reason: null };
    }
    if (exitCode !== 0) {
        return { continue: false, remainingUsd, reason: "non-zero exit" };
    }
    if (!sessionId) {
        return { continue: false, remainingUsd, reason: "no session_id in envelope" };
    }
    if (continuationsUsed >= maxContinuations) {
        return {
            continue: false,
            remainingUsd,
            reason: `continuation budget exhausted (${maxContinuations} continuation(s))`,
        };
    }
    if (remainingUsd < MIN_CONTINUATION_BUDGET_USD) {
        return {
            continue: false,
            remainingUsd,
            reason: `remaining budget ($${remainingUsd.toFixed(2)}) below the $${MIN_CONTINUATION_BUDGET_USD.toFixed(2)} continuation floor`,
        };
    }
    return { continue: true, remainingUsd, reason: null };
}

/**
 * Build the argv for a resume continuation: same `--append-system-prompt-file` /
 * `--settings` / `--model` / `--output-format` / `--permission-mode` as the initial
 * iteration spawn (the system prompt is passed again because it is unverified whether
 * a resumed session retains it),
 * `--resume <sessionId>` in place of `--worktree=<name>` - a resume must
 * NEVER pass `--worktree`, which would create a new worktree instead of
 * continuing in the existing one - and `--max-budget-usd` set to the
 * REMAINING budget (the flag counts only new spend on a resumed run; see
 * `updateSpend`). The follow-up message is the last argument, matching the
 * initial iteration's prompt-last convention.
 */
export function buildResumeArgs({
    sessionId,
    cfg,
    remainingBudget,
    message,
    systemPromptPath = ITERATION_SYSTEM_PROMPT_PATH,
}) {
    return [
        "-p",
        "--resume",
        sessionId,
        "--append-system-prompt-file",
        systemPromptPath,
        "--settings",
        ITERATION_SETTINGS,
        "--permission-mode",
        "bypassPermissions",
        "--max-budget-usd",
        String(remainingBudget),
        "--output-format",
        "json",
        "--model",
        cfg.model,
        message,
    ];
}

/** Fallback backoff (ms) when `retryAfterMs` is absent or not a usable number. */
export const DEFAULT_IDLE_BACKOFF_MS = 30_000;

/** Floor clamp for the backoff wait - never sleep for less than this. */
export const MIN_IDLE_BACKOFF_MS = 1_000;

/** Ceiling clamp for the backoff wait - never sleep for longer than this. */
export const MAX_IDLE_BACKOFF_MS = 300_000;

/**
 * Decide how the drain loop should react to an idle selection (an empty `items_read next` page
 * with a reason, or every candidate skip-listed): sleep and retry, or give up after too many
 * consecutive idles.
 *
 * `retryAfterMs` comes from the selection decision and is clamped to
 * `[MIN_IDLE_BACKOFF_MS, MAX_IDLE_BACKOFF_MS]`; a missing, non-numeric, non-finite (`NaN`/`+/-Infinity`),
 * or negative value falls back to `DEFAULT_IDLE_BACKOFF_MS` before clamping. `idleBudget` is the
 * `--idle-budget` count of consecutive idles the loop tolerates before exiting - reaching it
 * (`consecutiveIdle >= idleBudget`, checked BEFORE computing a wait) exits immediately with
 * `waitMs: 0`, including on the very first idle when `idleBudget` is 0.
 */
export function decideIdleBackoff({ retryAfterMs, consecutiveIdle, idleBudget }) {
    if (consecutiveIdle >= idleBudget) {
        return { exit: true, waitMs: 0 };
    }

    let waitMs = retryAfterMs;
    if (typeof waitMs !== "number" || !Number.isFinite(waitMs) || waitMs < 0) {
        waitMs = DEFAULT_IDLE_BACKOFF_MS;
    }
    waitMs = Math.min(MAX_IDLE_BACKOFF_MS, Math.max(MIN_IDLE_BACKOFF_MS, waitMs));

    return { exit: false, waitMs };
}

/**
 * Sentinel env var recognized by hooks/execution-mode.mjs's `isHeadlessIteration` to distinguish
 * a headless ralph iteration from an interactive session. No documented Claude Code hook-input
 * field distinguishes `claude -p`, hence this explicit signal.
 */
export const HEADLESS_ITERATION_MODE = 'headless-iteration';

/**
 * Env var naming the FILE that holds the iteration's seat token. The token value itself never
 * enters the env, argv, prompt text or any console line: only this path does.
 */
export const SEAT_TOKEN_FILE_ENV = "TASK_ORCHESTRATOR_SEAT_TOKEN_FILE";

/**
 * Builds the env object passed to every `claude -p` iteration spawn (initial and resume):
 * `base` plus `TASK_ORCHESTRATOR_MODE=headless-iteration`, plus `TASK_ORCHESTRATOR_SEAT_TOKEN_FILE`
 * when `seatTokenFile` is a non-empty string (the PATH of the token file, never the token). Never
 * mutates `base`; returns a new object so the caller's own env reference (typically `process.env`)
 * is left untouched. A `TASK_ORCHESTRATOR_SEAT_TOKEN_FILE` inherited from `base` is dropped when no
 * file is given, so a stale path never leaks into an iteration.
 */
export function buildIterationEnv(base, { seatTokenFile } = {}) {
    const env = { ...base, TASK_ORCHESTRATOR_MODE: HEADLESS_ITERATION_MODE };
    delete env[SEAT_TOKEN_FILE_ENV];
    if (typeof seatTokenFile === "string" && seatTokenFile) env[SEAT_TOKEN_FILE_ENV] = seatTokenFile;
    return env;
}

// ---------------------------------------------------------------------------
// 4.0 run plumbing: one run per loop, one implementer seat per iteration.
// ---------------------------------------------------------------------------

/** Default `--iteration-timeout`, in seconds (45 minutes). */
export const DEFAULT_ITERATION_TIMEOUT_SECONDS = 2700;

/** Grace between SIGTERM and SIGKILL for a timed-out or interrupted iteration child. */
export const KILL_GRACE_MS = 10_000;

/** Wait for an empty `next` page that carries a reason (matches exist, none ready yet). */
export const NEXT_EMPTY_WAIT_MS = 30_000;

/** The seat ralph assigns per iteration. */
export const RALPH_SEAT = "implementer";

/** Placeholder written over every known token value in streamed child output and records. */
export const REDACTED_TOKEN = "[redacted-token]";

/** Filter keys `--filter` accepts. */
export const FILTER_KEYS = Object.freeze(["tag", "type", "priority", "parentId", "ancestorId"]);

/**
 * Parse a `--filter` expression ("tag=bug-fix priority=high ancestorId=ce064d2c") into
 * `{filter: {tags?, type?, priority?, parentId?, ancestorId?}, errors: string[]}`. `tag` may repeat
 * and may hold a comma-separated list; every other key takes its last value. Terms without `=`,
 * empty values and unknown keys are reported in `errors` (the caller exits 64).
 */
export function parseFilter(expr) {
    const filter = {};
    const errors = [];
    const parts = String(expr ?? "").trim().split(/\s+/).filter(Boolean);
    for (const part of parts) {
        const eq = part.indexOf("=");
        if (eq <= 0) {
            errors.push(`filter term "${part}" is not key=value`);
            continue;
        }
        const key = part.slice(0, eq);
        const value = part.slice(eq + 1);
        if (!FILTER_KEYS.includes(key)) {
            errors.push(`unknown filter key "${key}" (keys: ${FILTER_KEYS.join(", ")})`);
            continue;
        }
        if (!value) {
            errors.push(`filter key "${key}" has an empty value`);
            continue;
        }
        if (key === "tag") {
            const tags = value.split(",").map((t) => t.trim()).filter(Boolean);
            filter.tags = [...new Set([...(filter.tags || []), ...tags])];
        } else {
            filter[key] = value;
        }
    }
    return { filter, errors };
}

/**
 * The `items_read next` request body for one selection. Ranks oldest first in the queue, scoped to
 * `filter.ancestorId` else `rootId`. `limit` is `skipCount + 1` (capped at 200) so one candidate
 * past every skip-listed item is always on the page; with a fixed member set (`--items`) the page
 * is the maximum 200 so members ranked behind non-members still show up for the client-side filter.
 */
export function buildNextQuery({ filter = {}, rootId, skipCount = 0, members = null }) {
    const q = { select: "next", role: "queue", orderBy: "oldest" };
    const ancestorId = filter.ancestorId || rootId;
    if (ancestorId) q.ancestorId = ancestorId;
    if (Array.isArray(filter.tags) && filter.tags.length) q.tags = [...filter.tags];
    if (filter.type) q.type = filter.type;
    if (filter.priority) q.priority = filter.priority;
    if (filter.parentId) q.parentId = filter.parentId;
    q.limit = members ? 200 : Math.min(200, Math.max(0, skipCount) + 1);
    return q;
}

/**
 * The `runs_write` `open` op. `items` is sent only when a non-empty member list is given (`--items`);
 * otherwise the run opens empty and each picked item is added with `update addItems`. `budget` is
 * sent only when at least one of `runBudgetTokens` (-> tokens), `runBudgetUsd` (-> costUsd) or
 * `maxAttempts` (-> attemptsPerSeat) is a finite number; `maxActiveSeats` is never sent and there is
 * no default cap. `hints` values are strings: `{tool: 'ralph', filter, pid}`.
 */
export function buildRunOpenOp({ rootId, items, label, title, filter, pid, runBudgetTokens, runBudgetUsd, maxAttempts }) {
    const op = { op: "open", rootId };
    if (Array.isArray(items) && items.length) op.items = [...items];
    if (label) op.label = String(label).slice(0, 64);
    if (title) op.title = String(title).slice(0, 200);
    op.hints = { tool: "ralph", filter: filter ? String(filter) : "any", pid: String(pid ?? "") };
    const budget = {};
    if (typeof runBudgetTokens === "number" && Number.isFinite(runBudgetTokens)) budget.tokens = runBudgetTokens;
    if (typeof runBudgetUsd === "number" && Number.isFinite(runBudgetUsd)) budget.costUsd = runBudgetUsd;
    if (typeof maxAttempts === "number" && Number.isFinite(maxAttempts)) budget.attemptsPerSeat = maxAttempts;
    if (Object.keys(budget).length) op.budget = budget;
    return op;
}

/** Codes whose skip lasts for the error's `retryAfterMs`; every other code skips for the whole loop. */
export const TRANSIENT_SKIP_CODES = Object.freeze(["claim_held", "resource_unavailable"]);

/**
 * Add (or replace) a skip row `{itemId, code, until}` and return a NEW list; `list` is not mutated.
 * `itemId` is lowercased. `claim_held` and `resource_unavailable` skip until `now + retryAfterMs`,
 * where a missing, non-numeric, non-finite or negative `retryAfterMs` falls back to
 * `DEFAULT_IDLE_BACKOFF_MS` (30000). Every other code (`invalid_transition`, `not_found`,
 * `duplicate`, ...) is permanent for the loop: `until = Infinity`.
 */
export function addSkip(list, { itemId, code, retryAfterMs, now }) {
    const id = String(itemId).toLowerCase();
    let until = Infinity;
    if (TRANSIENT_SKIP_CODES.includes(code)) {
        const ms = typeof retryAfterMs === "number" && Number.isFinite(retryAfterMs) && retryAfterMs >= 0
            ? retryAfterMs
            : DEFAULT_IDLE_BACKOFF_MS;
        until = now + ms;
    }
    return [...(list || []).filter((r) => r.itemId !== id), { itemId: id, code, until }];
}

/** True while the item has a skip row whose `until` is strictly later than `now`. */
export function isSkipped(list, itemId, now) {
    const id = String(itemId).toLowerCase();
    return (list || []).some((r) => r.itemId === id && r.until > now);
}

/**
 * Pure selection over one `items_read next` page. `candidates` are the page's items (each with an
 * `id`), `reason` the page's `reason` (only on an empty page), `members` a Set or array of member
 * ids when the run has a fixed member set (`--items`), else null.
 * - the first candidate that is a member (when `members` is set) and not skipped -> `{pick: item}`
 * - empty page with reason `claim_held`/`dependency_unmet` -> `{idle: true, waitMs: 30000, reason}`
 *   (transient: matches exist, none ready; never "drained")
 * - empty page with no reason -> `{drained: true}`
 * - every member candidate skipped -> `{idle: true, waitMs, reason: 'skipped'}` with `waitMs` the
 *   earliest finite `until - now`, clamped to [1000, 300000]; when every such skip is permanent
 *   there is nothing left to wait for -> `{drained: true}`
 * - candidates present but none is a member -> `{idle: true, waitMs: 30000, reason: 'no-member-ready'}`
 */
export function decideSelection({ candidates, reason, skipList, members = null, now }) {
    const page = Array.isArray(candidates) ? candidates.filter((c) => c && typeof c.id === "string") : [];
    if (page.length === 0) {
        if (reason === "claim_held" || reason === "dependency_unmet") {
            return { idle: true, waitMs: NEXT_EMPTY_WAIT_MS, reason };
        }
        return { drained: true };
    }
    const memberSet = members ? new Set([...members].map((m) => String(m).toLowerCase())) : null;
    const inRun = memberSet ? page.filter((c) => memberSet.has(c.id.toLowerCase())) : page;
    if (inRun.length === 0) return { idle: true, waitMs: NEXT_EMPTY_WAIT_MS, reason: "no-member-ready" };
    const pick = inRun.find((c) => !isSkipped(skipList, c.id, now));
    if (pick) return { pick };
    let earliest = Infinity;
    for (const c of inRun) {
        const row = (skipList || []).find((r) => r.itemId === c.id.toLowerCase());
        if (row && Number.isFinite(row.until) && row.until < earliest) earliest = row.until;
    }
    if (!Number.isFinite(earliest)) return { drained: true };
    const waitMs = Math.min(MAX_IDLE_BACKOFF_MS, Math.max(MIN_IDLE_BACKOFF_MS, earliest - now));
    return { idle: true, waitMs, reason: "skipped" };
}

/**
 * The one outcome table, rendered into skills/ralph/SKILL.md, iteration-prompt.md and
 * iteration-system-prompt.md by scripts/render-ralph-prompts.mjs. Rows `{code, kind, by, status,
 * action, loop}`:
 * - `code`: an error-catalog code (its `kind` is ERROR_KINDS[code]), a ralph-local code
 *   (`iteration_timeout`, `spawn_error`, kind `local`), or, for the plain RALPH_OUTCOME status rows,
 *   the status itself (kind `outcome`). Each code appears in exactly one row.
 * - `by`: who observes it: `driver` (ralph-loop.mjs, from a REST answer or the child process) or
 *   `iteration` (the agent, reported through the RALPH_OUTCOME `code` field).
 * - `status`: the RALPH_OUTCOME status the row resolves to.
 * - `loop`: `continue` (counts terminal, resets breakers), `gate` (gate-failure breaker), `error`
 *   (error breaker), `skip` (skip-list the item, no breaker change), `backoff` (idle wait, idle
 *   budget), `wait-retry` (wait, then retry the same assign), `drained` (exit, nothing left), `exit`
 *   (end the loop now: end the seat, close the run).
 */
export const OUTCOME_TABLE = Object.freeze([
    { code: "terminal", kind: "outcome", by: "iteration", status: "terminal", action: "The item reached terminal per its schema. The driver ends the seat done.", loop: "continue" },
    { code: "gate-blocked", kind: "outcome", by: "iteration", status: "gate-blocked", action: "A required note cannot be filled autonomously. The driver ends the seat abandoned.", loop: "gate" },
    { code: "error", kind: "outcome", by: "iteration", status: "error", action: "Tool error, build failure, budget cap, or a marker-less exit after every continuation. The driver ends the seat failed.", loop: "error" },
    { code: "skip", kind: "outcome", by: "iteration", status: "skip", action: "The item cannot be worked now; put the catalog code in `code`. The driver ends the seat abandoned and skip-lists the item.", loop: "skip" },
    { code: "idle", kind: "outcome", by: "driver", status: "idle", action: "No item is ready: an empty next page with a reason, or every candidate skip-listed. The driver waits, then selects again.", loop: "backoff" },
    { code: "no-item", kind: "outcome", by: "driver", status: "no-item", action: "An empty next page with no reason: nothing matches the filter. The driver closes the run completed.", loop: "drained" },
    { code: "claim_held", kind: ERROR_KINDS.claim_held, by: "driver", status: "skip", action: "The driver's claim under the seat token found another holder. It ends the seat abandoned and skip-lists the item for retryAfterMs.", loop: "skip" },
    { code: "resource_unavailable", kind: ERROR_KINDS.resource_unavailable, by: "iteration", status: "skip", action: "advance transition into work found a declared resource leased elsewhere. Emit skip with code and retryAfterMs; do not retry, do not release. The driver skip-lists the item for retryAfterMs.", loop: "skip" },
    { code: "dependency_unmet", kind: ERROR_KINDS.dependency_unmet, by: "driver", status: "idle", action: "An empty next page whose reason is dependency_unmet: matches exist but are blocked. The driver waits 30s, then selects again.", loop: "backoff" },
    { code: "invalid_transition", kind: ERROR_KINDS.invalid_transition, by: "driver", status: "skip", action: "The item cannot be claimed or moved from its role (for example already terminal). Skip-listed for the rest of the loop.", loop: "skip" },
    { code: "duplicate", kind: ERROR_KINDS.duplicate, by: "driver", status: "skip", action: "update addItems found the item in another open run. Skip-listed for the rest of the loop.", loop: "skip" },
    { code: "gate_blocked", kind: ERROR_KINDS.gate_blocked, by: "iteration", status: "gate-blocked", action: "advance transition reported missing required notes the iteration cannot fill. Emit gate-blocked with code.", loop: "gate" },
    { code: "seat_forbidden", kind: ERROR_KINDS.seat_forbidden, by: "iteration", status: "gate-blocked", action: "The item's schema does not let the implementer seat make this move. Emit gate-blocked with code; a coordinator or human advances it.", loop: "gate" },
    { code: "not_claim_holder", kind: ERROR_KINDS.not_claim_holder, by: "iteration", status: "error", action: "A write was refused because the call does not hold the claim (claim lapsed, or the call went out without the seat token). Emit error with code.", loop: "error" },
    { code: "unauthenticated", kind: ERROR_KINDS.unauthenticated, by: "iteration", status: "error", action: "The seat token was refused: the iteration must reach the server as the same principal as the driver. Emit error with code. On a driver call with the coordinator token the loop exits 2.", loop: "error" },
    { code: "run_paused", kind: ERROR_KINDS.run_paused, by: "driver", status: "error", action: "The run paused (budget, attempt cap or manual). Never retried: the driver ends the seat and closes the run cancelled; a director or human answers the pause decision.", loop: "exit" },
    { code: "run_closed", kind: ERROR_KINDS.run_closed, by: "driver", status: "error", action: "The run was closed elsewhere. The driver ends the seat and exits.", loop: "exit" },
    { code: "run_at_capacity", kind: ERROR_KINDS.run_at_capacity, by: "driver", status: "idle", action: "assign hit the run's maxActiveSeats. The driver waits 30s, then retries the assign.", loop: "wait-retry" },
    { code: "unavailable", kind: ERROR_KINDS.unavailable, by: "driver", status: "idle", action: "The server shed load on a driver call. The driver waits retryAfterMs, then tries again.", loop: "backoff" },
    { code: "iteration_timeout", kind: "local", by: "driver", status: "error", action: "The iteration outlived --iteration-timeout: SIGTERM, then SIGKILL after 10s. The driver ends the seat failed.", loop: "error" },
    { code: "spawn_error", kind: "local", by: "driver", status: "error", action: "The iteration process could not start (for example --claude-bin missing). The driver ends the seat failed.", loop: "error" },
].map((row) => Object.freeze(row)));

function mdCell(s) {
    return String(s).replace(/\|/g, "\\|");
}

/** The outcome table as markdown with one trailing newline: the exact text between the generated markers. */
export function renderOutcomeTable(rows = OUTCOME_TABLE) {
    const lines = [
        "| Code | Kind | By | Status | Action | Loop |",
        "|---|---|---|---|---|---|",
        ...rows.map((r) => `| \`${r.code}\` | ${r.kind} | ${r.by} | \`${r.status}\` | ${mdCell(r.action)} | ${r.loop} |`),
    ];
    return lines.join("\n") + "\n";
}

/** The `end_seat` outcome for a final RALPH_OUTCOME status: terminal -> done; gate-blocked, skip -> abandoned; error (and anything else) -> failed. */
export function endSeatOutcome(status) {
    if (status === "terminal") return "done";
    if (status === "gate-blocked" || status === "skip") return "abandoned";
    return "failed";
}

/**
 * How a finished iteration's outcome moves the loop. Keys on `outcome.code` first:
 * `run_paused` -> `{effect: 'exit', exitReason: 'run paused...', closeReason: 'cancelled'}`,
 * `run_closed` -> `{effect: 'exit', exitReason: 'run closed...', closeReason: 'completed'}`;
 * `unauthenticated`, `not_claim_holder` -> `{effect: 'error', hint}`. Otherwise on `outcome.status`:
 * terminal -> `terminal`, gate-blocked -> `gate`, skip -> `{effect: 'skip', skipCode, retryAfterMs}`
 * (skipCode is the outcome's code, else 'skip'), error and any unknown status -> `error`.
 */
export function loopEffect(outcome) {
    const code = outcome && typeof outcome.code === "string" ? outcome.code : null;
    const status = outcome && typeof outcome.status === "string" ? outcome.status : null;
    if (code === "run_paused") {
        const why = typeof outcome.pauseReason === "string" && outcome.pauseReason ? ` (${outcome.pauseReason})` : "";
        return { effect: "exit", exitReason: `run paused${why}: a director or human must answer the pause decision`, closeReason: "cancelled" };
    }
    if (code === "run_closed") {
        return { effect: "exit", exitReason: "run closed elsewhere", closeReason: "completed" };
    }
    if (code === "unauthenticated") {
        return {
            effect: "error",
            hint: "the iteration's seat token was refused: its MCP connection must reach the server as the same principal as the driver's REST credential (local mode satisfies this)",
        };
    }
    if (code === "not_claim_holder") {
        return {
            effect: "error",
            hint: "a write was refused as not_claim_holder: every iteration call must carry the seat token from the token file, and --ttl must outlast the iteration",
        };
    }
    switch (status) {
        case "terminal":
            return { effect: "terminal" };
        case "gate-blocked":
            return { effect: "gate" };
        case "skip":
            return { effect: "skip", skipCode: code || "skip", retryAfterMs: outcome.retryAfterMs };
        default:
            return { effect: "error" };
    }
}

/**
 * The usage report ops (D11) for a finished child envelope, or null when the envelope carries
 * neither `total_cost_usd` nor `usage` token counts: `[claim, heartbeat{usage: {tokens, costUsd?}},
 * release]`, sent under the seat token. `tokens` sums the numeric `input_tokens`, `output_tokens`,
 * `cache_creation_input_tokens` and `cache_read_input_tokens` (0 when none is present).
 */
export function buildUsageOps({ itemId, ttlSeconds, envelope }) {
    const cost = envelope?.total_cost_usd;
    const hasCost = typeof cost === "number" && Number.isFinite(cost) && cost >= 0;
    const u = envelope?.usage;
    let tokens = 0;
    let hasTokens = false;
    if (u && typeof u === "object") {
        for (const k of ["input_tokens", "output_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"]) {
            const v = u[k];
            if (typeof v === "number" && Number.isFinite(v) && v >= 0) {
                tokens += Math.floor(v);
                hasTokens = true;
            }
        }
    }
    if (!hasCost && !hasTokens) return null;
    const usage = { tokens };
    if (hasCost) usage.costUsd = cost;
    return [
        { op: "claim", itemId, ttlSeconds },
        { op: "heartbeat", itemId, usage },
        { op: "release", itemId },
    ];
}

/** Replace every occurrence of every non-empty known token value in `text` with `[redacted-token]`. */
export function redactTokens(text, tokens) {
    let out = String(text ?? "");
    // Longest first, so a token that is a prefix of another leaves no tail.
    const list = (tokens || []).filter((t) => typeof t === "string" && t.length > 0).sort((x, y) => y.length - x.length);
    for (const t of list) out = out.split(t).join(REDACTED_TOKEN);
    return out;
}

/** Path of the seat token file for one iteration: `<scratchDir>/tokens/<iter>.implementer.token`. */
export function seatTokenFilePath(scratchDir, iterIndex) {
    return path.join(scratchDir, "tokens", `${iterIndex}.${RALPH_SEAT}.token`);
}

/**
 * Normalize one REST answer (hooks/lib/api-client.mjs `callOp`) for a single-element ops call or a
 * select into `{ok: true, value}` or `{ok: false, error: {code, kind, message, retryAfterMs,
 * detail}}`. A null answer (no REST API configured) is `client_error`. A batch whose only element
 * failed (`partial_failure` with `results[0].ok === false`) reports that element's error.
 */
export function firstOpResult(res) {
    if (!res) return { ok: false, error: { code: "client_error", kind: "permanent", message: "no REST API configured", retryAfterMs: null, detail: null } };
    const r0 = Array.isArray(res.results) && res.results.length ? res.results[0] : null;
    if (res.ok) return { ok: true, value: r0 && r0.ok !== false ? r0 : res.data };
    const raw = r0 && r0.ok === false && r0.error ? r0.error : res.error || {};
    const detail = raw.detail && typeof raw.detail === "object" ? raw.detail : null;
    return {
        ok: false,
        error: {
            code: raw.code ?? null,
            kind: raw.kind ?? null,
            message: raw.message ?? null,
            retryAfterMs: raw.retryAfterMs ?? detail?.retryAfterMs ?? null,
            detail,
        },
    };
}

// ---------------------------------------------------------------------------
// Worktree naming / rename / cleanup. Git access is injected as `io.git(args)`, which takes an
// argv array (never a shell string) and resolves `{ stdout }` or rejects with `{ stderr, message }`.
// ralph-loop.mjs supplies a promisified `execFile('git', args)`; tests supply a recording fake.
// ---------------------------------------------------------------------------

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * `ralph-<first 8 of itemId>-<iter>` only when `itemId` is a string shaped like a UUID; any
 * other value (non-string, model-emitted junk) keeps `tempName`.
 */
export function worktreeNameForOutcome(itemId, iterIndex, tempName) {
    if (typeof itemId !== "string" || !UUID_RE.test(itemId)) return tempName;
    return `ralph-${itemId.slice(0, 8)}-${iterIndex}`;
}

/** Absolute cwd for resuming an iteration that ran in the worktree named `tempName`. */
export function resumeCwd(tempName) {
    return path.resolve(".claude", "worktrees", tempName);
}

/**
 * Rename a worktree via `git worktree move`. Returns { ok: true } or { ok: false, reason }
 * where reason is the first non-blank line of git's stderr (or 'unknown error').
 */
export async function renameWorktree(io, oldName, newName) {
    if (oldName === newName) return { ok: true };
    const oldPath = path.posix.join(".claude", "worktrees", oldName);
    const newPath = path.posix.join(".claude", "worktrees", newName);
    try {
        await io.git(["worktree", "move", oldPath, newPath]);
        return { ok: true };
    } catch (err) {
        const reason = (err?.stderr || err?.message || "")
            .split("\n")
            .map((s) => s.trim())
            .filter(Boolean)[0] || "unknown error";
        return { ok: false, reason };
    }
}

/**
 * Smart-cleanup decision for a finished iteration's worktree: removed | preserved | absent |
 * failed. Preserves on uncommitted changes, commits ahead of `baseRef`, or an unresolvable
 * `baseRef` (safer than removing a worktree with real commits).
 */
export async function maybeCleanupWorktree(io, worktreeName, baseRef) {
    const worktreePath = path.posix.join(".claude", "worktrees", worktreeName);

    try {
        const { stdout: listOut } = await io.git(["worktree", "list", "--porcelain"]);
        if (!listOut.includes(worktreePath) && !listOut.includes(worktreePath.replace(/\//g, path.sep))) {
            return { action: "absent" };
        }
    } catch (err) {
        return { action: "failed", reason: `git worktree list failed: ${err.message}` };
    }

    try {
        const { stdout: statusOut } = await io.git(["-C", worktreePath, "status", "--porcelain"]);
        if (statusOut.trim().length > 0) {
            return { action: "preserved", reason: "uncommitted changes present" };
        }
    } catch (err) {
        return { action: "failed", reason: `git status failed: ${err.message}` };
    }

    try {
        const { stdout: aheadOut } = await io.git(["-C", worktreePath, "rev-list", "--count", `${baseRef}..HEAD`]);
        const ahead = parseInt(aheadOut.trim(), 10);
        if (Number.isFinite(ahead) && ahead > 0) {
            return { action: "preserved", reason: `${ahead} commit(s) ahead of ${baseRef}` };
        }
    } catch {
        return { action: "preserved", reason: `could not compare against ${baseRef}` };
    }

    try {
        await io.git(["worktree", "remove", worktreePath]);
        return { action: "removed" };
    } catch (err) {
        return { action: "failed", reason: `git worktree remove failed: ${err.message}` };
    }
}
