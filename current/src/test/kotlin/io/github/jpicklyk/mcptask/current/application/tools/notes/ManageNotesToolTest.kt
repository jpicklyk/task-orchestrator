package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class ManageNotesToolTest {
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext
    private lateinit var tool: ManageNotesTool

    @BeforeEach
    fun setUp() {
        val dbName = "test_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)
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
    fun `upsert creates new note`() =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("approach"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Some approach text"))
                                    }
                                )
                            )
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
    fun `upsert updates existing note by key`() =
        runBlocking {
            val itemId = createTestItem()

            // First upsert
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("plan"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("Original plan"))
                                }
                            )
                        )
                ),
                context
            )

            // Second upsert with same (itemId, key)
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("plan"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Updated plan"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(1, (result["data"] as JsonObject)["upserted"]!!.jsonPrimitive.int)

            // Verify via query that only one note exists and body is updated
            // (includeBody: true — query_notes list omits bodies by default)
            val queryTool = QueryNotesTool()
            val listResult =
                queryTool.execute(
                    params(
                        "operation" to JsonPrimitive("list"),
                        "itemId" to JsonPrimitive(itemId),
                        "includeBody" to JsonPrimitive(true)
                    ),
                    context
                ) as JsonObject

            val notes = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
            assertEquals(1, notes.size)
            assertEquals("Updated plan", notes[0].jsonObject["body"]!!.jsonPrimitive.content)
        }

    @Test
    fun `upsert validates itemId exists`() =
        runBlocking {
            val fakeItemId = UUID.randomUUID().toString()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(fakeItemId))
                                        put("key", JsonPrimitive("orphan-note"))
                                        put("role", JsonPrimitive("queue"))
                                    }
                                )
                            )
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
    fun `upsert requires key`() =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("role", JsonPrimitive("queue"))
                                        // key is missing
                                    }
                                )
                            )
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
    fun `upsert validates role`() =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("bad-role"))
                                        put("role", JsonPrimitive("invalid"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert batch with mixed results`() =
        runBlocking {
            val itemId = createTestItem()
            val fakeItemId = UUID.randomUUID().toString()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
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
                                )
                            )
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
    fun `delete note by id`() =
        runBlocking {
            val itemId = createTestItem()

            // Create a note
            val upsertResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("to-delete"))
                                        put("role", JsonPrimitive("queue"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject
            val noteId =
                (upsertResult["data"] as JsonObject)["notes"]!!
                    .jsonArray[0]
                    .jsonObject["id"]!!
                    .jsonPrimitive.content

            // Delete by ID
            val deleteResult =
                tool.execute(
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
    fun `delete notes by itemId`() =
        runBlocking {
            val itemId = createTestItem()

            // Create 3 notes
            for (key in listOf("note-a", "note-b", "note-c")) {
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive(key))
                                        put("role", JsonPrimitive("queue"))
                                    }
                                )
                            )
                    ),
                    context
                )
            }

            // Delete all notes for this item
            val deleteResult =
                tool.execute(
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
    fun `delete note by itemId and key`() =
        runBlocking {
            val itemId = createTestItem()

            // Create 2 notes
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
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
                            )
                        )
                ),
                context
            )

            // Delete specific note by (itemId, key)
            val deleteResult =
                tool.execute(
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
            val listResult =
                queryTool.execute(
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
    fun `delete non-existent note by id returns failure not success`() =
        runBlocking {
            val nonExistentId = UUID.randomUUID().toString()
            val result =
                tool.execute(
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

    // ──────────────────────────────────────────────
    // itemContext helpers
    // ──────────────────────────────────────────────

    private suspend fun createTestItemWithTags(
        title: String = "Test Item",
        tags: String? = null,
        role: Role = Role.QUEUE
    ): String {
        val item = WorkItem(title = title, tags = tags, role = role)
        val result = context.workItemRepository().create(item)
        return ((result as Result.Success).data.id).toString()
    }

    private fun contextWithSchema(
        entries: List<NoteSchemaEntry>,
        matchTag: String
    ): ToolExecutionContext {
        val noteSchemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if (tags.contains(matchTag)) entries else null
            }
        return ToolExecutionContext(repositoryProvider, noteSchemaService)
    }

    // ──────────────────────────────────────────────
    // itemContext in upsert response
    // ──────────────────────────────────────────────

    @Test
    fun `upsert returns itemContext with guidancePointer and noteProgress when schema matches`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec"),
                    NoteSchemaEntry(key = "design", role = Role.QUEUE, required = true, guidance = "Write the design")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("The specification"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val itemContext = data["itemContext"] as JsonObject
            val ctx = itemContext[itemId] as JsonObject

            assertEquals("Write the design", ctx["guidancePointer"]!!.jsonPrimitive.content)

            val progress = ctx["noteProgress"] as JsonObject
            assertEquals(1, progress["filled"]!!.jsonPrimitive.int)
            assertEquals(1, progress["remaining"]!!.jsonPrimitive.int)
            assertEquals(2, progress["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert returns null guidancePointer when all phase notes filled`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Complete spec"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            assertTrue(ctx["guidancePointer"] is JsonNull)

            val progress = ctx["noteProgress"] as JsonObject
            assertEquals(1, progress["filled"]!!.jsonPrimitive.int)
            assertEquals(0, progress["remaining"]!!.jsonPrimitive.int)
            assertEquals(1, progress["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert returns null itemContext fields when no schema matches`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("some-note"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Content"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            assertTrue(ctx["guidancePointer"] is JsonNull)
            assertTrue(ctx["noteProgress"] is JsonNull)
        }

    @Test
    fun `upsert with empty body does not count as filled`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive(""))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            assertEquals("Write the spec", ctx["guidancePointer"]!!.jsonPrimitive.content)

            val progress = ctx["noteProgress"] as JsonObject
            assertEquals(0, progress["filled"]!!.jsonPrimitive.int)
            assertEquals(1, progress["remaining"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert batch returns itemContext for multiple items`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId1 = createTestItemWithTags(title = "Item 1", tags = "test-schema")
            val itemId2 = createTestItemWithTags(title = "Item 2", tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId1))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Spec for item 1"))
                                    },
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId2))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Spec for item 2"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val itemContext = data["itemContext"] as JsonObject
            assertNotNull(itemContext[itemId1])
            assertNotNull(itemContext[itemId2])
            val progress1 = (itemContext[itemId1] as JsonObject)["noteProgress"] as JsonObject
            assertEquals(0, progress1["remaining"]!!.jsonPrimitive.int)
            val progress2 = (itemContext[itemId2] as JsonObject)["noteProgress"] as JsonObject
            assertEquals(0, progress2["remaining"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert itemContext omits items where all notes failed`(): Unit =
        runBlocking {
            val fakeItemId = UUID.randomUUID().toString()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(fakeItemId))
                                        put("key", JsonPrimitive("orphan"))
                                        put("role", JsonPrimitive("queue"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            val data = result["data"] as JsonObject
            val itemContext = data["itemContext"] as JsonObject
            assertEquals(0, itemContext.size)
        }

    @Test
    fun `upsert returns null context for terminal item even with schema`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema", role = Role.TERMINAL)

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Content"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            assertTrue(ctx["guidancePointer"] is JsonNull)
            assertTrue(ctx["noteProgress"] is JsonNull)
        }

    @Test
    fun `upsert mixed batch only includes successful items in itemContext`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val validItemId = createTestItemWithTags(tags = "test-schema")
            val fakeItemId = UUID.randomUUID().toString()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(validItemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Good content"))
                                    },
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(fakeItemId))
                                        put("key", JsonPrimitive("spec"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Should fail"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val itemContext = data["itemContext"] as JsonObject
            assertEquals(1, itemContext.size)
            assertNotNull(itemContext[validItemId])
            assertNull(itemContext[fakeItemId])
        }

    @Test
    fun `upsert shows correct progress when notes are pre-filled`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec"),
                    NoteSchemaEntry(key = "design", role = Role.QUEUE, required = true, guidance = "Write the design"),
                    NoteSchemaEntry(key = "risks", role = Role.QUEUE, required = true, guidance = "List the risks")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            // Pre-fill the first note
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("spec"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive("Pre-filled spec"))
                                }
                            )
                        )
                ),
                schemaContext
            )

            // Now fill the second note — progress should reflect both filled
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("design"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Design content"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            // guidancePointer should point to the remaining unfilled note (risks)
            assertEquals("List the risks", ctx["guidancePointer"]!!.jsonPrimitive.content)
            val progress = ctx["noteProgress"] as JsonObject
            assertEquals(2, progress["filled"]!!.jsonPrimitive.int)
            assertEquals(1, progress["remaining"]!!.jsonPrimitive.int)
            assertEquals(3, progress["total"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // skillPointer in itemContext
    // ──────────────────────────────────────────────

    @Test
    fun `upsert returns skillPointer in itemContext when schema note has skill`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "spec",
                        role = Role.QUEUE,
                        required = true,
                        guidance = "Write the spec",
                        skill = "spec-quality"
                    ),
                    NoteSchemaEntry(
                        key = "design",
                        role = Role.QUEUE,
                        required = true,
                        guidance = "Write the design"
                    )
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("design"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Design content"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject

            // First unfilled required note is "spec" which has skill = "spec-quality"
            assertEquals("Write the spec", ctx["guidancePointer"]!!.jsonPrimitive.content)
            assertTrue(ctx.containsKey("skillPointer"), "skillPointer should be present when first unfilled note has skill")
            assertEquals("spec-quality", ctx["skillPointer"]!!.jsonPrimitive.content)
        }

    @Test
    fun `upsert omits skillPointer when schema note has no skill`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, guidance = "Write the spec")
                )
            val schemaContext = contextWithSchema(schemaEntries, "test-schema")
            val itemId = createTestItemWithTags(tags = "test-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("other-note"))
                                        put("role", JsonPrimitive("queue"))
                                        put("body", JsonPrimitive("Some content"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            val ctx = (data["itemContext"] as JsonObject)[itemId] as JsonObject
            assertFalse(ctx.containsKey("skillPointer"), "skillPointer should be absent when note has no skill")
        }

    // ──────────────────────────────────────────────
    // Actor attribution in upsert
    // ──────────────────────────────────────────────

    @Test
    fun `upsert with actor claim includes actor but omits noop verification in response`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("actor-note"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Implementation notes"))
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", JsonPrimitive("agent-001"))
                                                put("kind", JsonPrimitive("subagent"))
                                            }
                                        )
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)

            val note = data["notes"]!!.jsonArray[0] as JsonObject
            assertNotNull(note["actor"], "actor field should be present in response")
            val actor = note["actor"] as JsonObject
            assertEquals("agent-001", actor["id"]!!.jsonPrimitive.content)
            assertEquals("subagent", actor["kind"]!!.jsonPrimitive.content)

            assertFalse(note.containsKey("verification"), "verification field should be omitted for a no-op verifier")
        }

    @Test
    fun `upsert without actor has no actor in response`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("plain-note"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Some content"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val note = (result["data"] as JsonObject)["notes"]!!.jsonArray[0] as JsonObject
            assertFalse(note.containsKey("actor"), "actor key should be absent when no actor was provided")
            assertFalse(note.containsKey("verification"), "verification key should be absent when no actor was provided")
        }

    @Test
    fun `upsert update replaces previous actor`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            // First upsert with actor-a
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("actor-replace"))
                                    put("role", JsonPrimitive("work"))
                                    put("body", JsonPrimitive("First body"))
                                    put(
                                        "actor",
                                        buildJsonObject {
                                            put("id", JsonPrimitive("agent-first"))
                                            put("kind", JsonPrimitive("orchestrator"))
                                        }
                                    )
                                }
                            )
                        )
                ),
                context
            )

            // Second upsert with actor-b — same (itemId, key)
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("actor-replace"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Second body"))
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", JsonPrimitive("agent-second"))
                                                put("kind", JsonPrimitive("subagent"))
                                            }
                                        )
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            assertEquals(1, (result["data"] as JsonObject)["upserted"]!!.jsonPrimitive.int)

            // Verify the persisted note has the second actor via direct repo query
            val noteRepo = context.noteRepository()
            val itemUUID = java.util.UUID.fromString(itemId)
            val found = (noteRepo.findByItemIdAndKey(itemUUID, "actor-replace") as Result.Success).data
            assertNotNull(found, "note should exist")
            assertNotNull(found.actorClaim, "actorClaim should be persisted")
            assertEquals("agent-second", found.actorClaim.id)
            assertEquals(io.github.jpicklyk.mcptask.current.domain.model.ActorKind.SUBAGENT, found.actorClaim.kind)
        }

    @Test
    fun `invalid actor kind in upsert returns failure for that note`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("bad-actor"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Some content"))
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", JsonPrimitive("agent-x"))
                                                put("kind", JsonPrimitive("bogus"))
                                            }
                                        )
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("bogus"))
        }

    @Test
    fun `batch upsert with mixed actor presence both succeed with correct actor presence and omit noop verification`(): Unit =
        runBlocking {
            val itemId = createTestItem()

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    // Note with actor
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("with-actor"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("Has actor"))
                                        put(
                                            "actor",
                                            buildJsonObject {
                                                put("id", JsonPrimitive("agent-batch"))
                                                put("kind", JsonPrimitive("subagent"))
                                            }
                                        )
                                    },
                                    // Note without actor
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("without-actor"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("No actor"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(2, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)

            val notes = data["notes"]!!.jsonArray
            val noteWithActor = notes.map { it as JsonObject }.first { it["key"]!!.jsonPrimitive.content == "with-actor" }
            val noteWithoutActor = notes.map { it as JsonObject }.first { it["key"]!!.jsonPrimitive.content == "without-actor" }

            assertTrue(noteWithActor.containsKey("actor"), "note with actor should have actor in response")
            assertEquals("agent-batch", (noteWithActor["actor"] as JsonObject)["id"]!!.jsonPrimitive.content)
            assertFalse(noteWithActor.containsKey("verification"), "verification should be omitted for a no-op verifier")

            assertFalse(noteWithoutActor.containsKey("actor"), "note without actor should not have actor key")
            assertFalse(noteWithoutActor.containsKey("verification"), "note without actor should not have verification key")
        }

    // ──────────────────────────────────────────────
    // Bug regression: delete mutual exclusion (Bug 1) and notFound counter (Bug 2)
    // ──────────────────────────────────────────────

    @Test
    fun `delete with both ids and itemId throws validation error (bug 1 regression)`() =
        runBlocking {
            val itemId = createTestItem()
            val noteId = UUID.randomUUID().toString() // does not need to exist for validation check

            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("delete"),
                        "ids" to JsonArray(listOf(JsonPrimitive(noteId))),
                        "itemId" to JsonPrimitive(itemId)
                    )
                )
            }
        }

    @Test
    fun `delete with both ids and itemId does not wipe item notes (bug 1 defense-in-depth)`() =
        runBlocking {
            val itemId = createTestItem()

            // Create two notes on the item
            val upsertResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("note-to-delete-by-id"))
                                        put("role", JsonPrimitive("queue"))
                                    },
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("note-to-keep"))
                                        put("role", JsonPrimitive("queue"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            val noteId =
                (upsertResult["data"] as JsonObject)["notes"]!!
                    .jsonArray
                    .map { it as JsonObject }
                    .first { it["key"]!!.jsonPrimitive.content == "note-to-delete-by-id" }["id"]!!
                    .jsonPrimitive.content

            // Calling validateParams with both ids and itemId should throw — execute should never be reached
            assertFailsWith<ToolValidationException> {
                tool.validateParams(
                    params(
                        "operation" to JsonPrimitive("delete"),
                        "ids" to JsonArray(listOf(JsonPrimitive(noteId))),
                        "itemId" to JsonPrimitive(itemId)
                    )
                )
            }

            // Verify both notes still exist (no path ran)
            val queryTool = QueryNotesTool()
            val listResult =
                queryTool.execute(
                    params("operation" to JsonPrimitive("list"), "itemId" to JsonPrimitive(itemId)),
                    context
                ) as JsonObject
            val remaining = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
            assertEquals(2, remaining.size, "Both notes must remain — delete should not have executed")
        }

    @Test
    fun `delete by itemId and nonexistent key returns notFound count not failed (bug 2 regression)`() =
        runBlocking {
            val itemId = createTestItem()

            // Do NOT create any note with key "missing-key"
            val deleteResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("delete"),
                        "itemId" to JsonPrimitive(itemId),
                        "key" to JsonPrimitive("missing-key")
                    ),
                    context
                ) as JsonObject

            assertTrue(deleteResult["success"]!!.jsonPrimitive.boolean)
            val data = deleteResult["data"] as JsonObject
            assertEquals(0, data["deleted"]!!.jsonPrimitive.int, "deleted should be 0 — key never existed")
            assertEquals(1, data["notFound"]!!.jsonPrimitive.int, "notFound should be 1 — key not found is tracked separately")
            assertEquals(0, data["failed"]!!.jsonPrimitive.int, "failed should be 0 — not found is not an error")
            assertFalse(data.containsKey("failures"), "failures array should be absent when failed=0")
        }

    @Test
    fun `delete by itemId and existing key shows deleted not notFound (bug 2 positive case)`() =
        runBlocking {
            val itemId = createTestItem()

            // Create the note first
            tool.execute(
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("real-key"))
                                    put("role", JsonPrimitive("work"))
                                }
                            )
                        )
                ),
                context
            )

            val deleteResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("delete"),
                        "itemId" to JsonPrimitive(itemId),
                        "key" to JsonPrimitive("real-key")
                    ),
                    context
                ) as JsonObject

            val data = deleteResult["data"] as JsonObject
            assertEquals(1, data["deleted"]!!.jsonPrimitive.int)
            assertEquals(0, data["notFound"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // t31: bodyFromFile
    // ──────────────────────────────────────────────

    private fun tempBaseDir() = Files.createTempDirectory("manage-notes-bodyfromfile-test").also { it.toFile().deleteOnExit() }

    /** Fetches the persisted `body` for (itemId, key) via QueryNotesTool, since manage_notes's own response omits body. */
    private suspend fun fetchNoteBody(
        itemId: String,
        key: String
    ): String? {
        val queryTool = QueryNotesTool()
        val listResult =
            queryTool.execute(
                params(
                    "operation" to JsonPrimitive("list"),
                    "itemId" to JsonPrimitive(itemId),
                    "includeBody" to JsonPrimitive(true)
                ),
                context
            ) as JsonObject
        val notes = (listResult["data"] as JsonObject)["notes"]!!.jsonArray
        return notes
            .map { it.jsonObject }
            .firstOrNull { it["key"]!!.jsonPrimitive.content == key }
            ?.get("body")
            ?.jsonPrimitive
            ?.content
    }

    private fun upsertBodyFromFileParams(
        itemId: String,
        key: String,
        relativePath: String,
        role: String = "work"
    ) = params(
        "operation" to JsonPrimitive("upsert"),
        "notes" to
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("itemId", JsonPrimitive(itemId))
                        put("key", JsonPrimitive(key))
                        put("role", JsonPrimitive(role))
                        put("bodyFromFile", JsonPrimitive(relativePath))
                    }
                )
            )
    )

    @Test
    fun `bodyFromFile happy path reads file content into note body`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            baseDir.resolve("note.txt").toFile().writeText("Body sourced from a file")
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "from-file", "note.txt"), context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            assertEquals("Body sourced from a file", fetchNoteBody(itemId, "from-file"))
        }

    @Test
    fun `bodyFromFile round-trips exact file content`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val exactContent = "Line one\nLine two with punctuation! And unicode: café.\nLine three."
            baseDir.resolve("exact.txt").toFile().writeText(exactContent, Charsets.UTF_8)
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "exact", "exact.txt"), context) as JsonObject

            assertEquals(1, (result["data"] as JsonObject)["upserted"]!!.jsonPrimitive.int)
            assertEquals(exactContent, fetchNoteBody(itemId, "exact"))
        }

    @Test
    fun `bodyFromFile normalizes CRLF line endings to LF`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            baseDir.resolve("crlf.txt").toFile().writeBytes("Line1\r\nLine2\r\nLine3".toByteArray(Charsets.UTF_8))
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "crlf", "crlf.txt"), context) as JsonObject

            assertEquals(1, (result["data"] as JsonObject)["upserted"]!!.jsonPrimitive.int)
            val storedBody = fetchNoteBody(itemId, "crlf")
            assertEquals("Line1\nLine2\nLine3", storedBody)
            assertFalse(storedBody!!.contains("\r"), "CRLF must be normalized to LF")
        }

    @Test
    fun `bodyFromFile rejects a relative dot-dot escape`(): Unit =
        runBlocking {
            val outerDir = tempBaseDir()
            val baseDir = Files.createDirectory(outerDir.resolve("root"))
            outerDir.resolve("secret.txt").toFile().writeText("outside content")
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "escape", "../secret.txt"), context) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("escapes"), failure["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `bodyFromFile rejects an absolute path`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "absolute", "/etc/passwd"), context) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("absolute"), failure["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `bodyFromFile rejects a missing file`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "missing", "does-not-exist.txt"), context) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("not found"), failure["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `bodyFromFile rejects a file over the 64KB cap`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val oversized = "a".repeat(65536 + 1)
            baseDir.resolve("big.txt").toFile().writeText(oversized)
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "big", "big.txt"), context) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("65536"), failure["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `body and bodyFromFile together fails that note with a conflict error`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            baseDir.resolve("note.txt").toFile().writeText("file content")
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("conflict"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("inline content"))
                                        put("bodyFromFile", JsonPrimitive("note.txt"))
                                    }
                                )
                            )
                    ),
                    context
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(
                failure["error"]!!.jsonPrimitive.content.contains("mutually exclusive"),
                failure["error"]!!.jsonPrimitive.content
            )
        }

    @Test
    fun `bodyFromFile rejects a symlink that escapes the base directory`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val outsideDir = tempBaseDir()
            val outsideFile = outsideDir.resolve("secret.txt")
            outsideFile.toFile().writeText("outside content")
            val link = baseDir.resolve("escape-link.txt")
            try {
                Files.createSymbolicLink(link, outsideFile)
            } catch (e: Exception) {
                assumeTrue(false, "symlink creation unsupported in this environment: ${e.message}")
                return@runBlocking
            }
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "symlink", "escape-link.txt"), context) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int)
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertTrue(failure["error"]!!.jsonPrimitive.content.contains("symlink"), failure["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `bodyFromFile idempotent replay returns cached result without re-reading the file`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            val file = baseDir.resolve("note.txt").toFile()
            file.writeText("Idempotent content")
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItem()
            val requestId = UUID.randomUUID().toString()

            val requestParams =
                params(
                    "operation" to JsonPrimitive("upsert"),
                    "requestId" to JsonPrimitive(requestId),
                    "actor" to
                        buildJsonObject {
                            put("id", JsonPrimitive("agent-idem-bff"))
                            put("kind", JsonPrimitive("subagent"))
                        },
                    "notes" to
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("itemId", JsonPrimitive(itemId))
                                    put("key", JsonPrimitive("from-file-idem"))
                                    put("role", JsonPrimitive("work"))
                                    put("bodyFromFile", JsonPrimitive("note.txt"))
                                }
                            )
                        )
                )

            val result1 = fileTool.execute(requestParams, context) as JsonObject
            val data1 = result1["data"] as JsonObject
            assertEquals(1, data1["upserted"]!!.jsonPrimitive.int)
            val noteId1 = (data1["notes"]!!.jsonArray[0] as JsonObject)["id"]!!.jsonPrimitive.content

            // Delete the source file: if the replay re-read it, this call would now fail with
            // a "file not found" failure instead of returning the original cached success.
            file.delete()

            val result2 = fileTool.execute(requestParams, context) as JsonObject
            val data2 = result2["data"] as JsonObject
            assertEquals(1, data2["upserted"]!!.jsonPrimitive.int, "replay must return the cached success, not re-execute")
            assertEquals(0, data2["failed"]!!.jsonPrimitive.int)
            val noteId2 = (data2["notes"]!!.jsonArray[0] as JsonObject)["id"]!!.jsonPrimitive.content
            assertEquals(noteId1, noteId2, "replay must return the exact cached note")
        }

    // ──────────────────────────────────────────────
    // t45: schema maxLength enforcement at upsert
    // ──────────────────────────────────────────────

    private fun contextWithSchemaAndLimitsMode(
        entries: List<NoteSchemaEntry>,
        matchTag: String,
        noteLimitsMode: String = "warn"
    ): ToolExecutionContext {
        val noteSchemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if (tags.contains(matchTag)) entries else null

                override fun getNoteLimitsMode(): String = noteLimitsMode
            }
        return ToolExecutionContext(repositoryProvider, noteSchemaService)
    }

    /** Same as [contextWithSchemaAndLimitsMode], but with a [perRoot] layer wired in for t3 tests. */
    private fun contextWithSchemaLimitsAndPerRoot(
        entries: List<NoteSchemaEntry>,
        matchTag: String,
        globalNoteLimitsMode: String,
        perRoot: PerRootConfigService
    ): ToolExecutionContext {
        val noteSchemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if (tags.contains(matchTag)) entries else null

                override fun getNoteLimitsMode(): String = globalNoteLimitsMode
            }
        return ToolExecutionContext(repositoryProvider, noteSchemaService, perRootConfigService = perRoot)
    }

    private suspend fun createTestItemWithTagsAndRoot(
        rootId: UUID,
        tags: String,
        title: String = "Test Item"
    ): String {
        val item = WorkItem(title = title, tags = tags, role = Role.QUEUE, rootId = rootId)
        val result = context.workItemRepository().create(item)
        return ((result as Result.Success).data.id).toString()
    }

    @Test
    fun `upsert warns when body exceeds maxLength in default warn mode`(): Unit =
        runBlocking {
            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaAndLimitsMode(schemaEntries, "limited-schema")
            val itemId = createTestItemWithTags(tags = "limited-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("this body is way over the limit"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int, "warn mode still accepts the note")
            assertEquals(0, data["failed"]!!.jsonPrimitive.int)
            val note = data["notes"]!!.jsonArray[0] as JsonObject
            assertTrue(note.containsKey("warning"), "warning field should be present when over maxLength in warn mode")
            val warning = note["warning"]!!.jsonPrimitive.content
            assertTrue(warning.contains("10"), "warning should name the limit: $warning")
            assertTrue(warning.contains("limited"), "warning should name the key: $warning")
        }

    @Test
    fun `upsert body exactly at maxLength boundary passes without warning`(): Unit =
        runBlocking {
            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaAndLimitsMode(schemaEntries, "limited-schema")
            val itemId = createTestItemWithTags(tags = "limited-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("1234567890")) // exactly 10 chars
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
            val note = data["notes"]!!.jsonArray[0] as JsonObject
            assertFalse(note.containsKey("warning"), "body exactly at the limit must not warn")
        }

    @Test
    fun `upsert rejects body exceeding maxLength in reject mode`(): Unit =
        runBlocking {
            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaAndLimitsMode(schemaEntries, "limited-schema", noteLimitsMode = "reject")
            val itemId = createTestItemWithTags(tags = "limited-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("this body is way over the limit"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int, "reject mode must not persist the note")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
            val failure = data["failures"]!!.jsonArray[0] as JsonObject
            assertEquals("NOTE_BODY_TOO_LONG", failure["code"]!!.jsonPrimitive.content)
            assertEquals("limited", failure["key"]!!.jsonPrimitive.content)
            assertEquals(10, failure["maxLength"]!!.jsonPrimitive.int)
            assertEquals("this body is way over the limit".length, failure["actualLength"]!!.jsonPrimitive.int)

            // Verify nothing was actually persisted
            assertNull(fetchNoteBody(itemId, "limited"))
        }

    @Test
    fun `upsert enforces maxLength on bodyFromFile-sourced content too`(): Unit =
        runBlocking {
            val baseDir = tempBaseDir()
            baseDir.resolve("long.txt").toFile().writeText("this body is way over the limit")
            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaAndLimitsMode(schemaEntries, "limited-schema")
            val fileTool = ManageNotesTool(agentConfigBaseDir = baseDir)
            val itemId = createTestItemWithTags(tags = "limited-schema")

            val result =
                fileTool.execute(upsertBodyFromFileParams(itemId, "limited", "long.txt"), schemaContext) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int, "warn mode still accepts the note")
            val note = data["notes"]!!.jsonArray[0] as JsonObject
            assertTrue(note.containsKey("warning"), "maxLength must be enforced on bodyFromFile content as well as inline body")
        }

    // ──────────────────────────────────────────────
    // t3: per-root note_limits layering
    // ──────────────────────────────────────────────

    @Test
    fun `upsert per-root note_limits reject mode rejects an over-limit note for that root while another root stays on global warn`(): Unit =
        runBlocking {
            val strictRootId = UUID.randomUUID()
            val laxRootId = UUID.randomUUID()
            // resolveSchema's tag-fallback path also consults this same mock's snapshot (to look up
            // the note's maxLength) — only note_limits behavior is under test here, so each
            // snapshot's workItemSchemas/traits are empty, giving that path a harmless miss.
            val perRoot = mockk<PerRootConfigService>()
            coEvery { perRoot.getSnapshot(strictRootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = "reject",
                    statusLabels = null,
                    fingerprint = "fp-strict"
                )
            coEvery { perRoot.getSnapshot(laxRootId) } returns
                PerRootConfigService.Snapshot(
                    workItemSchemas = emptyMap(),
                    traits = emptyMap(),
                    noteLimitsModeExplicit = null,
                    statusLabels = null,
                    fingerprint = "fp-lax"
                )

            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaLimitsAndPerRoot(schemaEntries, "limited-schema", "warn", perRoot)

            val strictItemId = createTestItemWithTagsAndRoot(strictRootId, "limited-schema")
            val laxItemId = createTestItemWithTagsAndRoot(laxRootId, "limited-schema")

            val strictResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(strictItemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("this body is way over the limit"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject
            val strictData = strictResult["data"] as JsonObject
            assertEquals(0, strictData["upserted"]!!.jsonPrimitive.int, "the strict root's per-root reject mode must reject")
            assertEquals(1, strictData["failed"]!!.jsonPrimitive.int)
            val failure = strictData["failures"]!!.jsonArray[0] as JsonObject
            assertEquals("NOTE_BODY_TOO_LONG", failure["code"]!!.jsonPrimitive.content)

            val laxResult =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(laxItemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("this body is way over the limit"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject
            val laxData = laxResult["data"] as JsonObject
            assertEquals(
                1,
                laxData["upserted"]!!.jsonPrimitive.int,
                "no per-root value for this root must fall back to the global warn mode"
            )
            assertEquals(0, laxData["failed"]!!.jsonPrimitive.int)
            val laxNote = laxData["notes"]!!.jsonArray[0] as JsonObject
            assertTrue(laxNote.containsKey("warning"), "global warn mode should still accept the note with a warning")
        }

    @Test
    fun `upsert falls back to the global note_limits mode when the item has a null rootId`(): Unit =
        runBlocking {
            val perRoot = mockk<PerRootConfigService>()
            val schemaEntries = listOf(NoteSchemaEntry(key = "limited", role = Role.WORK, maxLength = 10))
            val schemaContext = contextWithSchemaLimitsAndPerRoot(schemaEntries, "limited-schema", "reject", perRoot)
            val itemId = createTestItemWithTags(tags = "limited-schema") // rootId defaults to null

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("limited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("this body is way over the limit"))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(0, data["upserted"]!!.jsonPrimitive.int, "a null rootId must still use the global mode (reject here)")
            assertEquals(1, data["failed"]!!.jsonPrimitive.int)
        }

    @Test
    fun `upsert with no maxLength configured never warns regardless of body length`(): Unit =
        runBlocking {
            val schemaEntries = listOf(NoteSchemaEntry(key = "unlimited", role = Role.WORK))
            val schemaContext = contextWithSchemaAndLimitsMode(schemaEntries, "unlimited-schema")
            val itemId = createTestItemWithTags(tags = "unlimited-schema")

            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("upsert"),
                        "notes" to
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("itemId", JsonPrimitive(itemId))
                                        put("key", JsonPrimitive("unlimited"))
                                        put("role", JsonPrimitive("work"))
                                        put("body", JsonPrimitive("a".repeat(10_000)))
                                    }
                                )
                            )
                    ),
                    schemaContext
                ) as JsonObject

            val data = result["data"] as JsonObject
            assertEquals(1, data["upserted"]!!.jsonPrimitive.int)
            val note = data["notes"]!!.jsonArray[0] as JsonObject
            assertFalse(note.containsKey("warning"))
        }
}
