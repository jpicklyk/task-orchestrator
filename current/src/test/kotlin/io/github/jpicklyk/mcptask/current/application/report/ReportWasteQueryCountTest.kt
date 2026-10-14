package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 03f3520d, R12) for scenario S15 of the frozen test-plan (NEW-SURFACE) and the
 * recommended scale guard of the performance-baseline note: the `waste` report keeps a CONSTANT number of SQL statements per
 * call, independent of how many call_log rows, sessions and roots the database holds, and answers a 7-day window of about 21 000
 * rows exactly and in bounded time.
 *
 * Oracles: task-scope AC11 ("a constant number of statements") and the performance-baseline complexity rule (no correlated
 * per-row subquery; O(n log n) and a constant statement count). The expected statement count is not a number taken from the
 * code: it is the count at scale 1 compared with the count at scale 40 of the same kind of data, for the same call. The
 * expected signal values are hand-counted from the seed rules in the comments (per kit and per session), never from a run.
 *
 * Method: as in [ReportQueryCountTest]. An Exposed [StatementInterceptor] is registered on the transaction of an OUTER read unit
 * and the call runs inside it (the report's own read unit joins it); each call is run once before the counted run. Scale N
 * means N copies of a kit of seven rows in its own session, with roots R1, R2 and none. Controls prove that the interceptor sees
 * ordinary reads, that the scale really changes the data (the denominators grow N-fold), and that the rootId and scope calls
 * really filter, so equal counts are not an inert request.
 *
 * Scale guard: about 21 000 rows in 50 sessions in one window. A quadratic join would take minutes, a linear one seconds; the
 * assertion is on the exact result, never on the elapsed time, and the Timeout is generous so that the test cannot flake.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ReportWasteQueryCountTest {
    private val clock = SettableClock(ReportKit.NOW)
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2

    private class StatementCounter : StatementInterceptor {
        var count = 0

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            count++
        }
    }

    private suspend fun countStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Any?
    ): Int =
        d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter.count
        }

    private fun uuid(
        high: Long,
        low: Long
    ): String = UUID(high, low).toString()

    private var seq = 0

    private fun row(
        at: Instant,
        tool: String,
        op: String,
        session: String? = null,
        surface: String = CallLogRecord.SURFACE_MCP,
        error: String? = null,
        versions: String? = null,
        ids: String? = null,
        shape: String? = null,
        result: Int? = null,
        eligible: Int? = null,
        hits: String? = null,
        lf: Int? = null,
        root: UUID? = null
    ): CallLogRecord =
        ReportKit
            .rec(++seq, at, tool, op, surface = surface, error = error, rootId = root)
            .copy(
                sessionId = session,
                targetIds = ids,
                targetVersions = versions,
                requestShape = shape,
                resultCount = result,
                eligibleCount = eligible,
                hitSignals = hits,
                lifecycleFreeRejections = lf
            )

    private fun hit(
        id: String,
        vararg signals: String
    ): String = "[{\"id\":\"$id\",\"kind\":\"item\",\"signals\":${signals.joinToString(",", "[", "]") { "\"$it\"" }}}]"

    /**
     * [scale] kits of seven rows. Kit k lives in session `s-k`, in root R1 (k mod 3 = 0), R2 (k mod 3 = 1) or no root, three days
     * before now (inside the default window). Seconds after the kit base:
     * 10 and 20 get the same key at version 1 (the first with `fields` when k is even), 30 a failed write that counted one
     * lifecycle-free rejection, 40 a next with result 0 and eligible 2, 50 a related row that found hit H by edges and text,
     * 60 a get that targets H, 70 a REST get without a session.
     */
    private suspend fun seed(
        fx: ReportFixture,
        scale: Int
    ) {
        fx.root(r1, "Alpha root")
        fx.root(r2, "Beta root")
        val rows = mutableListOf<CallLogRecord>()
        for (k in 0 until scale) {
            val root =
                when (k % 3) {
                    0 -> r1
                    1 -> r2
                    else -> null
                }
            val session = "s-$k"
            val base = ReportKit.NOW.minus(Duration.ofDays(3)).plusSeconds(k * 100L)
            val key = uuid(1, k.toLong())
            val h = uuid(2, k.toLong())
            val versions = "{\"$key\":1}"
            rows +=
                row(
                    base.plusSeconds(10),
                    "items_read",
                    "get",
                    session,
                    versions = versions,
                    root = root,
                    shape =
                        if (k % 2 ==
                            0
                        ) {
                            "{\"fields\":[\"ancestors\"]}"
                        } else {
                            null
                        }
                )
            rows += row(base.plusSeconds(20), "items_read", "get", session, versions = versions, root = root)
            rows += row(base.plusSeconds(30), "items_write", "update", session, error = "invalid_transition", lf = 1, root = root)
            rows += row(base.plusSeconds(40), "items_read", "next", session, result = 0, eligible = 2, root = root)
            rows += row(base.plusSeconds(50), "items_read", "related", session, hits = hit(h, "edges", "text"), root = root)
            rows += row(base.plusSeconds(60), "items_read", "get", session, ids = "[\"$h\"]", root = root)
            rows += row(base.plusSeconds(70), "items_read", "get", null, surface = CallLogRecord.SURFACE_REST, root = root)
        }
        rows.chunked(100).forEach { fx.seed(*it.toTypedArray()) }
    }

    private class Measured(
        val counts: Map<String, Int>,
        val all: WasteReport,
        val rootR1: WasteReport,
        val scopedR2: WasteReport
    )

    private suspend fun ReportFixture.wasteOk(
        request: ReportWasteRequest = ReportWasteRequest(),
        ctx: CallContext = ctx()
    ): WasteReport =
        when (val outcome = service().waste(ctx, request)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok waste report for $request but got ${outcome.error}")
        }

    private suspend fun measure(scale: Int): Measured {
        val counts = linkedMapOf<String, Int>()
        return SqliteTestDatabase.open(clock = clock).use { d ->
            val fx = ReportFixture(d, clock)
            seed(fx, scale)
            val scopedR1 = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))
            val scopedR2 = fx.ctx(AccessScope.principal(setOf(r2), emptySet()))
            val admin = fx.ctx()

            val cases = linkedMapOf<String, suspend () -> Any?>()
            cases["default window"] = { fx.service().waste(admin, ReportWasteRequest()) }
            cases["rootId full id"] = { fx.service().waste(admin, ReportWasteRequest(rootId = IdRef(r1.toString()))) }
            cases["rootId prefix"] = { fx.service().waste(admin, ReportWasteRequest(rootId = IdRef(r2.toString().take(8)))) }
            cases["root scoped"] = { fx.service().waste(scopedR1, ReportWasteRequest()) }
            cases["root scoped with rootId"] = { fx.service().waste(scopedR2, ReportWasteRequest(rootId = IdRef(r2.toString()))) }
            cases["explicit window"] = {
                fx.service().waste(admin, ReportWasteRequest(since = "2026-10-09T00:00:00Z", until = "2026-10-11T00:00:00Z"))
            }
            cases["window without rows"] = {
                fx.service().waste(admin, ReportWasteRequest(since = "2026-10-01T00:00:00Z", until = "2026-10-02T00:00:00Z"))
            }
            for ((name, call) in cases) {
                call()
                counts[name] = countStatements(d, call)
            }
            Measured(
                counts,
                fx.wasteOk(),
                fx.wasteOk(ReportWasteRequest(rootId = IdRef(r1.toString()))),
                fx.wasteOk(ReportWasteRequest(), scopedR2)
            )
        }
    }

    @Test
    fun `S15 every waste call issues the same number of statements at scale 1 and 40`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            assertEquals(small.counts.keys, large.counts.keys)
            val mismatches = small.counts.keys.filter { small.counts.getValue(it) != large.counts.getValue(it) }
            assertTrue(
                mismatches.isEmpty(),
                "statement count must not depend on data volume:\n" +
                    mismatches.joinToString("\n") { "$it: ${small.counts[it]} vs ${large.counts[it]}" }
            )
            for ((name, count) in small.counts) assertTrue(count > 0, "the interceptor saw no statement for '$name'")
        }

    @Test
    fun `S15 control - the scale changes the data and the rootId and scope calls really filter`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            // per kit: reads 1,2,4,5,6 are ok session reads (5), the write at +30 rescues the two gets before it: 3 unrescued
            assertEquals(5L, small.all.readsWithoutLaterWrite.denominator)
            assertEquals(3L, small.all.readsWithoutLaterWrite.numerator)
            assertEquals(200L, large.all.readsWithoutLaterWrite.denominator, "forty kits of five session reads")
            assertEquals(120L, large.all.readsWithoutLaterWrite.numerator)
            // two observations of the same key and version per kit: one of them redundant
            assertEquals(1L to 2L, small.all.redundantReads.numerator to small.all.redundantReads.denominator)
            assertEquals(40L to 80L, large.all.redundantReads.numerator to large.all.redundantReads.denominator)
            // one empty next, one edges and one text unit followed through, one write with one rejection, per kit
            assertEquals(40L to 40L, large.all.emptyNext.numerator to large.all.emptyNext.denominator)
            assertEquals(
                40L to 40L,
                large.all.relatedFollowThrough
                    .getValue("edges")
                    .let { it.numerator to it.denominator }
            )
            assertEquals(
                40L to 40L,
                large.all.relatedFollowThrough
                    .getValue("text")
                    .let { it.numerator to it.denominator }
            )
            assertEquals(40L to 40L, large.all.lifecycleFreeRejections.let { it.numerator to it.denominator })
            // overFetch: five fetch rows per kit (gets at +10 +20 +60 +70 and the next at +40); fields on even kits: 20 of 40
            assertEquals(1L to 5L, small.all.overFetch.let { it.numerator to it.denominator })
            assertEquals(20L to 200L, large.all.overFetch.let { it.numerator to it.denominator })
            // roots: R1 holds kits 0, 3, ..., 39 (14 of 40) and R2 kits 1, 4, ..., 37 (13 of 40); at scale 1 only kit 0 (R1)
            assertEquals(14L, large.rootR1.emptyNext.denominator)
            assertEquals(1L, small.rootR1.emptyNext.denominator)
            assertEquals(13L, large.scopedR2.emptyNext.denominator)
            assertEquals(0L, small.scopedR2.emptyNext.denominator)
            assertNull(small.scopedR2.emptyNext.value, "no row of R2 at scale 1: no denominator, no value")
        }

    @Test
    fun `S15 control - the interceptor counts ordinary reads`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val fx = ReportFixture(d, clock)
                fx.root(r1, "Alpha root")
                val repo = d.repositoryProvider().workItemRepository()

                val one = countStatements(d) { repo.getById(r1) }
                val three = countStatements(d) { repeat(3) { repo.getById(r1) } }

                assertTrue(one >= 1)
                assertTrue(three >= 3 * one, "three reads count at least three times one: $one vs $three")
            }
        }

    // ------------------------------------------------------------------ scale guard

    @Test
    @Timeout(value = 240, unit = TimeUnit.SECONDS)
    fun `S15 probe about 21000 rows in 50 sessions answer exactly and in bounded time`(): Unit =
        runBlocking {
            SqliteTestDatabase.open(clock = clock).use { d ->
                val fx = ReportFixture(d, clock)
                val rows = ArrayList<CallLogRecord>(21_100)
                val base = ReportKit.NOW.minus(Duration.ofDays(3))
                for (s in 0 until 50) {
                    val session = "g-$s"
                    // 400 gets at base + i seconds, on 200 distinct keys (i mod 200) at version 1; every fourth opts into fields;
                    // the gets at i = 20j + 5 (even j) also target the hit of related row j
                    for (i in 0 until 400) {
                        val key = uuid(1000L + s, (i % 200).toLong())
                        val j = (i - 5) / 20
                        val follows = i >= 5 && (i - 5) % 20 == 0 && j % 2 == 0
                        rows +=
                            row(
                                base.plusSeconds(i.toLong()),
                                "items_read",
                                "get",
                                session,
                                versions = "{\"$key\":1}",
                                ids = if (follows) "[\"${uuid(2000L + s, j.toLong())}\"]" else null,
                                shape = if (i % 4 == 0) "{\"fields\":[\"ancestors\"]}" else null
                            )
                    }
                    // one write at 199.5 seconds
                    rows += row(base.plusSeconds(199).plusMillis(500), "items_write", "update", session)
                    // 20 related rows at 20j + 0.5 seconds, each finding its own hit by edges and text
                    for (j in 0 until 20) {
                        rows +=
                            row(
                                base.plusSeconds(20L * j).plusMillis(500),
                                "items_read",
                                "related",
                                session,
                                hits = hit(uuid(2000L + s, j.toLong()), "edges", "text")
                            )
                    }
                }
                assertEquals(21_050, rows.size, "fixture size")
                rows.chunked(500).forEach { fx.seed(*it.toTypedArray()) }

                val report = fx.wasteOk()

                // per session: observations 400 on 200 distinct (session, key, version); fetch rows 400, 100 with fields;
                // session reads 400 gets + 20 related = 420, the write at 199.5 s rescues the 200 gets at i <= 199 and the 10
                // related rows at j <= 9, so 210 are unrescued; units 20 per signal, the 10 even ones are followed through
                fun pair(signal: WasteSignal) = signal.numerator to signal.denominator
                assertEquals(10_000L to 20_000L, pair(report.redundantReads))
                assertEquals(5_000L to 20_000L, pair(report.overFetch))
                assertEquals(10_500L to 21_000L, pair(report.readsWithoutLaterWrite))
                assertEquals(0L to 0L, pair(report.emptyNext))
                assertEquals(500L to 1_000L, pair(report.relatedFollowThrough.getValue("edges")))
                assertEquals(500L to 1_000L, pair(report.relatedFollowThrough.getValue("text")))
                assertEquals(0L to 0L, pair(report.relatedFollowThrough.getValue("links")))
                assertEquals(0L to 0L, pair(report.relatedFollowThrough.getValue("tags")))
                assertEquals(0L to 50L, pair(report.lifecycleFreeRejections))
            }
        }
}
