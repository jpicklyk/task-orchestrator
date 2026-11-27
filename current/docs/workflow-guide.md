# MCP Task Orchestrator - Workflow Guide

This guide covers the role-based workflow, dependency blocking and runs, and efficient query patterns for MCP Task Orchestrator 4.0.

---

## Calling the tools

Read this before the first example. Every example in this guide is the JSON request body of one 4.0 tool call. The request fields, the profile of each select or op and the error codes are specified once, in the [API Reference](./api-reference.md); the examples here show how the calls fit together.

**Tools.** There are a few tools, each named for the kind of work it does: `items_read` and `items_write` for work items, `notes_read` and `notes_write` for notes, `deps_read` and `deps_write` for dependencies, `docs_read` and `docs_write` for versioned documents, `advance` for phase transitions and claims, `config` for per-root configuration, `report` for aggregate reports, `runs_read` and `runs_write` for runs and `decisions_read` and `decisions_write` for decisions. A read tool takes a `select` that names what to read; a write tool takes an `ops` array whose elements each name an `op`:

```json
{ "select": "get", "itemId": "9f3a1c22" }
```

```json
{ "ops": [ { "op": "create", "title": "Add retry", "type": "feature-task", "parentId": "9f3a1c22" } ] }
```

**Tool profiles.** The server builds one tool set per profile, and a client sees only the selects and ops of its profile (a hidden select or op looks absent). The profiles and their paths are in the [API Reference](./api-reference.md#tool-profiles). The examples below use the orchestration profile unless they say otherwise.

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

The triggers and what each does are in the [API Reference](./api-reference.md#advance); `items_read` `context` tells you the next trigger for an item.

---

## 3. Note Schemas

Note schemas are described in the [config reference](./config-reference.md) and, key by key, in [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md); the plain-language introduction is [Note Schemas](./integration-guides/note-schemas.md), and `/task-orchestrator:manage-schemas` walks you through writing one.

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
  "guidanceKey": "feature-summary"
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
      { "key": "implementation-notes", "role": "work", "required": true, "description": "Cross-child record - decisions, merges, carry-ins and deferrals no child's notes hold.", "guidance": "Cross-child record only: what no child's notes can hold. `Decisions:` ordering or interface choices made between children; `Merges:` a shared-worktree merge that changed a child's behaviour after its review (what, which child, sha); `Carry-ins:` findings routed between children (from -> to, child ids); `Deferred:` scope deliberately left to a follow-up feature (item id). Never re-list the children's commits, files, test counts or findings; each child's notes and `orchestrator-confirmation` hold them. When nothing crossed child boundaries, the whole body is the one line `No cross-child decisions; each child's notes stand alone.` and that satisfies the gate. Target ~300-1200 characters; the 3000 maxLength is a backstop, not a goal." },
      { "key": "review-checklist", "role": "review", "required": true, "description": "Quality gate - plan alignment and test quality review.", "guidance": "Verify what was built aligns with the feature-summary and each child's task-scope, and that tests cover the test strategy from the planning notes. Tag each finding spec or standards, then close with one verdict line per axis: `Spec: Pass | Pass with observations | Fail` and `Standards: Pass | Pass with observations | Fail`." }
    ],
    "features": ["seats", "dispatchBySeat", "independent_of", "rules", "seatSkills", "seatWrites", "orchestratorNotes", "resumeGate", "answerProvenance", "decisionTools", "waitingOn", "decisionAuthority", "profileSets"]
  },
  "meta": { "reqId": "k7f3q9ab" }
}
```

(Abridged: the response and each note entry also carry the other members listed in the [API Reference](./api-reference.md#items_read).) A type with no schema answers `{ "schemaFree": true }`, which is not an error.

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

- **`items_read` `select: "context"` (with `itemId`)** - `guidanceKey` at the top level names the first unfilled required note of the current phase, and `skillPointer` its (first) skill; `skillPointers` lists every skill when there are two or more and `skillMode` is `method-on-diff` when the note says so. All are omitted when every required note of the phase is filled or the entry has no guidance or skill.
- **`advance` `transition` result** - `expectedNotes` (the keys the new phase expects), `guidanceKey` and `skillPointer` (with `skillPointers` and `skillMode` as above), for the phase just entered.
- **`advance` gate error** - a `gate_blocked` error's `detail.missing[]` names the missing notes (`key`, `role`, and `seat` for a seat-aware schema).
- **`items_read` `select: "schema"`** - the full note list of a type or an item (`description`, `skill`, `skills`, `skillMode`, `lane`, `maxLength`, `seat`; the optional ones appear only when the note declares them); the per-note `guidance` text is stripped unless the call passes `fields: ["guidance"]`.

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
| `resume`   | *(null)*         | Clears the label (a configured `resume` label is applied). |
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
| `queue`    | Any role except `blocked` (immediately) |
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

Note schemas are described in the [config reference](./config-reference.md) and, key by key, in [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md); the plain-language introduction is [Note Schemas](./integration-guides/note-schemas.md), and `/task-orchestrator:manage-schemas` walks you through writing one.

---

## 10. Claim Mechanism for Multi-Agent Fleets

Claims are the `claim`, `release` and `heartbeat` ops of `advance` ([API Reference](./api-reference.md#advance)); rolling them out and running a fleet on them is in [Rolling Out Claim Mode](./fleet-deployment.md#rolling-out-claim-mode) and [Operating a Live Fleet](./fleet-deployment.md#operating-a-live-fleet).

---

## 11. Resource Leasing

Resource leasing is operator-configured and is covered in [Resource Leasing](./fleet-deployment.md#resource-leasing).

---

## 12. Runs, Seats and Decisions

> This chapter is the one place where runs, seats, tokens and decisions are explained; the other guides link here. The examples use the `runs_write`, `runs_read`, `decisions_write`, `decisions_read`, `advance`, `notes_write` and `items_read` tools. Field tables and error lists are not repeated here: they live in the [API reference](./api-reference.md#category-runs), and the config keys in the [config reference](./config-reference.md).

A **run** is a bounded pass over a set of items of one root, optionally capped. Everything before this chapter works without one. A run adds four things on top: scoped credentials for the agents that work the items (seat tokens), enforcement of who may do what to a member, a liveness view, and a channel for questions that need a human or a director (decisions).

### Why two session layers

One session used to be both the user's point of contact and the driver of every run. That filled its context, made it unavailable while a run was in flight, and left the state it held fragile across compaction. The two layers split those jobs so each context is purposeful and clearable, token cost stays proportional to the work, and the server, not a prompt, holds state and boundaries.

- **Seat**: one agent on one phase of one item (planner, implementer, test author, reviewer), with one dispatch and a fresh context.
- **Orchestrator**: drives the work. It plans, sizes, dispatches seats and makes every later transition. The user's own session is the orchestrator in orchestration mode (the default) and can hold a run itself; a run's orchestrator may also be placed in another session or a background agent. Its scope is ad hoc items or one run; a run's orchestrator presents that run's token, and one in a background agent is disposable.
- **Director**: decides on the user's behalf. Director mode is an opt-in session mode in which the user's session acts on the board, at the user's instruction, instead of on the code. `/task-orchestrator:init` registers the director MCP entry by default, because decisions are answered through it; `--no-director` opts out.

"Director" names two things: a server tool profile (`/mcp/director`, `MCP_TOOL_PROFILE=director`) and a session mode entered with `/task-orchestrator:director`. The orchestrator is not a seat: the run token of a run writes the notes labelled `seat: orchestrator`.

A director earns its keep when the work is long or wide, for four reasons: its context stays lean and long-lived because it holds decisions and rollups rather than code, it is one decision inbox across runs, the user's session stays free to talk while a run's orchestrator drives a wave, and more parallel and longer-running work fits than one session can hold. For small work or one short wave, orchestration mode is enough.

### A run, and when to open one

Open a run when an orchestrator is about to hand several items to several agents at once and you want the server, not the prompt, to hold the boundaries: which agent may touch which item, how many attempts and seats it may use, and when a person has to weigh in. A single agent working one item needs none of this.

```json
{"ops": [{"op": "open", "rootId": "1be2db5e", "items": ["a1b2c3d4", "e5f6a7b8"], "title": "Docs wave",
          "budget": {"attemptsPerSeat": 3, "maxActiveSeats": 4}}]}
