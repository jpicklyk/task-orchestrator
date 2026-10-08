package io.github.jpicklyk.mcptask.current.infrastructure.sqlite

import io.github.jpicklyk.mcptask.current.application.port.ActiveUnit
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.time.Instant
import kotlin.coroutines.coroutineContext

/**
 * SQLite implementation of [UnitOfWork] over the [DatabaseManager] pools.
 *
 * Under an ambient [UnitElement] a nested [write] or [read] JOINS it: the block runs directly in the
 * ambient transaction with the ambient unit's hooks and `now`. Otherwise the call becomes the
 * outermost unit, run by [UnitRunner].
 *
 * @param repositories the stores handed to scopes (the event-publishing decorated provider in production)
 */
class SqliteUnitOfWork(
    private val dbs: DatabaseManager,
    private val repositories: RepositoryProvider,
    private val clock: Clock
) : UnitOfWork {
    override suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        val ambient = coroutineContext[UnitElement]?.unit
        if (ambient != null) {
            check(ambient.writable) { "write '$op' attempted inside a read unit" }
            return UnitScope(repositories, ambient).block()
        }
        return dbs.units.runWrite(op, clock) { unit -> UnitScope(repositories, unit).block() }
    }

    override suspend fun <T> read(block: suspend ReadScope.() -> T): T {
        val ambient = coroutineContext[UnitElement]?.unit
        if (ambient != null) return ReadView(UnitScope(repositories, ambient)).block()
        return dbs.units.runRead(clock) { unit -> ReadView(UnitScope(repositories, unit)).block() }
    }
}

/** The scope of one unit attempt; hooks land on the [ActiveUnit], so joined scopes share the outermost unit's hooks. */
private class UnitScope(
    override val repositories: RepositoryProvider,
    private val unit: ActiveUnit
) : WriteScope {
    override val now: Instant get() = unit.now

    override fun afterCommit(fn: suspend () -> Unit) = unit.addCommit(fn)

    override fun afterRollback(fn: suspend (DomainError?) -> Unit) = unit.addRollback(fn)
}

/** A read-only view, so a read block cannot downcast its scope to a [WriteScope]. */
private class ReadView(
    scope: ReadScope
) : ReadScope by scope
