package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeTool
import io.github.jpicklyk.mcptask.current.application.tools.config.ManagePlanDocumentsTool
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult
import io.github.jpicklyk.mcptask.current.interfaces.mcp.ServerComposition
import io.github.jpicklyk.mcptask.current.interfaces.mcp.installRestApiRoutes
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
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independently authored (blind test author) against the frozen `task-scope-addendum` note on
 * item `840e700a` (A3) -- S11 (par D "BUDGET": `PlanDocumentService.stash` rejects `rule/`-slug
 * bodies over `RuleService.MAX_RULE_BODY_BYTES` == 16384 UTF-8 bytes; multibyte counted as bytes;
 * non-rule slugs keep the existing 64 KiB cap) and S12 (par E "D1": `CreateWorkTreeTool`'s
 * `docRef.slug` guard rejects any `rule/`-prefixed slug -- zero items created, the referenced
 * document stays PENDING and re-stashable).
 *
 * Harness (contract "Public-API rule" / "Harness rule"): the `PlanDocumentService` leg calls the
 * public service directly (it has no route/tool wrapper of its own to prefer); the MCP/REST legs
 * of S11 run `ManagePlanDocumentsTool.execute` / the REST `PUT .../plans/{slug}` route through a
 * REAL [ServerComposition.build] + REAL [installRestApiRoutes] (mirrors
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.PlanDocumentRoutesTest]'s "over the
 * size cap returns 413" pattern, adapted to the rule/ boundary); S12 drives the REAL
 * [CreateWorkTreeTool] (`validateParams` for the guard itself, `execute` against a real H2-backed
 * `ToolExecutionContext` to confirm zero items created and the document's PENDING status
 * survives), mirroring [io.github.jpicklyk.mcptask.current.application.tools.compound.CreateWorkTreeToolIntegrationTest]'s
 * docRef-failure-is-atomic pattern.
 */
