# Status Progression - Validation Errors

This file contains common StatusValidator errors, prerequisite failures, and fix patterns. Load this when users encounter validation errors or ask about validation rules.

## Common Validation Rules

**From user's config.yaml:**

```yaml
status_validation:
  enforce_sequential: true/false    # Must follow flow order step-by-step?
  allow_backward: true/false        # Can move backwards in flow?
  allow_emergency: true/false       # Can jump to blocked/cancelled from any state?
  validate_prerequisites: true/false # Check business rules before transitions?
```

**How to check user's settings:**
```javascript
config = Read(".taskorchestrator/config.yaml")
rules = config.status_validation

// Explain their specific configuration
"Your config validation rules:
- enforce_sequential: {rules.enforce_sequential}
- allow_backward: {rules.allow_backward}
- allow_emergency: {rules.allow_emergency}
- validate_prerequisites: {rules.validate_prerequisites}"
```

---

## Flow Validation Errors

### Error: "Cannot skip statuses. Must transition through: in-progress"

**Meaning:** User tried to skip a status in the flow with `enforce_sequential: true`

**Example:**
```
Current: pending
Tried: testing
Error: Can't skip in-progress
```

**Cause:** Config has `enforce_sequential: true`

**Fix Options:**

**Option 1: Follow sequential flow**
```javascript
// Move step-by-step through the flow
manage_container(operation="setStatus", containerType="task",
  id="...", status="in-progress")  // First step

// Later:
manage_container(operation="setStatus", containerType="task",
  id="...", status="testing")      // Next step
```

**Option 2: Change config to allow skipping**
```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  enforce_sequential: false  # Now can skip statuses
```

**Explain to user:**
```
StatusValidator blocked this because YOUR config has:
  enforce_sequential: true

You tried: pending → testing (skipped in-progress)

Your options:
1. Follow sequential flow: pending → in-progress → testing
2. Change your config: Set enforce_sequential: false in config.yaml

Recommended:
manage_container(operation="setStatus", containerType="task",
  id="...", status="in-progress")
```

### Error: "Backward transitions not allowed. Cannot move from testing to in-progress"

**Meaning:** User tried to move backwards with `allow_backward: false`

**Example:**
```
Current: testing
Tried: in-progress (backward)
Error: Backward movement disabled
```

**Cause:** Config has `allow_backward: false`

**Fix Options:**

**Option 1: Move forward only**
```javascript
// Can't go back - must progress forward or use emergency transition
manage_container(operation="setStatus", containerType="task",
  id="...", status="completed")  // Forward

// OR use emergency transition:
manage_container(operation="setStatus", containerType="task",
  id="...", status="cancelled")  // Emergency (if allow_emergency: true)
```

**Option 2: Enable backward movement**
```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  allow_backward: true  # Now can move backwards for rework
```

**Explain to user:**
```
StatusValidator blocked this because YOUR config has:
  allow_backward: false

You tried: testing → in-progress (backward movement)

Your options:
1. Move forward only: testing → completed
2. Use emergency transition: testing → cancelled/on-hold
3. Change your config: Set allow_backward: true in config.yaml

Why backward is useful:
- Code review requested changes
- Found bugs during testing
- Iterative development
```

### Error: "Status not in defined flow: archived"

**Meaning:** User tried to transition to a status not in their current flow

**Example:**
```
Current flow: bug_fix_flow [pending, in-progress, testing, completed]
Tried: archived
Error: archived not in bug_fix_flow
```

**Cause:** The flow being used doesn't include the target status

**Fix Options:**

**Option 1: Use emergency transition (if configured)**
```javascript
// Check if status is in emergency_transitions
config = Read(".taskorchestrator/config.yaml")
emergencyStatuses = config.status_progression.tasks.emergency_transitions

// If archived is emergency status, it can be used from any state
manage_container(operation="setStatus", containerType="task",
  id="...", status="archived")
```

**Option 2: Use status from current flow**
```javascript
// Use terminal status from current flow
manage_container(operation="setStatus", containerType="task",
  id="...", status="completed")  // Terminal status in bug_fix_flow
```

**Option 3: Change task tags to use different flow**
```javascript
// Change tags to select flow that includes desired status
manage_container(operation="update", containerType="task",
  id="...", tags="backend")  // Uses default_flow which has more statuses
```

**Explain to user:**
```
StatusValidator blocked this because:
- Your task uses: bug_fix_flow
- bug_fix_flow doesn't include: archived
- archived is not in emergency_transitions

Your options:
1. Use status from bug_fix_flow: completed
2. Add archived to emergency_transitions in config
3. Change task tags to use flow that includes archived
```

---

## Prerequisite Validation Errors

