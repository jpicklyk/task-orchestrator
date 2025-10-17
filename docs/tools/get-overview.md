# get_overview Tool - Detailed Documentation

## Overview

Retrieves a lightweight, token-efficient hierarchical overview of all tasks and features in the project. Essential starting point for understanding current work state and making informed planning decisions.

**Resource**: `task-orchestrator://docs/tools/get-overview`

## Key Concepts

### Hierarchical Organization
Tasks are organized under their parent features, with orphaned tasks (no feature association) listed separately. This structure provides clear context and helps identify organizational gaps.

### Token Efficiency
Returns essential metadata without full task content, optimizing for context window efficiency. Configurable summary length allows fine-tuning token usage vs. information depth.

### Session Start Tool
**RECOMMENDED**: Begin every work session with `get_overview` to understand current state before creating or modifying tasks.

### Orphaned Tasks Detection
Identifies tasks not associated with any feature, helping maintain organizational hygiene and discover tasks needing categorization.

## Parameter Reference

### Optional Parameters
- **summaryLength** (integer, 0-200, default: 100): Maximum character length for task and feature summaries
  - **0**: Exclude summaries entirely (maximum token efficiency)
  - **50**: Brief context (very token-efficient)
  - **100**: Balanced context and efficiency (recommended default)
  - **200**: Full summary context (maximum information)

## Common Usage Patterns

### Pattern 1: Session Start Overview
Begin work session with full context.

```json
{
  "summaryLength": 100
}
```

**Response preview**:
```json
{
  "success": true,
  "data": {
    "features": [
      {
        "id": "feature-uuid",
        "name": "User Authentication",
        "status": "in-development",
        "summary": "Implements secure user authentication mechanisms with OAuth 2.0 and JWT tokens for sessio...",
        "tasks": [
          {
            "id": "task-uuid",
            "title": "Implement OAuth Google provider",
            "status": "in-progress",
            "priority": "high",
            "complexity": 7,
            "summary": "Create OAuth integration with Google provider including token exchange and user profile r...",
            "tags": "authentication, oauth, google, backend"
          }
        ]
      }
    ],
    "orphanedTasks": [...],
    "counts": {
      "features": 3,
      "tasks": 15,
      "orphanedTasks": 2
    }
  }
}
```

**When to use**: Start of every work session, before planning new work

### Pattern 2: Minimal Structure Overview
Get just the structure without summaries for maximum efficiency.

```json
{
  "summaryLength": 0
}
```

**Token savings**: ~60-70% reduction compared to full summaries

**When to use**:
- Quick status check
- Finding task IDs
- Counting tasks/features
- When you're already familiar with the work

### Pattern 3: Identify Work in Progress
Find tasks currently being worked on.

```javascript
const overview = await get_overview({ summaryLength: 50 });

const inProgressTasks = [];

overview.data.features.forEach(feature => {
  feature.tasks.forEach(task => {
    if (task.status === "in-progress") {
      inProgressTasks.push({
        taskId: task.id,
        taskTitle: task.title,
        featureName: feature.name
      });
    }
  });
});

console.log(`Found ${inProgressTasks.length} tasks in progress`);
```

**When to use**: Checking what's currently being worked on, avoiding duplicate efforts

### Pattern 4: Organize Orphaned Tasks
Identify and categorize orphaned tasks.

```javascript
const overview = await get_overview({ summaryLength: 100 });

console.log(`Found ${overview.data.orphanedTasks.length} orphaned tasks:`);

overview.data.orphanedTasks.forEach(task => {
  console.log(`- ${task.title} (${task.tags})`);

  // Suggest features based on tags
  if (task.tags.includes("authentication")) {
    console.log(`  → Consider adding to "User Authentication" feature`);
  }
});
```

**When to use**: Maintaining organizational hygiene, planning feature structure

### Pattern 5: Priority Distribution Analysis
Understand priority distribution across features.

```javascript
const overview = await get_overview({ summaryLength: 0 });

const priorities = { high: 0, medium: 0, low: 0 };

overview.data.features.forEach(feature => {
  feature.tasks.forEach(task => {
    priorities[task.priority]++;
  });
});

console.log(`Priority distribution: ${JSON.stringify(priorities)}`);
```

