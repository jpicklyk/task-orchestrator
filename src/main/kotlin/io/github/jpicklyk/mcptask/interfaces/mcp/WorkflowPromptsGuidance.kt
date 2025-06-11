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
        addCreateFeatureWorkflowPrompt(server)
        addTaskBreakdownPrompt(server)
        addBugTriageWorkflowPrompt(server)
        addSprintPlanningPrompt(server)
        addProjectSetupPrompt(server)
        addImplementFeatureWorkflowPrompt(server)
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
                            For each identified component:
                            ```json
                            Use create_task for each subtask:
                            {
                              "title": "[Specific, actionable task name]",
                              "summary": "[Clear scope with specific acceptance criteria]",
                              "featureId": "[from step 3, if created]",
                              "complexity": "[3-6 for manageable tasks]",
                              "priority": "[based on implementation dependencies]",
                              "templateIds": ["task-implementation-workflow"],
                              "tags": "[original-tags,component-type,implementation-area]"
                            }
                            ```

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
     * Adds a prompt for systematic bug triage and resolution workflow.
     */
    private fun addBugTriageWorkflowPrompt(server: Server) {
        server.addPrompt(
            name = "bug_triage_workflow",
            description = "Systematic approach to bug triage, investigation, and resolution planning"
        ) { _ ->
            GetPromptResult(
                description = "Complete workflow for triaging, investigating, and planning bug resolution",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Bug Triage Workflow

                            This workflow provides a systematic approach to bug triage, investigation, and resolution planning.

                            ## Step 1: Initial Bug Assessment
                            Start by understanding existing bug reports:
                            ```
                            Use search_tasks with tag="task-type-bug" and priority="high"
                            ```
                            Review current bug load and priorities.

                            ## Step 2: Create Bug Investigation Task
                            Create a structured task for investigation:
                            ```json
                            Use create_task with bug investigation template:
                            {
                              "title": "[Clear bug description with impact]",
                              "summary": "[Detailed symptoms, reproduction steps, and initial impact assessment]",
                              "priority": "[high for critical issues, medium for significant issues, low for minor issues]",
                              "complexity": "[Initial estimate: 3-5 for investigation, adjust after analysis]",
                              "templateIds": ["bug-investigation-workflow"],
                              "tags": "task-type-bug,component-[affected-area],severity-[high/medium/low]"
                            }
                            ```

                            ## Step 3: Detailed Investigation
                            Use the bug investigation template sections to systematically analyze:

                            **Problem Analysis**:
                            - Document exact symptoms and error messages
                            - Identify affected user workflows
                            - Determine frequency and impact scope
                            - List reproduction steps

                            **Technical Investigation**:
                            - Review relevant logs and error traces
                            - Identify potential root causes
                            - Check recent code changes in affected areas
                            - Test in different environments

                            **Impact Assessment**:
                            - Determine user impact severity
                            - Assess business impact and urgency
                            - Identify workarounds or temporary solutions
                            - Evaluate fix complexity and risk

                            ## Step 4: Determine Resolution Approach
                            Based on investigation findings:

                            **For Simple Bugs (complexity 3-5)**:
                            Update the existing task with resolution plan:
                            ```json
                            Use update_task to add resolution approach:
                            {
                              "id": "[bug-task-id]",
                              "title": "[Updated with root cause]",
                              "summary": "[Include resolution approach and testing plan]",
                              "complexity": "[Refined based on investigation]"
                            }
                            ```

                            **For Complex Bugs (complexity 6+)**:
                            Use task_breakdown_workflow to create focused subtasks:
                            1. Root cause analysis task
                            2. Fix implementation task
                            3. Testing and validation task
                            4. Deployment and monitoring task

                            ## Step 5: Prioritization and Planning
                            Set appropriate priority and timeline:

                            **Priority Guidelines**:
                            - **High**: Security issues, data corruption, complete feature breakdown
                            - **Medium**: Significant functionality issues, user experience problems
                            - **Low**: Minor UI issues, edge case problems, cosmetic issues

                            **Planning Considerations**:
                            ```
                            Use get_task_dependencies to understand blocking relationships
                            ```
                            - Consider impact on other features
                            - Evaluate fix risk vs. issue severity
                            - Plan testing strategy
                            - Determine rollback procedures

                            ## Step 6: Implementation Workflow
                            Apply appropriate implementation templates:
                            ```
                            Use apply_template with "local-git-branching-workflow" or "github-pr-workflow"
                            ```
                            This provides step-by-step guidance for:
                            - Branch creation and naming
                            - Development and testing approach
                            - Code review and deployment process

                            ## Step 7: Resolution Tracking
                            Monitor progress and completion:
                            ```
                            Use update_task to track progress through status updates
                            ```
                            - **pending** → **in-progress** when starting investigation/fix
                            - **in-progress** → **completed** when fix is deployed and verified
                            - Use sections to document resolution details and lessons learned

                            ## Bug Classification Tags

                            **Severity Tags**:
                            - `severity-critical`: System down, data loss, security breach
                            - `severity-high`: Major functionality broken, significant user impact
                            - `severity-medium`: Feature partially broken, workaround available
                            - `severity-low`: Minor issue, cosmetic problem

                            **Component Tags**:
                            - `component-frontend`: UI, user experience issues
                            - `component-backend`: API, server-side logic issues
                            - `component-database`: Data integrity, query performance
                            - `component-integration`: Third-party service issues
                            - `component-infrastructure`: Deployment, environment issues

                            **Type Tags**:
                            - `bug-type-regression`: Previously working functionality broken
                            - `bug-type-performance`: Speed or resource usage issues
                            - `bug-type-security`: Security vulnerabilities
                            - `bug-type-data`: Data corruption or incorrect processing

                            ## Quality Assurance
                            Ensure comprehensive resolution:
                            - Document root cause analysis in task sections
                            - Include testing verification steps
                            - Add monitoring or prevention measures
                            - Update task status promptly as work progresses
                            - Create follow-up tasks for systemic improvements if needed

                            This workflow ensures bugs are systematically analyzed, appropriately prioritized, and effectively resolved with proper documentation.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds a prompt for effective sprint planning using the task orchestrator.
     */
    private fun addSprintPlanningPrompt(server: Server) {
        server.addPrompt(
            name = "sprint_planning_workflow",
            description = "Comprehensive guide for sprint planning using task orchestrator tools and data"
        ) { _ ->
            GetPromptResult(
                description = "Step-by-step sprint planning process using task orchestrator capabilities",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Sprint Planning Workflow

                            This workflow guides you through effective sprint planning using the Task Orchestrator's capabilities for analysis, prioritization, and work organization.

                            ## Step 1: Current State Analysis
                            Begin by understanding your current project state:
                            ```
                            Use get_overview to see all features, tasks, and work distribution
                            ```
                            Look for:
                            - In-progress tasks that need completion
                            - High-priority pending tasks
                            - Feature completion status
                            - Orphaned tasks that need organization

                            ## Step 2: Backlog Analysis
                            Analyze available work:
                            ```
                            Use search_tasks with status="pending" and priority="high"
                            Use search_tasks with status="pending" and priority="medium"
                            ```
                            Review:
                            - Task complexity distribution
                            - Feature associations
                            - Dependencies between tasks
                            - Bug vs. feature work balance

                            ## Step 3: Capacity Assessment
                            Evaluate team capacity and constraints:

                            **Review In-Progress Work**:
                            ```
                            Use search_tasks with status="in-progress"
                            ```
                            - Estimate completion effort for current tasks
                            - Identify potential blockers or delays
                            - Consider carry-over work impact

                            **Analyze Task Complexity**:
                            ```
                            Use search_tasks sorted by complexity
                            ```
                            - Group tasks by complexity for estimation
                            - Consider team skill distribution
                            - Plan for complexity variance

                            ## Step 4: Priority Setting
                            Establish clear priorities:

                            **Business Value Assessment**:
                            - Review feature summaries for business impact
                            - Consider user-facing vs. internal improvements
                            - Evaluate technical debt vs. new feature balance

                            **Dependency Analysis**:
                            ```
                            Use get_task_dependencies for critical tasks
                            ```
                            - Identify blocking dependencies
                            - Plan task sequencing
                            - Consider cross-team dependencies

                            ## Step 5: Sprint Goal Definition
                            Create focused sprint objectives:

                            **Feature-Based Goals**:
                            ```
                            Use get_feature with includeTasks=true for key features
                            ```
                            - Complete specific features or feature phases
                            - Address critical bug backlogs
                            - Achieve technical milestones

                            **Create Sprint Feature (Optional)**:
                            ```json
                            For sprint-specific coordination, create a feature:
                            {
                              "name": "Sprint [X] - [Sprint Goal]",
                              "summary": "[Sprint objectives and success criteria]",
                              "status": "in-development",
                              "priority": "high",
                              "tags": "sprint-planning,sprint-[number],goal-[theme]"
                            }
                            ```

                            ## Step 6: Task Selection and Assignment
                            Select tasks for the sprint:

                            **Selection Criteria**:
                            - Aligns with sprint goals
                            - Appropriate complexity for team capacity
                            - Dependencies are satisfied or manageable
                            - Clear acceptance criteria defined

                            **Task Updates for Sprint Commitment**:
                            ```json
                            Use update_task for selected tasks:
                            {
                              "id": "[task-id]",
                              "tags": "[existing-tags],sprint-[number],assigned-[team-member]",
                              "priority": "[adjusted for sprint priority]"
                            }
                            ```

                            ## Step 7: Sprint Backlog Organization
                            Organize selected work:

                            **Create Dependencies**:
                            ```
                            Use create_dependency to establish work sequences
                            ```
                            - Link prerequisite tasks
                            - Create feature completion chains
                            - Plan integration points

                            **Task Breakdown (if needed)**:
                            For complex tasks, use task_breakdown_workflow to:
                            - Split large tasks into sprint-sized work
                            - Enable parallel development
                            - Reduce estimation uncertainty

                            ## Step 8: Sprint Monitoring Setup
                            Prepare for sprint tracking:

                            **Create Sprint Views**:
                            ```
                            Use search_tasks with tag="sprint-[number]"
                            ```
                            This becomes your primary sprint tracking view.

                            **Define Daily Standup Queries**:
                            - `status="in-progress"` + `tag="sprint-[number]"` for current work
                            - `status="completed"` + `tag="sprint-[number]"` for recent completions
                            - Tasks with blockers or dependency issues

                            ## Sprint Planning Best Practices

                            **Task Selection Guidelines**:
                            - Include a mix of complexities (avoid all high-complexity tasks)
                            - Reserve 20% capacity for unexpected work and support
                            - Balance feature development with technical debt
                            - Include testing and documentation tasks

                            **Risk Mitigation**:
                            - Identify tasks with external dependencies
                            - Plan alternatives for high-risk tasks
                            - Include buffer tasks for scope adjustment
                            - Consider team availability and skills

                            **Communication Planning**:
                            - Update task summaries with sprint-specific context
                            - Add acceptance criteria that align with sprint goals
                            - Plan integration points and review checkpoints
                            - Document assumptions and constraints

                            ## Sprint Execution Support

                            **Daily Progress Tracking**:
                            ```
                            Use search_tasks with various status filters
                            ```
                            - Track task progression through statuses
                            - Identify blockers early
                            - Monitor scope creep

                            **Mid-Sprint Adjustments**:
                            - Use task priority updates for scope changes
                            - Move non-critical tasks out of sprint
                            - Break down tasks that prove more complex

                            **Sprint Review Preparation**:
                            ```
                            Use search_tasks with status="completed" and tag="sprint-[number]"
                            ```
                            - Review completed work
                            - Document lessons learned in task sections
                            - Identify process improvements

                            This comprehensive approach ensures your sprint planning is data-driven, well-organized, and sets up your team for successful execution.
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

                            ## Step 1: Project Foundation
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

                            ## Step 2: Project Documentation Structure
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

                            ## Step 3: Feature Planning and Structure
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

                            ## Step 4: Initial Task Creation
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
                              "templateIds": ["task-implementation-workflow", "technical-approach"],
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

                            ## Step 5: Template Strategy Setup
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

                            ## Step 6: Development Workflow Setup
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

                            ## Step 7: Initial Dependencies and Sequencing
                            Establish logical task progression:
                            ```
                            Use create_dependency to establish foundational sequences:
                            ```
                            - Infrastructure setup BLOCKS feature development
                            - Research tasks BLOCK implementation decisions
                            - Architecture decisions BLOCK detailed design tasks

                            ## Step 8: Project Monitoring Setup
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
     * Adds a prompt for implementing features with automatic git detection and proper development workflow integration.
     */
    private fun addImplementFeatureWorkflowPrompt(server: Server) {
        server.addPrompt(
            name = "implement_feature_workflow",
            description = "Guide for implementing features with automatic git detection and proper development workflow integration"
        ) { _ ->
            GetPromptResult(
                description = "Intelligent feature implementation guidance with automatic project context detection and workflow template suggestion",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Implement Feature Workflow

                            This workflow provides intelligent feature implementation guidance with automatic project context detection and workflow template suggestion.

                            ## Step 1: Check Current State & Git Detection
                            Start by understanding project context:
                            ```
                            Use get_overview to understand project context and current work priorities
                            ```
                            - Review existing in-progress tasks to avoid conflicts
                            - Use file system tools to detect if project uses git (check for .git directory)
                            - If git detected, automatically suggest "Local Git Branching Workflow" template

                            ## Step 2: Select Implementation Target
                            Identify suitable work to implement:
                            ```
                            Use search_tasks with status="pending" and priority="high" to identify suitable work
                            Use search_features for feature-level work opportunities
                            ```
                            - Suggest starting with highest priority, unblocked tasks
                            - Verify prerequisites and dependencies are satisfied using get_task_dependencies

                            ## Step 3: Apply Appropriate Templates with Smart Detection
                            Apply templates based on detected project context:

                            **Always Apply**: "Task Implementation Workflow" template for implementation tasks

                            **If Git Detected**: Automatically apply "Local Git Branching Workflow" template

                            **Ask User**: "Do you use GitHub/GitLab pull requests? If yes, I can apply PR workflow template"

                            **If GitHub MCP Available**: Mention GitHub MCP tools can automate PR creation and management

                            **For Complex Tasks**: Consider "Technical Approach" template for architectural guidance

                            ## Step 4: Execute Implementation with Template Guidance
                            Follow structured implementation approach:
                            - Follow template-provided step-by-step instructions from applied templates
                            - Make incremental commits if using git workflows (following git template guidance)
                            - Update task status to "in-progress" when starting work:
                            ```json
                            Use update_task to mark work as started:
                            {
                              "id": "[task-id]",
                              "status": "in-progress"
                            }
                            ```
                            - Use update_task to track progress and add implementation notes

                            ## Step 5: Complete with Validation
                            Ensure proper completion with validation:

                            **Before marking task as completed**:
                            ```
                            Use get_sections to read all task sections
                            ```
                            - **Verify template compliance**: Ensure all instructional template guidance was followed
                            - **Git workflow completion**: If using git templates, complete branch merge process
                            - **Run tests and verification**: Follow testing guidance from applied templates
                            - Update task status to "completed" only after full validation

                            ## Git Integration Best Practices

                            **Auto-Detection Logic**:
                            - Check for .git directory in project root or parent directories
                            - If found, always suggest "Local Git Branching Workflow" template
                            - Ask about PR workflows rather than assuming (different teams have different practices)

                            **GitHub Integration**:
                            - Detect if GitHub MCP server is available in the environment
                            - If available, mention it can automate PR creation, review management, and merge processes
                            - Only suggest GitHub PR workflow template if user confirms they use PRs

                            **Template Selection Strategy**:
                            - Implementation tasks (complexity > 3): Task Implementation + Git Branching workflows
                            - Complex features (complexity > 6): Task Implementation + Git Branching + Technical Approach templates
                            - Bug fixes: Bug Investigation + Git Branching workflows

                            ## Quality Validation Requirements

                            **Task Completion Validation**:
                            ```bash
                            # Before marking any task as completed:
                            get_sections --entityType TASK --entityId [task-id]

                            # Review each section's guidance, especially:
                            # - Requirements compliance
                            # - Implementation approach validation  
                            # - Testing strategy completion
                            # - Git workflow steps (if applicable)
                            ```

                            **Feature Completion Validation**:
                            ```bash
                            # Before marking any feature as completed:
                            get_sections --entityType FEATURE --entityId [feature-id]
                            get_feature --id [feature-id] --includeTasks true --includeTaskCounts true

                            # Verify:
                            # - All associated tasks are completed
                            # - Feature-level requirements satisfied
                            # - Integration testing completed
                            # - Documentation updated
                            ```

                            ## Integration with Other Workflows

                            This workflow complements other workflow prompts:
                            - Use with task_breakdown_workflow for complex features requiring decomposition
                            - Integrate with sprint_planning_workflow for systematic work organization
                            - Apply bug_triage_workflow principles for bug-related implementation work

                            ## Common Implementation Patterns

                            **Feature Implementation**:
                            1. Review feature requirements from sections
                            2. Break down into implementation tasks if needed
                            3. Apply git branching workflow for development
                            4. Follow incremental development with regular commits
                            5. Complete testing and validation before marking done

                            **Bug Fix Implementation**:
                            1. Follow bug investigation template guidance
                            2. Create fix implementation plan
                            3. Apply git workflow for isolated development
                            4. Include regression testing in validation
                            5. Document resolution in bug task sections

                            **Enhancement Implementation**:
                            1. Review technical approach guidance
                            2. Plan backward compatibility considerations
                            3. Follow development workflow templates
                            4. Include impact testing and validation
                            5. Update documentation and examples

                            This workflow ensures systematic, well-documented implementation with proper version control integration and quality validation.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}