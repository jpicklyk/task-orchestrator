package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope` / `task-scope-addendum` notes on item
 * `79cd4f0c` (A1, stage A1c) -- scenarios S11 (happy path: body equals `query_items(schema)`'s
 * `data`) and S11b (error envelope), for the new `GET /api/v1/items/{id}/schema` route registered
 * inside [itemGateRoutes].
 *
 * Harness mirrors [ItemGateRouteTest] (route registration, id-handling probes) and
 * [ConfigUnavailableRoutesTest] (the cold-cache 503 fixture, `FailableProjectConfigRepository`
 * pattern duplicated here under this file's own names per the test-author scope rule -- no shared
 * harness file is declared for A1c). Every [ToolExecutionContext] built here uses the SAME
 * construction the REST contract cites: `ToolExecutionContext(repo, schemaService,
 * perRootConfigService = PerRootConfigService(repo.projectConfigRepository())).configResolver`.
 */
class ItemSchemaRouteTest {
    // ─── S11 -- happy path: route body == query_items(schema, itemId).data ────

    @Test
    fun `S11 GET items id schema body equals query_items schema data for a seat-aware per-root item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Schema S11 Root", depth = 0)).getOrNull()!!
                    val i =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(
                                    title = "Schema S11 seat-aware",
                                    type = "sq-seat",
                                    role = Role.WORK,
                                    parentId = r.id,
                                    rootId = r.id,
                                    depth = 1,
                                ),
                            ).getOrNull()!!
                    repo.projectConfigRepository().upsert(r.id, SEAT_AWARE_PER_ROOT_YAML).getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    i
                }
            application { configureSchemaTestApp(repo) }

            val response =
                client.get("/api/v1/items/${item.id}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val routeBody = parseObj(response.bodyAsText())
            val expected = queryItemsSchemaData(repo, item.id)

            assertEquals(expected, routeBody, "route body must equal query_items(schema,itemId).data exactly")
            // Sanity: the fixture is actually seat-aware -- proves this isn't a vacuous equality of
            // two empty objects.
            assertTrue(routeBody.containsKey("seats"), "sanity: fixture item must be seat-aware: $routeBody")
        }

    @Test
    fun `S11 GET items id schema body equals query_items schema data for a seat-less per-root item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val r = repo.workItemRepository().create(WorkItem(title = "Schema S11 Root Seatless", depth = 0)).getOrNull()!!
                    val i =
                        repo
                            .workItemRepository()
                            .create(
                                WorkItem(
                                    title = "Schema S11 seat-less",
                                    type = "sq-plain",
                                    role = Role.QUEUE,
                                    parentId = r.id,
                                    rootId = r.id,
                                    depth = 1,
                                ),
                            ).getOrNull()!!
                    repo.projectConfigRepository().upsert(r.id, SEATLESS_PER_ROOT_YAML).getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    i
                }
            application { configureSchemaTestApp(repo) }

            val response =
                client.get("/api/v1/items/${item.id}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val routeBody = parseObj(response.bodyAsText())
            val expected = queryItemsSchemaData(repo, item.id)

            assertEquals(expected, routeBody, "route body must equal query_items(schema,itemId).data exactly")
            assertFalse(routeBody.containsKey("seats"), "sanity: fixture item must be seat-less: $routeBody")
        }

    // ─── S11b -- error envelope ────────────────────────────────────────────────

    @Test
    fun `S11b GET items id schema returns 400 bad_request for a malformed id`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureSchemaTestApp(repo) }

            val response =
                client.get("/api/v1/items/not-a-uuid/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun `S11b GET items id schema returns 400 bad_request for an 8-hex prefix of a real item id (no hex-prefix resolution)`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking { repo.workItemRepository().create(WorkItem(title = "Schema S11b hex", depth = 0)).getOrNull()!! }
            application { configureSchemaTestApp(repo) }

            val hexPrefix =
                item.id
                    .toString()
                    .replace("-", "")
                    .substring(0, 8)
            val response =
                client.get("/api/v1/items/$hexPrefix/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains("bad_request"))
        }

    @Test
    fun `S11b GET items id schema returns 404 not_found for a random UUID`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            application { configureSchemaTestApp(repo) }

            val response =
                client.get("/api/v1/items/${UUID.randomUUID()}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("not_found"))
        }

    @Test
    fun `S11b GET items id schema returns 404 no_schema for a schema-free item`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    repo
                        .workItemRepository()
                        .create(WorkItem(title = "Schema S11b free", type = "no-schema-anywhere", depth = 0))
                        .getOrNull()!!
                }
            application { configureSchemaTestApp(repo) }

            val response =
                client.get("/api/v1/items/${item.id}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("no_schema"), "body: ${response.bodyAsText()}")
        }

    @Test
    fun `S11b GET items id schema returns 403 scope_forbidden for a token scoped to a different root`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val outsideScopeItem =
                runBlocking { repo.workItemRepository().create(WorkItem(title = "Schema S11b scope", depth = 0)).getOrNull()!! }
            val authConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
            application { configureSchemaTestApp(repo, authConfig = authConfig) }

            val response =
                client.get("/api/v1/items/${outsideScopeItem.id}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("scope_forbidden"))
        }

    @Test
    fun `S11b GET items id schema returns 503 config_unavailable on a cold per-root config read failure`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val item =
                runBlocking {
                    val r = h2.workItemRepository().create(WorkItem(title = "Schema S11b 503 root", depth = 0)).getOrNull()!!
                    // A real row must exist so getFingerprint succeeds first -- resolve() only
                    // reaches the .get() read (which failGet intercepts) once the fingerprint check
                    // has NOT short-circuited on Success(null)/absence. Mirrors
                    // ConfigUnavailableRoutesTest's S11 fixture for GET /items/{id}/gate.
                    h2.projectConfigRepository().upsert(r.id, "work_item_schemas:\n  t:\n    notes: []\n")
                    h2
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "Schema S11b 503 child",
                                type = "t",
                                role = Role.WORK,
                                parentId = r.id,
                                rootId = r.id,
                                depth = 1
                            ),
                        ).getOrNull()!!
                }
            val failable = SchemaRouteFailableProjectConfigRepository(h2.projectConfigRepository())
            failable.failGet = true
            val provider = SchemaRouteFailableRepositoryProvider(h2, failable)
            application { configureSchemaTestApp(provider) }

            val response =
                client.get("/api/v1/items/${item.id}/schema") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertTrue(response.bodyAsText().contains("config_unavailable"))
        }

    companion object {
        private const val SEAT_AWARE_PER_ROOT_YAML = """
work_item_schemas:
  sq-seat:
    seats:
      - { name: implementer, phase: work, enters: true }
    notes:
      - key: impl-notes
        role: work
        required: true
        seat: implementer
"""

        private const val SEATLESS_PER_ROOT_YAML = """
work_item_schemas:
  sq-plain:
    notes:
      - key: plan
        role: queue
        required: true
"""
    }
}

// ───────────────────────────── Shared test-file fixtures ─────────────────────────────

/** No global schema for any type -- mirrors [ConfigUnavailableRoutesTest]'s own private fixture. */
private object ItemSchemaRouteNoGlobalSchemaService : WorkItemSchemaService {
    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
}

private fun buildSchemaRouteContext(repo: RepositoryProvider): ToolExecutionContext =
    ToolExecutionContext(
        repo,
        ItemSchemaRouteNoGlobalSchemaService,
        perRootConfigService = PerRootConfigService(repo.projectConfigRepository()),
    )

private fun Application.configureSchemaTestApp(
    repo: RepositoryProvider,
    authConfig: ApiAuthConfig = makeTestAuthConfig(),
) {
    configureTestApp(authConfig) {
        itemGateRoutes(repo, buildSchemaRouteContext(repo).configResolver)
    }
}

private fun parseObj(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

/** The SAME builder the route is documented to reuse: `query_items(operation="schema", itemId=...)`'s `data`. */
private fun queryItemsSchemaData(
    repo: RepositoryProvider,
    itemId: UUID,
): JsonObject {
    val context = buildSchemaRouteContext(repo)
    val params =
        buildJsonObject {
            put("operation", JsonPrimitive("schema"))
            put("itemId", JsonPrimitive(itemId.toString()))
        }
    val result = runBlocking { QueryItemsTool().execute(params, context) }
    val obj = result as JsonObject
    assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected query_items(schema) success: $obj")
    return obj["data"] as JsonObject
}

/** Mirrors [ConfigUnavailableRoutesTest]'s `FailableProjectConfigRepository`, named for this file's own scope. */
private class SchemaRouteFailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository,
) : ProjectConfigRepository by delegate {
    @Volatile var failGet: Boolean = false

    override suspend fun get(rootItemId: UUID) = if (failGet) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
}

private class SchemaRouteFailableRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failable: SchemaRouteFailableProjectConfigRepository,
) : RepositoryProvider by delegate {
    override fun projectConfigRepository(): ProjectConfigRepository = failable
}
