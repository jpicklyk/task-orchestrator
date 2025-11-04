---
layout: default
title: Templates
---

# Template System

The MCP Task Orchestrator includes **9 built-in templates** organized into 3 strategic categories. Templates provide workflow instructions, decision frameworks, and quality gates that guide AI assistants through implementation, planning, and validation processes.

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

Templates are predefined workflow guidance, decision frameworks, and validation checklists that can be applied to tasks and features to guide implementation work. **Templates are universally available** - they work with any MCP-compatible AI client (Claude Desktop, Claude Code, Cursor, Windsurf, etc.) and function independently of orchestration setup.

Each template contains:

- **Section Definitions**: Structured content blocks with specific purposes
- **Usage Descriptions**: Guidance for AI assistants on how to use each section
- **Content Formats**: Support for Markdown, plain text, JSON, and code
- **Ordering**: Logical sequence for optimal information flow

### Benefits

- **Universal Availability**: Works with ALL MCP clients, not specific to Claude Code
- **Structured Guidance**: Standardized workflows and decision frameworks across all work
- **Completeness**: Ensures all critical planning and validation steps are covered
- **AI-Optimized**: Designed for AI assistant interaction and execution
- **Composable**: Can be applied individually or combined for comprehensive coverage
- **Discoverable**: AI agents dynamically discover and apply appropriate templates
- **Flexible Execution**: Works with both direct execution and sub-agent orchestration

### Templates Structure the WORK

**Templates define WHAT needs to be done and HOW to approach it**, not WHO does it:

- **Requirements Specification**: Creates "Requirements" section → defines what functionality is needed
- **Technical Approach**: Creates "Implementation Planning Checklist" section → guides planning with decision frameworks
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

Templates are **database-driven** and **dynamically discoverable** - AI agents use the `query_templates` tool to find appropriate templates based on work type, then apply them automatically or suggest them to users.

> **Learn More**: See [AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven) for how AI discovers and applies templates autonomously.

---

## Template Philosophy

### Multi-Layered Complementary Architecture

The Task Orchestrator uses a **three-layer architecture** where templates, skills, and subagents work together without redundancy:

**Layer 1: Templates (Universal - ANY MCP Client)**
- **What**: Database-driven workflow instructions
- **Purpose**: Provide actionable guidance for implementation
- **Scope**: Works with ALL MCP clients (Claude Desktop, Claude Code, Cursor, Windsurf, etc.)
- **Content**: Checklists, commands, decision frameworks, process steps
- **Example**: "Run `./gradlew test` before committing", "Create Flyway migration V{N}__description.sql"

**Layer 2: Skills (Claude Code Only)**
- **What**: Lightweight coordination workflows (2-5 tool calls)
- **Purpose**: Add domain expertise on top of templates
- **Scope**: Claude Code only (auto-activating from natural language)
- **Content**: Coordination patterns, integration examples, troubleshooting
- **Example**: Feature Management Skill coordinates "What's next?" by using templates + MCP tools

**Layer 3: Subagents (Claude Code Only - Complex Work)**
- **What**: Specialist agents for complex implementation
- **Purpose**: Deep reasoning + code implementation
- **Scope**: Claude Code only (launched via Task tool)
- **Content**: Backend Engineer, Frontend Developer, Database Engineer, etc.
- **Example**: Backend Engineer implements API endpoints using templates for guidance

**Key Principle**: No redundancy across layers. Templates provide WHAT workflow to follow (universal). Skills add HOW to coordinate (Claude Code). Subagents are WHO executes (Claude Code, complex work only).

### Section Tag Taxonomy

Templates create sections with **explicit tags** that enable token-efficient reading. Different roles read different section types:

#### Contextual Tags (Planning)
- **context** - Business context, user needs, dependencies, strategic alignment
- **requirements** - Functional and non-functional requirements, must-haves, constraints
- **acceptance-criteria** - Completion criteria, quality standards, definition of done

**Who reads**: Planning Specialist (during task breakdown from features)

#### Actionable Tags (Implementation)
- **workflow-instruction** - Step-by-step implementation processes
- **checklist** - Validation checklists, completion criteria
- **commands** - Bash commands to execute (build, test, deploy)
- **guidance** - Implementation patterns and best practices
- **process** - Workflow processes to follow

