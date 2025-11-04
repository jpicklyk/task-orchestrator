# Task Orchestration - Execution Patterns

Comprehensive guide to execution strategies, configuration patterns, and error handling.

## Execution Strategies

### Strategy 1: Sequential Execution

**When to use:**
- All tasks have dependencies on previous tasks
- No parallelization opportunities
- Linear workflow required

**Pattern:**
```
T1 → T2 → T3 → T4
```

**Implementation:**
```javascript
// Launch one task at a time
for (task of tasks) {
  recommendedAgent = recommend_agent(taskId=task.id)
  launchSpecialist(recommendedAgent)
  waitForCompletion()
}
```

**Time:** Total = sum of all task durations
**Use case:** Data pipeline, migration scripts, deployment steps

---

### Strategy 2: Full Parallel Execution

**When to use:**
- No dependencies between tasks
- Independent work streams
- Maximum speed required

**Pattern:**
```
T1
T2
T3
T4
```

**Implementation:**
```javascript
// Launch all tasks simultaneously (respecting max_parallel_tasks)
allTasks = query_container(operation="search", containerType="task", featureId="...", status="pending")

for (task of allTasks) {
  recommendedAgent = recommend_agent(taskId=task.id)
  launchSpecialistAsync(recommendedAgent)  // Don't wait
}

// Monitor all in parallel
monitorParallelExecution(allTasks)
```

**Time:** Total = longest task duration
**Savings:** Up to 75% if all tasks equal duration
**Use case:** Independent features, test suites, documentation tasks

---

### Strategy 3: Hybrid Batched Execution

**When to use:**
- Mix of dependencies and parallel opportunities
- Most common real-world scenario
- Optimize for both speed and correctness

**Pattern:**
```
Batch 1: T1, T3 (parallel)
Batch 2: T2 (depends on T1)
Batch 3: T4 (depends on T2, T3)
```

**Implementation:**
```javascript
// Step 1: Build dependency graph
batches = createExecutionBatches(featureId)

// Step 2: Execute batch by batch
for (batch of batches) {
  if (batch.parallel) {
    // Launch all tasks in batch simultaneously
    for (task of batch.tasks) {
      launchSpecialistAsync(task)
    }
    waitForBatchComplete(batch)
  } else {
    // Launch tasks sequentially within batch
    for (task of batch.tasks) {
      launchSpecialist(task)
      waitForCompletion()
    }
  }
}
```

**Time:** Total = sum of batch durations (batches run sequentially, tasks within batch run in parallel)
**Savings:** 30-50% typical for standard features
**Use case:** Standard feature development (database → backend → frontend → tests)

---

### Strategy 4: Resource-Aware Execution

**When to use:**
- Configuration specifies resource limits
- System constraints (memory, API limits)
- Controlled parallelism required

**Pattern:**
```
max_parallel_tasks: 3
Batch 1a: T1, T2, T3 (parallel)
Batch 1b: T4, T5 (wait for slot)
```

**Implementation:**
```javascript
// Step 1: Get resource limit from config
maxParallel = 3  // From .taskorchestrator/config.yaml

// Step 2: Split large batches
batch = batches[0]  // Has 5 parallelizable tasks

if (batch.tasks.length > maxParallel) {
  // Split into sub-batches
  subBatch1 = batch.tasks.slice(0, maxParallel)  // T1, T2, T3
  subBatch2 = batch.tasks.slice(maxParallel)     // T4, T5

  // Launch first sub-batch
  for (task of subBatch1) {
    launchSpecialistAsync(task)
  }

  // Queue remaining tasks
  queuedTasks = subBatch2
}

// Step 3: Fill slots as tasks complete
onTaskComplete = (completedTaskId) => {
  if (queuedTasks.length > 0) {
    nextTask = queuedTasks.shift()
    launchSpecialist(nextTask)
  }
}
```

**Time:** Total = (total tasks / max_parallel) × average task duration
**Use case:** Resource-constrained environments, rate-limited APIs, memory-intensive tasks

---

## Configuration Guidance

**Note:** Configuration patterns are documented here for AI reference. Configuration is NOT dynamically loaded via MCP tools.

### Parallelism Strategy

**Default recommended settings:**

