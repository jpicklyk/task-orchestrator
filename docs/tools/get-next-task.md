# get_next_task Tool - Detailed Documentation

## Overview

Recommends the next task to work on based on intelligent filtering and prioritization. Automatically excludes blocked tasks and ranks by priority and complexity to suggest optimal work order.

**Resource**: `task-orchestrator://docs/tools/get-next-task`

## Key Concepts

### Smart Task Recommendation

The tool implements a sophisticated selection algorithm:
1. **Retrieves Active Tasks**: Gets all `pending` and `in-progress` tasks
2. **Filters Blocked Tasks**: Automatically excludes tasks with incomplete dependencies
3. **Prioritizes by Impact**: Sorts by priority (HIGH → MEDIUM → LOW)
4. **Optimizes for Quick Wins**: Within same priority, sorts by complexity (lower first)
5. **Returns Top Recommendations**: Provides best task(s) to work on now

### Quick Wins Philosophy

**Why complexity matters**: High-priority, low-complexity tasks provide maximum impact with minimum effort. These "quick wins" deliver value fast while maintaining momentum.

**Example Sorting**:
```
1. High priority, complexity 3 (BEST - quick win!)
2. High priority, complexity 5
3. High priority, complexity 8
4. Medium priority, complexity 2
5. Medium priority, complexity 7
6. Low priority, complexity 1
```

### Automatic Blocker Filtering

Tasks are automatically excluded if:
- Has incoming dependencies (other tasks block it)
- Any blocker is NOT completed or cancelled
- Conservative approach: If blocker can't be found, assume incomplete

## Parameter Reference

### Optional Parameters

All parameters are optional - sensible defaults provide instant value.

- **limit** (integer, 1-20, default: 1): Number of recommendations to return
- **projectId** (UUID): Filter to specific project
- **featureId** (UUID): Filter to specific feature
- **includeDetails** (boolean, default: false): Include summary, tags, featureId

### Parameter Combinations

**Quick recommendation** (default):
```json
{}
```
Returns: Single best task across entire system

**Multiple recommendations**:
```json
{
  "limit": 5
}
```
Returns: Top 5 tasks to choose from

**Project-focused**:
```json
{
  "projectId": "772f9622-g41d-52e5-b827-668899101111",
  "limit": 3
}
```
Returns: Top 3 tasks in specific project

**Feature-focused with details**:
```json
{
  "featureId": "661e8511-f30c-41d4-a716-557788990000",
  "includeDetails": true
}
```
Returns: Best task in feature with full context

## Usage Patterns

### Pattern 1: "What Should I Work On Now?"
Get instant recommendation without any parameters.

```json
{}
```

**Response**:
```json
{
  "success": true,
  "message": "Found 1 recommendation(s) from 12 unblocked task(s)",
  "data": {
    "recommendations": [
      {
        "taskId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Fix authentication token refresh bug",
        "status": "pending",
        "priority": "high",
        "complexity": 3
      }
    ],
    "totalCandidates": 12
  }
}
```

**When to use**:
- Starting your workday
- Finishing a task and ready for next
- Context switching between activities
- Need quick decision on priorities

### Pattern 2: "Give Me Options"
Get multiple recommendations to choose from.

```json
{
  "limit": 5,
  "includeDetails": true
}
```

**When to use**:
- Want to choose based on skillset
- Need variety for team assignment
- Prefer seeing multiple options
- Planning work for the day

### Pattern 3: Project-Focused Work
Focus recommendations on specific project.

```json
{
  "projectId": "772f9622-g41d-52e5-b827-668899101111",
  "limit": 3
}
```

**When to use**:
- Working on specific project
- Project has deadline
- Dedicated project focus time
- Project-based team assignment

### Pattern 4: Feature Completion Sprint
Get recommendations within a feature scope.

```json
{
  "featureId": "661e8511-f30c-41d4-a716-557788990000",
  "limit": 10
}
```

**When to use**:
- Pushing feature to completion
- Feature-focused sprint
- Feature has approaching deadline
- Want to see all remaining feature work

### Pattern 5: Detailed Task Selection
Get full context for informed decision-making.

```json
{
  "limit": 3,
  "includeDetails": true
}
```

**Returns additional fields**:
- summary (task description)
- featureId (parent feature)
- tags (categorization)

