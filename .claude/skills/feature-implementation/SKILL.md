---
name: feature-implementation
description: "Guides the full lifecycle of a feature-implementation typed MCP item (the feature container) — from queue through review. Creates or resumes the feature container, fills gate-enforced notes at each phase (feature-summary, implementation-notes, session-tracking, review-checklist), dispatches implementation subagents, and advances through queue, work, and review to terminal. Use when the user says: implement a feature, start a new feature, feature workflow, resume feature work, guide feature lifecycle, or references a feature-implementation item UUID."
---

# Feature Implementation Workflow

End-to-end workflow for a `feature-implementation` typed item — the **feature container**
that holds the plan and holistic review. Child work items under this container use the
`feature-task` type with lighter gates (`task-scope` instead of the feature-level
`feature-summary`, and no review phase by default — task-level review is opt-in via the
`needs-task-review` trait, which adds a `review-checklist` note to that child). A second
child-level trait, `needs-test-author`, is opt-in the same way (applied per the
`/implement` Step 1 trigger rule) and adds its own gate-enforced notes.

Covers all three phases (queue → work → review) with gate-enforced notes at each transition.

Call shapes are in [`claude-plugins/task-orchestrator/skills/_generated/call-shapes.md`](../../../claude-plugins/task-orchestrator/skills/_generated/call-shapes.md); field semantics are in `current/docs/api-reference.md`. Ids in examples are 8-hex shorts; any id field also takes the full UUID. Read every answer through the envelope: `ok`, then `data.results[i]` (`ok` or `error`). When a write element fails, the call-level `error` is only `partial_failure` (no detail, no fix): read `code`, `kind`, `detail` and `fix` from `data.results[i].error`. A read (`select`) call reports its failure in the call-level `error`. When the dispatch names a seat token, pass it as the top-level `seatToken` on every call, reads included, and never print or store it.

**Usage:** `/feature-implementation [item-uuid]`

- If `item-uuid` is provided: load the existing item and resume from its current phase
- If omitted: create a new item and start from the beginning

---

## Phase 0 — Setup

If an item UUID was provided, call:
```to-call
{"tool":"items_read","select":"context","args":{"itemId":"4b7d0e19"}}
```
Check `gateStatus.canAdvance` and `gateStatus.missing` to determine which phase the item is in,
then jump to the appropriate phase below.

If no UUID was provided, create the item:
```to-call
{"tool":"items_write","op":"create","args":{"title":"<feature title>","type":"feature-implementation","priority":"medium"}}
```
Note the returned `id`, and read the gate notes with `items_read` `schema`. Confirm the item is in `queue` role,
then continue to Phase 1.

---

## Phase 1 — Queue: Define the Feature Summary

**Goal:** Fill the single required queue-phase note before advancing to work.

### 1a. Fill `feature-summary`

Use `notes_write` `upsert` for the `feature-summary` note:
```to-call
{"tool":"notes_write","op":"upsert","args":{"itemId":"4b7d0e19","key":"feature-summary","role":"queue","body":"<content>"}}
```

**What to write:** Keep this note lean (target under 2k chars) — it stays at the feature
level, not the child-task level where the full spec-quality disciplines apply. Cover:
- **Goal** — the problem this solves and who benefits.
- **Findings → tasks table** — a table mapping each finding or requirement to the child
  task that will address it.
- **Dependency edges** — ordering constraints between child tasks, if any.
- **Non-goals pointer** — a reference to where non-goals are documented per child (each
  child's `task-scope` note carries its own alternatives/non-goals/blast-radius/risk/test
  strategy per the spec-quality framework); this note does not repeat that analysis.

### 1b. Enter plan mode

After filling the note, use `EnterPlanMode` to explore the codebase and produce a concrete
implementation plan. The `pre-plan` hook will inject additional guidance.

When the plan is approved, `post-plan` hook fires — proceed directly to Phase 2 without pausing.

### 1c. Advance to work

```to-call
{"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"start"}}
```

Gate check: `feature-summary` must be filled. If the element fails `gate_blocked`, fill the notes in `data.results[0].error.detail.missing[]` and retry.
If it instead fails `resource_unavailable` (`kind: transient`), this
is resource-lease contention, not a note gate failure — see Quick Reference below.

Confirm `newRole: "work"` in `data.results[0]` before dispatching implementation subagents.

---

## Phase 2 — Work: Implement and Document

**Goal:** Delegate implementation, then fill work-phase notes before advancing to review.

### 2a. Materialize any child items

If the feature has sub-tasks, create them now using `items_write` `create_tree` in attach mode
(`root: {id: <feature UUID>}` plus `children[]` of type `feature-task`), or one `items_write`
`create` per child with `parentId` = the feature. Never pass the feature as the `create_tree`
`parentId`. Each child fills a `task-scope` queue note (the
full spec-quality disciplines — alternatives, non-goals, blast radius, risk flags, test
strategy — apply there, not in the feature-level `feature-summary`). Dispatch
implementation subagents with each child item UUID.

