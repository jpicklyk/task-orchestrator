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
 * One `waste` computation over the `call_log` rows with `since <= at < until` (plan R12, W6 brief 3.1).
 *
 * Visibility is that of [UsageQuery] ([scopeRootIds], then [rootId]) and applies to BOTH sides of every join: a row
 * outside the window or the root filter is never seen, not even as the later row of a session join.
 *
 * The row classes are data, bound as parameters and never spliced into SQL: [readTools] and [writeTools] name the READ
 * and WRITE tools; [itemsReadTool] is the tool whose [fetchOperations], [nextOperation] and [relatedOperation] rows feed
 * `overFetch`, `emptyNext` and `relatedFollowThrough`; [hitListOperations] (related, search) are left out of
 * `redundantReads` and are never a follow-through target. A session row has `surface` equal to [sessionSurface] and a
 * non-null `session_id`.
 */
data class WasteQuery(
    val since: Instant,
    val until: Instant,
    val scopeRootIds: Set<UUID>?,
    val rootId: UUID?,
    val readTools: Set<String>,
    val writeTools: Set<String>,
    val itemsReadTool: String,
    val fetchOperations: Set<String>,
    val nextOperation: String,
    val relatedOperation: String,
    val hitListOperations: Set<String>,
    val sessionSurface: String
)

/** One ratio of a `waste` report: [numerator] out of [denominator]. */
data class WasteCount(
    val numerator: Long,
    val denominator: Long
) {
    companion object {
        val ZERO = WasteCount(0, 0)
    }
}

/**
 * The raw counts of one `waste` computation. [relatedFollowThrough] is keyed by `hit_signals` signal name and holds only
 * the signals that had at least one unit in the window.
 */
data class WasteCounts(
    val redundantReads: WasteCount,
    val overFetch: WasteCount,
    val readsWithoutLaterWrite: WasteCount,
    val emptyNext: WasteCount,
    val relatedFollowThrough: Map<String, WasteCount>,
    val lifecycleFreeRejections: WasteCount
)

/**
 * Read-only aggregate queries over the telemetry tables (the `report` tool). Typed requests and rows; all SQL lives in
 * the infrastructure implementation. Each method is a read that joins the caller's unit and issues a constant number of
 * statements, whatever the row count.
 */
interface ReportQueries {
    /** The page of `usage` groups [query] asks for, with the total group count. */
    suspend fun usage(query: UsageQuery): UsagePage

    /**
     * The `waste` counts [query] asks for, computed set-based over the window (no correlated per-row subquery against
     * `call_log`).
     */
    suspend fun waste(query: WasteQuery): WasteCounts

    /** The oldest `call_log.at` still held, over the whole table (retention prunes by age, not per root); null when empty. */
    suspend fun retainedSince(): Instant?

    /**
     * The cost of run [runId]: the rows stamped with that run id, [RunCost.ZERO] when there are none. An internal port for
     * the rollup: it applies no access scope, so its caller decides who may see the figure.
     */
    suspend fun runCost(runId: UUID): RunCost
}
