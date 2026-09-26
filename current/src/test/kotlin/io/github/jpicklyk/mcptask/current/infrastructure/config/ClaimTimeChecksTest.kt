package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Independent test-author coverage for item 3dcfcbab, ADDENDUM 2 (fix cycle round 2, commit
 * 009c4867): direct unit coverage of [ClaimTimeChecks.isExpired] and
 * [ClaimTimeChecks.isNotYetValid] at nanosecond precision around the AUTH_CLOCK_SKEW_SECONDS
 * (60s) boundary -- the exact precision cache-level (JtiReplayCache) and end-to-end verifier
 * tests cannot pin down as directly as a pure Instant-in/Boolean-out unit does.
 *
 * Oracles:
 *  - isExpired: RFC 7519 s4.1.4 (exp) "a small leeway" = AUTH_CLOCK_SKEW_SECONDS = 60L (declared
 *    constant), combined with the invariant that the verifier's last accepted instant must not
 *    exceed the replay cache's own inclusive retention deadline of exp+60s (task-scope Part 2).
 *  - isNotYetValid: RFC 7519 s4.1.5 (nbf) uses the identical "small leeway" language for the
 *    not-before claim, and task-scope Part 1 states the lifetime cap is "checked after the
 *    existing exp/nbf checks" using the SAME skew constant already declared for exp -- i.e. nbf's
 *    leeway is symmetric with exp's, applied on the early side: not-yet-valid only when
 *    now < nbf - AUTH_CLOCK_SKEW_SECONDS.
 */
class ClaimTimeChecksTest {
    private val exp: Instant = Instant.ofEpochSecond(2_000_000_000L, 0)
    private val nbf: Instant = Instant.ofEpochSecond(2_000_000_000L, 0)

    // -------------------------------------------------------------------------
    // isExpired
    // -------------------------------------------------------------------------

    @Test
    fun `isExpired is false at exactly the exp plus 60s skew boundary`() {
        assertFalse(
            ClaimTimeChecks.isExpired(now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS), exp = exp),
            "exactly at exp+60s the token must still be accepted (inclusive boundary)"
        )
    }

    @Test
    fun `isExpired is true one nanosecond past the exp plus 60s skew boundary`() {
        assertTrue(
            ClaimTimeChecks.isExpired(now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(1), exp = exp),
            "one nanosecond past exp+60s the token must read as expired"
        )
    }

    @Test
    fun `isExpired is true 500 microseconds past the exp plus 60s skew boundary`() {
        assertTrue(
            ClaimTimeChecks.isExpired(now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusNanos(500_000), exp = exp),
            "500 microseconds past exp+60s the token must already read as expired"
        )
    }

    @Test
    fun `isExpired is false one millisecond before the exp plus 60s skew boundary (positive control)`() {
        assertFalse(
            ClaimTimeChecks.isExpired(now = exp.plusSeconds(AUTH_CLOCK_SKEW_SECONDS).minusMillis(1), exp = exp),
            "one millisecond before exp+60s the token must still be accepted"
        )
    }

    // -------------------------------------------------------------------------
    // isNotYetValid
    // -------------------------------------------------------------------------

    @Test
    fun `isNotYetValid is false at exactly the nbf minus 60s skew boundary`() {
        assertFalse(
            ClaimTimeChecks.isNotYetValid(now = nbf.minusSeconds(AUTH_CLOCK_SKEW_SECONDS), nbf = nbf),
            "exactly at nbf-60s the token must already be considered valid (inclusive boundary)"
        )
    }

    @Test
    fun `isNotYetValid is true one nanosecond before the nbf minus 60s skew boundary`() {
        assertTrue(
            ClaimTimeChecks.isNotYetValid(now = nbf.minusSeconds(AUTH_CLOCK_SKEW_SECONDS).minusNanos(1), nbf = nbf),
            "one nanosecond before nbf-60s the token must still read as not yet valid"
        )
    }

    @Test
    fun `isNotYetValid is true 500 microseconds before the nbf minus 60s skew boundary`() {
        assertTrue(
            ClaimTimeChecks.isNotYetValid(
                now = nbf.minusSeconds(AUTH_CLOCK_SKEW_SECONDS).minusNanos(500_000),
                nbf = nbf
            ),
            "500 microseconds before nbf-60s the token must still read as not yet valid"
        )
    }

    @Test
    fun `isNotYetValid is false one millisecond after the nbf minus 60s skew boundary (positive control)`() {
        assertFalse(
            ClaimTimeChecks.isNotYetValid(now = nbf.minusSeconds(AUTH_CLOCK_SKEW_SECONDS).plusMillis(1), nbf = nbf),
            "one millisecond after nbf-60s the token must already be considered valid"
        )
    }
}
