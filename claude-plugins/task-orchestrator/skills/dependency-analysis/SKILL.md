---
name: Dependency Analysis
description: Analyze task dependencies including finding blocked tasks, checking dependency chains, and identifying bottlenecks. Use when investigating why tasks are blocked or planning parallel work.
allowed-tools: mcp__task-orchestrator__query_container, mcp__task-orchestrator__query_dependencies
---

# Dependency Analysis Skill

## Purpose

This Skill helps you analyze task dependencies within Task Orchestrator to:
- Find all blocked tasks in a feature
- Analyze dependency chains to understand task relationships
- Identify bottleneck tasks that are blocking multiple others
- Recommend which dependencies to resolve first for maximum parallel work

## When to Use This Skill

**Use Dependency Analysis when:**
- ✅ User asks "what's blocking progress?" or "why can't we start this task?"
- ✅ Planning parallel work across team members
- ✅ Feature appears stalled with no tasks in progress
- ✅ Need to prioritize which tasks to complete first
- ✅ Investigating circular dependencies
- ✅ Optimizing task execution order

**Don't use this Skill when:**
- ❌ Creating new dependencies (use `manage_dependency` directly)
- ❌ Removing dependencies (use `manage_dependency` directly)
- ❌ Just checking a single task's dependencies (use `query_dependencies` directly)

## Core Workflows

### Workflow 1: Find All Blocked Tasks

**When to use**: User wants to know what tasks can't be started yet

**Steps**:
1. Get feature ID (from context or ask user)
2. Search for all tasks in feature: `query_container(operation="search", containerType="task", featureId=<id>)`
3. For each task, check dependencies: `query_dependencies(taskId=<id>, direction="incoming")`
4. Filter tasks that have incomplete blocking dependencies
5. Present summary: "X tasks are blocked, waiting on Y dependencies"

**Example**:
```
User: "What's blocking progress on the authentication feature?"

You (using this Skill):
1. query_container(operation="search", containerType="task", featureId="auth-feature-id", status="pending,in-progress")
2. For each task: query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)
3. Found 3 blocked tasks:
   - "Implement login UI" (blocked by "Create auth API" - status: in-progress)
   - "Add logout flow" (blocked by "Create auth API" - status: in-progress)
   - "Add password reset" (blocked by "Create auth API", "Implement login UI")
4. Response: "3 tasks are blocked. Priority: Complete 'Create auth API' first (unblocks 3 tasks)"
```

### Workflow 2: Analyze Dependency Chains

**When to use**: Need to understand full sequence of task dependencies

**Steps**:
1. Start with target task ID
2. Call `query_dependencies(taskId=<id>, direction="incoming")`
3. Build dependency tree:
   - Direct dependencies (must complete immediately before)
   - Full dependency chain from returned data
4. Identify longest chain (critical path)
5. Present visual representation

**Example**:
```
User: "What needs to happen before we can deploy the feature?"

You (using this Skill):
1. query_dependencies(taskId="deploy-task-id", direction="incoming", includeTaskInfo=true)
2. Chain discovered:
   - Deploy Feature (target)
     ← Integration Tests
       ← Frontend Implementation
         ← API Implementation
           ← Database Schema
3. Response: "Critical path is 5 tasks deep. Start with 'Database Schema' (no dependencies)"
```

### Workflow 3: Identify Bottleneck Tasks

**When to use**: Want to know which tasks are blocking the most work

**Steps**:
1. Get all tasks in feature with `query_container(operation="search", containerType="task", featureId=<id>)`
2. For each pending/in-progress task:
   - Count how many tasks depend on it (outgoing dependencies)
3. Sort by dependent count (descending)
4. Highlight top bottlenecks with dependent task counts

**Example**:
```
User: "Which tasks should we prioritize to unblock the most work?"

You (using this Skill):
1. query_container(operation="search", containerType="task", featureId="feature-id", status="pending,in_progress")
2. Analyze outgoing dependencies:
   - "Create auth API" → 5 tasks depend on this (BOTTLENECK)
   - "Setup database" → 3 tasks depend on this
   - "Design user flow" → 2 tasks depend on this
   - "Write documentation" → 0 tasks depend on this
3. Response: "Priority 1: 'Create auth API' (unblocks 5 tasks). Priority 2: 'Setup database' (unblocks 3 tasks)"
```

### Workflow 4: Recommend Resolution Order

**When to use**: Multiple blocked tasks, need to decide what to work on

