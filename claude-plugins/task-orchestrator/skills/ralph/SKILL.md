---
name: ralph
description: "Launcher for the Ralph-style queue drain script — emits the right `node ralph-loop.mjs` invocation based on the user's filter and bounds. The actual loop runs as a Node script that spawns one `claude -p --worktree` per iteration; this skill is the configurator, not the loop. Use when a user says: drain the backlog, ralph the queue, run Ralph loop, work the queue, batch through queue items, autonomous queue worker."
argument-hint: "[optional: filter expression like 'tag=bug-fix' or 'type=quick-fix priority=high']"
---

# ralph — Queue Drain Launcher

This skill is a **launcher** for the Ralph drain script (`scripts/ralph-loop.mjs` in this plugin). It does not run a loop in the current session. It helps you configure the right invocation, previews what will be processed, and emits the exact command to run.

The actual loop runs as a separate Node process. Each iteration spawns a fresh `claude -p` in its own git worktree (via Claude Code's `-w` flag), claims one item from the TO queue, and works it to terminal per the item's schema. Outcome is signaled via a `RALPH_OUTCOME:` JSON marker that the script parses for circuit-breaker decisions.

> **Why a separate script, not a slash-command loop?** Per Geoff Huntley's canonical Ralph: the value comes from carving off small bits of work into independent context windows. Each iteration is a fresh process — fresh context, fresh worktree, isolated logs, clean exit codes. The skill-as-orchestrator pattern (one session, internal loop) accumulates context within a session and doesn't preserve fresh-context isolation. For autonomous cadence, compose with `/loop`: `/loop 30m node scripts/ralph-loop.mjs --filter "tag=bug-fix"`.

---

## Step 1 — Resolve the filter

Determine what subset of the queue to drain.

**If `$ARGUMENTS` is non-empty**, parse it as a filter expression. Supported keys:

| Key | Effect |
|---|---|
| `tag=<value>` | Items whose `tags` field contains `<value>` (substring) |
| `type=<value>` | Items with this exact `type` |
| `priority=<value>` | Items at this priority (`high`, `medium`, `low`) |
| `parentId=<uuid-or-prefix>` | Only descendants of this container |

Multiple keys combine with AND (space-separated). Examples: `tag=bug-fix priority=high`, `type=quick-fix`, `parentId=89d02e32`.

**If `$ARGUMENTS` is empty**, ask the user via `AskUserQuestion`:

```
◆ Ralph loop — what should I drain?
  1. Bug-fix backlog (tag=bug-fix)
  2. Quick fixes (type=quick-fix)
  3. Tech debt container (parentId=<lookup>)
  4. Anything in queue (no filter)
  5. Custom filter — specify
```

If the user picks "Tech debt container", search via `query_items(operation="search", query="Tech Debt", depth=0)` and use the resulting UUID.

---

## Step 2 — Resolve bounds

Set sensible loop bounds. Show defaults and let the user adjust.

```
◆ Loop bounds (default in parens):
  Max iterations:           10
  Gate-failure budget:      3 consecutive
  Error budget:             2 consecutive
  Per-iteration USD cap:    $5
  Claim TTL per iteration:  1800s (30 min)
  Model:                    sonnet
  Cleanup on terminal:      smart (remove if no commits/changes; preserve otherwise)

  Adjust any?  Reply "ok" to use defaults.
```

If the user adjusts, capture the overrides. Common patterns:
- High-volume drain: `--max 30`
- Low-confidence run: `--gate-budget 1 --error-budget 1`
- Architecture-heavy items: `--model opus --budget 15`
- Preserve every worktree (debugging-heavy session): `--no-cleanup`

---

## Step 3 — Preview the queue

Show what the loop will see. Use `query_items` with the resolved filter to display the first 5 candidates and the total count:

```
query_items(operation="search", role="queue", claimStatus="unclaimed", limit=10, ...)
```

Display:

```
◆ Queue preview — filter: tag=bug-fix priority=high
  Total claimable: 7 items
  First 5 by priority:
    ◉ d4fc7b2e  Fix duplicate UUID race in claim_item            (high)
    ◉ 92da8e9a  Gate response includes stale guidance            (high)
    ◉ 0c916953  subagent-start hook double-advance               (high)
    ◉ 00769317  advance_item applied:false contradicts DB        (medium)
    ◉ 2e4c9e77  create_work_tree dependency semantics            (low)
```

If the count is zero, tell the user nothing matches and stop here. If the count exceeds the iteration cap by a lot, mention that subsequent runs would pick up the rest.

---

## Step 4 — Emit the command

Print the exact command to run. The user copies and pastes it.

```
◆ Ready to launch. Run this command:

  node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
    --filter "tag=bug-fix priority=high" \
    --max 10 \
    --budget 5 \
    --ttl 1800 \
    --model sonnet

  → Spawns one `claude -p --worktree=ralph-...` per iteration
  → Each iteration is fresh context, isolated worktree
  → Logs print live as iterations run
  → Final summary lists outcomes and preserved worktrees

  For autonomous cadence (re-run every 30 min until empty):
    /loop 30m node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix priority=high"

  Dry-run first to verify the iteration command:
    node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --dry-run --filter "tag=bug-fix priority=high"
```

Adjust the path if the user is in a different working directory — the script lives at `claude-plugins/task-orchestrator/scripts/ralph-loop.mjs` relative to the project root.

If the user wants the skill to launch the script directly (rather than emit a command), suggest using the Bash tool — but flag the tradeoff:

```
⚠ Running the script via Bash from this session means the loop output streams
   into our conversation transcript. That works for short drains but burns
   context on long ones. For drains over 5 iterations, prefer running the
   command in a separate terminal so this session stays free for other work.
```

---

## Step 5 — Post-run handoff

After the script finishes (the user reports back with the summary, or pastes the output), help interpret the outcomes:

| Final summary line | Action |
|---|---|
| `Exit reason: queue empty` | Loop succeeded — nothing to do |
| `Exit reason: iteration cap reached` | Re-run with same filter to continue, or raise `--max` |
| `Exit reason: gate failure budget exhausted` | Inspect preserved worktrees; the schema expects notes the iteration agent can't fill autonomously (often `review-checklist`) |
| `Exit reason: error budget exhausted` | Inspect last error; likely repo/build/network issue affecting all iterations |

For preserved worktrees from gate-blocked or errored iterations:

```bash
# inspect
git -C <repo-path> worktree list | grep ralph-

# resume manually with /status-progression on the item ID
# or clean up after inspection
git -C <repo-path> worktree remove ralph-<id>
```

---

## Outcomes & exit codes (script reference)

The iteration agent emits `RALPH_OUTCOME: {...}` as its final message. The loop driver maps each status to circuit-breaker behavior:

| Status | Effect on loop |
|---|---|
| `terminal` | Counter ✓; resets gate-failure and error counters; loop continues |
| `gate-blocked` | Counter ⊘; increments consecutive gate-failure counter; loop continues unless budget hit |
| `error` | Counter ✗; increments consecutive error counter; loop continues unless budget hit |
| `skip` | Counter —; no counter changes; loop continues |
| `no-item` | Loop exits cleanly (queue empty) |

The script's own exit code:
| Code | Meaning |
|---|---|
| `0` | Loop completed normally (queue empty, iteration cap, or gate-budget exit) |
| `2` | Loop stopped due to consecutive errors |
| `64` | CLI argument error |
| `70` | Could not read iteration prompt |

---

## Composition with other tools

| Want to... | Compose with |
|---|---|
| Run Ralph on a schedule | `/loop 30m node .../ralph-loop.mjs --filter ...` |
| Inspect what's claimable before running | `/work-summary` to see queue contents |
| Resume a single gate-blocked item from Ralph | `/status-progression <item-id>` |
| Clean up a preserved worktree | `git worktree remove <path>` |
| Check the iteration prompt template | Read `skills/ralph/iteration-prompt.md` in this plugin |

---

## Examples

### Example 1: Drain the bug-fix queue

User: "ralph the bug-fix backlog"

```
$ARGUMENTS = "tag=bug-fix"
```

**Step 1** parses the filter: `tag=bug-fix`.

**Step 2** uses defaults (max=10, budgets=3/2, $5/iter, sonnet).

**Step 3** shows 7 claimable items, top 5 by priority.

**Step 4** emits:

```
node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
  --filter "tag=bug-fix" \
  --max 10
```

User runs it. Each iteration spawns a fresh `claude -p --worktree=ralph-<id>` with the iteration prompt. The agent uses `claim_item` selector mode to atomically find and claim one item (a single MCP call — no race window), invokes `/schema-workflow` to drive it through whatever phases its schema declares, commits changes, and exits with the outcome marker.

5 iterations later: 3 terminal, 1 gate-blocked (a required note couldn't be filled — e.g., needed external context the iteration didn't have), 1 errored (test failure unrelated to the fix). Loop exits when consecutive gate failures hit the budget (3) or queue empties — whichever comes first.

---

### Example 2: Scheduled overnight drain

User wants the queue drained periodically.

After Step 4 emits the standard command, suggest:

```
/loop 1h node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix priority=high"
```

The `/loop` skill schedules it. Every hour, the script runs. If the queue is empty, it exits within seconds. If items appear (e.g., from CI or other agents), they get drained on the next tick.

---

### Example 3: One-shot drain of a specific container

User: "ralph everything in tech debt"

**Step 1** searches for "Tech Debt", finds container `89d02e32`. Filter resolves to `parentId=89d02e32`.

**Step 4** emits:

```
node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
  --filter "parentId=89d02e32" \
  --max 20
```

Smart cleanup is on by default — worktrees with no commits or uncommitted changes (e.g., from items whose schema only required note-fills) get auto-removed; worktrees with real diffs are preserved for review/push.

---

## Troubleshooting

**Problem: the script reports `error: could not read iteration prompt`**

Cause: The script expects `iteration-prompt.md` at `../skills/ralph/iteration-prompt.md` relative to its own location. The repo layout may have shifted, or the script was copied elsewhere.

Solution: Verify both files exist in the plugin layout: `claude-plugins/task-orchestrator/scripts/ralph-loop.mjs` and `claude-plugins/task-orchestrator/skills/ralph/iteration-prompt.md`. Don't move them independently.

---

**Problem: every iteration ends with `error: iteration agent exited cleanly without RALPH_OUTCOME marker`**

Cause: The iteration agent isn't following the prompt — it's exiting without emitting the outcome marker. Likely the agent ran out of budget, hit a tool restriction, or ignored the prompt's exit instructions.

Solution: Run with `--dry-run` and inspect the prompt that would be sent. If the prompt looks correct, raise `--budget` (some iterations need more headroom). If it persists, run one iteration manually with `claude -p --worktree=test-1 "$(cat skills/ralph/iteration-prompt.md)"` to debug interactively.

---

**Problem: claim contention — every iteration gets `skip` or `no_match` because all candidates are already claimed**

Cause: Stale claims from a crashed previous run, or another worker is already draining. TTL is the recovery mechanism.

Note: With selector mode, `claim_item` returns `no_match` (kind=permanent) when all queue items matching the filter are already claimed, rather than returning an `already_claimed` error. The iteration treats this as a `no-item` exit.

Solution: Check `query_items(operation="search", claimStatus="claimed")` to see who holds claims. If they're all from a crashed `ralph-<pid>-<ts>` actor, wait for TTL expiry (default 30 min per the loop's settings) or release them via `claim_item(releases=[...])` if you know they're stale.

