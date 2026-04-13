package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Tests short hex prefix resolution for manage_items operations (create, update, delete).
 *
 * Covers the four parameters that were missing short-ID support:
 * - update: items[].id and items[].parentId
 * - delete: ids[]
 * - create: per-item items[].parentId
 */
class ManageItemsToolPrefixTest {
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

    private suspend fun createItem(
        title: String,
        parentId: String? = null
    ): String {
        val itemObj = buildJsonObject {
            put("title", JsonPrimitive(title))
            parentId?.let { put("parentId", JsonPrimitive(it)) }
        }
        val result = tool.execute(
            buildJsonObject {
                put("operation", JsonPrimitive("create"))
                put("items", JsonArray(listOf(itemObj)))
                parentId?.let { put("parentId", JsonPrimitive(it)) }
            },
            context
        ) as JsonObject
        return (result["data"] as JsonObject)["items"]!!
            .jsonArray[0]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    }

    // ──────────────────────────────────────────────
    // Update: items[].id prefix resolution
    // ──────────────────────────────────────────────

    @Test
    fun `update by 4-char prefix resolves item`(): Unit = runBlocking {
        val itemId = createItem("Update Prefix Test")
        val prefix = itemId.substring(0, 4)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(prefix))
                        put("summary", JsonPrimitive("Updated via 4-char prefix"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["updated"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `update by 8-char prefix resolves item`(): Unit = runBlocking {
        val itemId = createItem("Update 8-char Test")
        val prefix = itemId.substring(0, 8)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(prefix))
                        put("summary", JsonPrimitive("Updated via 8-char prefix"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["updated"]!!.jsonPrimitive.int)
    }

    @Test
    fun `update by full UUID still works (regression)`(): Unit = runBlocking {
        val itemId = createItem("Update Full UUID")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(itemId))
                        put("summary", JsonPrimitive("Updated via full UUID"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["updated"]!!.jsonPrimitive.int)
    }

    @Test
    fun `update with too-short prefix returns error`(): Unit = runBlocking {
        createItem("Short Prefix Item")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive("abc"))
                        put("summary", JsonPrimitive("Should fail"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean) // envelope succeeds, item fails
        val data = result["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0].jsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("prefix too short"))
    }

    @Test
    fun `update with non-hex prefix returns error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive("ghij1234"))
                        put("summary", JsonPrimitive("Should fail"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0].jsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("must be a UUID or hex prefix"))
    }

    @Test
    fun `update with non-matching prefix returns not-found error`(): Unit = runBlocking {
        createItem("Some Item")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive("0000"))
                        put("summary", JsonPrimitive("Should fail"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["updated"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0].jsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("No WorkItem found"))
    }

    // ──────────────────────────────────────────────
    // Update: items[].parentId prefix resolution
    // ──────────────────────────────────────────────

    @Test
    fun `update parentId by short prefix moves item`(): Unit = runBlocking {
        val parentId = createItem("New Parent")
        val childId = createItem("Orphan Child")
        val parentPrefix = parentId.substring(0, 8)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("update"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("id", JsonPrimitive(childId))
                        put("parentId", JsonPrimitive(parentPrefix))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["updated"]!!.jsonPrimitive.int)

        // Verify the item was actually moved
        val queryTool = QueryItemsTool()
        val getResult = queryTool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(childId)
            ),
            context
        ) as JsonObject
        val itemData = getResult["data"] as JsonObject
        assertEquals(parentId, itemData["parentId"]!!.jsonPrimitive.content)
        assertEquals(1, itemData["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Delete: ids[] prefix resolution
    // ──────────────────────────────────────────────

    @Test
    fun `delete by 4-char prefix resolves and deletes item`(): Unit = runBlocking {
        val itemId = createItem("Delete Prefix Test")
        val prefix = itemId.substring(0, 4)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(prefix)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete by 8-char prefix resolves and deletes item`(): Unit = runBlocking {
        val itemId = createItem("Delete 8-char Test")
        val prefix = itemId.substring(0, 8)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(prefix)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete by full UUID still works (regression)`(): Unit = runBlocking {
        val itemId = createItem("Delete Full UUID")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(itemId)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete with too-short prefix returns error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive("ab")))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0].jsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("prefix too short"))
    }

    @Test
    fun `delete with non-hex prefix returns error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive("zzzz1234")))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0].jsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("must be a UUID or hex prefix"))
    }

