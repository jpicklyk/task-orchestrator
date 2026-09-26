package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * In-memory, single-instance replay cache for JWT `(iss, jti)` pairs.
 *
 * Used by [JwksActorVerifier] when `actor_authentication.verifier.jti_replay_protection` is
 * enabled: each presented `(iss, jti)` pair may be accepted at most once. An entry is retained
 * through `exp + [AUTH_CLOCK_SKEW_SECONDS]` **inclusive** — the same clock-skew leeway the
 * verifiers use elsewhere for `exp`/`nbf`/the lifetime cap (see [AUTH_CLOCK_SKEW_SECONDS] KDoc for
 * why this is a single shared constant, not a second hard-coded `60`) — because the verifier
 * itself still accepts the token at that exact instant (`expiry.before(now - skew)` is the
 * rejection test, so acceptance holds for `now <= exp + skew`). **Invariant: an entry must stay
 * live for every instant the verifier could still accept the token, and is purged only once `now`
 * is strictly after `exp + skew`** — purging at `now == exp + skew` (as an earlier version of this
 * class did) would let a replay through in a >=1ms window at exactly that boundary.
 *
 * Not persisted or shared across instances/restarts (see the item's task-scope "Non-goals") — a
 * restart or a second server instance reopens the replay window. Callers that need the memoized,
 * per-MCP-call behavior described in the class KDoc for [JwksActorVerifier] should install
 * `io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope` around the call
 * so the *same* verified proof is not re-recorded (and rejected as a "replay") within one call.
 *
 * Thread-safe: all mutating access is synchronized on this instance.
 *
 * @param maxEntries Soft capacity. When a new key must be admitted and the cache is already at
 *   capacity, expired entries are purged first; if that is not enough, the oldest-inserted entry
 *   is evicted and a rate-limited WARN is logged. This is a best-effort, per-instance control: under
 *   a flood of distinct valid proofs that admits more than [maxEntries] fresh `(iss, jti)` pairs
 *   before a live entry's retention window elapses, that live entry can be evicted early and its
 *   token replayed. Eviction is global, not scoped per issuer.
 * @param clock Injectable clock for deterministic tests.
 */
class JtiReplayCache(
    private val maxEntries: Int = 10_000,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(JtiReplayCache::class.java)

    // LinkedHashMap preserves insertion order so eviction can drop the oldest entry deterministically.
    private val entries = LinkedHashMap<String, Instant>()
    private var lastEvictionWarnAt: Instant? = null

    /**
     * Checks whether `(issuer, jti)` has already been recorded and is still within its retention
     * window (`exp + [AUTH_CLOCK_SKEW_SECONDS]`, inclusive, from when it was first recorded); if
     * not, records it.
     *
     * @return `true` on first sighting (the caller should accept the token and treat this as the
     *   authoritative record), `false` when the pair was already recorded and has not yet expired
     *   (the caller should reject the token as a replay).
     */
    @Synchronized
    fun checkAndRecord(
        issuer: String?,
        jti: String,
        expiresAt: Instant
    ): Boolean {
        val now = clock.instant()
        purgeExpired(now)

        // purgeExpired(now) has already removed every entry whose retention deadline is strictly
        // before `now`, so any entry still present here is by definition still live (deadline >=
        // now) — no separate "is it still live" re-check is needed (nor correct: re-checking with a
        // strict `isAfter` would reintroduce the exact off-by-one this class fixes at the deadline
        // instant itself).
        val key = key(issuer, jti)
        if (entries.containsKey(key)) {
            return false
        }

        if (entries.size >= maxEntries) {
            evictOldest(now)
        }

        entries[key] = expiresAt.plusSeconds(AUTH_CLOCK_SKEW_SECONDS)
        return true
    }

    /** Removes entries whose retention deadline is strictly before [now]; a deadline == now is kept. */
    private fun purgeExpired(now: Instant) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isBefore(now)) {
                iterator.remove()
            }
        }
    }

    private fun evictOldest(now: Instant) {
        val oldest = entries.entries.firstOrNull() ?: return
        entries.remove(oldest.key)
        val lastWarn = lastEvictionWarnAt
        if (lastWarn == null || lastWarn.plusSeconds(EVICTION_WARN_RATE_LIMIT_SECONDS).isBefore(now)) {
            logger.warn(
                "JtiReplayCache at capacity ({} entries); evicting the oldest entry to admit a new " +
                    "one. Consider raising maxEntries or lowering max_token_lifetime_seconds.",
                maxEntries
            )
            lastEvictionWarnAt = now
        }
    }

    private fun key(
        issuer: String?,
        jti: String
    ): String = "${issuer ?: ""}\u0000$jti"

    companion object {
        private const val EVICTION_WARN_RATE_LIMIT_SECONDS = 60L
    }
}
