package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools.advance

import io.github.jpicklyk.mcptask.current.application.api.CallContext
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.api.advance.ReleaseRequest
import io.github.jpicklyk.mcptask.current.application.port.ReleaseResult
import io.github.jpicklyk.mcptask.current.application.port.WriteScope
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/** `advance` op `release`: the holder gives up its claim on an item. */
internal val releaseOp =
    OpSpec<ReleaseRequest, JsonElement>(
        name = "release",
        doc = "Release the claim the calling actor holds on an item. An item claimed by someone else, or by no one, is not_claim_holder.",
        profile = ToolProfile.ORCHESTRATION,
        capability = ApiCapability.ADVANCE,
        request = serializer<ReleaseRequest>(),
        errors =
            setOf(
                ErrorCode.NOT_FOUND,
                ErrorCode.AMBIGUOUS_ID,
                ErrorCode.FORBIDDEN,
                ErrorCode.NOT_CLAIM_HOLDER,
                ErrorCode.UNAUTHENTICATED
            ),
        execute = { env, ctx, req -> runRelease(env, ctx, req) }
    )

private suspend fun WriteScope.runRelease(
    env: ToolExecutionContext,
    ctx: CallContext,
    req: ReleaseRequest
): Outcome<JsonElement> {
    val holder =
        when (val resolved = AdvanceSupport.holderId(env, ctx)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    val id =
        when (val resolved = AdvanceSupport.resolveInScope(stores, ctx, req.itemId)) {
            is Outcome.Err -> return resolved
            is Outcome.Ok -> resolved.value
        }
    val result =
        when (val outcome = env.claimService.release(id, holder)) {
            is Outcome.Err -> return outcome
            is Outcome.Ok -> outcome.value
        }
    return when (result) {
        is ReleaseResult.Success ->
            Outcome.Ok(
                buildJsonObject {
                    put("itemId", result.item.id.toString())
                    put("released", true)
                }
            )
        is ReleaseResult.NotClaimedByYou -> Outcome.Err(AdvanceSupport.notClaimHolder(result.itemId))
        is ReleaseResult.NotFound -> Outcome.Err(AdvanceSupport.itemNotFound(result.itemId))
    }
}
