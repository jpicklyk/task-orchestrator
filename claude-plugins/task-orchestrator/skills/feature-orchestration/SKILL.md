---
name: feature-orchestration
description: Coordinate feature lifecycle, task breakdown, and completion workflows using MCP Task Orchestrator. Use when managing features, breaking features into tasks, or tracking feature progress.
---

# Feature Orchestration

Patterns for managing features through the MCP Task Orchestrator. Features group related tasks under a project and track progress through a defined workflow.

## Feature Lifecycle

### 1. Create a Feature

Discover templates first:

```
query_templates(targetEntityType="FEATURE", isEnabled=true)
```

```
manage_container(
  operation="create",
  containerType="feature",
  title="User Authentication System",
  summary="JWT-based authentication with refresh tokens, role-based access, and session management",
  priority="high",
  projectId="<project-uuid>",
  tags="backend,security",
  templateIds=["<template-uuid>"]
)
```

### 2. Break Down into Tasks

Create tasks under the feature:

```
manage_container(
  operation="create",
  containerType="task",
  title="Design auth token schema",
  summary="Define JWT claims, refresh token rotation, and storage strategy",
  featureId="<feature-uuid>",
  priority="high",
  complexity=5,
  tags="backend,design",
  templateIds=["<template-uuid>"]
)
```

Repeat for each task. Set dependencies between tasks as needed (see dependency-analysis skill).

### 3. Progress the Feature

Feature status flow: `draft` -> `planning` -> `in-development` -> `testing` -> `validating` -> `completed`

```
request_transition(containerId="<feature-uuid>", containerType="feature", trigger="start")
```

- Move to `planning` when breaking down into tasks
- Move to `in-development` when tasks are actively being worked
- Move to `testing` when all tasks are complete
- Move to `completed` after validation

### 4. Track Progress

**Feature overview** (shows tasks list + counts, no section content):

```
query_container(operation="overview", containerType="feature", id="<feature-uuid>")
```

**Find tasks in a feature:**

```
query_container(operation="search", containerType="task", featureId="<feature-uuid>")
```

**Get next task to work on:**

```
get_next_task(featureId="<feature-uuid>", includeDetails=true)
```

**Check for blockers:**

```
get_blocked_tasks(featureId="<feature-uuid>", includeTaskDetails=true)
```

## Completion Workflow

Before completing a feature:

1. **Check all tasks are done:**
   ```
   query_container(operation="search", containerType="task", featureId="<uuid>", status="!completed,!cancelled")
   ```
   If any non-terminal tasks remain, complete them first.

2. **Review feature sections:**
   ```
   query_sections(entityType="FEATURE", entityId="<uuid>")
   ```

3. **Check readiness:**
   ```
   get_next_status(containerId="<uuid>", containerType="feature")
   ```

4. **Complete:**
   ```
   request_transition(containerId="<uuid>", containerType="feature", trigger="complete")
   ```

Watch for **cascade events** in the response — completing a feature's last task may trigger a suggestion to advance the feature status.

## Project-Level Overview

See all features in a project:

```
query_container(operation="overview", containerType="project", id="<project-uuid>")
```

Global overview (all projects, features, tasks):

```
query_container(operation="overview")
```

## Bulk Operations

Update multiple tasks at once:

```
manage_container(
  operation="bulkUpdate",
  containerType="task",
  containers=[
    {"id": "<uuid-1>", "priority": "high"},
    {"id": "<uuid-2>", "priority": "high"},
    {"id": "<uuid-3>", "tags": "sprint-3,backend"}
  ]
)
```

## Feature Status Flows

Default: `draft` -> `planning` -> `in-development` -> `testing` -> `validating` -> `completed`

| Tags | Flow |
|------|------|
| `prototype`, `poc`, `spike` | rapid_prototype_flow: draft -> in-development -> completed |
| `experiment`, `research` | experimental_flow: draft -> in-development -> archived |

Emergency transitions (from any status): `blocked`, `on-hold`, `archived`

## Partial Updates

Only send fields that changed:

```
manage_container(operation="update", containerType="feature", id="<uuid>", status="in-development")
```

Never fetch-modify-save. Always use partial updates for efficiency.
