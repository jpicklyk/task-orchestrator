package io.github.jpicklyk.mcptask.current.domain.error

import kotlin.reflect.KClass

/**
 * The closed catalog of 4.0 error codes. [wire] is the stable snake_case code, [kind] the retry
 * semantics (decided by the code, never per call site), [httpStatus] the REST status, and
 * [detailClass] the [ErrorDetail] subtype the code carries (null when it defines none).
 */
enum class ErrorCode(
    val wire: String,
    val kind: ErrorKind,
    val httpStatus: Int,
    val detailClass: KClass<out ErrorDetail>?
) {
    INVALID_REQUEST("invalid_request", ErrorKind.PERMANENT, 400, ErrorDetail.InvalidRequest::class),
    UNKNOWN_PARAMETER("unknown_parameter", ErrorKind.PERMANENT, 400, ErrorDetail.UnknownParameter::class),
    INVALID_CURSOR("invalid_cursor", ErrorKind.PERMANENT, 400, null),
    NOT_FOUND("not_found", ErrorKind.PERMANENT, 404, ErrorDetail.NotFound::class),
    AMBIGUOUS_ID("ambiguous_id", ErrorKind.PERMANENT, 409, ErrorDetail.AmbiguousId::class),
    VERSION_CONFLICT("version_conflict", ErrorKind.PERMANENT, 409, ErrorDetail.VersionConflict::class),
    DUPLICATE("duplicate", ErrorKind.PERMANENT, 409, ErrorDetail.Duplicate::class),
    IDEMPOTENCY_MISMATCH("idempotency_mismatch", ErrorKind.PERMANENT, 409, ErrorDetail.IdempotencyMismatch::class),
    INVALID_TRANSITION("invalid_transition", ErrorKind.PERMANENT, 409, ErrorDetail.InvalidTransition::class),
    GATE_BLOCKED("gate_blocked", ErrorKind.PERMANENT, 409, ErrorDetail.GateBlocked::class),
    DEPENDENCY_UNMET("dependency_unmet", ErrorKind.PERMANENT, 409, ErrorDetail.DependencyUnmet::class),
    CYCLE_DETECTED("cycle_detected", ErrorKind.PERMANENT, 409, ErrorDetail.CycleDetected::class),
    CLAIM_HELD("claim_held", ErrorKind.TRANSIENT, 409, ErrorDetail.ClaimHeld::class),
    NOT_CLAIM_HOLDER("not_claim_holder", ErrorKind.PERMANENT, 403, ErrorDetail.NotClaimHolder::class),
    SEAT_FORBIDDEN("seat_forbidden", ErrorKind.PERMANENT, 403, ErrorDetail.SeatForbidden::class),
    NOTE_OWNED_BY_OTHER("note_owned_by_other", ErrorKind.PERMANENT, 403, ErrorDetail.NoteOwnedByOther::class),
    NOTE_TOO_LONG("note_too_long", ErrorKind.PERMANENT, 413, ErrorDetail.NoteTooLong::class),
    RESOURCE_UNAVAILABLE("resource_unavailable", ErrorKind.TRANSIENT, 409, ErrorDetail.ResourceUnavailable::class),
    SCHEMA_VIOLATION("schema_violation", ErrorKind.PERMANENT, 422, ErrorDetail.SchemaViolation::class),
    SCHEMA_PINNED_CONFLICT("schema_pinned_conflict", ErrorKind.PERMANENT, 409, ErrorDetail.SchemaPinnedConflict::class),
    CONFIG_INVALID("config_invalid", ErrorKind.PERMANENT, 422, ErrorDetail.ConfigInvalid::class),
    PAYLOAD_TOO_LARGE("payload_too_large", ErrorKind.PERMANENT, 413, ErrorDetail.PayloadTooLarge::class),
    UNAUTHENTICATED("unauthenticated", ErrorKind.PERMANENT, 401, null),
    FORBIDDEN("forbidden", ErrorKind.PERMANENT, 403, ErrorDetail.Forbidden::class),
    PARTIAL_FAILURE("partial_failure", ErrorKind.PERMANENT, 207, null),
    UNAVAILABLE("unavailable", ErrorKind.SHEDDING, 503, ErrorDetail.Unavailable::class),
    INTERNAL("internal", ErrorKind.TRANSIENT, 500, null)
}
