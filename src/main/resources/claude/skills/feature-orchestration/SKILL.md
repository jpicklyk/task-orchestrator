---
skill: feature-orchestration
description: Intelligent feature lifecycle management with smart routing, parallel execution planning, and quality gate enforcement. Replaces Feature Management Skill with enhanced capabilities.
---

# Feature Orchestration Skill

Comprehensive feature lifecycle management from creation through completion, with intelligent complexity assessment and automatic orchestration.

## When to Use This Skill

**Activate for:**
- "Create a feature for X"
- "What's next for feature Y?"
- "Complete feature Z"
- "Check feature progress"
- "Plan feature execution"

**This skill handles:**
- Feature creation with complexity assessment
- Task breakdown coordination
- Parallel execution planning
- Feature status progression
- Quality gate validation
- Feature completion

## Tools Available

- `query_container` - Read features, tasks, projects
- `manage_container` - Create/update features and tasks
- `query_templates` - Discover available templates
- `apply_template` - Apply templates to features
- `recommend_agent` - Route tasks to specialists
- `manage_sections` - Create feature documentation

## Status Progression Trigger Points

**CRITICAL:** Never directly change feature status. Always use Status Progression Skill for ALL status changes.

These are universal events that trigger status progression checks, regardless of the user's configured status flow:

| Event | When to Check | Detection Pattern | Condition | Action |
|-------|---------------|-------------------|-----------|--------|
| **first_task_started** | After any task status changes to execution phase | `query_container(operation="overview", containerType="feature", id=task.featureId)` | `taskCounts.byStatus["in-progress"] == 1` | Use Status Progression Skill to progress feature |
| **all_tasks_complete** | After any task marked completed/cancelled | `query_container(operation="overview", containerType="feature", id=task.featureId)` | `taskCounts.byStatus.pending == 0 && taskCounts.byStatus["in-progress"] == 0` | Use Status Progression Skill to progress feature |
| **tests_passed** | After test execution completes | External test hook or manual trigger | `testResults.allPassed == true` | Use Status Progression Skill with context: `{testsPass: true, totalTests: N}` |
| **tests_failed** | After test execution completes | External test hook or manual trigger | `testResults.anyFailed == true` | Use Status Progression Skill with context: `{testsFailed: true, failures: [...]}` |
| **review_approved** | After human review | User/external signal | Review completed with approval | Use Status Progression Skill |
| **changes_requested** | After human review | User/external signal | Review rejected, rework needed | Use Status Progression Skill (may move backward) |
| **completion_requested** | User asks to complete feature | Direct user request | User says "complete feature" | Use Status Progression Skill, ask user to confirm if prerequisites met |

### Detection Example: All Tasks Complete

```javascript
// After a task is marked complete, check feature progress
task = query_container(operation="get", containerType="task", id=taskId)

if (task.featureId) {
  // Query feature to check all task statuses
  feature = query_container(
    operation="overview",
    containerType="feature",
    id=task.featureId
  )

  // Detect event: all tasks complete
  pending = feature.taskCounts.byStatus.pending || 0
  inProgress = feature.taskCounts.byStatus["in-progress"] || 0

  if (pending == 0 && inProgress == 0) {
    // EVENT DETECTED: all_tasks_complete
    // Delegate to Status Progression Skill

    "Use Status Progression Skill to progress feature status.
    Context: All ${feature.taskCounts.total} tasks complete
    (${feature.taskCounts.byStatus.completed} completed,
    ${feature.taskCounts.byStatus.cancelled || 0} cancelled)."

    // Status Progression Skill determines next status based on config
  }
}
```

For more detection examples, see [examples.md](examples.md)

## Core Workflows

### 1. Smart Feature Creation

**Assess complexity first:**
- Simple: Request < 200 chars, clear purpose, expected tasks < 3
- Complex: Multiple components, integration requirements, expected tasks ≥ 5

**For SIMPLE features:**
1. Discover templates: `query_templates(operation="list", targetEntityType="FEATURE", isEnabled=true)`
2. Create feature with templates: `manage_container(operation="create", containerType="feature", status="draft", ...)`
3. Create 2-3 tasks directly (tasks start in "backlog" status)
4. Use Status Progression Skill to move feature: draft → planning

**For COMPLEX features:**
- Recommend launching Feature Architect subagent for detailed planning
- Feature Architect will formalize requirements, discover templates, create structure
- Then Planning Specialist breaks into domain-isolated tasks

See [examples.md](examples.md) for detailed scenarios.

### 2. Task Breakdown Coordination

