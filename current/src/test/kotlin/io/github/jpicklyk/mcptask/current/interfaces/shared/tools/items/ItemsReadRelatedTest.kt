package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.Link
import io.github.jpicklyk.mcptask.current.domain.model.LinkRelation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpToolAdapter
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildMcpTools
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.mcp.closeInMemoryPair
import io.github.jpicklyk.mcptask.current.interfaces.mcp.inMemoryTestServerOptions
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolProfile
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.CallLogRows
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ClientCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.buildCallToolRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
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
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

private val REL_ROOT_A: UUID = UUID.fromString("a1a1a1a1-0000-4000-8000-0000000000a1")
private val REL_ROOT_B: UUID = UUID.fromString("b2b2b2b2-0000-4000-8000-0000000000b2")

private fun relUid(n: Int): UUID = UUID.fromString("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))

private fun relatedBody(
    itemId: Any,
    extra: String = ""
): String = """{"select":"related","itemId":"$itemId"$extra}"""

private fun <T> Outcome<T>.relValue(): T =
    when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> fail("fixture: expected Ok but was Err: ${error.code} ${error.message}")
    }

private suspend fun EventLogRig.relDoc(
    owner: WorkItem,
    slug: String,
    title: String? = null,
    body: String = "An ordinary body without special words"
): Document = DocumentService(raw, db.unitOfWork()).write(owner.id, DocumentKind.PLAN, slug, body, title = title).relValue()

private suspend fun EventLogRig.relNote(
    item: WorkItem,
    body: String,
    key: String = "k"
): Note = checkNotNull(raw.noteRepository().upsert(Note(itemId = item.id, key = key, role = "work", body = body)))

private suspend fun EventLogRig.relDep(
    from: WorkItem,
    to: WorkItem,
    type: DependencyType = DependencyType.RELATES_TO
) {
    raw.dependencyRepository().create(Dependency(fromItemId = from.id, toItemId = to.id, type = type))
}

private suspend fun EventLogRig.relLink(
    root: WorkItem,
    from: EntityRef,
    to: EntityRef,
    relation: LinkRelation = LinkRelation.REFERENCES,
    toRef: String? = null
) {
    raw.linkStore().insert(Link(UUID.randomUUID(), root.id, from, to, toRef, relation, null, Instant.now()))
}

private fun Reply.titlesOf(): List<String?> = items.map { it["title"]?.jsonPrimitive?.content }

private fun Reply.score(index: Int): Double = items[index].getValue("score").jsonPrimitive.double

