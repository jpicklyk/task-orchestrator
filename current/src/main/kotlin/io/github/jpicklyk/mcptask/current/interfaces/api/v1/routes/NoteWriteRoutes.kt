package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.enforceScopeForItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.requireCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteWriteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

private val noteWriteLogger = LoggerFactory.getLogger("NoteWriteRoutes")

private val VALID_NOTE_ROLES = setOf("queue", "work", "review")

// JSON encoder for capturing serialized note responses (matches the server's explicitNulls=false).
private val noteWriteJson =
    Json {
        explicitNulls = false
        encodeDefaults = true
    }

private fun noteErrorCaptured(
    status: HttpStatusCode,
    error: String,
    message: String,
): CachedHttpResponse =
    CachedHttpResponse(
        statusCode = status.value,
        bodyJson = noteWriteJson.encodeToString(ErrorDto.serializer(), ErrorDto(error, message)),
    )

/**
 * Registers note-write routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `PUT    /items/{id}/notes/{key}` — upsert note ([ApiCapability.WRITE_NOTES])
 * - `DELETE /items/{id}/notes/{key}` — delete note ([ApiCapability.WRITE_NOTES])
 *
 * **Audit:** actor claim is synthesized server-side from [ApiPrincipal]; client `actor.*` is dropped.
 * **ETag:** `If-Match` is accepted on PUT (update path) but not required for create.
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on PUT.
 */
fun Route.noteWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotencyCache: IdempotencyCache,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val redactor = AttributionRedactor.fromEnv()

    requireCapability(ApiCapability.WRITE_NOTES) {
        // ─── PUT /items/{id}/notes/{key} (upsert) ───────────────────────────
        put("/items/{id}/notes/{key}") {
            val principal =
                call.attributes.getOrNull(ApiPrincipalKey) ?: run {
                    call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthenticated", "No authenticated principal"))
                    return@put
                }

            val trustedActorId =
                ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy) ?: run {
                    call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorDto("verification_failed", "Actor verification failed (degradedModePolicy=reject)"),
                    )
                    return@put
                }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@put
                }
            val id =
                runCatching { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@put
                }
            val key =
                call.parameters["key"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing note key"))
                    return@put
                }

            val idempotencyKeyResult = call.parseIdempotencyKey()
            if (idempotencyKeyResult is IdempotencyKeyResult.Invalid) return@put

            // The state-dependent pre-conditions (item existence, scope, note-existence, If-Match
            // ETag) AND the upsert run INSIDE the captured block, so an Idempotency-Key replay
            // returns the cached response verbatim without re-evaluating the now-mutated note's ETag.
            suspend fun executeUpsert(): CachedHttpResponse {
                val itemResult = workItemRepo.getById(id)
                if (itemResult is Result.Error) {
                    return noteErrorCaptured(HttpStatusCode.NotFound, "not_found", "Item $id not found")
                }

                if (!enforceScopeForItem(call, id, workItemRepo)) {
                    return noteErrorCaptured(HttpStatusCode.Forbidden, "scope_forbidden", "Access denied for item $id")
                }

                // Check for existing note to handle ETag and ID preservation
                val existingNote =
                    when (val findResult = noteRepo.findByItemIdAndKey(id, key)) {
                        is Result.Success -> findResult.data
                        is Result.Error -> null
                    }

                // If-Match for update path (optional but validated when present)
                val ifMatch = call.request.headers[HttpHeaders.IfMatch]?.trim()
                if (ifMatch != null && existingNote != null) {
                    val currentEtag = etagFor(existingNote.modifiedAt)
                    if (ifMatch != currentEtag) {
                        return CachedHttpResponse(
                            statusCode = HttpStatusCode.PreconditionFailed.value,
                            bodyJson =
                                noteWriteJson.encodeToString(
                                    ErrorDto.serializer(),
                                    ErrorDto("etag_mismatch", "Note ETag mismatch; current ETag is $currentEtag"),
                                ),
                            etag = currentEtag,
                        )
                    }
                }

                val dto =
                    try {
                        call.receive<NoteWriteDto>()
                    } catch (e: SerializationException) {
                        return noteErrorCaptured(HttpStatusCode.BadRequest, "validation_error", e.message ?: "Invalid request body")
                    }

                if (dto.role.lowercase() !in VALID_NOTE_ROLES) {
                    return noteErrorCaptured(HttpStatusCode.BadRequest, "validation_error", "role must be one of: queue, work, review")
                }

                // Synthesize actor server-side — client body actor.* fields are dropped
                val actorClaim = ApiAuditBridge.toActorClaim(principal)
                val verification = ApiAuditBridge.toVerificationResult(principal)

                val note =
                    try {
                        Note(
                            id = existingNote?.id ?: UUID.randomUUID(),
                            itemId = id,
                            key = key,
                            role = dto.role.lowercase(),
                            body = dto.body,
                            actorClaim = actorClaim,
                            verification = verification,
                        )
                    } catch (e: Exception) {
                        return noteErrorCaptured(HttpStatusCode.BadRequest, "validation_error", e.message ?: "Validation failed")
                    }

                return when (val result = noteRepo.upsert(note)) {
                    is Result.Error -> {
                        noteWriteLogger.warn("PUT /items/{}/notes/{} DB error: {}", id, key, result.error.message)
                        noteErrorCaptured(HttpStatusCode.InternalServerError, "db_error", "Failed to upsert note")
                    }
                    is Result.Success -> {
                        val isCreate = existingNote == null
                        val redactedDto = redactor.redact(result.data.toDto(), call)
                        CachedHttpResponse(
                            statusCode = if (isCreate) HttpStatusCode.Created.value else HttpStatusCode.OK.value,
                            bodyJson = noteWriteJson.encodeToString(NoteDto.serializer(), redactedDto),
                            etag = etagFor(result.data.modifiedAt),
                        )
                    }
                }
            }

            call.runWithIdempotency(idempotencyCache, trustedActorId, idempotencyKeyResult) { executeUpsert() }
        }

        // ─── DELETE /items/{id}/notes/{key} ─────────────────────────────────
        delete("/items/{id}/notes/{key}") {
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
            val key =
                call.parameters["key"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing note key"))
                    return@delete
                }

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@delete
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@delete
            }

            val noteResult = noteRepo.findByItemIdAndKey(id, key)
            val existingNote =
                when (noteResult) {
                    is Result.Success -> noteResult.data
                    is Result.Error -> null
                }

            if (existingNote == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Note '$key' not found on item $id"))
                return@delete
            }

            when (val result = noteRepo.delete(existingNote.id)) {
                is Result.Error -> {
                    noteWriteLogger.warn("DELETE /items/{}/notes/{} DB error: {}", id, key, result.error.message)
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to delete note"))
                }
                is Result.Success -> {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