    @Test
    fun `delete multiple items by short prefix`(): Unit = runBlocking {
        val id1 = createItem("Delete Multi 1")
        val id2 = createItem("Delete Multi 2")
        val prefix1 = id1.substring(0, 8)
        val prefix2 = id2.substring(0, 8)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(prefix1), JsonPrimitive(prefix2)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete recursive by short prefix`(): Unit = runBlocking {
        val parentId = createItem("Parent for Recursive")
        val createChildResult = tool.execute(
            buildJsonObject {
                put("operation", JsonPrimitive("create"))
                put("parentId", JsonPrimitive(parentId))
                put("items", JsonArray(listOf(
                    buildJsonObject { put("title", JsonPrimitive("Child 1")) },
                    buildJsonObject { put("title", JsonPrimitive("Child 2")) }
                )))
            },
            context
        ) as JsonObject
        assertTrue(createChildResult["success"]!!.jsonPrimitive.boolean)

        val prefix = parentId.substring(0, 8)
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(prefix))),
                "recursive" to JsonPrimitive(true)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        // 1 parent + 2 children = 3 total deleted
        assertEquals(3, data["deleted"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Create: per-item parentId prefix resolution
    // ──────────────────────────────────────────────

    @Test
    fun `create with per-item parentId short prefix`(): Unit = runBlocking {
        val parentId = createItem("Parent Item")
        val parentPrefix = parentId.substring(0, 8)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child via prefix"))
                        put("parentId", JsonPrimitive(parentPrefix))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)
        val created = data["items"]!!.jsonArray[0].jsonObject
        assertEquals(1, created["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create with per-item parentId 4-char prefix`(): Unit = runBlocking {
        val parentId = createItem("Parent Short")
        val prefix = parentId.substring(0, 4)

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child via 4-char prefix"))
                        put("parentId", JsonPrimitive(prefix))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create with per-item parentId full UUID (regression)`(): Unit = runBlocking {
        val parentId = createItem("Parent Full UUID")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Child via full UUID"))
                        put("parentId", JsonPrimitive(parentId))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create with invalid per-item parentId prefix returns error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "items" to JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Bad parent"))
                        put("parentId", JsonPrimitive("xyz"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["created"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `create per-item parentId overrides shared parentId with short prefix`(): Unit = runBlocking {
        val sharedParent = createItem("Shared Parent")
        val itemParent = createItem("Item Parent")
        val itemParentPrefix = itemParent.substring(0, 8)

        val result = tool.execute(
            buildJsonObject {
                put("operation", JsonPrimitive("create"))
                put("parentId", JsonPrimitive(sharedParent))
                put("items", JsonArray(listOf(
                    buildJsonObject {
                        put("title", JsonPrimitive("Uses shared parent"))
                    },
                    buildJsonObject {
                        put("title", JsonPrimitive("Uses item-level parent"))
                        put("parentId", JsonPrimitive(itemParentPrefix))
                    }
                )))
            },
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["created"]!!.jsonPrimitive.int)

        // Verify the second item used the per-item parent
        val secondItemId = data["items"]!!.jsonArray[1].jsonObject["id"]!!.jsonPrimitive.content
        val queryTool = QueryItemsTool()
        val getResult = queryTool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(secondItemId)
            ),
            context
        ) as JsonObject
        val itemData = getResult["data"] as JsonObject
        assertEquals(itemParent, itemData["parentId"]!!.jsonPrimitive.content)
    }
}
