package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
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
import kotlinx.serialization.json.put
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Blind test-author coverage for c39fe915 (advance_item errorCode/errorKind on every
 * applied:false result). Test author actor id: test-author:c39fe915.
 *
 * Oracles: O-D = diagnosis note "Chosen shapes" mapping table (frozen at queue phase before
 * implementation existed); O-EK = current/docs/api-reference.md#ErrorKind-Values (transient /
 * permanent / shedding) and its "Every failure carries errorCode + errorKind" paragraph, both
 * project documentation this test author is permitted to read directly.
 *
 * Scenario ids (S9-S13) match the item test-plan note, section "advance_item failures". These
 * scenarios are EXISTING-SURFACE for the transition/gate/dependency/not-found mechanics
 * themselves (already exercised by AdvanceItemToolTest) but NEW-SURFACE for the errorCode and
 * errorKind fields this fix adds to every result entry -- narrowest-revert recipe per test-plan:
 * keep the Companion string constants, revert only the call sites that populate errorCode/
 * errorKind back through the old codeless buildErrorResult.
 *
 * Two additional, non-S-id checks (invalid_trigger, invalid_actor) are included for completeness
 * against the full Companion constant set the dispatch declarations enumerate; their oracle is
 * the same diagnosis "Chosen shapes" table, not an S-id in test-plan.
 */
class AdvanceItemToolErrorCodeTest {
    private lateinit var tool: AdvanceItemTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    @BeforeEach
    fun setUp() {
        tool = AdvanceItemTool()
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()

        repoProvider = mockk<RepositoryProvider>()
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

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        title: String = "Test Item",
        tags: String? = null
    ): WorkItem = WorkItem(id = id, title = title, role = role, tags = tags)

    private fun buildParams(vararg transitions: JsonObject): JsonObject =
        buildJsonObject {
            put("transitions", buildJsonArray { transitions.forEach { add(it) } })
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
        val data = obj["data"] as JsonObject
        return data["results"]!!.jsonArray
    }

    private fun resultFor(
        result: JsonElement,
        itemId: UUID
    ): JsonObject = extractResults(result).map { it.jsonObject }.first { it["itemId"]?.jsonPrimitive?.content == itemId.toString() }

    /** The full set of valid ErrorKind wire values (O-EK). */
    private val validErrorKinds = setOf("transient", "permanent", "shedding")

