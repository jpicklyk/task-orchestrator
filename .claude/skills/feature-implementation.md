---
description: Guide the full lifecycle of a feature-implementation tagged MCP item — from queue through review
---

# Feature Implementation Workflow

End-to-end workflow for a `feature-implementation` tagged item. Covers all three phases
(queue → work → review) with gate-enforced notes at each transition.

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

## Phase 1 — Queue: Define Requirements and Design

**Goal:** Fill the two required queue-phase notes before advancing to work.

### 1a. Fill `requirements`

Use `manage_notes` to upsert the `requirements` note:
```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "requirements",
    role: "queue",
    body: "<content>"
  }]
)
```

**What to write:** The problem this solves. Who benefits. 2–5 concrete acceptance criteria
that define done. Reference any existing observations or bug items that motivated this feature.

### 1b. Fill `design`

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "design",
    role: "queue",
    body: "<content>"
  }]
)
```

**What to write:** Chosen approach. Alternatives considered and why they were ruled out.
Key risks or constraints (schema migrations, tight coupling areas, ORM quirks, test isolation issues).
Reference specific files and classes that will be touched.

### 1c. Enter plan mode

After filling both notes, use `EnterPlanMode` to explore the codebase and produce a concrete
implementation plan. The `pre-plan` hook will inject additional guidance.

When the plan is approved, `post-plan` hook fires — proceed directly to Phase 2 without pausing.

### 1d. Advance to work

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Gate check: both `requirements` and `design` must be filled. If gate rejects, fill the missing
notes and retry.

Confirm `newRole: "work"` in the response before dispatching implementation subagents.

---

## Phase 2 — Work: Implement and Document

**Goal:** Delegate implementation, then fill work-phase notes before advancing to review.

### 2a. Materialize any child items

If the feature has sub-tasks, create them now using `create_work_tree` with the feature UUID
as `parentId`. Dispatch implementation subagents with each child item UUID.

Each subagent must:
- Call `advance_item(trigger="start")` on their item at the start
- Call `advance_item(trigger="complete")` on their item when done

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

**What to write:** Key decisions made during implementation. Deviations from the design note.
Any surprises (wrong class names, API differences, test isolation issues). Files changed with
line counts. If an observation was fixed, reference its item ID.

### 2c. Fill `test-results`

```
manage_notes(
  operation="upsert",
  notes=[{
    itemId: "<uuid>",
    key: "test-results",
    role: "work",
    body: "<content>"
  }]
)
```

**What to write:** Run `./gradlew :current:test`. Report total count and any failures.
List new test classes or cases added. If root-module tests were affected, report those too.

### 2d. Advance to review

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start" }])
```

Gate check: both `implementation-notes` and `test-results` must be filled.
Confirm `newRole: "review"` in the response.

---

## Phase 3 — Review: Deploy and Verify

**Goal:** Deploy, verify in the running MCP server, then close the item.

### 3a. Deploy to Docker (if needed)

Run `/deploy_to_docker --current` to rebuild the image with the new code.
Reconnect MCP after deploy: `/mcp`.

### 3b. Smoke test the change

Exercise the new capability via MCP tool calls. Confirm it behaves as described in the
`requirements` note acceptance criteria.

### 3c. Fill `deploy-notes` (optional)

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

### 3d. Close the item

```
advance_item(transitions=[{ itemId: "<uuid>", trigger: "start", summary: "<one-line summary>" }])
```

Confirm `newRole: "terminal"`. Run `get_context()` health check to verify no stalled items.

---

## Quick Reference

| Phase | Required notes | Advance trigger |
|-------|---------------|-----------------|
| queue | `requirements`, `design` | `start` → work |
| work | `implementation-notes`, `test-results` | `start` → review |
| review | _(none required)_ | `start` → terminal |

**Gate error pattern:** `"required notes not filled for <phase> phase: <keys>"`
→ Fill the listed notes, then retry `advance_item`.
