package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.sqlite.assertNoOutsideUnitWrites
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent P5b REST tests (item c01d2e90): S4 (409 version_conflict), S5 (write fault -> 500 db_error with the
 * route's text, nothing written) and S9 (read fault -> 500 db_error, GET by id is NOT a 404).
 *
 * Oracles (PART A "legacy wire shapes at BASE", carry-in F4/F5):
 *  - GET /items fault: 500, `{"error":"db_error","message":"Database query failed"}`.
 *  - POST /items fault: 500, error `db_error`, message `Failed to create item`.
 *  - PATCH fault: 500, error `db_error`, message `Failed to update item`.
 *  - PATCH lost optimistic lock: 409, error `version_conflict`, message
 *    `Item was modified by another request; retry with a fresh If-Match ETag`.
 *  - F4: not-found only for a null row, so a GET /items/{id} fault is 500 (the route's text is not declared, so only
 *    status and the `db_error` code are asserted).
 * The REST runtime call order is NOT DECLARED; nothing here depends on it. Faults are real SQL (BEFORE-trigger
 * RAISE(ABORT, 'inj') for writes, a renamed table for reads) and each negative has a same-fixture control.
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

    private fun rawInt(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun etag(item: WorkItem) = "\"v${item.version}-${item.modifiedAt.toEpochMilli()}\""

    private fun errorOf(body: String) = Json.parseToJsonElement(body).jsonObject

    // ---------------------------------------------------------------- S5
    @Test
    fun `S5 POST items with a failing insert returns 500 db_error Failed to create item and writes no row`(): Unit =
        testApplication {
            rawExec("CREATE TRIGGER s5_post BEFORE INSERT ON work_items WHEN NEW.title = 'REST boom' BEGIN SELECT RAISE(ABORT, 'inj'); END")
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response =
                db.assertNoOutsideUnitWrites {
                    client.post("/api/v1/items") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"REST boom"}""")
                    }
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.InternalServerError, response.status, body)
            assertEquals("db_error", errorOf(body)["error"]!!.jsonPrimitive.content, body)
            assertEquals("Failed to create item", errorOf(body)["message"]!!.jsonPrimitive.content, body)
            assertEquals(0, rawInt("SELECT count(*) FROM work_items WHERE title = 'REST boom'"))

            rawExec("DROP TRIGGER s5_post")
            val healthy =
                client.post("/api/v1/items") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"REST boom"}""")
                }
            assertEquals(HttpStatusCode.Created, healthy.status, "control: the same request succeeds once the trigger is gone")
            assertEquals(1, rawInt("SELECT count(*) FROM work_items WHERE title = 'REST boom'"))
        }

    @Test
    fun `S5 PATCH items with a failing update returns 500 db_error Failed to update item and leaves the title`(): Unit =
        testApplication {
            val item =
                runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "REST patch original", depth = 0)) }
            rawExec(
                "CREATE TRIGGER s5_patch BEFORE UPDATE ON work_items WHEN NEW.title = 'REST patch boom' BEGIN SELECT RAISE(ABORT, 'inj'); END"
            )
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

            val response =
                db.assertNoOutsideUnitWrites {
                    client.patch("/api/v1/items/${item.id}") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        header(HttpHeaders.IfMatch, etag(item))
                        contentType(ContentType.Application.Json)
                        setBody("""{"title":"REST patch boom"}""")
                    }
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.InternalServerError, response.status, body)
            assertEquals("db_error", errorOf(body)["error"]!!.jsonPrimitive.content, body)
            assertEquals("Failed to update item", errorOf(body)["message"]!!.jsonPrimitive.content, body)
            assertEquals(
                "REST patch original",
                runBlocking { assertNotNull(db.repositoryProvider().workItemRepository().getById(item.id)).title }
            )

            rawExec("DROP TRIGGER s5_patch")
            val healthy =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"REST patch boom"}""")
                }
            assertEquals(HttpStatusCode.OK, healthy.status, "control: ${healthy.bodyAsText()}")
        }

    // ---------------------------------------------------------------- S4
    private class ConflictOnUpdateRepository(
        private val delegate: WorkItemRepository
    ) : WorkItemRepository by delegate {
        override suspend fun update(item: WorkItem): WorkItem? = throw VersionConflictException(item.id, item.version, item.version + 1)
    }

    private class ConflictProvider(
        private val delegate: RepositoryProvider
    ) : RepositoryProvider by delegate {
        private val conflicting = ConflictOnUpdateRepository(delegate.workItemRepository())

        override fun workItemRepository(): WorkItemRepository = conflicting
    }

    private fun Application.conflictApp(
        provider: RepositoryProvider,
        unitOfWork: UnitOfWork
    ) {
        val authConfig = makeWriteAuthConfig()
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    this.authConfig = authConfig
                    tokenEntries =
                        authConfig.tokens.mapValues { (_, principal) -> BearerTokenStore.TokenEntry(principal, expiresAt = null) }
                }
                itemRoutes(provider)
                itemWriteRoutes(
                    provider,
                    DegradedModePolicy.ACCEPT_CACHED,
                    IdempotencyCache(),
                    ToolExecutionContext(
                        provider,
                        statusLabelService = NoOpStatusLabelService,
                        unitOfWork = unitOfWork
                    ).advanceServiceFactory(),
                    unitOfWork
                )
            }
        }
    }

    @Test
    fun `S4 PATCH items maps a store VersionConflictException to 409 version_conflict with the 3x message`(): Unit =
        testApplication {
            val item = runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "REST conflict", depth = 0)) }
            val provider = ConflictProvider(db.repositoryProvider())
            application { conflictApp(provider, SqliteUnitOfWork(db.databaseManager, provider) { Instant.now() }) }

            val response =
                client.patch("/api/v1/items/${item.id}") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    header(HttpHeaders.IfMatch, etag(item))
                    contentType(ContentType.Application.Json)
                    setBody("""{"title":"REST conflict changed"}""")
                }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.Conflict, response.status, body)
            assertEquals("version_conflict", errorOf(body)["error"]!!.jsonPrimitive.content, body)
            assertEquals(
                "Item was modified by another request; retry with a fresh If-Match ETag",
                errorOf(body)["message"]!!.jsonPrimitive.content,
                body
            )
            assertEquals(
                "REST conflict",
                runBlocking { assertNotNull(db.repositoryProvider().workItemRepository().getById(item.id)).title }
            )
        }

    // ---------------------------------------------------------------- S9
    @Test
    fun `S9 GET items on a read fault returns 500 db_error Database query failed`(): Unit =
        testApplication {
            runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "REST read", depth = 0)) }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val healthy = client.get("/api/v1/items") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, healthy.status, "control: the list works before the fault")

            rawExec("ALTER TABLE work_items RENAME TO work_items_gone")
            val response = client.get("/api/v1/items") { header("Authorization", "Bearer $TEST_TOKEN") }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.InternalServerError, response.status, body)
            assertEquals("db_error", errorOf(body)["error"]!!.jsonPrimitive.content, body)
            assertEquals("Database query failed", errorOf(body)["message"]!!.jsonPrimitive.content, body)
        }

    @Test
    fun `S9 GET items by id on a read fault returns 500 db_error and never 404`(): Unit =
        testApplication {
            val item = runBlocking { db.repositoryProvider().workItemRepository().create(WorkItem(title = "REST read one", depth = 0)) }
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val healthy = client.get("/api/v1/items/${item.id}") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, healthy.status, "control: the item is readable before the fault")

            rawExec("ALTER TABLE work_items RENAME TO work_items_gone")
            val response = client.get("/api/v1/items/${item.id}") { header("Authorization", "Bearer $TEST_TOKEN") }

            val body = response.bodyAsText()
            assertEquals(HttpStatusCode.InternalServerError, response.status, "F4: not-found only for a null row, never for a fault: $body")
            assertEquals("db_error", errorOf(body)["error"]!!.jsonPrimitive.content, body)
        }

    @Test
    fun `S9 GET items by id for an id that does not exist is still 404 not a 500`(): Unit =
        testApplication {
            application { configureWriteTestApp(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }
            val response = client.get("/api/v1/items/${UUID.randomUUID()}") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
            assertNull(errorOf(response.bodyAsText())["error"]?.takeIf { it.jsonPrimitive.content == "db_error" })
        }
}
