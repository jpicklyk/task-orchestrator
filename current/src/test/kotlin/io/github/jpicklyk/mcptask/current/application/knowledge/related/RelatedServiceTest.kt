package io.github.jpicklyk.mcptask.current.application.knowledge.related

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.CycleTriggers
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementInterceptor
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independent tests (test-author seat, item 630a826a) for [RelatedService] over a real migrated SQLite database, the
 * production search index and the production unit of work: scenarios S1-S5, S9-S15 and S18 of the frozen test-plan.
 *
 * Oracles (task-scope of 630a826a, never read from the implementation): the weighted RRF
 * `score(e) = sum over signals of w_s / (60 + r_s(e))` with `r_s` the 1-based position in that signal's list and weights
 * EDGES 1.0, LINKS 1.0, TAGS 0.5, TEXT 0.5; the order score desc, then kind (`document` before `item`), then id asc; the
 * cap of 50 applied before slicing (`total` is the capped size, `capped` true when the fused list was longer); the signal
 * definitions of item 1 (edges: RELATES_TO counterparties first then BLOCKS, tie id asc, reasons `relates_to` / `blocks` /
 * `blocked_by`; links: any relation, a note folds to its owner, rank by the number of link rows desc, reasons `links_to` /
 * `linked_from`, dangling links, missing targets and archived documents excluded; tags: score the sum of 1/df over the
 * shared tags, df counted over in-scope items without the ref, reason `shared_tags:<n>`; text: the terms of the ref ORed,
 * stemmed, over item, note and document, reason `text`); the ref never returned (also via its own notes); the scope rules
 * of item 2 (default bound is the ref's root for every signal, `acrossRoots` lifts only that, the access scope applies
 * BEFORE fusion and cap, a document is visible exactly when its root item is, out-of-scope results are dropped).
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId` = its parent, `depth` = parent depth + 1 and the parent's root; a document is written against a root; item
 * titles are unrelated made-up words so that a signal under test is the only one that can fire (the text signal ORs the
 * ref's own terms, so no fixture title shares a stemmed word with the ref's title, summary or notes unless the scenario
 * wants the text signal); tags are lowercase `[a-z0-9-]` elements.
 *
 * Not asserted (no oracle): the order of two entries tied on both link-row count and kind (the id comparison of a freshly
 * generated document id), the keys of `explain`, and the outcome for a ref that is outside the principal's scope (the wire
 * answers `forbidden`, owned by the wire suite).
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RelatedServiceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val provider get() = db.repositoryProvider()
    private val docs by lazy { DocumentService(provider, db.unitOfWork()) }
    private val service by lazy { RelatedService(index = provider.searchIndex(), unitOfWork = db.unitOfWork()) }

    // ------------------------------------------------------------------ fixtures

    private fun uid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

    private suspend fun root(
        title: String,
        tags: String? = null,
        id: UUID = UUID.randomUUID()
    ): WorkItem = checkNotNull(provider.workItemRepository().create(WorkItem(id = id, rootId = id, title = title, depth = 0, tags = tags)))

    private suspend fun child(
        parent: WorkItem,
        title: String,
        tags: String? = null,
        id: UUID = UUID.randomUUID(),
        summary: String = ""
    ): WorkItem =
        checkNotNull(
            provider.workItemRepository().create(
                WorkItem(
                    id = id,
                    title = title,
                    summary = summary,
                    parentId = parent.id,
                    depth = parent.depth + 1,
                    rootId = parent.rootId ?: parent.id,
                    tags = tags
                )
            )
        )

    private suspend fun note(
        owner: WorkItem,
        key: String,
        body: String
    ): Note = checkNotNull(provider.noteRepository().upsert(Note(itemId = owner.id, key = key, role = "work", body = body)))

    private suspend fun dep(
        from: WorkItem,
        to: WorkItem,
        type: DependencyType
    ) {
        provider.dependencyRepository().create(Dependency(fromItemId = from.id, toItemId = to.id, type = type))
    }

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("fixture: expected Ok but was Err: ${error.code} ${error.message}")
        }

    private suspend fun doc(
        owner: WorkItem,
        slug: String,
        body: String = "An ordinary body without special words",
        title: String? = null
    ): Document = docs.write(owner.id, DocumentKind.PLAN, slug, body, title = title).value()

    private fun item(ref: WorkItem) = EntityRef(RefKind.ITEM, ref.id)

    private fun docRef(d: Document) = EntityRef(RefKind.DOCUMENT, d.id)

    private fun noteRef(n: Note) = EntityRef(RefKind.NOTE, n.id)

    private suspend fun link(
        rootItem: WorkItem,
        from: EntityRef,
        to: EntityRef?,
        relation: LinkRelation = LinkRelation.REFERENCES,
        toRef: String? = null
    ) {
        provider.linkStore().insert(Link(UUID.randomUUID(), rootItem.id, from, to, toRef, relation, null, Instant.now()))
    }

    private suspend fun related(
        ref: WorkItem,
        limit: Int = 50,
        offset: Int = 0,
        across: Boolean = false,
        access: AccessScope = AccessScope.unrestricted()
    ): RelatedResult =
        service.related(RelatedRequest(ref = item(ref), limit = limit, offset = offset, acrossRoots = across, access = access))

    private fun RelatedResult.titles(): List<String?> = hits.map { it.title }

    private fun RelatedResult.sortedTitles(): List<String> = hits.mapNotNull { it.title }.sorted()

    private fun RelatedResult.byTitle(title: String): RelatedHit = hits.single { it.title == title }

    private fun scoreOf(
        weight: Double,
        rank: Int
    ): Double = weight / (60 + rank)

    private fun assertScore(
        expected: Double,
        hit: RelatedHit
    ) = assertEquals(expected, hit.score, 1e-9, "score of '${hit.title}'")

    // ------------------------------------------------------------------ S1: edges

    @Test
    fun `S1 edges order RELATES_TO first then BLOCKS by id and name the direction in the reason`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val a = child(p, "Kelmora")
            val b = child(p, "Plinthax", id = uid(2))
            val c = child(p, "Wombrel", id = uid(3))
            dep(r, a, DependencyType.RELATES_TO)
            dep(r, b, DependencyType.BLOCKS)
            dep(c, r, DependencyType.BLOCKS)

            val result = related(r)

            assertEquals(listOf<String?>("Kelmora", "Plinthax", "Wombrel"), result.titles())
            assertEquals(3, result.total)
            assertFalse(result.capped)
            assertEquals(listOf(RelatedService.REASON_RELATES_TO), result.byTitle("Kelmora").reasons)
            assertEquals(listOf(RelatedService.REASON_BLOCKS), result.byTitle("Plinthax").reasons, "the ref blocks it")
            assertEquals(listOf(RelatedService.REASON_BLOCKED_BY), result.byTitle("Wombrel").reasons, "it blocks the ref")
            assertScore(scoreOf(1.0, 1), result.byTitle("Kelmora"))
            assertScore(scoreOf(1.0, 2), result.byTitle("Plinthax"))
            assertScore(scoreOf(1.0, 3), result.byTitle("Wombrel"))
            assertEquals(mapOf(RelatedSignal.EDGES to 1), result.byTitle("Kelmora").ranks)
            assertEquals(item(a), result.byTitle("Kelmora").ref)
        }

    @Test
    fun `S1 the same counterpart through two RELATES_TO rows is one hit`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val a = child(p, "Kelmora")
            dep(r, a, DependencyType.RELATES_TO)
            dep(a, r, DependencyType.RELATES_TO)

            val result = related(r)

            assertEquals(1, result.hits.count { it.title == "Kelmora" }, "one hit per entity: ${result.hits}")
            assertEquals(1, result.total)
            assertEquals(setOf(RelatedService.REASON_RELATES_TO), result.byTitle("Kelmora").reasons.toSet())
        }

    // ------------------------------------------------------------------ S2 / S18: links and documents

    @Test
    fun `S2 links rank by the number of link rows then name the direction and a note folds to its item`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val holder = note(r, "k", "plumbline anchor")
            val d = doc(p, "doc-d", title = "Document dee")
            val dx = doc(p, "doc-x", title = "Document ex")
            val dy = doc(p, "doc-y", title = "Document why")
            link(p, noteRef(holder), docRef(d), LinkRelation.DERIVED_FROM, "plan:doc-d")
            link(p, docRef(dy), item(r), LinkRelation.REFERENCES)
            link(p, docRef(dy), item(r), LinkRelation.ADOPTED_BY)
            link(p, docRef(dx), item(r), LinkRelation.REFERENCES)

            val result = related(r)

            assertEquals(3, result.total)
            val first = result.hits.first()
            assertEquals(docRef(dy), first.ref, "two link rows outrank one: ${result.hits}")
            assertEquals(listOf(RelatedService.REASON_LINKED_FROM), first.reasons)
            assertScore(scoreOf(1.0, 1), first)
            val rest = result.hits.drop(1)
            assertEquals(setOf(docRef(dx), docRef(d)), rest.map { it.ref }.toSet())
            assertEquals(listOf(RelatedService.REASON_LINKED_FROM), rest.single { it.ref == docRef(dx) }.reasons)
            assertEquals(
                listOf(RelatedService.REASON_LINKS_TO),
                rest.single { it.ref == docRef(d) }.reasons,
                "a link of the item's note is the item's link"
            )
            assertEquals(setOf(scoreOf(1.0, 2), scoreOf(1.0, 3)), rest.map { it.score }.toSet())
        }

    @Test
    fun `S18 a document hit carries its root kind and slug title and version`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val d = doc(p, "plan-link", title = "Titled plan")
            val untitled = doc(p, "plan-bare")
            link(p, docRef(d), item(r))
            link(p, docRef(untitled), item(r), LinkRelation.ADOPTED_BY)

            val result = related(r)

            val hit = result.hits.single { it.ref == docRef(d) }
            assertEquals(RelatedDocument(rootId = p.id, kind = "plan", slug = "plan-link"), hit.document)
            assertEquals("Titled plan", hit.title)
            assertEquals(d.version.toLong(), hit.version)
            val bare = result.hits.single { it.ref == docRef(untitled) }
            assertEquals(RelatedDocument(rootId = p.id, kind = "plan", slug = "plan-bare"), bare.document)
            assertNull(bare.title, "a document without a title has none")
        }

    @Test
    fun `S18 an item hit has no document block and carries the current item version`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val a = child(p, "Kelmora")
            dep(r, a, DependencyType.RELATES_TO)
            val current = checkNotNull(provider.workItemRepository().getById(a.id))

            val hit = related(r).hits.single()

            assertNull(hit.document)
            assertEquals(current.version.toLong(), hit.version)
        }

    // ------------------------------------------------------------------ S3: tags

    @Test
    fun `S3 tags score the sum of one over the document frequency and break ties by shared count then id`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath", tags = "tagonea,tagtwob")
            child(p, "Vellum", tags = "tagonea,tagtwob")
            child(p, "Xylem", tags = "tagonea")
            child(p, "Yonder", tags = "tagtwob", id = uid(10))
            child(p, "Zenith", tags = "tagtwob", id = uid(11))
            // Lookalike tags: an element that merely contains the shared tag is not that tag.
            child(p, "Wisp", tags = "tagonea-x")
            child(p, "Wraith", tags = "xtagonea")

            val result = related(r)

            assertEquals(listOf<String?>("Vellum", "Xylem", "Yonder", "Zenith"), result.titles(), "lookalike tags and the ref are absent")
            assertEquals(listOf(RelatedService.REASON_SHARED_TAGS_PREFIX + "2"), result.byTitle("Vellum").reasons)
            for (title in listOf("Xylem", "Yonder", "Zenith")) {
                assertEquals(listOf(RelatedService.REASON_SHARED_TAGS_PREFIX + "1"), result.byTitle(title).reasons, title)
            }
            assertScore(scoreOf(0.5, 1), result.byTitle("Vellum"))
            assertScore(scoreOf(0.5, 2), result.byTitle("Xylem"))
            assertScore(scoreOf(0.5, 3), result.byTitle("Yonder"))
            assertScore(scoreOf(0.5, 4), result.byTitle("Zenith"))
            assertEquals(mapOf(RelatedSignal.TAGS to 1), result.byTitle("Vellum").ranks)
        }

    @Test
    fun `S3 a rare shared tag outranks a common one even with the same shared count`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath", tags = "rareone,commontwo")
            child(p, "Rarely", tags = "rareone")
            repeat(3) { child(p, "Common$it", tags = "commontwo") }

            val result = related(r)

            assertEquals("Rarely", result.hits.first().title, "df(rareone)=1 beats df(commontwo)=3: ${result.titles()}")
            assertEquals(4, result.total)
        }

    // ------------------------------------------------------------------ S4: text

    @Test
    fun `S4 a rare word of the ref found in another item's notes yields a text hit and a duplicate note folds`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Quasarium")
            val x = child(p, "Brindlewick")
            note(x, "n1", "the quasarium reading")
            note(x, "n2", "a second quasarium remark")
            child(p, "Dissimilar")

            val result = related(r)

            assertEquals(listOf<String?>("Brindlewick"), result.titles(), "the item appears once however many notes match")
            assertEquals(listOf(RelatedService.REASON_TEXT), result.hits.single().reasons)
            assertEquals(item(x), result.hits.single().ref)
            assertEquals(mapOf(RelatedSignal.TEXT to 1), result.hits.single().ranks)
            assertScore(scoreOf(0.5, 1), result.hits.single())
        }

    @Test
    fun `S4 text covers item titles and document bodies and uses any of the terms`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Quasarium nebulon")
            val byTitle = child(p, "Quasarium only")
            val byDoc = doc(p, "text-doc", body = "mentions nebulon once", title = "Doc title")
            child(p, "Unrelated")

            val result = related(r)

            assertEquals(setOf(item(byTitle), docRef(byDoc)), result.hits.map { it.ref }.toSet())
            result.hits.forEach { assertEquals(listOf(RelatedService.REASON_TEXT), it.reasons) }
        }

    // ------------------------------------------------------------------ S5: fusion

    @Test
    fun `S5 the fused score is the weighted reciprocal rank sum and orders edge text and tag hits`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Quasarium", tags = "kitsune")
            val e = child(p, "Elmorra", id = uid(1))
            val b = child(p, "Quasarium notes brigade", id = uid(2))
            val t = child(p, "Tilwen", tags = "kitsune")
            dep(r, e, DependencyType.RELATES_TO)
            dep(r, b, DependencyType.RELATES_TO)

            val result = related(r)

            assertEquals(listOf(item(b), item(e), item(t)), result.hits.map { it.ref }, "scores: ${result.hits.map { it.score }}")
            assertEquals(1.0 / 62 + 0.5 / 61, result.hits[0].score, 1e-9)
            assertEquals(1.0 / 61, result.hits[1].score, 1e-9)
            assertEquals(0.5 / 61, result.hits[2].score, 1e-9)
            assertEquals(listOf(RelatedService.REASON_RELATES_TO, RelatedService.REASON_TEXT), result.hits[0].reasons, "signal order")
            assertEquals(mapOf(RelatedSignal.EDGES to 2, RelatedSignal.TEXT to 1), result.hits[0].ranks)
            assertEquals(mapOf(RelatedSignal.TAGS to 1), result.hits[2].ranks)
        }

    @Test
    fun `S5 an entity found by every signal lists the reasons in signal order`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Quasarium", tags = "kitsune")
            val holder = note(r, "k", "plumbline")
            val m = child(p, "Quasarium meets tag", tags = "kitsune")
            dep(r, m, DependencyType.RELATES_TO)
            link(p, noteRef(holder), item(m), LinkRelation.REFERENCES)

            val result = related(r)

            val hit = result.hits.single { it.ref == item(m) }
            assertEquals(
                listOf(
                    RelatedService.REASON_RELATES_TO,
                    RelatedService.REASON_LINKS_TO,
                    RelatedService.REASON_SHARED_TAGS_PREFIX + "1",
                    RelatedService.REASON_TEXT
                ),
                hit.reasons
            )
            assertEquals(1.0 / 61 + 1.0 / 61 + 0.5 / 61 + 0.5 / 61, hit.score, 1e-9, "rank 1 in each of the four lists")
            assertEquals(
                mapOf(RelatedSignal.EDGES to 1, RelatedSignal.LINKS to 1, RelatedSignal.TAGS to 1, RelatedSignal.TEXT to 1),
                hit.ranks
            )
        }

    // ------------------------------------------------------------------ S12: the ref is never a hit

    @Test
    fun `S12 the ref is never returned not by its own note text nor by its own note links`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Quillonary")
            val own = note(r, "k", "quillonary appears in my own note")
            link(p, noteRef(own), item(r))
            val x = child(p, "Brindlewick")
            note(x, "n", "quillonary found here too")

            val result = related(r)

            assertEquals(listOf(item(x)), result.hits.map { it.ref })
            assertFalse(result.hits.any { it.ref == item(r) })
            assertEquals(1, result.total)
        }

    @Test
    fun `S12 a ref that is the only holder of its tag has no tag hits`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath", tags = "solo")

            val result = related(r)

            assertTrue(result.hits.isEmpty(), "the only holder of the tag is the ref: ${result.hits}")
            assertEquals(0, result.total)
        }

    // ------------------------------------------------------------------ S13: excluded targets

    @Test
    fun `S13 dangling links links to a missing item and archived documents are excluded`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "Zorvath")
            val holder = note(r, "k", "plumbline")
            val live = doc(p, "live-doc", title = "Live doc")
            val archived = doc(p, "arch-doc", title = "Archived doc")
            docs.archive(p.id, DocumentKind.PLAN, "arch-doc").value()
            link(p, noteRef(holder), docRef(live), LinkRelation.REFERENCES, "plan:live-doc")
            link(p, noteRef(holder), docRef(archived), LinkRelation.REFERENCES, "plan:arch-doc")
            link(p, noteRef(holder), null, LinkRelation.REFERENCES, "plan:never-written")
            link(p, item(r), EntityRef(RefKind.ITEM, UUID.randomUUID()), LinkRelation.REFERENCES)

            val result = related(r)

            assertEquals(listOf(docRef(live)), result.hits.map { it.ref }, "only the live resolved target: ${result.hits}")
            assertEquals(1, result.total)
        }

    // ------------------------------------------------------------------ S14: cap and paging

    private suspend fun bulk(
        count: Int,
        tag: String
    ): Pair<WorkItem, List<WorkItem>> {
        val p = root("Hangar container")
        val r = child(p, "Zorvath", tags = tag)
        val peers = (1..count).map { n -> child(p, "Satellite ${n.toString().padStart(3, '0')}", tags = tag, id = uid(1000 + n)) }
        return r to peers
    }

    @Test
    fun `S14 more than fifty fused hits are capped at fifty and pages partition the capped list`(): Unit =
        runBlocking {
            val (r, peers) = bulk(60, "bulktag")

            val pages = (0 until 50 step 10).map { related(r, limit = 10, offset = it) }

            pages.forEach {
                assertEquals(50, it.total, "total is the capped size on every page")
                assertTrue(it.capped, "the fused list had 60")
                assertEquals(10, it.hits.size)
            }
            val seen = pages.flatMap { page -> page.hits.map { it.ref } }
            assertEquals(50, seen.toSet().size, "no hit on two pages")
            assertEquals(peers.take(50).map { item(it) }, seen, "equal scores order by id ascending and the cap keeps the first fifty")
            val past = related(r, limit = 10, offset = 50)
            assertTrue(past.hits.isEmpty(), "past the cap")
            assertEquals(50, past.total)
        }

    @Test
    fun `S14 exactly fifty hits are not capped`(): Unit =
        runBlocking {
            val (r, _) = bulk(50, "fiftytag")

            val exact = related(r, limit = 50)

            assertEquals(50, exact.total)
            assertFalse(exact.capped, "fifty is not over the cap")
            assertEquals(50, exact.hits.size)
        }

    @Test
    fun `S14 fifty one hits report the cap`(): Unit =
        runBlocking {
            val (r, _) = bulk(51, "overtag")

            val over = related(r, limit = 50)

            assertEquals(50, over.total)
            assertTrue(over.capped, "fifty one is over the cap")
            assertEquals(50, over.hits.size)
        }

    @Test
    fun `S14 the same call twice returns the same order and scores`(): Unit =
        runBlocking {
            val (r, _) = bulk(20, "twicetag")

            val first = related(r, limit = 20)
            val second = related(r, limit = 20)

            assertEquals(first.hits.map { it.ref }, second.hits.map { it.ref })
            assertEquals(first.hits.map { it.score }, second.hits.map { it.score })
        }

    @Test
    fun `S14 limit and offset slice the same ordered list and the total is independent of the page`(): Unit =
        runBlocking {
            val (r, peers) = bulk(12, "slicetag")

            val one = related(r, limit = 1)
            val fifty = related(r, limit = 50)
            val shifted = related(r, limit = 5, offset = 3)

            assertEquals(1, one.hits.size)
            assertEquals(12, one.total)
            assertEquals(peers.map { item(it) }, fifty.hits.map { it.ref })
            assertEquals(peers.drop(3).take(5).map { item(it) }, shifted.hits.map { it.ref })
            assertNotNull(one.hits.single().version)
        }

    // ------------------------------------------------------------------ S15: nothing to relate

    @Test
    fun `S15 a stopword only ref with no edges links or tags has no hits and no error`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "the and for with")
            child(p, "the and for with that")

            val result = related(r)

            assertTrue(result.hits.isEmpty(), "stopwords are not search terms: ${result.hits}")
            assertEquals(0, result.total)
            assertFalse(result.capped)
        }

    @Test
    fun `S15 a ref with no terms of length three at all has no hits`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, "zz 42")
            child(p, "zz 42 again")

            val result = related(r)

            assertEquals(0, result.total)
            assertTrue(result.hits.isEmpty())
        }

    // ------------------------------------------------------------------ S11 / S9 / S10: scope

    private class Cross(
        val a: WorkItem,
        val b: WorkItem,
        val r: WorkItem
    )

    /** Root A holds the ref and one counterpart per signal; root B holds one more per signal. */
    private suspend fun cross(): Cross {
        val a = root("Alpha yard")
        val b = root("Beta yard")
        val r = child(a, "Quillonary", tags = "sharedtag")
        val holder = note(r, "k", "plumbline anchor")
        val ein = child(a, "Ember")
        val eout = child(b, "Frostel")
        dep(r, ein, DependencyType.RELATES_TO)
        dep(r, eout, DependencyType.RELATES_TO)
        val din = doc(a, "doc-in", title = "Inner document")
        val dout = doc(b, "doc-out", title = "Outer document")
        link(a, noteRef(holder), docRef(din), LinkRelation.REFERENCES, "plan:doc-in")
        link(a, noteRef(holder), docRef(dout), LinkRelation.REFERENCES, "plan:doc-out")
        child(a, "Tandem", tags = "sharedtag")
        child(b, "Umbral", tags = "sharedtag")
        val xin = child(a, "Xanthe")
        note(xin, "n", "quillonary in alpha")
        val xout = child(b, "Yarrow")
        note(xout, "n", "quillonary in beta")
        return Cross(a, b, r)
    }

    private val inScope = listOf("Ember", "Inner document", "Tandem", "Xanthe")
    private val outOfScope = listOf("Frostel", "Outer document", "Umbral", "Yarrow")

    @Test
    fun `S11 counterparts of other roots are absent by default for every signal`(): Unit =
        runBlocking {
            val c = cross()

            val result = related(c.r)

            assertEquals(inScope.sorted(), result.sortedTitles(), "default bound is the ref's root: ${result.titles()}")
            assertEquals(4, result.total)
        }

    @Test
    fun `S11 acrossRoots lifts the root bound for every signal`(): Unit =
        runBlocking {
            val c = cross()

            val result = related(c.r, across = true)

            assertEquals((inScope + outOfScope).sorted(), result.sortedTitles(), "all eight: ${result.titles()}")
            assertEquals(8, result.total)
        }

    @Test
    fun `S9 a root scoped principal gets only its own root's counterparts even with acrossRoots for every signal`(): Unit =
        runBlocking {
            val c = cross()
            val access = AccessScope.principal(rootIds = setOf(c.a.id), tagsInclude = emptySet())

            val result = related(c.r, across = true, access = access)

            assertEquals(inScope.sorted(), result.sortedTitles(), "out-of-scope never returned: ${result.titles()}")
            assertEquals(4, result.total, "out-of-scope results do not count either")
            assertFalse(result.hits.any { it.document?.rootId == c.b.id })
        }

    @Test
    fun `S9 a principal listing both roots sees both roots' counterparts`(): Unit =
        runBlocking {
            val c = cross()
            val access = AccessScope.principal(rootIds = setOf(c.a.id, c.b.id), tagsInclude = emptySet())

            val both = related(c.r, across = true, access = access)

            assertEquals(8, both.total, "control: the fixture holds eight counterparts")
        }

    @Test
    fun `S9 scope applies before the cap so a restricted caller still gets a full page`(): Unit =
        runBlocking {
            val a = root("Alpha yard")
            val b = root("Beta yard")
            val r = child(a, "Quillonary", tags = "pgone,pgtwo")
            repeat(12) { child(a, "Aitem ${it.toString().padStart(2, '0')}", tags = "pgone") }
            repeat(15) { child(b, "Bitem ${it.toString().padStart(2, '0')}", tags = "pgone,pgtwo") }
            val access = AccessScope.principal(rootIds = setOf(a.id), tagsInclude = emptySet())

            val page = related(r, limit = 10, across = true, access = access)
            val rest = related(r, limit = 10, offset = 10, across = true, access = access)

            assertEquals(10, page.hits.size, "out-of-scope entries ranked above must not take page slots: ${page.titles()}")
            assertEquals(12, page.total)
            assertTrue(page.titles().all { it!!.startsWith("Aitem") }, "${page.titles()}")
            assertEquals(2, rest.hits.size)
            assertEquals(12, rest.total)
            assertEquals(12, (page.hits + rest.hits).map { it.ref }.toSet().size)
            assertEquals(12 + 15, related(r, limit = 50, across = true).total, "control: unrestricted sees both roots")
        }

    @Test
    fun `S9 a ref outside the principal's root scope never has its own root's counterparts returned`(): Unit =
        runBlocking {
            val c = cross()
            val access = AccessScope.principal(rootIds = setOf(c.b.id), tagsInclude = emptySet())

            val result = related(c.r, across = true, access = access)

            assertFalse(result.hits.any { it.title in inScope }, "the ref's own root is not admitted: ${result.titles()}")
            assertFalse(result.hits.any { it.ref == item(c.r) })
        }

    @Test
    fun `S10 a tag scoped principal gets only items carrying an allowed tag and documents of such roots`(): Unit =
        runBlocking {
            val a = root("Alpha yard")
            val b = root("Beta yard", tags = "team")
            val c = root("Gamma yard")
            val r = child(a, "Quillonary", tags = "team,sharedx")
            val holder = note(r, "k", "plumbline anchor")
            val eok = child(a, "Ember", tags = "team")
            val ebad = child(a, "Frostel")
            dep(r, eok, DependencyType.RELATES_TO)
            dep(r, ebad, DependencyType.RELATES_TO)
            child(a, "Tandem", tags = "team,sharedx")
            child(a, "Umbral", tags = "sharedx")
            val xok = child(a, "Xanthe", tags = "team")
            note(xok, "n", "quillonary with the team tag")
            val xbad = child(a, "Yarrow")
            note(xbad, "n", "quillonary without any tag")
            val dok = doc(b, "doc-team", title = "Team document")
            val dbad = doc(c, "doc-plain", title = "Plain document")
            link(a, noteRef(holder), docRef(dok), LinkRelation.REFERENCES, "plan:doc-team")
            link(a, noteRef(holder), docRef(dbad), LinkRelation.REFERENCES, "plan:doc-plain")
            val access = AccessScope.principal(rootIds = null, tagsInclude = setOf("team"))

            val scoped = related(r, across = true, access = access)
            val open = related(r, across = true)

            assertEquals(
                listOf("Beta yard", "Ember", "Tandem", "Team document", "Xanthe"),
                scoped.sortedTitles(),
                "only entries carrying an allowed tag (a document by its root's tag): ${scoped.titles()}"
            )
            assertEquals(5, scoped.total)
            val unrestricted = open.titles().toSet()
            for (hidden in listOf("Frostel", "Umbral", "Yarrow", "Plain document")) {
                assertTrue(hidden in unrestricted, "control: '$hidden' is visible to the unrestricted caller: $unrestricted")
                assertFalse(hidden in scoped.titles(), "'$hidden' must be hidden")
            }
        }

    @Test
    fun `S10 a lookalike allowed tag does not admit an item`(): Unit =
        runBlocking {
            val a = root("Alpha yard")
            val r = child(a, "Quillonary", tags = "team,sharedx")
            child(a, "Lookalike", tags = "teamx,sharedx")
            child(a, "Exact", tags = "team,sharedx")
            val access = AccessScope.principal(rootIds = null, tagsInclude = setOf("team"))

            val result = related(r, access = access)

            assertEquals(listOf<String?>("Exact"), result.titles())
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe an unstamped depth zero ref bounds the search to its own tree`(): Unit =
        runBlocking {
            val top = checkNotNull(provider.workItemRepository().create(WorkItem(title = "Unstamped top", depth = 0, tags = "treetag")))
            val kid = child(top, "Kidling", tags = "treetag")
            val other = root("Other yard")
            child(other, "Stranger", tags = "treetag")

            val result = related(top)

            assertEquals(listOf<String?>("Kidling"), result.titles(), "the ref's root is itself: ${result.titles()}")
            assertEquals(item(kid), result.hits.single().ref)
        }

    @Test
    fun `probe a corrupt parent cycle above the ref still terminates without error`(): Unit =
        runBlocking {
            val a = checkNotNull(provider.workItemRepository().create(WorkItem(title = "Cyc alpha", depth = 0)))
            val b =
                checkNotNull(
                    provider.workItemRepository().create(WorkItem(title = "Cyc beta", depth = 1, parentId = a.id, rootId = null))
                )
            CycleTriggers.drop(db.database)
            DriverManager.getConnection(db.jdbcUrl).use { conn ->
                conn.prepareStatement("UPDATE work_items SET parent_id = ? WHERE id = ?").use { st ->
                    st.setObject(1, b.id)
                    st.setObject(2, a.id)
                    st.executeUpdate()
                }
            }
            dep(b, a, DependencyType.RELATES_TO)

            val result = related(b)

            assertFalse(result.hits.any { it.ref == item(b) }, "the ref is never a hit: ${result.hits}")
            assertEquals(
                result.hits.size,
                result.hits
                    .map { it.ref }
                    .toSet()
                    .size,
                "no duplicates"
            )
        }

    @Test
    fun `probe hostile characters in the ref's text are searched literally and do not fail the call`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val r = child(p, """NOT "quoted" star* AND colon:term (paren) Quasarium""")
            val x = child(p, "Brindlewick")
            note(x, "n", "quasarium appears")

            val result = related(r)

            assertTrue(item(x) in result.hits.map { it.ref }, "the plain word still matches: ${result.titles()}")
        }

    // ------------------------------------------------------------------ statement count (no N+1)

    private class StatementCounter : StatementInterceptor {
        val sql = mutableListOf<String>()

        override fun beforeExecution(
            transaction: Transaction,
            context: StatementContext
        ) {
            sql += runCatching { context.sql(transaction) }.getOrDefault("<unprintable>")
        }
    }

    private suspend fun countStatements(block: suspend () -> Unit): List<String> =
        db.unitOfWork().read {
            val tx = TransactionManager.current()
            val counter = StatementCounter()
            tx.registerInterceptor(counter)
            try {
                block()
            } finally {
                tx.unregisterInterceptor(counter)
            }
            counter.sql
        }

    /** A ref with [notes] notes, each linking its own document, plus three edge counterparts and three tag peers. */
    private suspend fun richRef(
        p: WorkItem,
        word: String,
        notes: Int
    ): WorkItem {
        val r = child(p, word, tags = "statx")
        repeat(3) { dep(r, child(p, "Edge${word}Peer$it"), DependencyType.RELATES_TO) }
        repeat(3) { child(p, "Tag${word}Peer$it", tags = "statx") }
        repeat(notes) { n ->
            val holder = note(r, "k$n", "uniqueword${word}note$n")
            val d = doc(p, "doc-$word-$n", title = "Doc $word $n")
            link(p, noteRef(holder), docRef(d), LinkRelation.REFERENCES, "plan:doc-$word-$n")
        }
        return r
    }

    @Test
    fun `S14 the number of SQL statements does not grow with the number of notes and linked documents`(): Unit =
        runBlocking {
            val p = root("Hangar container")
            val small = richRef(p, "quillax", notes = 5)
            val large = richRef(p, "mervint", notes = 50)
            related(small)
            related(large)

            val countSmall = countStatements { related(small) }
            val countLarge = countStatements { related(large) }

            assertTrue(countSmall.isNotEmpty(), "control: the interceptor sees the statements of the call")
            assertEquals(countSmall.size, countLarge.size, "5 notes: $countSmall, 50 notes: $countLarge")
        }
}