**When to use**:
- Need full context to choose
- Matching tasks to team skills
- Understanding task scope
- Detailed work planning

## Response Structure

### Minimal Response (default)
```json
{
  "success": true,
  "message": "Found 1 recommendation(s) from 8 unblocked task(s)",
  "data": {
    "recommendations": [
      {
        "taskId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Implement OAuth Google provider",
        "status": "pending",
        "priority": "high",
        "complexity": 4
      }
    ],
    "totalCandidates": 8
  }
}
```

**Token usage**: ~150-200 tokens

### Detailed Response (includeDetails=true)
```json
{
  "success": true,
  "message": "Found 3 recommendation(s) from 12 unblocked task(s)",
  "data": {
    "recommendations": [
      {
        "taskId": "550e8400-e29b-41d4-a716-446655440000",
        "title": "Implement OAuth Google provider",
        "status": "pending",
        "priority": "high",
        "complexity": 4,
        "summary": "Add Google OAuth 2.0 authentication with token management",
        "featureId": "661e8511-f30c-41d4-a716-557788990000",
        "tags": ["authentication", "oauth", "backend"]
      },
      {
        "taskId": "772f9622-g41d-52e5-b827-668899101111",
        "title": "Update API documentation",
        "status": "pending",
        "priority": "high",
        "complexity": 2,
        "summary": "Document new OAuth endpoints in API reference",
        "featureId": "661e8511-f30c-41d4-a716-557788990000",
        "tags": ["documentation", "api"]
      }
    ],
    "totalCandidates": 12
  }
}
```

**Token usage**: ~300-500 tokens (varies by task count and details)

### Empty Response (no unblocked tasks)
```json
{
  "success": true,
  "message": "No unblocked tasks available",
  "data": {
    "recommendations": [],
    "totalCandidates": 0
  }
}
```

**Interpretation**:
- All active tasks are blocked by dependencies
- Time to complete blocker tasks
- Check `get_blocked_tasks` to see what's blocking work

## Common Workflows

### Workflow 1: Daily Work Routine
```javascript
// Morning: Get recommended task
const next = await get_next_task({});

if (next.data.recommendations.length === 0) {
  console.log("No unblocked tasks available");

  // Check what's blocking work
  const blocked = await get_blocked_tasks({});
  console.log(`${blocked.data.totalBlocked} tasks are blocked`);

  // Work on completing blocker tasks
} else {
  const task = next.data.recommendations[0];
  console.log(`Recommended task: ${task.title}`);

  // Start work on recommended task
  await set_status({ id: task.taskId, status: "in-progress" });

  // Get full task details
  const fullTask = await get_task({
    id: task.taskId,
    includeSections: true
  });

  // Begin implementation...
}
```

### Workflow 2: Task Completion Cycle
```javascript
// Step 1: Complete current task
await set_status({
  id: currentTaskId,
  status: "completed"
});

await update_task({
  id: currentTaskId,
  summary: "Completed OAuth integration. All 12 tests passing."
});

// Step 2: Get next recommendation
const next = await get_next_task({});

// Step 3: Start next task immediately
if (next.data.recommendations.length > 0) {
  const nextTask = next.data.recommendations[0];

  await set_status({
    id: nextTask.taskId,
    status: "in-progress"
  });

  console.log(`Now working on: ${nextTask.title}`);
}
```

### Workflow 3: Team Task Assignment
```javascript
// Get top 5 recommendations
const next = await get_next_task({
  limit: 5,
  includeDetails: true
});

// Assign to team members based on skills
next.data.recommendations.forEach(task => {
  // Match tags to team member skills
  if (task.tags.includes("backend")) {
    console.log(`Assign to Backend Dev: ${task.title}`);
  } else if (task.tags.includes("frontend")) {
    console.log(`Assign to Frontend Dev: ${task.title}`);
  } else if (task.tags.includes("testing")) {
    console.log(`Assign to QA: ${task.title}`);
  }
});
```

