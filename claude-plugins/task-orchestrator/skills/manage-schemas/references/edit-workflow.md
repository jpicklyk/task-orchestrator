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

When writing guidance for the new note, apply these generation rules:
- **Lead with the consumer** — open with who reads this note and what they need from it
- **Structure over prose** — use bold section headers (`**Header**`) if the note covers 3 or more topics
- **Concrete over generic** — "state which files changed" not "describe the approach"
- If adding a `session-tracking` note, offer the standard structured guidance: `"This note feeds retrospective analysis. Structure: **Outcome**: success | partial | failure. **Files changed**: list with one-line rationale each. **Deviations**: from plan or diagnosis. **Friction**: tool errors, unexpected roundtrips, API confusion — include type and description. **Test results**: pass/fail counts, new tests added."`

### Remove a Note
Show numbered list, ask which to remove. Warn: "Existing notes on items under this key will become orphaned ad-hoc notes — they won't be deleted but will no longer be schema-enforced."
Remove the entry from the YAML list.

### Toggle Required
Show numbered list, ask which to toggle. Flip `required: true` to `false` or vice versa.
Warn if toggling to required: "Items that have already passed this phase without this note will not be retroactively blocked, but new items will be gated."

### Change Description/Guidance
Show current value, ask for the new value. Apply.
Remind: descriptions should stay under 80 chars.

#### Cross-Schema Duplication Check

After the user provides a new guidance value, scan all schemas in `.taskorchestrator/config.yaml` for notes with the **same key name** (e.g., `session-tracking`, `implementation-notes`).

If any other schema contains a note with the same key:
1. Compare guidance text — classify as **identical** (exact match), **near-identical** (same structure, minor wording differences), or **different**
2. Present the finding:

```
◆ This key also appears in other schemas:
  - "feature-task" → session-tracking (guidance is identical)
  - "bug-fix" → session-tracking (guidance is near-identical)

Update all instances?
  1. Yes — update all to the new value
  2. No — only update this schema
  3. Show me the differences first
```

3. If the user selects "Show me the differences", display the current schema's old value alongside each other schema's current value, then re-ask
4. Apply based on choice — when updating all, report each schema updated in the Write Back summary

This check triggers on **key name match** regardless of guidance similarity. The value is catching drift cases where the same concept (e.g., `session-tracking`) has diverged across schemas over time.

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