**After feature creation:**
- Simple (< 5 tasks): Create tasks directly with templates
- Complex (5+ tasks, multiple domains): Launch Planning Specialist subagent

Planning Specialist creates:
- Domain-isolated tasks (database, backend, frontend, testing, docs)
- Dependencies between tasks
- Execution graph with batches

### 3. Feature Progress Tracking

**Check feature status:**
```javascript
feature = query_container(operation="overview", containerType="feature", id="...")

// Analyze task counts
if (all tasks completed) {
  // Use Status Progression Skill to move to testing/completion
} else if (has blocked tasks) {
  // Address blockers first
} else if (has pending tasks) {
  // Launch next batch
}
```

### 4. Quality Gate Validation

**Prerequisites Enforced Automatically:**

Status Progression Skill validates prerequisites when changing status. You don't manually check these - just attempt the status change and handle validation errors.

| Transition | Prerequisites | Enforced By |
|------------|---------------|-------------|
| planning → in-development | ≥1 task created | StatusValidator |
| in-development → testing | All tasks completed/cancelled | StatusValidator |
| testing/validating → completed | All tasks completed/cancelled | StatusValidator |

**Validation Pattern:**
1. Use Status Progression Skill to change status
2. If validation fails → Skill returns detailed error
3. Resolve blocker (create tasks, complete dependencies, etc.)
4. Retry via Status Progression Skill

For common validation errors and solutions, see [troubleshooting.md](troubleshooting.md)

### 5. Feature Completion

**Tool Orchestration Pattern:**
```javascript
// Step 1: Check all tasks complete
overview = query_container(operation="overview", containerType="feature", id="...")

// Step 2: Create feature summary section (optional but recommended)
manage_sections(operation="add", entityType="FEATURE", ...)

// Step 3: Use Status Progression Skill to mark complete
"Use Status Progression Skill to mark feature as completed"

// Skill validates prerequisites automatically
// If validation fails, returns detailed error with what's missing
```

## Status Progression Flow (Config-Driven)

**The actual status flow depends on the user's `.taskorchestrator/config.yaml` and feature tags.**

**Common flows:**
- **default_flow**: draft → planning → in-development → testing → validating → completed
- **rapid_prototype_flow**: draft → in-development → completed (skip testing)
- **with_review_flow**: ... → validating → pending-review → completed

**How flow is determined:**
1. Status Progression Skill calls `get_next_status(featureId)`
2. Tool reads `.taskorchestrator/config.yaml`
3. Tool matches feature tags against `flow_mappings`
4. Tool recommends next status from matched flow
5. StatusValidator validates prerequisites at write-time

**Your role:** Detect events (table above), delegate to Status Progression Skill, let config determine actual statuses.

For flow details and examples, see [config-reference.md](config-reference.md)

## Token Efficiency

- Use `operation="overview"` for status checks (90% token reduction vs full get)
- Batch operations where possible
- Return brief status updates, not full context
- Delegate complex work to subagents
- Query only necessary task fields

**Example:**
```javascript
// Efficient: 1.2k tokens
query_container(operation="overview", containerType="feature", id="...")

// Inefficient: 18k tokens
query_container(operation="get", containerType="feature", id="...", includeSections=true)
```

## Integration with Other Skills

**Works alongside:**
- **Task Orchestration Skill** - Delegates task execution
- **Dependency Orchestration Skill** - For complex dependency analysis
- **Status Progression Skill** - For status management (ALWAYS use this for status changes)

**Launches subagents:**
- **Feature Architect** - Complex feature formalization
- **Planning Specialist** - Complex task breakdown

## Best Practices

1. **Always assess complexity** before creating features
2. **Always discover templates** via `query_templates` before creation
3. **Use overview operations** for status checks (token efficiency)
4. **Batch task creation** when creating multiple tasks
5. **Delegate to Status Progression Skill** for ALL status changes
6. **Return concise summaries** to orchestrator
7. **Delegate to subagents** when complexity exceeds threshold
8. **Monitor feature progress** after task completions

## Success Metrics

- Simple features created in < 5 tool calls
- 40% time savings with parallel execution
- 60% token reduction vs old Feature Management skill
- 95% successful quality gate validation
- Zero manual intervention for standard workflows

## Additional Resources

- **Detailed Examples**: See [examples.md](examples.md) for complete walkthroughs
- **Error Handling**: See [troubleshooting.md](troubleshooting.md) for validation errors
- **Configuration**: See [config-reference.md](config-reference.md) for flow customization
