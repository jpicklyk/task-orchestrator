package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.LOCAL_PRINCIPAL
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal

/**
 * What a transport established about a call before the dispatcher runs: who made it ([principal]), what it may see
 * ([scope]) and what it may do ([capabilities]).
 *
 * A null [principal] means no transport established one: the dispatcher answers the call `unauthenticated` before it
 * reads anything else (no request body ever names its own actor).
 *
 * [attributionVisible] says whether the caller may see who wrote a note and how that actor was verified (a note view's
 * `actor` and `verification`); it defaults to false so an access nobody decided about hides attribution. Every transport
 * computes it with the `API_REDACT_NOTE_ATTRIBUTION` rule (visible unless redaction is on and the caller lacks ADMIN).
 * Proof evidence (the proof hash and verified claims) is never visible on any surface, whatever this says.
 *
 * [principalsVisible] says whether the caller may see other principals' ids in an aggregate (the `report` `principal`
 * grouping, W6 decision 10). It follows ADMIN in [capabilities] unless a transport passes it explicitly.
 *
 * [sessionId] is the caller's MCP session (null on REST and stdio); the dispatcher copies it into
 * `CallContext.sessionId` so the call's provenance carries it.
 */
data class CallAccess(
    val principal: CallPrincipal?,
    val scope: AccessScope,
    val capabilities: Set<ApiCapability>,
    val attributionVisible: Boolean = false,
    val principalsVisible: Boolean = ApiCapability.ADMIN in capabilities,
    val sessionId: String? = null
) {
    companion object {
        /** The access of the local principal (`local`, ADMIN, unrestricted): stdio and the local `/mcp` mode. */
        val LOCAL =
            CallAccess(LOCAL_PRINCIPAL.toCallPrincipal(), AccessScope.unrestricted(), setOf(ApiCapability.ADMIN), attributionVisible = true)

        /** An access with no principal: every call made with it is `unauthenticated`. */
        fun unauthenticated(sessionId: String? = null): CallAccess =
            CallAccess(null, AccessScope.unrestricted(), emptySet(), sessionId = sessionId)
    }
}
