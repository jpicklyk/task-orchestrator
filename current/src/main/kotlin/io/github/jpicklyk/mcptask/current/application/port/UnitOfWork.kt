package io.github.jpicklyk.mcptask.current.application.port

import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The transaction boundary of the application layer.
 *
 * A unit is one database transaction. [write] runs [WriteScope] work on the single writer
 * connection and commits only when the block returns [Outcome.Ok]; [read] runs on the reader pool.
 * A nested [write] or [read] under an ambient [UnitElement] JOINS the ambient unit: same
 * transaction, no second connection, no retry, no commit of its own. The outermost block decides:
 * it rolls back when it returns [Outcome.Err] or throws.
 *
 * Persistence faults are translated once, at the outermost [write], into the error catalog
 * (`unavailable`, `duplicate`, `not_found`, `version_conflict`, `internal`); any other exception
 * rolls the unit back and is rethrown unchanged.
 *
 * Poisoned units: a store fault thrown inside a unit (even one a caller catches) has already rolled
 * the shared connection back. The unit records it, and the outermost [write] never commits after it:
 * an [Outcome.Ok] returned after a recorded fault becomes a rollback and the fault's translated
 * [Outcome.Err] (a non-persistence fault is rethrown). A nested [Outcome.Err] that was NOT a thrown
 * fault does not poison the unit; the outermost caller decides.
 */
interface UnitOfWork {
    suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T>

    suspend fun <T> read(block: suspend ReadScope.() -> T): T
}

/** What a read unit can see. The stores a scope exposes. */
interface ReadScope {
    val stores: RepositoryProvider

    /** Read once per unit attempt (re-read when a BUSY retry starts a fresh attempt). */
    val now: Instant
}

/** What a write unit can do on top of reading. */
interface WriteScope : ReadScope {
    /**
     * Runs [fn] after the unit COMMITS, in a non-cancellable context. A failure is logged at WARN
     * and does not change the [Outcome]. Registered from a joined scope, it fires once, on the
     * outermost unit's commit; registered by an attempt that is retried on BUSY, it never fires.
     */
    fun afterCommit(fn: suspend () -> Unit)

    /**
     * Runs [fn] after the unit ROLLS BACK, with the translated [DomainError], or null when a
     * non-persistence exception was rethrown. Skipped on cancellation and for an abandoned
     * (BUSY-retried) attempt. Same single-fire rule as [afterCommit].
     */
    fun afterRollback(fn: suspend (DomainError?) -> Unit)
}

/**
 * Coroutine-context carrier of the ambient unit. Opaque outside this module: only the
 * infrastructure unit runner creates it.
 */
class UnitElement internal constructor(
    internal val unit: ActiveUnit
) : AbstractCoroutineContextElement(UnitElement) {
    companion object Key : CoroutineContext.Key<UnitElement>
}

/**
 * Mutable state of one unit ATTEMPT: whether it can write, its [now], its registered hooks, and the
 * first store fault raised inside it. A BUSY retry creates a fresh instance, which is how abandoned
 * attempts lose their hooks and their recorded fault.
 */
internal class ActiveUnit(
    val writable: Boolean,
    val now: Instant
) {
    private val commitHooks = ArrayList<suspend () -> Unit>()
    private val rollbackHooks = ArrayList<suspend (DomainError?) -> Unit>()

    @Volatile
    private var fault: Throwable? = null

    /**
     * Records [t], thrown out of a store transaction JOINED to this unit. Exposed has then already rolled the
     * shared connection back, so the unit is poisoned: the runner never commits it (a later [Outcome.Ok]
     * becomes a rollback and an [Outcome.Err] of this fault). The first fault wins.
     */
    fun recordFault(t: Throwable) {
        synchronized(this) { if (fault == null) fault = t }
    }

    /** The first fault recorded by [recordFault] in this attempt, or null. */
    fun recordedFault(): Throwable? = fault

    fun addCommit(fn: suspend () -> Unit) {
        synchronized(commitHooks) { commitHooks.add(fn) }
    }

    fun addRollback(fn: suspend (DomainError?) -> Unit) {
        synchronized(rollbackHooks) { rollbackHooks.add(fn) }
    }

    fun commitHooks(): List<suspend () -> Unit> = synchronized(commitHooks) { commitHooks.toList() }

    fun rollbackHooks(): List<suspend (DomainError?) -> Unit> = synchronized(rollbackHooks) { rollbackHooks.toList() }
}
