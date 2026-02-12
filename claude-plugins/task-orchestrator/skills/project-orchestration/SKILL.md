---
name: project-orchestration
description: Manage project lifecycle, cross-feature tracking, and project completion workflows using MCP Task Orchestrator. Use when creating projects, tracking multi-feature progress, or completing projects.
---

# Project Orchestration

Projects are the top-level container, grouping related features into a single coordinated effort.

## When to Use Projects

- **Multiple related features** sharing a common goal (e.g., "v2.0 Release")
- **Cross-feature dependencies** where tasks in one feature block tasks in another
- **Portfolio tracking** across parallel workstreams

For single-feature work, skip the project layer and create a standalone feature.

## Project Lifecycle

1. **Create** a project with `manage_container(operation="create", containerType="project")`
2. **Add features** under it with `projectId` parameter. See `feature-orchestration` skill.
3. **Track progress** with `query_container(operation="overview", containerType="project")` — returns features + task counts without section content
4. **Search** across all project tasks with `query_container(operation="search", containerType="task", projectId=...)`
5. **Find blockers** across features with `get_blocked_tasks(projectId=...)`

## Status Flow

`planning` -> `in-development` -> `completed` -> `archived`

See `status-progression` skill for triggers, emergency transitions, and flow details.

## Completion Workflow

Before completing a project:

1. Verify all features are done — search for non-terminal features with `status="!completed,!cancelled"`
2. Check readiness with `get_next_status`
3. Complete with `request_transition(trigger="complete")`

## Cross-Feature Dependencies

Tasks in different features can block each other via `manage_dependencies`. Use `get_blocked_tasks(projectId=...)` to see blockers across the whole project. See `dependency-analysis` skill for patterns.
