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

/** The `transition.rejected` dimension a `gates` report groups by. [ERROR_CODE] is the rejection code. */
enum class GateDimension {
    NOTE_KEY,
    TYPE,
    TRAIT,
    ERROR_CODE
}

/**
 * One `gates` aggregation over the `transition.rejected` events with `since <= occurred_at < until`, grouped by [groupBy].
 * [GateDimension.NOTE_KEY], [GateDimension.TYPE] and [GateDimension.TRAIT] count only rejections coded `gate_blocked`;
 * [GateDimension.ERROR_CODE] counts every code. `type` and `trait` are the item's CURRENT type and traits.
 *
 * Visibility is applied before grouping: [scopeRootIds] null admits every event, a set admits only events whose `root_id`
 * is in it; [scopeTags] non-empty admits only events whose item currently carries an allowed tag (a deleted item's events
 * are invisible); [rootId] further keeps only events of that root. Groups are ordered by blocks descending, then key
 * ascending (a null key first).
 */
data class GatesQuery(
    val since: Instant,
    val until: Instant,
    val groupBy: GateDimension,
    val scopeRootIds: Set<UUID>?,
    val scopeTags: Set<String>,
    val rootId: UUID?,
    val limit: Int,
    val offset: Int
)

/** One `gates` group: [key] (null when the rejection has no value for the dimension), [blocks] (distinct rejection events) and [items] (distinct item ids). */
data class GateRow(
    val key: String?,
    val blocks: Long,
    val items: Long
)

/** One page of a `gates` report: [rows] in order and [totalGroups] before paging. */
data class GatesPage(
    val rows: List<GateRow>,
    val totalGroups: Int
)

/** Which coordination mechanism a `contention` row describes. */
enum class ContentionKind(
    val wire: String
) {
    CLAIM("claim"),
    LEASE("lease")
}

/**
 * One `contention` aggregation over the claim.* and lease.* events with `since <= occurred_at < until`, grouped by
 * (kind, resource). Visibility fields work as in [GatesQuery]; the groups are ordered by rejected descending, then
 * resource ascending (a null first), then kind ascending.
 */
data class ContentionQuery(
    val since: Instant,
    val until: Instant,
    val scopeRootIds: Set<UUID>?,
    val scopeTags: Set<String>,
    val rootId: UUID?,
    val limit: Int,
    val offset: Int
)

/**
 * One `contention` group. A lease row's [resource] is the lease key; a claim row's is the claimed item id (lower-case
 * hyphenated UUID). [forceReleased] is always 0 for claims. The rates are null when their denominator is 0 and are never clamped.
 */
data class ContentionRow(
    val kind: ContentionKind,
    val resource: String?,
    val acquired: Long,
    val rejected: Long,
    val expired: Long,
    val released: Long,
    val forceReleased: Long
) {
    /** `acquired + rejected`. */
    val attempts: Long get() = acquired + rejected

    /** `rejected / (acquired + rejected)`, 4 decimals half up; null when there were no attempts. */
    val contentionRate: Double? get() = rate(rejected, attempts)

    /** `expired / acquired`, 4 decimals half up; null when nothing was acquired. */
    val expiryRate: Double? get() = rate(expired, acquired)

    /** `forceReleased / acquired`, 4 decimals half up; null when nothing was acquired. */
    val forceReleaseRate: Double? get() = rate(forceReleased, acquired)

    private fun rate(
        numerator: Long,
        denominator: Long
    ): Double? =
        if (denominator <= 0) {
            null
        } else {
            java.math.BigDecimal
                .valueOf(numerator)
                .divide(java.math.BigDecimal.valueOf(denominator), 4, java.math.RoundingMode.HALF_UP)
                .toDouble()
        }
}

/** One page of a `contention` report: [rows] in order and [totalGroups] before paging. */
data class ContentionPage(
    val rows: List<ContentionRow>,
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

    /** The page of `gates` groups [query] asks for, with the total group count. */
    suspend fun gates(query: GatesQuery): GatesPage

    /** The page of `contention` groups [query] asks for, with the total group count. */
    suspend fun contention(query: ContentionQuery): ContentionPage

    /** The oldest `events.occurred_at` among rows of [types], over the whole table; null when there are none. */
    suspend fun eventsRetainedSince(types: Set<String>): Instant?

    /** The oldest `call_log.at` still held, over the whole table (retention prunes by age, not per root); null when empty. */
    suspend fun retainedSince(): Instant?

    /**
     * The cost of run [runId]: the rows stamped with that run id, [RunCost.ZERO] when there are none. An internal port for
     * the rollup: it applies no access scope, so its caller decides who may see the figure.
     */
    suspend fun runCost(runId: UUID): RunCost
}
