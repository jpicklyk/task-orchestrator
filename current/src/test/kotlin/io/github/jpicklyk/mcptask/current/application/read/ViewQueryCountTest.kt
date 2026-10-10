package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.api.PageRequest
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Priority
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
import kotlin.test.assertTrue

/**
 * Q14 test-plan S10 (NEW-SURFACE): every [WorkViewQueries] view runs a CONSTANT number of SQL statements, independent
 * of how many rows, children, edges, claims and notes the database holds.
 *
 * Oracle: plan 3.3 / task-scope Build 1 ("each inside ONE read unit with a constant number of statements
 * (independent of rows, children, edges, claims)"). The expected value is not a number taken from the code: it is the
 * statement count at scale 1 compared with the count at scale 40 over the same kind of data.
 *
 * Method (performance-baseline measurement plan, item 1): an Exposed [StatementInterceptor] is registered on the
 * transaction of an OUTER read unit, and the view runs inside it (a nested read joins the outer unit), so nothing in
 * production is instrumented. Scale N means N copies of one "kit" that contains every kind of data a view reads (all
 * five roles, a live claim, an expired claim, a claimed ancestor, a satisfied and an unsatisfied dependency, notes on
 * active items, a terminal parent with an open descendant), so scale 1 already exercises every branch and the two
 * scales differ only in multiplicity. Each view is run once before the counted run, so one-time warm-up work is not
 * counted. Statements issued through raw JDBC rather than Exposed's executor are not seen by the interceptor; the
 * control test below proves the interceptor sees ordinary repository reads.
 */
class ViewQueryCountTest {
    private val clock = SettableClock()
    private val t: Instant = clock.now()

    private class StatementCounter : StatementInterceptor {
        val sql = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            sql += runCatching { context.sql(transaction) }.getOrDefault("<unprintable>")
        }

