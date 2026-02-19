# MCP Task Orchestrator v3 — API Reference

## Overview

The v3 server exposes 13 MCP tools organized around a single **WorkItem** graph model. Every
entity — whether a project, feature, or task — is a WorkItem with a `role` (queue, work, review,
blocked, terminal), optional `parentId`, and optional `tags` that drive note-schema gate
enforcement. Notes are first-class keyed documents attached to items. Dependencies link items with
typed blocking or relational edges.

## Tool Categories

| Tool | Category | R/W | Description |
|---|---|---|---|
| `manage_items` | Hierarchy & CRUD | Write | Create, update, or delete WorkItems |
| `query_items` | Hierarchy & CRUD | Read | Get, search, or overview WorkItems |
| `create_work_tree` | Hierarchy & CRUD | Write | Atomically create root + children + deps + notes |
| `complete_tree` | Hierarchy & CRUD | Write | Batch-complete descendants in topological order |
| `manage_notes` | Notes | Write | Upsert or delete Notes on WorkItems |
| `query_notes` | Notes | Read | Get a single note or list notes for an item |
| `manage_dependencies` | Dependencies | Write | Create or delete dependency edges |
| `query_dependencies` | Dependencies | Read | Query deps with direction filter and optional BFS traversal |
| `advance_item` | Workflow | Write | Trigger-based role transitions with cascade and gate enforcement |
| `get_next_status` | Workflow | Read | Read-only transition recommendation for a single item |
| `get_context` | Workflow | Read | Context snapshot: item mode, session resume, or health check |
| `get_next_item` | Workflow | Read | Priority-ranked recommendation of next actionable item |
| `get_blocked_items` | Workflow | Read | All items blocked by dependency or explicit block trigger |

---

## Category: Hierarchy & CRUD

### manage_items

**Purpose.** Write operations for WorkItems: batch-create, partial-update, or batch-delete. Depth
is computed automatically from the parent; the maximum nesting depth is 3.

**Operations.** `create`, `update`, `delete`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `create`, `update`, `delete` |
| `items` | array | Yes (create/update) | Array of item objects |
| `ids` | array | Yes (delete) | Array of item UUIDs to delete |
| `parentId` | string (UUID) | No | Shared default parent for all created items; per-item `parentId` overrides this |
| `recursive` | boolean | No | Delete all descendants before deleting the target items (default: false) |
| `requiresVerification` | boolean | No | **Top-level `requiresVerification` is ignored.** Set it on individual items in the `items` array instead. |

**Item object fields (create):**

| Field | Type | Required | Default |
|---|---|---|---|
| `title` | string | Yes | — |
| `description` | string | No | null |
| `summary` | string | No | `""` |
| `role` | string | No | `queue` |
| `statusLabel` | string | No | null |
| `priority` | string | No | `medium` |
| `complexity` | integer (1–10) | No | null (not set) |
| `parentId` | string (UUID) | No | shared `parentId` or null |
| `tags` | string | No | null |
| `metadata` | string | No | null |
| `requiresVerification` | boolean | No | false |

**Item object fields (update):** Same fields as create plus required `id` (UUID). Only provided
fields are changed; omitted fields retain existing values. Setting `parentId` to JSON null moves
the item to root.

**Response (update).**

```json
{
  "items": [
    { "id": "uuid", "modifiedAt": "2025-01-01T00:00:00Z", "requiresVerification": false }
  ],
  "updated": 1,
  "failed": 0
}
```

**Examples.**

```json
// Create two child items under a shared parent
{
  "operation": "create",
  "parentId": "550e8400-e29b-41d4-a716-446655440000",
  "items": [
    { "title": "Design API schema", "priority": "high", "tags": "task-implementation" },
    { "title": "Write unit tests", "priority": "medium" }
  ]
}

// Partial update
{
  "operation": "update",
  "items": [
    { "id": "550e8400-e29b-41d4-a716-446655440001", "priority": "high", "complexity": 3 }
  ]
}

// Recursive delete
{
  "operation": "delete",
  "ids": ["550e8400-e29b-41d4-a716-446655440000"],
  "recursive": true
}
```