```yaml
# .taskorchestrator/config.yaml (documented pattern)
parallelism:
  max_parallel_tasks: 5        # Maximum concurrent tasks
  auto_launch: true            # Auto-cascade to next batch
  respect_dependencies: true   # Always check dependencies (CRITICAL)
```

**Best practices:**
- **max_parallel_tasks**: 3-5 for most projects (manageable monitoring)
- **auto_launch**: `true` for automation, `false` for manual control
- **respect_dependencies**: ALWAYS `true` (prevents blocking issues)

### Specialist Routing

**Routing rules (from agent-mapping.yaml pattern):**

```yaml
# Tags → Specialist mapping
backend:        Implementation Specialist (Haiku)
frontend:       Implementation Specialist (Haiku)
database:       Implementation Specialist (Haiku)
testing:        Implementation Specialist (Haiku)
documentation:  Implementation Specialist (Haiku)

bug:            Senior Engineer (Sonnet)
error:          Senior Engineer (Sonnet)
blocker:        Senior Engineer (Sonnet)
complex:        Senior Engineer (Sonnet)

feature-creation:  Feature Architect (Opus)
planning:          Planning Specialist (Sonnet)
task-breakdown:    Planning Specialist (Sonnet)
```

**Routing algorithm:**
1. Call `recommend_agent(taskId)`
2. If recommendation provided → use it
3. If no recommendation:
   - Check fallback_behavior in config
   - If `ask_user` → prompt user for specialist choice
   - If `use_default` → use default_specialist (Implementation Specialist Haiku)
4. **Never guess** or hardcode specialist assignments

**Fallback configuration pattern:**

```yaml
specialist_routing:
  fallback_behavior: "use_default"  # or "ask_user"
  default_specialist: "Implementation Specialist"
```

### Quality Gates (Optional)

**Hook-based validation pattern:**

```yaml
quality_gates:
  enabled: false  # Disable until hooks implemented

  task_gates:
    testing:
      hook: "run_tests"
      required: true

    completion:
      hook: "validate_summary"
      required: false  # Warning only
```

**Behavior:**
- `required: true` → Blocks status transition if gate fails
- `required: false` → Shows warning but allows progression

**Use case:** CI/CD integration, automated testing, code coverage checks

---

## Error Handling

### Error 1: Task Blocked During Execution

**Symptom:**
Task status changes to "blocked" mid-execution

**Detection:**
```javascript
// Monitor task status
task = query_container(operation="get", containerType="task", id=taskId)

if (task.status == "blocked") {
  // Specialist encountered blocker
}
```

**Actions:**
1. Notify orchestrator: "Task T2 blocked - waiting for external API access"
2. Suggest unblocking actions based on blocker type:
   - External dependency → "Contact team X for access"
   - Technical issue → "Launch Senior Engineer to investigate"
   - Missing information → "Clarify requirements with stakeholder"
3. Do NOT cascade to next batch
4. Report blocker in batch progress: "Batch 2: 0/1 (blocked)"

**Resolution:**
```javascript
// After blocker resolved
"Use Status Progression Skill to unblock task and resume work"
```

---

### Error 2: Specialist Fails Task

**Symptom:**
Task marked as failed by specialist

**Detection:**
```javascript
task = query_container(operation="get", containerType="task", id=taskId)

if (task.status == "failed") {
  // Specialist could not complete task
}
```

**Actions:**
1. Report failure to user: "Task T3 failed - specialist encountered errors"
2. Analyze failure reason (from task sections/specialist report)
3. Suggest remediation:
   - Code errors → "Fix issues and retry task"
   - Test failures → "Address failing tests"
   - Blocker → "Resolve external dependency"
4. **Do NOT cascade** to next batch (prevents cascading failures)
5. Pause feature execution until issue resolved

**Resolution:**
```javascript
// After fixing issues
"Use Status Progression Skill to reset task to pending and retry"
```

---

### Error 3: Max Parallel Limit Reached

**Symptom:**
Trying to launch more tasks than max_parallel_tasks allows

**Detection:**
```javascript
inProgressTasks = query_container(
  operation="search",
  containerType="task",
  status="in-progress"
)

if (inProgressTasks.length >= maxParallelTasks) {
  // At capacity
}
```

