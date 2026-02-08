---
name: task-mirroring
description: Mirror MCP Task Orchestrator tasks to Claude Code's task display for terminal visibility. Use when loading a feature's tasks, starting work, or progressing task status.
---

# Task Mirroring — MCP to Claude Code Display

Patterns for mirroring MCP Task Orchestrator tasks into Claude Code's terminal task display. This provides users with real-time visual feedback of orchestrated work without duplicating content.

## Principle

MCP Task Orchestrator is the source of truth. Claude Code tasks are a lightweight display mirror. Sync is one-way: MCP → CC display. CC tasks created independently (not mirrored) must never write back to MCP.

## When to Mirror

Mirror MCP tasks to CC display when:
- A feature is selected for work (load all its tasks)
- A new MCP task is created during the session
- An MCP task status changes (update the CC mirror)

Do NOT mirror:
- Tasks from unrelated features or projects
- Tasks the user hasn't focused on in this session

## Creating a Mirror Task

When loading MCP tasks for a feature, create CC tasks:

```
TaskCreate(
  subject: "[<first-8-of-mcp-uuid>] <MCP task title>",
  description: "MCP task <full-uuid> | Feature: <feature-name>",
  activeForm: "<present continuous form of the task>"
)
```

The `[xxxxxxxx]` prefix is the first 8 characters of the MCP task UUID. This makes mirrored tasks visually identifiable and enables the TaskCompleted hook to enforce MCP transitions.

Example: MCP task `d6f36eb4-648c-422d-ad47-965d28fc42e2` becomes:
`[d6f36eb4] Add getRoleForStatus() to StatusProgressionService`

Then store the correlation in metadata:

```
TaskUpdate(
  taskId: "<cc-task-id>",
  metadata: { "mcpTaskId": "<mcp-uuid>", "mcpFeatureId": "<mcp-feature-uuid>" }
)
```

### Field Mapping

| MCP Field | CC Field | Notes |
|-----------|----------|-------|
| `title` | `subject` | Prefixed with `[first-8-of-uuid]` for hook identification |
| `status` | `status` | Map using status table below |
| `id` | `metadata.mcpTaskId` | For correlation |
| `featureId` | `metadata.mcpFeatureId` | For grouping |
| — | `activeForm` | Derive from title: "Implementing...", "Testing...", etc. |
| — | `description` | Brief context line, not full MCP description |

### Status Mapping

| MCP Status | CC Status | Rationale |
|------------|-----------|-----------|
| BACKLOG | pending | Not yet started |
| PENDING | pending | Ready but not active |
| DEFERRED | pending | Postponed |
| BLOCKED | pending | Cannot proceed |
| ON_HOLD | pending | Paused |
| IN_PROGRESS | in_progress | Active work |
| IN_REVIEW | in_progress | Still being processed |
| CHANGES_REQUESTED | in_progress | Rework in progress |
| TESTING | in_progress | Validation phase |
| READY_FOR_QA | in_progress | Awaiting final check |
| INVESTIGATING | in_progress | Research/analysis |
| COMPLETED | completed | Done |
| DEPLOYED | completed | Released |
| CANCELLED | completed | Terminal state |

## Updating Mirror Tasks

After any MCP status change (`request_transition` or `manage_container setStatus`):

1. Find the CC task with matching `metadata.mcpTaskId`
2. Map the new MCP status to CC status
3. Update: `TaskUpdate(taskId: "<cc-id>", status: "<mapped-status>")`

### After request_transition

```
# MCP call
request_transition(containerId="<uuid>", containerType="task", trigger="complete")

# Mirror update
TaskUpdate(taskId: "<cc-mirror-id>", status: "completed")
```

### After Cascade Events

When `request_transition` returns cascade events suggesting a feature status change, this is informational for the orchestrator. No CC task update needed for cascades — they affect the MCP feature, not individual CC mirror tasks.

## Completing Mirror Tasks (CC → MCP)

A TaskCompleted hook enforces that mirrored CC tasks trigger an MCP transition before completion is allowed.

**Always transition MCP first, then complete the CC mirror:**

1. `request_transition(containerId="<uuid>", containerType="task", trigger="complete")`
2. `TaskUpdate(taskId: "<cc-mirror-id>", status: "completed")`

If you complete the CC task first, the hook blocks once and provides the exact `request_transition` call. After transitioning, retry the CC completion.

Non-mirrored CC tasks (no `[xxxxxxxx]` prefix in subject) are unaffected.

## Loading a Feature's Tasks

When the user selects a feature to work on:

```
1. query_container(operation="overview", containerType="feature", id="<feature-uuid>")
2. For each task in the response:
   a. TaskCreate with mapped fields
   b. TaskUpdate to set metadata correlation
   c. TaskUpdate to set initial status (map from MCP status)
3. Set up blockedBy relationships between CC tasks where MCP dependencies exist
```

### Dependency Mirroring

If MCP tasks have dependencies, mirror them as CC task blockedBy:

```
# MCP: Task A blocks Task B
# CC mirror:
TaskUpdate(taskId: "<cc-task-B-id>", addBlockedBy: ["<cc-task-A-id>"])
```

This ensures the CC display shows which tasks are blocked.

## Identifying Mirror vs Native Tasks

- **Mirror tasks**: Have `metadata.mcpTaskId` set — these reflect MCP state
- **Native CC tasks**: No `mcpTaskId` in metadata — these are orchestrator-created for session coordination

The orchestrator can freely create native CC tasks for its own tracking. These do not sync back to MCP.

## Lifecycle

1. **Session start**: Load focused feature → create CC mirrors
2. **During work**: Update CC mirrors as MCP statuses change
3. **Session end**: CC tasks persist in `~/.claude/tasks/` but are ephemeral context — MCP orchestrator is the durable record
4. **Next session**: Fresh mirrors created from current MCP state (old CC tasks may be cleared)
