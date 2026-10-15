package io.github.jpicklyk.mcptask.current.test

import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * A passive, process-wide statement recorder for tests that must count the SQL a whole tool call issues (the production unit of work
 * cannot be wrapped from a test, so a per-transaction interceptor cannot be registered ahead of the call). Exposed discovers it through
 * `META-INF/services/org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor`. It records nothing unless a test is inside
 * [record]; outside it every statement passes untouched, so no other test is affected.
 *
 * Use only from a test that runs no concurrent database work of its own (the recorder is global to the JVM).
 */
class StatementTap : GlobalStatementInterceptor {
    override fun beforeExecution(
        transaction: Transaction,
        context: StatementContext
    ) {
        val sink = active.get() ?: return
        sink += runCatching { context.sql(transaction) }.getOrDefault("<unprintable>")
    }

    companion object {
        private val active = AtomicReference<MutableList<String>?>(null)

        /** Runs [block] and returns its value with the SQL text of every statement executed meanwhile, in order. */
        suspend fun <T> record(block: suspend () -> T): Pair<T, List<String>> {
            val sink: MutableList<String> = Collections.synchronizedList(ArrayList())
            check(active.compareAndSet(null, sink)) { "a statement recording is already running" }
            try {
                val value = block()
                return value to synchronized(sink) { sink.toList() }
            } finally {
                active.set(null)
            }
        }

        /** True for a SELECT whose FROM clause names [table] as its first (and only leading) table. */
        fun selectsFrom(
            sql: String,
            table: String
        ): Boolean = Regex("""(?is)^\s*SELECT\b.*?\bFROM\s+"?$table"?(?![A-Za-z0-9_])""").containsMatchIn(sql)

        /** True for an UPDATE of [table]. */
        fun updates(
            sql: String,
            table: String
        ): Boolean = Regex("""(?is)^\s*UPDATE\s+"?$table"?(?![A-Za-z0-9_])""").containsMatchIn(sql)
    }
}
