package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item 74bb6db9, R28) for scenario S16 of the frozen test-plan (NEW-SURFACE): the run rollup keeps
 * a CONSTANT number of SQL statements per call whatever the members, seats, decisions, events and calls the run holds, and none of the
 * SELECTs it issues scans `events` or `call_log` without an index.
 *
 * Oracle: task-scope AC10 ("One read unit per call; a constant number of statements whatever the members, seats, decisions, events and
 * calls; no bare `SCAN events` / `SCAN call_log` ... the run path uses idx_events_run_seq, idx_call_log_run_at and idx_events_entity_seq;
 * member ids bound once through json_each, never chunked") and AC9 (a closed run with a stored snapshot costs one indexed event
 * lookup). The expected value is not a number taken from the code: it is the statement count at scale 1 compared with the count at
 * scale 40 and at scale 40 plus 1,500 plain members of the same call. The 1,500 extra members are the chunking probe: a statement that
 * binds member ids in chunks (a bound-parameter limit near 999 or 500) issues more statements for them. The plan check follows the R16
 * method of `RollupQueryCountTest`: statements are captured by an Exposed [StatementInterceptor] on the transaction of an OUTER read
 * unit (the rollup's own read unit joins it), and each SELECT is run through `EXPLAIN QUERY PLAN` with its parameters unbound.
 *
 * Scale N means 6N kits in run Q, so even scale 1 holds a kit of every role pattern (a statement that is skipped only when there is
 * nothing to ask, such as the blocked-reason read of a run with no blocked member, is then present at every scale). Kit k is: a member Ik (role cycling through queue, work, review, blocked dependency, blocked
 * awaiting_decision, terminal) with an open or answered decision for the awaiting_decision ones, an active seat (stalled for even k),
 * every third kit an extra ended seat, every seventh kit a lifecycle-free member; stamped events (a transition, a rejection, a note),
 * block / release events of the unbound callers for the blocked ones, three stamped calls. Controls prove the interceptor sees
 * ordinary reads, that the scale really changes the data, that the closed live run and the stored snapshot really differ in cost, and
 * that the root-scoped call really filters, so equal counts are not an inert request.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: see [RunKit] and [RollupRaw]; the stored snapshot is the encoding of a report whose run
 * id is the run it is stored for.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunRollupQueryCountTest {
    private val clock = SettableClock(ReportKit.NOW)
    private val root = ReportKit.R1
    private val now = ReportKit.NOW
    private val q = RunKit.id("f9000001")
    private val closedLive = RunKit.id("f9000002")
    private val closedSnap = RunKit.id("f9000003")
    private val opened = now.minusSeconds(86_400)

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

    private fun ik(k: Int): UUID = RunKit.id("f9100000", k)

    private fun lk(k: Int): UUID = RunKit.id("f9200000", k)

    private fun plain(n: Int): UUID = RunKit.id("f9300000", n)

    private val storedSample =
        RollupReport(
            scope = RollupScope(anchorId = null, runId = closedSnap, rootId = root),
            window = RollupWindow(opened, now, false),
            items = RollupItems(3, mapOf("work" to 3L)),
            blocked = RollupBlocked(0, null),
            transitions = RollupTransitions(7, 3, 1),
            notesWritten = 2,
            cost = RollupCost(11, 1, 4, 5_000, 1_250, "run"),
            run = RunHeader(RunStatus.CLOSED, RunTerminalReason.COMPLETED, opened, now, root),
            decisions = DecisionCounts(0, emptyMap(), emptyMap()),
            seats = RollupSeats(0, emptyList(), 0),
            attempts = AttemptCounts(2, 1),
            budget = BudgetFacts(10, null, "enforced")
        )

    private suspend fun seed(
        d: SqliteTestDatabase,
        scale: Int,
        extraMembers: Int
    ) {
        val f = ReportFixture(d, clock)
        RunKit.roots(f)
        val kits = scale * 6
        val roles = listOf("queue", "work", "review", "blocked", "blocked", "terminal")
        val members = mutableListOf<UUID>()
        RollupRaw.write(d.jdbcUrl) {
            for (k in 0 until kits) {
                val role = roles[k % 6]
                item(ik(k), root, role, 1, root)
                members += ik(k)
                if (role == "blocked") {
                    if (k % 6 == 3) blocked(ik(k), "dependency") else blocked(ik(k), "awaiting_decision", RunKit.id("f9400000", k))
                }
                if (k % 7 == 0) {
                    item(lk(k), root, null, 1, root)
                    members += lk(k)
                }
            }
            for (e in 0 until extraMembers) {
                item(plain(e), root, "work", 1, root)
                members += plain(e)
            }
        }
        RunKit.run(f, q, members = members, openedAt = opened)
        val classes = listOf("question", "attempt-cap", "run-budget")
        val severities = listOf(DecisionRequestSeverity.HIGH, DecisionRequestSeverity.NORMAL, DecisionRequestSeverity.LOW)
        for (k in 0 until kits) {
            if (k % 6 == 4) {
                val status = if (k % 12 == 4) DecisionRequestStatus.OPEN else DecisionRequestStatus.ANSWERED
                RunKit.decision(f, RunKit.id("f9400000", k), q, item = ik(k), status = status, severity = severities[k % 3])
            }
            RunKit.decision(f, RunKit.id("f9500000", k), q, decisionClass = classes[k % 3], severity = severities[(k + 1) % 3])
        }
        RollupRaw.write(d.jdbcUrl) {
            for (k in 0 until kits) {
                val at = now.minusSeconds(3_000L + k)
                val stalledSeen = if (k % 2 == 0) now.minusSeconds(700) else now.minusSeconds(10)
                seat(RunKit.id("f9600000", k), q, ik(k), "implementer", 1 + k % 3, opened, stalledSeen)
                if (k % 3 == 0) seat(RunKit.id("f9700000", k), q, ik(k), "reviewer", 1, opened, null, endedAt = at, outcome = "done")
                moved(at, ik(k), root, "work", run = q, origin = if (k % 2 == 0) "cascade" else "direct")
                runEvent(DomainEvent.TRANSITION_REJECTED, at.plusSeconds(1), ik(k), root, q, "{\"code\":\"gate_blocked\"}")
                runEvent(DomainEvent.NOTE_UPSERTED, at.plusSeconds(2), RunKit.id("f9800000", k), root, q, "{\"key\":\"k\"}", kind = "note")
                if (k % 6 == 3 || k % 6 == 4) {
                    moved(opened.plusSeconds(60L * (k + 1)), ik(k), root, "blocked", if (k % 6 == 3) "dependency" else "awaiting_decision")
                    if (k % 2 == 0) moved(opened.plusSeconds(60L * (k + 1) + 300), ik(k), root, "work")
                }
                // decoys: an unstamped transition naming the member
                moved(at, ik(k), root, "work")
            }
        }
        val old = now.minusSeconds(40_000)
        f.seed(
            *(0 until kits)
                .flatMap { k ->
                    (0..2).map { j ->
                        ReportKit.rec(
                            10 * k + j + 1,
                            old,
                            runId = q,
                            rootId = root,
                            responseBytes = 40,
                            error = if (j == 2) "gate_blocked" else null,
                            attempts = if (j == 1) 2 else 1
                        )
                    }
                }.toTypedArray()
        )
        // a closed run with no stored snapshot (computed live, members are the first kits) and a closed run whose run.ended carries one
        RunKit.run(
            f,
            closedLive,
            members = (0 until 5).map { ik(it) },
            status = RunStatus.CLOSED,
            openedAt = opened,
            closedAt = now.minusSeconds(60)
        )
        RunKit.run(f, closedSnap, status = RunStatus.CLOSED, openedAt = opened, closedAt = now)
        val snapshotText = RollupSnapshotCodec.encode(storedSample).toString()
        RollupRaw.write(d.jdbcUrl) {
            runEvent(
                "run.ended",
                now,
                closedSnap,
                root,
                closedSnap,
                "{\"reason\":\"completed\",\"rollup\":$snapshotText}",
                kind = DomainEvent.KIND_RUN
            )
        }
    }

    private class Measured(
        val counts: Map<String, Int>,
        val plans: Map<String, List<Pair<String, List<String>>>>,
        val items: Long,
        val transitions: Long,
        val stored: RollupReport?,
        val closedLiveItems: Long,
        val scopedOk: Boolean
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

    private fun okOf(outcome: Outcome<RollupReport>): RollupReport = assertIs<Outcome.Ok<RollupReport>>(outcome).value

    private suspend fun measure(
        scale: Int,
        extraMembers: Int = 0
    ): Measured {
        val counts = linkedMapOf<String, Int>()
        val plans = linkedMapOf<String, List<Pair<String, List<String>>>>()
        return SqliteTestDatabase.open(clock = clock).use { d ->
            seed(d, scale, extraMembers)
            val fx = ReportFixture(d, clock)
            val service = fx.service()
            val admin = fx.ctx()
            val scoped = fx.ctx(AccessScope.principal(setOf(root), emptySet()))

            fun request(ref: String) = ReportRollupRequest(runId = IdRef(ref))

            val cases = linkedMapOf<String, suspend () -> Any?>()
            cases["open run, full id"] = { service.rollup(admin, request(q.toString())) }
            cases["open run, hex prefix"] = { service.rollup(admin, request(q.toString().take(8))) }
            cases["open run, 32 hex digits"] = { service.rollup(admin, request(q.toString().replace("-", ""))) }
            cases["open run, root scoped caller"] = { service.rollup(scoped, request(q.toString())) }
            cases["closed run, computed live"] = { service.rollup(admin, request(closedLive.toString())) }
            cases["closed run, stored snapshot"] = { service.rollup(admin, request(closedSnap.toString())) }

            for ((name, call) in cases) {
                call()
                val captured = captureStatements(d, call)
                counts[name] = captured.count
                plans[name] = captured.sqls.filter(::isSelect).map { sql -> sql to explain(d, sql) }
            }

            val open = okOf(service.rollup(admin, request(q.toString())))
            val live = okOf(service.rollup(admin, request(closedLive.toString())))
            val stored = okOf(service.rollup(admin, request(closedSnap.toString())))
            val forScoped = service.rollup(scoped, request(q.toString()))
            Measured(counts, plans, open.items.total, open.transitions.total, stored, live.items.total, forScoped is Outcome.Ok)
        }
    }

    @Test
    fun `S16 every run rollup call issues the same number of statements at scale 1, at scale 40 and with 1500 more members`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)
            val wide = measure(40, extraMembers = 1_500)

            assertEquals(small.counts.keys, large.counts.keys)
            val mismatches =
                small.counts.keys.filter {
                    small.counts.getValue(it) != large.counts.getValue(it) || large.counts.getValue(it) != wide.counts.getValue(it)
                }
            assertTrue(
                mismatches.isEmpty(),
                "statement count must not depend on data volume (scale 1 / 40 / 40 + 1500 members):\n" +
                    mismatches.joinToString("\n") { "$it: ${small.counts[it]} / ${large.counts[it]} / ${wide.counts[it]}" }
            )
            for ((name, count) in small.counts) assertTrue(count > 0, "the interceptor saw no statement for '$name'")
        }

    @Test
    fun `S16 a closed run with a stored snapshot costs fewer statements than the same run computed live, and returns the snapshot`(): Unit =
        runBlocking {
            val large = measure(40)

            assertTrue(
                large.counts.getValue("closed run, stored snapshot") < large.counts.getValue("closed run, computed live"),
                "a stored snapshot is one indexed lookup: ${large.counts}"
            )
            assertEquals(storedSample, large.stored, "the closed run answers exactly the snapshot stored in its run.ended row")
        }

    @Test
    fun `S16 control - the scale changes the data and the root scoped call really reads`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)
            val wide = measure(40, extraMembers = 1_500)

            assertEquals(6L, small.items, "six kits, one counted member each; the lifecycle-free child of kit 0 is not counted")
            assertEquals(240L, large.items, "one counted member per kit (the 35 lifecycle-free ones are not counted)")
            assertEquals(1_740L, wide.items, "the plain members are counted")
            assertEquals(6L, small.transitions)
            assertEquals(240L, large.transitions, "one stamped transition per kit; the unstamped decoys are not counted")
            assertEquals(5L, large.closedLiveItems, "the closed run's five members")
            assertTrue(large.scopedOk, "the root-scoped caller reads its own root")
        }

    @Test
    fun `S16 no SELECT of the run rollup scans events or call_log without an index and the run indexes are used`(): Unit =
        runBlocking {
            val large = measure(40)

            val used = mutableSetOf<String>()
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
                    for (index in listOf("idx_events_run_seq", "idx_call_log_run_at", "idx_events_entity_seq")) {
                        if (plan.any { index in it }) used += index
                    }
                }
            }
            assertEquals(
                setOf("idx_events_run_seq", "idx_call_log_run_at", "idx_events_entity_seq"),
                used,
                "the run path reaches events and call_log through the run indexes and the entity index"
            )
        }

    @Test
    fun `S16 control - the interceptor counts ordinary reads`(): Unit =
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
