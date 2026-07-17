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

    // ──────────────────────────────────────────────
    // Parameter naming regression (de711807 — API parameter naming normalization)
    // ──────────────────────────────────────────────

    @Test
    fun `parameterSchema uses noteId for get, not the old id name`() {
        val propNames = tool.parameterSchema.properties!!.keys
        assertTrue(propNames.contains("noteId"), "expected 'noteId' for the get operation")
        assertFalse(propNames.contains("id"), "old top-level 'id' name must be removed")
    }

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
    private suspend fun createNote(
        itemId: String,
        key: String,
        role: String,
        body: String = ""
    ): String {
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive(key))
                                    put("role", JsonPrimitive(role))
                                    if (body.isNotEmpty()) put("body", JsonPrimitive(body))
                                }
                            )
                        )
                ),
                context
            ) as JsonObject
        return (result["data"] as JsonObject)["notes"]!!
            .jsonArray[0]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    }

    // ──────────────────────────────────────────────
    // Get operations
    // ──────────────────────────────────────────────

    @Test
    fun `get note by id`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val noteId = createNote(itemId, "approach", "work", "Approach body text")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "noteId" to JsonPrimitive(noteId)
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
    fun `get nonexistent note returns error`(): Unit =
        runBlocking {
            val randomId = UUID.randomUUID().toString()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "noteId" to JsonPrimitive(randomId)
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
    fun `list notes for item`() =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "plan", "queue")
            createNote(itemId, "approach", "work")
            createNote(itemId, "verification", "review")

            val result =
                tool.execute(
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
    fun `list notes with role filter`() =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "plan", "queue")
            createNote(itemId, "approach", "work")
            createNote(itemId, "verification", "review")

            val result =
                tool.execute(
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
    fun `list notes with includeBody false omits body`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "plan", "queue", "This body should not appear")

            val result =
                tool.execute(
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
    fun `list notes with includeBody true includes body`() =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "plan", "queue", "This body should appear")

            val result =
                tool.execute(
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
            // With bodies present, bodyLength is not emitted.
            assertNull(note["bodyLength"], "bodyLength should be absent when the body is included")
        }

    @Test
    fun `list notes omits body by default and reports bodyLength`() =
        runBlocking {
            val itemId = createTestItem()
            val body = "This body should be summarized by length only"
            createNote(itemId, "plan", "queue", body)

            // No includeBody param → defaults to false (lean).
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("list"),
                        "itemId" to JsonPrimitive(itemId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0] as JsonObject
            assertNull(note["body"], "body should be omitted by default (includeBody defaults to false)")
            assertEquals(
                body.length,
                note["bodyLength"]!!.jsonPrimitive.int,
                "bodyLength should equal the note body length when the body is omitted"
            )
            // The itemId echo is omitted in list context (the caller supplied it); note id is kept.
            assertNull(note["itemId"], "itemId echo should be omitted per note in list responses")
            assertNotNull(note["id"], "note id should still be present")
        }

    @Test
    fun `list notes with keys filter returns only matching keys`() =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "plan", "queue", "Plan body")
            createNote(itemId, "approach", "work", "Approach body")
            createNote(itemId, "verification", "review", "Verification body")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("list"),
                        "itemId" to JsonPrimitive(itemId),
                        "keys" to
                            JsonArray(
                                listOf(JsonPrimitive("plan"), JsonPrimitive("verification"))
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["total"]!!.jsonPrimitive.int)
            val keys = data["notes"]!!.jsonArray.map { it.jsonObject["key"]!!.jsonPrimitive.content }.toSet()
            assertEquals(setOf("plan", "verification"), keys)
        }

    @Test
    fun `list notes for item with no notes`() =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
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

    // ──────────────────────────────────────────────
    // Actor attribution surfacing in query responses
    // ──────────────────────────────────────────────

    /**
     * Helper to create a note with an actor claim via ManageNotesTool.
     */
    private suspend fun createNoteWithActor(
        itemId: String,
        key: String,
        role: String,
        body: String,
        actorId: String,
        actorKind: String,
        actorParent: String? = null
    ): String {
        val result =
            manageTool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive(key))
                                    put("role", JsonPrimitive(role))
                                    put("body", JsonPrimitive(body))
                                    put(
                                        "actor",
                                        buildJsonObject {
                                            put("id", JsonPrimitive(actorId))
                                            put("kind", JsonPrimitive(actorKind))
                                            actorParent?.let { put("parent", JsonPrimitive(it)) }
                                        }
                                    )
                                }
                            )
                        )
                ),
                context
            ) as JsonObject
        return (result["data"] as JsonObject)["notes"]!!
            .jsonArray[0]
            .jsonObject["id"]!!
            .jsonPrimitive.content
    }

    @Test
    fun `get note with actor includes actor but omits noop verification in response`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val noteId =
                createNoteWithActor(
                    itemId = itemId,
                    key = "approach",
                    role = "work",
                    body = "Implementation approach with actor",
                    actorId = "agent-1",
                    actorKind = "subagent",
                    actorParent = "orch-1"
                )

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "noteId" to JsonPrimitive(noteId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject

            // Actor should be present
            assertTrue(data.containsKey("actor"), "actor field should be present when note was created with actor claim")
            val actor = data["actor"]!!.jsonObject
            assertEquals("agent-1", actor["id"]!!.jsonPrimitive.content)
            assertEquals("subagent", actor["kind"]!!.jsonPrimitive.content)
            assertEquals("orch-1", actor["parent"]!!.jsonPrimitive.content)
            assertFalse(actor.containsKey("proof"), "proof should be absent when not provided")

            // Verification is omitted for a no-op verifier (NoOpActorVerifier) — it carries no
            // information beyond "no verifier is configured", which is a deployment-wide fact.
            assertFalse(data.containsKey("verification"), "verification field should be omitted for a no-op verifier")
        }

    @Test
    fun `list notes includes actor but omits noop verification on notes that have actor claim`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            // Create two notes — one with actor, one without
            createNoteWithActor(
                itemId = itemId,
                key = "plan",
                role = "queue",
                body = "Planned with actor",
                actorId = "orch-1",
                actorKind = "orchestrator"
            )
            createNote(itemId, "approach", "work", "Approach without actor")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("list"),
                        "itemId" to JsonPrimitive(itemId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            val notes = data["notes"]!!.jsonArray
            assertEquals(2, notes.size)

            // Find the note with actor claim
            val planNote = notes.first { it.jsonObject["key"]!!.jsonPrimitive.content == "plan" }.jsonObject
            assertTrue(planNote.containsKey("actor"), "plan note should have actor field")
            assertEquals("orch-1", planNote["actor"]!!.jsonObject["id"]!!.jsonPrimitive.content)
            assertEquals("orchestrator", planNote["actor"]!!.jsonObject["kind"]!!.jsonPrimitive.content)
            assertFalse(planNote.containsKey("verification"), "verification should be omitted for a no-op verifier")

            // The note without actor should NOT have actor field
            val approachNote = notes.first { it.jsonObject["key"]!!.jsonPrimitive.content == "approach" }.jsonObject
            assertFalse(approachNote.containsKey("actor"), "approach note should not have actor field")
            assertFalse(approachNote.containsKey("verification"), "approach note should not have verification field")
        }

    @Test
    fun `note without actor has no actor field in get response`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            val noteId = createNote(itemId, "approach", "work", "No actor on this note")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("get"),
                        "noteId" to JsonPrimitive(noteId)
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertFalse(data.containsKey("actor"), "actor field should be absent when note has no actor claim")
            assertFalse(data.containsKey("verification"), "verification field should be absent when note has no actor claim")
        }
}
