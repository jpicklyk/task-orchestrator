# Event-Driven Status Progression Pattern

**Status**: ✅ IMPLEMENTED (v2.0)
**Last Updated**: 2025-01-24

**Purpose**: Define a generic, config-agnostic pattern for Skills to trigger status transitions without hardcoding status names.

**Implementation**: This pattern is fully implemented in:
- `src/main/resources/claude/skills/feature-orchestration/SKILL.md` (see Status Progression Trigger Points table)
- `src/main/resources/claude/skills/task-orchestration/SKILL.md` (see Status Progression Trigger Points table)
- Supporting files: `examples.md`, `troubleshooting.md`, `config-reference.md`, `patterns.md`
- Updated diagrams: `docs/diagrams/feature-orchestration-skill-flow.html`, `docs/diagrams/task-orchestration-skill-flow.html`

## Core Principle

**Skills detect workflow events, not status names. Status Progression Skill determines the appropriate status based on user's config.**

## Universal Workflow Events

### Feature Events (Static Trigger Points)

| Event | When Detected | Example Scenario |
|-------|---------------|------------------|
| `feature_created` | Feature entity created | Initial creation, might auto-progress from draft |
| `requirements_finalized` | Planning sections populated | User finalized requirements |
| `first_task_started` | Any task moves to execution phase | Implementation begins |
| `task_completed` | Individual task marked complete | Track progress |
| `all_tasks_complete` | All tasks completed/cancelled | Ready for validation |
| `tests_triggered` | Test suite initiated | Automated or manual testing |
| `tests_passed` | All tests successful | Quality validation succeeded |
| `tests_failed` | Any tests failed | Quality validation failed |
| `review_requested` | User requests review | Human validation needed |
| `review_approved` | Review gate passed | Human validated |
| `changes_requested` | Review rejected | Needs rework |
| `completion_requested` | User asks to mark complete | Final disposition |
| `blocker_detected` | External dependency issue | Cannot proceed |
| `feature_cancelled` | Scope change | Work stopped |

### Task Events (Static Trigger Points)

| Event | When Detected | Example Scenario |
|-------|---------------|------------------|
| `task_created` | Task entity created | Initial creation |
| `task_prioritized` | Task ready for work | Moved from backlog |
| `work_started` | Specialist begins implementation | Code writing starts |
| `implementation_complete` | Code + tests written | Work done, not validated |
| `tests_written` | Test code complete | Tests exist |
| `tests_running` | Test execution begins | Validation in progress |
| `tests_passed` | All tests successful | Quality validated |
| `tests_failed` | Any tests failed | Issues found |
| `review_submitted` | Code submitted for review | Awaiting human validation |
| `review_approved` | Review passed | Approved by reviewer |
| `changes_requested` | Review rejected | Rework needed |
| `completion_requested` | Ready to mark done | Final check |
| `blocker_detected` | Cannot proceed | External issue |
| `dependencies_incomplete` | Blocked by other tasks | Prerequisite not met |
| `task_cancelled` | No longer needed | Scope change |

## Event-Driven Skill Pattern

### Pattern Template

```javascript
// Skill detects event
onWorkflowEvent(event, entityType, entityId, context) {

  // Step 1: Call Status Progression Skill (NOT direct status change)
  result = useStatusProgressionSkill({
    event: event,
    entityType: entityType,
    entityId: entityId,
    context: context
  })

  // Step 2: Status Progression Skill internally:
  // - Calls get_next_status(entityId, entityType, event)
  // - get_next_status reads user's config.yaml
  // - Determines active flow based on entity tags
  // - Analyzes event in context of current position in flow
  // - Checks prerequisites for potential next status
  // - Returns recommendation

  // Step 3: Handle response
  if (result.ready) {
    // Status changed successfully
    notify(`${entityType} moved to ${result.appliedStatus}`)
    notify(`Active flow: ${result.activeFlow}`)

    // Check for cascading events
    checkCascadingEvents(result.appliedStatus)
  }
  else if (result.blocked) {
    // Prerequisites not met
    notify(`Cannot progress: ${result.blockers}`)
    notify(`To proceed: ${result.suggestions}`)
  }
  else if (result.terminal) {
    // Already at terminal status
    notify(`${entityType} is ${result.terminalStatus} (no further progression)`)
  }
}
```

## Feature Orchestration Skill - Event Integration

### Trigger 1: First Task Started

