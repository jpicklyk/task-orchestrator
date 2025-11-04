# Workflow Testing Plan - Feature & Task Orchestration

**Purpose**: Comprehensive testing of event-driven status progression and orchestration workflows
**Version**: v2.0
**Created**: 2025-01-24

---

## Test Project Structure

We'll create a test project with multiple features demonstrating different workflow patterns:

```
Test Project: "E-Commerce Platform Testing"
├── Feature 1: Simple User Profile (simple workflow)
├── Feature 2: Complex Payment System (complex workflow, recommend architect)
├── Feature 3: Product Catalog (parallel tasks, no dependencies)
├── Feature 4: Order Processing (sequential tasks, full dependencies)
├── Feature 5: Shopping Cart (hybrid batched execution)
├── Feature 6: Circular Dependency Test (error handling)
├── Feature 7: Rapid Prototype Search (rapid_prototype_flow)
└── Feature 8: Security Audit (with_review_flow)
```

---

## Quick Start with Feature Architect

To rapidly create the test project structure, use this prompt with Feature Architect:

```
I need you to create a comprehensive test project to validate our v2.0 event-driven
feature and task orchestration workflows. Please read the testing plan at
D:\Projects\task-orchestrator\tests\workflow-testing-plan.md

Create a new project called "E-Commerce Platform Testing" with 8 features that
demonstrate different workflow patterns:

1. Simple User Profile (simple workflow, no architect needed)
2. Complex Payment System (complex workflow, should trigger architect recommendation)
3. Product Catalog (parallel task execution, no dependencies)
4. Order Processing (sequential tasks, full dependency chain)
5. Shopping Cart (hybrid batched execution with mixed dependencies)
6. Circular Dependency Test (error handling validation)
7. Rapid Prototype Search (rapid_prototype_flow alternative workflow)
8. Security Audit (with_review_flow alternative workflow)

For each feature, create the feature container with appropriate tags and apply
relevant templates from the testing plan. The goal is to have test data that
exercises every decision path in both Feature Orchestration and Task Orchestration
Skills.

Focus on creating well-structured features with proper tags for flow routing,
realistic descriptions, and the task structures outlined in the plan. Don't
implement the actual code - just create the orchestration structures.
```

---

## Test Execution Guide

### Phase 1: Project Setup

**Test 1.1: Create Test Project**

```javascript
// Expected: Project created successfully
manage_container(
  operation="create",
  containerType="project",
  name="E-Commerce Platform Testing",
  description="Comprehensive testing project for feature and task orchestration workflows",
  status="concept",
  tags="testing,e-commerce"
)
```

**Expected Result:**
- ✅ Project created with status "concept"
- ✅ Project ID returned
- ✅ No features yet (featureCounts.total = 0)

**Verification:**
```javascript
query_container(operation="overview", containerType="project", id="[project-id]")
```

---

### Phase 2: Feature Orchestration Testing

#### Test 2.1: Simple Feature Creation

**Scenario**: Short description, 2-3 expected tasks
**Expected**: Feature Orchestration creates feature directly (no Feature Architect)

```javascript
// Step 1: Discover templates
query_templates(operation="list", targetEntityType="FEATURE", isEnabled=true)

// Step 2: Create simple feature
manage_container(
  operation="create",
  containerType="feature",
  name="User Profile Management",
  description="Basic user profile with view and edit capabilities",
  projectId="[project-id]",
  status="draft",
  tags="frontend,simple",
  templateIds=["[requirements-template-id]"]
)

// Step 3: Progress to planning
// Use Status Progression Skill: draft → planning

// Step 4: Create 2 simple tasks
manage_container(
  operation="create",
  containerType="task",
  title="Create profile view component",
  description="Display user profile information",
  featureId="[feature-id]",
  status="backlog",
  priority="medium",
  complexity=3,
  tags="frontend,react"
)

manage_container(
  operation="create",
  containerType="task",
  title="Create profile edit component",
  description="Allow users to edit their profile",
  featureId="[feature-id]",
  status="backlog",
  priority="medium",
  complexity=4,
  tags="frontend,react"
)
```

**Expected Results:**
- ✅ Feature created in draft status
- ✅ Templates discovered and applied
- ✅ Feature progressed to planning via Status Progression Skill
- ✅ 2 tasks created in backlog status
- ✅ No recommendation to launch Feature Architect (simple)

**Decision Paths Tested:**
- [x] Simple feature creation (< 200 chars description)
- [x] Template discovery
- [x] Initial status: draft
- [x] Manual progression: draft → planning

---

#### Test 2.2: Complex Feature Creation

**Scenario**: Long description, 8+ expected tasks, multiple systems
**Expected**: Recommend Feature Architect subagent

```javascript
// Create complex feature
manage_container(
  operation="create",
  containerType="feature",
  name="Payment Processing System",
  description="Comprehensive payment processing system supporting multiple payment methods (credit cards, PayPal, Stripe, Apple Pay), with transaction history, refund processing, subscription billing, recurring payments, payment method tokenization, PCI compliance, fraud detection, webhook notifications, and detailed reporting dashboard",
  projectId="[project-id]",
  status="draft",
  tags="backend,payment,complex,security"
)
```

