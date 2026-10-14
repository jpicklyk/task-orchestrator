package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.inUnit
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.testReqId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared constants and record builders for the report tests (R9, test-author seat). */
internal object ReportKit {
    /** The env clock every report test runs at. */
    val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")

    val R1: UUID = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
    val R2: UUID = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
    val C1: UUID = UUID.fromString("c1c1c1c1-0000-4000-8000-00000000000c")

    /**
     * A call_log record whose dependent fields are derived from each other by construction: outcome follows [error] (an
     * `error` row always carries its code, an `ok` row never does) and the token estimate follows [responseBytes] (bytes / 4,
     * absent when the bytes are absent), so a fixture never sets related columns independently.
     */
    fun rec(
        n: Int,
        at: Instant,
        tool: String = "sample_tool",
        operation: String? = null,
        host: String? = null,
        surface: String = CallLogRecord.SURFACE_MCP,
        principalId: String? = null,
        principalKind: String? = null,
        error: String? = null,
        attempts: Int = 1,
        latencyMs: Long = 1,
        responseBytes: Long? = null,
        replayed: Boolean = false,
        runId: UUID? = null,
        rootId: UUID? = null
    ): CallLogRecord =
        CallLogRecord(
            reqId = testReqId(n),
            at = at,
            principalId = principalId,
            principalKind = principalKind,
            host = host,
            runId = runId,
            surface = surface,
            tool = tool,
            operation = operation,
            outcome = if (error == null) CallLogRecord.OUTCOME_OK else CallLogRecord.OUTCOME_ERROR,
            errorCode = error,
            attempts = attempts,
            latencyMs = latencyMs,
            responseBytes = responseBytes,
            responseTokensEst = responseBytes?.div(4),
            tokenMethod = responseBytes?.let { "bytes/4" },
            replayed = replayed,
            rootId = rootId
        )
}

/** A fresh SQLite database plus the helpers every report test needs: seeding, a service on the env clock, a call context. */
internal class ReportFixture(
    val database: SqliteTestDatabase,
    val clock: SettableClock
) {
    val now: Instant get() = clock.now()

    fun service(): ReportService = ReportService(database.unitOfWork(), clock)

    fun ctx(
        scope: AccessScope = AccessScope.unrestricted(),
        principalsVisible: Boolean = true
    ): CallContext =
        CallContext(
            reqId = "report-t",
            surface = CallLogRecord.SURFACE_MCP,
            principal = null,
            scope = scope,
            principalsVisible = principalsVisible
        )

    /** Appends [records] to call_log inside one write unit; returns how many rows were inserted. */
    suspend fun seed(vararg records: CallLogRecord): Int {
        var inserted = 0
        database.unitOfWork().inUnit("report.seed") {
            inserted = database.repositoryProvider().callLogStore().append(records.toList())
        }
        return inserted
    }

    /** A depth-0 root. */
    suspend fun root(
        id: UUID,
        title: String,
        tags: String? = null
    ) {
        database.repositoryProvider().workItemRepository().create(WorkItem(id = id, title = title, tags = tags))
    }

    /** A depth-1 child of [parent]; its root is the parent. */
    suspend fun child(
        id: UUID,
        parent: UUID,
        title: String
    ) {
        database
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(id = id, title = title, parentId = parent, depth = 1, rootId = parent))
    }

    suspend fun usage(
        request: ReportUsageRequest = ReportUsageRequest(),
        ctx: CallContext = ctx()
    ): Outcome<UsageReport> = service().usage(ctx, request)

    suspend fun ok(
        request: ReportUsageRequest = ReportUsageRequest(),
        ctx: CallContext = ctx()
    ): UsageReport =
        when (val outcome = usage(request, ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok report for $request but got ${outcome.error}")
        }
}