---

**Problem: the iteration agent dispatches subagents (Agent tool) and that's confusing the loop**

Cause: The iteration prompt doesn't forbid subagents, but they add complexity (the iteration agent's worktree is the dispatched subagent's working directory too).

Solution: Subagent dispatch within an iteration is allowed and sometimes useful (e.g., for parallel reads). It's fine as long as the iteration agent collects subagent results, fills notes, and emits the outcome marker as its own final message. If subagents are causing context bloat, edit the iteration prompt to discourage them.

---

## Design notes

**Why `claude -p` per iteration, not a slash-command loop.** Huntley's canonical Ralph relies on **fresh context per iteration**. A slash-command loop in a single Claude session accumulates context across iterations, even with compaction — that's the variant Huntley publicly criticized when Anthropic's plugin shipped. The script-driver pattern (this design) preserves fresh context cleanly: each iteration is a new OS process with no memory of previous iterations.

**Why a Node script, not bash.** Cross-platform — every Claude Code install has Node. Stdlib-only — no `npm install`, no plugin dependencies. The script uses `node:util.parseArgs`, `node:child_process.spawn`, and `node:fs/promises`. Targets Node 18+ to match Claude Code's own minimum.

**Why a dedicated `ralph-iteration` output style.** Iterations run under their own output style (`task-orchestrator:ralph-iteration`), passed via `claude --settings`. The default `workflow-orchestrator` output style is shaped for interactive orchestration — tier classification, delegation tables, plan-mode discipline, the workflow-analyst footer — all of which are wrong for a single-item per-iteration agent. The Ralph output style suppresses that chrome and authoritatively encodes per-iteration rules (schema is contract, no auto-memory, no further dispatch, RALPH_OUTCOME marker as final message). Iteration agents get the right system prompt instead of one designed for a different mode.

