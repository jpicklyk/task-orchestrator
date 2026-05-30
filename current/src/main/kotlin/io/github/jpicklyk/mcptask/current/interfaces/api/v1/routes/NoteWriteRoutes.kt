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
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteWriteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag.etagFor
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.mapping.toDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.slf4j.LoggerFactory
import java.util.UUID

private val noteWriteLogger = LoggerFactory.getLogger("NoteWriteRoutes")

private val VALID_NOTE_ROLES = setOf("queue", "work", "review")

/**
 * Sealed result for idempotency key parsing (note routes version — same pattern as item write routes).
 */
private sealed class NoteIdempotencyKeyResult {
    object Absent : NoteIdempotencyKeyResult()

    data class Present(
        val key: UUID
    ) : NoteIdempotencyKeyResult()

    object Invalid : NoteIdempotencyKeyResult()
}

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

            val itemResult = workItemRepo.getById(id)
            if (itemResult is Result.Error) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@put
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@put
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
                    call.response.header(HttpHeaders.ETag, currentEtag)
                    call.respond(
                        HttpStatusCode.PreconditionFailed,
                        ErrorDto("etag_mismatch", "Note ETag mismatch; current ETag is $currentEtag"),
                    )
                    return@put
                }
            }

            val idempotencyKeyResult = call.parseNoteIdempotencyKey()
            if (idempotencyKeyResult is NoteIdempotencyKeyResult.Invalid) return@put

            suspend fun executeUpsert() {
                val dto =
                    try {
                        call.receive<NoteWriteDto>()
                    } catch (e: SerializationException) {
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Invalid request body"))
                        return
                    }

                if (dto.role.lowercase() !in VALID_NOTE_ROLES) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorDto("validation_error", "role must be one of: queue, work, review"),
                    )
                    return
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
                        call.respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", e.message ?: "Validation failed"))
                        return
                    }

                when (val result = noteRepo.upsert(note)) {
                    is Result.Error -> {
                        noteWriteLogger.warn("PUT /items/{}/notes/{} DB error: {}", id, key, result.error.message)
                        call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to upsert note"))
                    }
                    is Result.Success -> {
                        val isCreate = existingNote == null
                        call.response.header(HttpHeaders.ETag, etagFor(result.data.modifiedAt))
                        val dto2 = redactor.redact(result.data.toDto(), call)
                        call.respond(if (isCreate) HttpStatusCode.Created else HttpStatusCode.OK, dto2)
                    }
                }
            }

            when (idempotencyKeyResult) {
                is NoteIdempotencyKeyResult.Present ->
                    idempotencyCache.getOrCompute(trustedActorId, idempotencyKeyResult.key) {
                        runBlocking { executeUpsert() }
                    }
                else -> executeUpsert()
            }
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

private suspend fun ApplicationCall.parseNoteIdempotencyKey(): NoteIdempotencyKeyResult {
    val keyHeader = request.headers["Idempotency-Key"] ?: return NoteIdempotencyKeyResult.Absent
    return try {
        NoteIdempotencyKeyResult.Present(UUID.fromString(keyHeader.trim()))
    } catch (e: IllegalArgumentException) {
        respond(HttpStatusCode.BadRequest, ErrorDto("validation_error", "Idempotency-Key must be a valid UUID"))
        NoteIdempotencyKeyResult.Invalid
    }
}
