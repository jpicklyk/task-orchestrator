---
name: task-mirroring
description: Mirror MCP Task Orchestrator tasks to Claude Code's task display for terminal visibility. Use when loading a feature's tasks or creating new mirrored tasks.
---

# Task Mirroring — MCP to Claude Code Display

MCP Task Orchestrator is the source of truth. Claude Code tasks are a one-way display mirror (MCP -> CC). Two hooks handle ongoing sync automatically:
- **PostToolUse** (`status-sync.sh`): After `request_transition`, prompts CC mirror status update
- **TaskCompleted** (`task-complete-sync.sh`): Blocks CC completion until MCP is transitioned first
- **PostToolUse** (`create-mirror-prompt.sh`): After task creation, prompts CC mirror creation

This skill covers **bootstrapping** — loading a feature's tasks into CC display. Hooks handle everything after that.

## Loading a Feature's Tasks

1. `query_container(operation="overview")` for the feature to get its task list
2. **Skip terminal-status tasks** (COMPLETED, DEPLOYED, CANCELLED, ARCHIVED) — the CC task list is a work queue, not a history log. MCP has the full record.
3. For each remaining task, create a CC mirror:
   - Subject: `[<first-8-of-uuid>] <title>` — the prefix enables hook identification
   - Description: `MCP task <full-uuid> | Feature: <name>`
   - Metadata: `{ "mcpTaskId": "<uuid>", "mcpFeatureId": "<feature-uuid>" }`
   - Map MCP status to CC status (see below)
4. Mirror MCP dependencies as CC `blockedBy` relationships (only among mirrored tasks)

### Status Mapping (MCP -> CC)

- **pending**: BACKLOG, PENDING, DEFERRED, BLOCKED, ON_HOLD
- **in_progress**: IN_PROGRESS, IN_REVIEW, CHANGES_REQUESTED, TESTING, READY_FOR_QA, INVESTIGATING

## Cascade Events

When `request_transition` returns cascade events suggesting a feature status change, no CC task update is needed — cascades affect the MCP feature, not individual CC mirror tasks.
