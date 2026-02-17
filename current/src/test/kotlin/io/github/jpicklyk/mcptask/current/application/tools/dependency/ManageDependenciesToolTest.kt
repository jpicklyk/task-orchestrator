package io.github.jpicklyk.mcptask.current.application.tools.dependency

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
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
import kotlin.test.*

class ManageDependenciesToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: ManageDependenciesTool
    private lateinit var workItemRepo: WorkItemRepository

    // Pre-created WorkItem IDs for use in dependency tests
    private lateinit var itemA: UUID
    private lateinit var itemB: UUID
    private lateinit var itemC: UUID
    private lateinit var itemD: UUID
    private lateinit var itemE: UUID

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = ManageDependenciesTool()
        workItemRepo = repositoryProvider.workItemRepository()

        // Create 5 WorkItems for dependency tests
        runBlocking {
            itemA = createWorkItem("Item A")
            itemB = createWorkItem("Item B")
            itemC = createWorkItem("Item C")
            itemD = createWorkItem("Item D")
            itemE = createWorkItem("Item E")
        }
    }

    private suspend fun createWorkItem(title: String): UUID {
        val item = WorkItem(title = title)
        val result = workItemRepo.create(item)
        return (result as io.github.jpicklyk.mcptask.current.domain.repository.Result.Success).data.id
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    // ──────────────────────────────────────────────
    // Create via dependencies array
    // ──────────────────────────────────────────────

    @Test
    fun `create single dependency via dependencies array`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)

        val deps = data["dependencies"]!!.jsonArray
        assertEquals(1, deps.size)
        val dep = deps[0] as JsonObject
        assertEquals(itemA.toString(), dep["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(itemB.toString(), dep["toItemId"]!!.jsonPrimitive.content)
        assertEquals("BLOCKS", dep["type"]!!.jsonPrimitive.content)
        assertNull(dep["unblockAt"]) // null by default, not serialized
    }

    @Test
    fun `create multiple dependencies via array (batch)`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    },
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemC.toString()))
                        put("toItemId", JsonPrimitive(itemD.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["created"]!!.jsonPrimitive.int)
        assertEquals(2, data["dependencies"]!!.jsonArray.size)
    }

    @Test
    fun `create with explicit type and unblockAt`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("IS_BLOCKED_BY"))
                        put("unblockAt", JsonPrimitive("work"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["created"]!!.jsonPrimitive.int)
        val dep = data["dependencies"]!!.jsonArray[0] as JsonObject
        assertEquals("IS_BLOCKED_BY", dep["type"]!!.jsonPrimitive.content)
        assertEquals("work", dep["unblockAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create with shared default type`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "type" to JsonPrimitive("RELATES_TO"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    },
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemC.toString()))
                        put("toItemId", JsonPrimitive(itemD.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(2, data["created"]!!.jsonPrimitive.int)
        val deps = data["dependencies"]!!.jsonArray
        assertEquals("RELATES_TO", (deps[0] as JsonObject)["type"]!!.jsonPrimitive.content)
        assertEquals("RELATES_TO", (deps[1] as JsonObject)["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create with shared default unblockAt`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "unblockAt" to JsonPrimitive("review"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val dep = (result["data"] as JsonObject)["dependencies"]!!.jsonArray[0] as JsonObject
        assertEquals("review", dep["unblockAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create per-dep type overrides shared default`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "type" to JsonPrimitive("RELATES_TO"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("BLOCKS"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val dep = (result["data"] as JsonObject)["dependencies"]!!.jsonArray[0] as JsonObject
        assertEquals("BLOCKS", dep["type"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Create via pattern
    // ──────────────────────────────────────────────

    @Test
    fun `create via linear pattern`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "itemIds" to JsonArray(listOf(
                    JsonPrimitive(itemA.toString()),
                    JsonPrimitive(itemB.toString()),
                    JsonPrimitive(itemC.toString()),
                    JsonPrimitive(itemD.toString())
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["created"]!!.jsonPrimitive.int) // A→B, B→C, C→D

        val deps = data["dependencies"]!!.jsonArray
        assertEquals(3, deps.size)
        // Verify chain order
        assertEquals(itemA.toString(), (deps[0] as JsonObject)["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(itemB.toString(), (deps[0] as JsonObject)["toItemId"]!!.jsonPrimitive.content)
        assertEquals(itemB.toString(), (deps[1] as JsonObject)["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(itemC.toString(), (deps[1] as JsonObject)["toItemId"]!!.jsonPrimitive.content)
        assertEquals(itemC.toString(), (deps[2] as JsonObject)["fromItemId"]!!.jsonPrimitive.content)
        assertEquals(itemD.toString(), (deps[2] as JsonObject)["toItemId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `create via fan-out pattern`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(itemA.toString()),
                "targets" to JsonArray(listOf(
                    JsonPrimitive(itemB.toString()),
                    JsonPrimitive(itemC.toString()),
                    JsonPrimitive(itemD.toString())
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["created"]!!.jsonPrimitive.int) // A→B, A→C, A→D

        val deps = data["dependencies"]!!.jsonArray
        for (dep in deps) {
            assertEquals(itemA.toString(), (dep as JsonObject)["fromItemId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `create via fan-in pattern`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-in"),
                "sources" to JsonArray(listOf(
                    JsonPrimitive(itemB.toString()),
                    JsonPrimitive(itemC.toString()),
                    JsonPrimitive(itemD.toString())
                )),
                "target" to JsonPrimitive(itemE.toString())
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["created"]!!.jsonPrimitive.int) // B→E, C→E, D→E

        val deps = data["dependencies"]!!.jsonArray
        for (dep in deps) {
            assertEquals(itemE.toString(), (dep as JsonObject)["toItemId"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `create via pattern with shared type`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("linear"),
                "type" to JsonPrimitive("IS_BLOCKED_BY"),
                "itemIds" to JsonArray(listOf(
                    JsonPrimitive(itemA.toString()),
                    JsonPrimitive(itemB.toString()),
                    JsonPrimitive(itemC.toString())
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val deps = (result["data"] as JsonObject)["dependencies"]!!.jsonArray
        for (dep in deps) {
            assertEquals("IS_BLOCKED_BY", (dep as JsonObject)["type"]!!.jsonPrimitive.content)
        }
    }

    // ──────────────────────────────────────────────
    // Create failure cases
    // ──────────────────────────────────────────────

    @Test
    fun `create fails on cycle detection`(): Unit = runBlocking {
        // First create A→B
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        )

        // Now try B→A (cycle)
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemB.toString()))
                        put("toItemId", JsonPrimitive(itemA.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("circular", ignoreCase = true))
    }

    @Test
    fun `create fails on duplicate dependency`(): Unit = runBlocking {
        // Create A→B
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        )

        // Try to create A→B again
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("already exists", ignoreCase = true))
    }

    @Test
    fun `create fails with invalid UUID`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive("not-a-uuid"))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("UUID", ignoreCase = true))
    }

    @Test
    fun `create fails with self-reference`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemA.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("same item", ignoreCase = true))
    }

    @Test
    fun `create RELATES_TO with unblockAt returns validation error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("RELATES_TO"))
                        put("unblockAt", JsonPrimitive("work"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("RELATES_TO", ignoreCase = true))
    }

    @Test
    fun `create with invalid dependency type`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("INVALID_TYPE"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("invalid type", ignoreCase = true))
    }

    @Test
    fun `create batch fails atomically on cycle within batch`(): Unit = runBlocking {
        // Try to create A→B and B→A in one batch (cycle)
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    },
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemB.toString()))
                        put("toItemId", JsonPrimitive(itemA.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        val error = result["error"] as JsonObject
        assertTrue(error["message"]!!.jsonPrimitive.content.contains("circular", ignoreCase = true))

        // Verify nothing was created (atomic)
        val deps = context.dependencyRepository().findByItemId(itemA)
        assertTrue(deps.isEmpty())
    }

    @Test
    fun `create with invalid shared type returns error`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "type" to JsonPrimitive("WRONG"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(result["error"]!!.jsonObject["message"]!!.jsonPrimitive.content.contains("Invalid dependency type"))
    }

    // ──────────────────────────────────────────────
    // Delete operations
    // ──────────────────────────────────────────────

    @Test
    fun `delete by dependency ID`(): Unit = runBlocking {
        // Create a dependency first
        val createResult = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject
        val depId = (createResult["data"] as JsonObject)["dependencies"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Delete by ID
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(depId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)

        // Verify it's gone
        val remaining = context.dependencyRepository().findByItemId(itemA)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `delete by fromItemId and toItemId`(): Unit = runBlocking {
        // Create dependency
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        )

        // Delete by relationship
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "fromItemId" to JsonPrimitive(itemA.toString()),
                "toItemId" to JsonPrimitive(itemB.toString())
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete by relationship with type filter`(): Unit = runBlocking {
        // Create BLOCKS and RELATES_TO deps between same items
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("BLOCKS"))
                    },
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                        put("type", JsonPrimitive("RELATES_TO"))
                    }
                ))
            ),
            context
        )

        // Delete only BLOCKS
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "fromItemId" to JsonPrimitive(itemA.toString()),
                "toItemId" to JsonPrimitive(itemB.toString()),
                "type" to JsonPrimitive("BLOCKS")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, (result["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)

        // RELATES_TO should still exist
        val remaining = context.dependencyRepository().findByFromItemId(itemA)
        assertEquals(1, remaining.size)
        assertEquals(io.github.jpicklyk.mcptask.current.domain.model.DependencyType.RELATES_TO, remaining[0].type)
    }

    @Test
    fun `delete all dependencies for an item`(): Unit = runBlocking {
        // Create multiple deps involving itemA
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "pattern" to JsonPrimitive("fan-out"),
                "source" to JsonPrimitive(itemA.toString()),
                "targets" to JsonArray(listOf(
                    JsonPrimitive(itemB.toString()),
                    JsonPrimitive(itemC.toString())
                ))
            ),
            context
        )

        // Also create an incoming dep
        tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemD.toString()))
                        put("toItemId", JsonPrimitive(itemA.toString()))
                    }
                ))
            ),
            context
        )

        // Delete all for itemA
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "fromItemId" to JsonPrimitive(itemA.toString()),
                "deleteAll" to JsonPrimitive(true)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["deleted"]!!.jsonPrimitive.int) // 2 outgoing + 1 incoming

        // Verify all are gone
        val remaining = context.dependencyRepository().findByItemId(itemA)
        assertTrue(remaining.isEmpty())
    }

    @Test
    fun `delete non-existent dependency by ID`(): Unit = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "id" to JsonPrimitive(randomId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete by relationship with no matching deps`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "fromItemId" to JsonPrimitive(itemA.toString()),
                "toItemId" to JsonPrimitive(itemB.toString())
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(0, (result["data"] as JsonObject)["deleted"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Validation (validateParams)
    // ──────────────────────────────────────────────

    @Test
    fun `invalid operation throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("invalid"))
            )
        }
    }

    @Test
    fun `create missing both dependencies and pattern throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("create"))
            )
        }
    }

    @Test
    fun `create with both dependencies and pattern throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "dependencies" to JsonArray(listOf(buildJsonObject { put("test", JsonPrimitive("val")) })),
                    "pattern" to JsonPrimitive("linear")
                )
            )
        }
    }

    @Test
    fun `create with empty dependencies array throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "dependencies" to JsonArray(emptyList())
                )
            )
        }
    }

    @Test
    fun `linear pattern without itemIds throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("linear")
                )
            )
        }
    }

    @Test
    fun `linear pattern with only 1 itemId throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("linear"),
                    "itemIds" to JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString())))
                )
            )
        }
    }

    @Test
    fun `fan-out pattern without source throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("fan-out"),
                    "targets" to JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString())))
                )
            )
        }
    }

    @Test
    fun `fan-out pattern without targets throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("fan-out"),
                    "source" to JsonPrimitive(UUID.randomUUID().toString())
                )
            )
        }
    }

    @Test
    fun `fan-in pattern without sources throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("fan-in"),
                    "target" to JsonPrimitive(UUID.randomUUID().toString())
                )
            )
        }
    }

    @Test
    fun `fan-in pattern without target throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("fan-in"),
                    "sources" to JsonArray(listOf(JsonPrimitive(UUID.randomUUID().toString())))
                )
            )
        }
    }

    @Test
    fun `invalid pattern name throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("create"),
                    "pattern" to JsonPrimitive("star")
                )
            )
        }
    }

    @Test
    fun `delete with no params throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("delete"))
            )
        }
    }

    @Test
    fun `delete with deleteAll but no item ID throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("delete"),
                    "deleteAll" to JsonPrimitive(true)
                )
            )
        }
    }

    @Test
    fun `delete with only fromItemId and no deleteAll throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("delete"),
                    "fromItemId" to JsonPrimitive(UUID.randomUUID().toString())
                )
            )
        }
    }

    // ──────────────────────────────────────────────
    // userSummary
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary for create`(): Unit = runBlocking {
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("create"),
                "dependencies" to JsonArray(listOf(
                    buildJsonObject {
                        put("fromItemId", JsonPrimitive(itemA.toString()))
                        put("toItemId", JsonPrimitive(itemB.toString()))
                    }
                ))
            ),
            context
        ) as JsonObject

        val summary = tool.userSummary(
            params("operation" to JsonPrimitive("create")),
            result,
            isError = false
        )
        assertEquals("Created 1 dependency(ies)", summary)
    }

    @Test
    fun `userSummary for delete`(): Unit = runBlocking {
        val summary = tool.userSummary(
            params("operation" to JsonPrimitive("delete")),
            buildJsonObject {
                put("success", JsonPrimitive(true))
                put("data", buildJsonObject { put("deleted", JsonPrimitive(3)) })
            },
            isError = false
        )
        assertEquals("Deleted 3 dependency(ies)", summary)
    }

    @Test
    fun `userSummary for error`() {
        val summary = tool.userSummary(
            params("operation" to JsonPrimitive("create")),
            buildJsonObject { put("success", JsonPrimitive(false)) },
            isError = true
        )
        assertEquals("manage_dependencies(create) failed", summary)
    }
}
