package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
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

class QueryNotesToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryNotesTool
    private lateinit var manageTool: ManageNotesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = QueryNotesTool()
        manageTool = ManageNotesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    /**
     * Helper to create a work item directly via repository and return its UUID string.
     */
    private suspend fun createTestItem(title: String = "Test Item"): String {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return ((result as Result.Success).data.id).toString()
    }

    /**
     * Helper to create a note via ManageNotesTool and return its ID.
     */
    private suspend fun createNote(itemId: String, key: String, role: String, body: String = ""): String {
        val result = manageTool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive(key))
                        put("role", JsonPrimitive(role))
                        if (body.isNotEmpty()) put("body", JsonPrimitive(body))
                    }
                ))
            ),
            context
        ) as JsonObject
        return (result["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content
    }

    // ──────────────────────────────────────────────
    // Get operations
    // ──────────────────────────────────────────────

    @Test
    fun `get note by id`(): Unit = runBlocking {
        val itemId = createTestItem()
        val noteId = createNote(itemId, "approach", "work", "Approach body text")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(noteId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(noteId, data["id"]!!.jsonPrimitive.content)
        assertEquals(itemId, data["itemId"]!!.jsonPrimitive.content)
        assertEquals("approach", data["key"]!!.jsonPrimitive.content)
        assertEquals("work", data["role"]!!.jsonPrimitive.content)
        assertEquals("Approach body text", data["body"]!!.jsonPrimitive.content)
        assertNotNull(data["createdAt"])
        assertNotNull(data["modifiedAt"])
    }

    @Test
    fun `get nonexistent note returns error`(): Unit = runBlocking {
        val randomId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("get"),
                "id" to JsonPrimitive(randomId)
            ),
            context
        ) as JsonObject

        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertNotNull(result["error"])
    }

    // ──────────────────────────────────────────────
    // List operations
    // ──────────────────────────────────────────────

    @Test
    fun `list notes for item`() = runBlocking {
        val itemId = createTestItem()
        createNote(itemId, "plan", "queue")
        createNote(itemId, "approach", "work")
        createNote(itemId, "verification", "review")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(3, data["total"]!!.jsonPrimitive.int)
        assertEquals(3, data["notes"]!!.jsonArray.size)
    }

    @Test
    fun `list notes with role filter`() = runBlocking {
        val itemId = createTestItem()
        createNote(itemId, "plan", "queue")
        createNote(itemId, "approach", "work")
        createNote(itemId, "verification", "review")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId),
                "role" to JsonPrimitive("work")
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["total"]!!.jsonPrimitive.int)
        val notes = data["notes"]!!.jsonArray
        assertEquals("work", notes[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list notes with includeBody false omits body`(): Unit = runBlocking {
        val itemId = createTestItem()
        createNote(itemId, "plan", "queue", "This body should not appear")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId),
                "includeBody" to JsonPrimitive(false)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0] as JsonObject
        assertNull(note["body"])
        assertNotNull(note["id"])
        assertNotNull(note["key"])
        assertNotNull(note["role"])
    }

    @Test
    fun `list notes with includeBody true includes body`() = runBlocking {
        val itemId = createTestItem()
        createNote(itemId, "plan", "queue", "This body should appear")

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId),
                "includeBody" to JsonPrimitive(true)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0] as JsonObject
        assertEquals("This body should appear", note["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `list notes for item with no notes`() = runBlocking {
        val itemId = createTestItem()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["total"]!!.jsonPrimitive.int)
        assertEquals(0, data["notes"]!!.jsonArray.size)
    }

    // ──────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────

    @Test
    fun `list requires itemId`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("list"))
            )
        }
    }

    @Test
    fun `get requires id parameter`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("get"))
            )
        }
    }

    @Test
    fun `list with invalid role throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("list"),
                    "itemId" to JsonPrimitive(UUID.randomUUID().toString()),
                    "role" to JsonPrimitive("invalid")
                )
            )
        }
    }
}
