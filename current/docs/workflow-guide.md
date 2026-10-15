# v3 MCP Task Orchestrator — Workflow Guide

> **4.0 notice.** The server contract changed in 4.0 (new tool names, call shapes and response envelope). The call examples in this guide still show the 3.x shapes and are being rewritten with the 4.0 plugin; the 4.0 contract is in the [API Reference](./api-reference.md).

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
| `complete` | `queue`, `work`, `review` | `terminal`               | Enforces gates: all required notes across ALL phases must be filled. Rejected from `blocked` — `resume` first. |
| `block`    | `queue`, `work`, `review` | `blocked`                | Saves `previousRole` for resume.                   |
| `hold`     | `queue`, `work`, `review` | `blocked`                | Alias for `block`.                                 |
| `resume`   | `blocked`                 | Previous role            | Restores role saved at block time.                 |
| `cancel`   | Any non-terminal (including `blocked`) | `terminal`  | Sets `statusLabel = "cancelled"`. Does not enforce gates. |
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

**Schema resolution order** (one rule; tags never select a schema):
1. **Per-root type** — the item's `type` (e.g. `feature`, `task`, `bug`) declared in the root's pushed config.
2. **Server type** — the same `type` declared in the server config.
3. **Per-root default** — the root's `default` schema.
4. **Server default** — the server's `default` schema. An item that matches none advances freely with no gate enforcement.

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
    lifecycle: <AUTO|MANUAL|PERMANENT>   # optional, default: AUTO
    notes:
      - key: <note-key>
        role: <queue|work|review>
        required: <true|false>
        description: "<Short description of what this note should contain.>"
        guidance: "<Optional longer guidance shown to agents filling the note.>"
    default_traits:            # optional — trait keys applied to every item matching this schema
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
| `required`    | boolean | no       | Whether this note must be filled before advancing past this phase. Defaults to `false` if omitted — set it explicitly. |
| `description` | string  | no       | Short description of expected content (defaults to `""`; recommended). Fetch via `query_items(operation="schema")`. |
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
| `PERMANENT`    | Parent never auto-terminates, regardless of child state.                                      |

The lifecycle also governs tree edits (4.0):

- **A closed parent takes no new children.** Creating an item under a `terminal` parent, or moving an item under
  one, is rejected when the parent's lifecycle is `AUTO` (reopen the parent first); under `MANUAL` and `PERMANENT`
  it is allowed. This applies to `manage_items` create and update, `create_work_tree` (its `parentId` and an
  attach-mode `root.id`) and REST `POST /items` / `PATCH /items/{id}` (`409 invalid_transition`).
- **Moving a child away, or deleting it, re-evaluates the old parent** exactly like a child completion: under `AUTO`
  the parent completes when every remaining child is terminal and its gate passes (missing required notes suppress
  it, reported with `gateBlocked`), in the same transaction as the move or delete. Nothing happens under `MANUAL` or
  `PERMANENT`, or when no child remains (deleting the last child never completes the parent). The new parent of a
  move is never cascaded. `manage_items` reports these cascades as `cascadeEvents` on the update or delete.
- **Items are always created in `queue`;** use `advance_item` to move them.

Set `lifecycle` at the schema level in `work_item_schemas:`:

```yaml
work_item_schemas:
  quick-fix:
    lifecycle: AUTO
    notes:
      - key: session-tracking
        role: work
        required: true
        description: "Brief record of what was changed and test results."
```

### Complete Example Schema

```yaml
work_item_schemas:
  feature-implementation:
    lifecycle: AUTO
    notes:
      - key: feature-summary
        role: queue
        required: true
        description: "Lean feature-level summary — goal, findings-to-tasks mapping, dependency edges, non-goals pointer."
        guidance: "Cover the goal in 2-3 sentences, a findings→tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded."
      - key: implementation-notes
        role: work
        required: true
        description: "Context handoff for downstream agents — deviations, surprises, and decisions that affect dependent work."
        guidance: "Document decisions made during implementation that weren't in the feature-summary. Focus on deviations from the plan, interface surprises, and assumptions that turned out wrong."
      - key: review-checklist
        role: review
        required: true
        description: "Quality gate — plan alignment, test quality, and simplification review."
        guidance: "Verify what was built aligns with the feature-summary, tests cover the test strategy, and any /simplify changes have test coverage."
```

> The legacy `note_schemas:` flat-list format is still accepted. New configs should prefer `work_item_schemas:` for access to the `lifecycle:` and `default_traits:` fields.

### Phase Flow with Gates

```
queue
  requires: feature-summary (filled)
      |
   [start]
      |
    work
  requires: implementation-notes (filled)
      |
   [start]
      |
   review
  requires: review-checklist (filled)
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
    "key": "feature-summary",
    "role": "queue",
    "body": "## Goal\nAllow users to reset passwords via email.\n\n## Findings -> Tasks\n- Token storage design -> PasswordResetTokenRepository task\n\n## Dependencies\nNone.\n\n## Non-goals\nSee linked spec doc for alternatives considered."
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
    "missing": ["feature-summary"]
  },
  "schema": [
    {
      "key": "feature-summary",
      "role": "queue",
      "required": true,
      "description": "Lean feature-level summary — goal, findings-to-tasks mapping, dependency edges, non-goals pointer.",
      "guidance": "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded.",
      "exists": false,
      "filled": false
    }
  ],
  "guidancePointer": "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded."
}
```

When `canAdvance: true`, calling `advance_item(trigger="start")` will succeed.

### Reading Existing Notes

