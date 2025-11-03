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
- `query_sections` - Read sections with tag filtering (**Token optimized**)
- `manage_container` - Create/update features and tasks
- `query_workflow_state` - Check workflow state, cascade events, prerequisites (**NEW**)
- `query_templates` - Discover available templates
- `apply_template` - Apply templates to features
- `recommend_agent` - Route tasks to specialists
- `manage_sections` - Create feature documentation

## Section Tag Taxonomy

**When reading feature/task sections, use tag filtering for token efficiency:**

**Contextual Tags** (Planning Specialist reads from features):
- **context** - Business context, user needs, dependencies
- **requirements** - Functional requirements, must-haves, constraints
- **acceptance-criteria** - Completion criteria, quality standards

**Actionable Tags** (Implementation Specialist reads from tasks):
- **workflow-instruction** - Step-by-step processes
- **checklist** - Validation checklists, completion criteria
- **commands** - Bash commands to execute
- **guidance** - Implementation patterns
- **process** - Workflow processes

**Reference Tags** (Read as needed):
- **reference** - Examples, patterns
- **technical-details** - Deep technical specs

**Example - Efficient Section Reading:**
```javascript
// Planning Specialist reads only context/requirements from feature
sections = query_sections(
  entityType="FEATURE",
  entityId=featureId,
  tags="context,requirements,acceptance-criteria",
  includeContent=true
)
// Token cost: ~2-3k vs ~7k+ with all sections (60% savings)

// Implementation Specialist reads only actionable content from task
sections = query_sections(
  entityType="TASK",
  entityId=taskId,
  tags="workflow-instruction,checklist,commands,guidance",
  includeContent=true
)
// Token cost: ~800-1.5k vs ~3-5k with all sections (50% savings)
```

**Note:** Subagents (Feature Architect, Planning Specialist, Implementation Specialist) automatically use tag filtering. This reference is for direct tool usage.

## Cascade Event Detection (Automatic)

**Recommended Approach:** Use `query_workflow_state` for automatic cascade event detection instead of manual checks.

```javascript
// After task completion or status change
workflowState = query_workflow_state(
  containerType="feature",
  id=task.featureId
)

// Check for detected cascade events
if (workflowState.detectedEvents.length > 0) {
  for (event of workflowState.detectedEvents) {
    // Event structure:
    // - event: "all_tasks_complete", "first_task_started", etc.
    // - suggestedStatus: Next recommended status
    // - automatic: Whether to apply automatically
    // - reason: Human-readable explanation

    if (event.automatic) {
      "✅ Cascade event detected: ${event.event}
      Suggested next status: ${event.suggestedStatus}
      Reason: ${event.reason}

      Use Status Progression Skill to apply this transition."
    }
  }
}
```

**Benefits:**
- Automatic detection based on config-driven workflows
- Works with custom user flows, not just defaults
- Handles complex prerequisite checking
- Provides human-readable explanations

## Status Progression Trigger Points (Manual Detection)

**Legacy Pattern:** Manual detection is still available but `query_workflow_state` is preferred.

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
1. **Launch Feature Architect subagent** for detailed planning:
   ```
   Use Task tool with subagent_type="Feature Architect" and prompt:
   "Create a feature for [user's request].
   - Read context from [file path if provided]
   - Formalize requirements
   - Discover and apply appropriate templates
   - Create comprehensive feature structure with sections"
   ```
2. Feature Architect will create feature with templates and sections
3. **Then launch Planning Specialist** for task breakdown:
   ```
   Use Task tool with subagent_type="Planning Specialist" and prompt:
   "Break down feature [feature-id] into domain-isolated tasks with dependencies.
   Create execution graph with parallel batches."
   ```

**CRITICAL:** For complex features (8+ tasks from the testing prompt), ALWAYS launch Feature Architect first, then Planning Specialist. DO NOT create features and tasks directly.

See [examples.md](examples.md) for detailed scenarios.

### 2. Task Breakdown Coordination

**After feature creation:**

**For SIMPLE breakdown (< 5 tasks):**
- Create tasks directly with templates
- Discover task templates: `query_templates(operation="list", targetEntityType="TASK", isEnabled=true)`
- Create 2-4 tasks in "backlog" status

**For COMPLEX breakdown (5+ tasks, multiple domains):**
1. **Launch Planning Specialist subagent** (MANDATORY):
   ```
   Use Task tool with subagent_type="Planning Specialist" and prompt:
   "Break down feature [feature-id or name] into domain-isolated tasks.
   Feature: [brief description]

   Create:
   - Domain-isolated tasks (database, backend, frontend, testing, docs)
   - BLOCKS dependencies between tasks
   - Execution graph with parallel batches
   - Provide execution order recommendation"
   ```
2. Planning Specialist will create all tasks with dependencies automatically
3. Planning Specialist returns execution graph showing batch order

**CRITICAL:** For the testing prompt (8 features with varying task counts), use Planning Specialist for features with 4+ tasks. DO NOT create tasks manually for complex features.

### 2.5. Phase 3 Handoff (Before Task Execution)

**CRITICAL: Before delegating to Task Orchestration Skill, progress feature status.**

When tasks are ready to execute (Phase 3 of coordinate_feature_development):

1. **Check current feature status:**
   ```javascript
   feature = query_container(operation="get", containerType="feature", id=featureId)
   ```

2. **If status is "planning" or "draft" and tasks exist:**
   ```javascript
   if ((feature.status == "planning" || feature.status == "draft") && taskCount > 0) {
     "Use Status Progression Skill to progress feature status.

     Cascade Event: tasks_ready_to_execute
     Current Status: ${feature.status}

     The Status Progression Skill will:
     1. Call get_next_status to determine appropriate next status (config-driven)
     2. Apply the recommended status based on your workflow
     3. Different workflows may use different status names (e.g., 'in-development', 'active', 'implementing')

     Why this matters: Feature status must reflect current development phase.
     Skipping this causes feature to jump directly to completion, missing execution phase."
   }
   ```

3. **Then delegate to Task Orchestration Skill:**
   ```
   Skill(command="task-orchestration")

   "Execute tasks for feature [featureName].
   Feature ID: [featureId]

   Execution graph:
   [paste execution graph]

   Guide me through parallel execution."
   ```

**When to perform this handoff:**
- After Phase 2 (task breakdown) completes
- Before Phase 3 (task execution) begins
- When resuming paused feature work

**Why this is critical:**
- Prevents feature status from being stuck in "planning" during active development
- Enables proper cascade event detection (tasks_ready_to_execute)
- Ensures workflow state accurately reflects reality (config determines execution phase status)
- Required for StatusValidator prerequisite checking
- Respects user's custom workflow definitions (not hardcoded to specific status names)

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
