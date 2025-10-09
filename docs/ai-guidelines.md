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
- `implement_feature_workflow` - Smart implementation workflow

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

**Content**: Six executable workflow patterns:

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

**When to Reference**: Recognizing user intent, applying appropriate workflows

### Workflow Integration
**URI**: `task-orchestrator://guidelines/workflow-integration`

**Content**:
- How three layers work together
- When to use autonomous vs. explicit mode
- All workflow prompt descriptions
- Custom workflow extension points

**When to Reference**: Understanding system architecture, advanced usage

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
