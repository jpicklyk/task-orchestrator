# MCP Task Orchestrator v3 — API Reference

## Overview

The v3 server exposes 14 MCP tools organized around a single **WorkItem** graph model. Every
entity — whether a project, feature, or task — is a WorkItem with a `role` (queue, work, review,
blocked, terminal), optional `parentId`, a `type` field that selects a work-item schema (lifecycle
mode + required notes), optional `tags` for categorization, and optional `traits` that compose
additional note requirements. Notes are first-class keyed documents attached to items. Dependencies
link items with typed blocking or relational edges.

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
| `claim_item` | Workflow | Write | Atomically claim or release work items for exclusive ownership |

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
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. Repeated calls with the same `(actor.id, requestId)` within ~10 minutes return the cached response without re-executing. Cache is single-instance and in-memory (not persisted). |

**Item object fields (create):**

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `title` | string | Yes | — | |
| `description` | string | No | null | |
| `summary` | string | No | `""` | |
| `role` | string | No | `queue` | |
| `statusLabel` | string | No | null | |
| `priority` | string | No | `medium` | |
| `complexity` | integer (1–10) | No | null (not set) | |
| `parentId` | string (UUID) | No | shared `parentId` or null | |
| `tags` | string | No | null | |
| `metadata` | string | No | null | |
| `type` | string | No | null | Schema type identifier. Selects the `work_item_schemas` entry that determines lifecycle mode and required notes. One-to-one lookup (unlike tags, only one type per item). |
| `properties` | string | No | null | JSON string for extensible item metadata. Traits are stored here automatically when using the `traits` parameter. |
| `traits` | string | No | null | Comma-separated trait names (e.g., `needs-security-review,needs-perf-review`). Adds additional note requirements from the `traits:` config section. Merged into `properties` JSON automatically. |
| `requiresVerification` | boolean | No | false | |

**Item object fields (update):** Same fields as create plus required `id` (UUID), including `type`,
`properties`, and `traits`. Only provided fields are changed; omitted fields retain existing values.
Setting `parentId` to JSON null moves the item to root.

**Note:** The `role` field is not accepted in update operations. Use `advance_item` with an appropriate trigger instead.

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
      "type": null,
      "expectedNotes": [
        { "key": "requirements", "role": "queue", "required": true, "description": "...", "exists": false }
      ]
    }
  ],
  "created": 1,
  "failed": 0
}
```

`expectedNotes` is included only when a note schema is resolved for the item (via `type`, tag match, or default fallback). Check it immediately after creation to know which notes to fill before calling `advance_item(trigger="start")`. Both full item responses (from `get`) and minimal responses (from `search` and `overview`) include `type` when it is non-null.

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
| `type` | string | No (search) | Filter by item type (exact match) |
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
| `claimStatus` | string | No (search only) | Filter by claim state: `claimed` (active live claim), `unclaimed` (never claimed), or `expired` (claim placed but TTL elapsed). When provided, a boolean `isClaimed` is added to each result. `claimedBy` identity is never exposed here — use `get_context(itemId)` for full claim details. |

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
    { "id": "uuid", "parentId": "uuid", "title": "...", "role": "work", "priority": "high", "depth": 1, "tags": null, "type": null }
  ],
  "total": 42,
  "returned": 20,
  "limit": 20,
  "offset": 0
}
```

Search returns minimal fields (`id`, `parentId`, `title`, `role`, `statusLabel`, `priority`, `depth`, `tags`, `type`). Nullable fields (`parentId`, `statusLabel`, `tags`, `type`) are omitted when null — they do not appear as JSON null.
Use `get` for full item JSON including `description`, `summary`, timestamps, and `roleChangedAt`.

When `claimStatus` filter is provided, each result item includes an additional `isClaimed` boolean:

```json
{
  "items": [
    { "id": "uuid", "title": "...", "role": "queue", "priority": "high", "depth": 1, "isClaimed": true }
  ],
  "total": 5,
  "returned": 5,
  "limit": 50,
  "offset": 0
}
```

`isClaimed` is `true` when the item has a live (non-expired) claim at the time of the query. `claimedBy` identity is never included in search results — use `get_context(itemId)` for full claim diagnostics.

**Response (overview — scoped mode, with `itemId`).**

```json
{
  "item": { "id": "uuid", "title": "Auth Feature", "role": "work", "priority": "high", "depth": 1, ... },
  "childCounts": { "queue": 2, "work": 1, "review": 0, "blocked": 0, "terminal": 1 },
  "children": [
    { "id": "uuid", "parentId": "uuid", "title": "Design login flow", "role": "terminal", "priority": "high", "depth": 2 }
  ]
}
```

Scoped overview returns the full item JSON in `item`, a count per role in `childCounts`, and a minimal JSON list of direct children in `children` (using `toMinimalJson` fields). Note: scoped overview children do not include `childCounts` or `traits`.

