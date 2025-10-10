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
     */
    fun configureWorkflowPrompts(server: Server) {
        addInitializeTaskOrchestratorPrompt(server)
        addCreateFeatureWorkflowPrompt(server)
        addTaskBreakdownPrompt(server)
        addProjectSetupPrompt(server)
        addImplementationWorkflowPrompt(server)
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

                            ### Step 2: WRITE Guidelines to Permanent Storage

                            After reading resources, AI agents **MUST write** the key principles to persistent storage. This is a one-time setup that persists across all sessions.

                            **REQUIRED ACTION**: Write initialization results to a permanent file on disk.

                            **Recommended Storage** (in priority order):
                            1. **CLAUDE.md** (append to existing file if present) - Best for Claude Code
                            2. **.cursorrules** (create/append if using Cursor IDE)
                            3. **.github/copilot-instructions.md** (if using GitHub Copilot)
                            4. **Project-specific .ai/ directory** (create if none of above exist)

                            **Required Format** - Use a clearly marked section:
                            ```markdown
                            ## Task Orchestrator - AI Initialization

                            Last initialized: YYYY-MM-DD

                            ### Critical Patterns

                            **Template Discovery** (NEVER skip this step):
                            - Always: list_templates(targetEntityType, isEnabled=true)
                            - Never: Assume templates exist
                            - Apply: Use templateIds parameter during creation
                            - Filter: By targetEntityType (TASK or FEATURE) and isEnabled=true

                            **Session Start Routine**:
                            1. Run get_overview() first to understand current state
                            2. Check for in-progress tasks before starting new work
                            3. Review priorities and dependencies

                            **Intent Recognition Patterns**:
                            - "Create feature for X" → Feature creation with template discovery
                            - "Implement X" → Task creation with implementation templates
                            - "Fix bug X" → Bug triage with Bug Investigation template
                            - "Break down X" → Task decomposition pattern
                            - "Set up project" → Project setup workflow

                            **Dual Workflow Model**:
                            - Autonomous: For common tasks with clear intent (faster, natural)
                            - Explicit Workflows: For complex scenarios or learning (comprehensive)

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

                            **File Writing Steps**:
                            1. Check if CLAUDE.md (or equivalent) exists in project root
                            2. If exists: Read current content, check for existing initialization section
                            3. If section exists: Update with new timestamp
                            4. If section missing: Append new section at end of file
                            5. If no file exists: Create CLAUDE.md with initialization section
                            6. **Verify**: Read the file back to confirm successful write
                            7. **Report**: Tell user where initialization was saved (file path and section)

                            ### Step 3: Understand Dual Workflow Usage Model

                            Task Orchestrator supports two complementary modes:

                            **Autonomous Pattern Application**:
                            - AI recognizes user intent from natural language
                            - Applies appropriate patterns automatically
                            - Uses template discovery and selection autonomously
                            - Provides streamlined, efficient interaction
                            - Best for: Common tasks, clear intents, experienced users

                            **Explicit Workflow Invocation**:
                            - User invokes specific workflow prompts
                            - AI follows detailed step-by-step guidance
                            - Provides teaching and comprehensive coverage
                            - Best for: Complex scenarios, learning, edge cases

                            **Decision Framework**:
                            ```
                            User Request → Intent Recognition
                            ├─ Clear, common pattern? → Apply autonomously
                            ├─ Complex, multi-phase? → Suggest workflow prompt
                            ├─ Educational need? → Offer workflow prompt
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
                               ✅ Write initialization summary to CLAUDE.md (or equivalent)
                               ✅ Include timestamp for tracking (YYYY-MM-DD format)
                               ✅ Use the exact format from Step 2 "Required Format"
                               ✅ Read the file back to verify successful write

                            3. **Report to User**:
                               ✅ Tell user where initialization was saved (specific file path)
                               ✅ Show which section was added/updated
                               ✅ Confirm one-time setup is complete
                               ✅ Example: "Initialization complete. Added 'Task Orchestrator - AI Initialization' section to CLAUDE.md"

                            4. **Confirm Pattern Recognition**:
                               ✅ Demonstrate understanding by explaining how to handle a sample request
                               ✅ Show template discovery pattern in action

                            ## ⚠️ Common Mistakes to Avoid

                            **❌ WRONG: Storing guidelines only in session/conversational context**
                            - This is lost when the session ends
                            - Requires re-initialization every time
                            - Defeats the purpose of one-time setup
                            - Makes the initialization workflow fail its primary goal

                            **✅ CORRECT: Writing guidelines to CLAUDE.md or equivalent**
                            - Persists across all sessions
                            - Available to all AI agents working in this project
                            - True one-time initialization
                            - Verifiable by reading the file

                            **❌ WRONG: Creating a new separate file without checking existing files**
                            - Creates fragmentation
                            - AI might not find guidelines later
                            - Duplicates effort

                            **✅ CORRECT: Checking for and appending to existing CLAUDE.md**
                            - Consolidates all AI guidance in one location
                            - Standard location for project-wide AI instructions
                            - Easy to find and update

                            **❌ WRONG: Saying "I've stored this in my memory" without file write**
                            - Vague and unverifiable
                            - Doesn't create persistent storage
                            - Lost on next session

                            **✅ CORRECT: Reporting specific file path and verification**
                            - "Added Task Orchestrator initialization to D:\Projects\myapp\CLAUDE.md at line 45"
                            - Shows exactly where it was saved
                            - Can be verified by reading the file

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
    private fun addCreateFeatureWorkflowPrompt(server: Server) {
        server.addPrompt(
            name = "create_feature_workflow",
            description = "Guide for creating a comprehensive feature with templates, tasks, and proper organization"
        ) { _ ->
            GetPromptResult(
                description = "Step-by-step workflow for creating a feature with comprehensive documentation and tasks",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Create Feature Workflow

                            This workflow guides you through creating a comprehensive feature with proper templates, documentation, and associated tasks.

                            ## Step 1: Load Feature Creation Configuration from Memory

                            **Check your available memory systems** for feature creation configuration:

                            **Global Preferences** (user-wide):
                            - Default feature templates
                            - Feature tagging conventions
                            - Auto-task creation preferences

                            **Project Configuration** (team-specific):
                            - Team-specific default templates
                            - Project tag conventions
                            - Standard task structure

                            **Memory Configuration Schema** (what to look for):
                            ```
                            # Task Orchestrator - Feature Creation Configuration

                            ## Default Templates
                            feature_default_templates:
                              - "context-and-background"
                              - "requirements-specification"

                            ## Tag Conventions
                            feature_tag_prefix: "feature-"
                            feature_tags_auto_add: "needs-review,in-planning"

                            ## Auto-Task Creation
                            feature_create_initial_tasks: true
                            feature_initial_task_templates:
                              - "task-implementation-workflow"
                            ```

                            **If configuration found**:
                            - Use configured default templates
                            - Apply tag conventions automatically
                            - Auto-create initial tasks if enabled

                            **If NOT found**:
                            - Use manual template selection
                            - Ask user about tagging preferences
                            - Offer to save preferences for future

                            ## Step 2: Check Current State
                            Start by understanding the current project state:
                            ```
                            Use get_overview to see existing features and work
                            ```

                            ## Step 3: Find Appropriate Templates
                            Identify templates for your feature:
                            ```
                            Use list_templates with targetEntityType="FEATURE" and isEnabled=true
                            ```
                            Look for templates like:
                            - Context & Background (for business context)
                            - Requirements Specification (for detailed requirements)
                            - Technical Approach (for architecture planning)

                            ## Step 4: Create the Feature
                            Create your feature with configuration from memory (if available):

                            **If memory configuration found**:
                            ```json
                            Use create_feature with memory-based defaults:
                            {
                              "name": "[Descriptive feature name]",
                              "summary": "[Comprehensive summary with business value]",
                              "status": "planning",
                              "priority": "[high/medium/low based on importance]",
                              "templateIds": ["[templates-from-memory-or-step-3]"],
                              "tags": "[feature_tag_prefix][feature-name],[feature_tags_auto_add-from-memory]"
                            }
                            ```

                            **If NO memory configuration**:
                            ```json
                            Use create_feature with manual template selection:
                            {
                              "name": "[Descriptive feature name]",
                              "summary": "[Comprehensive summary with business value]",
                              "status": "planning",
                              "priority": "[high/medium/low based on importance]",
                              "templateIds": ["context-template-id", "requirements-template-id"],
                              "tags": "[functional-area,technical-stack,business-impact]"
                            }
                            ```

                            **Tag Convention Examples**:
                            - With prefix "feature-": `"feature-authentication,needs-review,in-planning"`
                            - Without prefix: `"authentication,api,security,needs-review"`

                            ## Step 5: Review Created Structure
                            Examine the feature with its sections:
                            ```
                            Use get_feature with includeSections=true to see the template structure
                            ```

                            ## Step 6: Create Associated Tasks
                            **Git Detection**: Check for .git directory in project root using file system tools

                            **If memory configuration found with feature_create_initial_tasks=true**:
                            ```json
                            Automatically create foundation tasks using feature_initial_task_templates:
                            {
                              "title": "[Implementation task from template]",
                              "summary": "[Task description with acceptance criteria]",
                              "featureId": "[feature-id-from-step-4]",
                              "complexity": "[1-10 based on effort estimate]",
                              "priority": "[based on implementation order]",
                              "templateIds": ["[templates-from-memory]", "local-git-branching-workflow"],
                              "tags": "[inherit-feature-tags],[task-specific-tags]"
                            }
                            ```

                            **If NO memory configuration OR feature_create_initial_tasks=false**:
                            ```json
                            Manually create tasks for each major component:
                            {
                              "title": "[Specific implementation task]",
                              "summary": "[Clear task description with acceptance criteria]",
                              "featureId": "[feature-id-from-step-4]",
                              "complexity": "[1-10 based on effort estimate]",
                              "priority": "[based on implementation order]",
                              "templateIds": ["task-implementation-workflow", "local-git-branching-workflow", "technical-approach"],
                              "tags": "[task-type-feature,component-type,technical-area]"
                            }
                            ```

                            **Template Selection Notes**:
                            - If git detected, automatically include "local-git-branching-workflow" template
                            - Ask user: "Do you use GitHub/GitLab PRs? If yes, I can also apply PR workflow template"
                            - For complex tasks (complexity > 6): Include "technical-approach" template
                            - Apply tag conventions from memory to tasks (inherit feature tags + task-specific tags)

                            ## Step 7: Establish Dependencies (if needed)
                            Link related tasks:
                            ```
                            Use create_dependency to establish task relationships
                            ```

                            ## Step 8: Final Review
                            Verify the complete feature structure:
                            ```
                            Use get_feature with includeTasks=true and includeSections=true
                            ```

                            ## Saving Memory Configuration

                            **If user expressed preferences during workflow**:
                            Offer to save configuration for future features:
                            ```
                            "I noticed you prefer [templates/tags/auto-task-creation].
                             Would you like me to save these preferences to your memory
                             for future feature creation workflows?

                             This will automatically:
                             - Apply these templates to new features
                             - Use your tag naming conventions
                             - Auto-create initial tasks if enabled

                             Save to: [Global memory / Project CLAUDE.md / .cursorrules]"
                            ```

                            **Configuration to save**:
                            ```markdown
                            # Task Orchestrator - Feature Creation Configuration

                            ## Default Templates
                            feature_default_templates:
                              - "[template-1-id]"
                              - "[template-2-id]"

                            ## Tag Conventions
                            feature_tag_prefix: "[prefix-or-empty]"
                            feature_tags_auto_add: "[comma-separated-tags]"

                            ## Auto-Task Creation
                            feature_create_initial_tasks: [true/false]
                            feature_initial_task_templates:
                              - "[task-template-id]"
                            ```

                            ## Best Practices
                            - Use descriptive names that clearly indicate functionality
                            - Include business value and user impact in summaries
                            - Apply multiple templates for comprehensive coverage
                            - Create tasks that are appropriately sized (complexity 3-7)
                            - Use consistent tagging conventions
                            - Establish clear acceptance criteria in task summaries

                            This workflow ensures your feature is well-documented, properly organized, and ready for implementation.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for breaking down complex tasks into manageable subtasks.
     */
    private fun addTaskBreakdownPrompt(server: Server) {
        server.addPrompt(
            name = "task_breakdown_workflow",
            description = "Guide for breaking down complex tasks into manageable, well-organized subtasks"
        ) { _ ->
            GetPromptResult(
                description = "Systematic approach to decomposing complex tasks into manageable work items",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Task Breakdown Workflow

                            This workflow helps you systematically break down complex tasks into manageable, well-organized subtasks.

                            ## Step 1: Analyze the Complex Task
                            Start by understanding the scope:
                            ```
                            Use get_task with includeSections=true to review the complex task
                            ```
                            Look for:
                            - Scope that spans multiple technical areas
                            - Complexity rating above 7
                            - Multiple acceptance criteria
                            - Dependencies on different systems or teams

                            ## Step 2: Identify Natural Boundaries
                            Common breakdown patterns:
                            - **By Component**: Frontend, Backend, Database, Testing
                            - **By Feature Area**: Authentication, Data Processing, UI Components
                            - **By Phase**: Research, Design, Implementation, Testing, Documentation
                            - **By Skill Set**: Backend API, Frontend UI, DevOps, QA

                            ## Step 3: Create a Feature Container (if beneficial)
                            For task breakdowns with 4+ subtasks:
                            ```json
                            Use create_feature to group related subtasks:
                            {
                              "name": "[Original task name as feature]",
                              "summary": "[Expanded description from original task]",
                              "templateIds": ["context-background", "requirements-specification"]
                            }
                            ```

                            ## Step 4: Create Focused Subtasks
                            **Git Detection**: Check for .git directory in project root using file system tools
                            
                            For each identified component:
                            ```json
                            Use create_task for each subtask:
                            {
                              "title": "[Specific, actionable task name]",
                              "summary": "[Clear scope with specific acceptance criteria]",
                              "featureId": "[from step 3, if created]",
                              "complexity": "[3-6 for manageable tasks]",
                              "priority": "[based on implementation dependencies]",
                              "templateIds": ["task-implementation-workflow", "local-git-branching-workflow"],
                              "tags": "[original-tags,component-type,implementation-area]"
                            }
                            ```
                            
                            **Template Selection Notes**:
                            - If git detected, automatically include "local-git-branching-workflow" template

                            ## Step 5: Establish Task Dependencies
                            Link subtasks with proper sequencing:
                            ```
                            Use create_dependency to establish implementation order
                            Example: Database schema BLOCKS API implementation
                            ```

                            ## Step 6: Update Original Task
                            Transform the original complex task:
                            ```json
                            Use update_task to modify the original:
                            {
                              "id": "[original-task-id]",
                              "title": "[Updated to reflect coordination role]",
                              "summary": "[Reference to subtasks and overall coordination]",
                              "complexity": "[Reduced to 2-3 for coordination]",
                              "featureId": "[link to feature if created]"
                            }
                            ```

                            ## Breakdown Quality Guidelines

                            **Good Subtask Characteristics**:
                            - Complexity rating 3-6 (manageable in 1-3 days)
                            - Single clear responsibility
                            - Specific, testable acceptance criteria
                            - Minimal dependencies on other subtasks
                            - Can be assigned to one person or skill set

                            **Common Breakdown Patterns**:

                            **API Development**:
                            1. Database schema design
                            2. Core API endpoints implementation
                            3. Authentication/authorization integration
                            4. Input validation and error handling
                            5. API documentation and testing

                            **UI Feature**:
                            1. Component design and wireframes
                            2. Core component implementation
                            3. State management integration
                            4. Styling and responsive design
                            5. User testing and accessibility

                            **Integration Feature**:
                            1. External service research and setup
                            2. Authentication/connection implementation
                            3. Data mapping and transformation
                            4. Error handling and retry logic
                            5. Testing and monitoring setup

                            ## Step 7: Review the Breakdown
                            Validate your breakdown:
                            ```
                            Use get_feature (if created) or search_tasks to review all subtasks
                            ```
                            Ensure:
                            - Total complexity is manageable (sum of subtasks ≈ original complexity)
                            - Dependencies make sense and don't create cycles
                            - Each subtask has clear, actionable acceptance criteria
                            - Subtasks can be worked on in parallel where possible

                            This systematic approach transforms overwhelming tasks into manageable, well-organized work items.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }


    /**
     * Adds an AI-agnostic prompt for setting up projects with Task Orchestrator.
     * Works with existing codebases or new projects, using AI's native memory systems.
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

                            ## Step 2: Check Task Orchestrator Initialization

                            **Verify AI initialization** before proceeding:

                            Check your memory for "Task Orchestrator - AI Initialization" section:
                            - If found: Continue to scenario-specific steps
                            - If NOT found: Recommend running initialize_task_orchestrator first

                            **Why**: Initialization ensures you understand template discovery, intent recognition, and workflow patterns.

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
     * Adds a prompt for implementing work items (tasks, features, bugs) with AI memory-based workflow customization
     * and automatic git detection.
     */
    private fun addImplementationWorkflowPrompt(server: Server) {
        server.addPrompt(
            name = "implementation_workflow",
            description = "Intelligent implementation workflow for tasks, features, and bugs with AI memory-based customization and automatic git detection"
        ) { _ ->
            GetPromptResult(
                description = "Intelligent feature implementation guidance with automatic project context detection and workflow template suggestion",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Implementation Workflow

                            This workflow provides intelligent implementation guidance for tasks, features, and bugs with AI memory-based customization and automatic project context detection.

                            ## Step 1: Load Workflow Configuration from Memory

                            **Check your available memory systems** for workflow configuration:

                            **Global Preferences** (user-wide):
                            - Git provider preference
                            - PR/MR usage preference (always/never/ask)
                            - Default workflows

                            **Project Configuration** (team-specific):
                            - Team workflow requirements
                            - Branch naming conventions
                            - Testing and validation requirements

                            **Use whatever memory mechanism you support**:
                            - Don't hardcode file paths
                            - Use your native memory capabilities (CLAUDE.md, global memory, etc.)
                            - Store structured configuration data

                            **Memory Configuration Schema** (what to look for):
                            ```
                            # Task Orchestrator - Implementation Workflow Configuration

                            ## Pull Request Preference
                            use_pull_requests: "always" | "never" | "ask"

                            ## Branch Naming Conventions (optional - defaults provided)
                            branch_naming_bug: "bugfix/{task-id-short}-{description}"
                            branch_naming_feature: "feature/{task-id-short}-{description}"
                            branch_naming_hotfix: "hotfix/{task-id-short}-{description}"
                            branch_naming_enhancement: "enhancement/{task-id-short}-{description}"

                            ## Custom Workflow Steps (optional - leave empty to use templates)
                            ### Bug Fix Workflow Override
                            # [Custom steps override Bug Investigation template procedural guidance]
                            # Template validation requirements still apply

                            ### Feature Workflow Override
                            # [Custom steps override template procedural guidance]

                            ### Additional Validation Requirements
                            # [Extra requirements beyond template validation]
                            ```

                            **If configuration found**:
                            - Use configured preferences and workflow steps
                            - Adapt to work type (bug/feature/enhancement)
                            - Apply team-specific validation

                            **If NOT found**:
                            - Use sensible defaults
                            - Offer to set up preferences during workflow
                            - Ask: "Should I remember these preferences globally or just for this project?"

                            ## Step 2: Check Current State & Git Detection
                            Start by understanding project context:
                            ```
                            Use get_overview to understand project context and current work priorities
                            ```
                            - Review existing in-progress tasks to avoid conflicts
                            - Use file system tools to detect if project uses git (check for .git directory)
                            - If git detected, automatically suggest "Local Git Branching Workflow" template

                            ## Step 3: Understand the Work & Detect Type

                            Use get_task (or get_feature) to retrieve work details:
                            - **Detect work type** from tags:
                              - `task-type-bug` → Bug workflow
                              - `task-type-feature` → Feature workflow
                              - `task-type-enhancement` → Enhancement workflow
                              - `task-type-hotfix` → Hotfix workflow
                            - Load applied templates with get_sections
                            - Review template guidance for this work type

                            **For Bugs** (task-type-bug detected):
                            - Check if "Bug Investigation Workflow" template is applied
                            - If NOT applied:
                              ```
                              Offer to apply Bug Investigation template:
                              "This is a bug fix. I recommend applying the Bug Investigation Workflow template
                               to guide systematic investigation and ensure proper root cause analysis.

                               Apply template now? (This adds sections for: Problem Analysis, Technical Investigation,
                               Root Cause, Impact Assessment, Resolution Plan)"
                              ```
                            - If applied, verify investigation sections are documented:
                              - Problem symptoms and reproduction steps
                              - Root cause analysis
                              - Impact assessment
                            - **Before implementation**: Ensure root cause is documented
                            - If investigation incomplete, guide through it first before implementing fix

                            ## Step 4: Determine Workflow Steps

                            **Check for custom workflow override in memory**:

                            If memory contains custom steps for this work type (e.g., "Bug Fix Workflow Override"):
                              ✓ Use custom steps from memory
                              ✗ Template procedural sections provide context only

                            If NO custom override:
                              ✓ Use template sections as workflow steps
                              ✓ Follow template procedural guidance

                            **What's Always Used** (never overridden):
                            - ✅ Validation requirements from templates
                            - ✅ Acceptance criteria and definition of done
                            - ✅ Testing requirements and quality gates
                            - ✅ Technical context and background information

                            **What Can Be Overridden** (custom workflow steps replace):
                            - ⚠️ Step-by-step implementation instructions
                            - ⚠️ Procedural workflow guidance (how to execute)
                            - ⚠️ Tool invocation sequences

                            ## Step 5: Execute Implementation

                            **Follow the selected workflow** (custom override OR template guidance):

                            1. Update task status to "in-progress"
                            2. Execute each step from workflow source
                            3. Apply any additional validations from memory
                            4. Make incremental commits with descriptive messages

                            **For Bugs**: Follow bug-specific implementation approach:
                            1. **Reproduce the bug** in tests first (test should fail with current code)
                            2. **Document reproduction** steps in code comments or task sections
                            3. **Implement the fix** addressing the root cause
                            4. **Verify test passes** with the fix applied
                            5. **Create regression tests** (see Regression Testing Requirements below)

                            ---

                            ## Regression Testing Requirements (MANDATORY for Bugs)

                            When fixing bugs, you **MUST** create comprehensive regression tests to prevent the issue from recurring.

                            ### Required Test Types

                            **1. Bug Reproduction Test** (Required)
                            - Test that **fails with the old code** and **passes with the fix**
                            - Reproduces the exact conditions that caused the bug
                            - Documents what broke and how it was fixed

                            **Example**:
                            ```kotlin
                            @Test
                            fun `should handle null user token without NPE - regression for AUTH-70490b4d`() {
                                // BUG: User logout crashed when token was null
                                // ROOT CAUSE: user.token.invalidate() called without null check
                                // FIX: Changed to user.token?.invalidate()

                                val user = User(token = null)
                                assertDoesNotThrow {
                                    authService.logout(user)
                                }
                            }
                            ```

                            **2. Edge Case Tests** (Required if applicable)
                            - Test the boundary conditions that led to the bug
                            - Cover scenarios that weren't previously tested
                            - Validate fix doesn't break related functionality

                            **Example**:
                            ```kotlin
                            @Test
                            fun `should handle empty token string - edge case for AUTH-70490b4d`() {
                                val user = User(token = "")
                                assertDoesNotThrow { authService.logout(user) }
                            }

                            @Test
                            fun `should handle whitespace-only token - edge case for AUTH-70490b4d`() {
                                val user = User(token = "   ")
                                assertDoesNotThrow { authService.logout(user) }
                            }
                            ```

                            **3. Integration Tests** (Required if bug crossed component boundaries)
                            - Test the interaction between components where bug occurred
                            - Verify fix works in realistic scenarios
                            - Ensure no cascading failures

                            **Example**:
                            ```kotlin
                            @Test
                            fun `logout flow should complete when user has no active session`() {
                                // This bug affected the full logout flow
                                val user = createUserWithoutSession()

                                val result = authService.logout(user)

                                assertEquals(LogoutResult.SUCCESS, result.status)
                                verifySessionCleaned(user)
                                verifyAuditLogCreated(user, "logout")
                            }
                            ```

                            **4. Performance/Load Tests** (Required if bug was performance-related)
                            - Verify fix doesn't introduce performance regressions
                            - Test under load if original bug was load-related
                            - Measure and assert performance metrics

                            **Example**:
                            ```kotlin
                            @Test
                            fun `logout should complete within 100ms even with null token`() {
                                val user = User(token = null)

                                val duration = measureTimeMillis {
                                    authService.logout(user)
                                }

                                assertTrue(duration < 100, "Logout took ${'$'}{duration}ms, expected < 100ms")
                            }
                            ```

                            ### Test Documentation Requirements

                            Every regression test MUST include:

                            1. **Test Name**: Clearly describe what's being tested and reference task ID
                               - Format: `should [expected behavior] - regression for [TASK-ID-SHORT]`
                               - Example: `should handle null token - regression for AUTH-70490b4d`

                            2. **Bug Comment**: Explain what broke, root cause, and fix
                               ```kotlin
                               // BUG: [What went wrong and user impact]
                               // ROOT CAUSE: [Technical reason for the bug]
                               // FIX: [What code change fixed it]
                               ```

                            3. **Test Assertions**: Verify both:
                               - Bug condition doesn't cause failure
                               - Expected behavior occurs correctly

                            ### Validation Checklist

                            Before marking bug fix complete, verify:
                            - ✅ Bug reproduction test exists and fails on old code
                            - ✅ Bug reproduction test passes with fix
                            - ✅ Edge cases identified and tested
                            - ✅ Integration tests added if bug crossed boundaries
                            - ✅ Performance tests added if relevant
                            - ✅ All tests have proper documentation comments
                            - ✅ Test names reference task ID for traceability
                            - ✅ Code coverage increased for affected code paths

                            ### Common Regression Test Patterns

                            **Null/Empty Input Bugs**:
                            ```kotlin
                            @Test
                            fun `should handle null input - regression for TASK-12345`() {
                                assertDoesNotThrow { service.process(null) }
                            }
                            ```

                            **Race Condition Bugs**:
                            ```kotlin
                            @Test
                            fun `should handle concurrent access - regression for TASK-67890`() {
                                val threads = (1..10).map {
                                    thread { service.processRequest(it) }
                                }
                                threads.forEach { it.join() }
                                // Verify no corruption occurred
                            }
                            ```

                            **Boundary Value Bugs**:
                            ```kotlin
                            @Test
                            fun `should handle maximum value - regression for TASK-11111`() {
                                val result = service.calculate(Int.MAX_VALUE)
                                assertTrue(result.isSuccess)
                            }
                            ```

                            **State Management Bugs**:
                            ```kotlin
                            @Test
                            fun `should handle state transition - regression for TASK-22222`() {
                                service.initialize()
                                service.stop()
                                service.initialize() // Bug: second init failed
                                assertTrue(service.isRunning)
                            }
                            ```

                            ---

                            ## Step 6: Git Workflow (if applicable)

                            **Use memory overrides if specified, otherwise use defaults**:

                            **Branch naming**:
                            - Memory has custom branch_naming? → Use that pattern with variable expansion
                            - No override? → Use default: `{work-type}/{task-id-short}-{description}`
                            - Variables: {task-id}, {task-id-short}, {description}, {priority}, {complexity}

                            **Example variable expansion**:
                            - Pattern: `bugfix/{task-id-short}-{description}`
                            - Task ID: `3bd10691-f40a-4d30-8fa6-02d00b666305`
                            - Title: "Fix Docker CVE issues"
                            - Result: `bugfix/3bd10691-fix-docker-cve-issues`

                            **PR/MR Decision**:
                            - Use memory preference (use_pull_requests)
                            - If "always" → Create PR automatically
                            - If "never" → Push directly
                            - If "ask" or not set → Ask user, offer to remember preference

                            **PR/MR Creation**:
                            - Auto-detect GitHub MCP server → use `gh pr create`
                            - Auto-detect GitLab MCP server → use `glab mr create`
                            - No MCP available → guide manual creation

                            ## Step 7: Validation

                            **Combine template validation + memory validation**:

                            1. **Template requirements** (from applied templates)
                            2. **Additional validation from memory** (if specified)
                            3. Both must pass before marking complete

                            **Before marking work as completed**:
                            ```
                            Use get_sections to read all task/feature sections
                            ```

                            **For Bugs** (MANDATORY - see Regression Testing Requirements above):
                            - ✅ Root cause documented in task sections or code comments
                            - ✅ Bug investigation sections complete (if Bug Investigation template used)
                            - ✅ **Bug reproduction test exists** (must fail with old code, pass with fix)
                            - ✅ **Regression tests created** for all scenarios:
                              - Edge cases that led to the bug
                              - Integration tests if bug crossed boundaries
                              - Performance tests if performance-related
                            - ✅ **Test documentation complete** (BUG/ROOT CAUSE/FIX comments in tests)
                            - ✅ **Test names reference task ID** for traceability
                            - ✅ All tests passing
                            - ✅ Fix verified in affected scenarios
                            - ✅ Code coverage increased for affected code paths

                            **CRITICAL**: Do NOT mark bug fix as completed without regression tests. If user attempts to complete without tests, remind them of regression testing requirements and offer to help create them.

                            **For Features**:
                            - ✅ All feature tasks completed
                            - ✅ Feature requirements satisfied
                            - ✅ Integration testing done
                            - ✅ Feature sections complete

                            **For All Work**:
                            - ✅ Template guidance followed (validation requirements)
                            - ✅ Tests passing
                            - ✅ Additional memory validations met (if specified)
                            - ✅ Git workflow completed (if applicable)

                            ## Step 8: Complete

                            Mark work as completed only after all validation passes:
                            ```
                            Use update_task to set status="completed"
                            ```

                            ---

                            ## Memory Configuration Setup Helper

                            **If no configuration found during workflow, offer setup**:

                            "I don't have workflow preferences stored. Let me help you set this up:

                            1. Do you use pull requests? (yes/no/ask each time)
                            2. Any custom branch naming? (or use defaults)
                            3. Team-specific workflow steps? (or use templates)

                            Should I remember these preferences:
                            A) Globally (for all your projects)
                            B) Just for this project
                            C) Don't save (ask each time)"

                            **Save preferences using your memory mechanism** (CLAUDE.md, global memory, etc.)

                            ---

                            ## Branch Naming Variable System

                            ### Available Variables

                            Use these standardized placeholders in branch naming patterns:

                            | Variable | Description | Example Value |
                            |----------|-------------|---------------|
                            | `{task-id}` | Full task UUID | `70490b4d-f412-4c20-93f1-cacf038a2ee8` |
                            | `{task-id-short}` | First 8 characters of UUID | `70490b4d` |
                            | `{description}` | Sanitized task title | `rename-workflow-add-memory` |
                            | `{feature-id}` | Feature UUID (if task belongs to feature) | `a3d0ab76-d93d-455c-ba54-459476633a3f` |
                            | `{feature-id-short}` | First 8 characters of feature UUID | `a3d0ab76` |
                            | `{priority}` | Task priority | `high`, `medium`, `low` |
                            | `{complexity}` | Task complexity rating | `1` through `10` |
                            | `{type}` | Work type detected from tags | `bug`, `feature`, `enhancement`, `hotfix` |

                            ### Description Sanitization Rules

                            The `{description}` variable applies these transformations to the task title:
                            1. Convert to lowercase
                            2. Replace spaces with hyphens
                            3. Remove special characters (keep only alphanumeric and hyphens)
                            4. Collapse multiple hyphens to single hyphen
                            5. Trim leading/trailing hyphens
                            6. Truncate to 50 characters if longer

                            **Examples**:
                            - `"Fix Authentication Bug"` → `fix-authentication-bug`
                            - `"Add User Profile (with avatar)"` → `add-user-profile-with-avatar`
                            - `"Update API: Version 2.0"` → `update-api-version-2-0`

                            ### Default Branch Naming Patterns

                            If no custom configuration exists in memory, use these defaults:

                            | Work Type | Pattern | Example |
                            |-----------|---------|---------|
                            | Bug | `bugfix/{task-id-short}-{description}` | `bugfix/70490b4d-fix-auth-error` |
                            | Feature | `feature/{task-id-short}-{description}` | `feature/a2a36aeb-user-profile` |
                            | Hotfix | `hotfix/{task-id-short}-{description}` | `hotfix/12bf786d-security-patch` |
                            | Enhancement | `enhancement/{task-id-short}-{description}` | `enhancement/6d115591-improve-performance` |

                            ### Custom Pattern Examples

                            **Team-Specific Conventions**:

                            ```
                            # Jira-style with ticket numbers
                            branch_naming_bug: "bugfix/PROJ-{task-id-short}-{description}"
                            branch_naming_feature: "feature/PROJ-{task-id-short}-{description}"

                            # Linear-style
                            branch_naming_bug: "{type}/{description}-{task-id-short}"
                            branch_naming_feature: "{type}/{description}-{task-id-short}"

                            # Priority-based
                            branch_naming_hotfix: "hotfix/{priority}-{description}"
                            branch_naming_bug: "bug/{priority}-{complexity}-{description}"

                            # Feature-grouped
                            branch_naming_feature: "feature/{feature-id-short}/{description}"
                            ```

                            ### Variable Expansion Process

                            When creating a branch:
                            1. **Retrieve task details** using `get_task`
                            2. **Detect work type** from task tags (`task-type-bug`, `task-type-feature`, etc.)
                            3. **Load branch naming pattern** from memory for the detected type
                            4. **Extract variable values** from task data
                            5. **Apply sanitization** to description
                            6. **Replace all placeholders** with actual values
                            7. **Validate result** (no special chars, valid git branch name)

                            **Example Expansion**:
                            ```
                            Task: "Implement OAuth2 Authentication"
                            ID: 70490b4d-f412-4c20-93f1-cacf038a2ee8
                            Type: feature
                            Priority: high
                            Complexity: 8

                            Pattern: feature/{task-id-short}-{description}
                            Result: feature/70490b4d-implement-oauth2-authentication
                            ```

                            ### Commit Message Variables

                            Same variables can be used for commit message prefixes:
                            ```
                            commit_message_prefix: "[{type}/{task-id-short}]"

                            Result: "[feature/70490b4d] feat: add OAuth2 authentication"
                            ```

                            ---

                            This workflow provides simple defaults with full customization through AI memory, enabling team-specific workflows without code changes.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}