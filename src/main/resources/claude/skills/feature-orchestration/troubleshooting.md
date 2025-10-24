# Feature Orchestration - Troubleshooting Guide

Common validation errors and how to resolve them.

## Validation Error Patterns

### Error: "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT"

**Cause:** Attempting to move feature from `planning` to `in-development` without creating any tasks.

**Solution:**
```javascript
// 1. Create at least one task for the feature
manage_container(
  operation="create",
  containerType="task",
  title="Initial implementation task",
  description="First task to start development",
  featureId="feature-uuid",
  priority="high",
  complexity=5,
  status="backlog"  // Tasks start in backlog
)

// 2. Now use Status Progression Skill to transition status
"Use Status Progression Skill to move feature to in-development"
```

**Prevention:** Always create tasks before moving features into development. Use Feature Architect or Planning Specialist for task breakdown.

---

### Error: "Cannot transition to TESTING: X task(s) not completed"

**Cause:** Attempting to move feature to `testing` while tasks are still pending or in-progress.

**Solution:**
```javascript
// 1. Check which tasks are incomplete
overview = query_container(operation="overview", containerType="feature", id="...")
// Review: overview.taskCounts.byStatus

// 2. Complete remaining tasks OR cancel unnecessary tasks
// Option A: Complete tasks (use Status Progression Skill)
"Use Status Progression Skill to mark task as completed"

// Option B: Cancel tasks that are no longer needed (use Status Progression Skill)
"Use Status Progression Skill to mark task as cancelled"

// 3. Retry feature status transition via Status Progression Skill
"Use Status Progression Skill to move feature to testing"
```

**Prevention:** Track task progress regularly. Use `query_container(operation="overview")` to monitor completion status before attempting status changes.

---

### Error: "Cannot transition to COMPLETED: X task(s) not completed"

**Cause:** Attempting to complete feature with pending/in-progress tasks.

**Solution:**
```javascript
// 1. Identify incomplete tasks
tasks = query_container(
  operation="search",
  containerType="task",
  featureId="...",
  status="pending,in-progress"
)

// 2. For each incomplete task, decide:
// Option A: Complete the task (if work is done, use Status Progression Skill)
"Use Status Progression Skill to mark task as completed"

// Option B: Cancel the task (if no longer needed, use Status Progression Skill)
"Use Status Progression Skill to mark task as cancelled"

// 3. Verify all tasks are resolved
overview = query_container(operation="overview", containerType="feature", id="...")
// Check: overview.taskCounts.byStatus.pending === 0
// Check: overview.taskCounts.byStatus['in-progress'] === 0

// 4. Retry feature completion via Status Progression Skill
"Use Status Progression Skill to mark feature as completed"
```

**Prevention:** Regularly review task status. Cancel tasks early if scope changes. Don't leave tasks in limbo.

---

### Error: "Task summary is required before completion"

**Cause:** Attempting to mark task as complete without setting the summary field.

**Solution:**
```javascript
// 1. Add summary to task (300-500 characters recommended)
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  summary="Implemented user authentication with JWT tokens. Added login/logout endpoints, password hashing with bcrypt, and refresh token rotation. All tests passing."
)

// 2. Now use Status Progression Skill to mark task complete
"Use Status Progression Skill to mark task as completed"
```

**Prevention:** Add summaries as you complete work. Summaries help future reference and provide context for downstream tasks.

---

### Error: "Task summary must be 300-500 characters (current: X)"

**Cause:** Summary too short (< 300 chars) or too long (> 500 chars).

