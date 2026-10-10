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
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandErrors
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.application.service.ItemPatchCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
import io.github.jpicklyk.mcptask.current.application.service.rest.MergePatchApplier
import io.github.jpicklyk.mcptask.current.application.service.rest.WorkItemPatchProjection
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.lifecycle.Trigger
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.allowsItemTags
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.hasCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.mayHoldRoot
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceRequestDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemCreateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDeleteResultDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemPatchDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.DB_QUERY_FAILED
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.LegacyRestErrorMapper
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.respondError
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.util.UUID

private val writeLogger = LoggerFactory.getLogger("ItemWriteRoutes")

// Field allowlist for PATCH — server-owned fields that must not be patchable
private val REJECTED_PATCH_FIELDS =
    setOf(
        "id",
        "role",
        "previousRole",
        "roleChangedAt",
        "depth",
        "createdAt",
        "modifiedAt",
        "version",
        "claimedBy",
        "claimedAt",
        "claimExpiresAt",
        "originalClaimedAt",
    )

private val MERGE_PATCH_CONTENT_TYPES = setOf("application/merge-patch+json", "application/json")

// Accepted Content-Types for the JSON write bodies (POST /items). `*/*` is what
// `call.request.contentType()` reports when the header is ABSENT, which ContentNegotiation's
// wildcard match also accepted — so an absent header stays accepted and only a genuinely non-JSON
// Content-Type is rejected. Merge-patch is deliberately absent: only PATCH accepts it.
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
 * by `POST /items` and the advance route's `parseAdvanceRequest` — both gates are byte-identical
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

/** 409 `invalid_transition` for a create or reparent under a TERMINAL parent whose lifecycle is AUTO (D2). */
private fun closedParentCaptured(
    parentId: UUID?,
    message: String,
): CachedHttpResponse =
    LegacyRestErrorMapper.captured(
        LegacyRestCode.INVALID_TRANSITION,
        message,
        buildJsonObject { put("parentId", JsonPrimitive(parentId?.toString())) },
    )

/** 503 `config_unavailable` for a per-root config fault resolving a parent's lifecycle inside an item write. */
private fun itemConfigUnavailableCaptured(
    route: String,
    e: PerRootConfigUnavailableException,
): CachedHttpResponse {
    writeLogger.warn("{}: per-root config unavailable: {}", route, e.message)
    return LegacyRestErrorMapper.captured(LegacyRestCode.CONFIG_UNAVAILABLE, e.message)
}

/** The parent id an `invalid_transition` (closed-parent) error names. */
private fun closedParentId(error: DomainError): UUID? =
    (error.detail as? io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail.InvalidTransition)?.itemId

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
 * Registers item-write and advance routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `POST   /items`              — create item ([ApiCapability.WRITE_ITEMS])
 * - `PATCH  /items/{id}`         — JSON Merge Patch update; requires `If-Match` ([ApiCapability.WRITE_ITEMS])
 * - `DELETE /items/{id}`         — delete; a parent refuses with 409 `has_children` unless
 *   `?recursive=true`, which cascades (matching the MCP `manage_items` delete operation's
 *   semantics via [ItemCommandService.delete]) ([ApiCapability.WRITE_ITEMS])
 * - `POST   /items/{id}/advance` — role transition ([ApiCapability.ADVANCE])
 *
 * **Audit:** every write synthesizes an [ActorClaim] server-side from the [ApiPrincipal];
 * client `actor.*` body fields are silently dropped.
 *
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on POST and PATCH.
 * **ETag concurrency:** PATCH requires `If-Match`; DELETE accepts optional `If-Match`.
 * **degradedModePolicy:** `reject` policy + verification failure → 401.
 *
 * @param advanceServiceFactory builds the per-item [io.github.jpicklyk.mcptask.current.application.service.AdvanceService]
 *   for the advance route below — the SAME factory (and therefore the SAME [io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver]
 *   / per-root config cache) the MCP `advance_item` tool uses, so REST-driven advances stamp
 *   identical status labels (bug 80e48e55 — REST previously hardcoded [NoOpStatusLabelService] and
 *   never applied labels at all) and share MCP's per-root config cache. The advance reads that config
 *   inside its unit, where the last-known-good fallback is disabled: a per-root read fault answers 503
 *   `config_unavailable` even when the cache is warm.
 * @param itemCommandService the owner of item creates, patches and deletes — the SAME instance the MCP
 *   `manage_items` tool uses (`ToolExecutionContext.itemCommandService`), so placement, the closed-parent rule,
 *   the old-parent cascade and the item events are identical on both surfaces.
 */
fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotency: IdempotencyService,
    advanceServiceFactory: AdvanceServiceFactory,
    unitOfWork: UnitOfWork,
    itemCommandService: ItemCommandService,
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
    // ─── POST /items ─────────────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        post("/items") {
            val principal = call.attributes[ApiPrincipalKey]
            val trustedActorId = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy)

            // Content-Type gate — explicit because the body is no longer read through
            // `receive<ItemCreateDto>()`, which let ContentNegotiation reject a non-JSON body with
            // 415. Same shape as the PATCH gate below, minus merge-patch. It runs before the body
            // read, so 415 still precedes anything that depends on the body.
            if (respondIfNotJsonContentType(call)) return@post

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@post

            // Read the raw body BEFORE runWithIdempotency: reading it inside would hold this key's
            // idempotency in-flight entry across client-paced network I/O (see runWithIdempotency).
            // Bounded (bug e941c2c7 — this route had no size limit at all before this fix; see
            // receiveBounded's KDoc) and read as bytes only — deserialization stays inside the
            // captured block so status precedence is unchanged. Decoded with McpJson, the same
            // instance ContentNegotiation is installed with, so this behaves exactly as
            // `receive<ItemCreateDto>()` did, minus the unbounded buffering.
            val bodyText = call.receiveBounded(MAX_JSON_WRITE_BODY_BYTES) ?: return@post

            // Produce a CachedHttpResponse so the body is serialized once and replayed verbatim on
            // an Idempotency-Key hit (the DB write runs at most once — see runWithIdempotency).
            suspend fun executeCreate(): CachedHttpResponse {
                val actorClaim = ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey])
                val dto =
                    try {
                        McpJson.decodeFromString(ItemCreateDto.serializer(), bodyText)
                    } catch (e: SerializationException) {
                        return payloadRejection(e.message ?: "Invalid request body")
                    }

                val parentId =
                    dto.parentId?.let { pid ->
                        runCatchingNonCancellation { UUID.fromString(pid) }.getOrNull()
                            ?: return payloadRejection("Invalid parentId UUID: $pid")
                    }

                // Pre-generate the id so a root-level create (parentId == null) can stamp
                // rootId = own id without a second round trip.
                val itemId = UUID.randomUUID()

                // Computed here (rather than alongside propertiesStr below) because the
                // root-create scope check below needs the exact CSV that will be persisted.
                val tagsStr = dto.tags?.joinToString(",")?.takeIf { it.isNotBlank() }

                // Parent existence / scope pre-checks (and the root-create scope check) run BEFORE
                // the priority parse below, matching base error precedence: a bad parentId or an
                // out-of-scope caller must surface before a merely malformed priority value.
                // Placement (depth/rootId) itself is intentionally NOT read here — ItemCommandService
                // resolves it from the parent row read inside the write unit (AR-19).
                if (parentId != null) {
                    val parentResult =
                        legacyRead(
                            { return LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED) }
                        ) { workItemRepo.getById(parentId) }
                    if (parentResult == null) {
                        return LegacyRestErrorMapper.captured(LegacyRestCode.NOT_FOUND_AS_BAD_REQUEST, "Parent item $parentId not found")
                    }
                    if (!enforceScopeForItem(call, parentId, workItemRepo)) {
                        return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for parent $parentId")
                    }
                } else {
                    // A root-level create has no parent to anchor the scope check on: the new item
                    // is its own anchor. rootIds-wise it can never be in scope (see mayHoldRoot);
                    // tag-wise the tags it is created WITH must satisfy the principal's tag scope,
                    // mirroring the parent-tag check taken above for a non-root create.
                    if (!principal.mayHoldRoot(itemId) || !principal.allowsItemTags(tagsStr)) {
                        return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied to create a root item")
                    }
                }

                val priority =
                    dto.priority?.let { pStr ->
                        Priority.entries.find { it.name.equals(pStr, ignoreCase = true) }
                            ?: return payloadRejection("Invalid priority: $pStr")
                    } ?: Priority.MEDIUM

                val command =
                    ItemCreateCommand(
                        id = itemId,
                        parentId = parentId,
                        title = dto.title,
                        description = dto.description,
                        summary = dto.summary ?: "",
                        statusLabel = dto.statusLabel,
                        priority = priority,
                        complexity = dto.complexity,
                        requiresVerification = dto.requiresVerification ?: false,
                        metadata = dto.metadata,
                        tags = tagsStr,
                        type = dto.type,
                        properties = dto.properties?.toString(),
                    )

                // The item is always created in QUEUE; its depth/rootId come from the parent row read
                // INSIDE the write unit, and a TERMINAL parent under auto lifecycle rejects the create.
                val outcome =
                    try {
                        withEventActor(actorClaim) { itemCommandService.create(command) }
                    } catch (e: PerRootConfigUnavailableException) {
                        return itemConfigUnavailableCaptured("POST /items", e)
                    }
                return when (outcome) {
                    is Outcome.Ok ->
                        CachedHttpResponse(
                            statusCode = HttpStatusCode.Created.value,
                            bodyJson = writeJson.encodeToString(ItemDto.serializer(), outcome.value.toDto()),
                            etag = etagFor(outcome.value.modifiedAt),
                        )
                    is Outcome.Err -> {
                        val error = outcome.error
                        when {
                            error.code == ErrorCode.NOT_FOUND ->
                                LegacyRestErrorMapper.captured(LegacyRestCode.NOT_FOUND_AS_BAD_REQUEST, "Parent item $parentId not found")
                            ItemCommandErrors.isClosedParent(error) ->
                                closedParentCaptured(
                                    closedParentId(error) ?: parentId,
                                    "Parent item ${closedParentId(error) ?: parentId} is terminal under auto lifecycle; " +
                                        "reopen it before adding children",
                                )
                            error.code == ErrorCode.INVALID_REQUEST ->
                                LegacyRestErrorMapper.captured(LegacyRestCode.VALIDATION_ERROR, error.message)
                            else -> {
                                writeLogger.warn("POST /items DB error: {}", error.message)
                                LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, "Failed to create item")
                            }
                        }
                    }
                }
            }

            call.runWithIdempotency(idempotency, trustedActorId, idempotencyKeyResult, "/items", bodyText) { executeCreate() }
        }
    }

    // ─── PATCH /items/{id} ───────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        patch("/items/{id}") {
            val principal = call.attributes[ApiPrincipalKey]
            val trustedActorId = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy)

            // Content-Type check — accept merge-patch+json and application/json
            val contentType =
                call.request
                    .contentType()
                    .withoutParameters()
                    .toString()
            if (contentType !in MERGE_PATCH_CONTENT_TYPES) {
                call.response.header("Accept-Patch", "application/merge-patch+json, application/json")
                call.respondError(
                    LegacyRestCode.UNSUPPORTED_MEDIA_TYPE,
                    "Use Content-Type: application/merge-patch+json or application/json"
                )
                return@patch
            }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@patch
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@patch
                }

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@patch

            // Raw bytes only, read BEFORE runWithIdempotency so client-paced network I/O does not
            // happen while this key's idempotency in-flight entry is held. Bounded (bug e941c2c7
            // — see receiveBounded's KDoc). The JSON PARSE stays below, after the If-Match checks,
            // so `precondition_required` / `etag_mismatch` still precede `validation_error` for a
            // malformed body.
            val bodyText = call.receiveBounded(MAX_JSON_WRITE_BODY_BYTES) ?: return@patch

            // The state-dependent pre-conditions (existence, scope, If-Match ETag) AND the write run
            // INSIDE the captured block, so an Idempotency-Key replay returns the cached response
            // verbatim WITHOUT re-evaluating the ETag against the now-mutated item (which would
            // otherwise spuriously 412). The Content-Type 415 check above is request-shape-only and
            // stays outside (a replay carries the same Content-Type).
            suspend fun executePatch(): CachedHttpResponse {
                val actorClaim = ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey])
                val itemResult =
                    legacyRead(
                        { return LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED) }
                    ) { workItemRepo.getById(id) }
                if (itemResult == null) {
                    return LegacyRestErrorMapper.captured(LegacyRestCode.NOT_FOUND, "Item $id not found")
                }
                val existing = itemResult

                if (!enforceScopeForItem(call, id, workItemRepo)) {
                    return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                }

                // ETag concurrency — If-Match required for PATCH
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
                val currentEtag = etagFor(existing.modifiedAt)
                if (ifMatch == null) {
                    return LegacyRestErrorMapper.captured(
                        LegacyRestCode.PRECONDITION_REQUIRED,
                        "PATCH requires If-Match header with current ETag",
                        etag = currentEtag,
                    )
                }
                if (ifMatch != currentEtag) {
                    return LegacyRestErrorMapper.captured(
                        LegacyRestCode.ETAG_MISMATCH,
                        "ETag mismatch; current ETag is $currentEtag",
                        etag = currentEtag,
                    )
                }

                val patchObject =
                    try {
                        Json.parseToJsonElement(bodyText) as? JsonObject
                            ?: return payloadRejection("PATCH body must be a JSON object")
                    } catch (e: Exception) {
                        // Log the parse detail server-side; do not echo the raw exception message back
                        // to the client (avoids leaking parser internals / input fragments).
                        e.rethrowIfCancellation()
                        writeLogger.debug("PATCH body JSON parse failed: {}", e.message)
                        return payloadRejection("Invalid JSON in request body")
                    }

                // Security: reject any attempt to patch server-owned fields
                val disallowedFields = patchObject.keys.intersect(REJECTED_PATCH_FIELDS)
                if (disallowedFields.isNotEmpty()) {
                    return LegacyRestErrorMapper.captured(
                        LegacyRestCode.FIELD_NOT_PATCHABLE,
                        "The following fields cannot be patched: ${disallowedFields.joinToString()}"
                    )
                }

                // Merge-patch flow: project existing → apply patch → normalize → decode → update
                val base = WorkItemPatchProjection.toJsonObject(existing)
                val merged = MergePatchApplier.apply(base, patchObject) as JsonObject

                // Normalize: `properties` in the merged object may be a JsonObject (from
                // recursive merge). ItemPatchDto.properties is String? — re-serialize it.
                val normalizedMerged: JsonObject =
                    run {
                        val propsElement = merged["properties"]
                        if (propsElement != null && propsElement is JsonObject) {
                            JsonObject(
                                merged.toMutableMap().also { map ->
                                    map["properties"] = JsonPrimitive(propsElement.toString())
                                }
                            )
                        } else {
                            merged
                        }
                    }

                val patchDto =
                    try {
                        Json.decodeFromJsonElement<ItemPatchDto>(normalizedMerged)
                    } catch (e: Exception) {
                        // Log the decode detail server-side; return a generic message to the client.
                        e.rethrowIfCancellation()
                        writeLogger.debug("PATCH patch-value decode failed: {}", e.message)
                        return payloadRejection("Invalid patch values")
                    }

                // The projection (WorkItemPatchProjection) includes EVERY patchable field in `base`,
                // so `normalizedMerged` is the COMPLETE desired final state and `patchDto` carries the
                // final value for each field. RFC 7396 null-delete works correctly: a `null` patch
                // removes the key during merge, so the field decodes to null in patchDto (→ cleared).
                // We therefore use patchDto values directly. Required, non-nullable fields (title,
                // summary, priority, requiresVerification) fall back to the existing value as a safety
                // net so they can never be cleared to null.
                val newPriority =
                    patchDto.priority?.let { pStr ->
                        Priority.entries.find { it.name.equals(pStr, ignoreCase = true) }
                            ?: return payloadRejection("Invalid priority: $pStr")
                    } ?: existing.priority

                // parentId: patchDto.parentId is the FINAL parent (null = move to root). Depth is
                // server-owned and, when the parent actually changes, ItemCommandService resolves it
                // from the new parent row read inside the write unit (AR-19).
                val newParentId =
                    patchDto.parentId?.let { pid ->
                        runCatchingNonCancellation { UUID.fromString(pid) }.getOrNull()
                            ?: return payloadRejection("Invalid parentId UUID: $pid")
                    }
                val parentChanged = newParentId != existing.parentId

                // Authorization checks that do not need to be co-transactional with the placement
                // read: existence and scope. The self-parent, ancestor-cycle and closed-parent rules run
                // inside the write unit (ItemCommandService); a parent that vanishes before it runs is
                // surfaced as the same not_found error.
                if (parentChanged) {
                    if (newParentId == null) {
                        // Move to root — the item becomes its own root. After the move its chain is
                        // just {id}, so a rootIds-restricted principal stays in scope iff id itself is
                        // one of the listed roots (not an escape when it is: such a principal may
                        // already DELETE the item). The tag half was enforced above via
                        // enforceScopeForItem(call, id, ...) on the item's pre-patch tags.
                        if (!principal.mayHoldRoot(id)) {
                            return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied to move item $id to root")
                        }
                    } else {
                        val parentResult =
                            legacyRead(
                                { return LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED) }
                            ) { workItemRepo.getById(newParentId) }
                        if (parentResult == null) {
                            return LegacyRestErrorMapper.captured(
                                LegacyRestCode.NOT_FOUND_AS_BAD_REQUEST,
                                "Parent item $newParentId not found"
                            )
                        }
                        // A re-parent target is the same authorization object as a create-time parent,
                        // so it gets the same check the POST /items path applies — otherwise PATCH is a
                        // way to move items under a parent the caller is not scoped to. Ordered after
                        // the existence check so a bogus UUID still reports not_found, not 403.
                        if (!enforceScopeForItem(call, newParentId, workItemRepo)) {
                            return LegacyRestErrorMapper.captured(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for parent $newParentId")
                        }
                    }
                }

                val command =
                    ItemPatchCommand(
                        itemId = id,
                        // The row the If-Match matched: another writer that commits in between loses this
                        // patch the version race (409 version_conflict), exactly as the store's own check did.
                        expectedVersion = existing.version,
                        parent =
                            when {
                                !parentChanged -> ParentChange.Keep
                                newParentId == null -> ParentChange.MoveToRoot
                                else -> ParentChange.MoveUnder(newParentId)
                            },
                        title = patchDto.title ?: existing.title,
                        description = patchDto.description,
                        summary = patchDto.summary ?: existing.summary,
                        statusLabel = patchDto.statusLabel,
                        priority = newPriority,
                        complexity = patchDto.complexity,
                        requiresVerification = patchDto.requiresVerification ?: existing.requiresVerification,
                        metadata = patchDto.metadata,
                        tags = patchDto.tags,
                        type = patchDto.type,
                        properties = patchDto.properties,
                    )

                // One unit: the guards (self-parent, own-descendant, closed parent), the placement read,
                // the item's own row, the one-statement descendant restamp and the old parent's cascade
                // re-evaluation commit together or not at all.
                val outcome =
                    try {
                        withEventActor(actorClaim) { itemCommandService.patch(command) }
                    } catch (e: PerRootConfigUnavailableException) {
                        return itemConfigUnavailableCaptured("PATCH /items/$id", e)
                    }

                return when (outcome) {
                    is Outcome.Ok ->
                        CachedHttpResponse(
                            statusCode = HttpStatusCode.OK.value,
                            bodyJson = writeJson.encodeToString(ItemDto.serializer(), outcome.value.item.toDto()),
                            etag = etagFor(outcome.value.item.modifiedAt),
                        )
                    is Outcome.Err -> {
                        val error = outcome.error
                        when {
                            error.code == ErrorCode.NOT_FOUND && ItemCommandErrors.notFoundId(error) != id.toString() ->
                                LegacyRestErrorMapper.captured(
                                    LegacyRestCode.NOT_FOUND_AS_BAD_REQUEST,
                                    "Parent item $newParentId not found"
                                )
                            // Optimistic-lock loss is a distinct, retryable condition from a genuine DB failure, and
                            // distinct from an If-Match precondition failure (412 above): If-Match matched, but
                            // another writer won the version race in between. 409 so a client retries with a fresh
                            // GET + If-Match.
                            LegacyFaults.isVersionConflict(error) -> {
                                writeLogger.debug("PATCH /items/{} optimistic-lock conflict: {}", id, error.message)
                                LegacyRestErrorMapper.captured(
                                    LegacyRestCode.VERSION_CONFLICT,
                                    "Item was modified by another request; retry with a fresh If-Match ETag"
                                )
                            }
                            ItemCommandErrors.isSelfParent(error) ->
                                LegacyRestErrorMapper.captured(LegacyRestCode.VALIDATION_ERROR, "An item cannot be its own parent")
                            error.code == ErrorCode.CYCLE_DETECTED ->
                                LegacyRestErrorMapper.captured(
                                    LegacyRestCode.VALIDATION_ERROR,
                                    "Cannot re-parent an item under its own descendant"
                                )
                            ItemCommandErrors.isClosedParent(error) ->
                                closedParentCaptured(
                                    closedParentId(error) ?: newParentId,
                                    "Parent item ${closedParentId(error) ?: newParentId} is terminal under auto lifecycle; " +
                                        "reopen it before moving items under it",
                                )
                            error.code == ErrorCode.INVALID_REQUEST ->
                                LegacyRestErrorMapper.captured(LegacyRestCode.VALIDATION_ERROR, error.message)
                            else -> {
                                writeLogger.warn("PATCH /items/{} DB error: {}", id, error.message)
                                LegacyRestErrorMapper.captured(LegacyRestCode.DB_ERROR, "Failed to update item")
                            }
                        }
                    }
                }
            }

            call.runWithIdempotency(idempotency, trustedActorId, idempotencyKeyResult, "/items/{id}", bodyText) { executePatch() }
        }
    }

    // ─── DELETE /items/{id} ──────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        delete("/items/{id}") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Missing item id")
                    return@delete
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respondError(LegacyRestCode.BAD_REQUEST, "Invalid UUID: $rawId")
                    return@delete
                }

            // `recursive` is trimmed and case-insensitive. Absent means non-recursive (the default,
            // preserving the previous single-row behavior); "true"/"false" (any case) are the only
            // other accepted values — anything else (e.g. "1", "yes", "") is a validation error.
            // Checked here (before the existence/scope/If-Match checks below) per the REST contract's
            // check order: id -> recursive -> 404 -> 403 -> If-Match 412.
            val recursiveParam = call.request.queryParameters["recursive"]?.trim()
            val recursive =
                when {
                    recursiveParam == null -> false
                    recursiveParam.equals("false", ignoreCase = true) -> false
                    recursiveParam.equals("true", ignoreCase = true) -> true
                    else -> {
                        call.respondError(LegacyRestCode.VALIDATION_ERROR, "recursive must be 'true' or 'false', got: $recursiveParam")
                        return@delete
                    }
                }

            val itemResult =
                legacyRead({
                    call.respondError(LegacyRestCode.DB_ERROR, DB_QUERY_FAILED)
                    return@delete
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respondError(LegacyRestCode.NOT_FOUND, "Item $id not found")
                return@delete
            }
            val existing = itemResult

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respondError(LegacyRestCode.SCOPE_FORBIDDEN, "Access denied for item $id")
                return@delete
            }

            // Optional If-Match for conditional delete
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
            if (ifMatch != null && ifMatch != etagFor(existing.modifiedAt)) {
                val currentEtag = etagFor(existing.modifiedAt)
                call.response.header(HttpHeaders.ETag, currentEtag)
                call.respondError(LegacyRestCode.ETAG_MISMATCH, "ETag mismatch; current ETag is $currentEtag")
                return@delete
            }

            // The shared delete (ItemCommandService.delete): release-before-delete, the non-recursive
            // children guard, the recursive all-or-nothing subtree delete and the old parent's cascade
            // re-evaluation are identical to the MCP `manage_items` delete operation (DeleteItemHandler).
            val actorClaim = ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey])
            when (val outcome = withEventActor(actorClaim) { itemCommandService.delete(id, recursive) }) {
                is Outcome.Ok -> {
                    val result = outcome.value
                    if (recursive) {
                        call.respond(
                            HttpStatusCode.OK,
                            ItemDeleteResultDto(
                                id = result.id.toString(),
                                deleted = 1 + result.descendantsDeleted,
                                descendantsDeleted = result.descendantsDeleted,
                            ),
                        )
                    } else {
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
                is Outcome.Err -> {
                    val error = outcome.error
                    val childCount = ItemCommandErrors.childCount(error)
                    when {
                        childCount != null -> {
                            val details =
                                buildJsonObject {
                                    put("childCount", JsonPrimitive(childCount))
                                }
                            call.respondError(
                                LegacyRestCode.HAS_CHILDREN,
                                "Item $id has $childCount child item(s). " +
                                    "Use ?recursive=true to delete the item and all its descendants.",
                                details
                            )
                        }
                        error.code == ErrorCode.NOT_FOUND ->
                            call.respondError(LegacyRestCode.NOT_FOUND, "Item $id not found")
                        else -> {
                            writeLogger.warn("DELETE /items/{} DB error: {}", id, error.message)
                            call.respondError(LegacyRestCode.DB_ERROR, "Failed to delete item")
                        }
                    }
                }
            }
        }
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
