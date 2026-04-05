package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemTool
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
    private lateinit var advanceTool: AdvanceItemTool

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
        advanceTool = AdvanceItemTool()
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
        summary: String? = null,
        statusLabel: String? = null,
        type: String? = null,
        properties: String? = null
    ): String {
        val itemObj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                parentId?.let { put("parentId", JsonPrimitive(it)) }
                role?.let { put("role", JsonPrimitive(it)) }
                priority?.let { put("priority", JsonPrimitive(it)) }
                tags?.let { put("tags", JsonPrimitive(it)) }
                summary?.let { put("summary", JsonPrimitive(it)) }
                statusLabel?.let { put("statusLabel", JsonPrimitive(it)) }
                type?.let { put("type", JsonPrimitive(it)) }
                properties?.let { put("properties", JsonPrimitive(it)) }
            }
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(itemObj))
                ),
                context
            ) as JsonObject
        return (result["data"] as JsonObject)["items"]!!
            .jsonArray[0]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    }

    // ──────────────────────────────────────────────
    // Get operations
    // ──────────────────────────────────────────────

    @Test
    fun `get item by id`(): Unit =
        runBlocking {
            val itemId = createItem("Test Item", summary = "A short summary")

            val result =
                tool.execute(
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
    fun `get nonexistent item returns error`(): Unit =
        runBlocking {
            val randomId = UUID.randomUUID().toString()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(randomId)
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertNotNull(result["error"])
        }

    @Test
    fun `get nonexistent item error response contains requestedId as structured field`(): Unit =
        runBlocking {
            val randomId = UUID.randomUUID()
            val randomIdStr = randomId.toString()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(randomIdStr)
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            // The response data block must contain requestedId as a separate structured field
            val data = result["data"] as? JsonObject
            assertNotNull(data, "Error response should include a data block with requestedId")
            val requestedId = data["requestedId"]?.jsonPrimitive?.content
            assertEquals(randomIdStr, requestedId, "requestedId in response data must equal the attempted UUID")
        }

    @Test
    fun `get nonexistent item error response requestedId differs from other nonexistent id`(): Unit =
        runBlocking {
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()

            val result1 =
                tool.execute(
                    params("operation" to JsonPrimitive("get"), "id" to JsonPrimitive(id1.toString())),
                    context
                ) as JsonObject
            val result2 =
                tool.execute(
                    params("operation" to JsonPrimitive("get"), "id" to JsonPrimitive(id2.toString())),
                    context
                ) as JsonObject

            val reqId1 = (result1["data"] as? JsonObject)?.get("requestedId")?.jsonPrimitive?.content
            val reqId2 = (result2["data"] as? JsonObject)?.get("requestedId")?.jsonPrimitive?.content

            assertEquals(id1.toString(), reqId1)
            assertEquals(id2.toString(), reqId2)
            assertNotEquals(reqId1, reqId2, "requestedId should reflect the specific UUID attempted")
        }

    // ──────────────────────────────────────────────
    // Search operations
    // ──────────────────────────────────────────────

    @Test
    fun `search returns all items when no filters`() =
        runBlocking {
            createItem("Alpha")
            createItem("Beta")
            createItem("Gamma")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(3, data["total"]!!.jsonPrimitive.int)
            assertEquals(3, data["items"]!!.jsonArray.size)
        }

    @Test
    fun `search filters by role`() =
        runBlocking {
            createItem("Queue Item", role = "queue")
            createItem("Work Item", role = "work")
            createItem("Review Item", role = "review")

            val result =
                tool.execute(
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
    fun `search filters by priority`() =
        runBlocking {
            createItem("High Priority", priority = "high")
            createItem("Low Priority", priority = "low")
            createItem("Medium Priority")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "priority" to JsonPrimitive("high")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["total"]!!.jsonPrimitive.int)
            assertEquals(
                "high",
                data["items"]!!
                    .jsonArray[0]
                    .jsonObject["priority"]!!
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `search filters by tags`() =
        runBlocking {
            createItem("Tagged", tags = "backend,api")
            createItem("Other", tags = "frontend")
            createItem("No tags")

            val result =
                tool.execute(
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
    fun `search with text query matches title`() =
        runBlocking {
            createItem("Authentication module")
            createItem("Database layer")
            createItem("Auth helper")

            val result =
                tool.execute(
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
    fun `search respects limit`() =
        runBlocking {
            for (i in 1..5) {
                createItem("Item $i")
            }

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "limit" to JsonPrimitive(2)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            // total reflects full match count, returned reflects the page size
            assertEquals(5, data["total"]!!.jsonPrimitive.int)
            assertEquals(2, data["returned"]!!.jsonPrimitive.int)
            assertEquals(2, data["items"]!!.jsonArray.size)
            assertEquals(2, data["limit"]!!.jsonPrimitive.int)
            assertEquals(0, data["offset"]!!.jsonPrimitive.int)
        }

    @Test
    fun `search pagination with offset returns correct page`() =
        runBlocking {
            for (i in 1..5) {
                createItem("Item $i")
            }

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "limit" to JsonPrimitive(2),
                        "offset" to JsonPrimitive(2)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(5, data["total"]!!.jsonPrimitive.int)
            assertEquals(2, data["returned"]!!.jsonPrimitive.int)
            assertEquals(2, data["items"]!!.jsonArray.size)
            assertEquals(2, data["limit"]!!.jsonPrimitive.int)
            assertEquals(2, data["offset"]!!.jsonPrimitive.int)
        }

    @Test
    fun `search with limit larger than result set returns correct total and returned`() =
        runBlocking {
            createItem("Alpha")
            createItem("Beta")
            createItem("Gamma")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(3, data["total"]!!.jsonPrimitive.int)
            assertEquals(3, data["returned"]!!.jsonPrimitive.int)
            assertEquals(3, data["items"]!!.jsonArray.size)
            assertEquals(10, data["limit"]!!.jsonPrimitive.int)
            assertEquals(0, data["offset"]!!.jsonPrimitive.int)
        }

    @Test
    fun `search returns minimal fields`() =
        runBlocking {
            createItem("Minimal", tags = "test")

            val result =
                tool.execute(
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
    fun `scoped overview returns item with child counts`() =
        runBlocking {
            val parentId = createItem("Parent")
            createItem("Child Queue 1", parentId = parentId, role = "queue")
            createItem("Child Queue 2", parentId = parentId, role = "queue")
            createItem("Child Work", parentId = parentId, role = "work")

            val result =
                tool.execute(
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
    fun `scoped overview for nonexistent item returns error`() =
        runBlocking {
            val randomId = UUID.randomUUID().toString()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "itemId" to JsonPrimitive(randomId)
                    ),
                    context
                ) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `global overview returns root items`() =
        runBlocking {
            createItem("Root A")
            createItem("Root B")
            createItem("Root C")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(3, data["total"]!!.jsonPrimitive.int)
            assertEquals(3, data["items"]!!.jsonArray.size)
        }

    @Test
    fun `global overview root items include tags and type when set`() =
        runBlocking {
            createItem("Typed and Tagged Root", tags = "backend,api", type = "feature-task")
            createItem("Plain Root")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            val typedRoot =
                items.first {
                    it.jsonObject["title"]!!.jsonPrimitive.content == "Typed and Tagged Root"
                }.jsonObject

            // tags and type should be present when set on the root item
            assertTrue(typedRoot.containsKey("tags"), "Root item should include tags when set")
            assertEquals("backend,api", typedRoot["tags"]!!.jsonPrimitive.content)
            assertTrue(typedRoot.containsKey("type"), "Root item should include type when set")
            assertEquals("feature-task", typedRoot["type"]!!.jsonPrimitive.content)

            val plainRoot =
                items.first {
                    it.jsonObject["title"]!!.jsonPrimitive.content == "Plain Root"
                }.jsonObject

            // tags and type should be absent when not set
            assertFalse(plainRoot.containsKey("tags"), "Root item should not include tags key when not set")
            assertFalse(plainRoot.containsKey("type"), "Root item should not include type key when not set")
        }

    @Test
    fun `global overview includes child counts per root`() =
        runBlocking {
            val rootId = createItem("Root with kids")
            createItem("Kid 1", parentId = rootId, role = "queue")
            createItem("Kid 2", parentId = rootId, role = "work")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            // Find the root with kids
            val rootItem =
                items
                    .first {
                        it.jsonObject["title"]!!.jsonPrimitive.content == "Root with kids"
                    }.jsonObject

            val childCounts = rootItem["childCounts"] as JsonObject
            assertEquals(1, childCounts["queue"]!!.jsonPrimitive.int)
            assertEquals(1, childCounts["work"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // includeAncestors — get operation
    // ──────────────────────────────────────────────

    @Test
    fun `get with includeAncestors true returns ancestors array for depth 2 item`(): Unit =
        runBlocking {
            val rootId = createItem("Root Item")
            val parentId = createItem("Parent Item", parentId = rootId)
            val childId = createItem("Child Item", parentId = parentId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(childId),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(childId, data["id"]!!.jsonPrimitive.content)

            val ancestors = data["ancestors"]!!.jsonArray
            assertEquals(2, ancestors.size)

            // Root first
            val firstAncestor = ancestors[0].jsonObject
            assertEquals(rootId, firstAncestor["id"]!!.jsonPrimitive.content)
            assertEquals("Root Item", firstAncestor["title"]!!.jsonPrimitive.content)
            assertEquals(0, firstAncestor["depth"]!!.jsonPrimitive.int)

            // Direct parent last
            val secondAncestor = ancestors[1].jsonObject
            assertEquals(parentId, secondAncestor["id"]!!.jsonPrimitive.content)
            assertEquals("Parent Item", secondAncestor["title"]!!.jsonPrimitive.content)
            assertEquals(1, secondAncestor["depth"]!!.jsonPrimitive.int)
        }

    @Test
    fun `get with includeAncestors false omits ancestors key`(): Unit =
        runBlocking {
            val rootId = createItem("Root Item")
            val childId = createItem("Child Item", parentId = rootId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(childId),
                        "includeAncestors" to JsonPrimitive(false)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertNull(data["ancestors"])
        }

    @Test
    fun `get with includeAncestors true returns empty ancestors for root item`(): Unit =
        runBlocking {
            val rootId = createItem("Root Item")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(rootId),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val ancestors = data["ancestors"]!!.jsonArray
            assertEquals(0, ancestors.size)
        }

    // ──────────────────────────────────────────────
    // includeAncestors — search operation
    // ──────────────────────────────────────────────

    @Test
    fun `search with includeAncestors true returns ancestors for each item`(): Unit =
        runBlocking {
            val rootId = createItem("Root")
            val parentId = createItem("Parent", parentId = rootId)
            val child1Id = createItem("Child A", parentId = parentId)
            val child2Id = createItem("Child B", parentId = parentId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("Child"),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["total"]!!.jsonPrimitive.int)

            val items = data["items"]!!.jsonArray
            items.forEach { itemElement ->
                val item = itemElement.jsonObject
                val ancestors = item["ancestors"]!!.jsonArray
                assertEquals(2, ancestors.size)
                assertEquals(rootId, ancestors[0].jsonObject["id"]!!.jsonPrimitive.content)
                assertEquals(parentId, ancestors[1].jsonObject["id"]!!.jsonPrimitive.content)
            }
        }

    @Test
    fun `search without includeAncestors omits ancestors key`(): Unit =
        runBlocking {
            val rootId = createItem("Root")
            createItem("Child", parentId = rootId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("Child")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)
            assertNull(items[0].jsonObject["ancestors"])
        }

    // ──────────────────────────────────────────────
    // includeChildren — global overview operation
    // ──────────────────────────────────────────────

    @Test
    fun `global overview with includeChildren true includes children array per root`(): Unit =
        runBlocking {
            val rootId = createItem("Root With Children")
            createItem("Child One", parentId = rootId, role = "queue", tags = "backend", type = "feature-task", priority = "high")
            createItem("Child Two", parentId = rootId, role = "work")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "includeChildren" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            val rootItem =
                items
                    .first {
                        it.jsonObject["title"]!!.jsonPrimitive.content == "Root With Children"
                    }.jsonObject

            val children = rootItem["children"]!!.jsonArray
            assertEquals(2, children.size)

            val childTitles = children.map { it.jsonObject["title"]!!.jsonPrimitive.content }.toSet()
            assertTrue(childTitles.contains("Child One"))
            assertTrue(childTitles.contains("Child Two"))

            // Verify child structure: id, title, role, depth, priority, parentId, childCounts
            val firstChild = children.first { it.jsonObject["title"]!!.jsonPrimitive.content == "Child One" }.jsonObject
            assertNotNull(firstChild["id"])
            assertNotNull(firstChild["title"])
            assertNotNull(firstChild["role"])
            assertNotNull(firstChild["depth"])
            assertEquals(1, firstChild["depth"]!!.jsonPrimitive.int)

            // priority should be present (toMinimalJson includes it)
            assertNotNull(firstChild["priority"], "Child should include priority field")
            assertEquals("high", firstChild["priority"]!!.jsonPrimitive.content)

            // parentId should be present and equal root id
            assertNotNull(firstChild["parentId"], "Child should include parentId field")
            assertEquals(rootId, firstChild["parentId"]!!.jsonPrimitive.content)

            // tags should be present when set
            assertTrue(firstChild.containsKey("tags"), "Child should include tags when set")
            assertEquals("backend", firstChild["tags"]!!.jsonPrimitive.content)

            // type should be present when set
            assertTrue(firstChild.containsKey("type"), "Child should include type when set")
            assertEquals("feature-task", firstChild["type"]!!.jsonPrimitive.content)

            // childCounts should be present as an object with role keys
            val childCounts = firstChild["childCounts"]
            assertNotNull(childCounts, "Child should include childCounts object")
            val childCountsObj = childCounts!!.jsonObject
            assertNotNull(childCountsObj["queue"], "childCounts should have a queue key")
            assertNotNull(childCountsObj["work"], "childCounts should have a work key")
        }

    @Test
    fun `global overview without includeChildren omits children key`(): Unit =
        runBlocking {
            val rootId = createItem("Root No Children Param")
            createItem("Child", parentId = rootId)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            val rootItem =
                items
                    .first {
                        it.jsonObject["title"]!!.jsonPrimitive.content == "Root No Children Param"
                    }.jsonObject

            assertNull(rootItem["children"])
        }

    // ──────────────────────────────────────────────
    // statusLabel coverage
    // ──────────────────────────────────────────────

    @Test
    fun `get returns statusLabel in full JSON when non-null`(): Unit =
        runBlocking {
            val itemId = createItem("Labeled Item", statusLabel = "in-progress")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(itemId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertTrue(data.containsKey("statusLabel"), "statusLabel key should be present when non-null")
            assertEquals("in-progress", data["statusLabel"]!!.jsonPrimitive.content)
        }

    @Test
    fun `get omits statusLabel key from full JSON when null`(): Unit =
        runBlocking {
            val itemId = createItem("No Label Item")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "id" to JsonPrimitive(itemId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertFalse(data.containsKey("statusLabel"), "statusLabel key should be absent when null (not present as JSON null)")
        }

    @Test
    fun `search with includeAncestors does not include statusLabel in ancestor objects`(): Unit =
        runBlocking {
            // buildAncestorsArray serializes only id, title, depth — statusLabel is not included
            val parentId = createItem("Parent With Label", statusLabel = "done")
            val childId = createItem("Child Under Labeled Parent", parentId = parentId)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("Child Under"),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray
            assertEquals(1, items.size)

            val childItem = items[0].jsonObject
            val ancestors = childItem["ancestors"]!!.jsonArray
            assertEquals(1, ancestors.size)

            val parentAncestor = ancestors[0].jsonObject
            assertEquals(parentId, parentAncestor["id"]!!.jsonPrimitive.content)
            assertEquals("Parent With Label", parentAncestor["title"]!!.jsonPrimitive.content)
            // Ancestor objects use a custom format (id, title, depth only) — statusLabel is not serialized
            assertFalse(
                parentAncestor.containsKey("statusLabel"),
                "Ancestor objects should not include statusLabel (buildAncestorsArray only emits id, title, depth)"
            )
        }

    @Test
    fun `search minimal JSON includes statusLabel when non-null and omits key when null`(): Unit =
        runBlocking {
            // Verifies: non-null → key present with value; null → key truly absent (not JSON null)
            val withLabelId = createItem("With Label", statusLabel = "active")
            val withoutLabelId = createItem("Without Label")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val items = result["data"]!!.jsonObject["items"]!!.jsonArray

            val withLabelItem =
                items
                    .first {
                        it.jsonObject["id"]!!.jsonPrimitive.content == withLabelId
                    }.jsonObject
            val withoutLabelItem =
                items
                    .first {
                        it.jsonObject["id"]!!.jsonPrimitive.content == withoutLabelId
                    }.jsonObject

            // toMinimalJson uses statusLabel?.let { put(...) } — present when non-null, absent when null
            assertTrue(
                withLabelItem.containsKey("statusLabel"),
                "Minimal JSON should include statusLabel key when non-null"
            )
            assertEquals("active", withLabelItem["statusLabel"]!!.jsonPrimitive.content)
            assertFalse(
                withoutLabelItem.containsKey("statusLabel"),
                "Minimal JSON should not include statusLabel key when null (true absence, not JSON null)"
            )
        }

    // ──────────────────────────────────────────────
    // type filter in search
    // ──────────────────────────────────────────────

    @Test
    fun `search by type returns only matching items`(): Unit =
        runBlocking {
            // Create items with different types
            val typedId = createItem("Typed item", type = "feature-task")
            val otherTypedId = createItem("Other typed", type = "bug")
            val untypedId = createItem("Untyped item")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "type" to JsonPrimitive("feature-task")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray
            val ids = items.map { it.jsonObject["id"]!!.jsonPrimitive.content }

            assertTrue(ids.contains(typedId), "Should include item with matching type")
            assertFalse(ids.contains(otherTypedId), "Should exclude item with different type")
            assertFalse(ids.contains(untypedId), "Should exclude item with no type")
        }

    @Test
    fun `search results include type field in response objects`(): Unit =
        runBlocking {
            createItem("Typed item", type = "feature-task")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "type" to JsonPrimitive("feature-task")
                    ),
                    context
                ) as JsonObject

            val items = (result["data"] as JsonObject)["items"]!!.jsonArray
            assertEquals(1, items.size)
            val item = items[0].jsonObject
            assertTrue(item.containsKey("type"), "Search result should include type field")
            assertEquals("feature-task", item["type"]!!.jsonPrimitive.content)
        }

    @Test
    fun `search by type with no matches returns empty results`(): Unit =
        runBlocking {
            createItem("Some item", type = "bug")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "type" to JsonPrimitive("nonexistent-type")
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(0, data["total"]!!.jsonPrimitive.int)
            assertEquals(0, data["items"]!!.jsonArray.size)
        }

    // ──────────────────────────────────────────────
    // traits in global overview
    // ──────────────────────────────────────────────

    @Test
    fun `global overview root item includes traits when properties has traits set`(): Unit =
        runBlocking {
            // Create a root item with properties containing a traits array
            val traitProperties = """{"traits":["needs-migration-review"]}"""
            createItem("Root With Traits", properties = traitProperties)
            createItem("Root Without Traits")

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("overview")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            val withTraitsRoot =
                items.first {
                    it.jsonObject["title"]!!.jsonPrimitive.content == "Root With Traits"
                }.jsonObject

            // traits array should be present and contain the expected value
            val traits = withTraitsRoot["traits"]
            assertNotNull(traits, "Root item should include traits array when properties has traits")
            val traitsArray = traits!!.jsonArray
            assertEquals(1, traitsArray.size)
            assertEquals("needs-migration-review", traitsArray[0].jsonPrimitive.content)

            val withoutTraitsRoot =
                items.first {
                    it.jsonObject["title"]!!.jsonPrimitive.content == "Root Without Traits"
                }.jsonObject

            // traits key should be absent when item has no traits
            assertFalse(
                withoutTraitsRoot.containsKey("traits"),
                "Root item should not include traits key when properties has no traits"
            )
        }

    // ──────────────────────────────────────────────
    // childCounts in includeChildren — 3-level hierarchy
    // ──────────────────────────────────────────────

    @Test
    fun `global overview child has correct childCounts from 3-level hierarchy`(): Unit =
        runBlocking {
            // 3-level hierarchy: root -> child -> 2 grandchildren
            val rootId = createItem("Root Three Level")
            val childId = createItem("Middle Child", parentId = rootId, role = "queue")
            // Create two grandchildren under child — one queue, one work
            createItem("Grandchild Queue", parentId = childId, role = "queue")
            val grandchildWorkId = createItem("Grandchild Work", parentId = childId, role = "queue")

            // Advance grandchild to work role using AdvanceItemTool
            advanceTool.execute(
                buildJsonObject {
                    put(
                        "transitions",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(grandchildWorkId))
                                    put("trigger", JsonPrimitive("start"))
                                }
                            )
                        )
                    )
                },
                context
            )

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("overview"),
                        "includeChildren" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val items = data["items"]!!.jsonArray

            val rootItem =
                items.first {
                    it.jsonObject["title"]!!.jsonPrimitive.content == "Root Three Level"
                }.jsonObject

            // Root's children array should include Middle Child
            val children = rootItem["children"]!!.jsonArray
            assertEquals(1, children.size)

            val middleChild = children[0].jsonObject
            assertEquals(childId, middleChild["id"]!!.jsonPrimitive.content)

            // Middle Child's childCounts should reflect 1 queue + 1 work grandchild
            val childCounts = middleChild["childCounts"]
            assertNotNull(childCounts, "Middle child should have childCounts")
            val childCountsObj = childCounts!!.jsonObject
            assertEquals(1, childCountsObj["queue"]!!.jsonPrimitive.int, "Should have 1 grandchild in queue role")
            assertEquals(1, childCountsObj["work"]!!.jsonPrimitive.int, "Should have 1 grandchild in work role")
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
