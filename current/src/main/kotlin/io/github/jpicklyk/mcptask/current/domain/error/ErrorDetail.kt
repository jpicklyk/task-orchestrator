package io.github.jpicklyk.mcptask.current.domain.error

import java.time.Instant
import java.util.UUID

/** Entity families that errors can refer to. */
enum class EntityKind { ITEM, NOTE, DEPENDENCY, DOCUMENT, RUN, ROOT }

/** Scope that a `forbidden` error says the caller lacks. */
enum class ForbiddenScope { ROOT, TAG, CAPABILITY }

/** One offending request field. */
data class FieldViolation(
    val field: String,
    val reason: String,
    val received: String? = null
)

/** One note an item still needs before it can advance. */
data class MissingNote(
    val key: String,
    val role: String,
    val seat: String? = null
)

/** One blocking dependency. */
data class Blocker(
    val id: UUID,
    val role: String
)

/** One contended shared resource. */
data class ResourceRef(
    val name: String,
    val mode: String
)

/** One configuration problem. */
data class ConfigViolation(
    val path: String,
    val reason: String
)

/**
 * Code-specific error detail. One nested data class per [ErrorCode] that defines a detail
 * (see [ErrorCode.detailClass]). Details must never carry another principal's identity.
 */
sealed interface ErrorDetail {
    data class InvalidRequest(
        val fields: List<FieldViolation>
    ) : ErrorDetail {
        init {
            require(fields.isNotEmpty()) { "fields must not be empty" }
        }
    }

    data class UnknownParameter(
        val parameters: List<String>
    ) : ErrorDetail {
        init {
            require(parameters.isNotEmpty()) { "parameters must not be empty" }
        }
    }

    /** [id] is null only when the unit-of-work boundary translates a raw FK violation; services that know the id must set it. */
    data class NotFound(
        val kind: EntityKind,
        val id: String?
    ) : ErrorDetail

    data class AmbiguousId(
        val prefix: String,
        val candidates: List<UUID>
    ) : ErrorDetail {
        init {
            require(candidates.size >= 2) { "candidates must contain at least 2 ids" }
        }
    }

    data class VersionConflict(
        val kind: EntityKind,
        val id: String,
        val expected: Long,
        val actual: Long
    ) : ErrorDetail {
        init {
            require(expected != actual) { "expected and actual versions must differ" }
        }
    }

    /** [existingId] is null only for a raw unique-constraint translation at the unit-of-work boundary. */
    data class Duplicate(
        val kind: EntityKind,
        val id: String? = null,
        val existingId: String?
    ) : ErrorDetail

    data class IdempotencyMismatch(
        val idempotencyKey: String
    ) : ErrorDetail

    data class InvalidTransition(
        val itemId: UUID,
        val fromRole: String,
        val trigger: String,
        val allowed: List<String>
    ) : ErrorDetail

    data class GateBlocked(
        val itemId: UUID,
        val role: String,
        val missing: List<MissingNote>
    ) : ErrorDetail {
        init {
            require(missing.isNotEmpty()) { "missing must not be empty" }
        }
    }

    data class DependencyUnmet(
        val itemId: UUID,
        val blockers: List<Blocker>
    ) : ErrorDetail {
        init {
            require(blockers.isNotEmpty()) { "blockers must not be empty" }
        }
    }

    data class CycleDetected(
        val path: List<UUID>
    ) : ErrorDetail {
        init {
            require(path.isNotEmpty()) { "path must not be empty" }
        }
    }

    data class ClaimHeld(
        val itemId: UUID,
        val expiresAt: Instant,
        val retryAfterMs: Long
    ) : ErrorDetail

    data class NotClaimHolder(
        val itemId: UUID
    ) : ErrorDetail

    data class SeatForbidden(
        val itemId: UUID,
        val seat: String,
        val action: String,
        val allowedSeats: List<String>
    ) : ErrorDetail

    data class NoteOwnedByOther(
        val itemId: UUID,
        val key: String,
        val ownerSeat: String? = null
    ) : ErrorDetail

    data class NoteTooLong(
        val key: String,
        val max: Int,
        val actual: Int
    ) : ErrorDetail {
        init {
            require(actual > max) { "actual must exceed max" }
        }
    }

    data class ResourceUnavailable(
        val itemId: UUID,
        val resources: List<ResourceRef>,
        val retryAfterMs: Long? = null
    ) : ErrorDetail {
        init {
            require(resources.isNotEmpty()) { "resources must not be empty" }
        }
    }

    data class SchemaViolation(
        val type: String? = null,
        val reason: String,
        val availableTypes: List<String>? = null
    ) : ErrorDetail

    data class SchemaPinnedConflict(
        val itemId: UUID,
        val pinnedVersion: String,
        val currentVersion: String
    ) : ErrorDetail

    data class ConfigInvalid(
        val errors: List<ConfigViolation>
    ) : ErrorDetail {
        init {
            require(errors.isNotEmpty()) { "errors must not be empty" }
        }
    }

    data class PayloadTooLarge(
        val max: Long,
        val actual: Long
    ) : ErrorDetail {
        init {
            require(actual > max) { "actual must exceed max" }
        }
    }

    data class Forbidden(
        val scope: ForbiddenScope,
        val required: String
    ) : ErrorDetail

    data class Unavailable(
        val retryAfterMs: Long
    ) : ErrorDetail
}
