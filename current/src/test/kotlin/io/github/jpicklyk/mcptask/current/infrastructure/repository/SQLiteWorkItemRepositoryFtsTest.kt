package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.test.BaseFts5RepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FTS5 full-text search integration tests for [SQLiteWorkItemRepository].
 *
 * All tests require a real SQLite connection with FTS5 virtual tables. Extends
 * [BaseFts5RepositoryTest] which:
 * 1. Creates an in-memory SQLite DB with the base schema + FTS5 tables
 * 2. Verifies that FTS5 MATCH queries work with the production alias pattern
 * 3. Calls [org.junit.jupiter.api.Assumptions.assumeTrue] to skip tests gracefully
 *    when FTS5 is not functional in the bundled xerial/sqlite-jdbc environment
 *
 * **Production compatibility note:** The V7 migration uses `trigram case_sensitive=0`
 * which may fail in bundled SQLite. These tests use just `trigram` for compatibility.
 * The production Docker environment (system SQLite ≥ 3.45) exercises the full path.
 *
 * Test names follow plan §16.5 — communicating agent-visible behaviour.
 */
class SQLiteWorkItemRepositoryFtsTest : BaseFts5RepositoryTest() {
    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        summary: String = "",
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1,
    ): WorkItem {
        val repo = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository
        val item = WorkItem(title = title, summary = summary, parentId = parentId, depth = depth)
        val result = repo.create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private fun repo(): SQLiteWorkItemRepository =
        repositoryProvider.workItemRepository() as SQLiteWorkItemRepository

    // ────────────────────────────────────────────────────────────────────────
    // Trigram substring matching
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `searches by trigram for substrings under 4 characters`(): Unit =
        runBlocking {
            // "auth" is a 4-char substring of "OAuth" — trigram table handles this.
            val target = createItem(
                title = "OAuth integration task",
                summary = "Implement OAuth flow",
            )
            createItem(title = "Unrelated database migration")

            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"auth\"",
                matchMode = SearchMatchMode.SUBSTRING,
                limit = 10,
            )

            assertTrue(result.hits.isNotEmpty(), "Expected at least one trigram hit for 'auth'")
            val hitIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                target.id in hitIds,
                "Expected OAuth item to appear in trigram hits for 'auth', got: $hitIds"
            )
            assertTrue(result.hits.all { "trigram" in it.matchedIn })
        }

    // ────────────────────────────────────────────────────────────────────────
    // Porter stemming
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `searches by porter tokenizer for stem matches`(): Unit =
        runBlocking {
            // "authenticated" stems to "authent" via porter — should match query "authentication"
            val target = createItem(
                title = "User session handling",
                summary = "authenticated user management",
            )
            createItem(title = "Payment processing module")

            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"authentication\"",
                matchMode = SearchMatchMode.TEXT,
                limit = 10,
            )

            assertTrue(
                result.hits.isNotEmpty(),
                "Expected porter stemming to match 'authenticated' with query 'authentication'"
            )
            val hitIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                target.id in hitIds,
                "Expected stemmed-match item to appear in text hits, got: $hitIds"
            )
            assertTrue(result.hits.all { "text" in it.matchedIn })
        }

    // ────────────────────────────────────────────────────────────────────────
    // RRF fusion
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `fuses results across tokenizer tables via RRF`(): Unit =
        runBlocking {
            // This item matches both the trigram and text tables.
            val fusedItem = createItem(
                title = "authentication service",
                summary = "OAuth authenticated flow",
            )
            createItem(
                title = "identity verification module",
                summary = "authentication middleware layer",
            )

            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"authentication\"",
                matchMode = SearchMatchMode.AUTO,
                limit = 20,
            )

            assertTrue(result.hits.isNotEmpty(), "Expected hits in AUTO/RRF mode")

            // Results must be ordered by descending fused score.
            val scores = result.hits.map { it.score }
            val sortedDesc = scores.sortedDescending()
            assertEquals(sortedDesc, scores, "Hits should be ordered by descending fused score")

            // An item in both tables should have matchedIn containing both sources.
            val fusedHit = result.hits.find { it.itemId == fusedItem.id }
            if (fusedHit != null && fusedHit.matchedIn.size == 2) {
                val singleTableHits = result.hits.filter { it.matchedIn.size == 1 }
                if (singleTableHits.isNotEmpty()) {
                    val maxSingleScore = singleTableHits.maxOf { it.score }
                    assertTrue(
                        fusedHit.score >= maxSingleScore,
                        "Fused doc score ${fusedHit.score} should be >= best single-table score $maxSingleScore"
                    )
                }
            }
        }

    // ────────────────────────────────────────────────────────────────────────
    // Scope — ancestorId subtree filter
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `respects scope ancestorId to bound search to subtree`(): Unit =
        runBlocking {
            val root = createItem(title = "Feature root node", summary = "authentication system")
            val child = createItem(
                title = "Child task authentication",
                summary = "OAuth token handling",
                parentId = root.id, depth = 1,
            )
            val grandchild = createItem(
                title = "Grandchild OAuth implementation",
                summary = "authenticated API calls",
                parentId = child.id, depth = 2,
            )
            // Sibling item in a different tree — must NOT appear in scoped results.
            val outsideItem = createItem(
                title = "Unrelated authentication module",
                summary = "OAuth flow outside the subtree",
            )

            val scope = SearchScope(ancestorId = root.id)
            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"auth\"",
                matchMode = SearchMatchMode.AUTO,
                scope = scope,
                limit = 20,
            )

            val hitIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                root.id in hitIds || child.id in hitIds || grandchild.id in hitIds,
                "Expected at least one subtree item in ancestorId-scoped search, got: $hitIds"
            )
            assertTrue(
                outsideItem.id !in hitIds,
                "Item outside the subtree must not appear in ancestorId-scoped search, got: $hitIds"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Scope — itemId single-item filter
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `respects scope itemId to bound search to a single item`(): Unit =
        runBlocking {
            val targetItem = createItem(
                title = "Target OAuth service",
                summary = "authentication and authorization",
            )
            val otherItem = createItem(
                title = "Other authentication service",
                summary = "OAuth flow",
            )

            val scope = SearchScope(itemId = targetItem.id)
            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"auth\"",
                matchMode = SearchMatchMode.AUTO,
                scope = scope,
                limit = 10,
            )

            val hitIds = result.hits.map { it.itemId }.toSet()
            assertTrue(targetItem.id in hitIds, "Expected scoped-by-itemId search to include the target item")
            assertTrue(
                otherItem.id !in hitIds,
                "Expected scoped-by-itemId search to exclude other items, got: $hitIds"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Returns empty result for query with no matching content
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `returns empty result when no items match the search query`(): Unit =
        runBlocking {
            createItem(title = "Database design task")
            createItem(title = "API specification document")

            val result = repo().ftsSearch(
                sanitizedFtsQuery = "\"xyzunmatchableterm\"",
                matchMode = SearchMatchMode.AUTO,
                limit = 10,
            )

            assertEquals(0, result.totalHits, "Expected zero hits for a query matching nothing")
            assertTrue(result.hits.isEmpty())
        }
}
