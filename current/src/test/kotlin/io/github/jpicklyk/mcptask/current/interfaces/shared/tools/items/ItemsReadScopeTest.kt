package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for the scope behaviour of `items_read` get / list: scenarios S9, S10,
 * S11 (list), S12 and the scope probes of the frozen test-plan (NEW-SURFACE), through the production REST topology with a real
 * bearer principal whose scope is a root set or a tag set.
 *
 * Oracles: task-scope D2 (TARGET ids get `ScopeGuard.check` and `forbidden` precedes not_found / ifVersion; under a
 * restricted scope a missing id is `forbidden`, an ambiguous prefix keeps only the candidates the scope admits and is
 * `forbidden` for none, the single admitted id for one, `ambiguous_id` for two or more; FILTER ids are resolved the same
 * way but not guarded, the store scope drops out-of-scope rows), D3 (ancestors: a root scope drops ancestors above the first
 * listed root, a tag scope drops ancestors lacking a listed tag), D4 (every list, total and count is visible-only), the
 * ScopeGuard contract in `ScopeGuardTest` (a root scope admits an item whose ancestor chain holds a listed root, else
 * `forbidden{scope: root, required: <sorted listed ids>}`; a tag scope admits an item whose CSV tags, entries trimmed, contain
 * a listed tag exactly, with no ancestor walk, else `forbidden{scope: tag, required: <sorted tags>}`) and the envelope catalog
 * (`ambiguous_id {prefix, candidates}` ascending, ids only). The scope-leak oracle is invariance: a scoped answer must not
 * change when invisible items are deleted.
 *
 * Deliberately not asserted (reported as a finding, not an oracle): the get view's own `parentId` / `rootId` may carry the id of an\n * ancestor the scope hides; D3 truncates only the `ancestors` array and the ItemView spec lists both members unconditionally.\n *\n * Inapplicable probe: an upper-case tag (the domain accepts lowercase alphanumeric and hyphen tags only). Not asserted: a
 * cursor taken from another select. Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadScopeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun uuid(text: String): UUID = UUID.fromString(text)

    private val r1 = uuid("a1a1a1a1-0000-4000-8000-00000000000a")
    private val r2 = uuid("b2b2b2b2-0000-4000-8000-00000000000b")

    private fun getArgs(
        id: Any,
        extra: String = ""
    ) = """{"select":"get","itemId":"$id"$extra}"""

    private fun list(extra: String = "") = """{"select":"list"$extra}"""

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 a root scope forbids a foreign item before anything else and admits its own subtree`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val root = rig.make("Mine-root", id = r1)
            val own = rig.make("Mine-child", root)
            val foreign = rig.make("Beta-secret", id = r2)
            val v =
                rig.raw
                    .workItemRepository()
                    .getById(foreign.id)!!
                    .version

            assertEquals(200, select(getArgs(root.id), TEST_TOKEN).status)
            assertEquals(200, select(getArgs(own.id), TEST_TOKEN).status)

            for (args in listOf(
                getArgs(foreign.id),
                getArgs(foreign.id, ""","fields":["ancestors"]"""),
                getArgs(foreign.id, ""","ifVersion":$v""")
            )) {
                val r = select(args, TEST_TOKEN)
                assertEquals(403, r.status, "$args: ${r.raw}")
                assertEquals("forbidden", r.code, args)
                assertEquals("root", r.detail.str("scope"), args)
                assertEquals(r1.toString(), r.detail.str("required"), args)
                assertFalse("Beta-secret" in r.raw || short(foreign.id) in r.raw, "no trace of the foreign item: ${r.raw}")
            }
        }

    @Test
    fun `S9 a missing id under a restricted scope is forbidden, never not_found, for a UUID and for a prefix`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            rig.make("Mine-root", id = r1)

            for (missing in listOf("99999999-9999-4999-8999-999999999999", "00000000")) {
                val scoped = select(getArgs(missing), TEST_TOKEN)
                assertEquals(403, scoped.status, "$missing: ${scoped.raw}")
                assertEquals("forbidden", scoped.code, missing)

                val unrestricted = select(getArgs(missing))
                assertEquals(404, unrestricted.status, "control $missing: the unrestricted caller gets not_found")
                assertEquals("not_found", unrestricted.code)
            }
        }

    @Test
    fun `S9 a tag scope forbids an item without a listed tag, including a child of a tagged parent`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            val plainChild = rig.make("PlainChild", p)

            assertEquals(200, select(getArgs(p.id), TEST_TOKEN).status)

            val r = select(getArgs(plainChild.id), TEST_TOKEN)
            assertEquals(403, r.status, r.raw)
            assertEquals("forbidden", r.code)
            assertEquals("tag", r.detail.str("scope"))
            assertEquals("api", r.detail.str("required"))
            assertEquals(200, select(getArgs(plainChild.id)).status, "control: visible to the unrestricted caller")
        }

    // ------------------------------------------------------------------ S10

    private suspend fun IrClient.seedPrefixes(rig: EventLogRig) {
        val root1 = rig.make("Root one", id = r1)
        val root2 = rig.make("Root two", id = r2)
        rig.make("X", root1, id = uuid("abcd1234-0000-4000-8000-000000000001"))
        rig.make("X2", root1, id = uuid("abcd1234-1111-4111-8111-111111111111"))
        rig.make("Y", root2, id = uuid("abcd1234-7777-4777-8777-777777777777"))
        rig.make("W1", root1, id = uuid("beef0000-0000-4000-8000-000000000001"))
        rig.make("W2", root2, id = uuid("beef0000-7777-4777-8777-777777777777"))
        rig.make("V", root2, id = uuid("deadbeef-0000-4000-8000-000000000003"))
    }

    private fun candidates(r: Reply): List<String> =
        r.detail
            .getValue("candidates")
            .jsonArray
            .map { it.jsonPrimitive.content }

    @Test
    fun `S10 an unrestricted ambiguous prefix lists every candidate ascending`(): Unit =
        irApp(db.db, dir) { rig ->
            seedPrefixes(rig)

            val r = select(getArgs("abcd1234"))

            assertEquals("ambiguous_id", r.code, r.raw)
            assertEquals(ErrorCode.AMBIGUOUS_ID.httpStatus, r.status)
            assertEquals(
                listOf(
                    "abcd1234-0000-4000-8000-000000000001",
                    "abcd1234-1111-4111-8111-111111111111",
                    "abcd1234-7777-4777-8777-777777777777"
                ),
                candidates(r)
            )
        }

    @Test
    fun `S10 under a root scope an ambiguous prefix keeps only the admitted candidates`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            seedPrefixes(rig)

            val two = select(getArgs("abcd1234"), TEST_TOKEN)
            assertEquals("ambiguous_id", two.code, two.raw)
            assertEquals(
                listOf("abcd1234-0000-4000-8000-000000000001", "abcd1234-1111-4111-8111-111111111111"),
                candidates(two),
                "the out-of-scope candidate is not named"
            )
            assertFalse("7777" in two.raw, "the out-of-scope id leaves no trace: ${two.raw}")

            val one = select(getArgs("beef0000"), TEST_TOKEN)
            assertEquals(200, one.status, "one admitted candidate resolves: ${one.raw}")
            assertEquals("beef0000-0000-4000-8000-000000000001", one.data.str("id"))

            val none = select(getArgs("deadbeef"), TEST_TOKEN)
            assertEquals(403, none.status, "only an out-of-scope match: ${none.raw}")
            assertEquals("forbidden", none.code)

            val both = select(getArgs("beef0000"))
            assertEquals("ambiguous_id", both.code, "control: unrestricted sees both candidates")
            assertEquals(2, candidates(both).size)
        }

    @Test
    fun `S10 filter ids are resolved but not guarded, an unknown prefix is forbidden and an out-of-scope match is an empty page`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            seedPrefixes(rig)

            val foreignMatch = select(list(""","parentId":"deadbeef""""), TEST_TOKEN)
            assertEquals(200, foreignMatch.status, foreignMatch.raw)
            assertEquals(emptyList<JsonObject>(), foreignMatch.items)
            assertEquals(0, foreignMatch.total)

            val unknown = select(list(""","parentId":"00000000""""), TEST_TOKEN)
            assertEquals(403, unknown.status, unknown.raw)
            assertEquals("forbidden", unknown.code)

            val own = select(list(""","parentId":"$r1""""), TEST_TOKEN)
            assertEquals(200, own.status)
            assertEquals(listOf("W1", "X", "X2"), own.items.titles().sorted())
        }

    @Test
    fun `S10 the id forms are length and character checked - 7 and 33 hex fail, 8 and 32 hex pass`(): Unit =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Sized", id = uuid("5ae0c3d1-0000-4000-8000-0000000000aa"))
            val hex = item.id.toString().replace("-", "")

            for (bad in listOf(hex.substring(0, 7), hex + "0")) {
                val r = select(getArgs(bad))
                assertEquals("invalid_request", r.code, "'$bad' (${bad.length} chars): ${r.raw}")
                assertTrue("/itemId" in r.fields())
            }
            for (good in listOf(hex.substring(0, 8), hex)) {
                assertEquals(200, select(getArgs(good)).status, "'$good' (${good.length} chars)")
            }
        }

    // ------------------------------------------------------------------ S12

    private fun JsonObject.ancestorTitles(): List<String> = arr("ancestors").map { it.jsonObject.str("title") }

    @Test
    fun `S12 under a root scope the ancestor chain is complete from the listed depth-0 root down`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            // Roots only (item 754ae3dc): a listed root has no ancestor, so there is nothing above the scope to drop. The root is
            // stamped with its own id (the one-writer invariant); the chain below it is judged on the stamped root_id.
            val scopeRoot = rig.raw.workItemRepository().create(WorkItem(id = r1, title = "Scope-root", depth = 0, rootId = r1))
            val mid = rig.make("Mid", scopeRoot)
            val leaf = rig.make("Leaf", mid)
            val args = getArgs(leaf.id, ""","fields":["ancestors"]""")

            val scoped = select(args, TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf("Scope-root", "Mid"), scoped.data.ancestorTitles())
            assertEquals(
                listOf(short(scopeRoot.id), short(mid.id)),
                scoped.data.arr("ancestors").map {
                    it.jsonObject.str("id")
                },
                "the ancestors array names the admitted chain, root first"
            )

            val unrestricted = select(args)
            assertEquals(listOf("Scope-root", "Mid"), unrestricted.data.ancestorTitles(), "control: the unrestricted chain is the same")

            val viaList = select(list(""","parentId":"${scopeRoot.id}","fields":["ancestors"]"""), TEST_TOKEN)
            assertEquals(listOf("Scope-root"), viaList.items.single().ancestorTitles())
        }

    @Test
    fun `S12 a tag scope drops the ancestors without a listed tag`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val t1 = rig.make("T1-tagged", tags = "api")
            val t2 = rig.make("T2-untagged", t1)
            val t3 = rig.make("T3-tagged", t2, tags = "api")
            val args = getArgs(t3.id, ""","fields":["ancestors"]""")

            val scoped = select(args, TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf("T1-tagged"), scoped.data.ancestorTitles())
            assertFalse("T2-untagged" in scoped.raw, "the untagged ancestor is not titled: ${scoped.raw}")
            assertEquals(
                listOf(short(t1.id)),
                scoped.data.arr("ancestors").map {
                    it.jsonObject.str("id")
                },
                "the ancestors array names only admitted items"
            )

            assertEquals(listOf("T1-tagged", "T2-untagged"), select(args).data.ancestorTitles(), "control: the full chain")
        }

    // ------------------------------------------------------------------ S11 (list)

    @Test
    fun `S11 a tag scope lists only items with a listed tag, exactly, and counts only those`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            rig.make("A1", p, tags = "api")
            rig.make("A2", p, tags = "x, api")
            rig.make("B-ap", p, tags = "ap")
            rig.make("B-apis", p, tags = "apis")
            rig.make("B-none", p)
            rig.make("B-beta", p, tags = "beta")
            rig.make("B-hyphen", p, tags = "x-api")
            val a1 = rig.make("A1-child", p, tags = null)

            val children = select(list(""","parentId":"${p.id}""""), TEST_TOKEN)
            assertEquals(200, children.status, children.raw)
            assertEquals(listOf("A1", "A2"), children.items.titles().sorted(), "exact, trimmed CSV membership only")
            assertEquals(2, children.total)
            for (leak in listOf("B-ap", "B-none", "B-beta", "B-hyphen", "A1-child")) {
                assertFalse(leak in children.raw, "'$leak' must not appear: ${children.raw}")
            }

            val subtree = select(list(""","ancestorId":"${p.id}""""), TEST_TOKEN)
            assertEquals(listOf("A1", "A2", "P"), subtree.items.titles().sorted(), "no ancestor walk: A1-child has no tag of its own")
            assertEquals(3, subtree.total)

            val all = select(list(), TEST_TOKEN)
            assertEquals(3, all.total)

            val forbiddenChild = select(getArgs(a1.id), TEST_TOKEN)
            assertEquals(403, forbiddenChild.status)

            assertEquals(9, select(list()).total, "control: the unrestricted caller counts every item")
        }

    @Test
    fun `S11 a root scope lists the scoped subtree only`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val a = rig.make("In-scope root", id = r1)
            rig.make("In-scope child", a)
            val b = rig.make("Foreign root", id = r2)
            rig.make("Foreign child", b)

            val scoped = select(list(), TEST_TOKEN)

            assertEquals(listOf("In-scope child", "In-scope root"), scoped.items.titles().sorted())
            assertEquals(2, scoped.total)
            assertFalse("Foreign" in scoped.raw, scoped.raw)
            assertEquals(4, select(list()).total, "control")
        }

    @Test
    fun `B1 anchor drift - a root scope listing its drifted anchor as ancestorId sees nothing of the new tree`(): Unit =
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val a = rig.make("Drift-anchor", id = r1)
            val aChild = rig.make("Drift-anchor-child", a)
            rig.make("Drift-anchor-grand", aChild)
            val q = rig.make("Drift-newparent", id = r2)
            rig.make("Drift-newparent-kid", q)
            val args = list(""","ancestorId":"$r1"""")

            val before = select(args, TEST_TOKEN)
            assertEquals(200, before.status, before.raw)
            assertEquals(listOf("Drift-anchor", "Drift-anchor-child", "Drift-anchor-grand"), before.items.titles().sorted(), "control")
            assertEquals(3, before.total)

            // Admin reparents the anchor under Q; the subtree is restamped with Q's root_id (depth delta +1).
            val repo = rig.raw.workItemRepository()
            val moved = repo.getById(a.id)!!
            assertTrue(repo.update(moved.copy(parentId = q.id, depth = 1, rootId = q.id)) != null)
            assertEquals(2, repo.restampSubtree(a.id, 1, q.id))

            val after = select(args, TEST_TOKEN)
            assertEquals(200, after.status, after.raw)
            assertEquals(emptyList<JsonObject>(), after.items, "ancestorId = drifted anchor: ${after.raw}")
            assertEquals(0, after.total)
            assertFalse("Drift-" in after.raw, "no trace of the drifted or new-parent rows: ${after.raw}")

            val viaQ = select(list(""","ancestorId":"$r2""""), TEST_TOKEN)
            assertEquals(emptyList<JsonObject>(), viaQ.items, viaQ.raw)
            assertEquals(0, viaQ.total)
            assertFalse("Drift-" in viaQ.raw, viaQ.raw)

            val plain = select(list(), TEST_TOKEN)
            assertEquals(0, plain.total, "the plain scoped list sees nothing either: ${plain.raw}")
        }

    @Test
    fun `S11 the scoped list does not change when invisible items are deleted, the unscoped one does`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            rig.make("A1", p, tags = "api")
            val hidden = (1..4).map { rig.make("hidden-$it", p, tags = "beta") }
            val queries =
                listOf(
                    list(),
                    list(""","parentId":"${p.id}","limit":1"""),
                    list(""","ancestorId":"${p.id}","sort":"title","order":"asc""""),
                    """{"select":"search","query":"hidden"}"""
                )

            val before = queries.map { select(it, TEST_TOKEN).data }
            val adminBefore = select(queries[0]).data
            val repo = rig.raw.workItemRepository()
            for (h in hidden) assertTrue(repo.delete(h.id))

            val after = queries.map { select(it, TEST_TOKEN).data }
            assertEquals(before, after, "removing invisible items must not change a scoped answer")
            assertNotEquals(adminBefore, select(queries[0]).data, "control: the unscoped answer does change")
            assertEquals(
                2,
                before[0]
                    .getValue("page")
                    .jsonObject
                    .getValue("total")
                    .jsonPrimitive.long
            )
        }
}
