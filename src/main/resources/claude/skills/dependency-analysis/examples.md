# Dependency Analysis Skill - Usage Examples

## Example 1: Feature Appears Stalled

### Scenario
Developer notices that a feature with 8 tasks has been "in development" for 2 weeks, but only 2 tasks are complete. They want to understand what's blocking progress.

### User Query
> "The authentication feature seems stuck. What's blocking us?"

### Skill Execution

**Step 1**: Get feature ID from context or search
```javascript
search_features(name="authentication")
// Returns: featureId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
```

**Step 2**: Find blocked tasks
```javascript
get_blocked_tasks(featureId="a1b2c3d4-e5f6-7890-abcd-ef1234567890")
```

**Response Data**:
```json
{
  "blockedTasks": [
    {
      "id": "task-1",
      "title": "Implement login UI",
      "blockedBy": ["task-api"]
    },
    {
      "id": "task-2",
      "title": "Add logout functionality",
      "blockedBy": ["task-api"]
    },
    {
      "id": "task-3",
      "title": "Implement password reset",
      "blockedBy": ["task-api", "task-1"]
    },
    {
      "id": "task-4",
      "title": "Add session management",
      "blockedBy": ["task-api"]
    }
  ]
}
```

**Step 3**: Analyze blocking dependencies
```javascript
get_task(id="task-api", includeSections=false)
```

**Response**:
```json
{
  "id": "task-api",
  "title": "Create authentication API endpoints",
  "status": "in_progress",
  "priority": "high",
  "complexity": 7,
  "assignedTo": "Backend Engineer"
}
```

### Skill Response
```
Found 4 blocked tasks (50% of remaining work) in "Authentication Feature":

Root Cause: "Create authentication API endpoints" (in-progress) is blocking 4 tasks:
- Implement login UI
- Add logout functionality
- Implement password reset
- Add session management

Current Status: API task is in-progress (complexity: 7/10)

Recommendation:
1. Prioritize completing the API task (unblocks 4 tasks)
2. Once API is done, "Implement login UI" should be next (it blocks password reset)
3. After that, 3 tasks can proceed in parallel

Estimated Impact: Completing API task will unblock 50% of remaining feature work.
```

---

## Example 2: Planning Parallel Work Across Team

### Scenario
Team has 5 developers available. Project manager wants to maximize parallel work by identifying tasks with no dependencies.

### User Query
> "We have 5 developers ready to work. What tasks can they start in parallel?"

### Skill Execution

**Step 1**: Get all pending tasks with no dependencies
```javascript
search_tasks(projectId="project-id", status="pending")
// Filter to tasks where dependsOn is empty
```

**Step 2**: For each task, verify no incomplete dependencies
```javascript
get_task_dependencies(taskId="task-x")
// Repeat for all pending tasks
```

**Step 3**: Group by specialist type (from tags)

### Response Data
```
Tasks Ready to Start (No Dependencies):

Backend Engineer:
- "Implement user service API" (complexity: 6, priority: high)
- "Add logging infrastructure" (complexity: 4, priority: medium)

Frontend Engineer:
- "Create dashboard layout" (complexity: 5, priority: high)
- "Design error page components" (complexity: 3, priority: low)

Database Engineer:
- "Add database indexes" (complexity: 5, priority: medium)

Test Engineer:
- "Setup integration test framework" (complexity: 6, priority: high)

Documentation:
- "Write API documentation template" (complexity: 2, priority: medium)
```

### Skill Response
```
7 tasks are ready to start with no dependencies:

Parallel Work Assignment (5 developers):

Priority 1 (High Priority, Start Immediately):
- Backend: "Implement user service API" (complexity: 6/10)
- Frontend: "Create dashboard layout" (complexity: 5/10)
- Database: "Add database indexes" (complexity: 5/10)
- Test: "Setup integration test framework" (complexity: 6/10)

Priority 2 (After completing Priority 1):
- Backend: "Add logging infrastructure" (complexity: 4/10)
- Docs: "Write API documentation template" (complexity: 2/10)

Priority 3 (Lower Priority):
- Frontend: "Design error page components" (complexity: 3/10)

Recommendation: Start 4 developers on Priority 1 tasks, assign 5th developer to Priority 2 task. All can proceed in parallel without coordination.
```

