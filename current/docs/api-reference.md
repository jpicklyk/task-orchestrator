# MCP Task Orchestrator v3 — API Reference

## Overview

The v3 server exposes 16 MCP tools organized around a single **WorkItem** graph model. Every
entity — whether a project, feature, or task — is a WorkItem with a `role` (queue, work, review,
blocked, terminal), optional `parentId`, a `type` field that selects a work-item schema (lifecycle
mode + required notes), optional `tags` for categorization, and optional `traits` that compose
additional note requirements. Notes are first-class keyed documents attached to items. Dependencies
link items with typed blocking or relational edges.

**See also:** [`search-and-discovery.md`](./search-and-discovery.md) for the architecture behind
FTS5 search (two-tokenizer design, RRF fusion, scope filtering, backlinks, score interpretation).

## Tool Categories

| Tool | Category | R/W | Description |
|---|---|---|---|
| `manage_items` | Hierarchy & CRUD | Write | Create, update, or delete WorkItems |
| `query_items` | Hierarchy & CRUD | Read | Get, search, or overview WorkItems |
| `create_work_tree` | Hierarchy & CRUD | Write | Atomically create root + children + deps + notes |
| `complete_tree` | Hierarchy & CRUD | Write | Batch-complete descendants in topological order |
| `manage_notes` | Notes | Write | Upsert or delete Notes on WorkItems |
| `query_notes` | Notes | Read | Get, list, or FTS5-search notes |
| `manage_dependencies` | Dependencies | Write | Create or delete dependency edges |
| `query_dependencies` | Dependencies | Read | Query deps with direction filter, optional BFS traversal, or backlinks lookup |
| `advance_item` | Workflow | Write | Trigger-based role transitions with cascade and gate enforcement |
| `get_next_status` | Workflow | Read | Read-only transition recommendation for a single item |
| `get_context` | Workflow | Read | Context snapshot: item mode, session resume, or health check |
| `get_next_item` | Workflow | Read | Priority-ranked recommendation of next actionable item |
| `get_blocked_items` | Workflow | Read | All items blocked by dependency or explicit block trigger |
| `claim_item` | Workflow | Write | Atomically claim or release work items for exclusive ownership |
| `manage_project_config` | System | Write/Read | Push or read back per-root config YAML for the layered schema resolver |
| `manage_plan_documents` | System | Write/Read | Stash, read back, or list per-root plan documents |

---

## Category: Hierarchy & CRUD

### manage_items

**Purpose.** Write operations for WorkItems: batch-create, partial-update, or batch-delete. Depth
is computed automatically from the parent; nesting depth is unbounded (cycle protection is enforced at the database level).

**Operations.** `create`, `update`, `delete`

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | One of: `create`, `update`, `delete` |
| `items` | array | Yes (create/update) | Array of item objects |
| `itemIds` | array | Yes (delete) | Array of item UUIDs to delete |
| `parentId` | string (UUID) | No | Shared default parent for all created items; per-item `parentId` overrides this |
| `traits` | string | No | Comma-separated trait names applied as a shared default to all items in this batch (e.g., `"needs-migration-review,needs-security-review"`). Adds trait note requirements from the `traits:` config section. Merged into each item's `properties` JSON automatically. |
| `recursive` | boolean | No | Delete all descendants before deleting the target items (default: false) |
| `requiresVerification` | boolean | No | **Top-level `requiresVerification` is ignored.** Set it on individual items in the `items` array instead. |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. Repeated calls with the same `(actor.id, requestId)` within ~10 minutes return the cached response without re-executing. Cache is single-instance and in-memory (not persisted). |
| `actor` | object | No | Actor for idempotency key resolution: `{ id (required string), kind (required: orchestrator\|subagent\|user\|external), parent? (optional string), proof? (optional string) }`. Required when `requestId` is provided. |

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

**Item object fields (update):** Same fields as create plus required `itemId` (UUID), including `type`,
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
  "failed": 0,
  "failures": [{ "id": "bad-uuid", "error": "Item 'bad-uuid' not found" }]
}
```

`failures` is only present when `failed > 0`; omitted entirely on a clean run.

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
    { "itemId": "550e8400-e29b-41d4-a716-446655440001", "priority": "high", "complexity": 3 }
  ]
}

// Recursive delete
{
  "operation": "delete",
  "itemIds": ["550e8400-e29b-41d4-a716-446655440000"],
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
      "schemaMatch": true,
      "expectedNotes": [
        { "key": "feature-summary", "role": "queue", "required": true, "exists": false }
      ]
    }
  ],
  "created": 1,
  "failed": 0
}
```

`expectedNotes` is always present — an empty array when no schema resolves — and is keys-only: `key`, `role`, `required`, `exists` (no `description`/`guidance`/`skill`). The boolean `schemaMatch` indicates whether a note schema was resolved for the item (via `type`, tag match, or default fallback). Resolve full note text via `query_items(operation="schema", itemId=...)` (see below). Check `expectedNotes` immediately after creation to know which notes to fill before calling `advance_item(trigger="start")`. When the config defines traits, the create response also includes a top-level `availableTraits` string array naming the trait values accepted by the `traits` parameter (omitted when no traits are configured). Both full item responses (from `get`) and minimal responses (from `search` and `overview`) include `type` when it is non-null.

---

### query_items

**Purpose.** Read-only queries for WorkItems: full fetch by ID, FTS5 full-text search with ranked
snippets, filtered list search, or hierarchical overview.

**Operations.** `get`, `search`, `overview`, `schema`

> **BREAKING change (v3.8+):** The old LIKE-based `query` parameter (top-level on `operation=search`)
> has been removed. All text search now goes through the FTS5-backed `search` operation with the
> `query` field. See **FTS5 search mode** below.

#### Key Parameters — get

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"get"` |
| `itemId` | string (UUID or 4+ char prefix) | Yes | Item to fetch |
| `includeAncestors` | boolean | No | When true, each result includes an `ancestors` array (default: false) |
| `includeTimestamps` | boolean | No | When true, includes `createdAt`, `modifiedAt`, `roleChangedAt` (default: false — absent otherwise) |

#### Key Parameters — search (FTS5 mode, when `query` is provided)

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"search"` |
| `query` | string | Yes | FTS5 search terms — multiple words produce implicit AND. Special characters are auto-escaped; pass plain terms. |
| `scope` | object | No | Structural scope: `ancestorId` (UUID — subtree), `itemId` (UUID — single item), `tags` (string[] — OR-match), `role` (string — exact work-item role: queue/work/review/terminal/blocked) |
| `matchMode` | string | No | `"auto"` (default — trigram+text tables fused via RRF), `"substring"` (trigram only, requires ≥3-char tokens), `"text"` (porter+unicode61, stemming) |
| `snippet` | boolean | No | Include ~32-token snippet with `<mark>…</mark>` highlights (default: true) |
| `explain` | boolean | No | Include raw FTS5 scores per hit for ranking debug (default: false) |
| `limit` | integer | No | Max hits (default: 20, max: 100) |
| `offset` | integer | No | Skip N hits for pagination (default: 0) |

#### Key Parameters — search (list mode, when `query` is absent)

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"search"` |
| `parentId` | string (UUID) | No | Filter by parent |
| `ancestorId` | string (UUID or 4+ char prefix) | No | List mode only: limit to items in this item's subtree (any depth, inclusive). Mirrors `scope.ancestorId`'s semantics but for list-mode filtering; omitted = unscoped, byte-identical to prior behavior. Not used in FTS mode — use `scope.ancestorId` there. |
| `depth` | integer | No | Filter by depth level |
| `role` | string | No | Filter by role: `queue`, `work`, `review`, `blocked`, `terminal` |
| `priority` | string | No | Filter: `high`, `medium`, `low` |
| `tags` | string | No | Comma-separated tags filter (OR logic) |
| `type` | string | No | Filter by item type (exact match) |
| `createdAfter` | string (ISO 8601) | No | Timestamp lower bound |
| `createdBefore` | string (ISO 8601) | No | Timestamp upper bound |
| `modifiedAfter` | string (ISO 8601) | No | Modification lower bound |
| `modifiedBefore` | string (ISO 8601) | No | Modification upper bound |
| `roleChangedAfter` | string (ISO 8601) | No | Items whose role changed after this time |
| `roleChangedBefore` | string (ISO 8601) | No | Items whose role changed before this time |
| `sortBy` | string | No | One of: `title`, `priority`, `complexity`, `createdAt`, `modifiedAt` |
| `sortOrder` | string | No | `asc` or `desc` (default: `desc`) |
| `limit` | integer | No | Max results (default: 50, max: 100) |
| `offset` | integer | No | Skip N items for pagination (default: 0) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each item (default: false) |
| `claimStatus` | string | No | Filter by claim state: `claimed`, `unclaimed`, or `expired`. When provided, a boolean `isClaimed` is added to each result. `claimedBy` identity is never exposed here — use `get_context(itemId)` for full details. |

#### Key Parameters — overview

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"overview"` |
| `itemId` | string (UUID) | No | Scope overview to a specific item (scoped mode); omit for global root overview. Mutually exclusive with `anchorId`. |
| `anchorId` | string (UUID or 4+ char prefix) | No | Anchored mode: renders this item's DIRECT CHILDREN as the roots set, each with a full-subtree role-count roll-up. Mutually exclusive with `itemId` — supplying both is a validation error. |
| `includeChildren` | boolean | No | Include direct children on each root item (global mode only, default: false) |
| `limit` | integer | No | Max root items (default: 50; global and anchored modes only) |
| `offset` | integer | No | Skip N root items for pagination (default: 0; global and anchored modes only — scoped overview always returns all direct children, unpaginated) |
| `excludeTerminal` | boolean | No | Default false. Global mode: drop terminal-role roots from `items` at the SQL level before their children/counts are even fetched, and `total`/`truncated` reflect the filtered set. Anchored and scoped modes: drop terminal-role items from the `items`/`children` array, **except** a terminal-role item that still has non-terminal descendants, which is retained (it represents active work parked under a done container). The scoped parent is always returned regardless of its own role, and `childCounts` stays unfiltered. |

