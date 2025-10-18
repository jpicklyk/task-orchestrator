# Dependency Analysis Skill - Troubleshooting Guide

## Common Dependency Issues

### Issue 1: Tasks Stuck in "Pending" Despite No Dependencies

**Symptoms**:
- Task shows status="pending"
- `get_task_dependencies` returns empty list
- Task has been pending for days
- `get_blocked_tasks` doesn't include this task

**Possible Causes**:

#### Cause A: Task Never Started
**Problem**: Task has no dependencies but hasn't been assigned or started

**Diagnosis**:
```javascript
get_task(id="task-id")
// Check: assignedTo field empty? No start date?
```

**Solution**: Task is actually ready to start, just needs assignment
```
Response: "This task has no dependencies and is ready to start. It appears
nobody has been assigned yet. Recommend: Assign to [specialist] and begin work."
```

#### Cause B: Implicit Dependencies Not Captured
**Problem**: Real-world dependencies exist but weren't added to system

**Diagnosis**:
- Check task comments/sections for mentions of "waiting for..."
- Ask user if there are unstated dependencies

**Solution**: Add missing dependencies
```
Response: "Task shows no formal dependencies, but team may be waiting for
external factors (access, approval, resources). Recommend: Add explicit
dependencies or start task if truly ready."
```

#### Cause C: Completed Dependencies Not Marked
**Problem**: Dependency tasks are done but status not updated

**Diagnosis**:
```javascript
get_task_dependencies(taskId="task-id")
// Check if dependency tasks are actually complete but status=in_progress
```

**Solution**: Update dependency statuses
```
Response: "Task is blocked by 'X', but checking that task shows work is
complete. Recommend: Mark task X as complete to unblock this task."
```

---

### Issue 2: Circular Dependency Not Detected

**Symptoms**:
- User reports deadlock situation
- Multiple tasks all "waiting" on each other
- `get_task_dependencies` doesn't show circular reference
- No tasks can start

**Possible Causes**:

#### Cause A: Indirect Circular Dependency
**Problem**: Cycle exists through 3+ tasks, not immediately obvious

**Diagnosis**:
```javascript
// Trace full chain for each suspicious task
get_task_dependencies(taskId="A", includeTransitive=true)
get_task_dependencies(taskId="B", includeTransitive=true)
get_task_dependencies(taskId="C", includeTransitive=true)

// Look for task appearing in its own transitive dependencies
```

**Example**:
```
Task A depends on Task B
Task B depends on Task C
Task C depends on Task D
Task D depends on Task A ← Cycle found
```

**Solution**: Map full dependency graph, identify cycle, recommend breaking link
```
Response: "Indirect circular dependency found: A→B→C→D→A.
Recommend: Remove D→A dependency and restructure work to be linear: D→A→B→C"
```

#### Cause B: Feature-Level Circular Dependency
**Problem**: Tasks in Feature X depend on tasks in Feature Y, and vice versa

**Diagnosis**:
```javascript
// Check cross-feature dependencies
search_tasks(featureId="feature-X")
// For each task, check if dependencies are in different feature
get_task_dependencies(taskId="task-in-X")
// Does it depend on tasks in Feature Y that depend on Feature X?
```

**Solution**: Identify shared foundation work, extract to separate feature
```
Response: "Features X and Y have circular dependencies. Both need 'Auth Service'.
Recommend: Create new Feature Z 'Shared Authentication' and make both X and Y
depend on it (linear: Z→X, Z→Y)."
```

---

### Issue 3: Tasks Show as Blocked But Dependencies Are Complete

**Symptoms**:
- `get_blocked_tasks` returns task
- Checking dependencies shows all are status="completed"
- Task still can't be started

**Possible Causes**:

#### Cause A: Database/Cache Inconsistency
**Problem**: Dependency status cached or not updated in DB

**Diagnosis**:
```javascript
// Re-query with fresh data
get_task(id="task-id", includeDependencies=true)

// Check dependency statuses individually
for each dep in dependencies:
  get_task(id=dep.id)
```

**Solution**: If all dependencies truly complete, this is a data issue
```
Response: "All dependencies show as complete but task still marked as blocked.
This may be a caching issue. Recommend: Try updating the task (change and
revert priority) to force refresh, or report this bug."
```

#### Cause B: Soft Dependencies
**Problem**: Task has informal dependencies not captured in system

**Diagnosis**: Ask user about the task context
```
Questions to ask:
- "Are there any unstated requirements for this task?"
- "Is the task waiting for approval, access, or resources?"
- "Are there external dependencies outside Task Orchestrator?"
```

