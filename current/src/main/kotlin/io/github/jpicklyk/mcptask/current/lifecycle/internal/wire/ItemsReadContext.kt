package io.github.jpicklyk.mcptask.current.lifecycle.internal.wire

import io.github.jpicklyk.mcptask.current.claim.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemView
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ItemsReadDocs
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.ancestorChains
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.resolveOptionalId
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.resolveTarget
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items.withAncestors
import io.github.jpicklyk.mcptask.current.lifecycle.AdvanceSupport
import io.github.jpicklyk.mcptask.current.lifecycle.ContextScope
import io.github.jpicklyk.mcptask.current.lifecycle.ContextView
import io.github.jpicklyk.mcptask.current.lifecycle.Decision
import io.github.jpicklyk.mcptask.current.lifecycle.LifecycleToolServices
import io.github.jpicklyk.mcptask.current.lifecycle.NoteSchemaJson
import io.github.jpicklyk.mcptask.current.lifecycle.RejectContext
import io.github.jpicklyk.mcptask.current.lifecycle.blockedByWire
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildDispatchBySeatFlatJson
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildDispatchProfileJson
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildExpectedNotesJson
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildMissingBySeatJson
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.buildSeatsJson
import io.github.jpicklyk.mcptask.current.lifecycle.internal.app.putSkillExtras
import io.github.jpicklyk.mcptask.current.lifecycle.internal.domain.computePhaseNoteContext
import io.github.jpicklyk.mcptask.current.model.claim.ClaimState
import io.github.jpicklyk.mcptask.current.model.config.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.FieldViolation
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.Role
import io.github.jpicklyk.mcptask.current.model.item.Trigger
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.lease.ResourceMode
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequest
import io.github.jpicklyk.mcptask.current.model.run.DecisionRequestStatus
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.recordCallResultCounts
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.recordCallRoot
import io.github.jpicklyk.mcptask.current.platform.calltelemetry.recordCallTargetVersion
import io.github.jpicklyk.mcptask.current.platform.port.PageRequest
import io.github.jpicklyk.mcptask.current.platform.port.unitNow
import io.github.jpicklyk.mcptask.current.platform.request.Doc
import io.github.jpicklyk.mcptask.current.platform.support.IdRef
import io.github.jpicklyk.mcptask.current.platform.tool.SelectSpec
import io.github.jpicklyk.mcptask.current.platform.tool.acceptFields
import io.github.jpicklyk.mcptask.current.platform.tool.invalidFields
import io.github.jpicklyk.mcptask.current.platform.tool.itemNotFound
import io.github.jpicklyk.mcptask.current.platform.tool.orchestration
import io.github.jpicklyk.mcptask.current.platform.tool.pageRequest
import io.github.jpicklyk.mcptask.current.platform.tool.pointer
import io.github.jpicklyk.mcptask.current.platform.tool.putPage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * `items_read` select `context` (orchestration profile), replacing `get_context` and `get_next_status`:
 *
 * - **item mode** (`itemId`): the gate state of one item for an advance or dispatch decision. `canAdvance`, `blockedBy`,
 *   `blockers`, `contended`, `missing`, `missingBySeat` and `violations` all come from ONE
 *   `gateStatusQuery().inspect(item)` read unit, so they never disagree with `advance` on the same state.
 *   `transitions` is the item row of the status graph for its resolved schema (`resume` resolved to `previousRole`).
 *   `claimDetail` is the only sanctioned claim-identity disclosure. `decisions` lists the item's open, acknowledged and
 *   answered decision requests as `{id, class, status, option?}`. A per-root config read fault answers `unavailable`
 *   (decision D-f), never a fallback.
 * - **health mode** (no `itemId`): `{active, blocked, stalled, claimSummary}` for the scope or the `ancestorId`
 *   subtree. One `limit`/`cursor` pages `active` and `blocked` together; `blocked` is the same set as select
 *   `blocked` (decision D-b); `stalled` are the active items on the page missing required notes of their phase.
 *   The 3.x session-resume mode (`since`, `recentTransitions`) is not carried (decision D-a).
 */