```json
query_notes(operation="list", itemId="abc-123")
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
    { "key": "feature-summary", "role": "queue", "required": true, "description": "Lean feature-level summary — goal, findings-to-tasks mapping, dependency edges, non-goals pointer.", "guidance": "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded.", "exists": false },
    { "key": "implementation-notes", "role": "work", "required": true, "description": "Context handoff for downstream agents — deviations, surprises, and decisions that affect dependent work.", "guidance": "Document decisions made during implementation that weren't in the feature-summary. Focus on deviations from the plan, interface surprises, and assumptions that turned out wrong.", "exists": false },
    { "key": "review-checklist", "role": "review", "required": true, "description": "Quality gate — plan alignment, test quality, and simplification review.", "guidance": "Verify what was built aligns with the feature-summary, tests cover the test strategy, and any /simplify changes have test coverage.", "exists": false }
  ]
}
```

**Step 2: Fill queue-phase notes**

Before writing notes, consult `guidancePointer` for authoring instructions:

```json
get_context(itemId="abc-123")
// guidancePointer: "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded."
```

Use the guidance to author the note:

```json
manage_notes(operation="upsert", notes=[
  {
    "itemId": "abc-123",
    "key": "feature-summary",
    "role": "queue",
    "body": "Goal: let users reset passwords by email.\n\nFindings -> Tasks:\n- Token storage design -> PasswordResetTokenRepository task\n\nDependencies: none.\n\nNon-goals: see linked spec doc."
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
// guidancePointer: "Document decisions made during implementation that weren't in the feature-summary. Focus on deviations from the plan, interface surprises, and assumptions that turned out wrong."
```

Use the guidance to author the work-phase note:

```json
manage_notes(operation="upsert", notes=[
  {
    "itemId": "abc-123",
    "key": "implementation-notes",
    "role": "work",
    "body": "Added PasswordResetTokenRepository. Token TTL configurable via env var. 42 tests passing, 0 failing; added 8 new tests for token expiry edge cases."
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
  Required note: feature-summary
  Guidance: Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges
            between child tasks, and a pointer to where non-goals and alternatives are recorded.
```

The agent receives this context at session start and can proceed directly to `manage_notes(upsert)`.

#### Example delegation prompt with guidance embedding

When dispatching a subagent to fill notes for a specific item, embed the guidance directly in the prompt:

```
Fill the "feature-summary" note for item abc-123.

Guidance from schema: "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency
edges between child tasks, and a pointer to where non-goals and alternatives are recorded."

Use manage_notes(operation="upsert") with itemId="abc-123", key="feature-summary", role="queue".
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

1. **Config-driven label**: one rule for every applied transition: the `status_labels` entry for the trigger (per-root config, then the global config, then the defaults above). A `start` that lands in `terminal` uses the `complete` label; cascades use the `cascade` label. A configured `cancel` label takes effect (there is no hardcoded override).
2. **No label configured**: when the resolved label is null, a transition into `blocked` keeps the item's current label; any other transition clears it.

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
manage_dependencies(operation="create", pattern="fan-out", fromItemId="task-a", toItemIds=["task-b", "task-c", "task-d"])
```

Fan-in (many blockers, one dependent):