```

A run is `open`, may be `paused`, and ends `closed` with a terminal reason. An item can be in only one run that is not closed. The budget is optional and can only sit at or under the ceilings in the [`runs:` config](./config-reference.md#runs-runs); `update` can lower it later.

### Tokens

`open` returns an **orchestrator token** (the `orchestratorToken` field), shown once. Deprecated aliases (4.x, removed in 5.0): `coordinatorToken`, `coordinatorSeenAt` and `coordinatorStalled` are returned beside `orchestratorToken`, `orchestratorSeenAt` and `orchestratorStalled` with the same values; the stored seat value `coordinator` and the `required` values of a `forbidden` refusal keep their names. The run's orchestrator is whoever presents it, as the top-level `seatToken` field next to `select` or `ops`. `assign` (orchestrator only) hands out a **seat token** for one member item and one seat, also shown once:

```json
{"seatToken": "<orchestratorToken>",
 "ops": [{"op": "assign", "runId": "7c0f3a91", "itemId": "a1b2c3d4", "seat": "implementer"}]}
```

A lost orchestrator token cannot be recovered (close the run and open a new one); the other token rules are in the Runs category of the [API Reference](./api-reference.md#category-runs).

### Seats in a run

Seats come from the schema (`seats:` in the [config](./config-reference.md#seat-transitions)). Inside a run that is not closed, on a member whose pinned schema declares seats, the server enforces them.

The enforcement rules (what a seat token may start, which note keys it may write, and the `seat_forbidden` and `note_owned_by_other` refusals) are in [Seat transitions](./config-reference.md#seat-transitions).

```json
{"seatToken": "<seatToken>",
 "ops": [{"op": "transition", "itemId": "a1b2c3d4", "trigger": "start"}]}
