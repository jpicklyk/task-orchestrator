package io.github.jpicklyk.mcptask.current.interfaces.shared.tools.docs

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.service.NoteUpsertCommand
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
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
import kotlin.test.assertTrue

/**
 * Fix round 1 regressions (item 8e99f1e2). B1: the `links` select drops a link whose only non-document end is an
 * orphaned (deleted) note or item, under any scope, before paging. B2: under a restricted scope a prefix `rootId` /
 * `itemId` is resolved by the `items_*` rule: no match and an all-foreign ambiguity are `forbidden`, and an ambiguity
 * never lists an id outside the scope. Id-taking fields swept: `rootId` (every select and op) and `itemId` (attach,
 * detach); documents are addressed by (rootId, kind, slug), so there is no document-id field.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class DocsFixRound1Test {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private val registry = ToolRegistry(buildToolSpecs())
    private val scenarios = DocsParityScenarios

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun McpResult.all(): String = text + structured.toString()

    private fun McpResult.data(): JsonObject = structured.getValue("data").jsonObject

    private fun McpResult.errorCode(): String =
        structured
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun McpResult.elementCode(): String =
        data()
            .getValue("results")
            .jsonArray[0]
            .jsonObject
            .getValue("error")
            .jsonObject
            .getValue("code")
            .jsonPrimitive.content

    private fun McpResult.total(): Int =
        data()
            .getValue("page")
            .jsonObject
            .getValue("total")
            .jsonPrimitive.int

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

    private fun readerOf(vararg roots: UUID) = ParityToken("fix1-reader-${roots.size}", setOf(ApiCapability.READ), rootIds = roots.toSet())

    private fun writerOf(vararg roots: UUID) =
        ParityToken("fix1-writer-${roots.size}", setOf(ApiCapability.READ, ApiCapability.WRITE_DOCS), rootIds = roots.toSet())

    private suspend fun root(
        rig: EventLogRig,
        id: String,
        title: String
    ): UUID {
        val uuid = UUID.fromString(id)
        rig.raw.workItemRepository().create(WorkItem(id = uuid, title = title, depth = 0))
        return uuid
    }

    private suspend fun child(
        rig: EventLogRig,
        id: String,
        parent: UUID
    ): UUID {
        val uuid = UUID.fromString(id)
        rig.raw.workItemRepository().create(WorkItem(id = uuid, title = "child $id", parentId = parent, depth = 1, rootId = parent))
        return uuid
    }

    private suspend fun upsertPlan(
        rig: EventLogRig,
        root: UUID,
        slug: String
    ) {
        val r = writeAs(rig, null, scenarios.ops(scenarios.upsert(root, "plan", slug, "body")))
        assertFalse(r.isError, r.text)
    }

    // ------------------------------------------------------------------ B1 orphaned ends

    private suspend fun orphanWorld(rig: EventLogRig): UUID {
        val r = root(rig, "11111111-0000-4000-8000-000000000001", "root")
        val c = child(rig, "11111111-0000-4000-8000-0000000000c1", r)
        upsertPlan(rig, r, "p")
        val note = rig.ctx.noteCommandService.upsert(NoteUpsertCommand(c, "k1", "work", "see [[plan:p]]", null, null))
        assertTrue(note is Outcome.Ok)
        rig.raw.workItemRepository().delete(c)
        return r
    }

    @Test
    fun `B1 a link whose only non-document end is an orphaned note is dropped for an unrestricted reader`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val r = orphanWorld(rig)
            val result = readAs(rig, null, scenarios.links(r, "plan", "p"))
            assertFalse(result.isError, result.text)
            assertEquals(0, result.total(), result.text)
        }

    @Test
    fun `B1 a link whose only non-document end is an orphaned note is dropped for a root-scoped reader`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val r = orphanWorld(rig)
            val result = readAs(rig, readerOf(r), scenarios.links(r, "plan", "p"))
            assertFalse(result.isError, result.text)
            assertEquals(0, result.total(), result.text)
        }

    @Test
    fun `B1 an attachment whose item was deleted is dropped and a live link beside it still pages`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val r = root(rig, "11111111-0000-4000-8000-000000000002", "root")
            val gone = child(rig, "11111111-0000-4000-8000-0000000000d1", r)
            val live = child(rig, "11111111-0000-4000-8000-0000000000d2", r)
            upsertPlan(rig, r, "p")
            assertFalse(writeAs(rig, null, scenarios.ops(scenarios.attach("attach", r, "plan", "p", gone))).isError)
            assertFalse(writeAs(rig, null, scenarios.ops(scenarios.attach("attach", r, "plan", "p", live))).isError)
            rig.raw.workItemRepository().delete(gone)
            val result = readAs(rig, readerOf(r), scenarios.links(r, "plan", "p", ""","limit":1"""))
            assertFalse(result.isError, result.text)
            assertEquals(1, result.total(), result.text)
            assertFalse(gone.toString() in result.all())
            assertTrue(live.toString() in result.all())
        }

    // ------------------------------------------------------------------ B2 prefix ids under a restricted scope

    @Test
    fun `B2 a root prefix that matches nothing is forbidden under a restricted scope and not_found when unrestricted`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val mine = root(rig, "22222222-0000-4000-8000-000000000001", "mine")
            val denied = readAs(rig, readerOf(mine), scenarios.list("cdcdcdcd"))
            assertTrue(denied.isError, denied.text)
            assertEquals("forbidden", denied.errorCode())
            assertEquals("not_found", readAs(rig, null, scenarios.list("cdcdcdcd")).errorCode())
        }

    @Test
    fun `B2 a root prefix shared only by foreign roots is forbidden and names none of them, on every select and op`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val mine = root(rig, "22222222-0000-4000-8000-000000000002", "mine")
            val f1 = root(rig, "ab000000-0000-4000-8000-0000000000a1", "foreign one")
            val f2 = root(rig, "ab000000-0000-4000-8000-0000000000a2", "foreign two")
            val token = readerOf(mine)
            val bodies =
                listOf(
                    scenarios.list("ab000000"),
                    scenarios.get("ab000000", "plan", "p"),
                    scenarios.history("ab000000", "plan", "p"),
                    scenarios.links("ab000000", "plan", "p")
                )
            for (body in bodies) {
                val result = readAs(rig, token, body)
                assertTrue(result.isError, result.text)
                assertEquals("forbidden", result.errorCode(), body)
                assertFalse(f1.toString() in result.all() || f2.toString() in result.all(), "foreign id leaked: ${result.text}")
            }
            val write = writeAs(rig, writerOf(mine), scenarios.ops(scenarios.upsert("ab000000", "plan", "p", "x")))
            assertEquals("forbidden", write.elementCode(), write.text)
            assertFalse(f1.toString() in write.all() || f2.toString() in write.all(), "foreign id leaked: ${write.text}")
        }

    @Test
    fun `B2 an ambiguous root prefix lists only the candidates in scope and one in scope resolves`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val a1 = root(rig, "ab000000-0000-4000-8000-0000000000a1", "in one")
            val a2 = root(rig, "ab000000-0000-4000-8000-0000000000a2", "in two")
            val foreign = root(rig, "ab000000-0000-4000-8000-0000000000a3", "foreign")
            val two = readAs(rig, readerOf(a1, a2), scenarios.list("ab000000"))
            assertEquals("ambiguous_id", two.errorCode(), two.text)
            assertTrue(a1.toString() in two.all() && a2.toString() in two.all(), two.text)
            assertFalse(foreign.toString() in two.all(), "foreign id leaked: ${two.text}")

            val one = readAs(rig, readerOf(a1), scenarios.list("ab000000"))
            assertFalse(one.isError, one.text)
            assertFalse(a2.toString() in one.all() || foreign.toString() in one.all(), one.text)
        }

    @Test
    fun `B2 an item prefix for attach and detach is forbidden when no candidate is in scope and leaks none`(
        @TempDir dir: Path
    ): Unit =
        runBlocking {
            val rig = EventLogRig.build(db.db, dir)
            val mine = root(rig, "22222222-0000-4000-8000-000000000003", "mine")
            val other = root(rig, "22222222-0000-4000-8000-000000000004", "other")
            val i1 = child(rig, "ef000000-0000-4000-8000-0000000000e1", other)
            val i2 = child(rig, "ef000000-0000-4000-8000-0000000000e2", other)
            upsertPlan(rig, mine, "p")
            val token = writerOf(mine)
            for (name in listOf("attach", "detach")) {
                for (prefix in listOf("ef000000", "fafafafa")) {
                    val result = writeAs(rig, token, scenarios.ops(scenarios.attach(name, mine, "plan", "p", prefix)))
                    assertEquals("forbidden", result.elementCode(), "$name $prefix: ${result.text}")
                    assertFalse(i1.toString() in result.all() || i2.toString() in result.all(), "foreign id leaked: ${result.text}")
                }
            }
        }
}
