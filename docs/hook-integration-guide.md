# Hook Integration Guide

This guide covers how to write Claude Code plugin hooks that integrate with Task Orchestrator's MCP tools, particularly detecting status transitions and role changes.

## Overview

Claude Code hooks can intercept tool execution at multiple points:

- **PreToolUse** — Before tool execution (can block)
- **PostToolUse** — After successful tool execution (informational)
- **PostToolUseFailure** — After tool execution failure

This guide focuses on **PostToolUse** hooks for the `request_transition` tool, which provide rich metadata about status changes and role transitions.

## Hook Context Structure

When a PostToolUse hook fires, it receives:

```json
{
  "toolName": "mcp__mcp-task-orchestrator__request_transition",
  "toolInput": {
    "transitions": [
      {
        "containerId": "uuid",
        "containerType": "task",
        "trigger": "start",
        "summary": "optional note"
      }
    ]
  },
  "toolOutput": {
    // Full response schema (see below)
  }
}
```

## request_transition Response Schema

The `toolOutput` field contains the complete response from `request_transition`:

```json
{
  "results": [
    {
      "containerId": "c45a1482-95ff-46dc-8053-eb57bb5fe980",
      "containerType": "task",
      "previousStatus": "in-progress",
      "newStatus": "testing",
      "trigger": "start",
      "applied": true,
      "previousRole": "work",
      "newRole": "review",
      "activeFlow": "with_testing_flow",
      "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
      "flowPosition": 3,
      "summary": "Optional transition note",
      "unblockedTasks": [
        {
          "taskId": "uuid",
          "title": "Task that was blocked"
        }
      ],
      "cascadeEvents": [
        {
          "parentId": "uuid",
          "parentType": "feature",
          "recommendedStatus": "testing",
          "applied": true,
          "reason": "All child tasks completed",
          "childCascades": []
        }
      ]
    }
  ],
  "summary": {
    "total": 1,
    "succeeded": 1,
    "failed": 0
  }
}
```

### Key Response Fields

#### Per-Result Fields

| Field | Type | Description |
|-------|------|-------------|
| `containerId` | UUID | Entity that transitioned |
| `containerType` | string | `"task"`, `"feature"`, or `"project"` |
| `previousStatus` | string | Status before transition |
| `newStatus` | string | Status after transition |
| `trigger` | string | Trigger used: `start`, `complete`, `cancel`, `block`, `hold` |
| `applied` | boolean | Whether transition was successful |
| `previousRole` | string | Role before transition (see Role Enum) |
| `newRole` | string | Role after transition |
| `activeFlow` | string | Workflow identifier (e.g., `default_flow`, `with_testing_flow`) |
| `flowSequence` | string[] | Ordered list of statuses in the active flow |
| `flowPosition` | integer | Current position in flow (0-based index) |
| `summary` | string? | Optional note about the transition |
| `unblockedTasks` | array | Tasks whose dependencies were resolved by this transition |
| `cascadeEvents` | array | Parent status changes triggered by this transition |

#### Role Enum Values

Roles represent semantic phases in a workflow:

| Role | Meaning | Examples |
|------|---------|----------|
| `queue` | Waiting to start | `backlog`, `pending`, `draft`, `planning` |
| `work` | Active development | `in-progress`, `changes-requested` |
| `review` | Validation phase | `testing`, `in-review`, `validating` |
| `blocked` | Impediment | `blocked`, `on-hold` |
| `terminal` | Final state | `completed`, `cancelled`, `deferred`, `archived` |

**Role ordering:** `queue` → `work` → `review` → `terminal` (with `blocked` as a lateral state).

## Detecting Role Changes

### Basic Role Transition Detection

Compare `previousRole` and `newRole` to detect phase changes:

```bash
#!/bin/bash
# PostToolUse hook for request_transition

# Parse JSON input (simplified)
toolName=$(jq -r '.toolName' <<< "$1")
previousRole=$(jq -r '.toolOutput.results[0].previousRole // empty' <<< "$1")
newRole=$(jq -r '.toolOutput.results[0].newRole // empty' <<< "$1")

if [[ "$previousRole" != "$newRole" ]]; then
  echo "Role changed: $previousRole -> $newRole"
fi
```

### Detecting Specific Transitions

#### Work → Review (Testing/Review Phase)

When a task moves from active development to validation:

```bash
if [[ "$previousRole" == "work" && "$newRole" == "review" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  echo "Task $taskId entered review phase - trigger code review"
fi
```

#### Review → Terminal (Completion)

When a task completes the review phase:

```bash
if [[ "$previousRole" == "review" && "$newRole" == "terminal" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  echo "Task $taskId completed review - archive artifacts"
fi
```

#### Queue → Work (Task Pickup)

When work starts on a queued task:

```bash
if [[ "$previousRole" == "queue" && "$newRole" == "work" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  echo "Task $taskId started - log to active work tracker"
fi
```

