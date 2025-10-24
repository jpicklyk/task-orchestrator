# Task Orchestration Skill - Examples

## Example 1: Execute Feature Tasks with Parallel Batching

**User Request:**
> "Execute tasks for authentication feature"

**Scenario:**
- 5 tasks total
- Dependencies: Database → Backend → Tests
- UI has no dependencies

**Actions:**
1. Get all pending tasks
2. Query dependencies for each
3. Create execution batches

**Batch Analysis:**
```
Batch 1 (Parallel):
├─ T1: Create database schema (no dependencies)
└─ T3: Create UI components (no dependencies)
  Can run simultaneously

Batch 2 (Sequential):
└─ T2: Implement API (depends on T1)
  Must wait for database

Batch 3 (Parallel):
├─ T4: Unit tests (depends on T2)
└─ T5: Documentation (depends on T2)
  Can run simultaneously after API
```

**Output:**
```
Execution Plan Created: 3 batches, 40% time saved

Batch 1 - Ready to Launch (Parallel):
1. Database Engineer → Create database schema
2. Frontend Developer → Create UI components

Launch these 2 specialists in parallel now?
Next batch will launch automatically when Batch 1 completes.
```

---

## Example 2: Launch Parallel Batch

**User Request:**
> "Launch next task batch"

**Actions:**
1. Get next ready batch (Batch 1)
2. For each task, recommend specialist
3. Prepare parallel launch instructions

**Output:**
```
Launching Batch 1 in PARALLEL:

Specialist 1: Database Engineer
  Task: Create database schema (T1)
  Complexity: 5
  Tags: database, schema, migration

Specialist 2: Frontend Developer
  Task: Create UI components (T3)
  Complexity: 6
  Tags: frontend, ui, react

Both specialists launching now...
Monitor progress with: "show task progress"
```

---

## Example 3: Monitor Parallel Execution

**User Request:**
> "Show task progress"

**Actions:**
1. Get current batch tasks
2. Check status of each task
3. Calculate completion percentage

**Output:**
```
Batch 1 Progress: 1/2 (50%)

✓ Create database schema (T1)
  Status: completed
  Specialist: Database Engineer
  Duration: 25 minutes

⏳ Create UI components (T3)
  Status: in-progress (80% done)
  Specialist: Frontend Developer
  Est. completion: 5 minutes

Batch 2 is queued and will launch automatically when T3 completes.
```

---

## Example 4: Dependency Cascade Triggering

**User Request:**
> "Task T1 is complete"

**Actions:**
1. Mark T1 as completed
2. Check if batch complete
3. Batch not complete (T3 still running)
4. Wait for T3

**5 minutes later, T3 completes:**

**Actions:**
1. Mark T3 as completed
2. Check if batch complete: Yes
3. Get next batch (Batch 2)
4. Check dependencies satisfied: Yes
5. Trigger cascade

**Output:**
```
✓ Batch 1 Complete (2/2 tasks)
  • Database schema ✓
  • UI components ✓

Triggering Batch 2:

Specialist: Backend Engineer
  Task: Implement API endpoints (T2)
  Complexity: 7
  Dependencies: T1 (satisfied)

Launching specialist now...
```

---

## Example 5: Task Completion with Summary

**User Request:**
> "Complete API implementation task"

**Actions:**
1. Specialist reports work complete
2. Create summary section
3. Create files changed section
4. Mark task completed
5. Check for cascade

**Output:**
```
✓ Task Completed: Implement API endpoints

Summary:
Implemented REST API with 5 endpoints:
- POST /auth/register
- POST /auth/login
- POST /auth/logout
- POST /auth/refresh
- GET /auth/status

Includes JWT token handling, validation, and error responses.
Test coverage: 85%

Files Changed:
- src/api/auth.controller.ts (new)
- src/api/auth.service.ts (new)
- src/api/auth.dto.ts (new)
- tests/api/auth.test.ts (new)

Cascade: Batch 2 complete. Launching Batch 3 (2 parallel tasks)...
```

---

## Example 6: Specialist Routing with Fallback

**User Request:**
> "Execute task: Optimize performance"

**Scenario:**
- Task has no domain-specific tags
- recommend_agent returns no match

**Actions:**
1. recommend_agent(task_id) → no match
2. Check config: fallback_behavior = "use_default"
3. Use default_specialist: "Backend Engineer"

**Output:**
```
Task: Optimize performance
Tags: optimization, performance (no domain match)

No specialist matched by tags.
Using fallback: Backend Engineer (default)

Launching Backend Engineer for this task.

Note: Consider adding more specific tags:
- For DB: database, query-optimization
- For frontend: frontend, ui-performance
- For backend: backend, api-optimization
```

---

## Example 7: Handle Blocked Task

**User Request:**
> "Why won't task T5 start?"

**Actions:**
1. Check task dependencies
2. Find incomplete blockers
3. Report blocking tasks

