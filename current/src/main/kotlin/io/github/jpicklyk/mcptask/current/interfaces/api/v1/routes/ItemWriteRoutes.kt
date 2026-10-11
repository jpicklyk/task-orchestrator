package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceServiceFactory
import io.github.jpicklyk.mcptask.current.application.service.CredentialRefValidation
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.hasCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceRequestDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.DB_QUERY_FAILED
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestErrorMapper
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val writeLogger = LoggerFactory.getLogger("ItemWriteRoutes")

// Accepted Content-Types for the JSON write bodies (POST /items/{id}/advance). `*/*` is what
// `call.request.contentType()` reports when the header is ABSENT, which ContentNegotiation's
// wildcard match also accepted — so an absent header stays accepted and only a genuinely non-JSON
// Content-Type is rejected.
private val JSON_WRITE_CONTENT_TYPES = setOf("application/json", "*/*")

// Default source for itemWriteRoutes(warnOnClaimedAdvance=...) — reads API_WARN_ON_CLAIMED_ADVANCE
// via the typed AppConfig snapshot. The composition root passes an explicit value from its
// single startup snapshot; this default keeps direct (test) callers on the prior env behavior.
private val defaultWarnOnClaimedAdvance: Boolean
    get() = AppConfig.fromEnv().apiWarnOnClaimedAdvance

// IdempotencyKeyResult + parseIdempotencyKey + runWithIdempotency + CachedHttpResponse live in
// WriteIdempotency.kt (shared with NoteWriteRoutes).

// JSON encoder for capturing serialized write responses (matches the server's explicitNulls=false).
private val writeJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Responds 415 `unsupported_media_type` when [call]'s Content-Type is not JSON (per
 * [JSON_WRITE_CONTENT_TYPES]) and returns true; returns false (no response sent) otherwise. Shared
 * by the advance route's `parseAdvanceRequest`; the gate is byte-identical
 * (status, error code, and message).
 */
private suspend fun respondIfNotJsonContentType(call: ApplicationCall): Boolean {
    val contentType =
        call.request
            .contentType()
            .withoutParameters()
            .toString()
    if (contentType !in JSON_WRITE_CONTENT_TYPES) {
        call.respondError(LegacyRestCode.UNSUPPORTED_MEDIA_TYPE, "Use Content-Type: application/json")
        return true
    }
    return false
}

/**
 * Responds 503 `config_unavailable` for a [PerRootConfigUnavailableException] raised mid-advance-
 * pipeline (status label resolution, gate check, review-phase detection — see the advance route's
 * D6 note). Sibling to [LegacyRestErrorMapper.advanceFailure]; the catch site still decides when to call this and
 * still returns immediately afterward.
 */
private fun advanceConfigUnavailableCaptured(
    itemId: UUID,
    e: PerRootConfigUnavailableException,
): CachedHttpResponse {
    writeLogger.warn("Per-root config unavailable advancing item {}: {}", itemId, e.message)
    return LegacyRestErrorMapper.captured(LegacyRestCode.CONFIG_UNAVAILABLE, e.message)
}

/**
 * Parsed and validated POST /items/{id}/advance request, as returned by the local
 * `parseAdvanceRequest` helper inside [itemWriteRoutes].
 */
private data class ParsedAdvanceRequest(
    val advanceDto: AdvanceRequestDto,
    val userTrigger: Trigger.User,
    val credentialRefs: List<String>,
    val overrideResourceLeases: Boolean,
)

/**
 * Registers the advance route under the `/api/v1` route prefix. Item creates, updates and deletes are the
 * `items_write` tool (`POST /api/v1/ops/items_write`).
 *
 * Endpoints:
 * - `POST   /items/{id}/advance` — role transition ([ApiCapability.ADVANCE])
 *
 * **Audit:** every write synthesizes an [ActorClaim] server-side from the [ApiPrincipal];
 * client `actor.*` body fields are silently dropped.
 *
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on POST.
 * **degradedModePolicy:** `reject` policy + verification failure → 401.
 *
 * @param advanceServiceFactory builds the per-item [io.github.jpicklyk.mcptask.current.application.service.AdvanceService]
 *   for the advance route below — the SAME factory (and therefore the SAME [io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver]
 *   / per-root config cache) the MCP `advance_item` tool uses, so REST-driven advances stamp
 *   identical status labels (bug 80e48e55 — REST previously hardcoded [NoOpStatusLabelService] and
 *   never applied labels at all) and share MCP's per-root config cache. The advance reads that config
 *   inside its unit, where the last-known-good fallback is disabled: a per-root read fault answers 503
 *   `config_unavailable` even when the cache is warm.

 */
fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotency: IdempotencyService,
    advanceServiceFactory: AdvanceServiceFactory,
    unitOfWork: UnitOfWork,
    warnOnClaimedAdvance: Boolean = defaultWarnOnClaimedAdvance,
    clock: Clock = Clock.SYSTEM,
) {
    val workItemRepo = repositoryProvider.workItemRepository()

    // Parses and validates the POST /items/{id}/advance request body text: the AdvanceRequestDto decode,
    // trigger parsing and credentialRefs validation. A failure is a payload rejection (a pure function
    // of the body), returned as the second element; the 415 Content-Type gate and the bounded body read
    // happen BEFORE this, in the route. The ADMIN-only overrideResourceLeases 403 check is a separate
    // step (leaseOverrideForbidden below), called immediately after this returns.
    fun parseAdvanceRequest(advanceBodyText: String): Pair<ParsedAdvanceRequest?, CachedHttpResponse?> {
        // Decoded with McpJson, the same instance ContentNegotiation is installed with, so this behaves
        // exactly as `receive<AdvanceRequestDto>()` did, minus the unbounded buffering.
        val advanceDto =
            try {
                McpJson.decodeFromString(AdvanceRequestDto.serializer(), advanceBodyText)
            } catch (e: SerializationException) {
                return null to payloadRejection(e.message ?: "Invalid request body")
            }

        val userTrigger =
            Trigger.User.parse(advanceDto.trigger) ?: run {
                val validTriggers = Trigger.User.entries.joinToString { it.wire }
                return null to payloadRejection("Invalid trigger '${advanceDto.trigger}'. Valid: $validTriggers")
            }

        // Optional credentialRefs: same shared rules as the MCP advance_item tool
        // (CredentialRefValidation) — a violation fails the request before anything is persisted.
        val credentialRefs = advanceDto.credentialRefs ?: emptyList()
        when (val credentialRefsResult = CredentialRefValidation.validate(credentialRefs)) {
            is CredentialRefValidation.Result.Invalid -> {
                val suffix = if (credentialRefsResult.index >= 0) "[${credentialRefsResult.index}]" else ""
                return null to payloadRejection("credentialRefs$suffix ${credentialRefsResult.reason}")
            }
            is CredentialRefValidation.Result.Valid -> {} // ok
        }

        val overrideResourceLeases = advanceDto.overrideResourceLeases == true

        return ParsedAdvanceRequest(advanceDto, userTrigger, credentialRefs, overrideResourceLeases) to null
    }

    // Optional ADMIN-only resource-lease override gate, called right after parseAdvanceRequest
    // returns. Sent by a non-admin principal this is a hard 403, never a silent no-op: an operator who
    // thinks they bypassed the lease and did not would go on to touch a resource another item is
    // actively holding. Returns the 403 response, or null when the request may proceed.
    fun leaseOverrideForbidden(
        call: ApplicationCall,
        overrideResourceLeases: Boolean,
    ): CachedHttpResponse? =
        if (overrideResourceLeases && !hasCapability(call, ApiCapability.ADMIN)) {
            LegacyRestErrorMapper.captured(LegacyRestCode.INSUFFICIENT_CAPABILITY, "overrideResourceLeases requires the admin capability")
        } else {
            null
        }
    // ─── POST /items/{id}/advance ────────────────────────────────────────────
    requireCapability(ApiCapability.ADVANCE) {
        post("/items/{id}/advance") {
            val principal = call.attributes[ApiPrincipalKey]
            // Resolved for its fail-closed side effect (an unverified JWKS actor would throw here) and
            // as the principal an Idempotency-Key is scoped to; the audit trail below is built directly
            // from `principal` via ApiAuditBridge.toActorClaim/toVerificationResult.
            val trustedActorId = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy)

            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@post
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@post
                }

            // Content-Type gate, then the bounded body read (bug e941c2c7); parsing and every
            // state-dependent check run inside `executeAdvance` so an Idempotency-Key replay returns the
            // stored response without re-evaluating them.
            if (respondIfNotJsonContentType(call)) return@post
            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@post
            val advanceBodyText = call.receiveBounded(MAX_JSON_WRITE_BODY_BYTES) ?: return@post

            suspend fun executeAdvance(): CachedHttpResponse {
                // Payload decode, trigger parsing and credentialRefs validation live in the local
                // `parseAdvanceRequest` helper above; the ADMIN-only overrideResourceLeases 403 check
                // follows in `leaseOverrideForbidden`.
                val (parsedRequest, rejection) = parseAdvanceRequest(advanceBodyText)
                if (parsedRequest == null) return rejection!!
                val advanceDto = parsedRequest.advanceDto
                val userTrigger = parsedRequest.userTrigger
                val credentialRefs = parsedRequest.credentialRefs
                val overrideResourceLeases = parsedRequest.overrideResourceLeases
                leaseOverrideForbidden(call, overrideResourceLeases)?.let { return it }

                val itemResult =
                    legacyRead({
                        return LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    }) { workItemRepo.getById(id) }
                if (itemResult == null) {
                    return LegacyRestErrorMapper.captured(LegacyRestCode.NOT_FOUND, "Item $id not found")
                }
                val item = itemResult

                if (!enforceScopeForItem(call, id, workItemRepo)) {
                    return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                }

                // Claimed item: emit WARN but proceed — API callers override MCP claim semantics
                val isActivelyClaimed = ClaimState.isActive(item, clock.unitNow())

                if (isActivelyClaimed && warnOnClaimedAdvance) {
                    writeLogger.warn(
                        "API_WARN_ON_CLAIMED_ADVANCE: API advance on claimed item; itemId={}, apiTokenId={}, trigger={}",
                        id,
                        principal.tokenId,
                        advanceDto.trigger,
                    )
                }

                // A lease override is always audited: it is the one way an exclusive resource can be
                // entered by two items at once, so it must be reconstructible from logs alone. The
                // transition summary carries the same fact into the durable role_transitions row.
                if (overrideResourceLeases) {
                    writeLogger.warn(
                        "Resource-lease override: admin principal tokenId='{}' bypassed the resource gate for " +
                            "itemId={}, trigger={}. Another work item may hold this item's exclusive resources.",
                        principal.tokenId,
                        id,
                        advanceDto.trigger,
                    )
                }
                val transitionSummary = if (overrideResourceLeases) "(resource leases overridden)" else null

                // Build the synthesized actor for the transition audit trail (server-side only)
                val actorClaim = ApiAuditBridge.toActorClaim(principal)
                val verification = ApiAuditBridge.toVerificationResult(principal)

                // Delegate to the shared AdvanceService — the SAME pipeline the MCP advance_item tool
                // uses (resolve → validate → required-note gate → resource-lease gate → apply → cascade
                // → unblock). The schema resolver mirrors AdvanceItemTool's trait-merged resolution.
                //
                // The two enforcement flags are DELIBERATELY ASYMMETRIC:
                //
                //  * enforceOwnership = false — the REST API bypasses claim ownership entirely (plan §2
                //    — API callers are operators, not fleet agents). The advance SUCCEEDS even when the
                //    item is claimed by a different MCP agent, while the synthesized API actor is still
                //    recorded on the role_transitions row for audit. A claim is one agent's bookkeeping,
                //    and an operator is entitled to overrule it.
                //  * enforceResourceLeases = true (unless an ADMIN explicitly sent overrideResourceLeases)
                //    — a resource lease protects a shared EXTERNAL resource (a credential, a staging
                //    environment). Operator authority cannot make two concurrent holders of a single
                //    credential safe, so this gate is NOT waived by virtue of being an operator; it takes
                //    an explicit, admin-gated, WARN-logged opt-out per request.
                //
                // The required-note gate is ENFORCED. Status labels are bound to THIS item's rootId via the
                // SAME factory AdvanceItemTool uses, so REST advances stamp identical (config-driven, per-root)
                // status labels (bug 80e48e55).
                // Per D6: a per-root config read failure anywhere in this pre-commit pipeline (status
                // label resolution, gate check, review-phase detection) responds 503 with a
                // config_unavailable ErrorDto — no Retry-After header, matching the ErrorKind contract
                // used on the MCP side (RFC 9110 §15.6.4: 503 describes a temporary server-side
                // inability, distinct from the 409 used for resource-state conflicts).
                val outcome =
                    try {
                        withConfigSession {
                            val advanceService = advanceServiceFactory.forItem(item)

                            withEventActor(actorClaim) {
                                advanceService.advance(
                                    item = item,
                                    trigger = userTrigger.wire,
                                    summary = transitionSummary,
                                    actorClaim = actorClaim,
                                    verification = verification,
                                    degradedModePolicy = degradedModePolicy,
                                    enforceOwnership = false,
                                    credentialRefs = credentialRefs,
                                    enforceResourceLeases = !overrideResourceLeases,
                                )
                            }
                        }
                    } catch (e: PerRootConfigUnavailableException) {
                        return advanceConfigUnavailableCaptured(id, e)
                    }

                val advanceResult =
                    when (outcome) {
                        is AdvanceOutcome.Success -> outcome.result
                        is AdvanceOutcome.Failure -> return LegacyRestErrorMapper.advanceFailure(outcome.failure)
                    }

                // Build expectedNotes parity: fetch the item's current note keys for the `exists` flag.
                val existingNoteKeys =
                    run {
                        val notesResult = legacyRead({ return@run emptySet() }) { repositoryProvider.noteRepository().findByItemId(id) }
                        notesResult.map { it.key }.toSet()
                    }

                // Response body MUST NOT disclose claimedBy (tiered-disclosure principle)
                return CachedHttpResponse(
                    statusCode = HttpStatusCode.OK.value,
                    bodyJson = writeJson.encodeToString(AdvanceResponseDto.serializer(), advanceResult.toDto(existingNoteKeys)),
                )
            }

            call.runWithIdempotency(idempotency, trustedActorId, idempotencyKeyResult, "/items/{id}/advance", advanceBodyText) {
                executeAdvance()
            }
        }
    }
}
