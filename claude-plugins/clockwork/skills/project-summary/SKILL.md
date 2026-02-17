---
name: project-summary
description: Display a formatted project dashboard with feature progress, action items, and housekeeping suggestions. Use when the user asks for a project summary, status overview, or "where are we?"
user_invocable: true
---

# Project Summary

Generate a Tier 1 dashboard for the current project.

## Inputs

- **projectId** (optional): UUID of the project. If omitted, read from the `## Project:` header in CLAUDE.md — it follows the pattern `` (`uuid`) ``.

## Steps

1. **Resolve project ID** — use provided ID, or extract from CLAUDE.md
2. **Query project overview:**
   - `query_container(operation="overview", containerType="project", id=projectId)`
   - The overview includes `features[]` with per-feature task counts, AND `tasks[]` containing standalone project-level tasks (no featureId)
3. **Format dashboard** using the template below

## Dashboard Template

```
## ◆ {project.name} — {project.status}

**{featureCount} features** · {taskCounts.total} tasks ({breakdown by status})

### Active Features

| ID | Feature | Status | Progress | Priority |
|----|---------|--------|----------|----------|
| `{id[0..8]}` | {name} | {status} | {done}/{total} | {priority} |
(only non-terminal features: planning, in-development, blocked, on-hold, etc.)

Status symbols: ◉ in-progress/in-development, ⊘ blocked/on-hold, ○ pending/planning

**Completed:** {count} features

### Action Items

| ID | Task | Priority | Status |
|----|------|----------|--------|
| `{id[0..8]}` | {title} | {priority} | {status} |
(filter tasks[] for tag "action-item" with non-terminal status, or "No open action items." if none)

### Housekeeping

(flag features where all tasks are complete but feature status is not terminal)
(flag features in-development with zero in-progress tasks)
(note any open bugs that are external/unactionable)
```

## Notes

- This is a **read-only** skill — it queries and formats, never modifies state
- Only 1 MCP call — do NOT delegate to a subagent (the overhead far exceeds the payload)
- **Short UUIDs** — display the first 8 characters of entity IDs as `` `abcd1234` `` inline code. Include in feature table, action items, and housekeeping references
- Keep the dashboard concise — no section content, no task-level details
- Completed features go in a summary line, NOT in the table — the table is for actionable work only
- For deeper feature inspection, use `query_container(operation="overview", containerType="feature", id=...)`