#### Key Parameters — schema

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"schema"` |
| `type` | string | Exactly one of `type`/`itemId` | Schema type identifier — direct lookup in `work_item_schemas` |
| `itemId` | string (UUID or 4+ char prefix) | Exactly one of `type`/`itemId` | Resolves the item's schema via the standard type-first/tag-fallback/trait-merge logic, layered per-root-then-global using the item's own `rootId` |
| `rootId` | string (UUID or 4+ char prefix) | No | Only used with `type`. Resolves `type` against this root's per-root pushed config first (per-root exact type -> per-root `"default"` -> global exact type -> global `"default"`), falling back to global-only behavior when the root has no pushed config. Ignored when `itemId` is used instead — the item's own `rootId` is applied automatically. |

**Response (schema).**

```json
{
  "type": "feature-implementation",
  "configFingerprint": "a1b2c3d4",
  "configSource": "global",
  "notes": [
    { "key": "feature-summary", "role": "queue", "required": true, "description": "...", "guidance": "...", "skill": "spec-quality", "maxLength": 4000 },
    { "key": "implementation-notes", "role": "work", "required": true, "description": "..." }
  ]
}
```

This is the **only** place that returns full note text (`description`, `guidance`, `skill`, `maxLength`) in one shot for an entire schema. Use it to resolve the keys-only `expectedNotes` and the reference-only `guidanceKey`/`skillPointer` fields returned elsewhere. `guidance`, `skill`, and `maxLength` are omitted per-entry when unset. `configFingerprint` reports the fingerprint of whichever config layer actually supplied the schema (per-root or global) and is `null` when unavailable; cache schema responses per fingerprint to avoid re-fetching unchanged config. `configSource` is `"per-root"` when a per-root pushed config supplied the schema (via `rootId` on the `type` path, or the item's own `rootId` on the `itemId` path) and `"global"` otherwise. Errors with `RESOURCE_NOT_FOUND` when no schema matches the given `type` or the item is schema-free.

**Examples.**

```json
// Fetch single item with ancestor breadcrumbs
{ "operation": "get", "itemId": "550e8400-e29b-41d4-a716-446655440001", "includeAncestors": true }

// FTS5 full-text search — find items mentioning "OAuth"
{ "operation": "search", "query": "OAuth flow", "limit": 10 }

// FTS5 search scoped to a feature subtree
{ "operation": "search", "query": "database migration", "scope": { "ancestorId": "550e8400-e29b-41d4-a716-446655440000" } }

// List-mode search with role/priority filter (no query)
{ "operation": "search", "role": "work", "priority": "high", "limit": 20, "offset": 0 }

// Scoped overview of a feature's children
{ "operation": "overview", "itemId": "550e8400-e29b-41d4-a716-446655440000" }
```

**Response (search — FTS5 mode).**

```json
{
  "hits": [
    {
      "kind": "item",
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "field": "title",
      "snippet": "Implement <mark>OAuth</mark> <mark>flow</mark> for…",
      "score": 0.0325,
      "matchedIn": ["trigram", "text"],
      "explain": { "trigramRank": -8.1, "textRank": -6.4, "rrfK": 60 }
    }
  ],
  "totalHits": 5,
  "nextOffset": 10,
  "truncated": false
}
```

`snippet` is included by default; `explain` appears only when `explain=true` (default false). `score` is the descending RRF fused value (higher = more relevant). `nextOffset` is `null` when the
last page is reached. `truncated` is `true` when the result was capped at the 100-row hard limit —
refine the query or use `scope` filters to narrow results.

**Note on `totalHits`:** This is the page-bounded count from the in-memory RRF fusion list, not the
true database total. For large corpora, the actual match count may exceed `totalHits`. Use
`truncated=true` as the signal to refine.

**Response (search — list mode, when `query` is absent).**

```json
{
  "items": [
    { "id": "uuid", "parentId": "uuid", "title": "...", "role": "work", "priority": "high", "depth": 1 }
  ],
  "total": 42,
  "returned": 20,
  "skipped": 1,
  "limit": 20,
  "offset": 0
}
```

List mode returns minimal fields (`id`, `parentId`, `title`, `role`, `statusLabel`, `priority`, `depth`, `tags`, `type`). Nullable fields are omitted when null.
Use `get` for full item JSON including `description` and `summary`; on `get`, `createdAt`, `modifiedAt`, and `roleChangedAt` are opt-in via `includeTimestamps` (absent by default). List-mode results never include timestamps.

`total` is the raw SQL match count (unaffected by validation), `returned` is `items.length`. `skipped` is present only when > 0: it counts rows in this page's window that failed domain validation (e.g. a corrupt legacy row) and were dropped rather than returned — a WARN log identifies the row. Invariant: `total = returned + skipped + notFetched`, where `notFetched` is any rows beyond `limit`/`offset` never queried.

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
    {
      "id": "uuid", "parentId": "uuid", "title": "Design login flow", "role": "terminal", "priority": "high", "depth": 2,
      "childCounts": { "queue": 0, "work": 0, "review": 0, "blocked": 0, "terminal": 0 }
    }
  ]
}
```

Scoped overview returns the full item JSON in `item`, a count per role in `childCounts`, and a minimal JSON list of direct children in `children` (using `toMinimalJson` fields, each also enriched with its own `childCounts` and optional `traits` — same enrichment as global-mode `includeChildren`). `childCounts` on the parent is always the full, unfiltered role breakdown, even when `excludeTerminal` hides some of those children from the `children` array below it.

**Response (overview — global mode, no `itemId`).**

Global overview returns root items with the same minimal fields as search, plus `childCounts`, optional `traits`, and `claimSummary` per root item. When `includeChildren` is true, each root includes a `children` array where each child has the minimal fields plus its own `childCounts` and optional `traits`. Root items are ordered newest-`createdAt`-first (ties broken by `id` for stable pagination). `limit` defaults to 50 (same default as list-mode search); `offset` pages through roots beyond the first page, same convention as list-mode search.

When `excludeTerminal` is true, terminal-role roots are filtered at the SQL level before pagination — they never appear in `items`, their `children`/`childCounts`/`claimSummary` are never fetched, and `total`/`truncated` are computed against the filtered (non-terminal) set rather than the unconditional root count.

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
  "total": 55,
  "truncated": true,
  "offset": 0,
  "skipped": 1
}
```

Nullable fields (`parentId`, `statusLabel`, `tags`, `type`) are omitted when null. `traits` is omitted when the item has no traits (never an empty array). `children` is only present when `includeChildren` is true.

`total` is the **true count of matching root items** (via a dedicated `COUNT` query) — the unconditional root count when `excludeTerminal` is false/omitted, or the count of non-terminal roots when `excludeTerminal` is true. Either way it is independent of `limit`/`offset` and of any validation drops, and is **not** the size of the `items` array. `offset` echoes the request's `offset` (0 if omitted). `truncated` is `true` when `offset + items.length < total` for **any** reason — more pages remain, or validation drops shrank this page — which is broader than FTS search's `truncated` (that flag fires only at the FTS hit cap); use `skipped` to disambiguate the cause. `skipped` is present only when > 0: it counts root rows within this page's window that failed domain validation and were dropped rather than returned; a WARN log identifies the row so it can be repaired.

`claimSummary` counts are scoped to the direct children of each root item. `active` = live non-expired claims; `expired` = claims past TTL; `unclaimed` = items with no claim record. `claimedBy` identity is never included at this level.

**Response (overview — anchored mode, with `anchorId`).**

```json
{
  "anchor": { "id": "uuid", "title": "Q3 Platform Project" },
  "items": [
    {
      "id": "uuid", "parentId": "uuid", "title": "Auth Feature", "role": "work",
      "priority": "high", "depth": 1, "tags": "backend", "type": "feature-implementation",
      "traits": ["needs-migration-review"],
      "childCounts": { "queue": 2, "work": 1, "review": 0, "blocked": 0, "terminal": 1 }
    }
  ],
  "total": 6,
  "truncated": false,
  "offset": 0
}
```

Anchored overview renders `anchorId`'s **direct children** as the roots set (instead of true
depth-0 items) — the project-dashboard entry point when `anchorId` is a project/feature anchor.
The `anchor` envelope names the item whose children are being rendered. Each item uses the same
minimal fields as global overview, but its `childCounts` is a **full-subtree** role roll-up
(descendants at any depth), not the direct-children-only breakdown that global/scoped overview use
— this is the key semantic difference from scoped overview (`itemId`), which returns direct
childCounts. `claimSummary` and `includeChildren` are not supported in anchored mode (kept to one
repository call per child). `total`/`truncated`/`offset`/`excludeTerminal` follow the same
conventions as global mode, applied to the anchor's direct-children set rather than true roots.

---

### create_work_tree

**Purpose.** Atomically create a root WorkItem, optional child items, optional dependency edges
between them, and optional blank notes — all in a single call. Eliminates the round-trips required
when calling `manage_items`, `manage_dependencies`, and `manage_notes` separately.

**Attach mode.** Pass `root.id` (UUID or hex prefix of an existing item) instead of `root.title` to
attach children, deps, and notes directly to an existing item. When `root.id` is provided, `root.title`
is optional (ignored if both are given). The existing item is NOT re-inserted; children are created
under it at `existing.depth + 1`. Providing both `root.id` and `parentId` is rejected with `VALIDATION_ERROR`.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root` | object | Yes | Create mode: `{ title (required), priority?, tags?, type?, traits?, summary?, description?, requiresVerification?, noteAnchors? }`. Attach mode: `{ id? (UUID or hex prefix of existing item), title? (optional when id provided), priority?, tags?, type?, traits?, summary?, description?, requiresVerification?, noteAnchors? }`. When `id` is present, `title` is optional and ignored. `noteAnchors` is documented below alongside `docRef`. |
| `parentId` | string (UUID) | No | Existing parent; root depth = parent.depth + 1. Cannot be combined with `root.id`. |
| `children` | array | No | Child item specs: `[{ ref, title, parentRef?, priority?, tags?, type?, traits?, summary?, description?, requiresVerification?, noteAnchors? }]`. `ref` is a local name used to wire `deps`, `notes`, and other children's `parentRef`. `parentRef` (another child's `ref` or `"root"`, default `"root"`) sets the child's parent — nesting is expressed via `parentRef` only; a nested `children` key inside a child spec is rejected. |
| `deps` | array | No | Dependency specs: `[{ from: ref, to: ref, type?: BLOCKS\|IS_BLOCKED_BY\|RELATES_TO, unblockAt?: queue\|work\|review\|terminal }]`. Use `"root"` to reference the root item. |
| `createNotes` | boolean | No | Auto-create blank notes for each item from its resolved schema (looked up by `type` first, then by `tags`). Default: false. |
| `notes` | array | No | Notes to create with bodies: `[{ itemRef (required, "root" or child ref), key (required), role (required: queue\|work\|review), body? (defaults to empty string) }]`. Explicit notes win over `noteAnchors` AND `createNotes=true` blanks per `(itemRef, key)`. **Strict role enforcement:** when an explicit note's `key` is declared in the resolved schema for the target item, the note's `role` must equal the schema role; mismatch returns `VALIDATION_ERROR`. Off-schema keys and items without a schema are unconstrained. |
| `docRef` | object | No | Materialize-from-document source: `{ rootId? (defaults to the created/attached root's own rootId — `rootItem.rootId ?: rootItem.id`; validated for consistency if given, UUID or hex prefix), slug (required) }`. References a document stashed via `manage_plan_documents`/`PUT /roots/{rootId}/plans/{slug}`. Required whenever any item spec's `noteAnchors` is used; valid (adopts with zero sourced notes) even with none. |
| `actor` | object | No | Actor claim `{ id, kind: orchestrator\|subagent\|user\|external, parent?, proof? }`. Used for idempotency keying AND propagated as the actor attribution on every persisted note (explicit, `noteAnchors`-sourced, and `createNotes=true` blanks alike). |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). Requires `actor` to function. |

