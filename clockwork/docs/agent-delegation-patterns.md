# Agent Delegation Patterns

Guide for orchestrators (like Claude Code's Workflow Analyst) on mapping workflow roles to agent types and delegation patterns for Task Orchestrator workflows.

## Table of Contents

- [Role → Agent Type Mapping](#role--agent-type-mapping)
- [Delegation Patterns by Phase](#delegation-patterns-by-phase)
- [Parallel Agent Patterns](#parallel-agent-patterns)
- [Agent Prompt Templates](#agent-prompt-templates)
- [Error Handling and Recovery](#error-handling-and-recovery)
- [Advanced Patterns](#advanced-patterns)

## Role → Agent Type Mapping

Task Orchestrator uses **role annotations** to classify workflow statuses into semantic phases. Orchestrators should dispatch agent types appropriate for each role.

| Role | Agent Type | Rationale | Tools Needed |
|------|-----------|-----------|--------------|
| **queue** | Plan / Explore | Planning and research before implementation | Read, Grep, Glob, query_sections, query_container |
| **work** | general-purpose (sonnet) | Full implementation capability needed | Read, Write, Edit, Bash, manage_sections, request_transition |
| **review** | general-purpose / Explore | Testing, code review, verification | Read, Bash, query_sections, request_transition |
| **blocked** | Plan / general-purpose | Analysis to resolve blockers | Read, Grep, query_dependencies, manage_dependencies |
| **terminal** | haiku / general-purpose | Cleanup, documentation, final notes | Read, Write, manage_sections, request_transition |

### Why These Agent Types?

**Queue Phase** (planning, backlog, pending):
- Read-only exploration sufficient
- No code changes needed yet
- Agent maps requirements to technical approach

**Work Phase** (in-progress, in-development, changes-requested):
- File editing, test creation required
- Full bash access for running commands
- Agent needs to modify code and create tests

**Review Phase** (in-review, testing, validating):
- Test execution, code reading
- May need to write test results to sections
- Agent validates quality gates

**Terminal Phase** (completed, cancelled, archived):
- Documentation finalization
- Summary population
- Lightweight work suitable for smaller models

## Delegation Patterns by Phase

### 1. Queue Phase — Research & Planning

**Agent reads requirements and explores codebase:**

```javascript
// Step 1: Get task metadata without sections
task = query_container(
  operation="get",
  containerType="task",
  id="task-uuid",
  includeSections=false
)
// Returns: title, description, status, priority

// Step 2: Query queue-phase sections tagged role:queue
sections = query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:queue",
  includeContent=true
)
// Returns: requirements, planning notes, acceptance criteria
// Token cost: ~2,000-3,000 (vs ~7,000+ all sections)

// Step 3: Explore codebase to understand implementation scope
// Use Grep, Glob, Read tools to map requirements to code

// Step 4: Update task sections with findings
manage_sections(
  operation="updateText",
  id="section-uuid",
  oldText="[Research findings]",
  newText="Requires changes to AuthService.kt and UserRepository.kt..."
)
```

**Agent decision point:**
- If requirements clear → recommend transition to work phase
- If questions exist → update sections with questions, keep in queue

### 2. Work Phase — Implementation

**Agent implements code changes:**

```javascript
// Step 1: Transition to work status
request_transition(
  containerId="task-uuid",
  containerType="task",
  trigger="start"
)
// Response includes: newRole="work", activeFlow, flowSequence

// Step 2: Query work-phase sections tagged role:work
sections = query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:work",
  includeContent=true
)
// Returns: technical approach, implementation guidance, code specs
// Token cost: ~800-1,500 (vs ~3,000-5,000 all sections)

// Step 3: Read technical approach, implement changes
// Use Read, Edit, Write tools for code changes

// Step 4: Update implementation sections with changes made
manage_sections(
  operation="updateText",
  id="section-uuid",
  oldText="[Implementation steps]",
  newText="Implemented JWT validation middleware in AuthMiddleware.kt..."
)

// Step 5: Transition to review when implementation complete
request_transition(
  containerId="task-uuid",
  containerType="task",
  trigger="start"  // Advance to next status in flow
)
```

**Agent decision point:**
- Implementation complete + tests passing → transition to review
- Implementation blocked → transition to blocked status

### 3. Review Phase — Testing & Verification

**Agent validates work and runs tests:**

```javascript
// Step 1: Query review-phase sections tagged role:review
sections = query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:review",
  includeContent=true
)
// Returns: test checklists, review criteria, validation procedures

// Step 2: Execute test plan
// Use Bash tool to run tests

// Step 3: Record test results in review sections
manage_sections(
  operation="updateText",
  id="section-uuid",
  oldText="[Test results]",
  newText="✅ All 47 tests passing\n✅ Code coverage: 92%\n✅ No lint errors"
)

// Step 4: If review passes, transition to completion
request_transition(
  containerId="task-uuid",
  containerType="task",
  trigger="complete"
)
// Response includes: cascadeEvents (if feature also completes), unblockedTasks
```

**Early unblock pattern:**

If task has `unblockAt: "review"` in dependency configuration, downstream tasks become unblocked when entering review phase (before completion).

```javascript
// After transition to review status:
response = request_transition(trigger="start")
// Response includes: unblockedTasks array with taskId and title
```

Orchestrator dispatches agents for unblocked tasks immediately (don't wait for completion).

### 4. Terminal Phase — Completion

**Agent finalizes documentation:**

```javascript
// Step 1: Query terminal-phase sections tagged role:terminal
sections = query_sections(
  entityType="TASK",
  entityId="task-uuid",
  tags="role:terminal",
  includeContent=true
)
// Returns: completion checklist, retrospective notes, final documentation

// Step 2: Populate task summary (prerequisite for completion)
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  summary="Implemented JWT authentication middleware with token validation, refresh flow, and integration tests. All 47 tests passing."
)

// Step 3: Complete the task
request_transition(
  containerId="task-uuid",
  containerType="task",
  trigger="complete"
)

// Step 4: Check response for cascades and unblocked tasks
// cascadeEvents: Parent feature may have advanced if all tasks complete
// unblockedTasks: Downstream tasks now available for work
```

**Agent reports:**
- Task completion status
- Any cascade events (feature/project advancement)
- Newly unblocked downstream tasks

## Parallel Agent Patterns

### Independent Tasks — Full Parallelism

**Pattern**: Tasks with no dependency edges can run simultaneously.

```javascript
// Scenario: 5 independent tasks, all in "pending" status
tasks = [
  {id: "task-1", title: "Add login endpoint"},
  {id: "task-2", title: "Add logout endpoint"},
  {id: "task-3", title: "Add password reset endpoint"},
  {id: "task-4", title: "Add token refresh endpoint"},
  {id: "task-5", title: "Add profile update endpoint"}
]

// Dispatch all agents simultaneously (no wait)
for task in tasks:
  dispatch_agent(task_id=task.id, agent_type="general-purpose")
```

**Advantages**:
- Maximum parallelism
- Fastest completion time
- No coordination overhead

### Linear Chains — Sequential Dispatch

**Pattern**: Tasks with dependency edges (A→B→C) must be dispatched sequentially.

**CRITICAL**: Never parallelize tasks with dependency edges between them. Wait for Agent-A to return before dispatching Agent-B.

```javascript
// Scenario: Linear chain A → B → C
chain = [
  {id: "task-A", title: "Design API schema"},
  {id: "task-B", title: "Implement API endpoints", blockedBy: ["task-A"]},
  {id: "task-C", title: "Write integration tests", blockedBy: ["task-B"]}
]

// Sequential dispatch (wait for each)
result_A = dispatch_agent(task_id="task-A", agent_type="general-purpose")
wait_for_completion(result_A)

result_B = dispatch_agent(task_id="task-B", agent_type="general-purpose")
wait_for_completion(result_B)

result_C = dispatch_agent(task_id="task-C", agent_type="general-purpose")
wait_for_completion(result_C)
```

**Why sequential?**
- Task B cannot start until A completes (blocked by dependency)
- If B is dispatched early, `request_transition(trigger="start")` fails with "incomplete blocking dependency"

### Fan-out Pattern — After Root Completes

**Pattern**: One root task blocks many downstream tasks (A→{B,C,D}).

```javascript
// Scenario: Task A blocks B, C, D
//   A (foundation) → B (feature 1)
//                  → C (feature 2)
//                  → D (feature 3)

// Step 1: Complete root task A
result_A = dispatch_agent(task_id="task-A", agent_type="general-purpose")
wait_for_completion(result_A)

// Step 2: Check unblockedTasks in response
response = request_transition(containerId="task-A", containerType="task", trigger="complete")
// response.unblockedTasks = [{taskId: "task-B", ...}, {taskId: "task-C", ...}, {taskId: "task-D", ...}]

// Step 3: Dispatch all unblocked tasks in parallel
for task in response.unblockedTasks:
  dispatch_agent(task_id=task.taskId, agent_type="general-purpose")
```

**Advantages**:
- Maximize parallelism after blocker removed
- Automatic unblock detection via request_transition response

### Fan-in Pattern — Wait for All Upstream

**Pattern**: One task depends on many upstream tasks ({B,C,D}→E).

```javascript
// Scenario: Task E waits for B, C, D to complete
//   B (component 1) →
//   C (component 2) → E (integration)
//   D (component 3) →

// Step 1: Dispatch upstream tasks in parallel (no edges between B,C,D)
dispatch_agent(task_id="task-B", agent_type="general-purpose")
dispatch_agent(task_id="task-C", agent_type="general-purpose")
dispatch_agent(task_id="task-D", agent_type="general-purpose")

// Step 2: Wait for all to complete
wait_for_all([result_B, result_C, result_D])

// Step 3: Check unblockedTasks after last completion
// When task D completes (last blocker), response includes task E
response = request_transition(containerId="task-D", containerType="task", trigger="complete")
// response.unblockedTasks = [{taskId: "task-E", title: "Integration testing"}]

// Step 4: Dispatch downstream task E
dispatch_agent(task_id="task-E", agent_type="general-purpose")
```

**Key insight**: `request_transition` automatically detects when downstream tasks become unblocked.

## Agent Prompt Templates

### General Delegation Prompt Structure

```
You are Agent-{name} working on Task: {task_title}

MCP Task UUID: {task_uuid}
Container Type: task
Current Status: {current_status}
Current Role: {current_role}

Your objectives:
1. Query sections tagged with role:{current_role}
2. {phase-specific objectives}
3. Update relevant sections with your work
4. Transition to next status when ready

Status transitions:
- To start work: request_transition(containerId="{task_uuid}", containerType="task", trigger="start")
- To complete: request_transition(containerId="{task_uuid}", containerType="task", trigger="complete")

Expected return format:
- Markdown summary of work completed
- Status transitions executed
- Any blockers encountered
- Newly unblocked downstream tasks (from response.unblockedTasks)
```

### Queue Phase Prompt (Planning Agent)

```
You are Planning Agent for Task: {task_title}

MCP Task UUID: {task_uuid}
Current Status: pending
Current Role: queue

Your objectives:
1. Query sections tagged with role:queue for requirements
   query_sections(entityType="TASK", entityId="{task_uuid}", tags="role:queue")

2. Explore codebase to understand scope
   - Use Grep to find relevant files
   - Use Read to understand existing implementation

3. Document findings in task sections
   - Technical approach needed
   - Files requiring changes
   - Estimated complexity

4. Recommend readiness for work phase
   - If ready: "Ready to transition to work phase"
   - If questions: "Remaining questions: ..."

Expected return:
- Technical approach summary
- Files to modify
- Complexity estimate (1-10)
- Readiness recommendation
```

### Work Phase Prompt (Implementation Agent)

```
You are Implementation Agent for Task: {task_title}

MCP Task UUID: {task_uuid}
Current Status: in-progress
Current Role: work

Your objectives:
1. Transition to work status:
   request_transition(containerId="{task_uuid}", containerType="task", trigger="start")

2. Query work-phase sections:
   query_sections(entityType="TASK", entityId="{task_uuid}", tags="role:work")

3. Implement code changes per technical approach
   - Use Read, Edit, Write for code modifications
   - Create/update tests
   - Run tests locally

4. Update implementation sections with changes made

5. Transition to review when complete:
   request_transition(containerId="{task_uuid}", containerType="task", trigger="start")

Expected return:
- Files modified (absolute paths)
- Tests created/updated
- Test results (pass/fail)
- Status transition result (response.newStatus, response.unblockedTasks)
```

### Review Phase Prompt (Testing Agent)

```
You are Testing Agent for Task: {task_title}

MCP Task UUID: {task_uuid}
Current Status: testing
Current Role: review

Your objectives:
1. Query review-phase sections:
   query_sections(entityType="TASK", entityId="{task_uuid}", tags="role:review")

2. Execute test plan
   - Run all tests via Bash
   - Check code coverage
   - Validate against review criteria

3. Record results in review sections

4. If tests pass, complete the task:
   request_transition(containerId="{task_uuid}", containerType="task", trigger="complete")

5. Check response for cascades and unblocked tasks

Expected return:
- Test execution summary (pass/fail counts)
- Code coverage percentage
- Status transition result
- Cascade events (if any): response.cascadeEvents
- Newly unblocked tasks: response.unblockedTasks
```

### Terminal Phase Prompt (Completion Agent)

```
You are Completion Agent for Task: {task_title}

MCP Task UUID: {task_uuid}
Current Status: completed
Current Role: terminal

Your objectives:
1. Query terminal-phase sections:
   query_sections(entityType="TASK", entityId="{task_uuid}", tags="role:terminal")

2. Populate task summary (prerequisite for completion):
   manage_container(operation="update", containerType="task", id="{task_uuid}",
     summary="[Concise summary of work completed, up to 500 chars]")

3. Finalize documentation in terminal sections

4. Report completion status

Expected return:
- Task summary written
- Documentation finalized
- Any retrospective notes
```

## Error Handling and Recovery

### Blocked Dependency Error

**Symptom**: `request_transition(trigger="start")` fails with "incomplete blocking dependency"

**Cause**: Upstream task not yet complete

**Recovery pattern**:
```javascript
// Step 1: Query blocking dependencies
dependencies = query_dependencies(
  taskId="task-uuid",
  direction="incoming",
  type="BLOCKS",
  includeTaskInfo=true
)

// Step 2: Report blockers to orchestrator
blockers = dependencies.filter(d => d.status != "completed")
report_error({
  error: "Task blocked by incomplete dependencies",
  blockers: blockers.map(b => ({id: b.taskId, title: b.title, status: b.status}))
})

// Step 3: Orchestrator waits or dispatches agents for blockers first
```

### Prerequisite Not Met Error

**Symptom**: `request_transition(trigger="complete")` fails with "summary must be at most 500 characters"

**Cause**: Task summary not populated

**Recovery pattern**:
```javascript
// Step 1: Populate summary (agent generates from work done)
manage_container(
  operation="update",
  containerType="task",
  id="task-uuid",
  summary="Implemented authentication middleware with JWT validation..."
)

// Step 2: Retry completion
request_transition(containerId="task-uuid", containerType="task", trigger="complete")
```

### Cascade Failure

**Symptom**: `response.cascadeEvents` contains `applied: false` with error

**Cause**: Parent entity failed validation (e.g., feature summary missing)

**Recovery pattern**:
```javascript
// Step 1: Check cascade events for failures
cascadeEvents = response.cascadeEvents.filter(e => e.applied == false)

// Step 2: Report to orchestrator
for event in cascadeEvents:
  report_warning({
    message: "Cascade failed for parent entity",
    parentType: event.targetType,
    parentId: event.targetId,
    error: event.error
  })

// Step 3: Orchestrator dispatches agent to fix parent entity
// (e.g., populate feature summary, complete remaining feature tasks)
```

## Advanced Patterns

### Early Unblock with unblockAt Configuration

Some dependencies allow downstream work to start before upstream completion (e.g., testing can begin while documentation is still in progress).

**Dependency configuration**:
```javascript
manage_dependencies(
  operation="create",
  dependencies=[{
    fromTaskId: "upstream-task-uuid",
    toTaskId: "downstream-task-uuid",
    type: "BLOCKS",
    unblockAt: "review"  // Downstream unblocks when upstream enters review phase
  }]
)
```

**Orchestrator dispatch logic**:
```javascript
// After upstream task enters review phase
response = request_transition(containerId="upstream-uuid", containerType="task", trigger="start")
// response.newRole = "review"

// Check unblockedTasks (downstream task unblocked early)
if response.unblockedTasks.length > 0:
  for task in response.unblockedTasks:
    dispatch_agent(task_id=task.taskId, agent_type="general-purpose")
```

### Cascade-Aware Dispatching

When a task completes, cascades may advance parent feature/project. Orchestrators should check `cascadeEvents` and act accordingly.

**Pattern**:
```javascript
response = request_transition(containerId="task-uuid", containerType="task", trigger="complete")

// Check for automatic cascades
if response.cascadeEvents.length > 0:
  for cascade in response.cascadeEvents:
    if cascade.applied && cascade.automatic:
      // Parent advanced automatically
      log_info(f"Feature {cascade.targetName} advanced to {cascade.newStatus}")

      // Check if this cascade unblocked more tasks
      if cascade.childCascades.length > 0:
        // Recursive cascade (e.g., feature → project)
        log_info(f"Recursive cascade to {cascade.childCascades[0].targetType}")
```

### Role-Based Agent Switching

Some tasks may span multiple roles. Orchestrator can dispatch different agent types per phase.

**Pattern**:
```javascript
task_status = query_container(operation="get", containerType="task", id="task-uuid")

if task_status.role == "queue":
  dispatch_agent(task_id="task-uuid", agent_type="Plan")
elif task_status.role == "work":
  dispatch_agent(task_id="task-uuid", agent_type="general-purpose")
elif task_status.role == "review":
  dispatch_agent(task_id="task-uuid", agent_type="Explore")  # Read-only review
elif task_status.role == "terminal":
  dispatch_agent(task_id="task-uuid", agent_type="haiku")  # Lightweight completion
```

### Graph Traversal for Bottleneck Identification

Use `query_dependencies(neighborsOnly=false)` for full graph analysis.

**Pattern**:
```javascript
// Get full dependency graph with analysis
graph = query_dependencies(
  taskId="root-task-uuid",
  neighborsOnly=false
)

// Response includes:
// - chain: topologically sorted task IDs
// - depth: maximum chain depth
// - criticalPath: longest path through graph
// - bottlenecks: tasks with high fan-out (blocking many others)
// - parallelizable: groups of tasks at same depth (can run concurrently)

// Prioritize dispatching agents for bottleneck tasks
for bottleneck in graph.bottlenecks:
  dispatch_agent(task_id=bottleneck.taskId, agent_type="general-purpose", priority="high")

// Dispatch parallelizable groups simultaneously
for group in graph.parallelizable:
  for task in group:
    dispatch_agent(task_id=task.taskId, agent_type="general-purpose")
```

## Related Documentation

- [Role Tag Convention](role-tag-convention.md) - Template section tagging for workflow phases
- [Hook Integration Guide](hook-integration-guide.md) - Detecting status transitions with hooks
- [Status Progression Guide](status-progression.md) - Workflow configuration and validation rules
- [API Reference](api-reference.md) - Complete MCP tools documentation
- [AI Guidelines](ai-guidelines.md) - How AI agents use Task Orchestrator
