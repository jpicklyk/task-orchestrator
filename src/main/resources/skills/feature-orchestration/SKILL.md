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

## Core Workflows

### 1. Smart Feature Creation

**Assess complexity first:**

```javascript
// Indicators for SIMPLE features:
- User request < 200 characters
- Clear single purpose
- No complex integrations
- Expected tasks < 3
- No technical jargon

// Indicators for COMPLEX features:
- Multiple components
- Integration requirements
- Unclear scope
- Expected tasks ≥ 5
- Cross-domain work
```

**For SIMPLE features:**
```javascript
1. query_templates(operation="list", targetEntityType="FEATURE", isEnabled=true)
2. manage_container(
     operation="create",
     containerType="feature",
     name="Clear feature name",
     description="What needs to be built",
     status="planning",
     priority="medium",
     tags="domain,functional-area",
     templateIds=["basic-template-uuid"]
   )
3. Create 2-3 tasks directly with manage_container
4. Return feature ID to orchestrator
```

**For COMPLEX features:**
```javascript
Return recommendation:
"This feature requires detailed planning. Launch Feature Architect subagent
with the user's request for proper formalization and template application."
```

### 2. Task Breakdown Coordination

**After feature creation, assess breakdown needs:**

```javascript
// Check feature complexity
feature = query_container(operation="get", containerType="feature", id="...")

// Decision logic:
if (feature.tags includes database, backend, frontend, testing) {
  // Multiple domains = complex
  return "Launch Planning Specialist for detailed breakdown"
}

if (estimated_tasks < 5) {
  // Simple breakdown - create tasks directly
  create_simple_tasks(feature)
} else {
  // Complex breakdown
  return "Launch Planning Specialist"
}
```

**Simple task creation pattern:**
```javascript
// Example: Simple CRUD feature
tasks = [
  {
    title: "Create database schema",
    description: "Add tables for X with fields Y, Z",
    tags: "database,schema",
    complexity: 4,
    templateIds: ["technical-approach"]
  },
  {
    title: "Implement API endpoints",
    description: "Create GET/POST/PUT/DELETE for X",
    tags: "backend,api",
    complexity: 6,
    templateIds: ["technical-approach", "testing-strategy"]
  },
  {
    title: "Add UI components",
    description: "Create list and detail views",
    tags: "frontend,ui",
    complexity: 5,
    templateIds: ["technical-approach"]
  }
]

// Create tasks with dependencies
for each task in tasks {
  manage_container(operation="create", containerType="task", ...)
}
```

### 3. Parallel Execution Planning

**Analyze dependencies and create execution batches:**

```javascript
// Get all feature tasks
tasks = query_container(
  operation="search",
  containerType="task",
  featureId="...",
  status="pending"
)

// Group by dependencies
batches = analyze_dependencies(tasks)

// Example output:
{
  "execution_plan": {
    "batch_1": {
      "parallel": true,
      "tasks": ["T1_Database", "T3_Frontend_Components"]
    },
    "batch_2": {
      "parallel": false,
      "tasks": ["T2_Backend_API"]  // Depends on T1
    },
    "batch_3": {
      "parallel": false,
      "tasks": ["T4_Integration_Tests"]  // Depends on T2, T3
    }
  },
  "estimated_time_savings": "40%"
}
```

### 4. Feature Progress Tracking

**Check feature status and suggest next actions:**

```javascript
// Get feature with tasks
feature = query_container(
  operation="overview",
  containerType="feature",
  id="..."
)

// Analyze task status
analysis = {
  total_tasks: feature.taskCounts.total,
  completed: feature.taskCounts.byStatus.completed,
  in_progress: feature.taskCounts.byStatus['in-progress'],
  blocked: feature.taskCounts.byStatus.blocked,
  pending: feature.taskCounts.byStatus.pending
}

// Determine status
if (all tasks completed) {
  return "Ready for testing and completion"
} else if (has blocked tasks) {
  return "Address blockers first" + blocked_task_details
} else if (has pending tasks) {
  return "Launch next batch" + parallel_opportunities
}
```

### 5. Quality Gate Validation

**Automatic Prerequisite Validation:**

The system automatically validates prerequisites when changing feature status. You don't need to manually check these conditions - the validation happens when you call `manage_container` with `operation="setStatus"`.

**Prerequisites Enforced by System:**

