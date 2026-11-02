package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.ContentionKind
import io.github.jpicklyk.mcptask.current.application.port.ContentionPage
import io.github.jpicklyk.mcptask.current.application.port.ContentionQuery
import io.github.jpicklyk.mcptask.current.application.port.ContentionRow
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.GateDimension
import io.github.jpicklyk.mcptask.current.application.port.GateRow
import io.github.jpicklyk.mcptask.current.application.port.GatesPage
import io.github.jpicklyk.mcptask.current.application.port.GatesQuery
import io.github.jpicklyk.mcptask.current.application.port.NoteWriteDimension
import io.github.jpicklyk.mcptask.current.application.port.NoteWriteRow
import io.github.jpicklyk.mcptask.current.application.port.NoteWritesPage
import io.github.jpicklyk.mcptask.current.application.port.NoteWritesQuery
import io.github.jpicklyk.mcptask.current.application.port.ReportQueries
import io.github.jpicklyk.mcptask.current.application.port.RunCost
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsagePage
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.report.ContentionReport
import io.github.jpicklyk.mcptask.current.application.report.GatesReport
import io.github.jpicklyk.mcptask.current.application.report.NotesReport
import io.github.jpicklyk.mcptask.current.application.report.ReportContentionRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportFixture
import io.github.jpicklyk.mcptask.current.application.report.ReportGatesRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportGroupBy
import io.github.jpicklyk.mcptask.current.application.report.ReportKit
import io.github.jpicklyk.mcptask.current.application.report.ReportNotesRequest
import io.github.jpicklyk.mcptask.current.application.report.ReportUsageRequest
import io.github.jpicklyk.mcptask.current.application.support.IdRef
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.report.noteItem
import io.github.jpicklyk.mcptask.current.test.SettableClock
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

// ====================================================================================================================
// R11 (item 82397512): the gates and contention reads of the ReportQueries port, over the real SQLite database.
// ====================================================================================================================

/** Payload JSON for the event types the gates and contention reads aggregate; the keys follow the declared `payload()` maps. */
internal object R11Json {
    private fun strings(values: List<String>) = JsonArray(values.map { JsonPrimitive(it) })

    fun rejected(
        code: String = "gate_blocked",
        missingKeys: List<String> = emptyList(),
        blockerIds: List<UUID> = emptyList(),
        trigger: String = "start"
    ): String =
        buildJsonObject {
            put("trigger", trigger)
            put("code", code)
            put("missingKeys", strings(missingKeys))
            put("blockerIds", strings(blockerIds.map { it.toString() }))
        }.toString()

    fun claimAcquired(
        holder: String = "agent-1",
        ttlSeconds: Int = 60
    ): String =
        buildJsonObject {
            put("holder", holder)
            put("ttlSeconds", ttlSeconds)
        }.toString()

    fun claimReleased(reason: String = "released"): String = buildJsonObject { put("reason", reason) }.toString()

    fun claimRejected(retryAfterMs: Long = 1000): String = buildJsonObject { put("retryAfterMs", retryAfterMs) }.toString()

    fun claimExpired(holder: String = "agent-1"): String =
        buildJsonObject {
            put("holder", holder)
            put("expiresAt", "2026-10-13T11:00:00Z")
        }.toString()

    fun leaseAcquired(key: String): String =
        buildJsonObject {
            put("key", key)
            put("ttlSeconds", 600)
        }.toString()

    fun leaseReleased(
        key: String?,
        forced: Boolean = false
    ): String =
        buildJsonObject {
            if (key == null) put("key", JsonNull) else put("key", key)
            put("count", 1)
            put("forced", forced)
        }.toString()

    fun leaseRejected(keys: List<String>): String =
        buildJsonObject {
            put("contendedKeys", strings(keys))
            put("retryAfterMs", 1000)
        }.toString()

    fun leaseExpired(key: String): String =
        buildJsonObject {
            put("key", key)
            put("expiresAt", "2026-10-13T11:00:00Z")
        }.toString()
}

/** Seeds work items and `events` rows for the gates and contention tests through the production stores. */
internal class R11Events(
    private val db: SqliteTestDatabase
) {
    private val items get() = db.repositoryProvider().itemStore()
    private val store get() = db.repositoryProvider().eventStore()

    /** A work item; a child's depth and root derive from [parent], so the domain invariants hold by construction. */
    suspend fun item(
        title: String,
        parent: WorkItem? = null,
        type: String? = null,
        traits: List<String> = emptyList(),
        tags: String? = null,
        id: UUID = UUID.randomUUID()
    ): WorkItem =
        items.create(
            WorkItem(
                id = id,
                title = title,
                parentId = parent?.id,
                depth = (parent?.depth ?: -1) + 1,
                rootId = parent?.let { it.rootId ?: it.id } ?: id,
                type = type,
                tags = tags,
                traits = traits
            )
        )

    suspend fun delete(item: WorkItem) {
        items.deleteAll(setOf(item.id))
    }

    fun rootOf(item: WorkItem): UUID = item.rootId ?: item.id

    fun record(
        type: String,
        at: Instant,
        entityId: UUID,
        rootId: UUID,
        data: String
    ): EventRecord =
        EventRecord(
            id = UUID.randomUUID(),
            occurredAt = at,
            rootId = rootId,
            entityKind = DomainEvent.KIND_ITEM,
            entityId = entityId,
            type = type,
            data = data
        )

    /** Appends one event about [entity] (its root is the event's root). */
    suspend fun add(
        type: String,
        at: Instant,
        entity: WorkItem,
        data: String
    ) {
        store.append(listOf(record(type, at, entity.id, rootOf(entity), data)))
    }

    /** Appends one event about an entity that is not (or is no longer) a work item. */
    suspend fun addRaw(
        type: String,
        at: Instant,
        entityId: UUID,
        rootId: UUID,
        data: String
    ) {
        store.append(listOf(record(type, at, entityId, rootId, data)))
    }

    suspend fun append(records: List<EventRecord>) {
        records.chunked(500).forEach { store.append(it) }
    }

    /** [n] copies of one event, so a count is an input the test chose. */
    suspend fun repeat(
        n: Int,
        type: String,
        at: Instant,
        entity: WorkItem,
        data: String
    ) {
        append(List(n) { record(type, at, entity.id, rootOf(entity), data) })
    }
}

