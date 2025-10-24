# Parallel Opportunity Detection

**Purpose**: Identify missed parallelization opportunities in task execution.

**When**: Optional post-execution (controlled by enableEfficiencyAnalysis parameter)

**Applies To**: Task Orchestration Skill, Planning Specialist

**Token Cost**: ~400-600 tokens

## Parallel Opportunity Types

### Type 1: Independent Tasks Not Batched

**Opportunity**: Tasks with no dependencies can run simultaneously

**Detection**:
```javascript
independentTasks = tasks.filter(t =>
  query_dependencies(taskId=t.id).incoming.length == 0 &&
  t.status == "pending"
)

if (independentTasks.length >= 2 && !launchedInParallel) {
  return {
    type: "Independent tasks not batched",
    tasks: independentTasks.map(t => t.title),
    opportunity: `${independentTasks.length} tasks can run simultaneously`,
    impact: "Sequential execution when parallel possible",
    recommendation: "Use Task Orchestration Skill to batch parallel tasks"
  }
}
```

### Type 2: Tasks with Same Dependencies Not Grouped

**Opportunity**: Tasks blocked by the same tasks can run in parallel after blockers complete

**Detection**:
```javascript
// Group tasks by their blockers
tasksByBlockers = groupByBlockers(tasks)

for (blockerKey, taskGroup in tasksByBlockers) {
  if (taskGroup.length >= 2 && !inSameBatch(taskGroup)) {
    return {
      type: "Tasks with same dependencies not grouped",
      tasks: taskGroup.map(t => t.title),
      sharedBlockers: parseBlockers(blockerKey),
      opportunity: `${taskGroup.length} tasks can run parallel after blockers complete`,
      recommendation: "Batch these tasks together"
    }
  }
}
```

### Type 3: Sequential Specialist Launches When Parallel Possible

**Opportunity**: Multiple specialists launched one-by-one instead of in parallel

**Detection**:
```javascript
if (launchedSpecialists.length >= 2 && !launchedInParallel) {
  // Check if they have no dependencies between them
  noDependencies = !hasBlockingRelationships(launchedSpecialists)

  if (noDependencies) {
    return {
      type: "Sequential specialist launches",
      specialists: launchedSpecialists,
      opportunity: "Launch specialists in parallel",
      impact: "Sequential execution increases total time",
      recommendation: "Use Task tool multiple times in single message"
    }
  }
}
```

### Type 4: Domain-Isolated Tasks Not Parallelized

**Opportunity**: Backend + Frontend + Database tasks can often run in parallel

**Detection**:
```javascript
domains = {
  database: tasks.filter(t => t.tags.includes("database")),
  backend: tasks.filter(t => t.tags.includes("backend")),
  frontend: tasks.filter(t => t.tags.includes("frontend"))
}

// Check typical dependency pattern: database → backend → frontend
// BUT: If each domain has multiple tasks, those CAN run in parallel

for (domain, domainTasks in domains) {
  if (domainTasks.length >= 2 && !parallelizedWithinDomain(domainTasks)) {
    return {
      type: "Domain tasks not parallelized",
      domain: domain,
      tasks: domainTasks.map(t => t.title),
      opportunity: `${domainTasks.length} ${domain} tasks can run parallel`,
      recommendation: "Launch domain specialists in parallel"
    }
  }
}
```

## Analysis Workflow

```javascript
parallelOpportunities = []

// Check each opportunity type
checkIndependentTasks()
checkSameDependencyGroups()
checkSequentialLaunches()
checkDomainParallelization()

// Calculate potential time savings
if (parallelOpportunities.length > 0) {
  estimatedTimeSavings = calculateTimeSavings(parallelOpportunities)

  return {
    opportunitiesFound: parallelOpportunities.length,
    opportunities: parallelOpportunities,
    estimatedSavings: estimatedTimeSavings,
    recommendation: "Use Task Orchestration Skill for parallel batching"
  }
}
```

## Report Template

```markdown
## ⚡ Parallel Opportunity Detection

**Opportunities Found**: [count]
**Estimated Time Savings**: [X]% (parallel vs sequential)

### Opportunities

**ℹ️ INFO**: Independent tasks not batched
- Tasks: [Task A, Task B, Task C]
- Opportunity: 3 tasks can run simultaneously (no dependencies)
- Impact: Sequential execution taking 3x longer than necessary

**ℹ️ INFO**: Domain tasks not parallelized
- Domain: backend
- Tasks: [Task D, Task E]
- Opportunity: 2 backend tasks can run parallel

### Recommendations
1. Use Task Orchestration Skill for dependency-aware batching
2. Launch specialists in parallel: `Task(Backend Engineer, task1)` + `Task(Backend Engineer, task2)` in single message
```

## When to Report

- **Only if** enableEfficiencyAnalysis=true
- **INFO** level (optimizations, not violations)
- Most valuable after task execution workflows

## Integration with Task Orchestration Skill

This analysis helps validate that Task Orchestration Skill is identifying all parallel opportunities:

```javascript
// If Task Orchestration Skill was used
if (usedTaskOrchestrationSkill) {
  // Check if it identified all opportunities
  identifiedOpportunities = extractBatchStructure(output)
  missedOpportunities = parallelOpportunities.filter(o =>
    !identifiedOpportunities.includes(o)
  )

  if (missedOpportunities.length > 0) {
    return {
      severity: "WARN",
      issue: "Task Orchestration Skill missed parallel opportunities",
      missed: missedOpportunities,
      recommendation: "Update task-orchestration skill workflow"
    }
  }
}
```
