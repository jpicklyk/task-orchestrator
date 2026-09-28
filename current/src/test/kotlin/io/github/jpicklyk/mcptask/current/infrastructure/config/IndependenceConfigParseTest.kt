package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigLookup
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushResult
import io.github.jpicklyk.mcptask.current.application.service.ProjectConfigPushService
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.SeatDefinition
import io.github.jpicklyk.mcptask.current.domain.model.SeatDispatchOverride
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
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
 * ProjectConfigPushServiceTest's real-H2 style).
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
    // S12b -- per-root wholesale precedence via EffectiveConfigResolver (mirrors note_limits, Q10)
    // ──────────────────────────────────────────────

    private class FakePerRootConfigSource(
        private val layers: Map<UUID, ConfigLayer?>
    ) : PerRootConfigSource {
        override suspend fun layer(rootId: UUID): ConfigLayer? = layers[rootId]
    }

    // A minimal GlobalConfigLookup test double that returns a fixed independencePolicy() and
    // schema-free/empty defaults for every other member. Deliberately avoids constructing
    // ServiceBackedGlobalLookup from a bare YAML file path here: that constructor shape was not
    // among this dispatch's supplied declarations (self-resolved earlier from a pre-A2 test
    // file), and a two-service composition of independencePolicy() is exactly the kind of wiring
    // this item's own declarations do not describe -- exercising EffectiveConfigResolver's
    // wholesale-replacement logic directly against the GlobalConfigLookup INTERFACE (which IS a
    // supplied declaration) avoids that ambiguity entirely.
    private class FakeGlobalLookup(
        private val policy: IndependencePolicy
    ) : GlobalConfigLookup {
        override fun schemaForType(type: String): WorkItemSchema? = null

        override fun notesForTags(tags: List<String>): List<NoteSchemaEntry>? = null

        override fun traitNotes(name: String): List<NoteSchemaEntry>? = null

        override fun traitResources(name: String): List<ResourceRequirement> = emptyList()

        override fun traitDispatch(name: String): Map<Role, DispatchProfile> = emptyMap()

        override fun resourceRegistry(): Map<String, ResourceDefinition> = emptyMap()

        override fun noteLimitsMode(): String = "warn"

        override fun fingerprint(): String? = null

        override fun traitNames(): List<String> = emptyList()

        override fun statusLabel(trigger: String): String? = null

        override fun exactSchema(key: String): WorkItemSchema? = null

        override fun hasExactTagSchema(tag: String): Boolean = false

        override fun schemaResolution(): SchemaResolutionMode? = null

        override fun traitSeats(name: String): List<SeatDefinition> = emptyList()

        override fun traitDispatchBySeat(name: String): Map<Role, Map<String, SeatDispatchOverride>> = emptyMap()

        override fun independencePolicy(): IndependencePolicy = policy
    }

    @Test
    fun `S12a global-only -- a root with no per-root push resolves the global independence policy`(): Unit =
        runBlocking {
            val globalLookup = FakeGlobalLookup(IndependencePolicy(IndependenceMode.REJECT, requireVerified = true))
            val resolver = EffectiveConfigResolver(globalLookup, FakePerRootConfigSource(emptyMap()))
            val policy = resolver.resolveIndependencePolicy(UUID.randomUUID())
            assertEquals(IndependencePolicy(IndependenceMode.REJECT, requireVerified = true), policy)
        }

    @Test
    fun `S12b a per-root independence block replaces the global block WHOLESALE -- no merge of sub-keys`(): Unit =
        runBlocking {
            val globalLookup = FakeGlobalLookup(IndependencePolicy(IndependenceMode.OFF, requireVerified = true))
            // The per-root block sets ONLY mode; if require_verified merged from global rather than
            // replacing wholesale, the pushed root would resolve requireVerified=true instead of the
            // IndependencePolicy default (false).
            val perRootDoc =
                ConfigDocument(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    independence = IndependencePolicy(mode = IndependenceMode.REJECT, requireVerified = false)
                )
            val pushedRoot = UUID.randomUUID()
            val resolver =
                EffectiveConfigResolver(
                    globalLookup,
                    FakePerRootConfigSource(mapOf(pushedRoot to ConfigLayer(perRootDoc, "pr-fp", ConfigSource.PER_ROOT)))
                )

            val pushedPolicy = resolver.resolveIndependencePolicy(pushedRoot)
            assertEquals(IndependencePolicy(IndependenceMode.REJECT, requireVerified = false), pushedPolicy)

            // Control root: no push at all -- must still see the global policy unchanged.
            val controlPolicy = resolver.resolveIndependencePolicy(UUID.randomUUID())
            assertEquals(IndependencePolicy(IndependenceMode.OFF, requireVerified = true), controlPolicy)
            assertFalse(pushedPolicy == controlPolicy, "the pushed root's policy must differ from the un-pushed control root's")
        }

    // ──────────────────────────────────────────────
    // S12c -- push validation honors independence: it is never listed in ignoredSections
    // ──────────────────────────────────────────────

    @Test
    fun `S12c pushing a per-root independence block is honored, never listed in ignoredSections`(): Unit =
        runBlocking {
            val dbName = "indep_config_push_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            DirectDatabaseSchemaManager().updateSchema()
            val databaseManager = DatabaseManager(database)
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository
            val service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser)
            val rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id

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
            val dbName = "indep_config_push_warn_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            DirectDatabaseSchemaManager().updateSchema()
            val databaseManager = DatabaseManager(database)
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository
            val service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser)
            val rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id

            val yaml = "independence:\n  mode: warn\n  colour: red\nwork_item_schemas:\n  default:\n    notes: []\n"
            val result = service.push(rootId, yaml)
            assertTrue(result is ProjectConfigPushResult.Success, "push result: $result")
            val success = result as ProjectConfigPushResult.Success
            assertFalse(success.ignoredSections.contains("independence"), "the section itself is honored: ${success.ignoredSections}")
            assertTrue(success.schemaWarnings.any { it.contains("colour") }, "schemaWarnings: ${success.schemaWarnings}")
        }
}
