---
name: Task Management
description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status, mcp__task-orchestrator__update_task, mcp__task-orchestrator__recommend_agent, mcp__task-orchestrator__get_task_dependencies, mcp__task-orchestrator__add_section
---

# Task Management Skill

**Purpose**: Coordinate task lifecycle operations including routing tasks to specialists, completing tasks, updating status, and checking dependencies.

**Token Savings**: ~1300 tokens per START operation, ~1500 tokens per END operation → ~300-600 tokens (77% reduction)

## When to Use This Skill

✅ **Use Task Management Skill for**:
- Recommending which specialist should work on a task
- Completing a task and creating a summary
- Updating task status (pending → in_progress → completed)
- Checking task dependencies before starting work
- Simple coordination operations (2-5 tool calls)

❌ **Do NOT use this Skill for**:
- Actually implementing the task (use specialist subagents)
- Complex reasoning or planning (use Planning Specialist)
- Task creation (use direct MCP tools)

## Core Capabilities

### 1. Recommend Specialist for Task

**When**: You need to determine which specialist agent should work on a task

**Steps**:
1. Get task details: `get_task(id, includeSections=true, includeFeature=false)`
2. Get specialist recommendation: `recommend_agent(taskId)`
3. Format recommendation with context from task sections

**What to Include in Recommendation**:
- Specialist agent name
- Task title and ID
- Relevant task sections (Requirements, Technical Approach)
- Dependency context if task has dependencies
- Estimated complexity

### 2. Complete Task with Summary

**When**: A specialist agent has finished work and reported completion

**Steps**:
1. Get task details: `get_task(id, includeSections=true)`
2. Create Summary section: `add_section(entityType="TASK", entityId, title="Summary", content, ordinal=999)`
3. Update task if needed: `update_task(id, summary)` (optional)
4. Set status to completed: `set_status(id, status="completed")`

**Summary Section Requirements**:
- Title: "Summary"
- Content: 300-500 token summary of what was accomplished
- Ordinal: 999 (always last section)
- Format: MARKDOWN
- Include: What was implemented, key decisions, files changed, next steps

### 3. Update Task Status

**When**: Task status needs to change during workflow

**Valid Transitions**:
- `pending` → `in_progress` (task work started)
- `in_progress` → `completed` (task finished)
- `in_progress` → `blocked` (dependencies or issues)
- `blocked` → `in_progress` (blocker resolved)

**Steps**:
1. Verify current status: `get_task(id, includeSections=false)`
2. Check dependencies if moving to completed: `get_task_dependencies(id)`
3. Update status: `set_status(id, status)`

**Important**:
- Only mark completed when work is actually done
- Check that all dependencies are completed before starting
- Use blocked status when waiting on dependencies

### 4. Check Task Dependencies

**When**: Before starting task work, need to verify dependencies are ready

**Steps**:
1. Get dependencies: `get_task_dependencies(id)`
2. Check each dependency status
3. Identify any incomplete dependencies
4. Report blockers or confirm ready to start

**What to Report**:
- Total dependency count
- Completed vs incomplete
- Which dependencies are blocking (with IDs)
- Recommended action (wait, start anyway, resolve blocker)

## Tool Usage Patterns

### Pattern 1: Route Task to Specialist

```
1. get_task(id="550e8400-...", includeSections=true, includeFeature=false)
   → Get task title, tags, complexity, sections

2. recommend_agent(taskId="550e8400-...")
   → Get specialist recommendation

3. Format response:
   "Recommend Backend Engineer for this task.

   Task: Implement user authentication API
   Complexity: 7/10

   Key Requirements (from Requirements section):
   - JWT token generation
   - Password hashing with bcrypt
   - Rate limiting

   Technical Approach (from Technical Approach section):
   - Use Ktor framework
   - PostgreSQL for user storage
   - Redis for session management

   Launch: Task Backend Engineer START with taskId=550e8400-..."
```

### Pattern 2: Complete Task

