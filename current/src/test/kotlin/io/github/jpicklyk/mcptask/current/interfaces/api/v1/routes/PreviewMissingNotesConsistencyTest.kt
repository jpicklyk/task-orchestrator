package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.port.NoteStore
import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.workflow.GetContextTool
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.events.EventLogRig
import io.github.jpicklyk.mcptask.current.test.P11Driver
import io.github.jpicklyk.mcptask.current.test.P11MatrixState
import io.github.jpicklyk.mcptask.current.test.P11_BASE_YAML
import io.github.jpicklyk.mcptask.current.test.arr
import io.github.jpicklyk.mcptask.current.test.cleanup
import io.github.jpicklyk.mcptask.current.test.flag
import io.github.jpicklyk.mcptask.current.test.setup
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.text
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent P11 r1 tests (item 919d379e) for fix-decision H3: `get_context` and `GET /items/{id}/gate` report `missing` /
 * `missingBySeat` as the required notes the preview's decision itself found missing, so they always agree with
 * `canAdvance` / `blockedBy`. Oracle: fix-decisions-r1 H3 ("blockedBy note always comes with a non-empty missing list"; no
 * second note read outside the preview's unit).
 *
 * Rules asserted on every gate answer (derived from H3, not from the implementation):
 *  - blockedBy == "note" implies `missing` is exactly the required notes of the item's current phase that are not filled;
 *  - canAdvance == true implies `missing` is empty; a non-empty `missing` implies canAdvance == false.
 * The expected missing key of each generated state comes from the documented fixture (the phase's required note, filled or not).
 *
 * The race tests make a note read return a different answer the second time (as if a note were upserted or deleted between two
 * reads of the same request): with one read per answer the response stays self-consistent, with a second read outside the
 * preview's decision it cannot (the first read's missing disagrees with the second read's decision).
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class PreviewMissingNotesConsistencyTest {
    @RegisterExtension
    @JvmField
    val db = SqliteTestDatabase.perMethod()

    private fun phaseKey(role: Role): String? = mapOf(Role.QUEUE to "spec", Role.WORK to "impl", Role.REVIEW to "verdict")[role]

    private fun assertDecisionConsistent(
        label: String,
        gate: JsonObject,
        expectedMissingWhenNoteBlocked: List<String>,
        onlyNoteGateCanFail: Boolean,
    ) {
        val canAdvance = gate.flag("canAdvance")
        val blockedBy = gate.text("blockedBy")
        val missing = gate.arr("missing").map { it.jsonPrimitive.content }
        if (blockedBy ==
            "note"
        ) {
            assertEquals(expectedMissingWhenNoteBlocked, missing, "$label: blockedBy=note names the missing notes: $gate")
        }
        if (canAdvance == true) assertTrue(missing.isEmpty(), "$label: an allowed advance has no missing notes: $gate")
        if (missing.isNotEmpty()) assertFalse(canAdvance ?: false, "$label: missing notes block the advance: $gate")
        if (onlyNoteGateCanFail && missing.isNotEmpty()) assertEquals("note", blockedBy, "$label: $gate")
        if (onlyNoteGateCanFail && missing.isEmpty()) assertEquals(true, canAdvance, "$label: nothing else can block: $gate")
    }

    // ---------------------------------------------------------------------------------------------
    // Generated states: every role x dependency x notes x lease combination, MCP and REST
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `H3 get_context missing agrees with canAdvance and blockedBy across every generated state`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            for (state in P11MatrixState.all()) {
                val (item, holder) = d.setup(state)
                val data = d.rig.call(GetContextTool(), "itemId" to JsonPrimitive(item.id.toString()))["data"]!!.jsonObject
                val gate = data["gateStatus"]!!.jsonObject
                assertDecisionConsistent(state.label, gate, listOfNotNull(phaseKey(state.role)), onlyNoteGateCanFail = false)
                d.cleanup(item, holder)
            }
        }

    @Test
    fun `H3 REST gate missing agrees with canAdvance and blockedBy across every generated state`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, P11_BASE_YAML))
            val ctx = d.rig.ctx
            application {
                configureTestApp(makeWriteAuthConfig()) { itemGateRoutes(d.rig.provider, ctx.configResolver, ctx.transitionPreview()) }
            }
            for (state in P11MatrixState.all()) {
                val (item, holder) = d.setup(state)
                val gate = gateOf(client, item.id.toString(), state.label)
                assertDecisionConsistent(state.label, gate, listOfNotNull(phaseKey(state.role)), onlyNoteGateCanFail = false)
                d.cleanup(item, holder)
            }
        }

    private suspend fun gateOf(
        client: HttpClient,
        itemId: String,
        label: String,
    ): JsonObject {
        val response = client.get("/api/v1/items/$itemId/gate") { header("Authorization", "Bearer $WRITE_TOKEN") }
        assertEquals(HttpStatusCode.OK, response.status, "$label: ${response.bodyAsText()}")
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["gateStatus"]!!.jsonObject
    }

    // ---------------------------------------------------------------------------------------------
    // missingBySeat: a seat-aware schema with one of two required queue notes filled
    // ---------------------------------------------------------------------------------------------

    private val seatConfig =
        "work_item_schemas:\n" +
            "  h3-seat:\n" +
            "    seats:\n" +
            "      - { name: planner, phase: queue, enters: true }\n" +
            "    notes:\n" +
            "      - key: diagnosis\n        role: queue\n        required: true\n        seat: planner\n" +
            "      - key: task-scope\n        role: queue\n        required: true\n        seat: planner\n"

    @Test
    fun `H3 get_context missing and missingBySeat list exactly the unfilled required notes of the decision`(
        @TempDir dir: Path,
    ): Unit =
        runBlocking {
            val d = P11Driver(EventLogRig.build(db.db, dir, seatConfig))
            val item = d.item("seat item", Role.QUEUE, type = "h3-seat")
            d.note(item, "diagnosis", "queue")

            val gate =
                d.rig
                    .call(
                        GetContextTool(),
                        "itemId" to JsonPrimitive(item.id.toString())
                    )["data"]!!
                    .jsonObject["gateStatus"]!!
                    .jsonObject

            assertEquals(false, gate.flag("canAdvance"), "$gate")
            assertEquals("note", gate.text("blockedBy"), "$gate")
            assertEquals(listOf("task-scope"), gate.arr("missing").map { it.jsonPrimitive.content }, "$gate")
            val bySeat = gate["missingBySeat"]!!.jsonObject
            assertEquals(setOf("planner"), bySeat.keys, "$gate")
            assertEquals(listOf("task-scope"), bySeat.arr("planner").map { it.jsonPrimitive.content }, "$gate")
        }

    @Test
    fun `H3 REST gate missing and missingBySeat list exactly the unfilled required notes of the decision`(
        @TempDir dir: Path,
    ): Unit =
        testApplication {
            val d = P11Driver(EventLogRig.build(db.db, dir, seatConfig))
            val ctx = d.rig.ctx
            application {
                configureTestApp(makeWriteAuthConfig()) { itemGateRoutes(d.rig.provider, ctx.configResolver, ctx.transitionPreview()) }
            }
            val item = d.item("seat item", Role.QUEUE, type = "h3-seat")
            d.note(item, "diagnosis", "queue")

            val gate = gateOf(client, item.id.toString(), "seat")

            assertEquals(false, gate.flag("canAdvance"), "$gate")
            assertEquals("note", gate.text("blockedBy"), "$gate")
            assertEquals(listOf("task-scope"), gate.arr("missing").map { it.jsonPrimitive.content }, "$gate")
            val bySeat = gate["missingBySeat"]!!.jsonObject
            assertEquals(setOf("planner"), bySeat.keys, "$gate")
            assertEquals(listOf("task-scope"), bySeat.arr("planner").map { it.jsonPrimitive.content }, "$gate")
        }

    // ---------------------------------------------------------------------------------------------
    // Race: the note store answers differently on the second read of a request
    // ---------------------------------------------------------------------------------------------

    private class NotesProvider(
        delegate: RepositoryProvider,
        private val notes: NoteStore,
    ) : RepositoryProvider by delegate {
        override fun noteRepository(): NoteStore = notes
    }

    private class RaceFixture(
        val provider: RepositoryProvider,
        val ctx: ToolExecutionContext,
        val item: WorkItem,
        val noteReads: AtomicInteger,
    )

    /**
     * An item whose only start gate is the required queue note `spec`, which IS filled in the database. The note store
     * answers its first read with no notes and every later read truthfully ([hideFirstRead] = true) or the reverse.
     */
    private suspend fun raceFixture(hideFirstRead: Boolean): RaceFixture {
        val sqlite = db.repositoryProvider()
        val root = sqlite.workItemRepository().create(WorkItem(title = "h3 root"))
        val item =
            sqlite.workItemRepository().create(
                WorkItem(title = "h3 item", type = "h3-type", role = Role.QUEUE, parentId = root.id, rootId = root.id, depth = 1),
            )
        sqlite.noteRepository().upsert(Note(itemId = item.id, key = "spec", role = "queue", body = "filled"))
        sqlite.projectConfigRepository().upsert(
            root.id,
            "work_item_schemas:\n  h3-type:\n    notes:\n      - key: spec\n        role: queue\n        required: true\n",
        )

        val reads = AtomicInteger()
        val spy = spyk(sqlite.noteRepository())
        coEvery { spy.findByItemId(any()) } coAnswers {
            val first = reads.getAndIncrement() == 0
            if (first == hideFirstRead) emptyList<Note>() else callOriginal()
        }
        coEvery { spy.findByItemId(any(), any()) } coAnswers {
            val first = reads.getAndIncrement() == 0
            if (first == hideFirstRead) emptyList<Note>() else callOriginal()
        }
        val provider = NotesProvider(sqlite, spy)
        val ctx =
            ToolExecutionContext(
                provider,
                perRootConfigService = PerRootConfigService(sqlite.projectConfigRepository()),
                unitOfWork = SqliteUnitOfWork(db.databaseManager, provider) { Instant.now() },
            )
        return RaceFixture(provider, ctx, item, reads)
    }

    private fun raceGate(
        hideFirstRead: Boolean,
        label: String,
    ) = runBlocking {
        val f = raceFixture(hideFirstRead)
        val data =
            (
                GetContextTool().execute(JsonObject(mapOf("itemId" to JsonPrimitive(f.item.id.toString()))), f.ctx) as JsonObject
            )["data"]!!.jsonObject
        assertTrue(f.noteReads.get() >= 1, "$label: the fixture's note-read interception must have been exercised")
        assertDecisionConsistent(label, data["gateStatus"]!!.jsonObject, listOf("spec"), onlyNoteGateCanFail = true)
    }

    @Test
    fun `H3 get_context stays self-consistent when the first note read hides a note that later reads show`() =
        raceGate(true, "mcp/hide-first")

    @Test
    fun `H3 get_context stays self-consistent when the first note read shows a note that later reads hide`() =
        raceGate(false, "mcp/show-first")

    private fun raceGateRest(
        hideFirstRead: Boolean,
        label: String,
    ) = testApplication {
        val f = raceFixture(hideFirstRead)
        application {
            configureTestApp(makeWriteAuthConfig()) {
                itemGateRoutes(f.provider, f.ctx.configResolver, f.ctx.transitionPreview())
            }
        }
        val gate = gateOf(client, f.item.id.toString(), label)
        assertTrue(f.noteReads.get() >= 1, "$label: the fixture's note-read interception must have been exercised")
        assertDecisionConsistent(label, gate, listOf("spec"), onlyNoteGateCanFail = true)
    }

    @Test
    fun `H3 REST gate stays self-consistent when the first note read hides a note that later reads show`() =
        raceGateRest(true, "rest/hide-first")

    @Test
    fun `H3 REST gate stays self-consistent when the first note read shows a note that later reads hide`() =
        raceGateRest(false, "rest/show-first")
}
