---
name: task-orchestration
description: Manage tasks using MCP Task Orchestrator tools. Use when creating, updating, completing, or tracking tasks through the task lifecycle.
---

# Task Orchestration

Patterns for managing tasks through the MCP Task Orchestrator. Tasks are the primary work items, organized under Features (optional) and Projects (optional).

## Core Workflow

### 1. Create Tasks with Templates

Discover templates first, then create with them:

```
query_templates(targetEntityType="TASK", isEnabled=true)
```

```
manage_container(
  operation="create",
  containerType="task",
  title="Implement user authentication",
  summary="Add JWT-based auth with refresh tokens",
  priority="high",
  complexity=7,
  tags="feature,backend,auth",
  featureId="<feature-uuid>",
  templateIds=["<template-uuid>"]
)
```

Templates auto-create documentation sections (e.g., technical approach, testing strategy). Always check for relevant templates before creating.

### 2. Progress Through Status

Use named triggers instead of raw status values. See `status-progression` skill for flows, triggers, and emergency transitions.

```
request_transition(containerId="<task-uuid>", containerType="task", trigger="start")
```

### 3. Complete Tasks

Before marking complete:
1. Check section templates are filled in: `query_sections(entityType="TASK", entityId="<uuid>")`
2. Check dependencies are resolved: `query_dependencies(taskId="<uuid>")`
3. Transition: `request_transition(containerId="<uuid>", containerType="task", trigger="complete")`
4. If a CC mirror task exists, update it after the MCP transition. See `task-mirroring` skill.

## Partial Updates (Critical Efficiency Pattern)

**Never fetch an entity just to update it.** Only send fields that changed:

```
manage_container(operation="update", containerType="task", id="<uuid>", priority="high")
```

```
manage_container(operation="setStatus", containerType="task", id="<uuid>", status="in-progress")
```

Token savings: 90-95% vs sending the full entity.

## Section Management

Sections hold detailed content (technical approach, test plan, notes). Created automatically by templates.

**Read sections:**
```
query_sections(entityType="TASK", entityId="<uuid>")
```

**Update section text efficiently (surgical edit):**
```
manage_sections(operation="updateText", id="<section-uuid>", oldText="placeholder", newText="actual content")
```

**Add a new section:**
```
manage_sections(
  operation="add",
  entityType="TASK",
  entityId="<uuid>",
  title="Implementation Notes",
  usageDescription="Developer notes and decisions",
  content="## Notes\n\nInitial setup complete.",
  ordinal=3,
  tags="notes"
)
```

**Bulk create sections:**
```
manage_sections(
  operation="bulkCreate",
  entityType="TASK",
  entityId="<uuid>",
  sections=[
    {"title": "Design", "usageDescription": "Design decisions", "content": "...", "ordinal": 0},
    {"title": "Testing", "usageDescription": "Test approach", "content": "...", "ordinal": 1}
  ]
)
```

## Finding Work

Get the next recommended task (respects dependencies and priority):

```
get_next_task(featureId="<feature-uuid>", includeDetails=true)
```

Or search for specific tasks:

```
query_container(operation="search", containerType="task", status="in-progress", tags="backend")
```

**Multi-value status filter:** `status="pending,in-progress"` (matches either)
**Negation:** `status="!completed,!cancelled"` (excludes these)

## Export for External Use

```
query_container(operation="export", containerType="task", id="<uuid>")
```

Returns full markdown representation of the task with all sections.
