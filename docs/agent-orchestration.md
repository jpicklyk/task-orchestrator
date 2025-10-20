# AI Agent Orchestration System

**A hybrid 4-tier architecture combining Skills, Subagents, and Hooks for scalable, context-efficient AI workflows**

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
  - [Hybrid 4-Tier Architecture](#hybrid-4-tier-architecture)
  - [When to Use What: Decision Guide](#when-to-use-what-decision-guide)
  - [Skills Tier (Coordination)](#skills-tier-coordination)
  - [Subagents Tier (Complex Work)](#subagents-tier-complex-work)
  - [Hooks Layer (Automation)](#hooks-layer-automation)
  - [Key Design Principles](#key-design-principles)
- [Agent-Mapping Configuration](#agent-mapping-configuration)
  - [Purpose](#purpose)
  - [Configuration Structure](#configuration-structure)
  - [Tag-Based Agent Selection](#tag-based-agent-selection)
  - [Customizing Agent Mappings](#customizing-agent-mappings)
- [Feature Manager Agent](#feature-manager-agent)
  - [Role and Responsibilities](#role-and-responsibilities)
  - [START Mode Workflow](#feature-manager-start-mode)
  - [END Mode Workflow](#feature-manager-end-mode)
  - [Token Optimization](#feature-manager-token-optimization)
- [Task Manager Agent](#task-manager-agent)
  - [Role and Responsibilities](#task-manager-role)
  - [START Mode Workflow](#task-manager-start-mode)
  - [END Mode Workflow](#task-manager-end-mode)
  - [Dependency Context Passing](#dependency-context-passing)
  - [Token Optimization](#task-manager-token-optimization)
- [Specialist Agents](#specialist-agents)
  - [Available Specialists](#available-specialists)
  - [Specialist Workflow Pattern](#specialist-workflow-pattern)
  - [Section Tag Filtering](#section-tag-filtering)
- [Complete Workflow Examples](#complete-workflow-examples)
  - [Single Task Workflow](#single-task-workflow)
  - [Feature with Dependency Chain](#feature-with-dependency-chain)
  - [Parallel Work Opportunities](#parallel-work-opportunities)
  - [Multi-Feature Project](#multi-feature-project)
- [Token Efficiency and Scaling](#token-efficiency-and-scaling)
  - [Context Isolation](#context-isolation)
  - [Summary Sections](#summary-sections)
  - [Bookends Pattern](#bookends-pattern)
  - [Why This Architecture Scales](#why-this-architecture-scales)
- [Setup and Configuration](#setup-and-configuration)
  - [Agent Definition Files](#agent-definition-files)
  - [Setup Tool](#setup-tool)
  - [Re-initialization and Upgrades](#re-initialization-and-upgrades)
  - [Workflow Automation Hooks](#workflow-automation-hooks-optional)
  - [Customization Options](#customization-options)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Overview

The MCP Task Orchestrator implements a **hybrid 4-tier architecture** that enables scalable AI workflows with minimal token usage. This system provides four complementary mechanisms for AI-driven work:

1. **Direct Tools** - Single MCP tool calls for atomic operations
2. **Skills** - Lightweight coordination (2-5 tool calls) for repetitive workflows
3. **Subagents** - Complex reasoning and implementation for specialist work
4. **Hooks** - Automated side effects (git commits, tests, notifications)

This architecture achieves **60-82% token reduction** for coordination tasks while maintaining specialist capabilities for complex work.

### 🚀 Quick Start

**Recommended**: Use the initialization workflow for complete setup:

```
User: "Initialize Task Orchestrator"
```

This workflow will:
1. Write AI guidelines to your project's documentation file (CLAUDE.md, .cursorrules, etc.)
2. Detect if you're using Claude Code (checks for `.claude/` directory)
3. Offer optional features:
   - **Workflow Automation Hooks**: Auto-load context, template discovery reminders
   - **Sub-Agent Orchestration**: 3-level agent coordination system

**Manual setup** (if you prefer):

```
User: "Setup Claude Code agents"
```

This creates the `.claude/agents/` directory with all agent definitions. Without this step, sub-agents will not be available.

See [Setup Tool](#setup-tool) for complete details.

### What Problem Does This Solve?

Traditional AI workflows face several challenges:

1. **Context Overload**: Single agent accumulates massive context from all tasks
2. **Token Waste**: Launching subagents for simple coordination tasks (2-5 tool calls)
3. **Manual Side Effects**: Manually running git commits, tests after task completion
4. **Lack of Specialization**: One agent tries to handle all work types
5. **Poor Scaling**: Adding more work increases context linearly

### How Hybrid Architecture Solves This

The 4-tier architecture provides:

- **Direct Tools**: Atomic operations (create task, update status) - fastest, lowest overhead
- **Skills**: Lightweight coordination (recommend next task, complete feature) - **60-82% token savings** vs subagents
- **Subagents**: Complex work (implementation, planning, architecture) - context isolation and specialization
- **Hooks**: Automated workflows (git commits, test runs, notifications) - zero AI tokens, instant execution
- **Token Efficiency**: Summary sections enable knowledge transfer at ~300-500 tokens
- **Dependency Awareness**: Skills and subagents pass completed dependency context automatically

---

## Architecture

### Hybrid 4-Tier Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         TIER 1: DIRECT TOOLS                             │
│                         (Single MCP Tool Calls)                          │
│                                                                           │
│  create_task, update_task, set_status, add_section, etc.                 │
│  Use for: Atomic operations requiring no coordination                    │
│  Token Cost: ~100-200 tokens per call                                    │
│  Example: Update task summary field, add single section                  │
└─────────────────────────────────────────────────────────────────────────┘
                                      ↕
┌─────────────────────────────────────────────────────────────────────────┐
│                         TIER 2: SKILLS                                   │
│                    (Lightweight Coordination - 2-5 tool calls)           │
│                                                                           │
│  ┌─────────────────┐  ┌─────────────────┐  ┌──────────────────┐        │
│  │ Feature         │  │ Task            │  │ Dependency       │        │
│  │ Management      │  │ Management      │  │ Analysis         │        │
│  │                 │  │                 │  │                  │        │
│  │ • Recommend     │  │ • Route to      │  │ • Find blocked   │        │
│  │   next task     │  │   specialist    │  │   tasks          │        │
│  │ • Complete      │  │ • Complete      │  │ • Analyze        │        │
│  │   feature       │  │   task          │  │   chains         │        │
│  │ • Check         │  │ • Update        │  │ • Identify       │        │
│  │   progress      │  │   status        │  │   bottlenecks    │        │
│  └─────────────────┘  └─────────────────┘  └──────────────────┘        │
│                                                                           │
│  Use for: Repetitive coordination workflows                              │
│  Token Cost: ~300-600 tokens (60-82% savings vs subagents)               │
│  Example: Recommend next task (3 tool calls), complete task (4 calls)    │
└─────────────────────────────────────────────────────────────────────────┘
                                      ↕
┌─────────────────────────────────────────────────────────────────────────┐
│                      TIER 3: SUBAGENTS                                   │
│                  (Complex Work - Clean Context, Specialized)             │
│                                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │ Backend      │  │ Database     │  │ Frontend     │                  │
│  │ Engineer     │  │ Engineer     │  │ Developer    │                  │
│  └──────────────┘  └──────────────┘  └──────────────┘                  │
│                                                                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                  │
│  │ Test         │  │ Technical    │  │ Planning     │                  │
│  │ Engineer     │  │ Writer       │  │ Specialist   │                  │
│  └──────────────┘  └──────────────┘  └──────────────┘                  │
│                                                                           │
│  Use for: Implementation, testing, documentation, planning               │
│  Token Cost: ~2-5k tokens (context isolation, specialized work)          │
│  Example: Implement auth API, write integration tests, create plan       │
└─────────────────────────────────────────────────────────────────────────┘

═══════════════════════════════════════════════════════════════════════════
                         PARALLEL LAYER: HOOKS
                        (Automated Side Effects - Zero AI Tokens)

  PostToolUse          │  SubagentStop        │  PreToolUse
  ─────────────────────┼──────────────────────┼────────────────────
  • Git commit on      │  • Log completion    │  • Validate before
    task complete      │    metrics           │    operation
  • Run tests on       │  • Send              │  • Check
    feature complete   │    notifications     │    preconditions
  • Update external    │  • Archive           │  • Warn about
    systems (Jira)     │    transcripts       │    missing data

  Use for: Automation that doesn't require AI reasoning
  Token Cost: 0 (runs outside AI context)
  Example: Auto-commit when task completed, run ./gradlew test
═══════════════════════════════════════════════════════════════════════════
```

### When to Use What: Decision Guide

**Decision Matrix for AI Work**:

| Work Type | Use | Why | Token Cost | Example |
|-----------|-----|-----|------------|---------|
| **Single operation** | Direct Tool | Fastest, lowest overhead | ~100-200 | `set_status(id, 'completed')` |
| **Coordination (2-5 calls)** | Skill | 60-82% cheaper than subagent | ~300-600 | Recommend next task, complete feature |
| **Complex reasoning** | Subagent | Needs full context, specialization | ~2-5k | Implement auth API, write test suite |
| **Side effects** | Hook | Zero AI tokens, instant | 0 | Git commit, run tests, send Slack notification |

**Detailed Decision Flow**:

```
START: What do you need to do?
│
├─ Update ONE field/status?
│  └─> Use DIRECT TOOL (create_task, set_status, etc.)
│      Token cost: ~100-200
│
├─ Coordinate 2-5 tool calls? (recommend task, complete feature, check deps)
│  └─> Use SKILL (Feature Management, Task Management, Dependency Analysis)
│      Token cost: ~300-600 (60-82% cheaper than subagent)
│      Examples:
│        • "What task should I work on next?" → Feature Management Skill
│        • "Complete this task and create summary" → Task Management Skill
│        • "Show me blocked tasks" → Dependency Analysis Skill
│
├─ Implement code, write tests, create docs, plan architecture?
│  └─> Use SUBAGENT (Backend Engineer, Test Engineer, Planning Specialist, etc.)
│      Token cost: ~2-5k (context isolation, specialized reasoning)
│      Examples:
│        • "Implement user authentication" → Backend Engineer
│        • "Write integration tests" → Test Engineer
│        • "Break down feature into tasks" → Planning Specialist
│
└─ Automate git commits, tests, notifications after task/feature changes?
   └─> Use HOOK (PostToolUse, SubagentStop, PreToolUse)
       Token cost: 0 (runs outside AI context, bash scripts)
       Examples:
         • Auto-commit when task completed
         • Run ./gradlew test when feature marked complete
         • Log metrics when subagent finishes
```

**Key Principle**: **Prefer the simplest mechanism that meets your needs**. Skills are cheaper than subagents; direct tools are cheaper than skills; hooks don't use AI tokens at all.

### Skills Tier (Coordination)

**Purpose**: Lightweight coordination operations that require 2-5 tool calls but no complex reasoning.

**Core Skills**:

1. **Feature Management Skill** (`.claude/skills/feature-management/SKILL.md`)
   - Recommend next task to work on
   - Complete feature with summary
   - Check feature progress
   - List blocked tasks

2. **Task Management Skill** (`.claude/skills/task-management/SKILL.md`)
   - Recommend specialist for task
   - Complete task with summary
   - Update task status
   - Check task dependencies

3. **Dependency Analysis Skill** (`.claude/skills/dependency-analysis/SKILL.md`)
   - Find all blocked tasks
   - Analyze dependency chains
   - Identify bottleneck tasks
   - Recommend resolution order

**Token Savings**: **60-82% reduction** vs Feature Manager/Task Manager subagents

**Comparison**:
- Feature Manager subagent START: ~1400 tokens → Skill: ~300 tokens (78% reduction)
- Feature Manager subagent END: ~1700 tokens → Skill: ~600 tokens (65% reduction)
- Task Manager subagent START: ~1300 tokens → Skill: ~300 tokens (77% reduction)
- Task Manager subagent END: ~1500 tokens → Skill: ~600 tokens (60% reduction)

**How Skills Work**:
- Model-invoked (activate automatically based on description keywords)
- Restricted tool access via `allowed-tools` YAML frontmatter
- Execute 2-5 tool calls in sequence
- Return results directly to orchestrator
- No separate context/conversation history

**When NOT to Use Skills**: Complex reasoning, code generation, architectural decisions → use subagents instead

### Subagents Tier (Complex Work)

**Purpose**: Complex work requiring specialized reasoning, code generation, or substantial context.

**Specialist Subagents**:
- **Backend Engineer** (Sonnet) - REST APIs, services, business logic
- **Database Engineer** (Sonnet) - Schemas, migrations, query optimization
- **Frontend Developer** (Sonnet) - UI components, state management
- **Test Engineer** (Sonnet) - Unit tests, integration tests, test automation
- **Technical Writer** (Sonnet) - API docs, user guides, README files
- **Planning Specialist** (Sonnet) - Requirements analysis, task breakdown, architecture
- **Feature Architect** (Opus) - Feature design, requirements formalization

**When to Use Subagents**:
- Implementing code (requires reading codebase, writing files, testing)
- Writing comprehensive tests (needs to understand implementation)
- Creating documentation (needs to analyze API behavior)
- Planning/architecture (needs to reason about trade-offs)

**Subagent Benefits**:
- **Context Isolation**: Each starts with clean context, no orchestrator history
- **Specialization**: Domain-specific instructions and workflows
- **Tag-Based Routing**: `recommend_agent` tool selects right specialist
- **Dependency Context**: Receives summaries from completed dependencies

**Token Cost**: ~2-5k tokens per subagent invocation (acceptable for complex work)

### Hooks Layer (Automation)

**Purpose**: Automate side effects that don't require AI reasoning (git commits, tests, notifications).

**How Hooks Work**:
- Claude Code executes bash scripts at specific events
- Runs outside AI context (zero AI tokens)
- Can be blocking (prevent operation) or non-blocking (observe only)

**Hook Events**:
- **PostToolUse**: After any MCP tool is called
- **SubagentStop**: After a subagent completes
- **PreToolUse**: Before a tool is called (validation)

**Example Hooks**:

1. **Task Completion Auto-Commit** (PostToolUse on `set_status`)
   ```bash
   # When task marked complete, auto-commit changes
   git add -A
   git commit -m "feat: $TASK_TITLE" -m "Task-ID: $TASK_ID"
   ```

2. **Feature Completion Quality Gate** (PostToolUse on `update_feature`)
   ```bash
   # When feature marked complete, run tests first
   ./gradlew test
   if [ $? -ne 0 ]; then
     echo '{"decision": "block", "reason": "Tests failing"}'
   fi
   ```

3. **Subagent Metrics Logger** (SubagentStop)
   ```bash
   # Log subagent completion times
   echo "$TIMESTAMP,$SUBAGENT_TYPE" >> metrics/completions.csv
   ```

**Token Cost**: **Zero** (hooks run in bash, not through AI)

**Integration with Skills/Subagents**: Hooks run automatically after Skills invoke tools or Subagents complete, providing automation without manual steps.

### Orchestrator-Driven Model

**CRITICAL CONSTRAINT**: Sub-agents CANNOT launch other sub-agents. Only the orchestrator can invoke sub-agents.

**Why This Matters**:
- Prevents infinite agent chains
- Ensures orchestrator maintains workflow control
- Keeps conversation history at orchestrator level
- Allows orchestrator to adapt workflow based on progress

**Workflow Pattern**:
```
1. Orchestrator → Feature Manager START → recommends task
2. Orchestrator → Task Manager START → recommends specialist
3. Orchestrator → Specialist → performs work → returns brief
4. Orchestrator → Task Manager END → summarizes work
5. Repeat steps 1-4 until feature complete
6. Orchestrator → Feature Manager END → summarizes feature
```

### Key Design Principles

#### 1. Context Isolation

Each sub-agent invocation starts with **clean context**:
- No context inheritance from orchestrator
- Only task/feature data provided
- Previous work accessible via Summary sections
- Results in ~90% token reduction vs. shared context

#### 2. Bookends Pattern

Both Feature Manager and Task Manager use START/END modes:

**START Mode**: Prepare and analyze
- Feature Manager: Analyze feature, recommend next task
- Task Manager: Read task, recommend specialist

**END Mode**: Summarize and complete
- Feature Manager: Create feature summary, mark complete
- Task Manager: Extract specialist output, create task summary

**Benefits**:
- Minimizes token usage through selective reading
- Separates preparation from completion
- Enables dependency context passing

#### 3. Summary Sections

Structured markdown summaries enable knowledge transfer:
- Created by Task Manager END mode
- Token-efficient (~300-500 tokens)
- Used by dependency context, feature summaries, historical reference
- Preserve essential knowledge without full context

**Summary Section Format** (300-500 tokens):
```markdown
### Completed
[What was accomplished in 2-3 sentences]

### Files Changed
- `path/to/file.kt` - [brief description of what changed]
- `path/to/test.kt` - [brief description]

### Next Steps
[What depends on this or comes next - 1-2 sentences]

### Notes
[Important technical decisions or considerations - 1-2 sentences]
```

**Example Summary Section** (427 tokens):
```markdown
### Completed
Created Users table with authentication fields and proper indexing for email-based
lookups. Implemented UUID primary keys, bcrypt password hashing, and unique
constraints on username and email fields. Added timestamps for created_at and
updated_at tracking.

### Files Changed
- `db/migration/V3__create_users_table.sql` - Users table schema with auth fields
- `src/model/User.kt` - User domain model with validation
- `src/repository/UserRepository.kt` - Repository interface for user operations

### Next Steps
API endpoints can now use this schema for user CRUD operations. Authentication
service will need to integrate bcrypt hashing when creating/validating users.

### Notes
Used UUID for ID instead of auto-increment for distributed system compatibility.
Email field is indexed and unique to support fast login lookups. Password field
stores only bcrypt hashes, never plaintext.
```

#### 4. Automatic Specialist Selection

The `recommend_agent` tool provides intelligent routing:
- Reads `agent-mapping.yaml` configuration
- Matches task tags to specialist capabilities
- Returns exact agent name and reasoning
- Includes section tags for focused context

**Task Manager must call this tool** - no manual inference allowed.

---

## Agent-Mapping Configuration

### Purpose

The `agent-mapping.yaml` file maps task and feature characteristics to specialized AI agents. This enables:

- **Automatic Specialist Selection**: Task tags determine which agent handles the work
- **Focused Context**: Section tags tell specialists which sections to prioritize
- **Consistent Routing**: Same tags always route to same specialist
- **Easy Customization**: Add custom agents by editing YAML

### Configuration Structure

**File Location**: `src/main/resources/agents/agent-mapping.yaml`

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

  - task_tags: [testing, test, qa, quality, coverage]
    agent: Test Engineer
    section_tags: [requirements, testing-strategy, acceptance-criteria]

  - task_tags: [documentation, docs, user-docs, api-docs, guide]
    agent: Technical Writer
    section_tags: [requirements, context, documentation]

  - task_tags: [planning, requirements, specification, architecture]
    agent: Planning Specialist
    section_tags: [context, requirements, technical-approach]

# Priority order when multiple tags match (first match wins)
tagPriority:
  - database
  - backend
  - frontend
  - testing
  - documentation
  - planning
```

### Tag-Based Agent Selection

**How It Works**:

1. Task Manager calls `recommend_agent(taskId='...')`
2. Tool reads task tags (e.g., `["backend", "api", "rest"]`)
3. Tool reads `agent-mapping.yaml` tag mappings
4. Tool finds first matching mapping (according to tagPriority)
5. Tool returns:
   - `agent`: "Backend Engineer"
   - `reason`: "Task tags match backend category (matched: backend, api, rest)"
   - `matchedTags`: ["backend", "api", "rest"]
   - `sectionTags`: ["requirements", "technical-approach", "implementation"]

**Priority Resolution**:
- If task has both "backend" and "frontend" tags, "backend" wins (higher priority)
- If no tags match, tool recommends general agent or returns error
- Priority order prevents ambiguous routing

### Customizing Agent Mappings

To add a custom agent:

1. **Create agent definition file** (see [Agent Definition Files](#agent-definition-files))

2. **Add mapping to `agent-mapping.yaml`**:
```yaml
tagMappings:
  - task_tags: [mobile, ios, android, react-native]
    agent: Mobile Developer
    section_tags: [requirements, technical-approach, implementation, platform-specific]
```

3. **Update tag priority** (if needed):
```yaml
tagPriority:
  - mobile      # Add here if mobile should take precedence
  - database
  - backend
  # ...
```

4. **Use in tasks**:
```
Task tags: ["mobile", "ios", "ui"]
→ Routes to "Mobile Developer"
→ Focuses on: requirements, technical-approach, implementation, platform-specific sections
```

**Best Practices**:
- Use consistent tag naming (lowercase, hyphenated)
- List most specific tags first in tagPriority
- Include 3-5 section tags per mapping
- Test routing with `recommend_agent` tool

---

## Feature Manager Agent (REMOVED - Use Feature Management Skill)

> ⚠️ **REMOVED IN v1.1.0**: This subagent has been **completely removed**. Use the **Feature Management Skill** instead, which achieves the same functionality with **60-82% token savings**. See [Skills Tier](#skills-tier-coordination) for details.
>
> **Migration**: Replace all Feature Manager subagent launches with Feature Management Skill invocations.
>
> **This section is kept for historical reference only and documents the old architecture.**

### Role and Responsibilities

The Feature Manager operates at the **feature level**, coordinating multi-task workflows:

**Primary Responsibilities**:
- Analyze feature progress and task completion status
- Recommend next task to work on (dependency-aware)
- Track feature-level status transitions
- Create feature-level summaries when complete

**Key Characteristic**: Interface agent between orchestrator and Task Manager. Does NOT perform implementation work.

### Feature Manager START Mode

**Purpose**: Analyze feature and recommend next task to orchestrator

**Workflow Steps**:

#### Step 1: Read Feature with Full Context
```
get_feature(id='[feature-id]',
            includeTasks=true,
            includeTaskDependencies=true,
            includeTaskCounts=true)
```

**Why Full Context**: Need to see all tasks, their status, and dependencies to make intelligent recommendation.

#### Step 2: Analyze Feature State

From Step 1 data:
- Review task completion status (pending, in-progress, completed)
- Check `taskCounts.byStatus` for progress metrics
- Calculate progress: completed / total
- Review dependency information

#### Step 3: Get Next Task Recommendation
```
get_next_task(featureId='[feature-id]', limit=3, includeDetails=true)
```

**Key Feature**: Tool automatically filters blocked tasks, so results are safe to recommend.

#### Step 4: Update Feature Status (if needed)

If feature is in "planning" and recommending first task:
```
update_feature(id='[feature-id]', status='in-development')
```

#### Step 5: Return Recommendation to Orchestrator

**Format (unblocked task available)**:
```
Feature: User Authentication
Progress: 3/8 tasks completed
Status: in-development

Next Task: Implement OAuth token refresh
Task ID: 550e8400-e29b-41d4-a716-446655440000
Priority: high
Complexity: 5
Reason: Unblocked, high priority, blocks 2 other tasks

Context: Refresh tokens expire after 30 days. Need automatic refresh
mechanism to maintain user sessions without re-authentication.

Next: Orchestrator should launch Task Manager START for this task.
```

**Format (all tasks complete)**:
```
Feature: User Authentication
Progress: 8/8 tasks completed
Status: All tasks complete

Next: Orchestrator should call Feature Manager END to complete this feature.
```

**Format (tasks blocked)**:
```
Feature: User Authentication
Progress: 6/8 tasks completed
Blocked: 2 tasks are blocked by incomplete dependencies

Blocked Tasks:
- Integration testing (blocked by: OAuth implementation)
- Load testing (blocked by: Integration testing)

Next: Review and resolve blocking dependencies before proceeding.
```

### Feature Manager END Mode

**Purpose**: Summarize completed feature work and close the feature

**What Feature Manager Receives**: Orchestrator provides summaries from all completed tasks (or references to where to find them).

**Workflow Steps**:

#### Step 1: Extract Feature-Level Insights

If task summaries provided by orchestrator, use them directly.
Otherwise:
```
get_feature(id='[feature-id]', includeTasks=true, includeSections=true)
```

**Identify**:
- Overall accomplishment (what feature delivers)
- Tasks completed (brief list)
- Files changed (consolidated across all tasks)
- Key technical decisions (architecture, design patterns, libraries)
- Testing coverage
- Integration points
- Next steps (follow-up work)

#### Step 2: Create Feature Summary Section

```
add_section(
  entityType='FEATURE',
  entityId='[feature-id]',
  title='Summary',
  usageDescription='Summary of completed feature work including tasks, files, and technical decisions',
  content='[markdown content below]',
  contentFormat='MARKDOWN',
  ordinal=999,
  tags='summary,completion'
)
```

**Summary Content Format**:
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
[Brief overview of test coverage - unit tests, integration tests, etc.]

### Integration Points
[What systems/components this feature integrates with]

### Next Steps
[Any follow-up work, related features, or technical debt noted]
```

#### Step 3: Mark Feature Complete
```
update_feature(id='[feature-id]', status='completed')
```

#### Step 4: Return Brief Summary

**Keep it concise** - Summary section has the details:
```
Feature: User Authentication
Status: Completed
Tasks: 8/8 completed
Summary section created

Implemented OAuth 2.0 authentication with token refresh, rate limiting,
and session management. All endpoints tested with 95% code coverage.
Ready for integration with frontend.
```

### Feature Manager Token Optimization

**START Mode**:
- Full feature read required (~1-2k tokens depending on task count)
- Necessary to make intelligent recommendations
- Trade-off: Spend tokens once to coordinate efficiently

**END Mode**:
- If orchestrator provides task summaries, use them directly (~2k tokens total)
- Only read feature if needed (~3-5k tokens)
- Don't re-read individual task details
- Aim for ~50% token savings vs full re-read

**Overall Efficiency**: Feature Manager enables orchestrator to work with brief summaries while maintaining full feature awareness.

---

## Task Manager Agent (REMOVED - Use Task Management Skill)

> ⚠️ **REMOVED IN v1.1.0**: This subagent has been **completely removed**. Use the **Task Management Skill** instead, which achieves the same functionality with **60-77% token savings**. See [Skills Tier](#skills-tier-coordination) for details.
>
> **Migration**: Replace all Task Manager subagent launches with Task Management Skill invocations or direct specialist calls.
>
> **This section is kept for historical reference only and documents the old architecture.**

### Task Manager Role

The Task Manager operates at the **task level**, coordinating specialist routing:

**Primary Responsibilities**:
- Read task details and call `recommend_agent` for specialist selection
- Read completed dependency summaries and include in brief
- Prepare orchestrator brief with specialist recommendation
- Extract specialist output and create task summaries
- Mark tasks complete

**Key Characteristic**: Interface agent between orchestrator and specialists. Does NOT perform implementation work.

### Task Manager START Mode

**Purpose**: Prepare task and brief orchestrator on specialist needs

**Mode Detection**: Orchestrator provides task ID only (no specialist output)

**Workflow Steps**:

#### Step 1: Read the Task
```
get_task(id='[task-id]', includeSections=true)
```

**Execute this first** to get task details, summary, tags, sections.

#### Step 2: Get Agent Recommendation (REQUIRED)
```
recommend_agent(taskId='[task-id]')
```

**CRITICAL**: Task Manager MUST execute this tool call. Do not skip or try to infer manually.

**Response Provides**:
- `agent`: Exact agent name (use EXACTLY as provided)
- `reason`: Why this agent was selected
- `matchedTags`: Which tags matched
- `sectionTags`: Which section tags specialist should focus on

#### Step 2.5: Check for Dependencies (if task has dependencies)

```
get_task_dependencies(taskId='[task-id]',
                      direction='incoming',
                      includeTaskInfo=true)
```

If task has incoming dependencies (tasks that block this one):

**For each COMPLETED dependency**:
```
get_sections(entityType='TASK',
             entityId='[dependency-task-id]',
             tags='summary')
```

**Dependency Context Strategy**:
- Only read COMPLETED dependencies (status='completed')
- Ignore pending/in-progress dependencies (specialist can't use them yet)
- Get Summary section only (concise, ~300-500 tokens)
- Include in brief to orchestrator

**Why This Matters**:
- Dependencies often contain critical context (schemas, APIs, decisions)
- Summary sections are token-efficient
- Specialist needs context to build on previous work

#### Step 3: Set Task In-Progress
```
set_status(id='[task-id]', status='in-progress')
```

#### Step 4: Return Recommendation to Orchestrator

**Format WITHOUT Dependencies**:
```
Task: Implement user login endpoint
Specialist: Backend Engineer
Reason: Task tags match backend category (matched: backend, api, rest)
Focus: requirements, technical-approach, implementation
Context: Create POST /auth/login endpoint with email/password authentication
and JWT token generation.

Next: Orchestrator should launch Backend Engineer agent to complete this task.
```

**Format WITH Dependencies**:
```
Task: Create API endpoints for user management
Specialist: Backend Engineer
Reason: Task tags match backend category (matched: backend, api, rest)
Focus: requirements, technical-approach, implementation
Context: Implement REST endpoints for user CRUD operations using the
database schema created in previous task.

Dependencies (1 completed):
─────────────────────────
Task: Create database schema for users
Status: Completed
Summary: Created Users table with id (UUID), username, email, password_hash,
created_at, updated_at. Added unique constraints on username and email.
Implemented proper indexing for email lookups.
Files: src/main/resources/db/migration/V3__create_users_table.sql
─────────────────────────

Next: Orchestrator should launch Backend Engineer agent to complete this task.
```

**Remember**: You provide the recommendation - orchestrator launches the specialist.

### Task Manager END Mode

**Purpose**: Extract specialist work and close the task

**Mode Detection**: Orchestrator provides task ID + specialist output text

**What Task Manager Receives**: The orchestrator provides the specialist's complete output.

**Workflow Steps**:

#### Step 1: Extract Key Information from Specialist Output

Read through specialist output and identify:
- What was accomplished
- Which files were changed
- What comes next
- Important technical decisions

#### Step 2: Create Summary Section

```
add_section(
  entityType='TASK',
  entityId='[task-id]',
  title='Summary',
  usageDescription='Summary of completed task including files changed and next steps',
  content='[markdown content below]',
  contentFormat='MARKDOWN',
  ordinal=0,
  tags='summary,completion'
)
```

**Summary Content Format**:
```markdown
### Completed
[What was accomplished]

### Files Changed
- `path/to/file1.kt` - [what changed]
- `path/to/file2.sql` - [what changed]

### Next Steps
[What depends on this or comes next]

### Notes
[Any important technical decisions or considerations]
```

#### Step 3: Mark Complete
```
set_status(id='[task-id]', status='completed')
```

#### Step 4: Return Brief Summary

**Format**: 2-3 sentences maximum
**Content**: Specific file names and next actions

**Examples**:

✅ "Completed database schema. Created V3__add_task_summary.sql migration and updated Task entity with summary field. Ready for MCP tool updates."

✅ "Implemented OAuth endpoints. Added UserController with login/register/refresh methods, JWT token service, and integration tests. Ready for frontend integration."

❌ "Successfully completed the task!" (too vague)

### Dependency Context Passing

**The Innovation**: Task Manager reads Summary sections from completed dependencies and includes them in the brief to the orchestrator. The orchestrator then passes this context to the specialist. This is the **key mechanism enabling 97% token reduction** - passing summaries instead of full task contexts.

**How Summary Sections Enable Dependency Context**:

1. **Task Manager END** creates a Summary section after specialist completes work
   - Title: "Summary"
   - Tags: "summary,completion"
   - Content: 300-500 tokens capturing what was done, files changed, next steps, notes
   - Stored in database as a section of the task

2. **Task Manager START** (for dependent task) reads completed dependency summaries
   - Calls `get_task_dependencies(taskId, direction='incoming')` to find blocking tasks
   - For each COMPLETED dependency, calls `get_sections(entityType='TASK', entityId='...', tags='summary')`
   - Gets 300-500 token summary instead of 5-10k token full task context
   - Includes summaries in brief to orchestrator

3. **Orchestrator** passes dependency context to specialist
   - Includes Task Manager's brief with dependency summaries
   - Specialist receives: task content + dependency summaries (not full dependency tasks)

4. **Specialist** reads task and dependency summaries
   - Has everything needed to build on previous work
   - Never needs to read full dependency task contexts
   - Can reference files, decisions, schemas from summaries

**Example Flow**:

1. **Task T1** (Database Schema): Database Engineer creates users table
   - Task Manager END creates Summary section (427 tokens):
     ```
     ### Completed
     Created Users table with authentication fields and proper indexing...

     ### Files Changed
     - `db/migration/V3__create_users_table.sql` - Users table schema
     - `src/model/User.kt` - User domain model

     ### Next Steps
     API endpoints can now use this schema for user CRUD operations

     ### Notes
     Used UUID for ID, email field indexed and unique for fast lookups
     ```

2. **Task T2** (API Endpoints): Depends on T1
   - Task Manager START reads T1's Summary section (427 tokens, not 5k+ full context)
   - Includes in brief: "Dependencies (1 completed): Task: Create database schema... [T1 Summary content]"
   - Orchestrator launches Backend Engineer with this context
   - Backend Engineer builds API using schema details from T1 summary

**Token Efficiency**:
- Reading full T1 context: ~5-10k tokens
- Reading T1 Summary section: ~300-500 tokens
- **Savings: ~95% per dependency**

**Benefits**:
- Specialist has everything needed without reading T1 directly
- Token-efficient: ~300-500 tokens vs. thousands per dependency
- Enables building on previous work seamlessly
- Works across different specialists (Database → Backend → Frontend → Test)
- Scales to multiple dependencies (3 dependencies = 900-1500 tokens, not 15-30k)

### Task Manager Token Optimization

**START Mode**:
- Must read task (~1-2k tokens unavoidable)
- Must call recommend_agent (~500 tokens)
- May read dependency summaries (~300-500 tokens each)
- Total: ~2-4k tokens typical

**END Mode**:
- Does NOT read task (specialist already did)
- Only extracts from specialist output provided by orchestrator
- Creates Summary section (~500 tokens)
- Total: ~500-1k tokens

**Overall Pattern**: Saves ~50% tokens by avoiding duplicate task reads. Specialist and Task Manager END never both read the full task.

---

## Specialist Agents

### Available Specialists

The system includes 6 specialized agents:

#### Backend Engineer
- **Tags**: backend, api, service, kotlin, rest
- **Focus**: requirements, technical-approach, implementation
- **Responsibilities**: REST APIs, services, business logic, database integration

#### Frontend Developer
- **Tags**: frontend, ui, react, vue, angular, web
- **Focus**: requirements, technical-approach, design, ux
- **Responsibilities**: UI components, state management, API integration

#### Database Engineer
- **Tags**: database, migration, schema, sql, flyway
- **Focus**: requirements, technical-approach, data-model
- **Responsibilities**: Schemas, migrations, query optimization, indexing

#### Test Engineer
- **Tags**: testing, test, qa, quality, coverage
- **Focus**: requirements, testing-strategy, acceptance-criteria
- **Responsibilities**: Unit tests, integration tests, test automation, coverage

#### Technical Writer
- **Tags**: documentation, docs, user-docs, api-docs, guide, readme
- **Focus**: requirements, context, documentation
- **Responsibilities**: API docs, user guides, README files, code comments

#### Planning Specialist
- **Tags**: planning, requirements, specification, architecture, design
- **Focus**: context, requirements, technical-approach
- **Responsibilities**: Requirements analysis, architecture, design decisions

### Specialist Workflow Pattern (Direct Orchestration)

**NEW IN v1.1.0**: Specialists now work **directly with orchestrator** without Task Manager middleware. This reduces token overhead by 40-50% per task.

All specialists follow this **9-step workflow**:

#### Step 1: Read the Task
```
get_task(id='[task-id]', includeSections=true)
```

Specialists receive task ID from orchestrator and read task details themselves.

#### Step 2: Read Dependencies (Self-Service)

**NEW**: Specialists now read their own dependencies instead of receiving them from Task Manager.

```
# Check for dependencies
get_task_dependencies(taskId='[task-id]', direction='incoming', includeTaskInfo=true)

# For each COMPLETED dependency, read its Summary section
get_sections(entityType='TASK', entityId='[dep-id]', tags='summary')
```

**Why**: Eliminates Task Manager middleware, reduces token overhead, gives specialists direct access to dependency context.

#### Step 3: Do the Work

Perform specialized implementation:
- Write code (Backend, Frontend, Database)
- Write tests (Test Engineer)
- Write documentation (Technical Writer)
- Create plans (Planning Specialist)

Use file tools:
- `Read` - Read existing files
- `Edit` - Make precise changes
- `Write` - Create new files
- `Bash` - Run commands
- `Grep` - Search codebase
- `Glob` - Find files

#### Step 4: Update Task Sections

Update sections with results:
```
update_section_text(id='[section-id]',
                    oldText='[placeholder]',
                    newText='[actual content]')
```

Or add new sections:
```
add_section(entityType='TASK',
            entityId='[task-id]',
            title='Implementation Notes',
            content='[details]',
            ordinal=10)
```

#### Step 5: Populate Summary Section

**CRITICAL**: Create a Summary section with standardized format including **Files Changed** subsection:

```
add_section(
  entityType='TASK',
  entityId='[task-id]',
  title='Summary',
  usageDescription='Summary of completed task including files changed and next steps',
  content='[markdown below]',
  contentFormat='MARKDOWN',
  ordinal=0,
  tags='summary,completion'
)
```

**Summary Content Format**:
```markdown
### Completed
[What was accomplished in 2-3 sentences]

### Files Changed
- `path/to/file1.kt` - [what changed]
- `path/to/file2.sql` - [what changed]
- `path/to/test.kt` - [what changed]

### Next Steps
[What depends on this or comes next - 1-2 sentences]

### Notes
[Important technical decisions or considerations - 1-2 sentences]
```

**Why Files Changed Section Matters**:
- Provides context for dependent tasks without re-reading full implementation
- Enables specialists to understand what was built without file inspection
- ~200-300 token summary vs 2-5k tokens of reading all changed files
- Essential for dependency chain efficiency

#### Step 6: Mark Task Complete
```
set_status(id='[task-id]', status='completed')
```

**NEW**: Specialists now mark tasks complete themselves (previously Task Manager END did this).

#### Step 7: Return Minimal Output

**NEW FORMAT**: Return only success indicator or blocker information.

**Success Format** (2-3 sentences maximum):
```
✅ Completed: [Task title]
Files: [file1.kt, file2.kt, file3.kt]
Ready: [What's unblocked or ready next]
```

**Blocked Format** (if cannot complete):
```
⚠️ Blocked: [Reason]
Issue: [Specific blocker]
Requires: [What's needed to proceed]
```

**Examples**:

✅ **Good Response**:
```
✅ Completed: Implement user login endpoint
Files: UserController.kt, AuthenticationService.kt, UserControllerTest.kt
Ready: Frontend can integrate with POST /auth/login endpoint
```

✅ **Good Blocked Response**:
```
⚠️ Blocked: Database schema not ready
Issue: Users table doesn't exist yet (Task T1 not complete)
Requires: Complete T1 (Create database schema) before implementing API
```

❌ **Bad Response** (too verbose):
```
Successfully implemented the user login endpoint! I created UserController.kt with the login method that accepts email and password. The endpoint returns a JWT token upon successful authentication. I also wrote comprehensive tests covering all edge cases including invalid credentials, missing fields, and successful login scenarios. The authentication service uses bcrypt for password hashing...
[200 more lines]
```

**Why Minimal Output**:
- Orchestrator only needs success confirmation + file list
- Detailed work captured in task sections and Summary
- Reduces orchestrator context growth by ~90%
- Enables scaling to hundreds of tasks

#### Step 8: Files Changed Context Passing

The **Files Changed** section in the Summary enables efficient context passing in dependency chains:

**Example Dependency Chain**:

Task T1 (Database Schema):
```markdown
### Files Changed
- `db/migration/V3__create_users_table.sql` - Users table with auth fields
- `src/model/User.kt` - User domain model
```

Task T2 (depends on T1) reads T1's Summary:
- Knows exactly which files were created
- Understands schema structure without reading full SQL
- Can reference User model without inspecting code
- ~300 tokens vs ~2000 tokens reading all files

#### Step 9: Self-Manage Lifecycle

**NEW**: Specialists are responsible for their complete lifecycle:

1. ✅ Read task and dependencies (self-service)
2. ✅ Perform work
3. ✅ Update sections with results
4. ✅ Create Summary with Files Changed
5. ✅ Mark task complete
6. ✅ Return minimal output

**No Task Manager involvement** - Specialists are autonomous.

### Section Tag Filtering

**Recommended Pattern**: Specialists should focus on sections tagged with their `sectionTags` from `recommend_agent`.

**Example**:
```
recommend_agent returns: sectionTags: ["requirements", "technical-approach", "implementation"]

Specialist reads task:
get_task(id='...', includeSections=true)

Specialist filters sections:
- Priority 1: requirements, technical-approach, implementation
- Priority 2: context, testing-strategy
- Priority 3: Other sections as needed
```

**Benefits**:
- Focuses specialist attention on relevant content
- Reduces token usage by prioritizing key sections
- Enables faster specialist work

**Implementation**:
```
get_sections(entityType='TASK',
             entityId='[task-id]',
             tags='requirements,technical-approach,implementation')
```

---

## Complete Workflow Examples

### Single Task Workflow (Direct Orchestration)

**NEW IN v1.1.0**: Simplified workflow with direct specialist invocation.

**Scenario**: Implement a single task with no dependencies

**Workflow**:

```
1. Orchestrator uses get_next_task or recommend_agent
   - Identifies task: "Implement login endpoint"
   - Identifies specialist: "Backend Engineer"
   - Token cost: ~300 tokens (could be a Skill)

2. Orchestrator → Backend Engineer (DIRECT)
   - Reads task with sections
   - Reads dependencies (self-service, none in this case)
   - Implements login endpoint
   - Writes UserController.kt, authentication service, tests
   - Creates Summary section with Files Changed
   - Marks task complete
   - Returns: "✅ Completed: Implement login endpoint. Files: UserController.kt, AuthenticationService.kt, UserControllerTest.kt. Ready: Frontend integration."

3. Orchestrator continues with next task
   - Stores brief (~200 tokens)
   - Can query feature status with Feature Management Skill
```

**Token Usage** (per task):
- Routing (Skill or direct tool call): ~300 tokens
- Backend Engineer: ~2.5k tokens (includes task read, dependency check, implementation, completion)
- **Total: ~2.8k tokens per task**

**Context Accumulation**:
- Orchestrator context grows by: ~200 tokens (brief summary only)
- **vs Old Approach**: 7k tokens (Feature Manager + Task Manager + Specialist)
- **Savings: 60% total token reduction, 97% orchestrator context reduction**

**Key Changes from Old Approach**:
- ❌ No Feature Manager START (replaced by Skill or direct tool)
- ❌ No Task Manager START (specialist reads task directly)
- ❌ No Task Manager END (specialist creates summary and marks complete)
- ✅ Specialist handles full lifecycle autonomously
- ✅ 40-50% fewer tokens per task

### Feature with Dependency Chain (Direct Orchestration)

**NEW IN v1.1.0**: Streamlined workflow with specialists reading their own dependencies.

**Scenario**: Feature with 3 tasks in sequence (T1 → T2 → T3)

**Tasks**:
- T1: Create database schema (Database Engineer)
- T2: Implement API endpoints (Backend Engineer) - depends on T1
- T3: Add integration tests (Test Engineer) - depends on T2

**Workflow**:

```
ITERATION 1: Task T1
───────────────────────
1. Orchestrator identifies next task (Skill or direct tool)
   - get_next_task(featureId='...') → T1
   - Token cost: ~300 tokens

2. Orchestrator → Database Engineer (DIRECT)
   - Reads T1 task
   - No dependencies (first task)
   - Creates migration: V3__create_users_table.sql
   - Creates User.kt model
   - Creates Summary section:
     ### Files Changed
     - `db/migration/V3__create_users_table.sql` - Users table with auth fields
     - `src/model/User.kt` - User domain model
   - Marks T1 complete
   - Returns: "✅ Completed: Create database schema. Files: V3__create_users_table.sql, User.kt. Ready: API implementation."

ITERATION 2: Task T2 (Depends on T1)
───────────────────────
3. Orchestrator identifies next task
   - get_next_task(featureId='...') → T2
   - Token cost: ~300 tokens

4. Orchestrator → Backend Engineer (DIRECT)
   - Reads T2 task
   - SELF-SERVICE: Checks dependencies via get_task_dependencies(T2)
   - SELF-SERVICE: Reads T1 Summary section (300 tokens)
     - Sees: Users table schema, User.kt model
   - Implements UserController.kt using T1's User model
   - Creates Summary section:
     ### Files Changed
     - `src/controller/UserController.kt` - CRUD endpoints for users
     - `src/service/UserService.kt` - Business logic layer
     - `src/test/UserControllerTest.kt` - Unit tests
   - Marks T2 complete
   - Returns: "✅ Completed: Implement API endpoints. Files: UserController.kt, UserService.kt, tests. Ready: Integration testing."

ITERATION 3: Task T3 (Depends on T2)
───────────────────────
5. Orchestrator identifies next task
   - get_next_task(featureId='...') → T3
   - Token cost: ~300 tokens

6. Orchestrator → Test Engineer (DIRECT)
   - Reads T3 task
   - SELF-SERVICE: Checks dependencies via get_task_dependencies(T3)
   - SELF-SERVICE: Reads T2 Summary section (300 tokens)
     - Sees: UserController endpoints, UserService methods
   - Creates integration tests for CRUD endpoints
   - Creates Summary section:
     ### Files Changed
     - `src/test/integration/UserIntegrationTest.kt` - End-to-end API tests
   - Marks T3 complete
   - Returns: "✅ Completed: Add integration tests. Files: UserIntegrationTest.kt. Ready: Feature complete."

FEATURE COMPLETION
───────────────────────
7. Orchestrator checks feature status (Skill)
   - Feature Management Skill: All tasks complete
   - Creates feature summary
   - Marks feature complete
   - Token cost: ~700 tokens
```

**Token Usage** (approximate for entire feature):
- Task routing (3 iterations): 300 × 3 = ~900 tokens
- Task T1 (Database Engineer): ~2.5k tokens
- Task T2 (Backend Engineer): ~2.8k tokens (includes reading T1 summary)
- Task T3 (Test Engineer): ~2.8k tokens (includes reading T2 summary)
- Feature completion (Skill): ~700 tokens
- **Total spent: ~9.7k tokens**

**Orchestrator Context Growth**:
- Orchestrator receives only briefs: 3 × ~200 = ~600 tokens
- **vs Old Approach**: ~30k tokens total, ~600 tokens orchestrator
- **Savings: 68% total token reduction** (~30k → ~9.7k)

**Key Changes from Old Approach**:
- ❌ No Feature Manager START iterations (replaced by single Skill call)
- ❌ No Task Manager START/END for each task (specialists handle lifecycle)
- ✅ Specialists read dependencies themselves (self-service)
- ✅ Summary sections with Files Changed enable efficient context passing
- ✅ Minimal orchestrator involvement (routing + brief storage)

**Key Benefits**:
- Specialists autonomously manage their lifecycle
- Self-service dependency reading (no middleware)
- Files Changed section provides ~200-300 token context vs 2-5k reading files
- 68% token reduction vs old approach
- Orchestrator context still minimal (~600 tokens for 3 tasks)

### Parallel Work Opportunities

**Scenario**: Feature with 5 tasks, some can run in parallel

**Task Dependency Graph**:
```
T1 (Database Schema) ────┐
                         ├──→ T3 (API Endpoints) ──→ T5 (Integration Tests)
T2 (Authentication) ─────┘            ↓
                                      T4 (UI Components)
```

**Parallel Opportunities**:
- T1 and T2 can run in parallel (no dependencies)
- After T1 and T2 complete, T3 can start
- After T3 completes, T4 and T5 can run in parallel

**Orchestrator Strategy**:

```
ITERATION 1: Start T1 and T2 in parallel
───────────────────────────────────────
Feature Manager → Recommends T1 (both T1 and T2 are unblocked)
User decides to also start T2

Orchestrator launches:
- T1 workflow (Database Engineer)
- T2 workflow (Backend Engineer)

Both complete independently, create Summaries

ITERATION 2: Start T3 (depends on T1 and T2)
───────────────────────────────────────
Feature Manager → Recommends T3
Task Manager START (T3) → Reads T1 and T2 Summaries
Backend Engineer → Uses T1 schema and T2 auth
T3 completes

ITERATION 3: Start T4 and T5 in parallel
───────────────────────────────────────
Feature Manager → Recommends T4
User decides to also start T5

Orchestrator launches:
- T4 workflow (Frontend Developer, uses T3 Summary)
- T5 workflow (Test Engineer, uses T3 Summary)

Both complete independently
```

**Collision Prevention**: Built-in locking prevents conflicts when multiple specialists try to update the same task simultaneously.

### Multi-Feature Project

**Scenario**: Project with 3 features, features can run in parallel

**Project Structure**:
```
Project: E-commerce Platform
├── Feature 1: User Authentication (5 tasks)
├── Feature 2: Product Catalog (7 tasks)
└── Feature 3: Shopping Cart (6 tasks)
```

**Parallel Feature Development**:

```
FEATURE 1 WORKFLOW (User Authentication)
──────────────────────────────────────
Orchestrator works with Feature Manager for Feature 1:
- Iterate through 5 tasks
- Create feature Summary
- Mark Feature 1 complete

FEATURE 2 WORKFLOW (Product Catalog) - Can run in parallel
──────────────────────────────────────
Orchestrator (or different orchestrator) works with Feature Manager for Feature 2:
- Iterate through 7 tasks
- Create feature Summary
- Mark Feature 2 complete

FEATURE 3 WORKFLOW (Shopping Cart) - Might depend on Feature 1 and 2
──────────────────────────────────────
After Features 1 and 2 complete:
- Orchestrator works with Feature Manager for Feature 3
- Tasks can reference Feature 1 and 2 Summaries
- Iterate through 6 tasks
- Create feature Summary
- Mark Feature 3 complete
```

**Cross-Feature Dependencies**: If Feature 3 tasks depend on Feature 1 or 2 work, task summaries from those features can be passed as context.

---

## Token Efficiency and Scaling

### Architecture Evolution: Token Savings Summary

**v1.0 (Feature Manager + Task Manager + Specialist)**:
- Per task: ~7k tokens (1.5k FM + 2k TM START + 3k Specialist + 0.5k TM END)
- Orchestrator growth: ~200 tokens per task (briefs only)
- Feature with 8 tasks: ~56k tokens total

**v1.1 (Direct Orchestration - NEW)**:
- Per task: ~2.8k tokens (0.3k routing + 2.5k Specialist with self-service)
- Orchestrator growth: ~200 tokens per task (briefs only)
- Feature with 8 tasks: ~22.4k tokens total
- **Savings: 60% token reduction** (~56k → ~22.4k)

**What Changed**:
- ❌ Removed Feature Manager START iterations (replaced by single Skill call per feature)
- ❌ Removed Task Manager START (specialists read tasks directly)
- ❌ Removed Task Manager END (specialists create summaries and mark complete)
- ✅ Specialists handle full lifecycle (read task + dependencies, work, summarize, complete)
- ✅ Self-service dependency reading (specialists read Summary sections directly)
- ✅ Files Changed section in Summary enables efficient context passing (200-300 tokens vs 2-5k)

**Token Efficiency Comparison**:

| Operation | v1.0 (Old) | v1.1 (New) | Savings |
|-----------|------------|------------|---------|
| Feature coordination | 1500 tokens (FM START) | 300 tokens (Skill) | 80% |
| Task routing | 2000 tokens (TM START) | Included in specialist | 100% |
| Task completion | 500 tokens (TM END) | Included in specialist | 100% |
| Specialist work | 3000 tokens | 2500 tokens (self-service deps) | 17% |
| **Per Task Total** | **7000 tokens** | **2800 tokens** | **60%** |

**Why This Scales Better**:
- Feature Manager/Task Manager middleware eliminated
- Specialists more autonomous (read own dependencies)
- Orchestrator only does routing + brief storage
- Summary sections with Files Changed enable token-efficient context passing

### Context Isolation

**The Problem with Shared Context**:
- Single orchestrator accumulates all conversation history
- Every tool call and response adds to context
- Context grows linearly with work done
- Eventually hits context limits

**How Context Isolation Solves This**:

```
SHARED CONTEXT MODEL (Traditional):
─────────────────────────────────────────────────────────────────
Orchestrator accumulates ALL work:
- Task 1: Full implementation, code, tests, documentation = 5k tokens
- Task 2: Full implementation, code, tests, documentation = 8k tokens
- Task 3: Full implementation, code, tests, documentation = 7k tokens
- Task 4: Full implementation, code, tests, documentation = 9k tokens
- Task 5: Full implementation, code, tests, documentation = 6k tokens

Total Orchestrator Context: 35k tokens (grows linearly with each task)
Context never cleared, always growing


HYBRID MODEL v1.1 (Direct Orchestration + Skills + Hooks):
─────────────────────────────────────────────────────────────────
Orchestrator accumulates ONLY briefs:
- Task 1 brief: "✅ Completed schema. Files: V3 migration, User.kt..." = 200 tokens
- Task 2 brief: "✅ Implemented API. Files: UserController, tests..." = 200 tokens
- Task 3 brief: "✅ Added tests. Files: UserControllerTest..." = 200 tokens
- Task 4 brief: "✅ Documented API. Files: api-docs.md..." = 200 tokens
- Task 5 brief: "✅ Deployed. Files: deployment scripts..." = 200 tokens

Total Orchestrator Context: 1k tokens (200 tokens per task, not 5-10k)

Token Usage Per Task (v1.1 Hybrid):
Routing (Skills or direct):
- get_next_task or Feature Management Skill: ~300 tokens per task

Implementation (Specialist Subagent with self-service):
- Specialist reads task: ~500 tokens
- Specialist reads dependencies (self-service): ~300 tokens
- Specialist implements: ~1500 tokens
- Specialist creates Summary with Files Changed: ~200 tokens
- Specialist marks complete: ~100 tokens
Total Specialist: ~2500 tokens → discarded after completion

Automation (Hooks):
- Git commit after completion: 0 tokens (bash script)
- Test execution before completion: 0 tokens (bash script)

Total Per Task: ~2800 tokens spent (300 routing + 2500 specialist)
Orchestrator Accumulation: ~200 tokens brief only

Contexts are isolated and discarded after each operation!

v1.0 vs v1.1 Comparison:
- v1.0: 4200 tokens/task (1200 coordination + 3000 specialist)
- v1.1: 2800 tokens/task (300 routing + 2500 specialist)
- Savings: 33% reduction through middleware elimination


COMPARISON (Orchestrator Context Growth):
─────────────────────────────────────────────────────────────────
             │ Traditional │ v1.0 Subagent │ v1.1 Direct │ v1.1 vs v1.0
─────────────┼─────────────┼───────────────┼─────────────┼──────────────
Task 1       │     5k      │    200        │     200     │   Same
Task 1+2     │    13k      │    400        │     400     │   Same
Task 1+2+3   │    20k      │    600        │     600     │   Same
Task 1+2+3+4 │    29k      │    800        │     800     │   Same
All 5 tasks  │    35k      │   1,000       │    1,000    │   Same

Orchestrator Context Growth: Identical across v1.0 and v1.1 (200 tokens per task)
v1.1 optimizes TOTAL TOKENS SPENT through middleware elimination.


COMPARISON (Total Tokens Spent Per Task):
─────────────────────────────────────────────────────────────────
Task Operation         │ v1.0 Subagent │ v1.1 Direct │ Savings
───────────────────────┼───────────────┼─────────────┼─────────
Feature coordination   │   ~1400       │    ~300     │  78%
Task routing           │   ~1300       │  (included) │ 100%
Task completion        │   ~1500       │  (included) │ 100%
Specialist work        │   ~3000       │   ~2500     │  17%
Git commit (Hook)      │      0        │      0      │   0%
Test execution (Hook)  │      0        │      0      │   0%
───────────────────────┼───────────────┼─────────────┼─────────
Per Task Total         │   ~7200       │   ~2800     │  61%

**Key Insight**: v1.1 reduces TOTAL TOKEN COST by 61% vs v1.0 while maintaining
same orchestrator context growth. Savings come from:
- Feature Manager START eliminated (Skill does routing: 78% cheaper)
- Task Manager START eliminated (specialist reads task directly: 100% eliminated)
- Task Manager END eliminated (specialist creates summary: 100% eliminated)
- Self-service dependency reading (specialist reads summaries directly: 17% savings)
```

**Why This Matters**:
- Orchestrator context remains manageable indefinitely
- Can handle unlimited tasks without context growth
- Sub-agents work with fresh, focused context
- Enables long-running projects

### Summary Sections

**The Knowledge Transfer Problem**:
- Task T2 needs to know what Task T1 did
- Traditional: Read all of T1 (5-10k tokens)
- Summary Section: Read T1 Summary (300-500 tokens)

**Summary Section Format**:
```markdown
### Completed
Created Users table with authentication fields and indexes

### Files Changed
- `db/migration/V3__create_users_table.sql` - Users table schema
- `src/model/User.kt` - User domain model

### Next Steps
API endpoints can use this schema for user CRUD operations

### Notes
Used UUID for ID, bcrypt for password hashing, added email uniqueness constraint
```

**Token Efficiency**:
- Full task context: 5-10k tokens (all sections, implementation details, code)
- Summary section: 300-500 tokens (what was done, files changed, next steps, notes)
- **Savings: 93-97%** (summary is 3-7% of full context)

**Quality vs. Efficiency**:
- Summary preserves essential knowledge
- Enough context for dependent work
- Detailed work remains in task sections
- Can always read full task if needed

### Bookends Pattern

**START and END modes minimize token usage**:

**Task Manager START**:
- Must read task (unavoidable ~2k tokens)
- Must call recommend_agent (~500 tokens)
- May read dependency summaries (~300-500 tokens each)
- Returns brief to orchestrator (~200 tokens)

**Specialist**:
- Receives brief from orchestrator (~500 tokens including dependency context)
- Reads task (~2k tokens)
- Performs work
- Returns brief (~200 tokens)

**Task Manager END**:
- Receives specialist brief from orchestrator (~200 tokens)
- Does NOT read task (specialist already did)
- Extracts key info, creates Summary (~500 tokens)
- Returns brief (~200 tokens)

**Token Savings**:
- Task Manager START + END: ~3k tokens
- Specialist: ~2.5k tokens
- **Total: ~5.5k tokens**
- **vs. Single agent reading task 3 times: ~6k tokens**

The pattern prevents duplicate task reads while ensuring proper handoff.

### Why This Architecture Scales

**Linear Scaling**:
- Each task costs ~7k tokens total (Feature Manager, Task Manager, Specialist)
- Orchestrator context grows by ~200 tokens per task (brief only)
- 100 tasks = ~700k tokens spent total, but only ~20k in orchestrator context
- Can handle thousands of tasks without context overflow

**Parallel Scaling**:
- Multiple specialists can work simultaneously
- Each specialist has isolated context
- No context sharing or conflicts
- Near-linear speedup with parallelism

**Feature Scaling**:
- Features can be worked on in parallel
- Each feature has independent Feature Manager invocations
- Feature Summaries enable cross-feature dependencies
- Project size doesn't affect per-feature cost

**Comparison**:
```
SHARED CONTEXT: O(n) context growth
- 10 tasks: ~100k orchestrator context
- 100 tasks: ~1M orchestrator context (impossible)

AGENT ORCHESTRATION: O(1) context growth
- 10 tasks: ~2k orchestrator context
- 100 tasks: ~20k orchestrator context (manageable)
- 1000 tasks: ~200k orchestrator context (still viable)
```

---

## Setup and Configuration

### Agent Definition Files

Agent definitions are stored in two locations:

**User-Editable**: `.claude/agents/*.md`
- Feature Manager, Task Manager, and all specialists
- Users can edit these directly
- Changes take effect immediately

**Embedded (Docker)**: `src/main/resources/agents/claude/*.md`
- Copies of agent definitions packaged in JAR
- Used when running in Docker
- Ensures agents available in all environments

**Agent Definition Format**:

```markdown
---
name: Backend Engineer
description: Specialized in backend API development
tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__add_section, Read, Edit, Write
model: sonnet
---

# Backend Engineer Agent

You are a backend specialist focused on REST APIs, services, and business logic.

## Workflow (Follow this order)

1. **Read the task**: `get_task(id='...', includeSections=true)`
2. **Do your work**: Write services, APIs, business logic, tests
3. **Update task sections** with your results
4. **Mark complete**: `set_status(id='...', status='completed')`
5. **Return brief summary** (2-3 sentences)

[Additional guidance...]
```

### Setup Tool

⚠️ **REQUIRED FIRST STEP**: You must run the setup tool before using the agent orchestration system.

**Tool**: `setup_claude_orchestration`

**Purpose**: Creates `.claude/agents/` directory with all agent definitions required for Claude Code sub-agent support.

**When to Run**:
- ✅ **New project setup**: First time using Task Orchestrator with Claude Code
- ✅ **After cloning repository**: If `.claude/agents/` directory doesn't exist
- ✅ **New Claude Code integration**: When connecting Task Orchestrator to a new Claude Code instance
- ✅ **Agent definition updates**: To restore default agent definitions

**How to Run**:

```
User: "Setup Claude Code agents"

AI Response:
1. Calls setup_claude_orchestration() tool
2. Tool creates .claude/agents/ directory
3. Tool writes 10 agent definition files:
   - feature-manager.md
   - task-manager.md
   - backend-engineer.md
   - frontend-developer.md
   - database-engineer.md
   - test-engineer.md
   - technical-writer.md
   - planning-specialist.md
   - feature-architect.md
   - bug-triage-specialist.md
4. Reports: "Claude Code agents installed. You can now use Feature Manager and Task Manager workflows."
```

**Example Tool Call**:
```json
{
  "tool": "setup_claude_orchestration",
  "parameters": {}
}
```

**Alternative user commands**:
```
User: "Run setup_claude_orchestration"
User: "Install Claude agents"
User: "Setup sub-agents for Claude Code"
```

**What It Creates**:

**Coordination Agents** (Level 1-2):
- `.claude/agents/feature-manager.md` - Feature-level coordination (Level 1)
- `.claude/agents/task-manager.md` - Task-level coordination and routing (Level 2)

**Specialist Agents** (Level 3):
- `.claude/agents/backend-engineer.md` - Backend/API development, services, business logic
- `.claude/agents/frontend-developer.md` - Frontend/UI development, React/Vue components
- `.claude/agents/database-engineer.md` - Database schemas, migrations, SQL
- `.claude/agents/test-engineer.md` - Testing, QA, test automation
- `.claude/agents/technical-writer.md` - Documentation, API docs, user guides
- `.claude/agents/planning-specialist.md` - Requirements analysis, architecture, planning

**Utility Agents**:
- `.claude/agents/feature-architect.md` - Feature design and breakdown (pre-creation)
- `.claude/agents/bug-triage-specialist.md` - Bug investigation and triage

**Important Notes**:
- This tool is **idempotent** - safe to run multiple times
- Will **not overwrite** existing files (preserves your customizations)
- Only creates files that don't already exist
- The `.claude/agents/` directory is used by Claude Code to discover and load sub-agents
- **Without this setup, Claude Code sub-agents will not be available**

### Re-initialization and Upgrades

**What happens when running the initialization workflow on an existing project?**

The `initialize_task_orchestrator` workflow now includes smart re-initialization detection. When you run the workflow on a project that's already initialized, you'll see upgrade options instead of a fresh installation.

#### Re-initialization Modes

**Detection**: The workflow automatically detects existing Task Orchestrator initialization by checking for the "## Task Orchestrator - AI Initialization" section in your project's documentation file.

When detected, you'll see these options:

```
🔄 Task Orchestrator Already Initialized

Current: Last initialized 2025-10-10
Version: 1.0.0
Latest: Task Orchestrator 1.1.0-beta

What would you like to do?

[1] Refresh Guidelines
    • Update "AI Initialization" section with latest patterns
    • Preserve customizations outside this section
    • Update timestamp and version
    • Recommended for minor updates

[2] Install New Features
    • Check for newly available features (hooks, sub-agents)
    • Install only features not yet configured
    • Skip already-installed features
    • Recommended when new features added to MCP

[3] Full Re-initialization
    • Rewrite entire "AI Initialization" section
    • Re-offer all optional features (hooks, sub-agents)
    • Detect and preserve existing installations
    • Recommended after major version upgrades

[4] Cancel
    • Keep existing configuration unchanged
```

#### Mode Details

**[1] Refresh Guidelines** (Quick update):
- Updates only the "Critical Patterns" subsection with latest workflow patterns
- Preserves installed features (hooks, sub-agents remain configured)
- Updates version field to latest
- Updates timestamp
- **Use when**: After minor MCP updates or pattern improvements

**[2] Install New Features** (Feature additions):
- Detects what features are available vs. installed
- Only offers features not yet configured
- Smart detection checks both Features field and actual files:
  - Hooks: Checks `.claude/settings.local.json` for Task Orchestrator configuration
  - Sub-agents: Checks for `.claude/agents/` directory
- Handles sync issues (e.g., Features field says "hooks" but not actually installed)
- **Use when**: New features added to MCP (hooks, new sub-agents, etc.)

**[3] Full Re-initialization** (Complete rewrite):
- Rewrites entire "AI Initialization" section
- Re-offers all optional features (hooks and sub-agents)
- Smart detection prevents duplicate installations
- Updates all fields (timestamp, version, features)
- **Use when**: Major version upgrades or significant changes

**[4] Cancel**:
- Exits without making any changes
- Use if initialization looks correct and no updates needed

#### Version Tracking

The initialization section tracks two key pieces of information:

```markdown
## Task Orchestrator - AI Initialization

Last initialized: YYYY-MM-DD
Version: 1.1.0-beta
Features: [none|hooks|subagents|hooks,subagents]
```

- **Last initialized**: Date of last initialization or update
- **Version**: MCP Task Orchestrator version used
- **Features**: Comma-separated list of installed optional features

This tracking enables smart detection and prevents duplicate installations.

#### Feature Sync Validation

The workflow performs dual verification to ensure consistency:

1. **Check Features field**: Read what features are declared as installed
2. **Check actual files**: Verify the features actually exist
3. **Alert on mismatch**: If Features field doesn't match reality

**Example sync issue**:
```
⚠️  Features field outdated!
Listed: "hooks,subagents"
Found: Only subagents (hooks not in .claude/settings.local.json)

Options:
[1] Install hooks
[2] Update field to remove 'hooks'
```

#### Migration After Upgrades

**Scenario**: You upgrade from Task Orchestrator 1.0.0 to 1.1.0-beta

**Recommended workflow**:
1. Pull latest Docker image or rebuild JAR
2. Restart your MCP server
3. Run: "Initialize Task Orchestrator"
4. Choose option [2] "Install New Features"
5. Review new features (e.g., hooks support added in 1.1.0)
6. Install desired features

**Result**: You get new features without rewriting your entire configuration.

#### Best Practices

- **Run periodically**: Check for updates by running initialization workflow
- **Review changes**: Before choosing Full Re-initialization, consider Refresh Guidelines first
- **Track versions**: Note which version you're running in initialization section
- **Backup customizations**: If you've heavily customized, backup before Full Re-initialization
- **Test new features**: Use Install New Features mode to try new capabilities

### Workflow Automation Hooks (Optional)

**What are hooks?**: Claude Code hooks are automated scripts that execute at specific points during your workflow (before/after tool calls, session start, etc.).

**Task Orchestrator hooks** provide workflow automation:
- **SessionStart**: Automatically runs `get_overview()` to load project context at every session start
- **PreToolUse**: Reminds you to discover templates when creating tasks/features without templateIds

**Installation**: Hooks are offered during the `initialize_task_orchestrator` workflow when `.claude/` directory is detected.

**Manual Installation**:

1. Edit or create `.claude/settings.local.json` in your project root
2. Add Task Orchestrator hooks configuration:

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
    "PreToolUse": [{
      "matcher": "mcp__task-orchestrator__create_task|mcp__task-orchestrator__create_feature",
      "hooks": [{
        "type": "command",
        "command": "bash",
        "args": ["-c", "if ! echo \"$TOOL_INPUT\" | grep -q '\\\"templateIds\\\"'; then echo '{\\\"message\\\": \\\"💡 Tip: Consider running list_templates() to discover available templates.\\\"}'; fi"]
      }]
    }]
  }
}
```

**What these hooks do**:
- **SessionStart hook**: Automatically injects a reminder to run `get_overview()` at the start of every Claude Code session, ensuring you always have current project context
- **PreToolUse hook**: When creating tasks or features, checks if templates were applied and provides a friendly reminder if not

**Removal**: To disable hooks, edit or delete the `"hooks"` section from `.claude/settings.local.json`

**Benefits**:
- Eliminates forgetting to run `get_overview()` at session start
- Prevents the #1 mistake (forgetting template discovery)
- Non-intrusive reminders, not blocking enforcement
- Fully optional and easily removable

**Compatibility**:
- Hooks work alongside workflows (they automate best practices, don't replace guidance)
- Templates and workflows function with or without hooks
- Sub-agents work independently of hooks

### Customization Options

#### Adding a New Specialist

1. **Create agent definition file**: `.claude/agents/mobile-developer.md`
```markdown
---
name: Mobile Developer
description: Specialized in mobile app development
tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__add_section, Read, Edit, Write
model: sonnet
---

# Mobile Developer Agent

You are a mobile specialist focused on iOS, Android, and React Native.

[Workflow and guidance...]
```

2. **Add to agent-mapping.yaml**:
```yaml
tagMappings:
  - task_tags: [mobile, ios, android, react-native]
    agent: Mobile Developer
    section_tags: [requirements, technical-approach, implementation, platform-specific]
```

3. **Test with recommend_agent**:
```
Task with tags: ["mobile", "ios", "ui"]
→ recommend_agent returns: "Mobile Developer"
```

#### Modifying Existing Agents

Edit agent definition files in `.claude/agents/`:

**Example**: Add specific guidance to Backend Engineer

```markdown
## Coding Standards

- Use Kotlin coroutines for async operations
- Follow Clean Architecture patterns
- Write comprehensive unit tests (90%+ coverage)
- Document all public APIs with KDoc

## Project-Specific Patterns

- Use Exposed ORM for database access
- Follow repository pattern
- Return Result<T> for error handling
```

Changes take effect immediately for new agent invocations.

#### Changing Agent-Mapping Priority

Edit `src/main/resources/agents/agent-mapping.yaml`:

```yaml
# Prioritize mobile over backend for ambiguous tasks
tagPriority:
  - mobile       # Check mobile tags first
  - database     # Then database
  - backend      # Then backend
  - frontend
  - testing
  - documentation
  - planning
```

**Impact**: Tasks with both "mobile" and "backend" tags will route to Mobile Developer.

---

## Best Practices

### For Orchestrators (Main Claude Instance)

1. **Always use Feature Manager for multi-task features**
   - Let Feature Manager coordinate task order
   - Don't try to manage task sequence manually
   - Trust dependency-aware recommendations

2. **Pass full dependency context to specialists**
   - Task Manager provides brief with dependency summaries
   - Include this context when launching specialist
   - Don't abbreviate or summarize further

3. **Use briefs to maintain clean context**
   - Only keep brief summaries in your context
   - Don't internalize full specialist responses
   - Let Summary sections preserve detailed knowledge

4. **Launch agents sequentially unless parallel is safe**
   - Wait for task completion before starting dependent task
   - Parallel execution only for truly independent tasks
   - Built-in locking prevents conflicts but not logic errors

5. **Check feature status regularly**
   - Use Feature Manager START periodically
   - Understand progress and blockers
   - Adjust plans based on recommendations

### For Sub-Agents (Feature Manager, Task Manager, Specialists)

1. **Feature Manager: Trust get_next_task**
   - Don't manually select from task list
   - Tool handles dependency filtering automatically
   - Recommend exactly what tool suggests

2. **Task Manager: Always call recommend_agent**
   - Never infer specialist manually
   - Use exact agent name from response
   - Include section tags in brief

3. **Task Manager: Read all completed dependency summaries**
   - Don't skip dependencies
   - Include full context in brief
   - Better to over-inform than under-inform

4. **Specialists: Keep responses brief**
   - 2-3 sentences maximum
   - Mention specific file names
   - Detailed work goes in task sections

5. **All sub-agents: Update task sections**
   - Don't return work inline
   - Use add_section or update_section_text
   - Preserve work for future reference

### For Users

1. **Tag tasks consistently**
   - Use lowercase, hyphenated tags
   - Apply functional tags (backend, frontend, database)
   - Apply type tags (feature, bug, enhancement)

2. **Trust the agent routing**
   - Tags determine specialist assignment
   - System selects appropriate agent automatically
   - Override only when truly needed

3. **Review feature progress periodically**
   - Ask "What's the status of [feature]?"
   - Orchestrator will use Feature Manager
   - Get clear progress and blockers

4. **Define dependencies early**
   - Create dependencies when creating tasks
   - Clear dependencies enable automatic workflow
   - Prevents out-of-order execution

5. **Read summaries for project understanding**
   - Task summaries capture essential knowledge
   - Feature summaries show high-level progress
   - More efficient than reading all sections

---

## Migration Guide: From Subagents to Hybrid Architecture

**For Existing Users**: This guide helps you migrate from the subagent-only approach to the hybrid Skills + Subagents + Hooks architecture.

### Summary of Changes

**What's New**:
- ✅ Skills tier added for lightweight coordination (60-82% token savings)
- ✅ Hooks layer added for zero-token automation (git commits, tests)
- ✅ Feature Management Skill replaces Feature Manager subagent
- ✅ Task Management Skill replaces Task Manager subagent
- ✅ Dependency Analysis Skill added for dependency investigation

**What's Unchanged**:
- ✅ Specialist subagents (Backend, Frontend, Database, Test, Technical Writer, Planning) remain identical
- ✅ Tag-based routing via `recommend_agent` tool still works
- ✅ Dependency context passing via Summary sections unchanged
- ✅ Agent-mapping configuration unchanged

**Backwards Compatibility**: Feature Manager and Task Manager subagents continue to work. You can migrate gradually.

### Migration Steps

#### Step 1: Update setup_claude_orchestration

Run the enhanced setup tool to create Skills and Hooks:

```
User: "Setup Claude Code agents"

Result:
✓ Created .claude/agents/ with agent definitions (existing)
✓ Created .claude/skills/ with 3 core Skills (NEW)
✓ Created .claude/hooks/ with hook examples (NEW)
✓ Created README files for Skills and Hooks
```

After setup, you'll have:
- `.claude/skills/feature-management/` - Feature coordination Skill
- `.claude/skills/task-management/` - Task coordination Skill
- `.claude/skills/dependency-analysis/` - Dependency analysis Skill
- `.claude/hooks/task-complete-commit.sh` - Auto-commit example
- `.claude/hooks/feature-complete-gate.sh` - Quality gate example

#### Step 2: Enable Hooks (Optional)

Hooks automate git commits and test runs without using AI tokens:

```bash
# Copy example configuration
cp .claude/settings.local.json.example .claude/settings.local.json

# Edit to enable desired hooks
code .claude/settings.local.json
```

Common hooks to enable:
- **Task completion auto-commit**: Automatically commits when task marked complete
- **Feature completion quality gate**: Runs tests before allowing feature completion
- **Subagent metrics logger**: Logs completion times for monitoring

#### Step 3: Adopt Skills for Coordination

**Before (Subagent Approach)**:
```
User: "What task should I work on next in this feature?"

Orchestrator: Launches Feature Manager subagent
Feature Manager: Reads feature, calls get_next_task, returns recommendation
Cost: ~1400 tokens
```

**After (Skill Approach)**:
```
User: "What task should I work on next in this feature?"

Orchestrator: Feature Management Skill activates automatically
Skill: Reads feature, calls get_next_task, returns recommendation
Cost: ~300 tokens (78% savings)
```

**How to Trigger Skills**:
- Say: "What task should I work on next?" → Feature Management Skill
- Say: "Complete this task" → Task Management Skill
- Say: "Show me blocked tasks" → Dependency Analysis Skill

Skills activate automatically based on keywords in their descriptions.

#### Step 4: Continue Using Specialist Subagents

**No changes needed** for specialist subagents:

```
User: "Implement user authentication API"

Orchestrator: Launches Backend Engineer subagent (unchanged)
Backend Engineer: Reads task, implements code, writes tests
Result: Same as before
```

Specialist subagents remain the best choice for complex work.

#### Step 5: Update Your Workflows

**Old Workflow** (Subagent-Heavy):
```
1. User: "Work on feature F1"
2. Orchestrator → Launch Feature Manager START
3. Feature Manager → Recommend task T1
4. Orchestrator → Launch Task Manager START for T1
5. Task Manager → Recommend Backend Engineer
6. Orchestrator → Launch Backend Engineer
7. Backend Engineer → Implements, returns brief
8. Orchestrator → Launch Task Manager END for T1
9. Task Manager → Creates summary, marks complete
10. Repeat steps 2-9 for remaining tasks
```

**New Workflow** (Hybrid):
```
1. User: "Work on feature F1"
2. Feature Management Skill → Recommends task T1
3. Task Management Skill → Routes to Backend Engineer
4. Orchestrator → Launch Backend Engineer
5. Backend Engineer → Implements, returns brief
6. Task Management Skill → Creates summary, marks complete
   [Hook automatically creates git commit]
7. Feature Management Skill → Recommends next task
8. Repeat steps 3-7 for remaining tasks
```

**Token Savings**: ~50% reduction for feature with 5 tasks.

### Migration Examples

#### Example 1: Feature Coordination

**Old (Subagent)**:
```
Orchestrator: "I need to check feature progress"
→ Launch Feature Manager START
→ Feature Manager reads feature, returns progress
→ ~1400 tokens
```

**New (Skill)**:
```
Orchestrator: "Check feature progress"
→ Feature Management Skill activates
→ Skill reads feature, returns progress
→ ~300 tokens (78% savings)
```

#### Example 2: Task Completion

**Old (Subagent)**:
```
Backend Engineer: "I completed the auth API implementation"
→ Orchestrator: Launch Task Manager END
→ Task Manager creates summary, marks complete
→ ~1500 tokens
```

**New (Skill + Hook)**:
```
Backend Engineer: "I completed the auth API implementation"
→ Task Management Skill creates summary, marks complete
→ Hook automatically creates git commit
→ ~600 tokens + 0 for hook (60% savings)
```

#### Example 3: Dependency Analysis

**Old (Manual)**:
```
User: "Why is task T5 blocked?"
→ Orchestrator manually calls get_task_dependencies
→ Orchestrator reads each blocking task
→ Orchestrator explains blockers
→ ~2k tokens + effort
```

**New (Skill)**:
```
User: "Why is task T5 blocked?"
→ Dependency Analysis Skill activates
→ Skill analyzes dependencies, identifies blockers
→ ~400 tokens (80% savings)
```

### Gradual Migration Strategy

**Week 1: Setup**
- Run `setup_claude_orchestration` to create Skills and Hooks
- Review created files in `.claude/skills/` and `.claude/hooks/`
- Read Skills documentation to understand capabilities

**Week 2: Try Skills**
- Start using Feature Management Skill for next-task recommendations
- Use Task Management Skill for task completion
- Continue using subagents for specialist work (no change)

**Week 3: Enable Hooks**
- Copy `.claude/settings.local.json.example` to enable hooks
- Test auto-commit hook with one task
- Gradually enable other hooks as comfortable

**Week 4: Full Adoption**
- Use Skills for all coordination (feature/task management)
- Use Hooks for all automation (commits, tests)
- Use Subagents only for complex work (implementation, planning)

### Common Questions

**Q: Do I have to stop using Feature Manager/Task Manager subagents?**
A: No, they continue to work. Migrate at your own pace.

**Q: Will my existing features/tasks work with Skills?**
A: Yes, Skills use the same MCP tools as subagents. No data migration needed.

**Q: What if Skills don't activate?**
A: Check that you ran `setup_claude_orchestration` and Skills exist in `.claude/skills/`. Skills activate based on description keywords.

**Q: Can I customize Skills?**
A: Yes, edit SKILL.md files in `.claude/skills/*/SKILL.md`. Changes take effect immediately.

**Q: What if I prefer subagents?**
A: Continue using them. Skills are optional optimization, not mandatory.

---

## Troubleshooting

### Issue: Task Manager Not Calling recommend_agent

**Symptoms**: Task Manager tries to infer specialist manually

**Solution**:
- Remind Task Manager: "You must call recommend_agent tool"
- Check Task Manager agent definition includes recommend_agent in tools list
- Verify agent-mapping.yaml is accessible

### Issue: Dependency Context Not Passed

**Symptoms**: Specialist doesn't have context from previous tasks

**Solution**:
- Check Task Manager START is reading dependency summaries
- Verify dependencies are marked "completed"
- Ensure Task Manager included dependencies in brief
- Confirm orchestrator passed context to specialist

### Issue: Specialist Returns Full Code in Response

**Symptoms**: Specialist response contains hundreds of lines of code

**Solution**:
- Remind specialist: "Return brief summary only (2-3 sentences)"
- Emphasize: "Detailed work goes in task sections"
- Check specialist agent definition emphasizes brief responses

### Issue: Feature Manager Recommends Blocked Task

**Symptoms**: Feature Manager recommends task that has incomplete dependencies

**Solution**:
- Verify Feature Manager is using get_next_task tool
- Check that get_next_task is called correctly
- get_next_task automatically filters blocked tasks - trust the tool

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
- Consider splitting into multiple features if project very large

### Issue: Agents Not Found

**Symptoms**: "Agent definition not found" error

**Solution**:
- Run setup_claude_orchestration tool
- Verify .claude/agents/ directory exists
- Check agent name matches exactly (case-sensitive)
- Confirm agent-mapping.yaml uses correct agent names

### Issue: Task Manager END Not Executed

**Symptoms**: Task marked complete but no Summary section

**Solution**:
- Orchestrator must call Task Manager END after specialist completes
- Pass specialist output to Task Manager END
- Verify Task Manager END mode is detected (task ID + output provided)
- Check that add_section is working correctly

### Issue: Feature Never Completes

**Symptoms**: All tasks done but feature stays "in-development"

**Solution**:
- Call Feature Manager END explicitly
- Feature Manager checks all tasks complete, marks feature complete
- Verify no tasks are in "pending" or "in-progress" status

---

## Additional Resources

- **[Agent Definition Files](../src/main/resources/agents/claude/)** - Source agent definitions
- **[Agent Mapping Configuration](../src/main/resources/agents/agent-mapping.yaml)** - Tag-to-agent mappings
- **[MCP Tools Reference](api-reference.md)** - Complete tool documentation
- **[AI Guidelines](ai-guidelines.md)** - Autonomous workflow patterns
- **[Quick Start](quick-start.md)** - Getting started with Task Orchestrator

---

**Ready to coordinate multi-agent workflows?** Run `setup_claude_orchestration` and start orchestrating!
