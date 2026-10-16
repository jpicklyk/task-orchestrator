package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for `items_read` select `list`: scenarios S3, S18 and the list part of
 * S13 of the frozen test-plan (NEW-SURFACE), through the production REST topology.
 *
 * Oracles: task-scope item 5 (filters `parentId, ancestorId, depth, role, priority, tags, traits, type, claimStatus` and the
 * six time bounds; `sort` enum `title|priority|complexity|createdAt|modifiedAt`, `order` `asc|desc`; default limit 50, max
 * 200; inverted range and bad timestamp are `invalid_request` with the bound's pointer; bad cursor is `invalid_cursor`;
 * compact entries with 8-hex short ids and `total` from the same unit), item 3 (`fields` additive, unknown name is
 * `invalid_request` listing the allowed names), the parameter docs (tags match case-insensitively, any of; traits exact, any
 * of; ancestorId includes the item itself; depth 0 = roots), D2 (a full UUID filter that names nothing is an ok empty page),
 * D8 (`isClaimed` is dropped; claimant identity is never listed) and the envelope (`data {items, page {total, truncated,
 * next}}`). Expected values are literals computed from the seed.
 *
 * Open question F1 (escalated in the plan): the last page of a list may still carry `next`; these tests never assert that
 * `next` is absent at the end, only that following it while items remain yields disjoint pages whose union is the match set.
 * Not asserted (no oracle): the default order (createdAt desc ties on equal timestamps), the priority sort order, `tags: [""]`,
 * and a cursor taken from another select. Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadListTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private val shortId = Regex("^[0-9a-f]{8}$")

    private class Tree(
        val parent: WorkItem,
        val other: WorkItem,
        val a: WorkItem,
        val b: WorkItem,
        val c: WorkItem,
        val d: WorkItem,
        val e: WorkItem,
        val f: WorkItem,
        val g: WorkItem
    ) {
        val kids get() = listOf(a, b, c, d, e, f, g)
    }

    private suspend fun seed(rig: EventLogRig): Tree {
        val parent = rig.make("Parent")
        val other = rig.make("Other")
        val a =
            rig.make(
                "item-a",
                parent,
                Role.QUEUE,
                "alpha",
                priority = Priority.HIGH,
                type = "bug",
                traits = listOf("t1"),
                summary = "has-summary"
            )
        val b = rig.make("item-b", parent, Role.WORK, "beta,alpha", priority = Priority.MEDIUM)
        val c = rig.make("item-c", parent, Role.WORK, "gamma", priority = Priority.LOW)
        val d = rig.make("item-d", parent, Role.QUEUE, null, priority = Priority.HIGH, type = "feature")
        val e = rig.make("item-e", parent, Role.TERMINAL, null, priority = Priority.MEDIUM)
        val f = rig.make("item-f", parent, Role.QUEUE, null, priority = Priority.LOW, traits = listOf("t2"))
        val g = rig.make("item-g", parent, Role.QUEUE, null, priority = Priority.MEDIUM, claimedBy = "agent-x")
        return Tree(parent, other, a, b, c, d, e, f, g)
    }

    private fun list(extra: String) = """{"select":"list"$extra}"""

    private fun shorts(vararg items: WorkItem): Set<String> = items.map { short(it.id) }.toSet()

    private fun Reply.shortIds(): Set<String> = items.map { it.str("id") }.toSet()

    // ------------------------------------------------------------------ S3

    @Test
    fun `S3 children of a parent are compact entries with 8-hex short ids and the exact total`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)

            val r = select(list(""","parentId":"${t.parent.id}""""))

            assertEquals(200, r.status)
            assertEquals(7, r.total)
            assertEquals(shorts(*t.kids.toTypedArray()), r.shortIds())
            for (entry in r.items) {
                assertTrue(shortId.matches(entry.str("id")), "short id: ${entry.str("id")}")
                assertEquals(short(t.parent.id), entry.str("parentId"))
                for (member in listOf("type", "title", "role", "priority", "depth", "version")) {
                    assertTrue(member in entry, "compact entry has $member: $entry")
                }
                assertFalse("summary" in entry, "summary is opt-in via fields: $entry")
                assertFalse("isClaimed" in entry, "3.x isClaimed is dropped (D8)")
            }
            assertFalse("agent-x" in r.raw, "claimant identity is never listed")
            val b = r.items.single { it.str("title") == "item-b" }
            assertEquals(listOf("beta", "alpha"), b.strings("tags"))
            val a = r.items.single { it.str("title") == "item-a" }
            assertEquals(listOf("t1"), a.strings("traits"))
        }

    @Test
    fun `S3 pages are disjoint and their union is every match, with a stable total`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)
            val expected = shorts(*t.kids.toTypedArray())
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0

            while (seen.size < 7 && pages < 10) {
                val extra =
                    ""","parentId":"${t.parent.id}","limit":3,"sort":"title","order":"asc"""" +
                        (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(list(extra))
                assertEquals(200, r.status, r.raw)
                assertEquals(7, r.total, "total is the same on every page")
                assertTrue(r.items.size <= 3)
                if (seen.size + r.items.size < 7) {
                    assertTrue(r.page.getValue("truncated").toString() == "true", "more items remain: ${r.page}")
                    cursor = assertNotNull(r.next, "a truncated page carries next")
                }
                seen += r.items.map { it.str("id") }
                pages++
            }

            assertEquals(3, pages)
            assertEquals(seen.distinct(), seen, "no item appears on two pages")
            assertEquals(expected, seen.toSet())
        }

    @Test
    fun `S3 sort title asc and desc order the whole set`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)
            val parent = """"parentId":"${t.parent.id}""""

            val asc = select("""{"select":"list",$parent,"sort":"title","order":"asc"}""")
            val desc = select("""{"select":"list",$parent,"sort":"title","order":"desc"}""")

            assertEquals(listOf("item-a", "item-b", "item-c", "item-d", "item-e", "item-f", "item-g"), asc.items.titles())
            assertEquals(listOf("item-g", "item-f", "item-e", "item-d", "item-c", "item-b", "item-a"), desc.items.titles())
        }

    @Test
    fun `S3 each filter narrows to exactly the matching items`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)
            val p = """"parentId":"${t.parent.id}""""

            suspend fun expect(
                label: String,
                extra: String,
                vararg want: WorkItem
            ) {
                val r = select("""{"select":"list",$p$extra}""")
                assertEquals(200, r.status, "$label: ${r.raw}")
                assertEquals(shorts(*want), r.shortIds(), label)
                assertEquals(want.size, r.total, "$label total")
            }

            expect("role work", ""","role":"work"""", t.b, t.c)
            expect("priority high", ""","priority":"high"""", t.a, t.d)
            expect("tags any-of", ""","tags":["alpha"]""", t.a, t.b)
            expect("tags are case-insensitive (filter)", ""","tags":["ALPHA"]""", t.a, t.b)
            expect("tags are case-insensitive for every tag", ""","tags":["GaMMa"]""", t.c)
            expect("tags are OR-combined", ""","tags":["alpha","gamma"]""", t.a, t.b, t.c)
            expect("traits any-of, exact", ""","traits":["t1","t2"]""", t.a, t.f)
            expect("traits are exact (case)", ""","traits":["T1"]""")
            expect("type", ""","type":"bug"""", t.a)
            expect("claimed", ""","claimStatus":"claimed"""", t.g)
            expect("unclaimed", ""","claimStatus":"unclaimed"""", t.a, t.b, t.c, t.d, t.e, t.f)
            expect("expired (control: nothing expired)", ""","claimStatus":"expired"""")
            expect("filters are AND-combined", ""","role":"queue","priority":"high"""", t.a, t.d)
            expect("depth 1 under the parent", ""","depth":1""", *t.kids.toTypedArray())
        }

    @Test
    fun `S3 ancestorId lists the subtree including the item itself and depth 0 lists the roots`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)

            val subtree = select(list(""","ancestorId":"${t.parent.id}""""))
            assertEquals(shorts(t.parent, *t.kids.toTypedArray()), subtree.shortIds())
            assertEquals(8, subtree.total)

            val roots = select(list(""","depth":0"""))
            assertEquals(shorts(t.parent, t.other), roots.shortIds())

            val all = select(list(""))
            assertEquals(9, all.total)
        }

    @Test
    fun `S3 time bounds keep everything for a past lower bound and nothing for a future one`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (name in listOf("created", "modified", "roleChanged")) {
                val past = select(list(""","${name}After":"2000-01-01T00:00:00Z""""))
                assertEquals(9, past.total, "${name}After in the past keeps all: ${past.raw}")
                val future = select(list(""","${name}After":"2999-01-01T00:00:00Z""""))
                assertEquals(0, future.total, "${name}After in the future keeps none")
                val beforePast = select(list(""","${name}Before":"2000-01-01T00:00:00Z""""))
                assertEquals(0, beforePast.total, "${name}Before in the past keeps none")
                val beforeFuture = select(list(""","${name}Before":"2999-01-01T00:00:00Z""""))
                assertEquals(9, beforeFuture.total, "${name}Before in the future keeps all")
            }
        }

    @Test
    fun `S3 fields summary adds summary to entries that have one and nothing else`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)

            val r = select(list(""","parentId":"${t.parent.id}","fields":["summary"]"""))

            val a = r.items.single { it.str("title") == "item-a" }
            assertEquals("has-summary", a.str("summary"))
            val d = r.items.single { it.str("title") == "item-d" }
            assertFalse("summary" in d, "an empty summary stays omitted")
            assertFalse("createdAt" in a)
        }

    @Test
    fun `S3 fields createdAt modifiedAt roleChangedAt ancestors are accepted and add their members`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)

            val r = select(list(""","parentId":"${t.parent.id}","fields":["createdAt","modifiedAt","roleChangedAt","ancestors"]"""))

            assertEquals(200, r.status, r.raw)
            for (entry in r.items) {
                for (member in listOf("createdAt", "modifiedAt", "roleChangedAt")) {
                    java.time.Instant.parse(entry.str(member))
                }
                assertEquals(
                    listOf(short(t.parent.id)),
                    entry.arr("ancestors").map { (it as kotlinx.serialization.json.JsonObject).str("id") }
                )
            }
        }

    @Test
    fun `S3 the default page size is 50 and limit 200 returns up to 200`(): Unit =
        irApp(db.db, dir) { rig ->
            val box = rig.make("Box")
            repeat(55) { rig.make("many-$it", box) }

            val default = select(list(""","parentId":"${box.id}""""))
            assertEquals(50, default.items.size)
            assertEquals(55, default.total)
            assertNotNull(default.next)

            val big = select(list(""","parentId":"${box.id}","limit":200"""))
            assertEquals(55, big.items.size)
            assertEquals(55, big.total)
        }

    // ------------------------------------------------------------------ S18

    @Test
    fun `S18 a full-UUID parentId that names nothing is an ok empty page`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            val r = select(list(""","parentId":"99999999-9999-4999-8999-999999999999""""))

            assertEquals(200, r.status)
            assertTrue(r.ok, r.raw)
            assertEquals(emptyList(), r.items)
            assertEquals(0, r.total)
        }

    // ------------------------------------------------------------------ S13 (list)

    @Test
    fun `S13 limit outside 1 to 200 is invalid_request on limit and the bounds are accepted`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (bad in listOf("201", "0", "-1")) {
                val r = select(list(""","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertEquals(400, r.status)
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            assertEquals(200, select(list(""","limit":200""")).status, "control: 200 is the maximum")
            assertEquals(1, select(list(""","limit":1""")).items.size, "control: 1 is the minimum")
        }

    @Test
    fun `S13 enum members outside their set are invalid_request on that member`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            val cases =
                listOf(
                    "role" to """"doing"""",
                    "priority" to """"urgent"""",
                    "sort" to """"created"""",
                    "order" to """"up"""",
                    "claimStatus" to """"mine""""
                )
            for ((member, value) in cases) {
                val r = select(list(""","$member":$value"""))
                assertEquals("invalid_request", r.code, "$member=$value: ${r.raw}")
                assertTrue("/$member" in r.fields(), "$member=$value: ${r.fields()}")
            }
            assertEquals(200, select(list(""","sort":"modifiedAt","order":"asc"""")).status, "control: valid enum values")
        }

    @Test
    fun `S13 an inverted time range is invalid_request on the lower bound and a bad timestamp on its own member`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (name in listOf("created", "modified", "roleChanged")) {
                val inverted = select(list(""","${name}After":"2026-02-01T00:00:00Z","${name}Before":"2026-01-01T00:00:00Z""""))
                assertEquals("invalid_request", inverted.code, "$name: ${inverted.raw}")
                assertTrue("/${name}After" in inverted.fields(), "$name: ${inverted.fields()}")
            }
            val bad = select(list(""","createdAfter":"yesterday""""))
            assertEquals("invalid_request", bad.code)
            assertTrue("/createdAfter" in bad.fields(), bad.raw)
        }

    @Test
    fun `S13 a malformed cursor is invalid_cursor`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (cursor in listOf("not-a-cursor", "!!!")) {
                val r = select(list(""","cursor":"$cursor""""))
                assertEquals("invalid_cursor", r.code, "'$cursor': ${r.raw}")
                assertEquals(400, r.status)
            }
        }

    @Test
    fun `S13 unknown members and members of other selects are unknown_parameter`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (member in listOf("includeTimestamps", "query", "anchorId", "kinds", "ifVersion", "isClaimed")) {
                val r = select(list(""","$member":1"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters())
            }
        }

    @Test
    fun `S13 an unknown fields name is invalid_request on fields listing the five allowed names`(): Unit =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (bad in listOf("bogus", "guidance")) {
                val r = select(list(""","fields":["$bad"]"""))
                assertEquals("invalid_request", r.code, bad)
                assertTrue(r.fields().any { it.startsWith("/fields") }, "$bad: ${r.fields()}")
                val reasons = r.reasons().joinToString(" ")
                for (allowed in listOf("summary", "createdAt", "modifiedAt", "roleChangedAt", "ancestors")) {
                    assertTrue(allowed in reasons, "$bad: the allowed name $allowed is listed: $reasons")
                }
            }
        }

    @Test
    fun `S13 id filters accept a UUID or a hex prefix and reject malformed ids on their own pointer`(): Unit =
        irApp(db.db, dir) { rig ->
            val t = seed(rig)

            val byPrefix = select(list(""","parentId":"${short(t.parent.id)}""""))
            assertEquals(7, byPrefix.total, byPrefix.raw)

            for (member in listOf("parentId", "ancestorId")) {
                val r = select(list(""","$member":"xyz""""))
                assertEquals("invalid_request", r.code, "$member: ${r.raw}")
                assertTrue("/$member" in r.fields(), "$member: ${r.fields()}")
            }
        }
}
