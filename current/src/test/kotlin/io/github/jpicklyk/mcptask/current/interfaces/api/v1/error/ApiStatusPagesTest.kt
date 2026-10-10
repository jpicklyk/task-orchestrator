package io.github.jpicklyk.mcptask.current.interfaces.api.v1.error

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored for item `0c07190d` (error catalog adoption), scenarios S8-S11 of the frozen `test-plan`: the
 * Ktor StatusPages safety net installed by `Application.installApiStatusPages()` for `/api/v1`.
 *
 * NEW-SURFACE: every scenario binds to `installApiStatusPages`, which the item introduces. Narrowest-revert recipe
 * (from the plan): keep `installApiStatusPages` but empty its body - S8 and S9 (and the log probe) go red on behaviour
 * (an unhandled exception no longer becomes the internal ErrorDto, Ktor statuses no longer map), while S10 and S11
 * stay green by design (they only assert what does NOT happen). A plain revert fails only on compilation.
 *
 * Harness: the production install order - `ContentNegotiation` first (as in `installRestApiRoutes`'s caller), then
 * `installApiStatusPages()` with no parameters, then routing - over Ktor's `testApplication`, with throwing routes
 * registered by the test (the function takes no parameters, so the throwing routes are the test's own).
 *
 * Oracles: [T] task-scope Build step 7 and AC4 (an uncaught `/api/v1` exception returns 500
 * `{"error":"internal","message":"Internal server error"}` and never the exception text; `BadRequestException` ->
 * 400 `bad_request`; domain `ValidationException` -> 400 `validation_error`; `NotFoundException` -> 404 `not_found`;
 * `UnsupportedMediaTypeException` -> 415 `unsupported_media_type`; `PayloadTooLargeException` -> 413
 * `payload_too_large`; `CancellationException` is never mapped; other paths keep Ktor's default 500); [R] api-rest.md
 * section 6 as of the base commit (the code/status rows above and the `ErrorDto` shape `{error, message, details?}`).
 */
class ApiStatusPagesTest {
    private val json = Json

    private val internalMessage = "Internal server error"

    private fun Application.statusPagesApp(extra: io.ktor.server.routing.Route.() -> Unit = {}) {
        install(ContentNegotiation) { json(McpJson) }
        installApiStatusPages()
        routing {
            route("/api/v1") {
                get("/boom") { throw RuntimeException("SELECT secret FROM users WHERE token = 'hunter2'") }
                get("/boom-null") { throw RuntimeException() }
                get("/boom-state") { throw IllegalStateException("password=hunter2", RuntimeException("cause with SELECT")) }
                get("/ok") { call.respondText("fine") }
                get("/bad-request") { throw BadRequestException("bad thing") }
                get("/validation") { throw ValidationException("title must not be blank") }
                get("/missing") { throw NotFoundException("nothing here") }
                get("/unsupported") { throw UnsupportedMediaTypeException(ContentType.Text.Plain) }
                get("/too-large") { throw PayloadTooLargeException(1024L) }
                get("/cancelled") { throw CancellationException("cancelled by the test") }
                post("/echo") { call.respond(call.receive<Echo>()) }
                extra()
            }
            get("/other/boom") { throw RuntimeException("SELECT outside the api") }
            get("/api/v10/boom") { throw RuntimeException("SELECT near but not inside the api") }
            get("/mcp-like/boom") { throw RuntimeException("SELECT mcp") }
        }
    }

    @Serializable
    private data class Echo(
        val name: String
    )

    private fun HttpResponse.expectError(
        status: HttpStatusCode,
        error: String,
        body: String
    ): JsonObject {
        assertEquals(status, this.status, "status for $error: $body")
        val parsed = json.parseToJsonElement(body).jsonObject
        assertEquals(error, parsed["error"]?.jsonPrimitive?.content, "error code: $body")
        assertFalse(parsed["message"]?.jsonPrimitive?.content.isNullOrBlank(), "message must be present: $body")
        return parsed
    }

    // ----------------------------------------------
    // S8 - an uncaught exception under /api/v1
    // ----------------------------------------------

    @Test
    fun `S8 an uncaught exception under api v1 returns the exact internal ErrorDto and no exception text`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/boom")
            val body = response.bodyAsText()

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val expected =
                buildJsonObject {
                    put("error", "internal")
                    put("message", internalMessage)
                }
            assertEquals(expected, json.parseToJsonElement(body).jsonObject, "the body is exactly the internal ErrorDto: $body")
            assertFalse(body.contains("SELECT"), "the exception text must not leak: $body")
            assertFalse(body.contains("hunter2"), "the exception text must not leak: $body")
            assertTrue(
                response.contentType()?.match(ContentType.Application.Json) == true,
                "the error body is JSON, got ${response.contentType()}"
            )
        }

    @Test
    fun `S8 probe an exception with a null message and one with a cause both return the same internal ErrorDto`() =
        testApplication {
            application { statusPagesApp() }

            val nullMessage = client.get("/api/v1/boom-null")
            val withCause = client.get("/api/v1/boom-state")

            for (response in listOf(nullMessage, withCause)) {
                val body = response.bodyAsText()
                assertEquals(HttpStatusCode.InternalServerError, response.status, body)
                assertEquals(
                    "internal",
                    json
                        .parseToJsonElement(body)
                        .jsonObject["error"]
                        ?.jsonPrimitive
                        ?.content,
                    body
                )
                assertEquals(
                    internalMessage,
                    json
                        .parseToJsonElement(body)
                        .jsonObject["message"]
                        ?.jsonPrimitive
                        ?.content,
                    body
                )
                assertFalse(body.contains("hunter2") || body.contains("SELECT"), "no exception text or cause text: $body")
            }
        }

    @Test
    fun `S8 probe after a handled failure the same application still serves a normal request`() =
        testApplication {
            application { statusPagesApp() }

            client.get("/api/v1/boom")
            val ok = client.get("/api/v1/ok")

            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals("fine", ok.bodyAsText())
        }

    @Test
    fun `S8 an uncaught exception under api v1 is logged at ERROR`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        val appender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                root.addAppender(it)
            }
        try {
            testApplication {
                application { statusPagesApp() }
                client.get("/api/v1/boom")
            }
            // Ktor itself logs an unhandled route exception at ERROR ("Unhandled: ..." and "Unhandled server error: ...")
            // whether or not a safety net exists, so only an ERROR event carrying the exception that is not one of
            // those two default lines proves the safety net logged it.
            val ownErrors =
                appender.list.filter { event ->
                    event.level == Level.ERROR &&
                        event.throwableProxy?.className == RuntimeException::class.java.name &&
                        !event.formattedMessage.startsWith("Unhandled:") &&
                        !event.formattedMessage.startsWith("Unhandled server error")
                }
            assertTrue(
                ownErrors.isNotEmpty(),
                "the safety net must log the exception at ERROR itself, saw: ${appender.list.map { it.level to it.formattedMessage }}"
            )
        } finally {
            root.detachAppender(appender)
        }
    }

    // ----------------------------------------------
    // S9 - Ktor and domain exceptions map to their 3.x ErrorDto
    // ----------------------------------------------

    @Test
    fun `S9 BadRequestException maps to 400 bad_request`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/bad-request")

            response.expectError(HttpStatusCode.BadRequest, "bad_request", response.bodyAsText())
        }

    @Test
    fun `S9 domain ValidationException maps to 400 validation_error`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/validation")

            response.expectError(HttpStatusCode.BadRequest, "validation_error", response.bodyAsText())
        }

    @Test
    fun `S9 NotFoundException maps to 404 not_found`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/missing")

            response.expectError(HttpStatusCode.NotFound, "not_found", response.bodyAsText())
        }

    @Test
    fun `S9 probe UnsupportedMediaTypeException maps to 415 unsupported_media_type`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/unsupported")

            response.expectError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", response.bodyAsText())
        }

    @Test
    fun `S9 probe PayloadTooLargeException maps to 413 payload_too_large`() =
        testApplication {
            application { statusPagesApp() }

            val response = client.get("/api/v1/too-large")

            response.expectError(HttpStatusCode.PayloadTooLarge, "payload_too_large", response.bodyAsText())
        }

    @Test
    fun `S9 probe a malformed JSON body on a receive route maps to 400 bad_request, not 500`() =
        testApplication {
            application { statusPagesApp() }

            val response =
                client.post("/api/v1/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("{not json")
                }

            response.expectError(HttpStatusCode.BadRequest, "bad_request", response.bodyAsText())
        }

    @Test
    fun `S9 probe a well-formed body on the same receive route is untouched`() =
        testApplication {
            application { statusPagesApp() }

            val response =
                client.post("/api/v1/echo") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"alpha"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "alpha",
                json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["name"]
                    ?.jsonPrimitive
                    ?.content
            )
        }

    // ----------------------------------------------
    // S10 - paths outside /api/v1 keep Ktor's default handling
    // ----------------------------------------------

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.outcomeOf(path: String): Pair<HttpStatusCode?, String> =
        try {
            val response = client.get(path)
            response.status to response.bodyAsText()
        } catch (e: Throwable) {
            null to "threw ${e::class.simpleName}"
        }

    private fun isInternalErrorDto(body: String): Boolean =
        runCatching {
            json
                .parseToJsonElement(body)
                .jsonObject["error"]
                ?.jsonPrimitive
                ?.content == "internal"
        }.getOrDefault(false)

    @Test
    fun `S10 a throwing route outside api v1 keeps Ktor's default 500 and gets no internal ErrorDto`() =
        testApplication {
            application { statusPagesApp() }

            val (status, body) = outcomeOf("/other/boom")

            assertEquals(HttpStatusCode.InternalServerError, status, "other paths keep Ktor's default 500")
            assertFalse(isInternalErrorDto(body), "outside /api/v1 the body is not the StatusPages ErrorDto: $body")
        }

    @Test
    fun `S10 probe a path that merely starts with the api v1 characters is not an api v1 path`() =
        testApplication {
            application { statusPagesApp() }

            val (status, body) = outcomeOf("/api/v10/boom")

            assertEquals(HttpStatusCode.InternalServerError, status, "Ktor's default 500 applies to /api/v10")
            assertFalse(isInternalErrorDto(body), "/api/v10 is not /api/v1, so no StatusPages ErrorDto: $body")
        }

    @Test
    fun `S10 control the same two throwing routes under api v1 do get the internal ErrorDto`() =
        testApplication {
            application { statusPagesApp() }

            val (status, body) = outcomeOf("/api/v1/boom")

            assertEquals(HttpStatusCode.InternalServerError, status)
            assertTrue(isInternalErrorDto(body), "control: under /api/v1 the safety net applies: $body")
        }
    // ----------------------------------------------
    // S11 - cancellation is never mapped
    // ----------------------------------------------

    @Test
    fun `S11 a CancellationException in an api v1 route is not turned into the internal ErrorDto`() =
        testApplication {
            application { statusPagesApp() }

            val (status, body) = outcomeOf("/api/v1/cancelled")

            assertEquals(HttpStatusCode.InternalServerError, status, "cancellation is left to Ktor's default handling")
            assertFalse(isInternalErrorDto(body), "cancellation must not become the internal ErrorDto: $body")
            assertFalse(body.contains(internalMessage), "cancellation must not carry the StatusPages message: $body")
        }
}
