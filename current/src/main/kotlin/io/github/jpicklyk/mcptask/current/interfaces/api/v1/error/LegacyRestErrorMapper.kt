package io.github.jpicklyk.mcptask.current.interfaces.api.v1.error

import io.github.jpicklyk.mcptask.current.application.service.AdvanceFailure
import io.github.jpicklyk.mcptask.current.application.service.BlockerInfo
import io.github.jpicklyk.mcptask.current.application.tools.workflow.NoteSchemaJsonHelpers
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.CachedHttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The read-fault text the 3.x REST read routes already use for a `db_error`. */
const val DB_QUERY_FAILED: String = "Database query failed"

/**
 * The closed set of 3.x REST error codes. Each binds the unchanged 3.x [wire] string and HTTP [status] to the
 * catalog [ErrorCode] that classifies it, so every REST error is classified once, here. REST carries no `kind`
 * and no catalog `details` in phase 1 (that is the envelope, W2): the catalog binding is the classification
 * the envelope will read.
 */
enum class LegacyRestCode(
    val wire: String,
    val status: HttpStatusCode,
    val catalog: ErrorCode
) {
    BAD_REQUEST("bad_request", HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST),
    VALIDATION_ERROR("validation_error", HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST),
    VALIDATION_ERROR_UNPROCESSABLE("validation_error", HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_REQUEST),
    FIELD_NOT_PATCHABLE("field_not_patchable", HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST),
    ROOTID_MISMATCH("rootid_mismatch", HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_REQUEST),
    PARSE_ERROR("parse_error", HttpStatusCode.UnprocessableEntity, ErrorCode.CONFIG_INVALID),
    UNSUPPORTED_MEDIA_TYPE("unsupported_media_type", HttpStatusCode.UnsupportedMediaType, ErrorCode.INVALID_REQUEST),
    PRECONDITION_REQUIRED("precondition_required", HttpStatusCode.BadRequest, ErrorCode.INVALID_REQUEST),
    PAYLOAD_TOO_LARGE("payload_too_large", HttpStatusCode.PayloadTooLarge, ErrorCode.PAYLOAD_TOO_LARGE),
    NOTE_BODY_TOO_LONG("note_body_too_long", HttpStatusCode.UnprocessableEntity, ErrorCode.NOTE_TOO_LONG),
    NOT_FOUND("not_found", HttpStatusCode.NotFound, ErrorCode.NOT_FOUND),
    NOT_FOUND_AS_BAD_REQUEST("not_found", HttpStatusCode.BadRequest, ErrorCode.NOT_FOUND),
    NO_SCHEMA("no_schema", HttpStatusCode.NotFound, ErrorCode.NOT_FOUND),
    RULE_NOT_FOUND("rule_not_found", HttpStatusCode.NotFound, ErrorCode.NOT_FOUND),
    SCOPE_FORBIDDEN("scope_forbidden", HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN),
    HOST_NOT_ALLOWED("host_not_allowed", HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN),
    INSUFFICIENT_CAPABILITY("insufficient_capability", HttpStatusCode.Forbidden, ErrorCode.FORBIDDEN),
    VERIFICATION_FAILED("verification_failed", HttpStatusCode.Unauthorized, ErrorCode.UNAUTHENTICATED),
    ETAG_MISMATCH("etag_mismatch", HttpStatusCode.PreconditionFailed, ErrorCode.VERSION_CONFLICT),
    VERSION_CONFLICT("version_conflict", HttpStatusCode.Conflict, ErrorCode.VERSION_CONFLICT),
    SUPERSEDED("superseded", HttpStatusCode.Conflict, ErrorCode.VERSION_CONFLICT),
    ADOPTED_CONFLICT("adopted_conflict", HttpStatusCode.Conflict, ErrorCode.INVALID_TRANSITION),
    HAS_CHILDREN("has_children", HttpStatusCode.Conflict, ErrorCode.INVALID_TRANSITION),
    INVALID_TRANSITION("invalid_transition", HttpStatusCode.Conflict, ErrorCode.INVALID_TRANSITION),
    TRANSITION_FAILED("transition_failed", HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_TRANSITION),
    TRANSITION_BLOCKED("transition_blocked", HttpStatusCode.UnprocessableEntity, ErrorCode.DEPENDENCY_UNMET),
    GATE_BLOCKED("gate_blocked", HttpStatusCode.UnprocessableEntity, ErrorCode.GATE_BLOCKED),
    RESOURCE_UNAVAILABLE("resource_unavailable", HttpStatusCode.Conflict, ErrorCode.RESOURCE_UNAVAILABLE),
    NOT_CLAIM_HOLDER("not_claim_holder", HttpStatusCode.Conflict, ErrorCode.NOT_CLAIM_HOLDER),
    IDEMPOTENCY_MISMATCH("idempotency_mismatch", HttpStatusCode.Conflict, ErrorCode.IDEMPOTENCY_MISMATCH),
    DUPLICATE_DEPENDENCY("duplicate_dependency", HttpStatusCode.Conflict, ErrorCode.DUPLICATE),
    CYCLE_DETECTED("cycle_detected", HttpStatusCode.BadRequest, ErrorCode.CYCLE_DETECTED),
    DB_ERROR("db_error", HttpStatusCode.InternalServerError, ErrorCode.INTERNAL),
    INTERNAL("internal", HttpStatusCode.InternalServerError, ErrorCode.INTERNAL),
    UNAVAILABLE("unavailable", HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE),
    CONFIG_UNAVAILABLE("config_unavailable", HttpStatusCode.ServiceUnavailable, ErrorCode.INTERNAL)
}

