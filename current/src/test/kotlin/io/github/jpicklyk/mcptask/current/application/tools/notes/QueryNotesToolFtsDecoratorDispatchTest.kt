package io.github.jpicklyk.mcptask.current.application.tools.notes

import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.test.MockRepositoryProvider
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test for bug 56aa72f0 - `query_notes` operation=search silently returned an empty result whenever
 * [ToolExecutionContext.noteRepository] was NOT the concrete `SQLiteNoteRepository` type (e.g. the
 * `EventPublishingNoteRepository` decorator used when the REST API is enabled).
 *
 * Since the unified search core (item 4a15997e) the tool calls the `SearchService`, which reads through the `SearchIndex`
 * port from `RepositoryProvider.searchIndex()`; the mocked provider here exposes exactly that port next to a mocked,
 * non-concrete `NoteStore`, so a hit coming back proves the search path depends on the port only.
 */
class QueryNotesToolFtsDecoratorDispatchTest {
    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    @Test
    fun `search dispatches through the SearchIndex port regardless of the concrete note store type`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val ownerItemId = UUID.randomUUID()
            val sentinelCandidate =
                Candidate(
                    id = UUID.randomUUID(),
                    ownerItemId = ownerItemId,
                    noteKey = "requirements",
                    rank = -1.0,
                    field = "body",
                    snippet = "sentinel <mark>needle</mark> snippet",
                )
            coEvery { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) } returns listOf(sentinelCandidate)
            coEvery { mocks.searchIndex.titles(any()) } returns mapOf(ownerItemId to "Owning item title")

            val tool = QueryNotesTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                    ),
                    mocks.context(),
                ) as JsonObject

            // The NOTE corpus is queried with the plain term, never an FTS expression, and the ITEM corpus is not touched.
            coVerify(atLeast = 1) {
                mocks.searchIndex.candidates(
                    match { it == Corpus.NOTE },
                    match { it.terms == listOf("needle") },
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 0) {
                mocks.searchIndex.candidates(match { it == Corpus.ITEM }, any(), any(), any(), any())
            }

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["totalHits"]!!.jsonPrimitive.int)
            val hits = data["hits"]!!.jsonArray
            assertEquals(1, hits.size)
            assertEquals(ownerItemId.toString(), hits[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("requirements", hits[0].jsonObject["noteKey"]!!.jsonPrimitive.content)
            assertEquals("Owning item title", hits[0].jsonObject["title"]!!.jsonPrimitive.content)
        }
}