```json
manage_dependencies(operation="create", pattern="fan-in", fromItemIds=["task-a", "task-b"], toItemId="task-c")
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

### Cascade Behavior

A transition can re-evaluate the item's immediate parent in the same transaction (a start cascade moves a queued parent into `work`, a terminal cascade completes a parent whose children are all terminal and climbs while each ancestor qualifies, a reopen cascade reopens a terminal parent), and a cascade stopped by a gate is reported with its reason rather than failing the child's transition. The rules (lifecycle, gates, suppression reasons, what is and is not reported) are specified once, in the `advance` section of the API reference: [Cascades](./api-reference.md#advance).

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

Every write records *who* performed it, and in 4.0 that is the **transport principal**, never a field the caller writes. The principal is established by how the caller reached the server: `api:<id>` for the holder of a bearer or JWT credential (REST, and `/mcp` over HTTP when the REST API runs `API_AUTH_MODE=bearer|jwks`), and the single `local` principal for stdio, `/mcp` in local mode and REST `API_AUTH_MODE=none`. A request body `actor`, on any tool, is refused as `unknown_parameter`.

Key behaviors:
- **Nothing to send** - transitions and note upserts are attributed to the principal automatically
- **Cascade transitions** always have null actor (system-generated, not attributable)
- **Note re-upsert** replaces the attribution (last-writer-wins) but not the note's owner; another principal cannot overwrite an owned note unless its key is `shared`
- **`get_context` session resume** includes actor/verification on recent transitions
- **`query_notes`** includes actor/verification on notes that have them (admins, or when attribution redaction is off)
- **`local` is one principal** - every stdio or local-mode session is the same principal, so they share claims and note ownership; telling agents apart needs separate credentials or, inside a run, seat tokens (see [section 12](#12-runs-seats-and-decisions))

The `verification` record names how the principal was established (`api-jwks`, `api-bearer` or `local`). See [Actor Attribution](./api-reference.md#actor-attribution) in the API reference and [Identity Configuration](./fleet-deployment.md#identity-configuration) for the operator side.

---

## 8. Efficient Queries

### Full-Text Search (FTS5) Recipes

The `query_items(operation="search")` and `query_notes(operation="search")` operations both support
FTS5 full-text search when a `query` string is provided. Use these over list-mode filtering whenever
you are looking for items or notes by content rather than metadata.

See [`search-and-discovery.md`](./search-and-discovery.md) for the architecture and score interpretation.

#### Recipe 1 — Find passages mentioning X across all requirements

Find any item whose title or summary mentions "authentication":

```json
query_items(operation="search", query="authentication token", scope={role="queue"}, limit=20)
```

Find notes that discuss a specific topic across the entire workspace:

```json
query_notes(operation="search", query="rate limiting strategy", limit=20)
```

#### Recipe 2 — Search within a feature's subtree

Scope the search to descendants of a known feature UUID using `scope.ancestorId`:

```json
query_items(operation="search", query="database migration", scope={ancestorId="550e8400-e29b-41d4-a716-446655440000"})
```

```json
query_notes(operation="search", query="rollback plan", scope={ancestorId="550e8400-e29b-41d4-a716-446655440000"})
```

This uses a recursive CTE under the hood — any descendant at any depth is included.

#### Recipe 3 — Find items that reference REQ-42 (backlinks)

Identify all items that hold a dependency edge pointing AT a specific item — e.g., everything that
blocks or relates to a requirement:

```json
query_dependencies(operation="backlinks", itemId="550e8400-e29b-41d4-a716-446655440042")
```

Narrow to one type — "what blocks REQ-42?":

```json
query_dependencies(operation="backlinks", itemId="550e8400-e29b-41d4-a716-446655440042", type="BLOCKS")
```

Each backlink entry returns `{ fromItemId, type, fromTitle }`. Use `query_items(get, id=fromItemId)`
to load full details on any item of interest.

#### Recipe 4 — Iterative search-then-read

`search` returns references and snippets — NOT full note bodies. For full content, use a two-step
pattern:

Step 1 — find matching notes with snippet context:

```json
query_notes(operation="search", query="acceptance criteria OAuth", scope={ancestorId="feature-uuid"}, snippet=true)
```

Step 2 — fetch full body for any hit by note ID or by (itemId, key):

```json
query_notes(operation="get", id="<note-uuid-from-hit>")
```

Or list all notes for the owning item:

```json
query_notes(operation="list", itemId="<itemId-from-hit>", role="queue")
```

**Why two steps?** Snippets are ~32 tokens. Full note bodies can be thousands of tokens. Loading
them selectively avoids token budget exhaustion in multi-note workspaces.

---

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
# Preferred — supports lifecycle and default_traits
work_item_schemas:
  <schema-key>:
    lifecycle: <AUTO|MANUAL|PERMANENT>   # optional
    notes:
      - <note-entry>
    default_traits:            # optional — traits added to every item matching this schema
      - <trait-key>

# Legacy — flat list under each key, still fully supported
note_schemas:
  <schema-key>:
    - <note-entry>

# Composable traits — referenced by default_traits or by an item's own traits
traits:
  <trait-key>:
    notes:                     # the trait's notes go under a notes: key, not directly under the trait
      - key: <note-key>
        role: <queue|work|review>
        required: <true|false>
        description: "<description>"
        guidance: "<guidance>"
```

There is no schema-level `traits:` key — a schema names its traits with `default_traits:`, and the parser warns on and ignores any other key. Traits can also carry `resources:`, `dispatch:`, and `seats:`; see [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md) for the full format.

Additional top-level keys (workflows, status, cascade) are supported but not covered in this guide.

### Note Schema Entry Fields

| Field         | Type    | Required | Allowed Values            | Description                                                        |
|---------------|---------|----------|---------------------------|--------------------------------------------------------------------|
| `key`         | string  | yes      | Any non-empty string      | Note identifier. Must be unique within the schema.                 |
| `role`        | string  | yes      | `queue`, `work`, `review` | Phase gate this note belongs to.                                   |
| `required`    | boolean | no       | `true`, `false`           | If true, must be filled before `start` advances past this phase. Defaults to `false` if omitted — set it explicitly. |
| `description` | string  | no       | Any string                | Short description (defaults to `""`; recommended). Fetch via `query_items(operation="schema")`. |
| `guidance`    | string  | no       | Any string                | Longer authoring hint. Shown as `guidancePointer` in gate status.  |
| `skill`       | string  | no       | Skill name                | Skill to invoke before filling. Shown as `skillPointer`.           |

### Matching Rules

1. **Per-root type** — the item's `type` declared in the root's pushed config.
2. **Server type** — the same `type` declared in the server config.
3. **Per-root default**, then **server default** — the `default` schema of the root, then of the server. Items with no match at all advance freely.

Tags never select a schema.

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
docker run -e AGENT_CONFIG_DIR=/project -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro task-orchestrator:dev
```

---

## 10. Claim Mechanism for Multi-Agent Fleets

The claim mechanism prevents race conditions between independent agents competing for the same work items. It is optional: single-orchestrator deployments that serialize work dispatch do not need claims.

> **Operators:** see [Fleet Deployment Guide](./fleet-deployment.md) for fail-closed verification, `DATABASE_BUSY_TIMEOUT_MS`, capacity planning, and a Claims Troubleshooting FAQ. The tool-level `claim_item` reference lives in [API Reference](./api-reference.md#advance).

> **Agent implementers:** the bundled Claude Code plugin under `claude-plugins/task-orchestrator/` targets default-mode single-agent orchestration. Its skills, hooks, and orchestration context assume the agent-owned phase-entry pattern (`advance_item` called directly) — they do not reference `claim_item` or coordinate claim-then-advance sequencing. If you are building agents for a claim-based fleet, treat the bundled plugin's behavior as undefined and build claim-aware skills/hooks against the MCP tool surface directly (`claim_item`, `advance_item`, `get_next_item`, `query_items`, `get_context`). See the [Fleet Deployment Guide — Scope](./fleet-deployment.md#scope) for the public contract.

### When to Use Claims

**Skip claims if:**
- A single orchestrator dispatches work sequentially — it controls which agent gets which item.
- Your fleet has a natural partition (e.g., each agent handles a different feature tree).

**Use claims when:**
- 10 or more independent agents poll `get_next_item` and `advance_item` concurrently.
- You observe agents starting the same item twice (the `get_next_item → advance_item` race).
- Crash recovery requirements demand TTL-based automatic release.

### Claim Lifecycle

The standard two-call pattern — discover then claim:

```
get_next_item()                     → find an unclaimed item
claim_item(claims=[{itemId, ttl}])  → claim it (auto-releases any prior claim by this agent)
  outcome=success → proceed with advance_item + work
  outcome=already_claimed → pick a different item (retryAfterMs available)