```javascript
// PLANNING → IN_DEVELOPMENT:
- Feature must have at least 1 task created
- Error if 0 tasks: "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT"
- Rationale: A feature without tasks cannot be developed

// IN_DEVELOPMENT → TESTING:
- All feature tasks must be completed or cancelled
- Error if incomplete: "Cannot transition to TESTING: 2 task(s) not completed.
  Incomplete tasks: \"Implement API endpoints\", \"Add UI components\""
- Rationale: Cannot test incomplete implementation

// TESTING/VALIDATING → COMPLETED:
- All feature tasks must be completed or cancelled
- Error if incomplete: "Cannot transition to COMPLETED: 3 task(s) not completed.
  Incomplete tasks: \"Database schema\", \"API endpoints\", \"UI components\""
- Rationale: Cannot complete feature with unfinished work

// Task Prerequisite Validation:
- Tasks must have a summary before marking complete
- Error if missing: "Cannot transition to COMPLETED: Task summary is required before completion"
- Rationale: Summary captures what was accomplished for future reference
```

**Status Transition Validation Matrix:**

| From Status | To Status | Prerequisites | Validation |
|------------|-----------|---------------|------------|
| planning | in-development | ≥1 task created | Automatic |
| in-development | testing | All tasks completed/cancelled | Automatic |
| testing | validating | All tasks completed/cancelled | Automatic |
| validating | completed | All tasks completed/cancelled | Automatic |
| * | cancelled | None | No validation |

**Validation is Config-Driven:**

The validation can be toggled in `.taskorchestrator/config.yaml`:

```yaml
status_validation:
  validate_prerequisites: true  # Default: true
  # When false, status transitions proceed without checking prerequisites
  # Useful for testing or manual workflow management
```

When `validate_prerequisites: false`, status transitions proceed without checking task completion or summary requirements.

**Tool Orchestration Pattern:**

```javascript
// Step 1: Check feature status (to see what tasks remain)
overview = query_container(operation="overview", containerType="feature", id="...")
// Review taskCounts.byStatus to see incomplete tasks

// Step 2: Attempt status transition
result = manage_container(
  operation="setStatus",
  containerType="feature",
  id="...",
  status="testing"  // or "completed"
)

// Step 3: Handle validation results
if (result.success) {
  // Status changed successfully
  // Prerequisite validation passed automatically
  console.log("Feature status updated successfully")
} else {
  // Validation failed - error message explains what's missing
  // Example: "Cannot transition to TESTING: 2 task(s) not completed..."
  console.error(result.error)
  // Address the issues, then retry
}
```

**What You Should Check Manually:**

These items are NOT automatically validated by the system:

1. **Documentation completeness**: Use `query_sections()` to verify required sections exist
2. **Test results**: Testing hooks are external to MCP and must be verified separately
3. **Code review status**: Check if reviews are required and completed
4. **Quality standards**: Ensure code quality, test coverage, and standards are met
5. **Feature summary**: System doesn't auto-validate feature-level summary (only task summaries)

**Validation Failure Handling:**

```javascript
// If status transition fails, the error message tells you exactly what's wrong:
{
  "success": false,
  "error": "Cannot transition to COMPLETED: 2 task(s) not completed.
           Incomplete tasks: \"Implement API\", \"Write tests\""
}

// To resolve:
1. Check task status: query_container(operation="search", containerType="task", featureId="...")
2. Complete remaining tasks OR cancel tasks that are no longer needed
3. Retry status transition

// For task summary validation failure:
{
  "success": false,
  "error": "Cannot transition to COMPLETED: Task summary is required before completion"
}

// To resolve:
1. Update task with summary: manage_container(
     operation="update",
     containerType="task",
     id="...",
     summary="Brief description of what was accomplished (300-500 chars)"
   )
2. Retry marking task complete
```

### 6. Feature Completion

**Prerequisites are automatically validated when marking complete.**

**Tool Orchestration Pattern:**

```javascript
// Step 1: Gather feature information
overview = query_container(operation="overview", containerType="feature", id="...")
// Get task counts and feature metadata

// Step 2: Verify all tasks are complete
if (overview.taskCounts.byStatus.pending > 0 ||
    overview.taskCounts.byStatus['in-progress'] > 0) {
  // Complete or cancel remaining tasks first
  // The system will block completion until tasks are done
}

// Step 3: Create feature summary section (optional but recommended)
manage_sections(
  operation="add",
  entityType="FEATURE",
  entityId="...",
  title="Feature Summary",
  usageDescription="What was accomplished",
  content="[Manually composed summary based on task review]",
  contentFormat="MARKDOWN",
  ordinal=999,
  tags="summary,completion"
)

// Step 4: Mark feature complete (prerequisite validation happens automatically)
result = manage_container(
  operation="setStatus",
  containerType="feature",
  id="...",
  status="completed"
)

// If validation fails, result.error will explain what's missing:
// "Cannot transition to COMPLETED: 2 task(s) not completed.
//  Incomplete tasks: \"Implement API\", \"Write tests\""
```

