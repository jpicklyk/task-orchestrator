---
name: feature-implementation
description: "Guides the full lifecycle of a feature-implementation tagged MCP item (the feature container) — from queue through review. Creates or resumes the feature container, fills gate-enforced notes at each phase (feature-summary, implementation-notes, session-tracking, review-checklist), dispatches implementation subagents, and advances through queue, work, and review to terminal. Use when the user says: implement a feature, start a new feature, feature workflow, resume feature work, guide feature lifecycle, or references a feature-implementation item UUID."
---

# Feature Implementation Workflow

End-to-end workflow for a `feature-implementation` tagged item — the **feature container**
that holds the plan and holistic review. Child work items under this container use the
`feature-task` tag with lighter gates (`task-scope` instead of the feature-level
`feature-summary`, and no review phase by default — task-level review is opt-in via the
`needs-task-review` trait, which adds a `review-checklist` note to that child).

Covers all three phases (queue → work → review) with gate-enforced notes at each transition.

**Usage:** `/feature-implementation [item-uuid]`

- If `item-uuid` is provided: load the existing item and resume from its current phase
- If omitted: create a new item and start from the beginning

---

## Phase 0 — Setup

If an item UUID was provided, call:
```
get_context(itemId="<uuid>")
```
Check `canAdvance` and `missingRequiredNotes` to determine which phase the item is in,
then jump to the appropriate phase below.

If no UUID was provided, create the item:
```
manage_items(
  operation="create",
  items=[{ title: "<feature title>", tags: "feature-implementation", priority: "medium" }]
)
```
Note the returned UUID and `expectedNotes` list. Confirm the item is in `queue` role,
then continue to Phase 1.

---

## Phase 1 — Queue: Define the Feature Summary

**Goal:** Fill the single required queue-phase note before advancing to work.

### 1a. Fill `feature-summary`

Use `manage_notes` to upsert the `feature-summary` note:
```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "feature-summary",
    role: "queue",
    body: "<content>"
  }]
)
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

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Gate check: `feature-summary` must be filled. If gate rejects, fill the missing note and retry.

Confirm `newRole: "work"` in the response before dispatching implementation subagents.

---

## Phase 2 — Work: Implement and Document

**Goal:** Delegate implementation, then fill work-phase notes before advancing to review.

### 2a. Materialize any child items

If the feature has sub-tasks, create them now using `create_work_tree` with the feature UUID
as `parentId` and the `feature-task` tag. Each child fills a `task-scope` queue note (the
full spec-quality disciplines — alternatives, non-goals, blast radius, risk flags, test
strategy — apply there, not in the feature-level `feature-summary`). Dispatch
implementation subagents with each child item UUID.

Each subagent must:
- Call `advance_item(trigger="start")` on their item to enter work phase
- Fill work-phase notes following the JIT progression loop (the subagent-start hook
  provides guidance via `guidancePointer` and `skillPointer`)
- Return to the orchestrator — do NOT call `advance_item` again. The orchestrator
  handles all further transitions.

By default, `feature-task` items have no review phase — `advance_item(trigger="start")`
from work goes straight to terminal. A child only gets a review phase if it (or its
schema's `default_traits`) carries the `needs-task-review` trait, which adds the
`review-checklist` note back in.

### 2b. Fill `implementation-notes`

After implementation agents return:
```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "implementation-notes",
    role: "work",
    body: "<content>"
  }]
)
```

**What to write:** Key decisions made during implementation. Deviations from the plan.
Any surprises (wrong class names, API differences, test isolation issues). Files changed with
line counts. If an observation was fixed, reference its item ID.

### 2c. Fill `session-tracking`

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "session-tracking",
    role: "work",
    body: "<content>"
  }]
)
```

**What to write:** Run `./gradlew :current:test`. Report total count and any failures,
new test classes or cases added, and a summary of what changed this session. This note
comes from the `session-tracked` default trait and feeds `/session-retrospective`.

### 2d. Advance to review

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Gate check: both `implementation-notes` and `session-tracking` must be filled.
Confirm `newRole: "review"` in the response.

---

## Phase 3 — Review: Verify, Deploy, and Close

**Goal:** Fill the required `review-checklist` note (per the review-quality skill), deploy
and verify in the running MCP server, then close the item.

### 3a. Fill `review-checklist`

Follow the `review-quality` skill's framework — plan alignment against `feature-summary`
and each child's `task-scope`, test quality, and (if `/simplify` ran) test coverage of its
changes.

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "review-checklist",
    role: "review",
    body: "<content>"
  }]
)
```

**What to write:** A feature-level verdict aggregating across children — reference each
child's own review-checklist if the `needs-task-review` trait produced one. See the
review-quality skill for the full findings format and verdict types (Pass / Fail —
blocking issues / Pass with observations).

### 3b. Deploy to Docker (if needed)

Run `/deploy_to_docker --current` to rebuild the image with the new code.
Reconnect MCP after deploy: `/mcp`.

### 3c. Smoke test the change

Exercise the new capability via MCP tool calls. Confirm it behaves as described in the
`feature-summary` note's goal.

### 3d. Fill `deploy-notes` (optional)

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "deploy-notes",
    role: "review",
    body: "<content>"
  }]
)
```

**What to write:** Whether a Docker rebuild was done and what image tag was used.
Plugin version bump (if any). MCP reconnect required. Any smoke test results.

### 3e. Close the item

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start", summary: "<one-line summary>" }])
```

Confirm `newRole: "terminal"`. Run `get_context()` health check to verify no stalled items.

---

## Quick Reference

| Phase | Required notes | Advance trigger |
|-------|---------------|-----------------|
| queue | `feature-summary` | `start` → work |
| work | `implementation-notes`, `session-tracking` | `start` → review |
| review | `review-checklist` | `start` → terminal |

**Gate error pattern:** `"required notes not filled for <phase> phase: <keys>"`
→ Fill the listed notes, then retry `advance_item`.
