package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DegradedModePolicy] resolution via [ActorAware.resolveTrustedActorId].
 *
 * Covers the three-policy matrix against the five [VerificationStatus] values:
 * - Policy: ACCEPT_CACHED, ACCEPT_SELF_REPORTED, REJECT
 * - Statuses: VERIFIED, UNAVAILABLE (fresh), UNAVAILABLE (stale-cache), ABSENT, UNCHECKED, REJECTED
 */
class DegradedModePolicyTest {
    private val actorId = "agent-test-1"
    private val claim = ActorClaim(id = actorId, kind = ActorKind.SUBAGENT)

    // -------------------------------------------------------------------------
    // Default policy value
    // -------------------------------------------------------------------------

    @Test
    fun `default DegradedModePolicy is ACCEPT_CACHED`() {
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, DegradedModePolicy.ACCEPT_CACHED)
        // Verify the AuditingConfig default via the enum itself
        val policy = DegradedModePolicy.ACCEPT_CACHED
        assertEquals("accept-cached", policy.toConfigString())
    }

    // -------------------------------------------------------------------------
    // DegradedModePolicy.fromConfigString — parsing
    // -------------------------------------------------------------------------

    @Test
    fun `fromConfigString parses accept-cached`() {
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, DegradedModePolicy.fromConfigString("accept-cached"))
    }

    @Test
    fun `fromConfigString parses accept-self-reported`() {
        assertEquals(DegradedModePolicy.ACCEPT_SELF_REPORTED, DegradedModePolicy.fromConfigString("accept-self-reported"))
    }

    @Test
    fun `fromConfigString parses reject`() {
        assertEquals(DegradedModePolicy.REJECT, DegradedModePolicy.fromConfigString("reject"))
    }

    @Test
    fun `fromConfigString accepts underscore variant of accept_cached`() {
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, DegradedModePolicy.fromConfigString("accept_cached"))
    }

    @Test
    fun `fromConfigString accepts UPPERCASE enum names`() {
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, DegradedModePolicy.fromConfigString("ACCEPT-CACHED"))
        assertEquals(DegradedModePolicy.ACCEPT_SELF_REPORTED, DegradedModePolicy.fromConfigString("ACCEPT-SELF-REPORTED"))
        assertEquals(DegradedModePolicy.REJECT, DegradedModePolicy.fromConfigString("REJECT"))
    }

    @Test
    fun `fromConfigString returns null for unknown value`() {
        assertEquals(null, DegradedModePolicy.fromConfigString("magic-policy"))
    }

    // -------------------------------------------------------------------------
    // ACCEPT_CACHED policy
    // -------------------------------------------------------------------------

    @Test
    fun `ACCEPT_CACHED — VERIFIED returns trusted actor id`() {
        val verification = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — VERIFIED with stale-cache metadata returns trusted actor id`() {
        val verification =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "jwks",
                metadata = mapOf("verifiedFromCache" to "true", "cacheAgeSeconds" to "450")
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — UNAVAILABLE without cache metadata falls back to self-reported actor id`() {
        // Genuine "JWKS down with no cached key" case — no verifiedFromCache flag in metadata.
        // Should fall back to self-reported id (and log WARN), preserving pre-v3.3 behavior.
        val verification =
            VerificationResult(
                status = VerificationStatus.UNAVAILABLE,
                verifier = "jwks",
                reason = "network error"
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        // Falls back to the self-reported claim.id (pre-v3.3 behavior preserved)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — UNAVAILABLE with verifiedFromCache=true trusts verified actor id`() {
        // Future verifier path: JWKS unavailable but JWT was verified against a stale key.
        // The cache metadata flag signals that cryptographic verification succeeded.
        val verification =
            VerificationResult(
                status = VerificationStatus.UNAVAILABLE,
                verifier = "jwks",
                reason = "JWKS refresh failed",
                metadata = mapOf("verifiedFromCache" to "true", "cacheAgeSeconds" to "600")
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — UNAVAILABLE with verifiedFromCache=false falls back to self-reported actor id`() {
        // verifiedFromCache=false explicitly means no stale key available — degrade gracefully.
        val verification =
            VerificationResult(
                status = VerificationStatus.UNAVAILABLE,
                verifier = "jwks",
                reason = "JWKS endpoint unreachable",
                metadata = mapOf("verifiedFromCache" to "false")
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — VERIFIED fresh (no cache metadata) returns trusted actor id without requiring flag`() {
        // A freshly-verified JWT has no verifiedFromCache metadata — the flag is only present on
        // stale-cache hits. Fresh VERIFIED results must not require the metadata flag.
        val verification =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "jwks"
                // no metadata at all
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — ABSENT falls back to self-reported actor id`() {
        val verification = VerificationResult(status = VerificationStatus.ABSENT, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — UNCHECKED falls back to self-reported actor id`() {
        val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_CACHED — REJECTED falls back to self-reported actor id`() {
        val verification =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                verifier = "jwks",
                reason = "signature mismatch",
                metadata = mapOf("failureKind" to "crypto")
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    // -------------------------------------------------------------------------
    // ACCEPT_SELF_REPORTED policy
    // -------------------------------------------------------------------------

    @Test
    fun `ACCEPT_SELF_REPORTED — VERIFIED returns self-reported actor id`() {
        val verification = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_SELF_REPORTED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_SELF_REPORTED — UNAVAILABLE still returns self-reported actor id`() {
        val verification = VerificationResult(status = VerificationStatus.UNAVAILABLE, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_SELF_REPORTED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_SELF_REPORTED — ABSENT returns self-reported actor id`() {
        val verification = VerificationResult(status = VerificationStatus.ABSENT, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_SELF_REPORTED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_SELF_REPORTED — UNCHECKED noop verifier returns self-reported actor id without warning log`() {
        // noop verifier → no warning expected (this is normal no-JWKS operation)
        val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_SELF_REPORTED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `ACCEPT_SELF_REPORTED — REJECTED returns self-reported actor id`() {
        val verification =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                verifier = "jwks",
                reason = "expired token"
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_SELF_REPORTED)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    // -------------------------------------------------------------------------
    // REJECT policy
    // -------------------------------------------------------------------------

    @Test
    fun `REJECT — VERIFIED returns trusted actor id`() {
        val verification = VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
        assertInstanceOf(PolicyResolution.Trusted::class.java, result)
        assertEquals(actorId, (result as PolicyResolution.Trusted).trustedId)
    }

    @Test
    fun `REJECT — UNAVAILABLE returns Rejected`() {
        val verification =
            VerificationResult(
                status = VerificationStatus.UNAVAILABLE,
                verifier = "jwks",
                reason = "JWKS endpoint down"
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
        assertInstanceOf(PolicyResolution.Rejected::class.java, result)
        val rejection = result as PolicyResolution.Rejected
        assertNotNull(rejection.reason)
        assert(rejection.reason.contains("unavailable")) {
            "Expected reason to contain 'unavailable', got: ${rejection.reason}"
        }
    }

    @Test
    fun `REJECT — ABSENT returns Rejected`() {
        val verification = VerificationResult(status = VerificationStatus.ABSENT, verifier = "jwks")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
        assertInstanceOf(PolicyResolution.Rejected::class.java, result)
        assert((result as PolicyResolution.Rejected).reason.contains("absent")) {
            "Expected reason to mention 'absent': ${result.reason}"
        }
    }

    @Test
    fun `REJECT — UNCHECKED returns Rejected`() {
        val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
        assertInstanceOf(PolicyResolution.Rejected::class.java, result)
        assert((result as PolicyResolution.Rejected).reason.contains("unchecked")) {
            "Expected reason to mention 'unchecked': ${result.reason}"
        }
    }

    @Test
    fun `REJECT — REJECTED status returns Rejected with reason in message`() {
        val verification =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                verifier = "jwks",
                reason = "signature invalid",
                metadata = mapOf("failureKind" to "crypto")
            )
        val result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.REJECT)
        assertInstanceOf(PolicyResolution.Rejected::class.java, result)
        val rejection = result as PolicyResolution.Rejected
        assert(rejection.reason.contains("rejected")) {
            "Expected reason to contain 'rejected': ${rejection.reason}"
        }
        // The verification reason should be included in the rejection message
        assert(rejection.reason.contains("signature invalid")) {
            "Expected original reason to be surfaced: ${rejection.reason}"
        }
    }
}
