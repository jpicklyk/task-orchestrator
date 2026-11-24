package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.knowledge.DocumentService
import io.github.jpicklyk.mcptask.current.model.error.Outcome
import io.github.jpicklyk.mcptask.current.model.item.WorkItem
import io.github.jpicklyk.mcptask.current.model.knowledge.Document
import io.github.jpicklyk.mcptask.current.model.knowledge.DocumentKind
import io.github.jpicklyk.mcptask.current.model.knowledge.EntityRef
import io.github.jpicklyk.mcptask.current.model.knowledge.Link
import io.github.jpicklyk.mcptask.current.model.knowledge.LinkRelation
import io.github.jpicklyk.mcptask.current.model.knowledge.RefKind
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.platform.call.ApiCapability
import io.github.jpicklyk.mcptask.current.platform.tool.ToolProfile
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `links` on `items_write` `create` and on `create_tree` children (item 2006c481): `derived_from` links from the new
 * item to a note or a document. Oracles: task-scope D1-D6 and scenarios S1-S15.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteDerivedFromTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val d2 = UUID.fromString("b2000002-0000-4000-8000-0000000000d2")
    private val rootScoped = ParityToken("df-root", setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS), rootIds = setOf(r1))

    private class Fixture(
        val k: IwKit,
        val root1: WorkItem,
        val docs: DocumentService
    )

    /** R1 > C1 (note `goal`, page `rep` with heading F-12, plan `rep`); R2 > D2 (note `goal`). */
    private suspend fun fixture(): Fixture {
        val k = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))
        val root1 = k.make("R1", id = r1)
        k.make("C1", root1, id = c1)
        val root2 = k.make("R2", id = r2)
        k.make("D2", root2, id = d2)
        note(k, c1, "goal")
        note(k, d2, "goal")
        val docs = DocumentService(k.rig.raw, db.db.unitOfWork())
        docs.write(r1, DocumentKind.PAGE, "rep", "# Intro\ntext\n## F-12\nfinding").value()
        docs.write(r1, DocumentKind.PLAN, "rep", "# Plan\n").value()
        return Fixture(k, root1, docs)
    }

    private suspend fun note(
        k: IwKit,
        itemId: UUID,
        key: String
    ) {
        k.rig.ctx.noteCommandService
            .upsert(NoteUpsertCommand(itemId, key, "work", "body of $key", null, null, caller = null))
            .value()
    }

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok: ${error.code} ${error.message}")
        }

    private fun links(vararg targets: String): JsonArray = JsonArray(targets.map { buildJsonObject { put("to", it) } })

    private fun create(
        parent: UUID?,
        links: JsonElement,
        title: String = "New"
    ): JsonObject =
        buildJsonObject {
            put("op", "create")
            put("title", title)
            put("type", TEST_ITEM_TYPE)
            if (parent != null) put("parentId", parent.toString())
            put("links", links)
        }

    private suspend fun IwKit.linksFrom(id: UUID): List<Link> = rig.raw.linkStore().findFrom(EntityRef(RefKind.ITEM, id))

    private suspend fun IwKit.doc(
        kind: DocumentKind,
        slug: String
    ): Document = checkNotNull(rig.raw.documentStore().get(r1, kind, slug))

    private fun JsonObject.field(): String =
        getValue("detail")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .first()
            .jsonObject
            .getValue("field")
            .jsonPrimitive.content

    private fun JsonObject.code(): String = getValue("code").jsonPrimitive.content

    private fun assertRejected(
        k: IwKit,
        op: JsonObject,
        code: String,
        pointer: String? = null,
        profile: ToolProfile = ToolProfile.KNOWLEDGE
    ) {
        runBlocking {
            val counts = k.tableCounts()
            val err =
                k
                    .callIn(profile, op)
                    .envelope.body
                    .errorAt()
            assertEquals(code, err.code(), "error: $err")
            if (pointer != null) assertTrue(err.field().endsWith(pointer), "field '${err.field()}' must end with '$pointer' in $err")
            assertEquals(counts, k.tableCounts(), "a rejected element writes no item, link, note or event")
        }
    }

    // ------------------------------------------------------------------ new surface

    @Test
    fun `S1 a page anchor stores one item to document derived_from link, seen as an item backlink of the page`(): Unit =
        runBlocking {
            val f = fixture()
            val id =
                f.k
                    .callIn(ToolProfile.KNOWLEDGE, create(c1, links("page:rep#f-12")))
                    .envelope.body
                    .okId()
            val link = f.k.linksFrom(id).single()
            val page = f.k.doc(DocumentKind.PAGE, "rep")
            assertEquals(LinkRelation.DERIVED_FROM, link.relation)
            assertEquals(EntityRef(RefKind.DOCUMENT, page.id), link.to)
            assertEquals("page:rep", link.toRef)
            assertEquals("f-12", link.anchor)
            assertEquals(r1, link.rootItemId)
            val incoming =
                f.k.rig.raw
                    .linkStore()
                    .findTo(EntityRef(RefKind.DOCUMENT, page.id))
            assertEquals(listOf(RefKind.ITEM), incoming.map { it.from.kind })
        }

    @Test
    fun `S2 a note source stores an item to note link, S3 a bare slug picks the page over the plan, S11 relation defaults`(): Unit =
        runBlocking {
            val f = fixture()
            val goal =
                checkNotNull(
                    f.k.rig.raw
                        .noteRepository()
                        .findByItemIdAndKey(c1, "goal")
                )
            val id =
                f.k
                    .callIn(ToolProfile.KNOWLEDGE, create(r1, links("item:${c1.toString().take(8)}#goal", "rep")))
                    .envelope.body
                    .okId()
            val (toNote, toDoc) = f.k.linksFrom(id)
            assertEquals(EntityRef(RefKind.NOTE, goal.id), toNote.to)
            assertNull(toNote.toRef)
            assertNull(toNote.anchor)
            assertEquals(EntityRef(RefKind.DOCUMENT, f.k.doc(DocumentKind.PAGE, "rep").id), toDoc.to)
            assertEquals("page:rep", toDoc.toRef)
            assertTrue(f.k.linksFrom(id).all { it.relation == LinkRelation.DERIVED_FROM })
        }

    @Test
    fun `S4 create_tree children each get their own links, the root none, and the result keeps its shape`(): Unit =
        runBlocking {
            val f = fixture()
            val tree =
                twTree(
                    twRoot("Feature"),
                    listOf(
                        twChild("a", extra = listOf("links" to links("plan:rep"))),
                        twChild("b", extra = listOf("links" to links("item:$c1#goal", "page:rep#intro")))
                    ),
                    extra = listOf("parentId" to jstr(r1.toString()))
                )
            val ok =
                f.k
                    .callIn(ToolProfile.ORCHESTRATION, tree)
                    .envelope.body
                    .okResult()
            assertEquals(3, ok.treeItems().size, "result: $ok")
            assertTrue(ok.keys.none { it.contains("link") }, "the result names no link: $ok")
            assertEquals(emptyList(), f.k.linksFrom(ok.refId("root")))
            assertEquals(listOf(f.k.doc(DocumentKind.PLAN, "rep").id), f.k.linksFrom(ok.refId("a")).map { it.to?.id })
            assertEquals(listOf(RefKind.NOTE, RefKind.DOCUMENT), f.k.linksFrom(ok.refId("b")).map { it.to?.kind })
            assertEquals("intro", f.k.linksFrom(ok.refId("b"))[1].anchor)
        }

    @Test
    fun `S5 an unknown document is not_found and writes nothing, in create and in a tree`() {
        val f = runBlocking { fixture() }
        assertRejected(f.k, create(r1, links("page:nope")), "not_found")
        assertRejected(
            f.k,
            twTree(
                twRoot("T"),
                listOf(twChild("a", extra = listOf("links" to links("nope")))),
                extra =
                    listOf(
                        "parentId" to jstr(r1.toString())
                    )
            ),
            "not_found",
            profile = ToolProfile.ORCHESTRATION
        )
    }

    @Test
    fun `S6 an anchor that is no heading of the document is invalid_request at its to`() {
        val f = runBlocking { fixture() }
        assertRejected(f.k, create(r1, links("page:rep#nope")), "invalid_request", "/links/0/to")
        assertRejected(
            f.k,
            twTree(
                twRoot("T"),
                listOf(twChild("a"), twChild("b", extra = listOf("links" to links("plan:rep", "page:rep#nope")))),
                extra = listOf("parentId" to jstr(r1.toString()))
            ),
            "invalid_request",
            "/children/1/links/1/to",
            ToolProfile.ORCHESTRATION
        )
    }

    @Test
    fun `S7 a missing note key is not_found, a note item in another root is invalid_request`() {
        val f = runBlocking { fixture() }
        assertRejected(f.k, create(r1, links("item:$c1#absent")), "not_found")
        assertRejected(f.k, create(r1, links("item:$d2#goal")), "invalid_request", "/links/0/to")
    }

    @Test
    fun `S8 under a restricted scope an out-of-scope or nonexistent note item is forbidden`(): Unit =
        runBlocking {
            val f = fixture()
            val ghost = UUID.fromString("c3000001-0000-4000-8000-000000000003")
            for (target in listOf("item:$d2#goal", "item:$ghost#goal")) {
                val counts = f.k.tableCounts()
                val err = f.k.callScoped(rootScoped, create(r1, links(target))).errorAt()
                assertEquals("forbidden", err.code(), "$target: $err")
                assertEquals(counts, f.k.tableCounts())
            }
            f.k.callScoped(rootScoped, create(r1, links("item:$c1#goal"))).okResult()
        }

    @Test
    fun `S9 malformed targets are invalid_request at their to`() {
        val f = runBlocking { fixture() }
        for (target in listOf("", "item:zz#k", "item:$c1", "item:$c1#", "x:foo", "plan:")) {
            assertRejected(f.k, create(r1, links("rep", target)), "invalid_request", "/links/1/to")
        }
    }

    @Test
    fun `S10 a repeated target or two anchors of one document fail, 10 links pass and 11 fail`() {
        val f = runBlocking { fixture() }
        assertRejected(f.k, create(r1, links("page:rep", "rep")), "invalid_request", "/links/1/to")
        assertRejected(f.k, create(r1, links("page:rep#intro", "page:rep#f-12")), "invalid_request", "/links/1/to")
        runBlocking {
            for (i in 0 until 10) note(f.k, c1, "k$i")
        }
        val ten = (0 until 10).map { "item:$c1#k$it" }
        runBlocking {
            f.k
                .callIn(ToolProfile.KNOWLEDGE, create(r1, links(*ten.toTypedArray())))
                .envelope.body
                .okResult()
        }
        assertRejected(f.k, create(r1, links(*(ten + "plan:rep").toTypedArray())), "invalid_request", "/links")
    }

    @Test
    fun `S11 any relation other than derived_from is invalid_request`() {
        val f = runBlocking { fixture() }
        val bad =
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("to", "rep")
                        put("relation", "references")
                    }
                )
            )
        assertRejected(f.k, create(r1, bad), "invalid_request")
        val explicit =
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("to", "rep")
                        put("relation", "derived_from")
                    }
                )
            )
        runBlocking {
            f.k
                .callIn(ToolProfile.KNOWLEDGE, create(r1, explicit))
                .envelope.body
                .okResult()
        }
    }

    @Test
    fun `S15 a bad link on the second child rolls the whole tree back`() {
        val f = runBlocking { fixture() }
        assertRejected(
            f.k,
            twTree(
                twRoot("T"),
                listOf(
                    twChild("a", extra = listOf("links" to links("plan:rep"))),
                    twChild(
                        "b",
                        extra =
                            listOf(
                                "links" to links("item:$c1#absent")
                            )
                    )
                ),
                extra = listOf("parentId" to jstr(r1.toString()))
            ),
            "not_found",
            profile = ToolProfile.ORCHESTRATION
        )
    }

    // ------------------------------------------------------------------ existing surface

    @Test
    fun `S12 without links create writes no link`(): Unit =
        runBlocking {
            val f = fixture()
            val before = f.k.tableCounts().getValue("links")
            f.k
                .call(
                    buildJsonObject {
                        put("op", "create")
                        put("title", "Plain")
                        put("type", TEST_ITEM_TYPE)
                        put("parentId", r1.toString())
                    }
                ).envelope.body
                .okResult()
            assertEquals(before, f.k.tableCounts().getValue("links"))
        }

    @Test
    fun `S14 an item delete removes its links, a document delete leaves them dangling and a recreate resolves them`(): Unit =
        runBlocking {
            val f = fixture()
            val keep =
                f.k
                    .call(create(r1, links("plan:rep", "item:$c1#goal"), "Keep"))
                    .envelope.body
                    .okId()
            val gone =
                f.k
                    .call(create(r1, links("plan:rep"), "Gone"))
                    .envelope.body
                    .okId()
            f.k
                .call(deleteOp(gone))
                .envelope.body
                .okResult()
            assertEquals(emptyList(), f.k.linksFrom(gone))

            f.docs.delete(r1, DocumentKind.PLAN, "rep").value()
            val dangling = f.k.linksFrom(keep).single { it.toRef == "plan:rep" }
            assertNull(dangling.to)
            val recreated = f.docs.write(r1, DocumentKind.PLAN, "rep", "# Again\n").value()
            assertEquals(
                EntityRef(RefKind.DOCUMENT, recreated.id),
                f.k
                    .linksFrom(keep)
                    .single { it.toRef == "plan:rep" }
                    .to
            )
        }
}
