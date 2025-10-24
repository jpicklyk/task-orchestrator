# Feature Orchestration Skill - Examples

## Example 1: Simple Feature Creation

**User Request:**
> "Create a user profile feature"

**Skill Assessment:**
- Description: Short and clear (< 50 characters)
- Scope: Single purpose (user profile)
- Expected tasks: 2-3 (database, backend, frontend)
- Complexity: Simple

**Actions:**
1. Query templates for FEATURE type
2. Create feature with "Requirements Specification" template
3. Create 3 tasks directly:
   - Database: "Create user profile schema"
   - Backend: "Implement profile API endpoints"
   - Frontend: "Build profile UI component"

**Output:**
```
✓ Feature created: User Profile
  Status: planning
  Tasks: 3 created
  Templates: Requirements Specification

Next: Ready for task execution. Use Task Orchestration Skill to begin.
```

---

## Example 2: Complex Feature - Delegate to Feature Architect

**User Request:**
> "Build a comprehensive OAuth integration system supporting Google, GitHub, and Microsoft authentication with JWT token management, refresh token handling, secure session storage, role-based access control, and audit logging"

**Skill Assessment:**
- Description: Long (> 200 characters)
- Scope: Multiple systems (OAuth providers, JWT, RBAC, audit)
- Technical complexity: High (authentication, security)
- Expected tasks: 8+ tasks
- Complexity: Complex

**Actions:**
1. Assess complexity score: 9/10
2. Return recommendation to orchestrator

**Output:**
```
⚠️ Complex Feature Detected

This feature requires detailed planning:
- Multiple integration points (3 OAuth providers)
- Security-critical components
- Cross-cutting concerns (RBAC, audit)
- Estimated 8+ tasks

**Decision: COMPLEX feature → Launch Feature Architect**

**Execution Pattern:**
```javascript
// Step 1: Launch Feature Architect subagent
Use Task tool with:
- subagent_type: "Feature Architect"
- description: "Create OAuth integration feature"
- prompt: `Create a feature for comprehensive OAuth integration system.

Requirements:
- Support Google, GitHub, and Microsoft authentication
- JWT token management
- Refresh token handling
- Secure session storage
- Role-based access control
- Audit logging

Steps:
1. Formalize requirements and create feature structure
2. Discover and apply appropriate templates (security, API design)
3. Create comprehensive sections
4. Return feature ID for task breakdown`

// Step 2: After Feature Architect completes, launch Planning Specialist
Use Task tool with:
- subagent_type: "Planning Specialist"
- description: "Break down OAuth feature into tasks"
- prompt: `Break down feature [feature-id-from-architect] into domain-isolated tasks.

Feature: OAuth integration system with multi-provider support

Create:
- Domain-isolated tasks (database, backend, frontend, security, testing, docs)
- BLOCKS dependencies showing execution order
- Execution graph with parallel batches`
```

---

## Example 2B: Multi-Feature Creation from Testing Plan

**User Request:**
> "Create a test project to validate our v2.0 event-driven feature and task orchestration workflows. Please read the testing plan at D:\Projects\task-orchestrator\tests\workflow-testing-plan.md"

**Skill Assessment:**
- Request involves: Creating 8 features with varying complexity
- Features range from 2 tasks to 8+ tasks
- Multiple workflow patterns to test
- File reference provided
- Complexity: VERY COMPLEX

**CRITICAL Decision: This requires Feature Architect for EACH complex feature**

**Execution Pattern:**
```javascript
// For EACH feature in the testing plan:

// Feature 1: Simple User Profile (2-3 tasks) → CREATE DIRECTLY
manage_container(operation="create", containerType="feature", name="User Profile Management", ...)
// Create 2-3 tasks directly

// Feature 2: Complex Payment System (8+ tasks) → LAUNCH FEATURE ARCHITECT
Use Task tool with:
- subagent_type: "Feature Architect"
- prompt: `Create feature: Payment Processing System
  Read requirements from: D:\Projects\task-orchestrator\tests\workflow-testing-plan.md
  Feature details in Test 2.2
  Expected: 8+ tasks, complex workflow
  Return feature ID when complete`

// After Feature Architect returns:
Use Task tool with:
- subagent_type: "Planning Specialist"
- prompt: `Break down feature [feature-id] into tasks per testing plan Test 2.2`

// Feature 3: Product Catalog (4 parallel tasks) → LAUNCH PLANNING SPECIALIST
manage_container(operation="create", containerType="feature", name="Product Catalog", ...)
Use Task tool with:
- subagent_type: "Planning Specialist"
- prompt: `Break down Product Catalog feature into 4 independent tasks (Test 3.1)`

