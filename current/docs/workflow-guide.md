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
| `terminal` | Finished. Use `reopen` to move back to queue if needed.      |
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
| `reopen`   | `terminal`                | `queue`                  | Clears statusLabel, bypasses gates. Parent cascades TERMINAL → WORK. |

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

A note schema is a named set of note definitions. When an item matches a schema, the system enforces note requirements at `start` transitions.

**Schema resolution order:**
1. **Type-first lookup** — the item's `type` field (e.g., `feature`, `task`, `bug`) is looked up directly in `work_item_schemas`. If a match is found, that schema is used.
2. **Tag fallback** — if no type match, the item's tags are checked against schema keys. The first matching tag wins.
3. **Default schema** — if neither type nor tags match, the `default` schema is used (if configured). Items with no match at all advance freely with no gate enforcement.

Schemas are defined in `.taskorchestrator/config.yaml` in the project root.

> **Schemas vs notes:** Schemas and notes are independent systems. A schema is a set of rules defined in your project config that specifies what documentation an item must carry — it is never stored in the database. A note is actual content written by agents during implementation — it is stored in the database and carries no reference to any schema. The two meet only at gate-check time: `advance_item` fetches the item's notes from the database and checks them against the schema's requirements.

### How They Gate Transitions

- `advance_item(trigger="start")` checks required notes for the **current phase** before advancing.
- `advance_item(trigger="complete")` checks all required notes across all phases.
- `advance_item(trigger="cancel")` does not enforce gates.
- Optional notes never block transitions.

Gate enforcement applies to items that resolve a schema via type, tag, or default fallback.

### Full YAML Schema Format

The preferred format uses `work_item_schemas:`, which supports a `lifecycle:` field for cascade control. The legacy `note_schemas:` format is still accepted and fully backward-compatible.

```yaml
# Preferred format
work_item_schemas:
  <schema-key>:
    lifecycle: <AUTO|MANUAL|AUTO_REOPEN|PERMANENT>   # optional, default: AUTO
    notes:
      - key: <note-key>
        role: <queue|work|review>
        required: <true|false>
        description: "<Short description of what this note should contain.>"
        guidance: "<Optional longer guidance shown to agents filling the note.>"
    traits:                    # optional — composable trait keys applied to this schema
      - <trait-key>

# Legacy format (still works)
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
| `skill`       | string  | no       | Skill to invoke when filling this note. Shown in `get_context` as `skillPointer`.    |

#### Constraints

- `key` values must be unique within a schema.
- The same `key` may appear in multiple schemas (schemas are independent).
- Type lookup takes priority over tag matching — each item matches at most one schema.
- Schemas with no matching item (no type, tag, or default fallback) have no effect.

### Lifecycle Modes

The `lifecycle:` field on a schema controls how parent items cascade when all children reach terminal. This is only relevant for container items (items with children).

| Mode           | Behavior                                                                                      |
|----------------|-----------------------------------------------------------------------------------------------|
| `AUTO`         | *(default)* Parent automatically advances to terminal when all children reach terminal.       |
| `MANUAL`       | Suppress auto-cascade — parent must be completed explicitly via `advance_item` or `complete`. |
| `AUTO_REOPEN`  | Auto-cascade to terminal, and reopen the parent to work when a new child is created under a terminal parent. |
| `PERMANENT`    | Parent never auto-terminates, regardless of child state.                                      |

Set `lifecycle` at the schema level in `work_item_schemas:`:

```yaml
work_item_schemas:
  feature:
    lifecycle: MANUAL
    notes:
      - key: requirements
        role: queue
        required: true
        description: "Problem statement and acceptance criteria."
```

### Complete Example Schema

```yaml
work_item_schemas:
  feature-implementation:
    lifecycle: AUTO
    notes:
      - key: requirements
        role: queue
        required: true
        description: "Problem statement and acceptance criteria."
        guidance: "Describe what problem this solves. List 2-5 acceptance criteria."
      - key: design
        role: queue
        required: true
        description: "Chosen approach, alternatives considered."
        guidance: "Describe the chosen implementation approach. List alternatives considered and why they were rejected."
      - key: implementation-notes
        role: work
        required: true
        description: "Key decisions made during implementation."
        guidance: "Document key decisions made during coding. Include any deviations from the design and why."
      - key: test-results
        role: work
        required: true
        description: "Test pass/fail count and new tests added."
        guidance: "State total tests passing and failing. List new test cases added and what edge cases they cover."
      - key: deploy-notes
        role: review
        required: false
        description: "Deploy needed? Version bump? Reconnect required?"
        guidance: "Note any deployment steps, config changes, version bumps, or client reconnection requirements."
