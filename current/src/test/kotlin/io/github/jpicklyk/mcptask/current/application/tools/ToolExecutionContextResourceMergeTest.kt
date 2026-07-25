package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ToolExecutionContext.resolveResourceRequirements] and
 * [ToolExecutionContext.resolveResourceRegistry] — the resource-declaration resolution layer (T4).
 * No enforcement/leasing is exercised here (that's a follow-on task); these tests only verify the
 * config-merge algorithm.
 */
class ToolExecutionContextResourceMergeTest {
    private lateinit var noteSchemaService: NoteSchemaService
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        noteSchemaService = mockk()
        every { noteSchemaService.getSchemaForType(any()) } returns null
        every { noteSchemaService.getSchemaForTags(any()) } returns null
        every { noteSchemaService.getTraitResources(any()) } returns emptyList()
        every { noteSchemaService.getResourceRegistry() } returns emptyMap()

        val repoProvider = mockk<RepositoryProvider>(relaxed = true)
        context = ToolExecutionContext(repoProvider, noteSchemaService)
    }

    private fun makeItem(
        type: String? = null,
        tags: String? = null,
        properties: String? = null,
        rootId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            tags = tags,
            properties = properties,
            rootId = rootId,
            depth = 0
        )

    // ──────────────────────────────────────────────
    // Union across defaultTraits + item traits, dedup
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements unions defaultTraits and item traits without duplicating keys`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("trait-a") } returns listOf(ResourceRequirement(key = "resource-a"))
            every { noteSchemaService.getTraitResources("trait-b") } returns listOf(ResourceRequirement(key = "resource-b"))

            val item =
                makeItem(
                    type = "feature-task",
                    properties = """{"traits": ["trait-a", "trait-b"]}"""
                )

            val result = context.resolveResourceRequirements(item)

            assertEquals(2, result.size)
            assertEquals(setOf("resource-a", "resource-b"), result.map { it.key }.toSet())
        }

    @Test
    fun `resolveResourceRequirements dedups the same key declared by two traits into one entry`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a", "trait-b")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("trait-a") } returns
                listOf(ResourceRequirement(key = "shared-resource", mode = ResourceMode.ADVISORY))
            every { noteSchemaService.getTraitResources("trait-b") } returns
                listOf(ResourceRequirement(key = "shared-resource", mode = ResourceMode.ADVISORY))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("shared-resource", result[0].key)
        }

    // ──────────────────────────────────────────────
    // Exclusive-wins mode conflict; first-seen wins ttlSeconds
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements resolves a mode conflict with exclusive winning regardless of order`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-advisory-first", "trait-exclusive-second")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("trait-advisory-first") } returns
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.ADVISORY, ttlSeconds = 100))
            every { noteSchemaService.getTraitResources("trait-exclusive-second") } returns
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.EXCLUSIVE, ttlSeconds = 200))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals(ResourceMode.EXCLUSIVE, result[0].mode)
            // first-seen wins for ttlSeconds — the advisory trait's 100 is retained, not overwritten by 200.
            assertEquals(100, result[0].ttlSeconds)
        }

    @Test
    fun `resolveResourceRequirements keeps exclusive when the second trait also declares exclusive`() =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a", "trait-b")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("trait-a") } returns
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.EXCLUSIVE))
            every { noteSchemaService.getTraitResources("trait-b") } returns
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.ADVISORY))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals(ResourceMode.EXCLUSIVE, result[0].mode)
        }

    // ──────────────────────────────────────────────
    // Schema-free item still resolves from properties traits
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements resolves from item properties traits even when no schema resolves`() =
        runBlocking {
            // No type, no tags — resolveBaseSchemaWithSource returns null (schema-free item).
            every { noteSchemaService.getSchemaForTags(emptyList()) } returns null
            every { noteSchemaService.getTraitResources("needs-staging-db") } returns
                listOf(ResourceRequirement(key = "staging-db-credential"))

            val item =
                makeItem(
                    type = null,
                    tags = null,
                    properties = """{"traits": ["needs-staging-db"]}"""
                )

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("staging-db-credential", result[0].key)
        }

    @Test
    fun `resolveResourceRequirements returns empty list when the item has no traits at all`() =
        runBlocking {
            val item = makeItem(type = "feature-task")
            every { noteSchemaService.getSchemaForType("feature-task") } returns
                WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = emptyList())

            val result = context.resolveResourceRequirements(item)

            assertTrue(result.isEmpty())
        }

    // ──────────────────────────────────────────────
    // Per-root trait layering beats global
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements per-root trait resources override the global trait definition`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("needs-staging-db")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("needs-staging-db") } returns
                listOf(ResourceRequirement(key = "global-resource"))

            val perRootRequirement = ResourceRequirement(key = "per-root-resource", mode = ResourceMode.ADVISORY)
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp",
                    traitResources = mapOf("needs-staging-db" to listOf(perRootRequirement)),
                    resourceRegistry = emptyMap()
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("per-root-resource", result[0].key)
            // The global trait-resource lookup must be short-circuited once the per-root layer provides entries.
            verify(exactly = 0) { noteSchemaService.getTraitResources("needs-staging-db") }
        }

    @Test
    fun `resolveResourceRequirements falls through to global trait resources when per-root has none for that trait`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("needs-staging-db")
                )
            every { noteSchemaService.getSchemaForType("feature-task") } returns baseSchema
            every { noteSchemaService.getTraitResources("needs-staging-db") } returns
                listOf(ResourceRequirement(key = "global-resource"))

            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp",
                    traitResources = emptyMap(),
                    resourceRegistry = emptyMap()
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("global-resource", result[0].key)
        }

    // ──────────────────────────────────────────────
    // resolveResourceRegistry: global-wins collision (inverted vs trait layering)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRegistry merges non-colliding per-root and global entries`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp",
                    traitResources = emptyMap(),
                    resourceRegistry = mapOf("per-root-only" to ResourceDefinition(key = "per-root-only"))
                )
            every { noteSchemaService.getResourceRegistry() } returns
                mapOf("global-only" to ResourceDefinition(key = "global-only"))

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val result = ctx.resolveResourceRegistry(rootId)

            assertEquals(setOf("per-root-only", "global-only"), result.keys)
        }

    @Test
    fun `resolveResourceRegistry lets the global definition win on a colliding key`() =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val perRootDef = ResourceDefinition(key = "shared-key", description = "per-root version", defaultTtlSeconds = 111)
            val globalDef = ResourceDefinition(key = "shared-key", description = "global version", defaultTtlSeconds = 222)

            coEvery { perRoot.getSnapshot(rootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp",
                    traitResources = emptyMap(),
                    resourceRegistry = mapOf("shared-key" to perRootDef)
                )
            every { noteSchemaService.getResourceRegistry() } returns mapOf("shared-key" to globalDef)

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val result = ctx.resolveResourceRegistry(rootId)

            assertEquals(1, result.size)
            assertEquals(globalDef, result["shared-key"], "Global definition must win over the per-root one on collision")
        }

    @Test
    fun `resolveResourceRegistry with null rootId returns the global registry unchanged`() =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            every { noteSchemaService.getResourceRegistry() } returns
                mapOf("global-only" to ResourceDefinition(key = "global-only"))

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, noteSchemaService, perRootConfigService = perRoot)

            val result = ctx.resolveResourceRegistry(null)

            assertEquals(setOf("global-only"), result.keys)
        }
}