advance_item(trigger="start")       → ownership enforced: caller must be the claim holder
  ... do work ...
claim_item(claims=[{itemId}])       → heartbeat: refresh TTL (if work > TTL/2 = 450s)
advance_item(trigger="complete")    → ownership enforced at completion too
```

> **Single-claim-per-call:** `claim_item` enforces `claims.size ≤ 1`. Calls with `claims.size > 1` are rejected immediately with error code `multi_claim_not_supported`, regardless of whether entries use `itemId` or `selector` mode. Issue one `claim_item` call per item. The cap derives from the heartbeat write-budget assumption (one TTL refresh per agent per cycle); a future `claim_heartbeats` table mitigation could re-evaluate the constraint. The `releases` array remains batchable.

### Serialized Find-and-Claim (Selector Mode)

The two-call pattern above has an inherent race: between `get_next_item` returning an item ID and `claim_item(itemId=...)` locking it, another agent can claim the same item. For high-concurrency fleets, selector mode eliminates the **user-facing** race window by resolving the filter and claiming the top match in a single MCP call.

`get_next_item` and `claim_item.selector` accept **identical filter shapes** — the same `tags`, `priority`, `type`, `complexityMax`, `createdAfter/Before`, `roleChangedAfter/Before`, and `orderBy` parameters apply in both tools, backed by the same shared eligibility logic (`NextItemRecommender`).

**Selector mode lifecycle:**

```
claim_item(claims=[{ selector: { priority: "high", complexityMax: 4, orderBy: "oldest" }, ttlSeconds: 900 }],
           requestId=<uuid>)
  outcome=success, selectorResolved=true → proceed with advance_item + work (itemId is in the result)
  outcome=queue_empty, kind=permanent    → nothing matches these filters at all; back off or wait
  outcome=none_eligible, kind=transient  → matches exist but none is claimable now (see `excluded`); retry after `retryAfterMs`
advance_item(trigger="start")       → ownership enforced: caller must be the claim holder
  ... do work ...
claim_item(claims=[{itemId}])       → heartbeat: refresh TTL (use the itemId from the selector result)
advance_item(trigger="complete")    → ownership enforced at completion too
```

**Fleet drain pattern using selector mode:**

```json
// Each agent calls this loop independently — no coordination needed
{
  "claims": [{
    "selector": { "orderBy": "oldest" },
    "ttlSeconds": 900,
    "claimRef": "worker-7-drain-loop"
  }],
  "requestId": "<fresh UUID per iteration>"
}
```

`orderBy: "oldest"` provides FIFO (oldest-first) draining: agents process items in creation order rather than racing to the same high-priority items. When `queue_empty` is returned, the queue is genuinely drained — the agent can idle, exit, or poll after a delay. When `none_eligible` is returned instead, matches exist but are all excluded right now; the agent should back off `retryAfterMs` and retry rather than treating the drain as finished.

**Selector hygiene and ancestor-claim filtering.** In selector mode, items whose ancestor chain contains a live claim held by a *different* agent are automatically excluded from the eligible set. This sub-tree isolation protects in-progress feature orchestration from fleet drain workers picking up child items. Use specific selectors (`parentId: null` to restrict to top-level items, or tag filters such as `tags: "feature"`) when your fleet operates at the top level; broader selectors like `role: queue` will naturally skip sub-items of in-progress features without returning errors. Items excluded by ancestor-claim filtering appear as `none_eligible` (`excluded.ancestorClaimed`) rather than surfacing information about the competing agent's identity. See [Fleet Deployment Guide — Fleet Topology Patterns](./fleet-deployment.md#fleet-topology-patterns) for recommended patterns.

`claimRef` (up to 64 chars) is echoed verbatim in every result and is useful for correlating claim results back to your agent's internal loop state without parsing `itemId` values.

**Idempotency with selector mode.** A successful claim is stored per element for 24 hours and replayed verbatim on a retry with the same `requestId` — the same `itemId` is returned, and the selector is **not** re-evaluated against fresh queue state. A claim that did not succeed (`already_claimed`, `none_eligible`) is not stored, so retrying it with the same `requestId` runs it again. Use a fresh `requestId` per claim iteration, and the same one for retries of the same iteration.

### Heartbeat Pattern

For long-running work (longer than the TTL — default 900s), re-call `claim_item` before the TTL expires to refresh it. Recommended cadence: **TTL/2 = 450s** for the 900s default — the same convention used by Consul, etcd, and other lease-based distributed systems.

Re-claiming an already-held item:
- Refreshes `claimExpiresAt` (new TTL from now)
- Preserves `originalClaimedAt` (first claim timestamp is not overwritten)

Operators can inspect `originalClaimedAt` via `get_context(itemId)` to distinguish freshly-claimed items from long-running renewed work.

**Heartbeat write overhead.** Every re-claim is a row `UPDATE` on `work_items`. At 30 agents with TTL=900s, heartbeats produce approximately 4 writes/minute versus 30–60 writes/minute from real work transitions (~7% overhead). This is acceptable for v1. If writer contention from heartbeat traffic becomes a measured bottleneck, the mitigation is splitting heartbeats into a separate `claim_heartbeats` table (a non-breaking repository-layer change deferred to v1.5+).

### Effect of `complete` and `cancel` on the Claim

`advance_item(trigger="complete" | "cancel")` — and any other transition, path, or cascade that lands the item in TERMINAL — clears the claim record as part of that same transition: `claimedBy`, `claimedAt`, `claimExpiresAt`, and `originalClaimedAt` are all reset to `null`. This applies uniformly across `advance_item`, `complete_tree`, the REST `POST /items/{id}/advance` route, and system cascades.

Consequently `reopen` always starts the item **unclaimed** — there is no residual claim to enforce ownership against, and a subsequent claimant does not need to match the item's prior holder. Calling `claim_item(releases=[...])` before completing work is no longer necessary for claim hygiene (the terminal transition already clears it), though it remains useful for making an early release explicit in the audit trail.

### Crash Recovery via Passive Expiry

When an agent crashes or is killed, its claim expires naturally within the configured TTL (default 900s); no process clears it. Expired claims are filtered at read time, and the server records one `claim.expired` audit event per lapsed claim (hourly sweep, a transition of the item, or a contender's claim; the claim columns are left as they were):
- `get_next_item()` (default `includeClaimed=false`) excludes items with live claims but includes items with expired claims.
- `query_items(claimStatus="expired")` surfaces items with elapsed TTLs for operator inspection.

No manual cleanup is needed for correctness. Use `get_context(itemId)` to confirm whether a specific item's claim has expired (`isExpired: true`).

### Discovery Patterns

Multi-role fleets use `get_next_item(role=...)` to target different pipeline stages:

| Agent group | Call |
|---|---|
| Work-group (implementation) | `get_next_item()` (default `role=queue`) |
| Review-group | `get_next_item(role="review")` |
| Triage-group | `get_next_item(role="blocked")` |
| Fleet health operator | `get_next_item(includeClaimed=true)` — includes claimed items, shows `isClaimed` boolean |

### `includeClaimed` for Fleet Visibility

`get_next_item(includeClaimed=true)` and `query_items(claimStatus="expired")` are the primary operator tools for debugging stale claims:

```json
// Find all expired claims across the fleet
query_items(operation="search", claimStatus="expired")

