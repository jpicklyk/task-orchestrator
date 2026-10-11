package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.Doc
import io.github.jpicklyk.mcptask.current.application.api.IdRef
import io.github.jpicklyk.mcptask.current.application.port.ItemFilter
import io.github.jpicklyk.mcptask.current.application.port.unitNow
import io.github.jpicklyk.mcptask.current.application.read.NextEmptyReason
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.application.telemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.time.Instant

/**
 * `items_read` select `next` (orchestration profile): one page of the items an agent could pick up next, ranked.
 *
 * Eligibility is decided in SQL over the whole matching set ([io.github.jpicklyk.mcptask.current.application.read.WorkViewQueries.nextView]):
 * the selector filters, no active claim, no ancestor claim by another agent, no unsatisfied blocking dependency; with
 * `includeClaimed` the claim predicates are off (dependency blocking still applies). The access scope of the call is
 * applied in the same SQL, so a restricted caller gets full pages of visible items and an exact total.
 *
 * An empty page says why in `data.reason` (`claim_held` or `dependency_unmet`) only when items matched the selector but
 * none is eligible (decision D-c); when nothing matched there is no reason. Default limit 1, max 200 (D-d).
 */
object ItemsReadNext {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("summary", "ancestors")

    private const val DEFAULT_LIMIT = 1
    private const val MAX_LIMIT = 200
    private const val MAX_COMPLEXITY = 10

