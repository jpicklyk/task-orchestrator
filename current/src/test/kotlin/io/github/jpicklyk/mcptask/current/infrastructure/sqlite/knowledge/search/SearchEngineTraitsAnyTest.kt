package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.FTS_CANDIDATE_ROWS
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests for `ScopeFilter.traitsAny` on the SQLite [SearchIndex] adapter, for BOTH corpora (item 22088359,
 * Q15): scenarios S6 (candidates half) and S17 of the frozen test-plan.
 *
 * Oracles (frozen task-scope B8 and AC5, none read from the implementation): an item (ITEM corpus) or a note's OWNING item
 * (NOTE corpus) passes when it carries at least one listed trait name; names are exact and case-sensitive, elements are
 * trimmed and empty elements dropped, `null`, `[]` and `[""]` add no condition, the condition is AND-ed with every other
 * `ScopeFilter` field, and it is pushed down into the query rather than applied to a capped candidate window (the
 * `window` test gives each of ten equally matching documents its own trait and asks for each one with a window of one:
 * a post-window filter could return at most the single document the ranking happens to put first). The expected sets are
 * computed by a plain Kotlin predicate over the seeded items; the term ("traitprobe" in titles, "notetoken" in note
 * bodies) matches every seeded document, so the trait condition is the only thing that narrows the result.
 *
 * Fixture invariants: roots are stamped `root_id = id` and children take their parent's root, exactly as the production
 * write path places items; trait names are non-blank, trimmed, comma-free and unique within an item; tags are lowercase
 * `[a-z0-9-]` elements (WorkItem rejects other characters).
 */
class SearchEngineTraitsAnyTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val index: SearchIndex get() = provider.searchIndex()

    private class Doc(
        val item: WorkItem,
        val notes: Int,
    ) {
        val traits: List<String> get() = item.traits
    }

    private suspend fun root(title: String): WorkItem {
        val created = provider.workItemRepository().create(WorkItem(title = title, depth = 0))
        return assertNotNull(provider.workItemRepository().update(created.copy(rootId = created.id)))
    }

    private suspend fun child(
        parent: WorkItem,
        title: String,
        traits: List<String>,
        role: Role = Role.QUEUE,
        tags: String? = null,
        notes: Int = 1,
    ): Doc {
        val item =
            provider.workItemRepository().create(
                WorkItem(
                    title = title,
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id,
                    role = role,
                    tags = tags,
                    traits = traits,
                ),
            )
        repeat(notes) { n ->
            assertNotNull(
                provider.noteRepository().upsert(Note(itemId = item.id, key = "n$n", role = "work", body = "notetoken body $n of $title"))
            )
        }
        return Doc(item, notes)
    }

    private class Fixture(
        val rootA: WorkItem,
        val rootB: WorkItem,
        val docs: List<Doc>,
    ) {
        fun doc(name: String) = docs.single { it.item.title == "traitprobe $name" }
    }

    private suspend fun fixture(): Fixture {
        val rootA = root("Root A")
        val rootB = root("Root B")
        val docs =
            listOf(
                child(rootA, "traitprobe a", listOf("x"), Role.WORK, "alpha"),
                child(rootA, "traitprobe b", listOf("a", "x"), Role.QUEUE),
                child(rootA, "traitprobe c", listOf("y"), Role.WORK),
                child(rootA, "traitprobe d", listOf("x", "y"), Role.QUEUE, "alpha", notes = 2),
                child(rootA, "traitprobe e", emptyList(), Role.WORK),
                child(rootB, "traitprobe f", listOf("X"), Role.WORK),
                child(rootB, "traitprobe g", listOf("pct%"), Role.QUEUE),
                child(rootB, "traitprobe h", listOf("x"), Role.WORK, "alpha"),
            )
        return Fixture(rootA, rootB, docs)
    }

    /** Oracle: the documented normalisation (trim, drop empty), null or empty meaning no condition, exact membership. */
    private fun passes(
        traits: List<String>,
        wanted: List<String>?,
    ): Boolean {
        val names = wanted?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return names.isEmpty() || traits.any { it in names }
    }

    private suspend fun cands(
        corpus: Corpus,
        analyzer: Analyzer,
        filter: ScopeFilter,
        window: Int = FTS_CANDIDATE_ROWS,
    ): List<Candidate> {
        val term = if (corpus == Corpus.ITEM) "traitprobe" else "notetoken"
        return index.candidates(corpus, TextQuery(listOf(term)), analyzer, filter, window)
    }

    private fun ownerTitles(
        fx: Fixture,
        candidates: List<Candidate>,
    ): Map<String, Int> =
        candidates
            .map { c ->
                fx.docs
                    .single { it.item.id == c.ownerItemId }
                    .item.title
                    .removePrefix("traitprobe ")
            }.groupingBy { it }
            .eachCount()

    /** Expected owner title to number of candidates for [wanted] (an item has one ITEM candidate, one per note for NOTE). */
    private fun expectedFor(
        fx: Fixture,
        corpus: Corpus,
        wanted: List<String>?,
        extra: (Doc) -> Boolean = { true },
    ): Map<String, Int> =
        fx.docs
            .filter { passes(it.traits, wanted) && extra(it) }
            .associate { it.item.title.removePrefix("traitprobe ") to if (corpus == Corpus.ITEM) 1 else it.notes }

    private suspend fun assertCandidates(
        fx: Fixture,
        corpus: Corpus,
        analyzer: Analyzer,
        filter: ScopeFilter,
        wanted: List<String>?,
        extra: (Doc) -> Boolean = { true },
    ) {
        val found = cands(corpus, analyzer, filter)
        assertEquals(
            expectedFor(fx, corpus, wanted, extra),
            ownerTitles(fx, found),
            "$corpus/$analyzer traitsAny=$wanted: owners and candidate counts",
        )
        assertEquals(found.size, found.map { it.id }.toSet().size, "$corpus/$analyzer traitsAny=$wanted: no document is repeated")
    }

    // ---- S6 ----

    @Test
    fun `S6 traitsAny keeps the items carrying at least one listed name, in both analyzers`(): Unit =
        runBlocking {
            val fx = fixture()

            for (analyzer in Analyzer.entries) {
                for (wanted in listOf(listOf("x"), listOf("y"), listOf("x", "y"), listOf("a"), listOf("z"))) {
                    assertCandidates(fx, Corpus.ITEM, analyzer, ScopeFilter(traitsAny = wanted), wanted)
                }
            }
            // control: the unfiltered query matches every seeded child, so the filter is what narrows
            assertEquals(fx.docs.size, cands(Corpus.ITEM, Analyzer.SUBSTRING, ScopeFilter()).size)
            assertEquals(
                setOf("a", "b", "d", "h"),
                ownerTitles(fx, cands(Corpus.ITEM, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x")))).keys
            )
        }

    @Test
    fun `S6 for the note corpus traitsAny is evaluated on the note's owning item`(): Unit =
        runBlocking {
            val fx = fixture()

            for (analyzer in Analyzer.entries) {
                for (wanted in listOf(listOf("x"), listOf("y"), listOf("x", "y"), listOf("z"))) {
                    assertCandidates(fx, Corpus.NOTE, analyzer, ScopeFilter(traitsAny = wanted), wanted)
                }
            }
            val xNotes = cands(Corpus.NOTE, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x")))
            assertEquals(
                mapOf("a" to 1, "b" to 1, "d" to 2, "h" to 1),
                ownerTitles(fx, xNotes),
                "both notes of d, one note of each other x item"
            )
            assertEquals(
                fx.docs.sumOf {
                    it.notes
                },
                cands(Corpus.NOTE, Analyzer.SUBSTRING, ScopeFilter()).size,
                "control: every note matches unfiltered"
            )
        }

    @Test
    fun `S6 an item matching several listed names yields each candidate once`(): Unit =
        runBlocking {
            val fx = fixture()

            val items = cands(Corpus.ITEM, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x", "y", "a")))
            val notes = cands(Corpus.NOTE, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x", "y", "a")))

            assertEquals(1, items.count { it.ownerItemId == fx.doc("d").item.id }, "d carries x and y: one item candidate")
            assertEquals(2, notes.count { it.ownerItemId == fx.doc("d").item.id }, "d has two notes: two candidates, not four")
            assertEquals(1, items.count { it.ownerItemId == fx.doc("b").item.id }, "b carries a and x: one item candidate")
        }

    @Test
    fun `S6 traitsAny is AND-ed with the other scope fields in both corpora`(): Unit =
        runBlocking {
            val fx = fixture()
            val wanted = listOf("x", "y")

            for (corpus in Corpus.entries) {
                assertCandidates(
                    fx,
                    corpus,
                    Analyzer.SUBSTRING,
                    ScopeFilter(roles = setOf(Role.WORK), traitsAny = wanted),
                    wanted,
                ) { it.item.role == Role.WORK }
                assertCandidates(
                    fx,
                    corpus,
                    Analyzer.SUBSTRING,
                    ScopeFilter(tagsAny = listOf("alpha"), traitsAny = wanted),
                    wanted,
                ) { it.item.tags == "alpha" }
                assertCandidates(
                    fx,
                    corpus,
                    Analyzer.SUBSTRING,
                    ScopeFilter(rootIds = setOf(fx.rootB.id), traitsAny = wanted),
                    wanted,
                ) { it.item.rootId == fx.rootB.id }
                assertCandidates(
                    fx,
                    corpus,
                    Analyzer.SUBSTRING,
                    ScopeFilter(itemId = fx.doc("d").item.id, traitsAny = wanted),
                    wanted,
                ) { it.item.id == fx.doc("d").item.id }
                // the other scope field alone would admit these, the trait condition alone would admit them: only the AND excludes them
                assertEquals(
                    emptyMap(),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(roles = setOf(Role.WORK), traitsAny = listOf("a")))),
                    "$corpus: b carries a but is QUEUE, so WORK AND a selects nothing",
                )
                assertEquals(
                    emptyMap(),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(rootIds = setOf(fx.rootB.id), traitsAny = listOf("a")))),
                    "$corpus: b carries a but lives under root A",
                )
            }
        }

    @Test
    fun `S6 the trait condition is applied before the candidate window, not after it`(): Unit =
        runBlocking {
            val root = root("Window root")
            val docs = (0 until 10).map { child(root, "traitprobe w$it", listOf("unique-$it", "shared")) }

            for (corpus in Corpus.entries) {
                for ((index, doc) in docs.withIndex()) {
                    val found = cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("unique-$index")), window = 1)
                    assertEquals(listOf(doc.item.id), found.map { it.ownerItemId }, "$corpus: unique-$index must be found with window=1")
                }
                assertEquals(
                    1,
                    cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("shared")), window = 1).size,
                    "$corpus: the window still caps"
                )
            }
        }

    // ---- S17 ----

    @Test
    fun `S17 null, an empty list and blank elements add no condition in both corpora`(): Unit =
        runBlocking {
            val fx = fixture()

            for (corpus in Corpus.entries) {
                for (wanted in listOf(null, emptyList(), listOf(""), listOf(" "), listOf("", "  "))) {
                    assertCandidates(fx, corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = wanted), wanted)
                    assertEquals(
                        expectedFor(fx, corpus, null),
                        ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = wanted))),
                        "$corpus traitsAny=$wanted selects every document",
                    )
                }
                // a blank element beside a real name is dropped; it must not turn the filter into match-all
                val mixed = ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x", ""))))
                assertEquals(expectedFor(fx, corpus, listOf("x")), mixed, "$corpus: ['x', ''] behaves as ['x']")
                assertTrue("c" !in mixed.keys, "$corpus: c carries only y")
            }
        }

    @Test
    fun `S17 matching is exact and case-sensitive and elements are trimmed`(): Unit =
        runBlocking {
            val fx = fixture()

            for (corpus in Corpus.entries) {
                assertEquals(
                    setOf("f"),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("X")))).keys,
                    "$corpus: X is not x"
                )
                assertEquals(
                    setOf("a", "b", "d", "h"),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x")))).keys,
                    "$corpus: x does not match X",
                )
                assertEquals(
                    setOf("a", "b", "d", "h"),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf(" x ")))).keys,
                    "$corpus: ' x ' is trimmed to x",
                )
                assertEquals(
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x")))),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("x", "x", " x")))),
                    "$corpus: duplicate elements change nothing",
                )
            }
        }

    @Test
    fun `S17 special characters in a listed name are literal`(): Unit =
        runBlocking {
            val fx = fixture()

            for (corpus in Corpus.entries) {
                assertEquals(
                    setOf("g"),
                    ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("pct%")))).keys,
                    "$corpus: pct% is exact"
                )
                for (probe in listOf(
                    "%",
                    "pct_",
                    "p%",
                    "_",
                    "a,b",
                    "x') OR 1=1 --",
                    "x\" OR \"1\"=\"1",
                    "x'; DROP TABLE item_traits; --"
                )) {
                    assertEquals(
                        emptyMap(),
                        ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf(probe)))),
                        "$corpus: '$probe' matches nothing",
                    )
                }
                // the table is still there and still answers
                assertEquals(setOf("c", "d"), ownerTitles(fx, cands(corpus, Analyzer.SUBSTRING, ScopeFilter(traitsAny = listOf("y")))).keys)
            }
        }
}
