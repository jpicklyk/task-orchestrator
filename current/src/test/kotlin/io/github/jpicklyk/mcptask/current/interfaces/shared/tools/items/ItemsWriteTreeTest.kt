package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.bootstrap.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.Dispatched
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolSchemaRenderer
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------------------------------------------------
// Shared plumbing for the tree test files (create_tree and complete_tree). Pure construction and reading helpers; no
// assertion logic about the behaviour under test lives here.
// ---------------------------------------------------------------------------------------------------------------------

internal fun twObj(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

/** A `root` spec that creates a root item. A null [title] or [type] leaves the member out; [extra] adds members. */
internal fun twRoot(
    title: String?,
    type: String? = TEST_ITEM_TYPE,
    extra: List<Pair<String, JsonElement>> = emptyList()
): JsonObject =
    buildJsonObject {
        if (title != null) put("title", title)
        if (type != null) put("type", type)
        extra.forEach { (k, v) -> put(k, v) }
    }

/** A `children[]` element. A null [title] or [type] leaves the member out. */
internal fun twChild(
    ref: String,
    parentRef: String? = null,
    type: String? = TEST_ITEM_TYPE,
    title: String? = "Title $ref",
    extra: List<Pair<String, JsonElement>> = emptyList()
): JsonObject =
    buildJsonObject {
        put("ref", ref)
        if (title != null) put("title", title)
        if (type != null) put("type", type)
        if (parentRef != null) put("parentRef", parentRef)
        extra.forEach { (k, v) -> put(k, v) }
    }

internal fun twDep(
    from: String,
    to: String,
    type: String? = null,
    unblockAt: String? = null
): JsonObject =
    buildJsonObject {
        put("from", from)
        put("to", to)
        if (type != null) put("type", type)
        if (unblockAt != null) put("unblockAt", unblockAt)
    }

internal fun twNote(
    itemRef: String,
    key: String,
    role: String,
    body: String
): JsonObject =
    buildJsonObject {
        put("itemRef", itemRef)
        put("key", key)
        put("role", role)
        put("body", body)
    }

internal fun twAnchor(
    noteKey: String,
    role: String,
    anchor: String
): JsonObject =
    buildJsonObject {
        put("noteKey", noteKey)
        put("role", role)
        put("anchor", anchor)
    }

/** A `create_tree` element. */
internal fun twTree(
    root: JsonObject,
    children: List<JsonObject> = emptyList(),
    deps: List<JsonObject> = emptyList(),
    notes: List<JsonObject> = emptyList(),
    extra: List<Pair<String, JsonElement>> = emptyList()
): JsonObject =
    buildJsonObject {
        put("op", "create_tree")
        put("root", root)
        if (children.isNotEmpty()) put("children", JsonArray(children))
        if (deps.isNotEmpty()) put("deps", JsonArray(deps))
        if (notes.isNotEmpty()) put("notes", JsonArray(notes))
        extra.forEach { (k, v) -> put(k, v) }
    }

/** A `complete_tree` element. */
internal fun twComplete(vararg fields: Pair<String, JsonElement>): JsonObject =
    buildJsonObject {
        put("op", "complete_tree")
        fields.forEach { (k, v) -> put(k, v) }
    }

internal fun twIds(vararg ids: UUID): JsonArray = JsonArray(ids.map { JsonPrimitive(it.toString()) })

/** Dispatches over the real dispatcher in [profile] (the shared kit `call` is fixed to the knowledge profile, which hides tree ops per U23). */
internal suspend fun IwKit.callIn(
    profile: ToolProfile,
    vararg ops: JsonObject,
    atomic: Boolean = false,
    actor: JsonObject? = null
): Dispatched {
    val spec = checkNotNull(ToolRegistry(buildToolSpecs())["items_write"]) { "items_write is not registered" }
    val args =
        buildJsonObject {
            actor?.let { put("actor", it) }
            if (atomic) put("atomic", true)
            put("ops", JsonArray(ops.toList()))
        }
    return buildToolDispatcher(rig.ctx).dispatch(spec, profile, args, "k7f3q9ab", "mcp")
}

/** Row counts of every table a tree can write to (the events table included). */
internal fun IwKit.tableCounts(): Map<String, Int> =
    DriverManager.getConnection(rig.db.jdbcUrl).use { c ->
        listOf("work_items", "notes", "dependencies", "links", "events").associateWith { table ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM $table").use {
                    it.next()
                    it.getInt(1)
                }
            }
        }
    }

internal suspend fun IwKit.notesOf(id: UUID): List<Note> = rig.raw.noteRepository().findByItemId(id)

internal suspend fun IwKit.depsOf(id: UUID): List<Dependency> = rig.raw.dependencyRepository().findByItemId(id)

internal fun JsonObject.treeItems(): List<JsonObject> = getValue("items").jsonArray.map { it.jsonObject }

/** The id of the `items[]` entry whose `ref` is [ref]. */
internal fun JsonObject.refId(ref: String): UUID = UUID.fromString(treeItems().single { it.str("ref") == ref }.str("id"))

