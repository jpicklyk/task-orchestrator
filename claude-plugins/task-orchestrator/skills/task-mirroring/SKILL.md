---
name: task-mirroring
description: Mirror MCP Task Orchestrator tasks to Claude Code's task display for terminal visibility. Use when loading a feature's tasks or creating new mirrored tasks.
---

# Task Mirroring — MCP to Claude Code Display

MCP Task Orchestrator is the source of truth. Claude Code tasks are a one-way display mirror (MCP → CC). Two hooks handle ongoing sync automatically:
- **PostToolUse** (`status-sync.sh`): After `request_transition`, prompts you to update the CC mirror status
- **TaskCompleted** (`task-complete-sync.sh`): Blocks CC completion until MCP is transitioned first

This skill covers **bootstrapping** — creating the initial mirrors. The hooks handle everything after that.

## Loading a Feature's Tasks

When the user selects a feature to work on:

```
1. query_container(operation="overview", containerType="feature", id="<feature-uuid>")
2. For each task in the response:
   a. TaskCreate(subject: "[<first-8-of-uuid>] <title>", description: "MCP task <full-uuid> | Feature: <name>", activeForm: "<present continuous>")
   b. TaskUpdate(taskId: "<cc-id>", metadata: { "mcpTaskId": "<uuid>", "mcpFeatureId": "<feature-uuid>" })
   c. TaskUpdate to set initial CC status (map from MCP status)
3. Set up blockedBy relationships between CC tasks where MCP dependencies exist
```

The `[xxxxxxxx]` prefix enables the TaskCompleted hook to identify mirrored tasks.

### Status Mapping (MCP → CC)

- **pending**: BACKLOG, PENDING, DEFERRED, BLOCKED, ON_HOLD
- **in_progress**: IN_PROGRESS, IN_REVIEW, CHANGES_REQUESTED, TESTING, READY_FOR_QA, INVESTIGATING
- **completed**: COMPLETED, DEPLOYED, CANCELLED

### Dependency Mirroring

```
# MCP: Task A blocks Task B → CC mirror:
TaskUpdate(taskId: "<cc-task-B-id>", addBlockedBy: ["<cc-task-A-id>"])
```

## Creating a Single Mirror Task

When a new MCP task is created mid-session:

```
TaskCreate(
  subject: "[<first-8-of-uuid>] <title>",
  description: "MCP task <full-uuid> | Feature: <name>",
  activeForm: "<present continuous>"
)
TaskUpdate(taskId: "<cc-id>", metadata: { "mcpTaskId": "<uuid>" })
```

## Cascade Events

When `request_transition` returns cascade events suggesting a feature status change, no CC task update is needed — cascades affect the MCP feature, not individual CC mirror tasks.