### Error: "Feature must have at least 1 task before IN_DEVELOPMENT"

**Meaning:** StatusValidator checked prerequisites, found 0 tasks when transitioning feature to in-development

**Cause:** Config has `validate_prerequisites: true` and feature transition rules require tasks

**Fix:**
```javascript
// Create at least one task for the feature
manage_container(operation="create", containerType="task",
  featureId="feature-uuid",
  title="Implement X",
  description="...",
  tags="backend")

// Then retry feature status transition
manage_container(operation="setStatus", containerType="feature",
  id="feature-uuid", status="in-development")
```

**Explain to user:**
```
StatusValidator blocked this because:
- Your config requires features to have tasks before IN_DEVELOPMENT
- Current task count: 0
- Required: At least 1

Fix:
1. Create at least one task for this feature
2. Then retry the status transition

This prevents empty features from moving to development.
```

### Error: "Cannot transition to COMPLETED: 1 task(s) not completed"

**Meaning:** Feature has incomplete tasks, can't mark feature complete

**Cause:** Config requires all tasks completed before feature completion

**Check current state:**
```javascript
overview = query_container(operation="overview", containerType="feature",
  id="feature-uuid")

// Returns task counts by status
overview.taskCounts.byStatus
// Example: {completed: 8, in-progress: 1, pending: 0}
```

**Fix Options:**

**Option 1: Complete remaining tasks**
```javascript
// Find incomplete tasks
tasks = query_container(operation="search", containerType="task",
  featureId="feature-uuid",
  status="!completed")

// Complete each task
manage_container(operation="setStatus", containerType="task",
  id="task-uuid", status="completed")
```

**Option 2: Cancel unnecessary tasks**
```javascript
// Cancel tasks that are no longer needed
manage_container(operation="setStatus", containerType="task",
  id="task-uuid", status="cancelled")
```

**Explain to user:**
```
StatusValidator blocked this because:
- Your config requires ALL tasks completed before feature completion
- Status breakdown: 8 completed, 1 in-progress, 0 pending
- Incomplete tasks: 1

Fix options:
1. Complete remaining task(s)
2. Cancel tasks that are no longer needed

Task completion ensures feature is fully implemented.
```

### Error: "Task summary must be 300-500 characters (current: 50)"

**Meaning:** Task summary too short for completion

**Cause:** Prerequisite validation requires summary field populated with 300-500 chars

**Fix:**
```javascript
// Update task summary before marking complete
manage_container(operation="update", containerType="task",
  id="task-uuid",
  summary="Implemented authentication API using JWT tokens. Added /login and /refresh endpoints with rate limiting. Created middleware for token validation. Updated user schema to store refresh tokens. All tests passing with 95% coverage. Documentation updated in API reference.")

// Then mark complete
manage_container(operation="setStatus", containerType="task",
  id="task-uuid", status="completed")
```

**Explain to user:**
```
StatusValidator blocked this because:
- Your config requires task summary for completion
- Current length: 50 characters
- Required: 300-500 characters

Fix:
Update task summary with comprehensive description:
- What was implemented
- Technical approach
- Testing results
- Any important notes

Summary captures work done for future reference.
```

### Error: "Cannot start task: 2 blocking dependencies not completed"

**Meaning:** Task has incomplete blocking dependencies

**Cause:** Task has BLOCKS dependencies that must complete first

**Check dependencies:**
```javascript
deps = query_dependencies(taskId="task-uuid", direction="incoming",
  type="BLOCKS", includeTaskInfo=true)

// Returns blocking tasks
deps.incoming.forEach(dep => {
  console.log(`Blocked by: ${dep.fromTask.title} (${dep.fromTask.status})`)
})
```

**Fix:**
```javascript
// Complete blocking tasks first
manage_container(operation="setStatus", containerType="task",
  id="blocking-task-uuid", status="completed")

// Then start this task
manage_container(operation="setStatus", containerType="task",
  id="task-uuid", status="in-progress")
```

**Explain to user:**
```
StatusValidator blocked this because:
- Task has 2 blocking dependencies
- Blockers must complete before this task can start

Blocking tasks:
1. "Database schema migration" (in-progress)
2. "API authentication design" (pending)

Fix:
Complete blocking tasks first, then retry this task.

Dependencies ensure correct order of execution.
```

---

## Emergency Transition Handling

### Using Emergency Transitions

**What are emergency transitions:**
- Statuses accessible from ANY state
- Bypass normal flow rules
- Used for real-world interruptions

**Common emergency statuses:**
```yaml
emergency_transitions:
  - blocked      # External blocker
  - on-hold      # Paused temporarily
  - cancelled    # No longer needed
  - deferred     # Postponed indefinitely
  - archived     # Archive for history
```

