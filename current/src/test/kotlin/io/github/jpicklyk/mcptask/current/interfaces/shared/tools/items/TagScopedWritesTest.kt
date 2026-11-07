package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.test.TEST_ITEM_TYPE
import io.github.jpicklyk.mcptask.current.test.createOp
import io.github.jpicklyk.mcptask.current.test.errorAt
import io.github.jpicklyk.mcptask.current.test.okId
import io.github.jpicklyk.mcptask.current.test.okResult
import io.github.jpicklyk.mcptask.current.test.results
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.strings
import io.github.jpicklyk.mcptask.current.test.updateOp
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Independent tests (test-author seat, item b08a798a) for the tag half of scope on the tags a write PRODUCES, scenarios S1 to
 * S11 and S13 to S15 of the frozen test-plan (all EXISTING-SURFACE: a plain revert of the fix flips S2 to S8, S10 and S13 to S15),
 * through a real MCP client/server pair whose access seam carries the token's principal, scope and capabilities.
 *
 * Oracle (frozen rule): for a write by a tag-scoped principal, foreign = (old tags symmetric-difference new tags) minus the listed
 * tags; the write is refused when foreign is non-empty OR the resulting tags carry no listed tag. The refusal is the element
 * error `forbidden{scope: tag, required}` with `required` = the listed tags sorted and joined by `,` (api-rest.md section 3 error
 * shape), and it precedes any write, so the database snapshot is unchanged. An update that omits `tags` keeps them and needs no
 * tag check. A create or create_tree row starts from no tags. A root-scoped or unrestricted caller has no tag rule. Each refusal
 * assertion is paired with a control through the same call shape that DOES write.
 *
 * Fixture: R1 (alpha) > C1 (alpha,shared), C2 (alpha); R2 (beta) > D2 (beta). Seeded through ItemCommandService.
 *
 * Probes: empty vs absent vs [] tags (S4, S6, S11); duplicates and order (S10, S5); a comma inside one wire element (S13);
 * replay (S15). Uppercase tags are rejected by the domain as invalid_request, so that probe is not applicable.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class TagScopedWritesTest {
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
    private val tokenA = ParityToken("tsw-alpha", caps, tagsInclude = setOf("alpha"))
    private val tokenAG = ParityToken("tsw-alpha-gamma", caps, tagsInclude = setOf("gamma", "alpha"))
    private val tokenAdmin = ParityToken("tsw-admin", caps + ApiCapability.ADMIN, tagsInclude = setOf("alpha"))
    private val tokenRoot = ParityToken("tsw-root", caps, rootIds = setOf(r1))

    private suspend fun seeded(): IwKit {
        val k = IwKit(EventLogRig.build(db.db, dir, IW_CONFIG))
        val root1 = k.make("R1", tags = "alpha", id = r1)
        k.make("C1", root1, tags = "alpha,shared", id = c1)
        k.make("C2", root1, tags = "alpha", id = c2)
        val root2 = k.make("R2", tags = "beta", id = r2)
        k.make("D2", root2, tags = "beta", id = d2)
        return k
    }

    private fun retag(
        id: UUID,
        vararg tags: String
    ): JsonObject = updateOp(id, "tags" to strings(*tags))

    private fun child(
        title: String,
        vararg tags: String,
        parent: UUID = c1
    ): JsonObject = createOp(title, parent, TEST_ITEM_TYPE, "tags" to strings(*tags))

    /** A create_tree under [parent]; a null tag list leaves `tags` out of that row. */
    private fun tree(
        rootTags: Array<String>?,
        childTags: Array<String>?,
        parent: UUID = c1
    ): JsonObject =
        twTree(
            twRoot("TreeRoot", extra = rootTags?.let { listOf("tags" to strings(*it)) } ?: emptyList()),
            listOf(twChild("c", extra = childTags?.let { listOf("tags" to strings(*it)) } ?: emptyList())),
            extra = listOf("parentId" to jstr(parent.toString()))
        )

    private suspend fun IwKit.tagsOf(id: UUID): String? = assertNotNull(item(id)).tags

    // ------------------------------------------------------------------ S1 happy

    @Test
    fun `S1 in-scope tag writes are admitted for update, create and create_tree`(): Unit =
        runBlocking {
            val k = seeded()
            k.callScoped(tokenA, retag(c2, "alpha")).okResult()
            assertEquals("alpha", k.tagsOf(c2))

            k.callScoped(tokenAG, retag(c2, "alpha", "gamma")).okResult()
            assertEquals("alpha,gamma", k.tagsOf(c2), "a second listed tag is admitted for a token that lists both")

            val created = k.callScoped(tokenA, child("Kid", "alpha")).okId()
            assertEquals("alpha", k.tagsOf(created))
            assertEquals(c1, assertNotNull(k.item(created)).parentId)

            val before = k.all().size
            k.callScoped(tokenA, tree(arrayOf("alpha"), arrayOf("alpha"))).okResult()
            assertEquals(before + 2, k.all().size, "root and child of the tree were written")
            assertEquals("alpha", k.all().single { it.title == "TreeRoot" }.tags)
            assertEquals("alpha", k.all().single { it.title == "Title c" }.tags)
        }

    // ------------------------------------------------------------------ S2 retag away

    @Test
    fun `S2 a tag-scoped update that retags an item to another tenant is forbidden tag and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenA, retag(c1, "beta")).errorAt(), "tag", "alpha")
            assertEquals("alpha,shared", k.tagsOf(c1))
            assertEquals(before, k.snapshot())

            k.callScoped(tokenA, retag(c1, "alpha", "shared")).okResult()
            assertEquals("alpha,shared", k.tagsOf(c1), "control: an update that keeps the scope writes")
        }

    // ------------------------------------------------------------------ S3 create under parent for another tenant

    @Test
    fun `S3 a tag-scoped create whose tags are foreign or mixed with foreign is forbidden tag and writes no row`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val rows = k.all().size
            assertForbidden(k.callScoped(tokenA, child("Beta", "beta")).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, child("Mixed", "alpha", "beta")).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())
            assertEquals(rows, k.all().size)

            k.callScoped(tokenA, child("Fine", "alpha")).okId()
            assertEquals(rows + 1, k.all().size, "control: the same call shape with a listed tag writes")
        }

    // ------------------------------------------------------------------ S4 untagged child

    @Test
    fun `S4 a tag-scoped create without tags is forbidden tag, whether tags is absent or empty`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenA, createOp("Absent", c1)).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, child("Empty")).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())

            val ok = k.callScoped(tokenA, child("Tagged", "alpha")).okId()
            assertEquals("alpha", k.tagsOf(ok), "control: a child carrying the listed tag writes")
        }

    // ------------------------------------------------------------------ S5 dropping a foreign tag

    @Test
    fun `S5 a tag-scoped update may not drop a foreign tag, but may resend the tags unchanged`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenA, retag(c1, "alpha")).errorAt(), "tag", "alpha")
            assertEquals("alpha,shared", k.tagsOf(c1))
            assertEquals(before, k.snapshot())

            k.callScoped(tokenA, retag(c1, "alpha", "shared")).okResult()
            assertEquals("alpha,shared", k.tagsOf(c1), "control: the unchanged set is admitted")
            k.callScoped(tokenA, retag(c1, "shared", "alpha")).okResult()
            assertEquals("shared,alpha", k.tagsOf(c1), "control: only the order changed, which is not content")
        }

    // ------------------------------------------------------------------ S6 clearing tags

    @Test
    fun `S6 a tag-scoped update with an empty tags list is forbidden tag and keeps the tags`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenA, updateOp(c2, "tags" to strings())).errorAt(), "tag", "alpha")
            assertEquals("alpha", k.tagsOf(c2))
            assertEquals(before, k.snapshot())

            k.callScoped(tokenA, retag(c2, "alpha")).okResult()
            assertEquals("alpha", k.tagsOf(c2), "control: a non-empty listed set writes")
        }

    // ------------------------------------------------------------------ S7 create_tree rows

    @Test
    fun `S7 a tag-scoped create_tree with a foreign or missing tag on any row is forbidden tag and writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            assertForbidden(k.callScoped(tokenA, tree(arrayOf("alpha"), arrayOf("beta"))).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, tree(arrayOf("beta"), arrayOf("alpha"))).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, tree(null, arrayOf("alpha"))).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, tree(arrayOf("alpha"), null)).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())
            assertEquals(counts, k.tableCounts())

            k.callScoped(tokenA, tree(arrayOf("alpha"), arrayOf("alpha"))).okResult()
            assertEquals(counts.getValue("work_items") + 2, k.tableCounts().getValue("work_items"), "control: both rows tagged writes")
        }

    // ------------------------------------------------------------------ S8 attach root

    @Test
    fun `S8 a tag-scoped create_tree attached to a listed item still needs a listed tag on every child`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val counts = k.tableCounts()
            val attach = buildJsonObject { put("id", c1.toString()) }
            val untagged = twTree(attach, listOf(twChild("c")))
            assertForbidden(k.callScoped(tokenA, untagged).errorAt(), "tag", "alpha")
            val foreign = twTree(attach, listOf(twChild("c", extra = listOf("tags" to strings("beta")))))
            assertForbidden(k.callScoped(tokenA, foreign).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())
            assertEquals(counts, k.tableCounts())

            val ok = twTree(attach, listOf(twChild("c", extra = listOf("tags" to strings("alpha")))))
            k.callScoped(tokenA, ok).okResult()
            assertEquals(counts.getValue("work_items") + 1, k.tableCounts().getValue("work_items"), "control: a tagged child attaches")
        }

    // ------------------------------------------------------------------ S9 who is exempt

    @Test
    fun `S9 a root-scoped and an unrestricted caller write the tags a tag scope refuses, an admin with a tag scope does not`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenAdmin, retag(c1, "beta")).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenAdmin, child("AdminKid", "beta")).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot(), "ADMIN does not lift the tag rule")

            k.callScoped(tokenRoot, retag(c1, "beta")).okResult()
            assertEquals("beta", k.tagsOf(c1), "a root-scoped token has no tag rule (update)")
            val tagged = k.callScoped(tokenRoot, child("RootBeta", "beta", parent = c2)).okId()
            assertEquals("beta", k.tagsOf(tagged), "a root-scoped token has no tag rule (create with a foreign tag)")
            val bare = k.callScoped(tokenRoot, createOp("RootBare", c2)).okId()
            assertNull(k.tagsOf(bare), "a root-scoped token has no tag rule (create without tags)")

            k
                .call(retag(c2, "beta"))
                .envelope.body
                .okResult()
            assertEquals("beta", k.tagsOf(c2), "an unrestricted caller has no tag rule (update)")
            val open =
                k
                    .call(createOp("OpenBare", c1))
                    .envelope.body
                    .okId()
            assertNull(k.tagsOf(open), "an unrestricted caller has no tag rule (create without tags)")
        }

    // ------------------------------------------------------------------ S10 required lists every listed tag

    @Test
    fun `S10 the refusal names every listed tag, sorted`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenAG, retag(c1, "beta")).errorAt(), "tag", "alpha,gamma")
            assertForbidden(k.callScoped(tokenAG, child("Kid", "beta")).errorAt(), "tag", "alpha,gamma")
            assertEquals(before, k.snapshot())

            k.callScoped(tokenAG, retag(c1, "gamma", "alpha", "shared")).okResult()
            assertEquals("gamma,alpha,shared", k.tagsOf(c1), "control: listed tags in any order plus the kept foreign tag")
        }

    // ------------------------------------------------------------------ S11 update without tags

    @Test
    fun `S11 a tag-scoped update that does not send tags keeps them and is admitted`(): Unit =
        runBlocking {
            val k = seeded()
            k.callScoped(tokenA, updateOp(c1, "title" to jstr("renamed"))).okResult()
            assertEquals("renamed", assertNotNull(k.item(c1)).title)
            assertEquals("alpha,shared", k.tagsOf(c1), "tags are unchanged")
        }

    // ------------------------------------------------------------------ S13 comma probe

    @Test
    fun `S13 a comma inside one tags element is two tags, so a foreign one is forbidden tag`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            assertForbidden(k.callScoped(tokenA, child("Comma", "alpha,beta")).errorAt(), "tag", "alpha")
            assertForbidden(k.callScoped(tokenA, retag(c2, "alpha,beta")).errorAt(), "tag", "alpha")
            assertEquals(before, k.snapshot())

            k.callScoped(tokenA, child("CommaOk", "alpha")).okId()
            assertEquals(before.getValue("work_items").size + 1, k.snapshot().getValue("work_items").size, "control: one listed tag writes")
        }

    // ------------------------------------------------------------------ S14 batches

    @Test
    fun `S14 a forbidden tag write does not stop its neighbours, and atomic rolls everything back with the failing index`(): Unit =
        runBlocking {
            val k = seeded()
            val mixed =
                k.callScoped(
                    tokenA,
                    updateOp(c2, "title" to jstr("first")),
                    retag(c1, "beta"),
                    updateOp(c2, "title" to jstr("third"))
                )
            assertEquals("partial_failure", mixed.wholeError().str("code"))
            assertEquals("""{"ok":2,"failed":1}""", mixed.summary())
            assertEquals(3, mixed.results().size)
            assertForbidden(mixed.errorAt(1), "tag", "alpha")
            assertEquals("third", assertNotNull(k.item(c2)).title)
            assertEquals("alpha,shared", k.tagsOf(c1))

            val atomic =
                k.callScoped(
                    tokenA,
                    updateOp(c2, "title" to jstr("atom-one")),
                    retag(c1, "beta"),
                    atomic = true
                )
            val err = atomic.wholeError()
            assertEquals("forbidden", err.str("code"))
            assertEquals("1", err.str("index"))
            assertEquals("third", assertNotNull(k.item(c2)).title, "the in-scope element of the atomic batch was rolled back")
        }

    // ------------------------------------------------------------------ S15 replay

    @Test
    fun `S15 sending the same forbidden retag twice gives the same refusal and still writes nothing`(): Unit =
        runBlocking {
            val k = seeded()
            val before = k.snapshot()
            val first = k.callScoped(tokenA, retag(c1, "beta")).errorAt()
            val second = k.callScoped(tokenA, retag(c1, "beta")).errorAt()
            assertForbidden(first, "tag", "alpha")
            assertForbidden(second, "tag", "alpha")
            assertEquals(before, k.snapshot())
            assertEquals("alpha,shared", k.tagsOf(c1))
        }
}
