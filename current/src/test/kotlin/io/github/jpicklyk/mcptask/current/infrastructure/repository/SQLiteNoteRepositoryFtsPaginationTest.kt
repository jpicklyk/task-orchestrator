package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchHit
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.test.BaseFts5RepositoryTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FTS pagination determinism tests for [SQLiteNoteRepository.ftsSearch] — item 0ba7c92d, note
 * side. Mirrors [SQLiteWorkItemRepositoryFtsPaginationTest]'s S1/S2 for the note surface, per
 * `test-plan`'s S6: "S1 and S2 repeated against SQLiteNoteRepository.ftsSearch, tie-break on
 * noteId." Both scenarios here are labelled EXISTING-SURFACE — the public [ftsSearch] signature
 * is unchanged by the fix.
 *
 * `SearchHit` exposes only the owning work item's id (`itemId`), never the note's own id, so each
 * fixture note here lives on its own dedicated work item and its note id is captured at creation
 * time (via `Note.id`, a `val` the caller may set explicitly — see the item's supplementary
 * declaration) into a local `itemId -> noteId` map, which is how the tie-break-by-noteId
 * assertion below is verified without ever reading `src/main`.
 *
 * Extends [BaseFts5RepositoryTest] — see [SQLiteWorkItemRepositoryFtsPaginationTest]'s class doc
 * for why an H2-backed harness would make this suite vacuously green.
 */
class SQLiteNoteRepositoryFtsPaginationTest : BaseFts5RepositoryTest() {
    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private fun itemRepo(): SQLiteWorkItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository

    private fun noteRepo(): SQLiteNoteRepository = repositoryProvider.noteRepository() as SQLiteNoteRepository

