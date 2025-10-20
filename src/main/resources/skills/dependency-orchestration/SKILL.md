---
skill: dependency-orchestration
description: Advanced dependency analysis, critical path identification, bottleneck detection, and parallel opportunity discovery using MCP tool orchestration patterns.
---

# Dependency Orchestration Skill

Comprehensive dependency analysis and resolution strategies for optimizing task execution workflows through systematic MCP tool usage.

## When to Use This Skill

**Activate for:**
- "Analyze dependencies for feature X"
- "What's blocking task Y?"
- "Find bottlenecks in feature Z"
- "Show critical path"
- "Find parallel opportunities"
- "Resolve circular dependencies"

**This skill handles:**
- Systematic dependency analysis using query patterns
- Critical path identification through recursive queries
- Bottleneck detection by analyzing outgoing dependencies
- Parallel opportunity discovery
- Circular dependency detection
- Resolution strategy recommendations

## Tools Available

- `query_dependencies` - Query task dependencies
- `query_container` - Read tasks and features
- `manage_dependency` - Create/delete dependencies

## Core Workflows

### 1. Analyze Feature Dependencies

**Tool Orchestration Pattern:**

```
Step 1: Get all tasks in feature
query_container(operation="search", containerType="task", featureId="...")

Step 2: For each task, get dependencies
query_dependencies(taskId="...", direction="all", includeTaskInfo=true)

Step 3: Build dependency understanding
For each task, track:
- Incoming dependencies (what blocks this task)
- Outgoing dependencies (what this task blocks)
- Dependency status (complete/incomplete)

Step 4: Identify patterns
- Tasks with no incoming deps = can start immediately
- Tasks with many outgoing deps = potential bottlenecks
- Tasks blocked by incomplete deps = currently blocked
```

**Example:**

```
User: "Analyze dependencies for authentication feature"

Actions:
1. query_container(operation="search", containerType="task", featureId="auth-feature-id")
   Returns: 8 tasks

2. For each of 8 tasks:
   query_dependencies(taskId="...", direction="all", includeTaskInfo=true)

3. Analysis results:
   - 2 tasks have no incoming dependencies (can start now)
   - 1 task blocks 4 other tasks (BOTTLENECK)
   - 3 tasks are blocked by incomplete dependencies
   - 2 tasks are independent (can run in parallel)

4. Report:
   "Feature has 8 tasks with 1 critical bottleneck.
   Recommend completing 'Implement auth API' first (unblocks 4 tasks).
   2 tasks can start immediately in parallel."
```

### 2. Critical Path Identification

**Tool Orchestration Pattern:**

```
Step 1: Get all tasks
query_container(operation="search", containerType="task", featureId="...")

Step 2: Build dependency chains recursively
For each task with no outgoing dependencies (end tasks):
  Work backwards using incoming dependencies
  Track: task1 ← task2 ← task3 ← task4

Step 3: Calculate path lengths
Sum complexity values along each path

Step 4: Identify longest path
Path with highest total complexity = critical path

Step 5: Report findings
"Critical path: [tasks] with total complexity X"
```

**Example:**

```
User: "Show critical path for feature X"

Actions:
1. query_container(operation="search", containerType="task", featureId="...")
   Returns: Tasks T1, T2, T3, T4

2. For each task: query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)

3. Trace paths:
   Path A: T1 (complexity 5) → T2 (complexity 7) → T4 (complexity 5) = 17
   Path B: T3 (complexity 6) → T4 (complexity 5) = 11

4. Report:
   "Critical path: T1 → T2 → T4 (total complexity: 17, 68% of work)
   This path determines minimum feature completion time."
```

### 3. Bottleneck Detection

**Tool Orchestration Pattern:**

```
Step 1: Get all incomplete tasks
query_container(operation="search", containerType="task", featureId="...", status="pending,in-progress")

Step 2: For each task, count outgoing dependencies
query_dependencies(taskId="...", direction="outgoing", includeTaskInfo=true)
Count how many tasks are blocked by this task

Step 3: Identify high-impact tasks
Tasks blocking 3+ other tasks = HIGH impact bottleneck
Tasks blocking 2 other tasks = MEDIUM impact

Step 4: Prioritize by impact and status
- In-progress bottlenecks = highest priority (complete ASAP)
- Pending bottlenecks = high priority (start soon)
- Include task complexity in recommendations

Step 5: Report with actions
"Bottleneck: [Task X] blocks [N] tasks. Complete this first."
```

**Example:**

