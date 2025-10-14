---
layout: default
title: AI Guidelines and Initialization
---

# AI Guidelines and Initialization System

The MCP Task Orchestrator includes a comprehensive **AI Guidelines and Initialization System** that helps AI agents effectively use task orchestration tools through natural language pattern recognition and autonomous workflow execution.

## Table of Contents

- [Overview](#overview)
- [Three-Layer Guidance Architecture](#three-layer-guidance-architecture)
  - [Layer 1: MCP Resources (Internalized Knowledge)](#layer-1-mcp-resources-internalized-knowledge)
  - [Layer 2: Workflow Prompts (Explicit Guidance)](#layer-2-workflow-prompts-explicit-guidance)
  - [Layer 3: Dynamic Templates (Database-Driven)](#layer-3-dynamic-templates-database-driven)
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

## Three-Layer Guidance Architecture

The system uses three complementary layers to provide comprehensive guidance:

### Layer 1: MCP Resources (Internalized Knowledge)

**Purpose**: Provide AI agents with fundamental knowledge that gets internalized into their working memory.

**How It Works**: AI agents fetch MCP resources and store the content in their memory systems. This knowledge becomes part of their understanding and influences how they interpret user requests.

**Available Resources**:

| Resource URI | Name | Purpose |
|--------------|------|---------|
| `task-orchestrator://guidelines/usage-overview` | Usage Overview | High-level introduction and core concepts |
| `task-orchestrator://guidelines/template-strategy` | Template Strategy | Dynamic template discovery and application |
| `task-orchestrator://guidelines/task-management` | Task Management Patterns | 6 executable workflow patterns for common scenarios |
| `task-orchestrator://guidelines/workflow-integration` | Workflow Integration | How the three layers work together |

**Characteristics**:
- Always available through MCP resource protocol
- Markdown-formatted for easy AI consumption
- Optimized for AI memory systems (concise but comprehensive)
- No user action required - AI fetches automatically

### Layer 2: Workflow Prompts (Explicit Guidance)

**Purpose**: Provide detailed step-by-step workflows when users explicitly need guidance or when AI determines structured guidance would help.

**How It Works**: User or AI invokes a workflow prompt by name, receiving comprehensive instructions for completing a specific scenario.

**Available Prompts**:
- `initialize_task_orchestrator` - AI initialization workflow
- `create_feature_workflow` - Feature creation with templates
- `task_breakdown_workflow` - Breaking complex work into tasks
- `project_setup_workflow` - New project initialization
- `implementation_workflow` - Smart implementation workflow for tasks, features, and bugs

**Characteristics**:
- User or AI invokable
- Step-by-step instructions
- Includes tool usage examples
- Quality validation checklists
- See [Workflow Prompts documentation](workflow-prompts.md) for details

### Layer 3: Dynamic Templates (Database-Driven)

**Purpose**: Provide reusable documentation structures discovered and applied dynamically by AI.

**How It Works**: AI uses `list_templates` to discover available templates, then applies them during task/feature creation for consistent documentation.

**Template Categories**:
- **AI Workflow Instructions**: Git workflows, implementation workflows
- **Documentation Properties**: Technical approach, requirements, context
- **Process & Quality**: Testing strategy, definition of done

**Characteristics**:
- Database-stored, not hardcoded
- Discoverable via `list_templates` tool
- Applied via `templateIds` parameter
- Composable (multiple templates can be combined)
- See [Templates documentation](templates.md) for details

---

## Dual Workflow Model

The system supports two complementary modes of operation:

### Mode 1: Autonomous Pattern Application

**When AI uses this mode**: AI recognizes user intent and applies appropriate patterns directly.

**Example Scenarios**:

**User Request**: "Help me plan this authentication feature"

**AI Response** (autonomously):
1. Runs `get_overview` to understand current state
2. Runs `list_templates --targetEntityType FEATURE` to find templates
3. Selects appropriate templates (Context & Background, Requirements Specification)
4. Runs `create_feature` with selected templates
5. Confirms creation and next steps

**User Request**: "I need to break down this complex API implementation task"

**AI Response** (autonomously):
1. Runs `get_task` to understand the task
2. Analyzes complexity and identifies logical breakdown
3. Creates subtasks using `create_task` with appropriate templates
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
AI: [Invokes project_setup_workflow prompt]
```

**Benefits**:
- Guaranteed comprehensive coverage
- Step-by-step validation
- Explicit quality gates
- Useful for learning/training scenarios

**Integration**: Both modes complement each other. AI can:
- Start autonomous but escalate to explicit workflow if complexity warrants
- Use workflow prompts as references while working autonomously
- Suggest workflow invocation when user seems uncertain

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
- Integrate all three layers

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

**When to Reference**: Recognizing user intent, applying appropriate workflows

> **Recommended Workflow**: PRD-Driven Development provides the best results for complex projects. See [Quick Start - PRD Workflow](quick-start#prd-driven-development-workflow) for detailed guidance.

### Workflow Integration
**URI**: `task-orchestrator://guidelines/workflow-integration`

**Content**:
- How three layers work together
- When to use autonomous vs. explicit mode
- All workflow prompt descriptions
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
1. Run get_overview to understand current project state
2. Run list_templates --targetEntityType FEATURE --isEnabled true
3. Analyze templates and select appropriate ones:
   - Context & Background (business justification)
   - Requirements Specification (detailed requirements)
   - Technical Approach (if complex/architectural)
4. Run create_feature with templateIds parameter
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

[AI executes: get_overview → list_templates → create_feature]

AI Confirms: "Created User Authentication feature with:
- Context & Background section for business justification
- Requirements Specification for detailed requirements
- Ready to add tasks when you need to start implementation"
```

</details>

This pattern is just one of six available in the task-management resource. AI uses these patterns to recognize user intent and execute workflows autonomously without requiring explicit workflow invocation.

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

AI will discover these templates via `list_templates` and suggest them appropriately.

---

## Memory-Based Workflow Customization for AI Agents

The `implementation_workflow` and other workflows support memory-based customization. AI agents should check memory for configuration before applying defaults, enabling teams to customize workflows without code changes.

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
1. Retrieve task details with get_task
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

### Integration with Workflows

#### implementation_workflow Integration

The `implementation_workflow` checks memory at these points:

1. **Start** - Load all configuration from memory
2. **Git Detection** - Check if .git exists, load git preferences
3. **Branch Creation** - Expand variables using configuration
4. **PR Decision** - Use use_pull_requests preference
5. **Custom Steps** - Apply workflow overrides if configured

#### Feature Creation Workflow Memory Integration

**create_feature_workflow** can use memory for:

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

**project_setup_workflow** can use memory for:

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

**For bugs** (task-type-bug), `implementation_workflow` provides specialized handling:

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

- **[Workflow Prompts - Memory Customization](workflow-prompts#memory-based-workflow-customization)** - User guide for customization
- **[implementation_workflow](workflow-prompts#implementation_workflow)** - Workflow that uses memory configuration
- **[Templates Guide](templates)** - Understanding template validation requirements

---

## Best Practices

### For AI Agents

1. **Always start sessions with get_overview**
   - Understand current project state
   - Identify in-progress work
   - Plan new work in context

2. **Discover templates dynamically**
   - Use `list_templates` before creating tasks/features
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
   - Use get_overview to track progress

6. **Handle bugs with regression testing**
   - Detect bugs from task-type-bug or task-type-hotfix tags
   - Offer Bug Investigation template if not applied
   - Verify root cause documented before implementation
   - **MANDATORY**: Create regression tests before marking complete
   - Enforce test documentation (BUG/ROOT CAUSE/FIX comments)
   - Cannot complete bug fix without comprehensive tests

7. **Use implementation_workflow for all work types**
   - Tasks, features, AND bugs use same workflow
   - Workflow automatically adapts based on work type detection
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

### Selective Section Loading

**Problem**: Loading all section content consumes 5,000-15,000 tokens when only metadata or specific sections are needed.

**Solution**: Use the two-step workflow with `get_sections` for 85-99% token savings.

### When to Use Selective Loading

**Validation Workflows** (Browse before validating):
```
1. get_sections --entityType TASK --entityId [id] --includeContent false
2. Review section structure (titles, formats, ordinals)
3. Determine which sections need validation
4. get_sections --entityType TASK --entityId [id] --sectionIds [needed-ids]
5. Validate only the critical sections
```

**Finding Specific Content** (Browse then fetch):
```
1. get_sections --entityType TASK --entityId [id] --includeContent false
2. Identify section by title (e.g., "Technical Approach")
3. get_sections --entityType TASK --entityId [id] --sectionIds [approach-section-id]
4. Work with specific content
```

**Understanding Structure** (Metadata only):
```
1. get_sections --entityType TASK --entityId [id] --includeContent false
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
Before: get_sections (loads all 5-10 sections fully)
After:
1. get_sections --includeContent false (browse structure)
2. Identify required sections from template
3. get_sections --sectionIds [ids] (fetch only what's needed)
```

**Bug Investigation**:
```
Before: Load all task sections to find reproduction steps
After:
1. get_sections --includeContent false
2. Find "Reproduction Steps" section by title
3. get_sections --sectionIds [repro-section-id]
```

### AI Agent Guidelines

1. **Default to selective loading** when validating completion
2. **Browse structure first** when looking for specific content
3. **Use metadata** when structure is enough to answer the question
4. **Fetch all content** only when comprehensive review is needed
5. **Combine with sectionIds** for maximum efficiency

---

## Simple Status Updates

### Use `set_status` for Status-Only Changes

**Problem**: Using `update_task`, `update_feature`, or `update_project` for simple status updates wastes tokens with unnecessary parameter fields.

**Solution**: Use `set_status` for all status-only updates - simpler, more efficient, and works universally across all entity types.

### When to Use `set_status`

✅ **Use `set_status` when**:
- Only changing status (no other fields)
- User says "mark as completed", "set to in-progress", etc.
- Workflow transitions where only status changes
- Quick status updates during work

❌ **Don't use `set_status` when**:
- Updating multiple fields simultaneously (status + priority + complexity)
- Changing tags or associations
- Updating 3+ entities (use `bulk_update_tasks` instead)

### Efficiency Comparison

**Status-Only Update**:
```
❌ INEFFICIENT: update_task with many unused parameters
{
  "id": "task-uuid",
  "status": "completed",
  "title": "",    // Unnecessary - not changing
  "summary": "",  // Unnecessary - not changing
  ...
}

✅ EFFICIENT: set_status with only what's needed
{
  "id": "task-uuid",
  "status": "completed"
}

Token Savings: ~60% (2 params vs 10+ params)
```

### Auto-Detection Benefits

`set_status` automatically detects whether the ID is a task, feature, or project:

```
AI doesn't need to know entity type:
  set_status --id [any-entity-id] --status completed

Works for:
  - Tasks (pending, in-progress, completed, cancelled, deferred)
  - Features (planning, in-development, completed, archived)
  - Projects (planning, in-development, completed, archived)
```

### Smart Task Features

For tasks, `set_status` provides blocking dependency warnings:

```json
Response when completing a blocking task:
{
  "entityType": "TASK",
  "status": "completed",
  "blockingTasksCount": 2,
  "warning": "This task blocks 2 other task(s)"
}
```

This helps AI agents understand workflow implications when marking tasks complete.

### Status Format Flexibility

`set_status` accepts multiple status formats (auto-normalized):
- `in-progress`, `in_progress`, `inprogress` → all accepted
- `in-development`, `in_development`, `indevelopment` → all accepted
- Case-insensitive: `COMPLETED`, `completed`, `Completed` → all work

### AI Decision Tree

```
User requests status update?
  ├─ Only status changing?
  │  ├─ Single entity? → Use set_status
  │  └─ 3+ entities? → Use bulk_update_tasks
  └─ Multiple fields changing? → Use update_task/update_feature/update_project
```

### Example AI Patterns

**Simple Status Update**:
```
User: "Mark task X as done"
AI: set_status --id X --status completed
```

**Multi-Field Update**:
```
User: "Update task X to high priority and mark as in-progress"
AI: update_task --id X --priority high --status in-progress
```

**Bulk Status Update**:
```
User: "Mark all these tasks as completed"
AI: bulk_update_tasks (if 3+ tasks)
```

---

## Tag Discovery and Search Patterns

### Use `list_tags` Before Tag-Based Searches

**Problem**: Searching by tags without knowing what tags exist leads to failed searches and poor user experience.

**Solution**: Use `list_tags` to discover available tags before performing tag-based searches.

### When to Use `list_tags`

✅ **Use `list_tags` when**:
- User asks about tags ("what tags are we using?")
- Before searching by tag (discover available options)
- User mentions a concept but doesn't specify exact tag
- Tag cleanup or standardization tasks
- Understanding project categorization

❌ **Don't use `list_tags` when**:
- User provides exact tag to search for
- Tag is clearly standard (implementation, bug, feature, etc.)
- Already used list_tags in recent conversation

### Tag Discovery Workflow

**User Says**: "Show me all bug-related tasks"

**AI Workflow**:
```
1. list_tags (discover what tags exist)
2. Analyze results for bug-related tags:
   - "bug" (15 tasks)
   - "bugfix" (8 tasks)
   - "debugging" (3 tasks)
3. search_tasks --tag "bug" (use most common tag)
4. Present results to user
```

**Why This Works**:
- Confirms tags actually exist before searching
- Uses most common tag variation
- Finds related tags user might not know about
- Provides better results than guessing

### Tag Analytics and Cleanup

**User Says**: "What are our most common tags?"

**AI Response**:
```
list_tags --sortBy count --sortDirection desc

Shows:
- "implementation" (45 uses)
- "bug" (32 uses)
- "feature" (28 uses)
- etc.
```

**User Says**: "Are we using consistent tags?"

**AI Workflow**:
```
1. list_tags --sortBy name (alphabetical for review)
2. Identify variations:
   - "bug", "bugs", "bugfix" (should be standardized)
   - "test", "testing", "tests" (should be standardized)
3. Suggest tag standardization with rename_tag (Phase 2)
```

### Entity Type Filtering

**Use Case**: Understanding what tags are used for specific entity types

```
Task tags only:
list_tags --entityTypes ["TASK"]

Task and Feature tags:
list_tags --entityTypes ["TASK", "FEATURE"]

All entity types (default):
list_tags
```

### AI Decision Tree

```
User mentions searching by topic/category?
  ├─ Exact tag provided by user? → search_tasks with that tag
  ├─ Vague topic mentioned?
  │  ├─ Run list_tags to discover relevant tags
  │  └─ Search with most appropriate tag found
  └─ User asks about tags? → list_tags (possibly sorted/filtered)
```

### Response Interpretation

**Tag Usage Counts Help Prioritize**:
```json
{
  "tag": "urgent",
  "totalCount": 15,
  "byEntityType": {
    "TASK": 12,
    "FEATURE": 3
  }
}
```

**AI Insights**:
- Most "urgent" tags on tasks (12) vs features (3)
- Could suggest focusing on urgent tasks
- Can explain distribution to user

### Common Patterns

**Tag-Based Search**:
```
User: "Show authentication tasks"
AI:
1. list_tags
2. Find "authentication", "auth", "oauth" tags
3. search_tasks --tag "authentication"
```

**Tag Review**:
```
User: "What tags do we have?"
AI: list_tags --sortBy name --sortDirection asc
(Alphabetical list for easy review)
```

**Popular Tags**:
```
User: "What are our main focus areas?"
AI: list_tags --sortBy count --sortDirection desc
(Shows most used tags first = main focus areas)
```

---

## Bulk Operations for Multi-Entity Updates

**Problem**: Updating multiple tasks individually wastes 90-95% tokens through repeated tool calls and responses.

**Solution**: Use `bulk_update_tasks` for 3+ task updates to achieve 70-95% token savings.

### When to Use Bulk Updates

**Feature Completion** (Marking multiple tasks as done):
```
❌ INEFFICIENT: 10 individual update_task calls
✅ EFFICIENT: Single bulk_update_tasks call

Token Savings: 95% (11,850 characters saved)
```

**Priority Adjustments** (Updating urgency across tasks):
```
❌ INEFFICIENT:
update_task({"id": "task-1", "priority": "high"})
update_task({"id": "task-2", "priority": "high"})
update_task({"id": "task-3", "priority": "high"})

✅ EFFICIENT:
bulk_update_tasks({
  "tasks": [
    {"id": "task-1", "priority": "high"},
    {"id": "task-2", "priority": "high"},
    {"id": "task-3", "priority": "high"}
  ]
})
```

**Status Progression** (Moving tasks through workflow):
```
bulk_update_tasks({
  "tasks": [
    {"id": "task-1", "status": "in-progress"},
    {"id": "task-2", "status": "in-progress"},
    {"id": "task-3", "status": "completed"},
    {"id": "task-4", "status": "completed"}
  ]
})
```

### Token Savings Examples

**Scenario**: Mark 10 tasks as completed after feature implementation

**Individual Calls** (INEFFICIENT):
- 10 tool calls × ~250 chars each = ~2,500 characters
- 10 responses × ~1,000 chars each = ~10,000 characters
- **Total: ~12,500 characters**

**Bulk Operation** (EFFICIENT):
- Single tool call with 10 updates = ~350 characters
- Single response with minimal fields = ~300 characters
- **Total: ~650 characters**
- **Token Savings: 95% (11,850 characters saved!)**

### Integration with Workflows

**Task Completion Pattern**:
```
After completing feature implementation:
1. search_tasks --featureId [id] --status in-progress
2. bulk_update_tasks with all task IDs → status: completed
3. update_feature --id [id] --status completed
```

**Priority Triage Pattern**:
```
After discovering blocker:
1. get_task_dependencies --taskId [blocked-task]
2. bulk_update_tasks with dependent task IDs → priority: high
3. create_dependency relationships as needed
```

**Sprint Planning Pattern**:
```
When starting sprint:
1. search_tasks --status pending --priority high
2. bulk_update_tasks with selected task IDs → status: in-progress
3. Track progress with get_overview
```

### AI Agent Guidelines

1. **Use bulk operations for 3+ task updates** - Single operation vs multiple calls
2. **Combine with search_tasks** - Find tasks then bulk update in one call
3. **Minimal field updates** - Only send id + changed fields per task
4. **Leverage partial updates** - Each task can update different fields
5. **Atomic operations** - All succeed or detailed failure info provided

### Other Bulk Operations

**Section Management**:
- `bulk_create_sections` - Creating 2+ sections (prefer over multiple `add_section`)
- `bulk_update_sections` - Updating multiple sections simultaneously
- `bulk_delete_sections` - Removing multiple sections at once

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
   list_templates --isEnabled true
   ```

2. Check AI is querying templates:
   - AI should run `list_templates` before creating tasks/features
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

- [Workflow Prompts Documentation](workflow-prompts.md) - Detailed workflow prompt references
- [Templates Documentation](templates.md) - Template system guide
- [Quick Start Guide](quick-start.md) - Getting started with Task Orchestrator
- [API Reference](api-reference.md) - Complete tool documentation

---

**Need Help?** See the [Troubleshooting Guide](troubleshooting.md) or review the [Quick Start](quick-start.md) for common patterns.
