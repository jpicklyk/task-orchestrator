package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.tools.*
import io.github.jpicklyk.mcptask.current.domain.model.ErrorKind
import io.github.jpicklyk.mcptask.current.domain.model.ToolError
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
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    /** A tool that returns a fixed response envelope, for driving adapter envelope handling. */
    private fun envelopeTool(
        toolName: String,
        envelope: JsonObject
    ): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "Returns a fixed response envelope"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = envelope

            override fun userSummary(
                params: JsonElement,
                result: JsonElement,
                isError: Boolean
            ): String = if (isError) "$toolName failed" else "$toolName succeeded"
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

    // ──────────────────────────────────────────────
    // Structured error propagation via structuredContent
    // ──────────────────────────────────────────────

    @Test
    fun `gate-failure error carries structured code kind and missingNotes through structuredContent`(): Unit =
        runBlocking {
            // Shape mirrors an advance_item gate-failure error envelope:
            // structured ToolError fields plus gate details in the data payload.
            val envelope =
                ResponseUtil.createErrorResponse(
                    ToolError.permanent(
                        code = "gate_blocked",
                        message = "Cannot complete: 1 required note(s) missing"
                    ),
                    additionalData =
                        buildJsonObject {
                            put(
                                "missingNotes",
                                JsonArray(
                                    listOf(
                                        buildJsonObject {
                                            put("key", JsonPrimitive("acceptance-criteria"))
                                            put("phase", JsonPrimitive("review"))
                                        }
                                    )
                                )
                            )
                        }
                )
            adapter.registerToolWithServer(server, envelopeTool("advance_item", envelope), dummyContext)

            val result = client.callTool(name = "advance_item", arguments = emptyMap())

            assertEquals(true, result.isError)
            val structured =
                assertNotNull(result.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals("gate_blocked", error["code"]?.jsonPrimitive?.content)
            assertEquals("permanent", error["kind"]?.jsonPrimitive?.content)
            val missingNotes =
                assertNotNull(
                    structured["data"]?.jsonObject?.get("missingNotes")?.jsonArray,
                    "Gate-failure details (missingNotes) must ride along in structuredContent"
                )
            assertEquals("acceptance-criteria", missingNotes[0].jsonObject["key"]?.jsonPrimitive?.content)
        }

    @Test
    fun `claim contention error carries retryAfterMs through structuredContent`(): Unit =
        runBlocking {
            val contendedId = UUID.randomUUID()
            val envelope =
                ResponseUtil.createErrorResponse(
                    ToolError(
                        kind = ErrorKind.TRANSIENT,
                        code = "already_claimed",
                        message = "Item $contendedId is already claimed by another agent",
                        retryAfterMs = 1500L,
                        contendedItemId = contendedId
                    )
                )
            adapter.registerToolWithServer(server, envelopeTool("claim_item", envelope), dummyContext)

            val result = client.callTool(name = "claim_item", arguments = emptyMap())

            assertEquals(true, result.isError)
            val structured =
                assertNotNull(result.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals("already_claimed", error["code"]?.jsonPrimitive?.content)
            assertEquals("transient", error["kind"]?.jsonPrimitive?.content)
            assertEquals(1500L, error["retryAfterMs"]?.jsonPrimitive?.long)
            assertEquals(contendedId.toString(), error["contendedItemId"]?.jsonPrimitive?.content)
        }

    @Test
    fun `not_found error carries its code through structuredContent`(): Unit =
        runBlocking {
            // Real tool, real DB: an unresolvable parentId prefix produces a top-level
            // RESOURCE_NOT_FOUND error envelope.
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
                            "parentId" to "deadbeef", // valid hex prefix, matches nothing
                            "items" to listOf(mapOf("title" to "Orphan item"))
                        )
                )

            assertEquals(true, result.isError)
            val structured =
                assertNotNull(result.structuredContent, "not_found error must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals(ErrorCodes.RESOURCE_NOT_FOUND, error["code"]?.jsonPrimitive?.content)
            assertTrue(
                error["message"]?.jsonPrimitive?.content.orEmpty().contains("deadbeef"),
                "Error message should reference the unresolved prefix"
            )
        }

    @Test
    fun `success response structuredContent stays the raw data payload without envelope fields`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            val result =
                client.callTool(
                    name = "echo",
                    arguments = mapOf("message" to "structured")
                )

            assertTrue(result.isError != true, "Expected successful result")
            val structured =
                assertNotNull(result.structuredContent, "Success response should carry structuredContent")
            assertEquals("structured", structured["echoed"]?.jsonPrimitive?.content)
            assertTrue("error" !in structured.keys, "Success structuredContent must not contain an error object")
            assertTrue("success" !in structured.keys, "Success structuredContent must stay unwrapped (no envelope)")
        }

    // ──────────────────────────────────────────────
    // Response-size telemetry (t63): one INFO log line per tool call, naming the tool,
    // success/error, and a response char count — no argument or response bodies.
    // ──────────────────────────────────────────────

    /** Attaches a Logback ListAppender to McpToolAdapter's logger, runs [block], then detaches it. */
    private fun captureAdapterLogs(block: () -> Unit): List<ILoggingEvent> {
        val logbackLogger = LoggerFactory.getLogger(McpToolAdapter::class.java) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.INFO
        try {
            block()
            return listAppender.list.toList()
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    @Test
    fun `successful call logs one INFO response-size line naming the tool and a char count`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            var result: CallToolResult? = null
            val events =
                captureAdapterLogs {
                    result =
                        runBlocking {
                            client.callTool(name = "echo", arguments = mapOf("message" to "hello world"))
                        }
                }

            val infoEvents = events.filter { it.level == Level.INFO && it.formattedMessage.contains("tool call") }
            assertEquals(1, infoEvents.size, "Expected exactly one tool-call telemetry line, got: ${events.map { it.formattedMessage }}")
            val line = infoEvents[0].formattedMessage
            assertTrue(line.contains("echo"), "Telemetry line should name the tool: $line")
            assertTrue(line.contains("success=true"), "Telemetry line should report success=true: $line")

            // The logged char count must match text content + structuredContent, exactly what
            // McpToolAdapter actually returned to the client — no separate re-derivation.
            val textLen = result!!.content.filterIsInstance<TextContent>().sumOf { it.text.length }
            val structuredLen = result!!.structuredContent?.toString()?.length ?: 0
            val expectedChars = textLen + structuredLen
            assertTrue(
                line.contains("responseChars=$expectedChars"),
                "Telemetry line should report responseChars=$expectedChars (text=$textLen + structuredContent=$structuredLen): $line"
            )

            // No argument or response bodies leak into the log line.
            assertTrue(!line.contains("hello world"), "Telemetry must not log argument bodies: $line")
        }

    @Test
    fun `validation error call logs one INFO response-size line with success=false`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, echoTool(), dummyContext)

            val events =
                captureAdapterLogs {
                    runBlocking { client.callTool(name = "echo", arguments = emptyMap()) }
                }

            val infoEvents = events.filter { it.level == Level.INFO && it.formattedMessage.contains("tool call") }
            assertEquals(1, infoEvents.size, "Expected exactly one tool-call telemetry line, got: ${events.map { it.formattedMessage }}")
            val line = infoEvents[0].formattedMessage
            assertTrue(line.contains("echo"), "Telemetry line should name the tool: $line")
            assertTrue(line.contains("success=false"), "Telemetry line should report success=false: $line")
        }

    @Test
    fun `internal error call logs one INFO response-size line with success=false`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, failingTool(), dummyContext)

            val events =
                captureAdapterLogs {
                    runBlocking { client.callTool(name = "failing_tool", arguments = emptyMap()) }
                }

            val infoEvents = events.filter { it.level == Level.INFO && it.formattedMessage.contains("tool call") }
            assertEquals(1, infoEvents.size, "Expected exactly one tool-call telemetry line, got: ${events.map { it.formattedMessage }}")
            val line = infoEvents[0].formattedMessage
            assertTrue(line.contains("failing_tool"), "Telemetry line should name the tool: $line")
            assertTrue(line.contains("success=false"), "Telemetry line should report success=false: $line")
        }
}
