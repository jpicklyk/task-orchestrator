---
skill: status-progression
description: Configurable status workflow management with validation rules, automatic progression, and quality gate enforcement for features and tasks.
---

# Status Progression Skill

Intelligent status management with configurable workflows, validation rules, and automatic progression based on task/feature state.

## When to Use This Skill

**Activate for:**
- "Progress feature X to next status"
- "Update task status"
- "Check if ready for testing"
- "Move to next stage"
- "Validate status transition"
- "What's the next status?"

**This skill handles:**
- Status validation and progression
- Quality gate enforcement
- Automatic status transitions
- Custom workflow management
- Blocked status detection
- Review gate handling

## Tools Available

- `query_container` - Read features, tasks, status
- `manage_container` - Update status
- `query_dependencies` - Check blockers

## Status Workflows

### Feature Status Flow

**Default progression:**
```
planning
  ↓ (tasks created)
in-development
  ↓ (tasks in progress/some complete)
testing
  ↓ (all tasks complete, tests triggered)
validating
  ↓ (tests passed)
completed
```

**With review gate:**
```
planning → in-development → testing → validating → pending-review → completed
```

**With blocking:**
```
Any status → blocked (when blockers detected)
blocked → previous status (when blockers resolved)
```

### Task Status Flow

**Default progression:**
```
pending
  ↓ (specialist assigned/work started)
in-progress
  ↓ (work complete, needs testing)
testing
  ↓ (tests pass)
completed
```

**With blocking:**
```
pending/in-progress → blocked (when dependencies incomplete)
blocked → pending (when dependencies complete)
```

## Core Workflows

### 1. Validate Status Transition

**Check if transition is allowed:**

```javascript
function validate_status_transition(container_type, container_id, new_status) {
  // Get current state
  container = query_container(
    operation="get",
    containerType=container_type,
    id=container_id
  )

  // Note: Configuration workflows are documented in CLAUDE.md
  // MCP tools cannot load configuration files dynamically

  current_status = container.status

  // Get allowed flow (documented, not loaded dynamically)
  // For features: planning → in-development → testing → validating → completed
  // For tasks: pending → in-progress → testing → completed
  flow = get_default_flow(container_type)

  // Check if transition is valid
  current_index = flow.indexOf(current_status)
  new_index = flow.indexOf(new_status)

  // Validation rules
  if (new_index === -1) {
    return {
      valid: false,
      reason: `Status '${new_status}' not in configured flow`
    }
  }

  // Can't skip statuses (except going backwards)
  if (new_index > current_index + 1) {
    return {
      valid: false,
      reason: `Cannot skip from '${current_status}' to '${new_status}'`,
      suggestion: `Progress to '${flow[current_index + 1]}' first`
    }
  }

  // Check prerequisites for new status
  prerequisites = check_prerequisites(container_type, new_status, container)

  if (!prerequisites.met) {
    return {
      valid: false,
      reason: prerequisites.failures.join(", ")
    }
  }

  return {
    valid: true,
    message: `Transition from '${current_status}' to '${new_status}' allowed`
  }
}
```

### 2. Check Prerequisites

**Verify requirements before status change:**

```javascript
function check_prerequisites(container_type, status, container) {
  failures = []

  if (container_type === "feature") {
    switch (status) {
      case "in-development":
        // Must have tasks
        if (container.taskCounts.total === 0) {
          failures.push("Feature has no tasks")
        }
        break

      case "testing":
        // All tasks must be complete or in testing
        pending = container.taskCounts.byStatus.pending || 0
        in_progress = container.taskCounts.byStatus['in-progress'] || 0
        if (pending > 0 || in_progress > 0) {
          failures.push(`${pending + in_progress} tasks still in progress`)
        }
        break

      case "validating":
        // Note: Testing hooks cannot be triggered via MCP tools
        // Testing validation must be done externally
        // Document testing requirements but don't attempt to trigger
        break

      case "pending-review":
        // Must be in validating status first
        if (container.status !== "validating") {
          failures.push("Must validate before review")
        }
        break

      case "completed":
        // All validation gates must pass
        if (container.status !== "validating" &&
            container.status !== "pending-review") {
          failures.push("Must complete validation/review first")
        }
        break
    }
  }

  if (container_type === "task") {
    switch (status) {
      case "in-progress":
        // Check dependencies complete
        deps = query_dependencies(
          taskId=container.id,
          direction="incoming",
          type="BLOCKS"
        )

        incomplete_deps = deps.filter(d =>
          d.status !== "completed" && d.status !== "cancelled"
        )

        if (incomplete_deps.length > 0) {
          failures.push(`Blocked by ${incomplete_deps.length} incomplete tasks`)
        }
        break

      case "completed":
        // Must have summary
        if (!container.summary || container.summary.trim() === "") {
          failures.push("Task summary required before completion")
        }
        break
    }
  }

  return {
    met: failures.length === 0,
    failures: failures
  }
}
```

### 3. Automatic Progression