**Steps**:
1. Search for all tasks: `query_container(operation="search", containerType="task", featureId=<id>)`
2. For each task, get dependencies: `query_dependencies(taskId=<id>, direction="all", includeTaskInfo=true)`
3. Identify tasks that are blocking others (outgoing dependencies with incomplete status)
4. For each blocking task:
   - Count how many tasks it unblocks
   - Get task priority and complexity from search results
5. Calculate resolution score:
   - Higher score = unblocks more tasks + higher priority + lower complexity
6. Recommend top 3 tasks to complete first

**Scoring formula**:
```
Score = (tasks_unblocked × 10) + (priority_weight × 5) - (complexity_weight × 2)

Priority weights: critical=5, high=4, medium=3, low=2, trivial=1
Complexity: use inverse (10 - complexity_rating)
```

**Example**:
```
User: "We have 10 blocked tasks. What should we work on first?"

You (using this Skill):
1. query_container(operation="search", containerType="task", featureId="feature-id", status="pending,in-progress")
2. For each task: query_dependencies(taskId="...", direction="outgoing", includeTaskInfo=true)
3. Analyze blocking dependencies:
   - "Create auth API": unblocks 5 tasks, priority=high, complexity=6
     Score = (5×10) + (4×5) - (6×2) = 50 + 20 - 12 = 58
   - "Setup database": unblocks 3 tasks, priority=critical, complexity=8
     Score = (3×10) + (5×5) - (8×2) = 30 + 25 - 16 = 39
4. Response: "Work on 'Create auth API' first (score: 58, unblocks 5 tasks)"
```

## Advanced Patterns

### Pattern: Detect Circular Dependencies

**Problem**: Tasks depend on each other, creating a deadlock

**Detection**:
1. Get task dependencies recursively
2. Track visited tasks
3. If you encounter a task already in the chain → circular dependency found

**Response**:
```
"⚠️ Circular dependency detected:
Task A depends on Task B, which depends on Task C, which depends on Task A.
Action required: Remove one dependency to break the cycle."
```

### Pattern: Find Parallelizable Work

**Goal**: Identify tasks that can be worked on simultaneously

**Steps**:
1. Get all pending tasks
2. Filter to tasks with no incomplete dependencies
3. Group by specialist type (backend, frontend, etc.)
4. Recommend parallel assignments

**Example output**:
```
"Ready to start in parallel:
- Implementation Specialist (Haiku): 'Implement user service' (backend-implementation Skill)
- Implementation Specialist (Haiku): 'Create login form' (frontend-implementation Skill)
- Implementation Specialist (Haiku): 'Add user indexes' (database-implementation Skill)
All 3 tasks have no dependencies and can proceed simultaneously."
```

### Pattern: Critical Path Analysis

**Goal**: Find the longest sequence of dependent tasks

**Steps**:
1. Build complete dependency graph
2. Calculate longest path from start to each task
3. Identify tasks on critical path (longest sequence)
4. Recommend focusing on critical path to minimize total time

## Tool Usage Guidelines

### Finding Blocked Tasks

**Approach**: Search for tasks, then check each for blocking dependencies

**Steps**:
1. Search for tasks: `query_container(operation="search", containerType="task", featureId=<id>, status="pending,in-progress")`
2. For each task: `query_dependencies(taskId=<id>, direction="incoming", includeTaskInfo=true)`
3. Filter tasks where any incoming dependency has status != "completed" and status != "cancelled"

**Usage**:
```javascript
// Step 1: Get all active tasks
query_container(operation="search", containerType="task", featureId="550e8400-e29b-41d4-a716-446655440000", status="pending,in-progress")

// Step 2: Check each task for blockers
query_dependencies(taskId="task-id", direction="incoming", includeTaskInfo=true)

// Step 3: Identify blocked tasks from dependency status
```

### query_dependencies

**Purpose**: Get dependencies for a specific task

**Parameters**:
- `taskId` (required): Task to analyze
- `direction` (optional): "incoming", "outgoing", or "all" (default: all)
- `type` (optional): "BLOCKS", "IS_BLOCKED_BY", "RELATES_TO", or "all"
- `includeTaskInfo` (optional): Include task titles and status

**Returns**: Dependencies with direction filtering and counts

**Usage**:
```javascript
// All dependencies
query_dependencies(taskId="task-id")

// Just incoming dependencies (what blocks this task)
query_dependencies(taskId="task-id", direction="incoming", includeTaskInfo=true)
```

### query_container (get task)

**Purpose**: Get full task details including summary and priority

**Parameters**:
- `operation`: "get" (required)
- `containerType`: "task" (required)
- `id` (required): Task UUID
- `includeSections` (optional): Include detailed content sections

**Usage**:
```javascript
query_container(
  operation="get",
  containerType="task",
  id="task-id",
  includeSections=false
)
```

