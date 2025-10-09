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

### `implementation_workflow`

**Purpose**: Smart implementation workflow for tasks, features, and bugs with automatic git detection and workflow integration

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

## Memory-Based Workflow Customization

The `implementation_workflow` supports customization through AI memory configuration, allowing teams to adapt workflows to their specific processes without modifying code.

### Overview

**Memory-based customization** allows you to:
- Define pull request preferences (always/never/ask)
- Customize branch naming conventions with variables
- Override procedural workflow steps while keeping validation
- Configure team-specific processes
- Store configuration globally (user-wide) or per-project (team-wide)

**Key Benefits**:
- ✅ **Zero-config default**: Works out of the box with sensible defaults
- ✅ **Progressive enhancement**: Start minimal, add complexity as needed
- ✅ **Version-controlled**: Project configuration lives in your repo
- ✅ **Natural language**: Update via conversation with AI
- ✅ **AI-agnostic**: Works with any AI memory mechanism

---

### Minimal Configuration

The simplest customization is just your PR preference:

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"
```

That's it! The workflow will now always create pull requests without asking.

**Options**:
- `"always"` - Always create PRs (skip asking)
- `"never"` - Never create PRs, merge directly to main
- `"ask"` - Ask each time (default if not configured)

---

### Memory Configuration Schema

Complete configuration schema with all available options:

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always" | "never" | "ask"

## Branch Naming Conventions (optional - defaults provided)
branch_naming_bug: "bugfix/{task-id-short}-{description}"
branch_naming_feature: "feature/{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/{task-id-short}-{description}"
branch_naming_enhancement: "enhancement/{task-id-short}-{description}"

## Commit Message Customization (optional)
commit_message_prefix: "[{type}/{task-id-short}]"

## Custom Workflow Steps (optional - leave empty to use templates)
### Bug Fix Workflow Override
# [Custom steps override Bug Investigation template procedural guidance]
# Template validation requirements still apply

### Feature Implementation Workflow Override
# [Custom steps override Task Implementation template procedural guidance]
# Template validation requirements still apply
```

---

### Branch Naming Variables

Use these standardized variables in branch naming patterns:

| Variable | Description | Example |
|----------|-------------|---------|
| `{task-id}` | Full task UUID | `70490b4d-f412-4c20-93f1-cacf038a2ee8` |
| `{task-id-short}` | First 8 characters of UUID | `70490b4d` |
| `{description}` | Sanitized task title | `fix-authentication-bug` |
| `{feature-id}` | Feature UUID (if applicable) | `a3d0ab76-d93d-455c-ba54-459476633a3f` |
| `{feature-id-short}` | First 8 chars of feature UUID | `a3d0ab76` |
| `{priority}` | Task priority | `high`, `medium`, `low` |
| `{complexity}` | Task complexity | `1` through `10` |
| `{type}` | Work type from tags | `bug`, `feature`, `enhancement`, `hotfix` |

**Sanitization**: The `{description}` variable is automatically sanitized (lowercase, hyphenated, special chars removed, max 50 chars).

---

### Template Validation vs Procedural Override

**What's Always Used** (never overridden):
- ✅ Validation requirements from templates
- ✅ Acceptance criteria and definition of done
- ✅ Testing requirements and quality gates
- ✅ Technical context and background information

**What Can Be Overridden** (custom workflow steps replace):
- ⚠️ Step-by-step implementation instructions
- ⚠️ Procedural workflow guidance
- ⚠️ Tool invocation sequences

This ensures quality standards are maintained while allowing team-specific processes.

---

### Real-World Configuration Examples

#### Example 1: Startup Team (Minimal Setup)

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "never"
```

**Use Case**: Fast-moving startup, direct commits to main, rapid iteration.

---

#### Example 2: Jira Integration with Custom Branch Naming

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming (Jira-style)
branch_naming_bug: "bugfix/PROJ-{task-id-short}-{description}"
branch_naming_feature: "feature/PROJ-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/PROJ-{task-id-short}-{description}"

## Commit Messages
commit_message_prefix: "[PROJ-{task-id-short}]"
```

**Use Case**: Team using Jira with project prefix "PROJ", wants consistent ticket references.

