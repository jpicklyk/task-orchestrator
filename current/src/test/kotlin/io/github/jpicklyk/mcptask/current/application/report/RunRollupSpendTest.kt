package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.rollupJson
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
 * Tests for the 4.0.1 removal of the rollup `budget` block (item e74ff75e): a run rollup carries no `budget` member, live or
 * stored, and a snapshot stored by 4.0.0 with a `budget` member still decodes (the member is ignored).
 *
 * Oracles (never read from a run): the wire members of a run rollup are `run`, `decisions`, `seats`, `attempts` and the common
 * sections; none is `budget`. A stored 4.0.0 snapshot is the current encoding plus a `budget{used, limit, basis, breaches,
 * extends}` object; decoding it yields the current report.
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

    // ------------------------------------------------------------------ the live and stored rollup

    @Test
    fun `S10 a run rollup has no budget member, live, and the attempt-cap stop stores the same shape`(): Unit =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open(extra = ",\"budget\":{\"attemptsPerSeat\":1}")
            assertTrue(w.assign(run, w.m1, "builder").reply.ok(), "the first attempt is allowed")
            val capped = w.assign(run, w.m1, "builder").reply
            assertEquals("run_paused", capped.code(), "the second attempt hits the cap: $capped")

            val before = rollupOf(f, run.runId)
            assertTrue("budget" !in rollupJson(before).keys, "the live rollup has no budget member: ${rollupJson(before).keys}")
            assertTrue("attempts" in rollupJson(before).keys, "control: the attempts section is still reported")

            val decisionId =
                w.uuidOf(w.rows("SELECT id FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId)).single()["id"])
            val reply =
                w.director(
                    "runs_write",
                    "{\"ops\":[{\"op\":\"answer\",\"decisionId\":\"$decisionId\",\"option\":\"stop\"}]}",
                    director
                )
            assertTrue(reply.ok(), "answer stop must succeed: $reply")

            val live = rollupOf(f, run.runId)
            assertEquals(RunStatus.CLOSED, live.run?.status)
            assertEquals(RunTerminalReason.BUDGET_EXHAUSTED, live.run?.terminalReason)
            assertTrue("budget" !in rollupJson(live).keys, "the closed rollup has no budget member")

            val ended =
                Json
                    .parseToJsonElement(
                        w
                            .rows(
                                "SELECT data FROM events WHERE type = 'run.ended' AND entity_id = ?",
                                UUID.fromString(run.runId)
                            ).single()["data"] as String
                    ).jsonObject
            val storedJson = assertNotNull(ended["rollup"], "the close stored a rollup: $ended").jsonObject
            assertTrue("budget" !in storedJson.keys, "the stored snapshot has no budget member: ${storedJson.keys}")
            assertEquals(assertNotNull(RollupSnapshotCodec.decode(storedJson)), live, "and the closed run answers the snapshot")
        }

    // ------------------------------------------------------------------ the snapshot codec

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
            attempts = AttemptCounts(2, 1)
        )

    @Test
    fun `S10 the snapshot codec round-trips a run rollup and encodes no budget`() {
        val encoded = RollupSnapshotCodec.encode(sample)
        assertTrue("budget" !in encoded.keys, "no budget member: ${encoded.keys}")
        assertEquals(sample, RollupSnapshotCodec.decode(encoded))
        assertEquals(sample, RollupSnapshotCodec.decode(encoded.toString()), "text form")
    }

    @Test
    fun `S10 a snapshot stored by 4_0_0 with a budget member still decodes, the member is ignored`() {
        val encoded = RollupSnapshotCodec.encode(sample)
        val stored =
            buildJsonObject {
                put("used", 151)
                put("limit", 150)
                put("basis", "enforced")
                put("breaches", 2)
                put("extends", 1)
            }
        val old = JsonObject(encoded.toMutableMap().also { it["budget"] = stored })

        val decoded = assertNotNull(RollupSnapshotCodec.decode(old), "a 4.0.0 snapshot still decodes")

        assertEquals(sample, decoded, "nothing else changed")
    }
}
