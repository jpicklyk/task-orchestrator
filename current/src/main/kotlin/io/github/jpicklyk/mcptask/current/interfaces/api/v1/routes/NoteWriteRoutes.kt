package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.withConfigSession
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.service.withEventActor
import io.github.jpicklyk.mcptask.current.application.support.LegacyFaults
import io.github.jpicklyk.mcptask.current.application.support.legacyRead
import io.github.jpicklyk.mcptask.current.application.support.runCatchingNonCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
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
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.put
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID

private val noteWriteLogger = LoggerFactory.getLogger("NoteWriteRoutes")

// Accepted Content-Types for the JSON note body (PUT /items/{id}/notes/{key}). `*/*` is what
// `call.request.contentType()` reports when the header is ABSENT, which ContentNegotiation's
// wildcard match also accepted — so an absent header stays accepted and only a genuinely non-JSON
// Content-Type is rejected.
private val JSON_WRITE_CONTENT_TYPES = setOf("application/json", "*/*")

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
 * Maps a [NoteCommandService] write failure to its captured HTTP response. Only a payload-only failure
 * (an invalid role) is a recordable [payloadRejection]; every config-dependent one (schema-role,
 * `maxLength`) is returned unrecorded so a retry after a config change runs again.
 */
private fun noteWriteFailure(
    error: DomainError,
    itemId: UUID,
    key: String,
): CachedHttpResponse =
    when (error.code) {
        ErrorCode.INVALID_REQUEST -> payloadRejection(error.message)
        ErrorCode.PAYLOAD_TOO_LARGE -> noteErrorCaptured(HttpStatusCode.PayloadTooLarge, "payload_too_large", error.message)
        ErrorCode.NOTE_TOO_LONG -> noteErrorCaptured(HttpStatusCode.UnprocessableEntity, "note_body_too_long", error.message)
        ErrorCode.SCHEMA_VIOLATION -> noteErrorCaptured(HttpStatusCode.BadRequest, "validation_error", error.message)
        ErrorCode.NOT_FOUND -> noteErrorCaptured(HttpStatusCode.NotFound, "not_found", "Item $itemId not found")
        else -> {
            noteWriteLogger.warn("PUT /items/{}/notes/{} DB error: {} {}", itemId, key, error.code.wire, error.message)
            noteErrorCaptured(HttpStatusCode.InternalServerError, "db_error", "Failed to upsert note")
        }
    }

/**
 * Registers note-write routes under the `/api/v1` route prefix.
 *
 * Endpoints:
 * - `PUT    /items/{id}/notes/{key}` — upsert note ([ApiCapability.WRITE_NOTES])
 * - `DELETE /items/{id}/notes/{key}` — delete note ([ApiCapability.WRITE_NOTES])
 *
 * **Write policy:** role normalization, the byte cap, CRLF normalization, the schema-role rule and the
 * schema `maxLength` all live in [NoteCommandService]; this file only maps its errors to HTTP
 * (`maxLength` reject 422 `note_body_too_long`, byte cap 413 `payload_too_large`, schema-role 400
 * `validation_error`, per-root config unavailable 503 `config_unavailable`). A `maxLength` overflow
 * under `note_limits.mode: warn` is accepted and adds a `warning` string to the response body.
 * **Audit:** actor claim is synthesized server-side from [ApiPrincipal]; client `actor.*` is dropped.
 * **ETag:** `If-Match` is accepted on PUT (update path) but not required for create.
 * **Idempotency:** `Idempotency-Key: <UUID>` header supported on PUT.
 */
