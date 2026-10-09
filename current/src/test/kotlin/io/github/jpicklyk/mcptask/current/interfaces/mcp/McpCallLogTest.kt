package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.current.application.tools.ToolDefinition
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests for item 8abb69e2 (P10): the MCP surface of the call log, driven through the real [McpToolAdapter]
 * over the in-memory client/server pair, the real production tool set and the real composition (events included).
 * Scenario ids follow the frozen test-plan (S2, S3, S5, S6, S7, S9, S13, S14, S17 plus probes).
 *
 * Oracles: plan section 3.8 and the task-scope "Column sources" table and acceptance A1-A3 (frozen at queue phase).
 * Expected byte counts and token estimates are recomputed here from the wire payload the client received (UTF-8 bytes
 * of the text content plus the compact structuredContent JSON), never read from the implementation.
 *
 * Narrowest reverts for the NEW-SURFACE scenarios (test-plan labels): S2 drop the adapter's submit; S3 no stamping in
 * EventRecorder; S7 no row on an error branch; S13 no recordRetry; S14 no markReplayed.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpCallLogTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private lateinit var rig: EventLogRig
    private lateinit var writer: CallLogWriter
    private lateinit var server: Server
    private lateinit var client: Client
    private val extraClients = mutableListOf<Pair<Client, Server>>()

    private val uuidPattern = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    private fun probeTool(
        toolName: String,
        body: suspend (JsonElement) -> JsonElement
    ): ToolDefinition =
        object : ToolDefinition {
            override val name = toolName
            override val description = "P10 call-log probe"
            override val parameterSchema = ToolSchema()
            override val category = ToolCategory.SYSTEM

            override suspend fun execute(
                params: JsonElement,
                context: ToolExecutionContext
            ): JsonElement = body(params)
        }

    private val extraTools: List<ToolDefinition> =
        listOf(
            probeTool("p10_echo") { params ->
                buildJsonObject {
                    put("status", "success")
                    put(
                        "message",
                        "echo " + ((params as? JsonObject)?.get("m")?.jsonPrimitive?.contentOrNull ?: "")
                    )
                }
            },
            probeTool("p10_boom") { throw IllegalStateException("kaboom") },
            probeTool("p10_cancel") { throw CancellationException("cancelled from inside") }
        )

    private suspend fun connect(
        adapter: McpToolAdapter,
        tools: Collection<ToolDefinition>
    ): Pair<Client, Server> {
        val srv = Server(serverInfo = Implementation(name = "p10-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        adapter.registerToolsWithServer(srv, tools, rig.ctx)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val cli =
            Client(
                clientInfo = Implementation(name = "p10-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        srv.createSession(serverTransport)
        cli.connect(clientTransport)
        return cli to srv
    }

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            rig = EventLogRig.build(db.db, dir, EventLogRig.GATED_CONFIG)
            writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
            val pair = connect(McpToolAdapter(callLog = writer), buildMcpTools() + extraTools)
            client = pair.first
            server = pair.second
        }

    @AfterEach
    fun tearDown(): Unit =
        runBlocking {
            closeInMemoryPair(client, server)
            extraClients.forEach { closeInMemoryPair(it.first, it.second) }
        }

    // ------------------------------------------------------------------ helpers

    private fun jstr(value: String): JsonElement = JsonPrimitive(value)

    private suspend fun call(
        tool: String,
        vararg args: Pair<String, JsonElement>
    ): CallToolResult =
        client.callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private fun reqIdOf(result: CallToolResult): String? =
        result.meta
            ?.get("reqId")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun rows(tool: String): List<Map<String, Any?>> = CallLogRows.forTool(db.jdbcUrl, tool)

    private fun rowOf(reqId: String?): Map<String, Any?> = CallLogRows.read(db.jdbcUrl).single { it["req_id"] == reqId }

    private suspend fun flush(): List<Map<String, Any?>> {
        writer.flushNow()
        return CallLogRows.read(db.jdbcUrl)
    }

    private fun utf8(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /** UTF-8 bytes of the text content plus the compact structuredContent JSON, excluding _meta and framing. */
    private fun responseBytes(result: CallToolResult): Long =
        result.content.filterIsInstance<TextContent>().sumOf { utf8(it.text) } +
            (result.structuredContent?.let { utf8(it.toString()) } ?: 0L)

    private fun ceilQuarter(bytes: Long): Long = (bytes + 3) / 4

    private fun itemsCreateArgs(
        vararg titles: String,
        actorId: String? = null,
        requestId: String? = null
    ): Array<Pair<String, JsonElement>> {
        val list =
            mutableListOf<Pair<String, JsonElement>>(
                "operation" to jstr("create"),
                "items" to buildJsonArray { titles.forEach { t -> add(buildJsonObject { put("title", t) }) } }
            )
        if (actorId != null) {
            list += "actor" to
                buildJsonObject {
                    put("id", actorId)
                    put("kind", "subagent")
                }
        }
        if (requestId != null) list += "requestId" to jstr(requestId)
        return list.toTypedArray()
    }

    private fun parseAt(text: String): Instant = LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC)

    // ------------------------------------------------------------------ S2 / A1 / A2

    @Test
    fun `S2 a read call carries a valid reqId in meta equal to its MDC reqId and its call_log key`() =
        runBlocking {
            client.listTools()
            assertEquals(0, flush().size, "tools/list and initialize write no call_log row")

            val capture = LogCapture().attach(McpToolAdapter::class.java.name)
            val args = arrayOf("operation" to jstr("overview"))
            val before = Instant.now()
            val result = call("query_items", *args)
            val after = Instant.now()
            capture.detach()

            val reqId = assertNotNull(reqIdOf(result), "_meta.reqId must be present: ${result.meta}")
            assertTrue(ReqId.isValid(reqId), "reqId must be 8 Crockford characters: $reqId")

            val mdc = capture.events.lastOrNull { it.mdc["tool"] == "query_items" }
            assertNotNull(mdc, "the adapter logs its tool-call line inside the call's MDC scope")
            assertEquals(reqId, mdc.mdc["reqId"], "MDC reqId equals _meta.reqId (A1)")
            assertTrue(
                uuidPattern.matcher(mdc.mdc["requestId"] ?: "").matches(),
                "the existing MDC requestId stays a UUID: ${mdc.mdc["requestId"]}"
            )

            val all = flush()
            assertEquals(1, all.size, "exactly one row for one call: $all")
            val row = all.single()
            assertEquals(reqId, row["req_id"])
            assertEquals("mcp", row["surface"])
            assertEquals("query_items", row["tool"])
            assertEquals("overview", row["operation"])
            assertEquals("ok", row["outcome"])
            assertNull(row["error_code"])
            assertEquals(1L, row["attempts"])
            assertEquals(0L, row["replayed"])
            assertEquals(mdc.mdc["sessionId"], row["session_id"], "session_id is the connection id the adapter logs")
            assertTrue(!(row["session_id"] as String?).isNullOrBlank())
            assertTrue((row["latency_ms"] as Long) >= 0)
            val at = parseAt(row["at"] as String)
            assertTrue(
                !at.isBefore(before.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)) && !at.isAfter(after),
                "at is the call start: $at not in [$before, $after]"
            )

            val expectedRequestBytes = utf8(JsonObject(mapOf(*args)).toString())
            assertEquals(expectedRequestBytes, row["request_bytes"])
            val expectedResponseBytes = responseBytes(result)
            assertEquals(expectedResponseBytes, row["response_bytes"])
            assertEquals(ceilQuarter(expectedResponseBytes), row["response_tokens_est"])
            assertEquals("bytes/4", row["token_method"])
        }

    @Test
    fun `S2 concurrent calls get distinct reqIds and one row each`() =
        runBlocking {
            val results = (1..80).map { async { call("query_items", "operation" to jstr("overview")) } }.awaitAll()
            val ids = results.map { assertNotNull(reqIdOf(it)) }
            assertEquals(80, ids.toSet().size, "every call has its own reqId")
            assertTrue(ids.all { ReqId.isValid(it) })
            val stored = flush()
            assertEquals(ids.toSet(), stored.map { it["req_id"] as String }.toSet(), "one stored row per call, keyed by its reqId")
            assertEquals(80, stored.size)
        }

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 every events row a call wrote shares the call's reqId and session, and a second call differs`() =
        runBlocking {
            val first = call("manage_items", *itemsCreateArgs("S3 one", "S3 two"))
            val second = call("manage_items", *itemsCreateArgs("S3 three"))
            val id1 = assertNotNull(reqIdOf(first))
            val id2 = assertNotNull(reqIdOf(second))
            assertNotEquals(id1, id2)

            val rows = flush()
            val session = rowOf(id1)["session_id"] as String?
            assertNotNull(session)
            val stamps = CallLogRows.eventStamps(db.jdbcUrl)
            assertEquals(
                setOf<String?>(id1, id2),
                stamps.map { it.first }.toSet(),
                "every events row carries one of the two calls' reqIds: $stamps"
            )
            assertEquals(2, stamps.count { it.first == id1 }, "two items created by call 1: $stamps")
            assertEquals(1, stamps.count { it.first == id2 }, "one item created by call 2: $stamps")
            assertTrue(stamps.all { it.second == session }, "events carry the connection's session id: $stamps vs $session")
            assertEquals(2, rows.size, "reads and writes alike: one call_log row per call")
        }

    // ------------------------------------------------------------------ S5

    @Test
    fun `S5 byte counts are UTF-8 bytes of non-ASCII payloads and tokens are their ceil over 4`() =
        runBlocking {
            val title = "é ✓ 😀"
            val args = itemsCreateArgs(title)
            val result = call("manage_items", *args)
            assertTrue(result.isError != true, "fixture: the create must succeed: ${result.content}")
            val row = flush().single { it["tool"] == "manage_items" }

            val argsJson = JsonObject(mapOf(*args)).toString()
            assertNotEquals(argsJson.length.toLong(), utf8(argsJson), "fixture: the title must make bytes differ from characters")
            assertEquals(utf8(argsJson), row["request_bytes"], "request_bytes counts UTF-8 bytes of the compact arguments")
            val expectedResponse = responseBytes(result)
            assertEquals(expectedResponse, row["response_bytes"])
            assertEquals(ceilQuarter(expectedResponse), row["response_tokens_est"])
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 query_items get records its shape, its target id and the item version`() =
        runBlocking {
            val item = rig.seed("S6 get")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            val result =
                call(
                    "query_items",
                    "operation" to jstr("get"),
                    "itemId" to jstr(item.id.toString()),
                    "includeAncestors" to JsonPrimitive(true)
                )
            assertTrue(result.isError != true, "${result.content}")
            val row = flush().single()
            val shape = Json.parseToJsonElement(row["request_shape"] as String).jsonObject
            assertEquals(setOf("includeAncestors"), shape.keys)
            assertEquals("true", shape["includeAncestors"]!!.jsonPrimitive.content)
            assertEquals(
                listOf(item.id.toString()),
                Json.parseToJsonElement(row["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            )
            val versions = Json.parseToJsonElement(row["target_versions"] as String).jsonObject
            assertEquals(setOf(item.id.toString()), versions.keys)
            assertEquals(
                version.toLong(),
                versions[item.id.toString()]!!.jsonPrimitive.content.toLong(),
                "WorkItem.version is the target version"
            )
        }

    @Test
    fun `S6 get_context in item mode records the item version`() =
        runBlocking {
            val item = rig.seed("S6 context")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            call("get_context", "itemId" to jstr(item.id.toString()))
            val row = flush().single()
            val versions = Json.parseToJsonElement(row["target_versions"] as String).jsonObject
            assertEquals(version.toLong(), versions[item.id.toString()]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun `S6 a batch of three notes with one failing records batch_size 3 and failed_count 1`() =
        runBlocking {
            val item = rig.seed("S6 notes")
            val notes =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("key", "n1")
                            put("role", "work")
                            put("body", "b1")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("key", "n2")
                            put("role", "work")
                            put("body", "b2")
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemId", UUID.randomUUID().toString())
                            put("key", "n3")
                            put("role", "work")
                            put("body", "b3")
                        }
                    )
                }
            val result = call("manage_notes", "operation" to jstr("upsert"), "notes" to notes)
            // The MCP structuredContent is the tool's data payload itself (summary.failed, else failed).
            val payload = result.structuredContent
            val failed =
                payload
                    ?.get("summary")
                    ?.jsonObject
                    ?.get("failed")
                    ?.jsonPrimitive
                    ?.intOrNull
                    ?: payload?.get("failed")?.jsonPrimitive?.intOrNull
            assertEquals(1, failed, "fixture: exactly one of the three notes fails per the tool's own summary: ${result.structuredContent}")
            val row = flush().single()
            assertEquals(3L, row["batch_size"])
            assertEquals(1L, row["failed_count"])
            val ids = Json.parseToJsonElement(row["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            assertTrue(item.id.toString() in ids, "elements of the top-level notes array contribute their itemId: $ids")
        }

    @Test
    fun `S6 get_next_item over three dependency-blocked queue items records result 0 and eligible 3`() =
        runBlocking {
            val blocker = rig.seed("S6 blocker", role = Role.WORK)
            val items = (1..3).map { rig.seed("S6 blocked $it") }
            items.forEach {
                rig.raw.dependencyRepository().create(
                    Dependency(fromItemId = blocker.id, toItemId = it.id, type = DependencyType.BLOCKS)
                )
            }
            val result = call("get_next_item")
            assertTrue(result.isError != true, "${result.content}")
            val row = flush().single()
            assertEquals(0L, row["result_count"], "every candidate is dependency-blocked")
            assertEquals(3L, row["eligible_count"], "the candidate list before the dependency-block filter")
        }

    @Test
    fun `S6 get_next_item with one unblocked item records result 1 of eligible 1`() =
        runBlocking {
            rig.seed("S6 free")
            call("get_next_item")
            val row = flush().single()
            assertEquals(1L, row["result_count"])
            assertEquals(1L, row["eligible_count"])
        }

    @Test
    fun `S6 claim_item never sets result or eligible counts`() =
        runBlocking {
            val item = rig.seed("S6 claimable")
            val claims = buildJsonArray { add(buildJsonObject { put("itemId", item.id.toString()) }) }
            call(
                "claim_item",
                "claims" to claims,
                "actor" to
                    buildJsonObject {
                        put("id", "claimer")
                        put("kind", "subagent")
                    },
                "requestId" to jstr(UUID.randomUUID().toString())
            )
            val row = flush().single { it["tool"] == "claim_item" }
            assertNull(row["result_count"])
            assertNull(row["eligible_count"])
            assertEquals(1L, row["batch_size"], "claims is a batch array of length 1")
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 a validation error still gets a reqId and one error row with the structured code`() =
        runBlocking {
            val result = call("manage_items") // required operation is missing
            assertEquals(true, result.isError)
            val reqId = assertNotNull(reqIdOf(result), "error results carry _meta.reqId too")
            val row = flush().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("error", row["outcome"])
            val code =
                result.structuredContent
                    ?.get("error")
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.content
            assertEquals("VALIDATION_ERROR", code, "fixture: ${result.structuredContent}")
            assertEquals(code, row["error_code"])
            assertEquals(responseBytes(result), row["response_bytes"])
        }

    @Test
    fun `S7 a thrown exception yields INTERNAL_ERROR with a reqId and exactly one row`() =
        runBlocking {
            val result = call("p10_boom")
            assertEquals(true, result.isError)
            val reqId = assertNotNull(reqIdOf(result))
            val row = flush().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("error", row["outcome"])
            assertEquals("INTERNAL_ERROR", row["error_code"])
            assertEquals("p10_boom", row["tool"])
            assertEquals(ceilQuarter(responseBytes(result)), row["response_tokens_est"])
        }

    @Test
    fun `S7 a gate-blocked advance records its row and its rejection event carries the same reqId`() =
        runBlocking {
            val item = rig.seed("S7 gated", tags = EventLogRig.GATED_TAG)
            val transitions =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", item.id.toString())
                            put("trigger", "start")
                        }
                    )
                }
            val result = call("advance_item", "transitions" to transitions)
            val reqId = assertNotNull(reqIdOf(result))
            val row = flush().single()
            assertEquals(reqId, row["req_id"])
            val expectedOutcome = if (result.isError == true) "error" else "ok"
            assertEquals(expectedOutcome, row["outcome"], "outcome is error iff the result is an error")
            val expectedCode =
                if (result.isError ==
                    true
                ) {
                    result.structuredContent
                        ?.get("error")
                        ?.jsonObject
                        ?.get("code")
                        ?.jsonPrimitive
                        ?.content
                } else {
                    null
                }
            assertEquals(expectedCode, row["error_code"])
            val rejected = CallLogRows.eventRows(db.jdbcUrl).filter { it.first == "transition.rejected" }
            assertEquals(1, rejected.size, "fixture: exactly one rejection row: $rejected")
            assertEquals(reqId, rejected.single().second, "the rejection event carries the call's reqId")
            assertEquals(
                listOf(item.id.toString()),
                Json.parseToJsonElement(row["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            )
        }

    @Test
    fun `S7 a cancelled call writes no row`() =
        runBlocking {
            runCatching {
                withTimeoutOrNull(2_000) { call("p10_cancel") }
            } // a closed connection or a timeout are both acceptable outcomes; only the table matters
            val rows = flush()
            assertTrue(rows.none { it["tool"] == "p10_cancel" }, "a cancelled call must leave no call_log row: $rows")
        }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a sink that throws never changes the tool result`() =
        runBlocking {
            val throwing = CallLogSink { throw IllegalStateException("sink down") }
            val withNone = connect(McpToolAdapter(callLog = CallLogSink.NONE), extraTools).also { extraClients += it }
            val withThrowing = connect(McpToolAdapter(callLog = throwing), extraTools).also { extraClients += it }
            val request =
                buildCallToolRequest {
                    name = "p10_echo"
                    arguments(buildJsonObject { put("m", "hello") })
                }
            val expected = withNone.first.callTool(request)
            val actual = withThrowing.first.callTool(request)
            assertNotEquals(true, actual.isError)
            assertEquals(expected.content, actual.content, "identical content with and without a failing sink")
            assertEquals(expected.structuredContent, actual.structuredContent)
            assertEquals(expected.isError, actual.isError)
            assertNotNull(reqIdOf(actual), "the result still carries its reqId")
        }

    // ------------------------------------------------------------------ S13 (attempts)

    @Test
    fun `S13 a BUSY retry inside a call increments attempts and a clean unit does not`() =
        runBlocking {
            val uow = db.unitOfWork()
            val telemetry = CallTelemetry(reqId = "k7f3q9ab", surface = CallLogRecord.SURFACE_MCP, sessionId = null)
            var attempt = 0
            withContext(telemetry) {
                uow.write("S13.retry") {
                    attempt++
                    if (attempt == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            }
            assertEquals(2, attempt, "fixture: the body ran twice")
            assertEquals(1, telemetry.retries, "one retry was observed")

            withContext(telemetry) { uow.write("S13.clean") { Outcome.Ok(Unit) } }
            assertEquals(1, telemetry.retries, "a unit without a retry adds nothing")

            var second = 0
            withContext(telemetry) {
                uow.write("S13.retry2") {
                    second++
                    if (second == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            }
            assertEquals(2, telemetry.retries, "retries accumulate across the units of one call (attempts = 1 + retries)")
        }

    @Test
    fun `S13 outside any call a BUSY retry is harmless`() =
        runBlocking {
            var attempt = 0
            val result =
                db.unitOfWork().write("S13.noCall") {
                    attempt++
                    if (attempt == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(2, attempt)
        }

    // ------------------------------------------------------------------ S14

    @Test
    fun `S14 the same requestId and actor create three times replays after the first`() =
        runBlocking {
            val requestId = UUID.randomUUID().toString()
            val args = itemsCreateArgs("S14 once", actorId = "idem-agent", requestId = requestId)
            val ids = (1..3).map { assertNotNull(reqIdOf(call("manage_items", *args))) }
            assertEquals(3, ids.toSet().size, "each call has its own reqId even when it replays")
            flush()
            val replayed = ids.map { rowOf(it)["replayed"] }
            assertEquals(listOf<Any?>(0L, 1L, 1L), replayed, "first execution is not a replay, the next two are")
            assertEquals(1, rig.rows().count { it.type == "item.created" }, "the replays wrote no second item")
        }

    @Test
    fun `S14 different requestIds are never replays`() =
        runBlocking {
            val a =
                assertNotNull(
                    reqIdOf(
                        call("manage_items", *itemsCreateArgs("S14 a", actorId = "idem-agent", requestId = UUID.randomUUID().toString()))
                    )
                )
            val b =
                assertNotNull(
                    reqIdOf(
                        call("manage_items", *itemsCreateArgs("S14 b", actorId = "idem-agent", requestId = UUID.randomUUID().toString()))
                    )
                )
            flush()
            assertEquals(0L, rowOf(a)["replayed"])
            assertEquals(0L, rowOf(b)["replayed"])
        }

    // ------------------------------------------------------------------ S17

    @Test
    fun `S17 an actor without proof is recorded as principal id kind and status`() =
        runBlocking {
            call("manage_items", *itemsCreateArgs("S17 actor", actorId = "a1"))
            val row = flush().single()
            assertEquals("a1", row["principal_id"])
            assertEquals("subagent", row["principal_kind"])
            val status = row["proof_status"] as String?
            val known = VerificationStatus.entries.map { it.toJsonString() }
            assertTrue(status in known, "proof_status is a VerificationStatus.toJsonString() value, got $status of $known")
        }

    @Test
    fun `S17 a call without an actor has three null principal columns`() =
        runBlocking {
            call("manage_items", *itemsCreateArgs("S17 anonymous"))
            val row = flush().single()
            assertNull(row["principal_id"])
            assertNull(row["principal_kind"])
            assertNull(row["proof_status"])
        }

    @Test
    fun `A2 every call records one row whose outcome matches its result, errors included`() =
        runBlocking {
            val results =
                listOf(
                    call("query_items", "operation" to jstr("overview")),
                    call("query_items", "operation" to jstr("not-an-operation")),
                    call("query_items")
                )
            assertTrue(results.any { it.isError == true }, "fixture: at least one of the calls must fail")
            assertTrue(results.any { it.isError != true }, "fixture: at least one of the calls must succeed")
            val rows = flush()
            assertEquals(3, rows.size, "reads and failures are recorded too")
            for (result in results) {
                val row = rowOf(reqIdOf(result))
                assertEquals(if (result.isError == true) "error" else "ok", row["outcome"])
                assertEquals(
                    if (result.isError ==
                        true
                    ) {
                        result.structuredContent
                            ?.get("error")
                            ?.jsonObject
                            ?.get("code")
                            ?.jsonPrimitive
                            ?.content
                    } else {
                        null
                    },
                    row["error_code"]
                )
            }
        }
}
