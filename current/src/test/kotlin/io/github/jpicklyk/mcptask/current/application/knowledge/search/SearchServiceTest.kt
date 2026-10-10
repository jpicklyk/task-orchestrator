package io.github.jpicklyk.mcptask.current.application.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.MAX_FTS_RESULTS
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.SearchMatchMode
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.domain.model.Role
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SearchService scenarios over a hand-written fake [SearchIndex] (no database), so the service's own contract is
 * isolated from the SQLite adapter: validation, the per-analyzer plan, RRF fusion, the total order, the cap before
 * the slice, paging, and title hydration.
 *
 * Oracles (frozen test-plan S4/S5/S8 plus the pre-existing text quoted in the declarations):
 *  - `MAX_FTS_RESULTS` KDoc: the cap is applied BEFORE the page slice, `totalHits` is the same on every page and
 *    offsets at or beyond it return an empty page; `SearchResult.truncated` is true when more than the cap matched.
 *  - `SearchResult` KDoc: fused score descending, ties broken ascending by the stable domain id (work-item id for
 *    item hits, note id for note hits); successive pages partition the capped list with no duplicates or skips.
 *  - `RrfFusion` KDoc: score(doc) = sum over sources of 1 / (60 + rank_in_source(doc)), rank 1 = best of a list.
 *  - `FtsQuerySanitizer` KDoc (base): a blank query is rejected, "ab" is valid for the text analyzer and fails the
 *    trigram rule, every token shorter than 3 characters.
 *
 * Candidate ranks are FTS5 bm25 style: each fake list is ordered best-first with ascending (more negative is better)
 * values, which is how the SQL `ORDER BY rank` hands them over.
 *
 * Forbidden-construct declaration: none (no skips, no disjunctive assertions except the one justified
 * `isNullOrEmpty` on the unrestricted principal filter, whose representation of "no constraint" is undeclared).
 */
class SearchServiceTest {
    // -- fixtures -------------------------------------------------------------------------------

    private fun uuid(n: Long): UUID = UUID(0L, n)

    private fun itemCandidate(
        n: Long,
        position: Int,
        field: String = "title",
    ) = Candidate(
        id = uuid(n),
        ownerItemId = uuid(n),
        noteKey = null,
        rank = -1000.0 + position,
        field = field,
        snippet = "snippet <mark>needle</mark> $n",
    )

    private fun noteCandidate(
        noteN: Long,
        ownerN: Long,
        position: Int,
    ) = Candidate(
        id = uuid(noteN),
        ownerItemId = uuid(ownerN),
        noteKey = "key-$noteN",
        rank = -1000.0 + position,
        field = "body",
        snippet = "note <mark>needle</mark> $noteN",
    )

    private class FakeSearchIndex(
        private val lists: Map<Pair<Corpus, Analyzer>, List<Candidate>> = emptyMap(),
        private val titleMap: Map<UUID, String> = emptyMap(),
    ) : SearchIndex {
        data class Call(
            val corpus: Corpus,
            val query: TextQuery,
            val analyzer: Analyzer,
            val filter: ScopeFilter,
            val window: Int,
        )

        val calls = mutableListOf<Call>()
        val titleCalls = mutableListOf<Set<UUID>>()

        override suspend fun candidates(
            corpus: Corpus,
            query: TextQuery,
            analyzer: Analyzer,
            filter: ScopeFilter,
            window: Int,
        ): List<Candidate> {
            calls += Call(corpus, query, analyzer, filter, window)
            return (lists[corpus to analyzer] ?: emptyList()).take(window)
        }

        override suspend fun titles(itemIds: Set<UUID>): Map<UUID, String> {
            titleCalls += itemIds
            return titleMap.filterKeys { it in itemIds }
        }
    }

    private fun request(
        query: String = "needle",
        corpus: Corpus = Corpus.ITEM,
        matchMode: SearchMatchMode = SearchMatchMode.SUBSTRING,
        limit: Int = SearchRequest.DEFAULT_LIMIT,
        offset: Int = 0,
        access: AccessScope = AccessScope.unrestricted(),
    ) = SearchRequest(query = query, corpus = corpus, matchMode = matchMode, limit = limit, offset = offset, access = access)

