# Claude Code Skills System

**Lightweight, focused capabilities that achieve 60-82% token savings through progressive disclosure**

---

## Table of Contents

- [Overview](#overview)
- [What Are Skills?](#what-are-skills)
- [Skills vs Subagents vs Direct Tools](#skills-vs-subagents-vs-direct-tools)
- [How Skills Work](#how-skills-work)
  - [Progressive Disclosure](#progressive-disclosure)
  - [Discovery and Invocation](#discovery-and-invocation)
  - [Skill Composition](#skill-composition)
- [Available Skills](#available-skills)
  - [Task Management Skill](#task-management-skill)
  - [Feature Management Skill](#feature-management-skill)
  - [Dependency Analysis Skill](#dependency-analysis-skill)
  - [Skill Builder](#skill-builder)
  - [Hook Builder](#hook-builder)
- [YAML Frontmatter Structure](#yaml-frontmatter-structure)
- [Creating Custom Skills](#creating-custom-skills)
  - [Skill Templates and Patterns](#skill-templates-and-patterns)
  - [Step-by-Step Creation Guide](#step-by-step-creation-guide)
  - [Supporting Files Organization](#supporting-files-organization)
- [Integration with Hooks and Subagents](#integration-with-hooks-and-subagents)
- [Testing and Debugging Skills](#testing-and-debugging-skills)
- [Token Efficiency Analysis](#token-efficiency-analysis)
- [Best Practices](#best-practices)
- [Troubleshooting](#troubleshooting)

---

## Overview

Claude Code Skills are **lightweight capability modules** that provide focused, efficient workflows for common coordination tasks. Unlike subagents that load full system prompts, Skills use **progressive disclosure** - loading only the minimal instructions needed for each invocation.

### 🎯 Key Benefits

- **60-82% token reduction** vs equivalent subagent operations
- **Fast activation** - no subagent launch overhead
- **Tool restriction** - each Skill can only access specified tools
- **Composable** - multiple Skills can work together
- **Easy to create** - simple markdown files with YAML frontmatter

### 🚀 Quick Example

```
User: "What's the next task to work on in this feature?"

→ Feature Management Skill activates (300 tokens)
→ Makes 2 tool calls: query_container(operation='get', ...), get_next_task()
→ Returns recommendation

vs Subagent approach: 1400 tokens for the same operation (78% savings)
```

### When to Use Skills

**Use Skills for:**
- ✅ Simple coordination (2-5 tool calls)
- ✅ Status checks and queries
- ✅ Repetitive workflows
- ✅ Fast responses without reasoning overhead

**Use Subagents for:**
- ✅ Complex reasoning or planning
- ✅ Code generation
- ✅ Multi-step workflows with backtracking
- ✅ Full conversation context needed

---

## What Are Skills?

Skills are **markdown files** stored in `src/main/resources/skills/[skill-name]/SKILL.md` that define:

1. **Identity** (YAML frontmatter):
   - Name and description for activation
   - Allowed tools (restricted access)

2. **Instructions** (markdown body):
   - Clear workflow steps
   - Tool usage patterns
   - Response formats
   - Error handling

3. **Supporting Documentation** (optional):
   - `examples.md` - Concrete usage scenarios
   - `reference.md` - Tool reference documentation
   - `templates/` - File templates for generation Skills
   - `README.md` - Overview and usage guide

### Anatomy of a Skill

```markdown
---
name: Task Management
description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status, mcp__task-orchestrator__update_task, mcp__task-orchestrator__recommend_agent
---

# Task Management Skill

**Purpose**: Coordinate task lifecycle operations...

**Token Savings**: ~1300 tokens per START operation, ~1500 tokens per END operation → ~300-600 tokens (77% reduction)

## When to Use This Skill

✅ Use for:
- Recommending which specialist should work on a task
- Completing a task and creating a summary
- Updating task status

❌ Do NOT use for:
- Actually implementing the task (use specialist subagents)
- Complex reasoning or planning

## Core Capabilities

### 1. Recommend Specialist for Task

**Steps**:
1. Get task details: `query_container(operation="get", containerType="task", id=id, includeSections=true)`
2. Get specialist recommendation: `recommend_agent(taskId)`
3. Format recommendation with context

...
```

### Progressive Disclosure in Action

**Traditional Subagent**: Loads entire system prompt (~1500 tokens) every time

**Skill**: Loads only what's needed:
- User says "complete this task" → Skill loads 400 tokens about task completion
- User says "check dependencies" → Skill loads 300 tokens about dependency checking

Claude Code automatically extracts the relevant section based on the user's request.

---

## Workflow Diagrams

Visual representations of Skills in action are available as interactive HTML diagrams:

- **[Feature Orchestration Skill Flow](diagrams/feature-orchestration-skill-flow.html)** - How Feature Orchestration Skill manages feature lifecycle with event detection
- **[Task Orchestration Skill Flow](diagrams/task-orchestration-skill-flow.html)** - How Task Orchestration Skill coordinates parallel task execution

These diagrams illustrate the event-driven architecture where Skills detect workflow events and delegate to appropriate tools and specialists.

## Skills vs Subagents vs Direct Tools

### Comparison Matrix

| Aspect | Direct MCP Tools | Skills | Subagents |
|--------|-----------------|--------|-----------|
| **Token Cost** | ~50-100 per tool | 300-600 per workflow | 1400-1700 per launch |
| **Use Case** | Single operations | 2-5 tool sequences | Complex reasoning |
| **Context** | None | Focused instructions | Full system prompt |
| **Tool Access** | All tools | Restricted by allowed-tools | All tools |
| **Overhead** | None | Minimal | Subagent launch |
| **Best For** | One-off actions | Repetitive coordination | Code generation, planning |

### Decision Guide

```
Does task require complex reasoning or code generation?
├─ YES → Use Subagent (Backend Engineer, Planning Specialist)
└─ NO → Does it need 2+ MCP tool calls in sequence?
    ├─ YES → Use Skill (Feature Management, Task Management)
    └─ NO → Use Direct MCP Tool (get_task, update_task)
```

### Workflow Combination Examples

#### Example 1: Feature with Multiple Tasks

```
User: "Work on authentication feature"

Step 1: Feature Management Skill (300 tokens)
→ query_container(operation="get", containerType="feature", includeTaskCounts=true)
→ get_next_task(featureId, limit=1)
→ Returns: "Recommend working on T1: Database Schema"

Step 2: Task Management Skill (400 tokens)
→ query_container(operation="get", containerType="task", id=T1, includeSections=true)
→ recommend_agent(T1)
→ Returns: "Route to Database Engineer specialist"

Step 3: Database Engineer Subagent (1600 tokens)
→ Reads task sections
→ Implements database schema
→ Writes tests
→ Creates Summary section
→ Reports completion

Step 4: Task Management Skill (300 tokens)
→ manage_sections(operation="add", entityType="TASK", title="Summary", ...)
→ manage_container(operation="setStatus", containerType="task", id=T1, status="completed")
→ Returns: "Task completed"

Total: ~2600 tokens vs ~6200 tokens without Skills (58% savings)
```

#### Example 2: Simple Status Check

```
User: "What's blocking progress?"

Dependency Analysis Skill (350 tokens)
→ get_blocked_tasks(featureId)
→ For each: query_container(operation="get", containerType="task", id=id) to get details
→ Returns: "3 tasks blocked by T2 (Create API endpoints)"

No subagent needed! Skill handles entire workflow efficiently.
```

---

## How Skills Work

### Progressive Disclosure

Skills use Claude Code's **progressive disclosure** feature:

1. **User makes request**: "Complete this task"
2. **Claude identifies relevant Skill**: Task Management
3. **Claude extracts relevant section**: Only the "Complete Task" instructions (~400 tokens)
4. **Claude executes workflow**: Follows extracted instructions
5. **Response returned**: Brief summary to user

**Key Insight**: The full 2000-token Task Management Skill document is NOT loaded - only the 400-token subsection about task completion.

### Discovery and Invocation

#### How Claude Finds Skills

Claude Code automatically discovers Skills by:
1. **Scanning** `src/main/resources/skills/*/SKILL.md` files
2. **Indexing** name and description from YAML frontmatter
3. **Matching** user intent to description keywords
4. **Activating** when description matches user request

#### Activation Keywords

Skills activate based on **description keywords**:

```yaml
description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.
```

**Triggers activation for**:
- "Route this task to a specialist"
- "Complete task T123"
- "Update task status"
- "Check task details"

**Best practice**: Include multiple activation phrases in description.

#### Manual Invocation

You can also explicitly request a Skill:

```
User: "Use the Dependency Analysis Skill to check what's blocking feature F1"
```

This guarantees the specified Skill activates.

### Skill Composition

Skills can work together in sequences:

```
User: "Work through authentication feature systematically"

Feature Management Skill
→ Recommends next task: T1

Task Management Skill
→ Routes T1 to Database Engineer

[Database Engineer works on T1]

Task Management Skill
→ Completes T1

Feature Management Skill
→ Recommends next task: T2

Task Management Skill
→ Routes T2 to Backend Engineer

[Pattern continues...]
```

Each Skill invocation is independent with minimal token cost.

---

## Available Skills

### Task Management Skill

**Location**: `src/main/resources/skills/task-management/`

**Purpose**: Coordinate task lifecycle operations including routing, status updates, and completion.

**Token Savings**: 77% reduction (1300-1500 tokens → 300-600 tokens)

**Core Capabilities**:
1. **Recommend Specialist**: Determine which agent should work on a task
2. **Complete Task**: Create Summary section and mark complete
3. **Update Status**: Change task status with validation
4. **Check Dependencies**: Verify dependencies before starting

**Typical Workflow**:
```
1. query_container(operation="get", containerType="task", id=id, includeSections=true)
2. recommend_agent(taskId)
3. Format recommendation with task context
```

**When to Use**:
- "Start work on task T123"
- "Complete task T456"
- "What specialist should handle this task?"
- "Check if task is ready to start"

**Supporting Files**:
- `examples.md` - 7 concrete usage scenarios
- `routing-guide.md` - Detailed specialist routing patterns

---

### Feature Management Skill

**Location**: `src/main/resources/skills/feature-management/`

**Purpose**: Coordinate feature workflows including next task recommendation, progress tracking, and feature completion.

**Token Savings**: 60-82% reduction (1400-1700 tokens → 300-600 tokens)

**Core Capabilities**:
1. **Recommend Next Task**: Find unblocked task to work on
2. **Check Progress**: Get feature status and task counts
3. **Complete Feature**: Create Summary and mark complete (with quality gates)
4. **List Blocked Tasks**: Identify dependency blockers

**Quality Gates** (Feature Completion):
- Verifies ALL tasks are completed
- Checks that implementation tasks report passing tests
- Blocks completion if any tests are failing
- Creates comprehensive Summary section

**Typical Workflow**:
```
1. query_container(operation="get", containerType="feature", id=id, includeTasks=true, includeTaskCounts=true)
2. get_next_task(featureId, limit=1, includeDetails=true)
3. Return recommendation with context
```

**When to Use**:
- "What's the next task in this feature?"
- "How's progress on feature F1?"
- "Complete feature F2"
- "What tasks are blocked in this feature?"

**Supporting Files**:
- `examples.md` - Concrete usage examples
- `reference.md` - MCP tool reference

---

### Dependency Analysis Skill

**Location**: `src/main/resources/skills/dependency-analysis/`

**Purpose**: Analyze task dependencies, identify bottlenecks, and recommend resolution order.

**Token Savings**: 65% reduction for complex dependency analysis

**Core Capabilities**:
1. **Find Blocked Tasks**: List all tasks waiting on dependencies
2. **Analyze Dependency Chains**: Build complete dependency tree
3. **Identify Bottlenecks**: Find tasks blocking the most work
4. **Recommend Resolution Order**: Prioritize which tasks to complete first

**Typical Workflow**:
```
1. get_blocked_tasks(featureId)
2. For each blocker: analyze impact (how many tasks unblocked)
3. Calculate resolution score
4. Recommend priority order
```

**When to Use**:
- "What's blocking progress?"
- "Which task should we work on first?"
- "Show me the dependency chain for T123"
- "What tasks are bottlenecks?"

**Supporting Files**:
- `examples.md` - Dependency analysis scenarios
- `troubleshooting.md` - Common dependency issues

---

### Skill Builder

**Location**: `src/main/resources/skills/skill-builder/`

**Purpose**: Help users create custom Skills through guided workflow.

**Token Savings**: Reduces Skill creation time by 70% vs manual authoring

**Core Capabilities**:
1. **Interview User**: Understand Skill requirements
2. **Generate SKILL.md**: Create properly formatted Skill file
3. **Create Supporting Files**: examples.md, reference.md, templates
4. **Provide Testing Guidance**: How to verify Skill works
5. **Offer Customization Tips**: How to adjust for specific needs

**Typical Workflow**:
```
1. Ask clarifying questions (purpose, tools, triggers, scope)
2. Generate SKILL.md with proper structure
3. Create examples.md with usage scenarios
4. Create reference.md if using MCP tools
5. Provide testing instructions
```

**When to Use**:
- "Help me create a Skill"
- "I want to create a Skill that does X"
- "Create a documentation generator Skill"

**Supporting Files**:
- `examples.md` - Skill creation scenarios
- `skill-templates.md` - 7 reusable Skill patterns
- `README.md` - Overview and best practices

**Skill Templates Available**:
1. Coordination Skill - Coordinate MCP tool calls
2. File Generation Skill - Generate files from templates
3. Analysis Skill - Analyze data and provide insights
4. Integration Skill - Bridge with external systems
5. Validation Skill - Quality gates and checks
6. Reporting Skill - Generate reports from data
7. Migration Skill - Transform/move data

---

### Hook Builder

**Location**: `src/main/resources/skills/hook-builder/`

**Purpose**: Help users create Claude Code hooks that integrate with Task Orchestrator.

**Token Savings**: Reduces hook creation time by 80% vs manual authoring

**Core Capabilities**:
1. **Interview User**: Understand hook requirements (event, trigger, action)
2. **Generate Hook Script**: Create bash script with defensive checks
3. **Configure Settings**: Add hook to `.claude/settings.local.json`
4. **Provide Testing Instructions**: Sample JSON inputs to test hook
5. **Document Usage**: Add to hooks README

**Typical Workflow**:
```
1. Ask about event (PostToolUse, SubagentStop, PreToolUse)
2. Identify tool to watch (set_status, update_feature, etc.)
3. Define action (git commit, run tests, send notification)
4. Generate bash script with error handling
5. Update settings.local.json
6. Provide test JSON inputs
```

**When to Use**:
- "Create a hook to auto-commit on task completion"
- "I want tests to run before feature completion"
- "Help me create a notification hook"

**Supporting Files**:
- `examples.md` - Hook creation scenarios
- `hook-templates.md` - Common hook patterns
- Hook scripts for git commits, test gates, notifications

**Common Hook Patterns**:
1. Auto-commit on task completion
2. Quality gate on feature completion (run tests)
3. Notification on subagent completion
4. Metrics logging for task tracking

---

## YAML Frontmatter Structure

### Required Fields

```yaml
---
name: Skill Name
description: Brief description including activation keywords. Use when [scenario 1], [scenario 2], or [scenario 3].
allowed-tools: tool1, tool2, tool3
---
```

### Field Descriptions

#### name (required)

**Purpose**: Skill identifier displayed to user

**Format**: Title case, concise (2-4 words)

**Examples**:
- `Task Management`
- `Feature Management`
- `Dependency Analysis`
- `Skill Builder`

**Guidelines**:
- Use clear, descriptive names
- Avoid abbreviations
- Match the Skill's primary purpose

---

#### description (required)

**Purpose**: Activation trigger and brief overview

**Format**: 1-3 sentences, under 200 characters for optimal activation

**Structure**:
```
[Primary capability]. Use when [scenario 1], [scenario 2], or [scenario 3].
```

**Examples**:

✅ **Good**:
```yaml
description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.
```

❌ **Bad** (too vague):
```yaml
description: Helps with tasks
```

❌ **Bad** (too long):
```yaml
description: This Skill is designed to help you manage the complete lifecycle of tasks within the Task Orchestrator system including but not limited to creating tasks, updating their status, completing them with summaries, routing them to appropriate specialist agents based on tags, checking dependencies before starting work, and coordinating with other Skills for comprehensive feature management workflows.
```

**Activation Keywords**:
- Include verbs: "coordinate", "manage", "analyze", "create", "check"
- Include nouns: "task", "feature", "dependency", "workflow"
- Include scenarios: "Use when [doing X]"

---

#### allowed-tools (required)

**Purpose**: Restrict tool access for security and focus

**Format**: Comma-separated list of tool names

**Tool Types**:
- **File tools**: `Read`, `Write`, `Bash`, `Grep`, `Glob`
- **MCP tools**: `mcp__task-orchestrator__[tool_name]`

**Examples**:

```yaml
# Coordination Skill (Task Orchestrator tools only)
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status, mcp__task-orchestrator__update_task

# File Generation Skill (file tools + MCP query tools)
allowed-tools: Read, Write, mcp__task-orchestrator__get_task, mcp__task-orchestrator__get_sections

# Integration Skill (file tools + bash for API calls)
allowed-tools: Read, Write, Bash, mcp__task-orchestrator__search_tasks

# Analysis Skill (read-only)
allowed-tools: Read, Grep, mcp__task-orchestrator__get_blocked_tasks, mcp__task-orchestrator__get_task_dependencies
```

**Best Practices**:
- **Principle of least privilege**: Only include tools truly needed
- **Be specific**: Don't grant `Write` if Skill only reads
- **Test restrictions**: Verify Skill can't access unintended tools

**Available MCP Tools** (partial list):
- `mcp__task-orchestrator__get_task`
- `mcp__task-orchestrator__set_status`
- `mcp__task-orchestrator__update_task`
- `mcp__task-orchestrator__get_feature`
- `mcp__task-orchestrator__update_feature`
- `mcp__task-orchestrator__get_next_task`
- `mcp__task-orchestrator__get_blocked_tasks`
- `mcp__task-orchestrator__get_task_dependencies`
- `mcp__task-orchestrator__recommend_agent`
- `mcp__task-orchestrator__add_section`
- `mcp__task-orchestrator__search_tasks`
- `mcp__task-orchestrator__list_templates`

Use `mcp__task-orchestrator__list_templates` to discover all available tools.

---

### Optional Fields

While not currently used, you can add custom fields for documentation:

```yaml
---
name: My Skill
description: ...
allowed-tools: ...
version: 1.0.0
author: Your Name
category: coordination
tags: tasks, features, workflow
---
```

---

## Creating Custom Skills

### Skill Templates and Patterns

The Skill Builder includes 7 reusable templates for common patterns. See `src/main/resources/skills/skill-builder/skill-templates.md` for complete templates.

#### Template 1: Coordination Skill

**Use when**: Coordinating multiple MCP tool calls for a specific workflow

**Structure**:
```markdown
---
name: [Coordination Skill Name]
description: Coordinate [X workflow]. Use when [managing/coordinating/checking] [specific aspect].
allowed-tools: [relevant MCP tools]
---

# [Coordination Skill Name]

## What You Do
Coordinate [workflow] by making the right sequence of MCP tool calls.

## Workflow
1. **Gather context**: query_container(operation="get", ...)
2. **Perform action**: manage_container(operation="update", ...)
3. **Update state**: manage_container(operation="setStatus", ...)
4. **Return to user**: [summary]

## Common Patterns
### Pattern 1: [Scenario]
[Steps for handling this scenario]
```

**Examples**: Task Management, Feature Management

---

#### Template 2: File Generation Skill

**Use when**: Generating files from templates or data

**Structure**:
```markdown
---
name: [Generator Skill Name]
description: Generate [file type] from [source]. Use when creating [files], [generating documentation].
allowed-tools: Read, Write, [optional MCP tools]
---

# [Generator Skill Name]

## What You Do
Generate [file type] by reading [source], applying [template], and writing output.

## Workflow
1. **Read source data**: [MCP tool or Read]
2. **Apply template**: [transformation logic]
3. **Write output**: Write([path], [content])
4. **Return to user**: [file location]

## Template Structure
[Show generated file format]
```

**Examples**: API Documentation Generator, Config File Creator

---

#### Template 3: Analysis Skill

**Use when**: Analyzing data and providing insights

**Structure**:
```markdown
---
name: [Analyzer Skill Name]
description: Analyze [data type] and provide [insights]. Use when analyzing [aspect], checking [metrics].
allowed-tools: Read, Grep, [MCP query tools]
---

# [Analyzer Skill Name]

## What You Do
Analyze [data sources], identify [patterns], and provide recommendations.

## Workflow
1. **Collect data**: [query tools]
2. **Analyze patterns**: [analysis logic]
3. **Generate insights**: [reporting]
4. **Return to user**: [results]

## Analysis Criteria
- [Criterion 1]: [threshold]
- [Criterion 2]: [threshold]
```

**Examples**: Dependency Analysis, Test Coverage Analyzer

---

#### Template 4: Integration Skill

**Use when**: Bridging Task Orchestrator with external systems

**Structure**:
```markdown
---
name: [Integration Skill Name]
description: Integrate with [external system]. Use when [syncing/exporting] [data type].
allowed-tools: Bash, Read, Write, [MCP tools]
---

# [Integration Skill Name]

## What You Do
Bridge Task Orchestrator with [system] by [action].

## Workflow
1. **Read internal data**: [MCP tool]
2. **Transform data**: [mapping]
3. **Call external API**: Bash (curl)
4. **Update internal state**: [MCP tool]
5. **Return to user**: [confirmation]

## Configuration Required
- `API_KEY`: [how to obtain]
- `API_URL`: [endpoint]

## API Reference
[curl examples]
```

**Examples**: Jira Task Exporter, GitHub Issue Sync

---

#### Template 5: Validation/Quality Gate Skill

**Use when**: Checking quality criteria before proceeding

**Structure**:
```markdown
---
name: [Validator Skill Name]
description: Validate [aspect] meets [criteria]. Use when checking [quality aspect], verifying [requirements].
allowed-tools: Bash, Read, Grep, [MCP tools]
---

# [Validator Skill Name]

## What You Do
Validate that [aspect] meets [criteria] and report violations.

## Workflow
1. **Collect validation data**: [tools]
2. **Apply validation rules**: [criteria]
3. **Report violations**: [pass/fail]
4. **Return to user**: [results]

## Validation Rules
### Rule 1: [Name]
- **Check**: [what to validate]
- **Pass criteria**: [threshold]
- **Fail action**: [blocking/warning]
```

**Examples**: Code Quality Checker, Commit Message Validator

---

#### Template 6: Reporting Skill

**Use when**: Generating reports from project data

**Structure**:
```markdown
---
name: [Reporter Skill Name]
description: Generate [report type] from [data]. Use when creating [reports], [tracking metrics].
allowed-tools: [MCP query tools], Read, Write
---

# [Reporter Skill Name]

## What You Do
Generate [report] by querying [data], aggregating [metrics], and formatting results.

## Workflow
1. **Query data**: [MCP tools]
2. **Aggregate metrics**: [calculations]
3. **Format report**: [structure]
4. **Save/display**: Write or return

## Report Structure
[Markdown template for report]
```

**Examples**: Sprint Progress Report, Test Results Summary

---

#### Template 7: Migration/Data Transformation Skill

**Use when**: Moving or transforming data between structures

**Structure**:
```markdown
---
name: [Migration Skill Name]
description: Migrate [data] from [source] to [target]. Use when moving [data type], transforming [format].
allowed-tools: [relevant MCP tools]
---

# [Migration Skill Name]

## What You Do
Safely move [data] from [source] to [target] while preserving [aspects].

## Workflow
1. **Validate source**: [checks]
2. **Check conflicts**: [validation]
3. **Transform data**: [mapping]
4. **Perform migration**: [execute]
5. **Verify**: [post-migration checks]
6. **Return to user**: [summary]

## Safety Checks
- **Pre-migration**: [validations]
- **Post-migration**: [verifications]

## Rollback Procedure
[How to undo if needed]
```

**Examples**: Task Migration Assistant, Feature Restructure Tool

---

### Step-by-Step Creation Guide

#### Option 1: Use Skill Builder (Recommended)

The easiest way to create a Skill is using the Skill Builder Skill:

```
User: "Help me create a Skill that generates weekly progress reports"

Skill Builder will:
1. Interview you about requirements
2. Ask about tools needed
3. Identify activation keywords
4. Generate SKILL.md file
5. Create supporting documentation
6. Provide testing instructions
```

**Benefits**:
- Guided workflow
- Proper structure guaranteed
- Examples included automatically
- Testing guidance provided

---

#### Option 2: Manual Creation

For developers who prefer hands-on control:

**Step 1: Choose a Template**

Select from the 7 templates based on your Skill's primary purpose.

**Step 2: Create Directory Structure**

```bash
mkdir -p src/main/resources/skills/my-skill-name
cd src/main/resources/skills/my-skill-name
```

**Step 3: Create SKILL.md**

```markdown
---
name: My Skill Name
description: [Primary capability]. Use when [scenario 1], [scenario 2], or [scenario 3].
allowed-tools: [comma-separated tool list]
---

# My Skill Name

**Purpose**: [One-sentence purpose]

**Token Savings**: [Estimate if applicable]

## When to Use This Skill

✅ **Use for**:
- [Use case 1]
- [Use case 2]
- [Use case 3]

❌ **Do NOT use for**:
- [Anti-pattern 1]
- [Anti-pattern 2]

## Core Capabilities

### 1. [Capability Name]

**When**: [Situation where this is needed]

**Steps**:
1. [Step 1 with tool call]
2. [Step 2 with tool call]
3. [Step 3 with result]

**Response Format**:
```
[Example output format]
```

## Tool Usage Patterns

### Pattern 1: [Common Workflow]

```
1. tool_call_1(params)
   → [Expected result]

2. tool_call_2(params)
   → [Expected result]

3. Format response:
   "[Example response to user]"
```

## Error Handling

### [Error Type]
```
Error: [Error message]

Action: [How to resolve]
```

## Best Practices

1. [Best practice 1]
2. [Best practice 2]
3. [Best practice 3]

## See Also

- **examples.md**: Concrete usage examples
- **reference.md**: Tool reference (if applicable)
```

**Step 4: Create examples.md**

```markdown
# My Skill Name - Examples

## Example 1: [Realistic Scenario]

### Scenario
[Context and situation]

### Input
```
User: "[What user would say]"
```

### Skill Execution

**Step 1: [First Action]**
```
tool_name(param1="value", param2="value")
```

**Response**:
```json
{
  "result": "..."
}
```

**Step 2: [Second Action]**
[Continue pattern]

**Output to User**:
```
[What user receives]
```

---

## Example 2: [Another Scenario]

[Similar structure]

---

## Example 3: [Edge Case]

[Similar structure]
```

**Step 5: Create reference.md (if using MCP tools)**

```markdown
# My Skill Name - Tool Reference

## Tools Used

### tool_name_1

**Purpose**: [What this tool does]

**Parameters**:
- `param1` (string, required): [Description]
- `param2` (boolean, optional): [Description]

**Returns**: [Return type and structure]

**Example**:
```json
{
  "param1": "value",
  "param2": true
}
```

**Response**:
```json
{
  "result": "..."
}
```

---

### tool_name_2

[Similar structure]
```

**Step 6: Test Activation**

See [Testing and Debugging Skills](#testing-and-debugging-skills) section.

---

### Supporting Files Organization

```
src/main/resources/skills/my-skill-name/
├── SKILL.md              # Main Skill definition (REQUIRED)
├── examples.md           # Usage examples (RECOMMENDED)
├── reference.md          # Tool reference if using MCP tools (RECOMMENDED)
├── README.md             # Overview and usage guide (OPTIONAL)
├── templates/            # File templates for generation Skills (OPTIONAL)
│   ├── template1.md
│   └── template2.json
├── scripts/              # Helper scripts for complex Skills (OPTIONAL)
│   └── helper.sh
└── troubleshooting.md    # Common issues and solutions (OPTIONAL)
```

**File Purposes**:

- **SKILL.md** (required): Main Skill definition with YAML frontmatter and instructions
- **examples.md** (recommended): 3-5 concrete usage scenarios showing realistic inputs and outputs
- **reference.md** (recommended for MCP tools): Detailed tool documentation with parameters and examples
- **README.md** (optional): High-level overview, when to use, related Skills
- **templates/** (optional): File templates for Skills that generate files
- **scripts/** (optional): Helper scripts for complex operations
- **troubleshooting.md** (optional): Common problems and solutions specific to this Skill

**Best Practices**:
- Keep SKILL.md focused - move detailed examples to examples.md
- Use reference.md to document MCP tool parameters (reduces SKILL.md size)
- Include at least 3 examples in examples.md covering common cases
- Add troubleshooting.md if Skill has common failure modes

---

## Integration with Hooks and Subagents

Skills work seamlessly with both Claude Code hooks and subagent orchestration.

### Skills + Hooks

**Pattern**: Skills trigger operations, hooks automate side effects

**Example 1: Auto-Commit on Task Completion**

```
Task Management Skill
→ Completes task: manage_container(operation="setStatus", containerType="task", id="T1", status="completed")

PostToolUse Hook (automatic)
→ Watches for: mcp__task-orchestrator__manage_container where operation="setStatus" and status="completed"
→ Action: Create git commit with task title
→ Executes: git add -A && git commit -m "feat: [task title]"

Result: Task completed AND changes committed automatically
```

**Skills don't trigger hooks manually** - hooks observe tool calls and activate automatically.

**Example 2: Quality Gate Before Feature Completion**

```
Feature Management Skill
→ Attempts to complete feature: manage_container(operation="setStatus", containerType="feature", id="F1", status="completed")

PostToolUse Hook (automatic)
→ Watches for: mcp__task-orchestrator__manage_container where containerType="feature" and operation="setStatus" and status="completed"
→ Action: Run test suite
→ If tests fail: Return {"decision": "block", "reason": "Tests failing"}
→ If tests pass: Allow operation to proceed

Result: Feature cannot be completed until tests pass
```

---

### Skills + Subagents

**Pattern**: Skills coordinate high-level workflow, subagents do complex work

**Example: Feature with Multiple Tasks**

```
Level 0: Orchestrator (You)
├─ Feature Management Skill
│  └─ Recommends: "Work on T1: Database Schema"
│
Level 1: Task Manager Subagent
├─ Task Management Skill
│  └─ Routes to: Database Engineer specialist
│
Level 2: Database Engineer Subagent
└─ Implements schema
   Creates tests
   Reports completion

Back to Level 1: Task Manager
├─ Task Management Skill
│  └─ Completes task: Creates Summary, marks complete
│
Back to Level 0: Orchestrator
└─ Receives: "T1 completed" (2-3 sentences)
```

**Benefits**:
- **Skills reduce coordination overhead**: 300 tokens vs 1400 tokens per coordination step
- **Subagents handle complexity**: Full reasoning for implementation
- **Clear separation**: Skills = coordination, Subagents = execution

---

### Skills + Templates

**Pattern**: Templates structure the WORK, Skills coordinate execution

**Example: Create Task with Templates**

```
Step 1: Create task with templates (direct MCP tool)
manage_container(
  operation="create",
  containerType="task",
  title="Implement user authentication",
  templateIds=["technical-approach", "testing-strategy"]
)

→ Creates task with sections:
  - Requirements (from template)
  - Technical Approach (from template)
  - Testing Strategy (from template)

Step 2: Route task (Skill)
Task Management Skill
→ Reads sections from templates
→ Recommends Backend Engineer
→ Includes template context in recommendation

Step 3: Execute work (Subagent)
Backend Engineer
→ Reads "Technical Approach" section (created by template)
→ Implements according to approach
→ Reads "Testing Strategy" section (created by template)
→ Writes tests according to strategy

Step 4: Complete task (Skill)
Task Management Skill
→ Creates Summary section
→ Marks complete
```

**Templates create the WHAT**, **Skills coordinate the WHO/WHEN**, **Subagents do the HOW**.

---

## Testing and Debugging Skills

### Testing Activation

#### Method 1: Natural Language Testing

```
User: "[Phrase that should trigger Skill]"
```

**Check**:
- Did Claude invoke your Skill?
- If not, description keywords may need adjustment

**Examples**:

```
# Testing Task Management Skill
User: "Complete task T123"
→ Should activate Task Management Skill

User: "What specialist should work on this task?"
→ Should activate Task Management Skill

User: "Implement the authentication API"
→ Should NOT activate Task Management Skill (implementation, not coordination)
```

#### Method 2: Explicit Skill Request

```
User: "Use the [Skill Name] Skill to [action]"
```

**Guarantees** Skill activation for testing.

---

### Testing Tool Access

Verify that `allowed-tools` restrictions work:

```
# Create a test Skill with restricted tools
---
name: Test Skill
description: Test Skill for validation
allowed-tools: Read, mcp__task-orchestrator__get_task
---

# Test Skill

Try to use Write tool (should fail):
1. Attempt: Write("test.txt", "content")
2. Expected: Error about tool not allowed
```

---

### Debugging Activation Issues

**Problem**: Skill doesn't activate when expected

**Solutions**:

1. **Check description keywords**:
   - Does description include words from user's request?
   - Add more activation phrases

2. **Make description more specific**:
   ```yaml
   # Too vague
   description: Helps with tasks

   # Better
   description: Coordinate task lifecycle including routing, status updates, and completion. Use when managing tasks, completing tasks, or checking task status.
   ```

3. **Check description length**:
   - Keep under 200 characters for optimal activation
   - If longer, split into multiple Skills

4. **Avoid competing Skills**:
   - If multiple Skills have overlapping descriptions, Claude may choose wrong one
   - Make descriptions distinct

---

### Debugging Tool Call Failures

**Problem**: Skill makes tool call that fails

**Solutions**:

1. **Check tool name spelling**:
   ```yaml
   # Wrong
   allowed-tools: mcp__task-orchestrator__get_tsk

   # Correct
   allowed-tools: mcp__task-orchestrator__get_task
   ```

2. **Verify tool is in allowed-tools**:
   - Error: "Tool [name] not allowed"
   - Solution: Add to allowed-tools list

3. **Check parameter format**:
   - Use correct parameter names
   - Provide required parameters
   - Use correct data types

4. **Review tool documentation**:
   - Check `reference.md` for parameter details
   - Consult API reference for MCP tools

---

### Debugging Unexpected Behavior

**Problem**: Skill activates but does wrong thing

**Solutions**:

1. **Review Skill instructions**:
   - Are instructions clear?
   - Is workflow logical?
   - Are error cases handled?

2. **Add defensive checks**:
   ```markdown
   ## Workflow

   1. **Get task details**
      - If task not found: Report error and stop
      - If task already completed: Report status and stop
      - Otherwise: Continue to step 2
   ```

3. **Improve response formats**:
   - Provide clear templates for responses
   - Include examples in Skill instructions

4. **Add logging (for complex Skills)**:
   - Return intermediate results to user
   - Help diagnose where things go wrong

---

### Testing Checklist

Before considering a Skill complete:

- [ ] Skill activates with natural language (multiple phrases tested)
- [ ] Skill activates with explicit request
- [ ] Tool restrictions work (verified with disallowed tool)
- [ ] All workflows complete successfully
- [ ] Error cases handled gracefully
- [ ] Response format matches examples
- [ ] Supporting documentation created (examples.md)
- [ ] Integration tested with related Skills (if applicable)
- [ ] Integration tested with hooks (if applicable)
- [ ] Token savings measured (if replacing subagent workflow)

---

## Token Efficiency Analysis

### How Token Savings Are Calculated

**Baseline**: Subagent approach
**Comparison**: Skill approach

**Example: Feature Management**

#### Subagent Approach (Feature Manager START)

```
Token Breakdown:
- Subagent system prompt: ~800 tokens
- Agent guidance: ~400 tokens
- Task context passed: ~200 tokens
Total: ~1400 tokens

Operations:
- query_container(operation="get", containerType="feature", ...)
- get_next_task()
- Return recommendation

Total Cost: ~1400 tokens
```

#### Skill Approach (Feature Management Skill)

```
Token Breakdown:
- Progressive disclosure: ~250 tokens (only "Recommend Next Task" section)
- Tool context: ~50 tokens
Total: ~300 tokens

Operations:
- query_container(operation="get", containerType="feature", ...)
- get_next_task()
- Return recommendation

Total Cost: ~300 tokens
```

**Savings**: 1400 - 300 = 1100 tokens (78% reduction)

---

### Measured Token Savings by Skill

#### Task Management Skill

| Operation | Subagent Tokens | Skill Tokens | Savings |
|-----------|----------------|--------------|---------|
| Route Task (START) | 1300 | 300 | 77% (1000 tokens) |
| Complete Task (END) | 1500 | 600 | 60% (900 tokens) |
| Check Dependencies | 1200 | 350 | 71% (850 tokens) |
| Update Status | 1100 | 250 | 77% (850 tokens) |

**Average Savings**: 71% (875 tokens per operation)

---

#### Feature Management Skill

| Operation | Subagent Tokens | Skill Tokens | Savings |
|-----------|----------------|--------------|---------|
| Recommend Next Task (START) | 1400 | 300 | 78% (1100 tokens) |
| Check Progress | 1200 | 250 | 79% (950 tokens) |
| Complete Feature (END) | 1700 | 600 | 65% (1100 tokens) |
| List Blocked Tasks | 1300 | 400 | 69% (900 tokens) |

**Average Savings**: 73% (1013 tokens per operation)

---

#### Dependency Analysis Skill

| Operation | Subagent Tokens | Skill Tokens | Savings |
|-----------|----------------|--------------|---------|
| Find Blocked Tasks | 1400 | 350 | 75% (1050 tokens) |
| Analyze Chains | 1600 | 500 | 69% (1100 tokens) |
| Identify Bottlenecks | 1500 | 450 | 70% (1050 tokens) |
| Recommend Resolution | 1550 | 400 | 74% (1150 tokens) |

**Average Savings**: 72% (1088 tokens per operation)

---

### Complete Feature Workflow Comparison

**Scenario**: Feature with 5 tasks, dependencies between tasks

#### Traditional Subagent Approach

```
1. Feature Manager START: 1400 tokens
   → Recommend T1

2. Task Manager START (T1): 1300 tokens
   → Route to Database Engineer

3. [Database Engineer implements T1]: 2500 tokens
   → Report completion

4. Task Manager END (T1): 1500 tokens
   → Complete T1

5. Feature Manager (next task): 1400 tokens
   → Recommend T2

6. Task Manager START (T2): 1300 tokens
   → Route to Backend Engineer

7. [Backend Engineer implements T2]: 3000 tokens
   → Report completion

8. Task Manager END (T2): 1500 tokens
   → Complete T2

[Continue for T3, T4, T5...]

Total Coordination Overhead (5 tasks):
- Feature coordination: 1400 × 6 = 8400 tokens
- Task coordination: (1300 + 1500) × 5 = 14000 tokens
- Implementation: 13500 tokens
Total: 35900 tokens
```

#### Skills + Subagent Approach

```
1. Feature Management Skill: 300 tokens
   → Recommend T1

2. Task Management Skill: 300 tokens
   → Route to Database Engineer

3. [Database Engineer implements T1]: 2500 tokens
   → Report completion

4. Task Management Skill: 600 tokens
   → Complete T1

5. Feature Management Skill: 300 tokens
   → Recommend T2

6. Task Management Skill: 300 tokens
   → Route to Backend Engineer

7. [Backend Engineer implements T2]: 3000 tokens
   → Report completion

8. Task Management Skill: 600 tokens
   → Complete T2

[Continue for T3, T4, T5...]

Total Coordination Overhead (5 tasks):
- Feature coordination: 300 × 6 = 1800 tokens
- Task coordination: (300 + 600) × 5 = 4500 tokens
- Implementation: 13500 tokens
Total: 19800 tokens
```

**Total Savings**: 35900 - 19800 = 16100 tokens (45% reduction overall)

**Coordination Savings**: 22400 - 6300 = 16100 tokens (72% reduction in coordination overhead)

---

### Why Skills Achieve 60-82% Savings

1. **Progressive Disclosure**: Load only relevant section, not full Skill (~250-600 tokens vs 1400-1700)
2. **No Subagent Overhead**: Direct invocation, no launch ceremony
3. **Tool Restriction**: Smaller context from fewer available tools
4. **Focused Instructions**: Only what's needed for specific operation
5. **No Context Accumulation**: Each invocation is independent

---

### When Savings Are Greatest

**Highest savings** (75-82%):
- Simple operations (status checks, queries)
- Repetitive workflows (task routing, completion)
- Read-only operations (dependency analysis)

**Moderate savings** (60-70%):
- Complex workflows (feature completion with validation)
- Operations with multiple steps
- Operations requiring substantial context

**Lowest savings** (still 60%+):
- Operations requiring extensive error handling
- Operations needing multiple tool calls
- Operations with complex response formatting

---

## Best Practices

### Skill Design

1. **Single Responsibility**: One Skill = One clear purpose
   - ✅ Good: "Task Management" (task lifecycle operations)
   - ❌ Bad: "Project Everything" (tasks, features, reports, metrics)

2. **Minimal Tools**: Restrict to only what's necessary
   - ✅ Good: `allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status`
   - ❌ Bad: `allowed-tools: Read, Write, Bash, Grep, Glob, [all 50 MCP tools]`

3. **Clear Activation**: Description triggers on specific keywords
   - ✅ Good: "Use when managing task lifecycle, marking tasks complete, or checking dependencies"
   - ❌ Bad: "Helps with tasks"

4. **Defensive Instructions**: Handle error cases explicitly
   ```markdown
   ## Workflow
   1. Get task: query_container(operation="get", containerType="task", id=id)
      - If not found: Report "Task [id] not found" and stop
      - If already completed: Report "Task already complete" and stop
      - Otherwise: Continue to step 2
   ```

5. **Concrete Examples**: Provide realistic usage scenarios
   - Include in examples.md
   - Show actual tool calls
   - Show expected responses

---

### Tool Access Control

1. **Read-Only When Possible**: Analysis Skills shouldn't write
   ```yaml
   # Analysis Skill
   allowed-tools: mcp__task-orchestrator__get_blocked_tasks, mcp__task-orchestrator__get_task_dependencies
   # No update_task, set_status, etc.
   ```

2. **File Access**: Only if generating/reading files
   ```yaml
   # Report Generator needs Write
   allowed-tools: Read, Write, mcp__task-orchestrator__search_tasks

   # Coordination Skill doesn't need file access
   allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status
   ```

3. **Bash Access**: Only for external integrations
   ```yaml
   # Integration Skill needs Bash for API calls
   allowed-tools: Bash, Read, Write, mcp__task-orchestrator__get_task

   # Standard Skills don't need Bash
   allowed-tools: mcp__task-orchestrator__get_task
   ```

---

### Documentation Standards

1. **Required Files**:
   - SKILL.md with proper structure
   - examples.md with 3+ scenarios

2. **Recommended Files**:
   - reference.md for MCP tools
   - README.md for overview

3. **Optional Files**:
   - troubleshooting.md for common issues
   - templates/ for file generation Skills
   - scripts/ for complex operations

4. **Keep Instructions Focused**:
   - SKILL.md: Workflows and patterns
   - examples.md: Concrete usage
   - reference.md: Tool documentation
   - troubleshooting.md: Error handling

---

### Activation and Discovery

1. **Keyword-Rich Descriptions**: Include verbs and nouns
   ```yaml
   # Good
   description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.

   # Bad
   description: Task helper
   ```

2. **Multiple Activation Phrases**: Cover different user phrasings
   - "Complete task T123"
   - "Mark task T123 as done"
   - "Finish task T123"
   - All should activate Task Management Skill

3. **Avoid Overlapping Skills**: Make descriptions distinct
   - Don't have two Skills with similar descriptions
   - Users won't know which one will activate

4. **Test Activation**: Try multiple natural language phrases

---

### Performance Optimization

1. **Minimize Loaded Content**: Keep instructions concise
   - Progressive disclosure works best with well-structured sections
   - Each section should be self-contained

2. **Avoid Redundancy**: Don't repeat information
   - Examples in examples.md, not SKILL.md
   - Tool reference in reference.md, not SKILL.md

3. **Use Sections Wisely**: Organize by capability
   ```markdown
   ## Core Capabilities

   ### 1. Route Task
   [Instructions for routing]

   ### 2. Complete Task
   [Instructions for completion]

   ### 3. Check Dependencies
   [Instructions for dependency checking]
   ```
   Progressive disclosure loads only the relevant section.

---

### Integration Patterns

1. **Skills + Skills**: Chain for complex workflows
   ```
   Feature Management Skill → Task Management Skill → Return to user
   ```

2. **Skills + Subagents**: Coordinate with specialists
   ```
   Task Management Skill → Launch Backend Engineer → Task Management Skill completes
   ```

3. **Skills + Hooks**: Automate side effects
   ```
   Task Management Skill completes task → Hook auto-commits changes
   ```

4. **Skills + Templates**: Structure + coordination
   ```
   Templates create sections → Task Management Skill routes to specialist → Specialist reads template sections
   ```

---

### Error Handling

1. **Validate Inputs**: Check before acting
   ```markdown
   1. Get task: query_container(operation="get", containerType="task", id=id)
      - If task not found: Report error and stop
      - If task has invalid status: Report error and suggest fix
   ```

2. **Graceful Degradation**: Handle missing data
   ```markdown
   2. Get dependencies: get_task_dependencies(id)
      - If no dependencies: Continue without dependency check
      - If dependencies incomplete: Report blockers
   ```

3. **Clear Error Messages**: Tell user what to do
   ```markdown
   Error: Task T123 is blocked by 2 incomplete dependencies.

   Action Required:
   1. Complete T100 (Database schema)
   2. Complete T101 (API endpoints)

   Then retry this task.
   ```

---

### Maintenance and Updates

1. **Version Control**: Track changes to Skills
   - Commit SKILL.md changes with descriptive messages
   - Document breaking changes in commit

2. **Backward Compatibility**: Don't break existing usage
   - Add new capabilities, don't remove old ones
   - If removing capability, provide migration guide

3. **Documentation Updates**: Keep examples current
   - Update examples.md when workflows change
   - Add new examples for new capabilities

4. **Testing After Changes**: Verify activation still works
   - Test with original activation phrases
   - Test with new capability phrases

---

## Troubleshooting

### Skill Doesn't Activate

**Problem**: User says phrase that should trigger Skill, but it doesn't activate

**Diagnosis**:
1. Check description keywords
2. Check description length (>200 chars may reduce activation reliability)
3. Check for competing Skills with similar descriptions

**Solutions**:
- Add more activation keywords to description
- Make description more specific
- Shorten description if too long
- Disambiguate from other Skills

**Example Fix**:
```yaml
# Before
description: Helps manage tasks

# After
description: Coordinate task workflows including routing tasks to specialists, updating task status, and completing tasks. Use when managing task lifecycle, marking tasks complete, or checking task details.
```

---

### Skill Activates But Wrong Operation

**Problem**: Skill activates but performs wrong action

**Diagnosis**:
1. Review Skill instructions for ambiguity
2. Check if multiple capabilities share similar triggers

**Solutions**:
- Clarify instructions for each capability
- Add explicit conditions for each workflow
- Provide examples of when to use each capability

**Example Fix**:
```markdown
# Before (ambiguous)
## Workflow
1. Get task
2. Do something
3. Return result

# After (clear)
## Core Capabilities

### 1. Route Task (Use when: "start work", "who should handle")
1. Get task: query_container(operation="get", containerType="task", id=id, includeSections=true)
2. Get recommendation: recommend_agent(taskId)
3. Return specialist with context

### 2. Complete Task (Use when: "complete", "finish", "mark done")
1. Get task: query_container(operation="get", containerType="task", id=id, includeSections=true)
2. Create Summary: manage_sections(operation="add", ...)
3. Mark complete: manage_container(operation="setStatus", containerType="task", id=id, status="completed")
4. Return confirmation
```

---

### Tool Access Denied

**Problem**: Skill tries to use tool but gets "tool not allowed" error

**Diagnosis**:
1. Check `allowed-tools` in YAML frontmatter
2. Verify tool name spelling

**Solutions**:
- Add tool to allowed-tools list
- Fix tool name spelling
- Use different tool if access shouldn't be granted

**Example Fix**:
```yaml
# Before
allowed-tools: mcp__task-orchestrator__get_task

# After (add missing tool)
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__set_status
```

---

### Tool Call Fails

**Problem**: Skill calls tool but gets error response

**Diagnosis**:
1. Check parameter names (spelling, case)
2. Check required parameters provided
3. Check parameter data types
4. Review tool documentation

**Solutions**:
- Fix parameter names
- Provide missing required parameters
- Convert parameter to correct type
- Consult reference.md for correct usage

**Example Fix**:
```markdown
# Before (wrong parameter name)
query_container(operation="get", containerType="task", taskId="123", includeSections=true)

# After (correct parameter name)
query_container(operation="get", containerType="task", id="123", includeSections=true)
```

---

### Unexpected Response Format

**Problem**: Skill returns data in wrong format

**Diagnosis**:
1. Review response format instructions
2. Check if examples show desired format
3. Verify tool response is being formatted correctly

**Solutions**:
- Add explicit response format template
- Provide clear examples in SKILL.md
- Show exact formatting in instructions

**Example Fix**:
```markdown
# Before (vague)
4. Return recommendation to user

# After (specific template)
4. Return recommendation in this format:

**Response Format**:
```
Recommend [Specialist Name] for this task.

Task: [task title]
Complexity: [X]/10

Key Requirements:
- [requirement 1]
- [requirement 2]

Launch: Task [Specialist Name] START with taskId=[id]
```
```

---

### Skill Activates Too Broadly

**Problem**: Skill activates when it shouldn't

**Diagnosis**:
1. Check if description is too generic
2. Look for ambiguous keywords

**Solutions**:
- Make description more specific
- Add context about when NOT to use
- Split into multiple focused Skills

**Example Fix**:
```yaml
# Before (too broad)
description: Work with tasks and features

# After (specific)
description: Coordinate feature workflows including recommending next tasks, checking progress, and completing features. Use when managing feature lifecycle, not for individual task implementation.
```

---

### Progressive Disclosure Not Working

**Problem**: Entire Skill loads instead of just relevant section

**Diagnosis**:
1. Check Skill structure - are capabilities clearly separated?
2. Verify sections use clear headings

**Solutions**:
- Organize capabilities under clear H3 headings (###)
- Each capability should be self-contained section
- Use consistent heading structure

**Example Fix**:
```markdown
# Before (monolithic)
## Workflow
[All capabilities mixed together in one section]

# After (structured)
## Core Capabilities

### 1. Route Task
[Self-contained instructions for routing]

### 2. Complete Task
[Self-contained instructions for completion]

### 3. Check Dependencies
[Self-contained instructions for dependencies]
```

---

### Integration Issues

#### Skill + Hook Not Working Together

**Problem**: Hook doesn't trigger when Skill completes operation

**Diagnosis**:
1. Check hook matcher matches tool name
2. Verify hook is in settings.local.json (not settings.example.json)
3. Check hook script executes successfully

**Solutions**:
- Fix hook matcher to match exact tool name
- Copy settings.example.json to settings.local.json
- Test hook script manually
- Check hook script has execute permissions (Unix/Mac)

---

#### Skill + Subagent Coordination Issue

**Problem**: Skill launches subagent but handoff fails

**Diagnosis**:
1. Check subagent definition exists in `.claude/agents/`
2. Verify Skill returns correct subagent launch instruction
3. Check subagent has access to needed context

**Solutions**:
- Install Task Orchestrator plugin via Claude Code plugin marketplace to create agent definitions
- Update Skill to include launch instruction format
- Ensure task/feature has necessary sections for subagent

---

### Performance Issues

**Problem**: Skill is slow or uses excessive tokens

**Diagnosis**:
1. Check if SKILL.md is too long
2. Look for redundant content
3. Verify examples are in examples.md, not SKILL.md

**Solutions**:
- Move detailed examples to examples.md
- Move tool documentation to reference.md
- Keep SKILL.md focused on workflows
- Remove redundant instructions

---

### Documentation Issues

**Problem**: Users don't understand how to use Skill

**Diagnosis**:
1. Check if examples.md exists
2. Verify examples are realistic
3. Review if instructions are clear

**Solutions**:
- Create examples.md with 3+ scenarios
- Show realistic user requests
- Include expected inputs and outputs
- Add troubleshooting.md for common issues

---

### Getting Help

If you can't resolve an issue:

1. **Check existing Skills**: Review working examples
   - `src/main/resources/skills/task-management/`
   - `src/main/resources/skills/feature-management/`
   - `src/main/resources/skills/dependency-analysis/`

2. **Use Skill Builder**: Ask for help creating/fixing Skill
   ```
   User: "Help me fix my Skill that isn't activating correctly"
   ```

3. **Review documentation**:
   - This guide (skills-guide.md)
   - skill-templates.md for patterns
   - Individual Skill examples.md files

4. **Check Claude Code logs**: Look for error messages or activation issues

5. **Test systematically**:
   - Activation with multiple phrases
   - Tool access with each allowed tool
   - Error cases and edge cases
   - Integration with hooks/subagents

---

## Related Documentation

- **[Agent Architecture](agent-architecture.md)** - Agent coordination and hybrid architecture guide
- **[Templates](templates.md)** - Template system for structuring work
- **[Workflow Prompts](workflow-prompts.md)** - Universal workflow automation (all MCP clients)
- **[API Reference](api-reference.md)** - Complete MCP tools documentation
- **[AI Guidelines](ai-guidelines.md)** - How AI uses Task Orchestrator

---

## Summary

**Skills are lightweight, focused capabilities** that achieve 60-82% token savings through progressive disclosure:

- **What**: Markdown files with YAML frontmatter defining name, description, allowed-tools
- **When**: Coordination tasks (2-5 tool calls), repetitive workflows, fast responses
- **Why**: 60-82% token reduction vs subagents, no launch overhead, tool restriction
- **How**: Progressive disclosure loads only relevant section, not entire Skill
- **Where**: `src/main/resources/skills/[skill-name]/SKILL.md`

**5 Built-in Skills**:
1. Task Management (77% savings)
2. Feature Management (60-82% savings)
3. Dependency Analysis (65-75% savings)
4. Skill Builder (creation assistant)
5. Hook Builder (automation assistant)

**Create Custom Skills**:
- Use Skill Builder for guided creation
- Choose from 7 templates for common patterns
- Follow YAML frontmatter structure
- Test activation and tool access
- Document with examples.md and reference.md

**Integration**:
- Works with hooks for automation
- Coordinates with subagents for complex work
- Complements templates for structured workflows

**Token Efficiency**: Average 72% reduction in coordination overhead vs subagent-only approach.
