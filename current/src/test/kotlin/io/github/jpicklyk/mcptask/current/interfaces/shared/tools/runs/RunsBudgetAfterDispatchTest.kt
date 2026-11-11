package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Tests for scenarios S11 and S12 of the frozen test-plan as reshaped by the 4.0.1 removal of token and cost budgets (item e74ff75e):
 * an attempt cap that `runs_write update` newly sets AFTER a seat was dispatched is enforced on the next assign (the limit check does
 * not remember that the run had no budget when the seat was assigned), and tokens the writer observed enforce nothing.
 *
 * Oracles (never read from a run): `api-reference.md` for `runs_write` `update` (a budget field "can only be lowered or newly set",
 * coordinator only, on an open run), the attempt cap (the attempt beyond `attemptsPerSeat` is refused `run_paused` and pauses the run
 * on an `attempt-cap` decision) and a seat-token `start` while the run is paused for a limit is `run_paused`. A run opened without a
 * budget is never paused. `observedTokens` is the call log's own estimate: read-only on `runs_read get`, never a cap.
 *
 * Negative-assertion fixture conditions: "not paused" before the update is paired with the same assign pausing right after the update
 * (the only difference is the cap the update set); "the start is refused" is paired with the item still in `queue`.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsBudgetAfterDispatchTest {
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

    private suspend fun SeatWorld.setAttemptCap(
        run: Opened,
        attempts: Int
    ): Reply =
        ops("runs_write", """{"op":"update","runId":"${run.runId}","budget":{"attemptsPerSeat":$attempts}}""", token = run.coordinatorToken)

    private suspend fun SeatWorld.view(run: Opened): Reply =
        select("get", ""","runId":"${run.runId}"""").also { assertTrue(it.ok(), it.toString()) }

    private fun SeatWorld.decisionsOf(run: Opened): List<Map<String, Any?>> =
        rows("SELECT id, class, context FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId))

    private fun writerFor(w: SeatWorld): CallLogWriter =
        CallLogWriter(
            w.rig.composition.unitOfWork,
            SqliteCallLogStore(w.database.databaseManager),
            flushInterval = 1.hours,
            runStore = w.rig.raw.runStore()
        )

    private fun record(
        run: Opened,
        est: Long,
        n: Int
    ): CallLogRecord =
        CallLogRecord(
            reqId = testReqId(n),
            at = Instant.parse("2026-10-15T12:00:00Z"),
            runId = UUID.fromString(run.runId),
            surface = CallLogRecord.SURFACE_MCP,
            tool = "advance",
            outcome = CallLogRecord.OUTCOME_OK,
            latencyMs = 1,
            responseTokensEst = est
        )

    private fun Map<String, Any?>.contextField(key: String): String? =
        Json
            .parseToJsonElement(this["context"] as String)
            .jsonObject[key]
            ?.jsonPrimitive
            ?.content

    // ------------------------------------------------------------------ S11 an attempt cap set after dispatch

    @Test
    @Suppress("ktlint:standard:max-line-length")
    fun `S11 an attempt cap set by update after dispatch pauses the run on the seat's next over-cap assign and blocks the other seat's start`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            seatUp(w, run, w.m1)
            val b = seatUp(w, run, w.m2, "implementer")

            val before = w.assign(run, w.m1, "builder").reply
            assertTrue(before.ok(), "a run without a cap admits attempt 2: $before")
            assertEquals(0, w.decisionsOf(run).size, "no decision without a cap")

            val update = w.setAttemptCap(run, 2)
            assertTrue(update.ok(), "the coordinator newly sets a cap on the open run: $update")
            assertEquals("2", w.view(run).find("attemptsPerSeat"), "the run view shows the new cap")

            val after = w.assign(run, w.m1, "builder").reply

            assertEquals("run_paused", after.code(), "attempt 3 over the new cap of 2 is refused: $after")
            assertEquals("paused", w.view(run).find("status"))
            val decision = w.decisionsOf(run).single()
            assertEquals("attempt-cap", decision["class"], "the pause is an attempt-cap decision")
            assertEquals("2", decision.contextField("allowed"))

            val refused = w.start(w.m2, b.token)
            assertEquals("run_paused", refused.code(), "another seat's token start is refused while the run is paused: $refused")
            assertEquals("queue", w.itemRow(w.m2)["role"], "and the item did not start")
        }

    // ------------------------------------------------------------------ S12 observed tokens enforce nothing

    @Test
    fun `S12 tokens the writer observed enforce nothing and are shown as usage observedTokens`(): Unit =
        world { w ->
            val run = w.open(members = listOf(w.m1), extra = ",\"budget\":{\"attemptsPerSeat\":5}")
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(record(run, 1_000_000, 1))
            writer.flushNow()

            val beat = w.heartbeat(w.m1, seat.token)

            assertTrue(beat.ok(), "the heartbeat succeeds: $beat")
            assertNull(beat.findElement("paused"), "no pause member: $beat")
            val view = w.view(run)
            assertEquals("open", view.find("status"), "a million observed tokens pause nothing")
            assertEquals("1000000", view.find("observedTokens"), "the observed estimate is still shown")
            assertNull(view.findElement("reportedTokens"), "reported usage is gone")
            assertNull(view.findElement("tokens"), "and so is the token budget")
            assertEquals(0, w.decisionsOf(run).size)
        }
}