    private fun search(
        index: SearchIndex,
        request: SearchRequest,
    ): SearchResult = runBlocking { SearchService(index).search(request) }

    // -- S4: cap before slice, paging -------------------------------------------------------------

    @Test
    fun `S4 150 candidates are capped at 100 before the slice and pages partition the capped list`() {
        // Ids descend with list position, so a result sorted by id instead of by score would visibly differ.
        val all = (0 until 150).map { itemCandidate(n = 1000L - it, position = it) }
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to all))
        val expectedIds = all.take(MAX_FTS_RESULTS).map { it.id }

        val pages = listOf(0, 30, 60, 90).map { off -> off to search(index, request(limit = 30, offset = off)) }

        pages.forEach { (off, page) ->
            assertEquals(MAX_FTS_RESULTS, page.totalHits, "totalHits is the capped size on the page at offset $off")
            assertTrue(page.truncated, "150 matches exceed the cap, so truncated on the page at offset $off")
        }
        assertEquals(
            expectedIds,
            pages.flatMap { (_, page) -> page.hits.map { it.itemId } },
            "pages must partition the first 100 of the score-ordered list with no duplicate and no skip"
        )
        assertEquals(listOf(30, 30, 30, 10), pages.map { (_, page) -> page.hits.size })
        assertEquals(listOf(30, 60, 90, null), pages.map { (_, page) -> page.nextOffset })
    }

    @Test
    fun `S4 offsets at or beyond the cap return an empty page with unchanged totals`() {
        val all = (0 until 150).map { itemCandidate(n = 1000L - it, position = it) }
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to all))

        listOf(100, 101, 150).forEach { off ->
            val page = search(index, request(limit = 10, offset = off))
            assertTrue(page.hits.isEmpty(), "offset $off must return an empty page")
            assertNull(page.nextOffset, "offset $off is exhausted")
            assertEquals(MAX_FTS_RESULTS, page.totalHits, "totalHits unchanged at offset $off")
            assertTrue(page.truncated, "truncated unchanged at offset $off")
        }
        val last = search(index, request(limit = 5, offset = 99))
        assertEquals(1, last.hits.size, "offset 99 is the 100th and last row")
        assertNull(last.nextOffset)
        val whole = search(index, request(limit = MAX_FTS_RESULTS, offset = 0))
        assertEquals(MAX_FTS_RESULTS, whole.hits.size)
        assertNull(whole.nextOffset, "a page that ends exactly at the cap is exhausted")
    }

    @Test
    fun `S4 the cap boundary is exactly 100 matches not truncated and 101 matches truncated`() {
        val hundred = (0 until 100).map { itemCandidate(n = 2000L - it, position = it) }
        val hundredOne = (0 until 101).map { itemCandidate(n = 2000L - it, position = it) }

        val atCap = search(FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to hundred)), request(limit = 100))
        assertEquals(100, atCap.totalHits)
        assertFalse(atCap.truncated, "exactly 100 matches did not exceed the cap")

        val overCap = search(FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to hundredOne)), request(limit = 100))
        assertEquals(100, overCap.totalHits, "the fused list is cut to the cap")
        assertTrue(overCap.truncated, "101 matches exceed the cap")
    }

    @Test
    fun `S4 25 candidates under the cap report totalHits 25 and walk to exhaustion`() {
        val all = (0 until 25).map { itemCandidate(n = 500L - it, position = it) }
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to all))

        val first = search(index, request(limit = 10, offset = 0))
        val second = search(index, request(limit = 10, offset = 10))
        val third = search(index, request(limit = 10, offset = 20))

        assertEquals(listOf(25, 25, 25), listOf(first, second, third).map { it.totalHits })
        assertEquals(listOf(false, false, false), listOf(first, second, third).map { it.truncated })
        assertEquals(listOf(10, 10, 5), listOf(first, second, third).map { it.hits.size })
        assertEquals(listOf(10, 20, null), listOf(first, second, third).map { it.nextOffset })
        assertEquals(all.map { it.id }, (first.hits + second.hits + third.hits).map { it.itemId })
    }

    @Test
    fun `S4 the service asks the index for more than the cap so truncation is detectable`() {
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to listOf(itemCandidate(1, 0))))
        search(index, request())
        assertTrue(index.calls.isNotEmpty())
        assertTrue(
            index.calls.all { it.window > MAX_FTS_RESULTS },
            "a window of $MAX_FTS_RESULTS or less could never show that more than the cap matched: ${index.calls.map { it.window }}"
        )
    }

    // -- S4: total order --------------------------------------------------------------------------

    @Test
    fun `S4 tied scores are ordered by ascending item id`() {
        // A and C are rank 1 in their own list (1/61 each), B and D are rank 2 (1/62 each).
        val a = itemCandidate(n = 50, position = 0)
        val b = itemCandidate(n = 10, position = 1)
        val c = itemCandidate(n = 20, position = 0)
        val d = itemCandidate(n = 40, position = 1)
        val index =
            FakeSearchIndex(
                mapOf(
                    (Corpus.ITEM to Analyzer.SUBSTRING) to listOf(a, b),
                    (Corpus.ITEM to Analyzer.STEMMED) to listOf(c, d),
                )
            )

        val result = search(index, request(matchMode = SearchMatchMode.AUTO))

        assertEquals(listOf(uuid(20), uuid(50), uuid(10), uuid(40)), result.hits.map { it.itemId })
        assertEquals(1.0 / 61, result.hits[0].score, 1e-9)
        assertEquals(1.0 / 61, result.hits[1].score, 1e-9)
        assertEquals(1.0 / 62, result.hits[2].score, 1e-9)
        assertEquals(1.0 / 62, result.hits[3].score, 1e-9)
    }

    @Test
    fun `S4 tied note scores are ordered by ascending note id not by owning item id`() {
        // Note 2 sits on the higher owner id and note 1 on the lower owner id.
        // Ordering by owner id would put note 1 first, ordering by note id puts note 2 first.
        val noteLowId = noteCandidate(noteN = 1, ownerN = 300, position = 0)
        val noteHighId = noteCandidate(noteN = 2, ownerN = 200, position = 0)
        val index =
            FakeSearchIndex(
                mapOf(
                    (Corpus.NOTE to Analyzer.SUBSTRING) to listOf(noteHighId),
                    (Corpus.NOTE to Analyzer.STEMMED) to listOf(noteLowId),
                )
            )

        val result = search(index, request(corpus = Corpus.NOTE, matchMode = SearchMatchMode.AUTO))

        assertEquals(2, result.hits.size)
        assertEquals(result.hits[0].score, result.hits[1].score, 1e-12, "fixture: the two notes must tie")
        assertEquals(
            listOf("key-1", "key-2"),
            result.hits.map { it.noteKey },
            "ascending NOTE id decides the tie: note 1 first"
        )
        assertEquals(listOf(uuid(300), uuid(200)), result.hits.map { it.itemId }, "a note hit reports the owning item id")
    }

    // -- S4/S5: fusion across analyzers -------------------------------------------------------------

    @Test
    fun `S5 a document in both analyzer lists scores the RRF sum and reports both sources`() {
        val x = itemCandidate(n = 1, position = 0)
        val y1 = itemCandidate(n = 2, position = 1)
        val y2 = itemCandidate(n = 2, position = 0)
        val z = itemCandidate(n = 3, position = 1)
        val index =
            FakeSearchIndex(
                mapOf(
                    (Corpus.ITEM to Analyzer.SUBSTRING) to listOf(x, y1),
                    (Corpus.ITEM to Analyzer.STEMMED) to listOf(y2, z),
                )
            )

        val result = search(index, request(matchMode = SearchMatchMode.AUTO))
        val byId = result.hits.associateBy { it.itemId }

        assertEquals(listOf(uuid(2), uuid(1), uuid(3)), result.hits.map { it.itemId }, "Y (1/62 + 1/61) beats X (1/61) beats Z (1/62)")
        assertEquals(1.0 / 62 + 1.0 / 61, byId.getValue(uuid(2)).score, 1e-9)
        assertEquals(1.0 / 61, byId.getValue(uuid(1)).score, 1e-9)
        assertEquals(1.0 / 62, byId.getValue(uuid(3)).score, 1e-9)

        assertEquals(
            setOf(Ranker.label(Analyzer.SUBSTRING), Ranker.label(Analyzer.STEMMED)),
            byId.getValue(uuid(2)).matchedIn.toSet()
        )
        assertEquals(listOf(Ranker.label(Analyzer.SUBSTRING)), byId.getValue(uuid(1)).matchedIn)
        assertEquals(listOf(Ranker.label(Analyzer.STEMMED)), byId.getValue(uuid(3)).matchedIn)

        assertNotNull(byId.getValue(uuid(2)).trigramRank)
        assertNotNull(byId.getValue(uuid(2)).textRank)
        assertNotNull(byId.getValue(uuid(1)).trigramRank)
        assertNull(byId.getValue(uuid(1)).textRank, "a SUBSTRING-only hit has no text rank")
        assertNull(byId.getValue(uuid(3)).trigramRank, "a STEMMED-only hit has no trigram rank")
        assertNotNull(byId.getValue(uuid(3)).textRank)
    }

    // -- plan: which analyzers, which corpus, which terms ---------------------------------------------

    @Test
    fun `the match mode selects the analyzers and only the requested corpus is queried`() {
        val cases =
            mapOf(
                SearchMatchMode.AUTO to setOf(Analyzer.SUBSTRING, Analyzer.STEMMED),
                SearchMatchMode.SUBSTRING to setOf(Analyzer.SUBSTRING),
                SearchMatchMode.TEXT to setOf(Analyzer.STEMMED),
            )
        cases.forEach { (mode, expected) ->
            assertEquals(expected, SearchService.analyzers(mode).toSet(), "analyzers($mode)")
            val index = FakeSearchIndex()
            search(index, request(query = "OAuth flow", corpus = Corpus.NOTE, matchMode = mode))
            assertEquals(expected, index.calls.map { it.analyzer }.toSet(), "analyzers queried for $mode")
            assertEquals(expected.size, index.calls.size, "each analyzer is queried exactly once for $mode")
            assertTrue(index.calls.all { it.corpus == Corpus.NOTE }, "only the NOTE corpus is queried")
            assertTrue(
                index.calls.all { it.query.terms == listOf("OAuth", "flow") },
                "the index receives the tokenized terms, never an FTS expression: ${index.calls.map { it.query.terms }}"
            )
        }
    }

    @Test
    fun `AUTO with only terms shorter than 3 characters still searches the stemmed analyzer`() {
        val hit = itemCandidate(n = 7, position = 0)
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.STEMMED) to listOf(hit)))

        val result = search(index, request(query = "ab", matchMode = SearchMatchMode.AUTO))

        assertEquals(listOf(uuid(7)), result.hits.map { it.itemId })
    }

    @Test
    fun `TEXT with only terms shorter than 3 characters is valid`() {
        val hit = itemCandidate(n = 8, position = 0)
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.STEMMED) to listOf(hit)))

        val result = search(index, request(query = "ab", matchMode = SearchMatchMode.TEXT))

        assertEquals(listOf(uuid(8)), result.hits.map { it.itemId })
    }

    // -- hit content and title hydration ----------------------------------------------------------------

    @Test
    fun `item and note hits carry kind owner id note key field and snippet from the candidate`() {
        val item = itemCandidate(n = 5, position = 0, field = "summary")
        val note = noteCandidate(noteN = 6, ownerN = 600, position = 0)

        val itemResult = search(FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to listOf(item))), request())
        val noteResult =
            search(FakeSearchIndex(mapOf((Corpus.NOTE to Analyzer.SUBSTRING) to listOf(note))), request(corpus = Corpus.NOTE))

        val itemHit = itemResult.hits.single()
        assertEquals("item", itemHit.kind)
        assertEquals(uuid(5), itemHit.itemId)
        assertNull(itemHit.noteKey)
        assertEquals("summary", itemHit.field, "field is the candidate's field, not a service default")
        assertEquals(item.snippet, itemHit.snippet)

        val noteHit = noteResult.hits.single()
        assertEquals("note", noteHit.kind)
        assertEquals(uuid(600), noteHit.itemId)
        assertEquals("key-6", noteHit.noteKey)
        assertEquals("body", noteHit.field)
        assertEquals(note.snippet, noteHit.snippet)
    }

    @Test
    fun `titles are hydrated in one batched read keyed by the owning item id`() {
        val items = (1L..3L).mapIndexed { i, n -> itemCandidate(n = n, position = i) }
        val itemIndex =
            FakeSearchIndex(
                mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to items),
                titleMap = mapOf(uuid(1) to "First", uuid(2) to "Second"),
            )
        val itemHits = search(itemIndex, request()).hits

        assertEquals(1, itemIndex.titleCalls.size, "one batched title read, not one read per hit")
        assertTrue(itemIndex.titleCalls.single().containsAll(setOf(uuid(1), uuid(2), uuid(3))))
        assertEquals(listOf("First", "Second", null), itemHits.map { it.title }, "an item without a readable title gets a null title")

        val note = noteCandidate(noteN = 90, ownerN = 900, position = 0)
        val noteIndex =
            FakeSearchIndex(
                mapOf((Corpus.NOTE to Analyzer.SUBSTRING) to listOf(note)),
                titleMap = mapOf(uuid(900) to "Owner title", uuid(90) to "NOTE ID MUST NOT BE LOOKED UP"),
            )
        val noteHit = search(noteIndex, request(corpus = Corpus.NOTE)).hits.single()

        assertEquals("Owner title", noteHit.title, "a note hit's title is the owning item's title")
    }

    @Test
    fun `an empty candidate set yields an empty exhausted result`() {
        val result = search(FakeSearchIndex(), request())

        assertEquals(0, result.totalHits)
        assertTrue(result.hits.isEmpty())
        assertNull(result.nextOffset)
        assertFalse(result.truncated)
    }

    // -- S8: validation, before any index access ------------------------------------------------------------

    @Test
    fun `S8 empty and blank queries are rejected with the declared message and never reach the index`() {
        listOf("", "   ", "\t\n").forEach { blank ->
            val index = FakeSearchIndex()
            val ex =
                assertFailsWith<SearchValidationException>("query '${blank.replace("\n", "\\n").replace("\t", "\\t")}'") {
                    runBlocking { SearchService(index).search(request(query = blank, matchMode = SearchMatchMode.AUTO)) }
                }
            assertEquals(SearchService.EMPTY_QUERY_MESSAGE, ex.message)
            assertTrue(index.calls.isEmpty(), "validation precedes any index call")
        }
    }

    @Test
    fun `S8 SUBSTRING with every term shorter than 3 characters is rejected before the index`() {
        listOf("ab", "ab cd", "a").forEach { shortQuery ->
            val index = FakeSearchIndex()
            val ex =
                assertFailsWith<SearchValidationException>("query '$shortQuery'") {
                    runBlocking { SearchService(index).search(request(query = shortQuery, matchMode = SearchMatchMode.SUBSTRING)) }
                }
            assertFalse(ex.message.isNullOrBlank())
            assertTrue(index.calls.isEmpty(), "validation precedes any index call")
        }
    }

    @Test
    fun `S8 SUBSTRING with one 3 character term among short ones is accepted`() {
        val hit = itemCandidate(n = 9, position = 0)
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to listOf(hit)))

        val result = search(index, request(query = "ab foo", matchMode = SearchMatchMode.SUBSTRING))

        assertEquals(listOf(uuid(9)), result.hits.map { it.itemId })
    }

    @Test
    fun `S7 a limit above 100 is rejected with the declared message and 100 is accepted`() {
        listOf(101, 500, 1000).forEach { tooBig ->
            val index = FakeSearchIndex()
            val ex =
                assertFailsWith<SearchValidationException>("limit $tooBig") {
                    runBlocking { SearchService(index).search(request(limit = tooBig)) }
                }
            assertEquals(SearchService.limitTooLargeMessage(tooBig), ex.message)
            assertTrue(index.calls.isEmpty(), "validation precedes any index call")
        }
        val all = (0 until 120).map { itemCandidate(n = 3000L - it, position = it) }
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to all))
        assertEquals(100, search(index, request(limit = 100)).hits.size)
        assertEquals(1, search(index, request(limit = 1)).hits.size)
    }

    // -- scope plumbing ----------------------------------------------------------------------------------------

    @Test
    fun `request scope fields and the principal scope reach every analyzer call in the filter`() {
        val itemId = uuid(1001)
        val ancestorId = uuid(1002)
        val root = uuid(1003)
        val index = FakeSearchIndex()

        search(
            index,
            SearchRequest(
                query = "needle",
                corpus = Corpus.ITEM,
                matchMode = SearchMatchMode.AUTO,
                itemId = itemId,
                ancestorId = ancestorId,
                roles = setOf(Role.WORK),
                tags = listOf("x", "y"),
                access = AccessScope.principal(rootIds = setOf(root), tagsInclude = setOf("alpha")),
            )
        )

        assertEquals(2, index.calls.size)
        index.calls.forEach { call ->
            assertEquals(itemId, call.filter.itemId)
            assertEquals(ancestorId, call.filter.ancestorId)
            assertEquals(setOf(Role.WORK), call.filter.roles)
            assertEquals(listOf("x", "y"), call.filter.tagsAny)
            assertEquals(setOf(root), call.filter.rootIds)
            assertEquals(setOf("alpha"), call.filter.principalTagsAny)
        }
    }

    @Test
    fun `an unrestricted access scope adds no root or principal tag constraint to the filter`() {
        val index = FakeSearchIndex()

        search(index, request(matchMode = SearchMatchMode.AUTO))

        index.calls.forEach { call ->
            assertNull(call.filter.rootIds, "unrestricted means no root constraint")
            // The declarations do not say whether "no tag constraint" is null or an empty set; both mean unconstrained.
            assertTrue(call.filter.principalTagsAny.isNullOrEmpty(), "no principal tag constraint: ${call.filter.principalTagsAny}")
            assertNull(call.filter.itemId)
            assertNull(call.filter.ancestorId)
            assertNull(call.filter.roles)
            assertNull(call.filter.tagsAny)
        }
    }

    // -- determinism ---------------------------------------------------------------------------------------------

    @Test
    fun `repeating the same request returns the identical ordered result`() {
        val all = (0 until 40).map { itemCandidate(n = 700L - it, position = it) }
        val index = FakeSearchIndex(mapOf((Corpus.ITEM to Analyzer.SUBSTRING) to all))

        val first = search(index, request(limit = 40))
        val replay = search(index, request(limit = 40))

        assertEquals(first.hits.map { it.itemId }, replay.hits.map { it.itemId })
        assertEquals(first.totalHits, replay.totalHits)
    }
}