// ... Repeat pattern for all 8 features
```

**Rules for Testing Plan:**
- Simple features (< 4 tasks): Create directly + create tasks directly
- Complex features (4-7 tasks): Create directly + **launch Planning Specialist**
- Very complex features (8+ tasks): **Launch Feature Architect + Planning Specialist**

**DO NOT:**
- ❌ Create all features and tasks manually (defeats the purpose of testing orchestration)
- ❌ Skip Planning Specialist for features with dependencies
- ❌ Skip Feature Architect for features with 8+ tasks

**Expected Workflow:**
1. Read testing plan file
2. For each feature, assess complexity
3. Route appropriately (direct, Planning Specialist, or Feature Architect → Planning Specialist)
4. Verify orchestration works correctly

---

## Example 3: Progress Feature to Testing

**User Request:**
> "Check status of authentication feature"

**Actions:**
1. Query feature with overview operation
2. Analyze task status:
   - Total: 5 tasks
   - Completed: 4 tasks
   - In Progress: 1 task
3. Determine next status: Cannot progress yet

**Output:**
```
Feature: Authentication System
Status: in-development (80% complete)

Tasks:
✓ Database schema (completed)
✓ Backend API (completed)
✓ Frontend UI (completed)
✓ Unit tests (completed)
⏳ Integration tests (in-progress)

Cannot progress to testing yet - 1 task still in progress.
Next action: Wait for integration tests to complete.
```

---

## Example 4: Feature Completion with Quality Gates

**User Request:**
> "Complete authentication feature"

**Actions:**
1. Check all tasks completed: ✓
2. Trigger testing hook
3. Tests pass: ✓
4. Create feature summary section
5. Mark feature complete

**Output:**
```
✓ Feature Completed: Authentication System

Summary:
- 5 tasks completed
- All tests passing
- Coverage: 87%

Key Changes:
- OAuth integration with Google, GitHub
- JWT token management
- Session handling and refresh tokens
- Comprehensive test suite

Files Changed: 23 files
- Backend: 12 files
- Frontend: 8 files
- Tests: 3 files
```

---

## Example 5: Quality Gate Failure

**User Request:**
> "Complete user management feature"

**Actions:**
1. Validate completion readiness
2. All tasks complete: ✓
3. Trigger testing hook
4. Tests fail: ✗

**Output:**
```
✗ Cannot Complete Feature: Quality Gates Failing

Testing Gate: FAILED
- 3 test failures detected
  • UserManagementTest.testDeleteUser
  • UserManagementTest.testUpdatePermissions
  • UserManagementTest.testBulkImport

Coverage Gate: WARNING
- Current: 68%
- Required: 80%
- Missing: 12% coverage

Actions Required:
1. Fix 3 failing tests
2. Add tests to reach 80% coverage threshold
3. Re-run testing gate

Suggestion: Launch Test Engineer to address failures.
```

---

## Example 6: Parallel Execution Planning

**User Request:**
> "Plan execution for notification system feature"

**Actions:**
1. Get all tasks (6 tasks total)
2. Analyze dependencies
3. Create execution batches

**Output:**
```
Execution Plan: Notification System

Batch 1 (Parallel - 40% time saved):
├─ Database schema (Complexity: 4)
├─ Email templates (Complexity: 3)
└─ UI notification center (Complexity: 5)
  Total: 12 units → 5 units (parallel)

Batch 2 (Sequential):
└─ Backend notification service (Complexity: 7)
  Depends on: Database schema

Batch 3 (Parallel - 33% time saved):
├─ Integration tests (Complexity: 5)
└─ Documentation (Complexity: 4)
  Total: 9 units → 5 units (parallel)

Estimated Time Savings: 40% overall
Next: Launch Batch 1 with 3 specialists in parallel
```

---

## Example 7: Check Feature Progress

**User Request:**
> "What's next for the API redesign feature?"

**Actions:**
1. Query feature overview
2. Analyze current state
3. Determine blockers and next actions

**Output:**
```
Feature: API Redesign
Status: in-development
Progress: 60% (6/10 tasks)

Current Status:
✓ 4 completed
⏳ 2 in progress
  • API versioning implementation
  • Swagger documentation
⏸️ 4 pending

Blockers: None

Next Actions:
1. Wait for current 2 tasks to complete
2. Then launch Batch 3 (2 parallel tasks):
   - Integration tests
   - Migration scripts

Parallel opportunity available once current batch completes.
```

---

## Example 8: Feature with Review Gate

**User Request:**
> "Complete payment integration feature"

**Actions:**
1. Check configuration: review gate enabled
2. All tasks complete: ✓
3. Tests passing: ✓
4. Update status to pending-review

**Output:**
```
Feature: Payment Integration
Status: validating → pending-review

✓ All Prerequisites Met:
  • All 8 tasks completed
  • Tests passing (94% coverage)
  • No blockers

⏸️ Awaiting Review:
  Review required before completion (configured in .taskorchestrator/config.yaml)

Next: Review feature implementation, then:
  • Approve: Move to completed
  • Request changes: Return to in-development