Nesting depth is unbounded. The root item can be at any depth; each child's depth is its resolved parent's depth + 1 — root.depth + 1 for direct children (default `parentRef: "root"`), deeper when nested under another child via `parentRef`. In attach mode, children derive depth from the existing root's depth. `parentRef` cycles are rejected at validation; cycle protection is also enforced at the database level.

#### Materialize-from-document (`docRef` + `noteAnchors`)

`docRef` plus one or more item specs' `noteAnchors: [{ noteKey (required), role (required: queue|work|review), anchor (required) }]` source note bodies from a stashed [plan document](#manage_plan_documents) instead of inlining them. `noteAnchors` may appear on `root` and/or any `children` entry.

**Anchor resolution.** Each anchor is sliced out of the document body by heading: a deterministic kebab-case slug per markdown heading (any level `#`–`######`), duplicates disambiguated with `-2`, `-3`, ... suffixes in document order. A section runs from its heading line through the line before the next heading of the same or higher level (or the end of the document) — sub-headings stay part of their parent's slice.

**Atomicity.** Resolution and slicing happen before any database write; an anchor miss, an unresolved document, a `docRef.rootId` mismatch, or an already-adopted document fails the WHOLE call with `VALIDATION_ERROR`/`RESOURCE_NOT_FOUND` — no items, dependencies, or notes are created. On success, the document is marked adopted (`adopted_by_item_id` = the created/attached root's id) in the SAME database transaction as the item/dependency/note inserts, so a concurrent adoption race also rolls back the whole tree rather than double-materializing.

**Precedence** on `(itemRef, key)` collision: explicit `notes` win over `noteAnchors`; `noteAnchors` win over `createNotes=true` blanks.

**Example.**

```json
{
  "root": {
    "title": "Feature X",
    "noteAnchors": [{ "noteKey": "feature-summary", "role": "queue", "anchor": "overview" }]
  },
  "parentId": "<project-root-uuid>",
  "docRef": { "slug": "my-plan" },
  "children": [
    {
      "ref": "t1",
      "title": "Task 1",
      "noteAnchors": [{ "noteKey": "task-scope", "role": "queue", "anchor": "task-1" }]
    }
  ]
}
```

Without `docRef`, `create_work_tree` behavior is byte-identical to calls that predate this feature.

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
  "root": {
    "id": "uuid", "title": "Authentication Feature", "role": "queue", "depth": 0, "tags": "feature",
    "schemaMatch": true, "expectedNotes": [{ "key": "acceptance-criteria", "role": "queue", "required": true, "exists": false }]
  },
  "children": [
    { "ref": "t1", "id": "uuid", "title": "Design login flow", "role": "queue", "depth": 1, "schemaMatch": false, "expectedNotes": [] },
    { "ref": "t2", "id": "uuid", "title": "Implement JWT handler", "role": "queue", "depth": 1, "schemaMatch": false, "expectedNotes": [] }
  ],
  "dependencies": [
    { "id": "uuid", "fromRef": "t1", "toRef": "t2", "type": "BLOCKS", "unblockAt": "work" }
  ],
  "notes": []
}
```

`tags` on items and `unblockAt` on dependencies are included when set; when not set, the field is omitted (not null). `schemaMatch` and `expectedNotes` are always present; `expectedNotes` is `[]` when no schema matches. When `createNotes=true` and an item's resolved schema (matched by `type` first, then `tags`) declares notes, the `notes` array is populated with created note entries:

```json
"notes": [
  { "itemRef": "t1", "key": "acceptance-criteria", "role": "queue", "id": "uuid" },
  { "itemRef": "t1", "key": "done-criteria", "role": "work", "id": "uuid" }
]
```

When `createNotes=false` (default) or no items match a schema, `notes` is `[]`.

**Inline notes example.** Use the `notes` parameter to materialize a fully-populated graph in one call:

```json
{
  "root": {"title": "Feature X"},
  "children": [{"ref": "t1", "title": "Task 1"}],
  "notes": [
    {"itemRef": "root", "key": "feature-summary", "role": "queue", "body": "Full plan..."},
    {"itemRef": "t1", "key": "task-scope", "role": "queue", "body": "Build the thing"}
  ]
}
```

When both `notes` and `createNotes: true` are provided, explicit `notes` entries win per `(itemRef, key)` — schema-required keys not covered by `notes` are added with empty bodies by `createNotes`, while explicit off-schema keys are persisted as-is.

**Strict role enforcement.** If an explicit note's `key` matches a key declared in the resolved schema for its target item, the note's `role` MUST equal the schema's role. Mismatch returns `VALIDATION_ERROR` with a message naming the index, key, expected role, and submitted role. The DB enforces `UNIQUE(itemId, key)`, so allowing a role mismatch would silently leave the gate-required role unfilled and break later `advance_item` transitions. Off-schema keys (not declared by the schema) and items with no schema match remain unconstrained — they may use any valid role.

---

### complete_tree

**Purpose.** Batch-complete (or cancel) all descendants of a root item, or an explicit list of
items, in topological dependency order. Gate enforcement applies per item: if required notes are
missing, that item fails and its downstream dependents within the set are skipped.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `rootId` | string (UUID) | Conditionally | Complete the root item and all its descendants (default). Use `includeRoot: false` to process only descendants. Mutually exclusive with `itemIds`. |
| `itemIds` | array | Conditionally | Explicit list of item UUIDs to complete. Mutually exclusive with `rootId`. |
| `trigger` | string | No | `complete` (default) or `cancel`. See gate enforcement note below. |
| `includeRoot` | boolean | No | When using `rootId`, whether to include the root item itself (default: true). Ignored when `itemIds` is used. |
| `actor` | object | No | Actor claim `{ id, kind: orchestrator\|subagent\|user\|external, parent?, proof? }`. Used with `requestId` as the idempotency key. |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). Requires `actor` to function. |

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
    { "itemId": "uuid", "title": "Design login flow", "applied": true, "trigger": "complete", "statusLabel": "done" },
    { "itemId": "uuid", "title": "Implement handler", "applied": false, "gateErrors": ["missing: done-criteria"] },
    { "itemId": "uuid", "title": "Write tests", "applied": false, "skipped": true, "skippedReason": "dependency gate failed" }
  ],
  "summary": { "total": 3, "completed": 1, "skipped": 1, "gateFailures": 1 }
}
```

`statusLabel` is included on successfully transitioned items when the item ends up with a status label (config-driven via `status_labels`; defaults: `"done"` for `complete`, `"cancelled"` for `cancel`); omitted otherwise.

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
| `notes` | array | Yes (upsert) | Array of note objects: `{ itemId, key, role, body?, bodyFromFile? }` |
| `ids` | array | No (delete) | Array of note UUIDs to delete |
| `itemId` | string (UUID) | No (delete) | Delete all notes for this WorkItem (or specific note with `key`) |
| `key` | string | No (delete) | With `itemId`: delete the single note matching this key |
| `actor` | object | No | Top-level actor claim `{ id, kind: orchestrator\|subagent\|user\|external, parent?, proof? }`. Used with `requestId` as the idempotency key (distinct from the per-note `actor` field below). |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). Requires `actor` to function. |

Providing both `ids` and `itemId` in the same delete call is an error — the server returns a validation error. Use one or the other. Deleting a non-existent note by `(itemId, key)` is not an error; it is reflected in the `notFound` count of the response.

**Note object fields (upsert):**

| Field | Type | Required | Description |
|---|---|---|---|
| `itemId` | string (UUID) | Yes | The WorkItem this note belongs to |
| `key` | string | Yes | Logical name for this note (e.g., `requirements`, `done-criteria`) |
| `role` | string | Yes | Workflow phase: `queue`, `work`, or `review` |
| `body` | string | No | Note content (default: `""`). Mutually exclusive with `bodyFromFile` — providing both fails that note. |
| `bodyFromFile` | string | No | Server-side file path read in place of `body`. Resolved strictly relative to the agent config root (`AGENT_CONFIG_DIR`, falling back to the server's working directory) — absolute paths, `..` escapes, and symlink escapes are rejected. File must exist and be ≤65536 bytes. CRLF line endings are normalized to LF on read. |
| `actor` | object | No | Optional actor claim — see Actor Attribution section |

**Note body length limits.** When the resolved schema declares `maxLength` for a note's `key`, the resolved body (from `body` or `bodyFromFile`) is checked against it after resolution. The top-level config `note_limits.mode` controls enforcement: `warn` (default) accepts the note and adds a `warning` field to that note's result naming the limit and actual size; `reject` fails that note with a structured error: `{ "code": "NOTE_BODY_TOO_LONG", "key": "...", "maxLength": N, "actualLength": N }` in its `failures` entry. `note_limits` is layered per-root: a per-root `manage_project_config` push that explicitly sets `note_limits.mode` wins for that root's items; a per-root document that omits `note_limits` entirely falls through to the global mode unchanged — see [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md).

Each upsert note element may include an optional `actor` object:
- `id` (required string): Identifier for the actor writing this note
- `kind` (required string): One of `orchestrator`, `subagent`, `user`, `external`
- `parent` (optional string): ID of the dispatching agent (forms delegation chain)
- `proof` (optional string): Opaque credential blob (persisted, unused by Stage 1)

When provided, the upsert response includes an `actor` object on each successfully upserted note, and the note is persisted with actor claim data that appears in subsequent `query_notes` responses. A `verification` object is also included, *except* when no actor verifier is configured (the default `noop` verifier) — in that case `verification` is omitted entirely, since a no-op result carries no information beyond "no verifier is configured." See [Verification Record](#verification-record).

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
      "key": "feature-summary",
      "role": "queue",
      "body": "The handler must validate JWT signatures and return 401 on failure."
    }
  ]
}

