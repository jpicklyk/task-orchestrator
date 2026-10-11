package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for `items_read` select `overview`: scenarios S6, the overview part of
 * S11 / S13 and the overview probes of the frozen test-plan (NEW-SURFACE), through the production REST topology.
 *
 * Oracles: task-scope item 9 and the tool description (global `{items, page}` over the parentless items; scoped
 * `{item: full view, childCounts, items, page}` with the item's direct children; anchored `{anchor: {id, title}, items,
 * page}` with the anchor's direct children enriched with FULL-SUBTREE role counts; entries are compact views plus
 * `childCounts` with zero roles omitted, and `children` / `claimSummary` in the global form; `excludeTerminal` drops
 * terminal items without open work below, the scoped `childCounts` staying the unfiltered breakdown; `includeChildren` is
 * global only; `itemId` and `anchorId` are mutually exclusive), D2 (`itemId` / `anchorId` are TARGET ids: scope-guarded,
 * `forbidden` before not_found) and D4 (a restricted scope makes every number visible-only: listed items, totals,
 * `childCounts`, roll-ups, claim summaries and the excludeTerminal "open below" test; the root restriction narrows the
 * GLOBAL list to parentless visible items, so a mid-tree scope root is reached with `anchorId`). The scope-leak oracle is
 * invariance: the scoped answer must not change when invisible items are deleted, while the unscoped answer does.
 *
 * Not asserted (no oracle): whether the global form drops a terminal root that still has open work below, the shape of
 * `claimSummary`, and the roll-up of an item's own role. Forbidden-construct declaration: the itemId+anchorId case accepts
 * either `invalid_request` or `unknown_parameter` (the plan allows both); both are still a 400 rejection that returns no data.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadOverviewTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun overview(extra: String = "") = """{"select":"overview"$extra}"""

    private fun JsonObject.counts(): Map<String, Int> =
        (this["childCounts"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.int } ?: emptyMap()

    private fun Reply.entry(title: String): JsonObject = items.single { it.str("title") == title }

    // ------------------------------------------------------------------ S6

    @Test
    fun `S6 global overview lists the roots with compact entries and zero-omitted child counts`() =
        irApp(db.db, dir) { rig ->
            val open = rig.make("Open root")
            rig.make("k1", open)
            rig.make("k2", open)
            rig.make("k3", open, Role.WORK)
            rig.make("k4", open, Role.TERMINAL)
            rig.make("Done root", role = Role.TERMINAL)

            val r = select(overview())

            assertEquals(200, r.status, r.raw)
            assertEquals(setOf("items", "page"), r.data.keys)
            assertEquals(2, r.total)
            val entry = r.entry("Open root")
            assertEquals(short(open.id), entry.str("id"))
            assertEquals(mapOf("queue" to 2, "work" to 1, "terminal" to 1), entry.counts())
            assertTrue("claimSummary" in entry, "global entries carry a claim summary: $entry")
            assertFalse("children" in entry, "children only with includeChildren")
            for (e in r.items) {
                assertTrue(e.counts().values.all { it > 0 }, "zero roles are omitted: $e")
                assertFalse("parentId" in e, "global entries are parentless")
            }
        }

    @Test
    fun `S6 global excludeTerminal drops a terminal root with nothing below and reflects it in the total`() =
        irApp(db.db, dir) { rig ->
            rig.make("Open root")
            rig.make("Done root", role = Role.TERMINAL)

            val all = select(overview())
            assertEquals(listOf("Done root", "Open root"), all.items.titles().sorted())
            assertEquals(2, all.total)

            val open = select(overview(""","excludeTerminal":true"""))
            assertEquals(listOf("Open root"), open.items.titles())
            assertEquals(1, open.total, "the total is the filtered count")
        }

    @Test
    fun `S6 global includeChildren adds each entry's direct children as compact entries`() =
        irApp(db.db, dir) { rig ->
            val open = rig.make("Open root")
            val k1 = rig.make("k1", open)
            rig.make("k2", open, Role.WORK)

            val r = select(overview(""","includeChildren":true"""))

            val kids = r.entry("Open root").arr("children").map { it.jsonObject }
            assertEquals(listOf("k1", "k2"), kids.titles().sorted())
            assertTrue(short(k1.id) in kids.map { it.str("id") })
        }

    @Test
    fun `S6 global overview pages through every root exactly once`() =
        irApp(db.db, dir) { rig ->
            repeat(3) { rig.make("root-$it") }

            val first = select(overview(""","limit":1"""))
            assertEquals(1, first.items.size)
            assertEquals(3, first.total)
            val cursor = assertNotNull(first.next, "more roots remain: ${first.page}")
            val second = select(overview(""","limit":2,"cursor":"$cursor""""))
            assertEquals(2, second.items.size)

            val seen = (first.items + second.items).map { it.str("id") }
            assertEquals(3, seen.toSet().size, "the pages are disjoint and cover all three roots")
        }

    @Test
    fun `S6 scoped overview answers the full item, its unfiltered child counts and its direct children`() =
        irApp(db.db, dir) { rig ->
            val open = rig.make("Open root", summary = "root summary")
            rig.make("k1", open)
            rig.make("k2", open)
            rig.make("k3", open, Role.WORK)
            rig.make("k4", open, Role.TERMINAL)

            val r = select(overview(""","itemId":"${open.id}""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(setOf("item", "childCounts", "items", "page"), r.data.keys)
            assertEquals(
                open.id.toString(),
                r.data
                    .getValue("item")
                    .jsonObject
                    .str("id")
            )
            assertEquals(
                "root summary",
                r.data
                    .getValue("item")
                    .jsonObject
                    .str("summary")
            )
            assertEquals(mapOf("queue" to 2, "work" to 1, "terminal" to 1), r.data.counts())
            assertEquals(listOf("k1", "k2", "k3", "k4"), r.items.titles().sorted())
            assertEquals(4, r.total)
            for (child in r.items) assertEquals(short(open.id), child.str("parentId"))

            val open2 = select(overview(""","itemId":"${open.id}","excludeTerminal":true"""))
            assertEquals(listOf("k1", "k2", "k3"), open2.items.titles().sorted(), "the terminal child is dropped")
            assertEquals(mapOf("queue" to 2, "work" to 1, "terminal" to 1), open2.data.counts(), "childCounts stay unfiltered")
        }

    @Test
    fun `S6 scoped excludeTerminal keeps a terminal child that still has open work below`() =
        irApp(db.db, dir) { rig ->
            val box = rig.make("Box")
            rig.make("plain terminal", box, Role.TERMINAL)
            val parked = rig.make("terminal with open work", box, Role.TERMINAL)
            rig.make("open below", parked)
            rig.make("queued", box)

            val r = select(overview(""","itemId":"${box.id}","excludeTerminal":true"""))

            assertEquals(listOf("queued", "terminal with open work"), r.items.titles().sorted())
        }

    @Test
    fun `S6 anchored overview lists the anchor's children with full-subtree role roll-ups`() =
        irApp(db.db, dir) { rig ->
            val anchor = rig.make("Anchor")
            val s1 = rig.make("s1", anchor)
            val g1 = rig.make("g1", s1, Role.WORK)
            rig.make("g2", s1, Role.TERMINAL)
            rig.make("gg", g1, Role.REVIEW)
            rig.make("s2", anchor)

            val r = select(overview(""","anchorId":"${anchor.id}""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(setOf("anchor", "items", "page"), r.data.keys)
            val a = r.data.getValue("anchor").jsonObject
            assertEquals("Anchor", a.str("title"))
            assertTrue(a.str("id").startsWith(short(anchor.id)), "anchor id names the anchor: $a")
            assertEquals(listOf("s1", "s2"), r.items.titles().sorted())
            val counts = r.entry("s1").counts()
            assertEquals(1, counts["work"], "g1 is below s1: $counts")
            assertEquals(1, counts["terminal"], "g2 is below s1: $counts")
            assertEquals(1, counts["review"], "gg is two levels below s1, so the roll-up spans the subtree: $counts")
            assertTrue(
                r
                    .entry("s2")
                    .counts()
                    .values
                    .all { it > 0 },
                "zero roles are omitted: ${r.entry("s2")}"
            )
        }

    // ------------------------------------------------------------------ S13 (overview)

    @Test
    fun `S13 includeChildren is global only`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Item")

            for (target in listOf(""""itemId":"${item.id}"""", """"anchorId":"${item.id}"""")) {
                val r = select(overview(""",$target,"includeChildren":true"""))
                assertEquals("invalid_request", r.code, "$target: ${r.raw}")
                assertEquals(400, r.status)
            }
            assertEquals(200, select(overview(""","includeChildren":true""")).status, "control: global is fine")
        }

    @Test
    fun `S13 itemId and anchorId together are rejected without data`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("Item")

            val r = select(overview(""","itemId":"${item.id}","anchorId":"${item.id}""""))

            assertEquals(400, r.status, r.raw)
            assertTrue(r.code in setOf("invalid_request", "unknown_parameter"), r.code)
            assertFalse(r.ok)
            assertFalse("data" in r.body)
        }

    @Test
    fun `S13 target ids are validated - malformed is invalid_request, missing is not_found`() =
        irApp(db.db, dir) { rig ->
            rig.make("Item")

            for (member in listOf("itemId", "anchorId")) {
                val bad = select(overview(""","$member":"xyz""""))
                assertEquals("invalid_request", bad.code, "$member: ${bad.raw}")
                assertTrue("/$member" in bad.fields(), "$member: ${bad.fields()}")

                val missing = select(overview(""","$member":"99999999-9999-4999-8999-999999999999""""))
                assertEquals(404, missing.status, "$member: ${missing.raw}")
                assertEquals("not_found", missing.code)
            }
        }

    @Test
    fun `S13 limit bounds, unknown members and a malformed cursor are rejected`() =
        irApp(db.db, dir) { rig ->
            rig.make("Item")

            for (bad in listOf("201", "0")) {
                val r = select(overview(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertTrue("/limit" in r.fields(), "limit $bad")
            }
            assertEquals(200, select(overview(""","limit":200""")).status)

            for (member in listOf("query", "fields", "parentId", "kinds", "role")) {
                val r = select(overview(""","$member":1"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters())
            }
            assertEquals("invalid_cursor", select(overview(""","cursor":"not-a-cursor"""")).code)
        }

    // ------------------------------------------------------------------ S9 / S11 (overview scope)

    @Test
    fun `S11 under a tag scope every overview shape counts visible items only`() =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            rig.make("A1", p, Role.QUEUE, "api")
            rig.make("A2", p, Role.WORK, "api", claimedBy = "agent-a")
            rig.make("B1", p, Role.QUEUE, "beta")
            rig.make("X", p, Role.QUEUE, "beta", claimedBy = "agent-x")
            rig.make("B2", p, Role.TERMINAL, "api")
            rig.make("Z", tags = "beta")

            val scoped = select(overview(""","itemId":"${p.id}""""), TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf("A1", "A2", "B2"), scoped.items.titles().sorted())
            assertEquals(3, scoped.total)
            assertEquals(mapOf("queue" to 1, "work" to 1, "terminal" to 1), scoped.data.counts())

            val global = select(overview(), TEST_TOKEN)
            assertEquals(listOf("P"), global.items.titles(), "invisible roots drop out of the global list")
            assertEquals(1, global.total)
            assertEquals(mapOf("queue" to 1, "work" to 1, "terminal" to 1), global.entry("P").counts())

            val anchored = select(overview(""","anchorId":"${p.id}""""), TEST_TOKEN)
            assertEquals(listOf("A1", "A2", "B2"), anchored.items.titles().sorted())
            assertEquals(3, anchored.total)

            for (leak in listOf("B1", "\"X\"", "\"Z\"", "agent-x", "agent-a", "beta")) {
                for (reply in listOf(scoped, global, anchored)) {
                    assertFalse(leak in reply.raw, "'$leak' must not appear: ${reply.raw}")
                }
            }

            val control = select(overview(""","itemId":"${p.id}""""))
            assertEquals(mapOf("queue" to 3, "work" to 1, "terminal" to 1), control.data.counts(), "control: unscoped sees every child")
            assertEquals(5, control.total)
        }

    @Test
    fun `S11 the scoped overview does not change when invisible items are deleted, the unscoped one does`() =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val p = rig.make("P", tags = "api")
            rig.make("A1", p, Role.QUEUE, "api")
            rig.make("A2", p, Role.WORK, "api", claimedBy = "agent-a")
            val b1 = rig.make("B1", p, Role.QUEUE, "beta")
            val x = rig.make("X", p, Role.QUEUE, "beta", claimedBy = "agent-x")
            val z = rig.make("Z", tags = "beta")
            val queries =
                listOf(
                    overview(),
                    overview(""","itemId":"${p.id}""""),
                    overview(""","anchorId":"${p.id}""""),
                    overview(""","excludeTerminal":true,"includeChildren":true""")
                )

            fun JsonObject.withoutItem() = JsonObject(filterKeys { it != "item" })

            val before = queries.map { select(it, TEST_TOKEN).data.withoutItem() }
            val adminBefore = select(queries[1]).data.withoutItem()
            val repo = rig.raw.workItemRepository()
            for (id in listOf(b1.id, x.id, z.id)) assertTrue(repo.delete(id), "seed delete $id")

            val after = queries.map { select(it, TEST_TOKEN).data.withoutItem() }
            assertEquals(before, after, "removing invisible items must not change a scoped answer")
            assertNotEquals(adminBefore, select(queries[1]).data.withoutItem(), "control: the unscoped answer does change")
        }

    @Test
    fun `S11 excludeTerminal under a tag scope ignores open work that is invisible`() =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val k = rig.make("K", tags = "api")
            val t = rig.make("T", k, Role.TERMINAL, "api")
            rig.make("C", t, Role.QUEUE, "beta")
            rig.make("L", k, Role.QUEUE, "api")
            val args = overview(""","itemId":"${k.id}","excludeTerminal":true""")

            val scoped = select(args, TEST_TOKEN)
            assertEquals(
                listOf("L"),
                scoped.items.titles(),
                "T's only open descendant is invisible, so T has no open work below: ${scoped.raw}"
            )

            val control = select(args)
            assertEquals(listOf("L", "T"), control.items.titles().sorted(), "control: the unscoped caller sees C, so T is retained")
        }

    @Test
    fun `S11 excludeTerminal under a tag scope keeps a terminal item whose open descendant is visible`() =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val k = rig.make("K", tags = "api")
            val t = rig.make("T", k, Role.TERMINAL, "api")
            rig.make("C", t, Role.QUEUE, "api")

            val scoped = select(overview(""","itemId":"${k.id}","excludeTerminal":true"""), TEST_TOKEN)

            assertEquals(listOf("T"), scoped.items.titles())
        }

    @Test
    fun `S9 under a root scope foreign and missing overview targets are forbidden`() =
        irApp(db.db, dir, scopeRootIds = setOf(ROOT_A)) { rig ->
            val a = rig.make("A", id = ROOT_A)
            rig.make("inA", a)
            val b = rig.make("B", id = ROOT_B)
            rig.make("inB", b)

            for (member in listOf("itemId", "anchorId")) {
                val denied = select(overview(""","$member":"${b.id}""""), TEST_TOKEN)
                assertEquals(403, denied.status, "$member: ${denied.raw}")
                assertEquals("forbidden", denied.code)
                assertEquals("root", denied.detail.str("scope"))
                assertEquals(ROOT_A.toString(), denied.detail.str("required"))
                assertFalse("inB" in denied.raw, "nothing of the foreign subtree leaks")

                val missing = select(overview(""","$member":"99999999-9999-4999-8999-999999999999""""), TEST_TOKEN)
                assertEquals(403, missing.status, "$member: a missing id is forbidden, never not_found: ${missing.raw}")

                val own = select(overview(""","$member":"${a.id}""""), TEST_TOKEN)
                assertEquals(200, own.status, "$member control: ${own.raw}")
            }

            val global = select(overview(), TEST_TOKEN)
            assertEquals(listOf("A"), global.items.titles(), "the global list shows the visible roots only")
            assertEquals(1, global.total)
        }

    @Test
    fun `S9 a mid-tree scope root has an empty global list and is reached with anchorId`() =
        irApp(db.db, dir, scopeRootIds = setOf(MID)) { rig ->
            val top = rig.make("Top")
            val mid = rig.make("Mid", top, id = MID)
            rig.make("Under", mid)

            val global = select(overview(), TEST_TOKEN)
            assertEquals(200, global.status, global.raw)
            assertEquals(emptyList<JsonObject>(), global.items, "no parentless item is visible")
            assertEquals(0, global.total)

            val anchored = select(overview(""","anchorId":"${mid.id}""""), TEST_TOKEN)
            assertEquals(listOf("Under"), anchored.items.titles())

            val above = select(overview(""","anchorId":"${top.id}""""), TEST_TOKEN)
            assertEquals(403, above.status, above.raw)
            assertEquals("forbidden", above.code)
        }

    companion object {
        private val ROOT_A: UUID = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
        private val ROOT_B: UUID = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
        private val MID: UUID = UUID.fromString("c3c3c3c3-0000-4000-8000-00000000000c")
    }
}
