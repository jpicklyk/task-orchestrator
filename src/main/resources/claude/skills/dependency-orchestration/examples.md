# Dependency Orchestration Skill - Examples

## Example 1: Complete Dependency Analysis

**User Request:**
> "Analyze dependencies for e-commerce checkout feature"

**Scenario:**
- 8 tasks with complex dependencies
- Need to identify critical path and bottlenecks

**Actions:**
1. Build dependency graph
2. Find critical path
3. Identify bottlenecks
4. Discover parallel opportunities

**Output:**
```
Dependency Analysis: E-commerce Checkout Feature
Total Tasks: 8

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
CRITICAL PATH (determines minimum completion time)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Path: T1 → T3 → T5 → T8
Tasks:
1. Create database schema (Complexity: 5)
2. Implement payment API (Complexity: 8)
3. Add order processing (Complexity: 7)
4. Integration tests (Complexity: 6)

Total Complexity: 26 (72% of all work)
Length: 4 tasks

⚠️ This path determines minimum feature completion time.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
BOTTLENECKS (blocking multiple tasks)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

HIGH IMPACT:
🚨 T3: Implement payment API
   Status: pending
   Blocks: 4 tasks
   • Integration tests
   • UI payment form
   • Order processing
   • Email notifications

   ⚡ PRIORITY: Complete this task ASAP

MEDIUM IMPACT:
⚠️ T1: Create database schema
   Status: pending
   Blocks: 2 tasks
   • Payment API
   • Order processing

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PARALLEL OPPORTUNITIES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Opportunity 1 (Level 0): 45% time saved
├─ T1: Database schema (Complexity: 5)
├─ T2: UI cart component (Complexity: 6)
└─ T4: Email templates (Complexity: 4)
   Sequential: 15 units → Parallel: 6 units

Opportunity 2 (Level 2): 33% time saved
├─ T6: Unit tests (Complexity: 4)
└─ T7: Documentation (Complexity: 5)
   Sequential: 9 units → Parallel: 5 units

Total Potential Time Savings: 40%

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RECOMMENDATIONS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. IMMEDIATE: Complete T1 (Database) and T3 (Payment API)
   → Unblocks 6 other tasks

2. PARALLEL: Launch T1, T2, T4 simultaneously
   → Saves 45% time on first batch

3. MONITOR: T3 is critical path bottleneck
   → Assign best resources
   → Consider breaking into smaller tasks if possible
```

---

## Example 2: Critical Path Identification

**User Request:**
> "What's the critical path for this feature?"

**Scenario:**
- Multiple paths through dependency graph
- Need to find longest path

**Dependency Graph:**
```
T1 (DB) → T2 (API) → T5 (Tests)  [Path A: 17 complexity]
T1 (DB) → T3 (Processing) → T4 (UI) → T5 (Tests)  [Path B: 24 complexity]
```

**Output:**
```
Critical Path Found

Path: T1 → T3 → T4 → T5
Length: 4 tasks
Total Complexity: 24

Tasks on Critical Path:
1. T1: Create database schema (5)
2. T3: Order processing logic (8)
3. T4: UI integration (7)
4. T5: Integration tests (4)

Why This Matters:
- This path represents 67% of total work
- Determines minimum feature completion time
- Cannot be shortened without changing scope
- Should receive highest priority

Optimization Strategy:
✓ Prioritize critical path tasks
✓ Assign best resources
✓ Parallelize non-critical tasks (T2, T6, T7)
✗ Don't delay critical path tasks
```

---

## Example 3: Bottleneck Resolution

**User Request:**
> "Task T3 is blocking everything. What should I do?"

**Analysis:**
```
T3: Implement payment API
- Status: in-progress (40% complete)
- Blocks: 5 tasks
- Critical path: Yes
- Complexity: 8 (high)
```

