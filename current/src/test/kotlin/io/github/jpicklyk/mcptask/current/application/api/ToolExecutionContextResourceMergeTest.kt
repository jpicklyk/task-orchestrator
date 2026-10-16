package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.ResourceDefinition
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.test.MutableServerConfig
import io.mockk.coEvery
import io.mockk.mockk
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
    private lateinit var server: MutableServerConfig
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        server = MutableServerConfig()

        val repoProvider = mockk<RepositoryProvider>(relaxed = true)
        context = ToolExecutionContext(repoProvider, configResolver = server.resolver())
    }

    private fun makeItem(
        type: String? = null,
        tags: String? = null,
        traits: List<String> = emptyList(),
        rootId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = UUID.randomUUID(),
            title = "Test Item",
            type = type,
            tags = tags,
            traits = traits,
            rootId = rootId,
            depth = 0
        )

    // ──────────────────────────────────────────────
    // Union across defaultTraits + item traits, dedup
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements unions defaultTraits and item traits without duplicating keys`(): Unit =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["trait-a"] = listOf(ResourceRequirement(key = "resource-a"))
            server.traitResources["trait-b"] = listOf(ResourceRequirement(key = "resource-b"))

            val item =
                makeItem(
                    type = "feature-task",
                    traits = listOf("trait-a", "trait-b")
                )

            val result = context.resolveResourceRequirements(item)

            assertEquals(2, result.size)
            assertEquals(setOf("resource-a", "resource-b"), result.map { it.key }.toSet())
        }

    @Test
    fun `resolveResourceRequirements dedups the same key declared by two traits into one entry`(): Unit =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a", "trait-b")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["trait-a"] = listOf(ResourceRequirement(key = "shared-resource", mode = ResourceMode.ADVISORY))
            server.traitResources["trait-b"] = listOf(ResourceRequirement(key = "shared-resource", mode = ResourceMode.ADVISORY))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("shared-resource", result[0].key)
        }

    // ──────────────────────────────────────────────
    // Exclusive-wins mode conflict; first-seen wins ttlSeconds
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements resolves a mode conflict with exclusive winning regardless of order`(): Unit =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-advisory-first", "trait-exclusive-second")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["trait-advisory-first"] =
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.ADVISORY, ttlSeconds = 100))
            server.traitResources["trait-exclusive-second"] =
                listOf(ResourceRequirement(key = "shared", mode = ResourceMode.EXCLUSIVE, ttlSeconds = 200))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals(ResourceMode.EXCLUSIVE, result[0].mode)
            // first-seen wins for ttlSeconds — the advisory trait's 100 is retained, not overwritten by 200.
            assertEquals(100, result[0].ttlSeconds)
        }

    @Test
    fun `resolveResourceRequirements keeps exclusive when the second trait also declares exclusive`(): Unit =
        runBlocking {
            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("trait-a", "trait-b")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["trait-a"] = listOf(ResourceRequirement(key = "shared", mode = ResourceMode.EXCLUSIVE))
            server.traitResources["trait-b"] = listOf(ResourceRequirement(key = "shared", mode = ResourceMode.ADVISORY))

            val item = makeItem(type = "feature-task")

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals(ResourceMode.EXCLUSIVE, result[0].mode)
        }

    // ──────────────────────────────────────────────
    // Schema-free item still resolves from properties traits
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements resolves from item properties traits even when no schema resolves`(): Unit =
        runBlocking {
            // No type, no tags — resolveBaseSchemaWithSource returns null (schema-free item).
            server.traitResources["needs-staging-db"] = listOf(ResourceRequirement(key = "staging-db-credential"))

            val item =
                makeItem(
                    type = null,
                    tags = null,
                    traits = listOf("needs-staging-db")
                )

            val result = context.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("staging-db-credential", result[0].key)
        }

    @Test
    fun `resolveResourceRequirements returns empty list when the item has no traits at all`(): Unit =
        runBlocking {
            val item = makeItem(type = "feature-task")
            server.schemas["feature-task"] = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = emptyList())

            val result = context.resolveResourceRequirements(item)

            assertTrue(result.isEmpty())
        }

    // ──────────────────────────────────────────────
    // Per-root trait layering beats global
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRequirements per-root trait resources override the global trait definition`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("needs-staging-db")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["needs-staging-db"] = listOf(ResourceRequirement(key = "global-resource"))

            val perRootRequirement = ResourceRequirement(key = "per-root-resource", mode = ResourceMode.ADVISORY)
            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = mapOf("needs-staging-db" to emptyList()),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitResources = mapOf("needs-staging-db" to listOf(perRootRequirement)),
                            resourceRegistry = emptyMap(),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, perRootConfigService = perRoot, configResolver = server.resolver(perRoot))

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("per-root-resource", result[0].key)
        }

    @Test
    fun `resolveResourceRequirements falls through to global trait resources when per-root has none for that trait`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()

            val baseSchema =
                WorkItemSchema(
                    type = "feature-task",
                    notes = emptyList(),
                    defaultTraits = listOf("needs-staging-db")
                )
            server.schemas["feature-task"] = baseSchema
            server.traitResources["needs-staging-db"] = listOf(ResourceRequirement(key = "global-resource"))

            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitResources = emptyMap(),
                            resourceRegistry = emptyMap(),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, perRootConfigService = perRoot, configResolver = server.resolver(perRoot))

            val item = makeItem(type = "feature-task", rootId = rootId)
            val result = ctx.resolveResourceRequirements(item)

            assertEquals(1, result.size)
            assertEquals("global-resource", result[0].key)
        }

    // ──────────────────────────────────────────────
    // resolveResourceRegistry: the server registry only (a per-root registry is ignored)
    // ──────────────────────────────────────────────

    @Test
    fun `resolveResourceRegistry ignores a non-colliding per-root entry`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitResources = emptyMap(),
                            resourceRegistry = mapOf("per-root-only" to ResourceDefinition(key = "per-root-only")),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )
            server.resourceRegistry.putAll(mapOf("global-only" to ResourceDefinition(key = "global-only")))

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, perRootConfigService = perRoot, configResolver = server.resolver(perRoot))

            val result = ctx.resolveResourceRegistry(rootId)

            assertEquals(setOf("global-only"), result.keys, "a per-root resources registry is never honored")
        }

    @Test
    fun `resolveResourceRegistry uses the server definition on a colliding key`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val perRoot = mockk<PerRootConfigService>()
            val perRootDef = ResourceDefinition(key = "shared-key", description = "per-root version", defaultTtlSeconds = 111)
            val globalDef = ResourceDefinition(key = "shared-key", description = "global version", defaultTtlSeconds = 222)

            coEvery { perRoot.layer(rootId) } returns
                ConfigLayer(
                    document =
                        ConfigDocument(
                            workItemSchemas = emptyMap(),
                            traits = emptyMap(),
                            noteLimitsMode = null,
                            statusLabels = null,
                            traitResources = emptyMap(),
                            resourceRegistry = mapOf("shared-key" to perRootDef),
                        ),
                    fingerprint = "fp",
                    source = ConfigSource.PER_ROOT,
                )
            server.resourceRegistry.putAll(mapOf("shared-key" to globalDef))

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, perRootConfigService = perRoot, configResolver = server.resolver(perRoot))

            val result = ctx.resolveResourceRegistry(rootId)

            assertEquals(1, result.size)
            assertEquals(globalDef, result["shared-key"], "Global definition must win over the per-root one on collision")
        }

    @Test
    fun `resolveResourceRegistry with null rootId returns the global registry unchanged`(): Unit =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            server.resourceRegistry.putAll(mapOf("global-only" to ResourceDefinition(key = "global-only")))

            val repoProvider = mockk<RepositoryProvider>(relaxed = true)
            val ctx = ToolExecutionContext(repoProvider, perRootConfigService = perRoot, configResolver = server.resolver(perRoot))

            val result = ctx.resolveResourceRegistry(null)

            assertEquals(setOf("global-only"), result.keys)
        }
}
