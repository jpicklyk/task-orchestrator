# MCP Task Orchestrator v3 — API Reference

## Overview

The v3 server exposes a set of MCP tools organized around a single **WorkItem** graph model. Every
entity — whether a project, feature, or task — is a WorkItem with a `role` (queue, work, review,
blocked, terminal), optional `parentId`, a `type` field that selects a work-item schema (lifecycle
mode + required notes), optional `tags` for categorization, and optional `traits` that compose
additional note requirements. Notes are first-class keyed documents attached to items. Dependencies
link items with typed blocking or relational edges.

**See also:** [`search-and-discovery.md`](./search-and-discovery.md) for the architecture behind
FTS5 search (two-tokenizer design, RRF fusion, scope filtering, backlinks, score interpretation).

**Server capabilities:** the `initialize` result advertises `tools` (`listChanged`) and `logging`
only — there are no `prompts` or `resources` capabilities; the server registers neither. The
`logging` capability backs `notifications/message` events the server sends on tool validation
errors, per-root-config-unavailable failures, and internal errors.

## Tool Categories

| Tool | Category | R/W | Description |
|---|---|---|---|
| `items_write` | Hierarchy & CRUD | Write | Create, update, or delete WorkItems (`ops[]`, per-element results) |
| `items_read` | Hierarchy & CRUD | Read | Get one item, list a filtered page, read a note schema, search items and notes, or summarize the hierarchy (4.0 envelope) |
| `create_work_tree` | Hierarchy & CRUD | Write | Atomically create root + children + deps + notes |
| `complete_tree` | Hierarchy & CRUD | Write | Batch-complete descendants in topological order |
| `notes_write` | Notes | Write | Upsert or delete notes on work items, in batches, with version guards (4.0 envelope) |
| `notes_read` | Notes | Read | Get one note or list an item's notes (4.0 envelope) |
| `deps_write` | Dependencies | Write | Create or delete dependency edges, single or by pattern (linear, fan_out, fan_in) (4.0 envelope) |
| `deps_read` | Dependencies | Read | List an item's edges by direction, or walk its blocking graph in topological order (4.0 envelope) |
| `docs_write` | Documents | Write | Upsert, archive, unarchive, delete a root's versioned documents; attach or detach items (4.0 envelope) |
| `docs_read` | Documents | Read | Get a document or one version, list, history and links (4.0 envelope) |
| `advance` | Workflow | Write | Phase transitions (cascade and gate enforcement) and claim ownership: ops `transition`, `claim`, `release`, `heartbeat` |
| `get_next_status` | Workflow | Read | Read-only transition recommendation for a single item |
| `get_context` | Workflow | Read | Context snapshot: item mode, session resume, or health check |
| `get_next_item` | Workflow | Read | Priority-ranked recommendation of next actionable item |
| `get_blocked_items` | Workflow | Read | Items blocked by dependency or explicit block trigger, capped at 500 per role |
| `manage_project_config` | System | Write/Read | Push or read back per-root config YAML for the layered schema resolver |
| `query_rules` | System | Read | Read git-tracked rule text by rootId+key, by an item skill-pointer resolution, or list rule keys under a root |

---

## Category: Hierarchy & CRUD

### items_write

**Purpose.** Create, update and delete WorkItems. One `ops[]` array of flat objects, each naming its op in `op`
(`create`, `update`, `delete`). It is the 4.0 replacement of `manage_items`, and the same call is served over MCP and
as `POST /api/v1/ops/items_write` (the REST routes `POST /items`, `PATCH /items/{id}` and `DELETE /items/{id}` are
gone). The request and response follow the batch envelope: `{ "ops": [...], "atomic"?: bool, "actor"?: {...} }`
returns `data.results[i]` (`{ok:true, ...}` or `{ok:false, error}`), a `data.summary`, and `meta.reqId`. By default
every element is its own write unit (one failing element does not stop the others, and the call answers
`partial_failure`); `atomic: true` runs the whole array in one unit and the first failure rolls everything back.
An element may carry an `idempotencyKey` (requires an `actor`): a repeated key replays the stored result with
`replayed: true`; the same key with a different element is `idempotency_mismatch`.

Placement (depth, root) is computed from the parent row inside the write unit; an item is always created in `queue`
and there is no role field (use `advance`). A `terminal` parent under `auto` lifecycle accepts no new children
and no reparent onto it (`invalid_transition`).

| Op | Element fields | Result (`results[i]`) |
|---|---|---|
| `create` | `title` and `type` (required); `parentId`, `description`, `summary`, `statusLabel`, `priority` (`high`, `medium` default, `low`), `complexity` (1-10), `requiresVerification`, `metadata`, `tags` (array), `traits` (array), `properties` (JSON object) | `{ok, id, version}` |
| `update` | `id` (required), `expectedVersion`; any create field except that all are optional; `clear` | `{ok, id, version, cascade?}` |
| `delete` | `id` (required), `recursive`, `expectedVersion` | `{ok, id, deleted, cascade?}` (`deleted` counts the item and its descendants) |

**Update semantics.** An absent or `null` field keeps its stored value. `tags: []` and `traits: []` clear those
lists. `clear` lists optional fields to set to null: `description`, `statusLabel`, `metadata`, `complexity`,
`properties`, `parentId` (the last moves the item to root). A field cannot be both sent and cleared in one element.
`expectedVersion` is the optimistic lock: when given, a stale value fails `version_conflict` (`kind`, `id`,
`expected`, `actual`) and nothing is written; when absent the update locks against the version it just read.
`type` cannot be cleared; a changed type is checked against the destination root's declared types.

**Delete semantics.** A parent without `recursive: true` fails `invalid_request` with a violation on `recursive`
whose `received` is the child count; nothing is deleted. `recursive: true` deletes the whole subtree, deepest first,
in one unit. Notes and dependency edges go with their items. `expectedVersion` guards the item itself.

**Cascade.** A reparent away from, or a delete under, a parent re-evaluates that old parent like a child
completion. `cascade` (omitted when empty) lists `{itemId, from, to, applied, reason?}`; `reason` is the catalog code
that blocked an unapplied cascade (`gate_blocked`, `resource_unavailable`, `dependency_unmet`, `invalid_transition`).

**Errors.** `invalid_request` / `unknown_parameter` (decoding; pointers like `/ops/0/type`), `not_found`,
`ambiguous_id` (an 8-hex prefix matching several items), `version_conflict`, `cycle_detected` (reparent under a
descendant), `schema_violation` (undeclared or blank `type`, undeclared trait; `detail.availableTypes` /
`availableTraits`), `invalid_transition` (closed parent), `forbidden` (scope), `unavailable` (a per-root config fault:
transient, `retryAfterMs`). A `properties` object carrying a `traits` key is `invalid_request` on `properties`
(`traits` is its own field).

**Scope.** Every item an element touches is checked against the caller's scope before any write: the target of
`update` and `delete`, a `parentId` (create) or the new parent (a reparent). A root create is `forbidden` under a root
scope (the new id cannot be a listed root) and, under a tag scope, needs a listed tag in `tags`; `clear: ["parentId"]`
under a root scope is allowed only when the item itself is a listed root. Under a restricted scope an id that
resolves to nothing, or is ambiguous, is `forbidden` (scope is no existence oracle).

**Actor and events.** The caller's actor (body `actor` over MCP, the bearer principal over REST) is recorded as the
principal of the `item.created`, `item.updated`, `item.reparented` and `item.deleted` events the call writes.

```json
{
  "ops": [
    { "op": "create", "title": "Add retry", "type": "feature-task", "parentId": "9f3a1c22", "tags": ["backend"] },
    { "op": "update", "id": "9f3a1c22", "expectedVersion": 3, "priority": "high", "clear": ["description"] },
    { "op": "delete", "id": "0b6d4e11", "recursive": true }
  ]
}
```

---

### items_read

**Purpose.** Read work items: `get` one item, `list` a filtered page, read the note `schema` of a type or
an item, full-text `search` over item content and note bodies, or an `overview` of the hierarchy. A 4.0
envelope tool: a request is `select` plus that select's fields, and every answer is the response envelope
(`ok`, `data` or `error`, `meta.reqId`). The same tool is served over MCP and over REST
`POST /api/v1/ops/items_read`; `GET /items/{id}` and `GET /items/{id}/children` are thin translations to
it (see `api-rest.md` section 9). `items_read` replaces `query_items` (`get`, `search`, `overview`,
`schema`) and the note search of `query_notes`. Each field is documented once in the tool's own schema
(`describe: true`).

**Selects.** `get`, `list`, `schema`, `search`, `overview`. Every select needs the `READ` capability and
honors the caller's root and tag scope.

| Select | Fields | Answer (`data`) |
|---|---|---|
| `get` | `itemId`, `fields?`, `ifVersion?` | the full item view; with `ifVersion` equal to the stored version, `{unchanged: true, version}` |
| `list` | `parentId?`, `ancestorId?`, `depth?`, `role?`, `priority?`, `tags?`, `traits?`, `type?`, `claimStatus?`, `createdAfter?`, `createdBefore?`, `modifiedAfter?`, `modifiedBefore?`, `roleChangedAfter?`, `roleChangedBefore?`, `sort?`, `order?`, `limit?`, `cursor?`, `fields?` | `{items: [compact view], page}` |
| `schema` | exactly one of `type` / `itemId`; `rootId?` (with `type` only); `fields?` | the resolved schema view, or `{schemaFree: true}` |
| `search` | `query`, `kinds?`, `matchMode?`, `itemId?`, `ancestorId?`, `role?`, `tags?`, `traits?`, `snippet?`, `explain?`, `limit?`, `cursor?` | `{items: [hit], page}` |
| `overview` | `itemId?` or `anchorId?` (not both), `excludeTerminal?`, `includeChildren?` (global only), `limit?`, `cursor?` | global `{items, page}`; scoped `{item, childCounts, items, page}`; anchored `{anchor: {id, title}, items, page}` |

**Ids.** Every id field takes a full UUID or 8 to 32 hex characters of one. A prefix that matches more than
one item is `ambiguous_id`; no match is `not_found`. Under a restricted scope an id that matches nothing
the caller may see is `forbidden`, never `not_found`, so scope is no existence oracle, and an ambiguous
prefix lists only the candidates the scope admits. A target id (`get`, `schema`, the overview `itemId` and
`anchorId`, `rootId`) outside the scope is `forbidden`. A filter id (`list` `parentId` / `ancestorId`,
`search` `itemId` / `ancestorId`) is not scope-checked: an out-of-scope subtree simply selects nothing.

**Item views.** One renderer serves every select. Lean defaults: a null, an empty string, an empty array and
`requiresVerification: false` are omitted; `version` is always present; `tags` is an array (the stored
comma-separated list split and trimmed); claim identity is never rendered.

- Full view (`get`, the scoped overview's `item`): `id`, `parentId`, `rootId` as full UUIDs, then `type`,
  `title`, `summary`, `description`, `role`, `statusLabel`, `previousRole`, `priority`, `complexity`,
  `depth`, `tags`, `traits`, `metadata`, `properties`, `requiresVerification`, `version`, `diagnostics`.
- Compact view (`list` entries, overview entries, `ancestors`): `id` and `parentId` as 8-hex short ids,
  `type`, `title`, `role`, `statusLabel`, `priority`, `depth`, `tags`, `traits`, `version`.

**`fields`.** Opt-in members; a name a select does not accept is `invalid_request` on `/fields`.
`get`: `createdAt`, `modifiedAt`, `roleChangedAt`, `ancestors`. `list`: the same plus `summary`.
`schema`: `guidance` (per-note `guidance` is stripped unless asked for). `ancestors` is the compact chain,
root-first: a root-scoped caller sees nothing above the first scope root in the chain, and a tag-scoped
caller sees only ancestors carrying an allowed tag.

**Filters.** `tags` matches items with at least one of the listed tags (case-insensitive); `traits` matches
items with at least one of the listed traits (exact). Date bounds are inclusive ISO 8601 instants; an
inverted pair is `invalid_request`. `list` sorts by `sort` (`title`, `priority`, `complexity`, `createdAt`,
`modifiedAt`; default `createdAt`) in `order` (`asc`, `desc`; default `desc`); the 3.x aliases are gone.

**Paging.** `list`, `search` and `overview` page with `limit` and `cursor`. `list` and `overview`: `limit`
1 to 200, default 50. `search`: 1 to 100, default 20. A `limit` outside its range is `invalid_request`; a
`cursor` that does not decode is `invalid_cursor`. `page` is `{total, truncated, capped?, next?}` and
follows the envelope's list rules (amendment U22): `total` is the full count on every page, `truncated` is
true exactly when entries exist after this page, and `next` (an opaque cursor) is present exactly when
`truncated`, so following `next` until it is absent always terminates. A `list` page and its `total` are
read in one read unit, with the scope applied before paging.

**`get`.** The scope check runs before any `not_found` or `ifVersion` answer. `ifVersion` lets a client
holding a version skip the body: when it equals the stored version the answer is
`{unchanged: true, version}`.

**`schema`.** The `type` path resolves the base schema of that type, layered over `rootId`'s per-root config
when given (the `schema_resolution` mode decides the order; global-only when the root has no pushed
config). The `itemId` path resolves the item's fully trait-merged schema over its own `rootId`. The view is
`{type, configFingerprint, configSource, notes: [{key, role, required, description, guidance?, skill?,
maxLength?, seat?, independentOf?}], dispatch?, resources?, seats?, dispatchBySeat?, features}`. Seats,
dispatch and the type-path vs item-path difference are described in
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seats-trait--schema-dimension).
No matching schema is not an error: the answer is `{schemaFree: true}`. Giving both or neither of `type`
and `itemId`, or `rootId` without `type`, is `invalid_request`.

