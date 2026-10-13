package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.report

import io.github.jpicklyk.mcptask.current.application.port.ReportQueries
import io.github.jpicklyk.mcptask.current.application.port.RunCost
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsagePage
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.WorkViewSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.readTx
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.selectSql
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
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
        val roots = rootFilter(query, "root_id")
        val args =
            mutableListOf<Pair<IColumnType<*>, Any?>>(
                TextColumnType() to UtcTimestamp.format(query.since),
                TextColumnType() to UtcTimestamp.format(query.until)
            )
        args += roots.second
        return "at >= ? AND at < ?" + roots.first to args
    }

    /** The root-visibility clauses of [query] over [column] (each starts with ` AND `) and their bound arguments, in placeholder order. */
    private fun rootFilter(
        query: UsageQuery,
        column: String
    ): Pair<String, List<Pair<IColumnType<*>, Any?>>> {
        val sql = StringBuilder()
        val args = ArrayList<Pair<IColumnType<*>, Any?>>()
        query.scopeRootIds?.let { roots ->
            sql.append(" AND $column IN (${WorkViewSql.ID_SET_SELECT})")
            args += WorkViewSql.idSetArgument(roots)
        }
        query.rootId?.let { root ->
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
}
