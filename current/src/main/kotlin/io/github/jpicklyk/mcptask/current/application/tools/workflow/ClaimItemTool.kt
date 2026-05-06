package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.tools.ActorAware
import io.github.jpicklyk.mcptask.current.application.tools.ActorParseResult
import io.github.jpicklyk.mcptask.current.application.tools.BaseToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.PolicyResolution
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID

/**
 * MCP tool for atomically claiming or releasing work items.
 *
 * **Claim semantics:**
 * - One claim per agent: claiming item B auto-releases agent's prior claim on item A (if any).
 * - Re-claiming an item refreshes the TTL but preserves [WorkItem.originalClaimedAt].
 * - Only non-terminal items can be claimed (QUEUE, WORK, REVIEW, BLOCKED).
 * - Identity is resolved via [ActorAware.resolveTrustedActorId] using the configured
 *   [DegradedModePolicy]; a `REJECT`-policy with unverified actor returns `rejected_by_policy`.
 *
 * **Tiered disclosure:**
 * - Success: own claim metadata (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt).
 * - `already_claimed`: returns `retryAfterMs` based on existing claim TTL. Does NOT leak
 *   the competing agent's identity.
 *
 * **Selector mode:**
 * - Each claim entry may use `selector` (instead of `itemId`) for atomic find-and-claim.
 * - Selector resolves a filter+rank query and claims the top match in one call.
 * - Single-claim-per-call enforced by validation for all claim modes (`claims.size > 1` rejected).
 * - `no_match` outcome (kind=permanent) when the queue is empty for the given filters.
 * - `claimRef` (optional, ≤64 chars) is echoed in every result for caller correlation.
 *
 * **Release semantics:**
 * - Only the current claim holder can release; other agents receive `not_claimed_by_you`.
 *
 * Requires `requestId` for idempotency: `claim_item` is a fleet-mode tool by definition.
 * Single-orchestrator deployments do not use `claim_item`; fleet callers operate in a
 * multi-agent context where network retries are a real concern, so idempotency is a hard contract.
 * Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response
 * without re-executing the claim/release operations.
 *
 * Idempotency: a (actor, requestId) cache hit replays the resolved response verbatim —
 * including the same itemId for selector calls. Selector is NOT re-evaluated against fresh
 * queue state on retry.
 */
