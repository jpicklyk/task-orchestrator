# get_blocked_tasks Tool - Detailed Documentation

## Overview

Identifies tasks that are currently blocked by incomplete dependencies. Essential for workflow management, bottleneck identification, and team coordination.

**Resource**: `task-orchestrator://docs/tools/get-blocked-tasks`

## Key Concepts

### What Makes a Task Blocked?

A task is considered blocked when ALL of these conditions are met:
1. **Task is Active**: Status is `pending` or `in-progress`
2. **Has Dependencies**: Has incoming dependencies (other tasks block it)
3. **Blocker Incomplete**: At least one blocking task is NOT `completed` or `cancelled`

### Blocking vs Blocked
- **Blocking Task**: A task that must be completed before another task can proceed
- **Blocked Task**: A task waiting for blocking tasks to complete
- **Blocker Count**: Number of incomplete tasks blocking a specific task

### Use Cases

**Daily Standup**:
- "What tasks are blocked today?"
- "Which team members are blocked?"
- "What dependencies are holding us back?"

**Sprint Planning**:
- Identify tasks that can't start yet
- Understand dependency chains
- Plan work order based on blockers

**Bottleneck Analysis**:
- Find tasks that block multiple others
- Identify critical path dependencies
- Prioritize blocker resolution

**Team Coordination**:
- Know which tasks need other teams' work
- Coordinate handoffs between teams
- Track cross-team dependencies

## Parameter Reference

### Optional Parameters

All parameters are optional - no parameters returns all blocked tasks across all projects/features.

- **projectId** (UUID): Filter blocked tasks to specific project
- **featureId** (UUID): Filter blocked tasks to specific feature
- **includeTaskDetails** (boolean, default: false): Include full task metadata

## Usage Patterns

### Pattern 1: Find All Blocked Tasks
Get complete list of blocked tasks across entire system.

```json
{}
```

**Returns**: All blocked tasks regardless of project/feature
**When to use**:
- Daily standup overview
- System-wide bottleneck analysis
- Understanding overall workflow health

**Response size**: ~200-500 tokens per blocked task

### Pattern 2: Project-Specific Blocked Tasks
Focus on blockers within a specific project.

```json
{
  "projectId": "772f9622-g41d-52e5-b827-668899101111"
}
```

**When to use**:
- Project standup meetings
- Project-specific workflow analysis
- Project manager reviews

### Pattern 3: Feature-Specific Blocked Tasks
Identify blockers within a feature scope.

```json
{
  "featureId": "661e8511-f30c-41d4-a716-557788990000"
}
```

**When to use**:
- Feature team coordination
- Feature implementation planning
- Understanding feature completion blockers

### Pattern 4: Detailed Blocked Task Analysis
Get complete task metadata for deep analysis.

```json
{
  "includeTaskDetails": true
}
```

**Returns additional fields**:
- summary
- featureId
- tags

**Response size**: ~400-800 tokens per blocked task
**When to use**:
- Deep bottleneck analysis
- Understanding task context
- Detailed reporting

### Pattern 5: Feature Blockers with Details
Combine feature filter with detailed information.

```json
{
  "featureId": "661e8511-f30c-41d4-a716-557788990000",
  "includeTaskDetails": true
}
```

**When to use**: Feature retrospectives, detailed feature planning

## Response Structure

### Minimal Response (default)
```json
{
  "success": true,
  "message": "Found 3 blocked task(s)",
  "data": {
    "blockedTasks": [
      {
        "taskId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Implement API authentication endpoints",
        "status": "pending",
        "priority": "high",
        "complexity": 6,
        "blockedBy": [
          {
            "taskId": "661e8511-f30c-41d4-a716-557788990000",
            "title": "Create database schema for users",
            "status": "in-progress",
            "priority": "high"
          }
        ],
        "blockerCount": 1
      },
      {
        "taskId": "772f9622-g41d-52e5-b827-668899101111",
        "title": "Build user registration UI",
        "status": "pending",
        "priority": "medium",
        "complexity": 4,
        "blockedBy": [
          {
            "taskId": "550e8400-e29b-41d4-a716-446655440000",
            "title": "Implement API authentication endpoints",
            "status": "pending",
            "priority": "high"
          }
        ],
        "blockerCount": 1
      }
    ],
    "totalBlocked": 2
  }
}
```

