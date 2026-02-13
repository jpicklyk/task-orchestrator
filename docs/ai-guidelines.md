---
layout: default
title: AI Guidelines and Initialization
---

# AI Guidelines and Initialization System

The MCP Task Orchestrator includes a comprehensive **AI Guidelines and Initialization System** that helps AI agents effectively use task orchestration tools through natural language pattern recognition and autonomous workflow execution.

## Table of Contents

- [Overview](#overview)
- [MCP Tools Reference](#mcp-tools-reference)
- [Guidance Architecture](#guidance-architecture)
  - [Layer 1: MCP Resources (Internalized Knowledge)](#layer-1-mcp-resources-internalized-knowledge)
  - [Layer 2: Dynamic Templates (Database-Driven)](#layer-2-dynamic-templates-database-driven)
- [Orchestration Patterns and Decision Gates](#orchestration-patterns-and-decision-gates)
  - [Session Start Routine](#session-start-routine)
  - [Template Discovery Workflow](#template-discovery-workflow-universal---all-mcp-clients)
  - [Dependency-Aware Workflows](#dependency-aware-workflows)
  - [Quality Standards](#quality-standards)
- [Dual Workflow Model](#dual-workflow-model)
  - [Mode 1: Autonomous Pattern Application](#mode-1-autonomous-pattern-application)
  - [Mode 2: Explicit Workflow Invocation](#mode-2-explicit-workflow-invocation)
- [Initialization Process](#initialization-process)
  - [For Claude Code](#for-claude-code)
  - [For Other AI Agents](#for-other-ai-agents)
- [Available Guideline Resources](#available-guideline-resources)
- [Customization and Extension](#customization-and-extension)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Overview

The AI Guidelines system provides AI agents with comprehensive knowledge about how to effectively use the Task Orchestrator, enabling them to:

- **Recognize user intent** from natural language requests
- **Apply appropriate workflow patterns** autonomously
- **Discover and use templates** dynamically
- **Follow best practices** for task and feature creation
- **Integrate workflows** seamlessly with tool usage

### Key Benefits

- **AI-Agnostic Design**: Works with any AI agent that supports MCP
- **Natural Language Understanding**: AI recognizes patterns like "help me plan this feature" without explicit commands
- **Reduced Learning Curve**: Guidelines are internalized by AI, not required reading for users
- **Consistent Best Practices**: Ensures quality and standardization across projects
- **Extensible**: Teams can add custom patterns and guidelines

---

## MCP Tools Reference

The Task Orchestrator exposes 12 MCP tools organized into categories:

### Container Management (unified CRUD for Projects/Features/Tasks)
- **`manage_container`** - Write operations: create, update, delete (all use array parameters)
- **`query_container`** - Read operations: get, search, export, overview

### Section Management
- **`manage_sections`** - Write operations: add, update, updateText, updateMetadata, delete, reorder, bulkCreate, bulkUpdate, bulkDelete
- **`query_sections`** - Read operations with filtering

### Template Management
- **`query_templates`** - Browse and search templates
- **`manage_template`** - Create, update, delete templates
- **`apply_template`** - Apply template to entity

### Dependency Management
- **`query_dependencies`** - Query task dependencies. Use `neighborsOnly=false` for full graph traversal.
- **`manage_dependencies`** - Create and delete dependencies with batch support, pattern shortcuts (`linear`, `fan-out`, `fan-in`), and legacy single-pair creation.

### Workflow Optimization
- **`get_next_task`** - Intelligent task recommendation with dependency checking and priority sorting
- **`get_blocked_tasks`** - Dependency blocking analysis

### Status Progression
- **`get_next_status`** - Read-only status progression recommendations based on workflow configuration
- **`request_transition`** - Trigger-based status transitions with validation (start, complete, cancel, block, hold)

---

## Orchestration Patterns and Decision Gates

This section teaches AI assistants how to effectively use task-orchestrator's 12 tools including template discovery, dependency-aware workflows, and proactive task management.

### Session Start Routine

**ALWAYS start every session with these steps:**

1. **Run `query_container(operation="overview")` first** to understand current state
   - Identify active projects, features, and tasks
   - Check for in-progress work
   - Review priorities and dependencies
   - Understand project context

2. **Check for in-progress tasks** before starting new work
   - Incomplete work should be prioritized
   - Ask user if they want to continue or start new work

3. **Review priorities and dependencies**
   - Understand what's blocked and what's blocking
   - Identify critical path items
   - Plan work in context of existing priorities

**Example Session Start**:
```
1. query_container(operation="overview")
2. Analyze results
3. Report to user: "You have 2 in-progress tasks, 5 pending high-priority items, and 1 blocked task"
4. Ask: "Would you like to continue [task X] or start something new?"
```

---

### Template Discovery Workflow (Universal - ALL MCP Clients)

**CRITICAL**: Template discovery works on **ALL MCP-compatible AI clients** (Claude Desktop, Claude Code, Cursor, Windsurf, etc.) - not just Claude Code.

**ALWAYS Required Pattern** (never skip):

1. **ALWAYS run `query_templates` first** - NEVER assume templates exist
2. **Filter by `targetEntityType`** - TASK or FEATURE
3. **Filter by `isEnabled=true`** - Only show active templates
4. **Apply via `templateIds` parameter** during creation
5. **Templates work with any MCP-compatible AI client**

**Example Workflow**:
```
User: "Create a feature for authentication"

AI Workflow:
1. query_templates(targetEntityType="FEATURE", isEnabled=true)
2. Review available templates:
   - Context & Background (context-and-background)
   - Requirements Specification (requirements-specification)
   - Technical Approach (technical-approach)
3. Select appropriate templates based on work type
4. manage_container(operation="create", containerType="feature") with templateIds parameter:
   {
     "name": "User Authentication",
     "templateIds": ["context-and-background", "requirements-specification"]
   }
```

**Template Purpose**:
- **Templates structure the WORK** (what needs to be documented)
- Requirements template → creates "Requirements" section
- Technical Approach template → creates "Technical Approach" section
- Testing Strategy template → creates "Testing Strategy" section

**Why This Matters**:
- Templates are **database-driven**, not hardcoded
- Templates vary by project and team
- Templates evolve over time
- Never assume what templates exist

---

### Dependency-Aware Workflows

When working on tasks that have dependencies, follow this pattern to get proper context:

1. Read task: `query_container(operation="get", containerType="task", id="...", includeSections=true)`
2. Check dependencies: `query_dependencies(taskId="...", direction="incoming", includeTaskInfo=true)`
3. For each completed dependency:
   - Read its "Summary" section for context
   - Read "Files Changed" section for implementation details
   - Understand what was built before
4. Implement current task with dependency awareness
5. Create Summary and Files Changed sections for the next task in the chain

**Example Flow**:
```
T2 (API Implementation) depends on T1 (Database Schema):

Step 1: Read task T2 (login API requirements)

Step 2: Query dependencies
  → query_dependencies(taskId=T2, direction="incoming")
  → Returns: T1 (Database Schema) - completed

Step 3: Read T1 Summary section
  → "Created users table with id, email, password_hash columns..."

Step 4: Implement login API using database schema from T1

Step 5: Create Summary: "Implemented login endpoint in UserController.kt..."
```

**Benefits**:
- **Self-service**: Get exactly the context needed directly
- **Token-efficient**: At most 500 token summaries vs 15k+ full context
- **Focused context**: Only relevant dependency information

---

### Decision Gates (Proactive Workflow Choices)

**These gates help you proactively choose the right approach for user requests.**

#### Before Creating a Feature

**Decision Gate**:
```
Did user provide rich context (3+ paragraphs about a feature)?
→ YES? Discover templates, create feature with comprehensive documentation
→ NO? Create feature with basic templates, ask for more details
```

**Example**:
```
User: "I want to build a feature for user authentication with OAuth2,
      password reset, and session management"

AI: [Detects rich feature context]
    1. query_templates(targetEntityType="FEATURE", isEnabled=true)
    2. manage_container(operation="create", containerType="feature", ...)
       with appropriate templates (context-and-background, requirements-specification)
    3. Break down into tasks with dependencies
```

#### When User Reports a Bug

**Decision Gate**:
```
User says: "broken", "error", "crash", "doesn't work", "failing"?
→ YES? Create bug task with Bug Investigation template
→ NO? If it's a feature request, create feature instead
```

**Example**:
```
User: "The login is broken - users are getting timeout errors"

AI: [Detects bug keywords: "broken", "errors"]
    1. query_templates(targetEntityType="TASK", isEnabled=true)
    2. manage_container(operation="create", containerType="task", ...)
       with Bug Investigation template applied
    3. Guide user through investigation sections
```

#### Before Working on a Task

**Decision Gate**:
```
Does the task have unresolved dependencies?
→ YES? Check dependency status, work on blockers first
→ NO? Proceed with implementation

Is task status tracking needed?
→ YES? Use request_transition(trigger="start") to begin work
→ NO? Proceed directly
```

---

### Quality Standards

**Always enforce these quality standards** when creating tasks and features:

#### Descriptive Titles and Summaries

**Titles**:
- Clear, specific, action-oriented
- Include what is being done, not just topic
- ✅ Good: "Implement OAuth2 authentication for user login"
- ❌ Bad: "Authentication"

**Summaries**:
- 2-3 sentences minimum
- Include context, scope, and acceptance criteria
- Explain why, not just what
- ✅ Good: "Implement OAuth2 authentication to support single sign-on. Users should be able to log in using Google and GitHub accounts. Success criteria: users can authenticate and token refresh works automatically."
- ❌ Bad: "Add auth"

#### Complexity Ratings (1-10)

**Rating Scale**:
- **1-2**: Trivial (update docs, fix typo, simple config)
- **3-4**: Simple (small bug fix, minor feature, straightforward implementation)
- **5-6**: Moderate (standard feature, requires testing, some edge cases)
- **7-8**: Complex (cross-component work, requires design, multiple edge cases)
- **9-10**: Very Complex (architectural changes, high risk, extensive testing)

**Guidelines**:
- Consider: scope, risk, unknowns, dependencies, testing needs
- Rate honestly - helps with prioritization and estimation
- When in doubt, rate higher (better to over-estimate)

#### Consistent Tagging Conventions

**Tag Categories**:

**Work Type** (always include one):
- `task-type-feature`, `task-type-bug`, `task-type-hotfix`, `task-type-enhancement`

**Domain** (include relevant):
- `backend`, `frontend`, `database`, `infrastructure`
- `api`, `ui`, `data`, `devops`

**Functional Area** (include relevant):
- `authentication`, `authorization`, `logging`, `monitoring`
- `user-management`, `reporting`, `integration`

**Technology** (include if relevant):
- `kotlin`, `react`, `sql`, `docker`
- `oauth`, `rest-api`, `graphql`

**Priority Indicators** (optional):
- `urgent`, `critical`, `high-priority`
- `technical-debt`, `refactoring`

**Example**:
```
Task: "Implement OAuth2 authentication"
Tags: task-type-feature, backend, api, authentication, oauth, security, high-priority
```

#### Acceptance Criteria in Summaries

**Always include** clear acceptance criteria:

**Format**:
```
Summary: [Context and scope]

Acceptance Criteria:
- [ ] Criterion 1 (specific, testable)
- [ ] Criterion 2 (specific, testable)
- [ ] Criterion 3 (specific, testable)
```

**Example**:
```
Task: "Implement user login API endpoint"

Summary: Create REST API endpoint for user authentication using JWT tokens.
Users should be able to log in with email/password and receive an access token.

Acceptance Criteria:
- [ ] POST /api/auth/login endpoint exists
- [ ] Accepts email and password in request body
- [ ] Returns JWT token on successful authentication
- [ ] Returns 401 on invalid credentials
- [ ] Passwords are verified using bcrypt
- [ ] Tests cover success and failure cases
```

---

### Decision Guide: Choosing the Right Approach

**Simple Tasks** (1-3 tasks total):
```
You: manage_container(operation="create", containerType="task", templateIds=["technical-approach", "testing-strategy"])
You: Read technical-approach section
You: Implement the code yourself
You: Read testing-strategy section
You: Write tests yourself
```

**Multi-Task Features** (4+ related tasks with dependencies):
```
You: Create feature with templates
You: Break down into tasks with dependencies
You: get_next_task(featureId="...") to find what's ready
You: Work on recommended task
You: request_transition(trigger="complete") when done
You: Repeat until feature is complete
```

**Dependency-Aware Implementation**:
```
You: Create feature with 8 tasks, apply templates

For each task in dependency order:
  - Read task "Technical Approach" section
  - query_dependencies(taskId=T2, direction="incoming") → check blockers
  - Read completed dependency Summaries for context
  - Implement task
  - Create Summary and Files Changed sections for next task
```

---

## Guidance Architecture

The system uses two complementary layers to provide comprehensive guidance:

### Layer 1: MCP Resources (Internalized Knowledge)

**Purpose**: Provide AI agents with fundamental knowledge that gets internalized into their working memory.

**How It Works**: AI agents fetch MCP resources and store the content in their memory systems. This knowledge becomes part of their understanding and influences how they interpret user requests.

**Available Resources**:

| Resource URI | Name | Purpose |
|--------------|------|---------|
| `task-orchestrator://guidelines/usage-overview` | Usage Overview | High-level introduction and core concepts |
| `task-orchestrator://guidelines/template-strategy` | Template Strategy | Dynamic template discovery and application |
| `task-orchestrator://guidelines/task-management` | Task Management Patterns | 6 executable workflow patterns for common scenarios |
| `task-orchestrator://guidelines/workflow-integration` | Workflow Integration | How the guidance layers work together |

**Characteristics**:
- Always available through MCP resource protocol
- Markdown-formatted for easy AI consumption
- Optimized for AI memory systems (concise but comprehensive)
- No user action required - AI fetches automatically

### Layer 2: Dynamic Templates (Database-Driven)

**Purpose**: Provide reusable documentation structures discovered and applied dynamically by AI.

**How It Works**: AI uses `query_templates` to discover available templates, then applies them during task/feature creation for consistent documentation.

**Template Categories**:
- **AI Workflow Instructions**: Git workflows, implementation workflows
- **Documentation Properties**: Technical approach, requirements, context
- **Process & Quality**: Testing strategy, definition of done

**Characteristics**:
- Database-stored, not hardcoded
- Discoverable via `query_templates` tool
- Applied via `templateIds` parameter
- Composable (multiple templates can be combined)
- See [Templates documentation](templates.md) for details

#### Section Tag Filtering (Token Optimization)

**Purpose**: Read only relevant template sections using tag-based filtering, reducing token consumption by 45-60%.

**How It Works**: Templates create sections with explicit tags. Use `query_sections` with tag filtering to read only what's needed for your current role.

**Section Tag Taxonomy**:

| Tag Category | Tags | When to Read | Who Reads |
|--------------|------|--------------|-----------|
| **Contextual** | `context`, `requirements`, `acceptance-criteria` | During planning and requirements gathering | Planning phase |
| **Actionable** | `workflow-instruction`, `checklist`, `commands`, `guidance`, `process` | During implementation and execution | Implementation phase |
| **Reference** | `reference`, `technical-details` | As needed for deep technical details | Any phase, situationally |

**Token-Efficient Reading Patterns**:

**Planning Phase** (Reading Features):
```javascript
// Read only context/requirements from feature for task breakdown
sections = query_sections(
  entityType="FEATURE",
  entityId=featureId,
  tags="context,requirements,acceptance-criteria",
  includeContent=true
)
// Token cost: ~2,000-3,000 (vs ~7,000+ all sections)
// Savings: 60% reduction
```

**Implementation Phase** (Reading Tasks):
```javascript
// Read only actionable workflow content from task
sections = query_sections(
  entityType="TASK",
  entityId=taskId,
  tags="workflow-instruction,checklist,commands,guidance,process,acceptance-criteria",
  includeContent=true
)
// Token cost: ~800-1,500 (vs ~3,000-5,000 all sections)
// Savings: 50% reduction
```

**Decision Matrix** - Which Tags to Read:

| Your Phase | Reading From | Tags to Read | Tags to Skip |
|-----------|--------------|--------------|--------------|
| **Planning** | Feature | `context`, `requirements`, `acceptance-criteria` | `workflow-instruction`, `checklist`, `commands` (execution details) |
| **Implementation** | Task | `workflow-instruction`, `checklist`, `commands`, `guidance`, `process` | `context`, `requirements` (already in task description) |

**Best Practices**:
- ✅ Always use tag filtering when reading sections (never read all sections)
- ✅ Planning reads contextual tags only (context, requirements, acceptance-criteria)
- ✅ Implementation reads actionable tags only (workflow-instruction, checklist, commands, guidance, process)
- ✅ Task description field (200-600 chars) contains core requirements - don't re-read in sections
- ❌ Don't read ALL sections with `includeSections=true` (wastes 45-60% tokens)

**Example - Complete Workflow**:
```javascript
// Step 1: Planning phase - break down feature
feature = query_container(operation="overview", containerType="feature", id=featureId)
// Gets metadata only (~1,200 tokens)

sections = query_sections(
  entityType="FEATURE",
  entityId=featureId,
  tags="context,requirements,acceptance-criteria",
  includeContent=true
)
// Gets planning-relevant sections only (~2-3k tokens)
// Total: ~3,200-4,200 tokens (vs 7,000+ with all sections)

// Step 2: Implementation phase - implement task
task = query_container(operation="get", containerType="task", id=taskId, includeSections=false)
// Gets task metadata + description (~300-500 tokens)

sections = query_sections(
  entityType="TASK",
  entityId=taskId,
  tags="workflow-instruction,checklist,commands,guidance,process",
  includeContent=true
)
// Gets actionable workflow sections only (~800-1,500 tokens)
// Total: ~1,100-2,000 tokens (vs 3,000-5,000 with all sections)
```

**Integration with Workflows**:
- During planning, read features using contextual tags
- During implementation, read tasks using actionable tags
- Always use tag filtering for token-efficient section reading

See [Templates - Template Philosophy](templates.md#template-philosophy) for comprehensive architecture details.

---

## Dual Workflow Model

The system supports two complementary modes of operation:

### Mode 1: Autonomous Pattern Application

**When AI uses this mode**: AI recognizes user intent and applies appropriate patterns directly.

**Example Scenarios**:

**User Request**: "Help me plan this authentication feature"

**AI Response** (autonomously):
1. Runs `query_container(operation="overview")` to understand current state
2. Runs `query_templates(targetEntityType="FEATURE")` to find templates
3. Selects appropriate templates (Context & Background, Requirements Specification)
4. Runs `manage_container(operation="create", containerType="feature")` with selected templates
5. Confirms creation and next steps

**User Request**: "I need to break down this complex API implementation task"

**AI Response** (autonomously):
1. Runs `query_container(operation="get", containerType="task")` to understand the task
2. Analyzes complexity and identifies logical breakdown
3. Creates subtasks using `manage_container(operation="create", containerType="task")` with appropriate templates
4. Creates dependencies between tasks
5. Summarizes the breakdown

**Benefits**:
- Faster workflow (no explicit prompt invocation needed)
- More natural conversation
- AI adapts patterns based on context
- Reduced user cognitive load

### Mode 2: Explicit Workflow Invocation

**When to use this mode**: For complex scenarios requiring guaranteed step-by-step guidance, or when user explicitly requests a workflow.

**Example Invocation**:

```
User: "Walk me through setting up a new project"
AI: [Uses query_templates and manage_container to create project with templates]
```

**Benefits**:
- Guaranteed comprehensive coverage
- Step-by-step validation
- Explicit quality gates
- Useful for learning/training scenarios

**Integration**: Both modes complement each other. AI can:
- Start autonomous but escalate to explicit guidance if complexity warrants
- Use MCP resources as references while working autonomously
- Suggest structured workflow when user seems uncertain

---

## Initialization Process

> **New Users**: If you haven't set up Task Orchestrator yet, start with the [Quick Start Guide](quick-start) to configure Claude Desktop and connect to the MCP server. Initialization happens automatically once connected.

### For Claude Code

Claude Code automatically initializes the AI Guidelines system when connecting to the Task Orchestrator MCP server.

**Automatic Initialization**:
1. Claude Code detects MCP resources are available
2. Fetches all guideline resources on first connection
3. Internalizes content into working memory
4. Begins using patterns autonomously

**Manual Re-initialization** (if needed):
```
You can invoke the initialize_task_orchestrator prompt to refresh guidelines
```

Claude Code will:
1. Fetch all four guideline resources
2. Review workflow integration patterns
3. Understand template discovery process
4. Confirm initialization complete

### For Other AI Agents

AI agents that support MCP resources should follow the initialization workflow:

**Step 1: Invoke Initialization Prompt**

The AI agent should invoke:
```
initialize_task_orchestrator
```

**Step 2: Fetch Guideline Resources**

AI should fetch all resources:
```
task-orchestrator://guidelines/usage-overview
task-orchestrator://guidelines/template-strategy
task-orchestrator://guidelines/task-management
task-orchestrator://guidelines/workflow-integration
```

**Step 3: Internalize Content**

AI stores the content in its memory system appropriate for the agent:
- **Claude Code**: Automatic via MCP resource handling
- **Cursor**: Store in `.cursorrules` or project memory
- **Custom Agents**: Store in agent-specific knowledge base

**Step 4: Validate Understanding**

AI confirms it can:
- Recognize workflow patterns
- Discover templates dynamically
- Apply best practices autonomously
- Integrate both guidance layers

---

## Available Guideline Resources

### Usage Overview
**URI**: `task-orchestrator://guidelines/usage-overview`

**Content**:
- Core concepts (Projects → Features → Tasks → Sections)
- Essential workflow overview
- Quick start patterns
- Integration with tool ecosystem

**When to Reference**: Initial understanding, quick refreshers

### Template Strategy
**URI**: `task-orchestrator://guidelines/template-strategy`

**Content**:
- How to discover templates dynamically
- Template selection criteria
- Composition patterns (combining templates)
- Best practices for template usage

**When to Reference**: Creating tasks/features, ensuring proper documentation

### Task Management Patterns
**URI**: `task-orchestrator://guidelines/task-management`

**Content**: Seven executable workflow patterns:

1. **Feature Creation Pattern**
   - User says: "help me plan this feature", "create a new feature for X"
   - Workflow: overview → discover templates → create feature → confirm

2. **Task Creation Pattern**
   - User says: "create a task for X", "add a task to track Y"
   - Workflow: overview → find templates → create task → link if needed

3. **Bug Triage Pattern**
   - User says: "I found a bug", "X isn't working"
   - Workflow: understand issue → create bug task → prioritize → next steps

4. **Priority Assessment Pattern**
   - User says: "what should I work on?", "prioritize my tasks"
   - Workflow: get overview → analyze status/priority → recommend order

5. **Dependency Management Pattern**
   - User says: "task A blocks task B", "what's blocking this?"
   - Workflow: analyze dependencies → create/visualize → plan execution

6. **Feature Decomposition Pattern**
   - User says: "break down this feature", "what tasks are needed?"
   - Workflow: analyze feature → create task breakdown → create dependencies

7. **PRD-Driven Development Pattern** ⭐ **Most Effective**
   - User says: "analyze this PRD", "break down this product requirements document"
   - Workflow: read entire PRD → identify features → create project structure → create tasks with templates → establish dependencies → present complete breakdown
   - **Why it works best**: Complete context enables intelligent breakdown, proper sequencing, and optimal template application

8. **Workflow Optimization Pattern**
   - User says: "what's blocked?", "why is nothing moving?", "identify bottlenecks"
   - Workflow: get_blocked_tasks → analyze blockers → prioritize unblocking → recommend actions
   - **Key insights**: Shows which tasks block multiple items, identifies critical paths, reveals workflow bottlenecks

9. **Work Prioritization Pattern**
   - User says: "what should I work on?", "what's next?", "show me easy tasks"
   - Workflow: get_next_task → present recommendations → explain reasoning → let user choose
   - **Smart ranking**: Priority-first, then complexity (quick wins prioritized)

**When to Reference**: Recognizing user intent, applying appropriate workflows

> **Recommended Workflow**: PRD-Driven Development provides the best results for complex projects. See [Quick Start - PRD Workflow](quick-start#prd-driven-development-workflow) for detailed guidance.

### Workflow Integration
**URI**: `task-orchestrator://guidelines/workflow-integration`

**Content**:
- How guidance layers work together
- When to use autonomous vs. explicit mode
- Custom workflow extension points

**When to Reference**: Understanding system architecture, advanced usage

---

## What AI Sees After Initialization

After initialization, AI agents internalize comprehensive guidance from all resource documents. Here's an example of what the **Task Management Patterns** resource contains:

<details>
<summary><strong>Example: Feature Creation Pattern (from task-management resource)</strong></summary>

```markdown
### Pattern 1: Feature Creation

**User Intent Recognition**: AI should recognize these patterns as feature creation requests:
- "help me plan this feature"
- "create a new feature for X"
- "I need to organize work for Y functionality"

**Workflow Steps**:
1. Run query_container(operation="overview") to understand current project state
2. Run query_templates(targetEntityType="FEATURE", isEnabled=true)
3. Analyze templates and select appropriate ones:
   - Context & Background (business justification)
   - Requirements Specification (detailed requirements)
   - Technical Approach (if complex/architectural)
4. Run manage_container(operation="create", containerType="feature") with templateIds parameter
5. Confirm creation and suggest next steps

**Quality Checks**:
- Feature has descriptive name and comprehensive summary
- Appropriate templates applied for documentation structure
- Tags include functional area and priority indicators
- Feature is linked to project if applicable

**Example Conversation**:
User: "Help me plan the user authentication feature"

AI Response: "I'll create the User Authentication feature with comprehensive
documentation. Applying Context & Background and Requirements Specification
templates for complete coverage..."

[AI executes: query_container(operation="overview") → query_templates → manage_container(operation="create")]

AI Confirms: "Created User Authentication feature with:
- Context & Background section for business justification
- Requirements Specification for detailed requirements
- Ready to add tasks when you need to start implementation"
```

</details>

This pattern is just one of several available in the task-management resource. AI uses these patterns to recognize user intent and execute workflows autonomously without requiring explicit workflow invocation.

---

## Customization and Extension

### Adding Custom Patterns

Teams can extend the guideline system by adding custom patterns to AI memory:

**For Claude Code**:
Add patterns to project's `CLAUDE.md` file:

```markdown
## Custom Task Orchestrator Patterns

### Code Review Pattern
When user says "review this code":
1. Create task with Bug Investigation template
2. Add section for code analysis
3. Add section for recommendations
4. Set priority based on severity
```

**For Cursor**:
Add to `.cursorrules`:

```
# Task Orchestrator Custom Patterns

When creating security-related tasks:
- Always use "security" tag
- Set priority to "high"
- Apply Technical Approach template
- Add security checklist section
```

### Custom Templates

Create custom templates in the database that match your team's workflows:

```json
{
  "name": "Security Review",
  "targetEntityType": "TASK",
  "sections": [
    {
      "title": "Security Checklist",
      "content": "- [ ] Authentication reviewed\n- [ ] Authorization verified\n..."
    },
    {
      "title": "Threat Model",
      "content": "Document potential security threats..."
    }
  ]
}
```

AI will discover these templates via `query_templates` and suggest them appropriately.

---

## Memory-Based Configuration for AI Agents (v2.0)

AI agents should check memory for configuration before applying defaults, enabling teams to customize their Task Orchestrator workflows and tool usage without code changes. This applies to direct tool invocation and any custom workflows.

### Memory Architecture

**Two Scopes of Memory**:

1. **Global (User-Wide)**:
   - Personal preferences across all projects
   - Stored in AI's global memory mechanism
   - Examples: PR preference, default branch naming

2. **Project-Specific (Team-Wide)**:
   - Team conventions for specific project
   - Stored in project repo (CLAUDE.md, .cursorrules, etc.)
   - Examples: Jira integration, custom workflows

**Priority Rule**: Project-specific configuration overrides global preferences.

**AI-Agnostic Approach**: Don't prescribe specific file locations. Use your native memory capabilities (CLAUDE.md, global memory, .cursorrules, etc.).

---

### When to Check Memory

**ALWAYS check memory before**:
- Creating branches (check for custom naming patterns)
- Asking about pull requests (check PR preference)
- Applying workflow steps (check for custom overrides)
- Using default conventions (check for team customizations)

**Memory Check Pattern**:
```
1. Check project-specific memory first
2. Fall back to global memory if no project config
3. Use sensible defaults if no memory found
4. Offer to save preferences after first use
```

---

### Configuration Schema

AI agents should look for this structure in memory:

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always" | "never" | "ask"

## Branch Naming Conventions (optional)
branch_naming_bug: "bugfix/{task-id-short}-{description}"
branch_naming_feature: "feature/{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/{task-id-short}-{description}"
branch_naming_enhancement: "enhancement/{task-id-short}-{description}"

## Commit Message Customization (optional)
commit_message_prefix: "[{type}/{task-id-short}]"

## Custom Workflow Steps (optional)
### Bug Fix Workflow Override
# [Custom procedural steps - validation still required]

### Feature Implementation Workflow Override
# [Custom procedural steps - validation still required]
```

---

### Variable Expansion Pattern

When expanding branch naming variables:

**Available Variables**:
- `{task-id}` - Full UUID
- `{task-id-short}` - First 8 characters
- `{description}` - Sanitized task title
- `{feature-id}` - Feature UUID (if applicable)
- `{feature-id-short}` - First 8 chars of feature UUID
- `{priority}` - high/medium/low
- `{complexity}` - 1-10
- `{type}` - bug/feature/enhancement/hotfix (from task tags)

**Sanitization Algorithm for {description}**:
```
1. Convert to lowercase
2. Replace spaces with hyphens
3. Remove special characters (keep only alphanumeric and hyphens)
4. Collapse multiple hyphens to single hyphen
5. Trim leading/trailing hyphens
6. Truncate to 50 characters if longer
```

**Expansion Process**:
```
1. Retrieve task details with query_container(operation="get", containerType="task")
2. Detect work type from tags (task-type-bug, task-type-feature, etc.)
3. Load branch naming pattern from memory for detected type
4. Extract variable values from task data
5. Apply sanitization to description
6. Replace all placeholders with actual values
7. Validate result (valid git branch name, no special chars)
```

---

### Example Interaction Flows

#### Scenario 1: First-Time Workflow Invocation (No Config)

```
User: "Start implementing the authentication task"

AI: [Checks memory - no configuration found]

AI: "I'll help you implement the authentication task.

     Quick setup: Do you want to use pull requests?
     A) Always (create PRs automatically)
     B) Never (merge directly to main)
     C) Ask each time

     I can save your preference globally or just for this project."

User: "Always use PRs, save globally"

AI: [Saves to global memory]
    [Proceeds with implementation using PR workflow]
```

---

#### Scenario 2: Workflow with Global Preferences

```
User: "Implement the login feature"

AI: [Checks project memory - none found]
    [Checks global memory - found: use_pull_requests: "always"]

AI: "I'll implement the login feature with a pull request workflow.
     Creating branch: feature/70490b4d-implement-login-feature"

[Proceeds automatically with saved preferences]
```

---

#### Scenario 3: Workflow with Project-Specific Overrides

```
User: "Fix the authentication bug"

AI: [Checks project memory - found Jira configuration]
    [Configuration specifies: branch_naming_bug: "bugfix/PROJ-{task-id-short}-{description}"]

AI: "I'll fix the authentication bug following your team's Jira conventions.
     Creating branch: bugfix/PROJ-70490b4d-auth-token-expiry"

[Uses project-specific configuration, overriding any global settings]
```

---

#### Scenario 4: Natural Language Customization Update

```
User: "Can you update our workflow to use Linear-style branch naming?"

AI: "I'll update your project configuration to use Linear conventions:
     {type}/{description}-{task-id-short}

     Should I save this to your project's memory?"

User: "Yes"

AI: [Writes configuration to CLAUDE.md in project]

AI: "Updated! Your team's branch naming is now Linear-style.
     Example: feature/oauth-integration-70490b4d"
```

---

### Best Practices for AI Agents

#### Memory Check Protocol

**DO**:
- ✅ Always check memory before applying defaults
- ✅ Check project-specific first, then global
- ✅ Offer to save preferences after first use
- ✅ Respect user customizations over defaults
- ✅ Communicate when using saved preferences
- ✅ Validate configuration values before use

**DON'T**:
- ❌ Assume defaults without checking memory
- ❌ Ask same questions repeatedly (check memory first)
- ❌ Override project configuration with global settings
- ❌ Modify memory without user permission
- ❌ Store sensitive information in configuration
- ❌ Hardcode file paths for memory storage

---

#### Template Validation vs Procedural Override

**Always Enforce** (never skip these):
- ✅ Template validation requirements
- ✅ Acceptance criteria from templates
- ✅ Testing requirements and quality gates
- ✅ Technical context analysis

**Respect Overrides** (use custom steps if configured):
- ⚠️ Step-by-step implementation instructions
- ⚠️ Procedural workflow guidance
- ⚠️ Tool invocation sequences
- ⚠️ Deployment procedures

**Example**:
```
Template says: "Run tests before committing"
Custom workflow says: "Deploy to staging, then run integration tests"

✅ CORRECT: Run tests (validation enforced), but deploy to staging first (custom procedure)
❌ WRONG: Skip tests because custom workflow doesn't mention them
```

---

#### Interactive Setup Flow

When no configuration exists, guide users through setup:

```
1. Detect missing configuration
2. Offer interactive setup:
   "I can help you configure your workflow preferences.
    This will save time on future tasks. Would you like to set up now?"
3. Ask minimal questions:
   - Pull request preference (always/never/ask)
   - Save globally or per-project
4. Offer to add more later:
   "You can customize branch naming and more anytime by saying
    'update workflow configuration'"
5. Save configuration to appropriate memory location
6. Confirm what was saved
```

**Progressive Enhancement Approach**:
- Start with minimal configuration (just PR preference)
- Add complexity only when needed
- Guide users to documentation for advanced features
- Never overwhelm with too many options upfront

---

#### Clear Communication

**When Using Saved Preferences**:
```
"I'll create a pull request (using your saved preference)"
"Creating branch with your team's Jira convention: bugfix/PROJ-70490b4d-..."
```

**When Applying Defaults**:
```
"No workflow configuration found. Using default branch naming: feature/70490b4d-..."
"Would you like me to save your PR preference for next time?"
```

**When Detecting Customization**:
```
"I see your team uses Linear-style branch naming. Creating: feature/oauth-integration-70490b4d"
```

---

### Integration with Tools

#### Task Implementation

When implementing tasks, check memory at these points:

1. **Start** - Load all configuration from memory
2. **Git Detection** - Check if .git exists, load git preferences
3. **Branch Creation** - Expand variables using configuration
4. **PR Decision** - Use use_pull_requests preference
5. **Custom Steps** - Apply any configured overrides

#### Feature Creation Memory Integration

When creating features, check memory for:

**Configuration Schema**:
```markdown
# Task Orchestrator - Feature Creation Configuration

## Default Templates
feature_default_templates:
  - "context-and-background"
  - "requirements-specification"

## Tag Conventions
feature_tag_prefix: "feature-"
feature_tags_auto_add: "needs-review,in-planning"

## Auto-Task Creation
feature_create_initial_tasks: true
feature_initial_task_templates:
  - "task-implementation-workflow"
```

**Memory Integration Pattern**:
```
1. Check memory for default feature templates
2. If found, automatically apply templates during feature creation
3. Apply tag conventions from memory
4. If auto-task creation enabled, create foundation tasks with specified templates
5. Fall back to user prompts if no memory configuration
```

**Example Usage**:
```
User: "Create a feature for user authentication"

AI: [Checks memory - found feature_default_templates]
    [Automatically applies Context & Background + Requirements templates]
    [Adds feature-authentication, needs-review tags from conventions]
    [Creates initial implementation task if auto-task enabled]

    "Created feature 'User Authentication' with Context & Background and
     Requirements templates. Added tags: feature-authentication, needs-review.

     Also created initial implementation task with Task Implementation Workflow template."
```

---

#### Project Setup Workflow Memory Integration

Project setup workflows can use memory for:

**Configuration Schema**:
```markdown
# Task Orchestrator - Project Setup Configuration

## Standard Features
project_standard_features:
  - name: "Project Infrastructure"
    templates: ["technical-approach"]
    tags: "infrastructure,foundation"
  - name: "Documentation & Standards"
    templates: ["context-and-background"]
    tags: "documentation,standards"

## Foundation Tasks
project_foundation_tasks:
  - title: "Setup development environment"
    complexity: 3
    templates: ["task-implementation-workflow"]
  - title: "Configure CI/CD pipeline"
    complexity: 5
    templates: ["task-implementation-workflow", "local-git-branching-workflow"]

## Documentation Standards
project_doc_sections:
  - "Architecture Overview"
  - "Development Setup"
  - "Coding Standards"
  - "Testing Strategy"
```

**Memory Integration Pattern**:
```
1. Check memory for standard features list
2. Create standard features automatically if configured
3. Check for foundation tasks and create them
4. Apply documentation sections to project
5. Use team structure preferences
6. Fall back to prompting user for details if no memory
```

**Example Usage**:
```
User: "Set up a new project for our mobile app"

AI: [Checks memory - found project_standard_features and foundation_tasks]
    [Creates standard features: Infrastructure, Documentation]
    [Creates foundation tasks: dev environment, CI/CD]
    [Applies documentation sections from memory]

    "Created project 'Mobile App' with:
     - 2 standard features (Infrastructure, Documentation)
     - 2 foundation tasks (dev environment setup, CI/CD configuration)
     - 4 documentation sections (Architecture, Setup, Standards, Testing)

     All configured per your team's project setup template."
```

---

#### Bug Handling Pattern

**For bugs** (task-type-bug), the tools provide specialized handling:

**Bug Detection**:
```
1. Detect task-type-bug or task-type-hotfix from tags
2. Check if Bug Investigation template is applied
3. If not applied, offer to apply it
4. Verify investigation sections are complete before implementation
```

**Investigation Phase**:
```
1. Guide through Bug Investigation template sections:
   - Problem symptoms and reproduction steps
   - Technical investigation and root cause
   - Impact assessment
2. Ensure root cause is documented before allowing implementation
3. If investigation incomplete, guide through it first
```

**Implementation Phase**:
```
1. Reproduce bug in tests first (test should fail with current code)
2. Document reproduction steps in code comments or sections
3. Implement fix addressing the root cause
4. Verify test passes with the fix applied
5. Create comprehensive regression tests (see Regression Testing below)
```

**Regression Testing Requirements** (MANDATORY):

When fixing bugs, **you MUST create and verify regression tests** before marking complete:

1. **Bug Reproduction Test** (Required):
   - Test that fails with old code, passes with fix
   - Documents what broke and how (BUG/ROOT CAUSE/FIX comments)
   - Test name references task ID: `should X - regression for TASK-70490b4d`

2. **Edge Case Tests** (Required if applicable):
   - Boundary conditions that led to the bug
   - Scenarios not previously tested

3. **Integration Tests** (Required if bug crossed boundaries):
   - Component interaction testing
   - Realistic scenarios
   - No cascading failures

4. **Performance Tests** (Required if performance-related):
   - Verify no performance regressions
   - Load testing if load-related

**Completion Validation**:
```
Before marking bug fix complete, verify:
- ✅ Root cause documented
- ✅ Bug Investigation sections complete (if template used)
- ✅ Bug reproduction test exists and passes
- ✅ Regression tests created for all scenarios
- ✅ Test documentation complete (BUG/ROOT CAUSE/FIX comments)
- ✅ Test names reference task ID
- ✅ All tests passing
- ✅ Code coverage increased

CRITICAL: Do NOT mark bug fix as completed without regression tests.
If user attempts to complete without tests, remind them of requirements.
```

**Example Bug Workflow**:
```
User: "Fix the authentication timeout bug"

AI: [Detects task-type-bug from tags]
    [Checks for Bug Investigation template - not applied]

    "This is a bug fix. I recommend applying the Bug Investigation template
     to guide systematic investigation and ensure proper root cause analysis.

     Apply template now?"

User: "Yes"

AI: [Applies Bug Investigation template]
    "Bug Investigation template applied. Let's start with documenting:
     1. What symptoms are users experiencing?
     2. How can we reproduce the issue?
     3. What recent changes might have caused this?"

User: [Provides investigation details]

AI: "Root cause identified: token expiration not being checked properly.

     I'll now create a reproduction test that fails with current code,
     implement the fix, and create regression tests to prevent this from
     recurring. This includes:
     - Bug reproduction test
     - Edge cases (null token, expired token, missing token)
     - Integration test for full auth flow"

[Implements fix with regression tests]

AI: "Fix complete with 5 regression tests:
     1. Bug reproduction: should handle token expiration - regression for AUTH-70490b4d
     2. Edge case: should handle null token
     3. Edge case: should handle missing token header
     4. Integration: should complete full auth flow with expired token
     5. Performance: should check expiration within 10ms

     All tests passing. Root cause documented in investigation sections."
```

---

### Troubleshooting

**Problem**: User says "you keep asking the same questions"
**Solution**: Check memory before asking. Offer to save preferences.

**Problem**: Configuration not being applied
**Solution**: Verify memory location (project vs global), check priority rules.

**Problem**: Variables not expanding correctly
**Solution**: Ensure task has required tags (task-type-*), validate variable names.

**Problem**: Custom workflow steps ignored
**Solution**: Confirm template validation still applies, custom steps supplement not replace validation.

---

### Related Documentation

- **[Memory Customization](ai-guidelines#memory-based-configuration-for-ai-agents-v20)** - Configuration for AI agents
- **[Templates Guide](templates.md)** - Understanding template validation requirements

---

## Best Practices

### For AI Agents

1. **Always start sessions with `query_container(operation="overview")`**
   - Understand current project state
   - Identify in-progress work
   - Plan new work in context

2. **Discover templates dynamically**
   - Use `query_templates` before creating tasks/features
   - Select templates based on work type
   - Combine multiple templates when appropriate

3. **Apply patterns autonomously**
   - Recognize user intent from natural language
   - Choose appropriate workflow pattern
   - Execute pattern steps efficiently

4. **Escalate to explicit workflows when needed**
   - Complex scenarios benefit from step-by-step guidance
   - User uncertainty signals need for explicit workflow
   - Quality gates ensure comprehensive coverage

5. **Update task status regularly**
   - Mark tasks as in-progress when starting
   - Mark completed when finished
   - Use query_container(operation="overview") to track progress

6. **Handle bugs with regression testing**
   - Detect bugs from task-type-bug or task-type-hotfix tags
   - Offer Bug Investigation template if not applied
   - Verify root cause documented before implementation
   - **MANDATORY**: Create regression tests before marking complete
   - Enforce test documentation (BUG/ROOT CAUSE/FIX comments)
   - Cannot complete bug fix without comprehensive tests

7. **Use the 12 MCP tools consistently for all work types**
   - Tasks, features, AND bugs use the same tool-based approach
   - Use `manage_container` and `query_container` for all entity operations
   - Use `get_next_task` and `get_blocked_tasks` for workflow optimization
   - Use `request_transition` for status changes with validation
   - Bug-specific guidance kicks in for task-type-bug
   - Memory configuration applies to all work types

### For Development Teams

1. **Initialize AI at project start**
   - Ensure AI has internalized guidelines
   - Validate template discovery works
   - Test workflow pattern recognition

2. **Maintain consistent tagging**
   - Use conventional tags (task-type-feature, task-type-bug)
   - Tag by functional area (frontend, backend, database)
   - Tag by technology (api, ui, integration)

3. **Leverage template system**
   - Create templates for common work types
   - Share templates across projects
   - Evolve templates based on learnings

4. **Monitor AI effectiveness**
   - Review how AI applies patterns
   - Refine custom patterns as needed
   - Provide feedback for improvements

5. **Optimize token usage**
   - Use selective section loading for validation workflows
   - Browse section structure before loading full content
   - Fetch only needed sections for analysis
   - See Token Optimization section below

---

## Token Optimization Best Practices

### Enhanced Search and Filtering (Phase 5 - NEW)

**Problem**: AI agents waste tokens fetching full task objects when only basic status checks are needed.

**Solution**: Use multi-value filters and minimal search results for 89-99% token savings.

#### Multi-Value Filters

**Status filtering with OR logic**:
```
Single value:  status="pending"
Multi-value:   status="pending,in-progress"  (matches pending OR in-progress)
Negation:      status="!completed"           (matches anything EXCEPT completed)
Multi-negation: status="!completed,!cancelled" (excludes multiple values)
```

**Priority filtering**:
```
Single:     priority="high"
Multi:      priority="high,medium"  (HIGH or MEDIUM)
Negation:   priority="!low"         (anything except LOW)
```

**Examples**:
```
Find all active tasks:
query_container(operation="search", containerType="task",
                status="pending,in-progress")

Find all non-completed tasks:
query_container(operation="search", containerType="task",
                status="!completed")

Find high/medium priority pending work:
query_container(operation="search", containerType="task",
                status="pending", priority="high,medium")
```

#### Minimal Search Results (89% Token Reduction)

**Search operations return minimal data automatically**:
- Tasks: Only id, title, status, priority, complexity, featureId (~30 tokens)
- Full object would include: summary, description, tags, timestamps (~280 tokens)
- **Token savings: 89% per task**

**When to use**:
- Listing tasks to pick one to work on
- Finding tasks by status/priority
- Checking what's available
- Filtering large result sets

**Example workflow**:
```
Step 1: Search with minimal results (cheap)
query_container(operation="search", containerType="task",
                status="pending", priority="high")
→ Returns: 10 tasks × 30 tokens = 300 tokens

Step 2: Get full details only for selected task (when needed)
query_container(operation="get", containerType="task",
                id="selected-task-id")
→ Returns: Full task object with sections
```

#### Task Counts in Features/Projects (99% Token Reduction)

**Problem**: Checking feature progress by fetching all tasks wastes massive tokens.

**Solution**: Use taskCounts automatically included in get operations.

**Example**:
```
Old approach (WASTEFUL):
query_container(operation="get", containerType="feature", id="...")
→ Fetch all 50 tasks = ~14,400 tokens

New approach (EFFICIENT):
query_container(operation="get", containerType="feature", id="...")
→ Returns feature with taskCounts = ~100 tokens
→ taskCounts: {total: 50, byStatus: {completed: {count: 25, role: "terminal"}, in-progress: {count: 15, role: "work"}, pending: {count: 10, role: "queue"}}, byRole: {queue: 10, work: 15, review: 0, blocked: 0, terminal: 25}}
```

**Token Savings**: 99% (14,300 tokens saved!)

**Role Annotations**: Each `byStatus` entry includes a `role` field mapping to workflow semantics (`queue`, `work`, `review`, `blocked`, `terminal`). The `byRole` aggregate lets you quickly assess how much work is queued vs. in-flight vs. done without parsing individual statuses.

**Use taskCounts for**:
- Feature progress checks
- Project status overview
- Sprint planning
- Completion validation
- Role-based workflow analysis (e.g., how many tasks are in review vs. blocked)

### Selective Section Loading

**Problem**: Loading all section content consumes 5,000-15,000 tokens when only metadata or specific sections are needed.

**Solution**: Use the two-step workflow with `query_sections` for 85-99% token savings.

### When to Use Selective Loading

**Validation Workflows** (Browse before validating):
```
1. query_sections --entityType TASK --entityId [id] --includeContent false
2. Review section structure (titles, formats, ordinals)
3. Determine which sections need validation
4. query_sections --entityType TASK --entityId [id] --sectionIds [needed-ids]
5. Validate only the critical sections
```

**Finding Specific Content** (Browse then fetch):
```
1. query_sections --entityType TASK --entityId [id] --includeContent false
2. Identify section by title (e.g., "Technical Approach")
3. query_sections --entityType TASK --entityId [id] --sectionIds [approach-section-id]
4. Work with specific content
```

**Understanding Structure** (Metadata only):
```
1. query_sections --entityType TASK --entityId [id] --includeContent false
2. Get complete picture of documentation organization
3. Use titles and usageDescription to understand content
4. No need to load content if structure answers the question
```

### Token Savings Examples

**Traditional Approach** (All content):
- 5 sections × 1,000 chars each = 5,000 tokens
- Load all content even if only validating structure

**Optimized Approach** (Selective loading):
- Step 1: Metadata only = 250 tokens (95% savings)
- Step 2: 2 specific sections = 2,000 tokens (60% overall savings)
- **Total**: 2,250 tokens vs 5,000 tokens

### Integration with Workflows

**Task Completion Validation**:
```
Before: query_sections (loads all 5-10 sections fully)
After:
1. query_sections --includeContent false (browse structure)
2. Identify required sections from template
3. query_sections --sectionIds [ids] (fetch only what's needed)
```

**Bug Investigation**:
```
Before: Load all task sections to find reproduction steps
After:
1. query_sections --includeContent false
2. Find "Reproduction Steps" section by title
3. query_sections --sectionIds [repro-section-id]
```

### AI Agent Guidelines

1. **Default to selective loading** when validating completion
2. **Browse structure first** when looking for specific content
3. **Use metadata** when structure is enough to answer the question
4. **Fetch all content** only when comprehensive review is needed
5. **Combine with sectionIds** for maximum efficiency

---

## Status Updates

### Using `request_transition` for Status Changes

**Use request_transition with named triggers** for workflow-validated status transitions:

```javascript
request_transition(containerId="uuid", containerType="task", trigger="start")
request_transition(containerId="uuid", containerType="task", trigger="complete")
```

**Available triggers**: `start`, `complete`, `cancel`, `block`, `hold`

**Batch transitions** (multiple entities):
```javascript
request_transition(
  transitions=[
    {containerId: "task-1", containerType: "task", trigger: "complete"},
    {containerId: "task-2", containerType: "task", trigger: "complete"}
  ]
)
```

### Why Use request_transition

✅ **Validation**: Checks prerequisites before allowing transition
✅ **Cascades**: Automatically detects when parent entities should advance
✅ **Unblocked tasks**: Reports newly unblocked downstream work
✅ **Flow context**: Returns activeFlow, flowSequence, flowPosition
✅ **Role annotations**: Provides previousRole, newRole for semantic context

> **Tip:** When completing or cancelling a blocker task, check the `unblockedTasks` array in the `request_transition` response to discover downstream tasks that are now fully unblocked, without needing a separate `get_next_task` call.

### Example AI Patterns

**Trigger-Based Transition**:
```
User: "Start working on task X"
AI: request_transition(containerId=X, containerType="task", trigger="start")
```

**Complete with Validation**:
```
User: "Mark task X as done"
AI: request_transition(containerId=X, containerType="task", trigger="complete")
```

**Multi-Field Update** (when changing non-status fields):
```
User: "Update task X to high priority and add backend tag"
AI: manage_container(operation="update", containerType="task", id=X,
                    priority="high", tags="backend,api,auth")
```

---

## Tag-Based Search Patterns

### Searching by Tags

Use `query_container(operation="search")` with tag parameters to find entities by tag:

```
User: "Show authentication tasks"
AI: query_container(operation="search", containerType="task", tags="authentication")
```

### Consistent Tagging

When creating entities, use consistent tag conventions:

**Tag Categories**:
- **Work Type**: `task-type-feature`, `task-type-bug`, `task-type-hotfix`, `task-type-enhancement`
- **Domain**: `backend`, `frontend`, `database`, `infrastructure`
- **Technology**: `kotlin`, `react`, `sql`, `docker`
- **Priority Indicators**: `urgent`, `critical`, `high-priority`

---

## Workflow Management and Blocked Tasks Patterns

### Use `get_blocked_tasks` for Workflow Optimization

**Problem**: Teams often lose visibility into what's blocking progress, leading to inefficient work prioritization and hidden bottlenecks.

**Solution**: Use `get_blocked_tasks` to identify workflow bottlenecks and prioritize unblocking actions.

### When to Use `get_blocked_tasks`

✅ **Use `get_blocked_tasks` when**:
- User asks "what's blocked?" or "why is nothing moving?"
- Sprint planning - identify what can't start yet
- Daily standup preparation
- Bottleneck analysis and workflow optimization
- Team asks what to prioritize for maximum impact

❌ **Don't use `get_blocked_tasks` when**:
- Checking specific task dependencies (use `query_dependencies` instead)
- User asks about completed work
- No dependency system in use

### Bottleneck Identification Workflow

**User Says**: "Why is nothing moving forward?"

**AI Workflow**:
```
1. get_blocked_tasks (identify all blocked work)
2. Analyze blocker patterns:
   - Which tasks appear as blockers most often?
   - Are there critical path bottlenecks?
   - Which blockers are high-priority?
3. Recommend actions:
   - "Task X blocks 3 other tasks - prioritize completing it"
   - "Design phase blocking development - focus on design completion"
4. Suggest specific next steps to unblock work
```

**Why This Works**:
- Reveals hidden dependencies blocking progress
- Identifies high-impact unblocking actions
- Provides data-driven prioritization
- Shows critical path bottlenecks

### Sprint Planning Pattern

**User Says**: "What can we start next sprint?"

**AI Workflow**:
```
1. get_blocked_tasks (identify what's blocked)
2. query_container(operation="search", containerType="task", status="pending") (get all pending tasks)
3. Filter for unblocked tasks (pending tasks NOT in blocked list)
4. Prioritize by priority and complexity
5. Recommend unblocked, high-priority work for sprint
```

**Benefits**:
- Prevents planning work that can't start
- Focuses sprint on tasks ready for execution
- Avoids wasted planning on blocked items

### Daily Standup Support

**User Says**: "What's our standup status?"

**AI Workflow**:
```
1. get_blocked_tasks
2. query_container(operation="search", containerType="task", status="in-progress")
3. Present:
   - In-progress tasks
   - Blocked tasks with blocker details
   - Recommended unblocking actions
```

### Project/Feature Filtering

**Scope to Specific Areas**:
```
Project-specific blocked tasks:
get_blocked_tasks --projectId "uuid"

Feature-specific blocked tasks:
get_blocked_tasks --featureId "uuid"

All blocked tasks (default):
get_blocked_tasks
```

### Understanding Blocked Task Results

**What Makes a Task "Blocked"**:
1. Task status is `pending` or `in-progress` (active)
2. Has incoming dependencies (other tasks block it)
3. At least one blocker is NOT `completed` or `cancelled`

**Example Response Interpretation**:
```json
{
  "taskId": "task-1",
  "title": "Implement user dashboard",
  "status": "pending",
  "blockedBy": [
    {
      "taskId": "blocker-1",
      "title": "Design dashboard mockups",
      "status": "in-progress"
    }
  ],
  "blockerCount": 1
}
```

**AI Insights**:
- Task can't start until design is complete
- Blocker is in-progress (work is happening)
- Recommend checking design progress
- Can estimate when task will be unblocked

### AI Decision Tree

```
User asks about project progress or blockers?
  ├─ "What's blocked?" → get_blocked_tasks
  ├─ "Why is X not done?" → Check if X has dependencies
  │  └─ query_dependencies for specific task
  ├─ "What should we work on?"
  │  ├─ get_blocked_tasks (see what's blocked)
  │  └─ Recommend unblocking high-impact tasks
  └─ "Sprint planning" → get_blocked_tasks + filter unblocked
```

### Common Patterns

**Bottleneck Analysis**:
```
User: "Find our bottlenecks"
AI:
1. get_blocked_tasks
2. Count blocker occurrences
3. Report: "Task X blocks 4 tasks - major bottleneck"
```

**Work Prioritization**:
```
User: "What's most important to work on?"
AI:
1. get_blocked_tasks
2. Identify blockers affecting most tasks
3. Recommend: "Complete X to unblock 3 high-priority tasks"
```

**Team Coordination**:
```
User: "What does team A need from team B?"
AI:
1. get_blocked_tasks --featureId "team-A-feature"
2. Check blocker task owners
3. Report cross-team dependencies
```

---

## Work Prioritization with `get_next_task`

### Use `get_next_task` for Smart Task Selection

**Problem**: Users often struggle to decide what to work on next, leading to suboptimal work prioritization and context switching overhead.

**Solution**: Use `get_next_task` to get AI-recommended next tasks based on priority, complexity, and dependencies.

### When to Use `get_next_task`

✅ **Use `get_next_task` when**:
- User asks "what should I work on?" or "what's next?"
- Daily planning and task selection
- Context switching after completing a task
- Sprint planning - select high-value work
- User wants "easy wins" or "quick tasks"

❌ **Don't use `get_next_task` when**:
- User already knows what task to work on
- Searching for specific task by name/tag (use `query_container(operation="search")`)
- Reviewing all tasks regardless of priority (use `query_container(operation="overview")`)

### Daily Planning Workflow

**User Says**: "What should I work on today?"

**AI Workflow**:
```
1. get_next_task --limit 5 (get top recommendations)
2. Present recommendations with reasoning:
   - Task 1 (HIGH, complexity 3): "Quick win - high impact, easy to complete"
   - Task 2 (HIGH, complexity 7): "Important but time-consuming"
   - Task 3 (MEDIUM, complexity 2): "Medium priority quick win"
3. Explain sorting logic (priority first, then complexity)
4. Let user choose based on available time and energy
```

**Why This Works**:
- Automatically excludes blocked tasks
- Balances impact (priority) with effort (complexity)
- Quick wins come first for momentum
- User makes informed choice with AI guidance

### Context Switching Workflow

**User Says**: "I just finished task X, what's next?"

**AI Workflow**:
```
1. request_transition(containerId=X, containerType="task", trigger="complete")
2. get_next_task --limit 3 (get fresh recommendations)
3. Present options with context:
   - "Here are your top 3 unblocked tasks"
   - Explain priority and complexity
4. Recommend based on momentum:
   - If user finished complex task → suggest easier task
   - If user finished easy task → can tackle harder task
```

**Benefits**:
- Seamless transition between tasks
- Maintains momentum
- Reduces decision fatigue
- Optimizes workflow

### Quick Wins Pattern

**User Says**: "Show me easy tasks I can knock out quickly"

**AI Workflow**:
```
1. get_next_task --limit 10
2. Filter for complexity ≤ 3
3. Present quick win opportunities
4. Explain value:
   - "These are high-priority tasks you can complete quickly"
   - "Great for short time windows or building momentum"
5. Suggest batching similar quick tasks
```

**Use Cases**:
- End of day - limited time
- Between meetings
- Building momentum after break
- Clearing small tasks before big work

### Scope-Specific Recommendations

**Project-Specific**:
```
User: "What should I work on for the mobile app?"

AI:
1. get_next_task --projectId "mobile-app-uuid" --limit 5
2. Scoped recommendations for specific project
3. Present with project context
```

**Feature-Specific**:
```
User: "What's next for the authentication feature?"

AI:
1. get_next_task --featureId "auth-uuid" --limit 3
2. Feature-scoped recommendations
3. Explain feature completion progress
```

### Understanding the Sorting Logic

**Priority → Complexity Ranking**:
```
The tool sorts by:
1. Priority (HIGH → MEDIUM → LOW)
2. Complexity (1 → 10) within same priority

Example:
- Task A: HIGH, complexity 3 → Rank 1 (quick win!)
- Task B: HIGH, complexity 8 → Rank 2 (important but harder)
- Task C: MEDIUM, complexity 2 → Rank 3 (medium priority quick win)
- Task D: LOW, complexity 1 → Rank 4 (low priority)
```

**AI Explanation**:
```
When presenting recommendations, explain why:
- "This is a high-priority, low-complexity task - a quick win"
- "This task is also high priority but more complex - tackle after quick wins"
- "This medium-priority task is easy - good for momentum building"
```

### AI Decision Tree

```
User asks about what to work on?
  ├─ "What should I work on?" → get_next_task
  ├─ "What's next?" → get_next_task
  ├─ "Show me easy tasks" → get_next_task --limit 10 (filter complexity ≤ 3)
  ├─ "What's most important?" → get_next_task --limit 1 (top priority)
  ├─ "Just finished task X"
  │  ├─ request_transition(containerId=X, containerType="task", trigger="complete")
  │  └─ get_next_task --limit 3
  └─ "What's ready to start?" → get_next_task (auto-excludes blocked)
```

### Response Interpretation

**Recommendation Response**:
```json
{
  "recommendations": [
    {
      "taskId": "uuid",
      "title": "Implement login",
      "status": "pending",
      "priority": "high",
      "complexity": 3
    }
  ],
  "totalCandidates": 15
}
```

**AI Insights**:
- "15 unblocked tasks available - showing top 1"
- "This is a high-priority task with moderate complexity"
- "Good starting point - can complete in a few hours"
- "14 other unblocked tasks available if you need more options"

### Common Patterns

**Daily Standup**:
```
User: "What should I focus on today?"
AI:
1. get_next_task --limit 5
2. Present top 5 with reasoning
3. Suggest starting with quick wins
```

**Sprint Planning**:
```
User: "Help me plan this sprint"
AI:
1. get_next_task --limit 20
2. Group by priority and complexity
3. Recommend balanced sprint:
   - Some quick wins for momentum
   - Some complex tasks for progress
   - Mix of high and medium priority
```

**End of Day**:
```
User: "I have 30 minutes left, what can I do?"
AI:
1. get_next_task --limit 10
2. Filter complexity ≤ 2
3. "Here are quick tasks you can finish before end of day"
```

**Parameters Reference**:
- `limit`: Number of recommendations (default: 1, max: 20)
- `projectId`: Scope to specific project
- `featureId`: Scope to specific feature
- `includeDetails`: Get full task info (summary, tags, etc.)

---

## Bulk Operations for Multi-Entity Updates

**Problem**: Updating multiple tasks individually wastes 90-95% tokens through repeated tool calls and responses.

**Solution**: Use `manage_container(operation="update")` with a `containers` array for 3+ task updates to achieve 70-95% token savings.

### When to Use Array Updates

**Priority Adjustments** (Updating urgency across tasks):
```
❌ INEFFICIENT (3 separate calls):
manage_container(operation="update", containerType="task", containers=[{"id": "task-1", "priority": "high"}])
manage_container(operation="update", containerType="task", containers=[{"id": "task-2", "priority": "high"}])
manage_container(operation="update", containerType="task", containers=[{"id": "task-3", "priority": "high"}])

✅ EFFICIENT:
manage_container(operation="update", containerType="task", containers=[
  {"id": "task-1", "priority": "high"},
  {"id": "task-2", "priority": "high"},
  {"id": "task-3", "priority": "high"}
])
```

### Integration with Workflows

**Task Completion Pattern**:
```
After completing feature implementation:
1. query_container(operation="search", containerType="task", featureId=[id], status="in-progress")
2. request_transition(transitions=[{containerId: "task-1", containerType: "task", trigger: "complete"}, ...])
3. request_transition(containerId=[id], containerType="feature", trigger="complete")
```

**Priority Triage Pattern**:
```
After discovering blocker:
1. query_dependencies(taskId=[blocked-task])
2. manage_container(operation="update", containerType="task", containers=[{"id": "task-1", "priority": "high"}, ...])
3. manage_dependencies(operation="create", ...) as needed
```

**Sprint Planning Pattern**:
```
When starting sprint:
1. query_container(operation="search", containerType="task", status="pending", priority="high")
2. request_transition(transitions=[{containerId: "task-1", containerType: "task", trigger: "start"}, ...])
3. query_container(operation="overview") to track progress
```

### AI Agent Guidelines

1. **Use array-based updates for 3+ task updates** - Single operation vs multiple calls
2. **Combine with search** - Find tasks then update with containers array in one call
3. **Minimal field updates** - Only send id + changed fields per task
4. **Leverage partial updates** - Each task can update different fields

### Other Bulk Operations

**Section Management** (via `manage_sections`):
- `bulkCreate` - Creating 2+ sections at once
- `bulkUpdate` - Updating multiple sections simultaneously
- `bulkDelete` - Removing multiple sections at once

**Performance Benefit**: Single database transaction, reduced network overhead, 70-95% token savings

---

## Troubleshooting

### AI Not Using Patterns Autonomously

**Symptoms**: AI asks for explicit instructions instead of recognizing patterns

**Solutions**:
1. Verify AI has been initialized:
   ```
   Invoke initialize_task_orchestrator to ensure guidelines are loaded
   ```

2. Check resource availability:
   - Confirm MCP server is properly configured
   - Verify resources are registered in server

3. Provide explicit examples:
   - "Use the feature creation pattern for this"
   - "Follow the bug triage workflow"

### Templates Not Being Discovered

**Symptoms**: AI creates tasks/features without templates

**Solutions**:
1. Verify templates exist:
   ```
   query_templates(isEnabled=true)
   ```

2. Check AI is querying templates:
   - AI should run `query_templates` before creating tasks/features
   - If not, remind: "Check for applicable templates first"

3. Validate template targeting:
   - Ensure templates have correct `targetEntityType`
   - TASK templates for tasks, FEATURE templates for features

### Workflow Not Following Best Practices

**Symptoms**: Missing steps, incomplete documentation

**Solutions**:
1. Use explicit workflow mode:
   ```
   "Walk me through this using the [workflow_name] workflow"
   ```

2. Review guideline resources:
   - AI should re-fetch resources if behavior deviates
   - Update custom patterns if needed

3. Validate task completeness:
   - Check tasks have summaries and tags
   - Ensure templates were applied
   - Verify dependencies are created

### Initialization Not Persisting

**Symptoms**: AI forgets patterns between sessions

**Solutions**:
1. **For Claude Code**: Automatic re-initialization on reconnect
2. **For Cursor**: Ensure `.cursorrules` includes pattern references
3. **For Custom Agents**: Verify memory persistence mechanism
4. Re-invoke initialization prompt at session start

---

## Additional Resources

- [Templates Documentation](templates.md) - Template system guide
- [Quick Start Guide](quick-start.md) - Getting started with Task Orchestrator
- [API Reference](api-reference.md) - Complete tool documentation
- [Status Progression Guide](status-progression.md) - Status workflow guide with examples

---

**Need Help?** See the [Troubleshooting Guide](troubleshooting.md) or review the [Quick Start](quick-start.md) for common patterns.
