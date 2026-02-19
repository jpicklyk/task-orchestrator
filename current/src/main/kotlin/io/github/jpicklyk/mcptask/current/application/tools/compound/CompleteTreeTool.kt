package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Completes (or cancels) all descendants of a root item, or an explicit list of items,
 * in topological dependency order.
 *
 * Gate enforcement: if an item's tags match a note schema, all required notes must be
 * filled before the item can be completed. Gate failures propagate — dependents of a
 * gate-failed item within the target set are skipped.
 *
 * Parameters:
 * - rootId (optional UUID): complete all descendants of this item
 * - itemIds (optional array of UUID strings): explicit list of items to complete
 * - trigger (optional string, default "complete"): "complete" or "cancel"
 *
 * One of rootId or itemIds must be provided.
 */
class CompleteTreeTool : BaseToolDefinition() {

    override val name = "complete_tree"

    override val description = """
Complete or cancel all descendants of a root item (or an explicit list of items) in topological dependency order.

**Parameters:**
- `rootId` (optional UUID string): complete all descendants of this item (exclusive with itemIds)
- `itemIds` (optional array of UUID strings): explicit list of items to complete
- `trigger` (optional string, default "complete"): "complete" or "cancel"

**Validation:** Exactly one of `rootId` or `itemIds` must be provided.

**Behavior:**
- Items are processed in topological order (respecting dependency edges within the target set).
- Gate check: if an item's tags match a note schema, all required notes must be filled before completing.
  Gate failures cause downstream dependents (within the target set) to be skipped.
- Items already in TERMINAL role are recorded as skipped.

**Response:**
```json
{
  "results": [
    { "itemId": "uuid", "title": "...", "applied": true, "trigger": "complete" },
    { "itemId": "uuid", "title": "...", "applied": false, "skipped": true, "skippedReason": "dependency gate failed" },
    { "itemId": "uuid", "title": "...", "applied": false, "gateErrors": ["missing: acceptance-criteria"] }
  ],
  "summary": { "total": 3, "completed": 1, "skipped": 2, "gateFailures": 1 }
}
```
    """.trimIndent()

    override val category = ToolCategory.WORKFLOW

    override val toolAnnotations = ToolAnnotations(
        readOnlyHint = false,
        destructiveHint = false,
        idempotentHint = false,
        openWorldHint = false
    )

