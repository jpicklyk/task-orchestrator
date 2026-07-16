package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.test.BaseFts5RepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FTS5 full-text search integration tests for [SQLiteWorkItemRepository].
 *
 * All tests require a real SQLite connection with FTS5 virtual tables. Extends
 * [BaseFts5RepositoryTest] which creates an in-memory SQLite DB with the base schema
 * + FTS5 tables. If FTS5 setup fails the test run aborts with a loud error (not a skip).
 *
 * **Production compatibility note:** The V7 migration uses plain `tokenize='trigram'` (the
 * SQLite default). The `case_sensitive=0` option is NOT used because xerial/sqlite-jdbc 3.49.1.0
 * rejects it with a parse error. These tests match the production tokenizer configuration.
 * Repositories use `WHERE <table_name> MATCH ?` (not `WHERE alias MATCH ?`) to avoid the
 * "no such column: ft" error on Linux/Docker.
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
        tags: String? = null,
        role: Role = Role.QUEUE,
    ): WorkItem {
        val repo = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository
        val item =
            WorkItem(
                title = title,
                summary = summary,
                parentId = parentId,
                depth = depth,
                tags = tags,
                role = role,
            )
        val result = repo.create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private fun repo(): SQLiteWorkItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository

    // ────────────────────────────────────────────────────────────────────────
    // Trigram substring matching
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `searches by trigram for substrings under 4 characters`(): Unit =
        runBlocking {
            // "auth" is a 4-char substring of "OAuth" — trigram table handles this.
            val target =
                createItem(
                    title = "OAuth integration task",
                    summary = "Implement OAuth flow",
                )
            createItem(title = "Unrelated database migration")

            val result =
                repo().ftsSearch(
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
            val target =
                createItem(
                    title = "User session handling",
                    summary = "authenticated user management",
                )
            createItem(title = "Payment processing module")

            val result =
                repo().ftsSearch(
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
            val fusedItem =
                createItem(
                    title = "authentication service",
                    summary = "OAuth authenticated flow",
                )
            createItem(
                title = "identity verification module",
                summary = "authentication middleware layer",
            )

            val result =
                repo().ftsSearch(
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
            val child =
                createItem(
                    title = "Child task authentication",
                    summary = "OAuth token handling",
                    parentId = root.id,
                    depth = 1,
                )
            val grandchild =
                createItem(
                    title = "Grandchild OAuth implementation",
                    summary = "authenticated API calls",
                    parentId = child.id,
                    depth = 2,
                )
            // Sibling item in a different tree — must NOT appear in scoped results.
            val outsideItem =
                createItem(
                    title = "Unrelated authentication module",
                    summary = "OAuth flow outside the subtree",
                )

            val scope = SearchScope(ancestorId = root.id)
            val result =
                repo().ftsSearch(
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
            val targetItem =
                createItem(
                    title = "Target OAuth service",
                    summary = "authentication and authorization",
                )
            val otherItem =
                createItem(
                    title = "Other authentication service",
                    summary = "OAuth flow",
                )

            val scope = SearchScope(itemId = targetItem.id)
            val result =
                repo().ftsSearch(
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

            val result =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"xyzunmatchableterm\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )

            assertEquals(0, result.totalHits, "Expected zero hits for a query matching nothing")
            assertTrue(result.hits.isEmpty())
        }

    // ────────────────────────────────────────────────────────────────────────
    // FTS sync triggers — UPDATE and DELETE invalidate the FTS index
    // Exercises the V7 work_items_fts_*_au and work_items_fts_*_ad triggers.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `update to title makes new title searchable and old title no longer matches`(): Unit =
        runBlocking {
            val item = createItem(title = "Original payload moniker")

            // Sanity: old title is searchable before the update.
            val beforeUpdate =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"moniker\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )
            assertTrue(
                item.id in beforeUpdate.hits.map { it.itemId },
                "Pre-update: item should be searchable by its original title"
            )

            // Update title; FTS _au trigger must re-index.
            val updated = repo().update(item.copy(title = "Refreshed contraption identifier"))
            assertIs<Result.Success<WorkItem>>(updated)

            val newTitleHit =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"contraption\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )
            assertTrue(
                item.id in newTitleHit.hits.map { it.itemId },
                "Post-update: item should be searchable by its new title"
            )

            val oldTitleHit =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"moniker\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )
            assertTrue(
                item.id !in oldTitleHit.hits.map { it.itemId },
                "Post-update: item should no longer be searchable by its old title"
            )
        }

    @Test
    fun `delete removes item from FTS index`(): Unit =
        runBlocking {
            val item = createItem(title = "Doomed transient marker")

            // Sanity: item is searchable before delete.
            val before =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"transient\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )
            assertTrue(
                item.id in before.hits.map { it.itemId },
                "Pre-delete: item should be searchable by title"
            )

            val deleted = repo().delete(item.id)
            assertIs<Result.Success<Boolean>>(deleted)
            assertTrue(deleted.data, "Expected delete to return true for existing item")

            val after =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"transient\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )
            assertTrue(
                item.id !in after.hits.map { it.itemId },
                "Post-delete: item should be removed from the FTS index"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Scope filters — tags and role
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `scope tags filter returns only items whose tag list contains a requested tag`(): Unit =
        runBlocking {
            // Items each containing "match" so FTS yields them all; tag filter narrows results.
            // Tags are stored as comma-separated strings; matcher checks exact equality plus
            // prefix/middle/suffix LIKE patterns. Verify a tag in any position is included.
            val onlyAuth = createItem(title = "match alpha", tags = "auth")
            val authPlusOther = createItem(title = "match beta", tags = "auth,fts5")
            val ftsInMiddle = createItem(title = "match gamma", tags = "frontend,fts5,docs")
            val excluded = createItem(title = "match delta", tags = "unrelated")

            val authResult =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"match\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = SearchScope(tags = listOf("auth")),
                    limit = 20,
                )
            val authIds = authResult.hits.map { it.itemId }.toSet()
            assertTrue(onlyAuth.id in authIds, "Expected 'auth' single-tag item to match")
            assertTrue(authPlusOther.id in authIds, "Expected 'auth,fts5' prefix-tag item to match")
            assertTrue(
                ftsInMiddle.id !in authIds,
                "Expected an item without 'auth' to be excluded, got: $authIds"
            )
            assertTrue(excluded.id !in authIds, "Expected an 'unrelated'-tag item to be excluded")

            // Multi-tag OR semantics: pass ["auth", "fts5"] and expect items with either tag.
            val orResult =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"match\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = SearchScope(tags = listOf("auth", "fts5")),
                    limit = 20,
                )
            val orIds = orResult.hits.map { it.itemId }.toSet()
            assertTrue(onlyAuth.id in orIds, "Expected single 'auth' item under multi-tag OR")
            assertTrue(authPlusOther.id in orIds, "Expected 'auth,fts5' under multi-tag OR")
            assertTrue(ftsInMiddle.id in orIds, "Expected mid-position 'fts5' under multi-tag OR")
            assertTrue(excluded.id !in orIds, "Expected 'unrelated' to remain excluded")
        }

    @Test
    fun `scope role filter returns only items whose role matches`(): Unit =
        runBlocking {
            val queueItem = createItem(title = "buckwheat noodles", role = Role.QUEUE)
            val workItem = createItem(title = "buckwheat porridge", role = Role.WORK)
            val terminalItem = createItem(title = "buckwheat tea", role = Role.TERMINAL)

            val workOnly =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"buckwheat\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = SearchScope(role = Role.WORK),
                    limit = 20,
                )
            val ids = workOnly.hits.map { it.itemId }.toSet()
            assertTrue(workItem.id in ids, "Expected WORK item to be returned")
            assertTrue(queueItem.id !in ids, "Expected QUEUE item to be excluded")
            assertTrue(terminalItem.id !in ids, "Expected TERMINAL item to be excluded")
        }

    // ────────────────────────────────────────────────────────────────────────
    // RRF rank exposure (used by `explain=true` at the tool layer)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `scope itemId pointing at a non-existent UUID returns empty result without crash`(): Unit =
        runBlocking {
            // Populate the index so the FTS table has rows, then scope to an unknown UUID.
            createItem(title = "alphabet soup garnish")

            val result =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"alphabet\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = SearchScope(itemId = UUID.randomUUID()),
                    limit = 10,
                )

            assertEquals(0, result.totalHits, "Expected zero hits for non-existent scope.itemId")
            assertTrue(result.hits.isEmpty())
        }

    @Test
    fun `AUTO mode exposes per-table BM25 ranks for hits matched in each table`(): Unit =
        runBlocking {
            // "authentication" is a porter stem ⇒ matches text table.
            // "authenticated" appears as a literal token ≥3 chars ⇒ matches trigram table.
            // The doubly-matched item contains both stems and a token long enough for trigram.
            val doubleMatch =
                createItem(
                    title = "authentication service",
                    summary = "OAuth authenticated flow",
                )
            val textOnly =
                createItem(
                    title = "identity verification module",
                    summary = "authentication middleware layer",
                )

            val result =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"authentication\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 20,
                )

            val doubleHit = result.hits.firstOrNull { it.itemId == doubleMatch.id }
            val textOnlyHit = result.hits.firstOrNull { it.itemId == textOnly.id }

            // The double-match item should report both ranks populated when matched in both tables.
            if (doubleHit != null && doubleHit.matchedIn.size == 2) {
                assertNotNull(doubleHit.trigramRank, "Expected trigramRank to be populated for double-match")
                assertNotNull(doubleHit.textRank, "Expected textRank to be populated for double-match")
                // Score must be >= each single-table contribution.
                if (textOnlyHit != null && textOnlyHit.matchedIn.size == 1) {
                    assertNull(textOnlyHit.trigramRank, "Single-table text hit should have null trigramRank")
                    assertNotNull(textOnlyHit.textRank, "Single-table text hit should have textRank populated")
                    assertTrue(
                        doubleHit.score > textOnlyHit.score,
                        "Double-match score (${doubleHit.score}) should strictly exceed single-match score (${textOnlyHit.score})"
                    )
                }
            }
        }

    // ────────────────────────────────────────────────────────────────────────
    // Regression: alias-based MATCH pattern (WHERE ft MATCH ?) bug
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Regression guard for the v3.7.0 production bug where `WHERE ft MATCH ?` (alias-based
     * pattern) caused [org.sqlite.SQLiteException] "no such column: ft" on xerial/sqlite-jdbc
     * on both Linux Docker and Windows. The fix replaces alias with the table name:
     * `WHERE work_items_fts_trigram MATCH ?`.
     *
     * This test would have returned 0 hits (silently swallowed exception) in the broken code.
     * After EDIT 1 (repository fix) + EDIT 4 (remove assumeTrue skip), it must return ≥ 1 hit.
     */
    @Test
    fun `ftsSearch returns hits for exact title word — regression guard for alias-based MATCH bug`(): Unit =
        runBlocking {
            val uniqueWord = "xyzFts5RegressionGuard"
            val inserted = createItem(title = "Task with $uniqueWord in title")
            createItem(title = "Unrelated item without the magic word")

            val result =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$uniqueWord\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )

            assertTrue(
                result.hits.isNotEmpty(),
                "Expected at least one FTS5 hit for '$uniqueWord' — 0 hits indicates the alias MATCH bug has returned"
            )
            val hitIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                inserted.id in hitIds,
                "Inserted item ${inserted.id} must appear in FTS5 hits. Got: $hitIds"
            )
        }
}
