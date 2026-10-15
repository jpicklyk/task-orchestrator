package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.advance.ClaimRequest
import io.github.jpicklyk.mcptask.current.application.api.advance.HeartbeatRequest
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.FieldViolation
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidFields
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.runLimits
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.runs.withPaused
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.serializer

/**
 * `advance` op `heartbeat`: the holder extends its active claim. Unlike `claim`, it never takes an item: an item the
 * caller does not hold (unclaimed, lapsed or someone else's) is not_claim_holder.
 *
 * `usage` (R22, task-scope D9) reports the calling seat's absolute spend `{tokens, costUsd}`; it needs a seat token
 * (`invalid_request /usage` without one), each field must be a non-negative finite number (tokens an integer), and per
 * assignment the highest value reported is kept, field by field.
 */
internal val heartbeatOp =
    OpSpec<HeartbeatRequest, JsonElement>(
        name = "heartbeat",
        doc =
            "Extend the active claim the calling actor holds on an item. " +
                "Never takes an item: not_claim_holder unless the caller holds it. usage needs a seat token.",
        profiles = orchestration(),
        capability = ApiCapability.ADVANCE,
        request = serializer<HeartbeatRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> runHeartbeat(env, ctx, req) }
    )

private suspend fun WriteScope.runHeartbeat(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: HeartbeatRequest
): Outcome<JsonElement> {
    val holder = AdvanceSupport.holder(ctx)
    if (req.ttlSeconds !in 1..ClaimRequest.MAX_TTL_SECONDS) {
        return Outcome.Err(invalidField("/ttlSeconds", "must be between 1 and ${ClaimRequest.MAX_TTL_SECONDS}", req.ttlSeconds.toString()))
    }
    val usage =
        when (val parsed = usage(req.usage, ctx)) {
            is Outcome.Err -> return parsed
            is Outcome.Ok -> parsed.value
        }
    val id =
        when (val resolved = AdvanceSupport.resolveInScope(stores, ctx, req.itemId)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    val item =
        when (val loaded = AdvanceSupport.loadItem(stores, id)) {
            is Outcome.Err -> return loaded
            is Outcome.Ok -> loaded.value
        }
    if (!holder.holds(item) || !ClaimState.isActive(item, now)) return Outcome.Err(AdvanceSupport.notClaimHolder(id))

    val result =
        when (val outcome = env.claimService.claim(id, holder, req.ttlSeconds)) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
    val seat = ctx.seat
    if (usage != null && seat != null && result is ClaimResult.Success) {
        val recorded = env.seatService.reportUsage(seat.assignmentId, usage.tokens, usage.costMicroUsd)
        if (recorded is Outcome.Err) return recorded
    }
    // The budget check (R27) runs after the heartbeat's own writes, in its unit; a crossing still lets the heartbeat land.
    val paused =
        if (seat != null && result is ClaimResult.Success) {
            when (val checked = env.runLimits.checkBudget(seat.runId)) {
                is Outcome.Err -> return checked
                is Outcome.Ok -> checked.value
            }
        } else {
            null
        }
    return when (result) {
        is ClaimResult.Success ->
            Outcome.Ok(
                claimedElement(result.item, result.releasedItemIds, refreshed = true, selected = false, claimRef = null).withPaused(paused)
            )
        is ClaimResult.AlreadyClaimed -> Outcome.Err(AdvanceSupport.claimHeld(result))
        is ClaimResult.NotFound -> Outcome.Err(AdvanceSupport.itemNotFound(result.itemId))
        is ClaimResult.TerminalItem -> Outcome.Err(AdvanceSupport.claimTerminal(result.itemId))
        is ClaimResult.LifecycleFree -> Outcome.Err(AdvanceSupport.claimLifecycleFree(result.itemId))
    }
}

/** A seat's reported absolute usage: [tokens] and [costMicroUsd] (micro-USD); a null field was not reported. */
private class UsageReport(
    val tokens: Long?,
    val costMicroUsd: Long?
)

private const val USAGE_TOKENS = "tokens"
private const val USAGE_COST = "costUsd"

/**
 * The `usage` object of a heartbeat, or null when absent. It needs a seat token (`/usage`); `tokens` must be a
 * non-negative integer (`/usage/tokens`) and `costUsd` a non-negative finite number of US dollars (`/usage/costUsd`);
 * any other member is `/usage/<member>`.
 */
private fun usage(
    raw: JsonObject?,
    ctx: CallContext
): Outcome<UsageReport?> {
    if (raw == null) return Outcome.Ok(null)
    if (ctx.seat == null) return Outcome.Err(invalidField("/usage", "requires a seat token"))
    val faults = ArrayList<FieldViolation>()
    for (key in raw.keys) {
        if (key != USAGE_TOKENS && key != USAGE_COST) faults += FieldViolation("/usage/$key", "is not a usage field (tokens, costUsd)")
    }
    var tokens: Long? = null
    raw[USAGE_TOKENS]?.let { value ->
        val n = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (n == null || n < 0) {
            faults += FieldViolation("/usage/$USAGE_TOKENS", "must be a non-negative integer", value.toString().take(100))
        } else {
            tokens = n
        }
    }
    var cost: Long? = null
    raw[USAGE_COST]?.let { value ->
        val d = (value as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        val micro = if (d != null && d.isFinite() && d >= 0) ConfigValidator.costMicroUsd(d) else null
        if (micro == null || micro < 0) {
            faults += FieldViolation("/usage/$USAGE_COST", "must be a non-negative finite number of US dollars", value.toString().take(100))
        } else {
            cost = micro
        }
    }
    if (faults.isNotEmpty()) return Outcome.Err(invalidFields(faults))
    return Outcome.Ok(UsageReport(tokens, cost))
}