    override val parameterSchema = ToolSchema(
        properties = buildJsonObject {
            put("rootId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("UUID of root item whose descendants should be completed. Mutually exclusive with itemIds."))
            })
            put("itemIds", buildJsonObject {
                put("type", JsonPrimitive("array"))
                put("description", JsonPrimitive("Explicit list of item UUIDs to complete. Mutually exclusive with rootId."))
                put("items", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                })
            })
            put("trigger", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Transition trigger: 'complete' (default) or 'cancel'."))
                put("enum", buildJsonArray {
                    add(JsonPrimitive("complete"))
                    add(JsonPrimitive("cancel"))
                })
            })
        },
        required = listOf()
    )

    override fun validateParams(params: JsonElement) {
        val paramsObj = params as? JsonObject
            ?: throw ToolValidationException("Parameters must be a JSON object")

        val rootId = paramsObj["rootId"]
        val itemIds = paramsObj["itemIds"]

        val hasRootId = rootId != null && rootId !is JsonNull
        val hasItemIds = itemIds != null && itemIds !is JsonNull

        if (!hasRootId && !hasItemIds) {
            throw ToolValidationException("Must provide either 'rootId' or 'itemIds'")
        }
        if (hasRootId && hasItemIds) {
            throw ToolValidationException("Provide only one of 'rootId' or 'itemIds', not both")
        }

        if (hasRootId) {
            val prim = rootId as? JsonPrimitive
                ?: throw ToolValidationException("rootId must be a string UUID")
            if (!prim.isString || prim.content.isBlank()) {
                throw ToolValidationException("rootId must be a non-empty string UUID")
            }
            try {
                UUID.fromString(prim.content)
            } catch (_: IllegalArgumentException) {
                throw ToolValidationException("rootId must be a valid UUID, got: ${prim.content}")
            }
        }

        if (hasItemIds) {
            val arr = itemIds as? JsonArray
                ?: throw ToolValidationException("itemIds must be a JSON array")
            arr.forEachIndexed { index, element ->
                val prim = element as? JsonPrimitive
                    ?: throw ToolValidationException("itemIds[$index] must be a string UUID")
                if (!prim.isString || prim.content.isBlank()) {
                    throw ToolValidationException("itemIds[$index] must be a non-empty string UUID")
                }
                try {
                    UUID.fromString(prim.content)
                } catch (_: IllegalArgumentException) {
                    throw ToolValidationException("itemIds[$index] must be a valid UUID, got: ${prim.content}")
                }
            }
        }

        // Validate trigger if provided
        val triggerElem = paramsObj["trigger"]
        if (triggerElem != null && triggerElem !is JsonNull) {
            val triggerPrim = triggerElem as? JsonPrimitive
                ?: throw ToolValidationException("trigger must be a string")
            val trigger = triggerPrim.content.lowercase()
            if (trigger !in setOf("complete", "cancel")) {
                throw ToolValidationException("trigger must be 'complete' or 'cancel', got: $trigger")
            }
        }
    }

    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        val paramsObj = params as JsonObject
        val trigger = (paramsObj["trigger"] as? JsonPrimitive)?.content?.lowercase() ?: "complete"

        // Step 1: Collect target items
        val targetItems: List<WorkItem> = collectTargetItems(paramsObj, context)

        if (targetItems.isEmpty()) {
            return successResponse(buildJsonObject {
                put("results", JsonArray(emptyList()))
                put("summary", buildJsonObject {
                    put("total", JsonPrimitive(0))
                    put("completed", JsonPrimitive(0))
                    put("skipped", JsonPrimitive(0))
                    put("gateFailures", JsonPrimitive(0))
                })
            })
        }

        // Step 2: Build dependency graph within the target set
        val targetIds = targetItems.map { it.id }.toSet()
        val itemById = targetItems.associateBy { it.id }

        // in-degree: count of dependencies from other target items blocking this item
        val inDegree = mutableMapOf<UUID, Int>()
        // adjacency: fromId -> list of toIds that are blocked by fromId (within target set)
        val adjacency = mutableMapOf<UUID, MutableList<UUID>>()

        for (item in targetItems) {
            inDegree.getOrPut(item.id) { 0 }
            adjacency.getOrPut(item.id) { mutableListOf() }
        }

        for (item in targetItems) {
            val incomingDeps = context.dependencyRepository().findByToItemId(item.id)
            for (dep in incomingDeps) {
                val fromId = dep.fromItemId
                if (fromId in targetIds) {
                    // fromId blocks item.id within the target set
                    inDegree[item.id] = (inDegree[item.id] ?: 0) + 1
                    adjacency.getOrPut(fromId) { mutableListOf() }.add(item.id)
                }
            }
        }

        // Step 3: Kahn's algorithm topological sort
        val sortedOrder = mutableListOf<UUID>()
        val queue = ArrayDeque<UUID>()

        for ((id, degree) in inDegree) {
            if (degree == 0) queue.add(id)
        }

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            sortedOrder.add(current)
            val neighbors = adjacency[current] ?: emptyList()
            for (neighbor in neighbors) {
                val newDegree = (inDegree[neighbor] ?: 1) - 1
                inDegree[neighbor] = newDegree
                if (newDegree == 0) queue.add(neighbor)
            }
        }

        // If there are items not in sortedOrder (cycle), append them at the end
        val remaining = targetIds - sortedOrder.toSet()
        sortedOrder.addAll(remaining)

        // Step 4: Process items in topological order
        val handler = RoleTransitionHandler()
        val resultsList = mutableListOf<JsonObject>()
        val skippedSet = mutableSetOf<UUID>()
        var completedCount = 0
        var skippedCount = 0
        var gateFailureCount = 0

        for (itemId in sortedOrder) {
            val item = itemById[itemId] ?: continue

            // Check if this item is in the skipped set
            if (itemId in skippedSet) {
                skippedCount++
                resultsList.add(buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(false))
                    put("skipped", JsonPrimitive(true))
                    put("skippedReason", JsonPrimitive("dependency gate failed"))
                })
                // Propagate skip to dependents
                propagateSkip(itemId, adjacency, skippedSet)
                continue
            }

            // Gate check: required notes must be filled for "complete" trigger
            val itemTags = item.tagList()
            val schema = context.noteSchemaService().getSchemaForTags(itemTags)
            if (schema != null && trigger == "complete") {
                val allRequired = schema.filter { it.required }
                if (allRequired.isNotEmpty()) {
                    val notesResult = context.noteRepository().findByItemId(item.id)
                    val existingNotes = when (notesResult) {
                        is Result.Success -> notesResult.data
                        is Result.Error -> emptyList()
                    }
                    val filledKeys = existingNotes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
                    val missingKeys = allRequired.filter { it.key !in filledKeys }.map { it.key }
                    if (missingKeys.isNotEmpty()) {
                        gateFailureCount++
                        resultsList.add(buildJsonObject {
                            put("itemId", JsonPrimitive(itemId.toString()))
                            put("title", JsonPrimitive(item.title))
                            put("applied", JsonPrimitive(false))
                            put("gateErrors", JsonArray(missingKeys.map { JsonPrimitive("missing: $it") }))
                        })
                        // Skip dependents
                        propagateSkip(itemId, adjacency, skippedSet)
                        continue
                    }
                }
            }

            // Resolve transition
            val hasReviewPhase = context.noteSchemaService().hasReviewPhase(itemTags)
            val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
            if (!resolution.success || resolution.targetRole == null) {
                // Item may already be terminal or otherwise can't transition — skip silently
                skippedCount++
                resultsList.add(buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(false))
                    put("skipped", JsonPrimitive(true))
                    put("skippedReason", JsonPrimitive(resolution.error ?: "Cannot transition"))
                })
                continue
            }

            // Apply transition
            val applyResult = handler.applyTransition(
                item, resolution.targetRole, trigger, null, resolution.statusLabel,
                context.workItemRepository(),
                context.roleTransitionRepository()
            )

            if (!applyResult.success) {
                skippedCount++
                resultsList.add(buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(false))
                    put("skipped", JsonPrimitive(true))
                    put("skippedReason", JsonPrimitive(applyResult.error ?: "Failed to apply transition"))
                })
                // Propagate skip to dependents
                propagateSkip(itemId, adjacency, skippedSet)
                continue
            }

            completedCount++
            resultsList.add(buildJsonObject {
                put("itemId", JsonPrimitive(itemId.toString()))
                put("title", JsonPrimitive(item.title))
                put("applied", JsonPrimitive(true))
                put("trigger", JsonPrimitive(trigger))
            })
        }

        val totalCount = completedCount + skippedCount + gateFailureCount
        val data = buildJsonObject {
            put("results", JsonArray(resultsList))
            put("summary", buildJsonObject {
                put("total", JsonPrimitive(totalCount))
                put("completed", JsonPrimitive(completedCount))
                put("skipped", JsonPrimitive(skippedCount))
                put("gateFailures", JsonPrimitive(gateFailureCount))
            })
        }

        return successResponse(data)
    }

    override fun userSummary(params: JsonElement, result: JsonElement, isError: Boolean): String {
        if (isError) return "complete_tree failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val summary = data?.get("summary") as? JsonObject
        val completed = summary?.get("completed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val total = summary?.get("total")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val gateFailures = summary?.get("gateFailures")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return if (gateFailures == 0) "Completed $completed/$total item(s)"
        else "Completed $completed/$total item(s), $gateFailures gate failure(s)"
    }

    /**
     * Collect the target items based on parameters: either descendants of rootId or explicit itemIds.
     */
    private suspend fun collectTargetItems(
        paramsObj: JsonObject,
        context: ToolExecutionContext
    ): List<WorkItem> {
        val rootIdElem = paramsObj["rootId"]
        if (rootIdElem != null && rootIdElem !is JsonNull) {
            val rootId = UUID.fromString((rootIdElem as JsonPrimitive).content)
            return when (val result = context.workItemRepository().findDescendants(rootId)) {
                is Result.Success -> result.data
                is Result.Error -> emptyList()
            }
        }

        val itemIdsElem = paramsObj["itemIds"] as? JsonArray ?: return emptyList()
        val items = mutableListOf<WorkItem>()
        for (element in itemIdsElem) {
            val id = UUID.fromString((element as JsonPrimitive).content)
            when (val result = context.workItemRepository().getById(id)) {
                is Result.Success -> items.add(result.data)
                is Result.Error -> { /* skip missing items */ }
            }
        }
        return items
    }

    /**
     * Propagate a skip to all direct dependents (within target set) of the given item.
     * Only marks immediate dependents; those will propagate further during topological processing.
     */
    private fun propagateSkip(
        itemId: UUID,
        adjacency: Map<UUID, List<UUID>>,
        skippedSet: MutableSet<UUID>
    ) {
        val dependents = adjacency[itemId] ?: return
        for (dep in dependents) {
            skippedSet.add(dep)
        }
    }
}
