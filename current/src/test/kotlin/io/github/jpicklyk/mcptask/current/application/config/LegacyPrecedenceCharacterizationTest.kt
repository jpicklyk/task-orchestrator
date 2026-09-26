package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.SchemaSource
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlStatusLabelService
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * T0 — LEGACY precedence characterization, authored BEFORE the C2 move (EffectiveConfigResolver
 * extraction) against [ToolExecutionContext]'s EXISTING public surface at HEAD `65d86c6c`. Scenarios
 * S1-S5 and their oracles come from item `ce346f52`'s frozen `task-scope` (quirks Q1-Q15) and
 * `test-plan` notes — never from reading [ToolExecutionContext]'s source, which is out of bounds
 * for the test author per the `test-author` skill's blindness rule and this dispatch's ORCHESTRATOR
 * CONSTRAINT (no `PerRootConfigService.Snapshot`/`getSnapshot` reference; per-root config is always
 * supplied through a REAL [PerRootConfigService] over a REAL [SQLiteProjectConfigRepository] or a
 * thin failure-injecting wrapper around it; assertions go through [ToolExecutionContext]'s public
 * resolution methods only).
 *
 * This test MUST stay green across the C2 move (Part A + Part B) with no edits — it pins today's
 * behavior, not the refactor's internals.
 */
class LegacyPrecedenceCharacterizationTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var perRootConfigService: PerRootConfigService

    private lateinit var rootWithDefault: UUID
    private lateinit var rootNoDefault: UUID
    private lateinit var rootNoRow: UUID

    private lateinit var globalWithDefault: NoteSchemaService
    private lateinit var globalNoDefault: NoteSchemaService

    // ──────────────────────────────────────────────
    // Fixture config bodies (shared across S1/S2)
    // ──────────────────────────────────────────────

    private fun globalYaml(hasDefault: Boolean): String =
        buildString {
            appendLine("work_item_schemas:")
            appendLine("  container:")
            appendLine("    lifecycle: manual")
            appendLine("    notes:")
            appendLine("      - key: container-note")
            appendLine("        role: queue")
            appendLine("        required: false")
            appendLine("        description: \"c\"")
            appendLine("  bug:")
            appendLine("    notes:")
            appendLine("      - key: bug-note")
            appendLine("        role: queue")
            appendLine("        required: false")
            appendLine("        description: \"b\"")
            if (hasDefault) {
                appendLine("  default:")
                appendLine("    notes:")
                appendLine("      - key: global-default-note")
                appendLine("        role: queue")
                appendLine("        required: false")
                appendLine("        description: \"gd\"")
            }
        }

    private fun perRootYaml(hasDefault: Boolean): String =
        buildString {
            appendLine("work_item_schemas:")
            appendLine("  feature-task:")
            appendLine("    notes:")
            appendLine("      - key: feature-task-note")
            appendLine("        role: queue")
            appendLine("        required: false")
            appendLine("        description: \"ft\"")
            appendLine("  bug-fix:")
            appendLine("    notes:")
            appendLine("      - key: bug-fix-note")
            appendLine("        role: queue")
            appendLine("        required: false")
            appendLine("        description: \"bf\"")
            if (hasDefault) {
                appendLine("  default:")
                appendLine("    notes:")
                appendLine("      - key: per-root-default-note")
                appendLine("        role: queue")
                appendLine("        required: false")
                appendLine("        description: \"prd\"")
            }
        }

    private fun writeGlobalYamlFile(content: String): Path {
        val path = Files.createTempFile("legacy-precedence-global", ".yaml")
        Files.writeString(path, content)
        return path
    }

    private fun writeGlobalYaml(content: String): NoteSchemaService = YamlWorkItemSchemaService(writeGlobalYamlFile(content))

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            perRootConfigService = PerRootConfigService(projectConfigRepository)

            val rWithDefault = WorkItem(title = "Root with per-root default")
            workItemRepository.create(rWithDefault)
            rootWithDefault = rWithDefault.id
            projectConfigRepository.upsert(rootWithDefault, perRootYaml(hasDefault = true))

            val rNoDefault = WorkItem(title = "Root without per-root default")
            workItemRepository.create(rNoDefault)
            rootNoDefault = rNoDefault.id
            projectConfigRepository.upsert(rootNoDefault, perRootYaml(hasDefault = false))

            val rNoRow = WorkItem(title = "Rooted item, no config row")
            workItemRepository.create(rNoRow)
            rootNoRow = rNoRow.id
            // deliberately no upsert() for rootNoRow

            globalWithDefault = writeGlobalYaml(globalYaml(hasDefault = true))
            globalNoDefault = writeGlobalYaml(globalYaml(hasDefault = false))
        }

    private fun makeItem(
        type: String?,
        tags: List<String>,
        rootId: UUID?
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Legacy precedence item",
            type = type,
            tags = if (tags.isEmpty()) null else tags.joinToString(","),
            rootId = rootId,
            depth = 0
        )

    private fun contextFor(
        global: NoteSchemaService,
        perRoot: PerRootConfigService?
    ): ToolExecutionContext = ToolExecutionContext(mockk(relaxed = true), global, perRootConfigService = perRoot)

    // ──────────────────────────────────────────────
    // S1 oracle — Q1-Q4: whole-algorithm-first per-root-then-global precedence, both for the
    // type step and the tag step, using the REAL global/per-root services as ground truth for
    // each layer's OWN internal fallback (default-folding on the global side per Q2; no fold on
    // the per-root side per PerRootConfigServiceTest's `getSchemaForType` contract) — only the
    // CROSS-layer composition below is hand-derived from Q1-Q4, never from TEC's source.
    // ──────────────────────────────────────────────

    private suspend fun expectedResolution(
        type: String?,
        tags: List<String>,
        rootId: UUID?,
        global: NoteSchemaService,
        perRoot: PerRootConfigService
    ): Triple<WorkItemSchema, SchemaSource, String?>? {
        suspend fun perRootLookup(key: String): WorkItemSchema? = if (rootId == null) null else perRoot.getSchemaForType(rootId, key)

        // Step 1 (Q1): type lookup, per-root exact -> per-root "default" -> global exact -> global "default".
        if (type != null) {
            val prMatch = perRootLookup(type) ?: perRootLookup("default")
            if (prMatch != null) {
                return Triple(prMatch, SchemaSource.PER_ROOT, perRoot.getFingerprint(rootId!!))
            }
            val gMatch = global.getSchemaForType(type)
            if (gMatch != null) {
                return Triple(gMatch, SchemaSource.GLOBAL, global.getConfigFingerprint())
            }
        }

        // Step 2 (Q3): tag lookup, per-root first-matching-tag -> per-root "default", independent
        // of whatever the type step above did.
        run {
            var matched: WorkItemSchema? = null
            for (tag in tags) {
                val m = perRootLookup(tag)
                if (m != null) {
                    matched = m
                    break
                }
            }
            if (matched == null) matched = perRootLookup("default")
            if (matched != null) {
                return Triple(matched, SchemaSource.PER_ROOT, perRoot.getFingerprint(rootId!!))
            }
        }

        // Step 3 (Q4): global tag probe. tagNotes folds default; matchedType is "default" for an
        // empty tag list, else the first tag whose single-tag lookup is non-null, else "default".
        val tagNotes = global.getSchemaForTags(tags)
        val matchedType =
            if (tags.isEmpty()) {
                "default"
            } else {
                tags.firstOrNull { global.getSchemaForTags(listOf(it)) != null } ?: "default"
            }
        val synthesized = global.getSchemaForType(matchedType) ?: tagNotes?.let { WorkItemSchema(type = matchedType, notes = it) }
        return synthesized?.let { Triple(it, SchemaSource.GLOBAL, global.getConfigFingerprint()) }
    }

    private enum class RowState { WITH_DEFAULT, NO_DEFAULT, NO_ROW, NULL_ROOT }

    private fun rootIdFor(state: RowState): UUID? =
        when (state) {
            RowState.WITH_DEFAULT -> rootWithDefault
            RowState.NO_DEFAULT -> rootNoDefault
            RowState.NO_ROW -> rootNoRow
            RowState.NULL_ROOT -> null
        }

    @Test
    fun `S1 - full precedence matrix over per-root state x global-default x type x tags (128 cases)`(): Unit =
        runBlocking {
            val rowStates = RowState.entries
            val gDefaults = listOf(false, true)
            val types = listOf("feature-task", "container", "unknown", null)
            val tagSets = listOf(emptyList(), listOf("bug-fix"), listOf("bug"), listOf("unknown", "bug"))

            var casesRun = 0
            for (rowState in rowStates) {
                val rootId = rootIdFor(rowState)
                for (gDefault in gDefaults) {
                    val global = if (gDefault) globalWithDefault else globalNoDefault
                    for (type in types) {
                        for (tags in tagSets) {
                            val label = "rowState=$rowState gDefault=$gDefault type=$type tags=$tags"
                            val item = makeItem(type, tags, rootId)
                            val ctx = contextFor(global, perRootConfigService)

                            val expected = expectedResolution(type, tags, rootId, global, perRootConfigService)
                            val actual = ctx.resolveSchemaWithSource(item)

                            if (expected == null) {
                                assertNull(actual, label)
                            } else {
                                assertNotNull(actual, label)
                                assertEquals(expected.first, actual.schema, "$label - schema/notes/lifecycleMode")
                                assertEquals(expected.second, actual.source, "$label - source")
                                assertEquals(expected.third, actual.fingerprint, "$label - fingerprint")
                            }
                            casesRun++
                        }
                    }
                }
            }
            assertEquals(128, casesRun, "sanity: the declared matrix must run exactly 128 cases")
        }

    // ──────────────────────────────────────────────
    // S2 — resolveTypeSchema is step (1) only: no tag fallback, unlike resolveSchema/
    // resolveSchemaWithSource.
    // ──────────────────────────────────────────────

    @Test
    fun `S2 - resolveTypeSchema resolves via per-root exact match, mirroring the type step of S1`(): Unit =
        runBlocking {
            val ctx = contextFor(globalNoDefault, perRootConfigService)
            val result = ctx.resolveTypeSchema("feature-task", rootWithDefault)

            assertNotNull(result)
            assertEquals(SchemaSource.PER_ROOT, result.source)
            assertEquals(listOf("feature-task-note"), result.schema.notes.map { it.key })
        }

    @Test
    fun `S2 - resolveTypeSchema falls through to the global exact type match when per-root misses`(): Unit =
        runBlocking {
            val ctx = contextFor(globalNoDefault, perRootConfigService)
            val result = ctx.resolveTypeSchema("container", rootNoDefault)

            assertNotNull(result)
            assertEquals(SchemaSource.GLOBAL, result.source)
            assertEquals(listOf("container-note"), result.schema.notes.map { it.key })
        }

    @Test
    fun `S2 - resolveTypeSchema returns null on a total type miss, unlike resolveSchema which falls back to tags`(): Unit =
        runBlocking {
            val ctx = contextFor(globalNoDefault, perRootConfigService)

            // "unknown" matches neither per-root (rootNoDefault has no "unknown"/"default") nor
            // global (globalNoDefault has no "unknown"/"default") at the type step.
            val typeOnly = ctx.resolveTypeSchema("unknown", rootNoDefault)
            assertNull(typeOnly, "resolveTypeSchema must not fall back to tags")

            // The SAME item, resolved through resolveSchema with a tag that DOES match, succeeds —
            // proving the null above is specifically the missing tag-fallback step, not a broken setup.
            val itemWithMatchingTag = makeItem(type = "unknown", tags = listOf("bug"), rootId = rootNoDefault)
            val viaFullResolution = ctx.resolveSchema(itemWithMatchingTag)
            assertNotNull(viaFullResolution, "sanity: the tag path this type-only call skips does resolve")
            assertEquals(listOf("bug-note"), viaFullResolution.notes.map { it.key })
        }

    // ──────────────────────────────────────────────
    // S3 — one real fixture per facet: trait notes, resources, dispatch, registry, note_limits,
    // status labels, availableTraits. One root/global pair carrying all facets at once (real YAML,
    // parsed by the real services — no PerRootConfigService.Snapshot mocking).
    // ──────────────────────────────────────────────

    private val s3PerRootYaml =
        """
        work_item_schemas:
          feature-task:
            default_traits: [trait-a]
            notes:
              - key: base-note
                role: queue
                required: false
                description: "base"
        traits:
          trait-a:
            resources: [per-root-resource]
            dispatch:
              work: { agent: per-root-agent }
            notes:
              - key: trait-a-per-root-note
                role: queue
                required: false
                description: "pr"
        note_limits:
          mode: reject
        status_labels:
          start: "root-started"
          complete: null
        resources:
          shared-key:
            description: "per-root version"
            defaultTtlSeconds: 111
        """.trimIndent()

    private val s3GlobalYaml =
        """
        work_item_schemas:
          feature-task:
            notes:
              - key: base-note-global
                role: queue
                required: false
                description: "base-global"
        traits:
          trait-a:
            resources: [global-resource]
            dispatch:
              work: { agent: global-agent }
            notes:
              - key: trait-a-global-note
                role: queue
                required: false
                description: "pg"
          trait-b:
            notes:
              - key: trait-b-note
                role: queue
                required: false
                description: "b"
        note_limits:
          mode: warn
        status_labels:
          start: "global-started"
          complete: "global-done"
          cascade: "global-cascaded"
        resources:
          shared-key:
            description: "global version"
            defaultTtlSeconds: 222
        """.trimIndent()

    private lateinit var s3Root: UUID
    private lateinit var s3Global: NoteSchemaService
    private lateinit var s3Context: ToolExecutionContext

    private fun setUpS3(): Unit =
        runBlocking {
            val root = WorkItem(title = "S3 facet root")
            workItemRepository.create(root)
            s3Root = root.id
            projectConfigRepository.upsert(s3Root, s3PerRootYaml)
            val s3GlobalPath = writeGlobalYamlFile(s3GlobalYaml)
            s3Global = YamlWorkItemSchemaService(s3GlobalPath)
            // The S3 fixture asserts fall-through to an EXPLICIT global status label (Q11), so TEC
            // must be wired with a statusLabelService built from the SAME global file — otherwise it
            // stays at its declared default NoOpStatusLabelService and never sees this file's
            // status_labels section at all.
            s3Context =
                ToolExecutionContext(
                    mockk(relaxed = true),
                    s3Global,
                    statusLabelService = YamlStatusLabelService(s3GlobalPath),
                    perRootConfigService = perRootConfigService
                )
        }

    @Test
    fun `S3 - trait notes merge per-root over global (per-root trait list replaces global wholesale)`(): Unit =
        runBlocking {
            setUpS3()
            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = s3Root)
            val resolved = s3Context.resolveSchema(item)!!

            assertEquals(listOf("base-note", "trait-a-per-root-note"), resolved.notes.map { it.key })
        }

    @Test
    fun `S3 - resources union per trait, per-root wins over global for the same trait`(): Unit =
        runBlocking {
            setUpS3()
            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = s3Root)
            val reqs = s3Context.resolveResourceRequirements(item)

            assertEquals(listOf("per-root-resource"), reqs.map { it.key })
        }

    @Test
    fun `S3 - dispatch first trait with a profile for the role wins, per-root wins over global`(): Unit =
        runBlocking {
            setUpS3()
            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = s3Root)
            val schema = s3Context.resolveSchema(item)
            val profile = s3Context.resolveDispatchProfile(item, Role.WORK, schema)

            assertEquals(DispatchProfile(agent = "per-root-agent"), profile)
        }

    @Test
    fun `S3 - resource registry merges non-colliding entries, global wins on collision`(): Unit =
        runBlocking {
            setUpS3()
            val registry = s3Context.resolveResourceRegistry(s3Root)

            assertEquals(1, registry.size)
            assertEquals("global version", registry["shared-key"]?.description, "global wins on the colliding key")
            assertEquals(222, registry["shared-key"]?.defaultTtlSeconds)
        }

    @Test
    fun `S3 - note_limits mode is per-root explicit over global`(): Unit =
        runBlocking {
            setUpS3()
            assertEquals("reject", s3Context.resolveNoteLimitsMode(s3Root))
        }

    @Test
    fun `S3 - status labels honor an explicit per-root null without falling through, and fall through when absent`(): Unit =
        runBlocking {
            setUpS3()
            assertEquals("root-started", s3Context.resolveStatusLabel("start", s3Root))
            assertNull(
                s3Context.resolveStatusLabel("complete", s3Root),
                "an explicit null per-root value must win over the global label, not fall through"
            )
            assertEquals(
                "global-cascaded",
                s3Context.resolveStatusLabel("cascade", s3Root),
                "a trigger absent from the per-root map must fall through to global"
            )
        }

    @Test
    fun `S3 - availableTraits unions per-root keys then global, distinct`(): Unit =
        runBlocking {
            setUpS3()
            val traits = s3Context.availableTraits(listOf(s3Root))

            assertEquals(setOf("trait-a", "trait-b"), traits.toSet())
        }

    // ──────────────────────────────────────────────
    // S4 — read counts (Q13/Q15), counted at the repository's getFingerprint accessor via a spy
    // over a REAL SQLiteProjectConfigRepository — never via PerRootConfigService.getSnapshot.
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - resolveSchemaWithSource performs exactly one per-root repository read`(): Unit =
        runBlocking {
            val spyRepo = spyk(projectConfigRepository)
            val perRoot = PerRootConfigService(spyRepo)
            val ctx = contextFor(globalNoDefault, perRoot)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootWithDefault)
            ctx.resolveSchemaWithSource(item)

            coVerify(exactly = 1) { spyRepo.getFingerprint(rootWithDefault) }
        }

    @Test
    fun `S4 - the 3-arg resolveDispatchProfile overload with an empty trait list performs zero per-root reads`(): Unit =
        runBlocking {
            val spyRepo = spyk(projectConfigRepository)
            val perRoot = PerRootConfigService(spyRepo)
            val ctx = contextFor(globalNoDefault, perRoot)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = rootWithDefault)
            val schemaWithNoTraits = WorkItemSchema(type = "feature-task", notes = emptyList())
            ctx.resolveDispatchProfile(item, Role.WORK, schemaWithNoTraits)

            coVerify(exactly = 0) { spyRepo.getFingerprint(any()) }
        }

    @Test
    fun `S4 - a null rootId performs zero per-root reads`(): Unit =
        runBlocking {
            val spyRepo = spyk(projectConfigRepository)
            val perRoot = PerRootConfigService(spyRepo)
            val ctx = contextFor(globalNoDefault, perRoot)

            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = null)
            ctx.resolveSchemaWithSource(item)

            coVerify(exactly = 0) { spyRepo.getFingerprint(any()) }
        }

    @Test
    fun `S4 - availableTraits reads once per element of rootIds, duplicates included`(): Unit =
        runBlocking {
            val spyRepo = spyk(projectConfigRepository)
            val perRoot = PerRootConfigService(spyRepo)
            val ctx = contextFor(globalNoDefault, perRoot)

            ctx.availableTraits(listOf(rootWithDefault, rootWithDefault))

            coVerify(exactly = 2) { spyRepo.getFingerprint(rootWithDefault) }
        }

    // ──────────────────────────────────────────────
    // S5 — failure handling (CON oracle: PerRootConfigService's "Failure handling" KDoc). A cold
    // read failure throws PerRootConfigUnavailableException uncaught (Q14); a warm-then-error read
    // serves the last-known-good parse; an unparseable stored row is absence, not error, and falls
    // through to global.
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
    fun `S5 - a cold per-root read failure throws PerRootConfigUnavailableException, never falling back to global`(): Unit =
        runBlocking {
            val wrapper = FailableProjectConfigRepository(projectConfigRepository)
            val perRoot = PerRootConfigService(wrapper)

            val root = WorkItem(title = "S5 cold-failure root")
            workItemRepository.create(root)
            projectConfigRepository.upsert(root.id, perRootYaml(hasDefault = false))
            wrapper.failFingerprint = true // cold: no successful read has ever happened on this service

            val ctx = contextFor(globalWithDefault, perRoot)
            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = root.id)

            assertFailsWith<PerRootConfigUnavailableException> { ctx.resolveSchema(item) }
        }

    @Test
    fun `S5 - a warm-then-error read serves the last-known-good per-root schema instead of throwing`(): Unit =
        runBlocking {
            val wrapper = FailableProjectConfigRepository(projectConfigRepository)
            val perRoot = PerRootConfigService(wrapper)

            val root = WorkItem(title = "S5 warm-then-error root")
            workItemRepository.create(root)
            projectConfigRepository.upsert(root.id, perRootYaml(hasDefault = false))

            val ctx = contextFor(globalWithDefault, perRoot)
            val item = makeItem(type = "feature-task", tags = emptyList(), rootId = root.id)

            val warm = ctx.resolveSchema(item)
            assertNotNull(warm, "sanity: the warm read must succeed before injecting failures")
            assertEquals(listOf("feature-task-note"), warm.notes.map { it.key })

            wrapper.failFingerprint = true
            val servedDuringFailure = ctx.resolveSchema(item)

            assertEquals(warm, servedDuringFailure, "a read failure must serve the LKG unchanged, not throw")
        }

    @Test
    fun `S5 - an unparseable stored row is treated as absence and falls through to the global schema`(): Unit =
        runBlocking {
            val root = WorkItem(title = "S5 malformed-row root")
            workItemRepository.create(root)
            projectConfigRepository.upsert(root.id, "work_item_schemas: [\ninvalid yaml: :\n  - broken")

            val ctx = contextFor(globalWithDefault, perRootConfigService)
            val item = makeItem(type = "container", tags = emptyList(), rootId = root.id)

            val result = ctx.resolveSchemaWithSource(item)

            assertNotNull(result, "malformed YAML must not surface as an exception or as a schema-free result here")
            assertEquals(SchemaSource.GLOBAL, result.source, "absence (malformed row) falls through to the global layer")
            assertEquals(listOf("container-note"), result.schema.notes.map { it.key })
        }
}
