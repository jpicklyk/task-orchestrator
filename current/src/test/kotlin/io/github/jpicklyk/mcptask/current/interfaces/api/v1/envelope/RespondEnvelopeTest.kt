package io.github.jpicklyk.mcptask.current.interfaces.api.v1.envelope

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.application.api.BatchExecution
import io.github.jpicklyk.mcptask.current.domain.error.BatchResult
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.error.installApiStatusPages
import io.github.jpicklyk.mcptask.current.interfaces.shared.envelope.EnvelopeEncoder
import io.github.jpicklyk.mcptask.current.test.EnvelopeConformance
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for `ApplicationCall.respondEnvelope` and the `/api/v1/ops`
 * internal-envelope branch of `installApiStatusPages()`, scenario S18.
 *
 * Oracles: envelope section 3 (the HTTP status is a pure function of `error.code` and no route picks a status by hand; the
 * body is the envelope's wire bytes), task-scope D9 and the Contract (a non-cancellation throwable under a path equal to
 * `/api/v1/ops` or starting with `/api/v1/ops/` is logged at ERROR and answered with the `internal` envelope, status 500,
 * never the exception text; Q32 extended that to every `/api/v1` path, so the legacy body is gone) and plan section 3.2
 * ("`internal` never carries exception text").
 *
 * Harness: the production install order of `ApiStatusPagesTest` (ContentNegotiation, then `installApiStatusPages()`, then
 * routing) over Ktor `testApplication`, with test-owned throwing routes. The sentinel text `SECRET-7f3a` is test-chosen.
 * Not covered here: reuse of the correlation plugin's `X-Req-Id` (the correlation plugin is not part of this harness).
 */
class RespondEnvelopeTest {
    private val secret = "SECRET-7f3a"

    private fun Application.opsApp() {
        install(ContentNegotiation) { json(McpJson) }
        installApiStatusPages()
        routing {
            route("/api/v1") {
                get("/ops") { throw RuntimeException(secret) }
                get("/ops/x") { throw RuntimeException(secret) }
                get("/ops/a/b/c") { throw RuntimeException(secret) }
                get("/ops/null-message") { throw RuntimeException() }
                get("/ops/state") { throw IllegalStateException(secret, RuntimeException("cause-$secret")) }
                get("/opsx") { throw RuntimeException(secret) }
                get("/ops2/x") { throw RuntimeException(secret) }
                get("/ops-extra") { throw RuntimeException(secret) }
                get("/items") { throw RuntimeException(secret) }
                get("/items/ops") { throw RuntimeException(secret) }
                get("/ops/ok") {
                    call.respondEnvelope(EnvelopeEncoder.outcome(Outcome.Ok(1), "reqid001") { buildJsonObject { put("id", "x") } })
                }
                get("/ops/code/{wire}") {
                    val wire = call.parameters["wire"]!!
                    val code = ErrorCode.entries.first { it.wire == wire }
                    call.respondEnvelope(EnvelopeEncoder.error(ErrorFixtures.error(code), "reqid001"))
                }
                get("/ops/partial") {
                    val execution =
                        BatchExecution(
                            BatchResult(listOf(Outcome.Ok("a"), Outcome.Err(ErrorFixtures.error(ErrorCode.NOT_FOUND)))),
                            emptySet()
                        )
                    call.respondEnvelope(EnvelopeEncoder.batch(execution, "reqid001") { buildJsonObject { put("id", it) } })
                }
                get("/ops/unicode") {
                    val message = "Caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00 \"quoted\""
                    call.respondEnvelope(EnvelopeEncoder.error(DomainError(ErrorCode.INVALID_CURSOR, message), "reqid001"))
                }
            }
        }
    }

    private suspend fun HttpResponse.jsonBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    // ---------------------------------------------------------------- respondEnvelope

