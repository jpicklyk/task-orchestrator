package io.github.jpicklyk.mcptask.current.interfaces.api.v1.logging

import io.github.jpicklyk.mcptask.current.application.port.CallLogRecord
import io.github.jpicklyk.mcptask.current.application.port.CallLogSink
import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.telemetry.ReqId
import io.github.jpicklyk.mcptask.current.test.CollectingSink
import io.github.jpicklyk.mcptask.current.test.LogCapture
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for item 8abb69e2 (P10): the REST interceptor [installRequestCorrelation] with a [CallLogSink],
 * exercised on small hand-built routes so every status, header and body shape is under the test's control.
 *
 * Oracles: task-scope What-to-build item 8 and the "Column sources" REST column (frozen): for `/api/v1/...` except the SSE
 * stream `GET /api/v1/events` the reqId is always server-generated (inbound `X-Request-Id` keeps feeding MDC `requestId`
 * only), goes into MDC `reqId` and the `X-Req-Id` response header BEFORE the handler runs, and one record is submitted
 * after the handler; tool = `"<METHOD> <path>"` with UUID segments replaced by `{id}`; outcome `error` iff status >= 400
 * with error_code `http_<status>` when no typed ErrorDto was captured; request_bytes = Content-Length (0 if bodyless);
 * response_bytes from the outgoing content length; target ids are the UUID path segments; request_shape holds boolean
 * query params and `limit`.
 */
class RequestCorrelationCallLogTest {
    private val probeLogger = "p10.request.correlation.probe"
    private val fixed: Instant = Instant.parse("2026-04-05T06:07:08.123Z")

    private fun Application.probeRoutes() {
        routing {
            route("/api/v1") {
                get("/probe") {
                    LoggerFactory.getLogger(probeLogger).info("inside handler")
                    call.respondText(call.response.headers[REQ_ID_HEADER] ?: "header-missing")
                }
                get("/probe/{id}") { call.respondText("ok") }
                get("/items/{id}") { call.respondText("ok") }
                post("/echo") { call.respondText(call.receiveText()) }
                get("/status/{code}") {
                    val code = call.parameters["code"]!!.toInt()
                    call.respond(HttpStatusCode(code, "Probe"))
                }
                get("/events") { call.respondText("stream") }
            }
            get("/outside") { call.respondText("outside") }
            get("/.well-known/probe") { call.respondText("well-known") }
        }
    }

    @Test
    fun `A1 the reqId in the response header, in MDC and in the submitted record are the same server-generated value`() {
        val sink = CollectingSink()
        val capture = LogCapture().attach(probeLogger)
        try {
            testApplication {
                application {
                    installRequestCorrelation(callLog = sink, clock = Clock { fixed })
                    probeRoutes()
                }
                val response = client.get("/api/v1/probe") { header("X-Request-Id", "abc-123") }
                val reqId = assertNotNull(response.headers[REQ_ID_HEADER], "X-Req-Id must be set")
                assertTrue(ReqId.isValid(reqId), "valid reqId: $reqId")
                assertEquals(reqId, response.bodyAsText(), "the header is already on the response while the handler runs")
                val mdc = capture.events.single().mdc
                assertEquals(reqId, mdc["reqId"], "MDC reqId equals the header")
                assertEquals("abc-123", mdc["requestId"], "the inbound X-Request-Id still feeds MDC requestId only")
                assertEquals(reqId, sink.records.single().reqId, "the record is keyed by the same reqId")
            }
        } finally {
            capture.detach()
        }
    }

    @Test
    fun `A1 an inbound X-Request-Id that looks like a valid reqId is never adopted`() {
        val sink = CollectingSink()
        testApplication {
            application {
                installRequestCorrelation(callLog = sink)
                probeRoutes()
            }
            repeat(5) {
                val response = client.get("/api/v1/probe") { header("X-Request-Id", "k7f3q9ab") }
                assertNotEquals("k7f3q9ab", response.headers[REQ_ID_HEADER], "the reqId is always server-generated")
            }
            assertEquals(5, sink.records.size)
            assertEquals(
                5,
                sink.records
                    .map { it.reqId }
                    .toSet()
                    .size
            )
            assertTrue(sink.records.none { it.reqId == "k7f3q9ab" })
        }
    }

