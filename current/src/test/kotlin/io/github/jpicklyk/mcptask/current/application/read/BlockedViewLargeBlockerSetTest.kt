package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.port.PageRequest
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The `blocked` view issues the same number of statements for one page whose entries name more than 500 distinct
 * blockers as for a page with a single blocker: the blocker load is one id-set statement, not a chunked IN list
 * (review finding B1 of 671857b4). Statements are counted with an Exposed [StatementInterceptor] registered on an
 * outer read unit, the same mechanism as [ViewQueryCountTest].
 */
class BlockedViewLargeBlockerSetTest {
    private val clock = SettableClock()

    private class StatementCounter : StatementInterceptor {
        val sql = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext,
        ) {
            sql += runCatching { context.sql(transaction) }.getOrDefault("<unprintable>")
        }
    }

    /** [blockedCount] queue items, each blocked by [blockersEach] distinct open (work) items. */
    private suspend fun seed(
        d: SqliteTestDatabase,
        blockedCount: Int,
        blockersEach: Int,
    ) {
        val repo = d.repositoryProvider().workItemRepository()
        val deps = d.repositoryProvider().dependencyRepository()
        d.unitOfWork().inUnit {
            repeat(blockedCount) { b ->
                val blocked = repo.create(WorkItem(title = "blocked $b", role = Role.QUEUE, depth = 0))
                repeat(blockersEach) { k ->
                    val blocker = repo.create(WorkItem(title = "blocker $b.$k", role = Role.WORK, depth = 0))
                    deps.create(Dependency(fromItemId = blocker.id, toItemId = blocked.id))
                }
            }
        }
    }

    private suspend fun countBlockedStatements(
        blockedCount: Int,
        blockersEach: Int,
    ): Pair<Int, List<String>> =
        SqliteTestDatabase.open(clock = clock).use { d ->
            seed(d, blockedCount, blockersEach)
            val queries = WorkViewQueries(d.unitOfWork())
            val page = PageRequest(limit = 200)
            val warm = queries.blocked(BlockedScope(), page) // warm-up, not counted
            assertEquals(blockedCount, warm.items.size)
            assertEquals(blockedCount * blockersEach, warm.items.sumOf { it.blockers.size })
            assertEquals(
                blockedCount * blockersEach,
                warm.items.flatMap { it.blockers }.count { it.title != null && it.role == Role.WORK },
            )
            d.unitOfWork().read {
                val tx = TransactionManager.current()
                val counter = StatementCounter()
                tx.registerInterceptor(counter)
                try {
                    queries.blocked(BlockedScope(), page)
                } finally {
                    tx.unregisterInterceptor(counter)
                }
                counter.sql.size to counter.sql.toList()
            }
        }

    @Test
    fun `blocked view statement count does not grow past 500 distinct blockers on one page`(): Unit =
        runBlocking {
            val (single, _) = countBlockedStatements(blockedCount = 1, blockersEach = 1)
            val (large, largeSql) = countBlockedStatements(blockedCount = 200, blockersEach = 3)
            assertEquals(
                single,
                large,
                "a page naming 600 distinct blockers must issue as many statements as a 1-blocker page:\n" +
                    largeSql.joinToString("\n") { it.take(160) },
            )
        }
}
