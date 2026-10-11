package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent P5b REST tests (item c01d2e90): S9 (read fault -> 500, GET by id is NOT a 404). The S4/S5 write-route
 * cases left with `POST /items` and `PATCH /items/{id}` (Q18; `items_write` covers the write path).
 *
 * Oracles (PART A "legacy wire shapes at BASE", carry-in F4/F5):
 *  - GET /items/{id} fault (the items_read envelope since Q19): 500, never not_found.
 *  - F4: not-found only for a null row, so a GET /items/{id} fault is 500.
 * The REST runtime call order is NOT DECLARED; nothing here depends on it. The fault is real SQL (a renamed table)
 * and each negative has a same-fixture control.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bRouteFaultTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db

    private fun rawExec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    // ---------------------------------------------------------------- S9
    @Test
    fun `S9 GET items by id on a read fault returns 500 and never 404`(): Unit =
        testApplication {
            val item = runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "REST read one", depth = 0)) }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val healthy = client.get("/api/v1/items/${item.id}") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, healthy.status, "control: the item is readable before the fault")

            rawExec("ALTER TABLE work_items RENAME TO work_items_gone")
            val response = client.get("/api/v1/items/${item.id}") { header("Authorization", "Bearer $TEST_TOKEN") }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.InternalServerError, response.status, "F4: not-found only for a null row, never for a fault: $body")
            assertFalse("not_found" in body, "a store fault is never answered as not_found: $body") // 4.0 envelope since Q19
        }

    @Test
    fun `S9 GET items by id for an id that does not exist is still 404 not a 500`(): Unit =
        testApplication {
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val response = client.get("/api/v1/items/${UUID.randomUUID()}") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
            assertTrue("\"not_found\"" in response.bodyAsText(), "the items_read get envelope names not_found: ${response.bodyAsText()}")
        }
}
