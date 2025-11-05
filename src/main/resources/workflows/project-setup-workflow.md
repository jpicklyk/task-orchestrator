---
name: project-setup-workflow
description: AI-agnostic guide for setting up Task Orchestrator with existing or new projects
version: "2.1.0"
---

# Project Setup Workflow (AI-Agnostic)

This workflow helps you set up Task Orchestrator for project management, whether you're working with an existing codebase or starting a brand new project. It works with any AI agent by using abstract storage patterns rather than prescribing specific files.

## Version 2.1 Updates

**Breaking Changes from v2.0:**
- ✅ Uses v2.0 consolidated tools (`manage_container`, `query_container`, `query_templates`)
- ✅ Plugin-based Claude Code integration (removed `setup_claude_orchestration` tool)
- ✅ Removed all references to deprecated v1.0 tools
- ✅ Added mandatory template discovery pattern
- ✅ Corrected configuration file names
- ✅ Reorganized step numbering for logical flow

**Migration:** If using v1.0 documentation, update all tool calls to v2.0 syntax.

---

## Understanding Storage Categories

Task Orchestrator uses three storage layers:

**1. Shared Project Documentation** (committed to git):
- Purpose: Team-wide guidance visible to all developers
- Content: Technical implementation, architecture, build commands, development standards
- AI-specific formats: CLAUDE.md, .cursorrules, .github/copilot-instructions.md, .windsurfrules
- Fallback: .ai/PROJECT.md if your AI doesn't have a standard format

**2. Local Developer Memory** (gitignored, never committed):
- Purpose: Developer-specific context and preferences
- Content: Project UUID, personal workflow preferences, local configurations
- AI-specific formats: CLAUDE.local.md, .cursor/local, .ai/local.config
- Why local: Each developer may use different AI tools with different Task Orchestrator project instances

**3. Task Orchestrator Database** (managed by MCP):
- Purpose: Structured work planning and tracking
- Content: Features, tasks, dependencies, progress, templates
- Access: Through MCP tools (query_container, manage_container, etc.)

## Key Principle: AI Agent Agnostic

This workflow provides **what to store** and **why**, but lets your AI agent decide **where** and **how** using its native capabilities.

Your AI agent should:
- Use its preferred file formats and locations
- Store shared docs in git-committed files
- Store local context in gitignored files
- Report where it stored information

## Step 1: Check AI Initialization (Optional but Recommended)

**RECOMMENDED**: Verify AI has been initialized with Task Orchestrator guidelines.

**Check your memory** (CLAUDE.md, .cursorrules, etc.) for "Task Orchestrator - AI Initialization" section:

**If NOT found or uncertain**, use AskUserQuestion:
```
AskUserQuestion(
  questions: [{
    question: "AI initialization recommended before project setup. The initialize_task_orchestrator workflow teaches template discovery patterns, intent recognition, and quality standards. How would you like to proceed?",
    header: "Initialization",
    multiSelect: false,
    options: [
      {
        label: "Initialize First",
        description: "Run initialize_task_orchestrator to teach AI patterns (recommended)"
      },
      {
        label: "Skip Initialization",
        description: "Continue with project setup, use tools directly"
      }
    ]
  }]
)
```

**If user chooses "Initialize First"**: Pause this workflow, run `initialize_task_orchestrator`, then resume here.

**If user chooses "Skip Initialization" or initialization already done**: Continue to Step 2.

---

## Step 2: Initialize Task Orchestrator Configuration (REQUIRED FIRST)

**Before setting up your project**, you MUST initialize Task Orchestrator's core configuration.

### Step 2.1: Run setup_project Tool

**Execute the setup_project tool** to create core Task Orchestrator configuration:

```
setup_project()
```

