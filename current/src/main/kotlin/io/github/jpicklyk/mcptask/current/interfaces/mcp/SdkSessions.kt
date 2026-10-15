package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Ends SDK sessions whose transport never got a session id (a rejected request left them behind) once they have
     * been seen that way for the grace period. Returns the number ended.
     */
    suspend fun endOrphans(): Int
}

/**
 * The production [SdkSessions]: reads the public `Server.sessions` registry of every served [Server].
 *
 * Honest limit: liveness is the SDK registry, so an id is live from `initialize` until its transport closes (a
 * `DELETE`, [end], or server shutdown). A client that merely disconnects stays live until [McpSessionPrincipals]
 * evicts it for idleness or capacity.
 */
class ServerSdkSessions(
    private val clock: Clock = Clock.SYSTEM,
    private val orphanGrace: Duration = DEFAULT_ORPHAN_GRACE
) : SdkSessions {
    private val firstSeenOrphan = ConcurrentHashMap<String, Instant>()
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
                logger.warn("Closing an MCP session during eviction failed", e)
            }
            return
        }
    }

    override suspend fun endOrphans(): Int {
        val now = clock.now()
        val seen = mutableSetOf<String>()
        var ended = 0
        for (server in servers) {
            for ((id, session) in server.sessions.entries.toList()) {
                val transport = session.transport
                if (transport !is StreamableHttpServerTransport || transport.sessionId != null) continue
                seen += id
                val first = firstSeenOrphan.putIfAbsent(id, now) ?: now
                if (Duration.between(first, now) < orphanGrace) continue
                firstSeenOrphan.remove(id)
                try {
                    session.close()
                    ended++
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logger.warn("Closing an orphaned MCP session failed", e)
                }
            }
        }
        firstSeenOrphan.keys.retainAll(seen)
        return ended
    }

    private fun transportId(transport: Any?): String? = (transport as? StreamableHttpServerTransport)?.sessionId

    companion object {
        /** How long a session may stay without a transport id before it counts as abandoned (a handshake takes milliseconds). */
        val DEFAULT_ORPHAN_GRACE: Duration = Duration.ofSeconds(30)
    }
}
