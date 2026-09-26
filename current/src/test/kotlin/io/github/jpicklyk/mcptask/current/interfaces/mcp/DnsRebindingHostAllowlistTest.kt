package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ErrorDto
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * DNS-rebinding Host-allowlist regression suite for item `abf48945-d4e5-4e34-a78f-ebdfa6f5793c`.
 *
 * Oracle provenance: every expected value below traces to the `test-plan` note (scenarios S1-S15
 * plus the adversarial probe catalog) frozen at queue phase by the planning seat, and through it
 * to the `diagnosis` note's "Decisions (frozen by planning seat)" (a)-(h), RFC 3986 3.2.2/3.2.3,
 * RFC 1034 3.1, the MCP 2025-11-25 Streamable HTTP transport spec's Security Warning #1, and
 * JSON-RPC 2.0 5.1. Nothing here was read off `HostAllowlistPlugin.kt` / `AppConfig.kt`'s
 * implementation bodies to decide correctness (test-author skill S4, blindness rule) -- this
 * author worked only from the frozen notes above and the public declarations supplied in the
 * dispatch prompt, per the item's `needs-test-author` trait.
 *
 * Wires the exact same production functions the fix touches -- [installMcpStreamableHttp] then
 * [installRestApiRoutes], both given the SAME [AppConfig] instance -- inside `testApplication`,
 * following the pattern in [McpRestAuthBypassTest] and [McpStreamableHttpTransportTest]. Every
 * [AppConfig] is built from an explicit, empty-unless-stated env map (never the real process
 * environment) so these tests cannot pick up a stray `MCP_ALLOWED_HOSTS`/`CORS_ALLOWED_ORIGINS`
 * from the machine running them.
 */
class DnsRebindingHostAllowlistTest {
    private val rpc = Json { ignoreUnknownKeys = true }

