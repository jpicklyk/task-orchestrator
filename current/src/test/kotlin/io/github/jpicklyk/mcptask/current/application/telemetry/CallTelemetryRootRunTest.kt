package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.ItemCreateCommand
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.orFail
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 6a52466e, R2) for the per-call root, run and fields accumulators of
 * [CallTelemetry] and for the way they reach `call_log` rows and `events` rows.
 *
 * Oracles (frozen at queue phase): task-scope points 2, 3 and 4 and AC2, AC3, AC6 and test-plan S7: `setRoot` keeps the FIRST
 * root; a second DISTINCT root sets `multiRoot` (sticky) and `rootId` then reads null; `setRun` is first-wins and records the
 * (runId, seat) pair atomically; `CallLogFields.record` maps `rootId`, `runId` and `seat` from the telemetry; an event written
 * inside a call stamps the run and seat of that call on its `events` row and reports its root to the call; every recorder is a
 * silent no-op when no element is installed. Values are literals or fixture-derived, never read back from the implementation.
 *
 * Not asserted (the oracle does not fix it): the order or de-duplication of [CallTelemetry.fields] at the accumulator level
 * (the sorted distinct JSON form is asserted end to end in McpCallLogRootTest), and the root of a depth-0 item itself.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class CallTelemetryRootRunTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun telemetry(reqId: String = "k7f3q9ab") = CallTelemetry(reqId = reqId, surface = "mcp", sessionId = "session-1")

    private val r1: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val r2: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val r3: UUID = UUID.fromString("33333333-3333-4333-8333-333333333333")

    // ------------------------------------------------------------------ accumulators

    @Test
    fun `a fresh element has no root no run no seat no fields and is not multi-root`() {
        val t = telemetry()
        assertNull(t.rootId)
        assertFalse(t.multiRoot)
        assertNull(t.runId)
        assertNull(t.seat)
        assertNull(t.fields)
    }

    @Test
    fun `the first root is kept and the same root again changes nothing`() {
        val t = telemetry()
        t.setRoot(r1)
        assertEquals(r1, t.rootId)
        assertFalse(t.multiRoot)
        repeat(5) { t.setRoot(r1) }
        assertEquals(r1, t.rootId, "one root touched many times is still that root")
        assertFalse(t.multiRoot, "one root touched many times is not multi-root")
    }

    @Test
    fun `a second distinct root makes the call multi-root and rootId reads null`() {
        val t = telemetry()
        t.setRoot(r1)
        t.setRoot(r2)
        assertTrue(t.multiRoot)
        assertNull(t.rootId, "rootId is null whenever multiRoot")
    }

    @Test
    fun `multi-root is sticky - a later root and the first root again do not undo it`() {
        val t = telemetry()
        t.setRoot(r1)
        t.setRoot(r2)
        t.setRoot(r1)
        assertTrue(t.multiRoot, "r1 again after r2 stays multi-root")
        assertNull(t.rootId)
        t.setRoot(r3)
        assertTrue(t.multiRoot, "a third root stays multi-root")
        assertNull(t.rootId)
    }

    @Test
    fun `the same root recorded concurrently never turns the call multi-root`() {
        val t = telemetry()
        runBlocking {
            (1..8).map { async(Dispatchers.Default) { repeat(1000) { t.setRoot(r1) } } }.awaitAll()
        }
        assertEquals(r1, t.rootId)
        assertFalse(t.multiRoot)
    }

    @Test
    fun `two distinct roots recorded concurrently always end multi-root`() {
        repeat(50) {
            val t = telemetry()
            runBlocking {
                listOf(async(Dispatchers.Default) { t.setRoot(r1) }, async(Dispatchers.Default) { t.setRoot(r2) }).awaitAll()
            }
            assertTrue(t.multiRoot, "round $it")
            assertNull(t.rootId, "round $it")
        }
    }

    @Test
    fun `setRun is first-wins as one atomic pair`() {
        val t = telemetry()
        t.setRun(r1, "planner")
        t.setRun(r2, "implementer")
        assertEquals(r1, t.runId)
        assertEquals("planner", t.seat)
    }

    @Test
    fun `a first run without a seat is not completed by a later seat`() {
        val t = telemetry()
        t.setRun(r1, null)
        t.setRun(r2, "implementer")
        assertEquals(r1, t.runId)
        assertNull(t.seat, "the pair is atomic: the second call seat must not fill the first call null seat")
    }

    @Test
    fun `setFields stores the accepted names`() {
        val t = telemetry()
        t.setFields(listOf("createdAt", "ancestors"))
        assertEquals(setOf("createdAt", "ancestors"), assertNotNull(t.fields).toSet())
    }

    // ------------------------------------------------------------------ recorders

    @Test
    fun `the new recorders are silent no-ops when no element is installed`() =
        runBlocking {
            recordCallRoot(r1)
            recordCallRoot(null)
            recordCallFields(listOf("ancestors"))
            recordCallRun(r1, "planner")
            assertNull(currentCallTelemetry())
        }

    @Test
    fun `the new recorders write into the installed element and a null root is ignored`() =
        runBlocking {
            val t = telemetry()
            withContext(t) {
                recordCallRoot(null)
                assertNull(t.rootId, "a null root records nothing")
                assertFalse(t.multiRoot)
                recordCallRoot(r1)
                recordCallRoot(null)
                recordCallFields(listOf("ancestors"))
                recordCallRun(r1, "planner")
                recordCallRun(r2, "other")
            }
            assertEquals(r1, t.rootId)
            assertFalse(t.multiRoot, "a null root after a real one does not make the call multi-root")
            assertEquals(setOf("ancestors"), assertNotNull(t.fields).toSet())
            assertEquals(r1, t.runId)
            assertEquals("planner", t.seat)
        }

    @Test
    fun `the root recorded in a child coroutine on another dispatcher reaches the call`() =
        runBlocking {
            val t = telemetry()
            withContext(t) { withContext(Dispatchers.IO) { recordCallRoot(r2) } }
            assertEquals(r2, t.rootId)
        }

    // ------------------------------------------------------------------ CallLogFields.record mapping

    private fun record(t: CallTelemetry): CallLogRecord =
        CallLogFields.record(
            telemetry = t,
            at = Instant.parse("2026-03-04T05:06:07.089Z"),
            tool = "items_read",
            operation = null,
            targetIds = null,
            requestShape = null,
            isError = false,
            errorCode = null,
            latencyMs = 1,
            requestBytes = 1,
            responseBytes = 1,
            batchSize = null,
            failedCount = null,
            resultCount = null,
            eligibleCount = null
        )

    @Test
    fun `S7 record carries the first run and seat of the telemetry and null when setRun was never called`() {
        val stamped = telemetry("stamped1")
        stamped.setRun(r1, "planner")
        stamped.setRun(r2, "x")
        val rec = record(stamped)
        assertEquals(r1, rec.runId)
        assertEquals("planner", rec.seat)

        val bare = record(telemetry("bare0001"))
        assertNull(bare.runId, "never called: null")
        assertNull(bare.seat, "never called: null")
    }

    @Test
    fun `S7 a run with a null seat records the run and a null seat`() {
        val t = telemetry()
        t.setRun(r1, null)
        val rec = record(t)
        assertEquals(r1, rec.runId)
        assertNull(rec.seat)
    }

    @Test
    fun `record carries the single root and null when the call touched none or several`() {
        val one = telemetry("one00001")
        one.setRoot(r1)
        one.setRoot(r1)
        assertEquals(r1, record(one).rootId)

        assertNull(record(telemetry("none0001")).rootId, "no root recorded")

        val many = telemetry("many0001")
        many.setRoot(r1)
        many.setRoot(r2)
        assertNull(record(many).rootId, "two distinct roots: no single root")
    }

    // ------------------------------------------------------------------ events: root reported to the call, run stamped on the row

    private fun newRig() = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)

    private suspend fun EventLogRig.createUnder(
        parentId: UUID?,
        title: String
    ) = ctx.itemCommandService.create(ItemCreateCommand(parentId = parentId, title = title, type = "test-item")).orFail()

    @Test
    fun `S7 an event written inside a call carries the run and seat of that call on its events row`() =
        runBlocking {
            val rig = newRig()
            val parent = rig.seed("S7 parent")
            val t = telemetry("t900001a")
            withContext(t) {
                t.setRun(r1, "planner")
                t.setRun(r2, "implementer")
                rig.createUnder(parent.id, "S7 child")
            }

            val rows = rig.rows()
            assertTrue(rows.isNotEmpty(), "fixture: the write produced an event row")
            for (row in rows) {
                assertEquals(r1, row.runId, "first setRun wins on ${row.type}")
                assertEquals("planner", row.seat, "first setRun wins on ${row.type}")
            }
            assertEquals(
                setOf<String?>("t900001a"),
                CallLogRows.eventStamps(db.jdbcUrl).map { it.first }.toSet(),
                "control: rows carry this call reqId"
            )
        }

    @Test
    fun `S7 an event written in a call that never set a run has null run and seat`() =
        runBlocking {
            val rig = newRig()
            val parent = rig.seed("S7 parent")
            val t = telemetry("t900002a")
            withContext(t) { rig.createUnder(parent.id, "S7 child") }

            val rows = rig.rows()
            assertTrue(rows.isNotEmpty(), "fixture: the write produced an event row")
            assertTrue(rows.all { it.runId == null && it.seat == null }, "no setRun, no stamp: $rows")
            assertEquals(
                setOf<String?>("t900002a"),
                CallLogRows.eventStamps(db.jdbcUrl).map { it.first }.toSet(),
                "control: the call telemetry was installed"
            )
        }

    @Test
    fun `an event reports the root of its item to the call and a second root makes it multi-root`() =
        runBlocking {
            val rig = newRig()
            val rootA = rig.seed("Root A")
            val rootB = rig.seed("Root B")
            val t = telemetry("t900003a")
            withContext(t) {
                rig.createUnder(rootA.id, "under A first")
                rig.createUnder(rootA.id, "under A second")
            }
            assertEquals(rootA.id, t.rootId, "two writes under one root report that root")
            assertFalse(t.multiRoot)

            withContext(t) { rig.createUnder(rootB.id, "under B") }
            assertTrue(t.multiRoot, "a write under a second root makes the call multi-root")
            assertNull(t.rootId)
        }
}