**What this creates**:
- `.taskorchestrator/` directory
- `.taskorchestrator/config.yaml` - Orchestrator configuration (status workflows, validation rules)
- `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration
- `.taskorchestrator/orchestrator-prompt.txt` - System prompt for Claude Code orchestrator mode (optional)

**This is idempotent**: Safe to run multiple times, skips existing files.

**Verify success**: Tool should report success and list created/skipped files.

### Step 2.2: Claude Code Integration (Optional)

**If using Claude Code**, Skills and Subagents are available via plugin architecture:

**What's Included:**
- **Plugin:** `.claude/plugins/task-orchestrator/` (auto-discovered)
- **Skills:** Available through plugin (feature-orchestration, task-orchestration, dependency-analysis, status-progression, etc.)
- **Subagents:** Available through plugin (Feature Architect, Planning Specialist, Implementation Specialist, etc.)

**No additional setup required** - Plugin is auto-discovered by Claude Code when MCP server is configured in `claude_desktop_config.json`.

**Verify plugin:**
- Skills auto-activate from natural language ("What's next?", "Complete feature")
- Subagents accessible via Task tool
- See plugin documentation for details

**Note:** If user is not using Claude Code, they use Task Orchestrator via direct tool calls only.

---

**After Step 2 completion**, report to user:

```
✅ Task Orchestrator configuration initialized!

Core setup complete:
- Configuration files created in .taskorchestrator/
[If Claude Code:]
- Claude Code plugin integration available
- Skills and Subagents ready to use

Ready to proceed with project setup...
```

---

## Step 3: Detect Scenario

**Determine which setup scenario applies:**

Use file system tools to check:
- Does project documentation exist? (CLAUDE.md, .cursorrules, .github/copilot-instructions.md, .ai/PROJECT.md)
- Does code exist in src/, lib/, or similar directories?

**Scenario A: Existing Codebase**
- Project documentation exists
- Code files present
- Use existing docs as context
- Create Task Orchestrator structure referencing existing architecture

**Scenario B: New Project with Plan**
- No project documentation OR minimal code
- User has a project plan/PRD document
- Extract info from plan → write to shared documentation
- Create Task Orchestrator structure from plan

**Ask user if unclear** (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "What type of project setup are you doing?",
    header: "Scenario",
    multiSelect: false,
    options: [
      {
        label: "Existing Codebase",
        description: "Add Task Orchestrator to project with existing code and documentation"
      },
      {
        label: "New Project",
        description: "Start fresh project with Task Orchestrator from project plan/PRD"
      }
    ]
  }]
)
```

---

## SCENARIO A: Existing Codebase

Use this path when project documentation and code already exist.

### Step 4A: Review Existing Documentation

**Read existing project documentation** to understand the codebase:

Use file system tools to read:
- CLAUDE.md, .cursorrules, .github/copilot-instructions.md, or similar
- README.md for project overview
- Architecture documentation if available

**Extract context**:
- What is the project building?
- What technologies are being used?
- What features/components already exist?

### Step 5A: Check for Existing Projects (REQUIRED)

**Before creating a new project**, search for existing projects:

**Use query_container:**
```javascript
query_container(
  operation="search",
  containerType="project",
  query="[project name from documentation]"
)
```

**If existing project found**, use AskUserQuestion:
```
AskUserQuestion(
  questions: [{
    question: "Found existing project '[name]' (UUID: [uuid], created: [date]). Is this the same project?",
    header: "Duplicate",
    multiSelect: false,
    options: [
      {
        label: "Yes, Same Project",
        description: "Use existing project UUID, skip creation"
      },
      {
        label: "No, Different Project",
        description: "Provide a different name to avoid confusion"
      }
    ]
  }]
)
```

- **If "Yes, Same Project"**:
  - Skip project creation (Step 6A)
  - Use the existing project UUID
  - Jump to Step 10A (save existing UUID to local memory)
  - Report: "Recovered existing project UUID to local memory"

- **If "No, Different Project"**:
  - Ask user to provide a different name to avoid confusion
  - Continue to Step 6A with new name

**If no existing project found**:
- Continue to Step 6A (create new project)

### Step 6A: Create Lightweight Project Entity

**Create minimal project container** in Task Orchestrator:

