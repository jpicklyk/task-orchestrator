---
name: manage-schemas
description: "Creates, views, edits, deletes, and validates note schemas for the MCP Task Orchestrator in .taskorchestrator/config.yaml — the templates that define which notes agents must fill at each workflow phase. Also recommends schema designs from a library of workflow patterns (autonomous coding loops, spec-driven teams, research pipelines, content production, support triage, data pipelines, incident response, document review) — use this skill whenever the user describes a workflow they want tracked or gated, even without the word 'schema': what schema should I use, help me design schemas, recommend gates for X, set up schemas for my team/workflow/pipeline. Use when user says: create schema, show schemas, edit schema, delete schema, validate config, what schemas exist, add a note to schema, remove note from schema, configure gates, roll back schema, undo schema change, run budget, decision class, human-only approval, or stall."
argument-hint: "[optional: action + schema name or workflow description, e.g. 'view bug-fix', 'create research-spike', 'recommend schemas for support triage', 'validate']"
---

# Manage Schemas — Note Schema Lifecycle

Create, view, edit, delete, and validate note schemas in `.taskorchestrator/config.yaml`. Schemas define which notes agents must fill at each workflow phase before advancing items.

Every tool call below is a `to-call` block, `{tool, select|op, args}`, in the shapes of [call-shapes.md](../_generated/call-shapes.md). The `config` tool is served by the orchestration profile only.

## Director mode

Director mode is the session state entered by `/task-orchestrator:director` (or by running as the hardened `director` agent). Nothing in this skill runs inline there: the config file cannot be read or written (Read and Write are denied) and the `config` tool is orchestration-only. Dispatch ONE background `Agent` (`run_in_background: true`, `model` set) that runs the steps below with the user's request. It reports one line: the pushed version, any warnings and `affectedInFlight.count`, never YAML or note bodies. A background agent dispatched by a director is not in director mode: it runs the steps below.

---

## Step 1 — Determine Intent

Classify from `$ARGUMENTS` and conversation context before making any tool calls.

| Signal words | Action |
|---|---|
| "create", "build", "new", "add schema", "define", "set up" | CREATE |
| "recommend", "advise", "design", "what schema should I use", "help me choose", a described workflow with no schema name | CREATE (advisor path) |
| "show", "view", "list", "what schemas", "display" | VIEW |
| "edit", "modify", "change", "update", "add note to", "remove note from" | EDIT |
| "delete", "remove schema", "drop" | DELETE |
| "undo", "roll back", "restore", "previous version" | ROLLBACK |
| "validate", "check", "verify", "lint" | VALIDATE |
| "run budget", "stall", "stall_after", "attempts per seat", "run policy" | RUN POLICY |
| "decision class", "human-only", "who can answer", "approval gate", "decision expires" | DECISION CLASSES |

If `$ARGUMENTS` contains both an action and a schema name (e.g., "view bug-fix"), extract both. If intent cannot be determined, ask via `AskUserQuestion` with options: Create, View / Validate, Edit, Delete.

> Validate is grouped with View — both leave the config file unchanged.

---

## Step 2 — Config Bootstrap

The target file is the path on the SessionStart context's `Config:` line (project or personal scope) when there is one, otherwise `.taskorchestrator/config.yaml`. In personal scope that is the user-level file, so an edit applies to every directory that has no project config of its own. Check if the target file exists by reading it.

**If the file does not exist:**
- For **VIEW** or **VALIDATE**: report "No schemas configured — `.taskorchestrator/config.yaml` does not exist." and stop.
- For **CREATE**, create the file with the new schema (create-workflow Step 4). For **EDIT** or **DELETE**, report "No schemas configured" and stop.

**If the file exists:** Read and parse it. Proceed to Step 3.