**Who reads**: Implementation Specialist (during code/test/doc implementation)

#### Reference Tags (As Needed)
- **reference** - Examples, patterns, reference material
- **technical-details** - Deep technical specifications

**Who reads**: Any role, only when specifically needed

### Token-Efficient Section Reading

Using tag filtering reduces token consumption by 45-60%:

**Without Tag Filtering** (Old Approach):
```javascript
// Read ALL sections from task
task = query_container(
  operation="get",
  containerType="task",
  id=taskId,
  includeSections=true
)
// Token cost: ~3,000-5,000 tokens
// Includes business context, requirements, AND workflows
```

**With Tag Filtering** (New Approach):
```javascript
// Planning Specialist: Read only context/requirements from feature
sections = query_sections(
  entityType="FEATURE",
  entityId=featureId,
  tags="context,requirements,acceptance-criteria",
  includeContent=true
)
// Token cost: ~2,000-3,000 tokens (vs ~7,000+)
// Savings: 60% reduction

// Implementation Specialist: Read only actionable content from task
sections = query_sections(
  entityType="TASK",
  entityId=taskId,
  tags="workflow-instruction,checklist,commands,guidance,process",
  includeContent=true
)
// Token cost: ~800-1,500 tokens (vs ~3,000-5,000)
// Savings: 50% reduction
```

### Template Content Guidelines

**Templates contain ACTIONABLE content, not placeholders:**

❌ **Bad** (Old v1.0 approach - generic placeholders):
```markdown
## Implementation Steps
1. [Component 1]: [Description]
2. [Component 2]: [Description]
3. [Library Name]: [Configuration]
```
**Problem**: 500-1,500 tokens wasted on generic text. Provides zero guidance.

✅ **Good** (v2.0 approach - actionable guidance):
```markdown
## Implementation Planning Checklist

Before coding, answer these questions:

### Component Identification
**What 2-4 main classes/modules will you create/modify?**
- List each with single responsibility
- Example: UserService (CRUD), AuthController (HTTP), TokenValidator (JWT)

### Dependency Analysis
**What external libraries or services will you integrate?**
- List each with specific purpose
- Verify versions match project requirements
```
**Benefit**: Actual decision framework. Specialists use it to plan work.

---

## AI-Driven Template Discovery

### How AI Discovers Templates

Claude and other AI agents don't have hardcoded template knowledge - they **discover templates dynamically** using the Task Orchestrator's template system:

1. **Query Templates**: AI uses `query_templates` (operation="list") to find available templates
2. **Filter by Context**: Filters by `targetEntityType` (TASK vs FEATURE) and `isEnabled`
3. **Analyze Options**: Reviews template descriptions and categories
4. **Select Appropriate**: Chooses templates that match the work type
5. **Apply or Suggest**: Either applies automatically or suggests to user

### Autonomous vs. Explicit Template Application