**Solution**: Document actual blockers
```
Response: "System shows dependencies complete, but task may be waiting for:
[external dependency]. Recommend: Add comment to task documenting external
blocker, or create placeholder task for external dependency."
```

---

### Issue 4: Dependency Chain Seems Too Long

**Symptoms**:
- Task has 5+ levels of dependencies
- Critical path is 8+ tasks deep
- Timeline seems unrealistic

**Possible Causes**:

#### Cause A: Over-Specified Dependencies
**Problem**: Dependencies added for tasks that could be parallel

**Diagnosis**:
```javascript
get_task_dependencies(taskId="task-id", includeTransitive=true)
// Examine each dependency: is it truly required, or just "nice to have"?
```

**Example of Over-Specification**:
```
Bad:
  "Write API docs" depends on "Implement API" ✓ (correct)
  "Write API docs" depends on "Design database" ✗ (too strict)

Better:
  "Write API docs" depends on "Implement API"
  "Implement API" depends on "Design database"
  (Docs inherit transitive dependency naturally)
```

**Solution**: Remove unnecessary direct dependencies
```
Response: "Dependency chain is 8 tasks deep. Several dependencies are
transitive and don't need explicit links. Recommend: Remove direct dependencies
between 'A' and 'D' (A already depends on B→C→D)."
```

#### Cause B: Work Not Properly Parallelized
**Problem**: Tasks made sequential that could run in parallel

**Diagnosis**: Look for tasks with same complexity that don't actually conflict
```javascript
// Example: Frontend and Backend tasks made sequential unnecessarily
Task "Build API" (backend)
Task "Build UI" depends on "Build API"

// But UI could use mock API during development
```

**Solution**: Break hard dependencies where possible
```
Response: "Frontend is waiting for backend completion, but could proceed with
mock API. Recommend: Create task 'Create API mocks' with no dependencies, make
'Build UI' depend on mocks instead of real API. Real API integration happens later."
```

---

### Issue 5: Bottleneck Task Seems Low Priority

**Symptoms**:
- Task blocks 5+ other tasks
- Task has priority="low" or priority="medium"
- Team is focusing on other work first

**Possible Causes**:

#### Cause A: Priority Not Updated
**Problem**: Task was low priority when created, but importance increased

**Diagnosis**:
```javascript
get_task(id="bottleneck-task-id")
// Check: When was priority last updated?
// Compare: How many tasks depend on this?
```

**Solution**: Recommend priority adjustment
```
Response: "Task X is blocking 6 other tasks but has priority='medium'. Based
on impact, this should be priority='critical'. Recommend: Increase priority
to reflect blocking status."
```

#### Cause B: Tasks Shouldn't Depend on Bottleneck
**Problem**: Dependencies were added incorrectly, inflating bottleneck importance

**Diagnosis**: Review each dependent task
```javascript
// For each task blocked by bottleneck
for each dependent:
  get_task(id=dependent.id)
  // Question: Does this REALLY need bottleneck to complete first?
```

**Solution**: Remove incorrect dependencies
```
Response: "Task X appears to block 6 tasks, but reviewing dependents shows
3 don't actually need it. Recommend: Remove dependencies from tasks Y and Z
(they can proceed independently)."
```

---

### Issue 6: Feature Appears Stalled Despite Available Work

**Symptoms**:
- Feature has many pending tasks
- `get_blocked_tasks` shows most tasks blocked
- Only 1-2 tasks in progress
- Team reports not enough work to do

**Possible Causes**:

#### Cause A: Bottleneck Task Not Prioritized
**Problem**: Root blocker not being worked on actively

**Diagnosis**:
```javascript
get_blocked_tasks(featureId="feature-id")
// Identify: What task blocks the most work?
// Check: Is that task in-progress? Assigned?
```

**Solution**: Prioritize the bottleneck
```
Response: "Task 'Setup database' is blocking 8 tasks but hasn't been started.
Recommend: Assign database engineer immediately to unblock majority of feature work."
```

#### Cause B: False Perception of Blocking
**Problem**: Team assumes everything is blocked, but some tasks are ready

**Diagnosis**:
```javascript
search_tasks(featureId="feature-id", status="pending")
// For each pending task: get_task_dependencies(taskId=X)
// Filter: Which tasks have NO incomplete dependencies?
```

**Solution**: Identify ready-to-start tasks
```
Response: "Feature appears stalled, but 4 tasks are actually ready to start
with no dependencies: [list tasks]. Team has work available. Recommend: Assign
these tasks immediately."
```

---