**Response (delete).**

```json
{
  "ids": ["550e8400-e29b-41d4-a716-446655440000"],
  "deleted": 5,
  "failed": 0,
  "descendantsDeleted": 4
}
```

`descendantsDeleted` is only present when `recursive: true` and descendants were actually deleted; it counts the number of descendant items removed (not including the root items listed in `ids`). The `deleted` count includes both the root items and their descendants. Without `recursive: true`, deleting an item with children fails proactively (via a child-count check, not a DB constraint) with an error message listing the child count.

**Response (create).**

```json
{
  "items": [
    {
      "id": "uuid",
      "title": "Design API schema",
      "depth": 1,
      "role": "queue",
      "priority": "high",
      "requiresVerification": false,
      "tags": "task-implementation",
      "expectedNotes": [
        { "key": "requirements", "role": "queue", "required": true, "description": "...", "exists": false }
      ]
    }
  ],
  "created": 1,
  "failed": 0
}
```

`expectedNotes` is included only when the item's `tags` match a configured note schema. Check it
immediately after creation to know which notes to fill before calling `advance_item(trigger="start")`.

---

### query_items

**Purpose.** Read-only queries for WorkItems: full fetch by ID, filtered search with pagination, or
hierarchical overview.

**Operations.** `get`, `search`, `overview`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `get`, `search`, `overview` |
| `id` | string (UUID) | Yes (get) | Item to fetch |
| `itemId` | string (UUID) | No (overview) | Scope overview to a specific item; omit for global root overview |
| `parentId` | string (UUID) | No (search) | Filter by parent |
| `depth` | integer | No (search) | Filter by depth level |
| `role` | string | No (search) | Filter by role: `queue`, `work`, `review`, `blocked`, `terminal` |
| `priority` | string | No (search) | Filter: `high`, `medium`, `low` |
| `tags` | string | No (search) | Comma-separated tags filter (OR logic) |
| `query` | string | No (search) | Text search in title and summary |
| `createdAfter` | string (ISO 8601) | No (search) | Timestamp lower bound |
| `createdBefore` | string (ISO 8601) | No (search) | Timestamp upper bound |
| `modifiedAfter` | string (ISO 8601) | No (search) | Modification lower bound |
| `modifiedBefore` | string (ISO 8601) | No (search) | Modification upper bound |
| `roleChangedAfter` | string (ISO 8601) | No (search) | Items whose role changed after this time |
| `roleChangedBefore` | string (ISO 8601) | No (search) | Items whose role changed before this time |
| `sortBy` | string | No (search) | One of: `title`, `priority`, `complexity`, `createdAt`, `modifiedAt` |
| `sortOrder` | string | No (search) | `asc` or `desc` (default: `desc`) |
| `limit` | integer | No | Max results (default: 50 for search, 20 for overview) |
| `offset` | integer | No (search) | Skip N items for pagination (default: 0) |
| `includeAncestors` | boolean | No (get/search) | Include `ancestors` array on each item (default: false) |
| `includeChildren` | boolean | No (overview global) | Include direct children on each root item (default: false) |

**Examples.**

```json
// Fetch single item with ancestor breadcrumbs
{ "operation": "get", "id": "550e8400-e29b-41d4-a716-446655440001", "includeAncestors": true }

// Search with pagination
{ "operation": "search", "role": "work", "priority": "high", "limit": 20, "offset": 0 }

// Scoped overview of a feature's children
{ "operation": "overview", "itemId": "550e8400-e29b-41d4-a716-446655440000" }
```

**Response (search).**

```json
{
  "items": [
    { "id": "uuid", "parentId": "uuid", "title": "...", "role": "work", "priority": "high", "depth": 1, "tags": null }
  ],
  "total": 42,
  "returned": 20,
  "limit": 20,
  "offset": 0
}
```

Search returns minimal fields (`id`, `parentId`, `title`, `role`, `priority`, `depth`, `tags`).
Use `get` for full item JSON including `description`, `summary`, `statusLabel`, timestamps, and
`roleChangedAt`.

