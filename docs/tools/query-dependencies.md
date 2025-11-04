# query_dependencies Tool - Detailed Documentation

## Overview

The `query_dependencies` tool provides read-only dependency queries with flexible filtering support. It enables analysis of task relationships through bidirectional dependency filtering (incoming, outgoing, or all), dependency type filtering (BLOCKS, IS_BLOCKED_BY, RELATES_TO), and optional task enrichment for context-aware analysis.

**Key Feature (v2.0+):** Consolidated read-only tool for dependency analysis with counts breakdown and applied filter reporting, supporting direction and type filtering with optional task information enrichment.

**Resource**: `task-orchestrator://docs/tools/query-dependencies`

## Key Concepts

### Dependency Relationships

Dependencies represent relationships between tasks in four distinct ways:

- **BLOCKS** - Task A blocks Task B (A must complete before B can proceed)
- **IS_BLOCKED_BY** - Task A is blocked by Task B (B must complete before A can proceed)
- **RELATES_TO** - Task A relates to Task B (informational link, no completion requirement)
- **all** - Query all dependency types (default when no type filter applied)

### Dependency Directions

Queries return dependencies based on direction perspective:

- **incoming** - Dependencies TO this task (tasks that block this task)
  - "Show tasks blocking this task"
  - "What must complete before this task?"
  - Result: Tasks in `fromTaskId` position

- **outgoing** - Dependencies FROM this task (tasks blocked by this task)
  - "Show tasks blocked by this task"
  - "What tasks depend on this task completing?"
  - Result: Tasks in `toTaskId` position

- **all** - Both incoming and outgoing (default)
  - "Show all dependencies for this task"
  - Result: Organized into `incoming` and `outgoing` arrays

### Blocking vs Blocked

- **Blocking Task** - A task that must be completed before another task can proceed
- **Blocked Task** - A task waiting for blocking tasks to complete
- **Blocker Count** - Number of incomplete tasks blocking a specific task (see `get_blocked_tasks`)

### Counts Breakdown

Response includes detailed counts for dependency analysis:

```json
"counts": {
  "total": 5,           // Total dependencies (after filtering)
  "incoming": 2,        // Dependencies TO this task
  "outgoing": 3,        // Dependencies FROM this task
  "byType": {
    "BLOCKS": 3,
    "IS_BLOCKED_BY": 1,
    "RELATES_TO": 1
  }
}
```

**Note**: When `direction="all"`, counts show breakdown across both directions. When `direction="incoming"` or `direction="outgoing"`, only matching dependencies are counted.

## Parameter Reference

### Required Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `taskId` | UUID | Task to query dependencies for |

### Optional Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `direction` | enum | `all` | Filter direction: `incoming`, `outgoing`, `all` |
| `type` | enum | `all` | Filter type: `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO`, `all` |
| `includeTaskInfo` | boolean | `false` | Include task details (title, status, priority) for related tasks |

### Direction Options

- **incoming** - Tasks that block this task (dependencies TO this task)
- **outgoing** - Tasks blocked by this task (dependencies FROM this task)
- **all** - Both incoming and outgoing dependencies

### Type Options

- **BLOCKS** - Task A blocks Task B
- **IS_BLOCKED_BY** - Task A is blocked by Task B
- **RELATES_TO** - Informational relationship
- **all** - All dependency types (no type filtering)

---

## Quick Start

### Basic Pattern: Query All Dependencies

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Returns**: All dependencies (incoming and outgoing) for the task, organized by direction.

**Response**:

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "dependencies": {
      "incoming": [
        {
          "id": "dep-uuid-1",
          "fromTaskId": "661e8511-f30c-41d4-a716-557788990000",
          "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
          "type": "BLOCKS",
          "createdAt": "2025-10-24T10:00:00Z"
        }
      ],
      "outgoing": [
        {
          "id": "dep-uuid-2",
          "fromTaskId": "550e8400-e29b-41d4-a716-446655440000",
          "toTaskId": "772f9622-g41d-52e5-b827-668899101111",
          "type": "BLOCKS",
          "createdAt": "2025-10-24T10:15:00Z"
        }
      ]
    },
    "counts": {
      "total": 2,
      "incoming": 1,
      "outgoing": 1,
      "byType": {
        "BLOCKS": 2,
        "IS_BLOCKED_BY": 0,
        "RELATES_TO": 0
      }
    },
    "filters": {
      "direction": "all",
      "type": "all",
      "includeTaskInfo": false
    }
  }
}
```

**Token Cost**: ~300-400 tokens

### Pattern: Query Incoming Dependencies Only

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming"
}
```

