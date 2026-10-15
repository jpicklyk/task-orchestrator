package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import ch.qos.logback.classic.Level
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.payload
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (blind test-author seat, item 85d49060) for scenario S7 of the frozen test-plan: a member seat row that cannot be
 * decoded when the close-time rollup snapshot reads it must not fail the close.
 *
 * The fixture is the one the finding names: a member seat that ENDED normally (through `end_seat`) and whose `item_id` is then set to
 * NULL by a raw UPDATE (a row the domain cannot load). The close is the production one, through the real MCP adapter and dispatcher
 * over the real composition and SQLite (the `SeatWorld` rig), so the production snapshot runs inside the close unit.
 *
 * Oracles (never read from a run): the frozen task-scope D2 acceptance and the f65fef01 D10 contract: a snapshot that cannot be
 * computed because of a data fault is absorbed with a WARN, the close commits, the run is closed with the requested reason,
 * `run.ended` is recorded without a `rollup` payload key, and nothing repairs the offending row. A healthy close stores the rollup.
 *
 * Negative-assertion fixture conditions: "no rollup" is paired with a healthy run closed in the same world through the same calls
 * (only the NULL item_id differs), whose `run.ended` DOES carry a rollup and logs no WARN; "the close succeeds" fails without the fix
 * because the snapshot read poisons the close unit and the close rolls back with the run still open.
 *
 * Domain invariants satisfied by construction: both runs are opened through the tool over members that are children of the seeded
 * root; the seats are assigned through the coordinator token and ended through their own token, so every column except the one raw
 * UPDATE is what the production writes produced.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions; the log capture is detached in a
 * finally block and every assertion is outside it.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsCloseBadSeatRowTest {
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

    private class Closed(
        val reply: Reply,
        val warnings: Int,
        val ended: List<JsonObject>
    )

    private suspend fun closeCapturingWarnings(
        w: SeatWorld,
        run: Opened
    ): Closed {
        val mark = w.rig.maxSeq()
        val capture = LogCapture().attach()
        val reply =
            try {
                w.close(run, "cancelled", run.coordinatorToken)
            } finally {
                capture.detach()
            }
        val ended =
            w.rig
                .rowsAfter(mark)
                .filter { it.type == "run.ended" && it.entityId == UUID.fromString(run.runId) }
                .map { it.payload() }
        return Closed(reply, capture.events.count { it.level == Level.WARN }, ended)
    }

    private suspend fun status(
        w: SeatWorld,
        run: Opened
    ): Reply = w.select("get", ""","runId":"${run.runId}"""").also { assertTrue(it.ok(), it.toString()) }

    @Test
    fun `S7 a close succeeds when an ended member seat has no item, records run ended without a rollup and keeps the row`() =
        world { w ->
            val bad = w.open(members = listOf(w.m1))
            val healthy = w.open(members = listOf(w.m2))
            val badSeat = w.assignOk(bad, w.m1, "builder")
            val healthySeat = w.assignOk(healthy, w.m2, "implementer")
            assertTrue(w.endSeat(badSeat.token).ok(), "fixture: the member seat ends normally")
            assertTrue(w.endSeat(healthySeat.token).ok(), "fixture: the control member seat ends normally")
            val badId = assertNotNull(badSeat.assignmentId, "assign returns the assignment id")
            assertEquals("ended", w.seatById(badId)["status"], "fixture: the seat is ended")
            w.exec("UPDATE run_seats SET item_id = NULL WHERE id = ?", UUID.fromString(badId))
            assertNull(w.seatById(badId)["item_id"], "fixture: the seat row now has no item")

            val closedBad = closeCapturingWarnings(w, bad)
            val closedHealthy = closeCapturingWarnings(w, healthy)

            assertTrue(closedBad.reply.ok(), "the close commits although a seat row cannot be decoded: ${closedBad.reply}")
            val view = status(w, bad)
            assertEquals("closed", view.find("status"), "the run is closed, not left open by a rolled-back close")
            assertEquals("cancelled", view.find("terminalReason"), "with the requested reason")
            val ended = closedBad.ended.single()
            assertEquals("cancelled", ended.getValue("reason").jsonPrimitive.content, "run.ended carries the reason")
            assertTrue("rollup" !in ended, "run.ended carries no rollup when the snapshot could not be computed: $ended")
            assertTrue(closedBad.warnings >= 1, "the absorbed fault is logged at WARN")
            assertNull(w.seatById(badId)["item_id"], "the offending row is left as it was, not repaired")

            // control: the same calls on a run without the NULL item_id store a rollup and log no warning
            assertTrue(closedHealthy.reply.ok(), "control: the healthy close succeeds: ${closedHealthy.reply}")
            assertEquals("closed", status(w, healthy).find("status"))
            val healthyEnded = closedHealthy.ended.single()
            assertTrue("rollup" in healthyEnded, "control: a healthy close stores the rollup: $healthyEnded")
            assertEquals(0, closedHealthy.warnings, "control: a healthy close logs no warning")
        }
}
