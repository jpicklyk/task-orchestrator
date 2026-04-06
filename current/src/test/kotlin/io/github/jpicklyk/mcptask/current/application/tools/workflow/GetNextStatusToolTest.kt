package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

/** Convenience builder: a [WorkItemSchema] with a single REVIEW-phase note (so hasReviewPhase() == true). */
private fun schemaWithReview(type: String = "default"): WorkItemSchema =
    WorkItemSchema(
        type = type,
        notes = listOf(NoteSchemaEntry(key = "review-note", role = Role.REVIEW, required = false))
    )

/** Convenience builder: a [WorkItemSchema] with no REVIEW-phase notes (so hasReviewPhase() == false). */
private fun schemaWithoutReview(type: String = "default"): WorkItemSchema =
    WorkItemSchema(
        type = type,
        notes = listOf(NoteSchemaEntry(key = "work-note", role = Role.WORK, required = false))
    )

class GetNextStatusToolTest {
    private lateinit var tool: GetNextStatusTool
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var noteSchemaService: NoteSchemaService

    @BeforeEach
    fun setUp() {
        tool = GetNextStatusTool()
        workItemRepo = mockk()
        depRepo = mockk()
        noteSchemaService = mockk()

        // Default: no type-based schema; tag-based lookup returns a schema with a review phase
        // so that existing tests (which don't set up tags) still expect WORK→REVIEW progression.
        every { noteSchemaService.getSchemaForType(any()) } returns null
        every { noteSchemaService.getDefaultTraits(any()) } returns emptyList()
        every { noteSchemaService.getTraitNotes(any()) } returns null
        every { noteSchemaService.getSchemaForTags(any()) } returns schemaWithReview().notes

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns mockk()
        every { repoProvider.roleTransitionRepository() } returns mockk()

        context = ToolExecutionContext(repoProvider, noteSchemaService)
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Test Item",
        type: String? = null
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            type = type
        )

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean)
        return obj["data"] as JsonObject
    }

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    // ──────────────────────────────────────────────
    // Ready recommendations
    // ──────────────────────────────────────────────

    @Test
    fun `QUEUE item with no deps returns Ready with nextRole WORK`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("queue", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("work", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
        }

    @Test
    fun `WORK item with no deps returns Ready with nextRole REVIEW`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("work", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("review", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
        }

    @Test
    fun `REVIEW item with no deps returns Ready with nextRole TERMINAL`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.REVIEW)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("review", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Terminal recommendation
    // ──────────────────────────────────────────────

    @Test
    fun `TERMINAL item returns Terminal recommendation`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.TERMINAL)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Terminal", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("terminal", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals(
                "Item is terminal. Use 'reopen' trigger to move back to queue, or 'cancel' if already cancelled.",
                data["reason"]!!.jsonPrimitive.content
            )
        }

    // ──────────────────────────────────────────────
    // Blocked recommendations
    // ──────────────────────────────────────────────

    @Test
    fun `BLOCKED item returns Blocked with resume suggestion`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.BLOCKED, previousRole = Role.WORK)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("blocked", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("Use 'resume' trigger to return to previous role", data["suggestion"]!!.jsonPrimitive.content)
        }

    @Test
    fun `QUEUE item with unsatisfied dependency returns Blocked with blockers`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)
            val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

            // Blocker BLOCKS item, default unblockAt = terminal
            val dep =
                Dependency(
                    fromItemId = blockerId,
                    toItemId = itemId,
                    type = DependencyType.BLOCKS
                )
            every { depRepo.findByToItemId(itemId) } returns listOf(dep)
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("queue", data["currentRole"]!!.jsonPrimitive.content)

            val blockers = data["blockers"]!!.jsonArray
            assertEquals(1, blockers.size)
            val blocker = blockers[0].jsonObject
            assertEquals(blockerId.toString(), blocker["fromItemId"]!!.jsonPrimitive.content)
            assertEquals("queue", blocker["currentRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", blocker["requiredRole"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Error cases
    // ──────────────────────────────────────────────

    @Test
    fun `item not found returns error response`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()

            coEvery { workItemRepo.getById(itemId) } returns
                Result.Error(
                    RepositoryError.NotFound(itemId, "WorkItem not found: $itemId")
                )

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val obj = result as JsonObject
            assertFalse(obj["success"]!!.jsonPrimitive.boolean)
            val error = obj["error"] as JsonObject
            assertTrue(error["message"]!!.jsonPrimitive.content.contains("not found"))
        }

    @Test
    fun `missing itemId parameter fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params())
        }
    }

    @Test
    fun `invalid UUID format fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("itemId" to JsonPrimitive("not-a-uuid")))
        }
    }

    // ──────────────────────────────────────────────
    // progressionPosition calculation
    // ──────────────────────────────────────────────

    @Test
    fun `progressionPosition is 1 of 4 for QUEUE`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("1/4", data["progressionPosition"]!!.jsonPrimitive.content)
        }

    @Test
    fun `progressionPosition is 2 of 4 for WORK`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("2/4", data["progressionPosition"]!!.jsonPrimitive.content)
        }

    @Test
    fun `progressionPosition is 3 of 4 for REVIEW`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.REVIEW)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("3/4", data["progressionPosition"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // userSummary
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary shows recommendation and role`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val input = params("itemId" to JsonPrimitive(itemId.toString()))
            val result = tool.execute(input, context)
            val summary = tool.userSummary(input, result, isError = false)

            assertEquals("Recommendation: Ready (current: queue)", summary)
        }

    @Test
    fun `userSummary on error returns failure message`() {
        val errorResult =
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", buildJsonObject { put("message", JsonPrimitive("fail")) })
            }
        val summary = tool.userSummary(params(), errorResult, isError = true)
        assertEquals("get_next_status failed", summary)
    }

    // ──────────────────────────────────────────────
    // hasReviewPhase behaviour
    // ──────────────────────────────────────────────

    @Test
    fun `WORK role with no schema tags skips REVIEW and returns nextRole terminal`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            // Item has no tags — schema returns no review phase
            val item = makeItem(id = itemId, role = Role.WORK)

            // Override default: return a schema without a review phase for empty tag list
            every { noteSchemaService.getSchemaForTags(emptyList()) } returns schemaWithoutReview().notes

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("work", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
            // Effective total is 3 (QUEUE/WORK/TERMINAL — no REVIEW step)
            assertEquals("2/3", data["progressionPosition"]!!.jsonPrimitive.content)
        }

    @Test
    fun `WORK role with schema tags that include review phase returns nextRole review`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            // Item has a tag that matches a schema with a review note
            val item = makeItem(id = itemId, role = Role.WORK).copy(tags = "feature")

            // Override: tag lookup for "feature" returns a schema with a review phase
            every { noteSchemaService.getSchemaForTags(listOf("feature")) } returns schemaWithReview("feature").notes

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("work", data["currentRole"]!!.jsonPrimitive.content)
            assertEquals("review", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
            // Effective total is 4 (QUEUE/WORK/REVIEW/TERMINAL)
            assertEquals("2/4", data["progressionPosition"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // IS_BLOCKED_BY direction tests
    // ──────────────────────────────────────────────

    @Test
    fun `QUEUE item with unsatisfied IS_BLOCKED_BY dep returns Blocked with blockers`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)
            val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

            // No incoming BLOCKS deps
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            // IS_BLOCKED_BY: item -> blocker
            val dep =
                Dependency(
                    fromItemId = itemId,
                    toItemId = blockerId,
                    type = DependencyType.IS_BLOCKED_BY
                )
            every { depRepo.findByFromItemId(itemId) } returns listOf(dep)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("queue", data["currentRole"]!!.jsonPrimitive.content)

            val blockers = data["blockers"]!!.jsonArray
            assertEquals(1, blockers.size)
            val blocker = blockers[0].jsonObject
            assertEquals(blockerId.toString(), blocker["fromItemId"]!!.jsonPrimitive.content)
            assertEquals("queue", blocker["currentRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", blocker["requiredRole"]!!.jsonPrimitive.content)
        }

    @Test
    fun `QUEUE item with satisfied IS_BLOCKED_BY dep returns Ready`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)
            val blockerItem = makeItem(id = blockerId, role = Role.TERMINAL, title = "Satisfied Blocker")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

            every { depRepo.findByToItemId(itemId) } returns emptyList()
            val dep =
                Dependency(
                    fromItemId = itemId,
                    toItemId = blockerId,
                    type = DependencyType.IS_BLOCKED_BY
                )
            every { depRepo.findByFromItemId(itemId) } returns listOf(dep)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
            assertEquals("work", data["nextRole"]!!.jsonPrimitive.content)
        }

    @Test
    fun `mixed BLOCKS and IS_BLOCKED_BY both unsatisfied returns Blocked with 2 blockers`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val blockerAId = UUID.randomUUID()
            val blockerBId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)
            val blockerA = makeItem(id = blockerAId, role = Role.QUEUE, title = "Blocker A")
            val blockerB = makeItem(id = blockerBId, role = Role.WORK, title = "Blocker B")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.getById(blockerAId) } returns Result.Success(blockerA)
            coEvery { workItemRepo.getById(blockerBId) } returns Result.Success(blockerB)

            // blockerA BLOCKS item (incoming)
            val blocksDep =
                Dependency(
                    fromItemId = blockerAId,
                    toItemId = itemId,
                    type = DependencyType.BLOCKS
                )
            every { depRepo.findByToItemId(itemId) } returns listOf(blocksDep)

            // item IS_BLOCKED_BY blockerB (outgoing)
            val isBlockedByDep =
                Dependency(
                    fromItemId = itemId,
                    toItemId = blockerBId,
                    type = DependencyType.IS_BLOCKED_BY
                )
            every { depRepo.findByFromItemId(itemId) } returns listOf(isBlockedByDep)

            val result =
                tool.execute(
                    params("itemId" to JsonPrimitive(itemId.toString())),
                    context
                )

            val data = extractData(result)
            assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)

            val blockers = data["blockers"]!!.jsonArray
            assertEquals(2, blockers.size, "Both BLOCKS and IS_BLOCKED_BY blockers should appear")
        }

    // ──────────────────────────────────────────────
    // Type-first schema resolution
    // ──────────────────────────────────────────────

    @Test
    fun `type-based schema with review phase recommends review after work`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK, type = "typed-task")

            val typeSchema =
                WorkItemSchema(
                    type = "typed-task",
                    notes =
                        listOf(
                            NoteSchemaEntry(key = "review-note", role = Role.REVIEW, required = false, description = "Review")
                        )
                )
            every { noteSchemaService.getSchemaForType("typed-task") } returns typeSchema

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(params("itemId" to JsonPrimitive(itemId.toString())), context)
            val data = extractData(result)

            assertEquals("review", data["nextRole"]!!.jsonPrimitive.content)
            assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
        }

    @Test
    fun `type-based schema without review phase recommends terminal after work`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK, type = "simple-type")

            val typeSchema =
                WorkItemSchema(
                    type = "simple-type",
                    notes =
                        listOf(
                            NoteSchemaEntry(key = "work-note", role = Role.WORK, required = false, description = "Work")
                        )
                )
            every { noteSchemaService.getSchemaForType("simple-type") } returns typeSchema

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(params("itemId" to JsonPrimitive(itemId.toString())), context)
            val data = extractData(result)

            assertEquals("terminal", data["nextRole"]!!.jsonPrimitive.content)
        }
}
