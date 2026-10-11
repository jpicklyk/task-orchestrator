package io.github.jpicklyk.mcptask.current.infrastructure.sqlite.knowledge.search

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchRequest
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchService
import io.github.jpicklyk.mcptask.current.application.knowledge.search.SearchValidationException
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.port.Analyzer
import io.github.jpicklyk.mcptask.current.application.port.Candidate
import io.github.jpicklyk.mcptask.current.application.port.Corpus
import io.github.jpicklyk.mcptask.current.application.port.DocumentHitMeta
import io.github.jpicklyk.mcptask.current.application.port.ScopeFilter
import io.github.jpicklyk.mcptask.current.application.port.SearchHit
import io.github.jpicklyk.mcptask.current.application.port.SearchIndex
import io.github.jpicklyk.mcptask.current.application.port.SearchResult
import io.github.jpicklyk.mcptask.current.application.port.TextQuery
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 82f8a7db) for the DOCUMENT search corpus, against the real migrated SQLite
 * database: the [SearchIndex] adapter for [Corpus.DOCUMENT] and [SearchService] over it. Scenarios S1-S12 of the frozen
 * test-plan (all NEW-SURFACE except S7). Documents are written through [DocumentService]; every fixture term is a unique
 * nonsense word and each test has its own database.
 *
 * Oracles (task-scope of 82f8a7db, none read from the implementation):
 *  - item 1: a document candidate has `id` = the document id, `ownerItemId` = its root item, no note key, and a
 *    [DocumentHitMeta] of (kind, slug, title, version); a corpus without item facets must refuse roles / tags / traits
 *    filters with an `IllegalArgumentException` rather than ignore them.
 *  - item 3: the default corpora cover every corpus whose facets apply or whose item-facet filters are all unset; an
 *    explicit set containing DOCUMENT together with a non-empty role / tags / traits is a [SearchValidationException]
 *    whose field is `role`, else `tags`, else `traits`; an explicit empty set keeps [SearchService.EMPTY_CORPORA_MESSAGE];
 *    a document hit's title is the document's own title.
 *  - item 4 and the SearchResult paging contract ("Every page of a query is a slice of ONE ordered list": fused score
 *    descending, then kind, capped before offset and limit); kind order `document` < `item` < `note`.
 *  - D1 (a document is visible exactly when its ROOT item is admitted: listed root ids by ancestor chain, so a listed id
 *    below the root does NOT admit the root's documents; the tag allowlist is tested against the root item's own tags),
 *    D2 (archived documents are never searched), D3 (`itemId` / `ancestorId` apply through the root), D5 (field is
 *    `title` when the title matches, else `body`), D7 (plan, rule and page documents are all searchable).
 *  - ScopeFilter `principalTagsAny`: compared exactly and case-sensitively, so `%` and `_` are plain characters and a
 *    principal tag containing a comma is one element no item carries.
 *  - AC3: edits, archiving, unarchiving and deletion are visible to the next search.
 *
 * Fixture invariants: roots are stamped `root_id = id`, children inherit their parent's root and depth + 1, item tags are
 * lowercase `[a-z0-9-]` elements (WorkItem rejects uppercase), so the case and wildcard probes sit on the principal side
 * of the filter; plan bodies stay below the 65536-byte plan limit.
 *
 * Forbidden-construct declaration: none.
 */
class DocumentCorpusSearchTest {
    @RegisterExtension
    @JvmField
    val sqliteDb = SqliteTestDatabase.perMethod()

    private val provider get() = sqliteDb.repositoryProvider()
    private val index: SearchIndex get() = provider.searchIndex()
    private val documents get() = DocumentService(provider, sqliteDb.unitOfWork())
    private val searchService get() = SearchService(index, sqliteDb.unitOfWork())

    // -- fixtures ------------------------------------------------------------------------------------

    private suspend fun root(
        title: String,
        tags: String? = null,
        role: Role = Role.QUEUE,
        traits: List<String> = emptyList(),
    ): WorkItem {
        val created =
            assertNotNull(
                provider.workItemRepository().create(WorkItem(title = title, tags = tags, role = role, traits = traits, depth = 0)),
            )
        return assertNotNull(provider.workItemRepository().update(created.copy(rootId = created.id)))
    }