**Expected Results:**
- ✅ Feature created in draft status
- ✅ Complexity assessment: 9/10 (long description, multiple systems)
- ✅ **Recommendation**: "Launch Feature Architect subagent"
- ✅ Reason: Complex feature requiring detailed planning

**Decision Paths Tested:**
- [x] Complex feature creation (> 200 chars description)
- [x] Complexity assessment triggers architect recommendation
- [x] Feature Architect delegation pattern

**Note**: For testing purposes, we'll create tasks manually instead of launching Feature Architect.

---

#### Test 2.3: Event - first_task_started

**Scenario**: Start first task for User Profile feature
**Expected**: Feature auto-progresses planning → in-development

```javascript
// Step 1: Check current feature status
query_container(operation="overview", containerType="feature", id="[user-profile-feature-id]")
// Expected: status="planning", taskCounts.byStatus["in-progress"] = 0

// Step 2: Start first task (use Status Progression Skill)
// "Use Status Progression Skill to start task: Create profile view component"
// Expected: Task moves backlog → in-progress

// Step 3: Detect event
query_container(operation="overview", containerType="feature", id="[user-profile-feature-id]")
// Expected: taskCounts.byStatus["in-progress"] = 1

// Step 4: Trigger event (manually for testing)
// "Use Status Progression Skill to progress feature status.
// Context: First task started - Create profile view component"
```

**Expected Results:**
- ✅ Event detected: first_task_started
- ✅ Feature status: planning → in-development
- ✅ Status Progression Skill reads config.yaml
- ✅ Recommends next status based on default_flow
- ✅ StatusValidator validates prerequisites (≥1 task exists)

**Decision Paths Tested:**
- [x] Event detection: first_task_started
- [x] Event delegation to Status Progression Skill
- [x] Config-driven status progression (default_flow)
- [x] Prerequisite validation (≥1 task created)

---

#### Test 2.4: Event - all_tasks_complete

**Scenario**: Complete all tasks for User Profile feature
**Expected**: Feature auto-progresses in-development → testing

```javascript
// Step 1: Complete first task (summary required)
manage_container(
  operation="update",
  containerType="task",
  id="[profile-view-task-id]",
  summary="Created React component displaying user profile with avatar, name, email, and bio. Includes responsive design and loading states. All unit tests passing with 92% coverage. Files: ProfileView.tsx, ProfileView.test.tsx, ProfileView.module.css"
)
// "Use Status Progression Skill to mark task complete"
// Expected: in-progress → completed

// Step 2: Complete second task
manage_container(
  operation="update",
  containerType="task",
  id="[profile-edit-task-id]",
  summary="Created editable profile form with validation, error handling, and save/cancel buttons. Integrated with API endpoints for updating user data. Form validation prevents invalid submissions. Test coverage 89%. Files: ProfileEdit.tsx, ProfileEdit.test.tsx, ProfileEdit.module.css"
)
// "Use Status Progression Skill to mark task complete"
// Expected: in-progress → completed

// Step 3: Detect event
query_container(operation="overview", containerType="feature", id="[user-profile-feature-id]")
// Expected: taskCounts.byStatus.pending = 0, taskCounts.byStatus["in-progress"] = 0

// Step 4: Trigger event (manually for testing)
// "Use Status Progression Skill to progress feature status.
// Context: All 2 tasks complete (2 completed, 0 cancelled)"
```

**Expected Results:**
- ✅ Event detected: all_tasks_complete
- ✅ Feature status: in-development → testing
- ✅ StatusValidator validates: all tasks completed/cancelled
- ✅ Config determines next status (testing for default_flow)

**Decision Paths Tested:**
- [x] Event detection: all_tasks_complete
- [x] Prerequisite validation: all tasks complete
- [x] Status progression: in-development → testing

---

#### Test 2.5: Event - tests_passed

**Scenario**: Tests pass for User Profile feature
**Expected**: Feature progresses testing → validating

```javascript
// Step 1: Trigger tests (external signal for testing)
// "Use Status Progression Skill to progress feature status.
// Context: Tests passed - 24 tests successful"
```

**Expected Results:**
- ✅ Event detected: tests_passed
- ✅ Feature status: testing → validating
- ✅ Config-driven progression (default_flow)

**Decision Paths Tested:**
- [x] Event detection: tests_passed
- [x] External signal handling
- [x] Status progression: testing → validating

---

#### Test 2.6: Event - completion_requested

**Scenario**: User requests to complete User Profile feature
**Expected**: Feature progresses validating → completed

```javascript
// Step 1: Request completion
// "Use Status Progression Skill to mark feature complete"

// Step 2: Validate prerequisites
query_container(operation="overview", containerType="feature", id="[user-profile-feature-id]")
// Expected: All prerequisites met
```

