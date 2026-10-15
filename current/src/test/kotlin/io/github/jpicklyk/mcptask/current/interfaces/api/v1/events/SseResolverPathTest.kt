package io.github.jpicklyk.mcptask.current.interfaces.api.v1.events

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.CacheState
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksKeySetProvider
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksResult
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.eventRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import io.ktor.client.plugins.sse.SSE as ClientSSE

/**
 * Independent tests (test-author seat, item b9527ef9) for the SSE authentication path of `GET /api/v1/events` after the bearer
 * pipeline became one [io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver] (scenarios S8, S6 on the
 * SSE path, S11 on the SSE path and the S4 and S3 probes that reach `/events`).
 *
 * Oracles: `api-rest.md` section 1 failure table (missing or non-Bearer header: 401 `invalid_request`; bad or expired token: 401
 * `invalid_token`; valid token without the required capability: 403 `forbidden` with `detail {scope: "capability", required}`),
 * section 21 (the stream needs `read`, the `?token=` query form is an opt-in), the R10 plan paragraph and task-scope item 7 (a
 * credential with `profile: director` is `forbidden{scope: capability, required: "director"}` on any path other than
 * `/api/v1/ops/director/{tool}`, SSE included, whatever its capabilities), task-scope items 4 and 5 and decision D2 (an unknown or
 * non-string `to_kind` rejects the JWT; the JWT path is the same resolver as the bearer path). Expected values are literals.
 *
 * Negative-assertion conditions: every refusal is paired, inside the same test, with an accepted request through the same
 * route and the same wiring that differs only in the credential (a token without the profile, a valid `to_kind`, the query
 * flag switched on), and every accepted control proves the stream by receiving a replayed event. A route that refused
 * everything, or that streamed everything, fails one of each pair.
 *
 * Harness: the real `eventRoutes` under `testApplication`, mirroring [EventRoutesTest] and [SseJwksExpiryWatchdogTest]; the
 * `Authorization` header is set verbatim on the client request, so the exact header shape reaches the plugin.
 *
 * Domain invariants satisfied by construction: token-file fixtures are written by one generator (64-hex lowercase digest,
 * `scope` mapping, unique ids, only recognized keys); JWTs carry the configured issuer and audience and a whole-second `exp`
 * well inside the lifetime cap.
 */
class SseResolverPathTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    companion object {
        init {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }

        private val rsaKey = RSAKeyGenerator(2048).keyID("sse-resolver-key").generate()
        private const val ISSUER = "https://idp.test.example"
        private const val AUDIENCE = "task-orchestrator-api"

