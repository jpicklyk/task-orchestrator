package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
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
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.allowsItemTags
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.mayHoldRoot
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
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
import kotlinx.serialization.json.JsonArray
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
 * [JSON_WRITE_CONTENT_TYPES]) and returns true; returns false (no response sent) otherwise. Used by
 * `POST /items`.
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

/** 422 `config_invalid` for a config head that cannot be loaded (fail closed) while resolving an item write. */
private fun itemConfigInvalidCaptured(
    route: String,
    e: PerRootConfigInvalidException,
): CachedHttpResponse {
    writeLogger.warn("{}: config invalid: {}", route, e.message)
    return LegacyRestErrorMapper.captured(LegacyRestCode.CONFIG_INVALID, e.message)
}

/**
 * 400 `validation_error` for a type rule rejection (missing, cleared or undeclared `type`): `details` carries the
 * `reason`, the offending `type` (null when absent) and, when the root declares any, the sorted `availableTypes`
 * (the same status and code `NoteWriteRoutes` answers a `schema_violation` with).
 */
private fun typeViolationCaptured(error: DomainError): CachedHttpResponse {
    val violation = error.detail as? ErrorDetail.SchemaViolation
    val available = ItemCommandErrors.availableTypes(error)?.takeIf { it.isNotEmpty() }
    val availableTraits = ItemCommandErrors.availableTraits(error)?.takeIf { it.isNotEmpty() }
    return LegacyRestErrorMapper.captured(
        LegacyRestCode.VALIDATION_ERROR,
        error.message,
        buildJsonObject {
            put("reason", JsonPrimitive(violation?.reason ?: error.message))
            put("type", JsonPrimitive(violation?.type))
            if (available != null) put("availableTypes", JsonArray(available.map { JsonPrimitive(it) }))
            if (availableTraits != null) put("availableTraits", JsonArray(availableTraits.map { JsonPrimitive(it) }))
        },
    )
}

/** The parent id an `invalid_transition` (closed-parent) error names. */
private fun closedParentId(error: DomainError): UUID? =
    (error.detail as? io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail.InvalidTransition)?.itemId

/**
 * Registers item-write routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `POST   /items`              — create item ([ApiCapability.WRITE_ITEMS])
 * - `PATCH  /items/{id}`         — JSON Merge Patch update; requires `If-Match` ([ApiCapability.WRITE_ITEMS])
 * - `DELETE /items/{id}`         — delete; a parent refuses with 409 `has_children` unless
 *   `?recursive=true`, which cascades (matching the MCP `manage_items` delete operation's
 *   semantics via [ItemCommandService.delete]) ([ApiCapability.WRITE_ITEMS])
 *
 * **Audit:** every write synthesizes an [ActorClaim] server-side from the [ApiPrincipal];
 * client `actor.*` body fields are silently dropped.
 *
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on POST and PATCH.
 * **ETag concurrency:** PATCH requires `If-Match`; DELETE accepts optional `If-Match`.
 * **degradedModePolicy:** `reject` policy + verification failure → 401.
 *
 * @param itemCommandService the owner of item creates, patches and deletes — the SAME instance the MCP
 *   `manage_items` tool uses (`ToolExecutionContext.itemCommandService`), so placement, the closed-parent rule,
 *   the old-parent cascade and the item events are identical on both surfaces.
 */
fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotency: IdempotencyService,
    unitOfWork: UnitOfWork,
    itemCommandService: ItemCommandService,
    clock: Clock = Clock.SYSTEM,
) {
    val workItemRepo = repositoryProvider.workItemRepository()

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
                        traits = dto.traits.orEmpty(),
                    )

                // The item is always created in QUEUE; its depth/rootId come from the parent row read
                // INSIDE the write unit, and a TERMINAL parent under auto lifecycle rejects the create.
                val outcome =
                    try {
                        withEventActor(actorClaim) { itemCommandService.create(command) }
                    } catch (e: PerRootConfigUnavailableException) {
                        return itemConfigUnavailableCaptured("POST /items", e)
                    } catch (e: PerRootConfigInvalidException) {
                        return itemConfigInvalidCaptured("POST /items", e)
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
                            ItemCommandErrors.isTypeViolation(error) -> typeViolationCaptured(error)
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
                        traits = patchDto.traits.orEmpty(),
                    )

                // One unit: the guards (self-parent, own-descendant, closed parent), the placement read,
                // the item's own row, the one-statement descendant restamp and the old parent's cascade
                // re-evaluation commit together or not at all.
                val outcome =
                    try {
                        withEventActor(actorClaim) { itemCommandService.patch(command) }
                    } catch (e: PerRootConfigUnavailableException) {
                        return itemConfigUnavailableCaptured("PATCH /items/$id", e)
                    } catch (e: PerRootConfigInvalidException) {
                        return itemConfigInvalidCaptured("PATCH /items/$id", e)
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
                            ItemCommandErrors.isTypeViolation(error) -> typeViolationCaptured(error)
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
}
