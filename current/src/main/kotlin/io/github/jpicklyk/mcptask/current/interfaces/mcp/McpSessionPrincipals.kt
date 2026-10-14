package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditId
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import java.util.concurrent.ConcurrentHashMap

/**
 * The principal each MCP-over-HTTP session runs as (R14).
 *
 * [McpBearerAuth] resolves the caller on EVERY `/mcp` request and binds the session to it: from the `Mcp-Session-Id`
 * response header of the `initialize` request, or on the first request that carries the session id, whichever comes
 * first. Once bound, a session belongs to that principal: a request carrying the session id that resolves to another
 * principal is refused `unauthenticated` (compared by the audited id, so `api:x` and `local` never match). A tool call
 * reads the binding through [access]; a session with no binding gets an access with no principal, which the
 * dispatcher refuses (fail closed). `DELETE /mcp` (the session's end) forgets the binding; nothing else evicts one.
 *
 * @param redactNoteAttribution `API_REDACT_NOTE_ATTRIBUTION`: the same note-attribution rule as REST, so a READ bearer
 *   over `/mcp` sees what it would see over `/api/v1`.
 */
class McpSessionPrincipals(
    private val redactNoteAttribution: Boolean
) {
    private val bindings = ConcurrentHashMap<String, Principal>()

    /**
     * Binds [sessionId] to [principal] unless it is already bound. True when the session is (now) bound to the same
     * audited principal, false when it belongs to another one (the caller must then refuse the request).
     */
    fun bind(
        sessionId: String,
        principal: Principal
    ): Boolean {
        val existing = bindings.putIfAbsent(sessionId, principal) ?: return true
        return existing.auditId() == principal.auditId()
    }

    /** The principal [sessionId] is bound to, or null. */
    fun principalOf(sessionId: String): Principal? = bindings[sessionId]

    /**
     * The [CallAccess] of a tool call made in [sessionId]: the bound principal's access (its scope, its capabilities,
     * the note-attribution rule) carrying the session id, or an access with no principal when the session is unknown or
     * unbound.
     */
    fun access(sessionId: String?): CallAccess {
        val principal = sessionId?.let { bindings[it] } ?: return CallAccess.unauthenticated(sessionId)
        return principal.toCallAccess(redactNoteAttribution).copy(sessionId = sessionId)
    }

    /** Forgets [sessionId]'s binding (the session ended). */
    fun forget(sessionId: String) {
        bindings.remove(sessionId)
    }
}