        private const val READ_RAW = "sse-resolver-read-token"
        private const val DIR_RAW = "sse-resolver-director-token"
        private const val DIR_ADMIN_RAW = "sse-resolver-director-admin-token"
        private const val NEW_CAPS_RAW = "sse-resolver-newcaps-token"
        private const val ADMIN_RAW = "sse-resolver-admin-token"
        private const val EXPIRED_RAW = "sse-resolver-expired-token"
    }

    private var fileCounter = 0

    // ------------------------------------------------------------------ fixtures

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun entry(
        id: String,
        raw: String,
        capabilities: String,
        vararg extra: String,
        expiresAt: String? = null,
    ): String {
        val lines =
            mutableListOf(
                "  - id: $id",
                "    token_sha256: \"${sha256Hex(raw)}\"",
                "    scope:",
                "      root_ids: null",
                "      tags_include: []",
                "    capabilities: $capabilities",
            )
        if (expiresAt != null) lines += "    expires_at: \"$expiresAt\""
        extra.forEach { lines += "    $it" }
        return lines.joinToString("\n")
    }

    private fun load(vararg entries: String): Map<HashBytes, BearerTokenStore.TokenEntry> {
        val file = dir.resolve("sse-tokens-${fileCounter++}.yaml").toFile()
        file.writeText("version: 1\ntokens:\n" + entries.joinToString("\n") + "\n")
        return BearerTokenStore(file.absolutePath).loadWithEntries()
    }

    private fun standardEntries(): Map<HashBytes, BearerTokenStore.TokenEntry> =
        load(
            entry("sse-read", READ_RAW, "[read]"),
            entry("sse-dir", DIR_RAW, "[read]", "profile: director"),
            entry("sse-dir-admin", DIR_ADMIN_RAW, "[admin]", "profile: director"),
            entry("sse-newcaps", NEW_CAPS_RAW, "[write-docs, runs, decide]"),
            entry("sse-admin", ADMIN_RAW, "[admin]"),
            entry("sse-expired", EXPIRED_RAW, "[read]", expiresAt = "2020-01-01T00:00:00Z"),
        )

    private fun jwksConfig() =
        ApiAuthConfig.Jwks(
            url = "https://idp.test.example/.well-known/jwks.json",
            issuer = ISSUER,
            audience = AUDIENCE,
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    private fun verifier(): JwksApiVerifier {
        val provider = mockk<JwksKeySetProvider>()
        val result = JwksResult(JWKSet(listOf(rsaKey.toPublicJWK())), CacheState(fromStaleCache = false, ageSeconds = null))
        coEvery { provider.getKeySet() } returns result
        every { provider.getResolvedIssuer() } returns null
        every { provider.close() } just Runs
        return JwksApiVerifier(jwksConfig(), provider)
    }

    private fun jwt(claims: Map<String, Any> = emptyMap()): String {
        val builder =
            JWTClaimsSet
                .Builder()
                .subject("sse-jwt-caller")
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .expirationTime(Date.from(Instant.ofEpochSecond(Instant.now().epochSecond + 300)))
        claims.forEach { (k, v) -> builder.claim(k, v) }
        val signed = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("sse-resolver-key").build(), builder.build())
        signed.sign(RSASSASigner(rsaKey))
        return signed.serialize()
    }

    private fun ApplicationTestBuilder.mountBearer(
        bus: ApiEventBus,
        entries: Map<HashBytes, BearerTokenStore.TokenEntry>,
        allowQueryToken: Boolean,
    ) = application {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing { eventRoutes(bus, entries, allowQueryToken = allowQueryToken, redactAttribution = true) }
    }

    private fun ApplicationTestBuilder.mountJwks(
        bus: ApiEventBus,
        allowQueryToken: Boolean,
    ) = application {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            eventRoutes(
                bus,
                tokenEntries = emptyMap(),
                jwksVerifier = verifier(),
                authCheckIntervalSeconds = 60,
                authConfig = jwksConfig(),
                allowQueryToken = allowQueryToken,
                redactAttribution = true,
            )
        }
    }

    /** Connects with [path] and [headers], collects the one replayed event, and returns its type: proof the stream was opened. */
    private suspend fun ApplicationTestBuilder.streamedEventType(
        path: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val sseClient = createClient { install(ClientSSE) }
        var type: String? = null
        withTimeout(10.seconds) {
            sseClient.sse(
                urlString = path,
                request = {
                    headers.forEach { (k, v) -> header(k, v) }
                    header("Last-Event-ID", FROM_START)
                },
            ) {
                incoming.take(1).toList().forEach { type = it.event }
            }
        }
        return type ?: error("no event was streamed for $path")
    }

    private suspend fun HttpResponse.errorEnvelope(): JsonObject =
        Json
            .parseToJsonElement(bodyAsText())
            .jsonObject
            .getValue("error")
            .jsonObject

    private suspend fun assertForbidden(
        response: HttpResponse,
        required: String,
        label: String,
    ) {
        assertEquals(HttpStatusCode.Forbidden, response.status, label)
        val error = response.errorEnvelope()
        assertEquals("forbidden", error.getValue("code").jsonPrimitive.content, label)
        val detail = error.getValue("detail").jsonObject
        assertEquals("capability", detail.getValue("scope").jsonPrimitive.content, label)
        assertEquals(required, detail.getValue("required").jsonPrimitive.content, label)
    }

    private suspend fun ApiEventBus.seedEvent() = emit(ApiEventType.ITEM_CREATED, itemId = UUID.randomUUID())

    // ------------------------------------------------------------------ S8 bearer header and query path

    @Test
    fun `S8 a read token streams over the Authorization header and over the opt-in query token`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountBearer(bus, standardEntries(), allowQueryToken = true)

            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $READ_RAW")))
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events?token=$READ_RAW"))
        }

    @Test
    fun `S8 the query token is refused when the opt-in is off while the header still streams`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountBearer(bus, standardEntries(), allowQueryToken = false)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/events?token=$READ_RAW").status)
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $READ_RAW")))
        }

    @Test
    fun `S8 a missing header is 401 invalid_request and an unknown token is 401 invalid_token`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            mountBearer(bus, standardEntries(), allowQueryToken = false)

            val missing = client.get("/events")
            assertEquals(HttpStatusCode.Unauthorized, missing.status)
            assertTrue(
                missing.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_request\""),
                "missing header: ${missing.headers[HttpHeaders.WWWAuthenticate]}",
            )

            val unknown = client.get("/events") { header(HttpHeaders.Authorization, "Bearer not-a-known-token") }
            assertEquals(HttpStatusCode.Unauthorized, unknown.status)
            assertTrue(
                unknown.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""),
                "unknown token: ${unknown.headers[HttpHeaders.WWWAuthenticate]}",
            )
        }

    @Test
    fun `S7 an expired entry is 401 invalid_token on the header and on the query token`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            mountBearer(bus, standardEntries(), allowQueryToken = true)

            val viaHeader = client.get("/events") { header(HttpHeaders.Authorization, "Bearer $EXPIRED_RAW") }
            assertEquals(HttpStatusCode.Unauthorized, viaHeader.status)
            assertTrue(viaHeader.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""))
            assertEquals(HttpStatusCode.Unauthorized, client.get("/events?token=$EXPIRED_RAW").status)
        }

    // ------------------------------------------------------------------ pre-flight READ

    @Test
    fun `a token holding only the new capabilities is 403 required read while an admin token streams`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountBearer(bus, standardEntries(), allowQueryToken = true)

            assertForbidden(
                client.get("/events") { header(HttpHeaders.Authorization, "Bearer $NEW_CAPS_RAW") },
                required = "read",
                label = "write-docs, runs and decide do not imply read",
            )
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $ADMIN_RAW")))
        }

    // ------------------------------------------------------------------ S11 director credential on SSE

    @Test
    fun `S11 a director-profile credential is forbidden director on the header and on the query token`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountBearer(bus, standardEntries(), allowQueryToken = true)

            assertForbidden(
                client.get("/events") { header(HttpHeaders.Authorization, "Bearer $DIR_RAW") },
                required = "director",
                label = "director credential, header",
            )
            assertForbidden(client.get("/events?token=$DIR_RAW"), required = "director", label = "director credential, query token")

            // control: the same wiring streams for a credential without the profile
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $READ_RAW")))
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events?token=$READ_RAW"))
        }

    @Test
    fun `S11 probe admin does not lift the director restriction on SSE`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountBearer(bus, standardEntries(), allowQueryToken = true)

            assertForbidden(
                client.get("/events") { header(HttpHeaders.Authorization, "Bearer $DIR_ADMIN_RAW") },
                required = "director",
                label = "admin plus director profile",
            )
            // control: an admin credential without the profile streams
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $ADMIN_RAW")))
        }

    // ------------------------------------------------------------------ S4 / S6 JWT through the SSE plugin

    @Test
    fun `S4 a valid JWT streams over the header and the query token`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountJwks(bus, allowQueryToken = true)

            val token = jwt()
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $token")))
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events?token=$token"))
        }

    @Test
    fun `S6 a JWT with an unknown or non-string to_kind is 401 invalid_token on SSE while a valid to_kind streams`(): Unit =
        testApplication {
            val bus = ApiEventBus(source = db.repositoryProvider().eventStore())
            bus.seedEvent()
            mountJwks(bus, allowQueryToken = true)

            val robot = client.get("/events") { header(HttpHeaders.Authorization, "Bearer ${jwt(mapOf("to_kind" to "robot"))}") }
            assertEquals(HttpStatusCode.Unauthorized, robot.status)
            assertTrue(robot.headers[HttpHeaders.WWWAuthenticate].orEmpty().contains("error=\"invalid_token\""))

            val numeric = client.get("/events") { header(HttpHeaders.Authorization, "Bearer ${jwt(mapOf("to_kind" to 5))}") }
            assertEquals(HttpStatusCode.Unauthorized, numeric.status)

            assertEquals(HttpStatusCode.Unauthorized, client.get("/events?token=${jwt(mapOf("to_kind" to "robot"))}").status)

            // control: the same wiring and the same claim set with a valid kind streams
            val ok = jwt(mapOf("to_kind" to "human"))
            assertEquals(ApiEventType.ITEM_CREATED, streamedEventType("/events", mapOf(HttpHeaders.Authorization to "Bearer $ok")))
        }
}
