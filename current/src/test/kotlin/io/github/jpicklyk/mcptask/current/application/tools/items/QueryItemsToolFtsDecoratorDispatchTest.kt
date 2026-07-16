package io.github.jpicklyk.mcptask.current.application.tools.items

import io.github.jpicklyk.mcptask.current.domain.repository.SearchHit
import io.github.jpicklyk.mcptask.current.domain.repository.SearchResult
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
 * Regression test for bug 56aa72f0 — `query_items` operation=search silently returned an empty
 * result whenever [ToolExecutionContext.workItemRepository] was NOT the concrete
 * `SQLiteWorkItemRepository` type (e.g. the `EventPublishingWorkItemRepository` decorator used
 * when the REST API is enabled). The tool used to gate FTS dispatch behind an
 * `is SQLiteWorkItemRepository` check; [MockRepositoryProvider]'s mocked `WorkItemRepository`
 * is exactly such a non-concrete instance, so it reproduces the old failure mode without needing
 * a real decorator or database.
 *
 * After the fix, [QueryItemsTool] dispatches `ftsSearch` directly on the `WorkItemRepository`
 * interface, so this now succeeds regardless of the concrete repository type.
 */
class QueryItemsToolFtsDecoratorDispatchTest {
    private fun params(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) = JsonObject(mapOf(*pairs))

    @Test
    fun `search dispatches ftsSearch on the WorkItemRepository interface regardless of concrete type`() =
        runBlocking {
            val mocks = MockRepositoryProvider()
            val sentinelItemId = UUID.randomUUID()
            val sentinelHit =
                SearchHit(
                    kind = "item",
                    itemId = sentinelItemId,
                    field = "title",
                    snippet = "sentinel <mark>needle</mark> snippet",
                    score = 1.0,
                    matchedIn = listOf("text"),
                )
            val sentinel = SearchResult(hits = listOf(sentinelHit), totalHits = 1, nextOffset = null)

            coEvery {
                mocks.workItemRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any(),
                )
            } returns sentinel

            val tool = QueryItemsTool()
            val result =
                tool.execute(
                    params(
                        "operation" to JsonPrimitive("search"),
                        "query" to JsonPrimitive("needle"),
                    ),
                    mocks.context(),
                ) as JsonObject

            // Prove the tool actually called through to the (mocked) repository — the old
            // `is SQLiteWorkItemRepository` gate would have skipped this call entirely and
            // returned a hardcoded empty result without ever touching the mock. The query
            // argument is matched with `any()` (not `eq("needle")`) because FtsQuerySanitizer
            // wraps each token in literal quotes as an FTS5 phrase term (e.g. `"needle"`).
            coVerify(exactly = 1) {
                mocks.workItemRepo.ftsSearch(
                    sanitizedFtsQuery = any(),
                    matchMode = any(),
                    scope = any(),
                    limit = any(),
                    offset = any(),
                )
            }

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"] as JsonObject
            assertEquals(1, data["totalHits"]!!.jsonPrimitive.int)
            val hits = data["hits"]!!.jsonArray
            assertEquals(1, hits.size)
            assertEquals(sentinelItemId.toString(), hits[0].jsonObject["itemId"]!!.jsonPrimitive.content)
        }
}
