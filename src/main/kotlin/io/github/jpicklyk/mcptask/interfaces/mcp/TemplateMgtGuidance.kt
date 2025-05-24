package io.github.jpicklyk.mcptask.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.GetPromptResult
import io.modelcontextprotocol.kotlin.sdk.PromptMessage
import io.modelcontextprotocol.kotlin.sdk.Role
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject

/**
 * Extension functions to provide guidance for template management features.
 */
object TemplateMgtGuidance {

    /**
     * Configures template management guidance for the MCP server.
     */
    fun configureTemplateManagementGuidance(server: Server) {
        // Add specialized prompts for template management guidance
        addTemplateUpdateGuidance(server)
        addAdvancedTemplateUsageGuidance(server)
        addTemplateWorkflowExamples(server)
    }

    /**
     * Adds guidance on context-efficient template updates.
     */
    private fun addTemplateUpdateGuidance(server: Server) {
        // Template update guidance - explains the new context-efficient update tools
        server.addPrompt(
            name = "template_update_guide",
            description = "Guide to efficiently updating templates and sections",
        ) { _ ->
            GetPromptResult(
                description = "Guide to efficiently updating templates and sections",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Context-Efficient Template Update Guide
                            
                            The MCP Task Orchestrator provides specialized tools for updating templates and sections in a context-efficient manner. This guide explains how to use these tools to minimize token usage while maintaining full functionality.
                            
                            ## Core Update Tools
                            
                            ### 1. `update_template_metadata`
                            
                            Updates a template's metadata without affecting its sections.
                            
                            ```json
                            {
                              "id": "550e8400-e29b-41d4-a716-446655440000",
                              "name": "Updated Template Name",
                              "description": "Updated description",
                              "targetEntityType": "TASK",
                              "isEnabled": true,
                              "tags": "tag1,tag2,tag3"
                            }
                            ```
                            
                            - Only provide fields you want to update
                            - Protected templates cannot be updated
                            
                            ### 2. `update_section_metadata`
                            
                            Updates a section's metadata without affecting its content.
                            
                            ```json
                            {
                              "id": "772f9622-g41d-52e5-b827-668899101111",
                              "title": "Updated Section Title",
                              "usageDescription": "Updated usage description",
                              "contentFormat": "MARKDOWN",
                              "ordinal": 2,
                              "tags": "tag1,tag2,tag3"
                            }
                            ```
                            
                            - Only provide fields you want to update
                            - Use this for changing metadata like title, format, or ordering
                            
                            ### 3. `update_section_text`
                            
                            Updates specific portions of a section's content without sending the entire content.
                            
                            ```json
                            {
                              "id": "772f9622-g41d-52e5-b827-668899101111",
                              "oldText": "This text will be replaced",
                              "newText": "This is the replacement text"
                            }
                            ```
                            
                            - `oldText` must match exactly in the existing content
                            - Ideal for targeted changes in large content blocks
                            - Much more efficient than sending the entire content
                            
                            ### 4. `reorder_sections`
                            
                            Changes the display order of sections within an entity.
                            
                            ```json
                            {
                              "entityType": "TEMPLATE",
                              "entityId": "550e8400-e29b-41d4-a716-446655440000",
                              "sectionOrder": [
                                "772f9622-g41d-52e5-b827-668899101111",
                                "661f9511-f30c-52e5-b827-557766551111",
                                "993f0844-i63f-74g7-d049-8800a1323333"
                              ]
                            }
                            ```
                            
                            - The `sectionOrder` array must include all sections of the entity
                            - Sections will be reordered by updating their ordinal values
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }

        // Add the second part of the guidance
        server.addPrompt(
            name = "template_update_strategies",
            description = "Strategies for efficient template updates",
        ) { _ ->
            GetPromptResult(
                description = "Strategies for efficient template updates",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Template Update Strategies
                            
                            ## Context Efficiency Strategies
                            
                            ### Strategy 1: Metadata-Only Updates
                            
                            When you only need to change metadata fields (name, description, title, etc.):
                            
                            - Use `update_template_metadata` for template metadata
                            - Use `update_section_metadata` for section metadata
                            - Avoid sending content when it's not changing
                            
                            ### Strategy 2: Targeted Content Updates
                            
                            When you need to change specific parts of section content:
                            
                            - Use `update_section_text` for small changes in large content
                            - Only send the specific text segment to replace and its replacement
                            - For multiple changes, use multiple `update_section_text` calls
                            
                            ### Strategy 3: Structural Updates
                            
                            When you need to change the organization of sections:
                            
                            - Use `reorder_sections` to change display order
                            - No need to send any content, just the section IDs in the new order
                            
                            ## Update Use Cases
                            
                            ### Use Case 1: Updating Template Name and Description
                            
                            ```json
                            {
                              "id": "550e8400-e29b-41d4-a716-446655440000",
                              "name": "Updated Template Name",
                              "description": "Updated description"
                            }}
                            ```
                            
                            ### Use Case 2: Correcting a Typo in Section Content
                            
                            ```json
                            {
                              "id": "772f9622-g41d-52e5-b827-668899101111",
                              "oldText": "Implementation straegy",
                              "newText": "Implementation strategy"
                            }
                            ```
                            
                            ### Use Case 3: Reorganizing Template Sections
                            
                            ```json
                            {
                              "entityType": "TEMPLATE",
                              "entityId": "550e8400-e29b-41d4-a716-446655440000",
                              "sectionOrder": [
                                "993f0844-i63f-74g7-d049-8800a1323333", 
                                "772f9622-g41d-52e5-b827-668899101111",
                                "661f9511-f30c-52e5-b827-557766551111"
                              ]
                            }
                            ```
                            
                            ## Benefits of Context-Efficient Updates
                            
                            1. **Reduced Token Usage**: Only send the specific data that needs to change
                            2. **Increased Capacity**: Perform more complex operations within context limits
                            3. **Improved Performance**: Less data to process means faster operations
                            4. **Better User Experience**: More responsive AI interactions
                            
                            Always choose the most targeted and efficient update tool for your specific needs.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds guidance on advanced template usage patterns.
     */
    private fun addAdvancedTemplateUsageGuidance(server: Server) {
        // Advanced Template Usage guidance - explains multi-template capabilities
        server.addPrompt(
            name = "advanced_template_usage",
            description = "Guide to using multiple templates and template integration features",
        ) { _ ->
            GetPromptResult(
                description = "Guide to using multiple templates and template integration features",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Advanced Template Usage Guide
                            
                            The MCP Task Orchestrator supports advanced template usage patterns to provide flexibility and efficiency. This guide explains these advanced capabilities and how to use them effectively.
                            
                            ## Multiple Template Application
                            
                            ### Using templateIds Array
                            
                            You can apply multiple templates in a single operation using the `templateIds` array parameter in `apply_template`:
                            
                            ```json
                            {
                              "templateIds": [
                                "550e8400-e29b-41d4-a716-446655440000",
                                "661e8511-f30c-41d4-a716-557788990000"
                              ],
                              "entityType": "TASK",
                              "entityId": "772f9622-g41d-52e5-b827-668899101111"
                            }
                            ```
                            
                            Templates are applied in the order specified in the array. Sections from later templates will appear after sections from earlier templates.
                            
                            ### Error Handling
                            
                            By default, if any template in the array fails to apply, the operation stops at that point. You can change this behavior with the `continueOnError` parameter:
                            
                            ```json
                            {
                              "templateIds": [ ... ],
                              "entityType": "TASK",
                              "entityId": "772f9622-g41d-52e5-b827-668899101111",
                              "continueOnError": true
                            }
                            ```
                            
                            With `continueOnError: true`, the operation will try to apply all templates in the array, skipping any that fail and continuing with the next one.
                            
                            ## Template Integration with Creation
                            
                            Both `create_task` and `create_feature` tools support direct template application using the `templateIds` array:
                            
                            ```json
                            {
                              "title": "Implement Authentication",
                              "description": "Create secure authentication system",
                              "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
                            }
                            ```
                            
                            ```json
                            {
                              "name": "User Management",
                              "summary": "Complete user management module",
                              "templateIds": ["661e8511-f30c-41d4-a716-557788990000"]
                            }
                            ```
                            
                            This creates the entity and applies the template in a single operation, which is more efficient than separate calls.
                            
                            ## Template Combination Patterns
                            
                            ### Category-Based Combinations
                            
                            The new template system is organized into three categories that work well together:
                            
                            **AI Workflow Instructions** (for process guidance):
                            - Local Git Branching Workflow
                            - GitHub PR Workflow  
                            - Task Implementation Workflow
                            - Bug Investigation Workflow
                            
                            **Documentation Properties** (for capturing information):
                            - Technical Approach
                            - Requirements Specification
                            - Context & Background
                            
                            **Process & Quality** (for standards and completion):
                            - Testing Strategy
                            - Definition of Done
                            
                            ### Recommended Combinations
                            
                            Combine templates from different categories for comprehensive coverage:
                            
                            - **Development Task**: Technical Approach + Task Implementation Workflow + Testing Strategy
                            - **Bug Fix**: Bug Investigation Workflow + Technical Approach + Definition of Done
                            - **Feature Planning**: Requirements Specification + Context & Background + Testing Strategy
                            - **Complex Implementation**: Technical Approach + Local Git Branching Workflow + GitHub PR Workflow + Definition of Done
                            
                            ### Template Sequence Optimization
                            
                            Consider the logical ordering of templates based on categories:
                            
                            1. **Start with Context**: Context & Background, Requirements Specification
                            2. **Add Technical Detail**: Technical Approach for implementation guidance
                            3. **Include Process Guidance**: Task Implementation Workflow, Bug Investigation Workflow
                            4. **Add Git Workflow**: Local Git Branching Workflow, GitHub PR Workflow
                            5. **Ensure Quality**: Testing Strategy, Definition of Done
                            
                            This creates a natural flow from context ? requirements ? implementation ? process ? quality.
                            
                            ### Focused Template Design
                            
                            The new template system follows a modular, focused approach:
                            
                            - **Small and Focused**: Each template addresses a specific aspect (3-4 sections each)
                            - **Composable**: Templates can be mixed and matched based on needs
                            - **Category-Specific**: Clear separation between workflow, documentation, and quality concerns
                            - **AI-Optimized**: Workflow templates include specific MCP tool references and step-by-step guidance
                            
                            ## Context Efficiency Techniques
                            
                            ### 1. Direct Creation with Templates
                            
                            Always prefer creating tasks/features with templates directly rather than in separate operations:
                            
                            ```
                            // EFFICIENT
                            create_task(title: "X", templateIds: ["Y"])
                            
                            // LESS EFFICIENT
                            id = create_task(title: "X")
                            apply_template(entityId: id, templateIds: ["Y"])
                            ```
                            
                            ### 2. Multiple Templates in One Call
                            
                            Apply multiple templates in a single operation:
                            
                            ```
                            // EFFICIENT
                            apply_template(entityId: id, templateIds: ["A", "B", "C"])
                            
                            // LESS EFFICIENT
                            apply_template(entityId: id, templateIds: ["A"])
                            apply_template(entityId: id, templateIds: ["B"])
                            apply_template(entityId: id, templateIds: ["C"])
                            ```
                            
                            ### 3. Template Design Principles
                            
                            - Create focused, modular templates rather than monolithic ones
                            - Design templates to work well in combinations
                            - Use clear, consistent section naming across templates
                            - Include clear usageDescription values to guide AI assistants on section purpose
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }

    /**
     * Adds example workflows for template integration.
     */
    private fun addTemplateWorkflowExamples(server: Server) {
        server.addPrompt(
            name = "template_workflow_examples",
            description = "Example workflows for template integration",
        ) { _ ->
            GetPromptResult(
                description = "Example workflows for template integration",
                messages = listOf(
                    PromptMessage(
                        role = Role.assistant,
                        content = TextContent(
                            text = """
                            # Template Integration Example Workflows
                            
                            These examples show how to use the template integration features in common workflows.
                            
                            ## Workflow 1: Creating a Development Task with Category-Based Templates
                            
                            ```json
                            // Step 1: List available templates by category
                            {
                              "targetEntityType": "TASK"
                            }
                            
                            // Step 2: Create task with templates from multiple categories
                            {
                              "title": "Implement User Authentication API",
                              "description": "Create secure API endpoints for user authentication",
                              "priority": "high",
                              "complexity": 8,
                              "tags": "task-type-feature,authentication,api,security",
                              "templateIds": [
                                "technical-approach-template-id",
                                "task-implementation-workflow-template-id", 
                                "testing-strategy-template-id"
                              ]
                            }
                            
                            // Step 3: View created task with sections from all categories
                            {
                              "id": "new-task-id",
                              "includeSections": true
                            }
                            
                            // Step 4: Update specific sections with task details
                            {
                              "id": "architecture-section-id",
                              "oldText": "[Describe the overall system design]",
                              "newText": "Authentication will use OAuth 2.0 with JWT tokens, integrating with our existing user management system..."
                            }
                            ```
                            
                            ## Workflow 2: Creating a Bug Fix Task with Workflow Templates
                            
                            ```json
                            // Step 1: List available templates to find appropriate ones
                            {
                              "targetEntityType": "TASK"
                            }
                            
                            // Step 2: Create task with chosen templates
                            {
                              "title": "Implement Authentication API",
                              "description": "Create API endpoints for user authentication",
                              "priority": "high",
                              "complexity": 7,
                              "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
                            }
                            
                            // Step 3: View created task with sections
                            {
                              "id": "772f9622-g41d-52e5-b827-668899101111",
                              "includeSections": true
                            }
                            
                            // Step 4: Update section content with task-specific details
                            {
                              "id": "aaa9733-h52e-63f6-c938-779900212222",
                              "content": "Authentication will use OAuth 2.0 protocol with JWT tokens..."
                            }
                            ```
                            
                            ## Workflow 2: Creating a Bug Fix Task with Workflow Templates
                            
                            ```json
                            // Step 1: Create bug fix task with investigation workflow
                            {
                              "title": "Fix Authentication Session Timeout Issue",
                              "description": "Users are being logged out unexpectedly during long sessions",
                              "priority": "high",
                              "complexity": 6,
                              "tags": "task-type-bug,authentication,session,urgent",
                              "templateIds": [
                                "bug-investigation-workflow-template-id",
                                "local-git-branching-workflow-template-id",
                                "github-pr-workflow-template-id"
                              ]
                            }
                            
                            // Step 2: Update investigation section with specific details
                            {
                              "id": "investigation-section-id",
                              "oldText": "[Detailed description of the actual behavior or issue observed]",
                              "newText": "Users report being logged out after 10-15 minutes of activity, even though session timeout is configured for 30 minutes. Error logs show 'Invalid token' errors before logout."
                            }
                            
                            // Step 3: View the task with all workflow guidance
                            {
                              "id": "bug-task-id",
                              "includeSections": true
                            }
                            ```
                            
                            ## Workflow 3: Creating a Complex Task with Multiple Templates
                            
                            ```json
                            // Step 1: Create the task with multiple templates directly
                            {
                              "title": "Implement Authentication System",
                              "description": "Create a complete authentication system",
                              "priority": "high",
                              "complexity": 9,
                              "templateIds": [
                                "550e8400-e29b-41d4-a716-446655440000", // Implementation Strategy
                                "661e8511-f30c-41d4-a716-557788990000", // Testing Plan
                                "772f9622-g41d-52e5-b827-668899101111"  // Security Checklist
                              ]
                            }
                            
                            // Step 2: View the task with all sections
                            {
                              "id": "aaa8400-e29b-41d4-a716-446655440000",
                              "includeSections": true
                            }
                            ```
                            
                            ## Workflow 3: Creating a Complex Task with Multiple Templates
                            
                            ```json
                            // Step 1: Create comprehensive task with templates from all categories
                            {
                              "title": "Implement OAuth 2.0 Authentication System",
                              "description": "Create complete OAuth authentication with social login",
                              "priority": "high",
                              "complexity": 9,
                              "tags": "task-type-feature,oauth,authentication,social-login,complex",
                              "templateIds": [
                                "requirements-specification-template-id",
                                "technical-approach-template-id",
                                "task-implementation-workflow-template-id",
                                "testing-strategy-template-id", 
                                "definition-of-done-template-id"
                              ]
                            }
                            
                            // Step 2: View the task with comprehensive documentation structure
                            {
                              "id": "complex-task-id",
                              "includeSections": true
                            }
                            ```
                            
                            ## Workflow 4: Creating a Feature with Documentation Templates
                            
                            ```json
                            // Step 1: Create a feature with templates
                            {
                              "name": "User Management",
                              "summary": "Complete user management system",
                              "status": "planning",
                              "priority": "high",
                              "templateIds": ["bbb8511-f30c-41d4-a716-557788990000"] // Feature Planning template
                            }
                            
                            // Step 2: Create tasks associated with the feature
                            {
                              "title": "User Registration",
                              "description": "Implement user registration flow",
                              "priority": "high",
                              "complexity": 6,
                              "featureId": "ccc8511-f30c-41d4-a716-557788990000", // ID of the created feature
                              "templateIds": ["550e8400-e29b-41d4-a716-446655440000"] // Implementation Strategy template
                            }
                            
                            // Create another task with the same feature ID
                            {
                              "title": "User Authentication",
                              "description": "Implement login and session management",
                              "priority": "high",
                              "complexity": 7,
                              "featureId": "ccc8511-f30c-41d4-a716-557788990000",
                              "templateIds": ["550e8400-e29b-41d4-a716-446655440000"]
                            }
                            
                            // Step 3: Get feature with associated tasks
                            {
                              "id": "ccc8511-f30c-41d4-a716-557788990000",
                              "includeTasks": true,
                              "includeSections": true
                            }
                            ```
                            
                            ## Workflow 4: Creating a Feature with Documentation Templates
                            
                            ```json
                            // Step 1: Create feature with documentation-focused templates
                            {
                              "name": "User Management System",
                              "summary": "Complete user management with registration, profiles, and permissions",
                              "status": "planning",
                              "priority": "high",
                              "templateIds": [
                                "context-background-template-id",
                                "requirements-specification-template-id",
                                "technical-approach-template-id"
                              ]
                            }
                            
                            // Step 2: Create implementation tasks with workflow templates
                            {
                              "title": "User Registration Implementation",
                              "description": "Implement user registration with email verification",
                              "priority": "high",
                              "complexity": 6,
                              "tags": "task-type-feature,user-registration,email-verification",
                              "featureId": "user-management-feature-id",
                              "templateIds": [
                                "task-implementation-workflow-template-id",
                                "local-git-branching-workflow-template-id"
                              ]
                            }
                            
                            // Step 3: Create another task with different workflow focus
                            {
                              "title": "User Profile Management",
                              "description": "Implement user profile editing and avatar upload",
                              "priority": "medium",
                              "complexity": 5,
                              "tags": "task-type-feature,user-profile,avatar-upload",
                              "featureId": "user-management-feature-id",
                              "templateIds": [
                                "task-implementation-workflow-template-id",
                                "testing-strategy-template-id"
                              ]
                            }
                            
                            // Step 4: Get feature with associated tasks
                            {
                              "id": "user-management-feature-id",
                              "includeTasks": true,
                              "includeSections": true
                            }
                            ```
                            
                            ## Workflow 5: Updating Templates for Consistent Documentation
                            
                            ```json
                            // Step 1: Create a new template for consistent documentation
                            {
                              "name": "API Endpoint Documentation",
                              "description": "Standard documentation for API endpoints",
                              "targetEntityType": "TASK"
                            }
                            
                            // Step 2: Add sections to the template
                            {
                              "templateId": "ddd8511-f30c-41d4-a716-557788990000",
                              "title": "Endpoint Specification",
                              "usageDescription": "Detailed API endpoint specifications including HTTP method, path, and parameters",
                              "contentSample": "## Endpoint\\n\\n**Path**: `/api/resource`\\n**Method**: POST\\n\\n## Parameters\\n\\n...",
                              "contentFormat": "MARKDOWN",
                              "ordinal": 0
                            }
                            
                            // Add another section to the template
                            {
                              "templateId": "ddd8511-f30c-41d4-a716-557788990000",
                              "title": "Request/Response Examples",
                              "usageDescription": "Example request and response payloads",
                              "contentSample": "## Request Example\\n\\n```json\\n{\\n  \\\"field\\\": \\\"value\\\"\\n}\\n```\\n\\n## Response Example\\n\\n```json\\n{\\n  \\\"result\\\": \\\"success\\\"\\n}\\n```",
                              "contentFormat": "MARKDOWN",
                              "ordinal": 1
                            }
                            
                            // Step 3: Apply the template to existing tasks
                            {
                              "templateIds": ["ddd8511-f30c-41d4-a716-557788990000"],
                              "entityType": "TASK",
                              "entityId": "eee8400-e29b-41d4-a716-446655440000"
                            }
                            ```
                            
                            These workflows demonstrate how to effectively use the new focused, composable template system for different scenarios. The key principles are:
                            
                            1. **Category-Based Selection**: Choose templates from different categories based on your needs
                            2. **Workflow + Documentation**: Combine process guidance (workflows) with information capture (documentation)
                            3. **Quality Assurance**: Include quality templates (Testing Strategy, Definition of Done) for important work
                            4. **Progressive Enhancement**: Start with basic templates and add more as complexity increases
                            
                            The new template system provides flexibility while maintaining consistency and reducing cognitive overhead through focused, purpose-built templates.
                            
                            ## Filtering Tasks by Type
                            
                            Using the task type tagging convention, you can easily filter tasks:
                            
                            ```json
                            // Find all bug tasks
                            {
                              "tag": "task-type-bug"
                            }
                            
                            // Find all feature tasks  
                            {
                              "tag": "task-type-feature"
                            }
                            
                            // Find all enhancement tasks
                            {
                              "tag": "task-type-enhancement"
                            }
                            
                            // Find urgent bugs
                            {
                              "tag": "task-type-bug",
                              "priority": "high"
                            }
                            ```
                            
                            This makes it clear in `get_task_overview` and search results which tasks are bugs versus features or other work types.
                            """.trimIndent()
                        )
                    )
                ),
                _meta = JsonObject(emptyMap())
            )
        }
    }
}