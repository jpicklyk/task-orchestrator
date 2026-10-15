package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestSeverity
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f65fef01, R29) for scenarios S11 and S13 (R28 N2 and N5): the facts behind a run rollup are
 * read once each, and two runs on one root never mix their seats, attempts and decisions.
 *
 * Oracles (never read from a run), the frozen task-scope D9 and D11 and the R28 review:
 * - S11: a live rollup of an open run reads the `runs` table once; the close-time snapshot reads the run row, the member item id-set,
 *   the run's seat rows and its decision rows once each, plus one more decision read for the blocked references when (and only when)
 *   an awaiting_decision member exists. The count of statements does not depend on the data volume (scale 1 and scale 40).
 * - S13: the per-run facts (stalled seats, attempts, decisions, blocked reasons, budget) come from the store predicates alone, so a
 *   second run on the same root, with its own members, seats and decisions interleaved in time, changes nothing of the first. Every
 *   expected number is a hand sum written next to its rows.
 *
 * The statements are captured the way `RunRollupQueryCountTest` does (an Exposed interceptor on the transaction of an OUTER read unit
 * that the rollup's own read unit joins), after one uncounted warm-up call. A statement is attributed to a table when the table is
 * the source of its FROM or of one of its JOINs.
 *
 * Negative-assertion fixture conditions: the scale really changes the data (controls count the members), the awaiting fixture really
 * contains awaiting members and the plain fixture none, and the S13 decoys (the other run's rows) are non-zero in every figure they
 * could pollute.
 *
 * Domain invariants satisfied by construction: see [RunKit] and [RollupRaw] (an awaiting_decision block carries a decision ref, a seat
 * is active or ended with an outcome, an item seat has its item).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunRollupSnapshotReadsTest {
    private val clock = SettableClock(ReportKit.NOW)
    private val root = ReportKit.R1
    private val now = ReportKit.NOW
    private val q = RunKit.id("fa000001")
    private val opened = now.minusSeconds(86_400)

    private class StatementCounter : StatementInterceptor {
        val sqls = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            sqls += runCatching { context.sql(transaction) }.getOrDefault("<unprintable>")
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

    private fun reads(
        c: StatementCounter,
        table: String
    ): Int {
        val source = Regex("""(?i)\b(?:FROM|JOIN)\s+"?$table"?(?![A-Za-z0-9_])""")
        return c.sqls.count { sql ->
            sql.trimStart().let { it.startsWith("SELECT", ignoreCase = true) || it.startsWith("WITH", ignoreCase = true) } &&
                source.containsMatchIn(sql)
        }
    }

    private fun ik(k: Int): UUID = RunKit.id("fa100000", k)

    private fun dk(k: Int): UUID = RunKit.id("fa400000", k)

    /** Kit k is a member of the role cycle; every kit has an active seat (stalled for even k) and a stamped transition. */
    private suspend fun seed(
        d: SqliteTestDatabase,
        kits: Int,
        withAwaiting: Boolean
    ) {
        val f = ReportFixture(d, clock)
        RunKit.roots(f)
        val kinds = listOf("queue", "work", "review", "dependency", if (withAwaiting) "awaiting" else "work", "terminal")
        val members = (0 until kits).map { ik(it) }
        RollupRaw.write(d.jdbcUrl) {
            for (k in 0 until kits) {
                when (val kind = kinds[k % kinds.size]) {
                    "dependency" -> {
                        item(ik(k), root, "blocked", 1, root)
                        blocked(ik(k), "dependency")
                    }
                    "awaiting" -> {
                        item(ik(k), root, "blocked", 1, root)
                        blocked(ik(k), "awaiting_decision", dk(k))
                    }
                    else -> item(ik(k), root, kind, 1, root)
                }
            }
        }
        RunKit.run(f, q, members = members, openedAt = opened)
        for (k in 0 until kits) {
            if (withAwaiting && k % kinds.size == 4) {
                val status = if (k % 12 == 4) DecisionRequestStatus.OPEN else DecisionRequestStatus.ANSWERED
                RunKit.decision(f, dk(k), q, item = ik(k), status = status, severity = DecisionRequestSeverity.NORMAL)
            }
            RunKit.decision(f, RunKit.id("fa500000", k), q, decisionClass = "question", severity = DecisionRequestSeverity.LOW)
        }
        RollupRaw.write(d.jdbcUrl) {
            for (k in 0 until kits) {
                val at = now.minusSeconds(3_000L + k)
                val seen = if (k % 2 == 0) now.minusSeconds(700) else now.minusSeconds(10)
                seat(RunKit.id("fa600000", k), q, ik(k), "implementer", 1 + k % 3, opened, seen)
                if (k % 3 == 0) seat(RunKit.id("fa700000", k), q, ik(k), "reviewer", 1, opened, null, endedAt = at, outcome = "done")
                moved(at, ik(k), root, "work", run = q)
            }
        }
        f.seed(
            *(0 until kits)
                .map { k ->
                    ReportKit.rec(k + 1, now.minusSeconds(40_000), runId = q, rootId = root, responseBytes = 10)
                }.toTypedArray()
        )
    }

    private class Measured(
        val live: StatementCounter,
        val snapshot: StatementCounter,
        val members: Long
    )

    private suspend fun measure(
        kits: Int,
        withAwaiting: Boolean
    ): Measured =
        SqliteTestDatabase.open(clock = clock).use { d ->
            seed(d, kits, withAwaiting)
            val fx = ReportFixture(d, clock)
            val service = fx.service()
            val admin = fx.ctx()
            val request = ReportRollupRequest(runId = IdRef(q.toString()))
            val snapshotter = ReportRunRollupSnapshot.over(d.repositoryProvider(), d.unitOfWork(), clock)

            service.rollup(admin, request)
            val live = captureStatements(d) { service.rollup(admin, request) }
            snapshotter.snapshot(q)
            val snapshot = captureStatements(d) { snapshotter.snapshot(q) }
            val total = assertIs<Outcome.Ok<RollupReport>>(service.rollup(admin, request)).value.items.total
            Measured(live, snapshot, total)
        }

    // ------------------------------------------------------------------ S11 each fact table is read once

    @Test
    fun `S11 a live rollup reads runs once and the snapshot reads the run, members, seats and decisions once each`(): Unit =
        runBlocking {
            for (scale in listOf(1, 40)) {
                val m = measure(6 * scale, withAwaiting = true)

                assertEquals(1, reads(m.live, "runs"), "scale $scale: the live rollup reads runs once:\n${m.live.sqls.joinToString("\n")}")
                assertEquals(
                    1,
                    reads(m.snapshot, "runs"),
                    "scale $scale: the snapshot reads the run once:\n${m.snapshot.sqls.joinToString("\n")}"
                )
                assertEquals(1, reads(m.snapshot, "run_items"), "scale $scale: and the member id-set once")
                assertEquals(1, reads(m.snapshot, "run_seats"), "scale $scale: and the seat rows once")
                assertTrue(
                    reads(m.snapshot, "decision_requests") in 1..2,
                    "scale $scale: the decision list, plus at most one blocked-ref read"
                )
                assertTrue(m.snapshot.sqls.isNotEmpty(), "control: the interceptor saw the snapshot")
            }
        }

    @Test
    fun `S11 the snapshot reads the decisions once when no member awaits a decision, and one more time when one does`(): Unit =
        runBlocking {
            val plain = measure(12, withAwaiting = false)
            val awaiting = measure(12, withAwaiting = true)

            assertEquals(
                1,
                reads(plain.snapshot, "decision_requests"),
                "no awaiting member: the decision list only:\n${plain.snapshot.sqls.joinToString("\n")}"
            )
            assertEquals(
                plain.snapshot.sqls.size + 1,
                awaiting.snapshot.sqls.size,
                "exactly one more statement for the refs read\nplain:\n${plain.snapshot.sqls.joinToString(
                    "\n"
                )}\nawaiting:\n${awaiting.snapshot.sqls.joinToString("\n")}"
            )
        }

    @Test
    fun `S11 the number of statements of the live rollup and of the snapshot is the same at scale 1 and scale 40`(): Unit =
        runBlocking {
            val small = measure(6, withAwaiting = true)
            val large = measure(240, withAwaiting = true)

            assertEquals(6L, small.members, "control: the small run has six members")
            assertEquals(240L, large.members, "control: the large run has 240 members")
            assertEquals(small.live.sqls.size, large.live.sqls.size, "live rollup:\n${small.live.sqls}\nvs\n${large.live.sqls}")
            assertEquals(
                small.snapshot.sqls.size,
                large.snapshot.sqls.size,
                "snapshot:\n${small.snapshot.sqls}\nvs\n${large.snapshot.sqls}"
            )
        }

    // ------------------------------------------------------------------ S13 two runs on one root never mix

    private val q1 = RunKit.id("fb000001")
    private val q2 = RunKit.id("fb000002")
    private val a1 = RunKit.id("fb100000", 1)
    private val a2 = RunKit.id("fb100000", 2)
    private val b1 = RunKit.id("fb200000", 1)
    private val b2 = RunKit.id("fb200000", 2)

    private fun ago(seconds: Long) = now.minusSeconds(seconds)

    /**
     * Run Q1 (stalls after 600 s): a1 work with an ENDED implementer seat (attempt 1) and an active implementer seat (attempt 3, last
     * seen 700 s ago, stalled), a2 blocked dependency with an active reviewer seat (attempt 1, seen 10 s ago); decisions: a question
     * NORMAL open, a question ANSWERED. Budget 1,000, reported 300, observed 100.
     * Run Q2 (same root, same threshold): b1 work with an active implementer seat (attempt 2, seen 650 s ago, stalled), b2 blocked
     * manual with an active implementer seat (attempt 5, seen 1 s ago); decisions: a question HIGH open, an attempt-cap HIGH open, a
     * question LOW acknowledged. Budget 50, reported 10, observed 60.
     * Hand sums Q1: items 2 (work 1, blocked 1), blocked {dependency 1}, seats active 2 / stalled [a1 700,000 ms] / count 1,
     * attempts total 3 max 3, decisions open 1 {question 1} {normal 1}, budget used 300 limit 1,000.
     * Hand sums Q2: items 2 (work 1, blocked 1), blocked {manual 1}, seats active 2 / stalled [b1 650,000 ms] / count 1,
     * attempts total 2 max 5, decisions open 3 {question 2, attempt-cap 1} {high 2, low 1}, budget used 60 limit 50.
     */
    private suspend fun seedTwoRuns(f: ReportFixture) {
        RunKit.roots(f)
        RollupRaw.write(f.database.jdbcUrl) {
            item(a1, root, "work", 1, root)
            item(a2, root, "blocked", 1, root)
            item(b1, root, "work", 1, root)
            item(b2, root, "blocked", 1, root)
            blocked(a2, "dependency")
            blocked(b2, "manual")
        }
        RunKit.run(f, q1, members = listOf(a1, a2), openedAt = ago(7_200), budgetTokens = 1_000, reportedTokens = 300, observedTokens = 100)
        RunKit.run(f, q2, members = listOf(b1, b2), openedAt = ago(7_200), budgetTokens = 50, reportedTokens = 10, observedTokens = 60)
        RunKit.decision(f, RunKit.id("fb300000", 1), q1, item = a1, severity = DecisionRequestSeverity.NORMAL)
        RunKit.decision(f, RunKit.id("fb300000", 2), q1, status = DecisionRequestStatus.ANSWERED, severity = DecisionRequestSeverity.NORMAL)
        RunKit.decision(f, RunKit.id("fb300000", 3), q2, severity = DecisionRequestSeverity.HIGH)
        RunKit.decision(f, RunKit.id("fb300000", 4), q2, decisionClass = "attempt-cap", severity = DecisionRequestSeverity.HIGH)
        RunKit.decision(
            f,
            RunKit.id("fb300000", 5),
            q2,
            status = DecisionRequestStatus.ACKNOWLEDGED,
            severity = DecisionRequestSeverity.LOW
        )
        // interleaved in time: Q2's seats are assigned between Q1's
        RollupRaw.write(f.database.jdbcUrl) {
            seat(RunKit.id("fb400000", 1), q1, a1, "implementer", 1, ago(7_000), ago(6_000), endedAt = ago(6_500), outcome = "superseded")
            seat(RunKit.id("fb400000", 4), q2, b1, "implementer", 2, ago(6_900), ago(650))
            seat(RunKit.id("fb400000", 2), q1, a1, "implementer", 3, ago(6_800), ago(700))
            seat(RunKit.id("fb400000", 5), q2, b2, "implementer", 5, ago(6_700), ago(1))
            seat(RunKit.id("fb400000", 3), q1, a2, "reviewer", 1, ago(6_600), ago(10))
        }
    }

    private suspend fun rollupOf(
        f: ReportFixture,
        run: UUID
    ): RollupReport =
        when (val outcome = f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(run.toString())))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok rollup for $run but got ${outcome.error}")
        }

    @Test
    fun `S13 the first run's figures are its own although a second run on the same root is interleaved`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val f = ReportFixture(d, clock)
                seedTwoRuns(f)

                val one = rollupOf(f, q1)

                assertEquals(2L, one.items.total)
                assertEquals(mapOf("dependency" to 1), one.blocked.byReason)
                assertEquals(RollupSeats(active = 2, stalled = listOf(StalledSeat("implementer", a1, 700_000)), count = 1), one.seats)
                assertEquals(AttemptCounts(total = 3, max = 3), one.attempts)
                assertEquals(DecisionCounts(open = 1, byClass = mapOf("question" to 1), bySeverity = mapOf("normal" to 1)), one.decisions)
                assertEquals(BudgetFacts(used = 300, limit = 1_000, basis = "enforced"), one.budget)
            }
        }

    @Test
    fun `S13 the second run's figures are its own too`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val f = ReportFixture(d, clock)
                seedTwoRuns(f)

                val two = rollupOf(f, q2)

                assertEquals(2L, two.items.total)
                assertEquals(mapOf("manual" to 1), two.blocked.byReason)
                assertEquals(RollupSeats(active = 2, stalled = listOf(StalledSeat("implementer", b1, 650_000)), count = 1), two.seats)
                assertEquals(AttemptCounts(total = 2, max = 5), two.attempts)
                assertEquals(
                    DecisionCounts(
                        open = 3,
                        byClass = mapOf("question" to 2, "attempt-cap" to 1),
                        bySeverity =
                            mapOf(
                                "high" to 2,
                                "low" to 1
                            )
                    ),
                    two.decisions
                )
                assertEquals(BudgetFacts(used = 60, limit = 50, basis = "enforced"), two.budget)
            }
        }

    @Test
    fun `S13 closing the first run changes nothing of the second run's figures`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val f = ReportFixture(d, clock)
                seedTwoRuns(f)
                val before = rollupOf(f, q2)

                RollupRaw.write(d.jdbcUrl) {
                    sql(
                        "UPDATE run_seats SET status = 'ended', outcome = 'run_closed', ended_at = ? WHERE run_id = ?",
                        io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
                            .canon(now),
                        q1
                    )
                    sql("DELETE FROM decision_requests WHERE run_id = ?", q1)
                }

                assertEquals(before, rollupOf(f, q2), "another run's seats and decisions are not part of this run's facts")
                assertIs<Outcome.Ok<RollupReport>>(f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(q1.toString()))))
            }
        }
}
