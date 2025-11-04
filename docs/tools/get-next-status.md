---
layout: default
title: get_next_status Tool
---

# get_next_status - Intelligent Status Progression Recommendations

**Permission**: 🔍 READ-ONLY

**Category**: Workflow Tools (v2.0)

**Purpose**: Get intelligent workflow recommendations for entity status progression based on configuration-driven flows and prerequisite validation.

## Overview

The `get_next_status` tool is a read-only MCP tool that analyzes entity state and recommends the next status in a workflow. It provides AI-friendly recommendations for status transitions by:

1. **Determining active workflow** based on entity tags (bug_fix_flow, documentation_flow, default_flow)
2. **Finding current position** in the workflow sequence
3. **Checking prerequisites** (summary populated, tasks completed, dependencies resolved)
4. **Detecting terminal statuses** (completed, cancelled, archived) that block progression
5. **Returning rich context** for AI decision-making

This tool **only suggests** next status - it does NOT change status. Use `manage_container` with `setStatus` operation to apply the recommended status.

**Primary User**: Status Progression Skill (Claude Code) - Uses this tool to provide human-friendly status guidance.

## Quick Start

### Basic Usage

Get a recommendation for the next status:

```json
{
  "operation": "get_next_status",
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}
```

### Response Examples

#### Ready (can progress)

```json
{
  "success": true,
  "message": "Ready to progress to 'testing' in default_flow",
  "data": {
    "recommendation": "Ready",
    "recommendedStatus": "testing",
    "currentStatus": "in-progress",
    "activeFlow": "default_flow",
    "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
    "currentPosition": 2,
    "matchedTags": [],
    "reason": "Ready to progress. Summary populated (420 chars). No incomplete blocking dependencies."
  }
}
```

#### Blocked (cannot progress)

```json
{
  "success": true,
  "message": "Blocked by 2 issue(s)",
  "data": {
    "recommendation": "Blocked",
    "currentStatus": "in-progress",
    "blockers": [
      "Summary required (300-500 chars, current: 50 chars)",
      "1 incomplete blocking dependency"
    ],
    "activeFlow": "default_flow",
    "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
    "currentPosition": 2,
    "reason": "Cannot progress: Summary required (300-500 chars, current: 50 chars); 1 incomplete blocking dependency"
  }
}
```

#### Terminal (cannot progress further)

```json
{
  "success": true,
  "message": "At terminal status 'completed'",
  "data": {
    "recommendation": "Terminal",
    "currentStatus": "completed",
    "activeFlow": "default_flow",
    "reason": "Status 'completed' is terminal. No further progression available."
  }
}
```

## Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `containerId` | UUID string | **Yes** | ID of the task, feature, or project to analyze |
| `containerType` | enum | **Yes** | Type of container: `task`, `feature`, or `project` |
| `currentStatus` | string | No | Current status (if not provided, fetched from entity). Useful for what-if analysis. |
| `tags` | array of strings | No | Override entity tags for flow determination. Useful for testing different workflows. |

## Response Schema

### Success Response

All responses include:

```json
{
  "success": boolean,
  "message": "Human-readable message",
  "data": {
    "recommendation": "Ready|Blocked|Terminal",
    // ... recommendation-specific fields below
  }
}
```

### Recommendation Type: Ready

Entity can progress to next status:

| Field | Type | Description |
|-------|------|-------------|
| `recommendation` | string | Always: `"Ready"` |
| `recommendedStatus` | string | Next status to transition to (e.g., `"testing"`) |
| `currentStatus` | string | Current entity status |
| `activeFlow` | string | Name of active workflow (e.g., `"bug_fix_flow"`, `"default_flow"`) |
| `flowSequence` | array | Complete status sequence for active flow |
| `currentPosition` | integer | 0-based index of current status in flow |
| `matchedTags` | array | Tags that matched to determine flow (empty if using default) |
| `reason` | string | Human-readable explanation (e.g., "Ready to progress. Summary populated...") |

**Example**: Task at position 2 of 5-status flow can move to position 3.

### Recommendation Type: Blocked

Entity cannot progress due to blockers:

| Field | Type | Description |
|-------|------|-------------|
| `recommendation` | string | Always: `"Blocked"` |
| `currentStatus` | string | Current status (cannot change yet) |
| `blockers` | array | List of blocking reasons |
| `activeFlow` | string | Name of active workflow |
| `flowSequence` | array | Complete status sequence |
| `currentPosition` | integer | 0-based index of current status |
| `reason` | string | Concatenation of blockers with details |

