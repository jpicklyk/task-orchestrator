# v3 MCP Task Orchestrator — Workflow Guide

This guide covers the role-based workflow, note schema system, dependency blocking, and efficient query patterns for v3 MCP Task Orchestrator.

---

## 1. Role-Based Workflow

Every WorkItem moves through a set of lifecycle phases called **roles**. Roles are semantic — they describe where an item is in the workflow, independent of any specific status label.

### The Five Roles

| Role       | Meaning                                                      |
|------------|--------------------------------------------------------------|
| `queue`    | Pending, not yet started. Default role at creation.          |
| `work`     | Actively being worked on.                                    |
| `review`   | Work complete, undergoing verification or review.            |
| `terminal` | Finished. No further transitions possible (unless cancelled).|
| `blocked`  | Paused due to an unresolved dependency or explicit hold.     |

### Standard Flow

```
queue  -->  work  -->  review  -->  terminal
              |                         ^
              +--------- (no review) ---+
              |
          (hold/block)
              |
           blocked  --> (resume) --> previous role
```

When an item has no review-phase notes defined in its schema, `start` from `work` advances directly to `terminal`, skipping the `review` phase.

---

## 2. Transitions Reference

All role transitions use `advance_item(trigger=...)`. There is no direct role assignment.

### Trigger Table

| Trigger    | From Role(s)              | To Role                  | Notes                                              |
|------------|---------------------------|--------------------------|----------------------------------------------------|
| `start`    | `queue`                   | `work`                   | Queue-phase required notes must be filled.         |
| `start`    | `work`                    | `review` or `terminal`   | Work-phase required notes must be filled.          |
| `start`    | `review`                  | `terminal`               | Review-phase required notes must be filled.        |
| `complete` | Any non-terminal          | `terminal`               | Enforces gates: all required notes across ALL phases must be filled. |
| `block`    | Any non-terminal          | `blocked`                | Saves `previousRole` for resume.                   |
| `hold`     | Any non-terminal          | `blocked`                | Alias for `block`.                                 |
| `resume`   | `blocked`                 | Previous role            | Restores role saved at block time.                 |
| `cancel`   | Any non-terminal          | `terminal`               | Sets `statusLabel = "cancelled"`.                  |

### Example: Advance a Work Item

```json
advance_item(transitions=[
  { "itemId": "abc-123", "trigger": "start" }
])
```

### Example: Batch Transitions

```json
advance_item(transitions=[
  { "itemId": "abc-123", "trigger": "complete", "summary": "All tests passed" },
  { "itemId": "def-456", "trigger": "start" }
])
```

---

## 3. Note Schemas

Note schemas gate role transitions. They define what documentation an item must carry before it can advance to the next phase.

### What They Are

A note schema is a named set of note definitions attached to an item via a **tag**. When an item's tag matches a schema key, the system enforces note requirements at `start` transitions.

Schemas are defined in `.taskorchestrator/config.yaml` in the project root.

### How They Gate Transitions

- `advance_item(trigger="start")` checks required notes for the **current phase** before advancing.
- `advance_item(trigger="complete")` checks all required notes across all phases.
- `advance_item(trigger="cancel")` does not enforce gates.
- Optional notes never block transitions.

Gate enforcement is tag-based: only items whose tags match a schema key are gated.

### Full YAML Schema Format

```yaml
note_schemas:
  <schema-key>:
    - key: <note-key>
      role: <queue|work|review>
      required: <true|false>
      description: "<Short description of what this note should contain.>"
      guidance: "<Optional longer guidance shown to agents filling the note.>"
```

#### Field Reference

| Field         | Type    | Required | Description                                                                          |
|---------------|---------|----------|--------------------------------------------------------------------------------------|
| `key`         | string  | yes      | Unique identifier for this note within the schema. Used in `manage_notes`.           |
| `role`        | string  | yes      | Phase this note belongs to: `queue`, `work`, or `review`.                            |
| `required`    | boolean | yes      | Whether this note must be filled before advancing past this phase.                   |
| `description` | string  | yes      | Short description of expected content. Shown in `get_context` gate status.           |
| `guidance`    | string  | no       | Longer authoring guidance for agents. Shown in `get_context` as `guidancePointer`.   |