### Issue 7: Dependency Analysis Returns Unexpected Results

**Symptoms**:
- `get_blocked_tasks` returns empty list, but user insists tasks are blocked
- `get_task_dependencies` returns different results on repeated calls
- Dependency counts don't match expectations

**Possible Causes**:

#### Cause A: Query Filtering Issues
**Problem**: Filters excluding relevant tasks

**Diagnosis**:
```javascript
// Try broader query
get_blocked_tasks(projectId="project-id") // Instead of featureId
search_tasks(status="pending,in_progress,blocked") // Include all statuses
```

**Solution**: Adjust query scope
```
Response: "Query was limited to featureId=X, but blocking dependencies are in
different feature. Recommend: Use projectId filter instead for cross-feature
dependency analysis."
```

#### Cause B: Task Status Ambiguity
**Problem**: Tasks marked with non-standard status values

**Diagnosis**:
```javascript
search_tasks(featureId="feature-id")
// Check: Are there tasks with status="on_hold", "waiting", etc?
```

**Solution**: Clarify status meanings
```
Response: "Some tasks have status='on_hold' which isn't included in blocked
task queries. Recommend: Use status='pending' for tasks with dependencies,
status='in_progress' for active work only."
```

---

## Diagnostic Workflows

### Workflow 1: Why Is This Task Blocked?

**Step-by-Step Diagnosis**:

1. **Get task details**:
   ```javascript
   get_task(id="task-id", includeDependencies=true)
   ```

2. **Check dependency statuses**:
   ```javascript
   for each dependency:
     get_task(id=dep.id)
     // Is status="completed"? If not, that's the blocker
   ```

3. **Trace transitive dependencies**:
   ```javascript
   get_task_dependencies(taskId="dependency-id", includeTransitive=true)
   // Find root cause (deepest incomplete dependency)
   ```

4. **Report findings**:
   ```
   "Task X is blocked by Task Y (in-progress).
   Task Y is blocked by Task Z (pending).
   Root cause: Task Z has no dependencies but hasn't been started.
   Recommendation: Start Task Z to unblock chain."
   ```

### Workflow 2: Why Isn't Feature Making Progress?

**Step-by-Step Diagnosis**:

1. **Get feature overview**:
   ```javascript
   get_feature(id="feature-id", includeTasks=true, includeTaskCounts=true)
   ```

2. **Analyze task distribution**:
   ```
   Total: X tasks
   Completed: Y tasks
   In-progress: Z tasks
   Pending: W tasks
   ```

3. **Find blocked tasks**:
   ```javascript
   get_blocked_tasks(featureId="feature-id")
   ```

4. **Identify bottlenecks**:
   ```javascript
   // For each in-progress or pending task
   // Count how many tasks depend on it
   ```

5. **Report findings**:
   ```
   "Feature has X tasks:
   - Y completed (good progress)
   - Z in-progress (currently active)
   - W pending, of which:
     - N blocked by dependencies
     - M ready to start (no dependencies)

   Bottleneck: Task 'Q' is blocking N tasks.
   Recommendation: Prioritize completing Task Q."
   ```

### Workflow 3: Is This a Valid Dependency?

**Questions to Ask**:

1. **Strict dependency?**
   - Must TaskB ABSOLUTELY wait for TaskA to complete?
   - Or can TaskB start with partial results from TaskA?

2. **Could work in parallel?**
   - Can TaskB use mocks/stubs while TaskA completes?
   - Can TaskB work on independent parts?

3. **Is dependency transitive?**
   - Does TaskC need to depend on both TaskA and TaskB?
   - Or is TaskC→TaskB enough (TaskB already depends on TaskA)?

4. **Is dependency bidirectional?** (Red flag)
   - Does TaskA depend on TaskB AND TaskB depend on TaskA?
   - This is always incorrect (circular dependency)

**Valid Dependency Examples**:
- ✅ "Implement API" → "Write API docs" (docs need API to exist)
- ✅ "Design database" → "Implement API" (API needs schema)
- ✅ "Create UI mockups" → "Implement UI" (implementation needs design)

**Invalid Dependency Examples**:
- ❌ "Write tests" → "Deploy to production" (tests should run before deploy)
- ❌ "Backend API" → "Frontend UI" → "Backend API" (circular)
- ❌ "Add logging" → "Implement feature" (logging is orthogonal)

---

## Error Messages and Solutions

### Error: "Circular dependency detected"

**What it means**: Tasks depend on each other in a loop

**How to fix**:
1. Run dependency analysis to find the cycle
2. Identify the weakest link in the cycle
3. Remove that dependency
4. Restructure work to be linear