**Use manage_container:**
```javascript
manage_container(
  operation="create",
  containerType="project",
  name="[Project name from docs]",
  summary="[1-2 sentence description from docs]",
  status="in-development",
  tags="[main-tech-stack,project-type]"
)
```

**Keep it simple**:
- Summary: Just the essence from existing docs
- Tags: Technology stack and type
- Status: Likely "in-development" since code exists
- **DO NOT create project sections** - docs already exist in files

### Step 7A: Discover Available Templates (REQUIRED)

**BEFORE creating features or tasks**, discover available templates:

**Use query_templates:**
```javascript
// For feature templates
query_templates(
  operation="list",
  targetEntityType="FEATURE",
  isEnabled=true
)

// For task templates
query_templates(
  operation="list",
  targetEntityType="TASK",
  isEnabled=true
)
```

**Common Templates:**
- **Tasks:** `task-implementation-workflow`, `technical-approach`, `testing-strategy`, `bug-investigation`, `local-git-branching-workflow`, `github-pr-workflow`
- **Features:** `requirements-specification`, `context-and-background`, `definition-of-done`

**CRITICAL:** Never assume templates exist. Always discover first, then apply by ID.

**Store template IDs** for use in subsequent steps.

### Step 8A: Identify Features from Codebase

**Analyze existing structure** to identify features:

Use file system tools to:
- Read source code structure
- Review existing documentation
- Identify major functional areas

**Ask user to confirm features** (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "Based on the codebase, I identified [count] major features: [list]. Create these in Task Orchestrator?",
    header: "Features",
    multiSelect: false,
    options: [
      {
        label: "Yes, Create All",
        description: "Create features for all identified areas"
      },
      {
        label: "No, Skip",
        description: "I'll create features manually later"
      }
    ]
  }]
)
```

### Step 9A: Create Features

**For each confirmed feature**, use manage_container:

```javascript
manage_container(
  operation="create",
  containerType="feature",
  name="[Feature name]",
  summary="[What it does and current state]",
  status="[planning/in-development/completed based on code]",
  priority="medium",
  projectId="[project-id-from-step-6A]",
  templateIds=["[template-uuid-from-step-7A]"],
  tags="[feature-area,tech-stack]"
)
```

**Minimal templates**:
- Use just "requirements-specification" template ID for existing features
- Can add more detail later if needed

### Step 10A: Create Initial Tasks (Optional)

**Ask user** (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "Would you like to create tasks for planned features, bugs, or improvements?",
    header: "Tasks",
    multiSelect: false,
    options: [
      {
        label: "Yes, Create Tasks",
        description: "Create initial tasks for known upcoming work"
      },
      {
        label: "No, Skip",
        description: "I'll create tasks as needed later"
      }
    ]
  }]
)
```

If "Yes, Create Tasks", create tasks for:
- Planned features not yet implemented
- Known bugs or improvements
- Technical debt items

**Git Detection**: Check for .git directory

**Use manage_container:**
```javascript
manage_container(
  operation="create",
  containerType="task",
  title="[Specific task]",
  summary="[What to do and why]",
  featureId="[feature-id]",
  projectId="[project-id]",
  priority="[high/medium/low]",
  complexity=[3-7],
  templateIds=["[template-uuids-from-step-7A]"],
  tags="task-type-feature,[area]"
)
```

**Template notes**:
- Include "local-git-branching-workflow" template ID if git detected
- Ask about PR workflows if uncertain

### Step 11A: Save Project UUID to Local Memory

**REQUIRED ACTION**: Write project UUID to local developer memory.

**Storage location** - Your AI agent's local memory:
- CLAUDE.local.md (Claude Code)
- .cursor/local (Cursor)
- .ai/local.config (fallback)

**Required format**:
```markdown
## Task Orchestrator - Project Context

Project UUID: [uuid-from-step-6A]
Project Name: [name]
Last synced: YYYY-MM-DD

Quick commands:
- View overview: query_container(operation="overview", containerType="project")
- View project: query_container(operation="get", containerType="project", id="[uuid]")
```

