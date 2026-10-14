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

/** The first and last `occurred_at` among the events of a subtree (see [AnchorRollupQuery] for which events). */
data class SubtreeEventSpan(
    val first: Instant,
    val last: Instant
)

/**
 * One anchor-rollup computation (plan R16) over the subtree of [anchorId]: the anchor and every descendant, walked
 * through `parent_id`, whatever their lifecycle.
 *
 * - Items are current state and are not windowed; a lifecycle-free item (NULL role) is not counted, its descendants are.
 * - Subtree events are the events whose `entity_id` is a subtree item or a CURRENT note of one (a `note.upserted` event
 *   carries the note id), with `since <= occurred_at < until`. An upsert of a since-deleted note is not counted.
 * - Cost counts the DISTINCT `call_log` rows with `since <= at < until` whose `target_ids` names a subtree item, or whose
 *   `req_id` is that of a subtree event in the window. [rootId] (the anchor's project root) prefilters the target path:
 *   a call naming a subtree item carries that root or none. Visibility is that of [UsageQuery.scopeRootIds]: null admits
 *   every row, a set admits only rows whose `root_id` is in it (a row with no root never).
 */
data class AnchorRollupQuery(
    val anchorId: UUID,
    val rootId: UUID,
    val since: Instant,
    val until: Instant,
    val scopeRootIds: Set<UUID>?
)

/**
 * The raw counts of one anchor rollup. [roleCounts] maps a stored role to its subtree item count (lifecycle-free items
 * absent, zero roles absent). [transitions] counts `item.transitioned`, [cascades] those with origin `cascade`,
 * [rejected] `transition.rejected`, [notesWritten] `note.upserted`; the cost figures follow the usage definitions.
 */
data class AnchorRollupCounts(
    val roleCounts: Map<String, Long>,
    val transitions: Long,
    val cascades: Long,
    val rejected: Long,
    val notesWritten: Long,
    val calls: Long,
    val errors: Long,
    val retries: Long,
    val responseBytes: Long,
    val responseTokensEst: Long
)

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
     * The daily-basis counterpart of [usage] (R13): the groups of whole folded days from `usage_daily` for days before
     * [rawFrom] (the instant the fold covers up to, at 00:00Z, null when nothing is folded), summed with the raw `call_log`
     * rows from [rawFrom] on (all of the window when null), in ONE statement. [UsageQuery.since] and [UsageQuery.until] are
     * already day-aligned by the caller; the two parts never overlap. `groupBy = PRINCIPAL` is not supported (no principal id
     * is kept per day). Same ordering, paging and visibility rules as [usage].
     */
    suspend fun usageDaily(
        query: UsageQuery,
        rawFrom: Instant?
    ): UsagePage

    /** The page of `gates` groups [query] asks for, with the total group count. */
    suspend fun gates(query: GatesQuery): GatesPage

    /** The page of `contention` groups [query] asks for, with the total group count. */
    suspend fun contention(query: ContentionQuery): ContentionPage

    /** The oldest `events.occurred_at` among rows of [types], over the whole table; null when there are none. */
    suspend fun eventsRetainedSince(types: Set<String>): Instant?

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

    /**
     * The first and last event of the subtree of [anchorId] (the events [AnchorRollupQuery] counts, unwindowed), or null
     * when it has none: the default rollup window. Applies no access scope.
     */
    suspend fun subtreeEventSpan(anchorId: UUID): SubtreeEventSpan?

    /** The anchor-rollup counts [query] asks for, in ONE statement whatever the subtree, event and call counts. */
    suspend fun anchorRollup(query: AnchorRollupQuery): AnchorRollupCounts
}