### Workflow 4: Sprint Planning
```javascript
// Get all unblocked tasks in project
const next = await get_next_task({
  projectId: "772f9622-g41d-52e5-b827-668899101111",
  limit: 20,
  includeDetails: true
});

// Calculate sprint capacity
const totalComplexity = next.data.recommendations
  .reduce((sum, task) => sum + task.complexity, 0);

console.log(`${next.data.recommendations.length} tasks available`);
console.log(`Total complexity: ${totalComplexity}`);

// Group by priority for sprint planning
const byPriority = {
  high: next.data.recommendations.filter(t => t.priority === "high"),
  medium: next.data.recommendations.filter(t => t.priority === "medium"),
  low: next.data.recommendations.filter(t => t.priority === "low")
};

console.log(`High: ${byPriority.high.length} tasks`);
console.log(`Medium: ${byPriority.medium.length} tasks`);
console.log(`Low: ${byPriority.low.length} tasks`);
```

### Workflow 5: Feature Focus Mode
```javascript
// Work exclusively on a feature until complete
const featureId = "661e8511-f30c-41d4-a716-557788990000";

while (true) {
  // Get next task in feature
  const next = await get_next_task({ featureId });

  if (next.data.recommendations.length === 0) {
    console.log("Feature complete or all tasks blocked!");
    break;
  }

  const task = next.data.recommendations[0];
  console.log(`Working on: ${task.title}`);

  // Mark in progress
  await set_status({ id: task.taskId, status: "in-progress" });

  // ... do the work ...

  // Mark complete
  await set_status({ id: task.taskId, status: "completed" });

  // Loop to get next task
}
```

## Priority and Complexity Analysis

### Understanding the Sorting Algorithm

**Step 1: Priority Grouping**
```
HIGH priority tasks (all)
  ├─ Complexity 1 (quick win!)
  ├─ Complexity 3 (quick win!)
  ├─ Complexity 5
  └─ Complexity 8

MEDIUM priority tasks (all)
  ├─ Complexity 2 (quick win!)
  ├─ Complexity 4
  └─ Complexity 7

LOW priority tasks (all)
  ├─ Complexity 1
  └─ Complexity 6
```

**Step 2: Recommendation Order**
1. High priority, complexity 1
2. High priority, complexity 3
3. High priority, complexity 5
4. High priority, complexity 8
5. Medium priority, complexity 2
6. Medium priority, complexity 4
7. Medium priority, complexity 7
8. Low priority, complexity 1
9. Low priority, complexity 6

### Quick Win Analysis

**Identify quick wins** (high impact, low effort):
```javascript
const next = await get_next_task({ limit: 10 });

const quickWins = next.data.recommendations.filter(task =>
  task.priority === "high" && task.complexity <= 3
);

console.log(`${quickWins.length} quick wins available!`);
quickWins.forEach(task => {
  console.log(`- ${task.title} (complexity ${task.complexity})`);
});
```

### Complexity Distribution

**Analyze task complexity distribution**:
```javascript
const next = await get_next_task({ limit: 20 });

const distribution = {
  simple: next.data.recommendations.filter(t => t.complexity <= 3).length,
  medium: next.data.recommendations.filter(t => t.complexity >= 4 && t.complexity <= 6).length,
  complex: next.data.recommendations.filter(t => t.complexity >= 7).length
};

console.log(`Simple (1-3): ${distribution.simple}`);
console.log(`Medium (4-6): ${distribution.medium}`);
console.log(`Complex (7-10): ${distribution.complex}`);
```

## Performance Considerations

### Token Usage by Configuration

| Configuration | Tokens | Use Case |
|--------------|--------|----------|
| Default (limit=1) | ~150 | Quick recommendation |
| limit=5 | ~300 | Multiple options |
| limit=5, includeDetails | ~500-800 | Detailed selection |
| limit=20, includeDetails | ~2000-3000 | Sprint planning |

### Optimization Strategies

1. **Start with Default**: Get single recommendation first
2. **Expand as Needed**: Increase limit only when choosing
3. **Defer Details**: Load includeDetails only when necessary
4. **Use Filters**: Narrow scope with projectId/featureId
5. **Cache Results**: Store recommendations during work session

## Error Handling

### Invalid Limit
```json
{
  "success": false,
  "message": "Invalid limit. Must be between 1 and 20.",
  "error": {
    "code": "VALIDATION_ERROR"
  }
}
```

**Solution**: Use limit between 1 and 20

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

## Best Practices