**Response (overview — scoped mode, with `itemId`).**

```json
{
  "item": { "id": "uuid", "title": "Auth Feature", "role": "work", "priority": "high", "depth": 1, ... },
  "childCounts": { "queue": 2, "work": 1, "review": 0, "blocked": 0, "terminal": 1 },
  "children": [
    { "id": "uuid", "parentId": "uuid", "title": "Design login flow", "role": "terminal", "priority": "high", "depth": 2, "tags": null }
  ]
}
```

Scoped overview returns the full item JSON in `item`, a count per role in `childCounts`, and a minimal JSON list of direct children in `children`. The global overview (no `itemId`) returns a flat `items` array of root items each with `id`, `title`, `role`, `priority`, and `childCounts`. In global mode `total` reflects the count of root items returned (not a total-in-DB count).

---

### create_work_tree

**Purpose.** Atomically create a root WorkItem, optional child items, optional dependency edges
between them, and optional blank notes — all in a single call. Eliminates the round-trips required
when calling `manage_items`, `manage_dependencies`, and `manage_notes` separately.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root` | object | Yes | Root item spec: `{ title, priority?, tags?, summary?, description?, requiresVerification? }` |
| `parentId` | string (UUID) | No | Existing parent; root depth = parent.depth + 1 |
| `children` | array | No | Child item specs: `[{ ref, title, priority?, tags?, summary?, description?, requiresVerification? }]`. `ref` is a local name used in `deps`. |
| `deps` | array | No | Dependency specs: `[{ from: ref, to: ref, type?: BLOCKS\|IS_BLOCKED_BY\|RELATES_TO, unblockAt?: queue\|work\|review\|terminal }]`. Use `"root"` to reference the root item. |
| `createNotes` | boolean | No | Auto-create blank notes for each item from its tag schema (default: false) |

Depth cap: root must be at depth < 3 (i.e., root can be at depth 0, 1, or 2). Children are always root.depth + 1, so children can reach depth 3 (when root is at depth 2).

**Example.**

```json
{
  "root": { "title": "Authentication Feature", "priority": "high", "tags": "feature" },
  "children": [
    { "ref": "t1", "title": "Design login flow", "priority": "high" },
    { "ref": "t2", "title": "Implement JWT handler", "priority": "high" },
    { "ref": "t3", "title": "Write integration tests", "priority": "medium" }
  ],
  "deps": [
    { "from": "t1", "to": "t2", "type": "BLOCKS" },
    { "from": "t2", "to": "t3", "type": "BLOCKS" }
  ],
  "createNotes": false
}
```

**Response.**

```json
{
  "root": { "id": "uuid", "title": "Authentication Feature", "role": "queue", "depth": 0, "tags": "feature" },
  "children": [
    { "ref": "t1", "id": "uuid", "title": "Design login flow", "role": "queue", "depth": 1, "tags": null },
    { "ref": "t2", "id": "uuid", "title": "Implement JWT handler", "role": "queue", "depth": 1, "tags": null }
  ],
  "dependencies": [
    { "id": "uuid", "fromRef": "t1", "toRef": "t2", "type": "BLOCKS" }
  ],
  "notes": []
}
```

`tags` is included on both `root` and each child when it was set; when not set, the field is omitted (not null). When `createNotes=true` and items have tags matching a schema, the `notes` array is populated with created note entries:

```json
"notes": [
  { "itemRef": "t1", "key": "acceptance-criteria", "role": "queue", "id": "uuid" },
  { "itemRef": "t1", "key": "done-criteria", "role": "work", "id": "uuid" }
]
```

When `createNotes=false` (default) or no items match a schema, `notes` is `[]`.

---

### complete_tree

**Purpose.** Batch-complete (or cancel) all descendants of a root item, or an explicit list of
items, in topological dependency order. Gate enforcement applies per item: if required notes are
missing, that item fails and its downstream dependents within the set are skipped.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `rootId` | string (UUID) | Conditionally | Complete all **descendants** of this item. The root item itself is NOT completed — only its descendants are processed. Mutually exclusive with `itemIds`. |
| `itemIds` | array | Conditionally | Explicit list of item UUIDs to complete. Mutually exclusive with `rootId`. |
| `trigger` | string | No | `complete` (default) or `cancel`. See gate enforcement note below. |

Exactly one of `rootId` or `itemIds` must be provided.

**Gate enforcement and `trigger`:** When `trigger="complete"`, gate enforcement applies — items whose tags match a note schema must have all required notes filled before completing; items that fail gating are recorded as `gateErrors` and their dependents within the set are skipped. When `trigger="cancel"`, gate enforcement is bypassed — all items in the set are cancelled regardless of note state.

**Example.**

```json
{ "rootId": "550e8400-e29b-41d4-a716-446655440000", "trigger": "complete" }
```

**Response.**

```json
{
  "results": [
    { "itemId": "uuid", "title": "Design login flow", "applied": true, "trigger": "complete" },
    { "itemId": "uuid", "title": "Implement handler", "applied": false, "gateErrors": ["missing: done-criteria"] },
    { "itemId": "uuid", "title": "Write tests", "applied": false, "skipped": true, "skippedReason": "dependency gate failed" }
  ],
  "summary": { "total": 3, "completed": 1, "skipped": 1, "gateFailures": 1 }
}
```

**`skippedReason` values:** Items can be skipped for two reasons:
- `"dependency gate failed"` — a blocker item in the same target set failed its gate check or failed to apply, and this item is a downstream dependent.
- `"Cannot transition"` (or a specific error message) — the item itself could not be resolved for transition (e.g., it is already terminal or the role is incompatible with the trigger).

**`summary` fields:** `total` = completed + skipped + gateFailures. `completed` = items successfully transitioned. `skipped` = items skipped due to upstream gate/apply failures or items already terminal. `gateFailures` = items that failed gate checks (missing required notes); their downstream dependents are counted in `skipped`.

---

## Category: Notes

### manage_notes

**Purpose.** Write operations for Notes: batch-upsert (create-or-update by `(itemId, key)`) or
delete by IDs, by item, or by item and key.

**Operations.** `upsert`, `delete`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `upsert`, `delete` |
| `notes` | array | Yes (upsert) | Array of note objects: `{ itemId, key, role, body? }` |
| `ids` | array | No (delete) | Array of note UUIDs to delete |
| `itemId` | string (UUID) | No (delete) | Delete all notes for this WorkItem (or specific note with `key`) |
| `key` | string | No (delete) | With `itemId`: delete the single note matching this key |

When both `ids` and `itemId` are provided, the delete is **additive**: notes matched by `ids` are deleted first, then notes matched by `itemId` (optionally scoped by `key`) are deleted. Both deletions contribute to the final `deleted` count. Deleting a non-existent note by `(itemId, key)` is a silent no-op (returns success with that note not counted in `deleted`).

**Note object fields (upsert):**

| Field | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | The WorkItem this note belongs to |
| `key` | string | Yes | Logical name for this note (e.g., `requirements`, `done-criteria`) |
| `role` | string | Yes | Workflow phase: `queue`, `work`, or `review` |
| `body` | string | No | Note content (default: `""`) |

The `(itemId, key)` pair is unique — upserting with an existing pair updates the note in place
(preserving its UUID).

**Examples.**

```json
// Fill required notes before starting an item
{
  "operation": "upsert",
  "notes": [
    {
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "key": "requirements",
      "role": "queue",
      "body": "The handler must validate JWT signatures and return 401 on failure."
    }
  ]
}