**Why `--permission-mode bypassPermissions` on each iteration.** In `claude -p` (non-interactive) mode there is no UI prompt to approve MCP or tool calls — unpermitted calls auto-deny and the iteration aborts. Ralph cannot operate autonomously without bypassing the permission gate. The risk surface is bounded by four things working together: (1) the `--worktree` flag confines file edits to a single isolated tree; (2) the MCP server's own ACL still controls what TO operations are valid; (3) `--max-budget-usd` caps API spend per iteration; (4) the iteration prompt is tightly schema-scoped — it can't dispatch subagents, can't enter plan mode, and must emit `RALPH_OUTCOME` as its final message. If a deployment needs stricter permission control, swap the `--permission-mode` flag in the script for `--allowed-tools` with an explicit allowlist.

**Why smart cleanup is the default.** Each iteration's `--worktree` creates a fresh git worktree under `.claude/worktrees/ralph-...`. Without active cleanup, long-running deployments (especially scheduled Ralph via `/loop`) accumulate worktrees indefinitely — both on disk and in `git worktree list`. The cleanup heuristic checks two things after each `terminal` (or `no-item`) outcome: (1) does the worktree have uncommitted changes? (2) does it have commits ahead of `origin/main`? If neither: remove. If either: preserve, since there's something worth inspecting or pushing. `gate-blocked`, `error`, and `skip` outcomes always preserve regardless — debugging context matters. Pass `--no-cleanup` to opt out entirely (debugging-heavy sessions, audit-trail needs, etc.). The heuristic intentionally errs toward preservation: if `origin/main` can't be compared (e.g., upstream missing), the worktree is preserved rather than removed.

**Why no PR creation in the script.** The end state of an iteration is determined by the item's schema, not by Ralph. A `bug-fix` schema's review or terminal phase might prescribe pushing and opening a PR; an `agent-observation` schema's "done" might be just filling a single note. The script doesn't assume a code-change workflow — it just runs iterations and captures outcomes. Workflow logic lives in the schema, where users can configure it per their project.

**Why the `RALPH_OUTCOME:` marker instead of exit codes.** An agent inside `claude -p` doesn't directly control the parent process's exit code (that's Claude Code's harness). Structured stdout output is the cleanest channel — the script regex-matches the marker, parses the JSON, and decides loop control. This also makes outcomes inspectable in iteration logs after the fact.

**Why TTL=1800 default (not the documented 900).** Default TTL is 15 minutes. A non-trivial iteration (read context, fill notes, do code work, run tests, commit, advance) can exceed that. 1800s gives 30-minute headroom without requiring a heartbeat. For longer iterations, raise `--ttl` further; the cap is 86400 (24 hours).
