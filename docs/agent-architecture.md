# AI Agent Architecture Guide

**A hybrid 4-tier system combining Direct Tools, Skills, Hooks, and Subagents for scalable, context-efficient AI workflows**

---

## Table of Contents

- [Overview](#overview)
- [The Four Tiers Explained](#the-four-tiers-explained)
  - [Tier 1: Direct Tools](#tier-1-direct-tools)
  - [Tier 2: Skills](#tier-2-skills)
  - [Tier 3: Hooks](#tier-3-hooks)
  - [Tier 4: Subagents](#tier-4-subagents)
- [Decision Guide](#decision-guide)
- [Token Efficiency Comparison](#token-efficiency-comparison)
- [Specialist Agents](#specialist-agents)
- [Agent Mapping Configuration](#agent-mapping-configuration)
- [Integration Patterns](#integration-patterns)
- [Complete Workflow Examples](#complete-workflow-examples)
- [Setup and Configuration](#setup-and-configuration)
- [Migration Guide](#migration-guide)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Overview

Task Orchestrator implements a **hybrid 4-tier architecture** that matches the right tool to the right job:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                       TIER 1: DIRECT TOOLS                               │
│                      (Single MCP Tool Calls)                             │
│                                                                           │
│  • Atomic operations (create_task, set_status, add_section)              │
│  • No coordination overhead                                              │
│  • Token cost: ~100-200 per call                                         │
└─────────────────────────────────────────────────────────────────────────┘
                                      ↕
┌─────────────────────────────────────────────────────────────────────────┐
│                         TIER 2: SKILLS                                   │
│                   (Lightweight Coordination - 2-5 tool calls)            │
│                                                                           │
│  • Repetitive coordination workflows                                     │
│  • Task routing, status updates, dependency checks                       │
│  • Token cost: ~300-600 (60-82% savings vs subagents)                    │
└─────────────────────────────────────────────────────────────────────────┘
                                      ↕
┌─────────────────────────────────────────────────────────────────────────┐
│                        TIER 3: HOOKS                                     │
│                  (Zero-Token Side Effects - 0 tokens)                    │
│                                                                           │
│  • Git automation (commits, branches)                                    │
│  • Test execution gates                                                  │
│  • Notifications and logging                                             │
│  • 100% token reduction (bash scripts, no LLM)                           │
└─────────────────────────────────────────────────────────────────────────┘
                                      ↕
┌─────────────────────────────────────────────────────────────────────────┐
│                       TIER 4: SUBAGENTS                                  │
│              (Deep Reasoning & Code Generation - 1800-2200 tokens)       │
│                                                                           │
│  • Code implementation (Backend, Frontend, Database)                     │
│  • Architecture decisions (Planning Specialist, Feature Architect)       │
│  • Complex multi-step workflows with specialist expertise                │
│  • Self-service pattern: read own context (220 token handoff)            │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Innovation**: Use the lightest tier that solves the problem. Don't invoke a subagent when a Skill will do. Don't use a Skill when a Direct Tool or Hook can handle it. When using subagents, launch them directly with minimal context (UUID only) and let them read their own data.

**Why This Matters**:
- **58-62% total token savings** compared to old 3-hop middleware pattern
- **98% orchestrator context reduction** (specialists return briefs only)
- **90% routing overhead reduction** (direct specialist launch eliminates middleware)
- **Faster response times** for simple operations (Direct Tools, Skills) and complex work (direct specialists)
- **Clearer separation of concerns** (atomic ops vs coordination vs side effects vs reasoning)
- **Better scalability** as work complexity grows

---

## The Four Tiers Explained

### Tier 1: Direct Tools

**What Direct Tools Are**: Single MCP tool calls for atomic operations.

**Token Cost**: ~100-200 tokens per call

**When to Use**:
- Update ONE field/status
- Query single entity
- Create/delete single resource
- No coordination needed

**Examples**:
```javascript
// Update task status
manage_container(operation='setStatus', containerType='task', id='task-uuid', status='completed')  // ~150 tokens

// Create new task
manage_container(operation='create', containerType='task', title='...', featureId='...')  // ~200 tokens

// Add section
manage_sections(operation='add', entityType='TASK', title='...', content='...')  // ~180 tokens
```

**Key Characteristics**:
- ✅ Fastest execution (no overhead)
- ✅ Lowest token cost
- ✅ Direct database operations
- ❌ No coordination logic
- ❌ No multi-step workflows

### Tier 2: Skills

**What Skills Are**: Focused AI behaviors that execute 2-5 tool calls to accomplish specific workflows.

**How They Work**:
- Activated by description keywords in Claude Code
- Run in-context (no separate agent launch)
- Execute predefined workflows efficiently
- Return results directly to orchestrator

**Token Cost**: 300-600 tokens per invocation (vs 1800-2200 for subagents)

**Available Skills**:
- **Feature Orchestration** - Coordinate feature lifecycle, recommend next task (400-700 tokens)
- **Task Orchestration** - Route tasks, update status, parallel execution planning (300-600 tokens)
- **Dependency Analysis** - Check dependencies, identify blockers (350-550 tokens)
- **Status Progression** - Validate status transitions with config rules (300-500 tokens)

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

### Tier 3: Hooks

**What Hooks Are**: Bash scripts that execute automatically at specific trigger points (tool calls, session start, subagent completion).

**How They Work**:
- Configured in `.claude/settings.local.json`
- Execute when triggers fire (PostToolUse, PreToolUse, SessionStart, SubagentStop)
- Run outside Claude's context (no LLM calls)
- Can block operations or run in background

**Token Cost**: 0 tokens (no LLM involvement)

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

### Tier 4: Subagents

**What Subagents Are**: Specialized AI agents with full context and conversation history for complex reasoning and code generation.

**How They Work**:
- Launched by orchestrator via Claude Code's agent system
- Start with clean context (task UUID only)
- Perform complex multi-step work
- Return brief summaries to orchestrator

**Token Cost**: 1800-2200 tokens per invocation (includes task read, work, completion)

**Available Subagents**:
- **Backend Engineer** (Sonnet) - REST APIs, services, business logic
- **Database Engineer** (Sonnet) - Schemas, migrations, query optimization
- **Frontend Developer** (Sonnet) - UI components, state management
- **Test Engineer** (Sonnet) - Unit tests, integration tests, test automation
- **Technical Writer** (Sonnet) - API docs, user guides, README files
- **Planning Specialist** (Sonnet) - Task breakdown, dependency mapping
- **Feature Architect** (Opus) - Feature design, requirements formalization
- **Senior Engineer** (Sonnet) - Complex debugging, bug fixing, unblocking

**Self-Service Pattern** (Key Innovation):
Specialists receive only a task UUID from the orchestrator and read their own context:
1. `query_container(operation="get", containerType="task", id=UUID)` - Read task details
2. `query_dependencies(taskId=UUID, direction="incoming")` - Check blocking dependencies
3. `query_sections(entityType=TASK, entityId=DEP_ID, tags="files-changed")` - Read dependency outputs
4. Perform specialist work (implementation, testing, documentation, etc.)
5. `manage_container(operation="update", summary="...")` - Update task summary (300-500 chars)
6. `manage_sections(operation="add", title="Files Changed", ...)` - Document changes
7. `manage_container(operation="setStatus", status="completed")` - Mark complete
8. Return minimal brief: "✅ COMPLETED" or "⚠️ BLOCKED: [reason]"

This pattern eliminates 2680 tokens of routing overhead per task (90% reduction).

**Key Characteristics**:
- ✅ Full reasoning capabilities
- ✅ Code generation and file manipulation
- ✅ Multi-step workflows with backtracking
- ✅ Specialized expertise per domain
- ✅ Self-service context reading (read own task data)
- ✅ Context isolation (98% orchestrator savings)
- ✅ Minimal orchestrator overhead (20 tokens to pass UUID)
- ❌ Higher token cost per invocation than Skills
- ❌ Overkill for simple coordination

**When to Use**:
- Writing code (services, APIs, components)
- Creating database schemas and migrations
- Implementing complex business logic
- Writing comprehensive test suites
- Architecture and design decisions
- Requirements analysis and planning

---

## Decision Guide

Use this flowchart to choose the right tier:

```
Is this a single atomic operation?
(update one field, query one entity, create one resource)
    │
    ├─ YES ──→ USE DIRECT TOOL (Tier 1)
    │           • ~100-200 tokens
    │           • Fastest execution
    │           • Example: manage_container(operation='setStatus', ...)
    │
    └─ NO
        │
        Is this a side effect that can be scripted?
        (git commit, run tests, send notification, log metric)
            │
            ├─ YES ──→ USE HOOK (Tier 3)
            │           • 0 tokens
            │           • Fully automated
            │           • Example: Auto-commit on task completion
            │
            └─ NO
                │
                Is this simple coordination?
                (2-5 tool calls, no complex reasoning, no code generation)
                    │
                    ├─ YES ──→ USE SKILL (Tier 2)
                    │           • 300-600 tokens
                    │           • Fast execution
                    │           • Example: Route task to specialist
                    │
                    └─ NO
                        │
                        Does this require reasoning or code generation?
                        (implement features, architecture decisions, complex planning)
                            │
                            └─ YES ──→ USE SUBAGENT (Tier 4)
                                        • 1800-2200 tokens
                                        • Full capabilities
                                        • Example: Implement REST API
```

**Decision Rule**:
```
Single operation? → Direct Tool (~150 tokens)
Can script it? → Hook (0 tokens)
Can coordinate (2-5 tools)? → Skill (300-600 tokens)
Need reasoning/code? → Subagent (1800-2200 tokens, self-service)
```

---

## Token Efficiency Comparison

### Single Task Workflow: "Complete Task T1"

**Scenario**: Mark a task complete and create a summary section.

| Approach | Token Cost | Orchestrator Context Growth |
|----------|------------|------------------------------|
| **Direct Tool** | ~150 tokens | +150 tokens (in-context) |
| **Skill** | 450 tokens | +450 tokens (in-context) |
| **Subagent-Only** | 2000 tokens | +2000 tokens (full context) |
| **Hybrid (Skill + Hook)** | 450 tokens | +450 tokens (hook = 0) |

### Feature Completion Workflow: "8 Tasks in Sequence"

**Scenario**: Complete 8 sequential tasks with dependencies.

| Approach | Total Tokens | Per-Task Cost | Orchestrator Growth |
|----------|--------------|---------------|---------------------|
| **Old (3-hop pattern)** | 38,400 tokens | 4800 tokens/task | +38,400 tokens |
| **Hybrid (Direct + Skills)** | 14,400 tokens | 1800 tokens/task | +800 tokens |
| **Savings** | 62% | - | 98% reduction |

**Hybrid Breakdown**:
- 8× Direct specialist launches: (220 routing + 1800 work) × 8 = 16,160 tokens
- Skills coordination (status checks, recommendations): 400 × 5 = 2,000 tokens (in-context)
- Hooks (git, tests): 0 tokens
- Orchestrator context growth: 8 × 100 (briefs: "✅ COMPLETED") = 800 tokens

### Complex Feature: "API + Database + Frontend + Tests"

**Scenario**: 12-task feature with backend, database, frontend, and testing work.

| Approach | Total Tokens | Orchestrator Context | Time Estimate |
|----------|--------------|----------------------|---------------|
| **Old (3-hop pattern)** | 57,600 tokens | +57,600 tokens | ~20 minutes |
| **Hybrid (Direct + Skills)** | 24,240 tokens | +1,200 tokens | ~12 minutes |
| **Savings** | 58% | 98% reduction | 40% faster |

---

## Specialist Agents

### Available Specialists

#### Backend Engineer
- **Tags**: backend, api, service, kotlin, rest
- **Focus**: REST APIs, services, business logic, database integration
- **Model**: Sonnet
- **Cost**: ~1800-2200 tokens per task

#### Frontend Developer
- **Tags**: frontend, ui, react, vue, angular, web
- **Focus**: UI components, state management, API integration
- **Model**: Sonnet
- **Cost**: ~1800-2200 tokens per task

#### Database Engineer
- **Tags**: database, migration, schema, sql, flyway
- **Focus**: Schemas, migrations, query optimization, indexing
- **Model**: Sonnet
- **Cost**: ~1500-2000 tokens per task

#### Test Engineer
- **Tags**: testing, test, qa, quality, coverage
- **Focus**: Unit tests, integration tests, test automation, coverage
- **Model**: Sonnet
- **Cost**: ~1600-2200 tokens per task

#### Technical Writer
- **Tags**: documentation, docs, user-docs, api-docs, guide, readme
- **Focus**: API docs, user guides, README files, code comments
- **Model**: Sonnet
- **Cost**: ~1500-1900 tokens per task

#### Planning Specialist
- **Tags**: planning, requirements, specification, architecture, design
- **Focus**: Requirements analysis, architecture, design decisions, task breakdown
- **Model**: Sonnet
- **Cost**: ~1200-1800 tokens per task

#### Feature Architect
- **Tags**: feature, architecture, design, requirements
- **Focus**: Feature design, requirements formalization, high-level planning
- **Model**: Opus
- **Cost**: ~1800-2500 tokens per task

#### Senior Engineer
- **Tags**: debug, bug, complex, blocker, performance
- **Focus**: Complex debugging, bug fixing, performance optimization, unblocking
- **Model**: Sonnet
- **Cost**: ~1500-2200 tokens per task

### Specialist Workflow Pattern

All specialists follow this **9-step self-service workflow**:

1. **Read the Task**: `query_container(operation="get", containerType="task", id=UUID, includeSections=true)`
2. **Read Dependencies**: Self-service dependency checking and summary reading
3. **Do the Work**: Perform specialized implementation
4. **Update Task Sections**: Document implementation details
5. **Populate Summary**: Create standardized summary with Files Changed section
6. **Mark Complete**: `manage_container(operation="setStatus", status="completed")`
7. **Return Minimal Output**: "✅ COMPLETED" or "⚠️ BLOCKED: [reason]"

**Why Minimal Output**:
- Orchestrator only needs success confirmation + file list
- Detailed work captured in task sections and Summary
- Reduces orchestrator context growth by ~90%
- Enables scaling to hundreds of tasks

---

## Agent Mapping Configuration

### Purpose

The `agent-mapping.yaml` file maps task tags to specialized agents, enabling automatic specialist selection.

**File Location**: `.taskorchestrator/agent-mapping.yaml`

### Configuration Structure

```yaml
# Map workflow activities to agents
workflowPhases:
  planning: Planning Specialist
  documentation: Technical Writer
  review: Technical Writer

# Map task tags to specialized agents
tagMappings:
  - task_tags: [backend, api, service, kotlin, rest]
    agent: Backend Engineer
    section_tags: [requirements, technical-approach, implementation]

  - task_tags: [frontend, ui, react, vue, web]
    agent: Frontend Developer
    section_tags: [requirements, technical-approach, design, ux]

  - task_tags: [database, migration, schema, sql, flyway]
    agent: Database Engineer
    section_tags: [requirements, technical-approach, data-model]

# Priority order when multiple tags match (first match wins)
tagPriority:
  - database
  - backend
  - frontend
  - testing
  - documentation
  - planning
```

### How Routing Works

1. Task Manager calls `recommend_agent(taskId='...')`
2. Tool reads task tags (e.g., `["backend", "api", "rest"]`)
3. Tool reads `agent-mapping.yaml` tag mappings
4. Tool finds first matching mapping (according to tagPriority)
5. Tool returns:
   - `agent`: "Backend Engineer"
   - `reason`: "Task tags match backend category"
   - `matchedTags`: ["backend", "api", "rest"]
   - `sectionTags`: ["requirements", "technical-approach", "implementation"]

---

## Integration Patterns

### Pattern 1: Direct Tool (Atomic Operation)

**Workflow**: Single operation, no coordination needed

```
User: "Update task T1 status to completed"

Orchestrator:
1. Calls manage_container(operation='setStatus', containerType='task', id=T1, status='completed') directly
2. Receives success confirmation
3. Informs user

Token Cost: ~150 tokens
```

### Pattern 2: Skill → Hook (Task Completion)

**Workflow**: Skill completes task, Hook auto-commits

```
User: "Complete task T1"

Skill (Task Orchestration):
1. query_container(operation="get", id=T1)
2. manage_sections(operation="add", title="Summary", ...)
3. manage_container(operation="setStatus", id=T1, status="completed")
4. Return: "Task T1 completed. Summary created."

Hook (PostToolUse on setStatus):
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

### Pattern 3: Orchestrator → Subagent (Direct Routing)

**Workflow**: Orchestrator routes task directly to specialist

```
User: "Work on task T1"

Orchestrator:
1. Calls recommend_agent(taskId=T1)
2. Receives recommendation: "Backend Engineer"
3. Launches Backend Engineer subagent with task UUID only

Subagent (Backend Engineer - Self-Service):
1. Reads task: query_container(operation="get", id=T1)
2. Checks dependencies: query_dependencies(taskId=T1)
3. Reads completed dependency outputs (Files Changed sections)
4. Implements code
5. Updates task summary (300-500 chars)
6. Creates Files Changed section
7. Marks complete: manage_container(operation="setStatus", status="completed")
8. Returns brief: "✅ COMPLETED"

Result:
- Direct routing (Orchestrator: 20 tokens to pass UUID)
- Self-service specialist (Specialist: 200 tokens to read context)
- Total overhead: 220 tokens vs 2900 for old 3-hop pattern
- Savings: 90% reduction (2680 tokens saved)
```

### Pattern 4: Skill + Hook + Subagent (Complete Flow)

**Workflow**: Full task lifecycle with all three tiers

```
User: "Implement and complete task T1"

Step 1: Check Status (Skill)
Task Orchestration Skill:
- Reads task details
- Calls recommend_agent(taskId=T1)
- Returns: "Backend Engineer recommended"
Cost: 400 tokens

Step 2: Implement (Subagent - Direct Launch)
Orchestrator → Backend Engineer (with UUID only):
- Specialist reads task (200 tokens)
- Checks dependencies (100 tokens)
- Writes code
- Updates task summary
- Creates Files Changed section
- Marks complete
- Returns brief: "✅ COMPLETED"
Cost: 1800 tokens (specialist work)

Step 3: Auto-Commit (Hook)
PostToolUse Hook:
- Detects status=completed
- Creates git commit automatically
Cost: 0 tokens

Total: 2200 tokens
Orchestrator context growth: ~100 tokens (brief only)
Savings: 56% vs old 3-hop + subagent pattern (5000 tokens)
```

---

## Complete Workflow Examples

### Example 1: Simple Task Completion (Skill + Hook)

**Scenario**: User completes a task that doesn't need code changes.

```
User: "Complete task T1: Update README with new features"

TIER 2: SKILL (Task Orchestration)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. query_container(operation="get", id=T1, includeSections=true)
   → Task: "Update README with new features"
   → Status: in-progress

2. manage_sections(operation="add", entityType=TASK, entityId=T1,
     title="Summary", content="Updated README...", ordinal=999)

3. manage_container(operation="setStatus", id=T1, status="completed")

Return: "Task T1 completed. README updated with new features documentation."

Cost: 450 tokens

TIER 3: HOOK (Auto-Commit)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook Triggered:
1. Detects: tool_name = setStatus, status = "completed"
2. Extracts: task_id = T1
3. Queries: SELECT title FROM Tasks WHERE id='T1'
4. Executes:
   git add README.md
   git commit -m "docs: Update README with new features" -m "Task-ID: T1"

Cost: 0 tokens

RESULT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Task marked complete
✅ Summary section created
✅ Git commit created automatically
✅ Total tokens: 450 (Hook = 0)
✅ Orchestrator context growth: +450 tokens
```

### Example 2: Code Implementation Task (Direct Specialist + Hook)

**Scenario**: User needs to implement a new API endpoint.

```
User: "Implement task T2: Create user login endpoint"

ORCHESTRATOR (Direct Routing)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

1. Calls recommend_agent(taskId=T2)
   → Agent: "Backend Engineer"
   → Reason: "Task tags match backend category (backend, api)"

2. Launches Backend Engineer with task UUID: T2

Context passed to specialist: 20 tokens (just UUID)

TIER 4: SUBAGENT (Backend Engineer - Self-Service)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Backend Engineer (clean context, self-service):
1. query_container(operation="get", id=T2, includeSections=true)
   → Task: "Create user login endpoint"
   → Tags: ["backend", "api", "authentication"]
   → Dependencies: T1 (Database schema - completed)

2. query_dependencies(taskId=T2, direction="incoming")
   → Found: T1 (completed)

3. query_sections(entityType=TASK, entityId=T1, tags="files-changed")
   → Files Changed: "Users.kt, UserTable.kt, V5__create_users_table.sql"

4. Implements:
   - UserController.kt (login endpoint)
   - AuthenticationService.kt (JWT token generation)
   - UserControllerTest.kt (unit tests)

5. manage_container(operation="update", id=T2,
     summary="Implemented user login endpoint with JWT authentication...")

6. manage_sections(operation="add", entityType=TASK, entityId=T2,
     title="Files Changed", content="### Files Modified\n- UserController.kt\n...",
     ordinal=999, tags="files-changed,completion")

7. manage_container(operation="setStatus", id=T2, status="completed")

8. Returns: "✅ COMPLETED"

Cost: 1800 tokens (self-service context reading + implementation)

TIER 3: HOOK (Auto-Commit)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

PostToolUse Hook:
1. Detects: status = "completed"
2. Creates commit:
   git commit -m "feat: Create user login endpoint" -m "Task-ID: T2"

Cost: 0 tokens

RESULT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

✅ Direct specialist routing (Orchestrator: 20 tokens)
✅ Self-service context reading (Specialist: 200 tokens)
✅ Code implementation (Specialist: 1600 tokens)
✅ Git commit created (Hook: 0 tokens)
✅ Total tokens: 1820
✅ Orchestrator context growth: ~50 tokens (brief only: "✅ COMPLETED")

vs Old 3-hop pattern:
❌ Total tokens: 4700 tokens
❌ Orchestrator context growth: +2900 tokens
❌ Token savings: 61% reduction
```

---

## Setup and Configuration

### Quick Start

**Recommended**: Use the initialization workflow for complete setup:

```
User: "Initialize Task Orchestrator"
```

This workflow will:
1. Write AI guidelines to your project's documentation file
2. Detect if you're using Claude Code
3. Offer optional features:
   - **Workflow Automation Hooks**: Auto-load context, template discovery reminders
   - **Sub-Agent Orchestration**: 4-tier agent coordination system

**Manual setup** (if you prefer):

```
User: "Setup Claude Code agents"
```

### Plugin Installation

**Method**: Claude Code Plugin Marketplace

**What Gets Installed**:

**Subagents** (`.claude/agents/task-orchestrator/`):
- `feature-architect.md` - Feature design and breakdown
- `implementation-specialist.md` - General implementation tasks
- `planning-specialist.md` - Requirements, architecture, planning
- `senior-engineer.md` - Complex debugging, unblocking

**Skills** (`.claude/skills/`):
- `feature-orchestration/` - Feature lifecycle coordination
- `task-orchestration/` - Task execution coordination
- `dependency-analysis/` - Dependency tracking
- `dependency-orchestration/` - Dependency management
- `status-progression/` - Status workflow validation
- Plus implementation domain skills (backend, frontend, database, testing, documentation)

**Installation Commands**:
- Local development: `/plugin marketplace add ./` then `/plugin install task-orchestrator`
- From GitHub: `/plugin install jpicklyk/task-orchestrator`

**Post-Installation**:
- Run `setup_project` to initialize project configuration
- Plugin automatically loads agents and skills on restart

### Workflow Automation Hooks (Optional)

**Installation**: Edit or create `.claude/settings.local.json`:

```json
{
  "hooks": {
    "SessionStart": [{
      "matcher": "*",
      "hooks": [{
        "type": "command",
        "command": "bash",
        "args": ["-c", "echo '{\"message\": \"💡 Task Orchestrator: Loading project context with get_overview()...\"}'"]
      }]
    }],
    "PostToolUse": [{
      "matcher": "mcp__task-orchestrator__manage_container",
      "hooks": [{
        "type": "command",
        "command": "bash",
        "args": ["-c", "if echo \"$TOOL_OUTPUT\" | grep -q '\"status\":\"completed\"'; then task_id=$(echo \"$TOOL_INPUT\" | jq -r '.id'); git commit -m \"Task $task_id completed\"; fi"]
      }]
    }]
  }
}
```

---

## Migration Guide

### From Subagent-Only to Hybrid Architecture

#### Step 1: Identify Coordination Workflows

❌ **Before (Subagent for coordination)**:
```
User: "Complete task T1"
Orchestrator launches coordination subagent
Cost: 1500 tokens
```

✅ **After (Hybrid with Skill)**:
```
User: "Complete task T1"
Task Orchestration Skill executes
Cost: 450 tokens
Savings: 70%
```

**Migration Action**:
- Install Task Orchestration Skill
- Use Skill for task completion and coordination
- Reserve subagents for implementation work only

#### Step 2: Extract Side Effects to Hooks

❌ **Before (Subagent doing git)**:
```
Backend Engineer:
1. Implements code
2. Creates git commit
3. Runs tests
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

Savings: 200-300 tokens per task
```

#### Step 3: Optimize Task Routing

❌ **Before (3-hop with middleware)**:
```
Orchestrator → Middleware Layer 1 → Middleware Layer 2 → Specialist
Routing overhead: 2900 tokens
```

✅ **After (Direct + Self-Service)**:
```
Orchestrator → recommend_agent() → Direct Specialist Launch (UUID only)
Specialist → Self-service (reads own context)
Routing overhead: 220 tokens (90% reduction)
```

---

## Best Practices

### 1. Use the Lightest Tier Possible

**Decision Priority**:
1. Single operation? → Use Direct Tool (~150 tokens)
2. Can a Hook do it? → Use Hook (0 tokens)
3. Can a Skill do it? → Use Skill (300-600 tokens)
4. Need reasoning/code? → Use Subagent (1800-2200 tokens)

### 2. Combine Tiers for Maximum Efficiency

**Pattern**: Skill coordination + Hook side effects + Subagent implementation

```
Task lifecycle:
1. Skill START → Routes task (400 tokens)
2. Subagent → Implements code (1800 tokens)
3. Skill END → Completes task (450 tokens)
4. Hook → Auto-commits (0 tokens)

Total: 2650 tokens vs 5000+ subagent-only
```

### 3. Use Skills for Repeated Operations

**If you do it more than twice, use a Skill**:

❌ 10× task completions with subagent: 15,000 tokens
✅ 10× task completions with Skill: 4,500 tokens (70% savings)

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

---

## Troubleshooting

### Issue: Task Orchestration Skill Not Activating

**Symptoms**: Skill doesn't trigger for coordination tasks

**Solution**:
- Verify plugin is installed via `/plugin list`
- Check that Skills exist in `.claude/skills/`
- Skills activate based on description keywords in user requests
- Restart Claude Code if needed

### Issue: Dependency Context Not Passed

**Symptoms**: Specialist doesn't have context from previous tasks

**Solution**:
- Specialists use self-service pattern (read own dependencies)
- Verify dependencies are marked "completed"
- Check that dependency tasks have "Files Changed" sections
- Confirm specialist is calling `query_dependencies` and `query_sections`

### Issue: Specialist Returns Full Code in Response

**Symptoms**: Specialist response contains hundreds of lines of code

**Solution**:
- Remind specialist: "Return brief summary only (2-3 sentences)"
- Emphasize: "Detailed work goes in task sections"
- Check specialist agent definition emphasizes brief responses

### Issue: Wrong Specialist Selected

**Symptoms**: Backend task assigned to Frontend Developer

**Solution**:
- Check task tags match agent-mapping.yaml patterns
- Verify tagPriority order is correct
- Confirm recommend_agent is being called
- Review matchedTags in recommend_agent response

### Issue: Orchestrator Context Growing Large

**Symptoms**: Orchestrator context approaching limits

**Solution**:
- Verify orchestrator is only keeping brief summaries
- Check that full specialist responses are discarded
- Ensure detailed work stored in task sections, not context
- Specialists should return minimal output ("✅ COMPLETED")

---

## Summary

**Hybrid Architecture = Right Tool for Right Job**

| Tier | Purpose | Token Cost | When to Use |
|------|---------|------------|-------------|
| **Direct Tools** | Atomic ops | ~100-200 | Single operations (set_status, create_task) |
| **Skills** | Coordination | ~300-600 | Status updates, routing, dependency checks (2-5 tools) |
| **Hooks** | Side Effects | 0 | Git automation, test gates, notifications, logging |
| **Subagents** | Deep Work | ~1800-2200 | Code generation, architecture, complex reasoning (self-service) |

**Key Benefits**:
- **58-62% total token reduction** vs old 3-hop pattern
- **98% orchestrator context reduction** (specialists return briefs only)
- **90% routing overhead reduction** (direct launch vs middleware layers)
- **Self-service specialists** read their own context (20 token handoff)
- Faster execution for coordination
- Better separation of concerns
- Scales to larger projects

**Decision Rule**:
```
Single operation? → Direct Tool (~150 tokens)
Can script it? → Hook (0 tokens)
Can coordinate (2-5 tools)? → Skill (300-600 tokens)
Need reasoning/code? → Subagent (1800-2200 tokens, self-service)
```

---

**Ready to implement hybrid architecture?** See:
- [Skill Builder](.claude/skills/skill-builder/SKILL.md) - Create custom Skills
- [Hook Builder](.claude/skills/hook-builder/SKILL.md) - Create custom Hooks
- [Plugin Installation](plugin-installation.md) - Install agents and skills via marketplace