// Fill a note from a file — verbatim artifacts (logs, diffs, test output) bypass inline paste
{
  "operation": "upsert",
  "notes": [
    { "itemId": "550e8400-e29b-41d4-a716-446655440001", "key": "implementation-notes", "role": "work", "bodyFromFile": "logs/test-run.txt" }
  ]
}

// Delete all notes for an item
{ "operation": "delete", "itemId": "550e8400-e29b-41d4-a716-446655440001" }
```

**Response (upsert).**

```json
{
  "notes": [
    { "id": "uuid", "itemId": "uuid", "key": "feature-summary", "role": "queue" }
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

Each note in the `notes` response array also carries a `warning` field when its body exceeded a schema `maxLength` under `note_limits.mode: warn` (naming the limit and actual length). Under `mode: reject`, an over-limit note instead appears in `failures` with `code: "NOTE_BODY_TOO_LONG"`, `key`, `maxLength`, and `actualLength`.

Note that `itemContext[itemId].guidancePointer` here carries the **full** guidance text (unlike `get_context`/`advance_item`, which return only a `guidanceKey` reference) — this is one of the two places full guidance text is returned directly, the other being the gate-failure `missingNotes` payload (see `advance_item`).

The `itemContext` map is keyed by each `itemId` that had at least one successful upsert. For each item:
- `guidancePointer` — the `guidance` text from the first unfilled required note in the item's current phase, or `null` if all required notes are filled (or no schema matches).
- `skillPointer` — the skill name declared for the first unfilled required note; present only when that note's schema entry declares a `skill`, omitted otherwise.
- `noteProgress` — `{ filled, remaining, total }` counts of required notes for the current phase, or `null` if the item has no matching schema or is in terminal state.

This eliminates the need to call `get_context` after each `manage_notes` upsert to check remaining work.

**Response (delete).**

```json
{ "deleted": 1, "notFound": 0, "failed": 0 }
```

- `deleted` — number of notes successfully deleted
- `notFound` — number of `(itemId, key)` lookups where the key did not exist (not treated as an error)
- `failed` — number of delete attempts that produced an error (with a `failures` array when non-zero)

---

### query_notes

**Purpose.** Read-only queries for Notes: fetch a single note by UUID, list all notes for a
WorkItem, or FTS5 full-text search across note bodies.

**Operations.** `get`, `list`, `search`

#### Key Parameters — get

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"get"` |
| `noteId` | string (UUID) | Yes | Note UUID |

#### Key Parameters — list

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"list"` |
| `itemId` | string (UUID or 4+ char prefix) | Yes | WorkItem whose notes to list |
| `role` | string | No | Filter by phase: `queue`, `work`, or `review` |
| `keys` | array of strings | No | Filter results to notes with these keys |
| `includeBody` | boolean | No | Include note body in response (default: **false**); when false, each note includes `bodyLength` instead |

#### Key Parameters — search (FTS5)

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"search"` |
| `query` | string | Yes | FTS5 search terms — multiple words produce implicit AND. Pass plain terms; special characters are auto-escaped. |
| `scope` | object | No | `itemId` (UUID — single item's notes), `ancestorId` (UUID — subtree of items). To filter by note role use `list` instead. |
| `matchMode` | string | No | `"auto"` (default), `"substring"`, `"text"` — same semantics as `query_items.search` |
| `snippet` | boolean | No | Include ~32-token snippet with highlights (default: true). Markdown preserved. |
| `explain` | boolean | No | Include raw FTS5 ranks per hit (default: false) |
| `limit` | integer | No | Max hits (default: 20, max: 100) |
| `offset` | integer | No | Skip N hits for pagination (default: 0) |

**Examples.**

```json
// List queue-phase notes for an item (bodies omitted — the default)
{ "operation": "list", "itemId": "550e8400-e29b-41d4-a716-446655440001", "role": "queue" }

// List specific notes by key, with full bodies
{ "operation": "list", "itemId": "550e8400-e29b-41d4-a716-446655440001", "keys": ["feature-summary"], "includeBody": true }

// FTS5 search across all note bodies for "authentication"
{ "operation": "search", "query": "authentication token", "limit": 10 }

// FTS5 search scoped to a feature's subtree
{ "operation": "search", "query": "migration strategy", "scope": { "ancestorId": "550e8400-e29b-41d4-a716-446655440000" } }
```

**Response (list).**

```json
{
  "notes": [
    {
      "id": "uuid",
      "key": "feature-summary",
      "role": "queue",
      "bodyLength": 42,
      "createdAt": "2025-01-01T00:00:00Z",
      "modifiedAt": "2025-01-01T00:00:00Z"
    }
  ],
  "total": 1
}
```

Note objects omit the `itemId` echo in `list` results (you already supplied it). Each note carries `bodyLength` in place of `body` unless `includeBody: true` is passed, in which case `body` replaces `bodyLength`.

**Response (search — FTS5 mode).**

```json
{
  "hits": [
    {
      "kind": "note",
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "noteKey": "implementation-notes",
      "field": "body",
      "snippet": "The <mark>authentication</mark> <mark>token</mark> is stored…",
      "score": 0.0164,
      "matchedIn": ["trigram", "text"]
    }
  ],
  "totalHits": 3,
  "nextOffset": null,
  "truncated": false
}
```

`kind` is always `"note"` for note hits. `noteKey` is the note's key within its item. `field` is
always `"body"` (notes have a single body field). Score interpretation and `truncated` semantics are
the same as `query_items.search`.

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
| `type` | string | No | Create: shared default type — `BLOCKS` (default), `IS_BLOCKED_BY`, `RELATES_TO`. Delete-by-relationship: optional filter — delete only edges of this type. |
| `unblockAt` | string | No | Shared default threshold: `queue`, `work`, `review`, `terminal` (default: terminal) |
| `itemIds` | array | Yes (linear) | Ordered UUIDs: A→B, B→C, C→D |
| `fromItemId` | string (UUID) | Yes (fan-out); Cond. (delete) | Fan-out: single source item. Delete-by-relationship: source side. |
| `toItemIds` | array | Yes (fan-out) | Target item UUIDs |
| `fromItemIds` | array | Yes (fan-in) | Source item UUIDs |
| `toItemId` | string (UUID) | Yes (fan-in); Cond. (delete) | Fan-in: single target item. Delete-by-relationship: target side. |
| `dependencyId` | string (UUID) | Cond. (delete) | Delete a single dependency by its UUID |
| `deleteAll` | boolean | No (delete) | Delete ALL deps for `fromItemId` or `toItemId` |
| `actor` | object | No | Actor claim `{ id, kind: orchestrator\|subagent\|user\|external, parent?, proof? }`. Used with `requestId` as the idempotency key. |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). Requires `actor` to function. |

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
  "failed": 2,
  "failures": [
    { "index": 0, "error": "Dependency at index 0: invalid type 'FOO'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO" },
    { "index": 2, "error": "Dependency at index 2: 'fromItemId' could not be resolved: not-a-uuid" }
  ]
}
```

Note: atomicity is preserved — either all dependencies are created or none. On any validation failure, `created` is always 0 and `failed` **always equals `failures.length`**. Every element of the `dependencies` array is validated independently, so `failures` reports **one entry per invalid element**, each carrying that element's 0-based `index` — a batch with three specs where the first and third are malformed returns two failures at indices 0 and 2 (the valid middle element is still not created). A batch-level rejection that is a property of the whole batch rather than one element — a cycle formed across multiple specs, or a duplicate of an existing dependency — is reported as a single failure entry.

**Constraint: `RELATES_TO` and `unblockAt`.** Specifying `unblockAt` on a `RELATES_TO` dependency is a validation error. `RELATES_TO` dependencies have no blocking semantics and do not support an unblock threshold; providing one will return a validation failure response.

**Response (delete by relationship).**

```json
{
  "fromItemId": "uuid-a",
  "toItemId": "uuid-b",
  "deleted": 1
}
```

Optionally pass `type` to delete only edges of that type between the two items; omit it to delete all edges between them regardless of type.

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
detail enrichment, BFS graph traversal, and reverse-edge backlink lookup.

**Operations.** `get` (default), `backlinks`

#### Key Parameters — get (default)

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"get"` |
| `itemId` | string (UUID or 4+ char prefix) | Yes | WorkItem to query dependencies for |
| `direction` | string | No | `incoming`, `outgoing`, or `all` (default: `all`). Incoming = things that block this item; outgoing = things this item blocks. |
| `type` | string | No | Filter: `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO` |
| `includeItemInfo` | boolean | No | Include title, role, priority for related items (default: false) |
| `neighborsOnly` | boolean | No | When false, perform BFS graph traversal returning a topologically-ordered chain and max depth (default: true) |

#### Key Parameters — backlinks

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string | Yes | `"backlinks"` |
| `itemId` | string (UUID or 4+ char prefix) | Yes | The item whose incoming edges you want to find — i.e., other items that point AT this item |
| `type` | string | No | Narrow to one dependency type: `BLOCKS`, `IS_BLOCKED_BY`, or `RELATES_TO` |

**Examples.**

```json
// Incoming blocking dependencies (get operation)
{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "direction": "incoming", "includeItemInfo": true }

// Full dependency graph traversal
{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "neighborsOnly": false }

// Find all items that block REQ-42
{ "operation": "backlinks", "itemId": "550e8400-e29b-41d4-a716-446655440042", "type": "BLOCKS" }

// Find all items that reference FEAT-7
{ "operation": "backlinks", "itemId": "550e8400-e29b-41d4-a716-446655440007" }
```

**Response (get).**

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
      "fromItem": { "title": "Design API", "role": "terminal", "priority": "high" },
      "toItem": { "title": "Implement API", "role": "work", "priority": "high" }
    }
  ],
  "counts": { "incoming": 1, "outgoing": 0, "relatesTo": 0 },
  "graph": { "chain": ["uuid-a", "uuid-b"], "depth": 1 }
}
```

`graph` is only included when `neighborsOnly=false`. `fromItem` and `toItem` are included only when `includeItemInfo=true`, and each is present only if the referenced item still exists.

**Response (backlinks).**

```json
{
  "backlinks": [
    { "fromItemId": "uuid-a", "type": "BLOCKS", "fromTitle": "Implement auth service" },
    { "fromItemId": "uuid-b", "type": "RELATES_TO", "fromTitle": "Auth design doc" }
  ],
  "total": 2
}
```

Each backlink entry represents another item that holds a dependency edge pointing at your `itemId`.
`fromTitle` is JOIN-fetched for convenience. Uses the existing `to_item_id` index — no full table scan.

---

## Category: Workflow

### advance_item

**Purpose.** Trigger-based role transitions for WorkItems with dependency validation, note-schema
gate enforcement, cascade detection, and unblock reporting. Supports batch transitions.

This tool and the REST `POST /items/{id}/advance` route share a single advance pipeline
(`AdvanceService`): ownership pre-check → resolve → validate → required-note gate → apply → cascade
detection → unblock detection. The MCP path enforces claim ownership; the REST path bypasses it (see
`api-rest.md`). Both enforce the same note gates and report the same cascade/unblock results.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `transitions` | array | Conditional | Array of transition objects: `[{ itemId, trigger, summary?, actor? }]`. Required unless the singular-form sugar (`itemId` + `trigger`) is used instead. |
| `itemId` | string (UUID or 4+ char prefix) | Conditional | Singular-form sugar: advance a single item without a `transitions` array. Provide with `trigger` (and optional top-level `summary`/`actor`); the server wraps it into a one-element `transitions` array. Ignored when `transitions` is present. |
| `trigger` | string | Conditional | Singular-form sugar: the trigger for the single item named by top-level `itemId`. |
| `summary` | string | No | Singular-form sugar (optional): transition summary for the single top-level `itemId`. |
| `actor` | object | No | Singular-form sugar (optional): actor claim for the single top-level `itemId`, same shape as a transition's `actor`. |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. Repeated calls with the same `(actor.id, requestId)` within ~10 minutes return the cached response without re-executing. Uses the first transition's `actor.id` as the idempotency key actor. |

Provide **either** a `transitions` array **or** the singular `itemId` + `trigger`. If `transitions` is present, the top-level singular fields are ignored.

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

When provided, the response includes an `actor` object on each successful transition. A `verification` object is also included, *except* when no actor verifier is configured (the default `noop` verifier) — in that case `verification` is omitted entirely. See [Verification Record](#verification-record).

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

// Single transition — singular-form sugar (equivalent to the above)
{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "trigger": "start" }

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
      "newRole": "work",
      "statusLabel": "in-progress",
      "applied": true,
      "cascadeEvents": [
        { "itemId": "uuid-parent", "title": "Auth Feature", "previousRole": "queue", "targetRole": "work", "applied": true }
      ],
      "unblockedItems": [{ "itemId": "uuid-next", "title": "Next task" }],
      "expectedNotes": [
        { "key": "done-criteria", "role": "work", "required": true, "exists": false }
      ],
      "guidanceKey": "done-criteria",
      "noteProgress": { "filled": 0, "remaining": 1, "total": 1 }
    }
  ],
  "summary": { "total": 1, "succeeded": 1, "failed": 0 }
}
```

`previousRole` and `trigger` are omitted from successful results — the caller supplied the trigger, and `newRole` is the outcome. Both remain present on `cascadeEvents` entries and on failed results (see below). `cascadeEvents` and `unblockedItems` are omitted entirely when empty (there is no top-level `allUnblockedItems` aggregate — sum `unblockedItems` across `results` if you need a batch total). `expectedNotes` is always present (`[]` when no schema matches the item's tags) and is keys-only: `key`, `role`, `required`, `exists` (`filled` appears only in `get_context`'s item-mode `schema` entries, not here). `statusLabel` (string, optional) is present when the transition set a status label (config-driven via `status_labels`; defaults: `"in-progress"` for `start`, `"done"` for `complete`, `"blocked"` for `block`, `"cancelled"` for `cancel`; `resume`/`reopen` set none). `status_labels` is layered per-root: a per-root `manage_project_config` push resolves a label for a given trigger from that root's `status_labels` map first, falling through to the global config PER TRIGGER when the root's map doesn't mention that trigger (or has no `status_labels` section at all). `summary` (string, optional) echoes the transition's input annotation when one was supplied.

`guidanceKey` (string, optional) names the first unfilled required note with guidance for the **new** role; omitted when no schema matches, no required notes exist for the new role, or all required notes are already filled. Resolve the full guidance text via `query_items(operation="schema", itemId=...)`.

`skillPointer` (string, optional): Skill name to invoke for the first unfilled required note. Omitted when no skill is configured or all required notes are filled.

`noteProgress` provides counts of required notes for the new role: `filled` (notes that exist with non-blank body), `remaining` (missing or blank), and `total` (filled + remaining). Omitted from the response when no schema matches the item's tags, or when the new role is terminal (e.g. after `complete`/`cancel`).

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
  "summary": { "total": 1, "succeeded": 0, "failed": 1 }
}
```

