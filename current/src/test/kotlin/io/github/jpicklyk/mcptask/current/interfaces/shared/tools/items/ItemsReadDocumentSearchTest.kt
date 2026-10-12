package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.items

import io.github.jpicklyk.mcptask.current.application.knowledge.document.DocumentService
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteCallLogStore
import io.github.jpicklyk.mcptask.current.infrastructure.telemetry.CallLogWriter
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.TEST_TOKEN
import io.github.jpicklyk.mcptask.current.interfaces.mcp.McpOperationAdapter
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
import kotlinx.serialization.json.JsonArray
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.hours

private val DOC_ROOT_A: UUID = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a")
private val DOC_ROOT_B: UUID = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b")
private val DOC_CHILD_OF_A: UUID = UUID.fromString("c3c3c3c3-0000-4000-8000-00000000000c")

private fun EventLogRig.documentService(): DocumentService = DocumentService(raw, db.unitOfWork())

private suspend fun EventLogRig.writeDoc(
    owner: WorkItem,
    slug: String,
    body: String,
    kind: DocumentKind = DocumentKind.PLAN,
    title: String? = null,
    expectedVersion: Int? = null,
): Document =
    when (val outcome = documentService().write(owner.id, kind, slug, body, title = title, expectedVersion = expectedVersion)) {
        is Outcome.Ok -> outcome.value
        is Outcome.Err -> fail("fixture: writing document '$slug' failed: ${outcome.error.code} ${outcome.error.message}")
    }

private fun searchBody(extra: String): String = """{"select":"search"$extra}"""

private fun Reply.docSlugs(): Set<String> = items.filter { it.str("kind") == "document" }.map { it.str("slug") }.toSet()

private fun Reply.kindSet(): Set<String> = items.map { it.str("kind") }.toSet()

private fun hitKey(hit: JsonObject): String =
    listOf("kind", "itemId", "rootId", "noteKey", "docKind", "slug").joinToString("|") { hit[it]?.jsonPrimitive?.content.orEmpty() }

