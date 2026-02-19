package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

// ──────────────────────────────────────────────
// Note: includeAncestors tests use a real DB instead of mocks because
// findAncestorChains requires actual parent-child relationships in the DB.
// Those tests live in GetContextToolAncestorsTest.kt using a real H2 in-memory DB.
// ──────────────────────────────────────────────

class GetContextToolTest {

    private lateinit var tool: GetContextTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var noteSchemaService: NoteSchemaService

    // Default context uses NoOpNoteSchemaService (schema-free mode)
    private lateinit var context: ToolExecutionContext
    // Schema-aware context
    private lateinit var schemaContext: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = GetContextTool()
        workItemRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()
        noteSchemaService = mockk()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.dependencyRepository() } returns mockk()

        context = ToolExecutionContext(repoProvider, NoOpNoteSchemaService)
        schemaContext = ToolExecutionContext(repoProvider, noteSchemaService)
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Item",
        role: Role = Role.WORK,
        tags: String? = null,
        depth: Int = 0,
        parentId: UUID? = null
    ): WorkItem = WorkItem(
        id = id,
        parentId = parentId,
        title = title,
        role = role,
        tags = tags,
        depth = depth
    )

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    private fun isSuccess(result: JsonElement): Boolean =
        (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    // ──────────────────────────────────────────────
    // Test 1: Item mode with no schema (NoOp) → schema=[], gateStatus.canAdvance=true
    // ──────────────────────────────────────────────

    @Test
    fun `item mode with no schema (NoOp) returns empty schema and canAdvance=true`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK, tags = null)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context  // uses NoOpNoteSchemaService → no schema
        )

        val data = extractData(result)
        assertEquals("item", data["mode"]!!.jsonPrimitive.content)

        val schema = data["schema"]!!.jsonArray
        assertEquals(0, schema.size, "Expected empty schema in schema-free mode")

        val gateStatus = data["gateStatus"]!!.jsonObject
        assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "canAdvance should be true in schema-free mode")
        assertEquals("work", gateStatus["phase"]!!.jsonPrimitive.content)
        val missing = gateStatus["missing"]!!.jsonArray
        assertEquals(0, missing.size)
    }

    // ──────────────────────────────────────────────
    // Test 2: Item mode with schema and missing required note → canAdvance=false
    // ──────────────────────────────────────────────

    @Test
    fun `item mode with schema and missing required note returns canAdvance=false`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "implementation-notes", role = "work", required = true, description = "Notes on implementation")
        )
        every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns schemaEntries

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        // No notes exist → missing required note
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            schemaContext
        )

        val data = extractData(result)
        assertEquals("item", data["mode"]!!.jsonPrimitive.content)

        val schema = data["schema"]!!.jsonArray
        assertEquals(1, schema.size)
        val entry = schema[0].jsonObject
        assertEquals("implementation-notes", entry["key"]!!.jsonPrimitive.content)
        assertFalse(entry["exists"]!!.jsonPrimitive.boolean)
        assertFalse(entry["filled"]!!.jsonPrimitive.boolean)

        val gateStatus = data["gateStatus"]!!.jsonObject
        assertFalse(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "canAdvance should be false when required note is missing")
        val missing = gateStatus["missing"]!!.jsonArray
        assertEquals(1, missing.size)
        assertEquals("implementation-notes", missing[0].jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Test 3: Item mode with all notes filled → canAdvance=true
    // ──────────────────────────────────────────────

    @Test
    fun `item mode with all required notes filled returns canAdvance=true`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, role = Role.WORK, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "implementation-notes", role = "work", required = true, description = "Notes on implementation")
        )
        every { noteSchemaService.getSchemaForTags(listOf("feature-task")) } returns schemaEntries

        val existingNote = Note(
            itemId = itemId,
            key = "implementation-notes",
            role = "work",
            body = "This is the implementation approach."
        )

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(existingNote))

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            schemaContext
        )

        val data = extractData(result)
        val schema = data["schema"]!!.jsonArray
        assertEquals(1, schema.size)
        val entry = schema[0].jsonObject
        assertTrue(entry["exists"]!!.jsonPrimitive.boolean)
        assertTrue(entry["filled"]!!.jsonPrimitive.boolean)

        val gateStatus = data["gateStatus"]!!.jsonObject
        assertTrue(gateStatus["canAdvance"]!!.jsonPrimitive.boolean, "canAdvance should be true when all required notes are filled")
        val missing = gateStatus["missing"]!!.jsonArray
        assertEquals(0, missing.size)
    }

    // ──────────────────────────────────────────────
    // Test 4: Session resume mode with valid ISO timestamp
    // ──────────────────────────────────────────────

    @Test
    fun `session resume mode with valid ISO timestamp returns mode=session-resume`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val sinceStr = since.toString()

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), any()) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("since" to JsonPrimitive(sinceStr)),
            context
        )

        val data = extractData(result)
        assertEquals("session-resume", data["mode"]!!.jsonPrimitive.content)
        assertEquals(sinceStr, data["since"]!!.jsonPrimitive.content)
        assertNotNull(data["activeItems"])
        assertNotNull(data["recentTransitions"])
        assertNotNull(data["stalledItems"])
    }

    // ──────────────────────────────────────────────
    // Test 5: Health check mode (no params) → returns mode="health-check"
    // ──────────────────────────────────────────────

    @Test
    fun `health check mode with no params returns mode=health-check`(): Unit = runBlocking {
        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.BLOCKED, any()) } returns Result.Success(emptyList())

        val result = tool.execute(params(), context)

        val data = extractData(result)
        assertEquals("health-check", data["mode"]!!.jsonPrimitive.content)
        assertNotNull(data["activeItems"])
        assertNotNull(data["blockedItems"])
        assertNotNull(data["stalledItems"])
    }

    // ──────────────────────────────────────────────
    // Test 6: Invalid itemId → returns error
    // ──────────────────────────────────────────────

    @Test
    fun `invalid itemId UUID returns error response`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()

        coEvery { workItemRepo.getById(itemId) } returns Result.Error(
            RepositoryError.NotFound(itemId, "WorkItem not found: $itemId")
        )
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        assertFalse(isSuccess(result), "Expected error response for missing item")
        val errorObj = (result as JsonObject)["error"] as JsonObject
        assertTrue(errorObj["message"]!!.jsonPrimitive.content.contains("not found"))
    }

    // ──────────────────────────────────────────────
    // Test 7: Invalid `since` (not ISO 8601) → returns validation error
    // ──────────────────────────────────────────────

    @Test
    fun `invalid since timestamp format throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("since" to JsonPrimitive("not-a-timestamp")))
        }
    }

    // ──────────────────────────────────────────────
    // Additional: item mode returns correct item fields in response
    // ──────────────────────────────────────────────

    @Test
    fun `item mode returns item fields correctly`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val parentId = UUID.randomUUID()
        val item = WorkItem(
            id = itemId,
            parentId = parentId,
            title = "My Task",
            role = Role.QUEUE,
            tags = "feature-task",
            depth = 1
        )

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        val itemObj = data["item"]!!.jsonObject
        assertEquals(itemId.toString(), itemObj["id"]!!.jsonPrimitive.content)
        assertEquals("My Task", itemObj["title"]!!.jsonPrimitive.content)
        assertEquals("queue", itemObj["role"]!!.jsonPrimitive.content)
        assertEquals("feature-task", itemObj["tags"]!!.jsonPrimitive.content)
        assertEquals(1, itemObj["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Additional: session resume includes active items returned by repo
    // ──────────────────────────────────────────────

    @Test
    fun `session resume includes active items from work and review roles`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(1800)

        val workItem = makeItem(title = "Work item", role = Role.WORK)
        val reviewItem = makeItem(title = "Review item", role = Role.REVIEW)

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(listOf(workItem))
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(listOf(reviewItem))
        coEvery { roleTransitionRepo.findSince(any(), any()) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("since" to JsonPrimitive(since.toString())),
            context
        )

        val data = extractData(result)
        val activeItems = data["activeItems"]!!.jsonArray
        assertEquals(2, activeItems.size)
    }

    // ──────────────────────────────────────────────
    // includeAncestors tests (mock-based)
    // ──────────────────────────────────────────────

    @Test
    fun `item mode includeAncestors=true adds ancestors array to item`(): Unit = runBlocking {
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()

        val rootItem = makeItem(id = rootId, title = "Root Item", depth = 0)
        val childItem = makeItem(id = childId, title = "Child Item", depth = 1, parentId = rootId)

        coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
        coEvery { noteRepo.findByItemId(childId) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findAncestorChains(setOf(childId)) } returns Result.Success(
            mapOf(childId to listOf(rootItem))
        )

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(childId.toString()),
                "includeAncestors" to JsonPrimitive(true)
            ),
            context
        )

        val data = extractData(result)
        val itemObj = data["item"]!!.jsonObject
        val ancestors = itemObj["ancestors"]!!.jsonArray
        assertEquals(1, ancestors.size)
        val ancestor = ancestors[0].jsonObject
        assertEquals(rootId.toString(), ancestor["id"]!!.jsonPrimitive.content)
        assertEquals("Root Item", ancestor["title"]!!.jsonPrimitive.content)
        assertEquals(0, ancestor["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `item mode includeAncestors=false does not add ancestors array`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, depth = 0)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

        val result = tool.execute(
            params("itemId" to JsonPrimitive(itemId.toString())),
            context
        )

        val data = extractData(result)
        val itemObj = data["item"]!!.jsonObject
        assertNull(itemObj["ancestors"], "ancestors should not be present when includeAncestors=false")
    }

    @Test
    fun `item mode root item includeAncestors=true has empty ancestors array`(): Unit = runBlocking {
        val rootId = UUID.randomUUID()
        val rootItem = makeItem(id = rootId, title = "Root", depth = 0)

        coEvery { workItemRepo.getById(rootId) } returns Result.Success(rootItem)
        coEvery { noteRepo.findByItemId(rootId) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findAncestorChains(setOf(rootId)) } returns Result.Success(
            mapOf(rootId to emptyList())
        )

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(rootId.toString()),
                "includeAncestors" to JsonPrimitive(true)
            ),
            context
        )

        val data = extractData(result)
        val itemObj = data["item"]!!.jsonObject
        val ancestors = itemObj["ancestors"]!!.jsonArray
        assertEquals(0, ancestors.size, "Root item should have empty ancestors array")
    }

    @Test
    fun `health check includeAncestors=true adds ancestors to active and blocked items`(): Unit = runBlocking {
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val blockedId = UUID.randomUUID()

        val rootItem = makeItem(id = rootId, title = "Root", role = Role.WORK, depth = 0)
        val childItem = makeItem(id = childId, title = "Child Work", role = Role.WORK, depth = 1, parentId = rootId)
        val blockedItem = makeItem(id = blockedId, title = "Blocked Item", role = Role.BLOCKED, depth = 0)

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(listOf(rootItem, childItem))
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.BLOCKED, any()) } returns Result.Success(listOf(blockedItem))

        // findAncestorChains is called once with all 3 item IDs
        coEvery { workItemRepo.findAncestorChains(setOf(rootId, childId, blockedId)) } returns Result.Success(
            mapOf(
                rootId to emptyList(),
                childId to listOf(rootItem),
                blockedId to emptyList()
            )
        )

        val result = tool.execute(
            params("includeAncestors" to JsonPrimitive(true)),
            context
        )

        val data = extractData(result)
        assertEquals("health-check", data["mode"]!!.jsonPrimitive.content)

        val activeItems = data["activeItems"]!!.jsonArray
        assertEquals(2, activeItems.size)

        // Root item should have empty ancestors
        val rootJson = activeItems.find { it.jsonObject["id"]!!.jsonPrimitive.content == rootId.toString() }!!.jsonObject
        assertEquals(0, rootJson["ancestors"]!!.jsonArray.size)

        // Child item should have one ancestor (the root)
        val childJson = activeItems.find { it.jsonObject["id"]!!.jsonPrimitive.content == childId.toString() }!!.jsonObject
        val childAncestors = childJson["ancestors"]!!.jsonArray
        assertEquals(1, childAncestors.size)
        assertEquals(rootId.toString(), childAncestors[0].jsonObject["id"]!!.jsonPrimitive.content)

        // Blocked item should have empty ancestors
        val blockedJsonArr = data["blockedItems"]!!.jsonArray
        assertEquals(1, blockedJsonArr.size)
        assertEquals(0, blockedJsonArr[0].jsonObject["ancestors"]!!.jsonArray.size)
    }

    // ──────────────────────────────────────────────
    // Limit parameter tests for session-resume mode
    // ──────────────────────────────────────────────

    @Test
    fun `session resume without limit uses default of 50`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val capturedLimit = slot<Int>()

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), capture(capturedLimit)) } returns Result.Success(emptyList())

        tool.execute(
            params("since" to JsonPrimitive(since.toString())),
            context
        )

        assertEquals(50, capturedLimit.captured, "Default limit should be 50 when not specified")
    }

    @Test
    fun `session resume with limit=10 passes 10 to findSince`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val capturedLimit = slot<Int>()

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), capture(capturedLimit)) } returns Result.Success(emptyList())

        val result = tool.execute(
            params(
                "since" to JsonPrimitive(since.toString()),
                "limit" to JsonPrimitive(10)
            ),
            context
        )

        assertEquals(10, capturedLimit.captured, "Should pass limit=10 to findSince")
        val data = extractData(result)
        assertEquals("session-resume", data["mode"]!!.jsonPrimitive.content)
    }

    @Test
    fun `session resume with limit clamped to max 200`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val capturedLimit = slot<Int>()

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), capture(capturedLimit)) } returns Result.Success(emptyList())

        tool.execute(
            params(
                "since" to JsonPrimitive(since.toString()),
                "limit" to JsonPrimitive(999)
            ),
            context
        )

        assertEquals(200, capturedLimit.captured, "Limit above 200 should be clamped to 200")
    }

    @Test
    fun `session resume with limit clamped to min 1`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val capturedLimit = slot<Int>()

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), capture(capturedLimit)) } returns Result.Success(emptyList())

        tool.execute(
            params(
                "since" to JsonPrimitive(since.toString()),
                "limit" to JsonPrimitive(0)
            ),
            context
        )

        assertEquals(1, capturedLimit.captured, "Limit below 1 should be clamped to 1")
    }

    @Test
    fun `session resume includeAncestors=true adds ancestors to active items`(): Unit = runBlocking {
        val since = Instant.now().minusSeconds(3600)
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val rootItem = makeItem(id = rootId, title = "Root", role = Role.WORK, depth = 0)
        val childItem = makeItem(id = childId, title = "Child Work", role = Role.WORK, depth = 1, parentId = rootId)

        coEvery { workItemRepo.findByRole(Role.WORK, any()) } returns Result.Success(listOf(rootItem, childItem))
        coEvery { workItemRepo.findByRole(Role.REVIEW, any()) } returns Result.Success(emptyList())
        coEvery { roleTransitionRepo.findSince(any(), any()) } returns Result.Success(emptyList())
        coEvery { workItemRepo.findAncestorChains(setOf(rootId, childId)) } returns Result.Success(
            mapOf(
                rootId to emptyList(),
                childId to listOf(rootItem)
            )
        )

        val result = tool.execute(
            params(
                "since" to JsonPrimitive(since.toString()),
                "includeAncestors" to JsonPrimitive(true)
            ),
            context
        )

        val data = extractData(result)
        assertEquals("session-resume", data["mode"]!!.jsonPrimitive.content)

        val activeItems = data["activeItems"]!!.jsonArray
        assertEquals(2, activeItems.size)

        val childJson = activeItems.find { it.jsonObject["id"]!!.jsonPrimitive.content == childId.toString() }!!.jsonObject
        val childAncestors = childJson["ancestors"]!!.jsonArray
        assertEquals(1, childAncestors.size)
        assertEquals("Root", childAncestors[0].jsonObject["title"]!!.jsonPrimitive.content)
    }
}
