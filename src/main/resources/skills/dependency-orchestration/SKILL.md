---
skill: dependency-orchestration
description: Advanced dependency graph analysis, critical path identification, bottleneck detection, and parallel opportunity discovery for complex task workflows.
---

# Dependency Orchestration Skill

Comprehensive dependency analysis and resolution strategies for optimizing task execution workflows.

## When to Use This Skill

**Activate for:**
- "Analyze dependencies for feature X"
- "What's blocking task Y?"
- "Find bottlenecks in feature Z"
- "Show critical path"
- "Find parallel opportunities"
- "Resolve circular dependencies"

**This skill handles:**
- Dependency graph construction
- Critical path identification
- Bottleneck detection and resolution
- Parallel opportunity discovery
- Circular dependency detection
- Resolution strategy generation

## Tools Available

- `query_dependencies` - Query task dependencies
- `query_container` - Read tasks and features
- `manage_dependency` - Create/delete dependencies

## Core Workflows

### 1. Build Dependency Graph

**Construct complete dependency graph for feature:**

```javascript
function build_dependency_graph(feature_id) {
  // Get all tasks
  tasks = query_container(
    operation="search",
    containerType="task",
    featureId=feature_id
  )

  // Build graph structure
  graph = {}
  for (task in tasks) {
    // Get all dependencies
    deps = query_dependencies(
      taskId=task.id,
      direction="all",
      includeTaskInfo=true
    )

    graph[task.id] = {
      title: task.title,
      status: task.status,
      complexity: task.complexity,
      depends_on: deps.incoming,  // Tasks this depends on
      blocks: deps.outgoing,       // Tasks this blocks
      level: null  // Will calculate
    }
  }

  // Calculate dependency levels
  calculate_levels(graph)

  return graph
}
```

**Graph representation:**
```json
{
  "task-1": {
    "title": "Create database schema",
    "status": "completed",
    "complexity": 5,
    "depends_on": [],
    "blocks": ["task-2"],
    "level": 0
  },
  "task-2": {
    "title": "Implement API",
    "status": "in-progress",
    "complexity": 7,
    "depends_on": ["task-1"],
    "blocks": ["task-4"],
    "level": 1
  },
  "task-3": {
    "title": "Create UI",
    "status": "pending",
    "complexity": 6,
    "depends_on": [],
    "blocks": ["task-4"],
    "level": 0
  },
  "task-4": {
    "title": "Integration tests",
    "status": "pending",
    "complexity": 5,
    "depends_on": ["task-2", "task-3"],
    "blocks": [],
    "level": 2
  }
}
```

### 2. Critical Path Identification

**Find the longest path through dependencies:**

```javascript
function find_critical_path(graph) {
  paths = []

  function traverse(node_id, path, weight) {
    node = graph[node_id]

    // Leaf node - end of path
    if (node.blocks.length === 0) {
      paths.push({
        path: path.concat([node_id]),
        weight: weight + node.complexity,
        tasks: path.concat([node_id]).map(id => graph[id].title)
      })
      return
    }

    // Traverse each blocked task
    for (next_id in node.blocks) {
      traverse(
        next_id,
        path.concat([node_id]),
        weight + node.complexity
      )
    }
  }

  // Start from root nodes (no dependencies)
  roots = Object.keys(graph).filter(id =>
    graph[id].depends_on.length === 0
  )

  for (root in roots) {
    traverse(root, [], 0)
  }

  // Return longest path
  critical = paths.reduce((max, p) =>
    p.weight > max.weight ? p : max
  )

  return {
    path: critical.path,
    tasks: critical.tasks,
    total_complexity: critical.weight,
    length: critical.path.length,
    percentage_of_total: (critical.weight / sum_all_complexity) * 100
  }
}
```

**Output format:**
```json
{
  "critical_path": {
    "path": ["task-1", "task-2", "task-4"],
    "tasks": [
      "Create database schema",
      "Implement API",
      "Integration tests"
    ],
    "total_complexity": 17,
    "length": 3,
    "percentage_of_total": 68,
    "message": "This path represents 68% of total work and determines minimum completion time."
  }
}
```

### 3. Bottleneck Detection

**Identify tasks blocking multiple other tasks:**

