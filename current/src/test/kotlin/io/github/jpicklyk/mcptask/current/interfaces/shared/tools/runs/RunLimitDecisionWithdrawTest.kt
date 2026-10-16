package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.parity.ParityToken
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
import kotlin.test.assertTrue

/**
 * Independent tests (blind test-author seat, fix round, item acb8e2cf, finding N2) for the rule that a server-raised limit
 * decision (class `run-budget` or `attempt-cap`) cannot be withdrawn by anyone.
 *
 * Oracle: the orchestrator ruling for N2 and current/docs/api-rest.md (decisions section): such a decision "cannot be withdrawn
 * by anyone (the same `forbidden`): it stays pending until it is answered or its run is closed"; the withdraw of a decision raised
 * by a seat or coordinator by its raiser keeps working.
 *
 * Scenarios: W1 coordinator-token withdraw of run-budget is forbidden; W2 director withdraw of attempt-cap is forbidden;
 * W3 nothing changes after the refusal and the answer by the director still works; W4 regression, a raiser withdraws its own decision.
 *
 * Negative-assertion fixture conditions: every refusal asserts the code, and W4 is the control showing the same op succeeds for
 * the raiser of an ordinary decision; "still paused" is shown by a refused assign carrying the same pause decision id.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RunLimitDecisionWithdrawTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(block: suspend (SeatWorld) -> Unit) =
        runBlocking {
            val w = SeatWorld.create(db.db, dir, clock, SEAT_WORLD_CONFIG)
            try {
                block(w)
            } finally {
                w.shutdown()
            }
        }

    private val director =
        ParityToken("wd-dir", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

    private fun Reply.pausedId(): String? =
        (results().firstOrNull()?.let { findElement("paused", it) } as? JsonObject)?.get("decisionId")?.jsonPrimitive?.content

    private suspend fun open(
        w: SeatWorld,
        budget: String
    ): Opened = w.open(extra = ""","budget":$budget""")

    private fun decisions(
        w: SeatWorld,
        run: Opened
    ): List<Map<String, Any?>> =
        w.rows("SELECT * FROM decision_requests WHERE run_id = ? ORDER BY raised_at, dedupe_key", UUID.fromString(run.runId))

    private suspend fun status(
        w: SeatWorld,
        run: Opened
    ): String? = w.select("get", ""","runId":"${run.runId}"""").find("status")

    private suspend fun withdraw(
        w: SeatWorld,
        decisionId: String,
        by: ParityToken,
        coordinatorToken: String? = null
    ): Reply =
        w.director(
            "runs_write",
            "{" + (if (coordinatorToken != null) """"seatToken":"$coordinatorToken",""" else "") +
                """"ops":[{"op":"withdraw","decisionId":"$decisionId"}]}""",
            by
        )

    private suspend fun eventTypesAfter(
        w: SeatWorld,
        mark: Long
    ): List<String> = w.rig.rowsAfter(mark).map { it.type }

    /** A run paused on a budget breach: returns the run and the server-raised decision id. */
    private suspend fun budgetPaused(w: SeatWorld): Pair<Opened, String> {
        val run = open(w, """{"tokens":100}""")
        val seat = w.assignOk(run, w.m1, "builder")
        assertTrue(w.claim(w.m1, seat.token).ok())
        val beat = w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""")
        return run to assertNotNull(beat.pausedId(), "the breach pauses the run: $beat")
    }

    /** A run paused on the attempt cap: returns the run and the server-raised decision id. */
    private suspend fun attemptsPaused(w: SeatWorld): Pair<Opened, String> {
        val run = open(w, """{"attemptsPerSeat":1}""")
        w.assignOk(run, w.m1, "builder")
        val refused = w.assign(run, w.m1, "builder").reply
        assertEquals("run_paused", refused.code(), refused.toString())
        return run to assertNotNull(refused.find("pauseDecisionId", refused.error()))
    }

    @Test
    fun `W1 the coordinator token cannot withdraw a run-budget decision`(): Unit =
        world { w ->
            val (run, decisionId) = budgetPaused(w)
            assertEquals("run-budget", decisions(w, run).single()["class"], "control: the decision is the server-raised budget one")

            val reply = withdraw(w, decisionId, w.orch, coordinatorToken = run.coordinatorToken)

            assertEquals("forbidden", reply.code(), reply.toString())
            assertEquals("open", decisions(w, run).single()["status"])
        }

    @Test
    fun `W1 probe the opener principal alone and a stranger are forbidden too`(): Unit =
        world { w ->
            val (run, decisionId) = budgetPaused(w)

            assertEquals("forbidden", withdraw(w, decisionId, w.orch).code())
            assertEquals("forbidden", withdraw(w, decisionId, w.other).code())
            assertEquals("open", decisions(w, run).single()["status"])
        }

    @Test
    fun `W2 the director cannot withdraw an attempt-cap decision`(): Unit =
        world { w ->
            val (run, decisionId) = attemptsPaused(w)
            assertEquals("attempt-cap", decisions(w, run).single()["class"], "control: the decision is the server-raised attempt one")

            val byDirector = withdraw(w, decisionId, director)
            assertEquals("forbidden", byDirector.code(), byDirector.toString())

            val byCoordinator = withdraw(w, decisionId, w.orch, coordinatorToken = run.coordinatorToken)
            assertEquals("forbidden", byCoordinator.code(), byCoordinator.toString())
            assertEquals("open", decisions(w, run).single()["status"])
        }

    @Test
    fun `W3 after the refusal the decision is open, the run stays paused on the same decision, no event, and the answer still works`(): Unit =
        world { w ->
            val (run, decisionId) = budgetPaused(w)
            val mark = w.rig.maxSeq()

            assertEquals("forbidden", withdraw(w, decisionId, w.orch, coordinatorToken = run.coordinatorToken).code())
            assertEquals("forbidden", withdraw(w, decisionId, director).code())

            assertEquals("open", decisions(w, run).single()["status"])
            assertEquals("paused", status(w, run))
            val stillPaused = w.assign(run, w.m2, "implementer").reply
            assertEquals("run_paused", stillPaused.code(), stillPaused.toString())
            assertEquals(decisionId, stillPaused.find("pauseDecisionId", stillPaused.error()), "same pause decision id")
            assertTrue("decision.withdrawn" !in eventTypesAfter(w, mark), "no decision.withdrawn event: ${eventTypesAfter(w, mark)}")

            val answer =
                w.director(
                    "runs_write",
                    """{"ops":[{"op":"answer","decisionId":"$decisionId","option":"extend","amount":50}]}""",
                    director
                )
            assertTrue(answer.ok(), "the extend by the director still works: $answer")
            assertEquals("open", status(w, run))
            assertEquals("answered", decisions(w, run).single()["status"])
        }

    @Test
    fun `W3 probe an attempt-cap decision stays answerable after a refused withdraw`(): Unit =
        world { w ->
            val (run, decisionId) = attemptsPaused(w)
            assertEquals("forbidden", withdraw(w, decisionId, director).code())

            val answer =
                w.director("runs_write", """{"ops":[{"op":"answer","decisionId":"$decisionId","option":"allow_one"}]}""", director)

            assertTrue(answer.ok(), answer.toString())
            assertEquals("open", status(w, run))
        }

    @Test
    fun `W4 regression a decision raised by a caller is still withdrawn by its raiser`(): Unit =
        world { w ->
            val run = w.open()
            val raised =
                w.ops(
                    "runs_write",
                    """{"op":"raise","class":"question","question":"Which one?","options":[{"key":"a","label":"A"},""" +
                        """{"key":"b","label":"B"}],"dedupeKey":"wd-w4","runId":"${run.runId}"}""",
                    by = w.orch
                )
            val id = assertNotNull(raised.find("decisionId") ?: raised.find("id"), "a decision id in $raised")
            val mark = w.rig.maxSeq()

            val reply = withdraw(w, id, w.orch)

            assertTrue(reply.ok(), "the raiser withdraws: $reply")
            assertEquals("withdrawn", decisions(w, run).single()["status"])
            assertTrue("decision.withdrawn" in eventTypesAfter(w, mark))
        }
}