```
User: "Find bottlenecks in feature Y"

Actions:
1. query_container(operation="search", containerType="task", featureId="...", status="pending,in-progress")
   Returns: 6 active tasks

2. For each task: query_dependencies(taskId="...", direction="outgoing", includeTaskInfo=true)
   Results:
   - Task A: blocks 4 tasks (HIGH IMPACT)
   - Task B: blocks 2 tasks (MEDIUM IMPACT)
   - Task C-F: block 0-1 tasks (LOW IMPACT)

3. Report:
   "HIGH IMPACT BOTTLENECK:
   'Implement auth API' (in-progress, complexity 7) blocks 4 tasks.
   Recommend: Prioritize completion immediately.

   MEDIUM IMPACT:
   'Setup database' (pending, complexity 5) blocks 2 tasks.
   Recommend: Start after auth API."
```

### 4. Parallel Opportunity Discovery

**Tool Orchestration Pattern:**

```
Step 1: Get all pending tasks
query_container(operation="search", containerType="task", featureId="...", status="pending")

Step 2: For each task, check if unblocked
query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)
If all incoming dependencies are complete → task is ready

Step 3: Group ready tasks by domain
Using task tags, group by:
- database tasks
- backend tasks
- frontend tasks
- testing tasks

Step 4: Calculate parallelism benefit
Tasks in different domains = can run truly parallel
Sum complexity: serial vs parallel time savings

Step 5: Report opportunities
"Can run in parallel: [tasks] - saves X% time"
```

**Example:**

```
User: "Find parallel opportunities in feature Z"

Actions:
1. query_container(operation="search", containerType="task", featureId="...", status="pending")
   Returns: 5 pending tasks

2. For each: query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)
   Results:
   - T1 (Database): no incomplete dependencies (READY)
   - T2 (Backend): blocked by T5
   - T3 (Frontend): no incomplete dependencies (READY)
   - T4 (Tests): blocked by T2, T3
   - T5 (Backend): in-progress

3. Identify parallel group: T1 and T3
   - Different domains (database + frontend)
   - No interdependencies
   - Total complexity: 11
   - Parallel time: max(5, 6) = 6
   - Time saved: 5 units (45%)

4. Report:
   "Parallel opportunity: Run 'Database schema' and 'UI components' simultaneously.
   Saves 45% time (11 → 6 complexity units)."
```

### 5. Circular Dependency Detection

**Tool Orchestration Pattern:**

```
Step 1: Get all tasks
query_container(operation="search", containerType="task", featureId="...")

Step 2: For each task, trace dependency chain
query_dependencies(taskId="...", direction="outgoing", includeTaskInfo=true)
For each blocked task, recursively query its outgoing dependencies

Step 3: Track visited tasks
If you encounter a task already in current chain → circular dependency found

Step 4: Identify the cycle
Report: TaskA → TaskB → TaskC → TaskA

Step 5: Suggest resolution
Analyze which dependency is weakest/most recent
Recommend removal to break cycle
```

**Example:**

```
User: "Check for circular dependencies in feature W"

Actions:
1. query_container(operation="search", containerType="task", featureId="...")
   Returns: 6 tasks

2. For T1: query_dependencies(taskId="T1", direction="outgoing")
   T1 blocks T3

3. For T3: query_dependencies(taskId="T3", direction="outgoing")
   T3 blocks T5

4. For T5: query_dependencies(taskId="T5", direction="outgoing")
   T5 blocks T1 → CIRCULAR DEPENDENCY DETECTED!

5. Report:
   "⚠️ Circular dependency detected:
   T1 → T3 → T5 → T1

   Tasks involved:
   - T1: Implement API
   - T3: Add caching
   - T5: Update API for cache

   Resolution options:
   1. Remove T5 → T1 (most recent dependency)
   2. Reorder: Complete T1, then T5 won't need to depend on T1
   3. Split T5 into two tasks to break cycle"
```

### 6. Generate Resolution Strategy

**Tool Orchestration Pattern:**

```
Step 1: Identify blocked tasks
Use patterns from Workflow 1 and 3

Step 2: Prioritize bottlenecks
Use pattern from Workflow 3

Step 3: Find parallel opportunities
Use pattern from Workflow 4

Step 4: Create execution plan
Priority 1: Bottlenecks on critical path
Priority 2: High-impact bottlenecks off critical path
Priority 3: Tasks with parallel opportunities

Step 5: Report actionable strategy
"Execute in this order: [sequence] for optimal completion time"
```

**Example:**

