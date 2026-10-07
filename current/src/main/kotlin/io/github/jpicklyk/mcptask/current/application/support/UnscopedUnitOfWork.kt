package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.time.Instant

/**
 * A [UnitOfWork] with NO transaction: it runs the block directly and honours the hook contract
 * (commit hooks on [Outcome.Ok], rollback hooks on [Outcome.Err] or a throw). It exists so wiring that
 * has no database (unit tests with mocked stores) can still be handed a [UnitOfWork]; production
 * always uses the SQLite implementation, which gives the block real atomicity.
 */
class UnscopedUnitOfWork(
    private val repositories: RepositoryProvider,
    private val clock: Clock = Clock { Instant.now() }
) : UnitOfWork {
    override suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        val scope = HookScope(repositories, clock.now())
        val outcome =
            try {
                scope.block()
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                scope.rollback(null)
                throw e
            }
        when (outcome) {
            is Outcome.Ok -> scope.commit()
            is Outcome.Err -> scope.rollback(outcome.error)
        }
        return outcome
    }

    override suspend fun <T> read(block: suspend ReadScope.() -> T): T = HookScope(repositories, clock.now()).block()

    private class HookScope(
        override val repositories: RepositoryProvider,
        override val now: Instant
    ) : WriteScope {
        private val commitHooks = ArrayList<suspend () -> Unit>()
        private val rollbackHooks = ArrayList<suspend (DomainError?) -> Unit>()

        override fun afterCommit(fn: suspend () -> Unit) {
            commitHooks.add(fn)
        }

        override fun afterRollback(fn: suspend (DomainError?) -> Unit) {
            rollbackHooks.add(fn)
        }

        suspend fun commit() = commitHooks.forEach { it() }

        suspend fun rollback(error: DomainError?) = rollbackHooks.forEach { it(error) }
    }
}
