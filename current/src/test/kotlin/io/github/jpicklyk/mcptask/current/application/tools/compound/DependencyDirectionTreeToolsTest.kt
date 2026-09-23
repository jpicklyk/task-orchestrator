package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blind test-author coverage for item 2aa67b28 (inverted IS_BLOCKED_BY cycle-check fix):
 * [CompleteTreeTool] ordering/gating (S4, S13) and [CreateWorkTreeTool] in-memory cycle
 * detection (S3, S12), plus two adversarial probes.
 *
 * Oracles: [C] api-reference complete_tree topological order + failure-skip semantics;
 * [R] RELATES_TO has no blocking semantics; [G] a directed cycle over blocker->blocked edges is
 * rejected; [Dc] diagnosis decision (c) — orient every in-set edge via blocker/blocked
 * accessors, skip RELATES_TO; [Db] decision (b) — restatement as a separate row is not a cycle.
 *
 * Real H2 + [DefaultRepositoryProvider] throughout (real dependency graph / real
 * [io.github.jpicklyk.mcptask.current.infrastructure.service.SQLiteWorkTreeService]) — the
 * MockK-based sibling test files in this package are not usable here since S4/S13/S3/S12 need
 * true cycle/topological behavior over real dependency edges.
 */
class DependencyDirectionTreeToolsTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepository: WorkItemRepository
    private lateinit var depRepository: DependencyRepository
    private lateinit var completeTreeTool: CompleteTreeTool
    private lateinit var createWorkTreeTool: CreateWorkTreeTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        workItemRepository = repositoryProvider.workItemRepository()
        depRepository = repositoryProvider.dependencyRepository()
        completeTreeTool = CompleteTreeTool()
        createWorkTreeTool = CreateWorkTreeTool()
    }

    private suspend fun createItem(
        title: String,
        role: Role = Role.QUEUE
    ): UUID {
        val result = workItemRepository.create(WorkItem(title = title, role = role))
        return (result as Result.Success).data.id
    }

    private fun buildItemIdsParams(
        itemIds: List<UUID>,
        trigger: String = "complete"
    ): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it.toString())) } })
            put("trigger", JsonPrimitive(trigger))
        }

    private fun depSpecJson(
        from: String,
        to: String,
        type: String
    ): JsonObject =
        buildJsonObject {
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
            put("type", JsonPrimitive(type))
        }

    private fun childSpecJson(
        ref: String,
        title: String
    ): JsonObject =
        buildJsonObject {
            put("ref", JsonPrimitive(ref))
            put("title", JsonPrimitive(title))
        }

    // ──────────────────────────────────────────────
    // Happy / edge — complete_tree
    // ──────────────────────────────────────────────

    @Test
    fun `S4 complete_tree completes an IS_BLOCKED_BY pair with the real blocker first`(): Unit =
        runBlocking {
            val idA = createItem("A")
            val idB = createItem("B")

            // A IS_BLOCKED_BY B => B blocks A
            depRepository.create(Dependency(fromItemId = idA, toItemId = idB, type = DependencyType.IS_BLOCKED_BY))

            val result = completeTreeTool.execute(buildItemIdsParams(listOf(idA, idB)), context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success: $result")
            val data = result["data"] as JsonObject
            val results = data["results"]!!.jsonArray

            val order = results.map { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) }
            assertTrue(order.indexOf(idB) < order.indexOf(idA), "B (the real blocker) must be processed before A")

            results.forEach { r -> assertTrue(r.jsonObject["applied"]!!.jsonPrimitive.boolean, "Both items should complete: $r") }

            val summary = data["summary"]!!.jsonObject
            assertEquals(2, summary["completed"]!!.jsonPrimitive.int)
        }

    @Test
    fun `S13 complete_tree - out-of-set BLOCKS blocks its target, in-set RELATES_TO does not`(): Unit =
        runBlocking {
            val idE = createItem("E") // outside target set, left QUEUE (non-terminal)
            val idX = createItem("X")
            val idY = createItem("Y")

            depRepository.create(Dependency(fromItemId = idE, toItemId = idX, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = idX, toItemId = idY, type = DependencyType.RELATES_TO))

            val result = completeTreeTool.execute(buildItemIdsParams(listOf(idX, idY)), context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success envelope: $result")
            val data = result["data"] as JsonObject
            val resultMap =
                data["results"]!!.jsonArray.associate {
                    val obj = it.jsonObject
                    UUID.fromString(obj["itemId"]!!.jsonPrimitive.content) to obj
                }

            assertFalse(resultMap[idX]!!["applied"]!!.jsonPrimitive.boolean, "X is blocked by out-of-set E and must not apply")
            assertTrue(resultMap[idY]!!["applied"]!!.jsonPrimitive.boolean, "Y has only a RELATES_TO edge and must complete")
        }

    @Test
    fun `probe complete_tree diamond with mixed BLOCKS and IS_BLOCKED_BY orders blockers first`(): Unit =
        runBlocking {
            val idW = createItem("W")
            val idX = createItem("X")
            val idY = createItem("Y")
            val idZ = createItem("Z")

            // W blocks X (direct BLOCKS) and W blocks Y (restated as Y IS_BLOCKED_BY W)
            depRepository.create(Dependency(fromItemId = idW, toItemId = idX, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = idY, toItemId = idW, type = DependencyType.IS_BLOCKED_BY))
            // X blocks Z (direct BLOCKS) and Y blocks Z (restated as Z IS_BLOCKED_BY Y)
            depRepository.create(Dependency(fromItemId = idX, toItemId = idZ, type = DependencyType.BLOCKS))
            depRepository.create(Dependency(fromItemId = idZ, toItemId = idY, type = DependencyType.IS_BLOCKED_BY))

            val result = completeTreeTool.execute(buildItemIdsParams(listOf(idW, idX, idY, idZ)), context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Expected success: $result")
            val data = result["data"] as JsonObject
            val results = data["results"]!!.jsonArray
            results.forEach { r -> assertTrue(r.jsonObject["applied"]!!.jsonPrimitive.boolean, "All diamond items should complete: $r") }

            val order = results.map { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) }
            assertTrue(order.indexOf(idW) < order.indexOf(idX), "W must precede X")
            assertTrue(order.indexOf(idW) < order.indexOf(idY), "W must precede Y")
            assertTrue(order.indexOf(idX) < order.indexOf(idZ), "X must precede Z")
            assertTrue(order.indexOf(idY) < order.indexOf(idZ), "Y must precede Z")

            val summary = data["summary"]!!.jsonObject
            assertEquals(4, summary["completed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Happy / failure — create_work_tree
    // ──────────────────────────────────────────────

    @Test
    fun `S3 create_work_tree accepts BLOCKS plus a restating IS_BLOCKED_BY between the same pair`(): Unit =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpecJson("x", "X"))
                            add(childSpecJson("y", "Y"))
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(depSpecJson("x", "y", "BLOCKS"))
                            add(depSpecJson("y", "x", "IS_BLOCKED_BY"))
                        }
                    )
                }

            val result = createWorkTreeTool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "Restated non-cyclic edge must succeed: $result")
            val data = result["data"] as JsonObject
            assertEquals(2, data["dependencies"]!!.jsonArray.size)
        }

    @Test
    fun `S12 create_work_tree rejects a real BLOCKS plus IS_BLOCKED_BY cycle and persists nothing`(): Unit =
        runBlocking {
            val existingRootId = createItem("S12 Attach Root")

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingRootId.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpecJson("x", "X"))
                            add(childSpecJson("y", "Y"))
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(depSpecJson("x", "y", "BLOCKS"))
                            add(depSpecJson("x", "y", "IS_BLOCKED_BY"))
                        }
                    )
                }

            val result = createWorkTreeTool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "A real cycle must be rejected: $result")
            val message = result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(
                message.contains("Circular dependency detected", ignoreCase = true),
                "Expected the detectInMemoryCycle message, got: $message"
            )

            val descendants = (workItemRepository.findDescendants(existingRootId) as Result.Success).data
            assertTrue(descendants.isEmpty(), "Cycle detection runs before any insert — no children should be persisted")
        }

    @Test
    fun `probe attach-mode create_work_tree detects a cycle through the reserved root ref`(): Unit =
        runBlocking {
            val existingRootId = createItem("Attach Root")

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingRootId.toString())) })
                    put(
                        "children",
                        buildJsonArray { add(childSpecJson("c1", "C1")) }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(depSpecJson(CreateWorkTreeTool.ROOT_REF, "c1", "BLOCKS"))
                            add(depSpecJson(CreateWorkTreeTool.ROOT_REF, "c1", "IS_BLOCKED_BY"))
                        }
                    )
                }

            val result = createWorkTreeTool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "A cycle through the root ref must be rejected: $result")
            val message = result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(
                message.contains("Circular dependency detected", ignoreCase = true),
                "Expected the detectInMemoryCycle message, got: $message"
            )

            val descendants = (workItemRepository.findDescendants(existingRootId) as Result.Success).data
            assertTrue(descendants.isEmpty(), "Nothing should be persisted once a cycle is detected")
        }
}
