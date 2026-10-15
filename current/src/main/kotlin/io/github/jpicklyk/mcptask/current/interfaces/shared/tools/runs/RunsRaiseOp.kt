package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ScopeGuard
import io.github.jpicklyk.mcptask.current.application.api.ScopedIds
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.of
import io.github.jpicklyk.mcptask.current.application.api.runs.RunsRaiseRequest
import io.github.jpicklyk.mcptask.current.application.decision.DecisionRaiseCommand
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.application.service.AdvanceOutcome
import io.github.jpicklyk.mcptask.current.application.service.BlockInput
import io.github.jpicklyk.mcptask.current.application.service.eventRootOf
import io.github.jpicklyk.mcptask.current.application.support.toDomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.lifecycle.SeatCaller
import io.github.jpicklyk.mcptask.current.domain.model.BlockedReason
import io.github.jpicklyk.mcptask.current.domain.model.DecisionRequestOption
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigInvalidException
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.Run
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance.AdvanceSupport
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer
import java.util.UUID

/** runs_write raise: raise a decision request for an answer. */
internal val runsRaiseOp =
    OpSpec<RunsRaiseRequest, JsonObject>(
        name = "raise",
        doc =
            "Raise a decision. Needs itemId, runId or rootId (a seat token supplies its run and item). The class sets " +
                "the answerers and default severity. Re-raising a dedupeKey in the same scope returns the pending or " +
                "answered decision (existing: true), or idempotency_mismatch if class, question or options differ. " +
                "Payload at most 4096 bytes.",
        profiles = orchestration(),
        capability = ApiCapability.RUNS,
        request = serializer<RunsRaiseRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.IDEMPOTENCY_MISMATCH,
                ErrorCode.PAYLOAD_TOO_LARGE
            ),
        execute = { env, ctx, req -> raiseDecision(env, ctx, req) }
    )

private const val OPTION_KEY = "key"
private const val OPTION_LABEL = "label"
private const val OPTION_CONSEQUENCE = "consequence"
private val OPTION_MEMBERS = setOf(OPTION_KEY, OPTION_LABEL, OPTION_CONSEQUENCE)

/**
 * Resolves the run, item and root (task-scope D3: ids under scope, forbidden before not_found; the item's root, else the
 * run's, else rootId; they must agree), checks the scope of the root, then hands the raise to the decision service
 * (lifecycle, class, payload and dedupe rules).
 */
