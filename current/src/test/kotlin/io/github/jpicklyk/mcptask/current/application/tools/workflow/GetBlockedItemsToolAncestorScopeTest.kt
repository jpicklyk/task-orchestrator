package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
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

/**
 * Tests for the `ancestorId` scope parameter on [GetBlockedItemsTool] (T2.3).
 *
 * `ancestorId` routes candidate-role fetching through `findInScope(rootIds=...)` (composing
 * with the pre-existing `parentId` filter) instead of `findByFilters`/`findByRole`.
 */
class GetBlockedItemsToolAncestorScopeTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetBlockedItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_ancestor_scope_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetBlockedItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        val item = WorkItem(parentId = parentId, title = title, role = role, depth = depth)
        return (context.workItemRepository().create(item) as Result.Success).data
    }

    /** Directly creates an explicitly BLOCKED item (bypassing the normal block/hold trigger). */
    private suspend fun createBlockedItem(
        title: String,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        val item =
            WorkItem(
                title = title,
                role = Role.BLOCKED,
                previousRole = Role.WORK,
                parentId = parentId,
                depth = depth
            )
        return (context.workItemRepository().create(item) as Result.Success).data
    }

    private fun extractBlockedItems(result: JsonElement): JsonArray {
        val data = (result as JsonObject)["data"] as JsonObject
        return data["blockedItems"]!!.jsonArray
    }

    private fun extractTotal(result: JsonElement): Int = ((result as JsonObject)["data"] as JsonObject)["total"]!!.jsonPrimitive.int

    @Test
    fun `get_blocked_items without ancestorId is unscoped across all trees`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createBlockedItem("Blocked child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createBlockedItem("Blocked child of R2", parentId = r2.id)

            val result = tool.execute(params(), context)
            assertEquals(2, extractTotal(result))
        }

    @Test
    fun `get_blocked_items scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val blockedR1 = createBlockedItem("Blocked child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createBlockedItem("Blocked child of R2", parentId = r2.id)

            val result =
                tool.execute(
                    params("ancestorId" to JsonPrimitive(r1.id.toString())),
                    context
                )

            assertEquals(1, extractTotal(result))
            val ids = extractBlockedItems(result).map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(blockedR1.id.toString() in ids)
        }

    @Test
    fun `get_blocked_items ancestorId resolves via hex prefix`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createBlockedItem("Blocked child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createBlockedItem("Blocked child of R2", parentId = r2.id)

            val prefix =
                r1.id
                    .toString()
                    .replace("-", "")
                    .take(8)
            val result = tool.execute(params("ancestorId" to JsonPrimitive(prefix)), context)

            assertEquals(1, extractTotal(result))
        }

    @Test
    fun `get_blocked_items scoped to a childless leaf returns empty`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val leaf = createItem("Leaf with no children", parentId = r1.id)
            createBlockedItem("Blocked sibling", parentId = r1.id)

            val result = tool.execute(params("ancestorId" to JsonPrimitive(leaf.id.toString())), context)

            assertEquals(0, extractTotal(result))
        }

    @Test
    fun `get_blocked_items ancestorId composes with parentId`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val mid = createItem("Mid of R1", parentId = r1.id)
            val blockedUnderMid = createBlockedItem("Blocked under mid", parentId = mid.id, depth = 2)
            createBlockedItem("Blocked directly under R1", parentId = r1.id)

            val result =
                tool.execute(
                    params(
                        "ancestorId" to JsonPrimitive(r1.id.toString()),
                        "parentId" to JsonPrimitive(mid.id.toString())
                    ),
                    context
                )

            assertEquals(1, extractTotal(result), "Only the blocked item directly under 'mid' should match")
            val ids = extractBlockedItems(result).map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(blockedUnderMid.id.toString() in ids)
        }
}
