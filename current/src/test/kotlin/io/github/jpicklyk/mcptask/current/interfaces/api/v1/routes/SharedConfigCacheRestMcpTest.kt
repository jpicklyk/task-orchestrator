package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.ProjectConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
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
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
 * r1 (fix-decision H1): the advance (and its cascade targets) and the gate preview resolve per-root config INSIDE a unit of
 * work, where there is no last-known-good, so S4/S5 now pin a fail-closed config_unavailable on those surfaces while the ONE
 * shared cache still serves reads made outside any unit (GET /roots/{rootId}/config/effective, api-rest.md section 18).
 *
 * Harness mirrors [ConfigUnavailableRoutesTest]'s `FailableProjectConfigRepository`/
 * `FailableRepositoryProvider` pattern (each file defines its own copy; no shared harness class
 * exists for this port) and [ItemGateRouteTest]'s `configureGateApp` style for wiring one shared
 * [ToolExecutionContext] into both route families.
 */
class SharedConfigCacheRestMcpTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

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
            itemGateRoutes(provider, ctx.configResolver, ctx.transitionPreview())
            effectiveConfigRoutes(provider, ctx.configResolver, NoGlobalSchemaService)
            itemWriteRoutes(
                provider,
                DegradedModePolicy.ACCEPT_CACHED,
                IdempotencyService(ctx.unitOfWork),
                ctx.advanceServiceFactory(),
                ctx.unitOfWork
            )
        }
    }

    private fun gateParams(itemId: UUID): JsonObject = JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString())))

    private class FailableProjectConfigRepository(
        private val delegate: ProjectConfigStore
    ) : ProjectConfigStore by delegate {
        @Volatile var failFingerprint: Boolean = false

        @Volatile var failGet: Boolean = false

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (failFingerprint) throw IllegalStateException("x") else delegate.getFingerprint(rootItemId)

        override suspend fun get(rootItemId: UUID) = if (failGet) throw IllegalStateException("x") else delegate.get(rootItemId)
    }

    private class FailableRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val failable: FailableProjectConfigRepository
    ) : RepositoryProvider by delegate {
        override fun projectConfigRepository(): ProjectConfigStore = failable
    }

    private class SpyRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val spyConfigRepo: ProjectConfigStore
    ) : RepositoryProvider by delegate {
        override fun projectConfigRepository(): ProjectConfigStore = spyConfigRepo
    }

    // ──────────────────────────────────────────────
    // S4 - warm via MCP (GetContextTool), then a per-root failure: REST gate 503 / advance 503 (in-unit), effective config 200 (cache)
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - after a warm MCP read, a read failure fails REST gate and advance closed (503) while a non-unit read is still cached`() =
        testApplication {
            val sqlite = db.repositoryProvider()
            val failable = FailableProjectConfigRepository(sqlite.projectConfigRepository())
            val provider = FailableRepositoryProvider(sqlite, failable)
            val ctx =
                ToolExecutionContext(
                    provider,
                    NoGlobalSchemaService,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            val item =
                runBlocking {
                    val root = sqlite.workItemRepository().create(WorkItem(title = "S4 root", depth = 0))!!
                    sqlite.projectConfigRepository().upsert(root.id, perRootYaml())
                        ?: error("fixture: per-root config upsert failed")
                    val i =
                        sqlite
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
                            )!!

                    val warm = GetContextTool().execute(gateParams(i.id), ctx) as JsonObject
                    assertTrue(warm["success"]!!.jsonPrimitive.boolean, "sanity: the warm MCP read must succeed before injecting failures")
                    i
                }

            failable.failFingerprint = true
            failable.failGet = true

            application { configureSharedApp(provider, ctx) }

            // H1: the gate resolves config inside its preview's read unit - no last-known-good, so a warm cache does not help.
            val gateResponse = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                gateResponse.status,
                "H1: the gate must fail closed even though the shared cache is warm",
            )
            assertTrue(gateResponse.bodyAsText().contains("config_unavailable"), gateResponse.bodyAsText())
            assertNull(gateResponse.headers["Retry-After"], "D6: no Retry-After on a config_unavailable 503")

            // H1: the advance resolves config inside its write unit - same fail-closed answer, and nothing is applied.
            val advanceResponse =
                client.post("/api/v1/items/${item.id}/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                advanceResponse.status,
                "H1: the advance must fail closed (503), not gate-block (422) from a cached schema",
            )
            assertTrue(advanceResponse.bodyAsText().contains("config_unavailable"), advanceResponse.bodyAsText())
            assertNull(advanceResponse.headers["Retry-After"], "D6: no Retry-After on a config_unavailable 503")
            assertEquals(Role.QUEUE, runBlocking { sqlite.workItemRepository().getById(item.id)!!.role }, "nothing was applied")

            // The cache warmed by the MCP read is still the one shared cache: a read OUTSIDE any unit is served from it.
            val effective =
                client.get("/api/v1/roots/${item.rootId}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(
                HttpStatusCode.OK,
                effective.status,
                "O1: a non-unit read must still be served from the cache the MCP read warmed: ${effective.bodyAsText()}",
            )
            assertTrue(
                effective.bodyAsText().contains(SCHEMA_TYPE),
                "the per-root type is visible from the cached layer: ${effective.bodyAsText()}"
            )
        }

    // ----------------------------------------------------------------------------------------------------
    // S5 - reverse: warm via REST gate, then a per-root failure; MCP get_context fails closed on the shared context
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun `S5 - after a warm REST gate read, a read failure makes get_context throw while a non-unit read is still cached`() =
        testApplication {
            val sqlite = db.repositoryProvider()
            val failable = FailableProjectConfigRepository(sqlite.projectConfigRepository())
            val provider = FailableRepositoryProvider(sqlite, failable)
            val ctx =
                ToolExecutionContext(
                    provider,
                    NoGlobalSchemaService,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            val item =
                runBlocking {
                    val root = sqlite.workItemRepository().create(WorkItem(title = "S5 root", depth = 0))!!
                    sqlite.projectConfigRepository().upsert(root.id, perRootYaml())
                        ?: error("fixture: per-root config upsert failed")
                    sqlite
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
                        )!!
                }

            application { configureSharedApp(provider, ctx) }

            val warmGate = client.get("/api/v1/items/${item.id}/gate") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, warmGate.status, "sanity: the warm REST gate read must succeed before injecting failures")

            failable.failFingerprint = true
            failable.failGet = true

            // H1: get_context's canAdvance preview resolves config inside its read unit; a direct execute throws, the MCP
            // adapter turns that into the transient config_unavailable error.
            val e = assertFailsWith<PerRootConfigUnavailableException> { GetContextTool().execute(gateParams(item.id), ctx) }
            assertEquals(item.rootId, e.rootId)
            assertTrue(e.message.orEmpty().contains("inside a unit of work"), "the fault names the unit-of-work read: ${e.message}")

            // The REST-warmed cache is still the shared cache for reads outside any unit.
            val effective =
                client.get("/api/v1/roots/${item.rootId}/config/effective") { header("Authorization", "Bearer $TEST_TOKEN") }
            assertEquals(HttpStatusCode.OK, effective.status, effective.bodyAsText())
            assertTrue(effective.bodyAsText().contains(SCHEMA_TYPE), effective.bodyAsText())
        }

    // ----------------------------------------------------------------------------------------------------
    // S6 - control: cold cache, no warm anywhere; both routes still 503 config_unavailable
    // ----------------------------------------------------------------------------------------------------
    @Test
    fun `S6 - with no warm read at all, a cold per-root failure yields 503 config_unavailable with no Retry-After on both routes`() =
        testApplication {
            val sqlite = db.repositoryProvider()
            val failable = FailableProjectConfigRepository(sqlite.projectConfigRepository())
            failable.failFingerprint = true
            failable.failGet = true
            val provider = FailableRepositoryProvider(sqlite, failable)
            val ctx =
                ToolExecutionContext(
                    provider,
                    NoGlobalSchemaService,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            val item =
                runBlocking {
                    val root = sqlite.workItemRepository().create(WorkItem(title = "S6 root", depth = 0))!!
                    sqlite
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
                        )!!
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
            val sqlite = db.repositoryProvider()
            val spyConfigRepo = spyk(sqlite.projectConfigRepository())
            val provider = SpyRepositoryProvider(sqlite, spyConfigRepo)
            val ctx =
                ToolExecutionContext(
                    provider,
                    NoGlobalSchemaService,
                    perRootConfigService = PerRootConfigService(spyConfigRepo),
                    unitOfWork = db.unitOfWork()
                )

            val item =
                runBlocking {
                    val root = sqlite.workItemRepository().create(WorkItem(title = "S7 root", depth = 0))!!
                    sqlite.projectConfigRepository().upsert(root.id, "work_item_schemas:\n  default:\n    notes: []\n")
                        ?: error("fixture: per-root config upsert failed")
                    // Schema-free item type: the "start" transition must succeed with no gate
                    // involvement, isolating this scenario to config reads alone.
                    sqlite
                        .workItemRepository()
                        .create(WorkItem(title = "S7 item", role = Role.QUEUE, parentId = root.id, rootId = root.id, depth = 1))!!
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
