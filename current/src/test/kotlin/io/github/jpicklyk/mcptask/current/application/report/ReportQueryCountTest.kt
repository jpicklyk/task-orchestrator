package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.UsageDailyFolder
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report.R11Events
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report.R11Json
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent test (test-author seat, item eadaaa4e, R9) for scenario S15 of the frozen test-plan (NEW-SURFACE): the report
 * keeps a CONSTANT number of SQL statements per call, independent of how many call_log rows, groups, roots and principals the
 * database holds.
 *
 * Oracle: task-scope AC8 ("a constant number of statements per call, `ReportQueryCountTest`"). The expected value is not a
 * number taken from the code: it is the statement count at scale 1 compared with the count at scale 40 of the same kind of
 * data, for the same call.
 *
 * Method: as in `OverviewScopeQueryCountTest`. An Exposed [StatementInterceptor] is registered on the transaction of an OUTER
 * read unit and the call runs inside it (the report's own read unit joins it); each call is run once before the counted run.
 * Scale N means N copies of a kit of six tool groups with different hosts, principals, days, roots (R1, R2 and none) and one
 * run, so scale 1 already holds more groups than a page of five. A control proves the interceptor sees ordinary reads, a second
 * proves the scale really changes the data (more groups at scale 40), and a third that the page, scope and rootId calls really
 * filter, so equal counts are not an inert request. The one-read-unit-per-call property is carried by the equal counts across
 * every request shape (a per-group or per-row read unit would scale), not by a separate unit counter.
 *
 * Forbidden-construct declaration: none.
 *
 * R11 (item 82397512, scenario S14 of its frozen test-plan, NEW-SURFACE): the same equal-count property for `gates`,
 * `contention` and `eventsRetainedSince` at scale 1 and 40 of an events kit, plus an `EXPLAIN QUERY PLAN` check of every
 * SELECT those calls issue (oracle: task-scope AC10, every scan uses an index, no bare `SCAN events`). The statements are
 * captured by the Exposed interceptor, so the plan is that of the SQL production really runs.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ReportQueryCountTest {
    private val clock = SettableClock(ReportKit.NOW)
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2
    private val run = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")

    private class StatementCounter : StatementInterceptor {
        var count = 0
        val sqls = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            count++
            sqls += context.sql(transaction)
        }
    }

    private suspend fun countStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Any?
    ): Int = captureStatements(d, body).count

    private suspend fun captureStatements(
        d: SqliteTestDatabase,
        body: suspend () -> Any?
    ): StatementCounter =
        d.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                body()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter
        }

    /** [scale] copies of a kit of six tool groups; every row is inside the default seven day window. */
    private suspend fun seed(
        fx: ReportFixture,
        scale: Int
    ) {
        fx.root(r1, "Alpha root")
        fx.root(r2, "Beta root")
        var n = 0
        val rows = mutableListOf<CallLogRecord>()
        for (k in 0 until scale) {
            for (j in 0 until 6) {
                val rootId =
                    when (j % 3) {
                        0 -> r1
                        1 -> r2
                        else -> null
                    }
                repeat(j + 1) { c ->
                    rows +=
                        ReportKit.rec(
                            ++n,
                            ReportKit.NOW.minus(Duration.ofHours(((k * 6 + j) % 150).toLong())).minusMillis(1),
                            tool = "tool-$k-$j",
                            operation = "op-${j % 2}",
                            host = "host-$k",
                            surface = if (c % 2 == 0) "mcp" else "rest",
                            principalId = "p-$k-$j",
                            principalKind = if (j % 2 == 0) "subagent" else "user",
                            error = if (c == 1) "e-$j" else null,
                            responseBytes = (c * 4 + 4).toLong(),
                            runId = if (j == 0) run else null,
                            rootId = rootId
                        )
                }
            }
        }
        rows.chunked(100).forEach { fx.seed(*it.toTypedArray()) }
    }

    private class Measured(
        val counts: Map<String, Int>,
        val totalGroups: Int,
        val firstPageSize: Int,
        val scopedGroups: Int,
        val rootGroups: Int,
        val dailyBasis: String,
        val dailyGroups: Int
    )

    private suspend fun measure(scale: Int): Measured {
        val counts = linkedMapOf<String, Int>()
        return SqliteTestDatabase.open(clock = clock).use { d ->
            val fx = ReportFixture(d, clock)
            seed(fx, scale)
            val admin = fx.ctx(principalsVisible = true)
            val scoped = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))
            val first = fx.ok(ReportUsageRequest(limit = 5), admin)
            val cursor = assertNotNull(first.page.next, "scale $scale: the kit holds more groups than one page of five")

            val cases = linkedMapOf<String, suspend () -> Any?>()
            cases["first page"] = { fx.service().usage(admin, ReportUsageRequest(limit = 5)) }
            cases["second page"] = { fx.service().usage(admin, ReportUsageRequest(limit = 5, cursor = cursor)) }
            cases["rootId full id"] = { fx.service().usage(admin, ReportUsageRequest(rootId = IdRef(r1.toString()), limit = 5)) }
            cases["rootId prefix"] = { fx.service().usage(admin, ReportUsageRequest(rootId = IdRef(r2.toString().take(8)), limit = 5)) }
            cases["root scoped"] = { fx.service().usage(scoped, ReportUsageRequest(limit = 5)) }
            cases["root scoped with rootId"] = {
                fx.service().usage(scoped, ReportUsageRequest(rootId = IdRef(r1.toString()), limit = 5))
            }
            for (groupBy in ReportGroupBy.entries.filter { it.dimension != null && it != ReportGroupBy.TOOL }) {
                cases["groupBy ${groupBy.wire}"] = { fx.service().usage(admin, ReportUsageRequest(groupBy = groupBy, limit = 5)) }
            }
            cases["retainedSince"] = { d.unitOfWork().read { d.repositoryProvider().reportQueries().retainedSince() } }
            cases["runCost"] = { d.unitOfWork().read { d.repositoryProvider().reportQueries().runCost(run) } }
            // A window over 31 days is on the daily basis even before any fold exists (raw tail only).
            cases["daily basis 60 days, no fold yet"] = { fx.service().usage(admin, daily60(limit = 5)) }

            for ((name, call) in cases) {
                call()
                counts[name] = countStatements(d, call)
            }
            val scopedFirst = fx.ok(ReportUsageRequest(limit = 200), scoped)
            val rootFirst = fx.ok(ReportUsageRequest(rootId = IdRef(r1.toString()), limit = 200), admin)

            // R13: fold every completed day (the kit's rows are older than today up to six days), then the same 60 day window is
            // served from usage_daily plus the raw tail. Run after the control reads above, which would otherwise turn daily.
            UsageDailyFolder(d.unitOfWork()).foldOnce()
            val dailyFirst = fx.ok(daily60(limit = 5), admin)
            val dailyCursor = assertNotNull(dailyFirst.page.next, "scale $scale: the 60 day window holds more groups than one page of five")
            val dailyCases = linkedMapOf<String, suspend () -> Any?>()
            dailyCases["daily basis 60 days first page"] = { fx.service().usage(admin, daily60(limit = 5)) }
            dailyCases["daily basis 60 days second page"] = { fx.service().usage(admin, daily60(limit = 5, cursor = dailyCursor)) }
            dailyCases["daily basis 60 days rootId"] = { fx.service().usage(admin, daily60(rootId = IdRef(r1.toString()), limit = 5)) }
            dailyCases["daily basis 60 days root scoped"] = { fx.service().usage(scoped, daily60(limit = 5)) }
            dailyCases["daily basis 60 days groupBy day"] = { fx.service().usage(admin, daily60(groupBy = ReportGroupBy.DAY, limit = 5)) }
            dailyCases["daily basis 60 days groupBy host"] = { fx.service().usage(admin, daily60(groupBy = ReportGroupBy.HOST, limit = 5)) }
            for ((name, call) in dailyCases) {
                call()
                counts[name] = countStatements(d, call)
            }
            Measured(
                counts,
                first.page.total,
                first.page.items.size,
                scopedFirst.page.total,
                rootFirst.page.total,
                dailyFirst.window.basis,
                dailyFirst.page.total
            )
        }
    }

    /** A request over the 60 days before the env clock: longer than the 31 day raw cap, so it is served on the daily basis. */
    private fun daily60(
        rootId: IdRef? = null,
        groupBy: ReportGroupBy? = null,
        limit: Int? = null,
        cursor: String? = null
    ) = ReportUsageRequest(rootId, ReportKit.NOW.minus(Duration.ofDays(60)).toString(), null, groupBy, limit, cursor)

    @Test
    fun `S15 every report call issues the same number of statements at scale 1 and 40`(): Unit =
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
    fun `S15 control - the scale changes the data and the scoped and rootId calls really filter`(): Unit =
        runBlocking {
            val small = measure(1)
            val large = measure(40)

            assertEquals(6, small.totalGroups)
            assertEquals(240, large.totalGroups, "forty kits of six tool groups")
            assertEquals(5, large.firstPageSize)
            assertTrue(large.scopedGroups < large.totalGroups, "a root scope hides the groups of R2 and of no root")
            assertEquals(80, large.scopedGroups, "R1 holds two of the six groups of every kit")
            assertEquals(80, large.rootGroups, "the rootId call keeps the same two groups of every kit")
            assertEquals("daily", small.dailyBasis, "the 60 day window is on the daily basis at scale 1")
            assertEquals("daily", large.dailyBasis, "and at scale 40")
            assertEquals(small.totalGroups, small.dailyGroups, "folding changes no group: the daily window sees the same 6 groups")
            assertEquals(large.totalGroups, large.dailyGroups, "and the same 240 groups at scale 40")
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

    // ------------------------------------------------------------------ R11: gates, contention, eventsRetainedSince

    private val gateTypes = setOf(DomainEvent.TRANSITION_REJECTED)
    private val contentionTypes =
        setOf(
            DomainEvent.CLAIM_ACQUIRED,
            DomainEvent.CLAIM_RELEASED,
            DomainEvent.CLAIM_REJECTED,
            DomainEvent.CLAIM_EXPIRED,
            DomainEvent.LEASE_ACQUIRED,
            DomainEvent.LEASE_RELEASED,
            DomainEvent.LEASE_REJECTED,
            DomainEvent.LEASE_EXPIRED
        )

    /**
     * [scale] copies of an events kit. Kit k is one item (child of R1 for even k, of R2 for odd k; tag `api` when k is a
     * multiple of 3, otherwise `beta`; type `ty-k`; traits `tr-k` and `tr-k-b`) with: three `gate_blocked` rejections (keys
     * `nk-k-0` and `nk-k-1`; one with no key at all), one `dependency_unmet`, two claim acquisitions, a claim rejection,
     * expiry and release, and a lease acquired, rejected (keys `lk-k` and `lk-k-b`), expired and force-released under `lk-k`.
     * Every row is inside the default seven day window. So at scale N: gates have 2N + 1 noteKey groups, N type groups, 2N
     * trait groups and 2 errorCode groups; contention has N claim rows and 2N lease rows (3N groups).
     */
    private suspend fun seedEvents(
        d: SqliteTestDatabase,
        scale: Int
    ) {
        val ev = R11Events(d)
        val a = ev.item("Alpha root", id = r1)
        val b = ev.item("Beta root", id = r2)
        val records = mutableListOf<EventRecord>()
        for (k in 0 until scale) {
            val parent = if (k % 2 == 0) a else b
            val item =
                ev.item(
                    "kit-$k",
                    parent = parent,
                    type = "ty-$k",
                    traits = listOf("tr-$k", "tr-$k-b"),
                    tags = if (k % 3 == 0) "api" else "beta"
                )
            val at = ReportKit.NOW.minus(Duration.ofHours((k % 150).toLong())).minusMillis(1)

            fun add(
                type: String,
                data: String
            ) {
                records += ev.record(type, at, item.id, parent.id, data)
            }
            add(DomainEvent.TRANSITION_REJECTED, R11Json.rejected(missingKeys = listOf("nk-$k-0", "nk-$k-1")))
            add(DomainEvent.TRANSITION_REJECTED, R11Json.rejected(missingKeys = listOf("nk-$k-0")))
            add(DomainEvent.TRANSITION_REJECTED, R11Json.rejected(missingKeys = emptyList()))
            add(DomainEvent.TRANSITION_REJECTED, R11Json.rejected(code = "dependency_unmet"))
            repeat(2) { add(DomainEvent.CLAIM_ACQUIRED, R11Json.claimAcquired()) }
            add(DomainEvent.CLAIM_REJECTED, R11Json.claimRejected())
            add(DomainEvent.CLAIM_EXPIRED, R11Json.claimExpired())
            add(DomainEvent.CLAIM_RELEASED, R11Json.claimReleased())
            add(DomainEvent.LEASE_ACQUIRED, R11Json.leaseAcquired("lk-$k"))
            add(DomainEvent.LEASE_REJECTED, R11Json.leaseRejected(listOf("lk-$k", "lk-$k-b")))
            add(DomainEvent.LEASE_EXPIRED, R11Json.leaseExpired("lk-$k"))
            add(DomainEvent.LEASE_RELEASED, R11Json.leaseReleased("lk-$k", forced = true))
        }
        ev.append(records)
    }

    private class Measured11(
        val counts: Map<String, Int>,
        val plans: Map<String, List<String>>,
        val groups: Map<String, Int>
    )

    private fun explain(
        d: SqliteTestDatabase,
        sql: String
    ): List<String> =
        DriverManager.getConnection(d.jdbcUrl).use { c ->
            // Parameters stay unbound (NULL): the planner has no statistics, so the access paths do not depend on the values.
            c.prepareStatement("EXPLAIN QUERY PLAN $sql").use { st ->
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("detail")) } }
            }
        }

    private fun isSelect(sql: String): Boolean =
        sql.trimStart().let {
            it.startsWith("SELECT", ignoreCase = true) ||
                it.startsWith("WITH", ignoreCase = true)
        }

    private suspend fun gatesGroups(
        service: ReportService,
        ctx: CallContext,
        request: ReportGatesRequest
    ): Int = assertIs<Outcome.Ok<GatesReport>>(service.gates(ctx, request)).value.page.total

    private suspend fun contentionGroups(
        service: ReportService,
        ctx: CallContext,
        request: ReportContentionRequest
    ): Int = assertIs<Outcome.Ok<ContentionReport>>(service.contention(ctx, request)).value.page.total

    private suspend fun measure11(scale: Int): Measured11 {
        val counts = linkedMapOf<String, Int>()
        val plans = linkedMapOf<String, List<String>>()
        val groups = linkedMapOf<String, Int>()
        return SqliteTestDatabase.open(clock = clock).use { d ->
            val fx = ReportFixture(d, clock)
            seedEvents(d, scale)
            val admin = fx.ctx(principalsVisible = true)
            val scoped = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))
            val tagged = fx.ctx(AccessScope.principal(null, setOf("api")))
            val service = fx.service()

            val firstGates = assertIs<Outcome.Ok<GatesReport>>(service.gates(admin, ReportGatesRequest(limit = 2))).value
            val gatesCursor = assertNotNull(firstGates.page.next, "scale $scale: the kit holds more noteKey groups than one page of two")
            val firstContention =
                assertIs<Outcome.Ok<ContentionReport>>(
                    service.contention(admin, ReportContentionRequest(limit = 2))
                ).value
            val contentionCursor = assertNotNull(firstContention.page.next, "scale $scale: more contention rows than one page of two")

            val cases = linkedMapOf<String, suspend () -> Any?>()
            cases["gates first page"] = { service.gates(admin, ReportGatesRequest(limit = 2)) }
            cases["gates second page"] = { service.gates(admin, ReportGatesRequest(limit = 2, cursor = gatesCursor)) }
            for (groupBy in listOf(ReportGroupBy.TYPE, ReportGroupBy.TRAIT, ReportGroupBy.ERROR_CODE)) {
                cases["gates groupBy ${groupBy.wire}"] = { service.gates(admin, ReportGatesRequest(groupBy = groupBy, limit = 2)) }
            }
            cases["gates rootId full id"] = { service.gates(admin, ReportGatesRequest(rootId = IdRef(r1.toString()), limit = 2)) }
            cases["gates rootId prefix"] = { service.gates(admin, ReportGatesRequest(rootId = IdRef(r2.toString().take(8)), limit = 2)) }
            cases["gates root scoped"] = { service.gates(scoped, ReportGatesRequest(limit = 2)) }
            cases["gates root scoped with rootId"] = { service.gates(scoped, ReportGatesRequest(rootId = IdRef(r1.toString()), limit = 2)) }
            for (groupBy in listOf(ReportGroupBy.NOTE_KEY, ReportGroupBy.TYPE, ReportGroupBy.TRAIT)) {
                cases["gates tag scoped ${groupBy.wire}"] = { service.gates(tagged, ReportGatesRequest(groupBy = groupBy, limit = 2)) }
            }
            cases["contention first page"] = { service.contention(admin, ReportContentionRequest(limit = 2)) }
            cases["contention second page"] = { service.contention(admin, ReportContentionRequest(limit = 2, cursor = contentionCursor)) }
            cases["contention rootId full id"] =
                { service.contention(admin, ReportContentionRequest(rootId = IdRef(r1.toString()), limit = 2)) }
            cases["contention rootId prefix"] =
                { service.contention(admin, ReportContentionRequest(rootId = IdRef(r2.toString().take(8)), limit = 2)) }
            cases["contention root scoped"] = { service.contention(scoped, ReportContentionRequest(limit = 2)) }
            cases["contention tag scoped"] = { service.contention(tagged, ReportContentionRequest(limit = 2)) }
            cases["eventsRetainedSince gates"] =
                { d.unitOfWork().read { d.repositoryProvider().reportQueries().eventsRetainedSince(gateTypes) } }
            cases["eventsRetainedSince contention"] = {
                d.unitOfWork().read { d.repositoryProvider().reportQueries().eventsRetainedSince(contentionTypes) }
            }

            for ((name, call) in cases) {
                call()
                val captured = captureStatements(d, call)
                counts[name] = captured.count
                plans[name] = captured.sqls.filter(::isSelect).flatMap { sql -> explain(d, sql) }
            }

            groups["gates noteKey"] = firstGates.page.total
            groups["contention"] = firstContention.page.total
            groups["gates type"] = gatesGroups(service, admin, ReportGatesRequest(groupBy = ReportGroupBy.TYPE, limit = 200))
            groups["gates trait"] = gatesGroups(service, admin, ReportGatesRequest(groupBy = ReportGroupBy.TRAIT, limit = 200))
            groups["gates errorCode"] = gatesGroups(service, admin, ReportGatesRequest(groupBy = ReportGroupBy.ERROR_CODE))
            groups["gates root scoped"] = gatesGroups(service, scoped, ReportGatesRequest(limit = 200))
            groups["gates rootId"] = gatesGroups(service, admin, ReportGatesRequest(rootId = IdRef(r1.toString()), limit = 200))
            groups["gates tag scoped"] = gatesGroups(service, tagged, ReportGatesRequest(limit = 200))
            groups["contention tag scoped"] = contentionGroups(service, tagged, ReportContentionRequest(limit = 200))
            groups["contention root scoped"] = contentionGroups(service, scoped, ReportContentionRequest(limit = 200))
            Measured11(counts, plans, groups)
        }
    }

    @Test
    fun `R11 S14 gates, contention and eventsRetainedSince issue the same number of statements at scale 1 and 40`(): Unit =
        runBlocking {
            val small = measure11(1)
            val large = measure11(40)

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
    fun `R11 S14 control - the scale changes the events and the scoped, tag and rootId calls really filter`(): Unit =
        runBlocking {
            val small = measure11(1)
            val large = measure11(40)

            assertEquals(3, small.groups.getValue("gates noteKey"), "two keys and the null group")
            assertEquals(81, large.groups.getValue("gates noteKey"), "forty kits of two keys, plus the null group")
            assertEquals(1, small.groups.getValue("gates type"))
            assertEquals(40, large.groups.getValue("gates type"))
            assertEquals(80, large.groups.getValue("gates trait"))
            assertEquals(2, large.groups.getValue("gates errorCode"))
            assertEquals(120, large.groups.getValue("contention"), "forty claim rows and eighty lease rows")
            assertEquals(41, large.groups.getValue("gates root scoped"), "R1 holds the twenty even kits")
            assertEquals(41, large.groups.getValue("gates rootId"))
            assertEquals(29, large.groups.getValue("gates tag scoped"), "fourteen kits carry the api tag")
            assertEquals(14 * 3, large.groups.getValue("contention tag scoped"))
            assertEquals(20 * 3, large.groups.getValue("contention root scoped"))
        }

    @Test
    fun `R11 S14 every SELECT of gates, contention and eventsRetainedSince reaches events through an index`(): Unit =
        runBlocking {
            val large = measure11(40)

            for ((name, plan) in large.plans) {
                assertTrue(plan.isNotEmpty(), "no plan was captured for '$name'")
                // The aggregate names the table `events` or aliases it `e`; a scan of it without USING is a full table scan.
                val bare = plan.filter { Regex("^SCAN (events|\"events\"|e)( |$)").containsMatchIn(it.trim()) && " USING " !in it }
                val shown = plan.joinToString("\n")
                assertTrue(bare.isEmpty(), "'$name' scans events without an index:\n$shown")
                assertTrue(plan.any { "idx_events_" in it }, "'$name' reaches events through an idx_events_* index:\n$shown")
            }
        }
}
