package io.github.jpicklyk.mcptask.current.perf

import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.application.report.ReportRollupRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportRunRollupSnapshot
import io.github.jpicklyk.mcptask.current.application.report.RollupRaw
import io.github.jpicklyk.mcptask.current.application.report.RunKit
import io.github.jpicklyk.mcptask.current.application.report.blocked
import io.github.jpicklyk.mcptask.current.application.report.seat
import io.github.jpicklyk.mcptask.current.application.run.RunRollupSnapshot
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.math.ceil

/**
 * Run rollup benchmark (item f65fef01, R28 N1; instrumentation, no assertions). Platform-gated: runs only with TO_PERF=1.
 *
 * Seeds one run of 500 members (roles cycling, a fifth of them blocked), 200,000 call_log rows and 50,000 events stamped
 * with the run, a few dozen seats and decisions, then times (a) the live `report rollup runId` read and (b) the close-time
 * snapshot (the production snapshot against [RunRollupSnapshot.NONE], inside a write unit like the close). Targets
 * (R28 performance-baseline): live p95 under 250 ms, close delta under 200 ms. Prints `PERF_RESULT` lines.
 */
@Tag("serial")
class RunRollupBench {
    private val clock = SettableClock(ReportKit.NOW)
    private val root = ReportKit.R1
    private val now = ReportKit.NOW
    private val run = RunKit.id("fa000001")

    @Test
    fun runRollup() {
        assumeTrue(System.getenv("TO_PERF") == "1")
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { db ->
                val fixture = ReportFixture(db, clock)
                seed(db, fixture)
                val service = fixture.service()
                val admin = fixture.ctx()
                val request = ReportRollupRequest(runId = IdRef(run.toString()))
                val snapshot = ReportRunRollupSnapshot.over(db.repositoryProvider(), db.unitOfWork(), clock)

                repeat(WARMUP) {
                    check(service.rollup(admin, request) is Outcome.Ok) { "live rollup failed" }
                    snapshotIn(db, snapshot)
                }
                val live = LongArray(RUNS) { timed { check(service.rollup(admin, request) is Outcome.Ok) { "live rollup failed" } } }
                val withSnapshot = LongArray(RUNS) { timed { snapshotIn(db, snapshot) } }
                val withNone = LongArray(RUNS) { timed { snapshotIn(db, RunRollupSnapshot.NONE) } }
                println(line("live-rollup", live))
                println(line("close-snapshot-production", withSnapshot))
                println(line("close-snapshot-none", withNone))
                val delta = pct(withSnapshot.sorted(), 0.50) - pct(withNone.sorted(), 0.50)
                println("PERF_RESULT config=close-delta p50_delta_ms=${"%.3f".format(delta)}")
            }
        }
    }

    private suspend fun snapshotIn(
        db: SqliteTestDatabase,
        snapshot: RunRollupSnapshot
    ) {
        db.unitOfWork().write("bench.close") {
            snapshot.snapshot(run)
            Outcome.Ok(Unit)
        }
    }

    private inline fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return System.nanoTime() - t0
    }

    private fun pct(
        sorted: List<Long>,
        p: Double
    ): Double = sorted[minOf(sorted.size - 1, ceil(p * sorted.size).toInt() - 1)] / 1_000_000.0

    private fun line(
        label: String,
        samples: LongArray
    ): String {
        val sorted = samples.sorted()
        return "PERF_RESULT config=$label n=${sorted.size} p50_ms=${"%.3f".format(pct(sorted, 0.50))} " +
            "p95_ms=${"%.3f".format(pct(sorted, 0.95))} mean_ms=${"%.3f".format(sorted.average() / 1_000_000.0)}"
    }

    private suspend fun seed(
        db: SqliteTestDatabase,
        fixture: ReportFixture
    ) {
        RunKit.roots(fixture)
        val opened = now.minusSeconds(86_400)
        val members = (0 until MEMBERS).map { RunKit.id("fa100000", it) }
        val roles = listOf("queue", "work", "review", "blocked", "terminal")
        RollupRaw.write(db.jdbcUrl) {
            members.forEachIndexed { i, id ->
                val role = roles[i % roles.size]
                item(id, root, role, 1, root)
                if (role == "blocked") blocked(id, "dependency")
            }
        }
        RunKit.run(fixture, run, members = members, openedAt = opened)
        RollupRaw.write(db.jdbcUrl) {
            for (i in 0 until SEATS) {
                seat(RunKit.id("fa200000", i), run, members[i], "implementer", 1, opened, now.minusSeconds(if (i % 2 == 0) 900L else 30L))
            }
        }
        bulk(db, members, opened)
    }

    /** 50,000 stamped events and 200,000 stamped call_log rows, inserted as JDBC batches in one transaction. */
    private fun bulk(
        db: SqliteTestDatabase,
        members: List<UUID>,
        opened: java.time.Instant
    ) {
        DriverManager.getConnection(db.jdbcUrl).use { conn ->
            conn.autoCommit = false
            conn
                .prepareStatement(
                    "INSERT INTO events (id, occurred_at, root_id, entity_kind, entity_id, type, run_id, data) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                ).use { ps ->
                    for (i in 0 until EVENTS) {
                        ps.setBytes(1, P7Raw.bytes(UUID.randomUUID()))
                        ps.setString(2, P7Raw.canon(opened.plusSeconds(i.toLong() % 80_000)))
                        ps.setBytes(3, P7Raw.bytes(root))
                        ps.setString(4, DomainEvent.KIND_ITEM)
                        ps.setBytes(5, P7Raw.bytes(members[i % members.size]))
                        ps.setString(6, DomainEvent.ITEM_TRANSITIONED)
                        ps.setBytes(7, P7Raw.bytes(run))
                        ps.setString(8, "{\"origin\":\"direct\",\"toRole\":\"work\"}")
                        ps.addBatch()
                        if (i % BATCH == BATCH - 1) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            conn
                .prepareStatement(
                    "INSERT INTO call_log (req_id, at, surface, tool, outcome, attempts, latency_ms, response_bytes, " +
                        "response_tokens_est, token_method, root_id, run_id) VALUES (?, ?, 'mcp', 'sample_tool', 'ok', 1, 1, 40, 10, 'bytes/4', ?, ?)"
                ).use { ps ->
                    for (i in 0 until CALLS) {
                        ps.setString(1, testReqId(i % 999_999))
                        ps.setString(2, P7Raw.canon(opened.plusSeconds(i.toLong() % 80_000)))
                        ps.setBytes(3, P7Raw.bytes(root))
                        ps.setBytes(4, P7Raw.bytes(run))
                        ps.addBatch()
                        if (i % BATCH == BATCH - 1) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            conn.commit()
        }
    }

    private companion object {
        const val MEMBERS = 500
        const val SEATS = 40
        const val EVENTS = 50_000
        const val CALLS = 200_000
        const val BATCH = 5_000
        const val WARMUP = 3
        const val RUNS = 30
    }
}
