package io.github.jpicklyk.mcptask.current.infrastructure.identity

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.did.DidWebResolver
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.DefaultJwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksActorVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.toKeySource
import io.github.jpicklyk.mcptask.current.test.publicEgressGuard
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test-author coverage for item 385444c2 (R8), egress half (AR-05 rec 4): [EgressGuard] refuses
 * non-public destinations, and every JWKS / OIDC / did:web fetch passes through it before any
 * request is made.
 *
 * Oracles: O-RFC = the address classes of RFC 1122 (127/8, 0.0.0.0), RFC 4291 (::1, ::, fe80::/10),
 * RFC 3927 (169.254/16), RFC 1918 (10/8, 172.16/12, 192.168/16), RFC 6598 (100.64/10) and RFC 4193
 * (fc00::/7); O-SCOPE = task-scope D5 ("a host is refused when ANY resolved address is refused",
 * "with allowLoopback only the loopback class is admitted", "an IPv4-mapped IPv6 address is
 * classified by its IPv4 part", "a literal IP needs no DNS"); O-PLAN = plan 4.1 R8.
 *
 * Every resolver below is injected, so no test performs a DNS lookup. Tests that use the default
 * resolver pass only numeric or `localhost` hosts, which the JDK resolves without the network.
 */
class EgressGuardTest {
    companion object {
        private val rsaKey = RSAKeyGenerator(2048).keyID("eg-key").generate()
        private val jwksJson = JWKSet(listOf(rsaKey.toPublicJWK())).toString()
    }

    private fun addr(literal: String): InetAddress = InetAddress.getByName(literal) // numeric literal: no DNS

    /** A guard whose resolver answers every name with [addresses]. */
    private fun guardResolvingTo(
        vararg addresses: String,
        allowLoopback: Boolean = false,
    ) = EgressGuard(allowLoopback) { addresses.map { addr(it) } }

    private val noDns: (String) -> List<InetAddress> = { host ->
        fail(
            "a literal IP must not be resolved, but the resolver was asked for $host"
        )
    }

    private fun refusals(
        guard: EgressGuard,
        url: String,
    ): EgressRefusedException? =
        try {
            guard.check(url)
            null
        } catch (e: EgressRefusedException) {
            e
        }

    private fun assertRefused(
        guard: EgressGuard,
        url: String,
        message: String = url,
    ): EgressRefusedException = refusals(guard, url) ?: fail("expected $message to be refused")

    private fun assertAllowed(
        guard: EgressGuard,
        url: String,
        message: String = url,
    ) {
        val e = refusals(guard, url)
        assertNull(e, "expected $message to be allowed but it was refused: ${e?.reason}")
    }

    private val refusedAddresses =
        listOf(
            "127.0.0.1",
            "127.255.255.255",
            "::1",
            "169.254.1.1",
            "169.254.255.255",
            "fe80::1",
            "febf::1",
            "10.0.0.1",
            "10.255.255.255",
            "172.16.0.1",
            "172.31.255.255",
            "192.168.1.1",
            "192.168.255.255",
            "100.64.0.1",
            "100.64.0.0",
            "100.127.255.255",
            "fc00::1",
            "fd12:3456:789a::1",
            "0.0.0.0",
            "::",
            "::ffff:10.0.0.1",
            "::ffff:127.0.0.1",
        )

    private val allowedAddresses =
        listOf(
            "93.184.216.34",
            "8.8.8.8",
            "9.255.255.255",
            "11.0.0.1",
            "126.255.255.255",
            "128.0.0.1",
            "169.253.255.255",
            "169.255.0.1",
            "172.15.255.255",
            "172.32.0.1",
            "192.167.255.255",
            "192.169.0.1",
            "100.63.255.255",
            "100.128.0.1",
            "2606:2800:220:1::1",
            "fbff::1",
            "::ffff:93.184.216.34",
        )

    // -------------------------------------------------------------------------
    // S19 / S20 - address classification through a resolved name
    // -------------------------------------------------------------------------

    @Test
    fun `S19 every non-public address class is refused when a name resolves to it`() {
        refusedAddresses.forEach { a ->
            val e = assertRefused(guardResolvingTo(a), "https://idp.eg.example/jwks.json", "a name resolving to $a")
            assertEquals("idp.eg.example", e.host, "refusal for $a names the host")
            assertNotNull(e.address, "refusal for $a names the refused address")
        }
    }

    @Test
    fun `S20 public addresses and the neighbours of every refused range are allowed`() {
        allowedAddresses.forEach { a ->
            assertAllowed(guardResolvingTo(a), "https://idp.eg.example/jwks.json", "a name resolving to $a")
        }
    }

    // -------------------------------------------------------------------------
    // S19 / S20 - literal IPs in the URL (no DNS)
    // -------------------------------------------------------------------------

    @Test
    fun `S19 literal non-public IPs in the URL are refused without any DNS lookup`() {
        val guard = EgressGuard(false, noDns)
        listOf(
            "https://127.0.0.1/jwks.json",
            "https://127.0.0.1:8443/jwks.json",
            "https://10.0.0.5/jwks.json",
            "https://172.16.0.1/x",
            "https://172.31.255.255/x",
            "https://192.168.0.10/x",
            "https://169.254.169.254/latest/meta-data/",
            "https://100.64.0.1/x",
            "https://0.0.0.0/x",
            "https://[::1]/jwks.json",
            "https://[::1]:8443/jwks.json",
            "https://[fe80::1]/x",
            "https://[fc00::1]/x",
            "https://[fd00::1]/x",
            "https://[::]/x",
            "https://[::ffff:10.0.0.1]/x",
            "http://127.0.0.1/x",
        ).forEach { url -> assertRefused(guard, url) }
    }

    @Test
    fun `S20 literal public IPs in the URL are allowed without any DNS lookup`() {
        val guard = EgressGuard(false, noDns)
        listOf(
            "https://93.184.216.34/jwks.json",
            "https://172.32.0.1/x",
            "https://100.128.0.1/x",
            "https://[2606:2800:220:1::1]/x",
        ).forEach { url -> assertAllowed(guard, url) }
    }

    // -------------------------------------------------------------------------
    // S21 - allowLoopback admits only the loopback class
    // -------------------------------------------------------------------------

    @Test
    fun `S21 allowLoopback admits loopback addresses and nothing else`() {
        listOf("127.0.0.1", "127.5.5.5", "::1", "::ffff:127.0.0.1").forEach { a ->
            assertAllowed(guardResolvingTo(a, allowLoopback = true), "https://dev.eg.example/x", "loopback $a with allowLoopback")
        }
        listOf(
            "10.0.0.1",
            "172.16.0.1",
            "192.168.1.1",
            "169.254.1.1",
            "100.64.0.1",
            "fc00::1",
            "fe80::1",
            "0.0.0.0",
            "::",
            "::ffff:10.0.0.1"
        ).forEach { a ->
            assertRefused(guardResolvingTo(a, allowLoopback = true), "https://dev.eg.example/x", "$a with allowLoopback")
        }
    }

    @Test
    fun `S21 literal loopback URLs follow allowLoopback`() {
        val closed = EgressGuard(false, noDns)
        val open = EgressGuard(true, noDns)
        listOf("http://127.0.0.1:8080/jwks.json", "https://[::1]/jwks.json").forEach { url ->
            assertRefused(closed, url)
            assertAllowed(open, url)
        }
        assertRefused(open, "https://10.0.0.5/jwks.json")
    }

    @Test
    fun `S21 forConfig maps allow_insecure_url onto allowLoopback`() {
        assertTrue(EgressGuard.forConfig(true).allowLoopback)
        assertTrue(!EgressGuard.forConfig(false).allowLoopback)
    }

    // -------------------------------------------------------------------------
    // S22 - fail closed on any bad answer
    // -------------------------------------------------------------------------

    @Test
    fun `S22 a host with one public and one private address is refused in either order`() {
        val publicFirst = assertRefused(guardResolvingTo("93.184.216.34", "10.0.0.1"), "https://multi.eg.example/x")
        assertEquals("multi.eg.example", publicFirst.host)
        assertEquals("10.0.0.1", publicFirst.address)

        val privateFirst = assertRefused(guardResolvingTo("10.0.0.1", "93.184.216.34"), "https://multi.eg.example/x")
        assertEquals("10.0.0.1", privateFirst.address)

        assertRefused(guardResolvingTo("93.184.216.34", "::1"), "https://multi.eg.example/x")
        assertRefused(guardResolvingTo("2606:2800:220:1::1", "169.254.169.254"), "https://multi.eg.example/x")
    }

    @Test
    fun `S22 with allowLoopback a public plus loopback answer is allowed but public plus private is not`() {
        assertAllowed(guardResolvingTo("93.184.216.34", "127.0.0.1", allowLoopback = true), "https://multi.eg.example/x")
        assertRefused(guardResolvingTo("127.0.0.1", "10.0.0.1", allowLoopback = true), "https://multi.eg.example/x")
    }

    // -------------------------------------------------------------------------
    // Probes - host spelling tricks
    // -------------------------------------------------------------------------

    @Test
    fun `Probe the host is taken after the userinfo so user at host cannot smuggle a private destination`() {
        val asked = mutableListOf<String>()
        val guard =
            EgressGuard(false) { host ->
                asked.add(host)
                listOf(addr("93.184.216.34"))
            }

        // The real destination is the part after '@'.
        assertRefused(EgressGuard(false, noDns), "https://public.eg.example@127.0.0.1/jwks.json")
        assertRefused(EgressGuard(false, noDns), "https://public.eg.example:443@10.0.0.5/jwks.json")

        // Private-looking userinfo in front of a public host is still a public destination, and the
        // resolver must be asked about the real host, not the userinfo.
        assertAllowed(guard, "https://127.0.0.1@public.eg.example/jwks.json")
        assertEquals(listOf("public.eg.example"), asked)
    }

    @Test
    fun `Probe numeric spellings of loopback are refused by the default resolver`() {
        val guard = EgressGuard(false)
        listOf(
            "http://2130706433/jwks.json", // 127.0.0.1 as one 32-bit number
            "http://127.1/jwks.json", // shorthand 127.0.0.1
            "http://localhost/jwks.json",
            "http://[::ffff:7f00:1]/jwks.json", // mapped 127.0.0.1 in hex form
        ).forEach { url -> assertRefused(guard, url) }
    }

    @Test
    fun `Probe localhost is admitted only under allowLoopback`() {
        assertRefused(EgressGuard(false), "https://localhost:8443/jwks.json")
        assertAllowed(EgressGuard(true), "https://localhost:8443/jwks.json")
    }

    @Test
    fun `Probe an uppercase scheme and host do not change the verdict`() {
        assertRefused(EgressGuard(false, noDns), "HTTPS://10.0.0.5/JWKS.JSON")
        assertRefused(guardResolvingTo("10.0.0.1"), "HTTPS://IDP.EG.EXAMPLE/JWKS.JSON")
    }

    // -------------------------------------------------------------------------
    // S23 - every outbound key fetch passes the guard before a request is made
    // -------------------------------------------------------------------------

    private class Requests {
        val urls = mutableListOf<String>()
        val count = AtomicInteger(0)
    }

    private fun recordingEngine(
        requests: Requests,
        responder: (String) -> String,
    ) = MockEngine { request ->
        requests.count.incrementAndGet()
        requests.urls.add(request.url.toString())
        respond(responder(request.url.toString()), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
    }

    private fun causedByEgressRefusal(t: Throwable?): Boolean = generateSequence(t) { it.cause }.any { it is EgressRefusedException }

    private suspend fun thrownBy(block: suspend () -> Unit): Throwable? = runCatching { block() }.exceptionOrNull()

    @Test
    fun `S23 a jwks_uri on a private literal address is refused before any request`() =
        runTest {
            val requests = Requests()
            val config = VerifierConfig.Jwks(jwksUri = "https://10.0.0.5/jwks.json")
            val ex =
                thrownBy {
                    val provider =
                        DefaultJwksKeySetProvider(
                            config.toKeySource(),
                            Path.of("."),
                            httpClientEngineForTest = recordingEngine(requests) { jwksJson }
                        )
                    try {
                        provider.getKeySet()
                    } finally {
                        provider.close()
                    }
                }

            assertNotNull(ex, "a key source on 10/8 must not be fetched")
            assertTrue(causedByEgressRefusal(ex), "the failure must come from the egress guard, was $ex")
            assertEquals(0, requests.count.get(), "no request may be made to a refused destination")
        }

    @Test
    fun `S23 a jwks_uri that merely resolves to a private address is refused before any request`() =
        runTest {
            val requests = Requests()
            val config = VerifierConfig.Jwks(jwksUri = "https://internal.eg.example/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config.toKeySource(),
                    Path.of("."),
                    egressGuard = guardResolvingTo("10.0.0.9"),
                    httpClientEngineForTest = recordingEngine(requests) { jwksJson },
                )
            try {
                val ex = thrownBy { provider.getKeySet() }
                assertTrue(causedByEgressRefusal(ex), "the failure must come from the egress guard, was $ex")
                assertEquals(0, requests.count.get())
            } finally {
                provider.close()
            }
        }

    @Test
    fun `S23 a public jwks_uri is fetched exactly once`() =
        runTest {
            val requests = Requests()
            val config = VerifierConfig.Jwks(jwksUri = "https://keys.eg.example/jwks.json")
            val provider =
                DefaultJwksKeySetProvider(
                    config.toKeySource(),
                    Path.of("."),
                    egressGuard = publicEgressGuard(),
                    httpClientEngineForTest = recordingEngine(requests) { jwksJson },
                )
            try {
                assertEquals(
                    1,
                    provider
                        .getKeySet()
                        .keys.keys.size
                )
                assertEquals(listOf("https://keys.eg.example/jwks.json"), requests.urls)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `S23 an OIDC-discovered jwks_uri on a private address is guarded too`() =
        runTest {
            val requests = Requests()
            val discovery = "https://idp.eg.example/.well-known/openid-configuration"
            val engine =
                recordingEngine(requests) { url ->
                    if (url == discovery) {
                        """{"issuer":"https://idp.eg.example","jwks_uri":"https://10.0.0.5/keys"}"""
                    } else {
                        jwksJson
                    }
                }
            val config = VerifierConfig.Jwks(oidcDiscovery = discovery)
            val provider =
                DefaultJwksKeySetProvider(
                    config.toKeySource(),
                    Path.of("."),
                    egressGuard = publicEgressGuard(),
                    httpClientEngineForTest = engine,
                )
            try {
                val ex = thrownBy { provider.getKeySet() }
                assertTrue(causedByEgressRefusal(ex), "the failure must come from the egress guard, was $ex")
                assertEquals(listOf(discovery), requests.urls, "only the public discovery document may be fetched")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `S23 a did web host that resolves to a private address is refused before any request`() =
        runTest {
            val requests = Requests()
            val resolver = DidWebResolver(recordingEngine(requests) { "{}" }, guardResolvingTo("10.0.0.9"))

            val ex = thrownBy { resolver.resolve("did:web:internal.eg.example") }

            assertTrue(causedByEgressRefusal(ex), "the failure must come from the egress guard, was $ex")
            assertEquals(0, requests.count.get())
        }

    @Test
    fun `S23 a did web identifier that is a private IP literal is refused before any request`() =
        runTest {
            val requests = Requests()
            val resolver = DidWebResolver(recordingEngine(requests) { "{}" }, publicEgressGuard())

            val ex = thrownBy { resolver.resolve("did:web:10.0.0.5") }

            assertNotNull(ex, "did:web on a private literal must not resolve")
            assertEquals(0, requests.count.get())
        }

    @Test
    fun `S23 a did web host on a public address is fetched`() =
        runTest {
            val requests = Requests()
            val did = "did:web:keys.eg.example"
            val doc = """{"id":"$did","verificationMethod":[]}"""
            val resolver = DidWebResolver(recordingEngine(requests) { doc }, publicEgressGuard())

            assertEquals(did, resolver.resolve(did).id)
            assertEquals(listOf("https://keys.eg.example/.well-known/did.json"), requests.urls)
        }

    @Test
    fun `S23 a loopback key source is reachable only when allow_insecure_url is set`() =
        runTest {
            val url = "http://127.0.0.1:9/jwks.json"

            val closedRequests = Requests()
            val closed = VerifierConfig.Jwks(jwksUri = url, allowInsecureUrl = false)
            val closedEx =
                thrownBy {
                    val p =
                        DefaultJwksKeySetProvider(
                            closed.toKeySource(),
                            Path.of("."),
                            httpClientEngineForTest = recordingEngine(closedRequests) { jwksJson }
                        )
                    try {
                        p.getKeySet()
                    } finally {
                        p.close()
                    }
                }
            assertNotNull(closedEx)
            assertEquals(0, closedRequests.count.get())

            val openRequests = Requests()
            val open = VerifierConfig.Jwks(jwksUri = url, allowInsecureUrl = true)
            val p =
                DefaultJwksKeySetProvider(
                    open.toKeySource(),
                    Path.of("."),
                    httpClientEngineForTest = recordingEngine(openRequests) { jwksJson }
                )
            try {
                assertEquals(
                    1,
                    p
                        .getKeySet()
                        .keys.keys.size
                )
                assertEquals(1, openRequests.count.get())
            } finally {
                p.close()
            }
        }

    // -------------------------------------------------------------------------
    // S23 - the refusal surfaces as "unavailable" in both verifier mappers
    // -------------------------------------------------------------------------

    private val token: String by lazy {
        val header =
            com.nimbusds.jose.JWSHeader
                .Builder(com.nimbusds.jose.JWSAlgorithm.RS256)
                .keyID("eg-key")
                .build()
        val claims =
            com.nimbusds.jwt.JWTClaimsSet
                .Builder()
                .subject("agent-1")
                .issuer("https://idp.eg.example")
                .audience("eg-aud")
                .expirationTime(
                    java.util.Date.from(
                        java.time.Instant
                            .now()
                            .plusSeconds(300)
                    )
                ).build()
        val jwt = com.nimbusds.jwt.SignedJWT(header, claims)
        jwt.sign(
            com.nimbusds.jose.crypto
                .RSASSASigner(rsaKey)
        )
        jwt.serialize()
    }

    @Test
    fun `S23 a refused key source makes the actor verifier UNAVAILABLE and the API verifier fail closed`() =
        runTest {
            val requests = Requests()
            val actorConfig =
                VerifierConfig.Jwks(
                    jwksUri = "https://10.0.0.5/jwks.json",
                    issuer = "https://idp.eg.example",
                    audience = "eg-aud",
                    algorithms = listOf("RS256"),
                )
            val actorProvider =
                DefaultJwksKeySetProvider(
                    actorConfig.toKeySource(),
                    Path.of("."),
                    httpClientEngineForTest = recordingEngine(requests) { jwksJson }
                )
            try {
                val result = JwksActorVerifier(actorConfig, actorProvider).verify(ActorClaim("agent-1", ActorKind.SUBAGENT, proof = token))
                assertEquals(VerificationStatus.UNAVAILABLE, result.status, result.reason)
            } finally {
                actorProvider.close()
            }

            val apiConfig =
                ApiAuthConfig.Jwks(
                    url = "https://10.0.0.5/jwks.json",
                    issuer = "https://idp.eg.example",
                    audience = "eg-aud",
                    algorithms = listOf("RS256"),
                    cacheTtlSeconds = 300,
                )
            val apiProvider =
                DefaultJwksKeySetProvider(
                    apiConfig.toKeySource(),
                    Path.of("."),
                    httpClientEngineForTest = recordingEngine(requests) { jwksJson }
                )
            try {
                assertNull(JwksApiVerifier(apiConfig, apiProvider).verify(token))
            } finally {
                apiProvider.close()
            }

            assertEquals(0, requests.count.get(), "neither mapper may cause a request to the refused address")
        }
}