object ItemsReadContext {
    /** The `fields` names this select accepts. */
    val FIELDS: List<String> = listOf("ancestors")

    private const val MAX_LIMIT = 200

    private val graph = StatusGraphBuilder()

    @Serializable
    data class Request(
        @Doc(ItemsReadDocs.ITEM_ID) val itemId: IdRef? = null,
        @Doc(ItemsReadDocs.ANCESTOR_ID) val ancestorId: IdRef? = null,
        @Doc(ItemsReadDocs.LIMIT) val limit: Int? = null,
        @Doc(ItemsReadDocs.CURSOR) val cursor: String? = null,
        @Doc(ItemsReadDocs.FIELDS) val fields: List<String>? = null,
    )

    val select =
        SelectSpec.Single<LifecycleToolServices, Request, JsonObject>(
            name = "context",
            doc =
                "With itemId: the gate state of one item (canAdvance, blockedBy, blockers, missing notes, next, transitions, " +
                    "dispatch, claimDetail, decisions). Without: a health view of active, blocked and stalled items and the " +
                    "claim summary " +
                    "(default limit 50, max 200; ancestorId scopes it). fields: ancestors.",
            profiles = orchestration(),
            services = LifecycleToolServices::class,
            request = serializer<Request>(),
            errors =
                setOf(
                    ErrorCode.NOT_FOUND,
                    ErrorCode.AMBIGUOUS_ID,
                    ErrorCode.FORBIDDEN,
                    ErrorCode.INVALID_CURSOR,
                    ErrorCode.UNAVAILABLE
                ),
            handler = handler@{ env, ctx, req ->
                acceptFields(req.fields, FIELDS)?.let { return@handler Outcome.Err(it) }
                try {
                    if (req.itemId != null) itemMode(env, ctx, req, req.itemId) else healthMode(env, ctx, req)
                } catch (e: PerRootConfigUnavailableException) {
                    Outcome.Err(AdvanceSupport.configUnavailable(e.message))
                }
            },
            data = { it }
        )

    // ---- item mode ---------------------------------------------------------------------------------------

