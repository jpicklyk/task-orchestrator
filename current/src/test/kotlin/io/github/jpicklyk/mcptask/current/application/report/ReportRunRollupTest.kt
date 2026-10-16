package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.AttemptCounts
import io.github.jpicklyk.mcptask.current.application.port.BudgetFacts
import io.github.jpicklyk.mcptask.current.application.port.DecisionCounts
import io.github.jpicklyk.mcptask.current.application.port.RunHeader
import io.github.jpicklyk.mcptask.current.application.port.StalledSeat
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunStatus
import io.github.jpicklyk.mcptask.current.domain.model.RunTerminalReason
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.P7Raw
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.rollupJson
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 74bb6db9, R28) for the run rollup of `ReportService.rollup` over the real SQLite
 * database and the real report port: scenarios S1 to S9, S13 and S14 of the frozen test-plan (all NEW-SURFACE), plus the wire shape
 * of AC3. The snapshot scenarios (S10, S11) are in `RunRollupSnapshotTest` and `RunServiceSnapshotSeamTest`, the codec (S12) in
 * `RollupSnapshotCodecTest`, the size bound (S15) in `RunRollupSizeTest`, the statement count and plans (S16) in
 * `RunRollupQueryCountTest`; S17 lives in the existing guard and parity tests.
 *
 * Oracles (task-scope acceptance criteria; none read from a run, every number is a hand sum written next to its rows in
 * [RunWorld] and [DwellWorld]): AC1 (exactly one of runId and anchorId; runId with since is invalid at `/since`, with until only at
 * `/until`, both ids at `/runId`, neither at `/anchorId`), AC2 (the `runs_read get` rule: full UUID or 8 to 32 hex prefix; unrestricted:
 * unknown is not_found, ambiguous is ambiguous_id; restricted: unknown or out-of-root is forbidden, never not_found; tag scope is
 * forbidden before any read), AC3 (the wire members and their order), AC4 (events by the run stamp, no time predicate), AC5 (cost by
 * the run stamp, no time predicate, root not filtered, basis `run`), AC6 (the RunFacts rules: current members with a role, blocked
 * reasons with legacy NULL read as manual, resumable = awaiting_decision whose decision is not pending, open decisions with the
 * effective severity, stalled seats strictly past the run's threshold, attempts over every member seat row, budget used = the larger
 * of reported and observed), AC7 (blocked dwell by reason over the half-open window, blocks opened before the window counted from
 * its start, events ordered by seq).
 *
 * Negative-assertion conditions: every exclusion has a seeded row that WOULD be counted if the rule were dropped and a control from
 * the same fixture that does return data. Run Q2, the non-member X and XB, the unstamped events and calls, the note deletion, the
 * stamped call on another root and the stalled seat of Q2 are those rows ([RunWorld]); the non-member DJ and the block that starts at
 * the closing instant are theirs in [DwellWorld].
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: see [RunKit].
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ReportRunRollupTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val w = RunWorld
    private val now = ReportKit.NOW

    private suspend fun rollup(
        f: ReportFixture,
        request: ReportRollupRequest,
        ctx: CallContext = f.ctx()
    ): Outcome<RollupReport> = f.service().rollup(ctx, request)

    private suspend fun ok(
        f: ReportFixture,
        run: UUID,
        ref: String = run.toString(),
        ctx: CallContext = f.ctx()
    ): RollupReport =
        when (val outcome = rollup(f, ReportRollupRequest(runId = IdRef(ref)), ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok rollup for run $ref but got ${outcome.error}")
        }

    private fun err(outcome: Outcome<RollupReport>) = assertIs<Outcome.Err>(outcome).error

    private fun JsonObject.keysOf(name: String): List<String> = getValue(name).jsonObject.keys.toList()

    private fun JsonObject.textOf(
        name: String,
        member: String
    ): String =
        getValue(name)
            .jsonObject
            .getValue(member)
            .jsonPrimitive.content

    // ------------------------------------------------------------------ S1 items

    @Test
    fun `S1 items count the current role members of the run and nothing else`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val report = ok(f, w.Q)

            assertEquals(
                12L,
                report.items.total,
                "i1 to i12; the lifecycle-free L, the non-members X and XB and Q2's members are not counted"
            )
            assertEquals(
                mapOf("queue" to 1L, "work" to 1L, "review" to 1L, "blocked" to 8L, "terminal" to 1L),
                report.items.byRole
            )
            assertEquals(RollupScope(anchorId = null, runId = w.Q, rootId = w.R), report.scope)
            // control: the same data holds the rows the exclusions rely on, and Q2 counts its own two members
            val other = ok(f, w.Q2)
            assertEquals(2L, other.items.total)
            assertEquals(mapOf("work" to 1L, "blocked" to 1L), other.items.byRole)
            assertEquals(
                1L,
                RollupRaw.count(f.database.jdbcUrl, "SELECT COUNT(*) FROM work_items WHERE role IS NULL AND lifecycle = 'none'"),
                "exactly the lifecycle-free L"
            )
        }

    @Test
    fun `S1 the run header and the window are the run row, informational and never clamped`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val report = ok(f, w.Q)

            assertEquals(RunHeader(RunStatus.OPEN, null, w.OPENED, null, w.R), report.run)
            assertEquals(RollupWindow(since = w.OPENED, until = now, clamped = false), report.window, "an open run ends at now")
        }

    @Test
    fun `S1 probe a run opened 60 days ago is not clamped to the 31 day window and is not refused`(): Unit =
        runBlocking {
            val f = fx
            RunKit.roots(f)
            val long = RunKit.id("f6800000")
            RunKit.run(f, long, openedAt = now.minus(Duration.ofDays(60)))

            val report = ok(f, long)

            assertEquals(now.minus(Duration.ofDays(60)), report.window.since)
            assertEquals(now, report.window.until)
            assertFalse(report.window.clamped, "a run rollup always covers the whole run")
        }

    @Test
    fun `S1 probe an empty run reports zero everywhere and still carries its run parts`(): Unit =
        runBlocking {
            val f = fx
            RunKit.roots(f)
            val empty = RunKit.id("f6800000", 2)
            RunKit.run(f, empty)

            val report = ok(f, empty)

            assertEquals(0L, report.items.total)
            assertEquals(0L, report.blocked.total)
            assertEquals(RollupSeats(active = 0, stalled = emptyList(), count = 0), report.seats)
            assertEquals(AttemptCounts(total = 0, max = 0), report.attempts, "no member seat: 0 and 0")
            assertEquals(DecisionCounts(open = 0, byClass = emptyMap(), bySeverity = emptyMap()), report.decisions)
            assertEquals(RollupTransitions(0, 0, 0), report.transitions)
            assertEquals(RollupCost(0, 0, 0, 0, 0, "run"), report.cost)
            assertEquals(BudgetFacts(used = 0, limit = null, basis = "enforced"), report.budget, "present for every run")
        }

    // ------------------------------------------------------------------ S2 blocked reasons and resumable

    @Test
    fun `S2 blocked counts the blocked members by effective reason, a legacy block reads as manual, and the sum is the total`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val report = ok(f, w.Q)

            assertEquals(8L, report.blocked.total)
            assertEquals(
                mapOf("awaiting_decision" to 5, "dependency" to 1, "manual" to 1, "resource" to 1),
                report.blocked.byReason,
                "i4 has no stored reason: it reads as manual; the non-member XB (dependency) and Q2's J2 (manual) are not counted"
            )
            assertEquals(
                report.blocked.total,
                report.blocked.byReason!!
                    .values
                    .sumOf { it.toLong() }
            )
        }

    @Test
    fun `S2 resumable counts the awaiting_decision members whose decision is answered, withdrawn or missing`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val report = ok(f, w.Q)

            assertEquals(
                3,
                report.blocked.resumable,
                "i7 answered, i9 withdrawn, i10 no such decision; i8 open and i11 acknowledged are pending"
            )
        }

    @Test
    fun `S2 resumable follows the decision status, so answering the one pending decision makes the item resumable`(): Unit =
        runBlocking {
            val f = fx
            RunKit.roots(f)
            val stuck = RunKit.id("f6810000")
            val decision = RunKit.id("f6810000", 2)
            val run = RunKit.id("f6810000", 3)
            RollupRaw.write(f.database.jdbcUrl) {
                item(stuck, RunKit.R, "blocked", 1, RunKit.R)
                blocked(stuck, "awaiting_decision", decision)
            }
            RunKit.run(f, run, members = listOf(stuck))
            RunKit.decision(f, decision, run, item = stuck, status = DecisionRequestStatus.OPEN)

            val pending = rollupJson(ok(f, run))
            assertFalse(
                "resumable" in pending.getValue("blocked").jsonObject,
                "a pending decision: nothing is resumable, and zero is omitted"
            )
            assertEquals(1L, ok(f, run).blocked.total)

            RollupRaw.write(f.database.jdbcUrl) {
                sql(
                    "UPDATE decision_requests SET status = 'answered', answered_at = ?, answered_by_id = 'api:t' WHERE id = ?",
                    P7Raw.canon(now),
                    decision
                )
            }

            val answered = ok(f, run)
            assertEquals(1, answered.blocked.resumable, "the same item, the same block: only the decision changed")
        }

    // ------------------------------------------------------------------ S3 transitions and notes

    @Test
    fun `S3 transitions, cascades, rejections and notes come from the events stamped with the run, with no time predicate`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val report = ok(f, w.Q)

            // five stamped transitions: one on the non-member X and one a day before the run opened still count (the stamp decides)
            assertEquals(RollupTransitions(total = 5, cascades = 2, rejected = 2), report.transitions)
            assertEquals(3L, report.notesWritten, "three upserts; the stamped delete and the unstamped upsert are not counted")
            // control: the fixture holds the rows the exclusions rely on, and Q2 counts its own stamped events
            assertEquals(4L, RollupRaw.count(f.database.jdbcUrl, "SELECT COUNT(*) FROM events WHERE run_id IS NULL"), "four unstamped rows")
            val other = ok(f, w.Q2)
            assertEquals(RollupTransitions(total = 2, cascades = 1, rejected = 1), other.transitions)
            assertEquals(2L, other.notesWritten)
        }

    // ------------------------------------------------------------------ S4 cost

    @Test
    fun `S4 cost sums the calls stamped with the run, retries are attempts minus one and a missing token estimate is zero`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val cost = ok(f, w.Q).cost

            // c1 ok 100 B; c2 error 40 B 3 attempts; c3 ok 8 B; c4 ok no bytes 2 attempts; c5 ok 12 B on another root
            assertEquals(RollupCost(calls = 5, errors = 1, retries = 3, responseBytes = 160, responseTokensEst = 40, basis = "run"), cost)
            // control: Q2's one stamped call is an error of 5,000 B, and the unstamped calls naming Q's members are not Q's
            assertEquals(
                RollupCost(calls = 1, errors = 1, retries = 0, responseBytes = 5_000, responseTokensEst = 1_250, basis = "run"),
                ok(f, w.Q2).cost
            )
        }

    @Test
    fun `S4 once the run's root is visible every stamped row counts, including a row of another root`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            val inR = f.ctx(AccessScope.principal(setOf(w.R), emptySet()))

            val scoped = ok(f, w.Q, ctx = inR)

            assertEquals(5L, scoped.cost.calls, "c5 is stamped with the run but carries root R2; the run stamp is the predicate")
            assertEquals(160L, scoped.cost.responseBytes)
        }

    // ------------------------------------------------------------------ S5 budget

    @Test
    fun `S5 budget used is the larger of reported and observed tokens from the run row, never the call log`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val budget = ok(f, w.Q).budget

            // Q: reported 300, observed 450, limit 1,000; the stamped call log sums to 40 estimated tokens
            assertEquals(BudgetFacts(used = 450, limit = 1_000, basis = "enforced"), budget)
        }

    @Test
    fun `S5 reported above observed wins, equal figures stand, and no limit leaves the limit out`(): Unit =
        runBlocking {
            val f = fx
            RunKit.roots(f)
            val reportedWins = RunKit.id("f6820000", 1)
            val equal = RunKit.id("f6820000", 2)
            RunKit.run(f, reportedWins, reportedTokens = 700, observedTokens = 20)
            RunKit.run(f, equal, budgetTokens = 500, reportedTokens = 500, observedTokens = 500)

            assertEquals(BudgetFacts(used = 700, limit = null, basis = "enforced"), ok(f, reportedWins).budget)
            assertEquals(BudgetFacts(used = 500, limit = 500, basis = "enforced"), ok(f, equal).budget)
        }

    // ------------------------------------------------------------------ S6 decisions

    @Test
    fun `S6 decisions count the open and acknowledged ones of the run by class and by effective severity`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val decisions = ok(f, w.Q).decisions

            // dA overdue exactly at now (normal reads high), dB high; dA2 not yet overdue (normal), dC low overdue reads normal; d8 and d11 low.
            assertEquals(
                DecisionCounts(
                    open = 6,
                    byClass = mapOf("question" to 5, "attempt-cap" to 1),
                    bySeverity = mapOf("high" to 2, "normal" to 2, "low" to 2)
                ),
                decisions,
                "answered d7, withdrawn d9 and Q2's high d-q2 are not counted"
            )
        }

    @Test
    fun `S6 probe the overdue boundary is inclusive, one millisecond before it and one after`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            clock.set(now.minusMillis(1))
            val before = ok(f, w.Q).decisions!!.bySeverity
            clock.set(now)
            val at = ok(f, w.Q).decisions!!.bySeverity
            clock.set(now.plusMillis(1))
            val after = ok(f, w.Q).decisions!!.bySeverity

            assertEquals(mapOf("high" to 1, "normal" to 3, "low" to 2), before, "dA expires at now: 1 ms earlier it is not overdue")
            assertEquals(mapOf("high" to 2, "normal" to 2, "low" to 2), at, "now >= expiresAt is overdue")
            assertEquals(mapOf("high" to 3, "normal" to 1, "low" to 2), after, "dA2 expires at now + 1 ms: now it is overdue too")
        }

    // ------------------------------------------------------------------ S7 seats

    @Test
    fun `S7 stalled seats are the active member seats of this run strictly past the threshold, longest silent first`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val seats = ok(f, w.Q).seats

            assertEquals(
                RollupSeats(
                    active = 3,
                    stalled =
                        listOf(
                            StalledSeat("reviewer", w.mem(3), 3_600_000),
                            StalledSeat("implementer", w.mem(1), 601_000)
                        ),
                    count = 2
                ),
                seats,
                "sC never seen reads from its assignment; sB at exactly 600 s is not stalled; the coordinator, the ended sD and Q2's seat are out"
            )
        }

    @Test
    fun `S7 probe a seat silent exactly the threshold is not stalled and one millisecond more is`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            clock.set(now.plusMillis(1))
            val seats = ok(f, w.Q).seats!!

            assertEquals(3, seats.count, "sB is now 600.001 s silent")
            assertEquals(
                listOf(
                    StalledSeat("reviewer", w.mem(3), 3_600_001),
                    StalledSeat("implementer", w.mem(1), 601_001),
                    StalledSeat("implementer", w.mem(2), 600_001)
                ),
                seats.stalled
            )
        }

    @Test
    fun `S7 eight stalled seats give five entries ordered by silence, then item, then seat, and the true count`(): Unit =
        runBlocking {
            val f = fx
            RunKit.roots(f)
            val run = RunKit.id("f6830000")
            val items = (1..7).map { RunKit.id("1a000000", it) }
            RollupRaw.write(f.database.jdbcUrl) {
                for (member in items) item(member, RunKit.R, "work", 1, RunKit.R)
            }
            RunKit.run(f, run, members = items)

            fun silent(seconds: Long) = now.minusSeconds(seconds)
            RollupRaw.write(f.database.jdbcUrl) {
                val assigned = now.minusSeconds(10_000)
                // silence in seconds: s1 700, s2 700 (tie on silence: the lower item id first), s3 650 on two seats (tie on item: the seat name),
                // s4 640, s5 630, s6 620, s7 610; the coordinator is silent for 99,999 s and must not appear
                val plan =
                    listOf(items[0] to 700L, items[1] to 700L, items[3] to 640L, items[4] to 630L, items[5] to 620L, items[6] to 610L)
                for ((n, entry) in plan.withIndex()) {
                    seat(RunKit.id("f6830001", n + 1), run, entry.first, "implementer", 1, assigned, silent(entry.second))
                }
                seat(RunKit.id("f6830002", 1), run, items[2], "beta", 1, assigned, silent(650))
                seat(RunKit.id("f6830002", 2), run, items[2], "alpha", 1, assigned, silent(650))
                seat(RunKit.id("f6830003"), run, null, "coordinator", 1, assigned, silent(99_999))
            }

            val seats = ok(f, run).seats!!

            assertEquals(8, seats.active)
            assertEquals(8, seats.count, "count is the true number of stalled seats")
            assertEquals(
                listOf(
                    StalledSeat("implementer", items[0], 700_000),
                    StalledSeat("implementer", items[1], 700_000),
                    StalledSeat("alpha", items[2], 650_000),
                    StalledSeat("beta", items[2], 650_000),
                    StalledSeat("implementer", items[3], 640_000)
                ),
                seats.stalled,
                "at most five entries"
            )
        }

    // ------------------------------------------------------------------ S8 attempts

    @Test
    fun `S8 attempts count every member seat row, ended ones included, the coordinator out, and the highest attempt`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val attempts = ok(f, w.Q).attempts

            assertEquals(
                AttemptCounts(total = 4, max = 3),
                attempts,
                "sA sB sC and the ended sD; the coordinator row and Q2's seat are out"
            )
        }

    // ------------------------------------------------------------------ S9 dwell

    @Test
    fun `S9 blocked dwell per reason of an open run runs to now, from the window start for a block opened earlier`(): Unit =
        runBlocking {
            val f = fx
            DwellWorld.seed(f, closed = false)

            val report = ok(f, DwellWorld.QD)

            assertEquals(
                mapOf(
                    "dependency" to 7_800_000L,
                    "resource" to 900_000L,
                    "awaiting_decision" to 1_800_000L,
                    "manual" to 23_400_000L
                ),
                report.blocked.dwellMs,
                "D1 2 h + D5 10 min; D2 5 + 10 min; D3 from the run's opening, not from 01:00; D4 30 min (no reason reads manual) + D6 6 h; DJ is not a member"
            )
            assertEquals(2L, report.blocked.total)
            assertEquals(mapOf("dependency" to 1, "manual" to 1), report.blocked.byReason)
        }

    @Test
    fun `S9 blocked dwell of a closed run is cut at the closing instant and a block that starts there adds nothing`(): Unit =
        runBlocking {
            val f = fx
            DwellWorld.seed(f, closed = true)

            val report = ok(f, DwellWorld.QD)

            assertEquals(
                mapOf(
                    "dependency" to 4_200_000L,
                    "resource" to 900_000L,
                    "awaiting_decision" to 1_800_000L,
                    "manual" to 18_000_000L
                ),
                report.blocked.dwellMs,
                "until = closedAt 11:00: D1 1 h + D5 10 min; D4's block starts at 11:00, the end of the half-open window; D6 5 h"
            )
            assertEquals(RollupWindow(DwellWorld.OPENED, DwellWorld.CLOSED_AT, false), report.window)
            assertEquals(
                RunHeader(RunStatus.CLOSED, RunTerminalReason.COMPLETED, DwellWorld.OPENED, DwellWorld.CLOSED_AT, RunKit.R),
                report.run
            )
        }

    // ------------------------------------------------------------------ AC3 wire shape

    @Test
    fun `AC3 the run rollup wire carries the run members in order and omits what is zero or unlimited`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            val json = rollupJson(ok(f, w.Q))

            assertEquals(
                listOf(
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
                ),
                json.keys.toList()
            )
            assertEquals(listOf("runId", "rootId"), json.keysOf("scope"))
            assertEquals("f6000001", json.textOf("scope", "runId"))
            assertEquals("a1a1a1a1", json.textOf("scope", "rootId"))
            assertEquals(listOf("since", "until"), json.keysOf("window"), "clamped is omitted when false")
            assertEquals(listOf("status", "openedAt"), json.keysOf("run"), "an open run has no terminalReason and no closedAt")
            assertEquals("open", json.textOf("run", "status"))
            assertEquals(listOf("total", "byRole"), json.keysOf("itemCounts"))
            assertEquals(
                listOf("total", "byReason", "resumable"),
                json.keysOf("blocked"),
                "this run has no member block events, so dwellMs is omitted"
            )
            assertEquals("3", json.textOf("blocked", "resumable"))
            assertEquals(listOf("open", "byClass", "bySeverity"), json.keysOf("decisions"))
            assertEquals(listOf("total", "cascades", "rejected"), json.keysOf("transitions"))
            assertEquals(listOf("written"), json.keysOf("notes"))
            assertEquals(listOf("calls", "errors", "retries", "responseBytes", "responseTokensEst", "basis"), json.keysOf("cost"))
            assertEquals("run", json.textOf("cost", "basis"))
            assertEquals(listOf("used", "limit", "basis"), json.keysOf("budget"))
            assertEquals("450", json.textOf("budget", "used"))
            assertEquals("1000", json.textOf("budget", "limit"))
            assertEquals("enforced", json.textOf("budget", "basis"))
        }

    @Test
    fun `AC3 a closed run adds its terminal reason and closing instant, an unlimited budget has no limit member`(): Unit =
        runBlocking {
            val f = fx
            DwellWorld.seed(f, closed = true)

            val json = rollupJson(ok(f, DwellWorld.QD))

            assertEquals(listOf("status", "terminalReason", "openedAt", "closedAt"), json.keysOf("run"))
            assertEquals("closed", json.textOf("run", "status"))
            assertEquals("completed", json.textOf("run", "terminalReason"))
            assertEquals(
                listOf("total", "byReason", "dwellMs"),
                json.keysOf("blocked"),
                "no awaiting_decision member decision: resumable is zero and omitted"
            )
            assertEquals(listOf("used", "basis"), json.keysOf("budget"), "no limit: the member is omitted")
        }

    // ------------------------------------------------------------------ S13 request validation

    private suspend fun assertInvalid(
        f: ReportFixture,
        request: ReportRollupRequest,
        pointer: String,
        label: String
    ) {
        val error = err(rollup(f, request))
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, label)
        val fields = assertIs<ErrorDetail.InvalidRequest>(error.detail, label).fields.map { it.field }
        assertTrue(pointer in fields, "$label: expected $pointer in $fields")
    }

    @Test
    fun `S13 both ids is invalid at runId and neither is invalid at anchorId, the R16 behaviour`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)

            assertInvalid(f, ReportRollupRequest(anchorId = IdRef(w.X.toString()), runId = IdRef(w.Q.toString())), "/runId", "both ids")
            assertInvalid(f, ReportRollupRequest(), "/anchorId", "neither id")
            assertInvalid(f, ReportRollupRequest(since = "2026-10-13T00:00:00Z"), "/anchorId", "a window and no id")
            // control: the run id alone is a valid request
            assertEquals(12L, ok(f, w.Q).items.total)
        }

    @Test
    fun `S13 a run rollup refuses since at since and until alone at until, a run always covers its whole span`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            val run = IdRef(w.Q.toString())

            assertInvalid(f, ReportRollupRequest(runId = run, since = "2026-10-13T00:00:00Z"), "/since", "since")
            assertInvalid(f, ReportRollupRequest(runId = run, until = "2026-10-13T11:00:00Z"), "/until", "until alone")
            assertInvalid(
                f,
                ReportRollupRequest(runId = run, since = "2026-10-13T00:00:00Z", until = "2026-10-13T11:00:00Z"),
                "/since",
                "both bounds"
            )
        }

    // ------------------------------------------------------------------ S14 resolution and visibility

    @Test
    fun `S14 unrestricted - full id and hex prefix resolve, unknown is not_found, shared prefix is ambiguous`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            RunKit.run(f, UUID.fromString("e7000001-1111-4111-8111-111111111111"))
            RunKit.run(f, UUID.fromString("e7000001-2222-4222-8222-222222222222"))
            val full = ok(f, w.Q)

            for (ref in listOf("f6000001", "f60000010000", w.Q.toString().replace("-", ""))) {
                val byRef = ok(f, w.Q, ref = ref)
                assertEquals(w.Q, byRef.scope.runId, ref)
                assertEquals(full, byRef, ref)
            }
            val unknown = err(rollup(f, ReportRollupRequest(runId = IdRef("99999999-9999-4999-8999-999999999999"))))
            assertEquals(ErrorCode.NOT_FOUND, unknown.code)
            val unknownPrefix = err(rollup(f, ReportRollupRequest(runId = IdRef("dddddddd"))))
            assertEquals(ErrorCode.NOT_FOUND, unknownPrefix.code)
            val ambiguous = err(rollup(f, ReportRollupRequest(runId = IdRef("e7000001"))))
            assertEquals(ErrorCode.AMBIGUOUS_ID, ambiguous.code)
            assertEquals(
                UUID.fromString("e7000001-1111-4111-8111-111111111111"),
                ok(f, UUID.fromString("e7000001-1111-4111-8111-111111111111")).scope.runId,
                "control: the full id of either still resolves"
            )
        }

    @Test
    fun `S14 root scope - a run of another root and an unknown run are both forbidden, never not_found`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            val ownRun = RunKit.id("f6840000")
            RunKit.run(f, ownRun, root = w.R2)
            val inR2 = f.ctx(AccessScope.principal(setOf(w.R2), emptySet()))

            for (ref in listOf(w.Q.toString(), "f6000001", "99999999-9999-4999-8999-999999999999", "dddddddd")) {
                val error = err(rollup(f, ReportRollupRequest(runId = IdRef(ref)), inR2))
                assertEquals(ErrorCode.FORBIDDEN, error.code, ref)
                assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(error.detail, ref).scope, ref)
            }
            // controls: the own root's run answers, and the caller scoped to R reads Q
            assertEquals(ownRun, ok(f, ownRun, ctx = inR2).scope.runId)
            assertEquals(w.Q, ok(f, w.Q, ctx = f.ctx(AccessScope.principal(setOf(w.R), emptySet()))).scope.runId)
        }

    @Test
    fun `S14 tag scope - forbidden at the tag scope before any read, even for an unknown run`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            val tagged = f.ctx(AccessScope.principal(null, setOf("api")))

            for (ref in listOf(w.Q.toString(), "f6000001", "99999999-9999-4999-8999-999999999999")) {
                val error = err(rollup(f, ReportRollupRequest(runId = IdRef(ref)), tagged))
                assertEquals(ErrorCode.FORBIDDEN, error.code, ref)
                val detail = assertIs<ErrorDetail.Forbidden>(error.detail, ref)
                assertEquals(ForbiddenScope.TAG, detail.scope, ref)
                assertEquals("api", detail.required, ref)
            }
            assertEquals(12L, ok(f, w.Q).items.total, "control: an unrestricted caller reads the same run")
        }

    @Test
    fun `control - the run world holds the rows the exclusions rely on`(): Unit =
        runBlocking {
            val f = fx
            w.seedMain(f)
            val url = f.database.jdbcUrl

            assertEquals(13L, RollupRaw.count(url, "SELECT COUNT(*) FROM run_items WHERE run_id = x'" + hex(w.Q) + "'"))
            assertEquals(2L, RollupRaw.count(url, "SELECT COUNT(*) FROM runs"))
            assertEquals(6L, RollupRaw.count(url, "SELECT COUNT(*) FROM run_seats"))
            assertEquals(9L, RollupRaw.count(url, "SELECT COUNT(*) FROM decision_requests"))
            assertEquals(8L, RollupRaw.count(url, "SELECT COUNT(*) FROM call_log"))
            assertEquals(0L, RollupRaw.count(url, "SELECT COUNT(*) FROM events WHERE type = 'run.ended'"), "nothing here is closed")
        }

    private fun hex(id: UUID): String = id.toString().replace("-", "")
}
