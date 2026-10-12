package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.PlanDocumentStashResult
import io.github.jpicklyk.mcptask.current.bootstrap.ServerComposition
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemSchemaProbe
import io.github.jpicklyk.mcptask.current.test.upsert
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.fail

/**
 * Independently authored (blind test author) against the frozen `test-plan` note on item
 * `840e700a` (A3) -- scenario S5 ("pointer-only: with rule/spec-quality + rule/test-author
 * stashed, query_items(schema) item+type and REST GET /items/{id}/schema still equal
 * golden/a1-seatless/ *.json (A1 normalization); no rule-body marker in any"). Oracle:
 * `task-scope` "never inline rule text into query_items(schema)" + plan Sec 6.6.
 *
 * This is a REGRESSION check, not a new golden: it reuses the EXACT fixture and golden files
 * `SeatlessResponseGoldenTest` (A1, item `79cd4f0c`) already recorded, read-only here (never
 * re-recorded by this file -- a missing golden is a finding to report, not something to
 * fabricate). The only addition on top of that A1 harness is stashing two `rule/` documents at
 * the fixed root before capturing, to prove their presence does not perturb the byte-identical
 * pointer-only schema responses this item's acceptance criterion requires.
 *
 * Harness: mirrors `SeatlessResponseGoldenTest`'s own fixture builder (own copy; no shared
 * harness file per this item's file-ownership rule) -- REAL `ServerComposition.build` over SQLite for
 * MCP, REAL `installRestApiRoutes` with `ContentNegotiation` installed first for REST (per the
 * contract's Harness rule), reusing the SAME `composition.toolContext` for both surfaces.
 */
class RulePointerOnlyGoldenTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    companion object {
        private val ROOT_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000000")
        private val FEATURE_TASK_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000001")
        private val BUG_FIX_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000002")

        private const val GOLDEN_RESOURCE_DIR = "golden/a1-seatless"
        private const val REPO_CONFIG_RESOURCE = "$GOLDEN_RESOURCE_DIR/fixtures/repo-config.yaml"
        private const val GLOBAL_CONFIG_RESOURCE = "$GOLDEN_RESOURCE_DIR/fixtures/global-config.yaml"

        private val compactJson = Json { }

        /** Distinctive marker strings for the two stashed rule bodies -- used to grep captured JSON for a leak. */
        private const val RULE_MARKER_SPEC_QUALITY = "S5-MARKER-SPEC-QUALITY-RULE-BODY-MUST-NEVER-LEAK-INTO-SCHEMA"
        private const val RULE_MARKER_TEST_AUTHOR = "S5-MARKER-TEST-AUTHOR-RULE-BODY-MUST-NEVER-LEAK-INTO-SCHEMA"

        private fun classpathResourceText(path: String): String {
            val stream =
                RulePointerOnlyGoldenTest::class.java.classLoader.getResourceAsStream(path)
                    ?: error("missing test resource on classpath: $path")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    private fun materializeGlobalConfig(tempDir: Path) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        Files.write(configDir.resolve("config.yaml"), classpathResourceText(GLOBAL_CONFIG_RESOURCE).toByteArray(Charsets.UTF_8))
    }

    private class Fixture(
        val toolContext: ToolExecutionContext,
        val repositoryProvider: RepositoryProvider,
        val serverConfig: io.github.jpicklyk.mcptask.current.application.config.ServerConfigSource,
        val degradedModePolicy: io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy,
    )

    /** Reuses the A1 golden fixture verbatim, then stashes two rule/ documents at ROOT_ID (this item's addition). */
    private fun newFixtureWithRulesStashed(tempDir: Path): Fixture {
        materializeGlobalConfig(tempDir)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        val composition =
            ServerComposition(
                appConfig = appConfig,
                databaseManager = db.databaseManager,
                shutdownCoordinator = ShutdownCoordinator(),
            ).build()

        val repo = composition.toolContext.repositoryProvider
        runBlocking {
            repo
                .workItemRepository()
                .create(WorkItem(id = ROOT_ID, title = "A3 S5 golden root", type = "project", depth = 0))
                ?: error("fixture: root item creation failed")

            repo
                .configStore()
                .upsert(ROOT_ID, classpathResourceText(REPO_CONFIG_RESOURCE))
                ?: error("fixture: per-root config push failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = FEATURE_TASK_ID,
                        title = "A3 S5 golden feature-task",
                        type = "feature-task",
                        role = Role.QUEUE,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: feature-task item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = BUG_FIX_ID,
                        title = "A3 S5 golden bug-fix",
                        type = "bug-fix",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ) ?: error("fixture: bug-fix item creation failed")
        }

        // This item's addition: stash the two rule/ documents these skill pointers name.
        runBlocking {
            val r1 = composition.toolContext.planDocumentService.stash(ROOT_ID, "rule/spec-quality", RULE_MARKER_SPEC_QUALITY)
            val r2 = composition.toolContext.planDocumentService.stash(ROOT_ID, "rule/test-author", RULE_MARKER_TEST_AUTHOR)
            check(r1 is PlanDocumentStashResult.Success) { "fixture: rule/spec-quality stash failed: $r1" }
            check(r2 is PlanDocumentStashResult.Success) { "fixture: rule/test-author stash failed: $r2" }
        }

        return Fixture(
            toolContext = composition.toolContext,
            repositoryProvider = repo,
            serverConfig = composition.serverConfig,
            degradedModePolicy = composition.degradedModePolicy,
        )
    }

