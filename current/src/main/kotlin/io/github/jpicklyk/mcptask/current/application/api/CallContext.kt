package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult

/**
 * Who made a call: the actor [claim] the transport lifted from the request and the [verification]
 * result the actor verifier produced for it. No trust policy is applied when it is built; handlers
 * (and W4) decide what a given verification status allows.
 */
data class CallPrincipal(
    val claim: ActorClaim,
    val verification: VerificationResult
)

/**
 * Per-call context handed to every 4.0 tool handler next to its decoded request.
 *
 * Request classes never declare `actor`: the transport lifts the top-level `actor` into [principal]
 * in one place (transitional until W4 replaces self-reported actors). [reqId] is the call's
 * correlation id, [surface] the transport (`mcp` or `rest`) and [scope] what the caller may see: handlers check
 * every item they touch with [ScopeGuard]. [attributionVisible] says whether the caller may see note attribution (a
 * note view's `actor` and `verification`); the transport decides it, so application code never inspects capabilities.
 * It defaults to false (hidden). Proof evidence is never emitted, whatever its value.
 */
data class CallContext(
    val reqId: String,
    val surface: String,
    val principal: CallPrincipal?,
    val scope: AccessScope = AccessScope.unrestricted(),
    val attributionVisible: Boolean = false
)
