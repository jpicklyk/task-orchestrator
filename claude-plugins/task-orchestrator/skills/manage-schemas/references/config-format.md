# Config Format Reference

The schema-authoring guide for `.taskorchestrator/config.yaml`: how to write note entries, traits, dispatch, seats, resources, independence and the plugin's own settings. What the server accepts, which key has which owner, how a push is validated and what a schema pin is are in [config-reference.md](../../../../../current/docs/config-reference.md) - this guide links there instead of repeating them.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- Commit this file (`/task-orchestrator:init` advises it); keep `.taskorchestrator/client.json`, which holds a machine-specific URL, out of git
- How an edit takes effect depends on how the file reaches the server (see "Global vs Per-Project Config" below):
  - **Per-root (config-sync)** - the `config-sync` hook pushes the file to the server for this project's root at SessionStart and again whenever the file changes (`config` `push`); a pushed config is hot-reloaded, with no restart or reconnect. A push is strict: any error rejects it and stores nothing
  - **Server file (mounted via `AGENT_CONFIG_DIR`)** - the server imports the file into its database once, at boot, so a change needs a server restart. Under STDIO transport an MCP reconnect (`/mcp`) starts a fresh server process and so re-imports it; under HTTP transport the container itself must be restarted

### Config discovery (plugin hooks and skills)

The plugin hooks locate the config in this order: (1) `AGENT_CONFIG_DIR` (a relative value resolves against the hook's working directory), (2) walking up from the working directory, (3) the main checkout of a linked git worktree (a bare repository's worktrees skip this step), (4) the user-level `<home>/.taskorchestrator/config.yaml`. Any hit equal to the user-level path is user scope, whichever step finds it (including `AGENT_CONFIG_DIR` set to the home directory); every other hit is project scope. A config sitting directly in a home directory is never a walk-up or main-checkout hit.

The REST `apiUrl` (never a token) resolves in this order: `TASK_ORCHESTRATOR_API_URL`, then `apiUrl` in a `client.json` beside the located config when its scope is project, then `apiUrl` in the user-level `client.json`; if none resolves the hooks no-op. The project-level file is written by project-mode `/task-orchestrator:init` and is honoured only as a bare loopback origin: an `http`/`https` URL without credentials whose host is `localhost`, an IPv4 address in `127.0.0.0/8` or `[::1]`, with an optional port and no path, query or fragment (a repo file must not redirect the bearer token to a remote host or choose the request path); any other value is ignored silently. A server behind a path prefix needs `TASK_ORCHESTRATOR_API_URL` or the user-level file. The user-level file, written by `init --user`, and the env var are unrestricted. In a linked worktree, when the `client.json` beside the worktree's own config yields no usable loopback URL, the hooks also read `.taskorchestrator/client.json` in the main checkout, under the same loopback rule. `AGENT_CONFIG_DIR` pins both files to the directory it names. The project-level `client.json` assumes a single-user machine: any directory above the working directory that holds a `config.yaml` and a `client.json` can point the hooks, with the bearer token, at a loopback port. On a shared host, set `TASK_ORCHESTRATOR_API_URL`, which wins over every file, and do not work under a directory another user can write.

`TASK_ORCHESTRATOR_HOME` replaces your home directory for Task Orchestrator: when it is set, the user-level `config.yaml` and `client.json` are read only from `$TASK_ORCHESTRATOR_HOME/.taskorchestrator/`, and `~/.taskorchestrator/config.yaml` is ignored unless `AGENT_CONFIG_DIR` points at it.

`TASK_ORCHESTRATOR_CEILING` is an optional directory at which project-config discovery stops climbing - like `GIT_CEILING_DIRECTORIES`. It exists mainly so tests stay isolated; leave it unset in normal use.

Because the user-level file applies in every directory that has no project config of its own, the `extensions.claude-code` settings placed there (see "Plugin settings") take effect everywhere that is unconfigured.

---

## YAML Structure

```yaml
work_item_schemas:

  your-schema-type:            # Matches items whose type field equals "your-schema-type"
    lifecycle: auto            # Optional: auto | manual | permanent | none (default: auto)
    default_traits:            # Optional: traits applied to every item matching this schema
      - needs-security-review
    notes:
      - key: note-key          # Stable identifier; kebab-case
        role: queue            # Phase: "queue", "work", or "review"
        required: true         # true = blocks the advance gate | false = shown but not enforced
        description: "..."     # Short label - read with items_read select schema, not in expectedNotes
        guidance: "..."        # Optional - read with items_read select schema; advance and items_read context return guidanceKey (a reference)
        skill: "review-quality" # Optional - skill to invoke when filling this note (shown as skillPointer)
        maxLength: 4000         # Optional - max body length (chars); enforced on every note write per note_limits.mode

traits:

  needs-security-review:       # Trait name - referenced by default_traits or an item's traits array
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        skill: "security-review"
        guidance: "Evaluate input validation, injection risks, access control..."
    resources:                  # Optional - resources this trait's items need at WORK entry (see below)
      - staging-db               # short form: bare key = mode: exclusive, no ttlSeconds override
      - key: github-deploy-token  # long form: explicit mode + ttlSeconds
        mode: advisory
        ttlSeconds: 1800

resources:                      # Optional registry, SERVER FILE ONLY - describes keys, does not declare usage
  staging-db:
    description: "Single shared staging database - only one migration/test run at a time"
    defaultTtlSeconds: 3600
    maxHolders: 1                # values > 1 are rejected ("not yet supported")
```