/**
 * Independent tests (test-author seat, item 82f8a7db) for documents as a searchable kind of `items_read` select `search`,
 * through the production REST topology (`POST /api/v1/ops/items_read` with a real bearer principal): scenarios S1-S12 of
 * the frozen test-plan and its probes at the wire.
 *
 * Oracles (task-scope of 82f8a7db): item 3 (default kinds are filter-aware; an explicit kind set containing `document`
 * with a non-empty role / tags / traits is `invalid_request` on the pointer `/role`, else `/tags`, else `/traits`), item 5
 * (`KindWire.DOCUMENT` is the wire name `document`; a document hit has `kind`, `rootId` as a short id, `docKind`, `slug`,
 * `title` only when the document has one, `field` of `title` or `body`, `snippet` unless `snippet:false`, `score`,
 * `matchedIn`, `explain` only on request, and NO `itemId` / `noteKey`), D1 (a document is visible exactly when its root is
 * admitted: a listed id below the root admits nothing, a tag scope needs the tag on the root itself), D2 (archived are
 * never searched; no `includeArchived` parameter), D3, D5, D6 (a document is addressed by rootId, docKind, slug and no
 * document id is on the wire), D7, the non-goals (no `docKind` filter), and the existing Q19 contract that filter ids
 * naming an out-of-scope item are an ok empty page, that `kinds` outside `item|note|document` are `invalid_request` and
 * that unknown members are `unknown_parameter`. Expected values are literals from the seed.
 *
 * Not asserted (no oracle): the exact values of `matchedIn`, the third tie-break key of the order, the status of a NUL
 * in a query beyond parity with the item kind, and MCP-versus-REST ordered-id parity (owned by the parity suite).
 *
 * Forbidden-construct declaration: one conditional assertion, the duplicate-kinds probe, whose two branches are each
 * strict (an ok answer must hold exactly one hit; otherwise `invalid_request`) because the task-scope is silent on
 * whether duplicates are merged or rejected. No skip constructs.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadDocumentSearchTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private suspend fun EventLogRig.note(
        item: WorkItem,
        key: String,
        body: String,
    ) {
        raw.noteRepository().upsert(Note(itemId = item.id, key = key, role = "work", body = body))
    }

    // ------------------------------------------------------------------ S1 / S2 / S4: the hit shape

    @Test
    fun `S1 a document hit has the document view and neither itemId nor noteKey`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of the plan")
            rig.writeDoc(host, "plan-alpha", "The zorblaxquux plan body")
            rig.writeDoc(host, "plan-other", "nothing relevant here")

            val r = select(searchBody(""","query":"zorblaxquux","kinds":["document"]"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(1, r.total)
            val hit = r.items.single()
            assertEquals("document", hit.str("kind"))
            assertEquals(short(host.id), hit.str("rootId"))
            assertTrue(Regex("^[0-9a-f]{8}$").matches(hit.str("rootId")), "rootId is a short id: $hit")
            assertEquals("plan", hit.str("docKind"))
            assertEquals("plan-alpha", hit.str("slug"))
            assertEquals("body", hit.str("field"))
            assertTrue("<mark>" in hit.str("snippet"), "snippet marks the match: $hit")
            for (member in listOf("score", "matchedIn")) assertTrue(member in hit, "hit has $member: $hit")
            for (absent in listOf("itemId", "noteKey", "title", "explain")) {
                assertFalse(absent in hit, "a document hit without a title has no '$absent': $hit")
            }
        }

    @Test
    fun `S2 the field is title when the title matches else body and a titled document shows its title`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of field docs")
            rig.writeDoc(host, "both", "zorblaxquux in the body", title = "zorblaxquux in the title")
            rig.writeDoc(host, "untitled", "zorblaxquux body of an untitled document")
            rig.writeDoc(host, "other-title", "zorblaxquux body again", title = "an unrelated title")

            val r = select(searchBody(""","query":"zorblaxquux","kinds":["document"]"""))

            assertEquals(200, r.status, r.raw)
            val bySlug = r.items.associateBy { it.str("slug") }
            assertEquals(setOf("both", "untitled", "other-title"), bySlug.keys)
            assertEquals("title", bySlug.getValue("both").str("field"))
            assertEquals("zorblaxquux in the title", bySlug.getValue("both").str("title"))
            assertEquals("body", bySlug.getValue("untitled").str("field"))
            assertFalse("title" in bySlug.getValue("untitled"), "a null title is omitted: ${bySlug.getValue("untitled")}")
            assertEquals("body", bySlug.getValue("other-title").str("field"))
            assertEquals("an unrelated title", bySlug.getValue("other-title").str("title"))
        }

    @Test
    fun `S4 plan rule and page documents are searchable with their docKind`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of kinds")
            rig.writeDoc(host, "a-plan", "zorblaxquux plan", kind = DocumentKind.PLAN)
            rig.writeDoc(host, "a-rule", "zorblaxquux rule", kind = DocumentKind.RULE)
            rig.writeDoc(host, "a-page", "zorblaxquux page", kind = DocumentKind.PAGE)

            val r = select(searchBody(""","query":"zorblaxquux","kinds":["document"]"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(
                mapOf("a-plan" to "plan", "a-rule" to "rule", "a-page" to "page"),
                r.items.associate {
                    it.str("slug") to
                        it.str("docKind")
                }
            )
        }

    @Test
    fun `snippet false drops the document snippet and explain adds the rank breakdown`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of options")
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            val plain = select(searchBody(""","query":"zorblaxquux","kinds":["document"],"snippet":false"""))
            assertEquals(200, plain.status, plain.raw)
            assertFalse("snippet" in plain.items.single(), "snippet:false drops it: ${plain.items}")

            val explained = select(searchBody(""","query":"zorblaxquux","kinds":["document"],"explain":true"""))
            val explain = assertNotNull(explained.items.single()["explain"], "explain:true adds explain: ${explained.raw}").jsonObject
            assertTrue("rrfK" in explain, "explain carries rrfK: $explain")
        }

    // ------------------------------------------------------------------ S3: default kinds

    @Test
    fun `S3 without kinds item note and document hits come back together in one score-ordered list`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("zorblaxquux item title")
            val noteOwner = rig.make("Owner of the note")
            rig.note(noteOwner, "n1", "the zorblaxquux note body")
            val docRoot = rig.make("Root of the document")
            rig.writeDoc(docRoot, "plan-one", "the zorblaxquux document body")

            val r = select(searchBody(""","query":"zorblaxquux""""))

            assertEquals(200, r.status, r.raw)
            assertEquals(3, r.total)
            assertEquals(setOf("item", "note", "document"), r.kindSet())
            assertEquals(short(item.id), r.items.single { it.str("kind") == "item" }.str("itemId"))
            assertEquals(short(noteOwner.id), r.items.single { it.str("kind") == "note" }.str("itemId"))
            assertEquals(short(docRoot.id), r.items.single { it.str("kind") == "document" }.str("rootId"))
            r.items.zipWithNext().forEach { (a, b) ->
                val sa = a.getValue("score").jsonPrimitive.double
                val sb = b.getValue("score").jsonPrimitive.double
                assertTrue(sa > sb || (sa == sb && a.str("kind") <= b.str("kind")), "ordered by score then kind: $a before $b")
            }
        }

    @Test
    fun `S3 kinds item or note alone never returns a document and kinds document alone returns only documents`() =
        irApp(db.db, dir) { rig ->
            val item = rig.make("zorblaxquux item title")
            val host = rig.make("Host of the document")
            rig.writeDoc(host, "plan-one", "the zorblaxquux document body")

            val onlyItems = select(searchBody(""","query":"zorblaxquux","kinds":["item"]"""))
            assertEquals(setOf("item" to short(item.id)), onlyItems.items.map { it.str("kind") to it.str("itemId") }.toSet())

            val onlyNotes = select(searchBody(""","query":"zorblaxquux","kinds":["note"]"""))
            assertEquals(0, onlyNotes.total)

            val onlyDocs = select(searchBody(""","query":"zorblaxquux","kinds":["document"]"""))
            assertEquals(setOf("document"), onlyDocs.kindSet())
            assertEquals(1, onlyDocs.total)

            val twoKinds = select(searchBody(""","query":"zorblaxquux","kinds":["item","document"]"""))
            assertEquals(setOf("item", "document"), twoKinds.kindSet())
        }

    // ------------------------------------------------------------------ S5: itemId / ancestorId

    @Test
    fun `S5 itemId and ancestorId find a document through its root and a child id finds none`() =
        irApp(db.db, dir) { rig ->
            val rootA = rig.make("Root A")
            val inA = rig.make("Child of A", rootA)
            val rootB = rig.make("Root B")
            rig.writeDoc(rootA, "doc-a", "zorblaxquux of A")
            rig.writeDoc(rootB, "doc-b", "zorblaxquux of B")

            assertEquals(
                setOf("doc-a"),
                select(searchBody(""","query":"zorblaxquux","kinds":["document"],"itemId":"${rootA.id}"""")).docSlugs()
            )
            assertEquals(
                setOf("doc-a"),
                select(searchBody(""","query":"zorblaxquux","kinds":["document"],"ancestorId":"${rootA.id}"""")).docSlugs()
            )
            assertEquals(
                setOf("doc-a"),
                select(searchBody(""","query":"zorblaxquux","kinds":["document"],"itemId":"${short(rootA.id)}"""")).docSlugs()
            )
            assertEquals(0, select(searchBody(""","query":"zorblaxquux","kinds":["document"],"itemId":"${inA.id}"""")).total)
            assertEquals(0, select(searchBody(""","query":"zorblaxquux","kinds":["document"],"ancestorId":"${inA.id}"""")).total)
            assertEquals(setOf("doc-a", "doc-b"), select(searchBody(""","query":"zorblaxquux","kinds":["document"]""")).docSlugs())
        }

    // ------------------------------------------------------------------ S6 / S7: filter compatibility

    @Test
    fun `S6 kinds with document plus role tags or traits is invalid_request on the matching pointer and returns no hits`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("zorblaxquux host", tags = "api", traits = listOf("t1"))
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            for (kinds in listOf(
                """["document"]""",
                """["item","document"]""",
                """["note","document"]""",
                """["item","note","document"]"""
            )) {
                val role = select(searchBody(""","query":"zorblaxquux","kinds":$kinds,"role":"queue""""))
                assertEquals("invalid_request", role.code, "$kinds role: ${role.raw}")
                assertEquals(400, role.status)
                assertEquals(listOf("/role"), role.fields(), kinds)

                val tags = select(searchBody(""","query":"zorblaxquux","kinds":$kinds,"tags":["api"]"""))
                assertEquals("invalid_request", tags.code, "$kinds tags: ${tags.raw}")
                assertEquals(listOf("/tags"), tags.fields(), kinds)

                val traits = select(searchBody(""","query":"zorblaxquux","kinds":$kinds,"traits":["t1"]"""))
                assertEquals("invalid_request", traits.code, "$kinds traits: ${traits.raw}")
                assertEquals(listOf("/traits"), traits.fields(), kinds)

                val all = select(searchBody(""","query":"zorblaxquux","kinds":$kinds,"role":"queue","tags":["api"],"traits":["t1"]"""))
                assertEquals(listOf("/role"), all.fields(), "$kinds: the first offending field only")

                val tagsAndTraits = select(searchBody(""","query":"zorblaxquux","kinds":$kinds,"tags":["api"],"traits":["t1"]"""))
                assertEquals(listOf("/tags"), tagsAndTraits.fields(), kinds)
            }
        }

    @Test
    fun `S6 empty tags and traits arrays are no filter and an item and note kind set still takes role tags and traits`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("zorblaxquux host", tags = "api", traits = listOf("t1"))
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            val empties = select(searchBody(""","query":"zorblaxquux","kinds":["document"],"tags":[],"traits":[]"""))
            assertEquals(200, empties.status, empties.raw)
            assertEquals(setOf("plan-a"), empties.docSlugs())

            val itemFilters =
                select(searchBody(""","query":"zorblaxquux","kinds":["item","note"],"role":"queue","tags":["api"],"traits":["t1"]"""))
            assertEquals(200, itemFilters.status, itemFilters.raw)
            assertEquals(setOf("item" to short(host.id)), itemFilters.items.map { it.str("kind") to it.str("itemId") }.toSet())
        }

    @Test
    fun `S7 without kinds role tags and traits narrow item and note hits and leave documents out`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("zorblaxquux tagged host", tags = "api", role = Role.QUEUE, traits = listOf("t1"))
            rig.writeDoc(host, "plan-a", "zorblaxquux plan body")
            val other = rig.make("zorblaxquux other host", tags = "beta", role = Role.WORK)
            rig.writeDoc(other, "plan-b", "zorblaxquux other plan body")

            for (filter in listOf(
                """"role":"queue"""",
                """"tags":["api"]""",
                """"traits":["t1"]""",
                """"role":"queue","tags":["api"],"traits":["t1"]"""
            )) {
                val r = select(searchBody(""","query":"zorblaxquux",$filter"""))

                assertEquals(200, r.status, "$filter: ${r.raw}")
                assertEquals(setOf("item" to short(host.id)), r.items.map { it.str("kind") to it.str("itemId") }.toSet(), filter)
                assertEquals(1, r.total, filter)
            }
            val unfiltered = select(searchBody(""","query":"zorblaxquux""""))
            assertEquals(4, unfiltered.total, "control: with no filter the two items and both documents hit")
        }

    // ------------------------------------------------------------------ S8 / S9

    @Test
    fun `S8 an archived document is not found and is found again once unarchived`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of archive")
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")
            rig.writeDoc(host, "plan-b", "zorblaxquux sibling")
            val search = searchBody(""","query":"zorblaxquux","kinds":["document"]""")

            rig.documentService().archive(host.id, DocumentKind.PLAN, "plan-a")
            assertEquals(setOf("plan-b"), select(search).docSlugs())

            rig.documentService().unarchive(host.id, DocumentKind.PLAN, "plan-a")
            assertEquals(setOf("plan-a", "plan-b"), select(search).docSlugs())
        }

    @Test
    fun `S9 a deleted document is not found and an overwritten one is found by its new text only`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of edits")
            rig.writeDoc(host, "doomed", "zorblaxquux doomed")
            rig.writeDoc(host, "edited", "oldfrobnicate text")

            rig.documentService().delete(host.id, DocumentKind.PLAN, "doomed")
            rig.writeDoc(host, "edited", "newplinkazoid text", expectedVersion = 1)

            assertEquals(0, select(searchBody(""","query":"zorblaxquux","kinds":["document"]""")).total)
            assertEquals(0, select(searchBody(""","query":"oldfrobnicate","kinds":["document"]""")).total)
            assertEquals(setOf("edited"), select(searchBody(""","query":"newplinkazoid","kinds":["document"]""")).docSlugs())
        }

    // ------------------------------------------------------------------ S10 / S11: scope

    @Test
    fun `S10 under a root scope documents of the scoped root hit and other roots documents never appear`() =
        irApp(db.db, dir, scopeRootIds = setOf(DOC_ROOT_A)) { rig ->
            val rootA = rig.make("Root A", id = DOC_ROOT_A)
            val rootB = rig.make("Root B", id = DOC_ROOT_B)
            rig.writeDoc(rootA, "doc-a", "zorblaxquux of A")
            rig.writeDoc(rootB, "doc-bravo", "zorblaxquux of B")
            val search = searchBody(""","query":"zorblaxquux","kinds":["document"]""")

            val scoped = select(search, TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(setOf("doc-a"), scoped.docSlugs())
            assertEquals(1, scoped.total)
            assertFalse("doc-bravo" in scoped.raw || short(rootB.id) in scoped.raw, "out-of-scope content must not appear: ${scoped.raw}")
            assertEquals(setOf("doc-a", "doc-bravo"), select(search).docSlugs(), "control: the unrestricted token sees both")

            val filtered = select(searchBody(""","query":"zorblaxquux","kinds":["document"],"itemId":"${rootB.id}""""), TEST_TOKEN)
            assertEquals(200, filtered.status, "a filter naming an out-of-scope root is an ok empty page: ${filtered.raw}")
            assertEquals(0, filtered.total)
        }

    @Test
    fun `S10 a scope that lists an id below the root does not admit the root's documents`() =
        irApp(db.db, dir, scopeRootIds = setOf(DOC_CHILD_OF_A)) { rig ->
            val rootA = rig.make("Root A", id = DOC_ROOT_A)
            rig.make("Child of A", rootA, id = DOC_CHILD_OF_A)
            rig.writeDoc(rootA, "doc-a", "zorblaxquux of A")
            val search = searchBody(""","query":"zorblaxquux","kinds":["document"]""")

            val scoped = select(search, TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(0, scoped.total)
            assertFalse("doc-a" in scoped.raw, scoped.raw)
            assertEquals(setOf("doc-a"), select(search).docSlugs(), "control: the unrestricted token sees it")
        }

    @Test
    fun `S11 under a tag scope only documents whose root carries the exact tag hit`() =
        irApp(db.db, dir, tagsInclude = setOf("team")) { rig ->
            val tagged = rig.make("Root tagged", tags = "x,team")
            val lookalike = rig.make("Root lookalike", tags = "teamx")
            val untagged = rig.make("Root untagged")
            val childOnly = rig.make("Root with tagged child")
            rig.make("Tagged child", childOnly, tags = "team")
            rig.writeDoc(tagged, "doc-tagged", "zorblaxquux tagged")
            rig.writeDoc(lookalike, "doc-lookalike", "zorblaxquux lookalike")
            rig.writeDoc(untagged, "doc-untagged", "zorblaxquux untagged")
            rig.writeDoc(childOnly, "doc-child-only", "zorblaxquux child only")
            val search = searchBody(""","query":"zorblaxquux","kinds":["document"]""")

            val scoped = select(search, TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(setOf("doc-tagged"), scoped.docSlugs())
            assertEquals(1, scoped.total)
            for (leak in listOf(
                "doc-lookalike",
                "doc-untagged",
                "doc-child-only",
                short(lookalike.id),
                short(untagged.id),
                short(childOnly.id)
            )) {
                assertFalse(leak in scoped.raw, "out-of-scope content '$leak' must not appear: ${scoped.raw}")
            }
            assertEquals(4, select(search).total, "control: the unrestricted token sees all four documents")
        }

    @Test
    fun `S11 a percent sign in a token tag scope is a literal and admits nothing`() =
        irApp(db.db, dir, tagsInclude = setOf("te%")) { rig ->
            val tagged = rig.make("Root tagged", tags = "team")
            rig.writeDoc(tagged, "doc-tagged", "zorblaxquux tagged")

            val scoped = select(searchBody(""","query":"zorblaxquux","kinds":["document"]"""), TEST_TOKEN)

            assertEquals(200, scoped.status, scoped.raw)
            assertEquals(0, scoped.total)
        }

    // ------------------------------------------------------------------ S12: paging

    @Test
    fun `S12 cursor paging over 25 documents and 25 items visits each hit once with a stable total`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of paging docs")
            repeat(25) { n ->
                rig.make("pgtoken item ${n.toString().padStart(2, '0')}")
                rig.writeDoc(host, "pg-doc-${n.toString().padStart(2, '0')}", "pgtoken document body $n")
            }
            val seen = mutableListOf<String>()
            var cursor: String? = null
            var pages = 0

            while (pages < 6) {
                val extra = ""","query":"pgtoken","limit":20""" + (cursor?.let { ""","cursor":"$it"""" } ?: "")
                val r = select(searchBody(extra))
                assertEquals(200, r.status, r.raw)
                assertEquals(50, r.total, "the total is the same on every page")
                seen += r.items.map { hitKey(it) }
                pages++
                cursor = r.next
                if (cursor == null) break
            }

            assertEquals(3, pages)
            assertEquals(50, seen.size)
            assertEquals(50, seen.toSet().size, "no hit appears on two pages")
            assertEquals(25, seen.count { it.startsWith("document|") })
            assertEquals(25, seen.count { it.startsWith("item|") })
        }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe kinds is exact case sensitive and has no abbreviation`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of kinds probe")
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            for (bad in listOf("Document", "DOCUMENT", "doc", "documents", "plan")) {
                val r = select(searchBody(""","query":"zorblaxquux","kinds":["$bad"]"""))
                assertEquals("invalid_request", r.code, "kinds [$bad]: ${r.raw}")
                assertTrue(r.fields().any { it.startsWith("/kinds") }, "kinds [$bad]: ${r.fields()}")
            }
        }

    @Test
    fun `probe duplicate kinds never produce duplicate hits`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of duplicates")
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            val r = select(searchBody(""","query":"zorblaxquux","kinds":["document","document"]"""))

            if (r.status == 200) {
                assertEquals(1, r.total, "duplicates are merged, not doubled: ${r.raw}")
                assertEquals(1, r.items.size, r.raw)
            } else {
                assertEquals("invalid_request", r.code, r.raw)
            }
        }

    @Test
    fun `probe unknown members docKind and includeArchived are unknown_parameter`() =
        irApp(db.db, dir) { rig ->
            rig.make("Host of unknown members")

            for (member in listOf("docKind", "includeArchived")) {
                val r = select(searchBody(""","query":"zorblaxquux","kinds":["document"],"$member":"plan""""))
                assertEquals("unknown_parameter", r.code, "$member: ${r.raw}")
                assertEquals(listOf("/$member"), r.parameters())
            }
        }

    @Test
    fun `probe a NUL character in the query is treated the same for documents as for items`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of NUL")
            rig.writeDoc(host, "plan-a", "zorblaxquux plan")

            val asItems = select(searchBody(""","query":"zorb\u0000laxquux","kinds":["item"]"""))
            val asDocs = select(searchBody(""","query":"zorb\u0000laxquux","kinds":["document"]"""))

            assertTrue(asDocs.status < 500, "no server error: ${asDocs.raw}")
            assertEquals(asItems.status, asDocs.status, "documents behave as the existing kinds do: ${asDocs.raw}")
            if (asDocs.status == 200) assertEquals(0, asDocs.total, asDocs.raw)
        }

    @Test
    fun `probe a query term that appears only in a slug finds nothing`() =
        irApp(db.db, dir) { rig ->
            val host = rig.make("Host of slugs")
            rig.writeDoc(host, "slugonlyzorbish", "an ordinary body", title = "An ordinary title")

            val r = select(searchBody(""","query":"slugonlyzorbish","kinds":["document"]"""))

            assertEquals(200, r.status, r.raw)
            assertEquals(0, r.total)
        }

    @Test
    fun `probe a blank query with kinds document is still invalid_request on query`() =
        irApp(db.db, dir) { rig ->
            rig.make("Host of blank")

            val r = select(searchBody(""","query":"   ","kinds":["document"]"""))

            assertEquals("invalid_request", r.code, r.raw)
            assertTrue("/query" in r.fields(), r.fields().toString())
        }
}

/**
 * S13 (task-scope item 6): the telemetry of a document search, on the MCP surface driven through the real adapters over the
 * in-memory client/server pair and the production tool set. `request_shape` gains `hitsByKind`, one key per SEARCHED kind
 * with zeros kept, counted over the capped result (so independent of the page); every document hit on the returned page
 * records `target_versions[documentId] = version`; the query text is never stored; a search rejected by validation records
 * no `hitsByKind`. A document id is not on the wire, so the ids are the ones [DocumentService.write] returned.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class ItemsReadDocumentSearchTelemetryTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    @TempDir
    lateinit var dir: Path

    private class Rig(
        val rig: EventLogRig,
        val writer: CallLogWriter,
    )

    private fun newRig(): Rig {
        val rig = EventLogRig.build(db.db, dir)
        return Rig(rig, CallLogWriter(rig.composition.unitOfWork, SqliteCallLogStore(db.databaseManager), flushInterval = 1.hours))
    }

    private suspend fun connect(rig: Rig): Pair<Client, Server> {
        val server = Server(serverInfo = Implementation(name = "q26-server", version = "1.0.0"), options = inMemoryTestServerOptions())
        McpOperationAdapter(buildToolDispatcher(rig.rig.ctx), callLog = rig.writer)
            .register(server, ToolRegistry(buildToolSpecs()), ToolProfile.ORCHESTRATION)
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client =
            Client(
                clientInfo = Implementation(name = "q26-client", version = "1.0.0"),
                options = ClientOptions(capabilities = ClientCapabilities()),
            )
        server.createSession(serverTransport)
        client.connect(clientTransport)
        return client to server
    }

    private suspend fun Client.search(
        query: String,
        kinds: List<String>? = null,
        limit: Int? = null,
        extra: List<Pair<String, JsonElement>> = emptyList(),
    ): CallToolResult {
        val args = mutableListOf<Pair<String, JsonElement>>("select" to JsonPrimitive("search"), "query" to JsonPrimitive(query))
        if (kinds != null) args += "kinds" to JsonArray(kinds.map { JsonPrimitive(it) })
        if (limit != null) args += "limit" to JsonPrimitive(limit)
        args += extra
        return callTool(
            buildCallToolRequest {
                name = ITEMS_READ
                arguments(JsonObject(mapOf(*args.toTypedArray())))
            },
        )
    }

    private suspend fun rowOf(rig: Rig): Map<String, Any?> {
        rig.writer.flushNow()
        return CallLogRows.forTool(db.jdbcUrl, ITEMS_READ).single()
    }

    private fun Map<String, Any?>.shape(): JsonObject? = (get("request_shape") as String?)?.let { Json.parseToJsonElement(it).jsonObject }

    private fun Map<String, Any?>.hitsByKind(): Map<String, Int>? =
        shape()?.get("hitsByKind")?.jsonObject?.mapValues {
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
        val first: Document,
        val second: Document,
    )

    /** One item and two documents match "tlmtoken"; the first document is at version 2, the second at version 1. */
    private suspend fun seed(rig: Rig): Seeded {
        val host = rig.rig.seed("Host of telemetry docs")
        rig.rig.seed("tlmtoken item title")
        rig.rig.writeDoc(host, "doc-one", "tlmtoken first body")
        val first = rig.rig.writeDoc(host, "doc-one", "tlmtoken first body revised", expectedVersion = 1)
        val second = rig.rig.writeDoc(host, "doc-two", "tlmtoken second body")
        return Seeded(first, second)
    }

    @Test
    fun `S13 the default kinds record a count for item note and document with zeros kept and the version of each document hit`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val docs = seed(rig)

                val result = client.search("tlmtoken")
                assertTrue(result.isError != true, "search must succeed: ${result.content}")

                val row = rowOf(rig)
                assertEquals(mapOf("item" to 1, "note" to 0, "document" to 2), row.hitsByKind())
                val versions = row.targetVersions()
                assertEquals(2L, versions[docs.first.id.toString()], "the overwritten document is recorded at version 2: $versions")
                assertEquals(1L, versions[docs.second.id.toString()], "the other document is recorded at version 1: $versions")
                assertEquals(2, docs.first.version)
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 hitsByKind has one key per searched kind only`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                client.search("tlmtoken", kinds = listOf("document"))

                assertEquals(mapOf("document" to 2), rowOf(rig).hitsByKind())
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 an item and document kind set records exactly those two keys`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                client.search("tlmtoken", kinds = listOf("item", "document"))

                assertEquals(mapOf("item" to 1, "document" to 2), rowOf(rig).hitsByKind())
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 a search with no hits records zero for every searched kind`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                client.search("nomatchtokenxyz")

                assertEquals(mapOf("item" to 0, "note" to 0, "document" to 0), rowOf(rig).hitsByKind())
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 the counts cover the whole result and only the documents on the returned page record a version`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                val docs = seed(rig)

                client.search("tlmtoken", kinds = listOf("document"), limit = 1)

                val row = rowOf(rig)
                assertEquals(mapOf("document" to 2), row.hitsByKind(), "the mix counts the capped list, not the page")
                val documentVersions = row.targetVersions().filterKeys { it == docs.first.id.toString() || it == docs.second.id.toString() }
                assertEquals(1, documentVersions.size, "one document is on the page of one: ${row.targetVersions()}")
                val (id, version) = documentVersions.entries.single()
                assertEquals(if (id == docs.first.id.toString()) 2L else 1L, version, "the recorded version is that document's own")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 the query text is never stored in any column`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                client.search("tlmtoken", kinds = listOf("document"))
                rowOf(rig)

                val all = CallLogRows.read(db.jdbcUrl)
                assertTrue(all.isNotEmpty(), "fixture: call_log rows exist")
                all.forEach { row ->
                    row.forEach { (column, value) ->
                        val text = if (value is ByteArray) String(value, Charsets.UTF_8) else value?.toString().orEmpty()
                        assertFalse("tlmtoken" in text.lowercase(), "column '$column' must not contain the raw query word: $text")
                    }
                }
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    @Test
    fun `S13 a search rejected by validation records no hit mix`(): Unit =
        runBlocking {
            val rig = newRig()
            val (client, server) = connect(rig)
            try {
                seed(rig)

                val result = client.search("tlmtoken", kinds = listOf("document"), extra = listOf("role" to JsonPrimitive("queue")))
                assertEquals(true, result.isError, "documents with a role filter are rejected")

                val row = rowOf(rig)
                assertNull(row.shape()?.get("hitsByKind"), "the search never ran: ${row["request_shape"]}")
                assertTrue(row.targetVersions().isEmpty(), "no version is recorded: ${row["target_versions"]}")
            } finally {
                closeInMemoryPair(client, server)
            }
        }

    private companion object {
        const val ITEMS_READ = "items_read"
    }
}
