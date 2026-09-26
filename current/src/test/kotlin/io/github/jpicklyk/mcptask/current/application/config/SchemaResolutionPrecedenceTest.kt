package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `8879f554`
 * (scenarios S1-S6, S8, S13). Exercises [LayeredConfig] and [EffectiveConfigResolver] directly for
 * the mode-dependent precedence table (`task-scope` "Behaviour table (SCHEMA facet only)") and the
 * `effectiveMode` computation rule, plus one D2 regression pair through
 * [ToolExecutionContext]'s default (`ServiceBacked`) construction path and one cold-failure
 * pair through a real [PerRootConfigService].
 *
 * Fixture (per `test-plan`): G={container(manual),bug,default}; P={feature-task,bug-fix,default}
 * + schema_resolution per row. Oracles come exclusively from the frozen notes' precedence table
 * and effective-mode rule -- never from reading [LayeredConfig]'s or [EffectiveConfigResolver]'s
 * source, which is out of bounds for the test author per the `test-author` skill's blindness rule.
 */
class SchemaResolutionPrecedenceTest {
    // ──────────────────────────────────────────────
    // Fixtures
    // ──────────────────────────────────────────────

    private val globalContainerSchema =
        WorkItemSchema(
            type = "container",
            lifecycleMode = LifecycleMode.MANUAL,
            notes = listOf(NoteSchemaEntry(key = "container-note", role = Role.QUEUE))
        )
    private val globalBugSchema = WorkItemSchema(type = "bug", notes = listOf(NoteSchemaEntry(key = "bug-note", role = Role.QUEUE)))
    private val globalDefaultSchema =
        WorkItemSchema(type = "default", notes = listOf(NoteSchemaEntry(key = "global-default-note", role = Role.QUEUE)))

    private fun globalLookup(
        schemaResolution: SchemaResolutionMode?,
        hasDefault: Boolean = true
    ): GlobalConfigLookup {
        val schemas = mutableMapOf("container" to globalContainerSchema, "bug" to globalBugSchema)
        if (hasDefault) schemas["default"] = globalDefaultSchema
        val doc = ConfigDocument(workItemSchemas = schemas, traits = emptyMap(), schemaResolution = schemaResolution)
        return LayerBackedGlobalLookup(ConfigLayer(doc, "global-fp", ConfigSource.GLOBAL))
    }

    private val perRootFeatureTaskSchema =
        WorkItemSchema(type = "feature-task", notes = listOf(NoteSchemaEntry(key = "feature-task-note", role = Role.QUEUE)))
    private val perRootBugFixSchema =
        WorkItemSchema(type = "bug-fix", notes = listOf(NoteSchemaEntry(key = "bug-fix-note", role = Role.QUEUE)))
    private val perRootDefaultSchema =
        WorkItemSchema(type = "default", notes = listOf(NoteSchemaEntry(key = "per-root-default-note", role = Role.QUEUE)))

    private fun perRootLayer(
        schemaResolution: SchemaResolutionMode?,
        hasDefault: Boolean = true
    ): ConfigLayer {
        val schemas = mutableMapOf("feature-task" to perRootFeatureTaskSchema, "bug-fix" to perRootBugFixSchema)
        if (hasDefault) schemas["default"] = perRootDefaultSchema
        val doc = ConfigDocument(workItemSchemas = schemas, traits = emptyMap(), schemaResolution = schemaResolution)
        return ConfigLayer(doc, "pr-fp", ConfigSource.PER_ROOT)
    }

