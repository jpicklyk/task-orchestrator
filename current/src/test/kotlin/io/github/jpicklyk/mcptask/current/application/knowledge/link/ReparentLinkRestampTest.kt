package io.github.jpicklyk.mcptask.current.application.knowledge.link

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.service.ItemCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteCommandService
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.application.service.ParentChange
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
 * Independently authored behavioral tests of the link root after a reparent (item 774ac07e, seat test-author, finding N3).
 * No implementation source, diff or commit was opened. Oracles are the frozen queue-phase `task-scope` N3 decision and
 * `test-plan` S5-S7 (the `LinkService` KDoc "the target resolves within the source's root" and the V24 `links.root_item_id`
 * root cascade): after any reparent, every link sourced from a note of the moved item or of any of its descendants, and
 * the moved item's own item-sourced links, has `root_item_id` equal to the moved item's new root; links sourced from
 * documents (attached, adopted_by, doc-to-doc references) carry the document's root and are never restamped; a link
 * sourced from an item or note left behind keeps its root; a same-root move changes nothing; the restamp joins the move's
 * unit and records no event; and, because the cascade is kept, a link restamped to a new root survives the deletion of
 * its old root while a document-sourced link dies with the document's root.
 *
 * Links are observed through `LinkStore.findFrom` and through a full raw snapshot of the `links` table, so "every other
 * link is untouched" is a row-for-row comparison (all columns, including `created_at`), not a count.
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId` = its parent, `rootId` = the parent's root and `depth` = parent depth + 1; notes go through the production
 * `NoteCommandService`, documents through `DocumentService`, the attachment through `LinkService.attach`, the derived
 * link through `LinkService.recordDerivedFrom` and the item-sourced link through `LinkStore.insert` with the source's
 * current root.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class ReparentLinkRestampTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var provider: RepositoryProvider
    private lateinit var docs: DocumentService
    private lateinit var links: LinkService
    private lateinit var notes: NoteCommandService
    private lateinit var items: ItemCommandService

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            provider = db.repositoryProvider()
            docs = DocumentService(provider, db.unitOfWork())
            links = LinkService(provider, db.unitOfWork())
            val ctx = ToolExecutionContext(provider, unitOfWork = db.unitOfWork())
            notes = ctx.noteCommandService
            items = ctx.itemCommandService
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

    private suspend fun fresh(item: WorkItem): WorkItem = checkNotNull(provider.workItemRepository().getById(item.id))

    private suspend fun move(
        item: WorkItem,
        parent: ParentChange
    ) {
        items.patch(patchOf(fresh(item), parent = parent)).value()
    }

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

    private fun query(sql: String): List<Row> =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
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

    private fun snapshot(): List<Row> =
        query(
            "SELECT lower(hex(root_item_id)), from_kind, lower(hex(from_id)), to_kind, lower(hex(to_id)), to_ref, " +
                "relation, anchor, CAST(created_at AS TEXT) FROM links"
        )

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

    private fun sorted(rows: List<Row>) = rows.sortedBy { it.toString() }

    private class World(
        val a: WorkItem,
        val b: WorkItem,
        val i: WorkItem,
        val c: WorkItem,
        val l: WorkItem,
        val planA: Document,
        val planB: Document,
        val noteI: UUID,
        val noteC: UUID,
        val noteL: UUID
    )

    /**
     * Root A holds I (with child C) and a bystander leaf L; root B is empty. Plan `plan-a` lives in A and is referenced by a
     * note on each of I, C and L; I's note also has a derived_from link and I has an item-sourced link; `plan-a` is attached
     * to I and to L; a second plan `plan-b` in A references `plan-a` (a document-sourced link). Every link is recorded
     * under root A.
     */
    private suspend fun world(): World {
        val a = newRoot("A")
        val b = newRoot("B")
        val i = newItem(a, "I")
        val c = newItem(i, "C")
        val l = newItem(a, "L")
        val planA = docs.write(a.id, DocumentKind.PLAN, "plan-a", "# plan-a").value()
        val planB = docs.write(a.id, DocumentKind.PLAN, "plan-b", "doc to doc [[plan:plan-a]]").value()
        val noteI = upsertNote(i.id, "k", "i references [[plan:plan-a]]")
        val noteC = upsertNote(c.id, "k", "c references [[plan:plan-a]]")
        val noteL = upsertNote(l.id, "k", "l references [[plan:plan-a]]")
        links.recordDerivedFrom(a.id, noteI, "plan-a", "overview", Instant.now())
        provider.linkStore().insert(
            Link(UUID.randomUUID(), a.id, itemRef(i), docRef(planA), "plan:plan-a", LinkRelation.REFERENCES, null, Instant.now())
        )
        links.attach(a.id, DocumentKind.PLAN, "plan-a", i.id).value()
        links.attach(a.id, DocumentKind.PLAN, "plan-a", l.id).value()
        val w = World(a, b, i, c, l, planA, planB, noteI, noteC, noteL)
        val all = snapshot()
        assertTrue(all.isNotEmpty() && all.all { it.root == hex(a.id) }, "fixture: every link starts under root A: $all")
        assertEquals(2, from(noteRef(noteI)).size, "fixture: I's note has a reference and a derived_from link")
        assertEquals(2, from(docRef(planA)).count { it.relation == LinkRelation.ATTACHED }, "fixture: two attachments")
        assertEquals(1, from(docRef(planB)).size, "fixture: the doc-to-doc link")
        return w
    }

    /** The rows that follow the moved subtree: links sourced from the notes of [subtree] and from the item [moved] itself. */
    private fun followsMove(
        movedNotes: List<UUID>,
        movedItems: List<WorkItem>
    ): (Row) -> Boolean = { row -> movedNotes.any { row.sourcedFrom(noteRef(it)) } || movedItems.any { row.sourcedFrom(itemRef(it)) } }

    private fun expectedAfter(
        before: List<Row>,
        newRoot: UUID,
        follows: (Row) -> Boolean
    ): List<Row> {
        assertTrue(before.any(follows), "control: the fixture holds at least one row that must follow the move")
        return before.map { if (follows(it)) it.copy(root = hex(newRoot)) else it }
    }

    // ---------------------------------------------------------------- S5 cross-root move

    @Test
    fun `S5 moving an item to another root restamps the links of its subtree's notes and its own item links only`(): Unit =
        runBlocking {
            val w = world()
            val before = snapshot()
            val eventsBefore = eventTypes().size

            move(w.i, ParentChange.MoveUnder(w.b.id))

            assertEquals(w.b.id, fresh(w.i).rootId, "control: I now lives under B")
            assertEquals(w.b.id, fresh(w.c).rootId, "control: its descendant moved with it")
            from(noteRef(w.noteI)).forEach { assertEquals(w.b.id, it.rootItemId, "I's note link ${it.relation}") }
            from(noteRef(w.noteC)).forEach { assertEquals(w.b.id, it.rootItemId, "C's note link ${it.relation}") }
            from(itemRef(w.i)).forEach { assertEquals(w.b.id, it.rootItemId, "I's item-sourced link") }
            from(noteRef(w.noteL)).forEach { assertEquals(w.a.id, it.rootItemId, "a note left in A keeps A") }
            from(docRef(w.planA))
                .filter { it.relation == LinkRelation.ATTACHED }
                .forEach { assertEquals(w.a.id, it.rootItemId, "an attachment is sourced from the document and keeps A") }
            from(docRef(w.planB)).forEach { assertEquals(w.a.id, it.rootItemId, "a doc-to-doc link keeps A") }

            val follows = followsMove(listOf(w.noteI, w.noteC), listOf(w.i))
            assertEquals(
                sorted(expectedAfter(before, w.b.id, follows)),
                sorted(snapshot()),
                "exactly the moved subtree's rows changed root, column for column"
            )
            assertTrue(
                eventTypes().drop(eventsBefore).none { it.contains("link") },
                "the restamp records no event: ${eventTypes().drop(eventsBefore)}"
            )
        }

    // ---------------------------------------------------------------- S6 delete the old root afterwards

    @Test
    fun `S6 after the move, deleting the old root keeps the moved notes' links dangling with their text`(): Unit =
        runBlocking {
            val w = world()
            move(w.i, ParentChange.MoveUnder(w.b.id))
            assertNotNull(provider.documentStore().get(w.a.id, DocumentKind.PLAN, "plan-a"), "control: the plan exists before the delete")

            items.delete(w.a.id, recursive = true).value()

            val iLinks = from(noteRef(w.noteI))
            assertEquals(2, iLinks.size, "both of I's note links survive the deletion of the old root: $iLinks")
            val ref = iLinks.single { it.relation == LinkRelation.REFERENCES }
            assertEquals(null, ref.to, "the reference now dangles")
            assertEquals("plan:plan-a", ref.toRef, "and keeps its text")
            assertEquals(w.b.id, ref.rootItemId)
            val derived = iLinks.single { it.relation == LinkRelation.DERIVED_FROM }
            assertEquals(null, derived.to, "the derived_from link dangles")
            assertEquals("plan:plan-a", derived.toRef)
            assertEquals("overview", derived.anchor)
            val cLinks = from(noteRef(w.noteC))
            assertEquals(1, cLinks.size, "C's note link survives")
            assertEquals(null, cLinks.single().to)
            assertEquals("plan:plan-a", cLinks.single().toRef)
            assertEquals(0, scalar("SELECT count(*) FROM links WHERE relation = 'attached'"), "the attachments went with the document")
            assertEquals(0, scalar("SELECT count(*) FROM links WHERE lower(hex(root_item_id)) = '${hex(w.a.id)}'"), "nothing stays under A")
            assertEquals(null, provider.documentStore().get(w.a.id, DocumentKind.PLAN, "plan-a"), "the plan went with its root")
        }

    // ---------------------------------------------------------------- S7 edges

    @Test
    fun `S7 a same-root move changes no link row`(): Unit =
        runBlocking {
            val w = world()
            val before = snapshot()

            move(w.i, ParentChange.MoveUnder(w.l.id))

            assertEquals(w.l.id, fresh(w.i).parentId, "control: I moved under L")
            assertEquals(w.a.id, fresh(w.i).rootId, "control: still in A")
            assertEquals(sorted(before), sorted(snapshot()), "no link row changed at all")
        }

    @Test
    fun `S7 moving an item to the top level stamps its own id as the root of the moved subtree's links`(): Unit =
        runBlocking {
            val w = world()
            val before = snapshot()

            move(w.i, ParentChange.MoveToRoot)

            assertEquals(w.i.id, fresh(w.i).rootId, "control: I is now its own root")
            assertEquals(w.i.id, fresh(w.c).rootId, "control: C follows")
            val follows = followsMove(listOf(w.noteI, w.noteC), listOf(w.i))
            assertEquals(sorted(expectedAfter(before, w.i.id, follows)), sorted(snapshot()))
        }

    @Test
    fun `S7 moving only a leaf restamps only the links of that leaf's note`(): Unit =
        runBlocking {
            val w = world()
            val before = snapshot()

            move(w.c, ParentChange.MoveUnder(w.b.id))

            assertEquals(w.b.id, fresh(w.c).rootId, "control: C moved")
            assertEquals(w.a.id, fresh(w.i).rootId, "control: I stayed")
            val follows = followsMove(listOf(w.noteC), emptyList())
            assertEquals(sorted(expectedAfter(before, w.b.id, follows)), sorted(snapshot()))
        }

    @Test
    fun `S7 moving away and back restores every link row`(): Unit =
        runBlocking {
            val w = world()
            val before = snapshot()

            move(w.i, ParentChange.MoveUnder(w.b.id))
            assertTrue(sorted(before) != sorted(snapshot()), "control: the first move changed rows")
            move(w.i, ParentChange.MoveUnder(w.a.id))

            assertEquals(w.a.id, fresh(w.i).rootId, "control: I is back in A")
            assertEquals(sorted(before), sorted(snapshot()), "the original roots are restored")
        }

    @Test
    fun `P1 moving an item that owns no notes and no links changes no link row`(): Unit =
        runBlocking {
            val w = world()
            val bare = newItem(w.a, "Bare")
            val before = snapshot()

            move(bare, ParentChange.MoveUnder(w.b.id))

            assertEquals(w.b.id, fresh(bare).rootId, "control: the bare item moved")
            assertEquals(sorted(before), sorted(snapshot()))
        }

    @Test
    fun `P2 a subtree wider than one thousand notes is restamped in full`(): Unit =
        runBlocking {
            val a = newRoot("A")
            val b = newRoot("B")
            val i = newItem(a, "I")
            docs.write(a.id, DocumentKind.PLAN, "plan-a", "# plan-a").value()
            val count = 1100
            repeat(count) { n ->
                val child = newItem(i, "w$n")
                upsertNote(child.id, "k", "wide [[plan:plan-a]]")
            }
            assertEquals(
                count,
                scalar("SELECT count(*) FROM links WHERE lower(hex(root_item_id)) = '${hex(a.id)}'"),
                "control: all under A"
            )

            move(i, ParentChange.MoveUnder(b.id))

            assertEquals(
                count,
                scalar("SELECT count(*) FROM links WHERE lower(hex(root_item_id)) = '${hex(b.id)}'"),
                "every link moved to B"
            )
            assertEquals(0, scalar("SELECT count(*) FROM links WHERE lower(hex(root_item_id)) = '${hex(a.id)}'"), "none stays under A")
        }
}
