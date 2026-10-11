package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for `items_read` select `search`: scenarios S5, the search part of S11
 * and S13, and the search probes of the frozen test-plan (NEW-SURFACE), through the production REST topology.
 *
 * Oracles: task-scope item 7 (`kinds` is a non-empty subset of `item|note`, default both; `matchMode` enum
 * `auto|substring|text`; limit default 20, max 100; a blank query is `invalid_request` on `/query`; the access scope is the
 * caller's; a hit has `kind`, `itemId` (short), `noteKey`, `title`, `field`, `snippet` unless `snippet:false`, `score`,
 * `matchedIn`, and `explain {trigramRank, textRank, rrfK}` only with `explain:true`; `page.total` is the total hits and
 * `page.capped` marks a truncated result, so `total` is then a lower bound), item 8 (item and note corpora are queried
 * together and fused in one ranking), the parameter docs (query terms are matched literally; `tags` case-insensitive any-of;
 * `traits` exact any-of; `role` filters the owning item's role; `ancestorId` includes the item itself), D2 (filter ids are
 * not scope-guarded: out-of-scope rows simply drop out, so a filter naming an out-of-scope item is an ok empty page) and D4
 * (search results and totals count visible items only). Expected values are literals from the seed.
 *
 * Not asserted (no oracle): the values of `field` and `matchedIn`, whether duplicate `kinds` entries are rejected or merged,
 * and a cursor taken from another select. Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadSearchTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private fun search(extra: String) = """{"select":"search"$extra}"""

    private suspend fun EventLogRig.note(
        item: WorkItem,
        key: String,
        body: String,
        role: String = "work"
    ) {
        raw.noteRepository().upsert(Note(itemId = item.id, key = key, role = role, body = body))
    }

    private class Corpus(
        val title: WorkItem,
        val summary: WorkItem,
        val noted: WorkItem,
        val other: WorkItem,
        val deep: WorkItem,
        val deepParent: WorkItem
    )

    /** Four contents match "zephyrquux": a title, a summary, and two note bodies. */
    private suspend fun seed(rig: EventLogRig): Corpus {
        val title = rig.make("Zephyrquux alpha", tags = "api")
        val summary = rig.make("Plain item", summary = "contains zephyrquux inside")
        val noted = rig.make("Noted")
        rig.note(noted, "n1", "the zephyrquux note body")
        val other = rig.make("Unrelated item")
        rig.note(other, "n0", "nothing relevant here")
        val deepParent = rig.make("Deep parent")
        val deep = rig.make("Wolfram", deepParent, Role.WORK, traits = listOf("t1"))
        rig.note(deep, "n2", "another zephyrquux body", role = "queue")
        return Corpus(title, summary, noted, other, deep, deepParent)
    }

    private fun Reply.pairs(): Set<Pair<String, String>> = items.map { it.str("kind") to it.str("itemId") }.toSet()

    // ------------------------------------------------------------------ S5

    @Test
    fun `S5 the default search fuses item and note hits and totals them`() =
        irApp(db.db, dir) { rig ->
            val c = seed(rig)

            val r = select(search(""","query":"zephyrquux""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(
                setOf(
                    "item" to short(c.title.id),
                    "item" to short(c.summary.id),
                    "note" to short(c.noted.id),
                    "note" to short(c.deep.id)
                ),
                r.pairs()
            )
            assertEquals(4, r.total)
            for (hit in r.items) {
                assertTrue(Regex("^[0-9a-f]{8}$").matches(hit.str("itemId")), "short itemId: $hit")
                for (member in listOf("title", "field", "score", "matchedIn")) assertTrue(member in hit, "hit has $member: $hit")
                if (hit.str("kind") == "note") {
                    assertTrue(hit.str("noteKey") in setOf("n1", "n2"), "note hit names its note key: $hit")
                } else {
                    assertFalse("noteKey" in hit, "an item hit has no noteKey: $hit")
                }
                assertFalse("explain" in hit, "explain is opt-in: $hit")
            }
            val noteHit = r.items.single { it.str("kind") == "note" && it.str("itemId") == short(c.noted.id) }
            assertEquals("n1", noteHit.str("noteKey"))
            assertEquals("Noted", noteHit.str("title"), "a note hit carries the owning item's title")
        }

    @Test
    fun `S5 kinds note limits hits to notes and kinds item to items`() =
        irApp(db.db, dir) { rig ->
            val c = seed(rig)

            val notes = select(search(""","query":"zephyrquux","kinds":["note"]"""))
            assertEquals(setOf("note" to short(c.noted.id), "note" to short(c.deep.id)), notes.pairs())
            assertEquals(2, notes.total)

            val items = select(search(""","query":"zephyrquux","kinds":["item"]"""))
            assertEquals(setOf("item" to short(c.title.id), "item" to short(c.summary.id)), items.pairs())
            assertEquals(2, items.total)

            val both = select(search(""","query":"zephyrquux","kinds":["note","item"]"""))
            assertEquals(4, both.total)
        }

    @Test
    fun `S5 substring mode finds a fragment of a term and text mode finds the whole term`() =
        irApp(db.db, dir) { rig ->
            val c = seed(rig)

            val substring = select(search(""","query":"phyrquu","matchMode":"substring""""))
            assertEquals(200, substring.status, substring.raw)
            assertEquals(4, substring.total, "a fragment matches every content that holds it: ${substring.raw}")

            val text = select(search(""","query":"zephyrquux","matchMode":"text""""))
            assertEquals(200, text.status, text.raw)
            assertTrue("item" to short(c.title.id) in text.pairs(), "text mode finds the title: ${text.raw}")
        }

    @Test
    fun `S5 snippet is on by default and absent with snippet false, explain is opt-in`() =
        irApp(db.db, dir) { rig ->
            seed(rig)

            val plain = select(search(""","query":"zephyrquux""""))
            for (hit in plain.items) {
                assertTrue(hit.str("snippet").contains("zephyrquux", ignoreCase = true), "snippet shows the match: $hit")
            }

            val noSnippet = select(search(""","query":"zephyrquux","snippet":false"""))
            assertEquals(4, noSnippet.total)
            for (hit in noSnippet.items) assertFalse("snippet" in hit, "snippet:false drops it: $hit")

            val explained = select(search(""","query":"zephyrquux","explain":true,"snippet":false"""))
            for (hit in explained.items) {
                val explain = assertNotNull(hit["explain"], "explain:true adds explain: $hit").jsonObject
                assertTrue("rrfK" in explain, "explain carries rrfK: $explain")
            }
        }

    @Test
    fun `S5 itemId ancestorId role tags and traits filters narrow the hits`() =
        irApp(db.db, dir) { rig ->
            val c = seed(rig)

            val byItem = select(search(""","query":"zephyrquux","itemId":"${c.noted.id}""""))
            assertEquals(setOf("note" to short(c.noted.id)), byItem.pairs())

            val byPrefix = select(search(""","query":"zephyrquux","itemId":"${short(c.noted.id)}""""))
            assertEquals(setOf("note" to short(c.noted.id)), byPrefix.pairs())

            val byAncestor = select(search(""","query":"zephyrquux","ancestorId":"${c.deepParent.id}""""))
            assertEquals(setOf("note" to short(c.deep.id)), byAncestor.pairs())

            val byRole = select(search(""","query":"zephyrquux","role":"work""""))
            assertEquals(setOf("note" to short(c.deep.id)), byRole.pairs(), "role filters the owning item's role")

            val byTag = select(search(""","query":"zephyrquux","tags":["API"]"""))
            assertEquals(setOf("item" to short(c.title.id)), byTag.pairs(), "tags are case-insensitive")

            val byTrait = select(search(""","query":"zephyrquux","traits":["t1"]"""))
            assertEquals(setOf("note" to short(c.deep.id)), byTrait.pairs())

            val wrongCase = select(search(""","query":"zephyrquux","traits":["T1"]"""))
            assertEquals(0, wrongCase.total, "traits are exact")
        }

    @Test
    fun `S5 paging walks every hit exactly once`() =
        irApp(db.db, dir) { rig ->
            repeat(12) { rig.make("pagetoken entry $it") }
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0

            while (seen.size < 12 && pages < 10) {
                val extra = ""","query":"pagetoken","limit":5""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(search(extra))
                assertEquals(200, r.status, r.raw)
                assertEquals(12, r.total)
                seen += r.items.map { it.str("itemId") }
                if (seen.size < 12) cursor = assertNotNull(r.next, "more hits remain, so next is present: ${r.page}")
                pages++
            }

            assertEquals(3, pages)
            assertEquals(12, seen.size)
            assertEquals(12, seen.toSet().size, "no hit appears on two pages")
        }

    @Test
    fun `S5 more than 100 matches marks the page capped and totals a lower bound, fewer does not`() =
        irApp(db.db, dir) { rig ->
            repeat(130) { rig.make("capword entry $it") }
            repeat(12) { rig.make("fewword entry $it") }

            val capped = select(search(""","query":"capword","limit":100"""))
            assertEquals(200, capped.status, capped.raw)
            assertEquals(100, capped.items.size)
            assertTrue(
                capped.page
                    .getValue("capped")
                    .jsonPrimitive.boolean,
                "over the cap: ${capped.page}"
            )
            assertTrue(capped.total in 100..130, "total is a lower bound of the 130 matches, not above it: ${capped.total}")

            val few = select(search(""","query":"fewword","limit":100"""))
            assertEquals(12, few.total)
            assertTrue(few.page["capped"]?.jsonPrimitive?.boolean != true, "12 matches are not capped: ${few.page}")
        }

    @Test
    fun `S5 a query with search syntax characters is matched literally and never fails`() =
        irApp(db.db, dir) { rig ->
            seed(rig)

            for (query in listOf("zephyr*", "\"zephyrquux", "zephyrquux OR", "NEAR(", "-zephyrquux", "a:b", "zephyrquux'")) {
                val escaped = query.replace("\"", "\\\"")
                val r = select(search(""","query":"$escaped""""))
                assertEquals(200, r.status, "query [$query]: ${r.raw}")
            }
            assertEquals(0, select(search(""","query":"zephyr*"""")).total, "the star is a literal character, not a wildcard")
        }

    // ------------------------------------------------------------------ S11 (search)

    @Test
    fun `S11 under a tag scope search returns, counts and shows only visible items and their notes`() =
        irApp(db.db, dir, tagsInclude = setOf("api")) { rig ->
            val visible = rig.make("scopetoken visible", tags = "api")
            rig.note(visible, "v1", "scopetoken visible note")
            val hidden = rig.make("scopetoken hidden", tags = "beta")
            rig.note(hidden, "h1", "scopetoken hidden note")
            val lookalike = rig.make("scopetoken lookalike", tags = "ap")
            rig.note(lookalike, "l1", "scopetoken lookalike note")
            val untagged = rig.make("scopetoken untagged")

            val scoped = select(search(""","query":"scopetoken""""), TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(setOf("item" to short(visible.id), "note" to short(visible.id)), scoped.pairs())
            assertEquals(2, scoped.total, "the total counts visible hits only")
            for (leak in listOf("hidden", "lookalike", "untagged", short(hidden.id), short(lookalike.id), short(untagged.id))) {
                assertFalse(leak in scoped.raw, "out-of-scope content '$leak' must not appear: ${scoped.raw}")
            }

            val control = select(search(""","query":"scopetoken""""))
            assertEquals(7, control.total, "control: the unscoped caller sees all four items and three notes: ${control.raw}")
        }

    @Test
    fun `S11 under a root scope search covers the scoped subtree only`() =
        irApp(db.db, dir, scopeRootIds = setOf(ROOT_A)) { rig ->
            val rootA = rig.make("rootword A", id = ROOT_A)
            val inA = rig.make("rootword inside A", rootA)
            rig.note(inA, "a1", "rootword note inside A")
            val rootB = rig.make("rootword B", id = ROOT_B)
            val inB = rig.make("rootword inside B", rootB)
            rig.note(inB, "b1", "rootword note inside B")

            val scoped = select(search(""","query":"rootword""""), TEST_TOKEN)

            assertEquals(setOf("item" to short(rootA.id), "item" to short(inA.id), "note" to short(inA.id)), scoped.pairs())
            assertEquals(3, scoped.total)
            assertFalse(
                "rootword B" in scoped.raw || "rootword inside B" in scoped.raw || "rootword note inside B" in scoped.raw,
                scoped.raw
            )
            assertFalse(short(rootB.id) in scoped.raw || short(inB.id) in scoped.raw, scoped.raw)

            assertEquals(6, select(search(""","query":"rootword"""")).total, "control: unscoped sees both subtrees")
        }

    @Test
    fun `S11 an itemId or ancestorId filter that names an out-of-scope item is an ok empty page`() =
        irApp(db.db, dir, scopeRootIds = setOf(ROOT_A)) { rig ->
            rig.make("filterword A", id = ROOT_A)
            val rootB = rig.make("filterword B", id = ROOT_B)
            rig.make("filterword inside B", rootB)

            for (filter in listOf(""""itemId":"${rootB.id}"""", """"ancestorId":"${rootB.id}"""")) {
                val r = select(search(""","query":"filterword",$filter"""), TEST_TOKEN)
                assertEquals(200, r.status, "$filter: ${r.raw}")
                assertEquals(0, r.total, filter)
                assertEquals(emptyList<JsonObject>(), r.items, filter)
            }
            assertTrue(
                select(search(""","query":"filterword","ancestorId":"${rootB.id}"""")).total >= 2,
                "control: unscoped sees B's subtree"
            )
        }

    // ------------------------------------------------------------------ S13 (search)

    @Test
    fun `S13 a blank or missing query is invalid_request on query`() =
        irApp(db.db, dir) { rig ->
            rig.make("Anything")

            for (body in listOf(search(""","query":"""""), search(""","query":"   """"), search(""))) {
                val r = select(body)
                assertEquals("invalid_request", r.code, "$body: ${r.raw}")
                assertEquals(400, r.status)
                assertTrue("/query" in r.fields(), "$body: ${r.fields()}")
            }
        }

    @Test
    fun `S13 limit above 100 or below 1 is invalid_request on limit and 100 is accepted`() =
        irApp(db.db, dir) { rig ->
            rig.make("limitword")

            for (bad in listOf("101", "0", "-1")) {
                val r = select(search(""","query":"limitword","limit":$bad"""))
                assertEquals("invalid_request", r.code, "limit $bad: ${r.raw}")
                assertTrue("/limit" in r.fields(), "limit $bad: ${r.fields()}")
            }
            assertEquals(200, select(search(""","query":"limitword","limit":100""")).status)
        }

    @Test
    fun `S13 empty or unknown kinds and an unknown matchMode are invalid_request`() =
        irApp(db.db, dir) { rig ->
            rig.make("kindword")

            val empty = select(search(""","query":"kindword","kinds":[]"""))
            assertEquals("invalid_request", empty.code, empty.raw)
            assertTrue(empty.fields().any { it.startsWith("/kinds") }, empty.fields().toString())

            val unknown = select(search(""","query":"kindword","kinds":["doc"]"""))
            assertEquals("invalid_request", unknown.code, unknown.raw)
            assertTrue(unknown.fields().any { it.startsWith("/kinds") }, unknown.fields().toString())

            val mode = select(search(""","query":"kindword","matchMode":"fuzzy""""))
            assertEquals("invalid_request", mode.code, mode.raw)
            assertTrue("/matchMode" in mode.fields(), mode.fields().toString())
        }

    @Test
    fun `S13 unknown members and members of other selects are unknown_parameter, a malformed cursor is invalid_cursor`() =
        irApp(db.db, dir) { rig ->
            rig.make("strictword")

            for (member in listOf("fields", "parentId", "includeChildren", "type", "ifVersion", "bogus")) {
                val r = select(search(""","query":"strictword","$member":1"""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters())
            }
            val cursor = select(search(""","query":"strictword","cursor":"not-a-cursor""""))
            assertEquals("invalid_cursor", cursor.code, cursor.raw)
            assertEquals(400, cursor.status)
        }

    companion object {
        private val ROOT_A: UUID = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
        private val ROOT_B: UUID = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
    }
}