---

## Example 3: Investigating Circular Dependencies

### Scenario
Developer tries to mark a task complete but gets an error about circular dependencies. They need to understand and fix the issue.

### User Query
> "I'm getting a circular dependency error with task T5. What's wrong?"

### Skill Execution

**Step 1**: Get dependency chain for task
```javascript
get_task_dependencies(taskId="T5", includeTransitive=true)
```

**Response**:
```json
{
  "dependencies": [
    {"taskId": "T4", "title": "Design user flow"},
    {"taskId": "T3", "title": "Create wireframes", "transitive": true},
    {"taskId": "T5", "title": "Implement UI", "transitive": true}
  ]
}
```

**Step 2**: Trace the cycle
```
T5 (Implement UI)
  → depends on T4 (Design user flow)
    → depends on T3 (Create wireframes)
      → depends on T5 (Implement UI) ← CYCLE DETECTED
```

### Skill Response
```
⚠️ Circular Dependency Detected:

Dependency Cycle:
T5 "Implement UI"
  → T4 "Design user flow"
    → T3 "Create wireframes"
      → T5 "Implement UI" (cycle back to start)

Problem: These tasks depend on each other in a loop, creating a deadlock where none can be started.

Root Cause Analysis:
- T5 depends on T4: UI implementation needs finalized design
- T4 depends on T3: Design needs wireframes
- T3 depends on T5: Wireframes need to see existing UI (INCORRECT)

Solution: Remove the T3→T5 dependency. Wireframes should inform UI implementation, not depend on it.

Recommended Fix:
1. Remove dependency: T3 → T5
2. Correct sequence: T3 (wireframes) → T4 (design) → T5 (implement)

This creates a linear dependency chain with no cycles.
```

---

## Example 4: Finding Critical Path for Release

### Scenario
Product manager needs to know the minimum time to complete a feature, assuming unlimited resources. Need to find the longest dependency chain (critical path).

### User Query
> "What's the fastest we can ship the payment integration feature?"

### Skill Execution

**Step 1**: Get all tasks in feature
```javascript
search_tasks(featureId="payment-feature-id")
```

**Step 2**: Build complete dependency graph
```javascript
// For each task, get dependencies
get_task_dependencies(taskId="task-x", includeTransitive=true)
```

**Step 3**: Calculate critical path (longest chain)

### Dependency Graph
```
Tasks and Dependencies:

T1: Setup Stripe account (no dependencies) - 1 day
T2: Implement payment API (depends on T1) - 3 days
T3: Create payment UI (depends on T2) - 2 days
T4: Add error handling (depends on T2) - 1 day
T5: Write integration tests (depends on T3, T4) - 2 days
T6: Deploy to staging (depends on T5) - 1 day

Parallel paths:
Path A: T1 → T2 → T3 → T5 → T6 = 9 days
Path B: T1 → T2 → T4 → T5 → T6 = 8 days
```

### Skill Response
```
Critical Path Analysis for "Payment Integration Feature":

Longest dependency chain: 5 tasks, 9 days total
Critical Path: T1 → T2 → T3 → T5 → T6

Task Sequence (must complete in order):
1. T1: Setup Stripe account (1 day, complexity: 3/10)
2. T2: Implement payment API (3 days, complexity: 7/10) ← BOTTLENECK
3. T3: Create payment UI (2 days, complexity: 5/10)
4. T5: Write integration tests (2 days, complexity: 6/10)
5. T6: Deploy to staging (1 day, complexity: 4/10)

Parallel Opportunity:
- T4 "Add error handling" (1 day) can run in parallel with T3
- Assign to different developer to save 1 day

Optimized Timeline:
- Sequential: 9 days (one developer)
- With parallel work: 8 days (two developers)

Recommendation: Prioritize T2 (API) as it's the most complex and blocks all downstream work.
```

---

## Example 5: Prioritizing Bug Fixes with Dependencies

