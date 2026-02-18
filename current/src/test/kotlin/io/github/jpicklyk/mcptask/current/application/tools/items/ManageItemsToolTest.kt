package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
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

class ManageItemsToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: ManageItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    // ──────────────────────────────────────────────
    // Create operations
    // ──────────────────────────────────────────────

    @Test
    fun `create single item with title only`() = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Simple item")) }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)

        val items = data["items"]!!.jsonArray
        assertEquals(1, items.size)
        val item = items[0] as JsonObject
        assertNotNull(item["id"]!!.jsonPrimitive.content)
        assertEquals("Simple item", item["title"]!!.jsonPrimitive.content)
        assertEquals(0, item["depth"]!!.jsonPrimitive.int)
        assertEquals("queue", item["role"]!!.jsonPrimitive.content)
        assertEquals("medium", item["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create item with all fields`() = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Full item"))
                        put("description", JsonPrimitive("A detailed description"))
                        put("summary", JsonPrimitive("Short summary"))
                        put("role", JsonPrimitive("work"))
                        put("priority", JsonPrimitive("high"))
                        put("complexity", JsonPrimitive(8))
                        put("tags", JsonPrimitive("backend,api"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)

        val item = data["items"]!!.jsonArray[0] as JsonObject
        assertEquals("Full item", item["title"]!!.jsonPrimitive.content)
        assertEquals("work", item["role"]!!.jsonPrimitive.content)
        assertEquals("high", item["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create item with parentId computes depth`() = runBlocking {
        // Create parent
        val parentResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Parent")) }
                ))
            ),
            context
        ) as JsonObject
        val parentId = (parentResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Create child with parentId
        val childResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child"))
                        put("parentId", JsonPrimitive(parentId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(childResult["success"]!!.jsonPrimitive.boolean)
        val childItem = (childResult["data"] as JsonObject)["items"]!!.jsonArray[0] as JsonObject
        assertEquals(1, childItem["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create item rejects depth beyond MAX_DEPTH`() = runBlocking {
        // Create chain: depth 0 -> 1 -> 2 -> 3 (MAX_DEPTH)
        var currentParentId: String? = null
        for (i in 0..2) {
            val result = tool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(
                        buildJsonObject {
                            put("title", JsonPrimitive("Level $i"))
                            if (currentParentId != null) {
                                put("parentId", JsonPrimitive(currentParentId!!))
                            }
                        }
                    ))
                ),
                context
            ) as JsonObject
            currentParentId = (result["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
        }

        // At this point we have items at depths 0, 1, 2. Parent is at depth 2.
        // Try to create at depth 3 (MAX_DEPTH = 3) - should succeed
        val depth3Result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Level 3"))
                        put("parentId", JsonPrimitive(currentParentId!!))
                    }
                ))
            ),
            context
        ) as JsonObject
        assertTrue(depth3Result["success"]!!.jsonPrimitive.boolean)
        val depth3Id = (depth3Result["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Try to create at depth 4 (beyond MAX_DEPTH = 3) - should fail
        val depth4Result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Level 4 - too deep"))
                        put("parentId", JsonPrimitive(depth3Id))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(depth4Result["success"]!!.jsonPrimitive.boolean) // envelope success - batch partially succeeded
        val data = depth4Result["data"] as JsonObject
        assertEquals(0, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("exceeds maximum depth"))
    }

    @Test
    fun `create item with invalid parentId fails`() = runBlocking {
        val nonexistentId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Orphan"))
                        put("parentId", JsonPrimitive(nonexistentId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"))
    }

    @Test
    fun `create batch with mixed success and failure`() = runBlocking {
        val nonexistentId = UUID.randomUUID().toString()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Valid item")) },
                    buildJsonObject {
                        put("title", JsonPrimitive("Invalid item"))
                        put("parentId", JsonPrimitive(nonexistentId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create item with shared parentId default`() = runBlocking {
        // Create parent
        val parentResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Shared Parent")) }
                ))
            ),
            context
        ) as JsonObject
        val parentId = (parentResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Create two children using shared parentId
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "parentId" to JsonPrimitive(parentId),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Child A")) },
                    buildJsonObject { put("title", JsonPrimitive("Child B")) }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["created"]!!.jsonPrimitive.int)
        val items = data["items"]!!.jsonArray
        assertEquals(1, items[0].jsonObject["depth"]!!.jsonPrimitive.int)
        assertEquals(1, items[1].jsonObject["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create item with invalid tags fails`() = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Bad tags"))
                        put("tags", JsonPrimitive("UPPERCASE,Invalid"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean) // envelope success, but item failed
        val data = result["data"] as JsonObject
        assertEquals(0, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Update operations
    // ──────────────────────────────────────────────

    @Test
    fun `update item title`(): Unit = runBlocking {
        // Create
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Original")) }
                ))
            ),
            context
        ) as JsonObject
        val itemId = (createResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Update
        val updateResult = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId))
                        put("title", JsonPrimitive("Updated"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(updateResult["success"]!!.jsonPrimitive.boolean)
        val data = updateResult["data"] as JsonObject
        assertEquals(1, data["updated"]!!.jsonPrimitive.int)
        assertNotNull(data["items"]!!.jsonArray[0].jsonObject["modifiedAt"])
    }

    @Test
    fun `update partial fields preserves existing`() = runBlocking {
        // Create item with description and summary
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Original"))
                        put("description", JsonPrimitive("Keep this"))
                        put("summary", JsonPrimitive("Keep this too"))
                    }
                ))
            ),
            context
        ) as JsonObject
        val itemId = (createResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Update only title
        tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId))
                        put("title", JsonPrimitive("New Title"))
                    }
                ))
            ),
            context
        )

        // Verify via query_items get
        val queryTool = QueryItemsTool()
        val getResult = queryTool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        val itemData = getResult["data"] as JsonObject
        assertEquals("New Title", itemData["title"]!!.jsonPrimitive.content)
        assertEquals("Keep this", itemData["description"]!!.jsonPrimitive.content)
        assertEquals("Keep this too", itemData["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `update parentId recomputes depth`() = runBlocking {
        // Create two root items
        val rootAResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Root A")) }
                ))
            ),
            context
        ) as JsonObject
        val rootAId = (rootAResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val rootBResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Root B")) }
                ))
            ),
            context
        ) as JsonObject
        val rootBId = (rootBResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Create child under Root A
        val childResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child"))
                        put("parentId", JsonPrimitive(rootAId))
                    }
                ))
            ),
            context
        ) as JsonObject
        val childId = (childResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Move child to Root B
        val updateResult = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(childId))
                        put("parentId", JsonPrimitive(rootBId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(updateResult["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, (updateResult["data"] as JsonObject)["updated"]!!.jsonPrimitive.int)

        // Verify depth is still 1 (moved from one root to another)
        val queryTool = QueryItemsTool()
        val getResult = queryTool.execute(
            params("operation" to JsonPrimitive("get"), "id" to JsonPrimitive(childId)),
            context
        ) as JsonObject
        val itemData = getResult["data"] as JsonObject
        assertEquals(rootBId, itemData["parentId"]!!.jsonPrimitive.content)
        assertEquals(1, itemData["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `update with null parentId moves to root`() = runBlocking {
        // Create parent and child
        val parentResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Parent")) }
                ))
            ),
            context
        ) as JsonObject
        val parentId = (parentResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        val childResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child"))
                        put("parentId", JsonPrimitive(parentId))
                    }
                ))
            ),
            context
        ) as JsonObject
        val childId = (childResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Set parentId to null (move to root)
        val updateResult = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(childId))
                        put("parentId", JsonNull)
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(updateResult["success"]!!.jsonPrimitive.boolean)

        // Verify depth=0 and no parentId
        val queryTool = QueryItemsTool()
        val getResult = queryTool.execute(
            params("operation" to JsonPrimitive("get"), "id" to JsonPrimitive(childId)),
            context
        ) as JsonObject
        val itemData = getResult["data"] as JsonObject
        assertNull(itemData["parentId"])
        assertEquals(0, itemData["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `update item with self as parent is rejected`(): Unit = runBlocking {
        // Create an item
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Self loop item")) }
                ))
            ),
            context
        ) as JsonObject
        val itemId = (createResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Attempt to set parentId to own UUID
        val updateResult = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId))
                        put("parentId", JsonPrimitive(itemId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(updateResult["success"]!!.jsonPrimitive.boolean) // envelope success
        val data = updateResult["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("cannot be its own parent"))
    }

    @Test
    fun `update item to descendant as parent is rejected`(): Unit = runBlocking {
        // Create root item A
        val rootResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Root A")) }
                ))
            ),
            context
        ) as JsonObject
        val rootId = (rootResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Create child B under Root A
        val childResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child B"))
                        put("parentId", JsonPrimitive(rootId))
                    }
                ))
            ),
            context
        ) as JsonObject
        val childId = (childResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Attempt to reparent Root A under Child B (would create A -> B -> A cycle)
        val updateResult = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(rootId))
                        put("parentId", JsonPrimitive(childId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(updateResult["success"]!!.jsonPrimitive.boolean) // envelope success
        val data = updateResult["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("circular hierarchy"))
    }

    @Test
    fun `update nonexistent item fails`() = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(randomId))
                        put("title", JsonPrimitive("Ghost"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Delete operations
    // ──────────────────────────────────────────────

    @Test
    fun `delete item by id`() = runBlocking {
        // Create
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("To delete")) }
                ))
            ),
            context
        ) as JsonObject
        val itemId = (createResult["data"] as JsonObject)["items"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Delete
        val deleteResult = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(itemId)))
            ),
            context
        ) as JsonObject

        assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
        val data = deleteResult["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
        assertTrue(data["ids"]!!.jsonArray.map { it.jsonPrimitive.content }.contains(itemId))
    }

    @Test
    fun `delete nonexistent item returns failure`() = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(randomId)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failures = data["failures"]!!.jsonArray
        assertEquals(1, failures.size)
        val failure = failures[0] as JsonObject
        assertEquals(randomId, failure["id"]!!.jsonPrimitive.content)
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"))
    }

    @Test
    fun `delete batch`() = runBlocking {
        // Create 2 items
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Item 1")) },
                    buildJsonObject { put("title", JsonPrimitive("Item 2")) }
                ))
            ),
            context
        ) as JsonObject

        val items = (createResult["data"] as JsonObject)["items"]!!.jsonArray
        val ids = items.map { it.jsonObject["id"]!!.jsonPrimitive.content }

        // Delete both
        val deleteResult = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(ids.map { JsonPrimitive(it) })
            ),
            context
        ) as JsonObject

        assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
        val data = deleteResult["data"] as JsonObject
        assertEquals(2, data["deleted"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────

    @Test
    fun `create with empty items array throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(emptyList())
                )
            )
        }
    }

    @Test
    fun `unknown operation throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("invalid"))
            )
        }
    }
}
