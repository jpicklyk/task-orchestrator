package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.ItemStore
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.util.UUID

/**
 * The result of resolving an item id as the caller wrote it (see [IdResolver]).
 */
sealed interface IdResolution {
    /** The id names exactly one item ([id]), or is a canonical UUID (resolved without a store read). */
    data class Resolved(
        val id: UUID
    ) : IdResolution

    /** No item id starts with [prefix]. */
    data class NotFound(
        val prefix: String
    ) : IdResolution

    /**
     * Two or more item ids start with [prefix]: [candidates] holds at most [IdResolver.MAX_CANDIDATES] of them in
     * ascending order. [titles] carries each candidate's title for the 3.x `matches[]` wire; the 4.0 outcome
     * ([toOutcome]) reports ids only.
     */
    data class Ambiguous(
        val prefix: String,
        val candidates: List<UUID>,
        val titles: Map<UUID, String> = emptyMap(),
    ) : IdResolution

    /** [raw] is not a valid id format; [reason] says why, [defect] which rule it broke. */
    data class Invalid(
        val raw: String,
        val reason: String,
        val defect: IdFormatDefect = IdFormatDefect.FORMAT,
    ) : IdResolution

    /** The store failed during the prefix lookup. Never a not-found: [toOutcome] rethrows [cause]. */
    data class Failed(
        val cause: Exception
    ) : IdResolution
}

/** Which id format rule an [IdResolution.Invalid] broke. */
enum class IdFormatDefect {
    /** The 4.0 rule ([IdRef]): neither a canonical UUID nor 8..32 hex characters. */
    FORMAT,

    /** 3.x: 36 characters but not a parseable UUID. */
    MALFORMED_UUID,

    /** 3.x: not a hex string. */
    NOT_HEX,

    /** 3.x: a hex prefix shorter than [IdResolver.LEGACY_MIN_PREFIX_LENGTH]. */
    TOO_SHORT,
}

/**
 * Resolves item ids, full or abbreviated, against the [store]. The one definition of both id formats:
 *
 * - **4.0** ([resolve]): [IdRef]'s format, a canonical UUID (any case) or a dash-free hex prefix of 8..32 characters.
 * - **3.x** ([resolveLegacy]): a 36-character UUID or a hex prefix of [LEGACY_MIN_PREFIX_LENGTH] or more
 *   characters, for the 3.x tools until they are removed.
 *
 * A UUID resolves with no store read (whether the item exists stays the caller's question). A prefix is one store
 * read ([ItemStore.findByIdPrefix], at most [MAX_CANDIDATES] rows). A store fault becomes [IdResolution.Failed];
 * cancellation is never wrapped.
 */
class IdResolver(
    private val store: ItemStore,
) {
    /** Resolves [raw] under the 4.0 format; a malformed value is [IdResolution.Invalid] with [IdRef.FORMAT_REASON]. */
    suspend fun resolve(raw: String): IdResolution {
        val ref = IdRef.parse(raw) ?: return IdResolution.Invalid(raw, IdRef.FORMAT_REASON)
        return resolve(ref)
    }

    /** Resolves an already format-checked [ref]. */
    suspend fun resolve(ref: IdRef): IdResolution =
        if (ref.raw.length == UUID_LENGTH) {
            IdResolution.Resolved(UUID.fromString(ref.raw))
        } else {
            lookup(ref.raw)
        }

    /** Resolves [raw] under the 3.x format (see [legacyFormatDefect]). */
    suspend fun resolveLegacy(raw: String): IdResolution {
        legacyFormatDefect(raw)?.let { return it }
        if (raw.length == UUID_LENGTH) return IdResolution.Resolved(UUID.fromString(raw))
        return lookup(raw)
    }

    private suspend fun lookup(prefix: String): IdResolution {
        val matches =
            try {
                store.findByIdPrefix(prefix, MAX_CANDIDATES)
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                return IdResolution.Failed(e)
            }
        return when (matches.size) {
            0 -> IdResolution.NotFound(prefix)
            1 -> IdResolution.Resolved(matches.single().id)
            else -> {
                val sorted = matches.sortedBy { it.id }
                IdResolution.Ambiguous(prefix, sorted.map { it.id }, sorted.associate { it.id to it.title })
            }
        }
    }

    companion object {
        /** The most candidates an [IdResolution.Ambiguous] lists (and the row cap of the prefix lookup). */
        const val MAX_CANDIDATES = 10

        /** Minimum hex prefix length of the 3.x format. */
        const val LEGACY_MIN_PREFIX_LENGTH = 4

        /** A hex string, any case: the 3.x prefix rule. */
        val LEGACY_HEX_PATTERN: Regex = Regex("^[0-9a-fA-F]+$")

        private const val UUID_LENGTH = 36

        /**
         * The 3.x format check alone, no store read: null when [raw] is acceptable (a 36-character string that
         * parses as a UUID, or a hex string of at least [LEGACY_MIN_PREFIX_LENGTH] characters), else the
         * [IdResolution.Invalid] naming the broken rule.
         */
        fun legacyFormatDefect(raw: String): IdResolution.Invalid? {
            if (raw.length == UUID_LENGTH) {
                return try {
                    UUID.fromString(raw)
                    null
                } catch (_: IllegalArgumentException) {
                    IdResolution.Invalid(raw, "not a valid UUID", IdFormatDefect.MALFORMED_UUID)
                }
            }
            if (!LEGACY_HEX_PATTERN.matches(raw)) {
                return IdResolution.Invalid(raw, "must be a UUID or hex prefix", IdFormatDefect.NOT_HEX)
            }
            if (raw.length < LEGACY_MIN_PREFIX_LENGTH) {
                return IdResolution.Invalid(
                    raw,
                    "prefix shorter than $LEGACY_MIN_PREFIX_LENGTH hex characters",
                    IdFormatDefect.TOO_SHORT
                )
            }
            return null
        }
    }
}

/**
 * The 4.0 outcome of this resolution for the request field [field]: the id, or `invalid_request` (one field violation
 * with [IdRef.FORMAT_REASON]), `not_found` (kind item, the prefix as id) or `ambiguous_id` (the candidate ids, no
 * titles). [IdResolution.Failed] rethrows its cause, so the adapter's fault classification applies instead of a
 * not-found.
 */
fun IdResolution.toOutcome(field: String): Outcome<UUID> =
    when (this) {
        is IdResolution.Resolved -> Outcome.Ok(id)
        is IdResolution.Invalid ->
            Outcome.Err(
                DomainError(
                    code = ErrorCode.INVALID_REQUEST,
                    message = "The $field is not a valid id.",
                    detail = ErrorDetail.InvalidRequest(listOf(FieldViolation(field, IdRef.FORMAT_REASON, raw))),
                )
            )
        is IdResolution.NotFound ->
            Outcome.Err(
                DomainError(
                    code = ErrorCode.NOT_FOUND,
                    message = "No item matches $prefix.",
                    detail = ErrorDetail.NotFound(EntityKind.ITEM, prefix),
                    fixArgs = mapOf("kind" to "item", "id" to prefix),
                )
            )
        is IdResolution.Ambiguous ->
            Outcome.Err(
                DomainError(
                    code = ErrorCode.AMBIGUOUS_ID,
                    message = "The id $prefix matches more than one item.",
                    detail = ErrorDetail.AmbiguousId(prefix, candidates),
                    fixArgs = mapOf("prefix" to prefix),
                )
            )
        is IdResolution.Failed -> throw cause
    }