/**
 * Independent contract tests (test-author seat, item 82397512, R11) for `ReportQueries.gates`, `contention` and
 * `eventsRetainedSince` over the real SQLite database: the store-level half of scenarios S1 to S5 and S10 to S13 of the frozen
 * test-plan (all NEW-SURFACE). Events are appended through the production [io.github.jpicklyk.mcptask.current.application.port.EventStore]
 * and the port is reached as production reaches it, `repositoryProvider().reportQueries()` inside a read unit.
 *
 * Oracles (task-scope AC1 to AC8, none read from a run): gates count `transition.rejected` rows in `[since, until)`; noteKey,
 * type and trait count only code `gate_blocked`, errorCode groups every code; `blocks` is the number of distinct rejection rows
 * of the group and `items` the number of distinct entity ids; one rejection contributes once to each DISTINCT element of
 * `missingKeys` and an empty list falls in the null-key group; type and trait are joined as of now, a deleted item, a NULL type
 * and an item without traits fall in the null-key group; the contention counters per event type (lease: resource is the payload
 * key, `lease.rejected` once per DISTINCT contended key, released split by `forced`; claim: resource is the entity item id,
 * heartbeat re-claims count as acquired, released by any reason, force-released always 0); the rates are
 * rejected/(acquired+rejected), expired/acquired and forceReleased/acquired, rounded half-up to 4 decimals, omitted when the
 * denominator is 0 and never clamped; order blocks (gates) or rejected (contention) descending, then key ascending with the
 * null key first, then kind ascending; scope: a null root set is unrestricted, an empty set sees nothing, a set sees rows whose
 * `root_id` is in it, a tag set sees rows whose entity item currently carries one of the tags and never a deleted item;
 * retainedSince is the MIN(occurred_at) over the given event types only. The expected numbers are hand sums written next to
 * the rows.
 *
 * Negative-assertion conditions: every exclusion (a row on the window's end, a foreign root, an untagged item, a deleted item,
 * another event type, a non-`gate_blocked` code) has a seeded row that appears in the control query over the same fixture or
 * that the same fixture would count if the rule were dropped.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertion, no not-null-only check standing
 * in for a value.
 *
 * Domain invariants satisfied by construction: work items are built by [R11Events.item] (a child's depth is its parent's depth
 * plus one and its root is the parent's root; trait names are non-blank, trimmed, comma-free and unique within an item); an
 * event's root is the root of its entity item; payloads come from [R11Json].
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class SqliteReportGatesContentionContractTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val ev get() = R11Events(db.db)
    private val now = ReportKit.NOW
    private val rejectedType = DomainEvent.TRANSITION_REJECTED

    private fun ago(d: Duration): Instant = now.minus(d)

    private val hourAgo get() = ago(Duration.ofHours(1))

    private suspend fun <T> inRead(block: suspend (ReportQueries) -> T): T =
        db.unitOfWork().read { block(db.repositoryProvider().reportQueries()) }

    private fun gq(
        groupBy: GateDimension = GateDimension.NOTE_KEY,
        since: Instant = ago(Duration.ofDays(7)),
        until: Instant = now,
        scopeRootIds: Set<UUID>? = null,
        scopeTags: Set<String> = emptySet(),
        rootId: UUID? = null,
        limit: Int = 50,
        offset: Int = 0
    ) = GatesQuery(since, until, groupBy, scopeRootIds, scopeTags, rootId, limit, offset)

    private fun cq(
        since: Instant = ago(Duration.ofDays(7)),
        until: Instant = now,
        scopeRootIds: Set<UUID>? = null,
        scopeTags: Set<String> = emptySet(),
        rootId: UUID? = null,
        limit: Int = 50,
        offset: Int = 0
    ) = ContentionQuery(since, until, scopeRootIds, scopeTags, rootId, limit, offset)

    private suspend fun gates(q: GatesQuery): GatesPage = inRead { it.gates(q) }

    private suspend fun contention(q: ContentionQuery): ContentionPage = inRead { it.contention(q) }

    private fun row(
        kind: ContentionKind,
        resource: String?,
        acquired: Long = 0,
        rejected: Long = 0,
        expired: Long = 0,
        released: Long = 0,
        forceReleased: Long = 0
    ) = ContentionRow(kind, resource, acquired, rejected, expired, released, forceReleased)

    private suspend fun gateBlocked(
        item: WorkItem,
        vararg keys: String,
        at: Instant = hourAgo,
        code: String = "gate_blocked"
    ) = ev.add(rejectedType, at, item, R11Json.rejected(code = code, missingKeys = keys.toList()))

    // ------------------------------------------------------------------ gates S1 / S2

    @Test
    fun `R11 S1 gates by noteKey count one block per rejection and one item per entity, a rejection counts once per key`(): Unit =
        runBlocking {
            val a = ev.item("A")
            val b = ev.item("B")
            gateBlocked(a, "k1", "k2")
            gateBlocked(a, "k1")
            gateBlocked(b, "k1")

            val page = gates(gq())

            assertEquals(listOf(GateRow("k1", 3, 2), GateRow("k2", 1, 1)), page.rows, "k1 leads on blocks; A counts once per rejection")
            assertEquals(2, page.totalGroups)
        }

    @Test
    fun `R11 S2 errorCode groups every code and the other groupings count only gate_blocked`(): Unit =
        runBlocking {
            val a = ev.item("A", type = "t1", traits = listOf("x"))
            val b = ev.item("B", type = "t1", traits = listOf("x", "y"))
            gateBlocked(a, "k1")
            gateBlocked(a, "k1")
            gateBlocked(b, "k1")
            // The dependency_unmet rejection carries a key and sits on typed, traited items, so any grouping that forgot
            // the code filter would count it.
            gateBlocked(a, "k1", code = "dependency_unmet")

            assertEquals(
                listOf(GateRow("gate_blocked", 3, 2), GateRow("dependency_unmet", 1, 1)),
                gates(gq(GateDimension.ERROR_CODE)).rows
            )
            assertEquals(listOf(GateRow("k1", 3, 2)), gates(gq(GateDimension.NOTE_KEY)).rows, "dependency_unmet is not a gate block")
            assertEquals(listOf(GateRow("t1", 3, 2)), gates(gq(GateDimension.TYPE)).rows)
            assertEquals(
                listOf(GateRow("x", 3, 2), GateRow("y", 1, 1)),
                gates(gq(GateDimension.TRAIT)).rows,
                "A is rejected three times (one dependency_unmet), only gate_blocked rows count"
            )
        }

    @Test
    fun `R11 S2 gates ignore every other event type even when its payload looks like a gate block`(): Unit =
        runBlocking {
            val a = ev.item("A", type = "t1", traits = listOf("x"))
            gateBlocked(a, "k1")
            ev.add(DomainEvent.ITEM_TRANSITIONED, hourAgo, a, R11Json.rejected(missingKeys = listOf("k1")))
            ev.add(DomainEvent.CLAIM_REJECTED, hourAgo, a, R11Json.rejected(missingKeys = listOf("k1")))
            ev.add(DomainEvent.LEASE_REJECTED, hourAgo, a, R11Json.leaseRejected(listOf("k1")))

            for (dimension in GateDimension.entries) {
                assertEquals(1L, gates(gq(dimension)).rows.sumOf { it.blocks }, "$dimension counts the one real rejection only")
            }
            assertEquals(listOf(GateRow("k1", 1, 1)), gates(gq()).rows)
        }

    // ------------------------------------------------------------------ gates S3 type and trait as of now

    @Test
    fun `R11 S3 type and trait are joined as of now, absent values fall in the null-key group`(): Unit =
        runBlocking {
            val retyped = ev.item("retyped", type = "t1", traits = listOf("x"))
            val updated =
                assertNotNull(
                    db.repositoryProvider().itemStore().update(retyped.copy(type = "t2", traits = listOf("x", "y")))
                )
            val deleted = ev.item("deleted", type = "t9", traits = listOf("z"))
            val untyped = ev.item("untyped")
            val typedBare = ev.item("typedBare", type = "t1")
            val neverCreated = UUID.randomUUID()
            for (item in listOf(updated, deleted, untyped, typedBare)) gateBlocked(item, "k")
            ev.addRaw(rejectedType, hourAgo, neverCreated, UUID.randomUUID(), R11Json.rejected(missingKeys = listOf("k")))
            ev.delete(deleted)

            val byType = gates(gq(GateDimension.TYPE))
            val byTrait = gates(gq(GateDimension.TRAIT))

            assertEquals(
                listOf(GateRow(null, 3, 3), GateRow("t1", 1, 1), GateRow("t2", 1, 1)),
                byType.rows,
                "deleted item, NULL type and a never-created entity share the null group; the retyped item counts under t2 only"
            )
            assertEquals(
                listOf(GateRow(null, 4, 4), GateRow("x", 1, 1), GateRow("y", 1, 1)),
                byTrait.rows,
                "the retyped item counts once per current trait; deleted, untyped, typedBare and the never-created entity have none"
            )
            assertEquals(3, byType.totalGroups)
            assertEquals(3, byTrait.totalGroups)
        }

    // ------------------------------------------------------------------ gates S12 empty, absent, null, duplicates

    @Test
    fun `R11 S12 an empty, an absent and a null missingKeys fall in the null-key group, a duplicate key counts once`(): Unit =
        runBlocking {
            val a = ev.item("A")
            val b = ev.item("B")
            ev.add(rejectedType, hourAgo, a, R11Json.rejected(missingKeys = emptyList()))
            ev.add(rejectedType, hourAgo, a, """{"trigger":"start","code":"gate_blocked","blockerIds":[]}""")
            ev.add(rejectedType, hourAgo, b, """{"trigger":"start","code":"gate_blocked","missingKeys":null,"blockerIds":[]}""")
            gateBlocked(a, "k1", "k1")
            gateBlocked(b, "k1")

            val page = gates(gq())

            assertEquals(
                listOf(GateRow(null, 3, 2), GateRow("k1", 2, 2)),
                page.rows,
                "three rejections without keys; the duplicated key contributes one block to k1"
            )
            assertEquals(2, page.totalGroups)
        }

    // ------------------------------------------------------------------ gates S10 window

    @Test
    fun `R11 S10 gates window is half open to the millisecond`(): Unit =
        runBlocking {
            val a = ev.item("A")
            val since = ago(Duration.ofDays(2))
            gateBlocked(a, "in_start", at = since)
            gateBlocked(a, "out_before", at = since.minusMillis(1))
            gateBlocked(a, "in_end", at = now.minusMillis(1))
            gateBlocked(a, "out_until", at = now)

            val page = gates(gq(since = since, until = now))

            assertEquals(listOf("in_end", "in_start"), page.rows.map { it.key }, "blocks tie at 1, key ascending")
            assertEquals(2, page.totalGroups)
            assertEquals(
                4,
                gates(gq(since = since.minusMillis(1), until = now.plusMillis(1))).totalGroups,
                "control: the window widened by 1ms on both sides holds all four"
            )
        }

    // ------------------------------------------------------------------ gates S11 scope

    private class Tree(
        val r1: WorkItem,
        val r2: WorkItem,
        val c1: WorkItem
    )

    private suspend fun seedTree(): Tree {
        val r1 = ev.item("Alpha root", id = ReportKit.R1)
        val r2 = ev.item("Beta root", id = ReportKit.R2)
        val c1 = ev.item("Alpha child", parent = r1, id = ReportKit.C1)
        return Tree(r1, r2, c1)
    }

    @Test
    fun `R11 S11 gates root scope keeps the rows of its roots, rootId narrows and intersects`(): Unit =
        runBlocking {
            val t = seedTree()
            gateBlocked(t.r1, "k_r1")
            gateBlocked(t.c1, "k_r1", "k_c1")
            gateBlocked(t.r2, "k_r2")

            assertEquals(setOf("k_r1", "k_c1", "k_r2"), gates(gq(scopeRootIds = null)).rows.map { it.key }.toSet())
            assertEquals(
                listOf(GateRow("k_r1", 2, 2), GateRow("k_c1", 1, 1)),
                gates(gq(scopeRootIds = setOf(ReportKit.R1))).rows,
                "the child's rows are rooted at R1; k_r2 is another root's"
            )
            assertEquals(emptyList(), gates(gq(scopeRootIds = emptySet())).rows)
            assertEquals(0, gates(gq(scopeRootIds = emptySet())).totalGroups)
            assertEquals(listOf(GateRow("k_r2", 1, 1)), gates(gq(rootId = ReportKit.R2)).rows)
            assertEquals(
                emptyList(),
                gates(gq(scopeRootIds = setOf(ReportKit.R1), rootId = ReportKit.R2)).rows,
                "rootId R2 is outside a scope of {R1}: the filters intersect"
            )
            assertEquals(
                listOf(GateRow("k_r2", 1, 1)),
                gates(gq(scopeRootIds = setOf(ReportKit.R1, ReportKit.R2), rootId = ReportKit.R2)).rows
            )
        }

    @Test
    fun `R11 S11 gates tag scope sees only entities that currently carry an allowed tag, a deleted item is invisible`(): Unit =
        runBlocking {
            val api = ev.item("api", tags = "api")
            val listed = ev.item("listed", tags = "x,api")
            val prefix = ev.item("prefix", tags = "apiary")
            val other = ev.item("other", tags = "beta")
            val untagged = ev.item("untagged")
            val gone = ev.item("gone", tags = "api")
            for (item in listOf(api, listed, prefix, other, untagged, gone)) gateBlocked(item, "k")
            ev.delete(gone)

            val tagged = gates(gq(scopeTags = setOf("api")))
            val open = gates(gq())

            assertEquals(
                listOf(GateRow("k", 2, 2)),
                tagged.rows,
                "exact tag membership: api and x,api, not apiary, beta, untagged or deleted"
            )
            assertEquals(listOf(GateRow("k", 6, 6)), open.rows, "control: unrestricted sees all six, the deleted item's row included")
            assertEquals(
                listOf(GateRow("k", 3, 3)),
                gates(gq(scopeTags = setOf("api", "beta"))).rows,
                "any allowed tag matches: api, x,api and beta"
            )
            assertEquals(
                listOf(GateRow("k", 6, 6)),
                gates(
                    gq(
                        scopeRootIds =
                            setOf(
                                ev.rootOf(gone),
                                ev.rootOf(api),
                                ev.rootOf(listed),
                                ev.rootOf(prefix),
                                ev.rootOf(other),
                                ev.rootOf(untagged)
                            )
                    )
                ).rows,
                "control: a root scope is root_id based, so the deleted item's row stays visible to it"
            )
        }

    // ------------------------------------------------------------------ gates S13 order and paging

    @Test
    fun `R11 S13 gates order by blocks descending then key ascending with the null key first, and page by limit and offset`(): Unit =
        runBlocking {
            val a = ev.item("A")
            repeat(3) { gateBlocked(a, "k1") }
            repeat(2) { gateBlocked(a, "k3") }
            repeat(2) { gateBlocked(a, "k2") }
            repeat(2) { ev.add(rejectedType, hourAgo, a, R11Json.rejected(missingKeys = emptyList())) }

            val all = gates(gq())
            assertEquals(listOf("k1", null, "k2", "k3"), all.rows.map { it.key }, "k1 has 3, then the 2-block groups with null first")
            assertEquals(4, all.totalGroups)

            val first = gates(gq(limit = 2, offset = 0))
            val second = gates(gq(limit = 2, offset = 2))
            assertEquals(listOf("k1", null), first.rows.map { it.key })
            assertEquals(listOf("k2", "k3"), second.rows.map { it.key })
            assertEquals(4, second.totalGroups, "the group count does not depend on the slice")
            val beyond = gates(gq(limit = 2, offset = 10))
            assertTrue(beyond.rows.isEmpty())
            assertEquals(4, beyond.totalGroups)
        }

    // ------------------------------------------------------------------ contention S4 lease

    private suspend fun leaseEvent(
        type: String,
        holder: WorkItem,
        data: String,
        at: Instant = hourAgo
    ) = ev.add(type, at, holder, data)

    @Test
    fun `R11 S4 lease rows count each event type per key, a rejection counts once per distinct contended key`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            repeat(2) { leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("a")) }
            leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf("a", "b")))
            leaseEvent(DomainEvent.LEASE_EXPIRED, h, R11Json.leaseExpired("a"))
            leaseEvent(DomainEvent.LEASE_RELEASED, h, R11Json.leaseReleased("a", forced = false))
            leaseEvent(DomainEvent.LEASE_RELEASED, h, R11Json.leaseReleased("a", forced = true))

            val page = contention(cq())

            assertEquals(
                listOf(
                    row(ContentionKind.LEASE, "a", acquired = 2, rejected = 1, expired = 1, released = 1, forceReleased = 1),
                    row(ContentionKind.LEASE, "b", rejected = 1)
                ),
                page.rows,
                "released counts the unforced release only; forceReleased the forced one; the rejection reaches a and b once"
            )
            assertEquals(2, page.totalGroups)
            val a = page.rows[0]
            assertEquals(0.3333, assertNotNull(a.contentionRate), 1e-9, "1 / (2 + 1)")
            assertEquals(0.5, assertNotNull(a.expiryRate), 1e-9, "1 / 2")
            assertEquals(0.5, assertNotNull(a.forceReleaseRate), 1e-9, "1 / 2")
            val b = page.rows[1]
            assertEquals(1.0, assertNotNull(b.contentionRate), 1e-9, "1 / (0 + 1)")
            assertNull(b.expiryRate, "no acquisition: the denominator is 0")
            assertNull(b.forceReleaseRate)
        }

    @Test
    fun `R11 S12 a duplicated contended key counts one rejection for that key`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf("a", "a", "b")))

            assertEquals(
                listOf(row(ContentionKind.LEASE, "a", rejected = 1), row(ContentionKind.LEASE, "b", rejected = 1)),
                contention(cq()).rows
            )
        }

    @Test
    fun `R11 S4 probe a lease released without a key forms the null-resource group`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            leaseEvent(DomainEvent.LEASE_RELEASED, h, R11Json.leaseReleased(null, forced = false))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("a"))

            val rows = contention(cq()).rows

            assertEquals(2, rows.size)
            assertEquals(row(ContentionKind.LEASE, null, released = 1), rows.single { it.resource == null })
            assertEquals(row(ContentionKind.LEASE, "a", acquired = 1), rows.single { it.resource == "a" })
        }

    // ------------------------------------------------------------------ contention S5 claim

    @Test
    fun `R11 S5 claim rows are keyed by the claimed item id, heartbeats count as acquired and any release reason counts`(): Unit =
        runBlocking {
            val i = ev.item("claimed")
            repeat(3) { leaseEvent(DomainEvent.CLAIM_ACQUIRED, i, R11Json.claimAcquired()) }
            leaseEvent(DomainEvent.CLAIM_REJECTED, i, R11Json.claimRejected())
            leaseEvent(DomainEvent.CLAIM_EXPIRED, i, R11Json.claimExpired())
            for (reason in listOf("released", "completed", "forced")) {
                leaseEvent(DomainEvent.CLAIM_RELEASED, i, R11Json.claimReleased(reason))
            }

            val page = contention(cq())

            assertEquals(
                listOf(
                    row(ContentionKind.CLAIM, i.id.toString(), acquired = 3, rejected = 1, expired = 1, released = 3, forceReleased = 0)
                ),
                page.rows,
                "lower-case hyphenated item id; a claim is never force-released"
            )
            val r = page.rows.single()
            assertEquals(0.25, assertNotNull(r.contentionRate), 1e-9, "1 / (3 + 1)")
            assertEquals(0.3333, assertNotNull(r.expiryRate), 1e-9, "1 / 3")
            assertEquals(0.0, assertNotNull(r.forceReleaseRate), 1e-9, "0 / 3 is present as 0, only a zero denominator omits")
        }

    @Test
    fun `R11 S5 claim and lease events of the same entity stay in separate kinds`(): Unit =
        runBlocking {
            val h = ev.item("both")
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, h, R11Json.claimAcquired())
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("gradle"))

            assertEquals(
                setOf(
                    row(ContentionKind.CLAIM, h.id.toString(), acquired = 1),
                    row(ContentionKind.LEASE, "gradle", acquired = 1)
                ),
                contention(cq()).rows.toSet()
            )
        }

    @Test
    fun `R11 S5 contention ignores every other event type`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("a"))
            leaseEvent(rejectedType, h, R11Json.rejected(missingKeys = listOf("a")))
            leaseEvent(DomainEvent.ITEM_TRANSITIONED, h, R11Json.leaseAcquired("a"))

            assertEquals(listOf(row(ContentionKind.LEASE, "a", acquired = 1)), contention(cq()).rows)
        }

    // ------------------------------------------------------------------ contention rates

    @Test
    fun `R11 S4 rates round half up to four decimals, not half even and not down`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            // p: contention 1 / (31 + 1) = 0.03125 -> 0.0313; expiry 1 / 31 = 0.032258 -> 0.0323
            repeat(31) { leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("p")) }
            leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf("p")))
            leaseEvent(DomainEvent.LEASE_EXPIRED, h, R11Json.leaseExpired("p"))
            // q: expiry 1 / 32 = 0.03125 -> 0.0313; force-release 1 / 32 = 0.03125 -> 0.0313
            repeat(32) { leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("q")) }
            leaseEvent(DomainEvent.LEASE_EXPIRED, h, R11Json.leaseExpired("q"))
            leaseEvent(DomainEvent.LEASE_RELEASED, h, R11Json.leaseReleased("q", forced = true))

            val byKey = contention(cq()).rows.associateBy { it.resource }

            assertEquals(0.0313, assertNotNull(byKey.getValue("p").contentionRate), 1e-9)
            assertEquals(0.0323, assertNotNull(byKey.getValue("p").expiryRate), 1e-9)
            assertEquals(0.0313, assertNotNull(byKey.getValue("q").expiryRate), 1e-9)
            assertEquals(0.0313, assertNotNull(byKey.getValue("q").forceReleaseRate), 1e-9)
        }

    @Test
    fun `R11 S4 probe a rate above one is not clamped and a zero denominator omits the rate`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            // c: one acquisition seen, two expiries and two forced releases (the other acquisitions predate the window)
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("c"))
            repeat(2) { leaseEvent(DomainEvent.LEASE_EXPIRED, h, R11Json.leaseExpired("c")) }
            repeat(2) { leaseEvent(DomainEvent.LEASE_RELEASED, h, R11Json.leaseReleased("c", forced = true)) }
            // d: only an expiry, no acquisition in the window
            leaseEvent(DomainEvent.LEASE_EXPIRED, h, R11Json.leaseExpired("d"))

            val byKey = contention(cq()).rows.associateBy { it.resource }

            assertEquals(2.0, assertNotNull(byKey.getValue("c").expiryRate), 1e-9)
            assertEquals(2.0, assertNotNull(byKey.getValue("c").forceReleaseRate), 1e-9)
            assertEquals(0.0, assertNotNull(byKey.getValue("c").contentionRate), 1e-9, "rejected 0 and acquired 1: 0 / 1 is a present zero")
            assertNull(byKey.getValue("d").expiryRate)
            assertNull(byKey.getValue("d").contentionRate)
            assertEquals(row(ContentionKind.LEASE, "d", expired = 1), byKey.getValue("d"))
        }

    @Test
    fun `R11 S4 the computed members of a row follow the rate definitions without a database`() {
        val r = row(ContentionKind.LEASE, "k", acquired = 3, rejected = 1, expired = 1, released = 2, forceReleased = 1)
        assertEquals(4L, r.attempts, "acquired + rejected")
        assertEquals(0.25, assertNotNull(r.contentionRate), 1e-12)
        assertEquals(0.3333, assertNotNull(r.expiryRate), 1e-12)
        assertEquals(0.3333, assertNotNull(r.forceReleaseRate), 1e-12)

        val empty = row(ContentionKind.CLAIM, null)
        assertEquals(0L, empty.attempts)
        assertNull(empty.contentionRate)
        assertNull(empty.expiryRate)
        assertNull(empty.forceReleaseRate)

        val rejectedOnly = row(ContentionKind.CLAIM, "x", rejected = 7)
        assertEquals(1.0, assertNotNull(rejectedOnly.contentionRate), 1e-12)
        assertNull(rejectedOnly.expiryRate)

        val over = row(ContentionKind.LEASE, "y", acquired = 1, expired = 3, forceReleased = 5)
        assertEquals(3.0, assertNotNull(over.expiryRate), 1e-12)
        assertEquals(5.0, assertNotNull(over.forceReleaseRate), 1e-12)
        assertEquals(0.0, assertNotNull(over.contentionRate), 1e-12, "0 / (1 + 0) is a present zero")

        assertEquals("claim", ContentionKind.CLAIM.wire)
        assertEquals("lease", ContentionKind.LEASE.wire)
    }

    // ------------------------------------------------------------------ contention S10 window, S11 scope, S13 order

    @Test
    fun `R11 S10 contention window is half open to the millisecond for every contention type`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            val since = ago(Duration.ofDays(2))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("in_start"), at = since)
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("out_before"), at = since.minusMillis(1))
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, h, R11Json.claimAcquired(), at = now.minusMillis(1))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("out_until"), at = now)

            val page = contention(cq(since = since, until = now))

            assertEquals(setOf("in_start", h.id.toString()), page.rows.map { it.resource }.toSet())
            assertEquals(2, page.totalGroups)
            assertEquals(4, contention(cq(since = since.minusMillis(1), until = now.plusMillis(1))).totalGroups, "control: widened by 1ms")
        }

    @Test
    fun `R11 S11 contention root scope keeps the rows rooted at its roots, a lease row follows its holder's root`(): Unit =
        runBlocking {
            val t = seedTree()
            leaseEvent(DomainEvent.LEASE_ACQUIRED, t.c1, R11Json.leaseAcquired("held_in_r1"))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, t.r2, R11Json.leaseAcquired("held_in_r2"))
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, t.r1, R11Json.claimAcquired())

            assertEquals(3, contention(cq()).totalGroups)
            assertEquals(
                setOf("held_in_r1", t.r1.id.toString()),
                contention(cq(scopeRootIds = setOf(ReportKit.R1))).rows.map { it.resource }.toSet()
            )
            assertEquals(listOf("held_in_r2"), contention(cq(rootId = ReportKit.R2)).rows.map { it.resource })
            assertEquals(emptyList(), contention(cq(scopeRootIds = emptySet())).rows)
            assertEquals(emptyList(), contention(cq(scopeRootIds = setOf(ReportKit.R1), rootId = ReportKit.R2)).rows)
        }

    @Test
    fun `R11 S11 contention tag scope sees only entities that currently carry an allowed tag`(): Unit =
        runBlocking {
            val api = ev.item("api", tags = "api")
            val prefix = ev.item("prefix", tags = "apiary")
            val gone = ev.item("gone", tags = "api")
            leaseEvent(DomainEvent.LEASE_ACQUIRED, api, R11Json.leaseAcquired("l_api"))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, prefix, R11Json.leaseAcquired("l_prefix"))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, gone, R11Json.leaseAcquired("l_gone"))
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, api, R11Json.claimAcquired())
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, gone, R11Json.claimAcquired())
            ev.delete(gone)

            assertEquals(
                setOf("l_api", api.id.toString()),
                contention(cq(scopeTags = setOf("api"))).rows.map { it.resource }.toSet(),
                "apiary and the deleted item are invisible"
            )
            assertEquals(5, contention(cq()).totalGroups, "control: unrestricted sees all five groups")
        }

    @Test
    fun `R11 S13 contention order by rejected descending, resource ascending, kind ascending, and paging`(): Unit =
        runBlocking {
            val h = ev.item("holder", id = UUID.fromString("f0f0f0f0-0000-4000-8000-0000000000f1"))
            val shared = h.id.toString()
            // 2 rejections: lease "z"; 1 rejection each: claim and lease with the same resource string, lease "b"
            repeat(2) { leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf("z"))) }
            leaseEvent(DomainEvent.CLAIM_REJECTED, h, R11Json.claimRejected())
            leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf(shared)))
            leaseEvent(DomainEvent.LEASE_REJECTED, h, R11Json.leaseRejected(listOf("b")))
            leaseEvent(DomainEvent.LEASE_ACQUIRED, h, R11Json.leaseAcquired("a_acquired_only"))

            val all = contention(cq())

            assertEquals(
                listOf(
                    ContentionKind.LEASE to "z",
                    ContentionKind.LEASE to "b",
                    ContentionKind.CLAIM to shared,
                    ContentionKind.LEASE to shared,
                    ContentionKind.LEASE to "a_acquired_only"
                ),
                all.rows.map { it.kind to it.resource },
                "z has 2 rejections; among the rejected=1 rows resource ascending ('b' < uuid) and claim before lease on the equal string"
            )
            assertEquals(5, all.totalGroups)
            assertEquals(all.rows.drop(1).take(2), contention(cq(limit = 2, offset = 1)).rows)
            assertEquals(5, contention(cq(limit = 2, offset = 1)).totalGroups)
            assertTrue(contention(cq(limit = 2, offset = 9)).rows.isEmpty())
        }

    // ------------------------------------------------------------------ eventsRetainedSince

    @Test
    fun `R11 S10 eventsRetainedSince is the oldest row of the given types only and null when there is none`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            assertNull(inRead { it.eventsRetainedSince(setOf(rejectedType)) }, "no event at all")

            val oldestAny = ago(Duration.ofDays(30))
            val oldestRejected = ago(Duration.ofDays(9))
            leaseEvent(DomainEvent.ITEM_TRANSITIONED, h, "{}", at = oldestAny)
            leaseEvent(rejectedType, h, R11Json.rejected(), at = ago(Duration.ofDays(2)))
            leaseEvent(rejectedType, h, R11Json.rejected(), at = oldestRejected)
            leaseEvent(DomainEvent.CLAIM_ACQUIRED, h, R11Json.claimAcquired(), at = ago(Duration.ofDays(20)))

            assertEquals(oldestRejected, inRead { it.eventsRetainedSince(setOf(rejectedType)) }, "older rows of other types are ignored")
            assertEquals(ago(Duration.ofDays(20)), inRead { it.eventsRetainedSince(setOf(DomainEvent.CLAIM_ACQUIRED)) })
            assertEquals(
                ago(Duration.ofDays(20)),
                inRead { it.eventsRetainedSince(setOf(rejectedType, DomainEvent.CLAIM_ACQUIRED)) },
                "the minimum over several types"
            )
            assertNull(inRead { it.eventsRetainedSince(setOf(DomainEvent.LEASE_EXPIRED)) }, "a type with no row")
        }

    @Test
    fun `R11 S10 eventsRetainedSince reads every one of the eight contention types`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            val types =
                listOf(
                    DomainEvent.CLAIM_ACQUIRED,
                    DomainEvent.CLAIM_RELEASED,
                    DomainEvent.CLAIM_REJECTED,
                    DomainEvent.CLAIM_EXPIRED,
                    DomainEvent.LEASE_ACQUIRED,
                    DomainEvent.LEASE_RELEASED,
                    DomainEvent.LEASE_REJECTED,
                    DomainEvent.LEASE_EXPIRED
                )
            types.forEachIndexed { index, type -> leaseEvent(type, h, "{}", at = ago(Duration.ofDays(1L + index))) }

            types.forEachIndexed { index, type ->
                assertEquals(ago(Duration.ofDays(1L + index)), inRead { it.eventsRetainedSince(setOf(type)) }, type)
            }
            assertEquals(ago(Duration.ofDays(8)), inRead { it.eventsRetainedSince(types.toSet()) })
        }
}

/**
 * Independent tests (test-author seat, item 82397512, R11) for `ReportService.gates` and `contention` over the real SQLite
 * database and the real [ReportQueries] port: the service-level half of scenarios S4 to S13 of the frozen test-plan (all
 * NEW-SURFACE except the `usage` half of S7).
 *
 * Oracles (task-scope AC5 to AC9, the same window and paging rules as `usage`, none read from a run): the window is
 * `[since, until)` with until defaulting to the env clock, since to until minus 7 days, at most 31 days, since before until,
 * ISO 8601 with Z or an offset and echoed in UTC, basis `raw`; `retainedSince` is the oldest `occurred_at` over the select's own
 * event types, table-wide and independent of scope, absent when there is none, `truncatedByRetention` only when since is earlier
 * than it; groupBy subsets (gates: noteKey default, type, trait, errorCode; contention: resource; anything else
 * `invalid_request` at `/groupBy`; `usage` still rejects noteKey, type, trait, resource); rootId resolves as in `usage`
 * (outside a root scope forbidden{root}, unknown unrestricted not_found and restricted forbidden, a non-root invalid_request at
 * `/rootId`, an ambiguous prefix ambiguous_id) and narrows further; a root scope sees the rows whose root is in it; a tag scope
 * is NOT forbidden and sees only rows whose entity item currently carries an allowed tag; limit 1..200 default 50, a garbled
 * cursor is `invalid_cursor`, `page.total` is the number of groups on every page.
 *
 * Negative-assertion conditions: every exclusion has a seeded row that would appear without the rule (a row on the window's end,
 * a foreign root, a foreign tag, a deleted item, an older row of another event type, an older call_log row) and a control
 * from the same fixture that returns data.
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: work items from [R11Events.item] (a child's depth is its parent's depth plus one,
 * its root is the parent's root); an event's root is its entity item's root; payloads from [R11Json].
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ReportGatesContentionServiceTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val ev get() = R11Events(db.db)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2
    private val rejectedType = DomainEvent.TRANSITION_REJECTED

    private fun ago(d: Duration): Instant = now.minus(d)

    private val hourAgo get() = ago(Duration.ofHours(1))

    private val gateGroupings = setOf(ReportGroupBy.NOTE_KEY, ReportGroupBy.TYPE, ReportGroupBy.TRAIT, ReportGroupBy.ERROR_CODE)

    private suspend fun gates(
        request: ReportGatesRequest = ReportGatesRequest(),
        ctx: CallContext = fx.ctx()
    ): Outcome<GatesReport> = fx.service().gates(ctx, request)

    private suspend fun contention(
        request: ReportContentionRequest = ReportContentionRequest(),
        ctx: CallContext = fx.ctx()
    ): Outcome<ContentionReport> = fx.service().contention(ctx, request)

    private suspend fun gatesOk(
        request: ReportGatesRequest = ReportGatesRequest(),
        ctx: CallContext = fx.ctx()
    ): GatesReport =
        when (val outcome = gates(request, ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok gates report for $request but got ${outcome.error}")
        }

    private suspend fun contentionOk(
        request: ReportContentionRequest = ReportContentionRequest(),
        ctx: CallContext = fx.ctx()
    ): ContentionReport =
        when (val outcome = contention(request, ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok contention report for $request but got ${outcome.error}")
        }

    private fun err(outcome: Outcome<*>) = assertIs<Outcome.Err>(outcome).error

    private fun gatesRequest(
        since: String? = null,
        until: String? = null,
        groupBy: ReportGroupBy? = null,
        rootId: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ) = ReportGatesRequest(rootId?.let { IdRef(it) }, since, until, groupBy, limit, cursor)

    private fun contentionRequest(
        since: String? = null,
        until: String? = null,
        groupBy: ReportGroupBy? = null,
        rootId: String? = null,
        limit: Int? = null,
        cursor: String? = null
    ) = ReportContentionRequest(rootId?.let { IdRef(it) }, since, until, groupBy, limit, cursor)

    /** Both new selects behind one shape, so a rule that applies to both is asserted on both. */
    private class Call(
        val name: String,
        val run: suspend (since: String?, until: String?, rootId: String?, limit: Int?, cursor: String?, ctx: CallContext) -> Outcome<*>
    )

    private val calls =
        listOf(
            Call("gates") { since, until, rootId, limit, cursor, ctx ->
                gates(gatesRequest(since = since, until = until, rootId = rootId, limit = limit, cursor = cursor), ctx)
            },
            Call("contention") { since, until, rootId, limit, cursor, ctx ->
                contention(contentionRequest(since = since, until = until, rootId = rootId, limit = limit, cursor = cursor), ctx)
            }
        )

    private suspend fun seedOneOfEach(
        holder: WorkItem,
        at: Instant = hourAgo
    ) {
        ev.add(rejectedType, at, holder, R11Json.rejected(missingKeys = listOf("k")))
        ev.add(DomainEvent.LEASE_ACQUIRED, at, holder, R11Json.leaseAcquired("lease-a"))
    }

    // ------------------------------------------------------------------ S4 the shape of an empty answer

    @Test
    fun `R11 S4 with no events both selects answer an empty page over the default window and echo their grouping`(): Unit =
        runBlocking {
            val gatesReport = gatesOk()
            val contentionReport = contentionOk()

            assertEquals(ReportGroupBy.NOTE_KEY, gatesReport.groupBy, "gates default to noteKey")
            assertEquals(emptyList(), gatesReport.page.items)
            assertEquals(0, gatesReport.page.total)
            assertFalse(gatesReport.page.truncated)
            assertNull(gatesReport.page.next)
            assertEquals(emptyList(), contentionReport.page.items)
            assertEquals(0, contentionReport.page.total)
            for (window in listOf(gatesReport.window, contentionReport.window)) {
                assertEquals(Instant.parse("2026-10-06T12:00:00Z"), window.since)
                assertEquals(now, window.until)
                assertEquals("raw", window.basis)
                assertNull(window.retainedSince)
                assertFalse(window.truncatedByRetention)
            }
        }

    @Test
    fun `R11 S4 each gates grouping is echoed and groups its rows`(): Unit =
        runBlocking {
            val a = ev.item("A", type = "t1", traits = listOf("x"))
            ev.add(rejectedType, hourAgo, a, R11Json.rejected(missingKeys = listOf("k1")))

            val expected =
                mapOf(
                    ReportGroupBy.NOTE_KEY to "k1",
                    ReportGroupBy.TYPE to "t1",
                    ReportGroupBy.TRAIT to "x",
                    ReportGroupBy.ERROR_CODE to "gate_blocked"
                )
            assertEquals(gateGroupings, expected.keys, "fixture: every gates grouping has an expectation")
            for ((groupBy, key) in expected) {
                val report = gatesOk(ReportGatesRequest(groupBy = groupBy))
                assertEquals(groupBy, report.groupBy)
                assertEquals(listOf(GateRow(key, 1, 1)), report.page.items, groupBy.wire)
            }
            assertEquals(listOf(GateRow("k1", 1, 1)), gatesOk().page.items, "no groupBy is noteKey")
        }

    @Test
    fun `R11 S4 the service answers the rows the port computes, with the contention rates`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            repeat(2) { ev.add(DomainEvent.LEASE_ACQUIRED, hourAgo, h, R11Json.leaseAcquired("a")) }
            ev.add(DomainEvent.LEASE_REJECTED, hourAgo, h, R11Json.leaseRejected(listOf("a", "b")))
            ev.add(DomainEvent.LEASE_EXPIRED, hourAgo, h, R11Json.leaseExpired("a"))

            val report = contentionOk()

            assertEquals(
                listOf(
                    ContentionRow(ContentionKind.LEASE, "a", acquired = 2, rejected = 1, expired = 1, released = 0, forceReleased = 0),
                    ContentionRow(ContentionKind.LEASE, "b", acquired = 0, rejected = 1, expired = 0, released = 0, forceReleased = 0)
                ),
                report.page.items
            )
            assertEquals(0.3333, assertNotNull(report.page.items[0].contentionRate), 1e-9)
            assertEquals(2, report.page.total)
        }

    // ------------------------------------------------------------------ S7 groupBy subsets

    @Test
    fun `R11 S7 gates accept noteKey, type, trait and errorCode and reject every other grouping at the groupBy pointer`(): Unit =
        runBlocking {
            for (groupBy in ReportGroupBy.entries) {
                val outcome = gates(ReportGatesRequest(groupBy = groupBy))
                if (groupBy in gateGroupings) {
                    assertIs<Outcome.Ok<GatesReport>>(outcome, groupBy.wire)
                } else {
                    val error = err(outcome)
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, groupBy.wire)
                    assertEquals("/groupBy", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field, groupBy.wire)
                }
            }
        }

    @Test
    fun `R11 S7 contention accepts resource and no groupBy and rejects every other grouping at the groupBy pointer`(): Unit =
        runBlocking {
            assertIs<Outcome.Ok<ContentionReport>>(contention(ReportContentionRequest()), "no groupBy")
            for (groupBy in ReportGroupBy.entries) {
                val outcome = contention(ReportContentionRequest(groupBy = groupBy))
                if (groupBy == ReportGroupBy.RESOURCE) {
                    assertIs<Outcome.Ok<ContentionReport>>(outcome, groupBy.wire)
                } else {
                    val error = err(outcome)
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, groupBy.wire)
                    assertEquals("/groupBy", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field, groupBy.wire)
                }
            }
        }

    @Test
    fun `R11 S7 usage keeps its own subset and still rejects the new groupings`(): Unit =
        runBlocking {
            for (groupBy in listOf(ReportGroupBy.NOTE_KEY, ReportGroupBy.TYPE, ReportGroupBy.TRAIT, ReportGroupBy.RESOURCE)) {
                val error = err(fx.usage(ReportUsageRequest(groupBy = groupBy)))
                assertEquals(ErrorCode.INVALID_REQUEST, error.code, groupBy.wire)
                assertEquals("/groupBy", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field, groupBy.wire)
            }
            fx.ok(ReportUsageRequest(groupBy = ReportGroupBy.ERROR_CODE))
            fx.ok(ReportUsageRequest(groupBy = ReportGroupBy.TOOL))
        }

    // ------------------------------------------------------------------ S8 window validation and echo

    @Test
    fun `R11 S8 a window of exactly 31 days is accepted and one millisecond more is invalid, for both selects`(): Unit =
        runBlocking {
            for (call in calls) {
                assertIs<Outcome.Ok<*>>(call.run("2026-09-01T00:00:00Z", "2026-10-02T00:00:00Z", null, null, null, fx.ctx()), call.name)
                val over = err(call.run("2026-09-01T00:00:00Z", "2026-10-02T00:00:00.001Z", null, null, null, fx.ctx()))
                assertEquals(ErrorCode.INVALID_REQUEST, over.code, "${call.name} 31d + 1ms")
                val cap = err(call.run(now.minus(Duration.ofDays(31)).minusMillis(1).toString(), null, null, null, null, fx.ctx()))
                assertEquals(ErrorCode.INVALID_REQUEST, cap.code, "${call.name} the cap also applies against the default until")
                assertIs<Outcome.Ok<*>>(call.run(now.minus(Duration.ofDays(31)).toString(), null, null, null, null, fx.ctx()), call.name)
            }
        }

    @Test
    fun `R11 S8 since at or after until, a malformed instant and a date alone are invalid, for both selects`(): Unit =
        runBlocking {
            for (call in calls) {
                val bad =
                    listOf(
                        "2026-10-05T00:00:00Z" to "2026-10-05T00:00:00Z",
                        "2026-10-06T00:00:00Z" to "2026-10-05T00:00:00Z",
                        now.plusSeconds(1).toString() to null,
                        "yesterday" to "2026-10-05T00:00:00Z",
                        "2026-10-01T00:00:00Z" to "2026-13-45T00:00:00Z",
                        "2026-10-01" to "2026-10-05T00:00:00Z",
                        "2026-10-01T00:00:00" to "2026-10-05T00:00:00Z"
                    )
                for ((since, until) in bad) {
                    val error = err(call.run(since, until, null, null, null, fx.ctx()))
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, "${call.name} since=$since until=$until")
                    assertTrue(assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.isNotEmpty(), "${call.name} $since $until")
                }
                assertIs<Outcome.Ok<*>>(
                    call.run("2026-10-05T00:00:00Z", "2026-10-05T00:00:00.001Z", null, null, null, fx.ctx()),
                    "${call.name} control: one millisecond is a valid window"
                )
            }
        }

    @Test
    fun `R11 S8 the window is echoed in UTC with basis raw, an offset input is converted, until alone defaults since`(): Unit =
        runBlocking {
            val gatesReport = gatesOk(gatesRequest(since = "2026-10-12T14:00:00+02:00", until = "2026-10-13T13:30:00+02:00"))
            val contentionReport = contentionOk(contentionRequest(since = "2026-10-12T14:00:00+02:00", until = "2026-10-13T13:30:00+02:00"))

            for (window in listOf(gatesReport.window, contentionReport.window)) {
                assertEquals(Instant.parse("2026-10-12T12:00:00Z"), window.since)
                assertEquals(Instant.parse("2026-10-13T11:30:00Z"), window.until)
                assertEquals("raw", window.basis)
            }
            assertEquals(Instant.parse("2026-10-03T00:00:00Z"), gatesOk(gatesRequest(until = "2026-10-10T00:00:00Z")).window.since)
            assertEquals(
                Instant.parse("2026-10-03T00:00:00Z"),
                contentionOk(contentionRequest(until = "2026-10-10T00:00:00Z")).window.since
            )
        }

    @Test
    fun `R11 S10 the default window is seven days before now and half open to the millisecond, for both selects`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            val start = ago(Duration.ofDays(7))
            for ((index, at) in listOf(start, start.minusMillis(1), now.minusMillis(1), now).withIndex()) {
                ev.add(rejectedType, at, h, R11Json.rejected(missingKeys = listOf("k$index")))
                ev.add(DomainEvent.LEASE_ACQUIRED, at, h, R11Json.leaseAcquired("l$index"))
            }

            val gatesReport = gatesOk()
            val contentionReport = contentionOk()

            assertEquals(
                listOf("k0", "k2"),
                gatesReport.page.items.map {
                    it.key
                },
                "k0 at since is in, k1 one ms earlier is out, k2 is in, k3 at until is out"
            )
            assertEquals(listOf("l0", "l2"), contentionReport.page.items.map { it.resource })
            assertEquals(2, gatesReport.page.total)
            assertEquals(2, contentionReport.page.total)
            assertEquals(start, gatesReport.window.since)
            assertEquals(now, contentionReport.window.until)
        }

    // ------------------------------------------------------------------ S10 retention label

    @Test
    fun `R11 S10 gates retainedSince is the oldest transition_rejected row, older rows of other types and call_log are ignored`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            fx.seed(ReportKit.rec(1, ago(Duration.ofDays(40)), "old_call"))
            ev.add(DomainEvent.ITEM_TRANSITIONED, ago(Duration.ofDays(30)), h, "{}")
            ev.add(DomainEvent.CLAIM_REJECTED, ago(Duration.ofDays(25)), h, R11Json.claimRejected())
            val oldest = ago(Duration.ofDays(9))
            ev.add(rejectedType, oldest, h, R11Json.rejected(missingKeys = listOf("k")))
            ev.add(rejectedType, ago(Duration.ofDays(2)), h, R11Json.rejected(missingKeys = listOf("k")))

            val before = gatesOk(gatesRequest(since = ago(Duration.ofDays(12)).toString()))
            assertEquals(oldest, before.window.retainedSince)
            assertTrue(before.window.truncatedByRetention, "since is before the oldest rejection")

            val exact = gatesOk(gatesRequest(since = oldest.toString()))
            assertEquals(oldest, exact.window.retainedSince)
            assertFalse(exact.window.truncatedByRetention, "since equal to retainedSince is not truncated")

            val after = gatesOk(gatesRequest(since = ago(Duration.ofDays(8)).toString()))
            assertFalse(after.window.truncatedByRetention)
            assertEquals(oldest, after.window.retainedSince, "retainedSince ignores the window")
        }

    @Test
    fun `R11 S10 gates with only other event types have no retainedSince and are not truncated`(): Unit =
        runBlocking {
            val h = ev.item("holder")
            ev.add(DomainEvent.ITEM_TRANSITIONED, ago(Duration.ofDays(20)), h, "{}")
            ev.add(DomainEvent.CLAIM_ACQUIRED, ago(Duration.ofDays(19)), h, R11Json.claimAcquired())

            val report = gatesOk(gatesRequest(since = ago(Duration.ofDays(25)).toString()))

            assertNull(report.window.retainedSince)
            assertFalse(report.window.truncatedByRetention)
        }

    @Test
    fun `R11 S10 contention retainedSince is the oldest row of its own eight types, each one counts, other types do not`(): Unit =
        runBlocking {
            val contentionTypes =
                listOf(
                    DomainEvent.CLAIM_ACQUIRED,
                    DomainEvent.CLAIM_RELEASED,
                    DomainEvent.CLAIM_REJECTED,
                    DomainEvent.CLAIM_EXPIRED,
                    DomainEvent.LEASE_ACQUIRED,
                    DomainEvent.LEASE_RELEASED,
                    DomainEvent.LEASE_REJECTED,
                    DomainEvent.LEASE_EXPIRED
                )
            for (type in contentionTypes) {
                SqliteTestDatabase.open(clock = clock).use { d ->
                    val events = R11Events(d)
                    val holder = events.item("holder")
                    events.add(DomainEvent.ITEM_TRANSITIONED, ago(Duration.ofDays(20)), holder, "{}")
                    events.add(rejectedType, ago(Duration.ofDays(19)), holder, R11Json.rejected())
                    events.add(type, ago(Duration.ofDays(3)), holder, "{}")
                    val service = ReportFixture(d, clock).service()

                    val outcome = service.contention(fx.ctx(), contentionRequest(since = ago(Duration.ofDays(10)).toString()))

                    val report = assertIs<Outcome.Ok<ContentionReport>>(outcome, type).value
                    assertEquals(ago(Duration.ofDays(3)), report.window.retainedSince, type)
                    assertTrue(report.window.truncatedByRetention, type)
                }
            }
            val noContention = SqliteTestDatabase.open(clock = clock)
            noContention.use { d ->
                val events = R11Events(d)
                val holder = events.item("holder")
                events.add(DomainEvent.ITEM_TRANSITIONED, ago(Duration.ofDays(20)), holder, "{}")
                events.add(rejectedType, ago(Duration.ofDays(19)), holder, R11Json.rejected())

                val report =
                    assertIs<Outcome.Ok<ContentionReport>>(
                        ReportFixture(d, clock).service().contention(fx.ctx(), ReportContentionRequest())
                    ).value

                assertNull(report.window.retainedSince, "transition.rejected and item.transitioned are not contention types")
                assertFalse(report.window.truncatedByRetention)
            }
        }

    @Test
    fun `R11 S10 retainedSince is table-wide, a root scope sees the oldest row of another root`(): Unit =
        runBlocking {
            val a = ev.item("Alpha root", id = r1)
            val b = ev.item("Beta root", id = r2)
            val elsewhere = ago(Duration.ofDays(4))
            ev.add(rejectedType, elsewhere, b, R11Json.rejected(missingKeys = listOf("k_r2")))
            ev.add(rejectedType, ago(Duration.ofDays(1)), a, R11Json.rejected(missingKeys = listOf("k_r1")))
            ev.add(DomainEvent.LEASE_ACQUIRED, elsewhere, b, R11Json.leaseAcquired("l_r2"))
            ev.add(DomainEvent.LEASE_ACQUIRED, ago(Duration.ofDays(1)), a, R11Json.leaseAcquired("l_r1"))
            val scoped = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))

            val gatesReport = gatesOk(ReportGatesRequest(), scoped)
            val contentionReport = contentionOk(ReportContentionRequest(), scoped)

            assertEquals(elsewhere, gatesReport.window.retainedSince)
            assertEquals(elsewhere, contentionReport.window.retainedSince)
            assertEquals(listOf("k_r1"), gatesReport.page.items.map { it.key })
            assertEquals(listOf("l_r1"), contentionReport.page.items.map { it.resource })
        }

    // ------------------------------------------------------------------ S9 rootId

    private suspend fun seedRootTree(): List<WorkItem> {
        val a = ev.item("Alpha root", id = r1, tags = "api")
        val b = ev.item("Beta root", id = r2, tags = "beta")
        val c = ev.item("Alpha child", parent = a, id = ReportKit.C1)
        for (holder in listOf(a, b, c)) {
            ev.add(rejectedType, hourAgo, holder, R11Json.rejected(missingKeys = listOf("k_${holder.title}")))
            ev.add(DomainEvent.LEASE_ACQUIRED, hourAgo, holder, R11Json.leaseAcquired("l_${holder.title}"))
        }
        return listOf(a, b, c)
    }

    @Test
    fun `R11 S9 a rootId outside the root scope is forbidden at the root scope, the own root answers`(): Unit =
        runBlocking {
            seedRootTree()
            val inR1 = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))
            for (call in calls) {
                val error = err(call.run(null, null, r2.toString(), null, null, inR1))

                assertEquals(ErrorCode.FORBIDDEN, error.code, call.name)
                assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(error.detail).scope, call.name)
                assertIs<Outcome.Ok<*>>(call.run(null, null, r1.toString(), null, null, inR1), "${call.name} control: the own root answers")
            }
        }

    @Test
    fun `R11 S9 an unknown rootId is not_found unrestricted and forbidden under a restricted scope`(): Unit =
        runBlocking {
            seedRootTree()
            val unknown = "99999999-9999-4999-8999-999999999999"
            val restricted = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))
            for (call in calls) {
                assertEquals(ErrorCode.NOT_FOUND, err(call.run(null, null, unknown, null, null, fx.ctx())).code, call.name)
                assertEquals(ErrorCode.FORBIDDEN, err(call.run(null, null, unknown, null, null, restricted)).code, call.name)
            }
        }

    @Test
    fun `R11 S9 a rootId that is not a depth-0 item is invalid_request at the rootId pointer`(): Unit =
        runBlocking {
            seedRootTree()
            for (call in calls) {
                for (ctx in listOf(fx.ctx(), fx.ctx(AccessScope.principal(setOf(r1), emptySet())))) {
                    val error = err(call.run(null, null, ReportKit.C1.toString(), null, null, ctx))

                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, call.name)
                    assertEquals("/rootId", assertIs<ErrorDetail.InvalidRequest>(error.detail).fields.single().field, call.name)
                }
            }
        }

    @Test
    fun `R11 S9 an eight hex character prefix resolves, an ambiguous prefix is ambiguous_id`(): Unit =
        runBlocking {
            seedRootTree()
            ev.item("Alpha sibling", id = UUID.fromString("a1a1a1a1-1111-4111-8111-111111111111"))
            for (call in calls) {
                assertEquals(ErrorCode.AMBIGUOUS_ID, err(call.run(null, null, "a1a1a1a1", null, null, fx.ctx())).code, call.name)
                assertIs<Outcome.Ok<*>>(call.run(null, null, r2.toString().take(8), null, null, fx.ctx()), "${call.name} unique prefix")
                assertIs<Outcome.Ok<*>>(call.run(null, null, r2.toString().uppercase(), null, null, fx.ctx()), "${call.name} upper-case id")
            }
        }

    @Test
    fun `R11 S9 rootId narrows the rows to that root, by full id and by prefix`(): Unit =
        runBlocking {
            seedRootTree()

            assertEquals(
                setOf("k_Alpha root", "k_Alpha child", "k_Beta root"),
                gatesOk()
                    .page.items
                    .map { it.key }
                    .toSet(),
                "control"
            )
            for (ref in listOf(r2.toString(), r2.toString().take(8))) {
                assertEquals(listOf("k_Beta root"), gatesOk(gatesRequest(rootId = ref)).page.items.map { it.key }, ref)
                assertEquals(listOf("l_Beta root"), contentionOk(contentionRequest(rootId = ref)).page.items.map { it.resource }, ref)
            }
            assertEquals(
                setOf("k_Alpha root", "k_Alpha child"),
                gatesOk(gatesRequest(rootId = r1.toString()))
                    .page.items
                    .map { it.key }
                    .toSet(),
                "the child's rows are rooted at its parent"
            )
        }

    // ------------------------------------------------------------------ S11 scope

    @Test
    fun `R11 S11 a root scope sees only the rows of its roots, in the rows and in the total`(): Unit =
        runBlocking {
            seedRootTree()
            val inR1 = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))

            val gatesReport = gatesOk(ReportGatesRequest(), inR1)
            val contentionReport = contentionOk(ReportContentionRequest(), inR1)

            assertEquals(
                setOf("k_Alpha root", "k_Alpha child"),
                gatesReport.page.items
                    .map { it.key }
                    .toSet()
            )
            assertEquals(2, gatesReport.page.total)
            assertEquals(
                setOf("l_Alpha root", "l_Alpha child"),
                contentionReport.page.items
                    .map { it.resource }
                    .toSet()
            )
            assertEquals(2, contentionReport.page.total)
            assertEquals(3, gatesOk().page.total, "control: the unrestricted caller sees all three groups")
            assertEquals(3, contentionOk().page.total)
            val none = fx.ctx(AccessScope.principal(emptySet(), emptySet()))
            assertEquals(0, gatesOk(ReportGatesRequest(), none).page.total, "an empty root set sees nothing and is not forbidden")
            assertEquals(0, contentionOk(ReportContentionRequest(), none).page.total)
        }

    @Test
    fun `R11 S11 a tag-scoped caller is not forbidden and sees only entities that currently carry an allowed tag`(): Unit =
        runBlocking {
            val api = ev.item("api", tags = "api")
            val listed = ev.item("listed", tags = "x,api")
            val prefix = ev.item("prefix", tags = "apiary")
            val untagged = ev.item("untagged")
            val gone = ev.item("gone", tags = "api")
            for (holder in listOf(api, listed, prefix, untagged, gone)) {
                ev.add(rejectedType, hourAgo, holder, R11Json.rejected(missingKeys = listOf("k")))
                ev.add(DomainEvent.LEASE_ACQUIRED, hourAgo, holder, R11Json.leaseAcquired("l_${holder.title}"))
            }
            ev.delete(gone)
            val tagged = fx.ctx(AccessScope.principal(null, setOf("api")))

            val gatesReport = gatesOk(ReportGatesRequest(), tagged)
            val contentionReport = contentionOk(ReportContentionRequest(), tagged)

            assertEquals(
                listOf(GateRow("k", 2, 2)),
                gatesReport.page.items,
                "api and x,api only; apiary, untagged and the deleted item are invisible"
            )
            assertEquals(
                setOf("l_api", "l_listed"),
                contentionReport.page.items
                    .map { it.resource }
                    .toSet()
            )
            assertEquals(listOf(GateRow("k", 5, 5)), gatesOk().page.items, "control: unrestricted sees all five rejections")
            assertEquals(5, contentionOk().page.total)
        }

    // ------------------------------------------------------------------ S13 paging

    private suspend fun seedFiveGateGroups() {
        val a = ev.item("A")
        for ((key, blocks) in listOf("g1" to 5, "g2" to 4, "g3" to 3, "g4" to 2, "g5" to 1)) {
            ev.repeat(blocks, rejectedType, hourAgo, a, R11Json.rejected(missingKeys = listOf(key)))
        }
    }

    private suspend fun seedFiveLeaseGroups() {
        val a = ev.item("A")
        for ((key, rejections) in listOf("g1" to 5, "g2" to 4, "g3" to 3, "g4" to 2, "g5" to 1)) {
            ev.repeat(rejections, DomainEvent.LEASE_REJECTED, hourAgo, a, R11Json.leaseRejected(listOf(key)))
        }
    }

    @Test
    fun `R11 S13 gates limit 2 over five groups pages 2, 2, 1 with the total 5 and a next cursor until the last page`(): Unit =
        runBlocking {
            seedFiveGateGroups()

            val p1 = gatesOk(ReportGatesRequest(limit = 2))
            assertEquals(listOf("g1", "g2"), p1.page.items.map { it.key })
            assertEquals(5, p1.page.total)
            assertTrue(p1.page.truncated)
            val p2 = gatesOk(ReportGatesRequest(limit = 2, cursor = assertNotNull(p1.page.next)))
            assertEquals(listOf("g3", "g4"), p2.page.items.map { it.key })
            assertEquals(5, p2.page.total, "total is the group count on every page")
            assertTrue(p2.page.truncated)
            val p3 = gatesOk(ReportGatesRequest(limit = 2, cursor = assertNotNull(p2.page.next)))
            assertEquals(listOf("g5"), p3.page.items.map { it.key })
            assertFalse(p3.page.truncated)
            assertNull(p3.page.next)
            for (page in listOf(p1, p2, p3)) assertEquals(p1.window, page.window, "every page carries the same window")
            assertEquals(
                listOf("g1", "g2", "g3", "g4", "g5"),
                gatesOk().page.items.map { it.key },
                "control: the default limit holds all five"
            )
        }

    @Test
    fun `R11 S13 contention limit 2 over five groups pages 2, 2, 1 with the total 5 and a next cursor until the last page`(): Unit =
        runBlocking {
            seedFiveLeaseGroups()

            val p1 = contentionOk(ReportContentionRequest(limit = 2))
            assertEquals(listOf("g1", "g2"), p1.page.items.map { it.resource })
            assertEquals(5, p1.page.total)
            assertTrue(p1.page.truncated)
            val p2 = contentionOk(ReportContentionRequest(limit = 2, cursor = assertNotNull(p1.page.next)))
            assertEquals(listOf("g3", "g4"), p2.page.items.map { it.resource })
            val p3 = contentionOk(ReportContentionRequest(limit = 2, cursor = assertNotNull(p2.page.next)))
            assertEquals(listOf("g5"), p3.page.items.map { it.resource })
            assertFalse(p3.page.truncated)
            assertNull(p3.page.next)
            assertEquals(5, p3.page.total)
        }

    @Test
    fun `R11 S13 the limit bounds 1 and 200 are accepted, 0 and 201 are invalid and a garbled cursor is invalid_cursor`(): Unit =
        runBlocking {
            seedFiveGateGroups()
            for (call in calls) {
                assertIs<Outcome.Ok<*>>(call.run(null, null, null, 1, null, fx.ctx()), "${call.name} limit 1")
                assertIs<Outcome.Ok<*>>(call.run(null, null, null, 200, null, fx.ctx()), "${call.name} limit 200")
                for (limit in listOf(0, 201, -1)) {
                    val error = err(call.run(null, null, null, limit, null, fx.ctx()))
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, "${call.name} limit $limit")
                }
                for (cursor in listOf("!!!not-a-cursor", "", "bm90IGpzb24")) {
                    assertEquals(
                        ErrorCode.INVALID_CURSOR,
                        err(call.run(null, null, null, null, cursor, fx.ctx())).code,
                        "${call.name} cursor '$cursor'"
                    )
                }
            }
        }

    @Test
    fun `R11 S13 the same call twice answers identical data`(): Unit =
        runBlocking {
            val a = ev.item("A")
            seedOneOfEach(a)

            assertEquals(gatesOk().page.items, gatesOk().page.items)
            assertEquals(contentionOk().page.items, contentionOk().page.items)
        }
}