**Expected Results:**
- ✅ Event detected: completion_requested
- ✅ Prerequisites validated: all tasks complete
- ✅ Feature status: validating → completed
- ✅ Feature marked complete

**Decision Paths Tested:**
- [x] Event detection: completion_requested
- [x] Prerequisite validation before completion
- [x] Status progression: validating → completed

---

#### Test 2.7: Validation Failure - No Tasks

**Scenario**: Try to start development with no tasks created
**Expected**: StatusValidator blocks transition

```javascript
// Step 1: Create feature without tasks
manage_container(
  operation="create",
  containerType="feature",
  name="Test Validation Failure",
  description="Testing prerequisite validation",
  projectId="[project-id]",
  status="draft"
)

// Step 2: Progress to planning
// "Use Status Progression Skill: draft → planning"

// Step 3: Try to progress to in-development (no tasks)
// "Use Status Progression Skill: planning → in-development"
```

**Expected Results:**
- ❌ StatusValidator BLOCKS transition
- ❌ Error: "Feature must have at least 1 task before transitioning to IN_DEVELOPMENT"
- ✅ Feature remains in planning status
- ✅ Clear error message with resolution steps

**Decision Paths Tested:**
- [x] Prerequisite validation failure
- [x] StatusValidator enforcement
- [x] Error handling and user guidance

---

#### Test 2.8: Validation Failure - Incomplete Tasks

**Scenario**: Try to move to testing with incomplete tasks
**Expected**: StatusValidator blocks transition

```javascript
// Step 1: Create feature with 3 tasks
// Step 2: Complete only 2 tasks, leave 1 in-progress
// Step 3: Try to progress to testing
// "Use Status Progression Skill: in-development → testing"
```

**Expected Results:**
- ❌ StatusValidator BLOCKS transition
- ❌ Error: "Cannot transition to TESTING: 1 task(s) not completed"
- ✅ Feature remains in in-development status
- ✅ Lists incomplete tasks for user

**Decision Paths Tested:**
- [x] Prerequisite validation failure (incomplete tasks)
- [x] Error message clarity
- [x] Blocker identification

---

#### Test 2.9: Backward Movement - Tests Failed

**Scenario**: Tests fail, move backward to in-development
**Expected**: Feature moves backward if allow_backward: true

```javascript
// Step 1: Feature in testing status
// Step 2: Trigger test failure
// "Use Status Progression Skill to move feature back for rework.
// Context: Tests failed - 3 failures detected"
```

**Expected Results:**
- ✅ Event detected: tests_failed
- ✅ Config allows backward movement (allow_backward: true)
- ✅ Feature status: testing → in-development
- ✅ Failure details provided to user

**Decision Paths Tested:**
- [x] Event detection: tests_failed
- [x] Backward movement (rework scenario)
- [x] Config-driven behavior (allow_backward)

---

#### Test 2.10: Alternative Flow - rapid_prototype_flow

**Scenario**: Create feature with prototype tag
**Expected**: Flow skips testing phase

```javascript
// Step 1: Create feature with prototype tag
manage_container(
  operation="create",
  containerType="feature",
  name="Search Functionality Prototype",
  description="Quick prototype to test search UX patterns",
  projectId="[project-id]",
  status="draft",
  tags="prototype,frontend,spike"  // prototype tag triggers rapid_prototype_flow
)

// Step 2: Create and complete 1 task
// Step 3: Trigger all_tasks_complete event
// "Use Status Progression Skill to progress feature status.
// Context: All 1 tasks complete"
```

**Expected Results:**
- ✅ Feature tags matched to rapid_prototype_flow
- ✅ Flow: draft → in-development → completed
- ✅ **Skips testing phase** (no testing in rapid_prototype_flow)
- ✅ Feature moves directly to completed

**Decision Paths Tested:**
- [x] Tag-based flow routing (flow_mappings)
- [x] Alternative status flow (rapid_prototype_flow)
- [x] Config-driven flow selection

---

#### Test 2.11: Alternative Flow - with_review_flow

**Scenario**: Create security feature requiring review
**Expected**: Flow includes pending-review status

```javascript
// Step 1: Create feature with security tag
manage_container(
  operation="create",
  containerType="feature",
  name="Security Audit & Compliance",
  description="Comprehensive security audit and PCI compliance implementation",
  projectId="[project-id]",
  status="draft",
  tags="security,compliance,backend"  // security tag triggers with_review_flow
)

// Step 2: Complete tasks and progress through testing
// Step 3: Tests pass
// "Use Status Progression Skill to progress feature status.
// Context: Tests passed - all validation successful"
```

**Expected Results:**
- ✅ Feature tags matched to with_review_flow
- ✅ Flow: ... → testing → validating → pending-review → completed
- ✅ Feature moves to pending-review (not directly to completed)
- ✅ Awaits review_approved event

**Decision Paths Tested:**
- [x] Tag-based flow routing (security tag)
- [x] Alternative status flow (with_review_flow)
- [x] Review gate integration

---

### Phase 3: Task Orchestration Testing

#### Test 3.1: Parallel Execution - No Dependencies

