package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `dispatch` field on [AdvanceItemTool]'s per-transition success result (B1,
 * dispatch trait dimension, S11 + probe + S10 portion). Mirrors [AdvanceItemToolTest]'s setUp and
 * `contextWithSchema` wiring conventions (duplicated locally — that file's helpers are private).
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and AC2/P7/P9 in the item's `task-scope` note — never from reading AdvanceItemTool's
 * source. The WORK -> REVIEW transition trigger ("start", when the schema has a review-phase note)
 * is read from the existing, readable `AdvanceServiceStartCascadeGateTest.kt` ("P5 a start trigger
 * moving WORK to REVIEW...") under src/test — not from src/main.
 */
class AdvanceItemToolDispatchTest {
    private lateinit var tool: AdvanceItemTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = AdvanceItemTool()
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        val defaultNoteRepo = mockk<NoteRepository>()
        coEvery { defaultNoteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        coEvery { defaultNoteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())
        every { repoProvider.noteRepository() } returns defaultNoteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.resourceLeaseRepository() } returns mockk(relaxed = true)
        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }

        context = ToolExecutionContext(repoProvider)
    }

    /** Build a custom context wiring a schema-aware NoteSchemaService, reusing the mocked repos. */
    private fun contextWithSchema(
        noteRepo: NoteRepository,
        noteSchemaService: NoteSchemaService
    ): ToolExecutionContext {
        val provider = mockk<RepositoryProvider>()
        every { provider.workItemRepository() } returns workItemRepo
        every { provider.dependencyRepository() } returns depRepo
        every { provider.noteRepository() } returns noteRepo
        every { provider.roleTransitionRepository() } returns roleTransitionRepo
        every { provider.resourceLeaseRepository() } returns mockk(relaxed = true)
        return ToolExecutionContext(provider, noteSchemaService)
    }

    /** A type-based NoteSchemaService exposing one schema plus a dispatch map per trait name. */
    private fun schemaServiceWithDispatch(
        schema: WorkItemSchema,
        dispatchByTrait: Map<String, Map<Role, DispatchProfile>>
    ): NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null

            override fun getSchemaForType(type: String?): WorkItemSchema? = if (type == schema.type) schema else null

            override fun getTraitDispatch(traitName: String): Map<Role, DispatchProfile> = dispatchByTrait[traitName] ?: emptyMap()
        }

    private fun buildParams(vararg transitions: JsonObject): JsonObject =
        buildJsonObject {
            put(
                "transitions",
                buildJsonArray {
                    transitions.forEach { add(it) }
                }
            )
        }

    private fun transitionObj(
        itemId: UUID,
        trigger: String
    ): JsonObject =
        buildJsonObject {
            put("itemId", JsonPrimitive(itemId.toString()))
            put("trigger", JsonPrimitive(trigger))
        }

    private fun extractResults(result: JsonElement): JsonArray {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response: $obj")
        return (obj["data"] as JsonObject)["results"]!!.jsonArray
    }

    // ──────────────────────────────────────────────
    // S11 — advance queue→work and work→review surface the winning dispatch profile exactly
    // ──────────────────────────────────────────────

    @Test
    fun `S11 advance queue to work surfaces dispatch agent exactly, no other fields`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, title = "Delegated item", role = Role.QUEUE, type = "feature-task")

            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            val noteSchemaService =
                schemaServiceWithDispatch(
                    schema,
                    mapOf("delegated" to mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer")))
                )

            val noteRepo = mockk<NoteRepository>()
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())

            val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), gatedContext)

            val r = extractResults(result)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "expected transition to apply: $r")
            assertEquals("work", r["newRole"]!!.jsonPrimitive.content)

            val dispatch = r["dispatch"]!!.jsonObject
            assertEquals(setOf("agent"), dispatch.keys, "only agent must be present -- no model/effort keys")
            assertEquals("task-orchestrator:implementer", dispatch["agent"]!!.jsonPrimitive.content)
        }

    @Test
    fun `S11 advance work to review surfaces dispatch agent and effort exactly`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, title = "Delegated item", role = Role.WORK, type = "feature-task")

            val schema =
                WorkItemSchema(
                    type = "feature-task",
                    notes =
                        listOf(
                            NoteSchemaEntry(key = "implementation-notes", role = Role.WORK, required = true, description = "Impl"),
                            NoteSchemaEntry(key = "review-checklist", role = Role.REVIEW, required = false, description = "Review")
                        ),
                    defaultTraits = listOf("delegated")
                )
            val noteSchemaService =
                schemaServiceWithDispatch(
                    schema,
                    mapOf(
                        "delegated" to
                            mapOf(Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high"))
                    )
                )

            val noteRepo = mockk<NoteRepository>()
            val workNote = Note(itemId = itemId, key = "implementation-notes", role = "work", body = "Done")
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(workNote))
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(workNote))

            val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), gatedContext)

            val r = extractResults(result)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "expected transition to apply: $r")
            assertEquals("review", r["newRole"]!!.jsonPrimitive.content)

            val dispatch = r["dispatch"]!!.jsonObject
            assertEquals(setOf("agent", "effort"), dispatch.keys, "agent + effort only -- no model key")
            assertEquals("task-orchestrator:reviewer", dispatch["agent"]!!.jsonPrimitive.content)
            assertEquals("high", dispatch["effort"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Probe — advancing into TERMINAL never surfaces a dispatch key (queue/work/review only)
    // ──────────────────────────────────────────────

    @Test
    fun `probe -- advance work to terminal omits the dispatch key entirely`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, title = "Delegated item", role = Role.WORK, type = "feature-task")

            // No REVIEW-role note -- hasReviewPhase=false, so "complete" lands on TERMINAL.
            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            val noteSchemaService =
                schemaServiceWithDispatch(
                    schema,
                    mapOf(
                        "delegated" to
                            mapOf(
                                Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                                Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer")
                            )
                    )
                )

            val noteRepo = mockk<NoteRepository>()
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())

            val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "complete")), gatedContext)

            val r = extractResults(result)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "expected transition to apply: $r")
            assertEquals("terminal", r["newRole"]!!.jsonPrimitive.content)
            assertFalse(r.containsKey("dispatch"), "TERMINAL is not a dispatch phase -- key must be absent")
        }

    // ──────────────────────────────────────────────
    // S10 — no dispatch-bearing trait at all -> no dispatch key anywhere
    // ──────────────────────────────────────────────

    @Test
    fun `S10 advance with no dispatch-bearing trait omits the dispatch key`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = WorkItem(id = itemId, title = "Plain item", role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)

            val r = extractResults(result)[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean, "expected transition to apply: $r")
            assertFalse(r.containsKey("dispatch"), "no trait at all -- dispatch key must be absent")
        }
}
