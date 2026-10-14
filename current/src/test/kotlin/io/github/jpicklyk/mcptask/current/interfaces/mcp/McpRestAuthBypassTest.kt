package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.bootstrap.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.security.MessageDigest
import java.time.Instant
import kotlin.test.assertIs

/**
 * Regression guard (inverted by item 8ae4c8e7, R14): enabling the REST API now gates the MCP transport endpoint too.
 *
 * Before R14 `ApiBearerAuth` exempted `/mcp`, so an MCP client reached the server without any credential while `/api/v1`
 * was gated. Now `/mcp` leaves the public paths: with the REST API in bearer mode the production wiring resolves
 * [McpAuthMode.Credential] and `McpBearerAuth` answers `/mcp` with the same resolver, so a request without a resolvable
 * bearer token is 401 `unauthenticated`. Oracle: the R14 plan paragraph ("`/mcp` leaves `publicPaths`"), AC1 and AC7
 * ("`McpRestAuthBypassTest` asserts the inverse"). The `/api/v1` assertions are unchanged. This test wires the SAME production
 * functions ([installMcpStreamableHttp] + [installRestApiRoutes]) with the REST API enabled in bearer mode; the 401 is paired
 * with a 200 for the same call under a valid token, so a plugin that refuses everything fails the pair.
 */
class McpRestAuthBypassTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val token = "rest-bearer-token-for-test"

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun bearerConfig(): Pair<ApiAuthConfig.Bearer, Map<HashBytes, BearerTokenStore.TokenEntry>> {
        val principal =
            Principal(
                id = "test",
                scope = AccessScope.principal(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                kind = PrincipalKind.SERVICE,
                proofStatus = ProofStatus.ISSUED,
            )
        val key = HashBytes(sha256(token))
        return ApiAuthConfig.Bearer(tokens = mapOf(key to BearerTokenStore.TokenEntry(principal, expiresAt = null))) to
            mapOf(key to BearerTokenStore.TokenEntry(principal, expiresAt = null))
    }

    private fun emptyServer(): Server =
        Server(
            serverInfo = Implementation(name = "auth-bypass-test", version = "1.0.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                ),
        )

    private fun inMemoryProvider(): DefaultRepositoryProvider = db.repositoryProvider()

    @Test
    fun `REST API enabled gates api routes and the mcp endpoint alike`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = inMemoryProvider()
            val resolution = McpAuthMode.resolve("http", apiConfig, entries, null, { Instant.now() }, false)
            val mcpAuth = assertIs<McpAuthResolution.Resolved>(resolution).mode
            application {
                // Same wiring CurrentMcpServer uses in HTTP mode with the API enabled.
                installMcpStreamableHttp(emptyServer(), appConfig = AppConfig.fromEnv(), mcpAuth = mcpAuth)
                installRestApiRoutes(
                    apiConfig = apiConfig,
                    eventBus = null,
                    effectiveProvider = provider,
                    apiTokenEntries = entries,
                    allowQueryToken = false,
                    serverName = "auth-bypass-test",
                    serverVersion = "1.0.0",
                    actorAuthEnabled = false,
                    serverConfig = ServerConfigSource { null },
                    toolContext =
                        ToolExecutionContext(
                            provider,
                            statusLabelService = NoOpStatusLabelService,
                            perRootConfigService = PerRootConfigService(provider.configStore()),
                            unitOfWork = db.unitOfWork()
                        ),
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    appConfig = AppConfig.fromEnv(),
                )
            }

            val initialize =
                """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                    """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                    """"clientInfo":{"name":"t","version":"1.0"}}}"""

            // /mcp must now REQUIRE the bearer token: initialize with no Authorization is 401 unauthenticated.
            val mcp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }
            assertEquals(
                HttpStatusCode.Unauthorized,
                mcp.status,
                "/mcp must be gated when the REST API is enabled (no bearer token sent)",
            )
            assertTrue("unauthenticated" in mcp.bodyAsText(), "the refusal is the unauthenticated envelope")

            // Control: the same call under the valid token initializes.
            val authorised =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    header(HttpHeaders.Authorization, "Bearer $token")
                    contentType(ContentType.Application.Json)
                    setBody(initialize)
                }
            assertEquals(HttpStatusCode.OK, authorised.status, "/mcp initializes with a valid bearer token")

            // /api/v1 routes MUST be gated — no token → 401.
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/resources/leases").status,
                "/api/v1/* must require a bearer token",
            )

            // /api/v1/health stays public (existing bypass) — no token → 200.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/health").status,
                "/api/v1/health must remain public",
            )

            // With a valid token, /api/v1 routes are reachable.
            assertEquals(
                HttpStatusCode.OK,
                client.get("/api/v1/resources/leases") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
                "/api/v1/resources/leases must succeed with a valid bearer token",
            )
        }
}
