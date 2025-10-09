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

                            ### Step 2: Store Guidelines in AI's Memory System

                            After reading resources, AI agents should store the key principles and patterns in whatever memory mechanism they support:

                            **Memory Storage Options** (AI-dependent):
                            - Project-specific instruction files (e.g., CLAUDE.md, .cursorrules, .ai/*)
                            - Memory MCP servers or similar persistent storage
                            - Conversational context and session memory
                            - AI-specific configuration systems
                            - Any other AI-native memory mechanism

                            **What to Store**:
                            - Key decision frameworks from usage-overview
                            - Template discovery patterns from template-strategy
                            - Intent recognition categories from task-management
                            - Pattern application guidelines
                            - Dual workflow model principles

                            **Storage Best Practices**:
                            - Summarize key principles rather than full text
                            - Focus on actionable patterns and decision trees
                            - Include examples of common intent → action mappings
                            - Reference resource URIs for detailed lookup when needed
                            - Store workflow invocation triggers and scenarios

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

                            ### Step 6: Initialization Confirmation

                            After completing initialization, AI agents should:

                            1. **Confirm Resource Reading**:
                               Verify all 4 guideline resources were successfully read and understood

                            2. **Confirm Memory Storage**:
                               Verify key principles stored in available memory mechanism

                            3. **Confirm Pattern Recognition**:
                               Demonstrate understanding by explaining how to handle a sample request

                            4. **Ready for Use**:
                               Signal readiness to use Task Orchestrator tools effectively

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

                            ## Step 1: Check Current State
                            Start by understanding the current project state:
                            ```
                            Use get_overview to see existing features and work
                            ```

                            ## Step 2: Find Appropriate Templates
                            Identify templates for your feature:
                            ```
                            Use list_templates with targetEntityType="FEATURE" and isEnabled=true
                            ```
                            Look for templates like:
                            - Context & Background (for business context)
                            - Requirements Specification (for detailed requirements)
                            - Technical Approach (for architecture planning)

                            ## Step 3: Create the Feature
                            Create your feature with templates:
                            ```json
                            Use create_feature with:
                            {
                              "name": "[Descriptive feature name]",
                              "summary": "[Comprehensive summary with business value]",
                              "status": "planning",
                              "priority": "[high/medium/low based on importance]",
                              "templateIds": ["context-template-id", "requirements-template-id"],
                              "tags": "[functional-area,technical-stack,business-impact]"
                            }
                            ```

                            ## Step 4: Review Created Structure
                            Examine the feature with its sections:
                            ```
                            Use get_feature with includeSections=true to see the template structure
                            ```

                            ## Step 5: Create Associated Tasks
                            **Git Detection**: Check for .git directory in project root using file system tools
                            
                            Break down the feature into specific tasks:
                            ```json
                            For each major component, use create_task with:
                            {
                              "title": "[Specific implementation task]",
                              "summary": "[Clear task description with acceptance criteria]",
                              "featureId": "[feature-id-from-step-3]",
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

                            ## Step 6: Establish Dependencies (if needed)
                            Link related tasks:
                            ```
                            Use create_dependency to establish task relationships
                            ```

                            ## Step 7: Final Review
                            Verify the complete feature structure:
                            ```
                            Use get_feature with includeTasks=true and includeSections=true
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
     * Adds a prompt for setting up a new project with proper structure and organization.
     */
    private fun addProjectSetupPrompt(server: Server) {
        server.addPrompt(
            name = "project_setup_workflow",
            description = "Complete guide for setting up a new project with proper structure, features, and initial tasks"
        ) { _ ->
            GetPromptResult(
                description = "Comprehensive workflow for initializing a new project with optimal organization",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Project Setup Workflow

                            This workflow guides you through setting up a new project with proper structure, comprehensive documentation, and effective organization for long-term success.

                            ## Step 1: Initialize AI Environment
                            **Before creating the project, set up AI guidelines for optimal task orchestrator usage.**

                            If you haven't already initialized Task Orchestrator guidelines in this AI session:
                            ```
                            Invoke the initialize_task_orchestrator prompt to set up guidelines
                            ```

                            **Why This Matters**:
                            - Ensures AI understands best practices for task/feature creation
                            - Enables intelligent template discovery and application
                            - Provides natural language pattern recognition throughout project
                            - Establishes proper workflow integration from the start
                            - Optimizes task orchestrator usage for entire project lifecycle

                            **What It Does**:
                            - Guides reading all guideline resources (usage, templates, workflows, patterns)
                            - Sets up guidelines in AI's memory system
                            - Teaches dual workflow model (autonomous vs explicit)
                            - Prepares AI for effective project management

                            **Note**: If already initialized in current session, skip to Step 2.

                            ## Step 2: Project Foundation
                            Create the top-level project container:
                            ```json
                            Use create_project:
                            {
                              "name": "[Descriptive project name]",
                              "summary": "[Comprehensive project description with goals, scope, and success criteria]",
                              "status": "planning",
                              "tags": "[project-type,technology-stack,business-domain,timeline]"
                            }
                            ```

                            **Project Summary Best Practices**:
                            - Include business objectives and user value
                            - Define scope boundaries (what's included/excluded)
                            - Mention key technologies and constraints
                            - State success criteria and completion definition

                            ## Step 3: Project Documentation Structure
                            Add comprehensive project documentation:
                            ```json
                            Use bulk_create_sections for project-level documentation:
                            {
                              "sections": [
                                {
                                  "entityType": "PROJECT",
                                  "entityId": "[project-id]",
                                  "title": "Project Charter",
                                  "usageDescription": "High-level project goals, stakeholders, and success criteria",
                                  "content": "[Business goals, target users, key stakeholders, success metrics]",
                                  "ordinal": 0,
                                  "tags": "charter,goals,stakeholders"
                                },
                                {
                                  "entityType": "PROJECT",
                                  "entityId": "[project-id]",
                                  "title": "Technical Architecture",
                                  "usageDescription": "Overall system architecture and technology decisions",
                                  "content": "[Architecture overview, technology stack, key design decisions]",
                                  "ordinal": 1,
                                  "tags": "architecture,technical,decisions"
                                },
                                {
                                  "entityType": "PROJECT",
                                  "entityId": "[project-id]",
                                  "title": "Development Standards",
                                  "usageDescription": "Coding standards, workflows, and quality requirements",
                                  "content": "[Coding guidelines, git workflow, testing requirements, review process]",
                                  "ordinal": 2,
                                  "tags": "standards,workflow,quality"
                                }
                              ]
                            }
                            ```

                            ## Step 4: Feature Planning and Structure
                            Identify and create major features:

                            **Feature Identification Strategy**:
                            - Break project into 3-7 major functional areas
                            - Each feature should represent cohesive user functionality
                            - Features should be independently deliverable when possible
                            - Consider technical architecture boundaries

                            **Create Core Features**:
                            ```json
                            Use create_feature for each major area:
                            {
                              "name": "[Feature name representing user functionality]",
                              "summary": "[Feature description with user value and technical scope]",
                              "status": "planning",
                              "priority": "[high for core features, medium for enhancements]",
                              "projectId": "[project-id]",
                              "templateIds": ["context-background", "requirements-specification"],
                              "tags": "[feature-type,user-facing/internal,complexity-level]"
                            }
                            ```

                            ## Step 5: Initial Task Creation
                            **Git Detection**: Check for .git directory in project root using file system tools
                            
                            Create foundational tasks for project setup:

                            **Infrastructure and Setup Tasks**:
                            ```json
                            Use create_task for project foundation:
                            {
                              "title": "Project Infrastructure Setup",
                              "summary": "Set up development environment, CI/CD, and project tooling",
                              "projectId": "[project-id]",
                              "priority": "high",
                              "complexity": 6,
                              "templateIds": ["task-implementation-workflow", "local-git-branching-workflow", "technical-approach"],
                              "tags": "task-type-infrastructure,setup,foundation"
                            }
                            ```

                            **Research and Planning Tasks**:
                            ```json
                            {
                              "title": "[Technology/Approach] Research",
                              "summary": "Research and validate [specific technology or approach] for [specific use case]",
                              "projectId": "[project-id]",
                              "priority": "high",
                              "complexity": 4,
                              "templateIds": ["technical-approach"],
                              "tags": "task-type-research,planning,technology-validation"
                            }
                            ```
                            
                            **Template Selection Notes**:
                            - If git detected, include "local-git-branching-workflow" for implementation tasks
                            - Research tasks may not need git templates unless they involve code prototyping

                            ## Step 6: Template Strategy Setup
                            Establish consistent documentation patterns:

                            **Review Available Templates**:
                            ```
                            Use list_templates to understand available templates
                            ```
                            Identify templates that align with your project needs.

                            **Consider Custom Templates**:
                            For project-specific patterns, create custom templates:
                            ```json
                            Use create_template for project-specific needs:
                            {
                              "name": "[Project-Specific] Documentation Template",
                              "description": "Standardized documentation for [specific project context]",
                              "targetEntityType": "TASK"
                            }
                            ```

                            ## Step 7: Development Workflow Setup
                            Establish project workflows and standards:

                            **Git Workflow Configuration**:
                            Create tasks for workflow setup:
                            ```json
                            {
                              "title": "Establish Git Workflow Standards",
                              "summary": "Set up branching strategy, commit conventions, and PR process",
                              "templateIds": ["local-git-branching-workflow", "github-pr-workflow"],
                              "tags": "task-type-process,git-workflow,standards"
                            }
                            ```

                            **Quality Assurance Setup**:
                            ```json
                            {
                              "title": "Quality Assurance Framework",
                              "summary": "Establish testing strategy, code review process, and quality gates",
                              "templateIds": ["testing-strategy", "definition-of-done"],
                              "tags": "task-type-process,qa,testing,standards"
                            }
                            ```

                            ## Step 8: Initial Dependencies and Sequencing
                            Establish logical task progression:
                            ```
                            Use create_dependency to establish foundational sequences:
                            ```
                            - Infrastructure setup BLOCKS feature development
                            - Research tasks BLOCK implementation decisions
                            - Architecture decisions BLOCK detailed design tasks

                            ## Step 9: Project Monitoring Setup
                            Prepare for ongoing project management:

                            **Create Project Views**:
                            ```
                            Use search_tasks with projectId="[project-id]"
                            Use search_features with projectId="[project-id]"
                            ```

                            **Define Progress Tracking**:
                            - Feature completion metrics
                            - Task status distribution
                            - Complexity and effort tracking
                            - Priority balance monitoring

                            ## Project Organization Best Practices

                            **Naming Conventions**:
                            - Projects: Business/product focused names
                            - Features: User-functionality focused names
                            - Tasks: Implementation-action focused names

                            **Tagging Strategy**:
                            - **Project-level**: Domain, technology stack, business area
                            - **Feature-level**: User impact, complexity, dependencies
                            - **Task-level**: Type, component, skill requirements

                            **Documentation Standards**:
                            - Keep project-level docs high-level and stable
                            - Feature docs should focus on user value and requirements
                            - Task docs should be implementation-focused and actionable

                            **Scalability Planning**:
                            - Design feature structure for team growth
                            - Plan for feature independence and parallel development
                            - Establish clear interfaces between features
                            - Consider long-term maintenance and evolution

                            ## Success Metrics

                            **Project Setup Completion Indicators**:
                            - Project has clear charter and architecture documentation
                            - All major features identified and documented
                            - Foundation tasks created and prioritized
                            - Development workflow established
                            - Team understands project structure and conventions

                            **Ongoing Health Indicators**:
                            - Tasks are consistently well-documented with templates
                            - Features show steady progression
                            - Dependencies are managed and don't create bottlenecks
                            - Project overview shows balanced work distribution

                            This comprehensive setup ensures your project starts with solid foundations and maintains organization as it scales.
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

                            **For Bugs**:
                            - ✅ Root cause documented
                            - ✅ Regression test added
                            - ✅ Bug investigation sections complete
                            - ✅ Fix verified

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