> **Note:** a config document has these top-level keys: `work_item_schemas`, `traits`, `resources` (the server file only), `note_limits`, `status_labels`, `independence`, `runs`, `decision_classes`, `project` and `extensions`. Each has one owner; see [config-reference.md](../../../../current/docs/config-reference.md#key-ownership) "Key ownership". `project:` anchors this repo to a project root item and is written by `/task-orchestrator:init` and `/adopt-project-scope`, never by this skill; `extensions:` holds the plugin's own settings under `extensions.claude-code` (see `references/config-format.md` → "Plugin settings"). Never drop or rewrite either: every write operation in Step 3 (CREATE/EDIT/DELETE) must carry `project` and `extensions` through verbatim, byte for byte. See `references/config-format.md` → Project Scoping for the `project` fields, and → "Resources (Trait Dimension)" for `resources:`.
>
> **Never write** `actor_authentication`, `auditing`, `schema_resolution`, or a top-level `orchestration`, `retrospective` or `actor_attribution`. They are gone from the 4.0 config: a leftover `actor_authentication` key fails server startup, a push rejects the others, and `actor_attribution` has no reader left. VALIDATE and every write flag any of them found in the file, with the fix: delete `actor_authentication`, `auditing` and `schema_resolution`; move `orchestration` and `retrospective` under `extensions.claude-code`; delete `actor_attribution`. Caller identity is not configured here: it comes from the server's `API_AUTH_MODE` (see `/task-orchestrator:configure-server`).

---

## Step 3 — Route to Operation

### CREATE — Build a New Schema

Three entry paths: a pattern-driven **advisor** (the user describes their workflow; classify it
against `references/workflow-patterns.md` and recommend a configuration with per-gate
rationale), starter **templates**, and from-scratch **Q&A**. All three converge on the same
customize → write → smoke-test machinery. When the user described a workflow rather than naming
a schema, go straight to the advisor path.

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

When the file has a `project.rootId`, also show what the server resolves for that root. The file lists only the root's own types; the server layers them over its own config, so types the file lacks (server-layer, process-global types) exist for the root too:

```to-call
{"tool": "config", "op": "effective", "args": {"rootId": "<project.rootId>"}}
```

Add every `schemas[]` entry whose `configSource` is `global` and whose `type` the file does not declare to the table, marked `(global)`. Then show one sync line, from the head version and a validate of the local file (file text with CRLF replaced by LF):

```to-call
{"tool": "config", "op": "get", "args": {"rootId": "<project.rootId>"}}
```

```to-call
{"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, LF>"}}
```

An empty `diff` (no added, removed or changed type, trait or setting) reads `in sync with head v<version>`. Otherwise name the changed keys and say that config-sync (on the next save or session start) or the Step 4 push puts the file on the server. A `not_found` from `get` means the root has no stored config yet.

If the user specified a schema name, show that schema's full detail: each note with key, role, required, description, guidance, and skill (if set). Also show the schema's lifecycle mode and default_traits (if any).

If the config has a `traits:` section, show a separate traits summary table:

```
◆ Traits

| Trait | Notes | Skills | Dispatch |
|---|---|---|---|
| needs-security-review | security-assessment (review, req) | security-review | — |
| needs-migration-review | migration-assessment (queue, req) | migration-review | — |
| delegated | delegation-metadata (work, opt) | — | work: implementer, review: reviewer (effort high) |
```

The `Dispatch` column summarizes the trait's `dispatch:` map (if any) as `phase: agent` pairs,
each with `(effort <level>)` appended when that phase's profile sets one (e.g. `review: reviewer
(effort high)`); a `model`-only or `effort`-only phase profile (no `agent`) shows as
`phase: (model <name>)` / `phase: (effort <level>)`; `—` when the trait declares no `dispatch:`.
See `references/config-format.md` → "Dispatch (Trait Dimension)".

### EDIT — Modify an Existing Schema

Read current config, display the target schema, ask what to change (add note, remove note, toggle required, change description/guidance/skill, change lifecycle mode, add/remove default_traits, rename key), apply changes, write back.

When adding or editing a note, offer the `skill` field: "Should this note have a skill framework? If so, provide the skill name (e.g., `security-review`, or a skill that exists in this workspace). The skill will be invoked before the agent fills the note."

If the target schema is not found in config.yaml, inform the user and offer to CREATE instead.

For detailed workflow, see `references/edit-workflow.md` in this skill folder.

### DELETE — Remove a Schema

Read current config, confirm the schema name, warn about orphaned notes on existing items, remove the key, write back.

If the target schema is not found in config.yaml, inform the user and offer to CREATE instead.

For detailed workflow, see `references/delete-workflow.md` in this skill folder.

### RUN POLICY — the `runs:` block

View: show `runs:` from the file (or the merged view when a `rootId` exists). Edit: write the keys (`stall_after`, `budgets.tokens`, `budgets.cost_usd`, `budgets.attempts_per_seat`, `budgets.max_active_seats`), then run the Step 4 validate and push. Budgets are ceilings and opt-in; keys and ranges are in `references/config-format.md` -> "Run Budgets (`runs:`)".

### DECISION CLASSES — the `decision_classes:` block

A decision can only be raised under a declared class: a raise with an undeclared class fails `invalid_request /class`. View: when a `rootId` exists, read the server-merged classes with `config` `effective` (built-ins plus server plus root); otherwise read the file. Edit: write the class (`answerers`, `severity`, `expires_after`, `on_expiry`, `freeform`), then the existing Step 4 validate and push. Keys, built-ins and the merge rule are in `references/config-format.md` -> "Decision Classes".

### VALIDATE — Check Config Integrity

Run structural and semantic checks on the config file and report issues with fix suggestions. When the file has a `project.rootId`, also run the server's own check (`config` `validate`, Step 4): the server is the authority on what a push accepts, and the local checks are for files with no root.
In addition to the checks in `references/validate-workflow.md`, flag any `skill:` value that is
exactly one of `review`, `plan`, `run`, or `init` as a probable built-in-skill collision (warning,
not an error) — these bare names resolve to a Claude Code built-in (e.g. `review` is the
GitHub-PR review skill) rather than a project's intended framework skill. Suggest the likely
intended framework skill name (a specific name such as `code-review-checklist` instead of the bare `review`) and cite the exact-name
rule in `references/config-format.md` → "`skill` — exact-name rule".

For detailed workflow, see `references/validate-workflow.md` in this skill folder.

### ROLLBACK - Restore an Earlier Config Version

Needs a `project.rootId` (rollback is a server operation on the root's stored versions). The server keeps every pushed version; a rollback appends a copy of the chosen version as the new head, so nothing is lost.

1. List the versions, newest first, and show `version`, `source`, `createdAt` for each. Ask which one with `AskUserQuestion`.

   ```to-call
   {"tool": "config", "op": "history", "args": {"rootId": "<project.rootId>", "limit": 10}}
   ```

2. Read the chosen version, then validate its `configYaml` to see what the swap would change against the head (`diff`) and how many in-flight items it affects (`affectedInFlight.count`):

   ```to-call
   {"tool": "config", "op": "get", "args": {"rootId": "<project.rootId>", "version": 3}}
   ```

   ```to-call
   {"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<configYaml of that version>"}}
   ```

3. Show the diff and the count; confirm with the user before changing anything.
4. Roll back on the server FIRST, with `expectedVersion` set to the `headVersion` from step 1 (`version_conflict` means the head moved: read the history again and ask again):

   ```to-call
   {"tool": "config", "op": "rollback", "args": {"rootId": "<project.rootId>", "version": 3, "expectedVersion": 5}}
   ```

5. THEN write the local config file from that version's `configYaml` (LF line endings). Order matters: config-sync compares the file with the server head, so a file written before the rollback can be pushed by config-sync as new content (or skipped as superseded) while the server head is still the old one; written after the rollback, the next config-sync finds the file current and does nothing.
6. As in Step 4 item 4, offer `repin` for the in-flight items the rollback affects.

---

## Step 4 — Report

**For write operations (CREATE, EDIT, DELETE):**
- Show what changed in the config file
- **Sync to server (if the file has `project.rootId` — a project root or the personal root):** The local write is authoritative; the push puts it on the server for that root. Use the file text with CRLF replaced by LF as `configYaml` (at most 131072 bytes).

  1. Validate first. Nothing is written; a problem comes back as `valid: false`, not as a failure.

     ```to-call
     {"tool": "config", "op": "validate", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, LF>"}}
     ```

     Show each `errors[{path, reason}]` and stop on any (the local file is already saved; fix it and re-run VALIDATE). Relay `warnings`. Show the diff against the root's head (`types`, `traits`, `settings`, each with `added`, `removed` and `changed` names) and `affectedInFlight` (`{count, sample}`: in-flight items whose resolved schema would change on a repin; `sample` holds up to 20 `{itemId, changedNotes, schema?, lifecycle?}` entries, and `affectedInFlight` is present only for a valid document).
  2. Head version. A root with no config fails with `data.results[0].error.code` `not_found`, which means "no head".

     ```to-call
     {"tool": "config", "op": "get", "args": {"rootId": "<project.rootId>"}}
     ```

  3. Push, with `expectedVersion` set to the head's `version` when a head exists (omit it otherwise).

     ```to-call
     {"tool": "config", "op": "push", "args": {"rootId": "<project.rootId>", "configYaml": "<file text, LF>", "expectedVersion": 1}}
     ```

     - Success → report the new `version`; relay `warnings`. Re-pushing identical content appends nothing (`appended: false`), so a push after every write is safe.
     - A failure answers call-level `partial_failure`; read the code, `detail` and `fix` from `data.results[0].error`.
     - `config_invalid` → the server rejected the document; show `detail.errors[{path, reason}]`. Nothing was stored.
     - `version_conflict` → the head moved since step 2, or the document matches an earlier version (`force` re-pushes it). Read it again, show what differs, and ask before pushing; `force` is only for an intentional overwrite (it never skips validation or `expectedVersion`).
     - `forbidden` → the credential lacks `write-config`.
  4. When `affectedInFlight.count` is above 0, tell the user those items still resolve their pinned schema and offer (ask first) to move them to the new head with `items_write` `repin`, one per `sample[i].itemId`. Only 20 ids are listed; for a larger count, list the root's in-flight items with `items_read` `list`.

     ```to-call
     {"tool": "items_write", "op": "repin", "args": {"id": "<item-uuid>"}}
     ```
- Without `project.rootId` there is nothing to push: only the local file is edited. A server file (the one `AGENT_CONFIG_DIR` points at) is imported once at boot, so a change to it needs a server restart (for stdio, reconnecting with `/mcp` starts a new process). A successful per-root push takes effect immediately for new and unpinned items of that root, with no reconnect.

**For VIEW and VALIDATE:** The output from Step 3 is the deliverable — no additional report needed.

---

## Troubleshooting

**`expectedNotes` unchanged after a push**
- Cause: the item is pinned. An item resolves the schema versions it was created under, so a later push never changes it ([config-reference.md](../../../../current/docs/config-reference.md#schema-pins), "Schema pins"). `items_read` `get` shows `schemaVersion` and `stale: true` when the pin is behind.
- Solution: `items_write` `repin` for that item (Step 4, item 4), or create the item again.

**Schema not applied — item has no schema**
- Cause: an existing item whose `type` no layer declares any more (a 3.x carry-over or a deleted schema) when there is no `default`, or an item created at depth 0 or outside the project root, which sees only the server config. Tags are never consulted. A new item cannot be created with an undeclared type (`schema_violation`).
- Resolution order (fixed): the root's exact type, the server's exact type, the root's `default`, the server's `default`. See `references/config-format.md` → Matching Rules.
- Solution: Verify the item's type with `items_read` `get`, then `items_write` `update` `{id, type}` to a declared type, or `{id, parentId}` under the root.

```to-call
{"tool": "items_read", "select": "get", "args": {"itemId": "<uuid>"}}
```

**Duplicate schema key in config file**
- Cause: YAML allows duplicate keys but only the last one is used
- Solution: Check for duplicate entries under `work_item_schemas:` and merge them

**Changes to a server file not taking effect**
- Cause: the server imports its file once, at boot
- Solution: restart the server (stdio: run `/mcp` to reconnect, which starts a new process)

---

## Examples

**Example 1: View all schemas**

User says: "What schemas do I have?"
1. Read `.taskorchestrator/config.yaml`
2. Display summary table with note counts per phase
3. Offer to show detail for any specific schema

**Example 2: Add a required note to an existing schema**

User says: "Add a rollout-plan note to the bug-fix schema"
1. Read config, find `bug-fix` schema
2. Ask: which phase (queue/work/review), required?, description, guidance
3. Append the new note entry, write config back
4. Validate, then push (Step 4); offer `repin` for in-flight items it affects

**Example 3: Validate config after manual editing**

User says: "I edited the config by hand — check it"
1. Read and parse config
2. Run validation checks (syntax, structure, field rules, duplicates), and `config` `validate` when a root exists
3. Report issues or confirm "Config is valid — N schemas, M total notes"