### Detecting Unblocked Tasks

When a task completes or is cancelled, downstream tasks may become unblocked:

```bash
unblockedCount=$(jq '.toolOutput.results[0].unblockedTasks | length' <<< "$1")

if [[ "$unblockedCount" -gt 0 ]]; then
  echo "Unblocked $unblockedCount downstream tasks:"
  jq -r '.toolOutput.results[0].unblockedTasks[] | "- \(.title) (\(.taskId))"' <<< "$1"
fi
```

### Detecting Cascade Events

Parent entities may auto-advance when all children reach terminal states:

```bash
cascadeCount=$(jq '.toolOutput.results[0].cascadeEvents | length' <<< "$1")

if [[ "$cascadeCount" -gt 0 ]]; then
  jq -r '.toolOutput.results[0].cascadeEvents[] |
    "Cascade: \(.parentType) \(.parentId) -> \(.recommendedStatus) (applied: \(.applied))"' <<< "$1"
fi
```

## Use Cases

### 1. Trigger External Code Review

```bash
if [[ "$previousRole" == "work" && "$newRole" == "review" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  # Call GitHub API to request review
  gh pr review --request-changes "$taskId" || true
fi
```

### 2. Log Work Metrics

```bash
if [[ "$newRole" == "terminal" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  previousStatus=$(jq -r '.toolOutput.results[0].previousStatus' <<< "$1")
  newStatus=$(jq -r '.toolOutput.results[0].newStatus' <<< "$1")

  echo "METRICS: task=$taskId from=$previousStatus to=$newStatus role=terminal"
fi
```

### 3. Update Project Board

```bash
if [[ "$previousRole" != "$newRole" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  newRole=$(jq -r '.toolOutput.results[0].newRole' <<< "$1")

  # Move card on Kanban board
  # update-board.sh "$taskId" "$newRole"
fi
```

### 4. Notify Team on Blockers

```bash
if [[ "$newRole" == "blocked" ]]; then
  taskId=$(jq -r '.toolOutput.results[0].containerId' <<< "$1")
  summary=$(jq -r '.toolOutput.results[0].summary // "No reason provided"' <<< "$1")

  # Send Slack notification
  # slack-notify.sh "Task $taskId blocked: $summary"
fi
```

## Hook Event Comparison

| Event | When it Fires | Can Block Tool | Can Inject Context |
|-------|--------------|----------------|-------------------|
| `PreToolUse` | Before tool execution | Yes | Yes |
| `PostToolUse` | After success | No | Yes |
| `PostToolUseFailure` | After failure | No | Yes |

### additionalContext Injection

Hooks can inject additional context into Claude's conversation:

```bash
#!/bin/bash
# Example: Inject review checklist when entering review phase

if [[ "$previousRole" == "work" && "$newRole" == "review" ]]; then
  cat <<EOF
{
  "hookSpecificOutput": {
    "additionalContext": "Task entered review phase. Run the following checklist:\n- [ ] Code review\n- [ ] Test coverage\n- [ ] Documentation updated"
  }
}
EOF
fi
```

**Note:** As of February 2025, `additionalContext` does **not** surface for MCP tool hooks due to a known limitation. This may change in future Claude Code releases.

## Known Limitations

1. **MCP Tool Context Injection** — `additionalContext` from PostToolUse hooks on MCP tools does not currently surface to Claude ([Issue #24788](https://github.com/anthropics/claude-code/issues/24788))
2. **Hook Output Caching** — Hook script changes require removing and re-adding the plugin from marketplace to refresh
3. **Batch Transitions** — Response contains an array of results; hooks must iterate over `results[]` for batch operations

## Testing Hooks

### 1. Parse Hook Input

Save hook input to a file for inspection:

```bash
echo "$1" > /tmp/hook-debug.json
jq '.' /tmp/hook-debug.json
```

### 2. Validate JSON Parsing

Test jq queries in isolation:

```bash
cat /tmp/hook-debug.json | jq -r '.toolOutput.results[0].previousRole'
```

### 3. Check Hook Registration

Verify hook is registered in plugin manifest:

```json
{
  "hooks": "hooks/hooks.json"
}
```

And in `hooks/hooks.json`:

```json
{
  "PostToolUse": [
    {
      "toolName": "mcp__mcp-task-orchestrator__request_transition",
      "type": "command",
      "command": "bash",
      "args": ["hooks/detect-role-change.sh"]
    }
  ]
}
```

## Related Documentation

- [Status Progression Skill](../claude-plugins/task-orchestrator/skills/status-progression/SKILL.md) — Workflow guidance
- [Claude Plugin Guide](claude-plugin.md) — Plugin development
- [Status Progression Guide](status-progression.md) — Workflow configuration

## Future Improvements

- Enhanced cascade event metadata
- Hook-friendly query tools for downstream analysis
- `additionalContext` support for MCP tool hooks