```

---

## Token Usage Examples

### Efficient Status Check
```javascript
// Using overview operation
query_container(operation="overview", containerType="feature", id="uuid")
// Returns: ~1,200 tokens (task list + counts, no sections)
```

### Inefficient Status Check (Don't do this)
```javascript
// Using get with sections
query_container(operation="get", containerType="feature", id="uuid", includeSections=true)
// Returns: ~18,500 tokens (full sections + tasks)
```

**Savings: 93% token reduction**

---

## Integration Examples

### With Task Orchestration Skill
```
1. Feature Orchestration: Creates feature + tasks
2. Task Orchestration: Executes tasks in parallel batches
3. Feature Orchestration: Validates quality gates
4. Feature Orchestration: Marks complete
```

### With Dependency Orchestration Skill
```
User: "This feature has complex dependencies"
1. Feature Orchestration: Creates feature
2. Dependency Orchestration: Analyzes dependency graph
3. Task Orchestration: Uses analysis for batching
```

### With Status Progression Skill
```
User: "Progress feature through workflow"
1. Status Progression: Validates transition
2. Status Progression: Checks prerequisites
3. Feature Orchestration: Enforces quality gates
4. Status Progression: Updates status
```

---

## Event Detection Examples

### Detection: First Task Started

```javascript
// Triggered when ANY task changes to execution phase
function onTaskStatusChange(taskId, oldStatus, newStatus) {
  // Check if transitioned into execution
  if (isExecutionPhase(newStatus) && !isExecutionPhase(oldStatus)) {

    task = query_container(operation="get", containerType="task", id=taskId)

    if (task.featureId) {
      // Query feature to check task counts
      feature = query_container(
        operation="overview",
        containerType="feature",
        id=task.featureId
      )

      // Count how many tasks in execution
      inProgressCount = feature.taskCounts.byStatus["in-progress"] || 0

      if (inProgressCount == 1) {
        // This is the FIRST task to start work!
        // EVENT DETECTED: first_task_started

        "Use Status Progression Skill to progress feature status.
        Context: First task started - ${task.title}"

        // Possible outcomes based on user's config:
        // - default_flow: planning → in-development
        // - rapid_prototype_flow: draft → in-development
      }
    }
  }
}
```

### Detection: Tests Passed

```javascript
// Triggered by external test hook or manual trigger
function onTestsComplete(featureId, testResults) {
  if (testResults.allPassed) {
    // EVENT DETECTED: tests_passed

    "Use Status Progression Skill to progress feature status.
    Context: Tests passed - ${testResults.total} tests successful"

    // Possible outcomes:
    // - default_flow: testing → validating
    // - with_review_flow: testing → validating → pending-review
    // - rapid_prototype_flow: (no testing, wouldn't get here)
  }
}
```

### Detection: Review Completed

```javascript
// Triggered by user or external review system
function onReviewComplete(featureId, reviewResult) {
  if (reviewResult.approved) {
    // EVENT DETECTED: review_approved

    "Use Status Progression Skill to progress feature status.
    Context: Review approved by ${reviewResult.reviewer}"

    // Possible outcome:
    // - with_review_flow: pending-review → completed

  } else {
    // EVENT DETECTED: changes_requested

    "Use Status Progression Skill to move feature back for rework.
    Context: Changes requested - ${reviewResult.changesRequested}"

    // Backward movement (if allow_backward: true):
    // - pending-review → in-development
  }
}
```

---

## Complexity Assessment Algorithm

```python
def assess_feature_complexity(user_request, context):
    score = 0

    # Length indicators
    if len(user_request) > 200:
        score += 2  # Long description suggests complexity

    # Technical complexity keywords
    integration_keywords = ["oauth", "api", "integration", "authentication",
                           "third-party", "external", "webhook"]
    if has_keywords(user_request, integration_keywords):
        score += 3  # Integrations add complexity

    # Domain indicators
    domains = count_domains(user_request)  # database, backend, frontend, etc.
    if domains >= 3:
        score += 2  # Multiple domains = complex

    # Scope clarity
    unclear_keywords = ["might", "maybe", "possibly", "unclear", "TBD"]
    if has_keywords(user_request, unclear_keywords):
        score += 2  # Unclear scope needs exploration

    # Expected task count
    estimated_tasks = estimate_task_count(user_request)
    if estimated_tasks >= 8:
        score += 3  # Many tasks = complex
    elif estimated_tasks >= 5:
        score += 2  # Medium tasks = moderate
    elif estimated_tasks >= 3:
        score += 1  # Few tasks = simple

    # Decision thresholds
    if score <= 3:
        return "simple"   # Create directly with Feature Orchestration
    elif score <= 6:
        return "moderate" # Could go either way
    else:
        return "complex"  # Launch Feature Architect subagent
```

**Example Assessments:**

| Request | Length | Integration | Domains | Tasks | Score | Result |
|---------|--------|-------------|---------|-------|-------|--------|
| "User profile feature" | 0 | 0 | 1 | 1 | 1 | Simple |
| "API for user management with CRUD operations" | 0 | 0 | 2 | 1 | 1 | Simple |
| "OAuth integration for Google and GitHub" | 0 | 3 | 2 | 2 | 7 | Complex |
| "Build comprehensive reporting system with analytics, dashboards, PDF export, scheduled emails, and data warehouse integration" | 2 | 3 | 3 | 3 | 13 | Complex |