**Output:**
```
Task T5 (Integration tests) is BLOCKED

Blocked by 2 incomplete dependencies:
1. T2: Implement API endpoints
   Status: in-progress (60% complete)
   Est. completion: 20 minutes

2. T3: Create UI components
   Status: pending (not started)
   Blocker: Waiting for batch slot

Resolution:
- T2 is actively being worked on
- T3 will start when slot available
- T5 will automatically unblock when both complete

Estimated unblock: 30 minutes
```

---

## Example 8: Circular Dependency Detection

**User Request:**
> "Execute feature tasks"

**Actions:**
1. Build dependency graph
2. Detect circular dependency
3. Cannot create batches

**Output:**
```
✗ Error: Circular Dependencies Detected

Cycle found:
T2 → T5 → T7 → T2

Tasks involved:
- T2: Implement API
- T5: Add caching layer
- T7: Update API for cache

Resolution options:
1. Remove T7 → T2 dependency (likely unnecessary)
2. Split T2 into two tasks (API-v1, API-v2)
3. Reorder: Complete T2 before T5

Use Dependency Orchestration Skill for detailed analysis.
```

---

## Example 9: Resource-Limited Parallel Execution

**Configuration:**
```yaml
parallelism:
  max_parallel_tasks: 3
```

**Scenario:**
- Batch 1 has 5 parallelizable tasks
- Must respect limit of 3

**Actions:**
1. Split batch into sub-batches
2. Launch first 3 tasks
3. Queue remaining 2 tasks

**Output:**
```
Batch 1 split into sub-batches (max_parallel_tasks: 3)

Sub-batch 1a (launching now):
├─ T1: Database schema
├─ T2: Email service
└─ T3: UI components

Sub-batch 1b (queued):
├─ T4: Notification service
└─ T5: File upload service

Sub-batch 1b will launch when any task from 1a completes.
```

---

## Example 10: All Tasks Complete - Feature Ready

**User Request:**
> "Check task status"

**Actions:**
1. Monitor last batch
2. All batches complete
3. Notify feature ready

**Output:**
```
✓ All Task Batches Complete

Feature: Authentication System
Tasks completed: 5/5

Batch Summary:
- Batch 1: 2 tasks (parallel) - 25 min
- Batch 2: 1 task (sequential) - 30 min
- Batch 3: 2 tasks (parallel) - 15 min

Total time: 70 minutes
Time saved: 40% (vs 120 min sequential)

Feature Status: All tasks complete
Next: Use Feature Orchestration Skill to:
- Trigger testing
- Validate quality gates
- Mark feature complete
```

---

## Integration Patterns

### Pattern 1: Full Automated Workflow

```
1. Feature Orchestration: Creates feature + tasks
2. Task Orchestration: Creates execution batches
3. Task Orchestration: Launches Batch 1 (parallel)
4. Specialists: Complete tasks, return summaries
5. Task Orchestration: Auto-cascades to Batch 2
6. Repeat until all batches complete
7. Feature Orchestration: Validates and completes
```

### Pattern 2: Manual Batch Control

```yaml
# Configuration
parallelism:
  auto_launch: false  # Suggest only, don't launch
```

```
1. Task Orchestration: Analyzes and suggests batches
2. User: Reviews and approves
3. User: "Launch batch 1"
4. Task Orchestration: Launches approved batch
5. Repeat for each batch
```

### Pattern 3: With Dependency Analysis

```
1. User: "This feature has complex dependencies"
2. Dependency Orchestration: Analyzes graph
3. Dependency Orchestration: Finds bottlenecks
4. Task Orchestration: Uses analysis for batching
5. Task Orchestration: Prioritizes critical path
```

---

## Token Efficiency Examples

### Efficient Batch Status Check
```javascript
// Check batch without full task details
tasks_overview = query_container(
  operation="search",
  containerType="task",
  featureId="uuid",
  status="in-progress"
)
// Returns: ~400 tokens (ID, title, status only)
```

### Batch Launch Instructions
```javascript
// Minimal specialist context
"Launch Backend Engineer for task T2 (uuid-2)"
// Specialist reads full context themselves
// Total: ~50 tokens to orchestrator
```

**vs Old Pattern (Feature/Task Managers):**
```javascript
// Pass full task context to manager
"Here's the complete task with all sections..."
// Then manager passes to specialist
// Total: ~2,900 tokens
```

**Savings: 98% token reduction in routing**

---

## Detailed Batching Example with Output Format

**Given 4 tasks with dependencies:**
- T1 (Database Schema) - no dependencies
- T2 (API Implementation) - depends on T1
- T3 (UI Components) - no dependencies
- T4 (Integration Tests) - depends on T2 and T3

**Batching Result:**
- Batch 1: T1, T3 (parallel - no dependencies)
- Batch 2: T2 (sequential - depends on T1)
- Batch 3: T4 (sequential - depends on T2, T3)