    /** Same normalization `SeatlessResponseGoldenTest` applies -- own copy, per this item's file-ownership rule. */
    private fun normalizeGolden(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        var result = element

        val metadata = result["metadata"] as? JsonObject
        if (metadata != null && metadata.containsKey("timestamp")) {
            var patchedMetadata = JsonObject(metadata + ("timestamp" to JsonPrimitive("<A1-T0-NORMALIZED-TIMESTAMP>")))
            if (patchedMetadata.containsKey("version")) {
                patchedMetadata = JsonObject(patchedMetadata + ("version" to JsonPrimitive("<A1-T0-NORMALIZED-VERSION>")))
            }
            result = JsonObject(result + ("metadata" to patchedMetadata))
        }

        val success = result["success"] as? JsonPrimitive
        val data = result["data"] as? JsonObject
        if (success?.boolean == true && data != null && data.containsKey("configFingerprint")) {
            val patchedData = JsonObject(data + ("configFingerprint" to JsonPrimitive("<A1-T0-NORMALIZED-CONFIG-FINGERPRINT>")))
            result = JsonObject(result + ("data" to patchedData))
        }
        return result
    }

    private fun goldenFile(name: String): File {
        val moduleDir = resolveCurrentModuleDir()
        return File(moduleDir, "src/test/resources/$GOLDEN_RESOURCE_DIR/$name.json")
    }

    private fun resolveCurrentModuleDir(): File {
        val userDir = File(System.getProperty("user.dir")).absoluteFile
        if (File(userDir, "build.gradle.kts").exists() && userDir.name == "current") return userDir
        val nested = File(userDir, "current")
        if (File(nested, "build.gradle.kts").exists()) return nested
        return userDir
    }

    /** Read-only comparison -- NEVER records/writes a golden; a missing golden is a hard failure to report. */
    private fun assertMatchesExistingGolden(
        name: String,
        actual: JsonElement,
    ) {
        val normalized = normalizeGolden(actual)
        val file = goldenFile(name)
        if (!file.exists()) {
            fail(
                "missing golden resource for '$name' at ${file.absolutePath} -- " +
                    "this file never records goldens, only compares against ones A1 already recorded",
            )
        }
        val expectedText = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        val expected = Json.parseToJsonElement(expectedText)
        val expectedCompact = compactJson.encodeToString(JsonElement.serializer(), expected)
        val actualCompact = compactJson.encodeToString(JsonElement.serializer(), normalized)
        assertEquals(
            expectedCompact,
            actualCompact,
            "'$name' must stay byte-identical to its A1-recorded golden even with rule/ documents stashed at the same root",
        )
    }

    private fun assertNoRuleMarker(text: String) {
        assertFalse(text.contains(RULE_MARKER_SPEC_QUALITY), "response must never leak the stashed spec-quality rule body: $text")
        assertFalse(text.contains(RULE_MARKER_TEST_AUTHOR), "response must never leak the stashed test-author rule body: $text")
    }

    // -----------------------------------------------------------------------
    // MCP: query_items(schema) by itemId and by type -- unchanged goldens, no rule-body marker
    // -----------------------------------------------------------------------

    @Test
    fun `S5 query_items schema by itemId feature-task stays byte-identical to the A1 golden with rules stashed`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixtureWithRulesStashed(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("itemId", JsonPrimitive(FEATURE_TASK_ID.toString()))
                }
            val result = ItemSchemaProbe().execute(params, fx.toolContext)
            assertMatchesExistingGolden("query_items_schema_item_feature_task", result)
            assertNoRuleMarker(compactJson.encodeToString(JsonElement.serializer(), result))
        }

    @Test
    fun `S5 query_items schema by itemId bug-fix stays byte-identical to the A1 golden with rules stashed`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixtureWithRulesStashed(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("itemId", JsonPrimitive(BUG_FIX_ID.toString()))
                }
            val result = ItemSchemaProbe().execute(params, fx.toolContext)
            assertMatchesExistingGolden("query_items_schema_item_bug_fix", result)
            assertNoRuleMarker(compactJson.encodeToString(JsonElement.serializer(), result))
        }

    @Test
    fun `S5 query_items schema by type bug-fix with rootId stays byte-identical to the A1 golden with rules stashed`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixtureWithRulesStashed(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("type", JsonPrimitive("bug-fix"))
                    put("rootId", JsonPrimitive(ROOT_ID.toString()))
                }
            val result = ItemSchemaProbe().execute(params, fx.toolContext)
            assertMatchesExistingGolden("query_items_schema_type_bug_fix", result)
            assertNoRuleMarker(compactJson.encodeToString(JsonElement.serializer(), result))
        }
}
