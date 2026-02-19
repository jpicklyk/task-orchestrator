---
name: create-item
description: Create an MCP work item from conversation context. Scans existing containers to anchor the item in the right place (Bugs, Features, Tech Debt, Observations, etc.), infers type and priority, creates single items or work trees, and pre-fills required notes. Use this whenever the conversation surfaces a bug, feature idea, tech debt item, or observation worth tracking persistently.
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

If title or type cannot be inferred with confidence, use `AskUserQuestion` with 2–3 concrete options. Do not ask open-ended questions.

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

| Item type | Target category container |
|-----------|--------------------------|
| Bug / error / crash / unexpected behavior | Bugs |
| Feature / enhancement / new capability | Features |
| Tech debt / refactor / cleanup / improvement | Tech Debt |
| Observation / friction / optimization / missing capability | Observations |
| Action item / follow-up / reminder / TODO | Action Items |
| General / unclear | Best-effort match — ask if uncertain |

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

## Step 4 — Apply tags

| Item type | Tags to apply |
|-----------|--------------|
| Feature / new capability | `feature-implementation` |
| Observation: friction / confusing API | `agent-observation,friction` |
| Observation: token waste / redundant call | `agent-observation,optimization` |
| Observation: tool defect | `agent-observation,bug` |
| Observation: gap in tool surface | `agent-observation,missing-capability` |
| Bug, tech debt, action item, general task | *(no tags)* |

---

## Step 5 — Create the item(s)

**Single item** (bug, observation, standalone task, action item):
```
manage_items(operation="create", items=[{
  title: "<inferred title>",
  summary: "<1-2 sentence description from context>",
  priority: "<inferred priority>",
  tags: "<tags or omit>",
  parentId: "<category container UUID>"
}])
```

**Work tree** (feature with 2+ distinct subtasks clearly described):
```
create_work_tree(
  root={title, summary, priority, tags},
  children=[{ref: "t1", title: "..."}, {ref: "t2", title: "..."}, ...],
  parentId="<category container UUID>"
)
```

Default to single item when scope is unclear. Use `create_work_tree` only when the conversation explicitly names multiple distinct subtasks.

---

## Step 6 — Pre-fill required notes

Check `expectedNotes` in the create response. For each note where `required: true` and `role: "queue"`:
- Extract relevant content from the conversation
- Upsert: `manage_notes(operation="upsert", notes=[{itemId, key, role, body}])`
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