**Response (overview — global mode, no `itemId`).**

Global overview returns root items with the same minimal fields as search, plus `childCounts`, optional `traits`, and `claimSummary` per root item. When `includeChildren` is true, each root includes a `children` array where each child has the minimal fields plus its own `childCounts` and optional `traits`.

```json
{
  "items": [
    {
      "id": "uuid", "title": "Auth Feature", "role": "work", "priority": "high", "depth": 0,
      "tags": "backend", "type": "feature-implementation",
      "traits": ["needs-migration-review"],
      "childCounts": { "queue": 2, "work": 1, "review": 0, "blocked": 0, "terminal": 1 },
      "claimSummary": { "active": 1, "expired": 0, "unclaimed": 2 },
      "children": [
        {
          "id": "uuid", "parentId": "uuid", "title": "Design login flow", "role": "work",
          "priority": "high", "depth": 1, "tags": "backend", "type": "feature-task",
          "childCounts": { "queue": 0, "work": 0, "review": 0, "blocked": 0, "terminal": 0 }
        }
      ]
    }
  ],
  "total": 5
}
```

Nullable fields (`parentId`, `statusLabel`, `tags`, `type`) are omitted when null. `traits` is omitted when the item has no traits (never an empty array). `children` is only present when `includeChildren` is true. `total` reflects the count of root items returned (not a total-in-DB count).

`claimSummary` counts are scoped to the direct children of each root item. `active` = live non-expired claims; `expired` = claims past TTL; `unclaimed` = items with no claim record. `claimedBy` identity is never included at this level.

---

### create_work_tree

**Purpose.** Atomically create a root WorkItem, optional child items, optional dependency edges
between them, and optional blank notes — all in a single call. Eliminates the round-trips required
when calling `manage_items`, `manage_dependencies`, and `manage_notes` separately.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root` | object | Yes | Root item spec: `{ title, priority?, tags?, type?, traits?, summary?, description?, requiresVerification? }` |
| `parentId` | string (UUID) | No | Existing parent; root depth = parent.depth + 1 |
| `children` | array | No | Child item specs: `[{ ref, title, priority?, tags?, type?, traits?, summary?, description?, requiresVerification? }]`. `ref` is a local name used in `deps`. |
| `deps` | array | No | Dependency specs: `[{ from: ref, to: ref, type?: BLOCKS\|IS_BLOCKED_BY\|RELATES_TO, unblockAt?: queue\|work\|review\|terminal }]`. Use `"root"` to reference the root item. |
| `createNotes` | boolean | No | Auto-create blank notes for each item from its tag schema (default: false) |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). |

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
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). |

Exactly one of `rootId` or `itemIds` must be provided.

**Gate enforcement and `trigger`:** When `trigger="complete"`, gate enforcement applies — items whose schema resolves (via `type`, tag match, or default fallback) must have all required notes filled before completing; items that fail gating are recorded as `gateErrors` and their dependents within the set are skipped. When `trigger="cancel"`, gate enforcement is bypassed — all items in the set are cancelled regardless of note state.

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
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). |

When both `ids` and `itemId` are provided, the delete is **additive**: notes matched by `ids` are deleted first, then notes matched by `itemId` (optionally scoped by `key`) are deleted. Both deletions contribute to the final `deleted` count. Deleting a non-existent note by `(itemId, key)` is a silent no-op (returns success with that note not counted in `deleted`).

**Note object fields (upsert):**

| Field | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | The WorkItem this note belongs to |
| `key` | string | Yes | Logical name for this note (e.g., `requirements`, `done-criteria`) |
| `role` | string | Yes | Workflow phase: `queue`, `work`, or `review` |
| `body` | string | No | Note content (default: `""`) |
| `actor` | object | No | Optional actor claim — see Actor Attribution section |

Each upsert note element may include an optional `actor` object:
- `id` (required string): Identifier for the actor writing this note
- `kind` (required string): One of `orchestrator`, `subagent`, `user`, `external`
- `parent` (optional string): ID of the dispatching agent (forms delegation chain)
- `proof` (optional string): Opaque credential blob (persisted, unused by Stage 1)

When provided, the upsert response includes `actor` and `verification` objects on each successfully upserted note, and the note is persisted with actor claim data that appears in subsequent `query_notes` responses.

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
  "failed": 0,
  "itemContext": {
    "<itemId>": {
      "guidancePointer": "Guidance text for the next unfilled required note, or null",
      "noteProgress": { "filled": 1, "remaining": 0, "total": 1 }
    }
  }
}
```

The `itemContext` map is keyed by each `itemId` that had at least one successful upsert. For each item:
- `guidancePointer` — the `guidance` text from the first unfilled required note in the item's current phase, or `null` if all required notes are filled (or no schema matches).
- `noteProgress` — `{ filled, remaining, total }` counts of required notes for the current phase, or `null` if the item has no matching schema or is in terminal state.

