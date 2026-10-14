package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.Document
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.DocsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.ParityToken
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.parity.parityAccess
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.fail

/**
 * Independently authored behavioral tests of one visibility rule for the adopter of an adoption in every read view
 * (item 774ac07e, seat test-author, finding N1). No implementation source, diff or commit was opened. Oracles are the
 * frozen queue-phase `task-scope` N1 decision and `test-plan` S1-S3: an `adopted_by` link is provenance that outlives the
 * adopter (D16/C1), so the adopter is shown by `get` (`adoptedBy`), `list` (`adoptedBy`) and `links` (an `adopted_by` row)
 * exactly when the caller's scope admits the adopter's id, whether or not the item still exists; a restricted scope fails
 * closed on a missing item; `delete` of an adopted plan (invalid_request) and a write to it (duplicate) stay refused under
 * every scope.
 *
 * Public evidence used to resolve wire shape: `current/docs/api-reference.md` docs_read rows (`links` item shape
 * `{id, relation, from: {kind, id}, to?: {kind, id}, toRef?, anchor?}`, `get`/`list` carry `adoptedBy?`), and the existing
 * parity catalog (absent optional keys are omitted from the body, not null).
 *
 * Fixture invariants (satisfied by construction): the root is a depth-0 item seeded through `EventLogRig.seed`; the adopter
 * is its child (depth 1, the root's root id); the plan is written against the root and adopted through the production
 * `DocumentService.adopt`; the adopter is deleted through the production `ItemCommandService.delete` (non-recursive leaf),
 * except in the orphan probe which deletes through the repository exactly as the existing `B1` fix-round tests do.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DocsAdoptedByDeletedAdopterTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val registry = ToolRegistry(buildToolSpecs())
    private val scenarios = DocsParityScenarios
    private val slug = "adopted-plan"

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was Err: ${error.code} ${error.message}")
        }

    private fun McpResult.all(): String = text + structured.toString()

    private fun McpResult.data(): JsonObject = structured.getValue("data").jsonObject

    private fun McpResult.items(): List<JsonObject> = data().getValue("items").jsonArray.map { it.jsonObject }

    private fun McpResult.total(): Int =
        data()
            .getValue("page")
            .jsonObject
            .getValue("total")
            .jsonPrimitive.int

    private fun McpResult.elementCode(): String =
        data()
            .getValue("results")
            .jsonArray[0]
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.refId(key: String): String =
        getValue(key)
            .jsonObject
            .getValue("id")
            .jsonPrimitive.content

    private suspend fun readAs(
        rig: EventLogRig,
        token: ParityToken?,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, token?.let { t -> { parityAccess(t) } }, "docs_read", obj(body))

    private suspend fun writeAs(
        rig: EventLogRig,
        token: ParityToken?,
        body: String
    ): McpResult = callMcp(rig.ctx, registry, token?.let { t -> { parityAccess(t) } }, "docs_write", obj(body))

    private fun readerOf(vararg roots: UUID) = ParityToken("adopt-root-reader", setOf(ApiCapability.READ), rootIds = roots.toSet())

    private fun writerOf(vararg roots: UUID) =
        ParityToken("adopt-root-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS), rootIds = roots.toSet())

    private val tagReader = ParityToken("adopt-tag-reader", setOf(ApiCapability.READ), tagsInclude = setOf("alpha"))
    private val tagWriter =
        ParityToken("adopt-tag-writer", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS), tagsInclude = setOf("alpha"))

    private class World(
        val root: WorkItem,
        val adopter: WorkItem,
        val plan: Document
    )

    /** Root R (tag alpha), its child adopter (tag [adopterTags]), plan [slug] adopted by the adopter, optionally then deleted. */
    private suspend fun world(
        rig: EventLogRig,
        deleteAdopter: Boolean,
        adopterTags: String = "alpha"
    ): World {
        val root = rig.seed("root", tags = "alpha")
        val adopter = rig.seed("adopter", root, tags = adopterTags)
        val plan =
            rig.ctx.documentService
                .write(root.id, DocumentKind.PLAN, slug, "plan body")
                .value()
        rig.ctx.documentService
            .adopt(root.id, slug, adopter.id)
            .value()
        if (deleteAdopter) {
            rig.ctx.itemCommandService
                .delete(adopter.id, recursive = false, caller = null)
                .value()
        }
        return World(root, adopter, plan)
    }

    private suspend fun assertAdopterShown(
        rig: EventLogRig,
        token: ParityToken?,
        w: World,
        label: String
    ) {
        val get = readAs(rig, token, scenarios.get(w.root.id, "plan", slug))
        assertFalse(get.isError, "$label get: ${get.text}")
        assertEquals(w.adopter.id.toString(), get.data().text("adoptedBy"), "$label: get shows the adopter")

        val list = readAs(rig, token, scenarios.list(w.root.id, ""","kind":"plan""""))
        assertFalse(list.isError, "$label list: ${list.text}")
        assertEquals(
            w.adopter.id.toString(),
            list.items().single { it.text("slug") == slug }.text("adoptedBy"),
            "$label: list shows the adopter"
        )

        val links = readAs(rig, token, scenarios.links(w.root.id, "plan", slug))
        assertFalse(links.isError, "$label links: ${links.text}")
        assertEquals(1, links.total(), "$label: links total counts the adoption row: ${links.text}")
        val row = links.items().single()
        assertEquals("adopted_by", row.text("relation"), label)
        assertEquals(w.plan.id.toString(), row.refId("from"), "$label: the row starts at the adopted plan")
        assertEquals(w.adopter.id.toString(), row.refId("to"), "$label: the row names the adopter")
        assertFalse(row.containsKey("toRef"), "$label: an adoption row carries no reference text: $row")
    }

    private suspend fun assertAdopterHidden(
        rig: EventLogRig,
        token: ParityToken,
        w: World,
        label: String
    ) {
        val get = readAs(rig, token, scenarios.get(w.root.id, "plan", slug))
        assertFalse(get.isError, "$label get: ${get.text}")
        assertEquals(slug, get.data().text("slug"), "$label: control - the plan itself is readable")
        assertFalse(get.data().containsKey("adoptedBy"), "$label: get omits the adopter: ${get.text}")
        assertFalse(w.adopter.id.toString() in get.all(), "$label: get never names the adopter")

        val list = readAs(rig, token, scenarios.list(w.root.id, ""","kind":"plan""""))
        assertFalse(list.isError, "$label list: ${list.text}")
        val entry = list.items().single { it.text("slug") == slug }
        assertFalse(entry.containsKey("adoptedBy"), "$label: list omits the adopter: $entry")
        assertFalse(w.adopter.id.toString() in list.all(), "$label: list never names the adopter")

        val links = readAs(rig, token, scenarios.links(w.root.id, "plan", slug))
        assertFalse(links.isError, "$label links: ${links.text}")
        assertEquals(0, links.total(), "$label: links omits the adoption row and its total excludes it: ${links.text}")
        assertEquals(emptyList(), links.items(), label)
        assertFalse(w.adopter.id.toString() in links.all(), "$label: links never names the adopter")
    }

    private suspend fun assertWritesStillRefused(
        rig: EventLogRig,
        token: ParityToken?,
        w: World,
        label: String
    ) {
        val delete = writeAs(rig, token, scenarios.ops(scenarios.op("delete", w.root.id, "plan", slug)))
        assertEquals("invalid_request", delete.elementCode(), "$label: delete of an adopted plan stays refused: ${delete.text}")
        val upsert = writeAs(rig, token, scenarios.ops(scenarios.upsert(w.root.id, "plan", slug, "replacement body")))
        assertEquals("duplicate", upsert.elementCode(), "$label: a write to an adopted plan stays a duplicate: ${upsert.text}")
        val stored = assertNotNull(rig.raw.documentStore().get(w.root.id, DocumentKind.PLAN, slug), "$label: the plan survives")
        assertEquals("plan body", stored.body, "$label: and keeps its body")
    }

    // ---------------------------------------------------------------- S1 unrestricted

    @Test
    fun `S1 an unrestricted reader sees the deleted adopter in get, list and links, and writes stay refused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = true)
            assertEquals(null, rig.raw.workItemRepository().getById(w.adopter.id), "control: the adopter is gone")

            assertAdopterShown(rig, null, w, "S1 unrestricted")
            assertWritesStillRefused(rig, null, w, "S1 unrestricted")
        }

    // ---------------------------------------------------------------- S2 restricted: fail closed

    @Test
    fun `S2 a root-scoped reader never sees a deleted adopter in any view and writes stay refused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = true)

            assertAdopterHidden(rig, readerOf(w.root.id), w, "S2 root scope")
            assertWritesStillRefused(rig, writerOf(w.root.id), w, "S2 root scope")
        }

    @Test
    fun `S2 a tag-scoped reader never sees a deleted adopter in any view and writes stay refused`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = true)

            assertAdopterHidden(rig, tagReader, w, "S2 tag scope")
            assertWritesStillRefused(rig, tagWriter, w, "S2 tag scope")
        }

    @Test
    fun `S2 probe a live adopter outside the tag scope is hidden in every view exactly like a deleted one`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = false, adopterTags = "beta")
            assertNotNull(rig.raw.workItemRepository().getById(w.adopter.id), "control: the adopter still exists")

            assertAdopterHidden(rig, tagReader, w, "S2 live out-of-scope adopter")
            assertWritesStillRefused(rig, tagWriter, w, "S2 live out-of-scope adopter")
        }

    // ---------------------------------------------------------------- S3 control: live admitted adopter

    @Test
    fun `S3 control - a live admitted adopter is shown in every view unrestricted, root-scoped and tag-scoped`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = false)

            assertAdopterShown(rig, null, w, "S3 unrestricted")
            assertAdopterShown(rig, readerOf(w.root.id), w, "S3 root scope")
            assertAdopterShown(rig, tagReader, w, "S3 tag scope")
            assertWritesStillRefused(rig, null, w, "S3 live adopter")
        }

    // ---------------------------------------------------------------- probes

    @Test
    fun `P1 an attachment to a deleted item stays dropped while the adoption by a deleted item is listed`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root", tags = "alpha")
            val adopter = rig.seed("adopter", root, tags = "alpha")
            val target = rig.seed("attach target", root, tags = "alpha")
            val plan =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PLAN, slug, "plan body")
                    .value()
            rig.ctx.documentService
                .adopt(root.id, slug, adopter.id)
                .value()
            rig.ctx.linkService
                .attach(root.id, DocumentKind.PLAN, slug, target.id)
                .value()
            val live = readAs(rig, null, scenarios.links(root.id, "plan", slug))
            assertEquals(2, live.total(), "control: both relations are listed while both items exist: ${live.text}")
            rig.raw.workItemRepository().delete(adopter.id)
            rig.raw.workItemRepository().delete(target.id)

            val result = readAs(rig, null, scenarios.links(root.id, "plan", slug))

            assertFalse(result.isError, result.text)
            assertEquals(1, result.total(), result.text)
            val row = result.items().single()
            assertEquals("adopted_by", row.text("relation"))
            assertEquals(plan.id.toString(), row.refId("from"))
            assertEquals(adopter.id.toString(), row.refId("to"))
            assertFalse(target.id.toString() in result.all(), "the attachment to the deleted item is not listed: ${result.text}")
        }

    @Test
    fun `P2 replaying the links read of a deleted adopter lists the identical row`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val w = world(rig, deleteAdopter = true)

            val first = readAs(rig, null, scenarios.links(w.root.id, "plan", slug))
            val second = readAs(rig, null, scenarios.links(w.root.id, "plan", slug))

            assertTrue(!first.isError && !second.isError, first.text + second.text)
            assertEquals(first.items(), second.items(), "a replayed read lists the identical rows")
            assertEquals(1, first.items().size)
        }
}
