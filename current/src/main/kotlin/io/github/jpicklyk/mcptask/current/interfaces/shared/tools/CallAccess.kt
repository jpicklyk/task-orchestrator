package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.port.AccessScope

/**
 * What a transport established about a call before the dispatcher runs: who made it ([principal], null
 * means the dispatcher lifts the body `actor`, the MCP default), what it may see ([scope]) and what it may
 * do ([capabilities]).
 *
 * [attributionVisible] says whether the caller may see who wrote a note and how that actor was verified (a note view's
 * `actor` and `verification`); it defaults to false so an access nobody decided about hides attribution. REST computes it
 * with the `API_REDACT_NOTE_ATTRIBUTION` rule (visible unless redaction is on and the caller lacks ADMIN). Proof evidence
 * (the proof hash and verified claims) is never visible on any surface, whatever this says.
 *
 * REST builds one from the authenticated bearer principal. MCP stays [UNRESTRICTED] until W4; the parity
 * harness passes the same access to both transports.
 */
data class CallAccess(
    val principal: CallPrincipal?,
    val scope: AccessScope,
    val capabilities: Set<ApiCapability>,
    val attributionVisible: Boolean = false
) {
    companion object {
        val UNRESTRICTED = CallAccess(null, AccessScope.unrestricted(), setOf(ApiCapability.ADMIN), attributionVisible = true)
    }
}