    private suspend fun itemMode(
        env: LifecycleToolServices,
        ctx: io.github.jpicklyk.mcptask.current.platform.call.CallContext,
        req: Request,
        itemRef: IdRef
    ): Outcome<JsonObject> {
        val faults = ArrayList<FieldViolation>()
        if (req.ancestorId != null) faults.add(FieldViolation(pointer("ancestorId"), "is allowed only without itemId"))
        if (req.limit != null) faults.add(FieldViolation(pointer("limit"), "is allowed only without itemId"))
        if (req.cursor != null) faults.add(FieldViolation(pointer("cursor"), "is allowed only without itemId"))
        if (faults.isNotEmpty()) return Outcome.Err(invalidFields(faults))
        val id =
            when (val target = resolveTarget(env, ctx, itemRef, "itemId")) {
                is Outcome.Err -> return target
                is Outcome.Ok -> target.value
            }
        val item = env.workItemRepository().getById(id) ?: return Outcome.Err(itemNotFound(id))
        recordCallTargetVersion(item.id, item.version)
        recordCallRoot(item.rootId ?: item.id.takeIf { item.parentId == null })

        // canAdvance is the advance own policy evaluation of start (ownership excluded); the schema, notes and violations
        // come from the SAME read unit, so missing / missingBySeat / violations never disagree with canAdvance / blockedBy.
        val gate = env.gateStatusQuery().inspect(item)
        val decision = gate.decision
        val canAdvance = decision is Decision.Allow
        val schema = gate.schema
        val isTerminal = item.role == Role.TERMINAL
        // A lifecycle-free item (null role) has no phase: no phase context, seats, dispatch or status-graph row.
        val role = item.role
        val phase = gate.phase
        val missing = gate.missing
        val missingBySeat = gate.missingBySeat
        val violations = gate.violations
        val seats = role?.let { schema?.seatsForRole(it) } ?: emptyList()
        val dispatchBySeat =
            if (seats.isEmpty() || role == null) emptyMap() else env.configResolver.resolveDispatchBySeat(item, schema)[role] ?: emptyMap()
        val dispatch = role?.let { env.configResolver.resolveDispatchProfile(item, it, schema) }
        val blockers = if (decision is Decision.Reject) dependencyBlockers(env, ctx, decision) else emptyList()
        val chains = ancestorChains(env, listOf(item), "ancestors" in req.fields.orEmpty())
        val wire = role?.name?.lowercase()

        return Outcome.Ok(
            buildJsonObject {
                put(
                    "item",
                    withAncestors(
                        buildJsonObject {
                            ItemView.compact(item).forEach { (k, v) -> put(k, v) }
                            with(ItemView) { blockedMembers(item) }
                        },
                        item,
                        chains,
                        ctx.scope
                    )
                )
                put(
                    "schema",
                    buildExpectedNotesJson(
                        schema = schema?.notes,
                        existingNoteKeys = gate.notes.map { it.key }.toSet(),
                        filledNoteKeys = gate.filledKeys,
                        seatAware = schema?.isSeatAware() == true
                    )
                )
                put(
                    "gateStatus",
                    buildJsonObject {
                        put("canAdvance", canAdvance)
                        wire?.let { put("phase", it) }
                        put("missing", JsonArray(missing.map { JsonPrimitive(it) }))
                        buildMissingBySeatJson(missingBySeat)?.let { put("missingBySeat", it) }
                        NoteSchemaJson.buildViolationsArray(violations)?.let { put("violations", it) }
                        if (!canAdvance && !isTerminal) decision.blockedByWire()?.let { put("blockedBy", it) }
                        if (blockers.isNotEmpty()) put("blockers", JsonArray(blockers))
                        val contended = ((decision as? Decision.Reject)?.context as? RejectContext.Lease)?.contended.orEmpty()
                        if (contended.isNotEmpty()) put("contended", JsonArray(contended.map { JsonPrimitive(it) }))
                    }
                )
                if (decision is Decision.Allow) {
                    put(
                        "next",
                        buildJsonObject {
                            put("trigger", Trigger.User.START.wire)
                            decision.target?.let { put("role", it.name.lowercase()) }
                        }
                    )
                }
                put("transitions", transitions(schema, item))
                phase?.guidanceKey?.let { put("guidanceKey", it) }
                phase?.skillPointer?.let { put("skillPointer", it) }
                phase?.let { putSkillExtras(it) }
                dispatch?.let { put("dispatch", buildDispatchProfileJson(it)) }
                buildSeatsJson(seats)?.let { put("seats", it) }
                buildDispatchBySeatFlatJson(dispatchBySeat)?.let { put("dispatchBySeat", it) }
                // The caller's seat, only for its own item: the `seats` above then describe the caller's seat.
                ctx.seat?.takeIf { it.itemId == item.id }?.let { put("callerSeat", it.seat) }
                claimDetail(env, item)?.let { put("claimDetail", it) }
                decisions(env, item)?.let { put("decisions", it) }
                resourceLeases(env, ctx, item)?.let { put("resourceLeases", it) }
            }
        )
    }

    /** The unsatisfied blockers of a rejected dependency gate the caller may see (decision D-e): `{id, role?, threshold}`. */
    private suspend fun dependencyBlockers(
        env: LifecycleToolServices,
        ctx: io.github.jpicklyk.mcptask.current.platform.call.CallContext,
        decision: Decision.Reject
    ): List<JsonObject> {
        val unsatisfied = (decision.context as? RejectContext.Dependency)?.unsatisfied ?: return emptyList()
        val visible =
            if (ctx.scope.isUnrestricted) {
                null
            } else {
                env.workItemRepository().filterVisible(unsatisfied.map { it.blockerId }.toSet(), ctx.scope)
            }
        return unsatisfied
            .filter { visible == null || it.blockerId in visible }
            .map { blocker ->
                buildJsonObject {
                    put("id", blocker.blockerId.toString())
                    blocker.role?.let { put("role", it.name.lowercase()) }
                    put("threshold", blocker.threshold.name.lowercase())
                }
            }
    }

