---
name: status-progression
description: "Navigates role transitions for MCP work items: shows the current role, gate status, missing notes and the right trigger; use when a user says: advance this item, move to work, start this task, complete this item, what's the next status, why can't I advance, unblock this, cancel this item, reopen this, or check gate status."
argument-hint: "[optional: item id or title to look up]"
---

# Status Progression

Guides role transitions for a work item: identify it, read its gate status, fill missing notes, advance, and recover from a blocked or failed transition. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md) (`advance` `transition`); semantics are in `current/docs/api-reference.md` ("advance" and "items_read"). Ids below are 8-hex shorts and any id field also takes the full UUID. Read every answer through the envelope: `ok`, then `data.results[0]` (`ok` or `error`) and `data.summary {ok, failed}`. When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind` (retry class), `detail` and `fix` from `data.results[0].error`. A read (`select`) call reports its failure in the call-level `error`.

## Step 1: Identify the item

Resolve `$ARGUMENTS` to an id with a search; if several match, show them via `AskUserQuestion`. With no argument, ask for an id or a title fragment.

```to-call
{"tool": "items_read", "select": "search", "args": {"query": "OAuth login", "kinds": ["item"], "limit": 5}}
```

## Step 2: Read the state

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "4b7d0e19"}}
```

Render a status card from the answer:

```
* "Implement authentication module"
  Role:     work
  Gate:     BLOCKED - 2 required notes missing
  Missing:  implementation-notes (work), session-tracking (work)
  Guidance: "Describe what was implemented, which files changed and why..."
  Next:     start -> review
```

| Field | Show |
|---|---|
| `item.role` | the role (absent for a lifecycle-free item: no card, it has no lifecycle) |
| `gateStatus.canAdvance` | open or blocked |
| `gateStatus.missing`, `missingBySeat` | each missing note key and role, and which seat owns it |
| `gateStatus.blockedBy` | first failing gate: `table`, `dependency`, `note`, `lease` |
| `guidanceKey` | resolve its text with `items_read` `schema` (`fields: ["guidance"]`) and show it as Guidance |
| `transitions` | the allowed triggers and the role each leads to |
| `item.blockedReason`, `item.decisionId` | for a blocked item: why it is blocked (below) |
| `decisions[]` | the item's decisions, withdrawn ones excluded: `id`, `class`, `status`, and the chosen `option` once answered |

A schema-free item has an open gate: the schema read answers `{schemaFree: true}`.

## Step 3: Fill missing notes

If `canAdvance` is false because of notes, fill them from the conversation; ask the user when the content cannot be inferred. Send them in one call, an `upsert` element per note:

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "4b7d0e19", "key": "implementation-notes", "role": "work", "body": "Added the retry policy in client.kt; tests cover 503 and timeout."}}
```

Re-read `context` and confirm `canAdvance` is true. Only one `guidanceKey` is returned at a time (the first unfilled required note); `schema[]` lists all of them.

## Step 4: Advance

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "4b7d0e19", "trigger": "start", "summary": "Implementation finished"}}
```

Report from `data.results[0]`:

```
OK queue -> work
  Cascade: "Feature: Auth System" also moved queue -> work
  Unblocked: "Write integration tests"
  Next phase notes: implementation-notes (work), session-tracking (work)
```

| Field | Report |
|---|---|
| `previousRole`, `newRole` | the transition |
| `cascades` | parents that moved, or were suppressed (`applied: false`, with `blocked` or `reason`) |
| `unblocked` | items now actionable |
| `expectedNotes` | notes the new phase expects |

Omit a line when its field is empty.

## Triggers

| Trigger | From | To | Notes |
|---|---|---|---|
| `start` | queue, work, review | work, review, terminal | review to terminal; work to terminal when the schema has no review notes. Gates the current phase |
| `complete` | any non-terminal, non-blocked | terminal | checks required notes of every phase |
| `cancel` | any non-terminal | terminal | statusLabel cancelled; bypasses note gates, not claim ownership |
| `block` / `hold` | any non-terminal, non-blocked | blocked | take `reason` (below) |
| `resume` | blocked | previous role | clears the reason |
| `reopen` | terminal | queue | clears statusLabel, bypasses note gates (not claim ownership), and is a pin point (the item is re-pinned to the current config) |

## Blocked items

