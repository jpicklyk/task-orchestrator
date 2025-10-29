---
layout: default
title: Workflow Prompts
---

# Workflow Prompts

Workflow prompts provide structured, step-by-step guidance for complex project management scenarios. **Workflow prompts guide the PROCESS** of accomplishing work, while templates structure the WORK itself. They complement AI's autonomous pattern application by offering explicit workflows when needed.

**Available on all MCP clients**: Workflow prompts work with Claude Desktop, Claude Code, Cursor, Windsurf, and any MCP-compatible AI assistant.

## Table of Contents

- [Overview](#overview)
- [Dual Workflow Model](#dual-workflow-model)
- [Available Workflow Prompts](#available-workflow-prompts)
- [Skills Integration in Workflows](#skills-integration-in-workflows)
- [When to Use Workflow Prompts](#when-to-use-workflow-prompts)
- [Usage Patterns](#usage-patterns)
- [Integration with Templates](#integration-with-templates)
- [Regression Testing Requirements](#regression-testing-requirements)

---

## Overview

Workflow prompts are AI-invokable guides that provide comprehensive instructions for complex scenarios. Unlike autonomous pattern recognition (where Claude understands your intent naturally), workflow prompts offer explicit, step-by-step processes.

### What They Provide

- **Structured Processes**: Step-by-step instructions for complex workflows (the HOW)
- **Quality Validation**: Built-in checkpoints and validation steps
- **Template Integration**: Automatic template selection and application (the WHAT)
- **Best Practices**: Proven patterns for common scenarios
- **Universal Availability**: Works with ANY MCP client (not Claude Code specific)

### Templates vs. Workflow Prompts

**Templates structure the WORK** (WHAT needs to be documented):
- Create sections: Requirements, Technical Approach, Testing Strategy
- Define what information must be captured
- Provide consistent documentation structure
- Work with both direct execution and sub-agent orchestration

**Workflow Prompts guide the PROCESS** (HOW to accomplish the work):
- Provide step-by-step procedural guidance
- Integrate MCP tool calls systematically
- Offer quality validation checkpoints
- Guide from start to completion

**Example**:
```
Template (Technical Approach):
→ Creates "Technical Approach" section
→ Defines: Architecture, Technology Stack, Design Patterns

Workflow Prompt (coordinate_feature_development):
→ Phase 1: Create feature and apply templates
→ Phase 2: Break down into tasks with dependencies
→ Phase 3: Execute tasks using Specialists
→ Phase 4: Complete feature and validate quality gates
```

### What Makes Them Different

Workflow prompts are **explicit guidance** - you invoke them by name and receive detailed instructions. This contrasts with **autonomous patterns** where Claude recognizes your intent and applies appropriate workflows automatically.

> **Learn More**: See [AI Guidelines - Dual Workflow Model](ai-guidelines#dual-workflow-model) for understanding when to use autonomous vs. explicit workflows.

---

## Dual Workflow Model

Task Orchestrator supports two complementary modes of operation:

### Mode 1: Autonomous Pattern Application

Claude recognizes your intent from natural language and applies appropriate patterns automatically.

**Example**:
```
User: "Help me plan this authentication feature"

Claude autonomously:
1. Runs get_overview to understand context
2. Discovers templates via list_templates
3. Creates feature with appropriate templates
4. Confirms creation and suggests next steps
```

**When to use**: Most everyday tasks - faster, more natural conversation.

### Mode 2: Explicit Workflow Invocation

You invoke a specific workflow prompt by name for guaranteed comprehensive guidance.

**Example**:
```
User: "Walk me through creating a new project using the project setup workflow"

Claude explicitly:
1. Follows project_setup_workflow prompt
2. Walks through all 8 steps systematically
3. Validates each step before proceeding
4. Ensures nothing is missed
```

**When to use**: Complex scenarios, learning, ensuring comprehensive coverage.

> **See**: [AI Guidelines - Dual Workflow Model](ai-guidelines#dual-workflow-model) for detailed explanation and decision guidance.

---

## Available Workflow Prompts

### `initialize_task_orchestrator`

**Purpose**: Initialize AI agent with Task Orchestrator guidelines and patterns

**When to Use**:
- First time using Task Orchestrator with an AI agent
- AI seems unfamiliar with Task Orchestrator capabilities
- Starting a new project or work session
- Need to refresh AI's understanding of patterns

**What It Does**:
- Fetches all guideline resources from MCP
- Internalizes workflow patterns and best practices
- Sets up template discovery process
- Validates understanding of tool ecosystem

**Invocation**: AI automatically initializes on first connection, or invoke manually if needed

> **See**: [AI Guidelines - Initialization](ai-guidelines#initialization-process) for detailed initialization process

---

### `coordinate_feature_development`

**Purpose**: Coordinate end-to-end feature development through four phases using Skills for detailed guidance

**When to Use**:
- User provides feature request (with or without PRD file) for end-to-end orchestration
- Need to break down a feature into executable tasks
- Want comprehensive feature lifecycle management from creation through completion
- Prefer Skills-based coordination for token efficiency

**What It Covers**:
1. **Phase 1: Feature Creation** - Create feature with templates and sections, progress to planning status
2. **Phase 2: Task Breakdown** - Break feature into domain-isolated tasks with dependencies and execution graph
3. **Phase 3: Task Execution** - Execute tasks in parallel batches, cascade through dependencies
4. **Phase 4: Feature Completion** - Progress feature through testing and quality gates to completion

**Skills Integration**:
- **Phase 1 & 4**: Feature Orchestration Skill handles feature creation and completion
- **Phase 2**: Planning Specialist (Sonnet) creates tasks for complex features; Skill for simple breakdowns
- **Phase 3**: Task Orchestration Skill coordinates parallel task execution via Implementation Specialists
- **Token savings**: 64% vs sequential subagent approach (4500 → 1600 tokens per feature)
- **Pattern**: Phases orchestrated by workflow → Detailed work delegated to Skills and Specialists

**Key Decisions It Handles**:
- When to route to Feature Architect (complex features)
- When to route to Planning Specialist (5+ tasks)
- Parallel vs sequential task execution
- Quality gate enforcement before completion
- Cascade dependency handling

**Workflow Phases**:

```
Phase 1: Feature Creation
└─ Skill: Feature Orchestration
   └─ Decision: Simple → Direct | Complex → Feature Architect
   └─ Output: Feature created, status=planning

Phase 2: Task Breakdown
└─ Skill: Feature Orchestration + Planning Specialist
   └─ Decision: Simple (< 5 tasks) → Skill | Complex (≥ 5 tasks) → Specialist
   └─ Output: Tasks created, execution graph generated

Phase 3: Task Execution
└─ Skill: Task Orchestration
   └─ Coordination: Launch specialist per batch, cascade on dependency resolution
   └─ Output: All tasks completed, summaries populated

Phase 4: Feature Completion
└─ Skill: Feature Orchestration + Status Progression Skill
   └─ Flow: Validate completion → Run quality gates → Transition status
   └─ Output: Feature completed
```

**Autonomous Alternative**: Simply ask "Create feature for user authentication and break it down into tasks" and Claude will apply coordinate_feature_development patterns automatically

---


### `project_setup_workflow`

**Purpose**: Initialize new projects with proper structure, features, and foundation tasks

**When to Use**:
- Starting a brand new project from scratch
- Need comprehensive project foundation
- Want to establish standards and workflows upfront
- Large project requiring proper planning and structure

**What It Covers**:
1. Project foundation and comprehensive summary creation
2. Project documentation structure (charter, architecture, standards)
3. Feature planning and core feature creation
4. Initial task creation (infrastructure, research, setup)
5. Template strategy and custom template planning
6. Development workflow setup (git, QA, standards)
7. Dependency sequencing
8. Project monitoring and progress tracking setup

**Key Decisions It Helps With**:
- How to structure project hierarchy
- Which features to create initially
- What foundation tasks are needed
- How to set up development standards

**Autonomous Alternative**: Ask "Create a new project for building a web application" and Claude will create project with appropriate structure automatically

---


### `coordination_workflow`

**Purpose**: Handle common coordination operations efficiently using Skills instead of subagents

**When to Use**:
- Need to coordinate multiple tool calls (2-5 tools)
- Want maximum token efficiency for coordination tasks
- Performing repetitive operations (task completion, routing, dependency checks)
- Working within feature lifecycle (what's next, progress tracking)

**What It Covers**:
1. Identifying coordination operation type
2. Selecting appropriate Skill (Feature, Task, or Dependency)
3. Invoking Skill with proper context
4. Processing Skill output
5. Determining next action

**Key Decisions It Helps With**:
- Which Skill handles this coordination operation
- When to use Skill vs direct tool vs subagent
- How to chain Skills for complex workflows
- When to escalate from Skill to subagent

**Workflow Steps**:

**Step 1: Identify Operation Type**

Determine which category of coordination:
- **Feature-level**: "What's next?" "Feature progress?" "Complete feature?"
- **Task-level**: "Route this task" "Complete task" "Update status"
- **Dependency-level**: "What's blocked?" "Check dependencies" "Show blockers"
- **Hook/Skill creation**: "Create automation" "Build custom Skill"

**Step 2: Select Appropriate Skill**

| Operation Type | Skill to Use | Example Invocations |
|---------------|--------------|-------------------|
| Feature coordination | Feature Management Skill | "What's the next task?" "Feature F1 progress?" |
| Task lifecycle | Task Management Skill | "Complete task T1" "Route this task" |
| Dependency analysis | Dependency Analysis Skill | "Check dependencies" "What's blocking T5?" |
| Hook creation | Hook Builder Skill | "Create auto-commit hook" "Build test gate" |
| Skill creation | Skill Builder Skill | "Help me create a Skill" "Build doc generator" |

**Step 3: Invoke Skill**

**For Feature Management**:
```
Examples:
- "What should I work on next in feature F1?"
- "Check progress on feature F2"
- "Complete feature F1"
- "Show blocked tasks in this feature"

Expected Response: Task recommendation, progress report, completion summary, or blocker list (300-600 tokens)
```

**For Task Management**:
```
Examples:
- "Complete task T1"
- "Which specialist should handle task T2?"
- "Mark task T3 as in-progress"
- "Check if task T4 is ready to start"

Expected Response: Task completion confirmation, specialist recommendation, status update, or dependency report (300-600 tokens)
```

**For Dependency Analysis**:
```
Examples:
- "What's blocking progress in feature F1?"
- "Show me the dependency chain for task T5"
- "Which task should I unblock first?"
- "Why is task T6 blocked?"

Expected Response: Blocker analysis, dependency tree, prioritization recommendation (300-500 tokens)
```

**Step 4: Process Skill Output**

Skills return:
- **Brief summaries** (not full context) - keep orchestrator lean
- **Clear recommendations** - what to do next
- **Relevant context** - just enough for decision-making
- **Action items** - explicit next steps

**Step 5: Determine Next Action**

Based on Skill output:

**If Skill recommends implementation**:
→ Launch appropriate subagent (Backend Engineer, Database Engineer, etc.)

**If Skill identifies blocker**:
→ Address blocker first (use another Skill or subagent to unblock)

**If Skill completes operation**:
→ Optional: Hooks may trigger automatically (git commit, tests, notifications)
→ Return to user with confirmation

**If Skill requests clarification**:
→ Ask user for additional information

**Example Coordination Workflows**:

**Example 1: Feature Task Recommendation (Feature Management Skill)**

```
User: "What's the next unblocked task in authentication feature?"

Coordination Workflow:
1. Operation Type: Feature coordination
2. Skill Selected: Feature Management Skill
3. Invocation: Natural language triggers Skill
4. Skill Executes:
   - query_container(operation="get", containerType="feature", id=id, includeTasks=true, includeTaskDependencies=true)
   - get_next_task(featureId, limit=1, includeDetails=true)
   - Analyzes task priorities and dependencies
5. Output Processed:
   "Next Task: T3 - Implement token refresh endpoint
    Priority: high | Complexity: 6/10
    Status: Unblocked (dependency T2 completed)
    Recommended Specialist: Backend Engineer"
6. Next Action: Launch Backend Engineer or ask user to confirm

Tokens: ~300 (vs 1400 for subagent approach = 78% savings)
```

**Example 2: Task Completion (Task Management Skill + Hook)**

```
User: "Complete task T1"

Coordination Workflow:
1. Operation Type: Task lifecycle management
2. Skill Selected: Task Management Skill
3. Invocation: "Complete task T1"
4. Skill Executes:
   - query_container(operation="get", containerType="task", id=T1, includeSections=true)
   - manage_sections(operation="add", entityType=TASK, title="Summary", content="...")
   - manage_container(operation="setStatus", containerType="task", id=T1, status="completed")
5. Output Processed:
   "Task T1 completed. Summary section created documenting implementation of user login API."
6. Hook Triggers (if configured):
   - PostToolUse hook on manage_container (operation="setStatus")
   - Auto-creates git commit with task details
   - 0 additional tokens
7. Next Action: Return confirmation to user

Tokens: ~600 for Skill + 0 for Hook (vs 1500 for subagent = 60% savings)
```

**Example 3: Dependency Validation (Dependency Analysis Skill)**

```
User: "Why is task T5 blocked?"

Coordination Workflow:
1. Operation Type: Dependency analysis
2. Skill Selected: Dependency Analysis Skill
3. Invocation: "Why is task T5 blocked?"
4. Skill Executes:
   - query_container(operation="get", containerType="task", id=T5, includeSections=true)
   - get_task_dependencies(T5, direction=incoming)
   - For each dependency: query_container(operation="get", containerType="task", id=id) to check status
   - Analyzes blocker impact
5. Output Processed:
   "Task T5 blocked by 2 incomplete dependencies:
    - T2: Implement authentication API (in-progress)
    - T4: Create user database schema (pending)

    Recommendation: Complete T4 first (unblocks T5 and T6)"
6. Next Action: Ask user if they want to work on T4, or show T4 details

Tokens: ~350 (vs 1200 for subagent = 71% savings)
```

**Example 4: Chaining Skills for Complete Feature Flow**

```
User: "Work through authentication feature systematically"

Coordination Workflow (Multi-Step):

Step A: Feature Management Skill
→ "What's next in feature F1?"
→ Response: "Task T1: Database schema (unblocked)"
→ Tokens: 300

Step B: Task Management Skill
→ "Route task T1"
→ Response: "Database Engineer recommended"
→ Tokens: 300

Step C: Launch Database Engineer (subagent)
→ Implements database schema
→ Returns brief
→ Tokens: 1600

Step D: Task Management Skill
→ "Complete task T1"
→ Creates summary, marks complete
→ Tokens: 600

Step E: Hook triggers (auto-commit)
→ Git commit created
→ Tokens: 0

Step F: Feature Management Skill
→ "What's next in feature F1?"
→ Response: "Task T2: Implement auth API"
→ Tokens: 300

[Pattern continues...]

Total per iteration: 3100 tokens
vs Subagent-only: 5500 tokens
Savings: 44% per task cycle
```

**When to Escalate from Skill to Subagent**:

Skills handle coordination efficiently, but escalate to subagents when:
- ✅ Need to generate code (Skills can't write implementation)
- ✅ Need complex reasoning (architectural decisions, trade-off analysis)
- ✅ Need multi-step workflows with backtracking
- ✅ Need specialist domain knowledge (Backend Engineer, Database Engineer)

**Pattern**:
```
Skill identifies work → Recommends specialist → Orchestrator launches subagent
```

**Token Efficiency Summary**:

| Coordination Operation | Subagent Tokens | Skill Tokens | Savings |
|----------------------|----------------|--------------|---------|
| Feature recommendation | 1400 | 300 | 78% |
| Task routing | 1300 | 300 | 77% |
| Task completion | 1500 | 600 | 60% |
| Dependency analysis | 1200 | 350 | 71% |
| **Average** | **1350** | **388** | **71%** |

**Autonomous Alternative**: Claude recognizes coordination patterns automatically and invokes appropriate Skills without explicit workflow invocation

---

## Skills Integration in Workflows

Workflow prompts can leverage **Skills** to reduce token costs for coordination operations. Skills are lightweight, focused capabilities that achieve **60-82% token savings** compared to subagent operations through progressive disclosure.

### What Are Skills?

Skills are specialized mini-agents that:
- Handle coordination tasks with 2-5 tool calls
- Activate automatically based on description matching
- Cost 300-600 tokens (vs 1400-1700 for subagents)
- Work seamlessly with workflow prompts

**Available Skills for Workflows**:
1. **Feature Management Skill** - Feature coordination, next task recommendation (300-600 tokens)
2. **Task Management Skill** - Task completion, status updates, specialist routing (300-600 tokens)
3. **Dependency Analysis Skill** - Dependency validation, blocker identification (300-500 tokens)
4. **Hook Builder Skill** - Custom automation hook creation (400-700 tokens, interactive)
5. **Skill Builder Skill** - Custom skill creation (500-800 tokens, interactive)

### When Workflows Should Use Skills

**Use Skills when workflow steps involve**:
- ✅ 2-5 tool calls in sequence (coordination patterns)
- ✅ Repetitive operations (task completion, status checks)
- ✅ Simple data queries and updates
- ✅ Task routing and recommendation

**Continue using direct tools or subagents for**:
- ❌ Single tool calls (direct tools are simpler)
- ❌ Complex reasoning or code generation (subagents required)
- ❌ Multi-step workflows with backtracking (subagents handle better)

### Decision Criteria for Workflows

```
Does this workflow step require...
├─ Single tool call? → Use direct MCP tool
├─ 2-5 coordinated tool calls? → Recommend Skill
└─ Complex reasoning/code? → Launch subagent
```

### Token Efficiency Benefits

| Operation | Without Skills | With Skills | Savings |
|-----------|----------------|-------------|---------|
| **Recommend next task** | 1400 tokens (subagent) | 300 tokens (Skill) | 78% |
| **Complete task** | 1500 tokens (subagent) | 600 tokens (Skill) | 60% |
| **Check dependencies** | 1200 tokens (subagent) | 350 tokens (Skill) | 71% |
| **Route task to specialist** | 1300 tokens (subagent) | 300 tokens (Skill) | 77% |

**Example workflow comparison**:
```
Traditional (subagent-only):
→ Feature coordination: 1400 tokens
→ Task routing: 1300 tokens
→ Implementation: 2000 tokens (subagent)
→ Task completion: 1500 tokens
Total: 6,200 tokens

Skills-enhanced:
→ Feature coordination: 300 tokens (Skill)
→ Task routing: 300 tokens (Skill)
→ Implementation: 2000 tokens (subagent)
→ Task completion: 600 tokens (Skill)
Total: 3,200 tokens (48% savings)
```

### How to Recommend Skills in Workflows

When a workflow identifies a coordination operation, recommend the appropriate Skill:

**Pattern 1: Feature Coordination**
```markdown
Step 3: Determine next task to work on
- Use Feature Management Skill to get recommendation
- Invocation: "What's the next unblocked task in this feature?"
- Expected: Task recommendation with context (300-600 tokens)
```

**Pattern 2: Task Completion**
```markdown
Step 7: Mark task complete
- Use Task Management Skill for completion
- Invocation: "Complete task [id]"
- Expected: Summary created, status updated (600 tokens)
- Benefit: Hook can auto-commit if configured (0 additional tokens)
```

**Pattern 3: Dependency Validation**
```markdown
Step 2: Validate dependencies before starting
- Use Dependency Analysis Skill to check blockers
- Invocation: "Check if task [id] has incomplete dependencies"
- Expected: Dependency status report (300-500 tokens)
```

### Integration with Existing Workflows

Skills **complement** workflow prompts by handling the coordination steps efficiently:

- **Workflow prompts** guide the overall PROCESS (what to do, when, in what order)
- **Skills** execute coordination OPERATIONS efficiently (how to coordinate specific steps)
- **Subagents** perform complex WORK (implementation, reasoning, code generation)

This three-tier approach provides maximum efficiency:
```
Workflow Prompt (process guidance)
    ↓
Skills (coordination - 300-600 tokens)
    ↓
Subagents (complex work - 1500-3000 tokens)
```

**See**: [Skills Guide](skills-guide.md) for complete Skills documentation and [Agent Architecture](agent-architecture.md) for decision patterns.

---

## When to Use Workflow Prompts

### Choose Explicit Workflow Prompts When:

✅ **Complex scenarios** requiring guaranteed comprehensive coverage
✅ **Learning** the system and want to understand best practices
✅ **Quality gates** are critical (security, compliance, production systems)
✅ **Team standardization** - workflow prompts ensure consistent approach
✅ **Uncertain about approach** - step-by-step guidance reduces mistakes

### Choose Autonomous Pattern Application When:

✅ **Everyday tasks** - faster, more natural conversation
✅ **Familiar scenarios** - you know what you want
✅ **Iterative work** - quick adjustments and updates
✅ **Exploration** - trying different approaches
✅ **Simple operations** - creating single tasks, updating status

### Decision Tree

```
Is this a complex scenario with multiple steps?
├─ Yes → Consider explicit workflow prompt
│   └─ Are you learning or need guaranteed coverage?
│       ├─ Yes → Use explicit workflow prompt
│       └─ No → Try autonomous first, escalate if needed
│
└─ No → Use autonomous pattern application
```

> **See**: [AI Guidelines - When to Use Each Mode](ai-guidelines#dual-workflow-model) for detailed guidance

---

## Usage Patterns

### Pattern 1: Guided Discovery

Invoke workflow prompt without details - Claude will ask questions to understand your needs:

```
User: "Use the project setup workflow"

Claude: "I'll help you set up a new project. What type of project are you building?
What technologies will you use? What are your main business goals?"
```

**Best For**: Exploring options, learning, not sure about all details yet

---

### Pattern 2: Direct Implementation

Provide details directly for immediate, targeted assistance:

```
User: "Use the create feature workflow for user authentication with email/password
login, social auth, and JWT token management"

Claude: [Immediately applies workflow with your specific details]
```

**Best For**: Clear requirements, time-sensitive work, specific problem-solving

---

### Pattern 3: Escalation from Autonomous

Start autonomous, escalate to explicit if complexity warrants:

```
User: "Help me plan this feature"

Claude: [Starts autonomous pattern application]
[Recognizes complexity]

Claude: "This is a complex feature with multiple integration points. Would you like
me to use the coordinate_feature_development workflow for comprehensive step-by-step guidance?"
```

**Best For**: Most scenarios - let Claude assess and suggest escalation

---

### Pattern 4: Sequential Workflow Application

Combine workflows for comprehensive coverage:

```
User: "Use the project setup workflow to create a new API project"
[Project created]

User: "Now use the coordinate_feature_development workflow for the authentication feature"
[Feature created with tasks and execution plan]

User: "Start executing tasks from the feature plan"
[Implementation specialists execute tasks through phases]
```

**Best For**: Large projects, systematic development, learning the full workflow

---

### Pattern 5: PRD-Driven Development ⭐ Most Effective

Provide Product Requirements Document for comprehensive intelligent breakdown:

```
User: "Analyze this PRD and break it down into a complete project structure:

# Authentication System v2 PRD

## Overview
Modernize authentication with OAuth 2.0, social login, and MFA support.

## Requirements
- Email/password authentication
- Google and GitHub OAuth
- Two-factor authentication (TOTP)
- Session management with JWT
- Password reset flow

## Technical Constraints
- Must support 10,000 concurrent users
- Session tokens expire after 24 hours
- Integration with existing user database

## Success Criteria
- All authentication methods working
- < 200ms login response time
- 100% test coverage on auth flows"

Claude: [Reads entire PRD]
[Identifies 4 major features]
[Creates project structure with 15 tasks]
[Applies Technical Approach + Requirements templates]
[Creates 8 blocking dependencies]

Claude: "Created 'Authentication System v2' project with:

Features:
1. OAuth Integration (5 tasks) - BLOCKS other features
2. Session Management (4 tasks) - Core dependency
3. MFA Implementation (3 tasks) - Can run parallel after OAuth
4. Password Management (3 tasks) - Independent

Recommended sequence:
1. Start: OAuth Integration (unblocks most work)
2. Parallel: Password Management flow
3. After OAuth: Session Management
4. Final: MFA Implementation

Ready to begin implementation?"
```

**Best For**: ⭐ **Most scenarios with defined requirements** - provides best results

**Why It Works Best**:
- Complete context enables intelligent breakdown
- Proper technical dependency sequencing
- Systematic template application based on PRD content
- Optimal feature and task granularity
- AI makes informed decisions with full scope

> **Detailed Guide**: See [PRD Workflow Guide](quick-start#prd-driven-development-workflow) for complete instructions, examples, and PRD best practices.

---

## Integration with Templates

### How Workflows and Templates Work Together

**Workflow prompts guide the PROCESS, templates structure the WORK**:

| Aspect | Templates | Workflow Prompts |
|--------|-----------|------------------|
| **Purpose** | Define WHAT to document | Define HOW to accomplish work |
| **Creates** | Sections (Requirements, Technical Approach) | Step-by-step procedural guidance |
| **Works With** | Any MCP client | Any MCP client |
| **Sub-Agents** | Sections read by specialists | Process followed by orchestrator |
| **Example** | "Technical Approach" section with architecture | "Step 3: Read Technical Approach and implement" |

### How Workflows Use Templates

All workflow prompts integrate with the template system:

1. **Automatic Discovery**: Workflows use `list_templates` to find applicable templates
2. **Smart Selection**: Workflows suggest templates based on work type and context
3. **Section Creation**: Templates create the sections (WORK structure)
4. **Process Guidance**: Workflows guide you through using those sections (PROCESS steps)
5. **Multiple Templates**: Workflows often apply 2-3 templates for comprehensive coverage
6. **Git Detection**: Workflows automatically suggest git templates when .git directory detected

### Template Categories by Workflow

**Coordinate Feature Development Workflow** (creates WORK structure across 4 phases):

**Phase 1: Feature Creation**
- Context & Background → creates "Business Context" section
- Requirements Specification → creates "Requirements" section
- Technical Approach → creates "Technical Approach" section

**Phase 2: Task Breakdown**
- Task Implementation Workflow → creates "Implementation Steps" section
- Local Git Branching Workflow → creates "Git Workflow" section (if git detected)
- Technical Approach → creates "Technical Approach" section (for complex subtasks)

**Phase 3 & 4: Execution and Completion**
- Uses templates created in Phases 1 & 2
- Specialists read sections for context
- Task summaries populate "Results" or "Implementation Notes" sections

**Key Insight**: Templates create the sections FIRST in Phase 1-2, then Skills and Specialists guide you through USING those sections in Phase 3-4 to execute and complete work.

### Templates Work With Both Execution Patterns

**Direct Execution** (any MCP client):
```
1. Workflow applies templates → creates sections
2. Workflow guides you through process
3. You read template sections for context
4. You implement the work
5. You update template sections with results
```

**Sub-Agent Execution** (Claude Code only):
```
1. Workflow applies templates → creates sections
2. Orchestrator launches specialist
3. Specialist reads template sections for context
4. Specialist implements the work
5. Specialist updates template sections with results
```

**Templates are identical in both patterns** - they create the same sections (Requirements, Technical Approach, Testing Strategy). The difference is WHO reads and implements: you directly, or a specialist agent.

> **See**: [Templates Guide](templates) for complete template documentation, including how templates work with both direct and sub-agent execution patterns, and [AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven) for discovery patterns

---

## Regression Testing Requirements

When fixing bugs through feature development (Phase 3 execution in `coordinate_feature_development`), **comprehensive regression tests are mandatory** to prevent the issue from recurring. Skills and Specialists enforce these requirements and will not allow completion without proper tests.

### When Required

**All bug fixes MUST include regression tests.** This applies to:
- Tasks tagged with `task-type-bug`
- Hotfixes (`task-type-hotfix`)
- Any work addressing production issues or defects

### Required Test Types

#### 1. Bug Reproduction Test (Required)

Create a test that **fails with the old code** and **passes with the fix**:

```kotlin
@Test
fun `should handle null user token without NPE - regression for AUTH-70490b4d`() {
    // BUG: User logout crashed when token was null
    // ROOT CAUSE: user.token.invalidate() called without null check
    // FIX: Changed to user.token?.invalidate()

    val user = User(token = null)
    assertDoesNotThrow {
        authService.logout(user)
    }
}
```

**Requirements**:
- Test name clearly describes the scenario and references task ID
- Comments explain BUG, ROOT CAUSE, and FIX
- Test reproduces exact conditions that caused the bug
- Assertions verify bug condition doesn't cause failure

#### 2. Edge Case Tests (Required if applicable)

Test boundary conditions and scenarios not previously covered:

```kotlin
@Test
fun `should handle empty token string - edge case for AUTH-70490b4d`() {
    val user = User(token = "")
    assertDoesNotThrow { authService.logout(user) }
}

@Test
fun `should handle whitespace-only token - edge case for AUTH-70490b4d`() {
    val user = User(token = "   ")
    assertDoesNotThrow { authService.logout(user) }
}
```

#### 3. Integration Tests (Required if bug crossed boundaries)

Test component interactions where the bug occurred:

```kotlin
@Test
fun `logout flow should complete when user has no active session`() {
    // This bug affected the full logout flow
    val user = createUserWithoutSession()

    val result = authService.logout(user)

    assertEquals(LogoutResult.SUCCESS, result.status)
    verifySessionCleaned(user)
    verifyAuditLogCreated(user, "logout")
}
```

#### 4. Performance Tests (Required if performance-related)

Verify fix doesn't introduce performance regressions:

```kotlin
@Test
fun `logout should complete within 100ms even with null token`() {
    val user = User(token = null)

    val duration = measureTimeMillis {
        authService.logout(user)
    }

    assertTrue(duration < 100, "Logout took ${duration}ms, expected < 100ms")
}
```

### Test Naming Convention

**Format**: `should [expected behavior] - regression for [TASK-ID-SHORT]`

**Examples**:
- `should handle null input - regression for TASK-70490b4d`
- `should process concurrent requests - regression for TASK-a2a36aeb`
- `should validate maximum value - regression for TASK-12bf786d`

### Test Documentation Requirements

Every regression test **MUST** include:

1. **Descriptive test name** with task ID reference
2. **Comment block** explaining:
   - `BUG:` What went wrong and user impact
   - `ROOT CAUSE:` Technical reason for the bug
   - `FIX:` What code change fixed it
3. **Clear assertions** verifying expected behavior

### Completion Checklist

Before marking a bug fix as completed, verify:

- ✅ Bug reproduction test exists and fails on old code
- ✅ Bug reproduction test passes with fix
- ✅ Edge cases identified and tested
- ✅ Integration tests added if bug crossed boundaries
- ✅ Performance tests added if relevant
- ✅ All tests have proper documentation comments
- ✅ Test names reference task ID for traceability
- ✅ Code coverage increased for affected code paths
- ✅ All tests passing

### Common Patterns

**Null/Empty Input Bugs**:
```kotlin
@Test
fun `should handle null input - regression for TASK-xxxxx`() {
    assertDoesNotThrow { service.process(null) }
}
```

**Race Condition Bugs**:
```kotlin
@Test
fun `should handle concurrent access - regression for TASK-xxxxx`() {
    val threads = (1..10).map { thread { service.processRequest(it) } }
    threads.forEach { it.join() }
    // Verify no corruption occurred
}
```

**Boundary Value Bugs**:
```kotlin
@Test
fun `should handle maximum value - regression for TASK-xxxxx`() {
    val result = service.calculate(Int.MAX_VALUE)
    assertTrue(result.isSuccess)
}
```

**State Management Bugs**:
```kotlin
@Test
fun `should handle state transition - regression for TASK-xxxxx`() {
    service.initialize()
    service.stop()
    service.initialize() // Bug: second init failed
    assertTrue(service.isRunning)
}
```

### Enforcement

Skills and Specialists **enforce** regression testing requirements:

- **Phase 3 (Task Execution)**: Identifies bug fixes and prepares for regression testing
- **Implementation**: Guides through bug reproduction and test creation
- **Completion Validation**: Checks for regression tests before allowing task completion
- **Critical: Bug fixes require comprehensive regression tests before marking complete**

**You cannot mark a bug fix as completed without regression tests.**

---

## Customization

### Adding Team-Specific Patterns

Extend workflow prompts with team-specific patterns by adding to your project's `CLAUDE.md`:

```markdown
## Custom Task Orchestrator Workflow Patterns

### Security Review Workflow Pattern
When creating security-related tasks:
1. Always apply "security" tag
2. Set priority to "high"
3. Apply Technical Approach + Testing Strategy templates
4. Add security checklist section
5. Require review from security team member
```

### Creating Custom Workflow Prompts

Work with your team to create custom workflow prompts for:
- Domain-specific processes (healthcare, finance, etc.)
- Regulatory compliance workflows
- Team-specific quality gates
- Integration with external tools

---

## Best Practices

### For AI Agents

1. **Start with get_overview** before applying any workflow
2. **Suggest workflows** when complexity warrants explicit guidance
3. **Don't force workflows** - use autonomous patterns when appropriate
4. **Validate completion** using get_sections before marking tasks/features done
5. **Apply git workflows** automatically when .git directory detected

### For Development Teams

1. **Use autonomous mode** for most everyday work - it's faster
2. **Invoke explicit workflows** for complex scenarios or when learning
3. **Combine workflows** sequentially for comprehensive coverage
4. **Monitor effectiveness** - refine approach based on what works
5. **Extend with custom patterns** to match your team's processes

---

## Additional Resources

- **[AI Guidelines](ai-guidelines)** - Complete AI initialization and autonomous pattern documentation
- **[Templates Guide](templates)** - Template system and customization
- **[Quick Start](quick-start)** - Getting started with Task Orchestrator
- **[API Reference](api-reference)** - Complete tool documentation

---

**Questions?** Check the [Troubleshooting Guide](troubleshooting) or see [AI Guidelines - Troubleshooting](ai-guidelines#troubleshooting) for AI-specific issues.