    private fun schemaServiceWith(entries: List<NoteSchemaEntry>): NoteSchemaService =
        object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = if (tags.isNotEmpty()) entries else null
        }

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

    private fun gateSchemaEntries(): List<NoteSchemaEntry> =
        listOf(
            NoteSchemaEntry(
                key = "acceptance-criteria",
                role = Role.QUEUE,
                required = true,
                description = "Acceptance criteria for this task",
                guidance = "List each criterion as a bullet point"
            )
        )

    // -----------------------------------------------------------------------
    // S9: start, required queue note missing -> gate_blocked / permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S9 - start with a missing required queue note fails with gate_blocked permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE, tags = "feature-task")

            val noteSchemaService = schemaServiceWith(gateSchemaEntries())
            val noteRepo = mockk<NoteRepository>()
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())
            val gatedContext = contextWithSchema(noteRepo, noteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), gatedContext)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.GATE_BLOCKED, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
            assertTrue(r["missingNotes"] is JsonArray, "gate_blocked must keep the missingNotes field")
            assertEquals("queue", r["previousRole"]!!.jsonPrimitive.content)
        }

    // -----------------------------------------------------------------------
    // S10: start, unmet BLOCKS dependency -> dependency_blocked / permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S10 - start blocked by an unsatisfied dependency fails with dependency_blocked permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)
            val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker Task")

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

            val dep = Dependency(fromItemId = blockerId, toItemId = itemId, type = DependencyType.BLOCKS)
            every { depRepo.findByToItemId(itemId) } returns listOf(dep)
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.DEPENDENCY_BLOCKED, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
            val blockers = r["blockers"]!!.jsonArray
            assertEquals(1, blockers.size)
        }

    // -----------------------------------------------------------------------
    // S11: start, unresolvable full UUID -> item_not_found / permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S11 - start on an unresolvable full UUID fails with item_not_found permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()

            coEvery { workItemRepo.getById(itemId) } returns
                Result.Error(RepositoryError.NotFound(itemId, "WorkItem not found: $itemId"))

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.ITEM_NOT_FOUND, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // S12: start on a TERMINAL item -> invalid_transition / permanent
    // -----------------------------------------------------------------------

    @Test
    fun `S12 - start on a TERMINAL item fails with invalid_transition permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.TERMINAL)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.INVALID_TRANSITION, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------------
    // S13: S9-S12 in one batch call -- every result carries errorCode + errorKind, errorKind
    // drawn from the valid ErrorKind set (O-EK).
    // -----------------------------------------------------------------------

    @Test
    fun `S13 - a batch combining all four failure kinds gives every result an errorCode and a valid errorKind`(): Unit =
        runBlocking {
            val gateItemId = UUID.randomUUID()
            val depItemId = UUID.randomUUID()
            val blockerId = UUID.randomUUID()
            val notFoundId = UUID.randomUUID()
            val terminalId = UUID.randomUUID()

            val gateItem = makeItem(id = gateItemId, role = Role.QUEUE, tags = "feature-task")
            val depItem = makeItem(id = depItemId, role = Role.QUEUE)
            val blockerItem = makeItem(id = blockerId, role = Role.QUEUE, title = "Blocker Task")
            val terminalItem = makeItem(id = terminalId, role = Role.TERMINAL)

            val noteSchemaService = schemaServiceWith(gateSchemaEntries())
            val noteRepo = mockk<NoteRepository>()
            coEvery { noteRepo.findByItemId(gateItemId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(gateItemId, any()) } returns Result.Success(emptyList())
            val batchContext = contextWithSchema(noteRepo, noteSchemaService)

            coEvery { workItemRepo.getById(gateItemId) } returns Result.Success(gateItem)
            coEvery { workItemRepo.getById(depItemId) } returns Result.Success(depItem)
            coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)
            coEvery { workItemRepo.getById(terminalId) } returns Result.Success(terminalItem)
            coEvery { workItemRepo.getById(notFoundId) } returns
                Result.Error(RepositoryError.NotFound(notFoundId, "WorkItem not found: $notFoundId"))

            // gateItemId and terminalId have no dependency edges; depItemId is blocked by blockerId.
            every { depRepo.findByToItemId(gateItemId) } returns emptyList()
            every { depRepo.findByFromItemId(gateItemId) } returns emptyList()
            every { depRepo.findByToItemId(terminalId) } returns emptyList()
            every { depRepo.findByFromItemId(terminalId) } returns emptyList()
            val dep = Dependency(fromItemId = blockerId, toItemId = depItemId, type = DependencyType.BLOCKS)
            every { depRepo.findByToItemId(depItemId) } returns listOf(dep)
            every { depRepo.findByFromItemId(depItemId) } returns emptyList()

            val result =
                tool.execute(
                    buildParams(
                        transitionObj(gateItemId, "start"),
                        transitionObj(depItemId, "start"),
                        transitionObj(notFoundId, "start"),
                        transitionObj(terminalId, "start")
                    ),
                    batchContext
                )

            val expectedCodes =
                mapOf(
                    gateItemId to AdvanceItemTool.GATE_BLOCKED,
                    depItemId to AdvanceItemTool.DEPENDENCY_BLOCKED,
                    notFoundId to AdvanceItemTool.ITEM_NOT_FOUND,
                    terminalId to AdvanceItemTool.INVALID_TRANSITION
                )

            for ((itemId, expectedCode) in expectedCodes) {
                val r = resultFor(result, itemId)
                assertFalse(r["applied"]!!.jsonPrimitive.boolean, "expected applied=false for $itemId")
                val errorCode = r["errorCode"]?.jsonPrimitive?.content
                assertEquals(expectedCode, errorCode, "wrong errorCode for $itemId")
                val errorKind = r["errorKind"]?.jsonPrimitive?.content
                assertTrue(
                    errorKind != null && errorKind in validErrorKinds,
                    "errorKind for $itemId must be one of $validErrorKinds, got $errorKind"
                )
            }
        }

    // -----------------------------------------------------------------------
    // Additional coverage (not S-ids): the two remaining Companion constants the dispatch
    // declarations enumerate. Oracle: diagnosis "Chosen shapes" -- unknown trigger ->
    // invalid_trigger (permanent); malformed actor -> invalid_actor (permanent).
    // -----------------------------------------------------------------------

    @Test
    fun `extra - an unrecognized trigger string fails with invalid_trigger permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

            val result = tool.execute(buildParams(transitionObj(itemId, "reboot")), context)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.INVALID_TRIGGER, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
        }

    @Test
    fun `extra - an actor object with an unrecognized kind fails with invalid_actor permanent`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)

            val actorJson =
                buildJsonObject {
                    put("id", "agent-bad-kind")
                    put("kind", "bogus")
                }
            val params =
                buildJsonObject {
                    put(
                        "transitions",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemId", itemId.toString())
                                    put("trigger", "start")
                                    put("actor", actorJson)
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context)

            val r = resultFor(result, itemId)
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertEquals(AdvanceItemTool.INVALID_ACTOR, r["errorCode"]?.jsonPrimitive?.content)
            assertEquals("permanent", r["errorKind"]?.jsonPrimitive?.content)
        }
}
