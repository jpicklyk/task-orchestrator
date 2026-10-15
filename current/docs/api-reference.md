# MCP Task Orchestrator 4.0 - API Reference

## Overview

The server exposes a small set of MCP tools organized around a single **WorkItem** graph model. Every
entity, whether a project, feature, or task, is a WorkItem with a `role` (queue, work, review, blocked,
terminal; absent for a lifecycle-free item), optional `parentId`, a `type` field that selects a work-item schema
(lifecycle mode + required notes), optional `tags` for categorization, and optional `traits` that compose
additional note requirements. Notes are first-class keyed documents attached to items. Dependencies
link items with typed blocking or relational edges. Every tool answers the same
[response envelope](#response-envelope) and is served over MCP and over REST `POST /api/v1/ops/{tool}`.

**How this reference is built.** Behavior, semantics and examples are hand-written prose. Request fields (type,
required flag, description) and the error codes of each select or op are generated from the tool registry: each
tool section ends with a block between `<!-- generated:tool NAME -->` markers, and the
[error catalog](#error-catalog) is a generated block too. Never edit a generated block. Change the request
class `@Doc` (or the op's profile, capability or errors) and run `./gradlew :current:regenerateApiReference`;
`ApiReferenceFreshnessTest` fails when a block is stale and names that task. The same data is served at runtime
by a `describe: true` call on any tool. A write element also takes `op` and an optional `idempotencyKey`, and a select call takes
`select` and `describe`, and any call may carry a top-level `seatToken` (a run's seat or coordinator token, see [Runs](#category-runs)); the dispatcher reads those first, so the generated tables do not list them. No call takes
an `actor`: the caller is the principal its transport established (the bearer credential over REST and HTTP `/mcp`,
the `local` principal over stdio and in the unauthenticated modes), and a body `actor` is `unknown_parameter`. A call
with no principal is `unauthenticated` before anything in it is read.

**See also:** [`search-and-discovery.md`](./search-and-discovery.md) for the architecture behind
FTS5 search (two-tokenizer design, RRF fusion, scope filtering, backlinks, score interpretation).

**Server capabilities:** the `initialize` result advertises `tools` (`listChanged`) and `logging`
only; there are no `prompts` or `resources` capabilities, since the server registers neither. `logging` is still
declared, but no tool path sends `notifications/message` any more: failures are reported in the response
envelope and the server log.

## Tool profiles

The server builds one MCP `Server` per tool profile, so a client sees only the selects and ops of its profile (a hidden
select or op looks absent: `invalid_request`). Each select and op declares the set of profiles it appears in; the
generated tables below name that set, and an empty set ("REST only") is served by `POST /api/v1/ops/{tool}` and never
over MCP.

| Profile | HTTP path | stdio | Tools |
|---|---|---|---|
| `orchestration` | `/mcp` | `MCP_TOOL_PROFILE=orchestration` (the default) | every 4.0 tool except the director-only `report`, with all its selects and ops, including `advance` and `config` |
| `knowledge` | `/mcp/knowledge` | `MCP_TOOL_PROFILE=knowledge` | `items_read` (no `context`, `next`, `blocked`), `items_write` (no `create_tree`, `complete_tree`, `repin`), `notes_read`, `notes_write`, `deps_read`, `deps_write`, `docs_read`, `docs_write` |
| `director` | `/mcp/director` | `MCP_TOOL_PROFILE=director` | `items_read` (`get`, `list`, `overview`, `blocked` only), `deps_read`, `report`, `runs_read` (`get`, `list`, `frontier`, `decisions`, `decision`) and `runs_write` (`open`, `close`, `pause`, `resume`, `ack`, `answer`, `withdraw` only): no read that can return a note, document or transition-summary body, and no other write |

`/mcp/knowledge` and `/mcp/director` are exact and case-sensitive; any other `/mcp/<x>` is `404`. A session keeps the
profile it was initialised with. `MCP_TOOL_PROFILE` is validated on both transports (an unknown value fails startup) and
applied to stdio only. `POST /api/v1/ops/{tool}` serves every registered select and op whatever its profile set,
REST-only members included. The `runs_read` and `runs_write` tool descriptions are shared by the profiles, so a description can name an op the connected profile lacks; the table above and the per-op blocks under [Runs](#category-runs) name the profiles of each op. The `initialize` `instructions` is one sentence naming the profile and `describe`. Every 4.0 MCP call's `call_log.request_shape` carries `"profile": "<wire>"`.

## Tool Categories

| Tool | Category | R/W | Description |
|---|---|---|---|
| `items_write` | Hierarchy & CRUD | Write | Create, update, or delete WorkItems, create a whole tree, complete one, or repin an item (`ops[]`, per-element results) |
| `items_read` | Hierarchy & CRUD | Read | Get one item, list a filtered page, read a note schema, search items and notes, summarize the hierarchy, or find an item's relatives; orchestration profile adds `context` (gate state or health view) and `next` (ranked pick-up candidates), and orchestration and director add `blocked` (4.0 envelope) |
| `notes_write` | Notes | Write | Upsert or delete notes on work items, in batches, with version guards (4.0 envelope) |
| `notes_read` | Notes | Read | Get one note or list an item's notes (4.0 envelope) |
| `deps_write` | Dependencies | Write | Create or delete dependency edges, single or by pattern (linear, fan_out, fan_in) (4.0 envelope) |
| `deps_read` | Dependencies | Read | List an item's edges by direction, or walk its blocking graph in topological order (4.0 envelope) |
| `docs_write` | Documents | Write | Upsert, archive, unarchive, delete a root's versioned documents; attach or detach items (4.0 envelope) |
| `docs_read` | Documents | Read | Get a document or one version, list, history and links (4.0 envelope) |
| `advance` | Workflow | Write | Phase transitions (cascade and gate enforcement) and claim ownership: ops `transition`, `claim`, `release`, `heartbeat` |
| `config` | System | Write/Read | Per-root config (get, history, push, validate, rollback, reset), the effective resolved view, and rule text: ops of one 4.0 tool, orchestration profile only |
| `report` | Reports | Read | Aggregate reports: `usage` (call-log calls, errors, bytes, tokens, latency, retries and replays per group over a window), `gates` (rejected transitions per note key, type, trait or error code), `contention` (claim and lease acquire, reject, expire and release counts per resource) and `waste` (redundant reads, over-fetch, reads with no later write, empty `next`, related follow-through and lifecycle-free rejections as ratios) and `rollup` (one subtree or one run: items per role, blocked, transitions, notes written and call cost, plus a run's decisions, seats, attempts and budget); director profile only on MCP |
| `runs_read` | Runs | Read | Get one run, list runs (newest first; `status`, `rootId`, `endedSince`, `unmarked`) or read a run's frontier (ready, in-flight and waiting members); orchestration and director profiles (`seats`: orchestration only) |
| `runs_write` | Runs | Write | Open, update, close, pause, resume and mark runs: ops of one 4.0 tool; `mark` is REST only |

---

## Category: Hierarchy & CRUD

### items_write

**Purpose.** Create, update and delete WorkItems, create a whole tree, complete one, or repin an item. One `ops[]`
array of flat objects, each naming its op in `op` (`create`, `update`, `delete`, and `create_tree`, `complete_tree`
and `repin` in the orchestration profile only; the knowledge profile neither lists nor accepts them). It is the 4.0
replacement of `manage_items`, `create_work_tree` and `complete_tree`, and the same call is served over MCP and
as `POST /api/v1/ops/items_write` (the REST routes `POST /items`, `PATCH /items/{id}` and `DELETE /items/{id}` are
gone). The request and response follow the batch envelope: `{ "ops": [...], "atomic"?: bool }`
returns `data.results[i]` (`{ok:true, ...}` or `{ok:false, error}`), a `data.summary`, and `meta.reqId`. By default
every element is its own write unit (one failing element does not stop the others, and the call answers
`partial_failure`); `atomic: true` runs the whole array in one unit and the first failure rolls everything back.
An element may carry an `idempotencyKey`: a repeated key replays the stored result with
`replayed: true`; the same key with a different element is `idempotency_mismatch`.

Placement (depth, root) is computed from the parent row inside the write unit; an item is created in `queue`, or
lifecycle-free (no role) when its type's base schema is `lifecycle: none`, and there is no role field (use
`advance`). A `terminal` parent under `auto` lifecycle accepts no new children and no reparent onto it
(`invalid_transition`). See `config-reference.md` "Lifecycle-free items".

| Op | Result (`results[i]`) |
|---|---|
| `create` | `{ok, id, version}` |
| `update` | `{ok, id, version, cascade?}` |
| `delete` | `{ok, id, deleted, cascade?}` (`deleted` counts the item and its descendants) |
| `create_tree` | `{ok, id, version, items, deps, notes}` |
| `complete_tree` | `{ok, id?, trigger, items, summary}` |
| `repin` | `{ok, id, version, repinned, schemaVersion?}` |

**Update semantics.** An absent or `null` field keeps its stored value. `tags: []` and `traits: []` clear those
lists. `clear` lists optional fields to set to null: `description`, `statusLabel`, `metadata`, `complexity`,
`properties`, `parentId` (the last moves the item to root). A field cannot be both sent and cleared in one element.
A move to another root also restamps the root of the links sourced from the notes of the moved item and of its
descendants, so deleting the old root later does not remove them (a reference into the old root is not re-resolved;
it goes dangling if that root's document is deleted). Document-sourced links keep their document's root.
`expectedVersion` is the optimistic lock: when given, a stale value fails `version_conflict` (`kind`, `id`,
`expected`, `actual`) and nothing is written; when absent the update locks against the version it just read.
`type` cannot be cleared; a changed type is checked against the destination root's declared types.

**Delete semantics.** A parent without `recursive: true` fails `invalid_request` with a violation on `recursive`
whose `received` is the child count; nothing is deleted. `recursive: true` deletes the whole subtree, deepest first,
in one unit. Notes and dependency edges go with their items, as do the links the removed notes and items were the source of and the resolved links into the removed items (an `adopted_by` link is kept). The documents anchored to a removed item (by root, archived and adopted ones included) are deleted too, with the same link cleanup as `docs_write` `delete`, each recording `document.deleted` before that item's `item.deleted`. `expectedVersion` guards the item itself. Surviving items pinned to a deleted root's config are re-pinned first; a re-pin that would change a survivor's lifecycle class fails `invalid_transition` (`trigger` `repin`, `itemId` the survivor) and nothing is deleted.

**Pins.** Every item is pinned to the config it resolves against: the server and root config versions that were the
heads when the pin was taken, and the base schema (key and layer) its type resolved to under them. Its schema,
traits, seats, dispatch and resource requirements come from the pinned versions until a pin point fires; a later
config push, rollback or reset changes nothing for it (status labels, note limits, independence and the resource
registry always follow the heads). The pin points are: `create`; an `update` that changes `type`, moves the item to
another root, or changes `traits` (one `item.pinned` event; reason precedence `type_change` > `reparent` >
`trait_change`); a transition out of `terminal` (`reopen`, and the reopen cascade); and `repin`. A title-only
update or a move within the same root leaves the pin alone. See `config-reference.md` "Schema pins".

**Lifecycle flips.** Only a `type` change and `repin` may move an item between role-bearing and lifecycle-free.
Into lifecycle-free: from `queue` or `terminal` only, and only with no `blocks` edge in either direction; the role,
previous role, status label and claim are cleared. Out of lifecycle-free: the item enters `queue`, under the same
closed-parent rule as `create`. A flip records `item.updated` (fields include `role`) and `item.pinned`, and writes
no transition and runs no cascade. Any other crossing fails `invalid_transition` (`detail: {itemId, fromRole, trigger,
allowed: []}`, `fromRole` the role or `none`, `trigger` `type_change` or `repin`) and writes nothing. A `reparent`
(the item or any descendant moving with it), `trait_change` or `reopen` whose new pin would change an item's lifecycle
class fails the same way with that `trigger`.

**Repin.** `repin` moves the item's pin to the current heads and the base schema its type resolves to now. When the
recomputed pin equals the stored one nothing is written and no event is recorded (`repinned: false`, `version`
unchanged); otherwise the item's `version` goes up by one and one `item.pinned` event (reason `repin`) is recorded
(`repinned: true`). `expectedVersion` guards it like `update`. `schemaVersion` is the item's new pin view (see
[items_read](#items_read)). A repin may flip the item's lifecycle class (see Lifecycle flips above). Errors:
`not_found`, `ambiguous_id`, `version_conflict`, `invalid_transition` (a flip the rule forbids), `forbidden`,
`unavailable`.

**Cascade.** A reparent away from, or a delete under, a parent re-evaluates that old parent like a child
completion. `cascade` (omitted when empty) lists `{itemId, from, to, applied, reason?}`; `reason` is the catalog code
that blocked an unapplied cascade (`not_claim_holder`, `seat_forbidden`, `gate_blocked`, `resource_unavailable`, `dependency_unmet`, `invalid_transition`).

**Errors.** `invalid_request` / `unknown_parameter` (decoding; pointers like `/ops/0/type`), `not_found`,
`ambiguous_id` (an 8-hex prefix matching several items), `version_conflict`, `cycle_detected` (reparent under a
descendant), `schema_violation` (undeclared or blank `type`, undeclared trait; `detail.availableTypes` /
`availableTraits`), `invalid_transition` (closed parent), `forbidden` (scope), `unavailable` (a per-root config fault:
kind `shedding`, `retryAfterMs`). A `properties` object carrying a `traits` key is `invalid_request` on `properties`
(`traits` is its own field).

**Scope.** Every item an element touches is checked against the caller's scope before any write: the target of
`update` and `delete`, a `parentId` (create) or the new parent (a reparent). A root create is `forbidden` under a root
scope (the new id cannot be a listed root) and, under a tag scope, needs a listed tag in `tags`; `clear: ["parentId"]`
under a root scope is allowed only when the item itself is a listed root. Under a restricted scope an id that
resolves to nothing the caller may see is `forbidden` (scope is no existence oracle), and an ambiguous prefix lists only
the candidates the scope admits (the shared `items_read` "Ids" rule, Q32).

**Actor and events.** The caller's principal (`api:<id>` for a credential, `local` for the local principal) is recorded as the
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
replaces the 3.x `create_work_tree` tool. Orchestration profile only; capability `write-items`. Field semantics:

- `root` (required): The root. Create mode: `title` and `type` required, plus any `create` field except `parentId` (`priority`, `tags` and `traits` arrays, `summary`, `description`, `statusLabel`, `complexity`, `requiresVerification`, `metadata`, `properties`) and `noteAnchors`. Attach mode: `id` (UUID or 8+ hex prefix) names an existing item that becomes the root; no root row is written and the other root fields except `noteAnchors` are ignored.
- `parentId`: Create mode only: the new root's parent (root depth = parent depth + 1). Not allowed with `root.id`.
- `children`: `[{ref, title, type, parentRef?, ...}]`: `ref` is a unique local name (`root` is reserved), `parentRef` the parent's ref (default `root`), plus the same optional fields and `noteAnchors` as the root. Any order: children are written parent before child (`TopoOrder` over the `parentRef` edges, ties in request order).
- `deps`: `[{from, to, type?, unblockAt?}]` between refs (`root` included): `type` `blocks` (default) or `relates_to`, `unblockAt` `queue`, `work`, `review` or `terminal` (not with `relates_to`).
- `notes`: `[{itemRef, key, role, body?}]`: `role` `queue`, `work` or `review`.
- `createNotes`: `true`: a blank note for every key of each item's resolved schema that no explicit note or anchor writes.
- `docRef`: `{slug, rootId?}`: the PENDING plan document `noteAnchors` cut from; `rootId` defaults to (and must equal) the tree's own project root. A `rule/` slug is rejected.
- `noteAnchors` (on `root` and each child): `[{noteKey, role, anchor}]`: the document section under the heading slugged `anchor` becomes that item's note. Requires `docRef`.

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
tool. Orchestration profile only; capability `advance`. Field semantics:

- `rootId`: The descendants of this item (lifecycle-free ones left out), then the item itself last (`includeRoot`, default `true`).
- `itemIds`: Exactly these items. Exactly one of `rootId` and `itemIds`; an empty list is `invalid_request`.
- `trigger`: `complete` (default) or `cancel` (bypasses the note gate, not ownership).
- `includeRoot`: With `rootId`: also the root.

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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool items_write -->
Create, update and delete work items or whole trees, one ops[] element each.

**`items_write` op `create`** (knowledge and orchestration profiles; capability `write-items`): Create an item in queue.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `title` | string | yes | Item title. |
| `type` | string | yes | Declared item type; required on create. |
| `parentId` | string | no | Parent id; omit for a root. Update moves the item. |
| `description` | string | no | Long description. |
| `summary` | string | no | Short summary. |
| `statusLabel` | string | no | Free-form status label. |
| `priority` | string: high \| medium \| low | no | Create defaults to medium. |
| `complexity` | integer | no | 1 to 10. |
| `requiresVerification` | boolean | no | Completion needs verification. |
| `metadata` | string | no | Free-form metadata text. |
| `tags` | array of string | no | Lowercase, hyphenated. Update: [] clears. |
| `traits` | array of string | no | Declared trait names. Update: [] clears. |
| `properties` | object | no | Free-form JSON object without a traits key. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_found`, `partial_failure`, `schema_violation`, `unavailable`, `unknown_parameter`

**`items_write` op `update`** (knowledge and orchestration profiles; capability `write-items`): Update an item; absent or null keeps a value, clear nulls optional fields.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | yes | Item UUID or 8+ hex prefix. |
| `expectedVersion` | integer | no | Fails version_conflict if the item is at another version. |
| `title` | string | no | Item title. |
| `type` | string | no | Declared item type; required on create. |
| `parentId` | string | no | Parent id; omit for a root. Update moves the item. |
| `description` | string | no | Long description. |
| `summary` | string | no | Short summary. |
| `statusLabel` | string | no | Free-form status label. |
| `priority` | string: high \| medium \| low | no | Create defaults to medium. |
| `complexity` | integer | no | 1 to 10. |
| `requiresVerification` | boolean | no | Completion needs verification. |
| `metadata` | string | no | Free-form metadata text. |
| `tags` | array of string | no | Lowercase, hyphenated. Update: [] clears. |
| `traits` | array of string | no | Declared trait names. Update: [] clears. |
| `properties` | object | no | Free-form JSON object without a traits key. |
| `clear` | array of string: description \| statusLabel \| metadata \| complexity \| properties \| parentId | no | Update only: optional fields to set to null. |

Errors: `ambiguous_id`, `cycle_detected`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_claim_holder`, `not_found`, `partial_failure`, `schema_violation`, `unauthenticated`, `unavailable`, `unknown_parameter`, `version_conflict`

**`items_write` op `delete`** (knowledge and orchestration profiles; capability `write-items`; destructive): Delete an item; a parent needs recursive.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | yes | Item UUID or 8+ hex prefix. |
| `recursive` | boolean | no | Delete the subtree; required when the item has children. |
| `expectedVersion` | integer | no | Fails version_conflict if the item is at another version. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`, `version_conflict`

**`items_write` op `create_tree`** (orchestration profile only; capability `write-items`): Create a root (or attach to root.id) with children, dependencies and notes, all or nothing.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `root` | object | yes | Tree root: id attaches, else title and type create. |
| `root.id` | string | no | Item to attach to. Not with parentId. |
| `root.title` | string | no | Title; required without id. |
| `root.type` | string | no | Declared type; required without id. |
| `root.priority` | string: high \| medium \| low | no | Create defaults to medium. |
| `root.tags` | array of string | no | Lowercase, hyphenated. Update: [] clears. |
| `root.traits` | array of string | no | Declared trait names. Update: [] clears. |
| `root.summary` | string | no | Short summary. |
| `root.description` | string | no | Long description. |
| `root.statusLabel` | string | no | Free-form status label. |
| `root.complexity` | integer | no | 1 to 10. |
| `root.requiresVerification` | boolean | no | Completion needs verification. |
| `root.metadata` | string | no | Free-form metadata text. |
| `root.properties` | object | no | Free-form JSON object without a traits key. |
| `root.noteAnchors` | array of object | no | Notes cut from docRef sections. |
| `root.noteAnchors[].noteKey` | string | yes | Note key. |
| `root.noteAnchors[].role` | string: queue \| work \| review | yes | Note role. |
| `root.noteAnchors[].anchor` | string | yes | Heading to cut. |
| `parentId` | string | no | Parent id; omit for a root. Update moves the item. |
| `children` | array of object | no | Child items, any order; nest with parentRef. |
| `children[].ref` | string | yes | Unique ref; not root. |
| `children[].title` | string | yes | Item title. |
| `children[].type` | string | yes | Declared item type. |
| `children[].parentRef` | string | no | Parent ref; default root. |
| `children[].priority` | string: high \| medium \| low | no | Create defaults to medium. |
| `children[].tags` | array of string | no | Lowercase, hyphenated. Update: [] clears. |
| `children[].traits` | array of string | no | Declared trait names. Update: [] clears. |
| `children[].summary` | string | no | Short summary. |
| `children[].description` | string | no | Long description. |
| `children[].statusLabel` | string | no | Free-form status label. |
| `children[].complexity` | integer | no | 1 to 10. |
| `children[].requiresVerification` | boolean | no | Completion needs verification. |
| `children[].metadata` | string | no | Free-form metadata text. |
| `children[].properties` | object | no | Free-form JSON object without a traits key. |
| `children[].noteAnchors` | array of object | no | Notes cut from docRef sections. |
| `children[].noteAnchors[].noteKey` | string | yes | Note key. |
| `children[].noteAnchors[].role` | string: queue \| work \| review | yes | Note role. |
| `children[].noteAnchors[].anchor` | string | yes | Heading to cut. |
| `deps` | array of object | no | Dependencies between refs. |
| `deps[].from` | string | yes | From ref (the blocker). |
| `deps[].to` | string | yes | Target ref. |
| `deps[].type` | string: blocks \| relates_to | no | blocks (default) or relates_to. |
| `deps[].unblockAt` | string: queue \| work \| review \| terminal | no | Unblocks at this role; default terminal. |
| `notes` | array of object | no | Notes; win over noteAnchors and createNotes. |
| `notes[].itemRef` | string | yes | Item ref. |
| `notes[].key` | string | yes | Note key. |
| `notes[].role` | string: queue \| work \| review | yes | Note role. |
| `notes[].body` | string | no | Note text. |
| `createNotes` | boolean | no | true: blank notes for unwritten schema keys. |
| `docRef` | object | no | Plan document for noteAnchors; root adopts it. |
| `docRef.slug` | string | yes | Slug (not rule/). |
| `docRef.rootId` | string | no | Must be the tree's root (default). |

Errors: `ambiguous_id`, `cycle_detected`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_claim_holder`, `not_found`, `note_too_long`, `partial_failure`, `payload_too_large`, `schema_violation`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`items_write` op `complete_tree`** (orchestration profile only; capability `advance`): Complete or cancel a tree's descendants (rootId) or listed items (itemIds) in dependency order; per-item outcomes.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Complete its descendants. Exactly one of rootId, itemIds. |
| `itemIds` | array of string | no | Complete exactly these items. |
| `trigger` | string: complete \| cancel | no | complete (default) or cancel. |
| `includeRoot` | boolean | no | With rootId: the root too, last. Default true. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`items_write` op `repin`** (orchestration profile only; capability `write-items`): Move the item's config pin to the current config heads.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | yes | Item UUID or 8+ hex prefix. |
| `expectedVersion` | integer | no | Fails version_conflict if the item is at another version. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`
<!-- /generated:tool items_write -->

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

**Selects.** `get`, `list` and `overview` (the knowledge, orchestration and director profiles), `schema`, `search`
and `related` (knowledge and orchestration; the director profile neither lists nor accepts them, since they can return
note bodies), `blocked` (orchestration and director), and `context`, `next` and `history` (orchestration only). A select a
profile does not carry is neither listed nor accepted. Every select
needs the `READ` capability and honors the root and tag scope of the caller.

| Select | Answer (`data`) |
|---|---|
| `get` | the full item view; with `ifVersion` equal to the stored version, `{unchanged: true, version}` |
| `list` | `{items: [compact view], page}` |
| `schema` | the resolved schema view, or `{schemaFree: true}` |
| `search` | `{items: [hit], page}` |
| `overview` | global `{items, page}`; scoped `{item, childCounts, items, page}`; anchored `{anchor: {id, title}, items, page}` |
| `related` | `{items: [hit], page}` |
| `context` | with `itemId`: the gate state of one item; without: `{active, blocked, stalled, claimSummary}` |
| `next` | `{items: [compact view], page, reason?}` |
| `blocked` | `{items: [compact view + blockType + blockers], page}` |
| `history` | `{items: [transition entry], page}` |

**Ids.** Every id field takes a full UUID or 8 to 32 hex characters of one. A prefix that matches more than
one item is `ambiguous_id`; no match is `not_found`. Under a restricted scope an id that matches nothing
the caller may see is `forbidden`, never `not_found`, so scope is no existence oracle, and an ambiguous
prefix lists only the candidates the scope admits. Since Q32 every tool resolves ids by this one rule (`deps_*`,
`items_write`, `advance`, `notes_*`, `docs_*`), so no tool is an existence oracle under a restricted scope. A target id (`get`, `schema`, `related`, the overview
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

`childCounts` is `{role: n}` with zero roles omitted; lifecycle-free items count under no role (they are not folded
into `queue`), here and in the anchored roll-ups. `excludeTerminal` drops terminal items that have no
open work below them; a terminal item with a non-terminal descendant is kept, a lifecycle-free descendant is not open
work, and a lifecycle-free item itself is always kept. Under a restricted scope
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
(zeros kept), and each returned hit records its id and version as a target version. The row's `hit_signals`
column lists the returned page hit by hit, in page order, as `[{id, kind, signals}]` (`kind` is `item` or
`document`, `signals` the signals that found it in the order edges, links, tags, text; never a title or any
text), so a later MCP call in the same session that targets one of those ids can be attributed to the signals that
found it. Exclude later `related` and `search` calls from that join (`c.operation NOT IN ('related','search')`),
since they record their own hits as target versions. Follow-through is effectively MCP-only: REST call_log rows
carry no `session_id`. It is NULL for an empty page and for every other call.

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
no `origin`. `actor` `{id, kind, parent?}` and `verification` `{proofStatus, verifier?, reason?}` appear only when the
caller may see attribution (the `notes_read` rule); the proof hash and the verified claims are never returned.
Cascades: a live cascade row renders the caller's `actor` with `origin: cascade`; a backfilled 3.x cascade row
renders no `actor` and no `origin`, only `trigger: cascade`.
Known limit: without `itemId`, a root scope matches the event's stored root only, so a token scoped to a mid-tree
(non-root) ancestor gets an empty global history while its per-item `history` reads succeed. It under-returns and
never leaks another root's rows.

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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool items_read -->
Read work items. Selects: get (one item), list (a filtered page), schema (the note schema of a type or an item), search (full text over item, note and document content), overview (a hierarchy summary), related (items and documents related to an item by dependencies, links, shared tags and similar text).

**`items_read` select `get`** (knowledge, orchestration and director profiles; capability `read`): One item by id: the full view; fields adds timestamps or ancestors; ifVersion short-circuits an unchanged item.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |
| `ifVersion` | integer | no | Answer {unchanged: true, version} instead of the item when its version equals this value. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `list`** (knowledge, orchestration and director profiles; capability `read`): A filtered, sorted page of the items the caller may see, as compact views with an exact total.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `parentId` | string | no | Only direct children of this item (UUID or 8-32 hex). |
| `ancestorId` | string | no | Only this item's subtree, itself included (UUID or 8-32 hex). |
| `depth` | integer | no | Only items at this depth (0 = roots). |
| `role` | string: queue \| work \| review \| blocked \| terminal | no | Only items (search: content of items) in this role. |
| `priority` | string: high \| medium \| low | no | Only items with this priority. |
| `tags` | array of string | no | Only items with at least one of these tags (case-insensitive). |
| `traits` | array of string | no | Only items with at least one of these traits (exact). |
| `type` | string | no | list: only items of this type. schema: the type whose schema to return. |
| `claimStatus` | string: claimed \| unclaimed \| expired | no | Only items in this claim state (expired: TTL elapsed). |
| `createdAfter` | string (date-time) | no | Created at or after (ISO 8601). |
| `createdBefore` | string (date-time) | no | Created at or before (ISO 8601). |
| `modifiedAfter` | string (date-time) | no | Modified at or after (ISO 8601). |
| `modifiedBefore` | string (date-time) | no | Modified at or before (ISO 8601). |
| `roleChangedAfter` | string (date-time) | no | Role changed at or after (ISO 8601). |
| `roleChangedBefore` | string (date-time) | no | Role changed at or before (ISO 8601). |
| `sort` | string: title \| priority \| complexity \| createdAt \| modifiedAt | no | Sort field; default createdAt. |
| `order` | string: asc \| desc | no | Sort direction; default desc. |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `schema`** (knowledge and orchestration profiles; capability `read`): The note schema of a type or of an item; schemaFree when none matches; guidance only with fields.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `type` | string | no | list: only items of this type. schema: the type whose schema to return. |
| `itemId` | string | no | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `rootId` | string | no | With type: the project root whose config resolves it (UUID or 8-32 hex). |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `search`** (knowledge and orchestration profiles; capability `read`): Ranked full-text hits over item content, note bodies and documents the caller may see.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `query` | string | yes | Search terms, all must match; matched literally. |
| `kinds` | array of string: item \| note \| document | no | item (title, summary, description), note (bodies) and/or document (title, body); default every kind the filters apply to. role, tags and traits apply to item and note only: with any of them the default is item and note, and naming document too is invalid. |
| `matchMode` | string: auto \| substring \| text | no | auto (default: both, fused), substring (terms of 3+ characters), text (stemmed). |
| `itemId` | string | no | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `ancestorId` | string | no | Only this item's subtree, itself included (UUID or 8-32 hex). |
| `role` | string: queue \| work \| review \| blocked \| terminal | no | Only items (search: content of items) in this role. |
| `tags` | array of string | no | Only items with at least one of these tags (case-insensitive). |
| `traits` | array of string | no | Only items with at least one of these traits (exact). |
| `snippet` | boolean | no | Add a highlighted snippet to each hit; default true. |
| `explain` | boolean | no | Add ranking detail to each hit (search: per-analyzer ranks; related: per-signal ranks); default false. |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `overview`** (knowledge, orchestration and director profiles; capability `read`): A hierarchy summary: global, one item's children (itemId) or an anchor's children (anchorId).

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | no | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `anchorId` | string | no | List this item's children with subtree role counts (UUID or 8-32 hex). |
| `excludeTerminal` | boolean | no | Drop terminal items without open work below; default false. |
| `includeChildren` | boolean | no | Global only: add each item's children; default false. |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `related`** (knowledge and orchestration profiles; capability `read`): Items and documents related to an item, ranked by dependencies, links, shared tags and similar text; each hit names its reasons.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `acrossRoots` | boolean | no | Look beyond the item's own root (every signal); default false. The caller's scope still applies. |
| `explain` | boolean | no | Add ranking detail to each hit (search: per-analyzer ranks; related: per-signal ranks); default false. |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `context`** (orchestration profile only; capability `read`): With itemId: the gate state of one item (canAdvance, blockedBy, blockers, missing notes, next, transitions, dispatch, claimDetail, decisions). Without: a health view of active, blocked and stalled items and the claim summary (default limit 50, max 200; ancestorId scopes it). fields: ancestors.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | no | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `ancestorId` | string | no | Only this item's subtree, itself included (UUID or 8-32 hex). |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `next`** (orchestration profile only; capability `read`): One page of the items ready to pick up, ranked (default limit 1, max 200): in the role (default queue; not terminal), unclaimed, not under a claim by another agent, no unsatisfied blocker. includeClaimed lists claimed items too. Empty with reason claim_held or dependency_unmet when matches exist but none is ready. fields: summary, ancestors.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `parentId` | string | no | Only direct children of this item (UUID or 8-32 hex). |
| `ancestorId` | string | no | Only this item's subtree, itself included (UUID or 8-32 hex). |
| `role` | string: queue \| work \| review \| blocked \| terminal | no | Only items (search: content of items) in this role. |
| `priority` | string: high \| medium \| low | no | Only items with this priority. |
| `tags` | array of string | no | Only items with at least one of these tags (case-insensitive). |
| `type` | string | no | list: only items of this type. schema: the type whose schema to return. |
| `complexityMax` | integer | no | Only items with a complexity of at most this (1-10); items without one are excluded. |
| `createdAfter` | string (date-time) | no | Created at or after (ISO 8601). |
| `createdBefore` | string (date-time) | no | Created at or before (ISO 8601). |
| `modifiedAfter` | string (date-time) | no | Modified at or after (ISO 8601). |
| `modifiedBefore` | string (date-time) | no | Modified at or before (ISO 8601). |
| `roleChangedAfter` | string (date-time) | no | Role changed at or after (ISO 8601). |
| `roleChangedBefore` | string (date-time) | no | Role changed at or before (ISO 8601). |
| `orderBy` | string: priority \| oldest \| newest | no | Ranking: priority (default: high first, then lower complexity), oldest or newest first. |
| `includeClaimed` | boolean | no | Also list items under an active claim, each with isClaimed; default false. |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `blocked`** (orchestration and director profiles; capability `read`): A page of the blocked items (default limit 50, max 200): role blocked (blockType explicit) or open with an unsatisfied blocking dependency (dependency), each with its blockers (id, title, role, threshold, satisfied). fields: summary, ancestors.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `parentId` | string | no | Only direct children of this item (UUID or 8-32 hex). |
| `ancestorId` | string | no | Only this item's subtree, itself included (UUID or 8-32 hex). |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |
| `fields` | array of string | no | Extra members. get: createdAt, modifiedAt, roleChangedAt, ancestors. list: the same plus summary. schema: guidance. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`items_read` select `history`** (orchestration profile only; capability `read`): The transition audit trail, newest first (default limit 50, max 200): itemId limits it to one item, otherwise every transition the caller may see; since/until bound the time. Each entry has seq, id, itemId, occurredAt, trigger, fromRole, toRole, status labels, origin, summary, consumedCredentials, backfilled (a pre-4.0 row) and, when attribution is visible, actor and verification.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | no | Item id (UUID or 8-32 hex). get, schema: the item. search: only its own content. overview: list its children. related: the item to find relatives of. |
| `since` | string (date-time) | no | Only transitions at or after this time (ISO 8601). |
| `until` | string (date-time) | no | Only transitions at or before this time (ISO 8601). |
| `limit` | integer | no | Page size. list, overview: default 50, max 200. search: default 20, max 100. related: default 10, max 50. |
| `cursor` | string | no | The page.next of the previous page; absent for the first page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool items_read -->

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

| Select | Answer (`data`) |
|---|---|
| `get` | `{id, key, role, version, body, modifiedAt, actor?, verification?}`; with `ifVersion` equal to the current version, `{unchanged: true, version}` and no body |
| `list` | `{items: [{id, key, role, version, bodyLength, modifiedAt, actor?, verification?}], page: {total, truncated, next?}}` ordered by key then id |

- `itemId` is a full UUID or 8 to 32 hex characters of one (`ambiguous_id` when a prefix matches more
  than one item). A missing note is `not_found` with id `<itemUuid>/<key>`.
- `list` entries carry an 8-hex-character short `id` and never a body; read a body with `get`. An empty
  `keys` list matches nothing.
- Paging follows the envelope's list rules (amendment U22): `page.total` is the full count on every
  page, `truncated` is true exactly when notes exist after this page, and `next` (an opaque cursor) is
  present exactly when `truncated`, so following `next` until it is absent always terminates.
- `actor` (`{id, kind, parent?}`) and `verification` (`{proofStatus, verifier?, reason?}`) appear only when
  the caller may see attribution: on every transport, a principal with `ADMIN`, or any principal when
  `API_REDACT_NOTE_ATTRIBUTION=false`. Proof evidence (the proof hash and verified claims) is never
  returned.

```json
{ "select": "get", "itemId": "550e8400", "key": "feature-summary" }
{ "select": "list", "itemId": "550e8400-e29b-41d4-a716-446655440001", "role": "queue", "limit": 20 }
```

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool notes_read -->
Read the notes on work items: get one note with its body, or list an item's notes without bodies.

**`notes_read` select `get`** (knowledge and orchestration profiles; capability `read`): Read one note of an item by key, with its body. With ifVersion equal to the note's current version the answer is {unchanged: true, version} and no body.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Id of the work item that owns the note: a full UUID or 8 to 32 hex characters of one. |
| `key` | string | yes | Note key, unique per item (1 to 200 characters, case-sensitive). |
| `ifVersion` | integer | no | The version the caller already holds (at least 1). When it equals the current version the answer is {unchanged: true, version} with no body. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`notes_read` select `list`** (knowledge and orchestration profiles; capability `read`): List the notes of an item without their bodies (id, key, role, version, bodyLength, modifiedAt), ordered by key. Read a body with get.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Id of the work item that owns the note: a full UUID or 8 to 32 hex characters of one. |
| `role` | string | no | Only notes of this role: queue, work or review (case-insensitive). |
| `keys` | array of string | no | Only notes with one of these keys. An empty list matches nothing. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | Opaque cursor from the previous page's page.next. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool notes_read -->

### notes_write

**Purpose.** Create, replace or delete notes on work items, in batches. Replaces `manage_notes`. A
request is `{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry an `idempotencyKey`.
Every element commits on its own unless `atomic` is true; the answer is a batch result (`results[i]`
with `ok`, and `207 partial_failure` over REST when some elements failed). Both ops need the
`WRITE_NOTES` capability and honor the caller's scope.

| Op | Result (`results[i]`) |
|---|---|
| `upsert` | `{id, version, created?, warning?}` |
| `delete` | `{id}` |

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
- **Attribution.** The note records the call's principal (`api:<id>` for a credential, `local` for the local
  principal) with its verification, on every transport; a body `actor` is `unknown_parameter`. A write under a
  `seatToken` also records that token's seat as the note's attribution seat (`notes.seat`, rewritten on every write
  like the actor, NULL without a token); the independence gate compares the attribution pair (actor, seat). Attribution (`notes.seat`) is separate from ownership (`notes.owner_seat`, below): the first changes on every write, the second only when the owner changes.
- **Ownership.** A note's owner is the principal whose write created it, stored apart from its attribution; a note written before 4.0 has no owner until its first 4.0 `upsert`, which makes the caller the owner and records `note.ownership_transferred` with `from: null` and `legacyActor`. A note created or adopted under a `seatToken` is owned by the pair (principal, seat), stored as `notes.owner_id` and `notes.owner_seat`, which are written only together (create, adoption, takeover; a takeover is never under a token, so it clears the owner seat). Another owner pair's `upsert` or `delete` of an owned, non-`shared` note is `note_owned_by_other` (with `ownerSeat` when the owner is a seat); only an admin `takeover` moves an owned note. `notes_read` shows `owner{id, seat?}` under the attribution rule and omits it for an unowned note.

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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool notes_write -->
Create, replace or delete notes on work items, in batches. Every element commits on its own unless atomic is true.

**`notes_write` op `upsert`** (knowledge and orchestration profiles; capability `write-notes`): Create the note at (item, key) or replace it in place; its version goes up by one on every write. Give exactly one of body and bodyFromFile. A body over the schema's maxLength for the key is accepted with a warning, or rejected, depending on the server's note limits mode. A key the current schema requires but the item's pinned schema lacks is schema_pinned_conflict: repin the item first.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Id of the work item that owns the note: a full UUID or 8 to 32 hex characters of one. |
| `key` | string | yes | Note key, unique per item (1 to 200 characters, case-sensitive). |
| `role` | string | yes | Note role: queue, work or review (case-insensitive). |
| `body` | string | no | Inline note text (may be empty). Exactly one of body and bodyFromFile is required. |
| `bodyFromFile` | string | no | Server-side path whose text becomes the body. Exactly one of body and bodyFromFile is required. Resolved strictly inside the agent config root: no absolute path, no '..', no symlink out of it; a regular UTF-8 file of at most 65536 bytes; CRLF becomes LF. An idempotency replay does not re-read the file. |
| `expectedVersion` | integer | no | Guard on the note's version: omit to write unconditionally, 0 to require that the note does not exist, n to require that its current version is n. Anything else is version_conflict and nothing is written. |
| `takeover` | boolean | no | Admin only: take over a note owned by another principal. Never with a seat token. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `note_owned_by_other`, `note_too_long`, `partial_failure`, `payload_too_large`, `schema_pinned_conflict`, `schema_violation`, `unauthenticated`, `unavailable`, `unknown_parameter`, `version_conflict`

**`notes_write` op `delete`** (knowledge and orchestration profiles; capability `write-notes`; destructive): Delete the note at (item, key). A missing note is not_found.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Id of the work item that owns the note: a full UUID or 8 to 32 hex characters of one. |
| `key` | string | yes | Note key, unique per item (1 to 200 characters, case-sensitive). |
| `expectedVersion` | integer | no | Guard on the note's version: omit to write unconditionally, 0 to require that the note does not exist, n to require that its current version is n. Anything else is version_conflict and nothing is written. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `note_owned_by_other`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`, `version_conflict`
<!-- /generated:tool notes_write -->

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
scope: a subject `item` outside the scope is `forbidden`. Ids resolve by the shared scope-aware rule (Q32,
the `items_read` "Ids" rule): under a restricted scope an unknown full UUID, a prefix that matches nothing and a
prefix that matches only out-of-scope items are all the same `forbidden`, never `not_found`, and an ambiguous
prefix lists only the candidates the scope admits.

| Select | Answer (`data`) |
|---|---|
| `list` | `{items: [{id, from, to, type, unblockAt?}], page: {total, truncated, next?}}` ordered by creation time then id |
| `graph` | `{items: [{id, level} or {id, cyclic: true}], page: {total, truncated, next?, capped?}}` |

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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool deps_read -->
Read dependency edges between items. select: list (edges of one item, with direction and type filters) or graph (the blocking graph around an item). Edge: {id, from, to, type, unblockAt?}; from blocks to.

**`deps_read` select `list`** (knowledge, orchestration and director profiles; capability `read`): List the dependency edges touching one item, oldest first. incoming = what blocks it, outgoing = what it blocks. Edges whose other end is outside the scope of the call are left out and not counted.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `item` | string | yes | Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `direction` | string: incoming \| outgoing \| all | no | incoming = edges whose to is item (what blocks it), outgoing = edges whose from is item. Default all. |
| `type` | string: blocks \| relates_to | no | Only edges of this type. Default both. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | The page.next value of the previous page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`deps_read` select `graph`** (knowledge, orchestration and director profiles; capability `read`): The blocking graph around one item: every item connected to it by blocks edges in either direction, blockers first. level is the longest blocker path from an item with no blocker; cyclic items come last. relates_to edges are ignored. Items outside the scope of the call are neither listed nor traversed. page.capped means the walk stopped at 1000 items.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `item` | string | yes | Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | The page.next value of the previous page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool deps_read -->

### deps_write

**Purpose.** Create or delete dependency edges. Replaces `manage_dependencies`. A request is
`{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry an `idempotencyKey`. Every element
commits on its own unless `atomic` is true; the answer is a batch result (`results[i]` with `ok`, and
`207 partial_failure` over REST when some elements failed). Every op needs the `MANAGE_DEPENDENCIES`
capability (`manage-dependencies`); `delete` is destructive.

| Op | Result (`results[i]`) |
|---|---|
| `create` | `{id, from, to, type, unblockAt?}` |
| `delete` | `{deleted: n}` |
| `linear` | `{created: [edge, ...]}` |
| `fan_out` | `{created: [edge, ...]}` |
| `fan_in` | `{created: [edge, ...]}` |

- `type` is `blocks` (applied when absent on create) or `relates_to`. `unblockAt` is `queue`, `work`,
  `review` or `terminal` (absent means `terminal`) and is rejected on a `relates_to` edge. A self-edge
  is `invalid_request`. The 3.x `is_blocked_by` alias is not accepted: name the blocker as `from`.
- **Lifecycle-free endpoints.** A `blocks` edge with a lifecycle-free item at either end is `invalid_request` (the
  violation names `fromItemId` or `toItemId` with the item's id as `received`); nothing is written. `relates_to` edges
  are allowed. The same rule applies to `items_write` `create_tree` `deps`.
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
- **Attribution.** `dependency.added` and `dependency.removed` events carry the call's principal on every
  transport.

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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool deps_write -->
Create and delete dependency edges between items: create, delete, and the patterns linear, fan_out and fan_in (one element, all edges or none). Each op needs the manage-dependencies capability and sees only items in scope. Result: an edge {id, from, to, type, unblockAt?}, {created: [edge]} or {deleted: n}.

**`deps_write` op `create`** (knowledge and orchestration profiles; capability `manage-dependencies`): Create one dependency edge: from blocks to (type blocks, the default) or relates_to. A duplicate or a cycle is rejected.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `from` | string | yes | Source item of the edge (the blocker for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `to` | string | yes | Target item of the edge (the blocked item for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `type` | string: blocks \| relates_to | no | Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent. |
| `unblockAt` | string: queue \| work \| review \| terminal | no | Role the blocker must reach to unblock the target: queue, work, review or terminal (default terminal). Not allowed with relates_to. |

Errors: `ambiguous_id`, `cycle_detected`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`deps_write` op `delete`** (knowledge and orchestration profiles; capability `manage-dependencies`; destructive): Delete dependency edges. Give exactly one of: id (one edge, full UUID), from + to (with optional type), or item (every edge touching it). An unknown id is not_found; a from/to or item that matches nothing deletes 0.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `id` | string | no | Full dependency UUID (not a prefix). |
| `from` | string | no | Source item of the edge (the blocker for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `to` | string | no | Target item of the edge (the blocked item for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `type` | string: blocks \| relates_to | no | Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent. |
| `item` | string | no | Delete every edge touching this item (either end). Item id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`deps_write` op `linear`** (knowledge and orchestration profiles; capability `manage-dependencies`): Create a chain: each item in ids blocks the next. All edges are created together or none.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `ids` | array of string | yes | Items in chain order, at least 2: each one blocks the next. |
| `type` | string: blocks \| relates_to | no | Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent. |
| `unblockAt` | string: queue \| work \| review \| terminal | no | Role the blocker must reach to unblock the target: queue, work, review or terminal (default terminal). Not allowed with relates_to. |

Errors: `ambiguous_id`, `cycle_detected`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`deps_write` op `fan_out`** (knowledge and orchestration profiles; capability `manage-dependencies`): Create from to each of toIds. All edges are created together or none.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `from` | string | yes | Source item of the edge (the blocker for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `toIds` | array of string | yes | Target items, at least 1: from blocks each of them. |
| `type` | string: blocks \| relates_to | no | Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent. |
| `unblockAt` | string: queue \| work \| review \| terminal | no | Role the blocker must reach to unblock the target: queue, work, review or terminal (default terminal). Not allowed with relates_to. |

Errors: `ambiguous_id`, `cycle_detected`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`deps_write` op `fan_in`** (knowledge and orchestration profiles; capability `manage-dependencies`): Create each of fromIds to the to item. All edges are created together or none.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `fromIds` | array of string | yes | Source items, at least 1: each of them blocks to. |
| `to` | string | yes | Target item of the edge (the blocked item for blocks): a UUID or a hex prefix of 8 to 32 characters. |
| `type` | string: blocks \| relates_to | no | Edge type: blocks or relates_to. Create applies blocks when absent; delete matches either type when absent. |
| `unblockAt` | string: queue \| work \| review \| terminal | no | Role the blocker must reach to unblock the target: queue, work, review or terminal (default terminal). Not allowed with relates_to. |

Errors: `ambiguous_id`, `cycle_detected`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`
<!-- /generated:tool deps_write -->

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
element succeeds or fails alone; true: the first failure rolls the whole call back and is returned with its `error.index`). The
caller is the call's principal on every transport. Each element may
carry an optional `idempotencyKey`; a repeat of the same key and payload replays the stored result, the same
key with another payload is `idempotency_mismatch`. All ops need the `advance` capability.

| Op | Result element (on top of `ok: true`) |
|---|---|
| `transition` | `{itemId, previousRole, newRole, trigger, statusLabel?, cascades?, unblocked?, expectedNotes?, guidanceKey?, skillPointer?, dispatch?, violations?}` |
| `claim` | `{itemId, claimed: true, version, claimedAt, expiresAt, originalClaimedAt, released?, refreshed?, selected?, claimRef?, displaced?}` |
| `release` | `{itemId, released: true}` |
| `heartbeat` | `{itemId, claimed: true, version, claimedAt, expiresAt, originalClaimedAt, released?, refreshed: true}` |

`itemId` is a UUID or a dash-free hex prefix of 8 to 32 characters (`not_found` when nothing matches, `ambiguous_id` with the
candidate ids when several do).

**`transition` result.** `cascades` lists the parents evaluated as a side effect: `{itemId, previousRole, targetRole, applied,
blocked?: ownership | seat | gate | resource | role | dependency, missing?: [{key, role, seat?}], contended?: [resourceKey], blockers?: [{id, role}]}`.
`unblocked` is `[{itemId, title}]`. Under a root- or tag-scoped token, a `cascades` or `unblocked` entry for an item the scope
does not admit is `{itemId}` only (the same rule applies to `complete_tree` items and to `items_write` `cascade` entries).
`expectedNotes` is the list of note keys the new phase expects; `guidanceKey` and
`skillPointer` name the first missing required note's guidance and skill. `dispatch` is `{agent?, model?, effort?}` for the
phase just entered. `violations` is `[{key, seat?, constraint, conflictingSeat?, waived?}]`, present only when the list is
non-empty. Empty and null members are omitted.

**Block and hold.** `block` and `hold` take an optional `reason` (`awaiting_decision`, `dependency`, `resource` or
`manual`, default `manual`). `decisionId` is allowed only with `awaiting_decision`, where it names an open decision on that
item, and is then required. Under a restricted scope a decision is as visible as its root: one outside the scope answers like an unused id (
ames no decision), so under a tag scope a decision whose root lacks a listed tag is hidden even for the caller's own item (the same rule as aise and nswer). The reason and the decision id are stored with the blocked role and cleared by every way out of
it: `resume`, `cancel` and `complete_tree`. A cascade never moves an item out of `blocked` (`blocked: role`), and an answer
to a decision never resumes its item; `runs_write` `raise` with `block: true` blocks the item in the same unit as the raise.
`items_read` `context` and `get` show `blockedReason` and `decisionId` for a blocked item; the order of the fields in a view
is not part of the contract.

**Ownership.** When an item has an active (non-expired) claim, `transition` requires the caller's trusted actor id to equal the
holder, on every trigger and on both MCP and REST; any other caller gets `not_claim_holder`. A claim taken under a
`seatToken` is held by the pair (principal, assignment), so a stdio or local agent that presents a `seatToken` holds
claims as `(local, assignmentId)`: two local agents with different seats are distinct holders for claims, fencing and note
ownership.
An unclaimed item, or one whose claim has lapsed, can be transitioned by anyone. The same fence guards the other writes to a
held item: `notes_write` `upsert`/`delete`, `items_write` `update`/`delete` (a recursive delete over a held descendant is refused
for that descendant) and every `deps_write` op naming it answer `not_claim_holder{itemId}` and write nothing, checked right after
the item lookup and before any version compare. A cascade into a parent another principal holds is not applied (`blocked:
ownership`, see Cascades). A transition that lands the item in `terminal` clears its claim. There is no override: an operator who
must take over a claimed item waits out the claim TTL.

**Claims.** `claim` makes the caller the holder; any other item it held is released (one claim per actor) and listed in
`released` (omitted when none). `version` is the item's row version (claim writes never bump it). The holder claiming
again refreshes the claim (`refreshed: true`, `originalClaimedAt` kept). A claim that takes the item over the lapsed claim of a
different holder pair (principal, assignment) reports `displaced: {assignmentId?, expiredAt, principal?}`: the displaced seat
assignment (omitted for a principal-level holder), when its claim expired, and its principal only for a caller that may see
principals (ADMIN, outside a seat token). The same unit records that claim's one `claim.expired` row with `reason: "displaced"` and
`displacedBy` (the new holder's assignment id, or null), unless an expiry sweep or advance already recorded it (`reason:
"expired"`). Claiming an unclaimed item, or re-claiming the caller's own lapsed claim, reports no `displaced`. `heartbeat` is the same refresh but never takes an item:
it is `not_claim_holder` unless the caller holds the active claim (recommended cadence: half the TTL). For claim, release and
heartbeat the holder id is the principal's audited id (`api:<id>` for a credential, `local` for the local principal); no policy can reject it. The holder's identity never appears in any error detail.

`select` filters: `role` (default `queue`), `parentId`, `tags` (any), `priority`, `type`, `complexityMax`, `createdAfter`,
`createdBefore`, `roleChangedAfter`, `roleChangedBefore`, `orderBy` (`priority` default, `oldest`, `newest`). The best eligible
match (no active claim, no blocking ancestor claim by another actor, no unmet dependency) is claimed and `selected: true` is
reported. A selector that finds nothing claimable is `ok` with `{claimed: false}` plus, when matching items exist but none can be
claimed, `reason` (`claim_held` or `dependency_unmet`), `retryAfterMs` 30000 and `excluded: {claimed, ancestorClaimed,
dependencyBlocked, outOfScope}`; nothing matched at all has no `reason`. Under a root- or tag-scoped token, candidates outside the
scope are skipped and counted in `excluded.outOfScope`; a selector never answers `forbidden`. Each candidate's ancestors are
re-read inside the claim unit: one held by another principal skips it (counted in `excluded.ancestorClaimed`) and the next
candidate is tried; an ancestor the caller holds never shields.

**Error codes** (per element; the call is `207 partial_failure` when some elements fail): `invalid_request` (field pointers
such as `/ops/0/trigger`, `/ops/1/ttlSeconds`, `/ops/2/itemId`, `/ops/0/credentialRefs/1`),
`unknown_parameter`, `not_found` (item), `ambiguous_id`, `forbidden` (token scope or capability), `invalid_transition` (also for
claiming a terminal item, and for claiming a lifecycle-free item: `fromRole` `none`, `trigger` `claim`, nothing allowed, the
caller's other claims kept; every `transition` trigger on a lifecycle-free item fails the same way with `fromRole` `none`), `gate_blocked` (`detail.missing[]`; for an independence block `missing` is empty and
`detail.violations` is `[{gate: "independence", reason}]`), `dependency_unmet`, `not_claim_holder`, `claim_held`
(`detail.expiresAt`, `detail.retryAfterMs`), `resource_unavailable` (`detail.resources[]` with the contended key and its mode,
`retryAfterMs`; never the holder), `unauthenticated`, `unavailable` (per-root config could not be read; `retryAfterMs` 1000), `config_invalid` (a config head, the server's or the root's, has no loadable canonical form; permanent,
`detail.errors[0].path` empty; never `internal`),
`idempotency_mismatch`.

**Cascades (the single source of cascade semantics).** A transition, and a structural edit, may re-evaluate the
item's immediate parent inside the same unit. The parent goes through the same policy as a direct transition
(table, warrant, hold, dependencies, notes, lease) with a cascade trigger, fenced for the caller of the write that
caused it (see Suppressed below), and a cascade applies only when the parent's schema lifecycle is `auto`: a parent that is lifecycle-free, `manual` or
`permanent` is left alone and nothing is reported. The result lists each cascade that applied or was suppressed in
`cascades`.

- **start.** A user trigger that lands an item in `work` (`start`, `resume`) evaluates its immediate parent: a parent
  in `queue` moves to `work`. It does not climb further.
- **terminal.** A transition that lands an item in `terminal` (`complete`, `cancel`, or `start` out of `review`)
  evaluates its immediate parent: the parent moves to `terminal` only when it has at least one child and every child
  is `terminal`. An applied terminal cascade evaluates its own parent in turn, so the chain climbs while each ancestor
  qualifies. A reparent away from a parent, and a delete under it, re-evaluate that old parent the same way
  ([`items_write`](#items_write)).
- **reopen.** `reopen` of a terminal item evaluates its immediate parent: a `terminal` parent moves back to `work`.
  It does not climb further.
- **Not reported.** A parent whose lifecycle is not `auto`, a parent in a role the cascade does not apply to, and a
  terminal cascade whose parent still has non-terminal children (or no children) produce no `cascades` entry.
- **Suppressed, reported.** A cascade that would apply but is stopped by a gate appears with `applied: false`
  and its reason: `blocked: ownership` (the parent has an active claim held by a principal other than the caller,
  checked before every other gate; the parent keeps its role and claim and the chain stops there; `items_write`
  reports `reason: not_claim_holder`), `blocked: seat` (the parent is a member of a run that is not closed (open or paused) whose pinned schema declares
  `seats` and the write's caller could not send the equivalent trigger to it directly: `start` for a start cascade,
  `complete` for a terminal cascade, `reopen` for a reopen cascade; this holds for a `start`, `complete`, `cancel` or
  `reopen` and for the reparent and delete cascades of `items_write`. The check runs in the order ownership, then
  seat, then the other gates. The coordinator token and a human credential pass. A member-seat token passes only for
  the one item it is bound to, so a seat child cascades into a member parent only when its token is bound to that
  parent, and it needs the equivalent trigger in its seat's `transitions` (or `enters` for `start`). The parent keeps
  its role and claim and the chain stops there; the wire shows `blocked: seat` and no seat, run or holder, and
  `items_write` reports `reason: seat_forbidden`. To recover, the coordinator token or a human credential advances the
  parent directly), `blocked: gate` (the parent lacks required notes; a terminal cascade checks every phase's notes
  unless it descends from a `cancel`, a start cascade checks the parent's current phase only, a reopen cascade
  never checks notes), `blocked: dependency` (the parent has an unmet blocker; `blockers`), `blocked: resource`
  (a start or reopen cascade into `work` whose exclusive lease is held elsewhere; `contended`), or `blocked: role`
  (the parent is `blocked`, an explicit hold: no cascade moves an item out of it, cancel-origin chains included).
  A suppressed cascade never fails the primary transition. A fault while applying an applicable cascade fails the
  whole call and rolls everything back.

Resource leases: a `transition` that enters `work` acquires an exclusive lease for each resource the item's traits declare;
a held key is `resource_unavailable`. Leases are released on every exit from `work`. `DELETE /api/v1/resources/leases/{key}`
(admin) is the recovery path for a stuck lease.

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool advance -->
Move items through their phases and manage who owns them. Each ops[] element has an op: transition (apply a trigger), claim (take an item by id or by selector), release, or heartbeat (extend a held claim). A transition on an item claimed by someone else is not_claim_holder.

**`advance` op `transition`** (orchestration profile only; capability `advance`): Move one item between phases with a trigger (start, complete, block, hold, resume, cancel, reopen). Gates, dependencies, claim ownership and resource leases apply; the result lists cascaded parents, unblocked items and what the next phase expects.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Item id: a UUID or a dash-free hex prefix of 8 to 32 characters. |
| `trigger` | string: start \| complete \| block \| hold \| resume \| cancel \| reopen | yes | The trigger to apply: start, complete, block, hold, resume, cancel or reopen. |
| `summary` | string | no | Optional human-readable summary recorded on the transition. |
| `credentialRefs` | array of string | no | Audit labels of the credentials this transition consumed (opaque labels, never secret values): at most 8, each 1 to 128 characters matching ^[a-z0-9][a-z0-9\-_./]*$. |
| `reason` | string: awaiting_decision \| dependency \| resource \| manual | no | Why the item blocks, block or hold only; default manual. resume clears it. |
| `decisionId` | string | no | With reason awaiting_decision only: the open decision on this item. |

Errors: `ambiguous_id`, `dependency_unmet`, `forbidden`, `gate_blocked`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_claim_holder`, `not_found`, `partial_failure`, `resource_unavailable`, `run_paused`, `seat_forbidden`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`advance` op `claim`** (orchestration profile only; capability `advance`): Claim an item for the calling actor, by itemId or by select (find-and-claim the best eligible match). Give exactly one of itemId or select. A select that finds nothing claimable is ok with claimed false and, when matches exist, a reason (claim_held, dependency_unmet) and retryAfterMs. Taking over another holder's lapsed claim adds displaced {assignmentId?, expiredAt, principal?}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | no | Item id: a UUID or a dash-free hex prefix of 8 to 32 characters. |
| `select` | object | no | Find-and-claim: claim the best eligible item matching these filters. |
| `select.role` | string: queue \| work \| review \| blocked \| terminal | no | Role to look in; default queue. |
| `select.parentId` | string | no | Only direct children of this item. |
| `select.tags` | array of string | no | Match items carrying any of these tags. |
| `select.priority` | string: high \| medium \| low | no | Only items of this priority. |
| `select.type` | string | no | Only items of this type. |
| `select.complexityMax` | integer | no | Only items with a complexity of at most this value. |
| `select.createdAfter` | string (date-time) | no | Only items created at or after this instant. |
| `select.createdBefore` | string (date-time) | no | Only items created at or before this instant. |
| `select.roleChangedAfter` | string (date-time) | no | Only items whose role changed at or after this instant. |
| `select.roleChangedBefore` | string (date-time) | no | Only items whose role changed at or before this instant. |
| `select.orderBy` | string: priority \| oldest \| newest | no | Ranking of the matches: priority (default), oldest or newest. |
| `ttlSeconds` | integer | no | Claim lifetime in seconds, 1 to 86400; default 900. |
| `claimRef` | string | no | Opaque caller reference (at most 64 characters), echoed in the result. |

Errors: `ambiguous_id`, `claim_held`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`advance` op `release`** (orchestration profile only; capability `advance`): Release the claim the calling actor holds on an item. An item claimed by someone else, or by no one, is not_claim_holder.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Item id: a UUID or a dash-free hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`advance` op `heartbeat`** (orchestration profile only; capability `advance`): Extend the active claim the calling actor holds on an item. Never takes an item: not_claim_holder unless the caller holds it. usage needs a seat token.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `itemId` | string | yes | Item id: a UUID or a dash-free hex prefix of 8 to 32 characters. |
| `ttlSeconds` | integer | no | Claim lifetime in seconds, 1 to 86400; default 900. |
| `usage` | object | no | Absolute spend so far for the calling seat, {tokens, costUsd}; the highest reported value is kept. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_claim_holder`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`
<!-- /generated:tool advance -->

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

The request is `{ "ops": [...], "atomic"?: bool }`; each element is a flat object naming its op in
`op`, with an optional `idempotencyKey`. One failed element of a non-atomic call is `207 partial_failure` on REST;
`atomic: true` fails the whole call with the code's own status. Every `rootId` is a UUID or a hex prefix of 8 to 32
characters and must name a depth-0 item. The scope check runs first, before existence and depth: a root outside the
caller's scope is `forbidden`, never `not_found`.

| Op | Result |
|---|---|
| `get` | `{rootId, version, fingerprint, source, createdAt, configYaml, relation?}` |
| `history` | `{rootId, headVersion?, versions: [{version, fingerprint, source, createdAt, principalId?, warningsCount, canonical}]}` newest first |
| `push` | `{rootId, version, fingerprint, appended, createdAt, warnings?}` |
| `validate` | `{rootId, valid, errors: [{path, reason}], warnings: [{path, reason}], diff?, affectedInFlight?}` |
| `rollback` | `{rootId, version, fingerprint, source, appended, createdAt}` |
| `reset` | `{rootId, appended}` |
| `effective` | `{rootId?, schemas, traits, types, statusGraph, defaultSchema?, globalFingerprint?, perRootFingerprint?}` |
| `rules` | a list `{rootId, rules: [{key, rulesVersion, updatedAt}]}` or one rule `{rootId, key, slug, rulesVersion, body, resolvedFrom?}` |

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
`removed` and `changed` name lists (no head, or a reset head, counts as empty). A valid document also gets
`affectedInFlight: {count, sample}`: the non-terminal items (queue, work, review, blocked, and lifecycle-free) in the root's subtree, visible
to the caller, whose resolved schema would differ if the document were pushed and the items repinned. The comparison is
each item's schema now (at its pin, or at the heads when unpinned) against the schema under the candidate over the
current server head. A note counts as changed only when its key is added or removed or its `role` or `required` flag
changes. `count` is exact; `sample` lists the first 20 as `{itemId, changedNotes, schema?, lifecycle?}`, where `schema`
(`{from, to}` of `{key, layer}` or null) and `lifecycle` (`{from, to}`) appear only when the base schema or lifecycle
mode differs. An unreadable config is `unavailable`, an undecodable pinned version `config_invalid`; there is no
partial result.

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
| `work_item_schemas` | Yes | `EffectiveConfigResolver.resolveSchema()` |
| `note_schemas` (legacy) | Yes | `EffectiveConfigResolver.resolveSchema()` |
| `traits` | Yes | `EffectiveConfigResolver.resolveSchema()` |
| `project` | Yes | `ConfigService` (embedded `project.rootId` guard only) |
| `note_limits` | Yes | `EffectiveConfigResolver.resolveNoteLimitsMode()` |
| `status_labels` | Yes | `EffectiveConfigResolver.resolveStatusLabels()` |
| `resources` | No: the resource registry is server-only (rejected) | n/a |
| `schema_resolution` | No: removed in 4.0 (rejected) | n/a |
| `extensions` | Stored, never read: an opaque mapping of namespaces for plugin and hook settings | n/a |
| `independence` | Yes | `EffectiveConfigResolver.resolveIndependencePolicy()`: a per-root block replaces the server one wholesale |
| `actor_authentication` | No: removed in 4.0 (rejected as an unknown key) | n/a |
| any other key | No (rejected) | n/a |

**Strict validation.** A push is validated before anything is stored: the document is checked against the
published JSON Schema (`GET /api/v1/config/schema`), everything the 3.x parser used to skip with a warning is
an error (an unknown key, a note with no or an invalid `role`, an invalid `lifecycle`, a seat with no `name`,
an invalid dispatch `effort`, ...), and semantic rules apply: `resources` is
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
(required notes, review phases, trait routing) for every item under a root. Under `MCP_TRANSPORT=http`, `/mcp`
authenticates exactly like `/api/v1` when the REST API runs a credential mode (`API_AUTH_MODE=bearer` or `jwks`), so
these ops need a credential with `write-config`. In the two local modes (`API_AUTH_MODE=none`, or the API disabled under
`MCP_ALLOW_UNAUTHENTICATED=true`) every caller is the `local` principal (ADMIN) and can call these ops for any root:
fence the port at the network layer, or bind it to `127.0.0.1`, before exposing it beyond a single trusted process.

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool config -->
Per-root config and rules. Each ops[] element has an op: get, history, push, validate, rollback, reset (a root's versioned config document), effective (the resolved view), or rules (rule text). Reads need the read capability, push, rollback and reset the write-config capability. rootId is a depth-0 item.

**`config` op `get`** (orchestration profile only; capability `read`): Read a root's config document: the head, or the given version (a reset version reads as an empty document). A root with no config, or a reset head, is not_found.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `version` | integer | no | Config version number, 1 or greater. |
| `fingerprint` | string | no | Content fingerprint to classify against the root's history; adds relation: current, superseded or unknown. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`config` op `history`** (orchestration profile only; capability `read`): List a root's config versions newest first, with metadata and no YAML. A root with no config returns no versions.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `before` | integer | no | Page back: only versions below this number (exclusive); pass the last version of the previous page. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`config` op `push`** (orchestration profile only; capability `write-config`): Validate a config document and append it as the root's next version (an identical re-push appends nothing, appended false). Content an earlier version held is version_conflict unless forced; a document that fails parsing or validation is config_invalid and nothing is stored.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `configYaml` | string | yes | The config document, YAML text of at most 131072 bytes. |
| `force` | boolean | no | Skip the project.rootId mismatch and older-content guards (default false). Never skips validation or expectedVersion. |
| `expectedVersion` | integer | no | Compare-and-set: the head version this write expects, 1 or greater. A different head is version_conflict; force does not skip it. |

Errors: `ambiguous_id`, `config_invalid`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `payload_too_large`, `unavailable`, `unknown_parameter`, `version_conflict`

**`config` op `validate`** (orchestration profile only; capability `read`): Dry-run push: parse and validate a document and diff it against the head; nothing is stored. Problems are reported in the result (valid false, errors), not as an error.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `configYaml` | string | yes | The config document, YAML text of at most 131072 bytes. |

Errors: `ambiguous_id`, `config_invalid`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `payload_too_large`, `unavailable`, `unknown_parameter`

**`config` op `rollback`** (orchestration profile only; capability `write-config`): Append a copy of an earlier version as the new head (source rollback, history kept). Rolling back to the head's own content appends nothing (appended false). An absent version, or a root with no config, is not_found.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `version` | integer | yes | Config version number, 1 or greater. |
| `expectedVersion` | integer | no | Compare-and-set: the head version this write expects, 1 or greater. A different head is version_conflict; force does not skip it. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`

**`config` op `reset`** (orchestration profile only; capability `write-config`; destructive): Reset a root's config: append an empty reset version so the root falls back to the server config; history is kept. A root with no config, or one already reset, appends nothing (appended false).

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`config` op `effective`** (orchestration profile only; capability `read`): The resolved config view: every type's schema, the traits, the status graph and the layer fingerprints. With rootId it is that root's config layered over the server's; without it, the server config alone.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `config_invalid`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`config` op `rules`** (orchestration profile only; capability `read`): Read git-tracked rule text (rule/<key> plan documents). rootId alone lists {key, rulesVersion, updatedAt}; rootId with key returns one rule's body; itemId with noteKey follows that note's skill pointer to the rule under the item's root. Any other combination is invalid_request.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Project root item (depth 0): a UUID or a hex prefix of 8 to 32 characters. |
| `key` | string | no | Rule key: lowercase letters, digits, dot, underscore or hyphen, starting alphanumeric, at most 100 characters. |
| `itemId` | string | no | Item whose effective schema holds noteKey: a UUID or a hex prefix of 8 to 32 characters. A root item is its own root. |
| `noteKey` | string | no | Note key looked up in itemId's effective schema; its skill field names the rule key. |

Errors: `ambiguous_id`, `config_invalid`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`
<!-- /generated:tool config -->

### docs_read

**Purpose.** Read the versioned documents of a project root: plans, git-tracked rules and pages. A 4.0 registry tool: the request is
the envelope request (`select` plus the select's fields) and every answer is the response envelope. The same tool is served over MCP and
over REST `POST /api/v1/ops/docs_read`. It replaces the read half of `manage_plan_documents` and `GET /api/v1/roots/{rootId}/plans[/{slug}]`.
Each field is documented once in the tool's own schema (`describe: true`).

**Selects.** `get`, `list`, `history`, `links`. All need the `READ` capability and honor the caller's root and tag scope: a `rootId` outside
the scope is `forbidden`, and nothing is read. A document is addressed by `(rootId, kind, slug)`; `kind` (`plan`, `rule`, `page`) is required
on every select that names one document and an optional filter on `list`. A `rootId` that is not a depth-0 item is `invalid_request`.

| Select | Answer (`data`) |
|---|---|
| `get` | `{id, rootId, kind, slug, title?, body, contentHash, version, archivedAt?, adoptedBy?, createdAt, modifiedAt}`; with `ifVersion` equal to the current version `{unchanged: true, version}`; with `version` n `{kind, slug, version, body, contentHash, createdAt, actor?}` |
| `list` | `{items: [{id, kind, slug, title?, contentHash, version, archivedAt?, adoptedBy?, createdAt, modifiedAt}], page}` ordered by kind, slug, id; no body |
| `history` | `{items: [{version, contentHash, bodyLength, createdAt, actor?}], page}` oldest first |
| `links` | `{items: [{id, relation, from: {kind, id}, to?: {kind, id}, toRef?, anchor?}], page}` ordered by creation time then id |

- `version` and `ifVersion` together are `invalid_request`; a missing document or version is `not_found`. Versions are integers, 1 for the first write.
- `contentHash` is the SHA-256 hex of the LF-normalized body's UTF-8 bytes. For a `rule` document it is the `rulesVersion` that the [`config`](#config) tool's `rules` op reports.
- `list` leaves archived documents out unless `includeArchived` is true. `adoptedBy` is the item that adopted a plan (`items_write` `create_tree` with a `docRef`); it is omitted when the caller's scope does not admit that item. The adoption outlives the item, so an unrestricted caller still sees a deleted adopter, while a scoped caller (which cannot place a missing item) does not.
- `links` returns the links the document makes (`references` parsed from `[[slug]]` in its body, `attached`, `adopted_by`) and, with `incoming`, the links that point at it (a `derived_from` note, another document's reference). A link end is `{kind, id}` with `kind` `item`, `note` or `document`; `to` is absent while a reference names no document yet, and `toRef` is the reference text (set on every `references` link, resolved or not, and on `derived_from` as `plan:<slug>`; absent on `attached` and `adopted_by`). A link whose item or note end is outside the caller's scope, or no longer exists, is left out before paging and is not counted in `page.total`. The adopter of an `adopted_by` link follows the `adoptedBy` rule instead: it is shown whenever the caller's scope admits it, even after the item is deleted (an unrestricted caller always sees it; a scoped caller fails closed on a deleted adopter).
- `actor` on a version is shown only to a caller allowed to see attribution (the same rule as `notes_read`).
- Paging follows the envelope's list rules (amendment U22).

```json
{ "select": "get", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign" }
{ "select": "list", "rootId": "3f9c2b10", "kind": "rule", "includeArchived": true }
{ "select": "links", "rootId": "3f9c2b10", "kind": "plan", "slug": "auth-redesign", "direction": "incoming" }
```

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool docs_read -->
Read the versioned documents of a project root. select: get (one document or one stored version), list (metadata only), history (versions) or links (what the document links to and what links to it).

**`docs_read` select `get`** (knowledge and orchestration profiles; capability `read`): Read one document by (root, kind, slug), with its body. With version n the answer is that stored version; with ifVersion equal to the current version it is {unchanged: true, version} and no body.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. On list it is optional and filters. |
| `slug` | string | yes | Document slug, unique per (root, kind). |
| `version` | integer | no | Read this stored version (at least 1) instead of the current one. Not together with ifVersion. |
| `ifVersion` | integer | no | The version the caller already holds (at least 1). When it equals the current version the answer is {unchanged: true, version} with no body. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`docs_read` select `list`** (knowledge and orchestration profiles; capability `read`): List the documents of a root without their bodies, ordered by kind then slug. Archived documents are left out unless includeArchived. adoptedBy names the item that adopted a plan, when the caller may see it.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | no | Document kind: plan, rule or page. On list it is optional and filters. |
| `includeArchived` | boolean | no | Include archived documents. Default false. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | Opaque cursor from the previous page's page.next. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`docs_read` select `history`** (knowledge and orchestration profiles; capability `read`): List the stored versions of one document, oldest first, without bodies. Read one with get and version.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. On list it is optional and filters. |
| `slug` | string | yes | Document slug, unique per (root, kind). |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | Opaque cursor from the previous page's page.next. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`docs_read` select `links`** (knowledge and orchestration profiles; capability `read`): List the links of one document, oldest first: {id, relation, from, to?, toRef?, anchor?}. to is absent while a reference names no document yet; toRef is its text. Links to items or notes outside the caller's scope are left out.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. On list it is optional and filters. |
| `slug` | string | yes | Document slug, unique per (root, kind). |
| `direction` | string: outgoing \| incoming \| all | no | outgoing = links the document makes, incoming = links that point at it. Default all. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | Opaque cursor from the previous page's page.next. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool docs_read -->

### docs_write

**Purpose.** Write the versioned documents of a project root. A request is `{ops: [...], atomic?}`; each `ops[i]` names its `op` and may carry
an `idempotencyKey`. Every element commits on its own unless `atomic` is true; the answer is a batch result (`results[i]` with `ok`, and
`207 partial_failure` over REST when some elements failed). It is served over MCP and `POST /api/v1/ops/docs_write`, and replaces the write half
of `manage_plan_documents` and `PUT /api/v1/roots/{rootId}/plans/{slug}`.

Every op needs the `WRITE_DOCS` capability (`write-docs`; before 4.0 it was `write-config`, which no longer grants any `docs_write` op): a `write-notes` token cannot
rewrite documents, because rule documents are served to agents as instructions by the `config` tool's `rules` op. Every op also checks the caller's root and tag
scope on `rootId`; a forbidden element is never executed and records no event.

| Op | Result (`results[i]`) |
|---|---|
| `upsert` | `{id, kind, slug, version, contentHash, created}` |
| `archive` | `{id, kind, slug, version, archived: true}` |
| `unarchive` | `{id, kind, slug, version, archived: false}` |
| `delete` | `{id, kind, slug, deleted: true}` |
| `attach` | `{linkId, documentId, itemId}` |
| `detach` | `{detached: n}` |

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
  is re-created. An adopted plan cannot be deleted (`invalid_request`). Deleting the item a document is anchored to deletes the document the same way,
  adopted or archived, with its own `document.deleted` (see `items_write` delete semantics).
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

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool docs_write -->
Write the versioned documents (plans, rules, pages) of a project root, in batches: upsert, archive, unarchive, delete, attach and detach. Every op needs the write-docs capability.

**`docs_write` op `upsert`** (knowledge and orchestration profiles; capability `write-docs`): Create the document at (root, kind, slug) or write its next version; CRLF becomes LF. Give exactly one of body and bodyFromFile. A plan or page body is at most 65536 bytes, a rule body 16384. An adopted plan is duplicate and an archived document invalid_request. Result: {id, kind, slug, version, contentHash, created}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |
| `body` | string | no | Inline document text (may be empty). Exactly one of body and bodyFromFile is required. |
| `bodyFromFile` | string | no | Server-side path whose text becomes the body (MCP only). Exactly one of body and bodyFromFile is required. Resolved strictly inside the agent config root: no absolute path, no '..', no symlink out of it; a regular UTF-8 file of at most 65536 bytes. |
| `title` | string | no | Display title. Omit to keep the current title. |
| `expectedVersion` | integer | no | Guard on the document's version: omit to write unconditionally, 0 to require that it does not exist, n to require that its current version is n. Anything else is version_conflict and nothing is written. |

Errors: `ambiguous_id`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `payload_too_large`, `unavailable`, `unknown_parameter`, `version_conflict`

**`docs_write` op `archive`** (knowledge and orchestration profiles; capability `write-docs`): Archive the document; its version does not change. Archiving an archived document does nothing. Result: {id, kind, slug, version, archived}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |
| `expectedVersion` | integer | no | Guard on the document's version: omit to write unconditionally, 0 to require that it does not exist, n to require that its current version is n. Anything else is version_conflict and nothing is written. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`

**`docs_write` op `unarchive`** (knowledge and orchestration profiles; capability `write-docs`): Unarchive the document. Unarchiving a live document does nothing. Result: {id, kind, slug, version, archived}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |
| `expectedVersion` | integer | no | Guard on the document's version: omit to write unconditionally, 0 to require that it does not exist, n to require that its current version is n. Anything else is version_conflict and nothing is written. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`

**`docs_write` op `delete`** (knowledge and orchestration profiles; capability `write-docs`; destructive): Delete the document and its versions. Links to it become dangling. An adopted plan cannot be deleted. Result: {id, kind, slug, deleted}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`docs_write` op `attach`** (knowledge and orchestration profiles; capability `write-docs`): Attach an item of the same root to the document. Attaching twice returns the same link. Result: {linkId, documentId, itemId}.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |
| `itemId` | string | yes | Id of the work item: a full UUID or 8 to 32 hex characters of one. It must be under the document's root. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`docs_write` op `detach`** (knowledge and orchestration profiles; capability `write-docs`; destructive): Remove the item's attachment to the document. Result: {detached: n}, 0 when it was not attached.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | Id of the depth-0 project root that owns the document: a full UUID or 8 to 32 hex characters. |
| `kind` | string: plan \| rule \| page | yes | Document kind: plan, rule or page. |
| `slug` | string | yes | Document slug, unique per (root, kind). On upsert: 1 to 200 characters, no leading or trailing whitespace, no control characters, none of [ ] #; a plan slug must not start with rule/. |
| `itemId` | string | yes | Id of the work item: a full UUID or 8 to 32 hex characters of one. It must be under the document's root. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`
<!-- /generated:tool docs_write -->

---

## Category: Reports

### report

**Purpose.** Read-only aggregate reports over the server's own telemetry. A 4.0 registry tool: the request is the
envelope request (`select` plus the select's fields) and every answer is the response envelope. On MCP it is a
**director-profile** tool only (`/mcp/director`); REST serves it at `POST /api/v1/ops/report`. Each field is documented
once in the tool's own schema (`describe: true`).

**Selects.** `usage` (the `call_log` usage aggregate, windows up to 400 days), `gates` and `contention` (aggregates over
the `events` table, windows up to 31 days), `waste` (call-pattern waste signals, windows up to 7 days) and `rollup`
(one subtree as one object, windows up to 31 days, or one whole run as one object).

| Select | Answer (`data`) |
|---|---|
| `usage` | `{items: [{key: {<groupBy>?: value}, calls, errors, responseBytes, responseTokensEst, avgLatencyMs, maxLatencyMs, retries, replays}], page: {total, truncated, next?}, window: {since, until, retainedSince?, basis, truncatedByRetention?}}` |
| `gates` | `{items: [{key: {<groupBy>?: value}, blocks, items}], page, window}` |
| `contention` | `{items: [{key: {resource?: value}, kind, acquired, rejected, expired, released, forceReleased, contentionRate?, expiryRate?, forceReleaseRate?}], page, window}` |
| `waste` | `{window: {...as usage}, signals: {redundantReads, overFetch, readsWithoutLaterWrite, emptyNext, relatedFollowThrough: {edges, links, tags, text}, lifecycleFreeRejections}}`, each signal `{value?, numerator, denominator, basis?}` |
| `rollup` | `{scope: {anchorId?, runId?, rootId}, window: {since, until, clamped?}, run?: {status, terminalReason?, openedAt, closedAt?}, itemCounts: {total, byRole}, blocked: {total, byReason?, resumable?, dwellMs?}, decisions?: {open, byClass, bySeverity}, seats?: {active, stalled: [{seat, itemId, silentMs}], count}, transitions: {total, cascades, rejected}, notes: {written}, cost: {calls, errors, retries, responseBytes, responseTokensEst, basis}, attempts?: {total, max}, budget?: {used, limit?, basis}}` (the `?` sections are run-only) |

- **Window.** Half-open `[since, until)`, ISO 8601 instants with `Z` or an offset, echoed in UTC at millisecond
  precision. `until` defaults to now and `since` to `until` minus 7 days; `since` must be before `until` and the window
  at most 400 days for `usage` (its daily basis), 31 days (the raw-table cap) for `gates`, `contention` and `rollup`,
  and 7 days (the session-join bound) for `waste`, else `invalid_request` on `/since`. The shared `since`/`until` field
  docs point at `describe`, where each select states its own default and cap. An anchor `rollup` derives its default
  window from the subtree when both bounds are absent; a run `rollup` takes no window at all (see below).
- **Metrics.** Every metric is always present. `errors` counts calls whose outcome was an error; `responseBytes` and
  `responseTokensEst` are sums (a call that recorded none counts 0); `avgLatencyMs` is the mean rounded half up;
  `retries` is the sum of `attempts - 1`; `replays` counts idempotent replays.
- **Grouping.** `groupBy` defaults to `tool`. `usage` accepts `tool`, `operation`, `host`, `surface`, `principalKind`,
  `errorCode`, `day` (the UTC date of the call) and `principal` (the principal id); `noteKey`, `type`, `trait` and
  `resource` belong to other selects and are `invalid_request` on `/groupBy`. A missing (NULL) value omits the
  `<groupBy>` member, so `key` is `{}`, never a JSON `null`. Groups are ordered by `calls` descending, then key
  ascending (an empty key first); `page.total` is the number of groups.
- **Retention.** `window.retainedSince` is the oldest raw row still held of the select's source, over the whole table: the `call_log` row for `usage` and `waste`, the oldest `transition.rejected` event for `gates`, the oldest claim or lease event for `contention` (absent when there is none); `window.truncatedByRetention: true` appears only when `since` is before the oldest data held (on the daily basis, the older of `retainedSince` and the earliest folded day). `basis` is `raw` for `gates`, `contention` and `waste`; for `usage` it is `raw` or `daily` (see the `usage` entry in the request reference below); a `rollup` window carries no `basis`.
- **`gates`.** Counts `transition.rejected` events in the window. `groupBy` defaults to `noteKey`; `noteKey`, `type` and `trait` count only `gate_blocked` rejections, `errorCode` counts every rejection code (`gate_blocked`, `dependency_unmet`). `blocks` is the number of distinct rejections in the group and `items` the number of distinct item ids. A rejection counts once for each distinct key of its missing notes under `noteKey`; one that names no missing note (an independence violation) falls in the `{}` group. `type` and `trait` are joined **as of now** (the item's current type and traits, not those at the time of the rejection): a rejection counts once per current trait, and a deleted item, an item without a type, or an item without traits falls in the `{}` group. Groups are ordered by `blocks` descending, then key ascending (an empty key first).
- **`contention`.** Counts claim and lease events per resource; `groupBy` accepts only `resource`. A lease's resource is its key and a claim's resource is the claimed item id; `kind` (`claim` or `lease`) separates rows whose resource strings are equal. `acquired` counts `claim.acquired` (heartbeat re-claims included) or `lease.acquired`; `rejected` counts `claim.rejected`, or `lease.rejected` once per distinct contended key; `expired` counts `claim.expired` or `lease.expired`; `released` counts `claim.released` (any reason) or `lease.released` that was not forced; `forceReleased` counts forced lease releases and is always 0 for claims. The rates are computed by the server: `contentionRate` = `rejected / (acquired + rejected)`, `expiryRate` = `expired / acquired`, `forceReleaseRate` = `forceReleased / acquired`, each rounded half up to 4 decimals, omitted when the denominator is 0 and never clamped (a window edge can push a rate above 1). Groups are ordered by `rejected` descending, then resource ascending, then kind ascending.
- **Per root.** There is no `root` grouping: pass `rootId` to report one project root.
- **Visibility.** For `usage`, `waste` and `rollup` (whose cost reads call rows), a tag-scoped credential is `forbidden` (scope `tag`) before anything is read: call rows carry no tags.
  `gates` and `contention` read events, which name their item: a tag-scoped credential sees only the events whose item currently carries an allowed tag (a deleted item's events are invisible) and is not refused; a root-scoped one sees the events of its roots (a lease row is rooted at its holder item's root). For `usage`, a root-scoped credential sees only calls recorded against one of its roots (calls with no root are never visible to
  it). Grouping by `principal` needs the `admin` capability (else `forbidden`, scope `capability`); the MCP profiles run
  unrestricted. `rootId` must be a project root (depth 0, else `invalid_request`) and narrows the rows further; it
  resolves by the shared scope-aware id rule (a root outside the caller's scope is `forbidden`). `waste` applies the
  same visibility to both sides of every session join: a row outside the window or the root filter is never seen,
  not even as the later row.

**`waste` signals.** One object, no grouping and no page (`groupBy`, `limit` and `cursor` are `unknown_parameter`).
The window rules are those of `usage`, but the window is at most **7 days** (the session-join bound), else
`invalid_request` on `/since`; joins never look past `until`. Every signal is always present, and
`relatedFollowThrough` always holds `edges`, `links`, `tags` and `text`. `value` is `numerator / denominator` rounded
half up to 4 decimals, a JSON number, omitted when the denominator is 0. Rows are classed by tool: READ is
`items_read`, `notes_read`, `docs_read`, `deps_read`; WRITE is `items_write`, `notes_write`, `deps_write`,
`docs_write`, `advance`; `report` and `config` are in neither. A non-ops REST row records its route template as the
tool, so it never matches a class. A `docs_read` `get` answered `{unchanged, version}` for a current `ifVersion` is
an ok read like any other.

- **Session signals** (`basis: "mcp_sessions"`) use only MCP rows with a `session_id`, on both sides of the join;
  REST rows never count, even when they carry a session id.
  - `redundantReads`: the observations are the (row, id, version) pairs in `target_versions` of ok READ session
    rows other than `related` and `search`. Denominator: the observations; numerator: the observations minus the
    distinct (session, id, version) triples.
  - `readsWithoutLaterWrite`: denominator the ok READ session rows; numerator those with no WRITE row (any outcome)
    of the same session strictly later in the window.
  - `relatedFollowThrough`: a unit is (an ok `items_read` `related` session row, one of its hits, one signal that
    found it). Denominator per signal: its units; numerator: the units for which a strictly later row of the same
    session, of any tool or outcome but not a `related` or `search` row, names the hit id in `target_ids` or as a
    `target_versions` key (exact match).
- **Both surfaces.**
  - `overFetch`: denominator the ok `items_read` `get`, `list`, `schema`, `context`, `next` and `blocked` calls;
    numerator those that asked for extra `fields`.
  - `emptyNext`: denominator the ok `items_read` `next` calls; numerator those that returned nothing although some
    item was eligible (`result_count = 0` and `eligible_count > 0`).
  - `lifecycleFreeRejections`: denominator the WRITE calls that are not idempotent replays; numerator the sum of
    their `lifecycle_free_rejections`. A replay repeats a stored response and is not a new rejection, so replayed
    rows are left out of both. It is a per-call rate, so a batch can push it above 1.

**`rollup` (anchor).** One object, no grouping and no page, built from pointers and counts only (8-character short
ids, never a note body). Exactly one of `anchorId` and `runId` is given: neither is `invalid_request` on `/anchorId`,
both on `/runId`. `anchorId` resolves like any item id (a hex prefix of 8 to 32 characters; outside the caller's scope
`forbidden`; unknown `not_found`); any depth is allowed. The subtree is the anchor plus every descendant.

- **Items.** `itemCounts.total` and `itemCounts.byRole` count the subtree's items by their current role (not windowed); a
  lifecycle-free item (no role) is not counted, but its descendants are. Zero roles are omitted. `blocked.total` is the
  number of items in the `blocked` role.
- **Events.** `transitions.total` counts `item.transitioned` events of subtree items in the window, `cascades` those
  with origin `cascade`, `rejected` the `transition.rejected` events; `notes.written` counts `note.upserted` events of
  the subtree items' current notes (each upsert counts, deletes never). An upsert of a note deleted since is not counted.
- **Cost** (`basis: "targets"`, always present). The distinct calls in the window whose `target_ids` names a subtree
  item, or that wrote one of the subtree's events in the window (same `req_id`), each counted once; metrics as `usage`
  (`errors`, `retries` = sum of `attempts - 1`, response sums with a missing value as 0). A lower bound: a call that
  named no id (or an id past the 50 that `target_ids` keeps) and wrote nothing is missed. A root-scoped credential
  counts only calls recorded against one of its roots (calls with no root never).
- **Window.** With `since` or `until` given, the usual rules apply (at most 31 days). With neither, the window runs
  from the subtree's first event to its last event plus 1 ms; a span longer than 31 days is cut to the 31 days ending
  there and `window.clamped: true` is set; a subtree with no event gets the last 7 days.
- **Not on an anchor.** `run`, `blocked.byReason`, `blocked.resumable`, `blocked.dwellMs`, `decisions`, `seats`,
  `attempts` and `budget` are run-only sections and are absent.

**`rollup` (run).** `runId` rolls up one whole run. It resolves like `runs_read` `get` (a full UUID, or a hex prefix of
8 to 32 characters; unrestricted: unknown `not_found`, several matches `ambiguous_id`; under a restricted scope an
unknown run or a run of another root is `forbidden`, never `not_found`). A call names exactly one of `anchorId` and `runId`. A run rollup always covers the whole run, so
`since` with `runId` is `invalid_request` on `/since` (`until` alone on `/until`). A tag-scoped credential is
`forbidden` before anything is read, as for the anchor.

- **Window.** `window` is informational: `since` is the run's `openedAt`, `until` its `closedAt`, or now while the run
  is open or paused. It is never `clamped`.
- **Items.** `itemCounts` counts the run's CURRENT members with a lifecycle by role. `blocked.total` is the blocked
  members, `blocked.byReason` counts them by reason (a legacy block without a reason reads `manual`; the counts sum to
  `blocked.total`), `blocked.resumable` counts the `awaiting_decision` members whose decision is no longer open or
  acknowledged, and `blocked.dwellMs` gives, per reason, the milliseconds the current members spent blocked inside the
  window (a block opened before the run counts from `openedAt`; an open block counts to `until`). A member blocked
  before the event log recorded the reason has no dwell (a lower bound). `resumable` and `dwellMs` are omitted when
  zero or empty.
- **Events.** `transitions` and `notes.written` count the events stamped with the run (`item.transitioned`, those with
  origin `cascade`, `transition.rejected`, `note.upserted`), whatever their time.
- **Cost** (`basis: "run"`). The calls stamped with the run, whatever their time or root (metrics as `usage`). A lower
  bound: a call that is not bound to the run (so not stamped with it) is missed.
- **Run sections.** `run` is the run's `status`, `terminalReason`, `openedAt` and `closedAt`. `decisions` counts the
  run's decision requests still open or acknowledged (`open`), by class and by effective severity (an overdue request
  reads one step more urgent). `seats.active` counts the run's active member seats (the coordinator excluded),
  `seats.stalled` lists at most 5 of those stalled under the run's `stallAfterSeconds` (silent longest first, then item
  id, then seat) and `seats.count` is the true number. `attempts` counts every member-seat assignment, ended ones
  included (`total`), and the highest attempt (`max`). `budget` is the run's token budget from the run row: `used` is
  the larger of the reported and the observed tokens, `limit` the pinned `budget.tokens` (absent when none), `basis:
  "enforced"`; the cost budget stays in `runs_read` `get`.
- **Closed runs.** Every close (any terminal reason, the decision `stop` included) stores the rollup it computes at
  that moment in the `run.ended` event (`data.rollup`). A closed run answers that snapshot, so the figures survive
  event and call-log retention and ignore calls logged after the close; a run closed before the snapshot existed is
  computed live. An open or paused run is always computed live.
- **Size.** The full REST response body of a run rollup, the envelope included, is at most 1,500 UTF-8 bytes for a 50-member
  run with one stalled seat and 2,000 bytes in the worst case (five stalled seats, every blocked reason, three classes, three
  severities). The bound is on the response, not on the snapshot stored in the `run.ended` event.

```json
{ "select": "usage" }
{ "select": "usage", "groupBy": "day", "since": "2026-10-01T00:00:00Z", "until": "2026-10-08T00:00:00Z" }
{ "select": "usage", "rootId": "550e8400", "groupBy": "host", "limit": 20 }
{ "select": "gates", "groupBy": "trait" }
{ "select": "contention", "since": "2026-10-01T00:00:00Z" }
{ "select": "waste", "since": "2026-10-06T00:00:00Z", "until": "2026-10-13T00:00:00Z" }
{ "select": "rollup", "anchorId": "1be2db5e" }
{ "select": "rollup", "runId": "7c0f3a91" }
```

**Related.** [Reporting](./reporting.md) explains which select answers which question, how to read the bases and what
retention changes in an answer; [Data Retention](./fleet-deployment.md#data-retention) holds the retention values.

**Request reference.** Generated from the registry (see [Overview](#overview)); every select or op below lists its complete input, its profile and capability, and the error codes it can return.

<!-- generated:tool report -->
Aggregate reports. select: usage (call metrics per group), gates (rejected transitions), contention (claims, leases), waste (ratios), rollup (subtree or run summary).

**`report` select `usage`** (director profile only; capability `read`): Call-log usage per group over [since, until), default the 7 days before now. groupBy tool (default), operation, host, surface, principalKind, errorCode, day (UTC date) or principal (admin only). Metrics: calls, errors, responseBytes, responseTokensEst (sums), avgLatencyMs (rounded half up), maxLatencyMs, retries (attempts - 1) and replays. Ordered by calls desc, then key asc. A missing group value is omitted from key ({}), never null, and sorts first. data.window gives since, until, retainedSince (the oldest raw row held), basis and truncatedByRetention when the window starts before the oldest data held. Basis raw reads the raw call log (window of at most 31 days inside the raw horizon); otherwise basis daily reads per-day summaries (at most 400 days; since floors and a folded until ceils to UTC midnight; groupBy principal is raw-only). A tag-scoped credential is forbidden; a root-scoped one sees only rows of its roots.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Only this depth-0 root (UUID or 8-32 hex prefix). Default: all you may see. |
| `since` | string | no | Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe. |
| `until` | string | no | Window end, exclusive: ISO 8601 with Z or offset. |
| `groupBy` | string: tool \| operation \| host \| surface \| principalKind \| errorCode \| day \| principal \| noteKey \| type \| trait \| resource | no | Row grouping; subset and default per select. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | page.next of the prior page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`report` select `gates`** (director profile only; capability `read`): Rejected transitions per group over [since, until) (default the 7 days before now, at most 31 days): blocks (distinct rejections) and items (distinct item ids). groupBy noteKey (default, one count per missing note key), type or trait count only gate_blocked rejections, joined to the item's current type and traits (as of now, not as of the rejection); errorCode counts every rejection code. A rejection with no value for the dimension (no missing key, a deleted item, no type or trait) falls in the {} group. Ordered by blocks desc, then key asc. data.window gives since, until, retainedSince (the oldest transition.rejected event held), basis raw and truncatedByRetention. A root-scoped credential sees its roots; a tag-scoped one sees rejections of items that currently carry an allowed tag.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Only this depth-0 root (UUID or 8-32 hex prefix). Default: all you may see. |
| `since` | string | no | Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe. |
| `until` | string | no | Window end, exclusive: ISO 8601 with Z or offset. |
| `groupBy` | string: tool \| operation \| host \| surface \| principalKind \| errorCode \| day \| principal \| noteKey \| type \| trait \| resource | no | Row grouping; subset and default per select. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | page.next of the prior page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`report` select `contention`** (director profile only; capability `read`): Claim and lease contention per resource over [since, until) (default the 7 days before now, at most 31 days): kind (claim or lease), acquired, rejected, expired, released and forceReleased counts, plus contentionRate (rejected / (acquired + rejected)), expiryRate (expired / acquired) and forceReleaseRate (forceReleased / acquired), 4 decimals half up, omitted when the denominator is 0 and never clamped (a window edge can push a rate above 1). A lease's resource is its key (a lease.rejected counts once per contended key); a claim's resource is the claimed item id. Only leases report forceReleased. groupBy resource (the only value). Ordered by rejected desc, then resource asc, then kind asc. data.window gives since, until, retainedSince (the oldest claim or lease event held), basis raw and truncatedByRetention. Visibility as for gates.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Only this depth-0 root (UUID or 8-32 hex prefix). Default: all you may see. |
| `since` | string | no | Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe. |
| `until` | string | no | Window end, exclusive: ISO 8601 with Z or offset. |
| `groupBy` | string: tool \| operation \| host \| surface \| principalKind \| errorCode \| day \| principal \| noteKey \| type \| trait \| resource | no | Row grouping; subset and default per select. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | page.next of the prior page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`report` select `waste`** (director profile only; capability `read`): Waste signals over [since, until), default the 7 days before now, at most 7 days, each {value?, numerator, denominator, basis?}: value is numerator/denominator rounded half up to 4 decimals, omitted when denominator is 0. redundantReads, readsWithoutLaterWrite and relatedFollowThrough (edges, links, tags, text) use MCP session rows only (basis mcp_sessions); overFetch, emptyNext and lifecycleFreeRejections use both surfaces. data.window as usage. A tag-scoped credential is forbidden; a root-scoped one sees only rows of its roots.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Only this depth-0 root (UUID or 8-32 hex prefix). Default: all you may see. |
| `since` | string | no | Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe. |
| `until` | string | no | Window end, exclusive: ISO 8601 with Z or offset. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`report` select `rollup`** (director profile only; capability `read`): Exactly one of anchorId and runId. anchorId: one subtree (the item and every descendant, any depth). itemCounts: total and byRole (current state; lifecycle-free items not counted, zero roles omitted); blocked.total (blocked role); transitions {total, cascades, rejected} and notes.written (note upserts; an upsert of a since-deleted note is not counted) from subtree events in [since, until); cost {calls, errors, retries, responseBytes, responseTokensEst, basis: targets}: the distinct calls in the window that named a subtree item (target_ids, at most 50 ids per call) or wrote one of its events, a lower bound. Default window: the first to the last subtree event (until = last + 1 ms), clamped to the last 31 days of that span (window.clamped); the last 7 days when the subtree has none. An explicit window is at most 31 days (until defaults to now, since to until minus 7 days). runId: the whole run (since and until are refused); window is openedAt to closedAt or now. itemCounts and blocked cover the current members; blocked adds byReason, resumable (awaiting_decision items whose decision no longer waits) and dwellMs (blocked time per reason in the window); transitions and notes count the events stamped with the run, cost {basis: run} the calls stamped with it (a lower bound). Adds run {status, terminalReason, openedAt, closedAt}, decisions {open, byClass, bySeverity (overdue reads one step up)}, seats {active, stalled (at most 5, silent longest first), count}, attempts {total, max} and budget {used, limit, basis: enforced} (tokens, from the run row). A closed run answers the snapshot stored at close. resumable and dwellMs are omitted when zero or empty. Ids are 8-character short ids. A tag-scoped credential is forbidden; a root-scoped one counts only calls of its roots (anchorId) or sees only runs of its roots (runId).

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `anchorId` | string | no | Roll up this subtree (UUID or 8-32 hex prefix). |
| `since` | string | no | Window start, inclusive: ISO 8601 with Z or offset. Default and cap: describe. |
| `until` | string | no | Window end, exclusive: ISO 8601 with Z or offset. |
| `runId` | string | no | Roll up this run (UUID or 8-32 hex prefix). |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool report -->

---

## Category: Runs

A run is a bounded, budgeted pass over a set of member items of one root. The run tools are 4.0 registry tools: the request is
the envelope request and every answer is the response envelope. Each field is documented once in the tool's own schema
(`describe: true`).

### runs_read

**Selects.** `get` (one run by UUID or an 8 to 32 character hex prefix), `list` (a page of runs, `openedAt` descending
then id), `seats` (orchestration only) and `frontier` (below). `list` filters: `status`, `rootId`, `endedSince` (ISO 8601 UTC, compared with `closedAt`, inclusive) and
`unmarked` (a mark key; only closed runs that do not carry it, so combining it with a `status` other than `closed` is
`invalid_request` on `/unmarked`). A run is as visible as its root: `get` of a run whose root is outside the caller's scope is
`forbidden` (never `not_found`), and `list` leaves such runs out.

**The run view** (every `runs_read` and `runs_write` result): `{runId, rootId, scopeId?, label?, title?, status,
pauseReason?, terminalReason?, openedBy?, openedByKind?, openedAt, closedBy?, closedAt?, coordinatorSeenAt?,
coordinatorStalled, stallAfterSeconds, budget?: {tokens?, costUsd?, attemptsPerSeat?, maxActiveSeats?}, usage:
{reportedTokens, reportedCostUsd, observedTokens}, hints, marks, members, activeSeats, stalledSeats, version}`. Absent
members are omitted, never `null`; the cost is `costUsd` (US dollars), never micro-USD. `activeSeats` counts the active
member seats (never the coordinator) and `stalledSeats` those of them that are stalled.

**Stalled.** One rule for seats and the coordinator: stalled when the time since last seen is strictly longer than the
run's own `stallAfterSeconds` (pinned at open; never re-read from config), compared exactly. A seat is last seen at its
`lastSeenAt`, or its `assignedAt` when it never touched anything; the coordinator at `coordinatorSeenAt`. Only a call
made with the seat's token (or the coordinator token, for the coordinator) refreshes them, and only on a tool that takes an
`ops` array, whichever op it names: a read-only op such as `config` `get` or `history` touches it as much as an `advance`
`heartbeat` does. A call to a `select` tool (`runs_read`, `items_read`), and a `describe: true` call on any tool, never
does. A closed run is
never stalled. Stalled is a derived view only: nothing is ended, released or raised.

**Frontier** (`frontier{runId}`): `{runId, status, stalled, counts: {queue, work, review, blocked, terminal}, ready:
[{id, title, role, type?}], inFlight: [{id, role, seat, attempt, lastSeenAt?, stalled}], waiting: [{id, reason,
blockers?}]}`, one read with a constant number of statements. `runId` is the full UUID, item ids are 8-hex short ids;
`stalled` is the coordinator's, the same as the run view's `coordinatorStalled`; every array is present. `counts` covers
the visible members, every role present. `ready` (membership order) holds the members that are neither terminal nor
blocked, have no active seat, no unsatisfied blocking dependency and no live claim held outside the run (a claim whose
holder pair is not an active assignee of this run, the coordinator included; an expired claim never counts); it is
always empty on a closed run, and a paused run computes it normally. `inFlight` (assignment order) holds the active
member seats, never the coordinator. `waiting` (membership order): a blocked member has `reason: manual`; a member with
an unsatisfied blocking dependency has `reason: dependency` and `blockers`, the unsatisfied blockers' short ids, sorted.
Under a restricted scope, members and blockers outside it are left out; a blocker outside it still keeps its item
waiting. No body, summary, token, principal or assignment id is shown.

**Request reference.** Generated from the registry (see [Overview](#overview)).

<!-- generated:tool runs_read -->
Read runs, the bounded budgeted passes over a set of items. select: get (one run), list (runs, newest first), seats (a run's seat assignments), frontier (ready, in-flight and waiting members), decisions or decision. A run or decision is outside the call scope when its root is.

**`runs_read` select `get`** (orchestration and director profiles; capability `read`): Read one run by id (a UUID or a hex prefix of 8 to 32 characters). A run whose root is outside the call scope is forbidden, not not_found.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`runs_read` select `list`** (orchestration and director profiles; capability `read`): List runs, newest first (openedAt descending). status, rootId and endedSince (closedAt at or after, inclusive) filter; unmarked names a mark key and keeps only closed runs without it, so it cannot be combined with a status other than closed. Runs on roots outside the call scope are left out.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | no | Only those on this root. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `status` | string: open \| paused \| closed | no | Only runs in this status: open, paused or closed. |
| `endedSince` | string (date-time) | no | Only runs closed at or after this instant (ISO 8601 UTC, for example 2026-10-13T00:00:00Z). |
| `unmarked` | string | no | Only closed runs that do not carry this mark key (lowercase letters, digits, - and _, starting with a letter). Implies closed runs. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | The page.next value of the previous page. |

Errors: `ambiguous_id`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`runs_read` select `seats`** (orchestration profile only; capability `read`): List the seat assignments of one run, oldest first: active ones, or every one with ended:true, optionally only those on one item. The coordinator pseudo-seat has no itemId. Tokens are never shown.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `itemId` | string | no | Only those on this item (a UUID or a hex prefix). |
| `ended` | boolean | no | true: include ended seats; default only active ones. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`runs_read` select `frontier`** (orchestration and director profiles; capability `read`): A run's frontier: member counts by role, the ready members (no active seat, no unmet dependency, no claim held outside the run; none on a closed run), the active seats with their stalled flag, and the waiting members with a reason. Members and blockers outside the call scope are left out.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`runs_read` select `decisions`** (director profile only; capability `read`): List decisions, newest first (raisedAt descending): id, class, severity, effectiveSeverity (one step up while overdue), status, overdue and the first 120 characters of the question. runId, itemId and rootId filter; overdue true keeps the pending decisions past their expiry. No status filter: filter on status client-side. Decisions on roots outside the call scope are left out.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | no | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `itemId` | string | no | Only those on this item (a UUID or a hex prefix). |
| `rootId` | string | no | Only those on this root. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `overdue` | boolean | no | true: only decisions past their expiry. |
| `limit` | integer | no | Page size, 1 to 200 (default 50). |
| `cursor` | string | no | The page.next value of the previous page. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_cursor`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`

**`runs_read` select `decision`** (director profile only; capability `read`): Read one decision by id (a UUID or a hex prefix of 8 to 32 characters): its question, options, recommendation, links, context, who raised it (kind and seat), the acknowledgement and the answer with its provenance (kind, proof status, seat, run, profile). A decision whose root is outside the call scope is forbidden, not not_found.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `decisionId` | string | yes | Decision id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `internal`, `invalid_request`, `not_found`, `unavailable`, `unknown_parameter`
<!-- /generated:tool runs_read -->

### runs_write

**Ops.** `open` (a run on a root over up to 500 of its items; the caller is recorded as `openedBy`), `update` (add or remove
items, lower the budget, replace the hints; `expectedVersion` guards a concurrent change), `close` (reason `completed`,
`cancelled`, `failed` or `abandoned`; the answer adds `snapshot: {reason, countsByRole, inFlight, released, openDecisions}`),
`pause`, `resume`, `assign` (a seat of a member item; returns the seat token once), `end_seat` (the calling seat ends its own
assignment), `raise`, `ack`, `answer` and `withdraw` (decisions, below) and `mark` (REST only: sets a named instant, first
write wins, at most 32 marks; the answer adds `marked`, false for a replay). Every op needs the `runs` capability (`ack` and
`answer` need `decide`) and a run whose root is in scope.

**Tokens.** `open` returns a `coordinatorToken` once; a keyed replay omits it (`tokenRedacted: true`). The coordinator is the
caller that presents that token as the top-level `seatToken`. `assign` returns a seat token once, bound to one member item
and one seat; re-assigning the seat supersedes the old token. A token that is unknown, ended, superseded or another
principal's is `unauthenticated` with no detail. When a run closes, its tokens die with it: a coordinator token of a
closed run is `unauthenticated` (for example on `update`), while a director or human `close` still works. A lost
coordinator token cannot be recovered: close the run and open another.

**Permissions.** `open` any caller with `runs`. `close`, any reason: the coordinator, the director profile or a human
token. `pause` and `resume`: the director profile or a human token (the opener may not pause). `update` and `assign`: the
coordinator token only. `end_seat`: the seat's own token. `mark`: any caller with `runs`. A refusal is `forbidden` with `detail:
{scope: "principal_kind", required}` where `required` lists the allowed kinds. Errors come in a fixed order: the scope check
on the run's root first, so a permission refusal never reveals whether an out-of-scope run exists; then the permission
refusal; then the state of the run (`run_closed`, `run_paused`, `invalid_run_state`). `assign` follows the same order, so a
caller outside the scope never learns whether the run is closed.

**Marks.** `mark` has no rule about its key's kind, is first-write-wins with no way to remove a mark, and a run holds at
most 32. Any token with `runs` whose scope reaches the run can stamp any key, `retro` included, which hides that run from
`runs_read` `list` with `unmarked: retro`. Treat a mark as a hint, not as proof that the named work happened.

**Decisions.** A decision is a question raised under a class (see
[Decision classes](config-reference.md#decision-classes-decision_classes)) that a human or a director answers.
`raise` is under the orchestration profile (a seat token supplies its run and item); `ack`, `answer` and `withdraw`
are served by the director profile, and REST `POST /api/v1/ops/runs_write` serves every op. Who may `ack` or `answer`:

| Class answerers | A human (human credential, real proof, no seat token) | The director profile (no seat token) | Any other caller |
|---|---|---|---|
| `human-only` | yes | no | no |
| `director-or-human` | yes | yes | no |
| `any-actor` | yes | yes | yes |

`ack` uses the same answerer check as `answer`; an acknowledged decision is returned unchanged by a second `ack`. An answer
is final and immutable: a later `ack`, `answer` or `withdraw` of an answered or withdrawn decision is `decision_closed`,
checked after the permission. `withdraw` is allowed for the raiser (the same principal and seat) or the coordinator token of
the decision's run. The decisions the server raises for a limit (`run-budget`, `attempt-cap`) are never withdrawable; they stay
pending until answered; closing the run is the other way out of the pause (the decision stays pending and is recorded in the close snapshot's `openDecisions`). Expiry (a class's `expires_after`) escalates the severity only: the decision reads
one step more urgent (`effectiveSeverity`, `overdue: true`) and the server takes no other action. A close withdraws no
decision and reports the pending ones in `snapshot.openDecisions`. An `answer` never resumes a blocked item; a decision that
was raised with `block: true` leaves its item blocked until someone resumes it.

`runs_read` `decisions` lists decisions of every status, withdrawn included, and has no `status` filter: filter on `status`,
`overdue` and `effectiveSeverity` on the client. The `decisions[]` of `items_read` `context` lists the item's decisions
without the withdrawn ones.

**Request reference.** Generated from the registry (see [Overview](#overview)).

<!-- generated:tool runs_write -->
Open, update, close, pause, resume and mark runs; assign and end seats; raise, ack, answer and withdraw decisions. Each op needs the runs capability (ack, answer: decide) and a root in scope. close: coordinator, director or a human; update, assign: coordinator token; end_seat: the seat's own token; pause and resume: director or human only; mark: REST only. Result: the run (close adds snapshot, mark adds marked) or the decision.

**`runs_write` op `open`** (orchestration and director profiles; capability `runs`): Open a run on a root over up to 500 of its items. The caller is recorded as the opener. An item already in an open run is duplicate. Returns the run and coordinatorToken, the run's coordinator credential (shown once; a keyed replay omits it and returns tokenRedacted: true): pass it as the top-level seatToken to update, assign and close.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `rootId` | string | yes | The root item the run belongs to. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `items` | array of string | no | Member items, at most 500. Each is an item of the root that has a lifecycle and is in no other open run. |
| `scopeId` | string | no | An item the run is scoped to. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `label` | string | no | A short label, at most 64 characters. |
| `title` | string | no | A title, at most 200 characters. |
| `budget` | object | no | Run budget: tokens, costUsd, attemptsPerSeat, maxActiveSeats; absent means no cap. On open it may not exceed a configured ceiling; on update a field can only be lowered or newly set. |
| `budget.tokens` | integer | no | Token limit. |
| `budget.costUsd` | number | no | Cost limit in US dollars, a decimal such as 12.5. |
| `budget.attemptsPerSeat` | integer | no | Most attempts one seat may take. |
| `budget.maxActiveSeats` | integer | no | Most seats active at once. |
| `stallAfterSeconds` | integer | no | Seconds without a coordinator sign of life before the run reads as stalled. Default from config. |
| `hints` | map of string | no | Short structured coordinator hints, string to string. On update they replace the stored hints. |

Errors: `ambiguous_id`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`runs_write` op `update`** (orchestration profile only; capability `runs`): Change an open run: add or remove items, lower the budget (a field can only go down or be newly set), replace the hints. Coordinator only. A paused run is run_paused; a closed run is run_closed. expectedVersion guards against a concurrent change. Returns the run.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `addItems` | array of string | no | Items to add to the run. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `removeItems` | array of string | no | Items to remove from the run. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `budget` | object | no | Run budget: tokens, costUsd, attemptsPerSeat, maxActiveSeats; absent means no cap. On open it may not exceed a configured ceiling; on update a field can only be lowered or newly set. |
| `budget.tokens` | integer | no | Token limit. |
| `budget.costUsd` | number | no | Cost limit in US dollars, a decimal such as 12.5. |
| `budget.attemptsPerSeat` | integer | no | Most attempts one seat may take. |
| `budget.maxActiveSeats` | integer | no | Most seats active at once. |
| `hints` | map of string | no | Short structured coordinator hints, string to string. On update they replace the stored hints. |
| `expectedVersion` | integer | no | Fails with version_conflict unless the run is at this version. |

Errors: `ambiguous_id`, `duplicate`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `run_closed`, `run_paused`, `unavailable`, `unknown_parameter`, `version_conflict`

**`runs_write` op `close`** (orchestration and director profiles; capability `runs`; destructive): End a run with a reason: completed, cancelled, failed or abandoned (budget_exhausted is server-only). Coordinator, director or a human. Any reason but completed ends active seats and releases claims and leases of the members; completed is refused while a seat is active. Returns the run and a snapshot of what the close recorded.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `reason` | string: completed \| cancelled \| failed \| abandoned | yes | Why the run ends: completed, cancelled, failed or abandoned. completed is refused while a seat is active. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_run_state`, `not_found`, `partial_failure`, `run_closed`, `unavailable`, `unknown_parameter`

**`runs_write` op `pause`** (director profile only; capability `runs`): Pause an open run (reason manual). Director or a human only; the opener of a run may not pause it. A run that is not open is invalid_run_state. Returns the run.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_run_state`, `not_found`, `partial_failure`, `run_closed`, `unavailable`, `unknown_parameter`

**`runs_write` op `resume`** (director profile only; capability `runs`): Resume a manually paused run. Director or a human only. A budget or attempts pause cannot be resumed, only closed (invalid_run_state, allowed close). Returns the run.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_run_state`, `not_found`, `partial_failure`, `run_closed`, `unavailable`, `unknown_parameter`

**`runs_write` op `mark`** (REST only; capability `runs`): Set a named mark on a run to now. REST only. Allowed in every status, including closed; the first write of a key wins and a replay changes nothing (marked false). A run holds at most 32 marks. Returns the run and marked.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `key` | string | yes | The mark key: lowercase letters, digits, - and _, starting with a letter, at most 64 characters. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`

**`runs_write` op `assign`** (orchestration profile only; capability `runs`): Assign a seat of a member item: a seat of the item's schema, or implementer or planner for an item with none. Coordinator token only. Re-assigning an active seat supersedes it (its token dies and its claims are released) and counts a new attempt. Returns assignmentId, seatToken and attempt; the token is shown once (a keyed replay omits it and returns tokenRedacted: true; re-assign for a fresh token) and every call made under the seat passes it as the top-level seatToken. Past the run's attempt cap it is run_paused (the run pauses on an attempt-cap decision); at maxActiveSeats it is run_at_capacity (retry when a seat ends).

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `runId` | string | yes | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `itemId` | string | yes | Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `seat` | string | yes | A seat of the item's schema (implementer or planner when it has none). Returns a seat token; re-assigning supersedes. |
| `hints` | map of string | no | Short structured coordinator hints, string to string. On update they replace the stored hints. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `run_at_capacity`, `run_closed`, `run_paused`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`runs_write` op `end_seat`** (orchestration profile only; capability `runs`): End the calling seat's own assignment with an outcome (done, failed or abandoned), an optional summary (at most 500 characters) and up to 10 links. Needs the seat's own token; the coordinator token is refused. Releases the claims the seat holds and reports them in released; its token stops working.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `outcome` | string: done \| failed \| abandoned | yes | How the calling seat ended. |
| `summary` | string | no | Closing summary, at most 500 characters. |
| `links` | array of object | no | Up to 10 {kind, ref} pointers; kind is item, note, doc, commit or url. |

Errors: `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`

**`runs_write` op `raise`** (orchestration profile only; capability `runs`): Raise a decision. Needs itemId, runId or rootId (a seat token supplies its run and item). The class sets the answerers and default severity. Re-raising a dedupeKey in the same scope returns the pending or answered decision (existing: true), or idempotency_mismatch if class, question or options differ. Payload at most 4096 bytes.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `class` | string | yes | Decision class (for example question or deviation); it decides who may answer. |
| `question` | string | yes | The question, at most 500 characters. |
| `options` | array of object | yes | 1 to 8 choices, each {key, label, consequence}. |
| `recommended` | string | no | Key of the recommended option. |
| `links` | array of object | no | Up to 10 {kind, ref} pointers; kind is item, note, doc, commit or url. |
| `context` | string | no | Background for the answerer, at most 1000 characters. |
| `dedupeKey` | string | yes | Re-raising the same key returns the open or answered decision instead of a new one. |
| `itemId` | string | no | Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `runId` | string | no | Run id: a UUID or a hex prefix of 8 to 32 characters. |
| `rootId` | string | no | The root item the run belongs to. Item id: a UUID or a hex prefix of 8 to 32 characters. |
| `severity` | string: high \| normal \| low | no | Class default. |
| `block` | boolean | no | true: also block itemId as awaiting_decision in the same unit. |

Errors: `ambiguous_id`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `invalid_transition`, `not_claim_holder`, `not_found`, `partial_failure`, `payload_too_large`, `seat_forbidden`, `unavailable`, `unknown_parameter`

**`runs_write` op `ack`** (director profile only; capability `decide`): Acknowledge an open decision: the answerers of its class only (human-only: a human credential with a real proof and no seat token; director-or-human: also the director profile without a seat token). An acknowledged decision is returned unchanged; an answered or withdrawn one is decision_closed. Returns the decision.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `decisionId` | string | yes | Decision id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `decision_closed`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`

**`runs_write` op `answer`** (director profile only; capability `decide`): Answer an open or acknowledged decision with an option key (text only for a freeform class) and an optional rationale: the answerers of its class only, as for ack. The answer is final and records the caller's kind, proof, seat and profile; a second answer is decision_closed. Returns the decision. Answering a run-budget or attempt-cap decision acts on its paused run (extend, stop, allow_one, skip_item).

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `decisionId` | string | yes | Decision id: a UUID or a hex prefix of 8 to 32 characters. |
| `option` | string | no | Key of the chosen option. |
| `text` | string | no | Free-form answer, at most 1000 characters; freeform classes only. |
| `rationale` | string | no | Why this answer, at most 1000 characters. |
| `amount` | number | no | run-budget extend only: how much to add, in the breached dimension (whole tokens, or US dollars). |

Errors: `ambiguous_id`, `decision_closed`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unauthenticated`, `unavailable`, `unknown_parameter`, `version_conflict`

**`runs_write` op `withdraw`** (director profile only; capability `runs`): Withdraw an open or acknowledged decision: its raiser (the same principal and seat) or the coordinator token of its run. An answered or withdrawn decision is decision_closed. The dedupeKey is then free for a new decision. Returns the decision.

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `decisionId` | string | yes | Decision id: a UUID or a hex prefix of 8 to 32 characters. |

Errors: `ambiguous_id`, `decision_closed`, `forbidden`, `idempotency_mismatch`, `internal`, `invalid_request`, `not_found`, `partial_failure`, `unavailable`, `unknown_parameter`, `version_conflict`
<!-- /generated:tool runs_write -->

---

## Idempotency

The write tools (`items_write`, `notes_write`, `deps_write`, `advance`) take no `requestId`: each `ops[]` element may
carry an `idempotencyKey`, recorded per `(principal, tool, key)` for 24 hours. A repeated key with
the same element replays the stored result with `replayed: true`; the same key with a different element is
`idempotency_mismatch`. Only an element that committed is recorded, so a failed element re-runs on retry with the same
key. `items_write` `create_tree` and `complete_tree` follow this rule like every other op: one element is one unit and
one record (the 3.x `requestId` of `create_work_tree` and `complete_tree` left with those tools). See
[items_write](#items_write), [notes_write](#notes_write), [deps_write](#deps_write) and [advance](#advance).

---

## Response envelope

Every 4.0 tool answers one envelope, byte for byte the same over MCP (`structuredContent`) and REST (the body).
Keys come in the fixed order shown and nulls are never sent; there is no top-level `success`, `message` or
`metadata`.

```json
{ "ok": true, "data": { "...": "..." }, "meta": { "reqId": "k7f3q9ab" } }
{ "ok": false,
  "error": { "code": "gate_blocked", "kind": "permanent", "message": "...", "detail": { "...": "..." }, "fix": "..." },
  "meta": { "reqId": "k7f3q9ab" } }
```

| Member | Meaning |
|---|---|
| `ok` | `true` on success, `false` on any error. |
| `data` | The answer. Omitted when the operation has nothing to return. |
| `error.code` | The wire code from the [error catalog](#error-catalog). |
| `error.kind` | The retry class of the code: `transient`, `permanent` or `shedding`. Always present, decided by the code alone. |
| `error.message` | Human-readable text. `internal` always carries the fixed text `Internal server error`; the cause is in the server log only. |
| `error.detail` | The code's structured detail (shape per code in the catalog). Omitted for a code that has none. |
| `error.fix` | One sentence naming what to change. Present for codes that define a fix. |
| `error.retryAfterMs` | Copied to error level from the `claim_held`, `resource_unavailable` (when set) and `unavailable` details. |
| `error.index` | The position of the failing element when an `atomic` batch fails. |
| `meta.reqId` | The call's [correlation id](#request-correlation-reqid). |
| `meta.replayed` | `true` when the whole call replayed a stored rejection. |

The HTTP status of a REST answer is the code's status (200 on success). Over MCP, `isError` is `!ok`, the single text
content is `ok` or `<code>: <message>`, and the result also carries `_meta.reqId`.

**Batch answers.** A write tool answers `data: {results, summary: {ok, failed}}`. `results[i]` is `{ok: true, ...}` or
`{ok: false, error}` (with `replayed: true` for a replayed element). When a non-atomic batch has failures the call
is `ok: false` with an `error` of code `partial_failure` next to the full `data` (HTTP 207). When an `atomic` batch
fails, only the failing element's error is returned, with `error.index`, under that code's own status, and nothing was
written.

**Per-root config and store faults.** A per-root config that cannot be read answers `unavailable` (kind `shedding`,
`retryAfterMs`); a config head that exists but cannot be loaded answers `config_invalid` (permanent). Both are
reported per element in a batch, so the other elements of a non-atomic batch commit normally. A response-only
decoration that fails to resolve on an already-committed write is omitted with a WARN in the server log, never
reported as a failure of the write. A busy database past the unit's retry deadline is `unavailable`
(`Database is busy: <sql text>`, `retryAfterMs` 1000), a connection-pool timeout is `unavailable`
(`retryAfterMs` 2000), a unique-constraint fault is `duplicate`, a foreign-key fault is `not_found`, and any other
store fault is `internal` with the fixed text above.

## Request correlation (`reqId`)

Every response, success or error, carries `meta.reqId`: the call's 8-character correlation id (lowercase Crockford
base32, for example `k7f3q9ab`). Over MCP it is also `_meta.reqId` on the tool result, and over REST the `X-Req-Id`
response header. It is the `req_id` of every event row the call wrote, the MDC `reqId` log key, and the primary key of
the call's row in the `call_log` table, whose `operation` column holds the select name, the single distinct op, `mixed`
or `describe`, and whose `principal_id` column holds the audited id of the call's principal (`api:<id>` or `local`), with
`proof_status` saying how it was verified. `tools/list` and `initialize` have none. See `fleet-deployment.md` -> "Call log and `reqId`".

## List data

Every list answer is `data: {items: [...], page: {total, truncated, capped?, next?}}` (amendment U22). The rules hold
for every paged select (`items_read` `list`, `search`, `overview`, `related`, `context`, `next`, `blocked`, `history`;
`notes_read` `list`; `deps_read` `list` and `graph`; `docs_read` `list`, `history` and `links`):

- `total` is the full count of matching entries on every page, not the page size.
- `truncated` is true exactly when entries exist after this page.
- `next`, an opaque cursor, is present exactly when `truncated` is true; following it until it is absent always
  terminates. A cursor that does not decode is `invalid_cursor`.
- `capped` is true when the underlying list was cut at a fixed ceiling (`search` and `related` hit lists, the `deps_read`
  `graph` walk), in which case `total` is the size of the capped list.
- `limit` has a per-select range and default (see the generated request reference of each select); a `limit` outside it
  is `invalid_request`.

## Error catalog

The closed catalog of error codes. `kind` is the retry class, decided by the code and never per call site:

| Kind | Meaning | Retry behavior |
|---|---|---|
| `transient` | A temporary condition elsewhere: a claim or resource lease held by another caller, an internal fault. | Retry the same request as-is, after `retryAfterMs` when it is present, otherwise with your own backoff. |
| `permanent` | The request cannot succeed as sent: validation, gate, dependency, ownership, conflict, not-found, authorization. | Do not retry unchanged; `error.fix` says what to change. |
| `shedding` | The server is declining load: the database is busy past the unit deadline, a connection pool timed out, or a per-root config could not be read. | Retry after `retryAfterMs`, and reduce the call rate if it persists. |

Each operation's reference above lists the codes it can return. Every operation can also return `invalid_request`,
`unknown_parameter`, `unavailable` and `internal`; a paged select can return `invalid_cursor`; a batch element can
return `partial_failure` (the call) and `idempotency_mismatch`.

<!-- generated:error-catalog -->
| Code | Kind | HTTP status | Detail |
| --- | --- | --- | --- |
| `invalid_request` | permanent | 400 | `fields`: array of {field: JSON pointer, reason: string, received?: string} |
| `unknown_parameter` | permanent | 400 | `parameters`: array of JSON pointers |
| `invalid_cursor` | permanent | 400 | none |
| `not_found` | permanent | 404 | `kind`: string: item \| note \| dependency \| document \| run \| root \| lease \| decision; `id`: string, optional |
| `ambiguous_id` | permanent | 409 | `prefix`: string; `candidates`: array of string (UUID) |
| `version_conflict` | permanent | 409 | `kind`: string: item \| note \| dependency \| document \| run \| root \| lease \| decision; `id`: string; `expected`: integer; `actual`: integer |
| `duplicate` | permanent | 409 | `kind`: string: item \| note \| dependency \| document \| run \| root \| lease \| decision; `id`: string, optional; `existingId`: string, optional |
| `idempotency_mismatch` | permanent | 409 | `idempotencyKey`: string |
| `invalid_transition` | permanent | 409 | `itemId`: string (UUID); `fromRole`: string; `trigger`: string; `allowed`: array of string |
| `gate_blocked` | permanent | 409 | `itemId`: string (UUID); `role`: string; `missing`: array of {key: string, role: string, seat?: string}, empty when only violations block; `violations`: array of {gate: string, reason: string}, optional |
| `dependency_unmet` | permanent | 409 | `itemId`: string (UUID); `blockers`: array of {id: string (UUID), role: string} |
| `cycle_detected` | permanent | 409 | `path`: array of string (UUID) |
| `claim_held` | transient | 409 | `itemId`: string (UUID); `expiresAt`: string (ISO-8601 instant); `retryAfterMs`: integer |
| `not_claim_holder` | permanent | 403 | `itemId`: string (UUID) |
| `seat_forbidden` | permanent | 403 | `itemId`: string (UUID); `seat`: string, omitted without a token; `action`: string; `allowedSeats`: array of string |
| `note_owned_by_other` | permanent | 403 | `itemId`: string (UUID); `key`: string; `ownerSeat`: string, optional |
| `note_too_long` | permanent | 413 | `key`: string; `max`: integer; `actual`: integer |
| `resource_unavailable` | transient | 409 | `itemId`: string (UUID); `resources`: array of {name: string, mode: string}; `retryAfterMs`: integer, optional |
| `run_closed` | permanent | 409 | `runId`: string (UUID); `terminalReason`: string |
| `run_paused` | permanent | 409 | `runId`: string (UUID); `pauseReason`: string: budget \| attempts \| manual; `pauseDecisionId`: string (UUID), optional |
| `run_at_capacity` | transient | 409 | `runId`: string (UUID); `active`: integer; `max`: integer |
| `invalid_run_state` | permanent | 409 | `runId`: string (UUID); `status`: string: open \| paused \| closed; `allowed`: array of string |
| `decision_closed` | permanent | 409 | `decisionId`: string (UUID); `status`: string: answered \| withdrawn |
| `schema_violation` | permanent | 422 | `type`: string, optional; `reason`: string; `availableTypes`: array of string, optional; `availableTraits`: array of string, optional |
| `schema_pinned_conflict` | permanent | 409 | `itemId`: string (UUID); `pinnedVersion`: string; `currentVersion`: string |
| `config_invalid` | permanent | 422 | `errors`: array of {path: string, reason: string} |
| `payload_too_large` | permanent | 413 | `max`: integer; `actual`: integer |
| `unauthenticated` | permanent | 401 | none |
| `forbidden` | permanent | 403 | `scope`: string: root \| tag \| capability; `required`: string |
| `partial_failure` | permanent | 207 | none |
| `unavailable` | shedding | 503 | `retryAfterMs`: integer |
| `internal` | transient | 500 | none |
<!-- /generated:error-catalog -->

**Retry guide.**

```
kind=transient -> retry the same request, honoring retryAfterMs; for claim_held or resource_unavailable, or pick another
                  item with items_read(select="next")
kind=permanent -> do not retry; read error.fix and error.detail, change the request
kind=shedding  -> wait retryAfterMs, then retry; slow down if it persists
```

A retried write is safe when its element carries an `idempotencyKey` (see [Idempotency](#idempotency)): the same key
and element replays the stored result.

---

## Actor Attribution

### Overview

Actor attribution tracks *who* made changes to work items. The actor of every call is the principal its transport
established, never a request body: `api:<id>` for a bearer or JWT credential (REST, and `/mcp` over HTTP when the
API runs a credential mode) and `local` for the local principal (stdio, `/mcp` in local mode, REST
`API_AUTH_MODE=none`). Its kind is always `external`. A body `actor`, top-level or in an `ops[]` element, is
`unknown_parameter` on every tool. See [fleet-deployment.md](./fleet-deployment.md#identity-configuration) -> Identity Configuration.

### Actor Shape

| Field | Type | Description |
|-------|------|-------------|
| id | string | The audited id: `api:<credential id>` or `local` |
| kind | string | `external` for every transport principal |
| parent | string | Always absent for a transport principal |
### Verification Record

Every persisted actor claim includes a verification record.

**Where it appears.** A verification record is returned as `verification` `{proofStatus, verifier?, reason?}` on `notes_read`
entries and on `items_read` `history` entries, and only to a caller allowed to see attribution (on every transport, a
principal with `ADMIN`, or any principal when `API_REDACT_NOTE_ATTRIBUTION=false`). The record is always persisted; the metadata
below is stored with it and is not part of the MCP wire shape.

| Field | Type | Description |
|-------|------|-------------|
| proofStatus | string | One of the five proof-status values below |
| verifier | string | Which verifier produced the result: `api-jwks`, `api-bearer` or `local` (see Verifier names below; rows written by earlier versions may carry other names) |
| reason | string | Failure detail or exception message; null when absent or verified |
| metadata | object | Optional key/value bag - omitted when empty (see below) |

**Proof evidence (V17+, never on the MCP surface).** Since migration `V17__Store_Actor_Proof_Evidence.sql`,
the database additionally stores `proofSha256` (a SHA-256 hex digest of the raw proof, set
whenever a non-blank proof was supplied) and `proofClaims` (the verified JWT claims — `iss`,
`sub`, `aud`, `jti`, `iat`, `exp`, `kid`, `alg` — set only when `status` is `verified`). Neither
field is returned by any tool or REST route since the 4.0 note reads (`notes_read` and the REST note GETs return
`verification` as `{proofStatus, verifier?, reason?}` only, `ADMIN` included; 3.x showed `verification.proof` to `ADMIN`), and the
transition trail (`items_read` `history`) never returns them either. They are stored for forensics only. See
[api-rest.md](api-rest.md) section 22.

**Proof status values.** The wire spells a verification as a proof status, never as the stored verification status:

| Value | Meaning |
|-------|---------|
| `verified` | The credential was a JWT verified against a fresh key set |
| `verified_stale` | The JWT was verified against a key set served from the stale cache after a failed refresh (within `API_JWKS_MAX_STALE_SECONDS`); a per-request fact of the principal, call log and provenance. A stored note records `verified` for it |
| `issued` | A server-issued token from the token file; not cryptographic proof, so it never satisfies `independence.require_verified` |
| `none` | No credential at all (the local principal) |
| `rejected` | History only: a proof that was presented and failed. The pipeline never produces it and a rejected credential yields no principal (`401`) |

Stored rows keep the older spellings and are read through one mapping: `unchecked` with verifier `api-bearer` is `issued`,
any other `unchecked` and the legacy `absent`, `unverified` and `unavailable` are `none`, and the legacy `failed` is
`rejected`; an unknown value is skipped. Nothing on a wire says `unchecked`, `absent` or `unavailable`. A note row stores only the older vocabulary (a stale-verified principal is stored as `verified`, an issued token as `unchecked`); no writer stores `verified_stale`, `issued` or `none` in a note, and a note row holding one would be skipped with a warning.

**Metadata fields (history only).** Rows written by earlier versions may carry these keys; a 4.0 server writes none of them.

| Key | Present when | Description |
|-----|-------------|-------------|
| `failureKind` | `rejected` or `unavailable` | Category of failure: `crypto` (signature/key), `claims` (exp/iss/aud/sub), `policy` (algorithm allowlist), `network` (JWKS fetch error), `internal` (unexpected exception) |
| `verifiedFromCache` | `verified` + stale JWKS cache | `"true"` when the verification used a key set served from stale cache |
| `cacheAgeSeconds` | `verified` + stale JWKS cache | Age of the cached key set in seconds at verification time |

### Verifier names

A transport principal's verification record names its source: `api-jwks` (proof `verified`) for a verified JWT, fresh or
stale; `api-bearer` (proof `issued`) for a token-file credential; `local` (proof `none`) for the local principal. The
server accepts no body `actor`.

---
## Actor Authentication & Verification

### `actor_authentication` is removed

No request body carries an actor claim or proof, so the `actor_authentication:` block in `.taskorchestrator/config.yaml` authenticated and attributed no call. It is removed: a leftover block in the global file fails startup, and `GET /api/v1/info` no longer returns `actorAuthenticationEnabled`. Caller identity is configured with `API_AUTH_MODE` and the `API_JWKS_*` variables. See [fleet-deployment.md](fleet-deployment.md#actor_authentication-removed-in-40) for the upgrade steps.

### No degraded-mode policy

Verification fails closed. The `DEGRADED_MODE_POLICY` environment variable (and the former
`actor_authentication.degraded_mode_policy` key) was removed in 4.0: a set variable (any value), or a leftover
`actor_authentication:` block, is an
`IllegalArgumentException` at startup naming it, before the readiness marker is written, so the server does not start
believing a security setting is in force. Ownership-sensitive calls (`advance` `claim`, `release`, `heartbeat` and the
ownership check of `transition`) run as the transport principal and are always enforced. A JWT whose key set is
unavailable is answered `503 unavailable` at the transport (REST, `/mcp`, SSE `?token=`), never served under a guessed
identity; see [api-rest.md](api-rest.md) section 1.

### JWKS network access

The JWKS endpoint of `API_AUTH_MODE=jwks` (`API_JWKS_URL`) is fetched by the server. Every fetch passes an egress guard that resolves the host and refuses it when any resolved address is non-public, so the container must reach the endpoint at a public address:

| Scenario | Works? | Notes |
|----------|--------|-------|
| Public HTTPS endpoint | Yes | Standard outbound from the container |
| `localhost` / loopback | Only with `API_JWKS_ALLOW_INSECURE_URL=true` | The guard refuses loopback otherwise; inside a container `localhost` is the container itself, not the host |
| `host.docker.internal` | No | Refused by the egress guard (it resolves to a private or host-gateway address) |
| Docker network service name | No | Refused by the egress guard (private address) |
| Other private, link-local, CGNAT or unique-local address | No | Refused by the egress guard |

Serve the key set from a public HTTPS host, or run the issuer behind a public name; see [fleet-deployment.md](fleet-deployment.md#jwks-source-and-egress).