```javascript
function find_bottlenecks(graph) {
  bottlenecks = []

  for (task_id in graph) {
    task = graph[task_id]

    // Count how many tasks are blocked
    blocked_count = task.blocks.length

    // Only consider incomplete tasks
    if (task.status !== "completed" && task.status !== "cancelled") {
      // High impact: Blocks 3+ tasks
      if (blocked_count >= 3) {
        bottlenecks.push({
          task_id: task_id,
          title: task.title,
          status: task.status,
          complexity: task.complexity,
          blocks_count: blocked_count,
          blocked_tasks: task.blocks.map(id => graph[id].title),
          impact: "high",
          recommendation: "Prioritize completion immediately"
        })
      }
      // Medium impact: Blocks 2 tasks
      else if (blocked_count === 2) {
        bottlenecks.push({
          task_id: task_id,
          title: task.title,
          status: task.status,
          complexity: task.complexity,
          blocks_count: blocked_count,
          blocked_tasks: task.blocks.map(id => graph[id].title),
          impact: "medium",
          recommendation: "Complete before starting blocked tasks"
        })
      }
    }
  }

  // Sort by impact (blocks_count descending)
  return bottlenecks.sort((a, b) => b.blocks_count - a.blocks_count)
}
```

**Output format:**
```json
{
  "bottlenecks": [
    {
      "task_id": "task-2",
      "title": "Implement API",
      "status": "in-progress",
      "complexity": 7,
      "blocks_count": 4,
      "blocked_tasks": [
        "Integration tests",
        "Performance tests",
        "API documentation",
        "Frontend integration"
      ],
      "impact": "high",
      "recommendation": "Prioritize completion immediately - blocking 4 tasks"
    }
  ]
}
```

### 4. Parallel Opportunity Discovery

**Find groups of tasks that can run concurrently:**

```javascript
function discover_parallel_opportunities(graph) {
  opportunities = []

  // Group tasks by dependency level
  levels = {}
  for (task_id in graph) {
    level = graph[task_id].level
    if (!levels[level]) levels[level] = []
    levels[level].push(task_id)
  }

  // Analyze each level for parallel potential
  for (level in levels) {
    tasks = levels[level]

    // Only levels with 2+ tasks can be parallel
    if (tasks.length > 1) {
      // Check if tasks have interdependencies
      parallel_group = []

      for (task_id in tasks) {
        // Check if this task blocks any other in same level
        interdependent = tasks.some(other_id =>
          other_id !== task_id &&
          graph[task_id].blocks.includes(other_id)
        )

        if (!interdependent) {
          parallel_group.push(task_id)
        }
      }

      // If 2+ tasks can run parallel
      if (parallel_group.length > 1) {
        // Calculate time saved
        total_complexity = parallel_group.reduce((sum, id) =>
          sum + graph[id].complexity, 0
        )
        max_complexity = Math.max(...parallel_group.map(id =>
          graph[id].complexity
        ))
        time_saved = total_complexity - max_complexity

        opportunities.push({
          level: level,
          task_count: parallel_group.length,
          task_ids: parallel_group,
          task_titles: parallel_group.map(id => graph[id].title),
          total_complexity: total_complexity,
          parallel_time: max_complexity,
          time_saved: time_saved,
          efficiency_gain: (time_saved / total_complexity) * 100
        })
      }
    }
  }

  return opportunities.sort((a, b) => b.time_saved - a.time_saved)
}
```

**Output format:**
```json
{
  "opportunities": [
    {
      "level": 0,
      "task_count": 2,
      "task_ids": ["task-1", "task-3"],
      "task_titles": [
        "Create database schema",
        "Create UI components"
      ],
      "total_complexity": 11,
      "parallel_time": 6,
      "time_saved": 5,
      "efficiency_gain": 45,
      "message": "Running these 2 tasks in parallel saves 45% time"
    }
  ]
}
```

### 5. Circular Dependency Detection

**Detect and report circular dependencies:**

