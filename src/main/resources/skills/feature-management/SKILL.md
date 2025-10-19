---
name: Feature Management
description: Coordinate feature workflows including recommending next tasks, checking progress, and completing features. Use when managing feature lifecycle, checking feature status, or marking features complete.
allowed-tools: mcp__task-orchestrator__query_container, mcp__task-orchestrator__query_tasks, mcp__task-orchestrator__manage_container, mcp__task-orchestrator__manage_sections
---

# Feature Management Skill

You coordinate feature lifecycle management using lightweight, focused tool sequences.

## When to Use This Skill

**Use when you need to:**
- Recommend the next task to work on in a feature
- Check feature progress and task status
- Complete a feature after all tasks are done
- List blocked tasks in a feature
- Update feature status or summary

**Don't use for:**
- Complex feature planning (use Planning Specialist subagent)
- Implementing tasks (use specialist subagents like Backend Engineer)
- Creating new features (use Feature Architect subagent)

## Core Workflows

### 1. Recommend Next Task

**Purpose**: Find the next unblocked task to work on in a feature.

**Steps**:
```
Step 1: Get feature with task counts
  query_container(operation='get', containerType='feature', id='[feature-id]')

Step 2: Get next recommended task
  query_tasks(queryType='next', featureId='[feature-id]', limit=1, includeDetails=true)

Step 3 (optional): Update feature status if starting work
  If feature status is 'planning' and recommending first task:
    manage_container(operation='update', containerType='feature', id='[feature-id]', status='in-development')

Step 4: Return recommendation
  Format: Clear task recommendation with ID, title, priority, complexity, why it's ready
```

**Response Format**:
```
Feature: [feature name]
Progress: [completed]/[total] tasks completed
Status: [feature status]

Recommended Task:
- Title: [task title]
- Task ID: [id]
- Priority: [priority] | Complexity: [complexity]
- Reason: [why ready - e.g., "unblocked, high priority, no dependencies"]
- Context: [1 sentence: what this task does]

Next: Work on this task or launch appropriate specialist subagent.
```

**If no tasks available**:
```
Feature: [feature name]
Progress: [completed]/[total] tasks completed

All tasks are either:
- Completed: [count]
- In Progress: [count]
- Blocked: [count]

Next: [Wait for in-progress tasks OR resolve blockers OR complete feature if done]
```

### 2. Check Feature Progress

**Purpose**: Get current status of feature and all its tasks.

**Steps**:
```
Step 1: Get feature with task counts (99% token reduction vs fetching all tasks)
  query_container(operation='get', containerType='feature', id='[feature-id]')

Step 2: Analyze taskCounts
  - Review taskCounts.byStatus (pending, in-progress, completed, cancelled)
  - Calculate completion percentage from counts
  - Identify any blockers using counts

Token Optimization:
  Old approach: Fetch all 50 tasks = ~14,400 tokens
  New approach: Get feature with taskCounts = ~100 tokens
  Savings: 99% (14,300 tokens saved!)
```

**Response Format**:
```
Feature: [feature name]
Status: [feature status]
Progress: [completed]/[total] tasks ([percentage]%)

Task Breakdown (from taskCounts):
- Completed: [count]
- In Progress: [count]
- Pending: [count]
- Blocked: [count] (if any)
- Cancelled: [count] (if any)

[If tasks blocked]: Use "List Blocked Tasks" workflow to investigate blockers.
[If all complete]: Feature ready for completion workflow.
```

### 3. Complete Feature

**Purpose**: Mark feature complete after all tasks are done and tests pass.

**Prerequisites**:
- All tasks in feature are completed
- All implementation tasks report passing tests
- Feature work is validated