**Check if status is emergency:**
```javascript
config = Read(".taskorchestrator/config.yaml")
emergencyStatuses = config.status_progression.tasks.emergency_transitions

if (emergencyStatuses.includes("blocked")) {
  // Can transition to blocked from any state
  manage_container(operation="setStatus", containerType="task",
    id="...", status="blocked")
}
```

**When to use emergency transitions:**

**blocked:**
- External API not available
- Dependency on another team's work
- Technical blocker preventing progress

**on-hold:**
- Priority changed, pausing work temporarily
- Waiting for stakeholder decision
- Resource constraints

**cancelled:**
- Requirements changed, task no longer needed
- Duplicate work discovered
- Feature cancelled

**deferred:**
- Nice-to-have, postponing indefinitely
- Low priority, may revisit later

**Explain to user:**
```
Your task is blocked by external API unavailability.

Use emergency transition to: blocked
in-progress → blocked (emergency - can happen from any state)

Emergency transitions exist for real-world interruptions:
- blocked: External dependency or technical issue
- on-hold: Priority shift, paused temporarily
- cancelled: Task no longer needed
- deferred: Postponed indefinitely

These bypass normal flow rules because problems don't follow workflows.
```

---

## Terminal Status Handling

### What are Terminal Statuses

**Terminal statuses:**
- End states in the workflow
- No further progression possible
- Work is finished (successfully or unsuccessfully)

**Common terminal statuses:**
```yaml
terminal_statuses:
  - completed     # Successfully finished
  - cancelled     # Explicitly cancelled
  - deferred      # Postponed indefinitely
  - archived      # Archived for history
```

### Error: "Cannot transition from terminal status: completed"

**Meaning:** Tried to change status from a terminal state

**Example:**
```
Current: completed
Tried: in-progress
Error: Cannot progress from terminal status
```

**Why this happens:**
- Terminal statuses mark work as finished
- No valid next status exists
- Prevents accidental status changes on completed work

**Fix Options:**

**Option 1: Work is actually incomplete - reopen**
```javascript
// If work needs to be redone, create new task
manage_container(operation="create", containerType="task",
  featureId="...",
  title="Rework: [original task]",
  description="Additional work discovered after completion...",
  tags="backend")
```

**Option 2: Work is complete - no fix needed**
```
Work is correctly marked as completed.
If you need to make changes, create a new task for the additional work.
Completed tasks should remain completed for historical accuracy.
```

**Explain to user:**
```
StatusValidator blocked this because:
- Task is in terminal status: completed
- Terminal statuses cannot transition further
- Work is marked as finished

If you need to:
- Make small changes: Create new task "Update X"
- Major rework: Create new task "Rework X"
- Fix bug: Create bug fix task

Preserving completed status maintains accurate history.
```

---

## Config-Specific Error Patterns

### Checking User's Config for Error Context

**Always read user's actual config when interpreting errors:**

```javascript
config = Read(".taskorchestrator/config.yaml")

// Get validation rules
rules = config.status_validation

// Get current flow
taskFlows = config.status_progression.tasks
currentFlow = taskFlows.default_flow  // Or bug_fix_flow, etc.

// Get emergency transitions
emergencyStatuses = config.status_progression.tasks.emergency_transitions

// Explain in context of THEIR config
"StatusValidator blocked this because YOUR config has:
  enforce_sequential: {rules.enforce_sequential}
  allow_backward: {rules.allow_backward}

Your current flow: {currentFlow}
Emergency transitions available: {emergencyStatuses}"
```

### Config Customization Impact

**Different configs = different errors:**

**Strict config:**
```yaml
status_validation:
  enforce_sequential: true
  allow_backward: false
  allow_emergency: false
  validate_prerequisites: true
```
**Result:** More errors, stricter process

**Lenient config:**
```yaml
status_validation:
  enforce_sequential: false
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: false
```
**Result:** Fewer errors, flexible process

**Explain tradeoffs:**
```
Your config is strict (enforce_sequential: true, allow_backward: false).

This means:
✅ Enforces consistent process
✅ Prevents accidental status skipping
❌ Less flexibility for iterations
❌ More validation errors

You can customize this by editing .taskorchestrator/config.yaml
```

---

## Summary

**Progressive Loading Pattern:**
1. User encounters StatusValidator error
2. You load this file
3. Find matching error pattern
4. Read user's config for context
5. Explain error in terms of THEIR settings
6. Provide fix options specific to their situation

**Remember:**
- Always read user's config.yaml for context
- Explain errors in terms of their specific settings
- Provide multiple fix options when possible
- Explain WHY the rule exists (prerequisite purpose)
- Validation errors are FEATURES, not bugs - they prevent problems