```javascript
// Event Detection
onTaskStatusChange(taskId, oldStatus, newStatus) {
  if (isExecutionPhase(newStatus) && !isExecutionPhase(oldStatus)) {
    // Task just entered execution phase (e.g., pending → in-progress)

    // Check if this is first task for the feature
    feature = query_container(operation="overview", containerType="feature", id=task.featureId)

    inProgressCount = count(feature.tasks, status="in-progress")

    if (inProgressCount == 1) {
      // This is the FIRST task to start
      emitEvent("first_task_started", "feature", feature.id, {
        taskId: taskId,
        taskTitle: task.title
      })
    }
  }
}

// Event Handler
onEvent("first_task_started", featureId, context) {
  // Don't assume feature should move to "in-development"
  // Let Status Progression Skill + user's config decide

  result = useStatusProgressionSkill({
    event: "first_task_started",
    entityType: "feature",
    entityId: featureId,
    context: context
  })

  // Possible outcomes based on user's config:
  // - default_flow: planning → in-development
  // - rapid_prototype_flow: draft → in-development (might skip planning)
  // - custom_flow: might not change status at all

  if (result.ready) {
    notify(`Feature moved to ${result.appliedStatus} (first task started: ${context.taskTitle})`)
  }
}
```

### Trigger 2: All Tasks Complete

```javascript
// Event Detection
onTaskStatusChange(taskId, oldStatus, newStatus) {
  if (isTerminal(newStatus)) {
    // Task completed/cancelled

    feature = query_container(operation="overview", containerType="feature", id=task.featureId)

    pendingCount = feature.taskCounts.byStatus.pending || 0
    inProgressCount = feature.taskCounts.byStatus["in-progress"] || 0

    if (pendingCount == 0 && inProgressCount == 0) {
      // ALL tasks are now complete/cancelled
      emitEvent("all_tasks_complete", "feature", feature.id, {
        totalTasks: feature.taskCounts.total,
        completedTasks: feature.taskCounts.byStatus.completed
      })
    }
  }
}

// Event Handler
onEvent("all_tasks_complete", featureId, context) {
  result = useStatusProgressionSkill({
    event: "all_tasks_complete",
    entityType: "feature",
    entityId: featureId,
    context: context
  })

  // Possible outcomes based on user's config:
  // - default_flow: in-development → testing
  // - rapid_prototype_flow: in-development → completed (skip testing)
  // - with_review_flow: in-development → testing

  if (result.ready) {
    notify(`All ${context.totalTasks} tasks complete. Feature moved to ${result.appliedStatus}.`)

    // If moved to testing phase, trigger tests
    if (isValidationPhase(result.appliedStatus)) {
      triggerTests(featureId)
    }
  }
}
```

### Trigger 3: Tests Passed/Failed

```javascript
// Event Detection (from hooks or manual trigger)
onTestsComplete(featureId, testResults) {
  if (testResults.allPassed) {
    emitEvent("tests_passed", "feature", featureId, {
      totalTests: testResults.total,
      passed: testResults.passed
    })
  } else {
    emitEvent("tests_failed", "feature", featureId, {
      totalTests: testResults.total,
      failed: testResults.failed,
      failures: testResults.failures
    })
  }
}

// Event Handler - Tests Passed
onEvent("tests_passed", featureId, context) {
  result = useStatusProgressionSkill({
    event: "tests_passed",
    entityType: "feature",
    entityId: featureId,
    context: context
  })

  // Possible outcomes:
  // - default_flow: testing → validating
  // - with_review_flow: testing → validating → pending-review
  // - rapid_prototype_flow: might go straight to completed

  if (result.ready) {
    notify(`Tests passed (${context.passed}/${context.totalTests}). Feature moved to ${result.appliedStatus}.`)
  }
}

// Event Handler - Tests Failed
onEvent("tests_failed", featureId, context) {
  result = useStatusProgressionSkill({
    event: "tests_failed",
    entityType: "feature",
    entityId: featureId,
    context: context
  })

  // Possible outcomes (backward movement):
  // - testing → in-development (fix issues)
  // - config.allow_backward must be true

  if (result.ready) {
    notify(`Tests failed (${context.failed}/${context.totalTests}). Feature moved back to ${result.appliedStatus} for fixes.`)
    notify(`Failures: ${context.failures.join(", ")}`)
  }
}
```

### Trigger 4: Completion Requested

```javascript
// Event Detection
onUserRequest("complete feature", featureId) {
  emitEvent("completion_requested", "feature", featureId, {
    requestedBy: "user"
  })
}

// Event Handler
onEvent("completion_requested", featureId, context) {
  result = useStatusProgressionSkill({
    event: "completion_requested",
    entityType: "feature",
    entityId: featureId,
    context: context
  })

  if (result.ready) {
    // Ask user for final confirmation (even if prerequisites met)
    askUser(`All prerequisites met. Mark feature complete?`, {
      onYes: () => applyCompletion(featureId, result.appliedStatus),
      onNo: () => notify("Completion cancelled by user")
    })
  }
  else if (result.blocked) {
    notify(`Cannot complete feature:`)
    for (blocker of result.blockers) {
      notify(`  ❌ ${blocker}`)
    }
    notify(`\nSuggestions:`)
    for (suggestion of result.suggestions) {
      notify(`  → ${suggestion}`)
    }
  }
}
```

