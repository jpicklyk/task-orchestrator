package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.identity.SdkSessions
import io.github.jpicklyk.mcptask.current.platform.port.Clock
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.StreamableHttpServerTransport
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

/**
 * The production [SdkSessions]: reads the public `Server.sessions` registry of every served [Server].
 *
 * Honest limit: liveness is the SDK registry, so an id is live from `initialize` until its transport closes (a
 * `DELETE`, [end], the SDK's idle sweep, or server shutdown). A client that disconnects loses its GET stream at once
 * (the SDK cancels it) and its session when the SDK's idle sweep expires it, or when `McpSessionPrincipals` evicts it
 * for capacity. Over HTTP on kotlin-sdk 0.16 a rejected request leaves no id-less session, so [endOrphans] is a no-op
 * there (kept: harmless).
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
