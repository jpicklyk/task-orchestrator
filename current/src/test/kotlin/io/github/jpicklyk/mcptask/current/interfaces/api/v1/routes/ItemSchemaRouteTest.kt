package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
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
 * SF3 rewrite: every scenario is wired through the REAL [installRestApiRoutes] (never `itemGateRoutes`
 * called directly), following [SeatGateParityRestTest]'s / [EffectiveConfigRoutesTest]'s
 * `ServerComposition.build()` + `ContentNegotiation` + `installRestApiRoutes` production topology --
 * per the task-scope-addendum's Harness rule ("never a hand-built TEC/route replica"). S11 and the
 * 400/404/403 legs of S11b build the FULL real composition (global file layer, real per-root push via
 * `projectConfigRepository().upsert`, real `PerRootConfigService`) and compute the `expected` body from
 * the SAME `composition.toolContext` the route uses. The 503 leg needs a per-root config READ to fail
 * cold, which requires substituting a failing `ProjectConfigRepository`; `ServerComposition`'s public
 * constructor takes only a `DatabaseManager` with no seam to inject a custom `RepositoryProvider`
 * (confirmed from this item's supplied declarations -- `ServerComposition(appConfig, databaseManager,
 * shutdownCoordinator, logger)`), so that leg instead builds a `ToolExecutionContext` directly over the
 * failing provider and still calls the REAL `installRestApiRoutes` with it (never `itemGateRoutes`
 * standalone) -- the same substitution shape `ConfigUnavailableRoutesTest` uses for the sibling
 * `/items/{id}/gate` 503 case, just routed through the real top-level entry point instead of the bare
 * route function.
 */
class ItemSchemaRouteTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // ─── Shared composition wiring ─────────────────────────────────────────

    private fun materializeEmptyGlobalConfig(tempDir: Path) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(tempDir: Path): CompositionResult {
        materializeEmptyGlobalConfig(tempDir)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(
            appConfig = appConfig,
            databaseManager = db.databaseManager,
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun tokenEntriesFor(
        authConfig: ApiAuthConfig,
    ): Map<io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, principal) ->
            BearerTokenStore.TokenEntry(principal, expiresAt = null)
        } ?: emptyMap()

    private fun Application.configureProductionSchemaApp(
        composition: CompositionResult,
        authConfig: ApiAuthConfig = makeTestAuthConfig(),
    ) {
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntriesFor(authConfig),
            allowQueryToken = false,
            serverName = "item-schema-route-test",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
        )
    }

    private fun parseObj(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    /** The SAME builder the route is documented to reuse: `query_items(operation="schema", itemId=...)`'s `data`. */
    private fun queryItemsSchemaData(
        toolContext: ToolExecutionContext,
        itemId: UUID,
    ): JsonObject {
        val params =
            buildJsonObject {
                put("operation", JsonPrimitive("schema"))
                put("itemId", JsonPrimitive(itemId.toString()))
            }
        val result = runBlocking { QueryItemsTool().execute(params, toolContext) }
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "expected query_items(schema) success: $obj")
        return obj["data"] as JsonObject
    }

    // ─── S11 -- happy path: route body == query_items(schema, itemId).data ────

    @Test
    fun `S11 GET items id schema body equals query_items schema data for a seat-aware per-root item`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val item =
            runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Schema S11 Root", depth = 0))!!
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
                        )!!
                repo.projectConfigRepository().upsert(r.id, SEAT_AWARE_PER_ROOT_YAML)
                    ?: error("fixture: per-root config upsert failed")
                i
            }
        application { configureProductionSchemaApp(composition) }

        val response =
            client.get("/api/v1/items/${item.id}/schema") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val routeBody = parseObj(response.bodyAsText())
        val expected = queryItemsSchemaData(composition.toolContext, item.id)

        assertEquals(expected, routeBody, "route body must equal query_items(schema,itemId).data exactly")
        // Sanity: the fixture is actually seat-aware -- proves this isn't a vacuous equality of
        // two empty objects.
        assertTrue(routeBody.containsKey("seats"), "sanity: fixture item must be seat-aware: $routeBody")
    }

    @Test
    fun `S11 GET items id schema body equals query_items schema data for a seat-less per-root item`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val item =
            runBlocking {
                val r = repo.workItemRepository().create(WorkItem(title = "Schema S11 Root Seatless", depth = 0))!!
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
                        )!!
                repo.projectConfigRepository().upsert(r.id, SEATLESS_PER_ROOT_YAML)
                    ?: error("fixture: per-root config upsert failed")
                i
            }
        application { configureProductionSchemaApp(composition) }

        val response =
            client.get("/api/v1/items/${item.id}/schema") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
        assertEquals(HttpStatusCode.OK, response.status)
        val routeBody = parseObj(response.bodyAsText())
        val expected = queryItemsSchemaData(composition.toolContext, item.id)

        assertEquals(expected, routeBody, "route body must equal query_items(schema,itemId).data exactly")
        assertFalse(routeBody.containsKey("seats"), "sanity: fixture item must be seat-less: $routeBody")
    }

    // ─── S11b -- error envelope ────────────────────────────────────────────────

    @Test
    fun `S11b GET items id schema returns 400 bad_request for a malformed id`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        application { configureProductionSchemaApp(composition) }

        val response =
            client.get("/api/v1/items/not-a-uuid/schema") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("bad_request"))
    }

    @Test
    fun `S11b GET items id schema returns 400 bad_request for an 8-hex prefix of a real item id (no hex-prefix resolution)`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        val item =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(WorkItem(title = "Schema S11b hex", depth = 0))!!
            }
        application { configureProductionSchemaApp(composition) }

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
    fun `S11b GET items id schema returns 404 not_found for a random UUID`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        application { configureProductionSchemaApp(composition) }

        val response =
            client.get("/api/v1/items/${UUID.randomUUID()}/schema") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not_found"))
    }

    @Test
    fun `S11b GET items id schema returns 404 no_schema for a schema-free item`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        val item =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(WorkItem(title = "Schema S11b free", type = "no-schema-anywhere", depth = 0))!!
            }
        application { configureProductionSchemaApp(composition) }

        val response =
            client.get("/api/v1/items/${item.id}/schema") {
                header("Authorization", "Bearer $TEST_TOKEN")
            }
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("no_schema"), "body: ${response.bodyAsText()}")
    }

    @Test
    fun `S11b GET items id schema returns 403 scope_forbidden for a token scoped to a different root`(
        @TempDir tempDir: Path,
    ) = testApplication {
        val composition = buildComposition(tempDir)
        val outsideScopeItem =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(WorkItem(title = "Schema S11b scope", depth = 0))!!
            }
        val authConfig = makeTestAuthConfig(scopeRootIds = setOf(UUID.randomUUID()))
        application { configureProductionSchemaApp(composition, authConfig = authConfig) }

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
            val sqlite = db.repositoryProvider()
            val item =
                runBlocking {
                    val r = sqlite.workItemRepository().create(WorkItem(title = "Schema S11b 503 root", depth = 0))!!
                    // A real row must exist so getFingerprint succeeds first -- resolve() only
                    // reaches the .get() read (which failGet intercepts) once the fingerprint check
                    // has NOT short-circuited on Success(null)/absence. Mirrors
                    // ConfigUnavailableRoutesTest's S11 fixture for GET /items/{id}/gate.
                    sqlite.projectConfigRepository().upsert(r.id, "work_item_schemas:\n  t:\n    notes: []\n")
                    sqlite
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
                        )!!
                }
            val failable = SchemaRouteFailableProjectConfigRepository(sqlite.projectConfigRepository())
            failable.failGet = true
            val provider = SchemaRouteFailableRepositoryProvider(sqlite, failable)
            // ServerComposition's public constructor takes only a DatabaseManager, with no seam to
            // inject a failing RepositoryProvider (see class KDoc) -- so this leg builds the
            // ToolExecutionContext directly over the failing provider, but still exercises it through
            // the REAL installRestApiRoutes entry point, never a bare itemGateRoutes call.
            val toolContext =
                ToolExecutionContext(
                    provider,
                    ItemSchemaRouteNoGlobalSchemaService,
                    perRootConfigService = PerRootConfigService(provider.projectConfigRepository()),
                    unitOfWork = db.unitOfWork(),
                )
            application {
                install(ContentNegotiation) { json(McpJson) }
                val authConfig = makeTestAuthConfig()
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = provider,
                    apiTokenEntries = tokenEntriesFor(authConfig),
                    allowQueryToken = false,
                    serverName = "item-schema-route-test-503",
                    serverVersion = "test",
                    actorAuthEnabled = false,
                    noteSchemaService = ItemSchemaRouteNoGlobalSchemaService,
                    toolContext = toolContext,
                    degradedModePolicy = DegradedModePolicy.ACCEPT_CACHED,
                    idempotencyCache = IdempotencyCache(),
                )
            }

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

/** Mirrors [ConfigUnavailableRoutesTest]'s `FailableProjectConfigRepository`, named for this file's own scope. */
private class SchemaRouteFailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository,
) : ProjectConfigRepository by delegate {
    @Volatile var failGet: Boolean = false

    override suspend fun get(rootItemId: UUID) = if (failGet) throw IllegalStateException("x") else delegate.get(rootItemId)
}

private class SchemaRouteFailableRepositoryProvider(
    private val delegate: RepositoryProvider,
    private val failable: SchemaRouteFailableProjectConfigRepository,
) : RepositoryProvider by delegate {
    override fun projectConfigRepository(): ProjectConfigRepository = failable
}
