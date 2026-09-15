package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises `PUT /api/v1/roots/{rootId}/config`'s `schemaWarnings` field -- the additive array
 * surfacing
 * [io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult.Success.schemaWarnings]
 * on the REST response, present only when non-empty. The push still returns 200 with an ETag and
 * stores the document.
 *
 * Independent test authorship per the `needs-test-author` trait: written against the item's
 * `test-plan` note oracles and the route's public HTTP contract, without reading the
 * implementer's own tests or notes. Reuses [configureProjectConfigTestApp] and
 * [buildH2RepositoryProvider] from [ProjectConfigPutRouteTest]'s file, which already covers the
 * rest of the PUT contract -- this file focuses solely on `schemaWarnings`.
 */
class ProjectConfigRoutesSchemaWarningsTest {
    private fun createRoot(repo: DefaultRepositoryProvider): WorkItem =
        runBlocking {
            (
                repo.workItemRepository().create(
                    WorkItem(title = "Project Root", type = "project", depth = 0)
                ) as Result.Success
            ).data
        }

    // ──────────────────────────────────────────────
    // S11 — invalid role: 200, schemaWarnings present, ETag still set
    // ──────────────────────────────────────────────

    @Test
    fun `S11 PUT with an invalid role returns 200 with the warning text in schemaWarnings and keeps the ETag`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

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

            assertEquals(HttpStatusCode.OK, response.status)
            val etag = response.headers[HttpHeaders.ETag]
            assertNotNull(etag, "a push with only a soft schema warning must still succeed and set an ETag")

            val body = response.bodyAsText()
            assertTrue(body.contains("schemaWarnings"), "response should surface schemaWarnings: $body")
            assertTrue(body.contains("feature-task"), "schemaWarnings text should name the schema: $body")
            assertTrue(body.contains("not-a-role"), "schemaWarnings text should name the bad value: $body")

            val persisted = runBlocking { repo.projectConfigRepository().get(root.id) }
            assertEquals(
                yaml,
                (persisted as Result.Success).data?.configYaml,
                "the document must be stored despite the soft warning"
            )
        }

    // ──────────────────────────────────────────────
    // S1 — all-valid schema: schemaWarnings omitted entirely
    // ──────────────────────────────────────────────

    @Test
    fun `S1 PUT with an all-valid schema omits schemaWarnings from the response`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configureProjectConfigTestApp(repo) }

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