    private val acceptBoth = "application/json, text/event-stream"

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"dns-rebinding-test","version":"1.0"}}}"""

    /** Same shape as the reproduction in `diagnosis`: an attacker page rebinding to loopback. */
    private val rebindHost = "attacker.example:3001"
    private val rebindOrigin = "http://attacker.example:3001"

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private fun emptyServer(): Server =
        Server(
            serverInfo = Implementation(name = "dns-rebinding-test", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
        )

    private fun inMemoryProvider(): DefaultRepositoryProvider =
        DefaultRepositoryProvider(
            DatabaseManager(
                Database.connect(
                    "jdbc:h2:mem:dnsrebind_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                ),
            ).also { DirectDatabaseSchemaManager().updateSchema() },
        )

    /** An [AppConfig] built from an explicit env map -- never [System.getenv] -- for isolation. */
    private fun cfg(vararg env: Pair<String, String>): AppConfig = AppConfig.fromEnv(env = mapOf(*env)::get)

    private fun defaultCfg(): AppConfig = cfg()

    private fun bearerConfig(): Pair<ApiAuthConfig.Bearer, Map<HashBytes, BearerTokenStore.TokenEntry>> {
        val principal =
            ApiPrincipal(
                tokenId = "dns-rebind-bearer-principal",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        val key = HashBytes(ByteArray(32) { 0x11 })
        return ApiAuthConfig.Bearer(tokens = mapOf(key to principal)) to
            mapOf(key to BearerTokenStore.TokenEntry(principal, expiresAt = null))
    }

    /** Wires the SAME [appConfig] instance into both production install functions. */
    private fun Application.wire(
        appConfig: AppConfig,
        apiConfig: ApiAuthConfig = ApiAuthConfig.Unauthenticated,
        tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry> = emptyMap(),
        provider: DefaultRepositoryProvider = inMemoryProvider(),
        server: Server = emptyServer(),
    ) {
        installMcpStreamableHttp(server, appConfig)
        installRestApiRoutes(
            apiConfig = apiConfig,
            eventBus = null,
            effectiveProvider = provider,
            apiTokenEntries = tokenEntries,
            allowQueryToken = false,
            serverName = "dns-rebinding-test",
            serverVersion = "1.0.0",
            actorAuthEnabled = false,
            noteSchemaService = NoOpNoteSchemaService,
            toolContext =
                ToolExecutionContext(
                    provider,
                    NoOpNoteSchemaService,
                    statusLabelService = YamlStatusLabelService(),
                    perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                ),
            degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
            idempotencyCache = IdempotencyCache(),
            appConfig = appConfig,
        )
    }

    /** Captures WARN-level log records from the plugin's own logger (built from a String, not a class). */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logbackLogger = LoggerFactory.getLogger("HostAllowlistPlugin") as Logger
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

    private suspend fun HttpClient.postMcp(
        host: String? = null,
        origin: String? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ): HttpResponse =
        this.post("/mcp") {
            header(HttpHeaders.Accept, acceptBoth)
            if (host != null) header(HttpHeaders.Host, host)
            if (origin != null) header(HttpHeaders.Origin, origin)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(initializeBody)
        }

    private suspend fun HttpClient.apiCall(
        method: HttpMethod,
        path: String,
        host: String? = null,
        origin: String? = null,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        body: String? = null,
    ): HttpResponse =
        this.request(path) {
            this.method = method
            if (host != null) header(HttpHeaders.Host, host)
            if (origin != null) header(HttpHeaders.Origin, origin)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            if (body != null) setBody(body)
        }

    // ------------------------------------------------------------------
    // Assertion helpers
    // ------------------------------------------------------------------

    /** D-e: /mcp rejection is a JSON-RPC error envelope, code -32000, id absent or null. */
    private fun assertJsonRpcHostRejection(
        response: HttpResponse,
        body: String,
    ) {
        assertEquals(HttpStatusCode.Forbidden, response.status, "rejected Host must be 403, body: $body")
        val json = rpc.parseToJsonElement(body).jsonObject
        assertEquals("2.0", json["jsonrpc"]?.jsonPrimitive?.content, "jsonrpc must be \"2.0\": $body")
        val error = json["error"]?.jsonObject
        assertTrue(error != null, "expected a JSON-RPC error envelope, got: $body")
        assertEquals(-32000, error!!["code"]?.jsonPrimitive?.int, "JSON-RPC error code must be -32000: $body")
        val message = error["message"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(message.contains("MCP_ALLOWED_HOSTS"), "message must name MCP_ALLOWED_HOSTS: $message")
        val idElement = json["id"]
        assertTrue(idElement == null || idElement is JsonNull, "id must be absent or null, was: $idElement")
    }

    /** D-e: every non-/mcp rejection is an [ErrorDto] with error == "host_not_allowed". */
    private fun assertErrorDtoHostRejection(
        response: HttpResponse,
        body: String,
    ) {
        assertEquals(HttpStatusCode.Forbidden, response.status, "rejected Host must be 403, body: $body")
        val err = rpc.decodeFromString<ErrorDto>(body)
        assertEquals("host_not_allowed", err.error, "error code must be host_not_allowed: $body")
        assertTrue(err.message.contains("MCP_ALLOWED_HOSTS"), "message must name MCP_ALLOWED_HOSTS: ${err.message}")
    }

    /** D-e: neither rejection body ever echoes the rejected Host value back to the caller. */
    private fun assertNeverEchoesHost(
        body: String,
        rejectedHost: String,
    ) {
        assertTrue(!body.contains(rejectedHost), "response must never echo the rejected Host '$rejectedHost': $body")
    }

    // ==================================================================
    // Happy
    // ==================================================================

    @Test
    fun `S1 loopback defaults accepted on any port and with no port`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            listOf("localhost:3001", "127.0.0.1:54321", "[::1]:3001", "localhost").forEach { host ->
                val response = client.postMcp(host = host)
                assertEquals(HttpStatusCode.OK, response.status, "S1: loopback Host '$host' must be accepted")
            }
        }

    @Test
    fun `S2 hostname comparison is case-insensitive and tolerates one trailing dot`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            listOf("LOCALHOST:3001", "localhost.:3001").forEach { host ->
                val response = client.postMcp(host = host)
                assertEquals(
                    HttpStatusCode.OK,
                    response.status,
                    "S2 (RFC 3986 3.2.2 case-insensitivity / RFC 1034 3.1 trailing-dot): '$host' must be accepted",
                )
            }
        }

    @Test
    fun `S3 configured entries extend the loopback defaults, host-port entries match only that port`() =
        testApplication {
            val appConfig = cfg("MCP_ALLOWED_HOSTS" to "tohost.lan, box.lan:8443")
            application { wire(appConfig = appConfig) }

            assertEquals(
                HttpStatusCode.OK,
                client.postMcp(host = "TOHOST.lan:9999").status,
                "S3: bare-host entry 'tohost.lan' must match any port, case-insensitively",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.postMcp(host = "box.lan:8443").status,
                "S3: host:port entry must match that exact port",
            )
            assertEquals(
                HttpStatusCode.OK,
                client.postMcp(host = "localhost:3001").status,
                "S3: configured entries EXTEND, never replace, the always-on loopback defaults",
            )
            val wrongPort = client.postMcp(host = "box.lan:8444")
            assertJsonRpcHostRejection(wrongPort, wrongPort.bodyAsText())
        }

    @Test
    fun `S4 loopback Host with same-origin Origin reaches the REST API`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val response = client.apiCall(HttpMethod.Get, "/api/v1/items", host = "localhost:3001", origin = "http://localhost:3001")
            assertEquals(HttpStatusCode.OK, response.status, "S4: loopback Host + same-origin Origin must reach /api/v1")
        }

    @Test
    fun `S5 CORS_ALLOWED_ORIGINS is independent of the Host allowlist`() =
        testApplication {
            val appConfig = cfg("CORS_ALLOWED_ORIGINS" to "https://dash.example.com")
            application { wire(appConfig = appConfig) }
            val response =
                client.apiCall(
                    HttpMethod.Get,
                    "/api/v1/items",
                    host = "localhost:3001",
                    origin = "https://dash.example.com",
                )
            assertEquals(
                HttpStatusCode.OK,
                response.status,
                "S5: loopback Host + a CORS_ALLOWED_ORIGINS-listed Origin must reach /api/v1",
            )
        }

    // ==================================================================
    // Failure
    // ==================================================================

    @Test
    fun `S6 rebinding shape is rejected on every mcp HTTP method with a JSON-RPC envelope and no session id`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }

            val post = client.postMcp(host = rebindHost, origin = rebindOrigin)
            val postBody = post.bodyAsText()
            assertJsonRpcHostRejection(post, postBody)
            assertNeverEchoesHost(postBody, "attacker.example")
            assertNull(post.headers["mcp-session-id"], "S6: a rejected request must not get an Mcp-Session-Id")

            val get =
                client.get("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    header(HttpHeaders.Host, rebindHost)
                    header(HttpHeaders.Origin, rebindOrigin)
                }
            assertJsonRpcHostRejection(get, get.bodyAsText())

            val delete =
                client.delete("/mcp") {
                    header(HttpHeaders.Host, rebindHost)
                    header(HttpHeaders.Origin, rebindOrigin)
                }
            assertJsonRpcHostRejection(delete, delete.bodyAsText())
        }

    @Test
    fun `S7 rebinding shape is rejected on every api v1 surface and never reaches the item store`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val gatePath = "/api/v1/items/${UUID.randomUUID()}/gate"
            val marker = "dns-rebind-marker-${UUID.randomUUID()}"

            val getPaths =
                listOf(
                    "/api/v1/items",
                    gatePath,
                    "/api/v1/health",
                    "/api/v1/events",
                    "/.well-known/mcp-task-orchestrator.json",
                )
            getPaths.forEach { path ->
                val response = client.apiCall(HttpMethod.Get, path, host = rebindHost, origin = rebindOrigin)
                val body = response.bodyAsText()
                assertErrorDtoHostRejection(response, body)
                assertNeverEchoesHost(body, "attacker.example")
            }

            val post =
                client.apiCall(
                    HttpMethod.Post,
                    "/api/v1/items",
                    host = rebindHost,
                    origin = rebindOrigin,
                    body = marker,
                )
            assertErrorDtoHostRejection(post, post.bodyAsText())

            // The rejected POST must never have reached the item store: a loopback read afterward
            // must not see anything carrying the marker the rejected request's body would have set.
            val followUp = client.apiCall(HttpMethod.Get, "/api/v1/items", host = "localhost:3001", origin = "http://localhost:3001")
            assertEquals(HttpStatusCode.OK, followUp.status, "S7: loopback follow-up GET must succeed")
            assertTrue(
                !followUp.bodyAsText().contains(marker),
                "S7: the rejected POST must not have created an item (marker leaked into the item list)",
            )
        }

    @Test
    fun `S8 foreign Host with no Origin at all is still rejected`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val mcp = client.postMcp(host = rebindHost)
            assertJsonRpcHostRejection(mcp, mcp.bodyAsText())

            val api = client.apiCall(HttpMethod.Get, "/api/v1/items", host = rebindHost)
            assertErrorDtoHostRejection(api, api.bodyAsText())
        }

    @Test
    fun `S9 loopback Host with a foreign Origin is rejected by the pre-existing CORS check`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val response =
                client.apiCall(
                    HttpMethod.Get,
                    "/api/v1/items",
                    host = "localhost:3001",
                    origin = "http://evil.example",
                )
            // D-d: no new mechanism for Origin -- this 403 is the pre-existing Ktor CORS rejection,
            // not the Host allowlist (Host itself is loopback and passes). Only the status is this
            // scenario's oracle; the CORS plugin's own body/shape is out of this item's scope.
            assertEquals(HttpStatusCode.Forbidden, response.status, "S9: loopback Host + foreign Origin must still 403 via CORS")
        }

    // S10 is split into three independent @Test methods (10a/10b/10c) rather than three sequential
    // assertions in one method: JUnit stops a test method at its first failed assertion, and each
    // sub-case is an independently-oracled claim from test-plan S10 -- splitting them means a red
    // result in one sub-case does not hide whether the others are red or green.
    //
    // Only the third sub-case's test-plan line is qualified "bearer mode" ("...; bearer mode,
    // foreign Host, no token -> 403 not 401") -- the first two are not, so 10a/10b use the same
    // default (unauthenticated) wiring as S7-S9 to isolate Host-vs-CORS precedence from the
    // Host-vs-auth precedence 10c targets; wiring 10a/10b under Bearer mode too would confound the
    // two questions (Bearer mode's own auth check would fire on the same missing-Authorization
    // request 10c is built to probe).

    @Test
    fun `S10a Host allowlist wins precedence over CORS -- foreign Host plus a different foreign Origin gets our body`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val precedence =
                client.apiCall(
                    HttpMethod.Get,
                    "/api/v1/items",
                    host = rebindHost,
                    origin = "http://other.example",
                )
            assertErrorDtoHostRejection(precedence, precedence.bodyAsText())
        }

    @Test
    fun `S10b an OPTIONS CORS preflight from a foreign Host still gets our host_not_allowed body`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val preflight =
                client.apiCall(
                    HttpMethod.Options,
                    "/api/v1/items",
                    host = rebindHost,
                    origin = "http://other.example",
                    extraHeaders = listOf("Access-Control-Request-Method" to "GET"),
                )
            assertErrorDtoHostRejection(preflight, preflight.bodyAsText())
        }

    @Test
    fun `S10c bearer mode, foreign Host, no Authorization header -- 403 from the Host check, not 401 from auth`() =
        testApplication {
            val (bearerCfg, bearerEntries) = bearerConfig()
            application { wire(appConfig = defaultCfg(), apiConfig = bearerCfg, tokenEntries = bearerEntries) }
            val noToken = client.apiCall(HttpMethod.Get, "/api/v1/items", host = rebindHost)
            assertEquals(
                HttpStatusCode.Forbidden,
                noToken.status,
                "S10: the Host check must run before ApiBearerAuth -- a missing token must still read as 403, not 401",
            )
            assertErrorDtoHostRejection(noToken, noToken.bodyAsText())
        }

    // ==================================================================
    // Edge
    // ==================================================================

    @Test
    fun `S11 lookalike hosts are rejected -- exact match only, no subdomain, no range, no IPv6 canonicalisation`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val lookalikes =
                listOf(
                    "localhost.attacker.example",
                    "127.0.0.1.nip.io",
                    "127.0.0.2",
                    "::1",
                    "[0:0:0:0:0:0:0:1]",
                    "localhost:abc",
                    "user@localhost",
                    "localhost:3001, attacker.example",
                )
            lookalikes.forEach { host ->
                val response = client.postMcp(host = host)
                assertJsonRpcHostRejection(response, response.bodyAsText())
            }
        }

    @Test
    fun `S12 absent Host is allowed, blank or repeated Host is rejected`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }

            val absent = client.postMcp(host = null)
            assertEquals(HttpStatusCode.OK, absent.status, "S12: an absent Host header must be allowed (D-f)")

            val blank = client.postMcp(host = "")
            assertJsonRpcHostRejection(blank, blank.bodyAsText())

            val duplicated =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    header(HttpHeaders.Host, "localhost:3001")
                    header(HttpHeaders.Host, "attacker.example:3001")
                    contentType(ContentType.Application.Json)
                    setBody(initializeBody)
                }
            assertJsonRpcHostRejection(duplicated, duplicated.bodyAsText())
        }

    @Test
    fun `S13 MCP_ALLOWED_HOSTS equal to the wildcard disables the guard and WARNs at install`() =
        testApplication {
            val appConfig = cfg("MCP_ALLOWED_HOSTS" to "*")
            var warnLogs = emptyList<String>()
            application { warnLogs = captureWarnLogs { wire(appConfig = appConfig) } }

            val response = client.postMcp(host = rebindHost, origin = rebindOrigin)
            assertEquals(HttpStatusCode.OK, response.status, "S13: MCP_ALLOWED_HOSTS=* must disable the guard entirely")
            assertTrue(
                warnLogs.any { it.contains("MCP_ALLOWED_HOSTS") },
                "S13: disabling the guard must WARN naming MCP_ALLOWED_HOSTS at install; got: $warnLogs",
            )
        }

    @Test
    fun `S14 a malformed configured entry with a scheme is dropped, never widening the allowlist, with a WARN`() =
        testApplication {
            val appConfig = cfg("MCP_ALLOWED_HOSTS" to "http://tohost.lan")
            var warnLogs = emptyList<String>()
            application { warnLogs = captureWarnLogs { wire(appConfig = appConfig) } }

            val response = client.postMcp(host = "tohost.lan")
            assertJsonRpcHostRejection(response, response.bodyAsText())
            assertTrue(
                warnLogs.any { it.contains("MCP_ALLOWED_HOSTS") && it.contains("http://tohost.lan") },
                "S14: a malformed entry (scheme present) must WARN naming MCP_ALLOWED_HOSTS and the raw entry; got: $warnLogs",
            )
        }

    @Test
    fun `S15 parseCsv trims and blank-filters, fromEnv wires mcpAllowedHosts, unset is empty`() {
        // NEW-SURFACE (test-plan): AppConfig#mcpAllowedHosts and #parseCsv did not exist before this
        // fix. Oracle: D-c plus AppConfig.parseCsv's own KDoc ("trimmed, blank-filtered list, or
        // null when unset").
        assertEquals(
            listOf("a.lan", "B.lan:8443"),
            AppConfig.parseCsv(" a.lan , ,B.lan:8443 "),
            "S15: parseCsv must trim each entry and drop blank ones",
        )
        assertEquals(null, AppConfig.parseCsv(null), "S15: parseCsv(null) must be null so callers can apply their own default")

        assertEquals(
            emptyList<String>(),
            AppConfig.fromEnv(env = emptyMap<String, String>()::get).mcpAllowedHosts,
            "S15: mcpAllowedHosts must default to an empty list when MCP_ALLOWED_HOSTS is unset",
        )
        assertEquals(
            listOf("a.lan", "B.lan:8443"),
            AppConfig.fromEnv(env = mapOf("MCP_ALLOWED_HOSTS" to " a.lan , ,B.lan:8443 ")::get).mcpAllowedHosts,
            "S15: fromEnv must wire MCP_ALLOWED_HOSTS through parseCsv into mcpAllowedHosts",
        )
    }

    // ==================================================================
    // Adversarial probes (test-author skill S6) -- every probe is recorded in test-manifest,
    // including the clean (no-finding) ones.
    // ==================================================================

    @Test
    fun `probe X-Forwarded-Host does not launder a foreign Host past the allowlist`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val response =
                client.postMcp(
                    host = rebindHost,
                    extraHeaders = listOf("X-Forwarded-Host" to "localhost:3001"),
                )
            assertJsonRpcHostRejection(response, response.bodyAsText())
        }

    @Test
    fun `probe percent-encoded lookalike host is rejected -- no decoding, exact string match only`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val response = client.postMcp(host = "localhost%2eattacker.example")
            assertJsonRpcHostRejection(response, response.bodyAsText())
        }

    @Test
    fun `probe homoglyph host is rejected when the client will even send it`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            // Cyrillic 'о' (U+043E) in place of the Latin 'o' -- visually "localhost", not equal to it.
            val homoglyphHost = "lоcalhost"
            try {
                val response = client.postMcp(host = homoglyphHost)
                assertJsonRpcHostRejection(response, response.bodyAsText())
            } catch (e: IllegalArgumentException) {
                // N/A per test-plan: the HTTP client itself refused a non-ASCII header value.
                assertTrue(true, "probe N/A: client rejected non-ASCII Host header: ${e.message}")
            }
        }

    @Test
    fun `probe a trailing dot on the request side matches a configured entry without one`() =
        testApplication {
            val appConfig = cfg("MCP_ALLOWED_HOSTS" to "tohost.lan")
            application { wire(appConfig = appConfig) }
            val response = client.postMcp(host = "tohost.lan.")
            assertEquals(HttpStatusCode.OK, response.status, "probe: request-side trailing dot must still match 'tohost.lan'")
        }

    @Test
    fun `probe a bare trailing colon with zero port digits still matches a bare-host entry`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val response = client.postMcp(host = "localhost:")
            assertEquals(HttpStatusCode.OK, response.status, "probe: 'localhost:' (port = *DIGIT, zero digits) must match")
        }

    @Test
    fun `probe a rejected rebinding request is rejected identically on repeat -- no caching bypass`() =
        testApplication {
            application { wire(appConfig = defaultCfg()) }
            val first = client.postMcp(host = rebindHost, origin = rebindOrigin)
            assertJsonRpcHostRejection(first, first.bodyAsText())
            val second = client.postMcp(host = rebindHost, origin = rebindOrigin)
            assertJsonRpcHostRejection(second, second.bodyAsText())
        }
}
