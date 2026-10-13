package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository

import io.github.jpicklyk.mcptask.current.application.port.UsageDailyStore
import io.github.jpicklyk.mcptask.current.application.port.UsageFoldWatermark
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.schema.UtcTimestamp
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import java.time.Instant
import java.time.LocalDate

/**
 * SQLite implementation of [UsageDailyStore] over `usage_daily` and `usage_fold` (V33). Every statement joins the ambient
 * unit and binds its values.
 *
 * [foldDay] is ONE `INSERT ... SELECT ... GROUP BY ... ON CONFLICT DO UPDATE` that REPLACES every metric with the freshly
 * computed value (`excluded.<metric>`, never added), so folding a day twice leaves the same rows, followed by the
 * watermark upsert. Absent dimensions are stored as the sentinels (empty string, zero-length blob; see `UsageDailyTable`).
 */
class SqliteUsageDailyStore(
    private val databaseManager: DatabaseManager
) : UsageDailyStore {
    override suspend fun watermark(): UsageFoldWatermark? =
        databaseManager.readTx {
            selectSql("SELECT folded_through, folded_at FROM usage_fold WHERE singleton = 1", emptyList()) { rs ->
                listOfNotNull(
                    if (rs.next()) UsageFoldWatermark(LocalDate.parse(rs.getString(1)), UtcTimestamp.parse(rs.getString(2))) else null
                )
            }.firstOrNull()
        }

    override suspend fun earliestCallLogDay(): LocalDate? =
        databaseManager.readTx {
            selectSql("SELECT substr(MIN(at), 1, 10) FROM call_log", emptyList()) { rs ->
                listOfNotNull(if (rs.next()) rs.getString(1)?.let(LocalDate::parse) else null)
            }.firstOrNull()
        }

    override suspend fun earliestFoldedDay(): LocalDate? =
        databaseManager.readTx {
            selectSql("SELECT MIN(day) FROM usage_daily", emptyList()) { rs ->
                listOfNotNull(if (rs.next()) rs.getString(1)?.let(LocalDate::parse) else null)
            }.firstOrNull()
        }

    override suspend fun foldDay(
        day: LocalDate,
        foldedAt: Instant
    ): Int =
        databaseManager.writeTx("UsageDailyStore.foldDay") {
            val text = TextColumnType()
            val from = "$day 00:00:00.000"
            val to = "${day.plusDays(1)} 00:00:00.000"
            exec(
                FOLD_SQL,
                args = listOf<Pair<IColumnType<*>, Any?>>(text to day.toString(), text to from, text to to)
            )
            exec(
                "INSERT INTO usage_fold (singleton, folded_through, folded_at) VALUES (1, ?, ?) " +
                    "ON CONFLICT (singleton) DO UPDATE SET folded_through = excluded.folded_through, folded_at = excluded.folded_at",
                args = listOf<Pair<IColumnType<*>, Any?>>(text to day.toString(), text to UtcTimestamp.format(foldedAt))
            )
            selectSql("SELECT COUNT(*) FROM usage_daily WHERE day = ?", listOf<Pair<IColumnType<*>, Any?>>(text to day.toString())) { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    private companion object {
        const val FOLD_SQL =
            "INSERT INTO usage_daily (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code, " +
                "calls, errors, request_bytes, response_bytes, response_tokens_est, latency_ms_sum, latency_ms_max, retries, replays) " +
                "SELECT ?, COALESCE(root_id, x''), tool, COALESCE(operation, ''), surface, COALESCE(host, ''), " +
                "COALESCE(principal_kind, ''), outcome, COALESCE(error_code, ''), COUNT(*), " +
                "SUM(CASE WHEN outcome = 'error' THEN 1 ELSE 0 END), COALESCE(SUM(request_bytes), 0), " +
                "COALESCE(SUM(response_bytes), 0), COALESCE(SUM(response_tokens_est), 0), COALESCE(SUM(latency_ms), 0), " +
                "COALESCE(MAX(latency_ms), 0), COALESCE(SUM(attempts - 1), 0), COALESCE(SUM(replayed), 0) " +
                "FROM call_log WHERE at >= ? AND at < ? GROUP BY 2, 3, 4, 5, 6, 7, 8, 9 " +
                "ON CONFLICT (day, root_id, tool, operation, surface, host, principal_kind, outcome, error_code) DO UPDATE SET " +
                "calls = excluded.calls, errors = excluded.errors, request_bytes = excluded.request_bytes, " +
                "response_bytes = excluded.response_bytes, response_tokens_est = excluded.response_tokens_est, " +
                "latency_ms_sum = excluded.latency_ms_sum, latency_ms_max = excluded.latency_ms_max, " +
                "retries = excluded.retries, replays = excluded.replays"
    }
}
