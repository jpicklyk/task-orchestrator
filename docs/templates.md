---
layout: default
title: Templates
---

# Template System

The MCP Task Orchestrator includes **9 built-in templates** organized into 3 strategic categories. Templates provide reusable patterns for structuring task and feature documentation, ensuring consistency and completeness across your project management workflows.

## Table of Contents

- [Overview](#overview)
- [AI-Driven Template Discovery](#ai-driven-template-discovery)
- [Template Categories](#template-categories)
- [Built-in Templates](#built-in-templates)
- [Using Templates](#using-templates)
- [Template Composition Patterns](#template-composition-patterns)
- [Custom Templates](#custom-templates)
- [Best Practices](#best-practices)

---

## Overview

### What Are Templates?

Templates are predefined documentation structures that can be applied to tasks, features, and projects to create consistent, comprehensive documentation. **Templates are universally available** - they work with any MCP-compatible AI client (Claude Desktop, Claude Code, Cursor, Windsurf, etc.) and function independently of orchestration setup.

Each template contains:

- **Section Definitions**: Structured content blocks with specific purposes
- **Usage Descriptions**: Guidance for AI assistants on how to use each section
- **Content Formats**: Support for Markdown, plain text, JSON, and code
- **Ordering**: Logical sequence for optimal information flow

### Benefits

- **Universal Availability**: Works with ALL MCP clients, not specific to Claude Code
- **Consistency**: Standardized documentation across all work items
- **Completeness**: Ensures all critical aspects are covered
- **AI-Optimized**: Designed for AI assistant interaction and understanding
- **Composable**: Can be applied individually or combined for comprehensive coverage
- **Discoverable**: AI agents dynamically discover and apply appropriate templates
- **Flexible Execution**: Works with both direct execution and sub-agent orchestration

### Templates Structure the WORK

**Templates define WHAT needs to be documented**, not WHO does it:

- **Requirements Specification**: Creates "Requirements" section → defines what functionality is needed
- **Technical Approach**: Creates "Technical Approach" section → defines architecture and strategy
- **Testing Strategy**: Creates "Testing Strategy" section → defines test requirements

Templates work with **TWO execution patterns**:

#### Pattern 1: Direct Execution (Universal - Any MCP Client)

You (the AI assistant) read the template sections and implement the work yourself:

```
1. Apply templates when creating task/feature
2. Templates create sections (Requirements, Technical Approach, Testing)
3. You read those sections for context
4. You implement the code, tests, documentation yourself
5. You update sections with results
```

#### Pattern 2: Sub-Agent Execution (Claude Code Only)

Specialist agents read template sections and implement the work:

```
1. Apply templates when creating task/feature
2. Templates create sections (Requirements, Technical Approach, Testing)
3. Orchestrator launches specialist (Backend Engineer, Test Engineer, etc.)
4. Specialist reads template sections for context
5. Specialist implements the work
6. Specialist updates sections with results
```

**Key Insight**: Templates create the same sections in both patterns. The difference is WHO reads them and implements the work - you directly, or a specialist agent.

### How Templates Work

Templates are **database-driven** and **dynamically discoverable** - AI agents use the `list_templates` tool to find appropriate templates based on work type, then apply them automatically or suggest them to users.

> **Learn More**: See [AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven) for how AI discovers and applies templates autonomously.

---

## AI-Driven Template Discovery

### How AI Discovers Templates

Claude and other AI agents don't have hardcoded template knowledge - they **discover templates dynamically** using the Task Orchestrator's template system:

1. **Query Templates**: AI uses `list_templates` to find available templates
2. **Filter by Context**: Filters by `targetEntityType` (TASK vs FEATURE) and `isEnabled`
3. **Analyze Options**: Reviews template descriptions and categories
4. **Select Appropriate**: Chooses templates that match the work type
5. **Apply or Suggest**: Either applies automatically or suggests to user

### Autonomous vs. Explicit Template Application

**Autonomous Application** (most common):
```
User: "Create a task to implement the login API"

Claude autonomously:
1. Runs list_templates --targetEntityType TASK --isEnabled true
2. Identifies appropriate templates (Task Implementation, Technical Approach)
3. Creates task with templateIds parameter
4. Confirms creation with applied templates
```

**Explicit Suggestion**:
```
User: "Create a feature for user authentication"

Claude: "I'll create the User Authentication feature. I recommend applying:
- Context & Background (business context)
- Requirements Specification (detailed requirements)
- Technical Approach (architecture planning)

Would you like me to apply these templates?"
```

> **See**: [AI Guidelines - Dual Workflow Model](ai-guidelines#dual-workflow-model) for understanding autonomous vs. explicit patterns.

### Template Selection Intelligence

AI selects templates based on:

- **Work Type**: Implementation tasks get workflow templates, planning gets documentation templates
- **Complexity**: Complex tasks get more comprehensive template combinations
- **Context**: Git detection triggers git workflow templates automatically
- **User Request**: Explicit template requests override autonomous selection

---

## Template Categories

The 9 built-in templates are organized into 3 strategic categories:

### AI Workflow Instructions

**Purpose**: Step-by-step process guidance with MCP tool integration

**Templates**:
- Local Git Branching Workflow
- GitHub PR Workflow
- Task Implementation Workflow
- Bug Investigation Workflow

**Target**: Primarily tasks, designed for process automation

**When AI Uses Them**: Implementation tasks, bug fixes, any work requiring structured workflow

---

### Documentation Properties

**Purpose**: Information capture and context preservation

**Templates**:
- Technical Approach
- Requirements Specification
- Context & Background

**Target**: Both tasks and features, with feature-level emphasis

**When AI Uses Them**: Feature creation, complex tasks, architectural decisions

---

### Process & Quality

**Purpose**: Quality assurance and completion standards

**Templates**:
- Testing Strategy
- Definition of Done

**Target**: Tasks and features requiring quality gates

**When AI Uses Them**: Critical functionality, production deployments, quality-focused work

---

## Built-in Templates

### Local Git Branching Workflow
- **Target**: Tasks
- **Sections**: Branch creation, development workflow, integration
- **Auto-Applied**: When git repository detected (.git directory exists)

### GitHub PR Workflow
- **Target**: Tasks
- **Sections**: PR preparation, creation process, review management
- **Suggested**: When git detected and user confirms they use PRs

### Task Implementation Workflow
- **Target**: Tasks
- **Sections**: Implementation planning, development execution, completion validation
- **Auto-Applied**: Most implementation tasks with complexity > 3

### Bug Investigation Workflow
- **Target**: Tasks
- **Sections**: Problem analysis, investigation steps, resolution planning
- **Auto-Applied**: Bug-related tasks (when user reports bugs)

### Technical Approach
- **Target**: Tasks and Features
- **Sections**: Architecture overview, implementation strategy, technology decisions
- **Auto-Applied**: Complex tasks (complexity > 6), architectural features

### Requirements Specification
- **Target**: Features
- **Sections**: Functional requirements, non-functional requirements, acceptance criteria
- **Auto-Applied**: Feature creation workflows

### Context & Background
- **Target**: Features
- **Sections**: Business context, user value, project background
- **Auto-Applied**: Feature planning and project setup

### Testing Strategy
- **Target**: Tasks and Features
- **Sections**: Testing approach, coverage requirements, quality gates
- **Suggested**: Quality-critical work, production deployments

### Definition of Done
- **Target**: Tasks and Features
- **Sections**: Completion criteria, acceptance guidelines, handoff requirements
- **Suggested**: Work requiring clear completion standards

> **Full Template Details**: Use `get_template --id <template-id> --includeSections true` to see complete section structure for any template.

---

## Using Templates

### Natural Language Application

The recommended way to use templates is through natural conversation with Claude:

**Example 1: Automatic Template Application**
```
User: "Create a task to implement the user login endpoint"

Claude: [Autonomously]
- Discovers templates via list_templates
- Selects: Task Implementation Workflow + Technical Approach
- Creates task with both templates applied
- Confirms: "Created task with Task Implementation and Technical Approach templates"
```

**Example 2: Git Workflow Auto-Detection**
```
User: "Create a task to fix the authentication bug"

Claude: [Autonomously]
- Detects .git directory in project
- Selects: Bug Investigation + Local Git Branching Workflow
- Creates task with git workflow
- Confirms: "Created bug task with git workflow (detected .git directory)"
```

**Example 3: Feature Creation with Templates**
```
User: "Create a feature for payment processing"

Claude: [Suggests templates]
"I'll create the Payment Processing feature. I recommend applying:
- Context & Background (business justification)
- Requirements Specification (detailed requirements)
- Technical Approach (integration architecture)

Proceeding with these templates..."
```

**Example 4: Adding Templates Later**
```
User: "Apply testing strategy template to the login task"

Claude: [Uses apply_template tool]
"Applied Testing Strategy template to login task. The task now has guidance for:
- Unit test requirements
- Integration testing approach
- Coverage targets"
```

### Template Discovery in Action

Ask Claude to show you available templates:

```
User: "What templates are available for tasks?"

Claude: [Uses list_templates]
"Available templates for tasks:

AI Workflow Instructions:
- Local Git Branching Workflow (git operations)
- GitHub PR Workflow (pull requests)
- Task Implementation Workflow (structured implementation)
- Bug Investigation Workflow (systematic debugging)

Documentation Properties:
- Technical Approach (architecture and strategy)

Process & Quality:
- Testing Strategy (comprehensive testing)
- Definition of Done (completion criteria)

Would you like me to apply any of these to a task?"
```

---

## Template Composition Patterns

### Decision Tree for Template Selection

```
What type of work are you doing?
│
├─ Implementation Task
│   ├─ Simple (complexity 1-4)
│   │   └─ Task Implementation Workflow
│   │       + Git Branching (if git detected)
│   │
│   └─ Complex (complexity 5+)
│       └─ Task Implementation + Technical Approach + Testing Strategy
│           + Git Branching (if git detected)
│
├─ Bug Fix
│   └─ Bug Investigation Workflow
│       + Git Branching (if git detected)
│       + Technical Approach (if complex)
│
├─ Feature Planning
│   └─ Context & Background
│       + Requirements Specification
│       + Technical Approach
│
└─ Production Deployment
    └─ Technical Approach
        + Testing Strategy
        + Definition of Done
```

### Recommended Combinations

**For New Features**:
```
Context & Background + Requirements Specification + Technical Approach
```
Provides complete coverage from business justification through architecture.

**For Complex Implementation**:
```
Task Implementation Workflow + Technical Approach + Testing Strategy + Git Branching
```
Comprehensive guidance for challenging technical work with quality gates.

**For Bug Fixes**:
```
Bug Investigation Workflow + Git Branching + Definition of Done
```
Systematic investigation with clear completion criteria.

**For Critical Production Work**:
```
Technical Approach + Testing Strategy + Definition of Done + GitHub PR Workflow
```
Maximum rigor with quality gates and review processes.

### Template Stacking Strategy

**Layer 1: Process** (always include one)
- Task Implementation Workflow OR Bug Investigation Workflow

**Layer 2: Documentation** (for complex work)
- Technical Approach (architecture)
- Requirements Specification (features)
- Context & Background (features)

**Layer 3: Quality** (for critical work)
- Testing Strategy (test coverage)
- Definition of Done (completion criteria)

**Layer 4: Git Workflows** (if git detected)
- Local Git Branching Workflow (always if git)
- GitHub PR Workflow (if using PRs)

---

## Custom Templates

### When to Create Custom Templates

Create custom templates for:

- **Organization-Specific Processes**: Unique workflows or documentation requirements
- **Domain-Specific Needs**: Industry regulations (HIPAA, GDPR, etc.)
- **Team Preferences**: Customized structures matching team practices
- **Compliance Requirements**: Regulatory or policy adherence

### Creating Custom Templates

**Step 1: Create Template**

Ask Claude:
```
"Create a custom template called 'Security Review' for tasks with sections for:
- Security assessment
- Vulnerability analysis
- Compliance checklist
- Remediation plan"
```

Claude will use `create_template` and `add_template_section` tools to build your template.

**Step 2: Test Template**

```
"Apply the Security Review template to a test task"
```

**Step 3: Refine and Deploy**

```
"Enable the Security Review template for team use"
```

### Custom Template Example

**Scenario**: Healthcare application requiring HIPAA compliance documentation

```
Template Name: "HIPAA Compliance Task"
Target: TASK
Sections:
1. PHI Data Assessment
   - What protected health information is involved?
   - Data classification and sensitivity

2. Security Controls
   - Encryption requirements
   - Access control implementation
   - Audit logging approach

3. Compliance Validation
   - HIPAA checklist completion
   - Privacy officer review requirement
   - Documentation evidence
```

**Creating it**:
```
User: "Create a HIPAA compliance template for tasks with sections for PHI assessment,
security controls, and compliance validation"

Claude: [Creates template with proper sections]
"Created HIPAA Compliance Task template with 3 sections.
Template ID: [uuid]
Available for immediate use with create_task."
```

### Custom Template Management

**Disable Template** (hide but don't delete):
```
"Disable the old security review template"
```

**Enable Template**:
```
"Enable the security review template again"
```

**Delete Template** (cannot delete built-in templates):
```
"Delete the old custom template"
```

### Sharing Custom Templates

Custom templates are stored in the database and automatically available to:
- All AI agents connected to the same Task Orchestrator instance
- All users of the same database
- Team members using shared database volume

---

## Best Practices

### For AI Agents

1. **Always query templates first**: Use `list_templates` before creating tasks/features
2. **Auto-apply git workflows**: Detect .git and apply git templates automatically
3. **Suggest multiple templates**: For comprehensive coverage, recommend 2-3 templates
4. **Respect user preferences**: If user specifies templates, use exactly those
5. **Explain template value**: Briefly explain why templates are being applied

### For Development Teams

1. **Use autonomous mode**: Let Claude discover and apply templates automatically
2. **Create domain templates**: Build custom templates for your specific domain
3. **Maintain consistency**: Use same template combinations for similar work types
4. **Review template content**: Ensure template guidance is actually being followed
5. **Evolve templates**: Update custom templates as processes improve

### Template Content Quality

**Do**:
- Complete all template sections with specific information
- Update template content as work progresses
- Use templates consistently for similar work
- Leverage templates for knowledge transfer

**Don't**:
- Leave template sections empty or with placeholder text
- Apply too many overlapping templates
- Ignore template guidance and structure
- Create templates without clear purpose

---

## Integration with Workflows

### How Templates and Workflows Complement Each Other

**Templates structure the WORK** (what needs to be documented):
- Create sections automatically when applied
- Define required documentation (Requirements, Technical Approach, Testing)
- Provide consistent structure across all tasks and features

**Workflow Prompts guide the PROCESS** (how to accomplish the work):
- Provide step-by-step procedural guidance
- Integrate MCP tools systematically
- Offer quality validation checkpoints

Templates work seamlessly with workflow prompts:

- **PRD-Driven Development** ⭐: Analyzes PRD content to systematically apply appropriate templates across all features and tasks based on requirements and technical complexity
- **create_feature_workflow**: Automatically suggests Context & Background + Requirements Specification templates
- **task_breakdown_workflow**: Applies Task Implementation Workflow template to subtasks
- **implementation_workflow**: Auto-detects git and applies appropriate git workflow templates for any work type (tasks, features, and bugs)

### Templates + Sub-Agents: Working Together

When using Claude Code with sub-agent orchestration:

1. **Templates create the sections** (Requirements, Technical Approach, Testing Strategy)
2. **Sub-agents read those sections** for context when implementing
3. **Templates provide consistent structure** that all specialists understand

**Example Flow**:
```
1. Create task with Technical Approach template
   → Creates "Technical Approach" section with architecture guidance

2. Launch Backend Engineer specialist
   → Reads "Technical Approach" section for context
   → Implements code following the architecture
   → Updates section with implementation notes

3. Launch Test Engineer specialist
   → Reads "Testing Strategy" section for test requirements
   → Reads "Technical Approach" for implementation context
   → Writes comprehensive tests
   → Updates section with test results
```

**Key Point**: Templates work IDENTICALLY whether you're implementing directly or using sub-agents. The sections provide structure and context in both scenarios.

> **Most Effective**: PRD-driven development provides optimal template selection by analyzing complete requirements. See [PRD Workflow Guide](quick-start#prd-driven-development-workflow) for how Claude intelligently applies templates during PRD breakdown.
>
> **See Also**: [Workflow Prompts](workflow-prompts) for complete workflow integration details and [AI Guidelines](ai-guidelines#layer-3-dynamic-templates-database-driven) for template strategy patterns.

---

## Additional Resources

- **[AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven)** - How AI discovers and applies templates
- **[Workflow Prompts](workflow-prompts)** - Integration with workflow automation
- **[Quick Start](quick-start)** - Getting started with templates
- **[API Reference](api-reference)** - Complete template tool documentation

---

**Questions?** Ask Claude "How do templates work?" or "What templates should I use for [your scenario]?" - Claude understands the template system and can guide you.
