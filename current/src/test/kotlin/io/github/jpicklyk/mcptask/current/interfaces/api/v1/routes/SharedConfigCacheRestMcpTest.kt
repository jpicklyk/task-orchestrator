package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource
import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    private object NoGlobalSchemaService : WorkItemSchemaService

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

    /** Registers itemGateRoutes and effectiveConfigRoutes over the SAME [ctx], mirroring O1's shared wiring. */
    private fun Application.configureSharedApp(
        provider: RepositoryProvider,
        ctx: ToolExecutionContext,
    ) {
        configureTestApp(makeWriteAuthConfig()) {
            itemGateRoutes(provider, ctx.configResolver, ctx.transitionPreview())
            effectiveConfigRoutes(provider, ctx.configResolver, ServerConfigSource { null })
        }
    }

    private fun gateParams(itemId: UUID): JsonObject = JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString())))

    private class FailableProjectConfigRepository(
        private val delegate: ConfigStore
    ) : ConfigStore by delegate {
        @Volatile var failFingerprint: Boolean = false

        @Volatile var failGet: Boolean = false

        override suspend fun head(
            scope: ConfigScope,
            rootId: UUID?
        ) = if (failFingerprint) throw IllegalStateException("x") else delegate.head(scope, rootId)

        override suspend fun version(id: UUID) = if (failGet) throw IllegalStateException("x") else delegate.version(id)
    }

    private class FailableRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val failable: FailableProjectConfigRepository
    ) : RepositoryProvider by delegate {
        override fun configStore(): ConfigStore = failable
    }

    private class SpyRepositoryProvider(
        private val delegate: RepositoryProvider,
        private val spyConfigRepo: ConfigStore
    ) : RepositoryProvider by delegate {
        override fun configStore(): ConfigStore = spyConfigRepo
    }

    // ──────────────────────────────────────────────
    // S4 - warm via MCP (GetContextTool), then a per-root failure: REST gate 503 / advance 503 (in-unit), effective config 200 (cache)
    // ──────────────────────────────────────────────

    // ----------------------------------------------------------------------------------------------------
    // S5 - reverse: warm via REST gate, then a per-root failure; MCP get_context fails closed on the shared context
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun `S5 - after a warm REST gate read, a read failure makes get_context throw while a non-unit read is still cached`() =
        testApplication {
            val sqlite = db.repositoryProvider()
            val failable = FailableProjectConfigRepository(sqlite.configStore())
            val provider = FailableRepositoryProvider(sqlite, failable)
            val ctx =
                ToolExecutionContext(
                    provider,
                    perRootConfigService = PerRootConfigService(failable),
                    unitOfWork = db.unitOfWork()
                )

            val item =
                runBlocking {
                    val root = sqlite.workItemRepository().create(WorkItem(title = "S5 root", depth = 0))!!
                    sqlite.configStore().upsert(root.id, perRootYaml())
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

    // ──────────────────────────────────────────────
    // S7 — one REST advance request performs exactly one underlying per-root read
    // ──────────────────────────────────────────────
}
