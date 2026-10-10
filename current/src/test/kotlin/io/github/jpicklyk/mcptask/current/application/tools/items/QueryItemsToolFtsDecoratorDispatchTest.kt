package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
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
 * Regression test for bug 56aa72f0 - `query_items` operation=search silently returned an empty result whenever
 * [ToolExecutionContext.workItemRepository] was NOT the concrete `SQLiteWorkItemRepository` type (e.g. the
 * `EventPublishingWorkItemRepository` decorator used when the REST API is enabled).
 *
 * Since the unified search core (item 4a15997e) the tool no longer dispatches on a repository at all: it calls the
 * `SearchService`, which reads through the `SearchIndex` port from `RepositoryProvider.searchIndex()`. The mocked provider
 * here exposes exactly that port (with a mocked, non-concrete work-item repository), so a hit coming back proves the
 * search path depends on the port only, never on a concrete or decorated repository type.
 */
class QueryItemsToolFtsDecoratorDispatchTest {
    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    @Test
    fun `search dispatches through the SearchIndex port regardless of the concrete repository type`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val sentinelItemId = UUID.randomUUID()
            val sentinelCandidate =
                Candidate(
                    id = sentinelItemId,
                    ownerItemId = sentinelItemId,
                    noteKey = null,
                    rank = -1.0,
                    field = "title",
                    snippet = "sentinel <mark>needle</mark> snippet",
                )
            coEvery { mocks.searchIndex.candidates(any(), any(), any(), any(), any()) } returns listOf(sentinelCandidate)
            coEvery { mocks.searchIndex.titles(any()) } returns mapOf(sentinelItemId to "Sentinel title")

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                    ),
                    mocks.context(),
                ) as JsonObject

            // The ITEM corpus is queried (once per analyzer in the default AUTO mode) with the plain term, never an FTS expression.
            coVerify(atLeast = 1) {
                mocks.searchIndex.candidates(
                    match { it == Corpus.ITEM },
                    match { it.terms == listOf("needle") },
                    any(),
                    any(),
                    any(),
                )
            }
            coVerify(exactly = 0) {
                mocks.searchIndex.candidates(match { it == Corpus.NOTE }, any(), any(), any(), any())
            }
            coVerify(atLeast = 1) { mocks.searchIndex.candidates(any(), any(), match { it == Analyzer.SUBSTRING }, any(), any()) }
            coVerify(atLeast = 1) { mocks.searchIndex.candidates(any(), any(), match { it == Analyzer.STEMMED }, any(), any()) }

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["totalHits"]!!.jsonPrimitive.int)
            val hits = data["hits"]!!.jsonArray
            assertEquals(1, hits.size)
            assertEquals(sentinelItemId.toString(), hits[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertEquals("Sentinel title", hits[0].jsonObject["title"]!!.jsonPrimitive.content)
        }
}
