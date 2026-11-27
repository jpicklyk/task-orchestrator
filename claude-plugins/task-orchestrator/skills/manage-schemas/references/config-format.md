# Config Format Reference

The schema-authoring guide for `.taskorchestrator/config.yaml`: how to write note entries, traits, dispatch, seats, resources, independence and the plugin's own settings. What the server accepts, which key has which owner, how a push is validated and what a schema pin is are in [config-reference.md](../../../../../current/docs/config-reference.md) - this guide links there instead of repeating them.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- Commit this file (`/task-orchestrator:init` advises it); keep `.taskorchestrator/client.json`, which holds a machine-specific URL, out of git
- How an edit takes effect depends on how the file reaches the server (see "Global vs Per-Project Config" below):
  - **Per-root (config-sync)** - when a REST API URL resolves and the file carries `project.rootId`, the `config-sync` hook pushes the file to the server for that root at SessionStart and again whenever the file changes (`config` `push`); without either it pushes nothing; a pushed config is hot-reloaded, with no restart or reconnect. A push is strict: any error rejects it and stores nothing
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
        guidance: "..."        # Optional - read with items_read select schema and fields ["guidance"]; advance and items_read context return guidanceKey (a reference)
        skill: "security-review" # Optional - example value; a skill to invoke when filling this note (shown as skillPointer)
        skill_mode: method-on-diff # Optional - with skill: the skill is a method the reviewer applies to the owned diff, not a skill to invoke
        maxLength: 4000         # Optional - max body length (chars); enforced on every note write per note_limits.mode

traits:

  needs-security-review:       # Trait name - referenced by default_traits or an item's traits array
    description: "Changes touching authentication, input handling or secrets"  # Optional - text served to clients; never evaluated
    applies_when: "the diff changes an auth, parsing or secret-handling path"  # Optional - when a planner should add this trait; text only, never evaluated
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        skill: "security-review"
        skill_mode: method-on-diff
        guidance: "Evaluate input validation, injection risks, access control..."
    resources:                  # Optional - resources this trait's items need at WORK entry (see below)
      - staging-db               # short form: bare key = mode: exclusive, no ttlSeconds override
      - key: github-deploy-token  # long form: explicit mode + ttlSeconds
        mode: advisory
        ttlSeconds: 1800
```

The `resources:` registry lives in the **server file**, not in the project config above; every trait resource key, advisory ones included, must be registered there:

```yaml
# server file (.taskorchestrator/config.yaml mounted on the server)
resources:                      # Optional registry, SERVER FILE ONLY - describes keys, does not declare usage
  staging-db:
    description: "Single shared staging database - only one migration/test run at a time"
    defaultTtlSeconds: 3600
    maxHolders: 1                # values > 1 are rejected ("not yet supported")
  github-deploy-token:
    description: "Deploy token; advisory, recorded in the audit trail only"
