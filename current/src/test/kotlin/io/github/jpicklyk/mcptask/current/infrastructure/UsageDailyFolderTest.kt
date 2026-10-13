package io.github.jpicklyk.mcptask.current.infrastructure

import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.CallRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.FoldRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.UsageFoldKit
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 515ec0a4, R13) for [UsageDailyFolder]: scenarios S1 to S6 of the frozen test-plan
 * (NEW-SURFACE) through the production SQLite database and unit of work, with a settable env clock fixed at 2026-10-13T12:00Z.
 *
 * Oracles (frozen task-scope D2 to D5 and AC2 to AC5): the last foldable day for unit instant `now` is
 * `utcDate(now - grace) - 1 day` with `grace` defaulting to one hour, so today is never folded and yesterday only once
 * `now >= today 01:00Z`; a pass folds the days after the watermark up to the last foldable day in order, at most `maxDaysPerPass`
 * (default 400) per pass, and reports `capped` when more remain; the first fold starts at the UTC date of the oldest `call_log`
 * row and an empty `call_log` folds nothing and leaves the watermark absent; empty days still advance the watermark; a day below
 * the watermark is never re-folded; deleting the watermark makes a re-fold recompute the same rows (REPLACE, never add);
 * `foldedThrough()` answers the folded day plus one day at 00:00:00Z, or null when no watermark exists. Expected rows are hand
 * sums of the seeded rows (see [UsageFoldKit.day10Rows] and [UsageFoldKit.day10Expected]).
 *
 * Negative-assertion conditions: every "never folded" assertion has a seeded row on that day that WOULD appear in `usage_daily`
 * if the day were folded (a row at 2026-10-13, a late row on a folded day, a row on the day after the cap).
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: raw rows keep the call_log CHECKs (see [UsageFoldKit.insertCalls]); `FoldPass`
 * expectations are derived from the seeded day spans, not from a run.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class UsageDailyFolderTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val jdbc get() = db.jdbcUrl

    private fun folder(
        grace: Duration = Duration.ofHours(1),
        max: Int = 400
    ) = UsageDailyFolder(unitOfWork = db.unitOfWork(), grace = grace, maxDaysPerPass = max)

    private suspend fun foldedThrough(f: UsageDailyFolder): Instant? = db.unitOfWork().read { f.foldedThrough() }

    private fun day(iso: String) = LocalDate.parse(iso)

    private fun at(iso: String) = UsageFoldKit.at(iso)

    private fun deleteWatermark() {
        DriverManager.getConnection(jdbc).use { c -> c.createStatement().use { it.executeUpdate("DELETE FROM usage_fold") } }
    }

    // ------------------------------------------------------------------ lastFoldableDay

    @Test
    fun `lastFoldableDay is the UTC date of now minus grace, minus one day`() {
        val hour = Duration.ofHours(1)
        assertEquals(day("2026-10-12"), UsageDailyFolder.lastFoldableDay(at("2026-10-13T12:00:00Z"), hour))
        assertEquals(
            day("2026-10-11"),
            UsageDailyFolder.lastFoldableDay(at("2026-10-13T00:30:00Z"), hour),
            "yesterday is not complete until grace has passed"
        )
        assertEquals(
            day("2026-10-11"),
            UsageDailyFolder.lastFoldableDay(at("2026-10-13T00:59:59.999Z"), hour),
            "one millisecond before today + grace"
        )
        assertEquals(day("2026-10-12"), UsageDailyFolder.lastFoldableDay(at("2026-10-13T01:00:00.000Z"), hour), "today + grace exactly")
        assertEquals(day("2026-10-12"), UsageDailyFolder.lastFoldableDay(at("2026-10-13T23:59:59.999Z"), hour))
        assertEquals(
            day("2026-10-12"),
            UsageDailyFolder.lastFoldableDay(at("2026-10-14T00:30:00Z"), hour),
            "after midnight the 13th is not yet foldable"
        )
    }

    @Test
    fun `lastFoldableDay follows the grace it is given`() {
        assertEquals(day("2026-10-12"), UsageDailyFolder.lastFoldableDay(at("2026-10-13T00:00:00Z"), Duration.ZERO))
        assertEquals(day("2026-10-11"), UsageDailyFolder.lastFoldableDay(at("2026-10-12T23:59:59.999Z"), Duration.ZERO))
        assertEquals(day("2026-10-11"), UsageDailyFolder.lastFoldableDay(at("2026-10-13T12:00:00Z"), Duration.ofHours(24)))
        assertEquals(day("2026-10-12"), UsageDailyFolder.lastFoldableDay(at("2026-10-14T00:00:00Z"), Duration.ofHours(24)))
    }

    // ------------------------------------------------------------------ S1 a pass

    @Test
    fun `S1 a pass folds every completed day from the first call day, groups the rows and writes the watermark`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())
            val f = folder()
            assertNull(foldedThrough(f), "no watermark before the first fold")

            val pass = f.foldOnce()

            assertEquals(FoldPass(days = 3, groups = 5, foldedThrough = day("2026-10-12"), capped = false), pass, "10th, 11th and 12th")
            assertEquals(UsageFoldKit.day10Expected(), UsageFoldKit.readDaily(jdbc))
            assertEquals(listOf(FoldRow(1, "2026-10-12", "2026-10-13 12:00:00.000")), UsageFoldKit.readFold(jdbc))
            assertEquals(Instant.parse("2026-10-13T00:00:00Z"), foldedThrough(f), "the folded day plus one day, at midnight")
        }

    @Test
    fun `S1 the last millisecond of a completed day is folded and the first millisecond of today is not`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-12T23:59:59.999Z"), "edge_tool", latencyMs = 7),
                CallRow(2, at("2026-10-13T00:00:00.000Z"), "edge_tool", latencyMs = 900)
            )

            folder().foldOnce()

            assertEquals(
                listOf(UsageFoldKit.dailyRow("2026-10-12", null, "edge_tool", calls = 1, latencySum = 7, latencyMax = 7)),
                UsageFoldKit.readDaily(jdbc)
            )
        }

    // ------------------------------------------------------------------ S2 idempotency

    @Test
    fun `S2 a second pass folds nothing and changes nothing`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())
            val f = folder()
            f.foldOnce()
            val daily = UsageFoldKit.readDaily(jdbc)
            val fold = UsageFoldKit.readFold(jdbc)

            val again = f.foldOnce()

            assertEquals(0, again.days)
            assertEquals(0, again.groups)
            assertEquals(false, again.capped)
            assertEquals(daily, UsageFoldKit.readDaily(jdbc))
            assertEquals(fold, UsageFoldKit.readFold(jdbc))
        }

    @Test
    fun `S2 deleting the watermark and folding again recomputes the identical rows`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())
            val f = folder()
            val first = f.foldOnce()
            val daily = UsageFoldKit.readDaily(jdbc)
            deleteWatermark()
            assertEquals(emptyList(), UsageFoldKit.readFold(jdbc), "control: the watermark is really gone")
            assertNull(foldedThrough(f))

            val second = f.foldOnce()

            assertEquals(first, second, "the fold restarts from the first call day")
            assertEquals(daily, UsageFoldKit.readDaily(jdbc), "a replay recomputes the same values, it does not add to them")
            assertEquals(UsageFoldKit.day10Expected(), UsageFoldKit.readDaily(jdbc))
            assertEquals(listOf(FoldRow(1, "2026-10-12", "2026-10-13 12:00:00.000")), UsageFoldKit.readFold(jdbc))
        }

    // ------------------------------------------------------------------ S3 empty days

    @Test
    fun `S3 days without rows advance the watermark and add no usage_daily row`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, CallRow(1, at("2026-10-05T10:00:00Z"), "lonely", latencyMs = 3))

            val pass = folder().foldOnce()

            assertEquals(FoldPass(days = 8, groups = 1, foldedThrough = day("2026-10-12"), capped = false), pass, "5th to 12th inclusive")
            assertEquals(
                listOf(UsageFoldKit.dailyRow("2026-10-05", null, "lonely", calls = 1, latencySum = 3, latencyMax = 3)),
                UsageFoldKit.readDaily(jdbc)
            )
            assertEquals("2026-10-12", UsageFoldKit.readFold(jdbc).single().foldedThrough)
        }

    // ------------------------------------------------------------------ S4 partial days

    @Test
    fun `S4 yesterday is folded only once today plus the grace has started, and today never`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-11T10:00:00Z"), "d11", latencyMs = 1),
                CallRow(2, at("2026-10-12T10:00:00Z"), "d12", latencyMs = 2),
                CallRow(3, at("2026-10-13T00:10:00Z"), "d13", latencyMs = 3)
            )
            val f = folder()

            clock.set(at("2026-10-13T00:30:00Z"))
            val early = f.foldOnce()
            assertEquals(FoldPass(days = 1, groups = 1, foldedThrough = day("2026-10-11"), capped = false), early)
            assertEquals(listOf("2026-10-11"), UsageFoldKit.readDaily(jdbc).map { it.day }, "the 12th is still inside the grace hour")
            assertEquals(Instant.parse("2026-10-12T00:00:00Z"), foldedThrough(f))

            clock.set(at("2026-10-13T00:59:59.999Z"))
            assertEquals(0, f.foldOnce().days, "one millisecond before the grace ends")
            assertEquals(listOf("2026-10-11"), UsageFoldKit.readDaily(jdbc).map { it.day })

            clock.set(at("2026-10-13T01:00:00.000Z"))
            val later = f.foldOnce()
            assertEquals(FoldPass(days = 1, groups = 1, foldedThrough = day("2026-10-12"), capped = false), later)
            assertEquals(listOf("2026-10-11", "2026-10-12"), UsageFoldKit.readDaily(jdbc).map { it.day })

            clock.set(at("2026-10-13T23:59:59.999Z"))
            assertEquals(0, f.foldOnce().days, "today is never folded, the row at 00:10 stays unfolded")
            assertEquals(listOf("2026-10-11", "2026-10-12"), UsageFoldKit.readDaily(jdbc).map { it.day })
            assertEquals(Instant.parse("2026-10-13T00:00:00Z"), foldedThrough(f))
        }

    @Test
    fun `S4 a grace of zero folds yesterday at midnight while the default grace waits`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-11T10:00:00Z"), "d11"),
                CallRow(2, at("2026-10-12T10:00:00Z"), "d12")
            )
            clock.set(at("2026-10-13T00:00:00Z"))

            val withGrace = folder().foldOnce()
            assertEquals(day("2026-10-11"), withGrace.foldedThrough, "the default one hour grace keeps the 12th open at midnight")
            assertEquals(listOf("2026-10-11"), UsageFoldKit.readDaily(jdbc).map { it.day })

            val noGrace = folder(grace = Duration.ZERO).foldOnce()
            assertEquals(FoldPass(days = 1, groups = 1, foldedThrough = day("2026-10-12"), capped = false), noGrace)
            assertEquals(listOf("2026-10-11", "2026-10-12"), UsageFoldKit.readDaily(jdbc).map { it.day })
        }

    @Test
    fun `S4 probe a clock that moves backwards folds nothing and never moves the watermark back`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, CallRow(1, at("2026-10-10T10:00:00Z"), "t", latencyMs = 4))
            val f = folder()
            f.foldOnce()
            val daily = UsageFoldKit.readDaily(jdbc)
            val fold = UsageFoldKit.readFold(jdbc)

            clock.set(at("2026-10-11T12:00:00Z"))
            val back = f.foldOnce()

            assertEquals(0, back.days)
            assertEquals(daily, UsageFoldKit.readDaily(jdbc))
            assertEquals(fold, UsageFoldKit.readFold(jdbc))
            assertEquals(Instant.parse("2026-10-13T00:00:00Z"), foldedThrough(f))
        }

    // ------------------------------------------------------------------ S5 watermark and cap

    @Test
    fun `S5 an empty call_log folds nothing and leaves the watermark absent, and the next pass still folds rows that arrive later`(): Unit =
        runBlocking {
            val f = folder()

            val empty = f.foldOnce()

            assertEquals(FoldPass(days = 0, groups = 0, foldedThrough = null, capped = false), empty)
            assertEquals(emptyList(), UsageFoldKit.readFold(jdbc))
            assertNull(foldedThrough(f))
            assertEquals(emptyList(), UsageFoldKit.readDaily(jdbc))

            UsageFoldKit.insertCalls(jdbc, CallRow(1, at("2026-10-05T10:00:00Z"), "later", latencyMs = 1))
            val next = f.foldOnce()
            assertEquals(8, next.days, "the absent watermark is not sticky")
            assertEquals(1, UsageFoldKit.readDaily(jdbc).size)
        }

    @Test
    fun `S5 maxDaysPerPass bounds a pass, reports capped while days remain, and the next pass resumes after the last folded day`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-03T10:00:00Z"), "r03"),
                CallRow(2, at("2026-10-04T10:00:00Z"), "r04"),
                CallRow(3, at("2026-10-09T10:00:00Z"), "r09")
            )
            val f = folder(max = 3)

            assertEquals(FoldPass(days = 3, groups = 2, foldedThrough = day("2026-10-05"), capped = true), f.foldOnce())
            assertEquals(listOf("2026-10-03", "2026-10-04"), UsageFoldKit.readDaily(jdbc).map { it.day })
            assertEquals(Instant.parse("2026-10-06T00:00:00Z"), foldedThrough(f))

            assertEquals(FoldPass(days = 3, groups = 0, foldedThrough = day("2026-10-08"), capped = true), f.foldOnce())
            assertEquals(FoldPass(days = 3, groups = 1, foldedThrough = day("2026-10-11"), capped = true), f.foldOnce())
            assertEquals(listOf("2026-10-03", "2026-10-04", "2026-10-09"), UsageFoldKit.readDaily(jdbc).map { it.day })

            assertEquals(
                FoldPass(days = 1, groups = 0, foldedThrough = day("2026-10-12"), capped = false),
                f.foldOnce(),
                "10 days in total, 3 + 3 + 3 + 1"
            )
            val done = f.foldOnce()
            assertEquals(0, done.days)
            assertEquals(false, done.capped)
            assertEquals(listOf(FoldRow(1, "2026-10-12", "2026-10-13 12:00:00.000")), UsageFoldKit.readFold(jdbc))
        }

    @Test
    fun `S5 the default cap is 400 days per pass`(): Unit =
        runBlocking {
            val start = day("2025-09-01")
            UsageFoldKit.insertCalls(jdbc, CallRow(1, at("2025-09-01T10:00:00Z"), "ancient"))
            val total = ChronoUnit.DAYS.between(start, day("2026-10-12")) + 1

            val first = folder().foldOnce()

            assertEquals(FoldPass(days = 400, groups = 1, foldedThrough = start.plusDays(399), capped = true), first)
            val second = folder().foldOnce()
            assertEquals(FoldPass(days = (total - 400).toInt(), groups = 0, foldedThrough = day("2026-10-12"), capped = false), second)
        }

    // ------------------------------------------------------------------ S6 late rows

    @Test
    fun `S6 a row added to an already folded day is never folded`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())
            val f = folder()
            f.foldOnce()
            val daily = UsageFoldKit.readDaily(jdbc)
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(20, at("2026-10-10T12:00:00Z"), "t_a", "get", "mcp", "h1", "subagent", latencyMs = 999, rootId = UsageFoldKit.R1),
                CallRow(21, at("2026-10-10T12:00:00Z"), "brand_new_tool", latencyMs = 5)
            )

            val pass = f.foldOnce()

            assertEquals(0, pass.days)
            assertEquals(daily, UsageFoldKit.readDaily(jdbc), "neither the late row of an existing group nor of a new group appears")
            assertEquals(UsageFoldKit.day10Expected(), UsageFoldKit.readDaily(jdbc))
        }
}
