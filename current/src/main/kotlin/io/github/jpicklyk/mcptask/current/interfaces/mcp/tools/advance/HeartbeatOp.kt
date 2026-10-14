package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.advance.ClaimRequest
import io.github.jpicklyk.mcptask.current.application.api.advance.HeartbeatRequest
import io.github.jpicklyk.mcptask.current.application.port.ClaimResult
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.ClaimState
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.invalidField
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.orchestration
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * `advance` op `heartbeat`: the holder extends its active claim. Unlike `claim`, it never takes an item: an item the
 * caller does not hold (unclaimed, lapsed or someone else's) is not_claim_holder.
 */
internal val heartbeatOp =
    OpSpec<HeartbeatRequest, JsonElement>(
        name = "heartbeat",
        doc =
            "Extend the active claim the calling actor holds on an item. " +
                "Never takes an item: not_claim_holder unless the caller holds it.",
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
        when (val outcome = env.claimService.claim(id, holder.principalId, req.ttlSeconds)) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
    return when (result) {
        is ClaimResult.Success ->
            Outcome.Ok(claimedElement(result.item, result.releasedItemIds, refreshed = true, selected = false, claimRef = null))
        is ClaimResult.AlreadyClaimed -> Outcome.Err(AdvanceSupport.claimHeld(result))
        is ClaimResult.NotFound -> Outcome.Err(AdvanceSupport.itemNotFound(result.itemId))
        is ClaimResult.TerminalItem -> Outcome.Err(AdvanceSupport.claimTerminal(result.itemId))
        is ClaimResult.LifecycleFree -> Outcome.Err(AdvanceSupport.claimLifecycleFree(result.itemId))
    }
}