// Delete all notes for an item
{ "operation": "delete", "itemId": "550e8400-e29b-41d4-a716-446655440001" }
```

**Response (upsert).**

```json
{
  "notes": [
    { "id": "uuid", "itemId": "uuid", "key": "requirements", "role": "queue" }
  ],
  "upserted": 1,
  "failed": 0
}
```

---

### query_notes

**Purpose.** Read-only queries for Notes: fetch a single note by UUID, or list all notes for a
WorkItem with optional role filtering.

**Operations.** `get`, `list`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `get`, `list` |
| `id` | string (UUID) | Yes (get) | Note UUID |
| `itemId` | string (UUID) | Yes (list) | WorkItem whose notes to list |
| `role` | string | No (list) | Filter by phase: `queue`, `work`, or `review` |
| `includeBody` | boolean | No (list) | Include note body in response (default: true); set false for token efficiency |

**Examples.**

```json
// List queue-phase notes for an item, bodies omitted
{ "operation": "list", "itemId": "550e8400-e29b-41d4-a716-446655440001", "role": "queue", "includeBody": false }
```

**Response (list).**

```json
{
  "notes": [
    {
      "id": "uuid",
      "itemId": "uuid",
      "key": "requirements",
      "role": "queue",
      "body": "The handler must...",
      "createdAt": "2025-01-01T00:00:00Z",
      "modifiedAt": "2025-01-01T00:00:00Z"
    }
  ],
  "total": 1
}
```

---

## Category: Dependencies

### manage_dependencies

**Purpose.** Create or delete dependency edges between WorkItems. Create supports an explicit
`dependencies` array (atomic batch) or topology pattern shortcuts (`linear`, `fan-out`, `fan-in`).

**Operations.** `create`, `delete`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `create`, `delete` |
| `dependencies` | array | Cond. (create) | Explicit deps: `[{ fromItemId, toItemId, type?, unblockAt? }]`. Mutually exclusive with `pattern`. |
| `pattern` | string | Cond. (create) | Shortcut: `linear`, `fan-out`, or `fan-in`. Mutually exclusive with `dependencies`. |
| `type` | string | No | Shared default type: `BLOCKS` (default), `IS_BLOCKED_BY`, `RELATES_TO` |
| `unblockAt` | string | No | Shared default threshold: `queue`, `work`, `review`, `terminal` (default: terminal) |
| `itemIds` | array | Yes (linear) | Ordered UUIDs: A→B, B→C, C→D |
| `source` | string (UUID) | Yes (fan-out) | Source item |
| `targets` | array | Yes (fan-out) | Target item UUIDs |
| `sources` | array | Yes (fan-in) | Source item UUIDs |
| `target` | string (UUID) | Yes (fan-in) | Target item |
| `id` | string (UUID) | Cond. (delete) | Delete a single dependency by its UUID |
| `fromItemId` | string (UUID) | Cond. (delete) | Source side for delete-by-relationship |
| `toItemId` | string (UUID) | Cond. (delete) | Target side for delete-by-relationship |
| `deleteAll` | boolean | No (delete) | Delete ALL deps for `fromItemId` or `toItemId` |

The `dependencies` array create is atomic: all succeed or all fail (cycle and duplicate detection
spans the entire batch).

**Examples.**

```json
// Explicit batch with unblockAt
{
  "operation": "create",
  "dependencies": [
    { "fromItemId": "uuid-a", "toItemId": "uuid-b", "type": "BLOCKS", "unblockAt": "terminal" }
  ]
}

