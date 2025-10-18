# Hybrid Architecture Decision Guide

**A three-tier system combining Skills (coordination), Hooks (side effects), and Subagents (complex reasoning) for maximum efficiency**

---

## Table of Contents

- [Overview](#overview)
- [The Three Tiers Explained](#the-three-tiers-explained)
  - [Tier 1: Skills - Lightweight Coordination](#tier-1-skills---lightweight-coordination)
  - [Tier 2: Hooks - Zero-Token Side Effects](#tier-2-hooks---zero-token-side-effects)
  - [Tier 3: Subagents - Deep Reasoning](#tier-3-subagents---deep-reasoning)
- [Token Efficiency Comparison](#token-efficiency-comparison)
- [Decision Flowchart](#decision-flowchart)
- [When to Use Each Tier](#when-to-use-each-tier)
- [Integration Patterns](#integration-patterns)
- [Complete Workflow Examples](#complete-workflow-examples)
- [Migration Guide](#migration-guide)
- [Best Practices](#best-practices)
- [Common Pitfalls](#common-pitfalls)

---

## Overview

Task Orchestrator implements a **hybrid three-tier architecture** that matches the right tool to the right job:

```
┌─────────────────────────────────────────────────────────────────┐
│                    TIER 1: SKILLS                                │
│              Lightweight Coordination (300-600 tokens)           │
│                                                                   │
│  • Simple workflows (2-5 tool calls)                             │
│  • Task routing and status updates                               │
│  • Dependency checking                                           │
│  • 77% token reduction vs subagents                              │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    TIER 2: HOOKS                                 │
│                Zero-Token Side Effects (0 tokens)                │
│                                                                   │
│  • Git automation (commits, branches)                            │
│  • Test execution gates                                          │
│  • Notifications and logging                                     │
│  • 100% token reduction (no LLM calls)                           │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                   TIER 3: SUBAGENTS                              │
│            Deep Reasoning & Code Generation (1500-3000 tokens)   │
│                                                                   │
│  • Code implementation                                           │
│  • Architecture decisions                                        │
│  • Complex multi-step workflows                                  │
│  • Specialist expertise (Backend, Frontend, Database, etc.)      │
└─────────────────────────────────────────────────────────────────┘
```

**Key Innovation**: Use the lightest tier that solves the problem. Don't invoke a subagent when a Skill will do. Don't use a Skill when a Hook can handle it.

**Why This Matters**:
- **60-82% token savings** compared to subagent-only architecture
- **Faster response times** for simple operations
- **Clearer separation of concerns** (coordination vs side effects vs reasoning)
- **Better scalability** as work complexity grows

---

## The Three Tiers Explained

### Tier 1: Skills - Lightweight Coordination

**What Skills Are**: Focused AI behaviors that execute 2-5 tool calls to accomplish specific workflows.

**How They Work**:
- Activated by description keywords in Claude Code
- Run in-context (no separate agent launch)
- Execute predefined workflows efficiently
- Return results directly to orchestrator

**Token Cost**: 300-600 tokens per invocation (vs 1500-3000 for subagents)

**Architecture**:
```
Claude Code detects keywords
    ↓
Activates matching Skill
    ↓
Skill executes 2-5 MCP tool calls
    ↓
Returns result (brief summary)
    ↓
Orchestrator continues with context intact
```

**Example Skills**:
- **Task Management** - Route tasks, update status, complete tasks (300-600 tokens)
- **Feature Management** - Coordinate features, recommend next task (400-700 tokens)
- **Dependency Analysis** - Check dependencies, identify blockers (350-550 tokens)

**Key Characteristics**:
- ✅ No agent launching overhead
- ✅ Direct access to orchestrator context
- ✅ Fast execution (no context transfer)
- ✅ 77% cheaper than equivalent subagent
- ❌ Limited to coordination/simple logic
- ❌ Can't generate code or make complex decisions

**When to Use**:
- Task status changes (pending → in-progress → completed)
- Routing tasks to specialists (`recommend_agent`)
- Checking dependencies before starting work
- Creating task/feature summaries
- Simple data queries and updates

**Example Workflow**:
```
User: "Complete task T1"

Claude Code:
1. Activates Task Management Skill (keyword match)
2. Skill calls get_task(T1)
3. Skill calls add_section(Summary)
4. Skill calls set_status(T1, completed)
5. Skill returns: "Task T1 completed. Summary created."

Token Cost: ~450 tokens
Time: ~2 seconds
```

### Tier 2: Hooks - Zero-Token Side Effects

**What Hooks Are**: Bash scripts that execute automatically at specific trigger points (tool calls, session start, subagent completion).

**How They Work**:
- Configured in `.claude/settings.local.json`
- Execute when triggers fire (PostToolUse, PreToolUse, SessionStart, SubagentStop)
- Run outside Claude's context (no LLM calls)
- Can block operations or run in background

**Token Cost**: 0 tokens (no LLM involvement)

**Architecture**:
```
Claude executes MCP tool
    ↓
PostToolUse hook triggers
    ↓
Bash script reads tool input/output JSON
    ↓
Script executes side effect (git, test, notify)
    ↓
Script returns (optionally blocks operation)
    ↓
Claude continues (no context pollution)
```

**Example Hooks**:
- **Auto-Commit** - Create git commits when tasks complete (0 tokens)
- **Test Gate** - Run tests before allowing feature completion (0 tokens)
- **Template Reminder** - Suggest templates when creating tasks (0 tokens)
- **Session Context** - Load project overview at session start (0 tokens)

**Key Characteristics**:
- ✅ Zero token cost (bash scripts, no LLM)
- ✅ Completely transparent to orchestrator
- ✅ Can enforce quality gates (blocking hooks)
- ✅ Perfect for deterministic side effects
- ❌ No reasoning or decision-making
- ❌ Limited to scripted logic

**When to Use**:
- Git operations (commit, branch, tag)
- Test execution before completion
- Notifications (Slack, email, webhooks)
- Logging and metrics collection
- Data validation (blocking operations)

**Example Workflow**:
```
User: "Complete task T1"

Claude Code:
1. Calls set_status(T1, completed)
2. PostToolUse hook triggers
3. Hook extracts task ID from JSON
4. Hook queries database for task title
5. Hook creates git commit: "feat: Implement user login"
6. Hook returns success
7. Claude receives success (unaware of hook)

Token Cost: 0 tokens
Time: ~1 second (bash execution)
```

### Tier 3: Subagents - Deep Reasoning

**What Subagents Are**: Specialized AI agents with full context and conversation history for complex reasoning and code generation.

**How They Work**:
- Launched by orchestrator via Claude Code's agent system
- Start with clean context (task + dependencies)
- Perform complex multi-step work
- Return brief summaries to orchestrator

**Token Cost**: 1500-3000 tokens per invocation (includes task read, work, completion)

**Architecture**:
```
Orchestrator launches subagent
    ↓
Subagent receives fresh context (task + dependency summaries)
    ↓
Subagent performs complex reasoning/code generation
    ↓
Subagent updates task sections with results
    ↓
Subagent returns brief (2-3 sentences)
    ↓
Orchestrator stores brief (~200 tokens), discards full context
```

**Example Subagents**:
- **Backend Engineer** - Implement REST APIs, services, business logic (2000-3000 tokens)
- **Database Engineer** - Create schemas, migrations, query optimization (1500-2500 tokens)
- **Test Engineer** - Write comprehensive test suites (1800-2800 tokens)
- **Planning Specialist** - Requirements analysis, architecture decisions (2000-3500 tokens)

**Key Characteristics**:
- ✅ Full reasoning capabilities
- ✅ Code generation and file manipulation
- ✅ Multi-step workflows with backtracking
- ✅ Specialized expertise per domain
- ✅ Context isolation (97% orchestrator savings)
- ❌ Higher token cost per invocation
- ❌ Agent launching overhead
- ❌ Overkill for simple coordination

**When to Use**:
- Writing code (services, APIs, components)
- Creating database schemas and migrations
- Implementing complex business logic
- Writing comprehensive test suites
- Architecture and design decisions
- Requirements analysis and planning

**Example Workflow**:
```
User: "Implement user login API"

Orchestrator:
1. Launches Backend Engineer subagent
2. Passes task + dependency context

Backend Engineer (clean context):
1. Reads task (includeSections=true)
2. Implements UserController.kt
3. Implements AuthenticationService.kt
4. Writes unit tests
5. Updates task sections with implementation notes
6. Returns: "Implemented login API with JWT tokens. Files: UserController.kt, AuthenticationService.kt, tests."

Orchestrator:
1. Receives brief (200 tokens)
2. Stores brief, discards full subagent context
3. Continues with minimal context growth

Token Cost: ~2500 tokens (subagent execution)
Orchestrator Cost: ~200 tokens (brief only)
Savings: ~2300 tokens not added to orchestrator context
```

---

## Token Efficiency Comparison

### Single Task Workflow: "Complete Task T1"

**Scenario**: Mark a task complete and create a summary section.

| Approach | Token Cost | Breakdown | Orchestrator Context Growth |
|----------|------------|-----------|------------------------------|
| **Subagent-Only** | 2000 tokens | Read task (1000) + Work (500) + Summary (500) | +2000 tokens (full context) |
| **Skill** | 450 tokens | Read task (200) + add_section (150) + set_status (100) | +450 tokens (in-context) |
| **Hybrid (Skill + Hook)** | 450 tokens | Skill coordination + Hook auto-commit (0 tokens) | +450 tokens |
| **Savings vs Subagent** | 77% | - | 77% reduction |

### Feature Completion Workflow: "8 Tasks in Sequence"

**Scenario**: Complete 8 sequential tasks with dependencies.

| Approach | Total Tokens | Per-Task Cost | Orchestrator Growth |
|----------|--------------|---------------|---------------------|
| **Subagent-Only** | 21,000 tokens | 2500 tokens/task × 8 + coordination | +21,000 tokens |
| **Hybrid (Skills + Subagents)** | 12,600 tokens | Skills (3600) + Subagents (9000) | +3,800 tokens |
| **Savings** | 40% | - | 82% reduction in orchestrator |

**Breakdown (Hybrid)**:
- 8× Task Management Skill (complete tasks): 450 × 8 = 3,600 tokens
- 8× Subagent implementations: 1500 × 6 = 9,000 tokens (only 6 need code)
- Orchestrator context: 8 × 200 = 1,600 tokens (briefs) + 2,200 (coordination) = 3,800 tokens

### Complex Feature: "API + Database + Frontend + Tests"

**Scenario**: 12-task feature with backend, database, frontend, and testing work.

| Approach | Total Tokens | Orchestrator Context | Time Estimate |
|----------|--------------|----------------------|---------------|
| **Subagent-Only** | 36,000 tokens | +36,000 tokens | ~15 minutes |
| **Skills-Only** | N/A | Limited - can't generate code | Impossible |
| **Hooks-Only** | N/A | Limited - no reasoning | Impossible |
| **Hybrid** | 21,000 tokens | +4,800 tokens | ~12 minutes |
| **Savings** | 42% | 87% reduction | 20% faster |

**Hybrid Breakdown**:
- Skills coordination: 12 × 450 = 5,400 tokens
- Hooks (git, tests): 0 tokens
- Subagents (9 implementation tasks): 9 × 1800 = 16,200 tokens
- Orchestrator: 12 × 200 + 2,400 = 4,800 tokens

---

## Decision Flowchart

Use this flowchart to choose the right tier:

```
Is this a side effect that can be scripted?
(git commit, run tests, send notification, log metric)
    │
    ├─ YES ──→ USE HOOK (Tier 2)
    │           • 0 tokens
    │           • Fully automated
    │           • Example: Auto-commit on task completion
    │
    └─ NO
        │
        Is this simple coordination?
        (2-5 tool calls, no complex reasoning, no code generation)
            │
            ├─ YES ──→ USE SKILL (Tier 1)
            │           • 300-600 tokens
            │           • Fast execution
            │           • Example: Route task to specialist
            │
            └─ NO
                │
                Does this require reasoning or code generation?
                (implement features, architecture decisions, complex planning)
                    │
                    └─ YES ──→ USE SUBAGENT (Tier 3)
                                • 1500-3000 tokens
                                • Full capabilities
                                • Example: Implement REST API
```

---

## When to Use Each Tier

### Use Skills When...

✅ **Task Status Management**:
- Completing tasks (read → add_section → set_status)
- Updating task progress
- Checking task dependencies

✅ **Task Routing**:
- Calling `recommend_agent` to identify specialist
- Preparing task context for subagent
- Passing dependency summaries

✅ **Feature Coordination**:
- Recommending next task in feature
- Tracking feature progress
- Summarizing feature completion

✅ **Dependency Analysis**:
- Checking for incomplete dependencies
- Identifying blockers
- Validating dependency chains

✅ **Simple Queries**:
- Getting task details
- Listing templates
- Checking project status

**Pattern**: If you can describe it as "Call tool A, then tool B, then tool C" → Use Skill

### Use Hooks When...

✅ **Git Automation**:
- Auto-commit when tasks complete
- Create branches for features
- Tag releases
- Push to remote

✅ **Quality Gates**:
- Run tests before allowing completion
- Check code coverage thresholds
- Validate commit messages
- Enforce coding standards

✅ **Notifications**:
- Send Slack/Discord messages
- Email on critical events
- Webhook integrations
- Update external dashboards

✅ **Logging & Metrics**:
- Track task completion times
- Log specialist usage
- Record test results
- Generate analytics data

✅ **Workflow Automation**:
- Load context at session start
- Remind about template discovery
- Auto-assign tasks based on tags
- Update external systems (Jira, GitHub)

**Pattern**: If you can describe it as "When X happens, automatically do Y" → Use Hook

### Use Subagents When...

✅ **Code Implementation**:
- Writing services, APIs, controllers
- Implementing business logic
- Creating UI components
- Building integrations

✅ **Database Work**:
- Creating schemas and migrations
- Writing complex queries
- Optimizing database performance
- Designing data models

✅ **Testing**:
- Writing comprehensive test suites
- Creating integration tests
- Implementing test automation
- Setting up test infrastructure

✅ **Documentation**:
- Writing API documentation
- Creating user guides
- Documenting architecture
- Generating README files

✅ **Architecture & Planning**:
- Requirements analysis
- System design decisions
- Breaking down complex features
- Evaluating implementation approaches

**Pattern**: If you need to reason, make decisions, or generate code → Use Subagent

---

## Integration Patterns

### Pattern 1: Skill → Hook (Task Completion)

**Workflow**: Skill completes task, Hook auto-commits

```
User: "Complete task T1"

Skill (Task Management):
1. get_task(T1, includeSections=true)
2. add_section(entityType=TASK, title="Summary", content="...")
3. set_status(T1, completed)
4. Return: "Task T1 completed. Summary created."

Hook (PostToolUse on set_status):
1. Triggers when status="completed"
2. Extracts task ID from JSON
3. Queries database for task title
4. Creates git commit with task info
5. Returns success silently

Result:
- Task completed (Skill)
- Summary section created (Skill)
- Git commit created (Hook)
- Total tokens: 450 (Hook = 0)
```

**Benefits**:
- Skill handles coordination efficiently
- Hook adds zero-token side effect
- User gets both without extra cost

### Pattern 2: Skill → Subagent (Task Routing)

**Workflow**: Skill routes task, Subagent implements

```
User: "Work on task T1"

Skill (Task Management START):
1. get_task(T1, includeSections=true)
2. recommend_agent(taskId=T1)
3. get_task_dependencies(T1) → reads dependency summaries
4. Return: "Recommend Backend Engineer. Task: Implement login API. Dependencies: Database schema (completed)."

Orchestrator:
1. Receives Skill recommendation
2. Launches Backend Engineer subagent
3. Passes task + dependency context

Subagent (Backend Engineer):
1. Reads task (fresh context)
2. Implements code
3. Returns brief: "Implemented login API..."

Result:
- Efficient routing (Skill: 500 tokens)
- Deep implementation (Subagent: 2000 tokens)
- Total: 2500 tokens vs 4000 for subagent-only
```

**Benefits**:
- Skill preparation reduces subagent context needs
- Dependency context passed efficiently
- Orchestrator receives only brief

### Pattern 3: Skill + Hook + Subagent (Complete Flow)

**Workflow**: Full task lifecycle with all three tiers

```
User: "Implement and complete task T1"

Step 1: Route Task (Skill)
Task Management Skill START:
- Reads task, calls recommend_agent
- Returns: "Backend Engineer recommended"
Cost: 500 tokens

Step 2: Implement (Subagent)
Backend Engineer:
- Receives task + dependency context
- Writes code, updates sections
- Returns brief
Cost: 2000 tokens

Step 3: Complete Task (Skill)
Task Management Skill END:
- Extracts specialist output
- Creates Summary section
- Sets status = completed
Cost: 450 tokens

Step 4: Auto-Commit (Hook)
PostToolUse Hook:
- Detects status=completed
- Creates git commit automatically
Cost: 0 tokens

Total: 2950 tokens (vs 5000 for subagent-only approach)
Savings: 41%
```

**Benefits**:
- Each tier does what it does best
- Minimal orchestrator context growth (~200 tokens)
- Automated git integration
- Maximum efficiency

### Pattern 4: Hook → Subagent (Quality Gate)

**Workflow**: Hook blocks operation until quality criteria met

```
User: "Complete feature F1"

Orchestrator:
1. Calls update_feature(F1, status=completed)

Hook (PostToolUse Quality Gate):
1. Detects feature completion attempt
2. Runs test suite: ./gradlew test
3. Tests fail
4. Returns blocking decision:
   {
     "decision": "block",
     "reason": "Tests are failing. Fix 3 failing tests before completing feature."
   }

Orchestrator:
1. Receives block
2. Shows user: "Cannot complete feature - tests failing"
3. Suggests: "Launch Test Engineer to fix tests"

User: "Fix the tests"

Orchestrator:
1. Launches Test Engineer subagent
2. Subagent fixes tests
3. Returns brief

User: "Complete feature F1" (retry)

Hook:
1. Runs tests again
2. Tests pass
3. Returns success (no block)

Feature marked complete
```

**Benefits**:
- Hook enforces quality without tokens
- Subagent only invoked when needed (fixing tests)
- Prevents incomplete work from being marked complete

---

## Complete Workflow Examples

### Example 1: Simple Task Completion (Skill + Hook)

**Scenario**: User completes a task that doesn't need code changes.

```
User: "Complete task T1: Update README with new features"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 1: SKILL (Task Management)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. get_task(T1, includeSections=true)
   → Task: "Update README with new features"
   → Status: in_progress
   → Sections: Requirements, Documentation

2. add_section(
     entityType=TASK,
     entityId=T1,
     title="Summary",
     content="Updated README.md with new features section including skill system and hooks documentation.",
     ordinal=999
   )

3. set_status(T1, status="completed")

Return: "Task T1 completed. README updated with new features documentation."

Cost: 450 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 2: HOOK (Auto-Commit on set_status)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook Triggered:
1. Detects: tool_name = set_status, status = "completed"
2. Extracts: task_id = T1
3. Queries: SELECT title FROM Tasks WHERE id='T1'
   → "Update README with new features"
4. Executes:
   cd $CLAUDE_PROJECT_DIR
   git add README.md
   git commit -m "docs: Update README with new features" \
              -m "Task-ID: T1"
5. Returns: "✓ Hook completed successfully"

Cost: 0 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RESULT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Task marked complete
✅ Summary section created
✅ Git commit created automatically
✅ Total tokens: 450 (Hook = 0)
✅ Orchestrator context growth: +450 tokens
```

### Example 2: Code Implementation Task (Skill + Subagent + Hook)

**Scenario**: User needs to implement a new API endpoint.

```
User: "Implement task T2: Create user login endpoint"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 1: SKILL (Task Management START)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. get_task(T2, includeSections=true)
   → Task: "Create user login endpoint"
   → Tags: ["backend", "api", "authentication"]
   → Dependencies: T1 (Database schema - completed)

2. recommend_agent(taskId=T2)
   → Agent: "Backend Engineer"
   → Reason: "Task tags match backend category (backend, api)"
   → SectionTags: ["requirements", "technical-approach", "implementation"]

3. get_task_dependencies(T2, direction=incoming)
   → Found: T1 (completed)

4. get_sections(entityType=TASK, entityId=T1, tags="summary")
   → T1 Summary: "Created Users table with id, username, email, password_hash..."

Return: "Recommend Backend Engineer for task T2. Dependencies: T1 (Database schema completed). Focus: requirements, technical-approach, implementation."

Cost: 500 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 3: SUBAGENT (Backend Engineer)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Orchestrator launches Backend Engineer with:
- Task T2 details
- Dependency T1 Summary (400 tokens)

Backend Engineer (clean context):
1. get_task(T2, includeSections=true)
2. Reads T1 Summary from orchestrator brief
3. Implements:
   - UserController.kt (login endpoint)
   - AuthenticationService.kt (JWT token generation)
   - UserControllerTest.kt (unit tests)
4. Updates task sections with implementation notes
5. Returns: "Implemented login endpoint with JWT authentication. Files: UserController.kt, AuthenticationService.kt, tests. Ready for integration."

Cost: 2000 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 1: SKILL (Task Management END)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Orchestrator calls Task Management Skill with specialist output.

1. Extracts from Backend Engineer output:
   - Completed: Login endpoint implementation
   - Files: UserController.kt, AuthenticationService.kt, tests
   - Next: Integration testing

2. add_section(
     entityType=TASK,
     entityId=T2,
     title="Summary",
     content="### Completed\nImplemented user login endpoint...",
     ordinal=999
   )

3. set_status(T2, status="completed")

Return: "Task T2 completed. Login endpoint implemented in UserController.kt with JWT authentication."

Cost: 450 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 2: HOOK (Auto-Commit)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook:
1. Detects: status = "completed"
2. Creates commit:
   git commit -m "feat: Create user login endpoint" \
              -m "Task-ID: T2"

Cost: 0 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RESULT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Task routed efficiently (Skill: 500 tokens)
✅ Code implemented (Subagent: 2000 tokens)
✅ Task completed and summarized (Skill: 450 tokens)
✅ Git commit created (Hook: 0 tokens)
✅ Total tokens: 2950
✅ Orchestrator context growth: +650 tokens (briefs only)

vs Subagent-only approach:
❌ Total tokens: 5000
❌ Orchestrator context growth: +5000 tokens
```

### Example 3: Feature with Quality Gate (All Tiers)

**Scenario**: Complete a feature with automated testing enforcement.

```
User: "Complete feature F1: User Authentication"

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 1: SKILL (Feature Management START)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. get_feature(F1, includeTasks=true, includeTaskCounts=true)
   → Feature: "User Authentication"
   → Status: in-development
   → Tasks: 8 total, 8 completed

2. get_next_task(featureId=F1)
   → No incomplete tasks

Return: "All 8 tasks in feature F1 complete. Ready to close feature."

Cost: 600 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ORCHESTRATOR ATTEMPTS COMPLETION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Orchestrator calls: update_feature(F1, status="completed")

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 2: HOOK (Quality Gate - BLOCKS)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook (Feature Completion Gate):
1. Detects: feature completion attempt
2. Runs tests: cd $CLAUDE_PROJECT_DIR && ./gradlew test
3. Tests fail (3 failures in AuthenticationServiceTest)
4. Returns BLOCK:
   {
     "decision": "block",
     "reason": "Tests are failing. Fix 3 failing tests in AuthenticationServiceTest before completing feature."
   }

Cost: 0 tokens

Orchestrator receives block, shows user error.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
USER FIXES TESTS
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

User: "Launch Test Engineer to fix the failing tests"

Orchestrator launches Test Engineer subagent.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 3: SUBAGENT (Test Engineer)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Test Engineer:
1. Reads test failures
2. Fixes AuthenticationServiceTest.kt
3. Runs tests: ./gradlew test
4. All tests pass
5. Returns: "Fixed 3 failing tests in AuthenticationServiceTest. All tests now passing."

Cost: 1800 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
ORCHESTRATOR RETRIES COMPLETION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

User: "Complete feature F1" (retry)

Orchestrator calls: update_feature(F1, status="completed")

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 2: HOOK (Quality Gate - PASSES)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook:
1. Runs tests: ./gradlew test
2. All tests pass
3. Returns success (no block)

Cost: 0 tokens

Feature marked complete!

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TIER 1: SKILL (Feature Management END)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Feature Management Skill:
1. get_feature(F1, includeTasks=true)
2. Reads task summaries
3. Creates feature Summary section
4. Returns: "Feature F1 completed. Implemented OAuth 2.0 authentication with 8 tasks. All tests passing."

Cost: 700 tokens

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
RESULT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Quality gate enforced without tokens (Hook: 0 tokens)
✅ Tests fixed by specialist (Subagent: 1800 tokens)
✅ Feature completed with validation (Skill: 700 tokens)
✅ Total tokens: 3100
✅ Quality ensured automatically
```

---

## Migration Guide

### From Subagent-Only to Hybrid Architecture

If you're currently using only subagents, here's how to migrate:

#### Step 1: Identify Coordination Workflows

**Look for subagent invocations that only do coordination:**

❌ **Before (Subagent-only)**:
```
User: "Complete task T1"

Orchestrator launches Task Manager subagent

Task Manager:
1. get_task(T1)
2. add_section(Summary)
3. set_status(completed)
4. Returns brief

Cost: 1500 tokens
```

✅ **After (Hybrid with Skill)**:
```
User: "Complete task T1"

Task Management Skill:
1. get_task(T1)
2. add_section(Summary)
3. set_status(completed)
4. Returns brief

Cost: 450 tokens
Savings: 70%
```

**Migration Action**:
- Install Task Management Skill
- Use Skill for task completion instead of Task Manager subagent
- Reserve Task Manager subagent for START mode (routing)

#### Step 2: Extract Side Effects to Hooks

**Look for subagent work that's scripted/deterministic:**

❌ **Before (Subagent doing git)**:
```
Backend Engineer:
1. Implements code
2. Creates git commit
3. Runs tests
4. Returns brief
```

✅ **After (Subagent + Hooks)**:
```
Backend Engineer:
1. Implements code
2. Returns brief

Hook (auto-commit):
1. Creates git commit automatically

Hook (test runner):
1. Runs tests automatically

Savings: 200-300 tokens per task (git + test logic removed from subagent)
```

**Migration Action**:
- Create auto-commit hook (PostToolUse on set_status)
- Create test runner hook (PreToolUse or PostToolUse)
- Remove git/test logic from subagent instructions

#### Step 3: Optimize Task Routing

**Current routing pattern (subagent-heavy):**

❌ **Before**:
```
Orchestrator → Task Manager START → Returns recommendation
Orchestrator → Specialist → Does work
Orchestrator → Task Manager END → Creates summary

Cost: 1500 + 2000 + 1500 = 5000 tokens
```

✅ **After (Skill optimization)**:
```
Task Management Skill START → Returns recommendation
Orchestrator → Specialist → Does work
Task Management Skill END → Creates summary
Hook → Auto-commits

Cost: 500 + 2000 + 450 + 0 = 2950 tokens
Savings: 41%
```

**Migration Action**:
- Use Task Management Skill for START/END modes
- Keep subagent routing for complex cases only
- Add auto-commit hook

#### Step 4: Feature-Level Optimization

❌ **Before (Feature Manager subagent for every check)**:
```
User: "What's next?"
Orchestrator → Feature Manager → Recommends task
Cost: 1500 tokens per check
```

✅ **After (Feature Management Skill)**:
```
User: "What's next?"
Feature Management Skill → Recommends task
Cost: 600 tokens per check
Savings: 60%
```

**Migration Action**:
- Install Feature Management Skill
- Use for task recommendations
- Reserve Feature Manager subagent for END mode only

#### Migration Checklist

- [ ] Install Task Management Skill
- [ ] Install Feature Management Skill
- [ ] Install Dependency Analysis Skill (if needed)
- [ ] Create auto-commit hook
- [ ] Create test execution hook
- [ ] Create template reminder hook
- [ ] Update workflow to use Skills first
- [ ] Reserve subagents for implementation only
- [ ] Measure token savings

**Expected Results**:
- 40-60% token reduction for feature workflows
- 70-80% reduction in orchestrator context growth
- Faster response times for coordination tasks
- Automated git/test workflows

---

## Best Practices

### 1. Use the Lightest Tier Possible

**Decision Priority**:
1. Can a Hook do it? → Use Hook (0 tokens)
2. Can a Skill do it? → Use Skill (300-600 tokens)
3. Need reasoning/code? → Use Subagent (1500-3000 tokens)

**Example**:
- ❌ Don't use subagent to complete tasks
- ✅ Use Skill to complete, Hook to commit

### 2. Combine Tiers for Maximum Efficiency

**Pattern**: Skill coordination + Hook side effects + Subagent implementation

```
Task lifecycle:
1. Skill START → Routes task (500 tokens)
2. Subagent → Implements code (2000 tokens)
3. Skill END → Completes task (450 tokens)
4. Hook → Auto-commits (0 tokens)

Total: 2950 tokens vs 5000+ subagent-only
```

### 3. Use Skills for Repeated Operations

**If you do it more than twice, create a Skill**:

❌ **Repeated subagent calls**:
```
10× task completions with Task Manager subagent
Cost: 10 × 1500 = 15,000 tokens
```

✅ **Skill for repeated work**:
```
10× task completions with Task Management Skill
Cost: 10 × 450 = 4,500 tokens
Savings: 70%
```

### 4. Hooks for Deterministic Workflows

**If it's scriptable, it's a Hook**:

✅ **Good Hook candidates**:
- Git operations (commit, branch, tag)
- Test execution
- Notifications (Slack, email)
- Metrics logging

❌ **Bad Hook candidates**:
- Code generation (needs reasoning)
- Architecture decisions (needs expertise)
- Complex analysis (needs LLM)

### 5. Subagents for Deep Work Only

**Reserve subagents for what they do best**:

✅ **Use subagents for**:
- Writing code
- Creating schemas
- Implementing complex logic
- Architecture decisions

❌ **Don't use subagents for**:
- Task status updates
- Dependency checking
- Simple queries
- Git commits

### 6. Measure and Optimize

**Track your token usage**:

```
Before hybrid architecture:
- Feature completion: 35,000 tokens
- Orchestrator context: +35,000 tokens

After hybrid architecture:
- Feature completion: 21,000 tokens
- Orchestrator context: +4,800 tokens

Savings: 40% total, 86% orchestrator
```

**Optimization targets**:
- 50%+ total token reduction
- 80%+ orchestrator context reduction
- 30%+ faster workflow execution

---

## Common Pitfalls

### Pitfall 1: Using Subagents for Simple Coordination

❌ **Anti-pattern**:
```
User: "Mark task T1 complete"
Orchestrator launches Task Manager subagent
Cost: 1500 tokens
```

✅ **Correct approach**:
```
User: "Mark task T1 complete"
Task Management Skill executes
Cost: 450 tokens
```

**Why**: Subagents are overkill for 3-tool-call workflows.

### Pitfall 2: Duplicating Side Effects

❌ **Anti-pattern**:
```
Backend Engineer subagent:
1. Implements code
2. Creates git commit (included in instructions)
3. Returns

Hook also creates git commit

Result: Duplicate commits!
```

✅ **Correct approach**:
```
Backend Engineer:
1. Implements code
2. Returns

Hook creates single git commit

Result: One commit, automated
```

**Why**: Hooks handle side effects. Remove them from subagent instructions.

### Pitfall 3: Over-Engineering with Skills

❌ **Anti-pattern**:
```
Creating a Skill for "get_task"
→ Skill just wraps a single tool call
→ No value added
```

✅ **Correct approach**:
```
Call get_task directly
→ No Skill needed
→ Skills are for 2+ tool workflows
```

**Why**: Skills add value when coordinating multiple tools, not wrapping single calls.

### Pitfall 4: Forgetting Hook Limitations

❌ **Anti-pattern**:
```
Hook tries to make architectural decision about code structure
→ Hooks can't reason
→ Fails or makes wrong choice
```

✅ **Correct approach**:
```
Hook detects condition, reports to orchestrator
Orchestrator launches Planning Specialist if decision needed
Planning Specialist makes architectural choice
```

**Why**: Hooks are scripted logic only. Complex decisions need LLMs.

### Pitfall 5: Not Measuring Token Usage

❌ **Anti-pattern**:
```
Assuming hybrid is always better
Not tracking actual token costs
Missing optimization opportunities
```

✅ **Correct approach**:
```
Track token usage before/after
Measure orchestrator context growth
Identify bottlenecks
Optimize highest-cost operations first
```

**Why**: Data-driven optimization finds the biggest wins.

---

## Summary

**Hybrid Architecture = Right Tool for Right Job**

| Tier | Purpose | Token Cost | When to Use |
|------|---------|------------|-------------|
| **Skills** | Coordination | 300-600 | Task routing, status updates, dependency checks (2-5 tools) |
| **Hooks** | Side Effects | 0 | Git automation, test gates, notifications, logging |
| **Subagents** | Deep Work | 1500-3000 | Code generation, architecture, complex reasoning |

**Key Benefits**:
- 40-60% total token reduction
- 80-86% orchestrator context reduction
- Faster execution for coordination
- Better separation of concerns
- Scales to larger projects

**Migration Path**:
1. Identify coordination workflows → Convert to Skills
2. Extract side effects → Move to Hooks
3. Reserve subagents for implementation only
4. Measure and optimize

**Decision Rule**:
```
Can script it? → Hook
Can coordinate (2-5 tools)? → Skill
Need reasoning/code? → Subagent
```

Ready to implement hybrid architecture? See:
- [Skill Builder](../src/main/resources/skills/skill-builder/SKILL.md) - Create custom Skills
- [Hook Builder](../src/main/resources/skills/hook-builder/SKILL.md) - Create custom Hooks
- [Agent Orchestration](agent-orchestration.md) - Subagent system guide
