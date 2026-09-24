// ralph-lib.mjs — pure helpers extracted from ralph-loop.mjs for unit testing.
//
// ralph-loop.mjs runs its CLI parsing and main loop at module top level (including a
// top-level `await` and `process.exit` calls), so it isn't importable by tests. These are
// the pure, side-effect-free pieces factored out so they can be exercised in isolation:
// claude's JSON envelope parsing, the RALPH_OUTCOME marker scan, and the resume-continuation
// decision (whether to `claude -p --resume` an iteration that ended without emitting a
// marker).
//
// Stdlib-only. Node 18+.

/**
 * Parse claude's stdout (--output-format json) into the result envelope.
 * Tries a direct parse first (handles both compact and pretty-printed JSON),
 * then falls back to extracting the last balanced top-level object — which
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
 * newlines, nested objects, or escaped quotes — all of which broke the
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
 * brace depth, and return the index of the matching closing `}` — or -1 if
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
 * when the marker's JSON body fails to parse — a malformed marker counts as
 * absent, not as an error result.
 *
 * Returns `{ outcome, markerFound }`. Callers decide resume-continuation
 * eligibility on `markerFound` alone, never on `outcome.status` — an
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
            // Malformed marker JSON counts as absent — fall through to inference.
        }
    }

    // Inference fallback — claude exit code or stop_reason gives us a hint.
    if (exitCode === 0) {
        // No marker but a clean process exit — this is the case ralph-loop.mjs's
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
 * reported total, not a sum across runs — summing would double-count.
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
 * continuation-count budget would otherwise allow one — resuming for pennies
 * of headroom isn't worth the process spin-up.
 */
export const MIN_CONTINUATION_BUDGET_USD = 0.25;

/**
 * Decide whether a marker-less clean exit should be resumed with
 * `claude -p --resume <session_id>`.
 *
 * Keys ONLY on marker absence (`markerFound`) — never on `outcome.status`.
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
 * Build the argv for a resume continuation: same `--settings` / `--model` /
 * `--output-format` / `--permission-mode` as the initial iteration spawn,
 * `--resume <sessionId>` in place of `--worktree=<name>` — a resume must
 * NEVER pass `--worktree`, which would create a new worktree instead of
 * continuing in the existing one — and `--max-budget-usd` set to the
 * REMAINING budget (the flag counts only new spend on a resumed run; see
 * `updateSpend`). The follow-up message is the last argument, matching the
 * initial iteration's prompt-last convention.
 */
export function buildResumeArgs({ sessionId, cfg, remainingBudget, message }) {
    return [
        "-p",
        "--resume",
        sessionId,
        "--settings",
        JSON.stringify({ outputStyle: "task-orchestrator:ralph-iteration" }),
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

/** Floor clamp for the backoff wait — never sleep for less than this. */
export const MIN_IDLE_BACKOFF_MS = 1_000;

/** Ceiling clamp for the backoff wait — never sleep for longer than this. */
export const MAX_IDLE_BACKOFF_MS = 300_000;

/**
 * Decide how the drain loop should react to a `claim_item` selector `none_eligible` (transient
 * "idle") outcome: sleep and retry, or give up after too many consecutive idles.
 *
 * `retryAfterMs` comes from the claim response and is clamped to
 * `[MIN_IDLE_BACKOFF_MS, MAX_IDLE_BACKOFF_MS]`; a missing, non-numeric, `NaN`, or negative value
 * falls back to `DEFAULT_IDLE_BACKOFF_MS` before clamping. `idleBudget` is the
 * `--idle-budget` count of consecutive idles the loop tolerates before exiting — reaching it
 * (`consecutiveIdle >= idleBudget`, checked BEFORE computing a wait) exits immediately with
 * `waitMs: 0`, including on the very first idle when `idleBudget` is 0.
 */
export function decideIdleBackoff({ retryAfterMs, consecutiveIdle, idleBudget }) {
    if (consecutiveIdle >= idleBudget) {
        return { exit: true, waitMs: 0 };
    }

    let waitMs = retryAfterMs;
    if (typeof waitMs !== "number" || Number.isNaN(waitMs) || waitMs < 0) {
        waitMs = DEFAULT_IDLE_BACKOFF_MS;
    }
    waitMs = Math.min(MAX_IDLE_BACKOFF_MS, Math.max(MIN_IDLE_BACKOFF_MS, waitMs));

    return { exit: false, waitMs };
}
