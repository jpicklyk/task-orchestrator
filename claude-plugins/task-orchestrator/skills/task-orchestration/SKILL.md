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
- `query_sections` - Read sections with tag filtering (**Token optimized**)
- `manage_container` - Update task status, create tasks
- `query_workflow_state` - Check workflow state, cascade events, dependencies (**NEW**)
- `query_dependencies` - Analyze task dependencies
- `recommend_agent` - Route tasks to specialists
- `manage_sections` - Update task sections

## Section Tag Taxonomy

**When reading task/feature sections, use tag filtering for token efficiency:**

**Actionable Tags** (Implementation Specialist reads from tasks):
- **workflow-instruction** - Step-by-step implementation processes
- **checklist** - Validation checklists, completion criteria
- **commands** - Bash commands to execute
- **guidance** - Implementation patterns and best practices
- **process** - Workflow processes to follow
- **acceptance-criteria** - Definition of done, success conditions

**Contextual Tags** (Planning Specialist reads from features):
- **context** - Business context, user needs, dependencies
- **requirements** - Functional requirements, must-haves, constraints

**Reference Tags** (Read as needed):
- **reference** - Examples, patterns, reference material
- **technical-details** - Deep technical specifications

**Example - Efficient Task Section Reading:**
```javascript
// Implementation Specialist reads only actionable content from task
sections = query_sections(
  entityType="TASK",
  entityId=taskId,
  tags="workflow-instruction,checklist,commands,guidance,process,acceptance-criteria",
  includeContent=true
)
// Token cost: ~800-1,500 tokens (vs 3,000-5,000 with all sections)
// Savings: 45-60% token reduction

// Skip contextual sections (already in task description):
// - context (business context)
// - requirements (captured in description field)
```

**Note:** Implementation Specialist subagent automatically uses tag filtering. This reference is for direct tool usage.

## Dependency Cascade Detection (Automatic)

**Recommended Approach:** Use `query_workflow_state` to automatically check for dependency cascades and unblocked tasks.

```javascript
// After task completion, check for cascades
workflowState = query_workflow_state(
  containerType="task",
  id=taskId
)

// Check for detected cascade events (feature progression)
if (workflowState.detectedEvents.length > 0) {
  "✅ Task completion triggered cascade events:
  ${workflowState.detectedEvents.map(e => e.reason).join(', ')}

  Feature status may need to progress. Use Status Progression Skill."
}

// Check for unblocked dependencies (other tasks can now start)
dependencies = query_dependencies(
  taskId=taskId,
  direction="outgoing",
  includeTaskInfo=true
)

// Filter for now-unblocked tasks
for (dep of dependencies) {
  if (dep.toTask.status == "blocked" || dep.toTask.status == "pending") {
    "✅ Task ${dep.toTask.title} is now unblocked and ready to start!"
  }
}
```

**Benefits:**
- Automatic cascade detection based on config
- Dependency-aware unblocking
- Works with custom user workflows
- Handles complex prerequisite checking

## Status Progression Trigger Points (Manual Detection)

**Legacy Pattern:** Manual detection is still available but `query_workflow_state` is preferred.

**CRITICAL:** Never directly change task status. Always use Status Progression Skill for ALL status changes.

These are universal events that trigger status progression checks, regardless of the user's configured status flow:

| Event | When to Check | Detection Pattern | Condition | Action |
|-------|---------------|-------------------|-----------|--------|
| **work_started** | Specialist begins task implementation | Before specialist starts work | Task is in backlog/pending | Use Status Progression Skill to move to in-progress |
| **implementation_complete** | Code + tests written, sections updated | After specialist finishes coding | Summary populated (300-500 chars), sections updated | Use Status Progression Skill to move to next validation status |
| **tests_running** | Test execution begins | After triggering tests | Tests initiated | Use Status Progression Skill if needed |
| **tests_passed** | All tests successful | After test execution | `testResults.allPassed == true` | Use Status Progression Skill to move toward completion |
| **tests_failed** | Any tests failed | After test execution | `testResults.anyFailed == true` | Use Status Progression Skill (may move backward to in-progress) |
| **review_submitted** | Code submitted for review | After implementation complete | Code ready for review | Use Status Progression Skill to move to in-review |
| **review_approved** | Code review passed | After reviewer approval | Review completed with approval | Use Status Progression Skill to move forward (testing or completion) |
| **changes_requested** | Review rejected, needs rework | After reviewer rejection | Changes needed | Use Status Progression Skill (move backward to in-progress) |
| **blocker_detected** | Cannot proceed with work | When specialist encounters issue | External dependency or technical blocker | Use Status Progression Skill to move to blocked status |
| **task_cancelled** | Work no longer needed | User decides to cancel | Scope change | Use Status Progression Skill to move to cancelled |

