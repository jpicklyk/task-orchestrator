package io.github.jpicklyk.mcptask.current.interfaces.api.v1

import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging.CLIENT_HOST_HEADER
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import io.ktor.server.routing.get as routeGet

/**
 * Independent tests (test-author seat, item ab14346c, Q7 host capture) for the REST side: scenarios S3, S4, S5, S6, S8,
 * S9, S10, S13 and the probes of the frozen test-plan, driven through the REAL production wiring ([installRestApiRoutes]
 * with its `callLog` parameter) over the real composition, bearer auth and event log, exactly as RestCallLogTest does.
 *
 * Oracles (task-scope, frozen at queue phase): REST host = normalize(first X-Client-Host value) when non-null, otherwise
 * normalize(UA product name per RFC 9110 10.1.5) when non-null, otherwise `rest`; a blank header counts as absent;
 * `call_log.host` and `events.host` carry it for every call, 401 rows included; host is data only (responses are
 * identical across hosts apart from reqId); `AppConfig.DEFAULT_CORS_HEADERS` gains `X-Client-Host`.
 *
 * Harness proof: the test client may add a default User-Agent or normalize header values, so [echo] reads back through
 * a plain route what the server really received for the same request shape before any "absent" scenario is believed.
 *
 * Duplicate X-Client-Host headers are INAPPLICABLE here: testApplication merges duplicate headers and the shape cannot be
 * proven preserved (test-plan probe list).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class RestHostCaptureTest {
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
            serverName = "q7-rest-host",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            callLog = writer
        )
        routing {
            routeGet("/echo-headers") {
                val ua = call.request.headers[HttpHeaders.UserAgent] ?: "<none>"
                val xch = call.request.headers[CLIENT_HOST_HEADER] ?: "<none>"
                call.respondText("UA=[$ua];XCH=[$xch]")
            }
        }
    }

    private suspend fun HttpClient.send(
        path: String,
        token: String? = TEST_TOKEN,
        extra: List<Pair<String, String>> = emptyList()
    ): HttpResponse =
        get("/api/v1$path") {
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            extra.forEach { (k, v) -> header(k, v) }
        }

    /** What the server received for a request with exactly these extra headers (no Authorization, outside /api/v1). */
    private suspend fun HttpClient.echo(extra: List<Pair<String, String>> = emptyList()): String =
        get("/echo-headers") {
            header(HttpHeaders.Authorization, "Bearer $TEST_TOKEN")
            extra.forEach { (k, v) -> header(k, v) }
        }.bodyAsText()

    private fun callLogRows() = CallLogRows.read(db.jdbcUrl)

    private fun rowOf(response: HttpResponse): Map<String, Any?> {
        val reqId = assertNotNull(response.headers["X-Req-Id"], "X-Req-Id on a recorded response")
        return callLogRows().single { it["req_id"] == reqId }
    }

    /** (req_id, host) of every events row, ordered by seq, straight from the table. */
    private fun eventHosts(): List<Pair<String?, String?>> =
        DriverManager.getConnection(db.jdbcUrl).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT req_id, host FROM events ORDER BY seq").use { rs ->
                    buildList {
                        while (rs.next()) add(rs.getString(1) to rs.getString(2))
                    }
                }
            }
        }

    private fun ua(value: String) = listOf(HttpHeaders.UserAgent to value)

    private fun host(value: String) = listOf("X-Client-Host" to value)

    // ------------------------------------------------------------------ constants and harness proof

    @Test
    fun `the header name and the default CORS allow-list carry X-Client-Host`() {
        assertEquals("X-Client-Host", CLIENT_HOST_HEADER)
        assertEquals(listOf("Authorization", "Content-Type", "If-Match", "X-Client-Host"), AppConfig.DEFAULT_CORS_HEADERS)
    }

    @Test
    fun `harness proof the client preserves sent headers and always adds a default User-Agent unless one is set`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals(
                "UA=[ktor-client];XCH=[<none>]",
                client.echo(),
                "the test client cannot omit a User-Agent: with none set it sends its own default, so a truly absent UA is unreachable here"
            )
            assertEquals(
                "UA=[];XCH=[<none>]",
                client.echo(ua("")),
                "an empty User-Agent value reaches the server as an empty header, the closest reachable state to absent"
            )
            assertEquals(
                "UA=[curl/8.4.0];XCH=[Pi]",
                client.echo(ua("curl/8.4.0") + host("Pi")),
                "values arrive exactly as sent, case included"
            )
        }

    // ------------------------------------------------------------------ S3 S4 S5

    @Test
    fun `S3 X-Client-Host wins over the User-Agent`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response = client.send("/transitions", extra = host("Pi") + ua("curl/8.4.0"))
            assertEquals(HttpStatusCode.OK, response.status)
            writer.flushNow()
            val row = rowOf(response)
            assertEquals("rest", row["surface"])
            assertEquals("pi", row["host"], "the header (lowercased) beats the UA product")
        }

    @Test
    fun `S4 without X-Client-Host the User-Agent product is the host`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals("UA=[curl/8.4.0];XCH=[<none>]", client.echo(ua("curl/8.4.0")), "harness proof for this request shape")
            val response = client.send("/transitions", extra = ua("curl/8.4.0"))
            assertEquals(HttpStatusCode.OK, response.status)
            writer.flushNow()
            assertEquals("curl", rowOf(response)["host"])
        }

    @Test
    fun `S4 only the first product of a long User-Agent is used`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response =
                client.send(
                    "/transitions",
                    extra = ua("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
                )
            writer.flushNow()
            assertEquals("mozilla", rowOf(response)["host"])
        }

    @Test
    fun `S5 with no X-Client-Host and an empty User-Agent the host is the rest fallback`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals(
                "UA=[];XCH=[<none>]",
                client.echo(ua("")),
                "harness proof: the server sees an empty User-Agent and no X-Client-Host"
            )
            val response = client.send("/transitions", extra = ua(""))
            assertEquals(HttpStatusCode.OK, response.status)
            writer.flushNow()
            assertEquals("rest", rowOf(response)["host"])
        }

    @Test
    fun `S4 the test client's own default User-Agent is an ordinary product name`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals(
                "UA=[ktor-client];XCH=[<none>]",
                client.echo(),
                "harness proof: this is what a request with no User-Agent set carries"
            )
            val response = client.send("/items")
            writer.flushNow()
            assertEquals("ktor-client", rowOf(response)["host"], "RFC 9110 10.1.5: the product token before any slash")
        }

    // ------------------------------------------------------------------ S8

    @Test
    fun `S8 a blank X-Client-Host is absent and the User-Agent product is used`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals(
                "UA=[curl/1];XCH=[   ]",
                client.echo(host("   ") + ua("curl/1")),
                "harness proof: the blank value reaches the server unchanged"
            )
            val response = client.send("/items", extra = host("   ") + ua("curl/1"))
            writer.flushNow()
            assertEquals("curl", rowOf(response)["host"])
        }

    @Test
    fun `S8 a blank X-Client-Host with no User-Agent gives the rest fallback`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response = client.send("/items", extra = host("   ") + ua(""))
            writer.flushNow()
            assertEquals("rest", rowOf(response)["host"])
        }

    @Test
    fun `probe absent empty and blank X-Client-Host give the same outcome`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val absent = client.send("/items", extra = ua("curl/1"))
            val empty = client.send("/items", extra = host("") + ua("curl/1"))
            val blank = client.send("/items", extra = host("  ") + ua("curl/1"))
            writer.flushNow()
            assertEquals("curl", rowOf(absent)["host"])
            assertEquals("curl", rowOf(empty)["host"])
            assertEquals("curl", rowOf(blank)["host"])
        }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a User-Agent without a product gives the rest fallback`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val parenthesised = client.send("/items", extra = ua("(compatible; x)"))
            val noToken = client.send("/items", extra = ua("/1.0 foo"))
            writer.flushNow()
            assertEquals("rest", rowOf(parenthesised)["host"], "a UA starting with an opening parenthesis has no product")
            assertEquals("rest", rowOf(noToken)["host"], "an empty token before the first slash is no product")
        }

    @Test
    fun `probe a User-Agent with surrounding whitespace is trimmed before the product is taken`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response = client.send("/items", extra = ua("   curl/8   "))
            writer.flushNow()
            assertEquals("curl", rowOf(response)["host"])
        }

    // ------------------------------------------------------------------ normalization on the wire

    @Test
    fun `X-Client-Host is lowercased bounded and matched case-insensitively by header name`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val upper = client.send("/items", extra = host("PI"))
            val long = client.send("/items", extra = host("A".repeat(100)))
            val lowerName = client.send("/items", extra = listOf("x-client-host" to "Agent X"))
            writer.flushNow()
            assertEquals("pi", rowOf(upper)["host"])
            assertEquals("a".repeat(64), rowOf(long)["host"], "N5: the first 64 characters")
            assertEquals("agent x", rowOf(lowerName)["host"], "header names are case-insensitive; interior spaces survive")
        }

    // ------------------------------------------------------------------ S10

    @Test
    fun `S10 a 401 row carries the X-Client-Host the unauthenticated caller sent`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response = client.send("/items", token = null, extra = host("pi"))
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            writer.flushNow()
            val row = rowOf(response)
            assertEquals("error", row["outcome"], "fixture: this is the 401 error row")
            assertNull(row["principal_id"], "fixture: unauthenticated")
            assertEquals("pi", row["host"])
        }

    @Test
    fun `S10 a 401 row follows the same fallback chain`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val viaUa = client.send("/items", token = null, extra = ua("curl/8.4.0"))
            val bare = client.send("/items", token = null, extra = ua(""))
            val headerWins = client.send("/items", token = null, extra = host("Pi") + ua("curl/8.4.0"))
            for (r in listOf(viaUa, bare, headerWins)) assertEquals(HttpStatusCode.Unauthorized, r.status)
            writer.flushNow()
            assertEquals("curl", rowOf(viaUa)["host"])
            assertEquals("rest", rowOf(bare)["host"])
            assertEquals("pi", rowOf(headerWins)["host"])
        }

    @Test
    fun `S10 a typed 404 and an unmatched route both record the host`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val missing = client.send("/items/${java.util.UUID.randomUUID()}", extra = host("pi"))
            val unknown = client.send("/definitely-not-a-route", extra = host("pi"))
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            writer.flushNow()
            assertEquals("pi", rowOf(missing)["host"])
            assertEquals("pi", rowOf(unknown)["host"])
        }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 a REST write stamps call_log and every event row with the same host`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val response =
                client.post("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer $WRITE_TOKEN")
                    header("X-Client-Host", "Pi")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S6 host write","type":"test-item"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val reqId = assertNotNull(response.headers["X-Req-Id"])

            val events = eventHosts()
            assertTrue(events.isNotEmpty(), "fixture: the write recorded at least one event")
            assertTrue(events.all { it.first == reqId }, "fixture: every event belongs to this call: $events")
            assertTrue(events.all { it.second == "pi" }, "$events")
            writer.flushNow()
            assertEquals("pi", rowOf(response)["host"])
        }

    @Test
    fun `S6 a REST write with no host headers records the rest fallback on its events`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            assertEquals("UA=[];XCH=[<none>]", client.echo(ua("")), "harness proof: nothing identifies the caller")
            val response =
                client.post("/api/v1/items") {
                    header(HttpHeaders.Authorization, "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.UserAgent, "")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"S6 fallback write","type":"test-item"}""")
                }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            val events = eventHosts()
            assertTrue(events.isNotEmpty(), "fixture: events recorded")
            assertTrue(events.all { it.second == "rest" }, "$events")
            writer.flushNow()
            assertEquals("rest", rowOf(response)["host"])
        }

    // ------------------------------------------------------------------ per-call isolation and S13

    @Test
    fun `calls with different hosts in sequence and concurrently never leak into each other`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }

            val expected = mutableMapOf<String, String>()

            suspend fun go(
                extra: List<Pair<String, String>>,
                host: String
            ) {
                val r = client.send("/items", extra = extra)
                expected[assertNotNull(r.headers["X-Req-Id"])] = host
            }
            go(host("alpha"), "alpha")
            go(ua(""), "rest")
            go(host("beta"), "beta")
            go(ua("curl/1"), "curl")
            val concurrent =
                coroutineScope {
                    (1..24)
                        .map { i ->
                            async {
                                val name = if (i % 2 == 0) "even" else "odd"
                                val r = client.send("/items", extra = host(name))
                                assertNotNull(r.headers["X-Req-Id"]) to name
                            }
                        }.awaitAll()
                }
            concurrent.forEach { (reqId, name) -> expected[reqId] = name }

            writer.flushNow()
            val rows = callLogRows().filter { (it["tool"] as String).startsWith("GET /api/v1/items") }
            assertEquals(expected.size, rows.size, "one row per call")
            for (row in rows) assertEquals(expected.getValue(row["req_id"] as String), row["host"], "row ${row["req_id"]}")
        }

    @Test
    fun `S13 the same read with different hosts returns identical status and body`() =
        testApplication {
            val rig = EventLogRig.build(db.db, dir)
            val writer = newWriter(rig.composition)
            application { configureApp(rig.composition, writer) }
            val item = rig.seed("S13 rest read")

            val a = client.send("/items/${item.id}", extra = host("alpha"))
            val b = client.send("/items/${item.id}", extra = host("beta") + ua("curl/9"))
            val c = client.send("/items/${item.id}", extra = ua(""))
            assertEquals(HttpStatusCode.OK, a.status)
            val bodyA = a.bodyAsText()
            assertTrue(bodyA.contains("S13 rest read"), "fixture: the body is the real item: $bodyA")
            assertEquals(b.status, a.status)
            assertEquals(c.status, a.status)

            // The items_read envelope carries the call's own meta.reqId; everything else must be identical.
            fun withoutMeta(text: String) = JsonObject(Json.parseToJsonElement(text).jsonObject - "meta")
            assertEquals(withoutMeta(bodyA), withoutMeta(b.bodyAsText()), "host is data only: the body does not change with it")
            assertEquals(withoutMeta(bodyA), withoutMeta(c.bodyAsText()))
            assertNotNull(a.headers["X-Req-Id"])
            writer.flushNow()
            assertEquals("alpha", rowOf(a)["host"])
            assertEquals("beta", rowOf(b)["host"])
            assertEquals("rest", rowOf(c)["host"])
        }
}
