package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * What the MCP SDK knows about a Streamable HTTP transport session id (the `Mcp-Session-Id` header value).
 *
 * [McpSessionPrincipals] binds a principal only to an id the SDK serves ([isLive]) and closes the SDK session when it
 * evicts a binding ([end]), so the binding map stays a subset of the SDK's live sessions.
 */
interface SdkSessions {
    /** True when the SDK currently serves a session whose transport id is [sessionId]. */
    fun isLive(sessionId: String): Boolean

    /** Ends the SDK session with transport id [sessionId] (the SDK then answers 404 for it). No-op when none. */
    suspend fun end(sessionId: String)
}

/**
 * The production [SdkSessions]: reads the public `Server.sessions` registry of every served [Server].
 *
 * Honest limit: liveness is the SDK registry, so an id is live from `initialize` until its transport closes (a
 * `DELETE`, [end], or server shutdown). A client that merely disconnects stays live until [McpSessionPrincipals]
 * evicts it for idleness or capacity.
 */
class ServerSdkSessions : SdkSessions {
    private val logger = LoggerFactory.getLogger(ServerSdkSessions::class.java)
    private val servers = CopyOnWriteArrayList<Server>()

    /** Registers [server] (one per tool profile) as a source of sessions. */
    fun serve(server: Server) {
        servers.addIfAbsent(server)
    }

    override fun isLive(sessionId: String): Boolean =
        servers.any { server -> server.sessions.values.any { transportId(it.transport) == sessionId } }

    override suspend fun end(sessionId: String) {
        for (server in servers) {
            val session = server.sessions.values.firstOrNull { transportId(it.transport) == sessionId } ?: continue
            try {
                session.close()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.debug("Closing an MCP session during eviction failed", e)
            }
            return
        }
    }

    private fun transportId(transport: Any?): String? = (transport as? StreamableHttpServerTransport)?.sessionId
}