This eliminates the need to call `get_context` after each `manage_notes` upsert to check remaining work.

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
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). |

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
| `transitions` | array | Yes | Array of transition objects: `[{ itemId, trigger, summary?, actor? }]` |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. Repeated calls with the same `(actor.id, requestId)` within ~10 minutes return the cached response without re-executing. Uses the first transition's `actor.id` as the idempotency key actor. |

**Transition object fields:**

| Field | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | Item to transition |
| `trigger` | string | Yes | One of: `start`, `complete`, `block`, `hold`, `resume`, `cancel`, `reopen`. Only `UserTrigger` values are accepted — `cascade` is system-internal and is rejected at the API boundary. |
| `summary` | string | No | Optional annotation stored on the transition record |
| `actor` | object | No | Optional actor claim — see Actor Attribution section |

Each transition element may include an optional `actor` object:
- `id` (required string): Identifier for the actor making this transition
- `kind` (required string): One of `orchestrator`, `subagent`, `user`, `external`
- `parent` (optional string): ID of the dispatching agent (forms delegation chain)
- `proof` (optional string): Opaque credential blob (persisted, unused by Stage 1)

When provided, the response includes `actor` and `verification` objects on each successful transition.

**Ownership enforcement.** When an item has an active (non-expired) claim, `advance_item` enforces ownership on **every** trigger value. The actor (resolved via `degradedModePolicy`) must match the `claimedBy` value on the item. If the item is unclaimed or the claim has expired, any actor can transition it. Cascade transitions (system-generated, parent promotions) are always allowed and bypass ownership checks — they are not reachable via this tool.

**Trigger effects:**

| Trigger | Effect |
|---|---|
| `start` | QUEUE→WORK, WORK→REVIEW (or TERMINAL if no review phase in schema), REVIEW→TERMINAL |
| `complete` | Any non-terminal, non-blocked → TERMINAL |
| `block` / `hold` | Any non-terminal → BLOCKED (saves `previousRole`) |
| `resume` | BLOCKED → `previousRole` |
| `cancel` | Any non-terminal → TERMINAL with `statusLabel="cancelled"` |
| `reopen` | TERMINAL → QUEUE (clears statusLabel, bypasses gate enforcement, cascades parent TERMINAL → WORK) |

**Gate enforcement.** The schema used for gate checks is resolved in this order:
1. `type` field → direct lookup in `work_item_schemas` (highest priority)
2. Tag fallback → first tag in the item's `tags` that matches a schema key
3. `default` schema fallback (if configured)

When a schema is resolved:
- `start`: required notes for the current phase must exist and be filled.
- `complete`: all required notes across all phases must be filled.

Trait notes are merged into the resolved schema: `default_traits` from config apply globally, and per-item traits (stored in `properties` JSON) add their note requirements on top.

**Lifecycle modes** (set on the schema via `work_item_schemas`):
- `AUTO` (default) — terminal cascade fires automatically when all children reach terminal
- `MANUAL` — suppresses terminal cascade; parent must be advanced explicitly
- `PERMANENT` — item never auto-terminates; intended for persistent containers
- `AUTO_REOPEN` — cascade fires as in AUTO, and parent is also reopened when a new child is added

**Start cascade.** When a child item transitions to WORK, the parent is automatically advanced from QUEUE to WORK if it is still in QUEUE (same cascade logic applies up the ancestor chain). This appears in `cascadeEvents` in the response with `trigger="cascade"`.

**Terminal cascade.** When a child item reaches TERMINAL, the parent may also automatically advance if all its children are terminal.

**Reopen cascade.** When a child item is reopened (TERMINAL → QUEUE) and its parent is TERMINAL, the parent is automatically reopened to WORK. This ensures the parent reflects that it has active children again.

