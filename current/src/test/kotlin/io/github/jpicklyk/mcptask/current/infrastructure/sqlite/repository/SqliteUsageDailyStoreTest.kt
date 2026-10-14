package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.UsageDailyStore
import io.github.jpicklyk.mcptask.current.application.port.UsageFoldWatermark
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.RetentionRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One `call_log` row a fold test seeds through raw SQL (outcome follows [failed], which follows [error] unless a test says otherwise). */
internal data class CallRow(
    val n: Int,
    val at: Instant,
    val tool: String = "sample_tool",
    val operation: String? = null,
    val surface: String = "mcp",
    val host: String? = null,
    val principalKind: String? = null,
    val error: String? = null,
    val failed: Boolean = error != null,
    val attempts: Int = 1,
    val latencyMs: Long = 1,
    val requestBytes: Long? = null,
    val responseBytes: Long? = null,
    val tokens: Long? = null,
    val replayed: Boolean = false,
    val rootId: UUID? = null
)

/** One `usage_daily` row as read back through plain SQL: [root] is the upper-case hex of `root_id` ("" for the zero-length sentinel). */
internal data class DailyRow(
    val day: String,
    val root: String,
    val tool: String,
    val operation: String,
    val surface: String,
    val host: String,
    val principalKind: String,
    val outcome: String,
    val errorCode: String,
    val calls: Long,
    val errors: Long,
    val requestBytes: Long,
    val responseBytes: Long,
    val tokens: Long,
    val latencySum: Long,
    val latencyMax: Long,
    val retries: Long,
    val replays: Long
)

/** The single `usage_fold` row as read back through plain SQL. */
internal data class FoldRow(
    val singleton: Long,
    val foldedThrough: String,
    val foldedAt: String
)

private val DAILY_ORDER =
    compareBy<DailyRow>(
        { it.day },
        { it.root },
        { it.tool },
        { it.operation },
        { it.surface },
        { it.host },
        { it.principalKind },
        { it.outcome },
        { it.errorCode }
    )

/** The rows in key order, so a comparison does not depend on the order SQLite returns them in. */
internal fun List<DailyRow>.canonical(): List<DailyRow> = sortedWith(DAILY_ORDER)

/**
 * Raw JDBC fixtures and reads for the usage_daily fold tests (item 515ec0a4, test-author seat). Everything talks to `call_log`,
 * `usage_daily` and `usage_fold` through plain SQL, so an expected state is an input the test chose and a read-back is not
 * routed through the code under test. Dimension sentinels follow the frozen migration-assessment and task-scope D1: `''` for an
 * absent text dimension and the zero-length blob for an absent root.
 */
internal object UsageFoldKit {
    val R1: UUID = ReportKit.R1
    val R2: UUID = ReportKit.R2

    /** The fold watermark instant at [ReportKit.NOW] (2026-10-13T12:00Z, grace 1h): the midnight after the last completed day. */
    val F: Instant = Instant.parse("2026-10-13T00:00:00Z")

    fun at(iso: String): Instant = Instant.parse(iso)

    fun hex(id: UUID?): String = id?.let { RetentionRows.idBytes(it).joinToString("") { b -> "%02X".format(b) } } ?: ""

    private fun PreparedStatement.str(
        i: Int,
        v: String?
    ) = if (v == null) setNull(i, Types.VARCHAR) else setString(i, v)

    private fun PreparedStatement.lng(
        i: Int,
        v: Long?
    ) = if (v == null) setNull(i, Types.INTEGER) else setLong(i, v)

