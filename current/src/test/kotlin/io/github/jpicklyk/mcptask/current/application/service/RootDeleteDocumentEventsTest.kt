package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.application.port.EventRecord
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.patchOf
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Independently authored behavioral tests of the `document.deleted` rows an item delete records for the documents it
 * removes (item 774ac07e, seat test-author, finding N4). No implementation source, diff or commit was opened. Oracles are the
 * frozen queue-phase `task-scope` N4 decision and `test-plan` S8-S10, which cite the `DocumentService` KDoc ("every write
 * records its document.* event"), the v4-phase1 rule "one event per FK-cascaded row" and the `EventCoverageTest` rule
 * "cascade rows precede item.deleted": deleting an item deletes every document anchored to it (matched by the document's
 * root item, so a former root that was moved under another item keeps and loses its documents), archived and adopted ones
 * included, with one `document.deleted` row per document carrying the document id, its root, kind, slug and version, recorded
 * before that item's `item.deleted`, in the same unit; a refused or failed delete records and removes nothing.
 *
 * Fixture invariants (satisfied by construction): a root is a depth-0 item whose `rootId` is its own id; a child has
 * `parentId` = its parent, `rootId` = the parent's root and `depth` = parent depth + 1; documents are written against an
 * existing item, archived and adopted through the production `DocumentService`; the adopter is a child item of the document's
 * root; the delete is driven through `ItemCommandService.delete` over `db.unitOfWork()`, which records event rows.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class RootDeleteDocumentEventsTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private lateinit var provider: RepositoryProvider
    private lateinit var docs: DocumentService
    private lateinit var items: ItemCommandService

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            provider = db.repositoryProvider()
            docs = DocumentService(provider, db.unitOfWork())
            items = ToolExecutionContext(provider, unitOfWork = db.unitOfWork()).itemCommandService
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

    private fun Outcome<*>.errorCode(): ErrorCode =
        when (this) {
            is Outcome.Ok -> fail("expected Err but was Ok: $value")
            is Outcome.Err -> error.code
        }

    private suspend fun doc(
        root: WorkItem,
        kind: DocumentKind,
        slug: String,
        body: String = "# $slug"
    ): Document = docs.write(root.id, kind, slug, body).value()

    private suspend fun eventsAfter(mark: Long): List<EventRecord> = provider.eventStore().readAfter(mark, null, 100_000)

    private suspend fun mark(): Long = provider.eventStore().maxSeq()

    private fun List<EventRecord>.ofType(type: String) = filter { it.type == type }

    private fun List<EventRecord>.itemDeleted(item: WorkItem): EventRecord =
        ofType("item.deleted").singleOrNull { it.entityId == item.id } ?: fail("no item.deleted row for ${item.title}: ${map { it.type }}")

    private fun scalar(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private fun exec(sql: String) {
        DriverManager.getConnection(db.jdbcUrl).use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private fun payload(record: EventRecord): JsonObject = Json.parseToJsonElement(record.data).jsonObject

    /** Every primitive value of the row's data object, as text. */
    private fun payloadValues(record: EventRecord): List<String> =
        payload(record).values.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private suspend fun assertDocumentRowBefore(
        rows: List<EventRecord>,
        document: Document,
        anchor: WorkItem,
        label: String
    ): EventRecord {
        val row =
            rows.ofType("document.deleted").singleOrNull { it.entityId == document.id }
                ?: fail("$label: no document.deleted row for ${document.slug}")
        assertEquals(anchor.id, row.rootId, "$label: the row names the document's root")
        assertTrue(row.seq < rows.itemDeleted(anchor).seq, "$label: ${document.slug} is recorded before its item's item.deleted")
        return row
    }

    // ---------------------------------------------------------------- S8 root delete

    @Test
    fun `S8 a recursive root delete records one document deleted row per document before item deleted and removes the documents`(): Unit =
        runBlocking {
            val r = newRoot("R")
            val c = newItem(r, "C")
            doc(r, DocumentKind.PLAN, "plan-live", "version one")
            val live = doc(r, DocumentKind.PLAN, "plan-live", "version two")
            assertEquals(2, live.version, "fixture: the live plan is at version 2")
            val archivedPage = doc(r, DocumentKind.PAGE, "page-arch")
            docs.archive(r.id, DocumentKind.PAGE, "page-arch").value()
            val adoptedPlan = doc(r, DocumentKind.PLAN, "plan-adopted")
            docs.adopt(r.id, "plan-adopted", c.id).value()
            val rule = doc(r, DocumentKind.RULE, "rule-r")
            assertEquals(4, scalar("SELECT count(*) FROM documents"), "control: four documents")
            assertTrue(scalar("SELECT count(*) FROM links") >= 1, "control: the adoption link exists")
            val mark = mark()

            val result = items.delete(r.id, recursive = true, caller = null).value()

            assertEquals(1, result.descendantsDeleted)
            val rows = eventsAfter(mark)
            val documentRows = rows.ofType("document.deleted")
            assertEquals(4, documentRows.size, "one row per document, archived and adopted included: ${rows.map { it.type }}")
            assertEquals(setOf(live.id, archivedPage.id, adoptedPlan.id, rule.id), documentRows.map { it.entityId }.toSet())
            val liveRow = assertDocumentRowBefore(rows, live, r, "live plan")
            assertDocumentRowBefore(rows, archivedPage, r, "archived page")
            assertDocumentRowBefore(rows, adoptedPlan, r, "adopted plan")
            assertDocumentRowBefore(rows, rule, r, "rule")
            assertTrue("plan-live" in payloadValues(liveRow), "the row carries the slug: ${liveRow.data}")
            assertTrue("2" in payloadValues(liveRow), "the row carries the version the document had: ${liveRow.data}")
            assertEquals(0, scalar("SELECT count(*) FROM documents"), "the documents are gone")
            assertEquals(0, scalar("SELECT count(*) FROM document_versions"), "and their versions")
            assertEquals(0, scalar("SELECT count(*) FROM links"), "and the adoption link went with the adopted plan")
        }

    @Test
    fun `S8 the document rows precede every item deleted row of the root and are not duplicated by the delete of a descendant`(): Unit =
        runBlocking {
            val r = newRoot("R")
            val c = newItem(r, "C")
            val g = newItem(c, "G")
            doc(r, DocumentKind.PLAN, "only-plan")
            val mark = mark()

            items.delete(r.id, recursive = true, caller = null).value()

            val rows = eventsAfter(mark)
            assertEquals(1, rows.ofType("document.deleted").size, "exactly one row for the one document: ${rows.map { it.type }}")
            val documentSeq = rows.ofType("document.deleted").single().seq
            assertTrue(documentSeq < rows.itemDeleted(r).seq, "before the root's item.deleted")
            assertEquals(3, rows.ofType("item.deleted").size, "control: R, C and G each record item.deleted")
            assertNotNull(rows.itemDeleted(g))
        }

    // ---------------------------------------------------------------- S9 refused delete

    @Test
    fun `S9 a refused non-recursive delete of a root with children records no document row and removes nothing`(): Unit =
        runBlocking {
            val r = newRoot("R")
            newItem(r, "C")
            doc(r, DocumentKind.PLAN, "plan-live", "version one")
            doc(r, DocumentKind.PLAN, "plan-live", "version two")
            doc(r, DocumentKind.PAGE, "page-arch")
            val mark = mark()
            val documentsBefore = scalar("SELECT count(*) FROM documents")
            val versionsBefore = scalar("SELECT count(*) FROM document_versions")

            val code = items.delete(r.id, recursive = false, caller = null).errorCode()

            assertEquals(ErrorCode.INVALID_REQUEST, code)
            assertEquals(0, eventsAfter(mark).ofType("document.deleted").size, "no document row")
            assertEquals(documentsBefore, scalar("SELECT count(*) FROM documents"), "documents intact")
            assertEquals(versionsBefore, scalar("SELECT count(*) FROM document_versions"), "versions intact")
            assertEquals(2, documentsBefore, "control: the fixture holds two documents")
        }

    @Test
    fun `S9 probe a store fault at the root rolls back the document rows and the document removal`(): Unit =
        runBlocking {
            val r = newRoot("doomed")
            val c = newItem(r, "C")
            doc(r, DocumentKind.PLAN, "plan-live")
            doc(r, DocumentKind.PLAN, "plan-adopted")
            docs.adopt(r.id, "plan-adopted", c.id).value()
            val mark = mark()
            val linksBefore = scalar("SELECT count(*) FROM links")
            assertTrue(linksBefore >= 1, "control: the adoption link exists")
            exec("CREATE TRIGGER s9_fault BEFORE DELETE ON work_items WHEN OLD.title = 'doomed' BEGIN SELECT RAISE(ABORT, 'inj'); END")

            val outcome = runCatching { items.delete(r.id, recursive = true, caller = null) }
            val failed = outcome.isFailure || outcome.getOrNull() is Outcome.Err

            assertTrue(failed, "the injected store fault must fail the delete, got: $outcome")
            assertEquals(0, eventsAfter(mark).ofType("document.deleted").size, "no document row survives the failed unit")
            assertEquals(2, scalar("SELECT count(*) FROM documents"), "the documents are still there")
            assertEquals(linksBefore, scalar("SELECT count(*) FROM links"), "and the link cleanup was rolled back")
            exec("DROP TRIGGER s9_fault")
            items.delete(r.id, recursive = true, caller = null).value()
            assertEquals(
                2,
                eventsAfter(mark).ofType("document.deleted").size,
                "control: without the fault the same delete records both rows"
            )
        }

    // ---------------------------------------------------------------- S10 edges

    @Test
    fun `S10 an item delete with no documents records no document row`(): Unit =
        runBlocking {
            val r = newRoot("R")
            newItem(r, "C")
            val mark = mark()

            items.delete(r.id, recursive = true, caller = null).value()

            val rows = eventsAfter(mark)
            assertEquals(0, rows.ofType("document.deleted").size)
            assertEquals(2, rows.ofType("item.deleted").size, "control: the delete itself recorded its rows")
        }

    @Test
    fun `S10 a former root moved under another item keeps its documents and deleting it records their rows`(): Unit =
        runBlocking {
            val x = newRoot("X")
            val b = newRoot("B")
            val xPlan = doc(x, DocumentKind.PLAN, "x-plan")
            val xPage = doc(x, DocumentKind.PAGE, "x-page")
            val bPlan = doc(b, DocumentKind.PLAN, "b-plan")
            items
                .patch(
                    patchOf(checkNotNull(provider.workItemRepository().getById(x.id)), parent = ParentChange.MoveUnder(b.id)),
                    caller = null
                ).value()
            assertEquals(b.id, provider.workItemRepository().getById(x.id)!!.rootId, "control: X now lives under B")
            assertNotNull(provider.documentStore().get(x.id, DocumentKind.PLAN, "x-plan"), "the move keeps the former root's documents")
            val mark = mark()

            items.delete(x.id, recursive = false, caller = null).value()

            val rows = eventsAfter(mark)
            assertEquals(setOf(xPlan.id, xPage.id), rows.ofType("document.deleted").map { it.entityId }.toSet(), "only X's documents")
            assertDocumentRowBefore(rows, xPlan, x, "x-plan")
            assertDocumentRowBefore(rows, xPage, x, "x-page")
            assertNull(provider.documentStore().get(x.id, DocumentKind.PLAN, "x-plan"))
            assertNotNull(provider.documentStore().get(b.id, DocumentKind.PLAN, "b-plan"), "B's own document is untouched")
            assertEquals(1, scalar("SELECT count(*) FROM documents"), "control: exactly B's plan is left")
            assertEquals(bPlan.id, provider.documentStore().get(b.id, DocumentKind.PLAN, "b-plan")!!.id)
        }

    @Test
    fun `S10 a recursive delete reaches the documents of a former root nested below the deleted item`(): Unit =
        runBlocking {
            val x = newRoot("X")
            val b = newRoot("B")
            val xPlan = doc(x, DocumentKind.PLAN, "x-plan")
            val bPlan = doc(b, DocumentKind.PLAN, "b-plan")
            items
                .patch(
                    patchOf(checkNotNull(provider.workItemRepository().getById(x.id)), parent = ParentChange.MoveUnder(b.id)),
                    caller = null
                ).value()
            val mark = mark()

            items.delete(b.id, recursive = true, caller = null).value()

            val rows = eventsAfter(mark)
            assertEquals(setOf(xPlan.id, bPlan.id), rows.ofType("document.deleted").map { it.entityId }.toSet(), "both anchors' documents")
            assertDocumentRowBefore(rows, xPlan, x, "nested former root")
            assertDocumentRowBefore(rows, bPlan, b, "top root")
            assertEquals(0, scalar("SELECT count(*) FROM documents"))
            assertEquals(0, scalar("SELECT count(*) FROM document_versions"))
        }

    @Test
    fun `P1 replaying a root delete is not found and records no further document row`(): Unit =
        runBlocking {
            val r = newRoot("R")
            doc(r, DocumentKind.PLAN, "plan-live")
            items.delete(r.id, recursive = true, caller = null).value()
            val mark = mark()

            val code = items.delete(r.id, recursive = true, caller = null).errorCode()

            assertEquals(ErrorCode.NOT_FOUND, code)
            assertEquals(0, eventsAfter(mark).ofType("document.deleted").size)
            assertEquals(1, eventsAfter(0L).ofType("document.deleted").size, "control: the first delete recorded its one row")
        }
}
