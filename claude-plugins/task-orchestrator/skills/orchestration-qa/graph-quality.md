# Execution Graph Quality Analysis

**Purpose**: Validate Planning Specialist's execution graph matches actual database dependencies and identifies all parallel opportunities.

**When**: After Planning Specialist completes task breakdown

**Entity**: Planning Specialist only

**Token Cost**: ~600-900 tokens

## Quality Metrics

This analysis measures three aspects of execution graph quality:

1. **Dependency Accuracy** (70% baseline): Do claimed dependencies match database?
2. **Parallel Completeness** (70% baseline): Are all parallel opportunities identified?
3. **Format Clarity** (95% baseline): Is graph notation clear and unambiguous?

**Target**: 95%+ overall quality score

## Analysis Workflow

### Step 1: Query Actual Dependencies

```javascript
// Get all tasks for feature
tasks = query_container(operation="overview", containerType="feature", id=featureId).tasks

// Query dependencies for each task
actualDependencies = {}
for task in tasks:
  deps = query_dependencies(taskId=task.id, includeTaskInfo=true)
  actualDependencies[task.id] = {
    title: task.title,
    blockedBy: deps.incoming,
    blocks: deps.outgoing
  }
}
```

### Step 2: Extract Planning Specialist's Graph

Parse the output to extract claimed execution structure:

```javascript
planningGraph = extractExecutionGraph(planningOutput)
// Should contain: batches, dependencies, parallel claims
```

### Step 3: Verify Dependency Accuracy

Compare claimed vs actual dependencies:

```javascript
for task in tasks:
  graphBlockers = planningGraph.dependencies[task.title] || []
  actualBlockers = actualDependencies[task.id].blockedBy.map(t => t.title)

  if (!arraysEqual(graphBlockers, actualBlockers)) {
    issues.push({
      task: task.title,
      expected: actualBlockers,
      found: graphBlockers,
      severity: "ALERT"
    })
  }
}
```

### Step 4: Verify Parallel Completeness

Check all parallel opportunities identified:

```javascript
// Independent tasks (no blockers) should all be in Batch 1
independentTasks = tasks.filter(t => actualDependencies[t.id].blockedBy.length == 0)

for task in independentTasks:
  if (!isInBatch(task, 1, planningGraph)) {
    issues.push({
      task: task.title,
      issue: "Independent task not in Batch 1",
      severity: "WARN"
    })
  }
}

// Tasks in same batch should have no dependencies between them
for batch in planningGraph.batches:
  for [taskA, taskB] in batch.pairs():
    if (actualDependencies[taskA.id].blocks.includes(taskB.id)) {
      issues.push({
        issue: `${taskA.title} blocks ${taskB.title} but both in same batch`,
        severity: "ALERT"
      })
    }
  }
}
```

### Step 5: Calculate Quality Score

```javascript
score = {
  dependencyAccuracy: (correct / total) * 100,
  parallelCompleteness: (identified / opportunities) * 100,
  formatClarity: hasGoodFormat ? 100 : 50,
  overall: average(dependencyAccuracy, parallelCompleteness, formatClarity)
}
```

## Report Template

```markdown
## 📊 Execution Graph Quality

**Overall Score**: [X]% (Baseline: 70% / Target: 95%+)

### Metrics
- Dependency Accuracy: [X]%
- Parallel Completeness: [Y]%
- Format Clarity: [Z]%

### Issues ([count] total)
🚨 **ALERT** ([count]): Critical dependency errors
- [Task A]: Expected blocked by [B], found [C]

⚠️ **WARN** ([count]): Missed parallel opportunities
- [Task D]: Independent but not in Batch 1

### Recommendations
1. [Most critical fix]
2. [Process improvement]
```

## When to Report

- **ALWAYS** after Planning Specialist
- **Full details** if score < 95%
- **Brief summary** if score >= 95%
