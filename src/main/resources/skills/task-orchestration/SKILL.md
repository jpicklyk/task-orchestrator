---
skill: task-orchestration
description: Dependency-aware parallel task execution with automatic specialist routing, progress monitoring, and cascading completion. Replaces Task Management Skill with enhanced capabilities.
---

# Task Orchestration Skill

Intelligent task execution management with parallel processing, dependency-aware batching, and automatic specialist coordination.

## When to Use This Skill

**Activate for:**
- "Execute tasks for feature X"
- "What tasks are ready to start?"
- "Launch next batch of tasks"
- "Complete task Y"
- "Monitor parallel execution"
- "Show task progress"

**This skill handles:**
- Dependency-aware task batching
- Parallel specialist launching
- Progress monitoring
- Task completion with summaries
- Dependency cascade triggering
- Specialist routing

## Tools Available

- `query_container` - Read tasks, features, dependencies
- `manage_container` - Update task status, create tasks
- `query_dependencies` - Analyze task dependencies
- `recommend_agent` - Route tasks to specialists
- `manage_sections` - Update task sections

## Core Workflows

### 1. Dependency-Aware Batching

**Create execution batches based on dependencies:**

```javascript
// Algorithm for batching
function create_execution_batches(feature_id) {
  // Get all tasks
  tasks = query_container(
    operation="search",
    containerType="task",
    featureId=feature_id,
    status="pending"
  )

  // Get dependencies for all tasks
  dependencies = {}
  for (task in tasks) {
    deps = query_dependencies(
      taskId=task.id,
      direction="incoming",  // What blocks this task
      type="BLOCKS"
    )
    dependencies[task.id] = deps
  }

  // Build batches
  batches = []
  completed = new Set()
  remaining = new Set(tasks)

  while (remaining.size > 0) {
    batch = []

    // Find tasks with all dependencies completed
    for (task in remaining) {
      task_deps = dependencies[task.id]
      if (all dependencies in completed) {
        batch.push(task)
      }
    }

    if (batch.length === 0) {
      // Circular dependency detected
      return {error: "Circular dependencies", tasks: remaining}
    }

    batches.push(batch)
    completed.add(...batch)
    remaining.remove(...batch)
  }

  return batches
}
```

**Output format:**
```json
{
  "batches": [
    {
      "batch_number": 1,
      "parallel": true,
      "task_count": 2,
      "tasks": [
        {
          "id": "uuid-1",
          "title": "Create database schema",
          "complexity": 5,
          "specialist": "Database Engineer",
          "dependencies": []
        },
        {
          "id": "uuid-3",
          "title": "Create UI components",
          "complexity": 6,
          "specialist": "Frontend Developer",
          "dependencies": []
        }
      ]
    },
    {
      "batch_number": 2,
      "parallel": false,
      "task_count": 1,
      "tasks": [
        {
          "id": "uuid-2",
          "title": "Implement API endpoints",
          "complexity": 7,
          "specialist": "Backend Engineer",
          "dependencies": ["uuid-1"]
        }
      ]
    }
  ],
  "total_batches": 2,
  "estimated_time_savings": "40%"
}
```

### 2. Parallel Specialist Launch

**Launch multiple specialists concurrently:**

```javascript
// For each task in parallel batch
function launch_parallel_batch(batch) {
  launch_instructions = []

  for (task in batch.tasks) {
    // Get specialist recommendation
    recommendation = recommend_agent(taskId=task.id)

    // Prepare launch context
    launch_instructions.push({
      specialist: recommendation.agent,
      task_id: task.id,
      task_title: task.title,
      launch_mode: "parallel",
      batch_id: batch.batch_number
    })
  }

  return {
    action: "launch_parallel",
    batch: batch.batch_number,
    launches: launch_instructions
  }
}
```

**Orchestrator instructions format:**
```markdown
Launch the following specialists in PARALLEL (Batch 1):

1. **Database Engineer**
   - Task: Create database schema (uuid-1)
   - Complexity: 5

2. **Frontend Developer**
   - Task: Create UI components (uuid-3)
   - Complexity: 6

Wait for both to complete before proceeding to Batch 2.
```

### 3. Progress Monitoring

**Track parallel execution progress:**

```javascript
function monitor_parallel_execution(batch_id, feature_id) {
  // Get tasks in this batch
  batch_tasks = get_batch_tasks(batch_id, feature_id)

  // Check status of each
  status_counts = {
    completed: [],
    in_progress: [],
    failed: [],
    blocked: []
  }

  for (task_id in batch_tasks) {
    task = query_container(
      operation="get",
      containerType="task",
      id=task_id
    )
    status_counts[task.status].push(task_id)
  }

  // Calculate progress
  total = batch_tasks.length
  completed = status_counts.completed.length

  return {
    batch_id: batch_id,
    progress: `${completed}/${total}`,
    percent: (completed / total) * 100,
    status_breakdown: status_counts,
    ready_for_next_batch: status_counts.in_progress.length === 0
  }
}
```

