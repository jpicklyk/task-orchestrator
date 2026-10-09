package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceResult
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.CredentialRefValidation
import io.github.jpicklyk.mcptask.current.application.service.buildDispatchProfileJson
import io.github.jpicklyk.mcptask.current.application.service.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.application.service.buildMissingBySeatJson
import io.github.jpicklyk.mcptask.current.application.service.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.legacyReadOrNull
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
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

**Call shapes:** `transitions=[{itemId, trigger, summary?, actor?}, ...]` (batch), OR top-level
singular sugar `itemId`+`trigger` (+ optional `summary`, `actor`) for one item — the server wraps it
into a one-element array. If `transitions` is present the singular fields are ignored.

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
- `start`'s gate is scoped to the item's CURRENT phase, so successive `start` calls gate different
  note sets as the role advances (QUEUE notes, then WORK notes, then REVIEW notes) — this is
  deterministic per-phase behavior, not a timing-dependent artifact. A transition can also cascade
  a parent straight to TERMINAL when the parent's downstream gates are already satisfied by
  previously prefilled notes.

**Resource-lease gate:** transitions entering the work phase acquire an exclusive lease per resource
declared by the item's traits; contention rejects with transient `resource_unavailable` +
`retryAfterMs` + `contendedResources` (holder never disclosed). Leases release on every work exit.

**Batch actor constraint:** all transitions in a call must either all omit `actor` or all use the same
`actor.id`; cascade-triggered transitions always have a null actor.