### Scenario
Multiple bugs reported, some bugs are blocking work on new features. Need to prioritize which bugs to fix first.

### User Query
> "We have 8 bugs and 12 feature tasks. Which bugs should we fix first?"

### Skill Execution

**Step 1**: Get all blocked feature tasks
```javascript
get_blocked_tasks(projectId="project-id")
```

**Step 2**: Identify bug tasks blocking features
```javascript
// Filter blockedBy tasks where tags include "bug"
search_tasks(tags="bug", status="pending,in_progress")
```

**Step 3**: Count how many tasks each bug is blocking

### Analysis Result
```
Bug Impact Analysis:

High Impact Bugs (blocking multiple tasks):
- Bug #42 "API returns 500 on invalid input" → Blocking 5 feature tasks
  Status: pending, Complexity: 4/10, Priority: critical

- Bug #38 "Database migration fails on fresh install" → Blocking 3 feature tasks
  Status: in_progress, Complexity: 6/10, Priority: high

Medium Impact Bugs (blocking 1-2 tasks):
- Bug #51 "Login session expires too quickly" → Blocking 2 tasks
  Status: pending, Complexity: 3/10, Priority: medium

Low Impact Bugs (not blocking features):
- Bug #22, #33, #45, #49, #50 → Not blocking any tasks
  Priority: low to medium
```

### Skill Response
```
Bug Fix Priority for Maximum Feature Unblocking:

URGENT (Fix Immediately):
1. Bug #42 "API returns 500 on invalid input"
   - Blocking: 5 feature tasks
   - Complexity: 4/10 (relatively easy)
   - Impact Score: 58/100 (high impact, easy fix)
   - Estimated Time: 2-3 hours
   - Recommendation: Fix first, unblocks most work

2. Bug #38 "Database migration fails" (in-progress)
   - Blocking: 3 feature tasks
   - Complexity: 6/10
   - Impact Score: 39/100
   - Status: Already being worked on

MEDIUM PRIORITY (Fix Soon):
3. Bug #51 "Session expires too quickly"
   - Blocking: 2 feature tasks
   - Complexity: 3/10
   - Impact Score: 32/100

LOW PRIORITY (Fix After Features):
- Bugs #22, #33, #45, #49, #50
- Not blocking any work
- Schedule after feature tasks complete

Impact if prioritized correctly:
- Fixing Bug #42 → Unblocks 5 tasks (4 hours of feature work available)
- Fixing Bug #38 → Unblocks 3 more tasks
- Total: 8 tasks unblocked (66% of blocked work)

Recommendation: Assign 1 developer to Bug #42 immediately (2-3 hours to unblock 5 tasks). Continue Bug #38 work. Defer low-priority bugs.
```

---

## Example 6: Sprint Planning - Maximizing Velocity

### Scenario
Scrum master planning 2-week sprint. Want to maximize completed tasks by intelligently ordering work based on dependencies.

### User Query
> "Help me plan the sprint. We have 2 weeks and 4 developers. What order should we tackle tasks?"

### Skill Execution

**Step 1**: Get all tasks ready for sprint
```javascript
search_tasks(projectId="project-id", status="pending")
```

**Step 2**: Analyze dependencies and bottlenecks
```javascript
get_blocked_tasks(projectId="project-id")
// For each task: get_task_dependencies(taskId="x")
```

**Step 3**: Calculate optimal task ordering

### Analysis
```
Available Tasks: 20 pending tasks
Sprint Capacity: 4 developers × 10 days = 40 developer-days
Average task: 2-3 days

Dependency Analysis:
- 8 tasks have no dependencies (ready to start)
- 7 tasks have 1 dependency each
- 5 tasks have 2+ dependencies

Bottleneck Tasks (blocking multiple):
- T2 "Setup CI pipeline" → Blocks 4 tasks
- T5 "Create database schema" → Blocks 6 tasks
```