// Diagnose a specific stalled item (full claim detail including identity)
get_context(itemId="uuid")
// Response includes claimDetail.claimedBy, claimDetail.isExpired, claimDetail.originalClaimedAt
```

`get_context(itemId)` is the only mode that exposes `claimedBy` identity — all other surfaces expose at most a `isClaimed: boolean`.

---

## 11. Resource Leasing

Resource leasing lets a work item declare — via **traits** — that it needs exclusive or advisory
access to a named shared resource (a staging slot, a test database, a deploy credential, a
fleet-wide deployment mutex) before it enters the `work` phase. The server enforces this as a
**gate inside `advance_item`**, the same call agents already use to advance — there is no separate
acquire/release verb and no new MCP tool.

> Full config syntax (`resources:` on a trait, the optional top-level registry, merge semantics,
> the leaf-task-types-only rule) is in
> [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md).
> This section covers the runtime behavior and operating discipline.

### What it is (and isn't)

A trait can declare one or more `resources:` — each a short opaque key (e.g. `staging-db`,
`github-deploy-token`) with a **mode**:

- **`exclusive`** (default) — the server takes a lease on the key when the item enters WORK (via
  `start` or `resume` — both re-enter WORK). At most one item holds an exclusive key at a time in
  v1 (the storage is semaphore-ready for `maxHolders > 1` later; admission is capped at 1 today). A
  second item entering WORK while the key is held gets rejected — see Contention below.
- **`advisory`** — no lease, no lock, no contention possible. The key is recorded into the
  transition's `consumedCredentials` audit trail (alongside any caller-supplied `credentialRefs`)
  purely for visibility — "this item touched this resource" — without serializing anything.

**The single biggest modeling mistake to avoid:** `exclusive` means *"this item's work phase must
not overlap another item's work phase for this key"* — reserve it for genuinely single-occupancy
resources (a staging slot only one deployment can hold, a test DB only one suite can migrate at a
time, a fleet-wide "one deployment in flight" mutex). A credential that is safe for several agents
to *use concurrently* (most read-only API tokens, most service credentials with their own
rate-limit budget) should be declared `advisory`, not `exclusive` — tagging it `exclusive` just
because it's "a credential" needlessly serializes unrelated work. Use `advisory` for "record that
this item touched this credential"; reserve `exclusive` for "this item must have this resource to
itself."

### Contention and retry discipline

When an item enters WORK and an exclusive key it declares is already held, `advance_item` rejects
the transition with a **transient** error, not a gate block:

```json
{
  "itemId": "uuid",
  "trigger": "start",
  "applied": false,
  "error": "Resource lease contended: staging-db",
  "errorKind": "transient",
  "errorCode": "resource_unavailable",
  "retryAfterMs": 30000,
  "contendedResources": ["staging-db"]
}
```

This is deliberately shaped differently from a gate-blocked failure (`missingNotes` / write-more-notes)
because the fix is different: **wait and retry, or work a different item** — not "fill in more
context." `contendedResources` names the contended keys only; **the current holder's identity is
never disclosed** on this path, by design (see Guarantees below). Agents and Ralph-style loop
workers should treat `resource_unavailable` the same way they treat `already_claimed` from
`claim_item`: release any claim on the item and move to a different item, never spin-retry the same
item against the same lock.

On the REST surface, the same contention maps to `409 resource_unavailable` with a `Retry-After`
header (whole seconds, rounded up) — see [`api-rest.md`](./api-rest.md) §10/§14.

An item that declares **no** `resources:` can never produce `resource_unavailable` — the check
short-circuits before any lease-repository access, so the overwhelmingly common case (items with no
resource traits) pays zero extra cost and sees byte-identical behavior to a deployment without this
feature at all.

### Guarantees vs non-guarantees

Resource leasing gives you a real exclusivity guarantee at a specific moment, not a continuous
invariant, and not fairness. Read this section before relying on it for anything safety-critical.

**What it DOES guarantee:**
- **A precondition at WORK entry, not a continuous invariant.** At the instant an item's `start`/`resume`
  transition acquires an exclusive key, no other item holds that key. The lease is **not**
  re-checked or re-validated for the remainder of the item's time in WORK — it holds the key until
  it leaves WORK (complete, cancel, block/hold, reopen, or the TTL elapses), full stop. Both exit
  paths release leases: `advance_item` (and the equivalent REST advance route) release inline on
  every WORK exit, and `complete_tree` drives each item through the same `AdvanceService` pipeline,
  so lease release goes through that same work-exit path — a batch completion via `complete_tree`
  does not orphan leases until TTL expiry.
- **A transition that acquires a lease and then fails to apply leaves no lease behind.** The lease
  acquire, the role change, and every cascade of one advance run in ONE transaction: if any of them
  fails (a DB fault), the whole transaction rolls back — the item never entered WORK and the lease
  was never committed. A fault while acquiring or releasing a lease fails the advance
  (`apply_failed`) the same way.
- **The exclusivity subject is the work item, not the actor.** Leases are keyed on `holder_item_id`,
  not on `acquired_by_actor_id` (audit metadata only). This makes the guarantee independent of actor
  identity quality - it holds regardless of how many processes share one principal (one credential, or
  the `local` principal).
- **A single-DB arbiter.** Admission is enforced by one SQLite transaction (count-guard + insert, or
  refresh) against the `resource_leases` table. There is no distributed coordination — this is not
  a substitute for a distributed lock service across multiple database instances.
- **Keys are opaque labels, never secrets.** A resource key (`staging-db`, `github-deploy-token`) is
  a name, not a value — no credential material is ever stored by this feature. This is enforced by
  construction (the key/label charset is deliberately narrow), not by redaction — labels are
  intentionally NOT hidden from readers, since external verifiability is the point.

**What it does NOT guarantee:**
- **No fairness, no queueing, no waiting acquire.** A contended item is rejected immediately with
  `resource_unavailable` — it does not wait in line. Whichever retry happens to land after the
  holder releases wins; there is no FIFO ordering across contending items. If you need fair
  ordering, build it in your retry/dispatch logic, not in this feature.
- **Crash recovery is TTL expiry or an explicit ADMIN force-release — nothing automatic beyond
  that.** If a holder crashes mid-WORK, the lease sits until its TTL elapses (default 3600s,
  operator-configurable up to a max of 86400s per resource) or an operator calls
  `DELETE /api/v1/resources/leases/{key}` (ADMIN capability — see
  [`fleet-deployment.md`](./fleet-deployment.md)). A lapsed lease row is treated as absent by every
  read and is stolen by the next acquirer; an hourly sweep (also run once at server start) additionally removes lapsed rows
  and records one `lease.expired` event for each, closing the history interval as `expired`. The sweep does not change
  exclusion semantics: it only tidies rows that already no longer exclude anyone.
- **Identity governs early release, not exclusion.** Because leases are item-keyed (not
  actor-keyed), *any* successful transition that moves the holding item out of WORK releases the
  lease — there is no per-actor "only the acquiring actor can release" check. This is a deliberate
  simplification, not an oversight: the exclusivity guarantee above is about the item, not about who
  is allowed to touch it.
- **One item = one concurrent actor is an assumed modelling obligation.** Item-keyed exclusivity
  arbitrates between *items*, not between actors working inside the same item — two sessions
  operating under one work item share its lease, and the schema cannot detect that violation (the
  server sees transitions, not actions). Identity still matters, one level up, in how you cut
  items: model concurrent actors as separate work items at actor granularity. In fleet
  deployments, `claim_item` is the opt-in enforcement of exactly this assumption — it binds one
  verified actor to an item with a TTL, and an agent can hold its item claim and the item's
  resource leases simultaneously by design. The release-side corollary of the same assumption:
  any actor's work-exit transition on the shared item releases the lease for all of them.
- **The kill switch is read once at startup; changing it requires a restart.** `RESOURCE_LEASES_ENFORCED`
  is parsed a single time into `AppConfig.resourceLeasesEnforced` when the server boots and is handed to
  the advance machinery from there; it is not re-read per call. A running JVM's environment cannot
  change mid-process and a container's environment changes only when it is recreated, so flipping this
  var takes effect only after the process or container restarts. `false`/`0`/`no` disable acquisition
  (releases still always run); `true`/`1`/`yes` or unset leave it on; matching is case-insensitive and
  trimmed; any other value logs a WARN and enforcement stays on.
- **No selector-level awareness.** `get_next_item` and `claim_item`'s selector mode do not know
  about resource contention — they can still hand an agent an item that will immediately reject with
  `resource_unavailable` on `start`. This is a known, deliberately deferred gap (see Non-Goals in
  the implementation plan) — treat `resource_unavailable` as a normal, expected outcome to handle in
  your retry loop, not a bug.

### Lease-interval history (audit)

Every lease acquire/refresh/release/steal/force-release also writes to an append-only
`resource_lease_history` table — one row per hold INTERVAL, not per event — answering "who held
resource `R` at time `T`" after the fact, which the live `resource_leases` table (current holder
only) cannot: it still has the answer once a lease has already been released, stolen by another
item, or force-released by an operator. Query it via
`GET /api/v1/resources/leases/history?key=&at=&limit=&cursor=` (see [`api-rest.md`](./api-rest.md) §14) —
omit `at` for a recent-activity feed (newest-first, default 50 rows, max 200, paged by `cursor`); pass `at` as an
ISO-8601 instant to ask "who held this at exactly this moment" (an open interval is bounded by its
own `expiresAt`, so a crashed holder's interval reads as "not held" once its TTL lapses, even though
the operator hasn't force-released it yet). Holder/closer actor identity is ADMIN-only, same
redaction rule as the live list. History rows carry no foreign key to the holder work item and
deliberately survive its deletion — this is an audit trail, not a live-state mirror. There is no
pruning or retention policy in v1: the table is append-only and grows with lease-event cardinality.

---

## 12. Runs, Seats and Decisions

> Unlike sections 1 to 11, the examples in this chapter use the 4.0 call shapes (`runs_write`, `runs_read`, `advance`, `notes_write`, `items_read`). Field tables and error lists are not repeated here: they live in the [API reference](./api-reference.md#category-runs), and the config keys in the [config reference](./config-reference.md).

A **run** is a bounded, budgeted pass over a set of items of one root. Everything before this chapter works without one. A run adds four things on top: scoped credentials for the agents that work the items (seat tokens), enforcement of who may do what to a member, a liveness view, and a channel for questions that need a human or a director (decisions).

### A run, and when to open one

Open a run when an orchestrator is about to hand several items to several agents at once and you want the server, not the prompt, to hold the boundaries: which agent may touch which item, how much it may spend, and when a person has to weigh in. A single agent working one item needs none of this.

```json
{"ops": [{"op": "open", "rootId": "1be2db5e", "items": ["a1b2c3d4", "e5f6a7b8"], "title": "Docs wave",
          "budget": {"tokens": 2000000, "attemptsPerSeat": 3, "maxActiveSeats": 4}}]}
