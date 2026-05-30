package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.RoleTransitionHandler
import io.github.jpicklyk.mcptask.current.application.service.rest.MergePatchApplier
import io.github.jpicklyk.mcptask.current.application.service.rest.WorkItemPatchProjection
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.UserTrigger
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceRequestDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.AdvanceResponseDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemCreateDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ItemPatchDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val writeLogger = LoggerFactory.getLogger("ItemWriteRoutes")

// Field allowlist for PATCH — server-owned fields that must not be patchable
private val REJECTED_PATCH_FIELDS = setOf(
    "id", "role", "previousRole", "roleChangedAt", "depth",
    "createdAt", "modifiedAt", "version",
    "claimedBy", "claimedAt", "claimExpiresAt", "originalClaimedAt",
)

private val MERGE_PATCH_CONTENT_TYPES = setOf("application/merge-patch+json", "application/json")

private val warnOnClaimedAdvance: Boolean
    get() = System.getenv("API_WARN_ON_CLAIMED_ADVANCE")?.lowercase() != "false"

/**
 * Sealed result for idempotency key parsing.
 * - [Absent]: no `Idempotency-Key` header present
 * - [Present]: valid UUID parsed
 * - [Invalid]: malformed UUID (caller has already responded 400)
 */
private sealed class IdempotencyKeyResult {
    object Absent : IdempotencyKeyResult()
    data class Present(val key: UUID) : IdempotencyKeyResult()
    object Invalid : IdempotencyKeyResult()
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
 */
fun Route.itemWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotencyCache: IdempotencyCache,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val roleTransitionRepo = repositoryProvider.roleTransitionRepository()
    val depRepo = repositoryProvider.dependencyRepository()

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

            suspend fun executeCreate() {
                val dto =
                    try {
                        call.receive<ItemCreateDto>()
                    } catch (e: SerializationException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Invalid request body"))
                        return
                    }

                val parentId =
                    dto.parentId?.let { pid ->
                        runCatching { UUID.fromString(pid) }.getOrNull() ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid parentId UUID: $pid"))
                            return
                        }
                    }

