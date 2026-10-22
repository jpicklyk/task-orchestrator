package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.Opened
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SEAT_WORLD_CONFIG
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.SeatWorld
import io.github.jpicklyk.mcptask.current.parity.ParityToken
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
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 74bb6db9, R28) for scenarios S10 and S11 of the frozen test-plan (NEW-SURFACE): every
 * close of a run stores the rollup in `run.ended`, and a closed run answers that stored snapshot. They drive the production wiring
 * (the `SeatWorld` rig: the real composition over SQLite, the real `runs_write` tool and the real close path), so the one wiring site
 * of the snapshot seam is the thing under test.
 *
 * Oracles: task-scope AC8 (the snapshot is computed inside the close unit for EVERY terminal reason, the budget_exhausted decision stop
 * included, and stored under `rollup` in the `run.ended` data; the stored form decodes to the rollup of the run as the close left
 * it), AC9 (a CLOSED run whose `run.ended` carries a decodable rollup answers that snapshot, so figures survive retention and late
 * call_log rows; an open run, a pre-R28 close, or a snapshot that cannot be decoded is computed live) and AC6 (budget used is the
 * larger of reported and observed; a closed run has no active seat). The expected report of each close is the live rollup read just
 * before the close, with the header the close writes (status closed, the reason, the close instant): the window ends at the same
 * instant because the clock does not move.
 *
 * Negative-assertion conditions: "no rollup after a late write" is paired with the live control that DOES see the late write (the
 * snapshot is corrupted so the read must compute live); the stamped rows added before the close make the snapshot figures non-zero,
 * so an empty snapshot cannot equal the live one.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: runs are opened and closed through the tool, so the stored state is whatever the service
 * produced; the extra stamped rows are children of the run's root (their entity is a member of the run).
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class RunRollupSnapshotTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private val director =
        ParityToken("snap-dir", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

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

    private fun endedData(
        w: SeatWorld,
        runId: String
    ): JsonObject {
        val rows = w.rows("SELECT data FROM events WHERE type = 'run.ended' AND entity_id = ?", UUID.fromString(runId))
        assertEquals(1, rows.size, "exactly one run.ended row for the run")
        return Json.parseToJsonElement(rows.single().getValue("data") as String).jsonObject
    }

    /** Stamped rows that make the snapshot figures non-zero: two transitions (one cascade) and a note, one rejection, two calls. */
    private suspend fun stampRows(
        w: SeatWorld,
        f: ReportFixture,
        run: Opened
    ) {
        val runId = UUID.fromString(run.runId)
        val at = ReportKit.NOW.minusSeconds(600)
        RollupRaw.write(w.database.jdbcUrl) {
            moved(at, w.m1.id, w.root.id, "work", run = runId)
            moved(at.plusSeconds(1), w.m2.id, w.root.id, "work", run = runId, origin = "cascade")
            runEvent(DomainEvent.TRANSITION_REJECTED, at.plusSeconds(2), w.m3.id, w.root.id, runId, "{\"code\":\"gate_blocked\"}")
            runEvent(DomainEvent.NOTE_UPSERTED, at.plusSeconds(3), UUID.randomUUID(), w.root.id, runId, "{\"key\":\"k\"}", kind = "note")
        }
        f.seed(
            ReportKit.rec(910, at, runId = runId, rootId = w.root.id, responseBytes = 40),
            ReportKit.rec(911, at, runId = runId, rootId = w.root.id, responseBytes = 80, error = "gate_blocked", attempts = 2)
        )
    }

    private fun closeCase(
        reason: String,
        terminal: RunTerminalReason
    ) = world { w ->
        val f = ReportFixture(w.database, clock)
        val run = w.open()
        stampRows(w, f, run)
        val before = rollupOf(f, run.runId)
        assertEquals(
            RollupTransitions(total = 2, cascades = 1, rejected = 1),
            before.transitions,
            "control: the live rollup sees the stamped rows"
        )
        assertEquals(2L, before.cost.calls)

        val closed = w.close(run, reason, run.coordinatorToken)
        assertTrue(closed.ok(), "close as $reason must succeed: $closed")

        val data = endedData(w, run.runId)
        assertEquals(reason, data.getValue("reason").jsonPrimitive.content)
        val stored = assertNotNull(data["rollup"], "run.ended carries the rollup snapshot for a $reason close: $data")
        val decoded = assertNotNull(RollupSnapshotCodec.decode(stored), "the stored rollup decodes")
        val expected = before.copy(run = RunHeader(RunStatus.CLOSED, terminal, before.run!!.openedAt, clock.now(), w.root.id))
        assertEquals(expected, decoded, "the snapshot is the live rollup as the close left it")
        assertTrue(run.runId in stored.toString(), "the run id is stored in full")
        assertTrue(w.root.id.toString() in stored.toString(), "the root id is stored in full")
        assertEquals(decoded, rollupOf(f, run.runId), "a closed run answers its snapshot")
    }

    // ------------------------------------------------------------------ S10 every terminal reason stores the snapshot

    @Test
    fun `S10 a completed close stores the rollup in run ended`(): Unit = closeCase("completed", RunTerminalReason.COMPLETED)

    @Test
    fun `S10 a cancelled close stores the rollup in run ended`(): Unit = closeCase("cancelled", RunTerminalReason.CANCELLED)

    @Test
    fun `S10 a failed close stores the rollup in run ended`(): Unit = closeCase("failed", RunTerminalReason.FAILED)

    @Test
    fun `S10 an abandoned close stores the rollup in run ended`(): Unit = closeCase("abandoned", RunTerminalReason.ABANDONED)

    @Test
    fun `S10 the budget stop of the director closes budget_exhausted and stores the rollup with the cascade applied`(): Unit =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open(extra = ",\"budget\":{\"tokens\":100}")
            val seat = w.assignOk(run, w.m1, "builder")
            assertTrue(w.claim(w.m1, seat.token).ok(), "the seat claims its item")
            val beat = w.heartbeat(w.m1, seat.token, usage = "{\"tokens\":101}")
            val paused =
                (
                    beat
                        .results()
                        .getOrNull(
                            0
                        )?.let { beat.findElement("paused", it) } as? JsonObject
                )?.get("decisionId")?.jsonPrimitive?.content
            val decisionId = assertNotNull(paused, "101 over a limit of 100 pauses the run on a decision: $beat")

            val stopped =
                w.director(
                    "runs_write",
                    "{\"ops\":[{\"op\":\"answer\",\"decisionId\":\"$decisionId\",\"option\":\"stop\"}]}",
                    director
                )
            assertTrue(stopped.ok(), "the director's stop must succeed: $stopped")

            val data = endedData(w, run.runId)
            assertEquals("budget_exhausted", data.getValue("reason").jsonPrimitive.content)
            val decoded =
                assertNotNull(
                    RollupSnapshotCodec.decode(assertNotNull(data["rollup"], "the decision stop path stores the snapshot too: $data"))
                )
            assertEquals(RunStatus.CLOSED, decoded.run?.status)
            assertEquals(RunTerminalReason.BUDGET_EXHAUSTED, decoded.run?.terminalReason)
            assertEquals(BudgetFacts(used = 101, limit = 100, basis = "enforced", breaches = 1, extends = 0), decoded.budget)
            assertEquals(AttemptCounts(total = 1, max = 1), decoded.attempts, "the one seat assignment")
            assertEquals(0, decoded.seats?.active, "the close cascade ended the seat before the snapshot: a closed run has no active seat")
            assertEquals(3L, decoded.items.total)
            assertEquals(decoded, rollupOf(f, run.runId))
        }

    // ------------------------------------------------------------------ S11 closed runs answer the stored snapshot

    @Test
    fun `S11 a closed run keeps answering its snapshot after later call rows and after its events are gone, an open run does not`(): Unit =
        world { w ->
            val f = ReportFixture(w.database, clock)
            val run = w.open()
            val runUuid = UUID.fromString(run.runId)
            stampRows(w, f, run)
            val openFirst = rollupOf(f, run.runId)
            assertEquals(2L, openFirst.cost.calls)

            // control: an OPEN run shows a newly stamped call at once
            f.seed(ReportKit.rec(912, ReportKit.NOW.minusSeconds(500), runId = runUuid, rootId = w.root.id, responseBytes = 120))
            val openSecond = rollupOf(f, run.runId)
            assertEquals(3L, openSecond.cost.calls, "the open run is computed live")
            assertEquals(240L, openSecond.cost.responseBytes)

            assertTrue(w.close(run, "completed", run.coordinatorToken).ok())

            // after the close: a late stamped call and the removal of the stamped item events
            f.seed(ReportKit.rec(913, ReportKit.NOW.minusSeconds(400), runId = runUuid, rootId = w.root.id, responseBytes = 1_000))
            w.exec("DELETE FROM events WHERE run_id = ? AND type = 'item.transitioned'", runUuid)

            val closed = rollupOf(f, run.runId)
            assertEquals(openSecond.cost, closed.cost, "the late call is not in the closed run's figures")
            assertEquals(
                openSecond.transitions,
                closed.transitions,
                "the deleted events are still counted: the figures come from the snapshot"
            )
            assertEquals(RunStatus.CLOSED, closed.run?.status)

            // control: with the stored rollup made undecodable, the same read computes live and sees both changes
            w.exec("UPDATE events SET data = json_set(data, '\$.rollup', 'garbage') WHERE type = 'run.ended' AND entity_id = ?", runUuid)
            val live = rollupOf(f, run.runId)
            assertEquals(4L, live.cost.calls, "live: the two stamped calls of the open phase, the third and the late one")
            assertEquals(1_240L, live.cost.responseBytes)
            assertEquals(0L, live.transitions.total, "live: the stamped transitions were deleted")
            assertEquals(RunStatus.CLOSED, live.run?.status)
            assertFalse(live == closed, "the live figures differ from the snapshot: the snapshot was really what answered")
        }
}