```

Outside a run, seats stay an orchestration signal and nothing is enforced. A seat ends its own assignment with `{"op": "end_seat", "outcome": "done", "summary": "..."}` under its own token.

### Cascades into seat-enforced members

A cascade that would move a member parent is applied only if the caller could have sent the equivalent trigger to that parent directly; otherwise it is reported as `blocked: seat`. Advance the parent with the orchestrator token or a human credential (see the [cascade rules](./api-reference.md#advance)).

### Liveness and the frontier

A seat or the orchestrator is **stalled** when it has been quiet for longer than the run's `stallAfterSeconds`; a read through a `select` tool does not count as activity, and stalled is only a view. `runs_read` with `select: "frontier"` is the one read that tells the orchestrator what to do next (see the Runs category of the [API Reference](./api-reference.md#category-runs)).

### Why an item is blocked

`block` and `hold` take a `reason` (see the [API Reference](./api-reference.md#advance)). Answering a decision never resumes the item: the orchestrator does that, after reading the answer.

### Decisions

A decision is a question with 1 to 8 options that a seat or the orchestrator raises when it needs a person or a director. Its **class** (see [decision classes](./config-reference.md#decision-classes-decision_classes)) sets who may answer, the default severity and when it becomes overdue.

```json
{"seatToken": "<seatToken>",
 "ops": [{"op": "raise", "class": "deviation", "dedupeKey": "api-shape-1", "block": true,
          "question": "The spec says X but the code does Y. Which wins?",
          "options": [{"key": "spec", "label": "Follow the spec"}, {"key": "code", "label": "Keep the code"}],
          "recommended": "spec",
          "context": "The spec's acceptance criterion 2 asks for a 404; the handler returns 410 and two clients depend on it.",
          "links": [{"kind": "note", "ref": "item:<item-id>#specification"}]}]}
```

A raise carries its evidence: `context` (at most 1000 characters) says what the answer needs, and `links` (at most 10) points at the notes or commits it came from, so whoever answers decides from the request alone. Re-raising the same `dedupeKey` returns the existing decision. `block: true` raises and blocks the item in one step. The class sets who may `ack` or `answer` (`human-only`, `director-or-human` or `any-actor`); the matrix is in the [API reference](./api-reference.md#decisions_write). The example above is a `decisions_write` call.

The director (or the human) lists and reads decisions with `decisions_read` `list`, then `ack`s, `delegate`s, `escalate`s and `answer`s with `decisions_write`. `delegate` routes a pending decision to the director and `escalate` routes it to the user with a reason; the list filters on `waitingOn`. The `runs_read` `decisions` and `decision` selects and the `runs_write` decision ops still work as deprecated aliases (removed in 5.0). An answer is final. The raiser or the run's orchestrator can `withdraw` a pending decision, except the ones the server raises for a limit. When a class has `expires_after`, an unanswered decision becomes overdue and reads one step more urgent; the server does nothing else about it.

### Budgets, caps, concurrency and pauses

A run can carry an attempt cap per (item, seat) and a cap on concurrently active seats; both are checked on `assign`. Token and cost budgets and agent-reported usage were removed in 4.0.1 (a run still reports `usage.observedTokens`, the server's own estimate, which enforces nothing); see [Run Budgets and Caps](fleet-deployment.md#run-budgets-and-caps).

A run paused for a limit is resumed by the answer to its decision, or ended by a `close`.

Operator-side detail, including what the numbers mean and what is only an estimate, is in [Run Budgets and Caps](./fleet-deployment.md#run-budgets-and-caps).

### Closing a run

`close` is allowed to the orchestrator, the director profile or a human; `report` with `select: "rollup"` and a `runId` (see [reporting.md](./reporting.md)) answers the run's snapshot for a closed run, and `runs_write` `mark` (REST only) stamps a named instant on a run.

### Answering a decision as a single developer

A person working alone usually has one stdio server, and stdio serves the orchestration profile, which cannot answer decisions. `/task-orchestrator:init` registers that second entry by default (`--no-director` opts out); by hand, start a second stdio entry with `MCP_TOOL_PROFILE=director` (or point at `/mcp/director` over HTTP). That entry lists, acknowledges and answers decisions, pauses and resumes runs, and closes them. If you have no director entry and a run is paused for a limit, the only way out from the orchestration side is to `close` it. See the [director endpoint](./fleet-deployment.md#mcp-authentication---read-first) notes for the HTTP and REST forms. The director path is hygiene that keeps a session at altitude, not a security boundary.

