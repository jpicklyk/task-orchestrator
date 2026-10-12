package io.github.jpicklyk.mcptask.current.application.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored behavioral tests that the access scope, and the caller's tag filter, apply BEFORE the candidate
 * window and the result cap, over the real SQLite index (item 774ac07e, seat test-author, finding N5; test-only, so no
 * red-proof is owed). No implementation source, diff or commit was opened. Oracles: the frozen `test-plan` S11-S14 and the
 * `SearchRequest.access` KDoc ("applied before ranking and capping") plus the plan 3.10 scope-in-SQL rule: a restricted
 * caller's result is what the unrestricted search would return over only the rows the scope admits, so rows outside the
 * scope can never fill the `FTS_CANDIDATE_ROWS` window and push admitted rows out. The principal tag scope is exact and
 * case-sensitive element membership; the caller's own tag filter keeps the 3.x case-insensitive any-of semantics.
 *
 * Fixture: [STRONG_COUNT] strong matches in an out-of-scope place plus three weak matches in the admitted place. A strong
 * match repeats the term in a short title and summary; a weak match mentions it once in a long summary, so the unrestricted
 * control (S14) proves the weak rows rank below the window: under it the weak rows are absent from the first page.
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId` = its parent, `rootId` = the parent's root and `depth` = parent depth + 1; item tags are lowercase
 * `[a-z0-9-]` elements; the search term never appears in a root's title.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class SearchScopeBeforeCapTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val provider: RepositoryProvider get() = db.repositoryProvider()
    private val service get() = SearchService(provider.searchIndex(), db.unitOfWork())
    private val term = "capterm"

    private suspend fun root(title: String): WorkItem {
        val id = UUID.randomUUID()
        return checkNotNull(provider.workItemRepository().create(WorkItem(id = id, rootId = id, title = title, depth = 0)))
    }

    private suspend fun child(
        parent: WorkItem,
        title: String,
        summary: String,
        tags: String?
    ): WorkItem =
        checkNotNull(
            provider.workItemRepository().create(
                WorkItem(
                    id = UUID.randomUUID(),
                    rootId = parent.rootId,
                    parentId = parent.id,
                    title = title,
                    summary = summary,
                    depth = parent.depth + 1,
                    tags = tags
                )
            )
        )

    private class World(
        val outside: WorkItem,
        val inside: WorkItem,
        val strong: List<WorkItem>,
        val weak: List<WorkItem>
    )

    private suspend fun world(
        strongCount: Int,
        strongTags: String? = null,
        weakTags: String? = null
    ): World {
        val outside = root("outside root")
        val inside = root("inside root")
        val strong = (0 until strongCount).map { child(outside, "$term $term $term strong $it", "$term $term", strongTags) }
        val filler = "lorem ipsum dolor sit amet ".repeat(12)
        val weak = (0 until 3).map { child(inside, "weak $it", "$term once in $filler", weakTags) }
        return World(outside, inside, strong, weak)
    }

    private suspend fun search(
        access: AccessScope,
        tags: List<String>? = null
    ): SearchResult =
        service.search(
            SearchRequest(
                query = term,
                corpora = setOf(Corpus.ITEM),
                matchMode = SearchMatchMode.SUBSTRING,
                tags = tags,
                limit = 100,
                access = access
            )
        )

    private fun SearchResult.assertExactlyTheWeakRows(
        w: World,
        label: String
    ) {
        assertEquals(3, totalHits, "$label: totalHits counts only the admitted rows")
        assertEquals(w.weak.map { it.id }.toSet(), hits.map { it.itemId }.toSet(), "$label: exactly the admitted rows are returned")
        assertFalse(truncated, "$label: three admitted rows never exceed the cap")
        assertEquals(null, nextOffset, "$label: and the result is exhausted")
    }

    // ---------------------------------------------------------------- S14 control

    @Test
    fun `S14 control - unrestricted, the strong rows fill the capped list and the weak rows are absent`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT)

            val result = search(AccessScope.unrestricted())

            assertTrue(STRONG_COUNT > FTS_CANDIDATE_ROWS, "fixture: the strong rows alone overfill the candidate window")
            assertEquals(100, result.totalHits)
            assertTrue(result.truncated)
            assertEquals(100, result.hits.size)
            assertTrue(
                result.hits.map { it.itemId }.toSet().none {
                    it in
                        w.weak.map { weak ->
                            weak.id
                        }
                },
                "the weak rows rank below the cap"
            )
            assertTrue(result.hits.all { hit -> w.strong.any { it.id == hit.itemId } }, "every returned row is a strong one")
        }

    // ---------------------------------------------------------------- S11 root scope

    @Test
    fun `S11 a root-scoped caller gets its three rows although two hundred and ten out-of-scope rows match better`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT)

            val result = search(AccessScope.principal(rootIds = setOf(w.inside.id), tagsInclude = emptySet()))

            result.assertExactlyTheWeakRows(w, "S11")
        }

    @Test
    fun `S11 boundary - exactly as many out-of-scope matches as the window holds still leave the admitted rows`(): Unit =
        runBlocking {
            val w = world(strongCount = FTS_CANDIDATE_ROWS)

            val result = search(AccessScope.principal(rootIds = setOf(w.inside.id), tagsInclude = emptySet()))

            result.assertExactlyTheWeakRows(w, "S11 boundary at the window size")
        }

    @Test
    fun `S11 probe - a root scope that admits no matching row returns nothing`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT)
            val empty = root("empty root")

            val result = search(AccessScope.principal(rootIds = setOf(empty.id), tagsInclude = emptySet()))

            assertEquals(0, result.totalHits)
            assertTrue(result.hits.isEmpty())
            assertFalse(result.truncated)
            assertTrue(w.strong.isNotEmpty(), "control: matches exist elsewhere")
        }

    // ---------------------------------------------------------------- S12 principal tag scope

    @Test
    fun `S12 a tag-scoped caller gets its three tagged rows although two hundred and ten untagged-for-it rows match better`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT, strongTags = "beta", weakTags = "alpha")

            val result = search(AccessScope.principal(rootIds = null, tagsInclude = setOf("alpha")))

            result.assertExactlyTheWeakRows(w, "S12")
        }

    @Test
    fun `S12 probe - the principal tag scope is case sensitive`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT, strongTags = "beta", weakTags = "alpha")

            val result = search(AccessScope.principal(rootIds = null, tagsInclude = setOf("Alpha")))

            assertEquals(0, result.totalHits, "Alpha is not alpha")
            assertTrue(result.hits.isEmpty())
            assertEquals(3, w.weak.size, "control: the lowercase rows exist")
        }

    // ---------------------------------------------------------------- S13 caller tag filter

    @Test
    fun `S13 a caller tag filter applies before the cap too`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT, strongTags = "beta", weakTags = "alpha")

            val result = search(AccessScope.unrestricted(), tags = listOf("alpha"))

            result.assertExactlyTheWeakRows(w, "S13")
        }

    @Test
    fun `S13 probe - the caller tag filter is case insensitive and any-of`(): Unit =
        runBlocking {
            val w = world(strongCount = STRONG_COUNT, strongTags = "beta", weakTags = "alpha")

            val result = search(AccessScope.unrestricted(), tags = listOf("ALPHA", "no-such-tag"))

            result.assertExactlyTheWeakRows(w, "S13 case-insensitive any-of")
        }

    companion object {
        /** Strictly more than `FTS_CANDIDATE_ROWS` (200). */
        private const val STRONG_COUNT = 210
    }
}
