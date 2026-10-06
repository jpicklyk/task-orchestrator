---
name: create-item
description: "Creates an MCP work item from conversation context. Scans existing containers to anchor the item in the right place (Bugs, Features, Tech Debt, Observations, etc.), infers type and priority, creates single items or work trees, and pre-fills required notes. Use when the conversation surfaces a bug, feature idea, tech debt item, or observation worth tracking persistently. Also use when user says: track this, log this bug, create a task for, or add this to the backlog."
argument-hint: "[optional: brief description of what to create]"
---

# create-item — Container-Anchored Work Item Creation

Create MCP work items intelligently from conversation context. This skill handles container anchoring, tag inference, structure decisions, and note pre-population so you don't have to.

---

## Step 1 — Infer intent from conversation

Determine from context (or from `$ARGUMENTS` if provided):
- **Title** — what is the work item?
- **Type** — bug / feature / tech debt / observation / action item / general task
- **Priority** — high / medium / low (default: medium)
- **Scope** — single item, or feature with 2+ clear distinct subtasks?

If title or type cannot be inferred with confidence, use `AskUserQuestion` with concrete options. Do not ask open-ended questions.

---

## Step 2 — Scan containers

Resolve the scope first from the SessionStart context. A `## Project Scope` section (Active project plus a `Config:` line) carries a **project rootId**: it scopes reads and anchors new root-level items. A `## Personal Scope` section (Personal root plus a `Config:` line) carries a **personal root**: it only anchors new items, and reads stay unscoped (no `ancestorId`/`anchorId` on read calls, which may show other projects' items). Without session context, read `.taskorchestrator/config.yaml`'s top-level `project.rootId` (a file read, not an MCP call), which yields a project rootId only.

**If a project rootId is known:**
```
query_items(operation="overview", anchorId="<rootId>", includeChildren=true)
```
Category containers (Bugs, Features, Tech Debt, etc.) are expected as direct children of the project root — anchor new items there. **Exception:** `agent-observation` items always stay at global depth 0, outside any project root, regardless of whether a rootId is known — they're process-global, not project-scoped.

**If a personal root is known:** the anchor-finding scan may run under it (`query_items(operation="overview", anchorId="<personal root>", includeChildren=true)`) — that finds an anchor, it does not read work. If no container there fits, create the item with `parentId=<personal root>`; never leave it at depth 0.

**If no rootId is known**, fall back to an unscoped scan and the classification below (this is also the exact behavior from before project scoping existed):
```
query_items(operation="overview", includeChildren=true)
```

Classify the existing structure:

| Pattern | Classification |
|---------|---------------|
| Depth-0 item with category-named children (Bugs, Features, etc.) | **Hierarchical** — project root exists |
| Category-named items at depth 0, no project root | **Flat** — use category containers directly |
| No items at all | **Empty** — offer to create project root |

---

## Step 3 — Container anchoring

### Category mapping

| Item type | Target category container | Signal keywords |
|-----------|--------------------------|-----------------|
| Bug / error / crash / unexpected behavior | Bugs | bug, error, crash, broken, failure, wrong, exception |
| Feature / enhancement / new capability | Features | feature, add, implement, new, support, capability, enhancement |
| Tech debt / refactor / cleanup / improvement | Tech Debt | refactor, cleanup, simplify, debt, improve, migrate, restructure |
| Observation / friction / optimization / missing capability | Observations | slow, performance, optimize, latency, friction, missing, gap, observe |
| Action item / follow-up / reminder / TODO | Action Items | todo, follow up, remind, action, track, check |
| General / unclear | Best-effort match — ask if uncertain | |

### Anchoring decision tree

```
Hierarchical structure:
  Matching category found under project root → use as parentId
  Category missing under project root → create it, then use as parentId

Flat structure:
  Matching category at depth 0 → use as parentId
  Category missing at depth 0 → create it, then use as parentId

Empty (no project root exists):
  → AskUserQuestion: "No project root container exists yet.
    Would you like to create one for this project?"
  → Yes: create project root → create category under it → create item
  → No: create category container at depth 0 → create item under it
```

**Exception (all branches):** `agent-observation` items never anchor under a project root or category container — they are standalone process-global items (each its own root, not a child of any container) that live at depth 0 alongside (not under) any project root, per Step 2.

---

## Step 4 — Set type via schema discovery

Read the file named by the session context's `Config:` line when there is one, otherwise `.taskorchestrator/config.yaml`, to discover available schemas (this is a file read, not an MCP call). In Docker, the config is mounted at a path controlled by the `AGENT_CONFIG_DIR` env var — read `$AGENT_CONFIG_DIR/.taskorchestrator/config.yaml` if that variable is set, otherwise use `.taskorchestrator/config.yaml` relative to the working directory.

Schemas are defined under `work_item_schemas:` (preferred) or `note_schemas:` (legacy). Each schema key is a **type identifier** that activates gate enforcement when set as the item's `type` field. Tags remain available for categorization but are no longer the primary schema selector.

**Error handling:** If the config file is not found, cannot be read, or contains invalid YAML, skip schema-based type assignment and create the item without a type. Inform the user: "No schema config found — item created without a type." Do not abort item creation due to a missing or malformed config.

**Infer the best schema match from context:**

| Context signal | Schema to apply |
|----------------|-----------------|
| Feature, enhancement, new capability | Match against feature-related schema keys in config (if any exist) |
| Bug, error, crash, unexpected behavior | Match against bug-related schema keys in config (if any exist) |
| Observation, friction, optimization, missing capability | Match against observation-related schema keys in config (if any exist) |

If the inferred schema key exists in the config, set it as the item's `type` value. If the key does not exist in the config (e.g., no `bug-fix` schema defined), leave `type` unset — do not assign a type that has no matching schema.

**When no confident match can be inferred:**
- If a schema named `default` exists in the config, use it as the fallback — this lets users control what happens to unclassified items
- Otherwise, ask the user which schema to apply via `AskUserQuestion`, listing the available schema keys from the config
- Include a "No schema" option for items that should be schema-free

**If no config file exists**, skip type assignment entirely — all items will be schema-free.

> **Tags vs. type:** Set `type` for schema selection. Use `tags` only for additional categorization/filtering that is independent of schema matching.

### Trait discovery

While reading the config, also check for a top-level `traits:` section. Each key under `traits:` is a trait name that can be assigned to items via the `traits` parameter. Traits add additional note requirements on top of the base schema.

**Assess whether any configured traits apply based on conversation context:**

| Context signal | Trait to consider |
|----------------|-------------------|
| Database migration, schema change, ALTER TABLE | `needs-migration-review` (if configured) |
| MCP tool parameter changes, response shape changes | `needs-api-compat-review` (if configured) |
| Plugin skill/hook behavior changes | `needs-plugin-update` (if configured) |
| Auth, input validation, external data handling | `needs-security-review` (if configured) |
| Hot path changes, per-request work, startup impact | `needs-perf-review` (if configured) |

Only assign traits that exist in the config. If no `traits:` section exists, skip trait assignment entirely.

If multiple traits apply, combine them: `traits: "needs-migration-review,needs-api-compat-review"`

**Resource-bearing traits (informational):** if a chosen trait's config entry carries its own `resources:` list, note this to the user — items with that trait will acquire an exclusive lease on the named resource key(s) when they enter work phase, and `advance_item(start)` can transiently fail with `resource_unavailable` if another item currently holds it. No extra action is needed here; this is just so the choice isn't a surprise later.

---

## Step 5 — Create the item(s)

**Dedup check first.** Before creating each item (a work tree's root only — its children inherit the check), run ONE unscoped search:
```
query_items(operation="search", query="<title key terms>", limit=5)
```
It stays unscoped even when a rootId is known: depth-0 process-global items such as agent-observations sit outside any project ancestor, so an `ancestorId`-scoped search misses them. Show close matches as one FYI line (role + short id each) and let the user decide — never auto-link, auto-skip or auto-cancel.

**Single item** (bug, observation, standalone task, action item):
```
manage_items(operation="create", items=[{
  title: "<inferred title>",
  summary: "<1-2 sentence description from context>",
  priority: "<inferred priority>",
  type: "<schema key or omit>",
  tags: "<categorization tags or omit>",
  parentId: "<category container UUID>"
  // Additional fields like `complexity` are optional — omit if not relevant
}])
```

**Work tree** (feature with 2+ distinct subtasks clearly described):
```
create_work_tree(
  root={title, summary, priority, type: "<schema key>"},
  children=[{ref: "t1", title: "..."}, {ref: "t2", title: "..."}, ...],
  parentId="<category container UUID>"
)
```

Default to single item when scope is unclear. Use `create_work_tree` only when the conversation explicitly names multiple distinct subtasks.

---

## Step 6 — Pre-fill required notes

Check `expectedNotes` in the create response. For each note where `required: true` and `role: "queue"`:
- Extract relevant content from the conversation
- Resolve guidance via `query_items(operation="schema", itemId="<uuid>")` (`expectedNotes` itself is keys-only) — use its `guidance` field as the authoring instruction, taking precedence over free-form inference.
- Batch all notes into a single call rather than one call per note:
  ```
  manage_notes(operation="upsert", notes=[
    {itemId: "<bug-uuid>",     key: "diagnosis",       role: "queue", body: "..."},
    {itemId: "<feature-uuid>", key: "feature-summary", role: "queue", body: "..."}
  ])
  ```
  (note keys come from each item's schema `expectedNotes` — `diagnosis` for `bug-fix`, `feature-summary` for `feature-implementation`; a single call may batch notes across multiple items)
- If conversation content is too sparse for a meaningful note body, leave it — do not fabricate content

---

## Step 7 — Report

```
✓ Created: [title] (`short-id`)
  Path: [container path, e.g. "Features" or "Project Root › Features"]
  Tags: [tags, or "none"]
  Notes pre-filled: [key names, or "none"]
```

If a new category container was created, add one line:
```
  ↳ Created new container: [category name] under [parent]
```

---

## From a workflow findings proposal

An alternate entry path for materializing findings from a `task-orchestrator:audit` workflow run. The audit script (`workflows/audit.js`) never writes MCP items itself — it returns an `audit/result-v1` result carrying a `proposal` (candidate items to create) and a `kept[]` list (surviving findings after triage). Use this path instead of Steps 1–3 when the input is a proposal rather than free-form conversation.

**Input:** the `proposal` and `kept[]` from the audit result. Each `kept[]` finding carries `findingId`, `reportId`, `title`, `severity`, `verdict`, `category`, `locations`, `trackedId`. Each proposal row (`proposal.items[]`) carries `findingId`, `class`, `title`, `summary`, `description`, `priority`, `tags`, an optional `type`, `candidates` (`[{id, short, role, title}]`), `likelyDuplicate` (`none|weak|strong|unknown`), `materialize` (+ `reason`). Create rows with those fields; `create_work_tree` children accept them.

**1. Present one table to the user** — columns: id, severity, class, type, materialize + reason, candidates.

**2. Dedup.** Row `candidates` come from the unscoped Step 5 search the audit's own Triage phase already ran per finding — cite them, don't restate the search. Re-run Step 5's search only for rows where `likelyDuplicate: unknown`, or for rows the user added or retitled during review. Candidates are FYI only: never auto-link, auto-skip, or auto-cancel a row on their account (same rule as Step 5's dedup check).

**3. User edits.** The user may flip `materialize`, reclassify a row's class (bug / tech-debt / agent-observation), or drop a row entirely. Nothing here is applied without these edits being explicit.

**4. Resolve the container.** Resolve ONE category container for the whole proposal, using Step 2's overview scan and Step 3's decision tree — cite them, don't restate the classification table or anchoring logic — and have the user confirm it. All materialized non-observation rows go under the single audit container, which is created under that category container (never a container per class).

**5. Priority and type.** Priority is already mapped in the proposal (`critical`/`high` → `high`, `medium` → `medium`, `low` → `low`) — do not re-derive it. Use a row's own `type` when it has one; run Step 4's schema discovery only for rows where `type` is absent, and leave `type` unset when no schema key matches, per Step 4's existing rule.

**6. Materialize — only after explicit user confirmation.** One `create_work_tree` call for the audit container plus its materialized children under the resolved category container (≤25 children per call; a proposal with more attaches later batches via `root.id`). `agent-observation` rows are never part of this tree — create them in a separate `manage_items(create)` batch at depth 0, tagged `agent-observation`, per Step 3's observation exception.

**7. No notes pre-filled** at materialization time — Step 6's existing rule (sparse content is left, not fabricated) still applies; queue notes are filled later during normal planning, not from the proposal.

**8. Report** using Step 7's format, one line per created item.

**Never automated:** creating anything without explicit user confirmation, acting on dedup candidates (auto-link/skip/cancel), materializing a `materialize: false` row the user did not flip, or placing an `agent-observation` row under any root.

---

## Troubleshooting

**No containers found in overview**
- Cause: Fresh workspace with no existing structure
- Solution: The skill handles this automatically — it will offer to create a project root and category containers via `AskUserQuestion`

**Wrong container chosen for the item**
- Cause: Item type was ambiguous or the category mapping didn't match intent
- Solution: Move the item after creation with `manage_items(operation="update", items=[{itemId: "<uuid>", parentId: "<correct-container-uuid>"}])`

**Type not matching a schema — `expectedNotes` is empty**
- Cause: The `type` field doesn't match any key in `.taskorchestrator/config.yaml`, or the config hasn't been loaded
- Solution: Verify the type matches a `work_item_schemas:` (or `note_schemas:`) key exactly. If the config was recently changed, run `/mcp` to reconnect the server

**Expected notes not returned after item creation**
- Cause: MCP server caches schemas on first access — config changes require reconnect
- Solution: Run `/mcp` in Claude Code to reconnect the server, then retry the create operation