**When to use**: Sprint planning, workload balancing, priority rebalancing

### Pattern 6: Feature Completion Status
Track feature progress.

```javascript
const overview = await get_overview({ summaryLength: 50 });

overview.data.features.forEach(feature => {
  const total = feature.tasks.length;
  const completed = feature.tasks.filter(t => t.status === "completed").length;
  const inProgress = feature.tasks.filter(t => t.status === "in-progress").length;
  const pending = feature.tasks.filter(t => t.status === "pending").length;

  console.log(`${feature.name}:`);
  console.log(`  Completed: ${completed}/${total} (${Math.round(completed/total*100)}%)`);
  console.log(`  In Progress: ${inProgress}`);
  console.log(`  Pending: ${pending}`);
});
```

**When to use**: Status reports, progress tracking, stakeholder updates

### Pattern 7: Complexity Distribution
Analyze work complexity across features.

```javascript
const overview = await get_overview({ summaryLength: 0 });

overview.data.features.forEach(feature => {
  const complexities = feature.tasks.map(t => t.complexity);
  const avgComplexity = complexities.reduce((a, b) => a + b, 0) / complexities.length;
  const maxComplexity = Math.max(...complexities);

  console.log(`${feature.name}:`);
  console.log(`  Average complexity: ${avgComplexity.toFixed(1)}`);
  console.log(`  Max complexity: ${maxComplexity}`);
  console.log(`  Total tasks: ${feature.tasks.length}`);
});
```

**When to use**: Capacity planning, risk assessment, workload estimation

## Response Structure

### Complete Response
```json
{
  "success": true,
  "message": "Task overview retrieved successfully",
  "data": {
    "features": [
      {
        "id": "661e8511-f30c-41d4-a716-557788990000",
        "name": "User Authentication",
        "status": "in-development",
        "summary": "Implements secure user authentication mechanisms with OAuth 2.0 and JWT tokens...",
        "tasks": [
          {
            "id": "550e8400-e29b-41d4-a716-446655440000",
            "title": "Implement OAuth Authentication API",
            "summary": "Create secure authentication flow with OAuth 2.0 protocol and JWT token management...",
            "status": "in-progress",
            "priority": "high",
            "complexity": 8,
            "tags": "authentication, backend, oauth, api"
          }
        ]
      }
    ],
    "orphanedTasks": [
      {
        "id": "772f9622-g41d-52e5-b827-668899101111",
        "title": "Setup CI/CD Pipeline",
        "summary": "Configure automated build and deployment pipeline using GitHub Actions...",
        "status": "pending",
        "priority": "medium",
        "complexity": 6,
        "tags": "infrastructure, ci-cd, automation"
      }
    ],
    "counts": {
      "features": 5,
      "tasks": 23,
      "orphanedTasks": 7
    }
  }
}
```

### Empty Project Response
```json
{
  "success": true,
  "message": "Task overview retrieved successfully",
  "data": {
    "features": [],
    "orphanedTasks": [],
    "counts": {
      "features": 0,
      "tasks": 0,
      "orphanedTasks": 0
    }
  }
}
```

## Token Usage Analysis

### Summary Length Impact

| Summary Length | Features (5) | Tasks (20) | Total Tokens | Use Case |
|----------------|--------------|------------|--------------|----------|
| 0              | ~200         | ~800       | ~1000        | Structure only |
| 50             | ~350         | ~1200      | ~1550        | Brief context |
| 100            | ~450         | ~1500      | ~1950        | Balanced (default) |
| 200            | ~650         | ~2200      | ~2850        | Full context |

**Key insight**: Zero summary provides 65% token savings compared to full summaries

### Scaling with Project Size

| Project Size | Summary=0 | Summary=100 | Summary=200 |
|--------------|-----------|-------------|-------------|
| Small (5 tasks) | ~250 | ~400 | ~600 |
| Medium (20 tasks) | ~1000 | ~2000 | ~3000 |
| Large (50 tasks) | ~2500 | ~5000 | ~7500 |
| Very Large (100 tasks) | ~5000 | ~10000 | ~15000 |

**Recommendation**: Use `summaryLength=0` for large projects (50+ tasks)