/**
 * Independent tests (test-author seat, item eadaaa4e, R9) for [ReportService] `usage` over the real SQLite database and the
 * real [ReportQueries] port: scenarios S1 to S6 and S8 to S14 of the frozen test-plan (all NEW-SURFACE).
 *
 * Oracles (never read from a run): task-scope acceptance criteria AC1 (metric definitions: errors = rows with outcome `error`,
 * bytes and tokens SUM with NULL as 0, AVG rounded half-up, MAX, retries = SUM(attempts - 1), replays = COUNT(replayed)), AC2
 * (half-open window [since, until), until defaults to the env clock, since to until minus 7 days, at most 31 days, since before
 * until, ISO 8601 with Z or an offset, echoed in UTC), AC3 (groupBy subset, default tool, principal admin only, noteKey / type
 * / trait / resource invalid_request), AC4 (S22: root scope filters before grouping, NULL-root rows never visible to a root
 * scope, tag scope is forbidden{tag} before any read, principal grouping without ADMIN is forbidden{capability, admin}), AC5
 * (rootId resolves through ScopedIds and must be depth 0), AC6 (retainedSince = MIN(call_log.at) over the whole table, absent
 * when empty; truncatedByRetention only when since is before it); the list envelope (U22: total, truncated, next, order calls
 * desc then key asc, a NULL key sorts first among equal calls); `ListPageTest` / `CursorCodecTest` (a bad cursor is
 * invalid_cursor; limit outside 1..200 is invalid_request); `ScopedIdsTest` and `ScopeGuardTest` (restricted scope: an unknown
 * id is forbidden, unrestricted: not_found; ambiguous prefixes list candidates).
 *
 * Negative-assertion conditions: every "absent" assertion has a seeded row that WOULD appear without the rule under test (an
 * R2 or NULL-root row for the root scope, a row at the excluded boundary for the window, a foreign principal for the principal
 * grouping), and each is paired with a control from the same fixture that does return data.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 *
 * Domain invariants satisfied by construction: [ReportKit.rec] derives outcome from the error code and the token estimate from
 * the response bytes; roots are depth 0 with no parent, the child has depth 1 and its root is its parent.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ReportServiceTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2

    private fun ago(d: Duration): Instant = now.minus(d)

    private fun UsageReport.keys(): List<String?> = page.items.map { it.key }

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

    private fun err(outcome: Outcome<UsageReport>) = assertIs<Outcome.Err>(outcome).error

    private fun asRequest(
        since: String? = null,
        until: String? = null,
        groupBy: ReportGroupBy? = null,
        rootId: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ) = ReportUsageRequest(rootId?.let { IdRef(it) }, since, until, groupBy, limit, cursor)

    // ------------------------------------------------------------------ S1 metrics

    @Test
    fun `S1 each metric follows its definition and rows order by calls descending then key ascending`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))
            f.seed(
                // z_tool: 3 calls, 1 error, bytes 100 + NULL + 48, latencies 10 20 31, attempts 1 3 2, one replay
                ReportKit.rec(1, at, "z_tool", latencyMs = 10, responseBytes = 100),
                ReportKit.rec(2, at, "z_tool", error = "gate_blocked", latencyMs = 20, attempts = 3, replayed = true),
                ReportKit.rec(3, at, "z_tool", latencyMs = 31, responseBytes = 48, attempts = 2),
                // b_tool: 2 calls, latencies 10 and 15 (average 12.5 rounds half up to 13)
                ReportKit.rec(4, at, "b_tool", latencyMs = 10, responseBytes = 8),
                ReportKit.rec(5, at, "b_tool", latencyMs = 15, responseBytes = 12),
                // c_tool: 2 calls, both errors, no bytes at all, attempts 2 and 1, one replay
                ReportKit.rec(6, at, "c_tool", error = "timeout", latencyMs = 4, attempts = 2, replayed = true),
                ReportKit.rec(7, at, "c_tool", error = "timeout", latencyMs = 4)
            )

            val report = f.ok(asRequest(since = "2026-10-12T00:00:00Z"))

            assertEquals(
                listOf(
                    M("z_tool", calls = 3, errors = 1, bytes = 148, tokens = 37, avg = 20, max = 31, retries = 3, replays = 1),
                    M("b_tool", calls = 2, errors = 0, bytes = 20, tokens = 5, avg = 13, max = 15, retries = 0, replays = 0),
                    M("c_tool", calls = 2, errors = 2, bytes = 0, tokens = 0, avg = 4, max = 4, retries = 1, replays = 1)
                ),
                report.page.items.map { it.m() },
                "z_tool leads on calls although it sorts last by key; b_tool precedes c_tool on the tie by key"
            )
            assertEquals(3, report.page.total)
            assertFalse(report.page.truncated)
            assertEquals(ReportGroupBy.TOOL, report.groupBy)
        }

    @Test
    fun `S1 probe the average rounds half up, not down and not to even`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))
            f.seed(
                ReportKit.rec(1, at, "d_tool", latencyMs = 2),
                ReportKit.rec(2, at, "d_tool", latencyMs = 3),
                ReportKit.rec(3, at, "e_tool", latencyMs = 0),
                ReportKit.rec(4, at, "e_tool", latencyMs = 1)
            )

            val byKey =
                f
                    .ok()
                    .page.items
                    .associateBy { it.key }

            assertEquals(3L, byKey.getValue("d_tool").avgLatencyMs, "2.5 rounds up to 3 (round-half-even would give 2)")
            assertEquals(1L, byKey.getValue("e_tool").avgLatencyMs, "0.5 rounds up to 1 (floor and half-even would give 0)")
        }

    @Test
    fun `S1 probe sums beyond the Int range do not overflow`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))
            f.seed(
                ReportKit.rec(1, at, "big_tool", responseBytes = 2_000_000_000),
                ReportKit.rec(2, at, "big_tool", responseBytes = 2_000_000_000)
            )

            val row =
                f
                    .ok()
                    .page.items
                    .single()

            assertEquals(4_000_000_000L, row.responseBytes)
            assertEquals(1_000_000_000L, row.responseTokensEst)
        }

    @Test
    fun `S1 probe a duplicate req_id is ignored so the call counts once`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))

            val inserted = f.seed(ReportKit.rec(1, at, "dup_tool", latencyMs = 5), ReportKit.rec(1, at, "dup_tool", latencyMs = 500))

            assertEquals(1, inserted, "control: the second record with the same req_id is ignored")
            val row =
                f
                    .ok()
                    .page.items
                    .single()
            assertEquals(1L, row.calls)
            assertEquals(5L, row.maxLatencyMs)
        }

    @Test
    fun `S1 probe the same call twice answers identical data`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, ago(Duration.ofHours(1)), "a_tool"),
                ReportKit.rec(2, ago(Duration.ofHours(2)), "b_tool", error = "x")
            )

            val first = f.ok()
            val second = f.ok()

            assertEquals(first.page.items, second.page.items)
            assertEquals(first.window, second.window)
        }

    // ------------------------------------------------------------------ S2 default window

    @Test
    fun `S2 the default window is the seven days before now, start inclusive and end exclusive`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, ago(Duration.ofDays(7)), "in_start"),
                ReportKit.rec(2, ago(Duration.ofDays(7)).minusMillis(1), "out_before"),
                ReportKit.rec(3, now.minusMillis(1), "in_end"),
                ReportKit.rec(4, now, "out_until")
            )

            val report = f.ok()

            assertEquals(setOf("in_start", "in_end"), report.keys().toSet())
            assertEquals(2, report.page.total)
            assertEquals(Instant.parse("2026-10-06T12:00:00Z"), report.window.since)
            assertEquals(now, report.window.until)
        }

    @Test
    fun `S2 an explicit until without since defaults since to seven days before it`(): Unit =
        runBlocking {
            val report = fx.ok(asRequest(until = "2026-10-10T00:00:00Z"))

            assertEquals(Instant.parse("2026-10-03T00:00:00Z"), report.window.since)
            assertEquals(Instant.parse("2026-10-10T00:00:00Z"), report.window.until)
        }

    @Test
    fun `S2 the window follows the env clock`(): Unit =
        runBlocking {
            val f = fx
            clock.set(Instant.parse("2026-11-01T00:00:00Z"))

            val report = f.ok()

            assertEquals(Instant.parse("2026-11-01T00:00:00Z"), report.window.until)
            assertEquals(Instant.parse("2026-10-25T00:00:00Z"), report.window.since)
        }

    // ------------------------------------------------------------------ S3 groupBy dimensions

    /** Four rows spread over two UTC days; ats straddle the midnight boundary by one millisecond. */
    private suspend fun seedDims(f: ReportFixture) {
        f.seed(
            ReportKit.rec(
                1,
                Instant.parse("2026-10-11T23:59:59.999Z"),
                operation = "get",
                host = "h1",
                surface = "mcp",
                principalId = "pid-1",
                principalKind = "subagent"
            ),
            ReportKit.rec(
                2,
                Instant.parse("2026-10-12T00:00:00.000Z"),
                operation = "get",
                host = "h1",
                surface = "rest",
                principalId = "pid-2",
                principalKind = "user",
                error = "not_found"
            ),
            ReportKit.rec(3, Instant.parse("2026-10-12T12:00:00Z"), surface = "rest"),
            ReportKit.rec(
                4,
                Instant.parse("2026-10-12T23:59:59.999Z"),
                operation = "list",
                host = "h2",
                surface = "mcp",
                principalId = "pid-1",
                principalKind = "subagent",
                error = "forbidden"
            )
        )
    }

    private suspend fun keysBy(groupBy: ReportGroupBy): List<String?> {
        val f = fx
        seedDims(f)
        return f.ok(ReportUsageRequest(groupBy = groupBy)).keys()
    }

    @Test
    fun `S3 operation groups with a NULL key listed first among equal calls`(): Unit =
        runBlocking { assertEquals(listOf("get", null, "list"), keysBy(ReportGroupBy.OPERATION)) }

    @Test
    fun `S3 host groups with a NULL key listed first among equal calls`(): Unit =
        runBlocking { assertEquals(listOf("h1", null, "h2"), keysBy(ReportGroupBy.HOST)) }

    @Test
    fun `S3 surface groups, ties broken by key`(): Unit = runBlocking { assertEquals(listOf("mcp", "rest"), keysBy(ReportGroupBy.SURFACE)) }

    @Test
    fun `S3 principalKind groups with a NULL key listed first among equal calls`(): Unit =
        runBlocking { assertEquals(listOf("subagent", null, "user"), keysBy(ReportGroupBy.PRINCIPAL_KIND)) }

    @Test
    fun `S3 errorCode groups, the ok rows form the NULL group`(): Unit =
        runBlocking { assertEquals(listOf(null, "forbidden", "not_found"), keysBy(ReportGroupBy.ERROR_CODE)) }

    @Test
    fun `S3 day groups by the UTC date and splits at midnight to the millisecond`(): Unit =
        runBlocking {
            val f = fx
            seedDims(f)

            val report = f.ok(ReportUsageRequest(groupBy = ReportGroupBy.DAY))

            assertEquals(listOf("2026-10-12", "2026-10-11"), report.keys(), "23:59:59.999Z is the 11th, 00:00:00.000Z is the 12th")
            assertEquals(listOf(3L, 1L), report.page.items.map { it.calls })
        }

    @Test
    fun `S3 principal groups by principal_id for a caller that may see principals`(): Unit =
        runBlocking {
            val f = fx
            seedDims(f)

            val report = f.ok(ReportUsageRequest(groupBy = ReportGroupBy.PRINCIPAL), f.ctx(principalsVisible = true))

            assertEquals(listOf("pid-1", null, "pid-2"), report.keys())
            assertEquals(ReportGroupBy.PRINCIPAL, report.groupBy)
        }

    // ------------------------------------------------------------------ S4 / S5 window echo and retention

    @Test
    fun `S4 the window is echoed in UTC, basis raw, and an offset input is converted`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, ago(Duration.ofHours(3)), "t"))

            val report = f.ok(asRequest(since = "2026-10-12T14:00:00+02:00", until = "2026-10-13T13:30:00+02:00"))

            assertEquals(Instant.parse("2026-10-12T12:00:00Z"), report.window.since)
            assertEquals(Instant.parse("2026-10-13T11:30:00Z"), report.window.until)
            assertEquals("raw", report.window.basis)
        }

    @Test
    fun `S4 retainedSince is the minimum at over the whole table and truncatedByRetention only when since is earlier`(): Unit =
        runBlocking {
            val f = fx
            val oldest = ago(Duration.ofDays(3))
            f.seed(ReportKit.rec(1, oldest, "old"), ReportKit.rec(2, ago(Duration.ofDays(2)), "newer"))

            val before = f.ok(asRequest(since = ago(Duration.ofDays(5)).toString()))
            assertEquals(oldest, before.window.retainedSince)
            assertTrue(before.window.truncatedByRetention, "since is before the oldest row")

            val exact = f.ok(asRequest(since = oldest.toString()))
            assertEquals(oldest, exact.window.retainedSince)
            assertFalse(exact.window.truncatedByRetention, "since equal to retainedSince is not truncated")

            val after = f.ok(asRequest(since = ago(Duration.ofDays(2)).toString()))
            assertFalse(after.window.truncatedByRetention, "since after the oldest row")
            assertEquals(oldest, after.window.retainedSince, "retainedSince ignores the window")
        }

    @Test
    fun `S4 an empty table has no retainedSince and is not truncated`(): Unit =
        runBlocking {
            val report = fx.ok()

            assertNull(report.window.retainedSince)
            assertFalse(report.window.truncatedByRetention)
            assertEquals(0, report.page.total)
            assertTrue(report.page.items.isEmpty())
        }

    @Test
    fun `S4 retainedSince is table-wide, so a root scope sees the oldest row of another root`(): Unit =
        runBlocking {
            val f = fx
            val oldestElsewhere = ago(Duration.ofDays(4))
            f.root(r1, "Alpha root")
            f.root(r2, "Beta root")
            f.seed(
                ReportKit.rec(1, oldestElsewhere, "r2_tool", rootId = r2),
                ReportKit.rec(2, ago(Duration.ofDays(1)), "r1_tool", rootId = r1)
            )

            val report = f.ok(ReportUsageRequest(), f.ctx(AccessScope.principal(setOf(r1), emptySet())))

            assertEquals(oldestElsewhere, report.window.retainedSince)
            assertEquals(listOf("r1_tool"), report.keys())
        }

    @Test
    fun `S5 after a prune retainedSince is the oldest surviving row`(): Unit =
        runBlocking {
            val f = fx
            f.seed(
                ReportKit.rec(1, ago(Duration.ofDays(10)), "gone"),
                ReportKit.rec(2, ago(Duration.ofDays(5)), "kept_old"),
                ReportKit.rec(3, ago(Duration.ofDays(1)), "kept_new")
            )
            var deleted = -1
            db.unitOfWork().write("test.prune") {
                deleted = stores.callLogStore().prune(ago(Duration.ofDays(6)), 100)
                Outcome.Ok(deleted)
            }
            assertEquals(1, deleted, "control: exactly the ten-day-old row was pruned")

            val report = f.ok(asRequest(since = ago(Duration.ofDays(7)).toString()))

            assertEquals(ago(Duration.ofDays(5)), report.window.retainedSince)
            assertTrue(report.window.truncatedByRetention, "the requested start predates what is still held")
            assertEquals(setOf("kept_old", "kept_new"), report.keys().toSet())
        }

    // ------------------------------------------------------------------ S6 paging

    private suspend fun seedFiveGroups(f: ReportFixture) {
        var n = 0
        for ((tool, calls) in listOf("g1" to 5, "g2" to 4, "g3" to 3, "g4" to 2, "g5" to 1)) {
            repeat(calls) { f.seed(ReportKit.rec(++n, ago(Duration.ofHours(1)), tool)) }
        }
    }

    @Test
    fun `S6 limit 2 over five groups pages 2, 2, 1 with total 5 and a next cursor until the last page`(): Unit =
        runBlocking {
            val f = fx
            seedFiveGroups(f)

            val p1 = f.ok(ReportUsageRequest(limit = 2))
            assertEquals(listOf("g1", "g2"), p1.keys())
            assertEquals(5, p1.page.total)
            assertTrue(p1.page.truncated)
            val c1 = assertNotNull(p1.page.next, "a truncated page carries next")

            val p2 = f.ok(ReportUsageRequest(limit = 2, cursor = c1))
            assertEquals(listOf("g3", "g4"), p2.keys())
            assertEquals(5, p2.page.total, "total is the full group count on every page")
            assertTrue(p2.page.truncated)
            val c2 = assertNotNull(p2.page.next)

            val p3 = f.ok(ReportUsageRequest(limit = 2, cursor = c2))
            assertEquals(listOf("g5"), p3.keys())
            assertFalse(p3.page.truncated)
            assertNull(p3.page.next, "the last page has no next")
            for (page in listOf(p1, p2, p3)) assertEquals(p1.window, page.window, "every page carries the same window")
        }

    @Test
    fun `S6 the default limit is 50 and a page that holds everything is not truncated`(): Unit =
        runBlocking {
            val f = fx
            seedFiveGroups(f)

            val all = f.ok()

            assertEquals(listOf("g1", "g2", "g3", "g4", "g5"), all.keys())
            assertFalse(all.page.truncated)
            assertNull(all.page.next)
        }

    @Test
    fun `S6 the limit bounds 1 and 200 are accepted`(): Unit =
        runBlocking {
            val f = fx
            seedFiveGroups(f)

            assertEquals(
                1,
                f
                    .ok(ReportUsageRequest(limit = 1))
                    .page.items.size
            )
            assertEquals(
                5,
                f
                    .ok(ReportUsageRequest(limit = 200))
                    .page.items.size
            )
        }

    // ------------------------------------------------------------------ S8 unsupported groupBy

    @Test
    fun `S8 noteKey, type, trait and resource are invalid_request at the groupBy pointer`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, ago(Duration.ofHours(1)), "t"))
            for (unsupported in listOf(ReportGroupBy.NOTE_KEY, ReportGroupBy.TYPE, ReportGroupBy.TRAIT, ReportGroupBy.RESOURCE)) {
                val error = err(f.usage(ReportUsageRequest(groupBy = unsupported)))

                assertEquals(ErrorCode.INVALID_REQUEST, error.code, unsupported.wire)
                val violation = assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single()
                assertEquals("/groupBy", violation.field, unsupported.wire)
            }
            assertEquals(1, f.ok(ReportUsageRequest(groupBy = ReportGroupBy.TOOL)).page.total, "control: a supported groupBy answers")
        }

    // ------------------------------------------------------------------ S9 window and paging validation

    private suspend fun assertInvalid(
        request: ReportUsageRequest,
        label: String
    ) {
        val error = err(fx.usage(request))
        assertEquals(ErrorCode.INVALID_REQUEST, error.code, label)
        assertTrue(assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.isNotEmpty(), label)
    }

    @Test
    fun `S9 a window of exactly 31 days is raw and one millisecond more is served on the daily basis`(): Unit =
        runBlocking {
            val raw = fx.ok(asRequest(since = "2026-09-01T00:00:00Z", until = "2026-10-02T00:00:00Z"))
            assertEquals("raw", raw.window.basis, "31 days exactly")

            val daily = fx.ok(asRequest(since = "2026-09-01T00:00:00Z", until = "2026-10-02T00:00:00.001Z"))
            assertEquals("daily", daily.window.basis, "31d + 1ms")
            assertEquals(Instant.parse("2026-09-01T00:00:00Z"), daily.window.since)
            assertEquals(Instant.parse("2026-10-02T00:00:00.001Z"), daily.window.until, "no fold exists, so until is not ceiled")
        }

    @Test
    fun `S9 the 31 day raw cap also applies when until defaults to now`(): Unit =
        runBlocking {
            val raw = fx.ok(asRequest(since = now.minus(Duration.ofDays(31)).toString()))
            assertEquals("raw", raw.window.basis)
            assertEquals(now.minus(Duration.ofDays(31)), raw.window.since, "a raw window is not aligned")

            val daily = fx.ok(asRequest(since = now.minus(Duration.ofDays(31)).minusMillis(1).toString()))
            assertEquals("daily", daily.window.basis, "now - 31d - 1ms")
            assertEquals(Instant.parse("2026-09-12T00:00:00Z"), daily.window.since, "floored to its UTC midnight")
            assertEquals(now, daily.window.until)
        }

    @Test
    fun `S9 a window of exactly 400 days is accepted and 400 days plus one millisecond is invalid at the since pointer`(): Unit =
        runBlocking {
            val ok = fx.ok(asRequest(since = now.minus(Duration.ofDays(400)).toString()))
            assertEquals("daily", ok.window.basis)

            val error = err(fx.usage(asRequest(since = now.minus(Duration.ofDays(400)).minusMillis(1).toString())))
            assertEquals(ErrorCode.INVALID_REQUEST, error.code, "now - 400d - 1ms")
            assertTrue("/since" in assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.map { it.field })
        }

    @Test
    fun `S9 since at or after until is invalid`(): Unit =
        runBlocking {
            assertInvalid(asRequest(since = "2026-10-05T00:00:00Z", until = "2026-10-05T00:00:00Z"), "since == until")
            assertInvalid(asRequest(since = "2026-10-06T00:00:00Z", until = "2026-10-05T00:00:00Z"), "since > until")
            assertInvalid(asRequest(since = now.plusSeconds(1).toString()), "since after the default until (now)")
            fx.ok(asRequest(since = "2026-10-05T00:00:00Z", until = "2026-10-05T00:00:00.001Z"))
        }

    @Test
    fun `S9 a malformed instant, one without a zone or offset, and a date alone are invalid`(): Unit =
        runBlocking {
            for (bad in listOf("yesterday", "2026-13-45T00:00:00Z", "2026-10-01", "2026-10-01T00:00:00")) {
                assertInvalid(asRequest(since = bad, until = "2026-10-05T00:00:00Z"), "since '$bad'")
                assertInvalid(asRequest(since = "2026-10-01T00:00:00Z", until = bad), "until '$bad'")
            }
            fx.ok(asRequest(since = "2026-10-01T00:00:00Z", until = "2026-10-05T00:00:00Z"))
        }

    @Test
    fun `S9 a limit of 0 or 201 is invalid_request and a garbled cursor is invalid_cursor`(): Unit =
        runBlocking {
            val f = fx
            f.seed(ReportKit.rec(1, ago(Duration.ofHours(1)), "t"))
            for (limit in listOf(0, 201, -1)) assertInvalid(ReportUsageRequest(limit = limit), "limit $limit")

            for (cursor in listOf("!!!not-a-cursor", "", "bm90IGpzb24")) {
                assertEquals(ErrorCode.INVALID_CURSOR, err(f.usage(ReportUsageRequest(cursor = cursor))).code, "cursor '$cursor'")
            }
        }

    // ------------------------------------------------------------------ S10 tag scope

    private suspend fun seedScopeRows(f: ReportFixture) {
        f.root(r1, "Alpha root", "api")
        f.root(r2, "Beta root", "beta")
        val at = ago(Duration.ofHours(1))
        f.seed(
            ReportKit.rec(1, at, "shared_tool", rootId = r1),
            ReportKit.rec(2, at, "shared_tool", rootId = r1, error = "x"),
            ReportKit.rec(3, at, "shared_tool", rootId = r2),
            ReportKit.rec(4, at, "shared_tool"),
            ReportKit.rec(5, at, "r2_only_tool", rootId = r2),
            ReportKit.rec(6, at, "null_only_tool")
        )
    }

    @Test
    fun `S10 a tag-scoped caller is forbidden with the tag scope, with and without a rootId, even for an unknown rootId`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val tagScoped = f.ctx(AccessScope.principal(null, setOf("api")))
            val requests =
                listOf(
                    ReportUsageRequest(),
                    ReportUsageRequest(rootId = IdRef(r1.toString())),
                    ReportUsageRequest(rootId = IdRef("99999999-9999-4999-8999-999999999999")),
                    ReportUsageRequest(groupBy = ReportGroupBy.DAY)
                )
            for (request in requests) {
                val error = err(f.usage(request, tagScoped))

                assertEquals(ErrorCode.FORBIDDEN, error.code, "$request")
                val detail = assertIs<ErrorDetail.Forbidden>(error.detail)
                assertEquals(ForbiddenScope.TAG, detail.scope, "$request")
                assertEquals("api", detail.required, "$request")
            }
            assertTrue(
                f
                    .ok()
                    .page.items
                    .isNotEmpty(),
                "control: an unrestricted caller reads the same rows"
            )
        }

    @Test
    fun `S10 a caller scoped by both roots and tags is still forbidden by the tag`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            val error = err(f.usage(ReportUsageRequest(), f.ctx(AccessScope.principal(setOf(r1), setOf("api")))))

            assertEquals(ForbiddenScope.TAG, assertIs<ErrorDetail.Forbidden>(error.detail).scope)
        }

    // ------------------------------------------------------------------ S11 principal grouping

    @Test
    fun `S11 grouping by principal without the admin capability is forbidden at the capability scope`(): Unit =
        runBlocking {
            val f = fx
            seedDims(f)
            val noAdmin = f.ctx(principalsVisible = false)

            val error = err(f.usage(ReportUsageRequest(groupBy = ReportGroupBy.PRINCIPAL), noAdmin))

            assertEquals(ErrorCode.FORBIDDEN, error.code)
            val detail = assertIs<ErrorDetail.Forbidden>(error.detail)
            assertEquals(ForbiddenScope.CAPABILITY, detail.scope)
            assertEquals("admin", detail.required)
            val control = f.ok(ReportUsageRequest(groupBy = ReportGroupBy.OPERATION), noAdmin)
            assertEquals(4L, control.page.items.sumOf { it.calls }, "control: every other grouping stays open to a caller without admin")
        }

    @Test
    fun `S11 probe an admin grouping by principal under a root scope sees only that scope's principals`(): Unit =
        runBlocking {
            val f = fx
            f.root(r1, "Alpha root")
            f.root(r2, "Beta root")
            val at = ago(Duration.ofHours(1))
            f.seed(
                ReportKit.rec(1, at, principalId = "in-scope", rootId = r1),
                ReportKit.rec(2, at, principalId = "foreign", rootId = r2),
                ReportKit.rec(3, at, principalId = "no-root")
            )
            val byPrincipal = ReportUsageRequest(groupBy = ReportGroupBy.PRINCIPAL)

            val scoped = f.ok(byPrincipal, f.ctx(AccessScope.principal(setOf(r1), emptySet()), principalsVisible = true))
            val open = f.ok(byPrincipal, f.ctx(principalsVisible = true))

            assertEquals(listOf("in-scope"), scoped.keys(), "the foreign and the NULL-root principals are filtered before grouping")
            assertEquals(setOf("in-scope", "foreign", "no-root"), open.keys().toSet(), "control: the unrestricted admin sees all three")
        }

    // ------------------------------------------------------------------ S12 rootId resolution

    @Test
    fun `S12 a rootId outside the root scope is forbidden at the root scope`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val inR1 = f.ctx(AccessScope.principal(setOf(r1), emptySet()))

            val error = err(f.usage(ReportUsageRequest(rootId = IdRef(r2.toString())), inR1))

            assertEquals(ErrorCode.FORBIDDEN, error.code)
            assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(error.detail).scope)
            val own = f.ok(ReportUsageRequest(rootId = IdRef(r1.toString())), inR1)
            assertTrue(own.page.items.isNotEmpty(), "control: the own root answers")
        }

    @Test
    fun `S12 an unknown rootId is not_found unrestricted and forbidden under a restricted scope`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val unknown = IdRef("99999999-9999-4999-8999-999999999999")
            val restricted = f.ctx(AccessScope.principal(setOf(r1), emptySet()))

            assertEquals(ErrorCode.NOT_FOUND, err(f.usage(ReportUsageRequest(rootId = unknown))).code)
            assertEquals(ErrorCode.FORBIDDEN, err(f.usage(ReportUsageRequest(rootId = unknown), restricted)).code)
        }

    @Test
    fun `S12 a rootId that is not a depth-0 item is invalid_request at the rootId pointer`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            f.child(ReportKit.C1, r1, "Child")

            for (ctx in listOf(f.ctx(), f.ctx(AccessScope.principal(setOf(r1), emptySet())))) {
                val error = err(f.usage(ReportUsageRequest(rootId = IdRef(ReportKit.C1.toString())), ctx))

                assertEquals(ErrorCode.INVALID_REQUEST, error.code)
                assertEquals("/rootId", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field)
            }
        }

    @Test
    fun `S12 probe two roots sharing a prefix make the prefix ambiguous`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val sibling = UUID.fromString("a1a1a1a1-1111-4111-8111-111111111111")
            f.root(sibling, "Alpha sibling")

            val error = err(f.usage(ReportUsageRequest(rootId = IdRef("a1a1a1a1"))))

            assertEquals(ErrorCode.AMBIGUOUS_ID, error.code)
        }

    // ------------------------------------------------------------------ S13 root scope filters before grouping

    @Test
    fun `S13 a root scope sees only its root's rows in the rows and the total`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            val scoped = f.ok(ReportUsageRequest(), f.ctx(AccessScope.principal(setOf(r1), emptySet())))
            val open = f.ok()

            assertEquals(listOf(M("shared_tool", 2, 1, 0, 0, 1, 1, 0, 0)), scoped.page.items.map { it.m() })
            assertEquals(1, scoped.page.total, "the groups of other roots and NULL-root rows are not counted")
            assertEquals(
                setOf("shared_tool", "r2_only_tool", "null_only_tool"),
                open.keys().toSet(),
                "control: the unrestricted caller sees every group"
            )
            assertEquals(
                4L,
                open.page.items
                    .single { it.key == "shared_tool" }
                    .calls
            )
        }

    @Test
    fun `S13 probe an empty root set sees nothing and is not forbidden, unlike an absent root set`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            val none = f.ok(ReportUsageRequest(), f.ctx(AccessScope.principal(emptySet(), emptySet())))
            val absent = f.ok(ReportUsageRequest(), f.ctx(AccessScope.principal(null, emptySet())))

            assertEquals(0, none.page.total)
            assertTrue(none.page.items.isEmpty())
            assertEquals(3, absent.page.total, "a null root set is unrestricted")
        }

    // ------------------------------------------------------------------ S14 rootId filter

    @Test
    fun `S14 an unrestricted rootId filters to that root, by full id, by an eight character prefix and by an upper-case id`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            for (ref in listOf(r1.toString(), r1.toString().take(8), r1.toString().uppercase())) {
                val report = f.ok(ReportUsageRequest(rootId = IdRef(ref)))

                assertEquals(listOf(M("shared_tool", 2, 1, 0, 0, 1, 1, 0, 0)), report.page.items.map { it.m() }, ref)
                assertEquals(1, report.page.total, ref)
            }
        }

    @Test
    fun `S14 the rootId filter is applied on top of the root scope`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)
            val both = f.ctx(AccessScope.principal(setOf(r1, r2), emptySet()))

            val onlyR2 = f.ok(ReportUsageRequest(rootId = IdRef(r2.toString())), both)
            val all = f.ok(ReportUsageRequest(), both)

            assertEquals(setOf("shared_tool", "r2_only_tool"), onlyR2.keys().toSet())
            assertEquals(
                1L,
                onlyR2.page.items
                    .single { it.key == "shared_tool" }
                    .calls
            )
            assertEquals(setOf("shared_tool", "r2_only_tool"), all.keys().toSet(), "NULL-root rows are never visible to a root scope")
            assertEquals(
                3L,
                all.page.items
                    .single { it.key == "shared_tool" }
                    .calls
            )
        }
}
