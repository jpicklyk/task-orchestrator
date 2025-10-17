# Orchestrator Guidelines for Parallel Task Execution

**A comprehensive guide for AI orchestrators managing concurrent, multi-agent workflows in Task Orchestrator**

---

## Table of Contents

- [Overview](#overview)
- [Core Concepts](#core-concepts)
  - [What is an Orchestrator?](#what-is-an-orchestrator)
  - [Parallel vs Sequential Execution](#parallel-vs-sequential-execution)
  - [Task Dependencies and Blocking](#task-dependencies-and-blocking)
- [Identifying Unblocked Tasks](#identifying-unblocked-tasks)
  - [Using get_next_task](#using-get_next_task)
  - [Manual Dependency Analysis](#manual-dependency-analysis)
  - [Parallel Opportunities Detection](#parallel-opportunities-detection)
- [When to Launch Parallel vs Sequential](#when-to-launch-parallel-vs-sequential)
  - [Sequential Execution Scenarios](#sequential-execution-scenarios)
  - [Parallel Execution Scenarios](#parallel-execution-scenarios)
  - [Decision Matrix](#decision-matrix)
- [Coordination Patterns](#coordination-patterns)
  - [Pattern 1: Single-Threaded Sequential](#pattern-1-single-threaded-sequential)
  - [Pattern 2: Batch Parallel Launch](#pattern-2-batch-parallel-launch)
  - [Pattern 3: Wave-Based Progression](#pattern-3-wave-based-progression)
  - [Pattern 4: Feature-Level Parallelism](#pattern-4-feature-level-parallelism)
- [Error Handling for Parallel Failures](#error-handling-for-parallel-failures)
  - [Failure Isolation](#failure-isolation)
  - [Partial Success Handling](#partial-success-handling)
  - [Rollback Strategies](#rollback-strategies)
  - [Dependency Chain Recovery](#dependency-chain-recovery)
- [Best Practices](#best-practices)
  - [Resource Management](#resource-management)
  - [Progress Tracking](#progress-tracking)
  - [Communication Patterns](#communication-patterns)
  - [Context Management](#context-management)
- [Common Anti-Patterns](#common-anti-patterns)
- [Complete Workflow Examples](#complete-workflow-examples)
  - [Example 1: 4 Independent Tasks (Full Parallel)](#example-1-4-independent-tasks-full-parallel)
  - [Example 2: 2-Stage Dependency Chain](#example-2-2-stage-dependency-chain)
  - [Example 3: Diamond Dependency Pattern](#example-3-diamond-dependency-pattern)
  - [Example 4: Multi-Feature Parallel Development](#example-4-multi-feature-parallel-development)
- [Troubleshooting](#troubleshooting)

---

## Overview

As an **orchestrator** in the Task Orchestrator system, you are the main AI instance coordinating work across multiple specialized agents. Your primary responsibility is to **maximize efficiency** by identifying opportunities for parallel execution while **maintaining correctness** through proper dependency management.

### Key Responsibilities

1. **Analyze task dependencies** to identify execution opportunities
2. **Launch sub-agents** (Feature Manager, Task Manager, Specialists) for parallel work
3. **Coordinate context passing** between dependent tasks
4. **Handle failures gracefully** to prevent cascade issues
5. **Track progress** across multiple concurrent workflows
6. **Maintain clean context** through brief summaries

### Benefits of Parallel Execution

- **Faster completion**: Multiple specialists work simultaneously
- **Better resource utilization**: Don't wait idle while specialists work
- **Natural workflow**: Mirrors real team collaboration
- **Scalability**: Project size doesn't affect parallelism opportunities

---

## Core Concepts

### What is an Orchestrator?

**Orchestrator** = The main Claude Code instance (Level 0 in the 3-level architecture)

**You are the orchestrator if**:
- You are reading this guide
- You launch sub-agents (Feature Manager, Task Manager, Specialists)
- You receive brief summaries from sub-agents
- You accumulate conversation history across the project

**You are NOT a sub-agent** - sub-agents include Feature Manager, Task Manager, and all Specialists. They work for you and report back.

### Parallel vs Sequential Execution

**Sequential Execution**:
```
Task 1 → Task 2 → Task 3 → Task 4
[Complete T1] → [Then T2] → [Then T3] → [Then T4]
```
- Tasks execute one after another
- Each task waits for previous to complete
- **Use when**: Tasks have dependencies or risk conflicts

**Parallel Execution**:
```
Task 1 ─┐
Task 2 ─┼─→ [All execute simultaneously]
Task 3 ─┤
Task 4 ─┘
```
- Multiple tasks execute at the same time
- Tasks complete independently
- **Use when**: Tasks are unblocked and independent

### Task Dependencies and Blocking

**Dependency**: Task A depends on Task B means "A cannot start until B is completed"

**Blocked Task**: A task with incomplete dependencies
```
Task A (pending) → Task B (in-progress) → Task C (pending)
                   ✅ Can work on B
                   ❌ Cannot start C (blocked by B)
```

**Unblocked Task**: A task with all dependencies completed (or no dependencies)
```
Task A (completed) → Task B (completed) → Task C (pending)
                                          ✅ Can start C (unblocked)
```

**Key Point**: Only unblocked tasks are safe to start. The system handles this automatically through `get_next_task`.

---

## Identifying Unblocked Tasks

### Using get_next_task

**The recommended approach** - let the system identify unblocked tasks automatically.

#### Tool Usage

```
get_next_task(
  featureId='[feature-uuid]',
  limit=10,
  includeDetails=true
)
```

**What it does**:
- Reads all tasks in the feature
- Filters out blocked tasks automatically
- Returns only tasks safe to start
- Sorted by priority and dependencies

**Example Response**:
```json
{
  "tasks": [
    {
      "id": "task-uuid-1",
      "title": "Create database schema",
      "status": "pending",
      "priority": "high",
      "blockedBy": []
    },
    {
      "id": "task-uuid-2",
      "title": "Setup authentication service",
      "status": "pending",
      "priority": "high",
      "blockedBy": []
    },
    {
      "id": "task-uuid-3",
      "title": "Build UI components",
      "status": "pending",
      "priority": "medium",
      "blockedBy": []
    }
  ]
}
```

**Interpretation**:
- All 3 tasks are unblocked (empty `blockedBy`)
- Can launch all 3 in parallel if appropriate
- High priority tasks (1 & 2) should start first

#### When to Use

✅ **Use get_next_task when**:
- Starting a new feature
- Checking for next available work
- Want system to handle dependency logic
- Unsure which tasks are ready

❌ **Don't use when**:
- You already know specific task to work on
- Working on a single task (no need to check next)
- In middle of task execution

### Manual Dependency Analysis

Sometimes you need to analyze dependencies manually (e.g., when reviewing feature structure).

#### Check Task Dependencies

```
get_task_dependencies(
  taskId='[task-uuid]',
  direction='incoming',
  includeTaskInfo=true
)
```

**Direction options**:
- `incoming`: Tasks that BLOCK this task (what must complete first)
- `outgoing`: Tasks that this task BLOCKS (what depends on this)
- `both`: Both incoming and outgoing

**Example Response**:
```json
{
  "incoming": [
    {
      "id": "dependency-uuid",
      "fromTaskId": "task-schema",
      "toTaskId": "task-api",
      "fromTaskTitle": "Create database schema",
      "fromTaskStatus": "completed"
    }
  ],
  "outgoing": [
    {
      "id": "dependency-uuid-2",
      "fromTaskId": "task-api",
      "toTaskId": "task-ui",
      "toTaskTitle": "Build UI components",
      "toTaskStatus": "pending"
    }
  ]
}
```

**Interpretation**:
- This task (`task-api`) is unblocked because incoming dependency is `completed`
- Once this task completes, `task-ui` will become unblocked
- Safe to start `task-api`

#### Manual Unblocked Check

**Algorithm**:
```
FOR each task in feature:
  1. Get incoming dependencies
  2. Check status of each dependency
  3. IF all dependencies are "completed":
     → Task is UNBLOCKED
  4. ELSE:
     → Task is BLOCKED
```

**Warning**: Manual checking is error-prone. Prefer `get_next_task` when possible.

### Parallel Opportunities Detection

**How to find tasks that can run in parallel**:

#### Step 1: Get All Unblocked Tasks
```
get_next_task(featureId='...', limit=10)
```

#### Step 2: Check for Conflicts

**Tasks can run in parallel if**:
✅ Both are unblocked
✅ No shared file dependencies
✅ Different specialists (or same specialist but independent work)
✅ No logical conflicts (e.g., both creating same function)

**Tasks should NOT run in parallel if**:
❌ One depends on the other
❌ Both modify the same files
❌ Require sequential ordering (e.g., schema → migration → API)
❌ User explicitly requested sequential execution

#### Step 3: Group by Independence

**Example Analysis**:
```
Unblocked tasks:
1. Create database schema (Database Engineer, creates migration files)
2. Setup authentication service (Backend Engineer, creates auth/ directory)
3. Design UI mockups (Frontend Developer, creates design/ directory)
4. Write API documentation (Technical Writer, creates docs/ files)

Analysis:
- All 4 tasks touch different files → No file conflicts
- All 4 use different specialists → Can run truly parallel
- No logical dependencies → Safe to parallelize

Decision: Launch all 4 in parallel
```

**Counter-Example**:
```
Unblocked tasks:
1. Create User table schema (Database Engineer)
2. Create Product table schema (Database Engineer)
3. Create Order table schema (Database Engineer)

Analysis:
- All 3 use Database Engineer → Same specialist
- All 3 modify same migration directory
- Foreign key relationships between tables (Order → User, Order → Product)

Decision: Execute sequentially (User → Product → Order)
```

---

## When to Launch Parallel vs Sequential

### Sequential Execution Scenarios

**Use sequential execution when**:

#### 1. Strong Dependencies Exist
```
Task A (Database schema) → Task B (API using schema) → Task C (UI using API)
```
- B needs A's output to function
- C needs B's output to function
- **Must execute**: A → B → C

#### 2. Same Specialist, Related Work
```
Task A: Create User authentication
Task B: Create User authorization
Specialist: Backend Engineer
```
- Same codebase area
- Potential merge conflicts
- Second task builds on first
- **Prefer sequential** unless explicitly independent

#### 3. Logical Ordering Required
```
Task A: Design database schema
Task B: Write migration
Task C: Update ORM models
```
- Design should inform migration
- Migration should inform models
- **Sequential execution maintains logical flow**

#### 4. Resource Constraints
```
You (orchestrator) are approaching context limits
```
- Each parallel task adds brief to your context
- Too many parallel tasks = context overflow
- **Limit to 3-4 parallel tasks at once**

#### 5. Testing Dependencies
```
Task A: Implement feature
Task B: Write unit tests
Task C: Write integration tests
```
- Tests need implementation to exist
- Integration tests need unit tests to pass
- **Sequential ensures quality gates**

### Parallel Execution Scenarios

**Use parallel execution when**:

#### 1. Truly Independent Tasks
```
Task A: Create database schema (Database Engineer)
Task B: Design UI mockups (Frontend Developer)
Task C: Write API documentation (Technical Writer)
```
- Different specialists
- Different files/directories
- No shared dependencies
- **Perfect for parallelism**

#### 2. Same Domain, Different Modules
```
Task A: Implement User service (Backend Engineer - user/)
Task B: Implement Product service (Backend Engineer - product/)
Task C: Implement Order service (Backend Engineer - order/)
```
- Same specialist type
- But different modules/packages
- No shared code
- **Can parallelize** if carefully coordinated

#### 3. Multiple Features Simultaneously
```
Feature 1: User Authentication (5 tasks)
Feature 2: Product Catalog (7 tasks)
Feature 3: Shopping Cart (6 tasks)
```
- Features are independent
- Each feature has own Feature Manager
- **Feature-level parallelism scales indefinitely**

#### 4. Horizontal Scaling Tasks
```
Task A: Write documentation for Module A (Technical Writer)
Task B: Write documentation for Module B (Technical Writer)
Task C: Write documentation for Module C (Technical Writer)
```
- Same work type
- Different subjects
- No dependencies
- **Parallelize for speed**

### Decision Matrix

| Scenario | Sequential | Parallel | Reason |
|----------|-----------|----------|---------|
| Task B depends on Task A | ✅ | ❌ | Dependency |
| Different specialists, no shared files | ❌ | ✅ | Independent |
| Same file modifications | ✅ | ❌ | Conflict risk |
| User requested specific order | ✅ | ❌ | Explicit requirement |
| Testing depends on implementation | ✅ | ❌ | Logical dependency |
| Documentation for different modules | ❌ | ✅ | Independent content |
| Multiple features in same project | ❌ | ✅ | Feature isolation |
| Schema → API → UI flow | ✅ | ❌ | Layered architecture |
| Approaching context limits | ✅ | ❌ | Resource constraint |
| 4+ unblocked independent tasks | ❌ | ✅ | Efficiency |

---

## Coordination Patterns

### Pattern 1: Single-Threaded Sequential

**When to Use**:
- Simple features with clear dependencies
- All tasks have sequential dependencies
- Learning the system (easiest pattern)

**Workflow**:
```
1. Orchestrator → Feature Manager START
   → Recommends Task 1

2. Orchestrator → Task Manager START (T1)
   → Recommends Specialist A

3. Orchestrator → Specialist A
   → Completes T1

4. Orchestrator → Task Manager END (T1)
   → Creates Summary

5. Orchestrator → Feature Manager START
   → Recommends Task 2

6. Repeat steps 2-5 for each task
```

**Advantages**:
- Simple to manage
- Clear progress tracking
- No coordination complexity

**Disadvantages**:
- Slow (no parallelism)
- Underutilizes resources
- Not optimal for independent tasks

### Pattern 2: Batch Parallel Launch

**When to Use**:
- Multiple independent tasks available
- All tasks can start immediately
- Want to maximize speed

**Workflow**:
```
1. Orchestrator → get_next_task(limit=10)
   → Returns: Task 1, Task 2, Task 3, Task 4

2. Check for independence:
   → All 4 tasks unblocked
   → Different files
   → Different specialists

3. Launch all 4 in parallel:
   Orchestrator → T1 workflow (Database Engineer)
   Orchestrator → T2 workflow (Backend Engineer)
   Orchestrator → T3 workflow (Frontend Developer)
   Orchestrator → T4 workflow (Technical Writer)

4. Wait for all 4 to complete

5. Orchestrator → get_next_task
   → Returns next batch
```

**Advantages**:
- Maximum parallelism
- Fastest completion
- Efficient resource usage

**Disadvantages**:
- More complex to coordinate
- Requires careful independence verification
- Context grows faster (4 briefs vs 1)

**Example Code Flow**:
```
// Launch all 4 workflows
Launch Task Manager START (T1) → Specialist → Task Manager END
Launch Task Manager START (T2) → Specialist → Task Manager END
Launch Task Manager START (T3) → Specialist → Task Manager END
Launch Task Manager START (T4) → Specialist → Task Manager END

// Collect briefs from all 4
Brief 1: "Completed schema. Created migration V3..."
Brief 2: "Implemented auth service. Created UserController..."
Brief 3: "Built login UI. Created LoginForm component..."
Brief 4: "Documented auth API. Updated api-docs.md..."

// Your context grows by ~800 tokens (4 × 200), not 28k (4 × 7k)
```

### Pattern 3: Wave-Based Progression

**When to Use**:
- Tasks have layered dependencies
- Some tasks can parallelize within layers
- Want balance of speed and simplicity

**Dependency Graph**:
```
Wave 1: T1, T2 (parallel)
         ↓
Wave 2: T3 (depends on T1 and T2)
         ↓
Wave 3: T4, T5 (parallel, both depend on T3)
```

**Workflow**:
```
WAVE 1:
1. get_next_task → Returns T1, T2
2. Verify independence
3. Launch T1 and T2 in parallel
4. Wait for both to complete

WAVE 2:
5. get_next_task → Returns T3 (now unblocked)
6. Launch T3 (receives T1 and T2 summaries)
7. Wait for T3 to complete

WAVE 3:
8. get_next_task → Returns T4, T5
9. Verify independence
10. Launch T4 and T5 in parallel
11. Wait for both to complete
```

**Advantages**:
- Balances speed and safety
- Natural for layered architectures
- Prevents dependency violations

**Disadvantages**:
- Requires wave boundary detection
- Some idle time between waves
- More complex than pure sequential

### Pattern 4: Feature-Level Parallelism

**When to Use**:
- Multiple features in same project
- Features are independent
- Want maximum project-level throughput

**Project Structure**:
```
Project: E-commerce Platform
├── Feature 1: User Authentication (5 tasks)
├── Feature 2: Product Catalog (7 tasks)
└── Feature 3: Shopping Cart (6 tasks)
```

**Workflow**:
```
PARALLEL FEATURE DEVELOPMENT:

Feature 1 Thread:
  Orchestrator → Feature Manager (F1) → Tasks T1-T5

Feature 2 Thread:
  Orchestrator → Feature Manager (F2) → Tasks T6-T12

Feature 3 Thread:
  Orchestrator → Feature Manager (F3) → Tasks T13-T18

Each feature progresses independently
```

**Advantages**:
- Scales to any project size
- Features don't block each other
- Natural team collaboration model

**Disadvantages**:
- Requires careful feature design
- Cross-feature dependencies complicate
- Higher orchestrator coordination

**Context Management**:
```
Traditional approach:
  All 18 tasks × 7k tokens = 126k tokens

Feature-level parallelism:
  18 tasks × 200 token briefs = 3.6k tokens
  Still 97% reduction!
```

---

## Error Handling for Parallel Failures

### Failure Isolation

**Key Principle**: One task failing should not crash the entire workflow.

#### Strategy 1: Continue on Failure

**When**: Independent tasks with no downstream dependencies

```
Parallel execution:
  Task 1: Success ✅
  Task 2: Failed ❌
  Task 3: Success ✅
  Task 4: Success ✅

Response:
  → Mark Task 2 as failed
  → Continue with Task 3 and Task 4
  → Report failure to user
  → Retry Task 2 later
```

#### Strategy 2: Stop Dependent Branch

**When**: Failed task blocks other tasks

```
Dependency chain:
  Task A (success) → Task B (failed) → Task C (blocked)
                                    → Task D (blocked)

Response:
  → Mark Task B as failed
  → Block Task C and D (dependencies incomplete)
  → Continue with other independent tasks
  → User must fix Task B before C and D can proceed
```

#### Strategy 3: Rollback Wave

**When**: Entire wave must succeed atomically

```
Wave execution:
  Task 1: Success
  Task 2: Success
  Task 3: Failed ← Critical task
  Task 4: Success

Response:
  → All tasks in wave must rollback
  → Revert Task 1, 2, and 4 changes
  → Report wave failure
  → Fix Task 3, retry entire wave
```

### Partial Success Handling

**Scenario**: 4 tasks launched in parallel, 3 succeed, 1 fails

#### Option A: Accept Partial Success (Recommended)
```
✅ Task 1: Database schema created
✅ Task 2: Auth service implemented
❌ Task 3: UI component failed
✅ Task 4: Documentation written

Action:
1. Keep successful tasks (1, 2, 4) as completed
2. Mark Task 3 as failed with error details
3. Create summaries for successful tasks
4. Update task sections with failure context
5. Report to user: "3 of 4 completed. Task 3 failed: [reason]"
6. User can retry Task 3 independently
```

#### Option B: All-or-Nothing (Rare)
```
Action:
1. Rollback all 4 tasks to pending
2. Revert file changes from successful tasks
3. Report to user: "Batch failed due to Task 3. All rolled back."
4. Retry entire batch after fixing issue
```

**When to use All-or-Nothing**:
- Tasks modify shared state atomically
- Database transactions across tasks
- User explicitly requests atomic execution
- Testing scenario requiring clean state

### Rollback Strategies

#### File-Based Rollback

**For code changes**:
```
1. Specialist creates feature branch
2. All changes committed to branch
3. On success: Merge to main
4. On failure: Delete branch

OR (if no git):
1. Before starting, snapshot affected files
2. On failure, restore from snapshot
3. Report what was reverted
```

#### Database Rollback

**For schema changes**:
```
1. Create reversible migrations (with down() method)
2. On failure, run migration rollback
3. Restore to previous schema version

Example:
  Migration V5__add_users_table.sql (failed)
  → Run: V5__add_users_table_rollback.sql
  → Schema returns to V4 state
```

#### Task State Rollback

**For task orchestrator state**:
```
1. On failure, set task status back to 'pending'
2. Delete any sections created during failed execution
3. Remove incomplete summaries
4. Clear in-progress markers

Tool usage:
  set_status(id='task-uuid', status='pending')
  delete_section(id='section-uuid')
```

### Dependency Chain Recovery

**Scenario**: Task B fails in a dependency chain A → B → C → D

#### Recovery Steps

**Step 1: Identify Impact**
```
get_task_dependencies(taskId='B', direction='outgoing')

Response:
  → Task C is blocked by B
  → Task D is blocked by C
  → Total impact: 2 tasks blocked
```

**Step 2: Mark Blocked Tasks**
```
Update task summaries:
  Task C: "Blocked by failed Task B. Cannot proceed."
  Task D: "Blocked by failed Task B (via Task C)."
```

**Step 3: Continue Independent Work**
```
get_next_task(featureId='...')

Response:
  → Returns only tasks not blocked by B
  → Could be tasks in parallel branch
  → Could be tasks in different feature
```

**Step 4: Fix and Resume**
```
1. User/specialist fixes Task B
2. set_status(id='B', status='completed')
3. Create Task B summary
4. get_next_task() now returns Task C
5. Task C launches with Task B summary as dependency context
6. Chain resumes: C → D
```

#### Parallel Branch Handling

**Dependency Structure**:
```
      T1
     ↙  ↘
   T2    T3
    ↓     ↓
   T4    T5
    ↘  ↙
     T6
```

**Failure Scenario**: T2 fails, T3 succeeds

**Response**:
```
✅ T3 continues → T5 completes
❌ T2 failed → T4 blocked
⏸️  T6 partially blocked (needs both T4 and T5)

Actions:
1. Complete T3 → T5 branch (no impact from T2 failure)
2. Block T4 (depends on failed T2)
3. Block T6 (depends on incomplete T4, even though T5 done)
4. Fix T2 → T4 completes → T6 unblocked
```

**Key Insight**: Parallel branches are isolated until they converge.

---

## Best Practices

### Resource Management

#### Context Budget

**Your context is limited** - manage it carefully:

```
Rule of Thumb:
  - 1 task workflow = ~200 tokens in your context (brief only)
  - 10 parallel tasks = ~2k tokens
  - 100 sequential tasks = ~20k tokens (still manageable)
  - Limit parallel batches to 3-5 tasks at once
```

**Strategy**:
```
❌ Bad: Launch 20 tasks in parallel
   → 4k tokens added to context immediately
   → Hard to track progress
   → Overwhelming to coordinate

✅ Good: Launch 4 tasks, wait, launch next 4
   → 800 tokens per batch
   → Clear progress checkpoints
   → Easier to handle failures
```

#### Specialist Utilization

**Don't overload same specialist**:

```
❌ Bad: 5 Backend Engineer tasks in parallel
   → All compete for same code areas
   → Merge conflicts likely
   → No actual parallelism (sequential anyway)

✅ Good: 1 Backend, 1 Frontend, 1 Database, 1 Testing
   → True parallelism
   → No conflicts
   → Different codebases
```

#### System Load

**Be aware of system constraints**:

```
Considerations:
  - File system locks (same file modifications)
  - Database connections (migration conflicts)
  - External API rate limits (testing, deployments)
  - User attention (reviewing multiple PRs)
```

### Progress Tracking

#### Track Completion Status

**Maintain awareness of parallel work**:

```
Parallel batch launched:
  ⏳ Task 1: Database schema (Database Engineer)
  ⏳ Task 2: Auth service (Backend Engineer)
  ⏳ Task 3: Login UI (Frontend Developer)
  ⏳ Task 4: API docs (Technical Writer)

After 5 minutes:
  ✅ Task 1: Completed
  ⏳ Task 2: Still working
  ✅ Task 3: Completed
  ✅ Task 4: Completed

After 10 minutes:
  ✅ Task 1: Completed
  ✅ Task 2: Completed ← Just finished
  ✅ Task 3: Completed
  ✅ Task 4: Completed

All done! Proceed to next wave.
```

#### Use Feature Manager for Status

**Periodic progress checks**:

```
Every N tasks completed:
  Feature Manager START → Get progress update

Response:
  "Feature: User Authentication
   Progress: 12/18 tasks completed
   In Progress: 3 tasks
   Blocked: 2 tasks
   Available: 1 task"

Action:
  → Launch available task
  → Wait for in-progress to complete
  → Review blocked tasks for resolution
```

### Communication Patterns

#### Brief Summaries Only

**Remember**: You only keep brief summaries (2-3 sentences)

```
✅ Good brief:
  "Completed database schema. Created V3__add_users_table.sql migration
   with UUID primary keys and email indexing. Ready for API implementation."

❌ Bad brief (too much detail):
  "Completed database schema. The migration file includes:
   CREATE TABLE users (
     id BLOB PRIMARY KEY DEFAULT (randomblob(16)),
     username TEXT NOT NULL UNIQUE,
     email TEXT NOT NULL UNIQUE,
     password_hash TEXT NOT NULL,
     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
   );
   CREATE INDEX idx_users_email ON users(email);
   [... 50 more lines ...]"

   ^ This bloats your context unnecessarily!
```

#### Dependency Context Passing

**When launching dependent task**:

```
Task B depends on Task A (completed)

Your workflow:
1. Task Manager START (B) provides:
   "Task B brief + Task A summary (300-500 tokens)"

2. You pass this to specialist:
   "Here's the task and what Task A completed:
    [Task Manager's brief with dependency context]"

3. Specialist receives full context, you only kept the brief
```

### Context Management

#### Accumulation Pattern

**Track what goes in your context**:

```
Session start: 0 tokens

After Task 1 workflow:
  + 200 tokens (brief)
  = 200 tokens total

After Task 2 workflow (parallel with T3):
  + 200 tokens (T2 brief)
  = 400 tokens total

After Task 3 workflow:
  + 200 tokens (T3 brief)
  = 600 tokens total

After 20 tasks:
  = 4,000 tokens total

Still manageable! Without sub-agents would be 140k+ tokens.
```

#### Context Cleanup

**When context gets large**:

```
Option 1: Summarize conversation history
  → Ask Claude to summarize last 50 messages
  → Keep summary, discard details
  → Reduces 10k tokens → 500 tokens

Option 2: Session restart
  → Save feature state
  → Start new Claude Code session
  → Load context with get_overview()
  → Continue work fresh

Option 3: Feature completion
  → Complete current feature
  → Feature Manager END creates summary
  → Start next feature in clean session
```

---

## Common Anti-Patterns

### Anti-Pattern 1: "Launch Everything in Parallel"

**What it looks like**:
```
Orchestrator: "I see 15 tasks. Let me launch all 15 at once!"

Result:
  ❌ Context explodes (15 × 200 = 3k tokens immediately)
  ❌ Hard to track which completed
  ❌ Likely has dependencies (not all actually independent)
  ❌ Failures difficult to isolate
```

**Fix**:
```
✅ Analyze dependencies first
✅ Launch 3-5 at a time in batches
✅ Wait for wave to complete before next wave
✅ Verify independence before parallelizing
```

### Anti-Pattern 2: "Ignore Dependencies"

**What it looks like**:
```
Orchestrator: "Task B depends on Task A, but Task B looks ready. Let me start it!"

Result:
  ❌ Task B fails (needs Task A output)
  ❌ Waste specialist time
  ❌ May create incorrect implementation
  ❌ Rollback required
```

**Fix**:
```
✅ Always check dependencies with get_task_dependencies
✅ Use get_next_task to auto-filter blocked tasks
✅ Trust the dependency system
✅ Wait for dependencies to complete
```

### Anti-Pattern 3: "Over-Coordinate"

**What it looks like**:
```
Orchestrator:
  "I'll launch Task 1, wait for response, analyze, decide next step,
   check dependencies again, verify status, then maybe launch Task 2..."

Result:
  ❌ Slow (sequential when could be parallel)
  ❌ Over-thinking simple scenarios
  ❌ Misses parallelism opportunities
```

**Fix**:
```
✅ If get_next_task returns multiple unblocked tasks → parallelize
✅ Trust the tools to handle coordination
✅ Don't over-analyze obvious independent tasks
✅ Bias toward action, not deliberation
```

### Anti-Pattern 4: "Micromanage Specialists"

**What it looks like**:
```
Orchestrator:
  "Backend Engineer, make sure you use kotlin coroutines.
   And add error handling. And write tests. And follow clean
   architecture. And use repository pattern. And..."

Result:
  ❌ Specialist already knows best practices
  ❌ Context bloat with unnecessary guidance
  ❌ Distracts from actual task
```

**Fix**:
```
✅ Trust specialists to do their job
✅ Provide task context and dependency summaries
✅ Let specialist agent definition handle best practices
✅ Only intervene if specific project requirement
```

### Anti-Pattern 5: "Keep Full Context"

**What it looks like**:
```
Orchestrator accumulates:
  - Full specialist responses (code, explanations)
  - Complete task details
  - All file contents
  - Entire conversation histories

Result:
  ❌ Context explodes to 100k+ tokens
  ❌ Loses all benefits of sub-agent architecture
  ❌ Can't scale beyond 10-15 tasks
```

**Fix**:
```
✅ Keep only brief summaries (2-3 sentences)
✅ Details live in task sections, not your context
✅ Reference task IDs, not full content
✅ Aim for 97% token reduction
```

### Anti-Pattern 6: "Sequential Everything"

**What it looks like**:
```
Orchestrator:
  Feature has 10 tasks, all independent, all unblocked.
  "I'll do them one by one to be safe."

Result:
  ❌ 10× slower than necessary
  ❌ Poor resource utilization
  ❌ Misses entire point of parallelism
```

**Fix**:
```
✅ Check for independence
✅ Launch parallel when safe
✅ Use wave-based progression for mixed dependencies
✅ Default to parallelism, fall back to sequential only when needed
```

---

## Complete Workflow Examples

### Example 1: 4 Independent Tasks (Full Parallel)

**Scenario**: Feature with 4 documentation tasks, no dependencies

#### Setup

**Feature**: API Documentation Overhaul
**Tasks**:
- T1: Document authentication endpoints
- T2: Document user management endpoints
- T3: Document product catalog endpoints
- T4: Document order processing endpoints

**Analysis**:
- All tasks use Technical Writer specialist
- Different API domains (no overlap)
- Different files (auth.md, users.md, products.md, orders.md)
- No dependencies
- **Decision: Full parallel execution**

#### Execution Flow

```
STEP 1: Get unblocked tasks
────────────────────────────
get_next_task(featureId='feature-uuid', limit=10)

Response:
{
  "tasks": [
    {"id": "T1", "title": "Document authentication endpoints", "blockedBy": []},
    {"id": "T2", "title": "Document user management endpoints", "blockedBy": []},
    {"id": "T3", "title": "Document product catalog endpoints", "blockedBy": []},
    {"id": "T4", "title": "Document order processing endpoints", "blockedBy": []}
  ]
}

STEP 2: Verify independence
────────────────────────────
- All 4 unblocked ✅
- Same specialist (Technical Writer) but different subjects ✅
- Different files ✅
- No logical conflicts ✅

Decision: Launch all 4 in parallel

STEP 3: Launch parallel workflows
────────────────────────────────
Launch T1 workflow:
  Task Manager START (T1) → "Specialist: Technical Writer, Focus: requirements,documentation"
  Technical Writer → Documents auth endpoints
  Task Manager END (T1) → "Completed auth docs. Created auth-api.md with OAuth 2.0 flows."

Launch T2 workflow:
  Task Manager START (T2) → "Specialist: Technical Writer, Focus: requirements,documentation"
  Technical Writer → Documents user endpoints
  Task Manager END (T2) → "Completed user docs. Created users-api.md with CRUD operations."

Launch T3 workflow:
  Task Manager START (T3) → "Specialist: Technical Writer, Focus: requirements,documentation"
  Technical Writer → Documents product endpoints
  Task Manager END (T3) → "Completed product docs. Created products-api.md with catalog queries."

Launch T4 workflow:
  Task Manager START (T4) → "Specialist: Technical Writer, Focus: requirements,documentation"
  Technical Writer → Documents order endpoints
  Task Manager END (T4) → "Completed order docs. Created orders-api.md with checkout flows."

STEP 4: Collect results
────────────────────────
All 4 tasks completed successfully.

Orchestrator context grew by:
  4 × 200 tokens = 800 tokens

Time saved:
  Sequential: 4 × 10 minutes = 40 minutes
  Parallel: ~10 minutes
  Savings: 75%

STEP 5: Continue feature
────────────────────────
get_next_task(featureId='feature-uuid')

Response: No more unblocked tasks

Feature Manager END:
  Creates feature summary
  Marks feature complete
```

### Example 2: 2-Stage Dependency Chain

**Scenario**: Feature with layered dependencies (database → API → UI)

#### Setup

**Feature**: User Management System
**Tasks**:
- T1: Create User table schema (Database Engineer)
- T2: Create Profile table schema (Database Engineer)
- T3: Implement User API endpoints (Backend Engineer) - depends on T1
- T4: Implement Profile API endpoints (Backend Engineer) - depends on T2
- T5: Build User UI components (Frontend Developer) - depends on T3
- T6: Build Profile UI components (Frontend Developer) - depends on T4

**Dependency Graph**:
```
T1 (DB: User) ──→ T3 (API: User) ──→ T5 (UI: User)
T2 (DB: Profile) → T4 (API: Profile) → T6 (UI: Profile)
```

**Analysis**:
- 2 parallel tracks (User and Profile)
- 3 waves (Database → API → UI)
- **Decision: Wave-based parallel execution**

#### Execution Flow

```
WAVE 1: Database Schemas
────────────────────────
get_next_task(featureId='feature-uuid', limit=10)

Response: T1, T2 (both unblocked)

Verify independence:
  - Different specialists? Same (Database Engineer)
  - Different tables? Yes (User vs Profile)
  - Foreign keys? No dependencies between tables
  - Decision: Can parallelize

Launch T1 and T2:
  T1 workflow → "Created Users table with id, username, email, password_hash"
  T2 workflow → "Created Profiles table with user_id FK, bio, avatar, preferences"

Both complete. Summaries created.

WAVE 2: API Endpoints
────────────────────────
get_next_task(featureId='feature-uuid', limit=10)

Response: T3, T4 (both now unblocked after T1 and T2 completed)

Verify independence:
  - Different specialists? Same (Backend Engineer)
  - Different controllers? Yes (UserController vs ProfileController)
  - Shared code? Minimal (both use database, but different tables)
  - Decision: Can parallelize

Launch T3 and T4:
  Task Manager START (T3) reads T1 Summary:
    "Task: User API endpoints
     Dependencies: Created Users table with id, username, email..."

  Task Manager START (T4) reads T2 Summary:
    "Task: Profile API endpoints
     Dependencies: Created Profiles table with user_id FK, bio..."

  T3 workflow → "Implemented User CRUD endpoints using Users table"
  T4 workflow → "Implemented Profile CRUD endpoints using Profiles table"

Both complete. Summaries created.

WAVE 3: UI Components
────────────────────────
get_next_task(featureId='feature-uuid', limit=10)

Response: T5, T6 (both now unblocked after T3 and T4 completed)

Verify independence:
  - Different specialists? Same (Frontend Developer)
  - Different components? Yes (UserProfile vs ProfileEditor)
  - Shared state? Separate (user state vs profile state)
  - Decision: Can parallelize

Launch T5 and T6:
  Task Manager START (T5) reads T3 Summary:
    "Task: User UI components
     Dependencies: Implemented User CRUD endpoints..."

  Task Manager START (T6) reads T4 Summary:
    "Task: Profile UI components
     Dependencies: Implemented Profile CRUD endpoints..."

  T5 workflow → "Built UserProfile component with API integration"
  T6 workflow → "Built ProfileEditor component with API integration"

Both complete. Feature done!

RESULTS
───────
Total waves: 3
Tasks per wave: 2 parallel
Total time: 3 × specialist time (instead of 6 × specialist time)
Time savings: 50%
Orchestrator context: 6 × 200 = 1,200 tokens
```

### Example 3: Diamond Dependency Pattern

**Scenario**: Feature with converging dependencies

#### Setup

**Feature**: Order Processing System
**Tasks**:
- T1: Create Order schema (Database Engineer)
- T2: Implement payment service (Backend Engineer) - depends on T1
- T3: Implement inventory service (Backend Engineer) - depends on T1
- T4: Implement shipping service (Backend Engineer) - depends on T1
- T5: Create order processing workflow (Backend Engineer) - depends on T2, T3, T4

**Dependency Graph**:
```
       T1 (Order schema)
      ↙  ↓  ↘
    T2  T3  T4 (Services)
      ↘  ↓  ↙
       T5 (Workflow)
```

**Analysis**:
- Diamond pattern (1 → many → 1)
- Wave 1: T1 (must be first)
- Wave 2: T2, T3, T4 (can parallelize)
- Wave 3: T5 (must be last)

#### Execution Flow

```
WAVE 1: Foundation
──────────────────
get_next_task(featureId='feature-uuid')

Response: T1 only (no dependencies)

Launch T1:
  Database Engineer → Creates Order table schema
  Summary: "Created Orders table with id, user_id, total, status, created_at"

WAVE 2: Services (Parallel)
────────────────────────────
get_next_task(featureId='feature-uuid', limit=10)

Response: T2, T3, T4 (all now unblocked by T1)

Verify independence:
  - Same specialist (Backend Engineer)
  - Different services (Payment, Inventory, Shipping)
  - Different packages (payment/, inventory/, shipping/)
  - No shared code (all use Order schema independently)
  - Decision: Full parallelism

Launch all 3:
  Task Manager START (T2) → Includes T1 Summary
  Task Manager START (T3) → Includes T1 Summary
  Task Manager START (T4) → Includes T1 Summary

  T2: "Implemented PaymentService with Stripe integration"
  T3: "Implemented InventoryService with stock checking"
  T4: "Implemented ShippingService with carrier APIs"

All complete. 3 summaries created.

WAVE 3: Integration
───────────────────
get_next_task(featureId='feature-uuid')

Response: T5 (now unblocked by T2, T3, T4)

Task Manager START (T5) reads 3 dependency summaries:
  "Dependencies (3 completed):
   - PaymentService with Stripe integration
   - InventoryService with stock checking
   - ShippingService with carrier APIs"

Launch T5:
  Backend Engineer receives all 3 summaries (900-1500 tokens total)
  Implements OrderWorkflow orchestrating all 3 services
  Summary: "Created OrderWorkflow coordinating payment, inventory, and shipping"

RESULTS
───────
Total waves: 3
Parallel wave: T2, T3, T4 (3 simultaneous)
Sequential waves: T1, then T5
Time: T1 + max(T2, T3, T4) + T5 ≈ 3× specialist time
  vs. Sequential: T1 + T2 + T3 + T4 + T5 = 5× specialist time
Time savings: 40%
Context efficiency: 5 × 200 = 1,000 tokens (vs 35k without sub-agents)
```

### Example 4: Multi-Feature Parallel Development

**Scenario**: Project with 3 independent features

#### Setup

**Project**: E-commerce Platform
**Features**:
- F1: User Authentication (4 tasks)
  - T1: Database schema
  - T2: Auth service
  - T3: Login UI
  - T4: Auth tests

- F2: Product Catalog (4 tasks)
  - T5: Database schema
  - T6: Product service
  - T7: Catalog UI
  - T8: Product tests

- F3: Shopping Cart (4 tasks)
  - T9: Database schema
  - T10: Cart service
  - T11: Cart UI
  - T12: Cart tests

**Analysis**:
- Features are independent (can develop in parallel)
- Each feature has internal dependencies (schema → service → UI → tests)
- **Decision: Feature-level parallelism with task-level sequencing**

#### Execution Flow

```
PARALLEL FEATURE DEVELOPMENT
─────────────────────────────

FEATURE 1 THREAD: User Authentication
──────────────────────────────────────
Orchestrator → Feature Manager START (F1)
  → Recommends T1 (database schema)

Orchestrator → T1 workflow
  → Database Engineer creates schema
  → Summary: "Created Users table"

Orchestrator → Feature Manager START (F1)
  → Recommends T2 (auth service)

Orchestrator → T2 workflow (includes T1 summary)
  → Backend Engineer implements auth
  → Summary: "Implemented AuthService using Users table"

Orchestrator → Feature Manager START (F1)
  → Recommends T3 (login UI)

Orchestrator → T3 workflow (includes T2 summary)
  → Frontend Developer builds UI
  → Summary: "Built LoginForm using AuthService"

Orchestrator → Feature Manager START (F1)
  → Recommends T4 (auth tests)

Orchestrator → T4 workflow (includes T2, T3 summaries)
  → Test Engineer writes tests
  → Summary: "Created auth integration tests"

Orchestrator → Feature Manager END (F1)
  → Creates feature summary
  → Marks F1 complete


FEATURE 2 THREAD: Product Catalog
──────────────────────────────────
[Same pattern as F1, progressing independently]

T5 → T6 → T7 → T8 → Feature complete


FEATURE 3 THREAD: Shopping Cart
────────────────────────────────
[Same pattern as F1 and F2, progressing independently]

T9 → T10 → T11 → T12 → Feature complete


COORDINATION
────────────
Orchestrator manages all 3 features:
  - Tracks progress across features
  - Each feature managed by its own Feature Manager invocations
  - Tasks within features executed sequentially (dependencies)
  - Features developed in parallel (independence)

RESULTS
───────
Total tasks: 12
Total features: 3

Without feature-level parallelism:
  F1 → F2 → F3 = 12 sequential workflows
  Time: 12 × specialist time

With feature-level parallelism:
  F1 || F2 || F3 = 4 tasks per feature, 3 in parallel
  Time: 4 × specialist time
  Time savings: 67%

Context in orchestrator:
  12 tasks × 200 tokens = 2,400 tokens
  (vs 84k tokens without sub-agents)
  Still 97% reduction!

Scalability:
  Could have 10 features developing in parallel
  Context still manageable
  Orchestrator coordinates at feature level
```

---

## Troubleshooting

### Issue: Tasks Launched in Wrong Order

**Symptoms**:
- Task B launches before Task A completes
- Task B fails because missing Task A output
- Dependency violation

**Root Cause**:
- Didn't check dependencies before launching
- Ignored `get_next_task` results
- Manually selected task without verification

**Solution**:
```
1. Use get_next_task to filter blocked tasks automatically
2. Always check Task B's incoming dependencies
3. Wait for dependencies to complete before launching
4. Trust the dependency system - it's there for a reason
```

### Issue: Parallel Tasks Conflict

**Symptoms**:
- File merge conflicts
- Both tasks modify same code
- Race conditions

**Root Cause**:
- Launched parallel without independence verification
- Same specialist, overlapping code areas
- Assumed independence without checking

**Solution**:
```
1. Before parallelizing, check:
   - Different files? ✅
   - Different specialists? ✅ (preferred)
   - No shared state? ✅
2. If conflicts possible, execute sequentially
3. Use git branches per task to isolate changes
4. Coordinate file modifications carefully
```

### Issue: Lost Track of Parallel Work

**Symptoms**:
- Don't know which tasks completed
- Launched too many at once
- Context confusing

**Root Cause**:
- Launched too many tasks simultaneously
- No progress tracking
- Context overload

**Solution**:
```
1. Limit parallel batches to 3-5 tasks
2. Wait for batch to complete before next
3. Use Feature Manager START periodically for status
4. Track completion in structured way:
   ✅ T1, ✅ T2, ⏳ T3, ⏳ T4
```

### Issue: Orchestrator Context Growing Too Large

**Symptoms**:
- Context approaching limits
- Responses getting slow
- Losing focus on current work

**Root Cause**:
- Keeping full specialist responses
- Not using brief summaries
- Internalizing too much detail

**Solution**:
```
1. Keep ONLY brief summaries (2-3 sentences)
2. Discard full specialist responses after extracting brief
3. Details live in task sections, not your context
4. If context too large, restart session:
   - Complete current feature
   - Start new session
   - Load context with get_overview()
```

### Issue: Feature Never Completes

**Symptoms**:
- All tasks done but feature stays "in-development"
- Can't move to next feature

**Root Cause**:
- Never called Feature Manager END
- Assumed feature auto-completes

**Solution**:
```
1. After all tasks complete, explicitly call:
   Feature Manager END (featureId='...')
2. Feature Manager checks all tasks, marks feature complete
3. Creates feature summary
4. Don't skip this step!
```

### Issue: Dependency Context Not Passed

**Symptoms**:
- Specialist doesn't know about previous task
- Implements without using dependency output
- Duplicate work or incompatible implementation

**Root Cause**:
- Task Manager didn't read dependency summaries
- Orchestrator didn't pass context to specialist
- Summary section missing from dependency

**Solution**:
```
1. Verify Task Manager START reads dependency summaries:
   get_task_dependencies(taskId='...', direction='incoming')
   get_sections(entityType='TASK', entityId='dep-id', tags='summary')

2. Verify Task Manager includes dependencies in brief

3. Verify Orchestrator passes full brief to specialist

4. Verify dependency task has Summary section:
   Task Manager END must create summary for every completed task
```

### Issue: Wrong Specialist Selected

**Symptoms**:
- Frontend Developer assigned backend task
- Specialist doesn't have right expertise
- Implementation incorrect or incomplete

**Root Cause**:
- Wrong task tags
- Agent mapping misconfigured
- Manual specialist selection (bypassed recommend_agent)

**Solution**:
```
1. Check task tags match agent-mapping.yaml:
   Task tags: ["backend", "api", "rest"]
   Should route to: Backend Engineer

2. Verify recommend_agent was called:
   Task Manager MUST call recommend_agent(taskId='...')

3. Check agent-mapping.yaml priority order

4. Update tags if needed:
   update_task(id='...', tags='correct,tags,here')
```

### Issue: Parallel Failure Cascades

**Symptoms**:
- One task fails
- All parallel tasks fail
- Entire wave lost

**Root Cause**:
- Tasks weren't actually independent
- Shared dependency failed
- Rollback strategy too aggressive

**Solution**:
```
1. Isolate failures - don't rollback successful tasks

2. If T1 fails but T2, T3, T4 succeed:
   - Keep T2, T3, T4 as completed
   - Only mark T1 as failed
   - Retry T1 independently

3. Only rollback if truly necessary:
   - Database transactions
   - Atomic operations
   - User requested all-or-nothing

4. Review independence verification before parallelizing
```

---

## Additional Resources

- **[Agent Orchestration Documentation](agent-orchestration.md)** - Complete 3-level architecture guide
- **[AI Guidelines](ai-guidelines.md)** - Autonomous workflow patterns
- **[Workflow Prompts](workflow-prompts.md)** - Step-by-step workflow templates
- **[API Reference](api-reference.md)** - Complete tool documentation
- **[get_next_task Tool](tools/get-next-task.md)** - Detailed tool usage
- **[recommend_agent Tool](tools/recommend-agent.md)** - Specialist routing guide

---

**Ready to orchestrate parallel workflows?** Start by identifying unblocked tasks with `get_next_task`, verify independence, and launch your first parallel batch!
