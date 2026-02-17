package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.application.tools.items.ManageItemsTool
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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

class GetBlockedItemsToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetBlockedItemsTool
    private lateinit var manageTool: ManageItemsTool
    private lateinit var depTool: ManageDependenciesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetBlockedItemsTool()
        manageTool = ManageItemsTool()
        depTool = ManageDependenciesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /**
     * Helper to create a WorkItem via ManageItemsTool and return its ID.
     */
    private suspend fun createItem(
        title: String,
        parentId: String? = null,
        role: String? = null,
        priority: String? = null,
        tags: String? = null,
        summary: String? = null
    ): String {
        val itemObj = buildJsonObject {
            put("title", JsonPrimitive(title))
            parentId?.let { put("parentId", JsonPrimitive(it)) }
            role?.let { put("role", JsonPrimitive(it)) }
            priority?.let { put("priority", JsonPrimitive(it)) }
            tags?.let { put("tags", JsonPrimitive(it)) }
            summary?.let { put("summary", JsonPrimitive(it)) }
        }
        val result = manageTool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(itemObj))
            ),
            context
        ) as JsonObject
        return (result["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
    }

    /**
     * Helper to create a BLOCKS dependency between two items.
     */
    private fun createDep(
        fromItemId: String,
        toItemId: String,
        type: String = "BLOCKS",
        unblockAt: String? = null
    ) {
        val depObj = buildJsonObject {
            put("fromItemId", JsonPrimitive(fromItemId))
            put("toItemId", JsonPrimitive(toItemId))
            put("type", JsonPrimitive(type))
            unblockAt?.let { put("unblockAt", JsonPrimitive(it)) }
        }
        runBlocking {
            depTool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "dependencies" to JsonArray(listOf(depObj))
                ),
                context
            )
        }
    }

    /**
     * Helper to create a BLOCKED item directly via the repository.
     */
    private suspend fun createBlockedItem(
        title: String,
        parentId: UUID? = null,
        tags: String? = null,
        summary: String = ""
    ): UUID {
        val item = WorkItem(
            title = title,
            role = Role.BLOCKED,
            previousRole = Role.WORK,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0,
            tags = tags,
            summary = summary
        )
        val result = context.workItemRepository().create(item)
        assertTrue(result is io.github.jpicklyk.mcptask.current.domain.repository.Result.Success)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data.id
    }

    /**
     * Extract the blockedItems array from a tool result.
     */
    private fun extractBlockedItems(result: JsonObject): JsonArray {
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        return data["blockedItems"]!!.jsonArray
    }

    private fun extractTotal(result: JsonObject): Int {
        val data = result["data"] as JsonObject
        return data["total"]!!.jsonPrimitive.int
    }

    // ──────────────────────────────────────────────
    // Test: No blocked items
    // ──────────────────────────────────────────────

    @Test
    fun `no blocked items returns empty result`(): Unit = runBlocking {
        // Create some items with no blocking dependencies
        createItem("Item A")
        createItem("Item B")

        val result = tool.execute(params(), context) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, extractTotal(result))
        assertEquals(0, extractBlockedItems(result).size)
    }

    // ──────────────────────────────────────────────
    // Test: Explicitly BLOCKED items
    // ──────────────────────────────────────────────

    @Test
    fun `item explicitly in BLOCKED role is included with blockType explicit`(): Unit = runBlocking {
        val blockedId = createBlockedItem("Blocked Item")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        assertEquals(1, items.size)

        val item = items[0].jsonObject
        assertEquals(blockedId.toString(), item["itemId"]!!.jsonPrimitive.content)
        assertEquals("blocked", item["role"]!!.jsonPrimitive.content)
        assertEquals("explicit", item["blockType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `explicitly BLOCKED item with no deps has empty blockedBy array`(): Unit = runBlocking {
        createBlockedItem("Blocked No Deps")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        assertEquals(1, items.size)
        val blockedBy = items[0].jsonObject["blockedBy"]!!.jsonArray
        assertEquals(0, blockedBy.size)
        assertEquals(0, items[0].jsonObject["blockerCount"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test: Dependency-blocked items (QUEUE)
    // ──────────────────────────────────────────────

    @Test
    fun `item in QUEUE with unsatisfied BLOCKS dep is included with blockType dependency`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Blocked By Dep", role = "queue")

        // Blocker BLOCKS blocked
        createDep(blockerId, blockedId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        // blockedId should be blocked; blockerId itself has no incoming blocking deps
        val blockedItem = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(blockedItem)
        assertEquals("dependency", blockedItem.jsonObject["blockType"]!!.jsonPrimitive.content)
        assertEquals("queue", blockedItem.jsonObject["role"]!!.jsonPrimitive.content)

        val blockedBy = blockedItem.jsonObject["blockedBy"]!!.jsonArray
        assertEquals(1, blockedBy.size)
        assertEquals(blockerId, blockedBy[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertFalse(blockedBy[0].jsonObject["satisfied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // Test: Dependency-blocked items (WORK)
    // ──────────────────────────────────────────────

    @Test
    fun `item in WORK with unsatisfied dep is included`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Work Item Blocked", role = "work")

        createDep(blockerId, blockedId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val workItem = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(workItem)
        assertEquals("work", workItem.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("dependency", workItem.jsonObject["blockType"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Test: All deps satisfied -> NOT included
    // ──────────────────────────────────────────────

    @Test
    fun `item in QUEUE with all deps satisfied is NOT included`(): Unit = runBlocking {
        val blockerId = createItem("Completed Blocker", role = "terminal")
        val itemId = createItem("Should Be Free", role = "queue")

        // Blocker is TERMINAL, default unblockAt is "terminal" -> satisfied
        createDep(blockerId, itemId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val found = items.any { it.jsonObject["itemId"]!!.jsonPrimitive.content == itemId }
        assertFalse(found, "Item with all deps satisfied should not be in blocked list")
    }

    // ──────────────────────────────────────────────
    // Test: unblockAt threshold satisfied
    // ──────────────────────────────────────────────

    @Test
    fun `unblockAt work with blocker at WORK means dep satisfied, item NOT included`(): Unit = runBlocking {
        val blockerId = createItem("Blocker at Work", role = "work")
        val itemId = createItem("Downstream", role = "queue")

        // unblockAt=work means dep is satisfied once blocker is at WORK or beyond
        createDep(blockerId, itemId, unblockAt = "work")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val found = items.any { it.jsonObject["itemId"]!!.jsonPrimitive.content == itemId }
        assertFalse(found, "Item should not be blocked when blocker is at WORK and unblockAt=work")
    }

    @Test
    fun `unblockAt work with blocker at QUEUE means dep unsatisfied, item included`(): Unit = runBlocking {
        val blockerId = createItem("Blocker at Queue", role = "queue")
        val itemId = createItem("Downstream Blocked", role = "queue")

        createDep(blockerId, itemId, unblockAt = "work")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val found = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == itemId }
        assertNotNull(found, "Item should be blocked when blocker is at QUEUE and unblockAt=work")

        // Verify the blocker entry has correct unblockAt details
        val blockerEntry = found.jsonObject["blockedBy"]!!.jsonArray[0].jsonObject
        assertEquals("work", blockerEntry["unblockAt"]!!.jsonPrimitive.content)
        assertEquals("work", blockerEntry["effectiveUnblockRole"]!!.jsonPrimitive.content)
        assertFalse(blockerEntry["satisfied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // Test: parentId scoping
    // ──────────────────────────────────────────────

    @Test
    fun `parentId scoping only returns items under that parent`(): Unit = runBlocking {
        val parent1 = createItem("Parent 1")
        val parent2 = createItem("Parent 2")

        val child1 = createItem("Child 1", parentId = parent1, role = "queue")
        val child2 = createItem("Child 2", parentId = parent2, role = "queue")

        val blocker1 = createItem("Blocker 1", role = "queue")
        val blocker2 = createItem("Blocker 2", role = "queue")

        createDep(blocker1, child1)
        createDep(blocker2, child2)

        // Query scoped to parent1
        val result = tool.execute(
            params("parentId" to JsonPrimitive(parent1)),
            context
        ) as JsonObject

        val items = extractBlockedItems(result)
        assertEquals(1, extractTotal(result))
        assertEquals(child1, items[0].jsonObject["itemId"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Test: includeItemDetails
    // ──────────────────────────────────────────────

    @Test
    fun `includeItemDetails true includes summary and tags`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Blocked", role = "queue", summary = "A summary", tags = "backend,api")

        createDep(blockerId, blockedId)

        val result = tool.execute(
            params("includeItemDetails" to JsonPrimitive(true)),
            context
        ) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(item)
        assertEquals("A summary", item.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals("backend,api", item.jsonObject["tags"]!!.jsonPrimitive.content)
    }

    @Test
    fun `includeItemDetails false omits summary and tags`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Blocked", role = "queue", summary = "A summary", tags = "backend")

        createDep(blockerId, blockedId)

        val result = tool.execute(
            params("includeItemDetails" to JsonPrimitive(false)),
            context
        ) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(item)
        assertNull(item.jsonObject["summary"])
        assertNull(item.jsonObject["tags"])
    }

    // ──────────────────────────────────────────────
    // Test: TERMINAL items never included
    // ──────────────────────────────────────────────

    @Test
    fun `items in TERMINAL role are never included`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val terminalId = createItem("Terminal Item", role = "terminal")

        // Even if there's a dep pointing at the terminal item, it should not appear
        createDep(blockerId, terminalId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val found = items.any { it.jsonObject["itemId"]!!.jsonPrimitive.content == terminalId }
        assertFalse(found, "Terminal items should never appear in blocked list")
    }

    // ──────────────────────────────────────────────
    // Test: RELATES_TO deps are ignored for blocking
    // ──────────────────────────────────────────────

    @Test
    fun `RELATES_TO deps are ignored for blocking`(): Unit = runBlocking {
        val relatedId = createItem("Related Item", role = "queue")
        val itemId = createItem("Item With Relates To", role = "queue")

        createDep(relatedId, itemId, type = "RELATES_TO")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val found = items.any { it.jsonObject["itemId"]!!.jsonPrimitive.content == itemId }
        assertFalse(found, "RELATES_TO dependencies should not cause blocking")
    }

    // ──────────────────────────────────────────────
    // Test: Multiple blockers
    // ──────────────────────────────────────────────

    @Test
    fun `multiple blockers all listed in blockedBy array with correct count`(): Unit = runBlocking {
        val blocker1 = createItem("Blocker 1", role = "queue")
        val blocker2 = createItem("Blocker 2", role = "queue")
        val blocker3 = createItem("Blocker 3", role = "terminal")
        val blockedId = createItem("Multi-Blocked", role = "queue")

        createDep(blocker1, blockedId)
        createDep(blocker2, blockedId)
        createDep(blocker3, blockedId) // This one is satisfied (terminal)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(item)

        val blockedBy = item.jsonObject["blockedBy"]!!.jsonArray
        assertEquals(3, blockedBy.size)

        // blockerCount should be 2 (only unsatisfied ones)
        assertEquals(2, item.jsonObject["blockerCount"]!!.jsonPrimitive.int)

        // Verify blocker3 (terminal) is satisfied
        val terminalBlocker = blockedBy.find {
            it.jsonObject["itemId"]!!.jsonPrimitive.content == blocker3
        }
        assertNotNull(terminalBlocker)
        assertTrue(terminalBlocker.jsonObject["satisfied"]!!.jsonPrimitive.boolean)
    }

    // ──────────────────────────────────────────────
    // Test: IS_BLOCKED_BY dependency
    // ──────────────────────────────────────────────

    @Test
    fun `IS_BLOCKED_BY dep correctly identified`(): Unit = runBlocking {
        val blockerId = createItem("Blocker Via IS_BLOCKED_BY", role = "queue")
        val blockedId = createItem("Blocked Via IS_BLOCKED_BY", role = "queue")

        // IS_BLOCKED_BY: fromItemId is blocked BY toItemId
        createDep(blockedId, blockerId, type = "IS_BLOCKED_BY")

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(item, "Item with IS_BLOCKED_BY dep should appear as blocked")
        assertEquals("dependency", item.jsonObject["blockType"]!!.jsonPrimitive.content)

        val blockedBy = item.jsonObject["blockedBy"]!!.jsonArray
        assertEquals(1, blockedBy.size)
        assertEquals(blockerId, blockedBy[0].jsonObject["itemId"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Test: Blocker has effectiveUnblockRole in output
    // ──────────────────────────────────────────────

    @Test
    fun `blocker entry includes effectiveUnblockRole defaulting to terminal`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Blocked", role = "queue")

        // No unblockAt set -> effective is "terminal"
        createDep(blockerId, blockedId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == blockedId }
        assertNotNull(item)

        val blockerEntry = item.jsonObject["blockedBy"]!!.jsonArray[0].jsonObject
        assertEquals("terminal", blockerEntry["effectiveUnblockRole"]!!.jsonPrimitive.content)
        // unblockAt should not be present (was null)
        assertNull(blockerEntry["unblockAt"])
    }

    // ──────────────────────────────────────────────
    // Test: REVIEW item with unsatisfied dep
    // ──────────────────────────────────────────────

    @Test
    fun `item in REVIEW with unsatisfied dep is included`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val reviewId = createItem("Review Item", role = "review")

        createDep(blockerId, reviewId)

        val result = tool.execute(params(), context) as JsonObject

        val items = extractBlockedItems(result)
        val item = items.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == reviewId }
        assertNotNull(item)
        assertEquals("review", item.jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("dependency", item.jsonObject["blockType"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Test: userSummary
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary returns correct message`(): Unit = runBlocking {
        val blockerId = createItem("Blocker", role = "queue")
        val blockedId = createItem("Blocked", role = "queue")
        createDep(blockerId, blockedId)

        val result = tool.execute(params(), context) as JsonObject
        val summary = tool.userSummary(params(), result, false)
        assertTrue(summary.contains("blocked item"), "Summary should mention blocked items")
    }

    @Test
    fun `userSummary returns failure message on error`() {
        val summary = tool.userSummary(params(), JsonObject(emptyMap()), true)
        assertEquals("get_blocked_items failed", summary)
    }
}