/** Ranker scenarios: the RRF sum, the single constant K and the source labels. */
class RankerTest {
    private fun cand(
        n: Long,
        position: Int,
    ) = Candidate(
        id = UUID(0L, n),
        ownerItemId = UUID(0L, n),
        noteKey = null,
        rank = -100.0 + position,
        field = "title",
        snippet = "s",
    )

    @Test
    fun `S5 K is 60 and equals the RrfFusion constant`() {
        assertEquals(60, Ranker.K)
        assertEquals(RrfFusion.K, Ranker.K.toDouble(), 0.0)
    }

    @Test
    fun `S5 a single list scores 1 over 60 plus its 1-based position`() {
        val list = (1L..4L).mapIndexed { i, n -> cand(n, i) }
        val ranked = Ranker.rank(mapOf(Corpus.ITEM to mapOf(Analyzer.SUBSTRING to list)))
        val byId = ranked.associateBy { it.primary.id }

        assertEquals(4, ranked.size)
        (1L..4L).forEachIndexed { i, n ->
            assertEquals(1.0 / (60 + i + 1), byId.getValue(UUID(0L, n)).score, 1e-12, "position ${i + 1}")
        }
    }

    @Test
    fun `S5 a document present in both analyzer lists sums both contributions`() {
        val ranked =
            Ranker.rank(
                mapOf(
                    Corpus.ITEM to
                        mapOf(
                            Analyzer.SUBSTRING to listOf(cand(1, 0), cand(2, 1), cand(3, 2)),
                            Analyzer.STEMMED to listOf(cand(3, 0), cand(1, 1)),
                        )
                )
            )
        val byId = ranked.associateBy { it.primary.id }

        // Doc 1: position 1 and 2. Doc 2: position 2 only. Doc 3: position 3 and 1.
        assertEquals(1.0 / 61 + 1.0 / 62, byId.getValue(UUID(0L, 1)).score, 1e-12)
        assertEquals(1.0 / 62, byId.getValue(UUID(0L, 2)).score, 1e-12)
        assertEquals(1.0 / 63 + 1.0 / 61, byId.getValue(UUID(0L, 3)).score, 1e-12)
        assertEquals(3, ranked.size, "documents are fused by id, not duplicated per analyzer")
    }