    fun insertCalls(
        jdbcUrl: String,
        vararg rows: CallRow
    ) {
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.autoCommit = false
            c
                .prepareStatement(
                    "INSERT INTO call_log (req_id, at, surface, tool, operation, host, principal_kind, outcome, error_code, " +
                        "attempts, latency_ms, request_bytes, response_bytes, response_tokens_est, replayed, root_id) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                ).use { ps ->
                    for (r in rows) {
                        ps.setString(1, testReqId(r.n))
                        ps.setString(2, RetentionRows.stamp(r.at))
                        ps.setString(3, r.surface)
                        ps.setString(4, r.tool)
                        ps.str(5, r.operation)
                        ps.str(6, r.host)
                        ps.str(7, r.principalKind)
                        ps.setString(8, if (r.failed) "error" else "ok")
                        ps.str(9, r.error)
                        ps.setInt(10, r.attempts)
                        ps.setLong(11, r.latencyMs)
                        ps.lng(12, r.requestBytes)
                        ps.lng(13, r.responseBytes)
                        ps.lng(14, r.tokens)
                        ps.setInt(15, if (r.replayed) 1 else 0)
                        if (r.rootId == null) ps.setNull(16, Types.BLOB) else ps.setBytes(16, RetentionRows.idBytes(r.rootId))
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
            c.commit()
        }
    }

    /** An expected or seeded `usage_daily` row: absent dimensions take the D1 sentinels, every metric defaults to 0. */
    @Suppress("LongParameterList")
    fun dailyRow(
        day: String,
        root: UUID?,
        tool: String,
        operation: String? = null,
        surface: String = "mcp",
        host: String? = null,
        principalKind: String? = null,
        outcome: String = "ok",
        errorCode: String? = null,
        calls: Long,
        errors: Long = 0,
        requestBytes: Long = 0,
        responseBytes: Long = 0,
        tokens: Long = 0,
        latencySum: Long = 0,
        latencyMax: Long = 0,
        retries: Long = 0,
        replays: Long = 0
    ) = DailyRow(
        day,
        hex(root),
        tool,
        operation ?: "",
        surface,
        host ?: "",
        principalKind ?: "",
        outcome,
        errorCode ?: "",
        calls,
        errors,
        requestBytes,
        responseBytes,
        tokens,
        latencySum,
        latencyMax,
        retries,
        replays
    )

    fun insertDaily(
        jdbcUrl: String,
        vararg rows: DailyRow
    ) {
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.autoCommit = false
            for (r in rows) {
                // A zero-length blob is written as the SQL literal x'': a driver may bind an empty byte array as NULL.
                val rootSql = if (r.root.isEmpty()) "x''" else "?"
                c
                    .prepareStatement(
                        "INSERT INTO usage_daily (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code, " +
                            "calls, errors, request_bytes, response_bytes, response_tokens_est, latency_ms_sum, latency_ms_max, " +
                            "retries, replays) " +
                            "VALUES (?,$rootSql,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    ).use { ps ->
                        var i = 1
                        ps.setString(i++, r.day)
                        if (r.root.isNotEmpty()) {
                            ps.setBytes(
                                i++,
                                ByteArray(r.root.length / 2) {
                                    r.root
                                        .substring(it * 2, it * 2 + 2)
                                        .toInt(16)
                                        .toByte()
                                }
                            )
                        }
                        ps.setString(i++, r.tool)
                        ps.setString(i++, r.operation)
                        ps.setString(i++, r.surface)
                        ps.setString(i++, r.host)
                        ps.setString(i++, r.principalKind)
                        ps.setString(i++, r.outcome)
                        ps.setString(i++, r.errorCode)
                        ps.setLong(i++, r.calls)
                        ps.setLong(i++, r.errors)
                        ps.setLong(i++, r.requestBytes)
                        ps.setLong(i++, r.responseBytes)
                        ps.setLong(i++, r.tokens)
                        ps.setLong(i++, r.latencySum)
                        ps.setLong(i++, r.latencyMax)
                        ps.setLong(i++, r.retries)
                        ps.setLong(i++, r.replays)
                        ps.executeUpdate()
                    }
            }
            c.commit()
        }
    }

