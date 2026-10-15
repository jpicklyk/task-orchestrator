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
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (blind test-author seat, item 85d49060) for scenarios S11 and S12 of the frozen test-plan: a token budget that
 * `runs_write update` newly sets AFTER a seat was dispatched is enforced on the seat's next token-carrying write (the limit check
 * does not remember that the run had no budget when the seat was assigned).
 *
 * Oracles (never read from a run): `api-reference.md` for `runs_write` `update` (a budget field "can only be lowered or newly set",
 * coordinator only, on an open run) and `api-rest.md` "Budgets": the enforced figure is max(reported tokens, observed tokens), a
 * breach is strictly over the limit, a crossing pauses the run for `budget` and raises a `run-budget` decision in the same unit
 * (answered to the caller as `paused{decisionId}`), and a seat-token `start` while the run is paused for a limit is `run_paused`. A
 * run opened without a budget is never paused, however much it reports or observes.
 *
 * Negative-assertion fixture conditions: "not paused" before the update is paired with the same heartbeat pausing right after the
 * update (the only difference is the budget the update set); "the start is refused" is paired with the same start succeeding on a
 * seat of an unpaused run elsewhere in the existing budget tests (RunsBudgetsCapsTest S10 probe) and with the item still in `queue`.
 *
 * Domain invariants satisfied by construction: runs are opened through the tool over members that are children of the seeded root;
 * seats are assigned through the coordinator token; the budget is set through the tool itself, so the stored state is what the
 * service produced; call-log rows are hand-built records whose run id is a run the test opened.
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

    private fun Reply.pausedId(): String? = (findElement("paused") as? JsonObject)?.get("decisionId")?.jsonPrimitive?.content

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

    private suspend fun SeatWorld.setTokenBudget(
        run: Opened,
        tokens: Long
    ): Reply = ops("runs_write", """{"op":"update","runId":"${run.runId}","budget":{"tokens":$tokens}}""", token = run.coordinatorToken)

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

    // ------------------------------------------------------------------ S11 reported usage

    @Test
    fun `S11 a token budget set by update after dispatch pauses the run on the seat's next heartbeat and blocks the other seat's start`() =
        world { w ->
            val run = w.open(members = listOf(w.m1, w.m2))
            val a = seatUp(w, run, w.m1)
            val b = seatUp(w, run, w.m2, "implementer")

            val before = w.heartbeat(w.m1, a.token, usage = """{"tokens":101}""")
            assertTrue(before.ok(), "the heartbeat succeeds: $before")
            assertNull(before.pausedId(), "a run without a budget is never paused, however much the seat reports: $before")
            assertEquals(0, w.decisionsOf(run).size, "no decision without a budget")

            val update = w.setTokenBudget(run, 100)
            assertTrue(update.ok(), "the coordinator newly sets a token budget on the open run: $update")
            assertEquals("100", w.view(run).find("tokens"), "the run view shows the new limit")

            val after = w.heartbeat(w.m1, a.token)

            assertTrue(after.ok(), "the heartbeat itself still succeeds: $after")
            val decisionId = assertNotNull(after.pausedId(), "the same heartbeat now breaches 101 > 100 and pauses the run: $after")
            assertEquals("paused", w.view(run).find("status"))
            val decision = w.decisionsOf(run).single()
            assertEquals("run-budget", decision["class"], "the pause is a run-budget decision $decisionId")
            assertEquals("101", decision.contextField("used"), "enforced tokens are max(reported 101, observed 0)")
            assertEquals("100", decision.contextField("limit"))

            val refused = w.start(w.m2, b.token)
            assertEquals("run_paused", refused.code(), "another seat's token start is refused while the run is paused: $refused")
            assertEquals("queue", w.itemRow(w.m2)["role"], "and the item did not start")
        }

    // ------------------------------------------------------------------ S12 observed usage

    @Test
    fun `S12 a token budget set by update after dispatch is enforced against tokens the writer already observed`() =
        world { w ->
            val run = w.open(members = listOf(w.m1))
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(record(run, 60, 1))
            writer.flushNow()
            val unlimited = w.heartbeat(w.m1, seat.token)
            assertTrue(unlimited.ok() && unlimited.pausedId() == null, "no budget, no pause, whatever was observed: $unlimited")

            assertTrue(w.setTokenBudget(run, 50).ok(), "fixture: the budget is newly set below the observed 60")
            val beat = w.heartbeat(w.m1, seat.token)

            assertTrue(beat.ok(), "the heartbeat succeeds: $beat")
            assertNotNull(beat.pausedId(), "a heartbeat with no usage now enforces max(reported 0, observed 60) > 50: $beat")
            val view = w.view(run)
            assertEquals("paused", view.find("status"))
            assertEquals("0", view.find("reportedTokens"), "nothing was reported, the figure is the observed one")
            val decision = w.decisionsOf(run).single()
            assertEquals("run-budget", decision["class"])
            assertEquals("60", decision.contextField("used"))
            assertEquals("50", decision.contextField("limit"))
        }
}