### Detection Example: Implementation Complete

```javascript
// After specialist finishes code + tests
task = query_container(operation="get", containerType="task", id=taskId)

// Check implementation is complete
sectionsUpdated = true  // Specialist updated Implementation Details section
filesChanged = true     // Specialist created Files Changed section
summaryLength = task.summary?.length || 0

if (sectionsUpdated && filesChanged && summaryLength >= 300 && summaryLength <= 500) {
  // EVENT DETECTED: implementation_complete
  // Delegate to Status Progression Skill

  "Use Status Progression Skill to progress task status.
  Context: Implementation complete, summary populated (${summaryLength} chars)."

  // Status Progression Skill will:
  // 1. Call get_next_status(taskId, event="implementation_complete")
  // 2. get_next_status reads user's config.yaml
  // 3. Determines active flow based on task tags
  // 4. Recommends next status based on that flow
  // 5. Validates prerequisites
  // 6. Returns recommendation

  // Possible outcomes based on user's config:
  // - default_flow: in-progress → testing
  // - with_review: in-progress → in-review (code review first)
  // - documentation_flow: in-progress → in-review (no testing for docs)
  // - hotfix_flow: in-progress → completed (skip validation)
  // - bug_fix_flow: in-progress → testing
}
```

### Detection Example: Task Completion (Cascade Check)

```javascript
// After marking task complete, check for dependency cascade
completedTask = query_container(operation="get", containerType="task", id=taskId)

// Check if this unblocks other tasks
outgoingDeps = query_dependencies(
  taskId=taskId,
  direction="outgoing",
  includeTaskInfo=true
)

if (outgoingDeps.dependencies.length > 0) {
  // This task blocks other tasks
  // Check each dependent task to see if now unblocked

  for (dep of outgoingDeps.dependencies) {
    dependentTask = dep.toTask

    // Check all incoming dependencies for the dependent task
    incomingDeps = query_dependencies(
      taskId=dependentTask.id,
      direction="incoming",
      includeTaskInfo=true
    )

    // Count incomplete blockers
    incompleteBlockers = incomingDeps.dependencies.filter(d =>
      d.fromTask.status != "completed" && d.fromTask.status != "cancelled"
    ).length

    if (incompleteBlockers == 0) {
      // This task is now unblocked!
      notify(`Task "${dependentTask.title}" is now unblocked and ready to start.`)

      // Feature Orchestration Skill can now launch specialist for this task
    }
  }
}

// Also check if feature can progress (see Feature Orchestration Skill)
if (completedTask.featureId) {
  // Trigger Feature Orchestration Skill to check feature progress
  // (see Feature Orchestration Skill event: all_tasks_complete)
}
```

## Specialist Architecture (v2.0)

**Implementation Specialist (Haiku)** - Standard implementation (70-80% of tasks)
- Fast execution, cost-effective
- Loads domain Skills on-demand: backend-implementation, frontend-implementation, database-implementation, testing-implementation, documentation-implementation
- Escalates to Senior Engineer when blocked

**Senior Engineer (Sonnet)** - Complex problem solving (10-20%)
- Debugging, bug investigation, unblocking
- Performance optimization, tactical architecture

**Feature Architect (Opus)** - Feature design from ambiguous requirements
**Planning Specialist (Sonnet)** - Task decomposition with execution graphs

## Pre-Execution Checklist

**CRITICAL: Before launching specialists, ALWAYS check parent feature status.**

When starting task execution (Phase 3 of feature development):

1. **Check parent feature status:**
   ```javascript
   feature = query_container(operation="get", containerType="feature", id=featureId)

   if (feature.status == "planning" || feature.status == "draft") {
     // Feature status doesn't reflect execution phase
     // Delegate to Feature Orchestration Skill or Status Progression Skill

     "⚠️ Feature is still in ${feature.status}.
     Use Status Progression Skill to progress feature status before launching specialists.

     Cascade Event: tasks_ready_to_execute
     The skill will use get_next_status to determine the appropriate next status based on workflow config.

     Why: Feature status must reflect current development phase (not hardcoded - config determines next status)."

     // STOP - Don't proceed with specialist launches until feature status updated
     return
   }
   ```

