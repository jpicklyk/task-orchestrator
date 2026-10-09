package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.dependency.ManageDependenciesTool
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test authorship for item a19fcf0a (P12, needs-test-author): `create_work_tree` and `complete_tree`
 * after dependency direction normalization. Scenarios S3, S9 and S17 of the frozen test-plan, plus probes.
 *
 * Oracles (frozen plan 3.4 and task-scope): `x IS_BLOCKED_BY y` means y blocks x and is stored as `y BLOCKS x`;
 * the create_work_tree response lists the stored (normalized) edges as BLOCKS with the refs swapped; a restatement
 * (`x BLOCKS y` plus `y IS_BLOCKED_BY x`) is a duplicate, never a cycle; `x BLOCKS y` plus `x IS_BLOCKED_BY y` closes
 * a cycle; both rejections persist nothing, and the cycle message keeps the form
 * "Circular dependency detected involving ref '<ref>'"; complete_tree processes a blocker before what it blocks, with
 * an IS_BLOCKED_BY input oriented the same way as BLOCKS.
 *
 * Response-shape evidence (public, not implementation): `CreateWorkTreeExecuteCharacterizationTest` (data.children[].id,
 * data.dependencies[].fromRef / toRef / type, error.message), `DependencyDirectionTreeToolsTest` and
 * `CompleteTreeToolTest` (results[].itemId / applied, summary.completed). `create_work_tree` execute does not run
 * validateParams (per that file's KDoc), so every params object below is already valid.
 *
 * EXISTING-SURFACE: all calls go through tool.execute with real SQLite.
 */
class DependencyNormalizationTreeToolsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext
    private lateinit var createTool: CreateWorkTreeTool
    private lateinit var completeTool: CompleteTreeTool
    private lateinit var manageDeps: ManageDependenciesTool

    @BeforeEach
    fun setUp() {
        context = ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork())
        createTool = CreateWorkTreeTool()
        completeTool = CompleteTreeTool()
        manageDeps = ManageDependenciesTool()
    }

    private fun child(
        ref: String,
        title: String
    ): JsonObject =
        buildJsonObject {
            put("ref", JsonPrimitive(ref))
            put("title", JsonPrimitive(title))
        }

    private fun dep(
        from: String,
        to: String,
        type: String,
        unblockAt: String? = null
    ): JsonObject =
        buildJsonObject {
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
            put("type", JsonPrimitive(type))
            if (unblockAt != null) put("unblockAt", JsonPrimitive(unblockAt))
        }

    private fun treeParams(
        rootTitle: String,
        refs: List<String>,
        vararg deps: JsonObject
    ): JsonObject =
        buildJsonObject {
            put("root", buildJsonObject { put("title", JsonPrimitive(rootTitle)) })
            put("children", buildJsonArray { refs.forEach { add(child(it, "Child $it")) } })
            put("deps", buildJsonArray { deps.forEach { add(it) } })
        }

    private suspend fun titlesInDb(): List<String> =
        context
            .workItemRepository()
            .findByFilters()
            .items
            .map { it.title }

    private fun errorMessage(result: JsonObject): String {
        assertFalse(result["success"]!!.jsonPrimitive.boolean, "expected a failure envelope, got: $result")
        return result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }

    // ------------------------------------------------------------------
    // S3: create_work_tree stores an IS_BLOCKED_BY dep as a swapped BLOCKS row
    // ------------------------------------------------------------------

    @Test
    fun `S3 create_work_tree stores x IS_BLOCKED_BY y as y BLOCKS x and the response lists the stored edge`(): Unit =
        runBlocking {
            val params = treeParams("S3 Root", listOf("x", "y"), dep("x", "y", "IS_BLOCKED_BY", unblockAt = "work"))

            val result = createTool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            val depsOut = data["dependencies"]!!.jsonArray
            assertEquals(1, depsOut.size)
            val out = depsOut.single().jsonObject
            assertEquals("BLOCKS", out["type"]!!.jsonPrimitive.content)
            assertEquals("y", out["fromRef"]!!.jsonPrimitive.content, "y blocks x")
            assertEquals("x", out["toRef"]!!.jsonPrimitive.content)
            assertEquals("work", out["unblockAt"]!!.jsonPrimitive.content)

            val children = data["children"]!!.jsonArray
            val xId = UUID.fromString(children[0].jsonObject["id"]!!.jsonPrimitive.content)
            val yId = UUID.fromString(children[1].jsonObject["id"]!!.jsonPrimitive.content)
            val rows = context.dependencyRepository().findByItemId(xId)
            assertEquals(1, rows.size, "exactly one stored row: $rows")
            assertEquals(Triple(yId, xId, DependencyType.BLOCKS), Triple(rows[0].fromItemId, rows[0].toItemId, rows[0].type))
            assertEquals("work", rows[0].unblockAt)
        }

    @Test
    fun `S3 probe a BLOCKS dep and a RELATES_TO dep in the same tree are stored as given`(): Unit =
        runBlocking {
            val params = treeParams("S3 Probe Root", listOf("x", "y", "z"), dep("x", "y", "BLOCKS"), dep("y", "z", "RELATES_TO"))

            val result = createTool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            val ids = data["children"]!!.jsonArray.map { UUID.fromString(it.jsonObject["id"]!!.jsonPrimitive.content) }
            val rows = context.dependencyRepository().findByItemId(ids[1])
            val edges = rows.map { Triple(it.fromItemId, it.toItemId, it.type) }.toSet()
            assertEquals(setOf(Triple(ids[0], ids[1], DependencyType.BLOCKS), Triple(ids[1], ids[2], DependencyType.RELATES_TO)), edges)
        }

    // ------------------------------------------------------------------
    // S9: create_work_tree duplicate and cycle policy over normalized edges
    // ------------------------------------------------------------------

    @Test
    fun `S9 x BLOCKS y plus x IS_BLOCKED_BY y is a cycle that persists nothing`(): Unit =
        runBlocking {
            val params = treeParams("S9 Cycle Root", listOf("x", "y"), dep("x", "y", "BLOCKS"), dep("x", "y", "IS_BLOCKED_BY"))

            val result = createTool.execute(params, context) as JsonObject

            val message = errorMessage(result)
            assertTrue(message.contains("Circular dependency detected involving ref '", ignoreCase = false), "got: $message")
            assertTrue(message.contains("'x'") || message.contains("'y'"), "the message names one of the cycle refs: $message")
            assertFalse(message.contains("Duplicate", ignoreCase = true), "a reversal is a cycle, not a duplicate: $message")
            assertFalse("S9 Cycle Root" in titlesInDb(), "nothing may be persisted: ${titlesInDb()}")
            assertEquals(0, titlesInDb().count { it.startsWith("Child ") })
        }

    @Test
    fun `S9 x BLOCKS y plus y IS_BLOCKED_BY x is a duplicate that persists nothing`(): Unit =
        runBlocking {
            val params = treeParams("S9 Dup Root", listOf("x", "y"), dep("x", "y", "BLOCKS"), dep("y", "x", "IS_BLOCKED_BY"))

            val result = createTool.execute(params, context) as JsonObject

            val message = errorMessage(result)
            assertTrue(message.contains("Duplicate dependency", ignoreCase = true), "got: $message")
            assertFalse(message.contains("Circular", ignoreCase = true), "a restatement is never a cycle: $message")
            assertFalse("S9 Dup Root" in titlesInDb(), "nothing may be persisted: ${titlesInDb()}")
            assertEquals(0, titlesInDb().count { it.startsWith("Child ") })
        }

    @Test
    fun `S9 the same restatement in the opposite order is also a duplicate`(): Unit =
        runBlocking {
            val params = treeParams("S9 Dup Order Root", listOf("x", "y"), dep("y", "x", "IS_BLOCKED_BY"), dep("x", "y", "BLOCKS"))

            val result = createTool.execute(params, context) as JsonObject

            assertTrue(errorMessage(result).contains("Duplicate dependency", ignoreCase = true))
            assertFalse("S9 Dup Order Root" in titlesInDb())
        }

    @Test
    fun `S9 probe a three-ref cycle closed by an IS_BLOCKED_BY dep is rejected naming a ref`(): Unit =
        runBlocking {
            // x blocks y, y blocks z (z IS_BLOCKED_BY y), z blocks x
            val params =
                treeParams(
                    "S9 Triangle Root",
                    listOf("x", "y", "z"),
                    dep("x", "y", "BLOCKS"),
                    dep("z", "y", "IS_BLOCKED_BY"),
                    dep("z", "x", "BLOCKS")
                )

            val result = createTool.execute(params, context) as JsonObject

            val message = errorMessage(result)
            assertTrue(Regex("Circular dependency detected involving ref '[xyz]'").containsMatchIn(message), "got: $message")
            assertFalse("S9 Triangle Root" in titlesInDb())
        }

    @Test
    fun `S9 probe RELATES_TO between the same refs as a BLOCKS dep is neither a duplicate nor a cycle`(): Unit =
        runBlocking {
            val params = treeParams("S9 Relates Root", listOf("x", "y"), dep("x", "y", "BLOCKS"), dep("y", "x", "RELATES_TO"))

            val result = createTool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            assertEquals(2, (result["data"] as JsonObject)["dependencies"]!!.jsonArray.size)
        }

    // ------------------------------------------------------------------
    // S17: complete_tree orders an IS_BLOCKED_BY dep like a BLOCKS dep
    // ------------------------------------------------------------------

    private suspend fun queueItem(title: String): UUID = context.workItemRepository().create(WorkItem(title = title)).id

    private fun completeParams(itemIds: List<UUID>): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it.toString())) } })
            put("trigger", JsonPrimitive("complete"))
        }

    private suspend fun createDeps(vararg pairs: Pair<String, JsonElement>) {
        val result =
            manageDeps.execute(
                JsonObject(mapOf<String, JsonElement>("operation" to JsonPrimitive("create"), *pairs)),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "dependency setup failed: $result")
        assertTrue((result["data"] as JsonObject)["created"]!!.jsonPrimitive.int > 0, "dependency setup created nothing: $result")
    }

    @Test
    fun `S17 complete_tree completes the blocker of an IS_BLOCKED_BY pair first even when the blocked item is listed first`(): Unit =
        runBlocking {
            val blocked = queueItem("Blocked")
            val blocker = queueItem("Blocker")
            // blocked IS_BLOCKED_BY blocker: blocker blocks blocked
            createDeps(
                "dependencies" to
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("fromItemId", JsonPrimitive(blocked.toString()))
                                put("toItemId", JsonPrimitive(blocker.toString()))
                                put("type", JsonPrimitive("IS_BLOCKED_BY"))
                            }
                        )
                    )
            )

            val result = completeTool.execute(completeParams(listOf(blocked, blocker)), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val data = result["data"] as JsonObject
            val results = data["results"]!!.jsonArray
            val order = results.map { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) }
            assertTrue(order.indexOf(blocker) < order.indexOf(blocked), "the blocker must be processed first: $order")
            results.forEach { assertTrue(it.jsonObject["applied"]!!.jsonPrimitive.boolean, "both items complete: $it") }
            assertEquals(2, data["summary"]!!.jsonObject["completed"]!!.jsonPrimitive.int)
        }

    @Test
    fun `S17 probe a linear IS_BLOCKED_BY chain listed in reverse completes entirely, blockers first`(): Unit =
        runBlocking {
            val a = queueItem("A")
            val b = queueItem("B")
            val c = queueItem("C")
            // a is blocked by b, b is blocked by c: c blocks b blocks a
            createDeps(
                "pattern" to JsonPrimitive("linear"),
                "type" to JsonPrimitive("IS_BLOCKED_BY"),
                "itemIds" to JsonArray(listOf(a, b, c).map { JsonPrimitive(it.toString()) })
            )

            val result = completeTool.execute(completeParams(listOf(a, b, c)), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val results = (result["data"] as JsonObject)["results"]!!.jsonArray
            val order = results.map { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) }
            assertEquals(listOf(c, b, a), order, "blockers first")
            results.forEach { assertTrue(it.jsonObject["applied"]!!.jsonPrimitive.boolean, "each item completes: $it") }
        }

    @Test
    fun `S17 probe an out-of-set IS_BLOCKED_BY blocker still blocks its target`(): Unit =
        runBlocking {
            val target = queueItem("Target")
            val outsider = queueItem("Outsider") // not in the completion set, stays in queue
            createDeps(
                "dependencies" to
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("fromItemId", JsonPrimitive(target.toString()))
                                put("toItemId", JsonPrimitive(outsider.toString()))
                                put("type", JsonPrimitive("IS_BLOCKED_BY"))
                            }
                        )
                    )
            )

            val result = completeTool.execute(completeParams(listOf(target)), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val only = (result["data"] as JsonObject)["results"]!!.jsonArray.single().jsonObject
            assertFalse(only["applied"]!!.jsonPrimitive.boolean, "the open outside blocker keeps the target from completing: $only")
        }
}