**Autonomous Application** (most common):
```
User: "Create a task to implement the login API"

Claude autonomously:
1. Runs query_templates(operation="list", targetEntityType="TASK", isEnabled=true)
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

**Purpose**: Step-by-step process guidance, decision frameworks, and validation checklists

**Templates**:
- Local Git Branching Workflow
- GitHub PR Workflow
- Task Implementation Workflow
- Bug Investigation Workflow
- Technical Approach

**Target**: Tasks, designed for process automation and implementation planning

**When AI Uses Them**: Implementation tasks, bug fixes, complex planning, any work requiring structured workflow or decision-making guidance

---

### Documentation Properties

**Purpose**: Information capture and context preservation

**Templates**:
- Requirements Specification (Features only)
- Context & Background (Features only)

**Target**: Features, designed for planning and context documentation

**When AI Uses Them**: Feature creation, project planning, capturing business context and requirements

---

### Process & Quality

**Purpose**: Quality assurance and completion standards

**Templates**:
- Testing Strategy (Tasks only)
- Definition of Done (Tasks only)

**Target**: Tasks requiring quality gates

**When AI Uses Them**: Critical functionality, production deployments, quality-focused work

---

## Built-in Templates

### Local Git Branching Workflow
- **Target**: Tasks
- **Sections**: Create Branch, Implement & Commit, Verify & Finalize
- **Tags**: git, workflow-instruction, commands, checklist
- **Auto-Applied**: When git repository detected (.git directory exists)

### GitHub PR Workflow
- **Target**: Tasks
- **Sections**: Pre-Push Validation, Create Pull Request, Review & Merge
- **Tags**: github, pull-request, workflow-instruction, commands, checklist
- **Suggested**: When git detected and user confirms they use PRs

### Task Implementation Workflow
- **Target**: Tasks
- **Sections**: Implementation Analysis, Step-by-Step Implementation, Testing & Validation
- **Tags**: workflow, implementation, mcp-tools, commands, checklist
- **Auto-Applied**: Most implementation tasks with complexity > 3

### Bug Investigation Workflow
- **Target**: Tasks
- **Sections**: Investigation Process, Root Cause Analysis, Fix Implementation & Verification
- **Tags**: bug, investigation, workflow, mcp-tools, checklist
- **Auto-Applied**: Bug-related tasks (when user reports bugs)

### Technical Approach
- **Target**: Tasks
- **Sections**: Implementation Planning Checklist, Technical Decision Log, Integration Points Checklist
- **Tags**: technical, implementation, planning, guidance, checklist
- **Auto-Applied**: Complex tasks (complexity > 6)
- **Note**: Rewritten in v2.0 with actionable decision frameworks (no placeholders)

### Requirements Specification
- **Target**: Features
- **Sections**: Must-Have Requirements, Nice-to-Have Features, Constraints & Non-Functional Requirements
- **Tags**: requirements, specification, context, acceptance-criteria
- **Auto-Applied**: Feature creation workflows

### Context & Background
- **Target**: Features
- **Sections**: Why This Matters, User Context, Dependencies & Coordination
- **Tags**: context, background, business, strategic
- **Auto-Applied**: Feature planning and project setup

### Testing Strategy
- **Target**: Tasks
- **Sections**: Test Coverage, Acceptance Criteria, Testing Checkpoints
- **Tags**: testing, quality, validation, checklist
- **Suggested**: Quality-critical work, production deployments

### Definition of Done
- **Target**: Tasks
- **Sections**: Implementation Complete, Production Ready
- **Tags**: completion, done, checklist, acceptance-criteria
- **Suggested**: Work requiring clear completion standards

> **Full Template Details**: Use `query_templates(operation="get", id="<template-id>", includeSections=true)` to see complete section structure for any template.

---

## Using Templates

### Natural Language Application

The recommended way to use templates is through natural conversation with Claude:

**Example 1: Automatic Template Application**
```
User: "Create a task to implement the user login endpoint"

Claude: [Autonomously]
- Discovers templates via query_templates
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

Claude: [Uses query_templates]
"Available templates for tasks:

AI Workflow Instructions:
- Local Git Branching Workflow (git operations)
- GitHub PR Workflow (pull requests)
- Task Implementation Workflow (structured implementation)
- Bug Investigation Workflow (systematic debugging)
- Technical Approach (implementation planning and decision frameworks)

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
│
└─ Production Deployment
    └─ Technical Approach
        + Testing Strategy
        + Definition of Done
```

### Recommended Combinations

**For New Features**:
```
Context & Background + Requirements Specification
```
Provides complete coverage from business justification through detailed requirements.

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

**Layer 1: Core Workflow** (always include one)
- Task Implementation Workflow OR Bug Investigation Workflow
- Add Technical Approach for complex tasks (complexity 6+)

**Layer 2: Context Documentation** (for features and complex planning)
- Requirements Specification (features)
- Context & Background (features)

**Layer 3: Quality Gates** (for critical work)
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

Claude will use `manage_template` tool (operations: create, addSection) to build your template.

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

1. **Always query templates first**: Use `query_templates(operation="list", ...)` before creating tasks/features
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

Templates work seamlessly with v2.0 tools and Skills:

- **PRD-Driven Development** ⭐: Analyzes PRD content to systematically apply appropriate templates across all features and tasks based on requirements and technical complexity
- **Feature Management Skill (v2.0)**: Automatically discovers and suggests Context & Background + Requirements Specification templates
- **Task Decomposition (v2.0)**: Creates subtasks with Task Implementation Workflow template applied
- **Task Management Skill (v2.0)**: Auto-detects git and applies appropriate git workflow templates for any work type (tasks, features, and bugs)

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
