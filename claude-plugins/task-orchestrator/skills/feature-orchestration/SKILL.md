---
name: feature-orchestration
description: Coordinate feature lifecycle, task breakdown, and completion workflows using MCP Task Orchestrator. Use when managing features, breaking features into tasks, or tracking feature progress.
---

# Feature Orchestration

Features group related tasks under a project and track progress through a defined workflow.

## Feature Lifecycle

1. **Discover templates** with `query_templates(targetEntityType="FEATURE")` before creating
2. **Create feature** with `manage_container(operation="create", containerType="feature")` — pass `projectId` and `templateIds`
3. **Break down into tasks** — create tasks with `featureId` set. Set dependencies between tasks (see `dependency-analysis` skill)
4. **Progress status** with `request_transition(trigger="start")`

### When to Transition

- `planning` — when breaking down into tasks
- `in-development` — when tasks are actively being worked
- `testing` — when all tasks are complete
- `completed` — after validation

Default flow: `draft` -> `planning` -> `in-development` -> `testing` -> `validating` -> `completed`. See `status-progression` skill for tag-based flows and emergency transitions.

## Tracking Progress

- **Feature overview** (tasks + counts, no sections): `query_container(operation="overview")` with feature ID
- **Find tasks:** `query_container(operation="search", containerType="task", featureId=...)`
- **Next task:** `get_next_task(featureId=...)`
- **Blockers:** `get_blocked_tasks(featureId=...)`

## Feature Tagging

Tags drive workflow selection and enable filtering. Apply them via the `tags` parameter on create/update.

### Workflow Tags

These tags select which status flow a feature follows:

| Tags | Flow | Use When |
|------|------|----------|
| `prototype`, `poc`, `spike` | draft → in-development → completed | Rapid validation, skip planning/testing |
| `experiment`, `research` | draft → in-development → archived | Exploratory work, may not reach completion |

Without workflow tags, the default feature flow applies: draft → planning → in-development → testing → validating → completed.

### Organizational Tags

Use for filtering and categorization:
- **Domain area:** `authentication`, `api`, `database`, `ui`, `infrastructure`
- **Work type:** `tech-debt`, `enhancement`, `migration`, `integration`
- **Priority signals:** `blocking`, `deferred`, `critical`

Tags are searchable via `query_container(operation="search", tags="authentication")`.

## Completion Workflow

Before completing a feature:

1. Verify all tasks are done — search for non-terminal tasks with `status="!completed,!cancelled"`
2. Review feature sections with `query_sections`
3. Check readiness with `get_next_status`
4. Complete with `request_transition(trigger="complete")`

**Cleanup warning:** Completing a feature triggers automatic deletion of child tasks (except those tagged `bug`/`bugfix`/`fix`/`hotfix`/`critical`). To preserve task details, export the feature first with `query_container(operation="export")`. See `status-progression` skill for full cleanup details and configuration options.

Watch for **cascade events** in the response — they may suggest advancing the parent project status.

## Bulk Operations

Use `manage_container(operation="bulkUpdate")` with a `containers` array to update multiple tasks at once (priority, tags, etc.).