`blockers` is only present when the transition failed due to dependency constraints. `error` contains a human-readable description of why the transition was rejected. Note that (unlike success results) `trigger` **is** present on failed results, while `previousRole`, `newRole`, and `expectedNotes` are absent.

**Gate-blocked failure.** When the gate itself rejects the transition (missing required notes), the result includes a `missingNotes` array instead of `blockers`. Each entry carries the **full** guidance text — this and `manage_notes(upsert)`'s `itemContext.guidancePointer` are the only two places full guidance text is returned directly (everywhere else you get a `guidanceKey`/`skillPointer` reference and resolve via `query_items(operation="schema")`):

```json
{
  "results": [
    {
      "itemId": "uuid",
      "trigger": "start",
      "applied": false,
      "error": "Gate check failed: required notes not filled for queue phase: feature-summary",
      "missingNotes": [
        { "key": "feature-summary", "description": "...", "guidance": "...", "skill": "spec-quality" }
      ]
    }
  ],
  "summary": { "total": 1, "succeeded": 0, "failed": 1 }
}
```

`guidance` and `skill` are omitted per-entry when unset.

**Ownership / policy rejection.** When a transition is rejected because another agent holds a live claim, or by `degradedModePolicy=reject`, the failed result carries structured fields alongside `error`: `errorKind`, `errorCode` (`not_claim_holder` or `rejected_by_policy`), and — for ownership rejections — `contendedItemId`.

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
- **Item mode** — pass `mode: "item"` (or provide `itemId`): note schema, existing notes with fill status, and gate status for a specific item.
- **Session resume** — pass `mode: "session-resume"` (or provide `since`): active items, recent role transitions since the timestamp, and stalled items.
- **Health check** — pass `mode: "health-check"` (or omit all mode-selecting params): all active items (work/review), blocked items, and stalled items.

When `mode` is omitted, the mode is inferred from which parameters are present (`itemId` → item, `since` → session-resume, neither → health-check).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `mode` | string | No | Explicit mode: `"item"`, `"session-resume"`, or `"health-check"`. When provided, takes precedence over implicit detection. |
| `itemId` | string (UUID) | No | Item UUID for item mode. Required when `mode="item"`. |
| `since` | string (ISO 8601) | No | Timestamp for session-resume mode. Required when `mode="session-resume"`. |
| `ancestorId` | string (UUID or 4+ char prefix) | No | Limit health-check and session-resume results (active/blocked/stalled items, claim summary) to this item's subtree (any depth, inclusive). Ignored in item mode. Omitted = unscoped. Does NOT scope `recentTransitions` in session-resume mode — see note below. |
| `includeAncestors` | boolean | No | Include `ancestors` array on each listed item (default: false) |
| `limit` | integer (1–200) | No | Max role transitions in session-resume mode (default: 10) |

**Examples.**

```json
// Health check (explicit mode)
{ "mode": "health-check" }

// Session resume
{ "mode": "session-resume", "since": "2025-01-01T09:00:00Z", "includeAncestors": true }

// Item gate check
{ "mode": "item", "itemId": "550e8400-e29b-41d4-a716-446655440001" }
```

**Response (item mode).**

```json
{
  "mode": "item",
  "item": { "id": "uuid", "title": "JWT Handler", "role": "queue", "tags": "task-implementation", "depth": 1 },
  "schema": [
    { "key": "task-scope", "role": "queue", "required": true, "exists": true, "filled": true },
    { "key": "done-criteria", "role": "work", "required": true, "exists": false, "filled": false }
  ],
  "gateStatus": { "canAdvance": true, "phase": "queue", "missing": [] },
  "claimDetail": {
    "claimedBy": "agent-worker-42",
    "claimedAt": "2026-01-01T12:00:00Z",
    "claimExpiresAt": "2026-01-01T12:15:00Z",
    "originalClaimedAt": "2026-01-01T12:00:00Z",
    "isExpired": false
  }
}
```

(`guidanceKey` and `skillPointer` are omitted here because the current phase, `queue`, has no missing required notes.)

`guidanceKey` (string, optional) names the first unfilled required note with guidance for the **current** role. Omitted when no schema matches, no required notes exist, or all are filled. Resolve the full guidance text via `query_items(operation="schema", itemId=...)`.

`skillPointer` (string, optional): Skill name to invoke for the first unfilled required note. Omitted when no skill is configured or all required notes are filled. Derived from the `skill` field on the first unfilled required note in the schema.

`get_context` does not return `noteProgress` — `gateStatus` (`canAdvance`, `phase`, `missing[]`) is the canonical gate signal for the current phase. Required/remaining/total counts are still available via `advance_item` responses and `manage_notes(upsert)`'s `itemContext`.

Each entry in the `schema` array is keys-only: `key`, `role`, `required`, `exists`, `filled`. Resolve `description`/`guidance`/`skill` for any entry via `query_items(operation="schema", itemId=...)`.

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

**Response (session-resume mode).**

```json
{
  "mode": "session-resume",
  "since": "2025-01-01T09:00:00Z",
  "activeItems": [{ "id": "uuid", "title": "...", "role": "work", "tags": "task-implementation" }],
  "recentTransitions": [
    { "itemId": "uuid", "title": "JWT Handler", "fromRole": "queue", "toRole": "work", "at": "2025-01-01T09:05:00Z", "actorId": "agent-1" }
  ],
  "stalledItems": [{ "id": "uuid", "title": "...", "role": "work", "missingNotes": ["done-criteria"], "guidanceKey": "done-criteria" }]
}
```

`limit` caps `recentTransitions` (default 10, max 200). Each transition entry is `{ itemId, title, fromRole, toRole, at, actorId? }` — `actorId` is omitted when the transition had no actor claim. There is no claim summary in this mode — use item mode or health-check mode for claim visibility. `stalledItems` entries may include `guidanceKey`/`skillPointer` for the first missing note, same semantics as item mode.