**Solution:**
```javascript
// If too short - add more detail
summary = "User authentication implementation. Added JWT tokens."  // 60 chars - TOO SHORT

// Expand with:
summary = "Implemented comprehensive user authentication system with JWT token generation and validation. Added login/logout endpoints with proper session management. Integrated bcrypt for password hashing with configurable salt rounds. Implemented refresh token rotation for enhanced security. All endpoints include proper error handling and input validation. Test coverage at 94%."  // 380 chars - GOOD

// If too long - condense
summary = "Very long detailed description..." // 612 chars - TOO LONG

// Condense to key points:
summary = "Implemented user auth with JWT tokens (login/logout/refresh). Added bcrypt password hashing, session management, and comprehensive error handling. Includes validation, proper security practices, and 94% test coverage. All API endpoints documented in Swagger."  // 270 chars - needs 30 more

manage_container(operation="update", containerType="task", id="...", summary=summary)
```

**Prevention:** Aim for 350-450 characters. Include what was done, key technical decisions, and test coverage.

---

## Bypassing Validation (Development/Testing Only)

**When validation is too strict during development:**

```yaml
# Edit .taskorchestrator/config.yaml
status_validation:
  validate_prerequisites: false  # Temporarily disable validation
```

**Warning:** Only disable validation for:
- Development/testing workflows
- Prototyping and experimentation
- Fixing broken states

**Re-enable validation for production workflows:**
```yaml
status_validation:
  validate_prerequisites: true  # Default production setting
```

---

## Debugging Validation Issues

### Check Current Validation Settings

```javascript
// Validation is config-driven - check .taskorchestrator/config.yaml
// Default: validate_prerequisites: true
```

### View Detailed Error Messages

```javascript
result = manage_container(operation="setStatus", ...)
if (!result.success) {
  console.log("Error:", result.error)
  // Error message includes:
  // - What validation failed
  // - How many tasks are incomplete
  // - Names of incomplete tasks
}
```

### Common Validation Failure Patterns

1. **Task count = 0** → Create tasks before starting development
2. **Incomplete tasks** → Complete or cancel tasks before progressing
3. **Missing summary** → Add task summary before marking complete
4. **Summary wrong length** → Adjust to 300-500 characters
5. **Status transition invalid** → Check status progression flow

---

## Quality Gate Failures

### Tests Failing

```
Error: Cannot complete feature - testing gate failed
- 3 test failures in authentication module
```

**Solution:**
1. Review test failures
2. Fix code or update tests
3. Re-run tests
4. Retry feature completion

**Or delegate:**
```
"Launch Test Engineer subagent to investigate and fix test failures"
```

### Coverage Below Threshold

```
Warning: Code coverage below threshold
- Current: 68%
- Required: 80%
- Missing: 12% coverage
```

**Solution:**
1. Identify uncovered code
2. Write additional tests
3. Re-run coverage report
4. Retry feature completion

---

## Common Anti-Patterns

### ❌ Skipping Template Discovery

```javascript
// DON'T: Create without checking templates
manage_container(operation="create", containerType="feature", ...)
```

```javascript
// DO: Always discover templates first
templates = query_templates(operation="list", targetEntityType="FEATURE", isEnabled=true)
manage_container(operation="create", containerType="feature", templateIds=[...])
```

### ❌ Directly Changing Status

```javascript
// DON'T: Direct status change
manage_container(operation="setStatus", containerType="feature", status="testing")
```

```javascript
// DO: Use Status Progression Skill
"Use Status Progression Skill to progress feature to next status"
```

### ❌ Not Checking Task Counts

```javascript
// DON'T: Assume all tasks complete
"Use Status Progression Skill to mark feature complete"
```

```javascript
// DO: Check task counts first
overview = query_container(operation="overview", containerType="feature", id="...")
if (overview.taskCounts.byStatus.pending == 0 &&
    overview.taskCounts.byStatus["in-progress"] == 0) {
  "Use Status Progression Skill to mark feature complete"
}
```

### ❌ Using includeSections for Status Checks

```javascript
// DON'T: Expensive query for simple check
feature = query_container(operation="get", containerType="feature",
                         id="...", includeSections=true)  // 18k tokens!
```

```javascript
// DO: Use overview operation
feature = query_container(operation="overview", containerType="feature",
                         id="...")  // 1.2k tokens (93% savings)
```