```

> The legacy `note_schemas:` flat-list format is still accepted. New configs should prefer `work_item_schemas:` for access to the `lifecycle:` and `traits:` fields.

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
    {
      "key": "requirements",
      "role": "queue",
      "required": true,
      "description": "Problem statement and acceptance criteria.",
      "guidance": "Describe what problem this solves. List 2-5 acceptance criteria.",
      "exists": true,
      "filled": true
    },
    {
      "key": "design",
      "role": "queue",
      "required": true,
      "description": "Chosen approach, alternatives considered.",
      "guidance": "Describe the chosen implementation approach. List alternatives considered and why they were rejected.",
      "exists": false,
      "filled": false
    }
  ],
  "guidancePointer": "Describe the chosen implementation approach. List alternatives considered and why they were rejected."
}
```

When `canAdvance: true`, calling `advance_item(trigger="start")` will succeed.

### Reading Existing Notes

```json
manage_notes(operation="list", itemId="abc-123")
```

---

## 5. Filling Notes Workflow — Step by Step

This walkthrough covers a complete lifecycle for a `feature-implementation` item.

**Step 1: Create the item**

```json
manage_items(operation="create", items=[
  {
    "title": "Password Reset Feature",
    "type": "feature-implementation",
    "tags": "backend,auth",
    "priority": "high"
  }
])
```

The response includes `expectedNotes` when the type (or tags) match a schema:

```json
{
  "id": "abc-123",
  "title": "Password Reset Feature",
  "role": "queue",
  "tags": "feature-implementation",
  "expectedNotes": [
    { "key": "requirements", "role": "queue", "required": true, "description": "Problem statement and acceptance criteria.", "guidance": "Describe what problem this solves. List 2-5 acceptance criteria.", "exists": false },
    { "key": "design",        "role": "queue", "required": true, "description": "Chosen approach, alternatives considered.", "guidance": "Describe the chosen implementation approach. List alternatives considered and why they were rejected.", "exists": false },
    { "key": "implementation-notes", "role": "work", "required": true, "description": "Key decisions made during implementation.", "guidance": "Document key decisions made during coding. Include any deviations from the design and why.", "exists": false },
    { "key": "test-results",  "role": "work", "required": true, "description": "Test pass/fail count and new tests added.", "guidance": "State total tests passing and failing. List new test cases added and what edge cases they cover.", "exists": false },
    { "key": "deploy-notes",  "role": "review", "required": false, "description": "Deploy needed? Version bump? Reconnect required?", "exists": false }
  ]
}
```

**Step 2: Fill queue-phase notes**

Before writing notes, consult `guidancePointer` for authoring instructions:

```json
get_context(itemId="abc-123")
// guidancePointer: "Describe what problem this solves. List 2-5 acceptance criteria."
```

Use the guidance to author each note:

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

Consult `guidancePointer` again — it now points to the first unfilled work-phase note:

```json
get_context(itemId="abc-123")
// guidancePointer: "Document key decisions made during coding. Include any deviations from the design and why."
```

Use the guidance to author each work-phase note:

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

### 5.5 Guidance — Agent Communication Channel

The `guidance` field in note schemas is a communication channel from schema authors to automated agents. It provides specific authoring instructions that go beyond the short `description` — telling agents *how* to write a note, not just *what* the note is for.

#### What guidance is

`guidance` is an optional string set in `.taskorchestrator/config.yaml` alongside each note definition. It carries intent from whoever designed the schema to whoever (or whatever) fills the note at runtime. Agents should treat it as a prompt: follow it when composing note bodies.

#### Where guidance appears

Guidance surfaces in four places:

- **`get_context(itemId=...)`** — `guidancePointer` at the top level. Points to the guidance of the first unfilled required note for the current phase. `null` when all required notes are filled or no matching schema has guidance.
- **`manage_items(create)` response** — each entry in `expectedNotes` includes an optional `guidance` field when the schema defines one.
- **`create_work_tree` response** — `expectedNotes` on root and child items include the optional `guidance` field.
- **`advance_item` gate errors** — `missingNotes` array entries include the optional `guidance` field when present.

#### How agents consume guidance

The standard protocol is:

1. Call `get_context(itemId=...)` before writing notes.
2. Read `guidancePointer` — it is the instruction for the first unfilled required note in the current phase.
3. Use the guidance text as authoring instructions for `manage_notes(operation="upsert")`.
4. After filling that note, call `get_context` again if there are more unfilled required notes — `guidancePointer` will advance to the next one.

Alternatively, use `expectedNotes` from the item creation response to batch-fill all notes at once, using each entry's `guidance` field individually.

#### Semantics