**Server feature advertisement.** `features` lists the optional-response capabilities this server can serve
(`seats`, `dispatchBySeat`, `independent_of`, `rules`), unconditionally, whatever the resolved config
declares, so a client can tell an old server from a config that declares no seats. `features` also appears
on REST `GET /api/v1/info` and the `.well-known` service descriptor (see `api-rest.md`). `rules` advertises
per-root rule text served by [`query_rules`](#query_rules) and `GET /api/v1/roots/{rootId}/rules[/{key}]`.

**`search`.** Ranked full-text hits through the shared search service. `kinds` picks the content: `item`
(title, summary, description), `note` (bodies) and/or `document` (title and body of plan, rule and page
documents); an empty `kinds` is `invalid_request`. With `kinds` absent the search covers every kind the other
filters apply to, fused in one ranking: all three when none of `role`, `tags` and `traits` is set, `item` and
`note` when any is (they describe items, so they cannot filter documents). Naming `document` in `kinds`
together with `role`, `tags` or `traits` is `invalid_request` on `/role`, `/tags` or `/traits` (the first one
set, in that order); a filter is never silently dropped. `query` terms must all match and are matched literally (special characters are
escaped); a query the search core rejects (for example a control character other than ASCII whitespace) is
`invalid_request` on `/query`. `matchMode` is `auto` (default: the trigram and the stemmed tables fused by
RRF), `substring` (trigram only, terms of 3 or more characters) or `text` (stemmed). `itemId` keeps only
that item's own content; `ancestorId` its subtree; `role`, `tags` and `traits` filter on the item that owns
the content. A document is owned by its root item, so `itemId` or `ancestorId` set to that root finds the
root's documents and any other id finds none. Archived documents are never searched; an edited document is
searchable at once. The caller's scope filters candidates before ranking and capping, so a restricted token
gets a full page of in-scope hits; a document is visible exactly when its root item is (a token scoped to an
id below the root, or by a tag the root does not carry, does not see the root's documents). A hit is
`{kind, itemId, noteKey?, title?, field, snippet?, score, matchedIn, explain?}` for an item or note:
`kind` is `item` or `note`, `itemId` is the 8-hex short id, `noteKey` is set on note hits, `title` is the
owning item's title, `field` is the field holding the match (`title` or `summary`, `title` when both do,
`body` for a note). A document hit is `{kind: "document", rootId, docKind, slug, title?, field, snippet?,
score, matchedIn, explain?}`: `rootId` is the root's 8-hex short id, `docKind` is `plan`, `rule` or `page`,
`title` is the document's own title (omitted when it has none), `field` is `title` or `body` (`title` when
both match), and it has no `itemId` or `noteKey`; `rootId`, `docKind` and `slug` address the document for
`docs_read`. `snippet` (default on) carries `<mark>` highlights, `score` is the fused RRF value (higher is
more relevant), and `explain` (default off) adds `{trigramRank?, textRank?, rrfK}`. `page.total` is the size of the capped hit list (at most 100); `page.capped` is true when more matches
existed than the cap: refine the query or narrow the filters. The call log's `request_shape` for a search
carries `hitsByKind`, the count of hits per searched kind in the capped list (zeros kept, the same on every
page), and each document hit on the returned page records its id and version as a target version.

**`overview`.** Three modes:

- Global (no id): the parentless items the caller may see, newest first, each a compact view with direct
  `childCounts` and `claimSummary` (`{active, expired, unclaimed}`, counts only, over its direct children);
  `includeChildren` adds each entry's direct `children`, with the same enrichment.
- Scoped (`itemId`): the item's full view in `item`, its direct `childCounts`, and a page of its children.
- Anchored (`anchorId`): `anchor: {id, title}` and a page of the anchor's children, each with
  full-subtree role counts (descendants at any depth).

`childCounts` is `{role: n}` with zero roles omitted. `excludeTerminal` drops terminal items that have no
open work below them; a terminal item with a non-terminal descendant is kept. Under a restricted scope
every list and count covers only items the caller may see. `includeChildren` with `itemId` or `anchorId`,
or `itemId` with `anchorId`, is `invalid_request`.

```json
{ "select": "get", "itemId": "550e8400", "fields": ["ancestors"], "ifVersion": 7 }
{ "select": "list", "ancestorId": "550e8400", "role": "work", "tags": ["backend"], "limit": 20 }
{ "select": "schema", "type": "feature-task", "rootId": "a1b2c3d4", "fields": ["guidance"] }
{ "select": "search", "query": "OAuth flow", "kinds": ["note"], "ancestorId": "550e8400" }
{ "select": "overview", "anchorId": "550e8400", "excludeTerminal": true }
```

`query_items` and `query_notes` are removed. Their `offset` paging, `includeAncestors` and
`includeTimestamps` flags, comma-separated `tags` filter, `sortBy` / `sortOrder` names and the
`RESOURCE_NOT_FOUND` for a schema-free item have no direct form: use `cursor`, `fields`, a `tags` array,
`sort` / `order` and `schemaFree`.

---

### create_work_tree

**4.0 write unit.** The whole tree is ONE unit over the write services: the items root-first through the item
command service (every placement read from the just-inserted parent's row; a `terminal` parent under `auto` lifecycle,
given as `parentId` or as the attach-mode `root.id`, rejects the whole call with `VALIDATION_ERROR`), then the
dependencies, the notes and, last, the `docRef` adoption. Any failure leaves zero items, notes and dependencies, and
the document stays PENDING.

**Purpose.** Atomically create a root WorkItem, optional child items, optional dependency edges
between them, and optional blank notes — all in a single call. Eliminates the round-trips required
when calling `items_write`, `deps_write`, and `notes_write` separately.

**Attach mode.** Pass `root.id` (UUID or hex prefix of an existing item) instead of `root.title` to
attach children, deps, and notes directly to an existing item. When `root.id` is provided, `root.title`
is optional (ignored if both are given). The existing item is NOT re-inserted; children are created
under it at `existing.depth + 1`. Providing both `root.id` and `parentId` is rejected with `VALIDATION_ERROR`.

**When to call.** Call when materializing a planned hierarchy — one atomic call instead of per-item create sequences.

**Operations.** Single operation (no `operation` parameter).

#### Key Parameters

