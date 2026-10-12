package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.createTreeOp
import io.github.jpicklyk.mcptask.current.test.itemsWrite
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.treeItemId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored behavioral tests of the links service and its write hooks (item 23faed48, seat test-author)
 * over a real SQLite database (production PRAGMAs, foreign keys ON, one unit per operation). No implementation source, diff
 * or commit was opened. Oracles are the frozen queue-phase `task-scope` decisions D4-D13 and `test-plan` S7-S18, plus the
 * `performance-baseline` note for the unchanged-rewrite id stability (S18).
 *
 * Scenario ids are the test-plan's; additions that refine a scenario carry a letter suffix (S9b, S13b, ...) and cite the
 * decision they derive from. Sources are driven through the production services only: [NoteCommandService] for notes,
 * [DocumentService] for documents, the `items_write` `create_tree` op for the tree, and [LinkService] for attach/detach and the reads.
 * Links are observed through the link store's `findFrom` and `links` row counts.
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId == rootId == <its root>` and depth 1; documents are written against a depth-0 root.
 */
class LinkServiceTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var provider: RepositoryProvider
    private lateinit var docs: DocumentService
    private lateinit var links: LinkService
    private lateinit var notes: NoteCommandService
    private lateinit var rootId: UUID
    private lateinit var childId: UUID
    private lateinit var otherRootId: UUID
    private lateinit var otherChildId: UUID

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            provider = db.repositoryProvider()
            docs = DocumentService(provider, db.unitOfWork())
            links = LinkService(provider, db.unitOfWork())
            notes = ToolExecutionContext(provider, unitOfWork = db.unitOfWork()).noteCommandService
            rootId = newRoot("Root")
            childId = newChild(rootId, "Child")
            otherRootId = newRoot("Other root")
            otherChildId = newChild(otherRootId, "Other child")
        }

    // ---------------------------------------------------------------- helpers

    private suspend fun newRoot(title: String): UUID {
        val id = UUID.randomUUID()
        return checkNotNull(provider.workItemRepository().create(WorkItem(id = id, rootId = id, title = title, depth = 0))).id
    }

    private suspend fun newChild(
        root: UUID,
        title: String,
        id: UUID = UUID.randomUUID()
    ): UUID =
        checkNotNull(provider.workItemRepository().create(WorkItem(id = id, rootId = root, parentId = root, title = title, depth = 1))).id

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was Err: ${error.code} ${error.message}")
        }

    private fun Outcome<*>.error(): DomainError =
        when (this) {
            is Outcome.Ok -> fail("expected Err but was Ok: $value")
            is Outcome.Err -> error
        }

    private suspend fun doc(
        slug: String,
        kind: DocumentKind = DocumentKind.PLAN,
        body: String = "# $slug\n",
        root: UUID = rootId
    ): Document = docs.write(root, kind, slug, body).value()

    /** Upserts a work-role note and returns its id. */
    private suspend fun upsertNote(
        itemId: UUID,
        key: String,
        body: String
    ): UUID =
        notes
            .upsert(NoteUpsertCommand(itemId, key, "work", body, null, null))
            .value()
            .note.id

    private fun noteRef(id: UUID) = EntityRef(RefKind.NOTE, id)

    private fun docRef(d: Document) = EntityRef(RefKind.DOCUMENT, d.id)

    private fun itemRef(id: UUID) = EntityRef(RefKind.ITEM, id)

    private suspend fun from(ref: EntityRef): List<Link> = provider.linkStore().findFrom(ref)

    private suspend fun refs(ref: EntityRef): List<Link> = from(ref).filter { it.relation == LinkRelation.REFERENCES }

    private suspend fun insertLink(
        from: EntityRef,
        to: EntityRef,
        toRef: String?,
        relation: LinkRelation,
        anchor: String? = null,
        root: UUID = rootId
    ): Link = provider.linkStore().insert(Link(UUID.randomUUID(), root, from, to, toRef, relation, anchor, Instant.now()))

    private fun scalar(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun linkRows(): Int = scalar("SELECT count(*) FROM links")

    private fun eventTypes(): List<String> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT type FROM events ORDER BY seq").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    // ---------------------------------------------------------------- S7 note upsert records resolved links (D4, D5)

    @Test
    fun `S7 a note referencing an existing document in its root records one resolved REFERENCES link`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val noteId = upsertNote(childId, "k", "see [[plan-a]] for details")

            val all = from(noteRef(noteId))
            assertEquals(1, all.size, "exactly one link: $all")
            val link = all.single()
            assertEquals(LinkRelation.REFERENCES, link.relation)
            assertEquals(noteRef(noteId), link.from)
            assertEquals(docRef(d), link.to)
            assertEquals("plan-a", link.toRef)
            assertNull(link.anchor)
            assertEquals(rootId, link.rootItemId)
        }

    @Test
    fun `S7 a kind-qualified target with an anchor stores toRef without the anchor and the anchor separately`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val noteId = upsertNote(childId, "k", "see [[plan:plan-a#intro]]")

            val link = refs(noteRef(noteId)).single()
            assertEquals(docRef(d), link.to)
            assertEquals("plan:plan-a", link.toRef)
            assertEquals("intro", link.anchor)
        }

    @Test
    fun `S7 a note on the root item itself resolves within that root`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val noteId = upsertNote(rootId, "k", "root note [[plan-a]]")

            val link = refs(noteRef(noteId)).single()
            assertEquals(docRef(d), link.to)
            assertEquals(rootId, link.rootItemId)
        }

    @Test
    fun `S7 a body with no double brackets records no link at all`(): Unit =
        runBlocking {
            doc("plan-a")

            val noteId = upsertNote(childId, "k", "plain text, plan-a and [single] brackets")

            assertEquals(emptyList(), from(noteRef(noteId)))
            assertEquals(0, linkRows())
        }

    @Test
    fun `S7 a note referencing an item in its own root records ITEM links for the full id and for an unambiguous 8 hex prefix`(): Unit =
        runBlocking {
            val sibling = newChild(rootId, "Sibling")
            val full = sibling.toString()
            val prefix = sibling.toString().replace("-", "").take(8)

            val noteId = upsertNote(childId, "k", "full [[item:$full]] and short [[item:$prefix]]")

            val got = refs(noteRef(noteId))
            assertTrue(got.isNotEmpty(), "at least one resolved item link expected")
            assertTrue(got.all { it.to == itemRef(sibling) }, "every item link resolves to the sibling: $got")
            assertTrue(got.all { it.relation == LinkRelation.REFERENCES && it.rootItemId == rootId })
        }

    @Test
    fun `S7 a note referencing a document is observable as a backlink of that document`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val n1 = upsertNote(childId, "k1", "[[plan-a]]")
            val n2 = upsertNote(childId, "k2", "[[plan-a]]")

            val back = links.backlinks(docRef(d))

            assertEquals(setOf(noteRef(n1), noteRef(n2)), back.filter { it.relation == LinkRelation.REFERENCES }.map { it.from }.toSet())
            assertEquals(from(noteRef(n1)).map { it.id }.toSet(), links.linksFrom(noteRef(n1)).map { it.id }.toSet())
        }

    // ---------------------------------------------------------------- S8 rewrite removes parsed links, keeps provenance (D4, D9)

    @Test
    fun `S8 rewriting a note without double brackets removes its REFERENCES links but keeps DERIVED_FROM`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val noteId = upsertNote(childId, "k", "x [[plan-a]]")
            insertLink(noteRef(noteId), docRef(d), "plan:plan-a", LinkRelation.DERIVED_FROM, anchor = "overview")
            assertEquals(setOf(LinkRelation.REFERENCES, LinkRelation.DERIVED_FROM), from(noteRef(noteId)).map { it.relation }.toSet())

            upsertNote(childId, "k", "no links now")

            val remaining = from(noteRef(noteId))
            assertEquals(listOf(LinkRelation.DERIVED_FROM), remaining.map { it.relation })
            assertEquals("overview", remaining.single().anchor)
        }

    @Test
    fun `S8 rewriting a note to different targets replaces only the REFERENCES links`(): Unit =
        runBlocking {
            val a = doc("plan-a")
            val b = doc("plan-b")
            val noteId = upsertNote(childId, "k", "[[plan-a]]")
            insertLink(noteRef(noteId), docRef(a), "plan:plan-a", LinkRelation.DERIVED_FROM)

            upsertNote(childId, "k", "[[plan-b]]")

            val got = from(noteRef(noteId))
            assertEquals(setOf(docRef(b)), got.filter { it.relation == LinkRelation.REFERENCES }.map { it.to }.toSet())
            assertEquals(1, got.count { it.relation == LinkRelation.DERIVED_FROM })
            assertEquals(2, got.size)
        }

    @Test
    fun `S8 adding a first link to a note that had none records it`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val noteId = upsertNote(childId, "k", "nothing yet")
            assertEquals(emptyList(), from(noteRef(noteId)))

            upsertNote(childId, "k", "now [[plan-a]]")

            assertEquals(docRef(d), refs(noteRef(noteId)).single().to)
        }

    // ---------------------------------------------------------------- S9 dangling and resolution on create (D6, D7)

    @Test
    fun `S9 an unresolved document target is stored dangling and a later create resolves it, a later page does not move it`(): Unit =
        runBlocking {
            val noteId = upsertNote(childId, "k", "pending [[page-x]]")
            val dangling = refs(noteRef(noteId)).single()
            assertNull(dangling.to, "no document exists yet")
            assertEquals("page-x", dangling.toRef)
            assertEquals(LinkRelation.REFERENCES, dangling.relation)

            val plan = doc("page-x", DocumentKind.PLAN)

            val resolved = refs(noteRef(noteId)).single()
            assertEquals(docRef(plan), resolved.to)
            assertEquals("page-x", resolved.toRef, "the target text is kept when resolved")

            doc("page-x", DocumentKind.PAGE)

            assertEquals(docRef(plan), refs(noteRef(noteId)).single().to, "an already-resolved link is never moved")
        }

    @Test
    fun `S9b a dangling link sourced from a document is resolved when the target document is created`(): Unit =
        runBlocking {
            val source = doc("src", body = "needs [[later]]")
            assertNull(refs(docRef(source)).single().to)

            val later = doc("later")

            assertEquals(docRef(later), refs(docRef(source)).single().to)
        }

    @Test
    fun `S9c a dangling kind-qualified target is not resolved by a document of another kind`(): Unit =
        runBlocking {
            val noteId = upsertNote(childId, "k", "[[rule:z]]")

            doc("z", DocumentKind.PLAN)
            assertNull(refs(noteRef(noteId)).single().to, "plan z is not rule:z")

            val rule = doc("z", DocumentKind.RULE)
            assertEquals(docRef(rule), refs(noteRef(noteId)).single().to)
        }

    @Test
    fun `S9d a kind-qualified dangling target is resolved by a document of that kind`(): Unit =
        runBlocking {
            val noteId = upsertNote(childId, "k", "[[page:p1]]")
            assertNull(refs(noteRef(noteId)).single().to)

            val page = doc("p1", DocumentKind.PAGE)

            assertEquals(docRef(page), refs(noteRef(noteId)).single().to)
        }

    @Test
    fun `S9e rewriting an existing document does not disturb links that already resolve to it`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val noteId = upsertNote(childId, "k", "[[plan-a]]")
            val before = refs(noteRef(noteId)).single()

            docs.write(rootId, DocumentKind.PLAN, "plan-a", "# plan-a v2\n", expectedVersion = 1).value()

            val after = refs(noteRef(noteId)).single()
            assertEquals(before.id, after.id)
            assertEquals(docRef(d), after.to)
        }
    // ---------------------------------------------------------------- S10 bare slug precedence PAGE > PLAN > RULE (D5)

    @Test
    fun `S10 a bare slug resolves PAGE over PLAN over RULE regardless of creation order`(): Unit =
        runBlocking {
            val sPage = doc("s", DocumentKind.PAGE)
            val sPlan = doc("s", DocumentKind.PLAN)
            val sRule = doc("s", DocumentKind.RULE)
            doc("w", DocumentKind.RULE)
            doc("w", DocumentKind.PLAN)
            val wPage = doc("w", DocumentKind.PAGE)
            val tRule = doc("t", DocumentKind.RULE)
            val tPlan = doc("t", DocumentKind.PLAN)
            val t2Plan = doc("t2", DocumentKind.PLAN)
            doc("t2", DocumentKind.RULE)
            val uRule = doc("u", DocumentKind.RULE)

            val noteId = upsertNote(childId, "k", "[[s]] [[w]] [[t]] [[t2]] [[u]] [[rule:s]] [[plan:s]] [[rule:t]]")

            val byRef: Map<String?, EntityRef?> = refs(noteRef(noteId)).associate { it.toRef to it.to }
            val expected: Map<String?, EntityRef?> =
                mapOf(
                    "s" to docRef(sPage),
                    "w" to docRef(wPage),
                    "t" to docRef(tPlan),
                    "t2" to docRef(t2Plan),
                    "u" to docRef(uRule),
                    "rule:s" to docRef(sRule),
                    "plan:s" to docRef(sPlan),
                    "rule:t" to docRef(tRule)
                )
            assertEquals(expected, byRef)

            // [[page:s]] resolves to the same page as bare [[s]]; it is a separate note because two spellings of one resolved target collapse (S14).
            val explicitPage = upsertNote(childId, "k2", "[[page:s]]")
            assertEquals(docRef(sPage), refs(noteRef(explicitPage)).single().to)
        }

    @Test
    fun `S10 an archived document still resolves`(): Unit =
        runBlocking {
            val d = doc("arch")
            docs.archive(rootId, DocumentKind.PLAN, "arch", expectedVersion = 1).value()

            val noteId = upsertNote(childId, "k", "[[arch]]")

            assertEquals(docRef(d), refs(noteRef(noteId)).single().to)
        }

    // ---------------------------------------------------------------- S11 root scope (D5, D6)

    @Test
    fun `S11 an item target in another root is not stored and a slug that exists only in another root is dangling`(): Unit =
        runBlocking {
            doc("only-other", root = otherRootId)
            val mine = newChild(rootId, "Mine")
            val foreignPrefix = otherChildId.toString().replace("-", "").take(8)

            val noteId = upsertNote(childId, "k", "[[item:$otherChildId]] [[item:$foreignPrefix]] [[only-other]] [[item:$mine]]")

            val got = refs(noteRef(noteId))
            assertTrue(got.none { it.to == itemRef(otherChildId) }, "a foreign item must not be linked: $got")
            val dangling = got.single { it.toRef == "only-other" }
            assertNull(dangling.to, "a slug in another root does not resolve")
            assertEquals(itemRef(mine), got.single { it.to != null }.to)
            assertEquals(2, got.size, "foreign item targets are dropped, never stored: $got")
        }

    @Test
    fun `S11 a missing item id and an ambiguous 8 hex prefix are not stored`(): Unit =
        runBlocking {
            val one = newChild(rootId, "One", UUID.fromString("0123abcd-0000-4000-8000-000000000001"))
            val two = newChild(rootId, "Two", UUID.fromString("0123abcd-0000-4000-8000-000000000002"))

            val noteId = upsertNote(childId, "k", "[[item:${UUID.randomUUID()}]] [[item:0123abcd]]")
            assertEquals(emptyList(), from(noteRef(noteId)), "missing and ambiguous item targets are not stored")

            val n2 = upsertNote(childId, "k2", "[[item:${one.toString().replace("-", "")}]]")
            assertEquals(itemRef(one), refs(noteRef(n2)).single().to, "the full 32 hex form is unambiguous")
            assertNotEquals(one, two)
        }

    @Test
    fun `S11b creating a document in another root does not resolve a dangling link`(): Unit =
        runBlocking {
            val noteId = upsertNote(childId, "k", "[[x-doc]]")
            assertNull(refs(noteRef(noteId)).single().to)

            doc("x-doc", root = otherRootId)
            assertNull(refs(noteRef(noteId)).single().to, "another root's document never resolves this root's link")

            val mine = doc("x-doc")
            assertEquals(docRef(mine), refs(noteRef(noteId)).single().to)
        }

    // ---------------------------------------------------------------- S12 document delete (D8)

    @Test
    fun `S12 deleting a document turns inbound links with a target text dangling and drops the other links touching it`(): Unit =
        runBlocking {
            val b = doc("plan-b")
            val a = doc("plan-a", body = "outbound [[plan-b]]")
            val noteId = upsertNote(childId, "k", "inbound [[plan-a]]")
            assertEquals(docRef(b), refs(docRef(a)).single().to)
            insertLink(noteRef(noteId), docRef(a), null, LinkRelation.DERIVED_FROM)

            docs.delete(rootId, DocumentKind.PLAN, "plan-a").value()

            assertEquals(emptyList(), from(docRef(a)), "outbound links of the deleted document are gone")
            val inbound = from(noteRef(noteId))
            assertEquals(1, inbound.size, "the link without a target text is deleted, the one with it stays: $inbound")
            val kept = inbound.single()
            assertEquals(LinkRelation.REFERENCES, kept.relation)
            assertNull(kept.to, "now dangling")
            assertEquals("plan-a", kept.toRef, "the target text is kept")
            assertEquals(emptyList(), provider.linkStore().findTo(docRef(a)))
        }

    @Test
    fun `S12 re-creating a deleted document resolves the dangling inbound link to the new document`(): Unit =
        runBlocking {
            val a = doc("plan-a")
            val noteId = upsertNote(childId, "k", "[[plan-a]]")
            docs.delete(rootId, DocumentKind.PLAN, "plan-a").value()
            assertNull(refs(noteRef(noteId)).single().to)

            val again = doc("plan-a")

            assertNotEquals(a.id, again.id)
            assertEquals(docRef(again), refs(noteRef(noteId)).single().to)
        }

    @Test
    fun `S12 deleting a document leaves links of other documents and other notes untouched`(): Unit =
        runBlocking {
            val keep = doc("keep")
            doc("gone")
            val n1 = upsertNote(childId, "k1", "[[keep]]")
            val n2 = upsertNote(childId, "k2", "[[gone]]")

            docs.delete(rootId, DocumentKind.PLAN, "gone").value()

            assertEquals(docRef(keep), refs(noteRef(n1)).single().to)
            assertNull(refs(noteRef(n2)).single().to)
            assertNotNull(docs.get(rootId, DocumentKind.PLAN, "keep"))
            assertNull(docs.get(rootId, DocumentKind.PLAN, "gone"))
        }

    // ---------------------------------------------------------------- S13 documents as link sources (D4, D5)

    @Test
    fun `S13 a document body naming its own slug records no self link`(): Unit =
        runBlocking {
            val d = doc("self", body = "see [[self]] and [[plan:self]] and [[other]]")

            val got = from(docRef(d))

            assertEquals(listOf("other"), got.map { it.toRef }, "only the non-self target is recorded: $got")
            assertNull(got.single().to)
        }

    @Test
    fun `S13b rewriting a document replaces its REFERENCES links but never its ATTACHED links`(): Unit =
        runBlocking {
            val d = doc("d", body = "[[x]]")
            links.attach(rootId, DocumentKind.PLAN, "d", childId).value()
            assertEquals(setOf("x"), refs(docRef(d)).map { it.toRef }.toSet())

            docs.write(rootId, DocumentKind.PLAN, "d", "[[y]]", expectedVersion = 1).value()

            assertEquals(setOf("y"), refs(docRef(d)).map { it.toRef }.toSet())
            assertEquals(listOf(itemRef(childId)), from(docRef(d)).filter { it.relation == LinkRelation.ATTACHED }.map { it.to })
        }

    @Test
    fun `S13c a rejected document write leaves its links unchanged`(): Unit =
        runBlocking {
            val d = doc("d", body = "[[a]]")

            val err = docs.write(rootId, DocumentKind.PLAN, "d", "[[zzz]]", expectedVersion = 5).error()

            assertEquals(ErrorCode.VERSION_CONFLICT, err.code)
            assertEquals(setOf("a"), from(docRef(d)).map { it.toRef }.toSet())
        }

    @Test
    fun `S13d a first document write records its links atomically with the document`(): Unit =
        runBlocking {
            val target = doc("target")

            val d = doc("source", body = "[[target]] and [[missing]]")

            val got = refs(docRef(d)).associate { it.toRef to it.to }
            val expected: Map<String?, EntityRef?> = mapOf("target" to docRef(target), "missing" to null)
            assertEquals(expected, got)
            assertTrue(refs(docRef(d)).all { it.rootItemId == rootId })
        }

    // ---------------------------------------------------------------- S14 unique-index collision on resolve (D7)

    @Test
    fun `S14 two dangling spellings of one target resolve to exactly one link without error`(): Unit =
        runBlocking {
            val noteId = upsertNote(childId, "k", "[[plan-a]] [[plan:plan-a]]")
            assertEquals(2, refs(noteRef(noteId)).size, "both spellings are stored dangling")
            assertTrue(refs(noteRef(noteId)).all { it.to == null })

            val d = docs.write(rootId, DocumentKind.PLAN, "plan-a", "# a\n").value()

            val resolved = refs(noteRef(noteId))
            assertEquals(1, resolved.size, "one resolved link, the duplicate dangling row is dropped: $resolved")
            assertEquals(docRef(d), resolved.single().to)
        }

    @Test
    fun `S14b a document source with two spellings of one pending target also resolves to one link`(): Unit =
        runBlocking {
            val source = doc("src", body = "[[later]] [[plan:later]]")
            assertEquals(2, refs(docRef(source)).size)

            val later = doc("later")

            assertEquals(listOf(docRef(later)), refs(docRef(source)).map { it.to })
        }

    // ---------------------------------------------------------------- S15 note delete (D10)

    @Test
    fun `S15 deleting a note removes every link sourced from it and only those`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val gone = upsertNote(childId, "gone", "[[plan-a]] [[nowhere]]")
            val stays = upsertNote(childId, "stays", "[[plan-a]]")
            insertLink(noteRef(gone), docRef(d), "plan:plan-a", LinkRelation.DERIVED_FROM, anchor = "a")
            assertEquals(3, from(noteRef(gone)).size)

            notes.delete(childId, "gone").value()

            assertEquals(emptyList(), from(noteRef(gone)))
            assertEquals(docRef(d), refs(noteRef(stays)).single().to)
            assertEquals(1, linkRows())
        }
    // ---------------------------------------------------------------- S16 create_tree derived_from (D11)

    private val planBody = "# Overview\nFeature overview text.\n# Task 1\nTask 1 detail text."

    private suspend fun stashPlan(project: UUID): Document {
        provider.planDocumentRepository().stash(project, "my-plan", planBody)
        return assertNotNull(provider.documentStore().get(project, DocumentKind.PLAN, "my-plan"), "fixture: the stashed plan is a document")
    }

    private fun anchors(vararg a: Triple<String, String, String>): JsonArray =
        buildJsonArray {
            a.forEach { (key, role, anchor) ->
                add(
                    buildJsonObject {
                        put("noteKey", JsonPrimitive(key))
                        put("role", JsonPrimitive(role))
                        put("anchor", JsonPrimitive(anchor))
                    }
                )
            }
        }

    private fun treeParams(
        project: UUID,
        rootAnchors: JsonArray? = null,
        childAnchors: JsonArray? = null,
        explicitRootRequirements: String? = null
    ): JsonObject =
        buildJsonObject {
            put(
                "root",
                buildJsonObject {
                    put("type", JsonPrimitive("test-item"))
                    put("title", JsonPrimitive("Feature X"))
                    if (rootAnchors != null) put("noteAnchors", rootAnchors)
                }
            )
            put("parentId", JsonPrimitive(project.toString()))
            put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
            if (childAnchors != null) {
                put(
                    "children",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("ref", JsonPrimitive("t1"))
                                put("type", JsonPrimitive("test-item"))
                                put("title", JsonPrimitive("Task 1"))
                                put("noteAnchors", childAnchors)
                            }
                        )
                    }
                )
            }
            if (explicitRootRequirements != null) {
                put(
                    "notes",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("itemRef", JsonPrimitive("root"))
                                put("key", JsonPrimitive("requirements"))
                                put("role", JsonPrimitive("queue"))
                                put("body", JsonPrimitive(explicitRootRequirements))
                            }
                        )
                    }
                )
            }
        }

    private suspend fun runTree(params: JsonObject): JsonObject =
        ToolExecutionContext(provider, unitOfWork = db.unitOfWork()).itemsWrite(
            createTreeOp(
                *params.entries
                    .map {
                        it.key to it.value
                    }.toTypedArray()
            )
        )

    private fun JsonObject.succeeded(): Boolean = results()[0]["ok"]!!.jsonPrimitive.boolean

    private fun JsonObject.treeRootId(): UUID = okResult(0).treeItemId(0)

    private fun JsonObject.treeChildId(): UUID = okResult(0).treeItemId(1)

    private suspend fun noteIdOf(
        itemId: UUID,
        key: String
    ): UUID = assertNotNull(provider.noteRepository().findByItemIdAndKey(itemId, key), "note $key on $itemId").id

    @Test
    fun `S16 an anchored note gets a DERIVED_FROM link to the plan document carrying the anchor string`(): Unit =
        runBlocking {
            val project = newRoot("Project")
            val d = stashPlan(project)

            val result =
                runTree(
                    treeParams(
                        project,
                        rootAnchors = anchors(Triple("requirements", "queue", "overview")),
                        childAnchors = anchors(Triple("task-scope", "queue", "task-1"))
                    )
                )

            assertTrue(result.succeeded(), "expected success; got: $result")
            val rootLink = from(noteRef(noteIdOf(result.treeRootId(), "requirements"))).single()
            assertEquals(LinkRelation.DERIVED_FROM, rootLink.relation)
            assertEquals(docRef(d), rootLink.to)
            assertEquals("plan:my-plan", rootLink.toRef)
            assertEquals("overview", rootLink.anchor)
            assertEquals(project, rootLink.rootItemId)

            val childLink = from(noteRef(noteIdOf(result.treeChildId(), "task-scope"))).single()
            assertEquals(LinkRelation.DERIVED_FROM, childLink.relation)
            assertEquals(docRef(d), childLink.to)
            assertEquals("plan:my-plan", childLink.toRef)
            assertEquals("task-1", childLink.anchor)
        }

    @Test
    fun `S16 a note whose key is overridden by an explicit note gets no DERIVED_FROM link`(): Unit =
        runBlocking {
            val project = newRoot("Project")
            stashPlan(project)

            val result =
                runTree(
                    treeParams(
                        project,
                        rootAnchors = anchors(Triple("requirements", "queue", "overview")),
                        childAnchors = anchors(Triple("task-scope", "queue", "task-1")),
                        explicitRootRequirements = "Explicit body, no links"
                    )
                )

            assertTrue(result.succeeded(), "expected success; got: $result")
            val stored = assertNotNull(provider.noteRepository().findByItemIdAndKey(result.treeRootId(), "requirements"))
            assertEquals("Explicit body, no links", stored.body)
            assertEquals(emptyList(), from(noteRef(stored.id)), "explicit note wins, so nothing derives from the plan")
            assertEquals(1, from(noteRef(noteIdOf(result.treeChildId(), "task-scope"))).size, "control: the anchored child note is linked")
        }

    @Test
    fun `S16 when one note key has two anchors only the winning anchor is recorded`(): Unit =
        runBlocking {
            val project = newRoot("Project")
            stashPlan(project)

            val result =
                runTree(
                    treeParams(
                        project,
                        rootAnchors = anchors(Triple("requirements", "queue", "overview"), Triple("requirements", "queue", "task-1"))
                    )
                )

            assertTrue(result.succeeded(), "expected success; got: $result")
            val note = assertNotNull(provider.noteRepository().findByItemIdAndKey(result.treeRootId(), "requirements"))
            val winningAnchor =
                when (note.body) {
                    "# Overview\nFeature overview text." -> "overview"
                    "# Task 1\nTask 1 detail text." -> "task-1"
                    else -> fail("unexpected materialized body: ${note.body}")
                }
            val derived = from(noteRef(note.id)).filter { it.relation == LinkRelation.DERIVED_FROM }
            assertEquals(listOf(winningAnchor), derived.map { it.anchor }, "the link names the anchor that produced the stored body")
        }

    @Test
    fun `S16 a tree that fails on a bad anchor leaves zero links`(): Unit =
        runBlocking {
            val project = newRoot("Project")
            stashPlan(project)
            assertEquals(0, linkRows(), "fixture: no links before the call")

            val result =
                runTree(
                    treeParams(
                        project,
                        rootAnchors = anchors(Triple("requirements", "queue", "does-not-exist")),
                        childAnchors = anchors(Triple("task-scope", "queue", "task-1"))
                    )
                )

            assertTrue(!result.succeeded(), "expected failure; got: $result")
            assertEquals(0, linkRows(), "a rolled-back tree leaves no link, including the valid child anchor and any adoption")
        }

    @Test
    fun `S16 a tree without anchors records no DERIVED_FROM link`(): Unit =
        runBlocking {
            val project = newRoot("Project")
            stashPlan(project)

            val result = runTree(treeParams(project))

            assertTrue(result.succeeded(), "expected success; got: $result")
            assertEquals(0, scalar("SELECT count(*) FROM links WHERE relation = 'derived_from'"))
        }

    // ---------------------------------------------------------------- S17 attach / detach (D12)

    @Test
    fun `S17 attach twice yields one ATTACHED link and the second call returns the existing link`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val first = links.attach(rootId, DocumentKind.PLAN, "plan-a", childId).value()
            val second = links.attach(rootId, DocumentKind.PLAN, "plan-a", childId).value()

            assertEquals(LinkRelation.ATTACHED, first.relation)
            assertEquals(docRef(d), first.from)
            assertEquals(itemRef(childId), first.to)
            assertEquals(rootId, first.rootItemId)
            assertEquals(first.id, second.id)
            assertEquals(1, from(docRef(d)).count { it.relation == LinkRelation.ATTACHED })
            assertEquals(listOf(first.id), links.backlinks(itemRef(childId)).map { it.id })
            assertEquals(listOf(first.id), links.linksFrom(docRef(d)).map { it.id })
        }

    @Test
    fun `S17 attach of an item in another root is invalid_request and of a missing document or item is not_found`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            assertEquals(ErrorCode.INVALID_REQUEST, links.attach(rootId, DocumentKind.PLAN, "plan-a", otherChildId).error().code)
            assertEquals(ErrorCode.NOT_FOUND, links.attach(rootId, DocumentKind.PLAN, "missing", childId).error().code)
            assertEquals(
                ErrorCode.NOT_FOUND,
                links.attach(rootId, DocumentKind.RULE, "plan-a", childId).error().code,
                "kind is part of the key"
            )
            assertEquals(ErrorCode.NOT_FOUND, links.attach(rootId, DocumentKind.PLAN, "plan-a", UUID.randomUUID()).error().code)

            assertEquals(0, from(docRef(d)).count { it.relation == LinkRelation.ATTACHED }, "a refused attach stores nothing")
            assertEquals(0, linkRows())
        }

    @Test
    fun `S17 the document's own root item can be an attach target`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val link = links.attach(rootId, DocumentKind.PLAN, "plan-a", rootId).value()

            assertEquals(itemRef(rootId), link.to)
            assertEquals(docRef(d), link.from)
        }

    @Test
    fun `S17 detach removes the attachment returning 1 then 0, never errors, and leaves other links`(): Unit =
        runBlocking {
            val d = doc("plan-a", body = "keeps [[elsewhere]]")
            val other = newChild(rootId, "Other attachment")
            links.attach(rootId, DocumentKind.PLAN, "plan-a", childId).value()
            links.attach(rootId, DocumentKind.PLAN, "plan-a", other).value()

            assertEquals(1, links.detach(rootId, DocumentKind.PLAN, "plan-a", childId))
            assertEquals(0, links.detach(rootId, DocumentKind.PLAN, "plan-a", childId))

            val remaining = from(docRef(d))
            assertEquals(listOf(itemRef(other)), remaining.filter { it.relation == LinkRelation.ATTACHED }.map { it.to })
            assertEquals(listOf("elsewhere"), remaining.filter { it.relation == LinkRelation.REFERENCES }.map { it.toRef })
        }

    @Test
    fun `S17 detach of a missing document or a never attached item returns 0 without error`(): Unit =
        runBlocking {
            doc("plan-a")

            assertEquals(0, links.detach(rootId, DocumentKind.PLAN, "missing", childId))
            assertEquals(0, links.detach(rootId, DocumentKind.PLAN, "plan-a", UUID.randomUUID()))
            assertEquals(0, links.detach(rootId, DocumentKind.PLAN, "plan-a", otherChildId))
            assertEquals(0, links.detach(rootId, DocumentKind.RULE, "plan-a", childId))
        }

    // ---------------------------------------------------------------- S18 idempotency (D4)

    @Test
    fun `S18 an identical note upsert twice leaves the same link set and does not error`(): Unit =
        runBlocking {
            doc("plan-a")
            val noteId = upsertNote(childId, "k", "[[plan-a]] and [[later]]")
            val first = from(noteRef(noteId)).sortedBy { it.toRef }

            upsertNote(childId, "k", "[[plan-a]] and [[later]]")

            val second = from(noteRef(noteId)).sortedBy { it.toRef }
            assertEquals(first.map { Triple(it.toRef, it.to, it.relation) }, second.map { Triple(it.toRef, it.to, it.relation) })
            assertEquals(
                first.map { it.id },
                second.map { it.id },
                "an unchanged rewrite inserts nothing (performance-baseline, set-difference)"
            )
        }

    @Test
    fun `S18b changing only the anchor of a kept target updates the stored anchor`(): Unit =
        runBlocking {
            val d = doc("plan-a")
            val noteId = upsertNote(childId, "k", "[[plan-a#x]]")
            assertEquals("x", refs(noteRef(noteId)).single().anchor)

            upsertNote(childId, "k", "[[plan-a#y]]")

            val link = refs(noteRef(noteId)).single()
            assertEquals("y", link.anchor, "the anchor is the target fragment of the current body (D4)")
            assertEquals(docRef(d), link.to)
        }

    @Test
    fun `S18c a repeated target in one body is stored once with the first anchor`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val noteId = upsertNote(childId, "k", "[[plan-a#first]] again [[plan-a#second]] and [[plan-a]]")

            val link = refs(noteRef(noteId)).single()
            assertEquals(docRef(d), link.to)
            assertEquals("first", link.anchor)
        }

    @Test
    fun `S18d links inside code spans and fences of a note are not recorded`(): Unit =
        runBlocking {
            doc("plan-a")
            doc("plan-b")

            val noteId = upsertNote(childId, "k", "code `[[plan-a]]` and\n```\n[[plan-a]]\n```\nreal [[plan-b]]")

            assertEquals(listOf("plan-b"), refs(noteRef(noteId)).map { it.toRef })
        }

    @Test
    fun `S18e a CRLF note body records clean target text`(): Unit =
        runBlocking {
            val d = doc("plan-a")

            val noteId = upsertNote(childId, "k", "line one\r\n[[plan-a#h]]\r\nline three\r\n")

            val link = refs(noteRef(noteId)).single()
            assertEquals(docRef(d), link.to)
            assertEquals("plan-a", link.toRef)
            assertEquals("h", link.anchor)
        }

    @Test
    fun `S18f the 257th distinct target of a note is not recorded`(): Unit =
        runBlocking {
            val body = (0 until 257).joinToString(" ") { "[[cap$it]]" }

            val noteId = upsertNote(childId, "k", body)

            val got = refs(noteRef(noteId)).map { it.toRef }.toSet()
            assertEquals(256, got.size)
            assertEquals((0 until 256).map { "cap$it" }.toSet(), got)
        }

    // ---------------------------------------------------------------- D13 no new event types

    @Test
    fun `S18g a note write that records links emits the same event types as one that records none`(): Unit =
        runBlocking {
            doc("plan-a")
            val other = newChild(rootId, "Other")

            val before = eventTypes()
            upsertNote(childId, "k", "plain body")
            val plain = eventTypes().drop(before.size)
            val mid = eventTypes()
            upsertNote(other, "k", "linked [[plan-a]] and [[missing]]")
            val linked = eventTypes().drop(mid.size)

            assertTrue(plain.isNotEmpty(), "fixture: a note write emits at least one event")
            assertEquals(plain, linked, "D13: link rows ride the owning write, no new event types")
        }
}