### Detailed Response (includeTaskDetails=true)
```json
{
  "success": true,
  "message": "Found 1 blocked task(s)",
  "data": {
    "blockedTasks": [
      {
        "taskId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Implement API authentication endpoints",
        "status": "pending",
        "priority": "high",
        "complexity": 6,
        "summary": "Create REST endpoints for login, logout, token refresh",
        "featureId": "661e8511-f30c-41d4-a716-557788990000",
        "tags": ["backend", "api", "authentication"],
        "blockedBy": [
          {
            "taskId": "883g0733-h52e-63f6-c938-779910212222",
            "title": "Create database schema for users",
            "status": "in-progress",
            "priority": "high",
            "complexity": 4,
            "featureId": "661e8511-f30c-41d4-a716-557788990000"
          }
        ],
        "blockerCount": 1
      }
    ],
    "totalBlocked": 1
  }
}
```

### Empty Result
```json
{
  "success": true,
  "message": "Found 0 blocked task(s)",
  "data": {
    "blockedTasks": [],
    "totalBlocked": 0
  }
}
```

**Interpretation**: No active tasks are waiting on dependencies - workflow is flowing smoothly!

## Analysis Patterns

### Pattern: Identify Bottleneck Tasks
Find tasks that block multiple other tasks.

```javascript
const result = await get_blocked_tasks({});

// Count how often each blocker appears
const blockerFrequency = new Map();

result.data.blockedTasks.forEach(blockedTask => {
  blockedTask.blockedBy.forEach(blocker => {
    const count = blockerFrequency.get(blocker.taskId) || 0;
    blockerFrequency.set(blocker.taskId, count + 1);
  });
});

// Find top bottlenecks
const bottlenecks = Array.from(blockerFrequency.entries())
  .sort((a, b) => b[1] - a[1])
  .slice(0, 5);

console.log("Top 5 bottleneck tasks:", bottlenecks);
// Output: [["task-uuid-1", 5], ["task-uuid-2", 3], ...]
```

**Use case**: Prioritize which blocker tasks to complete first

### Pattern: Dependency Chain Analysis
Understand chains of dependencies.

```javascript
const blockedTasks = await get_blocked_tasks({});

// Build dependency chain
const chains = blockedTasks.data.blockedTasks.map(task => {
  return {
    blocked: task.title,
    blockers: task.blockedBy.map(b => b.title),
    depth: task.blockerCount
  };
});

// Find longest chains
const longestChains = chains.sort((a, b) => b.depth - a.depth);
console.log("Longest dependency chains:", longestChains.slice(0, 5));
```

**Use case**: Identify complex dependency paths

### Pattern: Team Coordination
Find which teams are blocking others.

```javascript
const blockedTasks = await get_blocked_tasks({ includeTaskDetails: true });

// Group by team (assuming teams are in tags)
const teamBlocks = new Map();

blockedTasks.data.blockedTasks.forEach(task => {
  const blockedTeam = task.tags.find(t => t.startsWith("team-")) || "unknown";

  task.blockedBy.forEach(blocker => {
    const blockerTeam = blocker.tags?.find(t => t.startsWith("team-")) || "unknown";

    const key = `${blockerTeam} → ${blockedTeam}`;
    const count = teamBlocks.get(key) || 0;
    teamBlocks.set(key, count + 1);
  });
});

console.log("Cross-team blocking relationships:", Array.from(teamBlocks.entries()));
```

**Use case**: Coordinate handoffs between teams

### Pattern: Priority Impact Analysis
Assess impact of high-priority blocked tasks.

```javascript
const blockedTasks = await get_blocked_tasks({});

const highPriorityBlocked = blockedTasks.data.blockedTasks
  .filter(task => task.priority === "high")
  .map(task => ({
    title: task.title,
    blockerCount: task.blockerCount,
    blockers: task.blockedBy.map(b => ({
      title: b.title,
      status: b.status,
      priority: b.priority
    }))
  }));

console.log(`${highPriorityBlocked.length} high-priority tasks are blocked`);
```