## Common Workflows

### Workflow 1: Session Start Routine
```javascript
async function startWorkSession() {
  console.log("Starting work session...\n");

  // Get overview
  const overview = await get_overview({ summaryLength: 100 });

  // Show high-priority in-progress tasks
  console.log("High-priority tasks in progress:");
  overview.data.features.forEach(feature => {
    feature.tasks
      .filter(t => t.status === "in-progress" && t.priority === "high")
      .forEach(t => console.log(`  - ${t.title} (${feature.name})`));
  });

  // Show pending high-priority tasks
  console.log("\nHigh-priority tasks ready to start:");
  overview.data.features.forEach(feature => {
    feature.tasks
      .filter(t => t.status === "pending" && t.priority === "high")
      .forEach(t => console.log(`  - ${t.title} (${feature.name})`));
  });

  // Show orphaned tasks
  if (overview.data.orphanedTasks.length > 0) {
    console.log(`\nWarning: ${overview.data.orphanedTasks.length} orphaned tasks need organization`);
  }

  return overview;
}
```

### Workflow 2: Feature Selection
```javascript
async function selectFeatureToWorkOn() {
  const overview = await get_overview({ summaryLength: 100 });

  // Filter features with pending work
  const activeFeatures = overview.data.features.filter(f =>
    f.tasks.some(t => t.status === "pending" || t.status === "in-progress")
  );

  console.log("Active features:");
  activeFeatures.forEach((f, i) => {
    const pending = f.tasks.filter(t => t.status === "pending").length;
    const inProgress = f.tasks.filter(t => t.status === "in-progress").length;
    console.log(`${i + 1}. ${f.name} (${pending} pending, ${inProgress} in progress)`);
  });

  // Return feature with highest priority pending work
  return activeFeatures
    .map(f => ({
      feature: f,
      highPriorityPending: f.tasks.filter(t => t.status === "pending" && t.priority === "high").length
    }))
    .sort((a, b) => b.highPriorityPending - a.highPriorityPending)[0]?.feature;
}
```

### Workflow 3: Cleanup Check
```javascript
async function identifyCleanupOpportunities() {
  const overview = await get_overview({ summaryLength: 0 });

  console.log("=== Cleanup Opportunities ===\n");

  // Completed tasks that could be deleted
  let completedCount = 0;
  overview.data.features.forEach(feature => {
    const completed = feature.tasks.filter(t => t.status === "completed");
    completedCount += completed.length;
  });
  console.log(`${completedCount} completed tasks (consider archiving)`);

  // Orphaned tasks
  console.log(`${overview.data.orphanedTasks.length} orphaned tasks (need feature association)`);

  // Cancelled/deferred tasks
  let staleTasks = 0;
  overview.data.features.forEach(feature => {
    feature.tasks.forEach(task => {
      if (task.status === "cancelled" || task.status === "deferred") {
        staleTasks++;
      }
    });
  });
  console.log(`${staleTasks} cancelled/deferred tasks (review for deletion)`);
}
```

## Best Practices

1. **Start Every Session**: Run `get_overview` before any work
2. **Use summaryLength=0 for Large Projects**: Minimize token usage when familiar with work
3. **Use summaryLength=100 for Planning**: Balance context and efficiency
4. **Monitor Orphaned Tasks**: Regular cleanup prevents disorganization
5. **Track In-Progress**: Avoid starting new work when tasks are already in-progress
6. **Feature Completion Tracking**: Monitor progress regularly
7. **Priority Distribution**: Ensure balanced workload
8. **Regular Cleanup**: Archive completed, review deferred/cancelled tasks

## Common Mistakes to Avoid

### ❌ Mistake 1: Skipping Overview Before Creating Tasks
```javascript
// ❌ Creating task without checking existing work
const newTask = await create_task({
  title: "Implement OAuth authentication",
  // ...
});
// Might duplicate existing work!
```

### ✅ Solution: Check Overview First
```javascript
// ✅ Check existing work first
const overview = await get_overview({ summaryLength: 50 });

// Search for similar work
const existingAuth = overview.data.features
  .flatMap(f => f.tasks)
  .find(t => t.title.includes("OAuth") || t.tags.includes("authentication"));

if (existingAuth) {
  console.log(`Similar task exists: ${existingAuth.title}`);
} else {
  // Safe to create new task
  const newTask = await create_task({...});
}
```

