# Validate Schema Workflow

Structural and semantic validation of `.taskorchestrator/config.yaml`.

---

## Validation Checks

Run the following checks in order. Collect all issues before reporting.

### 1. YAML Syntax
Parse the file. If YAML is invalid, report the parse error with line number (if available) and stop — further checks cannot proceed.

### 2. Top-Level Structure
- `note_schemas` key must exist at the top level
- `note_schemas` must be a mapping (not a list or scalar)
- No other top-level keys expected (warn if found — they may be from a different tool)

### 3. Schema Entry Structure
For each schema under `note_schemas`:
- Schema key should be kebab-case (warn if not — e.g., contains underscores or uppercase)
- Value must be a list of note definitions (not a mapping or scalar)

### 4. Note Definition Fields
For each note in each schema:
- `key` — required, must be a string, should be kebab-case
- `role` — required, must be one of: `queue`, `work`, `review`
- `required` — required, must be a boolean (`true` or `false`)
- `description` — required, must be a string
- `guidance` — optional, must be a string if present

### 5. Field Quality
- `description` should be under 80 characters (warn if longer)
- `key` values must be unique within a schema (error if duplicated)
- At least one note should have `required: true` (warn if all notes in a schema are optional — the schema has no gate effect)

### 6. Cross-Schema Consistency
- No duplicate schema keys under `note_schemas` (YAML silently uses the last one)
- Warn if a schema has only `role: review` notes and no queue/work notes (unusual pattern)

---

## Report Format

```
◆ Config Validation — .taskorchestrator/config.yaml

  Schemas found: 3
  Total notes: 12

  Errors (must fix):
    ✗ bug-fix.fix-summary: missing "required" field
    ✗ bug-fix: duplicate key "test-verification"

  Warnings (recommended):
    ! feature-implementation.deploy-notes: description is 94 chars (recommended: under 80)
    ! research-spike: all notes are optional — schema has no gate effect

  ✓ 1 schema passed all checks: agent-observation
```

If no issues are found:

```
◆ Config Validation — .taskorchestrator/config.yaml

  ✓ Valid — 3 schemas, 12 notes, no issues found
```
