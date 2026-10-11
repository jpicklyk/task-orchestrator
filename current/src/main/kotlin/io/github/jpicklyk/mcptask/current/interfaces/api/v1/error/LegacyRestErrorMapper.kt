package io.github.jpicklyk.mcptask.current.interfaces.api.v1.error

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonObject

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
    HAS_CHILDREN("has_children", HttpStatusCode.Conflict, ErrorCode.INVALID_TRANSITION),
    INVALID_TRANSITION("invalid_transition", HttpStatusCode.Conflict, ErrorCode.INVALID_TRANSITION),
    TRANSITION_FAILED("transition_failed", HttpStatusCode.UnprocessableEntity, ErrorCode.INVALID_TRANSITION),
    TRANSITION_BLOCKED("transition_blocked", HttpStatusCode.UnprocessableEntity, ErrorCode.DEPENDENCY_UNMET),
    GATE_BLOCKED("gate_blocked", HttpStatusCode.UnprocessableEntity, ErrorCode.GATE_BLOCKED),
    RESOURCE_UNAVAILABLE("resource_unavailable", HttpStatusCode.Conflict, ErrorCode.RESOURCE_UNAVAILABLE),
    NOT_CLAIM_HOLDER("not_claim_holder", HttpStatusCode.Conflict, ErrorCode.NOT_CLAIM_HOLDER),
    IDEMPOTENCY_MISMATCH("idempotency_mismatch", HttpStatusCode.Conflict, ErrorCode.IDEMPOTENCY_MISMATCH),
    DB_ERROR("db_error", HttpStatusCode.InternalServerError, ErrorCode.INTERNAL),
    INTERNAL("internal", HttpStatusCode.InternalServerError, ErrorCode.INTERNAL),
    UNAVAILABLE("unavailable", HttpStatusCode.ServiceUnavailable, ErrorCode.UNAVAILABLE),
    CONFIG_UNAVAILABLE("config_unavailable", HttpStatusCode.ServiceUnavailable, ErrorCode.INTERNAL)
}

/**
 * The only builder of REST error bodies: an [ErrorDto] (`{error, message, details?}`) at the code's status,
 * sent to the caller. Status, body, details and headers are the 3.x
 * ones; a REST error carries no `kind` in phase 1.
 */
object LegacyRestErrorMapper {
    /** The error body of [code]. */
    fun dto(
        code: LegacyRestCode,
        message: String,
        details: JsonObject? = null
    ): ErrorDto = ErrorDto(code.wire, message, details)
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
