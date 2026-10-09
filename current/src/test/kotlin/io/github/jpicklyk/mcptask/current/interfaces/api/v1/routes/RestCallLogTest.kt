package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installMcpStreamableHttp
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Independent tests for item 8abb69e2 (P10): the REST surface of the call log through the REAL production wiring
 * ([installRestApiRoutes] with its `callLog` parameter) over the real composition, bearer auth and event log.
 * Scenarios S4, S10, S16, S19 of the frozen test-plan plus path-normalization probes.
 *
 * Oracles: plan section 3.8, the task-scope "Column sources" REST column and acceptance A1-A3 (frozen at queue phase).
 * Response and request byte counts are recomputed from the bytes the client sent and received.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RestCallLogTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun newWriter(composition: CompositionResult) =
        CallLogWriter(composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours)

    private fun Application.configureApp(
        composition: CompositionResult,
        writer: CallLogWriter
    ) {
        val authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = authConfig.tokens.mapValues { (_, principal) -> BearerTokenStore.TokenEntry(principal, expiresAt = null) },
            allowQueryToken = false,
            serverName = "p10-rest-call-log",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            callLog = writer
        )
    }

    private suspend fun HttpClient.getWith(
        path: String,
        token: String? = TEST_TOKEN,
        extra: List<Pair<String, String>> = emptyList()
    ): HttpResponse =
        get("/api/v1$path") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            extra.forEach { (k, v) -> header(k, v) }
        }

    private fun callLogRows() = CallLogRows.read(db.jdbcUrl)

    private fun ceilQuarter(bytes: Long): Long = (bytes + 3) / 4

    private fun ids(json: Any?): List<String> = Json.parseToJsonElement(json as String).jsonArray.map { it.jsonPrimitive.content }

    // ------------------------------------------------------------------ S4

    @Test
    fun `S4 a REST read carries X-Req-Id and writes one row with the REST columns`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val item = rig.seed("S4 item")

            val response = client.getWith("/items/${item.id}", extra = listOf("X-Request-Id" to "spoof123"))
            assertEquals(HttpStatusCode.OK, response.status)
            val reqId = assertNotNull(response.headers["X-Req-Id"], "X-Req-Id on a recorded response")
            assertTrue(ReqId.isValid(reqId), reqId)
            assertNotEquals("spoof123", reqId, "an inbound X-Request-Id is never the reqId")
            val bodyBytes = response.bodyAsBytes().size.toLong()

            writer.flushNow()
            val row = callLogRows().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("rest", row["surface"])
            assertEquals("GET /api/v1/items/{id}", row["tool"])
            assertEquals(listOf(item.id.toString()), ids(row["target_ids"]))
            assertNull(row["target_versions"], "REST records no target versions")
            assertNull(row["operation"])
            assertEquals("api:$TEST_TOKEN_ID", row["principal_id"])
            assertEquals("external", row["principal_kind"])
            assertEquals("ok", row["outcome"])
            assertNull(row["error_code"])
            assertEquals(1L, row["attempts"])
            assertEquals(0L, row["request_bytes"], "a bodyless GET")
            assertEquals(bodyBytes, row["response_bytes"], "the UTF-8 length of the body the client received")
            assertEquals(ceilQuarter(bodyBytes), row["response_tokens_est"])
            assertEquals("bytes/4", row["token_method"])
            assertEquals(0L, row["replayed"])
            assertNull(row["session_id"], "REST has no MCP session")
        }

    @Test
    fun `S4 a REST write stamps its events with the X-Req-Id and records the request body size`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val body = """{"title":"S4 café write"}"""
            val response =
                client.post("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val reqId = assertNotNull(response.headers["X-Req-Id"])

            val stamps = CallLogRows.eventStamps(db.jdbcUrl)
            assertTrue(stamps.isNotEmpty(), "fixture: the write recorded at least one event")
            assertTrue(stamps.all { it.first == reqId }, "every event of the write carries X-Req-Id $reqId: $stamps")

            writer.flushNow()
            val row = callLogRows().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("POST /api/v1/items", row["tool"])
            assertEquals("api:$WRITE_TOKEN_ID", row["principal_id"])
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), row["request_bytes"], "Content-Length of the JSON body")
        }

    // ------------------------------------------------------------------ S10

    @Test
    fun `S10 a typed 404 records the ErrorDto code and an unknown route records http_404`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val missing = UUID.randomUUID()
            val typed = client.getWith("/items/$missing")
            assertEquals(HttpStatusCode.NotFound, typed.status)
            val typedCode =
                Json
                    .parseToJsonElement(typed.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content
            assertEquals("not_found", typedCode, "fixture: the ErrorDto of a missing item")

            val unknown = client.getWith("/definitely-not-a-route")
            assertEquals(HttpStatusCode.NotFound, unknown.status)

            writer.flushNow()
            val byTool = callLogRows().associateBy { it["tool"] }
            val typedRow = byTool.getValue("GET /api/v1/items/{id}")
            assertEquals("error", typedRow["outcome"])
            assertEquals("not_found", typedRow["error_code"], "the typed ErrorDto.error, not http_404")
            assertEquals(typed.headers["X-Req-Id"], typedRow["req_id"])
            assertEquals(
                listOf(missing.toString()),
                ids(typedRow["target_ids"]),
                "a UUID path segment is a target even when the item does not exist"
            )
            val unknownRow = byTool.getValue("GET unmatched")
            assertEquals("error", unknownRow["outcome"])
            assertEquals("http_404", unknownRow["error_code"], "no typed body: http_<status>")
            assertEquals(unknown.headers["X-Req-Id"], unknownRow["req_id"])
        }

    @Test
    fun `path normalisation replaces UUID segments only and ignores the query string`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val a = UUID.randomUUID()
            val b = UUID.randomUUID()
            client.getWith("/items/$a/zzz/$b")
            client.getWith("/items/$a/notes/my-key")
            client.getWith("/items?limit=5&includeChildren=true&title=x")

            writer.flushNow()
            val byTool = callLogRows().associateBy { it["tool"] }
            // F1: no route resolved, so the tool is path-derived: UUID -> {id}, any non-vocabulary segment -> {param}.
            val two = byTool.getValue("GET /api/v1/items/{id}/{param}/{id}")
            assertEquals(listOf(a.toString(), b.toString()), ids(two["target_ids"]))
            val keyed = callLogRows().single { (it["tool"] as String).startsWith("GET /api/v1/items/{id}/notes/") }
            assertTrue(!(keyed["tool"] as String).contains("my-key"), "the raw key is never stored: ${keyed["tool"]}")
            assertEquals(listOf(a.toString()), ids(keyed["target_ids"]), "a non-UUID segment is not a target")
            val listing = byTool.getValue("GET /api/v1/items")
            val shape = Json.parseToJsonElement(listing["request_shape"] as String).jsonObject
            assertEquals(listOf("includeChildren", "limit"), shape.keys.toList())
            assertNull(listing["target_ids"])
        }

    // ------------------------------------------------------------------ S19

    @Test
    fun `S19 an unauthenticated call is a 401 that still gets X-Req-Id and an error row without a principal`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response = client.getWith("/items", token = null)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val reqId = assertNotNull(response.headers["X-Req-Id"], "the 401 carries X-Req-Id")
            assertTrue(ReqId.isValid(reqId))

            writer.flushNow()
            val row = callLogRows().single()
            assertEquals(reqId, row["req_id"])
            assertEquals("error", row["outcome"])
            assertNull(row["principal_id"])
            assertNull(row["principal_kind"])
            assertNull(row["proof_status"])
            val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()) }.getOrNull() as? JsonObject
            val typed = parsed?.get("error")?.jsonPrimitive?.content
            assertEquals(typed ?: "http_401", row["error_code"], "ErrorDto.error when the 401 body is a typed ErrorDto, else http_401")
        }

    // ------------------------------------------------------------------ F1

    @Test
    fun `F1 a 401 on a long junk path never stores client text and no client-derived string exceeds 64 characters`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val junk = "j".repeat(4000)
            val manyFlags = (0 until 40).joinToString("&") { "flag${it.toString().padStart(2, '0')}=true" }
            // Unauthenticated: the auth plugin answers 401 before any route resolves.
            val outside = client.getWith("/$junk?$junk=true&$manyFlags&bad key=true", token = null)
            assertEquals(HttpStatusCode.Unauthorized, outside.status)
            val inside = client.getWith("/items/$junk", token = null)
            assertEquals(HttpStatusCode.Unauthorized, inside.status)

            writer.flushNow()
            val rows = callLogRows()
            assertEquals(2, rows.size)
            for (row in rows) {
                row.forEach { (column, value) ->
                    if (value is String) {
                        assertTrue(!value.contains("jjjjjjjj"), "$column stores client text: ${value.take(80)}")
                        if (column !in setOf("tool", "request_shape", "target_ids", "target_versions")) {
                            assertTrue(value.length <= 64, "$column is ${value.length} chars")
                        }
                    }
                }
                assertTrue((row["tool"] as String).length <= 128, "the tool is a bounded template: ${row["tool"]}")
                val shape = row["request_shape"]?.let { Json.parseToJsonElement(it as String).jsonObject }
                if (shape != null) {
                    assertTrue(shape.size <= 16, "at most 16 request_shape keys: ${shape.keys}")
                    assertEquals(shape.keys.sorted(), shape.keys.toList(), "sorted")
                    assertTrue(shape.keys.all { Regex("^[A-Za-z0-9_.-]{1,64}$").matches(it) }, "conforming keys: ${shape.keys}")
                }
            }
            val byReq = rows.associateBy { it["req_id"] }
            assertEquals("GET unmatched", byReq.getValue(outside.headers["X-Req-Id"])["tool"], "unknown first segment")
            assertEquals(
                "GET /api/v1/items/{param}",
                byReq.getValue(inside.headers["X-Req-Id"])["tool"],
                "vocabulary first segment, then a non-UUID segment becomes {param}"
            )
        }

    // ------------------------------------------------------------------ S16

    @Test
    fun `S16 the mcp endpoint writes one mcp row, and handshake, listing, well-known and the SSE path write none`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            val server =
                Server(
                    serverInfo = Implementation(name = "p10-http", version = "1.0.0"),
                    options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true)))
                )
            McpToolAdapter(callLog = writer).registerToolsWithServer(server, buildMcpTools(), rig.ctx)
            application {
                installMcpStreamableHttp(server)
                val authConfig: ApiAuthConfig.Bearer = makeWriteAuthConfig()
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = rig.composition.toolContext.repositoryProvider,
                    apiTokenEntries = authConfig.tokens.mapValues { (_, p) -> BearerTokenStore.TokenEntry(p, expiresAt = null) },
                    allowQueryToken = false,
                    serverName = "p10-http",
                    serverVersion = "test",
                    actorAuthEnabled = rig.composition.actorAuthEnabled,
                    noteSchemaService = rig.composition.noteSchemaService,
                    toolContext = rig.composition.toolContext,
                    degradedModePolicy = rig.composition.degradedModePolicy,
                    callLog = writer
                )
            }
            val accept = "application/json, text/event-stream"

            val init =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, accept)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"t","version":"1.0"}}}"""
                    )
                }
            val sessionId = assertNotNull(init.headers["mcp-session-id"])
            client.post("/mcp") {
                header(HttpHeaders.Accept, accept)
                header("mcp-session-id", sessionId)
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            }
            client.post("/mcp") {
                header(HttpHeaders.Accept, accept)
                header("mcp-session-id", sessionId)
                contentType(ContentType.Application.Json)
                setBody("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
            }
            val call =
                client.post("/mcp") {
                    header(HttpHeaders.Accept, accept)
                    header("mcp-session-id", sessionId)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"query_items","arguments":{"operation":"overview"}}}"""
                    )
                }
            val payload =
                call
                    .bodyAsText()
                    .lineSequence()
                    .firstOrNull { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
                    ?: call.bodyAsText().trim()
            val result = Json.parseToJsonElement(payload).jsonObject["result"]!!.jsonObject
            val reqId =
                result["_meta"]
                    ?.jsonObject
                    ?.get("reqId")
                    ?.jsonPrimitive
                    ?.content
            assertTrue(ReqId.isValid(reqId), "the over-HTTP tool result carries _meta.reqId: $result")

            client.get("/.well-known/oauth-protected-resource")
            client.getWith("/events")

            writer.flushNow()
            val rows = callLogRows()
            val mcp = rows.filter { it["surface"] == "mcp" }
            assertEquals(1, mcp.size, "only the tools/call is an MCP call_log row: $rows")
            assertEquals(reqId, mcp.single()["req_id"])
            assertEquals("query_items", mcp.single()["tool"])
            assertEquals(
                0,
                rows.count { it["surface"] == "rest" },
                "the /mcp endpoint is not a REST call, and neither is SSE or well-known: $rows"
            )
        }

    @Test
    fun `S16 reads and the public health route are REST calls but the SSE stream is not`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            client.getWith("/events")
            writer.flushNow()
            assertEquals(0, callLogRows().size, "GET /api/v1/events is the SSE stream and is excluded")
            client.getWith("/health", token = null)
            client.getWith("/items")
            writer.flushNow()
            val tools = callLogRows().map { it["tool"] }.toSet()
            assertEquals(
                setOf<Any?>("GET /api/v1/health", "GET /api/v1/items"),
                tools,
                "every non-SSE /api/v1 request is recorded, reads included"
            )
        }
}