// Linear chain shortcut
{
  "operation": "create",
  "pattern": "linear",
  "itemIds": ["uuid-a", "uuid-b", "uuid-c"]
}

// Delete by relationship
{
  "operation": "delete",
  "fromItemId": "uuid-a",
  "toItemId": "uuid-b"
}
```

**Response (create).**

```json
{
  "dependencies": [
    { "id": "uuid", "fromItemId": "uuid-a", "toItemId": "uuid-b", "type": "BLOCKS", "unblockAt": "terminal" }
  ],
  "created": 1
}
```

`unblockAt` is only included in the response when it was explicitly set. When `unblockAt` is null (the default), it is omitted from each dependency object in the response. All pattern shortcuts (`linear`, `fan-out`, `fan-in`) return the same `dependencies` array response shape as the explicit array create.

**Validation Failure Response** (any validation error: self-dependency, cycle, RELATES_TO+unblockAt, invalid threshold, etc.):

```json
{
  "dependencies": [],
  "created": 0,
  "failed": 1,
  "failures": [{ "index": 0, "error": "A dependency cannot reference the same item on both sides" }]
}
```

Note: atomicity is preserved — either all dependencies are created or none. On any validation failure, `created` is always 0 and `failures` contains a single entry describing the rejection reason. The `failures[].index` field is 0-based (the first item is index 0).

**Constraint: `RELATES_TO` and `unblockAt`.** Specifying `unblockAt` on a `RELATES_TO` dependency is a validation error. `RELATES_TO` dependencies have no blocking semantics and do not support an unblock threshold; providing one will return a validation failure response.

**Response (delete by relationship).**

```json
{
  "fromItemId": "uuid-a",
  "toItemId": "uuid-b",
  "deleted": 1
}
```

**Response (delete all by item).**

```json
{
  "itemId": "uuid-a",
  "deleted": 3
}
```

When `deleteAll=true`, provide either `fromItemId` or `toItemId` (not both required). All dependencies attached to that item (in either direction) are deleted, and the response contains the `itemId` and `deleted` count.

---

### query_dependencies

**Purpose.** Read-only dependency queries with direction and type filtering, optional WorkItem
detail enrichment, and optional BFS graph traversal.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | WorkItem to query dependencies for |
| `direction` | string | No | `incoming`, `outgoing`, or `all` (default: `all`). Incoming = things that block this item; outgoing = things this item blocks. |
| `type` | string | No | Filter: `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO` |
| `includeItemInfo` | boolean | No | Include title, role, priority for related items (default: false) |
| `neighborsOnly` | boolean | No | When false, perform BFS graph traversal returning a topologically-ordered chain and max depth (default: true) |

**Examples.**

```json
// Incoming blocking dependencies
{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "direction": "incoming", "includeItemInfo": true }

