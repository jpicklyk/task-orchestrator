---
layout: default
title: Templates
---

# Template System

The MCP Task Orchestrator includes built-in templates organized into strategic categories for planning, implementation, and quality assurance. Templates provide workflow instructions, decision frameworks, and quality gates that guide AI assistants through implementation, planning, and validation processes.

## Table of Contents

- [Overview](#overview)
- [AI-Driven Template Discovery](#ai-driven-template-discovery)
- [Template Categories](#template-categories)
- [Built-in Templates](#built-in-templates)
- [Completion Gates](#completion-gates)
- [Planning Tiers](#planning-tiers)
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
- **Flexible Execution**: Works with both direct execution and delegated subagent workflows
- **Verification Gates**: Templates with Verification sections automatically enforce completion criteria

### Templates Structure the WORK

**Templates define the minimum content (floor) for planning and implementation documentation.** They establish WHAT needs to be covered and HOW to approach it — but they're not a ceiling. The agent should add content beyond what templates provide whenever the work demands deeper analysis, additional context, or domain-specific considerations.

Templates define WHAT needs to be done and HOW to approach it, not WHO does it:

- **Requirements Specification**: Creates "Requirements" section -- defines what functionality is needed
- **Technical Approach**: Creates "Technical Decisions" section -- guides planning with decision frameworks
- **Test Plan**: Creates "Test Coverage" section -- defines test requirements

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

#### Pattern 2: Delegated Execution (Claude Code)

The orchestrator delegates work to subagents, each spawned with a clean context via the Task tool:

```
1. Apply templates when creating task/feature
2. Templates create sections (Requirements, Technical Approach, Testing)
3. Orchestrator spawns a subagent with task context and scope
4. Subagent reads template sections for guidance
5. Subagent implements the work
6. Subagent updates sections with results
```

**Key Insight**: Templates create the same sections in both patterns. The difference is WHO reads them and implements the work — you directly, or a delegated subagent. Templates don't dictate execution strategy; Claude Code decides when and how to delegate.

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
- **What**: Delegated agents spawned for implementation work
- **Purpose**: Deep reasoning + code implementation with clean context
- **Scope**: Claude Code only (launched via Task tool)
- **Content**: Implementation, testing, research — any work the orchestrator delegates
- **Example**: Subagent implements API endpoints using template sections for guidance

**Key Principle**: No redundancy across layers. Templates provide WHAT workflow to follow (universal). Skills add HOW to coordinate (Claude Code). Subagents handle complex execution when delegated (Claude Code).

### Section Tag Taxonomy

Templates create sections with **explicit tags** that enable token-efficient reading. Different workflow phases need different section types:

#### Contextual Tags (Planning Phase)
- **context** - Business context, user needs, dependencies, strategic alignment
- **requirements** - Functional and non-functional requirements, must-haves, constraints
- **acceptance-criteria** - Completion criteria, quality standards, verification gates

**When read**: During planning and task breakdown — understanding what needs to be built

#### Actionable Tags (Implementation Phase)
- **workflow-instruction** - Step-by-step implementation processes
- **checklist** - Validation checklists, completion criteria
- **commands** - Bash commands to execute (build, test, deploy)
- **guidance** - Implementation patterns and best practices
- **process** - Workflow processes to follow

**When read**: During active implementation — understanding how to build it

#### Reference Tags (As Needed)
- **reference** - Examples, patterns, reference material
- **technical-details** - Deep technical specifications

**When read**: On demand, when specific technical detail is needed

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
// During planning: Read only context/requirements from feature
sections = query_sections(
  entityType="FEATURE",
  entityId=featureId,
  tags="context,requirements,acceptance-criteria",
  includeContent=true
)
// Token cost: ~2,000-3,000 tokens (vs ~7,000+)
// Savings: 60% reduction

// During implementation: Read only actionable content from task
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

Bad (Old approach - generic placeholders):
```markdown
## Implementation Steps
1. [Component 1]: [Description]
2. [Component 2]: [Description]
3. [Library Name]: [Configuration]
```
**Problem**: 500-1,500 tokens wasted on generic text. Provides zero guidance.

Good (Actionable guidance):
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
- **Planning Tier**: Signal-based auto-sizing determines how many templates to apply (see [Planning Tiers](#planning-tiers))
- **User Request**: Explicit template requests override autonomous selection

---

## Template Categories

Built-in templates are organized into four categories:

### Planning Templates

**Purpose**: Upfront investigation, architectural decisions, and detailed specifications for complex work

**Templates**:
- **Feature Plan** (FEATURE) -- Engineering plan with problem definition, architecture, implementation phases, and verification
- **Codebase Exploration** (TASK) -- Scoped investigation with target files, key questions, and structured findings
- **Design Decision** (TASK) -- Architectural decisions with context, options analysis, and recommendation
- **Implementation Specification** (TASK) -- Detailed spec with code change points, technical specification, and test plan

**When AI Uses Them**: Detailed-tier planning, complex architecture work, multi-phase implementations, research tasks

---

### Implementation Templates

**Purpose**: Step-by-step process guidance, decision frameworks, and validation checklists for active development

**Templates**:
- **Task Implementation** (TASK) -- Implementation analysis, step-by-step implementation, testing and validation
- **Bug Investigation** (TASK) -- Investigation process, root cause analysis, fix implementation and verification
- **Technical Approach** (TASK) -- Technical decision documentation and integration considerations
- **Test Plan** (TASK) -- Test coverage areas and acceptance criteria

**When AI Uses Them**: Implementation tasks, bug fixes, complex planning, any work requiring structured workflow or decision-making guidance

---

### Feature Templates

**Purpose**: Information capture and context preservation for features

**Templates**:
- **Requirements Specification** (FEATURE) -- Must-have requirements, nice-to-have features, constraints and non-functional requirements
- **Context & Background** (FEATURE) -- Business rationale, user context, dependencies and coordination

**When AI Uses Them**: Feature creation, project planning, capturing business context and requirements

---

### Workflow Templates (Disabled by Default)

**Purpose**: Version control process guidance

**Templates**:
- **Local Git Branching Workflow** (TASK) -- Branch naming, commit patterns, pre-push verification
- **GitHub PR Workflow** (TASK) -- PR creation, review process, merge guidelines

**Note**: These templates are disabled by default. Enable them via `manage_template(operation="enable")` if your workflow benefits from structured git guidance.

---

## Built-in Templates

### Planning Templates

#### Feature Plan
- **Target**: Features
- **Sections**: Problem Statement, Architecture Overview, Implementation Phases, File Change Manifest, Design Decisions, Execution Notes, Risks & Mitigations, Verification
- **Tags**: planning, architecture, ai-optimized, engineering
- **Verification**: Yes (JSON criteria gate)
- **Use When**: Detailed-tier planning for complex, multi-phase features

#### Codebase Exploration
- **Target**: Tasks
- **Sections**: Exploration Scope, Key Questions, Findings
- **Tags**: planning, exploration, research, ai-optimized
- **Use When**: Investigating unfamiliar code before planning implementation; naturally parallelizable across multiple exploration tasks

#### Design Decision
- **Target**: Tasks
- **Sections**: Decision Context, Options Analysis, Recommendation
- **Tags**: planning, architecture, design-decision, ai-optimized
- **Use When**: Planning encounters a fork with multiple valid approaches

#### Implementation Specification
- **Target**: Tasks
- **Sections**: Scope & Boundaries, Code Change Points, Technical Specification, Test Plan, Verification
- **Tags**: planning, implementation, specification, ai-optimized
- **Verification**: Yes (JSON criteria gate)
- **Use When**: Detailed implementation spec for one phase or component, after exploration and design decisions are complete

### Implementation Templates

#### Task Implementation
- **Target**: Tasks
- **Sections**: Analysis & Approach, Implementation Notes, Verification & Results, Verification
- **Tags**: implementation, workflow
- **Verification**: Yes (JSON criteria gate)
- **Use When**: Most implementation tasks; provides structure for documenting approach, progress, and results

#### Bug Investigation
- **Target**: Tasks
- **Sections**: Investigation Findings, Root Cause, Fix & Verification, Verification
- **Tags**: bug, investigation, debugging, task-type-bug
- **Verification**: Yes (JSON criteria gate)
- **Use When**: Bug-related tasks requiring systematic investigation and documented root cause analysis

#### Technical Approach
- **Target**: Tasks
- **Sections**: Technical Decisions, Integration Considerations
- **Tags**: technical, architecture, implementation
- **Use When**: Tasks needing documented technical decisions and architectural approach

#### Test Plan
- **Target**: Tasks
- **Sections**: Test Coverage, Acceptance Criteria
- **Tags**: testing, quality, validation, qa
- **Use When**: Quality-critical work requiring defined test coverage and measurable acceptance criteria

### Feature Templates

#### Requirements Specification
- **Target**: Features
- **Sections**: Must-Have Requirements, Nice-to-Have Features, Constraints & Non-Functional Requirements, Verification
- **Tags**: requirements, specification, acceptance-criteria, constraints, documentation
- **Verification**: Yes (JSON criteria gate)
- **Use When**: Feature creation workflows requiring documented requirements and acceptance criteria

#### Context & Background
- **Target**: Features
- **Sections**: Why This Matters, User Context, Dependencies & Coordination
- **Tags**: context, background, business, strategic, documentation
- **Use When**: Feature planning and project setup; capturing business rationale and user needs

### Workflow Templates (Disabled by Default)

#### Local Git Branching Workflow
- **Target**: Tasks
- **Sections**: Create Branch, Implement & Commit, Verify & Finalize
- **Tags**: git, workflow, ai-optimized, version-control, branching
- **Status**: Disabled by default. Enable if your workflow benefits from structured git branch guidance.

#### GitHub PR Workflow
- **Target**: Tasks
- **Sections**: Pre-Push Validation, Create Pull Request, Review & Merge
- **Tags**: github, pull-request, workflow, ai-optimized, mcp-tools, git
- **Status**: Disabled by default. Enable if your workflow benefits from structured PR process guidance.

#### Definition of Done
- **Target**: Tasks
- **Sections**: Completion Checklist, Production Readiness
- **Tags**: completion, done, checklist, handoff, quality
- **Status**: Disabled. Superseded by the Verification Gate system. Structured JSON acceptance criteria in Verification sections now replace the generic checklist approach. See [Completion Gates](#completion-gates).

> **Full Template Details**: Use `query_templates(operation="get", id="<template-id>", includeSections=true)` to see complete section structure for any template.

---

## Completion Gates

### What Are Completion Gates?

Several templates include a **Verification** section that acts as a completion gate. This section uses JSON format to define structured acceptance criteria that must all pass before the entity can be marked complete.

### How It Works

1. **Template applies Verification section**: When `apply_template` detects a section titled "Verification", it automatically sets `requiresVerification=true` on the entity.

2. **Criteria are defined in JSON format**:
   ```json
   [
     {"criteria": "Unit tests pass for new/modified code", "pass": false},
     {"criteria": "No regressions in existing test suite", "pass": false}
   ]
   ```

3. **Agent verifies criteria during implementation**: As work progresses, the agent updates each criterion's `pass` field to `true` after personally confirming the condition.

4. **Completion is enforced**: The MCP server blocks completion via `request_transition(trigger="complete")` until ALL criteria in the Verification section have `pass: true`.

### Templates with Verification Sections

The following templates include Verification sections and automatically enable the completion gate:

- **Task Implementation** -- Verifies unit tests pass and no regressions
- **Bug Investigation** -- Verifies bug cannot be reproduced, regression test added, no side effects
- **Requirements Specification** -- Verifies all child tasks completed, end-to-end flow works
- **Feature Plan** -- Verifies acceptance criteria for the engineering plan
- **Implementation Specification** -- Verifies acceptance criteria for the spec

### Relationship to Definition of Done

The Definition of Done template has been superseded by this verification gate system. Where Definition of Done used generic markdown checklists, Verification sections provide:

- **Structured data**: JSON format enables programmatic checking
- **Enforcement**: The MCP server actively blocks premature completion
- **Specificity**: Each template defines criteria relevant to its work type
- **Automatic enablement**: No manual configuration needed -- applying a template with a Verification section is sufficient

---

## Planning Tiers

### Signal-Based Auto-Sizing

Templates are designed to compose based on planning depth. Each tier's recommended templates define the **minimum documentation** for that level of work — the agent determines the tier from context signals and then builds on the template baseline with project-specific content.

### Tier Definitions

**Quick** -- Task breakdown only, no templates
- For small changes and straightforward work
- Signal words: 'fix', 'typo', 'rename', single-file scope
- Minimum tier for: Bug fixes (simple)

**Standard** -- Feature + tasks with documentation templates (DEFAULT)
- For most feature work and moderate-scope changes
- Signal words: 'add feature', 'implement', 'build', moderate scope
- Minimum tier for: New features, research tasks

**Detailed** -- Full engineering plan with comprehensive templates
- For complex, multi-phase, or architecturally significant work
- Signal words: 'redesign', 'refactor the system', 'architect', 'migrate', multi-phase
- Minimum tier for: Architecture changes

### Recommended Templates by Tier

```
Planning Tier?
|
+-- Quick (small changes, fixes)
|   +-- No templates needed -- task breakdown only
|
+-- Standard (most features)
|   +-- Feature: Requirements Specification + Context & Background
|   +-- Tasks: Task Implementation + Technical Approach + Test Plan
|
+-- Detailed (complex, multi-phase)
    +-- Feature: Feature Plan + Requirements Specification + Context & Background
    +-- Tasks: Implementation Specification + Design Decision + Codebase Exploration
        + Task Implementation + Test Plan
```

### Work Type and Minimum Tiers

| Work Type | Minimum Tier | Rationale |
|-----------|-------------|-----------|
| Bug fix (simple) | Quick | Straightforward fix, no planning overhead |
| Bug fix (complex) | Standard | Needs investigation structure |
| New feature | Standard | Needs requirements and context documentation |
| Architecture change | Detailed | Needs full engineering plan |
| Research / exploration | Standard | Needs scoped investigation structure |

---

## Using Templates

### Natural Language Application

The recommended way to use templates is through natural conversation with Claude:

**Example 1: Automatic Template Application**
```
User: "Create a task to implement the user login endpoint"

Claude: [Autonomously]
- Discovers templates via query_templates
- Selects: Task Implementation + Technical Approach
- Creates task with both templates applied
- Confirms: "Created task with Task Implementation and Technical Approach templates"
```

**Example 2: Feature Creation with Templates**
```
User: "Create a feature for payment processing"

Claude: [Suggests templates]
"I'll create the Payment Processing feature. I recommend applying:
- Context & Background (business justification)
- Requirements Specification (detailed requirements)

Proceeding with these templates..."
```

**Example 3: Bug Investigation**
```
User: "Create a task to fix the authentication bug"

Claude: [Autonomously]
- Selects: Bug Investigation
- Creates task with bug investigation template
- Confirms: "Created bug task with Bug Investigation template"
```

**Example 4: Adding Templates Later**
```
User: "Apply test plan template to the login task"

Claude: [Uses apply_template tool]
"Applied Test Plan template to login task. The task now has guidance for:
- Test coverage areas
- Acceptance criteria"
```

### Template Discovery in Action

Ask Claude to show you available templates:

```
User: "What templates are available for tasks?"

Claude: [Uses query_templates]
"Available templates for tasks:

Planning:
- Codebase Exploration (scoped investigation)
- Design Decision (architectural decisions)
- Implementation Specification (detailed specs)

Implementation:
- Task Implementation (structured implementation)
- Bug Investigation (systematic debugging)
- Technical Approach (technical decisions and architecture)
- Test Plan (comprehensive testing)

Would you like me to apply any of these to a task?"
```

---

## Template Composition Patterns

### Decision Tree for Template Selection

```
What type of work are you doing?
|
+-- Implementation Task
|   +-- Simple (Quick tier)
|   |   +-- Task Implementation only (or no template)
|   |
|   +-- Moderate (Standard tier)
|   |   +-- Task Implementation + Technical Approach + Test Plan
|   |
|   +-- Complex (Detailed tier)
|       +-- Implementation Specification + Design Decision
|           + Task Implementation + Test Plan
|
+-- Bug Fix
|   +-- Simple
|   |   +-- Bug Investigation
|   |
|   +-- Complex
|       +-- Bug Investigation + Technical Approach
|
+-- Feature Planning
|   +-- Standard
|   |   +-- Requirements Specification + Context & Background
|   |
|   +-- Detailed
|       +-- Feature Plan + Requirements Specification + Context & Background
|
+-- Research / Exploration
    +-- Codebase Exploration
```

### Recommended Combinations

**Standard Feature**:
```
Feature: Requirements Specification + Context & Background
Tasks:   Task Implementation (per task) + Technical Approach + Test Plan
```
Provides requirements documentation with structured implementation guidance per task.

**Detailed Feature**:
```
Feature: Feature Plan + Requirements Specification + Context & Background
Tasks:   Implementation Specification + Design Decision + Codebase Exploration
         + Task Implementation + Test Plan
```
Full engineering plan with investigation, architectural decisions, and detailed specs.

**Bug Fix**:
```
Bug Investigation (+ Technical Approach if complex)
```
Systematic investigation with documented root cause and fix verification.

**Research / Exploration**:
```
Codebase Exploration
```
Scoped investigation with explicit boundaries to prevent infinite exploration.

### Template Stacking Strategy

**Layer 1: Core Workflow** (always include one)
- Task Implementation OR Bug Investigation
- Add Technical Approach for tasks needing documented decisions

**Layer 2: Context Documentation** (for features)
- Requirements Specification (features)
- Context & Background (features)

**Layer 3: Planning Depth** (for complex work)
- Feature Plan (features, Detailed tier)
- Codebase Exploration (research tasks)
- Design Decision (architectural forks)
- Implementation Specification (detailed specs)

**Layer 4: Quality Gates** (for critical work)
- Test Plan (test coverage and acceptance criteria)

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
2. **Match tier to complexity**: Use signal-based auto-sizing to determine the right planning depth
3. **Suggest multiple templates**: For comprehensive coverage, recommend 2-3 templates per entity
4. **Respect user preferences**: If user specifies templates, use exactly those
5. **Explain template value**: Briefly explain why templates are being applied
6. **Honor verification gates**: Update Verification section criteria as work completes; do not skip the gate

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
- Update Verification criteria to `pass: true` only after personally confirming each condition

**Don't**:
- Leave template sections empty or with placeholder text
- Apply too many overlapping templates
- Ignore template guidance and structure
- Create templates without clear purpose
- Mark Verification criteria as passing without actual verification

---

## Integration with Workflows

### How Templates and Workflows Complement Each Other

**Templates structure the WORK** (what needs to be documented):
- Create sections automatically when applied
- Define required documentation (Requirements, Technical Approach, Testing)
- Provide consistent structure across all tasks and features

**Workflow Patterns guide the PROCESS** (how to accomplish the work):
- Provide step-by-step procedural guidance
- Integrate MCP tools systematically
- Offer quality validation checkpoints

Templates work seamlessly with Skills and planning tiers:

- **Feature Orchestration Skill**: Automatically discovers and suggests Context & Background + Requirements Specification templates
- **Task Orchestration Skill**: Creates tasks with appropriate implementation templates applied
- **Planning Tiers**: Signal-based auto-sizing determines the right template depth for each piece of work
- **Completion Gates**: Verification sections enforce quality standards before allowing status transitions

### Templates + Delegated Execution

When using Claude Code, the orchestrator can delegate work to subagents. Templates provide consistent context regardless of who executes:

1. **Templates create the sections** (Requirements, Technical Approach, Test Plan)
2. **Subagents read those sections** for context when implementing
3. **Templates provide consistent structure** across all delegated work

**Example Flow**:
```
1. Create task with Technical Approach template
   -> Creates "Technical Decisions" section with architecture guidance

2. Orchestrator spawns implementation subagent
   -> Subagent reads "Technical Decisions" section for context
   -> Implements code following the architecture
   -> Updates section with implementation notes

3. Orchestrator spawns testing subagent
   -> Reads "Test Coverage" section for test requirements
   -> Reads "Technical Decisions" for implementation context
   -> Writes comprehensive tests
   -> Updates section with test results

4. Verification gate enforced
   -> All Verification criteria must pass before completion
```

**Key Point**: Templates work identically whether you're implementing directly or delegating to subagents. The sections provide structure and context in both scenarios.

> **See Also**: [Workflow Patterns](workflow-patterns) for complete workflow integration details and [AI Guidelines](ai-guidelines#layer-3-dynamic-templates-database-driven) for template strategy patterns.

---

## Additional Resources

- **[AI Guidelines - Template Strategy](ai-guidelines#layer-3-dynamic-templates-database-driven)** - How AI discovers and applies templates
- **[Workflow Patterns](workflow-patterns)** - Integration with workflow automation
- **[Quick Start](quick-start)** - Getting started with templates
- **[API Reference](api-reference)** - Complete template tool documentation
- **[Status Progression](status-progression)** - Status workflow and completion gates

---

**Questions?** Ask Claude "How do templates work?" or "What templates should I use for [your scenario]?" - Claude understands the template system and can guide you.
