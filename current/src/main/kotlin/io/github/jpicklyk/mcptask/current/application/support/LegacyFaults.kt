package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result

/**
 * Site-local legacy mapping of unit-of-work faults onto the 3.x wire shapes, until the error catalog reaches
 * the wire (P16). A fault translated at the unit boundary keeps each site's existing code and message prefix:
 *
 * - MCP: `DATABASE_ERROR` with `"<site prefix>: <message>"` (also for READ faults, never RESOURCE_NOT_FOUND).
 * - REST: `500 db_error` with the route's existing text (also for READ faults; not-found stays for a missing row).
 * - Only `version_conflict` is distinct: its [message] is the 3.x store text, and REST PATCH maps it to 409.
 *
 * [message] is the translated message: the INNERMOST SQL text for a persistence fault.
 */
object LegacyFaults {
    /** The store-style message of a unit fault (the 3.x version-mismatch text for `version_conflict`). */
    fun message(error: DomainError): String = if (isVersionConflict(error)) VersionConflictException.MESSAGE else error.message

    /** True when [error] is an optimistic-locking conflict (REST PATCH maps it to 409 `version_conflict`). */
    fun isVersionConflict(error: DomainError): Boolean = error.code == ErrorCode.VERSION_CONFLICT

    /** The MCP `DATABASE_ERROR` message of a site: `"<prefix>: <message>"`. */
    fun mcpMessage(
        prefix: String,
        error: DomainError
    ): String = "$prefix: ${message(error)}"

    /**
     * Bridge while the stores still return `Result` (removed when they throw): the [RepositoryError] a site's
     * existing mapping expects for a fault translated at the unit boundary. `version_conflict` becomes the
     * 3.x [RepositoryError.ConflictError]; every other fault a [RepositoryError.DatabaseError].
     */
    fun toRepositoryError(error: DomainError): RepositoryError =
        if (isVersionConflict(error)) {
            RepositoryError.ConflictError(VersionConflictException.MESSAGE)
        } else {
            RepositoryError.DatabaseError(error.message)
        }

    /**
     * Bridge while the stores still return `Result` (removed when they throw): the [DomainError] a unit block
     * returns as [Outcome.Err] when a store reported [error]. A unit must never continue to [Outcome.Ok]
     * after a store failure (the shared connection may already be rolled back).
     */
    fun storeFailure(error: RepositoryError): DomainError = DomainError(ErrorCode.INTERNAL, error.message.ifBlank { "Store failure" })

    /** [storeFailure] as an [Outcome.Err]. */
    fun <T> err(error: RepositoryError): Outcome<T> = Outcome.Err(storeFailure(error))

    /** [storeFailure] of a failed [Result] as an [Outcome.Err]. */
    fun <T> err(result: Result.Error): Outcome<T> = err(result.error)
}

/**
 * Bridge while the stores still return `Result` (removed when they throw): runs [block] as ONE write unit
 * labelled [op]. A [Result.Success] commits; a [Result.Error] rolls the unit back (a store failure never
 * continues to a commit) and is returned unchanged; a fault of the unit itself becomes a [Result.Error]
 * mapped by [LegacyFaults.toRepositoryError].
 */
suspend fun <T> UnitOfWork.writeResult(
    op: String,
    block: suspend WriteScope.() -> Result<T>
): Result<T> =
    writeUnit<Result<T>>(op, onFault = { Result.Error(LegacyFaults.toRepositoryError(it)) }) {
        when (val result = block()) {
            is Result.Success -> UnitResult.Commit(result)
            is Result.Error -> UnitResult.Rollback(result)
        }
    }