## Task Orchestration Skill - Event Integration

### Trigger 1: Work Started

```javascript
// Implementation Specialist begins task
onSpecialistStartsTask(taskId, specialistType) {
  emitEvent("work_started", "task", taskId, {
    specialistType: specialistType,
    timestamp: now()
  })
}

// Event Handler
onEvent("work_started", taskId, context) {
  result = useStatusProgressionSkill({
    event: "work_started",
    entityType: "task",
    entityId: taskId,
    context: context
  })

  // Possible outcomes based on user's config:
  // - default_flow: pending → in-progress
  // - with_review: pending → in-progress
  // - bug_fix_flow: pending → in-progress

  if (result.ready) {
    notify(`Task started by ${context.specialistType}. Status: ${result.appliedStatus}`)
  }
  else if (result.blocked) {
    // Likely blocked by dependencies
    notify(`Cannot start task: ${result.blockers}`)
    notify(`Suggestion: Complete blocking tasks first`)
  }
}
```

### Trigger 2: Implementation Complete

```javascript
// Specialist finishes writing code + tests
onSpecialistCompletesImplementation(taskId, implementationDetails) {
  emitEvent("implementation_complete", "task", taskId, {
    filesChanged: implementationDetails.filesChanged,
    testsWritten: implementationDetails.testsWritten,
    summaryLength: implementationDetails.summary.length
  })
}

// Event Handler
onEvent("implementation_complete", taskId, context) {
  result = useStatusProgressionSkill({
    event: "implementation_complete",
    entityType: "task",
    entityId: taskId,
    context: context
  })

  // Possible outcomes (CONFIG-DRIVEN!):
  // - default_flow: in-progress → testing
  // - with_review: in-progress → in-review
  // - documentation_flow: in-progress → in-review (no testing for docs)
  // - hotfix_flow: in-progress → completed (skip validation)

  if (result.ready) {
    notify(`Implementation complete. Task moved to ${result.appliedStatus}.`)

    // If moved to testing, run tests
    if (result.appliedStatus == "testing" || isValidationPhase(result.appliedStatus)) {
      runTests(taskId)
    }
  }
  else if (result.blocked) {
    // Likely summary missing or wrong length
    notify(`Cannot progress: ${result.blockers}`)
  }
}
```

### Trigger 3: Tests Passed

```javascript
// Tests complete successfully
onTestsComplete(taskId, testResults) {
  if (testResults.allPassed) {
    emitEvent("tests_passed", "task", taskId, {
      totalTests: testResults.total,
      duration: testResults.duration
    })
  }
}

// Event Handler
onEvent("tests_passed", taskId, context) {
  result = useStatusProgressionSkill({
    event: "tests_passed",
    entityType: "task",
    entityId: taskId,
    context: context
  })

  // Possible outcomes:
  // - default_flow: testing → completed
  // - with_review: testing → completed (if review already done)
  // - bug_fix_flow: testing → completed

  if (result.ready) {
    notify(`Tests passed (${context.totalTests} tests in ${context.duration}ms). Task ${result.appliedStatus}.`)

    // Check for dependency cascade
    checkDependencyCascade(taskId)
  }
}
```

### Trigger 4: Review Complete

```javascript
// Code review finished
onReviewComplete(taskId, reviewResult) {
  if (reviewResult.approved) {
    emitEvent("review_approved", "task", taskId, {
      reviewer: reviewResult.reviewer,
      comments: reviewResult.comments
    })
  } else {
    emitEvent("changes_requested", "task", taskId, {
      reviewer: reviewResult.reviewer,
      changesRequested: reviewResult.changesRequested
    })
  }
}

// Event Handler - Review Approved
onEvent("review_approved", taskId, context) {
  result = useStatusProgressionSkill({
    event: "review_approved",
    entityType: "task",
    entityId: taskId,
    context: context
  })

  // Possible outcomes:
  // - with_review: in-review → testing (need to run tests)
  // - documentation_flow: in-review → completed (docs don't need testing)

  if (result.ready) {
    notify(`Review approved by ${context.reviewer}. Task moved to ${result.appliedStatus}.`)
  }
}

// Event Handler - Changes Requested
onEvent("changes_requested", taskId, context) {
  result = useStatusProgressionSkill({
    event: "changes_requested",
    entityType: "task",
    entityId: taskId,
    context: context
  })

  // Backward movement (if allow_backward: true):
  // - in-review → in-progress (implement changes)

  if (result.ready) {
    notify(`Changes requested by ${context.reviewer}. Task moved back to ${result.appliedStatus}.`)
    notify(`Required changes:`)
    for (change of context.changesRequested) {
      notify(`  - ${change}`)
    }
  }
}
```