    @Test
    fun `A2 one record per request, with the REST columns derived from the request and response`() {
        val sink = CollectingSink()
        val id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e")
        testApplication {
            application {
                installRequestCorrelation(callLog = sink, clock = Clock { fixed })
                probeRoutes()
            }
            val response = client.get("/api/v1/items/$id?limit=7&flag=true&name=x&count=3")
            assertEquals(HttpStatusCode.OK, response.status)
            val record = sink.records.single()
            assertEquals(response.headers[REQ_ID_HEADER], record.reqId)
            assertEquals(CallLogRecord.SURFACE_REST, record.surface)
            assertEquals("GET /api/v1/items/{id}", record.tool, "UUID segments become {id}; the query string is not part of the tool")
            assertEquals(listOf(id.toString()), Json.parseToJsonElement(record.targetIds!!).jsonArray.map { it.jsonPrimitive.content })
            val shape = Json.parseToJsonElement(record.requestShape!!).jsonObject
            assertEquals(listOf("flag", "limit"), shape.keys.toList(), "boolean query params and limit, sorted: ${record.requestShape}")
            assertEquals("true", shape["flag"]!!.jsonPrimitive.content)
            assertEquals("7", shape["limit"]!!.jsonPrimitive.content)
            assertEquals(CallLogRecord.OUTCOME_OK, record.outcome)
            assertNull(record.errorCode)
            assertEquals(fixed, record.at, "at is the interceptor's entry time on the injected clock")
            assertEquals(1, record.attempts)
            assertTrue(record.latencyMs >= 0)
            assertEquals(0L, record.requestBytes, "a bodyless GET has request_bytes 0")
            assertEquals(2L, record.responseBytes, "the body \"ok\" is two bytes")
            assertEquals(1L, record.responseTokensEst, "ceil(2 / 4)")
            assertEquals("bytes/4", record.tokenMethod)
            assertNull(record.principalId, "no authenticated principal in this app")
            assertNull(record.principalKind)
            assertNull(record.proofStatus)
            assertNull(record.sessionId)
            assertNull(record.operation)
            assertNull(record.batchSize)
            assertTrue(!record.replayed)
        }
    }

    @Test
    fun `A2 request_bytes is the Content-Length of the request body in UTF-8 bytes`() {
        val sink = CollectingSink()
        testApplication {
            application {
                installRequestCorrelation(callLog = sink)
                probeRoutes()
            }
            val body = "café ✓"
            client.post("/api/v1/echo") { setBody(body) }
            assertEquals(body.toByteArray(Charsets.UTF_8).size.toLong(), sink.records.single().requestBytes)
            assertNotEquals(body.length.toLong(), sink.records.single().requestBytes, "fixture: bytes differ from characters")
        }
    }

    @Test
    fun `A2 outcome is error exactly from status 400 with an http_status code`() {
        val sink = CollectingSink()
        testApplication {
            application {
                installRequestCorrelation(callLog = sink)
                probeRoutes()
            }
            for (code in listOf(200, 204, 304, 399, 400, 401, 404, 422, 503)) client.get("/api/v1/status/$code")
            // /status is outside the REST vocabulary (F1), so every row is "GET unmatched"; requests are sequential, so
            // the records are in request order.
            val codes = listOf(200, 204, 304, 399, 400, 401, 404, 422, 503)
            assertEquals(codes.size, sink.records.size)
            assertTrue(sink.records.all { it.tool == "GET unmatched" }, "F1: ${sink.records.map { it.tool }}")
            val byCode = codes.zip(sink.records).toMap()
            for (code in listOf(200, 204, 304, 399)) {
                assertEquals(CallLogRecord.OUTCOME_OK, byCode.getValue(code).outcome, "status $code is not an error")
            }
            for (code in listOf(400, 401, 404, 422, 503)) {
                val record = byCode.getValue(code)
                assertEquals(CallLogRecord.OUTCOME_ERROR, record.outcome, "status $code is an error")
                assertEquals("http_$code", record.errorCode, "no typed ErrorDto, so the code is http_<status>")
            }
            assertNull(byCode.getValue(399).errorCode)
        }
    }

    @Test
    fun `A2 an unknown route under api v1 is recorded as http_404`() {
        val sink = CollectingSink()
        testApplication {
            application {
                installRequestCorrelation(callLog = sink)
                probeRoutes()
            }
            val response = client.get("/api/v1/no-such-route")
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertNotNull(response.headers[REQ_ID_HEADER], "even a 404 carries X-Req-Id")
            val record = sink.records.single()
            assertEquals("http_404", record.errorCode)
            assertEquals(CallLogRecord.OUTCOME_ERROR, record.outcome)
            assertEquals("GET unmatched", record.tool, "F1: the raw path is never stored; no-such-route is outside the vocabulary")
        }
    }

    @Test
    fun `A2 the SSE stream path and paths outside api v1 write no record and get no header`() {
        val sink = CollectingSink()
        testApplication {
            application {
                installRequestCorrelation(callLog = sink)
                probeRoutes()
            }
            for (path in listOf("/api/v1/events", "/outside", "/.well-known/probe", "/api/v10/other")) {
                val response = client.get(path)
                assertNull(response.headers[REQ_ID_HEADER], "no X-Req-Id for $path")
            }
            assertEquals(emptyList(), sink.records, "none of the four paths is a recorded REST call")
            client.get("/api/v1/probe")
            assertEquals(1, sink.records.size, "control: a recorded path in the same app does write a record")
        }
    }

    @Test
    fun `A4 a sink that throws never changes the response`() {
        val throwing = CallLogSink { throw IllegalStateException("sink down") }
        testApplication {
            application {
                installRequestCorrelation(callLog = throwing)
                probeRoutes()
            }
            val response = client.get("/api/v1/probe/${UUID.randomUUID()}")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
            assertTrue(ReqId.isValid(response.headers[REQ_ID_HEADER]))
        }
    }
}
