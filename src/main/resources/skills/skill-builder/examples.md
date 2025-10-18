# Skill Builder - Examples

This document provides concrete examples of using the Skill Builder to create custom Skills.

---

## Example 1: Creating a Documentation Generator Skill

**User Request:**
"Help me create a Skill that generates API documentation from Kotlin code"

**Skill Builder Actions:**

1. **Asks clarifying questions:**
   - "Will this read Kotlin source files?" → Yes
   - "Should it write markdown documentation?" → Yes
   - "Does it need to update Task Orchestrator tasks?" → Yes
   - "Keywords to activate?" → "document API", "generate API docs", "create API documentation"

2. **Generates SKILL.md:**
```yaml
---
name: API Documentation Generator
description: Generate API documentation from Kotlin code. Use when documenting APIs, creating markdown docs from code, or maintaining API references.
allowed-tools: Read, Write, Grep, mcp__task-orchestrator__update_section_text, mcp__task-orchestrator__add_section
---

# API Documentation Generator

You are a documentation specialist focused on generating API documentation from Kotlin source code.

## What You Do

Read Kotlin API code, extract endpoints, parameters, and responses, then generate comprehensive markdown documentation.

## Workflow

1. **Find API files**: Use Grep to locate controller/API files
2. **Read code**: Read each API file with Read tool
3. **Extract metadata**: Parse annotations (@GetMapping, @PostMapping, etc.)
4. **Generate docs**: Create markdown with endpoints, parameters, responses
5. **Update task**: Add documentation section to task with add_section

## Return to user

Brief summary: What APIs were documented, where docs were saved, task section ID.
```

3. **Creates examples.md** with realistic scenarios

4. **Creates reference.md** documenting the MCP tools

**User Receives:**
```
Created "API Documentation Generator" Skill in .claude/skills/api-doc-generator/. This Skill reads Kotlin controller files and generates markdown API documentation. Test by saying "Document the authentication API" and the Skill should activate.

Files created:
- .claude/skills/api-doc-generator/SKILL.md
- .claude/skills/api-doc-generator/examples.md
- .claude/skills/api-doc-generator/reference.md
```

---

## Example 2: Creating a Test Coverage Analyzer Skill

**User Request:**
"I want a Skill that analyzes test coverage and reports gaps"

**Skill Builder Actions:**

1. **Asks clarifying questions:**
   - "Run tests to get coverage?" → Yes, use Bash
   - "Read existing reports or generate new?" → Generate new with ./gradlew test
   - "Update tasks with findings?" → Yes, add coverage section
   - "Keywords?" → "analyze test coverage", "check coverage", "coverage report"

2. **Generates SKILL.md:**
```yaml
---
name: Test Coverage Analyzer
description: Analyze test coverage and identify gaps. Use when checking test coverage, finding untested code, or generating coverage reports.
allowed-tools: Bash, Read, Grep, Write, mcp__task-orchestrator__add_section
---

# Test Coverage Analyzer

You are a quality assurance specialist focused on test coverage analysis.

## What You Do

Run test suite with coverage, parse reports, identify untested code, and provide actionable recommendations.

## Workflow

1. **Run tests with coverage**: `./gradlew test jacocoTestReport`
2. **Read coverage report**: Find and parse build/reports/jacoco/test/html/index.html
3. **Identify gaps**: Find classes/methods with <70% coverage
4. **Generate report**: Create markdown report with findings
5. **Update task**: Add coverage analysis section if task exists

## Return to user

Coverage percentage, list of files needing tests, report location.
```

3. **Creates supporting files**

**User Receives:**
```
Created "Test Coverage Analyzer" Skill in .claude/skills/test-coverage/. This Skill runs tests with coverage analysis and identifies untested code. Test by saying "Check test coverage for the auth module".

Files created:
- .claude/skills/test-coverage/SKILL.md
- .claude/skills/test-coverage/examples.md
```

---

## Example 3: Creating a Jira Integration Skill

**User Request:**
"Create a Skill to sync Task Orchestrator tasks with Jira"

