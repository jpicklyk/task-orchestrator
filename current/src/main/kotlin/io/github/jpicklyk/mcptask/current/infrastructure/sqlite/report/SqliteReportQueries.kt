package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report

import io.github.jpicklyk.mcptask.current.application.port.ContentionKind
import io.github.jpicklyk.mcptask.current.application.port.ContentionPage
import io.github.jpicklyk.mcptask.current.application.port.ContentionQuery
import io.github.jpicklyk.mcptask.current.application.port.ContentionRow
import io.github.jpicklyk.mcptask.current.application.port.GateDimension
import io.github.jpicklyk.mcptask.current.application.port.GateRow
import io.github.jpicklyk.mcptask.current.application.port.GatesPage
import io.github.jpicklyk.mcptask.current.application.port.GatesQuery
import io.github.jpicklyk.mcptask.current.application.port.ReportQueries
import io.github.jpicklyk.mcptask.current.application.port.RunCost
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsagePage
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.port.WasteCount
import io.github.jpicklyk.mcptask.current.application.port.WasteCounts
import io.github.jpicklyk.mcptask.current.application.port.WasteQuery
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.TagAllowlistSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.WorkViewSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.selectSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * SQLite implementation of [ReportQueries]: every aggregate is computed by SQLite over `call_log`, and every statement goes
 * through Exposed ([selectSql]) inside the ambient read unit, so statement counting sees it and values are bound, never
 * interpolated. The only text spliced into a statement is the group expression, picked from a fixed map by [UsageDimension].
 *
 * The window is a half-open text range on the canonical 23-character UTC `at` (`idx_call_log_at`); a root filter adds
 * `root_id IN (<one bound JSON id set>)` (`idx_call_log_root_at`). `day` is the first 10 characters of `at`, the UTC date.
 */
