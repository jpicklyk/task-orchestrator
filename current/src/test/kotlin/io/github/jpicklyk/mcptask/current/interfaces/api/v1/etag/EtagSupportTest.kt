package io.github.jpicklyk.mcptask.current.interfaces.api.v1.etag

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.buildH2RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.configureTestApp
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.itemRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test
    fun `etagFor produces deterministic string from epoch millis`() {
        val ts = Instant.ofEpochMilli(1716910000000L)
        val etag1 = etagFor(ts)
        val etag2 = etagFor(ts)
        assertEquals(etag1, etag2)
        assertTrue(etag1.contains("1716910000000"), "ETag should contain millis: $etag1")
        assertTrue(etag1.startsWith("\"v1-"), "ETag should start with \"v1-: $etag1")
        assertTrue(etag1.endsWith("\""), "ETag should end with \": $etag1")
    }

    @Test
    fun `etagFor changes when modifiedAt changes`() {
        val ts1 = Instant.ofEpochMilli(1000L)
        val ts2 = Instant.ofEpochMilli(2000L)
        assertNotEquals(etagFor(ts1), etagFor(ts2))
    }

    @Test
    fun `same item read twice returns identical ETag`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "ETag stable", depth = 0)).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val r1 = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            val r2 = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, r1.status)
            assertEquals(HttpStatusCode.OK, r2.status)
            val etag1 = r1.headers[HttpHeaders.ETag]
            val etag2 = r2.headers[HttpHeaders.ETag]
            assertEquals(etag1, etag2, "ETags should be identical across reads")
        }

    @Test
    fun `If-None-Match with current ETag returns 304`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "ETag cache test", depth = 0)).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val r1 = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
            assertEquals(HttpStatusCode.OK, r1.status)
            val etag = r1.headers[HttpHeaders.ETag] ?: error("ETag header missing")

            val r2 = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
                header(HttpHeaders.IfNoneMatch, etag)
            }
            assertEquals(HttpStatusCode.NotModified, r2.status)
        }

    @Test
    fun `If-None-Match with stale ETag returns 200`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item = runBlocking {
                repo.workItemRepository().create(WorkItem(title = "Stale ETag test", depth = 0)).getOrNull()!!
            }
            application {
                configureTestApp { itemRoutes(repo) }
            }
            val staleEtag = "\"v1-0000000000000\""
            val response = client.get("/api/v1/items/${item.id}") {
                header("Authorization", "Bearer $TEST_TOKEN")
                header(HttpHeaders.IfNoneMatch, staleEtag)
            }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("Stale ETag test"), "Expected item in response: $body")
        }
}
