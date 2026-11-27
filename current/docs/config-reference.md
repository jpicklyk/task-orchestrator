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
| `work_item_schemas` | server | yes | yes | Note schemas by item `type`: `lifecycle`, `default_traits`, `notes`, `seats`, `builds`. |
| `note_schemas` | server | yes | yes | The legacy 3.x form of `work_item_schemas`. Cannot appear in the same document as `work_item_schemas`. |
| `traits` | server | yes | yes | Composable signals: `notes`, `resources`, `dispatch`, `seats`, `builds`, and the text fields `description` and `applies_when`. |
| `note_limits` | server | yes | yes | `mode`: `warn` or `reject`. |
| `status_labels` | server | yes | yes | Trigger to label map; an explicit `null` clears a label. |
| `independence` | server | yes | yes | `mode` (`off`, `warn`, `reject`) and `require_verified`. |
| `runs` | server | yes | yes | Run settings: `stall_after` and the `budgets` ceilings (`attempts_per_seat`, `max_active_seats`). Resolved per field. See [Runs](#runs-runs). |
| `decision_classes` | server | yes | yes | Decision classes by name. A per-root entry replaces the server entry of that name. See [Decision classes](#decision-classes-decision_classes). |
| `project` | server (per-root) | `rootId` retargets the import to that root (see Parse modes); `name` ignored | yes | `rootId` and `name`. A `rootId` that differs from the push target is rejected unless `force`. |
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
    steering: workflow
    retrospective:
      mode: dispatch
```

In 3.x three sections sat at the top level (`orchestration`, `retrospective`, `actor_attribution`). The strict
parser no longer knows them there: pushing them at the top level is rejected (`/orchestration` and so on) and the
fix is to move the `orchestration.mode` value to `extensions.claude-code.steering` and `retrospective` under `extensions.claude-code`. `actor_attribution` has no new home:
the 4.0 plugin removed the attribution hook that read it (the plugin reads no `actor_attribution` key), so delete the key.
Documents already stored from 3.x keep loading (see [Parse modes](#parse-modes)).

### `actor_authentication`

Removed in 4.0. No request carries an `actor` or an `actor.proof` any more (the caller is the transport principal,
established by `API_AUTH_MODE`), so the block authenticated nothing. A global file with an `actor_authentication:` key,
whatever its value, fails startup naming the key; a per-root push carrying it is rejected at `/actor_authentication` as an
unknown top-level section. A set `DEGRADED_MODE_POLICY` environment variable and a legacy `auditing:` key fail startup too.

Delete the block. Caller authentication is configured with `API_AUTH_MODE` and the `API_JWKS_*` variables; see
[Fleet Deployment](fleet-deployment.md#actor_authentication-removed-in-40).

### Note entry `shared`

A note entry in `work_item_schemas.<type>.notes` (or a trait's `notes`) may set `shared: true` (default `false`). A note
is owned by the (principal, seat) pair whose write created it, and an `upsert` or `delete` of an owned note by any other
pair (another principal, or the same principal under another seat) is `note_owned_by_other`; `shared: true` exempts that
key from ownership, so any principal that may write notes can write
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
- **Orchestrator notes.** A note labelled `seat: orchestrator` is owned in every schema and every phase, with no seat
  declared; in a schema that declares no seats the label stands as written. Inside an open run on a seat-aware pinned
  schema, only the run token of that run (or a human credential) may write it: a member seat token, or an agent with no
  token, is refused `note_owned_by_other` with `detail.ownerSeat` and `detail.runId`. Every other seat-owned key needs that seat's own token.
  The `orchestratorNotes` feature advertises this.
- **Reserved seat names.** Two seat names are refused and two warn. `unowned` is refused: a schema or trait that
  declares a seat of that name (exact match) is a structural error, because `unowned` is the `missingBySeat` bucket that
  holds the required notes no declared seat owns. `director` is refused the same way, because the director never fills a
  seat or writes notes. A seat named exactly `orchestrator` or `coordinator` loads with an advisory at `<entry>/name` saying the name will be
  refused in a later release: the orchestrator is a reserved note owner, not a seat, so remove the entry and label its
  notes `seat: orchestrator`. No agent is dispatched for orchestrator notes, and a `dispatch.<phase>.seats.orchestrator`
  override names no declared seat.

```yaml
work_item_schemas:
  feature-task:
    seats:
      - { name: implementer, phase: work, enters: true, transitions: [block, hold, resume] }
```

### Seat dispatch precedence

For each `(phase, seat)` the resolver takes the first per-seat dispatch override in trait order (per-item `traits`
first, then `default_traits`), with one exception: an override from a seat-only trait (no phase-level
`dispatch.<phase>` profile in any phase) that sets `model` and neither sets nor clears `agent` is model-selecting,
and the first such override outranks the others whatever the order. The winner replaces the rest whole, with no
field merge, so `[delegated, complex-implementation]` and `[complex-implementation, delegated]` both resolve the
implementer to opus. A model-only override on a seat that `delegated` gives `agent: null` (for example
`test-author`) drops that `agent: null`. The validator warns at
`/work_item_schemas/<type>/default_traits/<index>` when a `default_traits` list names a model-selecting trait after
an earlier trait whose override for that seat is not itself model-selecting (the earliest overrider decides); the warning is kept for one release as a
migration aid. It checks `default_traits` lists only, because the validator does not see item traits.

### Note entry `skill` and `skill_mode`

A note entry's `skill` is a string or a non-empty list of distinct non-blank strings, kept in invocation order.
`skill_mode` is `invoke` (default) or `method-on-diff`. `method-on-diff` marks the skill as a method the reviewer
applies to the item's owned diff, and the plugin's review-wave then keeps only the findings whose file is owned (every
review lane is given the owned-file diff; the mode adds that findings filter). A mode without a
`skill`, or a value other than those two, counts as `invoke`. `items_read` `schema` serves `skills` only when a note names two
or more skills and `skillMode` only when it is `method-on-diff`; a single-skill, `invoke` note serves as before.
A malformed list (empty, a non-string or blank element, a repeated name) or an unusable `skill_mode` is a parser finding
(see "Strict validation").

### Review note `lane`

A review-role note entry may declare `lane`, a map of `output` (`verdict`, the default, or `findings`) and `after`
(a note key or a list of note keys). `findings` lanes return findings only and never set the item verdict. `after` is an
ordering hint: the lanes of those notes run first, and it never halts or skips a lane. The server checks the shape, not
that an `after` key exists in the effective schema. A parser finding is an error under `STRICT` and a warning under
`MIGRATION`, and each of these is one: `lane` on a note whose role is not `review` (the lane is dropped), a `lane` that is
not a map (dropped), an unknown key inside it (ignored, the lane kept), an `output` other than `verdict` or
`findings` (it falls back to `verdict`), an `after` that is neither a note key nor a list (dropped), an `after` list
element that is not a non-blank string (the whole `after` is dropped), and an `after` element that names the note's
own key or repeats an earlier one (that element is dropped).

### Trait `description` and `applies_when`

A trait may carry `description` (what it is for) and `applies_when` (when it applies, in prose). Both are trimmed, a
blank string is absent, and a non-string value is a parser finding and is dropped. They are served with the trait by
`config` `effective` and never evaluated by the server. A per-root trait entry replaces the server's entry of the same
name as a whole, so a per-root trait that omits them serves neither.

### Schema and trait `builds`

`builds: false` on a work item schema or on a trait marks items that are not work that builds (decision 0004), such as a
plan or a design item. In the `queue` role, `items_read` `next` and an `advance` `claim` select skip them, with or
without `includeParents`; a claim by `itemId` still claims them, and the other roles are unaffected. An item builds unless
its schema or any of its traits (item traits, then `default_traits`) sets `builds: false`: false wins, and `builds: true`
changes nothing. A trait that carries only `builds: false` still applies. A schema-free item (a type no schema resolves)
always builds: its traits' `builds` is not read. A per-root trait entry replaces the server's
entry whole, so a per-root trait that omits `builds` leaves it unset. A non-boolean value is a parser finding and is
ignored. The value is part of the item's [schema pin](#schema-pins): a later push changes it only when the item re-pins
(`repin`), and an item pinned before the flag was stored builds until it re-pins. `config` `effective` shows `builds: false` on a schema or
trait that sets it.

### Seat writes

A seat entry's optional boolean `writes` says whether the seat changes files in the item's worktree and commits them; notes
are not covered. The field table in
[config-format.md](../../claude-plugins/task-orchestrator/skills/manage-schemas/references/config-format.md#seat-entry-fields-seats-schema-level-or-trait-level)
holds the default. The `seatWrites` feature advertises it.

### Seat rules

A seat entry may declare `rules`, a list of rule keys. It is absent when not declared (the plugin's defaults for that
seat's role apply) and `[]` declares no rule. Duplicates collapse in first order; a non-string element or a key that does not
match the rule key grammar is a parser finding and is dropped. Existence is not checked, because rules are pushed
separately: the server only serves the list, and only on a seat that declares it. Nothing in the server enforces it.

The plugin gives each seat three classes of rule key, from one table (`claude-plugins/task-orchestrator/hooks/lib/seat-rules.json`):
the seat role's protocol rule (`protocol.entry-seat`, `protocol.in-phase-seat` or `protocol.read-only-agent`; the role
comes from the seat's served `enters` and `writes`, [0016](../../design/decisions/0016-seat-capability-and-orchestrator-notes.md)), tool
mechanics the plugin chooses; the rules the plugin adds for the role (`commit-discipline` for a writing seat); and the
role's defaults, which a declared `rules` list replaces. No declared list removes the first two classes, and a declared
`protocol.*` key that is not the role's own is dropped and reported. A project may still replace a protocol rule's text
by serving its own copy under the same key: that copy stops receiving plugin updates, and the manage-schemas audit
reports it as project-authored. Conduct for a seat is added with a project rule on the seat's `rules` list.

### Seat skills

A seat entry may declare `skills`, a list of skill names. It is absent when not declared (the plugin's defaults for that
seat apply), `[]` declares no skill, and a declared list is served as written. Each name is kept verbatim (no trim, no
pattern: a plugin skill carries a `plugin:` prefix, a project skill is bare). Duplicates collapse in first order; a
non-string or blank element is a parser finding and is dropped, and a non-list value is a parser finding and the field
is absent. Existence is not checked and nothing in the server enforces the list: the server only serves it, and only on
a seat that declares it. The `seatSkills` feature advertises it.

## Runs (`runs:`)

The `runs:` block sets how long a run may go quiet and, optionally, ceilings on a run's caps. It is allowed in the
server file and in a per-root document, and each field resolves on its own: the per-root value, else the server value,
else the default.

| Key | Type | Default | Meaning |
|-----|------|---------|---------|
| `stall_after` | integer, seconds, 1 to 2147483647 | 600 | A seat or the orchestrator is stalled when the time since it was last seen is strictly longer than this. The value is pinned on the run when it opens. |
| `budgets.attempts_per_seat` | integer, 1 to 2147483647 | none | Ceiling on the attempts one seat may make on one item. |
| `budgets.max_active_seats` | integer, 1 to 2147483647 | none | Ceiling on the seats active at once. |

Ceilings are opt-in: an absent ceiling means unlimited. The block is a ceiling, not a default: a run's own budget at
`open` may only lower a ceiling (a value above it is refused), and `update` can only lower a field further or set one
that was absent. `budgets.tokens` and `budgets.cost_usd` were removed in 4.0.1: a document that still sets them (any
value) validates and pushes, each key draws one warning at its pointer (`/runs/budgets/tokens`,
`/runs/budgets/cost_usd`), and no ceiling is applied. How a run enforces its caps, and the decisions a limit raises, are in
[Fleet Deployment](fleet-deployment.md#run-budgets-and-caps) and the
[workflow guide](workflow-guide.md#12-runs-seats-and-decisions).

```yaml
runs:
  stall_after: 900
  budgets:
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
   `shared` (boolean, default `false`; `true` exempts the key from note ownership) must be a boolean. A note entry's `skill` must be a string or a list of strings, `skill_mode` one of `invoke` or
   `method-on-diff` and only with a `skill`, and `lane` only on a review note with a known `output`; a trait's
   `description` and `applies_when` must be strings; a seat's `rules` must be a list of rule keys and its `skills` a list of non-blank strings. Under `MIGRATION`
   each of these is dropped with a warning. A seat's
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
likely), a seat entry named `orchestrator` or `coordinator`, a required note that no declared seat owns, and a `default_traits` list that places a seat-only model-selecting
trait after an earlier trait whose override for the same seat is not model-selecting (see "Seat dispatch precedence").

How a rejection reaches you:

| Surface | Rejection |
|---------|-----------|
| `config` tool `push` (MCP, or `POST /api/v1/ops/config`) | The element fails `config_invalid`, and `detail.errors` holds the `{path, reason}` list. The call answers it as every batch does (see [Batch answers](api-reference.md#response-envelope)): `422` on an atomic call, otherwise `207 partial_failure`, with the code and its detail shape in the [error catalog](api-reference.md#error-catalog). Advisory findings come back in a successful push's `warnings` and in `validate`'s `warnings`. |

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
or a different lifecycle mode); guidance and description edits do not. `sample` holds the first 20 of them as
`{itemId, changedNotes, schema?, lifecycle?}` objects (see the `config` `validate` section of `api-reference.md`).

`validate` also returns `lint`, a list of `{path, reason}` findings about schema quality. It is separate from `errors`
and `warnings`, never changes `valid`, and is `[]` for an invalid document, a parse failure and a rootId mismatch. `push`
does not compute or store it. Only content declared in the per-root document is reported; the server layer is read for
resolution and comparison only. The checks: a seat that owns no note of its phase; an optional note with no seat on a
schema or trait that declares seats (a required unseated note is reported in `warnings`); the same guidance on several
notes; a per-root type or trait that shadows a server one (with what differs); a trait with no `applies_when`; a seat
`rules` entry the root does not serve; a seat `skills` entry or note `skill` that names a served rule; on a schema or trait that declares seats, an optional
note (or a required trait note no schema here lists) whose `seat` no seat of its phase owns,
and an `independent_of` or seat `after` name no seat declares; a trait `dispatch` seat override no seat declares for its
phase. Every finding is
a point to consider, not a defect.

## Parse modes

The parser has two modes. They differ in two ways: the mode classifies a finding, and `STRICT` also rejects a mapping
key repeated at any depth, where `MIGRATION` keeps the last value so a stored version stays readable. Otherwise
extraction is identical: the same document comes out of the same input.

| Mode | A former soft skip is | Used for |
|------|-----------------------|----------|
| `STRICT` | an error | pushing and validating new config, and the boot import of the server file (with the allowance below) |
| `MIGRATION` | a warning | reading config that already exists: stored versions, canonicalizing the rows copied from 3.x, restoring a version on rollback |

Reading a stored version is never an admission point, so a root whose 3.x config carries the old top-level plugin
sections keeps working. Only a new push has to be clean. Advisory findings are warnings in both modes. The required-note-no-seat-owns advisory is not a parse finding: `validate` and `push` report it in `warnings`, computed over the merged schema, and a stored version read in `MIGRATION` mode never draws it.

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
key and whether the server or the root layer declared it; none when schema-free), that schema's `lifecycle`, and its
[`builds`](#schema-and-trait-builds) merged with the item's traits (none when schema-free).

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
  lifecycle-free item caused (the `invalid_transition` with `fromRole: "none"` and the `blocks`-edge endpoint
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
- [REST API Reference](api-rest.md), sections "Config DTOs", "Config ETags", "Endpoints - Config JSON Schema" and "Endpoints - Ops (RPC)", for the routes.
- [Workflow guide, Runs, Seats and Decisions](workflow-guide.md#12-runs-seats-and-decisions): how runs, seats and
  decisions use these keys.