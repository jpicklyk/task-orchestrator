package io.github.jpicklyk.mcptask.current.application.run

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Assigned
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Opened
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Reply
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.StatementTap
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Tests for the run limits that remain after the 4.0.1 removal of token and cost budgets (item e74ff75e, scenarios S7 and S8 of
 * R29): how many statements the seat-token writes cost, and that the attempt cap and the limit pause still decide exactly as before.
 *
 * Oracles (never read from a run): a seat-token `heartbeat` no longer runs any limit check, so it does no single-table `runs` SELECT
 * and costs the same statements on a capped run as on an unlimited one; a seat-token `start` on a run with NO budget does not read
 * the run to see whether it is limit-paused (the run is not limited, so it cannot be), and so costs one statement fewer than the
 * same call on a capped run (the read that asks whether the run is limit-paused); the call-log writer batch updates `runs` once per DISTINCT run id of the batch, never once per row and
 * never for a row without a run. The decision itself is unchanged: the attempt beyond `attemptsPerSeat` is refused `run_paused` and
 * pauses the run on an `attempt-cap` decision, an attempt equal to the cap does not; while a run is paused for a limit a seat-token
 * `start` is `run_paused`; a manual pause is not a limit pause, so the start is allowed.
 *
 * Statements are recorded by the passive global tap ([StatementTap]) around one call, after one uncounted warm-up call of the same op
 * on the same kind of run. Both runs of a pair are seated on items of the SAME schema, claimed and touched alike, 30 s apart.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunLimitsStatementCountTest {
    private val clock = SettableClock(Instant.parse("2026-10-15T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private fun runsSelects(sql: List<String>): Int = sql.count { StatementTap.selectsFrom(it, "runs") }

    private suspend fun seatUp(
        w: SeatWorld,
        run: Opened,
        item: WorkItem,
        seat: String = "builder"
    ): Assigned {
        val assigned = w.assignOk(run, item, seat)
        assertTrue(w.claim(item, assigned.token).ok(), "the seat claims its item")
        return assigned
    }

    private suspend fun SeatWorld.start(
        item: WorkItem,
        token: String?
    ): Reply = ops("advance", """{"op":"transition","itemId":"${item.id}","trigger":"start"}""", token = token)

    // ------------------------------------------------------------------ S7 statement counts

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S7 a token heartbeat issues no runs SELECT and the same statements on a capped run as on an unlimited run`(): Unit =
        world { w ->
            val unlimited = w.open(members = listOf(w.m1))
            val capped = w.open(members = listOf(w.d), extra = ",\"budget\":{\"attemptsPerSeat\":100000}")
            val seatU = seatUp(w, unlimited, w.m1)
            val seatB = seatUp(w, capped, w.d)
            clock.advanceSeconds(30)
            assertTrue(w.heartbeat(w.m1, seatU.token).ok(), "warm-up, unlimited run")
            assertTrue(w.heartbeat(w.d, seatB.token).ok(), "warm-up, capped run")

            clock.advanceSeconds(30)
            val (replyU, sqlU) = StatementTap.record { w.heartbeat(w.m1, seatU.token) }
            clock.advanceSeconds(30)
            val (replyB, sqlB) = StatementTap.record { w.heartbeat(w.d, seatB.token) }

            assertTrue(replyU.ok() && replyB.ok(), "both heartbeats succeed: $replyU / $replyB")
            assertTrue(sqlB.isNotEmpty(), "control: the tap recorded the capped call")
            assertEquals(0, runsSelects(sqlU), "the unlimited run is never read:\n${sqlU.joinToString("\n")}")
            assertEquals(0, runsSelects(sqlB), "the heartbeat checks no limit, capped or not:\n${sqlB.joinToString("\n")}")
            assertEquals(
                sqlB.size,
                sqlU.size,
                "the same statements\nunlimited:\n${sqlU.joinToString("\n")}\ncapped:\n${sqlB.joinToString("\n")}"
            )
        }

    @Test
    fun `S7 a token start on an unlimited run skips the limit-pause read of a capped run and issues one statement fewer`(): Unit =
        world { w ->
            assertTrue(
                w.start(w.m3, null).ok(),
                "warm-up: this start moves the shared root to work, so no measured start carries that cascade"
            )
            val unlimited = w.open(members = listOf(w.m1))
            val budgeted = w.open(members = listOf(w.d), extra = ",\"budget\":{\"attemptsPerSeat\":100000}")
            val seatU = seatUp(w, unlimited, w.m1)
            val seatB = seatUp(w, budgeted, w.d)
            clock.advanceSeconds(30)

            val (replyU, sqlU) = StatementTap.record { w.start(w.m1, seatU.token) }
            clock.advanceSeconds(30)
            val (replyB, sqlB) = StatementTap.record { w.start(w.d, seatB.token) }

            assertTrue(replyU.ok() && replyB.ok(), "both starts succeed: $replyU / $replyB")
            assertEquals("work", w.itemRow(w.m1)["role"], "the unlimited member started")
            assertEquals("work", w.itemRow(w.d)["role"], "the budgeted member started")
            // The start path makes one `runs` read that has nothing to do with the limit (the open-run check of a member), so what the
            // hint removes is measured as a difference: the limit-pause read of the capped run is not issued for the unlimited one.
            val budgetedReads = runsSelects(sqlB)
            assertTrue(
                budgetedReads > runsSelects(sqlU),
                "the budgeted run is still checked, the unlimited one is not: ${runsSelects(sqlU)} vs $budgetedReads"
            )
            assertEquals(
                sqlB.size - 1,
                sqlU.size,
                "one statement fewer\nunlimited:\n${sqlU.joinToString("\n")}\nbudgeted:\n${sqlB.joinToString("\n")}"
            )
        }

    private var reqCounter = 0

    private fun record(
        runId: String?,
        est: Long?,
        reqId: String = testReqId(reqCounter++)
    ): CallLogRecord =
        CallLogRecord(
            reqId = reqId,
            at = Instant.parse("2026-10-15T12:00:00Z"),
            runId = runId?.let(UUID::fromString),
            surface = CallLogRecord.SURFACE_MCP,
            tool = "advance",
            outcome = CallLogRecord.OUTCOME_OK,
            latencyMs = 1,
            responseTokensEst = est
        )

    private fun writerFor(w: SeatWorld): CallLogWriter =
        CallLogWriter(
            w.rig.composition.unitOfWork,
            SqliteCallLogStore(w.database.databaseManager),
            flushInterval = 1.hours,
            runStore = w.rig.raw.runStore()
        )

    private fun observed(
        w: SeatWorld,
        run: Opened
    ): Long =
        w.rows("SELECT observed_tokens FROM runs WHERE id = ?", UUID.fromString(run.runId)).single().getValue("observed_tokens") as Long

    private fun storedEstimate(
        w: SeatWorld,
        run: Opened
    ): Long =
        (
            w
                .rows(
                    "SELECT COALESCE(SUM(response_tokens_est), 0) AS s FROM call_log WHERE run_id = ?",
                    UUID.fromString(run.runId)
                ).single()
                .getValue("s") as Number
        ).toLong()

    @Test
    fun `S7 a writer batch updates runs once per distinct run id, whatever the number of rows and the rows without a run`(): Unit =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val b = w.open(members = listOf(w.m2))
            val c = w.open(members = listOf(w.m3))
            val writer = writerFor(w)
            for (est in listOf(10L, 20L, 30L)) writer.submit(record(a.runId, est))
            for (est in listOf(5L, 5L)) writer.submit(record(b.runId, est))
            writer.submit(record(c.runId, 7))
            writer.submit(record(null, 99))
            writer.submit(record(null, 1))

            val (written, sql) = StatementTap.record { writer.flushNow() }

            assertEquals(8, written, "all eight rows are written")
            assertEquals(
                3,
                sql.count { StatementTap.updates(it, "runs") },
                "one UPDATE runs per distinct run id:\n${sql.joinToString("\n")}"
            )
            assertEquals(60L, observed(w, a), "10 + 20 + 30")
            assertEquals(10L, observed(w, b), "5 + 5")
            assertEquals(7L, observed(w, c), "the single row")
            for (run in listOf(
                a,
                b,
                c
            )) {
                assertEquals(storedEstimate(w, run), observed(w, run), "observed tokens equal the stored estimates")
            }
        }

    @Test
    fun `S7 probe an empty batch updates no run, and a duplicate request id is stored once`(): Unit =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val writer = writerFor(w)

            val (none, emptySql) = StatementTap.record { writer.flushNow() }
            assertEquals(0, none, "nothing queued")
            assertEquals(
                0,
                emptySql.count { StatementTap.updates(it, "runs") },
                "an empty batch touches no run:\n${emptySql.joinToString("\n")}"
            )

            val same = testReqId(900)
            writer.submit(record(a.runId, 40, reqId = same))
            writer.submit(record(a.runId, 40, reqId = same))
            writer.submit(record(a.runId, 2))
            val (written, sql) = StatementTap.record { writer.flushNow() }

            assertTrue(written >= 2, "the distinct rows are written, got $written")
            assertEquals(1, sql.count { StatementTap.updates(it, "runs") }, "one run, one UPDATE:\n${sql.joinToString("\n")}")
            assertEquals(42L, storedEstimate(w, a), "the duplicate request id was stored once: 40 + 2")
            assertEquals(42L, observed(w, a), "and it was counted once in observed tokens: 40 + 2, not 40 + 40 + 2")
        }

    // ------------------------------------------------------------------ S8 the cap still decides

    private fun Reply.pausedByAttempts(): Boolean = code() == "run_paused"

    @Test
    fun `S8 an attempt equal to the cap does not pause, one more attempt pauses on an attempt-cap decision`(): Unit =
        world { w ->
            val run = w.open(extra = ",\"budget\":{\"attemptsPerSeat\":1}")
            seatUp(w, run, w.m1)
            assertEquals(0, w.rows("SELECT id FROM decision_requests").size, "no decision at the cap")
            assertEquals("open", w.select("get", ",\"runId\":\"${run.runId}\"").find("status"))

            val over = w.assign(run, w.m1, "builder").reply

            assertTrue(over.pausedByAttempts(), "attempt 2 over a cap of 1 is refused: $over")
            val decision = w.rows("SELECT class FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId)).single()
            assertEquals("attempt-cap", decision["class"], "the pause is an attempt-cap decision")
            assertEquals("paused", w.select("get", ",\"runId\":\"${run.runId}\"").find("status"))
        }

    @Test
    fun `S8 an unlimited run is never paused by attempts however many, and its seat keeps starting items`(): Unit =
        world { w ->
            val run = w.open()
            val seat = seatUp(w, run, w.m1)

            for (n in 1..3) assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "attempt ${n + 1} is admitted")

            assertEquals("open", w.select("get", ",\"runId\":\"${run.runId}\"").find("status"))
            assertEquals(0, w.rows("SELECT id FROM decision_requests").size, "no decision on an unlimited run")
            assertNotNull(seat.token)
        }

    @Test
    fun `S8 a token start while the run is paused for its limit is run_paused, while a manual pause allows it`(): Unit =
        world { w ->
            val limited = w.open(members = listOf(w.m1, w.m2), extra = ",\"budget\":{\"attemptsPerSeat\":1}")
            seatUp(w, limited, w.m1)
            val b = seatUp(w, limited, w.m2, "implementer")
            assertTrue(w.assign(limited, w.m1, "builder").reply.pausedByAttempts(), "fixture: the limit pause")

            val refused = w.start(w.m2, b.token)

            assertEquals("run_paused", refused.code(), "a start under a limit pause is refused: $refused")
            assertEquals("queue", w.itemRow(w.m2)["role"], "and the item did not start")

            // control: the same shape under a MANUAL pause (a pause the director chose) is not a limit pause
            val manual = w.open(members = listOf(w.d, w.m3), extra = ",\"budget\":{\"attemptsPerSeat\":1}")
            seatUp(w, manual, w.d)
            val c = seatUp(w, manual, w.m3, "implementer")
            val paused = w.director("runs_write", """{"ops":[{"op":"pause","runId":"${manual.runId}"}]}""", w.human)
            assertTrue(paused.ok(), "fixture: manual pause: $paused")
            assertEquals("paused", w.select("get", ",\"runId\":\"${manual.runId}\"").find("status"))

            val allowed = w.start(w.m3, c.token)

            assertTrue(allowed.ok(), "a manual pause is not a limit pause: $allowed")
            assertEquals("work", w.itemRow(w.m3)["role"])
        }
}