**Skill Builder Actions:**

1. **Asks clarifying questions:**
   - "Export tasks to Jira or import from Jira?" → Export (one-way sync)
   - "Use Jira API directly?" → Yes, with curl in Bash
   - "Which task fields to sync?" → Title, description, status, assignee
   - "Keywords?" → "export to Jira", "create Jira issue", "sync with Jira"

2. **Generates SKILL.md:**
```yaml
---
name: Jira Task Exporter
description: Export Task Orchestrator tasks to Jira issues. Use when creating Jira issues from tasks, syncing with Jira, or exporting task data.
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__update_task, mcp__task-orchestrator__add_section, Bash, Read
---

# Jira Task Exporter

You are an integration specialist focused on syncing Task Orchestrator with Jira.

## What You Do

Read tasks from Task Orchestrator, create corresponding Jira issues via REST API, and update tasks with Jira issue keys.

## Workflow

1. **Read task**: Get full task details with get_task
2. **Map fields**: Convert task to Jira issue format (JSON)
3. **Create issue**: Use Bash to curl Jira REST API
4. **Parse response**: Extract Jira issue key
5. **Update task**: Add Jira issue key to task tags or summary

## Configuration Required

User must provide in .env or config:
- JIRA_URL: Jira instance URL
- JIRA_TOKEN: API token for authentication
- JIRA_PROJECT: Default project key

## Return to user

Jira issue key, URL to view issue, confirmation of sync.
```

3. **Creates examples.md** with curl commands and API payloads

4. **Creates configuration guide** for setting up Jira credentials

**User Receives:**
```
Created "Jira Task Exporter" Skill in .claude/skills/jira-export/. This Skill exports Task Orchestrator tasks to Jira issues. Requires JIRA_URL, JIRA_TOKEN, and JIRA_PROJECT environment variables. Test by saying "Export task T123 to Jira".

Files created:
- .claude/skills/jira-export/SKILL.md
- .claude/skills/jira-export/examples.md
- .claude/skills/jira-export/config-guide.md

Next steps:
1. Set up environment variables in .env
2. Test connection: curl $JIRA_URL/rest/api/2/myself
3. Try exporting a test task
```

---

## Example 4: Creating a Git Workflow Skill

**User Request:**
"I want to automate my git workflow: create branch, commit, push, open PR"

**Skill Builder Actions:**

1. **Recognizes this might be better as a hook**:
   - "Hooks can trigger on task completion automatically"
   - "Would you like a hook instead, or do you want manual control?"
   - User: "Manual control, I'll decide when to run it"

2. **Asks clarifying questions:**
   - "Branch naming pattern?" → feature/{task-id}-{task-title-slug}
   - "Commit message format?" → Task title with task ID
   - "Auto-push or ask first?" → Ask first
   - "Open PR automatically?" → No, just prepare PR description
   - "Keywords?" → "create git workflow", "prepare PR", "git commit and push"

3. **Generates SKILL.md:**
```yaml
---
name: Git Workflow Automation
description: Automate git workflow including branch creation, commits, and PR preparation. Use when creating feature branches, preparing commits, or setting up PRs.
allowed-tools: Bash, Read, Write, mcp__task-orchestrator__get_task, mcp__task-orchestrator__get_sections
---

# Git Workflow Automation

You are a git automation specialist focused on streamlining development workflows.

## What You Do

Automate common git operations: create feature branches from tasks, commit with proper messages, and prepare PR descriptions.

## Workflow

1. **Read task details**: Get task title, summary, tags
2. **Create branch**: `git checkout -b feature/{task-id}-{slug}`
3. **Stage changes**: `git add -A`
4. **Commit**: Use task title as commit message, task ID in body
5. **Ask user**: "Push to remote? (yes/no)"
6. **If yes, push**: `git push -u origin {branch-name}`
7. **Generate PR description**: Use task summary and acceptance criteria

## Return to user

Branch name, commit SHA, PR description (for user to manually create PR).
```

4. **Creates examples.md** with git command sequences