**Example Fix**:
```
Before: A→B→C→A (circular)
After:  C→A→B (linear)
```

### Error: "Task has no dependencies but shows as blocked"

**What it means**: Data inconsistency or external blocker

**How to fix**:
1. Refresh task data
2. Check for comments mentioning blockers
3. Ask user if there are external dependencies
4. If truly ready, mark as "in-progress"

### Error: "Dependency chain exceeds maximum depth"

**What it means**: More than 10 levels of dependencies (overly complex)

**How to fix**:
1. Review chain for unnecessary dependencies
2. Remove transitive dependencies that are explicitly stated
3. Consider breaking into multiple features
4. Parallelize work where possible

---

## Prevention Best Practices

### 1. Keep Dependency Chains Short

**Goal**: Maximum 3-4 levels of dependencies

**How**:
- Only add strict dependencies (must complete first)
- Don't add "nice to have" dependencies
- Let transitive dependencies be implicit

### 2. Review Dependencies During Planning

**When**: During task breakdown (Planning Specialist work)

**Check**:
- Does every dependency make sense?
- Could tasks run in parallel with mocks/stubs?
- Are we over-specifying dependencies?

### 3. Update Priorities Based on Blocking

**Rule**: If task blocks 3+ other tasks, consider increasing priority

**Automation idea**: Hook that warns when low-priority task becomes bottleneck

### 4. Use Tags to Indicate Dependency Type

**Suggested tags**:
- `hard-dependency` - Must complete first
- `soft-dependency` - Would be nice to complete first
- `data-dependency` - Needs data from other task
- `approval-dependency` - Waiting for external approval

This helps during analysis to understand which dependencies are flexible.

---

## When to Escalate

**Escalate to user/team when**:

1. **Circular dependencies found**
   - Can't be resolved automatically
   - Requires team decision on work restructuring

2. **Bottleneck blocking >50% of feature**
   - May need resource reallocation
   - May need to break up bottleneck task

3. **External dependencies blocking work**
   - Need user to follow up with external parties
   - May need to create workarounds

4. **Dependency chain >5 levels deep**
   - Suggests poor planning
   - May need feature re-architecture

5. **Tasks blocked for >1 week**
   - Dependencies not being resolved
   - May need priority adjustment or intervention

**Don't escalate when**:
- Normal sequential work (A→B→C is fine)
- Bottleneck is actively being worked on
- Dependencies are clear and expected
- Timeline is on track

---

## Advanced Debugging

### Tool: Manual Dependency Trace

When automated analysis isn't working, manually trace dependencies:

```javascript
// Start with problem task
task = get_task(id="problem-task")

// Get direct dependencies
deps = get_task_dependencies(taskId="problem-task")

// For each dependency
for each dep in deps:
  status = get_task(id=dep.id).status
  if status != "completed":
    // This is a blocker, recurse
    subdeps = get_task_dependencies(taskId=dep.id)
    // Continue tracing...
```

Build a tree structure manually and identify root causes.

### Tool: Dependency Impact Matrix

Create a matrix showing which tasks block which:

```
         | T1 | T2 | T3 | T4 | T5
---------|----|----|----|----|----
Blocks→  |    |    |    |    |
---------|----|----|----|----|----
T1       |    | X  | X  |    |
T2       |    |    |    | X  |
T3       |    |    |    | X  | X
T4       |    |    |    |    | X
T5       |    |    |    |    |

Interpretation:
- T1 blocks 2 tasks (T2, T3) → Medium bottleneck
- T3 blocks 2 tasks (T4, T5) → Medium bottleneck
- T4 blocks 1 task (T5) → Minor bottleneck
- T2, T5 block nothing → No bottleneck
```

This helps visualize the dependency graph when troubleshooting.

---

## Getting Help

If you've tried these troubleshooting steps and still have issues:

1. **Document the problem**:
   - Which task(s) are affected?
   - What analysis did you run?
   - What were you expecting vs what happened?

2. **Check for edge cases**:
   - Cross-feature dependencies?
   - External dependencies?
   - Tasks with unusual statuses?

3. **Review with team**:
   - Is the dependency structure actually correct?
   - Are there unspoken dependencies?
   - Should work be restructured?

4. **Report bugs** (if data inconsistency):
   - Provide task IDs
   - Provide exact tool calls that showed inconsistent data
   - Describe expected behavior

Remember: Dependency analysis is about understanding relationships, not just running tools. Use your judgment to interpret results and make recommendations that help teams deliver faster.