private suspend fun WriteScope.raiseDecision(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: RunsRaiseRequest
): Outcome<JsonObject> {
    if (req.block == true && req.itemId == null && ctx.seat?.itemId == null) {
        return Outcome.Err(RunsSupport.invalid("/block", "needs an item (itemId or the seat token's item)"))
    }
    val options =
        when (val r = options(req.options)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val links =
        when (val r = links(req.links)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val repo = stores.workItemRepository()
    val token = ctx.seat

    val run: Run? =
        when {
            req.runId != null ->
                when (val r = RunsSupport.loadRun(stores, ctx, req.runId)) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return r
                }
            token != null ->
                stores.runStore().getById(token.runId) ?: return Outcome.Err(RunsSupport.runNotFound(token.runId.toString()))
            else -> null
        }

    val itemId: UUID? =
        when {
            req.itemId != null ->
                when (val r = ScopedIds.resolveTarget(repo, ctx.scope, req.itemId, "/itemId")) {
                    is Outcome.Ok -> r.value
                    is Outcome.Err -> return r
                }
            else -> token?.itemId
        }
    val item: WorkItem? = itemId?.let { repo.getById(it) ?: return Outcome.Err(RunsSupport.notFoundItem(it)) }
    val itemRoot = item?.let { eventRootOf(it, stores.hierarchyStore()) }
    if (item != null && run != null && itemRoot != run.rootId) {
        return Outcome.Err(
            RunsSupport.invalid("/itemId", "belongs to root $itemRoot, not the run's root ${run.rootId}", item.id.toString())
        )
    }

    val explicitRoot: UUID? =
        req.rootId?.let { ref ->
            when (val r = ScopedIds.resolveTarget(repo, ctx.scope, ref, "/rootId")) {
                is Outcome.Ok -> r.value
                is Outcome.Err -> return r
            }
        }
    val rootId =
        itemRoot ?: run?.rootId ?: explicitRoot
            ?: return Outcome.Err(RunsSupport.invalid("/rootId", "one of itemId, runId or rootId is required"))
    if (explicitRoot != null && explicitRoot != rootId) {
        return Outcome.Err(
            RunsSupport.invalid("/rootId", "does not match the root $rootId of the item or run", explicitRoot.toString())
        )
    }
    if (item == null && run == null) {
        val root = repo.getById(rootId) ?: return Outcome.Err(RunsSupport.notFoundItem(rootId))
        if (root.parentId != null) {
            return Outcome.Err(RunsSupport.invalid("/rootId", "must be a root item (it has a parent)", rootId.toString()))
        }
    }
    when (val guard = ScopeGuard.check(ctx.scope, rootId, repo)) {
        is Outcome.Err -> return guard
        is Outcome.Ok -> Unit
    }

    val command =
        DecisionRaiseCommand(
            rootId = rootId,
            runId = run?.id,
            itemId = item?.id,
            decisionClass = req.`class`,
            question = req.question,
            options = options,
            recommended = req.recommended,
            links = links,
            context = req.context,
            dedupeKey = req.dedupeKey,
            severity = req.severity?.domain,
            raisedById = RunPermissions.callerId(ctx),
            raisedByKind = RunPermissions.openerKind(ctx),
            raisedBySeat = token?.seat
        )
    val result =
        when (val r = env.decisionService.raise(command)) {
            is Outcome.Ok -> r.value
            is Outcome.Err -> return r
        }
    val decision = result.decision
    var blocked: Boolean? = null
    if (req.block == true && item != null) {
        // Same unit as the raise: a refused block returns Err, which rolls the raise back (no decision row, no event).
        blocked =
            when {
                !decision.status.isPending -> false
                item.role == Role.BLOCKED && item.blockedRef == decision.id -> true
                else -> {
                    val outcome =
                        try {
                            env.advanceServiceFactory().forItem(item).advance(
                                item = item,
                                trigger = "block",
                                summary = null,
                                actorClaim = ctx.principal.claim,
                                verification = ctx.principal.verification,
                                enforceResourceLeases = true,
                                assignmentId = ctx.seat?.assignmentId,
                                seatCaller = SeatCaller.of(ctx),
                                block = BlockInput(BlockedReason.AWAITING_DECISION, decision.id)
                            )
                        } catch (e: PerRootConfigUnavailableException) {
                            return Outcome.Err(AdvanceSupport.configUnavailable(e.message))
                        } catch (e: PerRootConfigInvalidException) {
                            return Outcome.Err(e.toDomainError())
                        }
                    when (outcome) {
                        is AdvanceOutcome.Failure -> return Outcome.Err(AdvanceSupport.rooted(outcome.failure.error))
                        is AdvanceOutcome.Success -> true
                    }
                }
            }
    }
    val view = DecisionView.full(decision, env.clock.now(), ctx.principalsVisible)
    val extra = LinkedHashMap<String, JsonElement>()
    extra["existing"] = JsonPrimitive(result.existing)
    blocked?.let { extra["blocked"] = JsonPrimitive(it) }
    return Outcome.Ok(JsonObject(view + extra))
}

/**
 * The request [raw] options as decision options. Each element must be an object of `key` and `label` (strings) and an
 * optional `consequence` (a string); a missing or non-string member is `/options/<i>/<member>`, another member is
 * `/options/<i>`. The count, key, length and uniqueness rules are checked by the decision service.
 */
private fun options(raw: List<JsonObject>): Outcome<List<DecisionRequestOption>> {
    val out = ArrayList<DecisionRequestOption>(raw.size)
    val faults = ArrayList<FieldViolation>()
    raw.forEachIndexed { i, obj ->
        val extra = obj.keys.filter { it !in OPTION_MEMBERS }
        if (extra.isNotEmpty()) faults += FieldViolation("/options/$i", "unknown member(s): ${extra.sorted().joinToString(", ")}")
        val key = string(obj[OPTION_KEY])
        val label = string(obj[OPTION_LABEL])
        val consequence = string(obj[OPTION_CONSEQUENCE])
        if (key == null) faults += FieldViolation("/options/$i/$OPTION_KEY", "is required and must be a string")
        if (label == null) faults += FieldViolation("/options/$i/$OPTION_LABEL", "is required and must be a string")
        if (obj.containsKey(OPTION_CONSEQUENCE) && obj[OPTION_CONSEQUENCE] !is JsonNull && consequence == null) {
            faults += FieldViolation("/options/$i/$OPTION_CONSEQUENCE", "must be a string")
        }
        if (key != null && label != null) out += DecisionRequestOption(key, label, consequence)
    }
    return if (faults.isEmpty()) Outcome.Ok(out) else Outcome.Err(invalidFields(faults))
}

private fun string(value: JsonElement?): String? =
    when (value) {
        null, is JsonNull, is JsonObject, is JsonArray -> null
        is JsonPrimitive -> value.takeIf { it.isString }?.content
    }