### 4. Dependency Cascade

**Automatically trigger next batch when current completes:**

```javascript
function handle_task_completion(task_id) {
  // Update task status
  manage_container(
    operation="setStatus",
    containerType="task",
    id=task_id,
    status="completed"
  )

  // Check if batch complete
  batch_info = get_task_batch_info(task_id)
  batch_complete = check_batch_completion(batch_info.batch_number)

  if (batch_complete) {
    // Get next batch
    next_batch = get_next_batch(batch_info.batch_number + 1)

    if (next_batch) {
      return {
        action: "trigger_next_batch",
        batch: next_batch,
        cascade: true,
        message: `Batch ${batch_info.batch_number} complete. Ready to launch Batch ${next_batch.batch_number}.`
      }
    } else {
      // All batches complete
      return {
        action: "feature_tasks_complete",
        message: "All task batches completed. Feature ready for testing."
      }
    }
  }

  return {
    action: "wait",
    message: `Batch ${batch_info.batch_number} still in progress.`
  }
}
```

### 5. Specialist Routing

**Intelligent routing with fallback:**

```javascript
function route_task_to_specialist(task_id) {
  // Primary: Use recommend_agent tool
  recommendation = recommend_agent(taskId=task_id)

  if (recommendation.recommended) {
    return {
      specialist: recommendation.agent,
      reason: recommendation.reason,
      matched_tags: recommendation.matchedTags
    }
  }

  // Fallback: Load from config
  config = load_config(".taskorchestrator/config.yaml")
  default_specialist = config.specialist_routing?.default_specialist || "Backend Engineer"

  fallback_behavior = config.specialist_routing?.fallback_behavior || "use_default"

  if (fallback_behavior === "ask_user") {
    return {
      action: "ask_user",
      task_id: task_id,
      message: "No specialist matched. Which specialist should handle this task?"
    }
  }

  return {
    specialist: default_specialist,
    reason: "Default fallback",
    matched_tags: []
  }
}
```

### 6. Task Completion

**Complete task with summary section:**

```javascript
function complete_task(task_id, summary_content) {
  // Create summary section if not exists
  manage_sections(
    operation="add",
    entityType="TASK",
    entityId=task_id,
    title="Task Summary",
    usageDescription="What was accomplished",
    content=summary_content,
    contentFormat="MARKDOWN",
    ordinal=998,
    tags="summary,completion"
  )

  // Create Files Changed section
  manage_sections(
    operation="add",
    entityType="TASK",
    entityId=task_id,
    title="Files Changed",
    usageDescription="Files modified during implementation",
    content=extract_files_changed(summary_content),
    contentFormat="MARKDOWN",
    ordinal=999,
    tags="files-changed,completion"
  )

  // Mark complete
  manage_container(
    operation="setStatus",
    containerType="task",
    id=task_id,
    status="completed"
  )

  // Check for cascade
  return handle_task_completion(task_id)
}
```

## Execution Strategies

### Strategy 1: Sequential Execution
**When:** All tasks have dependencies on previous tasks
```
T1 → T2 → T3 → T4
```
Launch one at a time, wait for completion.

### Strategy 2: Full Parallel Execution
**When:** No dependencies between tasks
```
T1
T2
T3
T4
```
Launch all simultaneously (respecting max_parallel_tasks limit).

### Strategy 3: Hybrid Batched Execution
**When:** Mix of dependencies and parallel opportunities
```
Batch 1: T1, T3 (parallel)
Batch 2: T2 (depends on T1)
Batch 3: T4 (depends on T2, T3)
```
Most common pattern - optimize for parallelism.

### Strategy 4: Resource-Aware Execution
**When:** Config specifies resource limits
```
max_parallel_tasks: 3
Batch 1a: T1, T2, T3 (parallel)
Batch 1b: T4, T5 (wait for slot)
```
Respect resource constraints while maximizing parallelism.

## Configuration Integration

**Load settings from `.taskorchestrator/config.yaml`:**

```yaml
parallelism:
  enabled: true
  max_parallel_tasks: 5
  auto_launch: true
  monitor_interval: 30

  batch_strategy: "dependency"  # or "resource", "complexity"

specialist_routing:
  use_recommend_agent: true
  default_specialist: "Backend Engineer"
  fallback_behavior: "use_default"  # or "ask_user"
```

## Examples