```
1. get_task(id="550e8400-...", includeSections=true)
   → Verify task exists and get current state

2. add_section(
     entityType="TASK",
     entityId="550e8400-...",
     title="Summary",
     usageDescription="Summary of completed work for dependency context",
     content="### Completed Work\n\n- Implemented user authentication API...",
     contentFormat="MARKDOWN",
     ordinal=999
   )
   → Create completion summary

3. set_status(id="550e8400-...", status="completed")
   → Mark task complete

4. Report to orchestrator:
   "Task completed. Implemented user authentication API with JWT tokens,
   password hashing, and rate limiting. Created Summary section for
   dependent tasks."
```

### Pattern 3: Check Dependencies Before Starting

```
1. get_task_dependencies(id="550e8400-...")
   → Get all dependencies

2. Analyze results:
   - Total: 3 dependencies
   - Completed: 2 (Database schema, API framework setup)
   - Incomplete: 1 (Redis configuration - blocked)

3. Report:
   "Task has 1 incomplete dependency:
   - T2: Redis configuration (status: blocked)

   Recommendation: Wait for T2 completion before starting this task.
   Authentication API requires Redis for session storage."
```

## Integration with Hooks

**Task Management Skill works seamlessly with hooks**:

### Auto-Commit Hook
When you use `set_status(status="completed")`, a PostToolUse hook can automatically:
- Create git commit with task title
- Add Task-ID to commit message
- Stage all changes

**You don't need to trigger this** - the hook runs automatically when you complete tasks.

### Test Execution Hook
When completing critical tasks, a hook can:
- Run test suite before allowing completion
- Block completion if tests fail
- Report test results

**Hook handles quality gates** - you just complete the task normally.

## Error Handling

### Task Not Found
```
Error: Task 550e8400-... not found

Action: Verify task ID is correct, check if task was deleted
```

### Invalid Status Transition
```
Error: Cannot transition from 'completed' to 'in_progress'

Action: Task is already complete. Create new task for additional work.
```

### Missing Dependency Summary
```
Warning: Dependency T1 has no Summary section

Action: Dependent task may lack context. Consider updating T1 with Summary section.
```

## Best Practices

### 1. Always Check Dependencies First
Before routing a task to a specialist, check dependencies to avoid wasted specialist work.

### 2. Create Rich Summaries
Summary sections are read by dependent tasks. Include:
- What was implemented
- Key decisions and why
- Files changed
- Important patterns used
- Next steps or considerations

### 3. Use Appropriate Status
- `pending`: Task not started
- `in_progress`: Specialist actively working
- `blocked`: Waiting on dependency or external factor
- `completed`: Work finished, Summary created

### 4. Coordinate with Feature Management
When completing last task in a feature:
- Notify orchestrator
- Suggest using Feature Management Skill to complete feature
- Provide feature-level summary

## Common Workflows

### Workflow 1: Start Task Work
```
User: "Work on task T1"

Task Management Skill:
1. get_task_dependencies(T1)
   → Check all dependencies complete
2. get_task(T1, includeSections=true)
   → Get task details
3. recommend_agent(T1)
   → Identify specialist
4. Report: "All dependencies complete. Recommend Backend Engineer. Here's the task context..."
```

### Workflow 2: Complete Task
```
Specialist: "I completed the authentication API implementation"

Task Management Skill:
1. get_task(T1)
   → Verify task exists
2. add_section(Summary with 400-token completion report)
   → Document completion
3. set_status(T1, "completed")
   → Mark complete
4. Report: "Task T1 completed and summarized"

[Hook automatically creates git commit]
```

### Workflow 3: Check Blocked Task
```
User: "Why is task T3 blocked?"

Task Management Skill:
1. get_task(T3)
   → Get current status
2. get_task_dependencies(T3)
   → Find blocking dependencies
3. For each incomplete dependency:
   - get_task(dependency_id)
   - Check status and complexity
4. Report: "T3 blocked by T2 (Redis setup). T2 is in_progress, complexity 6. Estimated 2 hours remaining."
```

## See Also

- **examples.md**: Concrete usage examples with real task data
- **routing-guide.md**: Detailed specialist routing patterns
- **Feature Management Skill**: For feature-level coordination
- **Dependency Analysis Skill**: For complex dependency scenarios
