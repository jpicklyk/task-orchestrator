# Token Reduction: Before/After Examples

**Quantitative analysis of token savings through sub-agent orchestration**

This document provides concrete, measurable examples demonstrating how Task Orchestrator's 3-level sub-agent architecture achieves **85-90% real-world token reduction** compared to traditional single-agent workflows.

**Key metrics explained**:
- **Per-dependency reduction**: 92-95% (summaries vs full context)
- **Accumulated context reduction**: 97-99% (theoretical, if coordination was free)
- **Real-world net savings**: 85-90% (accounting for ~2-3k coordination overhead per task)

The examples below show context accumulation patterns. For real-world coordination overhead analysis, see the "Coordination Overhead" section at the end.

---

## Table of Contents

- [The Problem: Context Accumulation](#the-problem-context-accumulation)
- [The Solution: Summary-Based Context Passing](#the-solution-summary-based-context-passing)
- [Scenario 1: Simple Feature (4 Tasks)](#scenario-1-simple-feature-4-tasks)
- [Scenario 2: Complex Feature (10 Tasks)](#scenario-2-complex-feature-10-tasks)
- [Scenario 3: Cross-Domain Coordination](#scenario-3-cross-domain-coordination)
- [Visual Comparison: Token Growth Curves](#visual-comparison-token-growth-curves)
- [The Breaking Point: When Traditional Fails](#the-breaking-point-when-traditional-fails)
- [How It Works: The Mechanism](#how-it-works-the-mechanism)

---

## The Problem: Context Accumulation

**Traditional single-agent workflows** accumulate context linearly as work progresses:

```
┌─────────────────────────────────────────────────────────────┐
│             TRADITIONAL SINGLE-AGENT WORKFLOW                │
└─────────────────────────────────────────────────────────────┘

Task 1: Implement database schema
  Agent reads requirements (2k tokens)
  Agent implements schema (3k tokens)
  Context accumulation: 5k tokens
  ────────────────────────────────────────
  Running total: 5k tokens

Task 2: Create API endpoints (depends on Task 1)
  Agent reads requirements (2k tokens)
  Agent reads FULL Task 1 context (5k tokens) ← Problem!
  Agent implements endpoints (4k tokens)
  Context accumulation: 11k tokens
  ────────────────────────────────────────
  Running total: 16k tokens (5k + 11k)

Task 3: Build UI components (depends on Task 2)
  Agent reads requirements (2k tokens)
  Agent reads FULL Task 1 context (5k tokens) ← Still carrying
  Agent reads FULL Task 2 context (11k tokens) ← Getting worse
  Agent implements UI (3k tokens)
  Context accumulation: 21k tokens
  ────────────────────────────────────────
  Running total: 37k tokens (5k + 11k + 21k)

Task 4: Write integration tests (depends on all)
  Agent reads requirements (2k tokens)
  Agent reads FULL Task 1 context (5k tokens)
  Agent reads FULL Task 2 context (11k tokens)
  Agent reads FULL Task 3 context (21k tokens)
  Agent writes tests (3k tokens)
  Context accumulation: 42k tokens
  ────────────────────────────────────────
  Running total: 79k tokens (5k + 11k + 21k + 42k)
```

**Problem**: Each task carries the accumulated context of ALL previous tasks. By Task 4, the agent is processing 79k tokens total - most of which is redundant information from earlier work.

---

## The Solution: Summary-Based Context Passing

**Sub-agent orchestration** breaks the accumulation pattern by passing summaries instead of full contexts:

```
┌─────────────────────────────────────────────────────────────┐
│              SUB-AGENT ORCHESTRATION WORKFLOW                │
└─────────────────────────────────────────────────────────────┘

Task 1: Implement database schema
  Sub-agent reads task (2k tokens)
  Sub-agent implements schema (3k tokens)
  Sub-agent creates Summary (400 tokens) ← Key innovation
  Returns brief to orchestrator (200 tokens)
  ────────────────────────────────────────
  Orchestrator context growth: 200 tokens
  Sub-agent context: DISCARDED

Task 2: Create API endpoints (depends on Task 1)
  Sub-agent reads task (2k tokens)
  Sub-agent reads Task 1 SUMMARY (400 tokens) ← Not 5k!
  Sub-agent implements endpoints (4k tokens)
  Sub-agent creates Summary (400 tokens)
  Returns brief to orchestrator (200 tokens)
  ────────────────────────────────────────
  Orchestrator context growth: 400 tokens total (200 + 200)
  Sub-agent context: DISCARDED

Task 3: Build UI components (depends on Task 2)
  Sub-agent reads task (2k tokens)
  Sub-agent reads Task 2 SUMMARY (400 tokens) ← Not 11k!
  Sub-agent implements UI (3k tokens)
  Sub-agent creates Summary (400 tokens)
  Returns brief to orchestrator (200 tokens)
  ────────────────────────────────────────
  Orchestrator context growth: 600 tokens total (200 + 200 + 200)
  Sub-agent context: DISCARDED

Task 4: Write integration tests (depends on all)
  Sub-agent reads task (2k tokens)
  Sub-agent reads Task 3 SUMMARY (400 tokens) ← Not 37k!
  Sub-agent writes tests (3k tokens)
  Sub-agent creates Summary (400 tokens)
  Returns brief to orchestrator (200 tokens)
  ────────────────────────────────────────
  Orchestrator context growth: 800 tokens total (200 × 4)
  Sub-agent context: DISCARDED
```

**Breakthrough**: Orchestrator accumulates only 800 tokens instead of 79k tokens - **99% reduction**.

---

## Scenario 1: Simple Feature (4 Tasks)

**Feature**: User Authentication System
**Tasks**:
1. Create database schema (Database Engineer)
2. Implement login API (Backend Engineer)
3. Add password reset (Backend Engineer)
4. Write API documentation (Technical Writer)

**Dependencies**: Sequential (each task depends on previous)

### Traditional Approach: Token Accumulation

| Task | Task Content | Accumulated Context | Total in Agent | Cumulative Total |
|------|--------------|---------------------|----------------|------------------|
| T1   | 5k           | 5k (T1)             | 5k             | **5k**           |
| T2   | 6k           | 11k (T1 + T2)       | 11k            | **16k**          |
| T3   | 5k           | 16k (T1 + T2 + T3)  | 16k            | **32k**          |
| T4   | 4k           | 20k (all tasks)     | 20k            | **52k**          |

**Total tokens processed**: 52k tokens
**Agent context at end**: 20k tokens

### Sub-Agent Approach: Summary-Based

| Task | Task Content | Summary Created | Orchestrator Growth | Orchestrator Total |
|------|--------------|-----------------|---------------------|-------------------|
| T1   | 5k           | 400             | 200 (brief)         | **200**           |
| T2   | 6k + 400 (T1 summary) | 400   | 200 (brief)         | **400**           |
| T3   | 5k + 400 (T2 summary) | 400   | 200 (brief)         | **600**           |
| T4   | 4k + 400 (T3 summary) | 400   | 200 (brief)         | **800**           |

**Total tokens in orchestrator context**: 800 tokens
**Reduction**: 52k → 800 tokens = **98.5% savings**

### Detailed Breakdown: Task 2 (Login API)

**Traditional approach** (Task 2 with T1 dependency):
```
Task 2 content: 2k tokens (requirements, specifications)
Full Task 1 context: 5k tokens
  - Requirements (1k)
  - Database schema SQL (1.5k)
  - Migration script (1k)
  - Implementation notes (0.5k)
  - Testing details (1k)

Total context for Task 2: 7k tokens
What agent actually needs from T1: ~500 tokens (table names, key fields)
Wasted context: 4.5k tokens (87% of T1 context unnecessary)
```

**Sub-agent approach** (Task 2 with T1 summary):
```
Task 2 content: 2k tokens
Task 1 Summary: 400 tokens
  ### Completed
  Created Users table with id (UUID), username, email, password_hash,
  created_at, updated_at. Added indexes for email lookup.

  ### Files Changed
  - db/migration/V5__create_users.sql - Users table schema
  - src/model/User.kt - User domain model

  ### Next Steps
  API can use this schema for authentication endpoints

  ### Notes
  Password stored as bcrypt hash only, never plaintext

Total context for Task 2: 2.4k tokens
Agent has everything needed: schema details, file locations, design decisions
Reduction: 7k → 2.4k = 66% savings PER TASK
```

---

## Scenario 2: Complex Feature (10 Tasks)

**Feature**: E-Commerce Product Catalog
**Tasks**:
1. Design data model (Planning)
2. Create product tables (Database)
3. Create category tables (Database)
4. Implement product API (Backend)
5. Implement category API (Backend)
6. Implement search API (Backend)
7. Build product UI components (Frontend)
8. Build category UI components (Frontend)
9. Add unit tests (Test Engineer)
10. Add integration tests (Test Engineer)

**Dependencies**: Mixed (some sequential, some parallel opportunities)

### Traditional Approach: Exponential Growth

| Task | New Content | Accumulated | Agent Processes | Running Total |
|------|-------------|-------------|-----------------|---------------|
| T1   | 4k          | 4k          | 4k              | **4k**        |
| T2   | 5k          | 9k          | 9k              | **13k**       |
| T3   | 5k          | 14k         | 14k             | **27k**       |
| T4   | 7k          | 21k         | 21k             | **48k**       |
| T5   | 6k          | 27k         | 27k             | **75k**       |
| T6   | 8k          | 35k         | 35k             | **110k**      |
| T7   | 6k          | 41k         | 41k             | **151k**      |
| T8   | 5k          | 46k         | 46k             | **197k**      |
| T9   | 7k          | 53k         | 53k             | **250k**      |
| T10  | 8k          | 61k         | 61k             | **311k**      |

**Total tokens processed**: 311k tokens
**Agent context at end**: 61k tokens
**Status**: ⚠️ Approaching context limits (200k typical maximum)

### Sub-Agent Approach: Constant Growth

| Task | Content + Dependencies | Summary | Orchestrator | Running Total |
|------|------------------------|---------|--------------|---------------|
| T1   | 4k                     | 400     | 200          | **200**       |
| T2   | 5k + 400 (T1)          | 400     | 200          | **400**       |
| T3   | 5k + 400 (T2)          | 400     | 200          | **600**       |
| T4   | 7k + 400 (T2) + 400 (T3) | 400   | 200          | **800**       |
| T5   | 6k + 400 (T2) + 400 (T3) | 400   | 200          | **1,000**     |
| T6   | 8k + 800 (T4+T5)       | 400     | 200          | **1,200**     |
| T7   | 6k + 400 (T4)          | 400     | 200          | **1,400**     |
| T8   | 5k + 400 (T5)          | 400     | 200          | **1,600**     |
| T9   | 7k + 1200 (T4+T5+T6)   | 400     | 200          | **1,800**     |
| T10  | 8k + 1200 (T4+T5+T6)   | 400     | 200          | **2,000**     |

**Total tokens in orchestrator context**: 2k tokens
**Reduction**: 311k → 2k tokens = **99.4% savings**
**Status**: ✅ Well within limits, can scale indefinitely

### Key Insight: Dependency Context

**Task 9 (Unit Tests)** depends on Tasks 4, 5, and 6 (API implementations).

**Traditional approach**: Must read full context of T4 (7k) + T5 (6k) + T6 (8k) = **21k tokens** of dependency context

**Sub-agent approach**: Reads summaries of T4 (400) + T5 (400) + T6 (400) = **1,200 tokens** of dependency context

**Savings**: 21k → 1,200 = **94% reduction in dependency context**

---

## Scenario 3: Cross-Domain Coordination

**Feature**: Payment Processing Integration
**Workflow**: Database → Backend → Frontend → Testing (classic full-stack flow)

**Tasks**:
1. Create payment transactions table (Database Engineer)
2. Create payment logs table (Database Engineer)
3. Implement payment service (Backend Engineer)
4. Implement webhook handlers (Backend Engineer)
5. Build checkout UI (Frontend Developer)
6. Build payment history UI (Frontend Developer)
7. Write unit tests for backend (Test Engineer)
8. Write integration tests (Test Engineer)
9. Document payment API (Technical Writer)

**Characteristic**: Each domain needs context from previous domain but not full details.

### Traditional Approach: Full Cross-Domain Context

```
Task 1 (Database): Create transactions table
  Context: 4k tokens
  Output: SQL schema, migration, model classes

Task 2 (Database): Create logs table
  Context: 4k + 4k (T1 full context) = 8k tokens
  Agent needs: T1 schema to reference foreign keys
  Agent gets: T1 schema + testing + implementation notes (90% unnecessary)

Task 3 (Backend): Payment service
  Context: 6k + 8k (T1+T2 full context) = 14k tokens
  Agent needs: Table schemas, field names (600 tokens)
  Agent gets: All SQL, migrations, testing details (7.4k tokens unnecessary)

Task 4 (Backend): Webhook handlers
  Context: 7k + 14k (T1+T2+T3 full context) = 21k tokens
  Agent needs: Service interface from T3 (500 tokens)
  Agent gets: All previous implementation (20.5k tokens unnecessary)

Task 5 (Frontend): Checkout UI
  Context: 6k + 21k (all backend) = 27k tokens
  Agent needs: API endpoints, request/response formats (800 tokens)
  Agent gets: Database schemas, service implementations, SQL (26.2k unnecessary)

...continues growing...

Task 9 (Technical Writer): API documentation
  Context: 5k + 46k (all previous) = 51k tokens
  Agent needs: API specs, endpoints, parameters (2k tokens)
  Agent gets: Database migrations, UI components, test code (49k unnecessary)

Total orchestrator context: 51k tokens
```

### Sub-Agent Approach: Minimal Cross-Domain Context

```
Task 1 (Database): Create transactions table
  Sub-agent: 4k tokens
  Summary: 400 tokens
    - Table: payment_transactions
    - Fields: id, user_id, amount, status, created_at
    - Indexes: user_id, status, created_at
  Orchestrator: +200 tokens

Task 2 (Database): Create logs table
  Sub-agent: 4k + 400 (T1 summary) = 4.4k tokens
  Reads T1 summary: Gets transaction table name for foreign key
  Summary: 400 tokens
  Orchestrator: +200 tokens (400 total)

Task 3 (Backend): Payment service
  Sub-agent: 6k + 400 (T2 summary) = 6.4k tokens
  Reads T2 summary: Gets schema details without SQL, migrations, etc.
  Summary: 400 tokens
    - Service: PaymentService
    - Key methods: createPayment(), refundPayment()
    - Returns: PaymentResult sealed class
  Orchestrator: +200 tokens (600 total)

Task 4 (Backend): Webhook handlers
  Sub-agent: 7k + 400 (T3 summary) = 7.4k tokens
  Reads T3 summary: Gets service interface, no implementation details
  Summary: 400 tokens
  Orchestrator: +200 tokens (800 total)

Task 5 (Frontend): Checkout UI
  Sub-agent: 6k + 400 (T4 summary) = 6.4k tokens
  Reads T4 summary: Gets webhook endpoints, no backend implementation
  Summary: 400 tokens
    - Component: CheckoutForm
    - API calls: POST /api/payment/create
    - State management: Redux payment slice
  Orchestrator: +200 tokens (1,000 total)

...continues at constant rate...

Task 9 (Technical Writer): API documentation
  Sub-agent: 5k + 1,600 (T3+T4 summaries) = 6.6k tokens
  Reads backend summaries: Gets API specs without database or UI details
  Summary: 400 tokens
  Orchestrator: +200 tokens (1,800 total)

Total orchestrator context: 1,800 tokens
```

**Comparison**:
- Traditional: 51k tokens (writer reading database migrations, UI code)
- Sub-agent: 1,800 tokens (writer reading only relevant API summaries)
- **Reduction: 96.5%**

### Cross-Domain Efficiency Matrix

| Domain Transition | Traditional Context | Sub-Agent Context | Savings |
|-------------------|---------------------|-------------------|---------|
| Database → Backend | 8k (full schemas + SQL) | 400 (schema summary) | **95%** |
| Backend → Frontend | 21k (DB + services) | 400 (API summary) | **98%** |
| Frontend → Testing | 33k (DB + BE + FE) | 800 (BE + FE summaries) | **98%** |
| All → Documentation | 46k (everything) | 1,600 (relevant summaries) | **97%** |

**Key Insight**: Specialists read summaries filtered to their domain. Frontend Developer never sees SQL. Technical Writer never sees React components. Each specialist gets exactly what they need, nothing more.

---

## Visual Comparison: Token Growth Curves

### 4-Task Feature (Scenario 1)

```
Token Count
│
60k │                                    ● Traditional (52k)
    │                                 ●
50k │                              ●
    │                           ●
40k │                        ●
    │                     ●
30k │                  ●
    │               ●
20k │            ●
    │         ●
10k │      ●
    │   ●
 0k │●──────────────────────────────── ━ Sub-Agent (800)
    └────────────────────────────────
     T1    T2    T3    T4

Traditional: 5k → 16k → 32k → 52k (exponential)
Sub-Agent:  200 → 400 → 600 → 800 (linear)
```

### 10-Task Feature (Scenario 2)

```
Token Count
│
350k│                                                        ● Traditional (311k)
    │                                                    ●
300k│                                                ●
    │                                            ●
250k│                                        ●
    │                                    ●
200k│                                ●  ← Context limit reached
    │                            ●
150k│                        ●
    │                    ●
100k│                ●
    │            ●
 50k│        ●
    │    ●
  0k│●───────────────────────────────────────────────── ━ Sub-Agent (2k)
    └──────────────────────────────────────────────────
     T1  T2  T3  T4  T5  T6  T7  T8  T9  T10

Traditional: Exponential growth, hits limits at T8-T9
Sub-Agent:  Linear growth, 200 tokens per task (constant)
```

### Context Growth Rate Comparison

| Tasks | Traditional | Sub-Agent | Reduction | Status |
|-------|-------------|-----------|-----------|--------|
| 1     | 5k          | 200       | 96%       | ✅ Both OK |
| 5     | 75k         | 1k        | 99%       | ✅ Both OK |
| 10    | 311k        | 2k        | 99.4%     | ⚠️ Traditional at limits |
| 20    | ~1.2M       | 4k        | 99.7%     | ❌ Traditional impossible |
| 50    | ~7.5M       | 10k       | 99.9%     | ❌ Traditional impossible |
| 100   | ~30M        | 20k       | 99.9%+    | ❌ Traditional impossible |

**Conclusion**: Traditional approach becomes impossible after 10-15 tasks. Sub-agent approach scales to hundreds of tasks.

---

## The Breaking Point: When Traditional Fails

### Realistic Project: E-Commerce Platform

**Project Structure**:
- Feature 1: User Management (8 tasks)
- Feature 2: Product Catalog (12 tasks)
- Feature 3: Shopping Cart (10 tasks)
- Feature 4: Payment Processing (9 tasks)
- Feature 5: Order Management (11 tasks)

**Total: 50 tasks**

### Traditional Approach: Impossible

```
After Task 10:
  Agent context: 311k tokens
  Status: At context limit (200k typical, 300k maximum)

After Task 15:
  Agent context: ~700k tokens
  Status: FAILED - Context limit exceeded

Result: Cannot complete project
```

**Why it fails**:
- Each task must carry context from ALL previous tasks
- By task 15, agent is trying to process 700k tokens
- Context limits (200k-300k) make this impossible
- No way to complete Features 3, 4, 5

### Sub-Agent Approach: Scales Effortlessly

```
After Task 10:
  Orchestrator context: 2k tokens
  Status: ✅ 1% of limit used

After Task 25:
  Orchestrator context: 5k tokens
  Status: ✅ 2.5% of limit used

After Task 50:
  Orchestrator context: 10k tokens
  Status: ✅ 5% of limit used

Result: Project completes successfully with room to spare
```

**Why it succeeds**:
- Each task adds only 200 tokens to orchestrator context
- Orchestrator context grows linearly, not exponentially
- 50 tasks = 50 × 200 = 10k tokens (well within limits)
- Can easily handle 100+ tasks if needed

### The Breaking Point Calculation

**Traditional approach context formula**:
```
Context(n) = Σ(task_content[i] + Σ(previous_tasks[j]))
           ≈ n × (avg_task_size + (n/2) × avg_task_size)
           ≈ n² × (avg_task_size / 2)

For avg_task_size = 6k tokens:
- 10 tasks: ~300k tokens (at limit)
- 15 tasks: ~675k tokens (impossible)
- 20 tasks: ~1.2M tokens (impossible)
```

**Sub-agent approach context formula**:
```
Context(n) = n × brief_size
           = n × 200 tokens

For any reasonable n:
- 50 tasks: 10k tokens (5% of limit)
- 100 tasks: 20k tokens (10% of limit)
- 500 tasks: 100k tokens (50% of limit)
```

**Breaking point**: Traditional approaches fail around **12-15 tasks**. Sub-agent approaches scale to **hundreds of tasks**.

---

## How It Works: The Mechanism

### What Gets Stored in Summaries

**Summary sections** (300-500 tokens) capture essential knowledge:

```markdown
### Completed
[What was accomplished - 2-3 sentences]
Example: "Created Users table with authentication fields (id, username,
email, password_hash). Added indexes for email lookup and unique constraints
for username/email. Implemented bcrypt password hashing."

### Files Changed
[List of files with brief descriptions]
Example:
- db/migration/V5__create_users.sql - Users table schema
- src/model/User.kt - User domain model with validation
- src/repository/UserRepository.kt - Repository interface

### Next Steps
[What depends on this or comes next - 1-2 sentences]
Example: "API endpoints can now use this schema for user CRUD operations.
Authentication service will integrate bcrypt hashing for password validation."

### Notes
[Important technical decisions - 1-2 sentences]
Example: "Used UUID for ID instead of auto-increment for distributed system
compatibility. Email field indexed and unique for fast login lookups."
```

**Total**: ~400 tokens containing everything the next specialist needs.

### What Specialists DON'T See

**Full task context** (5-10k tokens) includes unnecessary details:

- ❌ Complete SQL schema with all constraints
- ❌ Full implementation code for models
- ❌ Detailed testing strategies
- ❌ Debug logs and iteration history
- ❌ Alternative approaches considered and rejected
- ❌ Step-by-step implementation process

**Why specialists don't need this**:
- Backend Engineer needs to know "Users table exists with these fields"
- Backend Engineer does NOT need the SQL DDL statements
- Backend Engineer does NOT need to see database testing details
- Backend Engineer does NOT need migration rollback plans

### The Summary Creation Process

**When Task Manager END mode executes**:

1. **Receives specialist output** (2-3 sentence brief)
   ```
   "Created Users table schema in V5__create_users.sql with authentication
   fields and proper indexing. Implemented User model with validation in
   User.kt. Ready for API implementation."
   ```

2. **Extracts key information**:
   - What was accomplished
   - Files that were changed
   - What comes next
   - Important technical decisions

3. **Creates Summary section**:
   ```kotlin
   add_section(
     entityType = "TASK",
     entityId = taskId,
     title = "Summary",
     usageDescription = "Summary of completed task for dependency context",
     content = """
       ### Completed
       Created Users table with authentication fields...

       ### Files Changed
       - db/migration/V5__create_users.sql - Users table schema
       ...
     """,
     contentFormat = "MARKDOWN",
     ordinal = 0,
     tags = "summary,completion"
   )
   ```

4. **Summary stored in database** as a section attached to the task

5. **Next dependent task** reads this Summary section (400 tokens) instead of full task context (5-10k tokens)

### The Dependency Context Flow

**Example: Task 2 depends on Task 1**

```
┌──────────────────────────────────────────────────────────┐
│ TASK 1: Create Database Schema (Completed)              │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Full Task Context (5,000 tokens):                       │
│ - Requirements section (1,000 tokens)                   │
│ - Technical approach section (800 tokens)               │
│ - Implementation section (2,000 tokens)                 │
│ - Testing strategy (500 tokens)                         │
│ - SQL schema code (700 tokens)                          │
│                                                          │
│ Summary Section (400 tokens): ← This is what gets passed│
│   ### Completed                                          │
│   Created Users table with auth fields...               │
│   ### Files Changed                                      │
│   - db/migration/V5__create_users.sql                   │
│   ### Next Steps                                         │
│   API can use this schema...                            │
│   ### Notes                                              │
│   Used UUID for ID, bcrypt for passwords...             │
│                                                          │
└──────────────────────────────────────────────────────────┘
                         │
                         │ Task Manager START reads Summary
                         ▼
┌──────────────────────────────────────────────────────────┐
│ TASK 2: Implement API Endpoints (Starting)              │
├──────────────────────────────────────────────────────────┤
│                                                          │
│ Task Manager START:                                      │
│ 1. Reads Task 2 (2k tokens)                             │
│ 2. Calls get_task_dependencies(Task 2)                  │
│ 3. Finds dependency: Task 1 (completed)                 │
│ 4. Calls get_sections(Task 1, tags='summary')           │
│ 5. Gets Task 1 Summary (400 tokens) ← NOT 5k tokens!   │
│ 6. Includes in brief to orchestrator                    │
│                                                          │
│ Orchestrator launches Backend Engineer with:            │
│ - Task 2 content (2k)                                   │
│ - Task 1 Summary (400 tokens)                           │
│                                                          │
│ Total context: 2.4k tokens                              │
│ vs. Traditional: 7k tokens (2k + 5k full T1 context)    │
│ Savings: 66% per dependency                             │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

### Why This Enables 97% Reduction

**Token savings compound at multiple levels**:

1. **Per-task savings** (66-95% per dependency):
   - Task with 1 dependency: Read 400-token summary instead of 5k-10k full context
   - Task with 3 dependencies: Read 1,200 tokens instead of 15-30k tokens

2. **Orchestrator context savings** (99%):
   - Traditional: Orchestrator accumulates full conversation (5-10k per task)
   - Sub-agent: Orchestrator accumulates brief summaries (200 tokens per task)
   - Each task adds 200 tokens instead of 5-10k tokens

3. **Context isolation savings** (90%):
   - Sub-agent contexts are discarded after task completion
   - No context inheritance between tasks
   - Each specialist starts with clean context
   - Peak sub-agent context: ~3k tokens (vs. unlimited accumulation)

4. **Cross-feature savings** (98%):
   - Features can reference other feature summaries (~500 tokens)
   - No need to read all tasks from dependent features
   - Cross-feature dependencies via summary sections only

**Combined effect**:
```
10-task feature:
  Traditional: 311k tokens processed, 61k accumulated
  Sub-agent:   70k tokens processed, 2k accumulated

Reduction in processed tokens: 77%
Reduction in accumulated tokens: 97%

The 97% figure refers to orchestrator context accumulation,
which is the limiting factor for scaling to large projects.
```

---

## Coordination Overhead: The Real Numbers

The examples above show **accumulated context reduction** (97-99%), but don't account for **coordination overhead** - the tokens used by Feature Manager, Task Manager, and orchestrator communication.

### Real-World Session: 7-Task Documentation Feature

**Observed token usage** (from actual session completing this documentation):

```
Session start: 164k tokens (82% of 200k limit)
Session end:   181k tokens (91% of 200k limit)
Growth:        +17k tokens for 7 tasks completed

Breakdown:
- Coordination messages: ~17k tokens
  - Feature Manager calls: ~2.8k (6 calls × ~400 tokens)
  - Task Manager START: ~2.8k (7 calls × ~400 tokens)
  - Specialist execution: ~5.6k (7 calls × ~800 tokens)
  - Task Manager END: ~1.4k (7 calls × ~200 tokens)
  - Orchestrator transitions: ~4.4k

Average: ~2.4k tokens coordination overhead per task
```

### What This Means

**Without sub-agents** (hypothetical direct implementation):
```
If orchestrator implemented all 7 tasks directly:
- T1 (agent-orchestration.md): Full file + history = ~8k tokens
- T2 (architecture.md): Full file + T1 context = ~12k tokens
- T3 (token examples): Research + T1/T2 = ~15k tokens
- T4 (README): All previous context = ~25k tokens
- T5 (quick-start): All previous = ~30k tokens
- T6 (ai-guidelines): All previous = ~35k tokens
- T7 (templates/workflows): All previous = ~40k tokens

Total context accumulation: ~165k tokens
```

**With sub-agents** (actual):
```
Coordination overhead: 17k tokens
Specialist contexts: Discarded (not accumulated)
Orchestrator context: Task summaries only (~1.4k)

Total context accumulation: 17k tokens
```

**Real savings: 165k - 17k = 148k tokens avoided (89.7% reduction)**

### Understanding the Numbers

**Three different percentages**:

1. **Per-dependency context reduction: 92-95%**
   - Reading summary (400 tokens) vs full task (5-10k tokens)
   - This is real and measurable per task

2. **Accumulated context reduction: 97-99%**
   - What specialists DON'T carry (no context accumulation)
   - Theoretical maximum if coordination was free

3. **Real-world net savings: 85-90%**
   - Accounts for coordination overhead (~2-3k per task)
   - Actual token usage reduction vs direct implementation

### Break-Even Analysis

**When does sub-agent coordination justify its overhead?**

| Tasks | Traditional | Sub-Agent (Coord) | Net Savings | Worth It? |
|-------|-------------|-------------------|-------------|-----------|
| 1     | 5k          | 2.4k              | 52%         | Maybe     |
| 2     | 11k         | 4.8k              | 56%         | Maybe     |
| 3     | 18k         | 7.2k              | 60%         | **Yes**   |
| 4     | 26k         | 9.6k              | 63%         | **Yes**   |
| 5     | 35k         | 12k               | 66%         | **Yes**   |
| 10    | 165k        | 24k               | 85%         | **Yes**   |
| 20    | ~700k       | 48k               | 93%         | **Yes**   |

**Break-even point**: Around **3-4 tasks** where coordination overhead becomes justified by context savings.

### The Real Value Proposition

**Sub-agent orchestration is valuable because**:

1. **Enables impossible work**: Traditional fails at 12-15 tasks, sub-agents scale to 100+
2. **Specialists stay lean**: No context accumulation in specialists (clean context per task)
3. **Cross-domain isolation**: Frontend dev never sees SQL, writer never sees React code
4. **Net positive at scale**: 85-90% real savings for features with 4+ tasks

**Honest assessment**:
- **Small features (1-2 tasks)**: Coordination overhead may not be worth it - work directly
- **Medium features (3-6 tasks)**: Break-even point - sub-agents provide moderate benefit
- **Large features (7+ tasks)**: Clear win - sub-agents enable work impossible otherwise
- **Complex projects (20+ tasks)**: Essential - traditional approaches impossible

## Summary: The Value Proposition

**Sub-agent orchestration achieves 85-90% real token reduction through**:

1. **Summary sections**: 300-500 tokens capture essential knowledge from 5-10k token tasks
2. **Context isolation**: Sub-agents start fresh, no context accumulation
3. **Dependency context passing**: Read summaries (400 tokens) not full tasks (5-10k tokens)
4. **Coordination overhead**: ~2-3k tokens per task (Feature Manager, Task Manager, briefs)

**Real-world impact** (accounting for coordination):

- **Simple features (4 tasks)**: 52k → ~10k tokens (**~80% reduction**)
- **Complex features (10 tasks)**: 311k → ~25k tokens (**~92% reduction**)
- **Cross-domain workflows (9 tasks)**: 51k → ~22k tokens (**~85% reduction**)
- **Large projects (50+ tasks)**: Impossible → ~120k tokens (**enables previously impossible work**)

**The breakthrough**: Traditional approaches fail around 12-15 tasks due to context limits. Sub-agent orchestration scales to hundreds of tasks while maintaining manageable context. The coordination overhead is the price of scalability - and it's worth it for complex projects.

---

**Ready to achieve 85-90% token reduction and scale to complex projects?** [Get started with Task Orchestrator](../README.md)
