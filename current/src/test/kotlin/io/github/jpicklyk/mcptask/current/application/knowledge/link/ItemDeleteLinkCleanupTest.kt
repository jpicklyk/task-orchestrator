package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
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
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored behavioral tests of link cleanup on an item-delete cascade (item 1a1ffbc3, seat test-author) over a
 * real SQLite database (production PRAGMAs, foreign keys ON, one unit per operation). No implementation source, diff or
 * commit was opened. Oracles are the frozen queue-phase `task-scope` decisions D1-D6 and `test-plan` S1-S12 (D1 every link
 * sourced from a removed note or item goes; D2 resolved REFERENCES/ATTACHED into a removed item go and ADOPTED_BY stays;
 * D3 matching is by entity, never by `root_item_id`; D4 atomic with the delete; D5 no events and no wire change).
 *
 * The delete is driven through `ItemCommandService.delete`; notes, documents and attachments through their production
 * services; the two link kinds no writer produces here (DERIVED_FROM, item-sourced) through `LinkStore.insert`. Links are
 * observed through `LinkStore.findFrom`/`findTo` and through a full raw snapshot of the `links` table, so "every other link
 * is untouched" is a row-for-row comparison (all columns, including `created_at`), not a count.
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId` = its parent, `rootId` = the root and `depth` = parent depth + 1; documents are written against a root.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemDeleteLinkCleanupTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var provider: RepositoryProvider
    private lateinit var docs: DocumentService
    private lateinit var links: LinkService
    private lateinit var notes: NoteCommandService
    private lateinit var items: ItemCommandService
    private lateinit var rootId: UUID
    private lateinit var otherRootId: UUID
    private lateinit var docA: Document

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            provider = db.repositoryProvider()
            docs = DocumentService(provider, db.unitOfWork())
            links = LinkService(provider, db.unitOfWork())
            val ctx = ToolExecutionContext(provider, unitOfWork = db.unitOfWork())
            notes = ctx.noteCommandService
            items = ctx.itemCommandService
            rootId = newRoot("Root").id
            otherRootId = newRoot("Other root").id
            docA = doc("plan-a")
        }

    // ---------------------------------------------------------------- helpers

    private suspend fun newRoot(title: String): WorkItem {
        val id = UUID.randomUUID()
        return checkNotNull(provider.workItemRepository().create(WorkItem(id = id, rootId = id, title = title, depth = 0)))
    }

    private suspend fun newItem(
        parent: WorkItem,
        title: String
    ): WorkItem =
        checkNotNull(
            provider.workItemRepository().create(
                WorkItem(id = UUID.randomUUID(), rootId = parent.rootId, parentId = parent.id, title = title, depth = parent.depth + 1)
            )
        )

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
        body: String = "# $slug",
        root: UUID = rootId
    ): Document = docs.write(root, DocumentKind.PLAN, slug, body).value()

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

    private fun itemRef(i: WorkItem) = EntityRef(RefKind.ITEM, i.id)

    private suspend fun from(ref: EntityRef): List<Link> = provider.linkStore().findFrom(ref)

    private suspend fun inbound(ref: EntityRef): List<Link> = provider.linkStore().findTo(ref)

    private suspend fun insertLink(
        from: EntityRef,
        to: EntityRef?,
        toRef: String?,
        relation: LinkRelation,
        anchor: String? = null,
        root: UUID = rootId
    ): Link = provider.linkStore().insert(Link(UUID.randomUUID(), root, from, to, toRef, relation, anchor, Instant.now()))

    private fun hex(id: UUID) = id.toString().replace("-", "")

    /** One raw `links` row, every column but the surrogate id, as text (blobs as lowercase hex). */
    private data class Row(
        val root: String?,
        val fromKind: String?,
        val fromId: String?,
        val toKind: String?,
        val toId: String?,
        val toRef: String?,
        val relation: String?,
        val anchor: String?,
        val createdAt: String?
    )

    private fun snapshot(): List<Row> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT lower(hex(root_item_id)), from_kind, lower(hex(from_id)), to_kind, lower(hex(to_id)), to_ref, " +
                            "relation, anchor, CAST(created_at AS TEXT) FROM links"
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Row(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(3),
                                        rs.getString(4),
                                        rs.getString(5),
                                        rs.getString(6),
                                        rs.getString(7),
                                        rs.getString(8),
                                        rs.getString(9)
                                    )
                                )
                            }
                        }
                    }
            }
        }

    private fun exec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun scalar(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun eventTypes(): List<String> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT type FROM events ORDER BY seq").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    private fun Row.sourcedFrom(ref: EntityRef) = fromKind == ref.kind.toDbValue() && fromId == hex(ref.id)

    private fun Row.targets(ref: EntityRef) = toKind == ref.kind.toDbValue() && toId == hex(ref.id)

    private fun sorted(rows: List<Row>) = rows.sortedBy { it.toString() }

    /**
     * Asserts the table now equals [before] minus exactly the rows [removed] selects. The control (some row was selected) makes
     * the removal non-vacuous: without it a fixture that built no matching row would pass.
     */
    private fun assertOnlyRemoved(
        before: List<Row>,
        label: String,
        removed: (Row) -> Boolean
    ) {
        assertTrue(before.any(removed), "$label: control - the fixture holds at least one row that must go")
        assertEquals(sorted(before.filterNot(removed)), sorted(snapshot()), "$label: every other link row is untouched, column for column")
    }

    private suspend fun rootItem() = provider.workItemRepository().getById(rootId)!!

    /** Links that must survive any delete in this suite: a keeper item with a note and an item-sourced link, a doc->doc link, another root's note link. */
    private suspend fun bystanders(): WorkItem {
        val keeper = newItem(rootItem(), "Keeper")
        val keeperNote = upsertNote(keeper.id, "keep", "keeps [[plan-a]]")
        insertLink(noteRef(keeperNote), docRef(docA), "plan:plan-a", LinkRelation.DERIVED_FROM, anchor = "overview")
        insertLink(itemRef(keeper), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES)
        doc("plan-b", body = "doc to doc [[plan-a]]")
        val other = doc("plan-r2", root = otherRootId)
        val otherChild = newItem(provider.workItemRepository().getById(otherRootId)!!, "Other child")
        val otherNote = upsertNote(otherChild.id, "other", "other root [[plan-r2]]")
        assertEquals(docRef(other), from(noteRef(otherNote)).single().to, "fixture: other root link resolves")
        return keeper
    }

    // ---------------------------------------------------------------- S1 leaf delete (D1)

    @Test
    fun `S1 deleting a leaf removes every link sourced from its note and leaves all other links untouched`(): Unit =
        runBlocking {
            bystanders()
            val sibling = newItem(rootItem(), "Sibling")
            val leaf = newItem(rootItem(), "Leaf")
            val n = upsertNote(leaf.id, "k", "see [[plan-a]] and [[item:${sibling.id}]]")
            insertLink(noteRef(n), docRef(docA), "plan:plan-a", LinkRelation.DERIVED_FROM, anchor = "overview")
            assertEquals(3, from(noteRef(n)).size, "control: two REFERENCES and one DERIVED_FROM before the delete")
            assertEquals(listOf(noteRef(n)), inbound(itemRef(sibling)).map { it.from }, "control: the sibling has a backlink from the note")
            val before = snapshot()

            items.delete(leaf.id, recursive = false).value()

            assertEquals(emptyList(), from(noteRef(n)))
            assertEquals(emptyList(), inbound(itemRef(sibling)), "the sibling's backlink from the deleted note is gone")
            assertOnlyRemoved(before, "S1") { it.sourcedFrom(noteRef(n)) }
        }

    // ---------------------------------------------------------------- S2 recursive delete (D1, AC2)

    @Test
    fun `S2 a recursive delete removes the links sourced from every note and item at every level and the inbound ones to each`(): Unit =
        runBlocking {
            bystanders()
            val peer = newItem(rootItem(), "Peer")
            val p = newItem(rootItem(), "P")
            val c = newItem(p, "C")
            val g = newItem(c, "G")
            val levels = listOf(p, c, g)
            val noteIds = levels.map { upsertNote(it.id, "k", "x [[plan-a]] [[item:${peer.id}]]") }
            levels.forEach { insertLink(itemRef(it), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES) }
            val observer = upsertNote(peer.id, "obs", "[[item:${p.id}]] [[item:${c.id}]] [[item:${g.id}]] [[plan-a]]")
            levels.forEach { assertTrue(inbound(itemRef(it)).isNotEmpty(), "control: ${it.title} has an inbound link before the delete") }
            val before = snapshot()

            val result = items.delete(p.id, recursive = true).value()

            assertEquals(2, result.descendantsDeleted)
            noteIds.forEach { assertEquals(emptyList(), from(noteRef(it)), "note $it") }
            levels.forEach {
                assertEquals(emptyList(), from(itemRef(it)), "item ${it.title} as a source")
                assertEquals(emptyList(), inbound(itemRef(it)), "item ${it.title} as a target")
            }
            assertEquals(1, from(noteRef(observer)).size, "the observer note keeps only its link to the surviving document")
            val removedSources = noteIds.map { noteRef(it) } + levels.map { itemRef(it) }
            assertOnlyRemoved(before, "S2") { row -> removedSources.any { row.sourcedFrom(it) } || levels.any { row.targets(itemRef(it)) } }
        }

    // ---------------------------------------------------------------- S3 item-sourced link (D1)

    @Test
    fun `S3 an item-sourced link goes with its item while a surviving item's link stays`(): Unit =
        runBlocking {
            bystanders()
            val leaf = newItem(rootItem(), "Leaf")
            val surviving = newItem(rootItem(), "Survivor")
            insertLink(itemRef(leaf), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES)
            insertLink(itemRef(leaf), docRef(docA), "plan:plan-a", LinkRelation.DERIVED_FROM, anchor = "a")
            val keptLink = insertLink(itemRef(surviving), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES)
            val before = snapshot()

            items.delete(leaf.id, recursive = false).value()

            assertEquals(emptyList(), from(itemRef(leaf)))
            assertEquals(listOf(keptLink.id), from(itemRef(surviving)).map { it.id })
            assertOnlyRemoved(before, "S3") { it.sourcedFrom(itemRef(leaf)) }
        }

    // ---------------------------------------------------------------- S4 inbound (D2)

    @Test
    fun `S4 resolved REFERENCES and ATTACHED links into a deleted item are removed and the observer's other links stay`(): Unit =
        runBlocking {
            bystanders()
            val victim = newItem(rootItem(), "Victim")
            val observerItem = newItem(rootItem(), "Observer")
            val shortHex = hex(victim.id).take(8)
            val full = victim.id.toString()
            val observer = upsertNote(observerItem.id, "k", "[[item:$full]] [[plan-a]] [[item:${full.uppercase()}]]")
            val observer2 = upsertNote(observerItem.id, "k2", "[[item:$shortHex]] [[item:${hex(victim.id)}]]")
            links.attach(rootId, DocumentKind.PLAN, "plan-a", victim.id).value()
            links.attach(rootId, DocumentKind.PLAN, "plan-a", victim.id).value()
            assertTrue(inbound(itemRef(victim)).any { it.relation == LinkRelation.ATTACHED }, "control: an ATTACHED link into the victim")
            assertTrue(
                inbound(itemRef(victim)).any { it.relation == LinkRelation.REFERENCES },
                "control: a resolved REFERENCES into the victim"
            )
            val docLinkBefore = from(noteRef(observer)).single { it.to == docRef(docA) }
            val before = snapshot()

            items.delete(victim.id, recursive = false).value()

            assertEquals(emptyList(), inbound(itemRef(victim)), "nothing resolves to the deleted item any more")
            assertEquals(docLinkBefore, from(noteRef(observer)).single { it.to == docRef(docA) }, "the observer's other link is untouched")
            assertTrue(from(noteRef(observer2)).all { it.to != itemRef(victim) })
            assertOnlyRemoved(before, "S4") { it.targets(itemRef(victim)) }
        }

    // ---------------------------------------------------------------- S5 ADOPTED_BY is kept (D2, Q4 D16)

    @Test
    fun `S5 an adoption of a deleted item survives still naming it and the adopted document still refuses delete`(): Unit =
        runBlocking {
            bystanders()
            val adopted = doc("plan-adopted")
            val adopter = newItem(rootItem(), "Adopter")
            docs.adopt(rootId, "plan-adopted", adopter.id).value()
            val before = snapshot()
            val adoptionBefore = assertNotNull(docs.adoptionOf(adopted), "control: the adoption exists")

            items.delete(adopter.id, recursive = false).value()

            val after = assertNotNull(docs.adoptionOf(adopted), "the ADOPTED_BY link outlives the adopting item")
            assertEquals(adoptionBefore, after, "the adoption row is unchanged")
            assertEquals(LinkRelation.ADOPTED_BY, after.relation)
            assertEquals(itemRef(adopter), after.to, "still resolved and naming the deleted adopter")
            assertEquals(ErrorCode.INVALID_REQUEST, docs.delete(rootId, DocumentKind.PLAN, "plan-adopted").error().code)
            assertEquals(sorted(before), sorted(snapshot()), "no link row changed at all")
        }

    @Test
    fun `S5b an adoption by a deleted descendant is kept while a reference to that descendant is removed`(): Unit =
        runBlocking {
            bystanders()
            val peer = newItem(rootItem(), "Peer")
            val p = newItem(rootItem(), "P")
            val c = newItem(p, "C")
            val g = newItem(c, "G")
            val adopted = doc("plan-adopted")
            docs.adopt(rootId, "plan-adopted", g.id).value()
            val observer = upsertNote(peer.id, "k", "[[item:${g.id}]] [[item:${c.id}]]")
            assertEquals(2, from(noteRef(observer)).size, "control: two resolved references")
            val before = snapshot()

            items.delete(p.id, recursive = true).value()

            assertEquals(itemRef(g), assertNotNull(docs.adoptionOf(adopted)).to)
            assertEquals(emptyList(), from(noteRef(observer)), "references to the deleted descendants are gone")
            assertOnlyRemoved(before, "S5b") { it.relation == "references" && (it.targets(itemRef(g)) || it.targets(itemRef(c))) }
        }

    // ---------------------------------------------------------------- S6 mixed inbound relations (D2)

    @Test
    fun `S6 a document attached to and adopted by the same deleted item loses only the ATTACHED link`(): Unit =
        runBlocking {
            bystanders()
            val victim = newItem(rootItem(), "Victim")
            val d = doc("plan-both")
            links.attach(rootId, DocumentKind.PLAN, "plan-both", victim.id).value()
            docs.adopt(rootId, "plan-both", victim.id).value()
            assertEquals(
                setOf(LinkRelation.ATTACHED, LinkRelation.ADOPTED_BY),
                from(docRef(d)).map { it.relation }.toSet(),
                "control: both relations before the delete"
            )
            val before = snapshot()

            items.delete(victim.id, recursive = false).value()

            assertEquals(setOf(LinkRelation.ADOPTED_BY), from(docRef(d)).map { it.relation }.toSet())
            assertEquals(itemRef(victim), assertNotNull(docs.adoptionOf(d)).to)
            assertOnlyRemoved(before, "S6") { it.relation == "attached" && it.targets(itemRef(victim)) }
        }

    // ---------------------------------------------------------------- S7 cross-root (D3)

    @Test
    fun `S7 links recorded under a former root are cleaned when the item is deleted from its new root`(): Unit =
        runBlocking {
            bystanders()
            val otherRoot = provider.workItemRepository().getById(otherRootId)!!
            val x = newItem(otherRoot, "X")
            val n = upsertNote(x.id, "k", "from the old root [[plan-r2]]")
            val oldRootLink = from(noteRef(n)).single()
            assertEquals(otherRootId, oldRootLink.rootItemId, "control: the link is recorded under the old root")

            items.patch(patchOf(x, parent = ParentChange.MoveUnder(rootId))).value()
            assertEquals(rootId, provider.workItemRepository().getById(x.id)!!.rootId, "control: X now lives under the new root")
            links.attach(rootId, DocumentKind.PLAN, "plan-a", x.id).value()
            insertLink(itemRef(x), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES, root = otherRootId)
            val before = snapshot()

            items.delete(x.id, recursive = false).value()

            assertEquals(emptyList(), from(noteRef(n)), "the old-root link sourced from the deleted note is gone")
            assertEquals(emptyList(), from(itemRef(x)))
            assertEquals(emptyList(), inbound(itemRef(x)), "the ATTACHED link recorded under the new root is gone")
            assertOnlyRemoved(before, "S7") { it.sourcedFrom(noteRef(n)) || it.sourcedFrom(itemRef(x)) || it.targets(itemRef(x)) }
        }

    @Test
    fun `S7b deleting the new root recursively also cleans links its moved descendant recorded under the old root`(): Unit =
        runBlocking {
            bystanders()
            val otherRoot = provider.workItemRepository().getById(otherRootId)!!
            val x = newItem(otherRoot, "X")
            val n = upsertNote(x.id, "k", "from the old root [[plan-r2]]")
            assertEquals(otherRootId, from(noteRef(n)).single().rootItemId, "control: recorded under the old root")
            items.patch(patchOf(x, parent = ParentChange.MoveUnder(rootId))).value()
            val before = snapshot()

            items.delete(rootId, recursive = true).value()

            assertTrue(before.any { it.sourcedFrom(noteRef(n)) }, "control: the row existed")
            assertEquals(emptyList(), from(noteRef(n)), "a root_item_id cascade cannot reach this row; entity matching must")
            assertTrue(
                snapshot().any { it.root == hex(otherRootId) && it.fromKind == "note" && it.relation == "references" },
                "the other root's own links stay"
            )
        }

    // ---------------------------------------------------------------- S8 dangling (D1)

    @Test
    fun `S8 a dangling link sourced from a deleted note goes while one from a surviving note stays`(): Unit =
        runBlocking {
            bystanders()
            val leaf = newItem(rootItem(), "Leaf")
            val survivor = newItem(rootItem(), "Survivor")
            val n = upsertNote(leaf.id, "k", "[[nowhere-yet]]")
            val keepNote = upsertNote(survivor.id, "k", "[[nowhere-yet]]")
            assertEquals(null, from(noteRef(n)).single().to, "control: dangling before the delete")
            val keptBefore = from(noteRef(keepNote)).single()
            val before = snapshot()

            items.delete(leaf.id, recursive = false).value()

            assertEquals(emptyList(), from(noteRef(n)))
            assertEquals(keptBefore, from(noteRef(keepNote)).single(), "the surviving note's dangling link is untouched")
            assertOnlyRemoved(before, "S8") { it.sourcedFrom(noteRef(n)) }
        }

    // ---------------------------------------------------------------- S9-S11 failure (D4)

    @Test
    fun `S9 a refused non-recursive delete of a parent with a child leaves every link row untouched`(): Unit =
        runBlocking {
            bystanders()
            val p = newItem(rootItem(), "P")
            val child = newItem(p, "Child")
            val peer = newItem(rootItem(), "Peer")
            upsertNote(p.id, "k", "[[plan-a]]")
            upsertNote(child.id, "k", "[[plan-a]]")
            upsertNote(peer.id, "k", "[[item:${p.id}]] [[item:${child.id}]]")
            val before = snapshot()
            assertTrue(before.any { it.targets(itemRef(p)) }, "control: an inbound link to the refused parent exists")

            val err = items.delete(p.id, recursive = false).error()

            assertEquals(ErrorCode.INVALID_REQUEST, err.code)
            assertEquals(sorted(before), sorted(snapshot()))
            assertEquals(2, scalar("SELECT count(*) FROM work_items WHERE title IN ('P','Child')"), "control: nothing was deleted")
        }

    @Test
    fun `S10 a store fault at the item delete rolls the link cleanup back`(): Unit =
        runBlocking {
            bystanders()
            val doomed = newItem(rootItem(), "doomed")
            val peer = newItem(rootItem(), "Peer")
            val n = upsertNote(doomed.id, "k", "[[plan-a]]")
            insertLink(itemRef(doomed), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES)
            upsertNote(peer.id, "k", "[[item:${doomed.id}]]")
            val before = snapshot()
            assertTrue(before.any { it.sourcedFrom(noteRef(n)) } && before.any { it.targets(itemRef(doomed)) }, "control: links both ways")
            exec("CREATE TRIGGER s10_fault BEFORE DELETE ON work_items WHEN OLD.title = 'doomed' BEGIN SELECT RAISE(ABORT, 'inj'); END")

            assertDeleteFails { items.delete(doomed.id, recursive = false) }

            assertEquals(sorted(before), sorted(snapshot()), "the failed unit rolled every link change back")
            assertEquals(1, scalar("SELECT count(*) FROM work_items WHERE title = 'doomed'"), "control: the item is still there")

            exec("DROP TRIGGER s10_fault")
            items.delete(doomed.id, recursive = false).value()
            assertEquals(emptyList(), from(noteRef(n)), "control: without the fault the same delete cleans the links")
        }

    @Test
    fun `S10b a fault on the root of a recursive delete rolls back the cleanup already done for its descendants`(): Unit =
        runBlocking {
            bystanders()
            val doomed = newItem(rootItem(), "doomed")
            val c = newItem(doomed, "C")
            val g = newItem(c, "G")
            val noteC = upsertNote(c.id, "k", "[[plan-a]]")
            val noteG = upsertNote(g.id, "k", "[[plan-a]]")
            val peer = newItem(rootItem(), "Peer")
            upsertNote(peer.id, "k", "[[item:${g.id}]]")
            val before = snapshot()
            assertTrue(before.any { it.sourcedFrom(noteRef(noteC)) } && before.any { it.sourcedFrom(noteRef(noteG)) }, "control")
            exec("CREATE TRIGGER s10b_fault BEFORE DELETE ON work_items WHEN OLD.title = 'doomed' BEGIN SELECT RAISE(ABORT, 'inj'); END")

            assertDeleteFails { items.delete(doomed.id, recursive = true) }

            assertEquals(sorted(before), sorted(snapshot()), "no descendant-level cleanup survives the failed unit")
            assertEquals(3, scalar("SELECT count(*) FROM work_items WHERE title IN ('doomed','C','G')"), "control: nothing was deleted")
        }

    private suspend fun assertDeleteFails(block: suspend () -> Outcome<*>) {
        val outcome = runCatching { block() }
        // A rejection may surface as an Err outcome or as the propagated store exception; both are a failed delete. What is
        // never accepted is a successful delete.
        val failed = outcome.isFailure || outcome.getOrNull() is Outcome.Err
        assertTrue(failed, "the injected store fault must fail the delete, got: $outcome")
    }

    @Test
    fun `S11 deleting an unknown id is not found and replaying a delete is not found, both leaving links unchanged`(): Unit =
        runBlocking {
            bystanders()
            val leaf = newItem(rootItem(), "Leaf")
            upsertNote(leaf.id, "k", "[[plan-a]]")
            val beforeAll = snapshot()

            assertEquals(ErrorCode.NOT_FOUND, items.delete(UUID.randomUUID(), recursive = true).error().code)
            assertEquals(sorted(beforeAll), sorted(snapshot()), "an unknown id changes no link")

            items.delete(leaf.id, recursive = false).value()
            val afterFirst = snapshot()
            assertTrue(afterFirst.size < beforeAll.size, "control: the first delete removed the leaf's link")

            assertEquals(ErrorCode.NOT_FOUND, items.delete(leaf.id, recursive = false).error().code)
            assertEquals(sorted(afterFirst), sorted(snapshot()), "the replay changes no link")
        }

    // ---------------------------------------------------------------- S12 no wire or event change (D5)

    @Test
    fun `S12 the delete result is unchanged and a delete of a linked item emits the same event types as one with no links`(): Unit =
        runBlocking {
            bystanders()
            val linked = newItem(rootItem(), "Linked")
            val plain = newItem(rootItem(), "Plain")
            val peer = newItem(rootItem(), "Peer")
            upsertNote(linked.id, "k", "[[plan-a]]")
            upsertNote(plain.id, "k", "no brackets")
            upsertNote(peer.id, "k", "[[item:${linked.id}]]")

            val m0 = eventTypes().size
            val rLinked = items.delete(linked.id, recursive = false).value()
            val linkedEvents = eventTypes().drop(m0)
            val m1 = eventTypes().size
            val rPlain = items.delete(plain.id, recursive = false).value()
            val plainEvents = eventTypes().drop(m1)

            assertTrue(linkedEvents.isNotEmpty(), "control: a delete emits events")
            assertEquals(plainEvents, linkedEvents, "D5: link cleanup adds no event")
            assertTrue(linkedEvents.none { it.contains("link") }, "no event type mentions a link: $linkedEvents")
            assertEquals(linked.id, rLinked.id)
            assertEquals(0, rLinked.descendantsDeleted)
            assertEquals(0, rPlain.descendantsDeleted)
            assertEquals(emptyList(), rLinked.cascadeEvents)
        }

    // ---------------------------------------------------------------- probes

    @Test
    fun `P1 deleting a leaf with no notes and no links changes no link row`(): Unit =
        runBlocking {
            bystanders()
            val bare = newItem(rootItem(), "Bare")
            val before = snapshot()
            assertTrue(before.isNotEmpty(), "control: other links exist that could be touched by mistake")

            items.delete(bare.id, recursive = false).value()

            assertEquals(sorted(before), sorted(snapshot()))
        }

    @Test
    fun `P2 a note carrying only DERIVED_FROM and no brackets loses it with the item`(): Unit =
        runBlocking {
            bystanders()
            val leaf = newItem(rootItem(), "Leaf")
            val n = upsertNote(leaf.id, "k", "no double brackets here")
            insertLink(noteRef(n), docRef(docA), null, LinkRelation.DERIVED_FROM, anchor = "overview")
            val before = snapshot()

            items.delete(leaf.id, recursive = false).value()

            assertEquals(emptyList(), from(noteRef(n)))
            assertOnlyRemoved(before, "P2") { it.sourcedFrom(noteRef(n)) }
        }

    @Test
    fun `P3 a level of more than one thousand children is cleaned in full, outbound and inbound`(): Unit =
        runBlocking {
            bystanders()
            val p = newItem(rootItem(), "Wide")
            val observerHost = newItem(rootItem(), "Observer host")
            val observer = upsertNote(observerHost.id, "obs", "observer without brackets")
            val count = 1100
            val children =
                (0 until count).map { i ->
                    val child = newItem(p, "w$i")
                    val note = upsertNote(child.id, "k", "plain")
                    insertLink(itemRef(child), docRef(docA), "plan:plan-a", LinkRelation.REFERENCES)
                    insertLink(noteRef(note), docRef(docA), null, LinkRelation.DERIVED_FROM, anchor = "wide")
                    insertLink(noteRef(observer), itemRef(child), child.id.toString(), LinkRelation.REFERENCES)
                    child
                }
            val childIds = children.map { hex(it.id) }.toSet()
            val before = snapshot()
            assertEquals(count, before.count { it.fromKind == "item" && it.fromId in childIds }, "control: one item-sourced link per child")
            assertEquals(count, before.count { it.toKind == "item" && it.toId in childIds }, "control: one inbound link per child")
            assertEquals(
                count,
                before.count { it.relation == "derived_from" && it.anchor == "wide" },
                "control: one note-sourced link per child"
            )

            val result = items.delete(p.id, recursive = true).value()

            assertEquals(count, result.descendantsDeleted)
            val after = snapshot()
            assertTrue(after.none { it.fromKind == "item" && it.fromId in childIds }, "no item-sourced link of any child remains")
            assertTrue(after.none { it.toKind == "item" && it.toId in childIds }, "no inbound link to any child remains")
            assertEquals(
                0,
                after.count { it.relation == "derived_from" && it.anchor == "wide" },
                "no note-sourced link of any child remains"
            )
            val removed = { r: Row ->
                (r.fromKind == "item" && r.fromId in childIds) ||
                    (r.toKind == "item" && r.toId in childIds) ||
                    (r.relation == "derived_from" && r.anchor == "wide")
            }
            assertEquals(sorted(before.filterNot(removed)), sorted(after), "everything else is untouched")
        }
}