**Returns**: Array of dependencies TO this task (tasks that block it)

**Response**:

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "dependencies": [
      {
        "id": "dep-uuid-1",
        "fromTaskId": "661e8511-f30c-41d4-a716-557788990000",
        "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "BLOCKS",
        "createdAt": "2025-10-24T10:00:00Z"
      }
    ],
    "counts": {
      "total": 1,
      "incoming": 1,
      "outgoing": 0,
      "byType": {
        "BLOCKS": 1,
        "IS_BLOCKED_BY": 0,
        "RELATES_TO": 0
      }
    },
    "filters": {
      "direction": "incoming",
      "type": "all",
      "includeTaskInfo": false
    }
  }
}
```

### Pattern: Query with Type Filter

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "BLOCKS"
}
```

**Returns**: Only BLOCKS dependencies (both incoming and outgoing)

### Pattern: Query with Task Info

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming",
  "includeTaskInfo": true
}
```

**Response with enriched task details**:

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "550e8400-e29b-41d4-a716-446655440000",
    "dependencies": [
      {
        "id": "dep-uuid-1",
        "fromTaskId": "661e8511-f30c-41d4-a716-557788990000",
        "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
        "type": "BLOCKS",
        "createdAt": "2025-10-24T10:00:00Z",
        "relatedTask": {
          "id": "661e8511-f30c-41d4-a716-557788990000",
          "title": "Create database schema for users",
          "status": "in-progress",
          "priority": "high"
        }
      }
    ],
    "counts": {
      "total": 1,
      "incoming": 1,
      "outgoing": 0,
      "byType": {
        "BLOCKS": 1,
        "IS_BLOCKED_BY": 0,
        "RELATES_TO": 0
      }
    },
    "filters": {
      "direction": "incoming",
      "type": "all",
      "includeTaskInfo": true
    }
  }
}
```

**Token Cost**: ~400-500 tokens (additional 100-150 tokens for task info)

---

## Advanced Usage

### Direction Filtering Patterns

#### Pattern 1: Analyze Incoming Dependencies (Blockers)

Find all tasks that block this task:

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming",
  "includeTaskInfo": true
}
```

**Use cases**:
- "What's preventing this task from starting?"
- "Which tasks must I wait for?"
- Identifying critical path dependencies
- Understanding task prerequisites

#### Pattern 2: Analyze Outgoing Dependencies (Dependents)

Find all tasks blocked by this task:

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "outgoing",
  "includeTaskInfo": true
}
```

**Use cases**:
- "What tasks depend on this task completing?"
- "What's the impact of this task?"
- Understanding downstream consequences
- Analyzing task ripple effects

#### Pattern 3: Complete Dependency Graph

See all dependencies in both directions:

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "all",
  "includeTaskInfo": true
}
```

**Use cases**:
- Full impact analysis
- Understanding task role in workflow
- Comprehensive dependency visualization
- Dependency chain analysis

### Type Filtering Patterns

#### Pattern 1: Find Blocking Dependencies

Query only BLOCKS relationships (hard dependencies):

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "BLOCKS",
  "includeTaskInfo": true
}
```

**Interpretation**:
- Hard blocking relationships
- Critical path dependencies
- Tasks that MUST be completed first

#### Pattern 2: Find Blocked-By Dependencies

Query only IS_BLOCKED_BY relationships:

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "IS_BLOCKED_BY"
}
```

**Note**: IS_BLOCKED_BY is semantically identical to BLOCKS but with roles reversed.

#### Pattern 3: Find Related Tasks

Query only RELATES_TO relationships (informational):

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "RELATES_TO"
}
```

**Use cases**:
- Informational relationships
- Tasks that should be completed together
- Related but not blocking dependencies
- Documentation references

### Combined Filtering Patterns

#### Pattern: Incoming Blocking Dependencies