### Example 1: Execute Feature Tasks

**User:** "Execute tasks for authentication feature"

**Actions:**
```javascript
1. Get feature ID
2. create_execution_batches(feature_id)
3. Result:
   Batch 1 (Parallel): Database schema, UI components
   Batch 2 (Sequential): Backend API
   Batch 3 (Sequential): Integration tests

4. Return: "Execution plan created with 3 batches.
   Ready to launch Batch 1 with 2 parallel tasks:
   - Database Engineer: Create schema
   - Frontend Developer: Create UI components"
```

### Example 2: Launch Parallel Batch

**User:** "Launch next batch"

**Actions:**
```javascript
1. Get next ready batch
2. For each task in batch:
   - recommend_agent(task_id)
3. Return: "Launching Batch 1 in PARALLEL:

   1. Database Engineer - Create database schema
   2. Frontend Developer - Create UI components

   Both tasks can run simultaneously."
```

### Example 3: Monitor Progress

**User:** "Show task progress"

**Actions:**
```javascript
1. Get current batch
2. monitor_parallel_execution(batch_id)
3. Return: "Batch 1 Progress: 1/2 (50%)
   ✓ Database schema - Completed
   ⏳ UI components - In Progress

   Waiting for UI components to complete before launching Batch 2."
```

### Example 4: Complete Task with Cascade

**User:** "Complete UI components task"

**Actions:**
```javascript
1. create_summary_section()
2. mark_complete()
3. handle_task_completion() detects batch complete
4. Return: "Task completed. Batch 1 complete (2/2).

   Ready to launch Batch 2:
   - Backend Engineer: Implement API endpoints"
```

### Example 5: Circular Dependency Detection

**User:** "Execute feature tasks"

**Actions:**
```javascript
1. create_execution_batches()
2. Detect: T2 → T5 → T7 → T2
3. Return: "Error: Circular dependencies detected
   T2 → T5 → T7 → T2

   Use Dependency Orchestration Skill to resolve:
   - Remove unnecessary dependencies
   - Reorder tasks
   - Split circular task"
```

## Integration with Other Skills

**Works alongside:**
- **Feature Orchestration Skill** - Receives task execution requests
- **Dependency Orchestration Skill** - For complex dependency analysis
- **Status Progression Skill** - For status management

**Launches subagents:**
- All specialist subagents based on recommend_agent results

## Parallel Execution Patterns

### Pattern 1: Domain Isolation
```
Database tasks  → Backend tasks
Frontend tasks  ↗
```
Database and Frontend can run parallel (different domains).

### Pattern 2: Layer Separation
```
Data Layer → Business Logic → Presentation Layer
```
Must be sequential (dependencies).

### Pattern 3: Feature Isolation
```
Auth module    → Integration
Reporting module ↗
```
Independent modules can run parallel.

### Pattern 4: Test Parallelism
```
Unit tests      (parallel)
Integration tests (parallel)
E2E tests       (sequential after all)
```
Test types can often run concurrently.

## Token Efficiency

**Optimization techniques:**
- Use `overview` operations for batch status checks
- Batch specialist launches in single message
- Return minimal progress reports
- Query only necessary dependency information
- Cache batch information

**Token savings:**
```
Old approach: Get each task fully (2800 tokens × 10 tasks = 28k)
New approach: Overview batch (1200 tokens total)
Savings: 95% token reduction
```

## Error Handling

**Common scenarios:**

1. **Task blocked during execution:**
   ```javascript
   Detect: Task status changed to "blocked"
   Action: Notify orchestrator, suggest unblocking actions
   ```

2. **Specialist fails task:**
   ```javascript
   Detect: Task marked failed
   Action: Report failure, suggest remediation
   Do not cascade to next batch
   ```

3. **Max parallel limit reached:**
   ```javascript
   Detect: 5 tasks already in progress
   Action: Queue remaining tasks, wait for slot
   ```

4. **No specialist matched:**
   ```javascript
   Use fallback_behavior from config
   If ask_user: Prompt for specialist choice
   If use_default: Use default_specialist
   ```

## Best Practices

1. **Always analyze dependencies** before execution
2. **Respect max_parallel_tasks** configuration
3. **Monitor parallel progress** actively
4. **Handle failures gracefully** without cascade
5. **Use recommend_agent** for all routing
6. **Create meaningful summaries** on completion
7. **Trigger cascades automatically** when batch completes
8. **Report clear progress** to users

## Success Metrics

- 40% reduction in feature completion time with parallelism
- 95% successful specialist routing on first attempt
- Zero circular dependencies in production
- Automated cascade triggering (no manual intervention)
- 500-900 token average per orchestration session
