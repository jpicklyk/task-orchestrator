package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import java.sql.SQLException

/**
 * Site-local legacy mapping of store faults onto the 3.x wire shapes, until the error catalog reaches
 * the wire (P16). A fault keeps each site's existing code and message prefix:
 *
 * - MCP: `DATABASE_ERROR` with `"<site prefix>: <message>"`, for writes AND reads (F10), never
 *   RESOURCE_NOT_FOUND.
 * - REST: `500 db_error` with the route's existing text, for writes AND reads (F4); not-found stays
 *   for a missing row only.
 * - Only `version_conflict` is distinct: its [message] is the 3.x store text, and REST PATCH maps it to 409.
 *
 * A write fault arrives as the [DomainError] the unit boundary translated; a read fault (outside a unit)
 * arrives as the thrown exception, mapped by [legacyRead]. Either way the message carries the innermost
 * SQL text.
 */
object LegacyFaults {
    /** The store-style message of a unit fault (the 3.x version-mismatch text for `version_conflict`). */
    fun message(error: DomainError): String = if (isVersionConflict(error)) VersionConflictException.MESSAGE else error.message

    /** The store-style message of a thrown store fault: the innermost SQL exception text, else the throwable's own. */
    fun message(fault: Throwable): String {
        var innermostSql: SQLException? = null
        var current: Throwable? = fault
        var depth = 0
        while (current != null && depth < MAX_CHAIN) {
            if (current is SQLException) innermostSql = current
            if (current is VersionConflictException) return VersionConflictException.MESSAGE
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return innermostSql?.message?.takeIf { it.isNotBlank() }
            ?: fault.message?.takeIf { it.isNotBlank() }
            ?: fault.javaClass.simpleName
    }

    /**
     * A fault THROWN out of a unit (one the boundary does not translate: a non-SQL exception, or any
     * exception under a no-transaction unit of work) as a [DomainError]: a [VersionConflictException]
     * becomes `version_conflict`, anything else `internal` carrying [message].
     */
    fun fault(e: Throwable): DomainError {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < MAX_CHAIN) {
            if (current is VersionConflictException) {
                val id = current.id.toString()
                return DomainError(
                    code = ErrorCode.VERSION_CONFLICT,
                    message = VersionConflictException.MESSAGE,
                    detail = ErrorDetail.VersionConflict(EntityKind.ITEM, id, current.expected, current.actual),
                    fixArgs = mapOf("kind" to "item", "id" to id, "actual" to current.actual.toString())
                )
            }
            current = current.cause.takeIf { it !== current }
            depth++
        }
        return DomainError(ErrorCode.INTERNAL, message(e))
    }

    /** True when [error] is an optimistic-locking conflict (REST PATCH maps it to 409 `version_conflict`). */
    fun isVersionConflict(error: DomainError): Boolean = error.code == ErrorCode.VERSION_CONFLICT

    /** The MCP `DATABASE_ERROR` message of a site: `"<prefix>: <message>"`. */
    fun mcpMessage(
        prefix: String,
        error: DomainError
    ): String = "$prefix: ${message(error)}"

    private const val MAX_CHAIN = 32
}

/**
 * The legacy READ mapper (F4/F10): runs [block], a store read, and hands a thrown store fault to [onFault]
 * with its legacy message ([LegacyFaults.message]); [onFault] returns the site's existing error response
 * (typically a non-local `return`). Cancellation is rethrown. A missing row is not a fault: stores return
 * null for it.
 */
inline fun <T> legacyRead(
    onFault: (message: String) -> Nothing,
    block: () -> T
): T =
    try {
        block()
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        onFault(LegacyFaults.message(e))
    }

/**
 * [legacyRead] for callers that keep the fault as a catalog error: a thrown store fault reaches [onFault] as the
 * [DomainError] [LegacyFaults.fault] classifies it as (its `message` is the innermost SQL text, so
 * [LegacyFaults.message] of it equals what [legacyRead] hands over). Cancellation is rethrown.
 */
inline fun <T> legacyReadFault(
    onFault: (error: DomainError) -> Nothing,
    block: () -> T
): T =
    try {
        block()
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        onFault(LegacyFaults.fault(e))
    }

/**
 * The legacy DEGRADING read: runs [block] (an optional, best-effort store read, e.g. a page total or an
 * `include=` decoration) and returns null on a store fault, as the 3.x `Result.Error -> null` sites did.
 * Cancellation is rethrown.
 */
inline fun <T> legacyReadOrNull(block: () -> T): T? =
    try {
        block()
    } catch (e: Exception) {
        e.rethrowIfCancellation()
        null
    }