/**
 * Tests for `items_write` `create_tree` (item c820b3d0, test-author seat): scenarios S1 to S5, S8, S9, S12, S13, S16 to S19 of
 * the frozen test-plan, all NEW-SURFACE, driven through the real dispatcher (and, for scope, a real MCP client/server pair)
 * over the production composition.
 *
 * Oracles: task-scope B2 (request classes), B3 (element shape), B4 (op order, error codes, element-relative pointers), AC2 to
 * AC7 and AC1; decisions D1, D2, D8, D9. The settled 3.x behaviour the op inherits is cited from public test evidence:
 * the plan-document slicing (`# Overview\nFeature overview text.` for anchor `overview`), the `warning` object `{key,max,actual}`
 * and the note codes (`payload_too_large`, `note_too_long`) of the notes_write tests.
 *
 * Arbitration records are in the test-manifest note: (1) pointer prefix: task-scope B4 writes element-relative pointers and the
 * test-plan writes `/ops/0/...`; every pointer assertion is a suffix match on the element-relative form, so both spellings pass.
 * (2) Every absence assertion (zero rows, no events) is paired with a control that writes through the same call shape.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsWriteTreeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun kit(config: String = IW_CONFIG) = IwKit(EventLogRig.build(db.db, dir, config))

    private val planBody = "# Overview\nFeature overview text.\n# Task 1\nTask 1 detail text."

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val c2 = UUID.fromString("a1000003-0000-4000-8000-0000000000c2")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val d2 = UUID.fromString("b2000002-0000-4000-8000-0000000000d2")

    private val writeCaps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
    private val rootScoped = ParityToken("tw-root", writeCaps, rootIds = setOf(r1))
    private val tagScoped = ParityToken("tw-tag", writeCaps, tagsInclude = setOf("alpha"))
    private val readOnly = ParityToken("tw-read", setOf(ApiCapability.READ))

    /** R1 (alpha) > C1 (alpha,shared), C2 (alpha); R2 (beta) > D2 (beta); seeded through ItemCommandService, never the op under test. */
    private suspend fun seeded(): IwKit {
        val k = kit()
        val root1 = k.make("R1", tags = "alpha", id = r1)
        k.make("C1", root1, tags = "alpha,shared", id = c1)
        k.make("C2", root1, tags = "alpha", id = c2)
        val root2 = k.make("R2", tags = "beta", id = r2)
        k.make("D2", root2, tags = "beta", id = d2)
        return k
    }

    private fun JsonObject.field(): String =
        (
            getValue("detail")
                .jsonObject
                .getValue("fields")
                .jsonArray
                .first() as JsonObject
        ).str("field")

    private fun assertField(
        err: JsonObject,
        pointer: String
    ) {
        assertEquals("invalid_request", err.str("code"), "error: $err")
        assertTrue(err.field().endsWith(pointer), "field '${err.field()}' must end with '$pointer' in $err")
    }

    /** Asserts nothing was written since [before]/[counts] were taken: the four snapshot tables and every row count (links included). */
    private fun assertUntouched(
        k: IwKit,
        before: Map<String, List<Map<String, Any?>>>,
        counts: Map<String, Int>
    ) {
        assertEquals(before, k.snapshot(), "no work_items, notes, dependencies or events row changed")
        assertEquals(counts, k.tableCounts(), "no row of any table (links included) was added")
    }

    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 create_tree writes root, children, dependency and note in one element and answers the documented shape`(): Unit =
        runBlocking {
            val k = kit()
            val r =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(
                        twRoot("Root"),
                        listOf(twChild("c1"), twChild("c2", "c1")),
                        listOf(twDep("c1", "c2")),
                        listOf(twNote("c1", "k1", "work", "hello"))
                    )
                )
            assertEquals(200, r.envelope.httpStatus)
            assertEquals("""{"ok":1,"failed":0}""", r.envelope.body.summary())
            val ok = r.envelope.body.okResult()
            assertEquals(setOf("ok", "id", "version", "items", "deps", "notes"), ok.keys, "B3: exactly these members")

            val items = ok.treeItems()
            assertEquals(listOf("root", "c1", "c2"), items.map { it.str("ref") }, "root first, then children in insertion order")
            assertEquals(listOf("0", "1", "2"), items.map { it.str("depth") })
            val root = ok.refId("root")
            val a = ok.refId("c1")
            val b = ok.refId("c2")
            assertEquals(3, setOf(root, a, b).size, "every id distinct")
            assertEquals(root.toString(), ok.str("id"), "the element id is the root id")
            val rootRow = assertNotNull(k.item(root))
            assertEquals(rootRow.version, ok.getValue("version").jsonPrimitive.long, "the element version is the root row's version")
            assertEquals(3, k.all().size)

            assertEquals(Triple(null, 0, root), Triple(rootRow.parentId, rootRow.depth, rootRow.rootId))
            val aRow = assertNotNull(k.item(a))
            val bRow = assertNotNull(k.item(b))
            assertEquals(Triple(root, 1, root), Triple(aRow.parentId, aRow.depth, aRow.rootId))
            assertEquals(Triple(a, 2, root), Triple(bRow.parentId, bRow.depth, bRow.rootId))
            assertEquals(listOf("Root", "Title c1", "Title c2"), listOf(rootRow.title, aRow.title, bRow.title))
            assertEquals(Role.QUEUE, aRow.role)

            val dep =
                ok
                    .getValue("deps")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals("c1", dep.str("fromRef"))
            assertEquals("c2", dep.str("toRef"))
            assertEquals("blocks", dep.str("type"))
            val stored = k.depsOf(a).single()
            assertEquals(dep.str("id"), stored.id.toString(), "the element dep id is the stored row's id")
            assertEquals(Triple(a, b, DependencyType.BLOCKS), Triple(stored.fromItemId, stored.toItemId, stored.type))

            val note =
                ok
                    .getValue("notes")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(Triple("c1", "k1", "work"), Triple(note.str("itemRef"), note.str("key"), note.str("role")))
            val storedNote = k.notesOf(a).single()
            assertEquals(note.str("id"), storedNote.id.toString())
            assertEquals("hello", storedNote.body)
            assertEquals("work", storedNote.role)
        }

    @Test
    fun `S1 children listed before their parent are still inserted parent-first, with depth and root derived from the parent rows`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("Root"), listOf(twChild("c2", "c1"), twChild("c1"))))
                    .envelope.body
                    .okResult()
            assertEquals(listOf("root", "c1", "c2"), ok.treeItems().map { it.str("ref") }, "the parent c1 precedes its child c2")
            val first = assertNotNull(k.item(ok.refId("c1")))
            val second = assertNotNull(k.item(ok.refId("c2")))
            assertEquals(ok.refId("root"), first.parentId)
            assertEquals(first.id, second.parentId, "c2's parent is the row created for c1")
            assertEquals(listOf(1, 2), listOf(first.depth, second.depth))
            assertEquals(ok.refId("root"), second.rootId)
        }

    @Test
    fun `S1 siblings with no ordering constraint keep the request order`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("Root"), listOf(twChild("c3"), twChild("c1"), twChild("c2"))))
                    .envelope.body
                    .okResult()
            assertEquals(listOf("root", "c3", "c1", "c2"), ok.treeItems().map { it.str("ref") })
            assertEquals(listOf(1, 1, 1), listOf("c3", "c1", "c2").map { assertNotNull(k.item(ok.refId(it))).depth })
        }

    @Test
    fun `S1 dependencies are answered in request order with the stored type and unblockAt, a relates_to edge carries no threshold`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("Root"),
                            listOf(twChild("a"), twChild("b"), twChild("c")),
                            listOf(twDep("b", "c", unblockAt = "work"), twDep("a", "b"), twDep("a", "c", type = "relates_to"))
                        )
                    ).envelope.body
                    .okResult()
            val deps = ok.getValue("deps").jsonArray.map { it.jsonObject }
            assertEquals(listOf("b" to "c", "a" to "b", "a" to "c"), deps.map { it.str("fromRef") to it.str("toRef") })
            assertEquals(listOf("blocks", "blocks", "relates_to"), deps.map { it.str("type") })
            assertEquals("work", deps[0].str("unblockAt"))
            val a = ok.refId("a")
            val b = ok.refId("b")
            val c = ok.refId("c")
            val bc = k.depsOf(b).single { it.fromItemId == b && it.toItemId == c }
            assertEquals("work", bc.unblockAt)
            val ac = k.depsOf(a).single { setOf(it.fromItemId, it.toItemId) == setOf(a, c) }
            assertEquals(DependencyType.RELATES_TO, ac.type)
            assertNull(ac.unblockAt)
            assertEquals(3, k.tableCounts().getValue("dependencies"))
        }

    // ------------------------------------------------------------------ S2 attach

    @Test
    fun `S2 attach mode inserts no root row, lands the children under the existing item and answers its id and version`(): Unit =
        runBlocking {
            val k = kit()
            val project = k.make("Project")
            val attached = k.make("Attach root", project)
            assertEquals(2, k.all().size)
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            buildJsonObject { put("id", attached.id.toString().take(8)) },
                            listOf(twChild("x"), twChild("y", "x"))
                        )
                    ).envelope.body
                    .okResult()
            assertEquals(4, k.all().size, "two children added, no new root row")
            assertEquals(attached.id.toString(), ok.str("id"))
            assertEquals(attached.id, ok.refId("root"), "the attach-mode root is listed with its existing id")
            assertEquals(attached.version, ok.getValue("version").jsonPrimitive.long)
            assertEquals("Attach root", assertNotNull(k.item(attached.id)).title)
            val x = assertNotNull(k.item(ok.refId("x")))
            val y = assertNotNull(k.item(ok.refId("y")))
            assertEquals(Triple(attached.id, attached.depth + 1, project.id), Triple(x.parentId, x.depth, x.rootId))
            assertEquals(Triple(x.id, attached.depth + 2, project.id), Triple(y.parentId, y.depth, y.rootId))
        }

    @Test
    fun `S2 an upper-case root id resolves, a 7-hex prefix and an unknown id do not, and root id with parentId is rejected`(): Unit =
        runBlocking {
            val k = kit()
            val target = k.make("Target")
            val upper =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(buildJsonObject { put("id", target.id.toString().uppercase()) }, listOf(twChild("u")))
                    ).envelope.body
                    .okResult()
            assertEquals(target.id.toString(), upper.str("id"), "the upper-case spelling resolves to the same item")
            val before = k.snapshot()
            val counts = k.tableCounts()

            val short =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(buildJsonObject { put("id", target.id.toString().take(7)) }, listOf(twChild("s")))
                    ).envelope.body
                    .errorAt()
            assertEquals("invalid_request", short.str("code"), "7 hex characters are below the 8-hex prefix minimum")
            val ghost = UUID.fromString("11111111-2222-4333-8444-555555555555")
            val missing =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(buildJsonObject { put("id", ghost.toString()) }, listOf(twChild("m"))))
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", missing.str("code"))
            val both =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            buildJsonObject { put("id", target.id.toString()) },
                            listOf(twChild("b")),
                            extra = listOf("parentId" to jstr(target.id.toString()))
                        )
                    ).envelope.body
                    .errorAt()
            assertField(both, "/parentId")
            assertUntouched(k, before, counts)
        }

    @Test
    fun `S2 an ambiguous 8-hex prefix as parentId or root id is ambiguous_id and nothing is written`(): Unit =
        runBlocking {
            val k = kit()
            val one = k.make("One", id = UUID.fromString("abcdef12-0000-4000-8000-000000000001"))
            val two = k.make("Two", id = UUID.fromString("abcdef12-0000-4000-8000-000000000002"))
            val before = k.snapshot()
            val counts = k.tableCounts()
            val asParent =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("T"), extra = listOf("parentId" to jstr("abcdef12"))))
                    .envelope.body
                    .errorAt()
            assertEquals("ambiguous_id", asParent.str("code"))
            assertTrue(one.id.toString() in asParent.toString() && two.id.toString() in asParent.toString(), asParent.toString())
            val asRoot =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(buildJsonObject { put("id", "abcdef12") }))
                    .envelope.body
                    .errorAt()
            assertEquals("ambiguous_id", asRoot.str("code"))
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("T"), extra = listOf("parentId" to jstr(one.id.toString()))))
                .envelope.body
                .okResult()
            assertEquals(3, k.all().size, "control: the full id works through the same call shape")
        }

    // ------------------------------------------------------------------ S3 parentId

    @Test
    fun `S3 parentId places the root at parent depth plus one under the parent's root, children follow`(): Unit =
        runBlocking {
            val k = kit()
            val top = k.make("Top")
            val mid = k.make("Mid", top)
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("Tree root"),
                            listOf(twChild("a")),
                            extra = listOf("parentId" to jstr(mid.id.toString().take(8)))
                        )
                    ).envelope.body
                    .okResult()
            val root = assertNotNull(k.item(ok.refId("root")))
            assertEquals(Triple(mid.id, 2, top.id), Triple(root.parentId, root.depth, root.rootId))
            val child = assertNotNull(k.item(ok.refId("a")))
            assertEquals(Triple(root.id, 3, top.id), Triple(child.parentId, child.depth, child.rootId))
            assertEquals("2", ok.treeItems().first().str("depth"))

            val free =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("Free")))
                    .envelope.body
                    .okResult()
            val freeRow = assertNotNull(k.item(free.refId("root")))
            assertEquals(
                Triple(null, 0, freeRow.id),
                Triple(freeRow.parentId, freeRow.depth, freeRow.rootId),
                "control: without parentId the root is its own root"
            )
        }

    // ------------------------------------------------------------------ S4 docRef and noteAnchors

    private suspend fun planProject(k: IwKit): UUID {
        val project = k.make("Project")
        k.rig.raw
            .planDocumentRepository()
            .stash(project.id, "my-plan", planBody)
        return project.id
    }

    private fun docTree(
        project: UUID,
        explicit: List<JsonObject> = emptyList(),
        rootAnchor: String = "overview"
    ): JsonObject =
        twTree(
            twRoot("Feature X", extra = listOf("noteAnchors" to JsonArray(listOf(twAnchor("requirements", "queue", rootAnchor))))),
            listOf(
                twChild(
                    "t1",
                    extra =
                        listOf(
                            "noteAnchors" to
                                JsonArray(listOf(twAnchor("task-scope", "queue", "task-1"), twAnchor("extra", "work", "overview")))
                        )
                )
            ),
            notes = explicit,
            extra = listOf("parentId" to jstr(project.toString()), "docRef" to buildJsonObject { put("slug", "my-plan") })
        )

    @Test
    fun `S4 anchors cut note bodies, an explicit note wins a collision, the document is adopted and links are derived`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val linksBefore = k.tableCounts().getValue("links")
            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, docTree(project, listOf(twNote("t1", "extra", "work", "explicit wins"))))
                    .envelope.body
                    .okResult()
            val root = ok.refId("root")
            val t1 = ok.refId("t1")
            val requirements =
                assertNotNull(
                    k.rig.raw
                        .noteRepository()
                        .findByItemIdAndKey(root, "requirements")
                )
            assertEquals("# Overview\nFeature overview text.", requirements.body, "anchor overview cuts the first section")
            val taskScope =
                assertNotNull(
                    k.rig.raw
                        .noteRepository()
                        .findByItemIdAndKey(t1, "task-scope")
                )
            assertEquals("# Task 1\nTask 1 detail text.", taskScope.body)
            val extra =
                assertNotNull(
                    k.rig.raw
                        .noteRepository()
                        .findByItemIdAndKey(t1, "extra")
                )
            assertEquals("explicit wins", extra.body, "explicit > anchor on the same (item, key)")
            assertEquals(1, k.notesOf(t1).count { it.key == "extra" }, "one note row per (item, key)")

            val doc =
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                )
            assertEquals(PlanDocumentStatus.ADOPTED, doc.status)
            assertEquals(root, doc.adoptedByItemId, "the tree root adopts the document")

            val links = k.rig.raw.linkStore()
            val fromRequirements = links.findFrom(EntityRef(RefKind.NOTE, requirements.id)).single()
            assertEquals(LinkRelation.DERIVED_FROM, fromRequirements.relation)
            assertEquals("overview", fromRequirements.anchor)
            assertEquals("plan:my-plan", fromRequirements.toRef)
            val fromTask = links.findFrom(EntityRef(RefKind.NOTE, taskScope.id)).single()
            assertEquals(LinkRelation.DERIVED_FROM, fromTask.relation)
            assertEquals("task-1", fromTask.anchor)
            assertEquals(emptyList(), links.findFrom(EntityRef(RefKind.NOTE, extra.id)), "the overridden anchor derives nothing")
            assertEquals(linksBefore + 3, k.tableCounts().getValue("links"), "two derived_from links plus one adopted_by link")
        }

    @Test
    fun `S4 two explicit notes on one item and key leave one row holding the last body`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("Root"),
                            listOf(twChild("c1")),
                            notes = listOf(twNote("c1", "k", "work", "first"), twNote("c1", "k", "work", "second"))
                        )
                    ).envelope.body
                    .okResult()
            val rows = k.notesOf(ok.refId("c1"))
            assertEquals(listOf("second"), rows.map { it.body }, "last wins per (itemRef, key)")
        }

    @Test
    fun `S4 a docRef rootId naming the tree's own project root is accepted, a foreign root is invalid_request on docRef rootId`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val other = k.make("Other project")
            k.rig.raw
                .planDocumentRepository()
                .stash(other.id, "my-plan", planBody)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val foreign =
                twTree(
                    twRoot("T"),
                    extra =
                        listOf(
                            "parentId" to jstr(project.toString()),
                            "docRef" to
                                buildJsonObject {
                                    put("slug", "my-plan")
                                    put("rootId", other.id.toString())
                                }
                        )
                )
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, foreign)
                    .envelope.body
                    .errorAt(),
                "/docRef/rootId"
            )
            assertUntouched(k, before, counts)
            assertEquals(
                PlanDocumentStatus.PENDING,
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(other.id, "my-plan")
                ).status
            )
            val own =
                twTree(
                    twRoot("T"),
                    extra =
                        listOf(
                            "parentId" to jstr(project.toString()),
                            "docRef" to
                                buildJsonObject {
                                    put("slug", "my-plan")
                                    put("rootId", project.toString())
                                }
                        )
                )
            val ok =
                k
                    .callIn(ToolProfile.ORCHESTRATION, own)
                    .envelope.body
                    .okResult()
            assertEquals(
                ok.refId("root"),
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                ).adoptedByItemId,
                "control: the matching rootId adopts"
            )
        }

    // ------------------------------------------------------------------ S5 createNotes

    @Test
    fun `S5 createNotes writes blanks for uncovered schema keys, keeps off-schema notes, explicit wins over blanks`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("Gated root", "feature-task"),
                            listOf(twChild("plain"), twChild("gated", type = "feature-task"), twChild("given", type = "feature-task")),
                            notes = listOf(twNote("root", "free", "work", "off-schema"), twNote("given", "spec", "queue", "provided")),
                            extra = listOf("createNotes" to JsonPrimitive(true))
                        )
                    ).envelope.body
                    .okResult()
            val rootSpec = k.notesOf(ok.refId("root")).single { it.key == "spec" }
            assertEquals(Pair("queue", ""), Pair(rootSpec.role, rootSpec.body), "blank queue note for the uncovered required key")
            assertEquals("off-schema", k.notesOf(ok.refId("root")).single { it.key == "free" }.body)
            assertEquals("", k.notesOf(ok.refId("gated")).single { it.key == "spec" }.body)
            assertEquals("provided", k.notesOf(ok.refId("given")).single { it.key == "spec" }.body, "the explicit note replaces the blank")
            assertEquals(1, k.notesOf(ok.refId("given")).size)
            assertEquals(emptyList(), k.notesOf(ok.refId("plain")), "an item of a type with no declared notes gets none")
            val answered = ok.getValue("notes").jsonArray.map { it.jsonObject.str("itemRef") + "/" + it.jsonObject.str("key") }
            assertTrue("root/spec" in answered && "gated/spec" in answered && "root/free" in answered, "answered notes: $answered")
        }

    @Test
    fun `S5 without createNotes a gated tree gets no blank notes`(): Unit =
        runBlocking {
            val k = kit()
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(twRoot("Gated root", "feature-task"), listOf(twChild("g", type = "feature-task")))
                    ).envelope.body
                    .okResult()
            assertEquals(emptyList(), k.notesOf(ok.refId("root")))
            assertEquals(emptyList(), k.notesOf(ok.refId("g")))
            assertEquals(0, k.tableCounts().getValue("notes"))
        }

    // ------------------------------------------------------------------ S8 shape failures

    @Test
    fun `S8 an unknown parentRef is invalid_request at that child's parentRef and writes nothing, the corrected call writes`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val bad = twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", "nope")))
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, bad)
                    .envelope.body
                    .errorAt(),
                "/children/1/parentRef"
            )
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", "c0"))))
                .envelope.body
                .okResult()
            assertEquals(3, k.all().size, "control: the same shape with a known parentRef writes the whole tree")
        }

    @Test
    fun `S8 a parentRef cycle is invalid_request on children naming the cycle and writes nothing`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val err =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("ok1"), twChild("a", "b"), twChild("b", "a"))))
                    .envelope.body
                    .errorAt()
            assertField(err, "/children")
            assertTrue("parentRef chain is cyclic" in err.toString(), err.toString())
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("ok1"), twChild("a", "ok1"), twChild("b", "a"))))
                .envelope.body
                .okResult()
            assertEquals(4, k.all().size, "control: an acyclic chain writes")
        }

    @Test
    fun `S8 a child without a type is invalid_request at its type, a blank or undeclared type is schema_violation`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", type = null))))
                    .envelope.body
                    .errorAt(),
                "/children/1/type"
            )
            assertEquals(
                "schema_violation",
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", type = "no-such-type"))))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertEquals(
                "schema_violation",
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", type = ""))))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"), twChild("c1", type = "feature-task"))))
                .envelope.body
                .okResult()
            assertEquals(3, k.all().size, "control: a declared type writes")
        }

    @Test
    fun `S8 create mode needs root title and root type, a blank or undeclared root type is schema_violation`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot(null), listOf(twChild("c0"))))
                    .envelope.body
                    .errorAt(),
                "/root/title"
            )
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R", null), listOf(twChild("c0"))))
                    .envelope.body
                    .errorAt(),
                "/root/type"
            )
            assertEquals(
                "schema_violation",
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R", "")))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertEquals(
                "schema_violation",
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R", "no-such-type")))
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c0"))))
                .envelope.body
                .okResult()
            assertEquals(2, k.all().size)
        }

    @Test
    fun `S8 ref root and a repeated ref are invalid_request at the offending child's ref`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("a"), twChild("root"))))
                    .envelope.body
                    .errorAt(),
                "/children/1/ref"
            )
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("a"), twChild("b"), twChild("a"))))
                    .envelope.body
                    .errorAt(),
                "/children/2/ref"
            )
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("a"), twChild("b"))))
                .envelope.body
                .okResult()
            assertEquals(3, k.all().size)
        }

    @Test
    fun `S8 a dependency or note naming an unknown ref is invalid_request at that element`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val kids = listOf(twChild("a"), twChild("b"))
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("a", "b"), twDep("zz", "b"))))
                    .envelope.body
                    .errorAt(),
                "/deps/1/from"
            )
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("a", "zz"))))
                    .envelope.body
                    .errorAt(),
                "/deps/0/to"
            )
            assertField(
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(twRoot("R"), kids, notes = listOf(twNote("a", "k", "work", "x"), twNote("zz", "k", "work", "x")))
                    ).envelope.body
                    .errorAt(),
                "/notes/1/itemRef"
            )
            assertUntouched(k, before, counts)
            k
                .callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(twRoot("R"), kids, listOf(twDep("a", "root")), listOf(twNote("root", "k", "work", "x")))
                ).envelope.body
                .okResult()
            assertEquals(3, k.all().size, "control: the reserved ref root is a valid dependency end and note target")
        }

    @Test
    fun `S8 noteAnchors without a docRef and a rule slug are invalid_request`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val anchored = twRoot("R", extra = listOf("noteAnchors" to JsonArray(listOf(twAnchor("requirements", "queue", "overview")))))
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(anchored))
                    .envelope.body
                    .errorAt(),
                "/docRef"
            )
            val ruleSlug = twTree(twRoot("R"), extra = listOf("docRef" to buildJsonObject { put("slug", "rule/x") }))
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, ruleSlug)
                    .envelope.body
                    .errorAt(),
                "/docRef/slug"
            )
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R")))
                .envelope.body
                .okResult()
            assertEquals(1, k.all().size)
        }

    @Test
    fun `S8 priority High in capitals and tags as a comma string are rejected, lower-case and arrays are accepted`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val loud =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R", extra = listOf("priority" to jstr("High")))))
                    .envelope.body
                    .errorAt()
            assertField(loud, "/priority")
            val csv =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R", extra = listOf("tags" to jstr("a,b")))))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", csv.str("code"))
            val childCsv =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), listOf(twChild("c", extra = listOf("tags" to jstr("a,b"))))))
                    .envelope.body
                    .errorAt()
            assertEquals("invalid_request", childCsv.str("code"))
            assertUntouched(k, before, counts)
            val ok =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("R", extra = listOf("priority" to jstr("high"), "tags" to strings("a", "b"))),
                            listOf(twChild("c", extra = listOf("priority" to jstr("low"))))
                        )
                    ).envelope.body
                    .okResult()
            assertEquals("a,b", assertNotNull(k.item(ok.refId("root"))).tags)
            assertEquals(listOf("high", "low"), listOf("root", "c").map { assertNotNull(k.item(ok.refId(it))).priority.name.lowercase() })
        }

    @Test
    fun `S8 an unknown member of a tree element is unknown_parameter and a valid sibling still commits`(): Unit =
        runBlocking {
            val k = kit()
            val r =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    JsonObject(twTree(twRoot("U")) + ("surprise" to JsonPrimitive(1))),
                    twTree(twRoot("V"))
                )
            val err = r.envelope.body.errorAt(0)
            assertEquals("unknown_parameter", err.str("code"))
            assertEquals(
                listOf("/ops/0/surprise"),
                err
                    .getValue("detail")
                    .jsonObject
                    .getValue("parameters")
                    .jsonArray
                    .map { it.jsonPrimitive.content }
            )
            assertEquals(listOf("V"), k.titles())
        }

    // ------------------------------------------------------------------ S9 service-level failures roll the element back

    @Test
    fun `S9 a dependency cycle is cycle_detected and a restated pair is duplicate, each leaving zero rows`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val kids = listOf(twChild("c1"), twChild("c2"))
            val cycle =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("c1", "c2"), twDep("c2", "c1"))))
                    .envelope.body
                    .errorAt()
            assertEquals("cycle_detected", cycle.str("code"))
            val restated =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("c1", "c2"), twDep("c1", "c2"))))
                    .envelope.body
                    .errorAt()
            assertEquals("duplicate", restated.str("code"))
            assertUntouched(k, before, counts)
            k
                .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("c1", "c2"))))
                .envelope.body
                .okResult()
            assertEquals(3, k.all().size)
            assertEquals(1, k.tableCounts().getValue("dependencies"), "control: the acyclic single edge writes")
        }

    @Test
    fun `S9 a closed parent is invalid_transition and an unknown parentId is not_found, with nothing written`(): Unit =
        runBlocking {
            val k = kit()
            val closed = k.rig.seed("Closed", role = Role.TERMINAL)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val shut =
                k
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(
                            twRoot("R"),
                            listOf(twChild("c")),
                            extra =
                                listOf(
                                    "parentId" to jstr(closed.id.toString())
                                )
                        )
                    ).envelope.body
                    .errorAt()
            assertEquals("invalid_transition", shut.str("code"))
            val ghost = UUID.fromString("11111111-2222-4333-8444-555555555555")
            val missing =
                k
                    .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), extra = listOf("parentId" to jstr(ghost.toString()))))
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", missing.str("code"))
            assertUntouched(k, before, counts)
            val open = k.make("Open")
            k
                .callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(
                        twRoot("R"),
                        listOf(twChild("c")),
                        extra =
                            listOf(
                                "parentId" to jstr(open.id.toString())
                            )
                    )
                ).envelope.body
                .okResult()
            assertEquals(4, k.all().size, "control: an open parent accepts the same tree (closed, open, root, child)")
        }

    @Test
    fun `S9 an anchor that matches no section is invalid_request at that anchor and the document stays unadopted`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, docTree(project, rootAnchor = "does-not-exist"))
                    .envelope.body
                    .errorAt(),
                "/root/noteAnchors/0/anchor"
            )
            val childMiss =
                twTree(
                    twRoot("R"),
                    listOf(
                        twChild(
                            "t",
                            extra =
                                listOf(
                                    "noteAnchors" to JsonArray(listOf(twAnchor("a", "work", "overview"), twAnchor("b", "work", "nope")))
                                )
                        )
                    ),
                    extra = listOf("parentId" to jstr(project.toString()), "docRef" to buildJsonObject { put("slug", "my-plan") })
                )
            assertField(
                k
                    .callIn(ToolProfile.ORCHESTRATION, childMiss)
                    .envelope.body
                    .errorAt(),
                "/children/0/noteAnchors/1/anchor"
            )
            assertUntouched(k, before, counts)
            val doc =
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                )
            assertEquals(PlanDocumentStatus.PENDING, doc.status)
            assertNull(doc.adoptedByItemId)
            k
                .callIn(ToolProfile.ORCHESTRATION, docTree(project))
                .envelope.body
                .okResult()
            assertEquals(
                PlanDocumentStatus.ADOPTED,
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                ).status,
                "control: valid anchors adopt"
            )
        }

    @Test
    fun `S9 an unknown slug is not_found of a document and an already adopted document is duplicate of a document`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val unknown =
                twTree(
                    twRoot("R"),
                    extra = listOf("parentId" to jstr(project.toString()), "docRef" to buildJsonObject { put("slug", "no-such-plan") })
                )
            val missing =
                k
                    .callIn(ToolProfile.ORCHESTRATION, unknown)
                    .envelope.body
                    .errorAt()
            assertEquals("not_found", missing.str("code"))
            assertEquals(
                "document",
                missing
                    .getValue("detail")
                    .jsonObject
                    .str("kind")
            )
            assertUntouched(k, before, counts)

            val earlier = k.make("Earlier adopter")
            assertNotNull(
                k.rig.raw
                    .planDocumentRepository()
                    .markAdopted(project, "my-plan", earlier.id),
                "fixture: the document is adopted"
            )
            val afterFixture = k.snapshot()
            val countsAfterFixture = k.tableCounts()
            val again =
                k
                    .callIn(ToolProfile.ORCHESTRATION, docTree(project))
                    .envelope.body
                    .errorAt()
            assertEquals("duplicate", again.str("code"))
            assertEquals(
                "document",
                again
                    .getValue("detail")
                    .jsonObject
                    .str("kind")
            )
            assertUntouched(k, afterFixture, countsAfterFixture)
            assertEquals(
                earlier.id,
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                ).adoptedByItemId,
                "the earlier adoption is untouched"
            )
        }

    private val limConfig =
        "note_limits:\n  mode: %MODE%\n" +
            "work_item_schemas:\n  test-item:\n    notes: []\n" +
            "  lim-type:\n    notes:\n      - key: lim\n        role: work\n        maxLength: 10\n" +
            "  feature-task:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n"

    @Test
    fun `S9 note policy - maxLength in reject mode is note_too_long and writes nothing, exactly maxLength is accepted`(): Unit =
        runBlocking {
            val reject = kit(limConfig.replace("%MODE%", "reject"))
            val before = reject.snapshot()
            val counts = reject.tableCounts()
            val over = twTree(twRoot("R", "lim-type"), listOf(twChild("c")), notes = listOf(twNote("root", "lim", "work", "x".repeat(11))))
            val err =
                reject
                    .callIn(ToolProfile.ORCHESTRATION, over)
                    .envelope.body
                    .errorAt()
            assertEquals(ErrorCode.NOTE_TOO_LONG.wire, err.str("code"))
            assertUntouched(reject, before, counts)
            val within = twTree(twRoot("R", "lim-type"), notes = listOf(twNote("root", "lim", "work", "x".repeat(10))))
            reject
                .callIn(ToolProfile.ORCHESTRATION, within)
                .envelope.body
                .okResult()
            assertEquals(1, reject.all().size, "control: exactly maxLength is accepted in reject mode")
        }

    @Test
    fun `S9 note policy - warn mode stores the over-long note and the answered note carries a warning`(): Unit =
        runBlocking {
            val warn = kit(limConfig.replace("%MODE%", "warn"))
            val ok =
                warn
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(twRoot("R", "lim-type"), notes = listOf(twNote("root", "lim", "work", "x".repeat(11))))
                    ).envelope.body
                    .okResult()
            // Arbitration: the frozen oracle names `warning?` on the note entry without a shape (the 3.x tool answered a message), so only
            // its presence is asserted, with a within-limit control that carries none.
            val entry =
                ok
                    .getValue("notes")
                    .jsonArray
                    .single()
                    .jsonObject
            assertTrue("warning" in entry, "an over-maxLength note carries a warning member: $entry")
            val within =
                warn
                    .callIn(
                        ToolProfile.ORCHESTRATION,
                        twTree(twRoot("R2", "lim-type"), notes = listOf(twNote("root", "lim", "work", "x".repeat(10))))
                    ).envelope.body
                    .okResult()
            assertFalse(
                "warning" in
                    within
                        .getValue("notes")
                        .jsonArray
                        .single()
                        .jsonObject,
                "control: exactly maxLength carries no warning"
            )
            assertEquals(
                11,
                warn
                    .notesOf(ok.refId("root"))
                    .single()
                    .body.length,
                "warn mode still stores"
            )
        }

    @Test
    fun `S9 note policy - over 65536 bytes is payload_too_large, a declared key with the wrong role is schema_violation`(): Unit =
        runBlocking {
            val k = kit()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val huge = twTree(twRoot("R"), listOf(twChild("c")), notes = listOf(twNote("c", "k", "work", "a".repeat(65537))))
            assertEquals(
                ErrorCode.PAYLOAD_TOO_LARGE.wire,
                k
                    .callIn(ToolProfile.ORCHESTRATION, huge)
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            val wrongRole = twTree(twRoot("R", "feature-task"), listOf(twChild("c")), notes = listOf(twNote("root", "spec", "work", "x")))
            assertEquals(
                "schema_violation",
                k
                    .callIn(ToolProfile.ORCHESTRATION, wrongRole)
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertUntouched(k, before, counts)
            k
                .callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(twRoot("R"), listOf(twChild("c")), notes = listOf(twNote("c", "k", "work", "a".repeat(65536))))
                ).envelope.body
                .okResult()
            k
                .callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(twRoot("R", "feature-task"), notes = listOf(twNote("root", "spec", "queue", "x")))
                ).envelope.body
                .okResult()
            assertEquals(3, k.all().size, "control: exactly 65536 bytes and the declared role both write")
        }

    // ------------------------------------------------------------------ S16 batch semantics

    @Test
    fun `S16 non-atomic batch - the failing tree rolls back alone, the earlier commits, atomic rolls both back with the index`(): Unit =
        runBlocking {
            val k = kit()
            val good = twTree(twRoot("Good"), listOf(twChild("a"), twChild("b", "a")))
            val badCycle = twTree(twRoot("Bad"), listOf(twChild("a"), twChild("b")), listOf(twDep("a", "b"), twDep("b", "a")))
            val before = k.snapshot()
            val counts = k.tableCounts()

            val atomic = k.callIn(ToolProfile.ORCHESTRATION, good, badCycle, atomic = true)
            assertEquals(409, atomic.envelope.httpStatus)
            val whole = atomic.envelope.body.wholeError()
            assertEquals("cycle_detected", whole.str("code"))
            assertEquals("1", whole.str("index"))
            assertUntouched(k, before, counts)

            val loose = k.callIn(ToolProfile.ORCHESTRATION, good, badCycle)
            assertEquals(207, loose.envelope.httpStatus)
            assertEquals("""{"ok":1,"failed":1}""", loose.envelope.body.summary())
            assertEquals(
                listOf("Good", "Title a", "Title b"),
                k.titles(),
                "control: without atomic the first tree commits and the cyclic one does not"
            )
            assertEquals(
                "cycle_detected",
                loose.envelope.body
                    .errorAt(1)
                    .str("code")
            )
        }

    @Test
    fun `S16 an atomic batch rolls the adoption and the derived links back with the tree when a later element fails`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val ghost = UUID.fromString("11111111-2222-4333-8444-555555555555")
            val r = k.callIn(ToolProfile.ORCHESTRATION, docTree(project), deleteOp(ghost), atomic = true)
            assertEquals(
                "not_found",
                r.envelope.body
                    .wholeError()
                    .str("code")
            )
            assertEquals(
                "1",
                r.envelope.body
                    .wholeError()
                    .str("index")
            )
            assertUntouched(k, before, counts)
            val doc =
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                )
            assertEquals(PlanDocumentStatus.PENDING, doc.status, "the adoption was inside the unit and rolled back")
            assertNull(doc.adoptedByItemId)
            k
                .callIn(ToolProfile.ORCHESTRATION, docTree(project), deleteOp(ghost))
                .envelope.body
                .okResult(0)
            assertEquals(
                PlanDocumentStatus.ADOPTED,
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(project, "my-plan")
                ).status,
                "control: non-atomic commits the tree"
            )
            assertEquals(
                counts.getValue("links") + 4,
                k.tableCounts().getValue("links"),
                "three derived_from links plus one adopted_by link"
            )
        }

    // ------------------------------------------------------------------ S17 idempotency

    @Test
    fun `S17 a repeated idempotencyKey replays the first tree with no second copy, a different payload is idempotency_mismatch`(): Unit =
        runBlocking {
            val k = kit()
            val keyed = twTree(twRoot("Keyed"), listOf(twChild("a")), extra = listOf("idempotencyKey" to jstr("tree-key-1")))
            val first =
                k
                    .callIn(ToolProfile.ORCHESTRATION, keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()
            assertNull(first["replayed"], "the first execution is not a replay")
            val counts = k.tableCounts()
            val again =
                k
                    .callIn(ToolProfile.ORCHESTRATION, keyed, actor = IW_ACTOR)
                    .envelope.body
                    .okResult()
            assertEquals("true", again.str("replayed"))
            assertEquals(first.str("id"), again.str("id"), "the replay answers the first root id")
            assertEquals(counts, k.tableCounts(), "no second tree")
            assertEquals(2, k.all().size)
            val other = twTree(twRoot("Other"), listOf(twChild("a")), extra = listOf("idempotencyKey" to jstr("tree-key-1")))
            assertEquals(
                "idempotency_mismatch",
                k
                    .callIn(ToolProfile.ORCHESTRATION, other, actor = IW_ACTOR)
                    .envelope.body
                    .errorAt()
                    .str("code")
            )
            assertEquals(counts, k.tableCounts())
        }

    // ------------------------------------------------------------------ S18 events

    @Test
    fun `S18 a tree emits item created, dependency added, note upserted and document adopted rows carrying the lifted actor`(): Unit =
        runBlocking {
            val k = kit()
            val project = planProject(k)
            val (ok, rows) =
                k.rig.written {
                    k
                        .callIn(
                            ToolProfile.ORCHESTRATION,
                            twTree(
                                twRoot(
                                    "Feature X",
                                    extra =
                                        listOf(
                                            "noteAnchors" to JsonArray(listOf(twAnchor("requirements", "queue", "overview")))
                                        )
                                ),
                                listOf(twChild("c1"), twChild("c2", "c1")),
                                listOf(twDep("c1", "c2")),
                                listOf(twNote("c1", "k1", "work", "hello")),
                                extra =
                                    listOf(
                                        "parentId" to jstr(project.toString()),
                                        "docRef" to buildJsonObject { put("slug", "my-plan") }
                                    )
                            ),
                            actor = IW_ACTOR
                        ).envelope.body
                        .okResult()
                }

            fun of(type: String) = rows.filter { it.type == type }
            assertEquals(3, of("item.created").size)
            assertEquals(setOf(ok.refId("root"), ok.refId("c1"), ok.refId("c2")), of("item.created").map { it.entityId }.toSet())
            assertEquals(1, of("dependency.added").size)
            assertEquals(2, of("note.upserted").size, "the explicit note and the anchored note")
            assertEquals(1, of("document.adopted").size)
            for (type in listOf("item.created", "dependency.added", "note.upserted", "document.adopted")) {
                for (row in of(type)) {
                    assertEquals("agent-1", row.principalId, "$type principal id")
                    assertEquals("subagent", row.principalKind, "$type principal kind")
                }
            }
        }

    @Test
    fun `S18 a rolled-back tree emits no event row, and without an actor the control tree records no principal`(): Unit =
        runBlocking {
            val k = kit()
            val kids = listOf(twChild("c1"), twChild("c2"))
            val (_, failedRows) =
                k.rig.written {
                    k
                        .callIn(
                            ToolProfile.ORCHESTRATION,
                            twTree(twRoot("R"), kids, listOf(twDep("c1", "c2"), twDep("c2", "c1"))),
                            actor = IW_ACTOR
                        ).envelope.body
                        .errorAt()
                }
            assertEquals(emptyList(), failedRows.map { it.type }, "a failed element writes no event")
            val (_, okRows) =
                k.rig.written {
                    k
                        .callIn(ToolProfile.ORCHESTRATION, twTree(twRoot("R"), kids, listOf(twDep("c1", "c2"))))
                        .envelope.body
                        .okResult()
                }
            assertEquals(3, okRows.count { it.type == "item.created" }, "control: the same shape without the cycle emits")
            assertNull(
                okRows
                    .first {
                        it.type == "item.created"
                    }.principalId,
                "an anonymous call records no principal, so the actor assertion can fail"
            )
        }

    // ------------------------------------------------------------------ S12 scope

    @Test
    fun `S12 root scope - a parentId outside the listed root is forbidden root for every spelling, an in-scope parent writes`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val tree = { parent: String -> twTree(twRoot("Sneak"), listOf(twChild("c")), extra = listOf("parentId" to jstr(parent))) }
            for (ref in listOf(r2.toString(), r2.toString().take(8), r2.toString().uppercase(), d2.toString().take(8))) {
                assertForbidden(k.callScoped(rootScoped, tree(ref)).errorAt(), "root", r1.toString())
            }
            assertUntouched(k, before, counts)
            k.callScoped(rootScoped, tree(c1.toString().take(8))).okResult()
            assertEquals(
                counts.getValue("work_items") + 2,
                k.tableCounts().getValue("work_items"),
                "control: an in-scope parent takes the tree"
            )
        }

    @Test
    fun `S12 root scope - attaching outside the listed root is forbidden root for every spelling, attaching inside writes`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val attach = { id: String -> twTree(buildJsonObject { put("id", id) }, listOf(twChild("c"))) }
            for (ref in listOf(r2.toString(), r2.toString().take(8), r2.toString().uppercase(), d2.toString())) {
                assertForbidden(k.callScoped(rootScoped, attach(ref)).errorAt(), "root", r1.toString())
            }
            assertUntouched(k, before, counts)
            val ok = k.callScoped(rootScoped, attach(c2.toString().take(8))).okResult()
            assertEquals(c2, assertNotNull(k.item(ok.refId("c"))).parentId, "control: attach inside the root writes")
        }

    @Test
    fun `S12 root scope - a top-level root create is forbidden root, a root under an in-scope parent is allowed`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertForbidden(k.callScoped(rootScoped, twTree(twRoot("NewRoot"), listOf(twChild("c")))).errorAt(), "root", r1.toString())
            assertUntouched(k, before, counts)
            k
                .callScoped(
                    rootScoped,
                    twTree(twRoot("Under"), listOf(twChild("c")), extra = listOf("parentId" to jstr(r1.toString())))
                ).okResult()
            assertEquals(counts.getValue("work_items") + 2, k.tableCounts().getValue("work_items"))
        }

    @Test
    fun `S12 root scope - an unknown parentId is forbidden for a restricted caller but not_found for an unrestricted one`(): Unit =
        runBlocking {
            val k = seeded()
            val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")
            val tree = twTree(twRoot("T"), extra = listOf("parentId" to jstr(ghost.toString())))
            assertForbidden(k.callScoped(rootScoped, tree).errorAt(), "root", r1.toString())
            assertEquals(
                "not_found",
                k
                    .callIn(ToolProfile.ORCHESTRATION, tree)
                    .envelope.body
                    .errorAt()
                    .str("code"),
                "control: the unrestricted path reports not_found"
            )
        }

    @Test
    fun `S12 root scope - a foreign docRef rootId is rejected with no effect and the document stays unadopted`(): Unit =
        runBlocking {
            val k = seeded()
            k.rig.raw
                .planDocumentRepository()
                .stash(r2, "my-plan", planBody)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val tree =
                twTree(
                    twRoot("T"),
                    extra =
                        listOf(
                            "parentId" to jstr(c1.toString()),
                            "docRef" to
                                buildJsonObject {
                                    put("slug", "my-plan")
                                    put("rootId", r2.toString())
                                }
                        )
                )
            k.callScoped(rootScoped, tree).errorAt()
            assertUntouched(k, before, counts)
            val doc =
                assertNotNull(
                    k.rig.raw
                        .planDocumentRepository()
                        .get(r2, "my-plan")
                )
            assertEquals(PlanDocumentStatus.PENDING, doc.status)
            assertNull(doc.adoptedByItemId)
        }

    @Test
    fun `S12 root scope - a foreign docRef rootId is invalid_request on docRef rootId (B4 step 3)`(): Unit =
        runBlocking {
            val k = seeded()
            k.rig.raw
                .planDocumentRepository()
                .stash(r2, "my-plan", planBody)
            val before = k.snapshot()
            val counts = k.tableCounts()
            val tree =
                twTree(
                    twRoot("T"),
                    extra =
                        listOf(
                            "parentId" to jstr(c1.toString()),
                            "docRef" to
                                buildJsonObject {
                                    put("slug", "my-plan")
                                    put("rootId", r2.toString())
                                }
                        )
                )
            val err = k.callScoped(rootScoped, tree).errorAt()
            assertField(err, "/docRef/rootId")
            assertUntouched(k, before, counts)
        }

    @Test
    fun `S12 tag scope - a root create needs a listed tag in tags, a parent is judged on its own tags`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertForbidden(
                k.callScoped(tagScoped, twTree(twRoot("NoTag", extra = listOf("tags" to JsonArray(emptyList()))))).errorAt(),
                "tag",
                "alpha"
            )
            assertForbidden(k.callScoped(tagScoped, twTree(twRoot("NoTagAtAll"))).errorAt(), "tag", "alpha")
            assertForbidden(
                k.callScoped(tagScoped, twTree(twRoot("Beta", extra = listOf("tags" to strings("beta"))))).errorAt(),
                "tag",
                "alpha"
            )
            assertForbidden(
                k.callScoped(tagScoped, twTree(twRoot("Under"), extra = listOf("parentId" to jstr(r2.toString())))).errorAt(),
                "tag",
                "alpha"
            )
            assertForbidden(
                k.callScoped(tagScoped, twTree(buildJsonObject { put("id", d2.toString()) }, listOf(twChild("c")))).errorAt(),
                "tag",
                "alpha"
            )
            assertUntouched(k, before, counts)
            k.callScoped(tagScoped, twTree(twRoot("Alpha", extra = listOf("tags" to strings("alpha"))), listOf(twChild("c")))).okResult()
            k.callScoped(tagScoped, twTree(twRoot("Under"), extra = listOf("parentId" to jstr(c1.toString())))).okResult()
            assertEquals(
                counts.getValue("work_items") + 3,
                k.tableCounts().getValue("work_items"),
                "control: a listed tag and an in-scope parent write"
            )
        }

    // ------------------------------------------------------------------ S13 capability

    @Test
    fun `S13 a read-only token is forbidden capability write-items on create_tree and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertForbidden(k.callScoped(readOnly, twTree(twRoot("T"), listOf(twChild("c")))).errorAt(), "capability", "write-items")
            assertUntouched(k, before, counts)
            k.callScoped(ParityToken("tw-write", writeCaps), twTree(twRoot("T"), listOf(twChild("c")))).okResult()
            assertEquals(counts.getValue("work_items") + 2, k.tableCounts().getValue("work_items"), "control: a write-items token writes")
        }

    // ------------------------------------------------------------------ S19 registration

    @Test
    fun `S19 items_write lists create_tree and complete_tree, the knowledge profile hides both (amendment U23)`() {
        val spec = assertNotNull(ToolRegistry(buildToolSpecs())["items_write"])
        assertTrue(spec is OpsToolSpec<*>)
        // Q24 (934b25e4) adds repin after the tree ops.
        assertEquals(listOf("create", "update", "delete", "create_tree", "complete_tree", "repin"), spec.ops.map { it.name })
        val knowledge = ToolSchemaRenderer.inputSchema(spec, ToolProfile.KNOWLEDGE).toString()
        val orchestration = ToolSchemaRenderer.inputSchema(spec, ToolProfile.ORCHESTRATION).toString()
        assertTrue("create_tree" !in knowledge, "knowledge hides create_tree")
        assertTrue("complete_tree" !in knowledge, "knowledge hides complete_tree")
        assertTrue("create_tree" in orchestration && "complete_tree" in orchestration, "orchestration shows both")
    }

    @Test
    fun `S19 a batch of tree ops is a normal envelope - one result per op, summary counts, distinct ids`(): Unit =
        runBlocking {
            val k = kit()
            val r =
                k.callIn(
                    ToolProfile.ORCHESTRATION,
                    twTree(twRoot("A")),
                    twTree(twRoot("B")),
                    twTree(twRoot("C"), listOf(twChild("x", "nope")))
                )
            assertEquals(
                3,
                r.envelope.body
                    .results()
                    .size
            )
            assertEquals("""{"ok":2,"failed":1}""", r.envelope.body.summary())
            assertEquals(listOf("A", "B"), k.titles())
            assertNotEquals(
                r.envelope.body
                    .okResult(0)
                    .str("id"),
                r.envelope.body
                    .okResult(1)
                    .str("id")
            )
        }
}