**Scenario**: Create Product Catalog feature with 4 independent tasks
**Expected**: All tasks can run in parallel (Batch 1)

```javascript
// Step 1: Create feature
manage_container(
  operation="create",
  containerType="feature",
  name="Product Catalog",
  description="Product listing and filtering functionality",
  projectId="[project-id]",
  status="draft",
  tags="frontend,backend,database"
)

// Step 2: Create 4 independent tasks (no dependencies)
manage_container(
  operation="create",
  containerType="task",
  title="Create product database schema",
  featureId="[product-catalog-feature-id]",
  status="backlog",
  priority="high",
  complexity=5,
  tags="database,schema"
)

manage_container(
  operation="create",
  containerType="task",
  title="Build product API endpoints",
  featureId="[product-catalog-feature-id]",
  status="backlog",
  priority="high",
  complexity=6,
  tags="backend,api"
)

manage_container(
  operation="create",
  containerType="task",
  title="Create product list UI component",
  featureId="[product-catalog-feature-id]",
  status="backlog",
  priority="high",
  complexity=5,
  tags="frontend,react"
)

manage_container(
  operation="create",
  containerType="task",
  title="Implement search filters",
  featureId="[product-catalog-feature-id]",
  status="backlog",
  priority="medium",
  complexity=4,
  tags="frontend,react"
)

// Step 3: Analyze dependencies
query_container(
  operation="search",
  containerType="task",
  featureId="[product-catalog-feature-id]",
  status="backlog"
)

// For each task, check dependencies
query_dependencies(taskId="[task-1-id]", direction="incoming")
query_dependencies(taskId="[task-2-id]", direction="incoming")
query_dependencies(taskId="[task-3-id]", direction="incoming")
query_dependencies(taskId="[task-4-id]", direction="incoming")
// Expected: All return 0 dependencies

// Step 4: Create execution batches (manually verify pattern)
// Expected batching result:
// Batch 1 (Parallel): All 4 tasks (no dependencies)
// Estimated time savings: 75% (vs sequential)
```

**Expected Results:**
- ✅ Dependency analysis: 0 dependencies for all tasks
- ✅ Batching strategy: Full parallel execution (Strategy 2)
- ✅ Batch 1: All 4 tasks can launch simultaneously
- ✅ Time savings: ~75% vs sequential execution

**Decision Paths Tested:**
- [x] Dependency analysis (query_dependencies)
- [x] Full parallel execution strategy
- [x] Batch creation with no dependencies

---

#### Test 3.2: Sequential Execution - Full Dependencies

**Scenario**: Create Order Processing feature with linear dependency chain
**Expected**: All tasks run sequentially (Batch 1 → 2 → 3 → 4)

```javascript
// Step 1: Create feature
manage_container(
  operation="create",
  containerType="feature",
  name="Order Processing Pipeline",
  description="End-to-end order processing workflow",
  projectId="[project-id]",
  status="draft",
  tags="backend,database"
)

// Step 2: Create 4 sequential tasks
task1 = manage_container(
  operation="create",
  containerType="task",
  title="Create orders database schema",
  featureId="[order-processing-feature-id]",
  status="backlog",
  priority="high",
  complexity=6,
  tags="database,schema"
)

task2 = manage_container(
  operation="create",
  containerType="task",
  title="Implement order creation API",
  featureId="[order-processing-feature-id]",
  status="backlog",
  priority="high",
  complexity=7,
  tags="backend,api"
)

task3 = manage_container(
  operation="create",
  containerType="task",
  title="Add payment integration",
  featureId="[order-processing-feature-id]",
  status="backlog",
  priority="high",
  complexity=8,
  tags="backend,payment"
)

task4 = manage_container(
  operation="create",
  containerType="task",
  title="Implement order fulfillment",
  featureId="[order-processing-feature-id]",
  status="backlog",
  priority="medium",
  complexity=6,
  tags="backend,integration"
)

// Step 3: Create dependencies (linear chain)
manage_dependency(
  operation="create",
  fromTaskId="[task-1-id]",  // Orders schema
  toTaskId="[task-2-id]",    // Order creation API
  type="BLOCKS"
)

manage_dependency(
  operation="create",
  fromTaskId="[task-2-id]",  // Order creation API
  toTaskId="[task-3-id]",    // Payment integration
  type="BLOCKS"
)

manage_dependency(
  operation="create",
  fromTaskId="[task-3-id]",  // Payment integration
  toTaskId="[task-4-id]",    // Order fulfillment
  type="BLOCKS"
)

// Step 4: Analyze dependencies
query_dependencies(taskId="[task-1-id]", direction="incoming")
// Expected: 0 dependencies (can start immediately)

query_dependencies(taskId="[task-2-id]", direction="incoming")
// Expected: 1 dependency (blocked by task-1)

query_dependencies(taskId="[task-3-id]", direction="incoming")
// Expected: 1 dependency (blocked by task-2)

query_dependencies(taskId="[task-4-id]", direction="incoming")
// Expected: 1 dependency (blocked by task-3)

// Step 5: Create execution batches
// Expected batching result:
// Batch 1: Task 1 (no dependencies)
// Batch 2: Task 2 (depends on Task 1)
// Batch 3: Task 3 (depends on Task 2)
// Batch 4: Task 4 (depends on Task 3)
// No parallelism possible
```