// ====================================================================================================================
// R12 (item d7b2a970): the `notes` select of the report tool, over the real SQLite database.
// ====================================================================================================================

/** Payload JSON of a `note.upserted` event; the keys follow the declared `NoteUpserted` payload map. */
internal object R12Json {
    fun noteUpserted(
        itemId: UUID,
        key: String,
        role: String,
        bodyLength: Int,
        bodyFromFile: String? = null
    ): String =
        buildJsonObject {
            put("itemId", itemId.toString())
            put("key", key)
            put("role", role)
            put("bodyLength", bodyLength)
            if (bodyFromFile != null) put("bodyFromFile", bodyFromFile)
        }.toString()
}

/** Builds `note.upserted` event rows: the entity is the NOTE (its id derives from item and key), the payload names the item. */
internal class R12Notes(
    private val ev: R11Events
) {
    /** The note id for an (item, key) pair: stable, and never equal to the item id. */
    fun noteId(
        itemId: UUID,
        key: String
    ): UUID = UUID.nameUUIDFromBytes("r12-note/$itemId/$key".toByteArray())

    fun write(
        item: WorkItem,
        key: String,
        role: String,
        bytes: Int,
        at: Instant,
        seat: String? = null,
        principalKind: String? = null,
        bodyFromFile: String? = null,
        type: String = DomainEvent.NOTE_UPSERTED
    ): EventRecord = writeFor(item.id, ev.rootOf(item), key, role, bytes, at, seat, principalKind, bodyFromFile, type)

    fun writeFor(
        itemId: UUID,
        rootId: UUID,
        key: String,
        role: String,
        bytes: Int,
        at: Instant,
        seat: String? = null,
        principalKind: String? = null,
        bodyFromFile: String? = null,
        type: String = DomainEvent.NOTE_UPSERTED
    ): EventRecord =
        EventRecord(
            id = UUID.randomUUID(),
            occurredAt = at,
            rootId = rootId,
            entityKind = DomainEvent.KIND_NOTE,
            entityId = noteId(itemId, key),
            type = type,
            principalKind = principalKind,
            seat = seat,
            data = R12Json.noteUpserted(itemId, key, role, bytes, bodyFromFile)
        )
}

