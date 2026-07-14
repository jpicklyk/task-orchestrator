package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.AdvanceService
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.ItemHierarchyValidator
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.service.rest.MergePatchApplier
import io.github.jpicklyk.mcptask.current.application.service.rest.WorkItemPatchProjection
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.UserTrigger
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceRequestDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemCreateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemPatchDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
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

/** Builds a [CachedHttpResponse] from an [ErrorDto] at [status]. */
private fun errorCaptured(
    status: HttpStatusCode,
    error: String,
    message: String,
): CachedHttpResponse =
    CachedHttpResponse(
        statusCode = status.value,
        bodyJson = writeJson.encodeToString(ErrorDto.serializer(), ErrorDto(error, message)),
    )

/**
 * Maps a structured [AdvanceFailure] from [AdvanceService] to an HTTP error response.
 *
 * - [AdvanceFailure.GateBlocked] → **422** `gate_blocked`, with the structured missing required
 *   notes in `details.missingNotes`. This is the key behavioral change from unification: a REST
 *   advance that fails a required-note gate is now REJECTED instead of silently advancing.
 * - [AdvanceFailure.ValidationFailed] → **422** `transition_blocked`, with the dependency blockers.
 * - [AdvanceFailure.ResolutionFailed] / [AdvanceFailure.ApplyFailed] → **422** `transition_failed`.
 * - [AdvanceFailure.PolicyRejected] → **401** `verification_failed` (defensive — ownership is not
 *   enforced on the REST path, so this is not expected to occur here).
 * - [AdvanceFailure.OwnershipRejected] → **409** `not_claim_holder` (defensive — same caveat).
 */
private suspend fun respondAdvanceFailure(
    call: io.ktor.server.application.ApplicationCall,
    failure: AdvanceFailure,
) {
    when (failure) {
        is AdvanceFailure.GateBlocked -> {
            val details =
                buildJsonObject {
                    put("targetRole", JsonPrimitive(failure.targetRole.name.lowercase()))
                    put(
                        "missingNotes",
                        kotlinx.serialization.json.buildJsonArray {
                            failure.missingNotes.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("key", JsonPrimitive(entry.key))
                                        put("description", JsonPrimitive(entry.description))
                                        entry.guidance?.let { put("guidance", JsonPrimitive(it)) }
                                        entry.skill?.let { put("skill", JsonPrimitive(it)) }
                                    },
                                )
                            }
                        },
                    )
                }
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto("gate_blocked", failure.message, details),
            )
        }
        is AdvanceFailure.ValidationFailed -> {
            val details =
                buildJsonObject {
                    put(
                        "blockers",
                        kotlinx.serialization.json.buildJsonArray {
                            failure.blockers.forEach { blocker ->
                                add(
                                    buildJsonObject {
                                        put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                                        put("currentRole", JsonPrimitive(blocker.currentRole.name.lowercase()))
                                        put("requiredRole", JsonPrimitive(blocker.requiredRole))
                                    },
                                )
                            }
                        },
                    )
                }
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                ErrorDto("transition_blocked", failure.message, details),
            )
        }
        is AdvanceFailure.ResolutionFailed ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("transition_failed", failure.message))
        is AdvanceFailure.ApplyFailed ->
            call.respond(HttpStatusCode.UnprocessableEntity, ErrorDto("transition_failed", failure.message))
        is AdvanceFailure.PolicyRejected ->
            call.respond(HttpStatusCode.Unauthorized, ErrorDto("verification_failed", failure.reason))
        is AdvanceFailure.OwnershipRejected ->
            call.respond(HttpStatusCode.Conflict, ErrorDto("not_claim_holder", failure.message))
    }
}

/**
 * Registers item-write and advance routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `POST   /items`              — create item ([ApiCapability.WRITE_ITEMS])
 * - `PATCH  /items/{id}`         — JSON Merge Patch update; requires `If-Match` ([ApiCapability.WRITE_ITEMS])
 * - `DELETE /items/{id}`         — cascade delete ([ApiCapability.WRITE_ITEMS])
 * - `POST   /items/{id}/advance` — role transition ([ApiCapability.ADVANCE])
 *
 * **Audit:** every write synthesizes an [ActorClaim] server-side from the [ApiPrincipal];
 * client `actor.*` body fields are silently dropped.
 *
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on POST and PATCH.
 * **ETag concurrency:** PATCH requires `If-Match`; DELETE accepts optional `If-Match`.
 * **degradedModePolicy:** `reject` policy + verification failure → 401.
 *
 * @param schemaService used to resolve the item's actual `hasReviewPhase` on advance, mirroring
 *   [io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool] (trait-merged).
 */
fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotencyCache: IdempotencyCache,
    schemaService: WorkItemSchemaService,
    warnOnClaimedAdvance: Boolean = defaultWarnOnClaimedAdvance,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val roleTransitionRepo = repositoryProvider.roleTransitionRepository()
    val depRepo = repositoryProvider.dependencyRepository()
    val hierarchyValidator = ItemHierarchyValidator()

    // Schema-resolution context — reuses the EXACT trait-merging + review-phase logic from
    // AdvanceItemTool (via ToolExecutionContext.resolveHasReviewPhase). Repository access is shared;
    // no MCP behavior is affected since this context is read-only for schema resolution here.
    val schemaResolutionContext = ToolExecutionContext(repositoryProvider, schemaService)

    // ─── POST /items ─────────────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        post("/items") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@post
                }

            val trustedActorId =
                ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                    )
                    return@post
                }

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@post

            // Produce a CachedHttpResponse so the body is serialized once and replayed verbatim on
            // an Idempotency-Key hit (the DB write runs at most once — see runWithIdempotency).
            suspend fun executeCreate(): CachedHttpResponse {
                val dto =
                    try {
                        call.receive<ItemCreateDto>()
                    } catch (e: SerializationException) {
                        return errorCaptured(HttpStatusCode.BadRequest, "validation_error", e.message ?: "Invalid request body")
                    }

                val parentId =
                    dto.parentId?.let { pid ->
                        runCatching { UUID.fromString(pid) }.getOrNull()
                            ?: return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid parentId UUID: $pid")
                    }

                // Pre-generate the id so a root-level create (parentId == null) can stamp
                // rootId = own id without a second round trip.
                val itemId = UUID.randomUUID()

                val depth: Int
                val rootId: UUID
                if (parentId != null) {
                    val parentResult = workItemRepo.getById(parentId)
                    if (parentResult is Result.Error) {
                        return errorCaptured(HttpStatusCode.BadRequest, "not_found", "Parent item $parentId not found")
                    }
                    if (!enforceScopeForItem(call, parentId, workItemRepo)) {
                        return errorCaptured(HttpStatusCode.Forbidden, "scope_forbidden", "Access denied for parent $parentId")
                    }
                    val parentData = (parentResult as Result.Success).data
                    depth = parentData.depth + 1
                    // Inherit the parent's root (or the parent's own id, if the parent predates
                    // the root_id backfill and has no rootId yet).
                    rootId = parentData.rootId ?: parentData.id
                } else {
                    depth = 0
                    rootId = itemId
                }

                val priority =
                    dto.priority?.let { pStr ->
                        Priority.entries.find { it.name.equals(pStr, ignoreCase = true) }
                            ?: return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid priority: $pStr")
                    } ?: Priority.MEDIUM

                val propertiesStr = dto.properties?.toString()
                val tagsStr = dto.tags?.joinToString(",")?.takeIf { it.isNotBlank() }

                val item =
                    try {
                        WorkItem(
                            id = itemId,
                            title = dto.title,
                            description = dto.description,
                            summary = dto.summary ?: "",
                            parentId = parentId,
                            rootId = rootId,
                            depth = depth,
                            type = dto.type,
                            priority = priority,
                            complexity = dto.complexity,
                            requiresVerification = dto.requiresVerification ?: false,
                            tags = tagsStr,
                            statusLabel = dto.statusLabel,
                            properties = propertiesStr,
                            metadata = dto.metadata,
                        )
                    } catch (e: Exception) {
                        return errorCaptured(HttpStatusCode.BadRequest, "validation_error", e.message ?: "Validation failed")
                    }

                return when (val result = workItemRepo.create(item)) {
                    is Result.Error -> {
                        writeLogger.warn("POST /items DB error: {}", result.error.message)
                        errorCaptured(HttpStatusCode.InternalServerError, "db_error", "Failed to create item")
                    }
                    is Result.Success ->
                        CachedHttpResponse(
                            statusCode = HttpStatusCode.Created.value,
                            bodyJson = writeJson.encodeToString(ItemDto.serializer(), result.data.toDto()),
                            etag = etagFor(result.data.modifiedAt),
                        )
                }
            }

            call.runWithIdempotency(idempotencyCache, trustedActorId, idempotencyKeyResult) { executeCreate() }
        }
    }

    // ─── PATCH /items/{id} ───────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        patch("/items/{id}") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@patch
                }

            val trustedActorId =
                ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                    )
                    return@patch
                }

            // Content-Type check — accept merge-patch+json and application/json
            val contentType =
                call.request
                    .contentType()
                    .withoutParameters()
                    .toString()
            if (contentType !in MERGE_PATCH_CONTENT_TYPES) {
                call.response.header("Accept-Patch", "application/merge-patch+json, application/json")
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorDto("unsupported_media_type", "Use Content-Type: application/merge-patch+json or application/json"),
                )
                return@patch
            }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@patch
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@patch
                }

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@patch

            // The state-dependent pre-conditions (existence, scope, If-Match ETag) AND the write run
            // INSIDE the captured block, so an Idempotency-Key replay returns the cached response
            // verbatim WITHOUT re-evaluating the ETag against the now-mutated item (which would
            // otherwise spuriously 412). The Content-Type 415 check above is request-shape-only and
            // stays outside (a replay carries the same Content-Type).
            suspend fun executePatch(): CachedHttpResponse {
                val itemResult = workItemRepo.getById(id)
                if (itemResult is Result.Error) {
                    return errorCaptured(HttpStatusCode.NotFound, "not_found", "Item $id not found")
                }
                val existing = (itemResult as Result.Success).data

                if (!enforceScopeForItem(call, id, workItemRepo)) {
                    return errorCaptured(HttpStatusCode.Forbidden, "scope_forbidden", "Access denied for item $id")
                }

                // ETag concurrency — If-Match required for PATCH
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
                val currentEtag = etagFor(existing.modifiedAt)
                if (ifMatch == null) {
                    return CachedHttpResponse(
                        statusCode = HttpStatusCode.BadRequest.value,
                        bodyJson =
                            writeJson.encodeToString(
                                ErrorDto.serializer(),
                                ErrorDto("precondition_required", "PATCH requires If-Match header with current ETag"),
                            ),
                        etag = currentEtag,
                    )
                }
                if (ifMatch != currentEtag) {
                    return CachedHttpResponse(
                        statusCode = HttpStatusCode.PreconditionFailed.value,
                        bodyJson =
                            writeJson.encodeToString(
                                ErrorDto.serializer(),
                                ErrorDto("etag_mismatch", "ETag mismatch; current ETag is $currentEtag"),
                            ),
                        etag = currentEtag,
                    )
                }

                val bodyText = call.receiveText()
                val patchObject =
                    try {
                        Json.parseToJsonElement(bodyText) as? JsonObject
                            ?: return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "PATCH body must be a JSON object")
                    } catch (e: Exception) {
                        // Log the parse detail server-side; do not echo the raw exception message back
                        // to the client (avoids leaking parser internals / input fragments).
                        writeLogger.debug("PATCH body JSON parse failed: {}", e.message)
                        return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid JSON in request body")
                    }

                // Security: reject any attempt to patch server-owned fields
                val disallowedFields = patchObject.keys.intersect(REJECTED_PATCH_FIELDS)
                if (disallowedFields.isNotEmpty()) {
                    return errorCaptured(
                        HttpStatusCode.BadRequest,
                        "field_not_patchable",
                        "The following fields cannot be patched: ${disallowedFields.joinToString()}",
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
                        writeLogger.debug("PATCH patch-value decode failed: {}", e.message)
                        return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid patch values")
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
                            ?: return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid priority: $pStr")
                    } ?: existing.priority

                // parentId: patchDto.parentId is the FINAL parent (null = move to root). Depth is
                // server-owned: recompute only when the resolved parent actually changes.
                val newParentId =
                    patchDto.parentId?.let { pid ->
                        runCatching { UUID.fromString(pid) }.getOrNull()
                            ?: return errorCaptured(HttpStatusCode.BadRequest, "validation_error", "Invalid parentId UUID: $pid")
                    }

                val newDepth: Int
                val newRootId: UUID?
                if (newParentId == existing.parentId) {
                    newDepth = existing.depth
                    newRootId = existing.rootId
                } else if (newParentId == null) {
                    // Move to root — the item becomes its own root.
                    newDepth = 0
                    newRootId = id
                } else {
                    val parentResult = workItemRepo.getById(newParentId)
                    if (parentResult is Result.Error) {
                        return errorCaptured(HttpStatusCode.BadRequest, "not_found", "Parent item $newParentId not found")
                    }
                    val parentData = (parentResult as Result.Success).data
                    newDepth = parentData.depth + 1
                    // Inherit the new parent's root (or the parent's own id, if the parent
                    // predates the root_id backfill and has no rootId yet).
                    newRootId = parentData.rootId ?: parentData.id
                }

                val updated =
                    try {
                        existing.update { item ->
                            item.copy(
                                title = patchDto.title ?: item.title,
                                description = patchDto.description,
                                summary = patchDto.summary ?: item.summary,
                                statusLabel = patchDto.statusLabel,
                                priority = newPriority,
                                complexity = patchDto.complexity,
                                requiresVerification = patchDto.requiresVerification ?: item.requiresVerification,
                                tags = patchDto.tags,
                                type = patchDto.type,
                                properties = patchDto.properties,
                                metadata = patchDto.metadata,
                                parentId = newParentId,
                                rootId = newRootId,
                                depth = newDepth,
                            )
                        }
                    } catch (e: Exception) {
                        return errorCaptured(HttpStatusCode.BadRequest, "validation_error", e.message ?: "Validation failed")
                    }

                // When the parent actually changes, the item's own write and the
                // descendant-depth/rootId cascade must succeed or fail together — wrap both in a
                // shared transaction so a cascade failure (e.g. a version-mismatch conflict on a
                // descendant) rolls back the parent's own write too, rather than leaving the tree
                // half-updated. Gated on parentId change rather than depthDelta != 0: moving an
                // item between two different root subtrees at the same depth leaves depth
                // unchanged but still requires a rootId cascade over every descendant.
                val depthDelta = newDepth - existing.depth
                val parentChanged = newParentId != existing.parentId
                var updateResult: Result<WorkItem>? = null
                var cascadeErrorMessage: String? = null
                if (parentChanged) {
                    try {
                        workItemRepo.inTransaction {
                            val txResult = workItemRepo.update(updated)
                            updateResult = txResult
                            if (txResult is Result.Success) {
                                // newRootId is always non-null on this branch (see the if/else
                                // above — only the unchanged-parent branch, which implies
                                // parentChanged == false, can leave it null); `?: id` only
                                // satisfies the nullable type.
                                when (
                                    val cascadeResult =
                                        hierarchyValidator.recomputeDescendantDepths(id, depthDelta, newRootId ?: id, workItemRepo)
                                ) {
                                    is Result.Success -> {}
                                    is Result.Error -> {
                                        cascadeErrorMessage = cascadeResult.error.message
                                        throw DepthCascadeException(cascadeResult.error.message)
                                    }
                                }
                            }
                        }
                    } catch (e: DepthCascadeException) {
                        // Expected abort path — cascadeErrorMessage already holds the detail and the
                        // transaction has rolled back, so no partial writes remain.
                    }
                } else {
                    updateResult = workItemRepo.update(updated)
                }

                if (cascadeErrorMessage != null) {
                    writeLogger.warn("PATCH /items/{} descendant depth cascade failed: {}", id, cascadeErrorMessage)
                    return errorCaptured(HttpStatusCode.InternalServerError, "db_error", "Failed to update item")
                }

                return when (val result = updateResult!!) {
                    is Result.Error -> {
                        writeLogger.warn("PATCH /items/{} DB error: {}", id, result.error.message)
                        errorCaptured(HttpStatusCode.InternalServerError, "db_error", "Failed to update item")
                    }
                    is Result.Success ->
                        CachedHttpResponse(
                            statusCode = HttpStatusCode.OK.value,
                            bodyJson = writeJson.encodeToString(ItemDto.serializer(), result.data.toDto()),
                            etag = etagFor(result.data.modifiedAt),
                        )
                }
            }

            call.runWithIdempotency(idempotencyCache, trustedActorId, idempotencyKeyResult) { executePatch() }
        }
    }

    // ─── DELETE /items/{id} ──────────────────────────────────────────────────
    requireCapability(ApiCapability.WRITE_ITEMS) {
        delete("/items/{id}") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@delete
                }

            ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                )
                return@delete
            }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@delete
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@delete
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@delete
            }
            val existing = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@delete
            }

            // Optional If-Match for conditional delete
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
            if (ifMatch != null && ifMatch != etagFor(existing.modifiedAt)) {
                val currentEtag = etagFor(existing.modifiedAt)
                call.response.header(HttpHeaders.ETag, currentEtag)
                call.respond(
                    HttpStatusCode.PreconditionFailed,
                    ErrorDto("etag_mismatch", "ETag mismatch; current ETag is $currentEtag"),
                )
                return@delete
            }

            when (val result = workItemRepo.delete(id)) {
                is Result.Error -> {
                    writeLogger.warn("DELETE /items/{} DB error: {}", id, result.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to delete item"))
                }
                is Result.Success -> {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }

    // ─── POST /items/{id}/advance ────────────────────────────────────────────
    requireCapability(ApiCapability.ADVANCE) {
        post("/items/{id}/advance") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@post
                }

            val trustedActorId =
                ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                    )
                    return@post
                }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@post
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@post
                }

            val advanceDto =
                try {
                    call.receive<AdvanceRequestDto>()
                } catch (e: SerializationException) {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Invalid request body"))
                    return@post
                }

            val userTrigger =
                UserTrigger.fromString(advanceDto.trigger) ?: run {
                    val validTriggers = UserTrigger.entries.joinToString { it.triggerString }
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto("validation_error", "Invalid trigger '${advanceDto.trigger}'. Valid: $validTriggers"),
                    )
                    return@post
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@post
            }
            val item = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@post
            }

            // Claimed item: emit WARN but proceed — API callers override MCP claim semantics
            val dbNow = workItemRepo.dbNow()
            val isActivelyClaimed =
                item.claimedBy != null &&
                    item.claimExpiresAt != null &&
                    item.claimExpiresAt.isAfter(dbNow)

            if (isActivelyClaimed && warnOnClaimedAdvance) {
                writeLogger.warn(
                    "API_WARN_ON_CLAIMED_ADVANCE: API advance on claimed item; itemId={}, apiTokenId={}, trigger={}",
                    id,
                    principal.tokenId,
                    advanceDto.trigger,
                )
            }

            // Build the synthesized actor for the transition audit trail (server-side only)
            val actorClaim = ApiAuditBridge.toActorClaim(principal)
            val verification = ApiAuditBridge.toVerificationResult(principal)

            // Delegate to the shared AdvanceService — the SAME pipeline the MCP advance_item tool
            // uses (resolve → validate → required-note gate → apply → cascade → unblock). The schema
            // resolver mirrors AdvanceItemTool's trait-merged resolution. enforceOwnership = false:
            // the REST API bypasses claim ownership entirely (plan §2 — API callers are operators,
            // not fleet agents). The advance SUCCEEDS even when the item is claimed by a different
            // MCP agent, while the synthesized API actor is still recorded on the role_transitions
            // row for audit. Unlike the prior userTransition() path, the gate is now ENFORCED.
            val advanceService =
                AdvanceService(
                    workItemRepository = workItemRepo,
                    roleTransitionRepository = roleTransitionRepo,
                    dependencyRepository = depRepo,
                    noteRepository = repositoryProvider.noteRepository(),
                    statusLabelService = NoOpStatusLabelService,
                    schemaResolver = { schemaResolutionContext.resolveSchema(it) },
                )

            val outcome =
                advanceService.advance(
                    item = item,
                    trigger = userTrigger.triggerString,
                    summary = null,
                    actorClaim = actorClaim,
                    verification = verification,
                    degradedModePolicy = degradedModePolicy,
                    enforceOwnership = false,
                )

            val advanceResult =
                when (outcome) {
                    is AdvanceOutcome.Success -> outcome.result
                    is AdvanceOutcome.Failure -> {
                        respondAdvanceFailure(call, outcome.failure)
                        return@post
                    }
                }

            // Build expectedNotes parity: fetch the item's current note keys for the `exists` flag.
            val existingNoteKeys =
                when (val notesResult = repositoryProvider.noteRepository().findByItemId(id)) {
                    is Result.Success -> notesResult.data.map { it.key }.toSet()
                    is Result.Error -> emptySet()
                }

            // Response body MUST NOT disclose claimedBy (tiered-disclosure principle)
            call.respond(
                HttpStatusCode.OK,
                advanceResult.toDto(existingNoteKeys),
            )
        }
    }
}

/**
 * Internal marker exception used to abort the shared `workItemRepo.inTransaction` block in the
 * PATCH `/items/{id}` handler when the descendant-depth cascade fails after the item's own depth
 * write succeeded. Caught immediately around the `inTransaction` call and converted into a 500
 * response; never surfaced to the client as a raw exception.
 */
private class DepthCascadeException(
    message: String
) : Exception(message)
