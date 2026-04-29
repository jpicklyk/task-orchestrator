package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ActorAware
import io.github.jpicklyk.mcptask.current.application.tools.ActorParseResult
import io.github.jpicklyk.mcptask.current.application.tools.BaseToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.PolicyResolution
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimResult
import io.github.jpicklyk.mcptask.current.domain.repository.ReleaseResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

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
 * **Release semantics:**
 * - Only the current claim holder can release; other agents receive `not_claimed_by_you`.
 *
 * Future extension point: a `requestId` parameter for idempotency will be added by item 8.
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
- `claims` (optional array): Items to claim. Each element: `{ itemId (required UUID or short hex), ttlSeconds? (optional int, default 900), agentId? (optional string, overridden by verified actor) }`
- `releases` (optional array): Items to release. Each element: `{ itemId (required UUID or short hex) }`
- `actor` (required): `{ id (required string), kind (required: orchestrator|subagent|user|external), parent? (optional string), proof? (optional string) }` — identity used as the claim holder. Verified identity overrides self-reported agentId.

At least one of `claims` or `releases` must be non-empty.

**Claim outcomes per item:**
- `success` — claim placed or TTL refreshed (same agent re-claim). Response includes own claim metadata.
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
                                    "Array of claim requests: { itemId (required UUID or hex prefix), " +
                                        "ttlSeconds? (optional int, default 900), " +
                                        "agentId? (optional string, overridden by verified actor) }"
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
                },
            required = listOf("actor")
        )

    override fun validateParams(params: JsonElement) {
        val paramsObj = params as? JsonObject
        val claims = paramsObj?.get("claims") as? JsonArray
        val releases = paramsObj?.get("releases") as? JsonArray
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

        claims?.forEachIndexed { index, element ->
            val obj =
                element as? JsonObject
                    ?: throw ToolValidationException("claims[$index] must be a JSON object")
            val itemIdPrim =
                obj["itemId"] as? JsonPrimitive
                    ?: throw ToolValidationException("claims[$index] missing required field: itemId")
            if (!itemIdPrim.isString || itemIdPrim.content.isBlank()) {
                throw ToolValidationException("claims[$index].itemId must be a non-empty string")
            }
            validateIdStringOrPrefix(itemIdPrim.content, "claims[$index].itemId")

            val ttlPrim = obj["ttlSeconds"] as? JsonPrimitive
            if (ttlPrim != null) {
                val ttl =
                    ttlPrim.content.toIntOrNull()
                        ?: throw ToolValidationException("claims[$index].ttlSeconds must be a positive integer")
                if (ttl <= 0) throw ToolValidationException("claims[$index].ttlSeconds must be positive, got $ttl")
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
            val itemIdStr = (claimObj["itemId"] as? JsonPrimitive)?.content ?: continue
            val ttlSeconds = (claimObj["ttlSeconds"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 900

            // Resolve ID (full UUID or prefix)
            val (itemId, idError) = resolveIdString(itemIdStr, context)
            if (idError != null) {
                claimsFailed++
                claimResultsList.add(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemIdStr))
                        put("outcome", JsonPrimitive("not_found"))
                        put("error", JsonPrimitive("Failed to resolve item ID: $itemIdStr"))
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
                        }
                    )
                }

                is ClaimResult.NotFound -> {
                    claimsFailed++
                    claimResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.itemId.toString()))
                            put("outcome", JsonPrimitive("not_found"))
                        }
                    )
                }

                is ClaimResult.TerminalItem -> {
                    claimsFailed++
                    claimResultsList.add(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(result.itemId.toString()))
                            put("outcome", JsonPrimitive("terminal_item"))
                        }
                    )
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
