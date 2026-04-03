---
name: create-item
description: Create an MCP work item from conversation context. Scans existing containers to anchor the item in the right place (Bugs, Features, Tech Debt, Observations, etc.), infers type and priority, creates single items or work trees, and pre-fills required notes. Use this whenever the conversation surfaces a bug, feature idea, tech debt item, or observation worth tracking persistently. Also use when user says "track this", "log this bug", "create a task for", or "add this to the backlog".
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

---

## Step 4 — Set type via schema discovery

Read `.taskorchestrator/config.yaml` to discover available schemas (this is a file read, not an MCP call). In Docker, the config is mounted at a path controlled by the `AGENT_CONFIG_DIR` env var — read `$AGENT_CONFIG_DIR/.taskorchestrator/config.yaml` if that variable is set, otherwise use `.taskorchestrator/config.yaml` relative to the working directory.

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

---

## Step 5 — Create the item(s)

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
- Check each `expectedNotes` entry for a `guidance` field — use it as the authoring instruction for note content. Guidance takes precedence over free-form inference.
- Batch all notes into a single call rather than one call per note:
  ```
  manage_notes(operation="upsert", notes=[
    {itemId: "<uuid>", key: "reproduction-steps", role: "queue", body: "..."},
    {itemId: "<uuid>", key: "root-cause",          role: "queue", body: "..."}
  ])
  ```
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

## Troubleshooting

**No containers found in overview**
- Cause: Fresh workspace with no existing structure
- Solution: The skill handles this automatically — it will offer to create a project root and category containers via `AskUserQuestion`

**Wrong container chosen for the item**
- Cause: Item type was ambiguous or the category mapping didn't match intent
- Solution: Move the item after creation with `manage_items(operation="update", items=[{id: "<uuid>", parentId: "<correct-container-uuid>"}])`

**Type not matching a schema — `expectedNotes` is empty**
- Cause: The `type` field doesn't match any key in `.taskorchestrator/config.yaml`, or the config hasn't been loaded
- Solution: Verify the type matches a `work_item_schemas:` (or `note_schemas:`) key exactly. If the config was recently changed, run `/mcp` to reconnect the server

**Expected notes not returned after item creation**
- Cause: MCP server caches schemas on first access — config changes require reconnect
- Solution: Run `/mcp` in Claude Code to reconnect the server, then retry the create operation
