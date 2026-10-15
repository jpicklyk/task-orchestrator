package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests (blind test-author seat, item acb8e2cf, R27) for run budgets, attempt caps and the active-seat cap, driven
 * through the real MCP adapter and the production dispatcher over the real composition and SQLite (the `SeatWorld` rig), plus the
 * call-log writer for the observed-token counter.
 *
 * Scenarios S1 to S15 of the frozen test-plan, with its probes.
 *
 * Oracles (never read from a run): task-scope R27 (usage summed into `runs.reported_*`; the writer only adds `response_tokens_est`
 * into `runs.observed_tokens` and never runs the check, D1; enforced tokens = max(reported, observed), cost = reported, D4; the
 * in-unit check on heartbeat, assign, end_seat, transition and raise; a crossing pauses the run for `budget`, raises `run-budget`
 * with dedupe `budget:<runId>:<n>`, options extend/stop, recommended stop and the numbers as context, committed although the write
 * succeeds and answered with `data.paused{decisionId}`; while paused `assign` and a seat-token `start` are `run_paused`; the
 * (cap+1)th assign is refused, pauses the run for `attempts` and raises `attempt-cap`; extend, stop, allow_one and skip_item
 * effects; `run_at_capacity{runId, active, max}` beyond `max_active_seats`, the coordinator not counted, D6), the test-plan frozen
 * rules (breach iff used > limit; reported = SUM over all assignments, ended included, of each one's max-kept report), and the
 * public docs `api-reference.md`, `api-rest.md` ("Budgets and caps"), `fleet-deployment.md` ("Run Budgets and Caps") for wire names
 * (`usage{tokens, costUsd}`, `paused{decisionId}`, `amount`, the run view and the `run_paused` / `run_at_capacity` detail keys).
 *
 * Arbitration record: the decision-row column names `recommended` and `context` follow the sibling `dedupe_key` / `answer_option`
 * columns DecisionServiceTest reads; the dedupe key of an attempt-cap decision uses the refused attempt number (cap + 1) per the
 * orchestrator's supplementary ruling.
 *
 * Negative-assertion fixture conditions: every "not paused / not refused" assertion is paired with the same call crossing the single
 * input it turns on (one more token, one more attempt, one more seat); every refusal asserts the code and its detail.
 *
 * Domain invariants satisfied by construction: runs are opened through the tool itself with items that are children of the root.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
@Timeout(value = 1_800, unit = TimeUnit.SECONDS)
class RunsBudgetsCapsTest {
    private val clock = SettableClock(Instant.parse("2026-10-10T12:00:00Z"))

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    @TempDir
    lateinit var dir: Path

    private fun world(
        config: String = SEAT_WORLD_CONFIG,
        block: suspend (SeatWorld) -> Unit
    ) = runBlocking {
        val w = SeatWorld.create(db.db, dir, clock, config)
        try {
            block(w)
        } finally {
            w.shutdown()
        }
    }

    private val director =
        ParityToken("budget-dir", setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.DECIDE), kind = PrincipalKind.HUMAN)

    /** The opener principal of `SeatWorld.orch` with the decide capability, so its coordinator token is presented by its holder. */
    private val orchDecide =
        ParityToken(
            "seat-orch",
            setOf(ApiCapability.RUNS, ApiCapability.READ, ApiCapability.ADVANCE, ApiCapability.DECIDE)
        )

    // ------------------------------------------------------------------ helpers

    private fun Reply.pausedId(i: Int = 0): String? =
        (results().getOrNull(i)?.let { findElement("paused", it) } as? JsonObject)?.get("decisionId")?.jsonPrimitive?.content

    private fun Reply.hasPaused(): Boolean = findElement("paused") != null

    private suspend fun open(
        w: SeatWorld,
        budget: String
    ): Opened = w.open(extra = ""","budget":$budget""")

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

    private suspend fun view(
        w: SeatWorld,
        run: Opened
    ): Reply = w.select("get", ""","runId":"${run.runId}"""").also { assertTrue(it.ok(), it.toString()) }

    private suspend fun status(
        w: SeatWorld,
        run: Opened
    ): String? = view(w, run).find("status")

    private fun decisions(
        w: SeatWorld,
        run: Opened
    ): List<Map<String, Any?>> =
        w.rows("SELECT * FROM decision_requests WHERE run_id = ? ORDER BY raised_at, dedupe_key", UUID.fromString(run.runId))

    private fun optionKeys(row: Map<String, Any?>): List<String> =
        Json.parseToJsonElement(row["options"] as String).jsonArray.map {
            it.jsonObject
                .getValue("key")
                .jsonPrimitive.content
        }

    private suspend fun answer(
        w: SeatWorld,
        decisionId: String,
        option: String,
        amount: String? = null,
        token: String? = null,
        by: ParityToken = director
    ): Reply =
        w.director(
            "runs_write",
            "{" + (if (token != null) """"seatToken":"$token",""" else "") +
                """"ops":[{"op":"answer","decisionId":"$decisionId","option":"$option"${amountField(amount)}}]}""",
            by
        )

    private fun amountField(amount: String?): String = if (amount != null) ""","amount":$amount""" else ""

    private suspend fun eventTypesAfter(
        w: SeatWorld,
        mark: Long
    ): List<String> = w.rig.rowsAfter(mark).map { it.type }

    private fun writerFor(w: SeatWorld): CallLogWriter =
        CallLogWriter(
            w.rig.composition.unitOfWork,
            SqliteCallLogStore(w.database.databaseManager),
            flushInterval = 1.hours,
            runStore = w.rig.raw.runStore()
        )

    private var reqCounter = 0

    private fun record(
        runId: String?,
        est: Long?
    ): CallLogRecord =
        CallLogRecord(
            reqId = testReqId(reqCounter++),
            at = Instant.parse("2026-10-10T12:00:00Z"),
            runId = runId?.let(UUID::fromString),
            surface = CallLogRecord.SURFACE_MCP,
            tool = "advance",
            outcome = CallLogRecord.OUTCOME_OK,
            latencyMs = 1,
            responseTokensEst = est
        )

    private suspend fun transition(
        w: SeatWorld,
        item: WorkItem,
        trigger: String,
        token: String?,
        by: ParityToken = w.orch
    ): Reply = w.ops("advance", """{"op":"transition","itemId":"${item.id}","trigger":"$trigger"}""", token = token, by = by)

    // ------------------------------------------------------------------ S1 usage is summed per assignment, max per field

    @Test
    fun `S1 reported usage is the sum over every assignment of each one's highest report, ended and superseded ones included`() =
        world { w ->
            val run = w.open()
            val a = seatUp(w, run, w.m1)
            val b = seatUp(w, run, w.m2, "implementer")

            assertTrue(w.heartbeat(w.m1, a.token, usage = """{"tokens":100}""").ok())
            assertTrue(w.heartbeat(w.m1, a.token, usage = """{"tokens":100}""").ok())
            assertTrue(w.heartbeat(w.m2, b.token, usage = """{"tokens":50}""").ok())
            assertEquals(
                "150",
                view(w, run).find("reportedTokens"),
                "100 (A, reported twice) + 50 (B) is 150, not 250 (test-plan S1, brief 3.9)"
            )

            // probe: a lower report keeps the highest
            assertTrue(w.heartbeat(w.m1, a.token, usage = """{"tokens":80}""").ok())
            assertEquals("150", view(w, run).find("reportedTokens"), "a lower absolute report does not reduce the kept maximum")

            // an ended assignment still counts
            assertTrue(w.endSeat(b.token).ok())
            assertEquals("150", view(w, run).find("reportedTokens"), "an ended seat's kept report stays in the sum")

            // a superseded assignment still counts, and the new attempt adds its own
            val again = seatUp(w, run, w.m1)
            assertEquals(2, again.attempt)
            assertTrue(w.heartbeat(w.m1, again.token, usage = """{"tokens":30}""").ok())
            assertEquals("180", view(w, run).find("reportedTokens"), "100 (attempt 1) + 50 (B) + 30 (attempt 2)")
        }

    // ------------------------------------------------------------------ S2 the writer adds observed tokens and never checks

    @Test
    fun `S2 the call-log writer adds response_tokens_est per run into observed tokens and does not touch the run`() =
        world { w ->
            val run = open(w, """{"tokens":5}""")
            val versionBefore = view(w, run).find("version")
            val marker = w.rig.maxSeq()
            val writer = writerFor(w)
            writer.submit(record(run.runId, 10))
            writer.submit(record(run.runId, 20))
            writer.submit(record(run.runId, 30))
            writer.submit(record(null, 99))
            assertEquals(4, writer.flushNow(), "all four rows are written")

            val after = view(w, run)
            assertEquals("60", after.find("observedTokens"), "10 + 20 + 30; the row without a run id adds nothing (test-plan S2)")
            assertEquals(
                "open",
                after.find("status"),
                "observed 60 is over the limit 5, yet the writer never runs the check (task-scope D1)"
            )
            assertEquals(versionBefore, after.find("version"), "the counter write does not bump the run version")
            assertEquals(0, decisions(w, run).size, "no decision is raised by the writer")
            assertEquals(emptyList(), eventTypesAfter(w, marker).filter { it.startsWith("run.") || it.startsWith("decision.") })
        }

    @Test
    fun `S2 probe rows for a closed run are added and rows for an unknown run are ignored without failing the batch`() =
        world { w ->
            val closed = w.open(members = listOf(w.e), rootItem = w.root2)
            assertTrue(w.close(closed, "cancelled", closed.coordinatorToken).ok())
            val live = w.open()
            val writer = writerFor(w)
            writer.submit(record(closed.runId, 7))
            writer.submit(record(UUID.randomUUID().toString(), 500))
            writer.submit(record(live.runId, 3))
            writer.flushNow()

            assertEquals("7", view(w, closed).find("observedTokens"), "a closed run still counts")
            assertEquals("3", view(w, live).find("observedTokens"), "the other rows of the batch are not lost")
            assertEquals(0L, writer.failed, "an unknown run id does not fail the batch")
        }

    @Test
    fun `S2 the writer alone never pauses a run whose observed usage is far over the limit, and the next token-carrying write does`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(record(run.runId, 1_000))
            writer.flushNow()
            assertEquals("open", status(w, run), "observed 1000 over 100 and still open")
            assertEquals(0, decisions(w, run).size)
            val beat = w.heartbeat(w.m1, seat.token)
            assertNotNull(beat.pausedId(), "control: the next token-carrying write is the one that pauses: $beat")
        }

    // ------------------------------------------------------------------ S3 a heartbeat breach pauses

    @Test
    fun `S3 a heartbeat over the token budget succeeds, pauses the run for budget and raises the run-budget decision in the same unit`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val mark = w.rig.maxSeq()

            val beat = w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""")

            assertTrue(beat.ok(), "the write itself succeeds: $beat")
            val decisionId = assertNotNull(beat.pausedId(), "the result carries paused{decisionId}: $beat")
            val v = view(w, run)
            assertEquals("paused", v.find("status"))
            assertEquals("budget", v.find("pauseReason"))
            val d = decisions(w, run).single()
            assertEquals(decisionId, w.uuidOf(d["id"]).toString())
            assertEquals("run-budget", d["class"])
            assertEquals("budget:${run.runId}:1", d["dedupe_key"])
            assertEquals(listOf("extend", "stop"), optionKeys(d))
            assertEquals("stop", d["recommended"])
            val ctx = Json.parseToJsonElement(d["context"] as String).jsonObject
            assertEquals("tokens", ctx["dimension"]?.jsonPrimitive?.content)
            assertEquals("101", ctx["used"]?.jsonPrimitive?.content)
            assertEquals("100", ctx["limit"]?.jsonPrimitive?.content)
            assertEquals(101L, w.seatById(seat.assignmentId!!)["usage_tokens"], "the report itself is kept")
            val types = eventTypesAfter(w, mark)
            assertEquals(1, types.count { it == "run.paused" }, "run.paused committed: $types")
            assertEquals(1, types.count { it == "decision.raised" }, "decision.raised committed: $types")
        }

    // ------------------------------------------------------------------ S4 an observed-only breach pauses on the next write

    @Test
    fun `S4 observed tokens over the limit with nothing reported pause the run on the next token-carrying write, not before`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(record(run.runId, 101))
            writer.flushNow()
            assertEquals("open", status(w, run), "the writer alone never pauses")
            assertEquals(0, decisions(w, run).size)

            val beat = w.heartbeat(w.m1, seat.token)

            assertTrue(beat.ok(), beat.toString())
            assertNotNull(beat.pausedId(), "a heartbeat with no usage re-checks the run: $beat")
            val v = view(w, run)
            assertEquals("paused", v.find("status"))
            assertEquals("0", v.find("reportedTokens"))
            val ctx = Json.parseToJsonElement(decisions(w, run).single()["context"] as String).jsonObject
            assertEquals("101", ctx["used"]?.jsonPrimitive?.content, "enforced tokens are max(reported 0, observed 101) (D4)")
        }

    @Test
    fun `S4 probe observed tokens exactly at the limit do not pause, one more does`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val writer = writerFor(w)
            writer.submit(record(run.runId, 100))
            writer.flushNow()
            val atLimit = w.heartbeat(w.m1, seat.token)
            assertTrue(atLimit.ok() && !atLimit.hasPaused(), "observed == limit is not a breach: $atLimit")
            assertEquals("open", status(w, run))

            writer.submit(record(run.runId, 1))
            writer.flushNow()
            val over = w.heartbeat(w.m1, seat.token)
            assertNotNull(over.pausedId(), "observed 101 over 100 pauses: $over")
        }

    // ------------------------------------------------------------------ S5 extend resumes, S6 stop closes

    @Test
    fun `S5 the director's extend raises the budget, resumes the run and lets seats assign again`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val decisionId = assertNotNull(w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""").pausedId())
            val mark = w.rig.maxSeq()

            val reply = answer(w, decisionId, "extend", amount = "50")

            assertTrue(reply.ok(), reply.toString())
            val v = view(w, run)
            assertEquals("open", v.find("status"))
            assertNull(v.find("pauseReason"), "the pause fields are cleared: $v")
            assertEquals("150", v.find("tokens"), "100 + 50")
            assertTrue("run.resumed" in eventTypesAfter(w, mark), "run.resumed is recorded")
            assertEquals("answered", decisions(w, run).single()["status"])
            // control: the refused operations of S10 work again
            assertTrue(w.assign(run, w.m2, "implementer").reply.ok(), "assign works after the resume")
            val after = w.heartbeat(w.m1, seat.token, usage = """{"tokens":150}""")
            assertTrue(after.ok() && !after.hasPaused(), "used 150 equals the raised limit 150: no breach: $after")
        }

    @Test
    fun `S6 the director's stop closes the run budget_exhausted with the full cascade`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val decisionId = assertNotNull(w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""").pausedId())
            val held = w.claim(w.m1, null, by = w.other)
            assertEquals("claim_held", held.code(), "control: before the stop the seat's claim holds the item: $held")
            val mark = w.rig.maxSeq()

            val reply = answer(w, decisionId, "stop")

            assertTrue(reply.ok(), reply.toString())
            val v = view(w, run)
            assertEquals("closed", v.find("status"))
            assertEquals("budget_exhausted", v.find("terminalReason"))
            val seats = w.seatsOfRun(run.runId)
            assertTrue(
                seats.isNotEmpty() && seats.all { it["status"] == "ended" && it["outcome"] == "run_closed" },
                "every seat ends run_closed: $seats"
            )
            assertTrue(w.claim(w.m1, null, by = w.other).ok(), "the assignee's claim is cleared, so another principal can take the item")
            assertTrue("run.ended" in eventTypesAfter(w, mark), "run.ended is recorded")
        }

    // ------------------------------------------------------------------ S7 / S8 attempt cap

    @Test
    fun `S7 the assign past attemptsPerSeat is refused run_paused and pauses the run on an attempt-cap decision`() =
        world { w ->
            val run = open(w, """{"attemptsPerSeat":2}""")
            assertEquals(1, w.assignOk(run, w.m1, "builder").attempt)
            assertEquals(2, w.assignOk(run, w.m1, "builder").attempt)
            val rowsBefore = w.seatsOfRun(run.runId).size
            val mark = w.rig.maxSeq()

            val refused = w.assign(run, w.m1, "builder").reply

            assertEquals("run_paused", refused.code(), refused.toString())
            val err = refused.error()
            assertEquals(run.runId, refused.find("runId", err))
            assertEquals("attempts", refused.find("pauseReason", err))
            val d = decisions(w, run).single()
            assertEquals(w.uuidOf(d["id"]).toString(), refused.find("pauseDecisionId", err))
            assertEquals("attempt-cap", d["class"])
            assertEquals("attempts:${run.runId}:${w.m1.id}:builder:3", d["dedupe_key"])
            assertEquals(listOf("allow_one", "skip_item", "stop"), optionKeys(d))
            val v = view(w, run)
            assertEquals("paused", v.find("status"), "the pause is committed although the assign failed")
            assertEquals("attempts", v.find("pauseReason"))
            assertEquals(rowsBefore, w.seatsOfRun(run.runId).size, "no third attempt row")
            val types = eventTypesAfter(w, mark)
            assertTrue("run.paused" in types && "decision.raised" in types, "pause and decision events committed: $types")
        }

    @Test
    fun `S8 allow_one gives that seat one more attempt and resumes, then the next assign is refused with a new decision`() =
        world { w ->
            val run = open(w, """{"attemptsPerSeat":2}""")
            w.assignOk(run, w.m1, "builder")
            w.assignOk(run, w.m1, "builder")
            assertEquals("run_paused", w.assign(run, w.m1, "builder").reply.code())
            val first = decisions(w, run).single()

            val reply = answer(w, w.uuidOf(first["id"]).toString(), "allow_one")

            assertTrue(reply.ok(), reply.toString())
            assertEquals("open", status(w, run))
            val third = w.assign(run, w.m1, "builder")
            assertTrue(third.reply.ok(), "the allowed third attempt is issued: ${third.reply}")
            assertEquals(3, third.attempt)
            val fourth = w.assign(run, w.m1, "builder")
            assertEquals("run_paused", fourth.reply.code(), "the allowance was for one attempt: ${fourth.reply}")
            val rows = decisions(w, run)
            assertEquals(2, rows.size, "a new decision is raised for the next refusal")
            assertTrue(rows.any { it["dedupe_key"] == "attempts:${run.runId}:${w.m1.id}:builder:4" }, "the new key names attempt 4: $rows")
        }

    @Test
    fun `S8 skip_item removes the item from the run and resumes it`() =
        world { w ->
            val run = open(w, """{"attemptsPerSeat":1}""")
            w.assignOk(run, w.m1, "builder")
            assertEquals("run_paused", w.assign(run, w.m1, "builder").reply.code())
            val decision = decisions(w, run).single()
            val membersBefore = w.rows("SELECT * FROM run_items WHERE run_id = ?", UUID.fromString(run.runId)).size

            val reply = answer(w, w.uuidOf(decision["id"]).toString(), "skip_item")

            assertTrue(reply.ok(), reply.toString())
            assertEquals("open", status(w, run))
            val members = w.rows("SELECT * FROM run_items WHERE run_id = ?", UUID.fromString(run.runId))
            assertEquals(membersBefore - 1, members.size, "one member removed")
            assertTrue(members.none { w.uuidOf(it["item_id"]) == w.m1.id }, "the skipped item is no longer a member")
        }

    // ------------------------------------------------------------------ S9 active-seat cap

    @Test
    fun `S9 an assign beyond maxActiveSeats is run_at_capacity with no pause, decision or row, and works after a seat ends`() =
        world { w ->
            val run = open(w, """{"maxActiveSeats":2}""")
            val a = seatUp(w, run, w.m1)
            w.assignOk(run, w.m2, "implementer")
            val rowsBefore = w.seatsOfRun(run.runId).size

            val refused = w.assign(run, w.m3, "implementer").reply

            assertEquals("run_at_capacity", refused.code(), refused.toString())
            val err = refused.error()
            assertEquals(run.runId, refused.find("runId", err))
            assertEquals("2", refused.find("active", err), "two member seats active; the coordinator is not counted (D6)")
            assertEquals("2", refused.find("max", err))
            assertEquals("open", status(w, run), "no pause")
            assertEquals(0, decisions(w, run).size, "no decision")
            assertEquals(rowsBefore, w.seatsOfRun(run.runId).size, "no row")

            assertTrue(w.endSeat(a.token).ok())
            val retry = w.assign(run, w.m3, "implementer")
            assertTrue(retry.reply.ok(), "after a seat ends the same assign succeeds: ${retry.reply}")
        }

    @Test
    fun `S9 probe superseding an active seat at capacity is allowed`() =
        world { w ->
            val run = open(w, """{"maxActiveSeats":1}""")
            w.assignOk(run, w.m1, "builder")
            val again = w.assign(run, w.m1, "builder")
            assertTrue(again.reply.ok(), "a supersede does not add an active seat: ${again.reply}")
            assertEquals(2, again.attempt)
        }

    @Test
    fun `S9 probe the attempt cap wins over the capacity when one assign breaks both`() =
        world { w ->
            val run = open(w, """{"attemptsPerSeat":1,"maxActiveSeats":1}""")
            val first = w.assignOk(run, w.m1, "builder")
            assertTrue(w.endSeat(first.token).ok())
            w.assignOk(run, w.m2, "implementer")

            val refused = w.assign(run, w.m1, "builder")

            assertEquals("run_paused", refused.reply.code(), "cap wins (frozen test-plan probe): ${refused.reply}")
            assertEquals("paused", status(w, run))
        }

    // ------------------------------------------------------------------ S10 while paused

    @Test
    fun `S10 while paused for a limit assign, a seat-token start and update are run_paused and a manual resume is invalid_run_state`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val a = seatUp(w, run, w.m1)
            val b = seatUp(w, run, w.m2, "implementer")
            val decisionId = assertNotNull(w.heartbeat(w.m1, a.token, usage = """{"tokens":101}""").pausedId())

            val assign = w.assign(run, w.m3, "implementer").reply
            assertEquals("run_paused", assign.code(), assign.toString())
            assertEquals(decisionId, assign.find("pauseDecisionId", assign.error()))
            assertEquals("budget", assign.find("pauseReason", assign.error()))

            val start = transition(w, w.m2, "start", b.token)
            assertEquals("run_paused", start.code(), "a seat-token start is refused: $start")
            assertEquals(decisionId, start.find("pauseDecisionId", start.error()))

            val update = w.ops("runs_write", """{"op":"update","runId":"${run.runId}","hints":{"k":"v"}}""", token = run.coordinatorToken)
            assertEquals("run_paused", update.code(), update.toString())

            val resume = w.director("runs_write", """{"ops":[{"op":"resume","runId":"${run.runId}"}]}""", director)
            assertEquals("invalid_run_state", resume.code(), resume.toString())
            assertTrue("close" in resume.toString(), "the allowed transitions name close: $resume")
            assertEquals("paused", status(w, run), "still paused")

            // probe: a human start (no seat token) on the same paused run is allowed
            val human = transition(w, w.m3, "start", null, by = w.human)
            assertTrue(human.ok(), "a start without a seat token is not refused: $human")
        }

    // ------------------------------------------------------------------ S11 answer permissions and amount validation

    @Test
    fun `S11 an answer under a seat token is forbidden principal_kind, and an extend needs a positive amount`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val decisionId = assertNotNull(w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""").pausedId())

            val coordinator = answer(w, decisionId, "extend", amount = "50", token = run.coordinatorToken, by = orchDecide)
            assertEquals("forbidden", coordinator.code(), coordinator.toString())
            assertEquals("principal_kind", coordinator.find("scope", coordinator.error()), coordinator.toString())
            assertEquals("paused", status(w, run), "the coordinator cannot lift its own cap")

            for (bad in listOf<String?>(null, "0", "-5")) {
                val reply = answer(w, decisionId, "extend", amount = bad)
                assertEquals("invalid_request", reply.code(), "amount $bad: $reply")
                assertTrue(reply.pointers().any { it.endsWith("/amount") }, "the pointer names /amount for $bad: $reply")
                assertEquals("open", decisions(w, run).single()["status"], "a rejected answer leaves the decision open")
            }

            val good = answer(w, decisionId, "extend", amount = "50")
            assertTrue(good.ok(), "control: a positive amount from the director is accepted: $good")
            assertEquals("open", status(w, run))
        }

    @Test
    fun `S11 probe answering the same decision twice is decision_closed`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            val decisionId = assertNotNull(w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""").pausedId())
            assertTrue(answer(w, decisionId, "extend", amount = "50").ok())

            val second = answer(w, decisionId, "stop")

            assertEquals("decision_closed", second.code(), second.toString())
            assertEquals("open", status(w, run), "the second answer changed nothing")
        }

    // ------------------------------------------------------------------ S12 ceilings (open may only lower)

    @Test
    fun `S12 open above a configured ceiling is invalid_request on the budget field, and extend may go past the ceiling`() =
        world(config = SEAT_WORLD_CONFIG + "\nruns:\n  budgets:\n    tokens: 150\n") { w ->
            val over =
                w.ops("runs_write", """{"op":"open","rootId":"${w.root.id}","items":[${w.ids(w.m1)}],"budget":{"tokens":151}}""")
            assertEquals("invalid_request", over.code(), over.toString())
            assertTrue(over.pointers().any { it.endsWith("/budget/tokens") }, "pointer names /budget/tokens: $over")

            val run = open(w, """{"tokens":150}""")
            val seat = seatUp(w, run, w.m1)
            val decisionId = assertNotNull(w.heartbeat(w.m1, seat.token, usage = """{"tokens":151}""").pausedId())
            assertTrue(answer(w, decisionId, "extend", amount = "100").ok())
            assertEquals("250", view(w, run).find("tokens"), "extend may exceed the ceiling (frozen probe)")
        }

    // ------------------------------------------------------------------ S13 boundary

    @Test
    fun `S13 usage equal to the limit does not breach and one more does`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)

            val atLimit = w.heartbeat(w.m1, seat.token, usage = """{"tokens":100}""")
            assertTrue(atLimit.ok() && !atLimit.hasPaused(), "used == limit is not a breach: $atLimit")
            assertEquals("open", status(w, run))
            assertEquals(0, decisions(w, run).size)

            val over = w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""")
            assertNotNull(over.pausedId(), "used 101 over 100 breaches: $over")
            assertEquals("paused", status(w, run))
        }

    // ------------------------------------------------------------------ S14 while paused

    @Test
    fun `S14 while paused the seat's other writes continue and repeat breaches return the same decision and one run paused event`() =
        world { w ->
            val run = open(w, """{"tokens":100}""")
            val seat = seatUp(w, run, w.m1)
            assertTrue(
                transition(w, w.m3, "start", run.coordinatorToken).ok(),
                "the coordinator starts a second item while the run is open"
            )
            val mark = w.rig.maxSeq()
            val first = w.heartbeat(w.m1, seat.token, usage = """{"tokens":101}""")
            val decisionId = assertNotNull(first.pausedId())

            val again = w.heartbeat(w.m1, seat.token, usage = """{"tokens":120}""")
            assertTrue(again.ok(), "a heartbeat with usage continues: $again")
            assertEquals(decisionId, again.pausedId(), "a repeat breach returns the same decision")
            assertEquals(120L, w.seatById(seat.assignmentId!!)["usage_tokens"], "the higher report is kept")
            assertTrue(w.noteUpsert(w.m1, "build-notes", seat.token).ok(), "a note write continues")
            val complete = transition(w, w.m3, "complete", run.coordinatorToken)
            assertTrue(complete.ok(), "a seat-token complete continues: $complete")
            assertTrue(w.endSeat(seat.token).ok(), "end_seat continues")

            assertEquals(1, decisions(w, run).size, "still one decision")
            assertEquals(1, eventTypesAfter(w, mark).count { it == "run.paused" }, "run.paused is recorded once")
            assertEquals("paused", status(w, run))
        }

    // ------------------------------------------------------------------ S15 cost

    @Test
    fun `S15 a cost breach alone pauses the run on the cost dimension, and a second breach after an extend is budget n 2`() =
        world { w ->
            val run = open(w, """{"costUsd":1.0}""")
            val seat = seatUp(w, run, w.m1)

            val first = w.heartbeat(w.m1, seat.token, usage = """{"costUsd":1.5}""")
            val firstId = assertNotNull(first.pausedId(), "1.5 over 1.0 USD pauses: $first")
            val d1 = decisions(w, run).single()
            assertEquals("budget:${run.runId}:1", d1["dedupe_key"])
            assertEquals(
                "costUsd",
                Json
                    .parseToJsonElement(d1["context"] as String)
                    .jsonObject["dimension"]
                    ?.jsonPrimitive
                    ?.content
            )

            assertTrue(answer(w, firstId, "extend", amount = "0.25").ok())
            val limit = assertNotNull(view(w, run).find("costUsd")).toDouble()
            assertEquals(1.25, limit, 1e-9, "1.0 + 0.25 USD")
            assertEquals("open", status(w, run))

            val second = w.heartbeat(w.m1, seat.token, usage = """{"costUsd":1.5}""")
            val secondId = assertNotNull(second.pausedId(), "1.5 is still over 1.25: $second")
            assertNotEquals(firstId, secondId)
            assertTrue(
                decisions(w, run).any { it["dedupe_key"] == "budget:${run.runId}:2" },
                "the second breach is budget:<runId>:2: ${decisions(w, run)}"
            )
        }
}