    @Test
    fun `S18 respondEnvelope answers an ok envelope with status 200, JSON content type and the exact wire bytes`() =
        testApplication {
            application { opsApp() }

            val response = client.get("/api/v1/ops/ok")
            val text = response.bodyAsText()

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.contentType()?.match(ContentType.Application.Json) == true, "got ${response.contentType()}")
            assertEquals("""{"ok":true,"data":{"id":"x"},"meta":{"reqId":"reqid001"}}""", text)
        }

    @Test
    fun `S18 respondEnvelope uses the envelope status for each error code and the body is the encoder's bytes`() =
        testApplication {
            application { opsApp() }
            val expected =
                mapOf(
                    "invalid_request" to 400,
                    "invalid_cursor" to 400,
                    "unauthenticated" to 401,
                    "forbidden" to 403,
                    "not_found" to 404,
                    "gate_blocked" to 409,
                    "idempotency_mismatch" to 409,
                    "payload_too_large" to 413,
                    "schema_violation" to 422,
                    "internal" to 500,
                    "unavailable" to 503
                )
            for ((wire, status) in expected) {
                val response = client.get("/api/v1/ops/code/$wire")
                val text = response.bodyAsText()

                assertEquals(HttpStatusCode.fromValue(status), response.status, "status for $wire")
                val code = ErrorCode.entries.first { it.wire == wire }
                assertEquals(EnvelopeEncoder.error(ErrorFixtures.error(code), "reqid001").body.toString(), text, "bytes for $wire")
                EnvelopeConformance.assertConforms(Json.parseToJsonElement(text), wire)
            }
        }

    @Test
    fun `S18 respondEnvelope answers a partial failure with 207 and both error and data results`() =
        testApplication {
            application { opsApp() }

            val response = client.get("/api/v1/ops/partial")
            val body = response.jsonBody()

            assertEquals(HttpStatusCode.MultiStatus, response.status)
            assertEquals(JsonPrimitive(false), body["ok"])
            assertEquals(
                "partial_failure",
                body
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            )
            assertTrue("data" in body)
            EnvelopeConformance.assertConforms(body, "partial over HTTP")
        }

    @Test
    fun `S18 probe non-ASCII and quote characters in a message survive the HTTP round trip`() =
        testApplication {
            application { opsApp() }

            val text = client.get("/api/v1/ops/unicode").bodyAsText()
            val message =
                Json
                    .parseToJsonElement(text)
                    .jsonObject
                    .getValue("error")
                    .jsonObject
                    .getValue("message")
                    .jsonPrimitive.content

            assertEquals("Caf\u00e9 \u65e5\u672c\u8a9e \ud83d\ude00 \"quoted\"", message)
        }

    // ---------------------------------------------------------------- /api/v1/ops internal mapping

    private fun assertInternalEnvelope(
        response: HttpResponse,
        text: String,
        label: String
    ) {
        assertEquals(HttpStatusCode.InternalServerError, response.status, label)
        assertTrue(response.contentType()?.match(ContentType.Application.Json) == true, "$label: content type ${response.contentType()}")
        assertFalse(text.contains(secret), "$label: the exception text must not leak: $text")
        assertFalse(text.contains("cause-"), "$label: the cause text must not leak: $text")
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals(listOf("ok", "error", "meta"), body.keys.toList(), "$label: envelope shape")
        assertEquals(JsonPrimitive(false), body["ok"], label)
        assertEquals(
            Json.parseToJsonElement("""{"code":"internal","kind":"transient","message":"Internal server error"}"""),
            body["error"],
            "$label: exactly the fixed internal error member"
        )
        val reqId =
            body
                .getValue("meta")
                .jsonObject
                .getValue("reqId")
                .jsonPrimitive.content
        assertTrue(reqId.isNotBlank(), "$label: a request id is always present")
        assertEquals(EnvelopeEncoder.internal(reqId).body.toString(), text, "$label: the bytes are the internal envelope for that reqId")
        EnvelopeConformance.assertConforms(body, label)
    }

    @Test
    fun `S18 a throwing route under api v1 ops answers the internal envelope without the exception text`() =
        testApplication {
            application { opsApp() }

            for (path in listOf("/api/v1/ops/x", "/api/v1/ops", "/api/v1/ops/a/b/c", "/api/v1/ops/null-message", "/api/v1/ops/state")) {
                val response = client.get(path)
                assertInternalEnvelope(response, response.bodyAsText(), path)
            }
        }

    @Test
    fun `S18 Q32 every other api v1 path, including look-alikes of the ops prefix, answers the same internal envelope`() =
        testApplication {
            application { opsApp() }

            for (path in listOf("/api/v1/items", "/api/v1/opsx", "/api/v1/ops2/x", "/api/v1/ops-extra", "/api/v1/items/ops")) {
                val response = client.get(path)
                assertInternalEnvelope(response, response.bodyAsText(), path)
            }
        }

    @Test
    fun `S18 an exception under api v1 ops is logged at ERROR with its text, though it never reaches the response`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                root.addAppender(it)
            }
        try {
            testApplication {
                application { opsApp() }
                client.get("/api/v1/ops/x")
            }
            // Ktor logs an unhandled route exception at ERROR itself ("Unhandled: ..."), so only a different ERROR event that
            // carries the exception proves the ops branch logged it.
            val ownErrors =
                appender.list.filter { event ->
                    event.level == Level.ERROR &&
                        event.throwableProxy?.className == RuntimeException::class.java.name &&
                        event.throwableProxy?.message == secret &&
                        !event.formattedMessage.startsWith("Unhandled:") &&
                        !event.formattedMessage.startsWith("Unhandled server error")
                }
            assertTrue(
                ownErrors.isNotEmpty(),
                "the ops branch must log the exception at ERROR itself, saw: ${appender.list.map { it.level to it.formattedMessage }}"
            )
        } finally {
            root.detachAppender(appender)
        }
    }
}