    // ──────────────────────────────────────────────
    // S1 — D1 opt-in, absent/legacy = LEGACY, no default flip
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - per-root layered mode resolves an unmatched type to the global exact schema (D1)`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveBaseSchema(type = "container", tags = emptyList())!!

        assertEquals(ConfigSource.GLOBAL, result.source)
        assertEquals("container", result.schema.type)
        assertEquals(LifecycleMode.MANUAL, result.schema.lifecycleMode)
    }

    @Test
    fun `S1 - an absent schema_resolution key defaults to LEGACY, so the per-root default shadows the global exact type (D1)`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(schemaResolution = null), globalLookup(null))

        val result = config.resolveBaseSchema(type = "container", tags = emptyList())!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    @Test
    fun `S1 - an explicit legacy key behaves identically to an absent key (D1)`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LEGACY), globalLookup(null))

        val result = config.resolveBaseSchema(type = "container", tags = emptyList())!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    // ──────────────────────────────────────────────
    // S2 — layered mode, unknown type, tag-driven fallback chain
    // ──────────────────────────────────────────────

    @Test
    fun `S2 - layered mode, unknown type with tags=(bug-fix) resolves to the per-root exact tag`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveBaseSchema("unknown", listOf("bug-fix"))!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("bug-fix", result.schema.type)
    }

    @Test
    fun `S2 - layered mode, unknown type with tags=(bug) falls through to the global exact tag`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveBaseSchema("unknown", listOf("bug"))!!

        assertEquals(ConfigSource.GLOBAL, result.source)
        assertEquals("bug", result.schema.type)
    }

    @Test
    fun `S2 - layered mode, unknown type with no tags falls through to the per-root default`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveBaseSchema("unknown", emptyList())!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    // ──────────────────────────────────────────────
    // S3 — per-root tag matching agrees across every mode; a global-only tag match diverges
    // ──────────────────────────────────────────────

    @Test
    fun `S3 - untyped item with tags=(x, bug-fix) resolves to the per-root exact tag match in every mode`() {
        for (mode in listOf(SchemaResolutionMode.LEGACY, SchemaResolutionMode.LAYERED, SchemaResolutionMode.ISOLATED)) {
            val config = LayeredConfig(UUID.randomUUID(), perRootLayer(mode), globalLookup(null))

            val result = config.resolveBaseSchema(null, listOf("x", "bug-fix"))!!

            assertEquals(ConfigSource.PER_ROOT, result.source, "mode=$mode")
            assertEquals("bug-fix", result.schema.type, "mode=$mode")
        }
    }

    @Test
    fun `S3 - untyped item with tags=(bug) diverges per mode - legacy and isolated fall to the per-root default, layered reaches global`() {
        val legacy = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LEGACY), globalLookup(null))
        val legacyResult = legacy.resolveBaseSchema(null, listOf("bug"))!!
        assertEquals(ConfigSource.PER_ROOT, legacyResult.source)
        assertEquals("default", legacyResult.schema.type)

        val layered = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))
        val layeredResult = layered.resolveBaseSchema(null, listOf("bug"))!!
        assertEquals(ConfigSource.GLOBAL, layeredResult.source)
        assertEquals("bug", layeredResult.schema.type)

        val isolated = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED), globalLookup(null))
        val isolatedResult = isolated.resolveBaseSchema(null, listOf("bug"))!!
        assertEquals(ConfigSource.PER_ROOT, isolatedResult.source)
        assertEquals("default", isolatedResult.schema.type)
    }

    // ──────────────────────────────────────────────
    // S4 — isolated mode never reaches the global layer
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - isolated mode resolves an unmatched type to the per-root default, never consulting global`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED), globalLookup(null))

        val result = config.resolveBaseSchema("container", emptyList())!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    @Test
    fun `S4 - isolated mode with no per-root default and an untyped unmatched tag resolves to null`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED, hasDefault = false), globalLookup(null))

        assertNull(config.resolveBaseSchema(null, listOf("bug")))
    }

    @Test
    fun `S4 - isolated mode never invokes a schema-lookup method on the global layer`() {
        var calls = 0
        val real = globalLookup(null)
        val counting =
            object : GlobalConfigLookup by real {
                override fun schemaForType(type: String): WorkItemSchema? {
                    calls++
                    return real.schemaForType(type)
                }

                override fun notesForTags(tags: List<String>): List<NoteSchemaEntry>? {
                    calls++
                    return real.notesForTags(tags)
                }

                override fun exactSchema(key: String): WorkItemSchema? {
                    calls++
                    return real.exactSchema(key)
                }

                override fun hasExactTagSchema(tag: String): Boolean {
                    calls++
                    return real.hasExactTagSchema(tag)
                }
            }
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED), counting)

        config.resolveBaseSchema("container", listOf("bug", "bug-fix"))
        config.resolveTypeSchema("container")

        assertEquals(0, calls, "ISOLATED mode must never call a global schema-lookup method")
    }

    // ──────────────────────────────────────────────
    // S5 — resolveTypeSchema (no tag fallback) per mode
    // ──────────────────────────────────────────────

    @Test
    fun `S5 - resolveTypeSchema in layered mode falls to the global exact type when per-root misses`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveTypeSchema("container")!!

        assertEquals(ConfigSource.GLOBAL, result.source)
        assertEquals("container", result.schema.type)
    }

    @Test
    fun `S5 - resolveTypeSchema in isolated mode falls to the per-root default, never reaching global`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED), globalLookup(null))

        val result = config.resolveTypeSchema("container")!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    @Test
    fun `S5 - resolveTypeSchema in layered mode falls all the way to the per-root default on a total type miss`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LAYERED), globalLookup(null))

        val result = config.resolveTypeSchema("unknown")!!

        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    @Test
    fun `S5 - resolveTypeSchema in isolated mode with no per-root default resolves a total type miss to null`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.ISOLATED, hasDefault = false), globalLookup(null))

        assertNull(config.resolveTypeSchema("unknown"))
    }

    // ──────────────────────────────────────────────
    // S6 — effectiveMode computation rule
    // ──────────────────────────────────────────────

    @Test
    fun `S6 - a per-root row without the schema_resolution key falls through to the global mode`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(schemaResolution = null), globalLookup(SchemaResolutionMode.LAYERED))

        assertEquals(SchemaResolutionMode.LAYERED, config.effectiveMode)
        val result = config.resolveBaseSchema("container", emptyList())!!
        assertEquals(ConfigSource.GLOBAL, result.source)
    }

    @Test
    fun `S6 - no per-root row at all falls through to the global mode identically`() {
        val config = LayeredConfig(rootId = UUID.randomUUID(), perRoot = null, global = globalLookup(SchemaResolutionMode.LAYERED))

        assertEquals(SchemaResolutionMode.LAYERED, config.effectiveMode)
        val result = config.resolveBaseSchema("container", emptyList())!!
        assertEquals(ConfigSource.GLOBAL, result.source)
    }

    @Test
    fun `S6 - a rootless item with no per-root row uses the global mode and its tag probe`() {
        val config = LayeredConfig(rootId = null, perRoot = null, global = globalLookup(SchemaResolutionMode.LAYERED))

        val result = config.resolveBaseSchema("task", listOf("bug"))!!

        assertEquals(ConfigSource.GLOBAL, result.source)
        assertEquals("bug", result.schema.type)
    }

    @Test
    fun `S6 - an explicit per-root legacy key overrides a layered global mode`() {
        val config = LayeredConfig(UUID.randomUUID(), perRootLayer(SchemaResolutionMode.LEGACY), globalLookup(SchemaResolutionMode.LAYERED))

        assertEquals(SchemaResolutionMode.LEGACY, config.effectiveMode)
        val result = config.resolveBaseSchema("container", emptyList())!!
        assertEquals(ConfigSource.PER_ROOT, result.source)
        assertEquals("default", result.schema.type)
    }

    @Test
    fun `S6 - no schema_resolution key anywhere defaults to legacy, so a rootless untyped item falls through to the global default`() {
        val config = LayeredConfig(rootId = null, perRoot = null, global = globalLookup(schemaResolution = null))

        assertEquals(SchemaResolutionMode.LEGACY, config.effectiveMode)
        val result = config.resolveBaseSchema("task", listOf("no-such-tag"))!!
        assertEquals(ConfigSource.GLOBAL, result.source)
        assertEquals("default", result.schema.type)
    }

    // ──────────────────────────────────────────────
    // S8 — D2 tag-probe fix, legacy mode, exercised through ToolExecutionContext's default
    // (ServiceBacked) construction path
    // ──────────────────────────────────────────────

    private fun writeGlobalYamlFile(content: String): Path {
        val path = Files.createTempFile("schema-resolution-precedence-global", ".yaml")
        Files.writeString(path, content)
        return path
    }

    private val s8GlobalYaml =
        """
        work_item_schemas:
          bug:
            notes:
              - key: bug-note
                role: queue
                required: false
                description: "b"
          default:
            notes:
              - key: global-default-note
                role: queue
                required: false
                description: "gd"
        """.trimIndent()

    @Test
    fun `S8 - legacy mode, rootless, resolves the first exact tag over the global default via TEC's default construction`(): Unit =
        runBlocking {
            val global = YamlWorkItemSchemaService(writeGlobalYamlFile(s8GlobalYaml))
            val ctx = ToolExecutionContext(mockk(relaxed = true), global)

            val itemWithBug = WorkItem(id = UUID.randomUUID(), title = "i", type = null, tags = "x,bug", rootId = null, depth = 0)
            val resolvedBug = ctx.resolveSchema(itemWithBug)!!
            assertEquals(listOf("bug-note"), resolvedBug.notes.map { it.key }, "the first EXACTLY matching tag (bug) must win, per D2")

            val itemWithOnlyUnknown = WorkItem(id = UUID.randomUUID(), title = "i2", type = null, tags = "x", rootId = null, depth = 0)
            val resolvedDefault = ctx.resolveSchema(itemWithOnlyUnknown)!!
            assertEquals(listOf("global-default-note"), resolvedDefault.notes.map { it.key })
        }

    @Test
    fun `S8 - legacy mode tag probe applies identically for a rooted item whose per-root config has no default`(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val perRootConfigService = PerRootConfigService(projectConfigRepository)

            val root = WorkItem(title = "S8 root")
            workItemRepository.create(root)
            projectConfigRepository.upsert(root.id, "work_item_schemas:\n  feature-task:\n    notes: []\n")

            val global = YamlWorkItemSchemaService(writeGlobalYamlFile(s8GlobalYaml))
            val ctx = ToolExecutionContext(mockk(relaxed = true), global, perRootConfigService = perRootConfigService)

            val itemWithBug = WorkItem(id = UUID.randomUUID(), title = "i", type = null, tags = "x,bug", rootId = root.id, depth = 0)
            val resolvedBug = ctx.resolveSchema(itemWithBug)!!
            assertEquals(listOf("bug-note"), resolvedBug.notes.map { it.key })
        }

    // ──────────────────────────────────────────────
    // S13 — a cold per-root read failure in LAYERED mode still throws, never falling back to
    // global (CON oracle: PerRootConfigService "Failure handling" KDoc, unchanged by C4)
    // ──────────────────────────────────────────────

    private class FailableProjectConfigRepository(
        private val delegate: ProjectConfigRepository
    ) : ProjectConfigRepository by delegate {
        @Volatile var failFingerprint: Boolean = false

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (failFingerprint) {
                Result.Error(RepositoryError.DatabaseError("boom"))
            } else {
                delegate.getFingerprint(rootItemId)
            }
    }

    @Test
    fun `S13 - a cold per-root read failure throws in layered mode too, never falling back to global`(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            val realRepo = SQLiteProjectConfigRepository(databaseManager)
            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val wrapper = FailableProjectConfigRepository(realRepo)
            val perRootConfigService = PerRootConfigService(wrapper)

            val root = WorkItem(title = "S13 cold-failure root")
            workItemRepository.create(root)
            realRepo.upsert(root.id, "work_item_schemas:\n  feature-task:\n    notes: []\n")
            wrapper.failFingerprint = true // cold: no successful read has ever happened on this service

            val resolver = EffectiveConfigResolver(globalLookup(schemaResolution = SchemaResolutionMode.LAYERED), perRootConfigService)
            val item = WorkItem(id = UUID.randomUUID(), title = "S13 item", type = "unknown", tags = null, rootId = root.id, depth = 0)

            assertFailsWith<PerRootConfigUnavailableException> { resolver.resolveSchema(item) }
        }
}