Each subagent must:
- Call `advance` `transition` with `trigger: "start"` on their item to enter work phase (only the entry seat does; a hand-dispatch outside a run carries no seat token)
- Fill its own work-phase notes (`implementation-notes`) following the JIT progression loop
  (`items_read` `context` returns the first missing note's `guidanceKey` and `skillPointer`;
  read guidance text with `items_read` `schema`, `fields: ["guidance"]`)
- Return to the orchestrator — do NOT call `advance` again. The orchestrator
  handles all further transitions.

By default, `feature-task` items have no review phase — `advance` `transition` `start`
from work goes straight to terminal. A child only gets a review phase if it (or its
schema's `default_traits`) carries the `needs-task-review` trait, which adds the
`review-checklist` note back in, or the `needs-test-author` trait, which adds
`test-independence-audit`.

### 2b. Fill `implementation-notes`

After implementation agents return:
```to-call
{"tool":"notes_write","op":"upsert","args":{"itemId":"4b7d0e19","key":"implementation-notes","role":"work","body":"<content>"}}
```

**What to write:** Key decisions made during implementation. Deviations from the plan.
Any surprises (wrong class names, API differences, test isolation issues). Files changed with
line counts. If an observation was fixed, reference its item ID.

### 2c. Fill `session-tracking`

```to-call
{"tool":"notes_write","op":"upsert","args":{"itemId":"4b7d0e19","key":"session-tracking","role":"work","body":"<content>"}}
```

**What to write:** Run `./gradlew :current:test` ONCE here, incrementally (no
`--rerun-tasks`) — this is the orchestrator's single whole-suite run for the item; no seat
ran it before you and the reviewer will not run it after. Report total count and any
failures, new test classes or cases added, and a summary of what changed this session. This
note comes from the `session-tracked` default trait and feeds `/session-retrospective`.

### 2d. Advance to review

```to-call
{"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"start"}}
```

Gate check: both `implementation-notes` and `session-tracking` must be filled.
Confirm `newRole: "review"` in `data.results[0]`.

---

## Phase 3 — Review: Verify, Deploy, and Close

**Goal:** Get the required `review-checklist` note filled by an independent reviewer, deploy
and verify in the running MCP server, then close the item.

### 3a. Dispatch one independent reviewer that fills `review-checklist`

The orchestrator does not write `review-checklist`. Dispatch one independent
`task-orchestrator:reviewer` (explicit `model`) over the feature range, handed each child's
own `review-checklist` if the `needs-task-review` trait produced one. The reviewer follows
the `review-quality` skill's framework - plan alignment against `feature-summary` and each
child's `task-scope`, test quality, and (if `/simplify` ran) test coverage of its changes -
and fills the feature's `review-checklist` with a feature-level verdict aggregating across
children (see review-quality for the findings format and verdict types: Pass / Fail -
blocking issues / Pass with observations). Record the orchestrator's dispositions in
`orchestrator-confirmation`.

### 3b. Deploy to Docker (if needed)

Run `/deploy_to_docker` (Reuse/Reconfigure chooser) or `/deploy_to_docker --reuse` to rebuild the image with the new code.
Reconnect MCP after deploy: `/mcp`.

### 3c. Smoke test the change

Exercise the new capability through the 4.0 tools. Confirm it behaves as described in the
`feature-summary` note's goal.

### 3d. Fill `deploy-notes` (optional)

```to-call
{"tool":"notes_write","op":"upsert","args":{"itemId":"4b7d0e19","key":"deploy-notes","role":"review","body":"<content>"}}
```

**What to write:** Whether a Docker rebuild was done and what image tag was used.
Plugin version bump (if any). MCP reconnect required. Any smoke test results.

### 3e. Close the item

```to-call
{"tool":"advance","op":"transition","args":{"itemId":"4b7d0e19","trigger":"start","summary":"<one-line summary>"}}
```

Confirm `newRole: "terminal"`. Run the `items_read` `context` health view (no `itemId`) to verify no stalled items.

---

## Quick Reference

| Phase | Required notes | Advance trigger |
|-------|---------------|-----------------|
| queue | `feature-summary` | `start` → work |
| work | `implementation-notes`, `session-tracking` | `start` → review |
| review | `review-checklist` | `start` → terminal |

**Gate error pattern:** `gate_blocked` with `detail.missing[]` naming the unfilled notes
→ Fill the listed notes, then retry `advance` `transition`.

**Resource-lease contention pattern:** the element fails `resource_unavailable` (`kind: transient`,
`detail.resources[]`, `retryAfterMs`) on a `start` into work — a different
failure mode than the gate error above. Do NOT fill notes or spin-retry; the resource is held by
another item. Work a different item and retry later, or report contention upstream.