class ClaimItemTool :
    BaseToolDefinition(),
    ActorAware {
    override val name = "claim_item"

    override val description =
        """
Atomically claim or release work items. One claim per agent: claiming a new item auto-releases
any prior claim held by the same agent.

**Parameters:**
- `claims` (optional array): Items to claim. Each element supports two mutually exclusive modes:
  - ID mode: `{ itemId (required UUID or short hex), ttlSeconds? (optional int, default 900, max 86400), agentId? (optional string, overridden by verified actor), claimRef? (optional string, max 64 chars — echoed in result) }`
  - Selector mode: `{ selector (required object — see below), ttlSeconds? (optional int, default 900, max 86400), claimRef? (optional string, max 64 chars — echoed in result) }`
  - Each entry must have exactly one of `itemId` or `selector`.
  - **Single-claim-per-call:** `claims` array must contain at most 1 entry. Use one call per item.
- `releases` (optional array): Items to release. Each element: `{ itemId (required UUID or short hex) }`
- `actor` (required): `{ id (required string), kind (required: orchestrator|subagent|user|external), parent? (optional string), proof? (optional string) }` — identity used as the claim holder. Verified identity overrides self-reported agentId.
- `requestId` (required UUID): Client-generated UUID for idempotency. Required — `claim_item` is a fleet-mode tool and idempotency is a hard contract. Repeated calls with the same (actor, requestId) within ~10 minutes return the cached response without re-executing.

**Selector object fields** (all optional except the parent `selector` key itself):
- `role` (string, default "queue"): role to search in — queue|work|review|blocked
- `parentId` (string UUID): filter to items under this parent
- `tags` (string, comma-separated): filter by tags
- `priority` (string): HIGH|MEDIUM|LOW
- `type` (string): item type
- `complexityMax` (int, 1–10): maximum complexity
- `createdAfter` (string, ISO 8601): items created after this timestamp
- `createdBefore` (string, ISO 8601): items created before this timestamp
- `roleChangedAfter` (string, ISO 8601): items whose role changed after this timestamp
- `roleChangedBefore` (string, ISO 8601): items whose role changed before this timestamp
- `orderBy` (string): priority|oldest|newest (default: priority)

At least one of `claims` or `releases` must be non-empty.

**Single-claim-per-call:** `claims` array must contain at most 1 entry. Use one call per item.
The cap derives from the heartbeat write-budget assumption (one TTL refresh per agent per cycle).
A future `claim_heartbeats` table mitigation could remove the constraint.
Releases array (`releases`) continues to support N entries — only `claims` is restricted.

**Claim outcomes per item:**
- `success` — claim placed or TTL refreshed (same agent re-claim). Response includes own claim metadata. Selector claims also include `selectorResolved: true`.
- `no_match` — selector found no eligible items; kind=permanent. No claim attempted, no retryAfterMs.
- `already_claimed` — another agent holds a live claim. Response includes `retryAfterMs` (no competing agent identity).
- `not_found` — no item with that ID.
- `terminal_item` — item is in TERMINAL role; cannot be claimed.
- `rejected_by_policy` — actor verification rejected by `degradedModePolicy=reject`.

**Release outcomes per item:**
- `success` — claim cleared.
- `not_claimed_by_you` — item is not claimed by this agent (or is unclaimed).
- `not_found` — no item with that ID.

**Response:**
```json
{
  "claimResults": [
    {
      "itemId": "uuid",
      "outcome": "success",
      "selectorResolved": true,
      "claimRef": "my-ref",
      "claimedBy": "agent-id",
      "claimedAt": "2026-01-01T00:00:00Z",
      "claimExpiresAt": "2026-01-01T00:15:00Z",
      "originalClaimedAt": "2026-01-01T00:00:00Z"
    }
  ],
  "releaseResults": [
    { "itemId": "uuid", "outcome": "success" }
  ],
  "summary": { "claimsTotal": 1, "claimsSucceeded": 1, "claimsFailed": 0, "releasesTotal": 0, "releasesSucceeded": 0, "releasesFailed": 0 }
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
                        "claims",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive(
                                    "Array of claim requests. Max 1 entry per call — use one call per item. " +
                                        "Each entry uses either ID mode: " +
                                        "{ itemId (required UUID or hex prefix), " +
                                        "ttlSeconds? (optional int, default 900, min 1, max 86400 — 24 h cap), " +
                                        "agentId? (optional string, overridden by verified actor), " +
                                        "claimRef? (optional string, max 64 chars) } " +
                                        "or Selector mode: { selector (required object with filter fields), " +
                                        "ttlSeconds? (optional int), claimRef? (optional string, max 64 chars) }. " +
                                        "Exactly one of itemId or selector must be present per entry."
                                )
                            )
                        }
                    )
                    put(
                        "releases",
                        buildJsonObject {
                            put("type", JsonPrimitive("array"))
                            put(
                                "description",
                                JsonPrimitive("Array of release requests: { itemId (required UUID or hex prefix) }")
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
                                    "Required actor claim: { id (required string), " +
                                        "kind (required: orchestrator|subagent|user|external), " +
                                        "parent? (optional string), proof? (optional string) }"
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
                                    "Client-generated UUID for idempotency. Required — claim_item is a fleet-mode tool " +
                                        "and idempotency is a hard contract. Repeated calls with the same (actor, requestId) " +
                                        "within ~10 minutes return the cached response without re-executing."
                                )
                            )
                        }
                    )
                },
            required = listOf("actor", "requestId")
        )

    override fun validateParams(params: JsonElement) {
        val paramsObj = params as? JsonObject

        // requestId is required for claim_item — fleet-mode idempotency is a hard contract
        val requestIdPrim = paramsObj?.get("requestId") as? JsonPrimitive
        if (requestIdPrim == null || requestIdPrim.content.isBlank()) {
            throw ToolValidationException(
                "requestId is required for claim_item. claim_item is a fleet-mode tool — " +
                    "generate a fresh UUID per call and supply it to enforce idempotency."
            )
        }
        try {
            UUID.fromString(requestIdPrim.content)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException(
                "requestId must be a valid UUID, got: '${requestIdPrim.content}'"
            )
        }

        val claims = paramsObj.get("claims") as? JsonArray
        val releases = paramsObj.get("releases") as? JsonArray
        val hasWork = (!claims.isNullOrEmpty()) || (!releases.isNullOrEmpty())
        if (!hasWork) {
            throw ToolValidationException("At least one of 'claims' or 'releases' must be non-empty")
        }

        // Validate actor.id is a non-blank string — an empty or whitespace-only id would
        // result in an untrackable claim holder and must be rejected eagerly.
        val actorObj = paramsObj?.get("actor") as? JsonObject
        if (actorObj != null) {
            val actorId =
                (actorObj["id"] as? JsonPrimitive)?.content
            if (actorId != null && actorId.isBlank()) {
                throw ToolValidationException("actor.id must be a non-blank string")
            }
        }

        // Validate claims entries — check for single-claim-per-call rule
        if (!claims.isNullOrEmpty()) {
            // Universal single-claim-per-call rule: claims array must contain at most 1 entry.
            // The cap derives from the heartbeat write-budget assumption (one TTL refresh per agent
            // per cycle). Multi-claim was previously silently broken for ID-based calls (the
            // repository auto-released all but the last claim while reporting all as success).
            if (claims.size > 1) {
                throw ToolValidationException(
                    "multi_claim_not_supported: claim_item currently supports one claim per call. " +
                        "Issue separate calls per item. " +
                        "(Multi-claim previously silently released all but the last claim — " +
                        "that footgun is now closed; the cap may be re-evaluated in a future major " +
                        "version if a use case emerges.)"
                )
            }

            claims.forEachIndexed { index, element ->
                val obj =
                    element as? JsonObject
                        ?: throw ToolValidationException("claims[$index] must be a JSON object")

                val hasItemId = obj.containsKey("itemId")
                val hasSelector = obj.containsKey("selector")

                // Exactly one of itemId or selector must be present
                if (hasItemId && hasSelector) {
                    throw ToolValidationException(
                        "claims[$index] must specify either itemId or selector, not both"
                    )
                }
                if (!hasItemId && !hasSelector) {
                    throw ToolValidationException(
                        "claims[$index] must specify either itemId or selector — neither was provided"
                    )
                }

                if (hasItemId) {
                    // ID-mode validation
                    val itemIdPrim =
                        obj["itemId"] as? JsonPrimitive
                            ?: throw ToolValidationException("claims[$index].itemId must be a string")
                    if (!itemIdPrim.isString || itemIdPrim.content.isBlank()) {
                        throw ToolValidationException("claims[$index].itemId must be a non-empty string")
                    }
                    validateIdStringOrPrefix(itemIdPrim.content, "claims[$index].itemId")
                } else {
                    // Selector-mode validation
                    val selectorObj =
                        obj["selector"] as? JsonObject
                            ?: throw ToolValidationException("claims[$index].selector must be a JSON object")

                    // role
                    val rolePrim = selectorObj["role"] as? JsonPrimitive
                    if (rolePrim != null) {
                        Role.fromString(rolePrim.content)
                            ?: throw ToolValidationException(
                                "claims[$index].selector.role must be one of: ${Role.VALID_NAMES.joinToString()}, got '${rolePrim.content}'"
                            )
                    }

                    // priority
                    val priorityPrim = selectorObj["priority"] as? JsonPrimitive
                    if (priorityPrim != null) {
                        Priority.fromString(priorityPrim.content)
                            ?: throw ToolValidationException(
                                "claims[$index].selector.priority must be high, medium, or low, got '${priorityPrim.content}'"
                            )
                    }

                    // complexityMax (1..10)
                    val complexityMaxPrim = selectorObj["complexityMax"] as? JsonPrimitive
                    if (complexityMaxPrim != null) {
                        val v =
                            complexityMaxPrim.content.toIntOrNull()
                                ?: throw ToolValidationException("claims[$index].selector.complexityMax must be an integer")
                        if (v < 1 || v > 10) {
                            throw ToolValidationException(
                                "claims[$index].selector.complexityMax must be between 1 and 10, got $v"
                            )
                        }
                    }

                    // createdAfter / createdBefore / roleChangedAfter / roleChangedBefore — ISO 8601
                    listOf("createdAfter", "createdBefore", "roleChangedAfter", "roleChangedBefore").forEach { field ->
                        val prim = selectorObj[field] as? JsonPrimitive
                        if (prim != null) {
                            try {
                                Instant.parse(prim.content)
                            } catch (_: Exception) {
                                throw ToolValidationException(
                                    "claims[$index].selector.$field must be an ISO 8601 timestamp, got '${prim.content}'"
                                )
                            }
                        }
                    }

                    // orderBy
                    val orderByPrim = selectorObj["orderBy"] as? JsonPrimitive
                    if (orderByPrim != null) {
                        NextItemOrder.fromString(orderByPrim.content)
                            ?: throw ToolValidationException(
                                "claims[$index].selector.orderBy must be priority, oldest, or newest, got '${orderByPrim.content}'"
                            )
                    }

                    // parentId — accepts full UUID or hex prefix (4+ chars), matching itemId
                    // resolution and every other parentId field on the surface (get_next_item,
                    // manage_items, create_work_tree). Format check only — actual prefix
                    // resolution happens in execute() before buildCriteria.
                    val parentIdPrim = selectorObj["parentId"] as? JsonPrimitive
                    if (parentIdPrim != null) {
                        if (!parentIdPrim.isString || parentIdPrim.content.isBlank()) {
                            throw ToolValidationException(
                                "claims[$index].selector.parentId must be a non-empty string"
                            )
                        }
                        validateIdStringOrPrefix(parentIdPrim.content, "claims[$index].selector.parentId")
                    }

                    // tags — non-blank string
                    val tagsPrim = selectorObj["tags"] as? JsonPrimitive
                    if (tagsPrim != null && tagsPrim.content.isBlank()) {
                        throw ToolValidationException("claims[$index].selector.tags must be a non-blank string")
                    }

                    // type — non-blank string
                    val typePrim = selectorObj["type"] as? JsonPrimitive
                    if (typePrim != null && typePrim.content.isBlank()) {
                        throw ToolValidationException("claims[$index].selector.type must be a non-blank string")
                    }
                }

                // claimRef — max 64 chars, non-blank when present
                val claimRefPrim = obj["claimRef"] as? JsonPrimitive
                if (claimRefPrim != null) {
                    if (claimRefPrim.content.isBlank()) {
                        throw ToolValidationException("claims[$index].claimRef must be a non-blank string when present")
                    }
                    if (claimRefPrim.content.length > 64) {
                        throw ToolValidationException(
                            "claims[$index].claimRef must not exceed 64 characters, got ${claimRefPrim.content.length}"
                        )
                    }
                }

                // ttlSeconds
                val ttlPrim = obj["ttlSeconds"] as? JsonPrimitive
                if (ttlPrim != null) {
                    val ttl =
                        ttlPrim.content.toIntOrNull()
                            ?: throw ToolValidationException("claims[$index].ttlSeconds must be a positive integer")
                    if (ttl <= 0) throw ToolValidationException("claims[$index].ttlSeconds must be positive, got $ttl")
                    if (ttl > 86400) throw ToolValidationException("claims[$index].ttlSeconds must not exceed 86400 (24 hours), got $ttl")
                }
            }
        }

        releases?.forEachIndexed { index, element ->
            val obj =
                element as? JsonObject
                    ?: throw ToolValidationException("releases[$index] must be a JSON object")
            val itemIdPrim =
                obj["itemId"] as? JsonPrimitive
                    ?: throw ToolValidationException("releases[$index] missing required field: itemId")
            if (!itemIdPrim.isString || itemIdPrim.content.isBlank()) {
                throw ToolValidationException("releases[$index].itemId must be a non-empty string")
            }
            validateIdStringOrPrefix(itemIdPrim.content, "releases[$index].itemId")
        }
    }

    override suspend fun execute(
        params: JsonElement,
        context: ToolExecutionContext
    ): JsonElement {
        val paramsObj = params as? JsonObject ?: return errorResponse("Parameters must be a JSON object")

        // --- Idempotency: requestId is required and already validated as a valid UUID ---
        val requestId = UUID.fromString(optionalString(params, "requestId")!!)

        // --- Actor resolution ---
        val actorObj = paramsObj["actor"] as? JsonObject
        val actorResult = parseActorClaim(actorObj, context)
        val (actorClaim, verification) =
            when (actorResult) {
                is ActorParseResult.Success -> Pair(actorResult.claim, actorResult.verification)
                is ActorParseResult.Absent ->
                    return errorResponse("actor is required for claim_item", ErrorCodes.VALIDATION_ERROR)
                is ActorParseResult.Invalid ->
                    return errorResponse(actorResult.error, ErrorCodes.VALIDATION_ERROR)
            }

        // Resolve trusted identity via DegradedModePolicy.
        val policyResolution =
            ActorAware.resolveTrustedActorId(actorClaim, verification, context.degradedModePolicy)
        val trustedAgentId =
            when (policyResolution) {
                is PolicyResolution.Trusted -> policyResolution.trustedId
                is PolicyResolution.Rejected -> {
                    // Policy-wide rejection: all claims fail, no releases attempted.
                    return buildRejectedByPolicyResponse(policyResolution.reason)
                }
            }

        // Atomic getOrCompute: check-compute-store under a single lock to prevent TOCTOU races.
        // Identity is already resolved above (trustedAgentId) — cache is keyed on the verified identity.
        // kotlinx.coroutines.runBlocking bridges the suspend execution into the lock-held lambda.
        // This is safe because the claim/release logic only accesses DB repositories and never
        // re-acquires the IdempotencyCache lock.
        return context.idempotencyCache.getOrCompute(trustedAgentId, requestId) {
            runBlocking { executeClaimRelease(paramsObj, context, trustedAgentId) }
        }
    }

    private suspend fun executeClaimRelease(
        paramsObj: JsonObject,
        context: ToolExecutionContext,
        trustedAgentId: String
    ): JsonElement {
        val claimsArray = paramsObj["claims"] as? JsonArray ?: JsonArray(emptyList())
        val releasesArray = paramsObj["releases"] as? JsonArray ?: JsonArray(emptyList())

        val claimResultsList = mutableListOf<JsonObject>()
        val releaseResultsList = mutableListOf<JsonObject>()
        var claimsSucceeded = 0
        var claimsFailed = 0
        var releasesSucceeded = 0
        var releasesFailed = 0

        // --- Process claims ---
        for (element in claimsArray) {
            val claimObj = element as? JsonObject ?: continue
            val ttlSeconds = (claimObj["ttlSeconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 900
            val claimRef = (claimObj["claimRef"] as? JsonPrimitive)?.content

            val hasSelector = claimObj.containsKey("selector")

            if (hasSelector) {
                // --- Selector path ---
                val selectorObj = claimObj["selector"] as? JsonObject ?: continue

                // Resolve parentId (may be a hex prefix) before building criteria. Format was
                // already checked in validateParams; resolution here looks up the full UUID
                // for prefixes via the repository. A failed lookup surfaces as not_found.
                val selectorParentIdStr = (selectorObj["parentId"] as? JsonPrimitive)?.content
                var resolvedSelectorParentId: UUID? = null
                if (selectorParentIdStr != null) {
                    val (resolvedUuid, idError) = resolveIdString(selectorParentIdStr, context)
                    if (idError != null) {
                        claimsFailed++
                        claimResultsList.add(
                            buildJsonObject {
                                put("outcome", JsonPrimitive("not_found"))
                                put(
                                    "error",
                                    JsonPrimitive(
                                        "Failed to resolve selector.parentId: $selectorParentIdStr"
                                    )
                                )
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                        continue
                    }
                    resolvedSelectorParentId = resolvedUuid
                }

                val criteria = buildCriteria(selectorObj, resolvedSelectorParentId)

                when (val recommendResult = context.nextItemRecommender.recommend(criteria, limit = 1)) {
                    is Result.Success -> {
                        val items = recommendResult.data
                        if (items.isEmpty()) {
                            // no_match: queue is empty for this filter — permanent outcome
                            claimsFailed++
                            claimResultsList.add(
                                buildJsonObject {
                                    put("outcome", JsonPrimitive("no_match"))
                                    put("kind", JsonPrimitive(ErrorKind.PERMANENT.toJsonString()))
                                    put("code", JsonPrimitive("no_match"))
                                    claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                }
                            )
                            continue
                        }

                        // Resolved: take the top item and claim it
                        val resolvedItem = items.first()
                        val resolvedItemId = resolvedItem.id

                        // Log if caller-supplied agentId differs from verified id.
                        val callerAgentId = (claimObj["agentId"] as? JsonPrimitive)?.content
                        if (callerAgentId != null && callerAgentId != trustedAgentId) {
                            logger.debug(
                                "claim_item: caller-supplied agentId='{}' overridden by verified trustedAgentId='{}'",
                                callerAgentId,
                                trustedAgentId
                            )
                        }

                        when (val claimResult = context.workItemRepository().claim(resolvedItemId, trustedAgentId, ttlSeconds)) {
                            is ClaimResult.Success -> {
                                claimsSucceeded++
                                val item = claimResult.item
                                claimResultsList.add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(item.id.toString()))
                                        put("outcome", JsonPrimitive("success"))
                                        put("selectorResolved", JsonPrimitive(true))
                                        claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                        put("claimedBy", JsonPrimitive(item.claimedBy ?: trustedAgentId))
                                        item.claimedAt?.let { put("claimedAt", JsonPrimitive(it.toString())) }
                                        item.claimExpiresAt?.let { put("claimExpiresAt", JsonPrimitive(it.toString())) }
                                        item.originalClaimedAt?.let {
                                            put("originalClaimedAt", JsonPrimitive(it.toString()))
                                        }
                                    }
                                )
                            }

                            is ClaimResult.AlreadyClaimed -> {
                                // TOCTOU: item was claimed between recommend() and claim()
                                claimsFailed++
                                val alreadyClaimedError =
                                    ToolError(
                                        kind = ErrorKind.TRANSIENT,
                                        code = "already_claimed",
                                        message = "Item ${claimResult.itemId} is already claimed by another agent",
                                        retryAfterMs = claimResult.retryAfterMs,
                                        contendedItemId = claimResult.itemId
                                    )
                                claimResultsList.add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(claimResult.itemId.toString()))
                                        put("outcome", JsonPrimitive("already_claimed"))
                                        put("kind", JsonPrimitive(alreadyClaimedError.kind.toJsonString()))
                                        put("contendedItemId", JsonPrimitive(alreadyClaimedError.contendedItemId!!.toString()))
                                        alreadyClaimedError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
                                        claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                    }
                                )
                            }

                            is ClaimResult.NotFound -> {
                                claimsFailed++
                                claimResultsList.add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(claimResult.itemId.toString()))
                                        put("outcome", JsonPrimitive("not_found"))
                                        claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                    }
                                )
                            }

                            is ClaimResult.TerminalItem -> {
                                claimsFailed++
                                claimResultsList.add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(claimResult.itemId.toString()))
                                        put("outcome", JsonPrimitive("terminal_item"))
                                        claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                    }
                                )
                            }

                            is ClaimResult.DBError -> {
                                claimsFailed++
                                val dbError =
                                    ToolError(
                                        kind = ErrorKind.TRANSIENT,
                                        code = "db_error",
                                        message = "Database error during claim operation",
                                        contendedItemId = claimResult.itemId
                                    )
                                claimResultsList.add(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(claimResult.itemId.toString()))
                                        put("outcome", JsonPrimitive("db_error"))
                                        put("kind", JsonPrimitive(dbError.kind.toJsonString()))
                                        put("code", JsonPrimitive(dbError.code))
                                        put("message", JsonPrimitive(dbError.message))
                                        put("contendedItemId", JsonPrimitive(dbError.contendedItemId!!.toString()))
                                        claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                    }
                                )
                            }
                        }
                    }

                    is Result.Error -> {
                        claimsFailed++
                        claimResultsList.add(
                            buildJsonObject {
                                put("outcome", JsonPrimitive("db_error"))
                                put("kind", JsonPrimitive(ErrorKind.TRANSIENT.toJsonString()))
                                put("code", JsonPrimitive("db_error"))
                                put("message", JsonPrimitive("Database error during selector recommendation"))
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                    }
                }
            } else {
                // --- ID-based path (existing logic) ---
                val itemIdStr = (claimObj["itemId"] as? JsonPrimitive)?.content ?: continue

                // Resolve ID (full UUID or prefix)
                val (itemId, idError) = resolveIdString(itemIdStr, context)
                if (idError != null) {
                    claimsFailed++
                    claimResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemIdStr))
                            put("outcome", JsonPrimitive("not_found"))
                            put("error", JsonPrimitive("Failed to resolve item ID: $itemIdStr"))
                            claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                        }
                    )
                    continue
                }

                // Log if caller-supplied agentId differs from verified id.
                val callerAgentId = (claimObj["agentId"] as? JsonPrimitive)?.content
                if (callerAgentId != null && callerAgentId != trustedAgentId) {
                    logger.debug(
                        "claim_item: caller-supplied agentId='{}' overridden by verified trustedAgentId='{}'",
                        callerAgentId,
                        trustedAgentId
                    )
                }

                when (val result = context.workItemRepository().claim(itemId!!, trustedAgentId, ttlSeconds)) {
                    is ClaimResult.Success -> {
                        claimsSucceeded++
                        val item = result.item
                        claimResultsList.add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(item.id.toString()))
                                put("outcome", JsonPrimitive("success"))
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                                put("claimedBy", JsonPrimitive(item.claimedBy ?: trustedAgentId))
                                item.claimedAt?.let { put("claimedAt", JsonPrimitive(it.toString())) }
                                item.claimExpiresAt?.let { put("claimExpiresAt", JsonPrimitive(it.toString())) }
                                item.originalClaimedAt?.let {
                                    put("originalClaimedAt", JsonPrimitive(it.toString()))
                                }
                            }
                        )
                    }

                    is ClaimResult.AlreadyClaimed -> {
                        claimsFailed++
                        // Emit ToolError fields (kind, retryAfterMs, contendedItemId) so agents can make
                        // retry decisions without string-parsing. Tiered disclosure: no competing agent identity.
                        val alreadyClaimedError =
                            ToolError(
                                kind = ErrorKind.TRANSIENT,
                                code = "already_claimed",
                                message = "Item ${result.itemId} is already claimed by another agent",
                                retryAfterMs = result.retryAfterMs,
                                contendedItemId = result.itemId
                            )
                        claimResultsList.add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(result.itemId.toString()))
                                put("outcome", JsonPrimitive("already_claimed"))
                                put("kind", JsonPrimitive(alreadyClaimedError.kind.toJsonString()))
                                put("contendedItemId", JsonPrimitive(alreadyClaimedError.contendedItemId!!.toString()))
                                // Tiered disclosure: retryAfterMs only — no competing agent identity.
                                alreadyClaimedError.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                    }

                    is ClaimResult.NotFound -> {
                        claimsFailed++
                        claimResultsList.add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(result.itemId.toString()))
                                put("outcome", JsonPrimitive("not_found"))
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                    }

                    is ClaimResult.TerminalItem -> {
                        claimsFailed++
                        claimResultsList.add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(result.itemId.toString()))
                                put("outcome", JsonPrimitive("terminal_item"))
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                    }

                    is ClaimResult.DBError -> {
                        claimsFailed++
                        val dbError =
                            ToolError(
                                kind = ErrorKind.TRANSIENT,
                                code = "db_error",
                                message = "Database error during claim operation",
                                contendedItemId = result.itemId
                            )
                        claimResultsList.add(
                            buildJsonObject {
                                put("itemId", JsonPrimitive(result.itemId.toString()))
                                put("outcome", JsonPrimitive("db_error"))
                                put("kind", JsonPrimitive(dbError.kind.toJsonString()))
                                put("code", JsonPrimitive(dbError.code))
                                put("message", JsonPrimitive(dbError.message))
                                put("contendedItemId", JsonPrimitive(dbError.contendedItemId!!.toString()))
                                claimRef?.let { put("claimRef", JsonPrimitive(it)) }
                            }
                        )
                    }
                }
            }
        }

        // --- Process releases ---
        for (element in releasesArray) {
            val releaseObj = element as? JsonObject ?: continue
            val itemIdStr = (releaseObj["itemId"] as? JsonPrimitive)?.content ?: continue

            val (itemId, idError) = resolveIdString(itemIdStr, context)
            if (idError != null) {
                releasesFailed++
                releaseResultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemIdStr))
                        put("outcome", JsonPrimitive("not_found"))
                        put("error", JsonPrimitive("Failed to resolve item ID: $itemIdStr"))
                    }
                )
                continue
            }

            when (val result = context.workItemRepository().release(itemId!!, trustedAgentId)) {
                is ReleaseResult.Success -> {
                    releasesSucceeded++
                    releaseResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.item.id.toString()))
                            put("outcome", JsonPrimitive("success"))
                        }
                    )
                }

                is ReleaseResult.NotClaimedByYou -> {
                    releasesFailed++
                    releaseResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.itemId.toString()))
                            put("outcome", JsonPrimitive("not_claimed_by_you"))
                        }
                    )
                }

                is ReleaseResult.NotFound -> {
                    releasesFailed++
                    releaseResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.itemId.toString()))
                            put("outcome", JsonPrimitive("not_found"))
                        }
                    )
                }

                is ReleaseResult.DBError -> {
                    releasesFailed++
                    val dbError =
                        ToolError(
                            kind = ErrorKind.TRANSIENT,
                            code = "db_error",
                            message = "Database error during release operation",
                            contendedItemId = result.itemId
                        )
                    releaseResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.itemId.toString()))
                            put("outcome", JsonPrimitive("db_error"))
                            put("kind", JsonPrimitive(dbError.kind.toJsonString()))
                            put("code", JsonPrimitive(dbError.code))
                            put("message", JsonPrimitive(dbError.message))
                            put("contendedItemId", JsonPrimitive(dbError.contendedItemId!!.toString()))
                        }
                    )
                }
            }
        }

        val data =
            buildJsonObject {
                put("claimResults", JsonArray(claimResultsList))
                put("releaseResults", JsonArray(releaseResultsList))
                put(
                    "summary",
                    buildJsonObject {
                        put("claimsTotal", JsonPrimitive(claimsArray.size))
                        put("claimsSucceeded", JsonPrimitive(claimsSucceeded))
                        put("claimsFailed", JsonPrimitive(claimsFailed))
                        put("releasesTotal", JsonPrimitive(releasesArray.size))
                        put("releasesSucceeded", JsonPrimitive(releasesSucceeded))
                        put("releasesFailed", JsonPrimitive(releasesFailed))
                    }
                )
            }

        return successResponse(data)
    }

    /**
     * Builds a [NextItemRecommender.Criteria] from a selector JSON object.
     *
     * `parentId` is resolved by the caller via [resolveIdString] (because resolution
     * of a hex prefix is suspending and may fail with a not_found error) and passed in
     * here as an already-resolved UUID. Pass null when the selector did not specify a
     * parentId.
     */
    private fun buildCriteria(
        selectorObj: JsonObject,
        resolvedParentId: UUID?
    ): NextItemRecommender.Criteria {
        val roleStr = (selectorObj["role"] as? JsonPrimitive)?.content
        val role = roleStr?.let { Role.fromString(it) } ?: Role.QUEUE

        val tagsStr = (selectorObj["tags"] as? JsonPrimitive)?.content
        val tags = tagsStr?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }

        val priorityStr = (selectorObj["priority"] as? JsonPrimitive)?.content
        val priority = priorityStr?.let { Priority.fromString(it) }

        val type = (selectorObj["type"] as? JsonPrimitive)?.content

        val complexityMaxStr = (selectorObj["complexityMax"] as? JsonPrimitive)?.content
        val complexityMax = complexityMaxStr?.toIntOrNull()

        val createdAfterStr = (selectorObj["createdAfter"] as? JsonPrimitive)?.content
        val createdAfter = createdAfterStr?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val createdBeforeStr = (selectorObj["createdBefore"] as? JsonPrimitive)?.content
        val createdBefore = createdBeforeStr?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val roleChangedAfterStr = (selectorObj["roleChangedAfter"] as? JsonPrimitive)?.content
        val roleChangedAfter = roleChangedAfterStr?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val roleChangedBeforeStr = (selectorObj["roleChangedBefore"] as? JsonPrimitive)?.content
        val roleChangedBefore = roleChangedBeforeStr?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val orderByStr = (selectorObj["orderBy"] as? JsonPrimitive)?.content
        val orderBy = orderByStr?.let { NextItemOrder.fromString(it) } ?: NextItemOrder.PRIORITY_THEN_COMPLEXITY

        return NextItemRecommender.Criteria(
            role = role,
            parentId = resolvedParentId,
            tags = tags,
            priority = priority,
            type = type,
            complexityMax = complexityMax,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            roleChangedAfter = roleChangedAfter,
            roleChangedBefore = roleChangedBefore,
            orderBy = orderBy,
        )
    }

    override fun userSummary(
        params: JsonElement,
        result: JsonElement,
        isError: Boolean
    ): String {
        if (isError) return "claim_item failed"
        val data = (result as? JsonObject)?.get("data") as? JsonObject
        val summary = data?.get("summary") as? JsonObject
        val claimsSucceeded = summary?.get("claimsSucceeded")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val releasesSucceeded = summary?.get("releasesSucceeded")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val claimsFailed = summary?.get("claimsFailed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        val releasesFailed = summary?.get("releasesFailed")?.let { (it as? JsonPrimitive)?.intOrNull } ?: 0
        return buildString {
            if (claimsSucceeded > 0 || claimsFailed > 0) {
                append("Claimed $claimsSucceeded")
                if (claimsFailed > 0) append("/${claimsSucceeded + claimsFailed} ($claimsFailed failed)")
            }
            if (releasesSucceeded > 0 || releasesFailed > 0) {
                if (isNotEmpty()) append(", ")
                append("Released $releasesSucceeded")
                if (releasesFailed > 0) append("/${releasesSucceeded + releasesFailed} ($releasesFailed failed)")
            }
        }.ifEmpty { "claim_item: no operations" }
    }

    private fun buildRejectedByPolicyResponse(reason: String): JsonElement =
        errorResponse(
            ToolError.permanent(
                code = "rejected_by_policy",
                message = "Actor rejected by degradedModePolicy: $reason"
            )
        )
}