fun Route.noteWriteRoutes(
    repositoryProvider: RepositoryProvider,
    degradedModePolicy: DegradedModePolicy,
    idempotency: IdempotencyService,
    unitOfWork: UnitOfWork,
    noteCommandService: NoteCommandService,
) {
    val workItemRepo = repositoryProvider.workItemRepository()
    val noteRepo = repositoryProvider.noteRepository()
    val redactor = AttributionRedactor.fromEnv()

    requireCapability(ApiCapability.WRITE_NOTES) {
        // ─── PUT /items/{id}/notes/{key} (upsert) ───────────────────────────
        put("/items/{id}/notes/{key}") {
            val principal = call.attributes[ApiPrincipalKey]
            val trustedActorId = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, degradedModePolicy)

            // Content-Type gate — explicit because the body is no longer read through
            // `receive<NoteWriteDto>()`, which let ContentNegotiation reject a non-JSON body with
            // 415. It runs before the body read, so 415 still precedes anything body-dependent.
            val upsertContentType =
                call.request
                    .contentType()
                    .withoutParameters()
                    .toString()
            if (upsertContentType !in JSON_WRITE_CONTENT_TYPES) {
                call.respond(
                    HttpStatusCode.UnsupportedMediaType,
                    ErrorDto("unsupported_media_type", "Use Content-Type: application/json"),
                )
                return@put
            }

            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@put
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
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

            // Raw bytes only, read BEFORE runWithIdempotency so client-paced network I/O does not
            // happen while this key's idempotency in-flight entry is held. Bounded (bug e941c2c7
            // — this route had no size limit at all before this fix; see receiveBounded's KDoc).
            // Deserialization stays below, after the If-Match check, so `etag_mismatch` still
            // precedes `validation_error`. Decoded with McpJson, the same instance
            // ContentNegotiation is installed with, so this behaves exactly as
            // `receive<NoteWriteDto>()` did, minus the unbounded buffering.
            val bodyText = call.receiveBounded(MAX_JSON_WRITE_BODY_BYTES) ?: return@put

            // The state-dependent pre-conditions (item existence, scope, note-existence, If-Match
            // ETag) AND the upsert run INSIDE the captured block, so an Idempotency-Key replay
            // returns the cached response verbatim without re-evaluating the now-mutated note's ETag.
            suspend fun executeUpsert(): CachedHttpResponse {
                val itemResult =
                    legacyRead({ return noteErrorCaptured(HttpStatusCode.InternalServerError, "db_error", DB_QUERY_FAILED) }) {
                        workItemRepo.getById(id)
                    }
                if (itemResult == null) {
                    return noteErrorCaptured(HttpStatusCode.NotFound, "not_found", "Item $id not found")
                }

                if (!enforceScopeForItem(call, id, workItemRepo)) {
                    return noteErrorCaptured(HttpStatusCode.Forbidden, "scope_forbidden", "Access denied for item $id")
                }

                // Check for existing note to handle ETag and ID preservation
                val existingNote =
                    legacyRead({ return noteErrorCaptured(HttpStatusCode.InternalServerError, "db_error", DB_QUERY_FAILED) }) {
                        noteRepo.findByItemIdAndKey(id, key)
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
                        McpJson.decodeFromString(NoteWriteDto.serializer(), bodyText)
                    } catch (e: SerializationException) {
                        return payloadRejection(e.message ?: "Invalid request body")
                    }

                // Synthesize actor server-side — client body actor.* fields are dropped
                val actorClaim = ApiAuditBridge.toActorClaim(principal)
                val verification = ApiAuditBridge.toVerificationResult(principal)

                val written =
                    try {
                        withConfigSession {
                            noteCommandService.upsert(NoteUpsertCommand(id, key, dto.role, dto.body, actorClaim, verification))
                        }
                    } catch (e: PerRootConfigUnavailableException) {
                        noteWriteLogger.warn("PUT /items/{}/notes/{} per-root config unavailable: {}", id, key, e.message)
                        return noteErrorCaptured(HttpStatusCode.ServiceUnavailable, PerRootConfigUnavailableException.CODE, e.message)
                    }
                return when (written) {
                    is Outcome.Err -> noteWriteFailure(written.error, id, key)
                    is Outcome.Ok -> {
                        val result = written.value
                        val redactedDto = redactor.redact(result.note.toDto(), call)
                        val dtoJson = noteWriteJson.encodeToJsonElement(NoteDto.serializer(), redactedDto) as JsonObject
                        val body =
                            result.warning?.let { warning ->
                                buildJsonObject {
                                    dtoJson.forEach { (name, value) -> put(name, value) }
                                    put("warning", JsonPrimitive(warning.message()))
                                }
                            } ?: dtoJson
                        CachedHttpResponse(
                            statusCode = if (result.created) HttpStatusCode.Created.value else HttpStatusCode.OK.value,
                            bodyJson = noteWriteJson.encodeToString(JsonObject.serializer(), body),
                            etag = etagFor(result.note.modifiedAt),
                        )
                    }
                }
            }

            call.runWithIdempotency(
                idempotency,
                trustedActorId,
                idempotencyKeyResult,
                "/items/{id}/notes/{key}",
                bodyText
            ) { executeUpsert() }
        }

        // ─── DELETE /items/{id}/notes/{key} ─────────────────────────────────
        delete("/items/{id}/notes/{key}") {
            val rawId =
                call.parameters["id"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing item id"))
                    return@delete
                }
            val id =
                runCatchingNonCancellation { UUID.fromString(rawId) }.getOrNull() ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Invalid UUID: $rawId"))
                    return@delete
                }
            val key =
                call.parameters["key"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request", "Missing note key"))
                    return@delete
                }

            val itemResult =
                legacyRead({
                    call.respondDbError()
                    return@delete
                }) { workItemRepo.getById(id) }
            if (itemResult == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Item $id not found"))
                return@delete
            }

            if (!enforceScopeForItem(call, id, workItemRepo)) {
                call.respond(HttpStatusCode.Forbidden, ErrorDto("scope_forbidden", "Access denied for item $id"))
                return@delete
            }

            val existingNote =
                legacyRead({
                    call.respondDbError()
                    return@delete
                }) { noteRepo.findByItemIdAndKey(id, key) }

            if (existingNote == null) {
                call.respond(HttpStatusCode.NotFound, ErrorDto("not_found", "Note '$key' not found on item $id"))
                return@delete
            }

            when (
                val result =
                    withEventActor(
                        ApiAuditBridge.toActorClaim(call.attributes[ApiPrincipalKey])
                    ) { noteCommandService.deleteById(existingNote.id) }
            ) {
                is Outcome.Err -> {
                    noteWriteLogger.warn("DELETE /items/{}/notes/{} DB error: {}", id, key, LegacyFaults.message(result.error))
                    call.respond(HttpStatusCode.InternalServerError, ErrorDto("db_error", "Failed to delete note"))
                }
                is Outcome.Ok -> {
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
