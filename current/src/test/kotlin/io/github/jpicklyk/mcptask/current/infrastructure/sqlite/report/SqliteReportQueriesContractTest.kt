package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report

import io.github.jpicklyk.mcptask.current.application.port.ReportQueries
import io.github.jpicklyk.mcptask.current.application.port.RunCost
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsagePage
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent contract tests (test-author seat, item eadaaa4e, R9) for the [ReportQueries] port over the real SQLite database:
 * the store-level half of scenarios S1, S3, S5, S7, S13 and S14 of the frozen test-plan (NEW-SURFACE). The port is reached the
 * way production reaches it, as `repositoryProvider().reportQueries()` inside a read unit.
 *
 * Oracles (never read from a run): task-scope AC1 (errors = rows with outcome `error`; bytes and tokens SUM with NULL as 0; the
 * latency sum, the half-up average and the maximum; retries = SUM(attempts - 1); replays = COUNT(replayed)), AC2 (half-open
 * window), AC3 (the dimension keys; a NULL key is NULL and lists first among equal calls; day is the UTC date), AC4 (S22 in SQL
 * before grouping: a null `scopeRootIds` is unrestricted, an empty set sees nothing, a set sees rows whose root_id is in it and
 * never a NULL-root row; `totalGroups` counts only visible groups), AC5 (the rootId filter on top of the scope), AC6
 * (retainedSince = MIN(call_log.at) over the whole table, null when empty), AC7 (runCost sums calls, errors, bytes and tokens of
 * the rows with that run_id, zeros when none, no window and no scope) and the order calls descending then key ascending. The
 * expected numbers are hand sums of the seeded rows, written next to the rows.
 *
 * Negative-assertion conditions: every exclusion (a row on the window's end, an R2 row, a NULL-root row, a row of another run)
 * has a seeded row that appears in the control query over the same fixture.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: records come from [ReportKit.rec] (outcome derived from the error code, token
 * estimate from the bytes); roots are real depth-0 items.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SqliteReportQueriesContractTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2

    private fun ago(d: Duration): Instant = now.minus(d)

    private suspend fun <T> inRead(block: suspend (ReportQueries) -> T): T =
        db.unitOfWork().read { block(db.repositoryProvider().reportQueries()) }

    private fun query(
        groupBy: UsageDimension = UsageDimension.TOOL,
        since: Instant = ago(Duration.ofDays(7)),
        until: Instant = now,
        scopeRootIds: Set<UUID>? = null,
        rootId: UUID? = null,
        limit: Int = 50,
        offset: Int = 0
    ) = UsageQuery(since, until, groupBy, scopeRootIds, rootId, limit, offset)

    private suspend fun usage(q: UsageQuery): UsagePage = inRead { it.usage(q) }

    private fun UsagePage.keys(): List<String?> = rows.map { it.key }

    private suspend fun seedRoots(f: ReportFixture) {
        f.root(r1, "Alpha root")
        f.root(r2, "Beta root")
    }

    // ------------------------------------------------------------------ S1 metrics

    @Test
    fun `S1 the port returns the sums, the latency sum, the half-up average, the maximum, retries and replays per group in order`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))
            f.seed(
                ReportKit.rec(1, at, "z_tool", latencyMs = 10, responseBytes = 100),
                ReportKit.rec(2, at, "z_tool", error = "gate_blocked", latencyMs = 20, attempts = 3, replayed = true),
                ReportKit.rec(3, at, "z_tool", latencyMs = 31, responseBytes = 48, attempts = 2),
                ReportKit.rec(4, at, "b_tool", latencyMs = 10, responseBytes = 8),
                ReportKit.rec(5, at, "b_tool", latencyMs = 15, responseBytes = 12),
                ReportKit.rec(6, at, "c_tool", error = "timeout", latencyMs = 4, attempts = 2, replayed = true),
                ReportKit.rec(7, at, "c_tool", error = "timeout", latencyMs = 4)
            )

            val page = usage(query())

            assertEquals(3, page.totalGroups)
            assertEquals(listOf("z_tool", "b_tool", "c_tool"), page.keys(), "calls descending, then key ascending")
            val z = page.rows[0]
            assertEquals(listOf(3L, 1L, 148L, 37L, 61L, 20L, 31L, 3L, 1L), z.longs())
            val b = page.rows[1]
            assertEquals(listOf(2L, 0L, 20L, 5L, 25L, 13L, 15L, 0L, 0L), b.longs(), "12.5 rounds half up to 13")
            val c = page.rows[2]
            assertEquals(listOf(2L, 2L, 0L, 0L, 8L, 4L, 4L, 1L, 1L), c.longs(), "all-NULL bytes and tokens sum to 0")
        }

    private fun UsageRow.longs(): List<Long> =
        listOf(calls, errors, responseBytes, responseTokensEst, latencySumMs, avgLatencyMs, maxLatencyMs, retries, replays)

    @Test
    fun `S1 probe half-way averages round up`(): Unit =
        runBlocking {
            val f = fx
            val at = ago(Duration.ofHours(1))
            f.seed(
                ReportKit.rec(1, at, "d_tool", latencyMs = 2),
                ReportKit.rec(2, at, "d_tool", latencyMs = 3),
                ReportKit.rec(3, at, "e_tool", latencyMs = 0),
                ReportKit.rec(4, at, "e_tool", latencyMs = 1)
            )

            val byKey = usage(query()).rows.associateBy { it.key }

            assertEquals(3L, byKey.getValue("d_tool").avgLatencyMs)
            assertEquals(1L, byKey.getValue("e_tool").avgLatencyMs)
        }

    // ------------------------------------------------------------------ window

    @Test
    fun `S2 the window is half open - since is in, until is out, to the millisecond`(): Unit =
        runBlocking {
            val f = fx
            val since = ago(Duration.ofDays(2))
            f.seed(
                ReportKit.rec(1, since, "in_start"),
                ReportKit.rec(2, since.minusMillis(1), "out_before"),
                ReportKit.rec(3, now.minusMillis(1), "in_end"),
                ReportKit.rec(4, now, "out_until")
            )

            val page = usage(query(since = since, until = now))

            assertEquals(setOf("in_start", "in_end"), page.keys().toSet())
            assertEquals(2, page.totalGroups)
            assertEquals(4, usage(query(since = since.minusMillis(1), until = now.plusMillis(1))).totalGroups, "control: widened by 1ms")
        }

    // ------------------------------------------------------------------ S3 dimensions

    private suspend fun seedDims(f: ReportFixture) {
        f.seed(
            ReportKit.rec(
                1,
                Instant.parse("2026-10-11T23:59:59.999Z"),
                "t_get",
                operation = "get",
                host = "h1",
                surface = "mcp",
                principalId = "pid-1",
                principalKind = "subagent"
            ),
            ReportKit.rec(
                2,
                Instant.parse("2026-10-12T00:00:00.000Z"),
                "t_get",
                operation = "get",
                host = "h1",
                surface = "rest",
                principalId = "pid-2",
                principalKind = "user",
                error = "not_found"
            ),
            ReportKit.rec(3, Instant.parse("2026-10-12T12:00:00Z"), "t_none", surface = "rest"),
            ReportKit.rec(
                4,
                Instant.parse("2026-10-12T23:59:59.999Z"),
                "t_list",
                operation = "list",
                host = "h2",
                surface = "mcp",
                principalId = "pid-1",
                principalKind = "subagent",
                error = "forbidden"
            )
        )
    }

    @Test
    fun `S3 every dimension keys its groups by its column, a NULL key lists first among equal calls`(): Unit =
        runBlocking {
            val f = fx
            seedDims(f)
            val expected =
                mapOf(
                    UsageDimension.TOOL to listOf("t_get", "t_list", "t_none"),
                    UsageDimension.OPERATION to listOf("get", null, "list"),
                    UsageDimension.HOST to listOf("h1", null, "h2"),
                    UsageDimension.SURFACE to listOf("mcp", "rest"),
                    UsageDimension.PRINCIPAL_KIND to listOf("subagent", null, "user"),
                    UsageDimension.ERROR_CODE to listOf(null, "forbidden", "not_found"),
                    UsageDimension.DAY to listOf("2026-10-12", "2026-10-11"),
                    UsageDimension.PRINCIPAL to listOf("pid-1", null, "pid-2")
                )
            assertEquals(UsageDimension.entries.toSet(), expected.keys, "fixture: every dimension has an expectation")

            for ((dimension, keys) in expected) {
                assertEquals(keys, usage(query(groupBy = dimension)).keys(), dimension.name)
            }
        }

    @Test
    fun `S3 the day dimension is the UTC date and splits exactly at midnight`(): Unit =
        runBlocking {
            val f = fx
            seedDims(f)

            val page = usage(query(groupBy = UsageDimension.DAY))

            assertEquals(listOf(3L, 1L), page.rows.map { it.calls }, "the 12th holds 00:00:00.000Z, 12:00Z and 23:59:59.999Z")
        }

    // ------------------------------------------------------------------ S5 retention

    @Test
    fun `S5 retainedSince is null on an empty table, the minimum at over the whole table, and moves up after a prune`(): Unit =
        runBlocking {
            val f = fx
            seedRoots(f)
            assertNull(inRead { it.retainedSince() })

            f.seed(
                ReportKit.rec(1, ago(Duration.ofDays(10)), "old", rootId = r2),
                ReportKit.rec(2, ago(Duration.ofDays(5)), "mid", rootId = r1),
                ReportKit.rec(3, ago(Duration.ofDays(1)), "new")
            )
            assertEquals(ago(Duration.ofDays(10)), inRead { it.retainedSince() }, "the oldest row, whichever its root")

            db.unitOfWork().write("test.prune") {
                stores.callLogStore().prune(ago(Duration.ofDays(6)), 100)
                Outcome.Ok(Unit)
            }
            assertEquals(ago(Duration.ofDays(5)), inRead { it.retainedSince() })
        }

    // ------------------------------------------------------------------ S6 paging at the port

    @Test
    fun `S6 limit and offset slice the ordered groups and totalGroups stays the full count`(): Unit =
        runBlocking {
            val f = fx
            var n = 0
            for ((tool, calls) in listOf("g1" to 5, "g2" to 4, "g3" to 3, "g4" to 2, "g5" to 1)) {
                repeat(calls) { f.seed(ReportKit.rec(++n, ago(Duration.ofHours(1)), tool)) }
            }

            assertEquals(listOf("g1", "g2"), usage(query(limit = 2, offset = 0)).keys())
            val second = usage(query(limit = 2, offset = 2))
            assertEquals(listOf("g3", "g4"), second.keys())
            assertEquals(5, second.totalGroups)
            assertEquals(listOf("g5"), usage(query(limit = 2, offset = 4)).keys())
            val beyond = usage(query(limit = 2, offset = 10))
            assertTrue(beyond.rows.isEmpty())
            assertEquals(5, beyond.totalGroups, "the count does not depend on the slice")
        }

    // ------------------------------------------------------------------ S7 runCost

    @Test
    fun `S7 runCost sums only the rows of that run, over any time and any root, and answers zeros for an unknown run`(): Unit =
        runBlocking {
            val f = fx
            seedRoots(f)
            val runA = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
            val runB = UUID.fromString("1f8fad5b-d9cb-469f-a165-70867728950f")
            f.seed(
                ReportKit.rec(1, ago(Duration.ofDays(40)), "a1", responseBytes = 100, runId = runA, rootId = r1),
                ReportKit.rec(2, ago(Duration.ofHours(1)), "a2", error = "x", runId = runA),
                ReportKit.rec(3, ago(Duration.ofHours(2)), "a3", responseBytes = 48, runId = runA, rootId = r2),
                ReportKit.rec(4, ago(Duration.ofHours(1)), "b1", responseBytes = 8, runId = runB),
                ReportKit.rec(5, ago(Duration.ofHours(1)), "n1", responseBytes = 1000)
            )

            assertEquals(RunCost(calls = 3, errors = 1, responseBytes = 148, responseTokensEst = 37), inRead { it.runCost(runA) })
            assertEquals(RunCost(calls = 1, errors = 0, responseBytes = 8, responseTokensEst = 2), inRead { it.runCost(runB) })
            assertEquals(RunCost.ZERO, inRead { it.runCost(UUID.fromString("2f8fad5b-d9cb-469f-a165-70867728950a")) })
        }

    // ------------------------------------------------------------------ S13 / S14 scope and root filter in SQL

    private suspend fun seedScopeRows(f: ReportFixture) {
        seedRoots(f)
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
    fun `S13 a null scope is unrestricted, a set keeps only its roots' rows, an empty set keeps nothing`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            val open = usage(query(scopeRootIds = null))
            assertEquals(3, open.totalGroups)
            assertEquals(4L, open.rows.single { it.key == "shared_tool" }.calls, "R1 x2, R2 and the NULL-root row")

            val inR1 = usage(query(scopeRootIds = setOf(r1)))
            assertEquals(listOf("shared_tool"), inR1.keys())
            assertEquals(1, inR1.totalGroups, "r2_only_tool and null_only_tool are not groups of this scope")
            assertEquals(listOf(2L, 1L), inR1.rows.single().let { listOf(it.calls, it.errors) })

            val inBoth = usage(query(scopeRootIds = setOf(r1, r2)))
            assertEquals(setOf("shared_tool", "r2_only_tool"), inBoth.keys().toSet(), "a NULL-root row is never visible to a root set")
            assertEquals(3L, inBoth.rows.single { it.key == "shared_tool" }.calls)

            val none = usage(query(scopeRootIds = emptySet()))
            assertTrue(none.rows.isEmpty())
            assertEquals(0, none.totalGroups)
        }

    @Test
    fun `S14 the rootId filter keeps one root's rows and is applied on top of the scope`(): Unit =
        runBlocking {
            val f = fx
            seedScopeRows(f)

            assertEquals(listOf("shared_tool"), usage(query(rootId = r1)).keys())
            assertEquals(2L, usage(query(rootId = r1)).rows.single().calls)
            assertEquals(setOf("shared_tool", "r2_only_tool"), usage(query(rootId = r2)).keys().toSet())

            val outside = usage(query(scopeRootIds = setOf(r1), rootId = r2))
            assertTrue(outside.rows.isEmpty(), "rootId r2 is outside a scope of {r1}: the filters intersect")
            assertEquals(0, outside.totalGroups)
            assertEquals(2L, usage(query(scopeRootIds = setOf(r1, r2), rootId = r1)).rows.single().calls)
        }
}
