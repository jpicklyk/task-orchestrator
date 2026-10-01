# Config Format Reference

YAML format and field rules for `.taskorchestrator/config.yaml`.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- Commit this file (`/task-orchestrator:init` advises it); keep `.taskorchestrator/client.json`, which holds a machine-specific URL, out of git
- The server reads and caches this file on first schema access — changes require MCP reconnect (`/mcp`)

### Config discovery (plugin hooks and skills)

The plugin hooks locate the config in this order: (1) `AGENT_CONFIG_DIR` (a relative value resolves against the hook's working directory), (2) walking up from the working directory, (3) the main checkout of a linked git worktree (a bare repository's worktrees skip this step), (4) the user-level `<home>/.taskorchestrator/config.yaml`. Any hit equal to the user-level path is user scope, whichever step finds it (including `AGENT_CONFIG_DIR` set to the home directory); every other hit is project scope. A config sitting directly in a home directory is never a walk-up or main-checkout hit.

The REST `apiUrl` (never a token) resolves in this order: `TASK_ORCHESTRATOR_API_URL`, then `apiUrl` in a `client.json` beside the located config when its scope is project, then `apiUrl` in the user-level `client.json`; if none resolves the hooks no-op. The project-level file is written by project-mode `/task-orchestrator:init` and is honoured only for an `http`/`https` URL without credentials whose host is `localhost`, an IPv4 address in `127.0.0.0/8` or `[::1]` (a repo file must not redirect the bearer token to a remote host); any other value is ignored silently. The user-level file, written by `init --user`, and the env var are unrestricted. A linked worktree that carries its own `config.yaml` locates that config, finds no (untracked) `client.json` beside it and falls through to the user-level file; `AGENT_CONFIG_DIR` moves the located config, and the project file with it.

`TASK_ORCHESTRATOR_HOME` replaces your home directory for Task Orchestrator: when it is set, the user-level `config.yaml` and `client.json` are read only from `$TASK_ORCHESTRATOR_HOME/.taskorchestrator/`, and `~/.taskorchestrator/config.yaml` is ignored unless `AGENT_CONFIG_DIR` points at it.

`TASK_ORCHESTRATOR_CEILING` is an optional directory at which project-config discovery stops climbing — like `GIT_CEILING_DIRECTORIES`. It exists mainly so tests stay isolated; leave it unset in normal use.

Because the user-level file applies in every directory that has no project config of its own, `actor_attribution`, `actor_authentication` and skill notes placed there take effect everywhere that is unconfigured.

---

## YAML Structure (Preferred — work_item_schemas)

```yaml
work_item_schemas:

  your-schema-type:            # Matches items whose type field equals "your-schema-type"
    lifecycle: auto            # Optional: auto | manual | permanent (default: auto)
    default_traits:            # Optional: traits applied to every item matching this schema
      - needs-security-review
    notes:
      - key: note-key          # Stable identifier; kebab-case
        role: queue            # Phase: "queue", "work", or "review"
        required: true         # true = blocks advance_item gate | false = shown but not enforced
        description: "..."     # Short label — fetch via query_items(operation="schema"), not in expectedNotes
        guidance: "..."        # Optional — fetch via query_items(operation="schema"); get_context/advance_item return guidanceKey (a reference)
        skill: "review-quality" # Optional — skill to invoke when filling this note (shown as skillPointer)
        maxLength: 4000         # Optional — max body length (chars); enforced by manage_notes upsert per note_limits.mode

traits:

  needs-security-review:       # Trait name — referenced by default_traits or per-item traits parameter
    notes:
      - key: security-assessment
        role: review
        required: true
        description: "Security review"
        skill: "security-review"
        guidance: "Evaluate input validation, injection risks, access control..."
    resources:                  # Optional — resources this trait's items need at WORK entry (see below)
      - staging-db               # short form: bare key = mode: exclusive, no ttlSeconds override
      - key: github-deploy-token  # long form: explicit mode + ttlSeconds
        mode: advisory
        ttlSeconds: 1800

resources:                      # Optional top-level registry — describes keys, does not declare usage
  staging-db:
    description: "Single shared staging database — only one migration/test run at a time"
    defaultTtlSeconds: 3600
    maxHolders: 1                # values > 1 are rejected at config load ("not yet supported")
```

---

## YAML Structure (Legacy — note_schemas)

The `note_schemas` format is still supported for backward compatibility. New configs should use `work_item_schemas`.

```yaml
note_schemas:

  your-schema-tag:             # Matches items whose tags include "your-schema-tag"
    - key: note-key
      role: queue
      required: true
      description: "..."
      guidance: "..."
```

When using `note_schemas`, the `lifecycle` field is not available — all schemas default to `AUTO` lifecycle mode.

---

## Field Reference

### Schema-level fields (work_item_schemas only)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `lifecycle` | no | string | `auto`, `manual`, or `permanent`. Defaults to `auto` |
| `default_traits` | no | list | Trait names applied to every item matching this schema |

### Note-level fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `key` | yes | string | kebab-case, unique within schema, stable after creation |
| `role` | yes | string | `queue`, `work`, or `review` only |
| `required` | no | boolean | Parser defaults to `false` if omitted — **always set explicitly**, since a note you meant to gate on silently becomes optional otherwise. `true` = blocks gate, `false` = shown but not enforced |
| `description` | no | string | Parser defaults to `""` if omitted (recommended in practice). Keep under 80 chars — quick label; fetched via `query_items(operation="schema")`, not in `expectedNotes` |
| `guidance` | no | string | Project-specific authoring instructions — fetched via `query_items(operation="schema")`; `get_context`/`advance_item` return `guidanceKey` (a reference) |
| `skill` | no | string | Reusable evaluation framework — shown as `skillPointer` in `get_context` |
| `maxLength` | no | integer | Max note body length (chars). Enforced by `manage_notes` upsert (inline `body` or `bodyFromFile`) per top-level `note_limits.mode` |

### `guidance` vs `skill` — when to use which

These fields serve different purposes and work together:

- **`guidance`** provides project-specific instructions for *what* the note should cover. It's free text you write in config — "Cover: problem statement, acceptance criteria, alternatives considered, blast radius, test strategy." The agent sees this as `guidancePointer` when it's about to fill the note.

- **`skill`** references a reusable evaluation framework that defines *how* to produce the note. The agent invokes the skill (e.g., `/spec-quality`) before writing, getting a structured methodology — rubrics, checklists, evaluation dimensions. The agent sees this as `skillPointer`.

| Combination | When to use |
|-------------|------------|
| `guidance` only | Most notes. You know what content you want — just tell the agent. |
| `skill` only | The skill is self-contained and doesn't need project-specific tailoring. |
| Both | The skill provides the structured process; guidance adds project-specific requirements the skill doesn't cover. The agent invokes the skill first, then uses guidance to fill in domain-specific details. |
| Neither | The note key and description are self-explanatory — no additional direction needed. |

### `skill` — exact-name rule

A `skill:` value must be the **exact name** the Skill tool accepts in the workspace where the
note gets filled — the Skill tool resolves strictly (no fuzzy matching, unlike a user typing a
slash command):