**Output:**
```
Bottleneck Analysis: T3 (Payment API)

Impact Assessment:
🚨 CRITICAL - Blocks 5 tasks
- T5: Integration tests
- T6: UI payment form
- T7: Order processing
- T8: Email notifications
- T9: Admin dashboard

Current Status:
- In progress: 40% complete
- Est. remaining: 3 hours
- On critical path: YES

Resolution Options:

Option 1: ACCELERATE (Recommended)
- Assign additional developer to help
- Break into sub-tasks (Auth, Processing, Webhooks)
- Complete in parallel
- Estimated gain: 40% faster

Option 2: REPLUMB DEPENDENCIES
- Remove T7 → T3 dependency (may not need API)
- Allow T6 (UI) to start with mocked API
- Estimated gain: Unblock 2 tasks immediately

Option 3: SIMPLIFY SCOPE
- Implement basic payment flow first
- Add advanced features in separate task
- Estimated gain: Unblock tasks sooner

Recommendation: Option 1 - Accelerate completion
This task is on critical path and high-impact.
```

---

## Example 4: Parallel Opportunity Discovery

**User Request:**
> "What tasks can run in parallel?"

**Dependency Graph:**
```
Level 0: T1, T2, T4 (no dependencies)
Level 1: T3, T5 (depend on Level 0)
Level 2: T6 (depends on Level 1)
```

**Output:**
```
Parallel Opportunities Found: 2

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Opportunity 1: Level 0 Tasks
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

3 tasks with NO dependencies:

T1: Database schema (Complexity: 5)
  └─ Specialist: Database Engineer

T2: UI components (Complexity: 6)
  └─ Specialist: Frontend Developer

T4: Email service (Complexity: 4)
  └─ Specialist: Backend Engineer

Sequential Time: 15 units
Parallel Time: 6 units (longest task)
Time Saved: 9 units (60% faster)

Launch Command:
"Launch parallel batch: Database Engineer (T1),
 Frontend Developer (T2), Backend Engineer (T4)"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
Opportunity 2: Level 1 Tasks
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

2 tasks can run parallel AFTER Level 0:

T3: API endpoints (Complexity: 7)
  └─ Depends on: T1

T5: Documentation (Complexity: 5)
  └─ Depends on: T2

Sequential Time: 12 units
Parallel Time: 7 units
Time Saved: 5 units (42% faster)

Total Feature Time Savings: 50%
```

---

## Example 5: Circular Dependency Detection

**User Request:**
> "Why can't I create an execution plan?"

**Analysis:**
```
T2 depends on T5
T5 depends on T7
T7 depends on T2
```

**Output:**
```
✗ CIRCULAR DEPENDENCY DETECTED

Cycle: T2 → T5 → T7 → T2
Length: 3 tasks

Tasks Involved:
- T2: Implement API
- T5: Add caching layer
- T7: Update API for cache

Problem:
This creates an impossible situation:
- T2 needs T5 to be complete
- T5 needs T7 to be complete
- T7 needs T2 to be complete
→ Deadlock! No task can start.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RESOLUTION OPTIONS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Option 1: REMOVE UNNECESSARY DEPENDENCY (Recommended)
✓ Remove T7 → T2 dependency
  Reason: "Update API for cache" doesn't truly need
  the original API implementation. It's an enhancement.

  New flow: T2 → T5 → T7 (sequential, valid)

Option 2: SPLIT TASK
✓ Split T2 into:
  - T2a: Basic API implementation
  - T2b: API with cache support

  New flow: T2a → T5 → T7 → T2b (sequential, valid)

Option 3: REORDER
✓ Complete T2 first (remove all dependencies)
✓ Then add T5 and T7 as separate feature

  New flow: T2 (standalone) → New Feature (T5, T7)

Recommended: Option 1
Most likely this is a case of over-specified dependencies.
```

---

## Example 6: Deep Dependency Chain Warning

**User Request:**
> "Check dependencies for migration feature"

**Analysis:**
```
T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8
(8-level chain)
```