                val depth: Int
                if (parentId != null) {
                    val parentResult = workItemRepo.getById(parentId)
                    if (parentResult is Result.Error) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("not_found", "Parent item $parentId not found"))
                        return
                    }
                    if (!enforceScopeForItem(call, parentId, workItemRepo)) {
                        call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for parent $parentId"))
                        return
                    }
                    depth = (parentResult as Result.Success).data.depth + 1
                } else {
                    depth = 0
                }

                val priority =
                    dto.priority?.let { pStr ->
                        Priority.entries.find { it.name.equals(pStr, ignoreCase = true) } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid priority: $pStr"))
                            return
                        }
                    } ?: Priority.MEDIUM

                val propertiesStr = dto.properties?.toString()
                val tagsStr = dto.tags?.joinToString(",")?.takeIf { it.isNotBlank() }

                val item =
                    try {
                        WorkItem(
                            title = dto.title,
                            description = dto.description,
                            summary = dto.summary ?: "",
                            parentId = parentId,
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
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Validation failed"))
                        return
                    }

                when (val result = workItemRepo.create(item)) {
                    is Result.Error -> {
                        writeLogger.warn("POST /items DB error: {}", result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to create item"))
                    }
                    is Result.Success -> {
                        call.response.header(HttpHeaders.ETag, etagFor(result.data.modifiedAt))
                        call.respond(HttpStatusCode.Created, result.data.toDto())
                    }
                }
            }

            when (idempotencyKeyResult) {
                is IdempotencyKeyResult.Present ->
                    idempotencyCache.getOrCompute(trustedActorId, idempotencyKeyResult.key) {
                        runBlocking { executeCreate() }
                    }
                else -> executeCreate()
            }
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
            val contentType = call.request.contentType().withoutParameters().toString()
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

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@patch
            }
            val existing = (itemResult as Result.Success).data

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@patch
            }

            // ETag concurrency — If-Match required for PATCH
            val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
            val currentEtag = etagFor(existing.modifiedAt)
            if (ifMatch == null) {
                call.response.header(HttpHeaders.ETag, currentEtag)
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorDto("precondition_required", "PATCH requires If-Match header with current ETag"),
                )
                return@patch
            }
            if (ifMatch != currentEtag) {
                call.response.header(HttpHeaders.ETag, currentEtag)
                call.respond(
                    HttpStatusCode.PreconditionFailed,
                    ErrorDto("etag_mismatch", "ETag mismatch; current ETag is $currentEtag"),
                )
                return@patch
            }

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@patch

            suspend fun executePatch() {
                val bodyText = call.receiveText()
                val patchObject =
                    try {
                        Json.parseToJsonElement(bodyText) as? JsonObject ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "PATCH body must be a JSON object"))
                            return
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid JSON: ${e.message}"))
                        return
                    }

                // Security: reject any attempt to patch server-owned fields
                val disallowedFields = patchObject.keys.intersect(REJECTED_PATCH_FIELDS)
                if (disallowedFields.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto(
                            "field_not_patchable",
                            "The following fields cannot be patched: ${disallowedFields.joinToString()}",
                            details = buildJsonObject { put("rejectedFields", disallowedFields.joinToString()) },
                        ),
                    )
                    return
                }

                // Merge-patch flow: project existing → apply patch → normalize → decode → update
                val base = WorkItemPatchProjection.toJsonObject(existing)
                val merged = MergePatchApplier.apply(base, patchObject) as JsonObject

                // Normalize: `properties` in the merged object may be a JsonObject (from
                // recursive merge). ItemPatchDto.properties is String? — re-serialize it.
                val normalizedMerged: JsonObject = run {
                    val propsElement = merged["properties"]
                    if (propsElement != null && propsElement is JsonObject) {
                        JsonObject(merged.toMutableMap().also { map ->
                            map["properties"] = JsonPrimitive(propsElement.toString())
                        })
                    } else {
                        merged
                    }
                }

                val patchDto =
                    try {
                        Json.decodeFromJsonElement<ItemPatchDto>(normalizedMerged)
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid patch values: ${e.message}"))
                        return
                    }

                val newPriority =
                    patchDto.priority?.let { pStr ->
                        Priority.entries.find { it.name.equals(pStr, ignoreCase = true) } ?: run {
                            call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid priority: $pStr"))
                            return
                        }
                    }

                // parentId: null in normalizedMerged = move to root (server-owned depth recalculated)
                val newParentId =
                    if (normalizedMerged.containsKey("parentId")) {
                        patchDto.parentId?.let { pid ->
                            runCatching { UUID.fromString(pid) }.getOrNull() ?: run {
                                call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Invalid parentId UUID: $pid"))
                                return
                            }
                        }
                    } else {
                        existing.parentId
                    }

                val newDepth =
                    if (normalizedMerged.containsKey("parentId")) {
                        if (newParentId == null) {
                            0
                        } else {
                            val parentResult = workItemRepo.getById(newParentId)
                            if (parentResult is Result.Error) {
                                call.respond(HttpStatusCode.BadRequest, ErrorDto("not_found", "Parent item $newParentId not found"))
                                return
                            }
                            (parentResult as Result.Success).data.depth + 1
                        }
                    } else {
                        existing.depth
                    }

                // properties: special handling per spec §5.4.1
                // The MergePatchApplier already handled properties sub-merging in the base→patch apply.
                // After decoding normalizedMerged to ItemPatchDto, properties is a re-serialized string.
                val newProperties =
                    if (normalizedMerged.containsKey("properties")) patchDto.properties else existing.properties

                val updated =
                    try {
                        existing.update { item ->
                            item.copy(
                                title = patchDto.title ?: item.title,
                                description = if (normalizedMerged.containsKey("description")) patchDto.description else item.description,
                                summary = patchDto.summary ?: item.summary,
                                statusLabel = if (normalizedMerged.containsKey("statusLabel")) patchDto.statusLabel else item.statusLabel,
                                priority = newPriority ?: item.priority,
                                complexity = if (normalizedMerged.containsKey("complexity")) patchDto.complexity else item.complexity,
                                requiresVerification = patchDto.requiresVerification ?: item.requiresVerification,
                                tags = if (normalizedMerged.containsKey("tags")) patchDto.tags else item.tags,
                                type = if (normalizedMerged.containsKey("type")) patchDto.type else item.type,
                                properties = newProperties,
                                metadata = if (normalizedMerged.containsKey("metadata")) patchDto.metadata else item.metadata,
                                parentId = newParentId,
                                depth = newDepth,
                            )
                        }
                    } catch (e: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Validation failed"))
                        return
                    }

                when (val result = workItemRepo.update(updated)) {
                    is Result.Error -> {
                        writeLogger.warn("PATCH /items/{} DB error: {}", id, result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to update item"))
                    }
                    is Result.Success -> {
                        call.response.header(HttpHeaders.ETag, etagFor(result.data.modifiedAt))
                        call.respond(HttpStatusCode.OK, result.data.toDto())
                    }
                }
            }

            when (idempotencyKeyResult) {
                is IdempotencyKeyResult.Present ->
                    idempotencyCache.getOrCompute(trustedActorId, idempotencyKeyResult.key) {
                        runBlocking { executePatch() }
                    }
                else -> executePatch()
            }
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

            val userTrigger = UserTrigger.fromString(advanceDto.trigger) ?: run {
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

            val handler = RoleTransitionHandler()

            // Simple hasReviewPhase=false for REST API path — full schema-aware logic via MCP
            val transitionResult =
                handler.userTransition(
                    item = item,
                    trigger = userTrigger,
                    summary = null,
                    statusLabel = null,
                    hasReviewPhase = false,
                    workItemRepository = workItemRepo,
                    roleTransitionRepository = roleTransitionRepo,
                    dependencyRepository = depRepo,
                    actorClaim = actorClaim,
                    verification = verification,
                    degradedModePolicy = degradedModePolicy,
                )

            if (!transitionResult.success) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    ErrorDto("transition_failed", transitionResult.error ?: "Transition failed"),
                )
                return@post
            }

            val newItem = transitionResult.item!!
            // Response body MUST NOT disclose claimedBy (tiered-disclosure principle)
            call.respond(
                HttpStatusCode.OK,
                AdvanceResponseDto(
                    itemId = id.toString(),
                    previousRole = transitionResult.previousRole!!.name.lowercase(),
                    newRole = transitionResult.newRole!!.name.lowercase(),
                    trigger = advanceDto.trigger,
                    statusLabel = newItem.statusLabel,
                ),
            )
        }
    }
}

/**
 * Parses the `Idempotency-Key` header from the request.
 *
 * Returns:
 * - [IdempotencyKeyResult.Absent] when no header is present
 * - [IdempotencyKeyResult.Present] when the header contains a valid UUID
 * - [IdempotencyKeyResult.Invalid] when the header is present but malformed (400 already sent)
 */
private suspend fun ApplicationCall.parseIdempotencyKey(): IdempotencyKeyResult {
    val keyHeader = request.headers["Idempotency-Key"] ?: return IdempotencyKeyResult.Absent
    return try {
        IdempotencyKeyResult.Present(UUID.fromString(keyHeader.trim()))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Idempotency-Key must be a valid UUID"))
        IdempotencyKeyResult.Invalid
    }
}