#### Constraints

- `key` values must be unique within a schema.
- The same `key` may appear in multiple schemas (schemas are independent).
- First matching tag wins — each item matches at most one schema.
- Schemas with no matching item tags have no effect.

### Complete Example Schema

```yaml
note_schemas:
  feature-implementation:
    - key: requirements
      role: queue
      required: true
      description: "Problem statement and acceptance criteria."
      guidance: "Describe what problem this solves. List 2-5 acceptance criteria."
    - key: design
      role: queue
      required: true
      description: "Chosen approach, alternatives considered."
    - key: implementation-notes
      role: work
      required: true
      description: "Key decisions made during implementation."
    - key: test-results
      role: work
      required: true
      description: "Test pass/fail count and new tests added."
    - key: deploy-notes
      role: review
      required: false
      description: "Deploy needed? Version bump? Reconnect required?"
```

### Phase Flow with Gates

```
queue
  requires: requirements (filled), design (filled)
      |
   [start]
      |
    work
  requires: implementation-notes (filled), test-results (filled)
      |
   [start]
      |
   review
  requires: (no required notes in this example)
      |
   [start]
      |
  terminal
```

---

## 4. Writing Notes

Notes are created or updated using `manage_notes(operation="upsert")`.

### Upsert a Note

```json
manage_notes(operation="upsert", notes=[
  {
    "itemId": "abc-123",
    "key": "requirements",
    "role": "queue",
    "body": "## Problem\nUsers cannot reset passwords via email.\n\n## Acceptance Criteria\n- User receives reset email within 60s\n- Link expires after 24h\n- Old password no longer works after reset"
  }
])
```

`(itemId, key)` is unique — upserting an existing `(itemId, key)` pair updates it in place.

### Checking Gate Status Before Advancing

Use `get_context(itemId=...)` to inspect the current gate status before calling `advance_item`.

```json
get_context(itemId="abc-123")
```

Response includes:

```json
{
  "item": { "id": "abc-123", "title": "...", "role": "queue" },
  "gateStatus": {
    "canAdvance": false,
    "phase": "queue",
    "missing": ["design"]
  },
  "schema": [
    { "key": "requirements", "role": "queue", "required": true, "description": "...", "exists": true, "filled": true },
    { "key": "design", "role": "queue", "required": true, "description": "...", "exists": false, "filled": false }
  ],
  "guidancePointer": "Describe chosen approach and alternatives considered."
}
```

When `canAdvance: true`, calling `advance_item(trigger="start")` will succeed.

### Reading Existing Notes

```json
manage_notes(operation="list", itemId="abc-123")
```

---

## 5. Filling Notes Workflow — Step by Step

This walkthrough covers a complete lifecycle for a `feature-implementation` tagged item.

**Step 1: Create the item**

```json
manage_items(operation="create", items=[
  {
    "title": "Password Reset Feature",
    "tags": "feature-implementation",
    "priority": "high"
  }
])
```

The response includes `expectedNotes` when the tag matches a schema:

```json
{
  "id": "abc-123",
  "title": "Password Reset Feature",
  "role": "queue",
  "tags": "feature-implementation",
  "expectedNotes": [
    { "key": "requirements", "role": "queue", "required": true, "exists": false },
    { "key": "design",        "role": "queue", "required": true, "exists": false },
    { "key": "implementation-notes", "role": "work", "required": true, "exists": false },
    { "key": "test-results",  "role": "work", "required": true, "exists": false },
    { "key": "deploy-notes",  "role": "review", "required": false, "exists": false }
  ]
}
```

**Step 2: Fill queue-phase notes**