**Common blockers:**
- `"Summary required (300-500 chars, current: X chars)"`
- `"N task(s) not completed. Incomplete: [List]"`
- `"N incomplete blocking dependencies"`
- `"Feature must have at least 1 task"`

### Recommendation Type: Terminal

Entity is at terminal status and cannot progress further:

| Field | Type | Description |
|-------|------|-------------|
| `recommendation` | string | Always: `"Terminal"` |
| `currentStatus` | string | Terminal status (e.g., `"completed"`, `"cancelled"`) |
| `activeFlow` | string | Name of active workflow |
| `reason` | string | Human-readable explanation (e.g., "Status is terminal. No further progression.") |

**Terminal statuses vary by container type:**
- **Task**: `completed`, `cancelled`, `deferred`
- **Feature**: `completed`, `archived`
- **Project**: `completed`, `archived`, `cancelled`

## Operations

This tool has a **single operation** (implicit, no `operation` parameter needed):

**Operation**: Get recommendation

Analyzes entity and returns status progression recommendation. Always reads entity state; optionally validates prerequisites if `containerId` is provided.

## Workflow Detection Logic

### How get_next_status Determines Active Workflow

The tool uses a **tag-based flow determination** system:

1. **Check entity tags** against configured `flow_mappings` in `config.yaml`
2. **First matching flow** wins (order matters in config)
3. **If no match**, use `default_flow` for container type
4. **Return flow sequence** and matched tags in response

### Example: Bug Fix Flow

**Configuration** (config.yaml):

```yaml
status_progression:
  tasks:
    default_flow:
      - backlog
      - pending
      - in-progress
      - testing
      - completed

    bug_fix_flow:          # Skip backlog for urgent bugs
      - pending
      - in-progress
      - testing
      - completed

    flow_mappings:
      - tags: [bug, bugfix]
        flow: bug_fix_flow
```

**Entity tags**: `["bug", "critical"]`

**Result**: Matches `bug_fix_flow` (because `bug` tag matches)
- Flow: `[pending, in-progress, testing, completed]`
- Backlog skipped for bug fixes

### Example: Documentation Flow

**Configuration**:

```yaml
status_progression:
  tasks:
    documentation_flow:    # Minimal flow for docs
      - pending
      - in-progress
      - completed

    flow_mappings:
      - tags: [documentation, docs]
        flow: documentation_flow
```

**Entity tags**: `["documentation", "api-reference"]`

**Result**: Matches `documentation_flow`
- Flow: `[pending, in-progress, completed]`
- Testing skipped for pure documentation

## Prerequisite Validation

When entity can progress, `get_next_status` validates prerequisites before recommending status. Prerequisites are defined in `config.yaml` under `status_validation`:

### Task Prerequisites

| Target Status | Prerequisite | How to Fix |
|---------------|--------------|-----------|
| `in-progress` | No incomplete blocking dependencies | Complete dependency tasks first |
| `completed` | Summary must be 300-500 characters | Update summary with `manage_container` |

**Example blocker**: "Summary required (300-500 chars, current: 50 chars)"

### Feature Prerequisites

| Target Status | Prerequisite | How to Fix |
|---------------|--------------|-----------|
| `in-development` | Must have ≥1 task | Create at least one task |
| `testing` | All tasks completed or cancelled | Complete or cancel all tasks |
| `completed` | All tasks completed or cancelled | Complete or cancel all tasks |

**Example blocker**: "3 task(s) not completed. Incomplete: Task A, Task B, Task C"

### Project Prerequisites

| Target Status | Prerequisite | How to Fix |
|---------------|--------------|-----------|
| `completed` | All features completed | Complete all features |

## Use Cases

### Use Case 1: "Can I complete this task?"

**Scenario**: Specialist finished implementation, wants to mark task completed.

**Request**:
```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}
```

**If Ready**:
```json
{
  "recommendation": "Ready",
  "recommendedStatus": "completed",
  "reason": "Ready to progress. Summary populated (420 chars). No blocking dependencies."
}
```

Response: "Yes, safe to complete. Summary and dependencies are fine."

**If Blocked**:
```json
{
  "recommendation": "Blocked",
  "blockers": ["Summary required (300-500 chars, current: 120 chars)"]
}
```