/**
 * Independent contract tests (test-author seat, item d7b2a970, R12) for `ReportQueries.noteWrites` over the real SQLite
 * database: the store-level half of scenarios S1 to S7 of the frozen test-plan (all NEW-SURFACE). Events are appended through
 * the production event store and the port is reached as production reaches it, `repositoryProvider().reportQueries()` inside a
 * read unit.
 *
 * Oracles (task-scope Wire and Filters clauses, none read from a run): the select aggregates `note.upserted` events in
 * `[since, until)`; `writes` is the number of events, `notes` the number of DISTINCT entity ids (the note id), `bytes` the sum of
 * `data.bodyLength` and `maxBytes` its maximum; the groupings are role (`data.role`), noteKey (`data.key`), seat (`events.seat`),
 * type (the item's CURRENT type joined through `data.itemId`, never the entity id), principalKind (`events.principal_kind`) and
 * day (the UTC date of `occurred_at`); a NULL value and a deleted item fall in the null-key group; rows order by bytes
 * descending then key ascending with the null key first; `totalGroups` counts all groups whatever the page; a null root set is
 * unrestricted, an empty set sees nothing, a set sees events whose `root_id` is in it, a tag set sees events whose item currently
 * carries one of the tags and never a deleted item; `anchorId` keeps the events whose item is in the anchor's subtree, the anchor
 * included, a deleted item excluded; `rootId` and `anchorId` together intersect. The expected numbers are hand sums written next
 * to the rows.
 *
 * Negative-assertion conditions: every exclusion (a row on the window's end, another event type, a sibling, a foreign root, a
 * deleted item, a retagged item) has a seeded row that the control query over the same fixture counts, so dropping the rule
 * changes the answer.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertion, no not-null-only check standing
 * in for a value.
 *
 * Domain invariants satisfied by construction: work items come from [R11Events.item] (a child's depth is its parent's depth plus
 * one and its root is the parent's root); an event's root is its item's root; the note id is derived from item and key; payloads
 * come from [R12Json].
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class SqliteReportNotesContractTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val ev get() = R11Events(db.db)
    private val nt get() = R12Notes(ev)
    private val now = ReportKit.NOW

    private fun ago(d: Duration): Instant = now.minus(d)

    private val hourAgo get() = ago(Duration.ofHours(1))

    private suspend fun <T> inRead(block: suspend (ReportQueries) -> T): T =
        db.unitOfWork().read { block(db.repositoryProvider().reportQueries()) }

    private fun nq(
        groupBy: NoteWriteDimension = NoteWriteDimension.ROLE,
        since: Instant = ago(Duration.ofDays(7)),
        until: Instant = now,
        scopeRootIds: Set<UUID>? = null,
        scopeTags: Set<String> = emptySet(),
        rootId: UUID? = null,
        anchorId: UUID? = null,
        limit: Int = 50,
        offset: Int = 0
    ) = NoteWritesQuery(since, until, groupBy, scopeRootIds, scopeTags, rootId, anchorId, limit, offset)

    private suspend fun noteWrites(q: NoteWritesQuery): NoteWritesPage = inRead { it.noteWrites(q) }

    private fun row(
        key: String?,
        writes: Long,
        notes: Long,
        bytes: Long,
        maxBytes: Long
    ) = NoteWriteRow(key, writes, notes, bytes, maxBytes)

    private suspend fun append(vararg records: EventRecord) = ev.append(records.toList())

    private class Mixed(
        val i1: WorkItem,
        val i2: WorkItem
    )

    /**
     * The mixed fixture, all inside the default window. Writes (item, key, role, bodyLength, seat, principalKind, time):
     * a1 (i1, k1, queue, 100, impl, agent, 2026-10-11T23:59:59.999Z); a2 (i1, k1, queue, 300, impl, agent, 2026-10-12T00:00:00Z);
     * b1 (i1, k2, work, 50, no seat, user, 2026-10-12T08:00Z); c1 (i2, k3, work, 1000, rev, no kind, 2026-10-12T09:00Z); d1
     * (i2, k1, queue, 7, empty-string seat, agent, 2026-10-12T10:00Z).
     */
    private suspend fun seedMixed(): Mixed {
        val i1 = ev.item("I1")
        val i2 = ev.item("I2")
        append(
            nt.write(i1, "k1", "queue", 100, Instant.parse("2026-10-11T23:59:59.999Z"), seat = "impl", principalKind = "agent"),
            nt.write(i1, "k1", "queue", 300, Instant.parse("2026-10-12T00:00:00Z"), seat = "impl", principalKind = "agent"),
            nt.write(i1, "k2", "work", 50, Instant.parse("2026-10-12T08:00:00Z"), seat = null, principalKind = "user"),
            nt.write(i2, "k3", "work", 1000, Instant.parse("2026-10-12T09:00:00Z"), seat = "rev", principalKind = null),
            nt.write(i2, "k1", "queue", 7, Instant.parse("2026-10-12T10:00:00Z"), seat = "", principalKind = "agent")
        )
        return Mixed(i1, i2)
    }

    // ------------------------------------------------------------------ S1 role

    @Test
    fun `R12 S1 by role a write is an event, a note is a distinct note id, bytes sum and max, ordered by bytes descending`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val i2 = ev.item("I2")
            append(
                nt.write(i1, "k1", "queue", 100, hourAgo),
                nt.write(i1, "k1", "queue", 200, hourAgo),
                nt.write(i1, "k1", "queue", 300, hourAgo),
                nt.write(i1, "k2", "queue", 50, hourAgo),
                nt.write(i2, "k3", "work", 1000, hourAgo)
            )

            val page = noteWrites(nq())

            assertEquals(
                listOf(row("work", 1, 1, 1000, 1000), row("queue", 4, 2, 650, 300)),
                page.rows,
                "work leads on bytes; queue has 4 events over the 2 notes (i1 k1, i1 k2), 100+200+300+50 bytes, maximum 300"
            )
            assertEquals(2, page.totalGroups)
        }

    @Test
    fun `R12 S1 the default grouping of the port query is whatever the caller passes, role is the plain dimension`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(nt.write(i1, "k1", "queue", 10, hourAgo), nt.write(i1, "k1", "work", 20, hourAgo))

            assertEquals(listOf(row("work", 1, 1, 20, 20), row("queue", 1, 1, 10, 10)), noteWrites(nq(NoteWriteDimension.ROLE)).rows)
        }

    @Test
    fun `R12 S1 probe a zero-length body counts as a write with zero bytes`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(nt.write(i1, "k1", "queue", 0, hourAgo))

            assertEquals(listOf(row("queue", 1, 1, 0, 0)), noteWrites(nq()).rows)
        }

    @Test
    fun `R12 S1 probe a replayed write is two recorded events of one note, a write from a file counts like an inline one`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            // Two events with identical payloads are two recorded writes (the report counts events, not calls) of one note.
            append(
                nt.write(i1, "k1", "queue", 40, hourAgo),
                nt.write(i1, "k1", "queue", 40, hourAgo),
                nt.write(i1, "k2", "queue", 5, hourAgo, bodyFromFile = "docs/body.md")
            )

            assertEquals(listOf(row("queue", 3, 2, 85, 40)), noteWrites(nq()).rows)
        }

    @Test
    fun `R12 S1 probe keys that differ only by case are two groups, the same key on two items is one group of two notes`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val i2 = ev.item("I2")
            append(
                nt.write(i1, "Spec", "queue", 10, hourAgo),
                nt.write(i1, "spec", "queue", 20, hourAgo),
                nt.write(i2, "spec", "queue", 30, hourAgo)
            )

            assertEquals(
                listOf(row("spec", 2, 2, 50, 30), row("Spec", 1, 1, 10, 10)),
                noteWrites(nq(NoteWriteDimension.NOTE_KEY)).rows,
                "spec on two items is two notes in one group; Spec is another group"
            )
        }

    @Test
    fun `R12 S1 a repeated query over unchanged data answers the same rows`(): Unit =
        runBlocking {
            seedMixed()

            val first = noteWrites(nq())
            val second = noteWrites(nq())

            assertEquals(first, second)
        }

    // ------------------------------------------------------------------ S2 the other groupings

    @Test
    fun `R12 S2 by noteKey the key is data key and the same key on two items is one group`(): Unit =
        runBlocking {
            seedMixed()

            assertEquals(
                listOf(row("k3", 1, 1, 1000, 1000), row("k1", 3, 2, 407, 300), row("k2", 1, 1, 50, 50)),
                noteWrites(nq(NoteWriteDimension.NOTE_KEY)).rows,
                "k1: a1, a2 on i1 and d1 on i2 = 3 events over 2 notes, 100+300+7 bytes"
            )
        }

    @Test
    fun `R12 S2 by seat a NULL seat is the null key`(): Unit =
        runBlocking {
            seedMixed()

            val page = noteWrites(nq(NoteWriteDimension.SEAT))

            assertEquals(
                listOf(row("rev", 1, 1, 1000, 1000), row("impl", 2, 1, 400, 300)),
                page.rows.filter { it.key == "rev" || it.key == "impl" },
                "rev (c1) and impl (a1, a2: one note written twice) are named seats"
            )
            assertTrue(page.rows.any { it.key == null && it.maxBytes == 50L }, "b1 has no seat: its 50-byte write is in the null group")
        }

    @Test
    fun `R12 S2 probe an empty-string seat is a key of its own, distinct from the NULL seat`(): Unit =
        runBlocking {
            seedMixed()

            val page = noteWrites(nq(NoteWriteDimension.SEAT))

            // Oracle: task-scope "seat = events.seat" and "Null value -> {}": only a NULL is the null group, so '' (d1, 7 bytes)
            // is its own group (three states: absent, empty, present). Arbitration is owed if the product merges blank into NULL.
            assertEquals(
                listOf(row("rev", 1, 1, 1000, 1000), row("impl", 2, 1, 400, 300), row(null, 1, 1, 50, 50), row("", 1, 1, 7, 7)),
                page.rows
            )
            assertEquals(4, page.totalGroups)
        }

    @Test
    fun `R12 S2 by principalKind a NULL kind is the null key`(): Unit =
        runBlocking {
            seedMixed()

            assertEquals(
                listOf(row(null, 1, 1, 1000, 1000), row("agent", 3, 2, 407, 300), row("user", 1, 1, 50, 50)),
                noteWrites(nq(NoteWriteDimension.PRINCIPAL_KIND)).rows
            )
        }

    @Test
    fun `R12 S2 by day the key is the UTC date and a note written on two days counts once in each`(): Unit =
        runBlocking {
            seedMixed()

            assertEquals(
                listOf(row("2026-10-12", 4, 4, 1357, 1000), row("2026-10-11", 1, 1, 100, 100)),
                noteWrites(nq(NoteWriteDimension.DAY)).rows,
                "a1 is one millisecond before midnight UTC, a2 on it; i1 k1 is one note on each day; 10-12 holds a2, b1, c1, d1"
            )
        }

    // ------------------------------------------------------------------ S3 type, as of now, joined through the item

    @Test
    fun `R12 S3 type is the current type of the item named in the payload, a deleted or typeless item falls in the null group`(): Unit =
        runBlocking {
            val retyped = ev.item("retyped", type = "t1")
            val updated = assertNotNull(db.repositoryProvider().itemStore().update(retyped.copy(type = "t2")))
            val deleted = ev.item("deleted", type = "t9")
            val untyped = ev.item("untyped")
            val typedBare = ev.item("typedBare", type = "t1")
            val neverCreated = UUID.randomUUID()
            append(
                nt.write(updated, "k", "queue", 10, hourAgo),
                nt.write(deleted, "k", "queue", 20, hourAgo),
                nt.write(untyped, "k", "queue", 30, hourAgo),
                nt.write(typedBare, "k", "queue", 40, hourAgo),
                nt.writeFor(neverCreated, UUID.randomUUID(), "k", "queue", 50, hourAgo)
            )
            ev.delete(deleted)

            val page = noteWrites(nq(NoteWriteDimension.TYPE))

            assertEquals(
                listOf(row(null, 3, 3, 100, 50), row("t1", 1, 1, 40, 40), row("t2", 1, 1, 10, 10)),
                page.rows,
                "deleted, typeless and never-created items share the null group; the retyped item counts under t2 only"
            )
            assertEquals(3, page.totalGroups)
        }

    // ------------------------------------------------------------------ S4 failure: other types and the window edges

    @Test
    fun `R12 S4 other event types with a note-shaped payload are ignored`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(
                nt.write(i1, "k", "queue", 5, hourAgo),
                nt.write(i1, "k", "queue", 1000, hourAgo, type = "note.deleted"),
                nt.write(i1, "k", "queue", 1000, hourAgo, type = "note.ownership_transferred"),
                nt.write(i1, "k", "queue", 1000, hourAgo, type = DomainEvent.TRANSITION_REJECTED),
                nt.write(i1, "k", "queue", 1000, hourAgo, type = DomainEvent.ITEM_TRANSITIONED)
            )

            for (dimension in NoteWriteDimension.entries) {
                assertEquals(1L, noteWrites(nq(dimension)).rows.sumOf { it.writes }, "$dimension counts the one real write only")
            }
            assertEquals(listOf(row("queue", 1, 1, 5, 5)), noteWrites(nq()).rows)
        }

    @Test
    fun `R12 S4 the window is half open - since is in, one millisecond before since is out, until is out`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val since = ago(Duration.ofDays(2))
            val until = ago(Duration.ofDays(1))
            append(
                nt.write(i1, "k", "queue", 1, since),
                nt.write(i1, "k", "queue", 2, since.minusMillis(1)),
                nt.write(i1, "k", "queue", 4, until.minusMillis(1)),
                nt.write(i1, "k", "queue", 8, until)
            )

            assertEquals(
                listOf(row("queue", 2, 1, 5, 4)),
                noteWrites(nq(since = since, until = until)).rows,
                "1 at since and 4 one millisecond before until are in; 2 and 8 are out"
            )
            assertEquals(
                listOf(row("queue", 3, 1, 13, 8)),
                noteWrites(nq(since = since, until = until.plusMillis(1))).rows,
                "control: widening until by one millisecond brings the write at until in"
            )
        }

    // ------------------------------------------------------------------ S5 anchorId

    private class Tree(
        val r1: WorkItem,
        val c: WorkItem,
        val g: WorkItem,
        val s: WorkItem,
        val d: WorkItem,
        val r2: WorkItem
    )

    /** r1 > c > g, r1 > s (sibling of c), c > d (deleted after its write), and another root r2. Bytes 1, 2, 4, 8, 16, 32. */
    private suspend fun seedTree(): Tree {
        val r1 = ev.item("Alpha root", id = ReportKit.R1)
        val r2 = ev.item("Beta root", id = ReportKit.R2)
        val c = ev.item("c", parent = r1)
        val g = ev.item("g", parent = c)
        val s = ev.item("s", parent = r1)
        val d = ev.item("d", parent = c)
        append(
            nt.write(r1, "k_r1", "queue", 1, hourAgo),
            nt.write(c, "k_c", "queue", 2, hourAgo),
            nt.write(g, "k_g", "queue", 4, hourAgo),
            nt.write(s, "k_s", "queue", 8, hourAgo),
            nt.write(d, "k_d", "queue", 16, hourAgo),
            nt.write(r2, "k_r2", "queue", 32, hourAgo)
        )
        ev.delete(d)
        return Tree(r1, c, g, s, d, r2)
    }

    @Test
    fun `R12 S5 an anchor keeps its own writes and its descendants, not a sibling, a deleted item or another root`(): Unit =
        runBlocking {
            val t = seedTree()

            assertEquals(
                listOf(row("queue", 6, 6, 63, 32)),
                noteWrites(nq()).rows,
                "control: with no anchor all six writes count, the deleted item's included"
            )
            assertEquals(
                listOf(row("queue", 2, 2, 6, 4)),
                noteWrites(nq(anchorId = t.c.id)).rows,
                "anchor c: c (2) and its grandchild g (4); not the sibling s, the deleted d, the root r1 or r2"
            )
            assertEquals(
                listOf(row("queue", 4, 4, 15, 8)),
                noteWrites(nq(anchorId = t.r1.id)).rows,
                "anchor r1: r1, c, g and s; the deleted d and the other root r2 are out"
            )
            assertEquals(listOf(row("queue", 1, 1, 4, 4)), noteWrites(nq(anchorId = t.g.id)).rows, "a leaf anchor counts itself")
        }

    @Test
    fun `R12 S5 rootId and anchorId together intersect`(): Unit =
        runBlocking {
            val t = seedTree()

            assertEquals(
                listOf(row("queue", 2, 2, 6, 4)),
                noteWrites(nq(rootId = ReportKit.R1, anchorId = t.c.id)).rows,
                "the anchor lies in R1"
            )
            assertEquals(
                emptyList(),
                noteWrites(nq(rootId = ReportKit.R2, anchorId = t.c.id)).rows,
                "the anchor c is not in R2: the filters are ANDed"
            )
            assertEquals(0, noteWrites(nq(rootId = ReportKit.R2, anchorId = t.c.id)).totalGroups)
            assertEquals(listOf(row("queue", 1, 1, 32, 32)), noteWrites(nq(rootId = ReportKit.R2)).rows, "control: R2 alone has its write")
        }

    // ------------------------------------------------------------------ S6 scope

    @Test
    fun `R12 S6 a root scope keeps the writes of its roots, an empty set sees nothing, rootId narrows`(): Unit =
        runBlocking {
            seedTree()

            assertEquals(
                listOf(row("queue", 5, 5, 31, 16)),
                noteWrites(nq(scopeRootIds = setOf(ReportKit.R1))).rows,
                "R1 holds r1, c, g, s and the deleted d (root scope is root_id based); k_r2 is another root's"
            )
            assertEquals(listOf(row("queue", 1, 1, 32, 32)), noteWrites(nq(scopeRootIds = setOf(ReportKit.R2))).rows)
            assertEquals(emptyList(), noteWrites(nq(scopeRootIds = emptySet())).rows)
            assertEquals(0, noteWrites(nq(scopeRootIds = emptySet())).totalGroups)
            assertEquals(
                emptyList(),
                noteWrites(nq(scopeRootIds = setOf(ReportKit.R1), rootId = ReportKit.R2)).rows,
                "rootId R2 is outside a scope of {R1}: the filters intersect"
            )
            assertEquals(
                listOf(row("queue", 1, 1, 32, 32)),
                noteWrites(nq(scopeRootIds = setOf(ReportKit.R1, ReportKit.R2), rootId = ReportKit.R2)).rows
            )
        }

    @Test
    fun `R12 S6 a tag scope sees only writes of items that currently carry an allowed tag`(): Unit =
        runBlocking {
            val api = ev.item("api", tags = "api")
            val listed = ev.item("listed", tags = "x,api")
            val prefix = ev.item("prefix", tags = "apiary")
            val other = ev.item("other", tags = "beta")
            val untagged = ev.item("untagged")
            val gone = ev.item("gone", tags = "api")
            val retagged = ev.item("retagged", tags = "api")
            val bytes = mapOf(api to 1, listed to 2, prefix to 4, other to 8, untagged to 16, gone to 32, retagged to 64)
            for ((item, length) in bytes) append(nt.write(item, "k", "queue", length, hourAgo))
            ev.delete(gone)
            assertNotNull(db.repositoryProvider().itemStore().update(retagged.copy(tags = "beta")))

            assertEquals(
                listOf(row("queue", 2, 2, 3, 2)),
                noteWrites(nq(scopeTags = setOf("api"))).rows,
                "exact tag membership: api (1) and x,api (2); not apiary, beta, untagged, the deleted item or the retagged one"
            )
            assertEquals(
                listOf(row("queue", 7, 7, 127, 64)),
                noteWrites(nq()).rows,
                "control: unrestricted sees all seven writes, the deleted and the retagged item's included"
            )
            assertEquals(
                listOf(row("queue", 4, 4, 75, 64)),
                noteWrites(nq(scopeTags = setOf("api", "beta"))).rows,
                "any allowed tag matches: api (1), x,api (2), beta (8) and the retagged item (64, now beta)"
            )
        }

    // ------------------------------------------------------------------ S7 order, paging, empty

    @Test
    fun `R12 S7 equal bytes order by key ascending with the null key first, limit and offset page the groups`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(
                nt.write(i1, "k1", "queue", 10, hourAgo, seat = "b"),
                nt.write(i1, "k2", "queue", 10, hourAgo, seat = "a"),
                nt.write(i1, "k3", "queue", 10, hourAgo, seat = null)
            )

            assertEquals(
                listOf(row(null, 1, 1, 10, 10), row("a", 1, 1, 10, 10), row("b", 1, 1, 10, 10)),
                noteWrites(nq(NoteWriteDimension.SEAT)).rows
            )
            val first = noteWrites(nq(NoteWriteDimension.SEAT, limit = 1))
            assertEquals(listOf(row(null, 1, 1, 10, 10)), first.rows)
            assertEquals(3, first.totalGroups, "totalGroups counts every group, not the page")
            val second = noteWrites(nq(NoteWriteDimension.SEAT, limit = 1, offset = 1))
            assertEquals(listOf(row("a", 1, 1, 10, 10)), second.rows)
            assertEquals(3, second.totalGroups)
            val beyond = noteWrites(nq(NoteWriteDimension.SEAT, limit = 5, offset = 3))
            assertEquals(emptyList(), beyond.rows)
            assertEquals(3, beyond.totalGroups)
        }

    @Test
    fun `R12 S7 an empty window answers no rows and zero groups, control the same data over a wide window answers`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(nt.write(i1, "k", "queue", 10, hourAgo))

            val empty = noteWrites(nq(since = ago(Duration.ofDays(3)), until = ago(Duration.ofDays(2))))

            assertEquals(emptyList(), empty.rows)
            assertEquals(0, empty.totalGroups)
            assertEquals(1, noteWrites(nq()).totalGroups)
        }

    // ------------------------------------------------------------------ retained-since over note.upserted

    @Test
    fun `R12 S9 eventsRetainedSince for note upserted is the oldest such row over the whole table`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val oldest = ago(Duration.ofDays(9))
            append(
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(30)), type = DomainEvent.ITEM_TRANSITIONED),
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(20)), type = "note.deleted"),
                nt.write(i1, "k", "queue", 1, oldest),
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(2)))
            )

            assertEquals(oldest, inRead { it.eventsRetainedSince(setOf(DomainEvent.NOTE_UPSERTED)) })
            assertNull(inRead { it.eventsRetainedSince(setOf("note.never_written")) })
        }
}