    fun readDaily(jdbcUrl: String): List<DailyRow> =
        DriverManager
            .getConnection(jdbcUrl)
            .use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT day, hex(root_id), tool, operation, surface, host, principal_kind, outcome, error_code, " +
                                "calls, errors, request_bytes, response_bytes, response_tokens_est, latency_ms_sum, latency_ms_max, " +
                                "retries, replays " +
                                "FROM usage_daily"
                        ).use { rs ->
                            buildList {
                                while (rs.next()) {
                                    add(
                                        DailyRow(
                                            rs.getString(1),
                                            rs.getString(2),
                                            rs.getString(3),
                                            rs.getString(4),
                                            rs.getString(5),
                                            rs.getString(6),
                                            rs.getString(7),
                                            rs.getString(8),
                                            rs.getString(9),
                                            rs.getLong(10),
                                            rs.getLong(11),
                                            rs.getLong(12),
                                            rs.getLong(13),
                                            rs.getLong(14),
                                            rs.getLong(15),
                                            rs.getLong(16),
                                            rs.getLong(17),
                                            rs.getLong(18)
                                        )
                                    )
                                }
                            }
                        }
                }
            }.canonical()

    fun readFold(jdbcUrl: String): List<FoldRow> =
        DriverManager.getConnection(jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT singleton, folded_through, folded_at FROM usage_fold").use { rs ->
                    buildList { while (rs.next()) add(FoldRow(rs.getLong(1), rs.getString(2), rs.getString(3))) }
                }
            }
        }

    /**
     * Rows of UTC day 2026-10-10. Six rows, five groups. With [withNeighbors], one extra row on each neighbouring day sits at the
     * millisecond just outside the day (2026-10-09T23:59:59.999Z and 2026-10-11T00:00:00.000Z), so a day window that is off by one
     * millisecond or one day changes a sum.
     */
    fun day10Rows(withNeighbors: Boolean): List<CallRow> =
        buildList {
            add(
                CallRow(
                    1,
                    at("2026-10-10T00:00:00.000Z"),
                    "t_a",
                    "get",
                    "mcp",
                    "h1",
                    "subagent",
                    latencyMs = 10,
                    requestBytes = 100,
                    responseBytes = 400,
                    tokens = 100,
                    rootId = R1
                )
            )
            add(
                CallRow(
                    2,
                    at("2026-10-10T23:59:59.999Z"),
                    "t_a",
                    "get",
                    "mcp",
                    "h1",
                    "subagent",
                    attempts = 3,
                    latencyMs = 30,
                    requestBytes = 50,
                    replayed = true,
                    rootId = R1
                )
            )
            add(
                CallRow(
                    3,
                    at("2026-10-10T08:00:00Z"),
                    "t_a",
                    "get",
                    "mcp",
                    "h1",
                    "subagent",
                    error = "not_found",
                    attempts = 2,
                    latencyMs = 5,
                    rootId = R1
                )
            )
            add(CallRow(4, at("2026-10-10T09:00:00Z"), "t_b", surface = "rest", latencyMs = 7))
            add(CallRow(5, at("2026-10-10T10:00:00Z"), "t_b", surface = "rest", failed = true, latencyMs = 0))
            add(
                CallRow(
                    6,
                    at("2026-10-10T11:00:00Z"),
                    "t_a",
                    "get",
                    "mcp",
                    "h1",
                    "subagent",
                    latencyMs = 4,
                    requestBytes = 8,
                    responseBytes = 16,
                    tokens = 4,
                    rootId = R2
                )
            )
            if (withNeighbors) {
                add(
                    CallRow(
                        7,
                        at("2026-10-09T23:59:59.999Z"),
                        "t_a",
                        "get",
                        "mcp",
                        "h1",
                        "subagent",
                        latencyMs = 1000,
                        responseBytes = 4000,
                        tokens = 1000,
                        rootId = R1
                    )
                )
                add(
                    CallRow(
                        8,
                        at("2026-10-11T00:00:00.000Z"),
                        "t_a",
                        "get",
                        "mcp",
                        "h1",
                        "subagent",
                        latencyMs = 2000,
                        responseBytes = 9999,
                        tokens = 2499,
                        rootId = R1
                    )
                )
            }
        }

    /** Hand sums of [day10Rows] (the neighbours excluded): group by group, NULL timings and bytes counted as 0. */
    fun day10Expected(): List<DailyRow> =
        listOf(
            dailyRow(
                "2026-10-10",
                R1,
                "t_a",
                "get",
                "mcp",
                "h1",
                "subagent",
                calls = 2,
                requestBytes = 150,
                responseBytes = 400,
                tokens = 100,
                latencySum = 40,
                latencyMax = 30,
                retries = 2,
                replays = 1
            ),
            dailyRow(
                "2026-10-10",
                R1,
                "t_a",
                "get",
                "mcp",
                "h1",
                "subagent",
                outcome = "error",
                errorCode = "not_found",
                calls = 1,
                errors = 1,
                latencySum = 5,
                latencyMax = 5,
                retries = 1
            ),
            dailyRow("2026-10-10", null, "t_b", surface = "rest", calls = 1, latencySum = 7, latencyMax = 7),
            dailyRow("2026-10-10", null, "t_b", surface = "rest", outcome = "error", calls = 1, errors = 1),
            dailyRow(
                "2026-10-10",
                R2,
                "t_a",
                "get",
                "mcp",
                "h1",
                "subagent",
                calls = 1,
                requestBytes = 8,
                responseBytes = 16,
                tokens = 4,
                latencySum = 4,
                latencyMax = 4
            )
        ).canonical()
}

