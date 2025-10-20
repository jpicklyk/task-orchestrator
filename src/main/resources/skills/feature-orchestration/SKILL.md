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

**Before marking feature complete using MCP tools:**

**Tool Orchestration Pattern:**

```
Step 1: Check all tasks complete
query_container(operation="overview", containerType="feature", id="...")
Check taskCounts.byStatus - ensure no pending/in-progress tasks

Step 2: Verify no blocked tasks
Search for pending tasks and check dependencies
query_container(operation="search", containerType="task", featureId="...", status="pending")
For each: query_dependencies(taskId="...", direction="incoming")

Step 3: Check documentation sections exist
query_sections(entityType="FEATURE", entityId="...", includeContent=false)
Verify required sections are present

Step 4: Report validation results
If all checks pass: "Feature ready for completion"
If any fail: "Cannot complete - [specific issues]"

Step 5: Mark complete if validated
manage_container(operation="setStatus", containerType="feature", id="...", status="completed")

Note: Testing hooks are external to MCP and cannot be triggered directly.
Testing validation should be done outside this skill or documented as manual step.
```

### 6. Feature Completion

**Create summary and mark complete using MCP tools:**

**Tool Orchestration Pattern:**

```
Step 1: Gather feature information
query_container(operation="overview", containerType="feature", id="...")
Get task counts and feature metadata

Step 2: Build summary content manually
Review completed tasks and synthesize:
- Total tasks completed
- Key functionality delivered
- Major changes made
- Testing status

Step 3: Create feature summary section
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

Step 4: Mark feature complete
manage_container(
  operation="setStatus",
  containerType="feature",
  id="...",
  status="completed"
)

Note: Summary generation requires manual review of task sections.
There is no automatic aggregation function in MCP tools.
```

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
1. Validate completion:
   ✓ All tasks completed
   ✓ No blocked tasks
   ✓ Tests passing (triggered hook)
   ✓ Documentation present

2. Create summary section
3. Mark status="completed"
4. Return: "Feature Y completed successfully.
   8 tasks completed, all tests passing."
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
   Consider running setup_claude_agents to install templates.
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