Response: "No, expand summary to 300 chars minimum first."

### Use Case 2: "What's next for this feature?"

**Scenario**: Product manager checking feature progress.

**Request**:
```json
{
  "containerId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "containerType": "feature"
}
```

**Response**:
```json
{
  "recommendation": "Ready",
  "currentStatus": "in-development",
  "recommendedStatus": "testing",
  "flowSequence": ["draft", "planning", "in-development", "testing", "validating", "completed"],
  "currentPosition": 2,
  "reason": "All 5 tasks completed. Ready to move to testing phase."
}
```

Response: "Feature is ready to move from development to testing. All tasks done."

### Use Case 3: "Why can't I mark this feature complete?"

**Scenario**: Feature team stuck on validation.

**Request**:
```json
{
  "containerId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "containerType": "feature"
}
```

**Response**:
```json
{
  "recommendation": "Blocked",
  "blockers": ["2 task(s) not completed. Incomplete: \"Update docs\", \"Fix edge cases\""],
  "currentStatus": "testing"
}
```

Response: "Two tasks need completion before feature can move forward."

### Use Case 4: What-if Analysis

**Test different workflow**: What if this task had `bug` tag instead?

**Request**:
```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task",
  "currentStatus": "in-progress",
  "tags": ["bug", "critical"]  // Override tags for analysis
}
```

**Response**: Shows recommendation using bug_fix_flow instead of default_flow.

## Advanced Usage

### Applying Recommended Status

Once you get a "Ready" recommendation, apply it with `manage_container`:

**Step 1: Get recommendation**
```json
{
  "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "containerType": "task"
}
```

**Step 2: Check recommendation**
```json
{
  "recommendation": "Ready",
  "recommendedStatus": "testing"
}
```

**Step 3: Apply status**
```json
{
  "operation": "setStatus",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "status": "testing"
}
```

### Flow Path Visualization

Use `flowSequence` and `currentPosition` to display workflow progress:

```
Backlog  →  Pending  →  In-Progress  →  Testing  →  Completed
                                       ↑ YOU ARE HERE

Next: Completed (when testing passes)
```

### Status Progression Skill Integration

The Status Progression Skill (Claude Code) automatically uses `get_next_status` to:

1. Recommend next status when user asks "What's next?"
2. Check readiness before marking complete ("Can I complete this?")
3. Explain blockers when status change not allowed
4. Suggest flow alternatives based on tags
5. Guide users through multi-step status transitions

## Error Handling

### Common Errors

| Error | Cause | Fix |
|-------|-------|-----|
| `RESOURCE_NOT_FOUND` | Container doesn't exist | Verify ID is correct and entity exists |
| `VALIDATION_ERROR` | Invalid parameters | Check containerId format (UUID), containerType value |
| `INTERNAL_ERROR` | Service failure | Check config.yaml exists, database connectivity |

### Error Response Example

```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task with ID: a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d"
  }
}
```

## Configuration Examples

### Configuration: Bug Fix Flow

**config.yaml**:
```yaml
status_progression:
  tasks:
    bug_fix_flow:
      - pending           # Skip backlog for urgent bugs
      - in-progress
      - testing
      - completed

    flow_mappings:
      - tags: [bug, bugfix]
        flow: bug_fix_flow
```

**When user tags task with `bug`**:
- Active flow: `bug_fix_flow`
- Sequence: `[pending, in-progress, testing, completed]`
- Backlog skipped automatically

### Configuration: Rapid Prototype Flow

**config.yaml**:
```yaml
status_progression:
  features:
    rapid_flow:
      - draft
      - in-development    # Skip planning
      - completed         # Skip testing

    flow_mappings:
      - tags: [prototype, poc, spike]
        flow: rapid_flow
```

**When feature tagged with `prototype`**:
- Active flow: `rapid_flow`
- Can skip planning and testing
- Faster progression for experiments

### Configuration: Enterprise Approval Flow

**config.yaml**:
```yaml
status_progression:
  features:
    approval_flow:
      - draft
      - planning
      - in-development
      - testing
      - pending-review    # Mandatory review gate
      - completed

    flow_mappings:
      - tags: [enterprise, stakeholder-approval]
        flow: approval_flow
```

**When feature tagged with `enterprise`**:
- Must pass `pending-review` before completion
- Cannot skip approval step

