package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.advance.ClaimRequest
import io.github.jpicklyk.mcptask.current.application.api.advance.ClaimSelect
import io.github.jpicklyk.mcptask.current.application.api.advance.SelectOrder
import io.github.jpicklyk.mcptask.current.application.api.advance.SelectPriority
import io.github.jpicklyk.mcptask.current.application.api.advance.SelectRole
import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.NextItemRecommender
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import java.util.UUID

/** How many ranked candidates a selector claim inspects for one the call's scope admits. */
private const val SELECT_CANDIDATES = 50

/** The backoff hint of a selector that matched items none of which is claimable right now. */
private const val SELECT_RETRY_AFTER_MS = 30_000L

private const val REASON_CLAIM_HELD = "claim_held"
private const val REASON_DEPENDENCY_UNMET = "dependency_unmet"

/**
 * `advance` op `claim`: claim one item by id, or find-and-claim the best eligible item matching a selector. The caller
 * becomes the holder (any other item it held is released); a holder claiming again refreshes the claim.
 */
internal val claimOp =
    OpSpec<ClaimRequest, JsonElement>(
        name = "claim",
        doc =
            "Claim an item for the calling actor, by itemId or by select (find-and-claim the best eligible match). " +
                "Give exactly one of itemId or select. A select that finds nothing claimable is ok with claimed false " +
                "and, when matches exist, a reason (claim_held, dependency_unmet) and retryAfterMs.",
        profiles = orchestration(),
        capability = ApiCapability.ADVANCE,
        request = serializer<ClaimRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.CLAIM_HELD,
                ErrorCode.INVALID_TRANSITION,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> runClaim(env, ctx, req) }
    )