// Full dependency graph traversal
{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "neighborsOnly": false }
```

**Response.**

```json
{
  "dependencies": [
    {
      "id": "uuid",
      "fromItemId": "uuid-a",
      "toItemId": "uuid-b",
      "type": "BLOCKS",
      "unblockAt": "terminal",
      "effectiveUnblockRole": "terminal",
      "fromItem": { "title": "Design API", "role": "terminal", "priority": "high" }
    }
  ],
  "counts": { "incoming": 1, "outgoing": 0, "relatesTo": 0 },
  "graph": { "chain": ["uuid-a", "uuid-b"], "depth": 1 }
}
```

`graph` is only included when `neighborsOnly=false`.

---

## Category: Workflow

### advance_item

**Purpose.** Trigger-based role transitions for WorkItems with dependency validation, note-schema
gate enforcement, cascade detection, and unblock reporting. Supports batch transitions.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `transitions` | array | Yes | Array of transition objects: `[{ itemId, trigger, summary? }]` |

**Transition object fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | Item to transition |
| `trigger` | string | Yes | One of: `start`, `complete`, `block`, `hold`, `resume`, `cancel` |
| `summary` | string | No | Optional annotation stored on the transition record |

**Trigger effects:**

| Trigger | Effect |
|---|---|
| `start` | QUEUE→WORK, WORK→REVIEW (or TERMINAL if no review phase in schema), REVIEW→TERMINAL |
| `complete` | Any non-terminal, non-blocked → TERMINAL |
| `block` / `hold` | Any non-terminal → BLOCKED (saves `previousRole`) |
| `resume` | BLOCKED → `previousRole` |
| `cancel` | Any non-terminal → TERMINAL with `statusLabel="cancelled"` |

**Gate enforcement.** When the item's `tags` match a configured note schema:
- `start`: required notes for the current phase must exist and be filled.
- `complete`: all required notes across all phases must be filled.

**Start cascade.** When a child item transitions to WORK, the parent is automatically advanced from QUEUE to WORK if it is still in QUEUE (same cascade logic applies up the ancestor chain). This appears in `cascadeEvents` in the response with `trigger="cascade"`.

**Terminal cascade.** When a child item reaches TERMINAL, the parent may also automatically advance if all its children are terminal. Both cascade types are recorded in `cascadeEvents`.

**Examples.**

```json
// Single transition
{ "transitions": [{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "trigger": "start" }] }

