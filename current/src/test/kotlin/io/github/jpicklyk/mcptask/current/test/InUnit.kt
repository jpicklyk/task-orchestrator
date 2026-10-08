package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.support.UnscopedUnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.mockk.mockk

/**
 * Test stand-in for the removed `WorkItemRepository.inTransaction`: runs [block] as ONE write unit. The unit
 * commits when [block] returns; a throw rolls it back and propagates unchanged; a persistence fault the unit
 * translated into an error is rethrown as an [IllegalStateException] carrying the translated message.
 */
suspend fun UnitOfWork.inUnit(
    op: String = "Test.inUnit",
    block: suspend () -> Unit
) {
    when (
        val outcome =
            write(op) {
                block()
                Outcome.Ok(Unit)
            }
    ) {
        is Outcome.Ok -> Unit
        is Outcome.Err -> throw IllegalStateException(outcome.error.message)
    }
}

/**
 * A no-transaction [UnitOfWork] for tests whose stores are mocks (no database): blocks run directly, with the
 * hook contract honoured. Never use it over a real database; use `SqliteTestDatabase.unitOfWork()` there.
 */
fun unscopedUnitOfWork(): UnitOfWork = UnscopedUnitOfWork(mockk<RepositoryProvider>(relaxed = true))
