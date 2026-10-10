package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.service.NoOpActorVerifier
import io.github.jpicklyk.mcptask.current.application.telemetry.CallLogFields
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.test.CollectingSink
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.FixtureTools
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 713c5c61) for [McpOperationAdapter], scenarios S12, S13 and S14 of the frozen
 * test-plan (NEW-SURFACE), driven through a real SDK [Server]/[Client] pair over `ChannelTransport.createLinkedPair()`.
 *
 * Oracles: the task-scope Contract for the adapter (registers `name`, rendered description, input schema and annotations;
 * `structuredContent` = the envelope body, `isError` = `!ok`, one `TextContent` = "ok" or "<code>: <message>", `_meta.reqId` =
 * `meta.reqId`; one `call_log` record per non-cancelled call with `tool`, `operation` (D8), `error_code` = the body's error
 * code, `batch_size`, `failed_count` = `data.summary.failed`, `result_count` = size of `data.items` when present,
 * `request_shape` = `CallLogFields.requestShapeJson(arguments)`), MCP tools spec (annotations in `tools/list`), D9 (an escaping
 * exception is the fixed internal envelope; cancellation produces no record) and the response byte rule of the existing P10
 * `McpCallLogTest` (UTF-8 bytes of the text content plus the compact structuredContent JSON).
 *
 * Vacuity: "no record" for a cancelled call is paired with a control call through the same sink that DOES record.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpOperationAdapterTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fx = FixtureTools()
    private val sink = CollectingSink()
    private val pairs = mutableListOf<Pair<Client, Server>>()

    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private fun adapter(): McpOperationAdapter =
        McpOperationAdapter(
            ToolDispatcher(RequestDecoder(), NoOpActorVerifier, BatchRunner(env.unitOfWork, env.idempotency), env),
            callLog = sink
        )

    private suspend fun connect(profile: ToolProfile): Client {
        val server =
            Server(serverInfo = Implementation(name = "op-adapter-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        adapter().register(server, fx.registry, profile)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "op-adapter-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        pairs += client to server
        return client
    }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            pairs.forEach { (client, server) -> closeInMemoryPair(client, server) }
        }

    private fun args(text: String): JsonObject =
        kotlinx.serialization.json.Json
            .parseToJsonElement(text)
            .jsonObject

    private suspend fun Client.run(
        tool: String,
        text: String
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(args(text))
            }
        )

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().single().text

    private fun CallToolResult.reqIdMeta(): String? = meta?.get("reqId")?.jsonPrimitive?.contentOrNull

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun utf8(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    // ------------------------------------------------------------------ S12 tools/list

    @Test
    fun `S12 orchestration lists every visible tool with the rendered description, schema and annotations`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val tools = client.listTools().tools
            assertEquals(listOf("fx_admin", "fx_peek", "fx_read", "fx_write"), tools.map { it.name }.sorted())
            for (tool in tools) {
                val spec = fx.registry[tool.name]!!
                assertEquals(ToolSchemaRenderer.description(spec), tool.description, tool.name)
                assertEquals<JsonElement?>(
                    ToolSchemaRenderer.inputSchema(spec, ToolProfile.ORCHESTRATION)!!.getValue("properties"),
                    tool.inputSchema.properties,
                    "${tool.name}: properties"
                )
                assertEquals(null, tool.inputSchema.required, "${tool.name}: no top-level required")
            }
            val read = tools.single { it.name == "fx_read" }.annotations
            assertEquals(true, read?.readOnlyHint)
            assertEquals(false, read?.destructiveHint)
            assertEquals(true, read?.idempotentHint)
            assertEquals(false, read?.openWorldHint)
            val write = tools.single { it.name == "fx_write" }.annotations
            assertEquals(false, write?.readOnlyHint)
            assertEquals(true, write?.destructiveHint, "purge is visible in orchestration")
            assertEquals(false, write?.idempotentHint)
            assertEquals(false, write?.openWorldHint)
        }

    @Test
    fun `S12 knowledge lists only tools with a visible operation and hides orchestration-only fields`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.KNOWLEDGE)
            val tools = client.listTools().tools
            assertEquals(listOf("fx_read", "fx_write"), tools.map { it.name }.sorted())
            val write = tools.single { it.name == "fx_write" }
            assertEquals(false, write.annotations?.destructiveHint, "purge is hidden, so the tool is not destructive")
            assertFalse("purge" in write.inputSchema.toString())
            val read = tools.single { it.name == "fx_read" }
            assertFalse("audit" in read.inputSchema.toString())
            assertFalse("scope" in read.inputSchema.properties!!.keys)
        }

    @Test
    fun `S12 toolFor returns the registered shape and null for an invisible tool`() {
        val spec = fx.readTool
        val tool = assertNotNull(McpOperationAdapter.toolFor(spec, ToolProfile.KNOWLEDGE))
        assertEquals("fx_read", tool.name)
        assertEquals(ToolSchemaRenderer.description(spec), tool.description)
        assertEquals<JsonElement?>(
            ToolSchemaRenderer.inputSchema(spec, ToolProfile.KNOWLEDGE)!!.getValue("properties"),
            tool.inputSchema.properties
        )
        assertEquals(true, tool.annotations?.readOnlyHint)
        assertNull(McpOperationAdapter.toolFor(fx.adminTool, ToolProfile.KNOWLEDGE))
        assertNull(McpOperationAdapter.toolFor(fx.peekTool, ToolProfile.KNOWLEDGE))
        assertNotNull(McpOperationAdapter.toolFor(fx.adminTool, ToolProfile.ORCHESTRATION))
    }

    // ------------------------------------------------------------------ S12 results

    @Test
    fun `S12 a successful call carries the envelope as structured content, ok text and the reqId in meta`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result = client.run("fx_read", """{"select":"get","id":"aaaaaaaa"}""")

            assertNotEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent)
            val reqId = assertNotNull(result.reqIdMeta(), "_meta.reqId")
            assertEquals(8, reqId.length)
            assertEquals(reqId, structured.getValue("meta").jsonObject.str("reqId"))
            assertEquals("""{"ok":true,"data":"got:aaaaaaaa:1","meta":{"reqId":"$reqId"}}""", structured.toString())
            assertEquals("ok", result.text())
            assertEquals(1, result.content.size)
        }

    @Test
    fun `S12 an error call sets isError and the text is code colon message`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result = client.run("fx_read", """{"select":"get","id":"bbbbbbbb"}""")

            assertEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals(
                false,
                structured
                    .getValue("ok")
                    .jsonPrimitive.content
                    .toBoolean()
            )
            val error = structured.getValue("error").jsonObject
            assertEquals("not_found", error.str("code"))
            assertEquals("not_found: ${error.str("message")}", result.text())
            assertEquals(result.reqIdMeta(), structured.getValue("meta").jsonObject.str("reqId"))
        }

    @Test
    fun `S12 a validation error from the dispatcher is an isError result with the same shape`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result = client.run("fx_read", """{"select":"nope"}""")
            assertEquals(true, result.isError)
            val error = assertNotNull(result.structuredContent).getValue("error").jsonObject
            assertEquals("invalid_request", error.str("code"))
            assertTrue(result.text().startsWith("invalid_request: "), result.text())
        }

    @Test
    fun `S12 a partial failure is an error result and its structured content keeps the per-element results`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result =
                client.run(
                    "fx_write",
                    """{"ops":[{"op":"create","title":"A"},{"op":"create","title":"${FixtureTools.FAIL_TITLE}"}]}"""
                )
            assertEquals(true, result.isError)
            val structured = assertNotNull(result.structuredContent)
            assertEquals("partial_failure", structured.getValue("error").jsonObject.str("code"))
            assertEquals(
                2,
                structured
                    .getValue("data")
                    .jsonObject
                    .getValue("results")
                    .jsonArray.size
            )
            assertTrue(result.text().startsWith("partial_failure: "), result.text())
        }

    @Test
    fun `S12 each call gets its own reqId`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val ids = (1..5).map { client.run("fx_read", """{"select":"get","id":"aaaaaaaa"}""").reqIdMeta() }
            assertEquals(5, ids.toSet().size, "five calls, five distinct ids: $ids")
        }

    // ------------------------------------------------------------------ S13

    @Test
    fun `S13 an exception from a handler reaches the client only as the fixed internal envelope`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_SECRET}"}""")

            assertEquals(true, result.isError)
            val reqId = assertNotNull(result.reqIdMeta())
            assertEquals(
                """{"ok":false,"error":{"code":"internal","kind":"transient","message":"Internal server error"},"meta":{"reqId":"$reqId"}}""",
                assertNotNull(result.structuredContent).toString()
            )
            assertEquals("internal: Internal server error", result.text())
            assertFalse(FixtureTools.SECRET in result.toString(), "no part of the result may carry the exception text")
        }

    @Test
    fun `S13 a translated busy fault is unavailable through the adapter`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_BUSY}"}""")
            assertEquals(true, result.isError)
            assertEquals("unavailable", assertNotNull(result.structuredContent).getValue("error").jsonObject.str("code"))
            assertTrue(result.text().startsWith("unavailable: "), result.text())
        }

    // ------------------------------------------------------------------ S14 call_log

    private fun only(): CallLogRecord = sink.records.single()

    @Test
    fun `S14 a successful select writes one record with tool, operation and no error code`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val text = """{"select":"get","id":"aaaaaaaa"}"""
            val result = client.run("fx_read", text)

            val record = only()
            assertEquals(result.reqIdMeta(), record.reqId)
            assertEquals("fx_read", record.tool)
            assertEquals("get", record.operation)
            assertNull(record.errorCode)
            assertEquals(CallLogRecord.OUTCOME_OK, record.outcome)
            assertEquals(CallLogRecord.SURFACE_MCP, record.surface)
            assertNull(record.batchSize)
            assertNull(record.failedCount)
            assertNull(record.resultCount, "a single-value read has no items")
            assertEquals(CallLogFields.requestShapeJson(args(text)), record.requestShape)
            val expectedBytes = utf8(result.text()) + utf8(assertNotNull(result.structuredContent).toString())
            assertEquals(expectedBytes, record.responseBytes?.toLong(), "UTF-8 text bytes plus compact structuredContent bytes")
        }

    @Test
    fun `S14 a paged read records the item count as result_count`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_read", """{"select":"list"}""")
            val record = only()
            assertEquals("list", record.operation)
            assertEquals(2, record.resultCount, "data.items holds two entries (the page total is 5)")
        }

    @Test
    fun `S14 an error call records the wire error code and the error outcome`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_read", """{"select":"get","id":"bbbbbbbb"}""")
            val record = only()
            assertEquals("not_found", record.errorCode)
            assertEquals(CallLogRecord.OUTCOME_ERROR, record.outcome)
            assertEquals("get", record.operation)
        }

    @Test
    fun `S14 an invalid selector records a null operation and the invalid_request code`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_read", """{"select":"nope"}""")
            val record = only()
            assertNull(record.operation)
            assertEquals("invalid_request", record.errorCode)
        }

    @Test
    fun `S14 an ops call records the single op, the batch size and the failed count`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run(
                "fx_write",
                """{"ops":[{"op":"create","title":"A"},{"op":"create","title":"${FixtureTools.FAIL_TITLE}"},{"op":"create","title":"C"}]}"""
            )
            val record = only()
            assertEquals("fx_write", record.tool)
            assertEquals("create", record.operation)
            assertEquals(3, record.batchSize)
            assertEquals(1, record.failedCount, "data.summary.failed")
            assertEquals("partial_failure", record.errorCode)
        }

    @Test
    fun `S14 an all-ok ops call has failed_count zero and no error code`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_write", """{"ops":[{"op":"create","title":"A"},{"op":"create","title":"B"}]}""")
            val record = only()
            assertEquals(2, record.batchSize)
            assertEquals(0, record.failedCount)
            assertNull(record.errorCode)
            assertEquals(CallLogRecord.OUTCOME_OK, record.outcome)
        }

    @Test
    fun `S14 mixed ops are operation mixed`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_write", """{"ops":[{"op":"create","title":"A"},{"op":"rename","id":"aaaaaaaa"}]}""")
            assertEquals("mixed", only().operation)
        }

    @Test
    fun `S14 describe is recorded as operation describe with the request shape`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val text = """{"describe":true}"""
            client.run("fx_read", text)
            val record = only()
            assertEquals("describe", record.operation)
            assertEquals(CallLogFields.requestShapeJson(args(text)), record.requestShape)
            assertTrue("describe" in record.requestShape.orEmpty(), "the shape names the describe flag: ${record.requestShape}")
            assertNull(record.errorCode)
        }

    @Test
    fun `S14 a cancelled call writes no record, while the control call through the same sink does`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            withTimeoutOrNull(3_000) {
                runCatching { client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_CANCEL}"}""") }
            }
            assertTrue(sink.records.isEmpty(), "a cancelled call must leave no record: ${sink.records}")

            // a cancelled request ends the in-memory session, so the control call uses a fresh client on the same sink
            val control = connect(ToolProfile.ORCHESTRATION).run("fx_read", """{"select":"get","id":"aaaaaaaa"}""")
            assertEquals(listOf(control.reqIdMeta()), sink.records.map { it.reqId }, "control: the sink records a normal call")
        }

    @Test
    fun `S14 an exception from a handler is recorded as internal`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_SECRET}"}""")
            val record = only()
            assertEquals("internal", record.errorCode)
            assertEquals("get", record.operation)
            assertFalse(FixtureTools.SECRET in record.toString(), "the record must not carry the exception text")
        }

    @Test
    fun `S14 the request shape of an ops call keeps key names and no values`(): Unit =
        runBlocking {
            val client = connect(ToolProfile.ORCHESTRATION)
            val text = """{"ops":[{"op":"create","title":"top-secret-title"}]}"""
            client.run("fx_write", text)
            val shape = only().requestShape.orEmpty()
            assertEquals(CallLogFields.requestShapeJson(args(text)), only().requestShape)
        }
}
