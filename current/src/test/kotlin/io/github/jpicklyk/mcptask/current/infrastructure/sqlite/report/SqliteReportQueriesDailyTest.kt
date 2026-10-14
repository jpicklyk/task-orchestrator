package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report

import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsagePage
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.CallRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.UsageFoldKit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent contract tests (test-author seat, item 515ec0a4, R13) for `ReportQueries.usageDaily(query, rawFrom)` over the real
 * SQLite database: the store-level half of scenarios S8, S9, S10, S14 of the frozen test-plan (NEW-SURFACE). `usage_daily` rows
 * and `call_log` rows are seeded through raw SQL, independently of the fold, so each test states exactly which rows the stitched
 * read must see.
 *
 * Oracles (frozen task-scope D1, D7 to D9, AC8, AC9; R9 AC1): the query's `since` and `until` arrive already aligned; with
 * `rawFrom = F` the daily part is the `usage_daily` rows with `day >= date(since)` and `day < date(min(F, until))`, the raw part
 * is the `call_log` rows with `at` in `[max(since, F), until)` (none when `until <= F`), and with `rawFrom = null` there is no
 * daily part and the raw part is `[since, until)`; the two parts are summed per key in ONE statement with the R9 metrics (calls,
 * errors, response bytes, token estimates, latency sum, half-up average, maximum, retries, replays), so a key present in both
 * parts is one row; the `''` and zero-length-root sentinels read back as a NULL key and never match a root scope or a rootId
 * filter; `day` keys the daily part by its `day` column and the raw part by the UTC date of `at`; rows order calls descending then
 * key ascending with NULL first; `totalGroups` counts the visible groups.
 *
 * Negative-assertion conditions: the raw copy of a folded day (a row at F minus one millisecond with 999 ms latency) and the
 * row at `until` (5000 ms) would change a sum if the raw part started before F or ended after `until`; the day before `since`
 * and the day of `until` each hold a daily row that would change a sum if the day window were inclusive at the wrong end; the
 * NULL-root rows (daily sentinel and raw NULL) would appear under a root scope if the sentinel matched.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: `usage_daily` rows keep the table CHECKs (calls >= 1, other metrics >= 0, outcome
 * `ok` or `error`, `errors` equal to `calls` on the error group); raw rows keep the call_log CHECKs.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteReportQueriesDailyTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val jdbc get() = db.jdbcUrl
    private val r1: UUID = UsageFoldKit.R1
    private val r2: UUID = UsageFoldKit.R2
    private val f: Instant = UsageFoldKit.F
    private val now: Instant = ReportKit.NOW

    private fun at(iso: String) = UsageFoldKit.at(iso)

    private fun query(
        groupBy: UsageDimension = UsageDimension.TOOL,
        since: Instant = at("2026-10-01T00:00:00Z"),
        until: Instant = now,
        scopeRootIds: Set<UUID>? = null,
        rootId: UUID? = null,
        limit: Int = 50,
        offset: Int = 0
    ) = UsageQuery(since, until, groupBy, scopeRootIds, rootId, limit, offset)

    private suspend fun daily(
        q: UsageQuery,
        rawFrom: Instant? = f
    ): UsagePage = db.unitOfWork().read { db.repositoryProvider().reportQueries().usageDaily(q, rawFrom) }

    private fun UsagePage.keys(): List<String?> = rows.map { it.key }

    private fun UsageRow.longs(): List<Long> =
        listOf(calls, errors, responseBytes, responseTokensEst, latencySumMs, avgLatencyMs, maxLatencyMs, retries, replays)

    /** Four folded daily rows and four raw rows; see the class comment for what each is for. */
    private fun seedStitched() {
        UsageFoldKit.insertDaily(
            jdbc,
            UsageFoldKit.dailyRow(
                "2026-10-01",
                r1,
                "x",
                "get",
                host = "h1",
                principalKind = "subagent",
                calls = 4,
                requestBytes = 40,
                responseBytes = 100,
                tokens = 25,
                latencySum = 100,
                latencyMax = 40,
                retries = 1
            ),
            UsageFoldKit.dailyRow(
                "2026-10-02",
                r1,
                "x",
                "get",
                host = "h1",
                principalKind = "subagent",
                outcome = "error",
                errorCode = "timeout",
                calls = 2,
                errors = 2,
                requestBytes = 20,
                latencySum = 30,
                latencyMax = 20,
                retries = 3,
                replays = 1
            ),
            UsageFoldKit.dailyRow("2026-10-02", null, "y", calls = 1, responseBytes = 8, tokens = 2, latencySum = 2, latencyMax = 2),
            UsageFoldKit.dailyRow(
                "2026-10-12",
                r2,
                "x",
                "get",
                host = "h1",
                principalKind = "subagent",
                calls = 5,
                latencySum = 50,
                latencyMax = 25,
                replays = 2
            )
        )
        UsageFoldKit.insertCalls(
            jdbc,
            // raw copy of the folded 12th: before F, must not be counted again
            CallRow(
                1,
                at("2026-10-12T23:59:59.999Z"),
                "x",
                "get",
                "mcp",
                "h1",
                "subagent",
                latencyMs = 999,
                responseBytes = 4000,
                tokens = 1000,
                rootId = r1
            ),
            // the raw tail: at F and after F
            CallRow(
                2,
                at("2026-10-13T00:00:00.000Z"),
                "x",
                "get",
                "mcp",
                "h1",
                "subagent",
                attempts = 2,
                latencyMs = 51,
                responseBytes = 40,
                tokens = 10,
                rootId = r1
            ),
            CallRow(3, at("2026-10-13T05:00:00Z"), "y", latencyMs = 3),
            // at until: outside the half open window
            CallRow(
                4,
                at("2026-10-13T12:00:00.000Z"),
                "x",
                "get",
                "mcp",
                "h1",
                "subagent",
                latencyMs = 5000,
                responseBytes = 777,
                tokens = 190,
                rootId = r1
            )
        )
    }

    // ------------------------------------------------------------------ S8 / S9 the stitched totals

    @Test
    fun `S8 the daily part and the raw tail are summed per key in one row each, the raw copy of a folded day is not counted again`(): Unit =
        runBlocking {
            seedStitched()

            val page = daily(query())

            assertEquals(listOf("x", "y"), page.keys(), "calls descending: 12 then 2")
            assertEquals(2, page.totalGroups)
            assertEquals(
                listOf(12L, 2L, 140L, 35L, 231L, 19L, 51L, 5L, 3L),
                page.rows[0].longs(),
                "x: 4 + 2 + 5 daily calls and 1 raw; 231 / 12 = 19.25"
            )
            assertEquals(
                listOf(2L, 0L, 8L, 2L, 5L, 3L, 3L, 0L, 0L),
                page.rows[1].longs(),
                "y: 1 daily + 1 raw; 5 / 2 = 2.5 rounds half up to 3"
            )
        }

    @Test
    fun `S8 a null rawFrom has no daily part and the raw part is the whole window`(): Unit =
        runBlocking {
            seedStitched()

            val page = daily(query(), rawFrom = null)

            assertEquals(listOf("x", "y"), page.keys())
            assertEquals(
                listOf(2L, 0L, 4040L, 1010L, 1050L, 525L, 999L, 1L, 0L),
                page.rows[0].longs(),
                "x: the raw rows at 12th 23:59:59.999 and at F; the daily rows are not read"
            )
            assertEquals(listOf(1L, 0L, 0L, 0L, 3L, 3L, 3L, 0L, 0L), page.rows[1].longs())
        }

    @Test
    fun `S9 a row one millisecond before F is counted once through the daily part and a row at F once through the raw part`(): Unit =
        runBlocking {
            UsageFoldKit.insertDaily(jdbc, UsageFoldKit.dailyRow("2026-10-12", null, "edge", calls = 1, latencySum = 1, latencyMax = 1))
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-12T23:59:59.999Z"), "edge", latencyMs = 1),
                CallRow(2, at("2026-10-13T00:00:00.000Z"), "edge", latencyMs = 4)
            )

            val page = daily(query(since = at("2026-10-12T00:00:00Z")))

            assertEquals(
                listOf(2L, 0L, 0L, 0L, 5L, 3L, 4L, 0L, 0L),
                page.rows.single().longs(),
                "1 folded call + 1 raw call at F; 5 / 2 rounds up"
            )
        }

    @Test
    fun `S9 a window that ends exactly at F reads only the daily part, one that starts at F only the raw part`(): Unit =
        runBlocking {
            UsageFoldKit.insertDaily(jdbc, UsageFoldKit.dailyRow("2026-10-12", null, "edge", calls = 1, latencySum = 1, latencyMax = 1))
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, at("2026-10-12T23:59:59.999Z"), "edge", latencyMs = 1000),
                CallRow(2, at("2026-10-13T00:00:00.000Z"), "edge", latencyMs = 4)
            )

            val upToF = daily(query(since = at("2026-10-12T00:00:00Z"), until = f))
            assertEquals(
                listOf(1L, 0L, 0L, 0L, 1L, 1L, 1L, 0L, 0L),
                upToF.rows.single().longs(),
                "the daily row only: the raw row at F is outside [since, F)"
            )

            val fromF = daily(query(since = f))
            assertEquals(
                listOf(1L, 0L, 0L, 0L, 4L, 4L, 4L, 0L, 0L),
                fromF.rows.single().longs(),
                "the raw row at F only: the 12th is before the window"
            )
        }

    @Test
    fun `S9 F set with an empty usage_daily answers the raw tail only, nothing is invented for the folded days`(): Unit =
        runBlocking {
            seedStitched()
            java.sql.DriverManager
                .getConnection(jdbc)
                .use { c -> c.createStatement().use { it.executeUpdate("DELETE FROM usage_daily") } }

            val page = daily(query())

            assertEquals(listOf("x", "y"), page.keys())
            assertEquals(
                listOf(1L, 1L),
                page.rows.map { it.calls },
                "x: the row at F, y: the row at 05:00; the 999 ms row before F is not read"
            )
            assertEquals(51L, page.rows[0].maxLatencyMs)
        }

    // ------------------------------------------------------------------ D8 the day window

    @Test
    fun `S10 the daily part includes the day of since and excludes the day of until`(): Unit =
        runBlocking {
            seedStitched()

            val untilThird = daily(query(until = at("2026-10-03T00:00:00Z")))
            assertEquals(listOf("x", "y"), untilThird.keys())
            assertEquals(
                listOf(6L, 2L, 100L, 25L, 130L, 22L, 40L, 4L, 1L),
                untilThird.rows[0].longs(),
                "days 10-01 and 10-02; no raw part, the window ends before F"
            )
            assertEquals(listOf(1L, 0L, 8L, 2L, 2L, 2L, 2L, 0L, 0L), untilThird.rows[1].longs())

            val untilSecond = daily(query(until = at("2026-10-02T00:00:00Z")))
            assertEquals(listOf("x"), untilSecond.keys(), "the 2nd is the day of until: its rows (x error group, y) are excluded")
            assertEquals(listOf(4L, 0L, 100L, 25L, 100L, 25L, 40L, 1L, 0L), untilSecond.rows.single().longs())

            val sinceSecond = daily(query(since = at("2026-10-02T00:00:00Z")))
            assertEquals(
                listOf(8L, 2L, 40L, 10L, 131L, 16L, 51L, 4L, 3L),
                sinceSecond.rows[0].longs(),
                "x: the 1st (4 calls) is the day before since and is excluded"
            )
            assertEquals(listOf(2L, 0L, 8L, 2L, 5L, 3L, 3L, 0L, 0L), sinceSecond.rows[1].longs())
        }

    // ------------------------------------------------------------------ dimensions and sentinels

    @Test
    fun `S14 absent dimensions read back as a NULL key in both parts and merge into one NULL group`(): Unit =
        runBlocking {
            seedStitched()

            assertEquals(listOf("get", null), daily(query(groupBy = UsageDimension.OPERATION)).keys())
            assertEquals(
                listOf(12L, 2L),
                daily(query(groupBy = UsageDimension.OPERATION)).rows.map {
                    it.calls
                },
                "get: 12; NULL: the sentinel daily row and the NULL raw row"
            )
            assertEquals(listOf("h1", null), daily(query(groupBy = UsageDimension.HOST)).keys())
            assertEquals(listOf(12L, 2L), daily(query(groupBy = UsageDimension.HOST)).rows.map { it.calls })
            assertEquals(listOf("subagent", null), daily(query(groupBy = UsageDimension.PRINCIPAL_KIND)).keys())
            assertEquals(listOf(12L, 2L), daily(query(groupBy = UsageDimension.PRINCIPAL_KIND)).rows.map { it.calls })
            val errors = daily(query(groupBy = UsageDimension.ERROR_CODE))
            assertEquals(listOf(null, "timeout"), errors.keys(), "the ok rows (daily '' and raw NULL) form the NULL group")
            assertEquals(listOf(12L, 2L), errors.rows.map { it.calls })
            val surface = daily(query(groupBy = UsageDimension.SURFACE))
            assertEquals(listOf("mcp"), surface.keys())
            assertEquals(14L, surface.rows.single().calls)
        }

    @Test
    fun `S9 the day dimension keys the daily part by its day column and the raw part by the UTC date, a folded day is not split`(): Unit =
        runBlocking {
            seedStitched()

            val page = daily(query(groupBy = UsageDimension.DAY))

            assertEquals(
                listOf("2026-10-12", "2026-10-01", "2026-10-02", "2026-10-13"),
                page.keys(),
                "calls 5, 4, 3, 2; the raw copy on the 12th is not counted"
            )
            assertEquals(listOf(5L, 4L, 3L, 2L), page.rows.map { it.calls })
        }

    // ------------------------------------------------------------------ S14 scope and rootId on both parts

    @Test
    fun `S14 a root scope filters both parts and never matches the NULL-root sentinel or a NULL-root raw row`(): Unit =
        runBlocking {
            seedStitched()

            val onlyR1 = daily(query(scopeRootIds = setOf(r1)))
            assertEquals(listOf("x"), onlyR1.keys(), "y has only NULL-root rows (daily sentinel and raw NULL)")
            assertEquals(1, onlyR1.totalGroups)
            assertEquals(
                listOf(7L, 2L, 140L, 35L, 181L, 26L, 51L, 5L, 1L),
                onlyR1.rows.single().longs(),
                "d1 + d2 (R1) and the raw row at F (R1); d4 is R2"
            )

            val both = daily(query(scopeRootIds = setOf(r1, r2)))
            assertEquals(listOf("x"), both.keys())
            assertEquals(12L, both.rows.single().calls)
            assertEquals(1, both.totalGroups)

            val open = daily(query(scopeRootIds = null))
            assertEquals(listOf("x", "y"), open.keys(), "control: an unrestricted read sees the NULL-root group")
        }

    @Test
    fun `S14 an empty root set sees nothing in either part`(): Unit =
        runBlocking {
            seedStitched()

            val page = daily(query(scopeRootIds = emptySet()))

            assertTrue(page.rows.isEmpty())
            assertEquals(0, page.totalGroups)
        }

    @Test
    fun `S14 the rootId filter applies to both parts and on top of the scope`(): Unit =
        runBlocking {
            seedStitched()

            val r2Only = daily(query(rootId = r2))
            assertEquals(
                listOf(5L, 0L, 0L, 0L, 50L, 10L, 25L, 0L, 2L),
                r2Only.rows.single().longs(),
                "the daily row of the 12th only: no raw R2 row exists"
            )
            assertEquals(1, r2Only.totalGroups)

            val r1OutsideScope = daily(query(scopeRootIds = setOf(r2), rootId = r1))
            assertTrue(r1OutsideScope.rows.isEmpty(), "R1 is outside the scope {R2}")
            assertEquals(0, r1OutsideScope.totalGroups)
        }

    // ------------------------------------------------------------------ paging

    @Test
    fun `S8 limit and offset slice the stitched groups and totalGroups stays the full count`(): Unit =
        runBlocking {
            seedStitched()

            val first = daily(query(limit = 1, offset = 0))
            val second = daily(query(limit = 1, offset = 1))
            val beyond = daily(query(limit = 1, offset = 5))

            assertEquals(listOf("x"), first.keys())
            assertEquals(listOf("y"), second.keys())
            assertEquals(2, first.totalGroups)
            assertEquals(2, second.totalGroups)
            assertTrue(beyond.rows.isEmpty())
            assertEquals(2, beyond.totalGroups)
        }

    @Test
    fun `S8 an empty database answers no rows and zero groups`(): Unit =
        runBlocking {
            val page = daily(query())

            assertTrue(page.rows.isEmpty())
            assertEquals(0, page.totalGroups)
        }
}