/**
 * The only builder of REST error bodies: an [ErrorDto] (`{error, message, details?}`) at the code's status,
 * either sent to the caller or captured for idempotent replay. Status, body, details and headers are the 3.x
 * ones; a REST error carries no `kind` in phase 1.
 */
object LegacyRestErrorMapper {
    private val json =
        Json {
            explicitNulls = false
            encodeDefaults = true
        }

    /** The error body of [code]. */
    fun dto(
        code: LegacyRestCode,
        message: String,
        details: JsonObject? = null
    ): ErrorDto = ErrorDto(code.wire, message, details)

    /** The error response of [code] as a [CachedHttpResponse] (the body the idempotency layer stores and replays). */
    fun captured(
        code: LegacyRestCode,
        message: String,
        details: JsonObject? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        etag: String? = null
    ): CachedHttpResponse =
        CachedHttpResponse(
            statusCode = code.status.value,
            bodyJson = json.encodeToString(ErrorDto.serializer(), dto(code, message, details)),
            etag = etag,
            extraHeaders = extraHeaders
        )

    /**
     * Maps a structured [AdvanceFailure] from the advance service to its HTTP error response:
     *
     * - [AdvanceFailure.GateBlocked] -> **422** `gate_blocked`, with the structured missing required notes in
     *   `details.missingNotes` and, when the target schema is seat-aware, `details.missingBySeat`.
     * - [AdvanceFailure.ValidationFailed] -> **422** `transition_blocked`, with the dependency blockers.
     * - [AdvanceFailure.ResolutionFailed] / [AdvanceFailure.ApplyFailed] -> **422** `transition_failed`.
     * - [AdvanceFailure.ResourceLeaseUnavailable] -> **409** `resource_unavailable`, with a `Retry-After`
     *   header (whole seconds, rounded UP so a client never retries early) and `details.contendedResources` /
     *   `details.retryAfterMs`. Resource KEYS only: never the holding item or actor.
     * - [AdvanceFailure.PolicyRejected] -> **401** `verification_failed` (defensive: ownership is not enforced on REST).
     * - [AdvanceFailure.OwnershipRejected] -> **409** `not_claim_holder` (defensive: same caveat).
     */
    fun advanceFailure(failure: AdvanceFailure): CachedHttpResponse =
        when (failure) {
            is AdvanceFailure.GateBlocked ->
                captured(LegacyRestCode.GATE_BLOCKED, failure.message, gateBlockedDetails(failure))
            is AdvanceFailure.ValidationFailed ->
                captured(LegacyRestCode.TRANSITION_BLOCKED, failure.message, validationFailedDetails(failure))
            is AdvanceFailure.ResourceLeaseUnavailable ->
                captured(
                    LegacyRestCode.RESOURCE_UNAVAILABLE,
                    failure.message,
                    resourceLeaseUnavailableDetails(failure),
                    // Round UP to whole seconds: Retry-After has second granularity, and rounding down
                    // would tell the client to retry before the lease can possibly have expired.
                    failure.retryAfterMs?.let { ms -> mapOf(HttpHeaders.RetryAfter to ((ms + 999) / 1000).coerceAtLeast(1).toString()) }
                        ?: emptyMap()
                )
            is AdvanceFailure.ResolutionFailed -> captured(LegacyRestCode.TRANSITION_FAILED, failure.message)
            is AdvanceFailure.ApplyFailed -> captured(LegacyRestCode.TRANSITION_FAILED, failure.message)
            is AdvanceFailure.PolicyRejected -> captured(LegacyRestCode.VERIFICATION_FAILED, failure.reason)
            is AdvanceFailure.OwnershipRejected -> captured(LegacyRestCode.NOT_CLAIM_HOLDER, failure.message)
        }

