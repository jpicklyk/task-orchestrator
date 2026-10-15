package io.github.jpicklyk.mcptask.current.application.read

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.parity.PARITY_NOW
import io.github.jpicklyk.mcptask.current.parity.ParitySeeder
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item fca8ce36, R25) for scenario S13 (NEW-SURFACE): `RunFrontierQueries.frontier` runs a CONSTANT
 * number of SQL statements, independent of how many members, seats, claims and edges the run holds.
 *
 * Oracle: R25 and the performance-baseline note ("constant statements, about 6-8 regardless of size ... the danger is accidental per-member
 * statements ... guarded by the statement-count test"; the membership cap 500 equals the IN chunk size, so even a full run is one batch). The
 * expected value is not a number taken from the code: it is the statement count at scale 1 compared with the count at a large scale over the
 * same kind of data, plus the coarse ceiling the baseline note sets (a handful of statements; 16 allows for the two visibility reads of a
 * restricted scope).
 *
 * Method (the pattern of `ViewQueryCountTest`): an Exposed [StatementInterceptor] is registered on the transaction of an OUTER read unit and the
 * query runs inside it (a nested read joins the outer unit), so nothing in production is instrumented. Scale N means N copies of one kit of 10
 * members that contains every kind of data the query reads: a ready member, a queued member and a working member each with an active seat (one
 * long overdue), a member behind an open blocker and one behind a finished blocker, a BLOCKED member, a TERMINAL member, a member with a live
 * foreign claim, one with an expired claim and one with a claim held by the run coordinator. Scale 1 already exercises every branch, so the
 * scales differ only in multiplicity; scale 50 is the membership cap of 500. One call before the counted one warms the connection. Besides
 * the counts, every scale also asserts the answer the kit must produce (4 ready, 2 in flight, 2 waiting per kit), so the comparison cannot
 * pass on a query that returns nothing.
 *
 * Negative-assertion conditions: the interceptor counts a positive number of statements at every scale (a silent interceptor would equate
 * two zeros), and the per-kit answer sizes would differ if a branch of the kit were not reached.
 *
 * Domain invariants satisfied by construction: items are children of one root and stamped with its id; the run, its members and its coordinator
 * seat are seeded by `ParitySeeder.run` (state fields derived from the status); the claim of an item satisfies the all-or-none claim invariant
 * (claimedAt, expiry and original claim time derived from one expiry); the coordinator-held claim names the coordinator seat id the seeder derives.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class RunFrontierQueryCountTest {
    private val clock = SettableClock(PARITY_NOW)
    private val now: Instant = PARITY_NOW

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
        val mostRepeated: String,
        val frontier: RunFrontier?
    )

    private class Seeded(
        val runId: UUID,
        val rootId: UUID
    )

    private suspend fun seed(
        d: SqliteTestDatabase,
        kits: Int
    ): Seeded {
        val repo = d.repositoryProvider().workItemRepository()
        val deps = d.repositoryProvider().dependencyRepository()
        val provider = d.repositoryProvider()
        val seeder = ParitySeeder(repo, deps, provider, d.unitOfWork())
        val runId = UUID.nameUUIDFromBytes("frontier-count-run".toByteArray())
        val coordinatorSeatId = UUID.nameUUIDFromBytes("parity-coordinator:$runId".toByteArray())
        val opener = "api:coordinator"
        lateinit var root: WorkItem
        val members = mutableListOf<WorkItem>()
        val seated = mutableListOf<Pair<WorkItem, Int>>()

        d.unitOfWork().inUnit {
            root = repo.create(WorkItem(title = "count root", tags = "plain-task", type = "plain-task").let { it.copy(rootId = it.id) })

            suspend fun make(
                title: String,
                role: Role = Role.QUEUE,
                claimedBy: String? = null,
                claimExpiresAt: Instant? = null,
                claimAssignmentId: UUID? = null
            ): WorkItem {
                val claimedAt = claimExpiresAt?.minusSeconds(60)
                return repo.create(
                    WorkItem(
                        title = title,
                        role = role,
                        parentId = root.id,
                        depth = 1,
                        rootId = root.id,
                        tags = "plain-task",
                        type = "plain-task",
                        claimedBy = claimedBy,
                        claimedAt = claimedAt,
                        claimExpiresAt = claimExpiresAt,
                        originalClaimedAt = claimedAt,
                        claimAssignmentId = claimAssignmentId
                    )
                )
            }

            for (k in 0 until kits) {
                members += make("kit $k ready")
                val overdue = make("kit $k overdue seat")
                members += overdue
                seated += overdue to k * 2
                val working = make("kit $k working seat", Role.WORK)
                members += working
                seated += working to k * 2 + 1
                val behindOpen = make("kit $k behind an open blocker")
                members += behindOpen
                deps.create(Dependency(fromItemId = make("kit $k open blocker").id, toItemId = behindOpen.id))
                val behindDone = make("kit $k behind a finished blocker")
                members += behindDone
                deps.create(Dependency(fromItemId = make("kit $k finished blocker", Role.TERMINAL).id, toItemId = behindDone.id))
                members += make("kit $k held", Role.BLOCKED)
                members += make("kit $k done", Role.TERMINAL)
                members += make("kit $k foreign claim", claimedBy = "outsider-$k", claimExpiresAt = now.plusSeconds(300))
                members += make("kit $k expired claim", claimedBy = "outsider-x$k", claimExpiresAt = now.minusSeconds(1))
                members +=
                    make(
                        "kit $k coordinator claim",
                        claimedBy = opener,
                        claimExpiresAt = now.plusSeconds(300),
                        claimAssignmentId = coordinatorSeatId
                    )
            }
        }

        seeder.run(runId, root.id, members.map { it.id }, openedBy = opener)
        for ((item, n) in seated) {
            seeder.seat(
                runId = runId,
                itemId = item.id,
                seat = "implementer",
                principalId = "api:seat-holder",
                token = "count-token-$n",
                assignedAt = if (n % 2 == 0) now.minusSeconds(3_000) else now.minusSeconds(10)
            )
        }
        return Seeded(runId, root.id)
    }

    private suspend fun measure(
        d: SqliteTestDatabase,
        seeded: Seeded,
        scope: AccessScope
    ): Counted {
        val queries = RunFrontierQueries(d.unitOfWork())
        queries.frontier(seeded.runId, now, scope) // warm-up, not counted
        return d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            val result =
                try {
                    queries.frontier(seeded.runId, now, scope)
                } finally {
                    tx.unregisterInterceptor(counter)
                }
            val top =
                counter.sql
                    .groupingBy { it }
                    .eachCount()
                    .maxByOrNull { it.value }
            Counted(counter.count, top?.let { "${it.value}x ${it.key.take(160)}" } ?: "<none>", result)
        }
    }

    private fun assertAnswerOfKits(
        counted: Counted,
        kits: Int,
        label: String
    ) {
        val frontier = assertNotNull(counted.frontier, "$label: the run exists")
        assertEquals(4 * kits, frontier.ready.size, "$label: ready per kit (ready, finished blocker, expired claim, coordinator claim)")
        assertEquals(2 * kits, frontier.inFlight.size, "$label: in flight per kit")
        assertEquals(2 * kits, frontier.waiting.size, "$label: waiting per kit (behind an open blocker, held)")
        assertEquals(kits, frontier.inFlight.count { it.stalled }, "$label: one overdue seat per kit")
        assertEquals(7 * kits, frontier.counts.getValue(Role.QUEUE), "$label: queue members per kit")
        assertTrue(counted.count > 0, "$label: the interceptor saw no statement, so the comparison proves nothing")
    }

    private fun assertConstant(
        small: Counted,
        large: Counted,
        label: String
    ) {
        assertEquals(
            small.count,
            large.count,
            "$label: statement count must not depend on data volume (small ${small.count}, large ${large.count}; most repeated at large: ${large.mostRepeated})"
        )
        assertTrue(large.count <= 16, "$label: a constant handful of statements, got ${large.count} (most repeated: ${large.mostRepeated})")
    }

    private fun restricted(rootId: UUID): AccessScope = AccessScope.principal(setOf(rootId), emptySet())

    @Test
    fun `S13 an unrestricted frontier issues the same number of statements at 1 kit and at 40 kits`(): Unit =
        runBlocking {
            val small =
                SqliteTestDatabase.open(clock = clock).use { d ->
                    seed(d, 1).let { s -> measure(d, s, AccessScope.unrestricted()) }
                }
            val large =
                SqliteTestDatabase.open(clock = clock).use { d ->
                    seed(d, 40).let { s -> measure(d, s, AccessScope.unrestricted()) }
                }

            assertAnswerOfKits(small, 1, "scale 1")
            assertAnswerOfKits(large, 40, "scale 40")
            assertConstant(small, large, "unrestricted")
        }

    @Test
    fun `S13 a root-restricted frontier issues the same number of statements at 1 kit and at 40 kits`(): Unit =
        runBlocking {
            val small = SqliteTestDatabase.open(clock = clock).use { d -> seed(d, 1).let { s -> measure(d, s, restricted(s.rootId)) } }
            val large = SqliteTestDatabase.open(clock = clock).use { d -> seed(d, 40).let { s -> measure(d, s, restricted(s.rootId)) } }

            assertAnswerOfKits(small, 1, "restricted scale 1")
            assertAnswerOfKits(large, 40, "restricted scale 40")
            assertConstant(small, large, "restricted")
        }

    @Test
    fun `S13 probe a run at the membership cap of 500 is still the same number of statements as a run of 10`(): Unit =
        runBlocking {
            val small =
                SqliteTestDatabase.open(clock = clock).use { d ->
                    seed(d, 1).let { s -> measure(d, s, AccessScope.unrestricted()) }
                }
            val cap = SqliteTestDatabase.open(clock = clock).use { d -> seed(d, 50).let { s -> measure(d, s, AccessScope.unrestricted()) } }

            assertAnswerOfKits(cap, 50, "scale 50")
            assertConstant(small, cap, "the membership cap")
            val capRestricted =
                SqliteTestDatabase.open(clock = clock).use { d -> seed(d, 50).let { s -> measure(d, s, restricted(s.rootId)) } }
            val smallRestricted =
                SqliteTestDatabase.open(clock = clock).use { d -> seed(d, 1).let { s -> measure(d, s, restricted(s.rootId)) } }
            assertConstant(smallRestricted, capRestricted, "the membership cap, restricted")
        }
}
