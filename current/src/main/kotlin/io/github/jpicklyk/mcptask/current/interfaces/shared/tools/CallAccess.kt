package io.github.jpicklyk.mcptask.current.interfaces.shared.tools

import io.github.jpicklyk.mcptask.current.application.api.CallPrincipal
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability

/**
 * What a transport established about a call before the dispatcher runs: who made it ([principal], null
 * means the dispatcher lifts the body `actor`, the MCP default), what it may see ([scope]) and what it may
 * do ([capabilities]).
 *
 * REST builds one from the authenticated bearer principal. MCP stays [UNRESTRICTED] until W4; the parity
 * harness passes the same access to both transports.
 */
data class CallAccess(
    val principal: CallPrincipal?,
    val scope: AccessScope,
    val capabilities: Set<ApiCapability>
) {
    companion object {
        val UNRESTRICTED = CallAccess(null, AccessScope.unrestricted(), setOf(ApiCapability.ADMIN))
    }
}