    /** The row of the status graph for [item] role under its resolved [schema]: trigger to target role, `resume` resolved to `previousRole`. */
    private fun transitions(
        schema: io.github.jpicklyk.mcptask.current.model.schema.WorkItemSchema?,
        item: WorkItem
    ): JsonObject =
        buildJsonObject {
            val role = item.role ?: return@buildJsonObject
            for ((trigger, target) in graph.rowFor(schema, role)) {
                if (target == StatusGraphBuilder.PREVIOUS_ROLE_SENTINEL) {
                    item.previousRole?.let { put(trigger, it.name.lowercase()) }
                } else {
                    put(trigger, target)
                }
            }
        }

    /**
     * The item's decision requests that are open, acknowledged or answered, newest first, as `{id, class, status,
     * option?}` (withdrawn ones are left out); null when there is none. A provider that serves no decision store (its
     * `decisionReads()` throws [UnsupportedOperationException], as the interface default does) has none.
     */
    private suspend fun decisions(
        env: LifecycleToolServices,
        item: WorkItem
    ): JsonArray? {
        val shown = DecisionRequestStatus.entries.filterTo(HashSet()) { it != DecisionRequestStatus.WITHDRAWN }
        val found =
            env.unitOfWork.read {
                val store =
                    try {
                        stores.decisionReads()
                    } catch (e: UnsupportedOperationException) {
                        return@read null
                    }
                store.list(itemId = item.id, statuses = shown)
            } ?: return null
        return if (found.isEmpty()) null else JsonArray(found.map { contextEntry(it) })
    }

    /** One entry of `decisions[]`: `{id, class, status, option?}`. */
    private fun contextEntry(decision: DecisionRequest): JsonObject =
        buildJsonObject {
            put("id", decision.id.toString())
            put("class", decision.decisionClass)
            put("status", decision.status.wire)
            decision.answer?.option?.let { put("option", it) }
        }

    /** Full claim detail: the only sanctioned disclosure of a claim identity (a diagnostic for stalled or expired claims). */
    private suspend fun claimDetail(
        env: LifecycleToolServices,
        item: WorkItem
    ): JsonObject? {
        val holder = item.claimedBy ?: return null
        return buildJsonObject {
            put("claimedBy", holder)
            item.claimedAt?.let { put("claimedAt", it.toString()) }
            item.claimExpiresAt?.let { put("claimExpiresAt", it.toString()) }
            item.originalClaimedAt?.let { put("originalClaimedAt", it.toString()) }
            // The predicate every claim decision uses: expired at or after the expiry instant.
            put("isExpired", !ClaimState.isActive(item, env.clock.unitNow()))
        }
    }

    /**
     * Resource lease disclosure: the declared resources plus any lease the item holds, with holder identity (this select
     * is the sanctioned holder diagnostic, like [claimDetail]). Null when the item neither declares nor holds one.
     */
    private suspend fun resourceLeases(
        env: LifecycleToolServices,
        ctx: io.github.jpicklyk.mcptask.current.platform.call.CallContext,
        item: WorkItem
    ): JsonArray? {
        val declared = env.configResolver.resolveResourceRequirements(item)
        val declaredKeys = declared.map { it.key }
        if (declaredKeys.isEmpty()) return null
        val leases = env.stores.resourceLeaseRepository()
        val own = leases.findActiveForItem(item.id).associateBy { it.resourceKey }
        val anyHolder = leases.findActiveByKeys(declaredKeys).associateBy { it.resourceKey }
        val requirementByKey = declared.associateBy { it.key }
        val keys = (declaredKeys + own.keys).distinct()
        // Decision D-e: a holder outside the caller's scope is no topology oracle - its identity fields are omitted (the entry
        // itself, a key the target declares, stays; `held` is about the target's own lease).
        val visibleHolders =
            if (ctx.scope.isUnrestricted) {
                null
            } else {
                env.workItemRepository().filterVisible(anyHolder.values.map { it.holderItemId }.toSet(), ctx.scope)
            }
        return JsonArray(
            keys.map { key ->
                val ownLease = own[key]
                val lease = anyHolder[key] ?: ownLease
                buildJsonObject {
                    put("key", key)
                    put("mode", (requirementByKey[key]?.mode ?: ResourceMode.ADVISORY).name.lowercase())
                    put("held", ownLease != null)
                    lease?.takeIf { visibleHolders == null || it.holderItemId in visibleHolders }?.let {
                        put("holderItemId", it.holderItemId.toString())
                        it.acquiredByActorId?.let { actor -> put("acquiredByActorId", actor) }
                        put("expiresAt", it.expiresAt.toString())
                    }
                }
            }
        )
    }

