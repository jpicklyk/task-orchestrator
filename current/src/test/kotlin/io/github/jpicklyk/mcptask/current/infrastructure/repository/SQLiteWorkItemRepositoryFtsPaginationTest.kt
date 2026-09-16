package io.github.jpicklyk.mcptask.current.infrastructure.repository

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.SearchHit
import io.github.jpicklyk.mcptask.current.domain.repository.SearchMatchMode
import io.github.jpicklyk.mcptask.current.domain.repository.SearchScope
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
 * FTS pagination determinism tests for [SQLiteWorkItemRepository.ftsSearch] — item 0ba7c92d.
 *
 * Independent test authorship per the `test-author` skill: derived from the item's `test-plan`
 * note (scenarios S1-S8) and the `diagnosis` note's revised RRF worked-failure and fix
 * description, both frozen at queue phase before this suite was written. All scenarios here are
 * labelled EXISTING-SURFACE in `test-plan` — the public [ftsSearch] signature and [SearchResult]
 * shape are unchanged by the fix, so a plain revert of the two `ftsSearch` bodies is expected to
 * turn this suite red (no narrowest-revert recipe needed).
 *
 * Contract under test (O1, `api-reference.md` search pagination section as revised by this
 * item's `diagnosis` §"Declared contract change"): every page is a slice of ONE fixed,
 * deterministically ordered list — fused score desc, ties broken ascending by stable domain id
 * (O2, mirroring the repo's own overview-pagination precedent) — capped at 100 fused results
 * before the offset/limit slice is applied. `totalHits` is the size of that capped list and is
 * identical across every page of the same query; `truncated` reports whether more than 100
 * matches existed. Tests deliberately use the literal `100` rather than importing
 * `MAX_FTS_RESULTS`, per `test-plan`'s instruction to stay bound to the existing, documented
 * surface rather than the new internal constant.
 *
 * RRF oracle (O3, Cormack & Clarke 2009, k=60): a fused score is `sum over matched tables of
 * 1/(60+rank)`, with `rank` the 1-indexed position of the doc within that table's own match
 * ordering — independently computed here, never read back from the implementation.
 *
 * Extends [BaseFts5RepositoryTest]: FTS5 is SQLite-only and `ftsSearch` returns an empty
 * [SearchResult] on H2, so an H2-backed harness would make every scenario below vacuously green.
 */
class SQLiteWorkItemRepositoryFtsPaginationTest : BaseFts5RepositoryTest() {
    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private suspend fun createItem(
        title: String,
        summary: String = "",
        parentId: UUID? = null,
        depth: Int = if (parentId == null) 0 else 1,
    ): WorkItem {
        val item = WorkItem(title = title, summary = summary, parentId = parentId, depth = depth)
        val result = repo().create(item)
        assertIs<Result.Success<WorkItem>>(result)
        return result.data
    }

    private fun repo(): SQLiteWorkItemRepository = repositoryProvider.workItemRepository() as SQLiteWorkItemRepository

    private fun approxEquals(
        a: List<Double>,
        b: List<Double>,
        eps: Double = 1e-9,
    ) = a.size == b.size && a.zip(b).all { (x, y) -> abs(x - y) < eps }

    // ────────────────────────────────────────────────────────────────────────
    // S1 — successive pages partition the bulk result with no duplicates or skips
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 - pages at offsets 0,10,20 partition the bulk result with no duplicates or skips`(): Unit =
        runBlocking {
            val marker = "s1partitionmark"
            val items = (1..25).map { i -> createItem(title = "$marker item $i", summary = "payload $i") }
            val itemIds = items.map { it.id }.toSet()
            assertEquals(25, itemIds.size, "Fixture setup sanity: 25 distinct items")

            val bulk =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(25, bulk.hits.size, "Expected all 25 seeded items in the uncapped bulk call")
            assertEquals(itemIds, bulk.hits.map { it.itemId }.toSet())

            val pages =
                (0 until 25 step 10).map { off ->
                    repo().ftsSearch(
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
                "Concatenation of offset 0/10/20 pages must equal the bulk call element-for-element"
            )
            assertEquals(
                concatenated.size,
                concatenated.map { it.itemId }.toSet().size,
                "No item should repeat across pages"
            )

            // Probe: replay/idempotency — repeating the identical call returns the identical order.
            val replay =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(
                bulk.hits.map { it.itemId },
                replay.hits.map { it.itemId },
                "Repeating the same call must return the identical order (replay/idempotency)"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // S2 — a page boundary landing inside a tied-score group is ordered by ascending itemId
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 - pages crossing a tied-score boundary are ordered by ascending itemId at the tie`(): Unit =
        runBlocking {
            // Exactly 3 items whose ONLY fts-table match is the trigram table (the query substring
            // is glued inside a longer single token, so the porter/text tokenizer indexes the whole
            // run as one distinct token that never matches the bare query) and exactly 3 whose ONLY
            // match is the text table (the real word "catalog", which porter-stems identically to
            // "cataloging" but is never a literal substring of it — same technique as the existing
            // suite's "authenticated"/"authentication" porter test). With exactly 3 single-table
            // candidates in EACH table, RRF assigns ranks {1,2,3} within each table — a permutation,
            // not necessarily matching physical creation order — so the six fused scores are
            // guaranteed (O3, k=60) to be the multiset {1/61,1/61,1/62,1/62,1/63,1/63} regardless of
            // which specific row lands at which rank.
            val trigramOnly = (1..3).map { i -> createItem(title = "zzzcatalogingzzz$i") }
            val textOnly = (1..3).map { i -> createItem(title = "catalog", summary = "tie filler $i") }

            val bulk =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"cataloging\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )

            val allIds = (trigramOnly + textOnly).map { it.id }.toSet()
            assertEquals(allIds, bulk.hits.map { it.itemId }.toSet(), "Expected exactly the 6 seeded tie-fixture items")
            assertEquals(6, bulk.hits.size)

            // O3: independently derived score multiset (never read back from the implementation).
            val expectedScoreMultiset = listOf(1.0 / 61, 1.0 / 61, 1.0 / 62, 1.0 / 62, 1.0 / 63, 1.0 / 63).sorted()
            val actualScoreMultiset = bulk.hits.map { it.score }.sorted()
            assertTrue(
                approxEquals(expectedScoreMultiset, actualScoreMultiset),
                "Expected score multiset $expectedScoreMultiset (RRF k=60, ranks 1..3 in each single-matched table), got $actualScoreMultiset"
            )

            // Confirm genuine ties: grouping by (approximate) score yields exactly 3 groups of 2.
            val groups = bulk.hits.groupBy { hit -> expectedScoreMultiset.first { abs(it - hit.score) < 1e-9 } }
            assertEquals(3, groups.size, "Expected three distinct score levels")
            groups.values.forEach { group ->
                assertEquals(2, group.size, "Expected each score level to hold exactly 2 tied items")
            }

            // O1+O2: total order is score desc, ties broken ascending by itemId (java.util.UUID's
            // signed-long ordering, not string ordering).
            val expectedOrder =
                bulk.hits.sortedWith(
                    compareByDescending<SearchHit> { it.score }.thenBy { it.itemId }
                )
            assertEquals(
                expectedOrder.map { it.itemId },
                bulk.hits.map { it.itemId },
                "Bulk order must already be sorted by score desc, then itemId asc"
            )

            // Paging straight through every tie group must reproduce the bulk order exactly.
            val paged =
                (0 until 6 step 2).flatMap { off ->
                    repo()
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

            // Boundary landing INSIDE the first tie pair (offset=1 splits it after its first member).
            val firstOfPair =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"cataloging\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 1,
                    offset = 0
                )
            val secondOfPair =
                repo().ftsSearch(
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
                "Splitting a tied pair across a page boundary must not duplicate the same item"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // S3 — totalHits and truncated are offset-independent
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 - totalHits and truncated are identical across offsets 0,10,20 of the same query`(): Unit =
        runBlocking {
            val marker = "s3invariantmark"
            repeat(25) { i -> createItem(title = "$marker entry $i") }

            val at0 =
                repo().ftsSearch(sanitizedFtsQuery = "\"$marker\"", matchMode = SearchMatchMode.AUTO, scope = null, limit = 10, offset = 0)
            val at10 =
                repo().ftsSearch(sanitizedFtsQuery = "\"$marker\"", matchMode = SearchMatchMode.AUTO, scope = null, limit = 10, offset = 10)
            val at20 =
                repo().ftsSearch(sanitizedFtsQuery = "\"$marker\"", matchMode = SearchMatchMode.AUTO, scope = null, limit = 10, offset = 20)

            assertEquals(25, at0.totalHits)
            assertEquals(at0.totalHits, at10.totalHits, "totalHits must not depend on offset")
            assertEquals(at0.totalHits, at20.totalHits, "totalHits must not depend on offset")
            assertEquals(at0.truncated, at10.truncated)
            assertEquals(at0.truncated, at20.truncated)
            assertEquals(false, at0.truncated, "25 matches must not exceed the 100-row cap")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S4 — a doc matched in both fts tables holds the same absolute position at any offset
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 - a doc matched in both fts tables holds the same absolute position regardless of offset`(): Unit =
        runBlocking {
            // Reproduces the diagnosis's worked failure at the black-box level: a doc's position must
            // not shift depending on which offset window a caller happens to request.
            val marker = "crosstablemark"
            // 30 fillers: trigram-only (glued token, never porter-stems back to the bare word).
            repeat(30) { i -> createItem(title = "zz${marker}zz$i") }
            // X: the bare marker word appears in the title on its own, so it naturally matches BOTH
            // the trigram table (substring of itself) and the text table (porter-stems to itself).
            val x = createItem(title = marker)

            val bulk =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 100,
                    offset = 0,
                )
            assertEquals(31, bulk.hits.size, "Expected 30 trigram-only fillers plus the dual-table doc")
            val posX = bulk.hits.indexOfFirst { it.itemId == x.id }
            assertTrue(posX >= 0, "Expected the cross-table doc to appear in the bulk result")

            // Two different offset windows, both engineered (from the canonical bulk position) to
            // straddle posX, issued as two independent calls with different offsets.
            val offsetA = maxOf(0, posX - 5)
            val offsetB = maxOf(0, posX - 2)
            val limit = 15
            val pageA =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = limit,
                    offset = offsetA
                )
            val pageB =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = limit,
                    offset = offsetB
                )

            val localA = pageA.hits.indexOfFirst { it.itemId == x.id }
            val localB = pageB.hits.indexOfFirst { it.itemId == x.id }
            assertTrue(localA >= 0, "offsetA window must contain the cross-table doc")
            assertTrue(localB >= 0, "offsetB window must contain the cross-table doc")
            assertEquals(
                offsetA + localA,
                offsetB + localB,
                "The cross-table doc's absolute position must be identical regardless of the requested offset"
            )
            assertEquals(posX, offsetA + localA, "Absolute position must match the canonical bulk-call position")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S5 — offset past the match count
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 - offset=100 on a 25-match corpus returns an empty page with null nextOffset and unchanged totalHits`(): Unit =
        runBlocking {
            val marker = "s5boundarymark"
            repeat(25) { i -> createItem(title = "$marker probe $i") }

            val base =
                repo().ftsSearch(sanitizedFtsQuery = "\"$marker\"", matchMode = SearchMatchMode.AUTO, scope = null, limit = 20, offset = 0)
            val past =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 20,
                    offset = 100
                )

            assertTrue(past.hits.isEmpty(), "offset=100 on a 25-match corpus must return no hits")
            assertNull(past.nextOffset, "An offset at or beyond totalHits must yield nextOffset == null")
            assertEquals(base.totalHits, past.totalHits, "totalHits must be unchanged from the offset=0 call")
        }

    // ────────────────────────────────────────────────────────────────────────
    // Boundary probe — the 100-row cap itself (offset == totalHits, == 100, == 101)
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `boundary probe - fused list is capped at 100 with truncated=true beyond the cap`(): Unit =
        runBlocking {
            val marker = "capboundary"
            // Trigram-only match (SUBSTRING mode queries only the trigram table directly, avoiding
            // any RRF fusion complexity for this cap-focused scenario).
            repeat(110) { i -> createItem(title = "zz${marker}zz$i") }

            val atZero =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 20,
                    offset = 0
                )
            assertEquals(100, atZero.totalHits, "Fused list must be capped at 100 even though 110 items match")
            assertTrue(atZero.truncated, "truncated must be true when more than 100 matches exist")

            val atCap =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 10,
                    offset = 100
                )
            assertTrue(atCap.hits.isEmpty(), "offset == totalHits (100) must return an empty page")
            assertNull(atCap.nextOffset)
            assertEquals(100, atCap.totalHits)

            val pastCap =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 10,
                    offset = 101
                )
            assertTrue(pastCap.hits.isEmpty(), "offset == 101 (beyond the cap) must return an empty page")
            assertNull(pastCap.nextOffset)
            assertEquals(100, pastCap.totalHits)

            val lastRow =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 5,
                    offset = 99
                )
            assertEquals(1, lastRow.hits.size, "offset=99 must return exactly the 100th (last, cap-boundary) row")
            assertNull(lastRow.nextOffset, "No further rows exist beyond the cap")
        }

    // ────────────────────────────────────────────────────────────────────────
    // S7 — scope.ancestorId keeps the partition property under a deep subtree
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 - pagination partitions correctly when scoped to a deep ancestorId subtree`(): Unit =
        runBlocking {
            val marker = "deepchainmark"
            val root = createItem(title = "$marker root")
            val chain = mutableListOf(root)
            var parent = root
            repeat(6) { i ->
                val child = createItem(title = "$marker depth $i", parentId = parent.id, depth = chain.size)
                chain += child
                parent = child
            }
            // A sibling subtree that must never appear in the scoped results (wave-3 CTE depth-cap
            // guard territory: the scope must not leak outside the requested ancestor).
            val outsideRoot = createItem(title = "$marker outside root")
            createItem(title = "$marker outside child", parentId = outsideRoot.id, depth = 1)

            val scope = SearchScope(ancestorId = root.id)
            val bulk =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"$marker\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = scope,
                    limit = 100,
                    offset = 0,
                )
            val expectedIds = chain.map { it.id }.toSet()
            assertEquals(
                expectedIds,
                bulk.hits.map { it.itemId }.toSet(),
                "Scoped search must include exactly the root plus its 6-level chain, not the outside subtree"
            )
            assertEquals(7, bulk.hits.size)

            val pages =
                listOf(0, 3, 6).map { off ->
                    repo()
                        .ftsSearch(
                            sanitizedFtsQuery = "\"$marker\"",
                            matchMode = SearchMatchMode.AUTO,
                            scope = scope,
                            limit = 3,
                            offset = off,
                        ).hits
                }
            val concatenated = pages.flatten()
            assertEquals(
                bulk.hits.map { it.itemId },
                concatenated.map { it.itemId },
                "Scoped pagination must partition the scoped bulk list with no duplicates or skips"
            )
        }

    // ────────────────────────────────────────────────────────────────────────
    // Probe — empty vs. absent: a zero-match query
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe - zero-match query yields totalHits=0 and nextOffset=null`(): Unit =
        runBlocking {
            createItem(title = "Something else entirely, unrelated to the probe term")
            val result =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"unmatchedzzzprobetermxyz\"",
                    matchMode = SearchMatchMode.AUTO,
                    scope = null,
                    limit = 20,
                    offset = 0,
                )
            assertEquals(0, result.totalHits)
            assertNull(result.nextOffset)
            assertTrue(result.hits.isEmpty())
        }

    // ────────────────────────────────────────────────────────────────────────
    // Probe — mixed case: a lowercase query against uppercase-seeded titles
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `probe - case-insensitive trigram match preserves the partition property`(): Unit =
        runBlocking {
            val marker = "MixedCaseProbe"
            repeat(5) { i -> createItem(title = "Title Containing $marker Segment $i") }

            val bulk =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"${marker.lowercase()}\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 10,
                    offset = 0,
                )
            assertEquals(5, bulk.totalHits, "Lowercase query must match uppercase-seeded titles (case-insensitive trigram)")

            val page1 =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"${marker.lowercase()}\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 2,
                    offset = 0
                )
            val page2 =
                repo().ftsSearch(
                    sanitizedFtsQuery = "\"${marker.lowercase()}\"",
                    matchMode = SearchMatchMode.SUBSTRING,
                    scope = null,
                    limit = 3,
                    offset = 2
                )
            val concatenated = (page1.hits + page2.hits).map { it.itemId }
            assertEquals(
                bulk.hits.map { it.itemId },
                concatenated,
                "Paged concatenation must equal the bulk order under a case-folded query too"
            )
        }
}
