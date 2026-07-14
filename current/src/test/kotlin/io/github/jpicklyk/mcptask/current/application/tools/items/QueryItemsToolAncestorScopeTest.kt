package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
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
 * Tests for the `ancestorId` scope parameter on [QueryItemsTool]'s list-mode `search` (T2.3).
 *
 * `ancestorId` routes list-mode queries through the T2.2 scoped repository variants
 * (`findInScope`/`countInScope`) instead of `findByFilters`/`countByFilters`. Coverage mirrors
 * the repository-level scoped-variant tests: sibling-root isolation, omitted = unscoped parity,
 * hex-prefix resolution, and empty-subtree scoping.
 */
class QueryItemsToolAncestorScopeTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryItemsTool
    private lateinit var manageTool: ManageItemsTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_ancestor_scope_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryItemsTool()
        manageTool = ManageItemsTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItemId(
        title: String,
        parentId: UUID? = null
    ): UUID {
        val itemObj =
            buildJsonObject {
                put("title", JsonPrimitive(title))
                parentId?.let { put("parentId", JsonPrimitive(it.toString())) }
            }
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("create"),
                    "items" to JsonArray(listOf(itemObj))
                ),
                context
            ) as JsonObject
        val idStr =
            (result["data"] as JsonObject)["items"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive.content
        return UUID.fromString(idStr)
    }

    @Test
    fun `search list mode without ancestorId is unscoped across all trees`(): Unit =
        runBlocking {
            val r1 = createItemId("Root 1")
            createItemId("Child of R1", parentId = r1)
            val r2 = createItemId("Root 2")
            createItemId("Child of R2", parentId = r2)

            val result =
                tool.execute(
                    params("operation" to JsonPrimitive("search")),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            // 2 roots + 2 children = 4 items total, unscoped
            assertEquals(4, data["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `search list mode scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = createItemId("Root 1")
            val c1 = createItemId("Child of R1", parentId = r1)
            val r2 = createItemId("Root 2")
            createItemId("Child of R2", parentId = r2)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "ancestorId" to JsonPrimitive(r1.toString())
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            // R1 root + its child = 2 items; R2's subtree must be excluded
            assertEquals(2, data["total"]!!.jsonPrimitive.int)
            val ids = data["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            assertTrue(r1.toString() in ids)
            assertTrue(c1.toString() in ids)
        }

    @Test
    fun `search list mode ancestorId resolves via hex prefix`(): Unit =
        runBlocking {
            val r1 = createItemId("Root 1")
            createItemId("Child of R1", parentId = r1)
            val r2 = createItemId("Root 2")
            createItemId("Child of R2", parentId = r2)

            val prefix = r1.toString().replace("-", "").take(8)
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "ancestorId" to JsonPrimitive(prefix)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `search list mode scoped to a childless leaf returns only that leaf`(): Unit =
        runBlocking {
            val r1 = createItemId("Root 1")
            val leaf = createItemId("Leaf with no children", parentId = r1)
            createItemId("Sibling of leaf", parentId = r1)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "ancestorId" to JsonPrimitive(leaf.toString())
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["total"]!!.jsonPrimitive.int, "Leaf's own subtree is just itself")
            assertEquals(
                leaf.toString(),
                data["items"]!!
                    .jsonArray[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `search list mode ancestorId composes with existing filters (role)`(): Unit =
        runBlocking {
            val r1 = createItemId("Root 1")
            val workChild =
                run {
                    val id = createItemId("Work child", parentId = r1)
                    val item = (context.workItemRepository().getById(id) as Result.Success).data
                    context.workItemRepository().update(
                        item.copy(role = io.github.jpicklyk.mcptask.current.domain.model.Role.WORK)
                    )
                    id
                }
            createItemId("Queue child", parentId = r1) // stays QUEUE
            val r2 = createItemId("Root 2")
            val r2WorkChild =
                run {
                    val id = createItemId("R2 work child", parentId = r2)
                    val item = (context.workItemRepository().getById(id) as Result.Success).data
                    context.workItemRepository().update(
                        item.copy(role = io.github.jpicklyk.mcptask.current.domain.model.Role.WORK)
                    )
                    id
                }

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "ancestorId" to JsonPrimitive(r1.toString()),
                        "role" to JsonPrimitive("work")
                    ),
                    context
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["total"]!!.jsonPrimitive.int, "Only R1's WORK child should match")
            val ids = data["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
            assertTrue(workChild.toString() in ids)
            assertTrue(r2WorkChild.toString() !in ids)
        }
}
