package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.omitOnConfigUnavailable
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger(CreateItemHandler::class.java)

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
        val createdRootIds = mutableSetOf<UUID>()

        for ((index, element) in items.withIndex()) {
            try {
                val itemObj =
                    element as? JsonObject
                        ?: throw ToolValidationException("Item at index $index must be a JSON object")

                val spec = parseItemSpec(itemObj, index, sharedParentId, sharedTraits, context, repo)
                val createResult = createWithPlacement(spec, index, repo)

                when (createResult) {
                    is Result.Success -> {
                        createResult.data.rootId?.let { createdRootIds.add(it) }
                        createdItems.add(buildCreatedItemJson(createResult.data, context))
                    }
                    is Result.Error -> {
                        failures.add(
                            buildJsonObject {
                                put("index", JsonPrimitive(index))
                                put("error", JsonPrimitive(createResult.error.message))
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

        // All items above are ALREADY PERSISTED — per D7, a per-root config read failure resolving
        // this response-only hint must never fail the whole batch. `availableTraits` is simply
        // omitted and a WARN is logged.
        val availableTraits =
            omitOnConfigUnavailable(logger, "availableTraits", createdRootIds) {
                context.availableTraits(createdRootIds)
            } ?: emptyList()
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

    /**
     * A single item's parsed and validated create spec (field extraction, role/priority
     * parsing, complexity range check, and parentId resolution + hierarchy guards). Placement
     * (depth/rootId) is deliberately NOT part of this spec — it is resolved fresh inside the
     * write transaction in [createWithPlacement] (AR-19).
     */
    private data class ParsedCreateSpec(
        val itemId: UUID,
        val title: String,
        val description: String?,
        val summary: String,
        val role: Role,
        val statusLabel: String?,
        val priority: Priority,
        val complexity: Int?,
        val requiresVerification: Boolean,
        val metadata: String?,
        val tags: String?,
        val type: String?,
        val properties: String?,
        val parentId: UUID?
    )

    /**
     * Extracts and validates all fields for one item-at-`index` in a create batch: title,
     * optional fields, role/priority (with defaults), complexity range, and parentId
     * resolution + hierarchy guards (self-parent, ancestor cycle). Mirrors the pre-refactor
     * inline logic byte-for-byte, including error messages.
     */
    private suspend fun parseItemSpec(
        itemObj: JsonObject,
        index: Int,
        sharedParentId: UUID?,
        sharedTraits: String?,
        context: ToolExecutionContext,
        repo: WorkItemRepository
    ): ParsedCreateSpec {
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

        // Validate hierarchy guards (self-parent, ancestor cycle) — the returned depth is
        // NOT used to stamp; placement is resolved fresh inside the write transaction
        // below so a concurrent reparent/delete of the parent cannot leave this item
        // stamped with stale depth/rootId (AR-19).
        if (parentId != null) {
            hierarchyValidator.validateAndComputeDepth(
                itemId = itemId,
                parentId = parentId,
                repo = repo,
                errorPrefix = "Item at index $index"
            )
        }

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

        return ParsedCreateSpec(
            itemId = itemId,
            title = title,
            description = description,
            summary = summary,
            role = role,
            statusLabel = statusLabel,
            priority = priority,
            complexity = complexity,
            requiresVerification = requiresVerification,
            metadata = metadata,
            tags = tags,
            type = type,
            properties = properties,
            parentId = parentId
        )
    }

    /**
     * Resolves depth/rootId and creates in ONE transaction: when [ParsedCreateSpec.parentId] is
     * non-null, the parent is read via `resolveChildPlacement` INSIDE the same transaction as
     * the insert, so a concurrent reparent/delete of the parent cannot leave this new item
     * stamped with stale placement (AR-19). Root items (no parent) need no placement read at
     * all. Mirrors the pre-refactor inline logic byte-for-byte.
     */
    private suspend fun createWithPlacement(
        spec: ParsedCreateSpec,
        index: Int,
        repo: WorkItemRepository
    ): Result<WorkItem> {
        var createResult: Result<WorkItem>? = null
        var placementNotFoundMessage: String? = null
        if (spec.parentId == null) {
            val workItem =
                WorkItem(
                    id = spec.itemId,
                    parentId = null,
                    rootId = spec.itemId,
                    title = spec.title,
                    description = spec.description,
                    summary = spec.summary,
                    role = spec.role,
                    statusLabel = spec.statusLabel,
                    priority = spec.priority,
                    complexity = spec.complexity,
                    requiresVerification = spec.requiresVerification,
                    depth = 0,
                    metadata = spec.metadata,
                    tags = spec.tags,
                    type = spec.type,
                    properties = spec.properties
                )
            createResult = repo.create(workItem)
        } else {
            repo.inTransaction {
                when (val placementResult = repo.resolveChildPlacement(spec.parentId)) {
                    is Result.Success -> {
                        val placement = placementResult.data
                        val workItem =
                            WorkItem(
                                id = spec.itemId,
                                parentId = spec.parentId,
                                rootId = placement.rootId,
                                title = spec.title,
                                description = spec.description,
                                summary = spec.summary,
                                role = spec.role,
                                statusLabel = spec.statusLabel,
                                priority = spec.priority,
                                complexity = spec.complexity,
                                requiresVerification = spec.requiresVerification,
                                depth = placement.depth,
                                metadata = spec.metadata,
                                tags = spec.tags,
                                type = spec.type,
                                properties = spec.properties
                            )
                        createResult = repo.create(workItem)
                    }
                    is Result.Error -> {
                        placementNotFoundMessage = "Item at index $index: parent '${spec.parentId}' not found"
                    }
                }
            }
            if (placementNotFoundMessage != null) {
                throw ToolValidationException(placementNotFoundMessage!!)
            }
        }
        return createResult!!
    }

    /**
     * Builds the response JSON for one successfully-created item, including the response-only
     * schemaMatch/expectedNotes decoration. The item is ALREADY PERSISTED at this point — per
     * D7, a per-root config read failure resolving this decoration must never be reported as a
     * failure of this (already-committed) create; schemaMatch/expectedNotes are simply omitted
     * and a WARN is logged (see [omitOnConfigUnavailable]).
     */
    private suspend fun buildCreatedItemJson(
        item: WorkItem,
        context: ToolExecutionContext
    ): JsonObject {
        val createdTags = item.tags
        val schemaFields =
            omitOnConfigUnavailable(logger, "schema", item.id) {
                buildSchemaResponseFields(context.resolveSchema(item))
            }
        return buildJsonObject {
            put("id", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("depth", JsonPrimitive(item.depth))
            put("role", JsonPrimitive(item.role.toJsonString()))
            put("priority", JsonPrimitive(item.priority.toJsonString()))
            put("requiresVerification", JsonPrimitive(item.requiresVerification))
            if (createdTags != null) {
                put("tags", JsonPrimitive(createdTags))
            } else {
                put("tags", JsonNull)
            }
            if (schemaFields != null) {
                put("schemaMatch", JsonPrimitive(schemaFields.schemaMatch))
                put("expectedNotes", schemaFields.expectedNotes)
            }
        }
    }
}
