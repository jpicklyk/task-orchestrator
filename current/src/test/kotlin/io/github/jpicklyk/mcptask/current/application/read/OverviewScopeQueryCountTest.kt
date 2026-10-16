package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.PageRequest
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
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 450aa813) for scenario S16 of the frozen test-plan (NEW-SURFACE): the overview
 * views keep a CONSTANT number of SQL statements when a restricted scope is applied, independent of how many visible and
 * invisible children, claims and descendants the database holds.
 *
 * Oracle: task-scope item 10 ("statement count per view stays constant") and the acceptance criterion "Overview statement
 * count stays constant with scope (Q14 `ViewQueryCountTest` style)". The expected value is not a number taken from the code: it
 * is the statement count at scale 1 compared with the count at scale 40 over the same kind of data, under the same scope.
 *
 * Method: as in `ViewQueryCountTest`. An Exposed [StatementInterceptor] is registered on the transaction of an OUTER read unit
 * and the view runs inside it; each view is run once before the counted run. Scale N means N copies of a kit holding visible
 * and invisible tagged children, a terminal child with an invisible open descendant, a live claim on a visible and on an
 * invisible item, so scale 1 already exercises every scope branch. A control proves the interceptor sees ordinary reads, and a
 * second control proves the scoped views really filter (a scoped view lists fewer entries than the unscoped one), so equality
 * of counts is not a coincidence of an inert scope. Forbidden-construct declaration: none.
 */
class OverviewScopeQueryCountTest {
    private val clock = SettableClock()
    private val t: Instant = clock.now()
    private val tagScope = AccessScope.principal(null, setOf("api"))