**Automatically progress status when conditions met:**

```javascript
function auto_progress_status(container_type, container_id) {
  // Note: Auto-progression configuration is documented in CLAUDE.md
  // Default behavior: recommend progression, don't auto-execute

  // Get container
  container = query_container(
    operation="get",
    containerType=container_type,
    id=container_id
  )

  current_status = container.status

  // Determine next status
  next_status = determine_next_status(container_type, container)

  if (!next_status) {
    return {action: "none", reason: "Already at final status"}
  }

  // Validate transition
  validation = validate_status_transition(
    container_type,
    container_id,
    next_status
  )

  if (!validation.valid) {
    return {
      action: "blocked",
      current: current_status,
      target: next_status,
      reason: validation.reason
    }
  }

  // Perform transition
  manage_container(
    operation="setStatus",
    containerType=container_type,
    id=container_id,
    status=next_status
  )

  return {
    action: "progressed",
    from: current_status,
    to: next_status
  }
}
```

### 4. Determine Next Status

**Calculate appropriate next status:**

```javascript
function determine_next_status(container_type, container) {
  // Use documented status flows (not dynamically loaded)
  flow = get_default_flow(container_type)

  current_index = flow.indexOf(container.status)

  // Already at end
  if (current_index === flow.length - 1) {
    return null
  }

  // Check for blocking conditions
  if (container_type === "feature") {
    // Check if any tasks blocked
    blocked = container.taskCounts.byStatus.blocked || 0
    if (blocked > 0) {
      return "blocked"
    }

    // Check task completion for progression
    total = container.taskCounts.total
    completed = container.taskCounts.byStatus.completed || 0
    in_progress = container.taskCounts.byStatus['in-progress'] || 0

    switch (container.status) {
      case "planning":
        if (total > 0) return "in-development"
        break

      case "in-development":
        if (in_progress > 0 || completed < total) return null  // Stay
        return "testing"

      case "testing":
        return "validating"  // Requires manual test trigger

      case "validating":
        // Note: Review gates are configured in CLAUDE.md
        // Default: skip review, move to completed
        return "completed"

      case "pending-review":
        return "completed"
    }
  }

  if (container_type === "task") {
    switch (container.status) {
      case "pending":
        return "in-progress"  // Manual start

      case "in-progress":
        return "testing"  // Manual completion trigger

      case "testing":
        return "completed"  // After tests pass
    }
  }

  // Default: next in flow
  return flow[current_index + 1]
}
```

### 5. Quality Gate Enforcement

**Enforce quality gates before status changes:**

```javascript
function enforce_quality_gates(container_type, container_id, target_status) {
  // Note: Quality gates cannot be enforced via MCP tools
  // Testing, security, and review hooks are external systems
  // This skill can only document requirements, not enforce them

  blocked_by = []

  // Document quality gate requirements
  if (target_status === "validating" || target_status === "completed") {
    // Note: Testing must be done externally
    // Document: "Ensure all tests pass before progressing to validating"
  }

  if (target_status === "completed") {
    // Check basic completion requirements via MCP tools
    container = query_container(
      operation="get",
      containerType=container_type,
      id=container_id
    )

    // Verify all tasks complete (for features)
    if (container_type === "feature") {
      overview = query_container(
        operation="overview",
        containerType="feature",
        id=container_id
      )

      pending = overview.taskCounts.byStatus.pending || 0
      in_progress = overview.taskCounts.byStatus['in-progress'] || 0

      if (pending > 0 || in_progress > 0) {
        blocked_by.push({
          gate: "task_completion",
          reason: `${pending + in_progress} tasks still incomplete`,
          action: "Complete all tasks before marking feature complete"
        })
      }
    }
  }

  return {
    allowed: blocked_by.length === 0,
    blocked_by: blocked_by
  }
}
```

### 6. Handle Blocked Status

**Manage blocked status detection and resolution:**

```javascript
function handle_blocked_status(container_type, container_id) {
  if (container_type === "task") {
    // Check dependencies
    deps = query_dependencies(
      taskId=container_id,
      direction="incoming",
      type="BLOCKS"
    )

    incomplete_deps = deps.filter(d =>
      d.status !== "completed" && d.status !== "cancelled"
    )

    if (incomplete_deps.length > 0) {
      // Set to blocked
      manage_container(
        operation="setStatus",
        containerType="task",
        id=container_id,
        status="blocked"
      )

      return {
        blocked: true,
        blockers: incomplete_deps.map(d => ({
          task_id: d.id,
          title: d.title,
          status: d.status
        }))
      }
    } else {
      // Can unblock
      manage_container(
        operation="setStatus",
        containerType="task",
        id=container_id,
        status="pending"
      )

      return {
        blocked: false,
        message: "Dependencies resolved, unblocked"
      }
    }
  }

  if (container_type === "feature") {
    // Check for blocked tasks
    feature = query_container(
      operation="overview",
      containerType="feature",
      id=container_id
    )

    blocked_count = feature.taskCounts.byStatus.blocked || 0

    if (blocked_count > 0) {
      manage_container(
        operation="setStatus",
        containerType="feature",
        id=container_id,
        status="blocked"
      )

      return {
        blocked: true,
        blocked_tasks: blocked_count
      }
    }
  }

  return {blocked: false}
}
```

