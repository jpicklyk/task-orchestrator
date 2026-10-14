package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 1be2db5e, R16) for scenario S19 of the frozen test-plan (NEW-SURFACE): the rollup
 * keeps a CONSTANT number of SQL statements per call, independent of how many items, events and calls the subtree holds, and
 * none of the SELECTs it issues scans `events` or `call_log` without an index.
 *
 * Oracle: task-scope AC10 ("one `unitOfWork.read` per call and a constant number of SQL statements independent of subtree size,
 * events and calls ... No bare `SCAN events` / `SCAN call_log` in the plan") and the performance-baseline measurement plan. The
 * expected value is not a number taken from the code: it is the statement count at scale 1 compared with the count at scale 40
 * of the same kind of data for the same call. The plan check follows the R11 method of `ReportQueryCountTest`: the statements
 * are captured by an Exposed [StatementInterceptor] registered on the transaction of an OUTER read unit (the rollup's own read
 * unit joins it), each SELECT is run through `EXPLAIN QUERY PLAN` with its parameters unbound, so the plan is that of the SQL
 * production really runs.
 *
 * Scale N means N kits under the anchor. Kit k is: an item Ik (role cycling) with a child Jk, a current note Nk on Ik, and for
 * every 7th kit a lifecycle-free child Lk of Ik with a child Mk; events: Ik transitioned, Jk transitioned with origin cascade,
 * Jk transitioned (its request wrote a call), Jk rejected, Nk upserted twice (one request wrote a call); calls: three naming Ik,
 * two written by the requests above, one unrelated. Hand sums at scale N: items 1 + 2N + ceil(N / 7) (the lifecycle-free items
 * are not counted), transitions 3N, cascades N, rejected N, notes 2N, cost calls 5N. Controls prove the interceptor sees
 * ordinary reads, that the scale really changes the data, and that the root-scoped and leaf calls really filter, so equal counts
 * are not an inert request.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: see [RollupRaw]; every event's root is the root of its entity item, every call's
 * `target_ids` is a JSON array of lowercase hyphenated UUIDs.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RollupQueryCountTest {
    private val clock = SettableClock(ReportKit.NOW)
    private val root = ReportKit.R1
    private val anchor = RollupRaw.id("a7000001")
    private val roles = listOf("queue", "work", "review", "blocked", "terminal")
    private val base: Instant = Instant.parse("2026-10-01T00:00:00Z")
    private val since = "2026-10-01T00:00:00Z"
    private val until = "2026-10-09T00:00:00Z"

    private class StatementCounter : StatementInterceptor {
        var count = 0
        val sqls = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            count++
            sqls += context.sql(transaction)
        }
    }

    private suspend fun captureStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Any?
    ): StatementCounter =
        d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter
        }

    private fun kitId(
        letter: Char,
        k: Int
    ): UUID = RollupRaw.id(letter + "%07x".format(k))

    private fun seed(
        d: SqliteTestDatabase,
        scale: Int
    ) {
        RollupRaw.write(d.jdbcUrl) {
            item(root, null, "queue", 0, null)
            item(anchor, root, "work", 1, root)
            for (k in 0 until scale) {
                val ik = kitId('b', k)
                val jk = kitId('c', k)
                val nk = kitId('f', k)
                val at = base.plus(Duration.ofHours((k % 20).toLong()))
                item(ik, anchor, roles[k % 5], 2, root)
                item(jk, ik, roles[(k + 2) % 5], 3, root)
                note(nk, ik, "k")
                if (k % 7 == 0) {
                    val lk = kitId('d', k)
                    item(lk, ik, null, 3, root)
                    item(kitId('e', k), lk, "work", 4, root)
                }
                val reqA = 10 * k + 4
                val reqB = 10 * k + 5
                event(DomainEvent.ITEM_TRANSITIONED, at, ik, root, """{"from":"queue","to":"work"}""")
                event(DomainEvent.ITEM_TRANSITIONED, at.plusSeconds(1), jk, root, """{"origin":"cascade"}""")
                event(DomainEvent.ITEM_TRANSITIONED, at.plusSeconds(2), jk, root, """{"origin":"direct"}""", reqN = reqA)
                event(DomainEvent.TRANSITION_REJECTED, at.plusSeconds(3), jk, root, """{"code":"gate_blocked"}""")
                event(DomainEvent.NOTE_UPSERTED, at.plusSeconds(4), nk, root, """{"itemId":"$ik","key":"k"}""", kind = "note")
                event(DomainEvent.NOTE_UPSERTED, at.plusSeconds(5), nk, root, """{"itemId":"$ik","key":"k"}""", reqN = reqB, kind = "note")
                val named = RollupRaw.targets(ik)
                call(10 * k + 1, at.plusSeconds(10), root, named)
                call(10 * k + 2, at.plusSeconds(11), root, named)
                call(10 * k + 3, at.plusSeconds(12), root, named)
                call(reqA, at.plusSeconds(13), root, null)
                call(reqB, at.plusSeconds(14), root, null)
                call(10 * k + 6, at.plusSeconds(15), root, null)
            }
        }
    }

    private class Measured(
        val counts: Map<String, Int>,
        val plans: Map<String, List<Pair<String, List<String>>>>,
        val items: Long,
        val transitions: Long,
        val cascades: Long,
        val rejected: Long,
        val notes: Long,
        val calls: Long,
        val leafItems: Long,
        val scopedForbiddenFree: Boolean
    )

    private val keywords =
        setOf("WHERE", "ON", "JOIN", "LEFT", "INNER", "CROSS", "GROUP", "ORDER", "LIMIT", "USING", "UNION", "SELECT", "AS", "SET")

    /** The names a statement gives `events` and `call_log`: the tables themselves and every alias after FROM / JOIN. */
    private fun namesOf(sql: String): Set<String> {
        val names = mutableSetOf("events", "call_log")
        val pattern = Regex("""(?i)\b(?:FROM|JOIN)\s+"?(events|call_log)"?(?:\s+(?:AS\s+)?"?([A-Za-z_][A-Za-z0-9_]*)"?)?""")
        for (m in pattern.findAll(sql)) {
            val alias = m.groupValues[2]
            if (alias.isNotEmpty() && alias.uppercase() !in keywords) names += alias
        }
        return names
    }

    private fun isSelect(sql: String): Boolean =
        sql.trimStart().let { it.startsWith("SELECT", ignoreCase = true) || it.startsWith("WITH", ignoreCase = true) }

    private fun explain(
        d: SqliteTestDatabase,
        sql: String
    ): List<String> =
        DriverManager.getConnection(d.jdbcUrl).use { c ->
            // Parameters stay unbound (NULL): the planner has no statistics, so the access paths do not depend on the values.
            c.prepareStatement("EXPLAIN QUERY PLAN $sql").use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
            }
        }

    private suspend fun measure(scale: Int): Measured {
        val counts = linkedMapOf<String, Int>()
        val plans = linkedMapOf<String, List<Pair<String, List<String>>>>()
        return SqliteTestDatabase.open(clock = clock).use { d ->
            seed(d, scale)
            val fx = ReportFixture(d, clock)
            val service = fx.service()
            val admin = fx.ctx()
            val scoped = fx.ctx(AccessScope.principal(setOf(root), emptySet()))

            fun request(
                id: UUID,
                window: Boolean,
                ref: String = id.toString()
            ) = ReportRollupRequest(IdRef(ref), if (window) since else null, if (window) until else null)

            val cases = linkedMapOf<String, suspend () -> Any?>()
            cases["container anchor, default window"] = { service.rollup(admin, request(anchor, window = false)) }
            cases["container anchor, explicit window"] = { service.rollup(admin, request(anchor, window = true)) }
            cases["container anchor, hex prefix"] =
                { service.rollup(admin, request(anchor, window = true, ref = anchor.toString().take(8))) }
            cases["leaf anchor"] = { service.rollup(admin, request(kitId('c', 0), window = true)) }
            cases["mid anchor with a lifecycle-free child"] = { service.rollup(admin, request(kitId('b', 0), window = true)) }
            cases["lifecycle-free anchor"] = { service.rollup(admin, request(kitId('d', 0), window = true)) }
            cases["root anchor"] = { service.rollup(admin, request(root, window = true)) }
            cases["root scoped caller"] = { service.rollup(scoped, request(anchor, window = true)) }
            cases["root scoped caller, default window"] = { service.rollup(scoped, request(anchor, window = false)) }

            for ((name, call) in cases) {
                call()
                val captured = captureStatements(d, call)
                counts[name] = captured.count
                plans[name] = captured.sqls.filter(::isSelect).map { sql -> sql to explain(d, sql) }
            }

            val full = okOf(service.rollup(admin, request(anchor, window = true)))
            val leaf = okOf(service.rollup(admin, request(kitId('c', 0), window = true)))
            val forScoped = service.rollup(scoped, request(anchor, window = true))
            Measured(
                counts,
                plans,
                full.items.total,
                full.transitions.total,
                full.transitions.cascades,
                full.transitions.rejected,
                full.notesWritten,
                full.cost.calls,
                leaf.items.total,
                forScoped is Outcome.Ok
            )
        }
    }

    private fun okOf(outcome: Outcome<RollupReport>): RollupReport = assertIs<Outcome.Ok<RollupReport>>(outcome).value

    @Test
    fun `S19 every rollup call issues the same number of statements at scale 1 and 40`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            assertEquals(small.counts.keys, large.counts.keys)
            val mismatches = small.counts.keys.filter { small.counts.getValue(it) != large.counts.getValue(it) }
            assertTrue(
                mismatches.isEmpty(),
                "statement count must not depend on data volume:\n" +
                    mismatches.joinToString("\n") { "$it: ${small.counts[it]} vs ${large.counts[it]}" }
            )
            for ((name, count) in small.counts) assertTrue(count > 0, "the interceptor saw no statement for '$name'")
        }

    @Test
    fun `S19 control - the scale changes the data and the calls really filter`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            assertEquals(4L, small.items, "A, I0, J0 and M0; the lifecycle-free L0 is not counted")
            assertEquals(87L, large.items, "1 + 80 + 6 (kits 0, 7, 14, 21, 28 and 35 hold a lifecycle-free child)")
            assertEquals(3L, small.transitions)
            assertEquals(120L, large.transitions, "3 per kit")
            assertEquals(1L, small.cascades)
            assertEquals(40L, large.cascades)
            assertEquals(40L, large.rejected)
            assertEquals(80L, large.notes, "2 per kit")
            assertEquals(5L, small.calls)
            assertEquals(200L, large.calls, "3 by target and 2 by request per kit; the unrelated call is never counted")
            assertEquals(1L, large.leafItems, "a leaf anchor counts itself only")
            assertTrue(large.scopedForbiddenFree, "the root-scoped caller reads its own root")
        }

    @Test
    fun `S19 no SELECT of the rollup scans events or call_log without an index`(): Unit =
        runBlocking {
            val large = measure(40)

            var sawEventsIndex = false
            for ((name, statements) in large.plans) {
                assertTrue(statements.isNotEmpty(), "no SELECT was captured for '$name'")
                for ((sql, plan) in statements) {
                    val names = namesOf(sql)
                    val bare =
                        plan.filter { line ->
                            val trimmed = line.trim()
                            names.any { n -> Regex("^SCAN (\"?$n\"?)( |$)").containsMatchIn(trimmed) } && " USING " !in trimmed
                        }
                    assertTrue(bare.isEmpty(), "'$name' scans events or call_log without an index:\n${plan.joinToString("\n")}\nSQL: $sql")
                    if (plan.any { "idx_events_" in it }) sawEventsIndex = true
                }
            }
            assertTrue(sawEventsIndex, "the rollup reaches events through an idx_events_* index")
        }

    @Test
    fun `S19 control - the interceptor counts ordinary reads`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val fx = ReportFixture(d, clock)
                fx.root(root, "Alpha root")
                val repo = d.repositoryProvider().workItemRepository()

                val one = captureStatements(d) { repo.getById(root) }.count
                val three = captureStatements(d) { repeat(3) { repo.getById(root) } }.count

                assertTrue(one >= 1)
                assertTrue(three >= 3 * one, "three reads count at least three times one: $one vs $three")
            }
        }
}
