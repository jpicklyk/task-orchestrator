package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome

/** What a [writeUnit] block decided: keep its writes ([Commit]) or discard them ([Rollback]), with a local result. */
sealed interface UnitResult<out R> {
    /** Commit the unit and return [value]. */
    data class Commit<out R>(
        val value: R
    ) : UnitResult<R>

    /** Roll the unit back and return [value] (a caller-local failure, e.g. a 3.x outcome type). */
    data class Rollback<out R>(
        val value: R
    ) : UnitResult<R>
}

/**
 * Runs [block] as one write unit labelled [op] for callers whose result is a LOCAL type rather than an
 * [Outcome]: [UnitResult.Commit] commits and returns its value; [UnitResult.Rollback] rolls the unit back
 * (an [Outcome.Err] to the unit; joined, the outermost unit decides) and returns its value. When the unit
 * fails (a store fault the boundary translated, a poisoned unit, a failed commit) [onFault] maps the
 * translated [DomainError] to the local result; any other exception the unit rethrows (a non-SQL store
 * exception, or any exception under a no-transaction unit of work) is caught HERE, outside the unit, and
 * mapped through [LegacyFaults.fault] to [onFault] too. Cancellation is rethrown.
 */
suspend fun <R> UnitOfWork.writeUnit(
    op: String,
    onFault: (DomainError) -> R,
    block: suspend WriteScope.() -> UnitResult<R>
): R {
    var rolledBack: UnitResult.Rollback<R>? = null
    val outcome =
        try {
            write(op) {
                rolledBack = null
                when (val decided = block()) {
                    is UnitResult.Commit -> Outcome.Ok(decided.value)
                    is UnitResult.Rollback -> {
                        rolledBack = decided
                        Outcome.Err(DomainError(ErrorCode.INTERNAL, "Unit '$op' rolled back by its caller"))
                    }
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            return onFault(LegacyFaults.fault(e))
        }
    return when (outcome) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> {
            val decided = rolledBack
            if (decided != null) decided.value else onFault(outcome.error)
        }
    }
}

/**
 * Runs [block] as one write unit labelled [op] and returns its value as [Outcome.Ok]; a store fault
 * (translated at the unit boundary, or thrown and mapped by [LegacyFaults.fault]) rolls the unit back
 * and becomes [Outcome.Err]. Cancellation is rethrown.
 */
suspend fun <T> UnitOfWork.writeOutcome(
    op: String,
    block: suspend WriteScope.() -> T
): Outcome<T> = writeUnit(op, onFault = { Outcome.Err(it) }) { UnitResult.Commit(Outcome.Ok(block())) }

/**
 * The legacy WRITE mapper: runs [block] as one write unit labelled [op] ([writeOutcome]) and returns its
 * value; a store fault hands its [DomainError] to [onFault] (the legacy message is [LegacyFaults.message]),
 * which returns the site's existing error response (typically a non-local `return`).
 */
suspend inline fun <T> UnitOfWork.legacyWrite(
    op: String,
    onFault: (error: DomainError) -> Nothing,
    noinline block: suspend WriteScope.() -> T
): T =
    when (val outcome = writeOutcome(op, block)) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> onFault(outcome.error)
    }
