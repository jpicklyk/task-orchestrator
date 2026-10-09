package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
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
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Handles the `create` operation for [ManageItemsTool].
 *
 * Parses each element into an [ItemCreateCommand] and creates it through
 * [io.github.jpicklyk.mcptask.current.application.service.ItemCommandService]: every item lands in QUEUE (a `role`
 * field is rejected), its depth/rootId come from the parent read inside the write unit, and a TERMINAL parent under
 * auto lifecycle rejects the element. Expected notes come from the note schema service.
 */
class CreateItemHandler {
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
                            val command = parseItemSpec(itemObj, index, sharedParentId, sharedTraits, context)
                            when (val created = context.itemCommandService.create(command)) {
                                is Outcome.Ok -> {
                                    written = created.value
                                    ElementResult.Done(buildCreatedItemFragment(created.value))
                                }
                                is Outcome.Err -> {
                                    val error = created.error
                                    when {
                                        error.code == ErrorCode.NOT_FOUND ->
                                            throw ToolValidationException("Item at index $index: parent '${command.parentId}' not found")
                                        ItemCommandErrors.isClosedParent(error) ->
                                            ElementResult.Failed(
                                                failureJson(
                                                    index,
                                                    "Item at index $index: parent '${command.parentId}' is terminal under auto " +
                                                        "lifecycle; reopen it before adding children"
                                                )
                                            )
                                        error.code == ErrorCode.INVALID_REQUEST -> throw ToolValidationException(error.message)
                                        else -> ElementResult.Failed(failureJson(index, LegacyFaults.message(error)))
                                    }
                                }
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
            } catch (e: PerRootConfigUnavailableException) {
                // A per-root config fault resolving a TERMINAL parent's lifecycle fails only this element.
                failures.add(configUnavailableFailure("index", JsonPrimitive(index), e))
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
     * Extracts and validates all fields for one item-at-`index` in a create batch into an [ItemCreateCommand]: title,
     * optional fields, priority (default medium), complexity range and parentId resolution. A `role` field is
     * rejected (D1): every item is created in QUEUE. Parent existence, placement and the closed-parent rule are the
     * service's, inside the write unit.
     */
    private suspend fun parseItemSpec(
        itemObj: JsonObject,
        index: Int,
        sharedParentId: UUID?,
        sharedTraits: String?,
        context: ToolExecutionContext
    ): ItemCreateCommand {
        val title =
            extractItemString(itemObj, "title")
                ?: throw ToolValidationException("Item at index $index: 'title' is required")

        if (itemObj.containsKey("role")) {
            throw ToolValidationException(
                "Item at index $index: 'role' is not accepted on create; items are created in queue (use advance_item to move them)"
            )
        }

        val description = extractItemString(itemObj, "description")
        val summary = extractItemString(itemObj, "summary") ?: ""
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

        // Resolve parentId: per-item overrides shared default
        val itemParentIdStr = extractItemString(itemObj, "parentId")
        val parentId =
            if (itemParentIdStr != null) {
                resolveWorkItemIdString(itemParentIdStr, context, "Item at index $index: 'parentId'")
            } else {
                sharedParentId
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

        return ItemCreateCommand(
            parentId = parentId,
            title = title,
            description = description,
            summary = summary,
            statusLabel = statusLabel,
            priority = priority,
            complexity = complexity,
            requiresVerification = requiresVerification,
            metadata = metadata,
            tags = tags,
            type = type,
            properties = properties
        )
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
