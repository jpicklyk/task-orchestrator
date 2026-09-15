package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.AdvanceCascadeEvent
import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceResult
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Completes (or cancels) all descendants of a root item, or an explicit list of items,
 * in topological dependency order.
 *
 * Each item is transitioned through the SAME
 * [io.github.jpicklyk.mcptask.current.application.service.AdvanceService] pipeline that backs
 * `advance_item` (ownership → resolve → dependency validation → note gate → resource-lease gate →
 * apply → cascade → unblock). `complete_tree` owns only the tree-shaped concerns on top of it:
 * target collection, the Kahn topological ordering, and skip propagation. Before this routing
 * (bug 3e455253) the tool called [io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler]
 * directly and therefore silently bypassed claim ownership, dependency validation, cascade/unblock
 * detection, actor attribution on audit rows, and per-root status labels.
 *
 * Gate enforcement: if an item's tags match a note schema, all required notes must be
 * filled before the item can be completed. Gate failures propagate — dependents of a
 * gate-failed item within the target set are skipped. An ownership, dependency, resource or
 * apply rejection propagates the same way; only a resolution failure (the item simply cannot
 * take this trigger from its current role) is recorded without skipping its dependents.
 *
 * Parameters:
 * - rootId (optional UUID): complete all descendants of this item
 * - itemIds (optional array of UUID strings): explicit list of items to complete
 * - trigger (optional string, default "complete"): "complete" or "cancel". Use "cancel" to bypass gate checks.
 * - includeRoot (optional boolean, default true): when rootId is used, also include the root item itself
 *
 * One of rootId or itemIds must be provided.
 */
