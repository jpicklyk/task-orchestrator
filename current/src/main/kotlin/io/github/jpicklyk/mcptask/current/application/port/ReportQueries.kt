package io.github.jpicklyk.mcptask.current.application.port

import java.time.Instant
import java.util.UUID

/** The `call_log` column a `usage` report groups by. [PRINCIPAL] is the principal id; [DAY] is the UTC date of `at`. */
enum class UsageDimension {
    TOOL,
    OPERATION,
    HOST,
    SURFACE,
    PRINCIPAL_KIND,
    ERROR_CODE,
    DAY,
    PRINCIPAL
}

/**
 * One `usage` aggregation over the `call_log` rows with `since <= at < until`, grouped by [groupBy].
 *
 * Visibility is applied before grouping: [scopeRootIds] null admits every row; a set admits only rows whose `root_id` is
 * in it (a row with no root never matches, and an empty set admits nothing). [rootId], when set, further keeps only rows
 * with that `root_id`. [limit] and [offset] page the groups, ordered by calls descending, then key ascending (a null key
 * first).
 */
data class UsageQuery(
    val since: Instant,
    val until: Instant,
    val groupBy: UsageDimension,
    val scopeRootIds: Set<UUID>?,
    val rootId: UUID?,
    val limit: Int,
    val offset: Int
)

/**
 * One group of a `usage` report. [key] is the group value (null when the column is NULL; the wire still writes `key` but omits the `<groupBy>` member inside it, giving `{}`, never JSON null). [errors] counts rows with
 * outcome `error`; [responseBytes] and [responseTokensEst] are sums (a NULL counts 0); [latencySumMs] is the sum of
 * `latency_ms`, so the average is exact; [maxLatencyMs] is the maximum; [retries] is the sum of `attempts - 1`;
 * [replays] counts replayed rows.
 */
data class UsageRow(
    val key: String?,
    val calls: Long,
    val errors: Long,
    val responseBytes: Long,
    val responseTokensEst: Long,
    val latencySumMs: Long,
    val maxLatencyMs: Long,
    val retries: Long,
    val replays: Long
) {
    /** The average latency, rounded half-up (0 when there are no calls). */
    val avgLatencyMs: Long get() = if (calls <= 0) 0 else (2 * latencySumMs + calls) / (2 * calls)
}

/** One page of a `usage` report: [rows] in order and [totalGroups], the number of groups before paging. */
data class UsagePage(
    val rows: List<UsageRow>,
    val totalGroups: Int
)

/** The cost of one run: the sums over every `call_log` row stamped with its id. */
data class RunCost(
    val calls: Long,
    val errors: Long,
    val responseBytes: Long,
    val responseTokensEst: Long
) {
    companion object {
        val ZERO = RunCost(0, 0, 0, 0)
    }
}

/**
 * Read-only aggregate queries over the telemetry tables (the `report` tool). Typed requests and rows; all SQL lives in
 * the infrastructure implementation. Each method is a read that joins the caller's unit and issues a constant number of
 * statements, whatever the row count.
 */
interface ReportQueries {
    /** The page of `usage` groups [query] asks for, with the total group count. */
    suspend fun usage(query: UsageQuery): UsagePage

    /** The oldest `call_log.at` still held, over the whole table (retention prunes by age, not per root); null when empty. */
    suspend fun retainedSince(): Instant?

    /**
     * The cost of run [runId]: the rows stamped with that run id, [RunCost.ZERO] when there are none. An internal port for
     * the rollup: it applies no access scope, so its caller decides who may see the figure.
     */
    suspend fun runCost(runId: UUID): RunCost
}