```

The legacy `note_schemas` form (a type name mapped to its notes, auto lifecycle) is still accepted but cannot share a document with `work_item_schemas`; write `work_item_schemas`.

---

## Field Reference

### `guidance` vs `skill` - when to use which

These fields serve different purposes and work together:

- **`guidance`** provides project-specific instructions for *what* the note should cover. It's free text you write in config - "Cover: problem statement, acceptance criteria, alternatives considered, blast radius, test strategy." The agent sees this as `guidanceKey` when it's about to fill the note.

- **`skill`** references a reusable evaluation framework that defines *how* to produce the note. The agent invokes the skill (the one the `skill` value names) before writing, getting a structured methodology - rubrics, checklists, evaluation dimensions. The agent sees this as `skillPointer`.

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
  **qualified**: `task-orchestrator:session-retrospective`, `task-orchestrator:work-summary`, etc.
- **Project-level skills** (`.claude/skills/`) and **Claude Code built-in skills** are **bare**:
  the names of project-level or built-in skills, such as `plan` or `review`.

**Watch for built-in name collisions.** A short, generic `skill:` value can silently resolve to
the wrong built-in instead of your intended framework skill - especially `review` (Claude Code's
GitHub-PR review skill, not a note-quality framework), and also `plan`, `run`, `init`. If you want
a review framework, point at the actual framework skill name (e.g. `<your-review-framework>`), not
the collision-prone generic word. `manage-schemas`'s `validate` operation flags known collisions
in this set - see `SKILL.md` -> VALIDATE.

### Rule text

A note entry's `skill` pointer doubles as more than a Skill-tool name: when it also names a rule
key, it is resolvable through the `config` tool's `rules` op (`itemId` + `noteKey`) as client-neutral
operating-rule TEXT, not just a skill invocation. This is a second, independent consumer of the same
`skill` field - no new schema field, no config syntax change. A note whose `skill` names a served rule key (for example `skill: closure-notes`) reaches the run-wave seat that owns the note as a rule, on every phase: a review lane lists it with its seat's `rules`, and the note's other skills stay skills, invoked in order. A note `skill` that names another seat role's protocol rule (`protocol.*`) is dropped and reported, as for a seat's `rules` below.

- **Storage.** Rule text is stored per project root as a `rule` document (`kind: rule`, `slug` = the
  key) with the [`docs_write`](../../../../../current/docs/api-reference.md#docs_write) `upsert` op,
  which needs the `write-docs` capability (`write-config` does not grant it). The document's
  `contentHash` (SHA-256 of the LF-normalized body) doubles as the rule's `rulesVersion`. The 3.x
  routes that wrote and read rules are gone.
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
- **Workspace rules.** A workspace may keep its source rule text git-tracked under
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
  bundled rule keys, protocol keys first; `bundled-rules/manifest.json` lists every key. It is an
  append-only list of known body hashes per key, and the push policy follows it: a key absent from the
  server is pushed; a server copy matching a known older hash is overwritten with the current body; a
  server copy matching no known hash is never touched. A same-named file in the workspace's
  `.taskorchestrator/rules/` wins the key over the bundled copy. A config whose every declared type
  is `lifecycle: none` gets no bundled rules (they are build-process rules for seats such a root never
  has). Copies pushed into such a root earlier are archived on SessionStart once the server holds that
  config: each live bundled copy whose hash is one the manifest lists (current or older) gets
  `docs_write` `archive`, which drops it from knowledge search, keeps its body and history, still
  serves it as rule text, and is undone with `unarchive`. A copy at any other hash, or a key the
  workspace provides, is never archived. Nothing is deleted. When such a root later declares a type
  with a lifecycle and the server holds that config, each archived bundled copy at a listed hash is
  unarchived, then brought to the current body when it held an older one. In user scope the pushed config is
  reported as "user config".
- **Shared deadline.** Config and rule requests share one budget measured from hook start (about 8 s on
  SessionStart, about 3.5 s on FileChanged). Work not finished in time is reported as
  `<N> deferred to next session`; an in-flight request aborted by the deadline counts as deferred, not
  failed.
- **Notice wordings.** `kept unrecognized server copy: <key>` appears at every session start for a
  server rule that was customized on purpose; to make it stop, put that rule in the workspace's
  `.taskorchestrator/rules/` so the workspace copy owns the key; in a lifecycle-free root, archiving
  that copy by hand (`docs_write` `archive`) also silences it. `archived bundled build rules: <keys>`
  names the bundled copies archived in this run, and `unarchived: <keys>` the ones restored for a root
  that regained a lifecycle type. `bundled rules skipped (unusable rules
  listing)` means the server's rule listing could not be read. `root <id> not found on this server`
  means the root was never created there. `closure-note guidance still asks for file lists or test counts: run /manage-schemas edit to adopt the current standard.` is appended to the SessionStart line when a `session-tracking` or `implementation-notes` guidance in the config still asks for a file list, test results or pass/fail counts; the hook never edits the file.

---

## Lifecycle Modes

`lifecycle` (lowercase) sets what happens when children reach terminal: `auto` (the default) cascades the parent, `manual` and `permanent` suppress the cascade, and `none` makes the item lifecycle-free (see [Lifecycle-free items](../../../../../current/docs/config-reference.md#lifecycle-free-items)). The removed `auto-reopen` value is an error in a push.

---

## Matching Rules

Schema resolution is by exact `type` over the root's own config, then the server config; an item no schema matches is schema-free. The schema is pinned when the item is created: see [Schema pins](../../../../../current/docs/config-reference.md#schema-pins). Tags are never consulted.

---

## Trait Merge Semantics

**A per-root trait entry replaces the server's entry as a whole.** When the root's `traits:` map
contains the trait's name, its notes, seats, resources and dispatch all come from that entry, and
a dimension the entry does not declare is empty (see "Global vs Per-Project Config"). Restate
everything you want to keep.

---

## Resources (Trait Dimension)

A trait can declare `resources:` - a list of shared-resource requirements enforced by the server as
a **gate at WORK entry**: any transition whose target role is `work` (`start` from queue, `resume`
of an item blocked from work, and the start/reopen cascades). This is independent of the note-requirement dimension: a trait can
carry notes, resources, both, or neither.

### Merge semantics - read this before combining traits

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
for the full `credentialRefs` contract.

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
| `effort` | no* | One of `low`, `medium`, `high`, `xhigh`, `max`, matched **case-sensitively** (`High` is invalid). Passed as the Agent tool's `effort` parameter - see "How effort reaches a dispatch" below. |

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
trait order wins (see "Seats" and
[Seat dispatch precedence](../../../../../current/docs/config-reference.md#seat-dispatch-precedence)).

### How effort reaches a dispatch

The server only reports a profile's `effort`; the dispatcher applies it. The dispatcher passes the
profile's value (or the class default) as the Agent tool's `effort` parameter, the same way it passes
`model`. The parameter overrides the agent definition's defaults and needs no `agent`, so a profile
with `effort` and no `agent` still applies. The plugin's dispatch hook denies an Agent call that
omits `model` or `effort`, and the plugin's agent definitions set neither, so every dispatch names
both explicitly: the profile's value when it sets one, otherwise the class default from
`task-orchestrator:model-effort`. `CLAUDE_CODE_EFFORT_LEVEL` and organization caps outrank the
parameter, and a level the model does not support runs at the highest supported level below it.
Level names do not mean the same amount of work across models, so review a phase's `effort` when a
trait changes that phase's model.

`implement-wave` passes a seat's `effort` on its `agent()` call and run-wave Method B `next`
entries carry `effort` for the Agent call. A fix after a failed test or review runs the base effort
raised by the seat's earlier failed-review or failed-test fixes plus one, capped at the policy's `escalation.ceiling`. Clients that call the
model API directly may apply a profile's `effort` field themselves. See
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
`seats:` anywhere is unaffected: no seat-related field appears in any response. A seat-less or
schema-free run member may still be assigned `implementer` or `planner`, plus `reviewer` when its schema has a
review-role note; those tokens carry no lane enforcement (the first writer's seat owns a note).

### Declaration form

```yaml
work_item_schemas:
  bug-fix:
    default_traits: [needs-test-author]
    seats:                                    # schema-level, list
      - { name: planner,      phase: queue }
      - { name: implementer,  phase: work, enters: true, transitions: [block, hold, resume] }
      - { name: extractor,    phase: work, after: [implementer] }
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
| `name` | yes | string | Non-blank, unique within its declaring scope. Seat names carry no meaning for the server, except the reserved names `unowned` (the bucket, see "The `unowned` bucket") and `director`, which a seat may never be named. A seat named exactly `orchestrator` or `coordinator` loads with an advisory at `<entry>/name` saying the name will be refused in a later release: the orchestrator is a reserved note owner, not a seat (see "Seat transitions"), so remove the entry and label its notes `seat: orchestrator`. |
| `phase` | yes | string | `queue`, `work`, or `review` only - exact lowercase match, same set as note `role`. |
| `enters` | no | boolean | Default `false`. Whether this seat is the one that transitions the item **into** `phase`. At most one seat may set this `true` per phase within one seats scope - see "Structural errors". A conflict discovered only at resolve time (per-item traits, or traits from a different config layer) is not fatal - see "Merge rules". |
| `writes` | no | boolean | Whether this seat changes files in the item's worktree and commits them; notes are not covered. Absent: `true` in the `work` phase, `false` elsewhere. Served, never enforced. Advertised by the `seatWrites` feature. |
| `transitions` | no | list | The triggers other than `start` that a token for this seat may send: `complete`, `block`, `hold`, `resume`, `cancel`, `reopen`. Absent: `block` and `hold`. A non-list or an unknown value rejects a push. See "Seat transitions". |
| `after` | no | list of strings | Seat names this seat's work logically follows - an ordering **hint**, not enforced by the server. A name outside the declaring scope, or naming a seat in a different phase, is served exactly as declared. |
| `reads_exclude` | no | list of strings | Note keys this seat should **not** read - a hint powering test-author-style blindness. Parsed and served only; blindness is a process discipline, not a server-side check. Separate from a note entry's own `independent_of`. |
| `skills` | no | list of strings | Skill names the seat should invoke, served as written. Absent: the plugin's defaults for that seat apply, from `hooks/lib/seat-skill-defaults.json`: planner `task-orchestrator:planning-standards` and `task-orchestrator:designing-modules`, implementer `task-orchestrator:implementation-standards`, reviewer `task-orchestrator:review-method`, test-author `task-orchestrator:test-authoring`. A declared list replaces the defaults and never merges with them, so a default is kept only by listing it; `[]` declares none. The plugin reads a declared list only when the server lists the `seatSkills` feature; against a server without it the defaults apply and are hinted only. Duplicates collapse. A non-list value, or a non-string or blank element, is a parser finding: an error on push (`STRICT`) and a warning only under `MIGRATION`, where a non-list value leaves the field absent (the defaults apply) and a bad element is dropped. Existence is not checked and nothing in the server enforces the list |
| `rules` | no | list of strings | Rule keys this seat is given, on a run dispatch and in the seat hint of a hand dispatch alike. Absent: the seat's defaults from the plugin's seat rule table (`hooks/lib/seat-rules.json`) apply; `[]` declares none. A declared list replaces the defaults only: the seat's protocol rule and the rules the plugin adds (same table) are always given first. A project may replace a protocol rule's text by serving its own copy under the same key; that copy then stops receiving plugin updates, and the manage-schemas audit reports it as project-authored. To add conduct for a seat, put a project rule on the seat's `rules` list instead. Duplicates collapse; a key that does not match the rule key grammar, or a non-string element, is dropped, and is an error on push (`STRICT`) and a warning only under `MIGRATION`. Existence is not checked, and served only when declared. Never enforced by the server: a declared key the root does not serve is a degradation the run planner reports, while a default rule the root does not serve (no `rules` list declared) is dropped silently |