**Known limitation:** when `ancestorId` is set, `activeItems` and `stalledItems` are scoped to the subtree, but `recentTransitions` is NOT — it always reflects transitions across the whole tree. Scoping transitions would require resolving the subtree set and filtering the transition-item lookup by it, which was flagged as invasive for the initial implementation.

**Response (health-check mode).**

```json
{
  "mode": "health-check",
  "activeItems": [{ "id": "uuid", "title": "...", "role": "work" }],
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
ascending (quick wins first) by default.

**Ancestor-claim filtering (strict mode).** When `includeClaimed=false` (the default), items whose
ancestor chain contains any live claim are excluded from results — even if the item itself is unclaimed.
This prevents `get_next_item` from surfacing sub-items of in-progress features to agents that would
be competing with the feature's orchestrator. Root items (no parent) are unaffected. When
`includeClaimed=true`, ancestor-claim filtering is NOT applied — that path uses `findForNextItem`
and is intentionally inclusive.

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `role` | string | No | Role to query: `queue`, `work`, `review`, or `blocked` (default: `queue`) |
| `parentId` | string (UUID) | No | Scope recommendations to items under this parent |
| `ancestorId` | string (UUID or 4+ char prefix) | No | Limit recommendations to this item's subtree (any depth, inclusive). Composes with `parentId` (both applied). Omitted = unscoped, byte-identical to prior behavior. |
| `limit` | integer (1–20) | No | Number of recommendations (default: 1) |
| `includeDetails` | boolean | No | Include `summary`, `tags`, and `parentId` in each recommendation (default: false) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each recommendation (default: false) |
| `includeClaimed` | boolean | No | When `false` (default), items with an active claim are filtered out. When `true`, claimed items are included but only a boolean `isClaimed` field is added — the claiming agent's identity is never exposed. |

#### Filter Parameters (all optional)

| Parameter | Type | Description |
|---|---|---|
| `tags` | string (comma-separated) | Return only items whose tags contain any of the listed values (any-match) |
| `priority` | string (`high`\|`medium`\|`low`) | Return only items with this exact priority |
| `type` | string | Return only items of this type (exact match) |
| `complexityMax` | integer (1–10) | Return only items with complexity ≤ this value |
| `createdAfter` | string (ISO 8601) | Return only items created after this timestamp |
| `createdBefore` | string (ISO 8601) | Return only items created before this timestamp |
| `modifiedAfter` | string (ISO 8601) | Return only items last modified after this timestamp |
| `modifiedBefore` | string (ISO 8601) | Return only items last modified before this timestamp |
| `roleChangedAfter` | string (ISO 8601) | Return only items whose role last changed after this timestamp |
| `roleChangedBefore` | string (ISO 8601) | Return only items whose role last changed before this timestamp |
| `orderBy` | string (`priority`\|`oldest`\|`newest`) | Ordering strategy: `priority` (default — HIGH first then complexity ascending / quick wins), `oldest` (createdAt ascending / FIFO drain), `newest` (createdAt descending / recency-biased) |

The tiered claim disclosure contract applies under all filter combinations: `claimedBy` identity is never exposed by this tool, regardless of which filters are active.

**Discovery patterns for multi-role fleets:**
- Work-group agents: `get_next_item()` (default `role=queue`)
- Review-group agents: `get_next_item(role="review")`
- Triage-group agents: `get_next_item(role="blocked")`
- Fleet health debugging: `get_next_item(includeClaimed=true)` to see claimed items without identity disclosure
- Fair-share FIFO drain: `get_next_item(orderBy="oldest")` — processes items in creation order

**Example.**

```json
{ "parentId": "550e8400-e29b-41d4-a716-446655440000", "limit": 3, "includeDetails": true }
```

#### Filtered Queries

Filter by tag (any-match):

```json
{ "tags": "task-implementation,feature-task", "limit": 5 }
```

Filter by priority:

```json
{ "priority": "high", "role": "queue" }
```

Filter by maximum complexity (quick wins only):

```json
{ "complexityMax": 3, "orderBy": "priority" }
```

Time-window filter — items added to the queue in the last hour:

```json
{ "createdAfter": "2026-01-01T11:00:00Z", "createdBefore": "2026-01-01T12:00:00Z" }
```

FIFO queue drain — oldest unclaimed item first:

```json
{ "orderBy": "oldest" }
```

**Response (unclaimed items only; request above with `includeDetails=true`).**

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

`summary`, `tags`, and `parentId` appear only when `includeDetails=true`; the default response includes `itemId`, `title`, `role`, `priority`, and `complexity`.

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
| `claims` | array | No | Items to claim. Each entry uses **ID mode** or **selector mode** (see below). At least one of `claims` or `releases` must be non-empty. |
| `releases` | array | No | Items to release: `[{ itemId (UUID or hex prefix) }]`. |
| `requestId` | string (UUID) | **Yes** | Client-generated UUID for idempotency. Required — `claim_item` is a fleet-mode tool and idempotency is a hard contract. Single-orchestrator deployments do not use `claim_item`; fleet callers are in a multi-agent context where network retries are a real concern. Repeated calls with the same (`actor.id`, `requestId`) within ~10 minutes return the cached response without re-executing. |

**`claims[]` entry schema — two mutually exclusive modes:**

- **ID mode:** `{ itemId (required UUID or hex prefix), ttlSeconds? (optional int, default 900, max 86400), agentId? (optional — overridden by verified actor), claimRef? (optional string, max 64 chars — echoed verbatim in the result for caller correlation) }`
- **Selector mode:** `{ selector (required object — see below), ttlSeconds? (optional int, default 900, max 86400), claimRef? (optional string, max 64 chars — echoed verbatim in the result) }`

Each entry must have exactly one of `itemId` or `selector`. The `claims` array must contain at most 1 entry (see single-claim-per-call note below).

**`selector` object fields** (all optional within the object; the object itself is required in selector mode):

| Field | Type | Description |
|---|---|---|
| `role` | string | Role to search in: `queue`\|`work`\|`review`\|`blocked` (default: `queue`) |
| `parentId` | string (UUID or hex prefix) | Filter to items under this parent (accepts full UUID or 4+ char hex prefix, like other `parentId` fields on the surface) |
| `tags` | string (comma-separated) | Filter by tags (any-match) |
| `priority` | string | `high`\|`medium`\|`low` |
| `type` | string | Item type (exact match) |
| `complexityMax` | integer (1–10) | Maximum complexity |
| `createdAfter` | string (ISO 8601) | Items created after this timestamp |
| `createdBefore` | string (ISO 8601) | Items created before this timestamp |
| `roleChangedAfter` | string (ISO 8601) | Items whose role changed after this timestamp |
| `roleChangedBefore` | string (ISO 8601) | Items whose role changed before this timestamp |
| `orderBy` | string | `priority`\|`oldest`\|`newest` (default: `priority`) |

The selector filter shape is identical to the `get_next_item` filter parameters — the two tools share the same underlying eligibility logic (`NextItemRecommender`), so the same filter expression produces the same item ranking in both tools.

**Claim semantics:**

- **One claim per agent.** Claiming item B auto-releases the agent's existing claim on item A (if any). No extra parameter needed.
- **Re-claim as TTL extension.** Calling `claim_item` again on an already-held item refreshes `claimExpiresAt` but preserves `originalClaimedAt`. Use this for heartbeats on long-running work (recommended cadence: TTL/2 = 450s for the default 900s TTL).
- **Terminal items cannot be claimed.** QUEUE, WORK, REVIEW, and BLOCKED items are all claimable.
- **Identity resolution.** `actor.id` is used as the claim identity, subject to `degradedModePolicy`. If JWKS verification succeeds, the verified `actor.id` (from the JWT `sub` claim) is used; otherwise the self-reported `actor.id` is used (unless `degradedModePolicy=reject`, in which case the claim fails with `rejected_by_policy`).
- **Passive expiry.** There is no background reaper. Expired claims are filtered at read time. Crash recovery happens automatically via TTL.
- **DB-side time.** All timestamps (`claimedAt`, `claimExpiresAt`) are set via SQLite `datetime('now', ...)` — they are UTC. Operators inspecting raw rows must not assume host-local time.
- **Per-entry `agentId` vs verified `actor.id`.** When the configured verifier resolves a trusted identity from `actor.proof`, that verified id becomes the claim holder and any `agentId` on the individual claim entry is ignored. The server logs a warning when the two disagree. Callers without a verifier configured may still supply `agentId`; it has no special status beyond providing a self-reported identity.
- **Ancestor-claim filtering in selector mode.** When using selector mode, items whose ancestor chain contains a live claim held by a *different* agent are excluded from the eligible set. Items under an ancestor claimed by the *same* agent (the requesting `actor.id`) are retained — enabling the hybrid fleet pattern: claim a feature at the top level, then orchestrate its child sub-tree. Root items (no parent) are unaffected. This filter is applied *before* dependency-blocking and ordering. Items excluded by this filter appear as absent from selector results; the competing agent's identity is never disclosed. **ID-mode claims bypass this filter entirely** — the per-item `claim()` path goes directly to the item, regardless of ancestor claim state.

**Claim outcome codes per item:**

| Outcome | Meaning |
|---|---|
| `success` | Claim placed or TTL refreshed. Response includes own claim metadata. Selector claims also include `selectorResolved: true`. |
| `no_match` | Selector found no eligible items; `kind=permanent`, `code="no_match"`. No claim attempted, no `retryAfterMs`, no `itemId`. |
| `already_claimed` | Another agent holds a live claim. Response includes `retryAfterMs` (no competing agent identity). |
| `not_found` | No item with that ID. |
| `terminal_item` | Item is in TERMINAL role; cannot be claimed. |
| `rejected_by_policy` | Actor verification rejected by `degradedModePolicy=reject`. All claims in the batch fail. |
| `db_error` | Transient database error during the claim attempt (`kind=transient`, with `code`, `message`, `contendedItemId`). Safe to retry. |

**Release outcome codes per item:**

| Outcome | Meaning |
|---|---|
| `success` | Claim cleared. |
| `not_claimed_by_you` | Item is not claimed by this agent (or is unclaimed). |
| `not_found` | No item with that ID. |
| `db_error` | Transient database error during the release attempt (`kind=transient`). Safe to retry. |

**Tiered disclosure rule.** On `already_claimed`, the response includes `kind`, `contendedItemId`, and `retryAfterMs` — never the competing agent's identity. This prevents claim sniping and jealousy patterns.

**Idempotency replay.** A `(actor, requestId)` cache hit replays the resolved response verbatim — including the same `itemId` for selector calls. The selector is **not** re-evaluated against fresh queue state on retry; the first-resolution result is what you get.

> **Single-claim-per-call:** The `claims` array must contain at most 1 entry. `claims.size > 1` returns a `validation_error` with code `multi_claim_not_supported` immediately, regardless of whether entries use `itemId` or `selector` mode.
>
> The cap derives from the heartbeat write-budget assumption (one TTL refresh per agent per cycle). A future `claim_heartbeats` table mitigation could remove the constraint.
>
> Releases array (`releases`) continues to support N entries — only `claims` is restricted. Issue one `claim_item` call per item you want to claim.

#### Atomic Find-and-Claim (Selector Mode)

Selector mode resolves a filter+rank query and claims the top match in a single MCP call. This eliminates the **user-facing** race window of the two-call pattern (`get_next_item` → `claim_item(itemId=...)`), where another agent can claim the recommended item between your two calls. A much smaller server-side window between recommend and claim still exists and surfaces as `already_claimed` — typically rare in practice.

**Request:**

```json
{
  "claims": [{
    "selector": {
      "priority": "high",
      "complexityMax": 4,
      "orderBy": "oldest"
    },
    "ttlSeconds": 900,
    "claimRef": "worker-7-round-42"
  }],
  "actor": { "id": "worker-agent-7", "kind": "subagent", "parent": "orchestrator-1" },
  "requestId": "550e8400-e29b-41d4-a716-446655440099"
}
```

**Response (item found and claimed):**

```json
{
  "claimResults": [
    {
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "outcome": "success",
      "selectorResolved": true,
      "claimRef": "worker-7-round-42",
      "claimedBy": "worker-agent-7",
      "claimedAt": "2026-01-01T12:00:00Z",
      "claimExpiresAt": "2026-01-01T12:15:00Z"
    }
  ],
  "releaseResults": []
}
```

**Response (no eligible items match the selector):**

```json
{
  "claimResults": [
    {
      "outcome": "no_match",
      "kind": "permanent",
      "code": "no_match",
      "claimRef": "worker-7-round-42"
    }
  ],
  "releaseResults": []
}
```

`no_match` means the queue is genuinely empty for the given filters at this moment. `kind=permanent` signals there is no point retrying with the same filters immediately — the condition will only change when new items enter the queue.

`selectorResolved: true` on success confirms the `itemId` in the result was resolved from the selector, not supplied directly by the caller.

#### Example (ID Mode)

```json
{
  "claims": [{ "itemId": "550e8400-e29b-41d4-a716-446655440001", "ttlSeconds": 900 }],
  "actor": { "id": "worker-agent-7", "kind": "subagent", "parent": "orchestrator-1" },
  "requestId": "550e8400-e29b-41d4-a716-000000000001"
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
      "claimExpiresAt": "2026-01-01T12:15:00Z"
    }
  ],
  "releaseResults": []
}
```

`originalClaimedAt` is omitted here because it equals `claimedAt` (a fresh claim with no prior claim to preserve). It appears — and differs from `claimedAt` — only on a TTL-refresh re-claim.

**Response (claim contested).**

```json
{
  "claimResults": [
    {
      "itemId": "550e8400-e29b-41d4-a716-446655440001",
      "outcome": "already_claimed",
      "kind": "transient",
      "contendedItemId": "550e8400-e29b-41d4-a716-446655440001",
      "retryAfterMs": 420000
    }
  ],
  "releaseResults": []
}
```

No `summary` block is returned in any `claim_item` response — counts are derivable from the `claimResults`/`releaseResults` array sizes and each entry's `outcome`.

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
| `ancestorId` | string (UUID or 4+ char prefix) | No | Limit candidate items to this item's subtree (any depth, inclusive). Composes with `parentId` (both applied). Omitted = unscoped, byte-identical to prior behavior. |
| `includeDetails` | boolean | No | Include `summary` and `tags` for each blocked item (default: false) |
| `includeAncestors` | boolean | No | Include `ancestors` array on each blocked item (default: false) |

**Example.**

```json
{ "includeDetails": true, "includeAncestors": true }
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