class SqliteReportQueries(
    private val databaseManager: DatabaseManager
) : ReportQueries {
    override suspend fun usage(query: UsageQuery): UsagePage =
        databaseManager.readTx {
            val (where, args) = filter(query)
            val key = groupExpression(query.groupBy)
            // ONE statement and one scan of the window: the groups are aggregated once into a materialized CTE, then
            // counted and paged from it. The LEFT JOIN keeps the total row when the page is empty (a cursor past the end);
            // calls is NULL only on that filler row, since every real group has at least one call.
            selectSql(
                "WITH g AS MATERIALIZED (SELECT $key AS k, COUNT(*) AS calls, " +
                    "COALESCE(SUM(CASE WHEN outcome = 'error' THEN 1 ELSE 0 END), 0) AS errors, " +
                    "COALESCE(SUM(response_bytes), 0) AS response_bytes, " +
                    "COALESCE(SUM(response_tokens_est), 0) AS response_tokens, " +
                    "COALESCE(SUM(latency_ms), 0) AS latency_sum, " +
                    "COALESCE(MAX(latency_ms), 0) AS latency_max, " +
                    "COALESCE(SUM(attempts - 1), 0) AS retries, " +
                    "COALESCE(SUM(replayed), 0) AS replays " +
                    "FROM call_log WHERE $where GROUP BY k) " +
                    PAGE_SQL,
                args + listOf(IntegerColumnType() to query.limit, IntegerColumnType() to query.offset),
                ::readPage
            )
        }

    override suspend fun usageDaily(
        query: UsageQuery,
        rawFrom: Instant?
    ): UsagePage {
        // Daily part: whole folded days [date(since), date(min(rawFrom, until))). Raw part: [max(since, rawFrom), until).
        // The two ranges never overlap (the raw one starts where the daily one ends), so nothing is counted twice.
        val dailyEnd = rawFrom?.let { minOf(it, query.until) }
        val dailyFrom = LocalDate.ofInstant(query.since, ZoneOffset.UTC).toString()
        val dailyTo = dailyEnd?.let { LocalDate.ofInstant(it, ZoneOffset.UTC).toString() }
        val hasDaily = dailyTo != null && dailyFrom < dailyTo
        val rawStart = if (rawFrom == null) query.since else maxOf(query.since, rawFrom)
        val hasRaw = rawStart < query.until
        if (!hasDaily && !hasRaw) return UsagePage(emptyList(), 0)
        return databaseManager.readTx {
            val parts = ArrayList<String>()
            val args = ArrayList<Pair<IColumnType<*>, Any?>>()
            if (hasDaily) {
                val roots = rootFilter(query, "root_id")
                parts +=
                    "SELECT ${dailyGroupExpression(query.groupBy)} AS k, calls, errors, response_bytes, " +
                    "response_tokens_est AS response_tokens, latency_ms_sum AS latency_sum, latency_ms_max AS latency_max, " +
                    "retries, replays FROM usage_daily WHERE day >= ? AND day < ?" + roots.first
                args += TextColumnType() to dailyFrom
                args += TextColumnType() to dailyTo
                args += roots.second
            }
            if (hasRaw) {
                val roots = rootFilter(query, "root_id")
                parts +=
                    "SELECT ${groupExpression(query.groupBy)} AS k, COUNT(*) AS calls, " +
                    "COALESCE(SUM(CASE WHEN outcome = 'error' THEN 1 ELSE 0 END), 0) AS errors, " +
                    "COALESCE(SUM(response_bytes), 0) AS response_bytes, " +
                    "COALESCE(SUM(response_tokens_est), 0) AS response_tokens, " +
                    "COALESCE(SUM(latency_ms), 0) AS latency_sum, COALESCE(MAX(latency_ms), 0) AS latency_max, " +
                    "COALESCE(SUM(attempts - 1), 0) AS retries, COALESCE(SUM(replayed), 0) AS replays " +
                    "FROM call_log WHERE at >= ? AND at < ?" + roots.first + " GROUP BY k"
                args += TextColumnType() to UtcTimestamp.format(rawStart)
                args += TextColumnType() to UtcTimestamp.format(query.until)
                args += roots.second
            }
            // ONE statement: both parts are summed per key into a materialized CTE, then counted and paged like usage().
            selectSql(
                "WITH g AS MATERIALIZED (SELECT k, SUM(calls) AS calls, SUM(errors) AS errors, SUM(response_bytes) AS response_bytes, " +
                    "SUM(response_tokens) AS response_tokens, SUM(latency_sum) AS latency_sum, MAX(latency_max) AS latency_max, " +
                    "SUM(retries) AS retries, SUM(replays) AS replays FROM (${parts.joinToString(" UNION ALL ")}) GROUP BY k) " +
                    PAGE_SQL,
                args + listOf(IntegerColumnType() to query.limit, IntegerColumnType() to query.offset),
                ::readPage
            )
        }
    }

    override suspend fun gates(query: GatesQuery): GatesPage =
        databaseManager.readTx {
            val gateOnly = query.groupBy != GateDimension.ERROR_CODE
            val (where, args) =
                eventFilter(
                    types = listOf(DomainEvent.TRANSITION_REJECTED),
                    since = query.since,
                    until = query.until,
                    scopeRootIds = query.scopeRootIds,
                    scopeTags = query.scopeTags,
                    rootId = query.rootId,
                    extra = if (gateOnly) "json_extract(e.data, '$.code') = '${DomainEvent.REJECTED_GATE_BLOCKED}'" else null
                )
            // One row per (rejection, group value) in x; a rejection with no value for the dimension has a NULL k. blocks
            // counts distinct rejections, items distinct item ids.
            val x =
                when (query.groupBy) {
                    GateDimension.NOTE_KEY ->
                        "SELECT DISTINCT ev.seq AS seq, ev.eid AS eid, j.value AS k FROM ev, json_each(ev.data, '$.missingKeys') j " +
                            "UNION ALL SELECT ev.seq, ev.eid, NULL FROM ev " +
                            "WHERE COALESCE(json_array_length(ev.data, '$.missingKeys'), 0) = 0"
                    GateDimension.TYPE ->
                        "SELECT ev.seq AS seq, ev.eid AS eid, w.type AS k FROM ev LEFT JOIN work_items w ON w.id = ev.eid"
                    GateDimension.TRAIT ->
                        "SELECT ev.seq AS seq, ev.eid AS eid, t.name AS k FROM ev JOIN item_traits t ON t.item_id = ev.eid " +
                            "UNION ALL SELECT ev.seq, ev.eid, NULL FROM ev " +
                            "WHERE NOT EXISTS (SELECT 1 FROM item_traits t WHERE t.item_id = ev.eid)"
                    GateDimension.ERROR_CODE ->
                        "SELECT ev.seq AS seq, ev.eid AS eid, json_extract(ev.data, '$.code') AS k FROM ev"
                }
            selectSql(
                "WITH ev AS MATERIALIZED (SELECT e.seq AS seq, e.entity_id AS eid, e.data AS data FROM ${eventSource(query.scopeTags)} " +
                    "WHERE $where), x AS ($x), " +
                    "g AS MATERIALIZED (SELECT k, COUNT(DISTINCT seq) AS blocks, COUNT(DISTINCT eid) AS items FROM x GROUP BY k) " +
                    "SELECT t.total, p.* FROM (SELECT COUNT(*) AS total FROM g) t " +
                    "LEFT JOIN (SELECT * FROM g ORDER BY blocks DESC, k ASC LIMIT ? OFFSET ?) p ON 1 = 1 " +
                    "ORDER BY p.blocks DESC, p.k ASC",
                args + listOf(IntegerColumnType() to query.limit, IntegerColumnType() to query.offset)
            ) { rs ->
                var total = 0
                val rows = ArrayList<GateRow>()
                while (rs.next()) {
                    total = rs.getInt("total")
                    rs.getLong("blocks")
                    if (rs.wasNull()) continue
                    rows += GateRow(rs.getString("k"), rs.getLong("blocks"), rs.getLong("items"))
                }
                GatesPage(rows, total)
            }
        }

    override suspend fun contention(query: ContentionQuery): ContentionPage =
        databaseManager.readTx {
            val (where, args) =
                eventFilter(
                    types = CONTENTION_TYPES,
                    since = query.since,
                    until = query.until,
                    scopeRootIds = query.scopeRootIds,
                    scopeTags = query.scopeTags,
                    rootId = query.rootId,
                    extra = null
                )
            val claimId =
                "lower(substr(hex(ev.eid), 1, 8) || '-' || substr(hex(ev.eid), 9, 4) || '-' || substr(hex(ev.eid), 13, 4) || '-' || " +
                    "substr(hex(ev.eid), 17, 4) || '-' || substr(hex(ev.eid), 21))"
            // x: one row per event (a lease.rejected row once per DISTINCT contended key). m: a acquired, r rejected,
            // e expired, l released, f force-released (lease.released with forced true).
            val x =
                "SELECT CASE WHEN ev.t LIKE 'lease.%' THEN 'lease' ELSE 'claim' END AS kind, " +
                    "CASE WHEN ev.t LIKE 'lease.%' THEN json_extract(ev.data, '$.key') ELSE $claimId END AS res, " +
                    "CASE ev.t WHEN 'lease.acquired' THEN 'a' WHEN 'claim.acquired' THEN 'a' WHEN 'claim.rejected' THEN 'r' " +
                    "WHEN 'lease.expired' THEN 'e' WHEN 'claim.expired' THEN 'e' WHEN 'claim.released' THEN 'l' " +
                    "WHEN 'lease.released' THEN CASE WHEN json_extract(ev.data, '$.forced') = 1 THEN 'f' ELSE 'l' END END AS m " +
                    "FROM ev WHERE ev.t <> 'lease.rejected' " +
                    "UNION ALL SELECT 'lease', res, 'r' FROM (SELECT DISTINCT ev.seq AS s, j.value AS res " +
                    "FROM ev, json_each(ev.data, '$.contendedKeys') j WHERE ev.t = 'lease.rejected')"
            selectSql(
                "WITH ev AS MATERIALIZED (SELECT e.seq AS seq, e.type AS t, e.entity_id AS eid, e.data AS data " +
                    "FROM ${eventSource(query.scopeTags)} WHERE $where), x AS ($x), " +
                    "g AS MATERIALIZED (SELECT kind, res, " +
                    "COALESCE(SUM(m = 'a'), 0) AS acquired, COALESCE(SUM(m = 'r'), 0) AS rejected, " +
                    "COALESCE(SUM(m = 'e'), 0) AS expired, COALESCE(SUM(m = 'l'), 0) AS released, " +
                    "COALESCE(SUM(m = 'f'), 0) AS forced FROM x GROUP BY kind, res) " +
                    "SELECT t.total, p.* FROM (SELECT COUNT(*) AS total FROM g) t " +
                    "LEFT JOIN (SELECT * FROM g ORDER BY rejected DESC, res ASC, kind ASC LIMIT ? OFFSET ?) p ON 1 = 1 " +
                    "ORDER BY p.rejected DESC, p.res ASC, p.kind ASC",
                args + listOf(IntegerColumnType() to query.limit, IntegerColumnType() to query.offset)
            ) { rs ->
                var total = 0
                val rows = ArrayList<ContentionRow>()
                while (rs.next()) {
                    total = rs.getInt("total")
                    val kind = rs.getString("kind") ?: continue
                    rows +=
                        ContentionRow(
                            kind = ContentionKind.entries.first { it.wire == kind },
                            resource = rs.getString("res"),
                            acquired = rs.getLong("acquired"),
                            rejected = rs.getLong("rejected"),
                            expired = rs.getLong("expired"),
                            released = rs.getLong("released"),
                            forceReleased = rs.getLong("forced")
                        )
                }
                ContentionPage(rows, total)
            }
        }

    /**
     * ONE statement. `w` materializes the visible window once (only the needed columns, plus the class flags computed from
     * the bound row classes), and every signal is a set-based aggregate over it: no correlated per-row subquery, so the
     * cost is one window range scan plus `json_each` expansion and hash/auto-index joins, never O(n^2).
     *
     * - `obs`: (session, key, version) observations of ok non-hit-list session reads; redundant = all - distinct.
     * - `lw`: the last write per session; a read is "without later write" when there is none strictly after it.
     * - `units` x `tmax`: a related hit (per signal) is followed through when some later non-hit-list session row of the
     *   same session names the hit id in `target_ids` or as a `target_versions` key (exact text match).
     *
     * Every `?` is in `w`, so the argument order is the order they appear there.
     */
    override suspend fun waste(query: WasteQuery): WasteCounts =
        databaseManager.readTx {
            val (where, filterArgs) = filter(query.since, query.until, query.scopeRootIds, query.rootId)
            val args =
                listOf<Pair<IColumnType<*>, Any?>>(
                    TextColumnType() to jsonArray(query.readTools),
                    TextColumnType() to jsonArray(query.writeTools),
                    TextColumnType() to query.sessionSurface,
                    TextColumnType() to query.itemsReadTool,
                    TextColumnType() to jsonArray(query.hitListOperations),
                    TextColumnType() to jsonArray(query.fetchOperations),
                    TextColumnType() to query.nextOperation,
                    TextColumnType() to query.relatedOperation
                ) + filterArgs
            val counts = HashMap<String, WasteCount>()
            selectSql(
                "WITH w AS MATERIALIZED (SELECT at, session_id, outcome, target_ids, target_versions, request_shape, " +
                    "result_count, eligible_count, replayed, lifecycle_free_rejections, hit_signals, " +
                    "(tool IN (SELECT value FROM json_each(?))) AS is_read, " +
                    "(tool IN (SELECT value FROM json_each(?))) AS is_write, " +
                    "(surface = ? AND session_id IS NOT NULL) AS is_session, " +
                    "(tool = ?) AS is_items_read, " +
                    "(COALESCE(operation, '') IN (SELECT value FROM json_each(?))) AS is_hit_list, " +
                    "(COALESCE(operation, '') IN (SELECT value FROM json_each(?))) AS is_fetch, " +
                    "(COALESCE(operation, '') = ?) AS is_next, " +
                    "(COALESCE(operation, '') = ?) AS is_related " +
                    "FROM call_log WHERE $where), " +
                    "obs AS MATERIALIZED (SELECT w.session_id AS s, j.key AS k, j.value AS v " +
                    "FROM w, json_each(w.target_versions) j " +
                    "WHERE w.is_read AND w.is_session AND w.outcome = 'ok' AND NOT w.is_hit_list " +
                    "AND w.target_versions IS NOT NULL), " +
                    "lw AS (SELECT session_id AS s, MAX(at) AS last_write FROM w WHERE w.is_write AND w.is_session " +
                    "GROUP BY session_id), " +
                    "units AS (SELECT r.session_id AS s, r.at AS at, json_extract(h.value, '$.id') AS id, sg.value AS sig " +
                    "FROM w r, json_each(r.hit_signals) h, json_each(h.value, '$.signals') sg " +
                    "WHERE r.is_items_read AND r.is_related AND r.is_session AND r.outcome = 'ok' AND r.hit_signals IS NOT NULL), " +
                    "named AS (SELECT c.session_id AS s, t.value AS id, c.at AS at FROM w c, json_each(c.target_ids) t " +
                    "WHERE c.is_session AND NOT c.is_hit_list AND c.target_ids IS NOT NULL " +
                    "UNION ALL SELECT c.session_id, t.key, c.at FROM w c, json_each(c.target_versions) t " +
                    "WHERE c.is_session AND NOT c.is_hit_list AND c.target_versions IS NOT NULL), " +
                    "tmax AS (SELECT s, id, MAX(at) AS last_at FROM named GROUP BY s, id) " +
                    "SELECT 'redundantReads' AS m, " +
                    "(SELECT COUNT(*) FROM obs) - (SELECT COUNT(*) FROM (SELECT DISTINCT s, k, v FROM obs)) AS num, " +
                    "(SELECT COUNT(*) FROM obs) AS den " +
                    "UNION ALL SELECT 'overFetch', " +
                    "COALESCE(SUM(CASE WHEN json_extract(request_shape, '$.fields') IS NOT NULL THEN 1 ELSE 0 END), 0), COUNT(*) " +
                    "FROM w WHERE is_items_read AND is_fetch AND outcome = 'ok' " +
                    "UNION ALL SELECT 'readsWithoutLaterWrite', " +
                    "COALESCE(SUM(CASE WHEN lw.last_write IS NULL OR lw.last_write <= r.at THEN 1 ELSE 0 END), 0), COUNT(*) " +
                    "FROM w r LEFT JOIN lw ON lw.s = r.session_id WHERE r.is_read AND r.is_session AND r.outcome = 'ok' " +
                    "UNION ALL SELECT 'emptyNext', " +
                    "COALESCE(SUM(CASE WHEN result_count = 0 AND eligible_count > 0 THEN 1 ELSE 0 END), 0), COUNT(*) " +
                    "FROM w WHERE is_items_read AND is_next AND outcome = 'ok' " +
                    "UNION ALL SELECT 'lifecycleFreeRejections', COALESCE(SUM(COALESCE(lifecycle_free_rejections, 0)), 0), COUNT(*) " +
                    "FROM w WHERE is_write AND replayed = 0 " +
                    "UNION ALL SELECT '$FOLLOW_THROUGH_PREFIX' || u.sig, " +
                    "COALESCE(SUM(CASE WHEN t.last_at > u.at THEN 1 ELSE 0 END), 0), COUNT(*) " +
                    "FROM units u LEFT JOIN tmax t ON t.s = u.s AND t.id = u.id GROUP BY u.sig",
                args
            ) { rs ->
                while (rs.next()) counts[rs.getString("m")] = WasteCount(rs.getLong("num"), rs.getLong("den"))
            }
            WasteCounts(
                redundantReads = counts["redundantReads"] ?: WasteCount.ZERO,
                overFetch = counts["overFetch"] ?: WasteCount.ZERO,
                readsWithoutLaterWrite = counts["readsWithoutLaterWrite"] ?: WasteCount.ZERO,
                emptyNext = counts["emptyNext"] ?: WasteCount.ZERO,
                relatedFollowThrough =
                    counts
                        .filterKeys { it.startsWith(FOLLOW_THROUGH_PREFIX) }
                        .mapKeys { it.key.removePrefix(FOLLOW_THROUGH_PREFIX) },
                lifecycleFreeRejections = counts["lifecycleFreeRejections"] ?: WasteCount.ZERO
            )
        }

    override suspend fun eventsRetainedSince(types: Set<String>): Instant? =
        databaseManager.readTx {
            if (types.isEmpty()) return@readTx null
            // One single-type MIN per type, so each is an index probe on idx_events_type_occurred, not a walk of the rows.
            val sorted = types.sorted()
            val union = sorted.joinToString(" UNION ALL ") { "SELECT MIN(occurred_at) AS m FROM events WHERE type = ?" }
            selectSql("SELECT MIN(m) FROM ($union)", sorted.map { TextColumnType() to it }) { rs ->
                listOfNotNull(if (rs.next()) rs.getString(1) else null)
            }.firstOrNull()?.let { UtcTimestamp.parse(it) }
        }

    override suspend fun retainedSince(): Instant? =
        databaseManager.readTx {
            selectSql("SELECT MIN(at) FROM call_log", emptyList()) { rs ->
                listOfNotNull(if (rs.next()) rs.getString(1) else null)
            }.firstOrNull()?.let { UtcTimestamp.parse(it) }
        }

    override suspend fun runCost(runId: UUID): RunCost =
        databaseManager.readTx {
            selectSql(
                "SELECT COUNT(*), COALESCE(SUM(CASE WHEN outcome = 'error' THEN 1 ELSE 0 END), 0), " +
                    "COALESCE(SUM(response_bytes), 0), COALESCE(SUM(response_tokens_est), 0) " +
                    "FROM call_log WHERE run_id = unhex(?)",
                listOf(TextColumnType() to runId.toString().replace("-", ""))
            ) { rs ->
                if (rs.next()) RunCost(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)) else RunCost.ZERO
            }
        }

    private fun filter(query: UsageQuery): Pair<String, List<Pair<IColumnType<*>, Any?>>> =
        filter(query.since, query.until, query.scopeRootIds, query.rootId)

    /** The WHERE clause of a window (window, scope roots, rootId) over `at` and `root_id` and its bound arguments, in placeholder order. */
    private fun filter(
        since: Instant,
        until: Instant,
        scopeRootIds: Set<UUID>?,
        rootId: UUID?
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val roots = rootFilter(scopeRootIds, rootId, "root_id")
        val args =
            mutableListOf<Pair<IColumnType<*>, Any?>>(
                TextColumnType() to UtcTimestamp.format(since),
                TextColumnType() to UtcTimestamp.format(until)
            )
        args += roots.second
        return "at >= ? AND at < ?" + roots.first to args
    }

    /** The root-visibility clauses of [query] over [column] (each starts with ` AND `) and their bound arguments, in placeholder order. */
    private fun rootFilter(
        query: UsageQuery,
        column: String
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> = rootFilter(query.scopeRootIds, query.rootId, column)

    /** The root-visibility clauses over [column] (each starts with ` AND `) and their bound arguments, in placeholder order. */
    private fun rootFilter(
        scopeRootIds: Set<UUID>?,
        rootId: UUID?,
        column: String
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val sql = StringBuilder()
        val args = ArrayList<Pair<IColumnType<*>, Any?>>()
        scopeRootIds?.let { roots ->
            sql.append(" AND $column IN (${WorkViewSql.ID_SET_SELECT})")
            args += WorkViewSql.idSetArgument(roots)
        }
        rootId?.let { root ->
            sql.append(" AND $column IN (${WorkViewSql.ID_SET_SELECT})")
            args += WorkViewSql.idSetArgument(listOf(root))
        }
        return sql.toString() to args
    }

    /** The group expression over `usage_daily`; a sentinel maps back to NULL. `principal` is raw-only. */
    private fun dailyGroupExpression(dimension: UsageDimension): String =
        when (dimension) {
            UsageDimension.TOOL -> "tool"
            UsageDimension.OPERATION -> "NULLIF(operation, '')"
            UsageDimension.HOST -> "NULLIF(host, '')"
            UsageDimension.SURFACE -> "surface"
            UsageDimension.PRINCIPAL_KIND -> "NULLIF(principal_kind, '')"
            UsageDimension.ERROR_CODE -> "NULLIF(error_code, '')"
            UsageDimension.DAY -> "day"
            UsageDimension.PRINCIPAL -> throw IllegalArgumentException("usage_daily has no principal id")
        }

    private fun readPage(rs: java.sql.ResultSet): UsagePage {
        var total = 0
        val rows = ArrayList<UsageRow>()
        while (rs.next()) {
            total = rs.getInt("total")
            rs.getLong("calls")
            if (rs.wasNull()) continue
            rows +=
                UsageRow(
                    key = rs.getString("k"),
                    calls = rs.getLong("calls"),
                    errors = rs.getLong("errors"),
                    responseBytes = rs.getLong("response_bytes"),
                    responseTokensEst = rs.getLong("response_tokens"),
                    latencySumMs = rs.getLong("latency_sum"),
                    maxLatencyMs = rs.getLong("latency_max"),
                    retries = rs.getLong("retries"),
                    replays = rs.getLong("replays")
                )
        }
        return UsagePage(rows, total)
    }

    private companion object {
        /** The count + page tail shared by the raw and daily reads; the LEFT JOIN keeps the total row for an empty page. */
        const val PAGE_SQL =
            "SELECT t.total, p.* FROM (SELECT COUNT(*) AS total FROM g) t " +
                "LEFT JOIN (SELECT * FROM g ORDER BY calls DESC, k ASC LIMIT ? OFFSET ?) p ON 1 = 1 " +
                "ORDER BY p.calls DESC, p.k ASC"

        /** The claim.* and lease.* event types a `contention` report reads. */
        val CONTENTION_TYPES =
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

        /** The result-row label prefix of a `relatedFollowThrough` signal (a fixed literal, never caller input). */
        const val FOLLOW_THROUGH_PREFIX = "followThrough:"
    }

    /**
     * The FROM source of an `events` read aliased `e`: a tag-scoped read inner-joins the item row (`w`), so an event whose
     * item is gone has no tags and is invisible; the other scopes read the events alone.
     */
    private fun eventSource(scopeTags: Set<String>): String =
        if (scopeTags.isEmpty()) "events e" else "events e JOIN work_items w ON w.id = e.entity_id"

    /** The WHERE clause over `events e` (types, half-open window, scope roots, tags, rootId, [extra]) and its bound arguments. */
    private fun eventFilter(
        types: List<String>,
        since: Instant,
        until: Instant,
        scopeRootIds: Set<UUID>?,
        scopeTags: Set<String>,
        rootId: UUID?,
        extra: String?
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<Pair<IColumnType<*>, Any?>>()
        if (types.size == 1) {
            clauses += "e.type = '${types.single()}'"
        } else {
            clauses += "e.type IN (${types.joinToString(", ") { "'$it'" }})"
        }
        clauses += "e.occurred_at >= ?"
        args += TextColumnType() to UtcTimestamp.format(since)
        clauses += "e.occurred_at < ?"
        args += TextColumnType() to UtcTimestamp.format(until)
        extra?.let { clauses += it }
        scopeRootIds?.let { roots ->
            clauses += "e.root_id IN (${WorkViewSql.ID_SET_SELECT})"
            args += WorkViewSql.idSetArgument(roots)
        }
        rootId?.let { root ->
            clauses += "e.root_id IN (${WorkViewSql.ID_SET_SELECT})"
            args += WorkViewSql.idSetArgument(listOf(root))
        }
        if (scopeTags.isNotEmpty()) {
            clauses += TagAllowlistSql.render("w.tags", scopeTags.size)
            TagAllowlistSql.args(scopeTags).forEach { args += TextColumnType() to it }
        }
        return clauses.joinToString(" AND ") to args
    }

    /** A JSON array of [values], bound to one `json_each(?)` parameter. */
    private fun jsonArray(values: Collection<String>): String = JsonArray(values.map { JsonPrimitive(it) }).toString()

    private fun groupExpression(dimension: UsageDimension): String =
        when (dimension) {
            UsageDimension.TOOL -> "tool"
            UsageDimension.OPERATION -> "operation"
            UsageDimension.HOST -> "host"
            UsageDimension.SURFACE -> "surface"
            UsageDimension.PRINCIPAL_KIND -> "principal_kind"
            UsageDimension.ERROR_CODE -> "error_code"
            UsageDimension.DAY -> "substr(at, 1, 10)"
            UsageDimension.PRINCIPAL -> "principal_id"
        }
}