Find tasks that block this task (with details):

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming",
  "type": "BLOCKS",
  "includeTaskInfo": true
}
```

**Result**: Only incoming BLOCKS dependencies with task details

**Use case**: "What hard blockers prevent this task from starting?"

#### Pattern: Outgoing Related Tasks

Find tasks that this task relates to:

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "outgoing",
  "type": "RELATES_TO"
}
```

**Result**: Only outgoing RELATES_TO dependencies

**Use case**: "Which tasks should be done in parallel with this task?"

### Task Info Enrichment Trade-offs

#### Without includeTaskInfo (Token Efficient)

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming"
}
```

**Returns**: Dependency IDs and type only
**Token Cost**: ~300-400 tokens
**Use when**: Quick lookup, dependency ID is sufficient, building custom analysis

#### With includeTaskInfo (Context Rich)

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "incoming",
  "includeTaskInfo": true
}
```

**Returns**: Includes task title, status, priority
**Token Cost**: ~400-500 tokens (additional 100-150 tokens)
**Use when**: Need context, building human-readable reports, status-aware analysis

**Recommendation**: Omit task info by default, add it only when context is needed. The additional 100-150 tokens is minimal but saves processing steps.

### Counts Breakdown Interpretation

Understanding the counts structure:

```json
"counts": {
  "total": 5,           // All dependencies after filters
  "incoming": 2,        // Dependencies TO this task
  "outgoing": 3,        // Dependencies FROM this task
  "byType": {
    "BLOCKS": 3,
    "IS_BLOCKED_BY": 1,
    "RELATES_TO": 1
  }
}
```

**When direction="all"**:
- `incoming` + `outgoing` = `total` (always true)
- Sum of `byType` values = `total`

**When direction="incoming" or "outgoing"**:
- Only matching direction has count > 0
- The other direction is 0
- Sum of `byType` values = `total`

**When type="BLOCKS"**:
- Only BLOCKS count in byType is non-zero
- `byType.BLOCKS` = `total`
- Other byType counts are 0

---

## Response Format Details

### Success Response Structure

```json
{
  "success": true,
  "message": "Dependencies retrieved successfully",
  "data": {
    "taskId": "UUID",
    "dependencies": { /* ... */ },
    "counts": { /* ... */ },
    "filters": { /* ... */ }
  }
}
```

### Direction="all" Response Structure

When querying all directions, dependencies are organized into incoming/outgoing:

```json
{
  "dependencies": {
    "incoming": [
      /* Incoming dependency objects */
    ],
    "outgoing": [
      /* Outgoing dependency objects */
    ]
  }
}
```

### Direction="incoming/outgoing" Response Structure

When filtering by direction, dependencies are returned as array:

```json
{
  "dependencies": [
    /* Array of dependency objects */
  ]
}
```

### Dependency Object Structure

Each dependency includes:

```json
{
  "id": "dep-uuid-1",
  "fromTaskId": "661e8511-f30c-41d4-a716-557788990000",
  "toTaskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "BLOCKS",
  "createdAt": "2025-10-24T10:00:00Z",
  "relatedTask": {
    "id": "661e8511-f30c-41d4-a716-557788990000",
    "title": "Create database schema for users",
    "status": "in-progress",
    "priority": "high"
  } // Only if includeTaskInfo=true
}
```

---

## Error Handling

| Error Code | Condition | Solution |
|-----------|-----------|----------|
| VALIDATION_ERROR | taskId missing | Provide taskId parameter |
| VALIDATION_ERROR | taskId invalid UUID format | Use valid UUID format: `550e8400-e29b-41d4-a716-446655440000` |
| VALIDATION_ERROR | Invalid direction | Use: `incoming`, `outgoing`, or `all` |
| VALIDATION_ERROR | Invalid type | Use: `BLOCKS`, `IS_BLOCKED_BY`, `RELATES_TO`, or `all` |
| RESOURCE_NOT_FOUND | Task not found | Verify taskId exists and is correct |
| DATABASE_ERROR | Database issue | Retry operation, contact support if persists |

### Error Response Format

```json
{
  "success": false,
  "message": "Human-readable error message",
  "error": {
    "code": "ERROR_CODE",
    "details": "Additional details if available"
  }
}
```

### Common Error Scenarios

**Scenario 1: Task Not Found**