**Use case**: Escalate blocker resolution for critical work

## Integration Workflows

### Workflow 1: Daily Standup Workflow
```javascript
// Step 1: Get blocked tasks
const blocked = await get_blocked_tasks({});

console.log(`\n=== Daily Standup: Blocked Tasks ===`);
console.log(`Total blocked: ${blocked.data.totalBlocked}`);

// Step 2: Report by priority
const byPriority = {
  high: blocked.data.blockedTasks.filter(t => t.priority === "high"),
  medium: blocked.data.blockedTasks.filter(t => t.priority === "medium"),
  low: blocked.data.blockedTasks.filter(t => t.priority === "low")
};

console.log(`High priority: ${byPriority.high.length}`);
console.log(`Medium priority: ${byPriority.medium.length}`);
console.log(`Low priority: ${byPriority.low.length}`);

// Step 3: Focus on high-priority blockers
byPriority.high.forEach(task => {
  console.log(`\n${task.title} blocked by:`);
  task.blockedBy.forEach(blocker => {
    console.log(`  - ${blocker.title} (${blocker.status})`);
  });
});
```

### Workflow 2: Unblock Tasks as Work Completes
```javascript
// Step 1: Complete a blocker task
await set_status({
  id: "blocker-task-id",
  status: "completed"
});

// Step 2: Check what this unblocked
const stillBlocked = await get_blocked_tasks({});

// Step 3: Find newly unblocked tasks
const previouslyBlocked = ["task-1", "task-2", "task-3"];
const currentlyBlocked = stillBlocked.data.blockedTasks.map(t => t.taskId);
const nowUnblocked = previouslyBlocked.filter(id => !currentlyBlocked.includes(id));

console.log(`Completing blocker unblocked ${nowUnblocked.length} tasks!`);

// Step 4: Get next task to work on
const nextTask = await get_next_task({});
```

### Workflow 3: Sprint Planning
```javascript
// Step 1: Get blocked tasks in sprint
const blocked = await get_blocked_tasks({
  projectId: "sprint-project-id",
  includeTaskDetails: true
});

// Step 2: Categorize blockers
const internal = blocked.data.blockedTasks.filter(task =>
  task.blockedBy.every(b => b.featureId === task.featureId)
);

const external = blocked.data.blockedTasks.filter(task =>
  task.blockedBy.some(b => b.featureId !== task.featureId)
);

console.log(`Internal dependencies: ${internal.length}`);
console.log(`External dependencies: ${external.length}`);

// Step 3: Estimate when blockers will complete
// (Could integrate with time estimates on blocker tasks)
```

