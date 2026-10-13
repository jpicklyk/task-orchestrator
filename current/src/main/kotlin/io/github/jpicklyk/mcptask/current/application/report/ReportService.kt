package io.github.jpicklyk.mcptask.current.application.report

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.api.ListPage
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.ReadScope
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.UsageDimension
import io.github.jpicklyk.mcptask.current.application.port.UsageQuery
import io.github.jpicklyk.mcptask.current.application.port.UsageRow
import io.github.jpicklyk.mcptask.current.application.support.PageRequest
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
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
 * [basis] (`raw`: the raw table) and [truncatedByRetention] (true exactly when the window starts before [retainedSince]).
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

/**
 * The `report` selects (plan R9, W6 brief section 3.1): window rules, the accepted `groupBy` subset, visibility, and ONE
 * read unit per call over [io.github.jpicklyk.mcptask.current.application.port.ReportQueries].
 *
 * Visibility (W6 decision 10): a tag-scoped caller is `forbidden` (scope tag) before any read, since `call_log` rows carry
 * no tags; a root-scoped caller sees only rows whose `root_id` is one of its roots (rows without a root never); the
 * `principal` grouping needs [CallContext.principalsVisible] (else `forbidden`, scope capability, required `admin`).
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
            when (val w = window(req.since, req.until)) {
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
            val result =
                queries.usage(
                    UsageQuery(
                        since = window.first,
                        until = window.second,
                        groupBy = dimension,
                        scopeRootIds = ctx.scope.rootIds,
                        rootId = rootId,
                        limit = page.limit,
                        offset = page.offset
                    )
                )
            val retainedSince = queries.retainedSince()
            Outcome.Ok(
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
    }

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
     * be before until, and the window must span at most 31 days (the raw-table cap).
     */
    private fun window(
        sinceRaw: String?,
        untilRaw: String?
    ): Outcome<Pair<Instant, Instant>> {
        val faults = ArrayList<FieldViolation>()
        val until = untilRaw?.let { raw -> parseInstant(raw).also { if (it == null) faults += instantFault("/until", raw) } }
        val since = sinceRaw?.let { raw -> parseInstant(raw).also { if (it == null) faults += instantFault("/since", raw) } }
        if (faults.isNotEmpty()) return Outcome.Err(invalidFields(faults))
        val effectiveUntil = until ?: clock.now().truncatedTo(ChronoUnit.MILLIS)
        val effectiveSince = since ?: effectiveUntil.minus(DEFAULT_WINDOW)
        if (!effectiveSince.isBefore(effectiveUntil)) return Outcome.Err(invalid("/since", "must be before until", sinceRaw))
        if (Duration.between(effectiveSince, effectiveUntil) > MAX_RAW_WINDOW) {
            return Outcome.Err(invalid("/since", "the window (until minus since) must be at most 31 days", sinceRaw))
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

        /** The default window: the last 7 days. */
        val DEFAULT_WINDOW: Duration = Duration.ofDays(7)

        /** The longest window the raw table serves. */
        val MAX_RAW_WINDOW: Duration = Duration.ofDays(31)

        /** The `groupBy` values `usage` accepts, in wire order. */
        val USAGE_GROUPS: List<ReportGroupBy> = ReportGroupBy.entries.filter { it.dimension != null }

        /** The `required` of the `forbidden` a `principal` grouping without the capability answers. */
        const val ADMIN = "admin"

        private const val ROOT_FIELD = "/rootId"
        private const val MAX_RECEIVED = 100
    }
}
