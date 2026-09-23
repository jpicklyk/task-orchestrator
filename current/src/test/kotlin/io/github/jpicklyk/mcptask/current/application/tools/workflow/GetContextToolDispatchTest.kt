package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import io.github.jpicklyk.mcptask.current.domain.repository.ClaimStatusCounts
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
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
 * Unit tests for the top-level `dispatch` field on [GetContextTool]'s item-mode response (B1,
 * dispatch trait dimension, S12 portion + probe + S10 portion). Mirrors [GetContextToolTest]'s
 * setUp wiring conventions (duplicated locally — that file's helpers are private).
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and P7/P9 in the item's `task-scope` note — never from reading GetContextTool's source.
 */
class GetContextToolDispatchTest {
    private lateinit var tool: GetContextTool
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var noteSchemaService: NoteSchemaService
    private lateinit var context: ToolExecutionContext
    private lateinit var schemaContext: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        tool = GetContextTool()
        workItemRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()
        noteSchemaService = mockk()
        every { noteSchemaService.getSchemaForType(any()) } returns null
        every { noteSchemaService.getDefaultTraits(any()) } returns emptyList()
        every { noteSchemaService.getTraitNotes(any()) } returns null
        every { noteSchemaService.getTraitResources(any()) } returns emptyList()
        every { noteSchemaService.getTraitDispatch(any()) } returns emptyMap()

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        every { repoProvider.dependencyRepository() } returns mockk()

        context = ToolExecutionContext(repoProvider, NoOpNoteSchemaService)
        schemaContext = ToolExecutionContext(repoProvider, noteSchemaService)

        coEvery { workItemRepo.countByClaimStatus(any()) } returns
            Result.Success(ClaimStatusCounts(active = 0, expired = 0, unclaimed = 0))
        coEvery { workItemRepo.dbNow() } returns Instant.now()
    }

    private fun callParams(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Item",
        role: Role = Role.WORK,
        tags: String? = null,
        type: String? = null,
        properties: String? = null,
        depth: Int = 0,
        parentId: UUID? = null
    ): WorkItem =
        WorkItem(
            id = id,
            parentId = parentId,
            title = title,
            role = role,
            tags = tags,
            type = type,
            properties = properties,
            depth = depth
        )

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true but got: $obj")
        return obj["data"] as JsonObject
    }

    // ──────────────────────────────────────────────
    // S12 — item mode surfaces the dispatch profile for the item's current role
    // ──────────────────────────────────────────────

    @Test
    fun `S12 item mode surfaces the dispatch profile for the item's current WORK role`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK, type = "feature-task")

            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            every { noteSchemaService.getSchemaForType("feature-task") } returns schema
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"))

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val result = tool.execute(callParams("itemId" to JsonPrimitive(itemId.toString())), schemaContext)
            val data = extractData(result)

            val dispatch = data["dispatch"]!!.jsonObject
            assertEquals(setOf("agent"), dispatch.keys)
            assertEquals("task-orchestrator:implementer", dispatch["agent"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // Probe — repeated get_context calls resolve the same dispatch profile
    // ──────────────────────────────────────────────

    @Test
    fun `probe -- get_context called twice for the same item returns the same dispatch profile both times`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.WORK, type = "feature-task")

            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            every { noteSchemaService.getSchemaForType("feature-task") } returns schema
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer", effort = "medium"))

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val requestParams = callParams("itemId" to JsonPrimitive(itemId.toString()))
            val dispatch1 = extractData(tool.execute(requestParams, schemaContext))["dispatch"]!!.jsonObject
            val dispatch2 = extractData(tool.execute(requestParams, schemaContext))["dispatch"]!!.jsonObject

            assertEquals(dispatch1, dispatch2, "repeated get_context calls must resolve the identical dispatch profile")
        }

    // ──────────────────────────────────────────────
    // S10 — no dispatch key when nothing resolves for the current role
    // ──────────────────────────────────────────────

    @Test
    fun `S10 item mode omits the dispatch key when the item has no dispatch-bearing trait`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val result = tool.execute(callParams("itemId" to JsonPrimitive(itemId.toString())), context)
            val data = extractData(result)

            assertFalse(data.containsKey("dispatch"), "no trait at all -- dispatch key must be absent")
        }

    @Test
    fun `S10 item mode omits the dispatch key for a role the item's trait does not declare`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE, type = "feature-task")

            val schema = WorkItemSchema(type = "feature-task", notes = emptyList(), defaultTraits = listOf("delegated"))
            every { noteSchemaService.getSchemaForType("feature-task") } returns schema
            every { noteSchemaService.getTraitDispatch("delegated") } returns
                mapOf(
                    Role.WORK to DispatchProfile(agent = "task-orchestrator:implementer"),
                    Role.REVIEW to DispatchProfile(agent = "task-orchestrator:reviewer")
                )

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())

            val result = tool.execute(callParams("itemId" to JsonPrimitive(itemId.toString())), schemaContext)
            val data = extractData(result)

            assertFalse(
                data.containsKey("dispatch"),
                "the pinned contract declares queue/work/review only -- QUEUE has no entry on this trait"
            )
        }
}
