package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import io.mockk.coVerify
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `f2c50e6d` —
 * scenarios S4-S7 (O1: REST gate/advance share MCP's `PerRootConfigService`, and therefore its
 * last-known-good cache). Oracle: `task-scope`'s invariant statement, "After any MCP or REST read
 * has warmed a root, a transient per-root read failure on the REST advance/gate route serves the
 * LKG instead of returning 503", plus [io.github.jpicklyk.mcptask.current.application.config.LegacyPrecedenceCharacterizationTest]'s
 * S5 ("a warm-then-error read serves the last-known-good per-root schema") for the underlying
 * [PerRootConfigService] LKG mechanism, and [AdvanceParityTest]'s established 422/`missingNotes`
 * shape for a gate-blocked REST advance.
 *
 * Harness mirrors [ConfigUnavailableRoutesTest]'s `FailableProjectConfigRepository`/
 * `FailableRepositoryProvider` pattern (each file defines its own copy; no shared harness class
 * exists for this port) and [ItemGateRouteTest]'s `configureGateApp` style for wiring one shared
 * [ToolExecutionContext] into both route families.
 */
class SharedConfigCacheRestMcpTest {
    companion object {
        private const val NOTE_KEY = "req-note"
        private const val SCHEMA_TYPE = "shared-type"
    }