**JSON Output Format:**
```json
{
  "batches": [
    {
      "batch_number": 1,
      "parallel": true,
      "task_count": 2,
      "tasks": [
        {
          "id": "uuid-1",
          "title": "Create database schema",
          "complexity": 5,
          "specialist": "Implementation Specialist",
          "skills_loaded": ["database-implementation"],
          "dependencies": []
        },
        {
          "id": "uuid-3",
          "title": "Create UI components",
          "complexity": 6,
          "specialist": "Implementation Specialist",
          "skills_loaded": ["frontend-implementation"],
          "dependencies": []
        }
      ]
    },
    {
      "batch_number": 2,
      "parallel": false,
      "task_count": 1,
      "tasks": [
        {
          "id": "uuid-2",
          "title": "Implement API endpoints",
          "complexity": 7,
          "specialist": "Implementation Specialist",
          "skills_loaded": ["backend-implementation"],
          "dependencies": ["uuid-1"]
        }
      ]
    }
  ],
  "total_batches": 2,
  "estimated_time_savings": "40%"
}
```

---

## Orchestrator Launch Instruction Format

**For parallel batch launch:**

```markdown
Launch the following specialists in PARALLEL (Batch 1):

1. **Implementation Specialist (Haiku)**
   - Task: Create database schema (uuid-1)
   - Complexity: 5
   - Skills: database-implementation

2. **Implementation Specialist (Haiku)**
   - Task: Create UI components (uuid-3)
   - Complexity: 6
   - Skills: frontend-implementation

Wait for both to complete before proceeding to Batch 2.
```

**Key:** Orchestrator launches specialists using Task tool, not the skill itself.

---

## Parallel Execution Patterns

### Pattern 1: Domain Isolation
```
Database tasks  → Backend tasks
Frontend tasks  ↗
```
**Analysis:** Database and Frontend can run parallel (different domains, no shared dependencies).

### Pattern 2: Layer Separation
```
Data Layer → Business Logic → Presentation Layer
```
**Analysis:** Must be sequential (dependencies between layers).

### Pattern 3: Feature Isolation
```
Auth module    → Integration
Reporting module ↗
```
**Analysis:** Independent modules can run parallel, integrate at the end.

### Pattern 4: Test Parallelism
```
Unit tests      (parallel)
Integration tests (parallel)
E2E tests       (sequential after all)
```
**Analysis:** Test types can often run concurrently, E2E waits for all code complete.

---

## Cascade Detection Pattern

**After task completes:**

```javascript
// Step 1: Get completed task
completedTask = query_container(operation="get", containerType="task", id=taskId)

// Step 2: Check if this task blocks other tasks
outgoingDeps = query_dependencies(
  taskId=taskId,
  direction="outgoing",
  includeTaskInfo=true
)

if (outgoingDeps.dependencies.length > 0) {
  // Step 3: Check each dependent task to see if now unblocked
  for (dep of outgoingDeps.dependencies) {
    dependentTask = dep.toTask

    // Step 4: Check ALL incoming dependencies for the dependent task
    incomingDeps = query_dependencies(
      taskId=dependentTask.id,
      direction="incoming",
      includeTaskInfo=true
    )

    // Step 5: Count incomplete blockers
    incompleteBlockers = incomingDeps.dependencies.filter(d =>
      d.fromTask.status != "completed" && d.fromTask.status != "cancelled"
    ).length

    // Step 6: If no incomplete blockers, task is unblocked!
    if (incompleteBlockers == 0) {
      notify(`Task "${dependentTask.title}" is now unblocked and ready to start.`)
    }
  }
}

// Step 7: Check if feature can progress
if (completedTask.featureId) {
  // Trigger Feature Orchestration Skill to check feature progress
}
```

---

## Event Detection Examples

### Detection: Implementation Complete

```javascript
// After specialist finishes code + tests
task = query_container(operation="get", containerType="task", id=taskId)

// Check implementation is complete
sectionsUpdated = true  // Specialist updated Implementation Details section
filesChanged = true     // Specialist created Files Changed section
summaryLength = task.summary?.length || 0

if (sectionsUpdated && filesChanged && summaryLength >= 300 && summaryLength <= 500) {
  // EVENT DETECTED: implementation_complete

  "Use Status Progression Skill to progress task status.
  Context: Implementation complete, summary populated (${summaryLength} chars)."

  // Status Progression Skill determines next status based on config:
  // - default_flow: in-progress → testing
  // - with_review: in-progress → in-review
  // - documentation_flow: in-progress → in-review (no testing)
  // - hotfix_flow: in-progress → completed (skip validation)
}
```

### Detection: Task Completion (Cascade Check)

```javascript
// After marking task complete, check for dependency cascade
completedTask = query_container(operation="get", containerType="task", id=taskId)

// Check if this unblocks other tasks (see Cascade Detection Pattern above)

// Also check if feature can progress
if (completedTask.featureId) {
  // Trigger Feature Orchestration Skill to check all_tasks_complete event
}
```
