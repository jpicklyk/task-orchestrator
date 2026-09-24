package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.config.JwksResult
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.test.runTest
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.security.Security
import java.util.UUID

/**
 * Independent test-author coverage for item 3a837a3b — "Fail closed on malformed scope claims
 * (JWKS and bearer tokens)". This file covers the JWKS side (`JwksApiVerifier.verify()` /
 * `verifyWithExpiry()`); the bearer side lives in `BearerScopeFailClosedTest.kt`.
 *
 * Oracle provenance: every expected value traces to the `test-plan` note frozen at queue phase
 * (S1-S18 and their listed probes) and the `diagnosis` note's decisions D1-D12, never to what
 * `JwksApiVerifier`/`ScopeClaimParser` currently return. `test-plan` marks every scenario here
 * EXISTING-SURFACE (`verify`/`verifyWithExpiry` are pre-existing public entry points; the plan
 * explicitly directs never to reference `ScopeClaimParser` by name), so red-proof for all of them
 * is a plain revert of the fix, not a narrowest-revert recipe.
 *
 * Per test-author skill §4, this class was authored from the declarations pasted into the dispatch
 * prompt and the existing sibling test fixtures under this package (JwksApiVerifierTest.kt,
 * JwksApiVerifierExpiryTest.kt, McpJwksRestAuthTest.kt) — no file under `src/main` was opened.
 */
