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
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.TagAllowlistSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.WorkViewSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.selectSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import java.time.Instant
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
                    "SELECT t.total, p.* FROM (SELECT COUNT(*) AS total FROM g) t " +
                    "LEFT JOIN (SELECT * FROM g ORDER BY calls DESC, k ASC LIMIT ? OFFSET ?) p ON 1 = 1 " +
                    "ORDER BY p.calls DESC, p.k ASC",
                args + listOf(IntegerColumnType() to query.limit, IntegerColumnType() to query.offset)
            ) { rs ->
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
                UsagePage(rows, total)
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

    /** The WHERE clause of [query] (window, scope roots, rootId) and its bound arguments, in placeholder order. */
    private fun filter(query: UsageQuery): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val clauses = mutableListOf("at >= ?", "at < ?")
        val args =
            mutableListOf<Pair<IColumnType<*>, Any?>>(
                TextColumnType() to UtcTimestamp.format(query.since),
                TextColumnType() to UtcTimestamp.format(query.until)
            )
        query.scopeRootIds?.let { roots ->
            clauses += "root_id IN (${WorkViewSql.ID_SET_SELECT})"
            args += WorkViewSql.idSetArgument(roots)
        }
        query.rootId?.let { root ->
            clauses += "root_id IN (${WorkViewSql.ID_SET_SELECT})"
            args += WorkViewSql.idSetArgument(listOf(root))
        }
        return clauses.joinToString(" AND ") to args
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

    private companion object {
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
    }
}
