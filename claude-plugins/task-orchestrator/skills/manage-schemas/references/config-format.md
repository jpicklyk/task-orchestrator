# Config Format Reference

YAML format and field rules for `.taskorchestrator/config.yaml`.

---

## File Location

- Path: `.taskorchestrator/config.yaml` in the project root (alongside `.claude/`, `.git/`, etc.)
- Create the directory if it doesn't exist: `.taskorchestrator/`
- This file is typically gitignored (runtime/project config, not source code)
- The server reads and caches this file on first schema access — changes require MCP reconnect (`/mcp`)

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

Schema resolution uses **type-first lookup with tag fallback**:

1. If the item has a `type` field, look up the schema by type in `work_item_schemas` → direct lookup (exact match)
2. If no type or no type-based schema found, look up by first tag match in `note_schemas` (legacy)
3. If no tag matches, fall back to the schema named `default` (in either section) if one exists
4. If nothing matches, the item is schema-free — no gate enforcement

**Key points:**
- Setting `type` on an item is the preferred way to activate a schema
- Tags can still be used for schema matching (legacy), but `type` takes precedence
- Only one schema applies per item
- Matching is exact and case-sensitive

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
- **Created by** the `quick-start` bootstrap flow or `/adopt-project-scope` when the user opts into anchoring session context to a single project root item.
- **Opt-in convention.** Scoping is not enforced — its absence just means skills operate without a default root anchor, falling back to unscoped behavior.
- The same scoping is pushed server-side via `manage_project_config` so it's visible beyond this local config file; see the project-scoping integration docs for the full push mechanism. In practice this push is triggered automatically by `manage-schemas`' write-operation report step (Step 4) and by `quick-start`'s bootstrap step (Step 1.5) whenever a `project.rootId` is present — both push the full config file text, not just this block.

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

For an item with a `rootId`, every schema / tag / trait lookup is **whole-algorithm-first**: the
entire per-root resolution runs to completion before the global layer is consulted at all. For the
type lookup, that precedence is:

1. Per-root exact match on `item.type`
2. Per-root `default` schema
3. Global exact match on `item.type`
4. Global `default` schema

The tag lookup follows the same shape (per-root first-matching-tag, then per-root `default`,
*then* the equivalent global steps). This means a per-root `default` schema wins over a **global
exact type match** — once a root has pushed its own config, that config is treated as the root's
complete self-description, not a patch layered on top of the global floor. An item with no
`rootId` uses the global file only. Behavior is byte-identical to a single-file setup when no
per-root config has been pushed.

**Layer roles:** global = server-wide floor; per-root = the project's complete self-description,
which can lower the floor to zero via the empty default (see below).

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

**Precedence — the workspace file is canonical; the per-root DB row is a synced replica.** The `config-sync.mjs` hook (fired at SessionStart, and again mid-session on a `FileChanged` event for the watched config.yaml — plus the `manage-schemas` / `quick-start` push steps) copies the local `.taskorchestrator/config.yaml` into the per-root store whenever it changes. Durable edits belong in the **file**: a runtime `manage_project_config` push that isn't reflected in the file is overwritten at the next sync (session start, or a mid-session file-change re-sync). A byte-identical file is a no-op (fingerprints match) — and fingerprints are computed after normalizing away a leading UTF-8 BOM and CRLF-vs-LF line endings (nothing else), so a Windows checkout of the exact same content also matches, even though its raw bytes differ.

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

**Per-root honorable settings.** `note_limits`, `status_labels`, and `resources` are layered the
same way as schemas/traits: a per-root document that **explicitly** sets `note_limits.mode`, a
trigger under `status_labels`, or the `resources` top-level key wins for that root; a per-root
document that **omits the key entirely** falls through to the global value, unchanged.
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
config with an **empty default schema** to fence off the global config entirely:

```yaml
work_item_schemas:
  default:
    lifecycle: auto   # or manual / permanent for workflow-style projects
    notes: []
```

Because per-root resolution is whole-algorithm-first, this `default` entry resolves for every item
type in this root (no exact type match needed) with zero required notes — every gate passes
automatically, regardless of what the global config requires for the same type elsewhere. The
global config is never consulted for this root once this per-root default resolves.

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

---

## Key Stability Warning

Changing a `key` after notes have been written orphans existing notes under the old key. There is no automatic migration — orphaned notes become ad-hoc notes on their items.
