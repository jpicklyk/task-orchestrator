package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for listing templates with various filtering options.
 *
 * This tool supports filtering templates by target entity type, built-in status,
 * enabled status, and tags, allowing for efficient template discovery.
 */
class ListTemplatesTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "list_templates"

    override val description: String = """Retrieve a list of templates with optional filtering.
        
        ## Purpose
        CRITICAL for template-driven workflow: Always check available templates before creating
        tasks or features to ensure consistent documentation structure and comprehensive coverage.
        
        ## Template System Overview
        The system provides focused, composable templates organized into categories:
        
        **AI Workflow Instructions** (process guidance):
        - Local Git Branching Workflow: Step-by-step git workflow with MCP tool integration
        - GitHub PR Workflow: Complete PR creation and management process
        - Task Implementation Workflow: Structured implementation approach
        - Bug Investigation Workflow: Systematic bug analysis and resolution
        
        **Documentation Properties** (information capture):
        - Technical Approach: Architecture and implementation strategy
        - Requirements Specification: Detailed requirements and acceptance criteria
        - Context & Background: Project context and background information
        
        **Process & Quality** (standards and completion):
        - Testing Strategy: Comprehensive testing approach and coverage
        - Definition of Done: Clear completion criteria and quality gates
        
        ## Usage Patterns
        **RECOMMENDED WORKFLOW**:
        1. Run `list_templates` with targetEntityType filter (TASK or FEATURE)
        2. Identify templates from multiple categories for comprehensive coverage
        3. Apply templates during create_task/create_feature using templateIds parameter
        4. Use template combinations: Workflow + Documentation + Quality
        
        ## Template Selection Strategy
        **For Implementation Tasks**:
        - Technical Approach + Task Implementation Workflow + Testing Strategy
        
        **For Bug Fixes**:
        - Bug Investigation Workflow + Technical Approach + Definition of Done
        
        **For Complex Features**:
        - Requirements Specification + Technical Approach + Local Git Branching + Testing Strategy
        
        **For Feature Planning**:
        - Context & Background + Requirements Specification + Testing Strategy
        
        ## Filtering Best Practices
        - Filter by targetEntityType to match your creation needs (TASK vs FEATURE)
        - Use isEnabled=true to only see available templates
        - Filter by tags to find domain-specific templates ("authentication", "api", "testing")
        - Built-in templates (isBuiltIn=true) provide proven workflow patterns
        
        ## Context Efficiency
        Templates are designed for modular composition rather than monolithic coverage:
        - Small, focused templates (3-4 sections each) reduce cognitive overhead
        - Composable design allows mixing based on specific needs
        - Category-based organization helps with systematic selection
        
        Example successful response:
        {
          "success": true,
          "message": "Retrieved 8 templates",
          "data": {
            "templates": [
              {
                "id": "workflow-uuid-001",
                "name": "Task Implementation Workflow",
                "description": "Step-by-step implementation guidance with MCP tool integration",
                "targetEntityType": "TASK",
                "isBuiltIn": true,
                "isProtected": true,
                "isEnabled": true,
                "tags": ["workflow", "implementation", "process"]
              },
              {
                "id": "tech-uuid-002",
                "name": "Technical Approach",
                "description": "Architecture and implementation strategy documentation",
                "targetEntityType": "TASK",
                "isBuiltIn": true,
                "isProtected": true,
                "isEnabled": true,
                "tags": ["technical", "architecture", "documentation"]
              },
              {
                "id": "test-uuid-003",
                "name": "Testing Strategy",
                "description": "Comprehensive testing approach and coverage requirements",
                "targetEntityType": "TASK",
                "isBuiltIn": true,
                "isProtected": true,
                "isEnabled": true,
                "tags": ["testing", "quality", "coverage"]
              }
            ],
            "count": 8,
            "filters": {
              "targetEntityType": "TASK",
              "isBuiltIn": "Any",
              "isEnabled": "true",
              "tags": "Any"
            }
          }
        }
        
        Common error responses:
        - VALIDATION_ERROR: When targetEntityType is not TASK or FEATURE
        - DATABASE_ERROR: When there's an issue retrieving templates
        - INTERNAL_ERROR: For unexpected system errors
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "targetEntityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by entity type (TASK or FEATURE)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "isBuiltIn" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for built-in templates only")
                    )
                ),
                "isEnabled" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("boolean"),
                        "description" to JsonPrimitive("Filter for enabled templates only")
                    )
                ),
                "tags" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Filter by tags (comma-separated)")
                    )
                )
            )
        ),
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        // Validate targetEntityType if present
        optionalString(params, "targetEntityType")?.let { entityType ->
            try {
                EntityType.valueOf(entityType)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("Invalid target entity type: $entityType. Must be 'TASK' or 'FEATURE'")
            }
        }

        // Boolean parameters (isBuiltIn, isEnabled) don't need special validation

        // Tags parameter doesn't need special validation
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing list_templates tool")

        try {
            // Extract filter parameters
            val targetEntityTypeStr = optionalString(params, "targetEntityType")
            val targetEntityType = targetEntityTypeStr?.let {
                try {
                    EntityType.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    return errorResponse(
                        message = "Invalid target entity type: $it",
                        code = ErrorCodes.VALIDATION_ERROR
                    )
                }
            }

            val isBuiltIn = if (params.jsonObject.containsKey("isBuiltIn")) {
                optionalBoolean(params, "isBuiltIn")
            } else {
                null
            }

            val isEnabled = if (params.jsonObject.containsKey("isEnabled")) {
                optionalBoolean(params, "isEnabled")
            } else {
                null
            }

            val tagsStr = optionalString(params, "tags")
            val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

            // Retrieve templates from repository
            val result = context.repositoryProvider.templateRepository()
                .getAllTemplates(targetEntityType, isBuiltIn, isEnabled, tags)

            return when (result) {
                is Result.Success -> {
                    val templates = result.data

                    // Build the response
                    val responseData = buildJsonObject {
                        put("templates", buildJsonArray {
                            templates.forEach { template ->
                                add(buildJsonObject {
                                    put("id", template.id.toString())
                                    put("name", template.name)
                                    put("description", template.description)
                                    put("targetEntityType", template.targetEntityType.name)
                                    put("isBuiltIn", template.isBuiltIn)
                                    put("isProtected", template.isProtected)
                                    put("isEnabled", template.isEnabled)
                                    put("tags", buildJsonArray {
                                        template.tags.forEach { add(it) }
                                    })
                                })
                            }
                        })

                        put("count", templates.size)

                        // Include the filters that were applied
                        put("filters", buildJsonObject {
                            put("targetEntityType", targetEntityTypeStr ?: "Any")
                            put("isBuiltIn", isBuiltIn?.toString() ?: "Any")
                            put("isEnabled", isEnabled?.toString() ?: "Any")
                            put("tags", tagsStr ?: "Any")
                        })
                    }

                    // Create an appropriate message based on the number of templates found
                    val message = when {
                        templates.isEmpty() -> "No templates found matching criteria"
                        templates.size == 1 -> "Retrieved 1 template"
                        else -> "Retrieved ${templates.size} templates"
                    }

                    successResponse(responseData, message)
                }

                is Result.Error -> {
                    errorResponse(
                        message = "Failed to retrieve templates",
                        code = ErrorCodes.DATABASE_ERROR,
                        details = result.error.toString()
                    )
                }
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error listing templates", e)
            return errorResponse(
                message = "Failed to list templates",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}
