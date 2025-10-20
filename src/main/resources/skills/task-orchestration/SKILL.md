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

**Identify which tasks can run in parallel using MCP tools:**

**Tool Orchestration Pattern:**

```
Step 1: Get all pending tasks
query_container(operation="search", containerType="task", featureId="...", status="pending")

Step 2: For each task, check blocking dependencies
query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)

Step 3: Group tasks by dependency level
- Batch 1: Tasks with NO incomplete blocking dependencies (can start immediately)
- Batch 2: Tasks blocked only by Batch 1 tasks
- Batch 3: Tasks blocked by Batch 1 or 2 tasks
- Continue until all tasks assigned to batches

Step 4: If a task is blocked but all its blockers are also blocked, circular dependency detected
```

**Example Analysis:**

Given 4 tasks with dependencies:
- T1 (Database Schema) - no dependencies
- T2 (API Implementation) - depends on T1
- T3 (UI Components) - no dependencies
- T4 (Integration Tests) - depends on T2 and T3

**Result:**
- Batch 1: T1, T3 (parallel - no dependencies)
- Batch 2: T2 (sequential - depends on T1)
- Batch 3: T4 (sequential - depends on T2, T3)

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

**Launch multiple specialists concurrently using MCP tools:**

**Tool Orchestration Pattern:**

```
For each task in parallel batch:

Step 1: Get specialist recommendation
recommend_agent(taskId="task-uuid")

Step 2: Prepare launch instructions for orchestrator
Return message: "Launch [Specialist Name] for task [Task Title] (ID: task-uuid)"

Step 3: Orchestrator launches specialists in parallel
(Uses Task tool with multiple concurrent invocations)
```

**Key Point:** The skill identifies WHICH specialists to launch and in what order. The actual subagent launching is done by the orchestrator, not by this skill.

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

**Track parallel execution progress using MCP tools:**

**Tool Orchestration Pattern:**

```
Step 1: Get current batch tasks (from earlier analysis)
Keep list of task IDs currently being worked on

Step 2: Check each task status
query_container(operation="overview", containerType="task", id="task-uuid")
(Repeat for each task in batch)

Step 3: Analyze status distribution
Count how many tasks are:
- completed
- in-progress
- blocked
- pending

Step 4: Determine if batch is complete
If all tasks in batch are "completed" or "cancelled", batch is done
If any task is "blocked", identify blocker using query_dependencies

Step 5: Report progress
Return: "Batch X: Y/Z tasks complete (N%)"
```

### 4. Dependency Cascade

**Automatically trigger next batch when current completes:**

**Tool Orchestration Pattern:**

```
When a task completes:

Step 1: Task is already marked complete by specialist
(Specialists mark their own tasks complete)

Step 2: Check if this unblocks other tasks
query_dependencies(taskId="completed-task-id", direction="outgoing", includeTaskInfo=true)

Step 3: For each previously blocked task, check if now unblocked
For each outgoing dependency:
  query_dependencies(taskId="dependent-task-id", direction="incoming", includeTaskInfo=true)
  If all incoming dependencies are complete, task is now ready

Step 4: Report newly available tasks
"Task [X] complete. This unblocks [N] tasks: [list]"

Step 5: Recommend next batch
Identify newly unblocked tasks and recommend launching specialists
```

### 5. Specialist Routing

**Intelligent routing using MCP tools:**

**Tool Orchestration Pattern:**

```
Step 1: Get specialist recommendation for task
recommend_agent(taskId="task-uuid")

Returns:
{
  "recommended": true/false,
  "agent": "Backend Engineer",
  "reason": "Task tags match backend patterns",
  "matchedTags": ["backend", "api"]
}

Step 2: If recommendation provided, use it
Launch recommended specialist

Step 3: If no recommendation, use fallback
Default to general specialist or ask user for guidance

Note: Configuration loading is NOT available via MCP tools.
Configuration should be documented statically in skill files or CLAUDE.md.
```

### 6. Task Completion

**Complete task with summary section using MCP tools:**

**Tool Orchestration Pattern:**

```
Note: Specialists typically mark their own tasks complete.
This pattern is for orchestrator-driven completion.

Step 1: Create task summary section
manage_sections(
  operation="add",
  entityType="TASK",
  entityId="task-uuid",
  title="Task Summary",
  usageDescription="What was accomplished",
  content="Summary content from specialist...",
  contentFormat="MARKDOWN",
  ordinal=998,
  tags="summary,completion"
)

Step 2: Create files changed section
manage_sections(
  operation="add",
  entityType="TASK",
  entityId="task-uuid",
  title="Files Changed",
  usageDescription="Files modified during implementation",
  content="- src/main/...\n- src/test/...",
  contentFormat="MARKDOWN",
  ordinal=999,
  tags="files-changed,completion"
)

Step 3: Mark task complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)

Step 4: Check for cascade (see Workflow 4)
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

## Configuration Guidance

**Configuration patterns (documented, not dynamically loaded):**

**Parallelism Strategy:**
- Always use dependency-based batching for safety
- Respect task dependencies to avoid blocking issues
- Launch specialists concurrently when dependencies allow

**Specialist Routing:**
- Always use `recommend_agent` tool as primary routing method
- If no recommendation, default to Backend Engineer or ask user
- Never guess or hardcode specialist assignments

**Best Practices:**
- Maximum 3-5 parallel tasks for manageable monitoring
- Always check dependencies before launching
- Monitor task status after launching specialists
- Report progress regularly to user

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
