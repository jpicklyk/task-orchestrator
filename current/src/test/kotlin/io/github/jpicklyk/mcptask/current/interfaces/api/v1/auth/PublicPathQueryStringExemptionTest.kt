package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.sha256
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `3a6c0e5a` — focused on the `ApiBearerAuth`
 * ([ApiAuthPluginConfig.publicPaths]/`publicPrefixes`) path-exemption mechanism itself, isolated
 * from the SSE route.
 *
 * Bug context (frozen at queue phase, before implementation): `ApiBearerAuth` is an
 * application-level plugin (`createApplicationPlugin`), so it intercepts every request regardless
 * of where `install()` is textually called -- registering a route in a sibling `route("/api/v1")`
 * block does NOT scope it away. The only way a path escapes authentication is the plugin's own
 * `publicPaths`/`publicPrefixes` exemption. That exemption must match the DECODED PATH ONLY, with
 * no query string -- matching against `call.request.local.uri` instead (which includes the query
 * string) would mean an exempted entry like `/api/v1/events` never matches
 * `/api/v1/events?token=x`, silently 401-ing every request that legitimately carries query
 * parameters alongside an exempted path.
 *
 * This suite stands up `ApiBearerAuth` directly (no SSE route, no full server wiring) against
 * plain probe routes, so the path-matching mechanism is exercised in isolation from
 * [SseAuthFullWiringTest][io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.SseAuthFullWiringTest],
 * which proves the same fix through the full production wiring.
 *
 * Oracle: `AuthenticationPlugin.kt`'s own documented contract (KDoc on `publicPaths`/`publicPrefixes`
 * cited in the frozen `test-plan` note) plus `current/docs/api-rest.md` §1 (every `/api/v1` route
 * requires authentication by default; exemptions are the deliberate exception).
 */
class PublicPathQueryStringExemptionTest {
    companion object {
        private const val PROBE_TOKEN = "public-path-probe-token"
    }

    private fun bearerConfig(): ApiAuthConfig.Bearer {
        val principal =
            ApiPrincipal(
                tokenId = "public-path-probe-principal",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        return ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(PROBE_TOKEN)) to principal))
    }

    /** Installs [ApiBearerAuth] with a caller-chosen `publicPaths` set (default `publicPrefixes`
     * left at `/.well-known/`) plus a handful of probe routes: the exempted path, two
     * prefix/suffix look-alikes, a protected path, and a well-known-prefixed path. */
    private fun Application.wireProbeApp(publicPaths: Set<String>) {
        val config = bearerConfig()
        // Required so ApiBearerAuth's 401/403 JSON body can actually be serialized -- without it
        // Ktor answers 406 Not Acceptable (no negotiated content type) instead of the auth plugin's
        // intended 401/403, exactly as the existing full-wiring route tests install it.
        install(ContentNegotiation) { json(McpJson) }
        install(ApiBearerAuth) {
            authConfig = config
            tokenEntries = config.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) }
            this.publicPaths = publicPaths
        }
        routing {
            get("/api/v1/probe") { call.respondText("public-ok") }
            get("/api/v1/probefoo") { call.respondText("prefix-leak-ok") }
            get("/api/v1/probe2") { call.respondText("prefix-leak-ok-2") }
            get("/api/v1/other") { call.respondText("protected-ok") }
            get("/.well-known/discovery") { call.respondText("wellknown-ok") }
        }
    }

    @Test
    fun `exact public path WITH a query string is exempted - the core regression fix`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }
            val response = client.get("/api/v1/probe?token=abc&extra=1")
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "publicPaths must match the decoded path only, ignoring the query string entirely",
            )
        }

    @Test
    fun `exact public path with no query string is exempted (baseline)`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }
            assertEquals(HttpStatusCode.OK, client.get("/api/v1/probe").status)
        }

    @Test
    fun `a non-exempted path still requires auth regardless of query string, and a valid header still works`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }

            val noAuth = client.get("/api/v1/other?token=abc")
            assertEquals(
                HttpStatusCode.Unauthorized,
                noAuth.status,
                "a path outside publicPaths must still require auth, even carrying a token-shaped query string",
            )

            val withAuth =
                client.get("/api/v1/other?token=abc") {
                    header(HttpHeaders.Authorization, "Bearer $PROBE_TOKEN")
                }
            assertEquals(
                HttpStatusCode.OK,
                withAuth.status,
                "a valid bearer header must still authenticate a non-exempted path",
            )
        }

    @Test
    fun `prefix-leak probe - probefoo and probe2 do not inherit the probe exemption`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/probefoo").status,
                "probefoo must not match the exact publicPaths entry for /api/v1/probe",
            )
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/probe2").status,
                "probe2 must not match the exact publicPaths entry for /api/v1/probe",
            )
        }

    @Test
    fun `trailing slash on the exempted path is not treated as the same exact path`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }
            val response = client.get("/api/v1/probe/")
            // Whether Ktor's routing 404s the unmatched trailing-slash route or the plugin's exact
            // string-equality check simply fails to match "/api/v1/probe/" against "/api/v1/probe",
            // the one outcome that must NOT occur is a 200 riding the exemption meant for the exact
            // path.
            assertTrue(
                response.status != HttpStatusCode.OK,
                "trailing-slash variant of an exempted path must not be treated as exempted. Got: ${response.status}",
            )
        }

    @Test
    fun `publicPrefixes matching also ignores the query string`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) } // publicPrefixes defaults to "/.well-known/"
            val response = client.get("/.well-known/discovery?x=1&y=2")
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "publicPrefixes matching must also ignore the query string -- same call.request.path() fix applies to prefixes",
            )
        }

    @Test
    fun `multiple query params and a bare trailing question mark still match the exempted path`() =
        testApplication {
            application { wireProbeApp(publicPaths = setOf("/api/v1/probe")) }
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/probe?a=1&b=2").status,
                "multiple query params must not change the decoded path used for matching",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/probe?").status,
                "a bare trailing '?' with no params must not change the path either",
            )
        }
}
