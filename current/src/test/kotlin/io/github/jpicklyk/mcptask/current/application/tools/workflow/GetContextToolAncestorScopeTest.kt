package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
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
 * Tests for the `ancestorId` scope parameter on [GetContextTool]'s health-check and
 * session-resume modes (T2.3). Uses a real H2 in-memory DB (via [DefaultRepositoryProvider])
 * since subtree resolution requires actual parent-child rows.
 *
 * Item mode ignores `ancestorId` entirely (documented in the field's parameterSchema
 * description) — not covered here since there is no scoping behavior to assert.
 */
class GetContextToolAncestorScopeTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetContextTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_ancestor_scope_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetContextTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
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
                    depth = depth,
                    claimedBy = "agent-1",
                    claimedAt = now,
                    originalClaimedAt = now,
                    claimExpiresAt = now.plusSeconds(900),
                )
            } else {
                WorkItem(parentId = parentId, title = title, role = role, depth = depth)
            }
        return (context.workItemRepository().create(item) as Result.Success).data
    }

    private fun activeItemIds(result: JsonElement): Set<String> {
        val data = (result as JsonObject)["data"] as JsonObject
        return data["activeItems"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
    }

    // ──────────────────────────────────────────────
    // Health-check mode
    // ──────────────────────────────────────────────

    @Test
    fun `health-check without ancestorId is unscoped across all trees`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createItem("Work child of R1", parentId = r1.id, role = Role.WORK)
            val r2 = createItem("Root 2")
            createItem("Work child of R2", parentId = r2.id, role = Role.WORK)

            val result = tool.execute(params("mode" to JsonPrimitive("health-check")), context)
            assertEquals(2, activeItemIds(result).size)
        }

    @Test
    fun `health-check scoped to R1 excludes R2's active items (sibling isolation)`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val workR1 = createItem("Work child of R1", parentId = r1.id, role = Role.WORK)
            val r2 = createItem("Root 2")
            createItem("Work child of R2", parentId = r2.id, role = Role.WORK)

            val result =
                tool.execute(
                    params(
                        "mode" to JsonPrimitive("health-check"),
                        "ancestorId" to JsonPrimitive(r1.id.toString())
                    ),
                    context
                )

            val ids = activeItemIds(result)
            assertEquals(1, ids.size)
            assertTrue(workR1.id.toString() in ids)
        }

    @Test
    fun `health-check claimSummary is scoped to ancestorId's subtree`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            createItem("Claimed child of R1", parentId = r1.id, claimed = true)
            val r2 = createItem("Root 2")
            createItem("Claimed child of R2", parentId = r2.id, claimed = true)

            val result =
                tool.execute(
                    params(
                        "mode" to JsonPrimitive("health-check"),
                        "ancestorId" to JsonPrimitive(r1.id.toString())
                    ),
                    context
                )

            val claimSummary = ((result as JsonObject)["data"] as JsonObject)["claimSummary"]!!.jsonObject
            assertEquals(1, claimSummary["active"]!!.jsonPrimitive.int, "Only R1's claimed child should count")
        }

    @Test
    fun `health-check scoped to a childless leaf returns no active items`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val leafQueue = createItem("Queue leaf (not active)", parentId = r1.id)
            createItem("Work sibling", parentId = r1.id, role = Role.WORK)

            val result =
                tool.execute(
                    params(
                        "mode" to JsonPrimitive("health-check"),
                        "ancestorId" to JsonPrimitive(leafQueue.id.toString())
                    ),
                    context
                )

            assertTrue(activeItemIds(result).isEmpty(), "Leaf's own subtree has no WORK/REVIEW items")
        }

    // ──────────────────────────────────────────────
    // Session-resume mode
    // ──────────────────────────────────────────────

    @Test
    fun `session-resume scoped to R1 excludes R2's active items`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val workR1 = createItem("Work child of R1", parentId = r1.id, role = Role.WORK)
            val r2 = createItem("Root 2")
            createItem("Work child of R2", parentId = r2.id, role = Role.WORK)

            val result =
                tool.execute(
                    params(
                        "mode" to JsonPrimitive("session-resume"),
                        "since" to JsonPrimitive(Instant.now().minusSeconds(3600).toString()),
                        "ancestorId" to JsonPrimitive(r1.id.toString())
                    ),
                    context
                )

            val ids = activeItemIds(result)
            assertEquals(1, ids.size)
            assertTrue(workR1.id.toString() in ids)
        }

    @Test
    fun `session-resume recentTransitions are NOT scoped by ancestorId (documented limitation)`(): Unit =
        runBlocking {
            val r1 = createItem("Root 1")
            val r2 = createItem("Root 2")
            val itemInR2 = createItem("Item in R2", parentId = r2.id, role = Role.WORK)

            // Record a transition on an item outside R1's subtree.
            context.roleTransitionRepository().create(
                RoleTransition(
                    itemId = itemInR2.id,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start"
                )
            )

            val result =
                tool.execute(
                    params(
                        "mode" to JsonPrimitive("session-resume"),
                        "since" to JsonPrimitive(Instant.now().minusSeconds(3600).toString()),
                        "ancestorId" to JsonPrimitive(r1.id.toString())
                    ),
                    context
                )

            val data = (result as JsonObject)["data"] as JsonObject
            val transitionItemIds =
                data["recentTransitions"]!!.jsonArray.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()
            assertTrue(
                itemInR2.id.toString() in transitionItemIds,
                "recentTransitions must remain unscoped even when ancestorId is set (documented limitation)"
            )
        }
}
