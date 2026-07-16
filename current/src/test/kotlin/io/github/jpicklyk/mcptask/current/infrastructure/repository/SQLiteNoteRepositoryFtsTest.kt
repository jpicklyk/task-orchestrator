package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
import io.github.jpicklyk.mcptask.current.test.BaseFts5RepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FTS5 full-text search integration tests for [SQLiteNoteRepository].
 *
 * FTS5 is SQLite-only. Extends [BaseFts5RepositoryTest] which creates an in-memory SQLite DB
 * with the base schema + FTS5 tables. If FTS5 setup fails the test run aborts with a loud
 * error (not a skip). Repositories use `WHERE <table_name> MATCH ?` (not `WHERE alias MATCH ?`)
 * to avoid the "no such column: ft" error on Linux/Docker.
 *
 * Test names follow plan §16.5 — communicating agent-visible behaviour.
 */
class SQLiteNoteRepositoryFtsTest : BaseFts5RepositoryTest() {
    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun itemRepo(): SQLiteWorkItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository

    private fun noteRepo(): SQLiteNoteRepository = repositoryProvider.noteRepository() as SQLiteNoteRepository

    private suspend fun createItem(title: String = "Test item"): WorkItem {
        val item = WorkItem(title = title)
        val result = itemRepo().create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private suspend fun createNote(
        itemId: UUID,
        key: String = "test-note",
        body: String,
        role: String = "work",
    ): Note {
        val note = Note(itemId = itemId, key = key, role = role, body = body)
        val result = noteRepo().upsert(note)
        assertIs<Result.Success<Note>>(result)
        return result.data
    }

    // ────────────────────────────────────────────────────────────────────────
    // Trigram substring matching
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `searches note body by trigram for substring matches`(): Unit =
        runBlocking {
            val item1 = createItem("OAuth item")
            val item2 = createItem("Unrelated item")

            // "OAuth" contains the substring "auth" — trigram table finds it.
            createNote(item1.id, key = "design", body = "Implement the OAuth flow using bearer tokens")
            createNote(item2.id, key = "notes", body = "This note has nothing matching at all")

            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"auth\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    limit = 10,
                )

            assertTrue(result.hits.isNotEmpty(), "Expected at least one note hit for 'auth'")
            val hitItemIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                item1.id in hitItemIds,
                "Expected note containing 'OAuth' to appear in trigram hits for 'auth', got: $hitItemIds"
            )
            assertTrue(result.hits.all { it.kind == "note" }, "All hits should have kind='note'")
            assertTrue(result.hits.all { it.field == "body" }, "All note hits should have field='body'")
            assertTrue(result.hits.all { "trigram" in it.matchedIn })
        }

    // ────────────────────────────────────────────────────────────────────────
    // Porter stemming
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `searches note body by porter tokenizer for stem matches`(): Unit =
        runBlocking {
            val item = createItem("User management feature")
            createNote(
                item.id,
                key = "impl",
                body = "authenticated users receive session tokens via the API",
            )
            val unrelatedItem = createItem("Payment module")
            createNote(
                unrelatedItem.id,
                key = "unrelated",
                body = "completely different topic about payment processing",
            )

            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"authentication\"",
                    matchMode = SearchMatchMode.TEXT,
                    limit = 10,
                )

            assertTrue(
                result.hits.isNotEmpty(),
                "Expected porter stemming to match 'authenticated' with query 'authentication'"
            )
            val hitItemIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                item.id in hitItemIds,
                "Expected note with 'authenticated' to match query 'authentication' via stemming, got: $hitItemIds"
            )
            assertTrue(result.hits.all { it.kind == "note" })
        }

    // ────────────────────────────────────────────────────────────────────────
    // Scope — itemId
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `narrows note search to a single item via scope itemId`(): Unit =
        runBlocking {
            val targetItem = createItem("Target feature")
            val otherItem = createItem("Other feature")

            createNote(targetItem.id, key = "note-a", body = "OAuth authentication flow for the API")
            createNote(otherItem.id, key = "note-b", body = "OAuth authentication flow for mobile")

            val scope = SearchScope(itemId = targetItem.id)
            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"auth\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = scope,
                    limit = 10,
                )

            val hitItemIds = result.hits.map { it.itemId }.toSet()
            assertTrue(targetItem.id in hitItemIds, "Expected note scoped to targetItem to appear in results")
            assertTrue(
                otherItem.id !in hitItemIds,
                "Expected note from otherItem to be excluded by itemId scope, got: $hitItemIds"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Scope — ancestorId subtree
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `narrows note search to a subtree via scope ancestorId`(): Unit =
        runBlocking {
            // Build a 3-level hierarchy
            val root = createItem("Root feature")
            val childResult = itemRepo().create(WorkItem(title = "Child task", parentId = root.id, depth = 1))
            assertIs<Result.Success<WorkItem>>(childResult)
            val childItem = childResult.data

            val grandchildResult =
                itemRepo().create(
                    WorkItem(title = "Grandchild task", parentId = childItem.id, depth = 2)
                )
            assertIs<Result.Success<WorkItem>>(grandchildResult)
            val grandchildItem = grandchildResult.data

            val outsideItem = createItem("Outside feature")

            createNote(root.id, key = "root-note", body = "OAuth authentication requirements document")
            createNote(childItem.id, key = "child-note", body = "authentication implementation details")
            createNote(grandchildItem.id, key = "gc-note", body = "OAuth token management specification")
            createNote(outsideItem.id, key = "outside-note", body = "authentication module in separate tree")

            val scope = SearchScope(ancestorId = root.id)
            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"auth\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = scope,
                    limit = 20,
                )

            val hitItemIds = result.hits.map { it.itemId }.toSet()

            assertTrue(
                root.id in hitItemIds || childItem.id in hitItemIds || grandchildItem.id in hitItemIds,
                "Expected notes from the subtree to appear in ancestorId-scoped search, got: $hitItemIds"
            )
            assertTrue(
                outsideItem.id !in hitItemIds,
                "Expected note from outside the subtree to be excluded, got: $hitItemIds"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Note hit shape verification
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `note search hit includes noteKey and kind note in response`(): Unit =
        runBlocking {
            val item = createItem("Task with note")
            createNote(item.id, key = "design-doc", body = "OAuth authentication design document")

            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"auth\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )

            assertTrue(result.hits.isNotEmpty(), "Expected at least one hit")
            val hit = result.hits.first()
            assertTrue(hit.kind == "note", "Expected hit.kind to be 'note', got '${hit.kind}'")
            assertTrue(hit.noteKey != null, "Expected noteKey to be present in note hit")
            assertTrue(hit.score > 0.0, "Expected positive RRF score")
            assertTrue(hit.snippet.isNotEmpty(), "Expected non-empty snippet")
        }

    // ────────────────────────────────────────────────────────────────────────
    // Edge cases — empty body and non-matching searches
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `empty note body is indexed without crash and produces zero hits`(): Unit =
        runBlocking {
            val item = createItem("Item with empty note")
            // Notes default to body="" but explicitly set here for clarity. The FTS
            // _ai trigger must accept the empty string and the resulting search must
            // produce no hits, not an exception.
            createNote(item.id, key = "stub", body = "")
            // Sibling note with content, to confirm the empty one wasn't accidentally
            // indexed under arbitrary tokens.
            val populated = createItem("Item with content")
            createNote(populated.id, key = "real", body = "lookup-me-find-this-term")

            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"lookup-me-find-this-term\"",
                    matchMode = SearchMatchMode.AUTO,
                    limit = 10,
                )

            val hitItemIds = result.hits.map { it.itemId }.toSet()
            assertTrue(
                item.id !in hitItemIds,
                "Empty-body note must not match any term, got: $hitItemIds"
            )
        }
}
