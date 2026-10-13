package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.telemetry.currentCallTelemetry
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult

/**
 * Who made a call: the actor [claim] and the [verification] result recorded for it, and, when a credential
 * established the caller, the credential [principal]. No trust policy is applied when it is built; handlers decide
 * what a given verification status allows.
 *
 * [principal] is the transport-established principal the claim was synthesized from (`api:<id>` for a credential,
 * `local` for the local principal; kind external); null only for a hand-built principal in tests.
 */
data class CallPrincipal(
    val claim: ActorClaim,
    val verification: VerificationResult,
    val principal: Principal? = null
)

/**
 * Per-call context handed to every 4.0 tool handler next to its decoded request.
 *
 * Request classes never declare `actor`: the transport establishes [principal] in one place, and a call reaches a
 * handler only with one (the dispatcher refuses a call without a principal as `unauthenticated`). [reqId] is the call's
 * correlation id, [surface] the transport (`mcp` or `rest`) and [scope] what the caller may see: handlers check
 * every item they touch with [ScopeGuard]. [attributionVisible] says whether the caller may see note attribution (a
 * note view's `actor` and `verification`); the transport decides it, so application code never inspects capabilities.
 * It defaults to false (hidden). Proof evidence is never emitted, whatever its value. [principalsVisible] says whether the
 * caller may see other principals' ids in an aggregate (the `report` `principal` grouping); the transport decides it too
 * (ADMIN only), and it defaults to false. [profile] is the wire name of
 * the tool view that served the call (set by the dispatcher); [sessionId] is the caller's MCP session (null on REST and stdio).
 */
data class CallContext(
    val reqId: String,
    val surface: String,
    val principal: CallPrincipal,
    val scope: AccessScope = AccessScope.unrestricted(),
    val attributionVisible: Boolean = false,
    val principalsVisible: Boolean = false,
    val profile: String? = null,
    val sessionId: String? = null
) {
    /**
     * The provenance tuple of this call, or null when its [CallPrincipal] carries no transport [Principal] (only a
     * hand-built test principal). The principal id is the audited actor id ([CallPrincipal.claim]); run and seat come from
     * the call's telemetry when one is installed.
     */
    suspend fun provenance(): AnswerProvenance? {
        val callPrincipal = principal
        val credential = callPrincipal.principal ?: return null
        val telemetry = currentCallTelemetry()
        return AnswerProvenance(
            principalId = callPrincipal.claim.id,
            kind = credential.kind,
            proofStatus = credential.proofStatus,
            host = credential.host,
            sessionId = sessionId,
            runId = telemetry?.runId,
            seat = telemetry?.seat,
            profile = profile
        )
    }
}
