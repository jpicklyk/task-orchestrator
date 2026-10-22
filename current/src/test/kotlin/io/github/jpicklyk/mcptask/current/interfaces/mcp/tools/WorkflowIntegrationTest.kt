package io.github.jpicklyk.mcptask.current.interfaces.mcp.tools

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.OutsideUnitPolicy
import io.github.jpicklyk.mcptask.current.test.advanceTool
import io.github.jpicklyk.mcptask.current.test.blockerIds
import io.github.jpicklyk.mcptask.current.test.errorCode
import io.github.jpicklyk.mcptask.current.test.errorMessage
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.tools.ItemContextProbe
import io.github.jpicklyk.mcptask.current.test.transitionOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.*

/**
 * End-to-end workflow integration tests that exercise multiple tools and services
 * together using a real SQLite database (transitions through the production `advance` tool; Q32: formerly the removed
 * 3.x advance_item). These tests verify cross-tool
 * workflows where operations happen in sequence and state evolves across calls.
 *
 * The scenarios run twice: under the test fixture's [OutsideUnitPolicy.IMPLICIT]
 * ([WorkflowIntegrationTest]) and under the production [OutsideUnitPolicy.FAIL]
 * ([WorkflowIntegrationFailPolicyTest]), so a tool write made outside a unit of work fails CI instead of
 * only failing in production. Fixtures are seeded inside a unit so both runs share one body.
 */
class WorkflowIntegrationTest : WorkflowIntegrationScenarios(OutsideUnitPolicy.IMPLICIT)

/** [WorkflowIntegrationScenarios] under the production [OutsideUnitPolicy.FAIL]. */
class WorkflowIntegrationFailPolicyTest : WorkflowIntegrationScenarios(OutsideUnitPolicy.FAIL)

