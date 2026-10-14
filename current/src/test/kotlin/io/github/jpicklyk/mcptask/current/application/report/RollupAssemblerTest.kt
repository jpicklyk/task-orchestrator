package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.application.port.StalledSeats
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.rollupJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 1be2db5e, R16) for the PURE half of the rollup: [RollupAssembler.assemble], the
 * [NotTrackedRunFacts] stub and the wire shape of [rollupJson]. Scenarios S16 and S17 of the frozen test-plan (NEW-SURFACE); no
 * database is involved, the inputs are hand-built [RollupParts].
 *
 * Oracles: task-scope AC8 (`assemble(parts)` is pure; every run-only part is nullable and null means omitted; `seats.stalled` is
 * capped at 5 entries plus `count`, the true total, whatever the port returned), AC9 (the stub answers "not tracked", null,
 * from every method of the seven-method port), AC7 (`blocked.total` is the blocked-role count, byReason only with a run; short
 * ids only on the wire), AC2 (`byRole` omits zero roles) and the declared constants (`STALLED_CAP = 5`, `ROLE_ORDER` queue,
 * work, review, blocked, terminal, `BASIS_TARGETS = "targets"`) and the declared wire member order of `data`. The expected
 * values are hand-written below; none is read from a run.
 *
 * Negative-assertion conditions: every "absent" assertion has a sibling call in the same test that supplies the part and sees it
 * present; the cap assertions have inputs of 6, 8 and 12 entries that would survive an uncapped implementation.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: [RollupParts] roles are the five role wire names; the run header's `closedAt` is
 * null exactly for an open run; stalled counts are at least the entries listed.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RollupAssemblerTest {
    private val anchor = UUID.fromString("f1000001-0000-4000-8000-000000000001")
    private val root = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
    private val run = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
    private val since = Instant.parse("2026-10-10T00:00:00Z")
    private val until = Instant.parse("2026-10-11T00:00:00Z")

    private val window = RollupWindow(since, until, clamped = false)
    private val transitions = RollupTransitions(total = 12, cascades = 5, rejected = 3)
    private val cost = RollupCost(calls = 9, errors = 2, retries = 4, responseBytes = 1_234, responseTokensEst = 308, basis = "targets")

    private fun parts(
        roleCounts: Map<String, Long> = mapOf("queue" to 1L, "work" to 2L),
        scope: RollupScope = RollupScope(anchorId = anchor, runId = null, rootId = root),
        seats: StalledSeats? = null,
        run: RunHeader? = null,
        blockedReasons: Map<String, Int>? = null,
        decisions: DecisionCounts? = null,
        attempts: AttemptCounts? = null,
        budget: BudgetFacts? = null,
        window: RollupWindow = this.window
    ) = RollupParts(
        scope = scope,
        window = window,
        roleCounts = roleCounts,
        transitions = transitions,
        notesWritten = 7,
        cost = cost,
        run = run,
        blockedReasons = blockedReasons,
        decisions = decisions,
        seats = seats,
        attempts = attempts,
        budget = budget
    )

    private fun stalled(n: Int): List<StalledSeat> =
        List(n) { i ->
            StalledSeat(
                seat = "seat-$i",
                itemId = UUID.fromString("57%04d00-0000-4000-8000-%012d".format(i, i)),
                silentMs = 1_000L * (n - i)
            )
        }

    private val header = RunHeader(RunStatus.OPEN, null, Instant.parse("2026-10-10T01:00:00Z"), null, root)
    private val decisions =
        DecisionCounts(
            open = 3,
            byClass = mapOf("design" to 2, "scope" to 1),
            bySeverity =
                mapOf(
                    "high" to 1,
                    "low" to 2
                )
        )
    private val attempts = AttemptCounts(total = 4, max = 3)
    private val budget = BudgetFacts(used = 500, limit = 1_000, basis = "targets")

    // ------------------------------------------------------------------ constants

    @Test
    fun `the declared constants have the documented values`() {
        assertEquals(5, RollupAssembler.STALLED_CAP)
        assertEquals(listOf("queue", "work", "review", "blocked", "terminal"), RollupAssembler.ROLE_ORDER)
        assertEquals("targets", RollupAssembler.BASIS_TARGETS)
    }

    // ------------------------------------------------------------------ items and blocked

    @Test
    fun `items total is the sum of the role counts and byRole omits zero roles`() {
        val report =
            RollupAssembler.assemble(
                parts(
                    roleCounts =
                        mapOf(
                            "queue" to 1L,
                            "work" to 0L,
                            "review" to 4L,
                            "blocked" to 0L,
                            "terminal" to 2L
                        )
                )
            )

        assertEquals(7L, report.items.total, "1 + 0 + 4 + 0 + 2")
        assertEquals(mapOf("queue" to 1L, "review" to 4L, "terminal" to 2L), report.items.byRole)
        assertEquals(0L, report.blocked.total, "the blocked role count is 0")
    }

    @Test
    fun `byRole iterates in the declared role order whatever the input order`() {
        val report =
            RollupAssembler.assemble(
                parts(
                    roleCounts =
                        linkedMapOf(
                            "terminal" to 2L,
                            "blocked" to 3L,
                            "queue" to 1L,
                            "review" to 5L
                        )
                )
            )

        assertEquals(
            listOf("queue", "review", "blocked", "terminal"),
            report.items.byRole.keys
                .toList()
        )
        assertEquals(11L, report.items.total)
        assertEquals(3L, report.blocked.total, "blocked.total is the blocked role count")
    }

    @Test
    fun `an empty role count map gives zero items, an empty byRole and zero blocked`() {
        val report = RollupAssembler.assemble(parts(roleCounts = emptyMap()))

        assertEquals(0L, report.items.total)
        assertTrue(report.items.byRole.isEmpty())
        assertEquals(0L, report.blocked.total)
    }

    @Test
    fun `the scope, window, transitions, notes and cost pass through unchanged`() {
        val clamped = RollupWindow(since, until, clamped = true)

        val report = RollupAssembler.assemble(parts(window = clamped))

        assertEquals(RollupScope(anchor, null, root), report.scope)
        assertEquals(clamped, report.window)
        assertEquals(transitions, report.transitions)
        assertEquals(7L, report.notesWritten)
        assertEquals(cost, report.cost)
        assertEquals("targets", report.cost.basis)
    }

    @Test
    fun `assemble is pure - the same parts give an equal report`() {
        val p = parts(seats = StalledSeats(active = 9, stalled = stalled(8), count = 8), run = header, decisions = decisions)

        assertEquals(RollupAssembler.assemble(p), RollupAssembler.assemble(p))
    }

    // ------------------------------------------------------------------ S16 stalled cap

    @Test
    fun `S16 eight stalled seats are capped at five entries and the count stays eight`() {
        val port = stalled(8)

        val seats = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(active = 10, stalled = port, count = 8))).seats)

        assertEquals(5, seats.stalled.size, "an uncapped implementation would return 8")
        assertEquals(8, seats.count)
        assertEquals(10, seats.active)
        assertEquals(port.take(5), seats.stalled, "the first five of the port's order are kept")
    }

    @Test
    fun `S16 three stalled seats are kept whole with count three`() {
        val port = stalled(3)

        val seats = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(active = 3, stalled = port, count = 3))).seats)

        assertEquals(port, seats.stalled)
        assertEquals(3, seats.count)
    }

    @Test
    fun `S16 the cap boundary - exactly five stays five and six becomes five`() {
        val five = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(5, stalled(5), 5))).seats)
        val six = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(6, stalled(6), 6))).seats)
        val none = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(2, emptyList(), 0))).seats)

        assertEquals(5, five.stalled.size)
        assertEquals(5, five.count)
        assertEquals(5, six.stalled.size)
        assertEquals(6, six.count, "the true total survives the cap")
        assertEquals(0, none.stalled.size)
        assertEquals(0, none.count)
        assertEquals(2, none.active)
    }

    @Test
    fun `S16 a port that over-returns is still capped and a count larger than the list is passed through`() {
        val twelve = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(20, stalled(12), 12))).seats)
        val truncatedByPort = assertNotNull(RollupAssembler.assemble(parts(seats = StalledSeats(30, stalled(7), 20))).seats)

        assertEquals(5, twelve.stalled.size)
        assertEquals(12, twelve.count)
        assertEquals(5, truncatedByPort.stalled.size, "7 returned, capped at 5")
        assertEquals(20, truncatedByPort.count, "the count is the port's true total, not recomputed from the list")
    }

    // ------------------------------------------------------------------ S17 omitted run parts

    @Test
    fun `S17 null run parts are omitted and supplied run parts pass through`() {
        val bare = RollupAssembler.assemble(parts())
        val full =
            RollupAssembler.assemble(
                parts(
                    scope = RollupScope(anchorId = null, runId = run, rootId = root),
                    run = header,
                    blockedReasons = mapOf("dependency" to 2, "decision" to 1),
                    decisions = decisions,
                    seats = StalledSeats(active = 4, stalled = stalled(2), count = 2),
                    attempts = attempts,
                    budget = budget
                )
            )

        assertNull(bare.run)
        assertNull(bare.blocked.byReason)
        assertNull(bare.decisions)
        assertNull(bare.seats)
        assertNull(bare.attempts)
        assertNull(bare.budget)
        assertEquals(header, full.run)
        assertEquals(mapOf("dependency" to 2, "decision" to 1), full.blocked.byReason)
        assertEquals(decisions, full.decisions)
        assertEquals(RollupSeats(active = 4, stalled = stalled(2), count = 2), full.seats)
        assertEquals(attempts, full.attempts)
        assertEquals(budget, full.budget)
        assertNull(full.scope.anchorId)
        assertEquals(run, full.scope.runId)
    }

    @Test
    fun `S17 probe an empty blocked-reason map is present and empty, a null one is absent`() {
        val empty = RollupAssembler.assemble(parts(blockedReasons = emptyMap()))
        val absent = RollupAssembler.assemble(parts(blockedReasons = null))

        assertEquals(emptyMap(), empty.blocked.byReason)
        assertNull(absent.blocked.byReason)
    }

    @Test
    fun `S17 the not-tracked stub answers null from all seven methods`(): Unit =
        runBlocking {
            val id = UUID.randomUUID()

            assertNull(NotTrackedRunFacts.run(id))
            assertNull(NotTrackedRunFacts.members(id))
            assertNull(NotTrackedRunFacts.blockedReasons(setOf(id)))
            assertNull(NotTrackedRunFacts.blockedReasons(emptySet()))
            assertNull(NotTrackedRunFacts.openDecisions(id))
            assertNull(NotTrackedRunFacts.stalledSeats(id, RollupAssembler.STALLED_CAP))
            assertNull(NotTrackedRunFacts.attempts(id))
            assertNull(NotTrackedRunFacts.budget(id))
        }

    // ------------------------------------------------------------------ wire shape

    @Test
    fun `wire - an anchor rollup has the declared member order and omits absent parts`() {
        val json = rollupJson(RollupAssembler.assemble(parts(roleCounts = linkedMapOf("work" to 2L, "queue" to 1L))))

        assertEquals(listOf("scope", "window", "items", "blocked", "transitions", "notes", "cost"), json.keys.toList())
        assertEquals(
            listOf("anchorId", "rootId"),
            json
                .getValue("scope")
                .jsonObject.keys
                .toList()
        )
        assertEquals(
            listOf("since", "until"),
            json
                .getValue("window")
                .jsonObject.keys
                .toList(),
            "clamped is written only when true"
        )
        assertEquals(
            listOf("queue", "work"),
            json
                .getValue("items")
                .jsonObject
                .getValue("byRole")
                .jsonObject.keys
                .toList()
        )
        assertEquals(
            "3",
            json
                .getValue("items")
                .jsonObject
                .getValue("total")
                .jsonPrimitive.content
        )
        assertEquals(
            listOf("total"),
            json
                .getValue("blocked")
                .jsonObject.keys
                .toList()
        )
        assertEquals(
            "f1000001",
            json
                .getValue("scope")
                .jsonObject
                .getValue("anchorId")
                .jsonPrimitive.content
        )
        assertEquals(
            "a1a1a1a1",
            json
                .getValue("scope")
                .jsonObject
                .getValue("rootId")
                .jsonPrimitive.content
        )
        assertEquals(
            "targets",
            json
                .getValue("cost")
                .jsonObject
                .getValue("basis")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `wire - a clamped window writes clamped and a run rollup writes every optional member in the declared order`() {
        val seatList = stalled(2)
        val report =
            RollupAssembler.assemble(
                parts(
                    roleCounts = mapOf("blocked" to 2L, "work" to 1L),
                    scope = RollupScope(anchorId = null, runId = run, rootId = root),
                    window = RollupWindow(since, until, clamped = true),
                    run = header,
                    blockedReasons = mapOf("dependency" to 2),
                    decisions = decisions,
                    seats = StalledSeats(active = 4, stalled = seatList, count = 2),
                    attempts = attempts,
                    budget = budget
                )
            )

        val json = rollupJson(report)

        assertEquals(
            listOf(
                "scope",
                "window",
                "run",
                "items",
                "blocked",
                "decisions",
                "seats",
                "transitions",
                "notes",
                "cost",
                "attempts",
                "budget"
            ),
            json.keys.toList()
        )
        assertEquals(
            listOf("runId", "rootId"),
            json
                .getValue("scope")
                .jsonObject.keys
                .toList()
        )
        assertEquals(
            listOf("since", "until", "clamped"),
            json
                .getValue("window")
                .jsonObject.keys
                .toList()
        )
        assertEquals(
            listOf("total", "byReason"),
            json
                .getValue("blocked")
                .jsonObject.keys
                .toList()
        )
        assertEquals(setOf("active", "stalled", "count"), json.getValue("seats").jsonObject.keys)
        assertEquals(
            "0f8fad5b",
            json
                .getValue("scope")
                .jsonObject
                .getValue("runId")
                .jsonPrimitive.content
        )
    }

    @Test
    fun `wire - ids are short everywhere, no full uuid reaches the payload`() {
        val seatList = stalled(3)
        val report =
            RollupAssembler.assemble(
                parts(
                    scope = RollupScope(anchorId = anchor, runId = run, rootId = root),
                    run = header,
                    seats = StalledSeats(active = 4, stalled = seatList, count = 3)
                )
            )

        val text = rollupJson(report).toString()

        for (full in listOf(anchor, run, root) + seatList.map { it.itemId }) {
            assertFalse(full.toString() in text, "the full id $full must not be on the wire")
            assertTrue(full.toString().take(8) in text, "the short id ${full.toString().take(8)} is on the wire")
        }
    }
}
