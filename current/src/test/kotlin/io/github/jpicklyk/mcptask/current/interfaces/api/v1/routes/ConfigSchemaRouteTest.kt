package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.infrastructure.config.ConfigJsonSchema
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for `GET /api/v1/config/schema`, scenario S13.
 *
 * Oracle P s4.4 and task-scope build item 2: the route returns the raw schema bytes, content type
 * `application/schema+json`, an ETag, under the same auth as `/config/schemas`. The schema declares JSON Schema 2020-12.
 *
 * Harness: [configureTestApp] with [configRoutes] in the route block (the same arrangement as [ConfigRoutesTest]).
 */
class ConfigSchemaRouteTest {
    @Test
    fun `S13 GET config schema returns 200 with the schema media type`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(ConfigRoutesTest.FakeSchemaService()) } }

            val response = client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }

            assertEquals(HttpStatusCode.OK, response.status)
            val contentType = assertNotNull(response.headers[HttpHeaders.ContentType], "Content-Type must be present")
            assertTrue(contentType.startsWith("application/schema+json"), "Content-Type was $contentType")
        }

    @Test
    fun `S13 the body is byte-equal to the schema resource and declares draft 2020-12`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(ConfigRoutesTest.FakeSchemaService()) } }

            val response = client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }

            val onClasspath =
                assertNotNull(
                    ConfigSchemaRouteTest::class.java.getResourceAsStream("/config/config.schema.json")
                ).use { it.readBytes() }
            assertTrue(onClasspath.contentEquals(response.readRawBytes()), "the route must serve the resource bytes unchanged")
            val declared =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["\$schema"]
                    ?.jsonPrimitive
                    ?.content
            assertEquals("https://json-schema.org/draft/2020-12/schema", declared)
        }

    @Test
    fun `S13 the response carries an ETag that is stable across reads and carries the schema digest`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(ConfigRoutesTest.FakeSchemaService()) } }

            val first = client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }
            val second = client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }

            val etag = assertNotNull(first.headers[HttpHeaders.ETag], "ETag must be present")
            assertEquals(etag, second.headers[HttpHeaders.ETag], "the same bytes must give the same ETag")
            assertTrue(Regex("^(W/)?\"[^\"]+\"$").matches(etag), "an ETag header is a quoted entity-tag: $etag")
            assertTrue(
                etag.contains(ConfigJsonSchema.etag.trim('"')),
                "the header ETag must derive from ConfigJsonSchema.etag: $etag vs ${ConfigJsonSchema.etag}"
            )
        }

    @Test
    fun `S13 the schema route has the same auth as the schemas route - no credentials is refused alike`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(ConfigRoutesTest.FakeSchemaService()) } }

            val schemaAnon = client.get("/api/v1/config/schema")
            val schemasAnon = client.get("/api/v1/config/schemas")
            val wrongToken = client.get("/api/v1/config/schema") { header("Authorization", "Bearer not-a-token") }

            assertNotEquals(HttpStatusCode.OK, schemaAnon.status, "anonymous access must be refused")
            assertEquals(schemasAnon.status, schemaAnon.status, "the same refusal as /config/schemas")
            assertEquals(schemasAnon.status, wrongToken.status, "an unknown token is refused like a missing one")
        }

    @Test
    fun `S13 a read-only token is sufficient`(): Unit =
        testApplication {
            application { configureTestApp { configRoutes(ConfigRoutesTest.FakeSchemaService()) } }

            val response = client.get("/api/v1/config/schema") { header("Authorization", "Bearer $TEST_TOKEN") }

            assertEquals(HttpStatusCode.OK, response.status, "TEST_TOKEN holds only the READ capability")
        }
}