```json
manage_notes(operation="upsert", notes=[
  {
    "itemId": "abc-123",
    "key": "requirements",
    "role": "queue",
    "body": "Users need to reset passwords by email.\n\nAcceptance Criteria:\n- Reset email delivered < 60s\n- Link expires after 24h"
  },
  {
    "itemId": "abc-123",
    "key": "design",
    "role": "queue",
    "body": "Use HMAC token stored in DB. Chose over JWT to allow server-side invalidation."
  }
])
```

**Step 3: Verify gate, then advance to work**

```json
get_context(itemId="abc-123")
// gateStatus.canAdvance: true

advance_item(transitions=[{ "itemId": "abc-123", "trigger": "start" }])
// item is now role: work
```

**Step 4: Fill work-phase notes**

```json
manage_notes(operation="upsert", notes=[
  {
    "itemId": "abc-123",
    "key": "implementation-notes",
    "role": "work",
    "body": "Added PasswordResetTokenRepository. Token TTL configurable via env var."
  },
  {
    "itemId": "abc-123",
    "key": "test-results",
    "role": "work",
    "body": "42 tests passing, 0 failing. Added 8 new tests for token expiry edge cases."
  }
])
```

**Step 5: Advance to review**

```json
advance_item(transitions=[{ "itemId": "abc-123", "trigger": "start" }])
// item is now role: review
```

**Step 6: Advance to terminal**

```json
advance_item(transitions=[{ "itemId": "abc-123", "trigger": "start" }])
// item is now role: terminal
```

---

## 6. Dependency Blocking

### BLOCKS Edges

A BLOCKS edge between item A and item B means: **B cannot start until A reaches terminal** (by default).

```json
manage_dependencies(operation="create", dependencies=[
  { "fromItemId": "abc-123", "toItemId": "def-456", "type": "BLOCKS" }
])
```

`advance_item(trigger="start")` on a blocked item will fail with a gate error listing the blocking items.

### Pattern Shortcuts

Create a linear chain in one call:

```json
manage_dependencies(operation="create", pattern="linear", itemIds=["task-a", "task-b", "task-c"])
// task-a BLOCKS task-b, task-b BLOCKS task-c
```

Fan-out (one blocker, many dependents):

```json
manage_dependencies(operation="create", pattern="fan-out", source="task-a", targets=["task-b", "task-c", "task-d"])
```

Fan-in (many blockers, one dependent):

```json
manage_dependencies(operation="create", pattern="fan-in", sources=["task-a", "task-b"], target="task-c")
```

### `unblockAt` Threshold

By default a dependency is satisfied when the blocking item reaches `terminal`. Use `unblockAt` to satisfy earlier:

| Value      | Satisfied When Blocker Reaches |
|------------|-------------------------------|
| `queue`    | Any role (immediately)         |
| `work`     | `work`, `review`, or `terminal`|
| `review`   | `review` or `terminal`         |
| `terminal` | `terminal` (default)           |

```json
manage_dependencies(operation="create", dependencies=[
  { "fromItemId": "abc-123", "toItemId": "def-456", "type": "BLOCKS", "unblockAt": "review" }
])
```

### Finding Blocked Items

```json
get_blocked_items()
```

Returns items blocked by unsatisfied dependencies and items explicitly in `blocked` role. Optionally scope to a subtree:

```json
get_blocked_items(parentId="feature-uuid", includeAncestors=true)
```

### Unblock Reporting in `advance_item`

When completing a blocking item, the response includes `unblockedItems`:

```json
{
  "results": [{
    "itemId": "abc-123",
    "previousRole": "work",
    "newRole": "terminal",
    "trigger": "start",
    "applied": true,
    "unblockedItems": [
      { "itemId": "def-456", "title": "Downstream Task" }
    ]
  }]
}
```

Items in `unblockedItems` are now eligible to be started.

---

## 7. Efficient Queries

### Role-Based Filtering

`query_items(operation="search")` supports filtering by semantic role across all status names:

```json
query_items(operation="search", role="work")
// Returns all items currently in the work phase
```

```json
query_items(operation="search", role="blocked", parentId="feature-uuid")
// Returns blocked items under a specific feature
```

### Two-Call Work Summary

Get a complete picture of active work with zero follow-up calls:

