# Validate Schema Workflow

Structural and semantic validation of `.taskorchestrator/config.yaml`.

**The server is the authority.** The `config` tool's `validate` op runs the same pipeline a push runs (structure against the JSON Schema, the parser's findings, then semantics) without writing, and returns `{valid, errors[{path, reason}], warnings[{path, reason}]}` plus a diff against the root's head and, for a valid document, `affectedInFlight`. When the file has a `project.rootId`, call it first and report its result; the local checks below are for a file with no root, for a quick pre-read, and for the plugin-level checks the server does not make (kebab-case keys, description length, skill names). See [config-reference.md](../../../../../current/docs/config-reference.md#strict-validation), "Strict validation", for every rule the server enforces.

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, CRLF replaced by LF>"}}
```

Hand-edited files fail on the strict rules most often, so check these before pushing:

- An unknown key is an error, reported at the key's own path (`additionalProperties: false` at every modeled level).
- Enumerated values are lowercase and case-sensitive: `lifecycle: auto`, resource `mode: exclusive`, note `role: queue`.
- A note entry needs a valid `role`; a seat needs a `name` and a `phase`; a dispatch `effort` must be a listed value.
- `lifecycle: none` schemas may declare notes (all optional) but no `seats` and no `required: true` note.
- `resources` is rejected in a per-root document (it belongs to the server file).
- A top-level `orchestration`, `retrospective`, `actor_attribution`, `schema_resolution` or `actor_authentication` is a problem (see Check 2).

---

## Validation Checks

Run the following checks in order. Collect all issues before reporting.

### 1. YAML Syntax
Parse the file. If YAML is invalid, report the parse error with line number (if available) and stop — further checks cannot proceed.

### 2. Top-Level Structure
- `work_item_schemas` is optional; when present it must be a mapping (not a list, scalar or null); the legacy `note_schemas` form is accepted by the server but cannot appear in the same document as `work_item_schemas`
- `traits` is an optional top-level key — if present, must be a mapping
- `project` is an optional top-level key — recognized, not schema-related; if present, must be a mapping containing `rootId` (string, required) and optionally `name` (string). Do not flag as unknown and do not modify it. See `references/config-format.md` → Project Scoping
- `extensions` is an optional top-level key — if present, must be a mapping of namespaces; its contents are opaque to the server and are never edited here. Plugin settings live under `extensions.claude-code` (`references/config-format.md` → "Plugin settings")
- `resources` is an optional top-level key — recognized, not schema-related; if present, must be a mapping of resource key → `{ description?, defaultTtlSeconds?, maxHolders? }`. Do not flag as unknown. See `references/config-format.md` → "Resources (Trait Dimension)"
- `note_limits`, `status_labels`, `independence`, `runs` and `decision_classes` are recognized server-owned keys; do not flag as unknown
- Flag these as problems, each with the fix:
  - `actor_authentication` (error — a leftover key fails server startup): delete it. Caller identity comes from the server's `API_AUTH_MODE`
  - `auditing`, `schema_resolution` (remove): both are gone in 4.0
  - a top-level `orchestration` or `retrospective` (error on push): move it under `extensions.claude-code`
  - `actor_attribution` (remove): its hook is gone
- Any other top-level key is an error on a push (warn when checking locally without a root)

### 3. Schema Entry Structure

**For `work_item_schemas` entries:**
- Schema key should be kebab-case (warn if not)
- Value must be a mapping containing optional `lifecycle`, optional `default_traits`, optional `seats`, and an optional `notes` list
- `lifecycle` (if present) must be one of: `auto`, `manual`, `permanent`, `none` (lowercase only; the removed `auto-reopen` is an error). `none` makes the items lifecycle-free: no notes may be `required: true` and no `seats` may be declared (see [Lifecycle-free items](../../../../../current/docs/config-reference.md#lifecycle-free-items))
- `default_traits` (if present) must be a list of strings
- `notes` must be a list of note definitions

**For `traits` entries:**
- Trait key should be kebab-case (warn if not)
- Value must be a mapping. `notes` (list), `resources` (list), `dispatch` (map) and `seats` are each
  optional — a trait may declare any combination, including `dispatch` and/or
  `resources` with no `notes` key at all (a dispatch-only or resources-only trait is valid, not an
  error). Warn (not error) if a trait declares none of them — an empty, no-op trait.
- Each note follows the same field rules as schema notes
- `dispatch` (if present), see `references/config-format.md` → "Dispatch (Trait Dimension)" for the
  full field reference: phase keys are `queue`/`work`/`review` only (case-sensitive); each phase
  profile needs at least one of `agent`/`model`/`effort` (or the phase map carries only `seats:`); `effort` (if set) must be one of `low`,
  `medium`, `high`, `xhigh`, `max` (case-sensitive)

### 4. Note Definition Fields
For each note in each schema (and trait):
- `key` — required, must be a string, should be kebab-case
- `role` — required, must be one of: `queue`, `work`, `review`
- `required` — optional; must be a boolean if present. The parser defaults it to `false` when omitted, so a missing `required` is **not** a hard error — **warn** (recommend setting explicitly, since it silently becomes `false` = no gate)
- `description` — optional; must be a string if present. Parser defaults to `""` when omitted — warn if missing
- `guidance` — optional, must be a string if present
- `skill` — optional, must be a string if present
- `shared` — optional; must be a boolean if present (`true` exempts the key from note ownership)

### 5. Field Quality
- `description` should be under 80 characters (warn if longer)
- `key` values must be unique within a schema (error if duplicated)
- At least one note should have `required: true` (warn if all notes in a schema are optional — the schema has no gate effect)
- `skill` values should reference skills that exist in the project (warn if unknown — not an error since skill availability varies)

### 6. Cross-Schema Consistency
- No duplicate schema keys within `work_item_schemas` (YAML silently uses the last one)
- Warn if a schema has only `role: review` notes and no queue/work notes (unusual pattern)
- `default_traits` values should reference trait keys defined in the `traits:` section (error on a push if neither the document nor the server config defines the trait; warn when checking locally)

### 7. Runs and Decision Classes
- `runs.stall_after` is an integer of at least 1; `runs.budgets.tokens`, `attempts_per_seat` and `max_active_seats` are integers of at least 1; `runs.budgets.cost_usd` is a number above 0 (error otherwise)
- Each `decision_classes` name matches `^[a-z][a-z0-9_-]{0,63}$` (error otherwise)
- `answerers` is one of `human-only`, `director-or-human`, `any-actor`; `severity` is one of `high`, `normal`, `low`; `on_expiry` is `escalate`; `expires_after` is an integer of at least 1 (error otherwise)
- A limit class (`run-budget`, `attempt-cap`) must not use `answerers: any-actor` (error)
- Warn on a `human-only` class when the server runs over stdio (no human credential exists there)

---

## Report Format

```
◆ Config Validation — .taskorchestrator/config.yaml

  work_item_schemas: 3 schemas
  traits: 2 traits
  project: "Task Orchestrator" (rootId set)
  server check: config validate → valid: false (2 errors, 1 warning)

  Errors (must fix):
    ✗ /work_item_schemas/bug-fix/notes/0/role: "queued" is invalid (must be queue/work/review)
    ✗ bug-fix: duplicate key "test-verification"

  Warnings (recommended):
    ! bug-fix.implementation-notes: missing "required" field — defaults to false (set explicitly)
    ! feature-implementation.deploy-notes: description is 94 chars (recommended: under 80)
    ! research-spike: all notes are optional — schema has no gate effect
    ! container: lifecycle is "auto" — consider "manual" for organizational containers

  ✓ 2 schemas passed all checks: feature-task, agent-observation
```

If no issues are found:

```
◆ Config Validation — .taskorchestrator/config.yaml

  ✓ Valid — 3 schemas, 2 traits, 14 notes, no issues found (server check: valid)
```
