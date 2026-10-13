package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date

/**
 * Test-author coverage for item 385444c2 (R8): the single [JwtVerifier] pipeline, its [JwtPolicy]
 * presets, and the two claim mappers ([JwksActorVerifier], [JwksApiVerifier]) that sit on top of it.
 *
 * Oracles (frozen in the item's task-scope and test-plan at queue phase): O-SCOPE = task-scope
 * sections 1-3 (pipeline order, failure kinds, decisions D2/D7/D8); O-ORDER = crypto is checked
 * before claims (a bad-signature, exp-less proof is `crypto`, not `claims`); O-DOC = a stale key
 * set is surfaced through the cache state, never silently.
 *
 * Scenario ids S1-S11 follow the test-plan numbering. Every scenario that has a forActor and a
 * forApi reading runs under BOTH presets in one test.
 */
class JwtVerifierTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("jv-rsa").generate()
        private val rotatedKey: RSAKey = RSAKeyGenerator(2048).keyID("jv-rotated").generate()
        private val ecKey = ECKeyGenerator(Curve.P_256).keyID("jv-ec").generate()

        private val NOW: Instant = Instant.parse("2026-10-13T12:00:00Z")
        private val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        private const val ISS = "https://idp.jv.example"
        private const val AUD = "jv-audience"
        private const val A_DID = "did:web:a.jv.example"
        private val FRESH = CacheState(fromStaleCache = false, ageSeconds = null)
    }

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    private fun actorConfig(
        requireSubMatch: Boolean = true,
        didAllowlist: List<String> = emptyList(),
        didLooseKidMatch: Boolean = true,
        jtiReplayProtection: Boolean = false,
    ) = VerifierConfig.Jwks(
        jwksPath = "/unused-in-unit-tests",
        issuer = if (didAllowlist.isEmpty()) ISS else null,
        audience = if (didAllowlist.isEmpty()) AUD else null,
        algorithms = if (didAllowlist.isEmpty()) listOf("RS256") else emptyList(),
        requireSubMatch = requireSubMatch,
        didAllowlist = didAllowlist,
        didLooseKidMatch = didLooseKidMatch,
        jtiReplayProtection = jtiReplayProtection,
    )

    private fun apiConfig() =
        ApiAuthConfig.Jwks(
            url = "https://idp.jv.example/jwks.json",
            issuer = ISS,
            audience = AUD,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun staticPolicies(): Map<String, JwtPolicy> =
        mapOf(
            "forActor" to JwtPolicy.forActor(actorConfig()),
            "forApi" to JwtPolicy.forApi(apiConfig()),
        )

    private suspend fun eachStaticPolicy(block: suspend (name: String, policy: JwtPolicy) -> Unit) {
        staticPolicies().forEach { (name, policy) -> block(name, policy) }
    }

    private fun claims(
        subject: String? = "agent-1",
        issuer: String? = ISS,
        audience: String? = AUD,
        expiry: Instant? = NOW.plusSeconds(300),
        jti: String? = null,
    ): JWTClaimsSet {
        val b = JWTClaimsSet.Builder()
        subject?.let { b.subject(it) }
        issuer?.let { b.issuer(it) }
        audience?.let { b.audience(it) }
        expiry?.let { b.expirationTime(Date.from(it)) }
        jti?.let { b.jwtID(it) }
        return b.build()
    }

    private fun signRsa(
        claimsSet: JWTClaimsSet = claims(),
        key: RSAKey = rsaKey,
        kid: String? = key.keyID,
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).apply { kid?.let { keyID(it) } }.build()
        val jwt = SignedJWT(header, claimsSet)
        jwt.sign(RSASSASigner(key))
        return jwt.serialize()
    }

    private fun signEc(claimsSet: JWTClaimsSet = claims()): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID(ecKey.keyID).build(), claimsSet)
        jwt.sign(ECDSASigner(ecKey))
        return jwt.serialize()
    }

    private fun corruptSignature(jwt: String): String {
        val parts = jwt.split(".")
        require(parts.size == 3)
        val sig = parts[2]
        val flipped = (if (sig[0] == 'A') 'B' else 'A') + sig.substring(1)
        return "${parts[0]}.${parts[1]}.$flipped"
    }

    private fun staticProvider(
        vararg keys: JWK,
        cache: CacheState = FRESH,
    ): JwksKeySetProvider {
        val p = mockk<JwksKeySetProvider>()
        coEvery { p.getKeySet() } returns JwksResult(JWKSet(keys.map { it.toPublicJWK() }), cache)
        coEvery { p.refreshForUnknownKid(any(), any()) } returns null
        every { p.getResolvedIssuer() } returns null
        every { p.close() } just Runs
        return p
    }

    private fun didProvider(vararg keys: JWK): JwksKeySetProvider {
        val p = mockk<JwksKeySetProvider>()
        coEvery { p.getKeySetForIssuer(A_DID) } returns JwksResult(JWKSet(keys.map { it.toPublicJWK() }), FRESH)
        coEvery { p.refreshForUnknownKid(any(), any()) } returns null
        every { p.getResolvedIssuer() } returns null
        every { p.close() } just Runs
        return p
    }

    private fun verifier(
        policy: JwtPolicy,
        provider: JwksKeySetProvider,
    ) = JwtVerifier(policy, provider, CLOCK)

    private fun JwtVerdict.asVerified(): JwtVerdict.Verified = this as? JwtVerdict.Verified ?: fail("expected Verified but was $this")

    private fun JwtVerdict.asRejected(): JwtVerdict.Rejected = this as? JwtVerdict.Rejected ?: fail("expected Rejected but was $this")

    private fun JwtVerdict.asUnavailable(): JwtVerdict.Unavailable =
        this as? JwtVerdict.Unavailable ?: fail("expected Unavailable but was $this")

    private fun actor(
        id: String,
        proof: String?,
    ) = ActorClaim(id = id, kind = ActorKind.SUBAGENT, proof = proof)

    // -------------------------------------------------------------------------
    // Policy presets (D7): the mapping from each config onto the one JwtPolicy
    // -------------------------------------------------------------------------

    @Test
    fun `forActor copies the verifier config and derives requireSub didBinding and jtiReplay`() {
        val plain =
            JwtPolicy.forActor(
                VerifierConfig.Jwks(
                    jwksPath = "/x",
                    issuer = ISS,
                    audience = AUD,
                    algorithms = listOf("RS256", "ES256"),
                    maxTokenLifetimeSeconds = 1234,
                    didLooseKidMatch = false,
                    jtiReplayProtection = true,
                ),
            )
        assertEquals(listOf("RS256", "ES256"), plain.algorithms)
        assertEquals(ISS, plain.issuer)
        assertEquals(AUD, plain.audience)
        assertEquals(1234L, plain.maxTokenLifetimeSeconds)
        assertFalse(plain.requireSub, "the actor preset does not require sub; require_sub_match is expectedSubject")
        assertFalse(plain.didBinding, "no allowlist and no pattern means no DID binding")
        assertFalse(plain.didLooseKidMatch)
        assertTrue(plain.jtiReplay)

        val byAllowlist = JwtPolicy.forActor(VerifierConfig.Jwks(didAllowlist = listOf(A_DID)))
        assertTrue(byAllowlist.didBinding)
        assertTrue(byAllowlist.didLooseKidMatch, "didLooseKidMatch defaults to true on the config")
        assertFalse(byAllowlist.jtiReplay)

        val byPattern = JwtPolicy.forActor(VerifierConfig.Jwks(didPattern = "did:web:*.jv.example"))
        assertTrue(byPattern.didBinding)
    }

    @Test
    fun `forApi requires sub and never binds DID or replays jti`() {
        val p = JwtPolicy.forApi(apiConfig())
        assertEquals(listOf("RS256"), p.algorithms)
        assertEquals(ISS, p.issuer)
        assertEquals(AUD, p.audience)
        assertEquals(86400L, p.maxTokenLifetimeSeconds)
        assertTrue(p.requireSub)
        assertFalse(p.didBinding)
        assertFalse(p.jtiReplay)
    }

    // -------------------------------------------------------------------------
    // S1 - valid token
    // -------------------------------------------------------------------------

    @Test
    fun `S1 a valid RS256 token is Verified under both presets with kid alg and fresh cache state`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val v = verifier(policy, staticProvider(rsaKey)).verify(signRsa()).asVerified()
                assertEquals("agent-1", v.claims.subject, name)
                assertEquals("jv-rsa", v.kid, name)
                assertEquals("RS256", v.alg, name)
                assertFalse(v.cacheState.fromStaleCache, name)
            }
        }

    @Test
    fun `S1 the same token is VERIFIED by the actor mapper and resolves a principal in the API mapper`() =
        runTest {
            val token = signRsa()
            val actorResult =
                JwksActorVerifier(actorConfig(), staticProvider(rsaKey), CLOCK)
                    .verify(actor("agent-1", token))
            assertEquals(VerificationStatus.VERIFIED, actorResult.status, actorResult.reason)
            assertEquals("jwks", actorResult.verifier)

            val principal = JwksApiVerifier(apiConfig(), staticProvider(rsaKey), CLOCK).verify(token)
            assertNotNull(principal)
            assertEquals("agent-1", principal!!.tokenId)
        }

    // -------------------------------------------------------------------------
    // S2 - algorithm allowlist
    // -------------------------------------------------------------------------

    @Test
    fun `S2 an algorithm outside the allowlist is Rejected as policy even though its key is in the set`() =
        runTest {
            eachStaticPolicy { name, policy ->
                // The key set holds BOTH keys, so without the allowlist this ES256 token would verify.
                val r = verifier(policy, staticProvider(rsaKey, ecKey)).verify(signEc()).asRejected()
                assertEquals(FailureKind.POLICY, r.failureKind, name)
            }
        }

    @Test
    fun `S2 an empty allowlist admits any algorithm`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val open = policy.copy(algorithms = emptyList())
                val v = verifier(open, staticProvider(rsaKey, ecKey)).verify(signEc()).asVerified()
                assertEquals("ES256", v.alg, name)
            }
        }

    // -------------------------------------------------------------------------
    // S3 / S4 - crypto before claims, exp required
    // -------------------------------------------------------------------------

    @Test
    fun `S3 a corrupted signature on an exp-less token is Rejected as crypto not claims`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val token = corruptSignature(signRsa(claims(expiry = null)))
                val r = verifier(policy, staticProvider(rsaKey)).verify(token).asRejected()
                assertEquals(FailureKind.CRYPTO, r.failureKind, name)
            }
        }

    @Test
    fun `S4 a validly signed token without exp is Rejected as claims with the missing exp reason`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val r = verifier(policy, staticProvider(rsaKey)).verify(signRsa(claims(expiry = null))).asRejected()
                assertEquals(FailureKind.CLAIMS, r.failureKind, name)
                assertEquals("missing exp claim", r.reason, name)
            }
        }

    // -------------------------------------------------------------------------
    // S5 - DID binding: sub must equal iss
    // -------------------------------------------------------------------------

    @Test
    fun `S5 under DID binding sub different from iss is Rejected as claims and sub equal to iss is Verified`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID)))
            val v = verifier(policy, didProvider(rsaKey))

            val mismatch = v.verify(signRsa(claims(subject = "someone-else", issuer = A_DID, audience = null))).asRejected()
            assertEquals(FailureKind.CLAIMS, mismatch.failureKind)

            val ok = v.verify(signRsa(claims(subject = A_DID, issuer = A_DID, audience = null))).asVerified()
            assertEquals(A_DID, ok.claims.subject)
        }

    @Test
    fun `S5 under DID binding a token without iss is Rejected as claims`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID)))
            val r =
                verifier(policy, didProvider(rsaKey))
                    .verify(signRsa(claims(subject = A_DID, issuer = null, audience = null)))
                    .asRejected()
            assertEquals(FailureKind.CLAIMS, r.failureKind)
        }

    @Test
    fun `S5 an issuer the provider does not trust is Rejected as policy`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID)))
            val p = didProvider(rsaKey)
            coEvery { p.getKeySetForIssuer("did:web:evil.jv.example") } throws IssuerNotTrustedException("did:web:evil.jv.example")
            val token = signRsa(claims(subject = "did:web:evil.jv.example", issuer = "did:web:evil.jv.example", audience = null))

            val r = verifier(policy, p).verify(token).asRejected()

            assertEquals(FailureKind.POLICY, r.failureKind)
        }

    // -------------------------------------------------------------------------
    // S6 - expectedSubject (D2) is checked before the jti replay record
    // -------------------------------------------------------------------------

    @Test
    fun `S6 a rejected expectedSubject does not poison the jti replay cache`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val replaying = policy.copy(jtiReplay = true)
                val v = verifier(replaying, staticProvider(rsaKey))
                val token = signRsa(claims(subject = "y", jti = "jti-$name"))

                val wrong = v.verify(token, expectedSubject = "x").asRejected()
                assertEquals(FailureKind.CLAIMS, wrong.failureKind, name)
                assertTrue(wrong.reason.startsWith("sub mismatch"), wrong.reason)
                assertTrue(wrong.reason.contains("expected=x"), wrong.reason)
                assertTrue(wrong.reason.contains("got=y"), wrong.reason)

                // The same jti is still unseen, so the right expectation verifies.
                v.verify(token, expectedSubject = "y").asVerified()

                // And now it has been recorded: a replay is a policy rejection.
                val replay = v.verify(token, expectedSubject = "y").asRejected()
                assertEquals(FailureKind.POLICY, replay.failureKind, name)
            }
        }

    @Test
    fun `S6 with jti replay on a token without jti is Rejected as claims`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val r =
                    verifier(policy.copy(jtiReplay = true), staticProvider(rsaKey))
                        .verify(signRsa(claims(jti = null)))
                        .asRejected()
                assertEquals(FailureKind.CLAIMS, r.failureKind, name)
            }
        }

    @Test
    fun `S6 requireSub rejects a token without sub as claims while the actor preset accepts it`() =
        runTest {
            val noSub = signRsa(claims(subject = null))

            val api = verifier(JwtPolicy.forApi(apiConfig()), staticProvider(rsaKey)).verify(noSub).asRejected()
            assertEquals(FailureKind.CLAIMS, api.failureKind)
            assertEquals("missing sub claim", api.reason)

            verifier(JwtPolicy.forActor(actorConfig()), staticProvider(rsaKey)).verify(noSub).asVerified()
        }

    // -------------------------------------------------------------------------
    // S7 - unknown kid triggers exactly one refresh, then the loose-kid rule
    // -------------------------------------------------------------------------

    @Test
    fun `S7 an unknown kid refreshes once and verifies against the refreshed set`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val p = staticProvider(rsaKey)
                coEvery { p.refreshForUnknownKid("jv-rotated", null) } returns
                    JwksResult(JWKSet(listOf(rsaKey.toPublicJWK(), rotatedKey.toPublicJWK())), FRESH)

                val v = verifier(policy, p).verify(signRsa(key = rotatedKey)).asVerified()

                assertEquals("jv-rotated", v.kid, name)
                coVerify(exactly = 1) { p.refreshForUnknownKid("jv-rotated", null) }
            }
        }

    @Test
    fun `S7 an unknown kid whose refresh yields nothing is Rejected as crypto after one refresh`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val p = staticProvider(rsaKey)

                val r = verifier(policy, p).verify(signRsa(key = rotatedKey)).asRejected()

                assertEquals(FailureKind.CRYPTO, r.failureKind, name)
                assertTrue(r.reason.contains("no matching key"), r.reason)
                coVerify(exactly = 1) { p.refreshForUnknownKid("jv-rotated", null) }
            }
        }

    @Test
    fun `S7 a known kid never triggers a refresh`() =
        runTest {
            eachStaticPolicy { _, policy ->
                val p = staticProvider(rsaKey)
                verifier(policy, p).verify(signRsa()).asVerified()
                coVerify(exactly = 0) { p.refreshForUnknownKid(any(), any()) }
            }
        }

    @Test
    fun `S7 under DID binding the refresh is scoped to the issuer`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID), didLooseKidMatch = false))
            val p = didProvider(rsaKey)
            coEvery { p.refreshForUnknownKid("jv-rotated", A_DID) } returns
                JwksResult(JWKSet(listOf(rotatedKey.toPublicJWK())), FRESH)
            val token = signRsa(claims(subject = A_DID, issuer = A_DID, audience = null), key = rotatedKey)

            verifier(policy, p).verify(token).asVerified()

            coVerify(exactly = 1) { p.refreshForUnknownKid("jv-rotated", A_DID) }
        }

    @Test
    fun `S7 the loose-kid rule applies only after the refresh found nothing`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID), didLooseKidMatch = true))
            val p = didProvider(rsaKey)
            val token = signRsa(claims(subject = A_DID, issuer = A_DID, audience = null), key = rotatedKey)

            // One eligible key (kid jv-rsa), token kid jv-rotated: refresh yields null, then the
            // single-key loose match is tried - and fails on the signature because it is a different key.
            val r = verifier(policy, p).verify(token).asRejected()

            assertEquals(FailureKind.CRYPTO, r.failureKind)
            coVerify(exactly = 1) { p.refreshForUnknownKid("jv-rotated", A_DID) }

            // Same shape with the genuinely matching key under a different kid label: loose match verifies.
            val relabelled = signRsa(claims(subject = A_DID, issuer = A_DID, audience = null), key = rsaKey, kid = "other-label")
            val p2 = didProvider(rsaKey)
            verifier(policy, p2).verify(relabelled).asVerified()
            coVerify(exactly = 1) { p2.refreshForUnknownKid("other-label", A_DID) }
        }

    // -------------------------------------------------------------------------
    // S8 - fetch failure is Unavailable, never Rejected
    // -------------------------------------------------------------------------

    @Test
    fun `S8 KeySetUnavailableException from the provider is Unavailable not Rejected`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val p = staticProvider(rsaKey)
                coEvery { p.getKeySet() } throws KeySetUnavailableException("keys too stale")
                assertTrue(verifier(policy, p).verify(signRsa()) is JwtVerdict.Unavailable, name)
            }
        }

    @Test
    fun `S8 any other fetch failure is also Unavailable`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val p = staticProvider(rsaKey)
                coEvery { p.getKeySet() } throws java.io.IOException("connection reset")
                val u = verifier(policy, p).verify(signRsa()).asUnavailable()
                assertTrue(u.reason.isNotBlank(), name)
            }
        }

    @Test
    fun `S8 an unavailable DID key set is Unavailable`() =
        runTest {
            val policy = JwtPolicy.forActor(actorConfig(didAllowlist = listOf(A_DID)))
            val p = didProvider(rsaKey)
            coEvery { p.getKeySetForIssuer(A_DID) } throws KeySetUnavailableException("did keys too stale")
            val token = signRsa(claims(subject = A_DID, issuer = A_DID, audience = null))

            verifier(policy, p).verify(token).asUnavailable()
        }

    @Test
    fun `S8 cancellation from the provider propagates instead of being mapped to a verdict`() =
        runTest {
            val p = staticProvider(rsaKey)
            coEvery { p.getKeySet() } throws CancellationException("cancelled by caller")
            var propagated = false
            try {
                verifier(JwtPolicy.forApi(apiConfig()), p).verify(signRsa())
            } catch (e: CancellationException) {
                propagated = true
            }
            assertTrue(propagated, "verify must rethrow cancellation")
        }

    // -------------------------------------------------------------------------
    // S9 - stale cache state is carried through to the mappers
    // -------------------------------------------------------------------------

    @Test
    fun `S9 a stale key set still verifies and the cache state is reported`() =
        runTest {
            val stale = CacheState(fromStaleCache = true, ageSeconds = 42)
            eachStaticPolicy { name, policy ->
                val v = verifier(policy, staticProvider(rsaKey, cache = stale)).verify(signRsa()).asVerified()
                assertEquals(stale, v.cacheState, name)
            }
        }

    @Test
    fun `S9 the actor mapper surfaces stale metadata`() =
        runTest {
            val stale = CacheState(fromStaleCache = true, ageSeconds = 42)
            val r = JwksActorVerifier(actorConfig(), staticProvider(rsaKey, cache = stale), CLOCK).verify(actor("agent-1", signRsa()))
            assertEquals(VerificationStatus.VERIFIED, r.status, r.reason)
            assertEquals("true", r.metadata["verifiedFromCache"])
            assertEquals("42", r.metadata["cacheAgeSeconds"])
        }

    // -------------------------------------------------------------------------
    // S10 - actor mapper
    // -------------------------------------------------------------------------

    @Test
    fun `S10 a blank proof is ABSENT`() =
        runTest {
            val v = JwksActorVerifier(actorConfig(), staticProvider(rsaKey), CLOCK)
            assertEquals(VerificationStatus.ABSENT, v.verify(actor("agent-1", "")).status)
            assertEquals(VerificationStatus.ABSENT, v.verify(actor("agent-1", "   ")).status)
            assertEquals(VerificationStatus.ABSENT, v.verify(actor("agent-1", null)).status)
        }

    @Test
    fun `S10 an unavailable key source is UNAVAILABLE with failureKind network`() =
        runTest {
            val p = staticProvider(rsaKey)
            coEvery { p.getKeySet() } throws KeySetUnavailableException("keys too stale")
            val r = JwksActorVerifier(actorConfig(), p, CLOCK).verify(actor("agent-1", signRsa()))
            assertEquals(VerificationStatus.UNAVAILABLE, r.status)
            assertEquals("network", r.metadata["failureKind"])
            assertNotNull(r.reason)
        }

    @Test
    fun `S10 rejections carry the verdict failure kind on the wire`() =
        runTest {
            val v = JwksActorVerifier(actorConfig(), staticProvider(rsaKey, ecKey), CLOCK)

            val crypto = v.verify(actor("agent-1", corruptSignature(signRsa())))
            assertEquals(VerificationStatus.REJECTED, crypto.status)
            assertEquals("crypto", crypto.metadata["failureKind"])

            val claimsKind = v.verify(actor("agent-1", signRsa(claims(expiry = null))))
            assertEquals("claims", claimsKind.metadata["failureKind"])

            val policyKind = v.verify(actor("agent-1", signEc()))
            assertEquals("policy", policyKind.metadata["failureKind"])
        }

    @Test
    fun `S10 require_sub_match compares the token sub with the actor id and can be switched off`() =
        runTest {
            val token = signRsa(claims(subject = "agent-1"))

            val strict = JwksActorVerifier(actorConfig(requireSubMatch = true), staticProvider(rsaKey), CLOCK)
            assertEquals(VerificationStatus.VERIFIED, strict.verify(actor("agent-1", token)).status)
            val mismatch = strict.verify(actor("agent-2", token))
            assertEquals(VerificationStatus.REJECTED, mismatch.status)
            assertEquals("claims", mismatch.metadata["failureKind"])

            val relaxed = JwksActorVerifier(actorConfig(requireSubMatch = false), staticProvider(rsaKey), CLOCK)
            assertEquals(VerificationStatus.VERIFIED, relaxed.verify(actor("agent-2", token)).status)
        }

    @Test
    fun `S10 under DID binding the verified subject is the token sub and without it there is none`() =
        runTest {
            val didToken = signRsa(claims(subject = A_DID, issuer = A_DID, audience = null))
            val did =
                JwksActorVerifier(actorConfig(didAllowlist = listOf(A_DID)), didProvider(rsaKey), CLOCK)
                    .verify(actor(A_DID, didToken))
            assertEquals(VerificationStatus.VERIFIED, did.status, did.reason)
            assertEquals(A_DID, did.verifiedSubject)

            val plain = JwksActorVerifier(actorConfig(), staticProvider(rsaKey), CLOCK).verify(actor("agent-1", signRsa()))
            assertEquals(VerificationStatus.VERIFIED, plain.status)
            assertNull(plain.verifiedSubject)
        }

    // -------------------------------------------------------------------------
    // S11 - API mapper
    // -------------------------------------------------------------------------

    @Test
    fun `S11 verifyWithExpiry returns the exp instant and verify returns null for every non-Verified verdict`() =
        runTest {
            val expEpoch = NOW.plusSeconds(300).epochSecond
            val token = signRsa(claims(expiry = Instant.ofEpochSecond(expEpoch)))
            val api = JwksApiVerifier(apiConfig(), staticProvider(rsaKey), CLOCK)

            val verified = api.verifyWithExpiry(token)
            assertNotNull(verified)
            assertEquals(Instant.ofEpochSecond(expEpoch), verified!!.expiresAt)

            assertNull(api.verify(corruptSignature(token)), "crypto rejection")
            assertNull(api.verify(signRsa(claims(expiry = null))), "claims rejection")
            assertNull(api.verify(signRsa(claims(subject = null))), "requireSub rejection")
            assertNull(api.verify("not-a-jwt"), "unparseable")

            val unavailable = staticProvider(rsaKey)
            coEvery { unavailable.getKeySet() } throws KeySetUnavailableException("keys too stale")
            assertNull(JwksApiVerifier(apiConfig(), unavailable, CLOCK).verify(token), "REST stays fail-closed when keys are unavailable")
        }

    @Test
    fun `S11 a stale key set still resolves a principal for the API mapper`() =
        runTest {
            val stale = CacheState(fromStaleCache = true, ageSeconds = 600)
            val principal = JwksApiVerifier(apiConfig(), staticProvider(rsaKey, cache = stale), CLOCK).verify(signRsa())
            assertNotNull(principal)
        }

    // -------------------------------------------------------------------------
    // Probes
    // -------------------------------------------------------------------------

    @Test
    fun `Probe unparseable and alg-none tokens are Rejected as crypto`() =
        runTest {
            val encoder =
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
            val noneHeader = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
            val payload = encoder.encodeToString("""{"sub":"x","exp":9999999999}""".toByteArray())
            eachStaticPolicy { name, policy ->
                val v = verifier(policy, staticProvider(rsaKey))
                listOf("", "abc", "a.b.c", "$noneHeader.$payload.", "$noneHeader.$payload.AAAA").forEach { bad ->
                    val r = v.verify(bad).asRejected()
                    assertEquals(FailureKind.CRYPTO, r.failureKind, "$name: '$bad'")
                }
            }
        }

    @Test
    fun `Probe a non-numeric exp claim is Rejected as claims`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val malformed =
                    JWTClaimsSet
                        .Builder()
                        .subject("agent-1")
                        .issuer(ISS)
                        .audience(AUD)
                        .claim("exp", "not-a-number")
                        .build()
                val r = verifier(policy, staticProvider(rsaKey)).verify(signRsa(malformed)).asRejected()
                assertEquals(FailureKind.CLAIMS, r.failureKind, name)
            }
        }

    @Test
    fun `Probe wrong issuer and wrong audience are Rejected as claims under both presets`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val v = verifier(policy, staticProvider(rsaKey))
                assertEquals(FailureKind.CLAIMS, v.verify(signRsa(claims(issuer = "https://evil.example"))).asRejected().failureKind, name)
                assertEquals(FailureKind.CLAIMS, v.verify(signRsa(claims(audience = "other-aud"))).asRejected().failureKind, name)
            }
        }

    @Test
    fun `Probe an expired token is Rejected as claims`() =
        runTest {
            eachStaticPolicy { name, policy ->
                val expired = signRsa(claims(expiry = NOW.minusSeconds(3600)))
                val r = verifier(policy, staticProvider(rsaKey)).verify(expired).asRejected()
                assertEquals(FailureKind.CLAIMS, r.failureKind, name)
            }
        }
}