**Expected Results:**
- ✅ Dependency analysis: Linear chain detected
- ✅ Batching strategy: Sequential execution (Strategy 1)
- ✅ 4 batches created (one task per batch)
- ✅ No time savings (must run sequentially)

**Decision Paths Tested:**
- [x] Dependency analysis with BLOCKS relationships
- [x] Sequential execution strategy
- [x] Batch creation with full dependencies

---

#### Test 3.3: Hybrid Batched Execution

**Scenario**: Create Shopping Cart feature with mixed dependencies
**Expected**: Mix of parallel and sequential batches

```javascript
// Step 1: Create feature
manage_container(
  operation="create",
  containerType="feature",
  name="Shopping Cart System",
  description="Shopping cart with add/remove items and checkout",
  projectId="[project-id]",
  status="draft",
  tags="frontend,backend,database"
)

// Step 2: Create 5 tasks with hybrid dependencies
// T1: Cart database schema (no dependencies)
// T2: Cart API endpoints (depends on T1)
// T3: Cart UI component (no dependencies)
// T4: Checkout workflow (depends on T2 and T3)
// T5: Unit tests (depends on T4)

task1 = manage_container(
  operation="create",
  containerType="task",
  title="Create cart database schema",
  featureId="[shopping-cart-feature-id]",
  status="backlog",
  priority="high",
  complexity=5,
  tags="database,schema"
)

task2 = manage_container(
  operation="create",
  containerType="task",
  title="Implement cart API endpoints",
  featureId="[shopping-cart-feature-id]",
  status="backlog",
  priority="high",
  complexity=7,
  tags="backend,api"
)

task3 = manage_container(
  operation="create",
  containerType="task",
  title="Create cart UI component",
  featureId="[shopping-cart-feature-id]",
  status="backlog",
  priority="high",
  complexity=6,
  tags="frontend,react"
)

task4 = manage_container(
  operation="create",
  containerType="task",
  title="Implement checkout workflow",
  featureId="[shopping-cart-feature-id]",
  status="backlog",
  priority="high",
  complexity=8,
  tags="backend,frontend,integration"
)

task5 = manage_container(
  operation="create",
  containerType="task",
  title="Write integration tests",
  featureId="[shopping-cart-feature-id]",
  status="backlog",
  priority="medium",
  complexity=5,
  tags="testing"
)

// Step 3: Create dependencies
manage_dependency(operation="create", fromTaskId="[task-1-id]", toTaskId="[task-2-id]", type="BLOCKS")
manage_dependency(operation="create", fromTaskId="[task-2-id]", toTaskId="[task-4-id]", type="BLOCKS")
manage_dependency(operation="create", fromTaskId="[task-3-id]", toTaskId="[task-4-id]", type="BLOCKS")
manage_dependency(operation="create", fromTaskId="[task-4-id]", toTaskId="[task-5-id]", type="BLOCKS")

// Step 4: Analyze dependencies and create batches
// Expected batching result:
// Batch 1 (Parallel): T1, T3 (no dependencies)
// Batch 2 (Sequential): T2 (depends on T1)
// Batch 3 (Sequential): T4 (depends on T2 and T3)
// Batch 4 (Sequential): T5 (depends on T4)
// Estimated time savings: 40% (2 tasks parallel in Batch 1)
```

**Expected Results:**
- ✅ Dependency analysis: Hybrid pattern detected
- ✅ Batching strategy: Hybrid batched execution (Strategy 3)
- ✅ Batch 1: 2 parallel tasks (T1, T3)
- ✅ Batches 2-4: Sequential tasks
- ✅ Time savings: ~40%

**Decision Paths Tested:**
- [x] Hybrid dependency pattern
- [x] Parallel opportunity identification
- [x] Mixed batching strategy
- [x] Time savings calculation

---

#### Test 3.4: Circular Dependency Detection

**Scenario**: Create tasks with circular dependency
**Expected**: Error detected, cannot create batches

```javascript
// Step 1: Create feature
manage_container(
  operation="create",
  containerType="feature",
  name="Circular Dependency Test",
  description="Testing circular dependency detection",
  projectId="[project-id]",
  status="draft",
  tags="testing"
)

// Step 2: Create 3 tasks
task1 = manage_container(
  operation="create",
  containerType="task",
  title="Task A",
  featureId="[circular-test-feature-id]",
  status="backlog"
)

task2 = manage_container(
  operation="create",
  containerType="task",
  title="Task B",
  featureId="[circular-test-feature-id]",
  status="backlog"
)

task3 = manage_container(
  operation="create",
  containerType="task",
  title="Task C",
  featureId="[circular-test-feature-id]",
  status="backlog"
)

// Step 3: Create circular dependencies
// A → B → C → A (circular!)
manage_dependency(operation="create", fromTaskId="[task-a-id]", toTaskId="[task-b-id]", type="BLOCKS")
manage_dependency(operation="create", fromTaskId="[task-b-id]", toTaskId="[task-c-id]", type="BLOCKS")
manage_dependency(operation="create", fromTaskId="[task-c-id]", toTaskId="[task-a-id]", type="BLOCKS")

// Step 4: Try to create execution batches
// Expected: Circular dependency detected
```