    /** A `next` ordering on the wire. */
    @Serializable
    enum class OrderByWire(
        val order: NextItemOrder
    ) {
        @SerialName("priority")
        PRIORITY(NextItemOrder.PRIORITY_THEN_COMPLEXITY),

        @SerialName("oldest")
        OLDEST(NextItemOrder.OLDEST_FIRST),

        @SerialName("newest")
        NEWEST(NextItemOrder.NEWEST_FIRST),
    }

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.PARENT_ID) val parentId: IdRef? = null,
        @Doc(ItemsReadDocs.ANCESTOR_ID) val ancestorId: IdRef? = null,
        @Doc(ItemsReadDocs.ROLE) val role: RoleWire? = null,
        @Doc(ItemsReadDocs.PRIORITY) val priority: PriorityWire? = null,
        @Doc(ItemsReadDocs.TAGS) val tags: List<String>? = null,
        @Doc(ItemsReadDocs.TYPE) val type: String? = null,
        @Doc("Only items with a complexity of at most this (1-10); items without one are excluded.")
        val complexityMax: Int? = null,
        @Doc(ItemsReadDocs.CREATED_AFTER) @Contextual val createdAfter: Instant? = null,
        @Doc(ItemsReadDocs.CREATED_BEFORE) @Contextual val createdBefore: Instant? = null,
        @Doc(ItemsReadDocs.MODIFIED_AFTER) @Contextual val modifiedAfter: Instant? = null,
        @Doc(ItemsReadDocs.MODIFIED_BEFORE) @Contextual val modifiedBefore: Instant? = null,
        @Doc(ItemsReadDocs.ROLE_CHANGED_AFTER) @Contextual val roleChangedAfter: Instant? = null,
        @Doc(ItemsReadDocs.ROLE_CHANGED_BEFORE) @Contextual val roleChangedBefore: Instant? = null,
        @Doc("Ranking: priority (default: high first, then lower complexity), oldest or newest first.")
        val orderBy: OrderByWire? = null,
        @Doc("Also list items under an active claim, each with isClaimed; default false.")
        val includeClaimed: Boolean? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
    )

    val select =
        SelectSpec.Single<Request, JsonObject>(
            name = "next",
            doc =
                "One page of the items ready to pick up, ranked (default limit 1, max 200): in the role (default queue; " +
                    "not terminal), unclaimed, not under a claim by another agent, no unsatisfied blocker. includeClaimed lists " +
                    "claimed items too. Empty with reason claim_held or dependency_unmet when matches exist but none is ready. " +
                    "fields: summary, ancestors.",
            profile = ToolProfile.ORCHESTRATION,
            request = serializer<Request>(),
            errors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN, ErrorCode.INVALID_CURSOR),
            handler = handler@{ env, ctx, req ->
                unknownFields(req.fields, FIELDS)?.let { return@handler Outcome.Err(it) }
                val role = req.role?.role ?: Role.QUEUE
                val faults = ArrayList<FieldViolation>()
                if (role == Role.TERMINAL) {
                    faults.add(FieldViolation(pointer("role"), "must be queue, work, review or blocked", "terminal"))
                }
                req.complexityMax?.let {
                    if (it !in 1..MAX_COMPLEXITY) {
                        faults.add(FieldViolation(pointer("complexityMax"), "must be between 1 and $MAX_COMPLEXITY", it.toString()))
                    }
                }
                val times =
                    ItemFilter(
                        createdAfter = req.createdAfter,
                        createdBefore = req.createdBefore,
                        modifiedAfter = req.modifiedAfter,
                        modifiedBefore = req.modifiedBefore,
                        roleChangedAfter = req.roleChangedAfter,
                        roleChangedBefore = req.roleChangedBefore,
                    )
                times.validate().forEach { faults.add(FieldViolation(pointer(it.field), it.reason, it.received)) }
                if (faults.isNotEmpty()) return@handler Outcome.Err(invalidFields(faults))
                val page =
                    when (val p = pageRequest(req.limit, req.cursor, DEFAULT_LIMIT, MAX_LIMIT)) {
                        is Outcome.Err -> return@handler p
                        is Outcome.Ok -> p.value
                    }
                val parentId =
                    when (val r = resolveOptionalId(env, ctx, req.parentId, "parentId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val ancestorId =
                    when (val r = resolveOptionalId(env, ctx, req.ancestorId, "ancestorId")) {
                        is Outcome.Err -> return@handler r
                        is Outcome.Ok -> r.value
                    }
                val criteria =
                    NextItemRecommender.Criteria(
                        role = role,
                        parentId = parentId,
                        tags =
                            req.tags
                                ?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                                ?.takeIf { it.isNotEmpty() },
                        priority = req.priority?.priority,
                        type = req.type,
                        complexityMax = req.complexityMax,
                        createdAfter = req.createdAfter,
                        createdBefore = req.createdBefore,
                        modifiedAfter = req.modifiedAfter,
                        modifiedBefore = req.modifiedBefore,
                        roleChangedAfter = req.roleChangedAfter,
                        roleChangedBefore = req.roleChangedBefore,
                        orderBy = req.orderBy?.order ?: NextItemOrder.PRIORITY_THEN_COMPLEXITY,
                        ancestorIds = ancestorId?.let { setOf(it) },
                    )
                val includeClaimed = req.includeClaimed ?: false
                val view = env.workViewQueries.nextView(criteria, page, includeClaimed, ctx.scope)
                val items = view.page.items
                recordCallResultCounts(items.size, view.page.total)

                val wanted = req.fields.orEmpty().toSet()
                val chains = ancestorChains(env, items, "ancestors" in wanted)
                val now = if (includeClaimed && items.any { it.claimedBy != null }) env.clock.unitNow() else null
                Outcome.Ok(
                    buildJsonObject {
                        putPage(view.page) { item ->
                            var entry = ItemView.compact(item, summary = "summary" in wanted)
                            if (includeClaimed) {
                                val claimed = now != null && ClaimState.isActive(item, now)
                                entry = ItemView.with(entry, "isClaimed", JsonPrimitive(claimed))
                            }
                            if ("ancestors" in wanted) entry = withAncestors(entry, item, chains, ctx.scope)
                            entry
                        }
                        view.emptyReason?.let { put("reason", reasonWire(it)) }
                    }
                )
            },
            data = { it }
        )

    private fun reasonWire(reason: NextEmptyReason): String =
        when (reason) {
            NextEmptyReason.CLAIM_HELD -> "claim_held"
            NextEmptyReason.DEPENDENCY_UNMET -> "dependency_unmet"
        }
}
