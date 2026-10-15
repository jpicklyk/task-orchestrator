package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.Ed25519Signer
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.OctetKeyPairGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Modifier
import java.security.Security
import java.time.Instant
import java.util.Date

/**
 * Independent test-author coverage for item 3a96e4f5 -- the identity-pipeline removal that follows
 * 779a9d0d. The API JWKS path (`JwksApiVerifier.verifyWithExpiry`) must behave exactly as the
 * documented REST contract says, and the removed surface must be gone.
 *
 * Oracles (never the implementation): `current/docs/fleet-deployment.md` (`API_JWKS_ALGORITHMS`
 * accepts `RS256,EdDSA`; the API JWT policy has no `jti` replay protection and no DID binding),
 * `current/docs/api-rest.md` section 1 (principal id is `sub`; `to_kind` defaults to `service`,
 * except `sub == iss` starting `did:` which is `agent`), RFC 7515 4.1.4 (`kid` selects the
 * verification key), and the `JwksKeySetProvider.refreshForUnknownKid` KDoc (one forced refresh per
 * unknown kid, null when declined or failed, never throws for a fetch failure).
 *
 * Scenario labels from the test-plan: S1-S4 and S7 are EXISTING-SURFACE; S5 and S6 are NEW-SURFACE.
 */
class ApiJwtPathAfterDidRemovalTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey: RSAKey = RSAKeyGenerator(2048).keyID("rsa-removal-key").generate()
        private val edKey = OctetKeyPairGenerator(Curve.Ed25519).keyID("ed-removal-key").generate()

        private const val BASE = "io.github.jpicklyk.mcptask.current"
    }

    private val httpIssuer = "https://idp.test.example"
    private val audience = "task-orchestrator-api"
    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    private fun config(
        issuer: String = httpIssuer,
        algorithms: List<String> = listOf("RS256"),
    ): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = issuer,
            audience = audience,
            algorithms = algorithms,
            cacheTtlSeconds = 300,
        )

    private fun providerWith(keys: List<JWK>): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(keys), freshCacheState)
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return provider
    }

    private fun claims(
        subject: String = "api-caller-1",
        issuer: String = httpIssuer,
        jti: String? = null,
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
        if (jti != null) builder.jwtID(jti)
        return builder.build()
    }

    private fun signRsa(
        claims: JWTClaimsSet,
        headerKid: String = rsaKey.keyID,
    ): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(headerKid).build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    private fun signEd(claims: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.EdDSA).keyID(edKey.keyID).build(), claims)
        jwt.sign(Ed25519Signer(edKey))
        return jwt.serialize()
    }

    private fun verified(verdict: ApiTokenVerdict): VerifiedApiToken {
        assertTrue(verdict is ApiTokenVerdict.Verified, "expected Verified but was $verdict")
        return (verdict as ApiTokenVerdict.Verified).token
    }

    // -------------------------------------------------------------------------
    // S1 -- Ed25519 (OKP) key verifies on the API path (keeps the EdDSA support documented for
    // API_JWKS_ALGORITHMS=RS256,EdDSA)
    // -------------------------------------------------------------------------

    @Test
    fun `S1 EdDSA token with matching kid is verified and principal id is sub`() =
        runTest {
            val verifier = JwksApiVerifier(config(algorithms = listOf("EdDSA")), providerWith(listOf(edKey.toPublicJWK())))
            val token = verified(verifier.verifyWithExpiry(signEd(claims(subject = "ed-caller"))))
            assertEquals("ed-caller", token.principal.id)
        }

    // -------------------------------------------------------------------------
    // S2 -- no jti replay protection: the same token with a jti verifies repeatedly
    // -------------------------------------------------------------------------

    @Test
    fun `S2 the same RS256 token carrying a jti is verified twice`() =
        runTest {
            val verifier = JwksApiVerifier(config(), providerWith(listOf(rsaKey.toPublicJWK())))
            val jwt = signRsa(claims(jti = "replayed-jti-1"))
            val first = verified(verifier.verifyWithExpiry(jwt))
            val second = verified(verifier.verifyWithExpiry(jwt))
            assertEquals("api-caller-1", first.principal.id)
            assertEquals("api-caller-1", second.principal.id)
        }

    @Test
    fun `S2b a token with no jti claim at all is verified (absent jti is not an error)`() =
        runTest {
            val verifier = JwksApiVerifier(config(), providerWith(listOf(rsaKey.toPublicJWK())))
            val token = verified(verifier.verifyWithExpiry(signRsa(claims(jti = null))))
            assertEquals("api-caller-1", token.principal.id)
        }

    // -------------------------------------------------------------------------
    // S3 / S4 -- no DID binding, but the live DID-prefix kind default stays
    // -------------------------------------------------------------------------

    @Test
    fun `S3 did web issuer with a different did web sub is verified as kind service`() =
        runTest {
            val didIssuer = "did:web:idp.example"
            val verifier = JwksApiVerifier(config(issuer = didIssuer), providerWith(listOf(rsaKey.toPublicJWK())))
            val token = verified(verifier.verifyWithExpiry(signRsa(claims(subject = "did:web:other.example", issuer = didIssuer))))
            assertEquals("did:web:other.example", token.principal.id)
            assertEquals(PrincipalKind.SERVICE, token.principal.kind)
        }

    @Test
    fun `S4 sub equal to a did issuer defaults to kind agent`() =
        runTest {
            val didIssuer = "did:web:agent.example"
            val verifier = JwksApiVerifier(config(issuer = didIssuer), providerWith(listOf(rsaKey.toPublicJWK())))
            val token = verified(verifier.verifyWithExpiry(signRsa(claims(subject = didIssuer, issuer = didIssuer))))
            assertEquals(didIssuer, token.principal.id)
            assertEquals(PrincipalKind.AGENT, token.principal.kind)
        }

    // -------------------------------------------------------------------------
    // S5 / S6 -- unknown kid: no sole-key fallback; one forced refresh decides
    // -------------------------------------------------------------------------

    /**
     * The token is signed with [rsaKey] but names kid "new-kid"; the cached set holds the SAME key
     * material relabelled "old-kid". A sole-key (loose kid) fallback would therefore verify the
     * signature and accept; only the kid-must-match rule rejects it.
     */
    private val relabelledOldKey: RSAKey = RSAKey.Builder(rsaKey.toPublicJWK()).keyID("old-kid").build()

    @Test
    fun `S5 unknown kid with a declined refresh is rejected after exactly one refresh for that kid`() =
        runTest {
            val provider = providerWith(listOf(relabelledOldKey))
            coEvery { provider.refreshForUnknownKid(any()) } returns null
            val verifier = JwksApiVerifier(config(), provider)

            val verdict = verifier.verifyWithExpiry(signRsa(claims(), headerKid = "new-kid"))

            assertEquals(ApiTokenVerdict.Rejected, verdict)
            coVerify(exactly = 1) { provider.refreshForUnknownKid("new-kid") }
            coVerify(exactly = 1) { provider.refreshForUnknownKid(any()) }
        }

    @Test
    fun `S5b a refresh that throws still yields Rejected, never Unavailable`() =
        runTest {
            val provider = providerWith(listOf(relabelledOldKey))
            coEvery { provider.refreshForUnknownKid(any()) } throws RuntimeException("simulated refresh failure")
            val verifier = JwksApiVerifier(config(), provider)

            val verdict = verifier.verifyWithExpiry(signRsa(claims(), headerKid = "new-kid"))

            assertEquals(ApiTokenVerdict.Rejected, verdict)
        }

    @Test
    fun `S6 unknown kid whose refresh returns a set holding the kid is verified`() =
        runTest {
            val provider = providerWith(listOf(relabelledOldKey))
            val rotatedIn = RSAKey.Builder(rsaKey.toPublicJWK()).keyID("new-kid").build()
            coEvery { provider.refreshForUnknownKid("new-kid") } returns JwksResult(JWKSet(listOf(rotatedIn)), freshCacheState)
            val verifier = JwksApiVerifier(config(), provider)

            val token = verified(verifier.verifyWithExpiry(signRsa(claims(subject = "rotated-caller"), headerKid = "new-kid")))

            assertEquals("rotated-caller", token.principal.id)
            coVerify(exactly = 1) { provider.refreshForUnknownKid("new-kid") }
        }

    // -------------------------------------------------------------------------
    // S7 -- the removed surface is gone (reflection by string name, so it compiles on both trees)
    // -------------------------------------------------------------------------

    @Test
    fun `S7 removed classes are absent from the classpath`() {
        val removed =
            listOf(
                "$BASE.infrastructure.identity.did.DidWebResolver",
                "$BASE.infrastructure.identity.did.DidResolverRegistry",
                "$BASE.infrastructure.identity.did.DidDocumentJwksExtractor",
                "$BASE.infrastructure.identity.jwt.JtiReplayCache",
                "$BASE.infrastructure.identity.jwt.IssuerNotTrustedException",
                "$BASE.application.service.ActorVerifier",
                "$BASE.application.service.NoOpActorVerifier",
            )
        removed.forEach { name ->
            assertThrows<ClassNotFoundException>("$name must no longer exist") { Class.forName(name) }
        }
    }

    @Test
    fun `S7 JwtPolicy carries exactly the five API fields`() {
        val fields =
            Class
                .forName("$BASE.infrastructure.identity.jwt.JwtPolicy")
                .declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
                .toSet()
        assertEquals(setOf("algorithms", "issuer", "audience", "maxTokenLifetimeSeconds", "requireSub"), fields)
    }

    @Test
    fun `S7 KeySourceConfig has no did fields`() {
        val fields =
            Class
                .forName("$BASE.infrastructure.identity.jwt.KeySourceConfig")
                .declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .map { it.name }
        val didFields = fields.filter { it.startsWith("did") }
        assertTrue(didFields.isEmpty(), "KeySourceConfig must not declare did* fields but has $didFields")
        assertTrue(fields.isNotEmpty(), "sanity: KeySourceConfig fields were enumerated")
    }

    @Test
    fun `S7 JwksKeySetProvider has no getKeySetForIssuer`() {
        val methods = Class.forName("$BASE.infrastructure.identity.jwt.JwksKeySetProvider").methods.map { it.name }
        assertFalse("getKeySetForIssuer" in methods, "getKeySetForIssuer must be removed")
        assertTrue("getKeySet" in methods, "sanity: getKeySet is still declared")
    }

    @Test
    fun `S7 ToolExecutionContext has no actorVerifier`() {
        val cls = Class.forName("$BASE.application.api.ToolExecutionContext")
        val methods = cls.methods.map { it.name } + cls.declaredMethods.map { it.name }
        assertFalse("actorVerifier" in methods || "getActorVerifier" in methods, "actorVerifier must be removed")
        assertFalse(cls.declaredFields.any { it.name == "actorVerifier" }, "no actorVerifier field")
        assertTrue("getRepositoryProvider" in methods, "sanity: the declared repositoryProvider getter was enumerated")
    }
}