### ❌ Mistake 2: Using Full Summaries for Large Projects
```javascript
// ❌ Wasteful for large projects
const overview = await get_overview({ summaryLength: 200 });
// Returns 15000+ tokens for 100 tasks!
```

### ✅ Solution: Use Zero Summaries for Structure
```javascript
// ✅ Efficient for large projects
const overview = await get_overview({ summaryLength: 0 });
// Returns ~5000 tokens for 100 tasks

// Fetch full details only for specific tasks
const taskDetails = await get_task({
  id: selectedTaskId,
  includeSections: true
});
```

### ❌ Mistake 3: Ignoring Orphaned Tasks
```javascript
// ❌ Not checking orphaned tasks
const overview = await get_overview();
// Continue without organizing orphans
```

### ✅ Solution: Regular Orphan Cleanup
```javascript
const overview = await get_overview({ summaryLength: 100 });

if (overview.data.orphanedTasks.length > 0) {
  console.log("Organizing orphaned tasks...");

  for (const task of overview.data.orphanedTasks) {
    // Find appropriate feature based on tags
    const matchingFeature = overview.data.features.find(f =>
      task.tags.split(", ").some(tag => f.name.toLowerCase().includes(tag))
    );

    if (matchingFeature) {
      await update_task({
        id: task.id,
        featureId: matchingFeature.id
      });
    }
  }
}
```

### ❌ Mistake 4: Not Using Counts
```javascript
// ❌ Manually counting
const overview = await get_overview();
let taskCount = 0;
overview.data.features.forEach(f => taskCount += f.tasks.length);
```

### ✅ Solution: Use Provided Counts
```javascript
const overview = await get_overview();
console.log(`Total tasks: ${overview.data.counts.tasks}`);
console.log(`Total features: ${overview.data.counts.features}`);
console.log(`Orphaned tasks: ${overview.data.counts.orphanedTasks}`);
```

## Integration Patterns

### Pattern: Smart Task Creation
```javascript
async function smartCreateTask(taskData) {
  // Check existing work
  const overview = await get_overview({ summaryLength: 50 });

  // Find appropriate feature
  let targetFeature = overview.data.features.find(f =>
    taskData.tags?.split(",").some(tag =>
      f.name.toLowerCase().includes(tag.trim())
    )
  );

  // Create feature if needed
  if (!targetFeature && taskData.featureName) {
    const newFeature = await create_feature({
      name: taskData.featureName,
      // ...
    });
    targetFeature = newFeature.data;
  }

  // Create task with feature association
  return await create_task({
    ...taskData,
    featureId: targetFeature?.id
  });
}
```

### Pattern: Progress Dashboard
```javascript
async function generateProgressDashboard() {
  const overview = await get_overview({ summaryLength: 0 });

  console.log("=== Project Dashboard ===\n");

  // Overall stats
  console.log(`Features: ${overview.data.counts.features}`);
  console.log(`Tasks: ${overview.data.counts.tasks}`);
  console.log(`Orphaned: ${overview.data.counts.orphanedTasks}\n`);

  // Feature breakdown
  overview.data.features.forEach(feature => {
    const tasks = feature.tasks;
    const completed = tasks.filter(t => t.status === "completed").length;
    const total = tasks.length;
    const progress = Math.round((completed / total) * 100);

    console.log(`${feature.name}: ${progress}% complete (${completed}/${total})`);
  });
}
```

## Related Tools

- **create_task**: Create new tasks (check overview first to avoid duplicates)
- **create_feature**: Create new features (check overview for existing features)
- **update_task**: Organize orphaned tasks into features
- **search_tasks**: Detailed search within overview results
- **get_task**: Get full details for specific tasks from overview
- **get_feature**: Get full feature details from overview

## See Also

- Task Management Patterns: `task-orchestrator://guidelines/task-management`
- Session Start Workflow: `task-orchestrator://guidelines/session-start`
- Project Organization: `task-orchestrator://guidelines/project-organization`