The other top-level keys (`note_limits`, `status_labels`, `independence`, `runs`, `decision_classes`, `project`, `extensions`) are described in "Key ownership" in [config-reference.md](../../../../../current/docs/config-reference.md#key-ownership). The legacy `note_schemas` form (a type name mapped to its notes, auto lifecycle) is still accepted but cannot share a document with `work_item_schemas`; write `work_item_schemas`.

---

## Field Reference

### Schema-level fields (`work_item_schemas`)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `lifecycle` | no | string | `auto`, `manual`, `permanent` or `none`, lowercase. Defaults to `auto` |
| `default_traits` | no | list | Trait names applied to every item matching this schema |
| `seats` | no | list | Named roles within a phase; see "Seats" below. A `lifecycle: none` schema may not declare any |
| `notes` | no | list | Note entries (below) |

### Note-level fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `key` | yes | string | kebab-case, unique within schema, stable after creation |
| `role` | yes | string | `queue`, `work`, or `review` only. A missing or invalid role rejects a push |
| `required` | no | boolean | Parser defaults to `false` if omitted - **always set explicitly**, since a note you meant to gate on silently becomes optional otherwise. `true` = blocks gate, `false` = shown but not enforced. A `lifecycle: none` schema's notes may not set `true` |
| `description` | no | string | Parser defaults to `""` if omitted (recommended in practice). Keep under 80 chars - quick label; read with `items_read` `schema`, not in `expectedNotes` |
| `guidance` | no | string | Project-specific authoring instructions - read with `items_read` `schema`; `advance` and `items_read` `context` return `guidanceKey` (a reference) |
| `skill` | no | string | Reusable evaluation framework - shown as `skillPointer` in `items_read` `context` |
| `maxLength` | no | integer | Max note body length (chars), at least 1. Enforced on every note write path (`notes_write` `upsert`, including `body` and `bodyFromFile`, and the notes of `items_write` `create_tree`) per top-level `note_limits.mode` |
| `seat` | no | string | Owning seat; see "Seats" |
| `independent_of` | no | list | Seats this note's authoring must stay independent of; see "Independence (A2)" |
| `shared` | no | boolean | Default `false`. `true` exempts the key from the note-ownership check: any caller allowed to write the item may upsert or delete it, and a shared-key write never changes the note's owner. Attribution (the principal and seat recorded on the note) is always the last writer's. Without it, a note is owned by the (principal, seat) pair that first wrote it, and any other pair's (the same principal under another seat included) `notes_write` upsert or delete is refused `note_owned_by_other`. Must be a boolean (a non-boolean rejects a push); an off-schema key is never shared. See [config-reference.md](../../../../../current/docs/config-reference.md#note-entry-shared) |

### `guidance` vs `skill` - when to use which

These fields serve different purposes and work together:

- **`guidance`** provides project-specific instructions for *what* the note should cover. It's free text you write in config - "Cover: problem statement, acceptance criteria, alternatives considered, blast radius, test strategy." The agent sees this as `guidanceKey` when it's about to fill the note.

- **`skill`** references a reusable evaluation framework that defines *how* to produce the note. The agent invokes the skill (e.g., `/spec-quality`) before writing, getting a structured methodology - rubrics, checklists, evaluation dimensions. The agent sees this as `skillPointer`.

| Combination | When to use |
|-------------|------------|
| `guidance` only | Most notes. You know what content you want - just tell the agent. |
| `skill` only | The skill is self-contained and doesn't need project-specific tailoring. |
| Both | The skill provides the structured process; guidance adds project-specific requirements the skill doesn't cover. The agent invokes the skill first, then uses guidance to fill in domain-specific details. |
| Neither | The note key and description are self-explanatory - no additional direction needed. |

### `skill` - exact-name rule

A `skill:` value must be the **exact name** the Skill tool accepts in the workspace where the
note gets filled - the Skill tool resolves strictly (no fuzzy matching, unlike a user typing a
slash command):

- **Plugin-shipped skills** (this plugin's `claude-plugins/task-orchestrator/skills/`) are
  **qualified**: `task-orchestrator:session-retrospective`, `task-orchestrator:review-quality`, etc.
- **Project-level skills** (`.claude/skills/`) and **Claude Code built-in skills** are **bare**:
  `review-quality`, `security-review`, `plan`, `review`, etc.

**Watch for built-in name collisions.** A short, generic `skill:` value can silently resolve to
the wrong built-in instead of your intended framework skill - especially `review` (Claude Code's
GitHub-PR review skill, not a note-quality framework), and also `plan`, `run`, `init`. If you want
a review-quality framework, point at the actual framework skill name (e.g. `review-quality`), not
the collision-prone generic word. `manage-schemas`'s `validate` operation flags known collisions
in this set - see `SKILL.md` -> VALIDATE.

### Rule text

A note entry's `skill` pointer doubles as more than a Skill-tool name: when it also names a rule
key, it is resolvable through the `config` tool's `rules` op (`itemId` + `noteKey`) as client-neutral
operating-rule TEXT, not just a skill invocation. This is a second, independent consumer of the same
`skill` field - no new schema field, no config syntax change.

- **Storage.** Rule text is stored per project root as a `rule` document (`kind: rule`, `slug` = the
  key) with the [`docs_write`](../../../../../current/docs/api-reference.md#docs_write) `upsert` op,
  which needs the `write-docs` capability (`write-config` does not grant it). The document's
  `contentHash` (SHA-256 of the LF-normalized body) doubles as the rule's `rulesVersion`. The 3.x
  routes that wrote and read rules are gone; [api-rest.md, section 10](../../../../../current/docs/api-rest.md#10-removed-3x-routes)
  maps each to its 4.0 op.
- **Serving.** The [`config`](../../../../../current/docs/api-reference.md#config) tool's `rules` op
  reads that store back, verbatim, never re-parsing or re-rendering the body: `rootId` alone lists
  `{key, rulesVersion, updatedAt}`, `rootId` with `key` returns one rule's body, and `itemId` with
  `noteKey` follows the item's effective schema to the rule under the item's root. It needs `read`
  and is orchestration-profile only. `docs_read` lists the same documents with `contentHash` and
  `version`.
- **`items_read` `schema` stays pointer-only.** Rule text is never inlined into a resolved schema's
  `skill`/`skillPointer` field - the caller always resolves the actual body through the `rules` op.
- **Key grammar.** `^[a-z0-9][a-z0-9._-]{0,99}$` - lowercase alphanumeric, `.`, `_`, `-`; must
  start with an alphanumeric; max 100 characters; never `/`, uppercase, or `:`. The `rules` op
  enforces it (`docs_write` checks only its generic slug rules), and it is the grammar a `skill:` value must
  match for it to resolve as a rule key - a `skill:` pointer that fails it still works as a
  Skill-tool name, it simply cannot also resolve through the `rules` op.
- **Size budget.** A `rule` document body is capped at **16384 UTF-8 bytes** (`payload_too_large`),
  tighter than the 65536-byte cap of a plan or page.
- **Writing style.** Client-neutral prose usable by any executor invoking the rule, not
  Claude-specific - with an optional trailing `## claude:` section for Claude Code-specific detail.
  The server never parses the body; it is served exactly as stored.
- **This repo's own rules.** This repository keeps its source rule text git-tracked under
  `.taskorchestrator/rules/<key>.md` - readable and diffable like any other file, and the
  authoritative source an agent edits. The `config-sync` SessionStart hook pushes these files
  automatically: after its usual config sync it lists the root's rule documents (`docs_read` `list`,
  `kind: rule`), hashes each workspace `rules/*.md` file (SHA-256 of the CRLF-normalized body), and
  compares against the server's per-key `contentHash`; a key whose local hash differs from (or is
  absent from) that listing is written with `docs_write` `upsert` (`expectedVersion` the listed
  version, or 0 when absent) and every other key is left untouched. A filename that doesn't match the
  key grammar above or a body over 16384 bytes is skipped client-side and named in the hook's output
  line instead of being sent. Like the config sync itself, the rule sync is fail-open - a `rules/` dir
  that's absent, or any error talking to the API, degrades to a no-op or a one-line note rather than
  blocking session start.
- **Bundled rules.** On SessionStart only (not on FileChanged) the hook also syncs the plugin's
  bundled rule keys (`protocol.entry-seat`, `protocol.in-phase-seat`, `protocol.read-only-agent`,
  `commit-discipline`, `review-scoping`), protocol keys first. `bundled-rules/manifest.json` is an
  append-only list of known body hashes per key, and the push policy follows it: a key absent from the
  server is pushed; a server copy matching a known older hash is overwritten with the current body; a
  server copy matching no known hash is never touched. A same-named file in the workspace's
  `.taskorchestrator/rules/` wins the key over the bundled copy. In user scope the pushed config is
  reported as "user config".
- **Shared deadline.** Config and rule requests share one budget measured from hook start (about 8 s on
  SessionStart, about 3.5 s on FileChanged). Work not finished in time is reported as
  `<N> deferred to next session`; an in-flight request aborted by the deadline counts as deferred, not
  failed.
- **Notice wordings.** `kept unrecognized server copy: <key>` appears at every session start for a
  server rule that was customized on purpose; to make it stop, put that rule in the workspace's
  `.taskorchestrator/rules/` so the workspace copy owns the key. `bundled rules skipped (unusable rules
  listing)` means the server's rule listing could not be read. `root <id> not found on this server`
  means the root was never created there.

---

## Lifecycle Modes

The `lifecycle` field on a schema controls automatic cascade behavior when children reach terminal:

| Mode | Behavior |
|------|----------|
| `auto` | Default - parent cascades to terminal when all children are terminal. A parent in the `review` role never cascades: the cascade is refused (`gate_blocked`, `review-pending`), and the parent leaves review only by an explicit `advance` |
| `manual` | Terminal cascade suppressed - parent must be explicitly completed. Reopen cascade also suppressed. |
| `permanent` | Parent never auto-terminates and never auto-reopens - always manual lifecycle. |
| `none` | Lifecycle-free: the item has no role at all (pages of knowledge or reference material). Notes may be declared but none required, and no seats; no trigger applies to it and it cannot be an endpoint of a `blocks` edge. See [Lifecycle-free items](../../../../../current/docs/config-reference.md#lifecycle-free-items) |

`lifecycle` values are lowercase. The removed `auto-reopen` value is an error in a push.

---

## Matching Rules

Schema resolution is **by type only, in one fixed order**, over the two config layers (the root's own
config and the server config; see "Global vs Per-Project Config"):

1. The root's schema for the item's exact `type`.
2. The server's schema for that `type`.
3. The root's schema named `default`.
4. The server's schema named `default`.
5. Nothing matches: the item is schema-free - no gate enforcement. An existing item with no `type` (pre-4.0) uses only steps 3 and 4.

**Key points:**
- Setting `type` on an item is how a schema is activated. Tags are never consulted.
- A `default` is the last resort in each layer, never a catch-all for new items. A create or retype must name a type one of the two layers declares (`default` counts) whenever either declares any, or it fails `schema_violation` with `detail.availableTypes`. A depth-0 create sees only the server's types. Steps 3-4 serve items typed `default`, and existing items whose type is no longer declared, when they are pinned or repinned. A root's `default` does not override a server type. To make a root gate-free, declare each type it uses with `notes: []`.
- Only one schema applies per item. Matching is exact and case-sensitive.
- The schema is pinned when the item is created, and again at the other pin points. A later push
  changes new and unpinned items only; an in-flight item keeps its pinned schema until `items_write`
  `repin` moves it to the heads. See [Schema pins](../../../../../current/docs/config-reference.md#schema-pins).
  If you are upgrading from 3.x, `schema_resolution` is gone and items keep the schema the 3.x
  rule gave them until a pin point fires.

---

## Trait Merge Semantics

How trait notes merge into an item's resolved schema (the one merge, `TraitResolver`):

1. **Trait order.** The item's own `traits` array (set on `items_write` `create` or `update`) first,
   then the schema's `default_traits`, deduplicated with the first position kept. Every dimension
   (notes, seats, resources, dispatch) uses this one order.
2. **Notes merge by key, base first.** The final list is the base schema's notes, then the surviving
   trait notes in trait order, one entry per key. A key's content (role, description, guidance,
   skill, `maxLength`, `seat`, `independent_of`) comes from its **first** declaration - the base
   schema's if it has the key, else the first trait that declares it.
3. **`required` can only tighten.** A key is required when **any** declaration of it is `required:
   true`. A trait cannot make a base note optional, and it cannot change a base note's role.
4. **Trait notes append after base notes.** Order is base notes, then trait notes in trait order.
5. **A per-root trait entry replaces the server's entry as a whole.** When the root's `traits:` map
   contains the trait's name, its notes, seats, resources and dispatch all come from that entry, and
   a dimension the entry does not declare is empty (see "Global vs Per-Project Config"). Restate
   everything you want to keep.
6. A create or update naming a trait neither layer declares fails `schema_violation` (`detail.availableTraits`) whenever any trait is declared; a trait that stops being declared after the write is skipped with a server-side warning.

**Example - the base role wins, `required` ORs:**

```yaml
work_item_schemas:
  feature-task:
    default_traits: [needs-task-review]
    notes:
      - {key: implementation-notes, role: work, required: false}

traits:
  needs-task-review:
    notes:
      - {key: implementation-notes, role: review, required: true}   # key exists: role stays "work", required becomes true
      - {key: review-checklist, role: review, required: true}       # added
```

---

## Resources (Trait Dimension)

A trait can declare `resources:` - a list of shared-resource requirements enforced by the server as
a **gate at WORK entry** (`advance` `transition` with `start` from queue, and `resume` from
blocked - both re-enter WORK). This is independent of the note-requirement dimension: a trait can
carry notes, resources, both, or neither.

### Declaration forms

```yaml
traits:
  needs-staging-slot:
    resources:
      - staging-db                     # short form - bare string
      - key: github-deploy-token       # long form - explicit fields
        mode: advisory                 # exclusive (default) | advisory
        ttlSeconds: 1800                # optional; overrides the registry default for this trait
```

| Field | Required | Default | Notes |
|-------|----------|---------|-------|
| `key` | yes | - | Resource key. Same charset as `credentialRefs`: `^[a-z0-9][a-z0-9\-_./]*$`, max 128 chars. An invalid key rejects a push. |
| `mode` | no | `exclusive` | `exclusive` - server takes a lease at WORK entry; contention rejects the transition. `advisory` - no lease, no lock; the key is recorded into the transition's `consumedCredentials` audit trail only. Lowercase only. |
| `ttlSeconds` | no | registry's `defaultTtlSeconds`, else `3600` | Must be a whole number; applied clamped to `[1, 86400]`. |

### The top-level `resources:` registry (server file only)

```yaml
resources:
  staging-db:
    description: "Human-readable note - what this key protects"
    defaultTtlSeconds: 3600    # whole number 1-86400
    maxHolders: 1              # v1 only supports 1 - see below
```

The registry belongs to the server file: a per-root document that carries `resources:` is rejected,
because a resource key is a server-global lock/lease namespace. It holds TTL defaults and a
description per key. A trait's `resources[].key` must be declared in the server registry: an undeclared
resource key is rejected by a push and by the boot import. `maxHolders` greater than `1` is an error
(storage is semaphore-ready for a future multi-holder release, but admission is capped at exactly 1).
Reserved budget fields (`budgetLimit`, `budgetWindowSeconds`) are errors: a push and the boot import reject them on a registry entry and on a trait's resource entry.

### Merge semantics - read this before combining traits

Resource requirements merge differently from notes - do not assume the note merge rules apply here.

1. **Union across traits, never dropped.** Two traits declaring the *same resource key* both
   contribute; the merge is a union, deduplicated by key.
2. **On a mode conflict for the same key, `exclusive` wins.** If one trait declares a key
   `advisory` and another (applied to the same item) declares it `exclusive`, the merged result is
   `exclusive`, whatever the order. `ttlSeconds` for a duplicate key keeps the **first-seen** value in
   trait order (item `traits` first, then the schema's `default_traits`).
3. **Fan-out warning.** A key declared by three or more traits draws an advisory warning on a push
   (contention is likely). Scope keys to the project (`proj-a/staging-db`) so two projects that pick the
   same generic name for different resources do not falsely serialize against each other.

### LEAF TASK TYPES ONLY for exclusive resources - a hard rule, not a suggestion

**Declare `mode: exclusive` resources on leaf task-level schemas only - never on a container/feature
schema (`lifecycle: manual` or `permanent` root containers, or any type with children).** The lease
is held for the ENTIRE time the declaring item sits in WORK. A feature container commonly stays in
WORK for the full duration of its child tree's implementation - hours to days. If a container-level
schema (or a container item's own `traits`) declares an exclusive resource, that resource is locked
for the container's whole WORK lifetime, not just for whichever child task actually needs it -
starving every other item that needs the same key for as long as the feature is in flight. This is
almost never the intended effect. Put the exclusive declaration on the leaf task type/trait that
actually performs the resource-touching work; use `advisory` (or no declaration at all) on container
types if you want audit visibility without the lock.

### Interaction with `credentialRefs`

When an item that declares resources via its traits' `resources:` enters WORK with the lease gate active, the `credentialRefs`
field on that transition is a **closed set** (its declared keys plus the registry keys) - each supplied ref must
name a declared/registered key, or the transition is rejected, naming the unrecognized ref and the
known-key list. Declared keys (both `exclusive`, once acquired, and `advisory`) are
**auto-recorded** into `consumedCredentials` on work entry - you do not need to repeat them in
`credentialRefs`. Items that declare no resources are unaffected: `credentialRefs` there stays an
open, format-only-validated field. See [`api-reference.md`](../../../../../current/docs/api-reference.md)
for the full `credentialRefs` contract and [`workflow-guide.md`](../../../../../current/docs/workflow-guide.md#11-resource-leasing)
for the contention/retry model and the guarantees-vs-non-guarantees statement.

---

## Dispatch (Trait Dimension)

A trait can declare `dispatch:` - a map of workflow phase -> **dispatch profile**, read by an
orchestrator (this plugin's orchestration context and `orchestrate` skill, and orchestrator workflows such as a project's
implementation skill) to decide which agent type, model, and thinking effort to dispatch for the
phase owner. Like `resources:`, this is
independent of the note-requirement dimension - a trait can carry `notes`, `resources`, `dispatch`,
any combination, or none.

### Declaration form

```yaml
traits:
  delegated:
    dispatch:
      work:   { agent: task-orchestrator:implementer }
      review: { agent: task-orchestrator:reviewer, effort: high }
```

| Field | Required | Notes |
|-------|----------|-------|
| Phase key | - | One of `queue`, `work`, `review` only - exact lowercase match. Any other key (`terminal`, `blocked`, `Work`, an unrecognized string) rejects a push. |
| `agent` | no* | Opaque string passed as `subagent_type` - never validated against any registry. |
| `model` | no* | Opaque string passed as the Agent tool's `model` parameter. |
| `effort` | no* | One of `low`, `medium`, `high`, `xhigh`, `max`, matched **case-sensitively** (`High` is invalid). Has no Agent-tool parameter of its own - see "The Claude Code note" below. |

\* At least one of `agent` / `model` / `effort` must be present (a phase map may instead carry only
`seats:`, see "Seats"). An empty profile, an unknown profile field, or a non-string or blank field
value rejects a push and the boot import. Documents stored before 4.0 are still read leniently: a
malformed entry is skipped with a warning at its own granularity and the rest of the trait still parses.

### Precedence - read this before combining traits

Resolving an item's dispatch profile for a phase walks trait names in the one trait order (item `traits`
first, then the schema's `default_traits`, deduplicated), the same order notes, seats and resources use.

The **first trait in that order that has a profile for the requested phase wins outright - the
whole profile, never merged field-by-field across traits.** If a per-item trait A declares
`work: {agent: X}` and a `default_traits` trait B declares `work: {agent: Y, effort: high}`, the
resolved profile is exactly `{agent: X}` - B's `effort: high` is never folded in. "First trait wins" is
a statement about **phase-level** profiles only.

Per-seat overrides follow their own rule: for a `(phase, seat)` pair a model-selecting override from a
seat-only trait outranks the other overrides whatever the order, and otherwise the first override in
trait order wins (see "Seats", merge rule 6, and
[Seat dispatch precedence](../../../../../current/docs/config-reference.md#seat-dispatch-precedence)).

### Per-root layering

A per-root trait entry replaces the server's entry of the same name **as a whole**: its `dispatch:`
map replaces the server trait's map, and there is **no per-role fall-through**. If a per-root trait
declares `work:` but omits `review:` and the server trait declares both, the resolved `review` profile
is **absent** for items in that root. The same holds for `notes`, `seats` and `resources`: a dimension the
per-root entry omits is empty, not inherited (see "Global vs Per-Project Config"). Restate every dimension
and every phase you want to keep.

### Where the profile surfaces

| Surface | Shape |
|---------|-------|
| `advance` `transition` result | `dispatch: {agent?, model?, effort?}` for the item's **new** role; the key is omitted entirely (never `null`/`{}`) when nothing resolves |
| `items_read` `context` (item mode) | `dispatch: {agent?, model?, effort?}` for the item's **current** role |
| `items_read` `schema` | `dispatch: {"queue"\|"work"\|"review": {agent?, model?, effort?}}` - one entry per resolved phase |
| `config` `effective` | each trait carries its `dispatch` (and `resources`) when declared; with a `rootId` the view is that root's config layered over the server's, without it the server's alone |

### The Claude Code note - effort only via agent frontmatter

`effort` has no parameter on the Agent tool itself - Claude Code applies effort only through the
dispatched agent definition's own frontmatter (`effort: low|medium|high|xhigh|max`). This holds
**even when `agent` is also set**: Claude Code never forwards a profile's `effort` value to the
dispatch call, so the effort that actually applies is whatever the named agent definition's own
frontmatter declares, not necessarily the value the profile states. A profile's `effort` is
therefore always advisory in Claude Code; to make a particular effort actually apply, point
`agent` at a definition whose own frontmatter carries that `effort` - a profile naming neither
`agent` nor a matching definition has nothing to attach its `effort` to at all. Clients that call
the model API directly, rather than dispatching through Claude Code's Agent tool, may apply a
profile's `effort` field themselves. The plugin's agent definitions declare `effort` in their frontmatter (see `agents/`)
and use `model: inherit` there, which is exactly why the
caller must still pass `model` explicitly on every dispatch of the phase owner - `dispatch.model`
when the profile sets one, otherwise its own model choice. See
[`orchestrate`](../../orchestrate/SKILL.md) -> Dispatching an item's phase owner for the
consumer-side rule, which applies in both orchestration modes and is followed identically by
`schema-workflow` and orchestrator workflows such as a project's implementation skill outside
this plugin.

---

## Seats (Trait & Schema Dimension)

A schema or trait can declare `seats:` - a list of named orchestration roles within a phase (A1). A
seat says which agent owns a phase's entry transition, which agent owns each required note, and which
dispatch profile (agent/model/effort) applies to it - without the orchestrator keeping that mapping
outside the config file. Seats are served to clients as an orchestration signal (`items_read`
`context`, `items_read` `schema`). On an item that is a member of a run that is not closed and whose
pinned schema declares seats, the server also **enforces** them: a seat token may send only the
transitions its seat allows and write only its own seat's note keys (see "Seat transitions" below and
[Seat transitions](../../../../../current/docs/config-reference.md#seat-transitions)). A config with no
`seats:` anywhere is unaffected: no seat-related field appears in any response.

### Declaration form

```yaml
work_item_schemas:
  bug-fix:
    default_traits: [needs-test-author]
    seats:                                    # schema-level, list
      - { name: planner,      phase: queue }
      - { name: implementer,  phase: work, enters: true, transitions: [block, hold, resume] }
      - { name: extractor,    phase: work, after: [implementer] }
      - { name: orchestrator, phase: work }
      - { name: reviewer,     phase: review }
    notes:
      - { key: diagnosis, role: queue, required: true, seat: planner }
      - { key: implementation-notes, role: work, required: true, seat: implementer }
      - { key: session-tracking, role: work, required: true, seat: orchestrator }

traits:
  needs-test-author:
    seats:                                    # trait-level
      - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
    notes:
      - { key: test-plan, role: queue, required: true, seat: planner }
      - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }

  delegated:
    dispatch:
      work:
        agent: task-orchestrator:implementer  # phase-level profile
        seats:                                # per-seat overrides
          test-author: { agent: task-orchestrator:test-author, model: sonnet }
          extractor:   { agent: null, model: sonnet, effort: low }   # agent: null CLEARS the phase agent for this seat
```

### Seat-entry fields (`seats[]`, schema-level or trait-level)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `name` | yes | string | Non-blank, unique within its declaring scope (see "Structural errors" below). Seat names carry no meaning for the server, except the reserved bucket name `unowned` (see "The `unowned` bucket"), which a seat may never be named, and the seat named `orchestrator`, whose note keys a run's coordinator token may write (see "Seat transitions"). |
| `phase` | yes | string | `queue`, `work`, or `review` only - exact lowercase match, same set as note `role`. |
| `enters` | no | boolean | Default `false`. Whether this seat is the one that transitions the item **into** `phase`. At most one seat may set this `true` per phase within one seats scope - see "Structural errors". A conflict discovered only at resolve time (per-item traits, or traits from a different config layer) is not fatal - see "Merge rules". |
| `transitions` | no | list | The triggers other than `start` that a token for this seat may send: `complete`, `block`, `hold`, `resume`, `cancel`, `reopen`. Absent: `block` and `hold`. A non-list or an unknown value rejects a push. See "Seat transitions". |
| `after` | no | list of strings | Seat names this seat's work logically follows - an ordering **hint**, not enforced by the server. A name outside the declaring scope, or naming a seat in a different phase, is served exactly as declared. |
| `reads_exclude` | no | list of strings | Note keys this seat should **not** read - a hint powering test-author-style blindness. Parsed and served only; blindness is a process discipline, not a server-side check. Separate from a note entry's own `independent_of`. |

### Note-entry fields (on `notes[]`)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `seat` | no | string | The seat (by name) that owns this note. Determines which bucket a missing required note lands in under `missingBySeat` - see "The `unowned` bucket". A note with no `seat`, a `seat` naming an undeclared seat, or a `seat` declared for a *different* phase than the note's own `role`, is `unowned`. |
| `independent_of` | no | list of strings | Seat names this note's authoring must stay independent of (e.g. a test-author's `test-manifest` staying independent of the `implementer` seat). Enforced by the attestation gate - see "Independence (A2)". |

### Seat transitions

A seat token sends `start` only into the phase of a seat that sets `enters: true`; every other trigger
it sends must be in its `transitions:` list (default `block`, `hold`). An entering seat that lists
`complete` or `cancel` is a structural error (F5), so the seat that opens a phase cannot also close the
item. A seat named `orchestrator` is the one seat whose note keys a run's coordinator token may write; every other
seat-owned key needs that seat's own token (a human credential is exempt). Enforcement applies only to
an item that is a member of a run that is open or paused, and a refused transition is `seat_forbidden`, and a refused note write is `note_owned_by_other` with `detail.ownerSeat`; outside a
run the seats stay a signal. The full rules, and the interaction with run membership, are in
[config-reference.md, Seat transitions](../../../../../current/docs/config-reference.md#seat-transitions).

### Per-seat dispatch overrides (`dispatch.<phase>.seats.<seat>`)

A trait's existing `dispatch:` block (see "Dispatch (Trait Dimension)" above) can carry a `seats:`
sub-key under any phase, overlaying a per-seat override on top of that phase's dispatch profile:

```yaml
traits:
  delegated:
    dispatch:
      work:
        agent: task-orchestrator:implementer
        seats:
          test-author: { agent: task-orchestrator:test-author, model: sonnet }
          extractor:   { agent: null, model: sonnet, effort: low }
```

| Field | Required | Notes |
|-------|----------|-------|
| `agent` / `model` / `effort` | no | Same types and validation as the phase-level fields (`effort` is validated against the same case-sensitive set). Each field independently either overrides the phase default, **clears** it (an explicit YAML `null` for that field), or - when absent from the override entirely - falls through to the phase default. A blank string is invalid, not a clear. |

A phase map that carries **only** `seats:` (no `agent`/`model`/`effort` of its own) is valid - it
declares no phase-level profile, but its seat overrides still apply on top of whatever phase
default resolves from elsewhere (another trait, or none). An override map with no field set at all
(nothing overridden, nothing cleared) rejects a push.

### Merge rules

Seats merge alongside notes when a trait's seats combine with a schema's base seats, in the one trait
order (item `traits` first, then `default_traits`; see "Trait Merge Semantics"):

1. **Base-schema seats always win by name.** If a trait declares a seat with the same `name` as one
   already in the schema's own `seats:` list, the trait's seat is dropped with a server-side warning.
   (A trait may *add* seats; it may not *redefine* a base seat.)
2. **First-trait-in-order wins on a duplicate seat name across traits.** The earlier trait keeps its
   seat; a later trait's same-named seat is dropped with a warning.
3. **Merged seat order is base seats, then surviving trait seats in trait order** - the order
   `missingBySeat` and the served `seats`/`dispatchBySeat` fields use.
4. **A second `enters: true` seat landing in a phase that already has one is demoted, not
   rejected.** A conflict inside one declaring scope is a structural error (F1); one that only appears
   once traits are merged at resolve time (a per-item trait, or a trait from a different config layer)
   is resolved deterministically: the seat that already has the phase keeps `enters: true`, the later
   seat is served with `enters` demoted to `false`, and a warning is logged.
5. **Note `seat` / `independent_of` ride on the note merge rules** (see "Trait Merge Semantics").
6. **Per-seat dispatch overrides resolve in the same trait order, with one exception.** For a given
   `(phase, seat)` pair the first trait in order that declares an override wins outright, except that a
   **model-selecting** override outranks the rest regardless of order; its fields overlay the phase's
   already-resolved dispatch profile (`agent`/`model`/`effort` field by field, an explicit `null`
   clearing that field). The winner replaces the others whole (no field merge). The result is
   **filtered to the seats present in the resolved schema's merged seats for that phase** - an override
   naming a seat the schema doesn't declare for that phase never surfaces. **A seat with no override of
   its own inherits the phase's already-resolved default profile as-is**; it is omitted only when that
   phase has neither a default profile nor an override for it.

   A *seat-only* trait is one whose declaring layer has no phase-level `dispatch.<phase>` profile in
   any phase (only `seats:` entries), such as `complex-implementation` or `high-stakes`. Its override is
   *model-selecting* when it sets `model` and neither sets nor clears `agent`. So
   `[delegated, complex-implementation]` resolves the implementer to the same model as
   `[complex-implementation, delegated]`. Known limitation: a seat-only model override for a seat that
   `delegated` gives `agent: null` (such as `test-author`) drops that `agent: null`. The validator warns
   on a `default_traits` list that places a seat-only model-selecting trait after an earlier trait that
   overrides the same seat without selecting a model; the warning is a migration aid and never rejects a
   push. See [Seat dispatch precedence](../../../../../current/docs/config-reference.md#seat-dispatch-precedence).

### Per-root layering

Seats and per-seat dispatch overrides are two dimensions of a trait, and a per-root trait entry replaces
the server's entry **whole** (see "Global vs Per-Project Config"): if the root's `traits:` map contains
the trait's name, its seats, its dispatch (phase-level profiles and per-seat overrides together), its
notes and its resources all come from the root's entry, and a dimension it omits is empty. A *base
schema's* own seats travel with whichever layer supplied the base schema (see "Matching Rules"); there
is no separate cross-layer seat merge for a schema's own (non-trait) seats.

### Structural errors (F1-F5) - reject the push and fail the boot import

These are checked over one "seats scope" at a time: a single `seats:` list (a schema's own, or a
trait's own), or - for a schema that names traits in its own `default_traits`, when both are defined in
the **same document** - the union of the schema's own seats with those same-document default traits'
seats. They are fatal in every parse mode, and each is reported at the path of the offending `seats`
list. A push carrying one fails `config_invalid` and stores nothing; the server file fails startup.

| Code | Condition |
|------|-----------|
| F1 | Two seats with `enters: true` in the same `phase`, within one seats scope. |
| F2 | A duplicate seat `name` within one seats scope. |
| F3 | A seat named `unowned` (the reserved bucket name - exact match only; `Unowned` is fine). |
| F4 | An `after` cycle within one seats scope. |
| F5 | A seat that sets `enters: true` and lists `complete` or `cancel` in `transitions`. |

A conflict that only appears **at resolve time** is never fatal; see "Merge rules" rule 4. Other
malformed seat entries - a missing or blank `name`, an invalid or missing `phase`, a non-boolean
`enters`, a non-list `after`/`reads_exclude`/`transitions`, an unknown key - are ordinary strict errors
(see [Strict validation](../../../../../current/docs/config-reference.md#strict-validation)).

An advisory warning, which never rejects a push, covers a schema whose effective seats are non-empty
and which has a **required** note whose `seat` is null or names no seat of the same phase: the note is
served under `unowned` in `missingBySeat`.

### The `unowned` bucket

A required note that has no seat owning it - no `seat` declared, an unknown seat name, or a `seat`
declared for a different phase than the note's own `role` - is served under the reserved
`"unowned"` key in `missingBySeat` (see `api-reference.md` and `api-rest.md` for the response shape).
This is deliberate, not an error: it lets an orchestrator see at a glance which missing notes have no
seat routing at all, so a schema can be seat-aware for *some* notes while others stay unowned.
`unowned` buckets are always sorted last among non-empty buckets; the reserved name itself can never be
used for a real seat (F3).

---

## Phase Flow

```
queue --(start)--> work --(start)--> review --(start)--> terminal
```

- If the schema has NO `role: review` notes, `start` from work goes directly to terminal (review is skipped)
- Required notes for the *current* phase gate the `start` trigger
- Optional notes (`required: false`) are shown but do not block advancement

---

## Design Principles

- **Keep it lean.** 2-3 required notes per phase is the practical maximum before agents find workarounds
- **Queue gates = pre-work contract.** Requirements, design, root cause - filled before agents touch code
- **Work gates = evidence of completion.** Implementation notes, test results - filled after implementation
- **Review phase is optional.** Only add `role: review` notes for genuine deploy/verify/sign-off steps
- **Optional notes are reminders.** `required: false` notes appear in `expectedNotes` without blocking
- **Use `type` for schema selection, tags for categorization.** Tags never select a schema.

---

## Multiple Schemas

```yaml
work_item_schemas:

  schema-one:
    lifecycle: auto
    notes:
      - key: ...

  schema-two:
    lifecycle: manual
    notes:
      - key: ...
```

Each schema is independent. An item matches at most one schema.

---

## Project Scoping

The `project:` section is a top-level key alongside `work_item_schemas` and `traits`. It anchors this repository's work to a single depth-0 MCP item.

```yaml
project:
  rootId: "<uuid>"
  name: "<project name>"

work_item_schemas:
  # ...
```

Default: absent - a workspace with no `project:` block is unscoped.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `rootId` | yes | string (UUID) | UUID of the depth-0 item (`type: project`) that anchors this repo's work. The hooks use it only when it is 1 to 64 letters, digits or hyphens and does not start with a hyphen (a UUID qualifies); any other value is treated as if no `rootId` were set |
| `name` | no | string | Human-readable project name shown in dashboards and skill output |

### Behavior

- **Honored per-root.** When the full config text is pushed per-root (`config` `push`), the embedded `project.rootId` is checked as a mismatch guard against the target `rootId`: a different value is rejected unless `force`. The server file's `project:` is not config; the one exception is that a server file whose `project.rootId` names a depth-0 root is imported as that root's config, held to the per-root rules, and a malformed or unknown `rootId` fails startup (see [Parse modes](../../../../../current/docs/config-reference.md#parse-modes)). Locally, the block is read by Claude Code: the SessionStart hook and plugin skills use it to scope their output to `rootId`.
- **Created by** `/task-orchestrator:init` or `/adopt-project-scope` when the user opts into anchoring session context to a single project root item.
- **Personal root.** `/task-orchestrator:init --user` writes a `project:` block into the user-level file whose root is a `type: project` item tagged `personal-root`. It is anchor-only: new items are parented under it, but reads stay unscoped (no `ancestorId`/`anchorId`), so the dashboard may show other projects' items.
- **Opt-in convention.** Scoping is not enforced - its absence just means skills operate without a default root anchor, falling back to unscoped behavior.
- The same scoping is pushed server-side so it is visible beyond this local config file. In practice this push is triggered automatically by `init` (P5 for a project, U3 for a personal root) and by `manage-schemas`' write-operation report step (Step 4) whenever a `project.rootId` is present - both validate and push the full config file text, not just this block.

**Example:**

```yaml
project:
  rootId: "3f9a1c2e-8b4d-4a11-9c3f-2d6e7a8b9c0d"
  name: "Task Orchestrator"

work_item_schemas:
  feature-task:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "What was built and why"
```

> **manage-schemas write operations must preserve this block untouched** - see the create/edit/delete workflow docs in this skill folder.

---

## Plugin settings (`extensions.claude-code`)

The Claude Code plugin keeps its own settings under `extensions.claude-code`. The server stores
`extensions` without reading it (it must be a mapping), so these keys can change without a server
release, and an edit takes effect on the next hook fire, with no reconnect. A top-level copy of any of
them (`orchestration:`, `retrospective:`) is rejected by a push and only warned about at server boot
(see [config-reference.md, `extensions`](../../../../../current/docs/config-reference.md#extensions)). `extensions` and `claude-code` must be block mappings; the hooks ignore a flow-form (`{...}`) block there, while the leaf section may be inline.

```yaml
extensions:
  claude-code:
    orchestration:
      mode: workflow
    retrospective:
      mode: dispatch
      dispatchThreshold: 3
      github_feedback:
        enabled: true
        repo: jpicklyk/task-orchestrator
```

| Key | Values | Default | Read by |
|-----|--------|---------|---------|
| `orchestration.mode` | `workflow`, `schema`, `off` | `workflow` | the orchestration hooks (see "Orchestration") |
| `retrospective.mode` | `nudge`, `dispatch`, `off` | `nudge` | the retrospective hooks (see "Retrospective") |
| `retrospective.dispatchThreshold` | integer >= 0: items terminal in the closed run | `3` | the retrospective hooks, in `dispatch` mode |
| `retrospective.github_feedback.enabled` | boolean | `false` | the `session-retrospective` and `review-proposals` skills |
| `retrospective.github_feedback.repo` | `owner/repo` | `jpicklyk/task-orchestrator` | the same skills |

A retrospective is owed per closed run of the project root that is not yet marked `retro`; the hooks
decide by that run-mark, not by a cooldown. The `session-retrospective` skill describes the run-mark
trigger in full ([session-retrospective](../../session-retrospective/SKILL.md)). There is no
`cooldownMinutes`, no `headless` mode and no `actor_attribution` setting. The user-level config's
`extensions.claude-code` applies in every directory that has no project config.

---

## Retrospective

The `retrospective` settings control how the plugin's hooks behave when a run closes - whether they merely suggest running `/session-retrospective`, or actively direct an agent to dispatch one in the background. They live at `extensions.claude-code.retrospective`:

```yaml
extensions:
  claude-code:
    retrospective:
      mode: nudge          # nudge (default) | dispatch | off
      dispatchThreshold: 3 # items terminal in the closed run required to auto-spawn (default 3)
      github_feedback:                     # optional
        enabled: true                      # default false - opt-in
        repo: jpicklyk/task-orchestrator   # optional; this is the default
```

Default: `nudge` - applied when the block or `mode` key is absent, or the value is unrecognized.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `nudge`, `dispatch` or `off`. Defaults to `nudge` |
| `dispatchThreshold` | no | integer >= 0 | Only meaningful in `mode: dispatch`. The count of items terminal in the closed run required before a hard background dispatch fires. Below this, the run still gets a nudge - it is never silent. Defaults to `3`; a non-integer or negative value falls back to the default. |
| `github_feedback.enabled` | no | boolean | Opt-in. When `true`, the session-retrospective skill files a GitHub enhancement issue for **global-scoped** improvement proposals via the `gh` CLI. Defaults to `false`. Requires `gh` installed and authenticated; filing is skipped gracefully otherwise. |
| `github_feedback.repo` | no | string | `owner/repo` target for filed issues. Defaults to `jpicklyk/task-orchestrator` (the plugin's upstream). |

### Modes

| Mode | Behavior |
|------|----------|
| `nudge` (default) | Hooks inject a suggestion to run `/session-retrospective` when a run closes. The agent decides whether to act on it. |
| `dispatch` | Hooks inject a directive that launches exactly one background retrospective agent per closed run, but only once that run's substance (items terminal in it) clears `dispatchThreshold`; the hook first writes the run's `retro` mark (the first write wins, so two sessions never both dispatch). Below threshold, the hook still injects the `nudge` suggestion instead of staying silent. The directive is **durable, not immediate**: it instructs the orchestrator to dispatch at the next run boundary - holding it while background tasks or subagents are still in flight, and merging any directives that accumulate while holding into a single dispatch covering the union of their roots. |
| `off` | Hooks stay silent - no retrospective suggestion or dispatch directive is injected. |

**The Stop backstop.** The `Stop` hook (`retro-backstop.mjs`) runs the same closed-run evaluation as the trigger, so a run closed elsewhere (another session, a subagent) still gets its retrospective. With a REST API it may dispatch (after winning the `retro` mark) as well as nudge, per the configured mode. Without REST it can only nudge, once per session, about runs this session saw close, and never marks or dispatches.

### Behavior

- **Read by hooks, not the server.** The block is read directly from the workspace file by the plugin hooks (`retro-trigger.mjs`, `retro-backstop.mjs`) - the MCP server stores `extensions` without interpreting it.
- **No hot-reload needed.** Because hooks read the file fresh on every fire, an edit takes effect on the next hook trigger - no `/mcp` reconnect required.
- **`project.rootId` required for runs.** The hooks list the closed, unmarked runs of the project root, so they need the `project.rootId` of the located config.
- **`github_feedback` is skill-read, not hook-read.** The nested block is read by the skills (`session-retrospective`, `review-proposals`) as plain YAML - the hooks' scalar reads are unaffected by its presence.
- **Held directives can be lost - known trade-off.** A directive the orchestrator is holding for a run boundary exists only in conversation context. If the session is killed or the context compacts before the last background task completes, that retrospective is skipped for the session; recover by running `/session-retrospective` manually. Dispatched retrospectives work only from durable MCP state, never conversation context.

---

## Orchestration

The `orchestration` setting selects how much orchestration behavior the plugin hooks inject into a session. It lives at `extensions.claude-code.orchestration`:

```yaml
extensions:
  claude-code:
    orchestration:
      mode: workflow   # workflow (default) | schema | off
```

Default: `workflow` - applied when the block or `mode` key is absent, or the value is unrecognized.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `workflow`, `schema`, or `off` (case-insensitive, quotes optional). Defaults to `workflow`; any other value falls back to the default. |

### Modes

| Mode | Behavior |
|------|----------|
| `workflow` (default) | Full behavior: tier classification, delegation table, and dispatch rules. |
| `schema` | No tiers and no model table; the item's resolved note schema alone drives the process. |
| `off` | Every orchestration hook exits silently. |

### Behavior

- **Read by hooks, not the server.** Read directly from the workspace file by the plugin hooks `orchestration-context.mjs` (SessionStart), `dispatch-model-guard.mjs` (PreToolUse on Agent), and `dispatch-hint.mjs` (PostToolUse on `advance`). The server stores `extensions` without interpreting it.
- **Also inert** when no config is located, and in headless ralph iterations (ralph receives its rules through `--append-system-prompt-file`).
- **Read fresh on every hook fire**, but the SessionStart context only changes at the next session start, `/clear`, or `/compact`.
- **`off` is a plain string.** The plugin reads it as text, so YAML 1.1 boolean coercion does not apply; quoting is optional.
- **manage-schemas write operations must preserve `extensions` untouched.**

---

## Global vs Per-Project Config

Config resolves in **two layers**, chosen per work item by its `rootId`:

| Layer | Where it lives | Reload | Scope |
|-------|----------------|--------|-------|
| **Server** | the `AGENT_CONFIG_DIR/.taskorchestrator/config.yaml` file, imported into the database | imported once at server boot (restart to reload) | one per server - the **fallback/default** |
| **Per-root** | pushed into the database per project-root UUID (`config` `push`, or `POST /api/v1/ops/config`) | **hot-reloaded** - no restart | one per project root |

A push is **strict**: it validates the whole document (`config` `validate` runs the same pipeline without
writing) and any error rejects it with `config_invalid` and a `{path, reason}` list; nothing is stored. Through the `config` tool a push is one element of a non-atomic call: the call-level error is `partial_failure`, and `config_invalid` with `detail.errors` is at `data.results[0].error`.
The server file is held to the same rules at boot, with a narrow allowance for the 3.x plugin sections and `schema_resolution`
(see [Parse modes](../../../../../current/docs/config-reference.md#parse-modes)); any other error fails
startup. Which top-level key may appear in which layer is in
[Key ownership](../../../../../current/docs/config-reference.md#key-ownership).

### How the layers combine

One precedence rule: a specific declaration beats a generic one, and the root beats the server at the
same level.

| Facet | Rule |
|-------|------|
| Schema for a type | root exact type, server exact type, root `default`, server `default`, none (see "Matching Rules") |
| Trait | when the root's `traits:` map contains the name, notes, seats, resources and dispatch all come from the root's entry (a dimension it omits is empty); otherwise all from the server's |
| `resources:` registry | server only; a root document that carries it is rejected |
| `note_limits.mode` | the root's explicit value, else the server's |
| `independence` | the root's block wins whole (`mode` and `require_verified` together), else the server's |
| `runs` | per field: root value, else server, else default |
| `decision_classes` | per class name: a root entry replaces the server entry of that name |
| `status_labels` | per trigger: the root's value (an explicit `null` included) wins, else the server's |

---

## Note Body Length Limits

Top-level `note_limits.mode` (`warn`, default, or `reject`) governs what happens when a note body exceeds its schema `maxLength`, checked in characters on every note write path (`notes_write` `upsert` and the notes of `items_write` `create_tree`) against the resolved body after CRLF-to-LF normalization (inline `body` or file-read via `bodyFromFile`): `warn` accepts the note with a `warning` field on its result; `reject` fails that note with `note_too_long`. Independently of `note_limits`, a note body over 65536 UTF-8 bytes is always rejected (`payload_too_large`), and a key the schema declares must be written with the schema's role on every path (`schema_violation`).

```yaml
note_limits:
  mode: warn   # warn | reject
```

This mode is per-root honorable (see "Global vs Per-Project Config" above): a project root that
pushes its own `note_limits.mode` overrides the server mode for that root's items only; a per-root
document with no `note_limits` section at all defers to the server mode unchanged.

Notes are a compression boundary: keep bodies distilled prose, and route verbatim artifacts (test output, diffs, logs) through `bodyFromFile` rather than pasting them inline.

---

## Independence (A2)

A note-schema entry's `independent_of` (see "Note-entry fields" under "Seats" above) declares which
other seat(s) a note's *authoring* must stay independent of - e.g. a test-author's `test-manifest`
staying independent of the `implementer` seat's notes. The server turns this into an attestation gate:
at `start`/`complete` (and the terminal/start cascade gates), it compares the identity that wrote a
declaring note against the identities that wrote the notes of the seat(s) it names, and reports (or, in
`reject` mode, blocks on) any conflict.

### Config block

```yaml
independence:
  mode: warn            # off | warn | reject - default warn
  require_verified: false  # default false
```

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `off` (the predicate is never evaluated - `violations` is absent from every surface), `warn` (default; violations are computed and reported but never block), or `reject` (a non-waived violation blocks the transition). Must be a YAML **string**, lowercase: an unquoted `off` parses as the YAML 1.1 boolean `false`, not the string `"off"` - **always quote it** (`mode: "off"`). A non-string or unrecognized value rejects a push. |
| `require_verified` | no | boolean | When `true`, a declaring note (or a conflicting seat's note) whose proof is not cryptographic also raises an `unverified` violation. Proof status `issued` (a server-issued bearer token), `none` and `rejected` all count as unverified, on purpose: attestation wants a cryptographic proof, which only `API_AUTH_MODE=jwks` produces. Default `false`. |

An unknown sub-key under `independence:` rejects a push. The block is per-root honorable: **a per-root
`independence` block replaces the server's WHOLESALE** (both `mode` and `require_verified` together, as
one unit) whenever the per-root document declares the key at all; a per-root document with no
`independence` section defers to the server's block unchanged.

### Constraints

For each note-schema entry `N` with a non-empty `independent_of` (a "declaring entry") whose note is
FILLED (non-blank body), the server checks `N`'s note against every FILLED note owned by each seat
`N.independent_of` names ("S-notes"), and reports up to three kinds of finding:

| Constraint | Raised when |
|---|---|
| `same_actor` | `N`'s note and a conflicting seat's note share the same identity (see "Identity" below). |
| `missing_actor` | `N`'s note, or a conflicting seat's note, carries no attribution at all - **fail-closed**: an unattributed note is never treated as automatically independent, it is flagged. |
| `unverified` | `require_verified: true` and the relevant note's proof is not cryptographic. |

Each finding is a `{key, seat?, constraint, conflictingSeat?, waived?}` object: `key` is the
declaring note's key, `seat` its owning seat (omitted when the schema is not seat-aware),
`conflictingSeat` the seat the finding was raised against (omitted for a `missing_actor`/`unverified`
finding raised against the declaring note itself), and `waived` present (`true`) only when the
temporal-only waiver applies (see below). **These objects never carry an identity, proof or claim, on any
surface, MCP or REST.**

**Identity.** A note's identity for comparison is the **attribution pair (principal, seat)** of its latest
write ([api-reference.md, notes_write](../../../../../current/docs/api-reference.md#notes_write)). The
principal is the verified subject (`proofClaims.sub`) when the note's proof is cryptographic and carries
one, else the principal id the call was authenticated as (`api:<id>` for a credential, `local` for the
local principal); the seat is the seat of the `seatToken` the write was made under, or none. Two notes of
one principal written under different seats are different identities. Comparison is an exact,
case-sensitive match. Ownership (who may overwrite a note) is a separate thing and is never read here.

**Evaluation scope.** For `start` (and the queued-parent start cascade), only declaring entries whose
`role` equals the item's CURRENT phase are evaluated. For `complete` (and the terminal cascade, and
`complete_tree`), every declaring entry across ALL phases is evaluated. `cancel`, `block`/`hold`,
`resume`, and `reopen` never evaluate independence - only `start`/`complete` triggers do. The findings
surface in the gate state (`items_read` `context`, `gateStatus.violations`), in the result of an
`advance` transition (`violations`, present only when non-empty) and of `items_write` `complete_tree`,
and, when `reject` mode blocks, in the `gate_blocked` error.

### The `independence: temporal-only` waiver

A declaring note may waive its own `same_actor` findings by making its body's **first line** (after
stripping one trailing `\r` and trimming) equal exactly `independence: temporal-only`, case-sensitive.
The waiver only takes effect when the declaring note's `createdAt` is strictly AFTER the `createdAt`
of every same-identity conflicting note it would otherwise collide with - i.e. the same identity wrote
both notes, but the declaring note is attested to have been written with the other note already
visible, which the schema owner accepts as sufficient independence for that pairing. A waived finding
carries `"waived": true` and **never blocks a transition in any mode**, `reject` included.

The waiver applies ONLY to `same_actor` findings - it never waives `missing_actor` or `unverified`
findings.

### Blocking

A transition is blocked only when `mode: reject` AND at least one finding is not `waived`. `warn`
mode never blocks - the transition applies, and `violations` simply reports what was
found. `off` mode never computes findings at all: `violations` is absent (not `[]`) everywhere for
that item. `canAdvance` in the gate state already folds this in. `gateStatus.violations` is present
(possibly `[]`) whenever the mode is not `off` and the schema declares `independent_of`, and omitted
otherwise.

### Honest limits

The gate is an **attestation-consistency check, not an enforcement mechanism**, unless the server
authenticates callers and `require_verified: true` is set. Understand what it can and cannot catch
before relying on it:

- **The principal is whatever the transport established.** Over stdio, or with `API_AUTH_MODE=none` /
  an unauthenticated `/mcp`, every caller is the one `local` principal, so two notes written by
  different agents collide as `same_actor` unless they were written under different seat tokens (the
  seat half of the pair). Separate callers need credentials (`API_AUTH_MODE=bearer` or `jwks`) or seat
  tokens. A caller cannot choose its own identity in a request: a body `actor` is refused.
- **Identity comparison is exact-match, case-sensitive.** Two credentials that belong to one real person
  are two identities, and the check cannot detect it; two parties sharing one credential always raise
  `same_actor`, a false positive it cannot tell apart from a genuine collision.
- **Attribution is last-writer-wins.** Re-upserting a note rewrites its principal and seat; there is no
  history of prior attributions to compare against, so a later re-write silently changes which findings
  a note contributes to.
- **A per-root config push can weaken or disable the gate for that root.** A per-root `independence`
  block replaces the server's wholesale, so `mode: off` or a dropped `require_verified` weakens every
  item under that root, with no separate confirmation beyond the ordinary push (which needs the
  `write-config` capability). A per-root schema push can also strip a note's `independent_of`/`seat`
  declaration, after which the gate has nothing to evaluate for that note, with no warning that the
  check went inert. An in-flight item keeps its pinned schema, and so its `independent_of`, until it is
  repinned.
- **Only FILLED notes are compared, and a filled note can be unfilled again.** Deleting a note's body
  or blanking it out erases its findings - the check re-evaluates from current note bodies each time.
  `start` only evaluates the declaring note's OWN current phase (not every phase the way `complete`
  does), so a conflict recorded against an earlier phase's S-note is invisible to a later phase's
  `start` check unless that note is still filled at evaluation time.
- **`require_verified` demands a cryptographic proof.** A server-issued bearer token is `issued`, not
  verified, so under `API_AUTH_MODE=bearer` every note is `unverified`; only `API_AUTH_MODE=jwks`
  produces a proof that satisfies it.
- **The `independence: temporal-only` waiver is self-service and has no config-level off switch.**
  Whoever writes the declaring note can add the waiver marker to it, and for a single agent writing its
  own notes sequentially the ordering requirement is satisfied automatically by writing last. In
  practice this makes the waiver an opt-out any note-writer can apply to itself under `reject` mode.
- **Even with attribution redaction on, non-admin callers still learn the relation between notes.**
  `API_REDACT_NOTE_ATTRIBUTION` hides the identity itself, but a `same_actor` or `missing_actor`
  finding is still visible to any caller who can read the gate state, so a non-admin caller cannot see
  WHO wrote two notes, but can see THAT they were (or were not) written by the same identity.

Treat a bare `warn`/`reject` config as a discipline aid, not a security control; pair it with
`API_AUTH_MODE=jwks` and `require_verified: true` for a stronger guarantee.

---

## Run Budgets (`runs:`)

The top-level `runs:` block (server file or per-root document, resolved per field: the root's value, else the server's, else the default) sets `stall_after` and optional `budgets` ceilings a run may declare up to. The keys, their types and defaults are in [config-reference.md, Runs](../../../../../current/docs/config-reference.md#runs-runs); how the server enforces a run's budget, and the decisions a limit raises, are in [Fleet Deployment](../../../../../current/docs/fleet-deployment.md#run-budgets-and-caps) and the [workflow guide](../../../../../current/docs/workflow-guide.md#12-runs-seats-and-decisions). Who may answer a limit decision is a `decision_classes:` setting: see [Decision classes](../../../../../current/docs/config-reference.md#decision-classes-decision_classes).

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration - orphaned notes become ad-hoc notes on their items, and an item pinned to the old schema keeps requiring the old key until it is repinned.