All cascade types are recorded in `cascadeEvents`.

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
      ],
      "guidancePointer": "Fill the done-criteria note with...",
      "noteProgress": { "filled": 0, "remaining": 1, "total": 1 }
    }
  ],
  "summary": { "total": 1, "succeeded": 1, "failed": 0 },
  "allUnblockedItems": [{ "itemId": "uuid-next", "title": "Next task" }]
}
```

`unblockedItems` and `allUnblockedItems` are always present (as `[]` when empty). `cascadeEvents` is always present (as `[]` when no cascades occurred). `expectedNotes` is always present (as `[]` when no schema matches the item's tags). Each entry in `expectedNotes` includes: `key`, `role`, `required`, `description`, `exists`, and optionally `skill` (present only when a skill is configured for that note).

`guidancePointer` (string or null) is the guidance text for the first unfilled required note in the **new** role. It is null when no schema matches, no required notes exist for the new role, or all required notes are already filled. Omitted from the response when null.

`skillPointer` (string, optional): Skill name to invoke for the first unfilled required note. Omitted when no skill is configured or all required notes are filled.

`noteProgress` provides counts of required notes for the new role: `filled` (notes that exist with non-blank body), `remaining` (missing or blank), and `total` (filled + remaining). Omitted from the response when no schema matches the item's tags.

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
{ "recommendation": "Terminal", "currentRole": "terminal", "reason": "Item is terminal. Use 'reopen' trigger to move back to queue, or 'cancel' if already cancelled." }
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
  "guidancePointer": null,
  "noteProgress": { "filled": 1, "remaining": 1, "total": 2 },
  "claimDetail": {
    "claimedBy": "agent-worker-42",
    "claimedAt": "2026-01-01T12:00:00Z",
    "claimExpiresAt": "2026-01-01T12:15:00Z",
    "originalClaimedAt": "2026-01-01T12:00:00Z",
    "isExpired": false
  }
}
```

`guidancePointer` (string or null) is the guidance text for the first unfilled required note in the current role. Null when no schema matches, no required notes exist, or all are filled.

`skillPointer` (string, optional): Skill name to invoke for the first unfilled required note. Omitted when no skill is configured or all required notes are filled. Derived from the `skill` field on the first unfilled required note in the schema.

`noteProgress` provides counts of required notes for the **current** role: `filled` (notes that exist with non-blank body), `remaining` (missing or blank), and `total` (filled + remaining). Null when no schema matches the item's tags or the item is in terminal role (distinguishes "no schema" from "empty schema").

Each entry in the `schema` array includes: `key`, `role`, `required`, `description`, `exists`, `filled`, and optionally `skill` (present only when a skill is configured for that note entry).

`claimDetail` is present only when the item is currently claimed (`claimedBy != null`). This is the **only** tool mode that exposes `claimedBy` identity — use it for operator diagnostics on stalled or contested items.

> **UTC note.** `claimedAt`, `claimExpiresAt`, and `originalClaimedAt` are stored as UTC using SQLite `datetime('now')`. Agents or operators inspecting raw database rows must not assume local timezone; the values are always UTC regardless of the host's system time.

`claimDetail` fields:

| Field | Type | Description |
|---|---|---|
| `claimedBy` | string | Agent identity (opaque string — may be `did:web`, session ID, container hostname, etc.) |
| `claimedAt` | ISO 8601 UTC | When the current claim was placed (refreshed on re-claim) |
| `claimExpiresAt` | ISO 8601 UTC | TTL-based expiry (DB-computed). Passive: the claim is not auto-released; expired claims are filtered at read time. |
| `originalClaimedAt` | ISO 8601 UTC | First claim timestamp by the current agent. Preserved across re-claims (heartbeats). Reset when a different agent claims the item. |
| `isExpired` | boolean | `true` when `claimExpiresAt` is in the past at the time of the query |

**Response (health-check mode).**

```json
{
  "mode": "health-check",
  "activeItems": [{ "id": "uuid", "title": "...", "role": "work", "tags": null }],
  "blockedItems": [{ "id": "uuid", "title": "...", "role": "blocked" }],
  "stalledItems": [{ "id": "uuid", "title": "...", "role": "work", "missingNotes": ["done-criteria"] }],
  "claimSummary": { "active": 3, "expired": 1 }
}
```

`claimSummary` in health-check mode: `active` = items with live non-expired claims globally; `expired` = items whose claim TTL has elapsed. Counts only — no identity exposed. Omitted if the claim count query fails.

`unclaimed` is deliberately excluded from the health-check `claimSummary` (too noisy). Use `query_items(operation="overview")` for per-root-item `claimSummary` including `unclaimed`.

---

### get_next_item

**Purpose.** Priority-ranked recommendation of the next WorkItem(s) to work on. Finds items in the
requested role (default: `queue`), filters out those with unsatisfied blocking dependencies and those
with active claims (unless `includeClaimed=true`), and ranks by priority descending then complexity
ascending (quick wins first).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `role` | string | No | Role to query: `queue`, `work`, `review`, or `blocked` (default: `queue`) |
| `parentId` | string (UUID) | No | Scope recommendations to items under this parent |
| `limit` | integer (1–20) | No | Number of recommendations (default: 1) |
| `includeDetails` | boolean | No | Include `summary`, `tags`, and `parentId` in each recommendation (default: false) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each recommendation (default: false) |
| `includeClaimed` | boolean | No | When `false` (default), items with an active claim are filtered out. When `true`, claimed items are included but only a boolean `isClaimed` field is added — the claiming agent's identity is never exposed. |