    // ---- health mode -------------------------------------------------------------------------------------

    private suspend fun healthMode(
        env: LifecycleToolServices,
        ctx: io.github.jpicklyk.mcptask.current.platform.call.CallContext,
        req: Request
    ): Outcome<JsonObject> {
        val page =
            when (val p = pageRequest(req.limit, req.cursor, PageRequest.DEFAULT_LIMIT, MAX_LIMIT)) {
                is Outcome.Err -> return p
                is Outcome.Ok -> p.value
            }
        val ancestorId =
            when (val r = resolveOptionalId(env, ctx, req.ancestorId, "ancestorId")) {
                is Outcome.Err -> return r
                is Outcome.Ok -> r.value
            }
        val view = env.workViewQueries.context(ContextScope(ancestorId), page, ctx.scope)
        recordCallResultCounts(view.active.total, null)
        val stalled = stalledEntries(env, view)
        val chains = ancestorChains(env, view.active.items + view.blocked.items, "ancestors" in req.fields.orEmpty())
        return Outcome.Ok(
            buildJsonObject {
                put("active", buildJsonObject { putPage(view.active) { withAncestors(ItemView.compact(it), it, chains, ctx.scope) } })
                put(
                    "blocked",
                    buildJsonObject {
                        putPage(view.blocked) { item ->
                            val blockType = if (item.role == Role.BLOCKED) "explicit" else "dependency"
                            var entry = ItemView.with(ItemView.compact(item), "blockType", JsonPrimitive(blockType))
                            item.effectiveBlockedReason?.let { entry = ItemView.with(entry, "reason", JsonPrimitive(it.wire)) }
                            item.blockedRef?.let { entry = ItemView.with(entry, "decisionId", JsonPrimitive(it.toString())) }
                            withAncestors(entry, item, chains, ctx.scope)
                        }
                    }
                )
                put("stalled", JsonArray(stalled))
                put("claimSummary", claimJson(view.claimSummary))
            }
        )
    }

    /**
     * The active items on the page whose resolved schema has unfilled required notes for their current role:
     * `{id, title, role, missing[], guidanceKey?, skillPointer?}`. Notes come from the view batched read; the schema of
     * each item from the config resolver.
     */
    private suspend fun stalledEntries(
        env: LifecycleToolServices,
        view: ContextView
    ): List<JsonObject> {
        val out = ArrayList<JsonObject>()
        for (item in view.active.items) {
            val schema = env.configResolver.resolveSchema(item) ?: continue
            val notes = view.notes[item.id].orEmpty().associateBy { it.key }
            val role = item.role ?: continue
            val phase = computePhaseNoteContext(role, schema, notes) ?: continue
            if (phase.missingKeys.isEmpty()) continue
            out.add(
                buildJsonObject {
                    put("id", ItemView.shortId(item.id))
                    put("title", item.title)
                    put("role", role.name.lowercase())
                    put("missing", JsonArray(phase.missingKeys.map { JsonPrimitive(it) }))
                    phase.guidanceKey?.let { put("guidanceKey", it) }
                    phase.skillPointer?.let { put("skillPointer", it) }
                    putSkillExtras(phase)
                }
            )
        }
        return out
    }

    private fun claimJson(counts: ClaimStatusCounts): JsonObject =
        buildJsonObject {
            put("active", counts.active)
            put("expired", counts.expired)
            put("unclaimed", counts.unclaimed)
        }
}
