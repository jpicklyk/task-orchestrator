package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * Extension functions to provide AI guidance for the MCP server.
 */
object McpServerAiGuidance {

    /**
     * Configures AI guidance for the MCP server by adding prompt templates
     * that provide guidance on using the tools and features.
     */
    fun Server.configureAiGuidance() {
        // Add prompts for AI guidance
        addSystemGuidancePrompts(this)

        // Add template management guidance
        TemplateMgtGuidance.configureTemplateManagementGuidance(this)
    }

    /**
     * Adds system guidance prompts to the server.
     */
    private fun addSystemGuidancePrompts(server: Server) {
        // Primary instructions for the MCP Task Orchestrator
        server.addPrompt(
            name = "instructions",
            description = "Primary instructions for using the MCP Task Orchestrator",
        ) { _ ->
            GetPromptResult(
                description = "Primary instructions for using the MCP Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # MCP Task Orchestrator Instructions
                            
                            This MCP server provides a structured system for managing tasks, features, sections, and templates. The system is optimized for context efficiency with AI assistants, focusing on structured documentation patterns.
                            
                            ## Core Design Principles
                            
                            1. **Context Optimization** - The system is designed to minimize token usage when interacting with AI assistants, using techniques like progressive loading and structured content.
                            
                            2. **Entity Relationships**:
                               - Tasks are the primary work units and can exist independently
                               - Features are optional organizational groupings for related tasks
                               - Sections provide structured content blocks for tasks and features
                               - Templates define reusable patterns of sections
                            
                            3. **Database-Backed Storage** - All entities are persisted in SQLite for reliable state management
                            
                            ## Template-Driven Documentation
                            
                            Templates provide consistent documentation patterns that can be applied to tasks and features. Available templates include:
                            
                            **AI Workflow Instructions:**
                            - Local Git Branching Workflow
                            - GitHub PR Workflow  
                            - Task Implementation Workflow
                            - Bug Investigation Workflow
                            
                            **Documentation Properties:**
                            - Technical Approach
                            - Requirements Specification
                            - Context & Background
                            
                            **Process & Quality:**
                            - Testing Strategy
                            - Definition of Done
                            
                            ## Common Workflows
                            
                            ### Task Type Tagging Convention
                            
                            To improve organization and filtering, use standardized task type tags:
                            
                            - **Bug Tasks**: Include "task-type-bug" tag for issues, defects, and fixes
                            - **Feature Tasks**: Include "task-type-feature" tag for new functionality
                            - **Enhancement Tasks**: Include "task-type-enhancement" tag for improvements to existing features
                            - **Research Tasks**: Include "task-type-research" tag for investigation and analysis work
                            - **Maintenance Tasks**: Include "task-type-maintenance" tag for refactoring, updates, and technical debt
                            
                            Example bug task:
                            ```json
                            {
                              "title": "Fix authentication session timeout issue",
                              "description": "Users are being logged out unexpectedly",
                              "tags": "task-type-bug,authentication,urgent",
                              "templateIds": ["bug-investigation-workflow-template-id"]
                            }
                            ```
                            
                            Example feature task:
                            ```json
                            {
                              "title": "Implement user profile editing",
                              "description": "Allow users to update their profile information",
                              "tags": "task-type-feature,user-management,profile",
                              "templateIds": ["task-implementation-workflow-template-id"]
                            }
                            ```
                            
                            This tagging convention allows easy filtering using `search_tasks` with the `tag` parameter.
                            
                            1. **Creating a new task with templates**:
                               - Use `create_task` with the `templateIds` parameter to create a task with one or more templates
                               - Example: 
                               ```json
                               {
                                 "title": "Implement OAuth API",
                                 "priority": "high",
                                 "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
                               }
                               ```
                               - You can apply multiple templates in a single operation:
                               ```json
                               {
                                 "title": "Implement OAuth API",
                                 "priority": "high",
                                 "templateIds": [
                                   "550e8400-e29b-41d4-a716-446655440000",
                                   "661e8511-f30c-41d4-a716-557788990000"
                                 ]
                               }
                               ```
                               - This creates the task and applies all sections from the templates in one operation
                            
                            2. **Creating a feature with templates**:
                               - Use `create_feature` with the `templateIds` parameter to create a feature with templates
                               - Example: 
                               ```json
                               {
                                 "name": "Authentication System",
                                 "summary": "Implement complete authentication system for our application",
                                 "templateIds": ["661e8511-f30c-41d4-a716-557788990000"]
                               }
                               ```
                            
                            3. **Applying multiple templates to existing entities**:
                               - Use `apply_template` with the `templateIds` array to apply multiple templates at once
                               - Example:
                               ```json
                               {
                                 "templateIds": [
                                   "550e8400-e29b-41d4-a716-446655440000",
                                   "661e8511-f30c-41d4-a716-557788990000"
                                 ],
                                 "entityType": "TASK",
                                 "entityId": "772f9622-g41d-52e5-b827-668899101111",
                                 "continueOnError": true
                               }
                               ```
                               
                            4. **Working with existing tasks**:
                               - Use `get_task_overview` or `search_tasks` to find tasks
                               - Use `get_task` to retrieve a specific task
                               - Use `get_sections` to view task documentation
                               - Use `update_task` to modify task properties
                            
                            5. **Adding documentation**:
                               - Use `add_section` or `bulk_create_sections` to add documentation 
                               - Use `update_section` to modify existing documentation
                               
                            6. **Updating templates and sections efficiently**:
                               - Use `update_template_metadata` to update template metadata
                               - Use `update_section_metadata` to update section metadata
                               - Use `update_section_text` for partial text updates
                               - Use `reorder_sections` to change section display order
                            
                            Always prioritize using templates when creating new tasks and features to maintain consistency. For efficient template usage, consider these options:
                            
                            - Creating entities with templates in a single operation (most efficient)
                            - Applying multiple templates at once when needed
                            - Using the `continueOnError` option when applying multiple templates to ensure all valid templates are applied
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Entity model guidance - explains the system's core data model
        server.addPrompt(
            name = "entity_model",
            description = "Explanation of the core entity model in MCP Task Orchestrator",
        ) { _ ->
            GetPromptResult(
                description = "Explanation of the core entity model in MCP Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Entity Model Guide
                            
                            The MCP Task Orchestrator uses a structured entity model for organizing work items and documentation. Understanding these entities and their relationships is important for effective usage.
                            
                            ## Primary Entities
                            
                            ### Task
                            - Primary unit of work in the system
                            - Has properties like title, status, priority, complexity
                            - Can exist independently or be associated with a Feature
                            - Can contain multiple Sections for structured documentation
                            - Has UUID identifier for reference
                            
                            ### Feature
                            - Optional organizational grouping for related Tasks
                            - Has properties like name, status, priority
                            - Can contain multiple associated Tasks
                            - Can contain multiple Sections for high-level documentation
                            - Has UUID identifier for reference
                            
                            ### Section
                            - Structured documentation block that can be attached to Tasks or Features
                            - Has title, content, usage description, content format
                            - Maintains an ordinal position for ordering
                            - Can use different content formats (Markdown, PlainText, JSON, Code)
                            - Used for organizing detailed documentation
                            
                            ### Template
                            - Defines a reusable pattern of Sections
                            - Can be applied to Tasks or Features
                            - Has target entity type (Task or Feature)
                            - Contains multiple Template Sections that get copied when applied
                            - Enables consistent documentation structure
                            
                            ## Entity Relationships
                            
                            - A Feature can have many Tasks (one-to-many)
                            - A Task can belong to at most one Feature (many-to-one)
                            - Both Tasks and Features can have multiple Sections (one-to-many)
                            - Templates define patterns of Sections for Tasks or Features
                            
                            ## Context-Efficient Design
                            
                            The entity model is designed for efficient context usage with AI systems:
                            
                            - Core Task/Feature entities contain minimal essential properties
                            - Section entities allow for detailed content to be loaded progressively
                            - Templates promote consistency while minimizing redundant content
                            
                            This design supports a "progressive loading" pattern where only necessary information is included in responses, reducing token usage during AI interactions.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Context efficiency guide - explains optimization strategies
        server.addPrompt(
            name = "context_efficiency",
            description = "Guide to context efficiency in MCP Task Orchestrator",
        ) { _ ->
            GetPromptResult(
                description = "Guide to context efficiency in MCP Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Context Efficiency Guide
                            
                            The MCP Task Orchestrator is designed with context efficiency as a core principle. This guide explains how to optimize interactions for minimal token usage while maintaining functional completeness.
                            
                            ## Context Optimization Patterns
                            
                            ### 1. Progressive Loading
                            
                            Most query tools support options to include or exclude related entities:
                            
                            - `get_task` with `includeSections=false` returns only the task metadata
                            - `get_task` with `includeSections=true` includes all associated sections
                            - Use `get_task_overview` for lightweight summaries of many tasks
                            
                            ### 2. Summary Views
                            
                            Many tools support summary views that truncate text fields:
                            
                            - Use `summaryView=true` parameter to get truncated content
                            - Text fields over a certain length are automatically truncated
                            - Full content can be loaded on demand when needed
                            
                            ### 3. Pagination
                            
                            Search and list operations support pagination:
                            
                            - Use `limit` and `offset` parameters to control result set size
                            - Default limits prevent accidentally returning large result sets
                            - Combine with filters to narrow results
                            
                            ### 4. Efficient Workflows
                            
                            - Use `bulk_create_sections` instead of multiple `add_section` calls
                            - Use `apply_template` to create multiple sections at once
                            - Use specific filters in search operations to reduce result size
                            - Use `update_section_text` for partial content updates
                            - Use `update_template_metadata` and `update_section_metadata` for metadata-only changes
                            - Use `reorder_sections` for structural reorganization without content transmission
                            
                            ## Best Practices
                            
                            1. Always use the most specific tool for the job
                            2. Only request related entities when needed
                            3. Use summary views for initial exploration
                            4. Load full content only when necessary
                            5. Keep section content concise and focused
                            6. Use bulk operations for multiple items
                            7. For updates, use partial-update tools instead of sending complete content
                            
                            Following these guidelines ensures efficient use of context when interacting with AI assistants, allowing more complex tasks to be performed within context limits.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Template usage guide
        server.addPrompt(
            name = "template_guide",
            description = "Guide to effectively using templates in the MCP Task Orchestrator",
        ) { _ ->
            GetPromptResult(
                description = "Guide to effectively using templates in the MCP Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Template Usage Guide
                            
                            Templates in the MCP Task Orchestrator provide reusable documentation patterns that can be applied to tasks and features. This guide explains how to effectively use templates.
                            
                            ## Available Templates
                            
                            The system includes focused, composable templates organized into three categories:
                            
                            ### AI Workflow Instructions
                            
                            1. **Local Git Branching Workflow** - Step-by-step guide for git operations and branch management
                               - Sections: Branch Creation Process, Implementation Steps, Testing & Verification, Commit Preparation
                            
                            2. **GitHub PR Workflow** - GitHub pull request creation and management using MCP tools
                               - Sections: Pre-Push Validation & Sync, Branch Push & PR Creation, Review Management & Updates, PR Approval & Merge Process
                            
                            3. **Task Implementation Workflow** - Systematic approach for implementing individual tasks
                               - Sections: Implementation Analysis, Step-by-Step Implementation, Testing & Validation
                            
                            4. **Bug Investigation Workflow** - Structured debugging and bug resolution process
                               - Sections: Investigation Process, Root Cause Analysis, Fix Implementation & Verification
                            
                            ### Documentation Properties
                            
                            5. **Technical Approach** - Document technical solution approach and architecture decisions
                               - Sections: Architecture Overview, Key Dependencies, Implementation Strategy
                            
                            6. **Requirements Specification** - Capture detailed functional and non-functional requirements
                               - Sections: Must-Have Requirements, Nice-to-Have Features, Constraints & Limitations
                            
                            7. **Context & Background** - Provide necessary context and background information
                               - Sections: Business Context, User Needs & Goals, Related Work & Dependencies
                            
                            ### Process & Quality
                            
                            8. **Testing Strategy** - Define comprehensive testing approach and quality validation
                               - Sections: Test Case Definitions, Acceptance Criteria, Quality Gates
                            
                            9. **Definition of Done** - Clear completion criteria and handoff requirements
                               - Sections: Completion Criteria, Quality Checklist, Handoff Requirements
                            
                            ## Using Templates
                            
                            ### Viewing Available Templates
                            
                            Use `list_templates` to see all available templates with their descriptions:
                            
                            ```json
                            {
                              "targetEntityType": "TASK"  // or "FEATURE"
                            }
                            ```
                            
                            ### Creating Tasks and Features with Templates
                            
                            Create a task or feature with templates applied in a single operation:
                            
                            ```json
                            {
                              "title": "Implement Authentication",
                              "description": "Create secure authentication system",
                              "templateIds": ["template-uuid-here"]
                            }
                            ```
                            
                            ```json
                            {
                              "name": "User Management",
                              "summary": "Complete user management module",
                              "templateIds": ["template-uuid-here"]
                            }
                            ```
                            
                            You can apply multiple templates at once:
                            
                            ```json
                            {
                              "title": "Implement Security Features",
                              "description": "Implement authentication and authorization",
                              "templateIds": [
                                "template-uuid-1", 
                                "template-uuid-2"
                              ]
                            }
                            ```
                            
                            ### Applying Templates to Existing Entities
                            
                            Apply templates to existing tasks or features using `apply_template`:
                            
                            ```json
                            {
                              "templateIds": ["template-uuid-here"],
                              "entityType": "TASK",
                              "entityId": "task-uuid-here"
                            }
                            ```
                            
                            Apply multiple templates at once:
                            
                            ```json
                            {
                              "templateIds": [
                                "template-uuid-1",
                                "template-uuid-2"
                              ],
                              "entityType": "TASK",
                              "entityId": "task-uuid-here",
                              "continueOnError": true
                            }
                            ```
                            
                            ### Customizing Applied Templates
                            
                            After applying a template, you can:
                            
                            1. Update section content using `update_section` or `update_section_text` for partial updates
                            2. Update section metadata using `update_section_metadata`
                            3. Add additional sections using `add_section`
                            4. Remove unwanted sections using `delete_section`
                            5. Reorder sections using `reorder_sections`
                            
                            ### Creating Custom Templates
                            
                            You can create custom templates using `create_template` and `add_template_section`.
                            
                            ### Updating Templates
                            
                            To update existing templates:
                            
                            1. Update template metadata using `update_template_metadata`
                            2. Update template section metadata using `update_section_metadata`
                            3. Update template section content using `update_section_text` or `update_section`
                            4. Reorder template sections using `reorder_sections`
                            
                            ### Deleting Templates
                            
                            To delete a template that is no longer needed:
                            
                            ```json
                            {
                              "id": "template-uuid-here"
                            }
                            ```
                            
                            Note: Built-in templates cannot be deleted. Use `disable_template` to make a built-in template unavailable instead.
                            
                            ## Best Practices
                            
                            1. Apply templates when creating tasks or features for maximum efficiency
                            2. Choose the most appropriate template for the task type
                            3. **Use Task Type Tags**: Include appropriate task type tags for better organization:
                               - "task-type-bug" for bug fixes and issues
                               - "task-type-feature" for new functionality  
                               - "task-type-enhancement" for improvements
                               - "task-type-research" for investigation work
                               - "task-type-maintenance" for technical debt and refactoring
                            4. Customize template content but maintain the section structure
                            5. Consider creating custom templates for recurring documentation patterns
                            6. For context-efficient updates, use partial update tools rather than full content replacement
                            7. When applying multiple templates, use `continueOnError: true` to ensure all valid templates are applied
                            8. Use template combinations that complement each other (e.g., implementation + testing)
                            
                            Templates provide a starting point - always customize the content for the specific context while maintaining consistent structure.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Claude Code workflow and memory instructions
        server.addPrompt(
            name = "claude_code_workflow",
            description = "Comprehensive workflow instructions for Claude Code when working with MCP Task Orchestrator",
        ) { _ ->
            GetPromptResult(
                description = "Comprehensive workflow instructions for Claude Code when working with MCP Task Orchestrator",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Claude Code Workflow Instructions for MCP Task Orchestrator

                            ## Task Management Protocol

                            **Always Use MCP Task Tools for Substantial Work:**
                            1. **Use `create_task`** instead of TodoWrite for any non-trivial work items:
                               - Multi-step implementations (3+ steps)
                               - Complexity rating > 2
                               - Testing suites for components
                               - Bug fixes requiring investigation
                               - Feature enhancements
                               - Integration work
                               - Any work benefiting from tracking and documentation

                            2. **Task Workflow Process:**
                               - Start with `get_task_overview` to check current work and priorities
                               - If no suitable task exists, use `create_task` with proper metadata
                               - Use `update_task` to set status to "in_progress" when starting work
                               - Work incrementally with regular git commits following conventional commit format
                               - Use `update_task` to set status to "completed" when finished
                               - Use TodoWrite only for simple tracking within larger tasks

                            3. **Task Quality Standards:**
                               - Write descriptive titles and summaries
                               - Use appropriate complexity ratings (1-10 scale)
                               - Add relevant tags for categorization and searchability
                               - Include acceptance criteria in summaries when helpful
                               - Reference related tasks, features, or projects when applicable

                            ## Template Usage Protocol

                            **Always Check and Use Available Templates:**
                            1. **Before creating tasks or features**, run `list_templates` to check available templates
                            2. **Filter templates by target entity type** (TASK or FEATURE) and enabled status
                            3. **Apply appropriate templates** using the `templateIds` parameter in `create_task` or `create_feature`
                            4. **Use templates for consistency** in documentation structure and content organization

                            **Template Selection Guidelines:**
                            - **For Tasks:** Look for templates matching the work type (implementation, testing, bug-fix, etc.)
                            - **For Features:** Use feature-level templates that provide comprehensive documentation structure
                            - **Match template tags** to the work being done (e.g., "testing", "implementation", "documentation")
                            - **Prefer built-in templates** for standard workflows when available

                            **Template Application Examples:**
                            ```bash
                            # Check available templates first
                            list_templates --targetEntityType TASK --isEnabled true

                            # Create task with appropriate template
                            create_task --title "Implement API endpoint" --summary "..." --templateIds ["template-uuid"]

                            # Check available feature templates
                            list_templates --targetEntityType FEATURE --isEnabled true

                            # Create feature with template
                            create_feature --name "User Authentication" --summary "..." --templateIds ["feature-template-uuid"]
                            ```

                            ## Git Workflow Integration

                            **Commit Standards:**
                            - Follow conventional commit format: `type: description`
                            - Include template application in commit messages when templates are used
                            - Commit incrementally as tasks progress
                            - Always include co-authorship attribution:
                              ```
                              🤖 Generated with [Claude Code](https://claude.ai/code)
                              
                              Co-Authored-By: Claude <noreply@anthropic.com>
                              ```

                            ## Testing Protocol

                            **When Creating Test Tasks:**
                            1. Create separate tasks for different test suites (unit, integration, component)
                            2. Use appropriate complexity ratings based on test scope
                            3. Include coverage requirements in task summaries
                            4. Tag tests with relevant categories ("unit-tests", "integration", "mocking", etc.)
                            5. Update mock repositories as needed to support new functionality

                            ## Priority and Dependency Management

                            **Task Prioritization:**
                            - Use "high" priority for critical functionality and blocking issues
                            - Use "medium" priority for enhancements and non-blocking features  
                            - Use "low" priority for optimization and nice-to-have features
                            - Consider dependency relationships when setting priorities

                            **Dependency Integration:**
                            - Always test that new dependency features work with existing tools
                            - Update mock repositories when adding new repository interfaces
                            - Ensure backward compatibility in API enhancements
                            - Run full test suite after significant changes

                            ## Error Handling and Quality

                            **Code Quality Standards:**
                            - Implement comprehensive error handling for all tools
                            - Provide clear, actionable error messages
                            - Use appropriate error codes from ErrorCodes utility
                            - Include proper logging for debugging support

                            **Testing Requirements:**
                            - Achieve comprehensive test coverage for new functionality
                            - Test both success and failure scenarios
                            - Include edge case testing
                            - Validate error responses and status codes

                            ## Project Structure Awareness

                            This is a **Kotlin-based MCP (Model Context Protocol) server** that provides task orchestration tools. Key architectural components:
                            - **Domain layer:** Core business models and repository interfaces
                            - **Infrastructure layer:** Database implementations and utilities
                            - **Application layer:** MCP tools and business logic
                            - **Interface layer:** MCP server and API definitions

                            When working on this project, maintain the architectural boundaries and follow the established patterns for consistency.

                            ## Quick Reference Workflow

                            1. **Start Work:** `get_task_overview` → identify or `create_task` → `update_task` status to in_progress
                            2. **Check Templates:** `list_templates` → apply during task creation or use `apply_template`
                            3. **Work Incrementally:** Make changes → test → git commit with conventional format
                            4. **Complete Work:** `update_task` status to completed → final commit
                            5. **Quality Check:** Run tests → verify functionality → document any new patterns

                            Following these guidelines ensures consistent, high-quality work that leverages the full capability of the MCP Task Orchestrator system.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Template integration guide
        server.addPrompt(
            name = "template_integration",
            description = "Guide for template integration with task and feature creation",
        ) { _ ->
            GetPromptResult(
                description = "Guide for template integration with task and feature creation",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Template Integration Guide
                            
                            The MCP Task Orchestrator provides seamless integration between task/feature creation and templates. This guide explains how to effectively use these integrated capabilities.
                            
                            ## Creating Tasks with Templates
                            
                            The `create_task` tool supports applying templates during task creation through the optional `templateIds` array parameter:
                            
                            ```json
                            {
                              "title": "Implement Authentication API",
                              "description": "Create API endpoints for user registration, login, and password reset",
                              "priority": "high",
                              "complexity": 7,
                              "tags": "task-type-feature,authentication,api,security",
                              "templateIds": ["template-uuid-here"]
                            }
                            ```
                            
                            You can apply multiple templates in a single create operation:
                            
                            ```json
                            {
                              "title": "Fix Session Management Bug",
                              "description": "Resolve issue where sessions expire too quickly",
                              "priority": "high", 
                              "complexity": 6,
                              "tags": "task-type-bug,session,authentication,urgent",
                              "templateIds": [
                                "template-uuid-1",
                                "template-uuid-2"
                              ]
                            }
                            ```
                            
                            ### Benefits
                            
                            - Creates the task and applies templates in a single operation
                            - More context-efficient than separate create and apply calls
                            - Ensures all new tasks start with structured documentation
                            
                            ### Options
                            
                            - `templateIds` (array of UUIDs): IDs of templates to apply
                            - `skipDisabledTemplateCheck` (boolean): Whether to apply even if templates are disabled
                            
                            ### Response
                            
                            A successful response will include both task information and details about the created sections:
                            
                            ```json
                            {
                              "success": true,
                              "message": "Task created successfully with 3 sections from templates",
                              "data": {
                                "id": "task-uuid-here",
                                "title": "Implement Authentication API",
                                "status": "pending",
                                "priority": "high",
                                "complexity": 7,
                                "appliedTemplates": [
                                  {
                                    "templateId": "template-uuid-here",
                                    "sectionsCreated": 3
                                  }
                                ],
                                "sections": [
                                  { "id": "...", "title": "Requirements" },
                                  { "id": "...", "title": "Implementation Notes" },
                                  { "id": "...", "title": "Testing Strategy" }
                                ]
                              }
                            }
                            ```
                            
                            ## Creating Features with Templates
                            
                            Similarly, the `create_feature` tool supports applying templates during feature creation:
                            
                            ```json
                            {
                              "name": "Authentication System",
                              "summary": "Complete authentication system with social login",
                              "status": "planning",
                              "templateIds": ["template-uuid-here"]
                            }
                            ```
                            
                            Multiple templates can be applied during feature creation:
                            
                            ```json
                            {
                              "name": "Authentication System",
                              "summary": "Complete authentication system with social login",
                              "status": "planning",
                              "templateIds": [
                                "template-uuid-1",
                                "template-uuid-2"
                              ]
                            }
                            ```
                            
                            The response will follow the same pattern as task creation, showing both feature information and the created sections.
                            
                            ## Applying Multiple Templates to Existing Entities
                            
                            For more complex documentation needs, the `apply_template` tool supports applying multiple templates at once:
                            
                            ```json
                            {
                              "templateIds": [
                                "template-uuid-1",
                                "template-uuid-2"
                              ],
                              "entityType": "TASK",
                              "entityId": "task-uuid-here",
                              "continueOnError": true
                            }
                            ```
                            
                            ### Options
                            
                            - `templateIds`: Array of template IDs to apply (in order)
                            - `continueOnError`: Whether to continue applying templates if one fails
                            - `skipDisabledTemplateCheck`: Whether to apply disabled templates
                            
                            ### Response
                            
                            ```json
                            {
                              "success": true,
                              "message": "2 of 2 templates applied successfully, created 7 sections",
                              "data": {
                                "entityType": "TASK",
                                "entityId": "task-uuid-here",
                                "appliedTemplates": [
                                  {
                                    "templateId": "template-uuid-1",
                                    "sectionsCreated": 3
                                  },
                                  {
                                    "templateId": "template-uuid-2",
                                    "sectionsCreated": 4
                                  }
                                ],
                                "totalSectionsCreated": 7
                              }
                            }
                            ```
                            
                            ## Best Practices
                            
                            1. **Create with Templates**: When creating new tasks or features, use templates directly in the creation call rather than applying after creation
                            
                            2. **Template Categories**: Choose templates based on your needs:
                               - **AI Workflow Instructions** for step-by-step process guidance
                               - **Documentation Properties** for capturing requirements and technical details  
                               - **Process & Quality** for ensuring completion standards
                            
                            3. **Template Combinations**: For complex documentation, apply multiple complementary templates rather than creating one massive template
                            
                            4. **Use `continueOnError`**: When applying multiple templates, use `continueOnError: true` to ensure all valid templates are applied even if some fail
                            
                            5. **Check Template Compatibility**: Ensure templates are designed for the correct entity type (TASK or FEATURE)
                            
                            6. **Customize After Creation**: After applying templates, customize the generated sections to fit the specific task or feature requirements
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}