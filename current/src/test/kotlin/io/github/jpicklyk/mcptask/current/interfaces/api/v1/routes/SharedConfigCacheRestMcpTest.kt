package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.ConfigStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.get
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
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
 * shared cache still serves reads made outside any unit (a direct EffectiveConfigResolver read; the REST effective route moved
 * to the `config` tool in Q23, whose reads run inside a unit).
 *
 * Harness mirrors [ConfigUnavailableRoutesTest]'s `FailableProjectConfigRepository`/
 * `FailableRepositoryProvider` pattern (each file defines its own copy; no shared harness class
 * exists for this port) and the route-test `configureTestApp` style for wiring one shared
 * [ToolExecutionContext] into both route families.
 */
class SharedConfigCacheRestMcpTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    companion object {
        private const val NOTE_KEY = "req-note"
        private const val SCHEMA_TYPE = "shared-type"
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

    /** Mounts the test app over [ctx]; no route reads config any more (the gate moved to items_read in Q22, the effective view to `config` in Q23). */
    private fun Application.configureSharedApp(
        provider: RepositoryProvider,
        ctx: ToolExecutionContext,
    ) {
        configureTestApp(makeWriteAuthConfig()) {
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
    // S4 - warm via MCP (items_read context), then a per-root failure: REST advance 503 (in-unit), effective config 200 (cache)
    // ──────────────────────────────────────────────

    // ----------------------------------------------------------------------------------------------------
    // S5 - warm via the MCP context select, then a per-root failure; the select fails closed (unavailable) on the shared context
    // ----------------------------------------------------------------------------------------------------

    @Test
    fun `S5 - after a warm context read, a read failure makes the context select unavailable while a non-unit read is still cached`() =
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

            val warm = ItemContextProbe().execute(gateParams(item.id), ctx) as JsonObject
            assertEquals(
                true,
                (warm["success"] as JsonPrimitive).content.toBoolean(),
                "sanity: the warm context read must succeed before injecting failures: $warm"
            )

            failable.failFingerprint = true
            failable.failGet = true

            // H1: the canAdvance preview resolves config inside its read unit (no last-known-good there); the select answers
            // the transient `unavailable` error (decision D-f), never a fallback.
            val failed = ItemContextProbe().execute(gateParams(item.id), ctx) as JsonObject
            assertEquals(false, (failed["success"] as JsonPrimitive).content.toBoolean(), "$failed")
            assertEquals("unavailable", ((failed["error"] as JsonObject)["code"] as JsonPrimitive).content, "$failed")

            // The REST-warmed cache is still the shared cache for reads outside any unit.
            val layered = ctx.configResolver.layered(item.rootId)
            assertTrue(
                SCHEMA_TYPE in layered.typeKeys(),
                "the warmed per-root layer is served from the shared cache: ${layered.typeKeys()}"
            )
        }

    // ──────────────────────────────────────────────
    // S7 — one REST advance request performs exactly one underlying per-root read
    // ──────────────────────────────────────────────
}
