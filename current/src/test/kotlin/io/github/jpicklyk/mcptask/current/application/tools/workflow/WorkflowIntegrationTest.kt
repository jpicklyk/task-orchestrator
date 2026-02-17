package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.*
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
 * End-to-end workflow integration tests that exercise multiple tools and services
 * together using a real in-memory H2 database. These tests verify cross-tool
 * workflows where operations happen in sequence and state evolves across calls.
 */
class WorkflowIntegrationTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var transitionTool: RequestTransitionTool
    private lateinit var nextItemTool: GetNextItemTool
    private lateinit var blockedItemsTool: GetBlockedItemsTool
    private lateinit var nextStatusTool: GetNextStatusTool

    @BeforeEach
    fun setUp() {
        val dbName = "workflow_integration_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)

        transitionTool = RequestTransitionTool()
        nextItemTool = GetNextItemTool()
        blockedItemsTool = GetBlockedItemsTool()
        nextStatusTool = GetNextStatusTool()
    }

    // ──────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        parentId: UUID? = null,
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        priority: Priority = Priority.MEDIUM,
        complexity: Int = 5,
        summary: String = "",
        tags: String? = null,
        statusLabel: String? = null
    ): WorkItem {
        val depth = if (parentId != null) {
            val parentResult = context.workItemRepository().getById(parentId)
            (parentResult as Result.Success).data.depth + 1
        } else 0
        val item = WorkItem(
            parentId = parentId,
            title = title,
            role = role,
            previousRole = previousRole,
            priority = priority,
            complexity = complexity,
            summary = summary,
            tags = tags,
            statusLabel = statusLabel,
            depth = depth
        )
        val result = context.workItemRepository().create(item)
        return (result as Result.Success).data
    }

    private fun createDependency(
        fromItemId: UUID,
        toItemId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        val dep = Dependency(
            fromItemId = fromItemId,
            toItemId = toItemId,
            type = type,
            unblockAt = unblockAt
        )
        return context.dependencyRepository().create(dep)
    }

    private fun buildTransitionParams(vararg transitions: JsonObject): JsonObject {
        return buildJsonObject {
            put("transitions", buildJsonArray {
                transitions.forEach { add(it) }
            })
        }
    }

    private fun transitionObj(itemId: UUID, trigger: String, summary: String? = null): JsonObject {
        return buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
            if (summary != null) put("summary", JsonPrimitive(summary))
        }
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response but got: $result")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray {
        return extractData(result)["results"]!!.jsonArray
    }

    private fun extractSummary(result: JsonElement): JsonObject {
        return extractData(result)["summary"]!!.jsonObject
    }

    private fun assertTransitionApplied(resultObj: JsonObject) {
        assertTrue(resultObj["applied"]!!.jsonPrimitive.boolean,
            "Expected transition to be applied but got: $resultObj")
    }

    private fun assertTransitionFailed(resultObj: JsonObject) {
        assertFalse(resultObj["applied"]!!.jsonPrimitive.boolean,
            "Expected transition to fail but got: $resultObj")
    }

    private suspend fun getItem(id: UUID): WorkItem {
        val result = context.workItemRepository().getById(id)
        return (result as Result.Success).data
    }

    // ──────────────────────────────────────────────
    // 1. Full Lifecycle: create -> transition -> cascade
    // ──────────────────────────────────────────────

    @Test
    fun `full lifecycle with cascade on last child completion`(): Unit = runBlocking {
        // Create parent with two children
        val parent = createItem("Parent Item", role = Role.WORK)
        val child1 = createItem("Child 1", parentId = parent.id, role = Role.QUEUE)
        val child2 = createItem("Child 2", parentId = parent.id, role = Role.QUEUE)

        // Transition child1: QUEUE -> WORK -> TERMINAL
        val startChild1 = transitionTool.execute(
            buildTransitionParams(transitionObj(child1.id, "start")),
            context
        )
        assertTransitionApplied(extractResults(startChild1)[0].jsonObject)
        assertEquals("work", extractResults(startChild1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)

        val completeChild1 = transitionTool.execute(
            buildTransitionParams(transitionObj(child1.id, "complete")),
            context
        )
        assertTransitionApplied(extractResults(completeChild1)[0].jsonObject)
        assertEquals("terminal", extractResults(completeChild1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)

        // Parent should NOT cascade yet (child2 still in QUEUE)
        val cascadeEvents1 = extractResults(completeChild1)[0].jsonObject["cascadeEvents"]!!.jsonArray
        assertEquals(0, cascadeEvents1.size, "No cascade should fire with one child still non-terminal")

        // Transition child2: QUEUE -> WORK -> TERMINAL
        val startChild2 = transitionTool.execute(
            buildTransitionParams(transitionObj(child2.id, "start")),
            context
        )
        assertTransitionApplied(extractResults(startChild2)[0].jsonObject)

        val completeChild2 = transitionTool.execute(
            buildTransitionParams(transitionObj(child2.id, "complete")),
            context
        )
        assertTransitionApplied(extractResults(completeChild2)[0].jsonObject)

        // Now parent should cascade to TERMINAL
        val cascadeEvents2 = extractResults(completeChild2)[0].jsonObject["cascadeEvents"]!!.jsonArray
        assertEquals(1, cascadeEvents2.size, "Cascade should fire when all children are terminal")
        val cascade = cascadeEvents2[0].jsonObject
        assertEquals(parent.id.toString(), cascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("terminal", cascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)

        // Verify parent is actually terminal in the database
        val updatedParent = getItem(parent.id)
        assertEquals(Role.TERMINAL, updatedParent.role)
    }

    // ──────────────────────────────────────────────
    // 2. Dependency Gating (unblockAt=terminal default)
    // ──────────────────────────────────────────────

    @Test
    fun `dependency gating blocks transition until blocker reaches terminal`(): Unit = runBlocking {
        val itemA = createItem("Item A")
        val itemB = createItem("Item B")

        // A BLOCKS B with default unblockAt (terminal)
        createDependency(fromItemId = itemA.id, toItemId = itemB.id)

        // Try to start B -- should be blocked
        val startB = transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "start")),
            context
        )
        val bResult = extractResults(startB)[0].jsonObject
        assertTransitionFailed(bResult)
        assertTrue(bResult["error"]!!.jsonPrimitive.content.contains("blocking"))
        assertNotNull(bResult["blockers"])

        // Transition A: QUEUE -> WORK
        transitionTool.execute(
            buildTransitionParams(transitionObj(itemA.id, "start")),
            context
        )

        // B should still be blocked (A is at WORK, not TERMINAL)
        val startB2 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "start")),
            context
        )
        assertTransitionFailed(extractResults(startB2)[0].jsonObject)

        // Complete A -> TERMINAL
        transitionTool.execute(
            buildTransitionParams(transitionObj(itemA.id, "complete")),
            context
        )

        // Now B should be startable
        val startB3 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "start")),
            context
        )
        val bResult3 = extractResults(startB3)[0].jsonObject
        assertTransitionApplied(bResult3)
        assertEquals("work", bResult3["newRole"]!!.jsonPrimitive.content)

        // Verify B is actually in WORK
        val updatedB = getItem(itemB.id)
        assertEquals(Role.WORK, updatedB.role)
    }

    // ──────────────────────────────────────────────
    // 3. unblockAt Early Threshold (work)
    // ──────────────────────────────────────────────

    @Test
    fun `unblockAt work allows transition when blocker enters WORK`(): Unit = runBlocking {
        val itemA = createItem("Item A")
        val itemB = createItem("Item B")

        // A BLOCKS B with unblockAt=work
        createDependency(fromItemId = itemA.id, toItemId = itemB.id, unblockAt = "work")

        // B is blocked while A is still in QUEUE
        val startB1 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "start")),
            context
        )
        assertTransitionFailed(extractResults(startB1)[0].jsonObject)

        // Transition A to WORK
        val startA = transitionTool.execute(
            buildTransitionParams(transitionObj(itemA.id, "start")),
            context
        )
        assertTransitionApplied(extractResults(startA)[0].jsonObject)

        // Now B should be startable (A is at WORK, threshold is WORK)
        val startB2 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "start")),
            context
        )
        val bResult = extractResults(startB2)[0].jsonObject
        assertTransitionApplied(bResult)
        assertEquals("work", bResult["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 4. Batch Transitions Mixed Results
    // ──────────────────────────────────────────────

    @Test
    fun `batch transitions with mixed success and failure results`(): Unit = runBlocking {
        val item1 = createItem("Item 1", role = Role.QUEUE)
        val item2 = createItem("Item 2", role = Role.TERMINAL)
        val item3 = createItem("Item 3", role = Role.QUEUE)
        val blocker = createItem("Blocker", role = Role.QUEUE)
        createDependency(fromItemId = blocker.id, toItemId = item3.id)

        // Batch transition all three with "start" trigger
        val result = transitionTool.execute(
            buildTransitionParams(
                transitionObj(item1.id, "start"),
                transitionObj(item2.id, "start"),
                transitionObj(item3.id, "start")
            ),
            context
        )

        val results = extractResults(result)
        assertEquals(3, results.size)

        // item1 succeeds (QUEUE -> WORK)
        assertTransitionApplied(results[0].jsonObject)
        assertEquals("work", results[0].jsonObject["newRole"]!!.jsonPrimitive.content)

        // item2 fails (already terminal)
        assertTransitionFailed(results[1].jsonObject)
        assertTrue(results[1].jsonObject["error"]!!.jsonPrimitive.content.contains("terminal"))

        // item3 fails (blocked by dependency)
        assertTransitionFailed(results[2].jsonObject)
        assertTrue(results[2].jsonObject["error"]!!.jsonPrimitive.content.contains("blocking"))

        val summary = extractSummary(result)
        assertEquals(3, summary["total"]!!.jsonPrimitive.int)
        assertEquals(1, summary["succeeded"]!!.jsonPrimitive.int)
        assertEquals(2, summary["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 5. BLOCKED/Resume Round-trip
    // ──────────────────────────────────────────────

    @Test
    fun `block and resume round-trip preserves previous role`(): Unit = runBlocking {
        val item = createItem("Work Item", role = Role.WORK)

        // Block the item
        val blockResult = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "block")),
            context
        )
        val blockRes = extractResults(blockResult)[0].jsonObject
        assertTransitionApplied(blockRes)
        assertEquals("work", blockRes["previousRole"]!!.jsonPrimitive.content)
        assertEquals("blocked", blockRes["newRole"]!!.jsonPrimitive.content)

        // Verify the item is blocked in the database with previousRole saved
        val blockedItem = getItem(item.id)
        assertEquals(Role.BLOCKED, blockedItem.role)
        assertEquals(Role.WORK, blockedItem.previousRole)

        // Resume the item
        val resumeResult = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "resume")),
            context
        )
        val resumeRes = extractResults(resumeResult)[0].jsonObject
        assertTransitionApplied(resumeRes)
        assertEquals("blocked", resumeRes["previousRole"]!!.jsonPrimitive.content)
        assertEquals("work", resumeRes["newRole"]!!.jsonPrimitive.content)

        // Verify item is back in WORK with previousRole cleared
        val resumedItem = getItem(item.id)
        assertEquals(Role.WORK, resumedItem.role)
        assertNull(resumedItem.previousRole)
    }

    // ──────────────────────────────────────────────
    // 6. Cancel Sets StatusLabel
    // ──────────────────────────────────────────────

    @Test
    fun `cancel transition sets statusLabel to cancelled`(): Unit = runBlocking {
        val item = createItem("Work Item", role = Role.WORK)

        val cancelResult = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "cancel")),
            context
        )
        val cancelRes = extractResults(cancelResult)[0].jsonObject
        assertTransitionApplied(cancelRes)
        assertEquals("work", cancelRes["previousRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", cancelRes["newRole"]!!.jsonPrimitive.content)

        // Verify the statusLabel is "cancelled" in the database
        val cancelledItem = getItem(item.id)
        assertEquals(Role.TERMINAL, cancelledItem.role)
        assertEquals("cancelled", cancelledItem.statusLabel)
    }

    // ──────────────────────────────────────────────
    // 7. get_next_item Skips Blocked Items
    // ──────────────────────────────────────────────

    @Test
    fun `get_next_item skips blocked items and sorts by priority`(): Unit = runBlocking {
        val itemA = createItem("Item A - no deps", priority = Priority.LOW, complexity = 3)
        val itemB = createItem("Item B - blocked", priority = Priority.HIGH, complexity = 1)
        val itemC = createItem("Item C - high priority", priority = Priority.HIGH, complexity = 2)

        // Create a dependency blocking B
        val blocker = createItem("Blocker", role = Role.QUEUE)
        createDependency(fromItemId = blocker.id, toItemId = itemB.id)

        // Get next item with limit 10 to see all recommendations
        val result = nextItemTool.execute(
            buildJsonObject { put("limit", JsonPrimitive(10)) },
            context
        )

        assertTrue((result as JsonObject)["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val recommendations = data["recommendations"]!!.jsonArray

        // B should NOT appear (blocked by dependency)
        val recIds = recommendations.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertFalse(recIds.contains(itemB.id.toString()),
            "Blocked item B should not be in recommendations")

        // C (HIGH priority, complexity 2) should come before A (LOW priority)
        assertTrue(recIds.contains(itemC.id.toString()),
            "Unblocked high-priority item C should be recommended")
        assertTrue(recIds.contains(itemA.id.toString()),
            "Unblocked item A should be recommended")

        // Verify ordering: HIGH priority items before LOW priority
        val cIndex = recIds.indexOf(itemC.id.toString())
        val aIndex = recIds.indexOf(itemA.id.toString())
        assertTrue(cIndex < aIndex,
            "High priority item C should come before low priority item A")
    }

    // ──────────────────────────────────────────────
    // 8. get_blocked_items Reports Both Explicit and Dependency Blocks
    // ──────────────────────────────────────────────

    @Test
    fun `get_blocked_items reports both explicit and dependency blocks`(): Unit = runBlocking {
        // X: explicitly BLOCKED
        val itemX = createItem("Explicitly Blocked", role = Role.BLOCKED, previousRole = Role.WORK)

        // Y: QUEUE with unsatisfied dependency
        val itemY = createItem("Dependency Blocked")
        val blocker = createItem("Blocker for Y", role = Role.QUEUE)
        createDependency(fromItemId = blocker.id, toItemId = itemY.id)

        val result = blockedItemsTool.execute(buildJsonObject { }, context) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        val blockedItems = data["blockedItems"]!!.jsonArray
        val total = data["total"]!!.jsonPrimitive.int

        assertEquals(2, total)

        // Find X (explicit block)
        val xEntry = blockedItems.firstOrNull {
            it.jsonObject["itemId"]!!.jsonPrimitive.content == itemX.id.toString()
        }
        assertNotNull(xEntry, "Explicitly blocked item X should be in results")
        assertEquals("explicit", xEntry.jsonObject["blockType"]!!.jsonPrimitive.content)

        // Find Y (dependency block)
        val yEntry = blockedItems.firstOrNull {
            it.jsonObject["itemId"]!!.jsonPrimitive.content == itemY.id.toString()
        }
        assertNotNull(yEntry, "Dependency-blocked item Y should be in results")
        assertEquals("dependency", yEntry.jsonObject["blockType"]!!.jsonPrimitive.content)
        assertEquals(1, yEntry.jsonObject["blockerCount"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 9. get_next_status Ready vs Blocked vs Terminal
    // ──────────────────────────────────────────────

    @Test
    fun `get_next_status returns Ready for unblocked item`(): Unit = runBlocking {
        val item = createItem("Ready Item")

        val result = nextStatusTool.execute(
            buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
            context
        )

        val data = extractData(result)
        assertEquals("Ready", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("queue", data["currentRole"]!!.jsonPrimitive.content)
        assertEquals("work", data["nextRole"]!!.jsonPrimitive.content)
        assertEquals("start", data["trigger"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_next_status returns Blocked for dependency-blocked item`(): Unit = runBlocking {
        val item = createItem("Blocked Item")
        val blocker = createItem("Blocker", role = Role.QUEUE)
        createDependency(fromItemId = blocker.id, toItemId = item.id)

        val result = nextStatusTool.execute(
            buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
            context
        )

        val data = extractData(result)
        assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("queue", data["currentRole"]!!.jsonPrimitive.content)
        val blockers = data["blockers"]!!.jsonArray
        assertEquals(1, blockers.size)
        assertEquals(blocker.id.toString(), blockers[0].jsonObject["fromItemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_next_status returns Terminal for completed item`(): Unit = runBlocking {
        val item = createItem("Terminal Item", role = Role.TERMINAL)

        val result = nextStatusTool.execute(
            buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
            context
        )

        val data = extractData(result)
        assertEquals("Terminal", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("terminal", data["currentRole"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_next_status returns Blocked with resume suggestion for BLOCKED item`(): Unit = runBlocking {
        val item = createItem("Blocked Item", role = Role.BLOCKED, previousRole = Role.WORK)

        val result = nextStatusTool.execute(
            buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
            context
        )

        val data = extractData(result)
        assertEquals("Blocked", data["recommendation"]!!.jsonPrimitive.content)
        assertEquals("blocked", data["currentRole"]!!.jsonPrimitive.content)
        assertTrue(data["suggestion"]!!.jsonPrimitive.content.contains("resume"))
    }

    // ──────────────────────────────────────────────
    // 10. Cascade Chain (parent -> grandparent)
    // ──────────────────────────────────────────────

    @Test
    fun `cascade chain advances parent then grandparent`(): Unit = runBlocking {
        // Three-level hierarchy: grandparent > parent > children
        // When the last child completes, cascade should propagate:
        //   child2 -> TERMINAL triggers parent -> TERMINAL triggers grandparent -> TERMINAL
        val grandparent = createItem("Grandparent", role = Role.WORK)
        val parent = createItem("Parent", parentId = grandparent.id, role = Role.WORK)
        val child1 = createItem("Child 1", parentId = parent.id, role = Role.WORK)
        val child2 = createItem("Child 2", parentId = parent.id, role = Role.WORK)

        // Complete child1 -- parent should NOT cascade yet
        transitionTool.execute(
            buildTransitionParams(transitionObj(child1.id, "complete")),
            context
        )
        assertEquals(Role.WORK, getItem(parent.id).role)
        assertEquals(Role.WORK, getItem(grandparent.id).role)

        // Complete child2 -- should trigger multi-level cascade:
        //   parent -> TERMINAL (all children terminal)
        //   grandparent -> TERMINAL (all children terminal, since parent is only child)
        val completeChild2 = transitionTool.execute(
            buildTransitionParams(transitionObj(child2.id, "complete")),
            context
        )

        val cascades = extractResults(completeChild2)[0].jsonObject["cascadeEvents"]!!.jsonArray

        // Should have cascades for BOTH parent and grandparent
        assertEquals(2, cascades.size, "Expected 2 cascade events (parent + grandparent)")

        // First cascade: parent -> TERMINAL
        val parentCascade = cascades[0].jsonObject
        assertEquals(parent.id.toString(), parentCascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("work", parentCascade["previousRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", parentCascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(parentCascade["applied"]!!.jsonPrimitive.boolean)

        // Second cascade: grandparent -> TERMINAL
        val grandparentCascade = cascades[1].jsonObject
        assertEquals(grandparent.id.toString(), grandparentCascade["itemId"]!!.jsonPrimitive.content)
        assertEquals("work", grandparentCascade["previousRole"]!!.jsonPrimitive.content)
        assertEquals("terminal", grandparentCascade["targetRole"]!!.jsonPrimitive.content)
        assertTrue(grandparentCascade["applied"]!!.jsonPrimitive.boolean)

        // Verify both are terminal in the database
        assertEquals(Role.TERMINAL, getItem(parent.id).role)
        assertEquals(Role.TERMINAL, getItem(grandparent.id).role)
    }

    // ──────────────────────────────────────────────
    // 11. Unblocked Items Reported After Completion
    // ──────────────────────────────────────────────

    @Test
    fun `completing a blocker reports unblocked downstream items`(): Unit = runBlocking {
        val itemA = createItem("Item A")
        val itemB = createItem("Item B")
        createDependency(fromItemId = itemA.id, toItemId = itemB.id)

        // Complete A
        val completeA = transitionTool.execute(
            buildTransitionParams(transitionObj(itemA.id, "complete")),
            context
        )

        val resultObj = extractResults(completeA)[0].jsonObject
        assertTransitionApplied(resultObj)

        // Check unblockedItems in per-result scope
        val unblockedItems = resultObj["unblockedItems"]!!.jsonArray
        assertEquals(1, unblockedItems.size)
        assertEquals(itemB.id.toString(), unblockedItems[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        assertEquals("Item B", unblockedItems[0].jsonObject["title"]!!.jsonPrimitive.content)

        // Check allUnblockedItems in top-level data
        val data = extractData(completeA)
        val allUnblocked = data["allUnblockedItems"]!!.jsonArray
        assertEquals(1, allUnblocked.size)
    }

    // ──────────────────────────────────────────────
    // 12. Full Progression Through All Roles
    // ──────────────────────────────────────────────

    @Test
    fun `item progresses through QUEUE to WORK to REVIEW to TERMINAL via start`(): Unit = runBlocking {
        val item = createItem("Full Progression Item")

        // QUEUE -> WORK
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertEquals("work", extractResults(start1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
        assertEquals(Role.WORK, getItem(item.id).role)

        // WORK -> REVIEW
        val start2 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertEquals("review", extractResults(start2)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
        assertEquals(Role.REVIEW, getItem(item.id).role)

        // REVIEW -> TERMINAL
        val start3 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertEquals("terminal", extractResults(start3)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
        assertEquals(Role.TERMINAL, getItem(item.id).role)

        // TERMINAL -> start should fail
        val start4 = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "start")),
            context
        )
        assertTransitionFailed(extractResults(start4)[0].jsonObject)
    }

    // ──────────────────────────────────────────────
    // 13. Multiple Dependency Gating
    // ──────────────────────────────────────────────

    @Test
    fun `item blocked by multiple dependencies requires all satisfied`(): Unit = runBlocking {
        val itemA = createItem("Blocker A")
        val itemB = createItem("Blocker B")
        val itemC = createItem("Blocked Item")

        createDependency(fromItemId = itemA.id, toItemId = itemC.id)
        createDependency(fromItemId = itemB.id, toItemId = itemC.id)

        // C should be blocked
        val start1 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemC.id, "start")),
            context
        )
        assertTransitionFailed(extractResults(start1)[0].jsonObject)

        // Complete A
        transitionTool.execute(
            buildTransitionParams(transitionObj(itemA.id, "complete")),
            context
        )

        // C should still be blocked (B is not terminal)
        val start2 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemC.id, "start")),
            context
        )
        assertTransitionFailed(extractResults(start2)[0].jsonObject)

        // Complete B
        transitionTool.execute(
            buildTransitionParams(transitionObj(itemB.id, "complete")),
            context
        )

        // Now C should be startable
        val start3 = transitionTool.execute(
            buildTransitionParams(transitionObj(itemC.id, "start")),
            context
        )
        assertTransitionApplied(extractResults(start3)[0].jsonObject)
        assertEquals("work", extractResults(start3)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 14. Hold Trigger Round-trip (alias for block)
    // ──────────────────────────────────────────────

    @Test
    fun `hold trigger works like block and resume restores from hold`(): Unit = runBlocking {
        val item = createItem("Review Item", role = Role.REVIEW)

        // Hold the item
        val holdResult = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "hold")),
            context
        )
        val holdRes = extractResults(holdResult)[0].jsonObject
        assertTransitionApplied(holdRes)
        assertEquals("review", holdRes["previousRole"]!!.jsonPrimitive.content)
        assertEquals("blocked", holdRes["newRole"]!!.jsonPrimitive.content)

        // Verify in DB
        val heldItem = getItem(item.id)
        assertEquals(Role.BLOCKED, heldItem.role)
        assertEquals(Role.REVIEW, heldItem.previousRole)

        // Resume -- should go back to REVIEW
        val resumeResult = transitionTool.execute(
            buildTransitionParams(transitionObj(item.id, "resume")),
            context
        )
        val resumeRes = extractResults(resumeResult)[0].jsonObject
        assertTransitionApplied(resumeRes)
        assertEquals("review", resumeRes["newRole"]!!.jsonPrimitive.content)

        assertEquals(Role.REVIEW, getItem(item.id).role)
    }

    // ──────────────────────────────────────────────
    // 15. Transition and GetNextItem Interaction
    // ──────────────────────────────────────────────

    @Test
    fun `completing a blocker makes downstream item appear in get_next_item`(): Unit = runBlocking {
        val blocker = createItem("Blocker", priority = Priority.HIGH, complexity = 1)
        val downstream = createItem("Downstream", priority = Priority.HIGH, complexity = 2)
        createDependency(fromItemId = blocker.id, toItemId = downstream.id)

        // Initially, only blocker should be recommended (downstream is blocked)
        val result1 = nextItemTool.execute(
            buildJsonObject { put("limit", JsonPrimitive(10)) },
            context
        )
        val recs1 = (extractData(result1))["recommendations"]!!.jsonArray
        val recIds1 = recs1.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertTrue(recIds1.contains(blocker.id.toString()))
        assertFalse(recIds1.contains(downstream.id.toString()))

        // Complete the blocker
        transitionTool.execute(
            buildTransitionParams(transitionObj(blocker.id, "complete")),
            context
        )

        // Now downstream should appear in recommendations (blocker no longer in QUEUE)
        val result2 = nextItemTool.execute(
            buildJsonObject { put("limit", JsonPrimitive(10)) },
            context
        )
        val recs2 = (extractData(result2))["recommendations"]!!.jsonArray
        val recIds2 = recs2.map { it.jsonObject["itemId"]!!.jsonPrimitive.content }
        assertTrue(recIds2.contains(downstream.id.toString()),
            "Downstream should now be recommended after blocker completion")
        assertFalse(recIds2.contains(blocker.id.toString()),
            "Completed blocker should no longer be recommended")
    }
}