Call to move an item between phases once its work is done — never edit status via manage_items.
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
                                        "kind (required: orchestrator|subagent|user|external), parent?, proof? }), " +
                                        "credentialRefs? (audit labels of credentials this transition consumed — " +
                                        "opaque labels, never secret values; string or string array, max 8, " +
                                        "each 1-128 chars matching ^[a-z0-9][a-z0-9\\-_./]*$; must name a " +
                                        "declared/registered resource key when any exist; declared keys are " +
                                        "auto-recorded on work entry) }"
                                )
                            )
                        }
                    )
                    put(
                        "itemId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Singular-form sugar (UUID or hex prefix): advance one item with `trigger` instead of " +
                                        "a `transitions` array; optional `summary`/`actor` are accepted too. See the description."
                                )
                            )
                        }
                    )
                    put(
                        "trigger",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("Singular-form sugar: the trigger for the single `itemId`."))
                        }
                    )
                    put(
                        "requestId",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Client-generated UUID; each transition runs once per 24h, keyed on the first " +
                                        "transition's actor.id; malformed values rejected."
                                )
                            )
                        }
                    )
                },
            required = emptyList()
        )

    /**
     * Normalizes the singular call shape into the canonical `transitions` array (eb4b3fd5).
     *
     * If `transitions` is already present, params pass through unchanged. Otherwise, a top-level
     * `itemId` is wrapped into a one-element `transitions=[{itemId, trigger?, summary?, actor?}]`,
     * carrying the singular sugar fields; any other top-level params (e.g. `requestId`) are
     * preserved. With neither `transitions` nor `itemId`, params pass through so downstream
     * validation raises the (now shape-naming) missing-parameter error.
     */
    private fun normalizeParams(params: JsonElement): JsonElement {
        val obj = params as? JsonObject ?: return params
        if (obj.containsKey("transitions")) return params
        val itemId = obj["itemId"] ?: return params
        val sugarKeys = setOf("itemId", "trigger", "summary", "actor")
        val singular =
            buildJsonObject {
                put("itemId", itemId)
                obj["trigger"]?.let { put("trigger", it) }
                obj["summary"]?.let { put("summary", it) }
                obj["actor"]?.let { put("actor", it) }
            }
        return buildJsonObject {
            obj.forEach { (k, v) -> if (k !in sugarKeys) put(k, v) }
            put("transitions", JsonArray(listOf(singular)))
        }
    }

    /**
     * Parses a `credentialRefs` JSON value into a `List<String>`: a bare string is coerced to a
     * one-element list; a JSON array is accepted only if every element is a string. Returns null
     * for any other shape (wrong type, non-string array element) so the caller can raise a
     * shape-specific validation error.
     */
    private fun parseCredentialRefsElement(element: JsonElement): List<String>? =
        when (element) {
            is JsonArray ->
                element.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return null }
            is JsonPrimitive -> if (element.isString) listOf(element.content) else null
            else -> null
        }

    override fun validateParams(params: JsonElement) {
        // requestId is a top-level param untouched by the singular->transitions[] normalization
        // below, so validate it against the raw params first.
        validateRequestIdParam(params)
        val normalized = normalizeParams(params)
        val normalizedObj = normalized as? JsonObject
        if (normalizedObj == null || !normalizedObj.containsKey("transitions")) {
            throw ToolValidationException(
                "advance_item requires either a `transitions` array or the singular `itemId` + `trigger`. " +
                    "Example: transitions=[{\"itemId\": \"...\", \"trigger\": \"complete\"}]"
            )
        }
        val transitions = requireJsonArray(normalized, "transitions")
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
            // Validate that the trigger is a known user trigger. "cascade" is system-internal
            // and is not a valid user trigger — reject it here at the API boundary.
            val validTriggers = Trigger.User.entries.joinToString { it.wire }
            Trigger.User.parse(triggerPrim.content)
                ?: throw ToolValidationException(
                    "transitions[$index].trigger '${triggerPrim.content}' is not a valid trigger. " +
                        "Valid triggers: $validTriggers"
                )

            // Optional credentialRefs: a bare string or an array of strings, validated against the
            // shared rules in CredentialRefValidation. Malformed shape or a rule violation fails
            // validateParams up front — nothing is persisted for ANY transition in the batch.
            val credentialRefsElement = obj["credentialRefs"]
            if (credentialRefsElement != null && credentialRefsElement !is JsonNull) {
                val parsed =
                    parseCredentialRefsElement(credentialRefsElement)
                        ?: throw ToolValidationException(
                            "transitions[$index].credentialRefs must be a string or an array of strings"
                        )
                when (val result = CredentialRefValidation.validate(parsed)) {
                    is CredentialRefValidation.Result.Invalid ->
                        throw ToolValidationException(
                            "transitions[$index].credentialRefs" +
                                (if (result.index >= 0) "[${result.index}]" else "") +
                                " ${result.reason}"
                        )
                    is CredentialRefValidation.Result.Valid -> {} // ok
                }
            }
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
    ): JsonElement =
        withConfigSession {
            executeWithSession(params, context)
        }

    private suspend fun executeWithSession(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val normalized = normalizeParams(params)
        val transitions = requireJsonArray(normalized, "transitions")
        // Defence-in-depth: unreachable via MCP (validateParams already ran validateRequestIdParam),
        // but guards a direct in-process call to execute() that skipped validateParams.
        validateRequestIdParam(normalized)
        val requestIdStr = optionalString(normalized, "requestId")
        val requestId = requestIdStr?.let { UUID.fromString(it.trim()) }

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

        // A keyed call (requestId plus a trusted principal) is keyed PER TRANSITION: each runs, and is
        // recorded, in its own unit. Unkeyed calls never touch the idempotency service.
        val keyed =
            if (requestId != null && trustedActorId != null) {
                KeyedCall(
                    context.idempotency,
                    trustedActorId,
                    requestId,
                    KeyedCall.op(name),
                    KeyedCall.sharedOf(normalized, "transitions")
                )
            } else {
                null
            }

        return executeTransitions(transitions, context, keyed)
    }

    private suspend fun executeTransitions(
        transitions: JsonArray,
        context: ToolExecutionContext,
        keyed: KeyedCall?
    ): JsonElement {
        val resultsList = mutableListOf<JsonObject>()
        var successCount = 0
        var failCount = 0

        for ((index, element) in transitions.withIndex()) {
            val obj = element as JsonObject

            // The transition as prepared by this call; null when a stored result served it (replay).
            var prepared: Pair<PreCheckResult.Ready, AdvanceResult>? = null
            val itemIdForFailure = (obj["itemId"] as? JsonPrimitive)?.content ?: "unknown"
            val triggerForFailure = (obj["trigger"] as? JsonPrimitive)?.content ?: "unknown"
            val transitionOutcome =
                runElement(
                    keyed,
                    index,
                    element,
                    onError = { mismatchResult(itemIdForFailure, triggerForFailure, it) }
                ) body@{
                    prepared = null
                    val preCheck = performPreChecks(obj, context)
                    val ready =
                        when (preCheck) {
                            is PreCheckResult.Failed -> return@body ElementResult.Failed(preCheck.resultJson)
                            is PreCheckResult.Ready -> preCheck
                        }

                    // Shared advance pipeline (snapshot -> policy -> apply -> cascades -> unblock, one unit).
                    // Status labels resolve per item rootId (EffectiveConfigResolver.labelFor), so a batch
                    // can mix items from different roots.
                    // MCP enforces claim ownership (enforceOwnership = true); the REST route passes false.
                    // A per-root config read failure anywhere in the pre-commit pipeline below (status
                    // label resolution, gate check, review-phase detection) must fail ONLY this transition
                    // with a transient config_unavailable outcome — the batch continues with the rest (D5).
                    // Nothing has been persisted for this transition at this point, so there is no
                    // committed-write-reported-as-failed risk here (contrast the post-commit dispatch
                    // decoration below, which is handled separately per D7).
                    val outcome =
                        try {
                            val advanceService = context.advanceServiceFactory().forItem(ready.item)

                            // Delegate the full pipeline to the per-item AdvanceService above.
                            // MCP ALWAYS enforces resource leases — there is no tool-level override. An
                            // operator who must bypass a lease uses the ADMIN-gated REST surface (an
                            // `overrideResourceLeases` advance, or DELETE /api/v1/resources/leases/{key}),
                            // both of which are logged at WARN.
                            // Each transition carries its own actor into the SSE events its write (and
                            // any cascade it triggers) publishes.
                            withEventActor(ready.actorClaim) {
                                advanceService.advance(
                                    item = ready.item,
                                    trigger = ready.trigger,
                                    summary = ready.summary,
                                    actorClaim = ready.actorClaim,
                                    verification = ready.verification,
                                    degradedModePolicy = context.degradedModePolicy,
                                    enforceOwnership = true,
                                    credentialRefs = ready.credentialRefs,
                                    enforceResourceLeases = true
                                )
                            }
                        } catch (e: PerRootConfigUnavailableException) {
                            return@body ElementResult.Failed(
                                buildStructuredErrorResult(
                                    ready.item.id,
                                    ready.trigger,
                                    ToolError(
                                        kind = ErrorKind.TRANSIENT,
                                        code = PerRootConfigUnavailableException.CODE,
                                        message = e.message
                                    )
                                )
                            )
                        }

                    val advanceResult =
                        when (outcome) {
                            is AdvanceOutcome.Success -> outcome.result
                            is AdvanceOutcome.Failure ->
                                return@body ElementResult.Failed(buildFailureResult(ready.item.id, ready.trigger, outcome.failure))
                        }

                    prepared = ready to advanceResult
                    ElementResult.Done(buildSuccessResult(ready, advanceResult, context))
                }

            when (transitionOutcome) {
                is ElementOutcome.Failed -> {
                    failCount++
                    resultsList.add(transitionOutcome.failure)
                }
                is ElementOutcome.Succeeded -> {
                    successCount++
                    resultsList.add(withDispatch(transitionOutcome.fragment, prepared, context))
                }
            }
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

    /**
     * Result of the per-transition pre-checks (id resolution, trigger parsing, actor parsing,
     * and item lookup) run at the top of [executeTransitions] for each batch element.
     *
     * [Ready] carries everything [executeTransitions] needs to build and invoke the
     * per-item [AdvanceService]; [Failed] carries an already-built `applied:false` result JSON
     * so the caller can append it and move to the next transition without re-deriving the error
     * shape.
     */
    private sealed class PreCheckResult {
        data class Ready(
            val trigger: String,
            val summary: String?,
            val credentialRefs: List<String>,
            val actorClaim: ActorClaim?,
            val verification: VerificationResult?,
            val item: WorkItem
        ) : PreCheckResult()

        data class Failed(
            val resultJson: JsonObject
        ) : PreCheckResult()
    }

    /**
     * Resolves and validates one `transitions[]` element: item id (full UUID or hex prefix),
     * trigger string, optional summary/credentialRefs/actor, and the target [WorkItem] itself.
     * Mirrors the pre-refactor inline logic byte-for-byte, including error codes/kinds.
     */
    private suspend fun performPreChecks(
        obj: JsonObject,
        context: ToolExecutionContext
    ): PreCheckResult {
        val itemIdStr = (obj["itemId"] as JsonPrimitive).content
        val (resolvedItemId, idError) = resolveIdString(itemIdStr, context)
        if (idError != null) {
            return PreCheckResult.Failed(
                buildJsonObject {
                    put("itemId", JsonPrimitive(itemIdStr))
                    put("applied", JsonPrimitive(false))
                    put("error", JsonPrimitive("Failed to resolve item ID: $itemIdStr"))
                    put("errorCode", JsonPrimitive(ITEM_NOT_FOUND))
                    put("errorKind", JsonPrimitive(ErrorKind.PERMANENT.toJsonString()))
                }
            )
        }
        val itemId = resolvedItemId!!

        // Translate trigger string to a Trigger.User at the JSON boundary.
        // validateParams already rejected unknown values, so fromString should never
        // return null here — but guard defensively.
        val triggerStr = (obj["trigger"] as JsonPrimitive).content
        val userTrigger = Trigger.User.parse(triggerStr)
        if (userTrigger == null) {
            val validTriggers = Trigger.User.entries.joinToString { it.wire }
            return PreCheckResult.Failed(
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
                    put("errorCode", JsonPrimitive(INVALID_TRIGGER))
                    put("errorKind", JsonPrimitive(ErrorKind.PERMANENT.toJsonString()))
                }
            )
        }
        // Use the canonical trigger string from the enum (already lowercased/normalized).
        val trigger = userTrigger.wire

        val summary =
            (obj["summary"] as? JsonPrimitive)?.let {
                if (it.isString && it.content.isNotBlank()) it.content else null
            }

        // credentialRefs already validated (shape + rules) in validateParams; re-parse here to
        // thread the resolved list into AdvanceService. Absent/null field -> empty list (no
        // behavior change).
        val credentialRefsElement = obj["credentialRefs"]
        val credentialRefs =
            if (credentialRefsElement != null && credentialRefsElement !is JsonNull) {
                parseCredentialRefsElement(credentialRefsElement) ?: emptyList()
            } else {
                emptyList()
            }

        // Extract optional actor claim
        val actorResult = parseActorClaim(obj["actor"] as? JsonObject, context)
        val actorClaim =
            when (actorResult) {
                is ActorParseResult.Success -> actorResult.claim
                is ActorParseResult.Absent -> null
                is ActorParseResult.Invalid -> {
                    return PreCheckResult.Failed(
                        buildErrorResult(
                            itemId,
                            trigger,
                            actorResult.error,
                            errorCode = INVALID_ACTOR,
                            errorKind = ErrorKind.PERMANENT
                        )
                    )
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
            itemResult ?: run {
                return PreCheckResult.Failed(
                    buildErrorResult(
                        itemId,
                        trigger,
                        "WorkItem not found: $itemId",
                        errorCode = ITEM_NOT_FOUND,
                        errorKind = ErrorKind.PERMANENT
                    )
                )
            }

        return PreCheckResult.Ready(trigger, summary, credentialRefs, actorClaim, verification, item)
    }

    /**
     * Builds the `applied:true` result JSON for a successful transition: cascade events,
     * unblocked items, and the schema-driven expectedNotes/guidanceKey/skillPointer/noteProgress/
     * dispatch fields. Mirrors the pre-refactor inline logic byte-for-byte, including JSON key
     * order.
     */
    private suspend fun buildSuccessResult(
        ready: PreCheckResult.Ready,
        advanceResult: AdvanceResult,
        context: ToolExecutionContext
    ): JsonObject {
        val item = ready.item
        val itemId = item.id
        val summary = ready.summary
        val actorClaim = ready.actorClaim
        val verification = ready.verification
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
                    if (event.resourceBlocked) {
                        put("resourceBlocked", JsonPrimitive(true))
                        put(
                            "contendedResources",
                            JsonArray(event.contendedResources.map { JsonPrimitive(it) })
                        )
                    }
                    if (event.roleBlocked) put("roleBlocked", JsonPrimitive(true))
                    if (event.dependencyBlocked) {
                        put("dependencyBlocked", JsonPrimitive(true))
                        put("blockers", NoteSchemaJsonHelpers.buildBlockersArray(event.blockers))
                    }
                    event.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                    event.error?.let { put("error", JsonPrimitive(it)) }
                    NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(event.violations)?.let { put("violations", it) }
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
                (legacyReadOrNull { context.noteRepository().findByItemId(item.id) } ?: emptyList())
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
        return buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("newRole", JsonPrimitive(targetRole.toJsonString()))
            advanceResult.statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
            put("applied", JsonPrimitive(true))
            if (summary != null) put("summary", JsonPrimitive(summary))
            actorClaim?.let { put("actor", it.toJson()) }
            verification?.toJsonOrOmit()?.let { put("verification", it) }
            if (cascadeJsonList.isNotEmpty()) put("cascadeEvents", JsonArray(cascadeJsonList))
            if (unblockedJsonList.isNotEmpty()) put("unblockedItems", JsonArray(unblockedJsonList))
            NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(advanceResult.violations)?.let { put("violations", it) }
            put("expectedNotes", expectedNotesJson)
            guidanceKey?.let { put("guidanceKey", JsonPrimitive(it)) }
            skillPointer?.let { put("skillPointer", JsonPrimitive(it)) }
            noteProgress?.let { put("noteProgress", it) }
        }
    }

    /**
     * Adds the dispatch routing profile for the phase just entered to a transition result. It is a
     * config-derived decoration of a transition that ALREADY COMMITTED: per D7 a per-root config read
     * failure must never be reported as a failure of it, so the hint is simply omitted (and a WARN
     * logged). It is never stored; a replay recomputes it from the stored `newRole` and the current
     * item. Resolved via the already-resolved schema overload when this call ran the transition (it
     * never re-resolves the schema; see ToolExecutionContext.resolveDispatchProfile).
     */
    private suspend fun withDispatch(
        fragment: JsonObject,
        prepared: Pair<PreCheckResult.Ready, AdvanceResult>?,
        context: ToolExecutionContext
    ): JsonObject {
        val dispatch =
            if (prepared != null) {
                val (ready, advanceResult) = prepared
                omitOnConfigUnavailable(logger, "dispatch profile", ready.item.id) {
                    context.resolveDispatchProfile(ready.item, advanceResult.newRole, advanceResult.resolvedSchema)
                }
            } else {
                val itemId =
                    (fragment["itemId"] as? JsonPrimitive)?.content?.let {
                        runCatchingNonCancellation {
                            UUID.fromString(
                                it
                            )
                        }.getOrNull()
                    }
                val role = (fragment["newRole"] as? JsonPrimitive)?.content?.let { Role.fromString(it) }
                val item = if (itemId != null) legacyReadOrNull { context.workItemRepository().getById(itemId) } else null
                if (item == null || role == null) {
                    null
                } else {
                    omitOnConfigUnavailable(logger, "dispatch profile", item.id) {
                        context.resolveDispatchProfile(item, role)
                    }
                }
            }
        if (dispatch == null) return fragment
        return buildJsonObject {
            var placed = false
            fragment.forEach { (k, v) ->
                if (k == "noteProgress") {
                    put("dispatch", buildDispatchProfileJson(dispatch))
                    placed = true
                }
                put(k, v)
            }
            if (!placed) put("dispatch", buildDispatchProfileJson(dispatch))
        }
    }

    /** The failure for a transition whose key was already used with another payload. */
    private fun mismatchResult(
        itemId: String,
        trigger: String,
        error: DomainError
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(error.message))
            put("errorCode", JsonPrimitive(KeyedCall.IDEMPOTENCY_MISMATCH_CODE))
            put("errorKind", JsonPrimitive(ErrorKind.PERMANENT.toJsonString()))
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
     * - Resource-lease contention → structured `resource_unavailable` error with
     *   `errorKind=transient`, `retryAfterMs`, and a `contendedResources` array of key strings.
     *   **No holder identity** (`contendedItemId` is deliberately left null, and no actor id
     *   appears anywhere in the payload) — an agent that can trigger an advance must not be able to
     *   enumerate who holds a resource; that is ADMIN-only via `GET /api/v1/resources/leases`.
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
            is AdvanceFailure.ResourceLeaseUnavailable ->
                buildStructuredErrorResult(
                    itemId,
                    trigger,
                    ToolError(
                        kind = ErrorKind.TRANSIENT,
                        code = "resource_unavailable",
                        message = failure.message,
                        retryAfterMs = failure.retryAfterMs
                    ),
                    contendedResources = failure.contendedResources
                )
            is AdvanceFailure.ResolutionFailed ->
                buildErrorResult(itemId, trigger, failure.message, errorCode = INVALID_TRANSITION, errorKind = ErrorKind.PERMANENT)
            is AdvanceFailure.ApplyFailed ->
                buildErrorResult(itemId, trigger, failure.message, errorCode = APPLY_FAILED, errorKind = ErrorKind.TRANSIENT)
            is AdvanceFailure.ValidationFailed -> {
                val blockersJson =
                    if (failure.blockers.isNotEmpty()) {
                        NoteSchemaJsonHelpers.buildBlockersArray(failure.blockers)
                    } else {
                        null
                    }
                val (code, kind) =
                    if (failure.blockers.isNotEmpty()) {
                        DEPENDENCY_BLOCKED to ErrorKind.PERMANENT
                    } else {
                        VALIDATION_FAILED to ErrorKind.PERMANENT
                    }
                buildErrorResult(itemId, trigger, failure.message, errorCode = code, errorKind = kind, blockers = blockersJson)
            }
            is AdvanceFailure.GateBlocked ->
                buildErrorResult(
                    itemId,
                    trigger,
                    failure.message,
                    errorCode = GATE_BLOCKED,
                    errorKind = ErrorKind.PERMANENT,
                    missingNotes = NoteSchemaJsonHelpers.buildMissingNotesArray(failure.missingNotes),
                    missingBySeat = buildMissingBySeatJson(failure.missingBySeat),
                    previousRole = failure.previousRole,
                    targetRole = failure.targetRole,
                    violations = NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(failure.violations)
                )
        }

    /**
     * Builds a per-transition failure result for an `applied:false` outcome, always including
     * [errorCode] + [errorKind] alongside the legacy `error` string (and, where applicable,
     * `blockers`/`missingNotes`/`previousRole`/`targetRole`) — every failure path in this tool
     * carries a code so `subagent-start.mjs` and other hooks can branch on the code's presence
     * rather than inferring "already in phase" from a codeless `applied:false`.
     */
    private fun buildErrorResult(
        itemId: UUID,
        trigger: String,
        error: String,
        errorCode: String,
        errorKind: ErrorKind,
        blockers: JsonArray? = null,
        missingNotes: JsonArray? = null,
        missingBySeat: JsonObject? = null,
        previousRole: Role? = null,
        targetRole: Role? = null,
        violations: JsonArray? = null
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(error))
            put("errorCode", JsonPrimitive(errorCode))
            put("errorKind", JsonPrimitive(errorKind.toJsonString()))
            if (blockers != null) {
                put("blockers", blockers)
            }
            if (missingNotes != null) {
                put("missingNotes", missingNotes)
            }
            if (missingBySeat != null) {
                put("missingBySeat", missingBySeat)
            }
            previousRole?.let { put("previousRole", JsonPrimitive(it.toJsonString())) }
            targetRole?.let { put("targetRole", JsonPrimitive(it.toJsonString())) }
            if (violations != null) {
                put("violations", violations)
            }
        }

    /**
     * Builds a per-transition error result with structured [ToolError] fields.
     *
     * Adds `kind`, `errorCode`, `retryAfterMs`, and `contendedItemId` alongside the legacy `error`
     * string so agents can make programmatic retry decisions on ownership rejections, policy
     * rejections, and resource-lease contention.
     *
     * @param contendedResources contended resource KEYS for a `resource_unavailable` rejection.
     *   Keys only — the holding item and actor are never disclosed here.
     */
    private fun buildStructuredErrorResult(
        itemId: UUID,
        trigger: String,
        toolError: ToolError,
        contendedResources: List<String> = emptyList()
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            put("applied", JsonPrimitive(false))
            put("error", JsonPrimitive(toolError.message))
            put("errorKind", JsonPrimitive(toolError.kind.toJsonString()))
            put("errorCode", JsonPrimitive(toolError.code))
            toolError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
            toolError.contendedItemId?.let { put("contendedItemId", JsonPrimitive(it.toString())) }
            if (contendedResources.isNotEmpty()) {
                put("contendedResources", JsonArray(contendedResources.map { JsonPrimitive(it) }))
            }
        }

    companion object {
        /** Item id in `transitions[].itemId` did not resolve to a full UUID or a known hex prefix. */
        const val ITEM_NOT_FOUND = "item_not_found"

        /** `transitions[].trigger` is not a recognized [Trigger.User] value. */
        const val INVALID_TRIGGER = "invalid_trigger"

        /** `transitions[].actor` failed to parse (see [ActorParseResult.Invalid]). */
        const val INVALID_ACTOR = "invalid_actor"

        /** [AdvanceFailure.GateBlocked] — required notes for the current phase are unfilled. */
        const val GATE_BLOCKED = "gate_blocked"

        /** [AdvanceFailure.ValidationFailed] with a non-empty `blockers` list. */
        const val DEPENDENCY_BLOCKED = "dependency_blocked"

        /** [AdvanceFailure.ValidationFailed] with no `blockers` (e.g. a `credentialRefs` rule). */
        const val VALIDATION_FAILED = "validation_failed"

        /** [AdvanceFailure.ResolutionFailed] — the trigger has no transition from the item's current role. */
        const val INVALID_TRANSITION = "invalid_transition"

        /** [AdvanceFailure.ApplyFailed] — the transition was valid but the DB write failed. */
        const val APPLY_FAILED = "apply_failed"
    }
}