**Discovery patterns for multi-role fleets:**
- Work-group agents: `get_next_item()` (default `role=queue`)
- Review-group agents: `get_next_item(role="review")`
- Triage-group agents: `get_next_item(role="blocked")`
- Fleet health debugging: `get_next_item(includeClaimed=true)` to see claimed items without identity disclosure

**Example.**

```json
{ "parentId": "550e8400-e29b-41d4-a716-446655440000", "limit": 3, "includeDetails": true }
```

**Response (default — unclaimed items only).**

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

**Response (with `includeClaimed=true` — adds `isClaimed` boolean per item).**

```json
{
  "recommendations": [
    {
      "itemId": "uuid",
      "title": "Design login flow",
      "role": "queue",
      "priority": "high",
      "complexity": 2,
      "isClaimed": false
    },
    {
      "itemId": "uuid2",
      "title": "Write unit tests",
      "role": "queue",
      "priority": "medium",
      "complexity": 3,
      "isClaimed": true
    }
  ],
  "total": 2
}
```

`isClaimed` is `true` when the item has a live (non-expired) claim at query time. `claimedBy` identity is never included — tiered disclosure applies here.

---

### claim_item

**Purpose.** Atomically claim or release work items for exclusive ownership. One claim per agent:
claiming a new item auto-releases any prior claim held by the same agent. Claims are
time-bounded (TTL, default 900s). Re-claiming an already-held item refreshes the TTL without
changing the claim holder.