- `guidancePointer` always points to the **first** unfilled required note for the **current phase**.
- It is `null` when all required notes for the current phase are filled, or when no schema entry for the current phase has a `guidance` value.
- Advancing to the next phase resets the pointer to the first unfilled required note of the new phase.
- Optional notes (required: false) do not contribute to `guidancePointer`.

#### `expectedNotes` field availability by tool

Different tools return different subsets of the `expectedNotes` fields:

| Field         | `manage_items` | `advance_item` (success) | `advance_item` (gate error) | `create_work_tree` | `get_context` |
|---------------|:--------------:|:------------------------:|:---------------------------:|:------------------:|:-------------:|
| `key`         | ✓              | ✓                        | ✓                           | ✓                  | ✓             |
| `role`        | ✓              | ✓                        | —                           | ✓                  | ✓             |
| `required`    | ✓              | ✓                        | —                           | ✓                  | ✓             |
| `description` | ✓              | ✓                        | ✓                           | ✓                  | ✓             |
| `guidance`    | ✓ (optional)   | ✓ (optional)             | ✓ (optional)                | ✓ (optional)       | ✓ (optional)  |
| `exists`      | ✓              | ✓                        | —                           | ✓                  | ✓             |
| `filled`      | —              | —                        | —                           | —                  | ✓             |

`guidance` is optional in all positions — it only appears when the schema entry defines it.

---

### 5.6 Agent Integration Patterns

#### Standard note-filling protocol

The recommended pattern for any agent filling notes on a schema-tagged item:

```
1. get_context(itemId=...)          → read guidancePointer
2. manage_notes(upsert, ...)        → fill the note using guidancePointer as instructions
3. Repeat until gateStatus.canAdvance: true
4. advance_item(trigger="start")    → advance to next phase
```

#### Using `expectedNotes` at creation time

When creating an item via `manage_items` or `create_work_tree`, the response includes `expectedNotes` with per-note `guidance`. Agents can use this to front-load all queue-phase notes immediately after creation without a separate `get_context` call:

```json
manage_items(operation="create", items=[{ "title": "...", "tags": "feature-implementation" }])
// Response includes expectedNotes with guidance per entry
// Use each entry's guidance to write the corresponding note body immediately
```

#### Hook and skill injection

Orchestration hooks (e.g., `SubagentStart`) can inject `guidancePointer` into a subagent's system prompt before the agent begins work. This removes the need for the agent to call `get_context` itself:

```
System context injected by hook:
  Item: abc-123 — Password Reset Feature (role: queue)
  Required note: design
  Guidance: Describe the chosen implementation approach. List alternatives considered
            and why they were rejected.
```

The agent receives this context at session start and can proceed directly to `manage_notes(upsert)`.

#### Example delegation prompt with guidance embedding

When dispatching a subagent to fill notes for a specific item, embed the guidance directly in the prompt:

```
Fill the "design" note for item abc-123.

Guidance from schema: "Describe the chosen implementation approach. List alternatives
considered and why they were rejected."

Use manage_notes(operation="upsert") with itemId="abc-123", key="design", role="queue".
```

---

## 6. Status Labels

Status labels are human-readable strings automatically set on WorkItems during role transitions. They provide a display-friendly status name alongside the semantic role.

### Default Labels

| Trigger    | Status Label     | Description                                     |
|------------|------------------|-------------------------------------------------|
| `start`    | `"in-progress"`  | Item has begun active work.                     |
| `complete` | `"done"`         | Item finished successfully.                     |
| `block`    | `"blocked"`      | Item is paused due to a dependency or hold.     |
| `cancel`   | `"cancelled"`    | Item was explicitly cancelled.                  |
| `cascade`  | `"done"`         | Item auto-completed via cascade from children.  |
| `resume`   | *(null)*         | Preserves the label from before the block.      |
| `reopen`   | *(null/cleared)* | Clears the label when reopening a terminal item.|

### Label Precedence

1. **Resolution label** — hardcoded for `cancel` ("cancelled") and `reopen` (null/cleared). Always wins when non-null.
2. **Config-driven label** — resolved from `StatusLabelService` for the trigger. Used when the resolution label is null.
3. **Resume behavior** — `applyTransition` preserves the pre-block label automatically.

### Customizing Labels

Override default labels in `.taskorchestrator/config.yaml`:

```yaml
status_labels:
  start: "working"
  complete: "finished"
  block: "on-hold"
  cancel: "abandoned"
  cascade: "auto-completed"
```

Triggers not listed in the config get no label override (null). If no `status_labels` section exists, the hardcoded defaults above are used.

### Where Labels Appear