/**
 * Independent tests (test-author seat, item 630a826a) for the wire of `items_read` select `related`, through the production
 * REST topology (`POST /api/v1/ops/items_read` with a real bearer principal): scenarios S7, S8, S9, S10, S11, S14 (paging) and
 * S18 of the frozen test-plan at the wire, and the document / item hit shapes.
 *
 * Oracles (task-scope of 630a826a): item 4 (fields `itemId` required, `acrossRoots?` default false, `explain?`, `limit?` 1..50
 * default 10, `cursor?`; the ref goes through the shared target resolution so a missing item is `not_found`, a scope denial is
 * `forbidden` and an 8-hex prefix shared by two items is `ambiguous_id`; an item hit is `{kind:"item", itemId (8-hex), title,
 * score, reasons, explain?}`, a document hit is `{kind:"document", rootId, docKind, slug, title?, score, reasons, explain?}`
 * and `explain` is `{ranks:{edges?,links?,tags?,text?}, weights, rrfK}`), item 2 (scope: default bound is the ref's root,
 * `acrossRoots` lifts it, the access scope is applied before the cap, out-of-scope results are dropped and leave no trace),
 * item 1 (order score desc then kind with `document` before `item`, cap of 50 before slicing, `total` the capped size). The
 * existing items_read contract supplies the error envelope (`invalid_request` with `fields`, `invalid_cursor`,
 * `unknown_parameter` with `parameters`, `forbidden` for a missing id under a restricted scope and `not_found` for an
 * unrestricted caller).
 *
 * Not asserted (no oracle): the pointer of an `invalid_request` for a missing `itemId` or an out-of-range `limit` (only the
 * code and the 400), the key set of `weights`, and the outcome of a 7-hex id.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadRelatedTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    // ------------------------------------------------------------------ hit shapes

    @Test
    fun `S18 an item hit and a document hit have their own views and a tied score lists the document first`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)
            val a = rig.make("Kelmora", p)
            rig.relDep(r, a)
            val d = rig.relDoc(p, "plan-link", title = "Titled plan")
            rig.relLink(p, EntityRef(RefKind.DOCUMENT, d.id), EntityRef(RefKind.ITEM, r.id))

            val reply = select(relatedBody(r.id))

            assertEquals(200, reply.status, reply.raw)
            assertEquals(2, reply.total)
            assertNull(reply.next)
            val doc = reply.items[0]
            val itemHit = reply.items[1]
            assertEquals("document", doc.str("kind"), "equal scores: document before item: ${reply.items}")
            assertEquals(short(p.id), doc.str("rootId"))
            assertEquals("plan", doc.str("docKind"))
            assertEquals("plan-link", doc.str("slug"))
            assertEquals("Titled plan", doc.str("title"))
            assertEquals(listOf("linked_from"), doc.strings("reasons"))
            for (absent in listOf("itemId", "noteKey", "explain")) assertFalse(absent in doc, "a document hit has no '$absent': $doc")
            assertEquals("item", itemHit.str("kind"))
            assertEquals(short(a.id), itemHit.str("itemId"))
            assertTrue(Regex("^[0-9a-f]{8}$").matches(itemHit.str("itemId")), "itemId is a short id: $itemHit")
            assertEquals("Kelmora", itemHit.str("title"))
            assertEquals(listOf("relates_to"), itemHit.strings("reasons"))
            for (absent in listOf(
                "rootId",
                "docKind",
                "slug",
                "explain"
            )) {
                assertFalse(absent in itemHit, "an item hit has no '$absent': $itemHit")
            }
            assertEquals(1.0 / 61, reply.score(0), 1e-9)
            assertEquals(1.0 / 61, reply.score(1), 1e-9)
        }

    @Test
    fun `S18 a document without a title has no title member`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)
            val d = rig.relDoc(p, "plan-bare")
            rig.relLink(p, EntityRef(RefKind.DOCUMENT, d.id), EntityRef(RefKind.ITEM, r.id))

            val hit = select(relatedBody(r.id)).items.single()

            assertEquals("document", hit.str("kind"))
            assertFalse("title" in hit, "a null title is omitted: $hit")
        }

    @Test
    fun `explain adds the rank breakdown weights and the fusion constant only when asked`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)
            val a = rig.make("Kelmora", p)
            rig.relDep(r, a)

            val plain = select(relatedBody(r.id))
            val explained = select(relatedBody(r.id, ""","explain":true"""))

            assertFalse("explain" in plain.items.single(), "off by default: ${plain.raw}")
            val explain = assertNotNull(explained.items.single()["explain"], explained.raw).jsonObject
            assertEquals(
                1,
                explain
                    .getValue("ranks")
                    .jsonObject
                    .getValue("edges")
                    .jsonPrimitive.content
                    .toInt(),
                "edges rank 1: $explain"
            )
            assertEquals(60.0, explain.getValue("rrfK").jsonPrimitive.double, 1e-9, "k = 60: $explain")
            assertTrue("weights" in explain, "explain carries weights: $explain")
            assertFalse("text" in explain.getValue("ranks").jsonObject, "a signal that did not contribute has no rank: $explain")
        }

    @Test
    fun `an item with nothing related is an ok empty page`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)

            val reply = select(relatedBody(r.id))

            assertEquals(200, reply.status, reply.raw)
            assertTrue(reply.ok)
            assertEquals(emptyList<JsonObject>(), reply.items)
            assertEquals(0, reply.total)
            assertNull(reply.next)
        }

    // ------------------------------------------------------------------ id forms

    @Test
    fun `itemId takes a full UUID or an 8-hex prefix and the answer is the same`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)
            val a = rig.make("Kelmora", p)
            rig.relDep(r, a)

            val byUuid = select(relatedBody(r.id))
            val byShort = select(relatedBody(short(r.id)))

            assertEquals(200, byShort.status, byShort.raw)
            assertEquals(byUuid.items, byShort.items)
            assertEquals(byUuid.total, byShort.total)
        }

    // ------------------------------------------------------------------ S14 paging

    @Test
    fun `S14 the default page is ten and a cursor walks the rest with a stable total`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p, tags = "pgtag")
            repeat(12) { rig.make("Satellite ${it.toString().padStart(2, '0')}", p, tags = "pgtag") }

            val first = select(relatedBody(r.id))

            assertEquals(200, first.status, first.raw)
            assertEquals(10, first.items.size, "the default limit is 10")
            assertEquals(12, first.total)
            val cursor = assertNotNull(first.next, "more pages remain")
            val second = select(relatedBody(r.id, ""","cursor":"$cursor""""))
            assertEquals(200, second.status, second.raw)
            assertEquals(2, second.items.size)
            assertEquals(12, second.total, "the total is the same on every page")
            assertNull(second.next, "the last page has no cursor")
            val seen = (first.items + second.items).map { it.str("itemId") }
            assertEquals(12, seen.toSet().size, "no hit on two pages")
        }

    @Test
    fun `S14 an explicit limit pages by that size`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p, tags = "pgtag")
            repeat(7) { rig.make("Satellite ${it.toString().padStart(2, '0')}", p, tags = "pgtag") }
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0

            while (pages < 6) {
                val extra = ""","limit":3""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val reply = select(relatedBody(r.id, extra))
                assertEquals(200, reply.status, reply.raw)
                assertEquals(7, reply.total)
                seen += reply.items.map { it.str("itemId") }
                pages++
                cursor = reply.next
                if (cursor == null) break
            }

            assertEquals(3, pages)
            assertEquals(7, seen.toSet().size)
        }

    @Test
    fun `S14 the same call twice returns the same items in the same order`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p, tags = "pgtag")
            repeat(15) { rig.make("Satellite ${it.toString().padStart(2, '0')}", p, tags = "pgtag") }

            val one = select(relatedBody(r.id, ""","limit":15"""))
            val two = select(relatedBody(r.id, ""","limit":15"""))

            assertEquals(one.items, two.items)
        }

    // ------------------------------------------------------------------ S7 failures

    @Test
    fun `S7 a missing itemId is invalid_request`() =
        irApp(db.db, dir) { rig ->
            rig.make("Hangar container")

            val reply = select("""{"select":"related"}""")

            assertEquals("invalid_request", reply.code, reply.raw)
            assertEquals(400, reply.status)
        }

    @Test
    fun `S7 a limit outside one to fifty is invalid_request and the bounds themselves are fine`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p, tags = "pgtag")
            rig.make("Satellite", p, tags = "pgtag")

            for (bad in listOf(0, 51, -1, 1000)) {
                val reply = select(relatedBody(r.id, ""","limit":$bad"""))
                assertEquals("invalid_request", reply.code, "limit $bad: ${reply.raw}")
                assertEquals(400, reply.status, "limit $bad")
            }
            assertEquals(200, select(relatedBody(r.id, ""","limit":1""")).status, "control: 1 is the minimum")
            assertEquals(200, select(relatedBody(r.id, ""","limit":50""")).status, "control: 50 is the maximum")
        }

    @Test
    fun `S7 a malformed cursor is invalid_cursor`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)

            for (cursor in listOf("not-a-cursor", "!!!")) {
                val reply = select(relatedBody(r.id, ""","cursor":"$cursor""""))
                assertEquals("invalid_cursor", reply.code, "'$cursor': ${reply.raw}")
                assertEquals(400, reply.status)
            }
        }

    @Test
    fun `S7 an unknown id is not_found for an unrestricted caller and a shared prefix is ambiguous_id`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            rig.make("Twin one", p, id = UUID.fromString("abcd1234-0000-4000-8000-000000000001"))
            rig.make("Twin two", p, id = UUID.fromString("abcd1234-1111-4111-8111-111111111111"))

            val missing = select(relatedBody("99999999-9999-4999-8999-999999999999"))
            assertEquals("not_found", missing.code, missing.raw)
            assertEquals(404, missing.status)

            val ambiguous = select(relatedBody("abcd1234"))
            assertEquals("ambiguous_id", ambiguous.code, ambiguous.raw)
            assertEquals(ErrorCode.AMBIGUOUS_ID.httpStatus, ambiguous.status)
        }

    @Test
    fun `S7 unknown members and members of other selects are unknown_parameter`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)

            for (member in listOf("query", "kinds", "role", "includeArchived")) {
                val reply = select(relatedBody(r.id, ""","$member":"x""""))
                assertEquals("unknown_parameter", reply.code, "$member: ${reply.raw}")
                assertEquals(listOf("/$member"), reply.parameters())
            }
        }

    @Test
    fun `S7 acrossRoots must be a boolean`() =
        irApp(db.db, dir) { rig ->
            val p = rig.make("Hangar container")
            val r = rig.make("Zorvath", p)

            val reply = select(relatedBody(r.id, ""","acrossRoots":"yes""""))

            assertEquals("invalid_request", reply.code, reply.raw)
            assertTrue("/acrossRoots" in reply.fields(), reply.fields().toString())
        }

    // ------------------------------------------------------------------ S11 cross-root

    @Test
    fun `S11 acrossRoots absent and false are the default bound and true lifts it`() =
        irApp(db.db, dir) { rig ->
            val a = rig.make("Alpha yard")
            val b = rig.make("Beta yard")
            val r = rig.make("Zorvath", a, tags = "sharedtag")
            rig.make("Tandem", a, tags = "sharedtag")
            rig.make("Umbral", b, tags = "sharedtag")

            val absent = select(relatedBody(r.id))
            val explicitFalse = select(relatedBody(r.id, ""","acrossRoots":false"""))
            val lifted = select(relatedBody(r.id, ""","acrossRoots":true"""))

            assertEquals(listOf<String?>("Tandem"), absent.titlesOf())
            assertEquals(absent.items, explicitFalse.items)
            assertEquals(setOf<String?>("Tandem", "Umbral"), lifted.titlesOf().toSet())
            assertEquals(2, lifted.total)
        }

    // ------------------------------------------------------------------ S8 / S9 / S10 scope

    private suspend fun IrClient.crossFixture(rig: EventLogRig): WorkItem {
        val a = rig.make("Alpha yard", id = REL_ROOT_A)
        val b = rig.make("Beta yard", id = REL_ROOT_B)
        val r = rig.make("Quillonary", a, tags = "sharedtag")
        val holder = rig.relNote(r, "plumbline anchor")
        val ein = rig.make("Ember", a)
        val eout = rig.make("Frostel", b)
        rig.relDep(r, ein)
        rig.relDep(r, eout)
        val din = rig.relDoc(a, "doc-in", title = "Inner document")
        val dout = rig.relDoc(b, "doc-out", title = "Outer document")
        rig.relLink(a, EntityRef(RefKind.NOTE, holder.id), EntityRef(RefKind.DOCUMENT, din.id), toRef = "plan:doc-in")
        rig.relLink(a, EntityRef(RefKind.NOTE, holder.id), EntityRef(RefKind.DOCUMENT, dout.id), toRef = "plan:doc-out")
        rig.make("Tandem", a, tags = "sharedtag")
        rig.make("Umbral", b, tags = "sharedtag")
        val xin = rig.make("Xanthe", a)
        rig.relNote(xin, "quillonary in alpha", key = "n")
        val xout = rig.make("Yarrow", b)
        rig.relNote(xout, "quillonary in beta", key = "n")
        return r
    }

    @Test
    fun `S9 a root scoped token gets only its own root's counterparts for every signal even with acrossRoots`() =
        irApp(db.db, dir, scopeRootIds = setOf(REL_ROOT_A)) { rig ->
            val r = crossFixture(rig)

            val scoped = select(relatedBody(r.id, ""","acrossRoots":true"""), TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf("Ember", "Inner document", "Tandem", "Xanthe"), scoped.titlesOf().filterNotNull().sorted())
            assertEquals(4, scoped.total, "out-of-scope results are not counted")
            for (leak in listOf("Frostel", "Outer document", "Umbral", "Yarrow", short(REL_ROOT_B), "doc-out")) {
                assertFalse(leak in scoped.raw, "out-of-scope content '$leak' must not appear: ${scoped.raw}")
            }
            val open = select(relatedBody(r.id, ""","acrossRoots":true"""))
            assertEquals(8, open.total, "control: the unrestricted token sees all eight")
        }

    @Test
    fun `S9 a restricted token still gets a full page when out-of-scope hits would have ranked above`() =
        irApp(db.db, dir, scopeRootIds = setOf(REL_ROOT_A)) { rig ->
            val a = rig.make("Alpha yard", id = REL_ROOT_A)
            val b = rig.make("Beta yard", id = REL_ROOT_B)
            val r = rig.make("Quillonary", a, tags = "pgone,pgtwo")
            repeat(12) { rig.make("Aitem ${it.toString().padStart(2, '0')}", a, tags = "pgone") }
            repeat(15) { rig.make("Bitem ${it.toString().padStart(2, '0')}", b, tags = "pgone,pgtwo") }

            val page = select(relatedBody(r.id, ""","acrossRoots":true,"limit":10"""), TEST_TOKEN)

            assertEquals(200, page.status, page.raw)
            assertEquals(10, page.items.size, page.raw)
            assertEquals(12, page.total)
            assertTrue(page.titlesOf().all { it!!.startsWith("Aitem") }, "${page.titlesOf()}")
        }

    @Test
    fun `S8 a ref outside the token's roots is forbidden with no trace and a missing id is forbidden too`() =
        irApp(db.db, dir, scopeRootIds = setOf(REL_ROOT_A)) { rig ->
            rig.make("Alpha yard", id = REL_ROOT_A)
            val b = rig.make("Beta yard", id = REL_ROOT_B)
            val foreign = rig.make("Beta secret", b)

            val denied = select(relatedBody(foreign.id), TEST_TOKEN)
            assertEquals(403, denied.status, denied.raw)
            assertEquals("forbidden", denied.code)
            assertFalse("Beta secret" in denied.raw || short(foreign.id) in denied.raw, "no trace of the foreign item: ${denied.raw}")

            for (missing in listOf("99999999-9999-4999-8999-999999999999", "00000000")) {
                val scoped = select(relatedBody(missing), TEST_TOKEN)
                assertEquals(403, scoped.status, "$missing: ${scoped.raw}")
                assertEquals("forbidden", scoped.code, missing)
                assertEquals(404, select(relatedBody(missing)).status, "control $missing: the unrestricted caller gets not_found")
            }
        }

    @Test
    fun `S8 a tag scoped token is forbidden on an item without an allowed tag and sees only tagged counterparts`() =
        irApp(db.db, dir, tagsInclude = setOf("team")) { rig ->
            val p = rig.make("Hangar container")
            val tagged = rig.make("Zorvath", p, tags = "team,sharedx")
            val untagged = rig.make("Plain ref", p)
            rig.make("Tandem", p, tags = "team,sharedx")
            rig.make("Umbral", p, tags = "sharedx")

            val denied = select(relatedBody(untagged.id), TEST_TOKEN)
            assertEquals(403, denied.status, denied.raw)
            assertEquals("forbidden", denied.code)

            val scoped = select(relatedBody(tagged.id), TEST_TOKEN)
            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(listOf<String?>("Tandem"), scoped.titlesOf(), "Umbral lacks the allowed tag: ${scoped.raw}")
            assertEquals(1, scoped.total)
            assertEquals(setOf<String?>("Tandem", "Umbral"), select(relatedBody(tagged.id)).titlesOf().toSet(), "control: unrestricted")
        }
}

/**
 * S17 (task-scope item 5): the telemetry of a `related` call, on the MCP surface driven through the real adapters over the
 * in-memory client/server pair and the production tool set. `request_shape` gains `relatedSignals {edges,links,tags,text}`,
 * the number of hits on the RETURNED page each signal contributed to with zeros kept; every page hit records its id and
 * version as a target version; `result_count` is the total. Oracle: the frozen S17 (a page of three hits, two edges-only and
 * one edges plus text, gives `{edges:3, links:0, tags:0, text:1}`) and the known-limit sentence that per-hit attribution is
 * deferred, so the counts cover the returned page only.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadRelatedTelemetryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private class Rig(
        val rig: EventLogRig,
        val writer: CallLogWriter
    )

    private fun newRig(): Rig {
        val rig = EventLogRig.build(db.db, dir)
        return Rig(rig, CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours))
    }

    private suspend fun connect(rig: Rig): Pair<Client, Server> {
        val server = Server(serverInfo = Implementation(name = "q30-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpToolAdapter(callLog = rig.writer).registerToolsWithServer(server, buildMcpTools(), rig.rig.ctx)
        McpOperationAdapter(buildToolDispatcher(rig.rig.ctx), callLog = rig.writer)
            .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "q30-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities())
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        return client to server
    }

    private suspend fun Client.related(
        itemId: String,
        limit: Int? = null,
        extra: List<Pair<String, JsonElement>> = emptyList()
    ): CallToolResult {
        val args = mutableListOf<Pair<String, JsonElement>>("select" to JsonPrimitive("related"), "itemId" to JsonPrimitive(itemId))
        if (limit != null) args += "limit" to JsonPrimitive(limit)
        args += extra
        return callTool(
            buildCallToolRequest {
                name = ITEMS_READ
                arguments(JsonObject(mapOf(*args.toTypedArray())))
            }
        )
    }

    private suspend fun rowOf(rig: Rig): Map<String, Any?> {
        rig.writer.flushNow()
        return CallLogRows.forTool(db.jdbcUrl, ITEMS_READ).single()
    }

    private fun Map<String, Any?>.shape(): JsonObject? = (get("request_shape") as String?)?.let { Json.parseToJsonElement(it).jsonObject }

    private fun Map<String, Any?>.relatedSignals(): Map<String, Int>? =
        shape()?.get("relatedSignals")?.jsonObject?.mapValues {
            it.value.jsonPrimitive.content
                .toInt()
        }

    private fun Map<String, Any?>.targetVersions(): Map<String, Long> =
        (get("target_versions") as String?)
            ?.let {
                Json.parseToJsonElement(it).jsonObject.mapValues { entry ->
                    entry.value.jsonPrimitive.content
                        .toLong()
                }
            }.orEmpty()

    private class Seeded(
        val ref: WorkItem,
        val b: WorkItem,
        val a: WorkItem,
        val c: WorkItem
    )

    /** Three edge counterparts (ids B < A < C so the edge order is B, A, C); only B also matches the ref's text. */
    private suspend fun seed(rig: Rig): Seeded {
        val p = rig.rig.make("Hangar container")
        val ref = rig.rig.make("Zorvath quasarium", p)
        val b = rig.rig.make("Quasarium brigade", p, id = relUid(1))
        val a = rig.rig.make("Kelmora", p, id = relUid(2))
        val c = rig.rig.make("Wombrel", p, id = relUid(3))
        for (counterpart in listOf(b, a, c)) rig.rig.relDep(ref, counterpart)
        return Seeded(ref, b, a, c)
    }

    private suspend fun versionOf(
        rig: Rig,
        item: WorkItem
    ): Long =
        checkNotNull(
            rig.rig.raw
                .workItemRepository()
                .getById(item.id)
        ).version.toLong()

    @Test
    fun `S17 a page of three hits records the signals that contributed zeros kept and the total as the result count`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val s = seed(rig)

                val result = client.related(s.ref.id.toString())
                assertTrue(result.isError != true, "related must succeed: ${result.content}")

                val row = rowOf(rig)
                assertEquals(mapOf("edges" to 3, "links" to 0, "tags" to 0, "text" to 1), row.relatedSignals(), "${row["request_shape"]}")
                assertEquals(3L, row["result_count"])
                val versions = row.targetVersions()
                for (hit in listOf(s.a, s.b, s.c)) {
                    assertEquals(
                        versionOf(rig, hit),
                        versions[hit.id.toString()],
                        "hit '${hit.title}' is recorded at its version: $versions"
                    )
                }
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S17 the signal counts and target versions cover the returned page only while the result count stays the total`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val s = seed(rig)

                client.related(s.ref.id.toString(), limit = 2)

                val row = rowOf(rig)
                assertEquals(
                    mapOf("edges" to 2, "links" to 0, "tags" to 0, "text" to 1),
                    row.relatedSignals(),
                    "page of B and A: ${row["request_shape"]}"
                )
                assertEquals(3L, row["result_count"], "the total, not the page")
                val versions = row.targetVersions()
                assertEquals(versionOf(rig, s.b), versions[s.b.id.toString()])
                assertEquals(versionOf(rig, s.a), versions[s.a.id.toString()])
                assertFalse(s.c.id.toString() in versions, "a hit that is not on the page is not recorded: $versions")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S17 a related call with no hits records all four counts as zero`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val p = rig.rig.make("Hangar container")
                val lonely = rig.rig.make("Zorvath", p)

                client.related(lonely.id.toString())

                val row = rowOf(rig)
                assertEquals(mapOf("edges" to 0, "links" to 0, "tags" to 0, "text" to 0), row.relatedSignals(), "${row["request_shape"]}")
                assertEquals(0L, row["result_count"])
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S17 a related call rejected by validation records no signal counts`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val s = seed(rig)

                val result = client.related(s.ref.id.toString(), limit = 51)
                assertEquals(true, result.isError, "limit 51 is rejected")

                val row = rowOf(rig)
                assertNull(row.shape()?.get("relatedSignals"), "the select never ran: ${row["request_shape"]}")
                assertTrue(row.targetVersions().isEmpty(), "no version is recorded: ${row["target_versions"]}")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S17 a search call does not carry relatedSignals`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                client.callTool(
                    buildCallToolRequest {
                        name = ITEMS_READ
                        arguments(JsonObject(mapOf("select" to JsonPrimitive("search"), "query" to JsonPrimitive("quasarium"))))
                    }
                )

                assertNull(rowOf(rig).shape()?.get("relatedSignals"))
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    private companion object {
        const val ITEMS_READ = "items_read"
    }
}