## Configuration Integration

**Status workflows defined in `.taskorchestrator/config.yaml`:**

```yaml
status_progression:
  features:
    default_flow:
      - planning
      - in-development
      - testing
      - validating
      - completed

    with_review_flow:
      - planning
      - in-development
      - testing
      - validating
      - pending-review
      - completed

  tasks:
    default_flow:
      - pending
      - in-progress
      - testing
      - completed

    with_blocking:
      - pending
      - in-progress
      - blocked
      - testing
      - completed

quality_gates:
  testing:
    enabled: true
    blocking: true
  review:
    enabled: false
    blocking: false
  security:
    enabled: false
    blocking: true

automation:
  auto_progress_status: true
```

## Examples

### Example 1: Progress Feature Status

**User:** "Move authentication feature to testing"

**Actions:**
```javascript
1. validate_status_transition("feature", feature_id, "testing")
2. Check prerequisites:
   ✓ All tasks completed
   ✓ No blocked tasks
3. enforce_quality_gates("feature", feature_id, "testing")
4. Update status

5. Return: "Feature status updated: in-development → testing
   All prerequisites met. Ready for test execution."
```

### Example 2: Blocked by Quality Gate

**User:** "Complete feature X"

**Actions:**
```javascript
1. validate_status_transition("feature", feature_id, "completed")
2. enforce_quality_gates("feature", feature_id, "completed")
3. Quality gate failure detected

4. Return: "Cannot complete feature - quality gates failing:

   ✗ Testing Gate: 3 test failures
     - AuthenticationTest.testLoginFlow
     - AuthenticationTest.testLogoutFlow
     - AuthenticationTest.testTokenRefresh
   ✗ Coverage Gate: 65% (required: 80%)

   Action: Fix failing tests and add coverage before completion."
```

### Example 3: Automatic Progression

**User:** "Check if feature can progress"

**Actions:**
```javascript
1. auto_progress_status("feature", feature_id)
2. Current: in-development
3. All tasks completed
4. Next: testing

5. Return: "Feature automatically progressed:
   in-development → testing

   All tasks complete. Ready to trigger test suite."
```

### Example 4: Handle Blocked Task

**User:** "Why is task X blocked?"

**Actions:**
```javascript
1. handle_blocked_status("task", task_id)
2. Check dependencies
3. Found incomplete blockers

4. Return: "Task is blocked by 2 incomplete dependencies:

   1. Create database schema (Status: in-progress)
   2. Set up authentication (Status: pending)

   Task will automatically unblock when dependencies complete."
```

### Example 5: Custom Status Validation

**User:** "Move to in-progress"

**Actions:**
```javascript
1. validate_status_transition("task", task_id, "in-progress")
2. Check dependencies - 1 incomplete
3. Validation fails

4. Return: "Cannot move to in-progress - blocked by dependencies:

   Incomplete:
   - Database schema migration (Status: pending)

   Suggestion: Wait for blocker to complete, or remove dependency
   if not truly required."
```

## State Machine Visualization

```
FEATURE STATES:

   ┌──────────┐
   │ planning │
   └─────┬────┘
         │ (tasks created)
         ↓
   ┌─────────────────┐
   │ in-development  │←─┐
   └────────┬────────┘  │
            │ (tasks    │
            │  complete)│
            ↓           │ (issues found)
   ┌────────────┐      │
   │  testing   │──────┘
   └─────┬──────┘
         │ (tests triggered)
         ↓
   ┌─────────────┐
   │ validating  │
   └──────┬──────┘
          │ (tests passed)
          ↓
   ┌────────────────┐
   │ pending-review │ (optional)
   └────────┬───────┘
            │ (approved)
            ↓
   ┌───────────┐
   │ completed │
   └───────────┘

   Any state can transition to "blocked" if blockers detected
```

## Integration with Other Skills

**Works alongside:**
- **Feature Orchestration Skill** - Status progression for features
- **Task Orchestration Skill** - Status progression for tasks
- **Quality Gate Hooks** - Test/security validation

## Token Efficiency

- Status validation: ~100 tokens
- Prerequisites check: ~150 tokens
- Quality gate enforcement: ~200 tokens
- **Total: 200-400 tokens per progression**

## Best Practices

1. **Always validate** before status changes
2. **Check prerequisites** for each status
3. **Enforce quality gates** strictly
4. **Auto-progress** when enabled and safe
5. **Handle blocked status** proactively
6. **Provide clear** failure messages
7. **Suggest remediation** actions

## Success Metrics

- 100% validation before status changes
- Zero invalid status transitions
- Quality gates enforced consistently
- Automatic progression when configured
- Clear user feedback on blockers
