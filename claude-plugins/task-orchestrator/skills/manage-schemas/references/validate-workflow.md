# Validate Schema Workflow

Structural and semantic validation of `.taskorchestrator/config.yaml`.

---

## Validation Checks

Run the following checks in order. Collect all issues before reporting.

### 1. YAML Syntax
Parse the file. If YAML is invalid, report the parse error with line number (if available) and stop — further checks cannot proceed.

### 2. Top-Level Structure
- At least one of `work_item_schemas` or `note_schemas` must exist at the top level
- Each must be a mapping (not a list or scalar)
- `traits` is an optional top-level key — if present, must be a mapping
- `actor_authentication` is an optional top-level key — if present, must be a mapping containing `enabled` (boolean)
- `actor_authentication.verifier` (if present) must be a mapping
- `verifier.type` must be one of: `noop`, `jwks` (warn on unknown type)
- When `type: jwks`: at least one of `oidc_discovery`, `jwks_uri`, `jwks_path` must be set (error if none) — unless DID-trust mode is active
- `algorithms` (if present) must be a list of strings
- `cache_ttl_seconds` (if present) must be a positive number
- `require_sub_match` (if present) must be a boolean
- `stale_on_error` (if present) must be a boolean
- `did_allowlist` (if present) must be a list of strings
- `did_pattern` (if present) must be a string
- `did_strict_relationship` (if present) must be a boolean
- `did_loose_kid_match` (if present) must be a boolean
- `did_allowlist` and `did_pattern` are mutually exclusive — error if both are set
- DID-trust mode (non-empty `did_allowlist` or non-null `did_pattern`) is mutually exclusive with static-JWKS mode (`oidc_discovery`/`jwks_uri`/`jwks_path`) — error if both are configured
- No other top-level keys expected (warn if found)

### 3. Schema Entry Structure

**For `work_item_schemas` entries:**
- Schema key should be kebab-case (warn if not)
- Value must be a mapping containing optional `lifecycle`, optional `default_traits`, and a `notes` list
- `lifecycle` (if present) must be one of: `auto`, `manual`, `auto-reopen`, `permanent`
- `default_traits` (if present) must be a list of strings
- `notes` must be a list of note definitions

**For `note_schemas` entries (legacy):**
- Schema key should be kebab-case (warn if not)
- Value must be a list of note definitions (flat format, no `lifecycle` or `notes` wrapper)

**For `traits` entries:**
- Trait key should be kebab-case (warn if not)
- Value must be a mapping containing a `notes` list
- Each note follows the same field rules as schema notes

### 4. Note Definition Fields
For each note in each schema (and trait):
- `key` — required, must be a string, should be kebab-case
- `role` — required, must be one of: `queue`, `work`, `review`
- `required` — required, must be a boolean (`true` or `false`)
- `description` — required, must be a string
- `guidance` — optional, must be a string if present
- `skill` — optional, must be a string if present

### 5. Field Quality
- `description` should be under 80 characters (warn if longer)
- `key` values must be unique within a schema (error if duplicated)
- At least one note should have `required: true` (warn if all notes in a schema are optional — the schema has no gate effect)
- `skill` values should reference skills that exist in the project (warn if unknown — not an error since skill availability varies)

### 6. Cross-Schema Consistency
- No duplicate schema keys within `work_item_schemas` or `note_schemas` (YAML silently uses the last one)
- Warn if the same schema key appears in both `work_item_schemas` and `note_schemas` (type-first lookup will always prefer `work_item_schemas`)
- Warn if a schema has only `role: review` notes and no queue/work notes (unusual pattern)
- `default_traits` values should reference trait keys defined in the `traits:` section (warn if not found)

---

## Report Format

```
◆ Config Validation — .taskorchestrator/config.yaml

  work_item_schemas: 3 schemas
  note_schemas: 1 schema (legacy)
  traits: 2 traits
  actor_authentication: enabled, verifier: jwks

  Errors (must fix):
    ✗ bug-fix.fix-summary: missing "required" field
    ✗ bug-fix: duplicate key "test-verification"

  Warnings (recommended):
    ! feature-implementation.deploy-notes: description is 94 chars (recommended: under 80)
    ! research-spike: all notes are optional — schema has no gate effect
    ! container: lifecycle is "auto" — consider "manual" for organizational containers

  ✓ 2 schemas passed all checks: feature-task, agent-observation
```

If no issues are found:

```
◆ Config Validation — .taskorchestrator/config.yaml

  ✓ Valid — 3 schemas, 2 traits, 14 notes, actor_authentication enabled, no issues found
```
