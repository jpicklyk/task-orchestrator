package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Regression guard: enabling the REST API must NOT gate the MCP transport endpoint.
 *
 * `ApiBearerAuth` is an application-level plugin, so it intercepts every request — not only
 * `/api/v1`. The `/mcp` Streamable HTTP endpoint is a separate protocol that MCP clients reach
 * without a REST bearer token; it must stay open while the `/api/v1` routes are gated. This was unreachable
 * until the SSE-double-install startup crash was fixed (HTTP + API-enabled could never run before),
 * so the leak shipped silently. This test wires the SAME production functions
 * ([installMcpStreamableHttp] + [installRestApiRoutes]) with the REST API enabled in bearer mode.
 */
class McpRestAuthBypassTest {
    private val token = "rest-bearer-token-for-test"

    private fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    private fun bearerConfig(): Pair<ApiAuthConfig.Bearer, Map<HashBytes, BearerTokenStore.TokenEntry>> {
        val principal =
            ApiPrincipal(
                tokenId = "test",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER,
            )
        val key = HashBytes(sha256(token))
        return ApiAuthConfig.Bearer(tokens = mapOf(key to principal)) to
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

    private fun inMemoryProvider(): DefaultRepositoryProvider =
        DefaultRepositoryProvider(
            DatabaseManager(
                Database.connect(
                    "jdbc:h2:mem:authbypass_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    driver = "org.h2.Driver",
                ),
            ).also { DirectDatabaseSchemaManager().updateSchema() },
        )

    @Test
    fun `REST API enabled gates api routes but leaves the mcp endpoint open`() =
        testApplication {
            val (apiConfig, entries) = bearerConfig()
            val provider = inMemoryProvider()
            application {
                // Same wiring CurrentMcpServer uses in HTTP mode with the API enabled.
                installMcpStreamableHttp(emptyServer())
                installRestApiRoutes(
                    apiConfig = apiConfig,
                    eventBus = null,
                    effectiveProvider = provider,
                    apiTokenEntries = entries,
                    allowQueryToken = false,
                    serverName = "auth-bypass-test",
                    serverVersion = "1.0.0",
                    actorAuthEnabled = false,
                    noteSchemaService = NoOpNoteSchemaService,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    idempotencyCache = IdempotencyCache(),
                )
            }

            // /mcp must NOT require the REST bearer token — initialize succeeds with no Authorization.
            val mcp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, "application/json, text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
                            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
                            """"clientInfo":{"name":"t","version":"1.0"}}}""",
                    )
                }
            assertEquals(
                HttpStatusCode.OK,
                mcp.status,
                "/mcp must stay open when the REST API is enabled (no bearer token sent)",
            )

            // /api/v1 routes MUST be gated — no token → 401.
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get("/api/v1/items").status,
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
                client.get("/api/v1/items") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
                "/api/v1/items must succeed with a valid bearer token",
            )
        }
}
