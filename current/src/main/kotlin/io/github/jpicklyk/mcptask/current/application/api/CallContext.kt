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
 * [principal] is set by REST (the claim is then synthesized from it: `api:<id>`, kind external) and null on MCP's
 * lifted-`actor` path.
 */
data class CallPrincipal(
    val claim: ActorClaim,
    val verification: VerificationResult,
    val principal: Principal? = null
)

/**
 * Per-call context handed to every 4.0 tool handler next to its decoded request.
 *
 * Request classes never declare `actor`: the transport establishes [principal] in one place. [reqId] is the call's
 * correlation id, [surface] the transport (`mcp` or `rest`) and [scope] what the caller may see: handlers check
 * every item they touch with [ScopeGuard]. [attributionVisible] says whether the caller may see note attribution (a
 * note view's `actor` and `verification`); the transport decides it, so application code never inspects capabilities.
 * It defaults to false (hidden). Proof evidence is never emitted, whatever its value. [principalsVisible] says whether the
 * caller may see other principals' ids in an aggregate (the `report` `principal` grouping); the transport decides it too
 * (ADMIN only), and it defaults to false. [rootWritesAllowed] says whether the caller may create a depth-0 root or move an
 * item to the top level; the transport decides it (an unrestricted scope or ADMIN), and it defaults to whether [scope] is
 * unrestricted. [profile] is the wire name of
 * the tool view that served the call (set by the dispatcher); [sessionId] is the caller's session, null until
 * sessions exist.
 */
data class CallContext(
    val reqId: String,
    val surface: String,
    val principal: CallPrincipal?,
    val scope: AccessScope = AccessScope.unrestricted(),
    val attributionVisible: Boolean = false,
    val principalsVisible: Boolean = false,
    val profile: String? = null,
    val sessionId: String? = null,
    val rootWritesAllowed: Boolean = scope.isUnrestricted
) {
    /**
     * The provenance tuple of this call, or null when no credential [Principal] established the caller (MCP's
     * lifted-`actor` path). The principal id is the audited actor id ([CallPrincipal.claim]); run and seat come from
     * the call's telemetry when one is installed.
     */
    suspend fun provenance(): AnswerProvenance? {
        val callPrincipal = principal ?: return null
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
