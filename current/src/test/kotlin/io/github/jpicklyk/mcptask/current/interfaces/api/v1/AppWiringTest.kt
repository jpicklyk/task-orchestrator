package io.github.jpicklyk.mcptask.current.interfaces.api.v1

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.serviceRoutes
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.wellKnownRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.MessageDigest

/**
 * Integration tests for REST API route wiring.
 *
 * These tests verify the Phase 2 server topology:
 * - ContentNegotiation with McpJson installed before routes
 * - CORS plugin installed
 * - SSE plugin installed
 * - /api/v1/info, /api/v1/health, /.well-known/... routes mounted correctly
 * - Auth plugin enforcing bearer tokens on /api/v1 routes
 * - /api/v1/health bypasses auth
 * - /.well-known/... bypasses auth
 *
 * Note: These tests do NOT instantiate a real MCP server (requires live DB).
 * They test the REST routing topology only.
 */
class AppWiringTest {

    private fun sha256(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }

    private fun makeBearerConfig(token: String, tokenId: String = "test-token"): ApiAuthConfig.Bearer {
        val principal = ApiPrincipal(
            tokenId = tokenId,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ),
            authMode = ApiAuthMode.BEARER,
        )
        return ApiAuthConfig.Bearer(tokens = mapOf(HashBytes(sha256(token)) to principal))
    }

    // -------------------------------------------------------------------------
    // /api/v1/health — should bypass auth
    // -------------------------------------------------------------------------

    @Test
    fun `health endpoint returns 200 without any auth header`() = testApplication {
        val apiConfig = makeBearerConfig("my-secret-token")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(CORS) { }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
                wellKnownRoutes(serverName = "wiring-test", serverVersion = "2.0.0")
            }
        }

        val response = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"status\""), "Expected status field: $body")
    }

    @Test
    fun `health endpoint returns 200 even with auth header present`() = testApplication {
        val token = "my-secret-token"
        val apiConfig = makeBearerConfig(token)
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
            }
        }

        val response = client.get("/api/v1/health") {
            header("Authorization", "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // -------------------------------------------------------------------------
    // /.well-known/mcp-task-orchestrator.json — should bypass auth
    // -------------------------------------------------------------------------

    @Test
    fun `well-known endpoint returns 200 without auth`() = testApplication {
        val apiConfig = makeBearerConfig("my-secret-token")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
                wellKnownRoutes(serverName = "wiring-test", serverVersion = "2.0.0")
            }
        }

        val response = client.get("/.well-known/mcp-task-orchestrator.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("wiring-test"), "Expected server name: $body")
        assertTrue(body.contains("\"/api/v1\""), "Expected apiUrl: $body")
    }

    // -------------------------------------------------------------------------
    // /api/v1/info — requires auth
    // -------------------------------------------------------------------------

    @Test
    fun `info endpoint returns 401 without auth header`() = testApplication {
        val apiConfig = makeBearerConfig("my-secret-token")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
            }
        }

        val response = client.get("/api/v1/info")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `info endpoint returns 200 with valid bearer token`() = testApplication {
        val plainToken = "valid-bearer-token-abc123"
        val apiConfig = makeBearerConfig(plainToken)
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(
                        repositoryProvider = null,
                        serverName = "wiring-test",
                        serverVersion = "2.0.0",
                        actorAuthEnabled = false,
                    )
                }
            }
        }

        val response = client.get("/api/v1/info") {
            header("Authorization", "Bearer $plainToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"apiVersion\""), "Expected apiVersion field: $body")
        assertTrue(body.contains("\"v1\""), "Expected v1 value: $body")
        assertTrue(body.contains("\"capabilities\""), "Expected capabilities: $body")
    }

    @Test
    fun `info endpoint returns 401 with invalid bearer token`() = testApplication {
        val apiConfig = makeBearerConfig("correct-token")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
            }
        }

        val response = client.get("/api/v1/info") {
            header("Authorization", "Bearer wrong-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `info response includes caller capabilities`() = testApplication {
        val plainToken = "admin-token"
        val principal = ApiPrincipal(
            tokenId = "admin-principal",
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS),
            authMode = ApiAuthMode.BEARER,
        )
        val apiConfig = ApiAuthConfig.Bearer(
            tokens = mapOf(HashBytes(sha256(plainToken)) to principal)
        )
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
            }
        }

        val response = client.get("/api/v1/info") {
            header("Authorization", "Bearer $plainToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("read"), "Expected read capability: $body")
        assertTrue(body.contains("write-items"), "Expected write-items capability: $body")
    }

    // -------------------------------------------------------------------------
    // API_ENABLED=false scenario — routes not mounted
    // -------------------------------------------------------------------------

    @Test
    fun `REST routes not mounted when API is disabled`() = testApplication {
        application {
            install(ContentNegotiation) { json(McpJson) }
            // Conditional: API disabled means no routing block
        }

        val healthResponse = client.get("/api/v1/health")
        assertEquals(HttpStatusCode.NotFound, healthResponse.status)

        val wellKnownResponse = client.get("/.well-known/mcp-task-orchestrator.json")
        assertEquals(HttpStatusCode.NotFound, wellKnownResponse.status)
    }

    // -------------------------------------------------------------------------
    // Info payload structure
    // -------------------------------------------------------------------------

    @Test
    fun `info response payload has expected structure`() = testApplication {
        val plainToken = "structure-test-token"
        val apiConfig = makeBearerConfig(plainToken, tokenId = "structure-principal")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(
                        repositoryProvider = null,
                        serverName = "wiring-test-server",
                        serverVersion = "2.0.0",
                    )
                }
            }
        }

        val response = client.get("/api/v1/info") {
            header("Authorization", "Bearer $plainToken")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"serverName\""), "Missing serverName: $body")
        assertTrue(body.contains("\"version\""), "Missing version: $body")
        assertTrue(body.contains("\"apiVersion\""), "Missing apiVersion: $body")
        assertTrue(body.contains("\"capabilities\""), "Missing capabilities: $body")
        assertTrue(body.contains("\"claimModeAvailable\""), "Missing claimModeAvailable: $body")
        assertTrue(body.contains("\"actorAuthenticationEnabled\""), "Missing actorAuthenticationEnabled: $body")
        assertTrue(body.contains("wiring-test-server"), "Missing server name value: $body")
        assertTrue(body.contains("2.0.0"), "Missing version value: $body")
    }

    // -------------------------------------------------------------------------
    // Well-known payload structure
    // -------------------------------------------------------------------------

    @Test
    fun `well-known response has all required fields`() = testApplication {
        val apiConfig = makeBearerConfig("any-token")
        application {
            install(ContentNegotiation) { json(McpJson) }
            install(SSE)
            routing {
                route("/api/v1") {
                    install(ApiBearerAuth) {
                        authConfig = apiConfig
                        tokenEntries = apiConfig.tokens.mapValues { (_, p) ->
                            BearerTokenStore.TokenEntry(p, expiresAt = null)
                        }
                    }
                    serviceRoutes(repositoryProvider = null, serverName = "wiring-test", serverVersion = "2.0.0")
                }
                wellKnownRoutes(serverName = "wiring-test", serverVersion = "2.0.0")
            }
        }

        val response = client.get("/.well-known/mcp-task-orchestrator.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"name\""), "Missing name: $body")
        assertTrue(body.contains("\"version\""), "Missing version: $body")
        assertTrue(body.contains("\"apiVersion\""), "Missing apiVersion: $body")
        assertTrue(body.contains("\"apiUrl\""), "Missing apiUrl: $body")
    }
}
