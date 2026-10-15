package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.StatementTap
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
 * Independent tests (blind test-author seat, item 85d49060) for the observed-token counter of a run (`runs.observed_tokens`) as the
 * call-log writer feeds it, scenarios S1 to S6 of the frozen test-plan, with its probes. The writer is the production
 * `CallLogWriter` over the production `SqliteCallLogStore` and the real run store of the `SeatWorld` rig; the database is read back
 * by SQL, and the enforced budget is read through the real MCP adapter (`advance` heartbeat).
 *
 * Oracles (never read from a run): `api-rest.md` "Budgets" ("observed" is the estimate of every call-log row that carries the run)
 * and the `CallLogStore` contract (the insert is per row and ignores a row whose request id is already stored, and a row the
 * database refuses is not stored); the frozen task-scope D1 acceptance: the observed tokens of a run equal the sum of
 * `response_tokens_est` over its call_log rows written by the writer, a duplicate request id (same batch or a later one) and a
 * refused row add nothing, and the writer updates `runs` once per distinct run that has a stored positive estimate and not at all
 * for a run whose rows were all dropped. The pause rule is the R27 one already pinned by `RunsBudgetsCapsTest`: enforced tokens =
 * max(reported, observed), a breach is strictly over the limit, and the writer never runs the check (the next token-carrying write
 * does).
 *
 * Arbitration: the stored copy of a duplicate request id is the first one (an insert that ignores the later row), the oracle the
 * plan states for S1 ("1 call_log row (42)"); every assertion about observed tokens is also stated as "equals the SUM of the stored
 * rows" so it does not depend on which copy wins.
 *
 * Negative-assertion fixture conditions: "the duplicate adds nothing" fails without the fix because the batch is summed (82, not
 * 42); "the refused row adds nothing" fails without the fix for the same reason (35, not 5); "no UPDATE for the run whose rows were
 * all dropped" fails without the fix because the run would receive the dropped row's estimate; "not paused at 42 of 50" fails
 * without the fix because the observed counter would read 82 and breach at the first heartbeat.
 *
 * Domain invariants satisfied by construction: runs are opened through the tool itself over members that are children of the
 * seeded root; call-log rows are hand-built records whose run id is a run the test opened and whose request ids are the shared
 * `testReqId` shape; the refused row is the one the table's CHECK refuses (negative latency).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsObservedTokensDedupTest {
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

    // ------------------------------------------------------------------ helpers

    private fun writerFor(w: SeatWorld): CallLogWriter =
        CallLogWriter(
            w.rig.composition.unitOfWork,
            SqliteCallLogStore(w.database.databaseManager),
            flushInterval = 1.hours,
            runStore = w.rig.raw.runStore()
        )

    private fun id(n: Int): String = testReqId(n)

    private fun rec(
        run: Opened?,
        est: Long?,
        reqId: String,
        latencyMs: Long = 1
    ): CallLogRecord =
        CallLogRecord(
            reqId = reqId,
            at = Instant.parse("2026-10-15T12:00:00Z"),
            runId = run?.let { UUID.fromString(it.runId) },
            surface = CallLogRecord.SURFACE_MCP,
            tool = "advance",
            outcome = CallLogRecord.OUTCOME_OK,
            latencyMs = latencyMs,
            responseTokensEst = est
        )

    private fun observed(
        w: SeatWorld,
        run: Opened
    ): Long =
        w.rows("SELECT observed_tokens FROM runs WHERE id = ?", UUID.fromString(run.runId)).single().getValue("observed_tokens") as Long

    private fun storedSum(
        w: SeatWorld,
        run: Opened
    ): Long =
        (
            w
                .rows("SELECT COALESCE(SUM(response_tokens_est), 0) AS s FROM call_log WHERE run_id = ?", UUID.fromString(run.runId))
                .single()
                .getValue("s") as Number
        ).toLong()

    private fun storedRows(
        w: SeatWorld,
        run: Opened
    ): Int = w.rows("SELECT req_id FROM call_log WHERE run_id = ?", UUID.fromString(run.runId)).size

    private fun updatesOfRuns(sql: List<String>): Int = sql.count { StatementTap.updates(it, "runs") }

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

    // ------------------------------------------------------------------ S1 a duplicate request id inside one batch

    @Test
    fun `S1 a duplicate request id in one batch is counted once in observed tokens`() =
        world { w ->
            val run = w.open(members = listOf(w.m1))
            val writer = writerFor(w)
            writer.submit(rec(run, 42, id(1)))
            writer.submit(rec(run, 40, id(1)))

            writer.flushNow()

            assertEquals(1, storedRows(w, run), "call_log keeps one row for the replayed request id")
            assertEquals(42L, storedSum(w, run), "and it is the first copy")
            assertEquals(42L, observed(w, run), "observed tokens count the stored row once, not 42 + 40 = 82")
            assertEquals(1L, writer.duplicates, "control: the writer saw the second copy as a duplicate")
        }

    // ------------------------------------------------------------------ S2 a duplicate in a later batch

    @Test
    fun `S2 a request id already stored by an earlier flush adds nothing when it is replayed in a later flush`() =
        world { w ->
            val run = w.open(members = listOf(w.m1))
            val writer = writerFor(w)
            writer.submit(rec(run, 42, id(1)))
            writer.flushNow()
            assertEquals(42L, observed(w, run), "control: the first flush counts its row")

            writer.submit(rec(run, 40, id(1)))
            writer.submit(rec(run, 10, id(2)))
            writer.flushNow()

            assertEquals(2, storedRows(w, run), "two distinct request ids are stored")
            assertEquals(52L, storedSum(w, run), "42 + 10")
            assertEquals(52L, observed(w, run), "the replayed row adds nothing: 42 + 10, not 42 + 40 + 10 = 92")
        }

    // ------------------------------------------------------------------ S3 a refused row

    @Test
    fun `S3 a row the database refuses adds nothing to observed tokens and the good row of the batch does`() =
        world { w ->
            val run = w.open(members = listOf(w.m1))
            val writer = writerFor(w)
            writer.submit(rec(run, 30, id(1), latencyMs = -1))
            writer.submit(rec(run, 5, id(2)))

            writer.flushNow()

            assertEquals(1L, writer.failed, "control: the refused row is counted as failed")
            assertEquals(1, storedRows(w, run), "only the good row is stored")
            assertEquals(5L, storedSum(w, run))
            assertEquals(5L, observed(w, run), "observed tokens are 5, not 30 + 5 = 35")
        }

    // ------------------------------------------------------------------ S4 one UPDATE per run with a stored estimate

    @Test
    fun `S4 a duplicate-only run gets no UPDATE in the batch while a run with a fresh row gets exactly one`() =
        world { w ->
            val r2 = w.open(members = listOf(w.m1))
            val r3 = w.open(members = listOf(w.m2))
            val writer = writerFor(w)
            writer.submit(rec(r2, 42, id(1)))
            writer.flushNow()
            assertEquals(42L, observed(w, r2), "fixture: R2 already holds request id 1")

            writer.submit(rec(r2, 40, id(1)))
            writer.submit(rec(r3, 10, id(2)))
            val (written, sql) = StatementTap.record { writer.flushNow() }

            assertTrue(written >= 1, "control: the fresh row is written, got $written")
            assertEquals(
                1,
                updatesOfRuns(sql),
                "one UPDATE runs, for R3 only; R2's only row is a replay:\n${sql.joinToString("\n")}"
            )
            assertEquals(42L, observed(w, r2), "R2 is unchanged")
            assertEquals(10L, observed(w, r3), "R3 counts its fresh row")
        }

    @Test
    fun `S4 probe resubmitting an identical flushed batch changes nothing and updates no run`() =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val b = w.open(members = listOf(w.m2))
            val writer = writerFor(w)
            writer.submit(rec(a, 10, id(1)))
            writer.submit(rec(b, 20, id(2)))
            writer.flushNow()
            assertEquals(10L, observed(w, a))
            assertEquals(20L, observed(w, b))

            writer.submit(rec(a, 10, id(1)))
            writer.submit(rec(b, 20, id(2)))
            val (_, sql) = StatementTap.record { writer.flushNow() }

            assertEquals(0, updatesOfRuns(sql), "a pure replay updates no run:\n${sql.joinToString("\n")}")
            assertEquals(10L, observed(w, a))
            assertEquals(20L, observed(w, b))
            assertEquals(1, storedRows(w, a))
            assertEquals(1, storedRows(w, b))
        }

    // ------------------------------------------------------------------ S5 the counter equals the stored estimates

    @Test
    fun `S5 after a mixed batch every run's observed tokens equal the sum of its stored estimates`() =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val b = w.open(members = listOf(w.m2))
            val writer = writerFor(w)
            writer.submit(rec(a, 10, id(1)))
            writer.submit(rec(a, 20, id(2)))
            writer.submit(rec(a, 15, id(1))) // replay of A's own row
            writer.submit(rec(a, 7, id(3), latencyMs = -1)) // refused
            writer.submit(rec(a, null, id(4))) // no estimate
            writer.submit(rec(a, 0, id(5))) // zero estimate
            writer.submit(rec(b, 5, id(6)))
            writer.submit(rec(b, 5, id(7)))
            writer.submit(rec(a, 100, id(6))) // replay of B's row, pointed at A
            writer.submit(rec(null, 99, id(8))) // no run
            writer.submit(rec(null, null, id(9))) // no run, no estimate

            writer.flushNow()

            assertEquals(30L, storedSum(w, a), "fixture: A's stored estimates are 10 + 20")
            assertEquals(10L, storedSum(w, b), "fixture: B's stored estimates are 5 + 5")
            assertEquals(30L, observed(w, a), "A: 10 + 20; the replays, the refused row and the empty estimates add nothing")
            assertEquals(10L, observed(w, b), "B: 5 + 5")
            for (run in listOf(a, b)) {
                assertEquals(storedSum(w, run), observed(w, run), "observed tokens equal the stored estimates of the run")
            }
        }

    @Test
    fun `S5 probe a replay whose first copy carried no run or no estimate adds nothing to the run of the second copy`() =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val writer = writerFor(w)
            writer.submit(rec(null, 3, id(1)))
            writer.submit(rec(a, 40, id(1))) // the first copy (no run) won the request id
            writer.submit(rec(a, null, id(2)))
            writer.submit(rec(a, 40, id(2))) // the first copy (no estimate) won the request id
            writer.submit(rec(a, 8, id(3)))

            writer.flushNow()

            assertEquals(8L, storedSum(w, a), "fixture: only request id 3 stored an estimate for A")
            assertEquals(8L, observed(w, a), "A counts the one stored estimate, not 40 + 40 + 8")
        }

    @Test
    fun `S5 probe a refused copy does not consume its request id, so a valid copy later in the batch is stored and counted`() =
        world { w ->
            val a = w.open(members = listOf(w.m1))
            val writer = writerFor(w)
            writer.submit(rec(a, 30, id(1), latencyMs = -1))
            writer.submit(rec(a, 5, id(1)))

            writer.flushNow()

            assertEquals(1, storedRows(w, a), "the valid copy is stored")
            assertEquals(5L, storedSum(w, a))
            assertEquals(5L, observed(w, a), "and counted once: 5, not 30 + 5")
        }

    // ------------------------------------------------------------------ S6 the enforced figure

    @Test
    fun `S6 a replayed request id does not push the run over its token budget, a distinct row that does is still enforced`() =
        world { w ->
            val run = w.open(members = listOf(w.m1), extra = ""","budget":{"tokens":50}""")
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(rec(run, 42, id(1)))
            writer.submit(rec(run, 40, id(1)))
            writer.flushNow()
            assertEquals(42L, observed(w, run), "fixture: the replay is not counted")

            val under = w.heartbeat(w.m1, seat.token)

            assertTrue(under.ok(), "the heartbeat succeeds: $under")
            assertNull(under.pausedId(), "42 of 50 is within the budget, so the run is not paused: $under")
            assertEquals("open", w.select("get", ""","runId":"${run.runId}"""").find("status"))
            assertEquals(0, w.rows("SELECT id FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId)).size)

            // control: one more distinct row that really takes the figure over the limit pauses the run
            writer.submit(rec(run, 9, id(2)))
            writer.flushNow()
            assertEquals(51L, observed(w, run))
            val over = w.heartbeat(w.m1, seat.token)

            val decisionId = assertNotNull(over.pausedId(), "51 over 50 pauses the run: $over")
            val decision = w.rows("SELECT id, class, context FROM decision_requests WHERE run_id = ?", UUID.fromString(run.runId)).single()
            assertEquals("run-budget", decision["class"], "the pause is a run-budget decision $decisionId")
            val context = Json.parseToJsonElement(decision["context"] as String).jsonObject
            assertEquals("51", context["used"]?.jsonPrimitive?.content, "enforced tokens are max(reported 0, observed 51)")
            assertEquals("50", context["limit"]?.jsonPrimitive?.content)
        }
}