/**
 * Independent tests (test-author seat, item d7b2a970, R12) for the `notes` wire item and `ReportService.notes` over the real
 * SQLite database and the real [ReportQueries] port: the service and wire half of scenarios S8 to S11 of the frozen test-plan
 * (all NEW-SURFACE).
 *
 * Oracles (task-scope Wire, Filters and groupBy clauses, none read from a run): `rewrites` is `writes - notes`, `avgBytes` is the
 * integer floor of `bytes / writes`, a null key is an empty `key` object and a non-null key is `{<groupBy wire name>: value}`;
 * groupBy defaults to role and accepts exactly role, noteKey, seat, type, principalKind and day, anything else (and the new role
 * and seat values on usage, gates and contention) is `invalid_request` at `/groupBy`; the window is `[since, until)`, until
 * defaults to the env clock, since to seven days before, at most 31 days (`invalid_request` at `/since`), basis `raw`;
 * `retainedSince` is the oldest `note.upserted` row over the whole table; rootId must be depth 0 (`invalid_request` at
 * `/rootId`), anchorId resolves at any depth, outside a root scope it is forbidden, unknown it is not_found unrestricted and
 * forbidden restricted; a tag scope is not forbidden; limit 1 to 200, a garbled cursor is `invalid_cursor`.
 *
 * Negative-assertion conditions: every exclusion has a seeded row that appears in the control from the same fixture (a foreign
 * root, an untagged item, an older row of another type).
 *
 * Forbidden-construct declaration: none.
 *
 * Domain invariants satisfied by construction: work items from [R11Events.item]; events from [R12Notes].
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ReportNotesServiceTest {
    private val clock = SettableClock(ReportKit.NOW)

    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod(clock = clock)

    private val fx get() = ReportFixture(db.db, clock)
    private val ev get() = R11Events(db.db)
    private val nt get() = R12Notes(ev)
    private val now = ReportKit.NOW
    private val r1 = ReportKit.R1
    private val r2 = ReportKit.R2

    private val noteGroupings =
        setOf(
            ReportGroupBy.ROLE,
            ReportGroupBy.NOTE_KEY,
            ReportGroupBy.SEAT,
            ReportGroupBy.TYPE,
            ReportGroupBy.PRINCIPAL_KIND,
            ReportGroupBy.DAY
        )

    private fun ago(d: Duration): Instant = now.minus(d)

    private val hourAgo get() = ago(Duration.ofHours(1))

    private suspend fun notes(
        request: ReportNotesRequest = ReportNotesRequest(),
        ctx: CallContext = fx.ctx()
    ): Outcome<NotesReport> = fx.service().notes(ctx, request)

    private suspend fun notesOk(
        request: ReportNotesRequest = ReportNotesRequest(),
        ctx: CallContext = fx.ctx()
    ): NotesReport =
        when (val outcome = notes(request, ctx)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("expected an ok notes report for $request but got ${outcome.error}")
        }

    private fun err(outcome: Outcome<*>) = assertIs<Outcome.Err>(outcome).error

    private fun fieldOf(error: ErrorDetail?): String = assertIs<ErrorDetail.InvalidRequest>(error).fields.single().field

    private suspend fun append(vararg records: EventRecord) = ev.append(records.toList())

    private fun row(
        key: String?,
        writes: Long,
        notes: Long,
        bytes: Long,
        maxBytes: Long
    ) = NoteWriteRow(key, writes, notes, bytes, maxBytes)

    // ------------------------------------------------------------------ S8 the wire item

    @Test
    fun `R12 S8 the wire item derives rewrites as writes minus notes and avgBytes as the floor of bytes over writes`() {
        val json =
            noteItem(ReportGroupBy.ROLE, row("queue", 4, 2, 650, 300))

        assertEquals(
            Json.parseToJsonElement(
                """{"key":{"role":"queue"},"writes":4,"notes":2,"rewrites":2,"bytes":650,"maxBytes":300,"avgBytes":162}"""
            ),
            json,
            "650 / 4 = 162.5, floored to 162"
        )
        assertEquals(
            Json.parseToJsonElement(
                """{"key":{"noteKey":"k"},"writes":3,"notes":3,"rewrites":0,"bytes":10,"maxBytes":5,"avgBytes":3}"""
            ),
            noteItem(ReportGroupBy.NOTE_KEY, row("k", 3, 3, 10, 5)),
            "10 / 3 = 3.33, floored to 3; no rewrite"
        )
    }

    @Test
    fun `R12 S8 a null key is an empty key object and each grouping names its own wire key`() {
        assertEquals(
            Json.parseToJsonElement("""{"key":{},"writes":1,"notes":1,"rewrites":0,"bytes":0,"maxBytes":0,"avgBytes":0}"""),
            noteItem(ReportGroupBy.SEAT, row(null, 1, 1, 0, 0)),
            "a zero-length body: bytes 0, avgBytes 0"
        )
        val keyedBy =
            mapOf(
                ReportGroupBy.ROLE to "role",
                ReportGroupBy.NOTE_KEY to "noteKey",
                ReportGroupBy.SEAT to "seat",
                ReportGroupBy.TYPE to "type",
                ReportGroupBy.PRINCIPAL_KIND to "principalKind",
                ReportGroupBy.DAY to "day"
            )
        assertEquals(noteGroupings, keyedBy.keys, "fixture: every note grouping has an expectation")
        for ((groupBy, wire) in keyedBy) {
            val key =
                noteItem(groupBy, row("v", 1, 1, 7, 7))
                    .getValue("key")
                    .jsonObject
            assertEquals(setOf(wire), key.keys, groupBy.wire)
            assertEquals("v", key.getValue(wire).jsonPrimitive.content, groupBy.wire)
        }
    }

    @Test
    fun `R12 S8 the service answers the rows the port computes, echoes the grouping and defaults to role`(): Unit =
        runBlocking {
            val i1 = ev.item("I1", type = "t1")
            append(
                nt.write(i1, "k1", "queue", 100, hourAgo, seat = "impl", principalKind = "agent"),
                nt.write(i1, "k1", "queue", 200, hourAgo, seat = "impl", principalKind = "agent"),
                nt.write(i1, "k2", "work", 50, hourAgo)
            )

            val byDefault = notesOk()
            assertEquals(ReportGroupBy.ROLE, byDefault.groupBy)
            assertEquals(listOf(row("queue", 2, 1, 300, 200), row("work", 1, 1, 50, 50)), byDefault.page.items)
            assertEquals(2, byDefault.page.total)

            val expectedFirst =
                mapOf(
                    ReportGroupBy.ROLE to "queue",
                    ReportGroupBy.NOTE_KEY to "k1",
                    ReportGroupBy.SEAT to "impl",
                    ReportGroupBy.TYPE to "t1",
                    ReportGroupBy.PRINCIPAL_KIND to "agent",
                    ReportGroupBy.DAY to "2026-10-13"
                )
            for ((groupBy, key) in expectedFirst) {
                val report = notesOk(ReportNotesRequest(groupBy = groupBy))
                assertEquals(groupBy, report.groupBy)
                assertEquals(
                    key,
                    report.page.items
                        .first()
                        .key,
                    groupBy.wire
                )
            }
        }

    // ------------------------------------------------------------------ S9 validation

    @Test
    fun `R12 S9 notes accepts role, noteKey, seat, type, principalKind and day and rejects every other grouping at groupBy`(): Unit =
        runBlocking {
            for (groupBy in ReportGroupBy.entries) {
                val outcome = notes(ReportNotesRequest(groupBy = groupBy))
                if (groupBy in noteGroupings) {
                    assertIs<Outcome.Ok<NotesReport>>(outcome, groupBy.wire)
                } else {
                    val error = err(outcome)
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, groupBy.wire)
                    assertEquals("/groupBy", fieldOf(error.detail), groupBy.wire)
                }
            }
        }

    @Test
    fun `R12 S9 a window of exactly 31 days is accepted and one millisecond more is invalid at since`(): Unit =
        runBlocking {
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(since = "2026-09-01T00:00:00Z", until = "2026-10-02T00:00:00Z")))
            val over = err(notes(ReportNotesRequest(since = "2026-09-01T00:00:00Z", until = "2026-10-02T00:00:00.001Z")))
            assertEquals(ErrorCode.INVALID_REQUEST, over.code)
            assertTrue(assertIs<ErrorDetail.InvalidRequest>(over.detail).fields.any { it.field == "/since" }, "the cap names /since")
            assertEquals(
                ErrorCode.INVALID_REQUEST,
                err(notes(ReportNotesRequest(since = now.minus(Duration.ofDays(31)).minusMillis(1).toString()))).code,
                "the cap also applies against the default until"
            )
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(since = now.minus(Duration.ofDays(31)).toString())))
        }

    @Test
    fun `R12 S9 since at or after until and a malformed instant are invalid`(): Unit =
        runBlocking {
            val bad =
                listOf(
                    "2026-10-05T00:00:00Z" to "2026-10-05T00:00:00Z",
                    "2026-10-06T00:00:00Z" to "2026-10-05T00:00:00Z",
                    "yesterday" to "2026-10-05T00:00:00Z",
                    "2026-10-01" to "2026-10-05T00:00:00Z"
                )
            for ((since, until) in bad) {
                assertEquals(ErrorCode.INVALID_REQUEST, err(notes(ReportNotesRequest(since = since, until = until))).code, "$since $until")
            }
            assertIs<Outcome.Ok<NotesReport>>(
                notes(ReportNotesRequest(since = "2026-10-05T00:00:00Z", until = "2026-10-05T00:00:00.001Z")),
                "control: one millisecond is a valid window"
            )
        }

    @Test
    fun `R12 S9 the default window is seven days before now and half open, basis raw`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val start = ago(Duration.ofDays(7))
            append(
                nt.write(i1, "in_start", "queue", 1, start),
                nt.write(i1, "out_before", "queue", 2, start.minusMillis(1)),
                nt.write(i1, "in_end", "queue", 4, now.minusMillis(1)),
                nt.write(i1, "out_end", "queue", 8, now)
            )

            val report = notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY))

            assertEquals(
                setOf("in_start", "in_end"),
                report.page.items
                    .map { it.key }
                    .toSet()
            )
            assertEquals(start, report.window.since)
            assertEquals(now, report.window.until)
            assertEquals("raw", report.window.basis)
        }

    @Test
    fun `R12 S9 an offset input is echoed in UTC and an empty answer has no retainedSince`(): Unit =
        runBlocking {
            val report = notesOk(ReportNotesRequest(since = "2026-10-12T14:00:00+02:00", until = "2026-10-13T13:30:00+02:00"))

            assertEquals(Instant.parse("2026-10-12T12:00:00Z"), report.window.since)
            assertEquals(Instant.parse("2026-10-13T11:30:00Z"), report.window.until)
            assertEquals(emptyList(), report.page.items)
            assertEquals(0, report.page.total)
            assertNull(report.window.retainedSince)
            assertFalse(report.window.truncatedByRetention)
        }

    @Test
    fun `R12 S9 retainedSince is the oldest note upserted row, older rows of other types are ignored`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            val oldest = ago(Duration.ofDays(9))
            append(
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(20)), type = DomainEvent.ITEM_TRANSITIONED),
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(15)), type = "note.deleted"),
                nt.write(i1, "k", "queue", 1, oldest),
                nt.write(i1, "k", "queue", 1, ago(Duration.ofDays(2)))
            )

            val before = notesOk(ReportNotesRequest(since = ago(Duration.ofDays(12)).toString()))
            assertEquals(oldest, before.window.retainedSince)
            assertTrue(before.window.truncatedByRetention, "since is before the oldest note write")

            val exact = notesOk(ReportNotesRequest(since = oldest.toString()))
            assertEquals(oldest, exact.window.retainedSince)
            assertFalse(exact.window.truncatedByRetention, "since equal to retainedSince is not truncated")

            val after = notesOk(ReportNotesRequest(since = ago(Duration.ofDays(8)).toString()))
            assertEquals(oldest, after.window.retainedSince, "retainedSince ignores the window")
            assertFalse(after.window.truncatedByRetention)
        }

    @Test
    fun `R12 S9 limit outside 1 to 200 is invalid and a garbled cursor is invalid_cursor`(): Unit =
        runBlocking {
            for (limit in listOf(0, 201, -1)) {
                assertEquals(ErrorCode.INVALID_REQUEST, err(notes(ReportNotesRequest(limit = limit))).code, "limit $limit")
            }
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(limit = 1)))
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(limit = 200)))
            assertEquals(ErrorCode.INVALID_CURSOR, err(notes(ReportNotesRequest(cursor = "!!!not-a-cursor"))).code)
        }

    @Test
    fun `R12 S9 paging walks every group once, total is the number of groups on every page`(): Unit =
        runBlocking {
            val i1 = ev.item("I1")
            append(
                nt.write(i1, "k1", "queue", 30, hourAgo),
                nt.write(i1, "k2", "queue", 20, hourAgo),
                nt.write(i1, "k3", "queue", 10, hourAgo)
            )

            val first = notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY, limit = 2))
            assertEquals(listOf("k1", "k2"), first.page.items.map { it.key })
            assertEquals(3, first.page.total)
            val cursor = assertNotNull(first.page.next)
            val second = notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY, limit = 2, cursor = cursor))
            assertEquals(listOf("k3"), second.page.items.map { it.key })
            assertEquals(3, second.page.total)
            assertNull(second.page.next)
        }

    // ------------------------------------------------------------------ S10 rootId and anchorId, scope

    private suspend fun seedRootTree() {
        val a = ev.item("Alpha root", id = r1, tags = "api")
        val b = ev.item("Beta root", id = r2, tags = "beta")
        val c = ev.item("Alpha child", parent = a, id = ReportKit.C1)
        append(
            nt.write(a, "k_alpha", "queue", 1, hourAgo),
            nt.write(b, "k_beta", "queue", 2, hourAgo),
            nt.write(c, "k_child", "queue", 4, hourAgo)
        )
    }

    private val restricted get() = fx.ctx(AccessScope.principal(setOf(r1), emptySet()))

    @Test
    fun `R12 S10 a rootId that is not a depth-0 item is invalid_request at the rootId pointer`(): Unit =
        runBlocking {
            seedRootTree()
            for (ctx in listOf(fx.ctx(), restricted)) {
                val error = err(notes(ReportNotesRequest(rootId = IdRef(ReportKit.C1.toString())), ctx))

                assertEquals(ErrorCode.INVALID_REQUEST, error.code)
                assertEquals("/rootId", fieldOf(error.detail))
            }
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(rootId = IdRef(r1.toString()))), "control: a root answers")
        }

    @Test
    fun `R12 S10 a rootId outside the root scope is forbidden at the root scope`(): Unit =
        runBlocking {
            seedRootTree()

            val error = err(notes(ReportNotesRequest(rootId = IdRef(r2.toString())), restricted))

            assertEquals(ErrorCode.FORBIDDEN, error.code)
            assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(error.detail).scope)
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(rootId = IdRef(r1.toString())), restricted), "control: own root")
        }

    @Test
    fun `R12 S10 an anchorId outside the root scope is forbidden, one inside answers at any depth`(): Unit =
        runBlocking {
            seedRootTree()

            val outside = err(notes(ReportNotesRequest(anchorId = IdRef(r2.toString())), restricted))
            assertEquals(ErrorCode.FORBIDDEN, outside.code)
            assertEquals(ForbiddenScope.ROOT, assertIs<ErrorDetail.Forbidden>(outside.detail).scope)

            val child = notesOk(ReportNotesRequest(anchorId = IdRef(ReportKit.C1.toString())), restricted)
            assertEquals(listOf(row("queue", 1, 1, 4, 4)), child.page.items, "the depth-1 child anchors a subtree of itself")
            val root = notesOk(ReportNotesRequest(anchorId = IdRef(r1.toString())), restricted)
            assertEquals(listOf(row("queue", 2, 2, 5, 4)), root.page.items, "anchor r1 holds r1 and its child")
        }

    @Test
    fun `R12 S10 an unknown anchorId is not_found unrestricted and forbidden under a restricted scope`(): Unit =
        runBlocking {
            seedRootTree()
            val unknown = IdRef("99999999-9999-4999-8999-999999999999")

            assertEquals(ErrorCode.NOT_FOUND, err(notes(ReportNotesRequest(anchorId = unknown))).code)
            assertEquals(ErrorCode.FORBIDDEN, err(notes(ReportNotesRequest(anchorId = unknown), restricted)).code)
            assertEquals(ErrorCode.NOT_FOUND, err(notes(ReportNotesRequest(rootId = unknown))).code)
        }

    @Test
    fun `R12 S10 an ambiguous anchorId prefix is ambiguous_id`(): Unit =
        runBlocking {
            seedRootTree()
            ev.item("Alpha sibling", id = UUID.fromString("a1a1a1a1-1111-4111-8111-111111111111"))

            assertEquals(ErrorCode.AMBIGUOUS_ID, err(notes(ReportNotesRequest(anchorId = IdRef("a1a1a1a1")))).code)
            assertIs<Outcome.Ok<NotesReport>>(notes(ReportNotesRequest(anchorId = IdRef(r2.toString().take(8)))), "control: unique prefix")
        }

    @Test
    fun `R12 S10 a root scope sees only its roots and rootId with anchorId intersect`(): Unit =
        runBlocking {
            seedRootTree()

            assertEquals(listOf(row("queue", 3, 3, 7, 4)), notesOk().page.items, "control: unrestricted sees all three writes")
            assertEquals(listOf(row("queue", 2, 2, 5, 4)), notesOk(ReportNotesRequest(), restricted).page.items, "R2's write is hidden")
            val both =
                notesOk(ReportNotesRequest(rootId = IdRef(r2.toString()), anchorId = IdRef(ReportKit.C1.toString())))
            assertEquals(emptyList(), both.page.items, "the anchor lies in R1, the rootId is R2: ANDed")
        }

    @Test
    fun `R12 S10 a tag scope is not forbidden and sees only writes of items that currently carry an allowed tag`(): Unit =
        runBlocking {
            seedRootTree()
            val tagged = fx.ctx(AccessScope.principal(null, setOf("api")))

            val report = notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY), tagged)

            assertEquals(
                listOf("k_alpha"),
                report.page.items.map { it.key },
                "only the root tagged api; the untagged child and beta are hidden"
            )
            assertEquals(
                3,
                notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY)).page.total,
                "control: unrestricted sees three keys"
            )
        }

    @Test
    fun `R12 S10 retainedSince is table-wide, a root scope sees the oldest note write of another root`(): Unit =
        runBlocking {
            val a = ev.item("Alpha root", id = r1)
            val b = ev.item("Beta root", id = r2)
            val elsewhere = ago(Duration.ofDays(4))
            append(nt.write(b, "k_r2", "queue", 1, elsewhere), nt.write(a, "k_r1", "queue", 2, ago(Duration.ofDays(1))))

            val report = notesOk(ReportNotesRequest(groupBy = ReportGroupBy.NOTE_KEY), restricted)

            assertEquals(elsewhere, report.window.retainedSince)
            assertEquals(listOf("k_r1"), report.page.items.map { it.key })
        }

    // ------------------------------------------------------------------ S11 the other selects keep their subsets

    @Test
    fun `R12 S11 usage, gates and contention reject the new role and seat groupings at the groupBy pointer`(): Unit =
        runBlocking {
            for (groupBy in listOf(ReportGroupBy.ROLE, ReportGroupBy.SEAT)) {
                val usage = err(fx.usage(ReportUsageRequest(groupBy = groupBy)))
                val gates = err(fx.service().gates(fx.ctx(), ReportGatesRequest(groupBy = groupBy)))
                val contention = err(fx.service().contention(fx.ctx(), ReportContentionRequest(groupBy = groupBy)))
                for ((name, error) in listOf("usage" to usage, "gates" to gates, "contention" to contention)) {
                    assertEquals(ErrorCode.INVALID_REQUEST, error.code, "$name ${groupBy.wire}")
                    assertEquals("/groupBy", fieldOf(error.detail), "$name ${groupBy.wire}")
                }
            }
            fx.ok(ReportUsageRequest(groupBy = ReportGroupBy.TOOL))
            assertIs<Outcome.Ok<GatesReport>>(fx.service().gates(fx.ctx(), ReportGatesRequest(groupBy = ReportGroupBy.NOTE_KEY)))
        }
}
