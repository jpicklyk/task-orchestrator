package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.shutdown.ShutdownCoordinator
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.extension.RegisterExtension
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen task-scope/test-plan/task-scope-addendum notes on item
 * 09cd604f (stage A2a) -- the "Config (frozen keys)" section: top-level independence: parsing
 * (mode off|warn|reject default warn, require_verified default false, invalid-value fallback,
 * unknown-sub-key warning, non-map handling) via YamlSchemaParser.parseRoot directly (mirrors
 * ConfigDocumentParseTest's SafeConstructor-based style and note_limits precedent), plus per-root
 * wholesale layering (S12) via EffectiveConfigResolver (mirrors EffectiveConfigResolverTest's
 * FakePerRootConfigSource style) and the push-validation ignoredSections contract (mirrors
 * ProjectConfigPushServiceTest's real-SQLite style).
 *
 * NEW-SURFACE: the independence: top-level section, ConfigDocument.independence,
 * GlobalConfigLookup.independencePolicy(), LayeredConfig.independencePolicy(), and
 * EffectiveConfigResolver.resolveIndependencePolicy() are all introduced by this item. No plain
 * revert can yield behavioral red; per the dispatch contract's Test author protocol rule 7,
 * red-proof is orchestrator-run against the addendum's M12/M12b mutation recipes.
 *
 * Oracle: task-scope-addendum "Config (frozen keys)" section, applied by hand -- never read from
 * the parser's or resolver's own source. ("mirrors note_limits" is the addendum's own citation for
 * the wholesale-per-root-wins precedent this file exercises.)
 */
class IndependenceConfigParseTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    // ──────────────────────────────────────────────
    // Parse-level: YamlSchemaParser.parseRoot direct
    // ──────────────────────────────────────────────

    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    private val noSchemaSection = "work_item_schemas:\n  default:\n    notes: []\n"

    @Test
    fun `independence absent yields a null policy`() {
        assertNull(parse(noSchemaSection).independence)
    }

    @Test
    fun `independence mode off warn and reject parse to their enum values`() {
        // "off" must be YAML-quoted: SafeConstructor (YAML 1.1) coerces a bare off/on/yes/no scalar
        // to a Boolean before YamlSchemaParser ever sees a String to match against IndependenceMode.
        assertEquals(IndependenceMode.OFF, parse("independence:\n  mode: \"off\"\n$noSchemaSection").independence!!.mode)
        assertEquals(IndependenceMode.WARN, parse("independence:\n  mode: warn\n$noSchemaSection").independence!!.mode)
        assertEquals(IndependenceMode.REJECT, parse("independence:\n  mode: reject\n$noSchemaSection").independence!!.mode)
    }

    @Test
    fun `probe -- an unquoted off value is coerced to YAML Boolean false by SafeConstructor -- falls back to warn plus a warning`() {
        val doc = parse("independence:\n  mode: off\n$noSchemaSection")
        assertEquals(
            IndependenceMode.WARN,
            doc.independence!!.mode,
            "an unquoted 'off' is a YAML 1.1 boolean literal, not a string -- honest limit"
        )
        assertTrue(doc.warnings.any { it.contains("independence") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `independence present as an empty map yields the all-defaults policy with no warning`() {
        val doc = parse("independence: {}\n$noSchemaSection")
        assertEquals(IndependencePolicy.DEFAULT, doc.independence)
        assertTrue(doc.warnings.none { it.contains("independence") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `independence with an invalid mode falls back to warn plus exactly one warning`() {
        val doc = parse("independence:\n  mode: bogus-mode\n$noSchemaSection")
        assertEquals(IndependenceMode.WARN, doc.independence!!.mode)
        assertEquals(1, doc.warnings.count { it.contains("independence") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `independence mode uppercase REJECT is invalid -- fromConfigString is exact-lowercase only -- falls back to warn`() {
        val doc = parse("independence:\n  mode: REJECT\n$noSchemaSection")
        assertEquals(IndependenceMode.WARN, doc.independence!!.mode)
        assertTrue(doc.warnings.any { it.contains("independence") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `require_verified true parses to a boolean true`() {
        val doc = parse("independence:\n  mode: reject\n  require_verified: true\n$noSchemaSection")
        assertEquals(IndependenceMode.REJECT, doc.independence!!.mode)
        assertTrue(doc.independence!!.requireVerified)
    }

    @Test
    fun `require_verified non-boolean falls back to false plus a warning naming require_verified`() {
        val doc = parse("independence:\n  require_verified: maybe\n$noSchemaSection")
        assertFalse(doc.independence!!.requireVerified)
        assertTrue(doc.warnings.any { it.contains("require_verified") }, "warnings: ${doc.warnings}")
    }

    @Test
    fun `an unknown sub-key under independence warns and names the key, the known mode key still applies`() {
        val doc = parse("independence:\n  mode: warn\n  colour: red\n$noSchemaSection")
        assertTrue(doc.warnings.any { it.contains("colour") }, "warnings: ${doc.warnings}")
        assertEquals(IndependenceMode.WARN, doc.independence!!.mode, "the known mode key must still apply despite the unknown sibling")
    }

    @Test
    fun `a non-map independence value warns and is treated as block-present-with-defaults`() {
        val doc = parse("independence: 5\n$noSchemaSection")
        assertEquals(
            IndependencePolicy.DEFAULT,
            doc.independence,
            "task-scope-addendum: a non-map value is treated as block present with defaults, not absent"
        )
        assertTrue(doc.warnings.any { it.contains("independence") }, "warnings: ${doc.warnings}")
    }

    // ──────────────────────────────────────────────
    // S12a/S12b -- per-root wholesale precedence through the REAL ServerComposition + a REAL
    // per-root push (orchestrator arbitration, 59a0d98e round: the prior FakeGlobalLookup test
    // double violated the Public-API/Harness rule -- replaced with the real path, mirroring
    // SeatPerRootResolverTest's buildComposition/createRoot/pushPerRoot pattern, asserted through
    // ToolExecutionContext.resolveIndependencePolicy(rootId) -- a public resolver method, not a
    // hand-built replica).
    // ──────────────────────────────────────────────

    private fun buildDatabaseManager(): DatabaseManager = sqliteDb.databaseManager

    private fun materializeGlobalConfig(
        tempDir: java.nio.file.Path,
        content: String
    ) {
        val configDir = tempDir.resolve(".taskorchestrator")
        java.nio.file.Files
            .createDirectories(configDir)
        java.nio.file.Files
            .write(configDir.resolve("config.yaml"), content.toByteArray(Charsets.UTF_8))
    }

    private fun buildComposition(
        tempDir: java.nio.file.Path,
        globalConfig: String
    ): io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult {
        materializeGlobalConfig(tempDir, globalConfig)
        val appConfig = AppConfig.fromEnv { key -> if (key == "AGENT_CONFIG_DIR") tempDir.toString() else null }
        return io.github.jpicklyk.mcptask.current.interfaces.mcp
            .ServerComposition(appConfig = appConfig, databaseManager = buildDatabaseManager(), shutdownCoordinator = ShutdownCoordinator())
            .build()
    }

    private fun createRoot(
        composition: io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult,
        title: String
    ): UUID =
        runBlocking {
            composition.toolContext.repositoryProvider
                .workItemRepository()
                .create(WorkItem(title = title, type = "project", depth = 0))!!
                .id
        }

    private fun pushPerRoot(
        composition: io.github.jpicklyk.mcptask.current.interfaces.mcp.CompositionResult,
        rootId: UUID,
        yaml: String
    ) {
        runBlocking {
            composition.toolContext.repositoryProvider
                .projectConfigRepository()
                .upsert(rootId, yaml)
                ?: error("fixture: per-root push failed for $rootId")
        }
    }

    @Test
    fun `S12a global-only -- a root with no per-root push resolves the global independence policy`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path
    ): Unit =
        runBlocking {
            val globalYaml =
                """
                independence:
                  mode: reject
                  require_verified: true
                work_item_schemas:
                  default:
                    notes: []
                """.trimIndent()
            val composition = buildComposition(tempDir, globalYaml)
            val root = createRoot(composition, "S12a root")
            val policy = composition.toolContext.resolveIndependencePolicy(root)
            assertEquals(IndependencePolicy(IndependenceMode.REJECT, requireVerified = true), policy)
        }

    @Test
    fun `S12b a per-root independence block replaces the global block WHOLESALE -- no merge of sub-keys`(
        @org.junit.jupiter.api.io.TempDir tempDir: java.nio.file.Path
    ): Unit =
        runBlocking {
            val globalYaml =
                """
                independence:
                  mode: "off"
                  require_verified: true
                work_item_schemas:
                  default:
                    notes: []
                """.trimIndent()
            val composition = buildComposition(tempDir, globalYaml)
            val pushedRoot = createRoot(composition, "S12b pushed root")
            // The per-root block sets ONLY mode; if require_verified merged from global rather than
            // replacing wholesale, the pushed root would resolve requireVerified=true instead of the
            // IndependencePolicy default (false).
            val perRootYaml =
                """
                independence:
                  mode: reject
                work_item_schemas:
                  default:
                    notes: []
                """.trimIndent()
            pushPerRoot(composition, pushedRoot, perRootYaml)
            val controlRoot = createRoot(composition, "S12b control root (no push)")

            val pushedPolicy = composition.toolContext.resolveIndependencePolicy(pushedRoot)
            assertEquals(IndependencePolicy(IndependenceMode.REJECT, requireVerified = false), pushedPolicy)

            // Control root: no push at all -- must still see the global policy unchanged.
            val controlPolicy = composition.toolContext.resolveIndependencePolicy(controlRoot)
            assertEquals(IndependencePolicy(IndependenceMode.OFF, requireVerified = true), controlPolicy)
            assertFalse(pushedPolicy == controlPolicy, "the pushed root's policy must differ from the un-pushed control root's")
        }

    // ──────────────────────────────────────────────
    // S12c -- push validation honors independence: it is never listed in ignoredSections
    // ──────────────────────────────────────────────

    @Test
    fun `S12c pushing a per-root independence block is honored, never listed in ignoredSections`(): Unit =
        runBlocking {
            val databaseManager = sqliteDb.databaseManager
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository
            val service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser, sqliteDb.unitOfWork())
            val rootId = workItemRepository.create(WorkItem(title = "Root", type = "project")).id

            val yaml = "independence:\n  mode: reject\nwork_item_schemas:\n  default:\n    notes: []\n"
            val result = service.push(rootId, yaml)
            assertTrue(result is ProjectConfigPushResult.Success, "push result: $result")
            val success = result as ProjectConfigPushResult.Success
            assertFalse(success.ignoredSections.contains("independence"), "ignoredSections: ${success.ignoredSections}")
        }

    // ──────────────────────────────────────────────
    // S12d -- an unknown sub-key under a pushed independence block still warns via schemaWarnings
    // ──────────────────────────────────────────────

    @Test
    fun `S12d a pushed independence block with an unknown sub-key still warns, naming the key`(): Unit =
        runBlocking {
            val databaseManager = sqliteDb.databaseManager
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository
            val service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser, sqliteDb.unitOfWork())
            val rootId = workItemRepository.create(WorkItem(title = "Root", type = "project")).id

            val yaml = "independence:\n  mode: warn\n  colour: red\nwork_item_schemas:\n  default:\n    notes: []\n"
            val result = service.push(rootId, yaml)
            assertTrue(result is ProjectConfigPushResult.Success, "push result: $result")
            val success = result as ProjectConfigPushResult.Success
            assertFalse(success.ignoredSections.contains("independence"), "the section itself is honored: ${success.ignoredSections}")
            assertTrue(success.schemaWarnings.any { it.contains("colour") }, "schemaWarnings: ${success.schemaWarnings}")
        }
}
