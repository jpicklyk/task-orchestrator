package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class GetNextItemToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetNextItemTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetNextItemTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /**
     * Helper to create a WorkItem directly via the repository.
     */
    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        complexity: Int = 5,
        summary: String = "",
        tags: String? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        val item = WorkItem(
            parentId = parentId,
            title = title,
            role = role,
            priority = priority,
            complexity = complexity,
            summary = summary,
            tags = tags,
            depth = depth
        )
        val result = context.workItemRepository().create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data
    }

    /**
     * Helper to create a dependency between two items.
     */
    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val dep = Dependency(
            fromItemId = fromItemId,
            toItemId = toItemId,
            type = type,
            unblockAt = unblockAt
        )
        return context.dependencyRepository().create(dep)
    }

    private fun extractRecommendations(result: JsonElement): JsonArray {
        val obj = result as JsonObject
        val data = obj["data"] as JsonObject
        return data["recommendations"]!!.jsonArray
    }

    private fun extractTotal(result: JsonElement): Int {
        val obj = result as JsonObject
        val data = obj["data"] as JsonObject
        return data["total"]!!.jsonPrimitive.int
    }

    private fun isSuccess(result: JsonElement): Boolean {
        return (result as JsonObject)["success"]!!.jsonPrimitive.boolean
    }

    // ──────────────────────────────────────────────
    // Basic recommendation tests
    // ──────────────────────────────────────────────

    @Test
    fun `no QUEUE items returns empty recommendations`(): Unit = runBlocking {
        // Create items in non-QUEUE roles
        createItem("Work Item", role = Role.WORK)
        createItem("Terminal Item", role = Role.TERMINAL)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(0, extractTotal(result))
        assertTrue(extractRecommendations(result).isEmpty())
    }

    @Test
    fun `single QUEUE item is recommended`(): Unit = runBlocking {
        val item = createItem("Only Item")

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(1, extractTotal(result))
        val recs = extractRecommendations(result)
        assertEquals(1, recs.size)
        assertEquals(item.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals("Only Item", recs[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("queue", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `multiple QUEUE items sorted by priority then complexity`(): Unit = runBlocking {
        val lowPri = createItem("Low Priority", priority = Priority.LOW, complexity = 2)
        val highPriHigh = createItem("High Priority High Complexity", priority = Priority.HIGH, complexity = 8)
        val highPriLow = createItem("High Priority Low Complexity", priority = Priority.HIGH, complexity = 2)
        val medPri = createItem("Medium Priority", priority = Priority.MEDIUM, complexity = 5)

        val result = tool.execute(
            params("limit" to JsonPrimitive(10)),
            context
        )

        assertTrue(isSuccess(result))
        assertEquals(4, extractTotal(result))
        val recs = extractRecommendations(result)

        // Order: HIGH/complexity2, HIGH/complexity8, MEDIUM/complexity5, LOW/complexity2
        assertEquals(highPriLow.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals(highPriHigh.id.toString(), recs[1].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals(medPri.id.toString(), recs[2].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals(lowPri.id.toString(), recs[3].jsonObject["itemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `items in WORK role are not recommended`(): Unit = runBlocking {
        createItem("Work Item", role = Role.WORK)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(0, extractTotal(result))
    }

    @Test
    fun `items in REVIEW role are not recommended`(): Unit = runBlocking {
        createItem("Review Item", role = Role.REVIEW)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(0, extractTotal(result))
    }

    @Test
    fun `items in TERMINAL role are not recommended`(): Unit = runBlocking {
        createItem("Done Item", role = Role.TERMINAL)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(0, extractTotal(result))
    }

    // ──────────────────────────────────────────────
    // Dependency blocking tests
    // ──────────────────────────────────────────────

    @Test
    fun `QUEUE item blocked by BLOCKS dependency is excluded`(): Unit = runBlocking {
        val blocker = createItem("Blocker", role = Role.QUEUE)
        val blocked = createItem("Blocked Item")

        // blocker BLOCKS blocked (blocker.id -> blocked.id)
        createDependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS)

        val result = tool.execute(
            params("limit" to JsonPrimitive(10)),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        // Only the blocker should be recommended (it's not blocked by anything)
        // The blocked item should be excluded because blocker is still in QUEUE (not TERMINAL)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertTrue(recIds.contains(blocker.id.toString()))
        assertFalse(recIds.contains(blocked.id.toString()))
    }

    @Test
    fun `QUEUE item with satisfied BLOCKS dependency is included`(): Unit = runBlocking {
        val blocker = createItem("Blocker at Terminal", role = Role.TERMINAL)
        val item = createItem("Ready Item")

        // blocker BLOCKS item, but blocker is TERMINAL (default threshold is terminal)
        createDependency(fromItemId = blocker.id, toItemId = item.id, type = DependencyType.BLOCKS)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertTrue(recIds.contains(item.id.toString()))
    }

    @Test
    fun `unblockAt work with blocker at WORK role is satisfied`(): Unit = runBlocking {
        val blocker = createItem("Blocker at Work", role = Role.WORK)
        val item = createItem("Waiting for Work")

        // Unblock when blocker reaches WORK
        createDependency(
            fromItemId = blocker.id,
            toItemId = item.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertTrue(recIds.contains(item.id.toString()))
    }

    @Test
    fun `unblockAt work with blocker at QUEUE role is unsatisfied`(): Unit = runBlocking {
        val blocker = createItem("Blocker at Queue", role = Role.QUEUE)
        val item = createItem("Waiting for Work Start")

        // Unblock when blocker reaches WORK, but blocker is still QUEUE
        createDependency(
            fromItemId = blocker.id,
            toItemId = item.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )

        val result = tool.execute(
            params("limit" to JsonPrimitive(10)),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        // blocker is in QUEUE so it would be recommended
        assertTrue(recIds.contains(blocker.id.toString()))
        // item should be blocked
        assertFalse(recIds.contains(item.id.toString()))
    }

    @Test
    fun `IS_BLOCKED_BY dependency correctly blocks item`(): Unit = runBlocking {
        val item = createItem("Blocked by IS_BLOCKED_BY")
        val blocker = createItem("The Blocker", role = Role.QUEUE)

        // item IS_BLOCKED_BY blocker (item.id -> blocker.id)
        createDependency(
            fromItemId = item.id,
            toItemId = blocker.id,
            type = DependencyType.IS_BLOCKED_BY
        )

        val result = tool.execute(
            params("limit" to JsonPrimitive(10)),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        // blocker (QUEUE) should be recommended
        assertTrue(recIds.contains(blocker.id.toString()))
        // item should be blocked because blocker is at QUEUE, not TERMINAL
        assertFalse(recIds.contains(item.id.toString()))
    }

    @Test
    fun `RELATES_TO dependencies are ignored for blocking`(): Unit = runBlocking {
        val relatedItem = createItem("Related Item", role = Role.QUEUE)
        val item = createItem("My Item")

        // RELATES_TO has no blocking semantics
        createDependency(
            fromItemId = relatedItem.id,
            toItemId = item.id,
            type = DependencyType.RELATES_TO
        )

        val result = tool.execute(
            params("limit" to JsonPrimitive(10)),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        // Both items should be recommended — RELATES_TO does not block
        assertTrue(recIds.contains(item.id.toString()))
        assertTrue(recIds.contains(relatedItem.id.toString()))
    }

    // ──────────────────────────────────────────────
    // Parameter tests
    // ──────────────────────────────────────────────

    @Test
    fun `parentId scoping returns only children of parent`(): Unit = runBlocking {
        val parent = createItem("Parent")
        val child1 = createItem("Child 1", parentId = parent.id, depth = 1)
        val child2 = createItem("Child 2", parentId = parent.id, depth = 1)
        val unrelated = createItem("Unrelated Root")

        val result = tool.execute(
            params(
                "parentId" to JsonPrimitive(parent.id.toString()),
                "limit" to JsonPrimitive(10)
            ),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()

        assertTrue(recIds.contains(child1.id.toString()))
        assertTrue(recIds.contains(child2.id.toString()))
        assertFalse(recIds.contains(parent.id.toString()))
        assertFalse(recIds.contains(unrelated.id.toString()))
    }

    @Test
    fun `limit parameter restricts number of recommendations`(): Unit = runBlocking {
        createItem("Item 1", priority = Priority.HIGH)
        createItem("Item 2", priority = Priority.HIGH)
        createItem("Item 3", priority = Priority.MEDIUM)
        createItem("Item 4", priority = Priority.LOW)

        val result = tool.execute(
            params("limit" to JsonPrimitive(2)),
            context
        )

        assertTrue(isSuccess(result))
        assertEquals(2, extractTotal(result))
        assertEquals(2, extractRecommendations(result).size)
    }

    @Test
    fun `default limit is 1`(): Unit = runBlocking {
        createItem("Item A", priority = Priority.HIGH)
        createItem("Item B", priority = Priority.HIGH)
        createItem("Item C", priority = Priority.MEDIUM)

        val result = tool.execute(params(), context)

        assertTrue(isSuccess(result))
        assertEquals(1, extractTotal(result))
        assertEquals(1, extractRecommendations(result).size)
    }

    // ──────────────────────────────────────────────
    // includeDetails tests
    // ──────────────────────────────────────────────

    @Test
    fun `includeDetails true includes summary tags and parentId`(): Unit = runBlocking {
        val parent = createItem("Parent")
        createItem(
            "Detailed Child",
            parentId = parent.id,
            summary = "A helpful summary",
            tags = "backend,api",
            depth = 1
        )

        val result = tool.execute(
            params(
                "parentId" to JsonPrimitive(parent.id.toString()),
                "includeDetails" to JsonPrimitive(true)
            ),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        assertEquals(1, recs.size)
        val rec = recs[0].jsonObject

        assertEquals("A helpful summary", rec["summary"]!!.jsonPrimitive.content)
        assertEquals("backend,api", rec["tags"]!!.jsonPrimitive.content)
        assertEquals(parent.id.toString(), rec["parentId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `includeDetails false omits summary tags and parentId`(): Unit = runBlocking {
        val parent = createItem("Parent")
        createItem(
            "Minimal Child",
            parentId = parent.id,
            summary = "A summary",
            tags = "test",
            depth = 1
        )

        val result = tool.execute(
            params(
                "parentId" to JsonPrimitive(parent.id.toString()),
                "includeDetails" to JsonPrimitive(false)
            ),
            context
        )

        assertTrue(isSuccess(result))
        val recs = extractRecommendations(result)
        assertEquals(1, recs.size)
        val rec = recs[0].jsonObject

        // These should be present
        assertNotNull(rec["itemId"])
        assertNotNull(rec["title"])
        assertNotNull(rec["role"])
        assertNotNull(rec["priority"])
        assertNotNull(rec["complexity"])

        // These should NOT be present when includeDetails=false
        assertNull(rec["summary"])
        assertNull(rec["tags"])
        assertNull(rec["parentId"])
    }

    // ──────────────────────────────────────────────
    // Validation tests
    // ──────────────────────────────────────────────

    @Test
    fun `limit below 1 fails validation`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("limit" to JsonPrimitive(0)))
        }
    }

    @Test
    fun `limit above 20 fails validation`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("limit" to JsonPrimitive(21)))
        }
    }

    @Test
    fun `invalid parentId UUID fails validation`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("parentId" to JsonPrimitive("not-a-uuid")))
        }
    }

    // ──────────────────────────────────────────────
    // User summary tests
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary with single recommendation shows title`(): Unit = runBlocking {
        createItem("Important Task", priority = Priority.HIGH)

        val result = tool.execute(params(), context)
        val summary = tool.userSummary(params(), result, isError = false)

        assertEquals("Next: Important Task", summary)
    }

    @Test
    fun `userSummary with error returns error message`(): Unit {
        val errorResult = buildJsonObject {
            put("success", JsonPrimitive(false))
            put("error", buildJsonObject { put("message", JsonPrimitive("fail")) })
        }
        val summary = tool.userSummary(params(), errorResult, isError = true)
        assertEquals("No recommendations available", summary)
    }
}
