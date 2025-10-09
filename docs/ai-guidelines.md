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
- `bug_triage_workflow` - Systematic bug investigation
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

#### Other Workflow Integration

All workflows can benefit from memory:

- **create_feature_workflow** - Default templates from memory
- **bug_triage_workflow** - Custom bug investigation steps
- **project_setup_workflow** - Team-specific project structure

**Pattern**:
```
1. Check memory for workflow-specific configuration
2. Apply project defaults if found
3. Fall back to global defaults
4. Use built-in defaults if no memory found
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