**Output:**
```
⚠️ WARNING: Deep Dependency Chain

Chain Depth: 8 tasks (sequential)
Max Recommended: 5 tasks

Chain: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8

Impact:
✗ Zero parallelism possible
✗ 8× longer completion time
✗ High risk of delays (any task blocks all subsequent)
✗ Difficult to recover from issues

Recommendations:

1. BREAK DEPENDENCIES
   Analyze which dependencies are truly required.
   Example: Does T6 really need T5? Or just T3?

2. CREATE PARALLEL STREAMS
   Group related tasks into independent streams:

   Stream A: T1 → T3 → T5 → T7
   Stream B: T2 → T4 → T6 → T8

   (If dependencies allow)

3. SPLIT FEATURE
   Consider breaking into 2 features:
   - Feature 1: Core migration (T1-T4)
   - Feature 2: Advanced migration (T5-T8)

Benefit: Could reduce completion time by 50%
```

---

## Example 7: Resolution Strategy Generation

**User Request:**
> "Generate a resolution strategy for this feature"

**Context:**
- 2 critical path bottlenecks
- 3 parallel opportunities
- 10 total tasks

**Output:**
```
Resolution Strategy: Authentication Feature

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 1: IMMEDIATE (Do Now)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Critical Path Bottlenecks:
1. T1: Database schema
   - Status: pending
   - Blocks: 4 tasks
   - Action: Assign immediately

2. T3: Authentication API
   - Status: pending
   - Blocks: 5 tasks
   - Action: High priority after T1

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 2: PARALLEL (Run Simultaneously)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Opportunity 1 (Start now):
- T1: Database schema
- T2: UI components
- T4: Email templates
→ 60% time savings

Opportunity 2 (After T3):
- T6: Unit tests
- T7: Documentation
→ 40% time savings

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PHASE 3: DEFER (Can Wait)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Non-Critical Tasks:
- T8: Admin dashboard (not on critical path)
- T9: Reporting (not on critical path)
- T10: Logging enhancement (nice-to-have)

Action: Complete after critical path finished

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
EXECUTION TIMELINE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Week 1:
- Launch Parallel Batch 1 (T1, T2, T4)
- Complete T1
- Start T3 (depends on T1)

Week 2:
- Complete T3
- Launch Parallel Batch 2 (T6, T7)
- Start T5 (depends on T3)

Week 3:
- Complete T5, T6, T7
- Start deferred tasks (T8, T9, T10)

Estimated Completion: 3 weeks
vs Sequential: 5 weeks
Time Saved: 40%
```

---

## Integration Examples

### With Task Orchestration
```
1. Task Orchestration: Needs to create batches
2. Dependency Orchestration: Analyzes dependencies
3. Dependency Orchestration: Returns batch structure
4. Task Orchestration: Uses structure for execution
```

### With Feature Orchestration
```
User: "This feature has many blockers"
1. Feature Orchestration: Requests analysis
2. Dependency Orchestration: Finds bottlenecks
3. Feature Orchestration: Prioritizes bottlenecks
4. Task Orchestration: Executes in optimal order
```

### Standalone Usage
```
User: "Analyze dependencies"
1. Dependency Orchestration: Complete analysis
2. Returns: Critical path + bottlenecks + opportunities
3. User makes decisions based on analysis
```

---

## Visualization Examples

### ASCII Graph
```
Database (T1)
    ├─→ Backend API (T2)
    │       ├─→ UI Integration (T5)
    │       │       └─→ E2E Tests (T7)
    │       └─→ Unit Tests (T6)
    │
UI Components (T3)
    └─→ UI Integration (T5)
         └─→ E2E Tests (T7)

Documentation (T4)
    (no dependencies, can run parallel)
```

### Dependency Matrix
```
       T1  T2  T3  T4  T5  T6  T7
T1     -   ✓   -   -   -   -   -
T2     -   -   -   -   ✓   ✓   -
T3     -   -   -   -   ✓   -   -
T4     -   -   -   -   -   -   -
T5     -   -   -   -   -   -   ✓
T6     -   -   -   -   -   -   -
T7     -   -   -   -   -   -   -

✓ = has dependency
- = no dependency
```
