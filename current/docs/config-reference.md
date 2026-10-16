# Config reference

The canonical description of a Task Orchestrator config document: which keys exist, who owns them, what the server
rejects, and how the server reports a rejection. The structure itself is published as a JSON Schema (draft 2020-12):
[`config.schema.json`](../src/main/resources/config/config.schema.json), served by `GET /api/v1/config/schema`
(see [REST API Reference](api-rest.md)).

A config document is YAML. It reaches the server two ways: the server-wide file `.taskorchestrator/config.yaml`
(imported into the database at every boot from `AGENT_CONFIG_DIR`, see [Fleet Deployment](fleet-deployment.md)), and a
per-root document pushed with the `config` tool's `push` op (`POST /api/v1/ops/config`), which layers over the
server config for that project root's items and hot-reloads without a restart. The runtime reads the stored copy of the
file, never the file itself.

## Key ownership

Every top-level key has one owner. The server validates what it owns and stores the rest without reading it.

| Key | Owner | Server file | Per-root push | Notes |
|-----|-------|-------------|---------------|-------|
| `work_item_schemas` | server | yes | yes | Note schemas by item `type`: `lifecycle`, `default_traits`, `notes`, `seats`. |
| `note_schemas` | server | yes | yes | The legacy 3.x form of `work_item_schemas`. Cannot appear in the same document as `work_item_schemas`. |
| `traits` | server | yes | yes | Composable signals: `notes`, `resources`, `dispatch`, `seats`. |
| `note_limits` | server | yes | yes | `mode`: `warn` or `reject`. |
| `status_labels` | server | yes | yes | Trigger to label map; an explicit `null` clears a label. |
| `independence` | server | yes | yes | `mode` (`off`, `warn`, `reject`) and `require_verified`. |
| `runs` | server | yes | yes | Run settings: `stall_after` and the `budgets` ceilings. Resolved per field. See [Runs](#runs-runs). |
| `decision_classes` | server | yes | yes | Decision classes by name. A per-root entry replaces the server entry of that name. See [Decision classes](#decision-classes-decision_classes). |
| `project` | server (per-root) | ignored | yes | `rootId` and `name`. A `rootId` that differs from the push target is rejected unless `force`. |
| `resources` | server only | yes | rejected | The resource registry is one per server. |
| `actor_authentication` | removed | fails startup | rejected | Removed in 4.0: a leftover key fails startup (see [below](#actor_authentication)). |
| `extensions` | extension | allowed | allowed | A mapping of namespaces. Stored, never read by the server. |
| `schema_resolution` | removed | warning | rejected | Removed in 4.0; schemas resolve by one fixed precedence rule. |

### `extensions`

Plugin and hook settings belong under `extensions.<tool>`, not at the top level. `extensions` must be a mapping
(`null`, a list or a scalar is an error); the namespaces and everything under them are opaque to the server and may
change without a server release. The Claude Code plugin keeps its settings under `extensions.claude-code`:

```yaml
extensions:
  claude-code:
    orchestration:
      mode: workflow
    retrospective:
      mode: dispatch
    actor_attribution:
      required: true
```

In 3.x these three sections sat at the top level (`orchestration`, `retrospective`, `actor_attribution`). The strict
parser no longer knows them there: pushing them at the top level is rejected (`/orchestration` and so on) and the
fix is to move them under `extensions.claude-code`. Documents already stored from 3.x keep loading (see
[Parse modes](#parse-modes)).

### `actor_authentication`

Removed in 4.0. No request carries an `actor` or an `actor.proof` any more (the caller is the transport principal,
established by `API_AUTH_MODE`), so the block authenticated nothing. A global file with an `actor_authentication:` key,
whatever its value, fails startup naming the key; a per-root push carrying it is rejected at `/actor_authentication` as an
unknown top-level section. A set `DEGRADED_MODE_POLICY` environment variable and a legacy `auditing:` key fail startup too.

Delete the block. Caller authentication is configured with `API_AUTH_MODE` and the `API_JWKS_*` variables; see
[Fleet Deployment](fleet-deployment.md#actor_authentication-removed-in-40).

### Note entry `shared`

A note entry in `work_item_schemas.<type>.notes` (or a trait's `notes`) may set `shared: true` (default `false`). A note
is owned by the principal whose write created it, and another principal's `upsert` or `delete` of an owned note is
`note_owned_by_other`; `shared: true` exempts that key from ownership, so any principal that may write notes can write
it. Use it for notes several seats legitimately co-author. See [`notes_write`](api-reference.md#notes_write).

### Seat transitions

A seat in `work_item_schemas.<type>.seats` (or a trait's `seats`) may list `transitions:`, the triggers other than
`start` that a token for that seat may send. The values are `complete`, `block`, `hold`, `resume`, `cancel` and
`reopen`.

- **Default.** A seat with no `transitions:` may send `block` and `hold`.
- **`start`** is governed by `enters`, never listed: a seat token may `start` an item only into the phase of a seat that
  sets `enters: true`.
- **F5.** A seat that sets `enters: true` and lists `complete` or `cancel` is a fatal structural error (reported at the
  path of its `seats` list), so the seat that opens a phase cannot also close the item.
- **Where it applies.** Seats are enforced only on an item that is a member of a run that is not closed (open or
  paused) and whose pinned schema declares seats. Outside a run they stay an orchestration signal. A refusal is
  `seat_forbidden` (see [`advance`](api-reference.md#advance)).
- **The `orchestrator` seat.** A seat named `orchestrator` is the one seat whose note keys a run's coordinator token may
  write; every other seat-owned key needs that seat's own token (a human credential is exempt).

```yaml
work_item_schemas:
  feature-task:
    seats:
      - { name: implementer, phase: work, enters: true, transitions: [block, hold, resume] }
      - { name: orchestrator, phase: work, after: [implementer] }
```

## Runs (`runs:`)

The `runs:` block sets how long a run may go quiet and, optionally, ceilings on a run's budget. It is allowed in the
server file and in a per-root document, and each field resolves on its own: the per-root value, else the server value,
else the default.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `stall_after` | integer, seconds, 1 to 2147483647 | 600 | A seat or the coordinator is stalled when the time since it was last seen is strictly longer than this. The value is pinned on the run when it opens. |
| `budgets.tokens` | integer, at least 1 | none | Ceiling on the tokens a run may be given. |
| `budgets.cost_usd` | number, US dollars, above 0 | none | Ceiling on the cost a run may be given. |
| `budgets.attempts_per_seat` | integer, 1 to 2147483647 | none | Ceiling on the attempts one seat may make on one item. |
| `budgets.max_active_seats` | integer, 1 to 2147483647 | none | Ceiling on the seats active at once. |

Budgets are opt-in: an absent ceiling means unlimited. The block is a ceiling, not a default: a run's own budget at
`open` may only lower a ceiling (a value above it is refused), and `update` can only lower a field further or set one
that was absent. How a run enforces its budget, and the decisions a limit raises, are in
[Fleet Deployment](fleet-deployment.md#run-budgets-and-caps) and the
[workflow guide](workflow-guide.md#12-runs-seats-and-decisions).

```yaml
runs:
  stall_after: 900
  budgets:
    tokens: 2000000
    cost_usd: 25
    attempts_per_seat: 3
    max_active_seats: 4
```

## Decision classes (`decision_classes:`)

A decision is raised under a class, and the class sets who may answer it, its default severity and when it becomes
overdue. `decision_classes:` is allowed in the server file and in a per-root document. A class name matches
`^[a-z][a-z0-9_-]{0,63}$`.

| Key | Values | Meaning |
|-----|--------|---------|
| `answerers` | `human-only`, `director-or-human`, `any-actor` | Who may answer. A class that is not built in defaults to `director-or-human`. |
| `severity` | `high`, `normal`, `low` | The severity of a decision raised without one. Default `normal`. |
| `expires_after` | integer, seconds, at least 1 | Seconds after the raise when an unanswered decision becomes overdue and its severity reads one step up. Absent: never overdue. |
| `on_expiry` | `escalate` | What expiry does. `escalate` is the only value, and it changes only the reported severity; the server takes no other action. |
| `freeform` | boolean | `true`: an answer may be free text instead of an option. Default `false`. |

**Built-in classes.**

| Class | Answerers | Severity | Expires after | Notes |
|-------|-----------|----------|---------------|-------|
| `run-budget` | `director-or-human` | `high` | never | Raised by the server at a budget limit; `raise` refuses it. |
| `attempt-cap` | `director-or-human` | `high` | never | Raised by the server at an attempt cap; `raise` refuses it. |
| `deviation` | `director-or-human` | `normal` | 14400 | |
| `question` | `any-actor` | `low` | never | `freeform: true`. |

**Merging.** A per-root entry replaces the server entry of the same name as a whole. An entry for a built-in class
overrides that class field by field: a field you leave out keeps the built-in value. The answerers of `run-budget` and
`attempt-cap` may be raised to `human-only` but never lowered to `any-actor`; a push that does so is rejected at
`/decision_classes/<name>/answerers`.

```yaml
decision_classes:
  deviation:
    expires_after: 3600
  release-gate:
    answerers: human-only
    severity: high
    expires_after: 86400
```

**Single-developer stdio.** On a stdio server, every caller is the one `local` principal, whose kind is `agent`, so a
`human-only` class cannot be answered there: an answer needs a credential with kind `human`, and a stdio server has
none. The built-in classes are unaffected, because their answerers are `director-or-human` or `any-actor`.

## Strict validation

A pushed document is validated before it is stored, and nothing is stored if any error is found. Validation has
three parts, merged into one result:

1. **Structure.** The document is checked against the JSON Schema. Every modeled level sets
   `additionalProperties: false`, so an unknown key is an error at the unknown key's own path.
2. **The parser's findings.** Everything the 3.x parser used to skip with a warning is an error: a note entry with no
   `role` or an invalid one, an invalid `lifecycle` (including the removed `auto-reopen`), a seat with no `name`, an
   invalid dispatch `effort`, a `maxHolders` above 1, a reserved budget key, and so on. Enumerated values are
   case-sensitive and lowercase (`lifecycle: auto`, resource `mode: exclusive`); values 3.x accepted in other
   cases, a non-string `status_labels` value, a `maxLength` of 0 or a fractional TTL are now errors. A note entry's
   `shared` (boolean, default `false`; `true` exempts the key from note ownership) must be a boolean. A seat's
   `transitions` must be a list of `complete`, `block`, `hold`, `resume`, `cancel` or `reopen` (`start` is
   governed by `enters`); outside strict validation a non-list is ignored and a bad element is dropped, with a warning.
3. **Semantics.** `resources` is rejected in a per-root document; `work_item_schemas` and
   `note_schemas` cannot be combined; every `default_traits` entry must name a trait defined in the document or in
   the server config; every trait `resources[].key` must be declared in the server resource registry; a
   `lifecycle: none` schema may not declare `seats` (error at `/work_item_schemas/<type>/seats`) nor a note with
   `required: true` (error at `/work_item_schemas/<type>/notes/<i>/required`).

A structural error in the seat declarations (two seats entering one phase, a duplicate or reserved seat name, an
entering seat whose `transitions` lists `complete` or `cancel`, an `after` cycle) is fatal in every mode and is
reported with the path of the offending `seats` list.

### Errors and warnings

The result is `{errors: [{path, reason}], warnings: [{path, reason}]}`, in a stable order (sorted by path, then
reason). `path` is an RFC 6901 JSON Pointer into the document you sent: `/work_item_schemas/feature-task/notes/0/role`.
The root is the empty string. A key containing `/` or `~` is escaped as `~1` and `~0`
(`a/b~c` becomes `a~1b~0c`). An unknown key is reported at the key itself.

Warnings never reject a push. They are advisory: a resource key declared by three or more traits (contention is
likely), and a required note that no declared seat owns.

How a rejection reaches you:

| Surface | Rejection |
|---------|-----------|
| `config` tool `push` (MCP, or `POST /api/v1/ops/config`) | The element fails `config_invalid` (`422` on a single-element or atomic call, `207 partial_failure` otherwise); `detail.errors` holds the `{path, reason}` list. Advisory findings come back in a successful push's `warnings` and in `validate`'s `warnings`. |

`force` bypasses the rootId-mismatch and older-content guards (the latter is `version_conflict` otherwise). It does not bypass validation or `expectedVersion`.

### Checking before you push

The `config` tool's `validate` op runs the push pipeline without the write, the compare-and-set and the older-content
guard. It returns `valid`, the errors and warnings and, when the document is valid, a diff against the root's head
version: `{types, traits, settings}`, each with `added`, `removed` and `changed` entry names. An entry is `changed`
when its canonical JSON differs. With no head (or a reset head) everything is `added`. A problem (including a parse
failure) comes back in the result as `valid: false`, not as an error.

A valid document also reports `affectedInFlight`: `{count, sample}` for the in-flight items (queue, work, review,
blocked, and lifecycle-free) of the root that the caller can see and whose resolved schema would change on a repin after the push. Pinned
items are compared at their pin, unpinned items at the heads, against the candidate over the current server config. Only
gate-relevant changes count (a note key added or removed, or its role or required flag changed, a different base schema
or a different lifecycle mode); guidance and description edits do not. `sample` holds the first 20 item ids.

## Parse modes

The parser has two modes. Extraction is identical in both: the same document comes out of the same input. The mode
only classifies a finding.

| Mode | A former soft skip is | Used for |
|------|-----------------------|----------|
| `STRICT` | an error | pushing and validating new config, and the boot import of the server file (with the allowance below) |
| `MIGRATION` | a warning | reading config that already exists: stored versions, canonicalizing the rows copied from 3.x, restoring a version on rollback |

Reading a stored version is never an admission point, so a root whose 3.x config carries the old top-level plugin
sections keeps working. Only a new push has to be clean. Advisory findings are warnings in both modes.

**The server file is an admission point once per boot.** The `server-config-import` data step parses the file in
`STRICT` mode, the same rules a push is held to, with one allowance: an error at exactly the top-level key
`schema_resolution`, `orchestration`, `retrospective` or `actor_attribution` (the plugin sections and the removed
resolution key a 3.x file carried) becomes a stored warning instead (served as `configWarnings` by `GET /api/v1/info`);
nested or differently-cased keys get no allowance, and a push of the same text is still rejected. A leftover
`actor_authentication` key fails startup before the import runs. Every other error fails startup with one `path: reason`
line each. A file whose `project.rootId` names a depth-0 root is imported as that root's config and is held to the
per-root rules (`resources` and the legacy `note_schemas` next to `work_item_schemas` are rejected); a malformed or
unknown `project.rootId` fails startup. An absent, empty or comment-only file imports nothing.

## Schema pins

Every config version is immutable, so an item can resolve against the exact versions it started under. Each item is
**pinned**: it records the server config version and its root's config version that were the heads when the pin was
taken (none for a scope with no config or a reset head), the base schema its type resolved to under them (the schema
key and whether the server or the root layer declared it; none when schema-free) and that schema's `lifecycle`.

**What a pin governs.** A pinned item's schema (notes and gates), trait merge, seats, dispatch and resource
requirements come from its pinned versions and its pinned base schema, so a later push, rollback or reset of either
scope never changes them. Settings that are not about one item always follow the heads: status labels, `note_limits`,
`independence`, the resource registry, the declared type and trait lists, and every type-only schema lookup. A pinned
key the pinned document does not declare resolves schema-free. An item that was never pinned resolves at the heads.

**Pin points.** A pin is taken, and an `item.pinned` event (`{from, to, reason}`) recorded, at exactly these points:

| Reason | When |
|--------|------|
| `create` | an item is created |
| `type_change` | an update changes the item's `type` |
| `reparent` | an update moves the item under another root (pinned against the new root's head); every descendant that moves with it is re-pinned the same way, one row each |
| `trait_change` | an update changes the item's `traits` |
| `reopen` | a transition leaves `terminal` (`reopen`, or the reopen cascade); the gate of that transition runs under the old pin |
| `repin` | `items_write` `repin` is called and the recomputed pin differs from the stored one |

One update records at most one `item.pinned` row (precedence `type_change` > `reparent` > `trait_change`). A
title-only update or a move within the same root leaves the pin alone. `item.pinned` is recorded in the events table
only; it is not projected onto the SSE stream. A pin never names another root's config version, so deleting a root
(which deletes its config versions) never fails on a pin; should a surviving item's pin still name one (placement
data that disagrees with the tree), the delete first re-pins it against its own root's heads (reason `repin`).

**Stale pins.** `items_read` `get`, the scoped `overview` item and `schema` (by `itemId`) render the pin as
`schemaVersion: {server?, root?, key?, layer?}` (version numbers) and add `stale: true` when either pinned version is
no longer the head of its scope. `repin` moves the pin to the heads. A note key that the pinned schema lacks but the
head schema requires cannot be written until the item is repinned (`schema_pinned_conflict`).

**Upgrade rule (3.x).** On the first 4.0 start the `pin-backfill` data step (phase PIN, once) pins every item that has
no pin, to the server head (the imported server file) and its root's head, with the base schema the 3.x server gave it:
the 3.x `schema_resolution`-aware rule over the item's `type`, so a per-root `default` still shadows a server schema of
the same type under `legacy` mode. In-flight 3.x items therefore keep their 3.x schema until a pin point fires; new pins
use the 4.0 rule (per-root type, server type, per-root `default`, server `default`). The step writes no events and does
not change an item's `version` or `modifiedAt`. With no stored config version at all, the step still marks each item
pinned with no versions and no schema, so it stays schema-free (`stale: true` once a config is pushed) until a pin
point fires or `repin` is called. A schema entry the parser skips (for example a non-map value) is never pinned.
Every backfilled item has a role, so a base schema declared `lifecycle: none` is pinned with lifecycle `auto` (see
[Lifecycle-free items](#lifecycle-free-items)).

## Lifecycle-free items

`lifecycle` takes `auto` (the default), `manual`, `permanent` or `none`. A schema declared `lifecycle: none` makes
its items **lifecycle-free**: pages of knowledge or reference material that have no role at all. On the wire their
`role` is absent (REST `Item.role` is omitted, the status graph lists the type with `lifecycleMode: "none"` and no
transitions).

- **Grammar.** `none` is accepted lowercase only in a pushed document. A `lifecycle: none` schema may declare notes
  (all optional) but no `seats` and no `required: true` note: no phase exists, so no gate could enforce them. Seats or
  required notes a trait merges onto a lifecycle-free item are inert.
- **Pins required.** Lifecycle-free is carried by the item's pin (pin lifecycle `none`, stored with a NULL role). With
  pins off every item is created in `queue`. A role-bearing item whose type resolves to a `none` schema without being
  pinned to it (for example an unpinned item after a push) behaves as `auto`.
- **Create.** An item whose type's base schema is `lifecycle: none` is created lifecycle-free (`create` and
  `create_tree`); every other item is created in `queue`.
- **No transitions.** Every `advance` trigger on a lifecycle-free item fails `invalid_transition` (`fromRole: "none"`,
  no allowed triggers), as does `claim` (the item is never claimed and the caller's other claims are kept). Cascades
  skip it. It never appears in `next` or `blocked`, a claim by selector never picks it, and `items_write` `complete_tree` leaves
  lifecycle-free descendants out (an explicitly targeted one fails as usual).
- **Dependencies.** A lifecycle-free item cannot be an endpoint of a `blocks` edge: the edge is rejected
  `invalid_request`. `relates_to` edges are allowed.
- **Telemetry.** The call log's `lifecycle_free_rejections` column counts the rejections in a call's response that a
  lifecycle-free item caused (the `invalid_transition` with `fromRole: "none"` and the BLOCKS-endpoint
  `invalid_request` above); it is NULL when there were none.
- **Counts.** Role counts (overview `childCounts`, anchored roll-ups, health counts) count lifecycle-free items under
  no role; they are not folded into `queue`. A parent whose only children are lifecycle-free has no children for the
  completion cascade. `excludeTerminal` keeps lifecycle-free items listed, and a lifecycle-free descendant does not
  count as open work below a terminal one.

**Flip rule.** Only two pin points may move an item between role-bearing and lifecycle-free: a `type_change` update
and `repin`. Crossing into lifecycle-free is allowed from `queue` or `terminal` only, and only when the item has no
BLOCKS edge in either direction; it clears the role, previous role and status label, stamps `roleChangedAt` and clears
the claim. Crossing out of lifecycle-free puts the item in `queue`, subject to the create rule (no new open item under
a `terminal` parent whose lifecycle is `auto`). A flip records `item.updated` (its fields include `role`) and
`item.pinned`; it writes no transition row and runs no cascade. Any other crossing fails `invalid_transition` with
`fromRole` the item's role (or `none`), `trigger` the pin reason (`type_change` or `repin`) and no allowed triggers,
and writes nothing.

The other pin points never change an item's lifecycle class: a `reopen`, a `trait_change`, a cross-root `reparent`
(checked for the item and every descendant that moves with it) and the delete-time re-pin of a surviving item. When a
pushed config made the item's type `none` (or no longer `none`), such a pin point fails `invalid_transition` with
`trigger` set to that pin reason (`reopen`, `trait_change`, `reparent`, `repin`) for the crossing item, and writes
nothing; call `repin` (or change the type) to flip the item explicitly. A reopen cascade in that situation keeps the
item's current pin.

## Related

- [config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md): the
  schema-authoring guide (note entries, traits, seats, dispatch, resources) the plugin ships.
- [REST API Reference](api-rest.md), section "Project config", for the routes.
- [Workflow guide, Runs, Seats and Decisions](workflow-guide.md#12-runs-seats-and-decisions): how runs, seats and
  decisions use these keys.