## Best Practices

### DO

✅ **Check readiness before applying status**
```json
// Step 1: Get recommendation
{"containerId": "...", "containerType": "task"}

// Step 2: Verify recommendation is "Ready"
if (recommendation == "Ready") {
  // Step 3: Apply recommended status
  manage_container(operation="setStatus", ...)
}
```

✅ **Use tags to customize workflows**
```yaml
# Tag-based flow determination
flow_mappings:
  - tags: [bug]
    flow: bug_fix_flow      # Skip backlog
  - tags: [documentation]
    flow: documentation_flow # Skip testing
```

✅ **Validate prerequisites before user actions**
- Check summary length before completion
- Verify task completion before feature progression
- Confirm dependencies resolved before in-progress

✅ **Use what-if analysis for testing**
```json
{
  "containerId": "...",
  "tags": ["different", "tags"],  // Test different flow
  "currentStatus": "pending"
}
```

### DON'T

❌ **Don't apply status without checking recommendation**
```javascript
// WRONG - No validation
manage_container(operation="setStatus", status="completed")

// CORRECT - Check first
rec = get_next_status(...)
if (rec.recommendation == "Ready") {
  manage_container(operation="setStatus", ...)
}
```

❌ **Don't assume all entities use default_flow**
- Custom flows determined by tags
- Different entities may have different active flows
- Check `activeFlow` in response to understand progression

❌ **Don't modify config.yaml while tool is running**
- Config cached for 60 seconds
- Changes may not be reflected immediately
- Restart server to force config reload

## Integration Patterns

### Pattern 1: Status Progression Skill (Claude Code)

**Primary integration**: Status Progression Skill calls `get_next_status` internally.

**User workflow**:
1. User asks: "What's next?" / "Can I complete this?"
2. Status Progression Skill calls get_next_status
3. Skill interprets recommendation and provides guidance
4. Skill offers manage_container commands if Ready

### Pattern 2: UI Status Indicator

**Display next available status**:
```javascript
// Get recommendation
rec = get_next_status(entityId, type)

// Show indicator
if (rec.recommendation == "Ready") {
  showNextStatusButton(rec.recommendedStatus)  // Green checkmark
} else if (rec.recommendation == "Blocked") {
  showBlockedMessage(rec.blockers)             // Red X with reasons
} else {
  showTerminalStatus(rec.currentStatus)        // Gray lock icon
}
```

### Pattern 3: Workflow Visualization

**Display progress through flow**:
```javascript
rec = get_next_status(...)

// Show progression
for (let i = 0; i < rec.flowSequence.length; i++) {
  if (i < rec.currentPosition) {
    mark(rec.flowSequence[i], 'completed')
  } else if (i == rec.currentPosition) {
    mark(rec.flowSequence[i], 'current')
  } else {
    mark(rec.flowSequence[i], 'future')
  }
}
```

### Pattern 4: Pre-transition Validation

**Validate before applying status change**:
```javascript
// User requests status change
userRequestedStatus = "testing"

// Check if valid
rec = get_next_status(entityId, type)
if (rec.recommendation == "Ready" &&
    rec.recommendedStatus == userRequestedStatus) {
  // Safe to apply
  applyStatus(userRequestedStatus)
} else if (rec.recommendation == "Blocked") {
  // Show blockers to user
  showBlockers(rec.blockers)
}
```

## Related Tools

| Tool | Purpose | When to Use |
|------|---------|------------|
| **manage_container** (setStatus) | Apply status change | After get_next_status recommends "Ready" |
| **query_container** (get) | Get full entity details | To understand entity context before transition |
| **Status Progression Skill** | AI-friendly status guidance | For natural language workflow recommendations |

## Examples

### Example 1: Simple Task Completion Check

**Scenario**: Implementing specialist finished task, wants to mark complete.

**Request**:
```bash
curl -X POST http://localhost:8000/mcp \
  -H "Content-Type: application/json" \
  -d '{
    "method": "tools/call",
    "params": {
      "name": "get_next_status",
      "arguments": {
        "containerId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
        "containerType": "task"
      }
    }
  }'
```

