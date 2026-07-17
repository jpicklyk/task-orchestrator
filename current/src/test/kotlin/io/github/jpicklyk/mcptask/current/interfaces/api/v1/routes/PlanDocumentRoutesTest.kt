package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the REST `PUT/GET /api/v1/roots/{rootId}/plans/{slug}` and
 * `GET /api/v1/roots/{rootId}/plans` routes registered by [planDocumentRoutes]. Reuses
 * [WRITE_TOKEN]/[TEST_TOKEN] from [ApiTestHelper] — same conventions as [ProjectConfigRoutesTest].
 */
fun Application.configurePlanDocumentTestApp(
    repo: DefaultRepositoryProvider,
    authConfig: ApiAuthConfig = makeWriteAuthConfig(),
) {
    install(ContentNegotiation) { json(McpJson) }
    install(SSE)
    routing {
        route("/api/v1") {
            install(ApiBearerAuth) {
                this.authConfig = authConfig
                tokenEntries =
                    (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, p) ->
                        BearerTokenStore.TokenEntry(p, expiresAt = null)
                    } ?: emptyMap()
            }
            planDocumentRoutes(repo)
        }
    }
}

private fun createRoot(
    repo: DefaultRepositoryProvider,
    title: String = "Project Root",
): WorkItem =
    runBlocking {
        (repo.workItemRepository().create(WorkItem(title = title, type = "project", depth = 0)) as Result.Success).data
    }

class PlanDocumentPutRouteTest {
    @Test
    fun `PUT roots rootId plans slug happy path returns 200 and persists the row`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("# Plan A\n")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains(root.id.toString()))
            assertTrue(body.contains("\"status\":\"pending\""))
            assertTrue(!body.contains("\"body\""), "PUT response should not echo the body back")

            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertTrue(persisted is Result.Success)
            assertEquals("# Plan A\n", (persisted as Result.Success).data?.body)
        }

    @Test
    fun `PUT roots rootId plans slug without WRITE_CONFIG capability returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("content")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertTrue((persisted as Result.Success).data == null)
        }

    @Test
    fun `PUT roots rootId plans slug with token scope lacking the root returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRoot = createRoot(repo, title = "Other Root")
            application { configurePlanDocumentTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(otherRoot.id))) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("content")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }

    @Test
    fun `PUT roots rootId plans slug to unknown root returns 404`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${UUID.randomUUID()}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("content")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `PUT roots rootId plans slug to a non-depth-0 root returns 422`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val parent = createRoot(repo, title = "Parent")
            val child =
                runBlocking {
                    (
                        repo.workItemRepository().create(
                            WorkItem(title = "Child", parentId = parent.id, depth = 1),
                        ) as Result.Success
                    ).data
                }
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.put("/api/v1/roots/${child.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("content")
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `PUT roots rootId plans slug over the size cap returns 413`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            val oversized = "x".repeat(65_537) // MAX_BODY_BYTES (65536) + 1
            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(oversized)
                }

            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertTrue((persisted as Result.Success).data == null)
        }

    @Test
    fun `PUT roots rootId plans slug re-push overwrites the PENDING row`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("v1")
            }
            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("v2")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertEquals("v2", (persisted as Result.Success).data?.body)
        }

    @Test
    fun `PUT roots rootId plans slug against an ADOPTED slug returns 409`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("v1")
            }
            val adopter =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "Adopter")) as Result.Success).data
                }
            runBlocking { repo.planDocumentRepository().markAdopted(root.id, "plan-a", adopter.id) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("v2")
                }

            assertEquals(HttpStatusCode.Conflict, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("adopted_conflict"))

            val persisted = runBlocking { repo.planDocumentRepository().get(root.id, "plan-a") }
            assertEquals("v1", (persisted as Result.Success).data?.body, "Rejected push must not overwrite the adopted row")
        }
}

class PlanDocumentGetRouteTest {
    @Test
    fun `GET roots rootId plans slug returns the stored document including body`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("# Plan A\n")
            }

            val response =
                client.get("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("# Plan A"))
        }

    @Test
    fun `GET roots rootId plans slug for a missing slug returns 404`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            val response =
                client.get("/api/v1/roots/${root.id}/plans/no-such-slug") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    @Test
    fun `GET roots rootId plans slug with token scope lacking the root returns 403`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            val otherRoot = createRoot(repo, title = "Other Root")
            application { configurePlanDocumentTestApp(repo, authConfig = makeWriteAuthConfig(scopeRootIds = setOf(otherRoot.id))) }

            val response =
                client.get("/api/v1/roots/${root.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
}

class PlanDocumentListRouteTest {
    @Test
    fun `GET roots rootId plans lists metadata-only summaries without bodies`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("a")
            }
            client.put("/api/v1/roots/${root.id}/plans/plan-b") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("b")
            }

            val response =
                client.get("/api/v1/roots/${root.id}/plans") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("plan-a") && body.contains("plan-b"))
            assertTrue(!body.contains("\"body\""), "list response must never include document bodies")
        }

    @Test
    fun `GET roots rootId plans filters by status query param`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val root = createRoot(repo)
            application { configurePlanDocumentTestApp(repo) }

            client.put("/api/v1/roots/${root.id}/plans/plan-a") {
                header("Authorization", "Bearer $WRITE_TOKEN")
                contentType(ContentType.Text.Plain)
                setBody("a")
            }
            val adopter =
                runBlocking {
                    (repo.workItemRepository().create(WorkItem(title = "Adopter")) as Result.Success).data
                }
            runBlocking { repo.planDocumentRepository().markAdopted(root.id, "plan-a", adopter.id) }

            val response =
                client.get("/api/v1/roots/${root.id}/plans?status=adopted") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("plan-a"))
        }
}

/**
 * Convergence test (task-scope acceptance criterion): the MCP `manage_plan_documents` tool's
 * `stash` operation and the REST `PUT /api/v1/roots/{rootId}/plans/{slug}` route both delegate to
 * [io.github.jpicklyk.mcptask.current.application.service.PlanDocumentService] — pushing the SAME
 * payload through each surface must produce identical content hashes + stored bytes.
 */
class PlanDocumentConvergenceTest {
    @Test
    fun `MCP tool stash and REST PUT converge on identical DB state for the same payload`(): Unit =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val rootViaTool = createRoot(repo, title = "Root Via Tool")
            val rootViaRest = createRoot(repo, title = "Root Via REST")
            val text = "# Shared Plan\n\nSame bytes either way.\n"

            val tool = ManagePlanDocumentsTool()
            val context = ToolExecutionContext(repo)
            runBlocking {
                tool.execute(
                    buildJsonObject {
                        put("operation", JsonPrimitive("stash"))
                        put("rootId", JsonPrimitive(rootViaTool.id.toString()))
                        put("slug", JsonPrimitive("plan-a"))
                        put("body", JsonPrimitive(text))
                    },
                    context,
                )
            }

            application { configurePlanDocumentTestApp(repo) }
            val response =
                client.put("/api/v1/roots/${rootViaRest.id}/plans/plan-a") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody(text)
                }
            assertEquals(HttpStatusCode.OK, response.status)

            val docViaTool = (runBlocking { repo.planDocumentRepository().get(rootViaTool.id, "plan-a") } as Result.Success).data
            val docViaRest = (runBlocking { repo.planDocumentRepository().get(rootViaRest.id, "plan-a") } as Result.Success).data
            assertEquals(docViaTool?.contentHash, docViaRest?.contentHash, "Content hashes must match for identical bytes")
            assertEquals(docViaTool?.body, docViaRest?.body, "Stored bytes must match")
        }
}
