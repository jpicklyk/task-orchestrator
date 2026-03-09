package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests that exercise tools through the full MCP protocol stack:
 * Client → ChannelTransport → Server → McpToolAdapter → ToolDefinition → response.
 *
 * Uses the SDK 0.9.0 kotlin-sdk-testing module with in-process channel transport.
 */
class McpToolAdapterIntegrationTest {
    private lateinit var server: Server
    private lateinit var client: Client
    private lateinit var adapter: McpToolAdapter

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server =
                Server(
                    serverInfo = Implementation(name = "test-server", version = "1.0.0"),
                    options =
                        ServerOptions(
                            capabilities =
                                ServerCapabilities(
                                    tools = ServerCapabilities.Tools(listChanged = true)
                                )
                        )
                )

            adapter = McpToolAdapter()

            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()

            client =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                    options =
                        ClientOptions(
                            capabilities = ClientCapabilities()
                        )
                )

            server.createSession(serverTransport)
            client.connect(clientTransport)
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            client.close()
            server.close()
        }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    /** A minimal ToolDefinition for testing that echoes its input. */
    private fun echoTool(): ToolDefinition =
        object : ToolDefinition {
            override val name = "echo"
            override val description = "Echoes input back"
            override val parameterSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put(
                                "message",
                                buildJsonObject {
                                    put("type", JsonPrimitive("string"))
                                    put("description", JsonPrimitive("Message to echo"))
                                }
                            )
                        },
                    required = listOf("message")
                )
            override val category = ToolCategory.SYSTEM

            override fun validateParams(params: JsonElement) {
                val obj =
                    params as? JsonObject
                        ?: throw ToolValidationException("Expected JSON object")
                if (obj["message"] == null) {
                    throw ToolValidationException("Missing required parameter: message")
                }
            }

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement {
                val message = (params as JsonObject)["message"]?.jsonPrimitive?.content ?: ""
                return buildJsonObject {
                    put("status", JsonPrimitive("success"))
                    put("message", JsonPrimitive("Echo: $message"))
                    put(
                        "data",
                        buildJsonObject {
                            put("echoed", JsonPrimitive(message))
                        }
                    )
                }
            }
        }

    /** A tool that always throws an exception during execution. */
    private fun failingTool(): ToolDefinition =
        object : ToolDefinition {
            override val name = "failing_tool"
            override val description = "Always fails"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw RuntimeException("Intentional test failure")
        }

    private val dummyContext =
        ToolExecutionContext(
            repositoryProvider =
                io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider(
                    io.github.jpicklyk.mcptask.current.infrastructure.database
                        .DatabaseManager(
                            org.jetbrains.exposed.v1.jdbc.Database.connect(
                                "jdbc:h2:mem:integration_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                                driver = "org.h2.Driver"
                            )
                        ).also {
                            io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management
                                .DirectDatabaseSchemaManager()
                                .updateSchema()
                        }
                )
        )

    // ──────────────────────────────────────────────
    // Tool listing through MCP protocol
    // ──────────────────────────────────────────────

    @Test
    fun `listTools returns registered tool`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            val result = client.listTools()
            val tools = result.tools
            assertEquals(1, tools.size)
            assertEquals("echo", tools[0].name)
            assertEquals("Echoes input back", tools[0].description)
        }

    // ──────────────────────────────────────────────
    // Successful tool call through MCP protocol
    // ──────────────────────────────────────────────

    @Test
    fun `callTool executes tool and returns result`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            val result =
                client.callTool(
                    name = "echo",
                    arguments = mapOf("message" to "hello world")
                )

            // Should not be an error
            assertTrue(result.isError != true, "Expected successful result but got error")

            // Content should contain the user summary
            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty(), "Expected at least one TextContent")
            assertTrue(textContent[0].text.contains("Echo: hello world"), "Summary should contain echo message")
        }

    // ──────────────────────────────────────────────
    // Validation error propagation
    // ──────────────────────────────────────────────

    @Test
    fun `callTool returns validation error when required param missing`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            val result =
                client.callTool(
                    name = "echo",
                    arguments = emptyMap()
                )

            // Should be flagged as error
            assertEquals(true, result.isError)

            // Error message should contain validation info
            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty())
            assertTrue(textContent[0].text.contains("Validation error"), "Should contain validation error message")
            assertTrue(textContent[0].text.contains("message"), "Should reference the missing parameter")
        }

    // ──────────────────────────────────────────────
    // Internal error propagation
    // ──────────────────────────────────────────────

    @Test
    fun `callTool returns internal error when tool throws exception`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, failingTool(), dummyContext)

            val result =
                client.callTool(
                    name = "failing_tool",
                    arguments = emptyMap()
                )

            assertEquals(true, result.isError)

            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty())
            assertTrue(textContent[0].text.contains("Internal error"), "Should contain internal error message")
            assertTrue(textContent[0].text.contains("Intentional test failure"), "Should contain exception message")
        }

    // ──────────────────────────────────────────────
    // Boolean preprocessing through MCP stack
    // ──────────────────────────────────────────────

    @Test
    fun `callTool preprocesses string booleans`(): Unit =
        runBlocking {
            // Tool that checks a boolean parameter
            val boolTool =
                object : ToolDefinition {
                    override val name = "bool_check"
                    override val description = "Checks boolean preprocessing"
                    override val parameterSchema =
                        ToolSchema(
                            properties =
                                buildJsonObject {
                                    put(
                                        "flag",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("boolean"))
                                        }
                                    )
                                    put(
                                        "message",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("string"))
                                        }
                                    )
                                }
                        )
                    override val category = ToolCategory.SYSTEM

                    override suspend fun execute(
                        params: JsonElement,
                        context: ToolExecutionContext
                    ): JsonElement {
                        val flag = (params as JsonObject)["flag"]
                        // After preprocessing, "true" string should become boolean true
                        val isBooleanTrue = flag is JsonPrimitive && !flag.isString && flag.boolean
                        return buildJsonObject {
                            put("status", JsonPrimitive("success"))
                            put("message", JsonPrimitive("flag=$isBooleanTrue"))
                            put(
                                "data",
                                buildJsonObject {
                                    put("isBooleanTrue", JsonPrimitive(isBooleanTrue))
                                }
                            )
                        }
                    }
                }

            adapter.registerToolWithServer(server, boolTool, dummyContext)

            // Send "true" as a string — MCP clients often do this
            val result =
                client.callTool(
                    name = "bool_check",
                    arguments = mapOf("flag" to "true", "message" to "test")
                )

            assertTrue(result.isError != true, "Expected successful result")
            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent[0].text.contains("flag=true"), "Boolean preprocessing should convert string 'true' to boolean")
        }

    // ──────────────────────────────────────────────
    // Multiple tool registration
    // ──────────────────────────────────────────────

    @Test
    fun `registerToolsWithServer registers multiple tools`(): Unit =
        runBlocking {
            adapter.registerToolsWithServer(server, listOf(echoTool(), failingTool()), dummyContext)

            val result = client.listTools()
            assertEquals(2, result.tools.size)

            val names = result.tools.map { it.name }.toSet()
            assertTrue("echo" in names)
            assertTrue("failing_tool" in names)
        }

    // ──────────────────────────────────────────────
    // Real tool through MCP stack
    // ──────────────────────────────────────────────

    @Test
    fun `callTool with real ManageItemsTool creates item`(): Unit =
        runBlocking {
            val manageTool =
                io.github.jpicklyk.mcptask.current.application.tools.items
                    .ManageItemsTool()
            adapter.registerToolWithServer(server, manageTool, dummyContext)

            val result =
                client.callTool(
                    name = "manage_items",
                    arguments =
                        mapOf(
                            "operation" to "create",
                            "items" to listOf(mapOf("title" to "Integration test item"))
                        )
                )

            assertTrue(result.isError != true, "Expected successful item creation, got: ${result.content}")
            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty())
            // The summary should indicate items were created
            assertTrue(
                textContent[0].text.contains("created", ignoreCase = true) ||
                    textContent[0].text.contains("1", ignoreCase = true),
                "Summary should indicate item creation: ${textContent[0].text}"
            )
        }
}
