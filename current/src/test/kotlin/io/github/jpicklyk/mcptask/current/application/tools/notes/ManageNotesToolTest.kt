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

class ManageNotesToolTest {

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: ManageNotesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        val repositoryProvider = DefaultRepositoryProvider(databaseManager)
        context = ToolExecutionContext(repositoryProvider)
        tool = ManageNotesTool()
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

    // ──────────────────────────────────────────────
    // Upsert operations
    // ──────────────────────────────────────────────

    @Test
    fun `upsert creates new note`() = runBlocking {
        val itemId = createTestItem()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("approach"))
                        put("role", JsonPrimitive("work"))
                        put("body", JsonPrimitive("Some approach text"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
        assertEquals(0, data["failed"]!!.jsonPrimitive.int)

        val note = data["notes"]!!.jsonArray[0] as JsonObject
        assertNotNull(note["id"]!!.jsonPrimitive.content)
        assertEquals(itemId, note["itemId"]!!.jsonPrimitive.content)
        assertEquals("approach", note["key"]!!.jsonPrimitive.content)
        assertEquals("work", note["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert updates existing note by key`() = runBlocking {
        val itemId = createTestItem()

        // First upsert
        tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("plan"))
                        put("role", JsonPrimitive("queue"))
                        put("body", JsonPrimitive("Original plan"))
                    }
                ))
            ),
            context
        )

        // Second upsert with same (itemId, key)
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("plan"))
                        put("role", JsonPrimitive("queue"))
                        put("body", JsonPrimitive("Updated plan"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertEquals(1, (result["data"] as JsonObject)["upserted"]!!.jsonPrimitive.int)

        // Verify via query that only one note exists and body is updated
        val queryTool = QueryNotesTool()
        val listResult = queryTool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        val notes = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
        assertEquals(1, notes.size)
        assertEquals("Updated plan", notes[0].jsonObject["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `upsert validates itemId exists`() = runBlocking {
        val fakeItemId = UUID.randomUUID().toString()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(fakeItemId))
                        put("key", JsonPrimitive("orphan-note"))
                        put("role", JsonPrimitive("queue"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"))
    }

    @Test
    fun `upsert requires key`() = runBlocking {
        val itemId = createTestItem()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("role", JsonPrimitive("queue"))
                        // key is missing
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failure = data["failures"]!!.jsonArray[0] as JsonObject
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("key"))
    }

    @Test
    fun `upsert validates role`() = runBlocking {
        val itemId = createTestItem()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("bad-role"))
                        put("role", JsonPrimitive("invalid"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    @Test
    fun `upsert batch with mixed results`() = runBlocking {
        val itemId = createTestItem()
        val fakeItemId = UUID.randomUUID().toString()

        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("valid"))
                        put("role", JsonPrimitive("queue"))
                    },
                    buildJsonObject {
                        put("itemId", JsonPrimitive(fakeItemId))
                        put("key", JsonPrimitive("invalid"))
                        put("role", JsonPrimitive("queue"))
                    }
                ))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Delete operations
    // ──────────────────────────────────────────────

    @Test
    fun `delete note by id`() = runBlocking {
        val itemId = createTestItem()

        // Create a note
        val upsertResult = tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("to-delete"))
                        put("role", JsonPrimitive("queue"))
                    }
                ))
            ),
            context
        ) as JsonObject
        val noteId = (upsertResult["data"] as JsonObject)["notes"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content

        // Delete by ID
        val deleteResult = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(noteId)))
            ),
            context
        ) as JsonObject

        assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
        val data = deleteResult["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete notes by itemId`() = runBlocking {
        val itemId = createTestItem()

        // Create 3 notes
        for (key in listOf("note-a", "note-b", "note-c")) {
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to JsonArray(listOf(
                        buildJsonObject {
                            put("itemId", JsonPrimitive(itemId))
                            put("key", JsonPrimitive(key))
                            put("role", JsonPrimitive("queue"))
                        }
                    ))
                ),
                context
            )
        }

        // Delete all notes for this item
        val deleteResult = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "itemId" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject

        assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
        val data = deleteResult["data"] as JsonObject
        assertEquals(3, data["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `delete note by itemId and key`() = runBlocking {
        val itemId = createTestItem()

        // Create 2 notes
        tool.execute(
            params(
                "operation" to JsonPrimitive("upsert"),
                "notes" to JsonArray(listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("keep-me"))
                        put("role", JsonPrimitive("queue"))
                    },
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive("delete-me"))
                        put("role", JsonPrimitive("work"))
                    }
                ))
            ),
            context
        )

        // Delete specific note by (itemId, key)
        val deleteResult = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "itemId" to JsonPrimitive(itemId),
                "key" to JsonPrimitive("delete-me")
            ),
            context
        ) as JsonObject

        assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
        val data = deleteResult["data"] as JsonObject
        assertEquals(1, data["deleted"]!!.jsonPrimitive.int)

        // Verify the other note still exists
        val queryTool = QueryNotesTool()
        val listResult = queryTool.execute(
            params(
                "operation" to JsonPrimitive("list"),
                "itemId" to JsonPrimitive(itemId)
            ),
            context
        ) as JsonObject
        val remaining = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
        assertEquals(1, remaining.size)
        assertEquals("keep-me", remaining[0].jsonObject["key"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────

    @Test
    fun `upsert with empty notes array throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to JsonArray(emptyList())
                )
            )
        }
    }

    @Test
    fun `delete without ids or itemId throws`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                params("operation" to JsonPrimitive("delete"))
            )
        }
    }

    @Test
    fun `delete non-existent note by id returns failure not success`() = runBlocking {
        val nonExistentId = UUID.randomUUID().toString()
        val result = tool.execute(
            params(
                "operation" to JsonPrimitive("delete"),
                "ids" to JsonArray(listOf(JsonPrimitive(nonExistentId)))
            ),
            context
        ) as JsonObject

        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        val data = result["data"] as JsonObject
        assertEquals(0, data["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        val failures = data["failures"]!!.jsonArray
        assertEquals(1, failures.size)
        val failure = failures[0] as JsonObject
        assertEquals(nonExistentId, failure["id"]!!.jsonPrimitive.content)
        assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"))
    }
}
