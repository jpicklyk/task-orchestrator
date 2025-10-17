# Parallel Processing Guide for Task Orchestrator

**Practical examples and guidance for coordinating parallel task execution**

---

## Table of Contents

- [Introduction](#introduction)
- [Quick Start: Your First Parallel Workflow](#quick-start-your-first-parallel-workflow)
- [When to Use Parallel Processing](#when-to-use-parallel-processing)
- [Step-by-Step Examples](#step-by-step-examples)
  - [Simple Batch: 4 Independent Tasks](#simple-batch-4-independent-tasks)
  - [Wave-Based: Mixed Dependencies](#wave-based-mixed-dependencies)
  - [Feature-Level: Multiple Features](#feature-level-multiple-features)
- [Launching Parallel Tasks](#launching-parallel-tasks)
  - [Finding Unblocked Tasks](#finding-unblocked-tasks)
  - [Verifying Independence](#verifying-independence)
  - [Executing the Batch](#executing-the-batch)
- [Best Practices](#best-practices)
  - [Batch Size Limits](#batch-size-limits)
  - [Context Management](#context-management)
  - [Progress Tracking](#progress-tracking)
  - [Handling Mixed Specialists](#handling-mixed-specialists)
- [Common Workflows](#common-workflows)
  - [Documentation Sprint](#documentation-sprint)
  - [Multi-Layer Architecture](#multi-layer-architecture)
  - [Cross-Feature Development](#cross-feature-development)
  - [Test Suite Creation](#test-suite-creation)
- [Troubleshooting](#troubleshooting)
  - [Tasks Not Appearing as Unblocked](#tasks-not-appearing-as-unblocked)
  - [Merge Conflicts in Parallel Work](#merge-conflicts-in-parallel-work)
  - [Losing Track of Progress](#losing-track-of-progress)
  - [One Task Fails in Parallel Batch](#one-task-fails-in-parallel-batch)
- [Advanced Patterns](#advanced-patterns)
  - [Diamond Dependencies](#diamond-dependencies)
  - [Conditional Parallelism](#conditional-parallelism)
  - [Rolling Waves](#rolling-waves)
- [Decision Flowchart](#decision-flowchart)

---

## Introduction

Parallel processing in Task Orchestrator allows you to execute multiple independent tasks simultaneously, dramatically reducing project completion time while maintaining clean context through the sub-agent architecture.

### What is Parallel Processing?

**Sequential Execution** (traditional):
```
Task 1 → Task 2 → Task 3 → Task 4
Total time: 40 minutes (4 × 10 min)
```

**Parallel Execution**:
```
Task 1 ─┐
Task 2 ─┼─→ All complete at same time
Task 3 ─┤
Task 4 ─┘
Total time: 10 minutes (max of all tasks)
```

### Parallel Batch Execution Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        ORCHESTRATOR                              │
│                                                                  │
│  1. Launches Feature Manager START                              │
│     → "Recommend next tasks (batch mode)"                       │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                     FEATURE MANAGER (START)                      │
│                                                                  │
│  2. get_next_task(featureId='...', limit=5)                     │
│     → Returns: [T1, T2, T3, T4, T5] (all unblocked)            │
│                                                                  │
│  3. Returns batch recommendation:                               │
│     "Recommended Tasks (Parallel Batch): 5                      │
│      1. T1 - Database schema                                    │
│      2. T2 - Auth service                                       │
│      3. T3 - User API                                           │
│      4. T4 - Product API                                        │
│      5. T5 - Order API"                                         │
└────────────────────────┬─────────────────────────────────────────┘
                         │
                         ▼
┌──────────────────────────────────────────────────────────────────┐
│                      ORCHESTRATOR                                │
│                                                                  │
│  4. Launches 5 Task Managers in parallel:                       │
└──┬───────┬───────┬───────┬───────┬───────────────────────────────┘
   │       │       │       │       │
   ▼       ▼       ▼       ▼       ▼
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│ TM  │ │ TM  │ │ TM  │ │ TM  │ │ TM  │  Task Manager (TM) Instances
│ T1  │ │ T2  │ │ T3  │ │ T4  │ │ T5  │  (Execute simultaneously)
└──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
   │       │       │       │       │
   ▼       ▼       ▼       ▼       ▼
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│ DB  │ │Back │ │Back │ │Back │ │Back │  Specialist Agents
│Engr │ │ End │ │ End │ │ End │ │ End │  (Work independently)
└──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
   │       │       │       │       │
   │ Work  │ Work  │ Work  │ Work  │ Work
   │       │       │       │       │
   ▼       ▼       ▼       ▼       ▼
┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
│ TM  │ │ TM  │ │ TM  │ │ TM  │ │ TM  │  Task Manager END
│END  │ │END  │ │END  │ │END  │ │END  │  (Create summaries)
└──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘ └──┬──┘
   │       │       │       │       │
   └───┬───┴───┬───┴───┬───┴───┬───┘
       │       │       │       │
       ▼       ▼       ▼       ▼
┌──────────────────────────────────────────────────────────────────┐
│                      ORCHESTRATOR                                │
│                                                                  │
│  5. Collects 5 brief summaries (200 tokens each = 1000 tokens)  │
│  6. Calls Feature Manager START again for next batch            │
└──────────────────────────────────────────────────────────────────┘

Time: 1 × specialist time (parallel execution)
vs: 5 × specialist time (sequential execution)
Savings: 80%
```

### Key Benefits

- **75% faster completion** for independent tasks
- **Efficient resource utilization** - no idle time
- **Natural workflow** - mirrors real team collaboration
- **Scalable** - works for 4 tasks or 40 tasks
- **Context-safe** - only brief summaries in your context (200 tokens each)

### Prerequisites

Before using parallel processing, ensure you understand:
- Task dependencies (blocking relationships)
- Sub-agent architecture (orchestrator → specialists)
- Brief summaries vs full context

---

## Quick Start: Your First Parallel Workflow

**Goal**: Launch 3 independent documentation tasks in parallel

### Step 1: Identify Available Work

```
get_next_task(featureId='your-feature-id', limit=10)
```

**Response**:
```json
{
  "tasks": [
    {"id": "T1", "title": "Document API endpoints", "blockedBy": []},
    {"id": "T2", "title": "Write user guide", "blockedBy": []},
    {"id": "T3", "title": "Create troubleshooting doc", "blockedBy": []}
  ]
}
```

### Step 2: Verify Independence

**Checklist**:
- ✅ All tasks show `"blockedBy": []` (unblocked)
- ✅ Different files (api-docs.md, user-guide.md, troubleshooting.md)
- ✅ Same or different specialists (Technical Writer)
- ✅ No shared state or conflicts

**Decision**: Safe to parallelize!

### Step 3: Launch All Three

**Launch T1**:
```
Task Manager START (T1)
→ Routes to Technical Writer
→ Completes documentation
Task Manager END (T1)
→ Creates summary: "Documented REST API endpoints. Created api-docs.md with 15 endpoints."
```

**Launch T2 (simultaneously)**:
```
Task Manager START (T2)
→ Routes to Technical Writer
→ Completes documentation
Task Manager END (T2)
→ Creates summary: "Wrote user guide. Created user-guide.md with setup instructions."
```

**Launch T3 (simultaneously)**:
```
Task Manager START (T3)
→ Routes to Technical Writer
→ Completes documentation
Task Manager END (T3)
→ Creates summary: "Created troubleshooting doc with common issues and solutions."
```

### Step 4: Collect Results

**Your context after all complete**:
```
3 tasks × 200 tokens = 600 tokens total
Time saved: 20 minutes (30 min parallel vs 50 min sequential)
```

**That's it!** You've completed your first parallel workflow.

---

## When to Use Parallel Processing

### Use Parallel Processing When...

#### ✅ Tasks Are Truly Independent

**Good example**:
```
T1: Document authentication (auth-api.md)
T2: Document users (users-api.md)
T3: Document products (products-api.md)
```
- Different files
- Different subjects
- No dependencies
- **Perfect for parallelism**

#### ✅ Different Specialists, Different Domains

**Good example**:
```
T1: Database schema (Database Engineer → migrations/)
T2: UI mockups (Frontend Developer → designs/)
T3: API docs (Technical Writer → docs/)
```
- Different specialists
- Different directories
- No conflicts possible
- **Ideal for parallelism**

#### ✅ Multiple Unblocked Tasks Available

**Good example**:
```
get_next_task() returns 5 tasks, all unblocked
```
- Efficiency opportunity
- No reason to wait
- **Launch in parallel (batches of 3-4)**

### Don't Use Parallel Processing When...

#### ❌ Tasks Have Dependencies

**Bad example**:
```
T1: Create database schema
T2: Implement API using schema  ← Depends on T1
T3: Build UI using API          ← Depends on T2
```
- T2 needs T1's output
- T3 needs T2's output
- **Must execute sequentially: T1 → T2 → T3**

#### ❌ Same Files Being Modified

**Bad example**:
```
T1: Add User authentication to AuthService.kt
T2: Add Admin authentication to AuthService.kt
```
- Both modify AuthService.kt
- Merge conflicts guaranteed
- **Execute sequentially**

#### ❌ Logical Ordering Required

**Bad example**:
```
T1: Design database schema
T2: Write migration
T3: Update ORM models
```
- Design should inform migration
- Migration should inform models
- **Sequential maintains logical flow**

---

## Step-by-Step Examples

### Simple Batch: 4 Independent Tasks

**Scenario**: Documentation sprint for different API modules

#### Context

**Feature**: API Documentation Overhaul
**Tasks**:
1. T1: Document auth endpoints (Technical Writer)
2. T2: Document user endpoints (Technical Writer)
3. T3: Document product endpoints (Technical Writer)
4. T4: Document order endpoints (Technical Writer)

**Analysis**:
- All unblocked ✅
- Different subjects (auth, users, products, orders) ✅
- Different files (auth-api.md, users-api.md, etc.) ✅
- Same specialist but independent work ✅
- **Decision: Full parallel execution**

#### Workflow

**1. Check for unblocked tasks**:
```
get_next_task(featureId='api-docs-feature', limit=10)

Response: T1, T2, T3, T4 all unblocked
```

**2. Launch all 4 simultaneously**:

```
Task Manager START (T1) → Technical Writer
  Task: Document auth endpoints
  Output: "Documented auth endpoints. Created auth-api.md with OAuth flows."

Task Manager START (T2) → Technical Writer
  Task: Document user endpoints
  Output: "Documented user CRUD. Created users-api.md with examples."

Task Manager START (T3) → Technical Writer
  Task: Document product endpoints
  Output: "Documented catalog API. Created products-api.md with search."

Task Manager START (T4) → Technical Writer
  Task: Document order endpoints
  Output: "Documented checkout flow. Created orders-api.md."
```

**3. Results**:
- All 4 complete in ~10 minutes (vs 40 minutes sequential)
- Orchestrator context: 800 tokens (4 × 200)
- Time savings: 75%

### Wave-Based: Mixed Dependencies

**Scenario**: User management feature with layered architecture

#### Context

**Feature**: User Management System
**Tasks**:
- T1: Create User table (Database Engineer)
- T2: Create Profile table (Database Engineer)
- T3: Implement User API (Backend Engineer) - depends on T1
- T4: Implement Profile API (Backend Engineer) - depends on T2
- T5: Build User UI (Frontend Developer) - depends on T3
- T6: Build Profile UI (Frontend Developer) - depends on T4

**Dependency Graph**:
```
Wave 1: T1, T2 (parallel)
         ↓
Wave 2: T3, T4 (parallel, after Wave 1)
         ↓
Wave 3: T5, T6 (parallel, after Wave 2)
```

**Wave-Based Coordination Diagram**:
```
┌─────────────────────────────────────────────────────────────────┐
│                          WAVE 1                                 │
│                    (Database Layer)                             │
└─────────────────────────────────────────────────────────────────┘

Orchestrator → Feature Manager START
                → get_next_task(limit=5)
                → Returns: [T1, T2] (only unblocked tasks)

    ┌─────────┐              ┌─────────┐
    │   T1    │              │   T2    │
    │  User   │  Parallel    │ Profile │
    │  Table  │  Execution   │  Table  │
    └────┬────┘              └────┬────┘
         │                        │
         │ Complete               │ Complete
         │ Summary: "Users..."    │ Summary: "Profiles..."
         │                        │
         └────────┬───────────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │   Both T1 & T2 Done   │
      │   T3 & T4 Unblocked   │ ← Dependency resolution
      └───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                          WAVE 2                                 │
│                      (API Layer)                                │
└─────────────────────────────────────────────────────────────────┘

Orchestrator → Feature Manager START
                → get_next_task(limit=5)
                → Returns: [T3, T4] (newly unblocked)

    ┌─────────┐              ┌─────────┐
    │   T3    │              │   T4    │
    │  User   │  Parallel    │ Profile │
    │   API   │  Execution   │   API   │
    │         │              │         │
    │ Reads:  │              │ Reads:  │
    │ T1 sum  │              │ T2 sum  │ ← Dependency context
    └────┬────┘              └────┬────┘
         │                        │
         │ Complete               │ Complete
         │ Summary: "API..."      │ Summary: "API..."
         │                        │
         └────────┬───────────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │   Both T3 & T4 Done   │
      │   T5 & T6 Unblocked   │ ← Dependency resolution
      └───────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│                          WAVE 3                                 │
│                       (UI Layer)                                │
└─────────────────────────────────────────────────────────────────┘

Orchestrator → Feature Manager START
                → get_next_task(limit=5)
                → Returns: [T5, T6] (newly unblocked)

    ┌─────────┐              ┌─────────┐
    │   T5    │              │   T6    │
    │  User   │  Parallel    │ Profile │
    │   UI    │  Execution   │   UI    │
    │         │              │         │
    │ Reads:  │              │ Reads:  │
    │ T3 sum  │              │ T4 sum  │ ← Dependency context
    └────┬────┘              └────┬────┘
         │                        │
         │ Complete               │ Complete
         └────────┬───────────────┘
                  │
                  ▼
      ┌───────────────────────┐
      │  All Tasks Complete   │
      │  Feature Complete!    │
      └───────────────────────┘

Results:
- 6 tasks in 3 waves (parallelism within each wave)
- Time: 3 × specialist time (vs 6 × sequential)
- 50% time savings
- Each task receives only dependency summaries (not full context)
```

#### Workflow

**WAVE 1: Database Schemas**

```
get_next_task(featureId='user-mgmt') → Returns T1, T2

Analysis:
- Both unblocked
- Different tables (User vs Profile)
- Can parallelize

Launch T1 and T2:
  T1: "Created Users table with id, username, email, password_hash"
  T2: "Created Profiles table with user_id FK, bio, avatar"

Both complete. Move to Wave 2.
```

**WAVE 2: API Endpoints**

```
get_next_task(featureId='user-mgmt') → Returns T3, T4 (now unblocked)

Analysis:
- Both unblocked (T1 and T2 completed)
- Different controllers (UserController vs ProfileController)
- Can parallelize

Launch T3 and T4:
  Task Manager START (T3) reads T1 summary:
    "Dependencies: Created Users table with id, username..."
  T3: "Implemented User CRUD using Users table"

  Task Manager START (T4) reads T2 summary:
    "Dependencies: Created Profiles table with user_id..."
  T4: "Implemented Profile CRUD using Profiles table"

Both complete. Move to Wave 3.
```

**WAVE 3: UI Components**

```
get_next_task(featureId='user-mgmt') → Returns T5, T6 (now unblocked)

Analysis:
- Both unblocked (T3 and T4 completed)
- Different components (UserProfile vs ProfileEditor)
- Can parallelize

Launch T5 and T6:
  Task Manager START (T5) reads T3 summary:
    "Dependencies: Implemented User CRUD endpoints..."
  T5: "Built UserProfile component with API integration"

  Task Manager START (T6) reads T4 summary:
    "Dependencies: Implemented Profile CRUD endpoints..."
  T6: "Built ProfileEditor component with API integration"

All complete. Feature done!
```

**Results**:
- 3 waves instead of 6 sequential tasks
- Time: 3 × specialist time (vs 6 × specialist time)
- Time savings: 50%
- Context: 6 × 200 = 1,200 tokens

### Feature-Level: Multiple Features

**Scenario**: E-commerce platform with 3 independent features

#### Context

**Project**: E-commerce Platform
**Features**:
1. **Feature 1: User Authentication** (4 tasks)
   - T1: Auth schema
   - T2: Auth service
   - T3: Login UI
   - T4: Auth tests

2. **Feature 2: Product Catalog** (4 tasks)
   - T5: Product schema
   - T6: Product service
   - T7: Catalog UI
   - T8: Product tests

3. **Feature 3: Shopping Cart** (4 tasks)
   - T9: Cart schema
   - T10: Cart service
   - T11: Cart UI
   - T12: Cart tests

**Analysis**:
- Features are independent
- Each feature has internal dependencies
- **Decision: Feature-level parallelism**

#### Workflow

**Parallel Feature Development**:

```
FEATURE 1 THREAD
────────────────
Orchestrator works through F1 tasks:
  T1 → T2 → T3 → T4 (sequential within feature)
  Each task brief added to context
  Feature Manager END creates F1 summary

FEATURE 2 THREAD (simultaneously)
────────────────
Orchestrator works through F2 tasks:
  T5 → T6 → T7 → T8 (sequential within feature)
  Each task brief added to context
  Feature Manager END creates F2 summary

FEATURE 3 THREAD (simultaneously)
────────────────
Orchestrator works through F3 tasks:
  T9 → T10 → T11 → T12 (sequential within feature)
  Each task brief added to context
  Feature Manager END creates F3 summary
```

**Results**:
- 12 tasks total across 3 features
- Time: 4 × specialist time (vs 12 × specialist time)
- Time savings: 67%
- Context: 12 × 200 = 2,400 tokens
- Scalable to any number of features!

---

## Launching Parallel Tasks

### Finding Unblocked Tasks

**Primary Method: get_next_task**

This tool automatically filters out blocked tasks, returning only safe-to-start work.

```
get_next_task(
  featureId='your-feature-id',
  limit=10,
  includeDetails=true
)
```

**What it does**:
1. Reads all tasks in the feature
2. Checks each task's dependencies
3. Filters out tasks with incomplete dependencies
4. Returns only unblocked tasks
5. Sorts by priority and complexity

**Example Response**:
```json
{
  "tasks": [
    {
      "id": "uuid-1",
      "title": "Create database schema",
      "status": "pending",
      "priority": "high",
      "blockedBy": [],
      "tags": ["database", "schema"]
    },
    {
      "id": "uuid-2",
      "title": "Setup authentication",
      "status": "pending",
      "priority": "high",
      "blockedBy": [],
      "tags": ["backend", "auth"]
    }
  ]
}
```

**Interpretation**:
- 2 unblocked tasks available
- Both high priority
- Different work types (database vs backend)
- Can launch in parallel

### Verifying Independence

**Before launching parallel tasks, verify they won't conflict**:

#### Checklist

**1. Dependency Check** ✅
```
All tasks show "blockedBy": [] in get_next_task response
```

**2. File Conflict Check** ✅
```
Ask: Will these tasks modify the same files?

Good: T1 creates auth/, T2 creates users/ (different directories)
Bad: T1 modifies AuthService.kt, T2 also modifies AuthService.kt
```

**3. Specialist Check** ✅
```
Ask: Are different specialists working, or same specialist on different subjects?

Good: T1 (Database), T2 (Backend), T3 (Frontend) - different specialists
Good: T1 (docs/auth.md), T2 (docs/users.md) - same specialist, different files
Bad: T1 (create User model), T2 (create Admin model) - same files likely
```

**4. Logical Dependency Check** ✅
```
Ask: Does the design/approach of one task affect the other?

Good: T1 (document module A), T2 (document module B) - independent
Bad: T1 (design API), T2 (implement API) - design should come first
```

#### Quick Independence Test

**Ask these 3 questions**:
1. Can T1 complete without knowing T2's results? ✅
2. Can T2 complete without knowing T1's results? ✅
3. Would merging their changes cause conflicts? ❌

If all yes/no (respectively), tasks are independent!

### Executing the Batch

**Step 1: Launch Each Task**

```
FOR each task in parallel batch:
  1. Task Manager START (task-id)
     - Reads task details
     - Reads dependency summaries (if any)
     - Recommends specialist
     - Provides brief with full context

  2. Specialist executes
     - Receives Task Manager brief
     - Has all necessary context
     - Completes work independently

  3. Task Manager END (task-id)
     - Updates task status
     - Creates summary section
     - Returns brief (2-3 sentences)
```

**Step 2: Collect Briefs**

```
Task 1 brief: "Completed X. Created Y files. Ready for Z."
Task 2 brief: "Implemented A. Updated B. Tested C."
Task 3 brief: "Documented D. Added E. Published F."

Total context added: 3 × 200 = 600 tokens
```

**Step 3: Continue**

```
get_next_task() → Check for next wave
If more unblocked tasks, repeat
If none, feature may be complete
```

---

## Best Practices

### Batch Size Limits

**Recommended: 3-5 tasks per batch**

#### Why Limit Batch Size?

**Too many tasks at once (8+)**:
- ❌ Hard to track which completed
- ❌ Context grows rapidly (8 × 200 = 1,600 tokens)
- ❌ Difficult to handle partial failures
- ❌ Overwhelming coordination

**Optimal batch size (3-5)**:
- ✅ Easy to track progress
- ✅ Manageable context (600-1,000 tokens)
- ✅ Clear success/failure visibility
- ✅ Natural checkpoint boundaries

#### Batch Size Guidelines

| Scenario | Recommended Batch Size | Reason |
|----------|----------------------|--------|
| First time using parallelism | 2-3 | Learn the pattern |
| Independent documentation tasks | 4-5 | Same specialist, low risk |
| Different specialists | 3-4 | Coordination overhead |
| Critical/complex tasks | 2-3 | More careful monitoring |
| Simple/routine tasks | 4-5 | Lower stakes |
| Approaching context limits | 2-3 | Preserve context budget |

### Context Management

**Your context budget is limited - use it wisely**

#### Context Accumulation

```
Every task workflow adds ~200 tokens to your context (brief only)

After 10 parallel tasks: 2,000 tokens
After 50 tasks: 10,000 tokens
After 100 tasks: 20,000 tokens (still manageable!)
```

#### Context Cleanup Strategies

**Strategy 1: Wave Boundaries**
```
Complete Wave 1 (3 tasks) → 600 tokens
Complete Wave 2 (4 tasks) → 1,400 tokens total
Complete Wave 3 (3 tasks) → 2,000 tokens total
Feature complete → Summarize feature → Reset to ~500 tokens
```

**Strategy 2: Feature Completion**
```
Complete Feature 1 → Feature Manager END → Feature summary created
Start Feature 2 → Fresh context, only F1 summary referenced
```

**Strategy 3: Session Restart**
```
If context gets large (>50k tokens):
1. Complete current feature
2. Save state
3. Start new Claude Code session
4. Load context with get_overview()
5. Continue work with clean slate
```

### Progress Tracking

**Keep track of parallel work to avoid losing context**

#### Simple Tracking Pattern

```
LAUNCHED:
⏳ T1: Database schema
⏳ T2: Auth service
⏳ T3: Login UI

COMPLETED:
✅ T1: Database schema - "Created Users table..."
⏳ T2: Auth service
⏳ T3: Login UI

COMPLETED:
✅ T1: Database schema - "Created Users table..."
✅ T2: Auth service - "Implemented OAuth 2.0..."
✅ T3: Login UI - "Built LoginForm component..."

ALL COMPLETE → Next wave
```

#### Periodic Status Checks

```
Every N tasks (e.g., every 5):
  Feature Manager START → Get progress report

Response:
  "Feature: User Management
   Progress: 12/18 tasks completed
   In Progress: 0 tasks
   Blocked: 4 tasks (waiting on T7)
   Available: 2 tasks"

Action: Launch next 2 available tasks
```

### Handling Mixed Specialists

**Same specialist, multiple tasks - can you parallelize?**

#### Safe: Independent Subjects

```
Specialist: Technical Writer
T1: Document auth API (auth-api.md)
T2: Document users API (users-api.md)
T3: Document products API (products-api.md)

Safe to parallelize:
- Different files ✅
- Different subjects ✅
- No shared content ✅
```

#### Risky: Related Code Areas

```
Specialist: Backend Engineer
T1: Add User authentication
T2: Add Admin authentication
T3: Add Guest authentication

Risky to parallelize:
- Same files (AuthService.kt) ❌
- Related logic ❌
- Merge conflicts likely ❌

Recommendation: Execute sequentially
```

#### Strategy: Spatial Separation

```
IF same specialist:
  Check: Do tasks modify different directories/modules?

  Different directories → Can parallelize
  Same directory → Sequential safer
  Unsure → Ask or execute sequentially
```

---

## Common Workflows

### Documentation Sprint

**Goal**: Document multiple API modules quickly

**Pattern**: Full parallel batch

```
Setup:
  Feature: API Documentation
  Tasks: 6 documentation tasks (all independent)

Workflow:
1. get_next_task() → Returns all 6 tasks

2. Verify independence:
   - All different files ✅
   - All different subjects ✅
   - Same specialist (Technical Writer) but independent work ✅

3. Launch 2 batches (3 tasks each):

   Batch 1: T1, T2, T3
   Wait for completion

   Batch 2: T4, T5, T6
   Wait for completion

4. Results:
   - 6 tasks in 2 × specialist time
   - vs 6 × specialist time sequential
   - 67% time savings
```

### Multi-Layer Architecture

**Goal**: Build database → API → UI flow efficiently

**Pattern**: Wave-based progression

```
Setup:
  Feature: User Management
  Layers: Database → Backend → Frontend

Workflow:
Wave 1: Database (parallel tables)
  T1: Users table
  T2: Profiles table
  T3: Settings table
  All complete → Create summaries

Wave 2: API (parallel endpoints)
  T4: Users API (reads T1 summary)
  T5: Profiles API (reads T2 summary)
  T6: Settings API (reads T3 summary)
  All complete → Create summaries

Wave 3: UI (parallel components)
  T7: User UI (reads T4 summary)
  T8: Profile UI (reads T5 summary)
  T9: Settings UI (reads T6 summary)
  All complete → Feature done

Results:
  - 9 tasks in 3 waves
  - Time: 3 × specialist time
  - vs 9 × specialist time sequential
  - 67% time savings
```

### Cross-Feature Development

**Goal**: Develop multiple features simultaneously

**Pattern**: Feature-level parallelism

```
Setup:
  Project: E-commerce Platform
  Features: 3 independent features

Workflow:
Thread 1: Authentication Feature (4 tasks)
  T1 → T2 → T3 → T4

Thread 2: Product Catalog Feature (5 tasks)
  T5 → T6 → T7 → T8 → T9

Thread 3: Shopping Cart Feature (3 tasks)
  T10 → T11 → T12

Coordination:
  - Orchestrator manages all 3 threads
  - Features progress independently
  - Each Feature Manager tracks its feature
  - Task Manager routes tasks to specialists

Results:
  - 12 tasks across 3 features
  - Time: 5 × specialist time (longest feature)
  - vs 12 × specialist time sequential
  - 58% time savings
  - Scalable to any number of features!
```

### Test Suite Creation

**Goal**: Write tests for multiple modules in parallel

**Pattern**: Module-based parallelism

```
Setup:
  Feature: Testing Suite
  Modules: Auth, Users, Products, Orders

Workflow:
1. get_next_task() → Returns 4 test tasks

2. Verify independence:
   - Different test files ✅
   - Different modules being tested ✅
   - Same specialist (Test Engineer) ✅

3. Launch all 4:
   T1: Auth tests (test/auth/)
   T2: User tests (test/users/)
   T3: Product tests (test/products/)
   T4: Order tests (test/orders/)

4. Each receives module implementation summary
   Each creates comprehensive test suite

Results:
  - 4 test suites in 1 × specialist time
  - vs 4 × specialist time sequential
  - 75% time savings
```

---

## Troubleshooting

### Tasks Not Appearing as Unblocked

**Symptom**: `get_next_task()` returns empty or fewer tasks than expected

**Possible Causes**:

1. **Tasks have incomplete dependencies**
   ```
   Solution:
   - Check task dependencies: get_task_dependencies(taskId, direction='incoming')
   - Verify all dependency tasks are marked 'completed'
   - Complete blocking tasks first
   ```

2. **Tasks are already in-progress or completed**
   ```
   Solution:
   - Check task status: get_task(id='task-id')
   - Filter for 'pending' tasks only
   - Review feature task list: get_feature(id='feature-id', includeTasks=true)
   ```

3. **Wrong feature ID**
   ```
   Solution:
   - Verify feature ID: get_feature(id='feature-id')
   - Check feature task count: get_feature(includeTaskCounts=true)
   ```

4. **Dependencies not properly created**
   ```
   Solution:
   - Review feature structure
   - Add missing dependencies: create_dependency(fromTaskId, toTaskId)
   ```

### Merge Conflicts in Parallel Work

**Symptom**: Git merge conflicts when combining parallel work

**Causes & Solutions**:

**Cause 1: Same File Modifications**
```
Problem: T1 and T2 both modified AuthService.kt

Solution:
- Before parallelizing, check file overlap
- If same files, execute sequentially
- Use git branches per task to isolate changes
```

**Cause 2: Related Code Areas**
```
Problem: T1 added User auth, T2 added Admin auth (same file)

Solution:
- Recognize related work requires coordination
- Execute sequentially to build on each other
- Or carefully separate into different files/functions
```

**Cause 3: Shared Configuration**
```
Problem: Both tasks modified application.conf

Solution:
- Identify shared config early
- Have one task handle all config changes
- Or merge config changes manually with care
```

### Losing Track of Progress

**Symptom**: Don't remember which parallel tasks completed

**Prevention**:

1. **Use structured tracking**
   ```
   WAVE 1 (Launched):
   ⏳ T1: Database schema
   ⏳ T2: Auth service
   ⏳ T3: Login UI

   (Update as tasks complete)

   ✅ T1: "Created Users table..."
   ✅ T2: "Implemented OAuth..."
   ⏳ T3: Still working...
   ```

2. **Limit batch size**
   ```
   Don't launch 10 tasks at once
   Launch 3-4, wait for completion, then next batch
   ```

3. **Feature Manager status checks**
   ```
   Feature Manager START → Get progress report
   Shows: completed, in-progress, blocked, available
   ```

**Recovery**:

```
If you've lost track:
1. get_feature(id='feature-id', includeTasks=true, includeTaskCounts=true)
2. Review task statuses
3. Identify what's completed vs pending
4. Continue from current state
```

### One Task Fails in Parallel Batch

**Symptom**: T2 failed, but T1, T3, T4 succeeded

**Best Practice: Isolate Failure**

```
DO:
✅ Keep T1, T3, T4 as completed (they succeeded)
✅ Mark T2 as failed with error details
✅ Create summaries for T1, T3, T4
✅ Update T2 sections with failure context
✅ Continue with other available tasks
✅ Retry T2 independently later

DON'T:
❌ Rollback all 4 tasks
❌ Mark feature as failed
❌ Stop all work
```

**Handling Dependent Tasks**:

```
If T5 depends on T2 (which failed):
1. T5 will be blocked automatically
2. get_next_task() won't return T5
3. Fix T2 first
4. set_status(id='T2', status='completed')
5. Now T5 becomes unblocked
6. Launch T5 with T2 summary
```

**When to Rollback All**:
- Tasks modify shared state atomically
- Database transactions across tasks
- User explicitly requested all-or-nothing
- Critical production deployment

---

## Advanced Patterns

### Diamond Dependencies

**Pattern**: Multiple tasks converge to single task

**Diagram**:
```
       T1
      ↙  ↘
    T2    T3
      ↘  ↙
       T4
```

**Example**: Order Processing
```
T1: Create Order schema
T2: Payment service (depends on T1)
T3: Inventory service (depends on T1)
T4: Order workflow (depends on T2 and T3)
```

**Execution**:
```
Wave 1: T1 alone
  Launch T1 → Create Order table
  Summary: "Created Orders table..."

Wave 2: T2 and T3 in parallel (both unblocked by T1)
  Task Manager START (T2) reads T1 summary
  Task Manager START (T3) reads T1 summary
  Both launch simultaneously
  T2 summary: "Implemented PaymentService..."
  T3 summary: "Implemented InventoryService..."

Wave 3: T4 alone (unblocked by T2 and T3)
  Task Manager START (T4) reads T2 AND T3 summaries
  T4 receives both dependency contexts (600-1000 tokens)
  Implements OrderWorkflow using both services
  Summary: "Created OrderWorkflow coordinating payment and inventory"

Results:
  - 4 tasks in 3 waves
  - Time: 3 × specialist time
  - vs 4 × specialist time sequential
  - 25% time savings
```

### Conditional Parallelism

**Pattern**: Decide parallel vs sequential based on runtime conditions

**Example**: Testing Strategy
```
Scenario: 6 test tasks available

IF all tests are unit tests (fast, independent):
  → Launch all 6 in parallel

ELSE IF some are integration tests (slower, shared test DB):
  → Wave 1: Unit tests (4 parallel)
  → Wave 2: Integration tests (2 sequential)
```

**Decision Logic**:
```
get_next_task() → Returns 6 tasks

FOR each task:
  Check tags: ['unit-test'] or ['integration-test']?

Group:
  Unit tests: T1, T2, T3, T4
  Integration tests: T5, T6

Execute:
  Batch 1: Launch T1, T2, T3, T4 in parallel
  Wait for completion

  Sequential: T5 → T6
  (Integration tests share test database)
```

### CONTINUE Mode: Incremental Batching

**Pattern**: Add tasks to fill capacity while others are in progress (future enhancement)

**CONTINUE Mode Workflow**:
```
┌─────────────────────────────────────────────────────────────────┐
│         CONTINUE MODE: INCREMENTAL BATCH PROCESSING             │
│                    (Future Enhancement)                         │
└─────────────────────────────────────────────────────────────────┘

Scenario: Feature with 10 tasks, MAX_PARALLEL_TASKS = 5

Initial State: 0 tasks in progress

┌──────────────────────────────┐
│ Orchestrator: START mode     │
│ Launch batch: [T1-T5]        │ ← 5 tasks start
│ Active: 5, Capacity: 0       │
└──────────────────────────────┘

After some time: T2 and T4 complete early

┌──────────────────────────────┐
│ Active: T1, T3, T5 (3 tasks) │
│ Capacity: 5 - 3 = 2 slots    │ ← 2 slots now available
└──────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ Orchestrator: CONTINUE mode                          │
│ Feature Manager calculates:                          │
│   remaining_capacity = MAX_PARALLEL_TASKS - active   │
│                      = 5 - 3 = 2                     │
│   get_next_task(limit=2)                             │
│   Returns: [T6, T7]                                  │
│                                                      │
│ Response modes based on available work:              │
│ 1. "FULL_CAPACITY": Capacity filled (2 tasks)       │
│ 2. "PARTIAL": Some tasks added (1 task)             │
│ 3. "NO_CAPACITY": All 5 slots still full            │
│ 4. "ALL_BLOCKED": Tasks available but all blocked   │
│ 5. "NO_WORK": No more tasks in feature              │
│ 6. "FEATURE_COMPLETE": All tasks done                │
└──────────────────────────────────────────────────────┘

Launch incremental batch: [T6, T7]

┌──────────────────────────────┐
│ Active: T1, T3, T5, T6, T7   │
│ Capacity: 5 - 5 = 0 (full)   │ ← Back to full capacity
└──────────────────────────────┘

T1 completes

┌──────────────────────────────┐
│ Active: T3, T5, T6, T7       │
│ Capacity: 5 - 4 = 1 slot     │
└──────────────────────────────┘

┌──────────────────────────────────────────────────────┐
│ Orchestrator: CONTINUE mode                          │
│   remaining_capacity = 1                             │
│   get_next_task(limit=1)                             │
│   Returns: [T8]                                      │
│   Launch T8                                          │
└──────────────────────────────────────────────────────┘

Benefit: Zero idle time, maximum throughput
- Continuously fill capacity as tasks complete
- No waiting for full wave completion
- Smooth rolling execution
```

**CONTINUE Mode Response Formats**:

```
Response Type 1: FULL_CAPACITY (ideal scenario)
────────────────────────────────────────────
"CONTINUE - Capacity filled (2 tasks added)

Newly Recommended Tasks:
1. T6 - Implement search API
2. T7 - Build pagination component

Active Tasks: T1, T3, T5, T6, T7 (5/5 capacity)
Available Tasks: 3 remaining
Blocked Tasks: 0

Next: Launch Task Manager START for T6 and T7"

Response Type 2: NO_CAPACITY
─────────────────────────────
"CONTINUE - At capacity (0 tasks added)

Active Tasks: T1, T2, T3, T4, T5 (5/5 capacity)
Available Tasks: 2 waiting
Blocked Tasks: 3

Next: Wait for any active task to complete, then call CONTINUE again"

Response Type 3: ALL_BLOCKED
────────────────────────────
"CONTINUE - No unblocked work (0 tasks added)

Active Tasks: T1, T2 (2/5 capacity)
Available Tasks: 0
Blocked Tasks: 8 (all waiting on T1 or T2)

Next: Wait for T1 or T2 to complete to unblock downstream tasks"

Response Type 4: FEATURE_COMPLETE
──────────────────────────────────
"CONTINUE - Feature complete

Active Tasks: 0
Completed Tasks: 10/10
Progress: 100%

Next: Call Feature Manager END to finalize feature"
```

### Rolling Waves

**Pattern**: Launch next wave as soon as any task unblocks

**Example**: Continuous flow
```
Initial state:
  T1 (unblocked)
  T2 (blocked by T1)
  T3 (blocked by T1)
  T4 (blocked by T2)

Execution:
  Launch T1 → Completes

  get_next_task() → Returns T2, T3 (now unblocked)
  Launch T2 and T3 in parallel → T3 completes

  get_next_task() → Returns nothing yet (T2 still working)

  T2 completes
  get_next_task() → Returns T4 (now unblocked)
  Launch T4 → Completes

Benefit:
  - No idle time
  - Always working on something
  - Maximize throughput
```

---

## Decision Flowchart

**Use this flowchart to decide parallel vs sequential execution**

```
START: New work available

↓

STEP 1: Get available tasks
→ get_next_task(featureId='...', limit=10)

↓

STEP 2: How many tasks returned?
→ 0 tasks: No work available → END
→ 1 task: Launch single task → END
→ 2+ tasks: Continue to STEP 3

↓

STEP 3: Check for dependencies between tasks
→ Do tasks depend on each other?
  YES: Group into waves → Use wave-based pattern
  NO: Continue to STEP 4

↓

STEP 4: Check for file conflicts
→ Will tasks modify same files?
  YES: Execute sequentially → END
  NO: Continue to STEP 5

↓

STEP 5: Check specialist type
→ Same specialist?
  YES: Different subjects/files?
    YES: Continue to STEP 6
    NO: Execute sequentially → END
  NO: Continue to STEP 6

↓

STEP 6: Determine batch size
→ How many tasks?
  2-3 tasks: Launch all in parallel
  4-5 tasks: Launch all in parallel
  6-10 tasks: Launch in batches of 3-4
  10+ tasks: Launch in batches of 3-4 (multiple rounds)

↓

STEP 7: Execute parallel batch
→ Launch all tasks in batch
→ Wait for completion
→ Collect briefs

↓

STEP 8: Check for more work
→ get_next_task() again
→ If more tasks: Return to STEP 2
→ If no tasks: END

END: All work complete or no more unblocked tasks
```

**Quick Reference**:
- **Independent tasks**: Parallel
- **Dependent tasks**: Sequential or wave-based
- **Same files**: Sequential
- **Different files/specialists**: Parallel
- **2-5 tasks**: One batch
- **6+ tasks**: Multiple batches of 3-4

---

## Summary

Parallel processing in Task Orchestrator enables efficient, scalable project execution by:

1. **Identifying unblocked tasks** with `get_next_task`
2. **Verifying independence** (no conflicts, dependencies, or file overlap)
3. **Launching in batches** of 3-5 tasks at a time
4. **Collecting brief summaries** (200 tokens each)
5. **Progressing in waves** when dependencies exist

**Key Benefits**:
- 50-75% time savings on independent tasks
- Clean context (only brief summaries)
- Scalable to projects of any size
- Natural team collaboration model

**Remember**:
- Limit batches to 3-5 tasks for manageability
- Trust `get_next_task` to filter blocked tasks
- Verify independence before parallelizing
- Keep only brief summaries in your context
- Use wave-based progression for mixed dependencies

**Next Steps**:
- Try your first parallel batch (2-3 simple tasks)
- Review [Orchestrator Guidelines](orchestrator-guidelines.md) for technical details
- Explore [Agent Orchestration](agent-orchestration.md) for sub-agent architecture
- Check [Workflow Prompts](workflow-prompts.md) for additional patterns

---

**Questions or issues?** See the [Troubleshooting](#troubleshooting) section or consult the [complete documentation](index.md).