private suspend fun WriteScope.runClaim(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: ClaimRequest
): Outcome<JsonElement> {
    val holder =
        when (val resolved = AdvanceSupport.holderId(env, ctx)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    if ((req.itemId == null) == (req.select == null)) {
        return Outcome.Err(invalidField("/itemId", "exactly one of itemId or select is required"))
    }
    if (req.ttlSeconds !in 1..ClaimRequest.MAX_TTL_SECONDS) {
        return Outcome.Err(invalidField("/ttlSeconds", "must be between 1 and ${ClaimRequest.MAX_TTL_SECONDS}", req.ttlSeconds.toString()))
    }
    if ((req.claimRef?.length ?: 0) > ClaimRequest.MAX_CLAIM_REF_LENGTH) {
        return Outcome.Err(invalidField("/claimRef", "must be at most ${ClaimRequest.MAX_CLAIM_REF_LENGTH} characters"))
    }

    if (req.itemId != null) {
        val id =
            when (val resolved = AdvanceSupport.resolveInScope(stores, ctx, req.itemId)) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        return claimItem(env, id, holder, req, selected = false)
    }
    return claimBySelector(env, ctx, holder, req, checkNotNull(req.select))
}

/** Claims [id] for [holder]; `refreshed` when [holder] already held it. */
internal suspend fun WriteScope.claimItem(
    env: ToolExecutionContext,
    id: UUID,
    holder: String,
    req: ClaimRequest,
    selected: Boolean
): Outcome<JsonElement> {
    val before = stores.workItemRepository().getById(id)
    val result =
        when (val outcome = env.claimService.claim(id, holder, req.ttlSeconds)) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
    return when (result) {
        is ClaimResult.Success -> Outcome.Ok(claimedElement(result.item, refreshed = before?.claimedBy == holder, selected, req.claimRef))
        is ClaimResult.AlreadyClaimed -> Outcome.Err(AdvanceSupport.claimHeld(result))
        is ClaimResult.NotFound -> Outcome.Err(AdvanceSupport.itemNotFound(result.itemId))
        is ClaimResult.TerminalItem -> Outcome.Err(AdvanceSupport.claimTerminal(result.itemId))
        is ClaimResult.LifecycleFree -> Outcome.Err(AdvanceSupport.claimLifecycleFree(result.itemId))
    }
}

private suspend fun WriteScope.claimBySelector(
    env: ToolExecutionContext,
    ctx: CallContext,
    holder: String,
    req: ClaimRequest,
    select: ClaimSelect
): Outcome<JsonElement> {
    val parentId =
        select.parentId?.let { ref ->
            when (val resolved = ScopedIds.resolve(stores.workItemRepository(), ctx.scope, ref, "/select/parentId")) {
                is Outcome.Err -> return resolved
                is Outcome.Ok -> resolved.value
            }
        }
    val criteria = criteria(select, parentId).copy(requestingAgentId = holder)
    val candidates = env.nextItemRecommender.recommend(criteria, SELECT_CANDIDATES)

    // A selector names no item, so a scope never answers `forbidden` here: out-of-scope candidates are skipped.
    var outOfScope = 0
    var chosen: WorkItem? = null
    for (candidate in candidates) {
        if (ScopeGuard.check(ctx.scope, candidate.id, stores.workItemRepository()) is Outcome.Ok) {
            chosen = candidate
            break
        }
        outOfScope++
    }
    if (chosen != null) return claimItem(env, chosen.id, holder, req, selected = true)

    val excluded =
        if (candidates.isEmpty()) {
            env.nextItemRecommender.explainEmpty(criteria)
        } else {
            NextItemRecommender.ExclusionCounts(claimed = 0, ancestorClaimed = 0, dependencyBlocked = 0)
        }
    return Outcome.Ok(emptyElement(excluded, outOfScope, req.claimRef))
}

private fun criteria(
    select: ClaimSelect,
    parentId: UUID?
): NextItemRecommender.Criteria =
    NextItemRecommender.Criteria(
        role = select.role?.let(::role) ?: Role.QUEUE,
        parentId = parentId,
        tags = select.tags?.map { it.trim() }?.filter { it.isNotBlank() },
        priority = select.priority?.let(::priority),
        type = select.type,
        complexityMax = select.complexityMax,
        createdAfter = select.createdAfter,
        createdBefore = select.createdBefore,
        roleChangedAfter = select.roleChangedAfter,
        roleChangedBefore = select.roleChangedBefore,
        orderBy = select.orderBy?.let(::order) ?: NextItemOrder.PRIORITY_THEN_COMPLEXITY
    )

private fun role(role: SelectRole): Role =
    when (role) {
        SelectRole.QUEUE -> Role.QUEUE
        SelectRole.WORK -> Role.WORK
        SelectRole.REVIEW -> Role.REVIEW
        SelectRole.BLOCKED -> Role.BLOCKED
        SelectRole.TERMINAL -> Role.TERMINAL
    }

private fun priority(priority: SelectPriority): Priority =
    when (priority) {
        SelectPriority.HIGH -> Priority.HIGH
        SelectPriority.MEDIUM -> Priority.MEDIUM
        SelectPriority.LOW -> Priority.LOW
    }

private fun order(order: SelectOrder): NextItemOrder =
    when (order) {
        SelectOrder.PRIORITY -> NextItemOrder.PRIORITY_THEN_COMPLEXITY
        SelectOrder.OLDEST -> NextItemOrder.OLDEST_FIRST
        SelectOrder.NEWEST -> NextItemOrder.NEWEST_FIRST
    }

/** `{itemId, claimed: true, claimedAt, expiresAt, originalClaimedAt, refreshed?, selected?, claimRef?}`. */
internal fun claimedElement(
    item: WorkItem,
    refreshed: Boolean,
    selected: Boolean,
    claimRef: String?
): JsonObject =
    buildJsonObject {
        put("itemId", item.id.toString())
        put("claimed", true)
        item.claimedAt?.let { put("claimedAt", it.toString()) }
        item.claimExpiresAt?.let { put("expiresAt", it.toString()) }
        (item.originalClaimedAt ?: item.claimedAt)?.let { put("originalClaimedAt", it.toString()) }
        if (refreshed) put("refreshed", true)
        if (selected) put("selected", true)
        claimRef?.let { put("claimRef", it) }
    }

/** A selector that claimed nothing: `{claimed: false, reason?, retryAfterMs?, excluded?, claimRef?}`. */
private fun emptyElement(
    excluded: NextItemRecommender.ExclusionCounts,
    outOfScope: Int,
    claimRef: String?
): JsonObject {
    val reason =
        when {
            excluded.claimed + excluded.ancestorClaimed > 0 -> REASON_CLAIM_HELD
            excluded.dependencyBlocked > 0 -> REASON_DEPENDENCY_UNMET
            else -> null
        }
    return buildJsonObject {
        put("claimed", false)
        reason?.let {
            put("reason", it)
            put("retryAfterMs", SELECT_RETRY_AFTER_MS)
        }
        if (excluded.total + outOfScope > 0) {
            put(
                "excluded",
                buildJsonObject {
                    put("claimed", excluded.claimed)
                    put("ancestorClaimed", excluded.ancestorClaimed)
                    put("dependencyBlocked", excluded.dependencyBlocked)
                    put("outOfScope", outOfScope)
                }
            )
        }
        claimRef?.let { put("claimRef", it) }
    }
}
