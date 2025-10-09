---
layout: default
title: Workflow Prompts
---

# Workflow Prompts

Workflow prompts provide structured, step-by-step guidance for complex project management scenarios. They complement AI's autonomous pattern application by offering explicit workflows when needed.

## Table of Contents

- [Overview](#overview)
- [Dual Workflow Model](#dual-workflow-model)
- [Available Workflow Prompts](#available-workflow-prompts)
- [When to Use Workflow Prompts](#when-to-use-workflow-prompts)
- [Usage Patterns](#usage-patterns)
- [Integration with Templates](#integration-with-templates)

---

## Overview

Workflow prompts are AI-invokable guides that provide comprehensive instructions for complex scenarios. Unlike autonomous pattern recognition (where Claude understands your intent naturally), workflow prompts offer explicit, step-by-step processes.

### What They Provide

- **Structured Processes**: Step-by-step instructions for complex workflows
- **Quality Validation**: Built-in checkpoints and validation steps
- **Template Integration**: Automatic template selection and application
- **Best Practices**: Proven patterns for common scenarios

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

### `create_feature_workflow`

**Purpose**: Create comprehensive features with templates, tasks, and proper organization

**When to Use**:
- Creating a major new functional area (3+ related tasks)
- Need structured approach to feature planning
- Want to ensure comprehensive documentation from the start
- Learning feature creation best practices

**What It Covers**:
1. Understanding current project state
2. Template discovery and selection
3. Feature creation with metadata
4. Associated task creation with git workflow detection
5. Dependency establishment
6. Validation and review

**Key Decisions It Helps With**:
- Which templates to apply for comprehensive coverage
- How to break down feature into tasks
- What priority and complexity to assign
- How to establish task dependencies

**Autonomous Alternative**: Simply ask "Create a feature for user authentication" and Claude will apply feature creation patterns automatically

---

### `task_breakdown_workflow`

**Purpose**: Break down complex tasks into manageable, focused subtasks

**When to Use**:
- Task complexity rating is 7 or higher
- Task spans multiple technical areas or skill sets
- Need clear implementation phases
- Want to enable parallel work by team members

**What It Covers**:
1. Analyzing the complex task
2. Identifying natural boundaries (component, phase, skill set)
3. Creating feature container (if beneficial for 4+ subtasks)
4. Creating focused subtasks with proper templates
5. Establishing dependencies and sequencing
6. Updating original task to coordination role

**Key Decisions It Helps With**:
- When to create a feature vs. just subtasks
- How to determine natural breakdown boundaries
- What complexity target for subtasks (3-6 recommended)
- How to sequence implementation dependencies

**Autonomous Alternative**: Ask "This task is too complex, help me break it down" and Claude will apply breakdown patterns automatically

---

### `bug_triage_workflow`

**Purpose**: Systematic bug assessment, investigation, and resolution planning

**When to Use**:
- Critical or high-severity bugs requiring systematic approach
- Complex bugs needing thorough investigation
- Want to ensure proper documentation for bug resolution
- Learning bug triage best practices

**What It Covers**:
1. Initial bug assessment and current bug load review
2. Bug investigation task creation with templates
3. Detailed problem and technical investigation
4. Impact assessment and priority determination
5. Resolution approach determination (simple vs. complex)
6. Implementation workflow and git integration
7. Resolution tracking and validation

**Key Decisions It Helps With**:
- Severity and priority assessment
- Simple fix vs. complex breakdown decision
- What investigation steps to take
- How to document root cause and resolution

**Autonomous Alternative**: Report "I found a bug where X isn't working" and Claude will create bug task with appropriate templates automatically

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

### `implement_feature_workflow`

**Purpose**: Smart feature implementation with automatic git detection and workflow integration

**When to Use**:
- Ready to start implementing a specific feature or task
- Want automatic git workflow integration
- Need guidance on template application
- Learning implementation best practices

**What It Covers**:
1. Current state check and git detection (automatic)
2. Implementation target selection (what to work on)
3. Smart template application based on context and git detection
4. Implementation execution with template guidance
5. Completion validation before marking done

**Key Decisions It Helps With**:
- Which task to work on next
- What templates to apply (automatic suggestions)
- Whether to use git workflows (auto-detected)
- When task is truly complete

**Special Features**:
- **Git Detection**: Automatically detects .git directory and suggests git workflow templates
- **GitHub Integration**: Asks about PR workflows if git detected
- **Template Stacking**: Suggests combining multiple templates for comprehensive guidance

**Autonomous Alternative**: Ask "What should I work on next?" or "I'll start implementing the login feature" and Claude will guide implementation automatically

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
me to use the create_feature_workflow for comprehensive step-by-step guidance?"
```

**Best For**: Most scenarios - let Claude assess and suggest escalation

---

### Pattern 4: Sequential Workflow Application

Combine workflows for comprehensive coverage:

```
User: "Use the project setup workflow to create a new API project"
[Project created]

User: "Now use the create feature workflow for the authentication feature"
[Feature created with tasks]

User: "Apply the implement feature workflow to start working on the first task"
[Implementation begins with proper templates]
```

**Best For**: Large projects, systematic development, learning the full workflow

---

## Integration with Templates

### How Workflows Use Templates

All workflow prompts integrate with the template system:

1. **Automatic Discovery**: Workflows use `list_templates` to find applicable templates
2. **Smart Selection**: Workflows suggest templates based on work type and context
3. **Multiple Templates**: Workflows often apply 2-3 templates for comprehensive coverage
4. **Git Detection**: Workflows automatically suggest git templates when .git directory detected

### Template Categories by Workflow

**Feature Creation Workflow** typically uses:
- Context & Background (business context)
- Requirements Specification (detailed requirements)
- Technical Approach (architecture planning)

**Task Breakdown Workflow** typically uses:
- Task Implementation Workflow (implementation guidance)
- Local Git Branching Workflow (if git detected)
- Technical Approach (for complex subtasks)

**Bug Triage Workflow** typically uses:
- Bug Investigation Workflow (systematic investigation)
- Local Git Branching Workflow (if git detected)
- Definition of Done (completion criteria)

**Project Setup Workflow** typically uses:
- Multiple templates for features and foundation tasks
- Custom templates created for project-specific needs

**Implement Feature Workflow** uses:
- Task Implementation Workflow (always)
- Local Git Branching Workflow (if git detected)
- GitHub PR Workflow (if user confirms they use PRs)
- Technical Approach (for complex tasks)

> **See**: [Templates Guide](templates) for complete template documentation and [AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven) for discovery patterns

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