**Expected Results:**
- ❌ Circular dependency DETECTED
- ❌ Error: "Circular dependencies detected: A → B → C → A"
- ✅ Clear error message with cycle path
- ✅ Suggestion: "Use Dependency Orchestration Skill to resolve"
- ✅ Cannot proceed with execution

**Decision Paths Tested:**
- [x] Circular dependency detection
- [x] Error reporting with cycle path
- [x] Execution blocked until resolved

---

#### Test 3.5: Specialist Routing

**Scenario**: Route tasks to appropriate specialists
**Expected**: recommend_agent returns correct specialist for each task

```javascript
// Test various task types and tags
tasks = [
  {
    title: "Create database migration",
    tags: ["database", "migration", "schema"],
    expectedSpecialist: "Implementation Specialist"
  },
  {
    title: "Implement REST API",
    tags: ["backend", "api", "rest"],
    expectedSpecialist: "Implementation Specialist"
  },
  {
    title: "Build React dashboard",
    tags: ["frontend", "react", "ui"],
    expectedSpecialist: "Implementation Specialist"
  },
  {
    title: "Fix performance bug",
    tags: ["bug", "performance", "optimization"],
    expectedSpecialist: "Senior Engineer"
  },
  {
    title: "Write API documentation",
    tags: ["documentation", "api-docs"],
    expectedSpecialist: "Implementation Specialist"
  }
]

// For each task, verify routing
for (task of tasks) {
  taskId = createTask(task)
  recommendation = recommend_agent(taskId=taskId)

  // Verify recommendation matches expected
  assert(recommendation.agent == task.expectedSpecialist)
}
```

**Expected Results:**
- ✅ Database/backend/frontend tasks → Implementation Specialist (Haiku)
- ✅ Bug/error/complex tasks → Senior Engineer (Sonnet)
- ✅ Documentation tasks → Implementation Specialist (Haiku)
- ✅ Tags matched correctly to agent mappings

**Decision Paths Tested:**
- [x] Specialist routing via recommend_agent
- [x] Tag-based routing logic
- [x] Agent mapping patterns

---

#### Test 3.6: Task Event - work_started

**Scenario**: Specialist starts task
**Expected**: Task moves backlog → in-progress

```javascript
// Step 1: Create task in backlog
task = manage_container(
  operation="create",
  containerType="task",
  title="Implement user authentication",
  featureId="[feature-id]",
  status="backlog",
  priority="high",
  complexity=7,
  tags="backend,api,security"
)

// Step 2: Check dependencies (none for this test)
deps = query_dependencies(taskId="[task-id]", direction="incoming")
// Expected: 0 dependencies

// Step 3: Start work (trigger event)
// "Use Status Progression Skill to start task"
```

**Expected Results:**
- ✅ Event detected: work_started
- ✅ Dependency validation: No blockers
- ✅ Task status: backlog → in-progress
- ✅ StatusValidator validates prerequisites

**Decision Paths Tested:**
- [x] Event detection: work_started
- [x] Dependency validation before starting
- [x] Status progression: backlog → in-progress

---

#### Test 3.7: Task Event - implementation_complete

**Scenario**: Specialist completes implementation
**Expected**: Task moves in-progress → testing (or next status per config)

```javascript
// Step 1: Add summary to task
manage_container(
  operation="update",
  containerType="task",
  id="[task-id]",
  summary="Implemented JWT-based authentication with login/logout endpoints, password hashing using bcrypt, refresh token rotation, and comprehensive error handling. Added middleware for route protection and token validation. Test coverage 94% with unit and integration tests. Files: auth.controller.ts, auth.service.ts, auth.middleware.ts, auth.test.ts"
)

// Step 2: Update sections (specialist would do this)
manage_sections(
  operation="add",
  entityType="TASK",
  entityId="[task-id]",
  title="Implementation Details",
  usageDescription="Technical implementation details",
  content="JWT authentication implementation using jsonwebtoken library...",
  contentFormat="MARKDOWN",
  ordinal=0
)

// Step 3: Trigger event
// "Use Status Progression Skill to progress task status.
// Context: Implementation complete, summary populated (380 chars)"
```

**Expected Results:**
- ✅ Event detected: implementation_complete
- ✅ Summary validation: 300-500 chars ✓
- ✅ Sections updated ✓
- ✅ Task status: in-progress → testing (default_flow)
- ✅ Config-driven next status

**Decision Paths Tested:**
- [x] Event detection: implementation_complete
- [x] Summary length validation
- [x] Section update detection
- [x] Status progression: in-progress → testing

