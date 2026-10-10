package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.notes.QueryNotesTool
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1 (AC1) and S2 (AC4) through the real `query_items` and `query_notes` search operations on a real migrated
 * database: `field` names the column that actually contains the match and `snippet` comes from that column; every hit
 * carries `title` (an item hit its own title, a note hit its owning item's title).
 *
 * Oracles: `SearchHit.field` KDoc ("which field matched"), `SearchHit.snippet` KDoc (excerpt with `<mark>` delimiters),
 * decision D5 (both columns match: title wins), plan 8.1 / task-scope item 7 (the field bug: `field` was `title`
 * whenever the title was non-empty because FTS5 `snippet()` returns unmarked leading text for a non-matching column),
 * AC4 (hits carry `title`), and `RrfFusion` KDoc (k = 60) for `explain.rrfK`.
 *
 * Fixtures use whole words ("quokka") so that both analyzers highlight exactly the term.
 * Forbidden-construct declaration: none.
 */
class SearchFieldAndTitleTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val context by lazy { ToolExecutionContext(db.repositoryProvider(), unitOfWork = db.unitOfWork()) }

    private fun params(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun createItem(
        title: String,
        summary: String = "",
    ): WorkItem = assertNotNull(context.workItemRepository().create(WorkItem(title = title, summary = summary)))

    private suspend fun createNote(
        item: WorkItem,
        key: String,
        body: String,
    ): Note = assertNotNull(context.noteRepository().upsert(Note(itemId = item.id, key = key, role = "work", body = body)))

    private suspend fun searchItems(
        query: String,
        matchMode: String? = null,
        explain: Boolean = false,
    ): JsonArray {
        val args = mutableListOf<Pair<String, JsonElement>>("operation" to JsonPrimitive("search"), "query" to JsonPrimitive(query))
        if (matchMode != null) args += "matchMode" to JsonPrimitive(matchMode)
        if (explain) args += "explain" to JsonPrimitive(true)
        val result = QueryItemsTool().execute(JsonObject(args.toMap()), context) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "query_items search must succeed: $result")
        return (result["data"] as JsonObject)["hits"]!!.jsonArray
    }

    private suspend fun searchNotes(
        query: String,
        matchMode: String? = null,
    ): JsonArray {
        val args = mutableListOf<Pair<String, JsonElement>>("operation" to JsonPrimitive("search"), "query" to JsonPrimitive(query))
        if (matchMode != null) args += "matchMode" to JsonPrimitive(matchMode)
        val result = QueryNotesTool().execute(JsonObject(args.toMap()), context) as JsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "query_notes search must succeed: $result")
        return (result["data"] as JsonObject)["hits"]!!.jsonArray
    }

    private fun JsonArray.byItem(): Map<String, JsonObject> = map { it.jsonObject }.associateBy { it["itemId"]!!.jsonPrimitive.content }

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    // -- S1: field and snippet -----------------------------------------------------------------------------------

    @Test
    fun `S1 a summary-only match reports field summary with a marked summary snippet in every match mode`(): Unit =
        runBlocking {
            val target = createItem("plain heading", summary = "the quokka lives here")
            createItem("unrelated heading", summary = "nothing to see")

            listOf(null, "auto", "substring", "text").forEach { mode ->
                val hit = searchItems("quokka", mode).byItem().getValue(target.id.toString())
                assertEquals("summary", hit.str("field"), "matchMode=$mode")
                val snippet = hit.str("snippet")
                assertTrue("<mark>quokka</mark>" in snippet.lowercase(), "matchMode=$mode snippet should mark the term: $snippet")
                assertTrue("lives" in snippet, "matchMode=$mode snippet should be the summary text: $snippet")
                assertFalse("plain heading" in snippet, "matchMode=$mode snippet must not be the title text: $snippet")
            }
        }

    @Test
    fun `S1 a title-only match reports field title and a match in both columns reports title`(): Unit =
        runBlocking {
            val titleOnly = createItem("quokka heading tale", summary = "other words")
            val both = createItem("quokka both tale", summary = "also quokka sentence")

            listOf(null, "substring", "text").forEach { mode ->
                val byItem = searchItems("quokka", mode).byItem()
                val t = byItem.getValue(titleOnly.id.toString())
                assertEquals("title", t.str("field"), "matchMode=$mode")
                assertTrue("<mark>quokka</mark>" in t.str("snippet").lowercase(), "matchMode=$mode: ${t.str("snippet")}")

                val b = byItem.getValue(both.id.toString())
                assertEquals("title", b.str("field"), "matchMode=$mode: both columns match, title wins")
                assertTrue("tale" in b.str("snippet"), "matchMode=$mode: the snippet comes from the title: ${b.str("snippet")}")
                assertFalse(
                    "sentence" in b.str("snippet"),
                    "matchMode=$mode: the snippet must not come from the summary: ${b.str("snippet")}"
                )
            }
        }

    @Test
    fun `S1 a note hit reports field body with a marked body snippet`(): Unit =
        runBlocking {
            val host = createItem("Host item")
            createNote(host, "design", "the quokka is described in this body")

            listOf(null, "substring", "text").forEach { mode ->
                val hit = searchNotes("quokka", mode).byItem().getValue(host.id.toString())
                assertEquals("body", hit.str("field"), "matchMode=$mode")
                assertEquals("design", hit.str("noteKey"), "matchMode=$mode")
                assertEquals("note", hit.str("kind"), "matchMode=$mode")
                assertTrue("<mark>quokka</mark>" in hit.str("snippet").lowercase(), "matchMode=$mode: ${hit.str("snippet")}")
            }
        }

    // -- S2: title -----------------------------------------------------------------------------------------------------

    @Test
    fun `S2 an item hit carries the item's own title`(): Unit =
        runBlocking {
            val item = createItem("Quokka reading list", summary = "marsupial notes")
            val summaryHit = createItem("Another heading entirely", summary = "mentions a quokka")

            val byItem = searchItems("quokka").byItem()

            assertEquals("Quokka reading list", byItem.getValue(item.id.toString()).str("title"))
            assertEquals(
                "Another heading entirely",
                byItem.getValue(summaryHit.id.toString()).str("title"),
                "the title is reported even when the match is in the summary"
            )
        }

    @Test
    fun `S2 a note hit carries the owning item's title not the note key`(): Unit =
        runBlocking {
            val host = createItem("Owning item title")
            createNote(host, "spec", "quokka facts live in the note body")

            val hit = searchNotes("quokka").byItem().getValue(host.id.toString())

            assertEquals("Owning item title", hit.str("title"))
            assertEquals("spec", hit.str("noteKey"))
        }

    // -- explain -----------------------------------------------------------------------------------------------------------

    @Test
    fun `explain reports the RRF constant 60 and the per-analyzer ranks`(): Unit =
        runBlocking {
            // 'authentication' is a substring of itself and stems like itself (both analyzers); 'authenticated' stems
            // like 'authentication' but does not contain it (text analyzer only).
            val both = createItem("authentication")
            val textOnly = createItem("authenticated")

            val byItem = searchItems("authentication", explain = true).byItem()

            val bothExplain = byItem.getValue(both.id.toString())["explain"]!!.jsonObject
            assertEquals(60.0, bothExplain["rrfK"]!!.jsonPrimitive.double, 0.0)
            assertNotNull(
                bothExplain["trigramRank"]?.takeIf { it !is JsonNull },
                "a document matched by the trigram analyzer exposes its rank"
            )
            assertNotNull(bothExplain["textRank"]?.takeIf { it !is JsonNull }, "a document matched by the text analyzer exposes its rank")

            val textOnlyExplain = byItem.getValue(textOnly.id.toString())["explain"]!!.jsonObject
            assertEquals(60.0, textOnlyExplain["rrfK"]!!.jsonPrimitive.double, 0.0)
            // Absent and JSON null both mean "no rank"; the declarations do not fix which one is serialized.
            assertNull(textOnlyExplain["trigramRank"]?.takeIf { it !is JsonNull }, "a stem-only document has no trigram rank")
            assertNotNull(textOnlyExplain["textRank"]?.takeIf { it !is JsonNull })
        }
}