**File Writing Steps**:
1. Check if local config file exists
2. If exists: Check for "Task Orchestrator - Project Context" section
3. If section exists: Update UUID and timestamp
4. If section missing: Append at end
5. If file doesn't exist: Create with this section
6. **Verify**: Read file back to confirm write
7. **Report**: "Saved project UUID to [file-path]"

### Step 12A: Ensure Local Memory is Gitignored

**REQUIRED ACTION**: Verify local storage is gitignored.

**Check .gitignore**:
1. Read .gitignore file
2. Check if your local config file is listed
3. If NOT listed: Add it

**Common entries to add**:
```
# Task Orchestrator local context
CLAUDE.local.md
.cursor/local
.ai/local.config
*.local.md
```

**If .gitignore doesn't exist**: Create it with these entries

**Verify**: Read .gitignore back to confirm

**Report**: "Added [your-local-file] to .gitignore"

### Step 13A: Completion

**Report to user**:
```
✅ Task Orchestrator setup complete for existing codebase!

Project: [name] ([uuid])
UUID saved to: [local-file] (gitignored)
Features created: [count]
Tasks created: [count]

Next steps:
- Run query_container(operation="overview", containerType="project") to see your project structure
- Start creating tasks for upcoming work
- Use Skills or direct tools (manage_container) when ready to code

Your existing documentation remains the source of truth in [CLAUDE.md/etc].
Task Orchestrator now tracks your work planning and progress.
```

---

## SCENARIO B: New Project with Plan

Use this path when starting a brand new project with a project plan document.

### Step 4B: Get Project Plan

**Ask user for project plan** (use AskUserQuestion):

```
AskUserQuestion(
  questions: [{
    question: "How would you like to provide the project plan?",
    header: "Plan Source",
    multiSelect: false,
    options: [
      {
        label: "File Path",
        description: "I'll read a project plan document (.md, .txt, .pdf)"
      },
      {
        label: "Paste Content",
        description: "Provide plan content inline"
      },
      {
        label: "Describe Verbally",
        description: "Answer structured questions about the project"
      },
      {
        label: "Already Documented",
        description: "Skip extraction, proceed with project creation"
      }
    ]
  }]
)
```

**If "File Path"**:
- Use file reading tools to load document
- Support: .md, .txt, .pdf, .docx, etc.

**If "Paste Content"**:
- User provides plan content directly
- Process the provided text

**If "Describe Verbally"**:
- Ask structured questions:
  - What are you building and why?
  - Who are the target users?
  - What technologies will you use?
  - What are the main features?
  - What are success criteria?

**If "Already Documented"**:
- Skip to Step 6B (create project entity directly)

### Step 5B: Extract Project Information to Shared Docs

**Extract key information from project plan**:
- Project overview (what/why)
- Technology stack decisions
- Architecture approach
- Development standards
- Core features/capabilities

**Write to shared project documentation**:

**Use your AI agent's preferred format**:
- CLAUDE.md (Claude Code - append or create)
- .cursorrules (Cursor - append or create)
- .github/copilot-instructions.md (GitHub Copilot)
- .ai/PROJECT.md (fallback if no standard format)

**Required format** - Append this section:
```markdown
# [Project Name]

## Project Overview

[Extracted overview from plan]

## Technology Stack

[Extracted technologies and architecture decisions]

## Core Features

[High-level feature list from plan]

## Development Standards

[Extracted guidelines, workflows, testing requirements]
```

**File Writing Steps**:
1. Check if preferred doc file exists
2. If exists: Append project information
3. If doesn't exist: Create file with project info
4. **Verify**: Read file back to confirm write
5. **Verify**: File is NOT in .gitignore (should be committed)
6. **Report**: "Created project documentation in [file-path]"

**Important**: This file should be committed to git so all team members can see it.

### Step 6B: Check for Existing Projects (REQUIRED)

**Before creating a new project**, search for existing projects:

**Use query_container:**
```javascript
query_container(
  operation="search",
  containerType="project",
  query="[project name from plan]"
)
```

**If existing project found**, use AskUserQuestion:
```
AskUserQuestion(
  questions: [{
    question: "Found existing project '[name]' (UUID: [uuid], created: [date]). Is this the same project?",
    header: "Duplicate",
    multiSelect: false,
    options: [
      {
        label: "Yes, Same Project",
        description: "Use existing project UUID, skip creation"
      },
      {
        label: "No, Different Project",
        description: "Provide a different name to avoid confusion"
      }
    ]
  }]
)
```

- **If "Yes, Same Project"**:
  - Skip project creation (Step 7B)
  - Use the existing project UUID
  - Jump to Step 11B (save existing UUID to local memory)
  - Report: "Recovered existing project UUID to local memory"

- **If "No, Different Project"**:
  - Ask user to provide a different name to avoid confusion
  - Continue to Step 7B with new name

**If no existing project found**:
- Continue to Step 7B (create new project)

### Step 7B: Create Lightweight Project Entity

**Create minimal project container** in Task Orchestrator:

**Use manage_container:**
```javascript
manage_container(
  operation="create",
  containerType="project",
  name="[Project name from plan]",
  summary="[1-2 sentence essence from plan]",
  status="planning",
  tags="[tech-stack,project-type]"
)
```

**Keep it simple**:
- Summary: Just the core value proposition
- Tags: Planned technology stack
- Status: "planning" for new projects
- **DO NOT create project sections** - info already in shared docs

### Step 8B: Discover Available Templates (REQUIRED)

**BEFORE creating features or tasks**, discover available templates:

**Use query_templates:**
```javascript
// For feature templates
query_templates(
  operation="list",
  targetEntityType="FEATURE",
  isEnabled=true
)

// For task templates
query_templates(
  operation="list",
  targetEntityType="TASK",
  isEnabled=true
)
```

**Common Templates:**
- **Tasks:** `task-implementation-workflow`, `technical-approach`, `testing-strategy`, `bug-investigation`, `local-git-branching-workflow`, `github-pr-workflow`
- **Features:** `requirements-specification`, `context-and-background`, `definition-of-done`

**CRITICAL:** Never assume templates exist. Always discover first, then apply by ID.

**Store template IDs** for use in subsequent steps.

### Step 9B: Create Features from Plan

**Identify 3-7 core features** from project plan:

Ask user to confirm (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "From your plan, I identified [count] core features: [list]. Create these in Task Orchestrator?",
    header: "Features",
    multiSelect: false,
    options: [
      {
        label: "Yes, Create All",
        description: "Create all identified features"
      },
      {
        label: "No, Let Me Review",
        description: "I'll select which features to create"
      }
    ]
  }]
)
```

**For each confirmed feature**, use manage_container:

```javascript
manage_container(
  operation="create",
  containerType="feature",
  name="[Feature name from plan]",
  summary="[What it will do and why it's needed]",
  status="planning",
  priority="[high for foundation, medium for enhancements]",
  projectId="[project-id-from-step-7B]",
  templateIds=["[template-uuid-from-step-8B]"],
  tags="[feature-area,user-facing/internal]"
)
```

**Minimal templates**:
- Use just "requirements-specification" template ID to start
- Keeps overhead low for new projects

### Step 10B: Create Foundation Tasks

**Create initial implementation tasks**:

**Git Detection**: Check for .git directory
- If exists: Include git workflow template IDs
- If not: Ask if user wants to initialize git

**Foundation tasks to create**:

```javascript
manage_container(
  operation="create",
  containerType="task",
  title="Project Infrastructure Setup",
  summary="Initialize repository, configure build tools, set up CI/CD",
  projectId="[project-id]",
  priority="high",
  complexity=5,
  templateIds=["[task-implementation-workflow-uuid]", "[technical-approach-uuid]"],
  tags="task-type-infrastructure,setup,foundation"
)
```

**If git detected**:
```javascript
manage_container(
  operation="create",
  containerType="task",
  title="Establish Development Workflow",
  summary="Set up branching strategy, PR process, commit conventions",
  projectId="[project-id]",
  priority="high",
  complexity=3,
  templateIds=["[local-git-branching-workflow-uuid]", "[github-pr-workflow-uuid]"],
  tags="task-type-process,git-workflow"
)
```

**Create 1-2 tasks per feature** for initial work:
```javascript
manage_container(
  operation="create",
  containerType="task",
  title="[Specific implementation task]",
  summary="[What to build with acceptance criteria]",
  featureId="[feature-id]",
  projectId="[project-id]",
  priority="medium",
  complexity=[3-6],
  templateIds=["[task-implementation-workflow-uuid]"],
  tags="task-type-feature,[component]"
)
```

### Step 11B: Save Project UUID to Local Memory

**REQUIRED ACTION**: Write project UUID to local developer memory.

**Storage location** - Your AI agent's local memory:
- CLAUDE.local.md (Claude Code)
- .cursor/local (Cursor)
- .ai/local.config (fallback)

**Required format**:
```markdown
## Task Orchestrator - Project Context