// Batch
{
  "transitions": [
    { "itemId": "uuid-1", "trigger": "complete" },
    { "itemId": "uuid-2", "trigger": "complete" }
  ]
}
```

**Response (success).**

```json
{
  "results": [
    {
      "itemId": "uuid",
      "previousRole": "queue",
      "newRole": "work",
      "trigger": "start",
      "applied": true,
      "cascadeEvents": [
        { "itemId": "uuid-parent", "title": "Auth Feature", "previousRole": "queue", "targetRole": "work", "applied": true }
      ],
      "unblockedItems": [{ "itemId": "uuid-next", "title": "Next task" }],
      "expectedNotes": [
        { "key": "done-criteria", "role": "work", "required": true, "description": "...", "exists": false }
      ]
    }
  ],
  "summary": { "total": 1, "succeeded": 1, "failed": 0 },
  "allUnblockedItems": [{ "itemId": "uuid-next", "title": "Next task" }]
}
```

`unblockedItems` and `allUnblockedItems` are always present (as `[]` when empty). `cascadeEvents` is always present (as `[]` when no cascades occurred). `expectedNotes` is always present (as `[]` when no schema matches the item's tags).

**Response (failed transition).** When `applied: false`, the result shape differs from the success shape:

```json
{
  "results": [
    {
      "itemId": "uuid",
      "trigger": "start",
      "applied": false,
      "error": "Gate check failed: required notes not filled for queue phase: requirements",
      "blockers": [
        { "fromItemId": "uuid-blocker", "currentRole": "queue", "requiredRole": "terminal" }
      ]
    }
  ],
  "summary": { "total": 1, "succeeded": 0, "failed": 1 },
  "allUnblockedItems": []
}
```

`blockers` is only present when the transition failed due to dependency constraints. `error` contains a human-readable description of why the transition was rejected. Note that `previousRole`, `newRole`, and `expectedNotes` are absent from failed results.

---

### get_next_status

**Purpose.** Read-only status progression recommendation for a single WorkItem. Returns whether
the item is Ready to advance, Blocked, or Terminal, without making any changes.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | WorkItem to analyze |

**Example.**

```json
{ "itemId": "550e8400-e29b-41d4-a716-446655440001" }
```

**`recommendation` values:** `"Ready"`, `"Blocked"`, or `"Terminal"`.

**Response.**

```json
// Ready — item can advance
{
  "recommendation": "Ready",
  "currentRole": "queue",
  "nextRole": "work",
  "trigger": "start",
  "progressionPosition": "1/3"
}

// Blocked by dependency
{
  "recommendation": "Blocked",
  "currentRole": "queue",
  "blockers": [
    { "fromItemId": "uuid-blocker", "currentRole": "queue", "requiredRole": "terminal" }
  ]
}

// Blocked — item is explicitly in BLOCKED role (set via block/hold trigger)
{
  "recommendation": "Blocked",
  "currentRole": "blocked",
  "suggestion": "Use 'resume' trigger to return to previous role"
}

// Terminal
{ "recommendation": "Terminal", "currentRole": "terminal", "reason": "Item is already terminal and cannot progress further" }
```

When the item is in BLOCKED role, the response includes a `suggestion` field instead of `blockers`. When the item is blocked by unsatisfied dependencies, the response includes `blockers` but no `suggestion`.

---

### get_context

**Purpose.** Read-only context snapshot in one of three modes determined by which parameters are
supplied. Use for session startup, work-summary dashboards, and pre-advance gate checks.

**Modes:**
- **Item mode** — `itemId` provided: note schema, existing notes with fill status, and gate status for a specific item.
- **Session resume** — `since` provided: active items, recent role transitions since the timestamp, and stalled items.
- **Health check** — no parameters: all active items (work/review), blocked items, and stalled items.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | No | Triggers item mode |
| `since` | string (ISO 8601) | No | Triggers session-resume mode |
| `includeAncestors` | boolean | No | Include `ancestors` array on each listed item (default: false) |
| `limit` | integer (1–200) | No | Max role transitions in session-resume mode (default: 50) |

**Examples.**

```json
// Health check (no params)
{}

