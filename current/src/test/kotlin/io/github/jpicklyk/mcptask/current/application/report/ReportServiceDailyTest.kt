package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.UsageDailyFolder
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.UsageFoldKit
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 515ec0a4, R13) for `ReportService.usage` on the daily basis over the real SQLite
 * database, the real fold and the real [ReportQueries] port: scenarios S8 to S12 and S14 of the frozen test-plan (S8 is
 * EXISTING-SURFACE: the 31 day rule of R9; the rest NEW-SURFACE). Rows are seeded through the production call-log store, folded by
 * [UsageDailyFolder] at the env clock (2026-10-13T12:00Z, so F = 2026-10-13T00:00Z) and pruned below F with the production store,
 * exactly as the retention pass leaves them.
 *
 * Oracles (frozen task-scope D6 to D10, AC7 to AC9; R9 AC1 for the metrics): with L = until - since, F the fold watermark and
 * R = `retainedSince`, the basis is raw iff `L <= 31 days` and not (`F` set, `R` set and `since < R`), otherwise daily; `L > 400
 * days` is `invalid_request` on `/since`; a daily window floors `since` to its UTC midnight and, when `F` is set and `until <= F`,
 * ceils `until` to the next UTC midnight (unchanged at a midnight), both echoed in `window`; the daily numbers equal the raw
 * aggregate over the aligned window computed before any fold or prune (no gap, no double count at F); `groupBy principal` is
 * `invalid_request` on `/groupBy` on the daily basis and works on the raw one; every other usage grouping works on both; root
 * scope and `rootId` apply to both parts and a NULL-root row is never visible to a root scope; `retainedSince` stays the oldest raw
 * row and `truncatedByRetention` holds when the aligned `since` is before `min(retainedSince, earliest usage_daily day at 00:00Z)`.
 * Every expected number is a hand sum of the rows seeded in the test.
 *
 * Negative-assertion conditions: each "not counted" assertion has a seeded row that WOULD change a sum (a row one millisecond
 * after the ceiled `until`, a NULL-root row under a root scope, a row before the aligned `since`'s day); the before-fold report is
 * compared with the after-fold-and-prune report so a gap or a double count at F shows as a difference.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: records come from [ReportKit.rec] (outcome derived from the error code, token
 * estimate from the response bytes); roots are depth-0 items.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ReportServiceDailyTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2

    private fun at(iso: String) = Instant.parse(iso)

    private fun req(
        since: String? = null,
        until: String? = null,
        groupBy: ReportGroupBy? = null,
        rootId: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ) = ReportUsageRequest(rootId?.let { IdRef(it) }, since, until, groupBy, limit, cursor)

    private data class M(
        val key: String?,
        val calls: Long,
        val errors: Long,
        val bytes: Long,
        val tokens: Long,
        val avg: Long,
        val max: Long,
        val retries: Long,
        val replays: Long
    )

    private fun UsageRow.m() = M(key, calls, errors, responseBytes, responseTokensEst, avgLatencyMs, maxLatencyMs, retries, replays)

    private fun UsageReport.rows() = page.items.map { it.m() }

    private suspend fun foldAll() {
        UsageDailyFolder(db.unitOfWork()).foldOnce()
    }

    /** What the retention pass leaves behind: every completed day folded, every raw row below F deleted. */
    private suspend fun foldAndPrune() {
        foldAll()
        db.unitOfWork().write("test.prune") {
            stores.callLogStore().prune(UsageFoldKit.F, 10_000)
            Outcome.Ok(Unit)
        }
    }

    private fun err(outcome: Outcome<UsageReport>) = assertIs<Outcome.Err>(outcome).error

    private val sixtyDaysAgo get() = now.minus(Duration.ofDays(60)).toString()

    // ------------------------------------------------------------------ S8 / AC8 the daily basis

    @Test
    fun `S8 a window over 31 days is daily, aligned, and equals the raw aggregate computed before the fold and prune`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-08-20T10:00:00Z"), "a", latencyMs = 10, responseBytes = 40),
                ReportKit.rec(2, at("2026-09-15T22:30:00Z"), "a", error = "timeout", latencyMs = 20, attempts = 2),
                ReportKit.rec(3, at("2026-10-12T23:59:59.999Z"), "b", latencyMs = 6),
                ReportKit.rec(4, at("2026-10-13T00:00:00Z"), "a", latencyMs = 30, replayed = true),
                ReportKit.rec(5, at("2026-10-13T11:00:00Z"), "b", latencyMs = 8)
            )
            val expected =
                listOf(
                    M("a", calls = 3, errors = 1, bytes = 40, tokens = 10, avg = 20, max = 30, retries = 1, replays = 1),
                    M("b", calls = 2, errors = 0, bytes = 0, tokens = 0, avg = 7, max = 8, retries = 0, replays = 0)
                )

            val before = f.ok(req(since = sixtyDaysAgo))
            foldAndPrune()
            val after = f.ok(req(since = sixtyDaysAgo))

            for ((label, report) in listOf("before fold" to before, "after fold and prune" to after)) {
                assertEquals("daily", report.window.basis, label)
                assertEquals(at("2026-08-14T00:00:00Z"), report.window.since, "$label: since floors to its UTC midnight")
                assertEquals(now, report.window.until, "$label: until is after F, so it stays exact")
                assertEquals(expected, report.rows(), label)
                assertEquals(2, report.page.total, label)
            }
        }

    @Test
    fun `S8 after the prune retainedSince is the oldest raw row, and the window is truncated only before the earliest folded day`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-08-20T10:00:00Z"), "a"),
                ReportKit.rec(2, at("2026-10-13T00:00:00Z"), "a")
            )
            foldAndPrune()

            val wide = f.ok(req(since = sixtyDaysAgo))
            assertEquals(at("2026-10-13T00:00:00Z"), wide.window.retainedSince, "the oldest surviving raw row")
            assertTrue(wide.window.truncatedByRetention, "08-14 is before the earliest folded day")

            val atEarliest = f.ok(req(since = "2026-08-20T10:00:00Z"))
            assertEquals("daily", atEarliest.window.basis)
            assertEquals(at("2026-08-20T00:00:00Z"), atEarliest.window.since)
            assertFalse(
                atEarliest.window.truncatedByRetention,
                "the aligned since equals the earliest folded day at 00:00Z, and the daily table covers it"
            )
            assertEquals(2L, atEarliest.page.items.sumOf { it.calls })

            val justBefore = f.ok(req(since = "2026-08-19T23:59:59.999Z"))
            assertEquals(at("2026-08-19T00:00:00Z"), justBefore.window.since)
            assertTrue(justBefore.window.truncatedByRetention, "the aligned since is before the earliest folded day")
        }

    // ------------------------------------------------------------------ S9 the raw / daily boundary

    @Test
    fun `S9 rows around F are counted once, before and after the fold and the prune`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-10-12T10:00:00Z"), "x", latencyMs = 1),
                ReportKit.rec(2, at("2026-10-12T23:59:59.999Z"), "x", latencyMs = 2),
                ReportKit.rec(3, at("2026-10-13T00:00:00Z"), "x", latencyMs = 3),
                ReportKit.rec(4, at("2026-10-13T01:00:00Z"), "x", latencyMs = 4)
            )
            val all = req(since = "2026-08-01T00:00:00Z")
            val upToF = req(since = "2026-08-01T00:00:00Z", until = "2026-10-13T00:00:00Z")

            val allBefore = f.ok(all).rows()
            foldAndPrune()
            val allAfter = f.ok(all).rows()
            val upToFAfter = f.ok(upToF)

            assertEquals(
                listOf(M("x", 4, 0, 0, 0, avg = 3, max = 4, retries = 0, replays = 0)),
                allBefore,
                "4 calls, 10 ms: 2.5 rounds up to 3"
            )
            assertEquals(allBefore, allAfter, "no gap and no double count at F")
            assertEquals(
                listOf(M("x", 2, 0, 0, 0, avg = 2, max = 2, retries = 0, replays = 0)),
                upToFAfter.rows(),
                "the two folded rows only: 1.5 rounds up to 2"
            )
            assertEquals(at("2026-10-13T00:00:00Z"), upToFAfter.window.until, "until equal to F stays")
        }

    // ------------------------------------------------------------------ S10 alignment

    @Test
    fun `S10 since floors to its UTC midnight, until inside folded days ceils to the next midnight, both echoed`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-09-01T03:00:00Z"), "t", latencyMs = 1),
                ReportKit.rec(2, at("2026-10-02T10:00:00Z"), "t", latencyMs = 2),
                ReportKit.rec(3, at("2026-10-02T20:00:00Z"), "t", latencyMs = 3),
                ReportKit.rec(4, at("2026-10-03T01:00:00Z"), "t", latencyMs = 4)
            )
            foldAndPrune()

            val a = f.ok(req(since = "2026-09-01T07:30:00Z", until = "2026-10-02T15:45:00Z"))
            assertEquals("daily", a.window.basis, "31 days 8 h 15 min")
            assertEquals(at("2026-09-01T00:00:00Z"), a.window.since)
            assertEquals(at("2026-10-03T00:00:00Z"), a.window.until, "the window ends inside the folded 2nd: the whole day is served")
            assertEquals(
                listOf(M("t", 3, 0, 0, 0, avg = 2, max = 3, retries = 0, replays = 0)),
                a.rows(),
                "rows 1 to 3; the row at 03:00 on the 1st and the one at 20:00 on the 2nd are in"
            )

            for (until in listOf("2026-10-02T00:00:00.001Z", "2026-10-02T23:59:59.999Z")) {
                val g = f.ok(req(since = "2026-09-01T00:00:00Z", until = until))
                assertEquals("daily", g.window.basis, until)
                assertEquals(at("2026-10-03T00:00:00Z"), g.window.until, "until $until ceils to the next midnight")
            }

            val midnight = f.ok(req(since = "2026-09-01T07:30:00Z", until = "2026-10-03T00:00:00Z"))
            assertEquals(at("2026-10-03T00:00:00Z"), midnight.window.until, "a midnight until is unchanged")
            assertEquals(3L, midnight.page.items.sumOf { it.calls }, "the row at 01:00 on the 3rd is outside")

            val atF = f.ok(req(since = "2026-09-10T00:00:00Z", until = "2026-10-13T00:00:00Z"))
            assertEquals(at("2026-10-13T00:00:00Z"), atF.window.until, "until equal to F is already a midnight")

            val afterF = f.ok(req(since = "2026-09-10T00:00:00Z", until = "2026-10-13T00:00:00.001Z"))
            assertEquals(at("2026-10-13T00:00:00.001Z"), afterF.window.until, "until after F is not ceiled")

            val offset = f.ok(req(since = "2026-09-01T01:30:00+02:00", until = "2026-10-05T00:00:00Z"))
            assertEquals(at("2026-08-31T00:00:00Z"), offset.window.since, "08-31T23:30Z floors to the UTC date, not the offset's date")
            assertEquals(4L, offset.page.items.sumOf { it.calls })
        }

    // ------------------------------------------------------------------ S11 the raw horizon

    @Test
    fun `S11 a 7 day window that starts before the oldest raw row is raw and truncated while no fold exists`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, at("2026-10-11T10:00:00Z"), "t"))

            val report = f.ok(req())

            assertEquals("raw", report.window.basis)
            assertEquals(at("2026-10-06T12:00:00Z"), report.window.since, "a raw window is not aligned")
            assertTrue(report.window.truncatedByRetention)
        }

    @Test
    fun `S11 the same window is daily once a fold exists and the oldest raw row is after since`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, at("2026-10-11T10:00:00Z"), "t"))
            foldAll()

            val report = f.ok(req())

            assertEquals("daily", report.window.basis)
            assertEquals(at("2026-10-06T00:00:00Z"), report.window.since)
            assertEquals(now, report.window.until)
            assertEquals(at("2026-10-11T10:00:00Z"), report.window.retainedSince, "retainedSince keeps meaning the oldest raw row")
            assertTrue(report.window.truncatedByRetention, "the earliest folded day is the 11th, after the aligned since")
            assertEquals(
                1L,
                report.page.items
                    .single()
                    .calls
            )
        }

    @Test
    fun `S11 the daily basis is not truncated when the daily table reaches back to the aligned since`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-10-06T13:00:00Z"), "t"),
                ReportKit.rec(2, at("2026-10-11T10:00:00Z"), "t")
            )
            foldAll()

            val report = f.ok(req())

            assertEquals("daily", report.window.basis, "since 10-06T12:00 is before the oldest raw row 10-06T13:00")
            assertEquals(at("2026-10-06T00:00:00Z"), report.window.since)
            assertFalse(
                report.window.truncatedByRetention,
                "min(10-06T13:00, earliest folded day 10-06T00:00) is not after the aligned since"
            )
            assertEquals(
                2L,
                report.page.items
                    .single()
                    .calls
            )
        }

    @Test
    fun `S11 a window starting at or after the oldest raw row stays raw even with a fold`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-10-06T12:00:00Z"), "t"),
                ReportKit.rec(2, at("2026-10-11T10:00:00Z"), "t")
            )
            foldAll()

            val equalToOldest = f.ok(req(since = "2026-10-06T12:00:00Z"))
            assertEquals("raw", equalToOldest.window.basis, "since equal to the oldest raw row is not before it")
            assertEquals(at("2026-10-06T12:00:00Z"), equalToOldest.window.since)
            assertFalse(equalToOldest.window.truncatedByRetention)

            val oneMillisecondBefore = f.ok(req(since = "2026-10-06T11:59:59.999Z"))
            assertEquals("daily", oneMillisecondBefore.window.basis, "one millisecond before the oldest raw row")
            assertEquals(at("2026-10-06T00:00:00Z"), oneMillisecondBefore.window.since)
        }

    @Test
    fun `S11 the 31 day raw cap with a fold present - exactly 31 days from the oldest row is raw, one millisecond more is daily`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, at("2026-09-01T00:00:00Z"), "t"))
            foldAll()
            val exact = now.minus(Duration.ofDays(31))

            val raw = f.ok(req(since = exact.toString()))
            assertEquals("raw", raw.window.basis)
            assertEquals(exact, raw.window.since)

            val daily = f.ok(req(since = exact.minusMillis(1).toString()))
            assertEquals("daily", daily.window.basis)
            assertEquals(at("2026-09-12T00:00:00Z"), daily.window.since)
        }

    // ------------------------------------------------------------------ S12 failures and the 400 day cap

    @Test
    fun `S12 grouping by principal is invalid_request at the groupBy pointer on the daily basis and works on the raw one`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, at("2026-10-11T10:00:00Z"), "t", principalId = "p1"))

            val dailyByWindow = err(f.usage(req(since = sixtyDaysAgo, groupBy = ReportGroupBy.PRINCIPAL)))
            assertEquals(ErrorCode.INVALID_REQUEST, dailyByWindow.code)
            assertTrue("/groupBy" in assertIs<ErrorDetail.InvalidRequest>(dailyByWindow.detail).fields.map { it.field })

            val rawNoFold = f.ok(req(groupBy = ReportGroupBy.PRINCIPAL))
            assertEquals(
                listOf("p1"),
                rawNoFold.page.items.map { it.key },
                "control: no fold, so the default window is raw and principal works"
            )

            foldAll()
            val dailyByHorizon = err(f.usage(req(groupBy = ReportGroupBy.PRINCIPAL)))
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                dailyByHorizon.code,
                "a fold exists and since is before the oldest raw row, so the 7 day window is daily"
            )
            assertTrue("/groupBy" in assertIs<ErrorDetail.InvalidRequest>(dailyByHorizon.detail).fields.map { it.field })
        }

    @Test
    fun `S12 grouping by principal still works on a raw window when a fold exists`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-10-05T10:00:00Z"), "t", principalId = "p1"),
                ReportKit.rec(2, at("2026-10-11T10:00:00Z"), "t", principalId = "p2")
            )
            foldAll()

            val report = f.ok(req(groupBy = ReportGroupBy.PRINCIPAL))

            assertEquals("raw", report.window.basis, "since 10-06T12:00 is after the oldest raw row 10-05T10:00")
            assertEquals(
                setOf("p2"),
                report.page.items
                    .map { it.key }
                    .toSet()
            )
        }

    @Test
    fun `S12 every other usage grouping works on the daily basis`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-09-20T10:00:00Z"), "t", operation = "get", host = "h", principalKind = "user", error = "x"),
                ReportKit.rec(2, at("2026-10-12T10:00:00Z"), "t", operation = "get", host = "h", principalKind = "user")
            )
            foldAndPrune()
            val groupings =
                listOf(
                    ReportGroupBy.TOOL,
                    ReportGroupBy.OPERATION,
                    ReportGroupBy.HOST,
                    ReportGroupBy.SURFACE,
                    ReportGroupBy.PRINCIPAL_KIND,
                    ReportGroupBy.ERROR_CODE,
                    ReportGroupBy.DAY
                )

            for (groupBy in groupings) {
                val report = f.ok(req(since = sixtyDaysAgo, groupBy = groupBy))

                assertEquals("daily", report.window.basis, groupBy.wire)
                assertEquals(2L, report.page.items.sumOf { it.calls }, groupBy.wire)
            }
        }

    @Test
    fun `S12 a window of exactly 400 days is accepted and one millisecond more is invalid at the since pointer`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, at("2026-10-11T10:00:00Z"), "t"))
            val exact = now.minus(Duration.ofDays(400))

            val ok = f.ok(req(since = exact.toString()))
            assertEquals("daily", ok.window.basis)
            assertEquals(at("2025-09-08T00:00:00Z"), ok.window.since)

            val error = err(f.usage(req(since = exact.minusMillis(1).toString())))
            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            assertTrue("/since" in assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.map { it.field })
        }

    @Test
    fun `S12 the 400 day cap applies to an explicit until as well`(): Unit =
        runBlocking {
            val f = fx
            f.ok(req(since = "2025-01-01T00:00:00Z", until = "2026-02-05T00:00:00Z"))
            val error = err(f.usage(req(since = "2025-01-01T00:00:00Z", until = "2026-02-05T00:00:00.001Z")))
            assertEquals(ErrorCode.INVALID_REQUEST, error.code)
            assertTrue("/since" in assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.map { it.field })
        }

    // ------------------------------------------------------------------ S14 scope on the daily basis

    private suspend fun seedScoped(f: ReportFixture) {
        f.root(r1, "Alpha root")
        f.root(r2, "Beta root")
        f.seed(
            ReportKit.rec(1, at("2026-08-20T10:00:00Z"), "shared", rootId = r1, latencyMs = 1),
            ReportKit.rec(2, at("2026-09-15T10:00:00Z"), "shared", rootId = r2, latencyMs = 2),
            ReportKit.rec(3, at("2026-09-16T10:00:00Z"), "null_only", latencyMs = 3),
            ReportKit.rec(4, at("2026-10-13T01:00:00Z"), "shared", rootId = r1, latencyMs = 4),
            ReportKit.rec(5, at("2026-10-13T02:00:00Z"), "null_tail", latencyMs = 5)
        )
        foldAndPrune()
    }

    @Test
    fun `S14 a root scope sees only its root's folded and raw rows, never a NULL-root row`(): Unit =
        runBlocking {
            val f = fx
            seedScoped(f)

            val scoped = f.ok(req(since = sixtyDaysAgo), f.ctx(AccessScope.principal(setOf(r1), emptySet())))
            val open = f.ok(req(since = sixtyDaysAgo))

            assertEquals(
                listOf(M("shared", 2, 0, 0, 0, avg = 3, max = 4, retries = 0, replays = 0)),
                scoped.rows(),
                "one folded and one raw row of R1; 5 / 2 rounds up"
            )
            assertEquals(1, scoped.page.total)
            assertEquals(
                setOf("shared", "null_only", "null_tail"),
                open.page.items
                    .map {
                        it.key
                    }.toSet(),
                "control: the unrestricted caller sees the NULL-root groups"
            )
            assertEquals(
                3L,
                open.page.items
                    .single { it.key == "shared" }
                    .calls
            )
        }

    @Test
    fun `S14 an empty root set sees nothing on the daily basis, a rootId filters both parts`(): Unit =
        runBlocking {
            val f = fx
            seedScoped(f)

            val none = f.ok(req(since = sixtyDaysAgo), f.ctx(AccessScope.principal(emptySet(), emptySet())))
            assertEquals(0, none.page.total)

            val byRoot = f.ok(req(since = sixtyDaysAgo, rootId = r2.toString()))
            assertEquals(listOf(M("shared", 1, 0, 0, 0, avg = 2, max = 2, retries = 0, replays = 0)), byRoot.rows())
        }

    @Test
    fun `S14 an absent dimension answers a NULL key on the daily basis`(): Unit =
        runBlocking {
            val f = fx
            seedScoped(f)

            val report = f.ok(req(since = sixtyDaysAgo, groupBy = ReportGroupBy.OPERATION))

            assertEquals(1, report.page.items.size, "every seeded row has no operation")
            assertNull(
                report.page.items
                    .single()
                    .key
            )
            assertEquals(
                5L,
                report.page.items
                    .single()
                    .calls
            )
        }

    // ------------------------------------------------------------------ paging and day grouping

    @Test
    fun `S8 paging works across the daily basis and every page carries the same aligned window`(): Unit =
        runBlocking {
            val f = fx
            var n = 0
            for ((tool, calls) in listOf("g1" to 3, "g2" to 2, "g3" to 1)) {
                repeat(calls) { f.seed(ReportKit.rec(++n, at("2026-09-%02dT10:00:00Z".format(n)), tool)) }
            }
            foldAndPrune()

            val p1 = f.ok(req(since = sixtyDaysAgo, limit = 2))
            val cursor = assertNotNull(p1.page.next)
            val p2 = f.ok(req(since = sixtyDaysAgo, limit = 2, cursor = cursor))

            assertEquals(listOf("g1", "g2"), p1.page.items.map { it.key })
            assertEquals(3, p1.page.total)
            assertTrue(p1.page.truncated)
            assertEquals(listOf("g3"), p2.page.items.map { it.key })
            assertFalse(p2.page.truncated)
            assertEquals(p1.window, p2.window)
            assertEquals("daily", p2.window.basis)
        }

    @Test
    fun `S9 the day grouping on the daily basis keys folded days and the raw tail separately`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, at("2026-09-30T10:00:00Z"), "x"),
                ReportKit.rec(2, at("2026-10-12T10:00:00Z"), "x"),
                ReportKit.rec(3, at("2026-10-12T11:00:00Z"), "x"),
                ReportKit.rec(4, at("2026-10-13T01:00:00Z"), "x")
            )
            foldAndPrune()

            val report = f.ok(req(since = sixtyDaysAgo, groupBy = ReportGroupBy.DAY))

            assertEquals(listOf("2026-10-12", "2026-09-30", "2026-10-13"), report.page.items.map { it.key }, "calls 2, 1, 1; ties by key")
            assertEquals(listOf(2L, 1L, 1L), report.page.items.map { it.calls })
        }
}
