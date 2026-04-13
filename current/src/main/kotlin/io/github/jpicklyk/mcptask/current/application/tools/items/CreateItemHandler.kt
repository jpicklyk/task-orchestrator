package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Handles the `create` operation for [ManageItemsTool].
 *
 * Creates WorkItems from a JSON array, computing depth from parent hierarchy,
 * validating constraints, and looking up expected notes from the note schema service.
 */
class CreateItemHandler(
    private val hierarchyValidator: ItemHierarchyValidator = ItemHierarchyValidator()
) {
    /**
     * Executes a batch create of WorkItems.
     *
     * @param items JSON array of item objects to create
     * @param sharedParentId Optional default parent ID applied to items without a per-item parentId
     * @param context The tool execution context providing repository and schema access
     * @return A JSON response envelope with created items, counts, and any failures
     */
    suspend fun execute(
        items: JsonArray,
        sharedParentId: UUID?,
        sharedTraits: String?,
        context: ToolExecutionContext
    ): JsonElement {
        val repo = context.workItemRepository()

        val createdItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()

        for ((index, element) in items.withIndex()) {
            try {
                val itemObj =
                    element as? JsonObject
                        ?: throw ToolValidationException("Item at index $index must be a JSON object")

                val title =
                    extractItemString(itemObj, "title")
                        ?: throw ToolValidationException("Item at index $index: 'title' is required")

                val description = extractItemString(itemObj, "description")
                val summary = extractItemString(itemObj, "summary") ?: ""
                val roleStr = extractItemString(itemObj, "role")
                val statusLabel = extractItemString(itemObj, "statusLabel")
                val priorityStr = extractItemString(itemObj, "priority")
                val complexity = extractItemInt(itemObj, "complexity")
                val requiresVerification = extractItemBoolean(itemObj, "requiresVerification") ?: false
                val metadata = extractItemString(itemObj, "metadata")
                val tags = extractItemString(itemObj, "tags")
                val type = extractItemString(itemObj, "type")
                val rawProperties = extractItemString(itemObj, "properties")
                val traitsStr = extractItemString(itemObj, "traits") ?: sharedTraits
                val properties = PropertiesHelper.mergeTraitsFromString(rawProperties, traitsStr)

                // Pre-generate the UUID so we can guard against self-parent before construction
                val itemId = UUID.randomUUID()

                // Resolve parentId: per-item overrides shared default
                val itemParentIdStr = extractItemString(itemObj, "parentId")
                val parentId =
                    if (itemParentIdStr != null) {
                        resolveWorkItemIdString(itemParentIdStr, context, "Item at index $index: 'parentId'")
                    } else {
                        sharedParentId
                    }

                // Validate hierarchy and compute depth
                val depth =
                    hierarchyValidator.validateAndComputeDepth(
                        itemId = itemId,
                        parentId = parentId,
                        repo = repo,
                        errorPrefix = "Item at index $index"
                    )

                // Parse role with default
                val role =
                    if (roleStr != null) {
                        Role.fromString(roleStr)
                            ?: throw ToolValidationException(
                                "Item at index $index: invalid role '$roleStr'. Valid: ${Role.VALID_NAMES}"
                            )
                    } else {
                        Role.QUEUE
                    }

                // Parse priority with default
                val priority =
                    if (priorityStr != null) {
                        Priority.fromString(priorityStr)
                            ?: throw ToolValidationException(
                                "Item at index $index: invalid priority '$priorityStr'. Valid: high, medium, low"
                            )
                    } else {
                        Priority.MEDIUM
                    }

                // Validate complexity range if provided
                if (complexity != null && complexity !in 1..10) {
                    throw ToolValidationException("Item at index $index: complexity must be between 1 and 10")
                }

                val workItem =
                    WorkItem(
                        id = itemId,
                        parentId = parentId,
                        title = title,
                        description = description,
                        summary = summary,
                        role = role,
                        statusLabel = statusLabel,
                        priority = priority,
                        complexity = complexity,
                        requiresVerification = requiresVerification,
                        depth = depth,
                        metadata = metadata,
                        tags = tags,
                        type = type,
                        properties = properties
                    )

                when (val result = repo.create(workItem)) {
                    is Result.Success -> {
                        val createdTags = result.data.tags
                        val resolvedSchema = context.resolveSchema(result.data)
                        val schemaFields = buildSchemaResponseFields(resolvedSchema)
                        createdItems.add(
                            buildJsonObject {
                                put("id", JsonPrimitive(result.data.id.toString()))
                                put("title", JsonPrimitive(result.data.title))
                                put("depth", JsonPrimitive(result.data.depth))
                                put("role", JsonPrimitive(result.data.role.toJsonString()))
                                put("priority", JsonPrimitive(result.data.priority.toJsonString()))
                                put("requiresVerification", JsonPrimitive(result.data.requiresVerification))
                                if (createdTags != null) {
                                    put("tags", JsonPrimitive(createdTags))
                                } else {
                                    put("tags", JsonNull)
                                }
                                put("schemaMatch", JsonPrimitive(schemaFields.schemaMatch))
                                put("expectedNotes", schemaFields.expectedNotes)
                            }
                        )
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                put("error", JsonPrimitive(result.error.message))
                            }
                        )
                    }
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
            } catch (e: Exception) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Unexpected error"))
                    }
                )
            }
        }

        val availableTraits = context.noteSchemaService().getAvailableTraits()
        val data =
            buildJsonObject {
                put("items", JsonArray(createdItems))
                put("created", JsonPrimitive(createdItems.size))
                put("failed", JsonPrimitive(failures.size))
                if (failures.isNotEmpty()) {
                    put("failures", JsonArray(failures))
                }
                if (availableTraits.isNotEmpty()) {
                    put(
                        "availableTraits",
                        JsonArray(availableTraits.map { JsonPrimitive(it) })
                    )
                }
            }

        return ResponseUtil.createSuccessResponse(data)
    }
}