/**
 * Independent tests (test-author seat, item 515ec0a4, R13) for the [UsageDailyStore] port over the real SQLite database:
 * the store-level half of scenarios S1, S2, S5 and S6 of the frozen test-plan (NEW-SURFACE).
 *
 * Oracles (frozen task-scope D1 to D4 and AC1 to AC5, migration-assessment section 1, R9 AC1): `foldDay` is ONE aggregate
 * upsert of the day's `call_log` rows (UTC day, half open at midnight to the millisecond) plus the `usage_fold` upsert in the
 * same write unit; calls = COUNT, errors = rows with outcome `error`, request/response bytes and token estimates SUM with NULL as 0,
 * latency sum and MAX, retries = SUM(attempts - 1), replays = COUNT(replayed); absent dimensions are stored as `''` and the
 * zero-length blob; the upsert REPLACES every metric and never adds, so re-folding is idempotent; the watermark row holds the
 * folded day (`yyyy-MM-dd`) and the fold instant (23 characters). Every expected number is a hand sum of the rows seeded next to
 * it (see [UsageFoldKit.day10Rows] and [UsageFoldKit.day10Expected]).
 *
 * Negative-assertion conditions: the neighbouring-day rows at 23:59:59.999 and 00:00:00.000 each carry a metric (1000 / 2000 ms
 * latency, large byte counts) that would change a day-10 sum if the window were widened by a millisecond; the late-row test seeds
 * a row that WOULD double the calls if a re-fold added instead of replacing.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: raw rows keep the call_log CHECKs (8-character req ids from [testReqId], outcome
 * `ok` or `error`, attempts >= 1, latency >= 0, 23-character timestamps from [RetentionRows.stamp]); a seeded `error` outcome row
 * is the only row with a NULL error code, which the schema allows.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteUsageDailyStoreTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val jdbc get() = db.jdbcUrl
    private val foldedAt = Instant.parse("2026-10-13T12:00:00.123Z")

    private suspend fun <T> inWrite(block: suspend (UsageDailyStore) -> T): T =
        when (val outcome = db.unitOfWork().write("test.usageDaily") { Outcome.Ok(block(stores.usageDailyStore())) }) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("unit failed: ${outcome.error.message}")
        }

    private suspend fun <T> inRead(block: suspend (UsageDailyStore) -> T): T =
        db.unitOfWork().read { block(db.repositoryProvider().usageDailyStore()) }

    private suspend fun fold(day: String): Int = inWrite { it.foldDay(LocalDate.parse(day), foldedAt) }

    // ------------------------------------------------------------------ S5 reads on an empty database

    @Test
    fun `S5 a fresh database has no watermark, no earliest call day and no earliest folded day`(): Unit =
        runBlocking {
            assertNull(inRead { it.watermark() })
            assertNull(inRead { it.earliestCallLogDay() })
            assertNull(inRead { it.earliestFoldedDay() })
        }

    @Test
    fun `S5 earliestCallLogDay is the UTC date of the oldest row, to the millisecond at midnight`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, CallRow(1, UsageFoldKit.at("2026-10-07T00:00:00.000Z")))
            assertEquals(LocalDate.parse("2026-10-07"), inRead { it.earliestCallLogDay() })

            UsageFoldKit.insertCalls(jdbc, CallRow(2, UsageFoldKit.at("2026-10-05T23:59:59.999Z")))
            assertEquals(LocalDate.parse("2026-10-05"), inRead { it.earliestCallLogDay() }, "23:59:59.999Z still belongs to the 5th")

            UsageFoldKit.insertCalls(jdbc, CallRow(3, UsageFoldKit.at("2026-10-05T00:00:00.000Z")))
            assertEquals(LocalDate.parse("2026-10-05"), inRead { it.earliestCallLogDay() })

            UsageFoldKit.insertCalls(jdbc, CallRow(4, UsageFoldKit.at("2026-10-04T23:59:59.999Z")))
            assertEquals(
                LocalDate.parse("2026-10-04"),
                inRead { it.earliestCallLogDay() },
                "a row a millisecond before midnight is the 4th"
            )
        }

    // ------------------------------------------------------------------ S1 the day aggregate

    @Test
    fun `S1 foldDay groups the day's rows, sums each metric, maps absent dimensions to sentinels and returns the group count`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = true).toTypedArray())

            val groups = fold("2026-10-10")

            assertEquals(5, groups)
            assertEquals(UsageFoldKit.day10Expected(), UsageFoldKit.readDaily(jdbc))
        }

    @Test
    fun `S1 each neighbouring day folds only its own row, and the watermark follows the last folded day`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = true).toTypedArray())
            fold("2026-10-10")

            assertEquals(1, fold("2026-10-09"))
            assertEquals(1, fold("2026-10-11"))

            val byDay = UsageFoldKit.readDaily(jdbc).groupBy { it.day }
            assertEquals(setOf("2026-10-09", "2026-10-10", "2026-10-11"), byDay.keys)
            assertEquals(
                listOf(
                    UsageFoldKit.dailyRow(
                        "2026-10-09",
                        UsageFoldKit.R1,
                        "t_a",
                        "get",
                        "mcp",
                        "h1",
                        "subagent",
                        calls = 1,
                        responseBytes = 4000,
                        tokens = 1000,
                        latencySum = 1000,
                        latencyMax = 1000
                    )
                ),
                byDay.getValue("2026-10-09")
            )
            assertEquals(
                listOf(
                    UsageFoldKit.dailyRow(
                        "2026-10-11",
                        UsageFoldKit.R1,
                        "t_a",
                        "get",
                        "mcp",
                        "h1",
                        "subagent",
                        calls = 1,
                        responseBytes = 9999,
                        tokens = 2499,
                        latencySum = 2000,
                        latencyMax = 2000
                    )
                ),
                byDay.getValue("2026-10-11")
            )
            assertEquals(5, byDay.getValue("2026-10-10").size, "folding the neighbours leaves the 10th untouched")
            assertEquals(listOf(FoldRow(1, "2026-10-11", "2026-10-13 12:00:00.123")), UsageFoldKit.readFold(jdbc))
        }

    @Test
    fun `S1 foldDay writes the watermark row with the folded day and the fold instant, and watermark reads it back`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())

            fold("2026-10-10")

            assertEquals(listOf(FoldRow(1, "2026-10-10", "2026-10-13 12:00:00.123")), UsageFoldKit.readFold(jdbc))
            assertEquals(UsageFoldWatermark(LocalDate.parse("2026-10-10"), foldedAt), inRead { it.watermark() })
        }

    @Test
    fun `S1 a day without rows upserts nothing, answers zero and still advances the watermark`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, CallRow(1, UsageFoldKit.at("2026-10-05T10:00:00Z"), "only_tool", latencyMs = 3))

            assertEquals(0, fold("2026-10-07"))

            assertEquals(emptyList(), UsageFoldKit.readDaily(jdbc))
            assertEquals(listOf(FoldRow(1, "2026-10-07", "2026-10-13 12:00:00.123")), UsageFoldKit.readFold(jdbc))
            assertNull(inRead { it.earliestFoldedDay() }, "no usage_daily row exists, so there is no earliest folded day")
        }

    @Test
    fun `S1 foldDay leaves call_log untouched`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = true).toTypedArray())
            val before = RetentionRows.callLogIds(jdbc)

            fold("2026-10-10")

            assertEquals(8, before.size, "control: the fixture holds eight rows")
            assertEquals(before, RetentionRows.callLogIds(jdbc))
        }

    @Test
    fun `S1 earliestFoldedDay is the minimum day of usage_daily`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = true).toTypedArray())
            fold("2026-10-11")
            assertEquals(LocalDate.parse("2026-10-11"), inRead { it.earliestFoldedDay() })

            fold("2026-10-10")
            assertEquals(LocalDate.parse("2026-10-10"), inRead { it.earliestFoldedDay() })

            fold("2026-10-09")
            assertEquals(LocalDate.parse("2026-10-09"), inRead { it.earliestFoldedDay() })
        }

    @Test
    fun `S1 probe an absent root and the all-zero root are two distinct groups of the same dimensions`(): Unit =
        runBlocking {
            val zero = UUID(0L, 0L)
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(1, UsageFoldKit.at("2026-10-10T01:00:00Z"), "same_tool", rootId = null, latencyMs = 2),
                CallRow(2, UsageFoldKit.at("2026-10-10T02:00:00Z"), "same_tool", rootId = zero, latencyMs = 5),
                CallRow(3, UsageFoldKit.at("2026-10-10T03:00:00Z"), "same_tool", rootId = zero, latencyMs = 6)
            )

            assertEquals(2, fold("2026-10-10"))

            assertEquals(
                listOf(
                    UsageFoldKit.dailyRow("2026-10-10", null, "same_tool", calls = 1, latencySum = 2, latencyMax = 2),
                    UsageFoldKit.dailyRow("2026-10-10", zero, "same_tool", calls = 2, latencySum = 11, latencyMax = 6)
                ).canonical(),
                UsageFoldKit.readDaily(jdbc)
            )
            assertEquals(listOf("", "00000000000000000000000000000000"), UsageFoldKit.readDaily(jdbc).map { it.root })
        }

    @Test
    fun `S1 probe a thousand groups in one day all fold, one row each`(): Unit =
        runBlocking {
            val rows =
                (1..1000).map {
                    CallRow(
                        it,
                        UsageFoldKit.at("2026-10-10T12:00:00Z"),
                        "tool_%04d".format(it),
                        latencyMs = it.toLong()
                    )
                }
            UsageFoldKit.insertCalls(jdbc, *rows.toTypedArray())

            assertEquals(1000, fold("2026-10-10"))

            val folded = UsageFoldKit.readDaily(jdbc)
            assertEquals(1000, folded.size)
            assertEquals(1000L, folded.sumOf { it.calls })
            assertEquals((1..1000).sumOf { it.toLong() }, folded.sumOf { it.latencySum })
        }

    // ------------------------------------------------------------------ S2 idempotency, S6 late rows

    @Test
    fun `S2 folding the same day twice leaves usage_daily and the watermark identical and answers the same group count`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = true).toTypedArray())
            val first = fold("2026-10-10")
            val dailyAfterFirst = UsageFoldKit.readDaily(jdbc)
            val foldAfterFirst = UsageFoldKit.readFold(jdbc)

            val second = fold("2026-10-10")

            assertEquals(5, first)
            assertEquals(first, second)
            assertEquals(dailyAfterFirst, UsageFoldKit.readDaily(jdbc))
            assertEquals(
                UsageFoldKit.day10Expected(),
                UsageFoldKit.readDaily(jdbc),
                "control: the unchanged state is the hand-summed one, not a doubled one"
            )
            assertEquals(foldAfterFirst, UsageFoldKit.readFold(jdbc))
        }

    @Test
    fun `S2 a re-fold replaces the metrics of a group from the current raw rows, it never adds to the stored ones`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())
            fold("2026-10-10")
            // a late row of the first group (R1, t_a, get, mcp, h1, subagent, ok): the group becomes 3 calls, not 2 + 3
            UsageFoldKit.insertCalls(
                jdbc,
                CallRow(
                    9,
                    UsageFoldKit.at("2026-10-10T12:00:00Z"),
                    "t_a",
                    "get",
                    "mcp",
                    "h1",
                    "subagent",
                    latencyMs = 50,
                    responseBytes = 8,
                    tokens = 2,
                    rootId = UsageFoldKit.R1
                )
            )

            fold("2026-10-10")

            val group =
                UsageFoldKit.readDaily(jdbc).single {
                    it.root == UsageFoldKit.hex(UsageFoldKit.R1) && it.outcome == "ok" && it.tool == "t_a"
                }
            assertEquals(3L, group.calls, "2 original rows + 1 late row, recounted")
            assertEquals(408L, group.responseBytes)
            assertEquals(102L, group.tokens)
            assertEquals(90L, group.latencySum, "10 + 30 + 50")
            assertEquals(50L, group.latencyMax)
            assertEquals(2L, group.retries)
            assertEquals(1L, group.replays)
            assertEquals(5, UsageFoldKit.readDaily(jdbc).size, "no group appears twice")
        }

    @Test
    fun `S2 foldDay joins the ambient unit, a throw after it rolls back both the groups and the watermark`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, *UsageFoldKit.day10Rows(withNeighbors = false).toTypedArray())

            assertFailsWith<IllegalStateException> {
                db.unitOfWork().write<Int>("test.usageDaily.rollback") {
                    val groups = stores.usageDailyStore().foldDay(LocalDate.parse("2026-10-10"), foldedAt)
                    assertTrue(groups > 0, "control: the fold did write inside the unit")
                    throw IllegalStateException("simulated failure after the fold")
                }
            }

            assertEquals(emptyList(), UsageFoldKit.readDaily(jdbc))
            assertEquals(emptyList(), UsageFoldKit.readFold(jdbc))
            assertNull(inRead { it.watermark() })
        }

    @Test
    fun `S6 the watermark stores the fold instant to the millisecond`(): Unit =
        runBlocking {
            UsageFoldKit.insertCalls(jdbc, CallRow(1, UsageFoldKit.at("2026-10-10T10:00:00Z")))
            val at = Instant.parse("2026-10-13T01:02:03.004Z")

            inWrite { it.foldDay(LocalDate.parse("2026-10-10"), at) }

            assertEquals(at, inRead { it.watermark() }?.foldedAt)
            assertEquals("2026-10-13 01:02:03.004", UsageFoldKit.readFold(jdbc).single().foldedAt)
        }
}
