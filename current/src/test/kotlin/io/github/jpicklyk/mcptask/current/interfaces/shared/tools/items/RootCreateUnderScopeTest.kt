package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item 754ae3dc, run r-202610132225-754ae3dc) for the root-write rule of `items_write`:
 * scenarios S8, S9 and S10 of the frozen test-plan (all EXISTING-SURFACE: the ops and the tokens exist before the fix, the
 * outcome flips), through a real MCP client/server pair whose access seam carries the token's principal, scope and capabilities.
 *
 * Oracles (task-scope item 6 and decisions D4, D5; never read from a run): under a restricted scope a depth-0 `create` (no
 * `parentId`), a `create_tree` that starts a new top-level root, and an `update` that moves a non-root item to root
 * (`clear: ["parentId"]`) are `forbidden{scope: root}` unless the caller is unrestricted or holds ADMIN. `required` is the sorted
 * listed root ids joined by a comma, or the empty string under a tag-only scope (D4). The old rules "a root create with a listed
 * tag is allowed" and "a listed root may clear its parentId" are deleted. `clear: ["parentId"]` on an item that already is
 * depth-0 stays a no-op (D5). A reparent is still judged on the NEW parent (api-rest.md: reparent rule unchanged, S10). Every
 * refusal precedes any write: the database snapshot is unchanged. Each forbidden assertion is paired with a control through the
 * same call shape that DOES write (ADMIN, unrestricted, or an in-scope parent).
 *
 * Fixture: R1 (tag alpha) with children C1 (alpha,shared) and C2 (alpha); R2 (tag beta) with child D2 (beta); fixed ids so the
 * tokens can list them; seeded through ItemCommandService (never the tool under test), so roots are stamped `root_id = id`.
 * Sorted order of the ids as text: R1 < C1 < C2 < R2.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class RootCreateUnderScopeTest {
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

    private val caps = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS)
    private val adminCaps = caps + ApiCapability.ADMIN
    private val rootScoped = ParityToken("rc-root", caps, rootIds = setOf(r1))
    private val rootScopedWithC1 = ParityToken("rc-root-c1", caps, rootIds = setOf(r1, c1))
    private val tagScoped = ParityToken("rc-tag", caps, tagsInclude = setOf("alpha"))
    private val rootAndTagScoped = ParityToken("rc-root-tag", caps, rootIds = setOf(r1), tagsInclude = setOf("alpha"))
    private val adminRootScoped = ParityToken("rc-admin-root", adminCaps, rootIds = setOf(r1))
    private val adminTagScoped = ParityToken("rc-admin-tag", adminCaps, tagsInclude = setOf("alpha"))

    private suspend fun seeded(): IwKit {
        val k = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))
        val root1 = k.make("R1", tags = "alpha", id = r1)
        k.make("C1", root1, tags = "alpha,shared", id = c1)
        k.make("C2", root1, tags = "alpha", id = c2)
        val root2 = k.make("R2", tags = "beta", id = r2)
        k.make("D2", root2, tags = "beta", id = d2)
        return k
    }

    private fun rootCreate(
        title: String,
        vararg tags: String
    ): JsonObject = if (tags.isEmpty()) createOp(title) else JsonObject(createOp(title) + ("tags" to strings(*tags)))

    private fun clearParent(id: UUID) = updateOp(id, "clear" to strings("parentId"))

    // ------------------------------------------------------------------ S8 create

    @Test
    fun `S8 a root-scoped token cannot create a depth-0 item, the refusal names the listed roots and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(rootScoped, rootCreate("NewRoot")).errorAt(), "root", r1.toString())
            assertForbidden(k.callScoped(rootScoped, rootCreate("NewRootTagged", "alpha")).errorAt(), "root", r1.toString())
            assertEquals(before, k.snapshot(), "no forbidden create wrote a row")
            assertEquals(5, k.all().size)

            // required is the sorted listed root ids, joined by a comma
            val two = ParityToken("rc-two", caps, rootIds = linkedSetOf(r2, r1))
            assertForbidden(k.callScoped(two, rootCreate("Two")).errorAt(), "root", "$r1,$r2")
            assertEquals(before, k.snapshot())

            // control: the same token creates under an in-scope parent
            val id = k.callScoped(rootScoped, createOp("Under", c1)).okId()
            assertEquals(c1, assertNotNull(k.item(id)).parentId)
        }

    @Test
    fun `S8 a tag-scoped token cannot create a depth-0 item even with a listed tag, required is the empty string`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tagScoped, rootCreate("Listed", "alpha")).errorAt(), "root", "")
            assertForbidden(k.callScoped(tagScoped, rootCreate("NoTags")).errorAt(), "root", "")
            assertForbidden(k.callScoped(tagScoped, rootCreate("MixedTags", "beta", "alpha")).errorAt(), "root", "")
            assertEquals(before, k.snapshot(), "no forbidden create wrote a row")
            assertEquals(5, k.all().size)

            // control: a parent carrying the tag still admits a child (judged on the parent)
            val id = k.callScoped(tagScoped, createOp("UnderC1", c1)).okId()
            assertEquals(c1, assertNotNull(k.item(id)).parentId)
        }

    @Test
    fun `S8 a token with both a root and a tag restriction is refused on the listed roots`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(rootAndTagScoped, rootCreate("Both", "alpha")).errorAt(), "root", r1.toString())
            assertEquals(before, k.snapshot())
            val id = k.callScoped(rootAndTagScoped, createOp("UnderC2", c2)).okId()
            assertEquals(c2, assertNotNull(k.item(id)).parentId, "control: an in-scope tagged parent admits the child")
        }

    @Test
    fun `S8 ADMIN and unrestricted callers still create a depth-0 item, whatever the scope`(): Unit =
        runBlocking {
            val k = seeded()
            val byAdminRoot = k.callScoped(adminRootScoped, rootCreate("AdminRoot")).okId()
            assertNull(assertNotNull(k.item(byAdminRoot)).parentId)
            val byAdminTag = k.callScoped(adminTagScoped, rootCreate("AdminTag", "alpha")).okId()
            assertNull(assertNotNull(k.item(byAdminTag)).parentId)
            val unrestricted =
                k
                    .call(rootCreate("Unrestricted"))
                    .envelope.body
                    .okId()
            assertNull(assertNotNull(k.item(unrestricted)).parentId)
            assertEquals(8, k.all().size)
            // a root created by the ADMIN is stamped as its own root (the one writer)
            assertEquals(byAdminRoot, assertNotNull(k.item(byAdminRoot)).rootId)
        }

    @Test
    fun `S8 create_tree starting a new top-level root is refused under a root scope and under a tag scope`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val plain = twTree(twRoot("NewTree"), listOf(twChild("c")))
            assertForbidden(k.callScoped(rootScoped, plain).errorAt(), "root", r1.toString())

            val tagged = twTree(twRoot("TaggedTree", extra = listOf("tags" to strings("alpha"))), listOf(twChild("c")))
            assertForbidden(k.callScoped(tagScoped, tagged).errorAt(), "root", "")
            assertForbidden(k.callScoped(rootScoped, tagged).errorAt(), "root", r1.toString())
            assertEquals(before, k.snapshot(), "no work_items, notes, dependencies or events row changed")
            assertEquals(counts, k.tableCounts(), "no row of any table was added")

            // controls: ADMIN with the same scope, an unrestricted caller, and a tree attached under an in-scope parent
            val adminId = k.callScoped(adminRootScoped, plain).okId()
            assertNull(assertNotNull(k.item(adminId)).parentId)
            val adminTagId = k.callScoped(adminTagScoped, tagged).okId()
            assertNull(assertNotNull(k.item(adminTagId)).parentId)
            val open = k.callIn(ToolProfile.ORCHESTRATION, plain)
            assertEquals(200, open.envelope.httpStatus)
            val under = twTree(twRoot("Under"), listOf(twChild("c")), extra = listOf("parentId" to jstr(r1.toString())))
            k.callScoped(rootScoped, under).okResult()
        }

    // ------------------------------------------------------------------ S9 clear parentId

    @Test
    fun `S9 clearing parentId of a depth-1 item is forbidden root under a root scope even when the item is listed`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(rootScoped, clearParent(c1)).errorAt(), "root", r1.toString())
            assertForbidden(k.callScoped(rootScopedWithC1, clearParent(c1)).errorAt(), "root", "$r1,$c1")
            assertEquals(before, k.snapshot(), "no forbidden move wrote a row")
            val kept = assertNotNull(k.item(c1))
            assertEquals(r1, kept.parentId, "C1 stays under R1")
            assertEquals(r1, kept.rootId)
        }

    @Test
    fun `S9 a tag-scoped token cannot move a tagged non-root item to root`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tagScoped, clearParent(c1)).errorAt(), "root", "")
            assertEquals(before, k.snapshot())
            assertEquals(r1, assertNotNull(k.item(c1)).parentId)
        }

    @Test
    fun `S9 ADMIN and unrestricted callers still move an item to root, which stamps it as its own root`(): Unit =
        runBlocking {
            val k = seeded()
            k.callScoped(adminRootScoped, clearParent(c1)).okResult()
            val moved = assertNotNull(k.item(c1))
            assertNull(moved.parentId)
            assertEquals(c1, moved.rootId, "an item moved to root becomes its own root")
            assertEquals(0, moved.depth)

            k
                .call(clearParent(c2))
                .envelope.body
                .okResult()
            val movedByOpen = assertNotNull(k.item(c2))
            assertNull(movedByOpen.parentId)
            assertEquals(c2, movedByOpen.rootId)
        }

    @Test
    fun `S9 clearing parentId on an item that is already depth-0 stays a no-op under a root scope`(): Unit =
        runBlocking {
            val k = seeded()
            k.callScoped(rootScoped, clearParent(r1)).okResult()
            val root = assertNotNull(k.item(r1))
            assertNull(root.parentId)
            assertEquals(r1, root.rootId)
            assertEquals(0, root.depth)
            assertEquals(
                setOf(c1, c2),
                k
                    .all()
                    .filter { it.parentId == r1 }
                    .map { it.id }
                    .toSet(),
                "R1 children are untouched"
            )
        }

    @Test
    fun `S9 replaying a refused move gives the same refusal and still writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            repeat(2) { assertForbidden(k.callScoped(rootScoped, clearParent(c2)).errorAt(), "root", r1.toString()) }
            assertEquals(before, k.snapshot())
        }

    // ------------------------------------------------------------------ S10 reparent is judged on the new parent

    @Test
    fun `S10 a reparent onto an in-scope parent moves, onto an out-of-scope parent it is forbidden root`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(rootScoped, updateOp(c1, "parentId" to jstr(d2.toString()))).errorAt(), "root", r1.toString())
            assertForbidden(k.callScoped(rootScoped, updateOp(c1, "parentId" to jstr(r2.toString()))).errorAt(), "root", r1.toString())
            assertEquals(before, k.snapshot())
            assertEquals(r1, assertNotNull(k.item(c1)).parentId)

            k.callScoped(rootScoped, updateOp(c2, "parentId" to jstr(c1.toString()))).okResult()
            val moved = assertNotNull(k.item(c2))
            assertEquals(c1, moved.parentId, "control: a reparent inside the root works")
            assertEquals(r1, moved.rootId, "and the item keeps its root")
            assertEquals(2, moved.depth)
        }
}
