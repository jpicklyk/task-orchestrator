package io.github.jpicklyk.mcptask.current.identity

/**
 * What the MCP SDK knows about a Streamable HTTP transport session id (the `Mcp-Session-Id` header value).
 *
 * `McpSessionPrincipals` binds a principal only to an id the SDK serves ([isLive]) and closes the SDK session when it
 * evicts a binding ([end]), so the binding map stays a subset of the SDK's live sessions.
 */
interface SdkSessions {
    /** True when the SDK currently serves a session whose transport id is [sessionId]. */
    fun isLive(sessionId: String): Boolean

    /** Ends the SDK session with transport id [sessionId] (the SDK then answers 404 for it). No-op when none. */
    suspend fun end(sessionId: String)

    /**
     * Ends SDK sessions whose transport never got a session id (a rejected request left them behind) once they have
     * been seen that way for the grace period. Returns the number ended.
     */
    suspend fun endOrphans(): Int
}
