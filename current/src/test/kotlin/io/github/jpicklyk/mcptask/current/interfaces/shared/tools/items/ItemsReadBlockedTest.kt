package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.relatesDep
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f35528b3) for `items_read` select `blocked`, scenario S15 and the `blocked` part
 * of S16 of the frozen test-plan (NEW-SURFACE), through the production REST topology with a real bearer principal.
 *
 * Oracles: the pre-existing `findBlocked` KDoc (a blocking dependency is unsatisfied when its blocker's role is not at or
 * beyond the edge threshold, TERMINAL when unset; a BLOCKED or missing blocker never satisfies; TERMINAL items are never
 * returned), task-scope item 3 and D-b / D-d / D-e / D-g (explicit = role BLOCKED, dependency = unsatisfied BLOCKS edge;
 * entries carry `blockType` and `blockers[{id, title, role, threshold, satisfied}]`; default limit 50, maximum 200; an
 * out-of-scope blocker is omitted while the item stays blocked), the frozen test-plan S15 for the order (blocked, queue,
 * work, review, then id; satisfied blockers listed and flagged) and the Q19 scope rules (every list and total is
 * visible-only). The role-changed instant of every fixture is the same so the id tie-break decides within a role.
 *
 * Unspecified and therefore not asserted: the `blockType` of an item that is both in role BLOCKED and has an unsatisfied
 * edge, and the `blockers` of an explicit entry.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemsReadBlockedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val t0: Instant = Instant.parse("2026-02-01T00:00:00Z")

    private fun blocked(extra: String = "") = """{"select":"blocked"$extra}"""

    /** A UUID whose 8-hex prefix and full text sort by [n]. */
    private fun uid(n: Int): UUID = UUID.fromString("%08x-0000-4000-8000-%012x".format(n, n))

    private fun shorts(vararg items: WorkItem): Set<String> = items.map { short(it.id) }.toSet()

    /** The `blockers` of an entry; an entry that names no blocker omits the member, which reads as the empty list. */
    private fun JsonObject.blockers(): List<JsonObject> = (this["blockers"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonObject }

    private suspend fun EventLogRig.fixed(
        title: String,
        n: Int,
        role: Role = Role.QUEUE,
        tags: String? = null,
        parent: WorkItem? = null
    ): WorkItem = vwMake(title, parent, role = role, id = uid(n), tags = tags, at = t0)

    // ------------------------------------------------------------------ S15

    private class Shape(
        val ex: WorkItem,
        val bb: WorkItem,
        val q: WorkItem,
        val mix: WorkItem,
        val thr2: WorkItem,
        val thr3: WorkItem,
        val w: WorkItem,
        val r: WorkItem,
        val bq: WorkItem,
        val done: WorkItem
    )

    private suspend fun seedShape(rig: EventLogRig): Shape {
        val bq = rig.fixed("Bq", 0x900)
        val done = rig.fixed("Done", 0x901, Role.TERMINAL)
        val bw = rig.fixed("Bw", 0x902, Role.WORK)
        val bb = rig.fixed("Bb", 0x903, Role.BLOCKED)
        val ex = rig.fixed("Ex", 0x10, Role.BLOCKED)
        val q = rig.fixed("Q", 0x11)
        val mix = rig.fixed("Mix", 0x12)
        val thr2 = rig.fixed("Thr2", 0x13)
        val thr3 = rig.fixed("Thr3", 0x14)
        val w = rig.fixed("W", 0x15, Role.WORK)
        val r = rig.fixed("R", 0x16, Role.REVIEW)
        val t = rig.fixed("T", 0x17, Role.TERMINAL)
        val sat = rig.fixed("Sat", 0x18)
        val rel = rig.fixed("Rel", 0x19)
        val thrOk = rig.fixed("ThrOk", 0x1a)

        rig.vwBlocks(bq, q)
        rig.vwBlocks(done, mix)
        rig.vwBlocks(bq, mix)
        rig.vwBlocks(bq, thr2, "work")
        rig.vwBlocks(bb, thr3, "queue")
        rig.vwBlocks(bq, w)
        rig.vwBlocks(bq, r)
        rig.vwBlocks(bq, t)
        rig.vwBlocks(done, sat)
        rig.raw.dependencyRepository().create(relatesDep(bq.id, rel.id))
        rig.vwBlocks(bw, thrOk, "work")
        return Shape(ex, bb, q, mix, thr2, thr3, w, r, bq, done)
    }

    @Test
    fun `S15 lists role-blocked and unsatisfied-dependency items in role then id order and never terminal or satisfied ones`(): Unit =
        irApp(db.db, dir) { rig ->
            val s = seedShape(rig)

            val r = select(blocked())

            assertEquals(200, r.status, r.raw)
            assertEquals(
                listOf(s.ex, s.bb, s.q, s.mix, s.thr2, s.thr3, s.w, s.r).map { short(it.id) },
                r.idList(),
                "blocked, queue, work, review, then id: ${r.items.titles()}"
            )
            assertEquals(8, r.total)
            val types = r.items.associate { it.str("title") to it.str("blockType") }
            assertEquals("explicit", types.getValue("Ex"))
            assertEquals("explicit", types.getValue("Bb"), "the BLOCKED-role blocker is itself an explicit block")
            for (title in listOf("Q", "Mix", "Thr2", "Thr3", "W", "R")) assertEquals("dependency", types.getValue(title), title)
            for (absent in listOf("T", "Sat", "Rel", "ThrOk", "Bq", "Done", "Bw")) {
                assertFalse(absent in types, "$absent must not be listed: ${types.keys}")
            }
        }

    @Test
    fun `S15 an entry lists every blocker with its role, threshold and satisfied flag`(): Unit =
        irApp(db.db, dir) { rig ->
            val s = seedShape(rig)

            val r = select(blocked())

            val q = r.items.single { it.str("title") == "Q" }
            val qBlocker = q.blockers().single()
            assertTrue(sameItem(qBlocker.str("id"), s.bq.id), qBlocker.toString())
            assertEquals("Bq", qBlocker.str("title"))
            assertEquals("queue", qBlocker.str("role"))
            assertEquals("terminal", qBlocker.str("threshold"))
            assertEquals(false, qBlocker.boolOf("satisfied"))

            val mix = r.items.single { it.str("title") == "Mix" }
            val byTitle = mix.blockers().associateBy { it.str("title") }
            assertEquals(setOf("Bq", "Done"), byTitle.keys, "a satisfied blocker is listed too")
            assertEquals(true, byTitle.getValue("Done").boolOf("satisfied"))
            assertEquals("terminal", byTitle.getValue("Done").str("role"))
            assertEquals(false, byTitle.getValue("Bq").boolOf("satisfied"))

            val thr2 =
                r.items
                    .single { it.str("title") == "Thr2" }
                    .blockers()
                    .single()
            assertEquals("work", thr2.str("threshold"), "the edge's own threshold")
            assertEquals(false, thr2.boolOf("satisfied"), "a queue blocker has not reached work")

            val thr3 =
                r.items
                    .single { it.str("title") == "Thr3" }
                    .blockers()
                    .single()
            assertEquals("blocked", thr3.str("role"))
            assertEquals("queue", thr3.str("threshold"))
            assertEquals(false, thr3.boolOf("satisfied"), "a BLOCKED blocker never satisfies, even at threshold queue")
        }

    @Test
    fun `S15 a blocker exactly at its threshold satisfies the edge and one role short does not`(): Unit =
        irApp(db.db, dir) { rig ->
            // (blocker role, unblockAt, listed)
            val cases =
                listOf(
                    Triple(Role.QUEUE, "work", true),
                    Triple(Role.WORK, "work", false),
                    Triple(Role.REVIEW, "work", false),
                    Triple(Role.WORK, "review", true),
                    Triple(Role.REVIEW, "review", false),
                    Triple(Role.REVIEW, "terminal", true),
                    Triple(Role.TERMINAL, "terminal", false),
                    Triple(Role.TERMINAL, "queue", false),
                    Triple(Role.BLOCKED, "queue", true)
                )
            var n = 0x100
            val expected = mutableMapOf<String, Boolean>()
            for ((role, threshold, listed) in cases) {
                val blocker = rig.fixed("B-$role-$threshold", n++, role)
                val item = rig.fixed("I-$role-$threshold", n++)
                rig.vwBlocks(blocker, item, threshold)
                expected[short(item.id)] = listed
            }

            val r = select(blocked(""","limit":200"""))

            assertEquals(200, r.status, r.raw)
            for ((id, listed) in expected) {
                assertEquals(listed, id in r.ids(), "item $id listed=$listed; listed items: ${r.items.titles()}")
            }
        }

    @Test
    fun `S15 a database with no blocked item answers an empty page`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("done", role = Role.TERMINAL)
            rig.vwMake("free")

            val r = select(blocked())

            assertEquals(200, r.status, r.raw)
            assertEquals(emptyList(), r.items)
            assertEquals(0, r.total)
            assertNull(r.next)
        }

    @Test
    fun `S15 parentId keeps the direct children and ancestorId the subtree, by UUID or hex prefix`(): Unit =
        irApp(db.db, dir) { rig ->
            val blocker = rig.fixed("Blocker", 0x900)
            val parent = rig.fixed("P", 0x10, Role.WORK)
            val child = rig.fixed("C", 0x11, parent = parent)
            val grand = rig.fixed("G", 0x12, parent = child)
            val outside = rig.fixed("Outside", 0x13)
            for (item in listOf(child, grand, outside)) rig.vwBlocks(blocker, item)

            val direct = select(blocked(""","parentId":"${parent.id}""""))
            val subtree = select(blocked(""","ancestorId":"${parent.id}""""))
            val prefix = select(blocked(""","parentId":"${short(parent.id)}""""))
            val all = select(blocked())

            assertEquals(shorts(child), direct.ids(), direct.items.titles().toString())
            assertEquals(1, direct.total)
            assertEquals(shorts(child, grand), subtree.ids(), subtree.items.titles().toString())
            assertEquals(2, subtree.total)
            assertEquals(direct.ids(), prefix.ids())
            assertEquals(shorts(child, grand, outside), all.ids())
            val bad = select(blocked(""","parentId":"xyz""""))
            assertEquals("invalid_request", bad.code, bad.raw)
            assertTrue("/parentId" in bad.fields(), bad.fields().toString())
        }

    // ------------------------------------------------------------------ paging and validation

    @Test
    fun `S15 the default page is 50 with an exact total, limit 200 returns all and pages are disjoint`(): Unit =
        irApp(db.db, dir) { rig ->
            val blocker = rig.fixed("Blocker", 0x9000)
            repeat(55) { rig.vwBlocks(blocker, rig.fixed("w-$it", 0x100 + it)) }

            val default = select(blocked())
            assertEquals(50, default.items.size)
            assertEquals(55, default.total)
            assertNotNull(default.next)

            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0
            do {
                val r = select(blocked(""","limit":20""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")))
                assertEquals(55, r.total, "total is exact on every page")
                seen += r.idList()
                cursor = r.next
                pages++
            } while (cursor != null && pages < 10)
            assertEquals(3, pages)
            assertEquals(55, seen.distinct().size, "no item on two pages")
            assertEquals(default.idList(), seen.take(50), "the paged order is the default order")

            assertEquals(55, select(blocked(""","limit":200""")).items.size)
        }

    @Test
    fun `S15 limit outside 1 to 200, a garbage cursor and unknown members are rejected on their own pointer`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("x")

            for (bad in listOf("0", "201", "-1")) {
                val r = select(blocked(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            val cursor = select(blocked(""","cursor":"not-a-cursor""""))
            assertEquals("invalid_cursor", cursor.code, cursor.raw)
            assertEquals(400, cursor.status)
            for ((member, value) in listOf(
                "role" to "\"queue\"",
                "mode" to "\"x\"",
                "includeAncestors" to "true",
                "since" to "\"2026-01-01T00:00:00Z\""
            )) {
                val r = select(blocked(""","$member":$value"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters(), member)
            }
        }

    @Test
    fun `S15 fields summary and ancestors add their members and an unknown name is rejected`(): Unit =
        irApp(db.db, dir) { rig ->
            val blocker = rig.fixed("Blocker", 0x900)
            val parent = rig.fixed("P", 0x10, Role.WORK)
            val child = rig.vwMake("C", parent, id = uid(0x11), summary = "child summary", at = t0)
            rig.vwBlocks(blocker, child)

            val plain = select(blocked())
            val full = select(blocked(""","fields":["summary","ancestors"]"""))
            val none = select(blocked(""","fields":[]"""))

            for (r in listOf(plain, none)) {
                assertFalse("summary" in r.items.single() || "ancestors" in r.items.single(), "no opt-in member by default: ${r.raw}")
            }
            assertEquals("child summary", full.items.single().str("summary"))
            assertEquals(
                listOf(short(parent.id)),
                full.items
                    .single()
                    .arr("ancestors")
                    .map { it.jsonObject.str("id") }
            )
            val bad = select(blocked(""","fields":["createdAt"]"""))
            assertEquals("invalid_request", bad.code, bad.raw)
            assertTrue(bad.fields().any { it.startsWith("/fields") })
            val reasons = bad.reasons().joinToString(" ")
            assertTrue("summary" in reasons && "ancestors" in reasons, "the allowed names are listed: $reasons")
        }

    // ------------------------------------------------------------------ S16 scope

    @Test
    fun `S16 a tag scope lists and counts only visible blocked items and omits hidden blockers while the item stays blocked`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val hiddenQ = rig.fixed("Hidden-blocker-queue", 0x900)
            val hiddenDone = rig.fixed("Hidden-blocker-done", 0x901, Role.TERMINAL)
            val visibleBlocker = rig.fixed("Visible-blocker", 0x902, tags = "api")
            val v1 = rig.fixed("V1", 0x10, tags = "api")
            val v2 = rig.fixed("V2", 0x11, tags = "api")
            val v3 = rig.fixed("V3-satisfied-by-hidden", 0x12, tags = "api")
            val v4 = rig.fixed("V4-explicit", 0x13, Role.BLOCKED, tags = "api")
            val hid = rig.fixed("Hid-blocked", 0x14)
            rig.fixed("Hid-explicit", 0x15, Role.BLOCKED)
            rig.vwBlocks(hiddenQ, v1)
            rig.vwBlocks(hiddenQ, v2)
            rig.vwBlocks(visibleBlocker, v2)
            rig.vwBlocks(hiddenDone, v3)
            rig.vwBlocks(visibleBlocker, hid)

            val scoped = select(blocked(), TEST_TOKEN)
            val open = select(blocked())

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(shorts(v1, v2, v4), scoped.ids(), "listed: ${scoped.items.titles()}")
            assertEquals(3, scoped.total, "the total counts only visible items")
            val v1Entry = scoped.items.single { it.str("id") == short(v1.id) }
            assertEquals("dependency", v1Entry.str("blockType"), "the item stays blocked by its hidden blocker")
            assertEquals(0, v1Entry.blockers().size, "the hidden blocker is omitted from blockers: $v1Entry")
            val v2Blockers = scoped.items.single { it.str("id") == short(v2.id) }.blockers()
            assertEquals(1, v2Blockers.size, v2Blockers.toString())
            assertTrue(sameItem(v2Blockers.single().str("id"), visibleBlocker.id))
            for (secret in listOf("Hidden-blocker", "Hid-", short(hiddenQ.id), short(hiddenDone.id), short(hid.id))) {
                assertFalse(secret in scoped.raw, "no trace of '$secret': ${scoped.raw}")
            }
            assertEquals(5, open.total, "control: the unrestricted caller sees v1, v2, v4, hid and the hidden explicit item")
            assertEquals(
                2,
                open.items
                    .single { it.str("id") == short(v2.id) }
                    .blockers()
                    .size
            )
            assertFalse(short(v3.id) in scoped.ids(), "V3's only blocker is satisfied: it is not blocked")
        }

    @Test
    fun `S16 a root scope lists only the subtree of the listed root and omits a blocker from another root`() {
        val r1 = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
        val r2 = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val root1 = rig.vwMake("Mine", id = r1, role = Role.WORK, at = t0)
            val mineBlocked = rig.vwMake("Mine-blocked", root1, id = uid(0x10), at = t0)
            val root2 = rig.vwMake("Theirs", id = r2, role = Role.WORK, at = t0)
            val theirsBlocker = rig.vwMake("Theirs-blocker", root2, id = uid(0x20), at = t0)
            val theirsBlocked = rig.vwMake("Theirs-blocked", root2, id = uid(0x21), at = t0)
            rig.vwBlocks(theirsBlocker, mineBlocked)
            rig.vwBlocks(theirsBlocker, theirsBlocked)

            val scoped = select(blocked(), TEST_TOKEN)

            assertEquals(shorts(mineBlocked), scoped.ids(), scoped.items.titles().toString())
            assertEquals(1, scoped.total)
            assertEquals(
                0,
                scoped.items
                    .single()
                    .blockers()
                    .size,
                "the other root's blocker is omitted: ${scoped.raw}"
            )
            assertEquals("dependency", scoped.items.single().str("blockType"))
            assertFalse("Theirs" in scoped.raw, scoped.raw)
            assertEquals(2, select(blocked()).total, "control: unrestricted sees both blocked items")
        }
    }

    @Test
    fun `S16 a parent filter naming a hidden item is not an error and lists only the visible children`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val blocker = rig.fixed("Blocker", 0x900, tags = "api")
            val hiddenParent = rig.fixed("Hidden-parent", 0x10, Role.WORK)
            val child = rig.fixed("Child", 0x11, parent = hiddenParent, tags = "api")
            rig.vwBlocks(blocker, child)

            val scoped = select(blocked(""","parentId":"${hiddenParent.id}""""), TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(shorts(child), scoped.ids(), "the visible child is still listed (filter ids are not guarded)")
            assertFalse("Hidden-parent" in scoped.raw, scoped.raw)
        }
}
