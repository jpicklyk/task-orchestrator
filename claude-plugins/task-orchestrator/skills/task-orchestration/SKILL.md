---
name: task-orchestration
description: Manage tasks using MCP Task Orchestrator tools. Use when creating, updating, completing, or tracking tasks through the task lifecycle.
---

# Task Orchestration

Tasks are the primary work items, organized under Features (optional) and Projects (optional).

## Core Workflow

1. **Discover templates** with `query_templates(targetEntityType="TASK")` before creating tasks
2. **Create tasks** with `manage_container(operation="create", containerType="task")` — pass `templateIds` to auto-create documentation sections, `featureId` to group under a feature
3. **Progress status** with `request_transition(trigger="start")`. See `status-progression` skill for flows and triggers.
4. **Complete** with `request_transition(trigger="complete")` — check sections are filled and dependencies resolved first. Update CC mirror after. See `task-mirroring` skill.

## Verification Gates

Templates with a Verification section (Task Implementation, Bug Investigation) auto-enable `requiresVerification: true` when applied. The completion workflow becomes:

1. Agent defines acceptance criteria in the Verification section as JSON
2. Agent performs the work
3. Agent verifies each criterion and flips `pass` to `true`
4. `request_transition(trigger="complete")` checks all criteria pass

Set `requiresVerification: true` manually when creating tasks without templates that warrant verification (code changes, infrastructure, tests).

## Partial Updates

**Never fetch an entity just to update it.** Use `manage_container(operation="update")` with only the changed fields. Token savings: 90-95% vs sending the full entity.

## Section Management

Sections hold detailed content (technical approach, test plan, notes). Templates auto-create them.

- **Read:** `query_sections` with `entityType` and `entityId`
- **Surgical edit:** `manage_sections(operation="updateText")` with `oldText`/`newText` — avoids rewriting entire content
- **Add/bulk create:** `manage_sections` operations `add` and `bulkCreate`

## Finding Work

- **Smart recommendation:** `get_next_task` — respects dependencies, sorts by priority then complexity (quick wins first)
- **Filtered search:** `query_container(operation="search")` — supports multi-value filters (`status="pending,in-progress"`) and negation (`status="!completed,!cancelled"`)
- **Export:** `query_container(operation="export")` — full markdown representation with all sections