```javascript
function detect_circular_dependencies(graph) {
  cycles = []

  function dfs(node_id, path, visited) {
    // Cycle detected
    if (path.includes(node_id)) {
      cycle_start = path.indexOf(node_id)
      cycle = path.slice(cycle_start).concat([node_id])
      cycles.push(cycle)
      return
    }

    // Already fully explored
    if (visited.includes(node_id)) return

    visited.push(node_id)
    path.push(node_id)

    // Visit all tasks this blocks
    for (next_id in graph[node_id].blocks) {
      dfs(next_id, path.slice(), visited)
    }
  }

  // Start DFS from each node
  for (start_id in graph) {
    dfs(start_id, [], [])
  }

  // Format results
  return cycles.map(cycle => ({
    cycle: cycle,
    tasks: cycle.map(id => graph[id].title),
    length: cycle.length,
    resolution_options: generate_resolution_options(cycle, graph)
  }))
}
```

**Output format:**
```json
{
  "circular_dependencies": [
    {
      "cycle": ["task-2", "task-5", "task-7", "task-2"],
      "tasks": [
        "Implement API",
        "Add caching",
        "Update API for cache",
        "Implement API"
      ],
      "length": 3,
      "resolution_options": [
        "Remove dependency: task-7 → task-2 (Update API doesn't actually need Implement API)",
        "Reorder: Move caching to separate feature",
        "Split: Divide task-2 into API-v1 and API-v2"
      ]
    }
  ]
}
```

### 6. Generate Resolution Strategy

**Create actionable resolution plan:**

```javascript
function generate_resolution_strategy(bottlenecks, critical_path, opportunities) {
  strategy = {
    immediate: [],   // Do now
    next: [],        // Do after immediate
    parallel: [],    // Can do simultaneously
    defer: []        // Can wait
  }

  // Priority 1: Critical path bottlenecks
  critical_bottlenecks = bottlenecks.filter(b =>
    critical_path.path.includes(b.task_id)
  )
  strategy.immediate = critical_bottlenecks

  // Priority 2: High-impact non-critical bottlenecks
  high_impact = bottlenecks.filter(b =>
    b.impact === "high" &&
    !critical_path.path.includes(b.task_id)
  )
  strategy.next = high_impact

  // Identify parallel opportunities
  strategy.parallel = opportunities.filter(o =>
    o.efficiency_gain > 30  // Only suggest if 30%+ gain
  )

  // Everything else can be deferred
  all_prioritized = [
    ...strategy.immediate.map(t => t.task_id),
    ...strategy.next.map(t => t.task_id)
  ]
  strategy.defer = Object.keys(graph).filter(id =>
    !all_prioritized.includes(id) &&
    graph[id].status === "pending"
  )

  return strategy
}
```

## Examples

### Example 1: Analyze Feature Dependencies

**User:** "Analyze dependencies for authentication feature"

**Actions:**
```javascript
1. build_dependency_graph(feature_id)
2. find_critical_path(graph)
3. find_bottlenecks(graph)
4. discover_parallel_opportunities(graph)

5. Return comprehensive analysis:
   "Feature: Authentication System
   Total tasks: 8

   Critical Path (68% of work):
   1. Database schema → 2. API implementation → 3. Integration tests

   Bottlenecks:
   - API implementation (blocks 4 tasks) - HIGH PRIORITY

   Parallel Opportunities:
   - Level 0: Database schema + UI components (45% time saved)
   - Level 2: Unit tests + Documentation (30% time saved)

   Recommendation: Complete API implementation first, then launch
   parallel execution for tests and documentation."
```

### Example 2: Find Bottlenecks

**User:** "What's blocking progress on feature X?"

**Actions:**
```javascript
1. build_dependency_graph(feature_id)
2. find_bottlenecks(graph)
3. filter by status = "pending" or "blocked"

4. Return: "Feature X has 2 bottlenecks:

   HIGH IMPACT:
   - Task: Implement authentication API
     Status: In Progress
     Blocks: 4 tasks (tests, UI, docs, integration)
     Recommendation: Prioritize completion

   MEDIUM IMPACT:
   - Task: Database migration
     Status: Pending
     Blocks: 2 tasks (API, reporting)
     Recommendation: Start this next"
```

### Example 3: Resolve Circular Dependency

**User:** "Fix circular dependencies in feature Y"