    @Test
    fun `the analyzer labels keep the wire names trigram and text`() {
        assertEquals("trigram", Ranker.label(Analyzer.SUBSTRING))
        assertEquals("text", Ranker.label(Analyzer.STEMMED))
    }

    @Test
    fun `matched sources and per-analyzer ranks follow the lists a document appears in`() {
        val ranked =
            Ranker.rank(
                mapOf(
                    Corpus.ITEM to
                        mapOf(
                            Analyzer.SUBSTRING to listOf(cand(1, 0)),
                            Analyzer.STEMMED to listOf(cand(1, 0), cand(2, 1)),
                        )
                )
            )
        val byId = ranked.associateBy { it.primary.id }

        val both = byId.getValue(UUID(0L, 1))
        assertEquals(setOf("trigram", "text"), both.matchedIn.toSet())
        assertNotNull(both.trigramRank)
        assertNotNull(both.textRank)
        val textOnly = byId.getValue(UUID(0L, 2))
        assertEquals(listOf("text"), textOnly.matchedIn)
        assertNull(textOnly.trigramRank)
        assertNotNull(textOnly.textRank)
    }

    @Test
    fun `empty lists rank to nothing`() {
        assertTrue(Ranker.rank(emptyMap()).isEmpty())
        assertTrue(Ranker.rank(mapOf(Corpus.ITEM to mapOf(Analyzer.SUBSTRING to emptyList()))).isEmpty())
    }
}