```

A run is `open`, may be `paused`, and ends `closed` with a terminal reason. An item can be in only one run that is not closed. The budget is optional and can only sit at or under the ceilings in the [`runs:` config](./config-reference.md#runs-runs); `update` can lower it later.

### Tokens

`open` returns a **coordinator token**, shown once. The coordinator is whoever presents it, as the top-level `seatToken` field next to `select` or `ops`. `assign` (coordinator only) hands out a **seat token** for one member item and one seat, also shown once:

```json
{"seatToken": "<coordinatorToken>",
 "ops": [{"op": "assign", "runId": "7c0f3a91", "itemId": "a1b2c3d4", "seat": "implementer"}]}
```

- A token is never stored or replayed. A keyed retry of `open` or `assign` answers `tokenRedacted: true`; recover a seat token by assigning the seat again (the old token dies and the attempt count goes up).
- A lost coordinator token cannot be recovered. Close the run and open a new one.
- A token is only good from the principal it was issued to, and only while its assignment is active. Anything else is a flat `unauthenticated`.
- Calls under a token lose ADMIN. Claims and notes are held by the pair (principal, assignment), so two agents that share one `local` principal are still separate holders.
- Closing a run ends its tokens. A closed run's coordinator token answers `unauthenticated`, though a director or a human can still `close`.

### Seats in a run

Seats come from the schema (`seats:` in the [config](./config-reference.md#seat-transitions)). Inside a run that is not closed, on a member whose pinned schema declares seats, the server enforces them:

- A seat token acts only on its own item. It may `start` that item only into the phase of the seat that `enters`, and may send another trigger only if the seat's `transitions:` lists it (default `block` and `hold`).
- A seat writes only the note keys its seat owns, plus unowned keys. The coordinator token writes only the keys of the seat named `orchestrator`, and unowned ones.
- A human credential without a token is exempt. Over stdio the caller is the `local` principal, which is an agent, so it is not exempt.
- A refusal is `seat_forbidden` (or `note_owned_by_other` for a note) and applies nothing.

```json
{"seatToken": "<seatToken>",
 "ops": [{"op": "transition", "itemId": "a1b2c3d4", "trigger": "start"}]}
