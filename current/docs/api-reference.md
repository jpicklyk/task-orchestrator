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
| `items_write` | Hierarchy & CRUD | Write | Create, update, or delete WorkItems, create a whole tree, complete one, or repin an item (`ops[]`, per-element results) |
| `items_read` | Hierarchy & CRUD | Read | Get one item, list a filtered page, read a note schema, search items and notes, summarize the hierarchy, or find an item's relatives; orchestration profile adds `context` (gate state or health view), `next` (ranked pick-up candidates) and `blocked` (4.0 envelope) |
| `notes_write` | Notes | Write | Upsert or delete notes on work items, in batches, with version guards (4.0 envelope) |
| `notes_read` | Notes | Read | Get one note or list an item's notes (4.0 envelope) |
| `deps_write` | Dependencies | Write | Create or delete dependency edges, single or by pattern (linear, fan_out, fan_in) (4.0 envelope) |
| `deps_read` | Dependencies | Read | List an item's edges by direction, or walk its blocking graph in topological order (4.0 envelope) |
| `docs_write` | Documents | Write | Upsert, archive, unarchive, delete a root's versioned documents; attach or detach items (4.0 envelope) |
| `docs_read` | Documents | Read | Get a document or one version, list, history and links (4.0 envelope) |
| `advance` | Workflow | Write | Phase transitions (cascade and gate enforcement) and claim ownership: ops `transition`, `claim`, `release`, `heartbeat` |
| `config` | System | Write/Read | Per-root config (get, history, push, validate, rollback, reset), the effective resolved view, and rule text: ops of one 4.0 tool, orchestration profile only |

---

## Category: Hierarchy & CRUD

### items_write