    private suspend fun createItem(title: String): WorkItem {
        val result = itemRepo().create(WorkItem(title = title))
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private suspend fun createNote(
        itemId: UUID,
        body: String,
        id: UUID = UUID.randomUUID(),
        key: String = "note",
    ): Note {
        val note = Note(id = id, itemId = itemId, key = key, role = "work", body = body)
        val result = noteRepo().upsert(note)
        assertIs<Result.Success<Note>>(result)
        return result.data
    }

    /** Small, easily-ordered explicit note ids: UUID(0, n) — MSB ties at 0, LSB compares as n. */
    private fun noteId(n: Long): UUID = UUID(0L, n)

    private fun approxEquals(
        a: List<Double>,
        b: List<Double>,
        eps: Double = 1e-9,
    ) = a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) < eps }

    // ────────────────────────────────────────────────────────────────────────
    // S6a — analog of S1: pages partition the bulk result with no duplicates or skips
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6a - note search pages at offsets 0,10,20 partition the bulk result with no duplicates or skips`(): Unit =
        runBlocking {
            val marker = "s6anotepartitionmark"
            val hostItems = (1..25).map { i -> createItem("host item $i") }
            hostItems.forEachIndexed { i, item -> createNote(item.id, body = "$marker payload $i") }
            val itemIds = hostItems.map { it.id }.toSet()

            val bulk =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(25, bulk.hits.size, "Expected all 25 seeded notes in the uncapped bulk call")
            assertEquals(itemIds, bulk.hits.map { it.itemId }.toSet())
            assertTrue(bulk.hits.all { it.kind == "note" })

            val pages =
                (0 until 25 step 10).map { off ->
                    noteRepo().ftsSearch(
                        sanitizedFtsQuery = "\"$marker\"",
                        matchMode = SearchMatchMode.AUTO,
                        scope = null,
                        limit = 10,
                        offset = off,
                    )
                }
            val concatenated = pages.flatMap { it.hits }
            assertEquals(
                bulk.hits.map { it.itemId },
                concatenated.map { it.itemId },
                "Concatenation of offset 0/10/20 note pages must equal the bulk call element-for-element"
            )
            assertEquals(
                concatenated.size,
                concatenated.map { it.itemId }.toSet().size,
                "No note should repeat across pages"
            )

            // Probe: replay/idempotency.
            val replay =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(
                bulk.hits.map { it.itemId },
                replay.hits.map { it.itemId },
                "Repeating the same note search call must return the identical order"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // S6b — analog of S2: a tied-score boundary is ordered by ascending noteId
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S6b - note pages crossing a tied-score boundary are ordered by ascending noteId at the tie`(): Unit =
        runBlocking {
            // Same construction as the work-item S2: 3 notes matching ONLY the trigram table (query
            // substring glued into one token) and 3 matching ONLY the text table (the real word
            // "catalog", porter-stemming identically to "cataloging" but never its literal
            // substring). Each note lives on its own item; explicit small ids (UUID(0,n)) make the
            // tie-break assertion easy to read and independent of Java's UUID.randomUUID() output.
            val trigramOnlyIds = listOf(10L, 20L, 30L).map { noteId(it) }
            val textOnlyIds = listOf(15L, 25L, 35L).map { noteId(it) }

            val trigramOnlyItemIds =
                trigramOnlyIds.mapIndexed { i, nid ->
                    val item = createItem("trigram host ${i + 1}")
                    createNote(item.id, body = "zzzcatalogingzzz${i + 1}", id = nid)
                    item.id to nid
                }
            val textOnlyItemIds =
                textOnlyIds.mapIndexed { i, nid ->
                    val item = createItem("text host ${i + 1}")
                    createNote(item.id, body = "catalog filler ${i + 1}", id = nid)
                    item.id to nid
                }
            val noteIdByItemId = (trigramOnlyItemIds + textOnlyItemIds).toMap()

            val bulk =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"cataloging\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(6, bulk.hits.size)
            assertEquals(noteIdByItemId.keys, bulk.hits.map { it.itemId }.toSet())

            // O3: independently derived score multiset — identical mechanism to the work-item S2.
            val expectedScoreMultiset = listOf(1.0 / 61, 1.0 / 61, 1.0 / 62, 1.0 / 62, 1.0 / 63, 1.0 / 63).sorted()
            val actualScoreMultiset = bulk.hits.map { it.score }.sorted()
            assertTrue(
                approxEquals(expectedScoreMultiset, actualScoreMultiset),
                "Expected score multiset $expectedScoreMultiset, got $actualScoreMultiset"
            )
            val groups = bulk.hits.groupBy { hit -> expectedScoreMultiset.first { abs(it - hit.score) < 1e-9 } }
            assertEquals(3, groups.size, "Expected three distinct score levels")
            groups.values.forEach { group -> assertEquals(2, group.size, "Expected each score level to hold exactly 2 tied notes") }

            // O1+O2, note variant: total order is score desc, ties broken ascending by the note's
            // OWN id (not the owning item's id) — translated via the itemId->noteId map recorded at
            // creation time, since SearchHit exposes only the owning item's id.
            val expectedOrder =
                bulk.hits.sortedWith(
                    compareByDescending<SearchHit> { it.score }.thenBy { noteIdByItemId.getValue(it.itemId) }
                )
            assertEquals(
                expectedOrder.map { it.itemId },
                bulk.hits.map { it.itemId },
                "Bulk order must be sorted by score desc, then noteId asc"
            )

            val paged =
                (0 until 6 step 2).flatMap { off ->
                    noteRepo()
                        .ftsSearch(
                            sanitizedFtsQuery = "\"cataloging\"",
                            matchMode = SearchMatchMode.AUTO,
                            scope = null,
                            limit = 2,
                            offset = off,
                        ).hits
                }
            assertEquals(
                bulk.hits.map { it.itemId },
                paged.map { it.itemId },
                "Paging through a tie group must reproduce the bulk order with no duplicates or skips"
            )

            val firstOfPair =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"cataloging\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 1,
                    offset = 0
                )
            val secondOfPair =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"cataloging\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 1,
                    offset = 1
                )
            assertEquals(bulk.hits[0].itemId, firstOfPair.hits.single().itemId)
            assertEquals(bulk.hits[1].itemId, secondOfPair.hits.single().itemId)
            assertTrue(
                firstOfPair.hits.single().itemId != secondOfPair.hits.single().itemId,
                "Splitting a tied pair across a page boundary must not duplicate the same note's item"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Probe — empty vs. absent: a zero-match note query
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe - zero-match note query yields totalHits=0 and nextOffset=null`(): Unit =
        runBlocking {
            val item = createItem("host with an unrelated note")
            createNote(item.id, body = "nothing relevant to the probe term here")

            val result =
                noteRepo().ftsSearch(
                    sanitizedFtsQuery = "\"unmatchedzzznotetermxyz\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 20,
                    offset = 0,
                )
            assertEquals(0, result.totalHits)
            assertNull(result.nextOffset)
            assertTrue(result.hits.isEmpty())
        }
}
