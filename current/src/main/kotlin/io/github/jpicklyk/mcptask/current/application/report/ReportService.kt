package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ContentionQuery
import io.github.jpicklyk.mcptask.current.application.port.ContentionRow
import io.github.jpicklyk.mcptask.current.application.port.GateDimension
import io.github.jpicklyk.mcptask.current.application.port.GateRow
import io.github.jpicklyk.mcptask.current.application.port.GatesQuery
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.port.WasteCount
import io.github.jpicklyk.mcptask.current.application.port.WasteQuery
import io.github.jpicklyk.mcptask.current.application.support.PageRequest
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.event.DomainEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The window a report covered, as `data.window` echoes it: the half-open range `[since, until)` actually queried (UTC,
 * millisecond precision), [retainedSince] (the oldest raw `call_log` row still held; null when the table is empty),
 * [basis] (`raw`: the raw table; `daily`: whole UTC days from `usage_daily` plus the raw tail, with `since` floored and `until` ceiled to day boundaries as read) and [truncatedByRetention] (true when the window starts before the oldest data held: [retainedSince] on the raw basis, the older of it and the earliest folded day on the daily one).
 */
data class ReportWindow(
    val since: Instant,
    val until: Instant,
    val retainedSince: Instant?,
    val basis: String,
    val truncatedByRetention: Boolean
)

/** One `usage` page: the groups of [page] keyed by [groupBy], and the [window] they cover. */
data class UsageReport(
    val groupBy: ReportGroupBy,
    val page: ListPage<UsageRow>,
    val window: ReportWindow
)

/** One `gates` page: the groups of [page] keyed by [groupBy], and the [window] they cover. */
data class GatesReport(
    val groupBy: ReportGroupBy,
    val page: ListPage<GateRow>,
    val window: ReportWindow
)

/** One `contention` page: the (kind, resource) groups of [page], and the [window] they cover. */
data class ContentionReport(
    val page: ListPage<ContentionRow>,
    val window: ReportWindow
)

/**
 * The `waste` row classes (plan R12): every MCP tool name is in exactly one of [READ], [WRITE] and [EXCLUDED] (a guard
 * test checks the registry). They are data bound as SQL parameters, never spliced into a statement. Non-ops REST rows
 * record a route template as `tool`, so they never match a class.
 */
object WasteToolClasses {
    /** Tools whose calls read: the `readsWithoutLaterWrite` and `redundantReads` reads. */
    val READ: Set<String> = setOf("items_read", "notes_read", "docs_read", "deps_read")

    /** Tools whose calls write: the later write of `readsWithoutLaterWrite`, and the `lifecycleFreeRejections` calls. */
    val WRITE: Set<String> = setOf("items_write", "notes_write", "deps_write", "docs_write", "advance")

    /** Tools in no class: the reports themselves and config. */
    val EXCLUDED: Set<String> = setOf("report", "config")

    /** The tool whose selects feed `overFetch`, `emptyNext` and `relatedFollowThrough`. */
    const val ITEMS_READ = "items_read"

    /** The `items_read` selects `overFetch` measures: those that accept `fields`. */
    val FETCH_OPERATIONS: Set<String> = setOf("get", "list", "schema", "context", "next", "blocked")

    const val NEXT_OPERATION = "next"
    const val RELATED_OPERATION = "related"

    /** Selects answering hit lists, not bodies: never a redundant read, never a follow-through target. */
    val HIT_LIST_OPERATIONS: Set<String> = setOf("related", "search")

    /** The `relatedFollowThrough` signals, in wire order; each is always present. */
    val FOLLOW_THROUGH_SIGNALS: List<String> = listOf("edges", "links", "tags", "text")
}

/**
 * One `waste` signal: [numerator] out of [denominator], with [value] their ratio rounded half up to 4 decimals (null when
 * [denominator] is 0). [basis] is [ReportService.BASIS_MCP_SESSIONS] on a session signal, else null.
 */
data class WasteSignal(
    val numerator: Long,
    val denominator: Long,
    val basis: String?
) {
    val value: BigDecimal?
        get() =
            if (denominator == 0L) {
                null
            } else {
                BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), VALUE_SCALE, RoundingMode.HALF_UP)
            }

    companion object {
        const val VALUE_SCALE = 4

        fun of(
            count: WasteCount,
            basis: String? = null
        ): WasteSignal = WasteSignal(count.numerator, count.denominator, basis)
    }
}

/**
 * The `waste` report: every signal is always present, and [relatedFollowThrough] always holds every
 * [WasteToolClasses.FOLLOW_THROUGH_SIGNALS] key, in that order.
 */