2. **Identify ready tasks** (no blocking dependencies)
3. **Create execution batches** (parallel groups)
4. **Then proceed with specialist launches**

**Why this matters:**
- Feature status should accurately reflect the current development phase
- Cascade event system determines appropriate next status (config-driven)
- Missing this step causes feature to jump directly to completion, skipping execution phase statuses
- Different workflows may use different status names (default: "in-development", but config controls this)

**When to check:**
- At the start of Phase 3 (task execution)
- Before launching the first batch of specialists
- When resuming paused work

## Core Workflows

### 1. Dependency-Aware Batching

**High-level steps:**
1. Get all pending tasks: `query_container(operation="search", containerType="task", featureId="...", status="pending")`
2. For each task, check dependencies: `query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)`
3. Group into batches:
   - Batch 1: Tasks with NO incomplete blocking dependencies (parallel)
   - Batch 2: Tasks blocked only by Batch 1 (sequential)
   - Batch 3+: Continue until all tasks assigned
4. Detect circular dependencies (task blocked by another task that's also blocked)

See [examples.md](examples.md) for detailed batching examples and output format.

### 2. Parallel Specialist Launch

**High-level steps:**
1. For each task in parallel batch: `recommend_agent(taskId="...")`
2. Prepare launch instructions for orchestrator
3. Orchestrator launches specialists in parallel (using Task tool)

**Key:** Skill identifies WHICH specialists to launch; orchestrator does the actual launching.

See [examples.md](examples.md) for orchestrator instruction format.

### 3. Progress Monitoring

**High-level steps:**
1. Keep list of task IDs currently being worked on
2. Check each task status: `query_container(operation="overview", containerType="task", id="...")`
3. Analyze status distribution (completed, in-progress, blocked, pending)
4. Determine if batch complete
5. Report progress: "Batch X: Y/Z tasks complete (N%)"

### 4. Dependency Cascade

**High-level steps:**
1. After task completes, check if it unblocks others: `query_dependencies(taskId="...", direction="outgoing", includeTaskInfo=true)`
2. For each dependent task, check if ALL blockers complete
3. Report newly available tasks
4. Recommend launching next batch

See [examples.md](examples.md) for cascade detection pattern.

### 5. Specialist Routing

**High-level steps:**
1. Get recommendation: `recommend_agent(taskId="...")`
2. Use recommendation if provided
3. If no recommendation, use fallback (Implementation Specialist or ask user)

**Routing patterns:**
- [backend, frontend, database, testing, documentation] → Implementation Specialist (Haiku)
- [bug, error, blocker, complex] → Senior Engineer (Sonnet)
- [feature-creation] → Feature Architect (Opus)
- [planning, task-breakdown] → Planning Specialist (Sonnet)

### 6. Task Completion

**High-level steps:**
1. Create task summary section (300-500 chars)
2. Create files changed section
3. Use Status Progression Skill to mark complete (validates prerequisites)
4. Check for cascade (trigger next batch if available)

**Note:** Specialists typically mark their own tasks complete. This is for orchestrator-driven completion.

## Integration with Other Skills

**Works alongside:**
- **Feature Orchestration Skill** - Receives task execution requests
- **Dependency Orchestration Skill** - For complex dependency analysis
- **Status Progression Skill** - For ALL status changes

**Launches subagents:**
- All specialist subagents based on recommend_agent results

## Token Efficiency

- Use `overview` operations for batch status checks (95% token reduction)
- Batch specialist launches in single message
- Return minimal progress reports
- Query only necessary dependency information

**Savings:** Overview batch (1.2k tokens) vs Get each task fully (28k tokens for 10 tasks)

## Best Practices

1. **Always analyze dependencies** before execution
2. **Use recommend_agent** for all routing (never guess)
3. **Monitor parallel progress** actively
4. **Handle failures gracefully** without cascade
5. **Trigger cascades automatically** when batch completes
6. **Report clear progress** to users
7. **Maximum 3-5 parallel tasks** for manageable monitoring

## Success Metrics

- 40% reduction in feature completion time with parallelism
- 95% successful specialist routing on first attempt
- Zero circular dependencies in production
- Automated cascade triggering (no manual intervention)
- 500-900 token average per orchestration session

## Additional Resources

- **Detailed Examples**: See [examples.md](examples.md) for complete walkthroughs
- **Execution Patterns**: See [patterns.md](patterns.md) for strategies, configuration, and error handling