**Actions:**
1. Queue remaining tasks: "5 tasks in progress (max). Queueing 2 additional tasks."
2. Wait for slot to open
3. When task completes, automatically launch next queued task
4. Report queued tasks to user: "Sub-batch 1b queued (2 tasks waiting for slots)"

**Implementation:**
```javascript
// Maintain queue
queuedTasks = []

onTaskComplete = (completedTaskId) => {
  if (queuedTasks.length > 0) {
    nextTask = queuedTasks.shift()
    launchSpecialist(nextTask)
    notify(`Launched queued task: ${nextTask.title}`)
  }
}
```

---

### Error 4: No Specialist Matched

**Symptom:**
recommend_agent returns no recommendation

**Detection:**
```javascript
recommendation = recommend_agent(taskId=taskId)

if (!recommendation.recommended) {
  // No specialist matched by tags
}
```

**Actions:**
1. Check fallback_behavior from config (documented pattern, not dynamically loaded)
2. If `ask_user`:
   ```
   Task: Optimize performance
   Tags: optimization, performance (no domain match)

   No specialist matched by tags.
   Which specialist should handle this task?
   1. Backend Engineer
   2. Frontend Developer
   3. Senior Engineer (complex)
   ```
3. If `use_default`:
   ```
   No specialist matched.
   Using fallback: Implementation Specialist (default)

   Note: Consider adding more specific tags:
   - For DB: database, query-optimization
   - For frontend: frontend, ui-performance
   - For backend: backend, api-optimization
   ```

**Prevention:**
- Improve task tagging during creation
- Update agent-mapping.yaml with missing patterns
- Add domain-specific tags (backend, frontend, database, etc.)

---

### Error 5: Circular Dependency Detected

**Symptom:**
Cannot create execution batches due to circular dependency

**Detection:**
```javascript
batches = createExecutionBatches(featureId)

if (batches.error == "circular_dependency") {
  // T2 → T5 → T7 → T2
}
```

**Actions:**
1. Report circular dependency to user:
   ```
   ✗ Error: Circular Dependencies Detected

   Cycle found:
   T2 (Implement API) → T5 (Add caching) → T7 (Update API) → T2

   Resolution options:
   1. Remove T7 → T2 dependency (likely unnecessary)
   2. Split T2 into two tasks (API-v1, API-v2)
   3. Reorder: Complete T2 before T5
   ```

2. Suggest using Dependency Orchestration Skill for detailed analysis:
   ```
   Use Dependency Orchestration Skill to:
   - Visualize dependency graph
   - Identify critical path
   - Suggest dependency removals
   ```

3. **Do NOT proceed** with execution until resolved

**Resolution:**
User must manually resolve circular dependency by removing or reordering dependencies.

---

### Error 6: Batch Timeout

**Symptom:**
Batch taking longer than expected

**Detection:**
```javascript
batchStartTime = Date.now()
expectedDuration = batch.estimatedDuration

checkInterval = setInterval(() => {
  elapsed = Date.now() - batchStartTime

  if (elapsed > expectedDuration * 1.5) {  // 50% overtime
    // Batch running long
  }
}, 60000)  // Check every minute
```

**Actions:**
1. Report delay to user: "Batch 2 running 50% over estimated time (45 min vs 30 min)"
2. Check task statuses for issues:
   ```javascript
   for (task of batch.tasks) {
     taskStatus = query_container(operation="get", containerType="task", id=task.id)
     if (taskStatus.status == "blocked") {
       report(`Task ${task.title} is blocked`)
     }
   }
   ```
3. Suggest user intervention if needed
4. Continue monitoring until batch completes or user intervenes

**Not an error if:**
- Complex task legitimately taking longer
- Specialist working through issues
- All tasks still in-progress (not blocked/failed)

---

## Token Efficiency Patterns

### Pattern 1: Batch Status Checks

**Inefficient:**
```javascript
// Get full task details for each task
for (task of batchTasks) {
  fullTask = query_container(
    operation="get",
    containerType="task",
    id=task.id,
    includeSections=true  // 2,800 tokens per task
  )
}
// Total: 2,800 × 10 tasks = 28,000 tokens
```