## Implementation Specialist - Event Integration

### Updated Self-Service Lifecycle

```javascript
// Implementation Specialist workflow

// 1. Read task
task = query_container(operation="get", containerType="task", id=taskId, includeSections=true)

// 2. Emit work_started event → triggers status progression
emitEvent("work_started", "task", taskId, {
  specialistType: "Implementation Specialist (Haiku)",
  skillLoaded: determineSkillFromTags(task.tags)
})
// Status Progression Skill handles: pending → in-progress (validates no blockers)

// 3. Perform implementation
writeCode()
writeTests()

// 4. Update sections
updateSections(implementationDetails, filesChanged)

// 5. Update summary (300-500 chars)
updateSummary(summary)

// 6. Emit implementation_complete event
emitEvent("implementation_complete", "task", taskId, {
  filesChanged: files,
  testsWritten: true,
  summaryLength: summary.length
})
// Status Progression Skill handles: in-progress → [next status]
// Next status determined by task tags and active flow:
//   - default_flow → testing
//   - with_review → in-review
//   - documentation_flow → in-review
//   - hotfix_flow → completed

// 7. If moved to testing, run tests
if (currentStatus == "testing") {
  runTests()
  // Emits tests_passed or tests_failed event
}

// 8. Return brief summary
return `Task implementation complete. Status: ${currentStatus}. Files changed: ${files.length}.`
```

## Status Progression Skill - Event Handling

### Updated Pattern

```markdown
# Status Progression Skill - Event-Driven

## Role
Interprets workflow events and determines appropriate status transitions based on user's config.

## Workflow

### User asks: "What's next?"
1. Call get_next_status(entityId, entityType)
2. Tool reads config, returns recommendation
3. Guide user

### Event occurs: "all_tasks_complete"
1. Receive event from Feature Orchestration Skill
2. Call get_next_status(entityId, entityType, event="all_tasks_complete")
3. Tool analyzes:
   - Current status (e.g., "in-development")
   - Active flow (determined by feature tags)
   - Event context ("all_tasks_complete")
   - Next status in flow (e.g., "testing")
   - Prerequisites (all tasks completed ✓)
4. Return recommendation:
   ready: true
   recommendedStatus: "testing"
   activeFlow: "default_flow"
   reason: "All tasks complete, ready for validation phase"
5. Apply transition via manage_container(setStatus)
6. Return result to Feature Orchestration Skill

### Validation fails at write-time
1. StatusValidator blocks transition
2. Return detailed error
3. Interpret error for user
4. Explain config rules
5. Guide resolution
```

## Benefits of Event-Driven Pattern

### 1. Config-Agnostic
Skills don't hardcode status names. They work with ANY user-defined flow.

### 2. Predictable Triggers
Universal events (like "all_tasks_complete") are static. Skills always know when to check.

### 3. Centralized Logic
Status Progression Skill + get_next_status handle ALL status determination. Skills just detect events.

### 4. Flexible Flows
Users can add custom flows without modifying Skill documentation.

### 5. Clear Separation
- Skills: Detect events, manage work
- Status Progression Skill: Interpret events, determine transitions
- get_next_status tool: Read config, analyze state
- StatusValidator: Enforce rules at write-time

## Summary: Static Trigger Points

### Features (7 primary triggers)
1. ✅ `first_task_started` - Work begins
2. ✅ `all_tasks_complete` - Implementation done
3. ✅ `tests_passed` - Validation succeeded
4. ✅ `tests_failed` - Validation failed
5. ✅ `review_approved` - Human validated (if applicable)
6. ✅ `completion_requested` - User wants to complete
7. ✅ `blocker_detected` - External issue

### Tasks (9 primary triggers)
1. ✅ `work_started` - Implementation begins
2. ✅ `implementation_complete` - Code + tests written
3. ✅ `tests_passed` - Tests succeeded
4. ✅ `tests_failed` - Tests failed
5. ✅ `review_submitted` - Awaiting review (if applicable)
6. ✅ `review_approved` - Review passed
7. ✅ `changes_requested` - Review failed, needs rework
8. ✅ `blocker_detected` - Cannot proceed
9. ✅ `task_cancelled` - No longer needed

These events are **universal and static** - they exist regardless of status names or flow configuration.