    private fun gateBlockedDetails(failure: AdvanceFailure.GateBlocked): JsonObject =
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
                            }
                        )
                    }
                }
            )
            // Mirrors get_context / REST /gate's missingBySeat: present only when AdvanceService computed it
            // (seat-aware schema), absent otherwise.
            failure.missingBySeat?.let { bySeat ->
                put(
                    "missingBySeat",
                    buildJsonObject {
                        bySeat.forEach { (seat, keys) ->
                            put(seat, JsonArray(keys.map { JsonPrimitive(it) }))
                        }
                    }
                )
            }
            // Independence-attestation findings: the same raw JSON shape MCP's advance_item gate_blocked
            // details use (actor-free by construction); present only when the list is non-empty.
            NoteSchemaJsonHelpers.buildViolationsArrayNonEmpty(failure.violations)?.let { put("violations", it) }
        }

    private fun validationFailedDetails(failure: AdvanceFailure.ValidationFailed): JsonObject =
        buildJsonObject {
            put(
                "blockers",
                kotlinx.serialization.json.buildJsonArray {
                    failure.blockers.forEach { blocker ->
                        add(
                            buildJsonObject {
                                put("fromItemId", JsonPrimitive(blocker.fromItemId.toString()))
                                put("currentRole", JsonPrimitive(blocker.currentRole?.name?.lowercase() ?: BlockerInfo.UNKNOWN_ROLE))
                                put("requiredRole", JsonPrimitive(blocker.requiredRole))
                            }
                        )
                    }
                }
            )
        }

    private fun resourceLeaseUnavailableDetails(failure: AdvanceFailure.ResourceLeaseUnavailable): JsonObject =
        buildJsonObject {
            put("targetRole", JsonPrimitive(failure.targetRole.name.lowercase()))
            put(
                "contendedResources",
                kotlinx.serialization.json.buildJsonArray {
                    failure.contendedResources.forEach { add(JsonPrimitive(it)) }
                }
            )
            failure.retryAfterMs?.let { put("retryAfterMs", JsonPrimitive(it)) }
        }
}

/** Responds with the error of [code] at its status: `{error, message, details?}`, plus [headers] when given. */
suspend fun ApplicationCall.respondError(
    code: LegacyRestCode,
    message: String,
    details: JsonObject? = null,
    headers: Map<String, String> = emptyMap()
) {
    headers.forEach { (name, value) -> response.header(name, value) }
    respond(code.status, LegacyRestErrorMapper.dto(code, message, details))
}
