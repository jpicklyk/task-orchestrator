---
name: ralph
description: "Launcher for the Ralph-style queue drain script - emits the right `node ralph-loop.mjs` invocation based on the user's filter and bounds. The actual loop runs as a Node script that opens one server run and spawns one `claude -p --worktree` per iteration under its own seat; this skill is the configurator, not the loop. Use when a user says: drain the backlog, ralph the queue, run Ralph loop, work the queue, batch through queue items, autonomous queue worker."
argument-hint: "[optional: filter expression like 'tag=bug-fix' or 'type=quick-fix priority=high']"
---

# ralph - Queue Drain Launcher

This skill is a **launcher** for the Ralph drain script (`scripts/ralph-loop.mjs` in this plugin). It does not run a loop in the current session. It helps you configure the right invocation, previews what will be processed, and emits the exact command to run.

The actual loop runs as a separate Node process that talks to the server over the REST API. It opens **one run per loop**. Each iteration selects the oldest ready queue item with `items_read next`, adds it to the run, assigns it an `implementer` seat, claims it under that seat's token, and spawns a fresh `claude -p` in its own git worktree (via Claude Code's `--worktree` flag) to work the item to terminal per its schema. The seat token reaches the iteration only as a file path. The iteration signals its outcome with a `RALPH_OUTCOME:` JSON marker; the driver then ends the seat, and on exit closes the run.

> **Why a separate script, not a slash-command loop?** Per Geoff Huntley's canonical Ralph: the value comes from carving off small bits of work into independent context windows. Each iteration is a fresh process - fresh context, fresh worktree, isolated logs, clean exit codes. The skill-as-orchestrator pattern (one session, internal loop) accumulates context within a session and doesn't preserve fresh-context isolation. For autonomous cadence, compose with `/loop`: `/loop 30m node scripts/ralph-loop.mjs --filter "tag=bug-fix"`.

**Requirements.** The REST API must be reachable: `TASK_ORCHESTRATOR_API_URL` (or `apiUrl` in `.taskorchestrator/client.json`), plus `TASK_ORCHESTRATOR_API_TOKEN` when the server requires a credential. The loop also needs a project root: `--root`, an `ancestorId=` in the filter, or `project.rootId` in the project config. Without either the script exits 64.

---

## Step 1 - Resolve the filter

Determine what subset of the queue to drain.

**If `$ARGUMENTS` is non-empty**, parse it as a filter expression. Supported keys:

| Key | Effect |
|---|---|
| `tag=<value>` | Items carrying this tag (repeat the key, or comma-separate, for any-of) |
| `type=<value>` | Items with this exact `type` |
| `priority=<value>` | Items at this priority (`high`, `medium`, `low`) |
| `parentId=<uuid-or-prefix>` | Only direct children of this container |
| `ancestorId=<uuid-or-prefix>` | Only items anywhere in this ancestor's subtree, any depth - broader than `parentId` |

Multiple keys combine with AND (space-separated). Examples: `tag=bug-fix priority=high`, `type=quick-fix`, `parentId=89d02e32`, `ancestorId=<projectRootId>`. An unknown key is a CLI error (exit 64).

Every selection is scoped to a subtree: the filter's `ancestorId` when given, else the run's root. The same `ancestorId` scopes both the Step 3 preview and the drain itself.