1. **Trust the Algorithm**: Ranking considers priority + complexity for optimal ordering
2. **Act on Recommendations**: Start recommended task immediately for flow
3. **Check totalCandidates**: Understand how many options are available
4. **Use Default First**: Single recommendation minimizes decision fatigue
5. **Filter by Scope**: Use projectId/featureId for focused work
6. **Handle Empty Results**: When no tasks available, check blockers
7. **Include Details Strategically**: Only when context needed for decision
8. **Refresh After Completion**: Get fresh recommendation after each task

## Common Mistakes to Avoid

### ❌ Mistake 1: Overriding Recommendation Without Reason
```javascript
const next = await get_next_task({});
const recommended = next.data.recommendations[0];

// ❌ Ignoring recommendation to work on different task
const otherTask = await get_task({ id: "some-other-task-id" });
await set_status({ id: otherTask.id, status: "in-progress" });
```

**Problem**: Algorithm chose best task based on priority and blockers

### ✅ Solution: Trust Recommendation or Get Multiple Options
```javascript
const next = await get_next_task({ limit: 5 });
// Choose from top 5 if you have specific reason
```

### ❌ Mistake 2: Not Checking for Empty Results
```javascript
const next = await get_next_task({});
const task = next.data.recommendations[0];
await set_status({ id: task.taskId, status: "in-progress" });
// ❌ Crashes if no recommendations available
```

### ✅ Solution: Check Array Length
```javascript
const next = await get_next_task({});

if (next.data.recommendations.length === 0) {
  console.log("No unblocked tasks available");
  const blocked = await get_blocked_tasks({});
  // Handle blocked tasks
} else {
  const task = next.data.recommendations[0];
  await set_status({ id: task.taskId, status: "in-progress" });
}
```

### ❌ Mistake 3: Excessive Limit
```javascript
const next = await get_next_task({ limit: 20, includeDetails: true });
// ❌ Loading 3000 tokens when only need top 3
```

### ✅ Solution: Request What You Need
```javascript
const next = await get_next_task({ limit: 3 });
// Then get details for chosen task separately
const task = await get_task({
  id: next.data.recommendations[0].taskId,
  includeSections: true
});
```

### ❌ Mistake 4: Not Using Filters
```javascript
// Working on specific project but getting all tasks
const next = await get_next_task({ limit: 10 });
// ❌ Recommendations might be from other projects
```

### ✅ Solution: Filter by Scope
```javascript
const next = await get_next_task({
  projectId: "current-project-id",
  limit: 10
});
```

## Integration Patterns

### Pattern: Automated Workflow Engine
```javascript
async function autoWorkflow(projectId) {
  while (true) {
    // Get next task
    const next = await get_next_task({ projectId });

    if (next.data.recommendations.length === 0) {
      console.log("No more unblocked tasks");
      break;
    }

    const task = next.data.recommendations[0];

    // Check if automated handler exists for this task type
    const handler = getAutomatedHandler(task.tags);

    if (handler) {
      console.log(`Auto-executing: ${task.title}`);
      await handler.execute(task);
      await set_status({ id: task.taskId, status: "completed" });
    } else {
      console.log(`Manual task: ${task.title}`);
      // Queue for human
      break;
    }
  }
}
```

### Pattern: Team Load Balancing
```javascript
async function assignTasksToTeam(teamMembers) {
  const next = await get_next_task({
    limit: teamMembers.length * 2,  // Get 2 tasks per team member
    includeDetails: true
  });

  // Assign based on current workload
  teamMembers.forEach(member => {
    // Find unassigned task matching member's skills
    const task = next.data.recommendations.find(t =>
      t.tags.some(tag => member.skills.includes(tag))
    );

    if (task) {
      console.log(`Assign to ${member.name}: ${task.title}`);
      // Update task with assignment
    }
  });
}
```

## Related Tools

- **get_blocked_tasks**: See what tasks are blocked (complementary view)
- **set_status**: Mark recommended task as in-progress
- **get_task**: Get full task details after selecting recommendation
- **update_task**: Update task after completion
- **search_tasks**: Find tasks by specific criteria (alternative to recommendations)
- **get_feature_tasks**: See all tasks in feature (for feature-focused work)

## See Also

- Task Prioritization Guide: `task-orchestrator://guidelines/task-prioritization`
- Workflow Optimization: `task-orchestrator://guidelines/workflow-optimization`
- Quick Wins Strategy: `task-orchestrator://guidelines/quick-wins`