**Actions:**
```javascript
1. build_dependency_graph(feature_id)
2. detect_circular_dependencies(graph)

3. Return: "Circular dependency detected:
   Task A → Task B → Task C → Task A

   Resolution options:
   1. Remove dependency: C → A (likely unnecessary)
   2. Split Task A into A1 and A2
   3. Reorder: Complete A first, remove from C's dependencies

   Recommended: Option 1 - Remove C → A dependency
   Rationale: Task C (Add caching) doesn't truly depend on
   Task A (Initial API implementation)"
```

### Example 4: Find Critical Path

**User:** "Show critical path for feature Z"

**Actions:**
```javascript
1. find_critical_path(graph)

2. Return: "Critical Path (minimum completion time):

   Path: T1 → T2 → T4 → T6
   Tasks:
   1. Create database schema (Complexity: 5)
   2. Implement core API (Complexity: 8)
   3. Add authentication (Complexity: 7)
   4. Integration testing (Complexity: 6)

   Total complexity: 26 (68% of all work)
   Length: 4 tasks

   This path determines minimum feature completion time.
   Optimize by:
   - Parallelizing non-critical tasks
   - Prioritizing critical path tasks
   - Allocating best resources to critical tasks"
```

### Example 5: Discover Parallel Opportunities

**User:** "Find parallel opportunities in feature X"

**Actions:**
```javascript
1. discover_parallel_opportunities(graph)

2. Return: "Found 3 parallel opportunities:

   Opportunity 1 (Level 0): 45% time savings
   - Database schema (Complexity: 5)
   - UI components (Complexity: 6)
   → Run in parallel: 11 → 6 time units

   Opportunity 2 (Level 2): 33% time savings
   - Unit tests (Complexity: 4)
   - API documentation (Complexity: 5)
   - Frontend tests (Complexity: 3)
   → Run in parallel: 12 → 5 time units

   Opportunity 3 (Level 3): 20% time savings
   - E2E tests (Complexity: 6)
   - Performance tests (Complexity: 5)
   → Run in parallel: 11 → 6 time units

   Total potential time savings: 40%"
```

## Resolution Strategies

### Strategy 1: Remove Unnecessary Dependencies
**When:** Circular dependencies or over-constrained graph
**Action:** Analyze actual dependencies, remove false positives

### Strategy 2: Split Complex Tasks
**When:** Task is bottleneck with high complexity
**Action:** Break into smaller sub-tasks with clearer dependencies

### Strategy 3: Reorder Tasks
**When:** Dependencies prevent optimal parallel execution
**Action:** Restructure task order to maximize parallelism

### Strategy 4: Parallel Sub-batches
**When:** Batch has mixed dependencies
**Action:** Split batch into parallel-safe sub-batches

## Integration with Other Skills

**Works alongside:**
- **Task Orchestration Skill** - Provides dependency data for batching
- **Feature Orchestration Skill** - Analyzes feature-level dependencies

**Informs:**
- Parallel execution strategies
- Bottleneck prioritization
- Critical path optimization

## Visualization Helpers

**Generate ASCII graph:**
```
Database Schema (T1)
    ├─→ Backend API (T2)
    │       └─→ Integration Tests (T4)
    │
UI Components (T3)
    └─→ Integration Tests (T4)
```

**Generate dependency matrix:**
```
       T1  T2  T3  T4
T1     -   ✓   -   -
T2     -   -   -   ✓
T3     -   -   -   ✓
T4     -   -   -   -
```

## Best Practices

1. **Always check for circular dependencies** before execution
2. **Prioritize critical path** tasks
3. **Address bottlenecks** before they block progress
4. **Leverage parallel opportunities** for 40%+ time savings
5. **Visualize complex graphs** for user clarity
6. **Provide actionable recommendations** not just analysis
7. **Consider task complexity** in time estimates

## Token Efficiency

- Graph construction: ~400 tokens
- Critical path analysis: ~150 tokens
- Bottleneck detection: ~200 tokens
- Parallel opportunities: ~250 tokens
- **Total: 300-500 tokens per analysis**

## Success Metrics

- 100% circular dependency detection
- Identify critical path in < 2 seconds
- 95% accuracy in bottleneck identification
- 40% time savings from parallel opportunities
- Token usage 60% lower than manual analysis