**Efficient:**
```javascript
// Get minimal task overview for batch
tasks = query_container(
  operation="search",
  containerType="task",
  featureId=featureId,
  status="in-progress"
)
// Total: ~1,200 tokens for all 10 tasks (95% savings)
```

---

### Pattern 2: Specialist Launch Instructions

**Inefficient:**
```javascript
// Pass full task context to orchestrator
fullTask = query_container(
  operation="get",
  containerType="task",
  id=taskId,
  includeSections=true
)

"Launch Backend Engineer with this task: [full task JSON]"
// Total: ~2,900 tokens
```

**Efficient:**
```javascript
// Minimal specialist reference
recommendation = recommend_agent(taskId=taskId)

"Launch ${recommendation.agent} for task ${taskId}"
// Specialist reads full context themselves
// Total: ~50 tokens (98% savings)
```

---

### Pattern 3: Progress Reporting

**Inefficient:**
```javascript
// Return full batch details
return {
  batch: fullBatchData,
  tasks: allTaskDetails,
  progress: calculations
}
// Total: ~3,500 tokens
```

**Efficient:**
```javascript
// Minimal progress summary
return "Batch 1: 3/5 tasks complete (60%)"
// Total: ~20 tokens (99% savings)
```

---

## Best Practices Summary

### 1. Dependency Management
- ✅ **Always** check dependencies before launching tasks
- ✅ Use `query_dependencies` with `includeTaskInfo=true`
- ✅ Validate no circular dependencies before batching
- ❌ **Never** launch tasks without dependency analysis

### 2. Specialist Routing
- ✅ **Always** use `recommend_agent` tool
- ✅ Respect recommendation if provided
- ✅ Use documented fallback behavior if no match
- ❌ **Never** guess or hardcode specialist assignments

### 3. Resource Management
- ✅ Respect `max_parallel_tasks` configuration
- ✅ Queue tasks when at capacity
- ✅ Monitor resource usage during execution
- ❌ **Never** exceed parallelism limits

### 4. Progress Monitoring
- ✅ Check task status regularly during execution
- ✅ Report progress to user proactively
- ✅ Detect blockers and failures early
- ❌ **Never** launch and forget (silent execution)

### 5. Error Handling
- ✅ Handle failures gracefully without cascade
- ✅ Report clear error messages with remediation steps
- ✅ Pause execution on critical errors
- ❌ **Never** cascade to next batch on failure

### 6. Token Efficiency
- ✅ Use `overview` operations for status checks
- ✅ Return minimal progress reports
- ✅ Batch specialist launches in single message
- ✅ Cache batch information to avoid re-querying
- ❌ **Never** include full task details in progress reports

### 7. Status Management
- ✅ **Always** use Status Progression Skill for status changes
- ✅ Detect events and delegate to Status Progression Skill
- ✅ Let Status Progression Skill read config and validate
- ❌ **Never** directly change task status

---

## Configuration Reference

**Note:** These are documented patterns for AI reference. Configuration is NOT dynamically loaded via MCP tools. All configuration should be documented in CLAUDE.md or skill files.

```yaml
# .taskorchestrator/config.yaml (documented pattern)

# Parallelism settings
parallelism:
  max_parallel_tasks: 5      # Concurrent task limit
  auto_launch: true          # Auto-cascade to next batch
  respect_dependencies: true # Always validate dependencies (CRITICAL)

# Specialist routing
specialist_routing:
  fallback_behavior: "use_default"  # or "ask_user"
  default_specialist: "Implementation Specialist"

# Quality gates (optional)
quality_gates:
  enabled: false
  task_gates:
    testing:
      hook: "run_tests"
      required: true
```

**Key settings:**
- `max_parallel_tasks`: 3-5 recommended
- `auto_launch`: `true` for automation, `false` for manual control
- `respect_dependencies`: ALWAYS `true`
- `fallback_behavior`: `use_default` recommended
- `quality_gates.enabled`: `false` until hooks implemented

---

## Related Documentation

- **Examples**: See `examples.md` for detailed walkthroughs
- **SKILL.md**: See `SKILL.md` for core workflows and trigger points
- **Status Progression**: See `.claude/skills/status-progression/SKILL.md`
- **Event-Driven Pattern**: See `docs/event-driven-status-progression-pattern.md`
