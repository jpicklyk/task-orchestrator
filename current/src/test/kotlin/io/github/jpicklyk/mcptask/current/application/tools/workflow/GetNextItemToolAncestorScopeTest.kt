package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
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
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Tests for the `ancestorId` scope parameter on [GetNextItemTool] (T2.3).
 *
 * Covers both selection paths: the default recommender path (`NextItemRecommender.Criteria.ancestorIds`
 * -> `findClaimable(rootIds=...)`) and the `includeClaimed=true` path
 * (`findForNextItem(rootIds=...)`).
 */
class GetNextItemToolAncestorScopeTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetNextItemTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_ancestor_scope_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetNextItemTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        claimed: Boolean = false,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        val now = Instant.now()
        val item =
            if (claimed) {
                WorkItem(
                    parentId = parentId,
                    title = title,
                    role = role,
                    priority = priority,
                    depth = depth,
                    claimedBy = "agent-1",
                    claimedAt = now,
                    originalClaimedAt = now,
                    claimExpiresAt = now.plusSeconds(900),
                )
            } else {
                WorkItem(parentId = parentId, title = title, role = role, priority = priority, depth = depth)
            }
        return (context.workItemRepository().create(item) as Result.Success).data
    }

    private fun extractRecommendations(result: JsonElement): JsonArray {
        val data = (result as JsonObject)["data"] as JsonObject
        return data["recommendations"]!!.jsonArray
    }

    private fun extractTotal(result: JsonElement): Int = ((result as JsonObject)["data"] as JsonObject)["total"]!!.jsonPrimitive.int

    @Test
    fun `get_next_item without ancestorId is unscoped across all trees`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createItem("Queue child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createItem("Queue child of R2", parentId = r2.id)

            val result = tool.execute(params("limit" to JsonPrimitive(20)), context)
            // 2 roots + 2 children, all QUEUE by default
            assertEquals(4, extractTotal(result))
        }

    @Test
    fun `get_next_item scoped to R1 excludes R2's subtree (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val c1 = createItem("Queue child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createItem("Queue child of R2", parentId = r2.id)

            val result =
                tool.execute(
                    params(
                        "ancestorId" to JsonPrimitive(r1.id.toString()),
                        "limit" to JsonPrimitive(20)
                    ),
                    context
                )

            assertEquals(2, extractTotal(result), "R1 root + its child = 2")
            val ids = extractRecommendations(result).map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(r1.id.toString() in ids)
            assertTrue(c1.id.toString() in ids)
        }

    @Test
    fun `get_next_item ancestorId resolves via hex prefix`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createItem("Queue child of R1", parentId = r1.id)
            val r2 = createItem("Root 2")
            createItem("Queue child of R2", parentId = r2.id)

            val prefix =
                r1.id
                    .toString()
                    .replace("-", "")
                    .take(8)
            val result =
                tool.execute(
                    params(
                        "ancestorId" to JsonPrimitive(prefix),
                        "limit" to JsonPrimitive(20)
                    ),
                    context
                )

            assertEquals(2, extractTotal(result))
        }

    @Test
    fun `get_next_item scoped to a childless leaf with no QUEUE items returns empty`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val leafWork = createItem("Work leaf (not queue)", parentId = r1.id, role = Role.WORK)
            createItem("Queue sibling", parentId = r1.id)

            val result =
                tool.execute(
                    params(
                        "ancestorId" to JsonPrimitive(leafWork.id.toString()),
                        "limit" to JsonPrimitive(20)
                    ),
                    context
                )

            assertEquals(0, extractTotal(result), "Leaf's own subtree has no QUEUE-role items")
        }

    @Test
    fun `get_next_item includeClaimed=true honors ancestorId scoping`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val claimedInR1 = createItem("Claimed child of R1", parentId = r1.id, claimed = true)
            val r2 = createItem("Root 2")
            createItem("Claimed child of R2", parentId = r2.id, claimed = true)

            val result =
                tool.execute(
                    params(
                        "ancestorId" to JsonPrimitive(r1.id.toString()),
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(20)
                    ),
                    context
                )

            // R1 root (unclaimed) + R1's claimed child = 2; R2's subtree must be excluded
            assertEquals(2, extractTotal(result))
            val ids = extractRecommendations(result).map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(claimedInR1.id.toString() in ids)
        }
}
