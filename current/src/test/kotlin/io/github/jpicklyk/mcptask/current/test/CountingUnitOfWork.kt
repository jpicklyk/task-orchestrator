package io.github.jpicklyk.mcptask.current.test

import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * A test [UnitOfWork] decorator that counts OUTERMOST write units and tags the coroutine context of each with
 * its 1-based ordinal, so a spy can tell which top-level unit a store call ran in ([currentOrdinal]). A nested
 * write (inside an outer write of this decorator) joins the outer unit: it keeps the outer ordinal and is not
 * counted. [onOutermostWrite] runs at the start of every outermost write attempt, inside the unit, before the
 * caller's block (e.g. to place a "concurrent" write at unit-open time).
 */
class CountingUnitOfWork(
    private val delegate: UnitOfWork,
    private val onOutermostWrite: suspend (ordinal: Int) -> Unit = {}
) : UnitOfWork {
    private val writeCount = AtomicInteger()
    private val readCount = AtomicInteger()
    private val opLog = Collections.synchronizedList(ArrayList<String>())

    /** Number of outermost write units entered. */
    val writes: Int get() = writeCount.get()

    /** Number of outermost read units entered. */
    val reads: Int get() = readCount.get()

    /** The `op` label of each outermost write unit, in order. */
    val ops: List<String> get() = synchronized(opLog) { opLog.toList() }

    override suspend fun <T> write(
        op: String,
        block: suspend WriteScope.() -> Outcome<T>
    ): Outcome<T> {
        if (coroutineContext[Ordinal] != null) return delegate.write(op, block)
        val ordinal = writeCount.incrementAndGet()
        opLog.add(op)
        return delegate.write(op) {
            val scope = this
            withContext(Ordinal(ordinal)) {
                onOutermostWrite(ordinal)
                scope.block()
            }
        }
    }

    override suspend fun <T> read(block: suspend ReadScope.() -> T): T {
        if (coroutineContext[Ordinal] == null) readCount.incrementAndGet()
        return delegate.read(block)
    }

    private class Ordinal(
        val value: Int
    ) : AbstractCoroutineContextElement(Ordinal) {
        companion object Key : CoroutineContext.Key<Ordinal>
    }

    companion object {
        /** The ordinal of the outermost [CountingUnitOfWork] write unit the caller runs in, or null outside one. */
        suspend fun currentOrdinal(): Int? = coroutineContext[Ordinal]?.value
    }
}
