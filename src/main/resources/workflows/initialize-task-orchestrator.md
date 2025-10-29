---
name: initialize-task-orchestrator
description: Guide AI agents through self-initialization with Task Orchestrator guidelines in their own memory systems
version: "2.0.1"
---

# Task Orchestrator Initialization Workflow

This workflow guides AI agents through self-initialization with Task Orchestrator guidelines, enabling effective natural language interaction with task management tools.

## Understanding the System Architecture

Task Orchestrator uses a three-layer guidance system:

**Layer 1: MCP Resources (Guidelines)**
- Discoverable reference documentation
- Principle-based guidance for AI agents
- Internalized patterns for autonomous operation
- Available via `task-orchestrator://guidelines/*` URIs

**Layer 2: Workflow Prompts**
- User-invokable step-by-step processes
- Explicit guidance for complex scenarios
- Complement autonomous AI operation
- Invoked when needed for complex workflows

**Layer 3: Dynamic Templates**
- Database-driven documentation structures
- Discovered via `list_templates` tool
- Applied during entity creation
- Provide consistent documentation patterns

## Initialization Steps

### Step 1: Read Core Guideline Resources

AI agents should read and internalize all guideline resources using appropriate MCP resource reading capabilities:

**Essential Resources**:
1. `task-orchestrator://guidelines/usage-overview`
   - When/why to use Task Orchestrator tools
   - Decision framework for tool selection
   - Natural language pattern recognition principles

2. `task-orchestrator://guidelines/template-strategy`
   - Critical: Templates are dynamic, database-driven
   - Template discovery workflow patterns
   - Template selection decision trees
   - Application patterns and best practices

3. `task-orchestrator://guidelines/task-management`
   - Intent recognition for natural language requests
   - 6 executable workflow patterns
   - Pattern application guidelines
   - Autonomy vs workflow prompt decisions

4. `task-orchestrator://guidelines/workflow-integration`
   - Dual workflow model explanation
   - When to apply patterns autonomously
   - When to invoke workflow prompts
   - Integration best practices

### Step 2: Check for Existing Initialization

Before writing guidelines, **CHECK** if Task Orchestrator is already initialized:

**Detection Steps**:
1. Check if your AI's standard documentation file exists (CLAUDE.md, .cursorrules, etc.)
2. If exists: Search for "## Task Orchestrator - AI Initialization" section
3. If found: Read the section to extract version/timestamp information

**If EXISTING initialization found**, present re-initialization options using AskUserQuestion:

```
AskUserQuestion(
  questions: [{
    question: "Task Orchestrator is already initialized (Last: [EXISTING-DATE], Version: [EXISTING-VERSION], Features: [EXISTING-FEATURES]). Latest version is 1.1.0-beta. What would you like to do?",
    header: "Update Mode",
    multiSelect: false,
    options: [
      {
        label: "Refresh Guidelines",
        description: "Update patterns to latest version, preserve features and customizations"
      },
      {
        label: "Install New Features",
        description: "Add newly available features (hooks/subagents) without changing guidelines"
      },
      {
        label: "Full Re-initialization",
        description: "Complete rewrite with all options (preserves existing installations)"
      },
      {
        label: "Cancel",
        description: "Keep existing configuration unchanged"
      }
    ]
  }]
)
```

**Based on user choice**:
- **"Refresh Guidelines"** → Jump to "Refresh Guidelines Mode" (see below)
- **"Install New Features"** → Jump to "Install New Features Mode" (see below)
- **"Full Re-initialization"** → Continue with fresh initialization (Step 2A)
- **"Cancel"** → Exit workflow

**If NO existing initialization found**, continue with fresh initialization.

### Step 2A: WRITE Guidelines to Permanent Storage (Fresh or Full Re-init)

After reading resources, AI agents **MUST write** the key principles to persistent storage.

**REQUIRED ACTION**: Write initialization results to a permanent file on disk.

