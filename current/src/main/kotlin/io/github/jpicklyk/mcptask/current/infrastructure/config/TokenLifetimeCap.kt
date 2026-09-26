package io.github.jpicklyk.mcptask.current.infrastructure.config

import java.time.Duration
import java.time.Instant

/**
 * Shared 60-second clock-skew leeway used across JWT verification (`exp`/`nbf`/lifetime checks in
 * [JwksActorVerifier] and `JwksApiVerifier`) and [JtiReplayCache]'s retention window. A single
 * source of truth keeps the replay cache's retention derived from the same leeway the verifiers
 * use, rather than a second hard-coded `60` that could silently drift out of sync with it.
 */
const val AUTH_CLOCK_SKEW_SECONDS = 60L

/**
 * Sane upper bound (~100 years) for `actor_authentication.verifier.max_token_lifetime_seconds` /
 * `API_JWKS_MAX_TOKEN_LIFETIME_SECONDS`. Configuring a cap near [Long.MAX_VALUE] would overflow
 * when [AUTH_CLOCK_SKEW_SECONDS] is added in [TokenLifetimeCap.violation]; both config loaders
 * reject a value above this ceiling at parse time (same startup-failure path as the existing
 * `<= 0` check), so the addition inside [TokenLifetimeCap.violation] can never overflow.
 */
const val MAX_TOKEN_LIFETIME_SECONDS_CEILING = 3_153_600_000L // 100 years, in seconds

/**
 * Pure predicate for the actor-token lifetime cap, shared by [JwksActorVerifier] and
 * `JwksApiVerifier` (`interfaces/api/v1/auth`) so the two verifiers' identical logic cannot drift
 * apart (they were previously duplicated verbatim in each class).
 *
 * Semantics (both checks use [AUTH_CLOCK_SKEW_SECONDS] leeway):
 *  - (a) always applies, independent of [iat]: `exp - now` must not exceed
 *    `maxTokenLifetimeSeconds + skew`. This alone bounds how long a stolen token stays usable from
 *    now, and is immune to a forged/absent `iat`.
 *  - (b) only when [iat] is present: reject a future-dated `iat`, and reject an honestly-declared
 *    over-long token (`exp - iat > maxTokenLifetimeSeconds + skew`) even when (a) alone would still
 *    pass. `iat` is OPTIONAL per RFC 7519 §4.1.6, so its absence never fails the token on its own —
 *    only (a) applies then.
 *
 * `maxTokenLifetimeSeconds` is assumed already bounded to at most [MAX_TOKEN_LIFETIME_SECONDS_CEILING]
 * by the caller's config loader (both loaders enforce this at parse time), so `maxTokenLifetimeSeconds
 * + AUTH_CLOCK_SKEW_SECONDS` below can never overflow [Long].
 *
 * @return a rejection reason string, or `null` if the token satisfies the cap.
 */
object TokenLifetimeCap {
    fun violation(
        now: Instant,
        exp: Instant,
        iat: Instant?,
        maxTokenLifetimeSeconds: Long
    ): String? {
        val capWithSkew = maxTokenLifetimeSeconds + AUTH_CLOCK_SKEW_SECONDS
        if (Duration.between(now, exp).seconds > capWithSkew) {
            return "token lifetime exceeds maximum"
        }
        if (iat != null) {
            if (iat.isAfter(now.plusSeconds(AUTH_CLOCK_SKEW_SECONDS))) {
                return "iat in the future"
            }
            if (Duration.between(iat, exp).seconds > capWithSkew) {
                return "token lifetime exceeds maximum"
            }
        }
        return null
    }
}

/**
 * Pure, nanosecond-precision predicates for the `exp`/`nbf` clock-skew checks, shared by
 * [JwksActorVerifier] and `JwksApiVerifier` (`interfaces/api/v1/auth`) so the two cannot drift
 * apart.
 *
 * Both claims arrive as [java.util.Date] (millisecond precision) from Nimbus's `JWTClaimsSet`, and
 * an earlier version of this check built its skew-adjusted cutoff the same way — via
 * `Date.from(clock.instant().minusSeconds(skew))` — which **truncates that cutoff Instant down to
 * the millisecond**, silently widening acceptance by up to just under 1ms past the intended
 * boundary (reproducible: a token timed within that final sub-millisecond window at `exp + skew`
 * is wrongly accepted, and — because [JtiReplayCache]'s retention deadline is the exact Instant
 * `exp + AUTH_CLOCK_SKEW_SECONDS` with no such truncation — a jti recorded there could still be
 * replayed against the verifier in that same window).
 *
 * These predicates instead take [Instant] end to end (`exp.toInstant()` / `nbf.toInstant()` vs.
 * [Clock.instant]) so the comparison keeps full nanosecond precision and never has to round the
 * cutoff at all: the verifier's last accepted instant for `exp` is now exactly
 * `exp + AUTH_CLOCK_SKEW_SECONDS`, matching the replay cache's inclusive retention deadline
 * precisely at that boundary — not up to ~1ms past it.
 */
object ClaimTimeChecks {
    /** `true` if `exp` is more than [AUTH_CLOCK_SKEW_SECONDS] in the past relative to [now]. */
    fun isExpired(
        now: Instant,
        exp: Instant
    ): Boolean = exp.isBefore(now.minusSeconds(AUTH_CLOCK_SKEW_SECONDS))

    /** `true` if `nbf` is more than [AUTH_CLOCK_SKEW_SECONDS] in the future relative to [now]. */
    fun isNotYetValid(
        now: Instant,
        nbf: Instant
    ): Boolean = nbf.isAfter(now.plusSeconds(AUTH_CLOCK_SKEW_SECONDS))
}
