package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.WorkItemSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ResourceLease
import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import io.github.jpicklyk.mcptask.current.domain.model.ResourceRequirement
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseReleaseResult
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ResourceLeaseRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end MCP response shape for a resource-lease-blocked `advance_item` call.
 *
 * The disclosure assertions here are the point of the file: an agent that can trigger an advance
 * must be able to learn WHICH resource keys are contended and WHEN to retry, and nothing else. The
 * holding item's id and the holding actor's id are seeded with distinctive values and asserted
 * ABSENT from the fully serialized response — enumerating holders is an ADMIN-only capability
 * exposed through `GET /api/v1/resources/leases`.
 */
class AdvanceItemToolLeaseBlockedTest {
    /** Distinctive seeds so their absence in the serialized response is unambiguous. */
    private val holderItemId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    private val holderActorId = "holder-actor-zzz999"
    private val resourceKey = "staging-db-credential"

    private lateinit var tool: AdvanceItemTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var leaseRepo: ResourceLeaseRepository

    /** Schema service that maps the `needs-staging-db` trait onto one exclusive resource. */
    private class ResourceTraitSchemaService : WorkItemSchemaService {
        override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

        override fun getTraitResources(traitName: String): List<ResourceRequirement> =
            if (traitName == "needs-staging-db") {
                listOf(ResourceRequirement("staging-db-credential", ResourceMode.EXCLUSIVE, 600))
            } else {
                emptyList()
            }
    }

    @BeforeEach
    fun setUp() {
        tool = AdvanceItemTool()
        workItemRepo = mockk()
        leaseRepo = mockk()
        val depRepo = mockk<DependencyRepository>()
        val roleTransitionRepo = mockk<RoleTransitionRepository>()
        val noteRepo = mockk<NoteRepository>()

        repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.resourceLeaseRepository() } returns leaseRepo

        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        coEvery { leaseRepo.releaseAllForItem(any()) } returns LeaseReleaseResult.Success(0)
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()

        context = ToolExecutionContext(repoProvider, ResourceTraitSchemaService())
    }

    private fun tracedItem(id: UUID): WorkItem =
        WorkItem(
            id = id,
            title = "Needs staging DB",
            role = Role.QUEUE,
            depth = 0,
            properties = """{"traits":["needs-staging-db"]}"""
        )

    private fun startParams(itemId: UUID): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemId", itemId.toString())
                            put("trigger", "start")
                        }
                    )
                }
            )
        }

    @Test
    fun `a lease-blocked start returns a transient resource_unavailable result with keys and backoff`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(tracedItem(itemId))
            coEvery { leaseRepo.acquireAll(itemId, any(), any()) } returns
                LeaseAcquireResult.Contended(listOf(resourceKey), retryAfterMs = 30_000)

            val result = tool.execute(startParams(itemId), context)
            val transition =
                (result as JsonObject)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject

            assertFalse(transition["applied"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("resource_unavailable", transition["errorCode"]!!.jsonPrimitive.content)
            assertEquals("transient", transition["errorKind"]!!.jsonPrimitive.content)
            assertEquals(30_000L, transition["retryAfterMs"]!!.jsonPrimitive.content.toLong())
            assertEquals(
                listOf(resourceKey),
                transition["contendedResources"]!!.jsonArray.map { it.jsonPrimitive.content }
            )
            // The advance was rejected before apply — nothing persisted.
            coVerify(exactly = 0) { workItemRepo.update(any()) }
        }

    @Test
    fun `a lease-blocked response discloses NO holder item id and NO actor id`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(tracedItem(itemId))
            // The seeded holder identity is what a leaky implementation would echo back.
            coEvery { leaseRepo.acquireAll(itemId, any(), any()) } returns
                LeaseAcquireResult.Contended(listOf(resourceKey), retryAfterMs = 30_000)
            // The holder IS discoverable from the lease store — the assertion below is that the
            // advance response never reaches for it.
            val now = Instant.now()
            coEvery { leaseRepo.findActiveByKeys(any()) } returns
                listOf(
                    ResourceLease(
                        resourceKey = resourceKey,
                        holderItemId = holderItemId,
                        acquiredByActorId = holderActorId,
                        acquiredAt = now,
                        expiresAt = now.plusSeconds(600),
                        originalAcquiredAt = now
                    )
                )

            val result = tool.execute(startParams(itemId), context)
            val serialized = Json.encodeToString(JsonObject.serializer(), result as JsonObject)

            assertTrue(
                serialized.contains(resourceKey),
                "the contended resource KEY must be disclosed so the agent can reason about backoff"
            )
            assertFalse(
                serialized.contains(holderItemId.toString()),
                "holder item id must never appear in an advance rejection: $serialized"
            )
            assertFalse(
                serialized.contains(holderActorId),
                "holder actor id must never appear in an advance rejection: $serialized"
            )
            assertFalse(
                serialized.contains("contendedItemId"),
                "contendedItemId is a claim-race field and must stay absent here: $serialized"
            )
        }

    @Test
    fun `a DB error from the lease store is reported as transient with the default backoff`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(tracedItem(itemId))
            coEvery { leaseRepo.acquireAll(itemId, any(), any()) } returns
                LeaseAcquireResult.DBError(IllegalStateException("write conflict"))

            val result = tool.execute(startParams(itemId), context)
            val transition =
                (result as JsonObject)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject

            assertEquals("resource_unavailable", transition["errorCode"]!!.jsonPrimitive.content)
            assertEquals("transient", transition["errorKind"]!!.jsonPrimitive.content)
            assertEquals(1000L, transition["retryAfterMs"]!!.jsonPrimitive.content.toLong())
        }

    @Test
    fun `an uncontended start acquires the trait-declared lease and succeeds`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(tracedItem(itemId))
            coEvery { leaseRepo.acquireAll(itemId, any(), any()) } returns
                LeaseAcquireResult.Success(emptyList())

            val result = tool.execute(startParams(itemId), context)
            val transition =
                (result as JsonObject)["data"]!!
                    .jsonObject["results"]!!
                    .jsonArray[0]
                    .jsonObject

            assertTrue(transition["applied"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("work", transition["newRole"]!!.jsonPrimitive.content)
            // TTL comes from the trait's own ttlSeconds override (600), not the 3600 default.
            coVerify(exactly = 1) { leaseRepo.acquireAll(itemId, any(), listOf(resourceKey to 600)) }
        }
}