data class WasteReport(
    val window: ReportWindow,
    val redundantReads: WasteSignal,
    val overFetch: WasteSignal,
    val readsWithoutLaterWrite: WasteSignal,
    val emptyNext: WasteSignal,
    val relatedFollowThrough: Map<String, WasteSignal>,
    val lifecycleFreeRejections: WasteSignal
)

/**
 * The `report` selects (plan R9, W6 brief section 3.1): window rules, the accepted `groupBy` subset, visibility, and ONE
 * read unit per call over [io.github.jpicklyk.mcptask.current.application.port.ReportQueries].
 *
 * Visibility (W6 decision 10): for `usage` and `waste` a tag-scoped caller is `forbidden` (scope tag) before any read, since
 * `call_log` rows carry no tags; a root-scoped caller sees only rows whose `root_id` is one of its roots (rows without a
 * root never); the `principal` grouping needs [CallContext.principalsVisible] (else `forbidden`, scope capability,
 * required `admin`). `gates` and `contention` read events instead: a tag-scoped caller is filtered, not refused.
 */
class ReportService(
    private val unitOfWork: UnitOfWork,
    private val clock: Clock
) {
    suspend fun usage(
        ctx: CallContext,
        req: ReportUsageRequest
    ): Outcome<UsageReport> {
        if (ctx.scope.tagsInclude.isNotEmpty()) return Outcome.Err(ScopeGuard.forbidden(ForbiddenScope.TAG, ctx.scope.tagsInclude))

        val groupBy = req.groupBy ?: ReportGroupBy.TOOL
        val dimension =
            groupBy.dimension
                ?: return Outcome.Err(invalid("/groupBy", "usage accepts: ${USAGE_GROUPS.joinToString(", ") { it.wire }}", groupBy.wire))
        val window =
            when (val w = window(req.since, req.until, MAX_DAILY_WINDOW)) {
                is Outcome.Err -> return w
                is Outcome.Ok -> w.value
            }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        if (dimension == UsageDimension.PRINCIPAL && !ctx.principalsVisible) {
            return Outcome.Err(ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, ADMIN))
        }

        return unitOfWork.read<Outcome<UsageReport>> {
            val rootId =
                when (val r = req.rootId?.let { root(ctx, it) }) {
                    null -> null
                    is Outcome.Err -> return@read r
                    is Outcome.Ok -> r.value
                }
            val queries = stores.reportQueries()
            val daily = stores.usageDailyStore()
            val retainedSince = queries.retainedSince()
            val folded =
                daily
                    .watermark()
                    ?.foldedThrough
                    ?.plusDays(1)
                    ?.atStartOfDay(ZoneOffset.UTC)
                    ?.toInstant()
            val length = Duration.between(window.first, window.second)
            val rawBasis =
                length <= MAX_RAW_WINDOW &&
                    !(folded != null && (retainedSince == null || window.first.isBefore(retainedSince)))
            if (rawBasis) {
                val result =
                    queries.usage(
                        UsageQuery(window.first, window.second, dimension, ctx.scope.rootIds, rootId, page.limit, page.offset)
                    )
                return@read Outcome.Ok(
                    UsageReport(
                        groupBy = groupBy,
                        page = ListPage.of(result.rows, result.totalGroups, page),
                        window =
                            ReportWindow(
                                since = window.first,
                                until = window.second,
                                retainedSince = retainedSince,
                                basis = BASIS_RAW,
                                truncatedByRetention = retainedSince != null && window.first < retainedSince
                            )
                    )
                )
            }
            if (dimension == UsageDimension.PRINCIPAL) {
                return@read Outcome.Err(
                    invalid("/groupBy", "principal needs the raw call log; the window needs the daily basis", groupBy.wire)
                )
            }
            // Daily basis: whole UTC days. since floors to its midnight; until is ceiled to the next midnight only when it
            // ends inside folded days (whose raw rows may be pruned), so the window never splits a folded day.
            val since =
                window.first
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            val until =
                if (folded != null && window.second <= folded) {
                    val floor =
                        window.second
                            .atZone(ZoneOffset.UTC)
                            .toLocalDate()
                            .atStartOfDay(ZoneOffset.UTC)
                            .toInstant()
                    if (floor == window.second) floor else floor.plus(Duration.ofDays(1))
                } else {
                    window.second
                }
            val result =
                queries.usageDaily(
                    UsageQuery(since, until, dimension, ctx.scope.rootIds, rootId, page.limit, page.offset),
                    folded
                )
            val earliestFolded = daily.earliestFoldedDay()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
            val horizon = listOfNotNull(retainedSince, earliestFolded).minOrNull()
            Outcome.Ok(
                UsageReport(
                    groupBy = groupBy,
                    page = ListPage.of(result.rows, result.totalGroups, page),
                    window =
                        ReportWindow(
                            since = since,
                            until = until,
                            retainedSince = retainedSince,
                            basis = BASIS_DAILY,
                            truncatedByRetention = horizon != null && since < horizon
                        )
                )
            )
        }
    }

    /**
     * `report gates`: `transition.rejected` events grouped by [ReportGroupBy.NOTE_KEY] (default), [ReportGroupBy.TYPE],
     * [ReportGroupBy.TRAIT] or [ReportGroupBy.ERROR_CODE]. Events carry the root and item ids but no tags, so a tag-scoped
     * caller is not forbidden: it sees the events whose item currently carries an allowed tag (decision 10).
     */
    suspend fun gates(
        ctx: CallContext,
        req: ReportGatesRequest
    ): Outcome<GatesReport> {
        val groupBy = req.groupBy ?: ReportGroupBy.NOTE_KEY
        val dimension =
            when (groupBy) {
                ReportGroupBy.NOTE_KEY -> GateDimension.NOTE_KEY
                ReportGroupBy.TYPE -> GateDimension.TYPE
                ReportGroupBy.TRAIT -> GateDimension.TRAIT
                ReportGroupBy.ERROR_CODE -> GateDimension.ERROR_CODE
                else -> return Outcome.Err(
                    invalid("/groupBy", "gates accepts: ${GATES_GROUPS.joinToString(", ") { it.wire }}", groupBy.wire)
                )
            }
        val window =
            when (val w = window(req.since, req.until, MAX_RAW_WINDOW)) {
                is Outcome.Err -> return w
                is Outcome.Ok -> w.value
            }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        return unitOfWork.read<Outcome<GatesReport>> {
            val rootId =
                when (val r = req.rootId?.let { root(ctx, it) }) {
                    null -> null
                    is Outcome.Err -> return@read r
                    is Outcome.Ok -> r.value
                }
            val queries = stores.reportQueries()
            val result =
                queries.gates(
                    GatesQuery(
                        since = window.first,
                        until = window.second,
                        groupBy = dimension,
                        scopeRootIds = ctx.scope.rootIds,
                        scopeTags = ctx.scope.tagsInclude,
                        rootId = rootId,
                        limit = page.limit,
                        offset = page.offset
                    )
                )
            val retainedSince = queries.eventsRetainedSince(setOf(DomainEvent.TRANSITION_REJECTED))
            Outcome.Ok(GatesReport(groupBy, ListPage.of(result.rows, result.totalGroups, page), reportWindow(window, retainedSince)))
        }
    }

    /**
     * `report contention`: claim and lease events grouped by (kind, resource). The only accepted `groupBy` is
     * [ReportGroupBy.RESOURCE]. Visibility is as for [gates].
     */
    suspend fun contention(
        ctx: CallContext,
        req: ReportContentionRequest
    ): Outcome<ContentionReport> {
        val groupBy = req.groupBy ?: ReportGroupBy.RESOURCE
        if (groupBy != ReportGroupBy.RESOURCE) {
            return Outcome.Err(invalid("/groupBy", "contention accepts: ${ReportGroupBy.RESOURCE.wire}", groupBy.wire))
        }
        val window =
            when (val w = window(req.since, req.until, MAX_RAW_WINDOW)) {
                is Outcome.Err -> return w
                is Outcome.Ok -> w.value
            }
        val page =
            when (val p = PageRequest.from(req.limit, req.cursor)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        return unitOfWork.read<Outcome<ContentionReport>> {
            val rootId =
                when (val r = req.rootId?.let { root(ctx, it) }) {
                    null -> null
                    is Outcome.Err -> return@read r
                    is Outcome.Ok -> r.value
                }
            val queries = stores.reportQueries()
            val result =
                queries.contention(
                    ContentionQuery(
                        since = window.first,
                        until = window.second,
                        scopeRootIds = ctx.scope.rootIds,
                        scopeTags = ctx.scope.tagsInclude,
                        rootId = rootId,
                        limit = page.limit,
                        offset = page.offset
                    )
                )
            val retainedSince = queries.eventsRetainedSince(CONTENTION_EVENT_TYPES)
            Outcome.Ok(ContentionReport(ListPage.of(result.rows, result.totalGroups, page), reportWindow(window, retainedSince)))
        }
    }

    /**
     * `report waste` (plan R12): the waste signals over a window of at most [MAX_WASTE_WINDOW] (the session-join bound),
     * computed in ONE read unit with the same visibility as [usage], applied to both sides of every session join.
     */
    suspend fun waste(
        ctx: CallContext,
        req: ReportWasteRequest
    ): Outcome<WasteReport> {
        if (ctx.scope.tagsInclude.isNotEmpty()) return Outcome.Err(ScopeGuard.forbidden(ForbiddenScope.TAG, ctx.scope.tagsInclude))
        val window =
            when (val w = window(req.since, req.until, MAX_WASTE_WINDOW)) {
                is Outcome.Err -> return w
                is Outcome.Ok -> w.value
            }

        return unitOfWork.read<Outcome<WasteReport>> {
            val rootId =
                when (val r = req.rootId?.let { root(ctx, it) }) {
                    null -> null
                    is Outcome.Err -> return@read r
                    is Outcome.Ok -> r.value
                }
            val queries = stores.reportQueries()
            val counts =
                queries.waste(
                    WasteQuery(
                        since = window.first,
                        until = window.second,
                        scopeRootIds = ctx.scope.rootIds,
                        rootId = rootId,
                        readTools = WasteToolClasses.READ,
                        writeTools = WasteToolClasses.WRITE,
                        itemsReadTool = WasteToolClasses.ITEMS_READ,
                        fetchOperations = WasteToolClasses.FETCH_OPERATIONS,
                        nextOperation = WasteToolClasses.NEXT_OPERATION,
                        relatedOperation = WasteToolClasses.RELATED_OPERATION,
                        hitListOperations = WasteToolClasses.HIT_LIST_OPERATIONS,
                        sessionSurface = CallLogRecord.SURFACE_MCP
                    )
                )
            Outcome.Ok(
                WasteReport(
                    window = reportWindow(window, queries.retainedSince()),
                    redundantReads = WasteSignal.of(counts.redundantReads, BASIS_MCP_SESSIONS),
                    overFetch = WasteSignal.of(counts.overFetch),
                    readsWithoutLaterWrite = WasteSignal.of(counts.readsWithoutLaterWrite, BASIS_MCP_SESSIONS),
                    emptyNext = WasteSignal.of(counts.emptyNext),
                    relatedFollowThrough =
                        WasteToolClasses.FOLLOW_THROUGH_SIGNALS.associateWith { signal ->
                            WasteSignal.of(counts.relatedFollowThrough[signal] ?: WasteCount.ZERO, BASIS_MCP_SESSIONS)
                        },
                    lifecycleFreeRejections = WasteSignal.of(counts.lifecycleFreeRejections)
                )
            )
        }
    }

    private fun reportWindow(
        window: Pair<Instant, Instant>,
        retainedSince: Instant?
    ): ReportWindow =
        ReportWindow(
            since = window.first,
            until = window.second,
            retainedSince = retainedSince,
            basis = BASIS_RAW,
            truncatedByRetention = retainedSince != null && window.first < retainedSince
        )

    /**
     * Resolves `rootId` under the call's scope (ScopedIds: a prefix resolves, an id outside the scope is `forbidden`, an
     * unknown one `not_found` when unrestricted) and requires a depth-0 item: `call_log.root_id` is always a project root.
     */
    private suspend fun ReadScope.root(
        ctx: CallContext,
        ref: IdRef
    ): Outcome<UUID> {
        val raw = ref.raw
        val repo = stores.workItemRepository()
        val id =
            when (val r = ScopedIds.resolveTarget(repo, ctx.scope, ref, ROOT_FIELD)) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        val item =
            repo.getById(id)
                ?: return Outcome.Err(
                    if (ctx.scope.isUnrestricted) {
                        DomainError(
                            code = ErrorCode.NOT_FOUND,
                            message = "No item matches $raw.",
                            detail = ErrorDetail.NotFound(EntityKind.ITEM, raw),
                            fixArgs = mapOf("kind" to "item", "id" to raw)
                        )
                    } else {
                        ScopedIds.denied(ctx.scope)
                    }
                )
        if (item.depth != 0) return Outcome.Err(invalid(ROOT_FIELD, "must be a project root (depth 0)", raw))
        return Outcome.Ok(id)
    }

    /**
     * The effective half-open window: until defaults to the clock's now, since to until minus 7 days. Both must be
     * ISO 8601 instants with `Z` or an offset (truncated to milliseconds, the precision `call_log.at` keeps), since must
     * be before until, and the window must span at most [max]: [MAX_DAILY_WINDOW] (400 days) for `usage`, where a window
     * of at most 31 days that the raw table fully holds reads the raw basis and anything else reads the daily basis (D6);
     * [MAX_RAW_WINDOW] (31 days, the raw-table cap) for the event selects `gates` and `contention`, which have no daily basis;
     * [MAX_WASTE_WINDOW] (7 days, the session-join bound) for `waste`.
     */
    private fun window(
        sinceRaw: String?,
        untilRaw: String?,
        max: Duration
    ): Outcome<Pair<Instant, Instant>> {
        val faults = ArrayList<FieldViolation>()
        val until = untilRaw?.let { raw -> parseInstant(raw).also { if (it == null) faults += instantFault("/until", raw) } }
        val since = sinceRaw?.let { raw -> parseInstant(raw).also { if (it == null) faults += instantFault("/since", raw) } }
        if (faults.isNotEmpty()) return Outcome.Err(invalidFields(faults))
        val effectiveUntil = until ?: clock.now().truncatedTo(ChronoUnit.MILLIS)
        val effectiveSince = since ?: effectiveUntil.minus(DEFAULT_WINDOW)
        if (!effectiveSince.isBefore(effectiveUntil)) return Outcome.Err(invalid("/since", "must be before until", sinceRaw))
        if (Duration.between(effectiveSince, effectiveUntil) > max) {
            return Outcome.Err(invalid("/since", "the window (until minus since) must be at most ${max.toDays()} days", sinceRaw))
        }
        return Outcome.Ok(effectiveSince to effectiveUntil)
    }

    private fun parseInstant(raw: String): Instant? =
        try {
            OffsetDateTime
                .parse(raw, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .truncatedTo(ChronoUnit.MILLIS)
                .takeIf { it.atZone(ZoneOffset.UTC).year in 0..9999 }
        } catch (_: DateTimeParseException) {
            null
        }

    private fun instantFault(
        field: String,
        raw: String
    ): FieldViolation = FieldViolation(field, "must be an ISO 8601 instant with Z or an offset", raw.take(MAX_RECEIVED))

    private fun invalid(
        field: String,
        reason: String,
        received: String?
    ): DomainError = invalidFields(listOf(FieldViolation(field, reason, received?.take(MAX_RECEIVED))))

    private fun invalidFields(fields: List<FieldViolation>): DomainError {
        val sorted = fields.sortedBy { it.field }
        return DomainError(
            code = ErrorCode.INVALID_REQUEST,
            message = "The request has ${sorted.size} invalid field(s): ${sorted.joinToString(", ") { it.field }}.",
            detail = ErrorDetail.InvalidRequest(sorted)
        )
    }

    companion object {
        /** `data.window.basis` of a window read from the raw `call_log` table. */
        const val BASIS_RAW = "raw"

        /** `data.window.basis` of a window read from `usage_daily` (completed UTC days) plus the raw tail. */
        const val BASIS_DAILY = "daily"

        /** The default window: the last 7 days. */
        val DEFAULT_WINDOW: Duration = Duration.ofDays(7)

        /** The longest window the raw table serves. */
        val MAX_RAW_WINDOW: Duration = Duration.ofDays(31)

        /** The longest `waste` window: the session-join bound (joins never look past until). */
        val MAX_WASTE_WINDOW: Duration = Duration.ofDays(7)

        /** `basis` of a `waste` signal computed over MCP session rows only. */
        const val BASIS_MCP_SESSIONS = "mcp_sessions"

        /** The longest window a report serves (the daily basis). */
        val MAX_DAILY_WINDOW: Duration = Duration.ofDays(400)

        /** The `groupBy` values `usage` accepts, in wire order. */
        val USAGE_GROUPS: List<ReportGroupBy> = ReportGroupBy.entries.filter { it.dimension != null }

        /** The `groupBy` values `gates` accepts, in wire order. */
        val GATES_GROUPS: List<ReportGroupBy> =
            listOf(ReportGroupBy.NOTE_KEY, ReportGroupBy.TYPE, ReportGroupBy.TRAIT, ReportGroupBy.ERROR_CODE)

        /** The claim.* and lease.* event types `contention` reads (and whose oldest row is its `retainedSince`). */
        val CONTENTION_EVENT_TYPES: Set<String> =
            setOf(
                DomainEvent.CLAIM_ACQUIRED,
                DomainEvent.CLAIM_RELEASED,
                DomainEvent.CLAIM_REJECTED,
                DomainEvent.CLAIM_EXPIRED,
                DomainEvent.LEASE_ACQUIRED,
                DomainEvent.LEASE_RELEASED,
                DomainEvent.LEASE_REJECTED,
                DomainEvent.LEASE_EXPIRED
            )

        /** The `required` of the `forbidden` a `principal` grouping without the capability answers. */
        const val ADMIN = "admin"

        private const val ROOT_FIELD = "/rootId"
        private const val MAX_RECEIVED = 100
    }
}