---

#### Test 3.8: Task Validation Failure - Missing Summary

**Scenario**: Try to complete task without summary
**Expected**: StatusValidator blocks completion

```javascript
// Step 1: Task in in-progress, no summary
task = query_container(operation="get", containerType="task", id="[task-id]")
// Expected: summary is null or empty

// Step 2: Try to mark complete
// "Use Status Progression Skill to mark task complete"
```

**Expected Results:**
- ❌ StatusValidator BLOCKS completion
- ❌ Error: "Task summary is required before completion"
- ✅ Task remains in in-progress status
- ✅ Clear guidance: Add summary (300-500 chars)

**Decision Paths Tested:**
- [x] Summary validation before completion
- [x] Error handling for missing summary
- [x] StatusValidator enforcement

---

#### Test 3.9: Task Validation Failure - Summary Wrong Length

**Scenario**: Try to complete task with summary too short or too long
**Expected**: StatusValidator blocks completion

```javascript
// Test A: Summary too short
manage_container(
  operation="update",
  containerType="task",
  id="[task-id]",
  summary="Added auth. Tests pass."  // 24 chars - TOO SHORT
)
// "Use Status Progression Skill to mark task complete"
// Expected: ❌ Error: "Summary must be 300-500 characters (current: 24)"

// Test B: Summary too long
manage_container(
  operation="update",
  containerType="task",
  id="[task-id]",
  summary="[600 character summary...]"  // 600 chars - TOO LONG
)
// "Use Status Progression Skill to mark task complete"
// Expected: ❌ Error: "Summary must be 300-500 characters (current: 600)"
```

**Expected Results:**
- ❌ StatusValidator BLOCKS completion
- ❌ Error: "Summary must be 300-500 characters (current: X)"
- ✅ Task remains in current status
- ✅ Clear guidance: Adjust summary length

**Decision Paths Tested:**
- [x] Summary length validation (< 300 chars)
- [x] Summary length validation (> 500 chars)
- [x] Error message clarity

---

#### Test 3.10: Task Validation Failure - Incomplete Blockers

**Scenario**: Try to start task with incomplete blocking dependencies
**Expected**: StatusValidator blocks start

```javascript
// Step 1: Create 2 tasks with dependency
task1 = manage_container(
  operation="create",
  containerType="task",
  title="Setup database",
  status="backlog"
)

task2 = manage_container(
  operation="create",
  containerType="task",
  title="Create API (depends on database)",
  status="backlog"
)

manage_dependency(operation="create", fromTaskId="[task-1-id]", toTaskId="[task-2-id]", type="BLOCKS")

// Step 2: Try to start task2 without completing task1
// "Use Status Progression Skill to start task: Create API"
```

**Expected Results:**
- ❌ StatusValidator BLOCKS start
- ❌ Error: "Cannot start task: blocked by incomplete dependencies"
- ✅ Lists blocking task: "Setup database (pending)"
- ✅ Task2 remains in backlog status

**Decision Paths Tested:**
- [x] Dependency validation before starting task
- [x] Blocker detection
- [x] Error handling for incomplete blockers

---

#### Test 3.11: Cascade Detection

**Scenario**: Complete task that blocks other tasks
**Expected**: Dependent tasks detected as unblocked

```javascript
// Step 1: Setup from Test 3.10 (task1 blocks task2)

// Step 2: Complete task1
manage_container(
  operation="update",
  containerType="task",
  id="[task-1-id]",
  summary="Created database schema with user, product, and order tables. Implemented migrations using Flyway. All migrations tested successfully. Database seeded with test data for development. Files: V1__create_schema.sql, V2__seed_data.sql, migration.config"
)
// "Use Status Progression Skill to mark task complete"
// Expected: task1 status → completed

// Step 3: Check cascade
query_dependencies(taskId="[task-1-id]", direction="outgoing", includeTaskInfo=true)
// Expected: 1 outgoing dependency to task2

query_dependencies(taskId="[task-2-id]", direction="incoming", includeTaskInfo=true)
// Expected: 1 incoming dependency from task1 (now completed)

// Step 4: Verify task2 is unblocked
// Count incomplete blockers for task2
// Expected: 0 incomplete blockers (task1 is completed)
// Result: "Task 'Create API' is now unblocked and ready to start"
```

**Expected Results:**
- ✅ Task1 completed
- ✅ Outgoing dependencies detected
- ✅ Task2 unblocked automatically
- ✅ Notification: "Task 'Create API' is now unblocked"
- ✅ Task2 ready to start

**Decision Paths Tested:**
- [x] Cascade detection after task completion
- [x] Outgoing dependency queries
- [x] Blocker counting
- [x] Unblock notification

---

### Phase 4: Token Efficiency Testing

#### Test 4.1: Overview vs Full Get

**Scenario**: Compare token usage for status checks
**Expected**: 90%+ token reduction with overview