## Category: System

### manage_project_config

**Purpose.** Transport-agnostic config sync: a client pushes its repo's `.taskorchestrator/config.yaml`
text keyed by a project root's WorkItem UUID; `ToolExecutionContext.resolveSchema()` layers this
per-root config over the global config on every schema-resolving read (see `PerRootConfigService` —
hot-reload is a property of that read path, so no separate reload call is needed after a push).

Supports two operations, selected via `operation`:

#### `push`

Validates, in order:
1. `configYaml` must not exceed **128 KiB** (131,072 bytes, UTF-8) — rejected before any parse
   attempt (CWE-770: bounds parse cost and storage growth against a hostile or oversized payload).
2. `rootId` must resolve to an existing WorkItem.
3. That WorkItem must be depth 0 (a project root) — configs anchor to roots only.
4. If the root's `type` is not `"project"`, the push still succeeds but the response includes a
   non-fatal `warning` field (a naming convention, not an enforced constraint).
5. `configYaml` is parsed via the same parser `PerRootConfigService` uses on every read, using a
   YAML *safe* constructor that only ever builds plain maps/lists/scalars (see **Parse safety**
   below). Unparseable or unsafe YAML is rejected here — nothing is stored — so a broken or
   malicious config can never silently exist server-side and fall through to the global schema on
   a later read.
6. If the parsed document embeds a top-level `project.rootId` that parses as a UUID and differs
   from `rootId`, the push is rejected as a `rootId` mismatch (see **rootId mismatch guard**
   below) — unless `force: true` is passed.
7. If the incoming document's fingerprint is **superseded** — it appears in the root's fingerprint
   history but is not the current fingerprint (i.e. the server has already moved past this exact
   content) — the push is rejected as stale (see **Fast-forward guard** below) — unless
   `force: true` is passed. A fingerprint the server has never seen (`unknown`) pushes normally;
   the current fingerprint (`current`) is the idempotent re-push.

On success, stores the document and returns its fingerprint. Pushing byte-identical content is
naturally idempotent: the fingerprint returned is unchanged, so a caller can `get` first and skip
the push when fingerprints already match — no separate idempotency-key machinery is needed.

**Which sections are honored per-root.** Only a subset of top-level `configYaml` keys are resolved
per-root; everything else stays global-only and is reported back via `ignoredSections` (see below)
so a push is never silently partial:

| Top-level key | Honored per-root? | Layered by |
|---|---|---|
| `work_item_schemas` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `note_schemas` (legacy) | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `traits` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `project` | Yes | `ProjectConfigPushService` (embedded `project.rootId` guard only) |
| `note_limits` | Yes | `ToolExecutionContext.resolveNoteLimitsMode()` |
| `status_labels` | Yes | `ToolExecutionContext.resolveStatusLabel()` |
| `actor_authentication` | No — global-only | n/a |
| any other key | No | n/a |

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"push"` \| `"get"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem — must be depth 0 for `push` |
| `configYaml` | string | Yes (push only) | Raw config.yaml text to store for this root; max 128 KiB |
| `force` | boolean | No (push only, default `false`) | Bypass push guards — skips both the embedded `project.rootId` mismatch check and the superseded (fast-forward) staleness check |

**rootId mismatch guard.** A pushed `configYaml` document may embed its own root id at top-level
`project.rootId`. If present and it parses as a UUID that differs from the `rootId` argument,
the push is rejected before any write — this catches a config document synced or copy-pasted
against the wrong project root before it silently overwrites the target root's gates. An absent or
non-UUID `project.rootId` is not an error; the push proceeds as if it were absent. Pass
`force: true` to push anyway.

**Parse safety.** `configYaml` is parsed with SnakeYAML's `SafeConstructor` rather than its default
`Constructor`. The default constructor will instantiate an arbitrary Java type named by a YAML
`!!`-tag — a well-known deserialization-RCE class of vulnerability (CWE-502) — which matters here
specifically because `configYaml` is attacker-reachable input pushed over the MCP protocol, not a
trusted local file read from disk. `SafeConstructor` only ever builds plain maps, lists, and
scalars, which is everything a config document legitimately needs; any `!!`-tagged custom type is
rejected as a parse failure instead of being instantiated. The same fix applies everywhere per-root
config bytes are parsed (`PerRootConfigService`, on every schema-resolving read) and to the global
`.taskorchestrator/config.yaml` loader.

**Example.**

```json
{
  "operation": "push",
  "rootId": "3f9c2b10-...",
  "configYaml": "work_item_schemas:\n  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"
}
```

**Response (success).**

```json
{
  "rootId": "3f9c2b10-...",
  "fingerprint": "a94a8fe5cc...",
  "updatedAt": "2026-07-14T18:40:00Z",
  "warning": "Root item type is 'null', not 'project' — config pushed anyway (a naming convention, not an enforced constraint)",
  "ignoredSections": ["actor_authentication"]
}
```

`warning` is only present when the root's `type` is not `"project"`. `ignoredSections` is only
present (and non-empty) when the pushed document contains top-level keys outside the honored
allowlist above — e.g. `actor_authentication`.

**Error cases.**

