package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.CascadeDetector
import io.github.jpicklyk.mcptask.current.application.service.CascadeEvent
import io.github.jpicklyk.mcptask.current.application.service.OwnershipCheckResult
import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.service.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.UserTrigger
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
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

**Parameters:**
- `transitions` (required array): Each element: `{ itemId (required UUID or short hex prefix, min 4 chars), trigger (required string), summary? (optional string), actor? (optional object) }`
- Valid triggers: start, complete, block, hold, resume, cancel, reopen
- `actor` (optional): `{ id (required string), kind (required: orchestrator|subagent|user|external), parent? (optional string), proof? (optional string) }` — records who performed the transition. Cascade transitions always have null actor. All transitions in a batch must either all omit actor or all use the same actor.id.
- `requestId` (optional UUID): Client-generated UUID for idempotency. Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response without re-executing. Uses the first transition's actor.id as the idempotency key actor.

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

**Response:**
```json
{
  "results": [
    {
      "itemId": "uuid",
      "previousRole": "queue",
      "newRole": "work",
      "trigger": "start",
      "applied": true,
      "actor": { "id": "agent-1", "kind": "subagent", "parent": "orch-1" },
      "verification": { "status": "unverified", "verifier": "noop" },
      "cascadeEvents": [
        { "itemId": "uuid", "title": "Parent Item Title", "previousRole": "work", "targetRole": "terminal", "applied": true }
      ],
      "unblockedItems": [],
      "expectedNotes": [
        { "key": "acceptance-criteria", "role": "work", "required": true, "description": "...", "exists": false }
      ],
      "guidancePointer": "Guidance text for the first unfilled required note in the new role (null if all filled or no schema)",
      "noteProgress": { "filled": 0, "remaining": 2, "total": 2 }
    }
  ],
  "summary": { "total": N, "succeeded": N, "failed": N },
  "allUnblockedItems": [{ "itemId": "uuid", "title": "..." }]
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
                        "transitions",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Array of transition objects: { itemId, trigger, summary?, actor? }. " +
                                        "actor (optional): { id (required string), kind (required: orchestrator|subagent|user|external), " +
                                        "parent (optional string), proof (optional string) }"
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
                                    "Client-generated UUID for idempotency. Repeated calls with the same (actor, requestId) " +
                                        "within ~10 minutes return the cached response without re-executing. " +
                                        "Uses the first transition's actor.id as the idempotency key actor."
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
        val handler = RoleTransitionHandler()
        val cascadeDetector = CascadeDetector()

        val resultsList = mutableListOf<JsonObject>()
        val allUnblockedItems = mutableListOf<JsonObject>()
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

            val previousRole = item.role

            // Resolve schema once per item — reused across gate checks and response building
            val itemSchema = context.resolveSchema(item)

            // Ownership check: enforced for all UserTrigger values before any transition logic runs.
            // Routes through RoleTransitionHandler.checkOwnershipForTransition() which is the same
            // check that userTransition() applies internally — the tool calls it explicitly here so
            // that gate-check errors (which require resolvedTargetRole) can be reported AFTER the
            // ownership error is surfaced with correct priority.
            val ownershipResult =
                handler.checkOwnershipForTransition(
                    item,
                    actorClaim,
                    verification,
                    context.degradedModePolicy
                )
            when (ownershipResult) {
                is OwnershipCheckResult.Allowed -> {} // proceed
                is OwnershipCheckResult.Rejected -> {
                    failCount++
                    resultsList.add(
                        buildStructuredErrorResult(
                            itemId,
                            trigger,
                            ToolError
                                .permanent(
                                    code = "not_claim_holder",
                                    message = ownershipResult.error
                                ).copy(contendedItemId = itemId)
                        )
                    )
                    continue
                }
                is OwnershipCheckResult.PolicyRejected -> {
                    failCount++
                    resultsList.add(
                        buildStructuredErrorResult(
                            itemId,
                            trigger,
                            ToolError.permanent(
                                code = "rejected_by_policy",
                                message = ownershipResult.reason
                            )
                        )
                    )
                    continue
                }
            }

            // Phase 1: Resolve — schema-driven review phase detection
            val hasReviewPhase = itemSchema?.hasReviewPhase() ?: false
            val resolution = handler.resolveTransition(item, trigger, hasReviewPhase)
            val configLabel = context.statusLabelService().resolveLabel(trigger)
            if (!resolution.success || resolution.targetRole == null) {
                failCount++
                resultsList.add(buildErrorResult(itemId, trigger, resolution.error ?: "Failed to resolve transition"))
                continue
            }

            val targetRole = resolution.targetRole

            // Phase 2: Validate dependency constraints
            val validation =
                handler.validateTransition(
                    item,
                    targetRole,
                    context.dependencyRepository(),
                    context.workItemRepository()
                )
            if (!validation.valid) {
                failCount++
                val blockersJson =
                    if (validation.blockers.isNotEmpty()) {
                        JsonArray(
                            validation.blockers.map { blocker ->
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
                resultsList.add(
                    buildErrorResult(
                        itemId,
                        trigger,
                        validation.error ?: "Transition validation failed",
                        blockersJson
                    )
                )
                continue
            }

            // Gate check: required notes for the CURRENT role must exist before advancing (start trigger)
            if (trigger == "start") {
                if (itemSchema != null) {
                    val resolvedSchema = itemSchema
                    val requiredForCurrentPhase = resolvedSchema.notes.filter { it.role == item.role && it.required }
                    if (requiredForCurrentPhase.isNotEmpty()) {
                        val notesResult = context.noteRepository().findByItemId(item.id)
                        val existingNotes =
                            when (notesResult) {
                                is Result.Success -> notesResult.data
                                is Result.Error -> emptyList()
                            }
                        val filledKeys = NoteSchemaJsonHelpers.buildFilledKeys(existingNotes)
                        val missingEntries = requiredForCurrentPhase.filter { it.key !in filledKeys }
                        if (missingEntries.isNotEmpty()) {
                            val missingKeys = missingEntries.map { it.key }
                            failCount++
                            resultsList.add(
                                buildErrorResult(
                                    itemId,
                                    trigger,
                                    "Gate check failed: required notes not filled for ${item.role.toJsonString()} phase: ${missingKeys.joinToString()}",
                                    missingNotes = NoteSchemaJsonHelpers.buildMissingNotesArray(missingEntries)
                                )
                            )
                            continue
                        }
                    }
                }
            }

            // Gate check: all required notes across all phases must be filled (complete trigger)
            if (trigger == "complete") {
                if (itemSchema != null) {
                    val resolvedSchema = itemSchema
                    val allRequired = resolvedSchema.notes.filter { it.required }
                    if (allRequired.isNotEmpty()) {
                        val notesResult = context.noteRepository().findByItemId(item.id)
                        val existingNotes =
                            when (notesResult) {
                                is Result.Success -> notesResult.data
                                is Result.Error -> emptyList()
                            }
                        val filledKeys = NoteSchemaJsonHelpers.buildFilledKeys(existingNotes)
                        val missingEntries = allRequired.filter { it.key !in filledKeys }
                        if (missingEntries.isNotEmpty()) {
                            val missingKeys = missingEntries.map { it.key }
                            failCount++
                            resultsList.add(
                                buildErrorResult(
                                    itemId,
                                    trigger,
                                    "Gate check failed: required notes not filled: ${missingKeys.joinToString()}",
                                    missingNotes = NoteSchemaJsonHelpers.buildMissingNotesArray(missingEntries)
                                )
                            )
                            continue
                        }
                    }
                }
            }

            // Phase 3: Apply — routes through userTransition() to enforce the ownership check
            // at the handler layer as well (canonical enforcement point).
            val effectiveLabel = resolution.statusLabel ?: configLabel
            val applyResult =
                handler.applyTransition(
                    item,
                    targetRole,
                    trigger,
                    summary,
                    effectiveLabel,
                    context.workItemRepository(),
                    context.roleTransitionRepository(),
                    actorClaim = actorClaim,
                    verification = verification
                )
            if (!applyResult.success || applyResult.item == null) {
                failCount++
                resultsList.add(buildErrorResult(itemId, trigger, applyResult.error ?: "Failed to apply transition"))
                continue
            }

            successCount++

            // Phase 4: Cascade detection (only when reaching TERMINAL)
            // Uses iterative detect-apply pattern: after applying each cascade,
            // re-detect from the cascaded parent with fresh DB state.
            // Bounded by CascadeDetector.MAX_DEPTH to prevent runaway recursion.
            val schemaResolver: (WorkItem) -> WorkItemSchema? = { context.resolveSchema(it) }
            val cascadeJsonList = mutableListOf<JsonObject>()
            if (targetRole == Role.TERMINAL) {
                var cascadeSource = applyResult.item!!
                var depth = 0
                while (depth < CascadeDetector.MAX_DEPTH) {
                    val events = cascadeDetector.detectCascades(cascadeSource, context.workItemRepository(), schemaResolver)
                    if (events.isEmpty()) break

                    // Only the immediate parent cascade (first event) is reliable;
                    // deeper events may read stale DB state prior to this cascade's apply.
                    val event = events.first()

                    val parentResult = context.workItemRepository().getById(event.itemId)
                    val parentItem =
                        when (parentResult) {
                            is Result.Success -> parentResult.data
                            is Result.Error -> break
                        }

                    // Gate check: cascade-to-TERMINAL requires all required notes (like "complete" trigger)
                    if (event.targetRole == Role.TERMINAL) {
                        val parentSchema = context.resolveSchema(parentItem)
                        if (parentSchema != null) {
                            val allRequired = parentSchema.notes.filter { it.required }
                            if (allRequired.isNotEmpty()) {
                                val parentNotes =
                                    when (val nr = context.noteRepository().findByItemId(parentItem.id)) {
                                        is Result.Success -> nr.data
                                        is Result.Error -> emptyList()
                                    }
                                val filledKeys = NoteSchemaJsonHelpers.buildFilledKeys(parentNotes)
                                val missingEntries = allRequired.filter { it.key !in filledKeys }
                                if (missingEntries.isNotEmpty()) {
                                    // Block cascade — report gate failure in cascade event
                                    cascadeJsonList.add(
                                        buildJsonObject {
                                            put("itemId", JsonPrimitive(event.itemId.toString()))
                                            put("title", JsonPrimitive(parentItem.title))
                                            put("previousRole", JsonPrimitive(event.currentRole.toJsonString()))
                                            put("targetRole", JsonPrimitive(event.targetRole.toJsonString()))
                                            put("applied", JsonPrimitive(false))
                                            put("gateBlocked", JsonPrimitive(true))
                                            put("missingNotes", NoteSchemaJsonHelpers.buildMissingNotesArray(missingEntries))
                                        }
                                    )
                                    break // Stop cascading up the tree
                                }
                            }
                        }
                    }

                    // Route through the internal cascadeTransition entry point — no public path
                    // can reach this method, enforced by Kotlin `internal` visibility.
                    val cascadeApply =
                        handler.cascadeTransition(
                            parentItem,
                            event.targetRole,
                            "Auto-cascaded from child completion",
                            context.workItemRepository(),
                            context.roleTransitionRepository(),
                            statusLabel = context.statusLabelService().resolveLabel("cascade")
                        )

                    cascadeJsonList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(event.itemId.toString()))
                            put("title", JsonPrimitive(parentItem.title))
                            put("previousRole", JsonPrimitive(event.currentRole.toJsonString()))
                            put("targetRole", JsonPrimitive(event.targetRole.toJsonString()))
                            put("applied", JsonPrimitive(cascadeApply.success))
                            cascadeApply.item?.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                        }
                    )

                    if (!cascadeApply.success || cascadeApply.item == null) break

                    // Continue up the tree: re-detect from the newly-cascaded parent
                    cascadeSource = cascadeApply.item
                    depth++
                }
            }

            // Phase 4b: Start cascade detection (only when reaching WORK)
            // When the first child starts, auto-advance the parent from QUEUE to WORK.
            if (targetRole == Role.WORK) {
                val startCascadeEvents = cascadeDetector.detectStartCascades(applyResult.item!!, context.workItemRepository())
                applyCascadeEvents(
                    startCascadeEvents,
                    "Auto-cascaded from child start",
                    handler,
                    context,
                    cascadeJsonList
                )
            }

            // Phase 4c: Reopen cascade detection (only when reopening to QUEUE)
            // When a child is reopened under a terminal parent, the parent should reopen to WORK.
            if (trigger == "reopen" && targetRole == Role.QUEUE) {
                val reopenCascadeEvents =
                    cascadeDetector.detectReopenCascades(
                        applyResult.item!!,
                        context.workItemRepository(),
                        schemaResolver
                    )
                applyCascadeEvents(
                    reopenCascadeEvents,
                    "Auto-cascaded from child reopen",
                    handler,
                    context,
                    cascadeJsonList
                )
            }

            // Phase 5: Unblock detection
            val unblockedJsonList = mutableListOf<JsonObject>()
            val unblockedItems =
                cascadeDetector.findUnblockedItems(
                    applyResult.item,
                    context.dependencyRepository(),
                    context.workItemRepository()
                )
            for (unblocked in unblockedItems) {
                val unblockedJson =
                    buildJsonObject {
                        put("itemId", JsonPrimitive(unblocked.itemId.toString()))
                        put("title", JsonPrimitive(unblocked.title))
                    }
                unblockedJsonList.add(unblockedJson)
                allUnblockedItems.add(unblockedJson)
            }

            // Schema-driven response fields: expectedNotes, guidancePointer, skillPointer, noteProgress
            val resolvedSchema = itemSchema
            // Only query notes when a schema exists (avoids unnecessary DB call)
            val expectedNotesJson: JsonArray
            val guidancePointer: String?
            val skillPointer: String?
            val noteProgress: JsonObject?

            if (resolvedSchema == null) {
                expectedNotesJson = JsonArray(emptyList())
                guidancePointer = null
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

                // Use shared PhaseNoteContext for guidancePointer, skillPointer, and noteProgress
                val phaseContext = computePhaseNoteContext(targetRole, resolvedSchema, notesByKey)
                guidancePointer = phaseContext?.guidancePointer
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

            // Build success result
            resultsList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(itemId.toString()))
                    put("previousRole", JsonPrimitive(previousRole.toJsonString()))
                    put("newRole", JsonPrimitive(targetRole.toJsonString()))
                    put("trigger", JsonPrimitive(trigger))
                    applyResult.item?.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                    put("applied", JsonPrimitive(true))
                    if (summary != null) put("summary", JsonPrimitive(summary))
                    actorClaim?.let { put("actor", it.toJson()) }
                    verification?.let { put("verification", it.toJson()) }
                    put("cascadeEvents", JsonArray(cascadeJsonList))
                    put("unblockedItems", JsonArray(unblockedJsonList))
                    put("expectedNotes", expectedNotesJson)
                    guidancePointer?.let { put("guidancePointer", JsonPrimitive(it)) }
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
                put("allUnblockedItems", JsonArray(allUnblockedItems))
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
     * Apply a list of cascade events: fetch each parent, apply the transition, and record
     * the cascade result as JSON. Shared by start cascade (Phase 4b) and reopen cascade (Phase 4c).
     *
     * Routes through the internal [RoleTransitionHandler.cascadeTransition] entry point so that
     * cascade transitions never pass through the ownership-check path that [UserTrigger]-based
     * user transitions will use.
     */
    private suspend fun applyCascadeEvents(
        events: List<CascadeEvent>,
        reason: String,
        handler: RoleTransitionHandler,
        context: ToolExecutionContext,
        cascadeJsonList: MutableList<JsonObject>
    ) {
        for (event in events) {
            val parentResult = context.workItemRepository().getById(event.itemId)
            val parentItem =
                when (parentResult) {
                    is Result.Success -> parentResult.data
                    is Result.Error -> continue
                }

            // Route through the internal cascadeTransition entry point — no public path
            // can reach this method, enforced by Kotlin `internal` visibility.
            val cascadeApply =
                handler.cascadeTransition(
                    parentItem,
                    event.targetRole,
                    reason,
                    context.workItemRepository(),
                    context.roleTransitionRepository(),
                    statusLabel = context.statusLabelService().resolveLabel("cascade")
                )

            cascadeJsonList.add(
                buildJsonObject {
                    put("itemId", JsonPrimitive(event.itemId.toString()))
                    put("title", JsonPrimitive(parentItem.title))
                    put("previousRole", JsonPrimitive(event.currentRole.toJsonString()))
                    put("targetRole", JsonPrimitive(event.targetRole.toJsonString()))
                    put("applied", JsonPrimitive(cascadeApply.success))
                    cascadeApply.item?.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                }
            )
        }
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