| Parameter | Type | Required | Description |
|---|---|---|---|
| `root` | object | Yes | Create mode: `{ title (required), type (required), priority?, tags?, traits?, summary?, description?, requiresVerification?, noteAnchors? }`. Attach mode: `{ id? (UUID or hex prefix of existing item), title? (optional when id provided), priority?, tags?, traits?, summary?, description?, requiresVerification?, noteAnchors? }`. When `id` is present, `title` is optional and ignored. `noteAnchors` is documented below alongside `docRef`. |
| `parentId` | string (UUID) | No | Existing parent; root depth = parent.depth + 1. Cannot be combined with `root.id`. |
| `children` | array | No | Child item specs: `[{ ref, title, type (required), parentRef?, priority?, tags?, traits?, summary?, description?, requiresVerification?, noteAnchors? }]`. A spec without a `type`, or with an undeclared one (any schema configured), fails the whole call with `VALIDATION_ERROR` naming the offending ref (`root` or the child ref) and listing the available types; nothing is written. Attach mode does not re-validate the existing root's type. `ref` is a local name used to wire `deps`, `notes`, and other children's `parentRef`. `parentRef` (another child's `ref` or `"root"`, default `"root"`) sets the child's parent — nesting is expressed via `parentRef` only; a nested `children` key inside a child spec is rejected. |
| `deps` | array | No | Dependency specs: `[{ from: ref, to: ref, type?: BLOCKS\|IS_BLOCKED_BY\|RELATES_TO, unblockAt?: queue\|work\|review\|terminal }]`. Use `"root"` to reference the root item. `IS_BLOCKED_BY` is an input alias: it is stored as `BLOCKS` with the refs swapped, and the response `dependencies` carry that stored form. A restated pair (for example `x BLOCKS y` plus `y IS_BLOCKED_BY x`) is rejected as a duplicate and a cycle as `Circular dependency detected involving ref ...`; nothing is written. |
| `createNotes` | boolean | No | Auto-create blank notes for each item from its resolved schema (looked up by `type` first, then by `tags`). Default: false. |
| `notes` | array | No | Notes to create with bodies: `[{ itemRef (required, "root" or child ref), key (required), role (required: queue\|work\|review), body? (defaults to empty string) }]`. Explicit notes win over `noteAnchors` AND `createNotes=true` blanks per `(itemRef, key)`. **Strict role enforcement:** when an explicit note's `key` is declared in the resolved schema for the target item, the note's `role` must equal the schema role; mismatch returns `VALIDATION_ERROR`. Off-schema keys and items without a schema are unconstrained. |
| `docRef` | object | No | Materialize-from-document source: `{ rootId? (defaults to the created/attached root's own rootId — `rootItem.rootId ?: rootItem.id`; validated for consistency if given, UUID or hex prefix), slug (required; a slug starting with `rule/` is rejected — rule documents are served by [`query_rules`](#query_rules) and adopting one would freeze it) }`. References a plan document written with [`docs_write`](#docs_write) `upsert`. Required whenever any item spec's `noteAnchors` is used; valid (adopts with zero sourced notes) even with none. |
| `actor` | object | No | Actor claim `{ id, kind: orchestrator\|subagent\|user\|external, parent?, proof? }`. Used for idempotency keying AND propagated as the actor attribution on every persisted note (explicit, `noteAnchors`-sourced, and `createNotes=true` blanks alike). |
| `requestId` | string (UUID) | No | Client-generated UUID for idempotency. See [Idempotency](#idempotency). Requires `actor` to function. |

**Note write policy.** Explicit `notes` and `noteAnchors`-sourced notes follow the same policy as [`notes_write`](#notes_write) (see its **Note write policy**). `role` may be given in any letter case (stored lowercase). Unlike `notes_write`, which fails one element at a time, a policy rejection here fails the WHOLE `create_work_tree` call: an over-cap body (65536 UTF-8 bytes), a schema-role mismatch, or - under `note_limits.mode: reject` - a body over the schema `maxLength` returns `VALIDATION_ERROR` with zero items created and the plan document not adopted. Under `note_limits.mode: warn` the call succeeds and the affected note's entry in the `notes` response array carries a `warning` field naming the limit and actual length; for a duplicate `(itemRef, key)` the warning follows the last-wins note. `createNotes=true` blanks are built from the schema entry with an empty body and do not go through the policy.

`root.priority` and each `children[i].priority` must be one of `high`, `medium`, `low` (case-insensitive); a blank or absent value defaults to `medium`, the same rule `items_write` applies. An unrecognized non-blank value (e.g. `"urgent"`) is rejected with a validation error, not silently coerced to `medium`.

Nesting depth is unbounded. The root item can be at any depth; each child's depth is its resolved parent's depth + 1 — root.depth + 1 for direct children (default `parentRef: "root"`), deeper when nested under another child via `parentRef`. In attach mode, children derive depth from the existing root's depth. `parentRef` cycles are rejected at validation; cycle protection is also enforced at the database level. Descendant traversal (e.g. cascade delete, re-parent depth recompute) is additionally bounded to 1000 levels and fails with a data error on a cycle or a subtree at/beyond the bound, instead of hanging or silently truncating; subtree search instead bounds-and-continues, excluding anything past the cap.

#### Materialize-from-document (`docRef` + `noteAnchors`)

`docRef` plus one or more item specs' `noteAnchors: [{ noteKey (required), role (required: queue|work|review), anchor (required) }]` source note bodies from a stashed [plan document](#docs_write) instead of inlining them. `noteAnchors` may appear on `root` and/or any `children` entry.

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
items, in topological dependency order. Each item is advanced through the SAME `AdvanceService`
pipeline as `advance_item` — ownership check → resolve → validate dependencies → required-note
gate → apply → cascade detection → unblock detection — run per item, one item at a time in
topological order, so claim ownership and dependency validation apply here exactly as they do on a
single `advance_item` call. (The resource-lease gate never fires here: `complete`/`cancel` always
target TERMINAL, and leases are acquired only on work entry; any leases the item holds are released
on the way out.) If required notes are missing
(and the trigger doesn't bypass the gate — see below), that item fails and its downstream
dependents within the set are skipped.

**Ordering is blocker-first, direction-aware.** For an in-set `BLOCKS` edge (`A BLOCKS B`), `A` is
ordered before `B`. Stored dependencies are always `BLOCKS` from the blocker to the blocked item
(`IS_BLOCKED_BY` is an input alias, normalized on write), so this is the single blocker-first rule.
Among items with no ordering constraint between them, the order of the target set is kept. `RELATES_TO` edges carry no blocking semantics: they impose no ordering constraint and never
cause a dependent to be skipped. A blocker item **outside** the target set is not part of this
in-set graph at all — it produces no ordering here; the in-set item that depends on it instead fails
`AdvanceService`'s dependency-validation check (see "Dependency-validation-failure fields" below),
and that failure skips its own in-set dependents same as a gate failure would.

**When to call.** Call when closing out a finished hierarchy — one atomic call instead of per-item advance sequences.

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

**Gate enforcement and `trigger`:** When `trigger="complete"`, the required-note gate applies —
items whose schema resolves (via `type`, tag match, or default fallback) must have all required
notes filled before completing; items that fail gating are recorded as `gateErrors` and their
dependents within the set are skipped. When `trigger="cancel"`, the **note gate** is bypassed — all
items in the set are cancelled regardless of note state — but claim ownership is **not** bypassed
by `cancel`; an item held by another agent's claim is still rejected the same way it would be under
`complete`.

**Example.**

```json
{ "rootId": "550e8400-e29b-41d4-a716-446655440000", "trigger": "complete" }
```

**Response.**

```json
{
  "results": [
    {
      "itemId": "uuid", "title": "Design login flow", "applied": true, "trigger": "complete",
      "statusLabel": "done", "previousRole": "review", "newRole": "terminal",
      "cascadeEvents": [], "unblockedItems": []
    },
    { "itemId": "uuid", "title": "Implement handler", "applied": false, "gateErrors": ["missing: done-criteria"] },
    { "itemId": "uuid", "title": "Write tests", "applied": false, "skipped": true, "skippedReason": "dependency gate failed" },
    {
      "itemId": "uuid", "title": "Deploy", "applied": false, "skipped": true,
      "skippedReason": "Item is claimed by another agent", "errorCode": "not_claim_holder", "errorKind": "permanent"
    },
    {
      "itemId": "uuid", "title": "Ship release notes", "applied": false,
      "error": "Blocked by dependency", "blockers": [{ "fromItemId": "uuid", "currentRole": "queue", "requiredRole": "terminal" }]
    }
  ],
  "summary": { "total": 5, "completed": 1, "skipped": 2, "gateFailures": 2 }
}
```

`statusLabel` is included on successfully transitioned items when the item ends up with a status label (config-driven via `status_labels`; defaults: `"done"` for `complete`, `"cancelled"` for `cancel`); omitted otherwise.

**Applied-entry fields (`applied: true`):** in addition to `itemId`, `title`, `trigger`, and
`statusLabel`, a successfully-applied entry now carries `previousRole`, `newRole`, `cascadeEvents`,
and `unblockedItems` — the same cascade/unblock-detection fields `advance_item` returns for a single
transition, since every item advances through the identical `AdvanceService` pipeline. `cascadeEvents`
is empty when nothing cascaded from this item's completion. An item whose completion was already
implied by an earlier cascade in this same batch (e.g. completing a child cascaded the root to
`TERMINAL` before the root's own entry in `itemIds`/`rootId` was processed) is reported as already
terminal rather than re-applied — it is not double-counted and produces no duplicate audit row.

**Independence (A2).** An applied entry (and each of its `cascadeEvents` entries) gains `violations`
(array, same `{key, seat?, constraint, conflictingSeat?, waived?}` shape as `advance_item`'s
`violations`) — present only when the list is non-empty (omitted, not `[]`, when independence
checking applies but finds nothing, and also omitted when independence mode is `off` or the item's
target schema declares no `independent_of`); populated in `warn` mode too, including on a plain
(non-cascaded) `complete_tree` applied entry. A `GateBlocked` entry (`applied: false`, `gateErrors`
present) also gains `violations` under the same non-empty-only rule alongside
`gateErrors`/`missingNotes` — the item may be gate-failed by missing notes, a non-waived
`reject`-mode violation, or both; either way this item's in-set dependents are skipped the same as
any other gate failure. See
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2)
→ "Independence (A2)".

**Rejected-entry fields (`applied: false`, ownership/policy rejection):** a rejection from claim
ownership, policy, or the underlying `AdvanceService` apply step carries the SAME error shape
`advance_item` uses — `skipped: true`, `skippedReason` set to the human-readable message, `errorCode`
set to the `advance_item` error code (e.g. `"not_claim_holder"`, `"rejected_by_policy"`), plus
whichever of `errorKind`, `errorCode`, `retryAfterMs`, `contendedItemId`, and `contendedResources`
apply to that failure — see [Error Envelope](#error-envelope) for the full field set. These
rejections skip in-set dependents exactly like a gate failure does.

**Per-root config unavailable (`applied: false`, `errorCode: "config_unavailable"`):** when an
item's per-root config cannot be read (a transient database error) inside its advance's unit of work,
where the last-known-good fallback is disabled (see `manage_project_config`'s Purpose note below), that item's entry
carries `skipped: true`, `errorKind: "transient"`, `errorCode: "config_unavailable"` — no
`retryAfterMs` — and counts as a rejection (`skipped`, not `gateFailures`); its in-set dependents are
skipped exactly like any other rejection. The rest of the batch (siblings, and items whose own root's
config IS readable) is unaffected.

**Dependency-validation-failure fields (`applied: false`, no `skipped` key):** an item rejected
because a blocker outside the target set is still non-terminal (`AdvanceService`'s
`ValidationFailed` outcome) reports `error` (a message string) and a `blockers` array — the same
element shape `advance_item` uses for its `422 transition_blocked`/`details.blockers` — instead of
`skipped`/`skippedReason`. It counts in `gateFailures`, not `skipped` (grouped with required-note
gate failures so the two `summary` counters stay consistent — both represent "this item's own
check failed", as opposed to `skipped`, which represents "an upstream failure in this batch reached
this item"). Like a required-note gate failure, a dependency-validation failure skips its in-set
dependents.

**`skippedReason` values (entries that DO carry a `skipped` key):** Items can be skipped for:
- `"dependency gate failed"` — a blocker item in the same target set failed its gate check or failed to apply, and this item is a downstream dependent.
- `"Cannot transition"` (or a specific error message) — a **resolution failure**: the item itself could not be resolved for transition (e.g., it is already terminal or the role is incompatible with the trigger). Unlike every other non-gate rejection, a resolution failure does NOT skip this item's in-set dependents.
- The human-readable message of an `advance_item` rejection (its code is in `errorCode` — see "Rejected-entry fields" above) — claim ownership or policy rejections; these DO skip in-set dependents.

**`summary` fields:** `total` = completed + skipped + gateFailures. `completed` = items successfully transitioned. `skipped` = items skipped due to upstream gate/apply failures, ownership/policy rejections, or items already terminal. `gateFailures` = items that failed the required-note gate OR a dependency-validation check (a still-non-terminal blocker outside the target set); their downstream dependents are counted in `skipped`.

**Audit attribution:** the top-level `actor` (see Key Parameters) is parsed once per call and
threaded into every item's `AdvanceService.advance(...)` call, so each resulting role-transition
audit row carries real actor attribution instead of a null actor. An actor that fails to parse
fails the whole `complete_tree` call before any item is touched.

---

## Category: Notes

### notes_read

**Purpose.** Read the notes on a work item: `get` one note with its body, or `list` an item's notes
without bodies. The first 4.0 registry tools: a request is the 4.0 envelope request (`select` plus the
select's fields) and every answer is the response envelope (`ok`, `data` or `error`, `meta.reqId`). The
same tool is served over MCP and over REST `POST /api/v1/ops/notes_read`; the REST note resource GETs
are thin translations to it (see `api-rest.md` section 11). `notes_read` replaces `query_notes` `get` and
`list`. Each field is documented once in the tool's own schema (`describe: true`).

**Selects.** `get`, `list`. Both need the `READ` capability and honor the caller's root and tag scope
(`forbidden`, fail closed for a missing item under a restricted scope).

| Select | Fields | Answer (`data`) |
|---|---|---|
| `get` | `itemId`, `key`, `ifVersion?` | `{id, key, role, version, body, modifiedAt, actor?, verification?}`; with `ifVersion` equal to the current version, `{unchanged: true, version}` and no body |
| `list` | `itemId`, `role?`, `keys?`, `limit?` (1-200, default 50), `cursor?` | `{items: [{id, key, role, version, bodyLength, modifiedAt, actor?, verification?}], page: {total, truncated, next?}}` ordered by key then id |

- `itemId` is a full UUID or 8 to 32 hex characters of one (`ambiguous_id` when a prefix matches more
  than one item). A missing note is `not_found` with id `<itemUuid>/<key>`.
- `list` entries carry an 8-hex-character short `id` and never a body; read a body with `get`. An empty
  `keys` list matches nothing.
- Paging follows the envelope's list rules (amendment U22): `page.total` is the full count on every
  page, `truncated` is true exactly when notes exist after this page, and `next` (an opaque cursor) is
  present exactly when `truncated`, so following `next` until it is absent always terminates.
- `actor` (`{id, kind, parent?}`) and `verification` (`{status, verifier?, reason?}`) appear only when
  the caller may see attribution: an MCP caller always, a REST token when it is `ADMIN` or
  `API_REDACT_NOTE_ATTRIBUTION=false`. Proof evidence (the proof hash and verified claims) is never
  returned.

```json
{ "select": "get", "itemId": "550e8400", "key": "feature-summary" }
{ "select": "list", "itemId": "550e8400-e29b-41d4-a716-446655440001", "role": "queue", "limit": 20 }
```

### notes_write

**Purpose.** Create, replace or delete notes on work items, in batches. Replaces `manage_notes`. A
request is `{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry an `idempotencyKey`.
Every element commits on its own unless `atomic` is true; the answer is a batch result (`results[i]`
with `ok`, and `207 partial_failure` over REST when some elements failed). Both ops need the
`WRITE_NOTES` capability and honor the caller's scope.

| Op | Fields | Result (`results[i]`) |
|---|---|---|
| `upsert` | `itemId`, `key`, `role`, exactly one of `body` / `bodyFromFile`, `expectedVersion?` | `{id, version, created?, warning?}` |
| `delete` | `itemId`, `key`, `expectedVersion?` | `{id}` |

- **Note version (V28).** Every note has a `version` that starts at 1 and goes up by one on every
  successful upsert of an existing note. It is a concurrency token and cache key, not a history.
  `expectedVersion` omitted writes unconditionally; `0` requires that the note does not exist; `n`
  requires that its current version is `n`. Anything else is `version_conflict` and nothing is written.
  A missing note on `delete` is `not_found`.
- **`bodyFromFile` (`BodySource`).** One `BodySource`, built once from the agent config root
  (`AGENT_CONFIG_DIR`, else the server's working directory), reads the file for `notes_write` and
  `docs_write`: a relative path strictly inside the root (no absolute path, no `..`, no
  symlink out of it), a regular UTF-8 file of at most 65536 bytes, CRLF normalized to LF. Failures use
  fixed reasons that never echo filesystem text. An idempotency replay does not re-read the file. Giving
  both or neither of `body` and `bodyFromFile` is `invalid_request`. `bodyFromFile` is MCP-only: on
  `POST /api/v1/ops/notes_write` an element that sets it fails `invalid_request` (`/bodyFromFile`) and no
  file is read; REST callers send the text inline in `body`.
- **Note write policy.** One service (`NoteCommandService`) applies the same policy to `notes_write`
  and `create_work_tree` notes, in this order: (1) `role` is lowercased and must be `queue`, `work` or
  `review`; (2) the body is capped at 65536 UTF-8 bytes in every `note_limits` mode
  (`payload_too_large`); (3) CRLF becomes LF (a lone CR is kept); (4) when the item's resolved schema
  declares the key, the role must equal the schema role (`schema_violation`); (5) the schema
  `maxLength` check in characters: under `note_limits.mode: warn` (default) the note is stored and the
  result carries `warning: {key, max, actual}`, under `reject` the element fails with `note_too_long`.
  `note_limits` is layered per root (see
  [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)).
  A per-root config that cannot be read answers `unavailable` (`retryAfterMs`).
- **Attribution.** Over MCP the body `actor` is lifted as before and recorded with the note
  (verification included); over REST `/ops` the principal is the bearer token's and a body `actor` is
  ignored.

```json
{
  "ops": [
    { "op": "upsert", "itemId": "550e8400", "key": "feature-summary", "role": "queue",
      "body": "The handler must validate JWT signatures.", "expectedVersion": 0, "idempotencyKey": "k-1" },
    { "op": "upsert", "itemId": "550e8400", "key": "implementation-notes", "role": "work",
      "bodyFromFile": "logs/test-run.txt" },
    { "op": "delete", "itemId": "550e8400", "key": "scratch", "expectedVersion": 3 }
  ]
}
```

`manage_notes` is removed (its delete by note ids and delete of every note of an item have no
replacement, and its `itemContext` guidance block is gone: read the next required note with
`get_context`).

---

## Category: Dependencies

### deps_read

**Purpose.** Read dependency edges: `list` the edges touching one item, or walk the `graph` of
`blocks` edges around it. A 4.0 registry tool: the request is the envelope request (`select` plus the
select's fields) and every answer is the response envelope. The same tool is served over MCP and over
REST `POST /api/v1/ops/deps_read`. Replaces `query_dependencies` (its backlinks lookup is
`list` with `direction: incoming`). Each field is documented once in the tool's own schema
(`describe: true`).

**Selects.** `list`, `graph`. Both need the `READ` capability and honor the caller's root and tag
scope: a subject `item` outside the scope is `forbidden`. Scope is checked before existence for a full
UUID, so an unknown full UUID is `forbidden` to a restricted caller; a short hex prefix is resolved
before the scope check, so a prefix that matches nothing can answer `not_found` to a restricted caller.

| Select | Fields | Answer (`data`) |
|---|---|---|
| `list` | `item`, `direction?` (`incoming`, `outgoing`, `all`; default `all`), `type?` (`blocks`, `relates_to`), `limit?` (1-200, default 50), `cursor?` | `{items: [{id, from, to, type, unblockAt?}], page: {total, truncated, next?}}` ordered by creation time then id |
| `graph` | `item`, `limit?`, `cursor?` | `{items: [{id, level} or {id, cyclic: true}], page: {total, truncated, next?, capped?}}` |

- `item` is a full UUID or 8 to 32 hex characters of one (`ambiguous_id` when a prefix matches more
  than one item). Every id in an answer is a full UUID.
- `incoming` is the edges whose `to` is `item` (what blocks it); `outgoing` the edges whose `from` is
  `item`. An edge whose other end is outside the caller's scope is left out and not counted.
- `graph` walks `blocks` edges in both directions from `item` (`relates_to` is ignored) and does not
  list or expand a node outside the caller's scope. Nodes come in topological order with `level` = the
  longest blocker path from a node with no blocker in the graph (sources are 0); nodes on a cycle
  follow with `cyclic: true`. The walk stops at 1000 nodes: then `page.capped` is true and `total` is a
  lower bound.
- Paging follows the envelope's list rules (amendment U22): `page.total` is the full count on every
  page, `truncated` is true exactly when items exist after this page, and `next` is present exactly
  when `truncated`.

```json
{ "select": "list", "item": "550e8400", "direction": "incoming" }
{ "select": "graph", "item": "550e8400-e29b-41d4-a716-446655440001", "limit": 100 }
```

### deps_write

**Purpose.** Create or delete dependency edges. Replaces `manage_dependencies`. A request is
`{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry an `idempotencyKey`. Every element
commits on its own unless `atomic` is true; the answer is a batch result (`results[i]` with `ok`, and
`207 partial_failure` over REST when some elements failed). Every op needs the `MANAGE_DEPENDENCIES`
capability (`manage-dependencies`); `delete` is destructive.

| Op | Fields | Result (`results[i]`) |
|---|---|---|
| `create` | `from`, `to`, `type?`, `unblockAt?` | `{id, from, to, type, unblockAt?}` |
| `delete` | exactly one of `{id}`, `{from, to, type?}`, `{item}` | `{deleted: n}` |
| `linear` | `ids` (at least 2, each blocks the next), `type?`, `unblockAt?` | `{created: [edge, ...]}` |
| `fan_out` | `from`, `toIds` (at least 1), `type?`, `unblockAt?` | `{created: [edge, ...]}` |
| `fan_in` | `fromIds` (at least 1), `to`, `type?`, `unblockAt?` | `{created: [edge, ...]}` |

- `type` is `blocks` (applied when absent on create) or `relates_to`. `unblockAt` is `queue`, `work`,
  `review` or `terminal` (absent means `terminal`) and is rejected on a `relates_to` edge. A self-edge
  is `invalid_request`. The 3.x `is_blocked_by` alias is not accepted: name the blocker as `from`.
- **Patterns are one element.** `linear`, `fan_out` and `fan_in` create all their edges in one unit or
  none: a duplicate edge (`duplicate`) or a cycle (`cycle_detected`, with the cycle path) anywhere in
  the pattern leaves nothing written.
- **Scope.** Every named item must be inside the caller's scope; `delete {id}` checks both stored ends,
  `delete {item}` the item and every counterparty of its edges. A miss is `forbidden` and nothing is
  written. Under a restricted scope, a `cycle_detected` `path` lists only the items the scope admits, in
  path order: a hop through an out-of-scope item is omitted.
- **Delete matching.** `{id}` takes a full dependency UUID; an unknown one is `not_found`. `{from, to}`
  without `type` matches edges of either type; `{from, to}` or `{item}` that matches nothing is
  `deleted: 0`.
- **Attribution.** `dependency.added` and `dependency.removed` events carry the call principal: over
  MCP the body `actor`, over REST `/ops` the bearer token.

```json
{
  "ops": [
    { "op": "create", "from": "550e8400", "to": "7c9e6679", "idempotencyKey": "k-1" },
    { "op": "linear", "ids": ["a1b2c3d4", "b2c3d4e5", "c3d4e5f6"] },
    { "op": "delete", "from": "550e8400", "to": "7c9e6679", "type": "relates_to" }
  ],
  "atomic": true
}
```

`manage_dependencies` and `query_dependencies` are removed. Their `counts`, `includeItemInfo` and
`limit`/`offset` have no replacement: use `page.total`, the `direction` and `type` filters, and
`items_read` for titles.

---

## Category: Workflow

### advance

**Purpose.** Phase transitions and claim ownership in one write tool. It replaces the 3.x `advance_item` and `claim_item`
tools and the REST `POST /items/{id}/advance` route. It is served over MCP and `POST /api/v1/ops/advance` (see `api-rest.md`
section 20a); both answer the same response envelope.

Every `transition` is ONE transaction through the shared advance pipeline (`AdvanceService`): the item is re-read, the transition
policy evaluates its gates in a fixed order (claim ownership, transition table, cascade warrant, hold, blocking dependencies,
required notes, resource lease; the first failure wins), and the role change, its audit row, its event, the lease acquire or
release, and every cascade it triggers commit together or not at all. Unblocked downstream items are then reported.

**Call shape.** `ops` is an array of flat objects, each naming its `op`. Optional top-level `atomic` (default false: each
element succeeds or fails alone; true: the first failure rolls the whole call back and is returned with its `error.index`) and
`actor` (`{id, kind, parent?, proof?}`; on REST the bearer token is the actor and a body `actor` is ignored). Each element may
carry an optional `idempotencyKey` (needs an actor); a repeat of the same key and payload replays the stored result, the same
key with another payload is `idempotency_mismatch`. All ops need the `advance` capability.

| Op | Element fields | Result element (on top of `ok: true`) |
|---|---|---|
| `transition` | `itemId`, `trigger` (`start`, `complete`, `block`, `hold`, `resume`, `cancel`, `reopen`), `summary?`, `credentialRefs?` | `{itemId, previousRole, newRole, trigger, statusLabel?, cascades?, unblocked?, expectedNotes?, guidanceKey?, skillPointer?, dispatch?, violations?}` |
| `claim` | exactly one of `itemId` or `select`; `ttlSeconds` (1 to 86400, default 900); `claimRef?` (at most 64 characters) | `{itemId, claimed: true, claimedAt, expiresAt, originalClaimedAt, refreshed?, selected?, claimRef?}` |
| `release` | `itemId` | `{itemId, released: true}` |
| `heartbeat` | `itemId`, `ttlSeconds` (default 900) | `{itemId, claimed: true, claimedAt, expiresAt, originalClaimedAt, refreshed: true}` |

`itemId` is a UUID or a dash-free hex prefix of 8 to 32 characters (`not_found` when nothing matches, `ambiguous_id` with the
candidate ids when several do).

**`transition` result.** `cascades` lists the parents evaluated as a side effect: `{itemId, previousRole, targetRole, applied,
blocked?: gate | resource | role | dependency, missing?: [{key, role, seat?}], contended?: [resourceKey], blockers?: [{id, role}]}`.
`unblocked` is `[{itemId, title}]`. `expectedNotes` is the list of note keys the new phase expects; `guidanceKey` and
`skillPointer` name the first missing required note's guidance and skill. `dispatch` is `{agent?, model?, effort?}` for the
phase just entered. `violations` is `[{key, seat?, constraint, conflictingSeat?, waived?}]`, present only when the list is
non-empty. Empty and null members are omitted.

**Ownership.** When an item has an active (non-expired) claim, `transition` requires the caller's trusted actor id to equal the
holder, on every trigger and on both MCP and REST; any other caller (including one with no actor) gets `not_claim_holder`.
An unclaimed item, or one whose claim has lapsed, can be transitioned by anyone. Cascades are system transitions and bypass the
check. A transition that lands the item in `terminal` clears its claim. There is no override: an operator who must take over a
claimed item waits out the claim TTL.

**Claims.** `claim` makes the caller the holder; any other item it held is released (one claim per actor). The holder claiming
again refreshes the claim (`refreshed: true`, `originalClaimedAt` kept). `heartbeat` is the same refresh but never takes an item:
it is `not_claim_holder` unless the caller holds the active claim (recommended cadence: half the TTL). Claim, release and heartbeat
need an `actor` (`invalid_request` on `/actor` otherwise); the holder id is the trusted id after the degraded-mode policy (`unauthenticated`
when the policy rejects). The holder's identity never appears in any error detail.

`select` filters: `role` (default `queue`), `parentId`, `tags` (any), `priority`, `type`, `complexityMax`, `createdAfter`,
`createdBefore`, `roleChangedAfter`, `roleChangedBefore`, `orderBy` (`priority` default, `oldest`, `newest`). The best eligible
match (no active claim, no blocking ancestor claim by another actor, no unmet dependency) is claimed and `selected: true` is
reported. A selector that finds nothing claimable is `ok` with `{claimed: false}` plus, when matching items exist but none can be
claimed, `reason` (`claim_held` or `dependency_unmet`), `retryAfterMs` 30000 and `excluded: {claimed, ancestorClaimed,
dependencyBlocked, outOfScope}`; nothing matched at all has no `reason`. Under a root- or tag-scoped token, candidates outside the
scope are skipped and counted in `excluded.outOfScope`; a selector never answers `forbidden`.

**Error codes** (per element; the call is `207 partial_failure` when some elements fail): `invalid_request` (field pointers
such as `/ops/0/trigger`, `/ops/1/ttlSeconds`, `/ops/2/itemId`, `/ops/0/credentialRefs/1`; `/actor` when the actor is missing),
`unknown_parameter`, `not_found` (item), `ambiguous_id`, `forbidden` (token scope or capability), `invalid_transition` (also for
claiming a terminal item), `gate_blocked` (`detail.missing[]`; for an independence block `missing` is empty and
`detail.violations` is `[{gate: "independence", reason}]`), `dependency_unmet`, `not_claim_holder`, `claim_held`
(`detail.expiresAt`, `detail.retryAfterMs`), `resource_unavailable` (`detail.resources[]` with the contended key and its mode,
`retryAfterMs`; never the holder), `unauthenticated`, `unavailable` (per-root config could not be read; `retryAfterMs` 1000),
`idempotency_mismatch`.

Resource leases: a `transition` that enters `work` acquires an exclusive lease for each resource the item's traits declare;
a held key is `resource_unavailable`. Leases are released on every exit from `work`. `DELETE /api/v1/resources/leases/{key}`
(admin) is the recovery path for a stuck lease.

---

### get_next_status

**Purpose.** Read-only status progression recommendation for a single WorkItem. Returns whether
the item is Ready to advance, Blocked, or Terminal, without making any changes. For a queue, work or
review item the answer is the same policy evaluation `advance_item(trigger="start")` runs (claim
ownership excluded), so `Ready` means the `start` would be applied right now.

**When to call.** Call to check one item's advance-readiness when a full context snapshot is not needed.

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

// Blocked by unfilled required notes for the current phase
{
  "recommendation": "Blocked",
  "currentRole": "queue",
  "reason": "gate_blocked",
  "missingNotes": ["task-scope"]
}

// Blocked by an exclusive resource held by another item
{
  "recommendation": "Blocked",
  "currentRole": "queue",
  "reason": "resource_unavailable",
  "contendedResources": ["staging-db"]
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

When the item is in BLOCKED role, the response includes a `suggestion` field instead of `blockers`. When the item is blocked by unsatisfied dependencies, the response includes `blockers` but no `suggestion` (a blocker whose row cannot be read reports `currentRole: "unknown"`). A note or resource block carries `reason` with `missingNotes` or `contendedResources`.

---

### get_context

**Purpose.** Read-only context snapshot in one of three modes determined by which parameters are
supplied. Use for session startup, work-summary dashboards, and pre-advance gate checks.

**Modes:**
- **Item mode** — pass `mode: "item"` (or provide `itemId`): note schema, existing notes with fill status, and gate status for a specific item.
- **Session resume** — pass `mode: "session-resume"` (or provide `since`): active items, recent role transitions since the timestamp, and stalled items, each capped at 200 per role.
- **Health check** — pass `mode: "health-check"` (or omit all mode-selecting params): active items (work/review), blocked items, and stalled items, each capped at 200 per role.

When `mode` is omitted, the mode is inferred from which parameters are present (`itemId` → item, `since` → session-resume, neither → health-check).

**When to call.** Call with no arguments to resume a session; call with `itemId` before any advance or dispatch decision.

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
  "item": { "id": "uuid", "title": "JWT Handler", "role": "work", "tags": "task-implementation", "depth": 1 },
  "schema": [
    { "key": "task-scope", "role": "queue", "required": true, "exists": true, "filled": true, "seat": "planner" },
    { "key": "done-criteria", "role": "work", "required": false, "exists": false, "filled": false, "seat": "implementer" }
  ],
  "gateStatus": { "canAdvance": true, "phase": "work", "missing": [], "missingBySeat": {} },
  "dispatch": { "agent": "task-orchestrator:implementer", "effort": "medium" },
  "seats": [
    { "name": "implementer", "phase": "work", "enters": true },
    { "name": "extractor", "phase": "work", "after": ["implementer"] }
  ],
  "dispatchBySeat": {
    "implementer": { "agent": "task-orchestrator:implementer" },
    "extractor": { "model": "sonnet", "effort": "low" }
  },
  "claimDetail": {
    "claimedBy": "agent-worker-42",
    "claimedAt": "2026-01-01T12:00:00Z",
    "claimExpiresAt": "2026-01-01T12:15:00Z",
    "originalClaimedAt": "2026-01-01T12:00:00Z",
    "isExpired": false
  }
}
```

(`guidanceKey` and `skillPointer` are omitted here because the current phase, `work`, has no missing required notes — `done-criteria` is optional and still unfilled, but only required notes drive `guidanceKey`/`skillPointer`. `dispatch` here is the **work**-phase profile, matching `role: "work"` — an implementer profile, since `get_context` only ever reports the item's current-role profile.)

`guidanceKey` (string, optional) names the first unfilled required note with guidance for the **current** role. Omitted when no schema matches, no required notes exist, or all are filled. Resolve the full guidance text via `items_read(select="schema", itemId=..., fields=["guidance"])`.

`skillPointer` (string, optional): Skill name to invoke for the first unfilled required note. Omitted when no skill is configured or all required notes are filled. Derived from the `skill` field on the first unfilled required note in the schema.

`dispatch` (object, optional): `{agent?, model?, effort?}` routing profile for the item's **current** role, resolved from the `dispatch` trait dimension — same resolution as `advance_item`'s `dispatch` field (per-item `traits` first, then `default_traits`; see [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#dispatch-trait-dimension) → "Dispatch (Trait Dimension)"). Omitted entirely (never `null`/`{}`) when no resolved trait declares a profile for the current role.

`get_context` does not return `noteProgress` — `gateStatus` (`canAdvance`, `phase`, `missing[]`) is the canonical gate signal for the current phase. Required/remaining/total counts are still available via `advance_item` responses (the 3.x `manage_notes` `itemContext` block is gone with that tool).

`gateStatus.canAdvance` is the policy evaluation `advance_item(trigger="start")` would run on the item right now, claim ownership excluded: it is `false` when the transition table rejects `start` (a `terminal` or `blocked` item), a blocking dependency is unmet, a required note for the current phase is unfilled (or a `reject`-mode independence violation blocks), or an exclusive resource the item declares is held by another item. `gateStatus.blockedBy` (string, optional) names that gate — `"table"`, `"dependency"`, `"note"` or `"lease"` — and is present only when `canAdvance` is `false` and the item is not terminal. The preview reads the item's config, notes and blockers in one read unit, so a store or per-root config read fault there fails the `get_context` call (transient `config_unavailable` for a config fault) rather than rendering an empty `missing` list.

Each entry in the `schema` array is keys-only: `key`, `role`, `required`, `exists`, `filled`, plus `seat` (A1) whenever the resolved schema is seat-aware — omitted entirely for a seat-less schema. Resolve `description`/`guidance`/`skill` for any entry via `items_read(select="schema", itemId=..., fields=["guidance"])`.

**Seats (A1).** `gateStatus.missingBySeat` (object, optional) buckets `missing`'s keys by owning seat — `{<seat>: [keys], ..., "unowned": [keys]}`, non-empty buckets only, in merged-seat order with `unowned` always last — present whenever the resolved schema is seat-aware AND the item is not `TERMINAL` (an empty object `{}` when the schema is seat-aware but nothing is missing); omitted entirely for a seat-less schema or a terminal item. Top-level `seats` (array, optional) and `dispatchBySeat` (object, optional, **flat** — `{<seat>: {agent?, model?, effort?}}`, unlike the per-phase-nested shape `items_read(select="schema")` returns) cover the item's **current phase only** — same seat-declaration and dispatch-resolution rules as `items_read(select="schema")`'s `seats`/`dispatchBySeat` fields (see [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seats-trait--schema-dimension) → "Seats"), both omitted entirely (never `[]`/`{}`) when the current phase declares no seats. All seat fields are absent for a `TERMINAL` item and for any seat-less schema — a seat-less config's `get_context` response is byte-identical to a pre-A1 server.

**Independence (A2).** `gateStatus.violations` (array, optional) reports A2 independence-attestation findings for the item's CURRENT phase — present (possibly `[]`) whenever independence `mode` is not `off` and the resolved schema declares `independent_of` somewhere; omitted entirely (not `null`) otherwise, including for a `TERMINAL` item. Each entry is `{key, seat?, constraint, conflictingSeat?, waived?}` — actor-free by construction, never an actor id, proof, or claim — see [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2) → "Independence (A2)" for the constraint kinds (`same_actor`/`missing_actor`/`unverified`), the `independence: temporal-only` waiver, and honest limits. `canAdvance` already accounts for a `reject`-mode block from a non-waived violation (`blockedBy: "note"`), the same way it accounts for missing required notes.

`claimDetail` is present only when the item is currently claimed (`claimedBy != null`). This is the **only** tool mode that exposes `claimedBy` identity — use it for operator diagnostics on stalled or contested items.

> **UTC note.** `claimedAt`, `claimExpiresAt`, and `originalClaimedAt` are computed in Kotlin from the server's one bound clock (no database clock is read) and stored as canonical UTC text, `yyyy-MM-dd HH:mm:ss.SSS` (24-hour, millisecond precision). Every persisted timestamp column uses that one shape, so agents or operators inspecting raw database rows can compare them as text and must not assume local timezone, whatever the host's system time. A claim is active only while `claimExpiresAt` is strictly after "now": an expiry equal to now is already expired, on every surface that decides it (claims and resource leases alike).

`claimDetail` fields:

| Field | Type | Description |
|---|---|---|
| `claimedBy` | string | Agent identity (opaque string — may be `did:web`, session ID, container hostname, etc.) |
| `claimedAt` | ISO 8601 UTC | When the current claim was placed (refreshed on re-claim) |
| `claimExpiresAt` | ISO 8601 UTC | TTL-based expiry (claim time plus TTL, computed server-side). Passive: the claim is not auto-released; expired claims are filtered at read time. |
| `originalClaimedAt` | ISO 8601 UTC | First claim timestamp by the current agent. Preserved across re-claims (heartbeats). Reset when a different agent claims the item. |
| `isExpired` | boolean | `true` when the item has a claim holder (`claimedBy`) and the claim is not active: `claimExpiresAt` is at or before the time of the query, or the claim has no usable timestamps (a partial claim) |

**`resourceLeases`** (array, optional) — present only when the item declares at least one
`resources:` requirement (via its resolved traits) or actively holds at least one lease; omitted
entirely otherwise (zero payload for the common case). Item mode is the sanctioned holder-identity
diagnostic for resource leases, mirroring `claimDetail` above:

```json
{
  "resourceLeases": [
    {
      "key": "staging-db",
      "mode": "exclusive",
      "held": true,
      "holderItemId": "uuid",
      "acquiredByActorId": "agent-worker-42",
      "expiresAt": "2026-07-25T13:00:00Z"
    },
    { "key": "deploy-credential", "mode": "advisory", "held": false }
  ]
}
```

One entry per key the item either declares or currently holds (union of both sets). `mode` is
`"exclusive"` or `"advisory"` from the resolved trait requirement; for a key the item still holds a
lease on but no longer declares (trait removed while the lease is active), `mode` defaults to
`"advisory"` (the least presumptuous fallback). `held` is `true` only when **this item's own** lease
is active for that key. `holderItemId`/`acquiredByActorId`/`expiresAt` are present only when *some*
lease is currently active for that key (self lease takes priority for an undeclared-but-held key;
otherwise the current holder across all items) — omitted for a declared-but-unheld key with no
active lease anywhere. See [Workflow Guide §11](workflow-guide.md#11-resource-leasing) for the
exclusive/advisory modeling guidance and the full guarantees statement.

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

`unclaimed` is deliberately excluded from the health-check `claimSummary` (too noisy). Use `items_read(select="overview")` for per-root-item `claimSummary` including `unclaimed`.

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

**When to call.** Call when choosing what to work on next — at session start or after finishing an item.

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

### get_blocked_items

**Purpose.** Identifies WorkItems that are blocked, either explicitly (role=blocked via a
block/hold trigger) or implicitly (items in queue/work/review with unsatisfied blocking
dependency edges), capped at 500 per role — the response's `total` field reflects the returned
count, not a full count of matching items. Terminal items are never included.

**When to call.** Call when work appears stalled or someone asks why an item cannot start.

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

**Versioned store.** The stored config is the head of a versioned, append-only store: a push appends the
next version of the root's config and moves the head, and a byte-identical re-push appends nothing. Version
history, reading an older version, rollback and reset are REST-only for now (`GET .../config?version=`,
`GET .../config/history`, `POST .../config/rollback`, `DELETE .../config`; see
[api-rest.md §18](api-rest.md#18-endpoints--project-config-per-root)) - this tool's `push` and `get` shapes are unchanged.

**Read-error fallback (last-known-good).** A read of the head (and, on a cache miss, of its version) failing with a transient
repository error is NOT the same as "no per-root config" — `PerRootConfigService` never falls back
to the global config on a read error. It instead serves that root's last cached parse (last-known-good)
without evicting it, and logs a WARN naming the root and the error. Last-known-good has no TTL: the
next successful read refreshes it via the normal fingerprint-comparison hot-reload path, and it is
shared by MCP and REST (one `EffectiveConfigResolver`, built once in `ServerComposition`). A read made
inside a unit of work (a write's or a preview's read unit) never serves last-known-good — a fault there fails closed as below even
with a warm cache; `notes_write` upsert reads
the config this way (`create_work_tree` reads it before its write, so the fallback still applies there). When there is no cached entry to serve — a cold
cache, e.g. the first read for the root — the read fails closed with a transient
`config_unavailable` error (see [Error Envelope](#error-envelope)) rather than silently resolving
against the global config. This is distinct from an explicit absence (no config version, or a reset head), which evicts any cached entry
and falls through to the global config. A head whose stored canonical form is missing or undecodable is neither:
it fails closed (`PerRootConfigInvalidException`) and never falls through to the global layer.
`advance_item`, `complete_tree` and
REST `POST /items/{id}/advance` read the advanced item's config (and every cascade target's) inside the
advance's unit, so the NOTE, lease and label decisions never use config read before the writer lock;
the `canAdvance` previews of `get_context` and REST `GET /items/{id}/gate` read it inside their read unit
the same way.

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
6a. The document is validated strictly (see **Strict validation** below). Any error rejects the push
   and `force: true` does not bypass it.
7. If the incoming document's fingerprint is **superseded** — it appears in the root's fingerprint
   history but is not the current fingerprint (i.e. the server has already moved past this exact
   content) — the push is rejected as stale (see **Fast-forward guard** below) — unless
   `force: true` is passed. A fingerprint the server has never seen (`unknown`) pushes normally;
   the current fingerprint (`current`) is the idempotent re-push.

On success, stores the document and returns its fingerprint. Pushing byte-identical content is
naturally idempotent: the fingerprint returned is unchanged, so a caller can `get` first and skip
the push when fingerprints already match — no separate idempotency-key machinery is needed. The
fingerprint is computed after stripping one leading UTF-8 BOM and normalizing CRLF to LF (nothing
else) — content that differs only by line-ending style or a leading BOM fingerprints identically;
the stored `configYaml` itself is never rewritten by this normalization.

**Which sections a per-root document may carry.** Only a subset of top-level `configYaml` keys belong in
a per-root document; any other key is rejected (see **Strict validation** below), so a push is never
silently partial:

| Top-level key | Honored per-root? | Layered by |
|---|---|---|
| `work_item_schemas` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `note_schemas` (legacy) | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `traits` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `project` | Yes | `ConfigService` (embedded `project.rootId` guard only) |
| `note_limits` | Yes | `ToolExecutionContext.resolveNoteLimitsMode()` |
| `status_labels` | Yes | `ToolExecutionContext.resolveStatusLabel()` |
| `resources` | No — the resource registry is server-only (rejected) | n/a |
| `schema_resolution` | No — removed in 4.0 (rejected) | n/a |
| `extensions` | Stored, never read — an opaque mapping of namespaces for plugin and hook settings | n/a |
| `independence` | Yes | `EffectiveConfigResolver.resolveIndependencePolicy()` (A2) — a per-root block replaces the global one wholesale; see [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2) → "Independence (A2)" |
| `actor_authentication` | No — global-only (rejected) | n/a |
| any other key | No (rejected) | n/a |

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

**Strict validation.** A push is validated before anything is stored: the document is checked against the
published JSON Schema (`GET /api/v1/config/schema`), everything the 3.x parser used to skip with a warning is
an error (an unknown key, a note with no or an invalid `role`, an invalid `lifecycle`, a seat with no `name`,
an invalid dispatch `effort`, ...), and semantic rules apply: `resources` and `actor_authentication` are
server-only, `work_item_schemas` and `note_schemas` cannot be combined, every `default_traits` entry must name
a trait defined in the document or the server config, and every trait `resources[].key` must be declared in
the server resource registry. Plugin and hook settings (the 3.x top-level `orchestration`, `retrospective`
and `actor_attribution`) go under `extensions.claude-code`. The rejection is `VALIDATION_ERROR`; `details`
(text) and `data.errors` list every problem as `{path, reason}` with an RFC 6901 JSON Pointer
`path`. See the [config reference](config-reference.md).

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
  "schemaWarnings": ["Schema 'feature-task': required note 'spec' (role 'work') is not owned by any declared seat; listed under 'unowned' in missingBySeat"]
}
```

`warning` is only present when the root's `type` is not `"project"`. `schemaWarnings` is only present
(and non-empty) for advisory findings: a resource key declared by three or more traits, or a required
note no declared seat owns. The push still succeeds and the document is still stored regardless of
`schemaWarnings` — these are advisory, not validation failures (contrast with the `VALIDATION_ERROR`
cases below, which reject the push outright). The 3.x `ignoredSections` field was removed in 4.0: a key
the per-root layer does not honor is now rejected, not ignored.

**Error cases.**

| Condition | `error.code` |
|---|---|
| `configYaml` exceeds 128 KiB | `VALIDATION_ERROR` (message names the limit and the actual size) |
| `rootId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| Root WorkItem has `depth != 0` | `VALIDATION_ERROR` |
| `configYaml` fails to parse (invalid/unsafe YAML syntax or shape, including `!!`-tagged custom types — see **Parse safety** above) | `VALIDATION_ERROR` (message includes the parse detail) |
| `configYaml` fails strict validation (see **Strict validation** above) | `VALIDATION_ERROR` (message gives the error count; `data.errors` lists `{path, reason}`; nothing is stored) |
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
| `fingerprint` | string (hex SHA-256) | No (get only) | A local document fingerprint (computed with the same BOM-stripped, CRLF-normalized rule the server uses) to classify against this root's stored config; adds `relation` to the response |

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

**Note on actor attribution.** Unlike `notes_write`/`items_write`, this tool does not accept an
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

### docs_read

**Purpose.** Read the versioned documents of a project root: plans, git-tracked rules and pages. A 4.0 registry tool: the request is
the envelope request (`select` plus the select's fields) and every answer is the response envelope. The same tool is served over MCP and
over REST `POST /api/v1/ops/docs_read`. It replaces the read half of `manage_plan_documents` and `GET /api/v1/roots/{rootId}/plans[/{slug}]`.
Each field is documented once in the tool's own schema (`describe: true`).

**Selects.** `get`, `list`, `history`, `links`. All need the `READ` capability and honor the caller's root and tag scope: a `rootId` outside
the scope is `forbidden`, and nothing is read. A document is addressed by `(rootId, kind, slug)`; `kind` (`plan`, `rule`, `page`) is required
on every select that names one document and an optional filter on `list`. A `rootId` that is not a depth-0 item is `invalid_request`.

| Select | Fields | Answer (`data`) |
|---|---|---|
| `get` | `rootId`, `kind`, `slug`, `version?`, `ifVersion?` | `{id, rootId, kind, slug, title?, body, contentHash, version, archivedAt?, adoptedBy?, createdAt, modifiedAt}`; with `ifVersion` equal to the current version `{unchanged: true, version}`; with `version` n `{kind, slug, version, body, contentHash, createdAt, actor?}` |
| `list` | `rootId`, `kind?`, `includeArchived?`, `limit?` (1-200, default 50), `cursor?` | `{items: [{id, kind, slug, title?, contentHash, version, archivedAt?, adoptedBy?, createdAt, modifiedAt}], page}` ordered by kind, slug, id; no body |
| `history` | `rootId`, `kind`, `slug`, `limit?`, `cursor?` | `{items: [{version, contentHash, bodyLength, createdAt, actor?}], page}` oldest first |
| `links` | `rootId`, `kind`, `slug`, `direction?` (`outgoing`, `incoming`, `all`; default `all`), `limit?`, `cursor?` | `{items: [{id, relation, from: {kind, id}, to: {kind, id}, toRef?, anchor?}], page}` ordered by creation time then id |

- `version` and `ifVersion` together are `invalid_request`; a missing document or version is `not_found`. Versions are integers, 1 for the first write.
- `contentHash` is the SHA-256 hex of the LF-normalized body's UTF-8 bytes. For a `rule` document it is the `rulesVersion` that [`query_rules`](#query_rules) reports.
- `list` leaves archived documents out unless `includeArchived` is true. `adoptedBy` is the item that adopted a plan (`create_work_tree` with a `docRef`); it is omitted when the caller's scope does not admit that item.
- `links` returns the links the document makes (`references` parsed from `[[slug]]` in its body, `attached`, `adopted_by`) and, with `incoming`, the links that point at it (a `derived_from` note, another document's reference). A link end is `{kind, id}` with `kind` `item`, `note` or `document`; a reference to a document that does not exist yet has no `to` and carries `toRef`. A link whose item or note end is outside the caller's scope, or no longer exists, is left out before paging and is not counted in `page.total`.
- `actor` on a version is shown only to a caller allowed to see attribution (the same rule as `notes_read`).
- Paging follows the envelope's list rules (amendment U22).

```json
{ "select": "get", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign" }
{ "select": "list", "rootId": "3f9c2b10", "kind": "rule", "includeArchived": true }
{ "select": "links", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign", "direction": "incoming" }
```

### docs_write

**Purpose.** Write the versioned documents of a project root. A request is `{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry
an `idempotencyKey`. Every element commits on its own unless `atomic` is true; the answer is a batch result (`results[i]` with `ok`, and
`207 partial_failure` over REST when some elements failed). It is served over MCP and `POST /api/v1/ops/docs_write`, and replaces the write half
of `manage_plan_documents` and `PUT /api/v1/roots/{rootId}/plans/{slug}`.

Every op needs the `WRITE_CONFIG` capability (`write-config`), the capability of the route this tool replaces: a `write-notes` token cannot
rewrite documents, because rule documents are served to agents as instructions by `query_rules`. Every op also checks the caller's root and tag
scope on `rootId`; a forbidden element is never executed and records no event.

| Op | Fields | Result (`results[i]`) |
|---|---|---|
| `upsert` | `rootId`, `kind`, `slug`, exactly one of `body` and `bodyFromFile`, `title?`, `expectedVersion?` | `{id, kind, slug, version, contentHash, created}` |
| `archive` | `rootId`, `kind`, `slug`, `expectedVersion?` | `{id, kind, slug, version, archived: true}` |
| `unarchive` | `rootId`, `kind`, `slug`, `expectedVersion?` | `{id, kind, slug, version, archived: false}` |
| `delete` | `rootId`, `kind`, `slug` | `{id, kind, slug, deleted: true}` |
| `attach` | `rootId`, `kind`, `slug`, `itemId` | `{linkId, documentId, itemId}` |
| `detach` | `rootId`, `kind`, `slug`, `itemId` | `{detached: n}` |

- **`upsert`.** Creates the document or writes its next version (every write, version 1 included, appends a history row). CRLF becomes LF. A plan or
  page body is at most 65536 UTF-8 bytes, a rule body 16384 (`payload_too_large`). `expectedVersion` omitted writes unconditionally; `0` requires that
  the document does not exist; `n` requires that its current version is `n`; anything else is `version_conflict` and nothing is written. An adopted plan
  is frozen (`duplicate`); an archived document must be unarchived first (`invalid_request`). `title` omitted keeps the current title. `created` is true
  when the write made version 1. The `slug` of an upsert is 1 to 200 characters with no leading or trailing whitespace, no control characters and none
  of `[`, `]`, `#` (a `[[slug]]` reference could not name it), and a `plan` slug must not start with `rule/` (the plan facade would hide it); reads and
  the other ops accept any non-empty slug, so slugs migrated from 3.x stay reachable. The rule-key grammar of `query_rules` is not enforced here.
- **`bodyFromFile`** is MCP-only: over `POST /api/v1/ops/docs_write` an element that sets it fails `invalid_request` (`/bodyFromFile`) and no file is
  read. Over MCP it is read through the same `BodySource` as `notes_write` (a relative path strictly inside the agent config root, a regular UTF-8 file
  of at most 65536 bytes).
- **`archive` / `unarchive`.** Set or clear the archived mark; the version does not change. Archiving an archived document, or unarchiving a live one, is
  a no-op that records no event.
- **`delete`.** Removes the document and its versions and its outbound links; links that pointed at it by text become dangling and resolve again if it
  is re-created. An adopted plan cannot be deleted (`invalid_request`).
- **`attach` / `detach`.** `attach` links an item of the same root to the document (relation `attached`); attaching twice returns the same link. The item
  must be inside the caller's scope (`forbidden` otherwise, nothing written) and under the document's root (`invalid_request`). `detach` is `not_found` for
  a missing document and otherwise `{detached: 0}` or `{detached: 1}`.
- **Events.** `document.created`, `document.updated`, `document.archived` and `document.deleted` carry the call principal.
- `rootId` is a full UUID or 8 to 32 hex characters; a missing root is `not_found`, a root that is not depth 0 `invalid_request`.

```json
{
  "ops": [
    { "op": "upsert", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign", "body": "# Auth Redesign\n\n## Goals\n...", "expectedVersion": 0 },
    { "op": "attach", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign", "itemId": "550e8400" }
  ]
}
```

`manage_plan_documents` and the REST `/roots/{rootId}/plans` routes are removed. A plan's 3.x `status` (`pending` / `adopted`) is now `adoptedBy` on a
document, and a write over an adopted plan answers `duplicate` instead of the 3.x conflict. `create_work_tree` with a `docRef` still adopts a stashed plan.

### query_rules

**Purpose.** Read-only surface for git-tracked rule text -- client-neutral operating rules (the
blind-authorship protocol, commit discipline, forbidden test patterns, review scoping, and so on)
stored as `rule` [documents](#docs_write) (`kind` `rule`, slug `<key>`) via `docs_write` and
served here verbatim. `query_rules` never inlines rule text into `items_read(select="schema")`
-- that surface stays pointer-only: a note-schema entry's `skill` field names a rule key, and the
caller resolves the actual text through this tool. Both operations converge on the same
`RuleService` the REST `rules` routes use (see [api-rest.md](./api-rest.md) section 19a) -- one
read-only view over the `rule` documents `docs_write`/`PlanDocumentService`
write, so MCP and REST always agree on `rulesVersion` and body for the same key.

Supports two operations, selected via `operation`:

#### `get`

Reads back one rule's body and `rulesVersion` (the backing plan document's `contentHash`).
Exactly one of two mutually exclusive parameter pairs selects the lookup:

- **Direct** -- `rootId`+`key`: reads `rule/<key>` under `rootId` directly.
- **Skill-pointer** -- `itemId`+`noteKey`: resolves `itemId`'s EFFECTIVE (trait-merged) schema via
  the same `ToolExecutionContext.resolveSchema()` path `manage_project_config`/`advance_item` use,
  looks up `noteKey`'s entry in that resolved schema, and follows its `skill` field as the rule key
  -- read under the item's OWN `rootId` (`item.rootId`, not a caller-supplied one). The response
  echoes this resolution as `resolvedFrom`.

Providing both pairs, or neither, fails validation (`get requires exactly one of (rootId+key) or
(itemId+noteKey)`).

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"get"` \| `"list"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | One of `rootId`+`key` / `itemId`+`noteKey` (get); required (list) | Project root WorkItem -- direct lookup, or the anchor for `list` |
| `key` | string | With `rootId` (get, direct lookup only) | Rule key: lowercase alphanumeric/`.`/`_`/`-`, starting alphanumeric, max 100 chars (see **Key grammar** below); rejected on `list` |
| `itemId` | string (UUID or 4+ char hex prefix) | With `noteKey` (get, skill-pointer lookup only) | WorkItem whose effective schema resolves the skill pointer; rejected on `list` |
| `noteKey` | string | With `itemId` (get, skill-pointer lookup only) | Note key to resolve via `itemId`'s effective schema; its entry's `skill` field names the rule key; rejected on `list` |

**Key grammar.** `^[a-z0-9][a-z0-9._-]{0,99}$` -- lowercase alphanumeric, `.`, `_`, `-`; must start
with an alphanumeric; max 100 characters; never `/`, uppercase, or `:` (a key is one path segment,
usable verbatim in a REST URL and as the `rule/<key>` plan-document slug suffix). Checked in
`validateParams` for the direct `rootId`+`key` pair (`VALIDATION_ERROR` before any repository
call); for the skill-pointer pair the RESOLVED `skill` value is checked the same way once the
schema lookup succeeds (see **Error cases** below).

**Example (direct).**

```json
{
  "operation": "get",
  "rootId": "3f9c2b10-...",
  "key": "commit-discipline"
}
```

**Example (skill-pointer).**

```json
{
  "operation": "get",
  "itemId": "840e700a-...",
  "noteKey": "test-manifest"
}
```

**Response (success, direct).**

```json
{
  "rootId": "3f9c2b10-...",
  "key": "commit-discipline",
  "slug": "rule/commit-discipline",
  "rulesVersion": "e3b0c44298fc1c14...",
  "body": "..."
}
```

**Response (success, skill-pointer).** Adds `resolvedFrom`:

```json
{
  "rootId": "3f9c2b10-...",
  "key": "test-author",
  "slug": "rule/test-author",
  "rulesVersion": "e3b0c44298fc1c14...",
  "body": "...",
  "resolvedFrom": {
    "itemId": "840e700a-...",
    "noteKey": "test-manifest",
    "skill": "test-author"
  }
}
```

`body` is served byte-for-byte as stored -- no trimming, no re-encoding -- and `rulesVersion` is
always the stored document's `contentHash`, the same value `docs_read` reports for that
rule document (`kind` `rule`, slug `<key>`).

**Error cases.**

| Condition | `error.code` |
|---|---|
| Neither or both of (`rootId`+`key`) / (`itemId`+`noteKey`) supplied | `VALIDATION_ERROR` |
| `rootId`/`itemId` malformed (not a UUID or 4+ char hex prefix) | `VALIDATION_ERROR` |
| `key` fails the key grammar (direct lookup) | `VALIDATION_ERROR` |
| `itemId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| Item has no `rootId` (skill-pointer lookup) | `VALIDATION_ERROR` |
| `rootId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| `rootId` (or the item's own root) is not depth-0 | `VALIDATION_ERROR` |
| `noteKey` not found in the item's effective schema, or its entry has no `skill` field | `RESOURCE_NOT_FOUND` |
| Resolved `skill` value fails the key grammar | `VALIDATION_ERROR` |
| No `rule/<key>` document exists at that root | `RESOURCE_NOT_FOUND` |
| Per-root config read fails transiently while resolving a skill pointer (no last-known-good cached) | transient `config_unavailable` (see [Error Envelope](#error-envelope)) -- a `PerRootConfigUnavailableException` from `resolveSchema()` is deliberately left uncaught in the tool and mapped by the MCP adapter, same as `manage_project_config`/`advance_item` |
| Storage failure | `DATABASE_ERROR` |

#### `list`

Returns `{key, rulesVersion, updatedAt}` for every valid rule key under `rootId`, sorted by key
ascending, **never the body**. Accepts only `rootId` -- `key`, `itemId`, and `noteKey` are all
rejected. A `rule/<key>` document whose key fails the key grammar (stashable via
`docs_write`, which does not itself enforce the grammar, but never served by `get` or
`list`) is silently excluded, as are non-`rule/` slugs. A document's `status` (`pending` vs
`adopted`) is ignored -- both are listed and served; adoption freezes a rule's content, it does not
hide it.

| Parameter | Type | Required | Description |
|---|---|---|---|
| `operation` | string (`"get"` \| `"list"`) | Yes | Selects the operation |
| `rootId` | string (UUID or 4+ char hex prefix) | Yes | Project root WorkItem to list rule keys for |

**Response (success).**

```json
{
  "rootId": "3f9c2b10-...",
  "rules": [
    { "key": "commit-discipline", "rulesVersion": "e3b0c4...", "updatedAt": "2026-09-28T13:00:00Z" },
    { "key": "test-author", "rulesVersion": "a94a8f...", "updatedAt": "2026-09-28T13:05:00Z" }
  ]
}
```

**Error cases.**

| Condition | `error.code` |
|---|---|
| `rootId` malformed, or `key`/`itemId`/`noteKey` supplied on `list` | `VALIDATION_ERROR` |
| `rootId` does not resolve to an existing WorkItem | `RESOURCE_NOT_FOUND` |
| `rootId` is not depth-0 | `VALIDATION_ERROR` |
| Storage failure | `DATABASE_ERROR` |

**Note on actor attribution.** Like `manage_project_config`, this tool does
not accept an `actor` parameter -- it is read-only, with no write to attribute.

---

## Idempotency

The mutating 3.x tools `create_work_tree` and `complete_tree` support `requestId: UUID` for idempotency. The 4.0 write tools (`items_write`, `notes_write`, `deps_write`, `advance`) take no `requestId`: each `ops[]` element may carry an `idempotencyKey` (needs an `actor`), recorded per `(principal, tool, key)` with the same 24-hour record and replay rules; see [items_write](#items_write), [notes_write](#notes_write), [deps_write](#deps_write) and [advance](#advance).

**`requestId` is optional.** `create_work_tree` and `complete_tree` serve both orchestrator-mode (single dispatcher, no idempotency needed) and fleet-mode (idempotency desired) callers. Omitting `requestId` (or the `actor` that identifies the principal) skips idempotency entirely — execution is exactly as before. When present, `requestId` must be a valid UUID — a malformed value is rejected at validation (see Constraints below), not silently ignored.

**How it works.** With a `requestId` and a trusted `actor`, the server keys the call (element `0`: both tools are atomic or single calls) as `"<requestId>:<index>"` under the tool and operation (`mcp.<tool>[.<operation>]`). The element runs in one transaction together with its record, so a record exists only if the element's effects committed. A record lives for 24 hours, is stored in the database (it survives restarts and is shared by every server process on it), and is scoped to the trusted principal id: the actor id after the server's actor policy is applied, which is the self-reported id when the actor is unverified, and always under `degraded_mode_policy: accept-self-reported` (see below).

- **Same key, same element → replayed.** The stored result is returned with `"replayed": true` on that element (`data.replayed: true` for an atomic or single-element tool) and the element is **not** re-executed. Batch counts are recomputed from the element results.
- **Same key, different element → `idempotency_mismatch`.** The element fails with `errorCode` `idempotency_mismatch` and nothing is executed. The comparison is a SHA-256 of the canonical request (sorted keys, no whitespace); the shared call parameters count too, and an `actor.proof` does not.
- **Config-derived decorations are recomputed, never stored:** `schemaMatch` and `expectedNotes` on a created item reflect the config at replay time.

**What is recorded.** Only a result that committed, and a payload rejection that depends on the payload alone (an element that is not a JSON object), is recorded. A failure that depends on state or is transient — `gate_blocked`, a missing item, a config or database fault, any other validation failure — rolls the element back and is **not** recorded, so a retry with the same `requestId` runs it again. This is the retry rule: **a stored success replays for 24 hours; a state or transient failure re-executes on retry with the same `requestId`.** (`complete_tree` is recorded only when every item succeeded; a batch whose elements partly failed re-runs just those on retry because the elements that succeeded replay.)

**Constraints:**
- For `create_work_tree` and `complete_tree`, the top-level `actor.id` is the principal. (Implementation note: these tools extract actor from the request-level field, not per-item fields.)
- `create_work_tree` and `complete_tree` are atomic or single calls: element `0` is the whole call. `complete_tree` opens a unit per item, so concurrent callers with the same key may both run; the call converges because already-terminal items are skipped.
- A non-UUID `requestId` string is rejected at validation on every one of these tools. There is no silent-ignore path.

**Usage.** Generate a fresh UUID per logical operation:

```json
{
  "root": { "type": "task", "title": "Write tests" },
  "actor": { "id": "agent-1", "kind": "subagent" },
  "requestId": "e3b0c442-98fc-1c14-9afb-f4c8996fb924"
}
```

Retry the same call with the same `requestId` whenever the outcome is unknown or a transient failure came back: elements that already succeeded replay, the rest run again. Use a **new** `requestId` for a different payload.

---

## Request Correlation (`_meta.reqId`)

Every tool result, success or error (validation, per-root config, database and internal failures
included), carries `_meta.reqId`: the call's 8-character correlation id (lowercase Crockford base32,
for example `k7f3q9ab`). It is the `req_id` of every event row the call wrote, the MDC `reqId` log
key, and the primary key of the call's row in the `call_log` table (whose `operation` column holds
the `operation` argument only when it is a plain name, else `invalid`; its `principal_id` column holds the
self-reported actor id, up to the 500-character actor cap, with `proof_status` saying whether it was verified). It is not part of
`structuredContent`, so tool payloads are unchanged. `tools/list` and `initialize` have none. See
`fleet-deployment.md` -> "Call log and `reqId`".

## Error Envelope

All tool failures use a structured `ToolError` shape that classifies retry semantics. The MCP layer
surfaces this same object through the tool call's `structuredContent.error`, so clients can branch
on `code`/`kind` without parsing the text summary. **Every** tool error carries `kind`: a top-level
failure, a per-element failure entry (`errorKind`), and a `claim_item` result outcome that is a
failure (`kind`). A single mapper builds every error, and `kind` is always the retry class of the
error catalog code that classifies the failure; the 3.x `code` strings and messages are unchanged.

```json
{
  "error": {
    "code": "DATABASE_ERROR",
    "message": "Database error in 'manage_notes': Database is busy: database is locked",
    "kind": "shedding"
  }
}
```

A per-transition failure of `advance_item` (and a per-item failure of `complete_tree`) puts the same
fields on the element, with `errorCode`/`errorKind` in place of `code`/`kind`, and adds `retryAfterMs`
or `contendedItemId` where they apply:

```json
{
  "itemId": "550e8400-e29b-41d4-a716-446655440001",
  "trigger": "start",
  "applied": false,
  "error": "Cannot enter work phase: resource(s) currently held by another work item: db-migrations",
  "errorKind": "transient",
  "errorCode": "resource_unavailable",
  "retryAfterMs": 420000,
  "contendedResources": ["db-migrations"]
}
```

**Per-element failure entries.** A failed element of a batch call (every `complete_tree` item that was
not applied, including a skipped dependent and an already-terminal item) carries `errorCode` and
`errorKind` next to `error`. `errorCode` is the error catalog's wire code (`invalid_request`,
`not_found`, `ambiguous_id`, `version_conflict`, `duplicate`, `cycle_detected`, `idempotency_mismatch`,
`note_too_long`, `payload_too_large`, `invalid_transition`, `dependency_unmet`, `internal`, ...); an
element that already carried a tool-local code (`config_unavailable`, `gate_blocked`, `NOTE_BODY_TOO_LONG`
in `code`) keeps it unchanged. A `claim_item` result whose `outcome` is a failure (`not_found`,
`terminal_item`, `not_claimed_by_you`, `queue_empty`, `none_eligible`, `db_error`,
`idempotency_mismatch`) carries `kind` and `code` (the outcome value) immediately after `outcome`;
`already_claimed` keeps its `kind`, `contendedItemId` and `retryAfterMs`.

**Kinds by cause.** A failure takes its kind from the catalog code that classifies it: validation, not-found,
ownership, gate, dependency and conflict failures are `permanent`; an unexpected internal error, a store
fault that is not busy, a resource-lease contention, a held claim and a per-root `config_unavailable` are
`transient`; a busy database or a connection-pool timeout is `shedding`. The kind of a `DATABASE_ERROR`
now follows the fault (it was always `permanent` when it escaped a tool): `shedding` for busy or pool
timeout, `permanent` for a duplicate or foreign-key fault, `transient` for any other store fault.
An `advance_item` `apply_failed` takes its kind from its cause the same way (a vanished row or a
version conflict is `permanent`, a busy database `shedding`, anything else `transient`).
An unexpected exception that escapes a tool returns the fixed text `Internal error in '<tool>'`: the exception
message and the session id are never sent to the client (in the text, the `notifications/message` log
notification or the structured error); the server log keeps both. The result also carries
`structuredContent.error` `{code: "INTERNAL_ERROR", message: <same text>, kind: "transient"}`.

**Storage faults.** A database fault keeps the 3.x `DATABASE_ERROR` code on both reads and writes, and
is never reported as `RESOURCE_NOT_FOUND` (not-found means only that the row does not exist). A tool
that already reports storage failures keeps its own message prefix. On a read the prefix is followed
by the innermost SQL error text (for example `Failed to read project config: <sql text>`). On a write
it is followed by the catalog message for the fault class: `Database error: <sql text>`,
`Duplicate record: <sql text>` or `Referenced record not found: <sql text>`; under contention that
outlasts the unit's retry deadline, `Database is busy: <sql text>`,
`No database connection became available in time: <sql text>` or
`Writer lock not available within <deadline>`; and, for a write misrouted to the read-only reader
connection, `A write was routed to the read-only reader: <sql text>`. Any other storage fault
is returned as `DATABASE_ERROR` with the message `Database error in '<tool>': <sql text>`.

### ErrorKind Values

| Kind | Meaning | Retry behavior |
|---|---|---|
| `transient` | Temporary failure; retrying may succeed | Retry with exponential backoff. Typical causes: lock contention, JWKS unavailable, transient DB busy, per-root config read failure (`config_unavailable` — see below). |
| `permanent` | Definitive failure; retrying will produce the same result | Do not retry. Typical causes: validation errors, authorization failures, not-found. |
| `shedding` | Server temporarily over capacity | Retry after `retryAfterMs` milliseconds when it is present, otherwise back off. Produced for a busy database (`SQLITE_BUSY` after the unit's retry deadline) and a connection-pool timeout. |

### Error Envelope Fields

| Field | Type | Description |
|---|---|---|
| `code` | string | Structured error code for programmatic handling |
| `message` | string | Human-readable failure description |
| `kind` | string | One of: `transient`, `permanent`, `shedding`. Always present. |
| `retryAfterMs` | integer (nullable) | Milliseconds to wait before retrying. Populated for `shedding`; null otherwise (use own backoff). |
| `contendedItemId` | string UUID (nullable) | UUID of the work item involved in a contention error. Populated for `transient` claim-race or version-conflict failures. Allows agents to distinguish "retry this item" from "pick a different item" without parsing `message`. |
| `details` | any (nullable) | Additional structured detail specific to the failure (e.g., gate `missingNotes`, dependency `blockers`). Omitted when there is nothing beyond `message`. |

**`config_unavailable` (transient).** A root's per-root config could not be read (a transient
database error on `getFingerprint`/`get`) and there was no last-known-good cached config for that
root to serve instead — see `manage_project_config`'s Purpose note above for the last-known-good
cache this falls back to. A read made inside a unit of work (an advance, and the `canAdvance` preview)
has no fallback, so there it fails even when the cache is warm. `retryAfterMs` is null, per the
`transient` kind's own-backoff rule.
`advance_item` reports this per transition (the rest of a batch continues) and `complete_tree`
reports it per item (`skipped: true`, outcome `REJECTED`, in-set dependents skipped); `notes_write`
reports it per element - an element whose schema/note-limits-mode resolution hits this error fails
alone with the envelope code `unavailable` (`retryAfterMs`) and nothing stored for it, while the other
elements of a non-atomic batch commit normally; every other tool
fails the whole call with this envelope. A read failure resolving a RESPONSE-ONLY decoration
(e.g. `advance_item`'s `dispatch`, `create_item`/`create_work_tree`'s `schemaMatch`/`expectedNotes`,
or `availableTraits`) on an already-committed write is never
reported as a failure of that write — the decoration is simply omitted and a WARN is logged instead.

**`VALIDATION_ERROR` (permanent, adapter-level).** Any `ToolValidationException` thrown by a tool's
`validateParams()` or `execute()` — malformed/missing/mistyped parameters caught anywhere in that
tool's request handling — is mapped by `McpToolAdapter` to this envelope: `text` is
`"Validation error in '<tool>': <exception message>"` and `structuredContent.error` is
`{code: "VALIDATION_ERROR", message: <same text>, kind: "permanent"}` (no `retryAfterMs`,
`contendedItemId`, or `details`). This applies uniformly regardless of which phase threw.

### Retry Decision Guide

```
kind=transient  → exponential backoff, retry same operation with a fresh requestId
  contendedItemId present → option: skip this item, pick another from get_next_item
kind=permanent  → do not retry; fix the request (validation, permissions, etc.)
kind=shedding   → wait retryAfterMs, then retry; reduce polling rate if this persists
```

See [Idempotency](#idempotency): a retried transient failure re-executes under the same `requestId`.

---

## Actor Attribution

### Overview

Actor attribution tracks *who* made changes to work items. Every `advance_item` transition and `notes_write` upsert can include an optional `actor` claim. By default the server uses a no-op verifier, so claims are persisted as `unchecked`; configuring `actor_authentication.verifier: {type: jwks}` switches to cryptographic verification of the claim's proof. See [fleet-deployment.md](./fleet-deployment.md) → Actor authentication.

### Actor Claim Shape

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| id | string | yes | Identifier for the actor |
| kind | string | yes | `orchestrator`, `subagent`, `user`, or `external` |
| parent | string | no | ID of the dispatching agent — forms a delegation chain |
| proof | string | no | Opaque credential — checked by the configured actor verifier (not verified under `noop`), never returned in MCP responses. Since migration V17, the raw proof is never persisted — only a SHA-256 hash (always, when non-blank) and the verified JWT claims (when VERIFIED) are stored; see [Verification Record](#verification-record). |

### Verification Record

Every persisted actor claim includes a verification record.

**Output omission.** MCP tool responses (`advance_item` transitions,
`get_context` recent transitions) omit the `verification` object
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

**Proof evidence (V17+, never on the MCP surface).** Since migration `V17__Store_Actor_Proof_Evidence.sql`,
the database additionally stores `proofSha256` (a SHA-256 hex digest of the raw proof, set
whenever a non-blank proof was supplied) and `proofClaims` (the verified JWT claims — `iss`,
`sub`, `aud`, `jti`, `iat`, `exp`, `kid`, `alg` — set only when `status` is `verified`). Neither
field is ever included in an MCP tool response — `VerificationResult.toJson()` enumerates only
`status`/`verifier`/`reason`/`metadata` by design. They are surfaced only over REST, as
`verification.proof` on `NoteDto`/`RoleTransitionDto`, and only to callers with `ADMIN`
capability. See [api-rest.md](api-rest.md) §5/§22.

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
  enabled: true   # read by the plugin hook, not the server
```

When enabled, the plugin's `PreToolUse` hook blocks any `advance_item` or `notes_write` call where one or more elements are missing an `actor` object. The call never reaches the server — the agent must retry with actor claims included.

When `enabled` is `false` or the `actor_authentication` section is absent, calls pass through with no enforcement. Actor claims can still be provided voluntarily.

> **Note:** The hook re-reads the config file on every call, so a change to `enabled` takes effect on the next write without an MCP reconnect. `enabled` is read only by the hook, from the client-side workspace or user config, not by the server.

#### Subagent Behavior

Actor claims are self-reported by convention — the server does not inject them automatically. This means:

- **Uninstructed subagents** make calls with no actor data, producing anonymous transitions and notes
- **Instructed subagents** include actor claims only when the delegation prompt tells them to
- The enforcement hook applies to all callers in the session, including subagents, providing a safety net regardless of prompt quality

For reliable attribution, combine actor authentication enforcement with explicit delegation instructions:

```
Include an "actor" object on every advance_item and notes_write call:
{ "id": "your-agent-name", "kind": "subagent", "parent": "orchestrator-id" }
```

---

## Actor Authentication & Verification

### Verifier Configuration

The `actor_authentication` section in `.taskorchestrator/config.yaml` controls actor attribution enforcement and verification. The `enabled` flag (read only by the plugin hook, from the client-side workspace or user config; the server ignores it) and the `verifier` block (read by the server from the global file) are independent — enforcement checks actor presence, while the verifier checks proof validity.

```yaml
actor_authentication:
  enabled: true          # Plugin hook only (client-side config); the server ignores this key
  degraded_mode_policy: accept-cached   # accept-cached (default) | accept-self-reported | reject
  verifier:
    type: jwks           # "noop" (default) | "jwks"
    # Exactly one key source: jwks_uri, jwks_path, or oidc_discovery.
    jwks_uri: "https://provider.example/.well-known/jwks.json"
    # jwks_path: ".agentlair/jwks.json"
    # oidc_discovery: "https://provider.example/.well-known/openid-configuration"
    issuer: "https://provider.example"   # strongly recommended (startup WARN when missing)
    audience: "task-orchestrator"            # strongly recommended
    algorithms: ["EdDSA", "RS256"]          # required
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
| `jwks` | JWT tokens in `actor.proof` are validated against the configured JWKS key set. Valid token → `status: verified`. Invalid, expired, wrong-claims, or missing-`exp` token → `status: rejected` with a descriptive `reason` (missing `exp` yields `reason: "missing exp claim"`) and `metadata.failureKind`. A token whose lifetime exceeds `max_token_lifetime_seconds` is rejected with `reason: "token lifetime exceeds maximum"`, `failureKind: claims`; a future-dated `iat` yields `reason: "iat in the future"`, `failureKind: claims`. A malformed claims set (e.g. non-numeric `exp`/`iat`) is rejected with `reason: "malformed JWT claims: …"`, `failureKind: claims` — under both DID trust and static-JWKS mode. When `jti_replay_protection: true`, a missing `jti` is rejected with `reason: "missing jti claim"`, `failureKind: claims`, and a replayed `(iss, jti)` pair is rejected with `reason: "jti replay detected"`, `failureKind: policy`. Missing proof → `status: absent`. Network/fetch errors → `status: unavailable`. |

### JWKS Key Sources

Configure exactly one of `oidc_discovery`, `jwks_uri`, or `jwks_path`; more than one fails startup. An explicit `issuer` overrides the issuer found by OIDC discovery.

| Field | Description |
|---|---|
| `oidc_discovery` | URL to an OpenID Connect discovery document. The server fetches `jwks_uri` and `issuer` from the document. |
| `jwks_uri` | Direct URL to a JWKS endpoint. Overrides the URI discovered via `oidc_discovery`. |
| `jwks_path` | Path to a local JWKS JSON file, relative to `AGENT_CONFIG_DIR`. Useful for local dev and air-gapped environments. |
| `issuer` | Expected `iss` claim in the JWT. Overrides the issuer discovered via `oidc_discovery`. Strongly recommended in `jwks_uri`/`jwks_path` mode; a startup WARN is logged when missing (and `oidc_discovery` is not set). |
| `audience` | Expected `aud` claim in the JWT. Strongly recommended in static-JWKS mode; a startup WARN is logged when missing. |
| `algorithms` | List of accepted signing algorithms (e.g., `["EdDSA", "RS256"]`). |
| `cache_ttl_seconds` | How long to cache fetched JWKS keys (default: 300). |
| `stale_on_error` | When true (default), a stale cached key set is used if a JWKS refresh fails. The result is `verified` with `metadata.verifiedFromCache="true"` and `metadata.cacheAgeSeconds` set. When false, fetch failures always return `unavailable`. |
| `require_sub_match` | When true, the JWT `sub` claim must match `actor.id`. |
| `allow_insecure_url` | Boolean, default `false`. Must be a boolean — any other type fails startup. When `false` (default), `oidc_discovery` and `jwks_uri` (plus a `jwks_uri` discovered via `oidc_discovery`) must use `https`; any other scheme fails startup. When `true`, plaintext `http` is additionally accepted, but only for a literal loopback host (`localhost`, `127.x.x.x`, `::1`) — local dev/test only. Does not affect `jwks_path` or DID-trust mode. |
| `max_token_lifetime_seconds` | Integer, default `86400` (24h). Must be a positive number (Int/Long only) — any other type or a non-positive value fails startup. Caps how long a token remains acceptable: rejected once `exp - now` exceeds this value plus the 60s clock-skew allowance, independent of `iat`; when `iat` is present, a future-dated `iat` or an `exp - iat` spread beyond the same cap is also rejected. See [fleet-deployment.md § Token Lifetime Cap](fleet-deployment.md#token-lifetime-cap). |
| `jti_replay_protection` | Boolean, default `false`. When `true`, every token must carry a non-blank `jti`, and each `(iss, jti)` pair may be presented only once (tracked until `exp + 60s`) — see [fleet-deployment.md § Opt-in `jti` Replay Protection](fleet-deployment.md#opt-in-jti-replay-protection) for the per-call memo that keeps legitimate in-call/heartbeat re-verification of the same proof from tripping this. |

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
