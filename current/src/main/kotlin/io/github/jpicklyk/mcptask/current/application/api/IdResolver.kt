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
     * ascending order (ids only, as [toOutcome] reports them).
     */
    data class Ambiguous(
        val prefix: String,
        val candidates: List<UUID>,
    ) : IdResolution

    /** [raw] is not a valid id format ([IdRef]'s); [reason] says why. */
    data class Invalid(
        val raw: String,
        val reason: String,
    ) : IdResolution

    /** The store failed during the prefix lookup. Never a not-found: [toOutcome] rethrows [cause]. */
    data class Failed(
        val cause: Exception
    ) : IdResolution
}

/**
 * Resolves item ids, full or abbreviated, against the [store]: [IdRef]'s format, a canonical UUID (any case) or a
 * dash-free hex prefix of 8..32 characters.
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
                IdResolution.Ambiguous(prefix, sorted.map { it.id })
            }
        }
    }

    companion object {
        /** The most candidates an [IdResolution.Ambiguous] lists (and the row cap of the prefix lookup). */
        const val MAX_CANDIDATES = 10

        private const val UUID_LENGTH = 36
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
