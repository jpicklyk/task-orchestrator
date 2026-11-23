package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.model.error.Outcome

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