`block` and `hold` take an optional `reason`: `awaiting_decision`, `dependency`, `resource` or `manual` (default). `decisionId` is allowed only with `awaiting_decision`, where it must name an open decision on that item:

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "4b7d0e19", "trigger": "block", "reason": "awaiting_decision", "decisionId": "7c9e6679"}}
```

- `items_read` `get` and `context` show `blockedReason` and `decisionId` for a blocked item.
- `items_read` `blocked` lists blocked items: each entry has `blockType` (`explicit` or `dependency`) and `blockers[]` (`threshold`, `satisfied`); an explicit entry also has `reason`, and an `awaiting_decision` entry adds `decisionId` and `resumable` (true once the decision is answered, withdrawn or missing).
- **Answering a decision never resumes the item.** Read the answer, then send `resume` yourself. `resume` clears the reason.
- Decisions are listed on the item in `context` `decisions[]`; `runs_read` `decisions` is director-only.
- Errors to recognize (on `data.results[0].error`): `decision_closed` (`detail.decisionId`, `detail.status`: the decision is already answered or withdrawn, so it cannot be waited on or acted on again); `invalid_transition` with `detail.fromRole: none` (a lifecycle-free item takes no trigger).

## Troubleshooting

**`gate_blocked`.** `data.results[0].error.detail.missing[]` lists the notes. Fill them (Step 3) and retry.

**Blocked by a dependency (`dependency_unmet`).** Find the blockers; advance them first, then this item appears in their `unblocked`:

```to-call
{"tool": "items_read", "select": "blocked", "args": {"ancestorId": "98d452e9"}}
```

Use `/dependency-manager` to view or change the edges.

**Item is in the blocked role and `start` fails.** `start` is not valid from blocked. Send `resume` (see Triggers), re-read `context`, then `start`.

**Skip the review phase.** `complete` jumps from work straight to terminal but checks every phase's required notes; fill what is missing or use `cancel`.

**`resource_unavailable` (kind transient).** A shared resource the item declares through a trait is held by another item. This is not a note gate: do not fill notes and do not spin-retry. Report `data.results[0].error.detail.resources[]` and `data.results[0].error.retryAfterMs`, work a different item, and retry later. To see the holder, read `context` `resourceLeases` (`holderItemId`, `expiresAt`); an operator can clear a stuck lease with `DELETE /api/v1/resources/leases/{key}` (admin). See [Workflow Guide, Resource Leasing](../../../../current/docs/workflow-guide.md#11-resource-leasing).

**A parent cascaded unexpectedly.** Cascades are by design: the first child starting moves a queued parent to work; the last child reaching terminal moves the parent to terminal. The `cascades` list shows each parent and why. A parent in review is never moved by that cascade (`applied: false`, review-pending): it needs its own explicit `complete` (or its review, then `start`).

**`invalid_transition`.** Read `data.results[0].error.detail.fromRole` and `.allowed`. A terminal item allows only `reopen`; a lifecycle-free item (`fromRole: none`) allows nothing.

**Item is terminal and should be worked again.** Send `reopen`; it returns to queue and its terminal parent returns to work.

**What next?** The next ready item, ranked by priority (`limit` defaults to 1):

```to-call
{"tool": "items_read", "select": "next", "args": {"role": "queue", "limit": 3}}
```

An empty answer with a `reason` (`claim_held`, `dependency_unmet`) means wait; no `reason` means nothing matches.

## Example: gated flow

1. `context` shows `role: queue`, `canAdvance: false`, `missing: ["feature-summary"]`.
2. Resolve its guidance, draft the note from the conversation, `notes_write` upsert it with `role: "queue"`.
3. `context` again: `canAdvance: true`.
4. `advance` `transition` `start` answers `newRole: work` and `expectedNotes` for the work phase. Fill those as work proceeds, then return control; the orchestrator advances to review or terminal.

## Quick decision guide

| Situation | Action |
|---|---|
| In queue, no gate | `start` |
| In queue, notes missing | fill them, then `start` |
| In work, ready for review | `start` |
| In work, skip review | `complete` (checks all phases) |
| In review, verified | `start` (lands terminal) |
| Needs pausing | `block` or `hold` with a `reason` |
| In blocked | `resume` |
| Abandon | `cancel` |
| Terminal, needs rework | `reopen` |
| Blocked by another item | advance the blocker first |
