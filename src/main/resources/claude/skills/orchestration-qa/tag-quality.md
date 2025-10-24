# Tag Quality Analysis

**Purpose**: Validate Planning Specialist's tag strategy ensures complete specialist coverage.

**When**: After Planning Specialist completes task breakdown

**Entity**: Planning Specialist only

**Token Cost**: ~400-600 tokens

## Quality Metrics

1. **Tag Coverage** (100% baseline): Every task has tags that map to a specialist
2. **Tag Conventions** (90% baseline): Tags follow project conventions (reuse existing)
3. **Agent Mapping Coverage** (100% baseline): All tags map to specialists in agent-mapping.yaml

**Target**: 100% coverage, 90%+ conventions adherence

## Analysis Workflow

### Step 1: Load Project Tag Conventions

```javascript
// Get existing tags from project
projectTags = list_tags(entityTypes=["TASK", "FEATURE"])

// Load agent-mapping.yaml
agentMapping = Read(".taskorchestrator/agent-mapping.yaml").tagMappings
```

### Step 2: Analyze Task Tags

```javascript
tasks = query_container(operation="overview", containerType="feature", id=featureId).tasks

tagAnalysis = {
  totalTasks: tasks.length,
  tasksWithTags: 0,
  tasksWithoutTags: [],
  tagCoverage: [],
  conventionViolations: [],
  unmappedTags: []
}

for task in tasks:
  if (!task.tags || task.tags.length == 0) {
    tagAnalysis.tasksWithoutTags.push(task.title)
    continue
  }

  tagAnalysis.tasksWithTags++

  // Check each tag
  for tag in task.tags:
    // Does tag map to a specialist?
    if (!agentMapping[tag]) {
      tagAnalysis.unmappedTags.push({
        task: task.title,
        tag: tag,
        severity: "ALERT"
      })
    }

    // Is tag following conventions (existing tag)?
    if (!projectTags.includes(tag)) {
      tagAnalysis.conventionViolations.push({
        task: task.title,
        tag: tag,
        severity: "WARN",
        suggestion: "Use existing project tags or add to agent-mapping.yaml"
      })
    }
  }
}
```

### Step 3: Verify Specialist Coverage

```javascript
coverageCheck = {
  covered: 0,
  uncovered: []
}

for task in tasks:
  specialists = getSpecialistsForTask(task.tags, agentMapping)

  if (specialists.length == 0) {
    coverageCheck.uncovered.push({
      task: task.title,
      tags: task.tags,
      issue: "No specialist mapping found",
      severity: "ALERT"
    })
  } else {
    coverageCheck.covered++
  }
}
```

### Step 4: Calculate Quality Score

```javascript
score = {
  tagCoverage: (tagAnalysis.tasksWithTags / tagAnalysis.totalTasks) * 100,
  agentMappingCoverage: (coverageCheck.covered / tagAnalysis.totalTasks) * 100,
  conventionAdherence: (
    (tagAnalysis.tasksWithTags - tagAnalysis.conventionViolations.length) /
    tagAnalysis.tasksWithTags
  ) * 100,
  overall: average(tagCoverage, agentMappingCoverage, conventionAdherence)
}
```

## Report Template

```markdown
## 🏷️ Tag Quality Analysis

**Overall Score**: [X]% (Baseline: 90% / Target: 100%)

### Metrics
- Tag Coverage: [X]% ([Y]/[Z] tasks have tags)
- Agent Mapping Coverage: [X]% ([Y]/[Z] tasks map to specialists)
- Convention Adherence: [X]%

### Issues ([count] total)
🚨 **ALERT** ([count]): No specialist mapping
- [Task A]: Tags [tag1, tag2] don't map to any specialist

⚠️ **WARN** ([count]): Convention violations
- [Task B]: Tag "new-tag" not in project conventions

### Recommendations
1. Add tags to tasks: [list]
2. Update agent-mapping.yaml for: [tags]
3. Use existing tags instead of: [new tags]
```

## Critical Checks

### Check 1: Every Task Has Tags
Tasks without tags cannot be routed to specialists.

### Check 2: Every Tag Maps to Specialist
Tags that don't map to agent-mapping.yaml will fail routing.

### Check 3: Tags Follow Project Conventions
New tags should be rare; reuse existing tags when possible.

## When to Report

- **ALWAYS** after Planning Specialist
- **Full details** if score < 100%
- **Brief summary** if score == 100%
