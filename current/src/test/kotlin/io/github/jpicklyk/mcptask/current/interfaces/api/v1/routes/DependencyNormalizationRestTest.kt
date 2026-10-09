package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): `POST /api/v1/dependencies` after
 * dependency direction normalization. Scenario S10 of the frozen test-plan, plus cross-surface probes.
 *
 * Oracles (frozen plan 3.4 and task-scope item 4): REST input stays `blocks|relates_to`; a cycle over the normalized
 * blocker-to-blocked graph is `400 cycle_detected`; a duplicate of the normalized (from, to, type) is
 * `409 duplicate_dependency`; both reject the whole request and store nothing; `relates_to` never cycles; the 3.x wire
 * shapes stay (P16 owns the error catalog). A row written through the MCP tool with the IS_BLOCKED_BY alias is stored
 * as BLOCKS with the ends swapped, so the REST policy must see it that way.
 *
 * Harness: [configureWriteTestApp] (WriteRoutesTest.kt) with the SQLite fixture, as `DependencyDuplicateRouteTest` does.
 *
 * EXISTING-SURFACE: every call goes through the route.
 */
class DependencyNormalizationRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private suspend fun makeItem(
        repo: io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider,
        title: String
    ): WorkItem = repo.workItemRepository().create(WorkItem(title = title, depth = 0))

    private suspend fun mcpCreate(
        repo: io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider,
        from: UUID,
        to: UUID,
        type: String
    ) {
        val context = ToolExecutionContext(repo, unitOfWork = db.unitOfWork())
        val spec =
            buildJsonObject {
                put("fromItemId", JsonPrimitive(from.toString()))
                put("toItemId", JsonPrimitive(to.toString()))
                put("type", JsonPrimitive(type))
            }
        val result =
            ManageDependenciesTool().execute(
                JsonObject(mapOf("operation" to JsonPrimitive("create"), "dependencies" to JsonArray(listOf(spec)))),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "setup failed: $result")
    }

    // ------------------------------------------------------------------
    // S10: REST cycle detection over normalized edges
    // ------------------------------------------------------------------

    @Test
    fun `S10 POST blocks A to B then blocks B to A returns 400 cycle_detected and relates_to B to A returns 201`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b) = runBlocking { makeItem(repo, "A") to makeItem(repo, "B") }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val first =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

            val reverse =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"blocks"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, reverse.status, reverse.bodyAsText())
            assertTrue(reverse.bodyAsText().contains("cycle_detected"), reverse.bodyAsText())

            val relates =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"relates_to"}""")
                }
            assertEquals(HttpStatusCode.Created, relates.status, relates.bodyAsText())

            val rows = runBlocking { repo.dependencyRepository().findByItemId(a.id) }
            assertEquals(
                setOf(
                    Triple(a.id, b.id, DependencyType.BLOCKS),
                    Triple(b.id, a.id, DependencyType.RELATES_TO)
                ),
                rows.map { Triple(it.fromItemId, it.toItemId, it.type) }.toSet()
            )
            assertEquals(2, rows.size, "the rejected cycle edge was not stored")
        }

    @Test
    fun `S10 POST closing a three-edge chain returns 400 cycle_detected and stores nothing for the new edge`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b, c) = runBlocking { Triple(makeItem(repo, "A"), makeItem(repo, "B"), makeItem(repo, "C")) }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }
            for ((from, to) in listOf(a to b, b to c)) {
                val ok =
                    client.post("/api/v1/dependencies") {
                        header("Authorization", "Bearer $WRITE_TOKEN")
                        contentType(ContentType.Application.Json)
                        setBody("""{"fromItemId":"${from.id}","toItemId":"${to.id}","type":"blocks"}""")
                    }
                assertEquals(HttpStatusCode.Created, ok.status, ok.bodyAsText())
            }

            val closing =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${c.id}","toItemId":"${a.id}","type":"blocks"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, closing.status, closing.bodyAsText())
            assertTrue(closing.bodyAsText().contains("cycle_detected"), closing.bodyAsText())
            val rows = runBlocking { repo.dependencyRepository().findByItemId(a.id) }
            assertEquals(1, rows.size, "only a -> b touches a: $rows")
        }

    // ------------------------------------------------------------------
    // Cross-surface probes: a row written through the MCP alias is seen in its stored direction
    // ------------------------------------------------------------------

    @Test
    fun `S10 probe REST blocks restating a row the MCP tool wrote as IS_BLOCKED_BY returns 409 duplicate_dependency`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b) = runBlocking { makeItem(repo, "A") to makeItem(repo, "B") }
            runBlocking { mcpCreate(repo, a.id, b.id, "IS_BLOCKED_BY") } // stored b BLOCKS a
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val restated =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${b.id}","toItemId":"${a.id}","type":"blocks"}""")
                }

            assertEquals(HttpStatusCode.Conflict, restated.status, restated.bodyAsText())
            assertTrue(restated.bodyAsText().contains("duplicate_dependency"), restated.bodyAsText())
            assertEquals(1, runBlocking { repo.dependencyRepository().findByItemId(a.id) }.size)
        }

    @Test
    fun `S10 probe REST blocks reversing a row the MCP tool wrote as IS_BLOCKED_BY returns 400 cycle_detected`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b) = runBlocking { makeItem(repo, "A") to makeItem(repo, "B") }
            runBlocking { mcpCreate(repo, a.id, b.id, "IS_BLOCKED_BY") } // stored b BLOCKS a
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val reversed =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, reversed.status, reversed.bodyAsText())
            val body = reversed.bodyAsText()
            assertTrue(body.contains("cycle_detected"), body)
            assertFalse(body.contains("duplicate_dependency"), "a reversal is a cycle, not a duplicate: $body")
            assertEquals(1, runBlocking { repo.dependencyRepository().findByItemId(a.id) }.size)
        }

    @Test
    fun `S10 probe replaying an identical blocks POST is 409 duplicate_dependency regardless of unblockAt`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val (a, b) = runBlocking { makeItem(repo, "A") to makeItem(repo, "B") }
            application { configureWriteTestApp(repo, unitOfWork = db.unitOfWork()) }

            val first =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks","unblockAt":"work"}""")
                }
            assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

            val replay =
                client.post("/api/v1/dependencies") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"fromItemId":"${a.id}","toItemId":"${b.id}","type":"blocks","unblockAt":"review"}""")
                }

            assertEquals(HttpStatusCode.Conflict, replay.status, replay.bodyAsText())
            assertTrue(replay.bodyAsText().contains("duplicate_dependency"), replay.bodyAsText())
            val row = runBlocking { repo.dependencyRepository().findByItemId(a.id) }.single()
            assertEquals("work", row.unblockAt, "the stored threshold is not upgraded")
        }
}
