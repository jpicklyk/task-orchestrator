package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.putNote
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.*

class QueryNotesToolTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var context: ToolExecutionContext
    private lateinit var tool: QueryNotesTool

    @BeforeEach
    fun setUp() {
        val repositoryProvider = db.repositoryProvider()
        context = ToolExecutionContext(repositoryProvider, unitOfWork = db.unitOfWork())
        tool = QueryNotesTool()
    }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    @Test
    fun `parameterSchema offers search only since get and list moved to notes_read`() {
        val operation = tool.parameterSchema.properties!!["operation"]!!.jsonObject
        assertEquals(listOf("search"), operation["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        val propNames = tool.parameterSchema.properties!!.keys
        assertFalse(propNames.contains("noteId"), "noteId belonged to get, which moved to notes_read")
        assertFalse(propNames.contains("includeBody"), "includeBody belonged to list, which moved to notes_read")
    }

    /**
     * Helper to create a work item directly via repository and return its UUID string.
     */
    private suspend fun createTestItem(title: String = "Test Item"): String {
        val item = WorkItem(title = title)
        val result = context.workItemRepository().create(item)
        return (result.id).toString()
    }

    /** Helper to create a note (fixture write through the note write service) and return its ID. */
    private suspend fun createNote(
        itemId: String,
        key: String,
        role: String,
        body: String = ""
    ): String = context.putNote(UUID.fromString(itemId), key, role, body).id.toString()

    // ──────────────────────────────────────────────
    // FTS search (real SQLite fixture)
    // ──────────────────────────────────────────────

    private suspend fun searchNotes(
        query: String,
        scopeItemId: String? = null
    ): JsonObject {
        val result =
            tool.execute(
                JsonObject(
                    buildMap<String, JsonElement> {
                        put("operation", JsonPrimitive("search"))
                        put("query", JsonPrimitive(query))
                        scopeItemId?.let { put("scope", buildJsonObject { put("itemId", JsonPrimitive(it)) }) }
                    }
                ),
                context
            ) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        return result["data"] as JsonObject
    }

    @Test
    fun `S5 search hits a seeded note body and scope itemId excludes another item's note`(): Unit =
        runBlocking {
            val itemA = createTestItem("Item A")
            val itemB = createTestItem("Item B")
            createNote(itemA, "approach", "work", "Migrate the quokkaflux pipeline carefully")
            createNote(itemB, "approach", "work", "Also touches the quokkaflux pipeline")

            val unscoped = searchNotes("quokkaflux")
            assertEquals(2, unscoped["totalHits"]!!.jsonPrimitive.int)

            val scoped = searchNotes("quokkaflux", scopeItemId = itemA)
            assertEquals(1, scoped["totalHits"]!!.jsonPrimitive.int)
            assertEquals(
                itemA,
                scoped["hits"]!!
                    .jsonArray[0]
                    .jsonObject["itemId"]!!
                    .jsonPrimitive.content
            )
        }

    @Test
    fun `S6 search with a non-matching query succeeds with zero hits`(): Unit =
        runBlocking {
            val itemId = createTestItem()
            createNote(itemId, "approach", "work", "Migrate the quokkaflux pipeline carefully")

            val data = searchNotes("nonexistentterm")
            assertEquals(0, data["totalHits"]!!.jsonPrimitive.int)
            assertEquals(0, data["hits"]!!.jsonArray.size)
        }

    // ──────────────────────────────────────────────
    // Validation
    // ──────────────────────────────────────────────

    @Test
    fun `get and list are no longer operations of query_notes`() {
        for (operation in listOf("get", "list")) {
            assertFailsWith<ToolValidationException>("$operation moved to notes_read") {
                tool.validateParams(params("operation" to JsonPrimitive(operation)))
            }
        }
    }
}