```json
get_context(includeAncestors=true)
```

Returns active items (work/review) and blocked items, each with a full ancestor chain:

```json
{
  "activeItems": [
    {
      "id": "abc-123",
      "title": "Implement reset endpoint",
      "role": "work",
      "ancestors": [
        { "id": "proj-001", "title": "Auth System", "depth": 0 },
        { "id": "feat-042", "title": "Password Reset Feature", "depth": 1 }
      ]
    }
  ]
}
```

Then get container-level counts:

```json
query_items(operation="overview")
```

**Total: 2 calls. No sequential parent-walk needed.**

### `includeAncestors`

Available on `query_items(get)`, `query_items(search)`, `get_context`, `get_blocked_items`, and `get_next_item`.

Each item gets an `ancestors` array ordered root-first (direct parent last). Root items get `[]`.

```json
query_items(operation="search", role="work", includeAncestors=true)
```

### Next Item Recommendation

```json
get_next_item(limit=3, includeAncestors=true)
```

Returns unblocked queue items ranked by priority (high first) then complexity (low first — quick wins first).

Scope to a subtree:

```json
get_next_item(parentId="feature-uuid")
```

---

## 8. Config Reference

The full schema for `.taskorchestrator/config.yaml`:

### Top-Level Structure

```yaml
note_schemas:
  <schema-key>: <note-list>
```

Additional top-level keys (workflows, status, cascade) are supported but not covered in this guide.

### Note Schema Entry Fields

| Field         | Type    | Required | Allowed Values            | Description                                                        |
|---------------|---------|----------|---------------------------|--------------------------------------------------------------------|
| `key`         | string  | yes      | Any non-empty string      | Note identifier. Must be unique within the schema.                 |
| `role`        | string  | yes      | `queue`, `work`, `review` | Phase gate this note belongs to.                                   |
| `required`    | boolean | yes      | `true`, `false`           | If true, must be filled before `start` advances past this phase.   |
| `description` | string  | yes      | Any string                | Short description. Shown in `get_context` gate status output.      |
| `guidance`    | string  | no       | Any string                | Longer authoring hint. Shown as `guidancePointer` in gate status.  |

### Matching Rules

1. An item's `tags` field is checked against all schema keys.
2. First matching tag wins.
3. Tags are matched as exact substrings within the comma-separated tags string.
4. Items with no matching tag are ungated — they advance freely.

### Minimal Config Example

```yaml
note_schemas:
  task-implementation:
    - key: acceptance-criteria
      role: queue
      required: true
      description: "Testable acceptance criteria for this task."
    - key: done-criteria
      role: work
      required: true
      description: "What does done look like? How was it verified?"
```

### Config Location

- Default: `.taskorchestrator/config.yaml` relative to the working directory.
- Docker override: set `AGENT_CONFIG_DIR` environment variable to the directory containing `.taskorchestrator/`.

```bash
docker run -e AGENT_CONFIG_DIR=/project -v "$(pwd)":/project:ro task-orchestrator:dev
```

---

## Quick Reference

### Common Call Patterns

| Goal                              | Tool Call                                                       |
|-----------------------------------|-----------------------------------------------------------------|
| Check what to work on next        | `get_next_item(includeAncestors=true)`                          |
| See all active work               | `get_context(includeAncestors=true)`                            |
| See container overview            | `query_items(operation="overview")`                             |
| Check gate before advancing       | `get_context(itemId="uuid")`                                    |
| Advance to next phase             | `advance_item(transitions=[{itemId, trigger:"start"}])`         |
| Fill a note                       | `manage_notes(operation="upsert", notes=[{itemId, key, role, body}])` |
| Find blocked items                | `get_blocked_items(includeAncestors=true)`                      |
| Create a dependency chain         | `manage_dependencies(operation="create", pattern="linear", itemIds=[...])` |
| Cancel an item                    | `advance_item(transitions=[{itemId, trigger:"cancel"}])`        |
| Filter by phase                   | `query_items(operation="search", role="work")`                  |
