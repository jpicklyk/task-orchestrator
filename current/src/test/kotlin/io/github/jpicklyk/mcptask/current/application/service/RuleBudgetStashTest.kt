package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.bootstrap.CompositionResult
import io.github.jpicklyk.mcptask.current.bootstrap.ServerComposition
import io.github.jpicklyk.mcptask.current.bootstrap.installRestApiRoutes
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.findByFilters
import io.github.jpicklyk.mcptask.current.test.createTreeOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independently authored (blind test author) against the frozen `task-scope-addendum` note on
 * item `840e700a` (A3) -- S11 (par D "BUDGET": `PlanDocumentService.stash` rejects `rule/`-slug
 * bodies over `RuleService.MAX_RULE_BODY_BYTES` == 16384 UTF-8 bytes; multibyte counted as bytes;
 * non-rule slugs keep the existing 64 KiB cap) and S12 (par E "D1": the `docRef.slug` guard of
 * `items_write` `create_tree` (formerly `create_work_tree`) rejects any `rule/`-prefixed slug -- zero
 * items created, the referenced document stays PENDING and re-stashable).
 *
 * Harness (contract "Public-API rule" / "Harness rule"): the `PlanDocumentService` leg calls the
 * public service directly; S12 drives the REAL `items_write` dispatcher over the composition's
 * `ToolExecutionContext` to confirm the guard, zero items created and the document's PENDING status.
 */
class RuleBudgetStashTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    // --- Shared composition wiring (own copy) ---

    private fun buildDatabaseManager(): DatabaseManager = db.databaseManager

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
            databaseManager = buildDatabaseManager(),
            shutdownCoordinator = ShutdownCoordinator()
        ).build()
    }

    private fun tokenEntriesFor(
        authConfig: ApiAuthConfig,
    ): Map<io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens ?: emptyMap()

    private fun Application.configureProductionApp(
        composition: CompositionResult,
        authConfig: ApiAuthConfig,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        installRestApiRoutes(
            apiConfig = authConfig,
            eventBus = null,
            effectiveProvider = composition.toolContext.repositoryProvider,
            apiTokenEntries = tokenEntriesFor(authConfig),
            allowQueryToken = false,
            serverName = "rule-budget-stash-test",
            serverVersion = "test",
            actorAuthEnabled = composition.actorAuthEnabled,
            serverConfig = composition.serverConfig,
            toolContext = composition.toolContext,
            appConfig = AppConfig.fromEnv(),
        )
    }

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    // =========================================================================================
    // S11 -- 16384-byte budget for rule/ slugs, enforced at PlanDocumentService.stash
    // =========================================================================================

    @Test
    fun `S11 PlanDocumentService stash accepts exactly 16384 UTF-8 bytes for a rule slug and rejects 16385`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val service = PlanDocumentService(composition.toolContext.repositoryProvider, db.unitOfWork())
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Root", depth = 0)
                    )!!
            }

        val atLimit = runBlocking { service.stash(root.id, "rule/budget-at-limit", "x".repeat(16384)) }
        assertTrue(atLimit is PlanDocumentStashResult.Success, "16384 bytes must be accepted: $atLimit")

        val overLimit = runBlocking { service.stash(root.id, "rule/budget-over-limit", "x".repeat(16385)) }
        assertTrue(overLimit is PlanDocumentStashResult.TooLarge, "16385 bytes must be rejected: $overLimit")
        val tooLarge = overLimit as PlanDocumentStashResult.TooLarge
        assertEquals(16385, tooLarge.sizeBytes)
        assertEquals(16384, tooLarge.maxBytes)
    }

    @Test
    fun `S11 the rule budget counts UTF-8 bytes not chars - multibyte body at the byte boundary is accepted, one char over is rejected`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val service = PlanDocumentService(composition.toolContext.repositoryProvider, db.unitOfWork())
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Multibyte Root", depth = 0)
                    )!!
            }

        // U+00E9 (e-acute) is 2 bytes in UTF-8: 8192 chars == 16384 bytes, exactly at the boundary.
        val atLimitBody = "é".repeat(8192)
        assertEquals(16384, atLimitBody.toByteArray(Charsets.UTF_8).size, "sanity: fixture must be exactly 16384 bytes")
        val atLimit = runBlocking { service.stash(root.id, "rule/multibyte-at-limit", atLimitBody) }
        assertTrue(
            atLimit is PlanDocumentStashResult.Success,
            "a multibyte body at exactly 16384 bytes (8192 chars) must be accepted: $atLimit"
        )

        // One more character (8193 chars == 16386 bytes) pushes it over, despite the char count
        // (8193) being far below the byte limit (16384) -- proving bytes, not chars, are counted.
        val overLimitBody = "é".repeat(8193)
        val overLimit = runBlocking { service.stash(root.id, "rule/multibyte-over-limit", overLimitBody) }
        assertTrue(overLimit is PlanDocumentStashResult.TooLarge, "16386 bytes (8193 multibyte chars) must be rejected: $overLimit")
        assertEquals(16386, (overLimit as PlanDocumentStashResult.TooLarge).sizeBytes)
    }

    @Test
    fun `S11 a non-rule slug keeps the existing 64 KiB cap - 20000 bytes is well within it and succeeds`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val service = PlanDocumentService(composition.toolContext.repositoryProvider, db.unitOfWork())
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Non-Rule Root", depth = 0)
                    )!!
            }

        val body = "x".repeat(20000)
        val result = runBlocking { service.stash(root.id, "plan-large-non-rule", body) }
        assertTrue(
            result is PlanDocumentStashResult.Success,
            "20000 bytes must succeed for a non-rule/ slug (well over the 16384 rule budget, well under the 64 KiB plan cap): $result",
        )
    }

    // =========================================================================================
    // S12 -- D1: items_write create_tree docRef.slug rule/x is rejected; zero items, doc stays pending
    // =========================================================================================

    @Test
    fun `S12 create_tree rejects a docRef slug that starts with rule slash`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val composition = buildComposition(tempDir)
            val result =
                composition.toolContext.itemsWrite(
                    createTreeOp(
                        "root" to
                            buildJsonObject {
                                put("type", JsonPrimitive("test-item"))
                                put("title", JsonPrimitive("Root"))
                            },
                        "docRef" to buildJsonObject { put("slug", JsonPrimitive("rule/some-rule-key")) },
                    ),
                )
            val error = result.errorAt(0)
            assertEquals("invalid_request", error["code"]?.jsonPrimitive?.content, "the rule/ guard is an invalid_request: $error")
            assertTrue(error.toString().contains("/ops/0/docRef/slug"), "the violation names docRef.slug: $error")
        }

    /**
     * The guard runs before any read or write of the tree: the element fails, zero items are created, and the rule/ document
     * stays PENDING and re-stashable (the oracle of the retired `create_work_tree` scenario, now over `items_write`).
     */
    @Test
    fun `S12 create_tree rejects docRef rule slash x, zero items, doc stays PENDING and re-stashable`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val composition = buildComposition(tempDir)
            val repo = composition.toolContext.repositoryProvider
            val root = repo.workItemRepository().create(WorkItem(title = "S12 MCP Root", depth = 0))!!
            repo.planDocumentRepository().stash(root.id, "rule/x", "A rule document, not a feature plan.")

            val result =
                composition.toolContext.itemsWrite(
                    createTreeOp(
                        "root" to
                            buildJsonObject {
                                put("title", JsonPrimitive("Feature via rule doc"))
                                put("type", JsonPrimitive("test-item"))
                            },
                        "parentId" to JsonPrimitive(root.id.toString()),
                        "docRef" to buildJsonObject { put("slug", JsonPrimitive("rule/x")) },
                    ),
                )
            val error = result.errorAt(0)
            assertEquals("invalid_request", error["code"]?.jsonPrimitive?.content, "review follow-up: assert the REASON: $error")
            assertTrue(error.toString().contains("/ops/0/docRef/slug"), "the validation reason must name the docRef.slug guard: $error")

            val itemsResult = repo.workItemRepository().findByFilters(parentId = root.id, limit = 100)
            val titles = itemsResult.items.map { it.title }
            assertTrue("Feature via rule doc" !in titles, "zero items must be created when docRef.slug is rule/-prefixed; got: $titles")

            val doc = (repo.planDocumentRepository().get(root.id, "rule/x")!!)
            assertNotNull(doc, "the rule document must still exist")
            assertEquals(
                PlanDocumentStatus.PENDING,
                doc.status,
                "the document must remain PENDING (not adopted) after the rejected docRef"
            )

            // Re-stashable: pushing a new body to the same slug must still succeed.
            val reStash = repo.planDocumentRepository().stash(root.id, "rule/x", "Updated rule body after the rejected docRef.")
            assertNotNull(reStash, "the document must remain re-stashable: $reStash")
        }

    // =========================================================================================
    // Review follow-up (orchestrator fix b3bdc5e0, per task-scope-addendum par C): a root lookup
    // that fails with a repository error OTHER than not-found must yield RepositoryError from
    // RuleService.get / RuleService.list -- NOT RootNotFound. Tested through the public RuleService
    // (constructor takes domain repositories -- a WorkItemRepository double returning
    // throw IllegalStateException(...) is fault injection at a declared
    // constructor seam, not a hand-built internal replica).
    // =========================================================================================

    @Test
    fun `review follow-up - RuleService get and list map a non-not-found root-lookup repo error to RepositoryError not RootNotFound`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root =
            runBlocking { repo.workItemRepository().create(WorkItem(title = "RuleService RepoError Root", depth = 0))!! }
        runBlocking { repo.planDocumentRepository().stash(root.id, "rule/x", "body") }
        val unknownId = UUID.randomUUID()

        val failingWorkItemRepo = RuleBudgetFailingWorkItemRepository(repo.workItemRepository(), root.id)
        val service = RuleService(repo.planDocumentRepository(), failingWorkItemRepo)

        val getResult = runBlocking { service.get(root.id, "x") }
        assertTrue(
            getResult is RuleGetResult.RepositoryError,
            "a non-not-found repository error must map to RepositoryError, not RootNotFound: $getResult",
        )

        val listResult = runBlocking { service.list(root.id) }
        assertTrue(listResult is RuleListResult.RepositoryError, "list must also map to RepositoryError: $listResult")

        // Control: a genuinely unknown root (untouched by the failing wrapper) still yields RootNotFound.
        val notFoundGet = runBlocking { service.get(unknownId, "x") }
        assertTrue(notFoundGet is RuleGetResult.RootNotFound, "an unrestricted unknown root must still be RootNotFound: $notFoundGet")
        val notFoundList = runBlocking { service.list(unknownId) }
        assertTrue(
            notFoundList is RuleListResult.RootNotFound,
            "list must also still RootNotFound for a genuinely unknown root: $notFoundList"
        )
    }
}

/** Wraps a real [WorkItemRepository], failing [getById] for exactly one id with a non-not-found repository error. */
private class RuleBudgetFailingWorkItemRepository(
    private val delegate: WorkItemRepository,
    private val failingId: UUID,
) : WorkItemRepository by delegate {
    override suspend fun getById(id: UUID): WorkItem? =
        if (id == failingId) {
            throw IllegalStateException("Simulated getById failure for $id")
        } else {
            delegate.getById(id)
        }
}