**User Receives:**
```
Created "Git Workflow Automation" Skill in .claude/skills/git-workflow/. This Skill automates branch creation, commits, and PR preparation for tasks. Test by saying "Create git workflow for task T456".

Files created:
- .claude/skills/git-workflow/SKILL.md
- .claude/skills/git-workflow/examples.md

Note: For fully automatic git commits on task completion, consider creating a Claude Code hook instead (see .claude/hooks/task-complete-commit.sh example).
```

---

## Example 5: When a Subagent is Better

**User Request:**
"Create a Skill that designs database schemas based on requirements"

**Skill Builder Response:**

```
This sounds like it requires a Subagent rather than a Skill. Here's why:

**Complexity Indicators:**
- Database schema design requires architectural reasoning
- Need to analyze requirements and make design decisions
- Multiple iterations likely needed
- Context from requirements documents important

**Recommendation:**
Create a "Database Architect" subagent instead.

**Would you like me to help you create a subagent definition?**

Subagents are better when:
- Complex reasoning required
- Code generation involved
- Need conversation history
- Multi-step design process

Skills are better when:
- Simple coordination (2-5 tool calls)
- Repetitive workflows
- Fast responses needed
- Clear, deterministic logic
```

**User:** "Yes, help me create a subagent"

**Skill Builder then guides subagent creation** (different workflow, creates .claude/agents/ file)

---

## Example 6: Creating a Migration Helper Skill

**User Request:**
"Help me create a Skill that helps migrate tasks between features"

**Skill Builder Actions:**

1. **Asks clarifying questions:**
   - "Move tasks one at a time or in batches?" → One at a time
   - "Preserve dependencies?" → Yes, warn if dependencies would break
   - "Update task tags automatically?" → Yes, adopt target feature tags
   - "Keywords?" → "move task to feature", "migrate task", "reassign task to feature"

2. **Generates SKILL.md:**
```yaml
---
name: Task Migration Assistant
description: Move tasks between features while preserving dependencies. Use when reorganizing features, moving tasks, or restructuring project hierarchy.
allowed-tools: mcp__task-orchestrator__get_task, mcp__task-orchestrator__update_task, mcp__task-orchestrator__get_task_dependencies, mcp__task-orchestrator__get_feature
---

# Task Migration Assistant

You are a project organization specialist focused on safely moving tasks between features.

## What You Do

Move tasks from one feature to another while checking dependencies and updating metadata appropriately.

## Workflow

1. **Validate source task**: Get task and confirm current feature
2. **Check dependencies**: Warn if task has dependencies in source feature
3. **Get target feature**: Verify target feature exists and is active
4. **Update task**: Change featureId to target feature
5. **Update tags**: Optionally adopt target feature's tag conventions
6. **Confirm**: Report old feature, new feature, any dependency warnings

## Safety Checks

- Warn if moving a task that other tasks depend on
- Warn if moving a task that depends on tasks in the source feature
- Confirm before proceeding if dependencies exist

## Return to user

Confirmation of move, old/new feature names, any dependency warnings.
```

3. **Creates examples.md** with migration scenarios including dependency edge cases

**User Receives:**
```
Created "Task Migration Assistant" Skill in .claude/skills/task-migration/. This Skill safely moves tasks between features with dependency checking. Test by saying "Move task T789 to feature F123".

Files created:
- .claude/skills/task-migration/SKILL.md
- .claude/skills/task-migration/examples.md
```

---

## Tips for Using These Examples

1. **Copy and adapt**: Use these examples as templates for your own Skills
2. **Adjust scope**: Narrow or expand based on your specific needs
3. **Combine patterns**: Mix coordination + file generation + analysis as needed
4. **Test thoroughly**: Verify activation triggers work with natural language
5. **Iterate**: Refine description keywords if activation is too broad/narrow

## Need More Examples?

See existing Task Orchestrator Skills:
- `.claude/skills/feature-management/` - Feature coordination
- `.claude/skills/task-management/` - Task coordination
- `.claude/skills/dependency-analysis/` - Dependency queries
- `.claude/skills/hook-builder/` - Hook generation
