package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.deleteOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 5462835d) for the scope rules of `items_write`: scenarios S12, S13 and S17 of the
 * frozen test-plan (NEW-SURFACE), through a real MCP client/server pair whose access seam carries the token's principal, scope and
 * capabilities (the same seam the parity harness uses).
 *
 * Oracles: task-scope AC5 and decision D2 (the 3.x REST rules): a write whose target item (update, delete) or parent (create under
 * a parent; a reparent, judged on the NEW parent) is outside the scope is an element `forbidden{scope, required}`; a root create
 * under a root scope is `forbidden{root}` (the new id can never be listed); a root create under a tag scope needs a listed tag in
 * `tags`, else `forbidden{tag}`; moving an item to root under a root scope is allowed only when the item's own id is listed.
 * `required` is the sorted listed root ids or tags joined by a comma (ScopeGuardTest); tags match case-sensitively on the item's own
 * tags; an unknown item under a restricted scope is `forbidden`, never `not_found` (no existence oracle). Every scope rejection
 * precedes any write: the database snapshot is unchanged. Each forbidden assertion is paired with an in-scope control through the
 * same call shape on the same fixture that DOES write.
 *
 * Fixture: R1 (tag alpha) with children C1 (alpha,shared) and C2 (alpha); R2 (tag beta) with child D2 (beta); fixed ids so the
 * tokens can list them. Seeded through ItemCommandService, never through the tool under test.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsWriteScopeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val r1 = UUID.fromString("a1000001-0000-4000-8000-000000000001")
    private val c1 = UUID.fromString("a1000002-0000-4000-8000-0000000000c1")
    private val c2 = UUID.fromString("a1000003-0000-4000-8000-0000000000c2")
    private val r2 = UUID.fromString("b2000001-0000-4000-8000-000000000002")
    private val d2 = UUID.fromString("b2000002-0000-4000-8000-0000000000d2")
    private val ghost = UUID.fromString("c3000000-0000-4000-8000-0000000000ee")

    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
    private val rootScoped = ParityToken("iw-root", caps, rootIds = setOf(r1))
    private val rootScopedWithC1 = ParityToken("iw-root-c1", caps, rootIds = setOf(r1, c1))
    private val tagScoped = ParityToken("iw-tag", caps, tagsInclude = setOf("alpha"))
    private val readOnly = ParityToken("iw-read", setOf(ApiCapability.READ), rootIds = null)

    private suspend fun seeded(): IwKit {
        val k = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))
        val root1 = k.make("R1", tags = "alpha", id = r1)
        k.make("C1", root1, tags = "alpha,shared", id = c1)
        k.make("C2", root1, tags = "alpha", id = c2)
        val root2 = k.make("R2", tags = "beta", id = r2)
        k.make("D2", root2, tags = "beta", id = d2)
        return k
    }

    private fun title(
        id: UUID,
        to: String
    ) = updateOp(id, "title" to jstr(to))

    // ------------------------------------------------------------------ S12 root scope

    @Test
    fun `S12 root scope - update and delete of an item outside the listed root are forbidden root and write nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val upd = k.callScoped(rootScoped, title(r2, "hacked")).errorAt()
            assertForbidden(upd, "root", r1.toString())
            val del = k.callScoped(rootScoped, deleteOp(r2, true)).errorAt()
            assertForbidden(del, "root", r1.toString())
            val delChild = k.callScoped(rootScoped, deleteOp(d2)).errorAt()
            assertForbidden(delChild, "root", r1.toString())
            assertEquals(before, k.snapshot(), "database unchanged by every forbidden element")
            assertEquals("R2", assertNotNull(k.item(r2)).title)

            k.callScoped(rootScoped, title(c1, "renamed")).okResult()
            assertEquals("renamed", assertNotNull(k.item(c1)).title, "control: the same call shape inside the root writes")
            assertNotEquals(before, k.snapshot())
            k.callScoped(rootScoped, deleteOp(c2)).okResult()
            assertNull(k.item(c2), "control: delete inside the root works")
        }

    @Test
    fun `S12 root scope - creating a root is forbidden root with the listed ids, creating under an in-scope parent is allowed`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val err = k.callScoped(rootScoped, createOp("NewRoot")).errorAt()
            assertForbidden(err, "root", r1.toString())
            assertEquals(before, k.snapshot())
            assertEquals(5, k.all().size)

            val id = k.callScoped(rootScoped, createOp("Under", c1)).okId()
            assertEquals(c1, assertNotNull(k.item(id)).parentId, "control: a child under an in-scope parent is created")
            assertEquals(6, k.all().size)
        }

    @Test
    fun `S12 root scope - creating under an out-of-scope parent is forbidden for every spelling of the parent id`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (ref in listOf(r2.toString(), r2.toString().take(8), r2.toString().uppercase(), d2.toString().take(8))) {
                val op = JsonObject(createOp("Sneak") + ("parentId" to jstr(ref)))
                assertForbidden(k.callScoped(rootScoped, op).errorAt(), "root", r1.toString())
            }
            assertEquals(before, k.snapshot(), "no row written for any spelling of the foreign parent")
            val ok = k.callScoped(rootScoped, JsonObject(createOp("Fine") + ("parentId" to jstr(r1.toString().take(8))))).okId()
            assertEquals(r1, assertNotNull(k.item(ok)).parentId, "control: an in-scope parent given as a prefix is accepted")
        }

    @Test
    fun `S12 root scope - a target given as upper-case UUID or 8-hex prefix is judged like the full id`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val upper = JsonObject(title(r2, "x") + ("id" to jstr(r2.toString().uppercase())))
            val prefixed = JsonObject(deleteOp(r2) + ("id" to jstr(r2.toString().take(8))))
            assertForbidden(k.callScoped(rootScoped, upper).errorAt(), "root", r1.toString())
            assertForbidden(k.callScoped(rootScoped, prefixed).errorAt(), "root", r1.toString())
            assertEquals(before, k.snapshot())
            val inScope = JsonObject(title(c1, "viaPrefix") + ("id" to jstr(c1.toString().take(8))))
            k.callScoped(rootScoped, inScope).okResult()
            assertEquals("viaPrefix", assertNotNull(k.item(c1)).title, "control: an in-scope prefix target writes")
        }

    @Test
    fun `S12 root scope - a reparent onto an out-of-scope parent is forbidden, onto an in-scope parent it moves`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val err = k.callScoped(rootScoped, updateOp(c1, "parentId" to jstr(r2.toString()))).errorAt()
            assertForbidden(err, "root", r1.toString())
            assertEquals(before, k.snapshot())
            assertEquals(r1, assertNotNull(k.item(c1)).parentId)

            k.callScoped(rootScoped, updateOp(c2, "parentId" to jstr(c1.toString()))).okResult()
            assertEquals(c1, assertNotNull(k.item(c2)).parentId, "control: a reparent inside the root works")
        }

    @Test
    fun `S12 root scope - clear parentId makes the item its own root, allowed only when its id is listed`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val err = k.callScoped(rootScoped, updateOp(c1, "clear" to strings("parentId"))).errorAt()
            assertForbidden(err, "root", r1.toString())
            assertEquals(before, k.snapshot())
            assertEquals(r1, assertNotNull(k.item(c1)).parentId, "C1 stays under R1")

            k.callScoped(rootScopedWithC1, updateOp(c1, "clear" to strings("parentId"))).okResult()
            val moved = assertNotNull(k.item(c1))
            assertNull(moved.parentId, "control: with C1 listed the move to root is allowed")
            assertEquals(c1, moved.rootId)
        }

    @Test
    fun `S12 root scope - an unknown item is forbidden for a restricted caller but not_found for an unrestricted one`(): Unit =
        runBlocking {
            val k = seeded()
            assertForbidden(k.callScoped(rootScoped, title(ghost, "x")).errorAt(), "root", r1.toString())
            assertForbidden(k.callScoped(rootScoped, deleteOp(ghost)).errorAt(), "root", r1.toString())
            assertEquals(
                "not_found",
                k
                    .call(title(ghost, "x"))
                    .envelope.body
                    .errorAt()
                    .str("code"),
                "control: the unrestricted path reports not_found"
            )
        }

    @Test
    fun `S12 a forbidden element does not stop its in-scope neighbours, and atomic rolls everything back with the failing index`(): Unit =
        runBlocking {
            val k = seeded()
            val mixed = k.callScoped(rootScoped, title(c1, "ok-one"), title(r2, "blocked"), title(c2, "ok-two"))
            assertEquals("partial_failure", mixed.wholeError().str("code"))
            assertEquals("""{"ok":2,"failed":1}""", mixed.summary())
            assertForbidden(mixed.errorAt(1), "root", r1.toString())
            assertEquals(3, mixed.results().size)
            assertEquals("ok-one", assertNotNull(k.item(c1)).title)
            assertEquals("ok-two", assertNotNull(k.item(c2)).title)
            assertEquals("R2", assertNotNull(k.item(r2)).title)

            val atomic = k.callScoped(rootScoped, title(c1, "atom-one"), title(r2, "blocked"), atomic = true)
            val err = atomic.wholeError()
            assertEquals("forbidden", err.str("code"))
            assertEquals("1", err.str("index"))
            assertEquals("ok-one", assertNotNull(k.item(c1)).title, "the in-scope element of the atomic batch was rolled back")
        }

    // ------------------------------------------------------------------ S12 / S17 tag scope

    @Test
    fun `S12 tag scope - update delete and reparent targets without a listed tag are forbidden tag`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tagScoped, title(r2, "x")).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tagScoped, deleteOp(d2)).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tagScoped, updateOp(c1, "parentId" to jstr(r2.toString()))).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())
            assertEquals(r1, assertNotNull(k.item(c1)).parentId)

            k.callScoped(tagScoped, title(r1, "alpha-renamed")).okResult()
            assertEquals("alpha-renamed", assertNotNull(k.item(r1)).title, "control: an item carrying the tag writes")
            k.callScoped(tagScoped, updateOp(c2, "parentId" to jstr(c1.toString()))).okResult()
            assertEquals(c1, assertNotNull(k.item(c2)).parentId, "control: a reparent onto a tagged parent works")
        }

    @Test
    fun `S17 tag scope - a root create needs a listed tag in tags`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val withoutTags = k.callScoped(tagScoped, createOp("NoTags")).errorAt()
            assertForbidden(withoutTags, "tag", "alpha")
            val emptyTags = k.callScoped(tagScoped, JsonObject(createOp("EmptyTags") + ("tags" to strings()))).errorAt()
            assertForbidden(emptyTags, "tag", "alpha")
            val otherTag = k.callScoped(tagScoped, JsonObject(createOp("Beta") + ("tags" to strings("beta")))).errorAt()
            assertForbidden(otherTag, "tag", "alpha")
            val upper = k.callScoped(tagScoped, JsonObject(createOp("Upper") + ("tags" to strings("ALPHA")))).errorAt()
            assertForbidden(upper, "tag", "alpha")
            assertEquals(before, k.snapshot(), "no forbidden create wrote a row")
            assertEquals(5, k.all().size)

            val listed = k.callScoped(tagScoped, JsonObject(createOp("Listed") + ("tags" to strings("alpha")))).okId()
            assertEquals("alpha", assertNotNull(k.item(listed)).tags, "control: tags [alpha] is accepted")
            val mixed = k.callScoped(tagScoped, JsonObject(createOp("Mixed") + ("tags" to strings("beta", "alpha")))).okId()
            assertEquals("beta,alpha", assertNotNull(k.item(mixed)).tags, "any listed tag among several admits")
        }

    @Test
    fun `S17 tag scope - creating under a parent is judged on the parent, not on the new tags`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tagScoped, createOp("Under", r2)).errorAt(), "tag", "alpha")
            assertForbidden(
                k.callScoped(tagScoped, JsonObject(createOp("Under2", r2) + ("tags" to strings("alpha")))).errorAt(),
                "tag",
                "alpha"
            )
            assertEquals(before, k.snapshot())
            val ok = k.callScoped(tagScoped, createOp("UnderC1", c1)).okId()
            assertEquals(c1, assertNotNull(k.item(ok)).parentId, "control: a parent carrying the tag admits an untagged child")
        }

    // ------------------------------------------------------------------ S13 capability

    @Test
    fun `S13 a read-only token is forbidden capability write-items on create update and delete and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            for (op in listOf(createOp("X"), title(c1, "x"), deleteOp(c2))) {
                assertForbidden(k.callScoped(readOnly, op).errorAt(), "capability", "write-items")
            }
            assertEquals(before, k.snapshot())
            val writer = ParityToken("iw-writer", caps)
            k.callScoped(writer, title(c1, "by-writer")).okResult()
            assertEquals("by-writer", assertNotNull(k.item(c1)).title, "control: a write-items token runs the same call")
        }
}
