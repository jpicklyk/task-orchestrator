package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
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

class GetNextItemToolTest {
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: GetNextItemTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = GetNextItemTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /**
     * Helper to create a WorkItem directly via the repository.
     */
    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        complexity: Int = 5,
        summary: String = "",
        tags: String? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        val item =
            WorkItem(
                parentId = parentId,
                title = title,
                role = role,
                priority = priority,
                complexity = complexity,
                summary = summary,
                tags = tags,
                depth = depth
            )
        val result = context.workItemRepository().create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data
    }

    /**
     * Helper to create a dependency between two items.
     */
    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val dep =
            Dependency(
                fromItemId = fromItemId,
                toItemId = toItemId,
                type = type,
                unblockAt = unblockAt
            )
        return context.dependencyRepository().create(dep)
    }

    private fun extractRecommendations(result: JsonElement): JsonArray {
        val obj = result as JsonObject
        val data = obj["data"] as JsonObject
        return data["recommendations"]!!.jsonArray
    }

    private fun extractTotal(result: JsonElement): Int {
        val obj = result as JsonObject
        val data = obj["data"] as JsonObject
        return data["total"]!!.jsonPrimitive.int
    }

    private fun isSuccess(result: JsonElement): Boolean = (result as JsonObject)["success"]!!.jsonPrimitive.boolean

    // ──────────────────────────────────────────────
    // Basic recommendation tests
    // ──────────────────────────────────────────────

    @Test
    fun `no QUEUE items returns empty recommendations`(): Unit =
        runBlocking {
            // Create items in non-QUEUE roles
            createItem("Work Item", role = Role.WORK)
            createItem("Terminal Item", role = Role.TERMINAL)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(0, extractTotal(result))
            assertTrue(extractRecommendations(result).isEmpty())
        }

    @Test
    fun `single QUEUE item is recommended`(): Unit =
        runBlocking {
            val item = createItem("Only Item")

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(1, extractTotal(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals(item.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("Only Item", recs[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals("queue", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `multiple QUEUE items sorted by priority then complexity`(): Unit =
        runBlocking {
            val lowPri = createItem("Low Priority", priority = Priority.LOW, complexity = 2)
            val highPriHigh = createItem("High Priority High Complexity", priority = Priority.HIGH, complexity = 8)
            val highPriLow = createItem("High Priority Low Complexity", priority = Priority.HIGH, complexity = 2)
            val medPri = createItem("Medium Priority", priority = Priority.MEDIUM, complexity = 5)

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(10)),
                    context
                )

            assertTrue(isSuccess(result))
            assertEquals(4, extractTotal(result))
            val recs = extractRecommendations(result)

            // Order: HIGH/complexity2, HIGH/complexity8, MEDIUM/complexity5, LOW/complexity2
            assertEquals(highPriLow.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals(highPriHigh.id.toString(), recs[1].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals(medPri.id.toString(), recs[2].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals(lowPri.id.toString(), recs[3].jsonObject["itemId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `items in WORK role are not recommended`(): Unit =
        runBlocking {
            createItem("Work Item", role = Role.WORK)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(0, extractTotal(result))
        }

    @Test
    fun `items in REVIEW role are not recommended`(): Unit =
        runBlocking {
            createItem("Review Item", role = Role.REVIEW)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(0, extractTotal(result))
        }

    @Test
    fun `items in TERMINAL role are not recommended`(): Unit =
        runBlocking {
            createItem("Done Item", role = Role.TERMINAL)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(0, extractTotal(result))
        }

    // ──────────────────────────────────────────────
    // Dependency blocking tests
    // ──────────────────────────────────────────────

    @Test
    fun `QUEUE item blocked by BLOCKS dependency is excluded`(): Unit =
        runBlocking {
            val blocker = createItem("Blocker", role = Role.QUEUE)
            val blocked = createItem("Blocked Item")

            // blocker BLOCKS blocked (blocker.id -> blocked.id)
            createDependency(fromItemId = blocker.id, toItemId = blocked.id, type = DependencyType.BLOCKS)

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(10)),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            // Only the blocker should be recommended (it's not blocked by anything)
            // The blocked item should be excluded because blocker is still in QUEUE (not TERMINAL)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(blocker.id.toString()))
            assertFalse(recIds.contains(blocked.id.toString()))
        }

    @Test
    fun `QUEUE item with satisfied BLOCKS dependency is included`(): Unit =
        runBlocking {
            val blocker = createItem("Blocker at Terminal", role = Role.TERMINAL)
            val item = createItem("Ready Item")

            // blocker BLOCKS item, but blocker is TERMINAL (default threshold is terminal)
            createDependency(fromItemId = blocker.id, toItemId = item.id, type = DependencyType.BLOCKS)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(item.id.toString()))
        }

    @Test
    fun `unblockAt work with blocker at WORK role is satisfied`(): Unit =
        runBlocking {
            val blocker = createItem("Blocker at Work", role = Role.WORK)
            val item = createItem("Waiting for Work")

            // Unblock when blocker reaches WORK
            createDependency(
                fromItemId = blocker.id,
                toItemId = item.id,
                type = DependencyType.BLOCKS,
                unblockAt = "work"
            )

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(item.id.toString()))
        }

    @Test
    fun `unblockAt work with blocker at QUEUE role is unsatisfied`(): Unit =
        runBlocking {
            val blocker = createItem("Blocker at Queue", role = Role.QUEUE)
            val item = createItem("Waiting for Work Start")

            // Unblock when blocker reaches WORK, but blocker is still QUEUE
            createDependency(
                fromItemId = blocker.id,
                toItemId = item.id,
                type = DependencyType.BLOCKS,
                unblockAt = "work"
            )

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(10)),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            // blocker is in QUEUE so it would be recommended
            assertTrue(recIds.contains(blocker.id.toString()))
            // item should be blocked
            assertFalse(recIds.contains(item.id.toString()))
        }

    @Test
    fun `IS_BLOCKED_BY dependency correctly blocks item`(): Unit =
        runBlocking {
            val item = createItem("Blocked by IS_BLOCKED_BY")
            val blocker = createItem("The Blocker", role = Role.QUEUE)

            // item IS_BLOCKED_BY blocker (item.id -> blocker.id)
            createDependency(
                fromItemId = item.id,
                toItemId = blocker.id,
                type = DependencyType.IS_BLOCKED_BY
            )

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(10)),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            // blocker (QUEUE) should be recommended
            assertTrue(recIds.contains(blocker.id.toString()))
            // item should be blocked because blocker is at QUEUE, not TERMINAL
            assertFalse(recIds.contains(item.id.toString()))
        }

    @Test
    fun `RELATES_TO dependencies are ignored for blocking`(): Unit =
        runBlocking {
            val relatedItem = createItem("Related Item", role = Role.QUEUE)
            val item = createItem("My Item")

            // RELATES_TO has no blocking semantics
            createDependency(
                fromItemId = relatedItem.id,
                toItemId = item.id,
                type = DependencyType.RELATES_TO
            )

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(10)),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            // Both items should be recommended — RELATES_TO does not block
            assertTrue(recIds.contains(item.id.toString()))
            assertTrue(recIds.contains(relatedItem.id.toString()))
        }

    // ──────────────────────────────────────────────
    // Parameter tests
    // ──────────────────────────────────────────────

    @Test
    fun `parentId scoping returns only children of parent`(): Unit =
        runBlocking {
            val parent = createItem("Parent")
            val child1 = createItem("Child 1", parentId = parent.id, depth = 1)
            val child2 = createItem("Child 2", parentId = parent.id, depth = 1)
            val unrelated = createItem("Unrelated Root")

            val result =
                tool.execute(
                    params(
                        "parentId" to JsonPrimitive(parent.id.toString()),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }.toSet()

            assertTrue(recIds.contains(child1.id.toString()))
            assertTrue(recIds.contains(child2.id.toString()))
            assertFalse(recIds.contains(parent.id.toString()))
            assertFalse(recIds.contains(unrelated.id.toString()))
        }

    @Test
    fun `limit parameter restricts number of recommendations`(): Unit =
        runBlocking {
            createItem("Item 1", priority = Priority.HIGH)
            createItem("Item 2", priority = Priority.HIGH)
            createItem("Item 3", priority = Priority.MEDIUM)
            createItem("Item 4", priority = Priority.LOW)

            val result =
                tool.execute(
                    params("limit" to JsonPrimitive(2)),
                    context
                )

            assertTrue(isSuccess(result))
            assertEquals(2, extractTotal(result))
            assertEquals(2, extractRecommendations(result).size)
        }

    @Test
    fun `default limit is 1`(): Unit =
        runBlocking {
            createItem("Item A", priority = Priority.HIGH)
            createItem("Item B", priority = Priority.HIGH)
            createItem("Item C", priority = Priority.MEDIUM)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            assertEquals(1, extractTotal(result))
            assertEquals(1, extractRecommendations(result).size)
        }

    // ──────────────────────────────────────────────
    // includeDetails tests
    // ──────────────────────────────────────────────

    @Test
    fun `includeDetails true includes summary tags and parentId`(): Unit =
        runBlocking {
            val parent = createItem("Parent")
            createItem(
                "Detailed Child",
                parentId = parent.id,
                summary = "A helpful summary",
                tags = "backend,api",
                depth = 1
            )

            val result =
                tool.execute(
                    params(
                        "parentId" to JsonPrimitive(parent.id.toString()),
                        "includeDetails" to JsonPrimitive(true)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            val rec = recs[0].jsonObject

            assertEquals("A helpful summary", rec["summary"]!!.jsonPrimitive.content)
            assertEquals("backend,api", rec["tags"]!!.jsonPrimitive.content)
            assertEquals(parent.id.toString(), rec["parentId"]!!.jsonPrimitive.content)
        }

    @Test
    fun `includeDetails false omits summary tags and parentId`(): Unit =
        runBlocking {
            val parent = createItem("Parent")
            createItem(
                "Minimal Child",
                parentId = parent.id,
                summary = "A summary",
                tags = "test",
                depth = 1
            )

            val result =
                tool.execute(
                    params(
                        "parentId" to JsonPrimitive(parent.id.toString()),
                        "includeDetails" to JsonPrimitive(false)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            val rec = recs[0].jsonObject

            // These should be present
            assertNotNull(rec["itemId"])
            assertNotNull(rec["title"])
            assertNotNull(rec["role"])
            assertNotNull(rec["priority"])
            assertNotNull(rec["complexity"])

            // These should NOT be present when includeDetails=false
            assertNull(rec["summary"])
            assertNull(rec["tags"])
            assertNull(rec["parentId"])
        }

    // ──────────────────────────────────────────────
    // Validation tests
    // ──────────────────────────────────────────────

    @Test
    fun `limit below 1 fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("limit" to JsonPrimitive(0)))
        }
    }

    @Test
    fun `limit above 20 fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("limit" to JsonPrimitive(21)))
        }
    }

    @Test
    fun `invalid parentId UUID fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(params("parentId" to JsonPrimitive("not-a-uuid")))
        }
    }

    // ──────────────────────────────────────────────
    // User summary tests
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary with single recommendation shows title`(): Unit =
        runBlocking {
            createItem("Important Task", priority = Priority.HIGH)

            val result = tool.execute(params(), context)
            val summary = tool.userSummary(params(), result, isError = false)

            assertEquals("Next: Important Task", summary)
        }

    @Test
    fun `userSummary with error returns error message`() {
        val errorResult =
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", buildJsonObject { put("message", JsonPrimitive("fail")) })
            }
        val summary = tool.userSummary(params(), errorResult, isError = true)
        assertEquals("No recommendations available", summary)
    }

    // ──────────────────────────────────────────────
    // includeAncestors tests
    // ──────────────────────────────────────────────

    @Test
    fun `includeAncestors=true adds ancestors array to recommended items`(): Unit =
        runBlocking {
            // Create hierarchy: root (depth 0) -> parent (depth 1) -> child (depth 2, QUEUE)
            val root = createItem("Root", depth = 0)
            val parent = createItem("Parent", parentId = root.id, depth = 1)
            val child = createItem("Queue Child", parentId = parent.id, depth = 2)

            val result =
                tool.execute(
                    params(
                        "limit" to JsonPrimitive(10),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)

            // Child should appear in recommendations (QUEUE, no blocking deps)
            val childRec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == child.id.toString() }
            assertNotNull(childRec, "Child item should be recommended")

            val ancestors = childRec.jsonObject["ancestors"]!!.jsonArray
            assertEquals(2, ancestors.size, "Child at depth 2 should have 2 ancestors")

            // Root-first ordering
            assertEquals(root.id.toString(), ancestors[0].jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("Root", ancestors[0].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(0, ancestors[0].jsonObject["depth"]!!.jsonPrimitive.int)

            assertEquals(parent.id.toString(), ancestors[1].jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("Parent", ancestors[1].jsonObject["title"]!!.jsonPrimitive.content)
            assertEquals(1, ancestors[1].jsonObject["depth"]!!.jsonPrimitive.int)
        }

    @Test
    fun `includeAncestors=true root QUEUE item has empty ancestors array`(): Unit =
        runBlocking {
            createItem("Root Queue Item", depth = 0)

            val result =
                tool.execute(
                    params("includeAncestors" to JsonPrimitive(true)),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)

            val ancestors = recs[0].jsonObject["ancestors"]!!.jsonArray
            assertEquals(0, ancestors.size, "Root item should have empty ancestors array")
        }

    @Test
    fun `includeAncestors=false default omits ancestors key from recommendations`(): Unit =
        runBlocking {
            createItem("Root Queue Item", depth = 0)

            val result = tool.execute(params(), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)

            assertNull(recs[0].jsonObject["ancestors"], "ancestors should not be present when includeAncestors=false")
        }

    @Test
    fun `includeAncestors=true with depth-1 item has one ancestor`(): Unit =
        runBlocking {
            val parent = createItem("Parent Root", depth = 0)
            val child = createItem("Child Queue", parentId = parent.id, depth = 1)

            val result =
                tool.execute(
                    params(
                        "parentId" to JsonPrimitive(parent.id.toString()),
                        "includeAncestors" to JsonPrimitive(true)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            // Only child is a direct child of parent and in QUEUE (parent itself is also QUEUE but not a child here)
            val childRec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == child.id.toString() }
            assertNotNull(childRec)

            val ancestors = childRec.jsonObject["ancestors"]!!.jsonArray
            assertEquals(1, ancestors.size, "Depth-1 item should have exactly one ancestor")
            assertEquals(parent.id.toString(), ancestors[0].jsonObject["id"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // IS_BLOCKED_BY — satisfied dep means item IS recommended
    // ──────────────────────────────────────────────

    @Test
    fun `IS_BLOCKED_BY dep satisfied when blocker is terminal means item IS recommended`(): Unit =
        runBlocking {
            val item = createItem("Unblocked by IS_BLOCKED_BY")
            val blocker = createItem("Terminal Blocker", role = Role.TERMINAL)

            createDependency(
                fromItemId = item.id,
                toItemId = blocker.id,
                type = DependencyType.IS_BLOCKED_BY
            )

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(item.id.toString()), "Item with satisfied IS_BLOCKED_BY dep should be recommended")
        }

    // ──────────────────────────────────────────────
    // IS_BLOCKED_BY with custom unblockAt
    // ──────────────────────────────────────────────

    @Test
    fun `IS_BLOCKED_BY with unblockAt work satisfied when blocker at WORK`(): Unit =
        runBlocking {
            val item = createItem("Custom Threshold Item")
            val blocker = createItem("Blocker At Work", role = Role.WORK)

            createDependency(
                fromItemId = item.id,
                toItemId = blocker.id,
                type = DependencyType.IS_BLOCKED_BY,
                unblockAt = "work"
            )

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(item.id.toString()), "IS_BLOCKED_BY with unblockAt=work should be satisfied when blocker is at WORK")
        }

    @Test
    fun `IS_BLOCKED_BY with unblockAt work unsatisfied when blocker at QUEUE`(): Unit =
        runBlocking {
            val item = createItem("Custom Threshold Item Blocked")
            val blocker = createItem("Blocker At Queue", role = Role.QUEUE)

            createDependency(
                fromItemId = item.id,
                toItemId = blocker.id,
                type = DependencyType.IS_BLOCKED_BY,
                unblockAt = "work"
            )

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertFalse(recIds.contains(item.id.toString()), "IS_BLOCKED_BY with unblockAt=work should block when blocker is at QUEUE")
        }

    // ──────────────────────────────────────────────
    // Mixed BLOCKS + IS_BLOCKED_BY on same item
    // ──────────────────────────────────────────────

    @Test
    fun `mixed BLOCKS and IS_BLOCKED_BY both must be satisfied for item to be recommended`(): Unit =
        runBlocking {
            val item = createItem("Doubly Blocked Item")
            val blockerA = createItem("Blocker A (BLOCKS)", role = Role.TERMINAL)
            val blockerB = createItem("Blocker B (IS_BLOCKED_BY)", role = Role.QUEUE)

            // blockerA BLOCKS item (satisfied — blockerA is TERMINAL)
            createDependency(fromItemId = blockerA.id, toItemId = item.id, type = DependencyType.BLOCKS)
            // item IS_BLOCKED_BY blockerB (unsatisfied — blockerB is QUEUE)
            createDependency(fromItemId = item.id, toItemId = blockerB.id, type = DependencyType.IS_BLOCKED_BY)

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertFalse(
                recIds.contains(item.id.toString()),
                "Item should be blocked when IS_BLOCKED_BY dep is unsatisfied even if BLOCKS dep is satisfied"
            )
        }

    // ──────────────────────────────────────────────
    // role parameter tests
    // ──────────────────────────────────────────────

    @Test
    fun `default role is queue — WORK items not returned without role param`(): Unit =
        runBlocking {
            createItem("Queue Item", role = Role.QUEUE)
            createItem("Work Item", role = Role.WORK)

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals("queue", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `role=queue returns QUEUE items`(): Unit =
        runBlocking {
            val item = createItem("Queue Only", role = Role.QUEUE)
            createItem("Work Item", role = Role.WORK)

            val result = tool.execute(params("role" to JsonPrimitive("queue"), "limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(item.id.toString()))
        }

    @Test
    fun `role=work returns WORK items only`(): Unit =
        runBlocking {
            createItem("Queue Item", role = Role.QUEUE)
            val workItem = createItem("Work Item", role = Role.WORK)

            val result = tool.execute(params("role" to JsonPrimitive("work"), "limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals(workItem.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("work", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `role=review returns REVIEW items only`(): Unit =
        runBlocking {
            createItem("Queue Item", role = Role.QUEUE)
            val reviewItem = createItem("Review Item", role = Role.REVIEW)

            val result = tool.execute(params("role" to JsonPrimitive("review"), "limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals(reviewItem.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("review", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `role=blocked returns BLOCKED items only`(): Unit =
        runBlocking {
            createItem("Queue Item", role = Role.QUEUE)
            val blockedItem = createItem("Blocked Item", role = Role.BLOCKED)

            val result = tool.execute(params("role" to JsonPrimitive("blocked"), "limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals(blockedItem.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("blocked", recs[0].jsonObject["role"]!!.jsonPrimitive.content)
        }

    @Test
    fun `invalid role value fails validation`() {
        assertFailsWith<io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException> {
            tool.validateParams(params("role" to JsonPrimitive("terminal")))
        }
    }

    @Test
    fun `invalid role arbitrary string fails validation`() {
        assertFailsWith<io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException> {
            tool.validateParams(params("role" to JsonPrimitive("done")))
        }
    }

    // ──────────────────────────────────────────────
    // includeClaimed / claim filtering tests
    // ──────────────────────────────────────────────

    /**
     * Create a work item with an active claim (non-expired) directly via the repository.
     * claimExpiresAt is set 1 hour in the future so it is definitely active.
     */
    private suspend fun createClaimedItem(
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem {
        // Capture Instant.now() ONCE: separate calls drift by microseconds on high-resolution
        // clocks (Linux CI), causing originalClaimedAt > claimedAt and tripping H2's
        // `originalClaimedAt <= claimedAt` invariant.
        val now = java.time.Instant.now()
        val item =
            WorkItem(
                title = title,
                role = role,
                claimedBy = "test-agent",
                claimedAt = now.minusSeconds(60),
                claimExpiresAt = now.plusSeconds(3600),
                originalClaimedAt = now.minusSeconds(60)
            )
        val result = context.workItemRepository().create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data
    }

    /**
     * Create a work item with an expired claim (claimExpiresAt in the past).
     */
    private suspend fun createExpiredClaimItem(
        title: String,
        role: Role = Role.QUEUE
    ): WorkItem {
        // Capture Instant.now() once — same clock-drift rationale as createClaimedItem.
        val now = java.time.Instant.now()
        val item =
            WorkItem(
                title = title,
                role = role,
                claimedBy = "test-agent",
                claimedAt = now.minusSeconds(7200),
                claimExpiresAt = now.minusSeconds(3600),
                originalClaimedAt = now.minusSeconds(7200)
            )
        val result = context.workItemRepository().create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data
    }

    @Test
    fun `actively claimed items are filtered out by default`(): Unit =
        runBlocking {
            val unclaimed = createItem("Unclaimed Item", role = Role.QUEUE)
            createClaimedItem("Claimed Item", role = Role.QUEUE)

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(unclaimed.id.toString()), "Unclaimed item should be recommended")
            assertTrue(recIds.none { id -> id != unclaimed.id.toString() }, "Claimed item should be filtered")
        }

    @Test
    fun `expired claimed items are treated as unclaimed and returned`(): Unit =
        runBlocking {
            val expiredClaim = createExpiredClaimItem("Expired Claim Item", role = Role.QUEUE)

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(expiredClaim.id.toString()), "Item with expired claim should be recommended")
        }

    @Test
    fun `includeClaimed=true returns claimed items alongside unclaimed`(): Unit =
        runBlocking {
            val unclaimed = createItem("Unclaimed Item", role = Role.QUEUE)
            val claimed = createClaimedItem("Claimed Item", role = Role.QUEUE)

            val result =
                tool.execute(
                    params(
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val recIds = recs.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
            assertTrue(recIds.contains(unclaimed.id.toString()), "Unclaimed item should be included")
            assertTrue(recIds.contains(claimed.id.toString()), "Claimed item should be included when includeClaimed=true")
        }

    @Test
    fun `includeClaimed=true exposes isClaimed boolean for claimed items`(): Unit =
        runBlocking {
            val claimed = createClaimedItem("Claimed Item", role = Role.QUEUE)

            val result =
                tool.execute(
                    params(
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val claimedRec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == claimed.id.toString() }
            assertNotNull(claimedRec, "Claimed item should be in recommendations")

            val isClaimed = claimedRec.jsonObject["isClaimed"]
            assertNotNull(isClaimed, "isClaimed field should be present when includeClaimed=true")
            assertTrue(isClaimed.jsonPrimitive.boolean, "isClaimed should be true for actively claimed item")
        }

    @Test
    fun `includeClaimed=true shows isClaimed=false for unclaimed items`(): Unit =
        runBlocking {
            val unclaimed = createItem("Unclaimed Item", role = Role.QUEUE)

            val result =
                tool.execute(
                    params(
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val unclaimedRec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == unclaimed.id.toString() }
            assertNotNull(unclaimedRec)
            val isClaimed = unclaimedRec.jsonObject["isClaimed"]
            assertNotNull(isClaimed, "isClaimed field should be present when includeClaimed=true")
            assertFalse(isClaimed.jsonPrimitive.boolean, "isClaimed should be false for unclaimed item")
        }

    @Test
    fun `claimedBy is never disclosed in response even when includeClaimed=true`(): Unit =
        runBlocking {
            createClaimedItem("Claimed Item", role = Role.QUEUE)

            val result =
                tool.execute(
                    params(
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            val rec = recs[0].jsonObject
            assertNull(rec["claimedBy"], "claimedBy must never be disclosed")
            assertNull(rec["claimedAt"], "claimedAt must never be disclosed")
            assertNull(rec["claimExpiresAt"], "claimExpiresAt must never be disclosed")
            assertNull(rec["originalClaimedAt"], "originalClaimedAt must never be disclosed")
        }

    @Test
    fun `isClaimed field is absent when includeClaimed=false (default)`(): Unit =
        runBlocking {
            createItem("Queue Item", role = Role.QUEUE)

            val result = tool.execute(params("limit" to JsonPrimitive(10)), context)

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertNull(recs[0].jsonObject["isClaimed"], "isClaimed should not be present when includeClaimed=false")
        }

    @Test
    fun `role=work with includeClaimed=false filters out actively claimed WORK items`(): Unit =
        runBlocking {
            val unclaimedWork = createItem("Unclaimed Work", role = Role.WORK)
            createClaimedItem("Claimed Work", role = Role.WORK)

            val result =
                tool.execute(
                    params(
                        "role" to JsonPrimitive("work"),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            assertEquals(1, recs.size)
            assertEquals(unclaimedWork.id.toString(), recs[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        }

    // NICE-N2: role=review with includeClaimed=true shows isClaimed=true for a claimed REVIEW item.
    // This verifies that multi-role scope applies claim-disclosure correctly on non-QUEUE roles.
    @Test
    fun `role=review with includeClaimed=true shows isClaimed=true for claimed REVIEW item`(): Unit =
        runBlocking {
            val claimedReview = createClaimedItem("Claimed Review Item", role = Role.REVIEW)

            val result =
                tool.execute(
                    params(
                        "role" to JsonPrimitive("review"),
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val rec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == claimedReview.id.toString() }
            assertNotNull(rec, "Claimed REVIEW item should appear when includeClaimed=true")

            val isClaimed = rec.jsonObject["isClaimed"]
            assertNotNull(isClaimed, "isClaimed field must be present when includeClaimed=true")
            assertTrue(isClaimed.jsonPrimitive.boolean, "isClaimed should be true for an actively claimed REVIEW item")

            // Tiered disclosure: claimedBy identity must not be leaked
            assertNull(rec.jsonObject["claimedBy"], "claimedBy must not be disclosed even for REVIEW items")
        }

    // ──────────────────────────────────────────────
    // H6: JSON-key-absence test for claimedBy when includeClaimed=true
    // ──────────────────────────────────────────────

    /**
     * H6-G1: Verifies that `claimedBy` is NOT present anywhere in the serialized response JSON
     * when `includeClaimed=true`, even as a null-valued key.
     *
     * This is a JSON-key scan (stronger than a value scan): it asserts that the quoted string
     * `"claimedBy"` does not appear anywhere in the serialized response, regardless of value.
     * A structural leakage regression (e.g. a field added as `claimedBy=null`) would be caught
     * by this scan but missed by a value-based check.
     *
     * Additionally verifies that `isClaimed=true` IS present in the response (confirming the
     * claimed item was included and the boolean signal is working).
     */
    @Test
    fun `includeClaimed=true response does NOT contain claimedBy JSON key anywhere in serialized output`(): Unit =
        runBlocking {
            val claimed = createClaimedItem("Claimed Item For Key Scan", role = Role.QUEUE)

            val result =
                tool.execute(
                    params(
                        "includeClaimed" to JsonPrimitive(true),
                        "limit" to JsonPrimitive(10)
                    ),
                    context
                )

            assertTrue(isSuccess(result))
            val recs = extractRecommendations(result)
            val claimedRec = recs.find { it.jsonObject["itemId"]!!.jsonPrimitive.content == claimed.id.toString() }
            assertNotNull(claimedRec, "Claimed item must appear in recommendations when includeClaimed=true")

            // isClaimed boolean SHOULD be present and true
            val isClaimed = claimedRec.jsonObject["isClaimed"]
            assertNotNull(isClaimed, "isClaimed field must be present when includeClaimed=true")
            assertTrue(isClaimed.jsonPrimitive.boolean, "isClaimed should be true for an actively claimed item")

            // JSON-key scan — stronger than a value check.
            // Assert that "claimedBy" (the quoted key) does NOT appear ANYWHERE in the entire
            // serialized response. This catches even claimedBy=null or renamed variants.
            val serialized = result.toString()
            assertFalse(
                "\"claimedBy\"" in serialized,
                "The \"claimedBy\" JSON key must NOT appear anywhere in the response when includeClaimed=true. " +
                    "Got: $serialized"
            )
        }
}