/** AccessScope constructors: the explicit unrestricted scope and the principal form. */
class AccessScopeTest {
    private val root = UUID(0L, 42L)

    @Test
    fun `unrestricted has no roots no tags and reports unrestricted`() {
        val scope = AccessScope.unrestricted()
        assertTrue(scope.isUnrestricted)
        assertNull(scope.rootIds)
        assertTrue(scope.tagsInclude.isEmpty())
    }

    @Test
    fun `a principal with no roots and no tags is unrestricted`() {
        assertTrue(AccessScope.principal(rootIds = null, tagsInclude = emptySet()).isUnrestricted)
    }

    @Test
    fun `a principal with roots or tags is restricted and exposes them`() {
        val rooted = AccessScope.principal(rootIds = setOf(root), tagsInclude = emptySet())
        assertFalse(rooted.isUnrestricted)
        assertEquals(setOf(root), rooted.rootIds)

        val tagged = AccessScope.principal(rootIds = null, tagsInclude = setOf("alpha"))
        assertFalse(tagged.isUnrestricted)
        assertEquals(setOf("alpha"), tagged.tagsInclude)
        assertNull(tagged.rootIds)

        val both = AccessScope.principal(rootIds = setOf(root), tagsInclude = setOf("alpha", "beta"))
        assertFalse(both.isUnrestricted)
        assertEquals(setOf(root), both.rootIds)
        assertEquals(setOf("alpha", "beta"), both.tagsInclude)
    }
}
