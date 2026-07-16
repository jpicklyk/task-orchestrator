---
name: manage-schemas
description: "Creates, views, edits, deletes, and validates note schemas for the MCP Task Orchestrator in .taskorchestrator/config.yaml — the templates that define which notes agents must fill at each workflow phase. Also manages the actor_authentication config block: set actor authentication policy, configure degraded mode, show actor_authentication config, set degradedModePolicy to reject, what's the current degraded mode policy. Use when user says: create schema, show schemas, edit schema, delete schema, validate config, what schemas exist, add a note to schema, remove note from schema, or configure gates."
argument-hint: "[optional: action + schema name or 'actor_authentication', e.g. 'view bug-fix', 'create research-spike', 'validate', 'set degradedModePolicy reject']"
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

> **Note:** `project:` is a recognized top-level key alongside `work_item_schemas`, `traits`, and `actor_authentication` — it anchors this repo to a project root item and is read only by other skills (`quick-start`, `/adopt-project-scope`), never by this skill. Do not treat it as unknown, and never drop or rewrite it — every write operation in Step 3 (CREATE/EDIT/DELETE) must carry it through unchanged. See `references/config-format.md` → Project Scoping for its fields.

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

If the config has an `actor_authentication:` section, display the actor authentication status including verifier type when present:

```
◆ Actor authentication: enabled, verifier: noop
```

Or with a JWKS verifier and its source:

```
◆ Actor authentication: enabled, verifier: jwks (uri: https://provider.example/.well-known/jwks.json)
```

Or with DID-trust mode:

```
◆ Actor authentication: enabled, verifier: jwks (DID trust: did:web:agent.example.com, did:web:lair.dev)
```

Or `◆ Actor authentication: disabled` (or omit if the section is absent). When `verifier` is absent, default to `verifier: noop`. When `did_allowlist` or `did_pattern` is set, show the DID trust variant.

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
- **Sync to server (if project-scoped):** Check whether the config has a top-level `project.rootId` (see Step 2's note and `references/config-format.md` → Project Scoping). If present, check the tool list for `manage_project_config` — older servers may not expose it, in which case note this to the user and skip (the config.yaml write above is authoritative locally; the server picks it up on its normal read path once the tool becomes available). If the tool is available, call:
  ```
  manage_project_config(operation="push", rootItemId="<project.rootId>", configYaml="<full current file text>")
  ```
  - Success → report the returned `fingerprint`. Re-pushing identical content later returns the same fingerprint (idempotent) — safe to call after every write without pre-checking.
  - `VALIDATION_ERROR` → the server rejected the YAML (e.g. a construct its parser can't accept). The local file is already saved — tell the user the parse error from the response and to fix `.taskorchestrator/config.yaml`, then re-run VALIDATE and retry the push.
  - `CONFLICT_ERROR` (superseded) → the pushed content is provably older than the server's (its fingerprint is in the server's history but not current). Tell the user to pull/copy the server's config back before editing (`manage_project_config(operation="get", ...)` shows it), or pass `force: true` on the push if overwriting the server's newer config is intentional.
  - A `warning` field on a successful response → relay it to the user as-is (non-fatal — e.g. the root item's `type` isn't `"project"`).
- Remind: **MCP reconnect required** (`/mcp`) for schema changes to take effect on the *global* config path — the server caches `.taskorchestrator/config.yaml` on first access. A successful per-root push above takes effect immediately for items scoped to that root, without needing reconnect.

**For VIEW and VALIDATE:** The output from Step 3 is the deliverable — no additional report needed.

---

## Troubleshooting

**`expectedNotes` is empty after creating an item with the schema tag**
- Cause: MCP server hasn't loaded the updated config file
- Solution: Run `/mcp` in Claude Code to reconnect the server, then retry

**Schema not applied — item has no schema**
- Cause: The item's `type` field doesn't match any key in `work_item_schemas`, and its tags don't match any `note_schemas` key (legacy fallback)
- Resolution order: `type` field → direct lookup in `work_item_schemas`; if no type or no match, first tag match in `note_schemas`; if no match, falls back to `default` schema if one exists
- Solution: Verify the item's type and tags with `query_items(operation="get", itemId="<uuid>")`. Set `type` to a key that exists in `work_item_schemas` for reliable schema selection.

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

---

## Actor authentication config

The `.taskorchestrator/config.yaml` file also has an `actor_authentication:` block:

```yaml
actor_authentication:
  degraded_mode_policy: accept-cached  # accept-cached | accept-self-reported | reject
  # ... other actor_authentication settings (enabled, verifier)
```

When the user wants to view, set, or change `degradedModePolicy`:
1. Read `.taskorchestrator/config.yaml`
2. Locate the `actor_authentication:` block (create it if absent)
3. For **view**: display the current value (default `accept-cached` if key is absent)
4. For **changes**: update the `degraded_mode_policy:` field (preserving all other actor_authentication keys)
5. Validate the new value is one of: `accept-cached`, `accept-self-reported`, `reject`
6. Write back

**Note:** If the `DEGRADED_MODE_POLICY` environment variable is set on the server, it overrides the YAML value. To check whether an override is in effect:
```bash
echo $DEGRADED_MODE_POLICY
```
Tell the user when the env-var override is active — the YAML change will be shadowed until the env var is unset or the server is restarted without it.

**Recommended:** Use `reject` for cross-org or multi-tenant fleet deployments where agents from different organizations share a single Task Orchestrator instance. See `current/docs/fleet-deployment.md` for the full security rationale.

### `degradedModePolicy` values

| Value | Behavior | When to use |
|---|---|---|
| `accept-cached` | *(default)* Trust JWKS-verified identity from stale cache on `UNAVAILABLE`; self-reported otherwise | Single-org; occasional JWKS outages |
| `accept-self-reported` | Always trust the caller-supplied `actor.id` | Local dev; no JWKS; explicit opt-out |
| `reject` | Reject any unverified operation (`rejected_by_policy`) | Cross-org `did:web` fleets; maximum assurance |
