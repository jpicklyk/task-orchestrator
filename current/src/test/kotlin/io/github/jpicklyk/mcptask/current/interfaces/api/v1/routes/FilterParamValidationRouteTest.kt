package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F-013: a supplied but unparsable filter value is rejected with 400 validation_error naming
 * the parameter, never silently dropped (which widened the result set).
 *
 * The item list, tree and search filters moved to `items_read` in Q19, whose request decoder rejects a bad value
 * as `invalid_request` on its field; only the 3.x transitions route remains here.
 */
class FilterParamValidationRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private suspend fun HttpClient.getAs(path: String) = get(path) { header("Authorization", "Bearer $TEST_TOKEN") }

    private suspend fun assertValidation400(
        client: HttpClient,
        path: String,
        param: String,
    ) {
        val response = client.getAs(path)
        assertEquals(HttpStatusCode.BadRequest, response.status, path)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("validation_error", json["error"]?.jsonPrimitive?.content, "$path -> $json")
        assertTrue(json["message"]!!.jsonPrimitive.content.contains(param), "$path -> $json")
    }

    @Test
    fun `GET transitions rejects invalid since and keeps the default when absent or blank`() =
        testApplication {
            val repo = db.repositoryProvider()
            application { configureTestApp { transitionRoutes(repo) } }

            assertValidation400(client, "/api/v1/transitions?since=garbage", "since")
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions").status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions?since=").status)
            assertEquals(HttpStatusCode.OK, client.getAs("/api/v1/transitions?since=2999-01-01T00:00:00Z").status)
        }
}