abstract class WorkflowIntegrationScenarios(
    outsideUnitPolicy: OutsideUnitPolicy
) {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod(outsideUnitPolicy)

    private lateinit var context: ToolExecutionContext
    private lateinit var nextItemTool: ItemContextProbe
    private lateinit var blockedItemsTool: ItemContextProbe
    private lateinit var nextStatusTool: ItemContextProbe

    @BeforeEach
    fun setUp() {
        val repositoryProvider = db.repositoryProvider()
        context = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork())

        nextItemTool = ItemContextProbe("next")
        blockedItemsTool = ItemContextProbe("blocked")
        nextStatusTool = ItemContextProbe("context")
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
        val depth =
            if (parentId != null) {
                val parentResult = context.workItemRepository().getById(parentId)
                parentResult!!.depth + 1
            } else {
                0
            }
        val item =
            WorkItem(
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
        return seed("WorkflowIntegration.createItem") { context.workItemRepository().create(item) }
    }

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
        // Orchestrator sweep repair [33e96efd]: create() is now suspend; helper stays non-suspend.
        return runBlocking { seed("WorkflowIntegration.createDependency") { context.dependencyRepository().create(dep) } }
    }

    /** Seeds a fixture inside one write unit, so the FAIL-policy run accepts it. */
    private suspend fun <T> seed(
        op: String,
        block: suspend () -> T
    ): T =
        when (val outcome = context.unitOfWork.write(op) { Outcome.Ok(block()) }) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> error("fixture seed '$op' failed: ${outcome.error.message}")
        }

    /** One `advance` call with [transitions] (`transition` ops); the envelope body. */
    private suspend fun advance(vararg transitions: JsonObject): JsonObject = context.advanceTool(*transitions)

    private fun transitionObj(
        itemId: UUID,
        trigger: String,
        summary: String? = null
    ): JsonObject = transitionOp(itemId, trigger, *(if (summary != null) arrayOf("summary" to JsonPrimitive(summary)) else emptyArray()))

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response but got: $result")
        return obj["data"] as JsonObject
    }

    /** The `data` of an `advance` envelope: present on success and on a partial failure alike. */
    private fun advanceData(result: JsonElement): JsonObject =
        assertNotNull((result as JsonObject)["data"] as? JsonObject, "the advance envelope carries data: $result")

    private fun extractResults(result: JsonElement): JsonArray = advanceData(result)["results"]!!.jsonArray

    private fun extractSummary(result: JsonElement): JsonObject = advanceData(result)["summary"]!!.jsonObject

    private fun assertTransitionApplied(resultObj: JsonObject) {
        assertTrue(
            resultObj["ok"]!!.jsonPrimitive.boolean,
            "Expected transition to be applied but got: $resultObj"
        )
    }

    private fun assertTransitionFailed(resultObj: JsonObject) {
        assertFalse(
            resultObj["ok"]!!.jsonPrimitive.boolean,
            "Expected transition to fail but got: $resultObj"
        )
    }

    private suspend fun getItem(id: UUID): WorkItem {
        val result = context.workItemRepository().getById(id)
        return result!!
    }

    // ──────────────────────────────────────────────
    // 1. Full Lifecycle: create -> transition -> cascade
    // ──────────────────────────────────────────────

    @Test
    fun `full lifecycle with cascade on last child completion`(): Unit =
        runBlocking {
            // Create parent with two children
            val parent = createItem("Parent Item", role = Role.WORK)
            val child1 = createItem("Child 1", parentId = parent.id, role = Role.QUEUE)
            val child2 = createItem("Child 2", parentId = parent.id, role = Role.QUEUE)

            // Transition child1: QUEUE -> WORK -> TERMINAL
            val startChild1 =
                advance(transitionObj(child1.id, "start"))
            assertTransitionApplied(extractResults(startChild1)[0].jsonObject)
            assertEquals("work", extractResults(startChild1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)

            val completeChild1 =
                advance(transitionObj(child1.id, "complete"))
            assertTransitionApplied(extractResults(completeChild1)[0].jsonObject)
            assertEquals("terminal", extractResults(completeChild1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)

            // Parent should NOT cascade yet (child2 still in QUEUE).
            // Empty cascadeEvents is omitted entirely for token efficiency.
            assertNull(
                extractResults(completeChild1)[0].jsonObject["cascades"],
                "cascades should be omitted when no cascade fires"
            )

            // Transition child2: QUEUE -> WORK -> TERMINAL
            val startChild2 =
                advance(transitionObj(child2.id, "start"))
            assertTransitionApplied(extractResults(startChild2)[0].jsonObject)

            val completeChild2 =
                advance(transitionObj(child2.id, "complete"))
            assertTransitionApplied(extractResults(completeChild2)[0].jsonObject)

            // Now parent should cascade to TERMINAL
            val cascadeEvents2 = extractResults(completeChild2)[0].jsonObject["cascades"]!!.jsonArray
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
    fun `dependency gating blocks transition until blocker reaches terminal`(): Unit =
        runBlocking {
            val itemA = createItem("Item A")
            val itemB = createItem("Item B")

            // A BLOCKS B with default unblockAt (terminal)
            createDependency(fromItemId = itemA.id, toItemId = itemB.id)

            // Try to start B -- should be blocked
            val startB =
                advance(transitionObj(itemB.id, "start"))
            val bResult = extractResults(startB)[0].jsonObject
            assertTransitionFailed(bResult)
            assertTrue(bResult.errorMessage()!!.contains("blocking"), "$bResult")
            assertEquals("dependency_unmet", bResult.errorCode())
            assertEquals(listOf(itemA.id.toString()), bResult.blockerIds())

            // Transition A: QUEUE -> WORK
            advance(transitionObj(itemA.id, "start"))

            // B should still be blocked (A is at WORK, not TERMINAL)
            val startB2 =
                advance(transitionObj(itemB.id, "start"))
            assertTransitionFailed(extractResults(startB2)[0].jsonObject)

            // Complete A -> TERMINAL
            advance(transitionObj(itemA.id, "complete"))

            // Now B should be startable
            val startB3 =
                advance(transitionObj(itemB.id, "start"))
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
    fun `unblockAt work allows transition when blocker enters WORK`(): Unit =
        runBlocking {
            val itemA = createItem("Item A")
            val itemB = createItem("Item B")

            // A BLOCKS B with unblockAt=work
            createDependency(fromItemId = itemA.id, toItemId = itemB.id, unblockAt = "work")

            // B is blocked while A is still in QUEUE
            val startB1 =
                advance(transitionObj(itemB.id, "start"))
            assertTransitionFailed(extractResults(startB1)[0].jsonObject)

            // Transition A to WORK
            val startA =
                advance(transitionObj(itemA.id, "start"))
            assertTransitionApplied(extractResults(startA)[0].jsonObject)

            // Now B should be startable (A is at WORK, threshold is WORK)
            val startB2 =
                advance(transitionObj(itemB.id, "start"))
            val bResult = extractResults(startB2)[0].jsonObject
            assertTransitionApplied(bResult)
            assertEquals("work", bResult["newRole"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // 4. Batch Transitions Mixed Results
    // ──────────────────────────────────────────────

    @Test
    fun `batch transitions with mixed success and failure results`(): Unit =
        runBlocking {
            val item1 = createItem("Item 1", role = Role.QUEUE)
            val item2 = createItem("Item 2", role = Role.TERMINAL)
            val item3 = createItem("Item 3", role = Role.QUEUE)
            val blocker = createItem("Blocker", role = Role.QUEUE)
            createDependency(fromItemId = blocker.id, toItemId = item3.id)

            // Batch transition all three with "start" trigger
            val result =
                advance(
                    transitionObj(item1.id, "start"),
                    transitionObj(item2.id, "start"),
                    transitionObj(item3.id, "start")
                )

            val results = extractResults(result)
            assertEquals(3, results.size)

            // item1 succeeds (QUEUE -> WORK)
            assertTransitionApplied(results[0].jsonObject)
            assertEquals("work", results[0].jsonObject["newRole"]!!.jsonPrimitive.content)

            // item2 fails (already terminal)
            assertTransitionFailed(results[1].jsonObject)
            assertTrue(results[1].jsonObject.errorMessage()!!.contains("terminal"), "${results[1]}")

            // item3 fails (blocked by dependency)
            assertTransitionFailed(results[2].jsonObject)
            assertTrue(results[2].jsonObject.errorMessage()!!.contains("blocking"), "${results[2]}")

            val summary = extractSummary(result)
            assertEquals(1, summary["ok"]!!.jsonPrimitive.int)
            assertEquals(2, summary["failed"]!!.jsonPrimitive.int)
            assertEquals(
                "partial_failure",
                (result["error"] as JsonObject)["code"]!!.jsonPrimitive.content,
                "a mixed batch is a partial failure"
            )
        }

    // ──────────────────────────────────────────────
    // 5. BLOCKED/Resume Round-trip
    // ──────────────────────────────────────────────

    @Test
    fun `block and resume round-trip preserves previous role`(): Unit =
        runBlocking {
            val item = createItem("Work Item", role = Role.WORK)

            // Block the item
            val blockResult =
                advance(transitionObj(item.id, "block"))
            val blockRes = extractResults(blockResult)[0].jsonObject
            assertTransitionApplied(blockRes)
            assertEquals("blocked", blockRes["newRole"]!!.jsonPrimitive.content)

            // Verify the item is blocked in the database with previousRole saved
            val blockedItem = getItem(item.id)
            assertEquals(Role.BLOCKED, blockedItem.role)
            assertEquals(Role.WORK, blockedItem.previousRole)

            // Resume the item
            val resumeResult =
                advance(transitionObj(item.id, "resume"))
            val resumeRes = extractResults(resumeResult)[0].jsonObject
            assertTransitionApplied(resumeRes)
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
    fun `cancel transition sets statusLabel to cancelled`(): Unit =
        runBlocking {
            val item = createItem("Work Item", role = Role.WORK)

            val cancelResult =
                advance(transitionObj(item.id, "cancel"))
            val cancelRes = extractResults(cancelResult)[0].jsonObject
            assertTransitionApplied(cancelRes)
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
    fun `next skips blocked items and sorts by priority`(): Unit =
        runBlocking {
            val itemA = createItem("Item A - no deps", priority = Priority.LOW, complexity = 3)
            val itemB = createItem("Item B - blocked", priority = Priority.HIGH, complexity = 1)
            val itemC = createItem("Item C - high priority", priority = Priority.HIGH, complexity = 2)

            // Create a dependency blocking B
            val blocker = createItem("Blocker", role = Role.QUEUE)
            createDependency(fromItemId = blocker.id, toItemId = itemB.id)

            // Get next item with limit 10 to see all recommendations
            val result =
                nextItemTool.execute(
                    buildJsonObject { put("limit", JsonPrimitive(10)) },
                    context
                )

            assertTrue((result as JsonObject)["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val recommendations = data["items"]!!.jsonArray

            // B should NOT appear (blocked by dependency); a compact entry carries the 8-hex short id
            val recIds = recommendations.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertFalse(
                recIds.contains(itemB.id.toString().take(8)),
                "Blocked item B should not be in recommendations"
            )

            // C (HIGH priority, complexity 2) should come before A (LOW priority)
            assertTrue(
                recIds.contains(itemC.id.toString().take(8)),
                "Unblocked high-priority item C should be recommended"
            )
            assertTrue(
                recIds.contains(itemA.id.toString().take(8)),
                "Unblocked item A should be recommended"
            )

            // Verify ordering: HIGH priority items before LOW priority
            val cIndex = recIds.indexOf(itemC.id.toString().take(8))
            val aIndex = recIds.indexOf(itemA.id.toString().take(8))
            assertTrue(
                cIndex < aIndex,
                "High priority item C should come before low priority item A"
            )
        }

    // ──────────────────────────────────────────────
    // 8. blocked Reports Both Explicit and Dependency Blocks
    // ──────────────────────────────────────────────

    @Test
    fun `blocked reports both explicit and dependency blocks`(): Unit =
        runBlocking {
            // X: explicitly BLOCKED
            val itemX = createItem("Explicitly Blocked", role = Role.BLOCKED, previousRole = Role.WORK)

            // Y: QUEUE with unsatisfied dependency
            val itemY = createItem("Dependency Blocked")
            val blocker = createItem("Blocker for Y", role = Role.QUEUE)
            createDependency(fromItemId = blocker.id, toItemId = itemY.id)

            val result = blockedItemsTool.execute(buildJsonObject { }, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val blockedItems = data["items"]!!.jsonArray
            val total = data["page"]!!.jsonObject["total"]!!.jsonPrimitive.int

            assertEquals(2, total)

            // Find X (explicit block)
            val xEntry =
                blockedItems.firstOrNull {
                    it.jsonObject["id"]!!.jsonPrimitive.content == itemX.id.toString().take(8)
                }
            assertNotNull(xEntry, "Explicitly blocked item X should be in results")
            assertEquals("explicit", xEntry.jsonObject["blockType"]!!.jsonPrimitive.content)

            // Find Y (dependency block)
            val yEntry =
                blockedItems.firstOrNull {
                    it.jsonObject["id"]!!.jsonPrimitive.content == itemY.id.toString().take(8)
                }
            assertNotNull(yEntry, "Dependency-blocked item Y should be in results")
            assertEquals("dependency", yEntry.jsonObject["blockType"]!!.jsonPrimitive.content)
            assertEquals(1, yEntry.jsonObject["blockers"]!!.jsonArray.count { !it.jsonObject["satisfied"]!!.jsonPrimitive.boolean })
        }

    // ──────────────────────────────────────────────
    // 9. context Ready vs Blocked vs Terminal
    // ──────────────────────────────────────────────

    @Test
    fun `context returns Ready for unblocked item`(): Unit =
        runBlocking {
            val item = createItem("Ready Item")

            val result =
                nextStatusTool.execute(
                    buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
                    context
                )

            val data = extractData(result)
            val gate = data["gateStatus"]!!.jsonObject
            assertEquals(true, gate["canAdvance"]!!.jsonPrimitive.boolean)
            assertEquals("queue", gate["phase"]!!.jsonPrimitive.content)
            assertEquals("work", data["next"]!!.jsonObject["role"]!!.jsonPrimitive.content)
            assertEquals("start", data["next"]!!.jsonObject["trigger"]!!.jsonPrimitive.content)
        }

    @Test
    fun `context returns Blocked for dependency-blocked item`(): Unit =
        runBlocking {
            val item = createItem("Blocked Item")
            val blocker = createItem("Blocker", role = Role.QUEUE)
            createDependency(fromItemId = blocker.id, toItemId = item.id)

            val result =
                nextStatusTool.execute(
                    buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
                    context
                )

            val data = extractData(result)
            val gate = data["gateStatus"]!!.jsonObject
            assertEquals(false, gate["canAdvance"]!!.jsonPrimitive.boolean)
            assertEquals("queue", gate["phase"]!!.jsonPrimitive.content)
            assertEquals("dependency", gate["blockedBy"]!!.jsonPrimitive.content)
            val blockers = gate["blockers"]!!.jsonArray
            assertEquals(1, blockers.size)
            assertEquals(blocker.id.toString(), blockers[0].jsonObject["id"]!!.jsonPrimitive.content)
        }

    @Test
    fun `context returns Terminal for completed item`(): Unit =
        runBlocking {
            val item = createItem("Terminal Item", role = Role.TERMINAL)

            val result =
                nextStatusTool.execute(
                    buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
                    context
                )

            val data = extractData(result)
            val gate = data["gateStatus"]!!.jsonObject
            assertEquals(false, gate["canAdvance"]!!.jsonPrimitive.boolean)
            assertEquals("terminal", gate["phase"]!!.jsonPrimitive.content)
            assertEquals("queue", data["transitions"]!!.jsonObject["reopen"]!!.jsonPrimitive.content)
        }

    @Test
    fun `context returns Blocked with resume suggestion for BLOCKED item`(): Unit =
        runBlocking {
            val item = createItem("Blocked Item", role = Role.BLOCKED, previousRole = Role.WORK)

            val result =
                nextStatusTool.execute(
                    buildJsonObject { put("itemId", JsonPrimitive(item.id.toString())) },
                    context
                )

            val data = extractData(result)
            val gate = data["gateStatus"]!!.jsonObject
            assertEquals("blocked", gate["phase"]!!.jsonPrimitive.content)
            assertEquals("work", data["transitions"]!!.jsonObject["resume"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // 10. Cascade Chain (parent -> grandparent)
    // ──────────────────────────────────────────────

    @Test
    fun `cascade chain advances parent then grandparent`(): Unit =
        runBlocking {
            // Three-level hierarchy: grandparent > parent > children
            // When the last child completes, cascade should propagate:
            //   child2 -> TERMINAL triggers parent -> TERMINAL triggers grandparent -> TERMINAL
            val grandparent = createItem("Grandparent", role = Role.WORK)
            val parent = createItem("Parent", parentId = grandparent.id, role = Role.WORK)
            val child1 = createItem("Child 1", parentId = parent.id, role = Role.WORK)
            val child2 = createItem("Child 2", parentId = parent.id, role = Role.WORK)

            // Complete child1 -- parent should NOT cascade yet
            advance(transitionObj(child1.id, "complete"))
            assertEquals(Role.WORK, getItem(parent.id).role)
            assertEquals(Role.WORK, getItem(grandparent.id).role)

            // Complete child2 -- should trigger multi-level cascade:
            //   parent -> TERMINAL (all children terminal)
            //   grandparent -> TERMINAL (all children terminal, since parent is only child)
            val completeChild2 =
                advance(transitionObj(child2.id, "complete"))

            val cascades = extractResults(completeChild2)[0].jsonObject["cascades"]!!.jsonArray

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
    fun `completing a blocker reports unblocked downstream items`(): Unit =
        runBlocking {
            val itemA = createItem("Item A")
            val itemB = createItem("Item B")
            createDependency(fromItemId = itemA.id, toItemId = itemB.id)

            // Complete A
            val completeA =
                advance(transitionObj(itemA.id, "complete"))

            val resultObj = extractResults(completeA)[0].jsonObject
            assertTransitionApplied(resultObj)

            // Check the unblocked items in per-result scope
            val unblockedItems = resultObj["unblocked"]!!.jsonArray
            assertEquals(1, unblockedItems.size)
            assertEquals(itemB.id.toString(), unblockedItems[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("Item B", unblockedItems[0].jsonObject["title"]!!.jsonPrimitive.content)

            // No top-level aggregate: the unblocked items are per element only.
            val data = advanceData(completeA)
            assertEquals(setOf("results", "summary"), data.keys, "the batch data carries only results and summary")
        }

    // ──────────────────────────────────────────────
    // 12. Full Progression Through All Roles
    // ──────────────────────────────────────────────

    @Test
    fun `item progresses through QUEUE to WORK to TERMINAL via start in schema-free mode`(): Unit =
        runBlocking {
            // In schema-free mode (NoOpWorkItemSchemaService), hasReviewPhase=false,
            // so WORK + start advances directly to TERMINAL (no review phase).
            val item = createItem("Full Progression Item")

            // QUEUE -> WORK
            val start1 =
                advance(transitionObj(item.id, "start"))
            assertEquals("work", extractResults(start1)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
            assertEquals(Role.WORK, getItem(item.id).role)

            // WORK -> TERMINAL (schema-free: no review phase)
            val start2 =
                advance(transitionObj(item.id, "start"))
            assertEquals("terminal", extractResults(start2)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
            assertEquals(Role.TERMINAL, getItem(item.id).role)

            // TERMINAL -> start should fail
            val start3 =
                advance(transitionObj(item.id, "start"))
            assertTransitionFailed(extractResults(start3)[0].jsonObject)
        }

    // ──────────────────────────────────────────────
    // 13. Multiple Dependency Gating
    // ──────────────────────────────────────────────

    @Test
    fun `item blocked by multiple dependencies requires all satisfied`(): Unit =
        runBlocking {
            val itemA = createItem("Blocker A")
            val itemB = createItem("Blocker B")
            val itemC = createItem("Blocked Item")

            createDependency(fromItemId = itemA.id, toItemId = itemC.id)
            createDependency(fromItemId = itemB.id, toItemId = itemC.id)

            // C should be blocked
            val start1 =
                advance(transitionObj(itemC.id, "start"))
            assertTransitionFailed(extractResults(start1)[0].jsonObject)

            // Complete A
            advance(transitionObj(itemA.id, "complete"))

            // C should still be blocked (B is not terminal)
            val start2 =
                advance(transitionObj(itemC.id, "start"))
            assertTransitionFailed(extractResults(start2)[0].jsonObject)

            // Complete B
            advance(transitionObj(itemB.id, "complete"))

            // Now C should be startable
            val start3 =
                advance(transitionObj(itemC.id, "start"))
            assertTransitionApplied(extractResults(start3)[0].jsonObject)
            assertEquals("work", extractResults(start3)[0].jsonObject["newRole"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // 14. Hold Trigger Round-trip (alias for block)
    // ──────────────────────────────────────────────

    @Test
    fun `hold trigger works like block and resume restores from hold`(): Unit =
        runBlocking {
            val item = createItem("Review Item", role = Role.REVIEW)

            // Hold the item
            val holdResult =
                advance(transitionObj(item.id, "hold"))
            val holdRes = extractResults(holdResult)[0].jsonObject
            assertTransitionApplied(holdRes)
            assertEquals("blocked", holdRes["newRole"]!!.jsonPrimitive.content)

            // Verify in DB
            val heldItem = getItem(item.id)
            assertEquals(Role.BLOCKED, heldItem.role)
            assertEquals(Role.REVIEW, heldItem.previousRole)

            // Resume -- should go back to REVIEW
            val resumeResult =
                advance(transitionObj(item.id, "resume"))
            val resumeRes = extractResults(resumeResult)[0].jsonObject
            assertTransitionApplied(resumeRes)
            assertEquals("review", resumeRes["newRole"]!!.jsonPrimitive.content)

            assertEquals(Role.REVIEW, getItem(item.id).role)
        }

    // ──────────────────────────────────────────────
    // 15. Transition and select next Interaction
    // ──────────────────────────────────────────────

    @Test
    fun `completing a blocker makes downstream item appear in next`(): Unit =
        runBlocking {
            val blocker = createItem("Blocker", priority = Priority.HIGH, complexity = 1)
            val downstream = createItem("Downstream", priority = Priority.HIGH, complexity = 2)
            createDependency(fromItemId = blocker.id, toItemId = downstream.id)

            // Initially, only blocker should be recommended (downstream is blocked)
            val result1 =
                nextItemTool.execute(
                    buildJsonObject { put("limit", JsonPrimitive(10)) },
                    context
                )
            val recs1 = (extractData(result1))["items"]!!.jsonArray
            val recIds1 = recs1.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(recIds1.contains(blocker.id.toString().take(8)))
            assertFalse(recIds1.contains(downstream.id.toString().take(8)))

            // Complete the blocker
            advance(transitionObj(blocker.id, "complete"))

            // Now downstream should appear in recommendations (blocker no longer in QUEUE)
            val result2 =
                nextItemTool.execute(
                    buildJsonObject { put("limit", JsonPrimitive(10)) },
                    context
                )
            val recs2 = (extractData(result2))["items"]!!.jsonArray
            val recIds2 = recs2.map { it.jsonObject["id"]!!.jsonPrimitive.content }
            assertTrue(
                recIds2.contains(downstream.id.toString().take(8)),
                "Downstream should now be recommended after blocker completion"
            )
            assertFalse(
                recIds2.contains(blocker.id.toString().take(8)),
                "Completed blocker should no longer be recommended"
            )
        }
}
