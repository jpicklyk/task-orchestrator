package io.github.jpicklyk.mcptask.application.tools.template

import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.SimpleLockAwareToolDefinition
import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for applying one or more templates to an entity (task or feature).
 * 
 * This tool creates sections based on template section definitions, allowing for
 * standardized documentation patterns to be applied to tasks and features. It supports
 * applying a single template or multiple templates in one operation.
 */
class ApplyTemplateTool(
    lockingService: SimpleLockingService? = null,
    sessionManager: SimpleSessionManager? = null
) : SimpleLockAwareToolDefinition(lockingService, sessionManager) {
    override val category: ToolCategory = ToolCategory.TEMPLATE_MANAGEMENT

    override val name: String = "apply_template"

    override val title: String = "Apply Template to Entity"

    override val outputSchema: Tool.Output = Tool.Output(
        properties = JsonObject(
            mapOf(
                "success" to JsonObject(mapOf("type" to JsonPrimitive("boolean"))),
                "message" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                "data" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("object"),
                        "description" to JsonPrimitive("Template application results"),
                        "properties" to JsonObject(
                            mapOf(
                                "entityType" to JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                "entityId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                "totalSectionsCreated" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                "appliedTemplates" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("array"),
                                        "items" to JsonObject(
                                            mapOf(
                                                "type" to JsonPrimitive("object"),
                                                "properties" to JsonObject(
                                                    mapOf(
                                                        "templateId" to JsonObject(mapOf("type" to JsonPrimitive("string"), "format" to JsonPrimitive("uuid"))),
                                                        "sectionsCreated" to JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                                                        "sections" to JsonObject(mapOf("type" to JsonPrimitive("array")))
                                                    )
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                ),
                "error" to JsonObject(mapOf("type" to JsonArray(listOf(JsonPrimitive("object"), JsonPrimitive("null"))))),
                "metadata" to JsonObject(mapOf("type" to JsonPrimitive("object")))
            )
        )
    )

    override val description: String = """Applies one or more templates to a task or feature, creating sections based on template definitions.

        Parameters:
        - templateIds (required): Array of template UUIDs (use single-item array for one template)
        - entityType (required): TASK or FEATURE
        - entityId (required): Task or feature UUID

        Template Application:
        - Templates applied in array order
        - Later templates' sections appear after earlier ones
        - Each template creates its defined sections with sample content
        - Section ordinals maintain sequence across multiple templates

        Recommended Combinations:
        - Development Task: Technical Approach + Task Implementation + Testing Strategy
        - Bug Fix: Bug Investigation + Technical Approach + Definition of Done
        - Feature Planning: Requirements + Context & Background + Testing Strategy

        Usage notes:
        - PREFER applying templates during create_task/create_feature (more efficient)
        - Apply multiple templates in one call rather than separate calls
        - Templates must match entity type (TASK templates for tasks, FEATURE templates for features)
        - Use list_templates to discover available templates

        Related tools: create_task, create_feature, list_templates, get_template

        For detailed examples and patterns: task-orchestrator://docs/tools/apply-template
    """

    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "templateIds" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("array"),
                        "description" to JsonPrimitive("List of template IDs to apply. Use a single-item array for applying just one template."),
                        "items" to JsonObject(
                            mapOf(
                                "type" to JsonPrimitive("string"),
                                "format" to JsonPrimitive("uuid")
                            )
                        )
                    )
                ),
                "entityType" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("Type of entity (TASK or FEATURE)"),
                        "enum" to JsonArray(listOf("TASK", "FEATURE").map { JsonPrimitive(it) })
                    )
                ),
                "entityId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("ID of the entity to apply the template to"),
                        "format" to JsonPrimitive("uuid")
                    )
                )
            )
        ),
        required = listOf("entityType", "entityId", "templateIds")
    )

    override fun validateParams(params: JsonElement) {
        // Validate required parameters
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        // Validate templateIds parameter
        val templateIdsElement = paramsObj["templateIds"]
        if (templateIdsElement !is JsonArray) {
            throw ToolValidationException("Parameter 'templateIds' must be an array of strings (UUIDs)")
        }

        if (templateIdsElement.isEmpty()) {
            throw ToolValidationException("Parameter 'templateIds' cannot be an empty array")
        }

        for ((index, item) in templateIdsElement.withIndex()) {
            if (item !is JsonPrimitive || !item.isString) {
                throw ToolValidationException("templateIds[$index] must be a string (UUID)")
            }

            try {
                UUID.fromString(item.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("templateIds[$index] is not a valid UUID format")
            }
        }

        // Validate entity type and ID
        val entityTypeStr = requireString(params, "entityType")
        val entityIdStr = requireString(params, "entityId")

        // Validate entity type
        try {
            EntityType.valueOf(entityTypeStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entity type: $entityTypeStr. Must be 'TASK' or 'FEATURE'")
        }
        
        // Validate entityId format
        try {
            UUID.fromString(entityIdStr)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid entity ID format. Must be a valid UUID.")
        }
    }

    override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing apply_template tool")

        try {
            // Extract parameters
            val entityTypeStr = requireString(params, "entityType")
            val entityIdStr = requireString(params, "entityId")
            val entityType = EntityType.valueOf(entityTypeStr)
            val entityId = UUID.fromString(entityIdStr)
            
            // Get template IDs to apply
            val templateIds = mutableListOf<UUID>()

            // Extract templateIds from the array
            val templateIdsArray = (params as JsonObject)["templateIds"] as JsonArray
            templateIdsArray.forEach { item ->
                if (item is JsonPrimitive && item.isString) {
                    templateIds.add(UUID.fromString(item.content))
                }
            }
            
            // Validate that the entity exists
            val entityExists = when (entityType) {
                EntityType.TASK -> {
                    val taskResult = context.repositoryProvider.taskRepository().getById(entityId)
                    taskResult is Result.Success
                }
                EntityType.FEATURE -> {
                    val featureResult = context.repositoryProvider.featureRepository().getById(entityId)
                    featureResult is Result.Success
                }
                else -> false
            }

            if (!entityExists) {
                return errorResponse(
                    message = "Entity not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND,
                    details = "No ${entityType.name.lowercase()} exists with ID $entityId"
                )
            }
            
            // Check if we're applying a single template or multiple templates
            return if (templateIds.size == 1) {
                // Single template application
                applySingleTemplate(templateIds.first(), entityType, entityId, context)
            } else {
                // Multiple template application
                applyMultipleTemplates(templateIds, entityType, entityId, context)
            }
        } catch (e: Exception) {
            // Handle unexpected exceptions
            logger.error("Error applying template", e)
            return errorResponse(
                message = "Failed to apply template",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Apply a single template to an entity
     */
    private suspend fun applySingleTemplate(
        templateId: UUID,
        entityType: EntityType,
        entityId: UUID,
        context: ToolExecutionContext
    ): JsonElement {
        // Verify template exists
        val templateResult = context.repositoryProvider.templateRepository().getTemplate(templateId)
        if (templateResult is Result.Error) {
            return if (templateResult.error is RepositoryError.NotFound) {
                errorResponse(
                    message = "Template not found",
                    code = ErrorCodes.RESOURCE_NOT_FOUND,
                    details = "No template exists with ID $templateId"
                )
            } else {
                errorResponse(
                    message = "Failed to retrieve template",
                    code = ErrorCodes.DATABASE_ERROR,
                    details = templateResult.error.toString()
                )
            }
        }

        // Apply the template
        val applyResult = context.repositoryProvider.templateRepository()
            .applyTemplate(templateId, entityType, entityId)

        return when (applyResult) {
            is Result.Success -> {
                val sections = applyResult.data
                val sectionCount = sections.size

                // Build the response
                val responseData = buildJsonObject {
                    put("templateId", templateId.toString())
                    put("entityType", entityType.name)
                    put("entityId", entityId.toString())
                    put("sectionsCreated", sectionCount)
                    put("sections", buildJsonArray {
                        sections.forEach { section ->
                            add(buildJsonObject {
                                put("id", section.id.toString())
                                put("title", section.title)
                                put("ordinal", section.ordinal)
                            })
                        }
                    })
                }

                successResponse(
                    responseData,
                    "Template applied successfully, created $sectionCount sections"
                )
            }

            is Result.Error -> {
                when (applyResult.error) {
                    is RepositoryError.NotFound -> {
                        errorResponse(
                            message = "Entity or template not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = applyResult.error.message
                        )
                    }

                    is RepositoryError.ValidationError -> {
                        errorResponse(
                            message = applyResult.error.message,
                            code = ErrorCodes.VALIDATION_ERROR
                        )
                    }

                    else -> {
                        errorResponse(
                            message = "Failed to apply template",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = applyResult.error.message
                        )
                    }
                }
            }
        }
    }
    
    /**
     * Apply multiple templates to an entity
     */
    private suspend fun applyMultipleTemplates(
        templateIds: List<UUID>,
        entityType: EntityType,
        entityId: UUID,
        context: ToolExecutionContext
    ): JsonElement {
        // Apply multiple templates
        val applyResult = context.repositoryProvider.templateRepository()
            .applyMultipleTemplates(templateIds, entityType, entityId)
        
        return when (applyResult) {
            is Result.Success -> {
                val results = applyResult.data
                val totalSections = results.values.sumOf { it.size }
                val appliedTemplatesCount = results.size
                
                // Build the response
                val responseData = buildJsonObject {
                    put("entityType", entityType.name)
                    put("entityId", entityId.toString())
                    put("totalSectionsCreated", totalSections)
                    put("appliedTemplates", buildJsonArray {
                        results.forEach { (templateId, sections) ->
                            add(buildJsonObject {
                                put("templateId", templateId.toString())
                                put("sectionsCreated", sections.size)
                                put("sections", buildJsonArray {
                                    sections.forEach { section ->
                                        add(buildJsonObject {
                                            put("id", section.id.toString())
                                            put("title", section.title)
                                            put("ordinal", section.ordinal)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }
                
                val message = "Applied $appliedTemplatesCount templates successfully, created $totalSections sections"
                successResponse(responseData, message)
            }
            
            is Result.Error -> {
                when (applyResult.error) {
                    is RepositoryError.NotFound -> {
                        errorResponse(
                            message = "One or more templates or the entity not found",
                            code = ErrorCodes.RESOURCE_NOT_FOUND,
                            details = applyResult.error.message
                        )
                    }
                    
                    is RepositoryError.ValidationError -> {
                        errorResponse(
                            message = applyResult.error.message,
                            code = ErrorCodes.VALIDATION_ERROR
                        )
                    }
                    
                    else -> {
                        errorResponse(
                            message = "Failed to apply templates",
                            code = ErrorCodes.DATABASE_ERROR,
                            details = applyResult.error.message
                        )
                    }
                }
            }
        }
    }
}