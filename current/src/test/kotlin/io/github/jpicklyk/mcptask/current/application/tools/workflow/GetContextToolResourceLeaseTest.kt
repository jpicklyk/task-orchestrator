package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [GetContextTool] resource-lease disclosure (T3: lease read surfaces).
 *
 * Per the disclosure matrix mirrored from claim detail (see [GetContextToolClaimTest]):
 * - Item mode: full `resourceLeases` array (declared + held resources, holder identity, actor id)
 *   — diagnostic only, the sanctioned surface for holder identity.
 * - Health-check / session-resume modes: no resourceLeases, no lease identity anywhere.
 */
class GetContextToolResourceLeaseTest {
    private lateinit var tool: GetContextTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var leaseRepo: ResourceLeaseRepository

    /** Test-only schema service declaring a single trait's resource requirements. */
    private class FakeSchemaService(
        private val traitResources: Map<String, List<ResourceRequirement>>
    ) : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>) = null

        override fun getTraitResources(traitName: String): List<ResourceRequirement> = traitResources[traitName] ?: emptyList()
    }

    private fun contextWith(schemaService: WorkItemSchemaService = NoOpNoteSchemaService): ToolExecutionContext {
        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.dependencyRepository() } returns mockk()
        every { repoProvider.resourceLeaseRepository() } returns leaseRepo
        return ToolExecutionContext(repoProvider, schemaService)
    }

    @BeforeEach
    fun setUp() {
        tool = GetContextTool()
        workItemRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()
        leaseRepo = mockk()
        coEvery { workItemRepo.dbNow() } returns Instant.now()
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    private fun makeLease(
        resourceKey: String,
        holderItemId: UUID,
        actorId: String? = "agent-1",
        expiresAt: Instant = Instant.now().plusSeconds(300)
    ) = ResourceLease(
        resourceKey = resourceKey,
        holderItemId = holderItemId,
        acquiredByActorId = actorId,
        acquiredAt = Instant.now().minusSeconds(60),
        expiresAt = expiresAt,
        originalAcquiredAt = Instant.now().minusSeconds(60)
    )

    // ──────────────────────────────────────────────
    // Item mode — declared + held resources with holder identity
    // ──────────────────────────────────────────────

    @Test
    fun `item mode shows declared and held resource with holder identity`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item =
                WorkItem(
                    id = itemId,
                    title = "Migration task",
                    role = Role.WORK,
                    properties = """{"traits":["needs-lock"]}"""
                )
            val schemaService =
                FakeSchemaService(
                    mapOf(
                        "needs-lock" to
                            listOf(ResourceRequirement(key = "db-migration-lock", mode = ResourceMode.EXCLUSIVE, ttlSeconds = 300))
                    )
                )
            val context = contextWith(schemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            val heldLease = makeLease("db-migration-lock", holderItemId = itemId, actorId = "agent-77")
            coEvery { leaseRepo.findActiveForItem(itemId) } returns listOf(heldLease)
            coEvery { leaseRepo.findActiveByKeys(listOf("db-migration-lock")) } returns listOf(heldLease)

            val data = extractData(tool.execute(JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString()))), context))

            val leases = data["resourceLeases"]?.jsonArray
            assertNotNull(leases, "resourceLeases must be present for an item that declares a resource")
            assertEquals(1, leases.size)
            val entry = leases[0].jsonObject
            assertEquals("db-migration-lock", entry["key"]?.jsonPrimitive?.content)
            assertEquals("exclusive", entry["mode"]?.jsonPrimitive?.content)
            assertTrue(entry["held"]!!.jsonPrimitive.boolean, "held must be true — this item holds the lease")
            assertEquals(itemId.toString(), entry["holderItemId"]?.jsonPrimitive?.content)
            assertEquals("agent-77", entry["acquiredByActorId"]?.jsonPrimitive?.content)
            assertNotNull(entry["expiresAt"], "expiresAt must be present when a lease is active")
        }

    @Test
    fun `item mode shows declared resource not currently held with no holder`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item =
                WorkItem(
                    id = itemId,
                    title = "Migration task",
                    role = Role.WORK,
                    properties = """{"traits":["needs-lock"]}"""
                )
            val schemaService =
                FakeSchemaService(
                    mapOf(
                        "needs-lock" to
                            listOf(ResourceRequirement(key = "db-migration-lock", mode = ResourceMode.ADVISORY, ttlSeconds = 60))
                    )
                )
            val context = contextWith(schemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { leaseRepo.findActiveForItem(itemId) } returns emptyList()
            coEvery { leaseRepo.findActiveByKeys(listOf("db-migration-lock")) } returns emptyList()

            val data = extractData(tool.execute(JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString()))), context))

            val leases = data["resourceLeases"]?.jsonArray
            assertNotNull(leases)
            assertEquals(1, leases.size)
            val entry = leases[0].jsonObject
            assertEquals("db-migration-lock", entry["key"]?.jsonPrimitive?.content)
            assertEquals("advisory", entry["mode"]?.jsonPrimitive?.content)
            assertFalse(entry["held"]!!.jsonPrimitive.boolean, "held must be false — no active lease exists")
            assertNull(entry["holderItemId"], "holderItemId must be absent when nobody holds the resource")
            assertNull(entry["acquiredByActorId"])
            assertNull(entry["expiresAt"])
        }

    @Test
    fun `item mode omits resourceLeases when item declares nothing and holds nothing`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, title = "Plain task", role = Role.WORK)
            val context = contextWith(NoOpNoteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { leaseRepo.findActiveForItem(itemId) } returns emptyList()

            val data = extractData(tool.execute(JsonObject(mapOf("itemId" to JsonPrimitive(itemId.toString()))), context))

            assertNull(data["resourceLeases"], "resourceLeases must be absent when the item declares nothing and holds nothing")
        }

    // ──────────────────────────────────────────────
    // Health-check / session-resume modes — no lease identity disclosure
    // ──────────────────────────────────────────────

    @Test
    fun `health-check mode contains no resourceLeases or lease identity`(): Unit =
        runBlocking {
            val context = contextWith(NoOpNoteSchemaService)
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.BLOCKED, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.countByClaimStatus(null) } returns
                Result.Success(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0))

            val result = tool.execute(JsonObject(emptyMap()), context)
            val serialized = result.toString()

            assertFalse("\"resourceLeases\"" in serialized, "Health-check mode must NEVER expose \"resourceLeases\"")
            assertFalse("\"acquiredByActorId\"" in serialized, "Health-check mode must NEVER expose \"acquiredByActorId\"")
            assertFalse("\"holderItemId\"" in serialized, "Health-check mode must NEVER expose \"holderItemId\"")
        }

    @Test
    fun `session-resume mode contains no resourceLeases or lease identity`(): Unit =
        runBlocking {
            val context = contextWith(NoOpNoteSchemaService)
            val since = Instant.now().minusSeconds(3600)
            coEvery { workItemRepo.findByRole(Role.WORK, limit = any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.findByRole(Role.REVIEW, limit = any()) } returns Result.Success(emptyList())
            coEvery { roleTransitionRepo.findSince(any(), limit = any()) } returns Result.Success(emptyList())

            val result = tool.execute(JsonObject(mapOf("since" to JsonPrimitive(since.toString()))), context)
            val serialized = result.toString()

            assertFalse("\"resourceLeases\"" in serialized, "Session-resume mode must NEVER expose \"resourceLeases\"")
            assertFalse("\"acquiredByActorId\"" in serialized, "Session-resume mode must NEVER expose \"acquiredByActorId\"")
            assertFalse("\"holderItemId\"" in serialized, "Session-resume mode must NEVER expose \"holderItemId\"")
        }
}