- **`advance_item` response** — each successful result includes `"statusLabel"` when set.
- **`complete_tree` response** — each completed/cancelled item includes `"statusLabel"` when set.
- **Cascade events** — cascade results in both tools include `"statusLabel"` when set.
- **`query_items` responses** — the `statusLabel` field is included in item JSON when non-null.

---

## 7. Dependency Blocking

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

### Cascade Behavior in `advance_item`

`advance_item` automatically cascades role transitions up the hierarchy in two situations:

**Start cascade (QUEUE → WORK):** When a child item transitions to WORK, the parent is automatically advanced from QUEUE to WORK (if it is still in QUEUE). This cascade continues up the ancestor chain.

**Terminal cascade (all children → TERMINAL):** When a child item reaches TERMINAL, if all siblings are also terminal, the parent is automatically advanced to TERMINAL. This cascade also continues up the ancestor chain.

**Reopen cascade (child TERMINAL → QUEUE):** When a child item is reopened under a terminal parent, the parent is automatically reopened to WORK. This only applies to the immediate parent — no recursion.

All cascade types appear in `cascadeEvents` in the response:

```json
{
  "cascadeEvents": [
    { "itemId": "feat-uuid", "title": "Auth Feature", "previousRole": "queue", "targetRole": "work", "applied": true }
  ]
}
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

### Actor Attribution

`advance_item` transitions and `manage_notes` upserts accept an optional `actor` claim that records *who* performed the action. This enables post-mortem analysis of multi-agent workflows.

```json
advance_item(transitions=[{
  "itemId": "abc-123",
  "trigger": "start",
  "actor": { "id": "impl-agent", "kind": "subagent", "parent": "orchestrator-1" }
}])
```

The response echoes the actor claim and includes a verification record:

```json
{
  "actor": { "id": "impl-agent", "kind": "subagent", "parent": "orchestrator-1" },
  "verification": { "status": "unverified", "verifier": "noop" }
}
```

Key behaviors:
- **Optional by default** — omitting `actor` produces no error and no attribution data
- **Cascade transitions** always have null actor (system-generated, not attributable)
- **Note re-upsert** replaces the actor (last-writer-wins semantics)
- **`get_context` session resume** includes actor/verification on recent transitions
- **`query_notes`** includes actor/verification on notes that have them

Actor claims are self-reported — the server trusts them as-is in Stage 1. To require actor claims on all write operations, enable auditing in `.taskorchestrator/config.yaml` (set `auditing.enabled: true`). See [Enforcing Actor Attribution](./api-reference.md#enforcing-actor-attribution) in the API reference.

---

## 8. Efficient Queries

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

## 9. Config Reference

The full schema for `.taskorchestrator/config.yaml`:

### Top-Level Structure

```yaml
# Preferred — supports lifecycle, traits, default_traits
work_item_schemas:
  <schema-key>:
    lifecycle: <AUTO|MANUAL|AUTO_REOPEN|PERMANENT>   # optional
    notes:
      - <note-entry>
    traits:                    # optional list of trait keys to apply
      - <trait-key>
    default_traits:            # optional — traits added to every item matching this schema
      - <trait-key>

# Legacy — flat list under each key, still fully supported
note_schemas:
  <schema-key>:
    - <note-entry>

# Composable traits (reusable note bundles)
traits:
  <trait-key>:
    - key: <note-key>
      role: <queue|work|review>
      required: <true|false>
      description: "<description>"
      guidance: "<guidance>"
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
| `skill`       | string  | no       | Skill name                | Skill to invoke before filling. Shown as `skillPointer`.           |

### Matching Rules

1. **Type-first lookup** — the item's `type` field is looked up directly in `work_item_schemas`. If found, that schema is used.
2. **Tag fallback** — if no type match, the item's tags are checked against schema keys. First matching tag wins. Tags are matched as exact substrings within the comma-separated tags string.
3. **Default schema** — if neither type nor tags match, the `default` schema is used (if defined). Items with no match at all advance freely.

### Minimal Config Example

```yaml
work_item_schemas:
  task-implementation:
    lifecycle: AUTO
    notes:
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
docker run -e AGENT_CONFIG_DIR=/project -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro task-orchestrator:dev
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
| Guidance consumption              | `get_context(itemId="uuid")` — `guidancePointer` gives instructions for filling the first missing required note |
| Find blocked items                | `get_blocked_items(includeAncestors=true)`                      |
| Create a dependency chain         | `manage_dependencies(operation="create", pattern="linear", itemIds=[...])` |
| Cancel an item                    | `advance_item(transitions=[{itemId, trigger:"cancel"}])`        |
| Reopen a terminal item           | `advance_item(transitions=[{itemId, trigger:"reopen"}])`        |
| Filter by phase                   | `query_items(operation="search", role="work")`                  |