> **See also:** [Workflow Guide §10 — Claim Mechanism](./workflow-guide.md#10-claim-mechanism-for-multi-agent-fleets) for the agent-side lifecycle, heartbeat pattern, and discovery patterns. [Fleet Deployment Guide](./fleet-deployment.md) for `degradedModePolicy`, capacity planning, tiered disclosure, and Claims Troubleshooting.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `actor` | object | Yes | Actor identity — `{ id, kind, parent?, proof? }`. Verified identity overrides any `agentId` field on individual claim entries. |
| `claims` | array | No | Items to claim: `[{ itemId (UUID or hex prefix), ttlSeconds? (default 900), agentId? (optional — overridden by verified actor when present) }]`. At least one of `claims` or `releases` must be non-empty. |
| `releases` | array | No | Items to release: `[{ itemId (UUID or hex prefix) }]`. |
| `requestId` | string (UUID) | **Yes** | Client-generated UUID for idempotency. Required — `claim_item` is a fleet-mode tool and idempotency is a hard contract. Single-orchestrator deployments do not use `claim_item`; fleet callers are in a multi-agent context where network retries are a real concern. Repeated calls with the same (`actor.id`, `requestId`) within ~10 minutes return the cached response without re-executing. |

**Claim semantics:**

- **One claim per agent.** Claiming item B auto-releases the agent's existing claim on item A (if any). No extra parameter needed.
- **Re-claim as TTL extension.** Calling `claim_item` again on an already-held item refreshes `claimExpiresAt` but preserves `originalClaimedAt`. Use this for heartbeats on long-running work (recommended cadence: TTL/2 = 450s for the default 900s TTL).
- **Terminal items cannot be claimed.** QUEUE, WORK, REVIEW, and BLOCKED items are all claimable.
- **Identity resolution.** `actor.id` is used as the claim identity, subject to `degradedModePolicy`. If JWKS verification succeeds, the verified `actor.id` (from the JWT `sub` claim) is used; otherwise the self-reported `actor.id` is used (unless `degradedModePolicy=reject`, in which case the claim fails with `rejected_by_policy`).
- **Passive expiry.** There is no background reaper. Expired claims are filtered at read time. Crash recovery happens automatically via TTL.
- **DB-side time.** All timestamps (`claimedAt`, `claimExpiresAt`) are set via SQLite `datetime('now', ...)` — they are UTC. Operators inspecting raw rows must not assume host-local time.
- **Per-entry `agentId` vs verified `actor.id`.** When the configured verifier resolves a trusted identity from `actor.proof`, that verified id becomes the claim holder and any `agentId` on the individual claim entry is ignored. The server logs a warning when the two disagree. Callers without a verifier configured may still supply `agentId`; it has no special status beyond providing a self-reported identity.

**Claim outcome codes per item:**

| Outcome | Meaning |
|---|---|
| `success` | Claim placed or TTL refreshed. Response includes own claim metadata. |
| `already_claimed` | Another agent holds a live claim. Response includes `retryAfterMs` (no competing agent identity). |
| `not_found` | No item with that ID. |
| `terminal_item` | Item is in TERMINAL role; cannot be claimed. |
| `rejected_by_policy` | Actor verification rejected by `degradedModePolicy=reject`. All claims in the batch fail. |

**Release outcome codes per item:**

| Outcome | Meaning |
|---|---|
| `success` | Claim cleared. |
| `not_claimed_by_you` | Item is not claimed by this agent (or is unclaimed). |
| `not_found` | No item with that ID. |

**Tiered disclosure rule.** On `already_claimed`, the response includes only `retryAfterMs`. The competing agent's identity is never disclosed — this prevents claim sniping and jealousy patterns.

**Example.**

```json
{
  "claims": [{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "ttlSeconds": 900 }],
  "actor": { "id": "worker-agent-7", "kind": "subagent", "parent": "orchestrator-1" }
}
```

**Response (all succeed).**

```json
{
  "claimResults": [
    {
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "outcome": "success",
      "claimedBy": "worker-agent-7",
      "claimedAt": "2026-01-01T12:00:00Z",
      "claimExpiresAt": "2026-01-01T12:15:00Z",
      "originalClaimedAt": "2026-01-01T12:00:00Z"
    }
  ],
  "releaseResults": [],
  "summary": {
    "claimsTotal": 1, "claimsSucceeded": 1, "claimsFailed": 0,
    "releasesTotal": 0, "releasesSucceeded": 0, "releasesFailed": 0
  }
}
```

**Response (claim contested).**

```json
{
  "claimResults": [
    {
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "outcome": "already_claimed",
      "retryAfterMs": 420000
    }
  ],
  "releaseResults": [],
  "summary": { "claimsTotal": 1, "claimsSucceeded": 0, "claimsFailed": 1, ... }
}
```

On `already_claimed`, `retryAfterMs` approximates the remaining TTL of the existing claim in milliseconds. Use it to schedule a retry after the current claim expires, or pick a different unclaimed item instead.

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

---

## Idempotency

Seven mutating tools support `requestId: UUID` for idempotency: `manage_items`, `manage_notes`, `manage_dependencies`, `advance_item`, `create_work_tree`, `complete_tree`, and `claim_item`.

**`claim_item` requires `requestId` (mandatory).** `claim_item` is a fleet-mode tool by definition — single-orchestrator deployments don't claim items. Fleet deployments using `claim_item` are by definition in a multi-agent context where network retries are a real concern, so `claim_item` enforces idempotency as a contract. Calls missing `requestId` are rejected at validation. For `claim_item`, the cache key uses the trusted agent identity (post-`DegradedModePolicy` resolution), matching the actor key used by the claim itself.

**The other 6 mutating tools keep `requestId` optional.** `manage_items`, `manage_notes`, `manage_dependencies`, `advance_item`, `create_work_tree`, and `complete_tree` serve both orchestrator-mode (single dispatcher, no idempotency needed) and fleet-mode (idempotency desired) callers. Omitting `requestId` skips the cache entirely — execution is always fresh.

**How it works.** When `requestId` and `actor.id` are both present, the server checks an in-memory LRU cache keyed on `(actor.id, requestId)`. If a cached result exists, the original response is returned immediately without re-executing the operation. The cache window is approximately 10 minutes.

**Constraints:**
- Cache is single-instance and in-memory. It is not persisted across server restarts and is not shared across multiple server processes.
- For `advance_item`, the `actor.id` of the **first** transition in the batch is used as the cache key actor.
- For `manage_items`, `manage_notes`, `manage_dependencies`, `create_work_tree`, and `complete_tree`, the top-level `actor.id` is used. (Implementation note: these tools extract actor from the request-level field, not per-item fields.)
- A non-parseable `requestId` string is silently ignored on the 6 optional tools (no cache lookup or store). For `claim_item`, a non-UUID `requestId` is rejected at validation.

**Usage.** Generate a fresh UUID per logical operation:

```json
{
  "transitions": [{ "itemId": "uuid", "trigger": "start", "actor": { "id": "agent-1", "kind": "subagent" } }],
  "requestId": "e3b0c442-98fc-1c14-9afb-f4c8996fb924"
}
```

Replay the same call if the network times out — the server either executes once or returns the cached result.

---

## Error Envelope

All tool failures use a structured `ToolError` shape that classifies retry semantics:

```json
{
  "error": {
    "kind": "transient",
    "code": "claim_contention",
    "message": "Item already claimed by another agent",
    "retryAfterMs": 420000,
    "contendedItemId": "550e8400-e29b-41d4-a716-446655440001"
  }
}
```

### ErrorKind Values

| Kind | Meaning | Retry behavior |
|---|---|---|
| `transient` | Temporary failure; retrying may succeed | Retry with exponential backoff. Typical causes: lock contention, JWKS unavailable, transient DB busy. |
| `permanent` | Definitive failure; retrying will produce the same result | Do not retry. Typical causes: validation errors, authorization failures, not-found. |
| `shedding` | Server temporarily over capacity | Retry after `retryAfterMs` milliseconds. Typical causes: writer queue saturated, circuit-breaker open. |

### Error Envelope Fields

| Field | Type | Description |
|---|---|---|
| `kind` | string | One of: `transient`, `permanent`, `shedding` |
| `code` | string | Structured error code for programmatic handling |
| `message` | string | Human-readable failure description |
| `retryAfterMs` | integer (nullable) | Milliseconds to wait before retrying. Populated for `shedding`; null otherwise (use own backoff). |
| `contendedItemId` | string UUID (nullable) | UUID of the work item involved in a contention error. Populated for `transient` claim-race or version-conflict failures. Allows agents to distinguish "retry this item" from "pick a different item" without parsing `message`. |

### Retry Decision Guide

```
kind=transient  → exponential backoff, retry same operation
  contendedItemId present → option: skip this item, pick another from get_next_item
kind=permanent  → do not retry; fix the request (validation, permissions, etc.)
kind=shedding   → wait retryAfterMs, then retry; reduce polling rate if this persists
```

---

## Actor Attribution

### Overview

Actor attribution tracks *who* made changes to work items. Every `advance_item` transition and `manage_notes` upsert can include an optional `actor` claim. Stage 1 ships with a no-op verifier — all claims are persisted as `unchecked`.

### Actor Claim Shape

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | string | yes | Identifier for the actor |
| kind | string | yes | `orchestrator`, `subagent`, `user`, or `external` |
| parent | string | no | ID of the dispatching agent — forms a delegation chain |
| proof | string | no | Opaque credential (persisted verbatim, unused by Stage 1) |

### Verification Record

Every persisted actor claim includes a verification record:

| Field | Type | Description |
|-------|------|-------------|
| status | string | One of the five values below |
| verifier | string | Which verifier produced the result (e.g., `noop`, `jwks`) |
| reason | string | Failure detail or exception message; null when absent or verified |
| metadata | object | Optional key/value bag — omitted when empty (see below) |

**VerificationStatus values:**

| Value | Meaning |
|-------|---------|
| `absent` | No proof was provided; the caller decides how to treat proof-less actors |
| `unchecked` | Proof was present but no verifier is configured to evaluate it |
| `verified` | Proof was cryptographically validated and all claims passed |
| `rejected` | Proof was present but validation failed (bad signature, expired, wrong claims, policy violation) |
| `unavailable` | Verification could not complete due to a transient error (network failure, unreachable JWKS endpoint) |

**Metadata fields:**

| Key | Present when | Description |
|-----|-------------|-------------|
| `failureKind` | `rejected` or `unavailable` | Category of failure: `crypto` (signature/key), `claims` (exp/iss/aud/sub), `policy` (algorithm allowlist), `network` (JWKS fetch error), `internal` (unexpected exception) |
| `verifiedFromCache` | `verified` + stale JWKS cache | `"true"` when the verification used a key set served from stale cache |
| `cacheAgeSeconds` | `verified` + stale JWKS cache | Age of the cached key set in seconds at verification time |

### Chain Propagation Convention

Delegation chains are built by convention:
1. The orchestrator includes `actor: { id: "orch-1", kind: "orchestrator" }` on its calls
2. When dispatching a subagent, it passes its own `id` as context
3. The subagent includes `actor: { id: "sub-1", kind: "subagent", parent: "orch-1" }` on its MCP calls
4. Query responses preserve the chain for post-mortem analysis

This is a documentation convention, not enforced by the server. Stage 1 trusts self-reported claims.

### Enforcing Actor Attribution

Actor claims are **optional by default** — users who don't need auditing pay no extra token cost. To require actor claims on all write operations, enable auditing in `.taskorchestrator/config.yaml`:

```yaml
auditing:
  enabled: true
```

When enabled, the plugin's `PreToolUse` hook blocks any `advance_item` or `manage_notes(upsert)` call where one or more elements are missing an `actor` object. The call never reaches the server — the agent must retry with actor claims included.

When `enabled` is `false` or the `auditing` section is absent, calls pass through with no enforcement. Actor claims can still be provided voluntarily.

> **Note:** Config changes require an MCP reconnect (`/mcp`) or session restart to take effect.

#### Subagent Behavior

Actor claims are self-reported by convention — the server does not inject them automatically. This means:

- **Uninstructed subagents** make calls with no actor data, producing anonymous transitions and notes
- **Instructed subagents** include actor claims only when the delegation prompt tells them to
- The enforcement hook applies to all callers in the session, including subagents, providing a safety net regardless of prompt quality

For reliable attribution, combine auditing enforcement with explicit delegation instructions:

```
Include an "actor" object on every advance_item and manage_notes call:
{ "id": "your-agent-name", "kind": "subagent", "parent": "orchestrator-id" }
```

---

## Auditing & Actor Verification

### Verifier Configuration

The `auditing` section in `.taskorchestrator/config.yaml` controls actor attribution enforcement and verification. The `enabled` flag and the `verifier` block are independent — enforcement checks actor presence, while the verifier checks proof validity.

```yaml
auditing:
  enabled: true          # Enforce actor claims on write operations
  degraded_mode_policy: accept-cached   # accept-cached (default) | accept-self-reported | reject
  verifier:
    type: jwks           # "noop" (default) | "jwks"
    oidc_discovery: "https://provider.example/.well-known/openid-configuration"
    jwks_uri: "https://provider.example/.well-known/jwks.json"
    jwks_path: ".agentlair/jwks.json"
    issuer: "https://provider.example"
    audience: "task-orchestrator"
    algorithms: ["EdDSA", "RS256"]
    cache_ttl_seconds: 300
    require_sub_match: true
```

### `auth.degradedModePolicy`

Controls how the server resolves actor identity when verification cannot produce a `verified` result.

| Value | Behavior | Use case |
|---|---|---|
| `accept-cached` | *(default)* When verification status is `unavailable` **and** a stale JWKS cache was used, the verified `actor.id` from the JWT is trusted. All other non-verified outcomes fall back to the self-reported `actor.id`. | Single-org deployments with occasional JWKS outages |
| `accept-self-reported` | Always trust the caller-supplied `actor.id` regardless of verification outcome. Equivalent to v3.2 implicit behavior. | Local dev without JWKS; explicitly documented opt-out |
| `reject` | Any operation requiring verified identity fails when verification status is not `verified`. All claim operations return `rejected_by_policy`. | Cross-org `did:web` deployments; maximum identity assurance |

`degradedModePolicy` applies at every ownership-sensitive call: `claim_item` placement, `advance_item` ownership checks. When in `reject` mode and verification is absent or fails, the operation is rejected before any DB access.

### Verification Behavior

| Verifier type | Behavior |
|---|---|
| `noop` (or absent) | All actor claims are accepted as `unchecked`. No cryptographic check is performed. |
| `jwks` | JWT tokens in `actor.proof` are validated against the configured JWKS key set. Valid token → `status: verified`. Invalid, expired, or wrong-claims token → `status: rejected` with a descriptive `reason` and `metadata.failureKind`. Missing proof → `status: absent`. Network/fetch errors → `status: unavailable`. |

### JWKS Key Sources

`oidc_discovery`, `jwks_uri`, and `jwks_path` can be used alone or combined. URI-sourced keys and file-sourced keys are merged into a single key set. When both `oidc_discovery` and explicit `jwks_uri`/`issuer` are set, the explicit values override the OIDC-discovered values.

| Field | Description |
|---|---|
| `oidc_discovery` | URL to an OpenID Connect discovery document. The server fetches `jwks_uri` and `issuer` from the document. |
| `jwks_uri` | Direct URL to a JWKS endpoint. Overrides the URI discovered via `oidc_discovery`. |
| `jwks_path` | Path to a local JWKS JSON file, relative to `AGENT_CONFIG_DIR`. Useful for local dev and air-gapped environments. |
| `issuer` | Expected `iss` claim in the JWT. Overrides the issuer discovered via `oidc_discovery`. |
| `audience` | Expected `aud` claim in the JWT. |
| `algorithms` | List of accepted signing algorithms (e.g., `["EdDSA", "RS256"]`). |
| `cache_ttl_seconds` | How long to cache fetched JWKS keys (default: 300). |
| `stale_on_error` | When true (default), a stale cached key set is used if a JWKS refresh fails. The result is `verified` with `metadata.verifiedFromCache="true"` and `metadata.cacheAgeSeconds` set. When false, fetch failures always return `unavailable`. |
| `require_sub_match` | When true, the JWT `sub` claim must match `actor.id`. |

### Docker — JWKS Path Mount

When using `jwks_path`, the `.agentlair/` directory must be mounted into the container alongside `.taskorchestrator/`:

```bash
docker run --rm -i \
  -v mcp-task-data:/app/data \
  -v "$(pwd)"/.taskorchestrator:/project/.taskorchestrator:ro \
  -v "$(pwd)"/.agentlair:/project/.agentlair:ro \
  -e AGENT_CONFIG_DIR=/project \
  task-orchestrator:dev
```

The `.agentlair/` mount is only needed when using `jwks_path`. When using `jwks_uri` or `oidc_discovery`, the container must be able to reach the endpoint over the network.

**Network access from Docker containers:**

| Scenario | Works? | Notes |
|----------|--------|-------|
| Public HTTPS endpoint | Yes | Standard outbound from container |
| `localhost` on host | No | Container localhost is itself, not the host |
| `host.docker.internal` | Docker Desktop only | On Linux: add `--add-host=host.docker.internal:host-gateway` |
| Docker network service | Yes | Use the container name as hostname |

`jwks_path` (local file) avoids all networking concerns — recommended for local dev and air-gapped environments.
