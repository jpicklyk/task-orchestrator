package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end tests for the MCP **Streamable HTTP** transport mounted at `/mcp`.
 *
 * These tests are the regression guard for the SDK-0.12.0 startup crash where an explicit
 * `install(SSE)` collided with the SSE plugin that `mcpStreamableHttp` installs itself
 * (`DuplicatePluginException` → HTTP server never started). They exercise the SAME production
 * wiring function ([installMcpStreamableHttp]) and the SAME tool set ([buildMcpTools]) the real
 * server uses — so re-introducing a duplicate plugin install, or otherwise breaking the `/mcp`
 * module, fails the application at first request and trips these tests.
 *
 * Unlike [McpToolAdapterIntegrationTest] (in-process ChannelTransport), this drives the protocol
 * over real HTTP through Ktor's test engine, covering the transport layer the integration test
 * cannot reach.
 */
class McpStreamableHttpTransportTest {
    private val rpc = Json { ignoreUnknownKeys = true }

    /** Builds a real MCP [Server] with all production tools registered against an in-memory DB. */
    private fun serverWithAllTools(): Server {
        val provider =
            DefaultRepositoryProvider(
                DatabaseManager(
                    Database.connect(
                        "jdbc:h2:mem:httptransport_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                        driver = "org.h2.Driver",
                    ),
                ).also { DirectDatabaseSchemaManager().updateSchema() },
            )
        val server =
            Server(
                serverInfo = Implementation(name = "http-transport-test", version = "1.0.0"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)),
                    ),
            )
        McpToolAdapter().registerToolsWithServer(server, buildMcpTools(), ToolExecutionContext(provider))
        return server
    }

    /**
     * Extracts the JSON-RPC envelope from a Streamable-HTTP response body. The transport may reply
     * with raw `application/json` or an SSE frame (`event: message\ndata: {...}`); handle both.
     */
    private fun parseRpc(body: String): JsonObject {
        val payload =
            body
                .lineSequence()
                .firstOrNull { it.startsWith("data:") }
                ?.removePrefix("data:")
                ?.trim()
                ?: body.trim()
        return rpc.parseToJsonElement(payload).jsonObject
    }

    private val initializeBody =
        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"transport-test","version":"1.0"}}}"""

    private val acceptBoth = "application/json, text/event-stream"

    @Test
    fun `mcpStreamableHttp module wires without SSE conflict and serves initialize`() =
        testApplication {
            val server = serverWithAllTools()
            application { installMcpStreamableHttp(server) }

            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    contentType(ContentType.Application.Json)
                    setBody(initializeBody)
                }

            // If a duplicate SSE plugin were installed, the app would fail to start and this would
            // not be 200 OK — this assertion is the core regression guard.
            assertEquals(HttpStatusCode.OK, response.status, "initialize should return 200")

            val sessionId = response.headers["mcp-session-id"]
            assertNotNull(sessionId, "server must assign an Mcp-Session-Id on initialize")

            val result = parseRpc(response.bodyAsText())["result"]?.jsonObject
            assertNotNull(result, "initialize must return a result")
            assertNotNull(result!!["protocolVersion"], "result must carry protocolVersion")
            assertNotNull(result["serverInfo"], "result must carry serverInfo")
        }

    @Test
    fun `rejects cross-origin requests with 403 to guard against DNS rebinding`() =
        testApplication {
            val server = serverWithAllTools()
            application { installMcpStreamableHttp(server) }

            // Streamable HTTP spec security requirement: servers MUST validate the Origin header and
            // respond 403 to an invalid origin (DNS-rebinding protection). This is enforced by the
            // SDK's mcpStreamableHttp; the test locks the behavior in for our wiring so a future SDK
            // bump or misconfiguration that drops it is caught.
            val response =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    header(HttpHeaders.Origin, "http://evil.example.com")
                    contentType(ContentType.Application.Json)
                    setBody(initializeBody)
                }

            assertEquals(
                HttpStatusCode.Forbidden,
                response.status,
                "a cross-origin Origin header must be rejected with 403",
            )
        }

    @Test
    fun `tools list and a tool call work over the HTTP transport`() =
        testApplication {
            val server = serverWithAllTools()
            application { installMcpStreamableHttp(server) }

            // 1) initialize → capture the session id
            val init =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    contentType(ContentType.Application.Json)
                    setBody(initializeBody)
                }
            assertEquals(HttpStatusCode.OK, init.status)
            val sessionId = init.headers["mcp-session-id"]
            assertNotNull(sessionId, "expected Mcp-Session-Id header")

            // 2) notifications/initialized — completes the handshake
            client.post("/mcp") {
                header(HttpHeaders.Accept, acceptBoth)
                header("mcp-session-id", sessionId!!)
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }

            // 3) tools/list — every production tool must be served over HTTP
            val listResp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    header("mcp-session-id", sessionId!!)
                    contentType(ContentType.Application.Json)
                    setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
                }
            assertEquals(HttpStatusCode.OK, listResp.status)
            val servedNames =
                parseRpc(listResp.bodyAsText())["result"]!!
                    .jsonObject["tools"]!!
                    .jsonArray
                    .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    .toSet()
            val expectedNames = buildMcpTools().map { it.name }.toSet()
            assertEquals(expectedNames, servedNames, "tools/list must expose exactly the production tool set")

            // 4) tools/call query_items overview — proves the full stack executes over HTTP
            val callResp =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, acceptBoth)
                    header("mcp-session-id", sessionId!!)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call",""" +
                            """"params":{"name":"query_items","arguments":{"operation":"overview"}}}""",
                    )
                }
            assertEquals(HttpStatusCode.OK, callResp.status)
            val callResult = parseRpc(callResp.bodyAsText())["result"]!!.jsonObject
            assertTrue(
                callResult["isError"]?.jsonPrimitive?.content != "true",
                "query_items overview should not be an error over HTTP: $callResult",
            )
        }
}