**Automatic Validation on Completion:**

When you attempt to mark a feature as `completed`, the system automatically checks:

1. **All tasks completed or cancelled** - Features cannot complete with pending/in-progress tasks
2. **Validation is enabled** - Controlled by `status_validation.validate_prerequisites` config flag

If validation fails, you'll receive a detailed error message listing the incomplete tasks.

**Note**: Summary generation requires manual review of task sections. There is no automatic aggregation function in MCP tools.

## Status Progression Flow

```
planning
  ↓ (tasks created)
in-development
  ↓ (tasks in progress)
testing
  ↓ (all tasks complete, tests triggered)
validating
  ↓ (tests passed)
pending-review (optional)
  ↓ (review approved or skipped)
completed
```

## Complexity Assessment Algorithm

```python
def assess_feature_complexity(user_request, context):
    score = 0

    # Length indicators
    if len(user_request) > 200:
        score += 2

    # Technical complexity
    if has_integration_keywords(user_request):
        score += 3
    if has_multiple_domains(user_request):
        score += 2

    # Scope clarity
    if is_vague_or_unclear(user_request):
        score += 2

    # Expected task count
    estimated_tasks = estimate_task_count(user_request)
    if estimated_tasks >= 5:
        score += 3
    elif estimated_tasks >= 3:
        score += 1

    # Decision
    if score <= 3:
        return "simple"  # Create directly
    else:
        return "complex"  # Launch Feature Architect
```

## Configuration Integration

**Load settings from `.taskorchestrator/config.yaml`:**

```yaml
# Feature-related configuration
automation:
  auto_create_tasks: true
  auto_progress_status: true
  auto_complete_features: false  # Manual confirmation

quality_gates:
  testing:
    enabled: true
    blocking: true
  review:
    enabled: false
    blocking: false

status_progression:
  features:
    default_flow:
      - planning
      - in-development
      - testing
      - validating
      - completed
```

## Token Efficiency

**Keep responses concise:**
- Use `operation="overview"` for status checks (90% token reduction)
- Batch operations where possible
- Return brief status updates, not full context
- Delegate complex work to subagents
- Query only necessary task fields

**Example efficient query:**
```javascript
// Instead of getting all tasks with full sections (18k tokens)
query_container(operation="get", containerType="feature", includeSections=true)

// Use overview for status checks (1.2k tokens)
query_container(operation="overview", containerType="feature", id="...")
```

## Examples

### Example 1: Simple Feature Creation

**User:** "Create a user profile feature"

**Assessment:** Simple
- Clear purpose (user profile)
- Single domain (likely 2-3 tasks)
- No complex integrations
- < 50 characters

**Actions:**
```javascript
1. query_templates(targetEntityType="FEATURE")
2. create feature with basic template
3. create 3 tasks:
   - "Create user profile database schema" (database)
   - "Implement profile API endpoints" (backend)
   - "Build profile UI component" (frontend)
4. Return: "Feature created with 3 tasks. Ready for execution."
```

### Example 2: Complex Feature Creation

**User:** "Build an OAuth integration system that supports Google, GitHub, and Microsoft authentication with JWT token management, refresh token handling, and role-based access control"

**Assessment:** Complex
- Multiple integration points (3 providers)
- Complex domain (auth, security)
- Multiple technical concepts (OAuth, JWT, RBAC)
- > 200 characters
- Estimated 8+ tasks

**Action:**
```javascript
Return: "This is a complex feature requiring detailed planning.
Recommend launching Feature Architect subagent to:
- Formalize requirements
- Apply appropriate templates (Security, API Design)
- Create comprehensive feature structure
Then Planning Specialist will break it into domain-isolated tasks."
```

### Example 3: Feature Progress Check

**User:** "What's the status of feature X?"

**Actions:**
```javascript
1. query_container(operation="overview", containerType="feature", id="X")
2. Analyze task status:
   - 5 tasks total
   - 3 completed
   - 1 in-progress
   - 1 pending (not blocked)
3. Return: "Feature X is 60% complete (3/5 tasks done).
   Currently in-progress: API implementation
   Next up: UI integration (can start in parallel)"
```

### Example 4: Feature Completion

**User:** "Complete feature Y"

