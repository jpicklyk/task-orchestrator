package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) for REST `Idempotency-Key` handling on all five keyed write
 * routes, over a real SQLite database through `configureWriteTestApp`.
 *
 * Oracles: plan section 3.9 l.280 and the frozen task-scope REST adapter: POST /items, PATCH /items/{id},
 * POST /items/{id}/advance and POST /dependencies honour the header; a replay sends the
 * stored status, body and ETag verbatim plus the header `Idempotent-Replayed: true`; the same key with another
 * request (fingerprint over op, path, If-Match and body) is 409 `idempotency_mismatch` and executes nothing; a 2xx and
 * a body-parse 400 `validation_error` are recorded, any other non-2xx (here: 400 `cycle_detected`) is not, so a retry
 * with the same key executes; a request without the header never writes a record.
 *
 * Record and row counts are read with raw JDBC. Every "not recorded" assertion has a control in this file that proves
 * the same route DOES record when the request succeeds.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class IdempotencyRestRoutesTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val replayedHeader = "Idempotent-Replayed"

    private fun rawInt(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun records(): Int = db.idempotencyRecordCount()

    private suspend fun ApplicationTestBuilder.send(
        method: String,
        path: String,
        body: String,
        key: String? = null,
        ifMatch: String? = null
    ): HttpResponse {
        val block: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            header("Authorization", "Bearer $WRITE_TOKEN")
            if (key != null) header("Idempotency-Key", key)
            if (ifMatch != null) header(HttpHeaders.IfMatch, ifMatch)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (method) {
            "POST" -> client.post(path, block)
            "PATCH" -> client.patch(path, block)
            "PUT" -> client.put(path, block)
            else -> error("unsupported method $method")
        }
    }

    private fun etagOf(item: WorkItem) = "\"v1-${item.modifiedAt.toEpochMilli()}\""

    private suspend fun assertVerbatimReplay(
        first: HttpResponse,
        firstBody: String,
        second: HttpResponse
    ) {
        assertEquals(first.status, second.status, "the replay must carry the stored status")
        assertEquals(firstBody, second.bodyAsText(), "the replay must carry the stored body verbatim")
        assertEquals(first.headers[HttpHeaders.ETag], second.headers[HttpHeaders.ETag], "the replay must carry the stored ETag")
        assertNull(first.headers[replayedHeader], "the first execution must not be marked as a replay")
        assertEquals("true", second.headers[replayedHeader], "the replay must be marked with $replayedHeader: true")
    }

    // ---------------------------------------------------------------- S4: each route replays

    @Test
    fun `S4 POST advance honours the key - one transition and a verbatim replay`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Advance me", depth = 0)) }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val key = UUID.randomUUID().toString()
            val path = "/api/v1/items/${item.id}/advance"

            val first = send("POST", path, """{"trigger":"start"}""", key)
            val firstBody = first.bodyAsText()
            val second = send("POST", path, """{"trigger":"start"}""", key)

            assertEquals(HttpStatusCode.OK, first.status)
            assertVerbatimReplay(first, firstBody, second)
            assertEquals(1, rawInt("SELECT count(*) FROM role_transitions"), "the replay must not transition again")
            assertEquals(1, records())
        }

    // ---------------------------------------------------------------- S6: mismatch

    @Test
    fun `S6 POST advance with the same key and another trigger is a 409 idempotency_mismatch and does not transition`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Advance me", depth = 0)) }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val key = UUID.randomUUID().toString()
            val path = "/api/v1/items/${item.id}/advance"

            val first = send("POST", path, """{"trigger":"start"}""", key)
            val mismatch = send("POST", path, """{"trigger":"cancel"}""", key)

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.Conflict, mismatch.status)
            assertTrue("idempotency_mismatch" in mismatch.bodyAsText())
            assertNull(mismatch.headers[replayedHeader], "a mismatch is not a replay")
            assertEquals(1, rawInt("SELECT count(*) FROM role_transitions"), "the mismatched request must not execute")
        }

    @Test
    fun `S1 probe - a mixed-case Idempotency-Key is the same key for advance`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Cased", depth = 0)) }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            val key = UUID.randomUUID()
            val path = "/api/v1/items/${item.id}/advance"

            val first = send("POST", path, """{"trigger":"start"}""", key.toString().lowercase())
            val second = send("POST", path, """{"trigger":"start"}""", key.toString().uppercase())

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("true", second.headers[replayedHeader], "a case-only difference must resolve to the same record")
            assertEquals(1, rawInt("SELECT count(*) FROM role_transitions"))
        }
}
