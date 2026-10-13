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