### Skill Response
```
Sprint Plan - 2 Weeks, 4 Developers:

Week 1 Strategy: Eliminate Bottlenecks

Monday-Tuesday (Days 1-2):
- Dev 1: T5 "Create database schema" (2 days, unblocks 6 tasks)
- Dev 2: T2 "Setup CI pipeline" (2 days, unblocks 4 tasks)
- Dev 3: T8 "Implement auth service" (3 days, no dependencies)
- Dev 4: T12 "Design homepage" (2 days, no dependencies)

Wednesday-Friday (Days 3-5):
After T5 completes, 6 new tasks become available
After T2 completes, 4 new tasks become available

- Dev 1: T15 "Add user CRUD" (depends on T5, now ready)
- Dev 2: T20 "Setup automated tests" (depends on T2, now ready)
- Dev 3: T8 continues (1 day left)
- Dev 4: T13 "Build component library" (2 days)

Week 2 Strategy: Parallel Execution

Monday-Friday (Days 6-10):
With bottlenecks cleared, 10 tasks now have no dependencies:
- All 4 developers can work in parallel
- No coordination overhead
- Maximum velocity

Projected Sprint Completion:
- Week 1: 6 tasks complete (focusing on bottlenecks)
- Week 2: 10-12 tasks complete (parallel work)
- Total: 16-18 tasks (80-90% of sprint backlog)

Key Insight: Prioritizing bottleneck tasks (T2, T5) in Week 1 unlocks maximum parallel work in Week 2.

Alternative Approach (Not Recommended):
If we ignore dependencies and just pick tasks randomly:
- Week 1: Complete 6 tasks, but 12 tasks still blocked
- Week 2: Only 2 tasks available (both developers wait)
- Total: 8 tasks complete (40% of sprint backlog)

Recommendation: Follow the suggested ordering to maximize sprint velocity.
```

---

## Example 7: Debugging "Why Can't I Start This Task?"

### Scenario
Developer assigned to task, but unclear why they can't start yet. Need to trace back through dependency chain.

### User Query
> "I'm assigned to 'Add payment webhooks' but it says dependencies aren't met. What am I waiting for?"

### Skill Execution

**Step 1**: Get the task with dependencies
```javascript
get_task(id="webhook-task-id", includeDependencies=true)
```

**Step 2**: Trace dependency chain
```javascript
get_task_dependencies(taskId="webhook-task-id", includeTransitive=true)
```

### Response Data
```json
{
  "task": {
    "id": "webhook-task-id",
    "title": "Add payment webhooks",
    "status": "pending"
  },
  "dependencies": [
    {
      "taskId": "T12",
      "title": "Implement payment processing",
      "status": "in_progress",
      "dependsOn": ["T9", "T10"]
    }
  ],
  "transitiveDependencies": [
    {
      "taskId": "T9",
      "title": "Setup Stripe integration",
      "status": "completed"
    },
    {
      "taskId": "T10",
      "title": "Create payment database tables",
      "status": "in_progress"
    }
  ]
}
```

### Skill Response
```
Dependency Trace for "Add payment webhooks":

You are blocked by: T12 "Implement payment processing" (in-progress)

Full Dependency Chain:
[Your Task] "Add payment webhooks"
  ← T12 "Implement payment processing" (in-progress) ← YOU ARE HERE
    ← T9 "Setup Stripe integration" (completed) ✓
    ← T10 "Create payment database tables" (in-progress) ← ROOT CAUSE

Root Cause: T10 "Create payment database tables" is still in progress
- T10 is being worked on by Database Engineer
- Once T10 completes, T12 can finish
- Once T12 completes, you can start your task

Current Status:
- T9: ✓ Completed
- T10: ⏳ In-progress (blocks T12)
- T12: ⏳ In-progress (blocks your task)

Estimated Timeline:
- T10 completion: Expected in 1-2 days
- T12 completion: Expected 2 days after T10
- Your task ready: ~3-4 days from now

What You Can Do Now:
1. Review T12 "Implement payment processing" code to prepare
2. Read Stripe webhook documentation
3. Design webhook handling strategy
4. Write unit tests (can start before implementation)
5. Check if there are other tasks you can help with

Would you like me to find other tasks you can work on while waiting?
```

---

## Example 8: Quarterly Planning - Long-term Dependencies