class JwksScopeClaimFailClosedTest {
    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("rsa-scope-fail-closed-key").generate()
    }

    private val testIssuer = "https://idp.test.example"
    private val testAudience = "task-orchestrator-api"

    private val defaultConfig =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = testIssuer,
            audience = testAudience,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private val freshCacheState = CacheState(fromStaleCache = false, ageSeconds = null)

    private fun rsaMockCache(): JwksKeySetProvider {
        val provider = mockk<JwksKeySetProvider>()
        coEvery { provider.getKeySet() } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        coEvery { provider.getKeySetForIssuer(any()) } returns JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), freshCacheState)
        return provider
    }

    private fun buildClaims(
        subject: String = "api-caller-1",
        issuer: String = testIssuer,
        audience: String = testAudience,
        expiry: java.time.Instant =
            java.time.Instant
                .now()
                .plusSeconds(300),
        extraClaims: Map<String, Any> = emptyMap(),
    ): JWTClaimsSet {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject(subject)
                .issuer(issuer)
                .audience(audience)
                .expirationTime(java.util.Date.from(expiry))
        extraClaims.forEach { (k, v) -> builder.claim(k, v) }
        return builder.build()
    }

    private fun signRsa(claims: JWTClaimsSet): String {
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rsa-scope-fail-closed-key").build(), claims)
        jwt.sign(RSASSASigner(rsaKey))
        return jwt.serialize()
    }

    /** Captures WARN-level log records emitted by [JwksApiVerifier] during [block] (test-author skill §10). */
    private suspend fun captureWarnLogs(block: suspend () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger(JwksApiVerifier::class.java.name) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN
        try {
            block()
            return listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    // -------------------------------------------------------------------------
    // S1 -- happy: two root_ids, mixed-case tags kept verbatim
    // -------------------------------------------------------------------------

    @Test
    fun `S1 to_scope with mixed-case tags and two root_ids resolves verbatim`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val u2 = UUID.randomUUID().toString()
            val claims =
                buildClaims(
                    extraClaims =
                        mapOf(
                            "to_scope" to
                                mapOf(
                                    "root_ids" to listOf(u1, u2),
                                    "tags_include" to listOf("feature", "Bug"),
                                ),
                        ),
                )
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa(claims))
            assertNotNull(principal, "S1: well-formed to_scope must resolve to a principal")
            assertEquals(setOf(UUID.fromString(u1), UUID.fromString(u2)), principal!!.scope.rootIds)
            assertEquals(setOf("feature", "Bug"), principal.scope.tagsInclude, "S1: tags are kept verbatim, no case-fold (D10)")
        }

    // -------------------------------------------------------------------------
    // S3 -- happy: every absent/empty form of to_scope is unrestricted
    // -------------------------------------------------------------------------

    @Test
    fun `S3 absent or empty to_scope forms are all unrestricted`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())

            // S3a: to_scope claim entirely absent.
            val principalA = verifier.verify(signRsa(buildClaims()))
            assertNotNull(principalA, "S3a: no to_scope claim must still verify")
            assertNull(principalA!!.scope.rootIds, "S3a: absent to_scope must be unrestricted")
            assertTrue(principalA.scope.tagsInclude.isEmpty(), "S3a: absent to_scope must carry no tag constraint")

            // S3b: to_scope present but an empty map.
            val claimsB = buildClaims(extraClaims = mapOf("to_scope" to emptyMap<String, Any>()))
            val principalB = verifier.verify(signRsa(claimsB))
            assertNotNull(principalB, "S3b: to_scope: {} must still verify")
            assertNull(principalB!!.scope.rootIds, "S3b: to_scope: {} must be unrestricted (D5)")
            assertTrue(principalB.scope.tagsInclude.isEmpty(), "S3b: to_scope: {} must carry no tag constraint")

            // S3c: to_scope present with explicit null root_ids and tags_include.
            val claimsC =
                buildClaims(
                    extraClaims = mapOf("to_scope" to mapOf<String, Any?>("root_ids" to null, "tags_include" to null)),
                )
            val principalC = verifier.verify(signRsa(claimsC))
            assertNotNull(principalC, "S3c: explicit-null root_ids/tags_include must still verify")
            assertNull(principalC!!.scope.rootIds, "S3c: explicit null root_ids must be unrestricted (D4)")
            assertTrue(principalC.scope.tagsInclude.isEmpty(), "S3c: explicit null tags_include must carry no tag constraint (D4)")

            // S3d: to_scope present with only an empty tags_include list (root_ids absent).
            val claimsD = buildClaims(extraClaims = mapOf("to_scope" to mapOf("tags_include" to emptyList<String>())))
            val principalD = verifier.verify(signRsa(claimsD))
            assertNotNull(principalD, "S3d: to_scope with only tags_include: [] must still verify")
            assertNull(principalD!!.scope.rootIds, "S3d: root_ids absent must be unrestricted")
            assertTrue(principalD.scope.tagsInclude.isEmpty(), "S3d: tags_include: [] is the canonical no-constraint form (D3)")
        }

    // -------------------------------------------------------------------------
    // S4 -- happy: an unrelated top-level claim does not interfere
    // -------------------------------------------------------------------------

    @Test
    fun `S4 unknown top-level claim does not affect a valid to_scope`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims =
                buildClaims(
                    extraClaims =
                        mapOf(
                            "to_scope" to mapOf("root_ids" to listOf(u1), "tags_include" to emptyList<String>()),
                            "some_other_claim" to "whatever",
                        ),
                )
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa(claims))
            assertNotNull(principal, "S4: unrelated top-level claims must not interfere with a valid to_scope")
            assertEquals(setOf(UUID.fromString(u1)), principal!!.scope.rootIds, "S4: root_ids must still resolve correctly")
        }

    // -------------------------------------------------------------------------
    // S5 -- failure: malformed root_ids shapes reject the whole JWT
    // -------------------------------------------------------------------------

    @Test
    fun `S5 malformed root_ids values are rejected`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val malformedRootIds: List<Pair<String, Any>> =
                listOf(
                    "scalar string" to u1,
                    "non-uuid element" to listOf("not-a-uuid"),
                    "mixed valid/invalid, not narrowed per D2" to listOf(u1, "not-a-uuid"),
                    "empty list" to emptyList<String>(),
                    "non-string element" to listOf(123),
                    "null element" to listOf(u1, null),
                )
            for ((label, rootIds) in malformedRootIds) {
                val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to rootIds)))
                val jwt = signRsa(claims)
                assertNull(verifier.verify(jwt), "S5[$label]: malformed root_ids must reject the whole JWT (verify)")
                assertNull(
                    verifier.verifyWithExpiry(jwt),
                    "S5[$label]: malformed root_ids must reject the whole JWT (verifyWithExpiry)",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S6 -- failure: malformed tags_include shapes reject the whole JWT
    // -------------------------------------------------------------------------

    @Test
    fun `S6 malformed tags_include values are rejected`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val malformedTagsInclude: List<Pair<String, Any>> =
                listOf(
                    "scalar string" to "feature",
                    "non-string element" to listOf("feature", 7),
                    "blank element" to listOf("  "),
                )
            for ((label, tagsInclude) in malformedTagsInclude) {
                val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("tags_include" to tagsInclude)))
                val jwt = signRsa(claims)
                assertNull(verifier.verify(jwt), "S6[$label]: malformed tags_include must reject the whole JWT (verify)")
                assertNull(
                    verifier.verifyWithExpiry(jwt),
                    "S6[$label]: malformed tags_include must reject the whole JWT (verifyWithExpiry)",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S7 -- failure: malformed to_scope shape (not a map, or an unknown key)
    // -------------------------------------------------------------------------

    @Test
    fun `S7 malformed to_scope shape is rejected`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val u2 = UUID.randomUUID().toString()
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val malformedScopes: List<Pair<String, Any>> =
                listOf(
                    "scalar string" to "all",
                    "list instead of map" to listOf(u1),
                    "unknown key alongside a valid key" to mapOf("root_ids" to listOf(u1), "root_id" to listOf(u2)),
                )
            for ((label, scopeValue) in malformedScopes) {
                val claims = buildClaims(extraClaims = mapOf("to_scope" to scopeValue))
                val jwt = signRsa(claims)
                assertNull(verifier.verify(jwt), "S7[$label]: malformed to_scope must reject the whole JWT (verify)")
                assertNull(
                    verifier.verifyWithExpiry(jwt),
                    "S7[$label]: malformed to_scope must reject the whole JWT (verifyWithExpiry)",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S8 -- failure: a malformed to_scope logs a WARN naming the claim path, never the JWT
    // -------------------------------------------------------------------------

    @Test
    fun `S8 malformed to_scope logs a WARN naming the claim path, never the JWT`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val u1 = UUID.randomUUID().toString()
            val malformedToScopeCases: List<Pair<String, Any>> =
                listOf(
                    "S5-style scalar root_ids" to mapOf("root_ids" to u1),
                    "S6-style scalar tags_include" to mapOf("tags_include" to "feature"),
                    "S7-style to_scope not a map" to "all",
                )
            for ((label, scopeValue) in malformedToScopeCases) {
                val claims = buildClaims(extraClaims = mapOf("to_scope" to scopeValue))
                val jwt = signRsa(claims)
                var result: ApiPrincipal? = null
                val warnMessages = captureWarnLogs { result = verifier.verify(jwt) }
                assertNull(result, "S8[$label]: sanity -- malformed to_scope must still reject the JWT")
                assertTrue(warnMessages.isNotEmpty(), "S8[$label]: must log at least one WARN")
                assertTrue(
                    warnMessages.any { it.contains("to_scope") },
                    "S8[$label]: WARN must name to_scope. Got: $warnMessages",
                )
                assertTrue(
                    warnMessages.none { it.contains(jwt) },
                    "S8[$label]: WARN must never include the raw JWT",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S9 -- failure: malformed to_scope 401s a real REST route with invalid_token
    // (harness pattern copied from McpJwksRestAuthTest.kt, per that file's own comment
    // that S9 fits its existing production wiring verbatim)
    // -------------------------------------------------------------------------

    private fun emptyMcpServer(): Server =
        Server(
            serverInfo = Implementation(name = "jwks-scope-fail-closed-test", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
        )

    private fun inMemoryRepositoryProvider(): DefaultRepositoryProvider =
        DefaultRepositoryProvider(
            DatabaseManager(
                Database.connect(
                    "jdbc:h2:mem:jwksscopefailclosed_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                ),
            ).also { DirectDatabaseSchemaManager().updateSchema() },
        )

    private fun io.ktor.server.application.Application.installJwksScopeTestApp(verifier: JwksApiVerifier) {
        installMcpStreamableHttp(emptyMcpServer())
        installRestApiRoutes(
            apiConfig = defaultConfig,
            eventBus = null,
            effectiveProvider = inMemoryRepositoryProvider(),
            apiTokenEntries = emptyMap(),
            allowQueryToken = false,
            serverName = "jwks-scope-fail-closed-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            noteSchemaService = NoOpNoteSchemaService,
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            idempotencyCache = IdempotencyCache(),
            jwksVerifier = verifier,
        )
    }

    @Test
    fun `S9 malformed to_scope 401s on a real REST route with invalid_token`() =
        testApplication {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            application { installJwksScopeTestApp(verifier) }

            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to u1)))
            val response =
                client.get("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer ${signRsa(claims)}")
                }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "S9: a malformed to_scope must 401 the whole request (api-rest.md: JWT rejected -> 401 invalid_token)",
            )
            val wwwAuth = response.headers[HttpHeaders.WWWAuthenticate]
            assertNotNull(wwwAuth, "S9: a 401 in jwks mode must carry WWW-Authenticate")
            assertTrue(wwwAuth!!.contains("invalid_token"), "S9: WWW-Authenticate must name invalid_token. Got: $wwwAuth")
        }

    // -------------------------------------------------------------------------
    // S16 -- edge: malformed to_capabilities widens to READ-only, with a WARN (D9)
    // -------------------------------------------------------------------------

    @Test
    fun `S16 malformed to_capabilities widens to READ-only with a WARN naming the claim`() =
        runTest {
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val malformedCapabilities: List<Pair<String, Any>> =
                listOf(
                    "unknown value alongside a known one" to listOf("read", "superuser"),
                    "all-unknown list" to listOf("superuser"),
                    "scalar string instead of a list" to "admin",
                )
            for ((label, capabilitiesValue) in malformedCapabilities) {
                val claims = buildClaims(extraClaims = mapOf("to_capabilities" to capabilitiesValue))
                val jwt = signRsa(claims)
                var principal: ApiPrincipal? = null
                val warnMessages = captureWarnLogs { principal = verifier.verify(jwt) }
                assertNotNull(principal, "S16[$label]: malformed to_capabilities must not reject the whole JWT (D9)")
                assertEquals(setOf(ApiCapability.READ), principal!!.capabilities, "S16[$label]: must default to READ-only")
                assertTrue(
                    warnMessages.any { it.contains("to_capabilities") },
                    "S16[$label]: must log a WARN naming to_capabilities. Got: $warnMessages",
                )
            }
        }

    // -------------------------------------------------------------------------
    // S17 -- edge: duplicate root_ids collapse to a single entry
    // -------------------------------------------------------------------------

    @Test
    fun `S17 duplicate root_ids collapse to a single entry (JWKS)`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to listOf(u1, u1))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa(claims))
            assertNotNull(principal)
            assertEquals(setOf(UUID.fromString(u1)), principal!!.scope.rootIds, "S17: duplicate root_ids must collapse into one entry")
        }

    // -------------------------------------------------------------------------
    // S18 -- edge: uppercase UUID text parses to the same UUID (RFC 4122 §3)
    // -------------------------------------------------------------------------

    @Test
    fun `S18 uppercase UUID text is accepted as the same root id (JWKS)`() =
        runTest {
            val u1 = UUID.randomUUID()
            val upper = u1.toString().uppercase()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to listOf(upper))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            val principal = verifier.verify(signRsa(claims))
            assertNotNull(principal)
            assertEquals(setOf(u1), principal!!.scope.rootIds, "S18: an uppercase UUID string must parse to the same UUID")
        }

    // -------------------------------------------------------------------------
    // Adversarial probes (test-author skill §6)
    // -------------------------------------------------------------------------

    @Test
    fun `Probe -- stringified-set root_ids scalar is rejected`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to "{$u1}")))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            assertNull(
                verifier.verify(signRsa(claims)),
                "Probe: a stringified set literal is a scalar, not a list -- malformed per D2",
            )
        }

    @Test
    fun `Probe -- root_ids element with leading whitespace is rejected`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to listOf(" $u1"))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            assertNull(
                verifier.verify(signRsa(claims)),
                "Probe: a UUID string padded with whitespace must not parse -- UUID.fromString rejects it",
            )
        }

    @Test
    fun `Probe -- wrong-case key name ROOT_IDS is an unknown key, not root_ids`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("ROOT_IDS" to listOf(u1))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            assertNull(
                verifier.verify(signRsa(claims)),
                "Probe: scope keys are case-sensitive; ROOT_IDS is unknown per D5, not a widened root_ids",
            )
        }

    @Test
    fun `Probe -- nested list root_ids is rejected`() =
        runTest {
            val u1 = UUID.randomUUID().toString()
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("root_ids" to listOf(listOf(u1)))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            assertNull(verifier.verify(signRsa(claims)), "Probe: a nested list element is not a String -- malformed per D2")
        }

    @Test
    fun `Probe -- tags_include as a map instead of a list is rejected`() =
        runTest {
            val claims = buildClaims(extraClaims = mapOf("to_scope" to mapOf("tags_include" to mapOf("a" to "b"))))
            val verifier = JwksApiVerifier(defaultConfig, rsaMockCache())
            assertNull(verifier.verify(signRsa(claims)), "Probe: tags_include must be a list -- a map is malformed per D3")
        }
}
