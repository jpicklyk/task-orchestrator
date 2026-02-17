package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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

class QueryItemsToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool
    private lateinit var manageTool: ManageItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryItemsTool()
        manageTool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /**
     * Helper to create a work item via ManageItemsTool and return its ID.
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

    // ──────────────────────────────────────────────
    // Get operations
    // ──────────────────────────────────────────────

    @Test
    fun `get item by id`(): Unit = runBlocking {
        val itemId = createItem("Test Item", summary = "A short summary")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(itemId, data["id"]!!.jsonPrimitive.content)
        assertEquals("Test Item", data["title"]!!.jsonPrimitive.content)
        assertEquals("A short summary", data["summary"]!!.jsonPrimitive.content)
        assertEquals("queue", data["role"]!!.jsonPrimitive.content)
        assertEquals("medium", data["priority"]!!.jsonPrimitive.content)
        assertEquals(0, data["depth"]!!.jsonPrimitive.int)
        assertNotNull(data["createdAt"])
        assertNotNull(data["modifiedAt"])
        assertNotNull(data["roleChangedAt"])
    }

    @Test
    fun `get nonexistent item returns error`(): Unit = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(randomId)
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertNotNull(result["error"])
    }

    // ──────────────────────────────────────────────
    // Search operations
    // ──────────────────────────────────────────────

    @Test
    fun `search returns all items when no filters`() = runBlocking {
        createItem("Alpha")
        createItem("Beta")
        createItem("Gamma")

        val result = tool.execute(
            params("operation" to JsonPrimitive("search")),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["total"]!!.jsonPrimitive.int)
        assertEquals(3, data["items"]!!.jsonArray.size)
    }

    @Test
    fun `search filters by role`() = runBlocking {
        createItem("Queue Item", role = "queue")
        createItem("Work Item", role = "work")
        createItem("Review Item", role = "review")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("search"),
                "role" to JsonPrimitive("work")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["total"]!!.jsonPrimitive.int)
        val items = data["items"]!!.jsonArray
        assertEquals("work", items[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search filters by priority`() = runBlocking {
        createItem("High Priority", priority = "high")
        createItem("Low Priority", priority = "low")
        createItem("Medium Priority")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("search"),
                "priority" to JsonPrimitive("high")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["total"]!!.jsonPrimitive.int)
        assertEquals("high", data["items"]!!.jsonArray[0].jsonObject["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `search filters by tags`() = runBlocking {
        createItem("Tagged", tags = "backend,api")
        createItem("Other", tags = "frontend")
        createItem("No tags")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("search"),
                "tags" to JsonPrimitive("backend")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `search with text query matches title`() = runBlocking {
        createItem("Authentication module")
        createItem("Database layer")
        createItem("Auth helper")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("search"),
                "query" to JsonPrimitive("Auth")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun `search respects limit`() = runBlocking {
        for (i in 1..5) {
            createItem("Item $i")
        }

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("search"),
                "limit" to JsonPrimitive(2)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["total"]!!.jsonPrimitive.int)
        assertEquals(2, data["items"]!!.jsonArray.size)
    }

    @Test
    fun `search returns minimal fields`() = runBlocking {
        createItem("Minimal", tags = "test")

        val result = tool.execute(
            params("operation" to JsonPrimitive("search")),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val item = data["items"]!!.jsonArray[0] as JsonObject

        // These fields should be present in minimal JSON
        assertNotNull(item["id"])
        assertNotNull(item["title"])
        assertNotNull(item["role"])
        assertNotNull(item["priority"])
        assertNotNull(item["depth"])
        assertNotNull(item["tags"])

        // These fields should NOT be present in minimal JSON
        assertNull(item["description"])
        assertNull(item["summary"])
        assertNull(item["complexity"])
        assertNull(item["createdAt"])
        assertNull(item["modifiedAt"])
    }

    // ──────────────────────────────────────────────
    // Overview operations
    // ──────────────────────────────────────────────

    @Test
    fun `scoped overview returns item with child counts`() = runBlocking {
        val parentId = createItem("Parent")
        createItem("Child Queue 1", parentId = parentId, role = "queue")
        createItem("Child Queue 2", parentId = parentId, role = "queue")
        createItem("Child Work", parentId = parentId, role = "work")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("overview"),
                "itemId" to JsonPrimitive(parentId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject

        // Should contain item metadata
        val item = data["item"] as JsonObject
        assertEquals("Parent", item["title"]!!.jsonPrimitive.content)

        // Should contain child counts
        val childCounts = data["childCounts"] as JsonObject
        assertEquals(2, childCounts["queue"]!!.jsonPrimitive.int)
        assertEquals(1, childCounts["work"]!!.jsonPrimitive.int)

        // Should contain children list
        val children = data["children"]!!.jsonArray
        assertEquals(3, children.size)
    }

    @Test
    fun `scoped overview for nonexistent item returns error`() = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("overview"),
                "itemId" to JsonPrimitive(randomId)
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `global overview returns root items`() = runBlocking {
        createItem("Root A")
        createItem("Root B")
        createItem("Root C")

        val result = tool.execute(
            params("operation" to JsonPrimitive("overview")),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["total"]!!.jsonPrimitive.int)
        assertEquals(3, data["items"]!!.jsonArray.size)
    }

    @Test
    fun `global overview includes child counts per root`() = runBlocking {
        val rootId = createItem("Root with kids")
        createItem("Kid 1", parentId = rootId, role = "queue")
        createItem("Kid 2", parentId = rootId, role = "work")

        val result = tool.execute(
            params("operation" to JsonPrimitive("overview")),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val items = data["items"]!!.jsonArray

        // Find the root with kids
        val rootItem = items.first {
            it.jsonObject["title"]!!.jsonPrimitive.content == "Root with kids"
        }.jsonObject

        val childCounts = rootItem["childCounts"] as JsonObject
        assertEquals(1, childCounts["queue"]!!.jsonPrimitive.int)
        assertEquals(1, childCounts["work"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────

    @Test
    fun `get requires id parameter`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("get"))
            )
        }
    }
}