```
User: "What's the best way to complete feature X?"

Actions:
1. Analyze dependencies (Workflow 1)
2. Find critical path (Workflow 2): T1 → T2 → T4
3. Find bottlenecks (Workflow 3): T2 blocks 4 tasks
4. Find parallel opportunities (Workflow 4): T1 and T3 can run parallel

5. Strategy:
   "Optimal execution strategy:

   Phase 1 (Parallel):
   - Start T1 (Database) and T3 (Frontend) simultaneously
   - These have no dependencies

   Phase 2 (Critical):
   - Start T2 (Backend API) after T1 completes
   - T2 is critical bottleneck blocking 4 tasks

   Phase 3 (Parallel):
   - After T2 completes, 4 tasks become available
   - Launch T4, T5, T6, T7 in parallel

   Expected benefit: 40% faster than sequential execution"
```

## Best Practices

### 1. Always Query Dependencies with includeTaskInfo

```
✅ Good:
query_dependencies(taskId="...", direction="all", includeTaskInfo=true)

❌ Bad:
query_dependencies(taskId="...")
```

**Reason:** You need task status to determine if dependencies are complete

### 2. Use Direction Parameter Appropriately

- `direction="incoming"` - Find what blocks this task (for unblocking analysis)
- `direction="outgoing"` - Find what this task blocks (for bottleneck analysis)
- `direction="all"` - Full picture (for comprehensive analysis)

### 3. Filter by Status for Relevance

```
✅ For active bottlenecks:
query_container(operation="search", containerType="task", status="pending,in-progress")

❌ Including completed tasks:
query_container(operation="search", containerType="task")
```

**Reason:** Completed tasks aren't bottlenecks

### 4. Consider Task Complexity in Analysis

- Critical path = sum of complexity values
- Bottleneck priority = (tasks_blocked × priority) - complexity
- Parallel benefit = sum_complexity - max_complexity

### 5. Report Actionable Recommendations

Don't just describe problems:
- ❌ "Task X has 5 dependencies"
- ✅ "Complete these 2 tasks to unblock Task X: [list]"

## Response Templates

### Dependency Analysis Summary
```
Dependency Analysis for "[Feature Name]":

Total Tasks: [N]
Tasks ready to start: [M] ([list])
Tasks blocked: [K] ([list with blocking tasks])

Bottlenecks:
- [Task A] blocks [X] tasks
- [Task B] blocks [Y] tasks

Recommendations:
1. Start [ready tasks] in parallel
2. Prioritize [bottleneck] completion
3. Monitor [blocked tasks] for automatic unblocking
```

### Critical Path Report
```
Critical Path Analysis:

Path: [T1] → [T2] → [T3] → [T4]
Total complexity: [N] ([X]% of all work)
Estimated time: [N] units

Parallel opportunities:
- [Tasks] can run alongside critical path
- Expected time savings: [X]%

Recommendation: Focus resources on critical path tasks
```

### Bottleneck Alert
```
⚠️ Bottleneck Detected:

Task: "[Task Name]"
Status: [status]
Complexity: [N]/10
Blocks: [M] tasks

Blocked tasks:
- [Task 1]
- [Task 2]
...

Action required: Prioritize completion of "[Task Name]" immediately
Impact: Unblocking this will enable [M] tasks to proceed
```

## Integration with Other Skills

**Works alongside:**
- **Dependency Analysis Skill** - Provides foundational blocked task queries
- **Task Orchestration Skill** - Uses this analysis for batching decisions
- **Feature Orchestration Skill** - Informs feature progress assessment

**Complements:**
- Planning Specialist subagent - Informs initial task breakdown
- Task completion workflows - Identifies cascade effects

## Token Efficiency

This skill is more token-intensive than others due to recursive querying:

- Simple dependency check: ~200 tokens
- Full feature analysis: ~800-1200 tokens
- Critical path analysis: ~400-600 tokens
- Bottleneck detection: ~300-500 tokens

**Optimization tips:**
- Use `status` filters to reduce task counts
- Query dependencies only for relevant tasks
- Cache results during analysis session
- Use `includeTaskInfo=true` once, reuse data

## Success Metrics

- 100% circular dependency detection
- Accurate critical path identification
- Bottleneck recommendations reduce completion time by 20-40%
- Parallel opportunity discovery achieves 30-50% time savings
- Zero false positives in blocking analysis

## See Also

- **Dependency Analysis Skill**: Basic dependency checking patterns
- **Task Orchestration Skill**: Applying dependency analysis to execution
- **examples.md**: Detailed usage scenarios