### query_container (search tasks)

**Purpose**: Find tasks by criteria

**Parameters**:
- `operation`: "search" (required)
- `containerType`: "task" (required)
- `featureId` (optional): Filter by feature
- `status` (optional): Filter by status
- `tags` (optional): Filter by tags

**Usage**:
```javascript
// Find all pending tasks in feature
query_container(operation="search", containerType="task", featureId="feature-id", status="pending")

// Find all in-progress tasks
query_container(operation="search", containerType="task", status="in_progress")
```

## Best Practices

### 1. Start Broad, Then Narrow

Always begin with feature-level analysis:
```
Step 1: query_container(operation="search", containerType="task", featureId=X) → Get all tasks
Step 2: query_dependencies(taskId=Y, ...) for each task → Identify blocked tasks
Step 3: Analyze patterns and prioritize resolution
```

### 2. Consider Task Metadata

When recommending priorities, factor in:
- **Priority**: Critical tasks should be resolved first
- **Complexity**: Lower complexity = faster to complete
- **Blocking count**: More tasks unblocked = higher impact
- **Specialist availability**: Can the right person work on it?

### 3. Visualize Chains

Present dependency chains visually:
```
Task E (Deploy)
  ← Task D (Integration Tests)
    ← Task C (Frontend)
      ← Task B (API)
        ← Task A (Database Schema)

Critical path: 5 tasks, start with Task A
```

### 4. Provide Actionable Recommendations

Don't just report problems, suggest solutions:
- ❌ "Task X is blocked by Task Y"
- ✅ "Complete Task Y first to unblock 3 tasks including Task X"

### 5. Watch for Anti-Patterns

**Warn users about**:
- Circular dependencies (deadlock situation)
- Long dependency chains (>5 tasks deep = brittle)
- Single bottleneck tasks (risk if that person is unavailable)
- Tasks with many dependencies (complexity, coordination overhead)

## Common Mistakes to Avoid

### Mistake 1: Not Checking Complete Dependency Chains

**Problem**: Missing hidden dependencies in the chain

**Solution**: Recursively query dependencies using `direction="incoming"` to build complete dependency tree

### Mistake 2: Ignoring Task Status

**Problem**: Counting completed tasks as blockers

**Solution**: Filter to `status=pending,in_progress` when analyzing blockers

### Mistake 3: Overwhelming Users

**Problem**: Dumping full dependency graph without interpretation

**Solution**: Summarize findings, prioritize recommendations

### Mistake 4: Not Updating Analysis

**Problem**: Dependencies change, old analysis becomes stale

**Solution**: Re-run analysis when tasks complete or dependencies change

## Response Templates

### Blocked Tasks Summary
```
Found [N] blocked tasks in feature "[Feature Name]":

Priority 1: Complete "[Task Title]" → Unblocks [X] tasks
Priority 2: Complete "[Task Title]" → Unblocks [Y] tasks
Priority 3: Complete "[Task Title]" → Unblocks [Z] tasks

[N-3] other tasks blocked with lower impact.

Recommendation: Focus on Priority 1 for maximum parallel work.
```

### Bottleneck Identification
```
Dependency Analysis for "[Feature Name]":

Bottleneck Tasks (blocking multiple others):
1. "[Task Title]" (complexity: [X]/10) → Blocking [N] tasks
   Status: [status] | Priority: [priority]

2. "[Task Title]" (complexity: [Y]/10) → Blocking [M] tasks
   Status: [status] | Priority: [priority]

Recommendation: Complete task #1 first to unblock maximum work.
```

### Critical Path Report
```
Critical Path Analysis for "[Feature Name]":

Longest dependency chain: [N] tasks deep
Path: [Task A] → [Task B] → [Task C] → [Task D] → [Task E]

Estimated sequence:
1. [Task A] (complexity: [X]/10, priority: [priority])
2. [Task B] (complexity: [Y]/10, priority: [priority])
... [etc]

Ready to start now: [Task A] (no dependencies)
```

## Integration with Other Skills

### Works Well With

**Feature Management Skill**:
- Use dependency analysis to recommend next task intelligently
- Check for blockers before recommending new work

**Task Management Skill**:
- Validate dependencies before marking task complete
- Route blocked tasks appropriately

**Planning Specialist** (Subagent):
- Provide dependency analysis to inform task breakdown
- Identify dependencies during feature planning

## See Also

- **examples.md**: Concrete usage scenarios with sample data
- **troubleshooting.md**: Common dependency issues and solutions
- **Feature Management Skill**: Task recommendation workflow
- **Task Management Skill**: Task completion workflow
