---
name: manage-schemas
description: "Creates, views, edits, deletes, and validates note schemas for the MCP Task Orchestrator in .taskorchestrator/config.yaml — the templates that define which notes agents must fill at each workflow phase. Use when user says: create schema, show schemas, edit schema, delete schema, validate config, what schemas exist, add a note to schema, remove note from schema, or configure gates."
argument-hint: "[optional: action + schema name, e.g. 'view bug-fix', 'create research-spike', 'validate']"
---

# Manage Schemas — Note Schema Lifecycle

Create, view, edit, delete, and validate note schemas in `.taskorchestrator/config.yaml`. Schemas define which notes agents must fill at each workflow phase before advancing items.

---

## Step 1 — Determine Intent

Classify from `$ARGUMENTS` and conversation context before making any tool calls.

| Signal words | Action |
|---|---|
| "create", "build", "new", "add schema", "define", "set up" | CREATE |
| "show", "view", "list", "what schemas", "display" | VIEW |
| "edit", "modify", "change", "update", "add note to", "remove note from" | EDIT |
| "delete", "remove schema", "drop" | DELETE |
| "validate", "check", "verify", "lint" | VALIDATE |

If `$ARGUMENTS` contains both an action and a schema name (e.g., "view bug-fix"), extract both. If intent cannot be determined, ask via `AskUserQuestion` with options: Create, View / Validate, Edit, Delete.

> Validate is grouped with View — both are read-only operations on the config file.

---

## Step 2 — Config Bootstrap

Check if `.taskorchestrator/config.yaml` exists by reading it.

**If the file does not exist:**
- For **VIEW** or **VALIDATE**: report "No schemas configured — `.taskorchestrator/config.yaml` does not exist." and stop.
- For **CREATE**, **EDIT**, or **DELETE**: create the `.taskorchestrator/` directory if missing, then create `config.yaml` with an empty `work_item_schemas:` key:
  ```yaml
  work_item_schemas:
  ```

**If the file exists:** Read and parse it. Proceed to Step 3.

---

## Step 3 — Route to Operation

### CREATE — Build a New Schema

Interactive Q&A flow that gathers schema requirements, generates YAML, merges into config, and optionally creates a companion lifecycle skill.

For detailed workflow, see `references/create-workflow.md` in this skill folder.

### VIEW — Display Existing Schemas

Read `.taskorchestrator/config.yaml` and display schemas in a summary table:

```
◆ Note Schemas — .taskorchestrator/config.yaml

| Schema Type | Lifecycle | Queue Notes | Work Notes | Review Notes | Total |
|---|---|---|---|---|---|
| feature-implementation | auto | 1 (1 req) | 2 (2 req) | 1 (1 req) | 4 |
| bug-fix | auto | 1 (1 req) | 2 (2 req) | 1 (1 req) | 4 |
```

If the user specified a schema name, show that schema's full detail: each note with key, role, required, description, guidance, and skill (if set). Also show the schema's lifecycle mode and default_traits (if any).

If the config has a `traits:` section, show a separate traits summary table:

```
◆ Traits

| Trait | Notes | Skills |
|---|---|---|
| needs-security-review | security-assessment (review, req) | security-review |
| needs-migration-review | migration-assessment (queue, req) | migration-review |
```

If the config has an `auditing:` section, display the auditing status including verifier type when present:

```
◆ Auditing: enabled, verifier: noop
```

Or with a JWKS verifier and its source:

```
◆ Auditing: enabled, verifier: jwks (uri: https://provider.example/.well-known/jwks.json)
```

Or `◆ Auditing: disabled` (or omit if the section is absent). When `verifier` is absent, default to `verifier: noop`.

### EDIT — Modify an Existing Schema

Read current config, display the target schema, ask what to change (add note, remove note, toggle required, change description/guidance/skill, change lifecycle mode, add/remove default_traits, rename key), apply changes, write back.

When adding or editing a note, offer the `skill` field: "Should this note have a skill framework? If so, provide the skill name (e.g., `review-quality`). The skill will be invoked before the agent fills the note."

If the target schema is not found in config.yaml, inform the user and offer to CREATE instead.

For detailed workflow, see `references/edit-workflow.md` in this skill folder.

### DELETE — Remove a Schema

Read current config, confirm the schema name, warn about orphaned notes on existing items, remove the key, write back.

If the target schema is not found in config.yaml, inform the user and offer to CREATE instead.

For detailed workflow, see `references/delete-workflow.md` in this skill folder.

### VALIDATE — Check Config Integrity

Run structural and semantic checks on the config file and report issues with fix suggestions.

For detailed workflow, see `references/validate-workflow.md` in this skill folder.

---

## Step 4 — Report

**For write operations (CREATE, EDIT, DELETE):**
- Show what changed in the config file
- Remind: **MCP reconnect required** (`/mcp`) for schema changes to take effect — the server caches schemas on first access

**For VIEW and VALIDATE:** The output from Step 3 is the deliverable — no additional report needed.

---

## Troubleshooting

**`expectedNotes` is empty after creating an item with the schema tag**
- Cause: MCP server hasn't loaded the updated config file
- Solution: Run `/mcp` in Claude Code to reconnect the server, then retry

**Schema not applied — item has no schema**
- Cause: The item's `type` field doesn't match any key in `work_item_schemas`, and its tags don't match any `note_schemas` key (legacy fallback)
- Resolution order: `type` field → direct lookup in `work_item_schemas`; if no type or no match, first tag match in `note_schemas`; if no match, falls back to `default` schema if one exists
- Solution: Verify the item's type and tags with `query_items(operation="get", id="<uuid>")`. Set `type` to a key that exists in `work_item_schemas` for reliable schema selection.

**Duplicate schema key in config file**
- Cause: YAML allows duplicate keys but only the last one is used
- Solution: Check for duplicate entries under `work_item_schemas:` (or `note_schemas:`) and merge them

**Changes not taking effect after editing config**
- Cause: The server caches schemas on first access — changes are not hot-reloaded
- Solution: Run `/mcp` to reconnect the MCP server subprocess

---

## Examples

**Example 1: View all schemas**

User says: "What schemas do I have?"
1. Read `.taskorchestrator/config.yaml`
2. Display summary table with note counts per phase
3. Offer to show detail for any specific schema

**Example 2: Add a required note to an existing schema**

User says: "Add a test-plan note to the bug-fix schema"
1. Read config, find `bug-fix` schema
2. Ask: which phase (queue/work/review), required?, description, guidance
3. Append the new note entry, write config back
4. Remind: `/mcp` reconnect needed

**Example 3: Validate config after manual editing**

User says: "I edited the config by hand — check it"
1. Read and parse config
2. Run validation checks (syntax, structure, field rules, duplicates)
3. Report issues or confirm "Config is valid — N schemas, M total notes"