class CompleteTreeTool :
    BaseToolDefinition(),
    ActorAware {
    override val name = "complete_tree"

    override val description =
        """
Complete or cancel all descendants of a root item (or an explicit list of items) in topological dependency order.

**Validation:** Exactly one of `rootId` or `itemIds` must be provided.

**Behavior:**
- Items are processed in topological order (respecting dependency edges within the target set).
- Each item runs the same pipeline as `advance_item` (ownership, dependency validation, note and
  resource-lease gates, cascade/unblock detection, per-root status labels); `actor` is recorded
  on every audit row.
- Gate check: required notes must be filled before completing (trigger "cancel" bypasses the note
  gate, not ownership). A gate, ownership (`errorCode` "not_claim_holder"), dependency or
  resource failure is reported on that item and skips its in-set dependents.
- Items already TERMINAL (including a parent terminalized by an earlier cascade in the same call)
  are recorded as skipped and never gate-checked.
- When rootId is used with includeRoot=true (the default), the root item is processed last, after all its descendants.

Call when closing out a finished hierarchy — one atomic call instead of per-item advance sequences.
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
                                JsonPrimitive(
                                    "UUID or hex prefix (4+ chars) of root item whose descendants should be completed. Mutually exclusive with itemIds."
                                )
                            )
                        }
                    )
                    put(
                        "itemIds",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Item UUIDs or hex prefixes (4+ chars) to complete. Mutually exclusive with rootId."
                                )
                            )
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
                    put(
                        "requestId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Client-generated UUID for idempotency (10 min cache, keyed by actor+requestId). " +
                                        "Requires actor."
                                )
                            )
                        }
                    )
                    put(
                        "actor",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Top-level actor: { id (required), " +
                                        "kind (required: orchestrator|subagent|user|external), parent?, proof? }"
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
                    ?: throw ToolValidationException("rootId must be a string")
            if (!prim.isString || prim.content.isBlank()) {
                throw ToolValidationException("rootId must be a non-empty string")
            }
            validateIdStringOrPrefix(prim.content, "rootId")
        }

        if (hasItemIds) {
            val arr =
                itemIds as? JsonArray
                    ?: throw ToolValidationException("itemIds must be a JSON array")
            arr.forEachIndexed { index, element ->
                val prim =
                    element as? JsonPrimitive
                        ?: throw ToolValidationException("itemIds[$index] must be a string")
                if (!prim.isString || prim.content.isBlank()) {
                    throw ToolValidationException("itemIds[$index] must be a non-empty string")
                }
                validateIdStringOrPrefix(prim.content, "itemIds[$index]")
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
        val explicitTrigger = (paramsObj["trigger"] as? JsonPrimitive)?.content?.lowercase()
        val trigger =
            when {
                explicitTrigger != null -> explicitTrigger
                else -> "complete"
            }
        val includeRoot = (paramsObj["includeRoot"] as? JsonPrimitive)?.booleanOrNull ?: true

        val requestIdStr = optionalString(params, "requestId")
        val requestId =
            requestIdStr?.let {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        // Resolve trusted actor identity from the top-level actor for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val actorObj = paramsObj["actor"] as? JsonObject
        val trustedActorId: String? =
            if (actorObj != null) {
                val actorResult = parseActorClaim(actorObj, context)
                when (actorResult) {
                    is ActorParseResult.Success -> {
                        when (
                            val r =
                                ActorAware.resolveTrustedActorId(
                                    actorResult.claim,
                                    actorResult.verification,
                                    context.degradedModePolicy
                                )
                        ) {
                            is PolicyResolution.Trusted -> r.trustedId
                            is PolicyResolution.Rejected -> null
                        }
                    }
                    else -> null
                }
            } else {
                null
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // kotlinx.coroutines.runBlocking bridges the suspend execution into the lock-held lambda.
        // This is safe because the tree completion logic only accesses DB repositories and never
        // re-acquires the IdempotencyCache lock.
        if (requestId != null && trustedActorId != null) {
            return context.idempotencyCache.getOrCompute(trustedActorId, requestId) {
                runBlocking { executeCompleteTree(paramsObj, trigger, includeRoot, context) }
            }
        }

        return executeCompleteTree(paramsObj, trigger, includeRoot, context)
    }

    private suspend fun executeCompleteTree(
        paramsObj: JsonObject,
        trigger: String,
        includeRoot: Boolean,
        context: ToolExecutionContext
    ): JsonElement {
        // Step 0: Resolve the single top-level actor once for the whole tree. Every per-item
        // advance below records THIS claim on its audit row — before bug 3e455253 was fixed the
        // tool passed a null actorClaim to applyTransition, so complete_tree audit rows carried no
        // attribution at all. An invalid actor object fails the whole call rather than silently
        // degrading to an unattributed completion of the entire tree.
        val actorResult = parseActorClaim(paramsObj["actor"] as? JsonObject, context)
        val actorClaim: ActorClaim? =
            when (actorResult) {
                is ActorParseResult.Success -> actorResult.claim
                is ActorParseResult.Absent -> null
                is ActorParseResult.Invalid -> return errorResponse(actorResult.error)
            }
        val verification: VerificationResult? =
            (actorResult as? ActorParseResult.Success)?.verification

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
        val resultsList = mutableListOf<JsonObject>()
        val skippedSet = mutableSetOf<UUID>()
        // Ancestors terminalized by a cascade triggered EARLIER in this same call. The item
        // snapshots were read up-front, so an intermediate node (or the root) whose children all
        // completed here would otherwise be re-advanced from a stale non-terminal role, producing a
        // duplicate audit row. Tracked instead of re-reading every item, since complete/cancel can
        // only ever cascade ancestors to TERMINAL.
        val terminalizedByCascade = mutableSetOf<UUID>()
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

            // Items already in a terminal role are recorded as skipped and never gate-checked.
            // A terminal item is already done, so it must NOT gate-fail on unfilled notes and must
            // NOT propagate a skip to its dependents — a terminal dependency is satisfied.
            if (item.role == Role.TERMINAL || itemId in terminalizedByCascade) {
                skippedCount++
                resultsList.add(buildAlreadyTerminalResult(item))
                continue
            }

            // Full advance pipeline (ownership → resolve → validate → gate → leases → apply →
            // cascade → unblock), identical to advance_item.
            when (
                advanceOne(item, trigger, actorClaim, verification, context, resultsList, terminalizedByCascade)
            ) {
                ItemOutcome.COMPLETED -> completedCount++
                ItemOutcome.GATE_FAILED -> {
                    gateFailureCount++
                    propagateSkip(itemId, adjacency, skippedSet)
                }
                ItemOutcome.REJECTED -> {
                    skippedCount++
                    propagateSkip(itemId, adjacency, skippedSet)
                }
                // The item simply cannot take this trigger from its current role (e.g. BLOCKED).
                // Recorded, but its dependents are NOT skipped — preserving pre-existing behavior.
                ItemOutcome.UNRESOLVABLE -> skippedCount++
            }
        }

        // Step 5: Process root item last (after all descendants), if requested
        if (rootItem != null) {
            if (rootItem.role == Role.TERMINAL || rootItem.id in terminalizedByCascade) {
                // Already terminal — record as skipped, never gate-check (mirrors the descendant
                // path). `terminalizedByCascade` covers the common case where completing the last
                // descendant above already cascaded this root to TERMINAL.
                skippedCount++
                resultsList.add(buildAlreadyTerminalResult(rootItem))
            } else {
                when (
                    advanceOne(rootItem, trigger, actorClaim, verification, context, resultsList, terminalizedByCascade)
                ) {
                    ItemOutcome.COMPLETED -> completedCount++
                    ItemOutcome.GATE_FAILED -> gateFailureCount++
                    ItemOutcome.REJECTED, ItemOutcome.UNRESOLVABLE -> skippedCount++
                }
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
     * How a single item's advance attempt ended. Drives both the summary counters and whether the
     * item's in-set dependents are skipped.
     */
    private enum class ItemOutcome {
        /** Transition applied and persisted. */
        COMPLETED,

        /** A required-note gate rejected it; counted in `gateFailures`, dependents skipped. */
        GATE_FAILED,

        /**
         * Ownership, policy, dependency, resource-lease or persistence rejection. Counted in
         * `skipped`, dependents skipped (the item did not actually reach terminal, so anything
         * depending on it must not be completed either).
         */
        REJECTED,

        /**
         * The trigger cannot be resolved from the item's current role (e.g. a BLOCKED item under
         * "complete"). Counted in `skipped`; dependents are NOT skipped, matching the behavior this
         * tool has always had for the "Cannot transition" case.
         */
        UNRESOLVABLE
    }

    /**
     * Runs one item through the shared [AdvanceService] pipeline and records the result.
     *
     * The service is constructed PER ITEM rather than once for the whole tree because
     * [ToolExecutionContext.rootAwareStatusLabelService] must be bound to THIS item's `rootId` — a
     * tree (or an explicit `itemIds` list) can span roots, each with its own per-root
     * `status_labels` override. This mirrors `AdvanceItemTool.executeTransitions` exactly.
     *
     * `enforceOwnership = true` and `enforceResourceLeases = true` are the MCP-side constants: an
     * operator who must bypass either uses the ADMIN-gated REST surface.
     */
    private suspend fun advanceOne(
        item: WorkItem,
        trigger: String,
        actorClaim: ActorClaim?,
        verification: VerificationResult?,
        context: ToolExecutionContext,
        resultsList: MutableList<JsonObject>,
        terminalizedByCascade: MutableSet<UUID>
    ): ItemOutcome {
        val advanceService =
            AdvanceService(
                workItemRepository = context.workItemRepository(),
                roleTransitionRepository = context.roleTransitionRepository(),
                dependencyRepository = context.dependencyRepository(),
                noteRepository = context.noteRepository(),
                statusLabelService = context.rootAwareStatusLabelService(item.rootId, trigger),
                schemaResolver = { context.resolveSchema(it) },
                resourceLeaseRepository = context.repositoryProvider.resourceLeaseRepository(),
                resourceRequirementsResolver = { context.resolveResourceRequirements(it) },
                resourceRegistryResolver = { context.resolveResourceRegistry(it) },
                resourceLeasesEnforced = AdvanceService.resourceLeasesEnforcedFromEnv()
            )

        val outcome =
            advanceService.advance(
                item = item,
                trigger = trigger,
                summary = null,
                actorClaim = actorClaim,
                verification = verification,
                degradedModePolicy = context.degradedModePolicy,
                enforceOwnership = true,
                credentialRefs = emptyList(),
                enforceResourceLeases = true
            )

        return when (outcome) {
            is AdvanceOutcome.Success -> {
                val result = outcome.result
                // Remember ancestors this advance already terminalized so a later item in the same
                // call (typically the root, processed last) is reported as "already terminal"
                // instead of being advanced again from its stale snapshot.
                result.cascadeEvents
                    .filter { it.applied && it.targetRole == Role.TERMINAL }
                    .forEach { terminalizedByCascade.add(it.itemId) }
                resultsList.add(buildAppliedResult(item, trigger, result))
                ItemOutcome.COMPLETED
            }
            is AdvanceOutcome.Failure -> {
                resultsList.add(buildFailureResult(item, outcome.failure))
                when (outcome.failure) {
                    is AdvanceFailure.GateBlocked -> ItemOutcome.GATE_FAILED
                    is AdvanceFailure.ResolutionFailed -> ItemOutcome.UNRESOLVABLE
                    else -> ItemOutcome.REJECTED
                }
            }
        }
    }

    /** Result entry for an item that was already terminal before this call reached it. */
    private fun buildAlreadyTerminalResult(item: WorkItem): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(false))
            put("skipped", JsonPrimitive(true))
            put("skippedReason", JsonPrimitive("already terminal"))
        }

    /**
     * Result entry for an applied transition. Keeps the pre-existing `applied`/`trigger`/
     * `statusLabel` fields and adds the cascade and unblock reporting `advance_item` has always
     * produced but `complete_tree` previously discarded (it never detected them at all).
     */
    private fun buildAppliedResult(
        item: WorkItem,
        trigger: String,
        result: AdvanceResult
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(true))
            put("trigger", JsonPrimitive(trigger))
            put("previousRole", JsonPrimitive(result.previousRole.toJsonString()))
            put("newRole", JsonPrimitive(result.newRole.toJsonString()))
            result.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
            if (result.cascadeEvents.isNotEmpty()) {
                put("cascadeEvents", JsonArray(result.cascadeEvents.map { buildCascadeEventJson(it) }))
            }
            if (result.unblockedItems.isNotEmpty()) {
                put(
                    "unblockedItems",
                    JsonArray(
                        result.unblockedItems.map { unblocked ->
                            buildJsonObject {
                                put("itemId", JsonPrimitive(unblocked.itemId.toString()))
                                put("title", JsonPrimitive(unblocked.title))
                            }
                        }
                    )
                )
            }
        }

    /** Mirrors the `cascadeEvents` element shape emitted by `advance_item`. */
    private fun buildCascadeEventJson(event: AdvanceCascadeEvent): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(event.itemId.toString()))
            put("title", JsonPrimitive(event.title))
            put("previousRole", JsonPrimitive(event.previousRole.toJsonString()))
            put("targetRole", JsonPrimitive(event.targetRole.toJsonString()))
            put("applied", JsonPrimitive(event.applied))
            if (event.gateBlocked) {
                put("gateBlocked", JsonPrimitive(true))
                put("missingNotes", NoteSchemaJsonHelpers.buildMissingNotesArray(event.gateMissingNotes))
            }
            if (event.resourceBlocked) {
                put("resourceBlocked", JsonPrimitive(true))
                put("contendedResources", JsonArray(event.contendedResources.map { JsonPrimitive(it) }))
            }
            event.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
        }

    /**
     * Maps a structured [AdvanceFailure] onto this tool's per-item result shape.
     *
     * - [AdvanceFailure.GateBlocked] keeps the historical `gateErrors` array of `"missing: <key>"`
     *   strings (and adds the structured `missingNotes` array `advance_item` emits).
     * - Every other variant is reported as `skipped` + `skippedReason`, the shape this tool already
     *   used for non-gate rejections, plus the structured `errorKind`/`errorCode` fields
     *   `advance_item` emits so a caller can distinguish a claim-ownership rejection
     *   (`not_claim_holder`) from a transient resource contention (`resource_unavailable`) without
     *   parsing the message.
     */
    private fun buildFailureResult(
        item: WorkItem,
        failure: AdvanceFailure
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(item.id.toString()))
            put("title", JsonPrimitive(item.title))
            put("applied", JsonPrimitive(false))
            when (failure) {
                is AdvanceFailure.GateBlocked -> {
                    put(
                        "gateErrors",
                        JsonArray(failure.missingNotes.map { JsonPrimitive("missing: ${it.key}") })
                    )
                    put("error", JsonPrimitive(failure.message))
                    put("missingNotes", NoteSchemaJsonHelpers.buildMissingNotesArray(failure.missingNotes))
                    put("previousRole", JsonPrimitive(failure.previousRole.toJsonString()))
                    put("targetRole", JsonPrimitive(failure.targetRole.toJsonString()))
                }
                is AdvanceFailure.OwnershipRejected -> {
                    putSkipped(failure.message)
                    putToolError(
                        ToolError
                            .permanent(code = "not_claim_holder", message = failure.message)
                            .copy(contendedItemId = item.id)
                    )
                }
                is AdvanceFailure.PolicyRejected -> {
                    putSkipped(failure.reason)
                    putToolError(ToolError.permanent(code = "rejected_by_policy", message = failure.reason))
                }
                is AdvanceFailure.ResourceLeaseUnavailable -> {
                    putSkipped(failure.message)
                    putToolError(
                        ToolError(
                            kind = ErrorKind.TRANSIENT,
                            code = "resource_unavailable",
                            message = failure.message,
                            retryAfterMs = failure.retryAfterMs
                        )
                    )
                    put(
                        "contendedResources",
                        JsonArray(failure.contendedResources.map { JsonPrimitive(it) })
                    )
                }
                is AdvanceFailure.ValidationFailed -> {
                    putSkipped(failure.message)
                    if (failure.blockers.isNotEmpty()) {
                        put(
                            "blockers",
                            JsonArray(
                                failure.blockers.map { blocker ->
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                                        put("currentRole", JsonPrimitive(blocker.currentRole.toJsonString()))
                                        put("requiredRole", JsonPrimitive(blocker.requiredRole))
                                    }
                                }
                            )
                        )
                    }
                }
                is AdvanceFailure.ResolutionFailed -> putSkipped(failure.message)
                is AdvanceFailure.ApplyFailed -> putSkipped(failure.message)
            }
        }

    /** The legacy non-gate rejection shape: `skipped` + `skippedReason`, plus a plain `error`. */
    private fun JsonObjectBuilder.putSkipped(reason: String) {
        put("skipped", JsonPrimitive(true))
        put("skippedReason", JsonPrimitive(reason))
        put("error", JsonPrimitive(reason))
    }

    /** The structured `errorKind`/`errorCode` fields `advance_item` emits for the same rejection. */
    private fun JsonObjectBuilder.putToolError(toolError: ToolError) {
        put("errorKind", JsonPrimitive(toolError.kind.toJsonString()))
        put("errorCode", JsonPrimitive(toolError.code))
        toolError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
        toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
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
            val rootIdStr = (rootIdElem as JsonPrimitive).content
            val (resolvedRootId, rootIdErr) = resolveIdString(rootIdStr, context)
            if (rootIdErr != null || resolvedRootId == null) {
                throw ToolValidationException("Could not resolve rootId: $rootIdStr")
            }
            val rootId = resolvedRootId
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
            val idStr = (element as JsonPrimitive).content
            val (resolvedId, idErr) = resolveIdString(idStr, context)
            if (idErr != null || resolvedId == null) {
                throw ToolValidationException("Could not resolve itemId: $idStr")
            }
            when (val result = context.workItemRepository().getById(resolvedId)) {
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