### Scenario
Engineering manager planning Q4 roadmap. Need to understand high-level dependencies across multiple features.

### User Query
> "Show me the dependency relationships between our Q4 features"

### Skill Execution

**Step 1**: Get all features in project
```javascript
search_features(projectId="project-id")
```

**Step 2**: For each feature, analyze task dependencies
```javascript
// Identify cross-feature dependencies
```

### Feature Dependency Map
```
Q4 Features (4 total):

F1: User Authentication (8 tasks)
  ├─ No external dependencies
  └─ Blocks: F2, F3, F4

F2: User Dashboard (12 tasks)
  ├─ Depends on: F1 (auth required)
  └─ Blocks: F4 (dashboard needed for settings)

F3: Payment Integration (10 tasks)
  ├─ Depends on: F1 (auth required for payments)
  └─ Independent from F2, F4

F4: User Settings (6 tasks)
  ├─ Depends on: F1 (auth), F2 (dashboard layout)
  └─ No downstream dependencies
```

### Skill Response
```
Q4 Feature Dependency Analysis:

Critical Path: F1 → F2 → F4
- Must complete sequentially
- Total: 26 tasks across 3 features
- Estimated: 8-10 weeks

Parallel Opportunity: F3 (Payment Integration)
- Only depends on F1 (auth)
- Can proceed in parallel with F2
- 10 tasks, estimated: 4 weeks

Recommended Q4 Schedule:

Weeks 1-3: Feature F1 "User Authentication"
- Critical foundation for all other features
- 8 tasks, complexity: medium-high
- Must complete first (blocks everything)

Weeks 4-7: Features F2 + F3 in parallel
- F2 "User Dashboard" (12 tasks) ← Team A
- F3 "Payment Integration" (10 tasks) ← Team B
- Both can proceed simultaneously after F1

Weeks 8-10: Feature F4 "User Settings"
- Depends on F1 + F2 completing
- 6 tasks, complexity: low-medium
- Can start once F2 completes

Bottleneck Analysis:
⚠️ F1 is a critical bottleneck - it blocks ALL other Q4 work
- Risk: If F1 slips, entire Q4 roadmap delays
- Mitigation: Prioritize F1, assign best team, add buffer time

Velocity Optimization:
- Sequential only: 16-18 weeks (F1→F2→F3→F4)
- With parallelization: 10-12 weeks (F1→[F2+F3]→F4)
- Time savings: 6 weeks (37% faster)

Recommendation:
1. Over-invest in F1 (critical path blocker)
2. Split teams after F1 to parallelize F2 + F3
3. Build buffer time into F1 estimate
4. Consider soft-launching F3 before F4 (independent feature)
```

---

## Key Takeaways from Examples

### Pattern Recognition

**When user asks about**:
- "What's blocking..." → Run `get_blocked_tasks`
- "Can we work in parallel..." → Find tasks with no dependencies
- "Circular dependency" → Trace dependency chain
- "Fastest timeline" → Calculate critical path
- "What should we prioritize" → Analyze bottlenecks + impact

### Response Quality

**Good responses include**:
1. Clear identification of the problem
2. Root cause analysis (why the blockage exists)
3. Quantified impact (tasks blocked, time saved)
4. Actionable recommendations (specific next steps)
5. Alternative approaches when applicable

### Tool Combinations

**Most analyses require**:
- `get_blocked_tasks` - Find what's blocked
- `get_task_dependencies` - Understand why
- `get_task` - Get task details (priority, complexity)
- `search_tasks` - Find patterns across multiple tasks

**Power combo**:
```javascript
// 1. Find blocked tasks
blockedTasks = get_blocked_tasks(featureId=X)

// 2. For each blocker, count impact
for each blocker:
  impactCount = count(tasks where blockedBy contains blocker)

// 3. Get blocker details
blockerDetails = get_task(id=blocker)

// 4. Calculate priority score
score = (impactCount × 10) + (priority × 5) - (complexity × 2)

// 5. Recommend highest score task
```

This pattern appears in Examples 1, 4, 5, and 6.
