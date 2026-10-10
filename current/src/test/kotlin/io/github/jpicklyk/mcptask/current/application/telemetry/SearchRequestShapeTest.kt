package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * S13 (AC8, decision U16/D8): the `call_log.request_shape` of a search call, on the MCP surface (driven through the real
 * [McpToolAdapter] over the in-memory client/server pair and the real production tool set) and on the REST surface
 * (through the real [installRestApiRoutes] wiring), gains `queryHash`, `termCount` and `matchMode`, and the raw query
 * text is never stored in any column of the row.
 *
 * Oracles: U16 / D8 / AC8 of the task-scope (a hash, a term count and the match mode; the query text never); the
 * declared `SearchService.QUERY_HASH_HEX_LENGTH` (16 lowercase hex characters); term count = number of whitespace
 * separated terms of the query. The exact hash function is not asserted (its serialization of the term list is not
 * declared): the tests assert the shape, determinism, whitespace independence, sensitivity to the query, and that the
 * same query hashes the same on MCP and REST.
 *
 * Not covered here (undeclared): how the hit count that makes the zero-hit rate derivable is recorded.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SearchRequestShapeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val hexPattern = Regex("^[0-9a-f]{${SearchService.QUERY_HASH_HEX_LENGTH}}$")

    private class Rig(
        val rig: EventLogRig,
        val writer: CallLogWriter,
    )

    private fun newRig(): Rig {
        val rig = EventLogRig.build(db.db, dir)
        val writer = CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)
        return Rig(rig, writer)
    }

    private suspend fun connect(rig: Rig): Pair<Client, Server> {
        val server = Server(serverInfo = Implementation(name = "s13-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpToolAdapter(callLog = rig.writer).registerToolsWithServer(server, buildMcpTools(), rig.rig.ctx)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "s13-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        return client to server
    }

    private suspend fun Client.call(
        tool: String,
        vararg args: Pair<String, JsonElement>,
    ): CallToolResult =
        callTool(
            buildCallToolRequest {
                name = tool
                arguments(JsonObject(mapOf(*args)))
            }
        )

    private suspend fun search(
        client: Client,
        tool: String,
        query: String,
        matchMode: String? = null,
    ): CallToolResult {
        val args = mutableListOf<Pair<String, JsonElement>>("operation" to JsonPrimitive("search"), "query" to JsonPrimitive(query))
        if (matchMode != null) args += "matchMode" to JsonPrimitive(matchMode)
        val result = client.call(tool, *args.toTypedArray())
        assertTrue(result.isError != true, "$tool search must succeed: ${result.content}")
        return result
    }

    private suspend fun rowsOf(
        rig: Rig,
        tool: String,
    ): List<Map<String, Any?>> {
        rig.writer.flushNow()
        return CallLogRows.forTool(db.jdbcUrl, tool)
    }

    private fun shapeOf(row: Map<String, Any?>): JsonObject = Json.parseToJsonElement(row["request_shape"] as String).jsonObject

    private fun assertNoQueryText(vararg words: String) {
        val all = CallLogRows.read(db.jdbcUrl)
        assertTrue(all.isNotEmpty(), "fixture: call_log rows exist")
        all.forEach { row ->
            row.forEach { (column, value) ->
                val text = if (value is ByteArray) String(value, Charsets.UTF_8) else value?.toString().orEmpty()
                words.forEach { word ->
                    assertFalse(word in text.lowercase(), "column '$column' must not contain the raw query word '$word': $text")
                }
            }
        }
    }

    private fun Application.restApp(rig: Rig) {
        val authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = rig.rig.composition.toolContext.repositoryProvider,
            apiTokenEntries = authConfig.tokens.mapValues { (_, principal) -> BearerTokenStore.TokenEntry(principal, expiresAt = null) },
            allowQueryToken = false,
            serverName = "s13-rest-call-log",
            serverVersion = "test",
            actorAuthEnabled = rig.rig.composition.actorAuthEnabled,
            noteSchemaService = rig.rig.composition.noteSchemaService,
            toolContext = rig.rig.composition.toolContext,
            degradedModePolicy = rig.rig.composition.degradedModePolicy,
            callLog = rig.writer
        )
    }

    // -- MCP ------------------------------------------------------------------------------------------------------------

    @Test
    fun `S13 an MCP query_items search records hash term count and match mode and never the query text`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                rig.rig.seed("S13 host item")
                search(client, "query_items", "zqxbananaquery qwvmangoquery", matchMode = "substring")

                val shape = shapeOf(rowsOf(rig, "query_items").single())

                val hash = shape["queryHash"]!!.jsonPrimitive.content
                assertTrue(
                    hexPattern.matches(hash),
                    "queryHash must be ${SearchService.QUERY_HASH_HEX_LENGTH} lowercase hex characters: $hash"
                )
                assertEquals(2, shape["termCount"]!!.jsonPrimitive.content.toInt())
                assertEquals("substring", shape["matchMode"]!!.jsonPrimitive.content.lowercase())
                assertNoQueryText("zqxbananaquery", "qwvmangoquery")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 an MCP query_notes search records the same shape`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                search(client, "query_notes", "zqxbananaquery", matchMode = "text")

                val shape = shapeOf(rowsOf(rig, "query_notes").single())

                assertTrue(hexPattern.matches(shape["queryHash"]!!.jsonPrimitive.content))
                assertEquals(1, shape["termCount"]!!.jsonPrimitive.content.toInt())
                assertEquals("text", shape["matchMode"]!!.jsonPrimitive.content.lowercase())
                assertNoQueryText("zqxbananaquery")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 an omitted match mode is recorded as auto`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                search(client, "query_items", "zqxbananaquery")

                val shape = shapeOf(rowsOf(rig, "query_items").single())

                assertEquals("auto", shape["matchMode"]!!.jsonPrimitive.content.lowercase())
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 the query hash is deterministic independent of spacing and sensitive to the query`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                search(client, "query_items", "alphaprobe betaprobe")
                search(client, "query_items", "  alphaprobe    betaprobe  ")
                search(client, "query_items", "alphaprobe betaprobe")
                search(client, "query_items", "alphaprobe gammaprobe")

                val hashes = rowsOf(rig, "query_items").map { shapeOf(it)["queryHash"]!!.jsonPrimitive.content }

                assertEquals(4, hashes.size)
                // Rows are returned in req_id order, which is not call order, so compare as a multiset of three equal and one different.
                val grouped = hashes.groupingBy { it }.eachCount()
                assertEquals(
                    listOf(1, 3),
                    grouped.values.sorted(),
                    "three calls share one hash (same terms, any spacing), the fourth differs: $grouped"
                )
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 calls that are not text searches carry no search shape`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                rig.rig.seed("S13 overview item")
                client.call("query_items", "operation" to JsonPrimitive("overview"))
                client.call("query_items", "operation" to JsonPrimitive("search"))

                val rows = rowsOf(rig, "query_items")

                assertEquals(2, rows.size)
                rows.forEach { row ->
                    val raw = row["request_shape"] as String?
                    val shape = raw?.let { Json.parseToJsonElement(it).jsonObject }
                    listOf("queryHash", "termCount", "matchMode").forEach { key ->
                        assertNull(shape?.get(key), "a call with no query text must not carry '$key': $raw")
                    }
                }
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    // -- REST ---------------------------------------------------------------------------------------------------------------

    @Test
    fun `S13 GET search and GET notes search record the shape and never the query text`() =
        testApplication {
            val rig = newRig()
            application { restApp(rig) }
            val items =
                client.get(
                    "/api/v1/search?q=zqxbananaquery%20qwvmangoquery"
                ) { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            val notes = client.get("/api/v1/notes/search?q=zqxbananaquery") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, items.status)
            assertEquals(HttpStatusCode.OK, notes.status)

            val itemShape = shapeOf(rowsOf(rig, "GET /api/v1/search").single())
            val noteShape = shapeOf(rowsOf(rig, "GET /api/v1/notes/search").single())

            assertTrue(hexPattern.matches(itemShape["queryHash"]!!.jsonPrimitive.content))
            assertEquals(2, itemShape["termCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("auto", itemShape["matchMode"]!!.jsonPrimitive.content.lowercase())
            assertTrue(hexPattern.matches(noteShape["queryHash"]!!.jsonPrimitive.content))
            assertEquals(1, noteShape["termCount"]!!.jsonPrimitive.content.toInt())
            assertEquals("auto", noteShape["matchMode"]!!.jsonPrimitive.content.lowercase())
            assertNoQueryText("zqxbananaquery", "qwvmangoquery")
        }

    // -- both surfaces -------------------------------------------------------------------------------------------------------

    @Test
    fun `S13 the same query hashes the same on MCP and REST`() =
        testApplication {
            val rig = newRig()
            application { restApp(rig) }
            val (mcp, server) = connect(rig)
            try {
                search(mcp, "query_items", "crossprobe surfaceprobe")
                search(mcp, "query_notes", "crossprobe surfaceprobe")
                client.get("/api/v1/search?q=crossprobe%20surfaceprobe") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }
                client.get("/api/v1/notes/search?q=crossprobe%20surfaceprobe") { header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN") }

                val mcpItems = shapeOf(rowsOf(rig, "query_items").single())["queryHash"]!!.jsonPrimitive.content
                val mcpNotes = shapeOf(rowsOf(rig, "query_notes").single())["queryHash"]!!.jsonPrimitive.content
                val restItems = shapeOf(rowsOf(rig, "GET /api/v1/search").single())["queryHash"]!!.jsonPrimitive.content
                val restNotes = shapeOf(rowsOf(rig, "GET /api/v1/notes/search").single())["queryHash"]!!.jsonPrimitive.content

                assertEquals(mcpItems, restItems, "item search hashes identically on both surfaces")
                assertEquals(mcpNotes, restNotes, "note search hashes identically on both surfaces")
                assertTrue(hexPattern.matches(mcpItems), "queryHash format: $mcpItems")
            } finally {
                closeInMemoryPair(mcp, server)
            }
        }
}
