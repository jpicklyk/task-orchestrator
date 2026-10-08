package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.service.PlacedWriteOutcome
import io.github.jpicklyk.mcptask.current.application.service.WorkItemPlacementService
import io.github.jpicklyk.mcptask.current.application.service.buildSchemaResponseFields
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyReadOrNull
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.tools.ElementOutcome
import io.github.jpicklyk.mcptask.current.application.tools.ElementResult
import io.github.jpicklyk.mcptask.current.application.tools.KeyedCall
import io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
import io.github.jpicklyk.mcptask.current.application.tools.ResponseUtil
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.omitOnConfigUnavailable
import io.github.jpicklyk.mcptask.current.application.tools.resolveWorkItemIdString
import io.github.jpicklyk.mcptask.current.application.tools.runElement
import io.github.jpicklyk.mcptask.current.application.tools.toJsonString
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
        context: ToolExecutionContext,
        keyed: KeyedCall? = null
    ): JsonElement {
        val repo = context.workItemRepository()

        val createdItems = mutableListOf<JsonObject>()
        val failures = mutableListOf<JsonObject>()
        val createdRootIds = mutableSetOf<UUID>()

        for ((index, element) in items.withIndex()) {
            try {
                // The created item, when this call wrote it (a replay has only the stored fragment).
                var written: WorkItem? = null
                val outcome =
                    runElement(keyed, index, element) {
                        written = null
                        val itemObj = element as? JsonObject
                        if (itemObj == null) {
                            val message = "Item at index $index must be a JSON object"
                            ElementResult.Invalid(failureJson(index, message), message)
                        } else {
                            val spec = parseItemSpec(itemObj, index, sharedParentId, sharedTraits, context, repo)
                            when (val createResult = createWithPlacement(spec, index, repo, context.unitOfWork)) {
                                is PersistedCreate.Written -> {
                                    written = createResult.item
                                    ElementResult.Done(buildCreatedItemFragment(createResult.item))
                                }
                                is PersistedCreate.Failed -> ElementResult.Failed(failureJson(index, createResult.message))
                            }
                        }
                    }

                when (outcome) {
                    is ElementOutcome.Succeeded -> {
                        val item = written ?: fetchCreated(outcome.fragment, repo)
                        item?.rootId?.let { createdRootIds.add(it) }
                        createdItems.add(if (item != null) withSchemaDecoration(outcome.fragment, item, context) else outcome.fragment)
                    }
                    is ElementOutcome.Failed -> failures.add(outcome.failure)
                }
            } catch (e: ToolValidationException) {
                failures.add(
                    buildJsonObject {
                        put("index", JsonPrimitive(index))
                        put("error", JsonPrimitive(e.message ?: "Validation failed"))
                    }
                )
            } catch (e: Exception) {
                e.rethrowIfCancellation()
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
    ) {
        /** Builds the [WorkItem] for this spec once [parentId]/[rootId]/[depth] placement is resolved. */
        fun toWorkItem(
            parentId: UUID?,
            rootId: UUID,
            depth: Int
        ): WorkItem =
            WorkItem(
                id = itemId,
                parentId = parentId,
                rootId = rootId,
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
    }

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

        // Existence guard only: a fresh random itemId can never be its own parent or an ancestor
        // of the parent, so no cycle check applies on create. Placement (depth/rootId) is resolved
        // inside the write transaction in createWithPlacement (AR-19).
        if (parentId != null && !WorkItemPlacementService(repo, hierarchyValidator).parentExists(parentId)) {
            throw ToolValidationException("Item at index $index: parent '$parentId' not found")
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
     * Resolves depth/rootId and creates via [WorkItemPlacementService.create]: when
     * [ParsedCreateSpec.parentId] is non-null, placement is read INSIDE the same transaction as
     * the insert, so a concurrent reparent/delete of the parent cannot leave this new item
     * stamped with stale placement (AR-19). Root items need no placement read at all.
     */
    private suspend fun createWithPlacement(
        spec: ParsedCreateSpec,
        index: Int,
        repo: WorkItemRepository,
        unitOfWork: UnitOfWork
    ): PersistedCreate =
        when (
            val outcome =
                WorkItemPlacementService(repo, hierarchyValidator).create(unitOfWork, spec.itemId, spec.parentId) { depth, rootId ->
                    spec.toWorkItem(parentId = spec.parentId, rootId = rootId, depth = depth)
                }
        ) {
            is PlacedWriteOutcome.Written -> PersistedCreate.Written(outcome.item)
            is PlacedWriteOutcome.ParentNotFound ->
                throw ToolValidationException("Item at index $index: parent '${outcome.parentId}' not found")
            is PlacedWriteOutcome.BuildFailed -> throw ToolValidationException(outcome.message)
            is PlacedWriteOutcome.WriteFailed -> PersistedCreate.Failed(LegacyFaults.message(outcome.error))
            is PlacedWriteOutcome.CascadeFailed -> throw IllegalStateException(outcome.message)
        }

    /** The per-item result of [createWithPlacement]: the written item, or the legacy failure message. */
    private sealed interface PersistedCreate {
        data class Written(
            val item: WorkItem
        ) : PersistedCreate

        data class Failed(
            val message: String
        ) : PersistedCreate
    }

    private fun failureJson(
        index: Int,
        message: String
    ): JsonObject =
        buildJsonObject {
            put("index", JsonPrimitive(index))
            put("error", JsonPrimitive(message))
        }

    /** Reloads the item a replayed fragment describes, for the response-only decoration; null when it is gone. */
    private suspend fun fetchCreated(
        fragment: JsonObject,
        repo: WorkItemRepository
    ): WorkItem? {
        val id =
            (fragment["id"] as? JsonPrimitive)?.content?.let { runCatchingNonCancellation { UUID.fromString(it) }.getOrNull() }
                ?: return null
        return legacyReadOrNull { repo.getById(id) }
    }

    /**
     * Builds the stored base fragment for one successfully-created item: everything that is a fact of
     * the committed create. The config-derived schemaMatch/expectedNotes are not part of it (see
     * [withSchemaDecoration]).
     */
    private fun buildCreatedItemFragment(item: WorkItem): JsonObject {
        val createdTags = item.tags
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
        }
    }

    /**
     * Adds the response-only schemaMatch/expectedNotes decoration to [fragment]. The item is ALREADY
     * PERSISTED at this point — per D7, a per-root config read failure resolving this decoration must
     * never be reported as a failure of this (already-committed) create; schemaMatch/expectedNotes
     * are simply omitted and a WARN is logged (see [omitOnConfigUnavailable]). Recomputed on every
     * replay, never stored.
     */
    private suspend fun withSchemaDecoration(
        fragment: JsonObject,
        item: WorkItem,
        context: ToolExecutionContext
    ): JsonObject {
        val schemaFields =
            omitOnConfigUnavailable(logger, "schema", item.id) {
                buildSchemaResponseFields(context.resolveSchema(item))
            } ?: return fragment
        return buildJsonObject {
            fragment.forEach { (k, v) -> put(k, v) }
            put("schemaMatch", JsonPrimitive(schemaFields.schemaMatch))
            put("expectedNotes", schemaFields.expectedNotes)
        }
    }
}
