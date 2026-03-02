# Edit Schema Workflow

Modify an existing schema in `.taskorchestrator/config.yaml`.

---

## Load Current Schema

Read `.taskorchestrator/config.yaml` and locate the target schema by key name. If the schema name was not provided in `$ARGUMENTS`, list all available schemas and ask the user to pick one via `AskUserQuestion`.

Display the current schema in full:

```
◆ Current Schema — "bug-fix"

| # | Key | Role | Required | Description |
|---|-----|------|----------|-------------|
| 1 | reproduction-steps | queue | yes | Step-by-step reproduction with expected vs actual result |
| 2 | root-cause | queue | yes | Why it happens — file, line, and condition |
| 3 | fix-summary | work | yes | What was changed and which files were modified |
| 4 | test-verification | work | yes | How the fix was verified and test results after fix |
```

---

## Determine Change Type

Ask via `AskUserQuestion`:

```
What would you like to change?
  1. Add a new note to this schema
  2. Remove an existing note
  3. Toggle required/optional on a note
  4. Change a note's description or guidance text
  5. Rename a note key (WARNING: orphans existing notes under the old key)
```

---

## Apply Changes

### Add a Note
Ask for: key (kebab-case), role (queue/work/review), required (yes/no), description (under 80 chars), guidance (optional).
Append the new note entry to the schema's list in the correct role group.

### Remove a Note
Show numbered list, ask which to remove. Warn: "Existing notes on items under this key will become orphaned ad-hoc notes — they won't be deleted but will no longer be schema-enforced."
Remove the entry from the YAML list.

### Toggle Required
Show numbered list, ask which to toggle. Flip `required: true` to `false` or vice versa.
Warn if toggling to required: "Items that have already passed this phase without this note will not be retroactively blocked, but new items will be gated."

### Change Description/Guidance
Show current value, ask for the new value. Apply.
Remind: descriptions should stay under 80 chars.

### Rename Key
Show current key, ask for the new key name.
**WARNING:** "Renaming a key orphans all existing notes written under the old key name. Those notes will remain on their items as ad-hoc notes but will no longer match the schema. There is no automatic migration. Proceed?"
Require explicit confirmation before applying.

---

## Write Back

Write the modified config back to `.taskorchestrator/config.yaml`. Show a diff-style summary of what changed:

```
✓ Schema "bug-fix" updated:
  + Added: test-plan (work, required)
  Gate impact: work phase now requires 3 notes (was 2)
```

Remind: **MCP reconnect required** (`/mcp`) for changes to take effect.
