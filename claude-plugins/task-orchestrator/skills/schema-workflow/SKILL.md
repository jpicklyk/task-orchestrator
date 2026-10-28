---
name: schema-workflow
description: "Internal, invoked from the orchestration context: drives a schema-typed MCP item through its gate-enforced phases, filling required notes."
user-invocable: false
---

# Schema Workflow

Drive a work item through its gate-enforced lifecycle. The skill is schema-driven: note requirements and authoring guidance are read from the item's resolved schema at runtime, never assumed. Shapes are in [`_generated/call-shapes.md`](../_generated/call-shapes.md); ids below are 8-hex shorts (a full UUID is also accepted). Read every answer through the envelope: `ok`, then `data.results[0]` (`ok` or `error`) and `data.summary {ok, failed}`. When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind` (retry class), `detail` and `fix` from `data.results[0].error`. A read (`select`) call reports its failure in the call-level `error`.

**Applies to** any item whose `type` resolves to a schema under its pin (`config` `effective` lists the declared types). An item whose type resolves to no schema is schema-free and advances without gates.

## Entry point

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "4b7d0e19"}}
```

| Field | Meaning |
|---|---|
| `item.role` | queue, work, review, blocked or terminal. Absent for a lifecycle-free item |
| `schema[]` | every schema note: `key`, `role`, `required`, `exists`, `filled`, and `seat` when the schema is seat-aware (keys only) |
| `gateStatus.canAdvance` | whether `start` would pass right now (claim ownership excluded) |
| `gateStatus.missing` / `missingBySeat` | required notes of the current phase not yet filled, and by whom |
| `gateStatus.blockedBy` | first failing gate: `table`, `dependency`, `note` or `lease`; present only when `canAdvance` is false |
| `guidanceKey` | first unfilled required note; resolve its text with the schema read below |
| `skillPointer` | skill named by that note; invoke it before filling |
| `dispatch` | `{agent?, model?, effort?}` for the item's current role |
| `transitions` | each allowed trigger and the role it leads to |

- `item.role` of `terminal`: nothing to do. `blocked`: use `/status-progression`.
- **Schema-free:** the schema read answers `{schemaFree: true}`. Tell the user "No schema for this item's type; use `/manage-schemas` to configure gates". The item still advances freely.
- **Lifecycle-free** (role absent): never advanced; its notes are optional. Any `advance` trigger fails `invalid_transition` with `fromRole: none`.

Resolve authoring text (guidance, description, skill) for the notes:

```to-call
{"tool": "items_read", "select": "schema", "args": {"itemId": "4b7d0e19", "fields": ["guidance"]}}
```

## Phase progression loop

Each phase: **fill the required notes, then advance.**

### Step 1 - Find the missing notes

Read `gateStatus.missing`. If it is empty and `canAdvance` is true, go to Step 3.

### Step 2 - Fill the notes

For the note named by `guidanceKey`, follow its resolved `guidance`; if `skillPointer` is set, invoke that skill first and use its output. If `guidanceKey` is absent, use the note's `description`. Write the body as distilled prose; route verbatim artifacts (test output, diffs, logs) through `bodyFromFile`.

```to-call
{"tool": "notes_write", "op": "upsert", "args": {"itemId": "4b7d0e19", "key": "implementation-notes", "role": "work", "body": "Added the retry policy; one deviation, see below."}}
```

When you already know several bodies, send them in one `notes_write` call (an `upsert` element each). Re-read `context` only when you need the next `guidanceKey`. A note whose `role` differs from the schema's role fails `schema_violation`. A `schema_pinned_conflict` means the item's pin lacks a key the current config requires: `repin` it (`items_write` `repin`), then retry.

### Step 3 - Advance

```to-call
{"tool": "advance", "op": "transition", "args": {"itemId": "4b7d0e19", "trigger": "start"}}
```

Read `data.results[0]`:

| Field | Check |
|---|---|
| `newRole` | the phase entered (`previousRole` is also present) |
| `cascades` | parents re-evaluated: `{itemId, previousRole, targetRole, applied, blocked?}`; when blocked, `reason` and `missing[]` (gate), `contended[]` (resource) or `blockers[]` (dependency) |
| `unblocked` | items that were waiting on this one |
| `expectedNotes` | note keys the new phase expects; fill these next |
| `dispatch` | the profile for `newRole` |

A failed transition is `ok: false` with call-level `partial_failure`; the reason is `data.results[0].error.code`:

- `gate_blocked`: `data.results[0].error.detail.missing[]` names the notes still unfilled. Fill them (Step 2) and retry; no need to re-read first. Also read `detail.violations[].gate` in that same error: `missing` and `violations` may both be present, and a review-pending or independence violation is not cleared by filling notes.
- `resource_unavailable` (`data.results[0].error.kind: transient`): not a note gate. Another item holds a resource this item's traits declare. Do not fill notes and do not spin-retry; report `data.results[0].error.detail.resources[]` and `data.results[0].error.retryAfterMs` and see `/status-progression`.
- `dependency_unmet`: a blocker is unfinished; diagnose with `/dependency-manager`.
- `not_claim_holder`: someone else holds the claim; only the holder can transition.

### Step 4 - Repeat or finish

If `expectedNotes` lists unfilled required notes, go back to Step 2. If `newRole` is `terminal` the item is complete. Otherwise keep working and fill notes as progress is made.

## Phase-specific guidance

| Phase | Typical purpose | Filled |
|---|---|---|
| queue | requirements, design, reproduction steps | during planning, before work |
| work | implementation notes, test results | during or after implementation |
| review | verdicts, deploy or verification notes | after implementation |

Keys vary per schema; take them from `schema[]`, never from assumption.

## Orchestrator and subagent responsibility

**Protocol injection is agent-type gated.** The `subagent-start` hook injects the Agent-Owned-Phase Protocol only when the dispatched `agent_type` resolves to `implementer` or `reviewer` (bare or plugin-qualified, for example `task-orchestrator:implementer`). Any other agent type gets no injected protocol: dispatch the phase owner as `task-orchestrator:implementer` or `task-orchestrator:reviewer`, or paste the protocol text into the prompt. The hook is also silent for a headless ralph iteration (`TASK_ORCHESTRATOR_MODE=headless-iteration`).

**Orchestrator:**
- fills queue notes during planning and dispatches with the item id;
- reads the profile for the phase being dispatched INTO: the `dispatch` of the `advance` result once the transition is made, or `items_read` `schema` (`dispatch`, `dispatchBySeat`) when the agent will enter the phase itself; `items_read` `context` returns only the current role's profile. Pass `subagent_type = dispatch.agent` when set, and always pass `model` explicitly;
- after agents return, advances with `start` and checks `newRole`: `review` means dispatch reviewers or review inline, `terminal` means a schema without review notes;
- performs the review-to-terminal transition after the verdict.

**Implementer (entry seat):** the seat the schema marks `enters` calls `start` once to enter work, fills work notes with the loop above, returns, and never calls `advance` again.

**Reviewer and other non-entry seats:** a reviewer is dispatched into an item already in review. It must NOT call `start` (from review that lands terminal). It reads `items_read` `context`, fills the review notes, reports the verdict and returns. Test authors, extractors and read-only seats never call `advance`.

**Invariant:** only an entry seat moves its item, and only into its own phase; the orchestrator owns every other transition.

### Pre-dispatch recipe: a seat at a parent

Before dispatching a seat (a feature reviewer, a closing step) at a parent, read the parent and branch on its role:

```to-call
{"tool": "items_read", "select": "context", "args": {"itemId": "98d452e9"}}
```

| Parent role | Action |
|---|---|
| queue | run the parent's own check; `start` it when `canAdvance` |
| work, `canAdvance` true | `start` to move it to review, then dispatch |
| work, `canAdvance` false | fill the orchestrator-owned missing notes; surface the seat-owned ones (`missingBySeat`) to their seats |
| review | dispatch |
| no review phase in its schema | never `start`: it lands terminal. Decide whether the parent needs a seat at all |

Never dispatch a reviewer at a work-role parent. Note that the terminal cascade from the last child into a review-role parent is suppressed (`cascades[]` shows `applied: false`, review-pending), so a review parent needs an explicit `complete`, or its review and then `start`.

## Creating a schema item

Set `type` to a declared schema key (`config` `effective` `types`; a wrong one fails `schema_violation` with `data.results[0].error.detail.availableTypes`):

```to-call
{"tool": "items_write", "op": "create", "args": {"title": "Add retry layer", "type": "feature-task", "priority": "medium", "traits": ["needs-docs-accuracy-review"]}}
```

The answer is `{ok, id, version}`; read `items_read` `schema` for the notes and start filling queue notes, then follow the loop. See `/create-item` for anchoring.

## Error recovery

- **Gate rejection:** `gate_blocked` lists the missing keys; fill and retry.
- **Resource contention:** `resource_unavailable` is transient and distinct from a gate; wait (`retryAfterMs` is a hint) or work another item.
- **Blocked by dependency:** `dependency_unmet`; use `items_read` `blocked` or `deps_read` `list` to find the blocker.
- **Stale pin:** an item keeps the schema it was pinned to. `items_read` `get` shows `stale: true` after a later config push; `repin` adopts the push. Changing `type` or `traits` is itself a pin point.
