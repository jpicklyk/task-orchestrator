package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Priority
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

class QueryDependenciesToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryDependenciesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryDependenciesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

    /** Helper to create a WorkItem directly via the repository and return its UUID. */
    private suspend fun createItem(
        title: String,
        priority: Priority = Priority.MEDIUM
    ): UUID {
        val item = WorkItem(title = title, priority = priority)
        val result = context.workItemRepository().create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data.id
    }

    /** Helper to create a dependency directly via the repository. */
    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val dep = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = type, unblockAt = unblockAt)
        return context.dependencyRepository().create(dep)
    }

    // ──────────────────────────────────────────────
    // Basic query tests
    // ──────────────────────────────────────────────

    @Test
    fun `query all deps for item with both incoming and outgoing`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b) // A blocks B
        createDependency(b, c) // B blocks C

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray
        assertEquals(2, deps.size)
    }

    @Test
    fun `query incoming only`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b) // A blocks B (incoming to B)
        createDependency(b, c) // B blocks C (outgoing from B)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "direction" to JsonPrimitive("incoming")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray
        assertEquals(1, deps.size)
        // The incoming dep is A->B
        assertEquals(a.toString(), deps[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(b.toString(), deps[0].jsonObject["toItemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `query outgoing only`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b) // A blocks B (incoming to B)
        createDependency(b, c) // B blocks C (outgoing from B)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "direction" to JsonPrimitive("outgoing")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray
        assertEquals(1, deps.size)
        // The outgoing dep is B->C
        assertEquals(b.toString(), deps[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(c.toString(), deps[0].jsonObject["toItemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `filter by type BLOCKS only`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b, DependencyType.BLOCKS)
        createDependency(c, b, DependencyType.RELATES_TO)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "type" to JsonPrimitive("BLOCKS")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray
        assertEquals(1, deps.size)
        assertEquals("BLOCKS", deps[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no dependencies returns empty result`(): Unit = runBlocking {
        val a = createItem("Lonely Item")

        val result = tool.execute(
            params("itemId" to JsonPrimitive(a.toString())),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray
        assertEquals(0, deps.size)

        val counts = data["counts"] as JsonObject
        assertEquals(0, counts["incoming"]!!.jsonPrimitive.int)
        assertEquals(0, counts["outgoing"]!!.jsonPrimitive.int)
        assertEquals(0, counts["relatesTo"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // includeItemInfo tests
    // ──────────────────────────────────────────────

    @Test
    fun `includeItemInfo true includes item details`(): Unit = runBlocking {
        val a = createItem("Alpha", Priority.HIGH)
        val b = createItem("Beta", Priority.LOW)

        createDependency(a, b)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "includeItemInfo" to JsonPrimitive(true)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        // fromItem should have Alpha's details
        val fromItem = dep["fromItem"] as JsonObject
        assertEquals("Alpha", fromItem["title"]!!.jsonPrimitive.content)
        assertEquals("queue", fromItem["role"]!!.jsonPrimitive.content)
        assertEquals("high", fromItem["priority"]!!.jsonPrimitive.content)

        // toItem should have Beta's details
        val toItem = dep["toItem"] as JsonObject
        assertEquals("Beta", toItem["title"]!!.jsonPrimitive.content)
        assertEquals("low", toItem["priority"]!!.jsonPrimitive.content)
    }

    @Test
    fun `includeItemInfo false omits item details`(): Unit = runBlocking {
        val a = createItem("Alpha")
        val b = createItem("Beta")

        createDependency(a, b)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "includeItemInfo" to JsonPrimitive(false)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        assertNull(dep["fromItem"])
        assertNull(dep["toItem"])
    }

    @Test
    fun `includeItemInfo defaults to false when not specified`(): Unit = runBlocking {
        val a = createItem("Alpha")
        val b = createItem("Beta")

        createDependency(a, b)

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        assertNull(dep["fromItem"])
        assertNull(dep["toItem"])
    }

    // ──────────────────────────────────────────────
    // Counts breakdown tests
    // ──────────────────────────────────────────────

    @Test
    fun `counts correctly separate incoming outgoing and relatesTo`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")
        val d = createItem("Item D")
        val e = createItem("Item E")

        createDependency(a, b, DependencyType.BLOCKS)     // incoming to B
        createDependency(c, b, DependencyType.BLOCKS)     // incoming to B
        createDependency(b, d, DependencyType.BLOCKS)     // outgoing from B
        createDependency(e, b, DependencyType.RELATES_TO) // relates-to for B (separate item to avoid cycle detection)

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val counts = data["counts"] as JsonObject

        assertEquals(2, counts["incoming"]!!.jsonPrimitive.int)
        assertEquals(1, counts["outgoing"]!!.jsonPrimitive.int)
        assertEquals(1, counts["relatesTo"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // effectiveUnblockRole tests
    // ──────────────────────────────────────────────

    @Test
    fun `BLOCKS dep includes effectiveUnblockRole terminal by default`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")

        createDependency(a, b, DependencyType.BLOCKS) // no explicit unblockAt

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        assertEquals("terminal", dep["effectiveUnblockRole"]!!.jsonPrimitive.content)
        // unblockAt should NOT be present since it's null
        assertNull(dep["unblockAt"])
    }

    @Test
    fun `BLOCKS dep with explicit unblockAt includes both fields`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")

        createDependency(a, b, DependencyType.BLOCKS, unblockAt = "work")

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        assertEquals("work", dep["unblockAt"]!!.jsonPrimitive.content)
        assertEquals("work", dep["effectiveUnblockRole"]!!.jsonPrimitive.content)
    }

    @Test
    fun `RELATES_TO dep has no effectiveUnblockRole`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")

        createDependency(a, b, DependencyType.RELATES_TO)

        val result = tool.execute(
            params("itemId" to JsonPrimitive(b.toString())),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val dep = data["dependencies"]!!.jsonArray[0].jsonObject

        assertEquals("RELATES_TO", dep["type"]!!.jsonPrimitive.content)
        assertNull(dep["effectiveUnblockRole"])
        assertNull(dep["unblockAt"])
    }

    // ──────────────────────────────────────────────
    // Graph traversal tests (neighborsOnly=false)
    // ──────────────────────────────────────────────

    @Test
    fun `neighborsOnly false returns graph with chain and depth for linear chain`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b) // A blocks B
        createDependency(b, c) // B blocks C

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "neighborsOnly" to JsonPrimitive(false)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val graph = data["graph"] as JsonObject

        val chain = graph["chain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, chain.size)
        // Topological order should be A, B, C
        assertEquals(a.toString(), chain[0])
        assertEquals(b.toString(), chain[1])
        assertEquals(c.toString(), chain[2])

        assertEquals(2, graph["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `neighborsOnly true omits graph`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")

        createDependency(a, b)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "neighborsOnly" to JsonPrimitive(true)
            ),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        assertNull(data["graph"])
    }

    @Test
    fun `graph traversal with single node returns depth 0`(): Unit = runBlocking {
        val a = createItem("Lonely Item")

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(a.toString()),
                "neighborsOnly" to JsonPrimitive(false)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val graph = data["graph"] as JsonObject

        val chain = graph["chain"]!!.jsonArray
        assertEquals(1, chain.size)
        assertEquals(a.toString(), chain[0].jsonPrimitive.content)
        assertEquals(0, graph["depth"]!!.jsonPrimitive.int)
    }

    @Test
    fun `graph traversal with fan-out pattern`(): Unit = runBlocking {
        val root = createItem("Root")
        val child1 = createItem("Child 1")
        val child2 = createItem("Child 2")
        val child3 = createItem("Child 3")

        createDependency(root, child1)
        createDependency(root, child2)
        createDependency(root, child3)

        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(root.toString()),
                "neighborsOnly" to JsonPrimitive(false)
            ),
            context
        ) as JsonObject

        val data = result["data"] as JsonObject
        val graph = data["graph"] as JsonObject

        val chain = graph["chain"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(4, chain.size)
        // Root should be first in topological order
        assertEquals(root.toString(), chain[0])
        // Depth should be 1 (root -> children)
        assertEquals(1, graph["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Linear chain: query middle item
    // ──────────────────────────────────────────────

    @Test
    fun `linear chain A-B-C query B gets both directions`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b) // A blocks B
        createDependency(b, c) // B blocks C

        // Query all directions for B
        val result = tool.execute(
            params(
                "itemId" to JsonPrimitive(b.toString()),
                "direction" to JsonPrimitive("all")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val deps = data["dependencies"]!!.jsonArray

        assertEquals(2, deps.size)

        // Verify we have both incoming (A->B) and outgoing (B->C)
        val fromIds = deps.map { it.jsonObject["fromItemId"]!!.jsonPrimitive.content }.toSet()
        val toIds = deps.map { it.jsonObject["toItemId"]!!.jsonPrimitive.content }.toSet()

        assertTrue(fromIds.contains(a.toString()))
        assertTrue(fromIds.contains(b.toString()))
        assertTrue(toIds.contains(b.toString()))
        assertTrue(toIds.contains(c.toString()))
    }

    // ──────────────────────────────────────────────
    // Validation tests
    // ──────────────────────────────────────────────

    @Test
    fun `missing itemId throws validation error`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params())
        }
    }

    @Test
    fun `invalid itemId format throws validation error`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("itemId" to JsonPrimitive("not-a-uuid"))
            )
        }
    }

    @Test
    fun `invalid direction throws validation error`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "direction" to JsonPrimitive("sideways")
                )
            )
        }
    }

    @Test
    fun `invalid type throws validation error`(): Unit {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "type" to JsonPrimitive("INVALID_TYPE")
                )
            )
        }
    }

    @Test
    fun `valid params pass validation`(): Unit {
        // Should not throw
        tool.validateParams(
            params(
                "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                "direction" to JsonPrimitive("incoming"),
                "type" to JsonPrimitive("BLOCKS"),
                "includeItemInfo" to JsonPrimitive(true),
                "neighborsOnly" to JsonPrimitive(false)
            )
        )
    }

    // ──────────────────────────────────────────────
    // User summary tests
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary returns error message on error`(): Unit {
        val result = buildJsonObject {
            put("success", JsonPrimitive(false))
        }
        assertEquals("Dependency query failed", tool.userSummary(params(), result, isError = true))
    }

    @Test
    fun `userSummary returns correct count`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")
        val c = createItem("Item C")

        createDependency(a, b)
        createDependency(b, c)

        val inputParams = params("itemId" to JsonPrimitive(b.toString()))
        val result = tool.execute(inputParams, context) as JsonObject

        val summary = tool.userSummary(inputParams, result, isError = false)
        assertEquals("Found 2 dependencies", summary)
    }

    @Test
    fun `userSummary uses singular for single dependency`(): Unit = runBlocking {
        val a = createItem("Item A")
        val b = createItem("Item B")

        createDependency(a, b)

        val inputParams = params(
            "itemId" to JsonPrimitive(b.toString()),
            "direction" to JsonPrimitive("incoming")
        )
        val result = tool.execute(inputParams, context) as JsonObject

        val summary = tool.userSummary(inputParams, result, isError = false)
        assertEquals("Found 1 dependency", summary)
    }
}
