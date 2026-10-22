package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.ops.toCallAccess
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditId
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.CallAccess
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The result of [McpSessionPrincipals.bind]. */
enum class BindOutcome {
    /** The session is bound to the same audited principal (newly or already). */
    BOUND,

    /** The session belongs to another principal; the caller must refuse the request. */
    OTHER_PRINCIPAL,

    /** The SDK does not serve this session id; nothing was bound. */
    UNKNOWN
}

/**
 * The principal each MCP-over-HTTP session runs as (R14), bounded.
 *
 * [McpBearerAuth] resolves the caller on EVERY `/mcp` request and binds the session to it: from the `Mcp-Session-Id`
 * response header of the `initialize` request, or on the first request that carries the session id, whichever comes
 * first. Only an id the SDK serves ([SdkSessions.isLive]) is ever bound; an unknown id is [BindOutcome.UNKNOWN] and
 * stores nothing (the SDK answers its own 404). Once bound, a session belongs to that principal: another principal on
 * it is [BindOutcome.OTHER_PRINCIPAL] (compared by the audited id). A tool call reads the binding through [access]; an
 * unbound session gets an access with no principal, which the dispatcher refuses (fail closed). `DELETE /mcp` forgets
 * the binding.
 *
 * Lifecycle: a bound id is trusted until forgotten or evicted (the liveness check runs on the first bind only). On a
 * new bind, eviction drops bindings whose SDK session already closed, then ends and drops sessions idle for at least
 * [idleTtl], then ends and drops the least-recently-seen binding of the principal holding the most sessions (the binder counted
 * with the bind in progress; ties go to the older binding) until there is room under [maxSessions], so one credential's
 * flood ends its own sessions first. Ending closes the SDK
 * session, so the client's next request is the spec's 404 and it re-initializes. There is no background sweeper: an
 * idle server keeps idle sessions until the next new bind.
 *
 * @param redactNoteAttribution `API_REDACT_NOTE_ATTRIBUTION`: the same note-attribution rule as REST.
 * @param sdkSessions the SDK's view of its sessions (no default: a forgotten argument must not fail open).
 */
class McpSessionPrincipals(
    private val redactNoteAttribution: Boolean,
    private val sdkSessions: SdkSessions,
    private val clock: Clock = Clock.SYSTEM,
    private val idleTtl: Duration = DEFAULT_IDLE_TTL,
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS
) {
    init {
        require(!idleTtl.isZero && !idleTtl.isNegative) { "idleTtl must be positive" }
        require(maxSessions >= 1) { "maxSessions must be at least 1" }
    }

    private class Entry(
        val principal: Principal,
        @Volatile var lastSeen: Instant
    )

    private val bindings = ConcurrentHashMap<String, Entry>()

    /** The number of bound sessions. */
    val size: Int get() = bindings.size

    /** Binds [sessionId] to [principal] if it is unbound and the SDK serves it; every call on a bound id refreshes its idle clock. */
    suspend fun bind(
        sessionId: String,
        principal: Principal
    ): BindOutcome {
        val now = clock.now()
        bindings[sessionId]?.let { return compare(it, principal, now) }
        if (!sdkSessions.isLive(sessionId)) return BindOutcome.UNKNOWN
        evict(now, keep = sessionId, binder = principal)
        val existing = bindings.putIfAbsent(sessionId, Entry(principal, now)) ?: return BindOutcome.BOUND
        return compare(existing, principal, now)
    }

    private fun compare(
        entry: Entry,
        principal: Principal,
        now: Instant
    ): BindOutcome =
        if (entry.principal.auditId() == principal.auditId()) {
            entry.lastSeen = now
            BindOutcome.BOUND
        } else {
            BindOutcome.OTHER_PRINCIPAL
        }

    private suspend fun evict(
        now: Instant,
        keep: String,
        binder: Principal
    ) {
        for (id in bindings.keys.toList()) {
            if (id != keep && !sdkSessions.isLive(id)) bindings.remove(id)
        }
        for ((id, entry) in bindings.entries.toList()) {
            if (id != keep && Duration.between(entry.lastSeen, now) >= idleTtl) {
                bindings.remove(id)
                sdkSessions.end(id)
            }
        }
        val binderHolder = binder.auditId()
        while (bindings.size >= maxSessions) {
            val candidates = bindings.entries.filter { it.key != keep }
            val counts = candidates.groupingBy { it.value.principal.auditId() }.eachCount().toMutableMap()
            counts[binderHolder] = (counts[binderHolder] ?: 0) + 1
            val largest = counts.values.max()
            val victim =
                candidates
                    .filter { counts.getValue(it.value.principal.auditId()) == largest }
                    .minByOrNull { it.value.lastSeen } ?: break
            bindings.remove(victim.key)
            sdkSessions.end(victim.key)
        }
    }

    /** Ends SDK sessions that never completed `initialize` within the grace; returns how many were ended. */
    suspend fun sweepOrphans(): Int = sdkSessions.endOrphans()

    /** The principal [sessionId] is bound to, or null. */
    fun principalOf(sessionId: String): Principal? = bindings[sessionId]?.principal

    /**
     * The [CallAccess] of a tool call made in [sessionId]: the bound principal's access (its scope, its capabilities,
     * the note-attribution rule) carrying the session id, or an access with no principal when the session is unknown or
     * unbound.
     */
    fun access(sessionId: String?): CallAccess {
        val principal = sessionId?.let { bindings[it]?.principal } ?: return CallAccess.unauthenticated(sessionId)
        return principal.toCallAccess(redactNoteAttribution).copy(sessionId = sessionId)
    }

    /** Forgets [sessionId]'s binding (the session ended). */
    fun forget(sessionId: String) {
        bindings.remove(sessionId)
    }

    companion object {
        /** Sessions idle this long are ended on the next new bind. */
        val DEFAULT_IDLE_TTL: Duration = Duration.ofHours(24)

        /** The most sessions bound at once; the least-recently-seen is ended to make room. */
        const val DEFAULT_MAX_SESSIONS: Int = 1024
    }
}