**Result**:
- Branch: `feature/PROJ-70490b4d-oauth-authentication`
- Commit: `[PROJ-70490b4d] feat: add OAuth2 authentication`

---

#### Example 3: Enterprise Team with Staging Deployment

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming
branch_naming_bug: "bugfix/{priority}-{task-id-short}-{description}"
branch_naming_hotfix: "hotfix/{task-id-short}-{description}"

## Bug Fix Workflow Override
### Custom Bug Fix Process
1. Create branch from main
2. Implement fix with tests
3. Deploy to staging environment: `./deploy-staging.sh`
4. Run integration test suite: `npm run test:integration:staging`
5. Request QA approval in PR
6. After QA approval, merge to main
7. Deploy to production: `./deploy-production.sh`

Note: Template validation still requires:
- Bug Investigation template analysis completed
- Root cause documented
- Test coverage for bug fix
- Regression tests passing
```

**Use Case**: Enterprise team with required staging deployment and QA approval.

---

#### Example 4: Priority-Based Branch Naming

```markdown
# Task Orchestrator - Implementation Workflow Configuration

## Pull Request Preference
use_pull_requests: "always"

## Branch Naming (priority-based)
branch_naming_bug: "bug/{priority}-{complexity}-{description}"
branch_naming_feature: "feature/{feature-id-short}/{description}"
branch_naming_hotfix: "hotfix/{description}"
```

**Use Case**: Team that prioritizes by severity and groups features together.

**Result**:
- Bug: `bug/high-8-security-vulnerability`
- Feature: `feature/a3d0ab76/oauth-integration`
- Hotfix: `hotfix/critical-data-leak`

---

### Natural Language Customization

You can update configuration through natural conversation with your AI:

**Example Conversation**:
```
User: "Can you update our workflow to use Linear-style branch naming?"

AI: "I'll update your project configuration to use Linear conventions:
     {type}/{description}-{task-id-short}

     Should I save this to your project's memory?"

User: "Yes, and we always use pull requests"

AI: "Updated! I've saved:
     - Linear-style branch naming
     - use_pull_requests: always

     Your team's configuration is now in CLAUDE.md"
```

The AI handles:
- Schema generation
- Variable substitution
- Validation
- Storage in appropriate memory location

---

### Global vs Project-Specific Configuration

**Global (User-Wide)**:
- Your personal preferences across all projects
- Stored in AI's global memory
- Examples: PR preference, default branch naming

**Project-Specific (Team-Wide)**:
- Team conventions for specific project
- Stored in project repo (e.g., `CLAUDE.md`)
- Examples: Jira integration, staging deployment steps

**Priority**: Project-specific configuration overrides global preferences.

---

### Getting Started with Customization

1. **Start Simple**: Just configure `use_pull_requests` preference
2. **Add Branch Naming**: Customize patterns for your team's conventions
3. **Override Workflows**: Add custom steps only if needed
4. **Iterate**: Refine based on team feedback

**First-Time Setup** (via AI conversation):
```
User: "Set up workflow customization for our team"

AI: "I'll help you configure. A few questions:
     1. Do you always use pull requests? (always/never/ask)
     2. Do you use Jira or Linear for tickets?
     3. Any specific branch naming conventions?"

[AI creates configuration based on answers]
```

---

### Best Practices

**DO**:
- ✅ Start with minimal configuration (just PR preference)
- ✅ Use default branch naming patterns unless team has strong conventions
- ✅ Store team configuration in version-controlled project files
- ✅ Document custom workflow steps clearly
- ✅ Test configuration with simple task first

**DON'T**:
- ❌ Override template validation requirements
- ❌ Create overly complex branch naming patterns
- ❌ Duplicate template guidance in custom workflow steps
- ❌ Store sensitive information in configuration

---

### Related Documentation

- **[implementation_workflow](workflow-prompts#implementation_workflow)** - Workflow that uses this configuration
- **[AI Guidelines - Memory Patterns](ai-guidelines)** - How AI agents use memory
- **[Quick Start](quick-start)** - Getting started examples
- **[Templates Guide](templates)** - Understanding template validation

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