    private suspend fun child(
        parent: WorkItem,
        title: String,
        tags: String? = null,
    ): WorkItem =
        assertNotNull(
            provider.workItemRepository().create(
                WorkItem(
                    title = title,
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id,
                    tags = tags,
                ),
            ),
        )

    private suspend fun note(
        owner: WorkItem,
        key: String,
        body: String,
    ): Note = assertNotNull(provider.noteRepository().upsert(Note(itemId = owner.id, key = key, role = "work", body = body)))

    private suspend fun doc(
        owner: WorkItem,
        slug: String,
        body: String,
        kind: DocumentKind = DocumentKind.PLAN,
        title: String? = null,
        expectedVersion: Int? = null,
    ): Document =
        when (val outcome = documents.write(owner.id, kind, slug, body, title = title, expectedVersion = expectedVersion)) {
            is Outcome.Ok -> outcome.value
            is Outcome.Err -> fail("fixture: writing document '$slug' failed: ${outcome.error.code} ${outcome.error.message}")
        }

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was Err: ${error.code} ${error.message}")
        }

    private suspend fun candidates(
        term: String,
        filter: ScopeFilter = ScopeFilter(),
        analyzer: Analyzer = Analyzer.SUBSTRING,
    ): List<Candidate> = index.candidates(Corpus.DOCUMENT, TextQuery(listOf(term)), analyzer, filter)

    private suspend fun run(
        query: String,
        corpora: Set<Corpus>? = null,
        itemId: UUID? = null,
        ancestorId: UUID? = null,
        roles: Set<Role>? = null,
        tags: List<String>? = null,
        traits: List<String>? = null,
        limit: Int = 100,
        offset: Int = 0,
        access: AccessScope = AccessScope.unrestricted(),
    ): SearchResult =
        searchService.search(
            SearchRequest(
                query = query,
                corpora = corpora,
                itemId = itemId,
                ancestorId = ancestorId,
                roles = roles,
                tags = tags,
                traits = traits,
                limit = limit,
                offset = offset,
                access = access,
            ),
        )

    private val docOnly = setOf(Corpus.DOCUMENT)

    private fun SearchResult.docSlugs(): Set<String> = hits.filter { it.kind == "document" }.mapNotNull { it.slug }.toSet()

    private fun SearchResult.kinds(): Set<String> = hits.map { it.kind }.toSet()

    private fun key(hit: SearchHit): String = listOf(hit.kind, hit.itemId, hit.noteKey, hit.documentKind, hit.slug).joinToString("|")

    /** The paging contract's order: fused score descending, ties broken by kind (`document` < `item` < `note`). */
    private fun assertFusedOrder(hits: List<SearchHit>) {
        hits.zipWithNext().forEach { (a, b) ->
            assertTrue(
                a.score > b.score || (a.score == b.score && a.kind <= b.kind),
                "hits must be ordered by score descending then kind: ${a.kind}/${a.score} before ${b.kind}/${b.score}",
            )
        }
    }

    private fun assertKindCounts(
        hits: List<SearchHit>,
        vararg expected: Pair<String, Int>,
    ) {
        expected.forEach { (kind, count) -> assertEquals(count, hits.count { it.kind == kind }, "hits of kind $kind: $hits") }
    }

    // -- S1 / S2 / S4: the candidate and hit shape ----------------------------------------------------

    @Test
    fun `S1 a document candidate carries the document id its root as owner no note key and its metadata`(): Unit =
        runBlocking {
            val host = root("Host of the plan")
            val plan = doc(host, "plan-alpha", "The zorblaxquux plan body", title = "Alpha plan")
            doc(host, "plan-other", "nothing relevant in this one")

            val found = candidates("zorblaxquux").single()

            assertEquals(plan.id, found.id)
            assertEquals(host.id, found.ownerItemId)
            assertNull(found.noteKey)
            assertEquals("body", found.field)
            assertEquals(DocumentHitMeta(kind = "plan", slug = "plan-alpha", title = "Alpha plan", version = 1), found.document)
            assertTrue("<mark>" in found.snippet && "zorblaxquux" in found.snippet, "snippet marks the match: ${found.snippet}")
        }

    @Test
    fun `S2 the field is title when the title matches and body otherwise and a null title stays null`(): Unit =
        runBlocking {
            val host = root("Host of field docs")
            doc(host, "both", "zorblaxquux in the body", title = "zorblaxquux in the title")
            doc(host, "title-only", "plain body text", title = "zorblaxquux title only")
            doc(host, "no-title", "zorblaxquux body of an untitled document")
            doc(host, "other-title", "zorblaxquux body again", title = "an unrelated title")

            val found = candidates("zorblaxquux")

            assertEquals(
                mapOf("both" to "title", "title-only" to "title", "no-title" to "body", "other-title" to "body"),
                found.associate { assertNotNull(it.document).slug to it.field },
            )
            assertNull(found.single { it.document?.slug == "no-title" }.document?.title)
            assertTrue(
                "<mark>" in found.single { it.document?.slug == "title-only" }.snippet,
                "a title match carries a marked snippet from the title",
            )
        }

    @Test
    fun `S4 plan rule and page documents are all searchable with their own kind`(): Unit =
        runBlocking {
            val host = root("Host of kinds")
            doc(host, "a-plan", "zorblaxquux plan", kind = DocumentKind.PLAN)
            doc(host, "a-rule", "zorblaxquux rule", kind = DocumentKind.RULE)
            doc(host, "a-page", "zorblaxquux page", kind = DocumentKind.PAGE)
            val expected = mapOf("a-plan" to "plan", "a-rule" to "rule", "a-page" to "page")

            assertEquals(
                expected,
                candidates("zorblaxquux").associate { assertNotNull(it.document).let { meta -> meta.slug to meta.kind } }
            )
            assertEquals(
                expected,
                run("zorblaxquux", docOnly).hits.associate { assertNotNull(it.slug) to assertNotNull(it.documentKind) },
            )
        }

    // -- S3: the default corpora -----------------------------------------------------------------------

    @Test
    fun `S3 the default search returns item note and document hits in one ordered list`(): Unit =
        runBlocking {
            val hostItem = root("zorblaxquux host item")
            val noteOwner = root("Owner of the note")
            note(noteOwner, "n1", "the zorblaxquux note body")
            val docRoot = root("Root of the document")
            val titled = doc(docRoot, "plan-one", "the zorblaxquux document body", title = "Document title")
            val untitled = doc(docRoot, "plan-two", "another zorblaxquux document body")

            val result = run("zorblaxquux")

            assertEquals(setOf("item", "note", "document"), result.kinds())
            assertEquals(4, result.totalHits)
            assertEquals(4, result.hits.size)
            assertKindCounts(result.hits, "item" to 1, "note" to 1, "document" to 2)
            val itemHit = result.hits.single { it.kind == "item" }
            assertEquals(hostItem.id, itemHit.itemId)
            assertEquals("zorblaxquux host item", itemHit.title)
            val noteHit = result.hits.single { it.kind == "note" }
            assertEquals(noteOwner.id, noteHit.itemId)
            assertEquals("n1", noteHit.noteKey)
            assertEquals("Owner of the note", noteHit.title, "a note hit carries the owning item's title")
            val docHit = result.hits.single { it.slug == "plan-one" }
            assertEquals("document", docHit.kind)
            assertEquals(docRoot.id, docHit.itemId)
            assertNull(docHit.noteKey)
            assertEquals("plan", docHit.documentKind)
            assertEquals("body", docHit.field)
            assertEquals("Document title", docHit.title, "a document hit's title is the document's own title, not its root item's")
            assertNull(result.hits.single { it.slug == "plan-two" }.title, "an untitled document has no title")
            assertEquals(setOf(titled.slug, untitled.slug), result.docSlugs())
            assertFusedOrder(result.hits)
        }

    // -- S5: itemId / ancestorId through the root -------------------------------------------------------

    @Test
    fun `S5 itemId and ancestorId match a document through its root and a non-root id finds none`(): Unit =
        runBlocking {
            val rootA = root("Root A")
            val inA = child(rootA, "Child of A")
            val rootB = root("Root B")
            doc(rootA, "doc-a", "zorblaxquux of A")
            doc(rootB, "doc-b", "zorblaxquux of B")

            assertEquals(setOf("doc-a"), run("zorblaxquux", docOnly, itemId = rootA.id).docSlugs())
            assertEquals(setOf("doc-a"), run("zorblaxquux", docOnly, ancestorId = rootA.id).docSlugs())
            assertEquals(setOf("doc-b"), run("zorblaxquux", docOnly, itemId = rootB.id).docSlugs())
            assertEquals(emptySet(), run("zorblaxquux", docOnly, itemId = inA.id).docSlugs(), "a child of the root owns no documents")
            assertEquals(emptySet(), run("zorblaxquux", docOnly, ancestorId = inA.id).docSlugs())
            assertEquals(setOf("doc-a", "doc-b"), run("zorblaxquux", docOnly).docSlugs(), "control: unfiltered finds both")
        }

    // -- S6 / S7: filter compatibility -----------------------------------------------------------------

    @Test
    fun `S6 an explicit document corpus with role tags or traits is a validation error on the first offending field`(): Unit =
        runBlocking {
            val host = root("Host of validation")
            doc(host, "plan-a", "zorblaxquux plan")
            val explicitSets =
                listOf(
                    docOnly,
                    setOf(Corpus.ITEM, Corpus.DOCUMENT),
                    setOf(Corpus.NOTE, Corpus.DOCUMENT),
                    setOf(Corpus.ITEM, Corpus.NOTE, Corpus.DOCUMENT),
                )

            for (corpora in explicitSets) {
                assertEquals(
                    "role",
                    assertFailsWith<SearchValidationException>("role with $corpora") {
                        run("zorblaxquux", corpora, roles = setOf(Role.QUEUE))
                    }.field,
                )
                assertEquals(
                    "tags",
                    assertFailsWith<SearchValidationException>("tags with $corpora") {
                        run("zorblaxquux", corpora, tags = listOf("api"))
                    }.field,
                )
                assertEquals(
                    "traits",
                    assertFailsWith<SearchValidationException>("traits with $corpora") {
                        run("zorblaxquux", corpora, traits = listOf("t1"))
                    }.field,
                )
                assertEquals(
                    "role",
                    assertFailsWith<SearchValidationException>("role wins with $corpora") {
                        run("zorblaxquux", corpora, roles = setOf(Role.QUEUE), tags = listOf("api"), traits = listOf("t1"))
                    }.field,
                )
                assertEquals(
                    "tags",
                    assertFailsWith<SearchValidationException>("tags wins over traits with $corpora") {
                        run("zorblaxquux", corpora, tags = listOf("api"), traits = listOf("t1"))
                    }.field,
                )
            }
        }

    @Test
    fun `S6 empty filters add no condition and an explicit item and note set accepts item filters`(): Unit =
        runBlocking {
            val host = root("zorblaxquux host", tags = "api", traits = listOf("t1"))
            doc(host, "plan-a", "zorblaxquux plan")

            // Empty is "no filter", distinct from a non-empty filter and from an absent one.
            assertEquals(setOf("plan-a"), run("zorblaxquux", docOnly, tags = emptyList(), traits = emptyList()).docSlugs())
            assertEquals(setOf("plan-a"), run("zorblaxquux", docOnly, roles = emptySet()).docSlugs())

            val itemsAndNotes = run("zorblaxquux", setOf(Corpus.ITEM, Corpus.NOTE), roles = setOf(Role.QUEUE), tags = listOf("api"))
            assertEquals(setOf("item"), itemsAndNotes.kinds())
            assertEquals(1, itemsAndNotes.totalHits)

            val empty = assertFailsWith<SearchValidationException> { run("zorblaxquux", emptySet()) }
            assertEquals(SearchService.EMPTY_CORPORA_MESSAGE, empty.message)
        }

    @Test
    fun `S7 the default corpora with role tags or traits keep the item and note result and skip documents`(): Unit =
        runBlocking {
            val host = root("zorblaxquux tagged host", tags = "api", role = Role.QUEUE, traits = listOf("t1"))
            doc(host, "plan-a", "zorblaxquux plan body")
            val other = root("zorblaxquux other host", tags = "beta", role = Role.WORK)
            doc(other, "plan-b", "zorblaxquux other plan body")
            val itemAndNote = setOf(Corpus.ITEM, Corpus.NOTE)

            val filters: List<Triple<Set<Role>?, List<String>?, List<String>?>> =
                listOf(
                    Triple(setOf(Role.QUEUE), null, null),
                    Triple(null, listOf("api"), null),
                    Triple(null, null, listOf("t1")),
                    Triple(setOf(Role.QUEUE), listOf("api"), listOf("t1")),
                )
            for ((roles, tags, traits) in filters) {
                val defaulted = run("zorblaxquux", roles = roles, tags = tags, traits = traits)
                val explicit = run("zorblaxquux", itemAndNote, roles = roles, tags = tags, traits = traits)

                assertEquals(setOf("item"), defaulted.kinds(), "filters $roles $tags $traits: only item hits, no document hit")
                assertEquals(listOf(host.id), defaulted.hits.map { it.itemId })
                assertEquals(
                    explicit.hits.map { key(it) },
                    defaulted.hits.map { key(it) },
                    "same ordered result as an explicit item+note set"
                )
                assertEquals(explicit.totalHits, defaulted.totalHits)
            }
        }

    // -- S8 / S9: archive, delete, overwrite ---------------------------------------------------------------

    @Test
    fun `S8 an archived document never hits and hits again once unarchived`(): Unit =
        runBlocking {
            val host = root("Host of archive")
            doc(host, "plan-a", "zorblaxquux plan", title = "Archive candidate")
            doc(host, "plan-b", "zorblaxquux sibling")

            documents.archive(host.id, DocumentKind.PLAN, "plan-a").value()

            assertEquals(setOf("plan-b"), run("zorblaxquux", docOnly).docSlugs())
            assertEquals(setOf("plan-b"), candidates("zorblaxquux").map { assertNotNull(it.document).slug }.toSet())
            assertEquals(
                emptySet(),
                run("archive candidate", docOnly).docSlugs(),
                "the title of an archived document is not searchable either"
            )

            documents.unarchive(host.id, DocumentKind.PLAN, "plan-a").value()

            assertEquals(setOf("plan-a", "plan-b"), run("zorblaxquux", docOnly).docSlugs())
            assertEquals(setOf("plan-a"), run("archive candidate", docOnly).docSlugs(), "control: the title hits again once unarchived")
        }

    @Test
    fun `S9 a deleted document no longer hits and an overwrite replaces the searchable text`(): Unit =
        runBlocking {
            val host = root("Host of edits")
            doc(host, "doomed", "zorblaxquux doomed text")
            doc(host, "edited", "oldfrobnicate text", title = "oldtitleword")

            documents.delete(host.id, DocumentKind.PLAN, "doomed").value()
            assertEquals(emptySet(), run("zorblaxquux", docOnly).docSlugs())

            doc(host, "edited", "newplinkazoid text", title = "newtitleword", expectedVersion = 1)

            assertEquals(emptySet(), run("oldfrobnicate", docOnly).docSlugs(), "the old body is gone")
            assertEquals(emptySet(), run("oldtitleword", docOnly).docSlugs(), "the old title is gone")
            assertEquals(setOf("edited"), run("newplinkazoid", docOnly).docSlugs(), "the new body is searchable at once")
            assertEquals(setOf("edited"), run("newtitleword", docOnly).docSlugs(), "the new title is searchable at once")
            assertEquals(
                2,
                assertNotNull(candidates("newplinkazoid").single().document).version,
                "the hit reports the version of the document that matched",
            )
        }

    // -- S10: root id scope ------------------------------------------------------------------------------

    @Test
    fun `S10 a listed root id admits that root's documents and a listed id below the root or an empty set admits none`(): Unit =
        runBlocking {
            val rootA = root("Root A")
            val inA = child(rootA, "Child of A")
            val rootB = root("Root B")
            doc(rootA, "doc-a", "zorblaxquux of A")
            doc(rootB, "doc-b", "zorblaxquux of B")

            suspend fun slugs(rootIds: Set<UUID>?): Set<String> =
                run("zorblaxquux", docOnly, access = AccessScope.principal(rootIds, emptySet())).docSlugs()

            assertEquals(setOf("doc-a"), slugs(setOf(rootA.id)))
            assertEquals(setOf("doc-b"), slugs(setOf(rootB.id)))
            assertEquals(setOf("doc-a", "doc-b"), slugs(setOf(rootA.id, rootB.id)))
            assertEquals(emptySet(), slugs(setOf(inA.id)), "a listed id below the root does not admit the root's documents")
            assertEquals(emptySet(), slugs(emptySet()), "an empty root set admits nothing")
            assertEquals(setOf("doc-a", "doc-b"), slugs(null), "control: no root restriction admits both")
            assertEquals(setOf("doc-a", "doc-b"), run("zorblaxquux", docOnly).docSlugs(), "control: unrestricted access admits both")
        }

    // -- S11: tag scope -------------------------------------------------------------------------------------

    @Test
    fun `S11 the tag allowlist is tested on the root item exactly and case sensitively`(): Unit =
        runBlocking {
            val tagged = root("Root tagged", tags = "x,team")
            val lookalike = root("Root lookalike", tags = "teamx")
            val other = root("Root other", tags = "xteam")
            val untagged = root("Root untagged")
            val childOnly = root("Root with tagged child")
            child(childOnly, "Tagged child", tags = "team")
            doc(tagged, "doc-tagged", "zorblaxquux tagged")
            doc(lookalike, "doc-lookalike", "zorblaxquux lookalike")
            doc(other, "doc-other", "zorblaxquux other")
            doc(untagged, "doc-untagged", "zorblaxquux untagged")
            doc(childOnly, "doc-child-only", "zorblaxquux child only")

            suspend fun slugs(
                vararg tags: String,
                rootIds: Set<UUID>? = null,
            ): Set<String> = run("zorblaxquux", docOnly, access = AccessScope.principal(rootIds, tags.toSet())).docSlugs()

            assertEquals(
                setOf("doc-tagged"),
                slugs("team"),
                "team as a non-first element; teamx, xteam, untagged and a tagged child are excluded"
            )
            assertEquals(setOf("doc-tagged"), slugs("x"))
            assertEquals(setOf("doc-tagged", "doc-lookalike"), slugs("x", "teamx"), "several principal tags are OR")
            assertEquals(emptySet(), slugs("Team"), "case sensitive: Team is not team")
            assertEquals(emptySet(), slugs("te%"), "% is a literal")
            assertEquals(emptySet(), slugs("%"), "a lone % is a literal, not a wildcard")
            assertEquals(emptySet(), slugs("t_am"), "_ is a literal")
            assertEquals(emptySet(), slugs("x,team"), "a principal tag containing a comma is one element no item carries")
            assertEquals(emptySet(), slugs("team", rootIds = setOf(lookalike.id)), "the tag scope is ANDed with the root scope")
            assertEquals(setOf("doc-tagged"), slugs("team", rootIds = setOf(tagged.id, lookalike.id)))
            assertEquals(
                setOf("doc-tagged", "doc-lookalike", "doc-other", "doc-untagged", "doc-child-only"),
                slugs(),
                "control: an empty allowlist admits every root",
            )
        }

    // -- S12: paging ------------------------------------------------------------------------------------------

    @Test
    fun `S12 pages of a mixed item and document result partition one ordered list with a stable total`(): Unit =
        runBlocking {
            val host = root("Host of paging docs")
            repeat(25) { n ->
                root("pgtoken item ${n.toString().padStart(2, '0')}")
                doc(host, "pg-doc-${n.toString().padStart(2, '0')}", "pgtoken document body $n")
            }

            val full = run("pgtoken", limit = 100)
            val pages = listOf(0, 20, 40).map { run("pgtoken", limit = 20, offset = it) }

            assertEquals(50, full.totalHits)
            assertEquals(50, full.hits.size)
            assertNull(full.nextOffset)
            assertEquals(setOf("item", "document"), full.kinds())
            assertEquals(25, full.hits.count { it.kind == "document" })
            assertEquals(
                50,
                full.hits
                    .map { key(it) }
                    .toSet()
                    .size,
                "no hit appears twice"
            )
            assertFusedOrder(full.hits)
            pages.forEach { assertEquals(50, it.totalHits, "the total is the same on every page") }
            assertEquals(listOf(20, 20, 10), pages.map { it.hits.size })
            assertEquals(
                full.hits.map { key(it) },
                pages.flatMap { page ->
                    page.hits.map { key(it) }
                },
                "the pages are slices of the one list"
            )
            assertEquals(listOf(20, 40, null), pages.map { it.nextOffset })
            assertEquals(
                full.hits.map { key(it) },
                run("pgtoken", limit = 100).hits.map { key(it) },
                "replaying the query gives the same ordered list"
            )
        }

    // -- defence in depth at the index ------------------------------------------------------------------------

    @Test
    fun `a document candidate query with an item facet filter is refused and an item query accepts it`(): Unit =
        runBlocking {
            val host = root("zorblaxquux host", tags = "api", traits = listOf("t1"))
            doc(host, "plan-a", "zorblaxquux plan")

            assertFailsWith<IllegalArgumentException> { candidates("zorblaxquux", ScopeFilter(roles = setOf(Role.QUEUE))) }
            assertFailsWith<IllegalArgumentException> { candidates("zorblaxquux", ScopeFilter(tagsAny = listOf("api"))) }
            assertFailsWith<IllegalArgumentException> { candidates("zorblaxquux", ScopeFilter(traitsAny = listOf("t1"))) }

            val itemCandidates =
                index.candidates(
                    Corpus.ITEM,
                    TextQuery(listOf("zorblaxquux")),
                    Analyzer.SUBSTRING,
                    ScopeFilter(roles = setOf(Role.QUEUE), tagsAny = listOf("api"), traitsAny = listOf("t1")),
                )
            assertEquals(listOf(host.id), itemCandidates.map { it.ownerItemId }, "control: the same facets are valid for the ITEM corpus")
            assertEquals(1, candidates("zorblaxquux").size, "control: the document candidate query itself works")
        }

    // -- probes -------------------------------------------------------------------------------------------------

    @Test
    fun `probe a term that appears only in the slug does not hit`(): Unit =
        runBlocking {
            val host = root("Host of slugs")
            doc(host, "slugonlyzorbish", "an ordinary body", title = "An ordinary title")

            assertEquals(emptySet(), run("slugonlyzorbish", docOnly).docSlugs())
            assertEquals(emptyList(), candidates("slugonlyzorbish"))
        }

    @Test
    fun `probe CRLF line endings and a match near the end of a 63 KB body`(): Unit =
        runBlocking {
            val host = root("Host of long bodies")
            doc(host, "crlf-doc", "first line" + CRLF + "second zorblaxquux line" + CRLF + "third line" + CRLF)
            doc(host, "long-doc", "filler ".repeat(9000) + " tailzorbtoken end")

            val crlf = run("zorblaxquux", docOnly).hits.single()
            assertEquals("crlf-doc", crlf.slug)
            assertTrue("zorblaxquux" in crlf.snippet && "<mark>" in crlf.snippet, "snippet: ${crlf.snippet}")

            val long = run("tailzorbtoken", docOnly).hits.single()
            assertEquals("long-doc", long.slug)
            assertEquals("body", long.field)
            assertTrue("<mark>" in long.snippet && "tailzorbtoken" in long.snippet, "snippet: ${long.snippet}")
        }

    @Test
    fun `probe search syntax characters in the query are literal for documents`(): Unit =
        runBlocking {
            val host = root("Host of syntax")
            doc(host, "plan-a", "zorblaxquux plan")

            // A star is a literal character, not a prefix wildcard; none of these calls may fail.
            assertEquals(0, run("zorblax*", docOnly).totalHits)
            // A stray quote or colon is not search syntax either: the calls complete, and one document cannot give two hits.
            assertTrue(run(QUOTE + "zorblaxquux", docOnly).totalHits <= 1)
            assertTrue(run("zorblaxquux:", docOnly).totalHits <= 1)
            assertEquals(1, run("zorblaxquux", docOnly).totalHits, "control: the plain term hits")
        }

    @Test
    fun `probe two documents with the same slug under different roots are distinct hits`(): Unit =
        runBlocking {
            val rootA = root("Root A")
            val rootB = root("Root B")
            doc(rootA, "shared-slug", "zorblaxquux of A")
            doc(rootB, "shared-slug", "zorblaxquux of B")

            val hits = run("zorblaxquux", docOnly).hits

            assertEquals(2, hits.size)
            assertEquals(setOf(rootA.id, rootB.id), hits.map { it.itemId }.toSet())
            assertEquals(setOf("shared-slug"), hits.mapNotNull { it.slug }.toSet())
        }

    private companion object {
        const val CRLF = "\r\n"
        const val QUOTE = "\""
    }
}
