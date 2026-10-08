package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.application.config.ConfigSession
import io.github.jpicklyk.mcptask.current.application.port.ActiveUnit
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.UnitElement
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.time.SystemClock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Runs units of work against the [DatabaseManager] pools. With the helpers in `TransactionHelper.kt`
 * it is the only code that opens Exposed transactions.
 *
 * - **Writes** hold the in-process writer [Mutex] for each ATTEMPT, so contention queues fairly and
 *   cancellably instead of surfacing as pool-checkout timeouts.
 * - **Retry.** SQLITE_BUSY/LOCKED re-runs the WHOLE unit (fresh Exposed transaction, fresh
 *   [ConfigSession], fresh hooks, `now` re-read) with jittered exponential backoff until [deadline].
 * - **Translation.** An explicit write ([runWrite]) turns persistence faults into [DomainError]s via
 *   [PersistenceFaults]; every other exception rolls back and is rethrown unchanged. Implicit units
 *   (a store call outside any unit) rethrow the final exception so today's `Result.Error` mapping
 *   in the stores is unchanged.
 * - **Hooks.** `afterCommit`/`afterRollback` hooks run once, on the outermost unit, never for an
 *   attempt abandoned by a BUSY retry, and never when the coroutine was cancelled.
 */
class UnitRunner internal constructor(
    private val dbs: DatabaseManager
) {
    private val logger = LoggerFactory.getLogger(UnitRunner::class.java)
    private val writerMutex = Mutex()
    private val outsideWrites = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Total time a unit may spend on BUSY retries AND on waiting for the in-process writer lock before it
     * surfaces as `unavailable`. Adjustable for tests. It is a soft bound: an attempt already running is never
     * force-cancelled (that would skip rollback hooks), so the unit can overrun by up to ~1.375 s: the maximum
     * backoff (250 ms x jitter up to 1.5) plus the pool's 1 s busy_timeout on BEGIN, plus the attempt's own
     * statement time.
     */
    @Volatile
    var deadline: Duration = DEFAULT_DEADLINE

    /**
     * Store writes made OUTSIDE an explicit unit (implicit units), by `op`. The first occurrence per
     * op is logged at WARN, later ones at DEBUG. P5b turns this into a hard failure.
     */
    val outsideUnitWrites: Map<String, Long> get() = outsideWrites.mapValues { it.value.get() }

    /** One explicit write unit. A block that returns [Outcome.Err] rolls the transaction back. */
    internal suspend fun <T> runWrite(
        op: String,
        clock: Clock,
        block: suspend (ActiveUnit) -> Outcome<T>
    ): Outcome<T> =
        when (
            val driven =
                drive(write = true, clock = clock, freshConfig = true) { unit ->
                    when (val outcome = block(unit)) {
                        is Outcome.Err -> throw UnitRollback(outcome.error)
                        is Outcome.Ok -> outcome
                    }
                }
        ) {
            is Driven.Done -> {
                fireCommit(driven.unit)
                driven.value
            }
            is Driven.RolledBack -> {
                fireRollback(driven.unit, driven.error)
                Outcome.Err(driven.error)
            }
            is Driven.Faulted -> {
                val cause = driven.cause
                val error =
                    if (cause is WriterLockTimeout) {
                        DomainError(
                            ErrorCode.UNAVAILABLE,
                            cause.message ?: "Writer lock not available",
                            ErrorDetail.Unavailable(PersistenceFaults.BUSY_RETRY_AFTER_MS)
                        )
                    } else {
                        PersistenceFaults.translate(cause)
                    }
                if (error == null) {
                    fireRollback(driven.unit, null)
                    throw driven.cause
                }
                logTranslated(op, error, driven.cause)
                fireRollback(driven.unit, error)
                Outcome.Err(error)
            }
        }

    /** One explicit read unit on the reader pool. BUSY retries; any other failure rethrows. */
    internal suspend fun <T> runRead(
        clock: Clock,
        block: suspend (ActiveUnit) -> T
    ): T =
        when (val driven = drive(write = false, clock = clock, freshConfig = true) { unit -> block(unit) }) {
            is Driven.Done -> driven.value
            is Driven.RolledBack -> error("a read unit cannot roll back by signal")
            is Driven.Faulted -> throw driven.cause
        }

    /** A store write made outside any unit: a counted, implicit write unit that rethrows the final exception. */
    suspend fun <T> implicitWrite(
        op: String,
        block: suspend JdbcTransaction.() -> T
    ): T {
        countOutsideWrite(op)
        return implicit(write = true, block = block)
    }

    /** A store read made outside any unit: an implicit read on the reader pool. */
    suspend fun <T> implicitRead(block: suspend JdbcTransaction.() -> T): T = implicit(write = false, block = block)

    private suspend fun <T> implicit(
        write: Boolean,
        block: suspend JdbcTransaction.() -> T
    ): T =
        when (val driven = drive(write = write, clock = SystemClock, freshConfig = false) { _ -> block() }) {
            is Driven.Done -> {
                fireCommit(driven.unit)
                driven.value
            }
            is Driven.RolledBack -> error("an implicit unit cannot roll back by signal")
            is Driven.Faulted -> {
                fireRollback(driven.unit, PersistenceFaults.translate(driven.cause))
                throw driven.cause
            }
        }

    private fun countOutsideWrite(op: String) {
        val count = outsideWrites.computeIfAbsent(op) { AtomicLong() }.incrementAndGet()
        if (count == 1L) {
            logger.warn("Store write '{}' ran outside a unit of work (implicit unit)", op)
        } else {
            logger.debug("Store write '{}' ran outside a unit of work (implicit unit, #{})", op, count)
        }
    }

    private sealed interface Driven<out R> {
        data class Done<R>(
            val value: R,
            val unit: ActiveUnit
        ) : Driven<R>

        data class RolledBack(
            val error: DomainError,
            val unit: ActiveUnit
        ) : Driven<Nothing>

        data class Faulted(
            val cause: Throwable,
            val unit: ActiveUnit
        ) : Driven<Nothing>
    }

    /** Thrown inside the transaction to roll it back when the block returned [Outcome.Err]. */
    private class UnitRollback(
        val error: DomainError
    ) : RuntimeException(error.message, null, false, false)

    private suspend fun <R> drive(
        write: Boolean,
        clock: Clock,
        freshConfig: Boolean,
        body: suspend JdbcTransaction.(ActiveUnit) -> R
    ): Driven<R> {
        val started = TimeSource.Monotonic.markNow()
        var attempt = 0
        var lastBusy: Throwable? = null
        while (true) {
            val unit = ActiveUnit(writable = write, now = clock.now())
            try {
                val value =
                    if (write) {
                        // Evict inside the lock, so no other writer can check out the poisoned connection first.
                        acquireWriter(started)
                        try {
                            transactEvictingOnFault(dbs.writer(), write = true, unit, freshConfig, body)
                        } finally {
                            writerMutex.unlock()
                        }
                    } else {
                        transactEvictingOnFault(dbs.reader(), write = false, unit, freshConfig, body)
                    }
                return Driven.Done(value, unit)
            } catch (e: UnitRollback) {
                return Driven.RolledBack(e.error, unit)
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                // A retry that reaches the lock with its budget already spent by BUSY retries is BUSY exhaustion.
                val busy = lastBusy
                if (e is WriterLockTimeout && busy != null) return Driven.Faulted(busy, unit)
                if (PersistenceFaults.isBusy(e) && started.elapsedNow() < deadline) {
                    lastBusy = e
                    logger.debug("Unit attempt {} hit SQLITE_BUSY; retrying: {}", attempt + 1, e.message)
                    backoff(attempt)
                    attempt++
                    continue
                }
                return Driven.Faulted(e, unit)
            }
        }
    }

    /**
     * Takes the writer lock, waiting at most the REMAINING unit [deadline] (time since [started]). A lock that
     * never frees (e.g. a unit whose [UnitElement] was lost and re-entered the writer) becomes [WriterLockTimeout],
     * which surfaces as `unavailable` instead of hanging. The wait is bounded, but an attempt already past the
     * lock is not: see the overrun note on [deadline].
     */
    private suspend fun acquireWriter(started: TimeMark) {
        if (writerMutex.tryLock()) return
        val remaining = deadline - started.elapsedNow()
        // Ownership rule (kotlinx "Asynchronous timeout and resources"): once lock() has returned, `locked` is set
        // in straight-line code and this coroutine owns the Mutex however withTimeoutOrNull then exits. A timeout
        // delivered after the grant makes it return null; an outer-job cancellation after the grant makes it
        // THROW. If this function returns normally, drive's finally unlocks. If it throws while `locked`, the
        // catch below unlocks before rethrowing; drive's try is never entered, so there is exactly one unlock.
        // A lock() that itself throws never granted the Mutex, so `locked` stays false and nothing is unlocked.
        var locked = false
        try {
            if (remaining.isPositive()) {
                withTimeoutOrNull(remaining) {
                    writerMutex.lock()
                    locked = true
                }
            }
        } catch (e: Throwable) {
            if (locked) writerMutex.unlock()
            throw e
        }
        if (!locked) throw WriterLockTimeout(deadline)
    }

    /** The in-process writer lock stayed held past the unit deadline. */
    private class WriterLockTimeout(
        deadline: Duration
    ) : RuntimeException("Writer lock not available within $deadline", null, false, false)

    /**
     * Runs one attempt and, when it fails with a connection-level persistence fault (BUSY/LOCKED, pool timeout),
     * evicts the pool's connections. sqlite-jdbc keeps no usable transaction after a failed BEGIN (e.g. SQLITE_BUSY
     * from another process): the pooled connection would otherwise fail every later commit with "no transaction is
     * active". Constraint (duplicate/FK) and other SQL faults leave the connection healthy and do not evict.
     */
    private suspend fun <R> transactEvictingOnFault(
        db: Database,
        write: Boolean,
        unit: ActiveUnit,
        freshConfig: Boolean,
        body: suspend JdbcTransaction.(ActiveUnit) -> R
    ): R =
        try {
            transact(db, unit, freshConfig, body)
        } catch (e: UnitRollback) {
            throw e
        } catch (e: Throwable) {
            e.rethrowIfCancellation()
            val fault = PersistenceFaults.classify(e)
            if (fault == PersistenceFaults.Fault.BUSY || fault == PersistenceFaults.Fault.POOL_TIMEOUT) dbs.evictConnections(write)
            throw e
        }

    private suspend fun <R> transact(
        db: Database,
        unit: ActiveUnit,
        freshConfig: Boolean,
        body: suspend JdbcTransaction.(ActiveUnit) -> R
    ): R {
        // Captured so a cancellation raised OUTSIDE the block (Exposed's own await/commit/close steps, e.g. an SSE
        // client disconnecting mid-read) can still release the connection. Exposed leaks it otherwise.
        var opened: JdbcTransaction? = null
        try {
            return suspendTransaction(db = db) {
                val tx = this
                opened = tx
                val context: CoroutineContext = if (freshConfig) UnitElement(unit) + ConfigSession() else UnitElement(unit)
                withContext(context) { tx.body(unit) }
            }
        } catch (e: CancellationException) {
            // Exposed does not roll back or close the transaction when the coroutine is cancelled after a
            // statement ran: the connection would stay checked out of its pool (a writer would hold the lock) and the
            // transaction would stay bound to the thread. Release both here, then rethrow.
            val leaked = opened
            if (leaked != null) withContext(NonCancellable) { abandon(leaked) }
            throw e
        }
    }

    private fun abandon(tx: JdbcTransaction) {
        try {
            tx.rollback()
        } catch (ignored: Exception) {
            ignored.rethrowIfCancellation()
            logger.debug("Rollback of a cancelled unit failed: {}", ignored.message)
        }
        try {
            tx.close()
        } catch (ignored: Exception) {
            ignored.rethrowIfCancellation()
            logger.debug("Close of a cancelled unit failed: {}", ignored.message)
        }
    }

    private suspend fun backoff(attempt: Int) {
        val exponent = attempt.coerceAtMost(MAX_BACKOFF_EXPONENT)
        val base = (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
        delay((base * (0.5 + Random.nextDouble())).toLong().coerceAtLeast(1L))
    }

    private suspend fun fireCommit(unit: ActiveUnit) {
        val hooks = unit.commitHooks()
        if (hooks.isEmpty()) return
        withContext(NonCancellable) {
            for (hook in hooks) {
                try {
                    hook()
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logger.warn("afterCommit hook failed; the unit's outcome is unchanged: {}", e.message, e)
                }
            }
        }
    }

    private suspend fun fireRollback(
        unit: ActiveUnit,
        error: DomainError?
    ) {
        val hooks = unit.rollbackHooks()
        if (hooks.isEmpty()) return
        withContext(NonCancellable) {
            for (hook in hooks) {
                try {
                    hook(error)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logger.warn("afterRollback hook failed: {}", e.message, e)
                }
            }
        }
    }

    private fun logTranslated(
        op: String,
        error: DomainError,
        cause: Throwable
    ) {
        when (PersistenceFaults.classify(cause)) {
            PersistenceFaults.Fault.POOL_TIMEOUT, PersistenceFaults.Fault.READONLY ->
                logger.error("Unit {} failed with {}: {}", op, error.code.wire, error.message, cause)
            PersistenceFaults.Fault.BUSY -> logger.warn("Unit {} gave up after {}: {}", op, deadline, error.message)
            else -> logger.warn("Unit {} failed with {}: {}", op, error.code.wire, error.message)
        }
    }

    private companion object {
        val DEFAULT_DEADLINE: Duration = 10.seconds
        const val BASE_DELAY_MS = 10L
        const val MAX_DELAY_MS = 250L
        const val MAX_BACKOFF_EXPONENT = 10
    }
}