```javascript
// Test A: Full get with sections (inefficient)
fullFeature = query_container(
  operation="get",
  containerType="feature",
  id="[feature-id]",
  includeSections=true
)
// Expected token usage: ~18,500 tokens (feature + 10 sections + 20 tasks)

// Test B: Overview operation (efficient)
overviewFeature = query_container(
  operation="overview",
  containerType="feature",
  id="[feature-id]"
)
// Expected token usage: ~1,200 tokens (feature metadata + task list + counts)

// Savings calculation
savings = (18500 - 1200) / 18500 * 100
// Expected: ~93% token reduction
```

**Expected Results:**
- ✅ Full get: ~18,500 tokens
- ✅ Overview: ~1,200 tokens
- ✅ Token savings: 93%
- ✅ Overview provides sufficient info for status checks

**Decision Paths Tested:**
- [x] Token efficiency comparison
- [x] Overview operation usage
- [x] Validate overview returns necessary data

---

#### Test 4.2: Batch Status Check Efficiency

**Scenario**: Check status of 10 tasks
**Expected**: Search more efficient than individual gets

```javascript
// Test A: Individual gets (inefficient)
for (taskId of taskIds) {
  task = query_container(
    operation="get",
    containerType="task",
    id=taskId,
    includeSections=true
  )
}
// Expected token usage: ~2,800 tokens × 10 = 28,000 tokens

// Test B: Batch search (efficient)
tasks = query_container(
  operation="search",
  containerType="task",
  featureId="[feature-id]",
  status="in-progress"
)
// Expected token usage: ~1,200 tokens total

// Savings calculation
savings = (28000 - 1200) / 28000 * 100
// Expected: ~95% token reduction
```

**Expected Results:**
- ✅ Individual gets: ~28,000 tokens
- ✅ Batch search: ~1,200 tokens
- ✅ Token savings: 95%
- ✅ Search returns minimal necessary data

**Decision Paths Tested:**
- [x] Batch operation efficiency
- [x] Search operation usage
- [x] Token optimization patterns

---

## Test Execution Checklist

### Feature Orchestration ✓
- [ ] Test 2.1: Simple feature creation
- [ ] Test 2.2: Complex feature creation (architect recommendation)
- [ ] Test 2.3: Event - first_task_started
- [ ] Test 2.4: Event - all_tasks_complete
- [ ] Test 2.5: Event - tests_passed
- [ ] Test 2.6: Event - completion_requested
- [ ] Test 2.7: Validation failure - no tasks
- [ ] Test 2.8: Validation failure - incomplete tasks
- [ ] Test 2.9: Backward movement - tests failed
- [ ] Test 2.10: Alternative flow - rapid_prototype_flow
- [ ] Test 2.11: Alternative flow - with_review_flow

### Task Orchestration ✓
- [ ] Test 3.1: Parallel execution - no dependencies
- [ ] Test 3.2: Sequential execution - full dependencies
- [ ] Test 3.3: Hybrid batched execution
- [ ] Test 3.4: Circular dependency detection
- [ ] Test 3.5: Specialist routing
- [ ] Test 3.6: Task event - work_started
- [ ] Test 3.7: Task event - implementation_complete
- [ ] Test 3.8: Validation failure - missing summary
- [ ] Test 3.9: Validation failure - summary wrong length
- [ ] Test 3.10: Validation failure - incomplete blockers
- [ ] Test 3.11: Cascade detection

### Token Efficiency ✓
- [ ] Test 4.1: Overview vs full get
- [ ] Test 4.2: Batch status check efficiency

---

## Success Criteria

**Feature Orchestration:**
- ✅ All 7 event triggers detected correctly
- ✅ Config-driven flows work (default, rapid_prototype, with_review)
- ✅ Prerequisites validated at write-time
- ✅ Backward movement works when enabled
- ✅ Error messages clear and actionable

**Task Orchestration:**
- ✅ All 4 execution strategies work correctly
- ✅ Circular dependencies detected
- ✅ Cascade detection works
- ✅ Specialist routing accurate
- ✅ All validation failures blocked correctly

**Token Efficiency:**
- ✅ 90%+ token reduction with overview operations
- ✅ 95%+ token reduction with batch operations
- ✅ Skills load < 500 lines (under limit)

---

## Reporting Template

For each test, record:

```markdown
### Test X.Y: [Test Name]

**Status**: ✅ PASS / ❌ FAIL / ⚠️ PARTIAL
**Date**: YYYY-MM-DD
**Tester**: [Name]

**Observations**:
- [What happened]
- [Any unexpected behavior]

**Issues Found**:
- [Issue 1]
- [Issue 2]

**Recommendation**:
- [Fix needed / Working as expected]
```

---

## Next Steps After Testing

1. **Document Issues**: Create GitHub issues for any failures
2. **Update Skills**: Fix any incorrect guidance in SKILL.md files
3. **Update Documentation**: Correct any inaccurate docs
4. **Performance Tuning**: Optimize any slow operations
5. **Monitor Production**: Track real-world usage patterns
6. **Plan Project Orchestration**: After feature/task stable, implement project-orchestration skill