**Actions:**
```javascript
1. Check feature status and validate prerequisites:
   overview = query_container(operation="overview", containerType="feature", id="Y")

   // Check prerequisite conditions:
   ✓ All tasks completed (taskCounts.byStatus.completed = 8)
   ✓ No pending tasks (taskCounts.byStatus.pending = 0)
   ✓ No in-progress tasks (taskCounts.byStatus['in-progress'] = 0)
   ✓ No blocked tasks
   ✓ Tests passing (triggered hook - external validation)
   ✓ Documentation present (manual check via query_sections)

2. Create summary section (optional but recommended):
   manage_sections(
     operation="add",
     entityType="FEATURE",
     entityId="Y",
     title="Feature Summary",
     content="Implemented complete user authentication system...",
     tags="summary,completion"
   )

3. Mark status="completed" (automatic prerequisite validation):
   result = manage_container(
     operation="setStatus",
     containerType="feature",
     id="Y",
     status="completed"
   )
   // System automatically validates: all tasks completed/cancelled
   // If validation fails, result.error contains details

4. Return: "Feature Y completed successfully.
   8 tasks completed, all tests passing."
```

**If validation fails:**
```javascript
// Error response:
{
  "success": false,
  "error": "Cannot transition to COMPLETED: 2 task(s) not completed.
           Incomplete tasks: \"Add password reset\", \"Write integration tests\""
}

// Resolution:
1. Complete or cancel the 2 incomplete tasks
2. Retry feature completion
```

## Integration with Other Skills

**Works alongside:**
- **Task Orchestration Skill** - Delegates task execution
- **Dependency Orchestration Skill** - For complex dependency analysis
- **Status Progression Skill** - For status management

**Launches subagents:**
- **Feature Architect** - Complex feature formalization
- **Planning Specialist** - Complex task breakdown

## Error Handling

**Common scenarios:**

1. **No templates found:**
   ```javascript
   Warning: No templates available for FEATURE type.
   Creating feature without templates (less structure).
   Consider running setup_claude_orchestration to install templates.
   ```

2. **Quality gates fail:**
   ```javascript
   Cannot complete feature: Tests failing
   - 3 test failures in authentication module
   Suggest: Launch Test Engineer to fix failing tests
   ```

3. **Circular dependencies detected:**
   ```javascript
   Error: Cannot create execution plan - circular dependencies
   Tasks: T2 → T5 → T7 → T2
   Suggest: Use Dependency Orchestration Skill to resolve
   ```

## Troubleshooting Common Validation Errors

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
  complexity=5
)

// 2. Now retry status transition
manage_container(
  operation="setStatus",
  containerType="feature",
  id="feature-uuid",
  status="in-development"
)
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
// Option A: Complete tasks
manage_container(operation="setStatus", containerType="task", id="task-uuid", status="completed")

// Option B: Cancel tasks that are no longer needed
manage_container(operation="setStatus", containerType="task", id="task-uuid", status="cancelled")

// 3. Retry feature status transition
manage_container(operation="setStatus", containerType="feature", id="...", status="testing")
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
// Option A: Complete the task (if work is done)
manage_container(operation="setStatus", containerType="task", id="...", status="completed")

// Option B: Cancel the task (if no longer needed)
manage_container(operation="setStatus", containerType="task", id="...", status="cancelled")

// 3. Verify all tasks are resolved
overview = query_container(operation="overview", containerType="feature", id="...")
// Check: overview.taskCounts.byStatus.pending === 0
// Check: overview.taskCounts.byStatus['in-progress'] === 0

// 4. Retry feature completion
manage_container(operation="setStatus", containerType="feature", id="...", status="completed")
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

// 2. Now mark task complete
manage_container(
  operation="setStatus",
  containerType="task",
  id="task-uuid",
  status="completed"
)
```

**Prevention:** Add summaries as you complete work. Summaries help future reference and provide context for downstream tasks.

---

### Bypassing Validation (Development/Testing Only)

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

### Debugging Validation Issues

**Check current validation settings:**
```javascript
// Validation is config-driven - check .taskorchestrator/config.yaml
// Default: validate_prerequisites: true
```

**View detailed error messages:**
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

**Common validation failure patterns:**
1. **Task count = 0** → Create tasks before starting development
2. **Incomplete tasks** → Complete or cancel tasks before progressing
3. **Missing summary** → Add task summary before marking complete
4. **Status transition invalid** → Check status progression flow (planning → in-development → testing → completed)

## Best Practices

1. **Always assess complexity** before taking action
2. **Always discover templates** before creating features
3. **Use overview operations** for status checks
4. **Batch task creation** when creating multiple tasks
5. **Validate quality gates** before completion
6. **Return concise summaries** to orchestrator
7. **Delegate to subagents** when complexity exceeds threshold
8. **Monitor parallel execution** opportunities

## Success Metrics

- Simple features created in < 5 tool calls
- 40% time savings with parallel execution
- 60% token reduction vs old Feature Management skill
- 95% successful quality gate validation
- Zero manual intervention for standard workflows
