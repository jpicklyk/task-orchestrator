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
 * itself fails after the block returned (a translated persistence fault, a poisoned unit, a failed commit),
 * [onFault] maps the translated [DomainError] to the local result. Any other exception rolls back and is
 * rethrown.
 */
suspend fun <R> UnitOfWork.writeUnit(
    op: String,
    onFault: (DomainError) -> R,
    block: suspend WriteScope.() -> UnitResult<R>
): R {
    var rolledBack: UnitResult.Rollback<R>? = null
    val outcome =
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
    return when (outcome) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> {
            val decided = rolledBack
            if (decided != null) decided.value else onFault(outcome.error)
        }
    }
}