### Workflow 4: Feature Completion Check
```javascript
// Check if feature can be completed
const featureId = "661e8511-f30c-41d4-a716-557788990000";

// Step 1: Get feature tasks
const featureTasks = await get_feature_tasks({ featureId });

// Step 2: Check for blocked tasks in feature
const blocked = await get_blocked_tasks({ featureId });

// Step 3: Analyze completion readiness
if (blocked.data.totalBlocked === 0) {
  const allComplete = featureTasks.data.tasks.every(t => t.status === "completed");

  if (allComplete) {
    console.log("✅ Feature ready to mark complete!");
    await set_status({ id: featureId, status: "completed" });
  } else {
    const remaining = featureTasks.data.tasks.filter(t => t.status !== "completed");
    console.log(`⏳ ${remaining.length} unblocked tasks remaining`);
  }
} else {
  console.log(`⛔ ${blocked.data.totalBlocked} tasks still blocked`);
  blocked.data.blockedTasks.forEach(task => {
    console.log(`  - ${task.title} (${task.blockerCount} blockers)`);
  });
}
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Tokens per Task | Use Case |
|--------------|-----------------|----------|
| Default (no params) | ~200-300 | Quick overview |
| With projectId filter | ~200-300 | Project focus |
| With featureId filter | ~200-300 | Feature focus |
| includeTaskDetails=true | ~400-600 | Deep analysis |

### Optimization Strategies

1. **Use Filters**: Reduce scope with projectId/featureId filters
2. **Minimal Details First**: Get overview without details, then deep-dive on specific tasks
3. **Cache Results**: Store results during analysis session
4. **Batch Analysis**: Analyze multiple blockers at once rather than one-by-one

## Error Handling

### Project Not Found
```json
{
  "success": false,
  "message": "Project not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND"
  }
}
```

**Solution**: Verify projectId with `get_project` or `search_projects`

### Feature Not Found
```json
{
  "success": false,
  "message": "Feature not found",
  "error": {
    "code": "RESOURCE_NOT_FOUND"
  }
}
```

**Solution**: Verify featureId with `get_feature` or `search_features`

### Invalid UUID Format
```json
{
  "success": false,
  "message": "Invalid projectId format. Must be a valid UUID.",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Ensure IDs are valid UUID format

## Best Practices

1. **Daily Reviews**: Check blocked tasks daily to maintain workflow
2. **Prioritize Bottlenecks**: Focus on tasks that block multiple others
3. **Use Filters**: Start with project/feature scope, expand if needed
4. **Track Trends**: Monitor blocked task count over time
5. **Coordinate Teams**: Share blocker information across teams
6. **Minimize Details**: Use includeTaskDetails only when needed
7. **Pair with get_next_task**: After resolving blockers, find next work
8. **Document Blockers**: Update task summaries to explain blocking reasons

## Common Mistakes to Avoid

### ❌ Mistake 1: Ignoring External Blockers
```javascript
const blocked = await get_blocked_tasks({ featureId });
// ❌ Didn't check if blockers are in different features
```
**Problem**: May miss cross-feature dependencies

### ✅ Solution: Analyze Blocker Scope
```javascript
const blocked = await get_blocked_tasks({
  featureId,
  includeTaskDetails: true
});

const externalBlockers = blocked.data.blockedTasks.filter(task =>
  task.blockedBy.some(b => b.featureId !== task.featureId)
);
```

### ❌ Mistake 2: Not Tracking Blocker Status
```javascript
const blocked = await get_blocked_tasks({});
console.log(`${blocked.data.totalBlocked} tasks blocked`);
// ❌ Didn't check blocker status or progress
```

### ✅ Solution: Monitor Blocker Progress
```javascript
const blocked = await get_blocked_tasks({});

blocked.data.blockedTasks.forEach(task => {
  task.blockedBy.forEach(blocker => {
    if (blocker.status === "in-progress") {
      console.log(`✓ ${blocker.title} is actively being worked on`);
    } else {
      console.log(`⚠ ${blocker.title} is still ${blocker.status}`);
    }
  });
});
```

### ❌ Mistake 3: Overusing includeTaskDetails
```javascript
// Daily standup - checking 50 blocked tasks
const blocked = await get_blocked_tasks({ includeTaskDetails: true });
// ❌ Loading 30,000 tokens when 10,000 would suffice
```

### ✅ Solution: Use Details Selectively
```javascript
// Get overview first
const blocked = await get_blocked_tasks({});

// Then get details only for high-priority ones
const highPriorityIds = blocked.data.blockedTasks
  .filter(t => t.priority === "high")
  .map(t => t.taskId);

// Load full details for those specific tasks
for (const id of highPriorityIds) {
  const task = await get_task({ id, includeSections: true });
  // Analyze in detail
}
```

## Related Tools

- **get_next_task**: Find unblocked tasks to work on
- **get_task_dependencies**: Get detailed dependency information for specific task
- **search_tasks**: Find tasks by criteria (including status)
- **set_status**: Update task status to unblock dependent tasks
- **get_feature_tasks**: Get all tasks in a feature to check blockers
- **create_dependency**: Create new dependencies between tasks

## See Also

- Task Dependencies Guide: `task-orchestrator://guidelines/task-dependencies`
- Workflow Management: `task-orchestrator://guidelines/workflow-management`
- Bottleneck Analysis: `task-orchestrator://guidelines/bottleneck-analysis`