**Response (Ready)**:
```json
{
  "success": true,
  "message": "Ready to progress to 'completed' in default_flow",
  "data": {
    "recommendation": "Ready",
    "recommendedStatus": "completed",
    "currentStatus": "testing",
    "activeFlow": "default_flow",
    "flowSequence": ["backlog", "pending", "in-progress", "testing", "completed"],
    "currentPosition": 3,
    "matchedTags": [],
    "reason": "Ready to progress. Summary populated (450 chars). All tests passing."
  }
}
```

**Next action**: Apply completion with manage_container

### Example 2: Feature Progress Check with Requirements

**Scenario**: Project manager checks if feature ready for testing.

**Request**:
```json
{
  "containerId": "f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c",
  "containerType": "feature"
}
```

**Response (Blocked)**:
```json
{
  "success": true,
  "message": "Blocked by 1 issue(s)",
  "data": {
    "recommendation": "Blocked",
    "currentStatus": "in-development",
    "blockers": [
      "2 task(s) not completed. Incomplete: \"Add API tests\", \"Document endpoints\""
    ],
    "activeFlow": "default_flow",
    "flowSequence": ["draft", "planning", "in-development", "testing", "validating", "completed"],
    "currentPosition": 2,
    "reason": "Cannot progress: 2 task(s) not completed. Incomplete: \"Add API tests\", \"Document endpoints\""
  }
}
```

**Action**: Inform team about incomplete tasks before moving to testing.

### Example 3: Bug Fix Workflow Analysis

**Scenario**: Analyzing task with custom bug_fix_flow.

**Entity**:
- Tags: `["bug", "critical"]`
- Status: `"in-progress"`
- Summary: Populated (320 chars)
- Dependencies: None blocking

**Request**:
```json
{
  "containerId": "b2c3d4e5-f6a5-4b8c-9d0e-1f2a3b4c5d6e",
  "containerType": "task"
}
```

**Response**:
```json
{
  "success": true,
  "message": "Ready to progress to 'testing' in bug_fix_flow",
  "data": {
    "recommendation": "Ready",
    "recommendedStatus": "testing",
    "currentStatus": "in-progress",
    "activeFlow": "bug_fix_flow",
    "flowSequence": ["pending", "in-progress", "testing", "completed"],
    "currentPosition": 1,
    "matchedTags": ["bug"],
    "reason": "Ready to progress. Summary populated (320 chars). No blocking dependencies. Bug fix flow detected (matched tag: bug)."
  }
}
```

**Insight**: Bug flow skipped backlog; task already at position 1 (normal flow would have position 2).

## Troubleshooting

### "Cannot progress: Summary required (300-500 chars, current: 50 chars)"

**Problem**: Task summary too short for completion.

**Solution**: Update summary to minimum 300 characters.

```json
{
  "operation": "update",
  "containerType": "task",
  "id": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "summary": "Implemented OAuth2 authentication with JWT tokens. Created AuthController with login/logout endpoints. UserService handles user management and validation. SecurityConfig applies security filters. Added refresh token mechanism with configurable expiry. All 15 unit tests passing, 8 integration tests passing. Code follows project conventions."
}
```

### "N incomplete blocking dependencies"

**Problem**: Task has blocking dependencies not completed.

**Solution**: Complete blocking dependencies first.

```json
{
  // Check which tasks are blocking
  "taskId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
  "direction": "incoming",
  "includeTaskInfo": true
}
```

Then complete each blocking task before retrying.

### "Active flow is empty for task"

**Problem**: No workflow configured for task type.

**Solution**: Verify config.yaml has `status_progression.tasks` section with at least `default_flow`.

```yaml
status_progression:
  tasks:
    default_flow:
      - backlog
      - pending
      - in-progress
      - testing
      - completed
```

## References

- **Source code**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/status/GetNextStatusTool.kt`
- **Service implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/progression/StatusProgressionServiceImpl.kt`
- **Configuration reference**: `src/main/resources/orchestration/default-config.yaml`
- **Status validation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/service/StatusValidator.kt`
- **Tests**: `src/test/kotlin/io/github/jpicklyk/mcptask/application/tools/status/GetNextStatusToolTest.kt`

### Related Documentation

- [Status Progression Guide](../status-progression.md) - Detailed workflow examples and use cases
- [API Reference](../api-reference.md) - Complete MCP tools documentation
- [manage_container](manage-container.md) - Apply status changes
- [Status Progression Skill](./../../../.claude/skills/status-progression/SKILL.md) - AI-friendly guidance (Claude Code only)
