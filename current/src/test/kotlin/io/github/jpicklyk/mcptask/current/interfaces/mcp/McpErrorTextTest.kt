package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.BatchRunner
import io.github.jpicklyk.mcptask.current.application.api.RequestDecoder
import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.knowledge
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 14dd0215) for the text content of an MCP error result produced by
 * [McpOperationAdapter], scenarios S1 to S8 of the frozen test-plan, driven through a real SDK [Server]/[Client] pair.
 *
 * Frozen text format (amendment A1): an `isError` result keeps ONE text content equal to the summary line
 * (`<code>: <message>`, unchanged) + a newline + one compact JSON object that is always the last line. A non-batch or atomic
 * error carries the envelope body; a `partial_failure` carries the body with `data.results` reduced to the failing entries
 * (each `{"index":i}` plus the element verbatim, ascending, at most the first 10) and `data.summary` unchanged. Success text
 * stays exactly "ok". Oracles: api-reference.md "Response envelope", the diagnosis fix (1) and amendment A1.
 *
 * Notation: L1 = text before the last newline, T = JSON parsed from the text after the last newline, S = structuredContent.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class McpErrorTextTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val fx = FixtureTools()
    private val sink = CollectingSink()
    private val pairs = mutableListOf<Pair<Client, Server>>()

    private val env: ToolExecutionContext by lazy {
        ToolExecutionContext(repositoryProvider = db.repositoryProvider(), unitOfWork = db.unitOfWork())
    }

    private val localTool =
        SelectToolSpec(
            "lc_read",
            "Local read tool whose errors carry messages with a newline and non-ASCII text.",
            listOf(
                SelectSpec.Single<FixtureTools.GetReq, String>(
                    name = "get",
                    doc = "Read one thing by id.",
                    profiles = knowledge(),
                    request = serializer<FixtureTools.GetReq>(),
                    errors = setOf(ErrorCode.NOT_FOUND),
                    handler = { _, _, req ->
                        val message =
                            when (req.id.raw) {
                                NEWLINE_ID -> "first line\nsecond line"
                                UNICODE_ID -> UNICODE_MESSAGE
                                else -> "plain"
                            }
                        Outcome.Err(
                            DomainError(
                                ErrorCode.NOT_FOUND,
                                message,
                                ErrorFixtures.detail(ErrorCode.NOT_FOUND),
                                ErrorFixtures.fixArgs(ErrorCode.NOT_FOUND)
                            )
                        )
                    },
                    data = { JsonPrimitive(it) }
                )
            )
        )

    private fun adapter(): McpOperationAdapter =
        McpOperationAdapter(
            ToolDispatcher(RequestDecoder(), BatchRunner(env.unitOfWork, env.idempotency), env),
            callLog = sink
        )

    private suspend fun connect(registry: ToolRegistry = fx.registry): Client {
        val server =
            Server(serverInfo = Implementation(name = "error-text-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        adapter().register(server, registry, ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "error-text-client", version = "1.0.0"),
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

    private suspend fun Client.run(
        tool: String,
        text: String
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(Json.parseToJsonElement(text).jsonObject)
            }
        )

    private fun CallToolResult.text(): String = content.filterIsInstance<TextContent>().single().text

    /** L1: everything before the last newline. */
    private fun CallToolResult.firstLine(): String = text().substringBeforeLast('\n')

    /** T: the compact JSON object on the last line. */
    private fun CallToolResult.tail(): JsonObject = Json.parseToJsonElement(text().substringAfterLast('\n')).jsonObject

    private fun CallToolResult.structured(): JsonObject = assertNotNull(structuredContent, "structuredContent")

    private fun JsonObject.err(): JsonObject = getValue("error").jsonObject

    private fun JsonObject.str(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonObject.results(): JsonArray = getValue("data").jsonObject.getValue("results").jsonArray

    private fun utf8(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    private fun titles(): List<String> =
        DriverManager.getConnection(db.jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM work_items ORDER BY title").use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

    private fun create(
        title: String,
        extra: String = ""
    ) = """{"op":"create","title":"$title"$extra}"""

    private fun ops(
        vararg elements: String,
        top: String = ""
    ) = """{"ops":[${elements.joinToString(",")}]$top}"""

    private fun failingIndices(result: CallToolResult): List<Int> =
        result.tail().results().map {
            it.jsonObject
                .getValue("index")
                .jsonPrimitive.int
        }

    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 a successful call and a describe call keep the exact text ok in one content`(): Unit =
        runBlocking {
            val client = connect()
            val ok = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_OK}"}""")
            assertNotEquals(true, ok.isError)
            assertEquals("ok", ok.text())
            assertEquals(1, ok.content.size)

            val describe = client.run("fx_read", """{"describe":true}""")
            assertNotEquals(true, describe.isError)
            assertEquals("ok", describe.text())
            assertEquals(1, describe.content.size)
        }

    // ------------------------------------------------------------------ S2 single-call error

    @Test
    fun `S2 a single-call error keeps the summary line and appends the envelope as the last line`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_MISSING}"}""")
            val s = result.structured()
            val sErr = s.err()

            assertEquals(true, result.isError)
            assertEquals(1, result.content.size)
            assertEquals("not_found: ${sErr.str("message")}", result.firstLine())
            assertEquals(2, result.text().lines().size, "summary line plus one JSON line: ${result.text()}")
            // control: the fixture error really carries a fix and a detail, so dropping them from the text is observable
            assertNotNull(sErr["fix"], "fixture error carries a fix")
            assertNotNull(sErr["detail"], "fixture error carries a detail")
            val t = result.tail()
            assertEquals(s, t)
            assertEquals(sErr["fix"], t.err()["fix"])
            assertEquals(sErr["detail"], t.err()["detail"])
        }

    // ------------------------------------------------------------------ S3 retryAfterMs

    @Test
    fun `S3 an unavailable error carries retryAfterMs and the shedding kind in the text tail`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_BUSY}"}""")

            assertEquals(true, result.isError)
            assertTrue(result.firstLine().startsWith("unavailable: "), result.text())
            val error = result.tail().err()
            assertEquals("shedding", error.str("kind"))
            val retry = error.getValue("retryAfterMs").jsonPrimitive.long
            assertTrue(retry > 0, "retryAfterMs is positive: $retry")
            assertEquals(
                retry,
                error
                    .getValue("detail")
                    .jsonObject
                    .getValue("retryAfterMs")
                    .jsonPrimitive.long
            )
            assertEquals(result.structured(), result.tail())
        }

    // ------------------------------------------------------------------ S4 partial_failure

    @Test
    fun `S4 a non-atomic partial failure lists only the failing elements with their index`(): Unit =
        runBlocking {
            val client = connect()
            val fail = FixtureTools.FAIL_TITLE
            val result = client.run("fx_write", ops(create("A"), create(fail), create("B"), create(fail)))
            val s = result.structured()
            val t = result.tail()

            assertEquals(true, result.isError)
            assertEquals(1, result.content.size)
            assertEquals("partial_failure: ${s.err().str("message")}", result.firstLine())
            assertEquals(4, s.results().size, "control: structuredContent keeps every element")
            assertEquals(s.err(), t.err())
            assertEquals(s.getValue("meta"), t.getValue("meta"))
            val summary =
                t
                    .getValue("data")
                    .jsonObject
                    .getValue("summary")
                    .jsonObject
            assertEquals(s.getValue("data").jsonObject.getValue("summary"), summary)
            assertEquals(2, summary.getValue("ok").jsonPrimitive.int)
            assertEquals(2, summary.getValue("failed").jsonPrimitive.int)

            val expected =
                listOf(1, 3).map { i ->
                    buildJsonObject {
                        put("index", i)
                        s.results()[i].jsonObject.forEach { (k, v) -> put(k, v) }
                    }
                }
            assertEquals(expected, t.results().toList())
            for (entry in t.results()) {
                assertEquals("not_found", entry.jsonObject.err().str("code"))
                assertNotEquals(
                    true,
                    entry.jsonObject["ok"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.toBoolean(),
                    "no ok entry in the tail"
                )
            }
            assertEquals(2, t.results().size)
        }

    // ------------------------------------------------------------------ S5 atomic

    @Test
    fun `S5 an atomic rollback error carries the envelope with the failing index and no data`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_write", ops(create("A"), create(FixtureTools.FAIL_TITLE), top = ""","atomic":true"""))
            val s = result.structured()
            val t = result.tail()

            assertEquals(true, result.isError)
            assertEquals("not_found: ${s.err().str("message")}", result.firstLine())
            assertEquals(s, t)
            assertEquals(
                1,
                t
                    .err()
                    .getValue("index")
                    .jsonPrimitive.int
            )
            assertFalse("data" in t, "an atomic error has no data member")
            assertFalse("A" in titles(), "the atomic batch wrote nothing")
        }

    // ------------------------------------------------------------------ S6 validation

    @Test
    fun `S6 an invalid selector error carries the field violations in the tail`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_read", """{"select":"nope"}""")

            assertEquals(true, result.isError)
            assertTrue(result.firstLine().startsWith("invalid_request: "), result.text())
            val fields =
                result
                    .tail()
                    .err()
                    .getValue("detail")
                    .jsonObject
                    .getValue("fields")
                    .jsonArray
            assertTrue(fields.isNotEmpty(), "detail.fields is non-empty")
            assertEquals(result.structured(), result.tail())
        }

    // ------------------------------------------------------------------ S7 internal

    @Test
    fun `S7 an internal error keeps the fixed message and never leaks the exception text`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_SECRET}"}""")

            assertEquals(true, result.isError)
            assertEquals("internal: Internal server error", result.firstLine())
            assertEquals(result.structured(), result.tail())
            assertEquals("Internal server error", result.tail().err().str("message"))
            assertFalse(FixtureTools.SECRET in result.text(), "the exception text is absent from the text content")
        }

    // ------------------------------------------------------------------ S8 cap

    @Test
    fun `S8 the failing entries are capped at the first 10 while the summary keeps the true failed count`(): Unit =
        runBlocking {
            val client = connect()
            for (n in listOf(9, 10, 11, 12)) {
                val elements = listOf(create("A$n")) + List(n) { create(FixtureTools.FAIL_TITLE) }
                val result = client.run("fx_write", ops(*elements.toTypedArray()))
                val t = result.tail()
                val kept = minOf(n, 10)
                assertEquals((1..kept).toList(), failingIndices(result), "N=$n: the first $kept failing indices, ascending")
                assertEquals(kept, t.results().size, "N=$n")
                val summary =
                    t
                        .getValue("data")
                        .jsonObject
                        .getValue("summary")
                        .jsonObject
                assertEquals(n, summary.getValue("failed").jsonPrimitive.int, "N=$n: summary.failed is the true count")
                assertEquals(1, summary.getValue("ok").jsonPrimitive.int, "N=$n")
                assertEquals(n + 1, result.structured().results().size, "N=$n: structuredContent is uncapped")
            }
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe an error without fix or detail has those keys absent in the tail, never null`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_write", ops(create("A"), create(FixtureTools.FAIL_TITLE)))
            val sErr = result.structured().err()
            val tErr = result.tail().err()
            for (key in listOf("fix", "detail", "retryAfterMs")) {
                assertEquals(key in sErr, key in tErr, "$key presence matches structuredContent")
                assertNotEquals<JsonElement?>(JsonNull, tErr[key], "$key is never an explicit null")
            }
            assertFalse("fix" in tErr, "control: a partial_failure error has no fix, so the key must be absent")
        }

    @Test
    fun `probe a non-ASCII message keeps the summary line byte-identical and the tail parseable`(): Unit =
        runBlocking {
            val client = connect(ToolRegistry(listOf(localTool)))
            val result = client.run("lc_read", """{"select":"get","id":"$UNICODE_ID"}""")

            assertEquals(true, result.isError)
            assertEquals("not_found: $UNICODE_MESSAGE", result.firstLine())
            assertEquals(UNICODE_MESSAGE, result.tail().err().str("message"))
            assertEquals(result.structured(), result.tail())
        }

    @Test
    fun `probe a message with a newline keeps the JSON tail the last line and the whole summary before it`(): Unit =
        runBlocking {
            val client = connect(ToolRegistry(listOf(localTool)))
            val result = client.run("lc_read", """{"select":"get","id":"$NEWLINE_ID"}""")

            assertEquals(true, result.isError)
            assertEquals("not_found: first line\nsecond line", result.firstLine())
            assertTrue(result.text().startsWith("not_found: first line\nsecond line\n{"), result.text())
            assertEquals("first line\nsecond line", result.tail().err().str("message"))
            assertEquals(result.structured(), result.tail())
        }

    @Test
    fun `probe a replayed keyed failing element appears in the tail exactly as in the structured results`(): Unit =
        runBlocking {
            val client = connect()
            val keyed = ops(create("A"), create(FixtureTools.FAIL_TITLE, ""","idempotencyKey":"key-1""""))
            client.run("fx_write", keyed)
            val second = client.run("fx_write", keyed)

            val s = second.structured()
            val expected =
                buildJsonObject {
                    put("index", 1)
                    s.results()[1].jsonObject.forEach { (k, v) -> put(k, v) }
                }
            assertEquals(listOf<JsonElement>(expected), second.tail().results().toList())
        }

    @Test
    fun `probe call_log responseBytes on an error call is the text bytes plus the structured bytes`(): Unit =
        runBlocking {
            val client = connect()
            val result = client.run("fx_read", """{"select":"get","id":"${FixtureTools.GET_MISSING}"}""")

            val record = sink.records.single()
            val expected = utf8(result.text()) + utf8(result.structured().toString())
            assertEquals(expected, record.responseBytes)
            assertTrue(result.text().contains('\n'), "control: the text really carries a tail the byte count must include")
        }

    private companion object {
        const val NEWLINE_ID = "ffffffff"
        const val UNICODE_ID = "99999999"
        const val UNICODE_MESSAGE = "café 中文 😀"
    }
}
