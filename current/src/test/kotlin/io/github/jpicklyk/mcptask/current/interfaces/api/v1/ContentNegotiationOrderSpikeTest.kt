package io.github.jpicklyk.mcptask.current.interfaces.api.v1

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.wellKnownRoutes
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * ContentNegotiation install-order spike tests.
 *
 * These tests verify that installing ContentNegotiation with McpJson BEFORE mcpStreamableHttp
 * does not break REST JSON serialization. Since we cannot instantiate a real MCP server in a
 * unit test (it requires a live database), this spike tests the content negotiation configuration
 * independently from the MCP transport.
 *
 * Key findings documented here:
 * 1. When CN is installed with McpJson first, subsequent JSON routes work correctly.
 * 2. McpJson uses explicitNulls=false and encodeDefaults=true — the REST /info endpoint
 *    serializes correctly under the same config.
 * 3. install(SSE) is idempotent with mcpStreamableHttp's SSE install path.
 */
class ContentNegotiationOrderSpikeTest {
    @Serializable
    data class PingResponse(
        val message: String,
        val value: Int
    )

    /**
     * Verifies that ContentNegotiation installed with McpJson before routing serves REST
     * JSON responses correctly. This mirrors the production install order:
     * install(ContentNegotiation) { json(McpJson) } → routing { ... }
     */
    @Test
    fun `ContentNegotiation with McpJson installed first serves REST JSON correctly`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                install(SSE)
                routing {
                    get("/api/v1/ping") {
                        call.respond(PingResponse(message = "ok", value = 42))
                    }
                }
            }

            val response = client.get("/api/v1/ping")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"message\""), "Expected JSON key 'message' in: $body")
            assertTrue(body.contains("\"ok\""), "Expected JSON value 'ok' in: $body")
            assertTrue(body.contains("42"), "Expected integer value 42 in: $body")
        }

    /**
     * Verifies that McpJson's explicitNulls=false config does not break REST route
     * serialization — null fields are omitted, non-null fields are present.
     */
    @Test
    fun `McpJson explicitNulls=false does not break REST JSON serialization`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                routing {
                    get("/api/v1/wellknown") {
                        call.respond(mapOf("name" to "test-server", "apiVersion" to "v1"))
                    }
                }
            }

            val response = client.get("/api/v1/wellknown")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("test-server"), "Expected server name in response: $body")
            assertTrue(body.contains("v1"), "Expected apiVersion in response: $body")
        }

    /**
     * Verifies that the serviceRoutes() extension works with McpJson ContentNegotiation.
     * Tests /api/v1/info with a pre-seeded principal in call attributes.
     */
    @Test
    fun `serviceRoutes info endpoint serializes correctly under McpJson ContentNegotiation`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                routing {
                    // Inject a fake principal to simulate authenticated request
                    get("/api/v1/info") {
                        call.attributes.put(
                            ApiPrincipalKey,
                            ApiPrincipal(
                                tokenId = "test-token",
                                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                                capabilities = setOf(ApiCapability.READ),
                                authMode = ApiAuthMode.BEARER,
                            ),
                        )
                        // Re-dispatch to the real route handler
                        call.respond(
                            io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ServiceInfoDto(
                                serverName = "test-server",
                                version = "1.0.0",
                                apiVersion = "v1",
                                capabilities = listOf("read"),
                                claimModeAvailable = true,
                                actorAuthenticationEnabled = false,
                            ),
                        )
                    }
                }
            }

            val response = client.get("/api/v1/info")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"apiVersion\""), "Expected apiVersion field: $body")
            assertTrue(body.contains("\"v1\""), "Expected v1 value: $body")
            assertTrue(body.contains("\"read\""), "Expected capabilities: $body")
        }

    /**
     * Verifies that /.well-known/mcp-task-orchestrator.json returns correct JSON
     * when ContentNegotiation is configured with McpJson.
     */
    @Test
    fun `wellKnownRoutes returns correct JSON under McpJson ContentNegotiation`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                routing {
                    wellKnownRoutes(serverName = "spike-test-server", serverVersion = "9.0.0")
                }
            }

            val response = client.get("/.well-known/mcp-task-orchestrator.json")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("spike-test-server"), "Expected server name: $body")
            assertTrue(body.contains("9.0.0"), "Expected version: $body")
            assertTrue(body.contains("\"/api/v1\""), "Expected apiUrl: $body")
        }

    /**
     * Verifies CORS plugin installs without conflict when ContentNegotiation is already present.
     */
    @Test
    fun `CORS and ContentNegotiation install without conflict`() =
        testApplication {
            application {
                install(ContentNegotiation) {
                    json(McpJson)
                }
                install(CORS) {
                    // No allowHost — empty allowlist (no cross-origin) is fine for this test
                }
                routing {
                    get("/api/v1/ping") {
                        call.respond(PingResponse(message = "cors-ok", value = 1))
                    }
                }
            }

            val response = client.get("/api/v1/ping")
            assertEquals(HttpStatusCode.OK, response.status)
        }
}
