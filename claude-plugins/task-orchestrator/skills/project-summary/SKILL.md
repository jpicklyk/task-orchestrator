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
2. **Query in parallel:**
   - `query_container(operation="overview", containerType="project", id=projectId)`
   - `query_container(operation="search", containerType="task", projectId=projectId, tags="action-item", status="!completed,!cancelled")`
3. **Format dashboard** using the template below

## Dashboard Template

```
## ◆ {project.name} — {project.status}

**{featureCount} features** · {taskCounts.total} tasks ({breakdown by status})

### Active Features

| Feature | Status | Progress | Priority |
|---------|--------|----------|----------|
(only non-terminal features: planning, in-development, blocked, on-hold, etc.)

Status symbols: ◉ in-progress/in-development, ⊘ blocked/on-hold, ○ pending/planning

**Completed:** {count} features

### Action Items

(table of standalone action-item tasks, or "No open action items." if none)

### Housekeeping

(flag features where all tasks are complete but feature status is not terminal)
(flag features in-development with zero in-progress tasks)
(note any open bugs that are external/unactionable)
```

## Notes

- This is a **read-only** skill — it queries and formats, never modifies state
- Only 2 MCP calls — do NOT delegate to a subagent (the overhead far exceeds the payload)
- Keep the dashboard concise — no section content, no task-level details
- Completed features go in a summary line, NOT in the table — the table is for actionable work only
- For deeper feature inspection, use `query_container(operation="overview", containerType="feature", id=...)`
