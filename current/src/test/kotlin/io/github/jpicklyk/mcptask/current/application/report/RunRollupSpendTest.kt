package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.rollupJson
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Opened
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SEAT_WORLD_CONFIG
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f65fef01, R29) for scenario S10 (r27 spend reporting): the rollup `budget` reports how
 * often the run hit its limit (`breaches`) and how often the director raised it (`extends`).
 *
 * Surface labels: the wire members and the facts are EXISTING-SURFACE (a plain revert shows the old figures); the codec round trip is
 * NEW-SURFACE (a revert is a compile failure; the behaviour is proved by the decode of an old snapshot).
 *
 * Oracles (never read from a run), the frozen task-scope D7: `breaches` is the number of the run's `run-budget` decisions of any status
 * (a breach raises exactly one), `extends` is how many of them were answered `extend`; the attempt-cap decisions are not counted; the
 * wire object is `budget{used, limit?, basis, breaches?, extends?}` with `breaches` and `extends` omitted when 0; the snapshot codec
 * round-trips both, and a snapshot stored before this item (no such members) decodes them as 0; a closed run keeps answering its
 * stored figures. The hand numbers: limit 100, a heartbeat reporting 101 breaches (used 101); `extend` by 50 raises the limit to 150;
 * a second heartbeat reporting 151 breaches again (used 151) and the director's `stop` closes the run `budget_exhausted`. Used is the
 * larger of the reported and the observed tokens (R28 AC6), here the reported 101 and 151.
 *
 * Negative-assertion fixture conditions: the attempt-cap decision is really raised (asserted from the table) so excluding it is a real
 * exclusion; "keys absent" is paired with the breached run that carries them.
 *
 * Domain invariants satisfied by construction: runs, seats, breaches and answers all go through the tools; the codec fixtures are built
 * with the rollup's own constructors.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunRollupSpendTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val director =
        ParityToken("spend-dir", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, SEAT_WORLD_CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private suspend fun rollupOf(
        f: ReportFixture,
        runId: String
    ): RollupReport =
        when (val outcome = f.service().rollup(f.ctx(), ReportRollupRequest(runId = IdRef(runId)))) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok rollup for run $runId but got ${outcome.error}")
        }

    private suspend fun SeatWorld.breach(
        run: Opened,
        tokens: Int
    ): String {
        val beat = heartbeat(m1, assignedToken(run), usage = "{\"tokens\":$tokens}")
        return assertNotNull(
            (beat.findElement("paused") as? JsonObject)?.get("decisionId")?.jsonPrimitive?.content,
            "$tokens tokens must breach the limit: $beat"
        )
    }

    private val tokens = mutableMapOf<String, String>()

    private fun assignedToken(run: Opened): String = checkNotNull(tokens[run.runId]) { "no seat token for ${run.runId}" }

    private suspend fun SeatWorld.seatUp(run: Opened) {
        val seat = assignOk(run, m1, "builder")
        assertTrue(claim(m1, seat.token).ok(), "the seat claims its item")
        tokens[run.runId] = checkNotNull(seat.token)
    }

    private suspend fun SeatWorld.answer(
        decisionId: String,
        option: String,
        amount: Int? = null
    ) {
        val amountField = if (amount != null) ",\"amount\":$amount" else ""
        val reply =
            director(
                "runs_write",
                "{\"ops\":[{\"op\":\"answer\",\"decisionId\":\"$decisionId\",\"option\":\"$option\"$amountField}]}",
                director
            )
        assertTrue(reply.ok(), "answer $option must succeed: $reply")
    }

    private fun keysOfBudget(report: RollupReport): List<String> =
        rollupJson(report)
            .getValue("budget")
            .jsonObject.keys
            .toList()

    private fun budgetNumber(
        report: RollupReport,
        key: String
    ): Int =
        rollupJson(report)
            .getValue("budget")
            .jsonObject
            .getValue(key)
            .jsonPrimitive.content
            .toInt()

    // ------------------------------------------------------------------ S10 the figures

    @Test
    fun `S10 a breach, an extend and a second breach are counted live, and the stop stores them in the closed run's rollup`() =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open(extra = ",\"budget\":{\"tokens\":100}")
            w.seatUp(run)

            val first = w.breach(run, 101)
            assertEquals(
                BudgetFacts(used = 101, limit = 100, basis = "enforced", breaches = 1, extends = 0),
                rollupOf(f, run.runId).budget,
                "one breach, no extend"
            )
            assertEquals(listOf("used", "limit", "basis", "breaches"), keysOfBudget(rollupOf(f, run.runId)), "extends is omitted at 0")

            w.answer(first, "extend", amount = 50)
            assertEquals(
                BudgetFacts(used = 101, limit = 150, basis = "enforced", breaches = 1, extends = 1),
                rollupOf(f, run.runId).budget,
                "the extend raised the limit to 150 and is counted once"
            )

            val second = w.breach(run, 151)
            assertEquals(
                BudgetFacts(used = 151, limit = 150, basis = "enforced", breaches = 2, extends = 1),
                rollupOf(f, run.runId).budget,
                "a second breach is counted although it is still open"
            )

            w.answer(second, "stop")
            val live = rollupOf(f, run.runId)
            val expected = BudgetFacts(used = 151, limit = 150, basis = "enforced", breaches = 2, extends = 1)
            assertEquals(expected, live.budget, "the closed run's rollup keeps both figures (stop is a breach answered, not an extend)")
            assertEquals(RunStatus.CLOSED, live.run?.status)
            assertEquals(RunTerminalReason.BUDGET_EXHAUSTED, live.run?.terminalReason)
            assertEquals(listOf("used", "limit", "basis", "breaches", "extends"), keysOfBudget(live), "wire order")
            assertEquals(2, budgetNumber(live, "breaches"))
            assertEquals(1, budgetNumber(live, "extends"))

            val ended =
                Json
                    .parseToJsonElement(
                        w
                            .rows(
                                "SELECT data FROM events WHERE type = 'run.ended' AND entity_id = ?",
                                UUID.fromString(run.runId)
                            ).single()["data"] as String
                    ).jsonObject
            val stored = assertNotNull(RollupSnapshotCodec.decode(assertNotNull(ended["rollup"], "the close stored a rollup: $ended")))
            assertEquals(expected, stored.budget, "the stored snapshot carries them")
            assertEquals(stored, live, "and the closed run answers the snapshot")
        }

    @Test
    fun `S10 an attempt-cap decision is not a breach, and a run that never breached carries neither member`() =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open(extra = ",\"budget\":{\"tokens\":100,\"attemptsPerSeat\":1}")
            assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "the first attempt is allowed")
            val capped = w.assign(run, w.m1, "builder").reply
            assertEquals("run_paused", capped.code(), "the second attempt hits the cap: $capped")

            val classes = w.rows("SELECT class FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId)).map { it["class"] }
            assertEquals(listOf<Any?>("attempt-cap"), classes, "fixture: the cap raised its decision, and no budget decision exists")

            val report = rollupOf(f, run.runId)
            assertEquals(
                BudgetFacts(used = 0, limit = 100, basis = "enforced", breaches = 0, extends = 0),
                report.budget,
                "attempt-cap is not counted"
            )
            assertEquals(listOf("used", "limit", "basis"), keysOfBudget(report), "no breach: both members are absent")
        }

    @Test
    fun `S10 probe an empty run reports no breach and no extend`() =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open(members = emptyList(), extra = ",\"budget\":{\"tokens\":100}")

            val report = rollupOf(f, run.runId)

            assertEquals(BudgetFacts(used = 0, limit = 100, basis = "enforced"), report.budget)
            assertEquals(listOf("used", "limit", "basis"), keysOfBudget(report))
        }

    // ------------------------------------------------------------------ S10 the snapshot codec

    private val sample =
        RollupReport(
            scope = RollupScope(anchorId = null, runId = UUID.fromString("f8000001-0000-4000-8000-000000000001"), rootId = ReportKit.R1),
            window = RollupWindow(ReportKit.NOW.minusSeconds(7_200), ReportKit.NOW, false),
            items = RollupItems(3, mapOf("work" to 3L)),
            blocked = RollupBlocked(0, null),
            transitions = RollupTransitions(7, 3, 1),
            notesWritten = 2,
            cost = RollupCost(11, 1, 4, 5_000, 1_250, "run"),
            run =
                RunHeader(
                    RunStatus.CLOSED,
                    RunTerminalReason.BUDGET_EXHAUSTED,
                    ReportKit.NOW.minusSeconds(7_200),
                    ReportKit.NOW,
                    ReportKit.R1
                ),
            decisions = DecisionCounts(0, emptyMap(), emptyMap()),
            seats = RollupSeats(0, emptyList(), 0),
            attempts = AttemptCounts(2, 1),
            budget = BudgetFacts(used = 151, limit = 150, basis = "enforced", breaches = 2, extends = 1)
        )

    @Test
    fun `S10 the snapshot codec round-trips breaches and extends`() {
        for (budget in listOf(
            BudgetFacts(used = 151, limit = 150, basis = "enforced", breaches = 2, extends = 1),
            BudgetFacts(used = 5, limit = null, basis = "enforced", breaches = 0, extends = 0),
            BudgetFacts(used = 5, limit = 9, basis = "enforced", breaches = 4, extends = 0)
        )) {
            val report = sample.copy(budget = budget)
            assertEquals(report, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(report)), "$budget")
            assertEquals(report, RollupSnapshotCodec.decode(RollupSnapshotCodec.encode(report).toString()), "text form: $budget")
        }
    }

    @Test
    fun `S10 a snapshot stored before this item has no breaches and no extends and decodes both as 0`() {
        val encoded = RollupSnapshotCodec.encode(sample)
        val oldBudget =
            buildJsonObject {
                put("used", 151)
                put("limit", 150)
                put("basis", "enforced")
            }
        val old = JsonObject(encoded.toMutableMap().also { it["budget"] = oldBudget })

        val decoded = assertNotNull(RollupSnapshotCodec.decode(old), "an old snapshot still decodes")

        assertEquals(BudgetFacts(used = 151, limit = 150, basis = "enforced", breaches = 0, extends = 0), decoded.budget)
        assertEquals(sample.copy(budget = BudgetFacts(used = 151, limit = 150, basis = "enforced")), decoded, "nothing else changed")
    }
}
