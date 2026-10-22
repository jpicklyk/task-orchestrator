package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 74bb6db9, R28) for scenario S12 of the frozen test-plan (NEW-SURFACE): the snapshot
 * codec `RollupSnapshotCodec` round-trips a typed [RollupReport], and stores it under the wire member names with FULL UUIDs.
 *
 * Oracle: task-scope AC8 ("an application codec of RollupReport using the wire member names with FULL UUIDs; `decode(encode(r)) == r`")
 * and AC9 (a stored snapshot that cannot be decoded is not a snapshot: the read computes live), so a decode of something that is not
 * an encoded rollup answers null. Expected values are constructed by hand below, never read from an encode.
 *
 * Negative-assertion conditions: the members that are absent in the anchor-scoped sample (run, decisions, seats, attempts, budget,
 * the optional blocked members) each have a twin in the run-scoped sample that is present, so an encoder that always wrote them or
 * never wrote them would break one of the two round trips. The real reports of the run world are round-tripped too, so a codec that
 * only handles hand-made samples is caught.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: a closed header carries a terminal reason and a closing instant, an open one neither;
 * the rollup scope of a run report has a run id and no anchor id, that of an anchor report the reverse.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class RollupSnapshotCodecTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val runId = UUID.fromString("a0a0a0a0-1111-4111-8111-111111111111")
    private val rootId = UUID.fromString("b0b0b0b0-2222-4222-8222-222222222222")
    private val anchorId = UUID.fromString("c0c0c0c0-3333-4333-8333-333333333333")
    private val itemA = UUID.fromString("d0d0d0d0-4444-4444-8444-444444444444")
    private val itemB = UUID.fromString("e0e0e0e0-5555-4555-8555-555555555555")
    private val opened = Instant.parse("2026-10-13T10:00:00Z")
    private val closed = Instant.parse("2026-10-13T11:30:15.123Z")

    private val runReport =
        RollupReport(
            scope = RollupScope(anchorId = null, runId = runId, rootId = rootId),
            window = RollupWindow(since = opened, until = closed, clamped = false),
            items = RollupItems(total = 12, byRole = mapOf("queue" to 1L, "blocked" to 8L, "terminal" to 3L)),
            blocked =
                RollupBlocked(
                    total = 8,
                    byReason = mapOf("manual" to 1, "awaiting_decision" to 7),
                    resumable = 3,
                    dwellMs = mapOf("manual" to 1_234_567_890_123L, "awaiting_decision" to 42L)
                ),
            transitions = RollupTransitions(total = 5, cascades = 2, rejected = 2),
            notesWritten = 3,
            cost = RollupCost(calls = 5, errors = 1, retries = 3, responseBytes = 160, responseTokensEst = 40, basis = "run"),
            run = RunHeader(RunStatus.CLOSED, RunTerminalReason.BUDGET_EXHAUSTED, opened, closed, rootId),
            decisions =
                DecisionCounts(
                    open = 6,
                    byClass = mapOf("question" to 5, "attempt-cap" to 1),
                    bySeverity = mapOf("high" to 2, "normal" to 2, "low" to 2)
                ),
            seats =
                RollupSeats(
                    active = 3,
                    stalled = listOf(StalledSeat("reviewer", itemA, 3_600_000), StalledSeat("implementer", itemB, 601_000)),
                    count = 2
                ),
            attempts = AttemptCounts(total = 4, max = 3),
            budget = BudgetFacts(used = 450, limit = 1_000, basis = "enforced")
        )

    private val anchorReport =
        RollupReport(
            scope = RollupScope(anchorId = anchorId, runId = null, rootId = rootId),
            window = RollupWindow(since = opened, until = closed, clamped = true),
            items = RollupItems(total = 1, byRole = mapOf("work" to 1L)),
            blocked = RollupBlocked(total = 0, byReason = null),
            transitions = RollupTransitions(total = 0, cascades = 0, rejected = 0),
            notesWritten = 0,
            cost = RollupCost(calls = 0, errors = 0, retries = 0, responseBytes = 0, responseTokensEst = 0, basis = "targets"),
            run = null,
            decisions = null,
            seats = null,
            attempts = null,
            budget = null
        )

    private val openRunReport =
        runReport.copy(
            run = RunHeader(RunStatus.OPEN, null, opened, null, rootId),
            blocked = RollupBlocked(total = 0, byReason = null),
            seats = RollupSeats(active = 0, stalled = emptyList(), count = 0),
            budget = BudgetFacts(used = 0, limit = null, basis = "enforced")
        )

    @Test
    fun `S12 a run report round-trips through encode and decode, and so does its text form`() {
        assertEquals(runReport, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(runReport)))
        assertEquals(runReport, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(runReport).toString()))
    }

    @Test
    fun `S12 an anchor report with every run section absent round-trips and stays absent`() {
        val decoded = assertNotNull(RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(anchorReport)))

        assertEquals(anchorReport, decoded)
        assertNull(decoded.run)
        assertNull(decoded.decisions)
        assertNull(decoded.seats)
        assertNull(decoded.attempts)
        assertNull(decoded.budget)
        assertNull(decoded.scope.runId)
    }

    @Test
    fun `S12 an open run with no limit, no terminal reason and no stalled seat round-trips`() {
        assertEquals(openRunReport, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(openRunReport)))
    }

    @Test
    fun `S12 the stored form uses the wire member names with full uuids`() {
        val stored: JsonObject = RollupSnapshotCodec.encode(runReport)

        assertEquals(
            runId.toString(),
            stored
                .getValue("scope")
                .jsonObject
                .getValue("runId")
                .jsonPrimitive.content,
            "full run id, not the short form"
        )
        assertEquals(
            rootId.toString(),
            stored
                .getValue("scope")
                .jsonObject
                .getValue("rootId")
                .jsonPrimitive.content
        )
        for (member in listOf(
            "scope",
            "window",
            "run",
            "itemCounts",
            "blocked",
            "decisions",
            "seats",
            "transitions",
            "notes",
            "cost",
            "attempts",
            "budget"
        )) {
            assertTrue(member in stored, "the stored snapshot carries the wire member '$member': ${stored.keys}")
        }
        assertTrue(itemA.toString() in stored.toString(), "a stalled seat's item id is stored in full")
        assertTrue(
            "anchorId" in RollupSnapshotCodec.encode(anchorReport).getValue("scope").jsonObject,
            "the anchor report keeps its anchor id"
        )
        assertFalse("run" in RollupSnapshotCodec.encode(anchorReport), "the anchor report has no run member")
    }

    @Test
    fun `S12 probe text that is not an encoded rollup decodes to null instead of throwing`() {
        assertNull(RollupSnapshotCodec.decode("not json"), "malformed text")
        assertNull(RollupSnapshotCodec.decode("{}"), "an object with none of the members")
        assertNull(RollupSnapshotCodec.decode("\"garbage\""), "a JSON string")
        assertNull(RollupSnapshotCodec.decode(Json.parseToJsonElement("[1,2]")), "a JSON array")
    }

    @Test
    fun `S12 every real report of the run world survives a round trip`(): Unit =
        runBlocking {
            val f = ReportFixture(db.db, clock)
            RunWorld.seedMain(f)
            val reports =
                listOf(RunWorld.Q, RunWorld.Q2).map { run ->
                    val outcome = f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(run.toString())))
                    (outcome as Outcome.Ok).value
                }

            for (report in reports) {
                assertEquals(report, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(report)), "run ${report.scope.runId}")
            }
            assertEquals(3, reports.first().blocked.resumable, "control: the main report is a rich one")
        }

    @Test
    fun `S12 the dwell world reports survive a round trip`(): Unit =
        runBlocking {
            val f = ReportFixture(db.db, clock)
            DwellWorld.seed(f, closed = true)
            val outcome = f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(DwellWorld.QD.toString())))
            val report = (outcome as Outcome.Ok).value

            assertEquals(report, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(report)))
            assertEquals(
                4_200_000L,
                report.blocked.dwellMs?.get("dependency"),
                "control: the report carries the dwell map the codec must keep"
            )
        }
}
