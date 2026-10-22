package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.relatesDep
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Independent tests (test-author seat, item f35528b3) for `items_read` select `next`, scenarios S11-S14 and the `next` part
 * of S16 of the frozen test-plan (NEW-SURFACE), through the production REST topology with a real bearer principal.
 *
 * Oracles: the pre-existing `findEligible` KDoc (ordered by priority rank then complexity ascending with nulls last, or by
 * `createdAt` ascending, or by `createdAt` descending, then `id` ascending; no active claim at the unit instant; every BLOCKS
 * edge into the item has a blocker whose role is at or beyond the edge threshold, TERMINAL when unset, a BLOCKED blocker never
 * satisfies, RELATES_TO ignored), task-scope item 2 and D-c / D-d / D-g (filters, empty-page `reason` precedence
 * claim_held over dependency_unmet and absent when nothing matched, default limit 1 and maximum 200, role terminal rejected,
 * dropped 3.x parameters), the `ListPage` / envelope "List data" shape (`data.items`, `data.page.total/truncated/next`,
 * `data.reason`), the compact item view (8-hex short id, `isClaimed` only with `includeClaimed`, `claimedBy` never) and
 * the Q19 scope rules (every list, total and count is visible-only; reasons are computed over the visible set).
 *
 * Ambiguities (not resolved from the implementation): none left open; the tag filter semantics (any-of, case-insensitive)
 * mirror the sibling `list` select's declared behaviour.
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 240, unit = TimeUnit.SECONDS)
class ItemsReadNextTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun next(extra: String = "") = """{"select":"next"$extra}"""

    /** A UUID whose 8-hex prefix and full text sort by [n]. */
    private fun uid(n: Int): UUID = UUID.fromString("%08x-0000-4000-8000-%012x".format(n, n))

    private fun Reply.reason(): String? = data["reason"]?.jsonPrimitive?.content

    private fun shorts(vararg items: WorkItem): Set<String> = items.map { short(it.id) }.toSet()

    private val t0: Instant = Instant.parse("2026-02-01T00:00:00Z")

    // ------------------------------------------------------------------ S11 ranking

    private class Ranked(
        val b: WorkItem,
        val f: WorkItem,
        val a: WorkItem,
        val c: WorkItem,
        val d: WorkItem,
        val g: WorkItem,
        val h: WorkItem,
        val e: WorkItem
    ) {
        val expected get() = listOf(b, f, a, c, d, g, h, e)
    }

    /** High: b(2) f(2, later id) a(5) c(null); medium: d(1) g(3) h(null); low: e(1). */
    private suspend fun seedRanked(rig: EventLogRig): Ranked {
        val e = rig.vwMake("e", priority = Priority.LOW, complexity = 1, id = uid(0x50))
        val h = rig.vwMake("h", priority = Priority.MEDIUM, complexity = null, id = uid(0x40))
        val c = rig.vwMake("c", priority = Priority.HIGH, complexity = null, id = uid(0x30))
        val a = rig.vwMake("a", priority = Priority.HIGH, complexity = 5, id = uid(0x20))
        val f = rig.vwMake("f", priority = Priority.HIGH, complexity = 2, id = uid(0x12))
        val b = rig.vwMake("b", priority = Priority.HIGH, complexity = 2, id = uid(0x11))
        val g = rig.vwMake("g", priority = Priority.MEDIUM, complexity = 3, id = uid(0x60))
        val d = rig.vwMake("d", priority = Priority.MEDIUM, complexity = 1, id = uid(0x70))
        return Ranked(b, f, a, c, d, g, h, e)
    }

    @Test
    fun `S11 priority ranking is high first, then complexity ascending with nulls last, then id ascending`(): Unit =
        irApp(db.db, dir) { rig ->
            val r0 = seedRanked(rig)
            val want = r0.expected.map { short(it.id) }

            val explicit = select(next(""","limit":200,"orderBy":"priority""""))
            val default = select(next(""","limit":200"""))

            assertEquals(200, explicit.status, explicit.raw)
            assertEquals(want, explicit.idList(), "orderBy priority: ${explicit.items.titles()}")
            assertEquals(want, default.idList(), "priority is the default order: ${default.items.titles()}")
            assertEquals(8, explicit.total)
        }

    @Test
    fun `S11 oldest and newest order by creation time with the id ascending as the tie-break`(): Unit =
        irApp(db.db, dir) { rig ->
            val x1 = rig.vwMake("x1", priority = Priority.LOW, id = uid(0x500), at = t0)
            val x2 = rig.vwMake("x2", priority = Priority.MEDIUM, id = uid(0x400), at = t0.plusSeconds(10))
            val y2 = rig.vwMake("y2", priority = Priority.HIGH, id = uid(0x300), at = t0.plusSeconds(20))
            val y1 = rig.vwMake("y1", priority = Priority.LOW, id = uid(0x200), at = t0.plusSeconds(20))
            val x4 = rig.vwMake("x4", priority = Priority.HIGH, id = uid(0x100), at = t0.plusSeconds(30))

            val oldest = select(next(""","limit":200,"orderBy":"oldest""""))
            val newest = select(next(""","limit":200,"orderBy":"newest""""))

            assertEquals(listOf(x1, x2, y1, y2, x4).map { short(it.id) }, oldest.idList(), "oldest: ${oldest.items.titles()}")
            assertEquals(listOf(x4, y1, y2, x2, x1).map { short(it.id) }, newest.idList(), "newest: ${newest.items.titles()}")
        }

    @Test
    fun `S11 pages are disjoint, their union is the whole ranking and a replayed cursor answers the same page`(): Unit =
        irApp(db.db, dir) { rig ->
            val want = seedRanked(rig).expected.map { short(it.id) }
            val seen = mutableListOf<String>()
            val cursors = mutableListOf<String>()
            var cursor: String? = null

            do {
                val r = select(next(""","limit":3""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")))
                assertEquals(200, r.status, r.raw)
                assertEquals(8, r.total, "total is exact on every page")
                assertTrue(r.items.size <= 3)
                seen += r.idList()
                cursor = r.next
                cursor?.let { cursors += it }
            } while (cursor != null && cursors.size < 10)

            assertEquals(want, seen)
            assertEquals(2, cursors.size, "8 items at 3 per page are 3 pages")
            val replayA = select(next(""","limit":3,"cursor":"${cursors[0]}""""))
            val replayB = select(next(""","limit":3,"cursor":"${cursors[0]}""""))
            assertEquals(replayA.idList(), replayB.idList(), "a replayed cursor is stable")
            assertEquals(want.subList(3, 6), replayA.idList())
        }

    @Test
    fun `S14 the default page holds one item with an exact total and a next cursor`(): Unit =
        irApp(db.db, dir) { rig ->
            val first = seedRanked(rig).expected.first()

            val r = select(next())

            assertEquals(200, r.status, r.raw)
            assertEquals(listOf(short(first.id)), r.idList(), "the single best item")
            assertEquals(8, r.total)
            assertNotNull(r.next, "more remain")
            assertTrue(r.page.getValue("truncated").toString() == "true", r.page.toString())
        }

    // ------------------------------------------------------------------ S12 claims and dependencies

    private class Eligibility(
        val free: WorkItem,
        val claimed: WorkItem,
        val bq: WorkItem,
        val depBlocked: WorkItem,
        val satisfied: WorkItem,
        val workOk: WorkItem,
        val bq2: WorkItem,
        val unmet: WorkItem,
        val rel: WorkItem,
        val relItem: WorkItem,
        val bbDep: WorkItem
    ) {
        val eligible get() = setOf(free, bq, satisfied, workOk, bq2, rel, relItem)
    }

    private suspend fun seedEligibility(rig: EventLogRig): Eligibility {
        val free = rig.vwMake("free")
        val claimed = rig.vwMake("claimed", claimedBy = "agent-x")
        val bq = rig.vwMake("bq")
        val depBlocked = rig.vwMake("depBlocked")
        rig.vwBlocks(bq, depBlocked)
        val done = rig.vwMake("done", role = Role.TERMINAL)
        val satisfied = rig.vwMake("satisfied")
        rig.vwBlocks(done, satisfied)
        val bw = rig.vwMake("bw", role = Role.WORK)
        val workOk = rig.vwMake("workOk")
        rig.vwBlocks(bw, workOk, "work")
        val bq2 = rig.vwMake("bq2")
        val unmet = rig.vwMake("unmet")
        rig.vwBlocks(bq2, unmet, "work")
        val rel = rig.vwMake("rel")
        val relItem = rig.vwMake("relItem")
        rig.raw.dependencyRepository().create(relatesDep(rel.id, relItem.id))
        val bb = rig.vwMake("bb", role = Role.BLOCKED)
        val bbDep = rig.vwMake("bbDep")
        rig.vwBlocks(bb, bbDep, "queue")
        return Eligibility(free, claimed, bq, depBlocked, satisfied, workOk, bq2, unmet, rel, relItem, bbDep)
    }

    @Test
    fun `S12 claimed and dependency-blocked items are excluded and the total counts only the eligible ones`(): Unit =
        irApp(db.db, dir) { rig ->
            val e = seedEligibility(rig)

            val r = select(next(""","limit":200"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(shorts(*e.eligible.toTypedArray()), r.ids(), "eligible: ${r.items.titles()}")
            assertEquals(7, r.total)
            for (excluded in listOf(e.claimed, e.depBlocked, e.unmet, e.bbDep)) {
                assertFalse(short(excluded.id) in r.ids(), "${excluded.title} must not be offered")
            }
            for (entry in r.items) assertFalse("isClaimed" in entry, "isClaimed is only present with includeClaimed: $entry")
            assertNull(r.reason(), "a non-empty page carries no reason")
            assertFalse("agent-x" in r.raw, "claimant identity is never listed")
        }

    @Test
    fun `S12 includeClaimed also lists claimed items, flagged, and dependency-blocked ones stay excluded`(): Unit =
        irApp(db.db, dir) { rig ->
            val e = seedEligibility(rig)

            val r = select(next(""","limit":200,"includeClaimed":true"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(shorts(e.claimed, *e.eligible.toTypedArray()), r.ids())
            assertEquals(8, r.total)
            for (entry in r.items) {
                val expected = entry.str("id") == short(e.claimed.id)
                assertEquals(
                    expected,
                    entry
                        .getValue("isClaimed")
                        .jsonPrimitive.content
                        .toBoolean(),
                    "isClaimed of ${entry.str("title")}"
                )
            }
            for (excluded in listOf(e.depBlocked, e.unmet, e.bbDep)) {
                assertFalse(short(excluded.id) in r.ids(), "${excluded.title} stays excluded by its dependency")
            }
            assertFalse("agent-x" in r.raw, "claimant identity is never listed")
            val off = select(next(""","limit":200,"includeClaimed":false"""))
            assertFalse(short(e.claimed.id) in off.ids(), "includeClaimed false is the default")
        }

    // ------------------------------------------------------------------ S13 empty reasons

    @Test
    fun `S13 an empty page whose matches are all claimed says claim_held`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("only-claimed", claimedBy = "agent-x")

            val r = select(next())

            assertEquals(200, r.status, r.raw)
            assertEquals(emptyList(), r.items)
            assertEquals(0, r.total)
            assertEquals("claim_held", r.reason(), r.raw)
        }

    @Test
    fun `S13 an empty page whose matches are all dependency-blocked says dependency_unmet`(): Unit =
        irApp(db.db, dir) { rig ->
            val blocker = rig.vwMake("blocker", role = Role.WORK)
            val waiting = rig.vwMake("waiting")
            rig.vwBlocks(blocker, waiting)

            val r = select(next())

            assertEquals(emptyList(), r.items, r.raw)
            assertEquals("dependency_unmet", r.reason(), r.raw)
        }

    @Test
    fun `S13 when claimed and dependency-blocked items both match, claim_held wins`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("claimed", claimedBy = "agent-x")
            val blocker = rig.vwMake("blocker", role = Role.WORK)
            val waiting = rig.vwMake("waiting")
            rig.vwBlocks(blocker, waiting)

            val r = select(next())

            assertEquals(emptyList(), r.items, r.raw)
            assertEquals("claim_held", r.reason(), r.raw)
        }

    @Test
    fun `S13 nothing matched gives an empty page with no reason`(): Unit =
        irApp(db.db, dir) { rig ->
            val empty = select(next())
            assertEquals(200, empty.status, empty.raw)
            assertEquals(emptyList(), empty.items)
            assertEquals(0, empty.total)
            assertNull(empty.reason(), "an empty database has no reason: ${empty.raw}")

            rig.vwMake("done", role = Role.TERMINAL)
            rig.vwMake("busy", role = Role.WORK, claimedBy = "agent-x")
            rig.vwMake("tagged", tags = "alpha")
            val filtered = select(next(""","tags":["no-such-tag"]"""))
            assertEquals(emptyList(), filtered.items)
            assertNull(filtered.reason(), "a filter that matches nothing has no reason: ${filtered.raw}")
        }

    @Test
    fun `S13 with includeClaimed a claimed match is offered, and only dependency-blocked matches leave dependency_unmet`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("claimed", claimedBy = "agent-x")
            val blocker = rig.vwMake("blocker", role = Role.WORK)
            val waiting = rig.vwMake("waiting")
            rig.vwBlocks(blocker, waiting)

            val withClaimed = select(next(""","includeClaimed":true"""))
            assertEquals(1, withClaimed.items.size, withClaimed.raw)
            assertEquals("claimed", withClaimed.items.single().str("title"))
            assertNull(withClaimed.reason(), "a non-empty page carries no reason")

            val onlyBlocked = select(next(""","includeClaimed":true,"tags":["x"]"""))
            assertNull(onlyBlocked.reason(), "no match for the tag, no reason")

            val tagged = rig.vwMake("tagged-waiting", tags = "w")
            rig.vwBlocks(blocker, tagged)
            val blockedOnly = select(next(""","includeClaimed":true,"tags":["w"]"""))
            assertEquals(emptyList(), blockedOnly.items, blockedOnly.raw)
            assertEquals("dependency_unmet", blockedOnly.reason(), blockedOnly.raw)
        }

    // ------------------------------------------------------------------ filters

    private class Tree(
        val parent: WorkItem,
        val k1: WorkItem,
        val k2: WorkItem,
        val k3: WorkItem,
        val grand: WorkItem,
        val outside: WorkItem
    )

    private suspend fun seedTree(rig: EventLogRig): Tree {
        val parent = rig.vwMake("P", role = Role.WORK)
        val k1 = rig.vwMake("k1", parent, priority = Priority.HIGH, tags = "alpha", type = "bug", complexity = 3, at = t0)
        val k2 = rig.vwMake("k2", parent, priority = Priority.LOW, tags = "beta", type = "feature", complexity = 8, at = t0.plusSeconds(60))
        val k3 = rig.vwMake("k3", parent, role = Role.WORK, priority = Priority.MEDIUM)
        val grand = rig.vwMake("grand", k1, priority = Priority.MEDIUM, tags = "alpha", type = "bug", at = t0.plusSeconds(90))
        val outside = rig.vwMake("outside", priority = Priority.HIGH, tags = "alpha", type = "bug", complexity = 1)
        return Tree(parent, k1, k2, k3, grand, outside)
    }

    @Test
    fun `S11 each filter narrows to exactly the matching items and filters combine with AND`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seedTree(rig)
            val scope = """"ancestorId":"${t.parent.id}""""

            suspend fun expect(
                label: String,
                extra: String,
                vararg want: WorkItem
            ) {
                val r = select(next(""","limit":200,$scope$extra"""))
                assertEquals(200, r.status, "$label: ${r.raw}")
                assertEquals(shorts(*want), r.ids(), "$label: ${r.items.titles()}")
                assertEquals(want.size, r.total, "$label total")
            }

            expect("queue by default, subtree of the ancestor", "", t.k1, t.k2, t.grand)
            expect("role work, the ancestor itself included", ""","role":"work"""", t.parent, t.k3)
            expect("priority high", ""","priority":"high"""", t.k1)
            expect("priority low", ""","priority":"low"""", t.k2)
            expect("type bug", ""","type":"bug"""", t.k1, t.grand)
            expect("tags any-of", ""","tags":["alpha"]""", t.k1, t.grand)
            expect("tags are case-insensitive", ""","tags":["ALPHA"]""", t.k1, t.grand)
            expect("tags are OR-combined", ""","tags":["alpha","beta"]""", t.k1, t.k2, t.grand)
            expect("tags with a duplicate entry", ""","tags":["beta","beta"]""", t.k2)
            expect("complexityMax 3 keeps k1 only and drops the item without a complexity", ""","complexityMax":3""", t.k1)
            expect("complexityMax 8 is inclusive", ""","complexityMax":8""", t.k1, t.k2)
            expect("complexityMax 2 keeps nothing", ""","complexityMax":2""")
            expect("AND of type and priority", ""","type":"bug","priority":"medium"""", t.grand)
            expect("createdBefore keeps the earlier items", ""","createdBefore":"2026-02-01T00:00:30Z"""", t.k1)
            expect("createdAfter keeps the later items", ""","createdAfter":"2026-02-01T00:00:30Z"""", t.k2, t.grand)
        }

    @Test
    fun `S11 parentId keeps only direct children and ancestorId the whole subtree`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seedTree(rig)

            val direct = select(next(""","limit":200,"parentId":"${t.parent.id}""""))
            val subtree = select(next(""","limit":200,"ancestorId":"${t.parent.id}""""))
            val byPrefix = select(next(""","limit":200,"parentId":"${short(t.parent.id)}""""))

            assertEquals(shorts(t.k1, t.k2), direct.ids(), direct.items.titles().toString())
            assertEquals(shorts(t.k1, t.k2, t.grand), subtree.ids(), subtree.items.titles().toString())
            assertEquals(direct.ids(), byPrefix.ids(), "a hex prefix resolves the same parent")
            assertFalse(short(t.outside.id) in subtree.ids())
            val everything = select(next(""","limit":200"""))
            assertTrue(short(t.outside.id) in everything.ids(), "control: unfiltered next offers the outside item")
        }

    @Test
    fun `S11 fields summary and ancestors add their members and an empty list adds nothing`(): Unit =
        irApp(db.db, dir) { rig ->
            val parent = rig.vwMake("P", role = Role.WORK)
            val child = rig.vwMake("C", parent, summary = "child summary")
            val bare = rig.vwMake("Bare", parent)

            val plain = select(next(""","limit":200,"parentId":"${parent.id}""""))
            val empty = select(next(""","limit":200,"parentId":"${parent.id}","fields":[]"""))
            val full = select(next(""","limit":200,"parentId":"${parent.id}","fields":["summary","ancestors"]"""))

            for (r in listOf(plain, empty)) {
                assertEquals(200, r.status, r.raw)
                for (entry in r.items) {
                    assertFalse("summary" in entry || "ancestors" in entry, "no opt-in member by default: $entry")
                }
            }
            assertEquals(200, full.status, full.raw)
            val c = full.items.single { it.str("id") == short(child.id) }
            assertEquals("child summary", c.str("summary"))
            assertEquals(listOf(short(parent.id)), c.arr("ancestors").map { it.jsonObject.str("id") })
            val b = full.items.single { it.str("id") == short(bare.id) }
            assertFalse("summary" in b, "an empty summary stays omitted: $b")
        }

    // ------------------------------------------------------------------ S14 validation

    @Test
    fun `S14 role terminal and unknown roles are invalid_request on role and work is accepted`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("w", role = Role.WORK)

            for (bad in listOf("terminal", "doing")) {
                val r = select(next(""","role":"$bad""""))
                assertEquals("invalid_request", r.code, "role $bad: ${r.raw}")
                assertEquals(400, r.status)
                assertTrue("/role" in r.fields(), "role $bad: ${r.fields()}")
            }
            assertEquals(200, select(next(""","role":"work"""")).status, "control: work is a valid role")
        }

    @Test
    fun `S14 limit outside 1 to 200 is invalid_request on limit and both bounds are accepted`(): Unit =
        irApp(db.db, dir) { rig ->
            repeat(3) { rig.vwMake("q$it") }

            for (bad in listOf("0", "201", "-1")) {
                val r = select(next(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertEquals(400, r.status)
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            assertEquals(1, select(next(""","limit":1""")).items.size)
            assertEquals(3, select(next(""","limit":200""")).items.size)
        }

    @Test
    fun `S14 a malformed cursor, enum, timestamp and type-mismatched tags are rejected on their own member`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("q")

            val cursor = select(next(""","cursor":"not-a-cursor""""))
            assertEquals("invalid_cursor", cursor.code, cursor.raw)
            assertEquals(400, cursor.status)

            for ((member, value) in listOf("orderBy" to "\"random\"", "priority" to "\"urgent\"", "createdAfter" to "\"yesterday\"")) {
                val r = select(next(""","$member":$value"""))
                assertEquals("invalid_request", r.code, "$member=$value: ${r.raw}")
                assertTrue("/$member" in r.fields(), "$member=$value: ${r.fields()}")
            }
            val commaTags = select(next(""","tags":"a,b""""))
            assertEquals("invalid_request", commaTags.code, "tags is an array, not a comma string (D-g): ${commaTags.raw}")
            assertTrue(commaTags.fields().any { it.startsWith("/tags") }, commaTags.fields().toString())
            val badFields = select(next(""","fields":["createdAt"]"""))
            assertEquals("invalid_request", badFields.code, badFields.raw)
            assertTrue(badFields.fields().any { it.startsWith("/fields") })
            val reasons = badFields.reasons().joinToString(" ")
            assertTrue("summary" in reasons && "ancestors" in reasons, "the allowed names are listed: $reasons")
        }

    @Test
    fun `D-g the dropped 3x parameters and members of other selects are unknown_parameter`(): Unit =
        irApp(db.db, dir) { rig ->
            rig.vwMake("q")

            for ((member, value) in listOf(
                "mode" to "\"item\"",
                "includeAncestors" to "true",
                "includeDetails" to "true",
                "itemId" to "\"abcd1234\""
            )) {
                val r = select(next(""","$member":$value"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters(), member)
            }
        }

    // ------------------------------------------------------------------ S16 scope

    @Test
    fun `S16 a tag scope offers and counts only items carrying a listed tag exactly, with no ancestor walk`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val v1 = rig.vwMake("v1", tags = "api")
            val v2 = rig.vwMake("v2", tags = "backend,api")
            val parent = rig.vwMake("tagged-parent", role = Role.WORK, tags = "api")
            rig.vwMake("untagged-child", parent)
            rig.vwMake("prefix-lookalike", tags = "apix")
            rig.vwMake("plain")

            val scoped = select(next(""","limit":200"""), TEST_TOKEN)
            val open = select(next(""","limit":200"""))

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(shorts(v1, v2), scoped.ids(), scoped.items.titles().toString())
            assertEquals(2, scoped.total, "the total counts only visible items")
            assertEquals(5, open.total, "control: the unrestricted caller sees all five queue items")
            for (hidden in listOf("untagged-child", "prefix-lookalike", "plain")) {
                assertFalse(hidden in scoped.raw, "no trace of $hidden: ${scoped.raw}")
            }
        }

    @Test
    fun `S16 a root scope offers only the subtree of the listed root`() {
        val r1 = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
        val r2 = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
        irApp(db.db, dir, scopeRootIds = setOf(r1)) { rig ->
            val root1 = rig.vwMake("Mine", id = r1)
            val own = rig.vwMake("Mine-child", root1)
            val root2 = rig.vwMake("Theirs", id = r2)
            rig.vwMake("Theirs-child", root2)

            val scoped = select(next(""","limit":200"""), TEST_TOKEN)

            assertEquals(shorts(root1, own), scoped.ids(), scoped.items.titles().toString())
            assertEquals(2, scoped.total)
            assertFalse("Theirs" in scoped.raw, scoped.raw)
            assertEquals(4, select(next(""","limit":200""")).total, "control: unrestricted")
        }
    }

    @Test
    fun `S16 the empty-page reason is computed over the visible items only`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            rig.vwMake("hidden-claimed", claimedBy = "agent-hidden")
            val hiddenBlocker = rig.vwMake("hidden-blocker", role = Role.WORK)
            val hiddenWaiting = rig.vwMake("hidden-waiting")
            rig.vwBlocks(hiddenBlocker, hiddenWaiting)

            val scoped = select(next(), TEST_TOKEN)
            val open = select(next())

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(emptyList(), scoped.items)
            assertNull(scoped.reason(), "nothing visible matched, so no reason (and no hint of hidden claims): ${scoped.raw}")
            assertFalse("agent-hidden" in scoped.raw, scoped.raw)
            assertEquals("claim_held", open.reason(), "control: the unrestricted caller gets the reason: ${open.raw}")
        }

    @Test
    fun `S16 a visible item with a hidden unmet blocker stays excluded and its reason is dependency_unmet`(): Unit =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val hiddenBlocker = rig.vwMake("hidden-blocker", role = Role.WORK)
            val waiting = rig.vwMake("waiting", tags = "api")
            rig.vwBlocks(hiddenBlocker, waiting)

            val scoped = select(next(), TEST_TOKEN)

            assertEquals(emptyList(), scoped.items, "satisfaction is computed on the true role of the hidden blocker: ${scoped.raw}")
            assertEquals("dependency_unmet", scoped.reason(), scoped.raw)
            assertFalse(short(hiddenBlocker.id) in scoped.raw || "hidden-blocker" in scoped.raw, scoped.raw)
        }
}
