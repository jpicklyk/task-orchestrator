package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * User-invokable MCP prompts for common Task Orchestrator workflows.
 * 
 * These prompts provide users with specific workflow templates and guidance
 * for common task orchestration scenarios, following proper MCP prompt patterns.
 */
object WorkflowPromptsGuidance {

    /**
     * Configures workflow prompts for the MCP server.
     *
     * v2.0 Architecture: Workflows now delegate to Skills for detailed guidance (Claude Code only).
     * For non-Claude Code users, direct tool calls are recommended.
     */
    fun configureWorkflowPrompts(server: Server) {
        addInitializeTaskOrchestratorPrompt(server)
        addProjectSetupPrompt(server)
        addUpdateProjectConfigPrompt(server)
        addCoordinateFeatureDevelopmentPrompt(server)
    }

    /**
     * Adds an AI-agnostic initialization prompt that guides AI agents through setting up
     * Task Orchestrator guidelines in their own memory systems.
     */
    private fun addInitializeTaskOrchestratorPrompt(server: Server) {
        server.addPrompt(
            name = "initialize_task_orchestrator",
            description = "Guide AI agents through self-initialization with Task Orchestrator guidelines in their own memory systems"
        ) { _ ->
            GetPromptResult(
                description = "AI-agnostic initialization workflow for setting up Task Orchestrator usage guidelines",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
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

                            **If EXISTING initialization found**, present re-initialization options:

                            Present this message with proper line breaks:

                            ```
                            🔄 Task Orchestrator Already Initialized

                            Current: Last initialized [EXISTING-DATE]
                            Version: [EXISTING-VERSION]  (if version field present)
                            Features: [EXISTING-FEATURES]  (if features field present)
                            Latest: Task Orchestrator 1.1.0-beta

                            What would you like to do?

                            [1] Refresh Guidelines
                                • Update "AI Initialization" section with latest patterns
                                • Preserve customizations outside this section
                                • Update timestamp and version
                                • Recommended for minor updates

                            [2] Install New Features
                                • Check for newly available features (hooks, sub-agents)
                                • Install only features not yet configured
                                • Skip already-installed features
                                • Recommended when new features added to MCP

                            [3] Full Re-initialization
                                • Rewrite entire "AI Initialization" section
                                • Re-offer all optional features (hooks, sub-agents)
                                • Detect and preserve existing installations
                                • Recommended after major version upgrades

                            [4] Cancel
                                • Keep existing configuration unchanged

                            Your choice: [1-4]
                            ```

                            **Based on user choice**:
                            - **[1]** → Jump to "Refresh Guidelines Mode" (see below)
                            - **[2]** → Jump to "Install New Features Mode" (see below)
                            - **[3]** → Continue with fresh initialization (Step 2A)
                            - **[4]** → Exit workflow

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

                            **Installation Options**:
                            ```
                            Available new features:

                            [Hooks] Workflow Automation Hooks (if not installed)
                                    • SessionStart: Auto-load context
                                    • PreToolUse: Template discovery reminders

                            [Sub-agents] Sub-Agent Orchestration (if not installed)
                                         • 3-level agent coordination
                                         • 97% token reduction

                            Which features would you like to install?
                            [H] Hooks only
                            [S] Sub-agents only
                            [B] Both
                            [N] None (cancel)
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

                            If `.claude/` directory exists, offer optional Claude Code features:

                            ```
                            🎯 Claude Code Detected! Optional features available:

                            [A] Workflow Automation Hooks
                                • Auto-loads project context at session start (runs get_overview automatically)
                                • Provides template discovery reminders when creating tasks/features
                                • Reduces manual workflow steps and improves consistency
                                • Configurable in .claude/settings.local.json

                            [B] Sub-Agent Orchestration System
                                • 3-level agent coordination (Feature Manager → Task Manager → Specialists)
                                • 97% token reduction for complex multi-task features
                                • Automatic specialist routing based on task tags
                                • Requires agent definitions in .claude/agents/ directory

                            Would you like to install:
                            [1] Hooks only (lightweight automation)
                            [2] Sub-agents only (complex feature support)
                            [3] Both (recommended for complex projects with 4+ tasks per feature)
                            [4] Neither (manual workflow only)
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
                                       "args": ["-c", "if ! echo \"\${'$'}TOOL_INPUT\" | grep -q '\\\"templateIds\\\"'; then echo '{\\\"message\\\": \\\"💡 Tip: Consider running list_templates() to discover available templates.\\\"}'; fi"]
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
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for creating a comprehensive feature with proper templates and tasks.
     */
    private fun addProjectSetupPrompt(server: Server) {
        server.addPrompt(
            name = "project_setup_workflow",
            description = "AI-agnostic guide for setting up Task Orchestrator with existing or new projects"
        ) { _ ->
            GetPromptResult(
                description = "Universal workflow for Task Orchestrator project setup across any AI agent",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Project Setup Workflow (AI-Agnostic)

                            This workflow helps you set up Task Orchestrator for project management, whether you're working with an existing codebase or starting a brand new project. It works with any AI agent by using abstract storage patterns rather than prescribing specific files.

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
                            - Access: Through MCP tools (get_overview, create_task, etc.)

                            ## Key Principle: AI Agent Agnostic

                            This workflow provides **what to store** and **why**, but lets your AI agent decide **where** and **how** using its native capabilities.

                            Your AI agent should:
                            - Use its preferred file formats and locations
                            - Store shared docs in git-committed files
                            - Store local context in gitignored files
                            - Report where it stored information

                            ## Step 0: Initialize Task Orchestrator Configuration (REQUIRED FIRST)

                            **Before setting up your project**, you MUST initialize Task Orchestrator's core configuration.

                            ### Step 0.1: Run setup_project Tool

                            **Execute the setup_project tool** to create core Task Orchestrator configuration:

                            ```
                            setup_project()
                            ```

                            **What this creates**:
                            - `.taskorchestrator/` directory
                            - `.taskorchestrator/config.yaml` - Orchestrator configuration
                            - `.taskorchestrator/status-workflow-config.yaml` - Workflow definitions
                            - `.taskorchestrator/agent-mapping.yaml` - Agent routing configuration

                            **This is idempotent**: Safe to run multiple times, skips existing files.

                            **Verify success**: Tool should report success and list created/skipped files.

                            ### Step 0.2: Detect Claude Code Usage

                            **Check if the user is using Claude Code** (enables optional advanced features):

                            **Detection methods** (try in order):
                            1. Check for `.claude/` directory in project root (indicates Claude Code setup)
                            2. Ask user: "Are you using Claude Code for this project? (enables sub-agents and skills) [Y/n]"

                            **Based on detection**:
                            - **If Claude Code detected/confirmed**: Continue to Step 0.3
                            - **If NOT Claude Code**: Skip to Step 1 (basic setup complete)

                            ### Step 0.3: Run setup_claude_orchestration Tool (Claude Code Only)

                            **If Claude Code is being used**, execute the setup_claude_orchestration tool:

                            ```
                            setup_claude_orchestration()
                            ```

                            **What this creates** (Claude Code specific):
                            - `.claude/agents/task-orchestrator/` - 4 specialized subagent definitions
                            - `.claude/skills/` - 6 Skills for lightweight coordination workflows
                            - `.claude/output-styles/` - Task Orchestrator output style

                            **Prerequisite check**: This tool will verify `.taskorchestrator/` exists (from Step 0.1).

                            **This is idempotent**: Safe to run multiple times, skips existing files.

                            **Verify success**: Tool should report success and list created/skipped files.

                            **Note**: If user is not using Claude Code, this step is skipped and they use Task Orchestrator via direct tool calls.

                            ---

                            **After Step 0 completion**, report to user:

                            ```
                            ✅ Task Orchestrator configuration initialized!

                            Core setup complete:
                            - Configuration files created in .taskorchestrator/
                            [If Claude Code:]
                            - Claude Code integration enabled
                            - Subagents and Skills available in .claude/

                            Ready to proceed with project setup...
                            ```

                            ---

                            ## Step 1: Detect Scenario

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

                            **Ask user if unclear**:
                            "I see [existing docs/no docs]. Are you:
                            A) Setting up Task Orchestrator for existing codebase
                            B) Starting a brand new project with a project plan"

                            ## Step 2: Check AI Initialization (Recommended)

                            **RECOMMENDED**: Verify AI has been initialized with Task Orchestrator guidelines.

                            **Check your memory** (CLAUDE.md, .cursorrules, etc.) for "Task Orchestrator - AI Initialization" section:

                            **If NOT found or uncertain**:
                            ```
                            ⚠️  AI Initialization Recommended

                            Before setting up projects, it's recommended to run:

                            `initialize_task_orchestrator`

                            This workflow teaches the AI:
                            - Template discovery patterns (CRITICAL for all work)
                            - Intent recognition for natural language
                            - When to apply patterns autonomously
                            - Quality standards and best practices

                            Without initialization, you can still use Task Orchestrator tools directly,
                            but the AI won't have learned the optimal usage patterns.

                            Would you like to:
                            [1] Run initialize_task_orchestrator first (recommended)
                            [2] Continue with project setup (skip initialization)
                            ```

                            **If user chooses [1]**: Pause this workflow, run `initialize_task_orchestrator`, then resume here.

                            **If user chooses [2] or initialization already done**: Continue to Step 0 (setup tools).

                            ---

                            ## SCENARIO A: Existing Codebase

                            Use this path when project documentation and code already exist.

                            ### Step 3A: Review Existing Documentation

                            **Read existing project documentation** to understand the codebase:

                            Use file system tools to read:
                            - CLAUDE.md, .cursorrules, .github/copilot-instructions.md, or similar
                            - README.md for project overview
                            - Architecture documentation if available

                            **Extract context**:
                            - What is the project building?
                            - What technologies are being used?
                            - What features/components already exist?

                            ### Step 3.5A: Check for Existing Projects (REQUIRED)

                            **Before creating a new project**, search for existing projects:

                            Use search_projects:
                            - Search by project name extracted from documentation
                            - Check if any results match

                            **If existing project found**:
                            Ask user: "I found an existing project '[name]' (UUID: [uuid], created: [date]).
                            Is this the same project you want to set up?"

                            - **If YES**:
                              - Skip project creation (Step 4A)
                              - Use the existing project UUID
                              - Jump to Step 8A (save existing UUID to local memory)
                              - Report: "Recovered existing project UUID to local memory"

                            - **If NO** (different project with same name):
                              - Ask user to provide a different name to avoid confusion
                              - Continue to Step 4A with new name

                            **If no existing project found**:
                            - Continue to Step 4A (create new project)

                            ### Step 4A: Create Lightweight Project Entity

                            **Create minimal project container** in Task Orchestrator:

                            Use create_project:
                            ```json
                            {
                              "name": "[Project name from docs]",
                              "summary": "[1-2 sentence description from docs]",
                              "status": "in-development",
                              "tags": "[main-tech-stack,project-type]"
                            }
                            ```

                            **Keep it simple**:
                            - Summary: Just the essence from existing docs
                            - Tags: Technology stack and type
                            - Status: Likely "in-development" since code exists
                            - **DO NOT create project sections** - docs already exist in files

                            ### Step 5A: Identify Features from Codebase

                            **Analyze existing structure** to identify features:

                            Use file system tools to:
                            - Read source code structure
                            - Review existing documentation
                            - Identify major functional areas

                            **Ask user to confirm features**:
                            "Based on the codebase, I see these major features:
                            - [Feature 1]
                            - [Feature 2]
                            - [Feature 3]

                            Should I create Task Orchestrator features for these?"

                            ### Step 6A: Create Features

                            **For each confirmed feature**, use create_feature:

                            ```json
                            {
                              "name": "[Feature name]",
                              "summary": "[What it does and current state]",
                              "status": "[planning/in-development/completed based on code]",
                              "priority": "medium",
                              "projectId": "[project-id-from-step-4A]",
                              "templateIds": ["requirements-specification"],
                              "tags": "[feature-area,tech-stack]"
                            }
                            ```

                            **Minimal templates**:
                            - Use just "requirements-specification" for existing features
                            - Can add more detail later if needed

                            ### Step 7A: Create Initial Tasks (Optional)

                            **Ask user**: "Would you like me to create tasks for upcoming work?"

                            If yes, create tasks for:
                            - Planned features not yet implemented
                            - Known bugs or improvements
                            - Technical debt items

                            **Git Detection**: Check for .git directory

                            Use create_task:
                            ```json
                            {
                              "title": "[Specific task]",
                              "summary": "[What to do and why]",
                              "featureId": "[feature-id]",
                              "projectId": "[project-id]",
                              "priority": "[high/medium/low]",
                              "complexity": "[3-7]",
                              "templateIds": ["task-implementation-workflow", "local-git-branching-workflow"],
                              "tags": "task-type-feature,[area]"
                            }
                            ```

                            **Template notes**:
                            - Include "local-git-branching-workflow" if git detected
                            - Ask about PR workflows if uncertain

                            ### Step 8A: Save Project UUID to Local Memory

                            **REQUIRED ACTION**: Write project UUID to local developer memory.

                            **Storage location** - Your AI agent's local memory:
                            - CLAUDE.local.md (Claude Code)
                            - .cursor/local (Cursor)
                            - .ai/local.config (fallback)

                            **Required format**:
                            ```markdown
                            ## Task Orchestrator - Project Context

                            Project UUID: [uuid-from-step-4A]
                            Project Name: [name]
                            Last synced: YYYY-MM-DD

                            Quick commands:
                            - View overview: get_overview()
                            - View project: get_project(id="[uuid]", includeFeatures=true, includeTasks=true)
                            ```

                            **File Writing Steps**:
                            1. Check if local config file exists
                            2. If exists: Check for "Task Orchestrator - Project Context" section
                            3. If section exists: Update UUID and timestamp
                            4. If section missing: Append at end
                            5. If file doesn't exist: Create with this section
                            6. **Verify**: Read file back to confirm write
                            7. **Report**: "Saved project UUID to [file-path]"

                            ### Step 9A: Ensure Local Memory is Gitignored

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

                            ### Step 10A: Completion

                            **Report to user**:
                            ```
                            ✅ Task Orchestrator setup complete for existing codebase!

                            Project: [name] ([uuid])
                            UUID saved to: [local-file] (gitignored)
                            Features created: [count]
                            Tasks created: [count]

                            Next steps:
                            - Run get_overview() to see your project structure
                            - Start creating tasks for upcoming work
                            - Use implementation_workflow when ready to code

                            Your existing documentation remains the source of truth in [CLAUDE.md/etc].
                            Task Orchestrator now tracks your work planning and progress.
                            ```

                            ---

                            ## SCENARIO B: New Project with Plan

                            Use this path when starting a brand new project with a project plan document.

                            ### Step 3B: Get Project Plan

                            **Ask user for project plan**:

                            "How would you like to provide the project plan?
                            A) File path (I'll read the document)
                            B) Paste content (provide inline)
                            C) Describe verbally (I'll ask questions)
                            D) Already documented elsewhere (skip extraction)"

                            **If A (File path)**:
                            - Use file reading tools to load document
                            - Support: .md, .txt, .pdf, .docx, etc.

                            **If B (Paste content)**:
                            - User provides plan content directly
                            - Process the provided text

                            **If C (Describe verbally)**:
                            - Ask structured questions:
                              - What are you building and why?
                              - Who are the target users?
                              - What technologies will you use?
                              - What are the main features?
                              - What are success criteria?

                            **If D (Already documented)**:
                            - Skip to Step 5B (create project entity directly)

                            ### Step 4B: Extract Project Information to Shared Docs

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

                            ### Step 4.5B: Check for Existing Projects (REQUIRED)

                            **Before creating a new project**, search for existing projects:

                            Use search_projects:
                            - Search by project name extracted from plan
                            - Check if any results match

                            **If existing project found**:
                            Ask user: "I found an existing project '[name]' (UUID: [uuid], created: [date]).
                            Is this the same project you want to set up?"

                            - **If YES**:
                              - Skip project creation (Step 5B)
                              - Use the existing project UUID
                              - Jump to Step 8B (save existing UUID to local memory)
                              - Report: "Recovered existing project UUID to local memory"

                            - **If NO** (different project with same name):
                              - Ask user to provide a different name to avoid confusion
                              - Continue to Step 5B with new name

                            **If no existing project found**:
                            - Continue to Step 5B (create new project)

                            ### Step 5B: Create Lightweight Project Entity

                            **Create minimal project container** in Task Orchestrator:

                            Use create_project:
                            ```json
                            {
                              "name": "[Project name from plan]",
                              "summary": "[1-2 sentence essence from plan]",
                              "status": "planning",
                              "tags": "[tech-stack,project-type]"
                            }
                            ```

                            **Keep it simple**:
                            - Summary: Just the core value proposition
                            - Tags: Planned technology stack
                            - Status: "planning" for new projects
                            - **DO NOT create project sections** - info already in shared docs

                            ### Step 6B: Create Features from Plan

                            **Identify 3-7 core features** from project plan:

                            Ask user to confirm:
                            "From your plan, I identified these core features:
                            - [Feature 1]
                            - [Feature 2]
                            - [Feature 3]

                            Should I create these in Task Orchestrator?"

                            **For each confirmed feature**, use create_feature:

                            ```json
                            {
                              "name": "[Feature name from plan]",
                              "summary": "[What it will do and why it's needed]",
                              "status": "planning",
                              "priority": "[high for foundation, medium for enhancements]",
                              "projectId": "[project-id-from-step-5B]",
                              "templateIds": ["requirements-specification"],
                              "tags": "[feature-area,user-facing/internal]"
                            }
                            ```

                            **Minimal templates**:
                            - Use just "requirements-specification" to start
                            - Keeps overhead low for new projects

                            ### Step 7B: Create Foundation Tasks

                            **Create initial implementation tasks**:

                            **Git Detection**: Check for .git directory
                            - If exists: Include git workflow templates
                            - If not: Ask if user wants to initialize git

                            **Foundation tasks to create**:
                            ```json
                            {
                              "title": "Project Infrastructure Setup",
                              "summary": "Initialize repository, configure build tools, set up CI/CD",
                              "projectId": "[project-id]",
                              "priority": "high",
                              "complexity": 5,
                              "templateIds": ["task-implementation-workflow", "technical-approach"],
                              "tags": "task-type-infrastructure,setup,foundation"
                            }
                            ```

                            **If git detected**:
                            ```json
                            {
                              "title": "Establish Development Workflow",
                              "summary": "Set up branching strategy, PR process, commit conventions",
                              "projectId": "[project-id]",
                              "priority": "high",
                              "complexity": 3,
                              "templateIds": ["local-git-branching-workflow", "github-pr-workflow"],
                              "tags": "task-type-process,git-workflow"
                            }
                            ```

                            **Create 1-2 tasks per feature** for initial work:
                            ```json
                            {
                              "title": "[Specific implementation task]",
                              "summary": "[What to build with acceptance criteria]",
                              "featureId": "[feature-id]",
                              "projectId": "[project-id]",
                              "priority": "medium",
                              "complexity": "[3-6]",
                              "templateIds": ["task-implementation-workflow"],
                              "tags": "task-type-feature,[component]"
                            }
                            ```

                            ### Step 8B: Save Project UUID to Local Memory

                            **REQUIRED ACTION**: Write project UUID to local developer memory.

                            **Storage location** - Your AI agent's local memory:
                            - CLAUDE.local.md (Claude Code)
                            - .cursor/local (Cursor)
                            - .ai/local.config (fallback)

                            **Required format**:
                            ```markdown
                            ## Task Orchestrator - Project Context

                            Project UUID: [uuid-from-step-5B]
                            Project Name: [name]
                            Last synced: YYYY-MM-DD

                            Quick commands:
                            - View overview: get_overview()
                            - View project: get_project(id="[uuid]", includeFeatures=true, includeTasks=true)
                            ```

                            **File Writing Steps**:
                            1. Check if local config file exists
                            2. If exists: Check for "Task Orchestrator - Project Context" section
                            3. If section exists: Update UUID and timestamp
                            4. If section missing: Append at end
                            5. If file doesn't exist: Create with this section
                            6. **Verify**: Read file back to confirm write
                            7. **Report**: "Saved project UUID to [file-path]"

                            ### Step 9B: Ensure Local Memory is Gitignored

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

                            ### Step 10B: Completion

                            **Report to user**:
                            ```
                            ✅ New project setup complete!

                            Project: [name] ([uuid])
                            Documentation: [shared-doc-file] (committed to git)
                            UUID saved to: [local-file] (gitignored)
                            Features created: [count]
                            Foundation tasks created: [count]

                            Next steps:
                            - Run get_overview() to see your project structure
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

                            **Gitignore Discipline**:
                            - Always ensure local memory is gitignored
                            - Project UUIDs are developer-specific
                            - Each developer may use different AI tools
                            - Verify .gitignore before completing setup

                            This streamlined approach works for vibe coding and small teams, avoiding enterprise bureaucracy while maintaining effective project management.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for upgrading Task Orchestrator configuration files to latest versions
     * while preserving user customizations where possible.
     */
    private fun addUpdateProjectConfigPrompt(server: Server) {
        server.addPrompt(
            name = "update_project_config",
            description = "Upgrade Task Orchestrator configuration files (.taskorchestrator/) to latest versions with backup and customization preservation"
        ) { _ ->
            GetPromptResult(
                description = "Guided workflow for upgrading configuration files to latest versions",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Update Project Configuration Workflow

                            This workflow upgrades your Task Orchestrator configuration files (.taskorchestrator/) to the latest versions while preserving your customizations.

                            ## Step 1: Check Current Configuration Status

                            **Run setup_project tool** to detect current versions:

                            ```
                            setup_project()
                            ```

                            **Analyze the response**:
                            - Look for "hasOutdatedConfigs" field in response data
                            - If false: No updates needed, exit workflow
                            - If true: Continue to Step 2

                            **Response will show**:
                            ```
                            ⚠️  Configuration updates available:
                              - config.yaml: v1.0.0 → v2.0.0
                              - status-workflow-config.yaml: No version → v2.0.0
                              - agent-mapping.yaml: v2.0.0 (up to date)
                            ```

                            ## Step 2: Backup Current Configuration

                            **REQUIRED**: Create backup before any modifications.

                            **Backup steps**:
                            1. Create backup directory with timestamp:
                            ```bash
                            mkdir -p .taskorchestrator/backups
                            cp .taskorchestrator/config.yaml .taskorchestrator/backups/config.yaml.backup-YYYY-MM-DD-HHMMSS
                            cp .taskorchestrator/status-workflow-config.yaml .taskorchestrator/backups/status-workflow-config.yaml.backup-YYYY-MM-DD-HHMMSS
                            cp .taskorchestrator/agent-mapping.yaml .taskorchestrator/backups/agent-mapping.yaml.backup-YYYY-MM-DD-HHMMSS
                            ```

                            2. Verify backups created successfully
                            3. Report backup location to user

                            ## Step 3: Choose Upgrade Mode

                            **Present options to user**:

                            ```
                            Configuration Upgrade Options:

                            [1] Add New Files Only (SAFEST)
                                • Copies only missing config files
                                • Preserves all existing configurations
                                • No changes to files with versions
                                • Recommended: When you have customizations

                            [2] Full Reset to Defaults (DESTRUCTIVE)
                                • Overwrites ALL config files with latest defaults
                                • Loses all customizations
                                • Use when: Major version upgrade or config corruption
                                • ⚠️  Requires backup confirmation

                            [3] Cancel
                                • Keep existing configuration unchanged

                            Your choice: [1-3]
                            ```

                            ## Step 4: Execute Upgrade (Based on Choice)

                            ### Option [1]: Add New Files Only

                            **This is safe** - only creates missing files.

                            **Actions**:
                            1. For each outdated config file:
                               - If file doesn't exist: Copy from defaults
                               - If file exists: Skip (preserve user version)
                            2. Report which files were added vs skipped
                            3. User can manually merge updates if desired

                            **No tool calls needed** - setup_project already skips existing files.

                            **Report**:
                            ```
                            ✅ Upgrade complete (Add New Files mode)

                            Files added: (none, all files already present)
                            Files preserved: config.yaml, status-workflow-config.yaml, agent-mapping.yaml

                            Your customizations are intact. To adopt new features:
                            - Review latest defaults in source: src/main/resources/orchestration/
                            - Manually merge desired changes into your configs
                            - Backups available in: .taskorchestrator/backups/
                            ```

                            ### Option [2]: Full Reset to Defaults

                            **⚠️  DESTRUCTIVE** - overwrites all config files.

                            **Confirmation required**:
                            ```
                            ⚠️  WARNING: Full Reset Mode

                            This will OVERWRITE all configuration files with defaults:
                            - config.yaml
                            - status-workflow-config.yaml
                            - agent-mapping.yaml

                            All customizations will be LOST.
                            Backups created in: .taskorchestrator/backups/

                            Type 'RESET' to confirm or 'cancel' to abort: ___
                            ```

                            **If user confirms**:
                            1. Delete existing config files:
                            ```bash
                            rm .taskorchestrator/config.yaml
                            rm .taskorchestrator/status-workflow-config.yaml
                            rm .taskorchestrator/agent-mapping.yaml
                            ```

                            2. Run setup_project to recreate with defaults:
                            ```
                            setup_project()
                            ```

                            3. Verify new files created with latest versions

                            **Report**:
                            ```
                            ✅ Full reset complete

                            All configuration files reset to v2.0.0 defaults.
                            Backups preserved in: .taskorchestrator/backups/

                            If you need to restore customizations:
                            1. Review backups for your custom settings
                            2. Manually re-apply to new config files
                            3. Use config.yaml comments as guide for valid options
                            ```

                            ## Step 5: Verification

                            **Run setup_project again** to verify upgrade:

                            ```
                            setup_project()
                            ```

                            **Check response**:
                            - hasOutdatedConfigs should be false
                            - All config files should show latest version
                            - No warnings about updates

                            **If issues detected**:
                            - Restore from backups
                            - Report issue for troubleshooting

                            ## Step 6: Update Team Documentation (If Committed)

                            **If .taskorchestrator/ is committed to git**:

                            1. Review changes:
                            ```bash
                            git status
                            git diff .taskorchestrator/
                            ```

                            2. Commit updates (if appropriate):
                            ```bash
                            git add .taskorchestrator/
                            git commit -m "chore: upgrade Task Orchestrator config to v2.0.0"
                            ```

                            3. Notify team of config updates

                            ## Rollback Procedure (If Needed)

                            **If upgrade causes issues**, restore from backups:

                            ```bash
                            # Find latest backup
                            ls -lt .taskorchestrator/backups/

                            # Restore from backup (replace TIMESTAMP)
                            cp .taskorchestrator/backups/config.yaml.backup-TIMESTAMP .taskorchestrator/config.yaml
                            cp .taskorchestrator/backups/status-workflow-config.yaml.backup-TIMESTAMP .taskorchestrator/status-workflow-config.yaml
                            cp .taskorchestrator/backups/agent-mapping.yaml.backup-TIMESTAMP .taskorchestrator/agent-mapping.yaml
                            ```

                            ## Best Practices

                            **When to upgrade**:
                            - After Task Orchestrator MCP server update
                            - When setup_project reports outdated configs
                            - When you need new configuration features

                            **Before upgrading**:
                            - Commit current state to git (if tracked)
                            - Create manual backup
                            - Review changelog for breaking changes

                            **After upgrading**:
                            - Test basic operations (create task, update status)
                            - Review new config options and customize
                            - Update team documentation if needed

                            This workflow ensures safe configuration upgrades with minimal risk to your customizations.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for implementing work items (tasks, features, bugs) with AI memory-based workflow customization
     * and automatic git detection.
     */
    private fun addCoordinateFeatureDevelopmentPrompt(server: Server) {
        server.addPrompt(
            name = "coordinate_feature_development",
            description = "Coordinate feature development through four phases (Feature Creation, Task Breakdown, Task Execution, Feature Completion) using Skills for detailed guidance"
        ) { _ ->
            val workflowContent = try {
                this::class.java.classLoader
                    .getResourceAsStream("workflows/coordinate-feature-development.md")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    ?: "# Coordinate Feature Development\n\nWorkflow file not found. Please ensure coordinate-feature-development.md exists in src/main/resources/workflows/"
            } catch (e: Exception) {
                "# Coordinate Feature Development\n\nError loading workflow: ${e.message}"
            }

            GetPromptResult(
                description = "Lightweight phase coordinator for feature development - Skills provide detailed templates, examples, and error handling",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(text = workflowContent.trimIndent())
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}
