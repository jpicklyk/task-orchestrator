---
name: error-handling
description: Error handling workflows, prerequisite validation failures, blocking scenarios, and error message anatomy
version: "2.0.0"
---

# Error Handling in Task Orchestration

## Example Blocking Scenarios

When working with Task Orchestration, various blocking scenarios can occur. Here's how to recognize and resolve them:

```
Scenario 1: Feature completion attempt with incomplete tasks
Error: "Cannot transition to COMPLETED: 3 task(s) not completed.
       Incomplete tasks: \"Fix bug\" (IN_PROGRESS), \"Add tests\" (PENDING), \"Update docs\" (PENDING)"
Action: Complete all tasks first, or remove tasks from feature if not needed
Solution: Use Task Orchestration Skill to execute remaining tasks

Scenario 2: Task completion without summary
Error: "Cannot transition to COMPLETED: Task summary must be 300-500
       characters (current: 45 characters)"
Action: Populate task summary field with 255+ more characters
Solution: manage_container(operation="update", id="...", summary="[300-500 char description]")

Scenario 3: Task start with blocking dependencies
Error: "Cannot transition to IN_PROGRESS: Task is blocked by 2
       incomplete task(s): \"Setup database\" (PENDING), \"Create schema\" (IN_PROGRESS)"
Action: Complete blocking tasks first or remove BLOCKS dependencies
Solution: Execute blockers first via Task Orchestration Skill, or remove dependencies

Scenario 4: Feature testing without tasks
Error: "Cannot transition to TESTING: Feature must have at least 1 task"
Action: Create tasks for the feature before moving to testing
Solution: Use Task Orchestration Skill to break down feature into tasks

Scenario 5: Task summary too long
Error: "Cannot transition to COMPLETED: Task summary must be 300-500
       characters (current: 612 characters)"
Action: Shorten summary by 112+ characters to meet limit
Solution: manage_container(operation="update", id="...", summary="[condensed version]")

Scenario 6: Feature in-development without tasks
Error: "Cannot transition to IN_DEVELOPMENT: Feature must have at least 1 task"
Action: Create initial task(s) before starting development
Solution: Use Task Orchestration Skill to create tasks, then retry status change
```

## Prerequisite Validation Failures

Status Progression Skill performs automatic prerequisite validation. When validation fails:

### Error Handling Workflow

1. Status Progression Skill returns detailed validation error
2. Parse the error message for:
   - What prerequisite failed (summary length, task count, dependencies)
   - Current state (e.g., "summary is 45 chars")
   - Required state (e.g., "must be 300-500 chars")
   - Specific blocking items (task names, dependency names)
3. Explain the requirement to user with context
4. Suggest concrete remediation (e.g., "Add 255 more characters to summary")
5. User resolves blocker OR you fix directly (with user permission)
6. Retry status change via Status Progression Skill
7. Repeat until prerequisites met

### Common Prerequisite Errors and Solutions

| Error | Cause | Solution |
|-------|-------|----------|
| "Task summary must be 300-500 characters (current: 45)" | Summary too short | `manage_container(operation="update", id="...", summary="[add 255+ chars]")` |
| "Task summary must be 300-500 characters (current: 612)" | Summary too long | `manage_container(operation="update", id="...", summary="[shorten by 112+ chars]")` |
| "Feature must have at least 1 task" | Empty feature | Create tasks via Task Orchestration Skill before IN_DEVELOPMENT |
| "Cannot transition: 3 task(s) not completed" | Incomplete tasks | Complete tasks first OR remove from feature if not needed |
| "Task is blocked by 2 incomplete task(s): \"X\", \"Y\"" | Blocker dependencies | Execute blocking tasks first via Task Orchestration Skill |
| "All tasks must be completed before feature testing" | Feature not ready | Use Task Orchestration Skill to complete remaining tasks |

## Error Message Anatomy

Understanding error messages helps you quickly identify the issue and solution:

```
Error: "Cannot transition to COMPLETED: Task summary must be 300-500 characters (current: 45 characters)"
       ↑                    ↑                ↑                                              ↑
   Action blocked    Target status    Prerequisite requirement                    Current state

This tells you:
- What was attempted: Transition to COMPLETED
- What's blocking: Summary validation
- What's required: 300-500 characters
- Current state: 45 characters
- What to do: Add 255+ characters to summary
```

## Prerequisite Validation Details

### Feature Prerequisites

**IN_DEVELOPMENT**: Must have ≥1 task created
- Validation: Checks task count ≥ 1 before allowing feature to start development
- Prevents empty features from progressing

**TESTING**: All tasks must be completed
- Validation: Checks all feature tasks have status=COMPLETED
- Prevents untested code from reaching testing phase

**COMPLETED**: All tasks must be completed
- Validation: Double-check all tasks complete before feature completion
- Ensures no unfinished work in completed features

### Task Prerequisites

**IN_PROGRESS**: No incomplete blocking dependencies
- Validation: Checks all BLOCKS dependencies are completed
- Prevents starting work before prerequisites are ready
- Checks dependency.type=BLOCKS and dependencyTask.status=COMPLETED

**COMPLETED**: Summary must be 300-500 characters
- Validation: Checks task.summary.length between 300-500 chars
- Ensures meaningful completion documentation
- Rejects summaries that are too brief (< 300) or too verbose (> 500)

### Project Prerequisites

**COMPLETED**: All features must be completed
- Validation: Checks all project features have status=COMPLETED
- Ensures comprehensive project completion

## Configuration Control

**Prerequisite Validation Configuration:**

Controlled via `status_validation.validate_prerequisites` in `.taskorchestrator/config.yaml`:

```yaml
status_validation:
  validate_prerequisites: true  # Enable prerequisite checks (recommended)
```

- Enabled by default
- Status Progression Skill reads live config on each invocation
- Disable validation by setting `validate_prerequisites: false` (not recommended)
- No server restart needed for config changes

## When to Use Status Progression Skill

ALWAYS use Status Progression Skill for status changes - it handles prerequisite validation automatically:

1. **Task Status Changes:**
   ```
   User: "Mark task complete"
   You: [Use Status Progression Skill]
   Skill validates: summary length, no blocking dependencies
   ```

2. **Feature Status Changes:**
   ```
   User: "Move feature to testing"
   You: [Use Status Progression Skill]
   Skill validates: all tasks completed
   ```

3. **Direct Work Completion:**
   ```
   You finished implementation directly
   You: [Use Status Progression Skill to mark complete]
   Skill validates: summary populated (if task), prerequisites met
   ```

The Skill handles all validation, error reporting, and user guidance. You just invoke it.

## Validation Error Handling

When prerequisite validation fails:

1. Status Progression Skill returns detailed error with:
   - Clear reason (e.g., "Feature must have at least 1 task")
   - Specific blocking items (e.g., incomplete task names, summary length)
   - Actionable suggestions (e.g., "Complete tasks first", "Add 250 more characters")

2. You relay the error to user with context

3. User resolves blocker (create tasks, complete dependencies, add summary)

4. Retry status change via Status Progression Skill

## General Error Handling Pattern

When issues arise in Task Orchestration:

1. Identify the failure point
2. Check for blockers or dependencies (via Dependency Analysis Skill)
3. Suggest remediation actions
4. **Ask user**: Direct fix vs specialist resolution
5. Never silently fail

## Validation is Dynamic

- Status Progression Skill reads `.taskorchestrator/config.yaml` on each invocation
- Validation rules can be disabled via `validate_prerequisites: false`
- Status progressions customizable via `status_progressions` config
- Skill adapts to configuration changes without restart
