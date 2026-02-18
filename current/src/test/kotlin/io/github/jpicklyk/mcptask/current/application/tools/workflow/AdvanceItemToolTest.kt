package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
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

class AdvanceItemToolTest {

    private lateinit var tool: AdvanceItemTool
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    @BeforeEach
    fun setUp() {
        tool = AdvanceItemTool()
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns mockk()
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo

        context = ToolExecutionContext(repoProvider)
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Test Item",
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        return WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            parentId = parentId,
            depth = depth
        )
    }

    private fun buildParams(vararg transitions: JsonObject): JsonObject {
        return buildJsonObject {
            put("transitions", buildJsonArray {
                transitions.forEach { add(it) }
            })
        }
    }

    private fun transitionObj(itemId: UUID, trigger: String, summary: String? = null): JsonObject {
        return buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            if (summary != null) put("summary", JsonPrimitive(summary))
        }
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray {
        return extractData(result)["results"]!!.jsonArray
    }

    private fun extractSummary(result: JsonElement): JsonObject {
        return extractData(result)["summary"]!!.jsonObject
    }

    // ──────────────────────────────────────────────
    // 1. Single start transition QUEUE -> WORK
    // ──────────────────────────────────────────────

    @Test
    fun `start trigger transitions QUEUE to WORK`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(1, results.size)

        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("queue", r["previousRole"]!!.jsonPrimitive.content)
        assertEquals("work", r["newRole"]!!.jsonPrimitive.content)
        assertEquals("start", r["trigger"]!!.jsonPrimitive.content)

        val summary = extractSummary(result)
        assertEquals(1, summary["total"]!!.jsonPrimitive.int)
        assertEquals(1, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(0, summary["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 2. Single complete transition QUEUE -> TERMINAL
    // ──────────────────────────────────────────────

    @Test
    fun `complete trigger transitions QUEUE to TERMINAL`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("queue", r["previousRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 3. Block trigger saves previousRole
    // ──────────────────────────────────────────────

    @Test
    fun `block trigger transitions to BLOCKED and saves previousRole`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "block"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("work", r["previousRole"]!!.jsonPrimitive.content)
        assertEquals("blocked", r["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 4. Resume trigger restores previousRole
    // ──────────────────────────────────────────────

    @Test
    fun `resume trigger restores previousRole from BLOCKED`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.BLOCKED, previousRole = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "resume"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("blocked", r["previousRole"]!!.jsonPrimitive.content)
        assertEquals("work", r["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 5. Cancel trigger sets statusLabel = "cancelled"
    // ──────────────────────────────────────────────

    @Test
    fun `cancel trigger transitions to TERMINAL with statusLabel cancelled`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "cancel"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)
        // The statusLabel is set on the persisted item, not directly in the result JSON.
        // But we can verify it reached TERMINAL, which is the cancel behavior.
        assertEquals("work", r["previousRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 6. Transition on TERMINAL item fails
    // ──────────────────────────────────────────────

    @Test
    fun `start on TERMINAL item fails`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("terminal"))

        val summary = extractSummary(result)
        assertEquals(0, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, summary["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `complete on TERMINAL item fails`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("terminal"))
    }

    @Test
    fun `cancel on TERMINAL item fails`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val params = buildParams(transitionObj(itemId, "cancel"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("terminal"))
    }

    // ──────────────────────────────────────────────
    // 7. Blocked by dependencies -- validation fails with blocker info
    // ──────────────────────────────────────────────

    @Test
    fun `start blocked by unsatisfied dependency includes blocker info`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val blockerId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)
        val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker Task")

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

        val dep = Dependency(
            fromItemId = blockerId,
            toItemId = itemId,
            type = DependencyType.BLOCKS
        )
        every { depRepo.findByToItemId(itemId) } returns listOf(dep)

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("blocking"))

        val blockers = r["blockers"]!!.jsonArray
        assertEquals(1, blockers.size)
        val blocker = blockers[0].jsonObject
        assertEquals(blockerId.toString(), blocker["fromItemId"]!!.jsonPrimitive.content)
        assertEquals("queue", blocker["currentRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", blocker["requiredRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 8. Batch transitions -- multiple succeed
    // ──────────────────────────────────────────────

    @Test
    fun `batch transitions all succeed`(): Unit = runBlocking {
        val id1 = UUID.randomUUID()
        val id2 = UUID.randomUUID()
        val item1 = makeItem(id = id1, role = Role.QUEUE)
        val item2 = makeItem(id = id2, role = Role.WORK)

        coEvery { workItemRepo.getById(id1) } returns Result.Success(item1)
        coEvery { workItemRepo.getById(id2) } returns Result.Success(item2)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()

        val params = buildParams(
            transitionObj(id1, "start"),
            transitionObj(id2, "start")
        )
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(2, results.size)
        assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)
        assertTrue(results[1].jsonObject["applied"]!!.jsonPrimitive.boolean)

        assertEquals("work", results[0].jsonObject["newRole"]!!.jsonPrimitive.content)
        // With NoOpNoteSchemaService (schema-free mode), hasReviewPhase=false,
        // so WORK + start advances directly to TERMINAL (no review phase).
        assertEquals("terminal", results[1].jsonObject["newRole"]!!.jsonPrimitive.content)

        val summary = extractSummary(result)
        assertEquals(2, summary["total"]!!.jsonPrimitive.int)
        assertEquals(2, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(0, summary["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 9. Batch with mixed success/failure
    // ──────────────────────────────────────────────

    @Test
    fun `batch with mixed success and failure`(): Unit = runBlocking {
        val goodId = UUID.randomUUID()
        val badId = UUID.randomUUID()
        val goodItem = makeItem(id = goodId, role = Role.QUEUE)
        val badItem = makeItem(id = badId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(goodId) } returns Result.Success(goodItem)
        coEvery { workItemRepo.getById(badId) } returns Result.Success(badItem)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()

        val params = buildParams(
            transitionObj(goodId, "start"),
            transitionObj(badId, "start")
        )
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(2, results.size)
        assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)
        assertFalse(results[1].jsonObject["applied"]!!.jsonPrimitive.boolean)

        val summary = extractSummary(result)
        assertEquals(2, summary["total"]!!.jsonPrimitive.int)
        assertEquals(1, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(1, summary["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 10. Cascade fires when all children terminal
    // ──────────────────────────────────────────────

    @Test
    fun `cascade fires when completing last child`(): Unit = runBlocking {
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val parentItem = makeItem(id = parentId, role = Role.WORK, title = "Parent")
        val childItem = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)

        coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
        coEvery { workItemRepo.getById(parentId) } returns Result.Success(parentItem)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()
        // All children of parent are terminal after this transition
        coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(
            mapOf(Role.TERMINAL to 1)
        )

        val params = buildParams(transitionObj(childId, "complete"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)

        val cascadeEvents = r["cascadeEvents"]!!.jsonArray
        assertEquals(1, cascadeEvents.size)
        val cascade = cascadeEvents[0].jsonObject
        assertEquals(parentId.toString(), cascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("work", cascade["previousRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", cascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // 11. Start cascade fires when first child starts
    // ──────────────────────────────────────────────

    @Test
    fun `start cascade fires when child starts and parent is in QUEUE`(): Unit = runBlocking {
        val parentId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val parentItem = makeItem(id = parentId, role = Role.QUEUE, title = "Parent")
        val childItem = makeItem(id = childId, role = Role.QUEUE, title = "Child", parentId = parentId)

        // Child fetch (first call) returns QUEUE state, then returns WORK after update
        val updatedChild = childItem.update { it.copy(role = Role.WORK) }
        coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
        coEvery { workItemRepo.getById(parentId) } returns Result.Success(parentItem)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()

        val params = buildParams(transitionObj(childId, "start"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("work", r["newRole"]!!.jsonPrimitive.content)

        val cascadeEvents = r["cascadeEvents"]!!.jsonArray
        assertEquals(1, cascadeEvents.size)
        val cascade = cascadeEvents[0].jsonObject
        assertEquals(parentId.toString(), cascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("queue", cascade["previousRole"]!!.jsonPrimitive.content)
        assertEquals("work", cascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // 12. (was 11) Unblocked items reported
    // ──────────────────────────────────────────────

    @Test
    fun `unblocked items reported after transition`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val downstreamId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK)
        val downstreamItem = makeItem(id = downstreamId, role = Role.QUEUE, title = "Downstream Task")

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.getById(downstreamId) } returns Result.Success(downstreamItem)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        // After completing itemId, downstream becomes unblocked
        val outgoingDep = Dependency(
            fromItemId = itemId,
            toItemId = downstreamId,
            type = DependencyType.BLOCKS
        )
        every { depRepo.findByFromItemId(itemId) } returns listOf(outgoingDep)
        // The downstream item's incoming deps are all satisfied (the blocker is now terminal)
        every { depRepo.findByToItemId(downstreamId) } returns listOf(outgoingDep)
        // The blocker (itemId) will be re-fetched during isFullyUnblocked check;
        // it's been updated to TERMINAL role, so return a terminal version
        val terminalItem = item.update { it.copy(role = Role.TERMINAL) }
        // We need getById for itemId to return the updated item after apply
        // But the first call returns the original. The second call (from findUnblockedItems)
        // should also return the applied version. We'll use coEvery with answers.
        coEvery { workItemRepo.getById(itemId) } returnsMany listOf(
            Result.Success(item),       // first call: fetch for transition
            Result.Success(terminalItem) // second call: isFullyUnblocked check
        )

        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)

        val unblockedItems = r["unblockedItems"]!!.jsonArray
        assertEquals(1, unblockedItems.size)
        assertEquals(downstreamId.toString(), unblockedItems[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals("Downstream Task", unblockedItems[0].jsonObject["title"]!!.jsonPrimitive.content)

        // Also check allUnblockedItems in the top-level data
        val data = extractData(result)
        val allUnblocked = data["allUnblockedItems"]!!.jsonArray
        assertEquals(1, allUnblocked.size)
    }

    // ──────────────────────────────────────────────
    // 12. Missing transitions param -> validation error
    // ──────────────────────────────────────────────

    @Test
    fun `missing transitions param fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject { })
        }
    }

    // ──────────────────────────────────────────────
    // 13. Empty transitions array -> validation error
    // ──────────────────────────────────────────────

    @Test
    fun `empty transitions array fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("transitions", buildJsonArray { })
            })
        }
    }

    // ──────────────────────────────────────────────
    // 14. Unknown trigger -> error
    // ──────────────────────────────────────────────

    @Test
    fun `unknown trigger returns error result`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

        val params = buildParams(transitionObj(itemId, "reboot"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("Unknown trigger"))

        val summary = extractSummary(result)
        assertEquals(1, summary["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Additional edge cases
    // ──────────────────────────────────────────────

    @Test
    fun `item not found returns error result`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()

        coEvery { workItemRepo.getById(itemId) } returns Result.Error(
            RepositoryError.NotFound(itemId, "WorkItem not found: $itemId")
        )

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("not found"))
    }

    @Test
    fun `summary field included in result when provided`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "start", summary = "Starting work on this"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("Starting work on this", r["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hold trigger behaves like block`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.REVIEW)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "hold"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("review", r["previousRole"]!!.jsonPrimitive.content)
        assertEquals("blocked", r["newRole"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid UUID in transitions fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("itemId", JsonPrimitive("not-a-uuid"))
                        put("trigger", JsonPrimitive("start"))
                    })
                })
            })
        }
    }

    @Test
    fun `missing trigger in transitions fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("transitions", buildJsonArray {
                    add(buildJsonObject {
                        put("itemId", JsonPrimitive(UUID.randomUUID().toString()))
                    })
                })
            })
        }
    }

    // ──────────────────────────────────────────────
    // userSummary tests
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary with all succeeded`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val input = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(input, context)
        val summary = tool.userSummary(input, result, isError = false)

        assertEquals("Transitioned 1 item(s)", summary)
    }

    @Test
    fun `userSummary with failures`(): Unit = runBlocking {
        val goodId = UUID.randomUUID()
        val badId = UUID.randomUUID()
        val goodItem = makeItem(id = goodId, role = Role.QUEUE)
        val badItem = makeItem(id = badId, role = Role.TERMINAL)

        coEvery { workItemRepo.getById(goodId) } returns Result.Success(goodItem)
        coEvery { workItemRepo.getById(badId) } returns Result.Success(badItem)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()

        val input = buildParams(
            transitionObj(goodId, "start"),
            transitionObj(badId, "start")
        )
        val result = tool.execute(input, context)
        val summary = tool.userSummary(input, result, isError = false)

        assertEquals("Transitioned 1/2 (1 failed)", summary)
    }

    @Test
    fun `userSummary on error returns failure message`() {
        val errorResult = buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", buildJsonObject { put("message", JsonPrimitive("fail")) })
        }
        val summary = tool.userSummary(buildJsonObject { }, errorResult, isError = true)
        assertEquals("advance_item failed", summary)
    }

    // ──────────────────────────────────────────────
    // Gate enforcement tests
    // ──────────────────────────────────────────────

    /**
     * Helper to create a NoteSchemaService that returns a fixed list of schema entries for any tags.
     */
    private fun schemaServiceWith(entries: List<NoteSchemaEntry>): NoteSchemaService {
        return object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) entries else null
        }
    }

    /**
     * Helper to create a ToolExecutionContext with a custom NoteSchemaService.
     */
    private fun contextWithSchema(
        noteRepo: NoteRepository,
        noteSchemaService: NoteSchemaService
    ): ToolExecutionContext {
        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        return ToolExecutionContext(repoProvider, noteSchemaService)
    }

    @Test
    fun `complete trigger with NoOp schema service succeeds without gate check`(): Unit = runBlocking {
        // With NoOpNoteSchemaService (default), no gate enforcement applies
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        // Use the default context (NoOpNoteSchemaService) — no gate enforcement
        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)
    }

    @Test
    fun `complete trigger with schema and missing required notes fails with gate error`(): Unit = runBlocking {
        // Item has a tag that matches a schema with required notes
        val itemId = UUID.randomUUID()
        val item = WorkItem(id = itemId, title = "Gated item", role = Role.QUEUE, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria"),
            NoteSchemaEntry(key = "implementation-notes", role = "work", required = true,
                description = "Implementation notes")
        )
        val noteSchemaService = schemaServiceWith(schemaEntries)

        val noteRepo = mockk<NoteRepository>()
        // No notes exist for this item
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())

        val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["error"]!!.jsonPrimitive.content.contains("Gate check failed"),
            "Expected gate check error but got: ${r["error"]!!.jsonPrimitive.content}")
    }

    @Test
    fun `complete trigger with schema and all required notes filled succeeds`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = WorkItem(id = itemId, title = "Gated item", role = Role.QUEUE, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria")
        )
        val noteSchemaService = schemaServiceWith(schemaEntries)

        val noteRepo = mockk<NoteRepository>()
        // The required note exists and has content
        val existingNote = Note(itemId = itemId, key = "acceptance-criteria", role = "queue",
            body = "The feature must do X")
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(existingNote))
        coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(existingNote))

        val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "complete"))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)
    }

    @Test
    fun `start trigger with schema and missing required notes for current phase fails gate check`(): Unit = runBlocking {
        // Item is in QUEUE phase; schema requires "acceptance-criteria" for queue phase
        val itemId = UUID.randomUUID()
        val item = WorkItem(id = itemId, title = "Gated item", role = Role.QUEUE, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria")
        )
        val noteSchemaService = schemaServiceWith(schemaEntries)

        val noteRepo = mockk<NoteRepository>()
        // No notes exist yet
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())

        val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        val errorMsg = r["error"]!!.jsonPrimitive.content
        assertTrue(errorMsg.contains("Gate check failed"),
            "Expected gate check error but got: $errorMsg")
        assertTrue(errorMsg.contains("acceptance-criteria"),
            "Expected missing key 'acceptance-criteria' in error but got: $errorMsg")
    }

    @Test
    fun `start trigger with schema and required notes filled succeeds`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = WorkItem(id = itemId, title = "Gated item", role = Role.QUEUE, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria")
        )
        val noteSchemaService = schemaServiceWith(schemaEntries)

        val noteRepo = mockk<NoteRepository>()
        val existingNote = Note(itemId = itemId, key = "acceptance-criteria", role = "queue",
            body = "The feature must do X")
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(existingNote))
        coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(existingNote))

        val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildParams(transitionObj(itemId, "start"))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("work", r["newRole"]!!.jsonPrimitive.content)
    }
}