- **Plugin-shipped skills** (this plugin's `claude-plugins/task-orchestrator/skills/`) are
  **qualified**: `task-orchestrator:session-retrospective`, `task-orchestrator:review-quality`, etc.
- **Project-level skills** (`.claude/skills/`) and **Claude Code built-in skills** are **bare**:
  `review-quality`, `security-review`, `plan`, `review`, etc.

**Watch for built-in name collisions.** A short, generic `skill:` value can silently resolve to
the wrong built-in instead of your intended framework skill — especially `review` (Claude Code's
GitHub-PR review skill, not a note-quality framework), and also `plan`, `run`, `init`. If you want
a review-quality framework, point at the actual framework skill name (e.g. `review-quality`), not
the collision-prone generic word. `manage-schemas`'s `validate` operation flags known collisions
in this set — see `SKILL.md` → VALIDATE.

### Rule text

A note entry's `skill` pointer doubles as more than a Skill-tool name: when it also names a rule
key, it is resolvable through the MCP `query_rules` tool's skill-pointer lookup
(`itemId`+`noteKey`) as client-neutral operating-rule TEXT, not just a skill invocation. This is a
second, independent consumer of the same `skill` field -- no new schema field, no config syntax
change.

- **Storage.** Rule text is stored per project root as `rule/<key>` [plan
  documents](../../../../../current/docs/api-reference.md#manage_plan_documents) via the existing
  `manage_plan_documents` tool (or `PUT /api/v1/roots/{rootId}/plans/rule%2F<key>` -- note the
  required `%2F`, since the REST plan route takes one path segment) -- no new table, no migration.
  The plan document's `contentHash` doubles as the rule's `rulesVersion`.
- **Serving.** `query_rules` (MCP) and `GET /api/v1/roots/{rootId}/rules[/{key}]` (REST) read that
  store back, verbatim, never re-parsing or re-rendering the body. See
  [api-reference.md](../../../../../current/docs/api-reference.md#query_rules) and
  [api-rest.md](../../../../../current/docs/api-rest.md) section 19a for the full get/list
  contracts, parameter shapes, and error tables.
- **`query_items(schema)` stays pointer-only.** Rule text is never inlined into a resolved
  schema's `skill`/`skillPointer` field -- the caller always resolves the actual body through
  `query_rules`, either directly (`rootId`+`key`) or via the item's effective schema
  (`itemId`+`noteKey`).
- **Key grammar.** `^[a-z0-9][a-z0-9._-]{0,99}$` -- lowercase alphanumeric, `.`, `_`, `-`; must
  start with an alphanumeric; max 100 characters; never `/`, uppercase, or `:` (a key is one path
  segment, usable verbatim in a REST URL and as the `rule/<key>` plan-document slug suffix). The
  same grammar a `skill:` value must match for it to resolve as a rule key -- a `skill:` pointer
  that fails this grammar still works as a Skill-tool name, it simply cannot also resolve through
  `query_rules`.
- **Size budget.** A `rule/`-prefixed plan-document body is capped at **16384 bytes** (16 KiB,
  UTF-8) at stash time -- tighter than the general 64 KiB plan-document cap -- enforced by the same
  `PlanDocumentService.stash` pipeline both `manage_plan_documents` and the REST plan PUT route
  share, so `query_rules`/the rules REST routes never need their own size check on read.
- **Writing style.** Client-neutral prose usable by any executor invoking the rule, not
  Claude-specific — with an optional trailing `## claude:` section for Claude Code-specific detail.
  The server never parses the body; it is served exactly as stored.
- **This repo's own rules.** This repository keeps its source rule text git-tracked under
  `.taskorchestrator/rules/<key>.md` — readable and diffable like any other file, and the
  authoritative source an agent edits. The `config-sync` SessionStart hook pushes these files
  automatically: after its usual config sync it lists the workspace's `rules/*.md` files, hashes
  each (SHA-256 of the CRLF-normalized body), and compares against the server's per-key
  `rulesVersion` from `GET /api/v1/roots/{rootId}/rules`; a key whose local hash differs from (or
  is absent from) that listing is PUT to `PUT /api/v1/roots/{rootId}/plans/rule%2F<key>` and every
  other key is left untouched. A filename that doesn't match the server's key grammar
  (`^[a-z0-9][a-z0-9._-]{0,99}$`) or a body over 16384 bytes is skipped client-side and named in
  the hook's output line instead of being sent. Like the config sync itself, the rule sync is
  fail-open — a `rules/` dir that's absent, or any error talking to the API, degrades to a no-op
  or a one-line note rather than blocking session start.
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
  listing)` means the server's rules listing could not be read. `root <id> not found on this server`
  means the root was never created there, whereas `not synced — this server has no rules API.` means
  the server predates the rules routes.

---

## Lifecycle Modes

The `lifecycle` field on a schema controls automatic cascade behavior when children reach terminal:

| Mode | Behavior |
|------|----------|
| `auto` | Default — parent cascades to terminal when all children are terminal |
| `manual` | Terminal cascade suppressed — parent must be explicitly completed. Reopen cascade also suppressed. |
| `permanent` | Parent never auto-terminates and never auto-reopens — always manual lifecycle. |

An `auto-reopen` value is no longer a distinct mode: it is treated as `auto` (with a load warning),
since it behaved exactly like `auto` for terminal cascade and there was no reopen-on-create-or-
reparent path anywhere in the codebase to make it live up to its name.

---

## Matching Rules

Schema resolution uses **type-first lookup with tag fallback**, run per the item's root's effective
`schema_resolution` mode (`legacy` | `layered` | `isolated` — see "Global vs Per-Project Config"
below for the full per-mode precedence chains and how the effective mode is chosen):

1. If the item has a `type` field, look it up in `work_item_schemas` (exact match) against each
   config layer, in the order that mode's chain sets.
2. If no type or no type-based schema found, fall back to tag matching: **the first tag with an
   exact schema match wins.** A tag is never reported as "matched" merely because a `default`
   schema exists in that layer — a bug fixed as part of AR-39 (previously, the legacy global tag
   step could match an unrelated first tag whenever a global `default` existed, even though that
   tag had no schema of its own).
3. If nothing matches by type or tag, fall back to the schema named `default` — always the last
   resort, never a substitute for an exact match, tried per-layer at the point that mode's chain
   reaches it.
4. If nothing matches, the item is schema-free — no gate enforcement.

**Key points:**
- Setting `type` on an item is the preferred way to activate a schema
- Tags can still be used for schema matching (legacy), but `type` takes precedence
- Only one schema applies per item
- Matching is exact and case-sensitive
- With no `schema_resolution` key set anywhere, resolution is `legacy` — behaves exactly as it
  always has. `layered` and `isolated` are opt-in, per-document.

---

## Trait Merge Semantics

How trait notes merge into an item's resolved schema (`ToolExecutionContext.resolveSchema()` /
`mergeTraits()`):

1. **Base-schema note keys always win.** If a trait declares a note with the same `key` as one
   already in the schema's own `notes` list, the trait's note is dropped — the base schema's
   version (role, required, guidance, skill) is kept unchanged.
2. **`default_traits` merge before per-item `traits`.** The schema-level trait list is applied
   first; traits carried on the item's own `properties.traits` are layered on after.
3. **First-trait-in-order wins on duplicate trait keys.** When two traits being applied (across
   `default_traits` and per-item `traits`, in application order) both declare a note with the same
   key, the earlier one keeps its note — the later duplicate is dropped, same as rule 1.
4. **Trait notes append after base notes.** The final note list order is: base schema notes, then
   surviving trait notes in application order.
5. **Per-root trait notes replace the global trait's notes wholesale, per trait name.** There is no
   note-level merge between a per-root and a global trait sharing a name — resolution picks
   `perRoot ?: global` for the trait's *entire note list*, not a union of the two. (`dispatch` and
   `resources` layer per dimension — see "Dispatch (Trait Dimension)" below.)
6. **Traits can only add note keys — never override or relax a base-schema gate.** A trait cannot
   turn a base-schema `required: true` note optional, and it cannot change a base note's role; the
   only way a trait can affect an existing base key is to be silently ignored (rule 1).

**Example — base key wins:**

```yaml
work_item_schemas:
  feature-task:
    default_traits: [needs-task-review]
    notes:
      - {key: implementation-notes, role: work, required: true}

traits:
  needs-task-review:
    notes:
      - {key: implementation-notes, role: review, required: false}  # dropped — key collides with base
      - {key: review-checklist, role: review, required: true}       # added
```

---

## Resources (Trait Dimension)

A trait can declare `resources:` — a list of shared-resource requirements enforced by the server as
a **gate at WORK entry** (`advance_item(trigger="start")` from queue, and `trigger="resume"` from
blocked — both re-enter WORK). This is independent of the note-requirement dimension: a trait can
carry notes, resources, both, or neither.

### Declaration forms

```yaml
traits:
  needs-staging-slot:
    resources:
      - staging-db                     # short form — bare string
      - key: github-deploy-token       # long form — explicit fields
        mode: advisory                 # exclusive (default) | advisory
        ttlSeconds: 1800                # optional; overrides the registry default for this trait
```

| Field | Required | Default | Notes |
|-------|----------|---------|-------|
| `key` | yes | — | Resource key. Same charset as `credentialRefs`: `^[a-z0-9][a-z0-9\-_./]*$`, max 128 chars. An invalid key is warned-and-skipped (that one entry only — not fatal to the config load). |
| `mode` | no | `exclusive` | `exclusive` — server takes a lease at WORK entry; contention rejects the transition. `advisory` — no lease, no lock; the key is recorded into the transition's `consumedCredentials` audit trail only. |
| `ttlSeconds` | no | registry's `defaultTtlSeconds`, else `3600` | Bounds: clamped to `[1, 86400]`. |

### The optional top-level `resources:` registry

```yaml
resources:
  staging-db:
    description: "Human-readable note — what this key protects"
    defaultTtlSeconds: 3600    # bounds 1-86400; out-of-range or non-numeric warns and falls back to 3600
    maxHolders: 1              # v1 only supports 1 — see below
```

The registry is optional metadata: TTL defaults and a human-readable description for a key. A trait
can reference a key that has **no** registry entry at all — see "Undeclared keys" below. `maxHolders`
values greater than `1` are a **load error**: the whole registry entry is skipped (not clamped) with
a warning containing the literal text `maxHolders > 1 not yet supported` — storage is
semaphore-ready for a future multi-holder release, but v1 admission is capped at exactly 1 regardless
of what a config author writes here. A `maxHolders` value less than `1` is warned-and-clamped to `1`
(entry still created, unlike the `> 1` case). Reserved budget fields (`budgetLimit`,
`budgetWindowSeconds`) are parsed and warned as "reserved for future use" but never stored on either
the registry entry or a trait's resource requirement.

### Merge semantics — read this before combining traits

Resource requirements merge differently from notes in three specific ways — do not assume the note
merge rules (`Trait Merge Semantics` above) apply here.

1. **Union across traits, never dropped.** Unlike notes (where a base-schema key always wins and a
   colliding trait note is silently dropped), two traits declaring the *same resource key* both
   contribute — the merge is a union, deduplicated by key. Nothing is ever silently lost to a
   collision the way a duplicate note key would be.
2. **On a mode conflict for the same key, `exclusive` wins.** If one trait declares a key
   `advisory` and another (applied to the same item) declares it `exclusive`, the merged result is
   `exclusive` — the stricter declaration always wins, regardless of application order. `ttlSeconds`
   for a duplicate key keeps the **first-seen** value in trait-application order (base schema's
   `default_traits` first, then per-item `traits`); a later trait's `ttlSeconds` for an
   already-recorded key is ignored.
3. **The registry layers in the OPPOSITE direction from every other per-root setting — read this
   twice.** Every other per-root-honorable setting in this document (schemas, traits, `note_limits`,
   `status_labels`) is **per-root-wins**: a project's own config overrides the global floor for that
   project. The `resources:` **registry** inverts this: on a key collision between a per-root
   registry entry and the global registry entry, **the global entry wins**, and the collision is
   logged. Rationale: a resource key is a **server-global lock/lease namespace** — a real shared
   credential or staging slot is a singleton across the whole server, so one project must not be
   able to silently redefine another project's shared key's TTL or holder cap by pushing its own
   per-root config. (The *trait declarations themselves* — which resources a trait requires — still
   follow the normal per-root-wins layering; only the registry's global-wins inversion is special.)

### Undeclared keys — warn and honor, never skip

A trait can declare a resource key with **no matching registry entry** (in either layer). This is
**not** an error and does **not** disable enforcement — the key is warned (`"undeclared resource"`
in the log) and enforced anyway using default TTL (3600s). The registry is fail-open (an absent
entry never widens or weakens the lock); the lock itself is fail-closed (absence of registry
metadata never skips enforcement). Known residual risk: two projects that happen to pick the same
generic key name (e.g. `db`) for two genuinely different resources will falsely serialize against
each other. Mitigate with a naming convention that scopes keys to your project
(`proj-a/staging-db` rather than `staging-db`) — the config loader also warns when one key is
declared by three or more traits/schemas (fan-out), which is a useful early signal for this exact
collision.

### LEAF TASK TYPES ONLY for exclusive resources — a hard rule, not a suggestion

**Declare `mode: exclusive` resources on leaf task-level schemas only — never on a container/feature
schema (`lifecycle: manual` or `permanent` root containers, or any type with children).** The lease
is held for the ENTIRE time the declaring item sits in WORK. A feature container commonly stays in
WORK for the full duration of its child tree's implementation — hours to days. If a container-level
schema (or a container item's own `traits`) declares an exclusive resource, that resource is locked
for the container's whole WORK lifetime, not just for whichever child task actually needs it —
starving every other item that needs the same key for as long as the feature is in flight. This is
almost never the intended effect. Put the exclusive declaration on the leaf task type/trait that
actually performs the resource-touching work; use `advisory` (or no declaration at all) on container
types if you want audit visibility without the lock.

### Interaction with `credentialRefs`

Once an item declares at least one resource via its traits' `resources:` (registry state alone
does not trigger this — an item declaring nothing keeps the open rung-1 behavior), the `credentialRefs`
field on that item's `advance_item` transitions becomes a **closed set** — each supplied ref must
name a declared/registered key, or the transition is rejected with a validation error naming the
unrecognized ref and the known-key list. Declared keys (both `exclusive`, once acquired, and
`advisory`) are **auto-recorded** into `consumedCredentials` on work entry — you do not need to
repeat them in `credentialRefs`. Items that declare no resources are unaffected — `credentialRefs`
there stays an open, format-only-validated field (rung-1 behavior, unchanged). See
[`api-reference.md`](../../../../../current/docs/api-reference.md) for the full `credentialRefs`
validation contract and [`workflow-guide.md`](../../../../../current/docs/workflow-guide.md#11-resource-leasing)
for the contention/retry model and the full guarantees-vs-non-guarantees statement.

---

## Dispatch (Trait Dimension)

A trait can declare `dispatch:` — a map of workflow phase → **dispatch profile**, read by an
orchestrator (this plugin's shipped output styles, and orchestrator workflows such as a project's
implementation skill) to decide which agent type, model, and thinking effort to dispatch for the
phase owner. Like `resources:`, this is
independent of the note-requirement dimension — a trait can carry `notes`, `resources`, `dispatch`,
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
| Phase key | — | One of `queue`, `work`, `review` only — exact lowercase match. Any other key (`terminal`, `blocked`, `Work`, an unrecognized string) is a load warning and that phase entry is skipped; the trait's other phases still parse. |
| `agent` | no* | Opaque string passed as `subagent_type` — never validated against any registry. |
| `model` | no* | Opaque string passed as the Agent tool's `model` parameter. |
| `effort` | no* | One of `low`, `medium`, `high`, `xhigh`, `max`, matched **case-sensitively** (`High` is invalid). Has no Agent-tool parameter of its own — see "The Claude Code note" below. |

\* At least one of `agent` / `model` / `effort` must be present, or the profile is empty and
dropped with a load warning. An unknown profile field is ignored with a warning; a non-string or
blank field value is dropped with a warning (the rest of the profile, if any field still survives,
still parses).

Malformed entries warn-and-skip rather than failing the config load — an invalid field, phase, or
the trait's whole `dispatch` map is skipped at its own granularity, and the load still succeeds. A
`manage_project_config` push carrying invalid `dispatch` entries still succeeds too; the warnings
surface in the push response's existing `schemaWarnings` array alongside any other config
warnings. **Known limitation:** a non-string YAML key under `dispatch:` (e.g. a bare `true`/`1`
used as a phase key) is not fully covered by this warn-and-skip path yet — tracked separately.

### Precedence — the reverse of note merging, read this before combining traits

Resolving an item's dispatch profile for a phase walks trait names in this order: **per-item
`traits` first, then the schema's `default_traits`**, both deduplicated. This is the **opposite**
of how trait *notes* merge ("Trait Merge Semantics" above: `default_traits` first, then per-item
`traits`) — a note requirement escalates outward from the base schema, but a dispatch profile is a
per-item override: when an item's own `traits` parameter names a trait explicitly, that is the
explicit escalation, and it should win over whatever the item's type declares by default.

The **first trait in that order that has a profile for the requested phase wins outright — the
whole profile, never merged field-by-field across traits.** If a per-item trait A declares
`work: {agent: X}` and a `default_traits` trait B declares `work: {agent: Y, effort: high}`, the
resolved profile is exactly `{agent: X}` — B's `effort: high` is never folded in. Per-trait lookup
also honors the usual per-root-before-global order (see "Per-root layering" below) — **resolved
per trait as the traits are walked in order**, not only for whichever trait ends up winning the
phase.

### Per-root layering — no per-role fall-through within a trait

Same as `resources:` trait declarations (not the `resources:` *registry*, which layers the other
direction — see above): a per-root trait definition's `dispatch:` map **replaces** the global
trait's `dispatch:` map wholesale, for that trait name. There is **no per-role fall-through within
a single trait** — if a per-root trait's `dispatch:` map declares `work:` but omits `review:`, and
the global trait of the same name declares both, the resolved `review` profile for that trait is
**absent** for items in that root, not inherited from the global trait's `review` entry. To keep a
role's profile from the global trait while overriding another role, the per-root trait must restate
every role it wants to keep.

### Per-root layering is PER DIMENSION, not whole-trait

Read this before writing a per-root trait entry that touches only one of `notes:` / `dispatch:` /
`resources:`. The three dimensions resolve from three **separate** per-root maps
(`Snapshot.traits`, `Snapshot.traitDispatch`, `Snapshot.traitResources`), each falling back to its
own global counterpart independently, per trait name (`snapshot.traits[name] ?: global`,
`snapshot.traitDispatch[name] ?: global`, `snapshot.traitResources[name] ?: global` —
`ToolExecutionContext.mergeTraits()` / `resolveDispatchProfilesForTraits()` /
`resolveResourceRequirements()`). A per-root trait entry does **not** replace the global trait's
notes, dispatch, and resources together as one unit — only the dimensions that entry actually
declares are replaced; a dimension the entry omits still falls through to the global trait's value
for that dimension:

- **`notes` — shadows the global trait's notes even when the per-root `notes:` list is empty.**
  Unlike `dispatch`/`resources` below, the notes parser always populates `Snapshot.traits[name]`
  for any trait key present in a root's pushed `traits:` section, defaulting to `[]` when `notes:`
  is omitted. So a per-root trait entry that declares only `dispatch:` (no `notes:` key at all)
  still parses to an **empty note list** for that trait in that root, and that empty list wins over
  the global trait's notes ("Trait Merge Semantics" rule 5 above) — **keep this caveat**: a
  dispatch-only per-root override silently drops every note the global trait declared under that
  name, for items in that root. To layer a dispatch override on top of a global trait's existing
  notes, restate the `notes:` list in the per-root entry too.
- **`dispatch` replaces the global trait's `dispatch:` map only when the per-root trait entry
  itself declares a non-empty, valid `dispatch:` map.** If it doesn't (or the map is empty or every
  phase entry is invalid), `Snapshot.traitDispatch` has no entry for that
  trait name at all (absent, not an empty map), and resolution falls through to the global trait's
  `dispatch:` map unchanged — a per-root trait entry with only `notes:` does NOT blank the global
  dispatch profile.
- **`resources` works the same way as `dispatch`.** A per-root trait entry replaces the global
  trait's `resources:` list only when it declares a non-empty, valid `resources:` list itself; otherwise the global
  trait's resource requirements still apply.

In short: write only the dimension(s) you mean to override in a per-root trait entry — `dispatch`
and `resources` fall through cleanly to the global trait when omitted, but `notes` does not (the
caveat above), so a dispatch- or resources-only override must restate `notes:` explicitly if it
needs to keep the global trait's notes.

### Where the profile surfaces

| Surface | Shape |
|---------|-------|
| `advance_item` success result | `dispatch: {agent?, model?, effort?}` for the item's **new** role (`newRole`); the key is omitted entirely (never `null`/`{}`) when nothing resolves |
| `get_context(itemId=...)` (item mode) | `dispatch: {agent?, model?, effort?}` for the item's **current** role |
| `query_items(operation="schema", ...)` | `dispatch: {"queue"\|"work"\|"review": {agent?, model?, effort?}}` — one entry per resolved phase |
| REST `GET /api/v1/config/traits` | `TraitDto.dispatch: {<phase>: {agent?, model?, effort?}}` — **global config only**. This route resolves against the server-wide schema service, not any per-root snapshot, so a per-root `dispatch` override is not visible here even for a rooted item; read `query_items(operation="schema", itemId=...)` instead when the per-root-resolved profile is needed — that path always applies the item's own `rootId` automatically, no `rootId` parameter needed. |
| REST `GET /api/v1/config` | Same `TraitDto.dispatch` shape, embedded per trait in `ConfigSnapshotDto.traits` — **global config only**, same caveat as `/config/traits` above. |

### The Claude Code note — effort only via agent frontmatter

`effort` has no parameter on the Agent tool itself — Claude Code applies effort only through the
dispatched agent definition's own frontmatter (`effort: low|medium|high|xhigh|max`). This holds
**even when `agent` is also set**: Claude Code never forwards a profile's `effort` value to the
dispatch call, so the effort that actually applies is whatever the named agent definition's own
frontmatter declares, not necessarily the value the profile states. A profile's `effort` is
therefore always advisory in Claude Code; to make a particular effort actually apply, point
`agent` at a definition whose own frontmatter carries that `effort` — a profile naming neither
`agent` nor a matching definition has nothing to attach its `effort` to at all. Clients that call
the model API directly, rather than dispatching through Claude Code's Agent tool, may apply a
profile's `effort` field themselves. The plugin ships two agent definitions that declare `effort` this
way — `task-orchestrator:implementer` (`effort: medium`) and `task-orchestrator:reviewer`
(`effort: high`) — both using `model: inherit` in their own frontmatter, which is exactly why the
caller must still pass `model` explicitly on every dispatch of the phase owner — `dispatch.model`
when the profile sets one, otherwise its own model choice. See
[`output-styles/workflow-orchestrator.md`](../../../output-styles/workflow-orchestrator.md) →
Delegation for the consumer-side rule, followed identically by `schema-orchestrator.md`,
`schema-workflow`, and orchestrator workflows such as a project's implementation skill outside
this plugin.

---

## Seats (Trait & Schema Dimension)

A schema or trait can declare `seats:` — a list of named orchestration roles within a phase (A1).
Seats are **pure signals**: the server parses, validates, merges, and serves them, but does not
itself enforce who reads or writes what. They exist so an orchestrator (or a client reading
`get_context`/`query_items`) can tell *which* agent owns a phase's entry transition, *which* agent
owns each required note, and *which* dispatch profile (agent/model/effort) applies to each seat —
without the orchestrator hand-maintaining that mapping outside the config file. A config with no
`seats:` anywhere is completely unaffected: every seat-related response field is omitted, and the
one exception (`features`) is documented in "Byte-identity" below.

### Declaration form

```yaml
work_item_schemas:
  bug-fix:
    default_traits: [needs-test-author]
    seats:                                    # NEW, schema-level, list
      - { name: planner,      phase: queue }
      - { name: implementer,  phase: work, enters: true }
      - { name: extractor,    phase: work, after: [implementer] }
      - { name: orchestrator, phase: work }
      - { name: reviewer,     phase: review }
    notes:
      - { key: diagnosis, role: queue, required: true, seat: planner }
      - { key: implementation-notes, role: work, required: true, seat: implementer }
      - { key: session-tracking, role: work, required: true, seat: orchestrator }

traits:
  needs-test-author:
    seats:                                    # NEW, trait-level
      - { name: test-author, phase: work, after: [extractor], reads_exclude: [implementation-notes] }
    notes:
      - { key: test-plan, role: queue, required: true, seat: planner }
      - { key: test-manifest, role: work, required: true, seat: test-author, independent_of: [implementer] }

  delegated:
    dispatch:
      work:
        agent: task-orchestrator:implementer  # phase-level profile (unchanged)
        seats:                                # NEW per-seat overrides
          test-author: { agent: task-orchestrator:test-author, model: sonnet }
          extractor:   { agent: null, model: sonnet, effort: low }   # agent: null CLEARS the phase agent for this seat
```

### Seat-entry fields (`seats[]`, schema-level or trait-level)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `name` | yes | string | Non-blank, unique within its declaring scope (see "Fatal errors" below). Seat names are **opaque to the server** — no meaning is attached to any particular name, including `orchestrator`, except the reserved bucket name `unowned` (see "The `unowned` bucket" below), which a seat may never be named. |
| `phase` | yes | string | `queue`, `work`, or `review` only — exact lowercase match, same set as note `role`. |
| `enters` | no | boolean | Default `false`. Whether this seat is the one that transitions the item **into** `phase`. At most one seat may set this `true` per phase within a single `seats:` list, or within a schema's own seats plus its same-document `default_traits`' seats — see "Fatal errors" (F1). A conflict discovered only at resolve time (per-item traits, or traits supplied by a different config layer) is **not** fatal — see "Merge rules" below. |
| `after` | no | list of strings | Seat names this seat's work logically follows — an ordering **hint**, not enforced by the server. A name outside the declaring scope, or naming a seat in a different phase, is served exactly as declared; it never fails validation. |
| `reads_exclude` | no | list of strings | Note keys this seat should **not** read — a hint powering test-author-style blindness. Parsed and served only — not enforced by the server (blindness is a contract/process discipline, not a server-side check). This is a separate field from a note entry's own `independent_of` — see "Independence (A2)" below. |

### Note-entry fields (new on `notes[]`)

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `seat` | no | string | The seat (by name) that owns this note. Determines which bucket a missing required note lands in under `missingBySeat` — see "The `unowned` bucket" below. A note with no `seat`, or a `seat` naming an undeclared seat, or a `seat` declared for a *different* phase than the note's own `role`, is `unowned`. |
| `independent_of` | no | list of strings | Seat names this note's authoring must stay independent of (e.g. a test-author's `test-manifest` staying independent of the `implementer` seat). Parsed and served since A1; **enforced as of A2** — see "Independence (A2)" below for the attestation gate, its `independence:` config block, constraints, the `independence: temporal-only` waiver, and its honest limits. |

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
| `agent` / `model` / `effort` | no | Same types and validation as the phase-level fields (see "Dispatch (Trait Dimension)" above; `effort` is validated against the same case-sensitive set). Each field independently either overrides the phase default, **clears** it (an explicit YAML `null` for that field), or — when absent from the override entirely — falls through to the phase default. |

A phase map that carries **only** `seats:` (no `agent`/`model`/`effort` of its own) is valid — it
declares no phase-level profile, but its seat overrides still apply on top of whatever phase
default resolves from elsewhere (another trait, or none). This differs from the phase-level rule in
"Dispatch (Trait Dimension)" above: there, a phase map with no valid field at all is dropped with a
warning; here, `seats:` alone is enough to keep the phase map meaningful. An override map with no
field set at all (nothing overridden, nothing cleared) is dropped with a warning, the same rule as
an empty phase-level profile.

### Merge rules

Seats merge alongside notes when a trait's seats combine with a schema's base seats
(`ToolExecutionContext.resolveSchema()` / `LayeredConfig.mergeTraits()`), using the same
`default_traits`-then-per-item-`traits` trait ordering as note merging (see "Trait Merge Semantics"
above):

1. **Base-schema seats always win by name.** If a trait declares a seat with the same `name` as one
   already in the schema's own `seats:` list, the trait's seat is dropped with a warning — the base
   schema's version is kept unchanged. (A trait may *add* seats; it may not *redefine* a base seat.)
2. **First-trait-in-order wins on a duplicate seat name across traits.** Same rule as note merging:
   the earlier trait in application order keeps its seat; a later trait's same-named seat is
   dropped with a warning.
3. **Merged seat order is base seats, then surviving trait seats in application order** — the same
   ordering `missingBySeat` and the served `seats`/`dispatchBySeat` fields use.
4. **A second `enters: true` seat landing in a phase that already has one is demoted, not
   rejected.** Unlike a same-document conflict (fatal at load time — F1, see "Fatal errors" below),
   an `enters` conflict that only appears once traits are merged at resolve time (a per-item trait,
   or a trait supplied by a different config layer) is resolved deterministically: the seat that
   already has the phase keeps `enters: true`; the later seat is served with `enters` demoted to
   `false`, and a warning is logged. Resolution never throws for this case.
5. **Note `seat` / `independent_of` ride on the existing note-merge rules unchanged** — base-key-wins,
   first-trait-in-order wins for duplicate trait keys (see "Trait Merge Semantics" above).
6. **Per-seat dispatch overrides resolve item-traits-first, then `default_traits`** — the *opposite*
   order from note merging, matching the existing dispatch-profile precedence rule (see "Dispatch
   (Trait Dimension)" → "Precedence" above). For a given `(phase, seat)` pair, the first trait in
   that order that declares an override for it wins outright; its fields overlay the phase's
   already-resolved dispatch profile (`agent`/`model`/`effort` field-by-field, an explicit `null`
   clearing that field). The result is always **filtered to the seats present in the resolved
   schema's merged seats for that phase** — an override naming a seat the item's schema doesn't
   declare (for that phase) never surfaces. **A seat with no per-seat override of its own inherits
   the phase's already-resolved default dispatch profile as-is** (the same profile `dispatch.<phase>`
   reports); it is omitted only when that phase has neither a default profile nor an override for
   it (`LayeredConfig.mergeDispatchBySeat`).

### Per-root layering

Seats and per-seat dispatch overrides follow the same "declares the trait at all" unit as trait
notes (see "Per-root layering is PER DIMENSION, not whole-trait" above), but as **two additional,
independent** dimensions:

- **Trait seats** — if the per-root document declares the trait at all (its `traits:` map contains
  the trait's key, regardless of whether that entry itself carries a `seats:` key), the per-root
  seats win **wholesale** for that trait name (possibly an empty list, same as trait notes); else
  the global trait's seats apply.
- **Trait dispatch (by-role and by-seat) replace as ONE unit.** A per-root trait entry's `dispatch:`
  block — its phase-level profiles **and** its per-seat overrides together — replaces the global
  trait's whole `dispatch:` block wholesale, whenever the per-root document declares **either**
  shape for that trait. A per-root author who writes only `dispatch.<phase>.seats:` (no
  phase-level `agent`/`model`/`effort` at all) still replaces the *entire* inherited dispatch
  declaration for that trait, not just the seats sub-key — restate the phase-level fields too if
  you want to keep them from the global trait.
- **Base-schema seats** travel with whichever layer/mode supplied the base schema itself (see
  "Global vs Per-Project Config" above for the `schema_resolution` precedence chains) — there is no
  separate cross-layer seat merge for a schema's own (non-trait) seats.

### Fatal errors (F1–F4) — fail the whole config load or push

These are **structural** errors, checked over one "seats scope" at a time: a single `seats:` list
(a schema's own, or a trait's own), or — for a schema that names traits in its own
`default_traits`, when both are defined in the **same document** — the union of the schema's own
seats with those same-document default traits' seats. A config violating more than one rule fails
deterministically with the message for whichever rule is checked first — the fixed check order is
**F3, then F2, then F1, then F4** (reserved name, then duplicate name, then duplicate `enters` per
phase, then an `after` cycle) — not ascending numeric order.

| Code | Condition | Effect |
|------|-----------|--------|
| F1 | Two seats with `enters: true` in the same `phase`, within one seats scope. | Global config: **fails server startup**, message names the config path and the conflicting phase. Per-root push: **rejected** like a YAML syntax error — `manage_project_config` returns `VALIDATION_ERROR`, REST `PUT` returns `422 parse_error`; **nothing is stored**. |
| F2 | A duplicate seat `name` within one seats scope. | Same effect as F1. |
| F3 | A seat named `unowned` (the reserved bucket name — exact match only; `Unowned` is fine). | Same effect as F1. |
| F4 | An `after` cycle within one seats scope. | Same effect as F1. |

A conflict that only appears **at resolve time** — from a per-item trait, or from a trait supplied
by a *different* config layer than the schema — is never fatal; see "Merge rules" rule 4 above
(resolve-time `enters` demotion) instead.

**R3 — a pre-existing per-root row is not retroactively rejected.** A per-root config row stored
*before* a server upgrade that introduces a new fatal seat check (e.g. it has two `enters: true`
seats in one phase) is not deleted or invalidated by the upgrade. It follows the existing
last-known-good absence contract: the server logs a `WARN` and falls back to the global config for
that root until the row is re-pushed (which will then be rejected and must be fixed) — see
"Read-error handling" above for the general shape of this fallback. In practice, `config-sync`
re-pushes the workspace file at the next `SessionStart` and surfaces the rejection then.

### Warnings (W1–W6) — never fail the load

All of these add one entry to the document's `warnings` (global startup log) or a push response's
`schemaWarnings` array (per-root push), and the config still loads/stores successfully.

| Code | Condition |
|------|-----------|
| W1 | An unknown key on a note-schema entry. Known keys: `key`, `role`, `required`, `description`, `guidance`, `skill`, `maxLength`, `seat`, `independent_of`. |
| W2 | An unknown top-level section. Known sections: `work_item_schemas`, `note_schemas`, `traits`, `resources`, `note_limits`, `status_labels`, `schema_resolution`, `actor_authentication`, `project`, `retrospective`, `actor_attribution`. |
| W3 | An unknown key at schema level (known: `lifecycle`, `default_traits`, `notes`, `seats`) or at trait level (known: `notes`, `resources`, `dispatch`, `seats`). |
| W4 | A malformed `seats[]` entry: missing/blank `name` (entry skipped), invalid or missing `phase` (entry skipped), non-boolean `enters` (defaults to `false`), non-list `after`/`reads_exclude` (defaults to empty), or an unknown key on the entry (ignored). |
| W5 | DEC-11: in a schema whose *effective* seats (its own plus its same-document `default_traits`' seats) are non-empty, a **required** note (the schema's own notes plus its default traits' notes, base-key-wins) whose `seat` is null, or doesn't name a seat of the *same phase* as the note's own `role` — the warning names the note key and role, and states it will be served under `unowned` in `missingBySeat`. |
| W6 | A malformed `dispatch.<phase>.seats` sub-section: not a map (whole sub-section ignored), an individual seat's override not a map (that seat skipped), an override naming an unknown field (ignored), an override field with an invalid value — e.g. an invalid `effort` (dropped), or an override with no valid field and nothing cleared (the whole seat entry skipped). |

Probes worth knowing: an empty `seats: []` is treated identically to no `seats:` key at all
(byte-identical serving); mixed-case `phase` values (e.g. `Work`) are invalid — phases are
case-sensitive, same as note `role`; a blank-string `agent`/`model`/`effort` value in a seat
override is dropped (W6) and is **not** treated as a clear — only an explicit YAML `null` clears a
field.

### The `unowned` bucket

A required note that has no seat owning it — no `seat` declared, an unknown seat name, or a `seat`
declared for a different phase than the note's own `role` — is served under the reserved
`"unowned"` key in `missingBySeat` (see `api-reference.md` and `api-rest.md` for the exact response
shape). This is deliberate, not an error: it lets an orchestrator see at a glance which missing
notes have no seat routing at all, without the config load itself failing (W5 flags it as a
warning, not a fatal error, precisely so a schema can be seat-aware for *some* notes while others
stay unowned). `unowned` buckets are always sorted last among non-empty buckets; the reserved name
itself can never be used for a real seat (F3).

### Byte-identity

A `seats`-less config (no schema or trait anywhere declares `seats:`) produces **byte-identical**
MCP and REST responses to a pre-A1 server, with exactly **one** additive exception: the `features`
array on `query_items(operation="schema")`, `GET /api/v1/info`, and the `.well-known` service
descriptor (see `api-reference.md` / `api-rest.md`). No `seat`, `seats`, `dispatchBySeat`, or
`missingBySeat` field ever appears for a seat-less schema or item — these keys are omitted
entirely, never emitted as `null`/`{}`/`[]`.

---

## Phase Flow

```
queue ──(start)──► work ──(start)──► review ──(start)──► terminal
```

- If the schema has NO `role: review` notes, `start` from work goes directly to terminal (review is skipped)
- Required notes for the *current* phase gate the `start` trigger
- Optional notes (`required: false`) are shown but do not block advancement

---

## Design Principles

- **Keep it lean.** 2-3 required notes per phase is the practical maximum before agents find workarounds
- **Queue gates = pre-work contract.** Requirements, design, root cause — filled before agents touch code
- **Work gates = evidence of completion.** Implementation notes, test results — filled after implementation
- **Review phase is optional.** Only add `role: review` notes for genuine deploy/verify/sign-off steps
- **Optional notes are reminders.** `required: false` notes appear in `expectedNotes` without blocking
- **Use `type` for schema selection, tags for categorization.** Mixing them causes confusion.

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

## Mixed Legacy Example

If you have an existing config using `note_schemas`, you can add new schemas as `work_item_schemas` alongside it:

```yaml
work_item_schemas:

  feature-task:
    lifecycle: auto
    notes:
      - key: implementation-notes
        role: work
        required: true
        description: "What was built and why"

note_schemas:

  bug-fix:
    - key: root-cause
      role: queue
      required: true
      description: "Root cause analysis"
```

Items with `type: feature-task` use the `work_item_schemas` entry. Items with tag `bug-fix` use the legacy `note_schemas` entry.

---

## Actor authentication

The `actor_authentication` section is a top-level key alongside `work_item_schemas` and `traits`. It controls whether actor attribution is enforced on write operations.

```yaml
actor_authentication:
  enabled: true

work_item_schemas:
  # ...
```

Default: `false` when absent — actor authentication is opt-in.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `enabled` | yes | boolean | `true` = block write calls missing actor claims. `false` = no enforcement |

### Behavior

When `actor_authentication.enabled` is `true`, the plugin's PreToolUse hook blocks `advance_item` and `manage_notes(upsert)` calls that are missing an `actor` object on any element. The agent must retry with actor attribution included.

When `false` or absent, actor claims are optional — calls pass through with no enforcement. Actor claims can still be provided voluntarily.

### Verifier

The optional `verifier` sub-key enables server-side JWT validation of actor claims. It operates independently of `enabled` — a call can pass client-side enforcement (actor present) but still fail server-side verification (bad or expired JWT).

**Fields:**

| Field | Required | Type | Default | Notes |
|-------|----------|------|---------|-------|
| `type` | yes | string | `"noop"` | `"noop"` or `"jwks"` |
| `oidc_discovery` | no | string | — | OIDC discovery URL; auto-populates `jwks_uri` and `issuer` |
| `jwks_uri` | no | string | — | Direct JWKS endpoint URL (overrides OIDC-discovered value) |
| `jwks_path` | no | string | — | Local JWKS file path (relative to `AGENT_CONFIG_DIR`) |
| `issuer` | no | string | — | Expected `iss` claim (overrides OIDC-discovered value) |
| `audience` | no | string | — | Expected `aud` claim |
| `algorithms` | yes (under `type: jwks`) | list | n/a | Allowed signing algorithms; required and must be non-empty — an empty list fails startup |
| `cache_ttl_seconds` | no | number | `300` | JWKS cache TTL in seconds |
| `require_sub_match` | no | boolean | `true` | JWT `sub` must match `actor.id` |
| `stale_on_error` | no | boolean | `true` | Serve stale cached key set if JWKS endpoint is unreachable during refresh. Set `false` to propagate the fetch exception |
| `allow_insecure_url` | no | boolean | `false` | Opt-in to allow `http` (instead of `https`) for `oidc_discovery`/`jwks_uri`, only when the host is a literal loopback address (`localhost`, `127.x.x.x`, `::1` — no DNS resolution). Does not affect `jwks_path` or DID-trust mode |
| `max_token_lifetime_seconds` | no | integer | `86400` | Maximum accepted actor-proof lifetime in seconds (24h). A proof is rejected once `exp - iat` exceeds this value. Must be a positive integer no greater than `3153600000` (100 years) — `<= 0`, a non-integer, or a larger value fails startup. The REST API has the equivalent env var `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS` (same default and validation) for bearer tokens |
| `jti_replay_protection` | no | boolean | `false` | Opt-in. When `true`, actor proofs must carry a `jti` claim and each proof is single-use per MCP call (tracked in-memory, per server instance) — clients must mint a fresh proof for every call, including retries and heartbeats. Best-effort: the cache is bounded (10,000 entries, oldest evicted first) |
| `did_allowlist` | no | list | `[]` | List of trusted DID strings (exact match against JWT `iss` claim). Non-empty activates DID-trust mode |
| `did_pattern` | no | string | — | Glob pattern matching trusted DIDs (not regex). `*` matches only DID idchars `[A-Za-z0-9._-]` — never `%`, and never crosses a `:` segment boundary. Non-null activates DID-trust mode. May be combined with `did_allowlist` (either or both activate DID-trust); mutually exclusive only with the static-JWKS fields (oidc_discovery/jwks_uri/jwks_path) |
| `did_strict_relationship` | no | boolean | `true` | When true, only verification methods referenced from the resolved DID document's `assertionMethod` array are eligible. Set false to allow any key in the document |
| `did_loose_kid_match` | no | boolean | `true` | Allow single-key fallback when JWT `kid` not found in the resolved DID document AND the eligible-key set has exactly one entry. Multi-key documents always require exact `kid` match |

When `type: jwks`, at least one of `oidc_discovery`, `jwks_uri`, or `jwks_path` is required for static-JWKS mode. Explicit `jwks_uri` and `issuer` values override OIDC-discovered values when both are present.

**HTTPS rule.** Unless `allow_insecure_url: true`, `oidc_discovery` and `jwks_uri` (including a `jwks_uri` discovered via `oidc_discovery`) must use `https`; any other scheme, or `http` without `allow_insecure_url` AND a literal loopback host, fails startup. `jwks_path` (a local file) and DID-trust mode are exempt from this rule.

> **DID trust fields** apply only to `type: jwks`. Either `did_allowlist` (non-empty) or `did_pattern` (non-null) activates DID-trust mode — either or both may be set (not mutually exclusive with each other). In DID-trust mode, `oidc_discovery`, `jwks_uri`, and `jwks_path` must all be null. The two trust modes are validated at startup.

**Example — OIDC discovery (simplest):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    oidc_discovery: "https://agentlair.dev/.well-known/openid-configuration"
```

**Example — File-based (air-gapped):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    jwks_path: ".agentlair/jwks.json"
```

**Example — Full config (all options):**

> Static-JWKS mode requires exactly one of `oidc_discovery`, `jwks_uri`, or `jwks_path`. The other two source fields are shown commented out below to illustrate the available options.

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    oidc_discovery: "https://agentlair.dev/.well-known/openid-configuration"
    # jwks_uri: "https://provider.example/.well-known/jwks.json"   # alternative source
    # jwks_path: ".agentlair/jwks.json"                            # alternative source
    issuer: "https://provider.example"
    audience: "task-orchestrator"
    algorithms: ["EdDSA", "RS256"]
    cache_ttl_seconds: 300
    require_sub_match: true
```

**Example — DID-rooted trust (per-agent identities):**

```yaml
actor_authentication:
  enabled: true
  verifier:
    type: jwks
    algorithms: ["EdDSA", "RS256"]
    audience: "task-orchestrator"
    did_allowlist:
      - "did:web:agent.example.com"
      - "did:web:lair.dev"
    did_loose_kid_match: true
```

When `did_allowlist` or `did_pattern` is set, the verifier resolves the JWT's `iss` claim as a DID and validates the signing key against the resolved DID document's `verificationMethod` entries (subject to `did_strict_relationship`). See `current/docs/fleet-deployment.md` for the full deployment guide.

> **Note:** `enabled` (client-side enforcement) and `verifier` (server-side validation) are independent concerns. A call can pass enforcement (actor present) but have verification fail (bad JWT).

### `actor_attribution` (hook-local, independent of `actor_authentication`)

```yaml
actor_attribution:
  required: true
```

`actor_attribution.required` is a separate, hook-local key read only by the plugin's
`enforce-actor-attribution` hook — the server does not read it and ignores it on a per-root config
push (it falls under the server's `ignoredSections`/unknown-key handling, alongside any other key
the server doesn't recognize). Setting it to `true` makes the hook deny actor-less `advance_item`
and `manage_notes(upsert)` calls exactly like `actor_authentication.enabled: true` would, but without
requiring `actor_authentication`'s JWKS identity verification to be configured — useful for a single
project that wants local, client-side actor-presence feedback (e.g. as its own dogfood setting)
without standing up a full identity-verification pipeline. Either option alone is sufficient to
enforce; the two are independent and can be set together.

Default: `false`/absent — actor attribution is not required by this option.

---

## Project Scoping

The `project:` section is a top-level key alongside `work_item_schemas`, `traits`, and `actor_authentication`. It anchors this repository's work to a single depth-0 MCP item.

```yaml
project:
  rootId: "<uuid>"
  name: "<project name>"

work_item_schemas:
  # ...
```

Default: absent — a workspace with no `project:` block is unscoped.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `rootId` | yes | string (UUID) | UUID of the depth-0 item tagged `type: "project"` that anchors this repo's work |
| `name` | no | string | Human-readable project name shown in dashboards and skill output |

### Behavior

- **Ignored by the global config loader, but honored per-root.** The global/fallback loader (the one that reads `AGENT_CONFIG_DIR`'s `config.yaml` at startup) ignores this block. But when the full config text is pushed per-root via `manage_project_config`, the `project:` block IS honored server-side: the embedded `project.rootId` is checked as a mismatch guard against the target `rootId` (bypass with `force`), and `project` is never listed in a push response's `ignoredSections`. Locally, it's also read by Claude Code: the SessionStart hook and plugin skills use it to scope their output to `rootId`.
- **Created by** `/task-orchestrator:init` or `/adopt-project-scope` when the user opts into anchoring session context to a single project root item.
- **Personal root.** `/task-orchestrator:init --user` writes a `project:` block into the user-level file whose root is a `type: project` item tagged `personal-root`. It is anchor-only: new items are parented under it, but reads stay unscoped (no `ancestorId`/`anchorId`), so the dashboard may show other projects' items.
- **Opt-in convention.** Scoping is not enforced — its absence just means skills operate without a default root anchor, falling back to unscoped behavior.
- The same scoping is pushed server-side via `manage_project_config` so it's visible beyond this local config file; see the project-scoping integration docs for the full push mechanism. In practice this push is triggered automatically by `init` (P5 for a project, U3 for a personal root) and by `manage-schemas`' write-operation report step (Step 4) whenever a `project.rootId` is present — both push the full config file text, not just this block.

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

> **manage-schemas write operations must preserve this block untouched** — see the create/edit/delete workflow docs in this skill folder.

---

## Retrospective

The `retrospective:` section is a top-level key alongside `work_item_schemas`, `traits`, `actor_authentication`, and `project`. It controls how the plugin's hooks behave when an implementation run reaches terminal — whether they merely suggest running `/session-retrospective`, or actively direct an agent to dispatch one in the background.

```yaml
retrospective:
  mode: nudge          # nudge (default) | dispatch | off | headless (reserved)
  dispatchThreshold: 3 # items terminal since the last retrospective directive required to auto-spawn (default 3)
  cooldownMinutes: 30  # minutes before the same run can trigger another directive (default 30)
  github_feedback:                     # optional
    enabled: true                      # default false — opt-in
    repo: jpicklyk/task-orchestrator   # optional; this is the default
```

Default: `nudge` — applied when the `retrospective:` block or `mode` key is absent, or the value is unrecognized.

### Fields

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `nudge`, `dispatch`, `off`, or the reserved `headless`. Defaults to `nudge` |
| `dispatchThreshold` | no | integer >= 0 | Only meaningful in `mode: dispatch`. Count of items that reached terminal since the last retrospective directive (nudge or dispatch) required before a hard background dispatch fires. Below this, the run still gets a nudge — it is never silent. Defaults to `3`; a non-integer or negative value falls back to the default. |
| `cooldownMinutes` | no | number > 0 | Minutes before the same run can trigger another nudge/dispatch directive. Defaults to `30`; an invalid value falls back to the default. |
| `github_feedback.enabled` | no | boolean | Opt-in. When `true`, the session-retrospective skill files a GitHub enhancement issue for **global-scoped** improvement proposals via the `gh` CLI. Defaults to `false`. Requires `gh` installed and authenticated; filing is skipped gracefully otherwise. |
| `github_feedback.repo` | no | string | `owner/repo` target for filed issues. Defaults to `jpicklyk/task-orchestrator` (the plugin's upstream). |

### Modes

| Mode | Behavior |
|------|----------|
| `nudge` (default) | Hooks inject a suggestion to run `/session-retrospective` when an implementation run reaches terminal. The agent decides whether to act on it. |
| `dispatch` | Hooks inject a directive that launches exactly one background retrospective agent, but only once the run's substance (items reached terminal since the last directive) clears `dispatchThreshold`. Below threshold, the hook still injects the `nudge` suggestion instead of staying silent — nothing goes unreported, only the automatic background spawn is reserved for substantial runs. The directive is **durable, not immediate**: it instructs the orchestrator to dispatch at the next run boundary — holding it while background tasks or subagents are still in flight, and merging any directives that accumulate while holding into a single dispatch covering the union of their roots. |
| `off` | Hooks stay silent — no retrospective suggestion or dispatch directive is injected. |
| `headless` (reserved) | **Not implemented yet.** Intended for a future version where the hook spawns a detached `claude -p` retrospective session outside the current conversation. Today, setting `mode: headless` falls back to `nudge` behavior, same as any other unrecognized value. |

**Backstop is always nudge-only.** The `Stop` hook (`retro-backstop.mjs`) — which escalates a lone terminal item that never got a `PARENT_COMPLETION` follow-up — always injects a nudge, regardless of configured mode. It fires on a signal that, by construction, is not a confirmed run boundary, so it never escalates to a hard dispatch even in `mode: dispatch`. When it has no identifiable root to scope the suggestion to, it omits the UUID argument entirely rather than falling back to the whole project — letting `/session-retrospective`'s own narrower fallback scan apply.

### Behavior

- **Client-side only.** This block is read directly from the workspace file by the plugin hooks (`PostToolUse` hook `retro-trigger.mjs` and `Stop` hook `retro-backstop.mjs`) — the MCP server never interprets it. Per-root `config-sync` still pushes the file verbatim; the server reports `retrospective` under `ignoredSections` in the push response, same as any other section it doesn't resolve against (see "Global vs Per-Project Config" below) — the nested `github_feedback` keys ride along in that same ignored `retrospective` section on per-root push.
- **No hot-reload needed.** Because hooks read the file fresh on every fire (unlike the server's cached/per-root schema resolution), an edit to `retrospective.mode` takes effect on the next hook trigger — no `/mcp` reconnect required.
- **`project.rootId` recommended.** The hooks key their dedup marker (which prevents a duplicate nudge or dispatch directive from firing twice for the same run) on `project.rootId` when present. This gives reliable self-suppression once the background retrospective subagent completes its own item and the run is recognized as closed out. Without a configured `rootId`, dedup falls back to a weaker session-local signal.
- **`github_feedback` is skill-read, not hook-read.** Unlike `mode`, `dispatchThreshold`, and `cooldownMinutes` (read by the hooks as scalars), the nested `github_feedback` block is read by the skills (`session-retrospective`, `review-proposals`) as plain YAML — the hooks' scalar reads are unaffected by its presence.
- **Held directives can be lost — known trade-off.** Because the dedup marker is stamped when a directive is *emitted* (not when it is acted on), a directive the orchestrator is holding for a run boundary exists only in conversation context. If the session is killed or the context compacts before the last background task completes, that retrospective is silently skipped — the `Stop` backstop cannot re-raise it. This is accepted by design (the alternative — re-raising from the marker — would recreate mid-run noise); recover by running `/session-retrospective` manually. Nothing is lost in fidelity by the delay itself: dispatched retrospectives work only from durable MCP state, never conversation context.

---

## Global vs Per-Project Config

Config resolves in **two layers**, chosen per work item by its `rootId`:

| Layer | Where it lives | Reload | Scope |
|-------|----------------|--------|-------|
| **Global** | the `AGENT_CONFIG_DIR/.taskorchestrator/config.yaml` file | read once at server startup (restart to reload) | one per server — the **fallback/default** |
| **Per-root** | pushed into the DB per project-root UUID (via `manage_project_config` or `PUT /api/v1/roots/{rootId}/config`) | **hot-reloaded** on every schema-resolving read — no restart | one per project root |

**Startup failure.** A structurally broken/unparseable global config.yaml, or an invalid or wrong-typed `actor_authentication` field within it, fails server startup outright (the parse exception propagates uncaught). The one exception is `status_labels`: a malformed `status_labels` value is caught, logged as a WARN, and the server falls back to defaults instead of refusing to start.

### `schema_resolution`: choosing how the two layers combine

An opt-in top-level key, `schema_resolution: legacy | layered | isolated`, controls how a root's
per-root and global layers combine for schema/tag lookup (every other facet — traits, resources,
`note_limits`, `status_labels` — layers the same way regardless of mode; see their own sections).
It can be set in the global config, in a per-root pushed document, or both. The **effective mode**
for a given root is resolved with no extra I/O, in this order:

1. That root's own per-root document's `schema_resolution` key, if it sets one.
2. Otherwise the global config's `schema_resolution` key, if it sets one — with one exception: a
   global file's `schema_resolution: isolated` has no per-root layer above it to isolate *from*, so
   it is treated as `layered` instead, with this load-time warning added verbatim:

   > `schema_resolution: isolated has no effect in the global config (nothing to isolate from); treating as layered`
3. Otherwise `legacy` — absent everywhere resolves exactly as it always has. This is a pure opt-in:
   no existing deployment's resolution changes because of this feature alone (aside from the tag-
   matching fix in "Matching Rules" above, which applies in every mode).

An unrecognized value (wrong type, wrong case, or any string other than the three above) is treated
as absent — it never fails config load — and adds exactly one warning naming the raw value,
verbatim:

> `Unrecognized schema_resolution value '<raw>' (expected legacy, layered or isolated); treating as absent`

On a per-root push (`manage_project_config` / `PUT /roots/{rootId}/config`), this same warning text
surfaces as one entry in the response's `schemaWarnings` array — see the push response documented
in `api-reference.md` / `api-rest.md`.

For an item with a `rootId`, the type-lookup precedence per mode is:

| Mode | Type-lookup precedence |
|---|---|
| **legacy** (effective when the key is absent everywhere) | 1. Per-root exact match on `item.type` → 2. Per-root `default` schema → 3. Global exact match on `item.type` → 4. Global `default` schema |
| **layered** | 1. Per-root exact match on `item.type` → 2. Global exact match on `item.type` → 3. Per-root `default` schema → 4. Global `default` schema |
| **isolated** | 1. Per-root exact match on `item.type` → 2. Per-root `default` schema — **the global layer is never consulted** |

The tag lookup follows the same per-mode shape, always exact-match-only per "Matching Rules" above:
- **legacy** is **whole-algorithm-first** — the entire per-root resolution (first-matching-tag,
  then per-root `default`) runs to completion before the global tag algorithm (first tag with an
  exact global match, then global `default`) is consulted at all. This means a per-root `default`
  schema wins over a **global exact type match** — once a root has pushed its own config, that
  config is treated as the root's complete self-description, not a patch layered on top of the
  global floor.
- **layered** is **exact-first**: per-root's first exact tag match, then the global layer's first
  exact tag match, are both tried before either layer's `default` — so a global exact type or tag
  match beats a per-root `default` under `layered`.
- **isolated** tries only the per-root layer's tags, then its `default`; the global layer is never
  reached.

An item with no `rootId` uses the global file only, in every mode. Behavior is byte-identical to a
single-file setup when no per-root config has been pushed and no document sets `schema_resolution`.

**Layer roles:** global = server-wide floor; per-root = the project's complete self-description,
which can lower the floor to zero via the empty default (see below) — this description of the
`default` schema's role holds under `legacy`; under `layered`/`isolated`, prefer
`schema_resolution: isolated` for that purpose (see "Schema-free / non-dev / business-workflow
projects" below).

**This project's own split.** In this repository, the **global** config
(`deploy/global-config/.taskorchestrator/config.yaml`, mounted via `AGENT_CONFIG_DIR`) carries
*only* the process/self-improvement schemas that every project sharing the server should get for
free — `agent-observation`, `session-retrospective`, `improvement-proposal`, and the generic
`container` schema. Project-specific schemas (`feature-implementation`, `feature-task`, `bug-fix`,
and the `traits:` section) live entirely in this project's own git-tracked
`.taskorchestrator/config.yaml` and reach the server per-root via the `config-sync` hook — run at
SessionStart and again mid-session whenever the file changes, via the SessionStart hook's
`watchPaths` + a `FileChanged` hook entry — they are never part of the global floor. Because per-root resolution is whole-algorithm-first
(see above), this project's per-root `default` schema (if any) or exact-type match for its own
schemas beats a global exact-type match, even though in practice the global floor only defines
process schemas this project's config doesn't redeclare.

**Precedence — the workspace file is canonical; the per-root DB row is a synced replica.** The `config-sync.mjs` hook (fired at SessionStart, and again mid-session on a `FileChanged` event for the watched config.yaml — plus the `init` and `manage-schemas` push steps) copies the local `.taskorchestrator/config.yaml` into the per-root store whenever it changes. Durable edits belong in the **file**: a runtime `manage_project_config` push that isn't reflected in the file is overwritten at the next sync (session start, or a mid-session file-change re-sync). A byte-identical file is a no-op (fingerprints match) — and fingerprints are computed after normalizing away a leading UTF-8 BOM and CRLF-vs-LF line endings (nothing else), so a Windows checkout of the exact same content also matches, even though its raw bytes differ.

**Line endings.** Both sides compute the fingerprint over the config text with one leading BOM stripped and every `\r\n` replaced with `\n` — the stored/served bytes themselves are never rewritten, only the value fed into the hash. This means a `core.autocrlf`-checked-out `.taskorchestrator/config.yaml` (CRLF on Windows, LF elsewhere) fingerprints identically regardless of checkout platform. Projects that track `config.yaml` in git should still add `**/.taskorchestrator/*.yaml text eol=lf` to `.gitattributes` (this repo does) to keep the file itself byte-stable across platforms and avoid noisy diffs — normalization only protects the fingerprint comparison, not `git diff` or editors that don't understand the normalization rule. Mixed-version caveat: an OLD plugin talking to a NEW (normalizing) server never matches its raw-byte hash against the server's normalized fingerprint for a CRLF/BOM file, so it reports a false `superseded`/mismatch on **every** session — not one-time — until the plugin is upgraded. A NEW plugin talking to an OLD server has the opposite, harmless problem: its normalized hash never matches the server's raw hash for a CRLF/BOM file, so config-sync re-uploads on every session until the server is upgraded. With matched versions, a root sees at most one `unknown` relation and one re-push after rollout (fingerprint history holds raw hashes; no backfill, by design). LF-only files without a BOM are unaffected in every case. Recommendation: upgrade the server and the plugin together.

**Fast-forward guarded.** The server keeps a per-root fingerprint history (newest first, pruned to
20), so file-canonical precedence is enforced, not just assumed. A push whose fingerprint is
**known-old** — present in that history but not the root's current fingerprint (e.g. a stale
checkout that missed a later push made from another machine) — is rejected server-side (REST `409
superseded`; MCP tool `CONFLICT_ERROR`) instead of silently reverting the later change.
`config-sync.mjs` checks this on every sync — session start, or a mid-session `FileChanged` re-sync — via `GET .../config?fingerprint=<local-sha256>`:
a `superseded` relation skips the push and surfaces a message telling the agent to pull or copy the
server's config back before editing, rather than pushing over it. `current` (already in sync) and
`unknown` (brand-new content, or an older server that predates this guard — no `relation` field
returned) both proceed as before. `force: true` (or `?force=true`) bypasses the guard when a
deliberate revert or overwrite is intended.

**Read-error handling.** If a per-root config read itself fails (the underlying repository call errors), the server serves the last-known-good cached parse for that root when one exists. Only when there is no cached parse yet (a cold root hitting a read error on its very first resolution) does the call fail, surfaced as errorCode `config_unavailable` / errorKind `transient` — callers should apply their own backoff and retry rather than treating it as "no per-root config, fall back to global".

**Per-root honorable settings.** `note_limits`, `status_labels`, `resources`, and
`schema_resolution` are layered the same way as schemas/traits: a per-root document that
**explicitly** sets `note_limits.mode`, a trigger under `status_labels`, the `resources` top-level
key, or `schema_resolution` wins for that root; a per-root document that **omits the key
entirely** falls through to the global value, unchanged. `schema_resolution` was parsed but *not*
yet honored per-root before AR-39 — it is honored now, so a per-root document that sets it no
longer appears in `ignoredSections` on push. An unrecognized `schema_resolution` value adds one
`schemaWarnings` entry on push (see above) but does not block the push or fall back to a different
key.
`status_labels` falls through **per trigger** — a per-root map that only overrides `start` still
defers to the global config for `complete`, `block`, etc. (and a trigger explicitly mapped to
`null` in the per-root doc means "no label for this trigger", which is different from the trigger
being absent). A pushed document's response (`manage_project_config` push, or
`PUT /roots/{rootId}/config`) reports which top-level keys it contains that are NOT honored per-root
in an additive `ignoredSections` field.

> **`resources` is the one exception to "per-root wins" in this list — read it separately.** Every
> other honored section above (schemas, traits, `note_limits`, `status_labels`) resolves
> per-root-wins: the project's own config beats the global floor. The `resources:` **registry**
> specifically inverts this — on a key collision, the **global** registry entry wins over a
> per-root one, because a resource key is a server-wide lock namespace, not a per-project setting.
> This applies only to the registry entries (`resources: <key>: {...}`), not to which resources a
> trait declares — trait resource declarations still layer per-root-wins like every other trait
> field. See "Resources (Trait Dimension)" above for the full merge semantics and the rationale.
>
> Also unlike every other validation failure documented in this file (which degrades gracefully to
> a default), a registry entry with `maxHolders > 1` is dropped entirely rather than clamped — see
> "Resources (Trait Dimension)" above.

**Global-only settings.** `actor_authentication` is **not** part of the per-root layer — the
resolver reads it only from the global file. A per-root document may carry it, but it is ignored
(and reported in `ignoredSections`); keep it in the global config.

`actor_attribution` is not a server concept at all — it is read only by the plugin's
`enforce-actor-attribution` hook directly from the workspace's `.taskorchestrator/config.yaml`. If
it's present in a document pushed to the server (e.g. via `config-sync`), the server ignores it the
same way it ignores any other unrecognized top-level key.

### Schema-free / non-dev / business-workflow projects

A project that has no notion of "notes to fill" — non-dev workflows, business-process tracking,
or anything using Task Orchestrator purely for status/dependency tracking — can push a per-root
config with `schema_resolution: isolated` and an **empty default schema** to fence off the global
config entirely:

```yaml
schema_resolution: isolated
work_item_schemas:
  default:
    lifecycle: auto   # or manual / permanent for workflow-style projects
    notes: []
```

`schema_resolution: isolated` makes this explicit and unconditional: the global layer is never
consulted for this root, for any type or tag, regardless of what it defines. This is the
**preferred** way to fence a root off — it does not depend on an empty `default` shadowing every
type, so it also fences types the global config exact-matches, which an empty default alone would
not do under `layered`.

**Without `schema_resolution` set (legacy, the default), an empty per-root `default` schema still
fences the root**, because legacy's per-root resolution is whole-algorithm-first: this `default`
entry resolves for every item type in this root (no exact type match needed) with zero required
notes — every gate passes automatically, regardless of what the global config requires for the same
type elsewhere. The global config is never consulted for this root once this per-root default
resolves.

**An empty per-root `default` does *not* fence a root under `schema_resolution: layered`** — under
`layered`, a global *exact* type match is tried before the per-root `default`, so any type the
global config defines exactly still resolves globally despite the per-root empty default. Use
`isolated`, not an empty `default`, to fence a root once it opts into `layered`.

**Why the global default still matters even with per-root fencing available:** stdio mode (no
per-root DB row exists at all — items resolve via `rootId == null`), the bootstrap window before a
workspace's first `manage_project_config` push, legacy items with a null `rootId` predating the
root-backfill, and process-global containers that intentionally sit outside any single project
root. The global default is the floor these cases fall back to.

---

## Note Body Length Limits

Top-level `note_limits.mode` (`warn`, default, or `reject`) governs what happens when a note body exceeds its schema `maxLength`, checked at `manage_notes` upsert time against the resolved body (inline `body` or file-read via `bodyFromFile`): `warn` accepts the note with a `warning` field on its result; `reject` fails that note with `code: NOTE_BODY_TOO_LONG`.

```yaml
note_limits:
  mode: warn   # warn | reject
```

This mode is per-root honorable (see "Global vs Per-Project Config" above): a project root that
pushes its own `note_limits.mode` overrides the global mode for that root's items only; a per-root
document with no `note_limits` section at all defers to the global mode unchanged.

Notes are a compression boundary: keep bodies distilled prose, and route verbatim artifacts (test output, diffs, logs) through `bodyFromFile` rather than pasting them inline.

## Independence (A2)

A note-schema entry's `independent_of` (see "Note-entry fields" under "Seats" above) declares which
other seat(s) a note's *authoring* must stay independent of — e.g. a test-author's `test-manifest`
staying independent of the `implementer` seat's notes. A2 turns this from a served-only hint (A1)
into an attestation gate: at `start`/`complete` (and the terminal/start cascade gates), the server
compares the ACTOR who wrote a declaring note against the actor(s) who wrote the seat(s) it names,
and reports (or, in `reject` mode, blocks on) any conflict.

### Config block

```yaml
independence:
  mode: warn            # off | warn | reject — default warn
  require_verified: false  # default false
```

| Field | Required | Type | Notes |
|-------|----------|------|-------|
| `mode` | no | string | `off` (the predicate is never evaluated — `violations` is absent from every surface), `warn` (default; violations are computed and reported but never block), or `reject` (a non-waived violation blocks the transition). Must be a YAML **string**: an unquoted `off` parses as the YAML 1.1 boolean `false`, not the string `"off"` — **always quote it** (`mode: "off"`). A non-string or unrecognized value falls back to `warn` with a load warning — never silently, never toward `off`. |
| `require_verified` | no | boolean | When `true`, a declaring note (or a conflicting seat's note) whose verification status is not `VERIFIED` (see `actor_authentication` above) also raises an `unverified` violation. Default `false`. A non-boolean value falls back to `false` with a load warning. |

An unknown sub-key under `independence:` warns and is ignored. A present-but-non-map `independence:`
value warns and is treated as the block being present with defaults (`warn`/`false`). The block is
per-root honorable: **a per-root `independence` block replaces the global one WHOLESALE** (both
`mode` and `require_verified` together, as one unit — not merged field-by-field) whenever the
per-root document declares the key at all; a per-root document with no `independence` section at all
defers to the global block unchanged (mirrors `note_limits.mode`'s per-root-wins-when-present rule,
see "Note Body Length Limits" below, but as one wholesale unit rather than per-field).

### Constraints

For each note-schema entry `N` with a non-empty `independent_of` (a "declaring entry") whose note is
FILLED (non-blank body), the server checks `N`'s note against every FILLED note owned by each seat
`N.independent_of` names ("S-notes"), and reports up to three kinds of finding:

| Constraint | Raised when |
|---|---|
| `same_actor` | `N`'s note and a conflicting seat's note share the same identity (see "Identity" below). |
| `missing_actor` | `N`'s note, or a conflicting seat's note, has no actor claim at all — **fail-closed**: an untagged (actor-less) note is never treated as automatically independent, it is flagged. |
| `unverified` | `require_verified: true` and the relevant note's verification status is not `VERIFIED`. |

Each finding is a `{key, seat?, constraint, conflictingSeat?, waived?}` object: `key` is the
declaring note's key, `seat` its owning seat (omitted when the schema is not seat-aware),
`conflictingSeat` the seat the finding was raised against (omitted for a `missing_actor`/`unverified`
finding raised against the declaring note itself, rather than against a specific conflicting seat),
and `waived` present (`true`) only when the temporal-only waiver applies (see below). **These objects
are actor-free by construction — never an actor id, proof, or claim, on any surface, MCP or REST.**

**Identity.** A note's identity for comparison is its verification's `proofClaims.sub` when its
verification status is `VERIFIED` and that `sub` is non-null; otherwise its self-reported
`actorClaim.id`. Comparison is an exact, case-sensitive string match — see "Honest limits" below for
what this does and does not catch.

**Evaluation scope.** For `start` (and the queued-parent start cascade), only declaring entries whose
`role` equals the item's CURRENT phase are evaluated. For `complete` (and the terminal cascade, and
`complete_tree`), every declaring entry across ALL phases is evaluated. `cancel`, `block`/`hold`,
`resume`, and `reopen` never evaluate independence — only `start`/`complete` triggers do. The gate
paths that surface findings are: `advance_item` (both the direct `checkGate` and both cascade gates),
`complete_tree`, `get_context`, the REST `GET /items/{id}/gate` route, the REST advance route's
success and `422 gate_blocked` failure bodies, and the plugin's SubagentStop phase-guard hook (which
trusts the gate route's own `canAdvance` rather than re-deriving `reject`-mode blocking itself).

### The `independence: temporal-only` waiver

A declaring note may waive its own `same_actor` findings by making its body's **first line** (after
stripping one trailing `\r` and trimming) equal exactly `independence: temporal-only`, case-sensitive.
The waiver only takes effect when the declaring note's `createdAt` is strictly AFTER the `createdAt`
of every same-identity conflicting note it would otherwise collide with — i.e. the same actor wrote
both notes, but the declaring note is attested to have been written with the other note already
visible, which the schema owner accepts as sufficient independence for that pairing. A waived finding
carries `"waived": true` and **never blocks a transition in any mode**, `reject` included.

The waiver applies ONLY to `same_actor` findings — it never waives `missing_actor` or `unverified`
findings, even when it also appears on a note that has one of those. An actor-less declaring note
with a waiver first-line still raises (and still blocks, in `reject` mode) its `missing_actor`
finding.

### Blocking

A transition is blocked only when `mode: reject` AND at least one finding is not `waived`. `warn`
mode never blocks — the transition applies, and `violations` on the result simply reports what was
found. `off` mode never computes findings at all: `violations` is absent (not `[]`) everywhere for
that item. `canAdvance` (`get_context`, REST `GET /items/{id}/gate`) already folds this in:
`canAdvance = !terminal && missing.isEmpty() && !independenceBlocks`, the same pattern the missing-
required-notes check already used.

### JSON emission

The `violations` key's presence rule differs by surface, and the difference is deliberate (a bare
gate READ should tell a client "the check ran and found nothing" vs "the check doesn't apply here",
while a transition RESULT should stay quiet unless there is something to report):

- **`gateStatus`** (`get_context`, REST `GET /items/{id}/gate`) emits `violations` whenever the
  check applies — mode is not `off` and the item's resolved schema declares `independent_of` in any
  phase — **including an empty array** `[]` when the check ran and found nothing. It is absent for a
  terminal item.
- **Everywhere else** — a successful `advance_item`/REST-advance result, each `cascadeEvents` entry,
  a REST `422 gate_blocked`'s `details.violations`, and an `advance_item`/`complete_tree`
  applied-or-failure entry (`complete_tree` in `warn` mode included) — `violations` is emitted
  **only when the list is non-empty**; an empty result is simply omitted, not sent as `[]`.
- **On every surface**, `violations` is absent entirely when independence `mode` is `off`, or when
  the relevant schema (the item's own for `gateStatus`/an applied entry, the cascade's own target
  schema for a `cascadeEvents` entry) declares no `independent_of` anywhere.

### Honest limits

A2 is an **attestation-consistency check, not an enforcement mechanism**, unless it is paired with
`actor_authentication` and `require_verified: true`. Understand what it can and cannot catch before
relying on it:

- **Without `actor_authentication` + `require_verified`, identity is a self-reported `actorClaim.id`
  — whatever string the caller supplied.** Nothing on the write path verifies that the caller is who
  they claim to be; the check only verifies that two notes claim to be from different callers.
- **Identity comparison is exact-match, case-sensitive.** Two ids differing only by case or
  whitespace (`Implementer-1` vs `implementer-1`, or a trailing space) are treated as genuinely
  distinct identities — the check cannot detect that they were meant to be the same caller, and it
  equally cannot detect that a caller deliberately varied its id to dodge the check.
- **The check is last-writer-wins.** Re-upserting a note (`manage_notes(upsert)` or a REST note
  write) with a different `actor` replaces the note's actor claim outright; there is no history of
  prior claims to compare against, so a later re-attribution silently changes which findings a note
  contributes to.
- **A per-root config push can weaken or disable the gate for that root** — `mode: off` or dropping
  `require_verified` in a per-root `independence` block replaces the global policy wholesale for
  every item under that root, with no separate confirmation step beyond the ordinary
  `manage_project_config` push.
- **Two parties sharing one actor id always raise `same_actor` — a false positive the check cannot
  tell apart from a genuine collision.** If two different people or processes both write notes under
  the same `actorClaim.id` (deliberately or by copy-pasted configuration), every note they write is
  indistinguishable to this check — it raises `same_actor` between them every time, even when by the
  real-world-actor meaning of "actor" they are genuinely independent; the check only knows the string
  in `actorClaim.id`, not who is really typing.
- **REST note writes ignore any client-supplied actor field.** A REST note write's actor is always
  the server-resolved caller identity, never a value the request body can set. Under
  `API_AUTH_MODE=none` every REST write is attributed to the single actor `api:local-unauth`, so ALL
  notes written over REST while unauthenticated collide as `same_actor` with each other — REST
  independence checking is meaningless in that mode. Bearer-token REST writes are attributed to
  `api:<tokenId>`/`api:<sub>` and so are distinguishable from each other, but they are UNCHECKED for
  verification purposes — under `require_verified: true` a bearer REST write's note is always
  `unverified`, regardless of how strong the bearer token's own authentication was.
- **Only FILLED notes are compared, and a filled note can be unfilled again.** Deleting a note's body
  or blanking it out erases its findings — the check re-evaluates from current note bodies each time,
  it does not remember that a conflicting note ever existed. `start` only ever evaluates the
  declaring note's OWN current phase (not every phase the way `complete` does), so a same-actor
  conflict recorded in an earlier phase's S-note is invisible to a later phase's `start` check unless
  that earlier note is still filled at evaluation time.
- **Under `require_verified: true`, a VERIFIED note with no proof `sub`** (non-DID trust, a JWKS
  config with `requireSubMatch: false`, or a JWT lacking a `sub` claim) **falls back to the
  self-reported `actorClaim.id` for identity** — so a single credential can present two different
  identities to the check depending on what `actor` string the caller chose to send. Separately,
  identity resolution differs by transport for JWKS-verified writes: a REST JWKS write is identified
  as `api:<sub>` while an MCP JWKS-verified note resolves to the bare `<sub>` — the same real-world
  principal writing one note over REST and another over MCP is NEVER flagged `same_actor` between
  those two notes, even with `require_verified: true` and full JWKS verification (a documented,
  pre-existing limit, unchanged by A2).
- **A per-root config push can weaken or disable the gate in more ways than the obvious one.**
  Beyond a deliberate `mode: off` or a dropped `require_verified`, an empty, non-map, or bad-`mode`
  per-root `independence` block still resolves to `mode: warn, require_verified: false` (the same
  fallback a malformed *global* block gets) and, because a present per-root block always wins
  wholesale, this silently downgrades a stricter global `reject` policy for every item under that
  root. A per-root schema push can also strip a note's `independent_of`/`seat` declaration entirely —
  once stripped, the gate has nothing to evaluate for that note and simply stops emitting findings,
  with no error or warning that independence checking went inert for it. `manage_project_config` over
  MCP has no capability gate of its own, unlike the REST `PUT` route which requires `WRITE_CONFIG` —
  anyone who can call `manage_project_config` over MCP can weaken or disable the independence gate
  for a root.
- **The `independence: temporal-only` waiver is self-service and has no config-level off switch.**
  Whoever writes the declaring note is the same party who can add the waiver marker to it, and for
  the common case of a single agent writing its own notes sequentially, the ordering requirement
  (`N.createdAt` after every same-identity `S`-note's `createdAt`) is satisfied automatically simply
  by writing last. In practice this makes the waiver an opt-out any note-writer can apply to itself
  under `reject` mode — there is no config knob to disable the waiver mechanism for a schema, trait,
  or root.
- **Even with attribution redaction on, non-admin callers still learn the relation between notes.**
  `API_REDACT_NOTE_ATTRIBUTION` hides the actor id itself, but a `same_actor` or `missing_actor`
  finding is still visible on `gateStatus.violations` and the advance/cascade/failure surfaces to any
  caller who can read them — so a non-admin caller cannot see WHO wrote two notes, but can still see
  THAT they were (or were not) written by the same actor.
- **Under `API_AUTH_MODE=none`, every REST note write carries the same actor, `api:local-unauth`**
  (REST ignores client-supplied actor fields — see above). Any two notes written over REST in that
  mode therefore always raise `same_actor` against each other, and the REST layer adds no identity
  assurance of its own.

These limits are by design: A2 is scoped as attestation, not identity verification. The exception
is the `require_verified` identity gaps above (a VERIFIED note without a proof `sub`, and the REST
`api:<sub>` vs MCP `<sub>` split), which a planned follow-up hardens; until then, do not treat
`require_verified: true` as identity enforcement. Pair the check with `actor_authentication` for a
stronger guarantee, and treat a bare `warn`/`reject` config as a discipline aid, not a security
control.

---

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration — orphaned notes become ad-hoc notes on their items.