        val count: Int get() = sql.size
    }

    private data class Counted(
        val count: Int,
        val mostRepeated: String
    )

    private suspend fun countStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Unit
    ): Counted =
        d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            val top =
                counter.sql
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
            Counted(counter.count, top?.let { "${it.value}x ${it.key.take(160)}" } ?: "<none>")
        }

    private class Seeded(
        val anchor: UUID,
        val firstKitRoot: UUID,
        val terminalRoot: UUID,
        val claimedRoot: UUID
    )

    /** [scale] copies of the kit, plus one anchor container with [scale] children. Everything seeds in one write unit. */
    private suspend fun seed(
        d: SqliteTestDatabase,
        scale: Int
    ): Seeded {
        val repo = d.repositoryProvider().workItemRepository()
        val deps = d.repositoryProvider().dependencyRepository()
        val notes = d.repositoryProvider().noteRepository()
        lateinit var seeded: Seeded

        suspend fun make(
            title: String,
            role: Role = Role.QUEUE,
            parent: WorkItem? = null,
            priority: Priority = Priority.MEDIUM,
            claimedBy: String? = null,
            claimExpiresAt: Instant? = null
        ): WorkItem {
            val claimedAt = claimExpiresAt?.minusSeconds(60)
            return repo.create(
                WorkItem(
                    title = title,
                    role = role,
                    parentId = parent?.id,
                    depth = (parent?.depth ?: -1) + 1,
                    priority = priority,
                    complexity = 3,
                    claimedBy = claimedBy,
                    claimedAt = claimedAt,
                    claimExpiresAt = claimExpiresAt,
                    originalClaimedAt = claimedAt
                )
            )
        }

        d.unitOfWork().inUnit {
            val anchor = make("anchor")
            var firstKitRoot: WorkItem? = null
            var terminalRoot: WorkItem? = null
            var claimedRoot: WorkItem? = null
            for (k in 0 until scale) {
                val root = make("kit $k root")
                val queued = make("kit $k queued", parent = root)
                val working = make("kit $k working", Role.WORK, root)
                make("kit $k review", Role.REVIEW, root)
                make("kit $k held", Role.BLOCKED, root)
                val finishedWithOpen = make("kit $k terminal with open descendant", Role.TERMINAL, root)
                make("kit $k open descendant", Role.QUEUE, finishedWithOpen)
                val done = make("kit $k terminal", Role.TERMINAL, root)
                make("kit $k live claim", Role.QUEUE, root, claimedBy = "agent-$k", claimExpiresAt = t.plusSeconds(300))
                make("kit $k expired claim", Role.QUEUE, root, claimedBy = "agent-x$k", claimExpiresAt = t.minusSeconds(1))
                val blockedByOpen = make("kit $k blocked by open work", Role.QUEUE, root)
                val blockedBySatisfied = make("kit $k blocked by finished work", Role.QUEUE, root)
                deps.create(Dependency(fromItemId = working.id, toItemId = blockedByOpen.id))
                deps.create(Dependency(fromItemId = done.id, toItemId = blockedBySatisfied.id))
                notes.upsert(Note(itemId = working.id, key = "session-tracking", role = "work", body = "note $k"))
                notes.upsert(Note(itemId = queued.id, key = "task-scope", role = "queue", body = "scope $k"))

                val terminalKitRoot = make("kit $k terminal root", Role.TERMINAL)
                make("kit $k under terminal root", Role.QUEUE, terminalKitRoot)

                val heldRoot = make("kit $k claimed root", Role.WORK, claimedBy = "holder-$k", claimExpiresAt = t.plusSeconds(300))
                make("kit $k shielded child", Role.QUEUE, heldRoot)

                if (firstKitRoot == null) {
                    firstKitRoot = root
                    terminalRoot = terminalKitRoot
                    claimedRoot = heldRoot
                }

                val section = make("anchor child $k", parent = anchor)
                make("anchor grandchild $k a", Role.TERMINAL, section)
                make("anchor grandchild $k b", Role.QUEUE, section, claimedBy = "agent-a$k", claimExpiresAt = t.plusSeconds(300))
            }
            seeded = Seeded(anchor.id, checkNotNull(firstKitRoot).id, checkNotNull(terminalRoot).id, checkNotNull(claimedRoot).id)
        }
        return seeded
    }

    private fun view(
        name: String,
        run: suspend (WorkViewQueries) -> Unit
    ): Pair<String, suspend (WorkViewQueries) -> Unit> = name to run

    private fun views(s: Seeded): List<Pair<String, suspend (WorkViewQueries) -> Unit>> {
        val page = PageRequest(limit = 200)
        return listOf(
            view("next default") { q -> q.next(NextItemRecommender.Criteria(), page) },
            view("next as agent, subtree scope, by oldest") { q ->
                q.next(
                    NextItemRecommender.Criteria(
                        requestingAgentId = "agent-0",
                        ancestorIds = setOf(s.anchor, s.firstKitRoot),
                        orderBy = NextItemOrder.OLDEST_FIRST
                    ),
                    page
                )
            },
            view("next includeClaimed") { q -> q.next(NextItemRecommender.Criteria(), page, includeClaimed = true) },
            view("next role work") { q -> q.next(NextItemRecommender.Criteria(role = Role.WORK), page) },
            view("blocked unscoped") { q -> q.blocked(BlockedScope(), page) },
            view("blocked by parent") { q -> q.blocked(BlockedScope(parentId = s.firstKitRoot), page) },
            view("blocked by ancestor") { q -> q.blocked(BlockedScope(ancestorId = s.anchor), page) },
            view("overview global") { q -> q.overview(OverviewRequest(page = page)) },
            view("overview global excludeTerminal includeChildren") { q ->
                q.overview(OverviewRequest(page = page, excludeTerminal = true, includeChildren = true))
            },
            view("overview scoped") { q -> q.overview(OverviewRequest(itemId = s.firstKitRoot, page = page)) },
            view("overview scoped excludeTerminal") { q ->
                q.overview(OverviewRequest(itemId = s.firstKitRoot, page = page, excludeTerminal = true))
            },
            view("overview anchored") { q -> q.overview(OverviewRequest(anchorId = s.anchor, page = page)) },
            view("overview anchored excludeTerminal") { q ->
                q.overview(OverviewRequest(anchorId = s.anchor, page = page, excludeTerminal = true))
            },
            view("context unscoped") { q -> q.context(ContextScope(), page) },
            view("context scoped to anchor") { q -> q.context(ContextScope(ancestorId = s.anchor), page) },
            view("context scoped to claimed root") { q -> q.context(ContextScope(ancestorId = s.claimedRoot), page) }
        )
    }

    private suspend fun measure(scale: Int): Map<String, Counted> {
        val result = linkedMapOf<String, Counted>()
        SqliteTestDatabase.open(clock = clock).use { d ->
            val seeded = seed(d, scale)
            val queries = WorkViewQueries(d.unitOfWork())
            for ((name, run) in views(seeded)) {
                run(queries) // warm-up, not counted
                result[name] = countStatements(d) { run(queries) }
            }
        }
        return result
    }

    @Test
    fun `S10 every view issues the same number of statements at scale 1 and at scale 40`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            val mismatches =
                small.keys.filter { small.getValue(it).count != large.getValue(it).count }.map {
                    "$it: scale 1 = ${small.getValue(
                        it
                    ).count}, scale 40 = ${large.getValue(it).count} (most repeated at scale 40: ${large.getValue(it).mostRepeated})"
                }
            assertTrue(mismatches.isEmpty(), "statement count must not depend on data volume:\n" + mismatches.joinToString("\n"))
            for ((name, counted) in small) {
                assertTrue(counted.count > 0, "the interceptor saw no statement for '$name', so the comparison proves nothing")
            }
        }

    @Test
    fun `S10 the same holds between scale 2 and scale 25, so equality is not a coincidence of the two chosen scales`(): Unit =
        runBlocking {
            val a = measure(2)
            val b = measure(25)

            val mismatches =
                a.keys
                    .filter {
                        a.getValue(it).count != b.getValue(it).count
                    }.map { "$it: ${a.getValue(it).count} vs ${b.getValue(it).count}" }
            assertTrue(mismatches.isEmpty(), "statement count must not depend on data volume:\n" + mismatches.joinToString("\n"))
        }

    @Test
    fun `S10 control the interceptor counts ordinary repository reads, so a per-row view would be caught`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val seeded = seed(d, 3)
                val repo = d.repositoryProvider().workItemRepository()
                val ids = listOf(seeded.anchor, seeded.firstKitRoot, seeded.terminalRoot, seeded.claimedRoot)

                val one = countStatements(d) { repo.getById(ids[0]) }
                val four = countStatements(d) { ids.forEach { repo.getById(it) } }

                assertTrue(one.count >= 1, "one read must be counted")
                assertTrue(four.count >= 4 * one.count, "four reads must count at least four times one read: ${one.count} vs ${four.count}")
            }
        }
}