**Steps**:
```
Step 1: Get feature with all tasks and sections
  get_feature(id='[feature-id]', includeTasks=true, includeSections=true, includeTaskCounts=true)

Step 2: Verify completion readiness
  - Check all tasks have status='completed'
  - Verify implementation task summaries report passing tests
  - Confirm no critical work is missing

Step 3: QUALITY GATE - Verify Tests (MANDATORY)
  For EACH implementation task (Backend, Frontend, Database), check summary for:
  - "tests passing" OR "tests passed" OR "[X] tests passing"
  - "build successful" OR "build passed"
  - Specific test counts (e.g., "42 unit tests passing")

  If ANY task has failing tests:
    ❌ ABORT - Return error: "Cannot complete feature - Task '[title]' has failing tests"
    ❌ DO NOT create Summary section
    ❌ DO NOT mark feature complete

  If ANY task missing test info (WARN but can proceed):
    ⚠️ Include warning in summary
    ⚠️ Note: "Task '[title]' completed without test verification"

Step 4: Create feature Summary section (detailed)
  add_section(
    entityType='FEATURE',
    entityId='[feature-id]',
    title='Summary',
    usageDescription='Summary of completed feature work including tasks, files, and technical decisions',
    content='[markdown content - see format below]',
    contentFormat='MARKDOWN',
    ordinal=999,
    tags='summary,completion'
  )

Step 5: Update feature summary field (brief)
  update_feature(
    id='[feature-id]',
    summary='[2-3 sentence outcome, max 500 chars]'
  )

Step 6: Mark feature complete
  update_feature(id='[feature-id]', status='completed')
```

**Summary Section Content Format**:
```markdown
### What Was Built
[2-3 sentences describing the feature outcome and value delivered]

### Tasks Completed
1. **[Task 1 title]** - [1 sentence: what it did]
2. **[Task 2 title]** - [1 sentence: what it did]
3. **[Task 3 title]** - [1 sentence: what it did]

### Files Changed
- `path/to/file1.kt` - [what changed]
- `path/to/file2.kt` - [what changed]
- `path/to/file3.sql` - [what changed]

### Technical Decisions
- [Key decision 1 and rationale]
- [Key decision 2 and rationale]
- [Architecture pattern or design choice]

### Testing
[Brief overview of test coverage - unit tests, integration tests, pass/fail counts]

### Integration Points
[What systems/components this feature integrates with]

### Next Steps
[Any follow-up work, related features, or technical debt noted]
```

**Response Format**:
```
Feature: [feature name]
Status: Completed ✓
Tasks: [total] completed
Summary section created

[2-3 sentence summary of what was delivered and its impact]
```

### 4. List Blocked Tasks

**Purpose**: Find all tasks in feature that are blocked by dependencies.

**Steps**:
```
Step 1: Get blocked tasks
  get_blocked_tasks(featureId='[feature-id]')

Step 2: Analyze blockers
  - Group by blocking dependency
  - Identify critical path blockers (blocking multiple tasks)
  - Suggest resolution order
```

**Response Format**:
```
Feature: [feature name]
Blocked Tasks: [count]

Blockers:
1. [Task title]
   Blocked by: [dependency task title] ([dependency status])
   Impact: [how many other tasks this affects]
   Resolution: [what needs to happen]

2. [Task title]
   Blocked by: [dependency task title] ([dependency status])
   Impact: [how many other tasks this affects]
   Resolution: [what needs to happen]

Recommended Action: [prioritize which dependency to resolve first]
```

## Key Principles

**Lightweight Coordination**:
- 2-5 tool calls per operation
- No complex reasoning or code generation
- Focus on orchestration, not implementation

**Trust the Tools**:
- `get_next_task` automatically filters blocked tasks
- Don't manually implement dependency logic
- Task status is source of truth

**Clear Communication**:
- Brief, actionable recommendations
- Always include Task IDs for reference
- Explain why tasks are ready or blocked

**Quality Gates**:
- Feature completion requires passing tests
- Verify all implementation work before marking complete
- Document warnings for missing test info

## Token Efficiency

**This Skill saves 60-82% tokens vs Feature Manager subagent**:
- Feature Manager START: ~1400 tokens → Skill: ~300 tokens (78% reduction)
- Feature Manager END: ~1700 tokens → Skill: ~600 tokens (65% reduction)

**How**:
- No subagent launch overhead
- Direct tool invocation
- Focused context (no system prompt duplication)

## See Also

- **examples.md** - Concrete usage examples for each workflow
- **reference.md** - Detailed MCP tool reference and parameters
- **Task Management Skill** - For completing individual tasks
- **Dependency Analysis Skill** - For deep dependency investigation