**Purpose.** Create, update and delete WorkItems, create a whole tree, complete one, or repin an item. One `ops[]`
array of flat objects, each naming its op in `op` (`create`, `update`, `delete`, and `create_tree`, `complete_tree`
and `repin` in the orchestration profile only; the knowledge profile neither lists nor accepts them). It is the 4.0
replacement of `manage_items`, `create_work_tree` and `complete_tree`, and the same call is served over MCP and
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
| `create_tree` | `root` (required), `parentId`, `children`, `deps`, `notes`, `createNotes`, `docRef` (see [`create_tree`](#create_tree)) | `{ok, id, version, items, deps, notes}` |
| `complete_tree` | exactly one of `rootId` and `itemIds`; `trigger`, `includeRoot` (see [`complete_tree`](#complete_tree)) | `{ok, id?, trigger, items, summary}` |
| `repin` | `id` (required), `expectedVersion` | `{ok, id, version, repinned, schemaVersion?}` |

**Update semantics.** An absent or `null` field keeps its stored value. `tags: []` and `traits: []` clear those
lists. `clear` lists optional fields to set to null: `description`, `statusLabel`, `metadata`, `complexity`,
`properties`, `parentId` (the last moves the item to root). A field cannot be both sent and cleared in one element.
`expectedVersion` is the optimistic lock: when given, a stale value fails `version_conflict` (`kind`, `id`,
`expected`, `actual`) and nothing is written; when absent the update locks against the version it just read.
`type` cannot be cleared; a changed type is checked against the destination root's declared types.

**Delete semantics.** A parent without `recursive: true` fails `invalid_request` with a violation on `recursive`
whose `received` is the child count; nothing is deleted. `recursive: true` deletes the whole subtree, deepest first,
in one unit. Notes and dependency edges go with their items, as do the links the removed notes and items were the source of and the resolved links into the removed items (an `adopted_by` link is kept). `expectedVersion` guards the item itself.

**Pins.** Every item is pinned to the config it resolves against: the server and root config versions that were the
heads when the pin was taken, and the base schema (key and layer) its type resolved to under them. Its schema,
traits, seats, dispatch and resource requirements come from the pinned versions until a pin point fires; a later
config push, rollback or reset changes nothing for it (status labels, note limits, independence and the resource
registry always follow the heads). The pin points are: `create`; an `update` that changes `type`, moves the item to
another root, or changes `traits` (one `item.pinned` event; reason precedence `type_change` > `reparent` >
`trait_change`); a transition out of `terminal` (`reopen`, and the reopen cascade); and `repin`. A title-only
update or a move within the same root leaves the pin alone. See `config-reference.md` "Schema pins".

**Repin.** `repin` moves the item's pin to the current heads and the base schema its type resolves to now. When the
recomputed pin equals the stored one nothing is written and no event is recorded (`repinned: false`, `version`
unchanged); otherwise the item's `version` goes up by one and one `item.pinned` event (reason `repin`) is recorded
(`repinned: true`). `expectedVersion` guards it like `update`. `schemaVersion` is the item's new pin view (see
[items_read](#items_read)). Errors: `not_found`, `ambiguous_id`, `version_conflict`, `forbidden`, `unavailable`.

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
principal of the `item.created`, `item.updated`, `item.reparented`, `item.deleted` and `item.pinned` events the call
writes. `item.pinned` (`{from, to, reason}`, where `from` / `to` are `{server, root, key, layer}` with the two config
version ids) is recorded in the events table only; it is not projected onto the SSE stream.

```json
{
  "ops": [
    { "op": "create", "title": "Add retry", "type": "feature-task", "parentId": "9f3a1c22", "tags": ["backend"] },
    { "op": "update", "id": "9f3a1c22", "expectedVersion": 3, "priority": "high", "clear": ["description"] },
    { "op": "delete", "id": "0b6d4e11", "recursive": true }
  ]
}
```

#### `create_tree`

Creates a root with its children, the dependencies between them and their notes as ONE write unit: everything commits
or nothing does (zero items, notes, dependencies, links and events; a referenced plan document stays PENDING). It
replaces the 3.x `create_work_tree` tool. Orchestration profile only; capability `write-items`.

| Field | Meaning |
|---|---|
| `root` (required) | The root. Create mode: `title` and `type` required, plus any `create` field except `parentId` (`priority`, `tags` and `traits` arrays, `summary`, `description`, `statusLabel`, `complexity`, `requiresVerification`, `metadata`, `properties`) and `noteAnchors`. Attach mode: `id` (UUID or 8+ hex prefix) names an existing item that becomes the root; no root row is written and the other root fields except `noteAnchors` are ignored. |
| `parentId` | Create mode only: the new root's parent (root depth = parent depth + 1). Not allowed with `root.id`. |
| `children` | `[{ref, title, type, parentRef?, ...}]`: `ref` is a unique local name (`root` is reserved), `parentRef` the parent's ref (default `root`), plus the same optional fields and `noteAnchors` as the root. Any order: children are written parent before child (`TopoOrder` over the `parentRef` edges, ties in request order). |
| `deps` | `[{from, to, type?, unblockAt?}]` between refs (`root` included): `type` `blocks` (default) or `relates_to`, `unblockAt` `queue`, `work`, `review` or `terminal` (not with `relates_to`). |
| `notes` | `[{itemRef, key, role, body?}]`: `role` `queue`, `work` or `review`. |
| `createNotes` | `true`: a blank note for every key of each item's resolved schema that no explicit note or anchor writes. |
| `docRef` | `{slug, rootId?}`: the PENDING plan document `noteAnchors` cut from; `rootId` defaults to (and must equal) the tree's own project root. A `rule/` slug is rejected. |
| `noteAnchors` (on `root` and each child) | `[{noteKey, role, anchor}]`: the document section under the heading slugged `anchor` becomes that item's note. Requires `docRef`. |

Note precedence on `(item, key)`: explicit `notes` win over `noteAnchors`, anchors win over `createNotes` blanks, and
among explicit notes (and among anchors) the last one wins. Every note goes through the
[`notes_write`](#notes_write) write policy; under `note_limits.mode: warn` an over-`maxLength` note carries `warning`.
The notes record the caller's actor. Anchored notes get a `derived_from` link to the document; the root adopts the
document as the unit's last write.

Result: `{ok, id, version, items, deps, notes}`. `id`/`version` are the root's (created or attached). `items` is
`[{ref, id, depth}]`, the root first (ref `root`), then the children in insertion order. `deps` is
`[{id, fromRef, toRef, type, unblockAt?}]` in request order; `notes` is `[{itemRef, key, role, id, warning?}]`.
Schema decorations (`schemaMatch`, `expectedNotes`) are not returned: read them with `items_read` `select: schema`.

Check order, each before any write: (1) the request's shape (`invalid_request` with element pointers, all faults at
once: `root.id` with `parentId`, a missing `root.title` / `root.type` in create mode, a reserved or repeated child
`ref`, an unknown `parentRef`, a `parentRef` cycle on `/children`, an unknown dep or note ref, a self dependency,
`unblockAt` with `relates_to`, `noteAnchors` without `docRef`, a `rule/` slug); (2) scope (below); (3) the plan:
an undeclared or blank `type` (`schema_violation`, the item service's rule), a dependency cycle (`cycle_detected`, the
path holds item ids, the message names the ref) or restatement (`duplicate`), a note policy rejection
(`schema_violation`, `payload_too_large`, `note_too_long`), a missing document (`not_found`, kind `document`), an
already-adopted document (`duplicate`, kind `document`), an anchor miss (`invalid_request` on
`/root/noteAnchors/<j>/anchor` or `/children/<i>/noteAnchors/<j>/anchor`), a `docRef.rootId` mismatch
(`invalid_request` on `/docRef/rootId`); (4) the writes: a closed parent is `invalid_transition`, a vanished parent
`not_found`.

**Scope.** The whole tree hangs under one anchor, which is the only check: `root.id` (attach) or `parentId` must be
admitted; a top-level root create is `forbidden` under a root scope and, under a tag scope, needs a listed tag in
`root.tags`. A `docRef.rootId` is never looked up: it is matched against the tree's own root, so a foreign or
unknown id is the same `invalid_request` on `/docRef/rootId`.

#### `complete_tree`

Applies `complete` or `cancel` to a tree or an explicit list, item by item, through the same pipeline as `advance`
`transition` (claim ownership, dependencies, note gate, leases, cascades, unblocks). Replaces the 3.x `complete_tree`
tool. Orchestration profile only; capability `advance`.

| Field | Meaning |
|---|---|
| `rootId` | The descendants of this item, then the item itself last (`includeRoot`, default `true`). |
| `itemIds` | Exactly these items. Exactly one of `rootId` and `itemIds`; an empty list is `invalid_request`. |
| `trigger` | `complete` (default) or `cancel` (bypasses the note gate, not ownership). |
| `includeRoot` | With `rootId`: also the root. |

Items run in blocking order: `TopoOrder` over the in-set blocking edges (ties in target order; items on a cycle last),
then the root. Per item:

- already `terminal`, or terminalized by a cascade earlier in this call: `{itemId, outcome: "skipped", reason:
  "already_terminal"}`, never gate-checked, and its dependents still run. No `error` member: an already-done item is
  not a failure.
- an in-set blocker failed (directly or upstream): `{itemId, outcome: "skipped", reason: "upstream_failed",
  blockedBy: [<failed in-set ids>]}`.
- applied: `{itemId, outcome: "applied", previousRole, newRole, statusLabel?, cascades?, unblocked?, violations?}` in
  the `advance` `transition` shapes.
- rejected: `{itemId, outcome: "failed", error: {code, kind, message, detail?, fix?}}`, the same catalog error
  `advance` `transition` returns for that item (`gate_blocked`, `dependency_unmet`, `not_claim_holder`,
  `resource_unavailable`, `invalid_transition`, `unauthenticated`). Its in-set dependents are skipped, except after
  `invalid_transition` (the item cannot take the trigger from its role), whose dependents still run.

Result: `{ok, id?, trigger, items, summary: {total, applied, skipped, failed}}` (`id` is the `rootId` when given;
`total` is the sum of the others). All applied transitions of the element commit together, in one write unit; a
per-item rejection is data and never rolls them back. A store fault while applying an item, or a per-root config fault
(`unavailable`), fails the whole element and rolls it back. Errors of the element itself: `not_found`,
`ambiguous_id`, `forbidden`, `unavailable`.

**Scope.** `rootId` and every `itemIds` entry are checked (under a restricted scope an unknown or ambiguous id is
`forbidden`), and then every collected target: a tree that is not wholly admitted is `forbidden` and nothing runs.

**Idempotency.** Standard: a keyed element replays its stored result (`replayed: true`), including its per-item
failures; resend with a new key to retry the failed items.

---

### items_read

**Purpose.** Read work items: `get` one item, `list` a filtered page, read the note `schema` of a type or
an item, full-text `search` over item content and note bodies, an `overview` of the hierarchy, or the
items and documents `related` to an item. A 4.0
envelope tool: a request is `select` plus that select's fields, and every answer is the response envelope
(`ok`, `data` or `error`, `meta.reqId`). The same tool is served over MCP and over REST
`POST /api/v1/ops/items_read`; `GET /items/{id}` and `GET /items/{id}/children` are thin translations to
it (see `api-rest.md` section 9). `items_read` replaces `query_items` (`get`, `search`, `overview`,
`schema`) and the note search of `query_notes`. Each field is documented once in the tool's own schema
(`describe: true`).

**Selects.** `get`, `list`, `schema`, `search`, `overview`, `related` (every profile), and `context`, `next`,
`blocked`, `history` (the orchestration profile only; the knowledge profile neither lists nor accepts them). Every select
needs the `READ` capability and honors the root and tag scope of the caller.

| Select | Fields | Answer (`data`) |
|---|---|---|
| `get` | `itemId`, `fields?`, `ifVersion?` | the full item view; with `ifVersion` equal to the stored version, `{unchanged: true, version}` |
| `list` | `parentId?`, `ancestorId?`, `depth?`, `role?`, `priority?`, `tags?`, `traits?`, `type?`, `claimStatus?`, `createdAfter?`, `createdBefore?`, `modifiedAfter?`, `modifiedBefore?`, `roleChangedAfter?`, `roleChangedBefore?`, `sort?`, `order?`, `limit?`, `cursor?`, `fields?` | `{items: [compact view], page}` |
| `schema` | exactly one of `type` / `itemId`; `rootId?` (with `type` only); `fields?` | the resolved schema view, or `{schemaFree: true}` |
| `search` | `query`, `kinds?`, `matchMode?`, `itemId?`, `ancestorId?`, `role?`, `tags?`, `traits?`, `snippet?`, `explain?`, `limit?`, `cursor?` | `{items: [hit], page}` |
| `overview` | `itemId?` or `anchorId?` (not both), `excludeTerminal?`, `includeChildren?` (global only), `limit?`, `cursor?` | global `{items, page}`; scoped `{item, childCounts, items, page}`; anchored `{anchor: {id, title}, items, page}` |
| `related` | `itemId`, `acrossRoots?`, `explain?`, `limit?`, `cursor?` | `{items: [hit], page}` |
| `context` | `itemId?`, `ancestorId?`, `limit?`, `cursor?`, `fields?` | with `itemId`: the gate state of one item; without: `{active, blocked, stalled, claimSummary}` |
| `next` | `parentId?`, `ancestorId?`, `role?`, `priority?`, `tags?`, `type?`, `complexityMax?`, date bounds, `orderBy?`, `includeClaimed?`, `limit?`, `cursor?`, `fields?` | `{items: [compact view], page, reason?}` |
| `blocked` | `parentId?`, `ancestorId?`, `limit?`, `cursor?`, `fields?` | `{items: [compact view + blockType + blockers], page}` |
| `history` | `itemId?`, `since?`, `until?`, `limit?`, `cursor?` | `{items: [transition entry], page}` |

**Ids.** Every id field takes a full UUID or 8 to 32 hex characters of one. A prefix that matches more than
one item is `ambiguous_id`; no match is `not_found`. Under a restricted scope an id that matches nothing
the caller may see is `forbidden`, never `not_found`, so scope is no existence oracle, and an ambiguous
prefix lists only the candidates the scope admits. A target id (`get`, `schema`, `related`, the overview
`itemId` and `anchorId`, `rootId`) outside the scope is `forbidden`. A filter id (`list` `parentId` / `ancestorId`,
`search` `itemId` / `ancestorId`) is not scope-checked: an out-of-scope subtree simply selects nothing.

**Item views.** One renderer serves every select. Lean defaults: a null, an empty string, an empty array and
`requiresVerification: false` are omitted; `version` is always present; `tags` is an array (the stored
comma-separated list split and trimmed); claim identity is never rendered.

- Full view (`get`, the scoped overview's `item`): `id`, `parentId`, `rootId` as full UUIDs, then `type`,
  `title`, `summary`, `description`, `role`, `statusLabel`, `previousRole`, `priority`, `complexity`,
  `depth`, `tags`, `traits`, `metadata`, `properties`, `requiresVerification`, `version`, `schemaVersion`, `stale`,
  `diagnostics`. `schemaVersion` is the item's pin, `{server?, root?, key?, layer?}`: the pinned server and root
  config version numbers and the pinned base schema key and layer (`server` or `root`), each omitted when null;
  the whole member is omitted for an item that was never pinned. `stale: true` appears only when either pinned
  version is no longer the head of its scope (`repin` moves it). Rendering the pin reads config, so a config read
  fault with nothing cached answers `unavailable`.
- Compact view (`list` entries, overview entries, `ancestors`): `id` and `parentId` as 8-hex short ids,
  `type`, `title`, `role`, `statusLabel`, `priority`, `depth`, `tags`, `traits`, `version`. A compact view never
  carries a pin.

**`fields`.** Opt-in members; a name a select does not accept is `invalid_request` on `/fields`.
`get`: `createdAt`, `modifiedAt`, `roleChangedAt`, `ancestors`. `list`: the same plus `summary`.
`schema`: `guidance` (per-note `guidance` is stripped unless asked for). `ancestors` is the compact chain,
root-first: a root-scoped caller sees nothing above the first scope root in the chain, and a tag-scoped
caller sees only ancestors carrying an allowed tag.

**Filters.** `tags` matches items with at least one of the listed tags (case-insensitive); `traits` matches
items with at least one of the listed traits (exact). Date bounds are inclusive ISO 8601 instants; an
inverted pair is `invalid_request`. `list` sorts by `sort` (`title`, `priority`, `complexity`, `createdAt`,
`modifiedAt`; default `createdAt`) in `order` (`asc`, `desc`; default `desc`); the 3.x aliases are gone.

**Paging.** `list`, `search`, `overview` and `related` page with `limit` and `cursor`. `list` and `overview`:
`limit` 1 to 200, default 50. `search`: 1 to 100, default 20. `related`: 1 to 50, default 10. A `limit` outside its range is `invalid_request`; a
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
config). The `itemId` path resolves the item's fully trait-merged schema over its own `rootId` (a pinned item over
its pinned config versions and base schema, so `configFingerprint` is the pinned layer's). The view is
`{type, configFingerprint, configSource, schemaVersion?, stale?, notes: [{key, role, required, description,
guidance?, skill?, maxLength?, seat?, independentOf?}], dispatch?, resources?, seats?, dispatchBySeat?, features}`;
`schemaVersion` and `stale` are the item's pin view as in the full item view (item path only). Seats,
dispatch and the type-path vs item-path difference are described in
[`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seats-trait--schema-dimension).
No matching schema is not an error: the answer is `{schemaFree: true}`. Giving both or neither of `type`
and `itemId`, or `rootId` without `type`, is `invalid_request`.

**Server feature advertisement.** `features` lists the optional-response capabilities this server can serve
(`seats`, `dispatchBySeat`, `independent_of`, `rules`), unconditionally, whatever the resolved config
declares, so a client can tell an old server from a config that declares no seats. `features` also appears
on REST `GET /api/v1/info` and the `.well-known` service descriptor (see `api-rest.md`). `rules` advertises
per-root rule text served by the [`config`](#config) tool's `rules` op.

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

**`related`.** The items and documents related to `itemId`, fused from four ranked signals with weighted
Reciprocal Rank Fusion: each signal contributes `weight / (60 + position)` (position 1-based in that
signal's list), with weights `edges` 1.0, `links` 1.0, `tags` 0.5, `text` 0.5. The signals:

- `edges`: dependency counterparts in both directions, RELATES_TO first, then blocking ones, each by id.
  Reasons `relates_to`, `blocks` (this item blocks it) and `blocked_by` (it blocks this item).
- `links`: resolved links from the item or one of its notes (`links_to`) and links to the item
  (`linked_from`), any relation; a note end counts as its owning item. Entities with more link rows rank
  higher. Dangling links, a deleted target and an archived document never count.
- `tags`: items sharing a tag (case-insensitive), each shared tag worth `1/n` where `n` is the number of
  visible items carrying it, so rare tags weigh more. Reason `shared_tags:<n>` (the number of shared tags).
- `text`: up to 8 distinctive words of the item's title, summary, description and note bodies (longest
  first, common words dropped), matched any-term with stemming over item content, note bodies and
  documents; a note match counts as its item. Reason `text`.

Hits are ordered by score (higher first), then kind (`document` before `item`), then id. The item itself
never appears. By default every signal stays inside the item's own root; `acrossRoots: true` lifts that
bound only. The caller's scope always applies before fusion, so a relative the caller may not see is
absent and not counted. An item hit is `{kind: "item", itemId, title, score, reasons, explain?}`; a document
hit is `{kind: "document", rootId, docKind, slug, title?, score, reasons, explain?}`, addressed like a search
document hit. `reasons` lists every signal that found the hit, in the order edges, links, tags, text.
`explain` (default off) adds `{ranks: {edges?, links?, tags?, text?}, weights, rrfK}`. `page.total` is the
size of the capped list (at most 50); `page.capped` is true when more relatives existed. The call log's
`request_shape` carries `relatedSignals`, per signal the number of hits on the returned page it found
(zeros kept), and each returned hit records its id and version as a target version.

**Orchestration selects.** `context`, `next`, `blocked` and `history` replace `get_context`, `get_next_status`,
`get_next_item` and `get_blocked_items`. They share the compact view, the id rules, the `fields` rule and the page
rules above; every list is a `page` with an exact `total`, and under a restricted scope every list, count and total
covers only items the caller may see (the scope is applied in SQL before paging). Whether an item is blocked and
whether a blocker is satisfied are always decided on the true rows; a blocker outside the scope of the caller is
omitted from `blockers` while its item stays listed.

**`context`, item mode (`itemId`).** The state an advance or dispatch decision needs, from ONE preview read unit
(`canAdvance`, `blockedBy`, `blockers`, `contended`, `missing`, `missingBySeat` and `violations` never disagree with
`advance` on the same state):

```json
{
  "item": { "id": "550e8400", "type": "feature-task", "title": "JWT Handler", "role": "work", "priority": "high", "depth": 2, "version": 4 },
  "schema": [ { "key": "task-scope", "role": "queue", "required": true, "exists": true, "filled": true, "seat": "planner" } ],
  "gateStatus": { "canAdvance": false, "phase": "work", "missing": ["implementation-notes"], "missingBySeat": { "implementer": ["implementation-notes"] }, "blockedBy": "note" },
  "transitions": { "block": "blocked", "cancel": "terminal", "complete": "terminal", "hold": "blocked", "start": "review" },
  "guidanceKey": "implementation-notes",
  "skillPointer": "spec-quality",
  "dispatch": { "agent": "task-orchestrator:implementer" }
}
```

- `item` is the compact view; `fields: ["ancestors"]` adds the ancestors the caller may see.
- `schema` is keys-only (`key`, `role`, `required`, `exists`, `filled`, plus `seat` when the resolved schema is
  seat-aware). Resolve `description`, `guidance` and `skill` with `items_read(select="schema", itemId=...,
  fields=["guidance"])`.
- `gateStatus.canAdvance` is the policy evaluation `advance(start)` would run right now, claim ownership excluded:
  false when the transition table rejects `start` (a `terminal` or `blocked` item), a blocking dependency is unmet,
  a required note of the current phase is unfilled (or a `reject`-mode independence violation blocks), or an
  exclusive resource the item declares is held by another item. `blockedBy` (`table`, `dependency`, `note`,
  `lease`) names the first failing gate and is present only when `canAdvance` is false and the item is not
  terminal. `blockers` is the unsatisfied list of a dependency block, `[{id, role?, threshold}]` (`id` a full UUID);
  `contended` lists the resource keys of a lease block; both are omitted when empty.
- `next` (`{trigger: "start", role}`) is present only when `canAdvance` is true.
- `transitions` is the row of the item in the status graph for its resolved schema: each user trigger the
  transition table allows from the role of the item, to its target role (`resume` resolved to the `previousRole` of
  the item, omitted when none). A terminal item allows `reopen`.
- `gateStatus.missingBySeat` (seat-aware schema, non-terminal item), `seats` and `dispatchBySeat` (current phase
  only, flat `{<seat>: {agent?, model?, effort?}}`) follow the seat rules of
  [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seats-trait--schema-dimension);
  `dispatch` is the `{agent?, model?, effort?}` profile of the current role of the item. All are omitted for a
  seat-less or terminal item, never `[]`/`{}`.
- `gateStatus.violations` (`[{key, seat?, constraint, conflictingSeat?, waived?}]`, actor-free) reports the
  independence-attestation findings of the current phase: present (possibly `[]`) when independence `mode` is not
  `off` and the schema declares `independent_of`, omitted otherwise
  ([`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#independence-a2)).
- `claimDetail` (`claimedBy`, `claimedAt`, `claimExpiresAt`, `originalClaimedAt`, `isExpired`) is present only for a
  claimed item and is the only place a claim identity is disclosed; `isExpired` is true when the claim is not active
  (an expiry at or before now, or a claim without usable timestamps). Timestamps are UTC ISO-8601.
- `resourceLeases` (`[{key, mode, held, holderItemId?, acquiredByActorId?, expiresAt?}]`) is present only when the item
  declares or holds a resource lease: one entry per key it declares or holds; `held` is true only for the own active
  lease of the item; the holder members appear when some lease on the key is active and its holder item is visible to the
  caller (a holder outside a restricted scope is omitted, like an out-of-scope blocker)
  (see [Workflow Guide](workflow-guide.md#11-resource-leasing), section 11).
- `ancestorId`, `limit` and `cursor` with `itemId` are `invalid_request`. A per-root config read fault answers the
  transient `unavailable` error, never a fallback.

**`context`, health mode (no `itemId`).** `{active: {items, page}, blocked: {items, page}, stalled: [...],
claimSummary: {active, expired, unclaimed}}` over the whole scope or, with `ancestorId`, that subtree. One `limit`
(default 50, max 200) and `cursor` page `active` (role `work` and `review`, longest in the role first) and `blocked`
together. `blocked` is the same set as the `blocked` select (role `blocked`, or open with an unsatisfied
dependency; each entry has `blockType`, no blocker detail); the 3.x health check counted role `blocked` only. `stalled` holds the active items on the page whose
resolved schema has unfilled required notes for their role: `{id, title, role, missing[], guidanceKey?,
skillPointer?}`. `fields: ["ancestors"]` adds ancestors to `active` and `blocked` entries. The 3.x session-resume
mode (`since`, `recentTransitions`) has no form here: `history` serves the transition trail.

**`next`.** The items an agent could pick up now, ranked, with eligibility decided in SQL over the whole matching set
(an eligible item is found however many ineligible ones rank ahead of it): the item has the `role` (default
`queue`; `terminal` is `invalid_request` on `/role`), matches the filters, has no active claim, has no ancestor
holding an active claim, and has no unsatisfied blocking dependency. `complexityMax` is 1 to 10 and excludes items
without a complexity; `tags` matches any (case-insensitive); date bounds are inclusive and an inverted pair is
`invalid_request`. `orderBy`: `priority` (default: high first, then lower complexity, unrated last), `oldest`
(`createdAt` ascending) or `newest`; ties by id. `limit` 1 to 200, default **1**. `includeClaimed` turns the claim
predicates off (claimed items and items under a claim by another agent stay; dependency blocking still applies) and
adds `isClaimed` (a boolean, never the holder) to each entry. `fields`: `summary`, `ancestors`.

When the page is empty, `data.reason` says why only if items matched the filters: `claim_held` (matches exist but
are under an active claim, their own or an ancestor claim) or `dependency_unmet` (matches are free of claims but wait
on a blocker; with `includeClaimed`, always this). Nothing matched: no `reason`, and a drain worker should stop; a
`reason` means wait and retry.

```json
{ "items": [ { "id": "550e8400", "type": "feature-task", "title": "Design login flow", "role": "queue", "priority": "high", "depth": 2, "version": 3 } ],
  "page": { "total": 12, "truncated": true, "next": "b2Zmc2V0OjE" } }
```

Discovery patterns for multi-role fleets: work agents `{select: "next"}`; review agents `{select: "next", role: "review"}`;
triage agents `{select: "next", role: "blocked"}`; fleet health `{select: "next", includeClaimed: true}`; FIFO drain
`{select: "next", orderBy: "oldest"}`.

**`blocked`.** The blocked items the caller may see: role `blocked` (`blockType: "explicit"`) and queue, work and
review items with at least one unsatisfied blocking dependency (`blockType: "dependency"`); never terminal. Order:
`blocked` first, then queue, work, review, then id. Each entry is the compact view plus `blockType` and `blockers`,
EVERY blocking dependency into the item (satisfied ones too) ordered by blocker id: `[{id, title?, role?, threshold,
satisfied}]` (`id` a full UUID, `threshold` the role the blocker must reach, `satisfied` whether it has). `limit`
1 to 200, default 50. `fields`: `summary`, `ancestors`.

**`history`.** The transition audit trail, read from the `item.transitioned` rows of the event log, newest first
(`seq` descending). With `itemId` (resolved and scope-checked like `get`: `forbidden`, `not_found`, `ambiguous_id`)
it lists that item's transitions; without it, every transition of the items the caller may see (a root scope keeps
its roots, a tag scope keeps tagged items; a row whose item was deleted is visible to an unrestricted or
root-scoped caller only). `since` and `until` bound `occurredAt` inclusively (ISO 8601; `since` after `until` is
`invalid_request` on `/since`); there is no default window. `limit` 1 to 200, default 50, `page.total` is exact.
Each entry: `{seq, id, itemId, occurredAt, trigger, fromRole, toRole, fromStatusLabel?, toStatusLabel?, origin?,
summary?, consumedCredentials?, backfilled?, actor?, verification?}`; `id` is the event id and `itemId` a full
UUID; absent values are omitted; `consumedCredentials` is omitted when empty; `origin` is `user` or `cascade` on
a live row; `backfilled: true` marks a row copied from the retired 3.x `role_transitions` table (V29), which has
no `origin`. `actor` `{id, kind, parent?}` and `verification` `{status, verifier?, reason?}` appear only when the
caller may see attribution (the `notes_read` rule); the proof hash and the verified claims are never returned.

```json
{ "select": "context", "itemId": "550e8400" }
{ "select": "context", "ancestorId": "550e8400", "limit": 25 }
{ "select": "next", "role": "queue", "tags": ["backend"], "orderBy": "oldest", "limit": 5 }
{ "select": "blocked", "ancestorId": "550e8400", "fields": ["ancestors"] }
{ "select": "history", "itemId": "550e8400", "limit": 20 }
{ "select": "history", "since": "2026-10-01T00:00:00Z" }
```

```json
{ "select": "get", "itemId": "550e8400", "fields": ["ancestors"], "ifVersion": 7 }
{ "select": "list", "ancestorId": "550e8400", "role": "work", "tags": ["backend"], "limit": 20 }
{ "select": "schema", "type": "feature-task", "rootId": "a1b2c3d4", "fields": ["guidance"] }
{ "select": "search", "query": "OAuth flow", "kinds": ["note"], "ancestorId": "550e8400" }
{ "select": "overview", "anchorId": "550e8400", "excludeTerminal": true }
{ "select": "related", "itemId": "550e8400", "explain": true, "limit": 5 }
```

`get_context`, `get_next_status`, `get_next_item` and `get_blocked_items` are removed: their `mode`,
`includeAncestors` / `includeDetails` flags, comma-separated `tags`, `limit` ceilings (20, 500 per role, 200 per
role) and `blockerCount` / `recommendations` / `blockedItems` shapes have no direct form; use `select`, `fields`,
a `tags` array, `limit` / `cursor` with exact `page.total`, and the shapes above.

`query_items` and `query_notes` are removed. Their `offset` paging, `includeAncestors` and
`includeTimestamps` flags, comma-separated `tags` filter, `sortBy` / `sortOrder` names and the
`RESOURCE_NOT_FOUND` for a schema-free item have no direct form: use `cursor`, `fields`, a `tags` array,
`sort` / `order` and `schemaFree`.

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
  and `items_write` `create_tree` notes, in this order: (1) `role` is lowercased and must be `queue`, `work` or
  `review`; (2) the body is capped at 65536 UTF-8 bytes in every `note_limits` mode
  (`payload_too_large`); (3) CRLF becomes LF (a lone CR is kept); (4) when the item's resolved schema
  declares the key, the role must equal the schema role (`schema_violation`); a pinned item whose pinned
  schema does not declare the key, while the schema it would resolve to at the current config heads
  declares it as required, fails `schema_pinned_conflict` (409, permanent; `detail.itemId`,
  `pinnedVersion`, `currentVersion`, each version as `server:<n|none>/root:<n|none>`): `repin` the item
  first; (5) the schema
  `maxLength` check in characters: under `note_limits.mode: warn` (default) the note is stored and the
  result carries `warning: {key, max, actual}`, under `reject` the element fails with `note_too_long`.
  `note_limits` is layered per root (see
  [`config-format.md`](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md)).
  A per-root config that cannot be read answers `unavailable` (`retryAfterMs`). A config head that exists but cannot be loaded answers `config_invalid` (permanent).
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
`items_read(select="context")`).

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
`retryAfterMs`; never the holder), `unauthenticated`, `unavailable` (per-root config could not be read; `retryAfterMs` 1000), `config_invalid` (a config head, the server's or the root's, has no loadable canonical form; permanent,
`detail.errors[0].path` empty; never `internal`),
`idempotency_mismatch`.

Resource leases: a `transition` that enters `work` acquires an exclusive lease for each resource the item's traits declare;
a held key is `resource_unavailable`. Leases are released on every exit from `work`. `DELETE /api/v1/resources/leases/{key}`
(admin) is the recovery path for a stuck lease.

---

## Category: System

### config

**Purpose.** Per-root config and rule text in one `ops[]` call (4.0 envelope; orchestration profile only, nothing
of it appears in the knowledge profile). It replaces the 3.x `manage_project_config` and `query_rules` tools and the
REST routes `GET|PUT|DELETE /roots/{rootId}/config`, `GET /roots/{rootId}/config/history`,
`POST /roots/{rootId}/config/rollback`, `GET /roots/{rootId}/config/effective`, `GET /roots/{rootId}/rules[/{key}]`
and the server-set reads `GET /config`, `/config/schemas[/{type}]`, `/config/traits`, `/config/types` and
`/config/status-graph`. The same call is served over MCP and as `POST /api/v1/ops/config`. Only
`GET /api/v1/config/schema` (the published JSON Schema) stays a route.

The request is `{ "ops": [...], "atomic"?: bool, "actor"?: {...} }`; each element is a flat object naming its op in
`op`, with an optional `idempotencyKey`. One failed element of a non-atomic call is `207 partial_failure` on REST;
`atomic: true` fails the whole call with the code's own status. Every `rootId` is a UUID or a hex prefix of 8 to 32
characters and must name a depth-0 item. The scope check runs first, before existence and depth: a root outside the
caller's scope is `forbidden`, never `not_found`.

| Op | Fields | Capability | Result |
|---|---|---|---|
| `get` | `rootId`, `version?`, `fingerprint?` | `read` | `{rootId, version, fingerprint, source, createdAt, configYaml, relation?}` |
| `history` | `rootId`, `limit?` (1..200, default 50), `before?` | `read` | `{rootId, headVersion?, versions: [{version, fingerprint, source, createdAt, principalId?, warningsCount, canonical}]}` newest first |
| `push` | `rootId`, `configYaml`, `force?`, `expectedVersion?` | `write-config` | `{rootId, version, fingerprint, appended, createdAt, warnings?}` |
| `validate` | `rootId`, `configYaml` | `read` | `{rootId, valid, errors: [{path, reason}], warnings: [{path, reason}], diff?}` |
| `rollback` | `rootId`, `version`, `expectedVersion?` | `write-config` | `{rootId, version, fingerprint, source, appended, createdAt}` |
| `reset` | `rootId` | `write-config` (destructive) | `{rootId, appended}` |
| `effective` | `rootId?` | `read` | `{rootId?, schemas, traits, types, statusGraph, defaultSchema?, globalFingerprint?, perRootFingerprint?}` |
| `rules` | `rootId?`, `key?`, `itemId?`, `noteKey?` | `read` | a list `{rootId, rules: [{key, rulesVersion, updatedAt}]}` or one rule `{rootId, key, slug, rulesVersion, body, resolvedFrom?}` |

`validate` writes nothing, so it needs only `read`. The retired `cfg-<fingerprint>` and `cfg-v<version>` ETags and
the `If-Match` / `If-None-Match` compare-and-set are gone: use `expectedVersion` on `push` and `rollback`.

**Versioned store.** The stored config is the head of a versioned, append-only store: a `push` appends the next
version of the root's config and moves the head, and a byte-identical re-push appends nothing (`appended: false`).
`rollback` appends a copy of an earlier version (source `rollback`; to the head's own content it appends nothing; to a reset version it appends a reset, or nothing when the head is already a reset),
`reset` appends an empty `reset` version so the root falls back to the server config, and history keeps every version.
`get` without `version` reads the head (a reset head is `not_found`); with `version` it reads that exact version, a
reset one as an empty document. `get` with `fingerprint` adds `relation`: `current`, `superseded` (an earlier version
or the pre-versioning history held that content) or `unknown`.

**`push` pipeline.** In order: `configYaml` at most **128 KiB** (131,072 bytes, UTF-8), rejected before any parse
(`payload_too_large`); strict parse with a safe constructor (see **Parse safety**); the embedded `project.rootId`
guard; strict validation (see **Strict validation**); then one write. Failures are `config_invalid` with
`detail.errors` as `{path, reason}` RFC 6901 pointers (a parse failure without a pointer is one entry at path `""`;
a `project.rootId` naming another root is one entry at `/project/rootId`). `force: true` skips the `rootId` mismatch
guard and the older-content guard only: it never skips validation or `expectedVersion`. Content an earlier version
held, without `force`, is `version_conflict` with `expected` the newest stored version that held it (0 when only the
pre-versioning history knew it) and `actual` the head. An `expectedVersion` that differs from the head is
`version_conflict` with `expected` your value and `actual` the head; it is ignored when the root has no config yet.
A root whose `type` is not `project` still succeeds, with a first `warnings` entry; the advisory schema findings
(a resource key declared by three or more traits, a required note no declared seat owns) follow it, and `warnings`
is omitted when there are none. A successful push that appended also makes later ops of the same call see the new
head (`effective` after `push` reads the new layer).

**`validate`** runs the same size, parse, `rootId` and validation steps as `push` without writing, without
`expectedVersion` and without the older-content guard. Problems come back in the result (`valid: false`, `errors`),
including a parse failure and a `project.rootId` mismatch; an oversized document stays `payload_too_large`. When the
document has no errors, `diff` is the change against the head, `{types, traits, settings}` each with `added`,
`removed` and `changed` name lists (no head, or a reset head, counts as empty).

**`effective`** is one layered read. With `rootId` it is that root's config over the server's; without it, the
server config alone (no scope check, `read` only). `schemas` lists every registered type resolved (`configSource`
is `per-root` or `global`), `traits` carries `dispatch` and `resources` when declared, `statusGraph` is the
structural transition graph for those types, and the two fingerprints are omitted when that layer has none. The
status graph is structural: it shows which triggers are valid per role, not whether an item's gates would pass.

**`rules`** reads git-tracked rule text (`rule` documents, `kind` `rule` and slug `<key>`, written by [`docs_write`](#docs_write)). The mode
follows the fields: `rootId` alone lists keys (never bodies); `rootId` with `key` returns one rule's body and its
`rulesVersion` (the document's content hash), byte for byte; `itemId` with `noteKey` resolves the item's effective
schema, looks up the note entry and follows its `skill` field as the rule key under the item's root (a depth-0 item
is its own root), echoing `resolvedFrom: {itemId, noteKey, skill}`. Any other combination is `invalid_request`
naming the offending field. `key` is `^[a-z0-9][a-z0-9._-]{0,99}$`; a `noteKey` with no entry, no `skill`, or a
skill that is not a valid key is `invalid_request` at `/noteKey`. An absent rule is `not_found` kind `document` with
id `rule/<key>`. Documents whose key fails the grammar are never listed or served; `pending` and `adopted` rules are
both served.

**Errors.** An absent root under an unrestricted scope is `not_found` kind `root`; a root with no config, or an
absent version, `not_found` kind `config` (id the root). A `rootId` that is not depth 0 is `invalid_request` at
`/rootId`. An unloadable config head is `config_invalid`, an unreadable one `unavailable`.

**Read-error fallback (last-known-good).** A read of the head failing with a transient repository error is NOT the
same as "no per-root config": `PerRootConfigService` never falls back to the server config on a read error. Outside a
unit of work it serves that root's last cached parse (last-known-good) and logs a WARN; the cache is shared by MCP and
REST (one `EffectiveConfigResolver`, built once in `ServerComposition`) and has no TTL. A read made inside a unit of
work (a write's or a preview's read unit, and every `config` op runs in one) never serves last-known-good and fails
closed with a transient `unavailable`; with a cold cache the same holds everywhere. This is distinct from an explicit
absence (no config version, or a reset head), which evicts any cached entry and falls through to the server config. A
head whose stored canonical form is missing or undecodable fails closed (`config_invalid`) and never falls through.
`advance`, `items_write` `create_tree` and `complete_tree`, and the `items_read` `context` preview read the item's config inside their unit the same way.

**Which sections a per-root document may carry.** Only a subset of top-level `configYaml` keys belong in a
per-root document; any other key is rejected, so a push is never silently partial:

| Top-level key | Honored per-root? | Layered by |
|---|---|---|
| `work_item_schemas` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `note_schemas` (legacy) | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `traits` | Yes | `PerRootConfigService` / `ToolExecutionContext.resolveSchema()` |
| `project` | Yes | `ConfigService` (embedded `project.rootId` guard only) |
| `note_limits` | Yes | `ToolExecutionContext.resolveNoteLimitsMode()` |
| `status_labels` | Yes | `ToolExecutionContext.resolveStatusLabel()` |
| `resources` | No: the resource registry is server-only (rejected) | n/a |
| `schema_resolution` | No: removed in 4.0 (rejected) | n/a |
| `extensions` | Stored, never read: an opaque mapping of namespaces for plugin and hook settings | n/a |
| `independence` | Yes | `EffectiveConfigResolver.resolveIndependencePolicy()`: a per-root block replaces the server one wholesale |
| `actor_authentication` | No: server-only (rejected) | n/a |
| any other key | No (rejected) | n/a |

**Strict validation.** A push is validated before anything is stored: the document is checked against the
published JSON Schema (`GET /api/v1/config/schema`), everything the 3.x parser used to skip with a warning is
an error (an unknown key, a note with no or an invalid `role`, an invalid `lifecycle`, a seat with no `name`,
an invalid dispatch `effort`, ...), and semantic rules apply: `resources` and `actor_authentication` are
server-only, `work_item_schemas` and `note_schemas` cannot be combined, every `default_traits` entry must name
a trait defined in the document or the server config, and every trait `resources[].key` must be declared in
the server resource registry. Plugin and hook settings (the 3.x top-level `orchestration`, `retrospective`
and `actor_attribution`) go under `extensions.claude-code`. See the [config reference](config-reference.md).

**Parse safety.** `configYaml` is parsed with SnakeYAML's `SafeConstructor` rather than its default
`Constructor`. The default constructor will instantiate an arbitrary Java type named by a YAML
`!!`-tag, a well-known deserialization-RCE class of vulnerability (CWE-502), which matters here
because `configYaml` is attacker-reachable input pushed over the MCP protocol, not a trusted local file.
`SafeConstructor` only builds plain maps, lists and scalars, which is everything a config document needs; any
`!!`-tagged custom type is rejected as a parse failure. The same applies everywhere per-root config bytes are
parsed (`PerRootConfigService`, on every schema-resolving read) and to the server-set boot import.

**Examples.**

```json
{ "ops": [
  { "op": "validate", "rootId": "3f9c2b10-...", "configYaml": "work_item_schemas:\n  feature-task:\n    notes: []\n" },
  { "op": "push", "rootId": "3f9c2b10-...", "configYaml": "work_item_schemas:\n  feature-task:\n    notes: []\n", "expectedVersion": 4 },
  { "op": "effective", "rootId": "3f9c2b10-..." }
] }
```

```json
{ "ops": [ { "op": "rules", "itemId": "840e700a-...", "noteKey": "test-manifest" } ] }
```

**Out of scope.** The server set (`project.rootId` absent) has no `get`, `history`, `push`, `rollback` or `reset`
op: it is written only by the boot import of the mounted file, and `effective` without a `rootId` is its read.

**Trust-model caveat.** `push`, `rollback` and `reset` can silently rewrite a project's gate configuration
(required notes, review phases, trait routing) for every item under a root. Under `MCP_TRANSPORT=http`, the `/mcp`
endpoint is **unauthenticated regardless of REST API auth mode**: any client that can reach the port can call these
ops for any root. This is the existing MCP transport trust model (see [`fleet-deployment.md`](./fleet-deployment.md),
"MCP HTTP Transport (`/mcp`) Is Unauthenticated - Read First") applying to config data the way it applies to every
other MCP tool. Fence `/mcp` at the network layer per that guidance before exposing `MCP_TRANSPORT=http` beyond a
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
- `contentHash` is the SHA-256 hex of the LF-normalized body's UTF-8 bytes. For a `rule` document it is the `rulesVersion` that the [`config`](#config) tool's `rules` op reports.
- `list` leaves archived documents out unless `includeArchived` is true. `adoptedBy` is the item that adopted a plan (`items_write` `create_tree` with a `docRef`); it is omitted when the caller's scope does not admit that item.
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
rewrite documents, because rule documents are served to agents as instructions by the `config` tool's `rules` op. Every op also checks the caller's root and tag
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
  the other ops accept any non-empty slug, so slugs migrated from 3.x stay reachable. The rule-key grammar of the `config` tool's `rules` op is not enforced here.
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
document, and a write over an adopted plan answers `duplicate` instead of the 3.x conflict. `items_write` `create_tree` with a `docRef` still adopts a stashed plan.

---

## Idempotency

The write tools (`items_write`, `notes_write`, `deps_write`, `advance`) take no `requestId`: each `ops[]` element may
carry an `idempotencyKey` (needs an `actor`), recorded per `(principal, tool, key)` for 24 hours. A repeated key with
the same element replays the stored result with `replayed: true`; the same key with a different element is
`idempotency_mismatch`. Only an element that committed is recorded, so a failed element re-runs on retry with the same
key. `items_write` `create_tree` and `complete_tree` follow this rule like every other op: one element is one unit and
one record (the 3.x `requestId` of `create_work_tree` and `complete_tree` left with those tools). See
[items_write](#items_write), [notes_write](#notes_write), [deps_write](#deps_write) and [advance](#advance).

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

A per-transition failure of `advance_item` puts the same
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

**Per-element failure entries.** A failed element of a batch call carries `errorCode` and
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
root to serve instead — see the [`config`](#config) tool's last-known-good note for the
cache this falls back to. A read made inside a unit of work (an advance, and the `canAdvance` preview)
has no fallback, so there it fails even when the cache is warm. `retryAfterMs` is null, per the
`transient` kind's own-backoff rule.
`advance_item` reports this per transition (the rest of a batch continues), `items_write` `complete_tree`
fails its whole element (`unavailable`, every applied transition of it rolled back); `notes_write`
reports it per element - an element whose schema/note-limits-mode resolution hits this error fails
alone with the envelope code `unavailable` (`retryAfterMs`) and nothing stored for it, while the other
elements of a non-atomic batch commit normally; every other tool
fails the whole call with this envelope. A read failure resolving a RESPONSE-ONLY decoration
(e.g. `advance_item`'s `dispatch`, `create_item`'s `schemaMatch`/`expectedNotes`,
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
  contendedItemId present → option: skip this item, pick another from items_read(select="next")
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

**Output omission.** MCP tool responses (`advance_item` transitions) omit the `verification` object
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
