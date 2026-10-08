package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.EventSink
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.EventRecorder
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant

private val logger = LoggerFactory.getLogger(UnscopedUnitOfWork::class.java)

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
    /** Resolved on first use, so a mocked provider without an event store still works for units that record nothing. */
    private val eventsLazy: Lazy<EventSink> = lazy { EventRecorder(repositories.eventStore(), clock) }

    override suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        val scope = HookScope(repositories, clock.now(), eventsLazy)
        val outcome =
            try {
                scope.block()
            } catch (e: Throwable) {
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

    override suspend fun <T> read(block: suspend ReadScope.() -> T): T = HookScope(repositories, clock.now(), eventsLazy).block()

    private class HookScope(
        override val stores: RepositoryProvider,
        override val now: Instant,
        private val sink: Lazy<EventSink>
    ) : WriteScope {
        override val events: EventSink get() = sink.value
        private val commitHooks = ArrayList<suspend () -> Unit>()
        private val rollbackHooks = ArrayList<suspend (DomainError?) -> Unit>()

        override fun afterCommit(fn: suspend () -> Unit) {
            commitHooks.add(fn)
        }

        override fun afterRollback(fn: suspend (DomainError?) -> Unit) {
            rollbackHooks.add(fn)
        }

        /** Runs non-cancellably; a failing hook is logged at WARN and does not change the outcome. */
        suspend fun commit() =
            withContext(NonCancellable) {
                for (hook in commitHooks) {
                    try {
                        hook()
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        logger.warn("afterCommit hook failed; the unit's outcome is unchanged: {}", e.message, e)
                    }
                }
            }

        suspend fun rollback(error: DomainError?) =
            withContext(NonCancellable) {
                for (hook in rollbackHooks) {
                    try {
                        hook(error)
                    } catch (e: Exception) {
                        e.rethrowIfCancellation()
                        logger.warn("afterRollback hook failed: {}", e.message, e)
                    }
                }
            }
    }
}