```

Outside a run, seats stay an orchestration signal and nothing is enforced. A seat ends its own assignment with `{"op": "end_seat", "outcome": "done", "summary": "..."}` under its own token.

### Cascades into seat-enforced members

A cascade is a write too. When a child's change would move a parent that is a member of a run that is not closed, the cascade is applied only if the caller could have sent the equivalent trigger to that parent directly. Otherwise it is suppressed and reported as `blocked: seat` (and `seat_forbidden` on an `items_write`), after the ownership check and before the gates. In practice a seat child does not complete its member parent by cascade unless its token is bound to that parent. The recovery is to advance the parent with the coordinator token or a human credential. See the [cascade rules](./api-reference.md#advance).

### Liveness and the frontier

A seat or the coordinator is **stalled** when it has been quiet for strictly longer than the run's `stallAfterSeconds` (pinned when the run opens; default 600). Quiet means no call under its token on a tool that takes `ops`; a read through a `select` tool does not count, so a long-running seat makes some call on an `ops` tool now and then, for example an `advance` `heartbeat` on its claim (which also reports `usage`). Stalled is only a view: nothing is ended or released for you.

`runs_read` with `select: "frontier"` is the one read that tells the coordinator what to do next:

```json
{"select": "frontier", "runId": "7c0f3a91"}
```

It returns the members that are `ready` (no active seat, no unmet dependency, no claim held outside the run), the `inFlight` seats with their stalled flag, and the `waiting` members with a reason.

### Why an item is blocked

`block` and `hold` take a `reason` (`awaiting_decision`, `dependency`, `resource`, default `manual`). `awaiting_decision` carries a `decisionId` naming an open decision on that item. The reason shows on the item as `blockedReason`, and every way out of `blocked` (`resume`, `cancel`, `complete_tree`) clears it. Answering a decision never resumes the item: the coordinator does that, after reading the answer.

### Decisions

A decision is a question with 1 to 8 options that a seat or the coordinator raises when it needs a person or a director. Its **class** (see [decision classes](./config-reference.md#decision-classes-decision_classes)) sets who may answer, the default severity and when it becomes overdue.

```json
{"seatToken": "<seatToken>",
 "ops": [{"op": "raise", "class": "deviation", "dedupeKey": "api-shape-1", "block": true,
          "question": "The spec says X but the code does Y. Which wins?",
          "options": [{"key": "spec", "label": "Follow the spec"}, {"key": "code", "label": "Keep the code"}],
          "recommended": "spec"}]}
