package io.github.jpicklyk.mcptask.current.interfaces.mcp

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.items.QueryItemsTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.BearerTokenStore
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.WRITE_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeTestAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.makeWriteAuthConfig
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * T0 — seat-less response goldens (characterization), item `79cd4f0c-10aa-484e-b378-d4d5c0f10430`,
 * stage T0. Independently authored per the frozen `test-plan`/`task-scope-addendum` notes (scenario
 * S1) BEFORE any A1 `src/main` change — committed green on base `f9d71a69`.
 *
 * PURPOSE: pins today's MCP/REST response bytes for a config with NO seats declared, so every later
 * A1 stage (A1a model/parse/layering, A1b MCP serving, A1c REST) can be checked for byte-identity
 * (DEC-1 / task-scope §6.1 rule 1 / acceptance criterion AC1: "every MCP/REST response in T0 is
 * byte-identical after A1, except the one declared addition `features` on query_items(schema)").
 * This file makes no claim about what a response *should* be — it is a snapshot/characterization
 * harness, not an independently-derived-oracle suite. Its own red-proof is M1
 * (`task-scope-addendum` "Red-proof recipes"): once A1 lands, forcing
 * `WorkItemSchema.isSeatAware()` to always return `true` makes seat-less schemas start emitting
 * `seat`/`missingBySeat` keys, and every capture below goes red against its recorded golden.
 *
 * HARNESS (task-scope-addendum "Harness rule" — never a hand-built replica):
 * - MCP captures run through the REAL [ServerComposition.build] over H2 (LayerBackedGlobalLookup +
 *   PerRootConfigService, exactly as production wires it — pattern:
 *   [ServerCompositionResolverWiringTest]) and execute the REAL tool classes
 *   ([GetContextTool], [QueryItemsTool], [AdvanceItemTool]) against the resulting
 *   `composition.toolContext`.
 * - REST captures run through the REAL [installRestApiRoutes] with `ContentNegotiation` installed
 *   first (pattern: `ItemGateRouteTest`'s S15 production-topology wiring), reusing the SAME
 *   `composition.toolContext` / `composition.noteSchemaService` / repository provider the MCP
 *   captures use, so both surfaces observe one identical backing state.
 *
 * FIXTURES: the global layer is a deliberately pinned, seat-less snapshot of
 * `deploy/global-config/.taskorchestrator/config.yaml` (the process-schema floor) and the per-root layer is a
 * deliberately pinned, seat-less snapshot of this repo's own tracked `.taskorchestrator/config.yaml` (has
 * `default_traits`, a `delegated` dispatch profile, `skill`, `maxLength`, and a `default` schema - the
 * task-scope-addendum's stated S1 fixture), both taken at base f9d71a69 and committed under
 * `golden/a1-seatless/fixtures/`. They are intentionally NOT kept in sync with the live files (re-copying
 * would bring in seats and change the goldens); live-file validity is guarded by `LiveConfigFilesParseTest`.
 * They are loaded from the test classpath (never read from their live repo paths, so this test does not
 * depend on the working directory a future `gradlew` invocation happens to use). Four items with FIXED UUIDs ([FEATURE_TASK_ID], [BUG_FIX_ID],
 * [SCHEMA_FREE_ID], [TERMINAL_ID]) are created under one fixed root ([ROOT_ID]):
 * - `feature-task` in QUEUE, no notes filled -> missing `task-scope` (queue-phase gate).
 * - `bug-fix` in WORK, no notes filled -> missing `implementation-notes`/`session-tracking`/
 *   `test-manifest` (work-phase gate; trait `needs-test-author` via `default_traits`).
 * - an unmapped type in WORK -> schema-free (neither config layer declares it).
 * - `bug-fix` in TERMINAL, no notes filled -> terminal short-circuit regardless of schema.
 *
 * Every id embedded in a captured response is one of these four fixed constants or [ROOT_ID] —
 * literal and stable across every run by construction, so none needs normalization.
 *
 * GOLDEN MECHANISM: [normalizeGolden] normalizes exactly two field families (full rationale on the
 * function itself) and nothing else:
 * 1. `metadata.timestamp` and `metadata.version` — every MCP tool envelope (`ResponseUtil.createSuccessResponse`/
 *    `createErrorResponse`) always attaches `metadata: {timestamp, version}`
 *    (`ResponseUtilTest` "createMetadata includes timestamp and version"); the wall-clock
 *    `timestamp` varies every run, and `version` is the build version, which changes at every
 *    release: the recorded captures pinned `3.15.0` and the 3.16.0 version bump failed all nine
 *    MCP captures, so it is normalized as well. An earlier version of
 *    this test assumed no timestamp appeared in any of these response shapes — WRONG: that held
 *    for `data`, not for the MCP envelope wrapping it. Caught by the orchestrator's record+compare
 *    run against the untouched base, which failed on effectively all 9 MCP captures until this was
 *    fixed.
 * 2. `data.configFingerprint` on a `query_items` `schema`-operation success response (see
 *    `QueryItemsToolTest` "schema operation by type returns full entries and fingerprint" for the
 *    field's existence). Whether that value is a pure content hash (the GLOBAL-layer formula
 *    `configFingerprint(content)` asserted by `GlobalConfigFileTest` looks to be, content-only) or
 *    additionally reflects a per-root push identity (`PerRootConfigService.getFingerprint`, not
 *    among the declarations supplied to this blind author) cannot be confirmed without opening
 *    `src/main`, so it is normalized defensively rather than compared byte-for-byte.
 *
 * No other field in any of the 12 captures is normalized. In particular the REST `/gate` and REST
 * advance-422 response bodies carry no `metadata`/timestamp field of their own (their fields are
 * `itemId`/`title`/`role`/`gateStatus`/... and `error`/`details`/... — per `ItemGateRouteTest` /
 * `ItemWriteRoutesFailurePathTest`'s existing field-shape assertions), so [normalizeGolden]'s
 * timestamp rule is a no-op there by construction rather than by a REST-specific carve-out — it is
 * still applied (recursively, into the REST envelope's `body`) so the mechanism self-adjusts if a
 * REST shape ever gains the same wrapper, per the orchestrator's "check the REST responses too"
 * instruction.
 *
 * RECORD MODE — deviation from the literal dispatch, flagged for reviewer confirmation: the
 * dispatch asked for a JVM system property (`a1.golden.record=true`). `current/build.gradle.kts`'s
 * `tasks.test { }` block (read here, not edited — outside this stage's writable File-ownership
 * scope) forwards no `-D` system property from the `gradlew` command line into the forked test
 * worker JVM; only `user.timezone` is explicitly forwarded via `systemProperty(...)`. Gradle's
 * `Test` task DOES inherit the launching process's environment into the forked worker by default,
 * with no build-file change required — so this test reads the environment variable
 * [RECORD_ENV_VAR] (`A1_GOLDEN_RECORD`) instead of a system property. This is a self-resolved
 * ambiguity per the test-author protocol's carve-out (evidence: `current/build.gradle.kts`'s
 * `tasks.test` block, cited above); the reviewer should confirm this reasoning holds before relying
 * on it. Every golden the orchestrator has not yet recorded currently makes its test FAIL with a
 * "missing golden" message (never auto-created) until a record run is performed:
 *
 * ```powershell
 * # from the worktree root
 * $env:A1_GOLDEN_RECORD = "true"
 * & .\gradlew.bat ":current:test" "--tests" "io.github.jpicklyk.mcptask.current.interfaces.mcp.SeatlessResponseGoldenTest"
 * Remove-Item Env:\A1_GOLDEN_RECORD
 * # then re-run the SAME command without the env var set, to verify the recorded goldens compare clean.
 * ```
 *
 * A record run always FAILS each test it records (message: "recorded golden '<name>' ... re-run
 * WITHOUT A1_GOLDEN_RECORD to verify") so a record run can never read as a silent pass. Per the
 * dispatch contract, this test author does not run `:current:test` — recording and full-suite
 * verification are orchestrator-run.
 */
class SeatlessResponseGoldenTest {
    companion object {
        private val ROOT_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000000")
        private val FEATURE_TASK_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000001")
        private val BUG_FIX_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000002")
        private val SCHEMA_FREE_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000003")
        private val TERMINAL_ID: UUID = UUID.fromString("a1000000-0000-4000-8000-000000000004")

        private const val GOLDEN_RESOURCE_DIR = "golden/a1-seatless"
        private const val REPO_CONFIG_RESOURCE = "$GOLDEN_RESOURCE_DIR/fixtures/repo-config.yaml"
        private const val GLOBAL_CONFIG_RESOURCE = "$GOLDEN_RESOURCE_DIR/fixtures/global-config.yaml"

        /** See class KDoc "RECORD MODE" for why this is an env var rather than the dispatched `-D` property. */
        private const val RECORD_ENV_VAR = "A1_GOLDEN_RECORD"

        private val prettyJson = Json { prettyPrint = true }

        /** SF4: comparison must be exact STRING equality of the compact serialization (key order
         * significant), never [JsonElement] structural equality (which ignores key order). This is a
         * plain (non-pretty) [Json] instance so both sides serialize identically regardless of
         * whether the source was read pretty-printed (the golden file) or built in memory (the
         * actual, normalized capture). */
        private val compactJson = Json { }

        private fun classpathResourceText(path: String): String {
            val stream =
                SeatlessResponseGoldenTest::class.java.classLoader.getResourceAsStream(path)
                    ?: error("missing test resource on classpath: $path")
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Fixture wiring — REAL ServerComposition.build over H2, REAL per-root push
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildDatabaseManager(): DatabaseManager {
        val dbName = "a1_seatless_golden_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        DirectDatabaseSchemaManager().updateSchema()
        return DatabaseManager(database)
    }

    /**
     * Writes the classpath global-config fixture to a real file under [tempDir], as
     * [GlobalConfigFile] requires a Path. Written as explicit UTF-8 bytes (never the platform
     * default charset — this fixture's guidance text carries em-dashes (U+2014) that a
     * cp1252-default Windows JVM would otherwise mangle on write).
     */
    private fun materializeGlobalConfig(tempDir: Path): Path {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val file = configDir.resolve("config.yaml")
        Files.write(file, classpathResourceText(GLOBAL_CONFIG_RESOURCE).toByteArray(Charsets.UTF_8))
        return file
    }

    private class Fixture(
        val toolContext: ToolExecutionContext,
        val repositoryProvider: RepositoryProvider,
        val noteSchemaService: io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService,
        val degradedModePolicy: io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy,
        val idempotencyCache: io.github.jpicklyk.mcptask.current.application.service.IdempotencyCache,
    )

    /**
     * Builds the REAL production composition (global file layer + per-root pushed layer over H2)
     * and materializes the four fixed-id fixture items described in the class KDoc, under one
     * fixed root with the repo's own per-root config pushed to it.
     */
    private fun newFixture(tempDir: Path): Fixture {
        materializeGlobalConfig(tempDir)
        val appConfig =
            AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        val composition =
            ServerComposition(
                appConfig = appConfig,
                databaseManager = buildDatabaseManager(),
                shutdownCoordinator = null,
            ).build()

        val repo = composition.toolContext.repositoryProvider
        runBlocking {
            repo
                .workItemRepository()
                .create(
                    WorkItem(id = ROOT_ID, title = "A1 T0 golden root", type = "project", depth = 0),
                ).getOrNull() ?: error("fixture: root item creation failed")

            repo
                .projectConfigRepository()
                .upsert(ROOT_ID, classpathResourceText(REPO_CONFIG_RESOURCE))
                .getOrNull() ?: error("fixture: per-root config push failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = FEATURE_TASK_ID,
                        title = "A1 T0 golden feature-task",
                        type = "feature-task",
                        role = Role.QUEUE,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: feature-task item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = BUG_FIX_ID,
                        title = "A1 T0 golden bug-fix",
                        type = "bug-fix",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: bug-fix item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = SCHEMA_FREE_ID,
                        title = "A1 T0 golden schema-free",
                        type = "a1-t0-unmapped-type",
                        role = Role.WORK,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: schema-free item creation failed")

            repo
                .workItemRepository()
                .create(
                    WorkItem(
                        id = TERMINAL_ID,
                        title = "A1 T0 golden terminal",
                        type = "bug-fix",
                        role = Role.TERMINAL,
                        parentId = ROOT_ID,
                        rootId = ROOT_ID,
                        depth = 1,
                    ),
                ).getOrNull() ?: error("fixture: terminal item creation failed")
        }

        return Fixture(
            toolContext = composition.toolContext,
            repositoryProvider = repo,
            noteSchemaService = composition.noteSchemaService,
            degradedModePolicy = composition.degradedModePolicy,
            idempotencyCache = composition.idempotencyCache,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Golden mechanism
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * See class KDoc "GOLDEN MECHANISM". Normalizes exactly two field families, recursively (so a
     * REST capture's `body` sub-object — see [restEnvelope] — gets the same treatment as a
     * top-level MCP envelope):
     *
     * 1. `metadata.timestamp` — EVERY MCP tool envelope is built by `ResponseUtil.createSuccessResponse`
     *    / `createErrorResponse`, which always attaches `metadata: {timestamp, version}`
     *    (`ResponseUtilTest` "createMetadata includes timestamp and version": an ISO-8601
     *    wall-clock capture time). `metadata.version` (the build version) is normalized in the same
     *    step: it does not vary run-to-run, but it changes at every release. Confirmed absent from the REST `/gate` and REST
     *    advance-422 response bodies captured here (their fields are `itemId`/`title`/`role`/
     *    `gateStatus`/... and `error`/`details`/... respectively, per `ItemGateRouteTest` /
     *    `ItemWriteRoutesFailurePathTest`'s existing field-shape assertions — no `metadata` key),
     *    so in practice this only fires on the 9 MCP captures; it is applied uniformly (including
     *    to a REST `body`) rather than hand-listing which captures need it, so it self-adjusts if a
     *    REST shape ever gains the same envelope.
     * 2. `data.configFingerprint` on a `query_items` `schema`-operation success response — see the
     *    class KDoc for why this one is normalized defensively.
     *
     * Nothing else is touched.
     */
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
        if (success?.booleanOrNull == true && data != null && data.containsKey("configFingerprint")) {
            val patchedData = JsonObject(data + ("configFingerprint" to JsonPrimitive("<A1-T0-NORMALIZED-CONFIG-FINGERPRINT>")))
            result = JsonObject(result + ("data" to patchedData))
        }

        val body = result["body"] as? JsonObject
        if (body != null) {
            result = JsonObject(result + ("body" to normalizeGolden(body)))
        }

        return result
    }

    /** Resolves the golden `.json` file's on-disk location, matching the dispatch's "resolved from the project dir". */
    private fun goldenFile(name: String): File {
        val moduleDir = resolveCurrentModuleDir()
        return File(moduleDir, "src/test/resources/$GOLDEN_RESOURCE_DIR/$name.json")
    }

    /**
     * `:current:test`'s working directory is the `current/` module dir by default, so `user.dir`
     * usually already IS the module dir. Fall back to `user.dir/current` for an IDE run launched
     * from the repo root, so record mode works from either entry point.
     */
    private fun resolveCurrentModuleDir(): File {
        val userDir = File(System.getProperty("user.dir")).absoluteFile
        if (File(userDir, "build.gradle.kts").exists() && userDir.name == "current") return userDir
        val nested = File(userDir, "current")
        if (File(nested, "build.gradle.kts").exists()) return nested
        return userDir
    }

    /**
     * Explicit UTF-8 byte-level golden I/O — never `File.readText()`/`File.writeText()`'s or
     * `Files.writeString`'s charset DEFAULTS, always [Charsets.UTF_8] spelled out on both the write
     * and the read, and the comparison always runs over a String decoded from those explicit UTF-8
     * bytes on both sides. This machine (and CI Windows runners generally) is not guaranteed to
     * default to UTF-8 (cp1252), and this fixture's config guidance text carries em-dashes
     * (U+2014) that a platform-default read/write would mangle into `�` — exactly the failure the
     * orchestrator's record+compare run on the untouched base surfaced.
     */
    private fun compareOrRecord(
        name: String,
        actual: JsonElement,
    ) {
        val normalized = normalizeGolden(actual)
        val file = goldenFile(name)
        if (System.getenv(RECORD_ENV_VAR)?.equals("true", ignoreCase = true) == true) {
            file.parentFile.mkdirs()
            val jsonText = prettyJson.encodeToString(JsonElement.serializer(), normalized)
            Files.write(file.toPath(), jsonText.toByteArray(Charsets.UTF_8))
            fail(
                "recorded golden '$name' to ${file.absolutePath}; re-run WITHOUT $RECORD_ENV_VAR to verify " +
                    "— a record run must never be treated as a passing verification.",
            )
        }
        if (!file.exists()) {
            fail(
                "missing golden resource for '$name' at ${file.absolutePath} — a missing golden FAILS, it is " +
                    "never auto-created; run record mode first (see class KDoc \"RECORD MODE\").",
            )
        }
        val expectedText = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
        val expected = Json.parseToJsonElement(expectedText)

        // SF4: exact STRING equality of the compact serialization -- catches a key-order drift that
        // JsonElement structural equality (used previously) would silently ignore. Both sides go
        // through the SAME compact encoder so the golden file's own pretty-printing never matters.
        val expectedCompact = compactJson.encodeToString(JsonElement.serializer(), expected)
        val actualCompact = compactJson.encodeToString(JsonElement.serializer(), normalized)
        assertEquals(expectedCompact, actualCompact, "golden '$name' drifted from its recorded byte-identical snapshot")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP: get_context(itemId) x4
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `golden get_context feature-task QUEUE missing task-scope`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params = buildJsonObject { put("itemId", JsonPrimitive(FEATURE_TASK_ID.toString())) }
            val result = GetContextTool().execute(params, fx.toolContext)
            compareOrRecord("get_context_feature_task", result)
        }

    @Test
    fun `golden get_context bug-fix WORK missing notes across the trait-merged schema`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params = buildJsonObject { put("itemId", JsonPrimitive(BUG_FIX_ID.toString())) }
            val result = GetContextTool().execute(params, fx.toolContext)
            compareOrRecord("get_context_bug_fix", result)
        }

    @Test
    fun `golden get_context schema-free item`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params = buildJsonObject { put("itemId", JsonPrimitive(SCHEMA_FREE_ID.toString())) }
            val result = GetContextTool().execute(params, fx.toolContext)
            compareOrRecord("get_context_schema_free", result)
        }

    @Test
    fun `golden get_context TERMINAL item`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params = buildJsonObject { put("itemId", JsonPrimitive(TERMINAL_ID.toString())) }
            val result = GetContextTool().execute(params, fx.toolContext)
            compareOrRecord("get_context_terminal", result)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP: query_items(schema) by itemId x2, by type+rootId x1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `golden query_items schema by itemId feature-task`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("itemId", JsonPrimitive(FEATURE_TASK_ID.toString()))
                }
            val result = QueryItemsTool().execute(params, fx.toolContext)
            compareOrRecord("query_items_schema_item_feature_task", result)
        }

    @Test
    fun `golden query_items schema by itemId bug-fix`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("itemId", JsonPrimitive(BUG_FIX_ID.toString()))
                }
            val result = QueryItemsTool().execute(params, fx.toolContext)
            compareOrRecord("query_items_schema_item_bug_fix", result)
        }

    @Test
    fun `golden query_items schema by type bug-fix with rootId`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params =
                buildJsonObject {
                    put("operation", JsonPrimitive("schema"))
                    put("type", JsonPrimitive("bug-fix"))
                    put("rootId", JsonPrimitive(ROOT_ID.toString()))
                }
            val result = QueryItemsTool().execute(params, fx.toolContext)
            compareOrRecord("query_items_schema_type_bug_fix", result)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // MCP: advance_item start (feature-task, gate fail) + complete (bug-fix, gate fail)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `golden advance_item start on feature-task fails the queue gate`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(FEATURE_TASK_ID.toString()))
                                    put("trigger", JsonPrimitive("start"))
                                },
                            )
                        },
                    )
                }
            val result = AdvanceItemTool().execute(params, fx.toolContext)
            compareOrRecord("advance_start_feature_task", result)
        }

    @Test
    fun `golden advance_item complete on bug-fix fails the work gate`(
        @TempDir tempDir: Path,
    ): Unit =
        runBlocking {
            val fx = newFixture(tempDir)
            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(BUG_FIX_ID.toString()))
                                    put("trigger", JsonPrimitive("complete"))
                                },
                            )
                        },
                    )
                }
            val result = AdvanceItemTool().execute(params, fx.toolContext)
            compareOrRecord("advance_complete_bug_fix", result)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // REST: GET /items/{id}/gate x2, POST /items/{id}/advance 422 x1
    // ─────────────────────────────────────────────────────────────────────────

    private fun restEnvelope(
        status: HttpStatusCode,
        bodyText: String,
    ): JsonObject =
        buildJsonObject {
            put("status", JsonPrimitive(status.value))
            put("body", Json.parseToJsonElement(bodyText))
        }

    /** Mirrors `ItemGateRouteTest` S15's production-topology wiring: `apiTokenEntries` must be
     * derived from the SAME [ApiAuthConfig.Bearer] passed as `apiConfig`, or every request 401s. */
    private fun tokenEntriesFor(
        authConfig: ApiAuthConfig
    ): Map<io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.HashBytes, BearerTokenStore.TokenEntry> =
        (authConfig as? ApiAuthConfig.Bearer)?.tokens?.mapValues { (_, principal) ->
            BearerTokenStore.TokenEntry(principal, expiresAt = null)
        } ?: emptyMap()

    @Test
    fun `golden REST GET items id gate feature-task`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val fx = newFixture(tempDir)
            val authConfig = makeTestAuthConfig()
            application {
                install(ContentNegotiation) { json(McpJson) }
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = fx.repositoryProvider,
                    apiTokenEntries = tokenEntriesFor(authConfig),
                    allowQueryToken = false,
                    serverName = "a1-t0-golden",
                    serverVersion = "test",
                    actorAuthEnabled = false,
                    noteSchemaService = fx.noteSchemaService,
                    toolContext = fx.toolContext,
                    degradedModePolicy = fx.degradedModePolicy,
                    idempotencyCache = fx.idempotencyCache,
                )
            }
            val response =
                client.get("/api/v1/items/$FEATURE_TASK_ID/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            compareOrRecord("rest_gate_feature_task", restEnvelope(response.status, response.bodyAsText()))
        }

    @Test
    fun `golden REST GET items id gate bug-fix`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val fx = newFixture(tempDir)
            val authConfig = makeTestAuthConfig()
            application {
                install(ContentNegotiation) { json(McpJson) }
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = fx.repositoryProvider,
                    apiTokenEntries = tokenEntriesFor(authConfig),
                    allowQueryToken = false,
                    serverName = "a1-t0-golden",
                    serverVersion = "test",
                    actorAuthEnabled = false,
                    noteSchemaService = fx.noteSchemaService,
                    toolContext = fx.toolContext,
                    degradedModePolicy = fx.degradedModePolicy,
                    idempotencyCache = fx.idempotencyCache,
                )
            }
            val response =
                client.get("/api/v1/items/$BUG_FIX_ID/gate") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            compareOrRecord("rest_gate_bug_fix", restEnvelope(response.status, response.bodyAsText()))
        }

    @Test
    fun `golden REST POST items id advance feature-task returns 422 gate_blocked`(
        @TempDir tempDir: Path,
    ): Unit =
        testApplication {
            val fx = newFixture(tempDir)
            val authConfig = makeWriteAuthConfig()
            application {
                install(ContentNegotiation) { json(McpJson) }
                installRestApiRoutes(
                    apiConfig = authConfig,
                    eventBus = null,
                    effectiveProvider = fx.repositoryProvider,
                    apiTokenEntries = tokenEntriesFor(authConfig),
                    allowQueryToken = false,
                    serverName = "a1-t0-golden",
                    serverVersion = "test",
                    actorAuthEnabled = false,
                    noteSchemaService = fx.noteSchemaService,
                    toolContext = fx.toolContext,
                    degradedModePolicy = fx.degradedModePolicy,
                    idempotencyCache = fx.idempotencyCache,
                )
            }
            val response =
                client.post("/api/v1/items/$FEATURE_TASK_ID/advance") {
                    header("Authorization", "Bearer $WRITE_TOKEN")
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger":"start"}""")
                }
            compareOrRecord("rest_advance_422_feature_task", restEnvelope(response.status, response.bodyAsText()))
        }
}
