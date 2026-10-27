# MCP Task Orchestrator - Workflow Guide

This guide covers the role-based workflow, note schema system, dependency blocking, claims, resource leases and runs, and efficient query patterns for MCP Task Orchestrator 4.0.

---

## Calling the tools

Read this before the first example. Every example in this guide is the JSON request body of one 4.0 tool call. The request fields, the profile of each select or op and the error codes are specified once, in the [API Reference](./api-reference.md); the examples here show how the calls fit together.

**Tools.** There are a few tools, each named for the kind of work it does: `items_read` and `items_write` for work items, `notes_read` and `notes_write` for notes, `deps_read` and `deps_write` for dependencies, `docs_read` and `docs_write` for versioned documents, `advance` for phase transitions and claims, `config` for per-root configuration, `report` for aggregate reports and `runs_read` and `runs_write` for runs. A read tool takes a `select` that names what to read; a write tool takes an `ops` array whose elements each name an `op`:

```json
{ "select": "get", "itemId": "9f3a1c22" }
```

```json
{ "ops": [ { "op": "create", "title": "Add retry", "type": "feature-task", "parentId": "9f3a1c22" } ] }
```

**Tool profiles.** The server builds one tool set per profile, and a client sees only the selects and ops of its profile (a hidden select or op looks absent). `orchestration` (the default, `/mcp`, or `MCP_TOOL_PROFILE=orchestration` over stdio) carries everything except `report`. `knowledge` (`/mcp/knowledge`) is the item, note, dependency and document tools without the orchestration selects and ops. `director` (`/mcp/director`) is the read-mostly view for a human or a director: no note, document or summary bodies, plus `report` and the run controls. The table is in the [API Reference](./api-reference.md#tool-profiles). The examples below use the orchestration profile unless they say otherwise.

**`describe: true`.** Any tool answers `describe: true` with its own registry entry: every select or op it serves in your profile, each field's type and description, and the error codes. It is the live form of the generated tables in the API Reference, so a client never has to guess a field name:

```json
{ "describe": true }
```

**Short ids.** Every id field takes a full UUID or 8 to 32 hex characters of one. Compact views (lists, overviews, `next`) show the 8-hex short id, as the examples do. A prefix that matches several items is `ambiguous_id`.

**Writes are batches.** A write tool answers one result per `ops[]` element. By default each element commits on its own and a failing element does not stop the others; `"atomic": true` runs the whole array in one unit. An element may carry an `idempotencyKey` so a retry replays the stored result.

**Response envelope.** Every tool answers the same envelope, over MCP and over REST:

```json
{ "ok": true, "data": { "results": [ { "ok": true, "id": "3d7e91ab-5c2a-4f60-8b13-9e7a41c0d2f6", "version": 1 } ], "summary": { "ok": 1, "failed": 0 } }, "meta": { "reqId": "k7f3q9ab" } }
```

A failure is `{ "ok": false, "error": { "code", "kind", "message", "detail"?, "fix"? }, "meta": { "reqId" } }`. The `kind` (`transient`, `permanent` or `shedding`) says whether a retry can help. A list answer is `data: { items, page: { total, truncated, next? } }`; follow `page.next` as `cursor` until it is absent. The envelope, paging and error catalog are in the [API Reference](./api-reference.md#response-envelope).

**No `actor`.** A call never names its caller: the principal is established by the transport (see Actor Attribution in section 7), and a body `actor` is `unknown_parameter`.

**Runs and decisions** are explained once, in [section 12](#12-runs-seats-and-decisions); the other guides link there. Aggregate `report` calls are in [reporting.md](./reporting.md).

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

All role transitions use the `advance` tool's `transition` op. There is no direct role assignment, and an item is always created in `queue` (or lifecycle-free, with no role at all, when its type's base schema says so).

### Trigger Table

| Trigger    | From Role(s)              | To Role                  | Notes                                              |
|------------|---------------------------|--------------------------|----------------------------------------------------|
| `start`    | `queue`                   | `work`                   | Queue-phase required notes must be filled.         |
| `start`    | `work`                    | `review` or `terminal`   | Work-phase required notes must be filled.          |
| `start`    | `review`                  | `terminal`               | Review-phase required notes must be filled.        |
| `complete` | `queue`, `work`, `review` | `terminal`               | Enforces gates: all required notes across ALL phases must be filled. Rejected from `blocked` — `resume` first. |
| `block`    | `queue`, `work`, `review` | `blocked`                | Saves `previousRole` for resume. Takes an optional `reason`. |
| `hold`     | `queue`, `work`, `review` | `blocked`                | Alias for `block`.                                 |
| `resume`   | `blocked`                 | Previous role            | Restores role saved at block time.                 |
| `cancel`   | Any non-terminal (including `blocked`) | `terminal`  | Sets `statusLabel = "cancelled"`. Does not enforce gates. |
| `reopen`   | `terminal`                | `queue`                  | Clears statusLabel, bypasses gates. Parent cascades TERMINAL → WORK. |

### Example: Advance a Work Item

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

```json
{
  "ok": true,
  "data": {
    "results": [
      { "ok": true, "itemId": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37", "previousRole": "queue", "newRole": "work", "trigger": "start",
        "statusLabel": "in-progress", "expectedNotes": ["implementation-notes"], "guidanceKey": "implementation-notes" }
    ],
    "summary": { "ok": 1, "failed": 0 }
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

`expectedNotes` lists the note keys the phase just entered expects, and `guidanceKey` names the first missing required one (see section 5).

### Example: Batch Transitions

Several elements in one call, each its own write unit:

```json
{
  "ops": [
    { "op": "transition", "itemId": "3d7e91ab", "trigger": "complete", "summary": "All tests passed" },
    { "op": "transition", "itemId": "8a4c2e10", "trigger": "start" }
  ]
}
```

A rejected element is `{ "ok": false, "error": ... }` in `results[i]` and the call is `partial_failure`; pass `"atomic": true` to make the first failure roll the whole call back.

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

> **Schemas vs notes:** Schemas and notes are independent systems. A schema is a set of rules defined in your project config that specifies what documentation an item must carry - it is never stored in the database. A note is actual content written by agents during implementation - it is stored in the database and carries no reference to any schema. The two meet only at gate-check time: `advance` fetches the item's notes from the database and checks them against the schema's requirements.

### How They Gate Transitions

- A `transition` with `trigger: "start"` checks required notes for the **current phase** before advancing.
- A `transition` with `trigger: "complete"` checks all required notes across all phases.
- A `transition` with `trigger: "cancel"` does not enforce gates.
- Optional notes never block transitions.

Gate enforcement applies to items that resolve a schema via type or default fallback.

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
| `key`         | string  | yes      | Unique identifier for this note within the schema. Used in `notes_write`.            |
| `role`        | string  | yes      | Phase this note belongs to: `queue`, `work`, or `review`.                            |
| `required`    | boolean | no       | Whether this note must be filled before advancing past this phase. Defaults to `false` if omitted — set it explicitly. |
| `description` | string  | no       | Short description of expected content (defaults to `""`; recommended). Fetch via `items_read` `select: "schema"`. |
| `guidance`    | string  | no       | Longer authoring guidance for agents. Named by `guidanceKey` in `items_read` `context`; the text comes from `select: "schema"` with `fields: ["guidance"]`. |
| `skill`       | string  | no       | Skill to invoke when filling this note. Shown in `items_read` `context` as `skillPointer`.    |

#### Constraints

- `key` values must be unique within a schema.
- The same `key` may appear in multiple schemas (schemas are independent).
- Each item matches at most one schema, and tags never select one.
- Schemas with no matching item (no type or default fallback) have no effect.

### Lifecycle Modes

The `lifecycle:` field on a schema controls how parent items cascade when all children reach terminal. This is only relevant for container items (items with children).

| Mode           | Behavior                                                                                      |
|----------------|-----------------------------------------------------------------------------------------------|
| `AUTO`         | *(default)* Parent automatically advances to terminal when all children reach terminal, unless it is in `review` (that cascade is suppressed with `reason: review-pending`; advance it explicitly). |
| `MANUAL`       | Suppress auto-cascade - parent must be completed explicitly with an `advance` `transition` (`complete`). |
| `PERMANENT`    | Parent never auto-terminates, regardless of child state.                                      |

The lifecycle also governs tree edits (4.0):

- **A closed parent takes no new children.** Creating an item under a `terminal` parent, or moving an item under
  one, is rejected when the parent's lifecycle is `AUTO` (reopen the parent first); under `MANUAL` and `PERMANENT`
  it is allowed. This applies to `items_write` `create` and `update` and `create_tree` (its `parentId` and an
  attach-mode `root.id`), on MCP and over REST `POST /api/v1/ops/items_write` (`409 invalid_transition`).
- **Moving a child away, or deleting it, re-evaluates the old parent** exactly like a child completion: under `AUTO`
  the parent completes when every remaining child is terminal and its gate passes (missing required notes suppress
  it, reported with reason `gate_blocked`), in the same transaction as the move or delete. Nothing happens under `MANUAL` or
  `PERMANENT`, or when no child remains (deleting the last child never completes the parent). The new parent of a
  move is never cascaded. `items_write` reports these cascades as `cascade` on the update or delete result.
- **Items are always created in `queue`;** use `advance` to move them.

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

Notes are created or updated with the `notes_write` tool's `upsert` op, and read with `notes_read`.

### Upsert a Note

```json
{
  "ops": [
    {
      "op": "upsert",
      "itemId": "3d7e91ab",
      "key": "feature-summary",
      "role": "queue",
      "body": "## Goal\nAllow users to reset passwords via email.\n\n## Findings -> Tasks\n- Token storage design -> PasswordResetTokenRepository task\n\n## Dependencies\nNone.\n\n## Non-goals\nSee linked spec doc for alternatives considered."
    }
  ]
}
```

`(itemId, key)` is unique - upserting an existing `(itemId, key)` pair replaces it in place and raises its `version` by one. Pass `expectedVersion` to guard against a concurrent writer (`0` means the note must not exist yet), and `bodyFromFile` instead of `body` to read a file inside the agent config root over MCP.

### Checking Gate Status Before Advancing

Use `items_read` with `select: "context"` and an `itemId` to inspect the current gate status before calling `advance`.

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

The response `data` includes:

```json
{
  "item": { "id": "3d7e91ab", "type": "feature-implementation", "title": "Password Reset Feature", "role": "queue", "priority": "high", "depth": 1, "version": 1 },
  "schema": [
    { "key": "feature-summary", "role": "queue", "required": true, "exists": false, "filled": false },
    { "key": "implementation-notes", "role": "work", "required": true, "exists": false, "filled": false },
    { "key": "review-checklist", "role": "review", "required": true, "exists": false, "filled": false }
  ],
  "gateStatus": { "canAdvance": false, "phase": "queue", "missing": ["feature-summary"], "blockedBy": "note" },
  "transitions": { "block": "blocked", "cancel": "terminal", "complete": "terminal", "hold": "blocked", "start": "work" },
  "guidanceKey": "feature-summary",
  "skillPointer": "spec-quality"
}
```

`schema` is keys-only. `guidanceKey` names the first missing required note of the current phase and `skillPointer` its skill, when it has one; read the description and guidance text itself with `items_read` `select: "schema"` (see section 5.5). When `gateStatus.canAdvance` is `true`, an `advance` `transition` with `trigger: "start"` will pass the note gate (claim ownership is checked by the transition itself).

### Reading Existing Notes

`list` returns an item's notes without bodies (with `bodyLength`); `get` returns one body:

```json
{ "select": "list", "itemId": "3d7e91ab", "role": "queue" }
```

```json
{ "select": "get", "itemId": "3d7e91ab", "key": "feature-summary" }
```

---

## 5. Filling Notes Workflow — Step by Step

This walkthrough covers a complete lifecycle for a `feature-implementation` item.

**Step 1: Create the item**

`type` is required on create and must be a type your config declares (an undeclared one is `schema_violation` and the error lists the available types).

```json
{
  "ops": [
    {
      "op": "create",
      "title": "Password Reset Feature",
      "type": "feature-implementation",
      "tags": ["backend", "auth"],
      "priority": "high"
    }
  ]
}
```

```json
{ "ok": true, "data": { "results": [ { "ok": true, "id": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37", "version": 1 } ], "summary": { "ok": 1, "failed": 0 } }, "meta": { "reqId": "k7f3q9ab" } }
```

A create returns no schema decoration. Read the notes the item expects with the `schema` select:

```json
{ "select": "schema", "itemId": "3d7e91ab", "fields": ["guidance"] }
```

```json
{
  "ok": true,
  "data": {
    "type": "feature-implementation",
    "configFingerprint": "e109db8e1ed00ecf5e2dc586815df71418105edfc664174c6ea569884c96a5fd",
    "configSource": "per-root",
    "notes": [
      { "key": "feature-summary", "role": "queue", "required": true, "description": "Lean feature-level summary - goal, findings-to-tasks mapping, dependency edges, non-goals pointer.", "guidance": "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges between child tasks, and a pointer to where non-goals and alternatives are recorded." },
      { "key": "implementation-notes", "role": "work", "required": true, "description": "Context handoff for downstream agents - deviations, surprises, and decisions that affect dependent work.", "guidance": "Document decisions made during implementation that weren't in the feature-summary. Focus on deviations from the plan, interface surprises, and assumptions that turned out wrong." },
      { "key": "review-checklist", "role": "review", "required": true, "description": "Quality gate - plan alignment, test quality, and simplification review.", "guidance": "Verify what was built aligns with the feature-summary, tests cover the test strategy, and any /simplify changes have test coverage." }
    ],
    "features": ["seats", "dispatchBySeat", "independent_of", "rules"]
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

(Abridged: the response also carries the other members listed in the [API Reference](./api-reference.md#items_read).) A type with no schema answers `{ "schemaFree": true }`, which is not an error.

**Step 2: Fill queue-phase notes**

Before writing notes, consult `guidanceKey` and read its guidance (section 5.5):

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

Use the guidance to author the note:

```json
{
  "ops": [
    {
      "op": "upsert",
      "itemId": "3d7e91ab",
      "key": "feature-summary",
      "role": "queue",
      "body": "Goal: let users reset passwords by email.\n\nFindings -> Tasks:\n- Token storage design -> PasswordResetTokenRepository task\n\nDependencies: none.\n\nNon-goals: see linked spec doc."
    }
  ]
}
```

**Step 3: Verify gate, then advance to work**

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

`gateStatus.canAdvance` is now `true`, so:

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

The result element carries `newRole: "work"`.

**Step 4: Fill work-phase notes**

Read `guidanceKey` again - it now names the first unfilled work-phase note (the transition result already carried it):

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

Use the guidance to author the work-phase note:

```json
{
  "ops": [
    {
      "op": "upsert",
      "itemId": "3d7e91ab",
      "key": "implementation-notes",
      "role": "work",
      "body": "Added PasswordResetTokenRepository. Token TTL configurable via env var. 42 tests passing, 0 failing; added 8 new tests for token expiry edge cases."
    }
  ]
}
```

**Step 5: Advance to review**

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

The result element carries `newRole: "review"`.

**Step 6: Advance to terminal**

After the `review-checklist` note is filled, the same call again gives `newRole: "terminal"`:

```json
{ "ops": [ { "op": "transition", "itemId": "3d7e91ab", "trigger": "start" } ] }
```

### 5.5 Guidance — Agent Communication Channel

The `guidance` field in note schemas is a communication channel from schema authors to automated agents. It provides specific authoring instructions that go beyond the short `description` — telling agents *how* to write a note, not just *what* the note is for.

#### What guidance is

`guidance` is an optional string set in `.taskorchestrator/config.yaml` alongside each note definition. It carries intent from whoever designed the schema to whoever (or whatever) fills the note at runtime. Agents should treat it as a prompt: follow it when composing note bodies.

#### Where guidance appears

In 4.0 the responses that name a note carry a **key**, not the guidance text, so a response stays small. The text is read once with the `schema` select:

- **`items_read` `select: "context"` (with `itemId`)** - `guidanceKey` at the top level names the first unfilled required note of the current phase, and `skillPointer` its skill. Both are omitted when every required note of the phase is filled or the entry has no guidance or skill.
- **`advance` `transition` result** - `expectedNotes` (the keys the new phase expects), `guidanceKey` and `skillPointer`, for the phase just entered.
- **`advance` gate error** - a `gate_blocked` error's `detail.missing[]` names the missing notes (`key`, `role`, and `seat` for a seat-aware schema).
- **`items_read` `select: "schema"`** - the full note list of a type or an item (`description`, `skill`, `maxLength`, `seat`); the per-note `guidance` text is stripped unless the call passes `fields: ["guidance"]`.

#### How agents consume guidance

The standard protocol is:

1. Call `items_read` with `select: "context"` and the `itemId` before writing notes.
2. Read `guidanceKey` - it names the first unfilled required note in the current phase.
3. Read that note's guidance text once with `select: "schema"`, `itemId` and `fields: ["guidance"]`, and use it as authoring instructions for the `notes_write` `upsert`.
4. After filling that note, call `select: "context"` again if there are more unfilled required notes - `guidanceKey` advances to the next one.

#### Semantics

- `guidanceKey` always names the **first** unfilled required note for the **current phase**.
- It is omitted when all required notes for the current phase are filled, or when no unfilled entry has guidance.
- Advancing to the next phase resets it to the first unfilled required note of the new phase.
- Optional notes (required: false) do not contribute to `guidanceKey`.

#### Where each tool names the expected notes

| Member | `items_write` `create` | `advance` `transition` (success) | `advance` gate error | `items_write` `create_tree` | `items_read` `context` |
|---|:---:|:---:|:---:|:---:|:---:|
| Note keys | - | `expectedNotes` | `detail.missing[]` | - | `schema[]` (with `exists`, `filled`) |
| `guidanceKey` | - | yes | - | - | yes |
| `skillPointer` | - | yes | - | - | yes |
| Descriptions and guidance text | - | - | - | - | - |

Descriptions and guidance text come only from `items_read` `select: "schema"`. A create and a `create_tree` return ids and counts, not schema decorations.

---

### 5.6 Agent Integration Patterns

#### Standard note-filling protocol

The recommended pattern for any agent filling notes on a schema-typed item:

```
1. items_read   {select: "context", itemId}                       -> read guidanceKey
2. items_read   {select: "schema", itemId, fields: ["guidance"]}   -> read that note's guidance (once per item)
3. notes_write  {ops: [{op: "upsert", ...}]}                       -> fill the note
4. Repeat 1-3 until gateStatus.canAdvance is true
5. advance      {ops: [{op: "transition", itemId, trigger: "start"}]} -> advance to next phase
```

#### Reading the schema at creation time

A create does not echo the expected notes. An agent that wants to front-load every queue-phase note reads the item's schema once after creating it (`select: "schema"`, `itemId`, `fields: ["guidance"]`), then writes all of the notes in one `notes_write` call, using each entry's `guidance` for its own body. To create a whole tree in one unit and have blank notes made for every key its schemas declare, use `items_write` `create_tree` with `createNotes: true`.

#### Hook and skill injection

Orchestration hooks (e.g., `SubagentStart`) can inject the guidance for the item's first missing note into a subagent's system prompt before the agent begins work. This removes the need for the agent to read the context itself:

```
System context injected by hook:
  Item: 3d7e91ab - Password Reset Feature (role: queue)
  Required note: feature-summary
  Guidance: Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency edges
            between child tasks, and a pointer to where non-goals and alternatives are recorded.
```

The agent receives this context at session start and can proceed directly to the `notes_write` `upsert`.

#### Example delegation prompt with guidance embedding

When dispatching a subagent to fill notes for a specific item, embed the guidance directly in the prompt:

```
Fill the "feature-summary" note for item 3d7e91ab.

Guidance from schema: "Cover the goal in 2-3 sentences, a findings->tasks mapping, dependency
edges between child tasks, and a pointer to where non-goals and alternatives are recorded."

Use notes_write with {"ops": [{"op": "upsert", "itemId": "3d7e91ab", "key": "feature-summary", "role": "queue", "body": "..."}]}.
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

1. **Config-driven label**: one rule for every applied transition: the `status_labels` entry for the trigger (per-root config, then the global config, then the defaults above). A `start` that lands in `terminal` uses the `complete` label; the cascade that completes a parent uses the `cascade` label, while a cascade that moves a parent into `work` (a child started, or a child was reopened) uses the `start` or `reopen` label. A configured `cancel` label takes effect (there is no hardcoded override).
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

- **`advance` `transition` result** - each successful element includes `"statusLabel"` when set.
- **`items_write` `complete_tree` result** - each applied item outcome includes `"statusLabel"` when set.
- **`items_read` views** - the `statusLabel` member is included in item views when non-null.

---

## 7. Dependency Blocking

### BLOCKS Edges

A `blocks` edge from item A to item B means: **B cannot start until A reaches terminal** (by default). The blocker is `from`.

```json
{ "ops": [ { "op": "create", "from": "3d7e91ab", "to": "8a4c2e10", "type": "blocks" } ] }
```

`type` defaults to `blocks`; the other value is `relates_to`, which never gates anything. A `transition` with `trigger: "start"` on a blocked item fails with `dependency_unmet`, and the error lists the blocking items.

### Pattern Shortcuts

Each pattern is one element: all its edges are created together or none are.

Create a linear chain in one call:

```json
{ "ops": [ { "op": "linear", "ids": ["task-a", "task-b", "task-c"] } ] }
```

`task-a` blocks `task-b`, and `task-b` blocks `task-c`. (Use real item ids or 8-hex prefixes in place of the names.)

Fan-out (one blocker, many dependents):

```json
{ "ops": [ { "op": "fan_out", "from": "task-a", "toIds": ["task-b", "task-c", "task-d"] } ] }
```

Fan-in (many blockers, one dependent):

```json
{ "ops": [ { "op": "fan_in", "fromIds": ["task-a", "task-b"], "to": "task-c" } ] }
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
{ "ops": [ { "op": "create", "from": "3d7e91ab", "to": "8a4c2e10", "type": "blocks", "unblockAt": "review" } ] }
```

`unblockAt` is rejected on a `relates_to` edge.

### Reading Edges and the Blocking Graph

`deps_read` lists the edges touching one item, with `direction` set to `incoming` (what blocks it) or `outgoing` (what it blocks), and walks the whole blocking graph around it:

```json
{ "select": "list", "item": "8a4c2e10", "direction": "incoming" }
```

```json
{ "select": "graph", "item": "8a4c2e10" }
```

### Finding Blocked Items

```json
{ "select": "blocked" }
```

`items_read` `blocked` returns the items blocked by unsatisfied dependencies (`blockType: "dependency"`) and the items explicitly in the `blocked` role (`blockType: "explicit"`), each with its `blockers`. Scope it to a subtree and ask for ancestors:

```json
{ "select": "blocked", "ancestorId": "9f3a1c22", "fields": ["ancestors"] }
```

### Cascade Behavior

A transition can re-evaluate the item's immediate parent in the same transaction (a start cascade moves a queued parent into `work`, a terminal cascade completes a parent whose children are all terminal and climbs while each ancestor qualifies, a reopen cascade reopens a terminal parent), and a cascade stopped by a gate is reported with its reason rather than failing the child's transition. The rules (lifecycle, gates, suppression reasons, what is and is not reported) are specified once, in the `advance` section of the API reference: [Cascades](./api-reference.md#advance).

### Unblock Reporting in `advance`

When a transition completes a blocking item, its result element lists `unblocked`:

```json
{
  "ok": true,
  "data": {
    "results": [{
      "ok": true,
      "itemId": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37",
      "previousRole": "work",
      "newRole": "terminal",
      "trigger": "start",
      "unblocked": [
        { "itemId": "8a4c2e10-77d1-4b3e-8f20-5c19a0b6d3e4", "title": "Downstream Task" }
      ]
    }],
    "summary": { "ok": 1, "failed": 0 }
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

Items in `unblocked` are now eligible to be started.

### Actor Attribution

Every write records *who* performed it, and in 4.0 that is the **transport principal**, never a field the caller writes. The principal is established by how the caller reached the server: `api:<id>` for the holder of a bearer or JWT credential (REST, and `/mcp` over HTTP when the REST API runs `API_AUTH_MODE=bearer|jwks`), and the single `local` principal for stdio, `/mcp` in local mode and REST `API_AUTH_MODE=none`. A request body `actor`, on any tool, is refused as `unknown_parameter`.

Key behaviors:
- **Nothing to send** - transitions and note upserts are attributed to the principal automatically
- **Cascade transitions** are attributed to the principal whose write caused them and are marked `origin: cascade` in the `items_read` `history` trail
- **Note re-upsert** replaces the attribution (last-writer-wins) but not the note's owner; another principal cannot overwrite an owned note unless its schema entry is marked `shared`
- **`items_read` `history`** includes actor/verification on transitions, for a caller who may see attribution
- **`notes_read`** includes actor/verification on notes that have them (admins, or when attribution redaction is off)
- **`local` is one principal** - every stdio or local-mode session is the same principal, so they share claims and note ownership; telling agents apart needs separate credentials or, inside a run, seat tokens (see [section 12](#12-runs-seats-and-decisions))

The `verification` record names how the principal was established (`api-jwks`, `api-bearer` or `local`). See [Actor Attribution](./api-reference.md#actor-attribution) in the API reference and [Identity Configuration](./fleet-deployment.md#identity-configuration) for the operator side.

---

## 8. Efficient Queries

### Full-Text Search (FTS5) Recipes

`items_read` `select: "search"` searches item content, note bodies and documents in one ranked list (`kinds` picks which: `item`, `note`, `document`). Use it over `list` filtering whenever you are looking for items or notes by content rather than metadata.

See [`search-and-discovery.md`](./search-and-discovery.md) for the architecture and score interpretation.

#### Recipe 1 — Find passages mentioning X across all requirements

Find any item whose title or summary mentions "authentication":

```json
{ "select": "search", "query": "authentication token", "kinds": ["item"], "limit": 20 }
```

Find notes that discuss a specific topic across the entire workspace:

```json
{ "select": "search", "query": "rate limiting strategy", "kinds": ["note"], "limit": 20 }
```

#### Recipe 2 — Search within a feature's subtree

Scope the search to descendants of a known feature using `ancestorId`:

```json
{ "select": "search", "query": "database migration", "kinds": ["item"], "ancestorId": "550e8400" }
```

```json
{ "select": "search", "query": "rollback plan", "kinds": ["note"], "ancestorId": "550e8400" }
```

This uses a recursive CTE under the hood — any descendant at any depth is included.

#### Recipe 3 — Find items that reference REQ-42 (backlinks)

Identify all items that hold a dependency edge pointing AT a specific item — e.g., everything that
blocks or relates to a requirement. That is the incoming direction of `deps_read` `list`:

```json
{ "select": "list", "item": "550e8400", "direction": "incoming" }
```

Narrow to one type — "what blocks REQ-42?":

```json
{ "select": "list", "item": "550e8400", "direction": "incoming", "type": "blocks" }
```

Each edge entry returns `{ id, from, to, type, unblockAt? }` with full UUIDs. Use `items_read` `get` with the `from` id to load full details on any item of interest.

For everything related to an item, not only its edges (links, shared tags and similar text too), use `items_read` `select: "related"`.

#### Recipe 4 — Iterative search-then-read

`search` returns references and snippets — NOT full note bodies. For full content, use a two-step
pattern:

Step 1 — find matching notes with snippet context:

```json
{ "select": "search", "query": "acceptance criteria OAuth", "kinds": ["note"], "ancestorId": "9f3a1c22", "snippet": true }
```

Step 2 - fetch the full body for any hit by the hit's `itemId` and `noteKey`:

```json
{ "select": "get", "itemId": "<itemId-from-hit>", "key": "<noteKey-from-hit>" }
```

Or list all notes for the owning item (no bodies):

```json
{ "select": "list", "itemId": "<itemId-from-hit>", "role": "queue" }
```

(Both are `notes_read` calls.)

**Why two steps?** Snippets are ~32 tokens. Full note bodies can be thousands of tokens. Loading
them selectively avoids token budget exhaustion in multi-note workspaces.

---

### Role-Based Filtering

`items_read` `select: "list"` filters by semantic role across all status names:

```json
{ "select": "list", "role": "work" }
```

Returns all items currently in the work phase.

```json
{ "select": "list", "role": "blocked", "parentId": "9f3a1c22" }
```

Returns blocked items under a specific feature.

### Two-Call Work Summary

Get a complete picture of active work in one call:

```json
{ "select": "context", "fields": ["ancestors"] }
```

With no `itemId` the `context` select is the health view: `{ active, blocked, stalled, claimSummary }`, where `active` holds the items in `work` and `review` and `blocked` the blocked ones, each with its ancestor chain:

```json
{
  "active": {
    "items": [
      {
        "id": "3d7e91ab",
        "type": "feature-task",
        "title": "Implement reset endpoint",
        "role": "work",
        "version": 4,
        "ancestors": [
          { "id": "5b21c0de", "title": "Auth System", "depth": 0 },
          { "id": "9f3a1c22", "title": "Password Reset Feature", "depth": 1 }
        ]
      }
    ],
    "page": { "total": 1, "truncated": false }
  },
  "blocked": { "items": [], "page": { "total": 0, "truncated": false } },
  "stalled": [],
  "claimSummary": { "active": 0, "expired": 0, "unclaimed": 14 }
}
```

Then get container-level counts:

```json
{ "select": "overview" }
```

**Total: 2 calls. No sequential parent-walk needed.**

### Ancestors

`ancestors` is an opt-in member (`"fields": ["ancestors"]`) of `items_read` `get` and `list`, of the `context`, `next` and `blocked` selects. Each item gets an `ancestors` array ordered root-first (direct parent last). Root items get `[]`.

```json
{ "select": "list", "role": "work", "fields": ["ancestors"] }
```

### Next Item Recommendation

```json
{ "select": "next", "limit": 3, "fields": ["ancestors"] }
```

Returns unblocked, unclaimed `queue` items ranked by priority (high first) then complexity (low first - quick wins first). `limit` defaults to 1.

Scope to a subtree:

```json
{ "select": "next", "parentId": "9f3a1c22" }
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
| `description` | string  | no       | Any string                | Short description (defaults to `""`; recommended). Fetch via `items_read` `select: "schema"`. |
| `guidance`    | string  | no       | Any string                | Longer authoring hint. Named by `guidanceKey` in the gate status; read with `fields: ["guidance"]`.  |
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

The claim mechanism prevents race conditions between independent agents competing for the same work items. It is optional: single-orchestrator deployments that serialize work dispatch do not need claims. Claims are ops of the `advance` tool (`claim`, `release`, `heartbeat`), orchestration profile only.

> **Operators:** see [Fleet Deployment Guide](./fleet-deployment.md) for fail-closed verification, `DATABASE_BUSY_TIMEOUT_MS`, capacity planning, and a Claims Troubleshooting FAQ. The op-level reference lives in [API Reference](./api-reference.md#advance).

> **Agent implementers:** the bundled Claude Code plugin under `claude-plugins/task-orchestrator/` targets default-mode single-agent orchestration. Its skills, hooks, and orchestration context assume the agent-owned phase-entry pattern (`advance` `transition` called directly) - they do not coordinate claim-then-advance sequencing. If you are building agents for a claim-based fleet, treat the bundled plugin's behavior as undefined and build claim-aware skills/hooks against the MCP tool surface directly (`advance`, `items_read` `next` and `context`). See the [Fleet Deployment Guide - Scope](./fleet-deployment.md#scope) for the public contract.

### When to Use Claims

**Skip claims if:**
- A single orchestrator dispatches work sequentially — it controls which agent gets which item.
- Your fleet has a natural partition (e.g., each agent handles a different feature tree).

**Use claims when:**
- 10 or more independent agents poll `items_read` `next` and `advance` concurrently.
- You observe agents starting the same item twice (the `next` then `transition` race).
- Crash recovery requirements demand TTL-based automatic release.

### Claim Lifecycle

The standard two-call pattern — discover then claim:

```
items_read {select: "next"}                             -> find an unclaimed item
advance    {ops: [{op: "claim", itemId, ttlSeconds}]}   -> claim it (releases any other claim this agent holds)
  claimed: true     -> proceed with the transition + work
  claim_held error  -> pick a different item (retryAfterMs available)
advance    {ops: [{op: "transition", itemId, trigger: "start"}]} -> ownership enforced: caller must be the claim holder
  ... do work ...
advance    {ops: [{op: "heartbeat", itemId}]}           -> refresh TTL (if work > TTL/2 = 450s)
advance    {ops: [{op: "transition", itemId, trigger: "complete"}]} -> ownership enforced at completion too
```

```json
{ "ops": [ { "op": "claim", "itemId": "3d7e91ab", "ttlSeconds": 900 } ] }
```

```json
{
  "ok": true,
  "data": {
    "results": [
      { "ok": true, "itemId": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37", "claimed": true, "version": 4,
        "claimedAt": "2026-10-16T13:00:00Z", "expiresAt": "2026-10-16T13:15:00Z", "originalClaimedAt": "2026-10-16T13:00:00Z" }
    ],
    "summary": { "ok": 1, "failed": 0 }
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

An actor holds one claim at a time: claiming a second item releases the first and lists it in `released`. Claim writes never bump the item's `version`.

### Serialized Find-and-Claim (Select Mode)

The two-call pattern above has an inherent race: between `next` returning an item ID and the claim locking it, another agent can claim the same item. For high-concurrency fleets, `select` mode eliminates the **user-facing** race window by resolving the filter and claiming the best match in a single call.

`items_read` `next` and the claim's `select` take the same filter shapes - `role`, `parentId`, `tags`, `priority`, `type`, `complexityMax`, `createdAfter/Before`, `roleChangedAfter/Before` and `orderBy` - backed by the same shared eligibility logic.

**Select mode lifecycle:**

```
advance {ops: [{op: "claim", select: {priority: "high", complexityMax: 4, orderBy: "oldest"}, ttlSeconds: 900}]}
  claimed: true, selected: true       -> proceed with the transition + work (itemId is in the result)
  claimed: false, no reason           -> nothing matches these filters at all; back off or wait
  claimed: false, reason + retryAfterMs -> matches exist but none is claimable now (see `excluded`); retry after `retryAfterMs`
advance {ops: [{op: "transition", itemId, trigger: "start"}]} -> ownership enforced: caller must be the claim holder
  ... do work ...
advance {ops: [{op: "heartbeat", itemId}]}                    -> refresh TTL (use the itemId from the select result)
advance {ops: [{op: "transition", itemId, trigger: "complete"}]} -> ownership enforced at completion too
```

**Fleet drain pattern using select mode:**

```json
{
  "ops": [
    {
      "op": "claim",
      "select": { "orderBy": "oldest" },
      "ttlSeconds": 900,
      "claimRef": "worker-7-drain-loop",
      "idempotencyKey": "<fresh UUID per iteration>"
    }
  ]
}
```

`orderBy: "oldest"` provides FIFO (oldest-first) draining: agents process items in creation order rather than racing to the same high-priority items. When a select claim answers `claimed: false` with no `reason`, the queue is genuinely drained - the agent can idle, exit, or poll after a delay. When it answers `claimed: false` with a `reason` (`claim_held` or `dependency_unmet`), matches exist but are all excluded right now; the agent should back off `retryAfterMs` (30000) and retry rather than treating the drain as finished. The `excluded` counts (`claimed`, `ancestorClaimed`, `dependencyBlocked`, `outOfScope`) say why.

**Select hygiene and ancestor-claim filtering.** In select mode, items whose ancestor chain contains a live claim held by a *different* agent are automatically skipped. This sub-tree isolation protects in-progress feature orchestration from fleet drain workers picking up child items. Use specific selects (`parentId`, or tag filters such as `tags: ["feature"]`) when your fleet operates at the top level; broader selects like `role: queue` will naturally skip sub-items of in-progress features without returning errors. Skipped items are counted in `excluded.ancestorClaimed` rather than surfacing information about the competing agent's identity. See [Fleet Deployment Guide - Fleet Topology Patterns](./fleet-deployment.md#fleet-topology-patterns) for recommended patterns.

`claimRef` (up to 64 chars) is echoed verbatim in the result and is useful for correlating claim results back to your agent's internal loop state without parsing `itemId` values.

**Idempotency with select mode.** An element that committed is stored for 24 hours per (principal, tool, `idempotencyKey`) and replayed verbatim on a retry with the same key - the same `itemId` is returned, and the select is **not** re-evaluated against fresh queue state. An element that failed (a `claim_held` error) is not stored, so retrying it with the same key runs it again. A select that found nothing answers `claimed: false`, which is a successful result: it is stored and replayed for 24 hours like any other, so use a fresh key to look again. Use a fresh key per claim iteration, and the same one for retries of the same iteration.

### Heartbeat Pattern

For long-running work (longer than the TTL - default 900s), send a `heartbeat` before the TTL expires to refresh it. Recommended cadence: **TTL/2 = 450s** for the 900s default - the same convention used by Consul, etcd, and other lease-based distributed systems.

```json
{ "ops": [ { "op": "heartbeat", "itemId": "3d7e91ab", "ttlSeconds": 900 } ] }
```

Heartbeating (or re-claiming) an already-held item:
- Refreshes `expiresAt` (new TTL from now)
- Preserves `originalClaimedAt` (first claim timestamp is not overwritten)

A `heartbeat` never takes an item: it is `not_claim_holder` unless the caller holds the active claim, so a heartbeat after the claim lapsed cannot silently steal the item back from whoever claimed it since.

Operators can inspect `originalClaimedAt` via `items_read` `select: "context"` with the `itemId` to distinguish freshly-claimed items from long-running renewed work.

**Heartbeat write overhead.** Every heartbeat is a row `UPDATE` on `work_items`. At 30 agents with TTL=900s, heartbeats produce approximately 4 writes/minute versus 30–60 writes/minute from real work transitions (~7% overhead). This is acceptable for v1. If writer contention from heartbeat traffic becomes a measured bottleneck, the mitigation is splitting heartbeats into a separate `claim_heartbeats` table (a non-breaking repository-layer change deferred to v1.5+).

### Effect of `complete` and `cancel` on the Claim

A `complete` or `cancel` transition - and any other transition, path, or cascade that lands the item in TERMINAL - clears the claim record as part of that same transition: `claimedBy`, `claimedAt`, `claimExpiresAt`, and `originalClaimedAt` are all reset to `null`. This applies uniformly across `advance` `transition`, `items_write` `complete_tree` and system cascades.

Consequently `reopen` always starts the item **unclaimed** - there is no residual claim to enforce ownership against, and a subsequent claimant does not need to match the item's prior holder. Sending a `release` before completing work is not necessary for claim hygiene (the terminal transition already clears it), though it remains useful for making an early release explicit in the audit trail.

### Crash Recovery via Passive Expiry

When an agent crashes or is killed, its claim expires naturally within the configured TTL (default 900s); no process clears it. Expired claims are filtered at read time, and the server records one `claim.expired` audit event per lapsed claim (hourly sweep, a transition of the item, or a contender's claim; the claim columns are left as they were):
- `items_read` `next` (default `includeClaimed: false`) excludes items with live claims but includes items with expired claims.
- `items_read` `list` with `claimStatus: "expired"` surfaces items with elapsed TTLs for operator inspection.

No manual cleanup is needed for correctness. Use `items_read` `select: "context"` with the `itemId` to confirm whether a specific item's claim has expired (`claimDetail.isExpired: true`).

### Discovery Patterns

Multi-role fleets use `next` with a `role` to target different pipeline stages:

| Agent group | Call |
|---|---|
| Work-group (implementation) | `{ "select": "next" }` (default `role: "queue"`) |
| Review-group | `{ "select": "next", "role": "review" }` |
| Triage-group | `{ "select": "next", "role": "blocked" }` |
| Fleet health operator | `{ "select": "next", "includeClaimed": true }` - includes claimed items, shows the `isClaimed` boolean |

### `includeClaimed` for Fleet Visibility

`next` with `includeClaimed: true` and `list` with `claimStatus: "expired"` are the primary operator reads for debugging stale claims:

```json
{ "select": "list", "claimStatus": "expired" }
```

Diagnose a specific stalled item (full claim detail including identity):

```json
{ "select": "context", "itemId": "3d7e91ab" }
```

The response includes `claimDetail.claimedBy`, `claimDetail.isExpired` and `claimDetail.originalClaimedAt`.

`select: "context"` with an `itemId` is the only read that exposes `claimedBy` identity - all other surfaces expose at most an `isClaimed: boolean`.

---

## 11. Resource Leasing

Resource leasing lets a work item declare — via **traits** — that it needs exclusive or advisory
access to a named shared resource (a staging slot, a test database, a deploy credential, a
fleet-wide deployment mutex) before it enters the `work` phase. The server enforces this as a
**gate inside `advance` `transition`**, the same call agents already use to advance - there is no separate
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

When an item enters WORK and an exclusive key it declares is already held, `transition` rejects
the call with a **transient** error, not a gate block:

```json
{
  "ok": false,
  "error": {
    "code": "resource_unavailable",
    "kind": "transient",
    "message": "Resource lease contended: staging-db",
    "detail": { "itemId": "3d7e91ab-5c0e-4f1a-9b2d-0a6e8c4d1f37", "resources": [ { "name": "staging-db", "mode": "exclusive" } ], "retryAfterMs": 30000 },
    "retryAfterMs": 30000
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

This is deliberately shaped differently from a gate-blocked failure (`gate_blocked` with `detail.missing` / write-more-notes)
because the fix is different: **wait and retry, or work a different item** — not "fill in more
context." `detail.resources` names the contended keys and their modes only; **the current holder's identity is
never disclosed** on this path, by design (see Guarantees below). Agents and Ralph-style loop
workers should treat `resource_unavailable` the same way they treat `claim_held` from
the `claim` op: release any claim on the item and move to a different item, never spin-retry the same
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
  paths release leases: `advance` (and the equivalent REST ops route) release inline on
  every WORK exit, and `items_write` `complete_tree` drives each item through the same `AdvanceService` pipeline,
  so lease release goes through that same work-exit path - a batch completion via `items_write` `complete_tree`
  does not orphan leases until TTL expiry.
- **A transition that acquires a lease and then fails to apply leaves no lease behind.** The lease
  acquire, the role change, and every cascade of one advance run in ONE transaction: if any of them
  fails (a DB fault), the whole transaction rolls back — the item never entered WORK and the lease
  was never committed. A fault while acquiring or releasing a lease fails the advance
  (`internal` or `unavailable`) the same way.
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
  deployments, the `claim` op is the opt-in enforcement of exactly this assumption - it binds one
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
- **No select-level awareness.** `items_read` `next` and the `claim` op's `select` mode do not know
  about resource contention — they can still hand an agent an item that will immediately reject with
  `resource_unavailable` on `start`. (`items_read` `context` with an `itemId` does report `contended` for one item.) This is a known, deliberately deferred gap (see Non-Goals in
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

> This chapter is the one place where runs, seats, tokens and decisions are explained; the other guides link here. The examples use the `runs_write`, `runs_read`, `advance`, `notes_write` and `items_read` tools. Field tables and error lists are not repeated here: they live in the [API reference](./api-reference.md#category-runs), and the config keys in the [config reference](./config-reference.md).

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

`block` and `hold` take a `reason` (`awaiting_decision`, `dependency`, `resource`, default `manual`). `awaiting_decision` carries a `decisionId` naming an open decision on that item. The reason shows on the item as `blockedReason`, and every way out of `blocked` (`resume`, `cancel`, `items_write` `complete_tree`) clears it. Answering a decision never resumes the item: the coordinator does that, after reading the answer.

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

`close` takes a reason (`completed`, `cancelled`, `failed` or `abandoned`; `budget_exhausted` is the server's own) and is allowed to the coordinator, the director profile or a human. Any reason but `completed` ends the active seats and releases the members' claims and leases; `completed` is refused while a seat is active. The answer carries a `snapshot` of what the close recorded, the stored `run.ended` event carries the run rollup computed at that moment, and `report` with `select: "rollup"` and a `runId` (see [reporting.md](./reporting.md)) answers that snapshot for a closed run, so the figures survive retention.

`runs_write` `mark` (REST only) stamps a named instant on a run, for example a retrospective marker. A mark is first-write-wins and cannot be removed, and `runs_read` `list` with `unmarked` returns the closed runs that lack it.

### Answering a decision as a single developer

A person working alone usually has one stdio server, and stdio serves the orchestration profile, which cannot answer decisions. Start a second stdio entry with `MCP_TOOL_PROFILE=director` (or point at `/mcp/director` over HTTP). That entry lists, acknowledges and answers decisions, pauses and resumes runs, and closes them. If you have no director entry and a run is paused for a limit, the only way out from the orchestration side is to `close` it. See the [director endpoint](./fleet-deployment.md#mcp-authentication---read-first) notes for the HTTP and REST forms.

---

## Quick Reference

### Common Call Patterns

Each row is the request body of one call to the named tool (orchestration profile).

| Goal                              | Call                                                            |
|-----------------------------------|-----------------------------------------------------------------|
| Check what to work on next        | `items_read` `{"select":"next","fields":["ancestors"]}`         |
| See all active work               | `items_read` `{"select":"context","fields":["ancestors"]}`      |
| See container overview            | `items_read` `{"select":"overview"}`                            |
| Check gate before advancing       | `items_read` `{"select":"context","itemId":"3d7e91ab"}`         |
| Advance to next phase             | `advance` `{"ops":[{"op":"transition","itemId":"3d7e91ab","trigger":"start"}]}` |
| Fill a note                       | `notes_write` `{"ops":[{"op":"upsert","itemId":"3d7e91ab","key":"...","role":"work","body":"..."}]}` |
| Guidance consumption              | `items_read` `context` gives `guidanceKey`; `items_read` `{"select":"schema","itemId":"3d7e91ab","fields":["guidance"]}` gives the text |
| Find blocked items                | `items_read` `{"select":"blocked","fields":["ancestors"]}`      |
| Create a dependency chain         | `deps_write` `{"ops":[{"op":"linear","ids":["a1b2c3d4","b2c3d4e5"]}]}` |
| Cancel an item                    | `advance` `{"ops":[{"op":"transition","itemId":"3d7e91ab","trigger":"cancel"}]}` |
| Reopen a terminal item            | `advance` `{"ops":[{"op":"transition","itemId":"3d7e91ab","trigger":"reopen"}]}` |
| Filter by phase                   | `items_read` `{"select":"list","role":"work"}`                  |
| Claim an item (fleet, id mode)    | `advance` `{"ops":[{"op":"claim","itemId":"3d7e91ab","ttlSeconds":900}]}` |
| Claim next eligible (select mode) | `advance` `{"ops":[{"op":"claim","select":{"orderBy":"oldest"}}]}` |
| Release a claim                   | `advance` `{"ops":[{"op":"release","itemId":"3d7e91ab"}]}`      |
| Heartbeat (refresh TTL)           | `advance` `{"ops":[{"op":"heartbeat","itemId":"3d7e91ab"}]}`    |
| Find expired claims               | `items_read` `{"select":"list","claimStatus":"expired"}`        |
| Diagnose stalled claim            | `items_read` `context` with the `itemId` - returns `claimDetail` with `claimedBy` |
| Filter next item by tag           | `items_read` `{"select":"next","tags":["task-implementation"]}` |
| FIFO queue drain                  | `items_read` `{"select":"next","orderBy":"oldest"}`             |
| Record a consumed credential/resource | `advance` `{"ops":[{"op":"transition","itemId":"3d7e91ab","trigger":"start","credentialRefs":["vault/prod-db-password"]}]}` |
| Diagnose a stuck resource lease   | `items_read` `context` with the `itemId` - `resourceLeases` block; or `GET /api/v1/resources/leases` (ADMIN sees holder actor) |
| Force-release a stuck lease (operator) | `DELETE /api/v1/resources/leases/{key}` (ADMIN capability) |
| Read a tool's own contract        | any tool, `{"describe":true}`                                   |