```

Re-raising the same `dedupeKey` returns the existing decision. `block: true` raises and blocks the item in one step. The class sets who may `ack` or `answer` (`human-only`, `director-or-human` or `any-actor`); the matrix is in the [API reference](./api-reference.md#runs_write).

The director (or the human) lists and reads decisions with `runs_read` `decisions` and `decision`, then `ack`s and `answer`s with `runs_write`. An answer is final. The raiser or the run's coordinator can `withdraw` a pending decision, except the ones the server raises for a limit. When a class has `expires_after`, an unanswered decision becomes overdue and reads one step more urgent; the server does nothing else about it.

### Budgets, caps, concurrency and pauses

A run can carry a token budget, a cost budget, an attempt cap per (item, seat) and a cap on concurrently active seats. The server checks the budget on five writes made under a token (heartbeat, transition, assign, end_seat and raise); see [Run Budgets and Caps](fleet-deployment.md#run-budgets-and-caps).

- **Budget breach.** The next token-carrying write after a crossing pauses the run (`pauseReason: budget`) and raises a `run-budget` decision. A director or human answers `extend` (with an `amount`) to resume, or `stop` to close the run.
- **Attempt cap.** An `assign` past `attemptsPerSeat` is refused `run_paused`, the run pauses (`attempts`) and an `attempt-cap` decision is raised (`allow_one`, `skip_item`, `stop`).
- **Concurrency.** An `assign` past `maxActiveSeats` is a transient `run_at_capacity`. Retry when a seat ends.
- **Manual pause.** A director or human can `pause` and `resume` a run. Members in flight can still finish. A pause for `budget` or `attempts` cannot be resumed by the coordinator; only the answer resumes it, or a `close` ends it. While a run is paused for a limit, a `start` under any of its tokens is refused.

Operator-side detail, including what the numbers mean and what is only an estimate, is in [Run Budgets and Caps](./fleet-deployment.md#run-budgets-and-caps).

### Closing a run

`close` takes a reason (`completed`, `cancelled`, `failed` or `abandoned`; `budget_exhausted` is the server's own) and is allowed to the coordinator, the director profile or a human. Any reason but `completed` ends the active seats and releases the members' claims and leases; `completed` is refused while a seat is active. The answer carries a `snapshot` of what the close recorded, the stored `run.ended` event carries the run rollup computed at that moment, and `report` with `select: "rollup"` and a `runId` answers that snapshot for a closed run, so the figures survive retention.

`runs_write` `mark` (REST only) stamps a named instant on a run, for example a retrospective marker. A mark is first-write-wins and cannot be removed, and `runs_read` `list` with `unmarked` returns the closed runs that lack it.

### Answering a decision as a single developer

A person working alone usually has one stdio server, and stdio serves the orchestration profile, which cannot answer decisions. Start a second stdio entry with `MCP_TOOL_PROFILE=director` (or point at `/mcp/director` over HTTP). That entry lists, acknowledges and answers decisions, pauses and resumes runs, and closes them. If you have no director entry and a run is paused for a limit, the only way out from the orchestration side is to `close` it. See the [director endpoint](./fleet-deployment.md#mcp-authentication---read-first) notes for the HTTP and REST forms.

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
| Claim an item (fleet, ID mode)    | `claim_item(claims=[{itemId, ttlSeconds:900}], requestId=<uuid>)` |
| Claim next eligible (selector)    | `claim_item(claims=[{selector:{orderBy:"oldest"}}], requestId=<uuid>)` |
| Release a claim                   | `claim_item(releases=[{itemId}], requestId=<uuid>)` |
| Heartbeat (refresh TTL)           | `claim_item(claims=[{itemId}], requestId=<uuid>)` — same as claim, TTL refreshed |
| Find expired claims               | `query_items(operation="search", claimStatus="expired")` |
| Diagnose stalled claim            | `get_context(itemId="uuid")` — returns `claimDetail` with `claimedBy` |
| Filter next item by tag           | `get_next_item(tags="task-implementation", priority="high")` |
| FIFO queue drain                  | `get_next_item(orderBy="oldest")` |
| Record a consumed credential/resource | `advance_item(transitions=[{itemId, trigger:"start", credentialRefs:["vault:prod-db-password"]}])` |
| Diagnose a stuck resource lease   | `get_context(itemId="uuid")` — `resourceLeases` block; or `GET /api/v1/resources/leases` (ADMIN sees holder actor) |
| Force-release a stuck lease (operator) | `DELETE /api/v1/resources/leases/{key}` (ADMIN capability) |
