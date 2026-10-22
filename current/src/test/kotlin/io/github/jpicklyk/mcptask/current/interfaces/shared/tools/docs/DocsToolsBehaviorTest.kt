package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.EntityRef
import io.github.jpicklyk.mcptask.current.domain.model.RefKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.OpsToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.SelectToolSpec
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.DocsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.installParityRestApp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.parity.parityAuthConfig
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 8e99f1e2, Q25) for the parts of `docs_read` / `docs_write` the parity harness cannot
 * express: the registration shape, the multi-call lifecycle with stored rows and event rows (S1, S2, S5, S6, S7), the size caps
 * (S9), `bodyFromFile` over MCP and its refusal over REST (S13), the idempotent replay (S21), cursor paging, event attribution,
 * the ambiguous-prefix probe, and the removal of the 3.x surface (S16).
 *
 * Oracles: the declared registration (names, order, capabilities, destructive flags, error sets); the `DocumentService` KDoc (hash
 * of the LF body, CRLF to LF only, created iff version 1, caps 65536 / 16384 UTF-8 bytes); the golden-declared `bodyFromFile`
 * contract (strictly inside the agent config root, at most 65536 bytes, MCP only); envelope idempotency (same key and payload
 * replays the stored element result flagged `replayed`, executing once; another payload is `idempotency_mismatch`) and paging
 * (`truncated` with `next`, exact `total`, bad cursor `invalid_cursor`); the ToolDispatcher order block (a verified access
 * principal replaces the body actor, with no access seam the body actor is lifted); plan section 4.4 (the 3.x tool and routes are
 * gone).
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DocsToolsBehaviorTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val registry = ToolRegistry(buildToolSpecs())
    private val scenarios = DocsParityScenarios

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val McpResult.body: String get() = structured.toString()

    private fun McpResult.data(): JsonObject = structured.getValue("data").jsonObject

    private fun McpResult.results(): List<JsonObject> = data().getValue("results").jsonArray.map { it.jsonObject }

    private fun McpResult.items(): List<JsonObject> = data().getValue("items").jsonArray.map { it.jsonObject }

    private fun McpResult.page(): JsonObject = data().getValue("page").jsonObject

    private fun McpResult.errorCode(): String =
        structured
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun McpResult.elementCode(index: Int = 0): String =
        results()[index]
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content

    private suspend fun write(
        rig: EventLogRig,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, null, "docs_write", obj(body))

    private suspend fun read(
        rig: EventLogRig,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, null, "docs_read", obj(body))

    private suspend fun upsert(
        rig: EventLogRig,
        root: UUID,
        slug: String,
        body: String,
        kind: String = "plan",
        extra: String = ""
    ): McpResult = write(rig, scenarios.ops(scenarios.upsert(root, kind, slug, body, extra)))

    private suspend fun eventTypes(rig: EventLogRig): List<String> = rig.rows().map { it.type }

    private fun count(sql: String): Int =
        DriverManager.getConnection(db.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use {
                    it.next()
                    it.getInt(1)
                }
            }
        }

    private val agent = """"actor":{"id":"agent-1","kind":"subagent"}"""

    // ------------------------------------------------------------------ registration shape (declarations)

    @Test
    fun `docs_read and docs_write are registered with the declared members, capabilities and error sets`() {
        val names = registry.tools.map { it.name }
        assertTrue("docs_read" in names && "docs_write" in names, "both tools registered: $names")
        assertTrue(names.indexOf("docs_read") < names.indexOf("docs_write"), "registration order read then write")

        val read = registry["docs_read"] as SelectToolSpec
        assertEquals(listOf("get", "list", "history", "links"), read.selects.map { it.name })
        assertTrue(read.selects.all { it.capability == ApiCapability.READ }, "reads need READ")
        val readErrors = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN)
        for (select in read.selects) assertEquals(readErrors, select.errors, "errors of ${select.name}")

        val write = registry["docs_write"] as OpsToolSpec<*>
        assertEquals(listOf("upsert", "archive", "unarchive", "delete", "attach", "detach"), write.ops.map { it.name })
        assertTrue(write.ops.all { it.capability == ApiCapability.WRITE_DOCS }, "every write op needs WRITE_DOCS")
        assertEquals(listOf("delete", "detach"), write.ops.filter { it.destructive }.map { it.name }, "delete and detach are destructive")

        val base = setOf(ErrorCode.NOT_FOUND, ErrorCode.AMBIGUOUS_ID, ErrorCode.FORBIDDEN)
        assertEquals(
            base + setOf(ErrorCode.VERSION_CONFLICT, ErrorCode.PAYLOAD_TOO_LARGE, ErrorCode.DUPLICATE),
            write.ops.single { it.name == "upsert" }.errors
        )
        assertEquals(base + ErrorCode.VERSION_CONFLICT, write.ops.single { it.name == "archive" }.errors)
        assertEquals(base + ErrorCode.VERSION_CONFLICT, write.ops.single { it.name == "unarchive" }.errors)
        for (name in listOf("delete", "attach", "detach")) assertEquals(base, write.ops.single { it.name == name }.errors, name)
    }

    // ------------------------------------------------------------------ S1 / S2 / S5 / S6 lifecycle

    @Test
    fun `S1 S2 S5 S6 a document goes through create, update, archive, unarchive and delete with the stored rows and events of each step`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")

            // S1: CRLF becomes LF before storing and hashing; created iff version 1
            val created = upsert(rig, root.id, "p1", """line1\r\nline2""", extra = ""","expectedVersion":0""")
            assertFalse(created.isError, created.text)
            val first = created.results().single()
            assertEquals("true", first.str("created"))
            assertEquals(1, first.getValue("version").jsonPrimitive.int)
            assertEquals(scenarios.hashOf("line1\nline2"), first.str("contentHash"))
            val stored = assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1"))
            assertEquals("line1\nline2", stored.body)
            assertEquals(listOf("document.created"), eventTypes(rig))

            // S2: expectedVersion 1 -> version 2; created is false; history lists both versions oldest first
            val updated = upsert(rig, root.id, "p1", "second", extra = ""","expectedVersion":1""")
            assertFalse(updated.isError, updated.text)
            assertEquals("false", updated.results().single().str("created"))
            assertEquals(
                2,
                updated
                    .results()
                    .single()
                    .getValue("version")
                    .jsonPrimitive.int
            )
            assertEquals(listOf("document.created", "document.updated"), eventTypes(rig))
            assertEquals(2, count("SELECT count(*) FROM document_versions"))
            assertEquals(1, count("SELECT count(*) FROM documents"))

            val history = read(rig, scenarios.history(root.id, "plan", "p1"))
            assertFalse(history.isError, history.text)
            assertEquals(listOf(1, 2), history.items().map { it.getValue("version").jsonPrimitive.int })
            assertEquals(listOf(11, 6), history.items().map { it.getValue("bodyLength").jsonPrimitive.int })

            val old = read(rig, scenarios.get(root.id, "plan", "p1", ""","version":1"""))
            assertEquals("line1\nline2", old.data().str("body"))
            val current = read(rig, scenarios.get(root.id, "plan", "p1"))
            assertEquals("second", current.data().str("body"))
            assertEquals(
                2,
                current
                    .data()
                    .getValue("version")
                    .jsonPrimitive.int
            )
            assertEquals(scenarios.hashOf("second"), current.data().str("contentHash"))

            // S5: archive records one event; a second archive records none; unarchive restores
            val archived = write(rig, scenarios.ops(scenarios.op("archive", root.id, "plan", "p1")))
            assertFalse(archived.isError, archived.text)
            assertEquals("true", archived.results().single().str("archived"))
            assertEquals(listOf("document.created", "document.updated", "document.archived"), eventTypes(rig))
            val afterArchive = rig.rows().size
            val again = write(rig, scenarios.ops(scenarios.op("archive", root.id, "plan", "p1")))
            assertFalse(again.isError, again.text)
            assertEquals(afterArchive, rig.rows().size, "a second archive records no event")
            val whileArchived = read(rig, scenarios.get(root.id, "plan", "p1"))
            val archivedAt = whileArchived.data()["archivedAt"]
            assertTrue(archivedAt != null && archivedAt !is JsonNull, "an archived document reports archivedAt: ${whileArchived.data()}")

            val restored = write(rig, scenarios.ops(scenarios.op("unarchive", root.id, "plan", "p1")))
            assertFalse(restored.isError, restored.text)
            assertEquals("false", restored.results().single().str("archived"))
            val afterUnarchive = read(rig, scenarios.get(root.id, "plan", "p1"))
            val restoredAt = afterUnarchive.data()["archivedAt"]
            assertTrue(restoredAt == null || restoredAt is JsonNull, "an unarchived document has no archivedAt: ${afterUnarchive.data()}")
            val rewritten = upsert(rig, root.id, "p1", "third")
            assertFalse(rewritten.isError, "writing works again after unarchive: ${rewritten.text}")
            assertEquals("third", assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1")).body)

            // S6: delete removes the document, its versions and records one event
            val deleted = write(rig, scenarios.ops(scenarios.op("delete", root.id, "plan", "p1")))
            assertFalse(deleted.isError, deleted.text)
            assertEquals("true", deleted.results().single().str("deleted"))
            assertEquals(1, eventTypes(rig).count { it == "document.deleted" })
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1"))
            assertEquals(0, count("SELECT count(*) FROM documents"))
            assertEquals(0, count("SELECT count(*) FROM document_versions"))
            val gone = read(rig, scenarios.get(root.id, "plan", "p1"))
            assertTrue(gone.isError)
            assertEquals("not_found", gone.errorCode())
        }

    @Test
    fun `S6 deleting a page that a note links to leaves the note's link dangling with its target text`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val child = rig.seed("child", root)
            assertFalse(upsert(rig, root.id, "pg", "page body", kind = "page").isError)
            val noteId =
                (
                    rig.ctx.noteCommandService.upsert(
                        NoteUpsertCommand(child.id, "k", "work", "see [[page:pg]]", null, null, caller = null)
                    )
                        as Outcome.Ok
                ).value.note.id
            val before =
                rig.raw
                    .linkStore()
                    .findFrom(EntityRef(RefKind.NOTE, noteId))
                    .single()
            assertNotNull(before.to, "the link starts resolved")

            val deleted = write(rig, scenarios.ops(scenarios.op("delete", root.id, "page", "pg")))
            assertFalse(deleted.isError, deleted.text)

            val after =
                rig.raw
                    .linkStore()
                    .findFrom(EntityRef(RefKind.NOTE, noteId))
                    .single()
            assertNull(after.to, "the link is now dangling")
            assertEquals("page:pg", after.toRef)
        }

    // ------------------------------------------------------------------ S18 scope on secondary ends

    private class LinkWorld(
        val root: WorkItem,
        val alphaChild: WorkItem,
        val plainChild: WorkItem,
        val alphaNoteId: UUID,
        val plainNoteId: UUID
    )

    /**
     * A tagged root R (tag alpha) with an admitted child C (tag alpha) and an untagged child P; the plan `alpha-plan` links out to
     * the page `alpha-page` and to P (attachment), and is referenced by a note on C and a note on P. A tag-scoped token (alpha)
     * admits R and C but not P.
     */
    private suspend fun linkWorld(rig: EventLogRig): LinkWorld {
        val root = rig.seed("root", tags = "alpha")
        val alphaChild = rig.seed("alpha child", root, tags = "alpha")
        val plainChild = rig.seed("plain child", root)
        assertFalse(upsert(rig, root.id, "alpha-page", "page body", kind = "page").isError)
        assertFalse(upsert(rig, root.id, "alpha-plan", "see [[page:alpha-page]]").isError)
        val alphaNote =
            (
                rig.ctx.noteCommandService.upsert(
                    NoteUpsertCommand(alphaChild.id, "k1", "work", "see [[plan:alpha-plan]]", null, null, caller = null)
                ) as Outcome.Ok
            ).value.note.id
        val plainNote =
            (
                rig.ctx.noteCommandService.upsert(
                    NoteUpsertCommand(plainChild.id, "k2", "work", "also [[plan:alpha-plan]]", null, null, caller = null)
                ) as Outcome.Ok
            ).value.note.id
        val attached = write(rig, scenarios.ops(scenarios.attach("attach", root.id, "plan", "alpha-plan", plainChild.id)))
        assertFalse(attached.isError, attached.text)
        return LinkWorld(root, alphaChild, plainChild, alphaNote, plainNote)
    }

    private suspend fun readAs(
        rig: EventLogRig,
        token: ParityToken,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, { parityAccess(token) }, "docs_read", obj(body))

    @Test
    fun `S18 control - an unrestricted reader sees all four links, including the ones that name the untagged item`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val world = linkWorld(rig)

            val result = readAs(rig, DocsParityScenarios.reader, scenarios.links(world.root.id, "plan", "alpha-plan"))

            assertFalse(result.isError, result.text)
            assertEquals(
                4,
                result
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int,
                result.text
            )
            assertTrue(world.plainChild.id.toString() in result.body, "the attachment to the untagged item is listed")
            assertTrue(world.plainNoteId.toString() in result.body, "the note on the untagged item is listed")
            assertTrue(world.alphaNoteId.toString() in result.body, "the note on the admitted item is listed")
        }

    @Test
    fun `S18 a tag-scoped reader gets only the links whose ends it admits, and the total excludes the dropped ones`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val world = linkWorld(rig)

            val result = readAs(rig, DocsParityScenarios.tagReader, scenarios.links(world.root.id, "plan", "alpha-plan"))

            assertFalse(result.isError, result.text)
            assertEquals(
                2,
                result
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int,
                result.text
            )
            assertEquals(2, result.items().size, result.text)
            assertFalse(world.plainChild.id.toString() in result.body, "the untagged item never appears: ${result.text}")
            assertFalse(world.plainNoteId.toString() in result.body, "the note on the untagged item never appears: ${result.text}")
            assertTrue(world.alphaNoteId.toString() in result.body, "the note on the admitted item stays: ${result.text}")
        }

    @Test
    fun `S18 paging the tag-scoped links with limit 1 yields the two admitted links and nothing else`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val world = linkWorld(rig)
            val token = DocsParityScenarios.tagReader

            val first = readAs(rig, token, scenarios.links(world.root.id, "plan", "alpha-plan", ""","limit":1"""))
            assertFalse(first.isError, first.text)
            assertEquals(1, first.items().size)
            assertEquals(
                2,
                first
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int,
                "total counts admitted links only"
            )
            assertTrue(
                first
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.boolean
            )
            val second =
                readAs(
                    rig,
                    token,
                    scenarios.links(world.root.id, "plan", "alpha-plan", ""","limit":1,"cursor":"${first.page().str("next")}"""")
                )
            assertFalse(second.isError, second.text)
            assertEquals(1, second.items().size)
            assertFalse(
                second
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.boolean
            )

            val ids = (first.items() + second.items()).map { it.str("id") }
            assertEquals(2, ids.toSet().size, "two distinct links")
            val text = first.body + second.body
            assertFalse(world.plainChild.id.toString() in text, text)
            assertFalse(world.plainNoteId.toString() in text, text)
        }

    @Test
    fun `S18 a tag-scoped writer cannot attach the untagged item and nothing is written`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root", tags = "alpha")
            val plain = rig.seed("plain child", root)
            val admitted = rig.seed("alpha child", root, tags = "alpha")
            assertFalse(upsert(rig, root.id, "alpha-plan", "x").isError)
            val events = rig.rows().size
            val token = DocsParityScenarios.tagWriter

            val refused =
                callMcp(
                    rig.ctx,
                    registry,
                    { parityAccess(token) },
                    "docs_write",
                    obj(scenarios.ops(scenarios.attach("attach", root.id, "plan", "alpha-plan", plain.id)))
                )
            assertEquals("forbidden", refused.elementCode())
            assertEquals(0, count("SELECT count(*) FROM links WHERE relation = 'attached'"))
            assertEquals(events, rig.rows().size, "a forbidden attach records no event")

            val allowed =
                callMcp(
                    rig.ctx,
                    registry,
                    { parityAccess(token) },
                    "docs_write",
                    obj(scenarios.ops(scenarios.attach("attach", root.id, "plan", "alpha-plan", admitted.id)))
                )
            assertFalse(allowed.isError, "control: an admitted item is attachable by the same token: ${allowed.text}")
            assertEquals(1, count("SELECT count(*) FROM links WHERE relation = 'attached'"))
        }

    // ------------------------------------------------------------------ S7 attach / detach

    @Test
    fun `S7 attach twice returns the same link, detach returns 1 then 0`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val child = rig.seed("child", root)
            assertFalse(upsert(rig, root.id, "p1", "x").isError)

            val first = write(rig, scenarios.ops(scenarios.attach("attach", root.id, "plan", "p1", child.id)))
            assertFalse(first.isError, first.text)
            val firstLink = first.results().single().str("linkId")
            val second = write(rig, scenarios.ops(scenarios.attach("attach", root.id, "plan", "p1", child.id)))
            assertFalse(second.isError, second.text)
            assertEquals(firstLink, second.results().single().str("linkId"), "the second attach returns the existing link")
            assertEquals(1, count("SELECT count(*) FROM links WHERE relation = 'attached'"))

            val detached = write(rig, scenarios.ops(scenarios.attach("detach", root.id, "plan", "p1", child.id)))
            assertEquals(
                1,
                detached
                    .results()
                    .single()
                    .getValue("detached")
                    .jsonPrimitive.int
            )
            val detachedAgain = write(rig, scenarios.ops(scenarios.attach("detach", root.id, "plan", "p1", child.id)))
            assertEquals(
                0,
                detachedAgain
                    .results()
                    .single()
                    .getValue("detached")
                    .jsonPrimitive.int
            )
            assertEquals(0, count("SELECT count(*) FROM links WHERE relation = 'attached'"))
        }

    // ------------------------------------------------------------------ S9 size caps (UTF-8 bytes)

    @Test
    fun `S9 a rule body of 16384 bytes is stored and 16385 is payload_too_large`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")

            val atCap = upsert(rig, root.id, "at-cap", "r".repeat(16384), kind = "rule")
            assertFalse(atCap.isError, "control: exactly the cap is accepted: ${atCap.text}")
            val over = upsert(rig, root.id, "over", "r".repeat(16385), kind = "rule")
            assertEquals("payload_too_large", over.elementCode())
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.RULE, "over"), "nothing is stored over the cap")
            assertEquals(1, count("SELECT count(*) FROM documents"))
        }

    @Test
    fun `S9 a plan body of 65536 bytes is stored and 65537 is payload_too_large`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")

            val atCap = upsert(rig, root.id, "at-cap", "p".repeat(65536))
            assertFalse(atCap.isError, "control: exactly the cap is accepted: ${atCap.text}")
            val over = upsert(rig, root.id, "over", "p".repeat(65537))
            assertEquals("payload_too_large", over.elementCode())
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "over"))
            assertEquals(1, count("SELECT count(*) FROM documents"))
        }

    @Test
    fun `S9 the cap counts UTF-8 bytes, not characters`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val two = "é" // two bytes in UTF-8

            val atCap = upsert(rig, root.id, "at-cap", two.repeat(8192), kind = "rule")
            assertFalse(atCap.isError, "8192 two-byte characters are exactly 16384 bytes: ${atCap.text}")
            val over = upsert(rig, root.id, "over", two.repeat(8193), kind = "rule")
            assertEquals("payload_too_large", over.elementCode(), "8193 characters are 16386 bytes")
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.RULE, "over"))
        }

    @Test
    fun `S9 an over-cap overwrite keeps the stored version`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            assertFalse(upsert(rig, root.id, "r1", "small", kind = "rule").isError)
            val events = rig.rows().size

            val over = upsert(rig, root.id, "r1", "r".repeat(16385), kind = "rule")

            assertEquals("payload_too_large", over.elementCode())
            val kept = assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.RULE, "r1"))
            assertEquals("small", kept.body)
            assertEquals(1, kept.version)
            assertEquals(events, rig.rows().size, "no event for a refused write")
        }

    // ------------------------------------------------------------------ S13 bodyFromFile

    @Test
    fun `S13 over MCP a contained file is read with CRLF normalized, escapes and both-or-neither are refused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val cfg = dir.resolve("cfg")
            val rig = EventLogRig.build(db.db, cfg)
            val root = rig.seed("root")
            Files.write(cfg.resolve("body.txt"), "from file\r\nline2".toByteArray(Charsets.UTF_8))
            Files.write(dir.resolve("outside.txt"), "OUTSIDE-SECRET".toByteArray(Charsets.UTF_8))
            Files.write(cfg.resolve("at-cap.txt"), "p".repeat(65536).toByteArray(Charsets.UTF_8))
            Files.write(cfg.resolve("over-cap.txt"), "p".repeat(65537).toByteArray(Charsets.UTF_8))

            val ok = write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "via-file", ""","bodyFromFile":"body.txt"""")))
            assertFalse(ok.isError, ok.text)
            assertEquals("from file\nline2", assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "via-file")).body)
            assertEquals(scenarios.hashOf("from file\nline2"), ok.results().single().str("contentHash"))

            val atCap = write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "at-cap", ""","bodyFromFile":"at-cap.txt"""")))
            assertFalse(atCap.isError, "control: exactly 65536 bytes is accepted: ${atCap.text}")

            val escape =
                write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "escape", ""","bodyFromFile":"../outside.txt"""")))
            assertEquals("invalid_request", escape.elementCode())
            assertFalse("OUTSIDE-SECRET" in escape.body, "the outside text never comes back")
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "escape"))

            val absolute = cfg.resolve("body.txt").toAbsolutePath().toString()
            val abs =
                write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "abs", ""","bodyFromFile":${JsonPrimitive(absolute)}""")))
            assertEquals("invalid_request", abs.elementCode())
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "abs"))

            val over = write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "over", ""","bodyFromFile":"over-cap.txt"""")))
            assertTrue(over.isError, "a file over the cap is refused: ${over.text}")
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "over"))

            val missing = write(rig, scenarios.ops(scenarios.op("upsert", root.id, "plan", "missing", ""","bodyFromFile":"no-such.txt"""")))
            assertTrue(missing.isError, "a missing file is refused: ${missing.text}")
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "missing"))

            val both =
                write(rig, scenarios.ops(scenarios.upsert(root.id, "plan", "both", "inline", ""","bodyFromFile":"body.txt"""")))
            assertEquals("invalid_request", both.elementCode())
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "both"))
        }

    @Test
    fun `S13 over REST bodyFromFile is refused before any file is opened, with the same call refused for an existing and a missing file`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val cfg = dir.resolve("cfg")
            val rig = EventLogRig.build(db.db, cfg)
            val root = rig.seed("root")
            Files.write(cfg.resolve("body.txt"), "from file".toByteArray(Charsets.UTF_8))
            val token = DocsParityScenarios.admin
            val refusal = "bodyFromFile is available over MCP only; send the document text inline in body"
            val existing = scenarios.ops(scenarios.op("upsert", root.id, "plan", "via-rest", ""","bodyFromFile":"body.txt""""))
            val missing = scenarios.ops(scenarios.op("upsert", root.id, "plan", "via-rest-missing", ""","bodyFromFile":"no-such.txt""""))
            val inline = scenarios.ops(scenarios.upsert(root.id, "plan", "inline-rest", "inline text"))

            testApplication {
                application { installParityRestApp(rig, parityAuthConfig(token), registry) }

                suspend fun post(body: String) =
                    client.post("/api/v1/ops/docs_write") {
                        header(HttpHeaders.Authorization, "Bearer ${token.raw}")
                        contentType(ContentType.Application.Json)
                        setBody(body)
                    }

                val control = post(inline)
                assertEquals(200, control.status.value, "control: an inline body is accepted over REST: ${control.bodyAsText()}")

                for (body in listOf(existing, missing)) {
                    val response = post(body)
                    val text = response.bodyAsText()
                    assertEquals(207, response.status.value, text)
                    assertTrue(refusal in text, "the refusal names the MCP-only rule, whether or not the file exists: $text")
                    assertTrue("\"code\":\"invalid_request\"" in text, text)
                }
            }
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "via-rest"), "the existing file was not read")
            assertNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "via-rest-missing"))
            assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "inline-rest"))
        }

    // ------------------------------------------------------------------ S21 idempotency

    @Test
    fun `S21 the same idempotency key and payload is replayed, the version does not move and one event is recorded`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val body = """{$agent,"ops":[${scenarios.upsert(root.id, "plan", "p1", "once", ""","idempotencyKey":"k-1"""")}]}"""

            val first = write(rig, body)
            assertFalse(first.isError, first.text)
            assertNull(first.results().single()["replayed"], "the first call executes")

            val second = write(rig, body)
            assertFalse(second.isError, second.text)
            assertEquals("true", second.results().single().str("replayed"))
            assertEquals(first.results().single().str("id"), second.results().single().str("id"), "the replay returns the stored result")
            assertEquals(
                1,
                second
                    .results()
                    .single()
                    .getValue("version")
                    .jsonPrimitive.int,
                "the version does not move"
            )

            assertEquals(1, assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1")).version)
            assertEquals(1, eventTypes(rig).count { it == "document.created" })
        }

    @Test
    fun `S21 the same key with another payload is idempotency_mismatch and stores nothing more`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val one = """{$agent,"ops":[${scenarios.upsert(root.id, "plan", "p1", "one", ""","idempotencyKey":"k-1"""")}]}"""
            val other = """{$agent,"ops":[${scenarios.upsert(root.id, "plan", "p1", "two", ""","idempotencyKey":"k-1"""")}]}"""
            assertFalse(write(rig, one).isError)

            val mismatch = write(rig, other)

            assertEquals("idempotency_mismatch", mismatch.elementCode())
            val kept = assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1"))
            assertEquals("one", kept.body)
            assertEquals(1, kept.version)
        }

    // ------------------------------------------------------------------ paging

    @Test
    fun `list of three plans with limit 2 is truncated with a cursor, the cursor returns the rest and order is by slug within a kind`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            for (slug in listOf("c-plan", "a-plan", "b-plan")) assertFalse(upsert(rig, root.id, slug, "x").isError)

            val first = read(rig, scenarios.list(root.id, ""","kind":"plan","limit":2"""))
            assertFalse(first.isError, first.text)
            assertEquals(listOf("a-plan", "b-plan"), first.items().map { it.str("slug") }, "first page: ${first.structured}")
            assertEquals(
                3,
                first
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int
            )
            assertTrue(
                first
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.boolean
            )
            val next = first.page().str("next")

            val second = read(rig, scenarios.list(root.id, ""","kind":"plan","limit":2,"cursor":"$next""""))
            assertFalse(second.isError, second.text)
            assertEquals(listOf("c-plan"), second.items().map { it.str("slug") }, "second page: ${second.structured}")
            assertEquals(
                3,
                second
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int,
                "total is exact on every page"
            )
            assertFalse(
                second
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.boolean
            )
            assertNull(second.page()["next"], "next is present exactly when truncated")
        }

    @Test
    fun `history of three versions with limit 2 pages oldest first`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            for (body in listOf("one", "two", "three")) assertFalse(upsert(rig, root.id, "p1", body).isError)

            val first = read(rig, scenarios.history(root.id, "plan", "p1", ""","limit":2"""))
            assertEquals(listOf(1, 2), first.items().map { it.getValue("version").jsonPrimitive.int })
            assertTrue(
                first
                    .page()
                    .getValue("truncated")
                    .jsonPrimitive.boolean
            )
            val second = read(rig, scenarios.history(root.id, "plan", "p1", ""","limit":2,"cursor":"${first.page().str("next")}""""))
            assertEquals(listOf(3), second.items().map { it.getValue("version").jsonPrimitive.int })
            assertEquals(
                3,
                second
                    .page()
                    .getValue("total")
                    .jsonPrimitive.int
            )
        }

    @Test
    fun `an undecodable cursor is invalid_cursor`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val result = read(rig, scenarios.list(root.id, ""","cursor":"%%%""""))
            assertTrue(result.isError)
            assertEquals("invalid_cursor", result.errorCode())
        }

    // ------------------------------------------------------------------ title, body and CR probes

    @Test
    fun `a title is stored, kept when omitted on the next upsert and replaced when given`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            assertFalse(upsert(rig, root.id, "p1", "v1", extra = ""","title":"My Title"""").isError)
            assertEquals("My Title", read(rig, scenarios.get(root.id, "plan", "p1")).data().str("title"))

            assertFalse(upsert(rig, root.id, "p1", "v2").isError)
            assertEquals("My Title", read(rig, scenarios.get(root.id, "plan", "p1")).data().str("title"), "omitting title keeps it")

            assertFalse(upsert(rig, root.id, "p1", "v3", extra = ""","title":"Other"""").isError)
            assertEquals("Other", read(rig, scenarios.get(root.id, "plan", "p1")).data().str("title"))
        }

    @Test
    fun `a lone CR survives, CRLF becomes LF and an empty body is stored as empty`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            assertFalse(upsert(rig, root.id, "cr", """a\rb""").isError)
            assertFalse(upsert(rig, root.id, "crlf", """a\r\nb""").isError)
            assertFalse(upsert(rig, root.id, "empty", "").isError)

            assertEquals("a\rb", read(rig, scenarios.get(root.id, "plan", "cr")).data().str("body"))
            assertEquals("a\nb", read(rig, scenarios.get(root.id, "plan", "crlf")).data().str("body"))
            val empty = read(rig, scenarios.get(root.id, "plan", "empty")).data()
            assertEquals("", empty.str("body"))
            assertEquals(scenarios.hashOf(""), empty.str("contentHash"))
        }

    // ------------------------------------------------------------------ attribution

    @Test
    fun `a write on the default path records the lifted actor as the principal of the event and the version`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")

            val created = write(rig, """{$agent,"ops":[${scenarios.upsert(root.id, "plan", "p1", "x")}]}""")
            assertFalse(created.isError, created.text)

            assertEquals("agent-1", rig.rows().single { it.type == "document.created" }.principalId)
            val versionActor =
                DriverManager.getConnection(db.jdbcUrl).use { c ->
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT actor_id FROM document_versions").use {
                            it.next()
                            it.getString(1)
                        }
                    }
                }
            assertEquals("agent-1", versionActor)
        }

    @Test
    fun `control - a write without any actor fixture runs as the local principal`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")

            val created = upsert(rig, root.id, "p1", "x")
            assertFalse(created.isError, created.text)

            assertEquals("local", rig.rows().single { it.type == "document.created" }.principalId, "no actor fixture, the local principal")
        }

    @Test
    fun `a body actor next to a verified access principal is unknown_parameter and writes nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val access = parityAccess(DocsParityScenarios.writer)
            val expected = assertNotNull(access.principal).claim.id

            val result =
                callMcp(
                    rig.ctx,
                    registry,
                    { access },
                    "docs_write",
                    obj("""{"actor":{"id":"intruder","kind":"subagent"},"ops":[${scenarios.upsert(root.id, "plan", "p1", "x")}]}""")
                )
            assertTrue(result.isError, result.text)
            assertEquals(
                "unknown_parameter",
                result.structured
                    .getValue("error")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content
            )
            assertTrue(rig.rows().none { it.type == "document.created" }, "nothing is written")
            assertNotNull(expected)
        }

    // ------------------------------------------------------------------ ambiguous prefix

    @Test
    fun `a root prefix shared by two roots is ambiguous_id and reads nothing`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val one = UUID.fromString("ab000000-0000-4000-8000-0000000000a1")
            val two = UUID.fromString("ab000000-0000-4000-8000-0000000000a2")
            rig.raw.workItemRepository().create(WorkItem(id = one, title = "root one", depth = 0))
            rig.raw.workItemRepository().create(WorkItem(id = two, title = "root two", depth = 0))
            assertFalse(upsert(rig, one, "p1", "x").isError)

            val ambiguous = read(rig, scenarios.list("ab000000"))
            assertTrue(ambiguous.isError, ambiguous.text)
            assertEquals("ambiguous_id", ambiguous.errorCode())

            val full = read(rig, scenarios.list(one))
            assertFalse(full.isError, full.text)
            assertEquals(listOf("p1"), full.items().map { it.str("slug") })
        }

    // ------------------------------------------------------------------ S16 removal of the 3.x surface

    @Test
    fun `S16 manage_plan_documents is not registered anywhere`() {
        assertFalse("manage_plan_documents" in registry.tools.map { it.name })
        val legacy = ToolRegistry(buildToolSpecs()).tools.map { it.name }
        assertFalse("manage_plan_documents" in legacy, "legacy tool list: $legacy")
    }

    @Test
    fun `S16 the 3_x plan routes are gone while the ops endpoint serves the new tools`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            assertFalse(upsert(rig, root.id, "p1", "x").isError)
            val admin = DocsParityScenarios.admin
            testApplication {
                application { installParityRestApp(rig, parityAuthConfig(admin), registry) }

                fun io.ktor.client.request.HttpRequestBuilder.auth() {
                    header(HttpHeaders.Authorization, "Bearer ${admin.raw}")
                }
                val control =
                    client.post("/api/v1/ops/docs_read") {
                        auth()
                        contentType(ContentType.Application.Json)
                        setBody(scenarios.get(root.id, "plan", "p1"))
                    }
                assertEquals(200, control.status.value, "control: the ops endpoint serves docs_read: ${control.bodyAsText()}")
                assertEquals(404, client.get("/api/v1/roots/${root.id}/plans") { auth() }.status.value)
                assertEquals(404, client.get("/api/v1/roots/${root.id}/plans/p1") { auth() }.status.value)
                assertEquals(
                    404,
                    client
                        .put("/api/v1/roots/${root.id}/plans/p1") {
                            auth()
                            contentType(ContentType.Application.Json)
                            setBody("""{"body":"hijack"}""")
                        }.status.value
                )
            }
            assertEquals("x", assertNotNull(rig.raw.documentStore().get(root.id, DocumentKind.PLAN, "p1")).body, "the PUT stored nothing")
        }
}
