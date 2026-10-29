# Ralph Iteration - Work One TO Item

This prompt is the per-iteration workflow. Operating principles, output discipline, and what NOT to do all live in the iteration system prompt (`iteration-system-prompt.md`, appended to your system prompt) - read them there, not here. This document covers only the steps for *this specific* iteration.

The loop driver has already picked your item, added it to its run, assigned you the `implementer` seat and claimed the item under that seat. You do not select or claim anything, and you never hand the claim back.

## Per-iteration variables

- **Item:** `${item_id}`
- **Run:** `${run_id}`
- **Seat token file:** `${seat_token_file}` (the same path is in the `TASK_ORCHESTRATOR_SEAT_TOKEN_FILE` environment variable)
- **Claim TTL:** `${ttl}` seconds, held for you by the driver

## Your seat token

Read the seat token file with your file tool and trim it. Pass the value as the top-level `seatToken` field of every task-orchestrator call you make, reads included. It is a credential: never print it, echo it, quote it in a note, a commit message or your final message, or write it to any other file. A call without it is not the claim holder and its writes are refused (`not_claim_holder`).

---

## Step 1 - Read the item

```
items_read(select: "context", itemId: "${item_id}", seatToken: <token>)
```

The context shows the item's role, its resolved schema (expected notes per phase, keys and seats only, no guidance), gate status, and blockers. If the item is already terminal, emit `skip` with code `invalid_transition` and stop.

---

## Step 2 - Drive through the schema

Invoke `/schema-workflow` with the item ID:

```
/schema-workflow ${item_id}
```

The skill reads the item's schema at runtime (guidance text via `items_read` `schema` with `fields: ["guidance"]`) and drives note fills (`notes_write`) and phase moves (`advance` op `transition`) up to, but not including, the move into terminal. Every one of those calls carries your `seatToken`. Your job inside that flow:

- **Author note content** per each note's `guidance` field. The guidance is authoritative - follow it.
- **Do the actual work** the notes describe. Could be code changes, research, configuration edits, anything. The schema decides what; you execute.
- **Run any verification** the spec note calls for (tests, linters, etc.).
- **On a seat-aware schema** your seat token writes only keys with no seat or `seat: implementer`. Planner, reviewer, test-author and orchestrator keys answer `note_owned_by_other`: never take them over; emit `gate-blocked` with that code and the key in `reason`.
- **On a seat-less schema, bug-fix items may carry the `needs-test-author` notes** (when the project's bug-fix schema includes that trait - check the expected notes) - if so, fill `test-plan` before advancing to work, write the regression test red-first, and record verdict `independent-degraded (temporal-only)` in the `test-independence-audit` note.

When a call fails, branch on its `error.code` using the outcome table below, and put that code in your `RALPH_OUTCOME` as `code` (with `retryAfterMs` when the error carries one). In particular:

- **`resource_unavailable`** on a transition into work (a shared resource the item declares is leased by another item, kind `transient`): this is expected contention, not a failure. Do NOT retry the transition and leave the claim alone; emit `skip` with `code: "resource_unavailable"` and the error's `retryAfterMs`. The driver skip-lists the item and ends your seat.
- **`gate_blocked`** that you cannot clear because a required note needs information you do not have: emit `gate-blocked` with the note key in `reason`.
- **`run_paused`** or **`run_closed`**: stop at once and emit `error` with that code; the driver ends your seat and waits for a paused run to resume, or exits on a closed one.

**Stop before terminal.** Do NOT send the final `complete` transition (or any move that lands the item in terminal). Entering terminal clears the claim, and the driver can report this iteration's usage only while the claim is still held. Fill every note, commit, and emit `ready-to-complete`; the driver reports usage and sends the transition itself.

Do not end your seat yourself; the driver ends it when your process exits.

---

## Step 3 - Commit and emit the outcome

If the work involved file changes, commit them with a message that references the item:

```
git -C <worktree-path> add <changed-files>
git -C <worktree-path> commit -m "<descriptive>: <title> (item <short-uuid>)"
```

If the schema's terminal phase declared push or PR steps, follow them. Otherwise, leaving the worktree with a commit is sufficient.

Emit the outcome marker as your final message:

```
RALPH_OUTCOME: {"status": "ready-to-complete", "itemId": "${item_id}", "summary": "<short description of what was done>"}
```

If the item is already terminal (a one-step schema moved it for you), emit `terminal` instead; that status cannot carry usage.

For any other ending use the matching status from the table, with `reason` instead of `summary`, plus `code` (and `retryAfterMs`) when a catalog code caused it:

```
RALPH_OUTCOME: {"status": "skip", "itemId": "${item_id}", "code": "resource_unavailable", "retryAfterMs": 30000, "reason": "<contended resource>"}
```

## Outcome table

Rows marked `by: driver` are observed by the loop driver, not by you; they are listed so the whole contract is in one place.

<!-- BEGIN GENERATED:ralph-outcomes -->
| Code | Kind | By | Status | Action | Loop |
|---|---|---|---|---|---|
| `ready-to-complete` | outcome | iteration | `ready-to-complete` | All notes are filled and the work is committed, but the iteration did NOT send the move into terminal. The driver reports usage and sends the complete transition itself while the claim is still held, then ends the seat done. | continue |
| `terminal` | outcome | iteration | `terminal` | The item already reached terminal per its schema (legacy; prefer ready-to-complete). The driver ends the seat done and cannot report usage (usage-unreported). | continue |
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
| `note_owned_by_other` | permanent | iteration | `gate-blocked` | A note this seat may not write belongs to another seat. Never take it over. Emit gate-blocked with code and the note key in reason; that seat or a human fills it. | gate |
| `not_claim_holder` | permanent | iteration | `error` | A write was refused because the call does not hold the claim (claim lapsed, or the call went out without the seat token). Emit error with code. | error |
| `unauthenticated` | permanent | iteration | `error` | The seat token was refused: the iteration must reach the server as the same principal as the driver. Emit error with code. On a driver call with the coordinator token the loop exits 2. | error |
| `run_paused` | permanent | driver | `error` | The run paused (budget, attempt cap or manual). Never retried and never closes the run: the driver ends the seat, then polls the run every 30s until a director or human answers the pause decision (or resumes a manual pause) and the loop continues, or closes the run (exit 0). | pause-wait |
| `run_closed` | permanent | driver | `error` | The run was closed elsewhere (a closed run's tokens may also answer unauthenticated; the driver probes the run to tell the two apart). The driver ends the seat and exits 0 without closing. | exit |
| `run_at_capacity` | transient | driver | `idle` | assign hit the run's maxActiveSeats. The driver waits 30s, then retries the assign, up to 10 times, then ends the loop. | wait-retry |
| `unavailable` | shedding | driver | `idle` | The server shed load on a driver call. The driver waits retryAfterMs, then tries again. | backoff |
| `internal` | transient | driver | `idle` | A driver call failed inside the server (transient, no retryAfterMs). The driver waits 30s, then repeats the same step. | backoff |
| `iteration_timeout` | local | driver | `error` | The iteration outlived --iteration-timeout: SIGTERM, then SIGKILL after 10s. The driver ends the seat failed. | error |
| `spawn_error` | local | driver | `error` | The iteration process could not start (for example --claude-bin missing). The driver ends the seat failed. | error |
<!-- END GENERATED:ralph-outcomes -->
