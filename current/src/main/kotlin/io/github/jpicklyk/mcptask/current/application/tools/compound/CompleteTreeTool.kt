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
 * - includeRoot (optional boolean, default true): when rootId is used, also include the root item itself
 *
 * One of rootId or itemIds must be provided.
 */
class CompleteTreeTool : BaseToolDefinition() {
    override val name = "complete_tree"

    override val description =
        """
Complete or cancel all descendants of a root item (or an explicit list of items) in topological dependency order.

**Parameters:**
- `rootId` (optional UUID string): complete all descendants of this item (exclusive with itemIds)
- `itemIds` (optional array of UUID strings): explicit list of items to complete
- `trigger` (optional string, default "complete"): "complete" or "cancel"
- `includeRoot` (optional boolean, default true): when rootId is used, also include the root item itself in the completion scope. Ignored when itemIds is used.

**Validation:** Exactly one of `rootId` or `itemIds` must be provided.

**Behavior:**
- Items are processed in topological order (respecting dependency edges within the target set).
- Gate check: if an item's tags match a note schema, all required notes must be filled before completing.
  Gate failures cause downstream dependents (within the target set) to be skipped.
- Items already in TERMINAL role are recorded as skipped.
- When rootId is used with includeRoot=true (the default), the root item is processed last, after all its descendants.

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

    override val toolAnnotations =
        ToolAnnotations(
            readOnlyHint = false,
            destructiveHint = false,
            idempotentHint = false,
            openWorldHint = false
        )

    override val parameterSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    put(
                        "rootId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive("UUID of root item whose descendants should be completed. Mutually exclusive with itemIds.")
                            )
                        }
                    )
                    put(
                        "itemIds",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put("description", JsonPrimitive("Explicit list of item UUIDs to complete. Mutually exclusive with rootId."))
                            put(
                                "items",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                }
                            )
                        }
                    )
                    put(
                        "trigger",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Transition trigger: 'complete' (default) or 'cancel'."))
                            put(
                                "enum",
                                buildJsonArray {
                                    add(JsonPrimitive("complete"))
                                    add(JsonPrimitive("cancel"))
                                }
                            )
                        }
                    )
                    put(
                        "includeRoot",
                        buildJsonObject {
                            put("type", JsonPrimitive("boolean"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "When rootId is used, also include the root item itself in the completion scope (default true). Ignored when itemIds is used."
                                )
                            )
                        }
                    )
                },
            required = listOf()
        )

    override fun validateParams(params: JsonElement) {
        val paramsObj =
            params as? JsonObject
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
            val prim =
                rootId as? JsonPrimitive
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
            val arr =
                itemIds as? JsonArray
                    ?: throw ToolValidationException("itemIds must be a JSON array")
            arr.forEachIndexed { index, element ->
                val prim =
                    element as? JsonPrimitive
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
            val triggerPrim =
                triggerElem as? JsonPrimitive
                    ?: throw ToolValidationException("trigger must be a string")
            val trigger = triggerPrim.content.lowercase()
            if (trigger !in setOf("complete", "cancel")) {
                throw ToolValidationException("trigger must be 'complete' or 'cancel', got: $trigger")
            }
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val paramsObj = params as JsonObject
        val trigger = (paramsObj["trigger"] as? JsonPrimitive)?.content?.lowercase() ?: "complete"
        val includeRoot = (paramsObj["includeRoot"] as? JsonPrimitive)?.booleanOrNull ?: true

        // Step 1: Collect target items (descendants only) and optionally the root item separately
        val (targetItems, rootItem) = collectTargetItemsWithRoot(paramsObj, context, includeRoot)

        if (targetItems.isEmpty() && rootItem == null) {
            return successResponse(
                buildJsonObject {
                    put("results", JsonArray(emptyList()))
                    put(
                        "summary",
                        buildJsonObject {
                            put("total", JsonPrimitive(0))
                            put("completed", JsonPrimitive(0))
                            put("skipped", JsonPrimitive(0))
                            put("gateFailures", JsonPrimitive(0))
                        }
                    )
                }
            )
        }

        // Step 2: Build dependency graph within the target set (descendants only, not root)
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

        // Step 3: Kahn's algorithm topological sort (descendants only)
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
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("applied", JsonPrimitive(false))
                        put("skipped", JsonPrimitive(true))
                        put("skippedReason", JsonPrimitive("dependency gate failed"))
                    }
                )
                // Propagate skip to dependents
                propagateSkip(itemId, adjacency, skippedSet)
                continue
            }

            // Gate check: required notes must be filled for "complete" trigger
            val missingKeys = checkGate(item, trigger, context)
            if (missingKeys.isNotEmpty()) {
                gateFailureCount++
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("applied", JsonPrimitive(false))
                        put("gateErrors", JsonArray(missingKeys.map { JsonPrimitive("missing: $it") }))
                    }
                )
                // Skip dependents
                propagateSkip(itemId, adjacency, skippedSet)
                continue
            }

            // Resolve transition
            val hasReviewPhase = context.resolveHasReviewPhase(item)
            val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
            if (!resolution.success || resolution.targetRole == null) {
                // Item may already be terminal or otherwise can't transition — skip silently
                skippedCount++
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("applied", JsonPrimitive(false))
                        put("skipped", JsonPrimitive(true))
                        put("skippedReason", JsonPrimitive(resolution.error ?: "Cannot transition"))
                    }
                )
                continue
            }

            // Apply transition with config-driven status label
            val configLabel = context.statusLabelService().resolveLabel(trigger)
            val effectiveLabel = resolution.statusLabel ?: configLabel
            val applyResult =
                handler.applyTransition(
                    item,
                    resolution.targetRole,
                    trigger,
                    null,
                    effectiveLabel,
                    context.workItemRepository(),
                    context.roleTransitionRepository()
                )

            if (!applyResult.success) {
                skippedCount++
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("title", JsonPrimitive(item.title))
                        put("applied", JsonPrimitive(false))
                        put("skipped", JsonPrimitive(true))
                        put("skippedReason", JsonPrimitive(applyResult.error ?: "Failed to apply transition"))
                    }
                )
                // Propagate skip to dependents
                propagateSkip(itemId, adjacency, skippedSet)
                continue
            }

            completedCount++
            resultsList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(true))
                    put("trigger", JsonPrimitive(trigger))
                    applyResult.item?.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                }
            )
        }

        // Step 5: Process root item last (after all descendants), if requested
        if (rootItem != null) {
            val missingKeys = checkGate(rootItem, trigger, context)
            if (missingKeys.isNotEmpty()) {
                gateFailureCount++
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(rootItem.id.toString()))
                        put("title", JsonPrimitive(rootItem.title))
                        put("applied", JsonPrimitive(false))
                        put("gateErrors", JsonArray(missingKeys.map { JsonPrimitive("missing: $it") }))
                    }
                )
            } else {
                processItem(
                    rootItem,
                    trigger,
                    handler,
                    context,
                    resultsList,
                    onComplete = { completedCount++ },
                    onSkip = { skippedCount++ }
                )
            }
        }

        val totalCount = completedCount + skippedCount + gateFailureCount
        val data =
            buildJsonObject {
                put("results", JsonArray(resultsList))
                put(
                    "summary",
                    buildJsonObject {
                        put("total", JsonPrimitive(totalCount))
                        put("completed", JsonPrimitive(completedCount))
                        put("skipped", JsonPrimitive(skippedCount))
                        put("gateFailures", JsonPrimitive(gateFailureCount))
                    }
                )
            }

        return successResponse(data)
    }

    /**
     * Apply a transition to a single item and record the result.
     */
    private suspend fun processItem(
        item: WorkItem,
        trigger: String,
        handler: RoleTransitionHandler,
        context: ToolExecutionContext,
        resultsList: MutableList<JsonObject>,
        onComplete: () -> Unit,
        onSkip: () -> Unit
    ) {
        val hasReviewPhase = context.resolveHasReviewPhase(item)
        val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
        if (!resolution.success || resolution.targetRole == null) {
            onSkip()
            resultsList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(false))
                    put("skipped", JsonPrimitive(true))
                    put("skippedReason", JsonPrimitive(resolution.error ?: "Cannot transition"))
                }
            )
            return
        }

        val configLabel = context.statusLabelService().resolveLabel(trigger)
        val effectiveLabel = resolution.statusLabel ?: configLabel
        val applyResult =
            handler.applyTransition(
                item,
                resolution.targetRole,
                trigger,
                null,
                effectiveLabel,
                context.workItemRepository(),
                context.roleTransitionRepository()
            )

        if (!applyResult.success) {
            onSkip()
            resultsList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(item.id.toString()))
                    put("title", JsonPrimitive(item.title))
                    put("applied", JsonPrimitive(false))
                    put("skipped", JsonPrimitive(true))
                    put("skippedReason", JsonPrimitive(applyResult.error ?: "Failed to apply transition"))
                }
            )
            return
        }

        onComplete()
        resultsList.add(
            buildJsonObject {
                put("itemId", JsonPrimitive(item.id.toString()))
                put("title", JsonPrimitive(item.title))
                put("applied", JsonPrimitive(true))
                put("trigger", JsonPrimitive(trigger))
                applyResult.item?.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
            }
        )
    }

    /**
     * Check gate requirements for an item. For the "complete" trigger, all required notes
     * across all phases must be filled. Returns the list of missing note keys (empty = gate passed).
     */
    private suspend fun checkGate(
        item: WorkItem,
        trigger: String,
        context: ToolExecutionContext
    ): List<String> {
        if (trigger != "complete") return emptyList()
        val resolvedSchema = context.resolveSchema(item) ?: return emptyList()
        val allRequired = resolvedSchema.notes.filter { it.required }
        if (allRequired.isEmpty()) return emptyList()

        val notes =
            when (val result = context.noteRepository().findByItemId(item.id)) {
                is Result.Success -> result.data
                is Result.Error -> emptyList()
            }
        val filledKeys = notes.filter { it.body.isNotBlank() }.map { it.key }.toSet()
        return allRequired.filter { it.key !in filledKeys }.map { it.key }
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "complete_tree failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val summary = data?.get("summary") as? JsonObject
        val completed = summary?.get("completed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val total = summary?.get("total")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val gateFailures = summary?.get("gateFailures")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return if (gateFailures == 0) {
            "Completed $completed/$total item(s)"
        } else {
            "Completed $completed/$total item(s), $gateFailures gate failure(s)"
        }
    }

    /**
     * Collect the target items based on parameters.
     * Returns a Pair of (descendants/explicit items, root item or null).
     * The root item is returned separately so it can be processed last (after all descendants).
     * When itemIds is used, the root item return value is always null.
     */
    private suspend fun collectTargetItemsWithRoot(
        paramsObj: JsonObject,
        context: ToolExecutionContext,
        includeRoot: Boolean = true
    ): Pair<List<WorkItem>, WorkItem?> {
        val rootIdElem = paramsObj["rootId"]
        if (rootIdElem != null && rootIdElem !is JsonNull) {
            val rootId = UUID.fromString((rootIdElem as JsonPrimitive).content)
            val descendants =
                when (val result = context.workItemRepository().findDescendants(rootId)) {
                    is Result.Success -> result.data
                    is Result.Error -> emptyList()
                }
            if (!includeRoot) return Pair(descendants, null)

            // Fetch root item separately — it will be processed after all its descendants
            val rootItem =
                when (val result = context.workItemRepository().getById(rootId)) {
                    is Result.Success -> result.data
                    is Result.Error -> null
                }
            return Pair(descendants, rootItem)
        }

        val itemIdsElem = paramsObj["itemIds"] as? JsonArray ?: return Pair(emptyList(), null)
        val items = mutableListOf<WorkItem>()
        for (element in itemIdsElem) {
            val id = UUID.fromString((element as JsonPrimitive).content)
            when (val result = context.workItemRepository().getById(id)) {
                is Result.Success -> items.add(result.data)
                is Result.Error -> { /* skip missing items */ }
            }
        }
        return Pair(items, null)
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