| Condition | `error.code` |
|---|---|
| `configYaml` exceeds 128 KiB | `VALIDATION_ERROR` (message names the limit and the actual size) |
| `rootId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| Root WorkItem has `depth != 0` | `VALIDATION_ERROR` |
| `configYaml` fails to parse (invalid/unsafe YAML syntax or shape, including `!!`-tagged custom types — see **Parse safety** above) | `VALIDATION_ERROR` (message includes the parse detail) |
| `configYaml` embeds a `project.rootId` that differs from `rootId` (and `force` was not `true`) | `VALIDATION_ERROR` (message names both ids) |
| `configYaml`'s fingerprint is superseded — in the root's history but not current (and `force` was not `true`) | `VALIDATION_ERROR` (message: local config is older than the server's, with the server row's `updatedAt`) |
| Storage failure | `DATABASE_ERROR` |

**Fast-forward guard.** Every successful push appends the *previous* current fingerprint to a
per-root history (newest first, pruned to the last 20; stored in `project_config.fingerprint_history`,
added in migration V11). An incoming push whose fingerprint appears in that history but is not the
current fingerprint is provably *older content the server has already moved past* — the classic
stale-checkout case (old branch, unsynced worktree, second machine) — and is rejected so a session
starting from an outdated file cannot silently revert the project's live config. Content the server
has never seen (`unknown`) is accepted — true divergence remains last-writer-wins by design. Rows
created before V11 have no history and classify every non-current fingerprint as `unknown`
(pre-guard behavior) until history accumulates. The `config-sync` SessionStart hook performs the
same check client-side via `get` + `fingerprint` (below) and skips the push with a warning instead
of hitting the rejection.

#### `get`

Reads back the stored config for a root.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"push"` \| `"get"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem to read the config for |
| `fingerprint` | string (hex SHA-256) | No (get only) | A local document fingerprint to classify against this root's stored config; adds `relation` to the response |

**Response (success).**

```json
{
  "rootId": "3f9c2b10-...",
  "configYaml": "work_item_schemas:\n  ...\n",
  "fingerprint": "a94a8fe5cc...",
  "updatedAt": "2026-07-14T18:40:00Z",
  "relation": "superseded"
}
```

`relation` is only present when a `fingerprint` argument was supplied: `"current"` (matches the
stored fingerprint), `"superseded"` (in this root's fingerprint history but not current — the
supplied content is older than the server's), or `"unknown"` (never seen — new content, or a
pre-V11 row with no history). Callers deciding whether to push should treat `superseded` as
"do not push without `force`" — this is exactly what the `config-sync` hook does.

**Response (no config pushed for this root).** `error.code` is `RESOURCE_NOT_FOUND`.

**Note on actor attribution.** Unlike `manage_notes`/`manage_items`, this tool does not accept an
`actor` parameter — its writes aren't part of the per-note/per-item attribution model (a config
document has no "author" note field to stamp), so mirroring the actor-claim/verification machinery
would add complexity with no corresponding read surface to expose it through.

**Trust-model caveat.** `manage_project_config` can silently rewrite a project's gate configuration
(required notes, review phases, trait routing) for every item under a root. Under
`MCP_TRANSPORT=http`, the `/mcp` endpoint is **unauthenticated regardless of REST API auth mode** —
any client that can reach the port can call `push` for any root. This is not a gap introduced by
this tool; it's the existing MCP transport trust model (see
[`fleet-deployment.md`](./fleet-deployment.md), "MCP HTTP Transport (`/mcp`) Is Unauthenticated —
Read First") applying to config data the same way it already applies to every other MCP tool.
Fence `/mcp` at the network layer per that guidance before exposing `MCP_TRANSPORT=http` beyond a
single trusted process.

### manage_plan_documents

**Purpose.** Transport-agnostic store for per-root plan documents — free-floating planning docs an
agent stashes ahead of adoption into a real WorkItem. Mirrors `manage_project_config`'s push/get
shape: `PlanDocumentService` is the same validate-then-persist pipeline shared with the REST
`PUT/GET /api/v1/roots/{rootId}/plans/{slug}` and `GET /api/v1/roots/{rootId}/plans` routes — both
surfaces converge on identical DB state (including `contentHash`) for the same payload.

Supports three operations, selected via `operation`:

#### `stash`

Validates, in order:
1. The resolved body (`body` or `bodyFromFile`) must not exceed **64 KiB** (65,536 bytes, UTF-8) —
   rejected before any repository call.
2. `rootId` must resolve to an existing WorkItem.
3. That WorkItem must be depth 0 (a project root) — plan documents anchor to roots only.
4. If a document already exists at `rootId`+`slug` and its `status` is `"adopted"`, the stash
   is rejected — adoption is a one-way transition (see the materialization tooling that calls
   `PlanDocumentRepository.markAdopted`) and cannot be undone by stashing over it. A `"pending"`
   document at that slug is overwritten in place (`contentHash`, `body`, and `updatedAt` replaced;
   `id` and `createdAt` preserved).

`body` (inline text) and `bodyFromFile` (server-side path) are mutually exclusive — providing both,
or neither, fails validation. `bodyFromFile` resolves strictly relative to the agent config root
(`AGENT_CONFIG_DIR`, same containment rules as `manage_notes`' `bodyFromFile`: rejects absolute
paths, `..`, and symlink escapes; file must exist, ≤65536 bytes; CRLF is normalized to LF) —
so a document stashed via REST PUT and via `bodyFromFile` land byte-identical content when the
underlying text matches.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"stash"` \| `"get"` \| `"list"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem — must be depth 0 for `stash` |
| `slug` | string | Yes (stash, get) | Document identifier, unique within `rootId` |
| `body` | string | One of `body`/`bodyFromFile` (stash only) | Inline document text; max 64 KiB |
| `bodyFromFile` | string | One of `body`/`bodyFromFile` (stash only) | Server-side path, resolved relative to the agent config root |
| `status` | string (`"pending"` \| `"adopted"`) | No (list only) | Filter to a single status |

**Example.**

```json
{
  "operation": "stash",
  "rootId": "3f9c2b10-...",
  "slug": "auth-redesign",
  "body": "# Auth Redesign\n\n## Goals\n..."
}
```

**Response (success).**

```json
{
  "id": "8a1e...",
  "rootId": "3f9c2b10-...",
  "slug": "auth-redesign",
  "contentHash": "e3b0c44298fc1c14...",
  "status": "pending",
  "createdAt": "2026-07-16T18:00:00Z",
  "updatedAt": "2026-07-16T18:00:00Z"
}
```

`body` is never echoed back on `stash` — the caller already has it. `adoptedByItemId` is included
only once `status` is `"adopted"`.

**Error cases.**

| Condition | `error.code` |
|---|---|
| Resolved body exceeds 64 KiB | `VALIDATION_ERROR` (message names the limit and the actual size) |
| `rootId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| Root WorkItem has `depth != 0` | `VALIDATION_ERROR` |
| Both `body` and `bodyFromFile` supplied, or neither | `VALIDATION_ERROR` |
| `bodyFromFile` path rejected (absolute, `..`, symlink escape, missing, over cap) | `VALIDATION_ERROR` |
| Target slug is already `"adopted"` | `CONFLICT_ERROR` (message names the adopting item when known) |
| Storage failure | `DATABASE_ERROR` |

#### `get`

Reads back the full stored document (including `body`) for `rootId`+`slug`.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"stash"` \| `"get"` \| `"list"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem to read from |
| `slug` | string | Yes | Document identifier to read |

**Response (no document at this slug).** `error.code` is `RESOURCE_NOT_FOUND`.

#### `list`

Reads back metadata-only summaries (never the body) for every document under `rootId`,
optionally filtered by `status`, ordered by `slug` ascending.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"stash"` \| `"get"` \| `"list"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem to list documents for |
| `status` | string (`"pending"` \| `"adopted"`) | No | Filter to a single status |

**Response (success).**

```json
{
  "rootId": "3f9c2b10-...",
  "plans": [
    { "id": "8a1e...", "rootId": "3f9c2b10-...", "slug": "auth-redesign", "contentHash": "e3b0c4...", "status": "pending", "createdAt": "...", "updatedAt": "..." }
  ]
}
```

**Note on actor attribution.** Like `manage_project_config`, this tool does not accept an `actor`
parameter — plan documents have no per-note attribution model to stamp.

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

All tool failures use a structured `ToolError` shape that classifies retry semantics. The MCP layer
surfaces this same object through the tool call's `structuredContent.error`, so clients can branch
on `code`/`kind` without parsing the text summary:

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
| `details` | any (nullable) | Additional structured detail specific to the failure (e.g., gate `missingNotes`, dependency `blockers`). Omitted when there is nothing beyond `message`. |

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

Every persisted actor claim includes a verification record.

**Output omission.** MCP tool responses (`manage_notes` upsert, `advance_item` transitions,
`query_notes` get/list, `get_context` recent transitions) omit the `verification` object
whenever `verifier == "noop"` — the default when no `actor_authentication.verifier` is
configured. A no-op result carries no information beyond "no verifier is configured," which is
a deployment-wide fact repeated on every attributed note and transition, so it is dropped from
output rather than serialized. The record is still persisted in the database; only the MCP
response representation omits it. Any other verifier result — including a future verifier that
legitimately returns `unchecked` with a `reason` — serializes in full.

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

Actor claims are **optional by default** — users who don't need actor authentication pay no extra token cost. To require actor claims on all write operations, enable actor authentication in `.taskorchestrator/config.yaml`:

```yaml
actor_authentication:
  enabled: true
```

When enabled, the plugin's `PreToolUse` hook blocks any `advance_item` or `manage_notes(upsert)` call where one or more elements are missing an `actor` object. The call never reaches the server — the agent must retry with actor claims included.

When `enabled` is `false` or the `actor_authentication` section is absent, calls pass through with no enforcement. Actor claims can still be provided voluntarily.

> **Note:** Config changes require an MCP reconnect (`/mcp`) or session restart to take effect.

#### Subagent Behavior

Actor claims are self-reported by convention — the server does not inject them automatically. This means:

- **Uninstructed subagents** make calls with no actor data, producing anonymous transitions and notes
- **Instructed subagents** include actor claims only when the delegation prompt tells them to
- The enforcement hook applies to all callers in the session, including subagents, providing a safety net regardless of prompt quality

For reliable attribution, combine actor authentication enforcement with explicit delegation instructions:

```
Include an "actor" object on every advance_item and manage_notes call:
{ "id": "your-agent-name", "kind": "subagent", "parent": "orchestrator-id" }
```

---

## Actor Authentication & Verification

### Verifier Configuration

The `actor_authentication` section in `.taskorchestrator/config.yaml` controls actor attribution enforcement and verification. The `enabled` flag and the `verifier` block are independent — enforcement checks actor presence, while the verifier checks proof validity.

```yaml
actor_authentication:
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

### `DEGRADED_MODE_POLICY` Environment Variable

The `DEGRADED_MODE_POLICY` environment variable overrides the `actor_authentication.degraded_mode_policy` YAML value. It is evaluated at server startup before any requests are processed.

| Aspect | Detail |
|---|---|
| Valid values | `accept-cached`, `accept-self-reported`, `reject` (case-insensitive) |
| Priority | Env var **>** YAML field **>** coded default (`accept-cached`) |
| Invalid value | Throws `IllegalArgumentException` at startup — server will not start |
| Unset | Falls through to the YAML value (or the coded default) |

Use `DEGRADED_MODE_POLICY=reject` for cross-org or multi-tenant fleet deployments. See [Fleet Deployment Guide — DEGRADED_MODE_POLICY](fleet-deployment.md#degraded_mode_policy-environment-variable) for Docker examples and security rationale.

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
  -v "$(pwd)"/deploy/global-config/.taskorchestrator:/project/.taskorchestrator:ro \
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
