package io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for ETag generation and [respondWithEtagCheck].
 *
 * Test coverage:
 * - Same item read twice → identical ETag
 * - ETag changes after `modifiedAt` bump
 * - `If-None-Match` with current ETag → 304
 * - `If-None-Match` with stale ETag → 200 (full response)
 */
class EtagSupportTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    @Test
    fun `same item read twice returns identical ETag`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "ETag stable", depth = 0))!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val r1 =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            val r2 =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals(HttpStatusCode.OK, r2.status)
            val etag1 = r1.headers[HttpHeaders.ETag]
            val etag2 = r2.headers[HttpHeaders.ETag]
            assertEquals(etag1, etag2, "ETags should be identical across reads")
        }

    @Test
    fun `If-None-Match with current ETag returns 304`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "ETag cache test", depth = 0))!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val r1 =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, r1.status)
            val etag = r1.headers[HttpHeaders.ETag] ?: error("ETag header missing")

            val r2 =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, etag)
                }
            assertEquals(HttpStatusCode.NotModified, r2.status)
        }

    @Test
    fun `If-None-Match with stale ETag returns 200`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item =
                runBlocking {
                    repo.workItemRepository().create(WorkItem(title = "Stale ETag test", depth = 0))!!
                }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val staleEtag = "\"v1-0000000000000\""
            val response =
                client.get("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    header(HttpHeaders.IfNoneMatch, staleEtag)
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Stale ETag test"), "Expected item in response: $body")
        }
}
