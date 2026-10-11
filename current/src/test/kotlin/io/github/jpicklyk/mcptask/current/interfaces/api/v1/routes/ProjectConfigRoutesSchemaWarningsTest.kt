package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.configYaml
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises `PUT /api/v1/roots/{rootId}/config`'s `schemaWarnings` field -- the additive array
 * surfacing
 * [io.github.jpicklyk.mcptask.current.application.service.ConfigPushResult.Success.schemaWarnings]
 * on the REST response, present only when non-empty. The push still returns 200 with an ETag and
 * stores the document.
 *
 * Independent test authorship per the `needs-test-author` trait: written against the item's
 * `test-plan` note oracles and the route's public HTTP contract, without reading the
 * implementer's own tests or notes. Reuses [configureProjectConfigTestApp] and
 * `SqliteTestDatabase.repositoryProvider()` from [ProjectConfigPutRouteTest]'s file, which already covers the
 * rest of the PUT contract -- this file focuses solely on `schemaWarnings`.
 */
class ProjectConfigRoutesSchemaWarningsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun createRoot(repo: DefaultRepositoryProvider): WorkItem =
        runBlocking {
            (
                repo.workItemRepository().create(
                    WorkItem(title = "Project Root", type = "project", depth = 0)
                )!!
            )
        }

    // ──────────────────────────────────────────────
    // S11 — invalid role: 200, schemaWarnings present, ETag still set
    // ──────────────────────────────────────────────

    @Test
    fun `S11 PUT with an invalid role returns 422 parse_error with the error path and stores nothing`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: not-a-role
                """.trimIndent()

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(yaml)
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("parse_error"), "the error code is parse_error: $body")
            assertTrue(body.contains("/work_item_schemas/feature-task/notes/0/role"), "details.errors names the path: $body")
            assertTrue(body.contains("not-a-role"), "the reason names the bad value: $body")

            val persisted = runBlocking { repo.configStore().get(root.id) }
            assertNull(persisted, "a rejected push stores nothing")
        }

    // ──────────────────────────────────────────────
    // S11b — an advisory finding: 200, schemaWarnings present, ETag still set
    // ──────────────────────────────────────────────

    @Test
    fun `S11b PUT with an advisory finding returns 200 with the warning text in schemaWarnings and keeps the ETag`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    seats:
                      - { name: impl, phase: work, enters: true }
                    notes:
                      - key: spec
                        role: work
                        required: true
                """.trimIndent()

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(yaml)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertNotNull(response.headers[HttpHeaders.ETag], "a push with only an advisory finding must still succeed and set an ETag")

            val body = response.bodyAsText()
            assertTrue(body.contains("schemaWarnings"), "response should surface schemaWarnings: $body")
            assertTrue(body.contains("feature-task") && body.contains("spec"), "schemaWarnings text should name the schema and note: $body")

            val persisted = runBlocking { repo.configStore().get(root.id) }
            assertEquals(yaml, persisted?.configYaml, "the document must be stored despite the advisory finding")
        }

    // ──────────────────────────────────────────────
    // S1 — all-valid schema: schemaWarnings omitted entirely
    // ──────────────────────────────────────────────

    @Test
    fun `S1 PUT with an all-valid schema omits schemaWarnings from the response`(): Unit =
        testApplication {
            val repo = db.repositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo, unitOfWork = db.unitOfWork()) }

            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: true
                """.trimIndent()

            val response =
                client.put("/api/v1/roots/${root.id}/config") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(yaml)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("schemaWarnings"), "schemaWarnings must be omitted entirely when empty: $body")
        }
}