### Seat transitions

A seat token sends `start` only into the phase of a seat that sets `enters: true`, and every other trigger it sends must be in its `transitions:` list (default `block`, `hold`). The full rules are in [config-reference.md, Seat transitions](../../../../../current/docs/config-reference.md#seat-transitions).

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
order (item `traits` first, then `default_traits`):

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
5. **Per-seat dispatch overrides resolve in the same trait order, with one exception.** For a given
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
   when a `default_traits` list names a model-selecting trait after an earlier trait whose override for
   that seat is not itself model-selecting (the earliest overrider decides); the warning
   is a migration aid and never rejects a push. See [Seat dispatch precedence](../../../../../current/docs/config-reference.md#seat-dispatch-precedence).

### Structural errors (F1-F5) - reject the push and fail the boot import

A structural error in a `seats:` list (F1-F5, for example two entering seats in one phase or an `after` cycle) is fatal in every parse mode: the push fails `config_invalid` and stores nothing, and the server file fails startup. `config` `validate` reports each at the path of the offending list.

### The `unowned` bucket

A required note that has no seat owning it - no `seat` declared, an unknown seat name, or a `seat`
declared for a different phase than the note's own `role` (the exception is `seat: orchestrator`, owned in any phase with no seat declared) - is served under the reserved
`"unowned"` key in `missingBySeat` (see `api-reference.md` for the response shape).
This is deliberate, not an error: it lets an orchestrator see at a glance which missing notes have no
seat routing at all, so a schema can be seat-aware for *some* notes while others stay unowned.
`unowned` buckets are always sorted last among non-empty buckets; the reserved name itself can never be
used for a real seat (F3).

---

## Design Principles

- **Keep it lean.** 2-3 required notes per phase is the practical maximum before agents find workarounds
- **Queue gates = pre-work contract.** Requirements, design, root cause - filled before agents touch code
- **Work gates = evidence of completion.** Implementation notes (what the diff does not say), verification evidence - filled after implementation
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

The `project:` section is a top-level key alongside `work_item_schemas` and `traits`. It anchors a workspace's work to a single depth-0 MCP item.

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
| `rootId` | yes | string (UUID) | UUID of the depth-0 item (`type: project`) that anchors a workspace's work. The hooks use it only when it is 1 to 64 letters, digits or hyphens and does not start with a hyphen (a UUID qualifies); any other value is treated as if no `rootId` were set |
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
        description: "Delta record: what the diff, git log and CI do not hold (deviations, decisions, not-done, gotchas)"
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
    steering: workflow
    retrospective:
      mode: dispatch
      minItems: 3
      boundary: scope
      github_feedback:
        enabled: true
        repo: jpicklyk/task-orchestrator
```

| Key | Values | Default | Read by |
|-----|--------|---------|---------|
| `steering` | `workflow`, `schema`, `off` | `workflow` | the orchestration hooks (see "Steering"); `orchestration.mode` is the deprecated alias |
| `retrospective.mode` | `nudge`, `dispatch`, `off` | `nudge` | the retrospective hooks (see "Retrospective") |
| `retrospective.minItems` | integer >= 0: terminal items that make a group worth a retrospective (`dispatchThreshold` is the older name, read when `minItems` is absent) | `3` | the retrospective hooks |
| `retrospective.boundary` | `scope`, `run` | `scope` | the retrospective hooks |
| `retrospective.github_feedback.enabled` | boolean | `false` | the `session-retrospective` and `review-proposals` skills |
| `retrospective.github_feedback.repo` | `owner/repo` | `jpicklyk/task-orchestrator` | the same skills, for proposal issues only |
| `retrospective.baselines.<name>` | a map of dated figures the project measured, such as `note-volume: { measured: 2026-11-02, review-share-pct: 31 }` | absent | `run-planner retro-metrics`, which echoes them in `noteVolume.baselines` (null when absent) |

A retrospective is owed per completed scope (boundary `scope`) or per closed run not yet marked `retro` (boundary `run`),
and only to the session that opened or closed one of its runs (a bystander's group at the scope boundary excepted, and only when it became due after the session started; an abandoned
group is its owner's alone); the hooks decide by run marks, not by a cooldown. The hooks README describes the run-mark
trigger and the scope state machine in full (the "Retrospective trigger" and "Retrospective firing" sections of [hooks/README.md](../../../hooks/README.md)). There is no
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
      minItems: 3          # terminal items that make a group worth a retrospective (default 3)
      boundary: scope      # scope (default: one per completed scope) | run (one per closed run)
      github_feedback:                     # optional
        enabled: true                      # default false - opt-in
        repo: jpicklyk/task-orchestrator   # optional; this is the default
```

Default: `nudge` - applied when the block or `mode` key is absent, or the value is unrecognized.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `nudge`, `dispatch` or `off`. Defaults to `nudge` |
| `minItems` | no | integer >= 0 | At boundary `scope` it is one of the triage's signals: a completed scope with at least this many terminal items always gets a retrospective; a smaller, clean one is skipped by the hook. At boundary `run`, in `mode: dispatch`, it is the count of items terminal in the closed run required before a background dispatch fires; below it the run gets a nudge. `dispatchThreshold` is the older name, read when `minItems` is absent; `minItems` wins when both are set. Defaults to `3`; a non-integer or negative value falls back to the default. |
| `boundary` | no | string | `scope` or `run`. `scope` (default) fires one retrospective per completed scope, covering every run of it; `run` fires one per closed run. An unrecognized value reads as `scope`. |
| `github_feedback.enabled` | no | boolean | Opt-in. When `true`, the session-retrospective skill files a GitHub enhancement issue for **product-scope** improvement proposals via the `gh` CLI. Defaults to `false`. Requires `gh` installed and authenticated; filing is skipped gracefully otherwise. Product-scope proposals are the only thing a config sends to GitHub; nothing project-scoped is ever sent. |
| `github_feedback.repo` | no | string | `owner/repo` target for filed proposal issues. Defaults to `jpicklyk/task-orchestrator` (the plugin's upstream). |

### Modes

| Mode | Behavior |
|------|----------|
| `nudge` (default) | Hooks inject a suggestion to run `/session-retrospective` when a run closes. The agent decides whether to act on it. |
| `dispatch` | Hooks inject a directive that launches exactly one background retrospective agent per completed scope (boundary `scope`; the hook first writes a `retro-claim` mark, the first write wins, so two sessions never both dispatch, and a low-signal scope is skipped with no output) or per closed run (boundary `run`, once the run clears `minItems`; the hook first writes the run's `retro` mark, and below the threshold it injects the `nudge` suggestion instead of staying silent). The directive is **durable, not immediate**: it instructs the orchestrator to dispatch at the next run boundary - holding it while background tasks or subagents are still in flight, and merging any directives that accumulate while holding into a single dispatch covering the union of their roots. |
| `off` | Hooks stay silent - no retrospective suggestion or dispatch directive is injected. |

**The Stop backstop.** The `Stop` hook (`retro-backstop.mjs`) runs the same evaluation as the trigger (it also retries a failed or dead dispatch and nudges or dispatches an abandoned scope to its owner at the scope boundary), so a run this session opened that closed elsewhere (a subagent, a tool the trigger never saw) still gets its retrospective. A run another session opened and closed is that session's, never nudged here. With a REST API it may dispatch (after winning the `retro` mark) as well as nudge, per the configured mode. Without REST it can only nudge, once per session, about runs this session saw close, and never marks or dispatches.

### Behavior

- **Read by hooks, not the server.** The block is read directly from the workspace file by the plugin hooks (`retro-trigger.mjs`, `retro-backstop.mjs`, `retro-subagent-stop.mjs`) - the MCP server stores `extensions` without interpreting it.
- **No hot-reload needed.** Because hooks read the file fresh on every fire, an edit takes effect on the next hook trigger - no `/mcp` reconnect required.
- **`project.rootId` required for runs.** The hooks list the closed, unmarked runs of the project root, so they need the `project.rootId` of the located config.
- **`github_feedback` is skill-read, not hook-read.** The nested block is read by the skills (`session-retrospective`, `review-proposals`) as plain YAML - the hooks' scalar reads are unaffected by its presence.
- **Held directives can be lost - known trade-off.** A directive the orchestrator is holding for a run boundary exists only in conversation context. If the session is killed or the context compacts before the last background task completes, that retrospective is skipped for the session; recover by running `/session-retrospective` manually. Dispatched retrospectives work only from durable MCP state, never conversation context.

---

## Steering

The `steering` setting selects how much orchestration behavior the plugin hooks inject into a session. It lives at `extensions.claude-code.steering`:

```yaml
extensions:
  claude-code:
    steering: workflow   # workflow (default) | schema | off
```

Default: `workflow` - applied when the key is absent, or the value is unrecognized.

**Deprecated alias.** `extensions.claude-code.orchestration.mode` (block or inline form) is read only when `steering` is absent or unrecognized; `steering` wins when both are set. While the old key is present, `orchestration-context.mjs` prints one deprecation line per session. Rename it to `steering`.

### Values

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `steering` | no | string | `workflow`, `schema`, or `off` (case-insensitive, quotes optional). Defaults to `workflow`; any other value falls back to the default. |

### Modes

| Mode | Behavior |
|------|----------|
| `workflow` (default) | Full behavior: tier classification, the `model-effort` class table, and dispatch rules. |
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
startup.

### How the layers combine

One precedence rule: a specific declaration beats a generic one, and the root beats the server at the
same level.

| Facet | Rule |
|-------|------|
| Schema for a type | root exact type, server exact type, root `default`, server `default`, none |
| Trait | when the root's `traits:` map contains the name, notes, seats, resources, dispatch and `builds` all come from the root's entry (a dimension it omits is empty); otherwise all from the server's |
| `resources:` registry | server only; a root document that carries it is rejected |
| `note_limits.mode` | the root's explicit value, else the server's |
| `independence` | the root's block wins whole (`mode` and `require_verified` together), else the server's |
| `runs` | per field: root value, else server, else default |
| `decision_classes` | per class name: a root entry replaces the server entry of that name |
| `status_labels` | per trigger: the root's value (an explicit `null` included) wins, else the server's |

---

## Note Body Length Limits

Top-level `note_limits.mode` (`warn`, default, or `reject`) governs what happens when a note body exceeds its schema `maxLength`, checked in characters on every note write path (`notes_write` `upsert` and the notes of `items_write` `create_tree`) against the resolved body after CRLF-to-LF normalization (inline `body` or file-read via `bodyFromFile`): `warn` accepts the note with a `warning` field on its result; `reject` fails that note with `note_too_long`. Independently of `note_limits`, a note body over 65536 UTF-8 bytes is always rejected (`payload_too_large`), and a key the schema declares must be written with the schema's role on every path (`schema_violation`).

This mode is per-root honorable (see "Global vs Per-Project Config" above): a project root that
pushes its own `note_limits.mode` overrides the server mode for that root's items only; a per-root
document with no `note_limits` section at all defers to the server mode unchanged.

Notes are a compression boundary: keep bodies distilled prose. Verbatim artifacts never go in a note body: test output, diffs, logs, grep or search output, file inventories, line-number tables and anything else a command produced. Store them as a `docs_write` `page` document (slug `<item-short>/<purpose>`) and have the note name the slug, the command that produced it and the row count. A note holding more than about 20 table rows, or citing more than about 30 `file:line` pairs, is an inventory and belongs in a document.

---

## Independence (A2)

A note-schema entry's `independent_of` declares which
other seat(s) a note's *authoring* must stay independent of - e.g. a test-author's `test-manifest`
staying independent of the `implementer` seat's notes. The server turns this into an attestation gate:
at `start`/`complete` (and the terminal/start cascade gates), it compares the identity that wrote a
declaring note against the identities that wrote the notes of the seat(s) it names, and reports (or, in
`reject` mode, blocks on) any conflict.

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

| Key | Type | Meaning |
|---|---|---|
| `stall_after` | integer seconds, 1 or more | How long a seat or orchestrator may go unseen before it reads as stalled (default 600) |
| `budgets.attempts_per_seat` | integer, 1 or more | Ceiling on attempts one seat may make on one item |
| `budgets.max_active_seats` | integer, 1 or more | Ceiling on seats active at once |

An absent ceiling means unlimited; a run's own budget may only lower a ceiling. The run token and cost ceilings were removed in 4.0.1; a config that still sets them loads with a warning and the keys have no effect.

## Decision Classes (`decision_classes:`)

A decision is raised under a class; the class sets who answers it, its default severity and when it turns overdue. A per-class entry has `answerers` (`human-only`, `director-or-human`, `any-actor`), `severity` (`high`, `normal`, `low`), `expires_after` (seconds), `on_expiry` (`escalate`, the only value), `freeform` (boolean) and `autonomy` (`answer` or `ask`, default `ask`; a `human-only` class is always `ask`). The class name matches `^[a-z][a-z0-9_-]{0,63}$`.

```yaml
decision_classes:
  release-gate:
    answerers: human-only
    severity: high
    expires_after: 86400
```

- Built-in classes: `run-budget` and `attempt-cap` (raised by the server only; `run-budget` is legacy, raised only by 4.0.0 and still answerable on stored rows with `extend` and no amount, or `stop`), `deviation` and `question`. A built-in entry in config overrides it field by field.
- Merge: a per-root entry replaces the server entry of the same name as a whole.
- The limit classes (`run-budget`, `attempt-cap`) may be raised to `human-only` but are never `any-actor`; a push that lowers them is rejected at `/decision_classes/<name>/answerers`.
- A `human-only` class cannot be answered on a stdio server: every caller there is the `local` agent principal.
- Raising a class that is not declared fails `invalid_request /class`.

The full tables are in [config-reference.md](../../../../../current/docs/config-reference.md#decision-classes-decision_classes).

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration - orphaned notes become ad-hoc notes on their items, and an item pinned to the old schema keeps requiring the old key until it is repinned.