class RuleBudgetStashTest {
    // --- Shared composition wiring (own copy) ---

    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "rule_budget_stash_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    private fun materializeEmptyGlobalConfig(tempDir: Path) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), "work_item_schemas: {}\n".toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(tempDir: Path): CompositionResult {
        materializeEmptyGlobalConfig(tempDir)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return ServerComposition(appConfig = appConfig, databaseManager = buildDatabaseManager(), shutdownCoordinator = null).build()
    }

    private fun tokenEntriesFor(
        authConfig: ApiAuthConfig,
    ): Map<io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, principal) ->
            BearerTokenStore.TokenEntry(principal, expiresAt = null)
        } ?: emptyMap()

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
            noteSchemaService = composition.noteSchemaService,
            toolContext = composition.toolContext,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
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
        val service = PlanDocumentService(composition.toolContext.repositoryProvider)
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Root", depth = 0)
                    ).getOrNull()!!
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
        val service = PlanDocumentService(composition.toolContext.repositoryProvider)
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Multibyte Root", depth = 0)
                    ).getOrNull()!!
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
        val service = PlanDocumentService(composition.toolContext.repositoryProvider)
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 Non-Rule Root", depth = 0)
                    ).getOrNull()!!
            }

        val body = "x".repeat(20000)
        val result = runBlocking { service.stash(root.id, "plan-large-non-rule", body) }
        assertTrue(
            result is PlanDocumentStashResult.Success,
            "20000 bytes must succeed for a non-rule/ slug (well over the 16384 rule budget, well under the 64 KiB plan cap): $result",
        )
    }

    @Test
    fun `S11 MCP - manage_plan_documents stash for a rule slug over 16384 bytes returns VALIDATION_ERROR naming 16384`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val root =
            runBlocking {
                composition.toolContext.repositoryProvider
                    .workItemRepository()
                    .create(
                        WorkItem(title = "S11 MCP Root", depth = 0)
                    ).getOrNull()!!
            }
        val result =
            runBlocking {
                ManagePlanDocumentsTool().execute(
                    params(
                        "operation" to JsonPrimitive("stash"),
                        "rootId" to JsonPrimitive(root.id.toString()),
                        "slug" to JsonPrimitive("rule/mcp-over-limit"),
                        "body" to JsonPrimitive("x".repeat(16385)),
                    ),
                    composition.toolContext,
                )
            } as JsonObject
        assertFalse(result["success"]!!.jsonPrimitive.boolean, "expected failure: $result")
        val error = result["error"] as JsonObject
        assertEquals(
            io.github.jpicklyk.mcptask.current.application.tools.ErrorCodes.VALIDATION_ERROR,
            error["code"]!!.jsonPrimitive.content,
        )
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("16384"), "message must name the 16384-byte limit: $error")
    }

    @Test
    fun `S11 REST - PUT roots rootId plans rule slug over 16384 bytes returns 413 payload_too_large`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val composition = buildComposition(tempDir)
            val root =
                runBlocking {
                    composition.toolContext.repositoryProvider
                        .workItemRepository()
                        .create(
                            WorkItem(title = "S11 REST Root", depth = 0)
                        ).getOrNull()!!
                }
            application { configureProductionApp(composition, makeWriteAuthConfig()) }

            val response =
                client.put("/api/v1/roots/${root.id}/plans/rule%2Frest-over-limit") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("x".repeat(16385))
                }
            assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
            assertTrue(response.bodyAsText().contains("payload_too_large"), "body: ${response.bodyAsText()}")

            // Sanity: the same body, one byte shorter, at a DIFFERENT rule slug succeeds.
            val okResponse =
                client.put("/api/v1/roots/${root.id}/plans/rule%2Frest-at-limit") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Text.Plain)
                    setBody("x".repeat(16384))
                }
            assertEquals(HttpStatusCode.OK, okResponse.status, "16384 bytes must succeed: ${okResponse.bodyAsText()}")
        }

    // =========================================================================================
    // S12 -- D1: create_work_tree docRef.slug rule/x is rejected; zero items, doc stays pending
    // =========================================================================================

    @Test
    fun `S12 CreateWorkTreeTool validateParams rejects a docRef slug that starts with rule slash`() {
        val ex =
            assertFailsWith<ToolValidationException> {
                CreateWorkTreeTool().validateParams(
                    params(
                        "root" to buildJsonObject { put("title", JsonPrimitive("Root")) },
                        "docRef" to buildJsonObject { put("slug", JsonPrimitive("rule/some-rule-key")) },
                    ),
                )
            }
        assertTrue(ex.message?.contains("rule/") == true, "exception should mention the rule/ guard: ${ex.message}")
    }

    @Test
    fun `S12 execute against docRef slug rule slash x fails, creates zero items, leaves the document PENDING and re-stashable`(
        @TempDir tempDir: Path,
    ) {
        val composition = buildComposition(tempDir)
        val repo = composition.toolContext.repositoryProvider
        val root = runBlocking { repo.workItemRepository().create(WorkItem(title = "S12 Root", depth = 0)).getOrNull()!! }
        runBlocking { repo.planDocumentRepository().stash(root.id, "rule/x", "A rule document, not a feature plan.") }

        val execParams =
            params(
                "root" to buildJsonObject { put("title", JsonPrimitive("Feature via rule doc")) },
                "parentId" to JsonPrimitive(root.id.toString()),
                "docRef" to buildJsonObject { put("slug", JsonPrimitive("rule/x")) },
            )
        val result = runBlocking { CreateWorkTreeTool().execute(execParams, composition.toolContext) } as JsonObject
        assertFalse(result["success"]!!.jsonPrimitive.boolean, "expected failure: $result")

        val itemsResult = runBlocking { repo.workItemRepository().findByFilters(parentId = root.id, limit = 100) }
        val titles = (itemsResult as Result.Success).data.items.map { it.title }
        assertTrue("Feature via rule doc" !in titles, "zero items must be created when docRef.slug is rule/-prefixed; got: $titles")

        val doc = (runBlocking { repo.planDocumentRepository().get(root.id, "rule/x") } as Result.Success).data
        assertNotNull(doc, "the rule document must still exist")
        assertEquals(PlanDocumentStatus.PENDING, doc.status, "the document must remain PENDING (not adopted) after the rejected docRef")

        // Re-stashable: pushing a new body to the same slug must still succeed.
        val reStash = runBlocking { repo.planDocumentRepository().stash(root.id, "rule/x", "Updated rule body after the rejected docRef.") }
        assertTrue(reStash is Result.Success, "the document must remain re-stashable: $reStash")
    }
}
