package io.github.jpicklyk.mcptask.current.interfaces.mcp

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `4e110d22`.
 * All scenarios are EXISTING-SURFACE per the test-plan: a plain revert of `McpToolAdapter.kt`
 * is expected to turn S1-S4, S7, S8 red (missing `structuredContent` / "Internal error" text /
 * ERROR-level logging) while S5 stays green (unrelated `RuntimeException` path untouched).
 *
 * Harness mirrors [McpToolAdapterIntegrationTest] / [McpToolAdapterConfigUnavailableTest]: a real
 * [Server]/[Client] pair over [ChannelTransport.createLinkedPair], driven via [Client.callTool] —
 * never `tool.execute()`/`validateParams()` directly — so the adapter's catch handling is what is
 * actually exercised.
 *
 * Oracles: [U] plans/roadmap-now-2026-09.md #9 (user decision), [W] the `diagnosis` note's
 * "Target wire shape" section, [E] `ErrorCodes.VALIDATION_ERROR` / `ToolError.permanent` kind
 * `"permanent"` plus `current/docs/api-reference.md` § Error Envelope's `VALIDATION_ERROR
 * (permanent, adapter-level)` entry: `text` is `"Validation error in '<tool>': <exception
 * message>"` and `structuredContent.error` is exactly `{code: "VALIDATION_ERROR", message: <same
 * text>, kind: "permanent"}` — no `retryAfterMs`, `contendedItemId`, or `details` — applying
 * uniformly regardless of which phase (`validateParams` vs `execute`) threw.
 */
class McpToolAdapterValidationEnvelopeTest {
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
                            capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
                        )
                )
            adapter = McpToolAdapter()
            val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
            client =
                Client(
                    clientInfo = Implementation(name = "test-client", version = "1.0.0"),
                    options = ClientOptions(capabilities = ClientCapabilities())
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

    private val dummyContext =
        ToolExecutionContext(
            repositoryProvider =
                DefaultRepositoryProvider(
                    DatabaseManager(
                        Database.connect(
                            "jdbc:h2:mem:mcpadapter_validation_${System.nanoTime()};DB_CLOSE_DELAY=-1",
                            driver = "org.h2.Driver"
                        )
                    ).also { DirectDatabaseSchemaManager().updateSchema() }
                )
        )

    // ──────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────

    /** A tool whose `validateParams` always throws [ToolValidationException] with [message]. */
    private fun validateParamsThrowingTool(message: String): ToolDefinition =
        object : ToolDefinition {
            override val name = "validate_throws"
            override val description = "Always throws from validateParams"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override fun validateParams(params: JsonElement): Unit = throw ToolValidationException(message)

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw IllegalStateException("execute() must not run when validateParams throws")
        }

    /**
     * A tool with a no-op `validateParams` (the interface default) whose `execute` throws
     * [ToolValidationException] with [message] — drives the diagnosis's repro #2 (a
     * type-mismatch caught deep in `execute()`, e.g. `BaseToolDefinition.optionalBoolean`).
     */
    private fun executeThrowingValidationTool(message: String): ToolDefinition =
        object : ToolDefinition {
            override val name = "execute_throws_validation"
            override val description = "Always throws ToolValidationException from execute"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw ToolValidationException(message)
        }

    /** A tool whose `execute` throws a generic [RuntimeException] — S5, the unaffected path. */
    private fun executeThrowingRuntimeTool(message: String): ToolDefinition =
        object : ToolDefinition {
            override val name = "execute_throws_runtime"
            override val description = "Always throws a generic RuntimeException from execute"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = throw RuntimeException(message)
        }

    /** Attaches a Logback ListAppender to McpToolAdapter's logger, runs [block], then detaches it. */
    private fun captureAdapterLogs(block: () -> Unit): List<ILoggingEvent> {
        val logbackLogger = LoggerFactory.getLogger(McpToolAdapter::class.java) as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.ALL
        try {
            block()
            return listAppender.list.toList()
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    // ──────────────────────────────────────────────
    // Happy path
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - validateParams throwing ToolValidationException maps to a VALIDATION_ERROR envelope`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(
                server,
                validateParamsThrowingTool("Missing required parameter: message"),
                dummyContext
            )

            val result = client.callTool(name = "validate_throws", arguments = emptyMap())

            assertEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("permanent", error["kind"]?.jsonPrimitive?.content)

            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty(), "Expected at least one TextContent")
            assertEquals(
                "Validation error in 'validate_throws': Missing required parameter: message",
                textContent[0].text
            )
        }

    @Test
    fun `S2 - execute throwing ToolValidationException maps to the same VALIDATION_ERROR envelope, not Internal error`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(
                server,
                executeThrowingValidationTool("Parameter flag must be a boolean"),
                dummyContext
            )

            val result = client.callTool(name = "execute_throws_validation", arguments = emptyMap())

            assertEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("permanent", error["kind"]?.jsonPrimitive?.content)

            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty(), "Expected at least one TextContent")
            val text = textContent[0].text
            assertTrue(
                text.startsWith("Validation error in 'execute_throws_validation':"),
                "Expected a validation-error prefix, got: $text"
            )
            assertFalse(text.contains("Internal error"), "execute-phase validation failures must not read as internal errors: $text")
        }

    @Test
    fun `S3 - structuredContent error message equals the text summary exactly, in both phases`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, validateParamsThrowingTool("bad params"), dummyContext)
            adapter.registerToolWithServer(server, executeThrowingValidationTool("bad execute params"), dummyContext)

            val validateResult = client.callTool(name = "validate_throws", arguments = emptyMap())
            val executeResult = client.callTool(name = "execute_throws_validation", arguments = emptyMap())

            val validateText = validateResult.content.filterIsInstance<TextContent>()[0].text
            val validateMessage =
                validateResult.structuredContent!!["error"]!!
                    .jsonObject["message"]!!
                    .jsonPrimitive.content
            assertEquals(validateText, validateMessage, "validateParams phase: structuredContent.error.message must equal the text summary")

            val executeText = executeResult.content.filterIsInstance<TextContent>()[0].text
            val executeMessage =
                executeResult.structuredContent!!["error"]!!
                    .jsonObject["message"]!!
                    .jsonPrimitive.content
            assertEquals(executeText, executeMessage, "execute phase: structuredContent.error.message must equal the text summary")
        }

    @Test
    fun `S4 - structuredContent has only an error object, and error has only code, message and kind`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, validateParamsThrowingTool("bad params"), dummyContext)
            adapter.registerToolWithServer(server, executeThrowingValidationTool("bad execute params"), dummyContext)

            val validateResult = client.callTool(name = "validate_throws", arguments = emptyMap())
            val executeResult = client.callTool(name = "execute_throws_validation", arguments = emptyMap())

            val validateStructured = assertNotNull(validateResult.structuredContent)
            assertEquals(setOf("error"), validateStructured.keys, "validateParams phase: no `data` field alongside `error`")
            val validateError = validateStructured["error"]!!.jsonObject
            assertEquals(setOf("code", "message", "kind"), validateError.keys)

            val executeStructured = assertNotNull(executeResult.structuredContent)
            assertEquals(setOf("error"), executeStructured.keys, "execute phase: no `data` field alongside `error`")
            val executeError = executeStructured["error"]!!.jsonObject
            assertEquals(setOf("code", "message", "kind"), executeError.keys)
        }

    // ──────────────────────────────────────────────
    // Failure / regression — the unrelated path must stay untouched
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - a generic RuntimeException from execute stays an Internal error with no structuredContent`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, executeThrowingRuntimeTool("Intentional"), dummyContext)

            val result = client.callTool(name = "execute_throws_runtime", arguments = emptyMap())

            assertEquals(true, result.isError)
            val textContent = result.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty())
            assertTrue(textContent[0].text.contains("Internal error"), "Non-validation exceptions must keep the Internal error text")
            assertNull(
                result.structuredContent,
                "A generic RuntimeException must not gain structuredContent — out of scope per diagnosis non-goals"
            )
        }

    // ──────────────────────────────────────────────
    // Logging — WARN not ERROR, exactly one INFO telemetry line, success=false
    // ──────────────────────────────────────────────

    @Test
    fun `S7 - execute-phase ToolValidationException logs WARN not ERROR, plus one INFO line with success=false`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(
                server,
                executeThrowingValidationTool("Parameter includeAncestors must be a boolean"),
                dummyContext
            )

            val events =
                captureAdapterLogs {
                    runBlocking { client.callTool(name = "execute_throws_validation", arguments = emptyMap()) }
                }

            val errorEvents = events.filter { it.level == Level.ERROR }
            assertTrue(
                errorEvents.isEmpty(),
                "Expected zero ERROR events for a validation failure, got: ${errorEvents.map { it.formattedMessage }}"
            )

            val warnEvents = events.filter { it.level == Level.WARN }
            assertTrue(warnEvents.isNotEmpty(), "Expected at least one WARN event for a validation failure")

            val infoEvents = events.filter { it.level == Level.INFO && it.formattedMessage.contains("tool call") }
            assertEquals(1, infoEvents.size, "Expected exactly one tool-call telemetry line, got: ${events.map { it.formattedMessage }}")
            val line = infoEvents[0].formattedMessage
            assertTrue(line.contains("execute_throws_validation"), "Telemetry line should name the tool: $line")
            assertTrue(line.contains("success=false"), "Telemetry line should report success=false: $line")
        }

    // ──────────────────────────────────────────────
    // Edge — a real tool, real DB, execute-phase type-mismatch (diagnosis repro #2)
    // ──────────────────────────────────────────────

    @Test
    fun `S8 - real query_items with a non-boolean includeAncestors maps to VALIDATION_ERROR naming the parameter`(): Unit =
        runBlocking {
            adapter.registerToolWithServer(server, ManageItemsTool(), dummyContext)
            adapter.registerToolWithServer(server, QueryItemsTool(), dummyContext)

            val createResult =
                client.callTool(
                    name = "manage_items",
                    arguments =
                        mapOf(
                            "operation" to "create",
                            "items" to listOf(mapOf("title" to "S8 item"))
                        )
                )
            assertTrue(createResult.isError != true, "Setup precondition: item creation must succeed, got: ${createResult.content}")
            val createdItems =
                assertNotNull(createResult.structuredContent, "Success response must carry structuredContent")["items"]!!.jsonArray
            val itemId = createdItems[0].jsonObject["id"]!!.jsonPrimitive.content

            val queryResult =
                client.callTool(
                    name = "query_items",
                    arguments =
                        mapOf(
                            "operation" to "get",
                            "itemId" to itemId,
                            "includeAncestors" to 42
                        )
                )

            assertEquals(true, queryResult.isError)
            val structured = assertNotNull(queryResult.structuredContent, "Error response must carry structuredContent")
            val error = assertNotNull(structured["error"]?.jsonObject, "structuredContent must contain the error object")
            assertEquals(ErrorCodes.VALIDATION_ERROR, error["code"]?.jsonPrimitive?.content)
            assertEquals("permanent", error["kind"]?.jsonPrimitive?.content)
            assertTrue(
                error["message"]
                    ?.jsonPrimitive
                    ?.content
                    .orEmpty()
                    .contains("includeAncestors"),
                "Error message should name the offending parameter: ${error["message"]}"
            )

            val textContent = queryResult.content.filterIsInstance<TextContent>()
            assertTrue(textContent.isNotEmpty())
            assertTrue(
                textContent[0].text.startsWith("Validation error in 'query_items':"),
                "Expected a validation-error prefix, not an Internal error, got: ${textContent[0].text}"
            )
        }
}