**Project-scoped default:** when a **project** rootId is known (the `## Project Scope` section of the session context injected by the SessionStart hook, or the project-level config's `project.rootId`), the script uses it as the run's root and selection scope. Pass `--root` to drain another project. A personal root (`## Personal Scope`, or the user-level config) never defaults the root: it only anchors new items.

**If `$ARGUMENTS` is empty**, ask the user via `AskUserQuestion`:

```
* Ralph loop - what should I drain?
  1. Bug-fix backlog (tag=bug-fix)
  2. Quick fixes (type=quick-fix)
  3. Tech debt container (parentId=<lookup>)
  4. Anything in queue (no filter)
  5. Custom filter - specify
```

If the user picks "Tech debt container", search for it: `items_read(select="search", query="Tech Debt", limit=5)` and use the resulting UUID.

---

## Step 2 - Resolve bounds

Set sensible loop bounds. Show defaults and let the user adjust.

```
* Loop bounds (default in parens):
  Max iterations:           10
  Gate-failure budget:      3 consecutive
  Error budget:             2 consecutive
  Idle budget:              3 consecutive
  Per-iteration USD cap:    $5           (the hard stop)
  Iteration timeout:        2700s (45 min)
  Claim TTL per iteration:  1800s (30 min)
  Run budget:               none         (tokens / USD / attempts per seat)
  Model:                    sonnet
  Cleanup on terminal:      smart (remove if no commits/changes; preserve otherwise)
  Resume continuations:     2 per iteration (0 disables)

  Adjust any?  Reply "ok" to use defaults.
```

If the user adjusts, capture the overrides. Common patterns:
- High-volume drain: `--max 30`
- Low-confidence run: `--gate-budget 1 --error-budget 1`
- Architecture-heavy items: `--model opus --budget 15 --iteration-timeout 5400`
- Cap the whole run's spend on the server side: `--run-budget-usd 40` (or `--run-budget-tokens <n>`); the run pauses on a budget decision when it is reached
- Stop re-seating a struggling item: `--max-attempts 2`
- Drain an exact list of items: `--items <id>,<id>,...`
- Drain another project's queue: `--root <rootId>`
- Preserve every worktree (debugging-heavy session): `--no-cleanup`
- Disable resume-on-marker-less-exit: `--max-continuations 0`

There is no run budget unless one of `--run-budget-tokens`, `--run-budget-usd` or `--max-attempts` is passed. The removed `--actor` flag is gone for good: the server records the principal of every call, and each iteration acts under its own seat.

---

## Step 3 - Preview the queue

Show what the loop will see. Use `items_read` `next` with the resolved filter and scope - the same select the driver uses:

```
items_read(select="next", role="queue", orderBy="oldest", ancestorId="<rootId or filter ancestorId>", limit=10, ...)
```

Pass the filter's `tags`, `type`, `priority` and `parentId` in `...`. An empty page with a `reason` (`claim_held` or `dependency_unmet`) means matches exist but none is ready yet; an empty page without a `reason` means nothing matches.

Display:

```
* Queue preview - filter: tag=bug-fix priority=high
  Ready now: 7 items (oldest first)
    d4fc7b2e  Fix duplicate UUID race in claim handling
    92da8e9a  Gate response includes stale guidance
    0c916953  subagent-start hook double-advance
    00769317  advance applied:false contradicts DB
    2e4c9e77  create_tree dependency semantics
```

If nothing is ready, tell the user and stop here (mention the `reason` when there is one). If the count exceeds the iteration cap by a lot, mention that subsequent runs would pick up the rest.

---

## Step 4 - Emit the command

Print the exact command to run. The user copies and pastes it.

```
* Ready to launch. Run this command:

  node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
    --filter "tag=bug-fix priority=high" \
    --max 10 \
    --budget 5 \
    --ttl 1800 \
    --iteration-timeout 2700 \
    --model sonnet

  -> Opens one run on the project root; closes it when the loop ends
  -> Per iteration: one implementer seat, claimed under its seat token, then
     one `claude -p --worktree=ralph-...` with the token path in its env
  -> Each iteration is fresh context, isolated worktree
  -> Logs print live as iterations run (token values masked)
  -> An iteration that exits cleanly with no RALPH_OUTCOME marker is resumed
     in place (`claude -p --resume`, same worktree) up to `--max-continuations`
     times (default 2) before it's counted as an error
  -> An iteration that outlives --iteration-timeout is killed and counted as an error
  -> Final summary lists outcomes, the run, and preserved worktrees

  Optional flags: --root <rootId>, --items <id,...>, --run-budget-tokens <n>,
  --run-budget-usd <usd>, --max-attempts <n>, --scratch-dir <dir>, --claude-bin <path>

  For autonomous cadence (re-run every 30 min until empty):
    /loop 30m node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix priority=high"

  Dry-run first (no request is sent; prints the run open op and the iteration command):
    node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --dry-run --filter "tag=bug-fix priority=high"
```

Adjust the path if the user is in a different working directory - the script lives at `claude-plugins/task-orchestrator/scripts/ralph-loop.mjs` relative to the project root.

If the user wants the skill to launch the script directly (rather than emit a command), suggest using the Bash tool - but flag the tradeoff:

```
! Running the script via Bash from this session means the loop output streams
  into our conversation transcript. That works for short drains but burns
  context on long ones. For drains over 5 iterations, prefer running the
  command in a separate terminal so this session stays free for other work.
```

---

## Step 5 - Post-run handoff

After the script finishes (the user reports back with the summary, or pastes the output), help interpret the outcomes:

| Final summary line | Action |
|---|---|
| `Exit reason: queue drained` | Loop succeeded - nothing ready matches the filter |
| `Exit reason: iteration cap reached` | Re-run with same filter to continue, or raise `--max` |
| `Exit reason: gate failure budget exhausted` | Inspect preserved worktrees; the schema expects notes the iteration agent can't fill autonomously (often `review-checklist`) |
| `Exit reason: error budget exhausted` | Inspect last error; likely repo/build/network issue affecting all iterations |
| `Exit reason: idle budget exhausted` | Matches exist but stayed claimed, blocked or skip-listed; re-run later |
| `Exit reason: run paused` | The run hit its budget or attempt cap (or was paused by hand); a director or human answers the pause decision, or closes the run |

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

The iteration agent emits `RALPH_OUTCOME: {...}` as its final message, with an optional `code` (an error-catalog code) and `retryAfterMs`. The driver keys on `code` first, then on `status`. This one table, rendered from `OUTCOME_TABLE` in `scripts/ralph-lib.mjs`, is the whole mapping; the iteration prompt and the iteration system prompt carry the same rendered copy.

<!-- BEGIN GENERATED:ralph-outcomes -->
| Code | Kind | By | Status | Action | Loop |
|---|---|---|---|---|---|
| `terminal` | outcome | iteration | `terminal` | The item reached terminal per its schema. The driver ends the seat done. | continue |
| `gate-blocked` | outcome | iteration | `gate-blocked` | A required note cannot be filled autonomously. The driver ends the seat abandoned. | gate |
| `error` | outcome | iteration | `error` | Tool error, build failure, budget cap, or a marker-less exit after every continuation. The driver ends the seat failed. | error |
| `skip` | outcome | iteration | `skip` | The item cannot be worked now; put the catalog code in `code`. The driver ends the seat abandoned and skip-lists the item. | skip |
| `idle` | outcome | driver | `idle` | No item is ready: an empty next page with a reason, or every candidate skip-listed. The driver waits, then selects again. | backoff |
| `no-item` | outcome | driver | `no-item` | An empty next page with no reason: nothing matches the filter. The driver closes the run completed. | drained |
| `claim_held` | transient | driver | `skip` | The driver's claim under the seat token found another holder. It ends the seat abandoned and skip-lists the item for retryAfterMs. | skip |
| `resource_unavailable` | transient | iteration | `skip` | advance transition into work found a declared resource leased elsewhere. Emit skip with code and retryAfterMs; do not retry, do not release. The driver skip-lists the item for retryAfterMs. | skip |
| `dependency_unmet` | permanent | driver | `idle` | An empty next page whose reason is dependency_unmet: matches exist but are blocked. The driver waits 30s, then selects again. | backoff |
| `invalid_transition` | permanent | driver | `skip` | The item cannot be claimed or moved from its role (for example already terminal). Skip-listed for the rest of the loop. | skip |
| `duplicate` | permanent | driver | `skip` | update addItems found the item in another open run. Skip-listed for the rest of the loop. | skip |
| `gate_blocked` | permanent | iteration | `gate-blocked` | advance transition reported missing required notes the iteration cannot fill. Emit gate-blocked with code. | gate |
| `seat_forbidden` | permanent | iteration | `gate-blocked` | The item's schema does not let the implementer seat make this move. Emit gate-blocked with code; a coordinator or human advances it. | gate |
| `note_owned_by_other` | permanent | iteration | `gate-blocked` | A note this seat may not write belongs to another seat. Never take it over. Emit gate-blocked with code and the note key in reason; a coordinator or human fills it. | gate |
| `not_claim_holder` | permanent | iteration | `error` | A write was refused because the call does not hold the claim (claim lapsed, or the call went out without the seat token). Emit error with code. | error |
| `unauthenticated` | permanent | iteration | `error` | The seat token was refused: the iteration must reach the server as the same principal as the driver. Emit error with code. On a driver call with the coordinator token the loop exits 2. | error |
| `run_paused` | permanent | driver | `error` | The run paused (budget, attempt cap or manual). Never retried: the driver ends the seat and closes the run cancelled; a director or human answers the pause decision. | exit |
| `run_closed` | permanent | driver | `error` | The run was closed elsewhere. The driver ends the seat and exits. | exit |
| `run_at_capacity` | transient | driver | `idle` | assign hit the run's maxActiveSeats. The driver waits 30s, then retries the assign, up to 10 times, then ends the loop. | wait-retry |
| `unavailable` | shedding | driver | `idle` | The server shed load on a driver call. The driver waits retryAfterMs, then tries again. | backoff |
| `internal` | transient | driver | `idle` | A driver call failed inside the server (transient, no retryAfterMs). The driver waits 30s, then repeats the same step. | backoff |
| `iteration_timeout` | local | driver | `error` | The iteration outlived --iteration-timeout: SIGTERM, then SIGKILL after 10s. The driver ends the seat failed. | error |
| `spawn_error` | local | driver | `error` | The iteration process could not start (for example --claude-bin missing). The driver ends the seat failed. | error |
<!-- END GENERATED:ralph-outcomes -->

The seat's end follows the final status: `terminal` ends it `done`; `gate-blocked` and `skip` end it `abandoned`; `error` (including a timeout or spawn error) ends it `failed`. Every exit path ends any active seat before it closes the run (`completed`, or `cancelled` on SIGINT/SIGTERM or a paused run). An `end_seat` that fails leaves the seat active until `close`, where `completed` falls back to `cancelled`.

**Contention is a skip, never a release.** A claim the driver cannot take (`claim_held`) and a resource lease the iteration cannot take (`resource_unavailable`) both skip-list the item for its `retryAfterMs` (30s when absent); `invalid_transition`, `not_found` and `duplicate` skip it for the rest of the loop. The next selection picks a different item. A shedding or transient fault on a driver call (`unavailable`, `internal`, a network timeout) backs the loop off and retries the same step; it never skip-lists the item. See [Workflow Guide section 11 - Resource Leasing](../../../../current/docs/workflow-guide.md#11-resource-leasing) for the lease model.

The script's own exit code:

| Code | Meaning |
|---|---|
| `0` | Loop ended normally (drained, iteration cap, gate or idle budget, run paused or closed) |
| `2` | Error budget exhausted, or a REST call the loop depends on failed (run open, coordinator credential refused) |
| `64` | CLI argument error, no REST API URL, or no project root |
| `70` | Could not read iteration prompt |
| `130` / `143` | Interrupted by SIGINT / SIGTERM: iteration stopped, seat ended, run closed `cancelled` |

---

## Composition with other tools

| Want to... | Compose with |
|---|---|
| Run Ralph on a schedule | `/loop 30m node .../ralph-loop.mjs --filter ...` |
| Inspect what's ready before running | `/work-summary` to see queue contents |
| Resume a single gate-blocked item from Ralph | `/status-progression <item-id>` |
| Clean up a preserved worktree | `git worktree remove <path>` |
| Check the iteration prompt template | Read `skills/ralph/iteration-prompt.md` in this plugin |
| Regenerate the outcome table after editing `OUTCOME_TABLE` | `node claude-plugins/task-orchestrator/scripts/render-ralph-prompts.mjs` (`--check` verifies) |

---

## Examples

### Example 1: Drain the bug-fix queue

User: "ralph the bug-fix backlog"

```
$ARGUMENTS = "tag=bug-fix"
```

**Step 1** parses the filter: `tag=bug-fix`; the project root scopes it.

**Step 2** uses defaults (max=10, budgets=3/2, $5/iter, 45 min timeout, sonnet).

**Step 3** shows 7 ready items, oldest first.

**Step 4** emits:

```
node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
  --filter "tag=bug-fix" \
  --max 10
```

User runs it. The driver opens one run. Per iteration it selects the oldest ready item, assigns it an implementer seat, claims it under the seat token, and spawns a fresh `claude -p --worktree=ralph-<id>` whose prompt names the item and the token file. The agent reads the item's context, invokes `/schema-workflow` to drive it through whatever phases its schema declares, commits changes, and exits with the outcome marker; the driver ends the seat.

5 iterations later: 3 terminal, 1 gate-blocked (a required note couldn't be filled), 1 errored (test failure unrelated to the fix). Loop exits when consecutive gate failures hit the budget (3) or nothing is ready - whichever comes first - and closes the run.

---

### Example 2: Scheduled overnight drain

User wants the queue drained periodically.

After Step 4 emits the standard command, suggest:

```
/loop 1h node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs --filter "tag=bug-fix priority=high"
```

The `/loop` skill schedules it. Every hour, the script runs, opening a fresh run. If nothing is ready, it closes the run and exits within seconds. If items appear (e.g., from CI or other agents), they get drained on the next tick.

---

### Example 3: One-shot drain of a specific container under a spend cap

User: "ralph everything in tech debt, but stop at $30"

**Step 1** searches for "Tech Debt", finds container `89d02e32`. Filter resolves to `parentId=89d02e32`.

**Step 4** emits:

```
node claude-plugins/task-orchestrator/scripts/ralph-loop.mjs \
  --filter "parentId=89d02e32" \
  --max 20 \
  --run-budget-usd 30
```

When the run's reported spend reaches $30 the server pauses the run on a budget decision; the next assign answers `run_paused`, and the driver ends the loop and closes the run `cancelled`.

---

## Troubleshooting

**Problem: the script exits 64 with "ralph on 4.0 needs the REST API"**

Cause: no REST base URL resolved. The driver has no MCP client; every coordinator call (run open, assign, claim, end seat, close) goes over REST.

Solution: set `TASK_ORCHESTRATOR_API_URL` (for example `http://localhost:3001`) or `apiUrl` in `.taskorchestrator/client.json`, and `TASK_ORCHESTRATOR_API_TOKEN` when the server requires a credential.

---

**Problem: every iteration ends `error` with code `unauthenticated` (the seat token was refused)**

Cause: a seat token is accepted only from the principal it was issued to. The driver obtains it over REST with `TASK_ORCHESTRATOR_API_TOKEN`; the iteration presents it over its own MCP connection. If those two connections authenticate as different principals, every seat-token call from the iteration is refused.

Solution: make both reach the server as the same principal. Local mode (`API_AUTH_MODE=none` with `API_ALLOW_UNAUTHENTICATED=true`, or `MCP_ALLOW_UNAUTHENTICATED`) makes both `local` out of the box. In credential mode, configure the iteration's MCP server entry with the same bearer as `TASK_ORCHESTRATOR_API_TOKEN`. The driver prints this hint once.

---

**Problem: where does the seat token live, and who can read it?**

The seat token value lives in exactly one place: `<scratch-dir>/tokens/<iter>.implementer.token`, one line, written with mode `0o600` in a `0o700` directory, and deleted after the seat ends (a SIGKILLed driver leaves it). `--scratch-dir` defaults to `<tmpdir>/task-orchestrator/ralph/<label>`. The iteration gets the file's PATH (in `TASK_ORCHESTRATOR_SEAT_TOKEN_FILE` and in its prompt), never the value; the coordinator token stays in the driver's memory. File modes are advisory on Windows, so the scratch directory must be private to the user running the loop - do not point `--scratch-dir` at a shared or synced folder. Streamed iteration output, the iteration records and the summary mask every known token value as `[redacted-token]`. After the iteration reads the file, its own `claude -p` transcript holds the value for the seat's lifetime; the token dies at `end_seat`.

---

**Problem: a warning says a run from an earlier ralph is still open**

Cause: a driver killed with SIGKILL (or a crash) cannot close its run, and its coordinator token died with it. Its members are `duplicate` for a new run's `update addItems`, so the new loop skips them.

Solution: close the stale run from a director or human credential: `runs_write(ops=[{op: "close", runId: "<run id from the warning>", reason: "abandoned"}])`, and delete `<scratch-dir>/tokens`. That ends its seats and releases their claims. Recovery is manual by design; SIGINT and SIGTERM are handled and always close the run.

---

**Problem: the script reports `error: could not read iteration prompt`**

Cause: The script expects `iteration-prompt.md` at `../skills/ralph/iteration-prompt.md` relative to its own location. The repo layout may have shifted, or the script was copied elsewhere.

Solution: Verify both files exist in the plugin layout: `claude-plugins/task-orchestrator/scripts/ralph-loop.mjs` and `claude-plugins/task-orchestrator/skills/ralph/iteration-prompt.md`. Don't move them independently.

---

**Problem: every iteration ends with `error: iteration agent exited cleanly without RALPH_OUTCOME marker (after N continuation(s))`**

Cause: The iteration agent isn't following the prompt - it keeps ending its turn without emitting the outcome marker, even after the loop driver resumed it in place (`claude -p --resume <session_id>`, same worktree) via `--max-continuations`. The `(after N continuation(s))` suffix tells you how many resumes were tried before giving up; without the suffix, no continuation was attempted at all.

Solution: Run with `--dry-run` to check the iteration command and the rendered prompt size. Raise `--budget` (some iterations need more headroom) or `--max-continuations`. To debug interactively, run one iteration by hand with `claude -p --worktree=test-1 --append-system-prompt-file skills/ralph/iteration-system-prompt.md "<rendered iteration-prompt.md>"` against an item you have claimed under a seat token yourself.

---

**Problem: every selection is idle because all candidates are claimed or blocked**

Cause: Stale claims from a crashed previous run, or another worker is already draining. `items_read next` answers an empty page with `reason: claim_held` (or `dependency_unmet`) when matches exist but none is ready; the driver treats that as idle, waits 30s and selects again, up to `--idle-budget` consecutive idles.

Solution: Check who is working what with `items_read(select="list", role="work")` and `runs_read(select="list", status="open")`. A claim lapses at its TTL; a stale run is closed as above.

---

**Note: Ralph workers skip sub-items of in-progress features (ancestor-claim filtering)**

`items_read next` leaves out a candidate under an ancestor claimed by another agent, so a Ralph drain never picks the child tasks of a feature an orchestrator holds - the correct and desired behavior. There is no depth-0 filter; to drain only top-level items, tag or type them distinctly (for example `tag=top-level`) and filter on that.

---

**Problem: iterations end `gate-blocked` with code `note_owned_by_other`**

Cause: the item's schema is seat-aware and a required note belongs to another seat (planner, reviewer, test-author or orchestrator). The implementer seat token cannot write it, and takeover is never allowed.

Solution: have that seat or a human fill the note, or drain only items whose schema leaves every note to the implementer.

---

**Problem: iterations end `gate-blocked` with code `seat_forbidden`**

Cause: the item's schema declares seats, and its transitions past work belong to a seat other than `implementer` (for example a reviewer seat). The iteration cannot make that move under its implementer seat.

Solution: drain items whose schema lets one seat carry them to terminal, or finish those items with `/status-progression` or a run-wave.

---

**Problem: the iteration agent dispatches subagents (Agent tool) and that's confusing the loop**

Cause: The iteration system prompt forbids further dispatch, but an agent may still try.

Solution: The iteration must collect any results itself, fill notes, and emit the outcome marker as its own final message. If dispatch persists, tighten the system prompt's "No further dispatch" rule for your project.

---

## Design notes

**Why `claude -p` per iteration, not a slash-command loop.** Huntley's canonical Ralph relies on **fresh context per iteration**. A slash-command loop in a single Claude session accumulates context across iterations, even with compaction. The script-driver pattern (this design) preserves fresh context cleanly: each iteration is a new OS process with no memory of previous iterations.

**Why a Node script, not bash.** Cross-platform - every Claude Code install has Node. Stdlib-only - no `npm install`, no plugin dependencies. Targets Node 18+ to match Claude Code's own minimum.

**Why one run per loop, one seat per iteration.** A run gives the drain a server-side boundary: membership, an optional budget, attempt caps, and one place a director or human can pause or close it. Each iteration gets its own `implementer` seat and claims the item under that seat's token before the agent starts, so the iteration is the claim holder for its transitions and note writes, and ending the seat on every outcome (timeout and crash included) frees the claim at once instead of leaving it to run out its TTL.

**Why the driver selects and claims, not the iteration.** Selection with `items_read next` plus a per-loop skip list keeps contended items out of the way: a `claim_held` or `resource_unavailable` item is skipped for its `retryAfterMs` and the next selection picks a different one, with no release and no spin. Claiming before the spawn means the seat token, not the agent, owns the claim.

**Why the token travels as a file path.** An agent cannot use an environment value without echoing it into its own transcript, and argv and prompt text are logged. A `0o600` file whose path is the only thing handed over keeps the value out of every log the driver writes, and the driver masks any known token value that still shows up in the child's output.

**Why a dedicated iteration system prompt.** Iterations append `skills/ralph/iteration-system-prompt.md` to the system prompt via `claude --append-system-prompt-file`, and pin `--settings '{"outputStyle":"default"}'` so a user's own configured output style never reaches an iteration. Orchestration-shaped chrome is wrong for a single-item per-iteration agent. The appended prompt suppresses it and authoritatively encodes per-iteration rules (schema is contract, the seat token is a credential, no auto-memory, no further dispatch, RALPH_OUTCOME marker as final message), while keeping the default Claude Code system prompt and its tool guidance intact.

**Why `--permission-mode bypassPermissions` on each iteration.** In `claude -p` (non-interactive) mode there is no UI prompt to approve MCP or tool calls - unpermitted calls auto-deny and the iteration aborts. The risk surface is bounded by: (1) the `--worktree` flag confines file edits to a single isolated tree; (2) the server's own scope checks and the seat's permissions still control what TO operations are valid; (3) `--max-budget-usd` and `--iteration-timeout` cap spend and time per iteration; (4) the iteration prompt is tightly schema-scoped. If a deployment needs stricter permission control, swap the `--permission-mode` flag in the script for `--allowed-tools` with an explicit allowlist.

**Why smart cleanup is the default.** Each iteration's `--worktree` creates a fresh git worktree under `.claude/worktrees/ralph-...`. Without active cleanup, long-running deployments accumulate worktrees indefinitely. After each `terminal` outcome the heuristic checks (1) uncommitted changes and (2) commits ahead of `--base-ref`. If neither: remove. If either: preserve. `gate-blocked`, `error`, and `skip` outcomes always preserve. Pass `--no-cleanup` to opt out entirely. If `--base-ref` can't be compared, the worktree is preserved rather than removed.

**Why no PR creation in the script.** The end state of an iteration is determined by the item's schema, not by Ralph. The script doesn't assume a code-change workflow - it just runs iterations and captures outcomes.

**Why the `RALPH_OUTCOME:` marker instead of exit codes.** An agent inside `claude -p` doesn't directly control the parent process's exit code. Structured stdout output is the cleanest channel - the script finds the marker, parses the JSON, and decides loop control. This also makes outcomes inspectable in iteration logs after the fact.

**Why one rendered outcome table.** The skill, the iteration prompt and the iteration system prompt used to carry their own outcome lists, and they drifted. `OUTCOME_TABLE` in `scripts/ralph-lib.mjs` is now the single source, keyed on error-catalog codes (each code's kind is the catalog's), and `scripts/render-ralph-prompts.mjs` writes it into all three files; `--check` fails on drift.

**Why a marker-less clean exit gets resumed instead of immediately counted as an error.** A model left without a human in the loop can end its turn early - trailing off into a status report instead of continuing the work or emitting a terminating signal. The loop driver treats "clean exit, no marker" as recoverable: it resumes the same session (`claude -p --resume <session_id>`, same worktree - never `--worktree`, which would spin up a new one) and asks it to finish or emit the marker itself, up to `--max-continuations` times (default 2) and only while the iteration's remaining `--budget` stays at or above $0.25 and the iteration timeout has not passed. The decision keys on marker *absence*, never on the outcome's `status`. Spend across resumes is tracked from each envelope's `total_cost_usd`, which reports the resumed conversation's running total, so the driver takes the latest reading rather than summing.

**Why TTL=1800 default (not the server's 900).** A non-trivial iteration can exceed 15 minutes. 1800s gives 30-minute headroom without requiring a heartbeat; ending the seat frees the claim as soon as the iteration finishes. For longer iterations, raise `--ttl` along with `--iteration-timeout`; the cap is 86400 (24 hours).
