package io.github.jpicklyk.mcptask.current.application.knowledge.related

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 630a826a) for the term extraction behind the text signal of `related`:
 * scenario S6 of the frozen test-plan, once on the pure function [SimilarTerms.extract] and once through the production
 * SQLite [io.github.jpicklyk.mcptask.current.application.port.SearchIndex.similarTerms] over a real database.
 *
 * Oracle (task-scope item 3 of 630a826a, a documented algorithm): the texts are split on every non-letter-or-digit
 * character, lowercased, a token is kept when its length is at least 3, all-digit tokens and stopwords are dropped (the
 * list includes: the and for with that this from are was were will have has not but into than then they them their there
 * these those when what which while where who how all any can its our you your), the tokens are distinct, sorted by length
 * descending then lexicographically ascending, and the first `max` are taken. For an item the texts are its title, summary,
 * description and every note body; for a document its title and body. The expected lists below are recomputed by hand from
 * that rule, not copied from the implementation: "The Zephyr-alpha 2026 runs; and THE gamma42" gives the tokens the, zephyr,
 * alpha, 2026, runs, and, the, gamma42; dropping `the` and `and` (stopwords) and `2026` (all digits) leaves zephyr (6), alpha
 * (5), runs (4) and gamma42 (7), which sort as gamma42, zephyr, alpha, runs.
 *
 * Evidence note: the frozen test-plan names the signature `SimilarTerms.extract(texts, max)`; the declaration block of the
 * dispatch left this file's declaration empty, so the call shape here is taken from that frozen note (a public,
 * non-implementation source) and recorded in the manifest.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class SimilarTermsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val provider get() = db.repositoryProvider()

    private val stopwords =
        "the and for with that this from are was were will have has not but into than then they them their there these those " +
            "when what which while where who how all any can its our you your"

    // ------------------------------------------------------------------ the pure function

    @Test
    fun `S6 the sample text yields the longest terms first and respects max`() {
        val texts = listOf("The Zephyr-alpha 2026 runs; and THE gamma42")

        assertEquals(listOf("gamma42", "zephyr"), SimilarTerms.extract(texts, 2))
        assertEquals(listOf("gamma42", "zephyr", "alpha", "runs"), SimilarTerms.extract(texts, 8))
    }

    @Test
    fun `S6 every listed stopword is dropped`() {
        assertEquals(emptyList(), SimilarTerms.extract(listOf(stopwords), 8))
        assertEquals(emptyList(), SimilarTerms.extract(listOf(stopwords.uppercase()), 8), "case does not hide a stopword")
        for (word in stopwords.split(" ")) {
            assertEquals(listOf("zephyr"), SimilarTerms.extract(listOf("$word zephyr"), 8), "stopword '$word' must be dropped")
        }
    }

    @Test
    fun `S6 a token must be at least three characters long`() {
        assertEquals(listOf("abc"), SimilarTerms.extract(listOf("ab abc a xy"), 8))
        assertEquals(emptyList(), SimilarTerms.extract(listOf("ab a xy 7"), 8))
    }

    @Test
    fun `S6 all digit tokens are dropped but alphanumeric ones are kept`() {
        assertEquals(listOf("a1b2"), SimilarTerms.extract(listOf("2026 12345 a1b2 3d 007"), 8))
    }

    @Test
    fun `S6 terms are lowercased distinct and ordered by length then alphabetically`() {
        assertEquals(listOf("aaaa", "bbbb", "cccc"), SimilarTerms.extract(listOf("Bbbb aaaa BBBB cccc dd bbbb"), 8))
        assertEquals(listOf("alpha", "gamma", "beta"), SimilarTerms.extract(listOf("alpha beta", "gamma alpha"), 8), "texts are pooled")
    }

    @Test
    fun `S6 any non letter or digit character separates tokens`() {
        assertEquals(listOf("quux", "bar", "baz", "foo", "qux"), SimilarTerms.extract(listOf("foo_bar-baz.qux/quux"), 8))
        assertEquals(listOf("tabbed", "lines", "split"), SimilarTerms.extract(listOf("split\ttabbed\nlines"), 8))
    }

    @Test
    fun `S6 no text and a zero max give no terms`() {
        assertEquals(emptyList(), SimilarTerms.extract(emptyList(), 8))
        assertEquals(emptyList(), SimilarTerms.extract(listOf("", "   ", "!!! ???"), 8))
        assertEquals(emptyList(), SimilarTerms.extract(listOf("zephyr alpha"), 0))
    }

    @Test
    fun `S6 a max above the number of terms returns them all`() {
        assertEquals(listOf("zephyr", "alpha"), SimilarTerms.extract(listOf("alpha zephyr"), 50))
    }

    // ------------------------------------------------------------------ through the SQLite index

    private suspend fun item(
        title: String,
        summary: String = "",
        description: String? = null
    ): WorkItem =
        checkNotNull(provider.workItemRepository().create(WorkItem(title = title, summary = summary, description = description, depth = 0)))

    private suspend fun note(
        owner: WorkItem,
        key: String,
        body: String
    ) = checkNotNull(provider.noteRepository().upsert(Note(itemId = owner.id, key = key, role = "work", body = body)))

    private fun ref(item: WorkItem) = EntityRef(RefKind.ITEM, item.id)

    private suspend fun terms(
        ref: EntityRef,
        max: Int = 8
    ): List<String> = provider.searchIndex().similarTerms(ref, max)

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("fixture: expected Ok but was Err: ${error.code} ${error.message}")
        }

    @Test
    fun `S6 an item's terms come from its title`(): Unit =
        runBlocking {
            val a = item("The Zephyr-alpha 2026 runs; and THE gamma42")

            assertEquals(listOf("gamma42", "zephyr"), terms(ref(a), 2))
            assertEquals(listOf("gamma42", "zephyr", "alpha", "runs"), terms(ref(a), 8))
        }

    @Test
    fun `S6 an item's terms pool its title summary description and every note body`(): Unit =
        runBlocking {
            val a = item("zephyr", summary = "alpha", description = "gamma42")
            note(a, "n1", "runs")
            note(a, "n2", "wombat")

            assertEquals(listOf("gamma42", "wombat", "zephyr", "alpha", "runs"), terms(ref(a), 8))
            assertEquals(listOf("gamma42", "wombat"), terms(ref(a), 2))
        }

    @Test
    fun `S6 another item's text never leaks into the terms`(): Unit =
        runBlocking {
            val a = item("zephyr")
            val other = item("wombat quokka")
            note(other, "n", "marmoset")

            assertEquals(listOf("zephyr"), terms(ref(a)))
        }

    @Test
    fun `S6 a stopword only item has no terms`(): Unit =
        runBlocking {
            val a = item(stopwords, summary = "ab 2026")

            assertEquals(emptyList(), terms(ref(a)))
        }

    @Test
    fun `S6 a document's terms come from its title and body`(): Unit =
        runBlocking {
            val host = item("Host of documents")
            val docs = DocumentService(provider, db.unitOfWork())
            val d: Document =
                docs.write(host.id, DocumentKind.PLAN, "terms-doc", "runs and gamma42 with alpha", title = "Zephyr plan").value()

            val found = terms(EntityRef(RefKind.DOCUMENT, d.id), 8)

            assertEquals(listOf("gamma42", "zephyr", "alpha", "plan", "runs"), found)
            assertTrue("host" !in found, "the owner item's own text is not the document's")
        }
}