// Session resume
{ "since": "2025-01-01T09:00:00Z", "includeAncestors": true }

// Item gate check
{ "itemId": "550e8400-e29b-41d4-a716-446655440001" }
```

**Response (item mode).**

```json
{
  "mode": "item",
  "item": { "id": "uuid", "title": "JWT Handler", "role": "queue", "tags": "task-implementation", "depth": 1 },
  "schema": [
    { "key": "requirements", "role": "queue", "required": true, "description": "...", "exists": true, "filled": true },
    { "key": "done-criteria", "role": "work", "required": true, "description": "...", "exists": false, "filled": false }
  ],
  "gateStatus": { "canAdvance": true, "phase": "queue", "missing": [] },
  "guidancePointer": null
}
```

**Response (health-check mode).**

```json
{
  "mode": "health-check",
  "activeItems": [{ "id": "uuid", "title": "...", "role": "work", "tags": null }],
  "blockedItems": [{ "id": "uuid", "title": "...", "role": "blocked" }],
  "stalledItems": [{ "id": "uuid", "title": "...", "role": "work", "missingNotes": ["done-criteria"] }]
}
```

---

### get_next_item

**Purpose.** Priority-ranked recommendation of the next WorkItem(s) to work on. Finds QUEUE items,
filters out those with unsatisfied blocking dependencies, and ranks by priority descending then
complexity ascending (quick wins first).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `parentId` | string (UUID) | No | Scope recommendations to items under this parent |
| `limit` | integer (1–20) | No | Number of recommendations (default: 1) |
| `includeDetails` | boolean | No | Include `summary`, `tags`, and `parentId` in each recommendation (default: false) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each recommendation (default: false) |

**Example.**

```json
{ "parentId": "550e8400-e29b-41d4-a716-446655440000", "limit": 3, "includeDetails": true }
```

**Response.**

```json
{
  "recommendations": [
    {
      "itemId": "uuid",
      "title": "Design login flow",
      "role": "queue",
      "priority": "high",
      "complexity": 2,
      "summary": "Create wireframes and API contract",
      "tags": "task-implementation",
      "parentId": "uuid-parent"
    }
  ],
  "total": 1
}
```

---

### get_blocked_items

**Purpose.** Identifies all WorkItems that are blocked, either explicitly (role=blocked via a
block/hold trigger) or implicitly (items in queue/work/review with unsatisfied blocking
dependency edges). Terminal items are never included.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `parentId` | string (UUID) | No | Scope results to items under this parent |
| `includeItemDetails` | boolean | No | Include `summary` and `tags` for each blocked item (default: false) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each blocked item (default: false) |

**Example.**

```json
{ "includeItemDetails": true, "includeAncestors": true }
```

**Response.**

```json
{
  "blockedItems": [
    {
      "itemId": "uuid",
      "title": "Implement JWT handler",
      "role": "queue",
      "priority": "high",
      "complexity": 5,
      "blockType": "dependency",
      "blockedBy": [
        {
          "itemId": "uuid-blocker",
          "title": "Design login flow",
          "role": "queue",
          "unblockAt": "terminal",
          "effectiveUnblockRole": "terminal",
          "satisfied": false
        }
      ],
      "blockerCount": 1,
      "summary": "Handle JWT validation",
      "tags": "task-implementation"
    }
  ],
  "total": 1
}
```

`blockType` values:
- `"explicit"` — item is in the BLOCKED role (set via a `block` or `hold` trigger). `blockedBy` lists any associated dependency blockers but the item is included regardless.
- `"dependency"` — item is in QUEUE, WORK, or REVIEW with one or more unsatisfied blocking dependency edges.

`satisfied` is true when the blocker has reached its `effectiveUnblockRole`.

`blockerCount` reflects only the number of **unsatisfied** blockers (not total blockers). For `"explicit"` items with no dependency blockers, `blockerCount` is 0.
