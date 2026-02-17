package io.github.jpicklyk.mcptask.current.application.tools.workflow

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

class GetNextStatusToolTest {

    private lateinit var tool: GetNextStatusTool
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository

    @BeforeEach
    fun setUp() {
        tool = GetNextStatusTool()
        workItemRepo = mockk()
        depRepo = mockk()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns mockk()
        every { repoProvider.roleTransitionRepository() } returns mockk()

        context = ToolExecutionContext(repoProvider)
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Test Item"
    ): WorkItem {
        return WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole
        )
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean)
        return obj["data"] as JsonObject
    }

    private fun isSuccess(result: JsonElement): Boolean {
        return (result as JsonObject)["success"]!!.jsonPrimitive.boolean
    }

    // ──────────────────────────────────────────────
    // Ready recommendations
    // ──────────────────────────────────────────────

    @Test
    fun `QUEUE item with no deps returns Ready with nextRole WORK`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
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
    fun `WORK item with no deps returns Ready with nextRole REVIEW`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
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
    fun `REVIEW item with no deps returns Ready with nextRole TERMINAL`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.REVIEW)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
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
    fun `TERMINAL item returns Terminal recommendation`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        assertEquals("Terminal", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("terminal", data["currentRole"]!!.jsonPrimitive.content)
        assertEquals("Item is already terminal and cannot progress further", data["reason"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Blocked recommendations
    // ──────────────────────────────────────────────

    @Test
    fun `BLOCKED item returns Blocked with resume suggestion`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.BLOCKED, previousRole = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("blocked", data["currentRole"]!!.jsonPrimitive.content)
        assertEquals("Use 'resume' trigger to return to previous role", data["suggestion"]!!.jsonPrimitive.content)
    }

    @Test
    fun `QUEUE item with unsatisfied dependency returns Blocked with blockers`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val blockerId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)
        val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker")

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

        // Blocker BLOCKS item, default unblockAt = terminal
        val dep = Dependency(
            fromItemId = blockerId,
            toItemId = itemId,
            type = DependencyType.BLOCKS
        )
        every { depRepo.findByToItemId(itemId) } returns listOf(dep)

        val result = tool.execute(
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
    fun `item not found returns error response`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()

        coEvery { workItemRepo.getById(itemId) } returns Result.Error(
            RepositoryError.NotFound(itemId, "WorkItem not found: $itemId")
        )

        val result = tool.execute(
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
    fun `progressionPosition is 1 of 4 for QUEUE`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        assertEquals("1/4", data["progressionPosition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `progressionPosition is 2 of 4 for WORK`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        assertEquals("2/4", data["progressionPosition"]!!.jsonPrimitive.content)
    }

    @Test
    fun `progressionPosition is 3 of 4 for REVIEW`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.REVIEW)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val result = tool.execute(
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
    fun `userSummary shows recommendation and role`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val input = params("itemId" to JsonPrimitive(itemId.toString()))
        val result = tool.execute(input, context)
        val summary = tool.userSummary(input, result, isError = false)

        assertEquals("Recommendation: Ready (current: queue)", summary)
    }

    @Test
    fun `userSummary on error returns failure message`() {
        val errorResult = buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", buildJsonObject { put("message", JsonPrimitive("fail")) })
        }
        val summary = tool.userSummary(params(), errorResult, isError = true)
        assertEquals("get_next_status failed", summary)
    }
}
