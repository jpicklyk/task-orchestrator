package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.UserTrigger
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * Trigger-based role transitions for WorkItems with validation, cascade detection,
 * and unblock reporting.
 *
 * Supports batch transitions via the `transitions` array parameter. Each transition
 * is processed independently: failures on one do not block others.
 *
 * Valid triggers: start, complete, block, hold, resume, cancel, reopen.
 *
 * NoteSchemaService integration:
 * - If the item's tags match a schema, gate enforcement applies:
 *   - "start": required notes for the CURRENT role must be filled before advancing
 *   - "complete": all required notes across all phases must be filled
 * - hasReviewPhase: if the schema has no "review" entries, start from WORK skips REVIEW
 * - expectedNotes: the success result includes schema entries for the new role
 */
class AdvanceItemTool :
    BaseToolDefinition(),
    ActorAware {
    override val name = "advance_item"

    override val description =
        """
Trigger-based role transitions for WorkItems with validation, cascade detection, and unblock reporting.

**Trigger effects:**
- start: QUEUE->WORK, WORK->REVIEW (or TERMINAL if no review phase in schema), REVIEW->TERMINAL
- complete: any non-TERMINAL/BLOCKED -> TERMINAL
- block/hold: any non-TERMINAL/BLOCKED -> BLOCKED (saves previousRole)
- resume: BLOCKED -> previousRole
- cancel: any non-TERMINAL -> TERMINAL (statusLabel = "cancelled")
- reopen: TERMINAL -> QUEUE (clears statusLabel; bypasses gate enforcement)

**Gate enforcement (when tags match a note schema):**
- start: required notes for the current phase must be filled before advancing
- complete: all required notes across all phases must be filled

**Batch actor constraint:** all transitions in a call must either all omit `actor` or all use the same
`actor.id`; cascade-triggered transitions always have a null actor.
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
                        "transitions",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Array of transition objects: { itemId (required, UUID or hex prefix), " +
                                        "trigger (required), summary?, actor? ({ id (required), " +
                                        "kind (required: orchestrator|subagent|user|external), parent?, proof? }) }"
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
                                    "Client-generated UUID for idempotency (10 min cache), keyed on the first " +
                                        "transition's actor.id."
                                )
                            )
                        }
                    )
                },
            required = listOf("transitions")
        )

    override fun validateParams(params: JsonElement) {
        val transitions = requireJsonArray(params, "transitions")
        if (transitions.isEmpty()) {
            throw ToolValidationException("transitions array must not be empty")
        }
        for ((index, element) in transitions.withIndex()) {
            val obj =
                element as? JsonObject
                    ?: throw ToolValidationException("transitions[$index] must be a JSON object")
            val itemIdPrim =
                obj["itemId"] as? JsonPrimitive
                    ?: throw ToolValidationException("transitions[$index] missing required field: itemId")
            if (!itemIdPrim.isString || itemIdPrim.content.isBlank()) {
                throw ToolValidationException("transitions[$index].itemId must be a non-empty string")
            }
            validateIdStringOrPrefix(itemIdPrim.content, "transitions[$index].itemId")
            val triggerPrim =
                obj["trigger"] as? JsonPrimitive
                    ?: throw ToolValidationException("transitions[$index] missing required field: trigger")
            if (!triggerPrim.isString || triggerPrim.content.isBlank()) {
                throw ToolValidationException("transitions[$index].trigger must be a non-empty string")
            }
            // Validate that the trigger is a known UserTrigger. "cascade" is system-internal
            // and is not a valid UserTrigger — reject it here at the API boundary.
            val validTriggers = UserTrigger.entries.joinToString { it.triggerString }
            UserTrigger.fromString(triggerPrim.content)
                ?: throw ToolValidationException(
                    "transitions[$index].trigger '${triggerPrim.content}' is not a valid trigger. " +
                        "Valid triggers: $validTriggers"
                )
        }

        // Validate that all transitions share the same actor presence and actor.id.
        // Two valid cases: all omit actor, or all provide the exact same actor.id.
        if (transitions.size > 1) {
            // Collect (index, actorId-or-null) for each element
            data class ActorEntry(
                val index: Int,
                val actorId: String?
            )

            val actorEntries: List<ActorEntry> =
                transitions.mapIndexed { idx, element ->
                    val obj = element as JsonObject
                    val actorId =
                        (obj["actor"] as? JsonObject)
                            ?.get("id")
                            ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ActorEntry(idx, actorId)
                }

            val withActor = actorEntries.filter { it.actorId != null }
            val withoutActor = actorEntries.filter { it.actorId == null }

            if (withActor.isNotEmpty() && withoutActor.isNotEmpty()) {
                // Mixed presence: some have actor, some don't
                val mixedIndexes =
                    (withActor + withoutActor)
                        .sortedBy { it.index }
                        .map { it.index }
                throw ToolValidationException(
                    "transitions must either all omit actor or all use the same actor.id; " +
                        "found mixed actor presence at indexes $mixedIndexes"
                )
            }

            if (withActor.isNotEmpty()) {
                val distinctIds = withActor.map { it.actorId!! }.toSet()
                if (distinctIds.size > 1) {
                    throw ToolValidationException(
                        "transitions must use a single actor.id across the batch; " +
                            "found ${distinctIds.size} distinct actor.id values: ${distinctIds.sorted()}"
                    )
                }
            }
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val transitions = requireJsonArray(params, "transitions")
        val requestIdStr = optionalString(params, "requestId")
        val requestId =
            requestIdStr?.let {
                try {
                    UUID.fromString(it)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

        // Resolve trusted actor identity from the first transition's actor for the idempotency key.
        // Must be done BEFORE the cache lookup so the cache is keyed on the verified identity,
        // not the self-reported actor.id (bug 3a fix).
        val firstActorObj =
            transitions
                .firstOrNull()
                ?.let { it as? JsonObject }
                ?.get("actor")
                ?.let { it as? JsonObject }

        val trustedActorId: String? =
            if (firstActorObj != null) {
                val actorResult = parseActorClaim(firstActorObj, context)
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
                            is PolicyResolution.Rejected -> null // rejection handled per-transition below
                        }
                    }
                    else -> null
                }
            } else {
                null
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // Cache is only engaged when both requestId and a trusted actor id are available.
        // The compute lambda is non-suspending (IdempotencyCache uses a JVM write lock, not a
        // coroutine mutex). kotlinx.coroutines.runBlocking bridges the suspend execution into
        // the lock-held lambda. This is safe because executeTransitions only accesses DB
        // repositories and never re-acquires the IdempotencyCache lock.
        if (requestId != null && trustedActorId != null) {
            return context.idempotencyCache.getOrCompute(trustedActorId, requestId) {
                runBlocking { executeTransitions(transitions, context) }
            }
        }

        return executeTransitions(transitions, context)
    }

    private suspend fun executeTransitions(
        transitions: JsonArray,
        context: ToolExecutionContext
    ): JsonElement {
        val resultsList = mutableListOf<JsonObject>()
        var successCount = 0
        var failCount = 0

        for (element in transitions) {
            val obj = element as JsonObject
            val itemIdStr = (obj["itemId"] as JsonPrimitive).content
            val (resolvedItemId, idError) = resolveIdString(itemIdStr, context)
            if (idError != null) {
                failCount++
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemIdStr))
                        put("applied", JsonPrimitive(false))
                        put("error", JsonPrimitive("Failed to resolve item ID: $itemIdStr"))
                    }
                )
                continue
            }
            val itemId = resolvedItemId!!

            // Translate trigger string to UserTrigger enum at the JSON boundary.
            // validateParams already rejected unknown values, so fromString should never
            // return null here — but guard defensively.
            val triggerStr = (obj["trigger"] as JsonPrimitive).content
            val userTrigger = UserTrigger.fromString(triggerStr)
            if (userTrigger == null) {
                failCount++
                val validTriggers = UserTrigger.entries.joinToString { it.triggerString }
                resultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId.toString()))
                        put("trigger", JsonPrimitive(triggerStr))
                        put("applied", JsonPrimitive(false))
                        put(
                            "error",
                            JsonPrimitive(
                                "Unknown trigger '$triggerStr'. Valid triggers: $validTriggers"
                            )
                        )
                    }
                )
                continue
            }
            // Use the canonical trigger string from the enum (already lowercased/normalized).
            val trigger = userTrigger.triggerString

            val summary =
                (obj["summary"] as? JsonPrimitive)?.let {
                    if (it.isString && it.content.isNotBlank()) it.content else null
                }

            // Extract optional actor claim
            val actorResult = parseActorClaim(obj["actor"] as? JsonObject, context)
            val actorClaim =
                when (actorResult) {
                    is ActorParseResult.Success -> actorResult.claim
                    is ActorParseResult.Absent -> null
                    is ActorParseResult.Invalid -> {
                        failCount++
                        resultsList.add(buildErrorResult(itemId, trigger, actorResult.error))
                        continue
                    }
                }
            val verification =
                when (actorResult) {
                    is ActorParseResult.Success -> actorResult.verification
                    else -> null
                }

            // Fetch the WorkItem
            val itemResult = context.workItemRepository().getById(itemId)
            val item =
                when (itemResult) {
                    is Result.Success -> itemResult.data
                    is Result.Error -> {
                        failCount++
                        resultsList.add(buildErrorResult(itemId, trigger, "WorkItem not found: $itemId"))
                        continue
                    }
                }

            // Shared advance pipeline (ownership → resolve → validate → gate → apply → cascade →
            // unblock). Built per-item (not once for the whole batch) because statusLabelService
            // must be bound to THIS item's rootId — a batch can mix items from different roots, each
            // with its own per-root status_labels override (see
            // ToolExecutionContext.rootAwareStatusLabelService).
            // MCP enforces claim ownership (enforceOwnership = true); the REST route passes false.
            val advanceService =
                AdvanceService(
                    workItemRepository = context.workItemRepository(),
                    roleTransitionRepository = context.roleTransitionRepository(),
                    dependencyRepository = context.dependencyRepository(),
                    noteRepository = context.noteRepository(),
                    statusLabelService = context.rootAwareStatusLabelService(item.rootId, trigger),
                    schemaResolver = { context.resolveSchema(it) }
                )

            // Delegate the full pipeline to the per-item AdvanceService above.
            val outcome =
                advanceService.advance(
                    item = item,
                    trigger = trigger,
                    summary = summary,
                    actorClaim = actorClaim,
                    verification = verification,
                    degradedModePolicy = context.degradedModePolicy,
                    enforceOwnership = true
                )

            val advanceResult =
                when (outcome) {
                    is AdvanceOutcome.Success -> outcome.result
                    is AdvanceOutcome.Failure -> {
                        failCount++
                        resultsList.add(buildFailureResult(itemId, trigger, outcome.failure))
                        continue
                    }
                }

            successCount++

            val targetRole = advanceResult.newRole

            // Map structured cascade events to the existing MCP JSON shape.
            val cascadeJsonList =
                advanceResult.cascadeEvents.map { event ->
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
                        event.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                    }
                }

            // Map unblocked items (per-transition only; the top-level aggregate was dropped as derivable).
            val unblockedJsonList =
                advanceResult.unblockedItems.map { unblocked ->
                    buildJsonObject {
                        put("itemId", JsonPrimitive(unblocked.itemId.toString()))
                        put("title", JsonPrimitive(unblocked.title))
                    }
                }

            // Schema-driven response fields: expectedNotes, guidanceKey, skillPointer, noteProgress
            val resolvedSchema = advanceResult.resolvedSchema
            val expectedNotesJson: JsonArray
            val guidanceKey: String?
            val skillPointer: String?
            val noteProgress: JsonObject?

            if (resolvedSchema == null) {
                expectedNotesJson = JsonArray(emptyList())
                guidanceKey = null
                skillPointer = null
                noteProgress = null
            } else {
                val existingNotes =
                    when (val notesResult = context.noteRepository().findByItemId(item.id)) {
                        is Result.Success -> notesResult.data
                        is Result.Error -> emptyList()
                    }
                val notesByKey = existingNotes.associateBy { it.key }
                val existingKeys = notesByKey.keys

                // Build expectedNotes: schema entries matching the new role (tool-specific, includes "exists")
                expectedNotesJson =
                    buildExpectedNotesJson(
                        schema = resolvedSchema,
                        existingNoteKeys = existingKeys,
                        filterRole = targetRole
                    )

                // Use shared PhaseNoteContext for guidanceKey, skillPointer, and noteProgress
                val phaseContext = computePhaseNoteContext(targetRole, resolvedSchema, notesByKey)
                guidanceKey = phaseContext?.guidanceKey
                skillPointer = phaseContext?.skillPointer
                noteProgress =
                    phaseContext?.let {
                        buildJsonObject {
                            put("filled", JsonPrimitive(it.filled))
                            put("remaining", JsonPrimitive(it.remaining))
                            put("total", JsonPrimitive(it.total))
                        }
                    }
            }

            // Build success result. previousRole + trigger echoes dropped (caller supplied the trigger;
            // newRole is the outcome). Empty cascadeEvents/unblockedItems are omitted.
            resultsList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("newRole", JsonPrimitive(targetRole.toJsonString()))
                    advanceResult.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                    put("applied", JsonPrimitive(true))
                    if (summary != null) put("summary", JsonPrimitive(summary))
                    actorClaim?.let { put("actor", it.toJson()) }
                    verification?.toJsonOrOmit()?.let { put("verification", it) }
                    if (cascadeJsonList.isNotEmpty()) put("cascadeEvents", JsonArray(cascadeJsonList))
                    if (unblockedJsonList.isNotEmpty()) put("unblockedItems", JsonArray(unblockedJsonList))
                    put("expectedNotes", expectedNotesJson)
                    guidanceKey?.let { put("guidanceKey", JsonPrimitive(it)) }
                    skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
                    noteProgress?.let { put("noteProgress", it) }
                }
            )
        }

        val totalCount = successCount + failCount
        val data =
            buildJsonObject {
                put("results", JsonArray(resultsList))
                put(
                    "summary",
                    buildJsonObject {
                        put("total", JsonPrimitive(totalCount))
                        put("succeeded", JsonPrimitive(successCount))
                        put("failed", JsonPrimitive(failCount))
                    }
                )
            }

        return successResponse(data)
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "advance_item failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val summary = data?.get("summary") as? JsonObject
        val total = summary?.get("total")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val succeeded = summary?.get("succeeded")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val failed = summary?.get("failed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return if (failed == 0) "Transitioned $succeeded item(s)" else "Transitioned $succeeded/$total ($failed failed)"
    }

    /**
     * Maps a structured [AdvanceFailure] from [AdvanceService] back to the legacy per-transition
     * error JSON shapes (preserved byte-for-byte from the pre-unification inline logic):
     * - Ownership rejection → structured `not_claim_holder` error (+ `contendedItemId`)
     * - Policy rejection → structured `rejected_by_policy` error
     * - Validation failure → `error` + `blockers` array
     * - Gate block → `error` + `missingNotes` array
     * - Resolution / apply failure → plain `error` string
     */
    private fun buildFailureResult(
        itemId: UUID,
        trigger: String,
        failure: AdvanceFailure
    ): JsonObject =
        when (failure) {
            is AdvanceFailure.OwnershipRejected ->
                buildStructuredErrorResult(
                    itemId,
                    trigger,
                    ToolError
                        .permanent(code = "not_claim_holder", message = failure.message)
                        .copy(contendedItemId = itemId)
                )
            is AdvanceFailure.PolicyRejected ->
                buildStructuredErrorResult(
                    itemId,
                    trigger,
                    ToolError.permanent(code = "rejected_by_policy", message = failure.reason)
                )
            is AdvanceFailure.ResolutionFailed ->
                buildErrorResult(itemId, trigger, failure.message)
            is AdvanceFailure.ApplyFailed ->
                buildErrorResult(itemId, trigger, failure.message)
            is AdvanceFailure.ValidationFailed -> {
                val blockersJson =
                    if (failure.blockers.isNotEmpty()) {
                        JsonArray(
                            failure.blockers.map { blocker ->
                                buildJsonObject {
                                    put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                                    put("currentRole", JsonPrimitive(blocker.currentRole.toJsonString()))
                                    put("requiredRole", JsonPrimitive(blocker.requiredRole))
                                }
                            }
                        )
                    } else {
                        null
                    }
                buildErrorResult(itemId, trigger, failure.message, blockers = blockersJson)
            }
            is AdvanceFailure.GateBlocked ->
                buildErrorResult(
                    itemId,
                    trigger,
                    failure.message,
                    missingNotes = NoteSchemaJsonHelpers.buildMissingNotesArray(failure.missingNotes)
                )
        }

    private fun buildErrorResult(
        itemId: UUID,
        trigger: String,
        error: String,
        blockers: JsonArray? = null,
        missingNotes: JsonArray? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(error))
            if (blockers != null) {
                put("blockers", blockers)
            }
            if (missingNotes != null) {
                put("missingNotes", missingNotes)
            }
        }

    /**
     * Builds a per-transition error result with structured [ToolError] fields.
     *
     * Adds `kind`, `errorCode`, and `contendedItemId` alongside the legacy `error` string so
     * agents can make programmatic retry decisions on ownership rejections and policy rejections.
     */
    private fun buildStructuredErrorResult(
        itemId: UUID,
        trigger: String,
        toolError: ToolError
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(toolError.message))
            put("errorKind", JsonPrimitive(toolError.kind.toJsonString()))
            put("errorCode", JsonPrimitive(toolError.code))
            toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
        }
}