Project UUID: [uuid-from-step-7B]
Project Name: [name]
Last synced: YYYY-MM-DD

Quick commands:
- View overview: query_container(operation="overview", containerType="project")
- View project: query_container(operation="get", containerType="project", id="[uuid]")
```

**File Writing Steps**:
1. Check if local config file exists
2. If exists: Check for "Task Orchestrator - Project Context" section
3. If section exists: Update UUID and timestamp
4. If section missing: Append at end
5. If file doesn't exist: Create with this section
6. **Verify**: Read file back to confirm write
7. **Report**: "Saved project UUID to [file-path]"

### Step 12B: Ensure Local Memory is Gitignored

**REQUIRED ACTION**: Verify local storage is gitignored.

**Check .gitignore**:
1. Read .gitignore file (or create if missing)
2. Check if your local config file is listed
3. If NOT listed: Add it

**Common entries to add**:
```
# Task Orchestrator local context
CLAUDE.local.md
.cursor/local
.ai/local.config
*.local.md
```

**Verify**: Read .gitignore back to confirm

**Report**: "Added [your-local-file] to .gitignore"

### Step 13B: Completion

**Report to user**:
```
✅ New project setup complete!

Project: [name] ([uuid])
Documentation: [shared-doc-file] (committed to git)
UUID saved to: [local-file] (gitignored)
Features created: [count]
Foundation tasks created: [count]

Next steps:
- Run query_container(operation="overview", containerType="project") to see your project structure
- Start with foundation tasks (infrastructure, workflow setup)
- Begin feature implementation

Documentation split:
- Technical guidance: [shared-doc-file] (all developers)
- Work planning: Task Orchestrator database (this MCP)
- Local context: [local-file] (just you)

Ready to start building! 🚀
```

---

## Best Practices Summary

**Clear Division of Documentation**:
- **Shared Docs** (git): Technical how-to, architecture, build commands
- **Task Orchestrator**: Work planning, task tracking, progress
- **Local Memory** (gitignored): Project UUID, personal preferences

**Keep It Simple**:
- Minimal project entity (just container)
- No database sections that duplicate file docs
- Lightweight templates (1-2 max per entity)
- Focus on work tracking, not documentation duplication

**AI Agent Agnostic**:
- Let AI choose its preferred storage format
- Provide WHAT to store, not WHERE
- Work with any AI tool (Claude, Cursor, Copilot, etc.)
- Report actual storage locations to user

**Template Discovery**:
- ALWAYS run query_templates before creating entities
- Never assume templates exist
- Apply templates by UUID from discovery results
- Store template IDs for reuse

**Gitignore Discipline**:
- Always ensure local memory is gitignored
- Project UUIDs are developer-specific
- Each developer may use different AI tools
- Verify .gitignore before completing setup

This streamlined approach works for vibe coding and small teams, avoiding enterprise bureaucracy while maintaining effective project management.