    private class StatementCounter : StatementInterceptor {
        var count = 0

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            count++
        }
    }

    private suspend fun countStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Unit
    ): Int =
        d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter.count
        }

    private class Seeded(
        val anchor: UUID,
        val container: UUID
    )

    private suspend fun seed(
        d: SqliteTestDatabase,
        scale: Int
    ): Seeded {
        val repo = d.repositoryProvider().workItemRepository()
        lateinit var seeded: Seeded

        suspend fun make(
            title: String,
            role: Role = Role.QUEUE,
            parent: WorkItem? = null,
            tags: String? = null,
            claimedBy: String? = null,
            id: UUID = UUID.randomUUID()
        ): WorkItem {
            val expires = if (claimedBy != null) t.plusSeconds(300) else null
            val claimedAt = expires?.minusSeconds(60)
            return repo.create(
                WorkItem(
                    title = title,
                    role = role,
                    parentId = parent?.id,
                    depth = (parent?.depth ?: -1) + 1,
                    id = id,
                    rootId = parent?.let { it.rootId ?: it.id } ?: id,
                    tags = tags,
                    claimedBy = claimedBy,
                    claimedAt = claimedAt,
                    claimExpiresAt = expires,
                    originalClaimedAt = claimedAt
                )
            )
        }

        d.unitOfWork().inUnit {
            val anchor = make("anchor", tags = "api")
            val container = make("container", tags = "api")
            for (k in 0 until scale) {
                make("visible $k", parent = container, tags = "api")
                make("visible claimed $k", Role.WORK, container, "api", claimedBy = "agent-v$k")
                make("hidden $k", parent = container, tags = "beta")
                make("hidden claimed $k", Role.WORK, container, "beta", claimedBy = "agent-h$k")
                val parked = make("parked $k", Role.TERMINAL, container, "api")
                make("invisible open below $k", Role.QUEUE, parked, "beta")
                val section = make("section $k", parent = anchor, tags = "api")
                make("section child $k", Role.TERMINAL, section, "api")
                make("section hidden $k", Role.QUEUE, section, "beta")
                make("hidden root $k", tags = "beta")
            }
            seeded = Seeded(anchor.id, container.id)
        }
        return seeded
    }

    private fun views(
        s: Seeded,
        scope: AccessScope
    ): List<Pair<String, suspend (WorkViewQueries) -> Unit>> {
        val page = PageRequest(limit = 200)
        return listOf<Pair<String, suspend (WorkViewQueries) -> Unit>>(
            "global" to { q -> q.overview(OverviewRequest(page = page, scope = scope)) },
            "global excludeTerminal includeChildren" to { q ->
                q.overview(OverviewRequest(page = page, excludeTerminal = true, includeChildren = true, scope = scope))
            },
            "scoped" to { q -> q.overview(OverviewRequest(itemId = s.container, page = page, scope = scope)) },
            "scoped excludeTerminal" to { q ->
                q.overview(OverviewRequest(itemId = s.container, page = page, excludeTerminal = true, scope = scope))
            },
            "anchored" to { q -> q.overview(OverviewRequest(anchorId = s.anchor, page = page, scope = scope)) },
            "anchored excludeTerminal" to { q ->
                q.overview(OverviewRequest(anchorId = s.anchor, page = page, excludeTerminal = true, scope = scope))
            }
        )
    }

    private suspend fun measure(
        scale: Int,
        scope: AccessScope
    ): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        SqliteTestDatabase.open(clock = clock).use { d ->
            val seeded = seed(d, scale)
            val queries = WorkViewQueries(d.unitOfWork())
            for ((name, run) in views(seeded, scope)) {
                run(queries)
                result[name] = countStatements(d) { run(queries) }
            }
        }
        return result
    }

    @Test
    fun `S16 every overview view issues the same number of statements at scale 1 and 40 under a tag scope`(): Unit =
        runBlocking {
            val small = measure(1, tagScope)
            val large = measure(40, tagScope)

            val mismatches = small.keys.filter { small.getValue(it) != large.getValue(it) }.map { "$it: ${small[it]} vs ${large[it]}" }
            assertTrue(
                mismatches.isEmpty(),
                "statement count must not depend on data volume under a scope:\n" + mismatches.joinToString("\n")
            )
            for ((name, count) in small) assertTrue(count > 0, "the interceptor saw no statement for '$name'")
        }

    @Test
    fun `S16 the same holds under a root scope`(): Unit =
        runBlocking {
            val a = measureRootScoped(2)
            val b = measureRootScoped(25)

            val mismatches = a.keys.filter { a.getValue(it) != b.getValue(it) }.map { "$it: ${a[it]} vs ${b[it]}" }
            assertTrue(
                mismatches.isEmpty(),
                "statement count must not depend on data volume under a root scope:\n" + mismatches.joinToString("\n")
            )
            assertEquals(a.keys, b.keys)
        }

    private suspend fun measureRootScoped(scale: Int): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        SqliteTestDatabase.open(clock = clock).use { d ->
            val seeded = seed(d, scale)
            val scope = AccessScope.principal(setOf(seeded.anchor), emptySet())
            val queries = WorkViewQueries(d.unitOfWork())
            for ((name, run) in views(seeded, scope)) {
                run(queries)
                result[name] = countStatements(d) { run(queries) }
            }
        }
        return result
    }

    @Test
    fun `S16 control - the scope really filters, so equal counts are not an inert scope`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val seeded = seed(d, 5)
                val queries = WorkViewQueries(d.unitOfWork())
                val unscoped = queries.overview(OverviewRequest(itemId = seeded.container, page = PageRequest(limit = 200)))
                val scoped =
                    queries.overview(OverviewRequest(itemId = seeded.container, page = PageRequest(limit = 200), scope = tagScope))

                assertTrue(unscoped.toString() != scoped.toString(), "a tag scope must change the container overview")
            }
        }

    @Test
    fun `S16 control - the interceptor counts ordinary repository reads`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val seeded = seed(d, 3)
                val repo = d.repositoryProvider().workItemRepository()

                val one = countStatements(d) { repo.getById(seeded.anchor) }
                val three = countStatements(d) { repeat(3) { repo.getById(seeded.anchor) } }

                assertTrue(one >= 1)
                assertTrue(three >= 3 * one, "three reads count at least three times one: $one vs $three")
            }
        }
}
