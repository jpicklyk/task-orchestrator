package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests for item 8abb69e2 (P10): the MCP surface of the call log, driven through the real
 * [McpOperationAdapter] over the in-memory client/server pair, the real production tool registry and the real composition
 * (events included). Q32 port: the removed 3.x adapter's probe tools are the 4.0 [FixtureTools] (its `get` select throws
 * or cancels on magic ids), and the 3.x advance_item/claim_item calls are the production `advance` tool. The 3.x
 * adapter's per-call MDC log line has no 4.0 counterpart (McpOperationAdapter logs none, a Q23 decision), so S2 asserts
 * the reqId on `_meta` and on the row only. Scenario ids follow the frozen test-plan (S2, S3, S5, S6, S7, S9, S13, S14,
 * S17 plus probes).
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

    private val fixtures = FixtureTools()

    /** A server with the production registry and the [FixtureTools] registry, both through one [McpOperationAdapter] on [sink]. */
    private suspend fun connect(sink: CallLogSink): Pair<Client, Server> {
        val srv = Server(serverInfo = Implementation(name = "p10-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        val adapter = McpOperationAdapter(buildToolDispatcher(rig.ctx), callLog = sink)
        adapter.register(srv, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        adapter.register(srv, fixtures.registry, ToolProfile.ORCHESTRATION)
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
            val pair = connect(writer)
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

    /** A `config` call's `ops` argument holding one `rules` list element for [rootId]. */
    private fun rulesList(rootId: String): Pair<String, JsonElement> =
        "ops" to
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("op", "rules")
                        put("rootId", rootId)
                    }
                )
            }

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

    /** One `transition` element of an `advance` call. */
    private fun transition(
        itemId: String,
        trigger: String
    ): JsonObject =
        buildJsonObject {
            put("op", "transition")
            put("itemId", itemId)
            put("trigger", trigger)
        }

    /** The `fx_read` `get` arguments for [id] (one of the [FixtureTools] magic ids steers the handler). */
    private fun fixtureGet(id: String): Array<Pair<String, JsonElement>> = arrayOf("select" to jstr("get"), "id" to jstr(id))

    /** The structured content without its per-call `meta` (the reqId differs between two calls). */
    private fun sansMeta(result: CallToolResult): JsonObject? =
        result.structuredContent?.let { JsonObject(it.filterKeys { k -> k != "meta" }) }

    private fun parseAt(text: String): Instant = LocalDateTime.parse(text.replace(' ', 'T')).toInstant(ZoneOffset.UTC)

    // ------------------------------------------------------------------ S2 / A1 / A2

    @Test
    fun `S2 a read call carries a valid reqId in meta equal to its call_log key`() =
        runBlocking {
            client.listTools()
            assertEquals(0, flush().size, "tools/list and initialize write no call_log row")

            val args = arrayOf("select" to jstr("get"), "itemId" to jstr(rig.seed("S2 read").id.toString()))
            val before = Instant.now()
            val result = call("items_read", *args)
            val after = Instant.now()

            val reqId = assertNotNull(reqIdOf(result), "_meta.reqId must be present: ${result.meta}")
            assertTrue(ReqId.isValid(reqId), "reqId must be 8 Crockford characters: $reqId")

            val all = flush()
            assertEquals(1, all.size, "exactly one row for one call: $all")
            val row = all.single()
            assertEquals(reqId, row["req_id"])
            assertEquals("mcp", row["surface"])
            assertEquals("items_read", row["tool"])
            assertEquals("get", row["operation"])
            assertEquals("ok", row["outcome"])
            assertNull(row["error_code"])
            assertEquals(1L, row["attempts"])
            assertEquals(0L, row["replayed"])
            assertTrue(!(row["session_id"] as String?).isNullOrBlank(), "session_id is the connection id")
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
            val seeded = jstr(rig.seed("S2 concurrent").id.toString())
            val results = (1..80).map { async { call("config", rulesList(seeded.jsonPrimitive.content)) } }.awaitAll()
            val ids = results.map { assertNotNull(reqIdOf(it)) }
            assertEquals(80, ids.toSet().size, "every call has its own reqId")
            assertTrue(ids.all { ReqId.isValid(it) })
            val stored = flush()
            assertEquals(ids.toSet(), stored.map { it["req_id"] as String }.toSet(), "one stored row per call, keyed by its reqId")
            assertEquals(80, stored.size)
        }

    // ------------------------------------------------------------------ S3

    // ------------------------------------------------------------------ S5

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 items_read context in item mode records the item version`() =
        runBlocking {
            val item = rig.seed("S6 context")
            val version =
                rig.raw
                    .workItemRepository()
                    .getById(item.id)!!
                    .version
            call("items_read", "select" to jstr("context"), "itemId" to jstr(item.id.toString()))
            val row = flush().single()
            val versions = Json.parseToJsonElement(row["target_versions"] as String).jsonObject
            assertEquals(version.toLong(), versions[item.id.toString()]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun `S6 a batch of three transitions with one failing records batch_size 3 and failed_count 1`() =
        runBlocking {
            val first = rig.seed("S6 batch a")
            val second = rig.seed("S6 batch b")
            val item = first
            val ops =
                buildJsonArray {
                    add(transition(first.id.toString(), "cancel"))
                    add(transition(second.id.toString(), "cancel"))
                    add(transition(UUID.randomUUID().toString(), "cancel"))
                }
            val result = call("advance", "ops" to ops)
            // The MCP structuredContent is the envelope body: data.summary.failed counts the failing element.
            val failed =
                result.structuredContent
                    ?.get("data")
                    ?.jsonObject
                    ?.get("summary")
                    ?.jsonObject
                    ?.get("failed")
                    ?.jsonPrimitive
                    ?.intOrNull
            assertEquals(
                1,
                failed,
                "fixture: exactly one of the three transitions fails per the envelope's own summary: ${result.structuredContent}"
            )
            val row = flush().single()
            assertEquals(3L, row["batch_size"])
            assertEquals(1L, row["failed_count"])
            val ids = Json.parseToJsonElement(row["target_ids"] as String).jsonArray.map { it.jsonPrimitive.content }
            assertTrue(item.id.toString() in ids, "elements of the ops array contribute their itemId: $ids")
        }

    @Test
    fun `S6 items_read next over three dependency-blocked queue items records result 0 and eligible 0`() =
        runBlocking {
            val blocker = rig.seed("S6 blocker", role = Role.WORK)
            val items = (1..3).map { rig.seed("S6 blocked $it") }
            items.forEach {
                rig.raw.dependencyRepository().create(
                    Dependency(fromItemId = blocker.id, toItemId = it.id, type = DependencyType.BLOCKS)
                )
            }
            val result = call("items_read", "select" to jstr("next"))
            assertTrue(result.isError != true, "${result.content}")
            val row = flush().single()
            assertEquals(0L, row["result_count"], "every candidate is dependency-blocked")
            assertEquals(0L, row["eligible_count"], "Q14: eligible_count is the exact eligible count, after the dependency-block filter")
        }

    @Test
    fun `S6 items_read next with one unblocked item records result 1 of eligible 1`() =
        runBlocking {
            rig.seed("S6 free")
            call("items_read", "select" to jstr("next"))
            val row = flush().single()
            assertEquals(1L, row["result_count"])
            assertEquals(1L, row["eligible_count"])
        }

    @Test
    fun `S6 an advance claim never sets result or eligible counts`() =
        runBlocking {
            val item = rig.seed("S6 claimable")
            val claims =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("op", "claim")
                            put("itemId", item.id.toString())
                        }
                    )
                }
            call(
                "advance",
                "ops" to claims,
                "actor" to
                    buildJsonObject {
                        put("id", "claimer")
                        put("kind", "subagent")
                    }
            )
            val row = flush().single { it["tool"] == "advance" }
            assertNull(row["result_count"])
            assertNull(row["eligible_count"])
            assertEquals(1L, row["batch_size"], "ops is a batch array of length 1")
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 a thrown exception yields internal with a reqId and exactly one row`() =
        runBlocking {
            val result = call("fx_read", *fixtureGet(FixtureTools.GET_SECRET))
            assertEquals(true, result.isError)
            val reqId = assertNotNull(reqIdOf(result))
            val row = flush().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("error", row["outcome"])
            assertEquals("internal", row["error_code"])
            assertEquals("fx_read", row["tool"])
            assertEquals(ceilQuarter(responseBytes(result)), row["response_tokens_est"])
        }

    @Test
    fun `S7 a gate-blocked advance records its row and its rejection event carries the same reqId`() =
        runBlocking {
            val item = rig.seed("S7 gated", tags = EventLogRig.GATED_TAG)
            val result = call("advance", "ops" to buildJsonArray { add(transition(item.id.toString(), "start")) })
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
            assertEquals(1L, row["failed_count"], "the gate-blocked element is the one failure")
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
                withTimeoutOrNull(2_000) { call("fx_read", *fixtureGet(FixtureTools.GET_CANCEL)) }
            } // a closed connection or a timeout are both acceptable outcomes; only the table matters
            val rows = flush()
            assertTrue(rows.isEmpty(), "a cancelled call must leave no call_log row: $rows")
        }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a sink that throws never changes the tool result`(): Unit =
        runBlocking {
            val throwing = CallLogSink { throw IllegalStateException("sink down") }
            val withNone = connect(CallLogSink.NONE).also { extraClients += it }
            val withThrowing = connect(throwing).also { extraClients += it }
            val request =
                buildCallToolRequest {
                    name = "fx_read"
                    arguments(JsonObject(mapOf(*fixtureGet(FixtureTools.GET_OK))))
                }
            val expected = withNone.first.callTool(request)
            val actual = withThrowing.first.callTool(request)
            assertNotEquals(true, actual.isError)
            assertEquals(expected.content, actual.content, "identical content with and without a failing sink")
            assertEquals(sansMeta(expected), sansMeta(actual), "identical body apart from the per-call reqId")
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

    // ------------------------------------------------------------------ S17

    @Test
    fun `A2 every call records one row whose outcome matches its result, errors included`() =
        runBlocking {
            val results =
                listOf(
                    call("config", rulesList(rig.seed("A2 read").id.toString())),
                    call("config", "ops" to buildJsonArray { add(buildJsonObject { put("op", "not-an-operation") }) }),
                    call("config")
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
