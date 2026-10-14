package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.DocumentKind
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.parity.DocsParityScenarios
import io.github.jpicklyk.mcptask.current.parity.McpResult
import io.github.jpicklyk.mcptask.current.parity.callMcp
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.fail

/**
 * Independently authored behavioral test of the `to` / `toRef` shape of the `docs_read` `links` rows (item 774ac07e, seat
 * test-author, finding N2; it pins existing behaviour, so no red is expected). No implementation source, diff or commit was
 * opened. Oracle: the `LinkService` KDoc as quoted by the frozen `task-scope` N2 and `test-plan` S4 (a dangling link has
 * `to_id` NULL with the reference text in `to_ref`; `references` always stores the reference text; `derived_from` stores
 * `plan:<slug>`; `attached` and `adopted_by` store no text) and `current/docs/api-reference.md` (`to?` is absent while a
 * reference names no document yet, `toRef?` is the reference text; absent optional keys are omitted, not null).
 *
 * Fixture invariants (satisfied by construction): the root is seeded with `EventLogRig.seed`; the note owners are its
 * children; every document is written against the root and every note through the production `NoteCommandService`;
 * `derived_from` is recorded by the production `LinkService.recordDerivedFrom` for an existing note and an existing plan;
 * the attachment goes through `LinkService.attach` for an item of the document's own root.
 *
 * Forbidden-construct declaration: none.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DocsLinksToRefShapeTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val registry = ToolRegistry(buildToolSpecs())
    private val scenarios = DocsParityScenarios

    private fun <T> Outcome<T>.value(): T =
        when (this) {
            is Outcome.Ok -> value
            is Outcome.Err -> fail("expected Ok but was Err: ${error.code} ${error.message}")
        }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.refId(key: String): String =
        getValue(key)
            .jsonObject
            .getValue("id")
            .jsonPrimitive.content

    private suspend fun linksOf(
        rig: EventLogRig,
        root: UUID,
        slug: String,
        extra: String = ""
    ): List<JsonObject> {
        val result: McpResult =
            callMcp(
                rig.ctx,
                registry,
                null,
                "docs_read",
                Json.parseToJsonElement(scenarios.links(root, "plan", slug, extra)).jsonObject
            )
        assertFalse(result.isError, result.text)
        return result.structured
            .getValue("data")
            .jsonObject
            .getValue("items")
            .jsonArray
            .map { it.jsonObject }
    }

    @Test
    fun `S4 links rows carry to and toRef as references, dangling references, derived_from and attached links define them`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val owner = rig.seed("note owner", root)
            val attachTarget = rig.seed("attach target", root)
            val page =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PAGE, "pg", "page body")
                    .value()
            val plan =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PLAN, "alpha-plan", "see [[page:pg]] and [[page:missing]]")
                    .value()
            val referencingNote =
                rig.ctx.noteCommandService
                    .upsert(NoteUpsertCommand(owner.id, "refs", "work", "see [[plan:alpha-plan]]", null, null, caller = null))
                    .value()
                    .note.id
            val derivingNote =
                rig.ctx.noteCommandService
                    .upsert(NoteUpsertCommand(owner.id, "derived", "work", "no brackets here", null, null, caller = null))
                    .value()
                    .note.id
            rig.ctx.linkService.recordDerivedFrom(root.id, derivingNote, "alpha-plan", "overview", Instant.now())
            rig.ctx.linkService
                .attach(root.id, DocumentKind.PLAN, "alpha-plan", attachTarget.id)
                .value()

            val rows = linksOf(rig, root.id, "alpha-plan")

            assertEquals(5, rows.size, "control: two outgoing references, one inbound reference, one derived_from, one attachment: $rows")

            val resolvedRef =
                rows.single {
                    it.text("relation") == "references" &&
                        it.refId("from") == plan.id.toString() &&
                        it.containsKey("to")
                }
            assertEquals(page.id.toString(), resolvedRef.refId("to"), "a resolved reference names its document")
            assertEquals("page:pg", resolvedRef.text("toRef"), "and keeps the reference text")

            val dangling =
                rows.single {
                    it.text("relation") == "references" &&
                        it.refId("from") == plan.id.toString() &&
                        !it.containsKey("to")
                }
            assertFalse(dangling.containsKey("to"), "a reference to a document that does not exist has no to: $dangling")
            assertEquals("page:missing", dangling.text("toRef"), "its toRef is the reference text")

            val inbound = rows.single { it.text("relation") == "references" && it.refId("from") == referencingNote.toString() }
            assertEquals(plan.id.toString(), inbound.refId("to"))
            assertEquals("plan:alpha-plan", inbound.text("toRef"), "an inbound resolved note reference keeps its text")

            val derived = rows.single { it.text("relation") == "derived_from" }
            assertEquals(derivingNote.toString(), derived.refId("from"))
            assertEquals(plan.id.toString(), derived.refId("to"))
            assertEquals("plan:alpha-plan", derived.text("toRef"), "derived_from stores plan:<slug>")

            val attached = rows.single { it.text("relation") == "attached" }
            assertEquals(plan.id.toString(), attached.refId("from"))
            assertEquals(attachTarget.id.toString(), attached.refId("to"))
            assertFalse(attached.containsKey("toRef"), "an attachment carries no reference text: $attached")
        }

    @Test
    fun `S4 a link dangling after its page is deleted has no to and keeps its text, and resolves again when the page is re-created`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val root = rig.seed("root")
            val plan =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PLAN, "alpha-plan", "see [[page:pg]]")
                    .value()
            val page =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PAGE, "pg", "page body")
                    .value()
            val resolved = linksOf(rig, root.id, "alpha-plan", ""","direction":"outgoing"""").single()
            assertEquals(page.id.toString(), resolved.refId("to"), "control: the reference resolves while the page exists")

            rig.ctx.documentService
                .delete(root.id, DocumentKind.PAGE, "pg")
                .value()
            val dangling = linksOf(rig, root.id, "alpha-plan", ""","direction":"outgoing"""").single()
            assertFalse(dangling.containsKey("to"), "after the page is deleted the link has no to: $dangling")
            assertEquals("page:pg", dangling.text("toRef"), "and keeps the reference text")
            assertEquals(plan.id.toString(), dangling.refId("from"))

            val recreated =
                rig.ctx.documentService
                    .write(root.id, DocumentKind.PAGE, "pg", "page body again")
                    .value()
            val again = linksOf(rig, root.id, "alpha-plan", ""","direction":"outgoing"""").single()
            assertEquals(recreated.id.toString(), again.refId("to"), "the link resolves again to the re-created page: $again")
            assertEquals("page:pg", again.text("toRef"))
        }
}