```json
{
  "taskId": "00000000-0000-0000-0000-000000000000"
}
```

Response:

```json
{
  "success": false,
  "message": "Task not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "details": "No task exists with ID 00000000-0000-0000-0000-000000000000"
  }
}
```

**Scenario 2: Invalid Direction**

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "direction": "sideways"
}
```

Response:

```json
{
  "success": false,
  "message": "Invalid direction: sideways. Must be one of: incoming, outgoing, all",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Scenario 3: Invalid Type**

```json
{
  "taskId": "550e8400-e29b-41d4-a716-446655440000",
  "type": "DEPENDS_ON"
}
```

Response:

```json
{
  "success": false,
  "message": "Invalid type: DEPENDS_ON. Must be one of: BLOCKS, IS_BLOCKED_BY, RELATES_TO, all",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

---

## Integration Patterns

### Pattern 1: Dependency Analysis for Task Status

Check if task has blockers before planning:

```javascript
const result = await query_dependencies({
  taskId: "550e8400-e29b-41d4-a716-446655440000",
  direction: "incoming",
  type: "BLOCKS",
  includeTaskInfo: true
});

if (result.data.counts.total > 0) {
  console.log("Task is blocked by:");
  result.data.dependencies.forEach(dep => {
    console.log(`- ${dep.relatedTask.title} (${dep.relatedTask.status})`);
  });
} else {
  console.log("Task is unblocked - can start immediately");
}
```

### Pattern 2: Impact Analysis

Understand impact of completing a task:

```javascript
const blockerResult = await query_dependencies({
  taskId: "550e8400-e29b-41d4-a716-446655440000",
  direction: "outgoing",
  includeTaskInfo: true
});

const dependentCount = blockerResult.data.counts.outgoing;

if (dependentCount > 0) {
  console.log(`Completing this task will unblock ${dependentCount} task(s):`);
  blockerResult.data.dependencies.forEach(dep => {
    console.log(`- ${dep.relatedTask.title}`);
  });
}
```

### Pattern 3: Dependency Chain Visualization

Build visualization of dependency chains:

```javascript
async function getDependencyChain(taskId, visited = new Set()) {
  if (visited.has(taskId)) {
    return { title: "...[cycle detected]", dependencies: [] };
  }
  visited.add(taskId);

  const task = await get_task({ id: taskId });
  const deps = await query_dependencies({
    taskId,
    direction: "incoming",
    includeTaskInfo: true
  });

  return {
    title: task.data.title,
    blockers: deps.data.dependencies.map(d => ({
      title: d.relatedTask.title,
      status: d.relatedTask.status
    }))
  };
}

// Usage
const chain = await getDependencyChain("550e8400-e29b-41d4-a716-446655440000");
console.log(JSON.stringify(chain, null, 2));
```

### Pattern 4: Integration with get_blocked_tasks

Combine query_dependencies with get_blocked_tasks for comprehensive analysis:

```javascript
// Step 1: Get overview of blocked tasks
const blocked = await get_blocked_tasks({
  includeTaskDetails: true
});

// Step 2: For high-priority blocked tasks, get detailed dependencies
for (const blockedTask of blocked.data.blockedTasks.filter(t => t.priority === "high")) {
  const deps = await query_dependencies({
    taskId: blockedTask.taskId,
    direction: "incoming",
    type: "BLOCKS",
    includeTaskInfo: true
  });

  console.log(`\n${blockedTask.title}:`);
  console.log(`Blocked by ${deps.data.counts.total} task(s):`);
  deps.data.dependencies.forEach(d => {
    console.log(`- ${d.relatedTask.title} (${d.relatedTask.status})`);
  });
}
```

### Pattern 5: Status Progression with Dependency Check

Check dependencies before marking task complete:

```javascript
async function canCompleteTask(taskId) {
  // Check for incoming blocking dependencies
  const incomingDeps = await query_dependencies({
    taskId,
    direction: "incoming",
    type: "BLOCKS"
  });

  if (incomingDeps.data.counts.total > 0) {
    return {
      canComplete: false,
      reason: `Task has ${incomingDeps.data.counts.total} blocking dependencies`
    };
  }

  // Check with status progression tool for other requirements
  const progression = await get_next_status({
    containerId: taskId,
    containerType: "task"
  });

  return {
    canComplete: progression.data.status === "Ready",
    reason: progression.data.message
  };
}
```

---

## Use Cases

### Use Case 1: Blocker Analysis for Standups

**Scenario**: Daily standup - identify what's blocking the team.

**Steps**:

1. Query dependencies for high-priority tasks:

```json
{
  "taskId": "priority-task-uuid",
  "direction": "incoming",
  "type": "BLOCKS",
  "includeTaskInfo": true
}
```

2. For each blocked task, identify blocker status
3. Focus resolution on blockers in progress vs pending
4. Report to team

**Result**: Team knows exactly what's blocking progress.

### Use Case 2: Sprint Planning Dependency Analysis

**Scenario**: Planning sprint - understand dependencies between tasks.

**Steps**:

1. For each planned task, query outgoing dependencies:

```json
{
  "taskId": "planned-task-uuid",
  "direction": "outgoing",
  "includeTaskInfo": true
}
```

2. Identify tasks that block other sprints
3. Identify external dependencies
4. Plan work order based on dependency graph

**Result**: Sprint sequenced optimally around dependencies.

### Use Case 3: Impact Assessment

**Scenario**: Evaluating impact of changing/cancelling a task.

**Steps**:

1. Query all dependencies for task:

```json
{
  "taskId": "target-task-uuid",
  "includeTaskInfo": true
}
```

2. Analyze incoming: what would need to be rescheduled?
3. Analyze outgoing: what tasks would be unblocked?
4. Calculate impact and communicate to stakeholders

**Result**: Well-informed decision on task changes.

### Use Case 4: Dependency Cycle Detection

**Scenario**: Ensuring no circular dependencies exist.

**Steps**:

1. For each task, recursively trace dependencies
2. Use visited set to detect cycles
3. Query dependencies at each level:

```json
{
  "taskId": "current-task-uuid",
  "direction": "outgoing"
}
```

4. Report any cycles found

**Result**: Validation that dependency graph is acyclic.

### Use Case 5: Critical Path Analysis

**Scenario**: Finding longest dependency chain (critical path).

**Steps**:

1. Start with tasks that have no blockers
2. Recursively query outgoing dependencies
3. Track depth of each path:

```json
{
  "taskId": "chain-task-uuid",
  "direction": "outgoing",
  "includeTaskInfo": true
}
```

4. Calculate longest path
5. Identify critical tasks that affect overall timeline

**Result**: Understanding of critical path for project completion.

---

## Best Practices

### DO

✅ **Always include taskId** - Required parameter, no queries without it

✅ **Use direction filters** - Narrow results to incoming or outgoing when analyzing specific impact

✅ **Add task info when needed for context** - Small token cost for significant value

✅ **Check counts before analysis** - Quick check if dependencies exist: `counts.total > 0`

✅ **Use with get_next_status** - Combine dependency checks with status progression validation

✅ **Cache results in analysis loop** - Don't query same task multiple times in quick succession

✅ **Use type filters for hard dependencies** - Filter to BLOCKS when analyzing critical paths

✅ **Combine with manage_dependency** - Use query to verify before creating/deleting dependencies

✅ **Check task existence first** - Verify task exists before dependency query (prevents not found errors)

✅ **Interpret counts carefully** - Remember incoming + outgoing = total (when direction="all")

### DON'T

❌ **Don't assume task exists** - Always validate taskId before querying

❌ **Don't ignore filter settings** - Remember default is direction="all", which returns organized structure

❌ **Don't overuse includeTaskInfo** - It's optional; only add when context needed

❌ **Don't confuse direction values** - incoming ≠ outgoing (different perspectives)

❌ **Don't ignore RELATES_TO relationships** - May be important for task sequencing

❌ **Don't create circular dependencies** - Use query_dependencies to validate before creating

❌ **Don't assume all dependencies are blocking** - RELATES_TO relationships don't block

❌ **Don't query without filtering** - When performance matters, use type or direction filters

❌ **Don't ignore empty results** - Task with no dependencies is valid (independent task)

❌ **Don't hardcode dependency types** - Use consistent type values (BLOCKS, IS_BLOCKED_BY, RELATES_TO)

---

## Performance Considerations

### Token Usage by Configuration

| Configuration | Tokens | Use Case |
|--------------|--------|----------|
| direction=incoming, type=all, no task info | 250-300 | Quick incoming dependency check |
| direction=incoming, type=BLOCKS, no task info | 250-300 | Critical blocker check |
| direction=all, type=all, no task info | 300-400 | Full dependency graph |
| direction=all, type=all, includeTaskInfo=true | 400-500 | Context-rich analysis |
| With many dependencies (50+) | 500-800 | Large dependency set |

### Optimization Strategies

1. **Filter first**: Use direction and type filters to reduce result set
2. **Minimal details**: Omit includeTaskInfo by default
3. **Batch analysis**: Group dependency queries in single operation
4. **Cache dependencies**: In loops, cache results rather than re-querying
5. **Pre-filter**: Use get_blocked_tasks first if analyzing blockers

---

## Dependency Type Reference

### BLOCKS

**Direction**: A → B (A blocks B)
**Semantics**: A must be completed before B can proceed
**Impact**: Hard blocking relationship
**Example**: "Create database schema" BLOCKS "Implement API endpoints"

### IS_BLOCKED_BY

**Direction**: A ← B (A is blocked by B, equivalent to B BLOCKS A)
**Semantics**: B must be completed before A can proceed
**Impact**: Hard blocking relationship
**Example**: "Implement API endpoints" IS_BLOCKED_BY "Create database schema"

**Note**: Semantically identical to BLOCKS but with roles reversed. Both represent hard dependencies.

### RELATES_TO

**Direction**: A ↔ B (A relates to B)
**Semantics**: Informational relationship, no blocking
**Impact**: Should be coordinated but doesn't block
**Example**: "User registration UI" RELATES_TO "User profile page"

---

## Integration with Related Tools

### With manage_dependency

Use `query_dependencies` to verify dependencies before creating/deleting:

```javascript
// Verify dependency doesn't exist
const existing = await query_dependencies({
  taskId: "task-a-uuid",
  direction: "outgoing",
  type: "BLOCKS"
});

if (!existing.data.dependencies.find(d => d.toTaskId === "task-b-uuid")) {
  // Safe to create
  await manage_dependency({
    operation: "create",
    fromTaskId: "task-a-uuid",
    toTaskId: "task-b-uuid",
    type: "BLOCKS"
  });
}
```

### With Task Orchestration Skill

The Dependency Analysis Skill uses query_dependencies to answer:
- "What's blocking this task?"
- "What does this task block?"
- "Show task dependencies"

### With get_blocked_tasks

Complementary tool that uses dependency data:

```javascript
// get_blocked_tasks shows overall blocked status
const blocked = await get_blocked_tasks({});

// query_dependencies shows detailed dependency structure
const deps = await query_dependencies({
  taskId: "blocked-task-uuid",
  direction: "incoming"
});
```

### With Status Progression Skill

Use query_dependencies to validate task readiness:

```javascript
const deps = await query_dependencies({
  taskId: "task-uuid",
  direction: "incoming",
  type: "BLOCKS"
});

const isBlocked = deps.data.counts.total > 0;

// Then use get_next_status for full status progression check
const progression = await get_next_status({
  containerId: "task-uuid",
  containerType: "task"
});
```

---

## Common Patterns and Examples

### Pattern: Quick Unblock Check

Is this task ready to start?

```javascript
async function isTaskReady(taskId) {
  const deps = await query_dependencies({
    taskId,
    direction: "incoming",
    type: "BLOCKS"
  });
  return deps.data.counts.total === 0;
}

// Usage
if (await isTaskReady("task-uuid")) {
  console.log("Task can start immediately");
} else {
  console.log("Task has blockers");
}
```

### Pattern: Dependency Summary

Get human-readable dependency summary:

```javascript
async function getDependencySummary(taskId) {
  const result = await query_dependencies({
    taskId,
    includeTaskInfo: true
  });

  return {
    incoming: result.data.dependencies.incoming.map(d => d.relatedTask.title),
    outgoing: result.data.dependencies.outgoing.map(d => d.relatedTask.title),
    totalDependencies: result.data.counts.total
  };
}
```

### Pattern: Find All Blockers

Get all tasks that block this task:

```javascript
async function getBlockers(taskId) {
  const result = await query_dependencies({
    taskId,
    direction: "incoming",
    type: "BLOCKS",
    includeTaskInfo: true
  });

  return result.data.dependencies.map(d => ({
    id: d.fromTaskId,
    title: d.relatedTask.title,
    status: d.relatedTask.status
  }));
}
```

### Pattern: Find Dependent Tasks

Get all tasks that depend on this task:

```javascript
async function getDependents(taskId) {
  const result = await query_dependencies({
    taskId,
    direction: "outgoing",
    includeTaskInfo: true
  });

  return result.data.dependencies.map(d => ({
    id: d.toTaskId,
    title: d.relatedTask.title,
    type: d.type
  }));
}
```

---

## Error Cases and Recovery

### Task Not Found

**Cause**: Provided taskId doesn't exist in system

**Prevention**:
```javascript
// Verify task exists first
const taskExists = await query_container({
  operation: "get",
  containerType: "task",
  id: "task-uuid"
});

if (!taskExists.success) {
  console.error("Task not found");
  return;
}

// Now safe to query dependencies
const deps = await query_dependencies({ taskId: "task-uuid" });
```

### Invalid UUID Format

**Cause**: taskId is not valid UUID format

**Prevention**:
```javascript
function isValidUUID(uuid) {
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  return uuidRegex.test(uuid);
}

if (!isValidUUID(taskId)) {
  console.error("Invalid UUID format");
  return;
}
```

### Invalid Direction/Type

**Cause**: Misspelled or unknown direction/type value

**Prevention**:
```javascript
const VALID_DIRECTIONS = ["incoming", "outgoing", "all"];
const VALID_TYPES = ["BLOCKS", "IS_BLOCKED_BY", "RELATES_TO", "all"];

if (!VALID_DIRECTIONS.includes(direction)) {
  console.error(`Invalid direction: ${direction}`);
  return;
}

if (!VALID_TYPES.includes(type)) {
  console.error(`Invalid type: ${type}`);
  return;
}
```

---

## References

### Source Code

- **Tool Implementation**: `src/main/kotlin/io/github/jpicklyk/mcptask/application/tools/dependency/QueryDependenciesTool.kt`
- **Tool Tests**: `src/test/kotlin/io/github/jpicklyk/mcptask/application/tools/dependency/QueryDependenciesToolTest.kt`
- **Domain Model**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/model/Dependency.kt`
- **Dependency Repository**: `src/main/kotlin/io/github/jpicklyk/mcptask/domain/repository/DependencyRepository.kt`

### Related Documentation

- **[manage_dependency Tool](manage-dependency.md)** - Create/delete dependencies
- **[get_blocked_tasks Tool](get-blocked-tasks.md)** - Identify blocked tasks
- **[get_next_task Tool](get-next-task.md)** - Get next unblocked task
- **[Dependency Analysis Skill]** - Claude Code skill for dependency queries
- **[Task Orchestration Skill]** - Comprehensive task management
- **[API Reference](../api-reference.md)** - Complete tool documentation
- **[Quick Start Guide](../quick-start.md)** - Getting started

### Example Dataset

All examples use consistent IDs:

- **Project**: `b160fbdb-07e4-42d7-8c61-8deac7d2fc17` (MCP Task Orchestrator)
- **Feature**: `f8a3c1e9-4b2d-4f7e-9a1c-5d6e7f8a9b0c` (Container Management API)
- **Task A**: `550e8400-e29b-41d4-a716-446655440000` (API endpoints)
- **Task B**: `661e8511-f30c-41d4-a716-557788990000` (Database schema)
- **Task C**: `772f9622-g41d-52e5-b827-668899101111` (User UI)

---

## Version History

- **v2.0.0** (2025-10-24): Initial comprehensive documentation for query_dependencies tool
- **v2.0.0-beta** (2025-10-19): query_dependencies tool release as part of v2.0 consolidation

---

## See Also

- [API Reference](../api-reference.md) - Complete tool documentation
- [manage_dependency Tool](manage-dependency.md) - Dependency creation/deletion
- [get_blocked_tasks Tool](get-blocked-tasks.md) - Blocked task identification
- [Quick Start Guide](../quick-start.md) - Common workflows
- [Task Dependencies Guide](../guidelines/task-dependencies.md) - Dependency concepts and best practices