**Storage Options** - Use your AI agent's standard documentation file:
- **CLAUDE.md** (Claude Code)
- **.cursorrules** (Cursor IDE)
- **.github/copilot-instructions.md** (GitHub Copilot)
- **.windsurfrules** (Windsurf)
- **.ai/INITIALIZATION.md** (fallback if your AI doesn't have a standard format)

**Required Format** - Use a clearly marked section:
```markdown
## Task Orchestrator - AI Initialization

**Last initialized:** YYYY-MM-DD | **Version:** 1.1.0-beta | **Features:** [none|hooks|subagents|hooks,subagents]

### Critical Patterns

**Session Start Routine**:
1. Run get_overview() first to understand current state
2. Check for in-progress tasks before starting new work
3. Review priorities and dependencies

**Intent Recognition** (applies to templates OR sub-agents):
- "Create feature for X" → Feature creation with template discovery
- "Implement X" → Task creation with implementation templates (then optionally route to specialist)
- "Fix bug X" → Bug triage with Bug Investigation template (then optionally route to specialist)
- "Break down X" → Task decomposition pattern
- "Set up project" → Project setup workflow

**Template Discovery** (ALWAYS required, regardless of using sub-agents):
- Always: list_templates(targetEntityType, isEnabled=true)
- Never: Assume templates exist
- Apply: Use templateIds parameter during creation
- Templates work with both direct execution AND sub-agent execution

**Git Integration**:
- Auto-detect .git directory presence
- Suggest git workflow templates when detected
- Ask about PR workflows (don't assume)

**Quality Standards**:
- Write descriptive titles and summaries
- Use appropriate complexity ratings (1-10)
- Apply consistent tagging conventions
- Include acceptance criteria in summaries
```

**File Writing Steps** (Fresh or Full Re-init):
1. Check if your AI's standard documentation file exists in project root
2. If exists: Read current content
3. If section exists: **Replace entire section** with new content (full rewrite)
4. If section missing: Append new section at end of file
5. If no file exists: Create your AI's standard documentation file with initialization section
6. Update version field to current version (1.1.0-beta)
7. Update Features field based on Step 7 selections (none, hooks, subagents, or hooks,subagents)
8. **Verify**: Read the file back to confirm successful write
9. **Report**: Tell user where initialization was saved (specific file path and section)

### Step 2B: Refresh Guidelines Mode (Option [1])

**Purpose**: Update patterns without changing features or full rewrite.

**Actions**:
1. Read existing initialization section from documentation file
2. Extract current version and features from existing section
3. **Replace only** the "### Critical Patterns" subsection with latest patterns (from template above)
4. Update timestamp to current date (YYYY-MM-DD)
5. Update version to 1.1.0-beta
6. **Preserve** features field (don't change existing hooks/subagents status)
7. **Preserve** any custom content user added outside Critical Patterns subsection
8. Write updated section back to file
9. Verify and report: "Guidelines refreshed to v1.1.0-beta. Your features unchanged: [list]"

**What gets updated**:
- ✅ Critical Patterns subsection (latest workflow patterns)
- ✅ Timestamp (current date)
- ✅ Version field (1.1.0-beta)

**What is preserved**:
- ✅ Features field (hooks/subagents status unchanged)
- ✅ User customizations outside Critical Patterns
- ✅ File location and overall structure

### Step 2C: Install New Features Mode (Option [2])

**Purpose**: Detect and install newly available features without rewriting guidelines.

**Actions**:
1. Read existing initialization section to check Features field
2. Parse Features field to see what's already installed
3. Check for `.claude/` directory (Claude Code detection)
4. Determine what's NEW and available:

**Feature Detection Logic**:
```
If .claude/ exists:
    Check Features field for "hooks"
    ├─ Missing "hooks" → Offer hook installation
    └─ Has "hooks" → Skip (already installed)

    Check Features field for "subagents"
    ├─ Missing "subagents" → Offer subagent installation
    └─ Has "subagents" → Skip (already installed)

    Check if .claude/settings.local.json has Task Orchestrator hooks
    ├─ Not found → Offer hook installation (Features field outdated)
    └─ Found → Confirm already installed

    Check if .claude/agents/ directory exists
    ├─ Not found → Offer subagent installation (Features field outdated)
    └─ Found → Confirm already installed
Else:
    Report: "Claude Code not detected. Features require .claude/ directory."
```

**Installation Options** (use AskUserQuestion):
```
AskUserQuestion(
  questions: [{
    question: "Which new features would you like to install?",
    header: "Features",
    multiSelect: false,
    options: [
      {
        label: "Hooks Only",
        description: "Auto-load context at session start, template discovery reminders"
      },
      {
        label: "Sub-agents Only",
        description: "Specialist routing with 97% token reduction for complex features"
      },
      {
        label: "Both",
        description: "Install hooks and sub-agents together (recommended)"
      },
      {
        label: "None",
        description: "Cancel installation"
      }
    ]
  }]
)
```

5. Based on selection, install requested features (same as Step 7 logic)
6. Update Features field in initialization section
7. Update timestamp
8. Report: "Installed: [features]. Your guidelines unchanged."

**Sync Detection** (important!):
If Features field says "hooks" but `.claude/settings.local.json` doesn't have them:
- Alert: "Features field outdated. Hooks listed but not found in settings."
- Offer: "Would you like to [1] Install hooks or [2] Update field to remove 'hooks'?"

If Features field says "subagents" but `.claude/agents/` doesn't exist:
- Alert: "Features field outdated. Sub-agents listed but directory not found."
- Offer: "Would you like to [1] Install sub-agents or [2] Update field to remove 'subagents'?"

### Step 3: Understand Two Workflow Systems

Task Orchestrator provides TWO complementary workflow systems:

**System 1: Templates + Workflow Prompts (Universal)**:
- Works with ANY MCP client (Claude Desktop, Claude Code, Cursor, Windsurf, etc.)
- Always available, no setup required
- Templates structure the WORK (what to document, requirements, testing strategy)
- Workflow Prompts guide the PROCESS (step-by-step creation, implementation, validation)
- Use for: All work, regardless of complexity or AI client

**System 2: Sub-Agent Orchestration (Claude Code Only)**:
- 3-level agent coordination for complex multi-task features
- Works ONLY with Claude Code (requires `.claude/agents/` directory)
- Setup required: Run `setup_claude_orchestration` tool first
- 97% token reduction through specialist routing
- Use for: Complex features (4+ tasks), specialist coordination, token efficiency

**How They Work Together**:
- Templates structure documentation → Sub-agents read and execute
- Templates work with BOTH direct execution AND sub-agent execution
- You can use templates without sub-agents (universal)
- Sub-agents always use templates for context (complementary)

**Pattern Application Modes** (applies to BOTH systems):
```
User Request → Intent Recognition
├─ Clear, common pattern? → Apply pattern autonomously (fast)
├─ Complex, multi-phase? → Invoke workflow prompt (guided)
├─ Educational need? → Invoke workflow prompt (teaching)
└─ Uncertain? → Apply pattern + explain approach
```

### Step 4: Key Patterns to Remember

**Always Start Sessions With**:
```
get_overview() to understand current work state
```

**Template Discovery Pattern**:
```
list_templates(targetEntityType, isEnabled=true)
→ Review templates
→ Select appropriate templates
→ Apply via templateIds parameter
```

**Never Assume Templates Exist**:
- Templates are database-driven and dynamic
- Always use list_templates to discover
- Filter by targetEntityType (TASK or FEATURE)
- Check isEnabled status

**Natural Language Intent Recognition**:
- "Create feature for..." → Feature creation pattern
- "Implement..." → Task creation with implementation templates
- "Fix bug..." → Bug triage pattern
- "Break down..." → Task decomposition pattern
- "Set up project..." → Project setup pattern

**Git Workflow Integration**:
- Detect .git directory in project
- Auto-suggest git workflow templates when detected
- Ask about PR workflows (don't assume)

### Step 5: Verify Initialization

**Self-Check Questions**:
- [ ] Can I explain when to use Task Orchestrator vs internal task tracking?
- [ ] Do I understand the template discovery pattern and why it's critical?
- [ ] Can I recognize common user intents and map them to patterns?
- [ ] Do I know when to apply patterns autonomously vs suggest workflow prompts?
- [ ] Have I stored key guidelines in my available memory system?

**Test Understanding**:
- Read a hypothetical user request
- Identify the intent category
- Determine appropriate pattern or workflow
- Plan template discovery approach
- Decide autonomous vs workflow prompt

### Step 6: Initialization Confirmation and File Persistence

After completing initialization, AI agents **MUST**:

1. **Confirm Resource Reading**:
   ✅ Verify all 4 guideline resources were successfully read and understood

2. **WRITE to Permanent Storage**:
   ✅ Write initialization summary to your AI's standard documentation file
   ✅ Include timestamp for tracking (YYYY-MM-DD format)
   ✅ Use the exact format from Step 2 "Required Format"
   ✅ Read the file back to verify successful write

3. **Report to User**:
   ✅ Tell user where initialization was saved (specific file path)
   ✅ Show which section was added/updated
   ✅ Confirm one-time setup is complete
   ✅ Example: "Initialization complete. Added 'Task Orchestrator - AI Initialization' section to [your-doc-file]"

4. **Confirm Pattern Recognition**:
   ✅ Demonstrate understanding by explaining how to handle a sample request
   ✅ Show template discovery pattern in action

## ⚠️ Common Mistakes to Avoid

**❌ WRONG: Storing guidelines only in session/conversational context**
- This is lost when the session ends
- Requires re-initialization every time
- Defeats the purpose of one-time setup
- Makes the initialization workflow fail its primary goal

**✅ CORRECT: Writing guidelines to your AI's standard documentation file**
- Persists across all sessions
- Available to all AI agents working in this project
- True one-time initialization
- Verifiable by reading the file

**❌ WRONG: Creating a new separate file without checking existing files**
- Creates fragmentation
- AI might not find guidelines later
- Duplicates effort

**✅ CORRECT: Checking for and appending to existing shared documentation**
- Consolidates all AI guidance in one location
- Standard location for project-wide AI instructions
- Easy to find and update

**❌ WRONG: Saying "I've stored this in my memory" without file write**
- Vague and unverifiable
- Doesn't create persistent storage
- Lost on next session

**✅ CORRECT: Reporting specific file path and verification**
- "Added Task Orchestrator initialization to [your-documentation-file] at line 45"
- Shows exactly where it was saved
- Can be verified by reading the file

### Step 7: Claude Code Optional Features (If Applicable)

**Only if using Claude Code** - Check for `.claude/` directory in project root.

If `.claude/` directory exists, offer optional Claude Code features using AskUserQuestion:

```
AskUserQuestion(
  questions: [{
    question: "Claude Code detected! Which optional features would you like?",
    header: "Features",
    multiSelect: false,
    options: [
      {
        label: "Hooks Only",
        description: "Lightweight automation (session start, template reminders)"
      },
      {
        label: "Sub-agents Only",
        description: "Specialist routing for complex features (3+ tasks)"
      },
      {
        label: "Both",
        description: "Recommended for complex projects with 4+ tasks per feature"
      },
      {
        label: "Neither",
        description: "Manual workflow only, no automation"
      }
    ]
  }]
)
```

**Option [1] or [3]: Install Hooks**

If user chooses hooks:
1. Check if `.claude/settings.local.json` exists
2. If exists: Read current content
3. Merge Task Orchestrator hooks into existing config:
   ```json
   {
     "hooks": {
       "SessionStart": [{
         "matcher": "*",
         "hooks": [{
           "type": "command",
           "command": "bash",
           "args": ["-c", "echo '{\"message\": \"💡 Task Orchestrator: Loading project context with get_overview()...\"}'"]
         }]
       }],
       "PreToolUse": [{
         "matcher": "mcp__task-orchestrator__create_task|mcp__task-orchestrator__create_feature",
         "hooks": [{
           "type": "command",
           "command": "bash",
           "args": ["-c", "if ! echo \"${'$'}TOOL_INPUT\" | grep -q '\\\"templateIds\\\"'; then echo '{\\\"message\\\": \\\"💡 Tip: Consider running list_templates() to discover available templates.\\\"}'; fi"]
         }]
       }]
     }
   }
   ```
4. Write merged config back to `.claude/settings.local.json`
5. Verify file was written successfully

**Option [2] or [3]: Install Sub-Agents and Skills**

If user chooses sub-agents:
1. Run the `setup_claude_orchestration` tool
2. This creates `.claude/agents/` directory with 8 subagent definitions:
   - backend-engineer.md
   - bug-triage-specialist.md
   - database-engineer.md
   - feature-architect.md
   - frontend-developer.md
   - planning-specialist.md
   - technical-writer.md
   - test-engineer.md
3. This also creates `.claude/skills/` directory with 6 skill definitions:
   - dependency-analysis (analyze dependencies, identify blockers)
   - dependency-orchestration (manage dependency creation/updates)
   - feature-orchestration (coordinate feature lifecycle)
   - hook-builder (interactive hook creation tool)
   - status-progression (guide status transitions)
   - task-orchestration (coordinate task lifecycle)
4. Verify agent and skill files were created successfully

**Skills vs Subagents**:
- Skills: Lightweight coordination (2-5 tool calls, 500-800 tokens)
- Subagents: Complex implementation (2000+ tool calls, deep work)

**Option [4]: Skip Both**

If user declines:
- Note that workflows and templates still work (universal features)
- User can manually install later if needed

### Step 8: Initialization Complete - Final Report

After all steps completed, provide comprehensive summary:

```
✅ Task Orchestrator initialized successfully!

Configuration:
• Initialization written to: [file path and section name]
• Last initialized: [YYYY-MM-DD]

[If hooks installed:]
📝 Workflow Automation Active:
   • Auto-loads context at session start
   • Provides template discovery reminders
   • Configuration: .claude/settings.local.json

   💡 To disable: Edit or remove "hooks" section from .claude/settings.local.json

[If subagents installed:]
🤖 Subagents & Skills Ready:
   • Use recommend_agent(taskId) to find appropriate specialist
   • Skills auto-activate for coordination tasks
   • Subagent definitions: .claude/agents/*.md
   • Skill definitions: .claude/skills/*/SKILL.md
   • See docs/agent-orchestration.md for complete guide

[If neither installed:]
📋 Manual Workflow Mode:
   • Templates and workflow prompts available (universal features)
   • Can install hooks/subagents later if needed

Next steps:
1. Try: "Show me the project overview"
2. Create your first feature: "Create a feature for [description]"
3. [If subagents] For implementation: Use recommend_agent(taskId) to find specialist

🎯 You're ready to use Task Orchestrator!
```

**Important Notes**:
- Hooks are OPTIONAL automation helpers, not required for workflows to function
- Sub-agents are OPTIONAL Claude Code feature, templates work universally
- Users can remove hooks by editing `.claude/settings.local.json`
- Users can remove sub-agents by deleting `.claude/agents/` directory

## Ongoing Best Practices

**Session Start Routine**:
- Run get_overview() to understand current state
- Check for in-progress tasks before starting new work
- Review priorities and dependencies

**Template Usage**:
- Always discover templates dynamically
- Apply multiple templates for comprehensive coverage
- Combine workflow + documentation + quality templates

**Natural Language Interaction**:
- Recognize intent from user's natural phrasing
- Apply appropriate patterns automatically when clear
- Offer workflow prompts for complex scenarios
- Explain your approach when applying patterns

**Quality Standards**:
- Write descriptive titles and summaries
- Use appropriate complexity ratings (1-10)
- Apply consistent tagging conventions
- Include acceptance criteria in summaries

## Success Indicators

Initialization is successful when AI agents:
- Recognize user intents without explicit workflow invocation
- Autonomously apply template discovery patterns
- Make appropriate autonomous vs workflow prompt decisions
- Provide efficient, natural interactions with Task Orchestrator
- Reference guideline resources when needed for detailed guidance

This initialization enables effective, natural language interaction with Task Orchestrator tools while maintaining access to detailed workflow guidance when needed.

---

## Next Step: Set Up Your Project

**After completing AI initialization**, use the project setup workflow to configure Task Orchestrator for your specific project:

**Run**: `project_setup_workflow`

**What it does**:
- Calls `setup_project` tool → Creates `.taskorchestrator/` configuration directory
- Detects if you're using Claude Code → Optionally calls `setup_claude_orchestration`
- Creates project entity in Task Orchestrator database
- Sets up features and initial tasks
- Saves project UUID to local memory

**Separation of Concerns**:
- **`initialize_task_orchestrator`** (this workflow): Teaches AI HOW to use Task Orchestrator (once per AI agent)
- **`project_setup_workflow`**: Configures Task Orchestrator FOR a specific project (once per project)

**You can skip project setup** if you just want to use Task Orchestrator tools directly without the full workflow guidance.