    /** No global schema for any type — the note key in every scenario must come from the per-root push only. */
    private object NoGlobalSchemaService : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
    }

    private fun perRootYaml(): String =
        """
        work_item_schemas:
          $SCHEMA_TYPE:
            notes:
              - key: $NOTE_KEY
                role: queue
                required: true
                description: "shared cache test note"
        """.trimIndent()

    /** Registers itemGateRoutes and itemWriteRoutes over the SAME [ctx], mirroring O1's shared wiring. */
    private fun Application.configureSharedApp(
        provider: RepositoryProvider,
        ctx: ToolExecutionContext,
    ) {
        configureTestApp(makeWriteAuthConfig()) {
            itemGateRoutes(provider, ctx.configResolver)
            itemWriteRoutes(provider, DegradedModePolicy.ACCEPT_CACHED, IdempotencyCache(), ctx.advanceServiceFactory())
        }
    }

    private fun gateParams(itemId: UUID): JsonObject = JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString())))

    private class FailableProjectConfigRepository(
        private val delegate: ProjectConfigRepository
    ) : ProjectConfigRepository by delegate {
        @Volatile var failFingerprint: Boolean = false

        @Volatile var failGet: Boolean = false

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (failFingerprint) Result.Error(RepositoryError.DatabaseError("x")) else delegate.getFingerprint(rootItemId)

        override suspend fun get(rootItemId: UUID) =
            if (failGet) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
    }

    private class FailableRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val failable: FailableProjectConfigRepository
    ) : RepositoryProvider by delegate {
        override fun projectConfigRepository(): ProjectConfigRepository = failable
    }

    private class SpyRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val spyConfigRepo: ProjectConfigRepository
    ) : RepositoryProvider by delegate {
        override fun projectConfigRepository(): ProjectConfigRepository = spyConfigRepo
    }

    // ──────────────────────────────────────────────
    // S4 — warm via MCP (GetContextTool), then a per-root failure; REST gate 200 / advance 422
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - after a warm read via GetContextTool, a per-root read failure serves REST gate 200 and advance 422, not 503`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val failable = FailableProjectConfigRepository(h2.projectConfigRepository())
            val provider = FailableRepositoryProvider(h2, failable)
            val ctx = ToolExecutionContext(provider, NoGlobalSchemaService, perRootConfigService = PerRootConfigService(failable))

            val item =
                runBlocking {
                    val root = h2.workItemRepository().create(WorkItem(title = "S4 root", depth = 0)).getOrNull()!!
                    h2.projectConfigRepository().upsert(root.id, perRootYaml()).getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    val i =
                        h2
                            .workItemRepository()
                            .create(
                                WorkItem(
                                    title = "S4 item",
                                    type = SCHEMA_TYPE,
                                    role = Role.QUEUE,
                                    parentId = root.id,
                                    rootId = root.id,
                                    depth = 1,
                                ),
                            ).getOrNull()!!

                    val warm = GetContextTool().execute(gateParams(i.id), ctx) as JsonObject
                    assertTrue(warm["success"]!!.jsonPrimitive.boolean, "sanity: the warm MCP read must succeed before injecting failures")
                    i
                }

            failable.failFingerprint = true
            failable.failGet = true

            application { configureSharedApp(provider, ctx) }

            val gateResponse = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(
                HttpStatusCode.OK,
                gateResponse.status,
                "O1: a warm shared cache must serve the gate route through a per-root read failure",
            )
            val gateJson = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject
            assertEquals(
                listOf(NOTE_KEY),
                gateJson["gateStatus"]!!.jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content },
            )

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(
                HttpStatusCode.UnprocessableEntity,
                advanceResponse.status,
                "O1: a warm shared cache must gate-block (422), not fail cold (503)",
            )
            val advanceJson = Json.parseToJsonElement(advanceResponse.bodyAsText()).jsonObject
            val missingKeys =
                advanceJson["details"]!!
                    .jsonObject["missingNotes"]!!
                    .jsonArray
                    .map { it.jsonObject["key"]!!.jsonPrimitive.content }
            assertEquals(listOf(NOTE_KEY), missingKeys)
        }

    // ──────────────────────────────────────────────
    // S5 — reverse: warm via REST gate, then a per-root failure; MCP get_context still succeeds
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - after a warm REST gate read, a per-root read failure still serves get_context on the shared context`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val failable = FailableProjectConfigRepository(h2.projectConfigRepository())
            val provider = FailableRepositoryProvider(h2, failable)
            val ctx = ToolExecutionContext(provider, NoGlobalSchemaService, perRootConfigService = PerRootConfigService(failable))

            val item =
                runBlocking {
                    val root = h2.workItemRepository().create(WorkItem(title = "S5 root", depth = 0)).getOrNull()!!
                    h2.projectConfigRepository().upsert(root.id, perRootYaml()).getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    h2
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "S5 item",
                                type = SCHEMA_TYPE,
                                role = Role.QUEUE,
                                parentId = root.id,
                                rootId = root.id,
                                depth = 1,
                            ),
                        ).getOrNull()!!
                }

            application { configureSharedApp(provider, ctx) }

            val warmGate = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, warmGate.status, "sanity: the warm REST gate read must succeed before injecting failures")

            failable.failFingerprint = true
            failable.failGet = true

            val getContextResult = GetContextTool().execute(gateParams(item.id), ctx) as JsonObject
            assertTrue(
                getContextResult["success"]!!.jsonPrimitive.boolean,
                "O1: get_context on the shared context must be served from the LKG cache, not throw config_unavailable: $getContextResult",
            )
            val data = getContextResult["data"] as JsonObject
            val missing = data["gateStatus"]!!.jsonObject["missing"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals(listOf(NOTE_KEY), missing, "get_context must still see the per-root schema pushed for this root")
        }

    // ──────────────────────────────────────────────
    // S6 — control: cold cache, no warm anywhere; both routes still 503 config_unavailable
    // ──────────────────────────────────────────────

    @Test
    fun `S6 - with no warm read at all, a cold per-root failure yields 503 config_unavailable with no Retry-After on both routes`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val failable = FailableProjectConfigRepository(h2.projectConfigRepository())
            failable.failFingerprint = true
            failable.failGet = true
            val provider = FailableRepositoryProvider(h2, failable)
            val ctx = ToolExecutionContext(provider, NoGlobalSchemaService, perRootConfigService = PerRootConfigService(failable))

            val item =
                runBlocking {
                    val root = h2.workItemRepository().create(WorkItem(title = "S6 root", depth = 0)).getOrNull()!!
                    h2
                        .workItemRepository()
                        .create(
                            WorkItem(
                                title = "S6 item",
                                type = SCHEMA_TYPE,
                                role = Role.QUEUE,
                                parentId = root.id,
                                rootId = root.id,
                                depth = 1,
                            ),
                        ).getOrNull()!!
                }

            application { configureSharedApp(provider, ctx) }

            val gateResponse = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.ServiceUnavailable, gateResponse.status)
            assertTrue(gateResponse.bodyAsText().contains("config_unavailable"))
            assertNull(gateResponse.headers["Retry-After"], "D6: no Retry-After on a config_unavailable 503")

            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, advanceResponse.status)
            assertTrue(advanceResponse.bodyAsText().contains("config_unavailable"))
            assertNull(advanceResponse.headers["Retry-After"], "D6: no Retry-After on a config_unavailable 503")
        }

    // ──────────────────────────────────────────────
    // S7 — one REST advance request performs exactly one underlying per-root read
    // ──────────────────────────────────────────────

    @Test
    fun `S7 - one POST advance for a rooted item with a config row performs exactly one underlying getFingerprint read`() =
        testApplication {
            val h2 = buildH2RepositoryProvider()
            val spyConfigRepo = spyk(h2.projectConfigRepository())
            val provider = SpyRepositoryProvider(h2, spyConfigRepo)
            val ctx = ToolExecutionContext(provider, NoGlobalSchemaService, perRootConfigService = PerRootConfigService(spyConfigRepo))

            val item =
                runBlocking {
                    val root = h2.workItemRepository().create(WorkItem(title = "S7 root", depth = 0)).getOrNull()!!
                    h2.projectConfigRepository().upsert(root.id, "work_item_schemas:\n  default:\n    notes: []\n").getOrNull()
                        ?: error("fixture: per-root config upsert failed")
                    // Schema-free item type: the "start" transition must succeed with no gate
                    // involvement, isolating this scenario to config reads alone.
                    h2
                        .workItemRepository()
                        .create(WorkItem(title = "S7 item", role = Role.QUEUE, parentId = root.id, rootId = root.id, depth = 1))
                        .getOrNull()!!
                }

            application { configureSharedApp(provider, ctx) }

            val response =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(HttpStatusCode.OK, response.status, "sanity: a schema-free start must succeed")

            coVerify(exactly = 1) { spyConfigRepo.getFingerprint(item.rootId!!) }
        }
}
