package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/**
 * Independent test-author coverage for item 3e455253 (complete_tree via AdvanceService) —
 * cascade/topological-order scenarios S3, S4, S8, S12 from the frozen `test-plan` note.
 *
 * Oracles: `current/docs/api-reference.md` `advance_item` + `complete_tree` sections, and
 * `AdvanceService` KDoc (steps 1-7). Written against public signatures/declarations and the
 * existing `AdvanceItemToolTest`/`CompleteTreeToolTest` harnesses only — never the implementer's
 * changed function bodies in `CompleteTreeTool.kt`.
 */
class CompleteTreeToolCascadeOrderTest {
    private lateinit var tool: CompleteTreeTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    @BeforeEach
    fun setUp() {
        tool = CompleteTreeTool()
        workItemRepo = mockk()
        depRepo = mockk()
        noteRepo = mockk()
        roleTransitionRepo = mockk()

        repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
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
        title: String = "Test Item",
        role: Role = Role.QUEUE,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem = WorkItem(id = id, title = title, role = role, parentId = parentId, depth = depth)

    private fun buildItemIdsParams(
        itemIds: List<UUID>,
        trigger: String = "complete"
    ): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it.toString())) } })
            put("trigger", JsonPrimitive(trigger))
        }

    private fun buildRootIdParams(
        rootId: UUID,
        trigger: String = "complete",
        includeRoot: Boolean? = null
    ): JsonObject =
        buildJsonObject {
            put("rootId", JsonPrimitive(rootId.toString()))
            put("trigger", JsonPrimitive(trigger))
            if (includeRoot != null) put("includeRoot", JsonPrimitive(includeRoot))
        }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response, got: $obj")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray = extractData(result)["results"]!!.jsonArray

    private fun extractSummary(result: JsonElement): JsonObject = extractData(result)["summary"]!!.jsonObject

    private fun resultFor(
        result: JsonElement,
        itemId: UUID
    ): JsonObject =
        extractResults(result)
            .map { it.jsonObject }
            .first { it["itemId"]!!.jsonPrimitive.content == itemId.toString() }

    // ──────────────────────────────────────────────
    // S3 — last child completes; its AUTO parent (outside the target set) is cascaded to
    // TERMINAL, and the cascade is reported on the child's own result — parity with advance_item's
    // `cascadeEvents`.
    // ──────────────────────────────────────────────

    @Test
    fun `S3 completing the last child cascades an out-of-set AUTO parent to terminal`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parentItem = makeItem(id = parentId, role = Role.WORK, title = "Cascade Parent")
            val childItem = makeItem(id = childId, role = Role.WORK, title = "Last Child", parentId = parentId)

            coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parentItem)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()
            every { depRepo.findByFromItemId(childId) } returns emptyList()
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))

            // Only the child is in the target set — the parent is NOT requested directly.
            val params = buildItemIdsParams(listOf(childId))
            val result = tool.execute(params, context)

            val rChild = resultFor(result, childId)
            assertTrue(rChild["applied"]!!.jsonPrimitive.boolean)

            val cascadeEvents = rChild["cascadeEvents"]!!.jsonArray
            assertEquals(1, cascadeEvents.size, "expected exactly one cascade event on the child's result: $rChild")
            val cascade = cascadeEvents[0].jsonObject
            assertEquals(parentId.toString(), cascade["itemId"]!!.jsonPrimitive.content)
            assertEquals("terminal", cascade["targetRole"]!!.jsonPrimitive.content)
            assertTrue(cascade["applied"]!!.jsonPrimitive.boolean)
        }

    // ──────────────────────────────────────────────
    // S4 — topological dependency order: A BLOCKS B, both in the target set, supplied in REVERSED
    // input order. A must persist (and become visible to B's own dependency validation) before B
    // is advanced — proven dynamically, not just by input-order coincidence.
    // ──────────────────────────────────────────────

    @Test
    fun `S4 a blocker in the target set is persisted before its dependent regardless of input order`(): Unit =
        runBlocking {
            val idA = UUID.randomUUID()
            val idB = UUID.randomUUID()
            var currentA = makeItem(id = idA, title = "Blocker A", role = Role.QUEUE)
            val itemB = makeItem(id = idB, title = "Dependent B", role = Role.QUEUE)
            val depAtoB = Dependency(fromItemId = idA, toItemId = idB, type = DependencyType.BLOCKS)

            val applyOrder = mutableListOf<UUID>()
            coEvery { workItemRepo.getById(idA) } answers { Result.Success(currentA) }
            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.update(any()) } answers {
                val updated = firstArg<WorkItem>()
                applyOrder.add(updated.id)
                if (updated.id == idA) currentA = updated
                Result.Success(updated)
            }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(idA) } returns emptyList()
            every { depRepo.findByFromItemId(idA) } returns emptyList()
            every { depRepo.findByToItemId(idB) } returns listOf(depAtoB)
            every { depRepo.findByFromItemId(idB) } returns emptyList()

            // Reversed input order: B is listed first.
            val params = buildItemIdsParams(listOf(idB, idA))
            val result = tool.execute(params, context)

            val rA = resultFor(result, idA)
            val rB = resultFor(result, idB)
            assertTrue(rA["applied"]!!.jsonPrimitive.boolean, "A should complete: $rA")
            assertTrue(
                rB["applied"]!!.jsonPrimitive.boolean,
                "B can only complete if A was already TERMINAL by the time B's dependency " +
                    "validation ran — proves topological order, not input order: $rB"
            )

            assertEquals(listOf(idA, idB), applyOrder, "A must be persisted (updated) before B")
        }

    // ──────────────────────────────────────────────
    // S8 — a blocking dependency OUTSIDE the target set, still non-terminal, fails the item's own
    // validation (not a gate failure) with a `blockers` array, mirroring advance_item's dependency
    // validation failure shape.
    // ──────────────────────────────────────────────

    @Test
    fun `S8 a non-terminal blocker outside the target set fails validation with blockers`(): Unit =
        runBlocking {
            val idB = UUID.randomUUID()
            val idBlockerC = UUID.randomUUID()
            val itemB = makeItem(id = idB, title = "Blocked B", role = Role.QUEUE)
            val blockerC = makeItem(id = idBlockerC, title = "Outside blocker C", role = Role.QUEUE)
            val depCtoB = Dependency(fromItemId = idBlockerC, toItemId = idB, type = DependencyType.BLOCKS)

            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.getById(idBlockerC) } returns Result.Success(blockerC)
            every { depRepo.findByToItemId(idB) } returns listOf(depCtoB)
            every { depRepo.findByFromItemId(idB) } returns emptyList()

            // Only B is in the target set; C (the blocker) is never requested.
            val params = buildItemIdsParams(listOf(idB))
            val result = tool.execute(params, context)

            val rB = resultFor(result, idB)
            assertFalse(rB["applied"]!!.jsonPrimitive.boolean)
            assertNull(rB["gateErrors"], "an outside-the-set blocking dependency is a validation failure, not a gate failure")
            assertNull(rB["skipped"], "B itself directly fails validation; it is not a propagated skip")

            val blockers = rB["blockers"]!!.jsonArray
            assertEquals(1, blockers.size)
            val blocker = blockers[0].jsonObject
            assertEquals(idBlockerC.toString(), blocker["fromItemId"]!!.jsonPrimitive.content)
            assertEquals("queue", blocker["currentRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", blocker["requiredRole"]!!.jsonPrimitive.content)
        }

    // ──────────────────────────────────────────────
    // S12 — the root item, itself in the target set (rootId + includeRoot=true, default), is
    // terminalized by the cascade fired while completing its own last descendant earlier in the
    // SAME call. When complete_tree reaches the root (processed last, per its own documented
    // ordering), it must be reported exactly once as skipped "already terminal" — not gate-checked,
    // not double-completed, not errored.
    // ──────────────────────────────────────────────

    @Test
    fun `S12 a root terminalized by its own child's cascade is reported once as already terminal`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val rootItem = makeItem(id = rootId, role = Role.WORK, title = "Root")
            val childItem = makeItem(id = childId, role = Role.WORK, title = "Only child", parentId = rootId)

            var currentRoot = rootItem
            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(childItem))
            coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
            coEvery { workItemRepo.getById(rootId) } answers { Result.Success(currentRoot) }
            coEvery { workItemRepo.update(any()) } answers {
                val updated = firstArg<WorkItem>()
                if (updated.id == rootId) currentRoot = updated
                Result.Success(updated)
            }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()
            every { depRepo.findByFromItemId(childId) } returns emptyList()
            // All (one) of the root's children are now terminal once the child applies — the
            // terminal cascade fires up to the root during the CHILD's own advance() call.
            coEvery { workItemRepo.countChildrenByRole(rootId) } returns Result.Success(mapOf(Role.TERMINAL to 1))

            // rootId mode, includeRoot=true (default): descendants processed first, root last —
            // per CompleteTreeTool's own documented ordering.
            val params = buildRootIdParams(rootId)
            val result = tool.execute(params, context)

            val results = extractResults(result)
            val rootResults = results.filter { it.jsonObject["itemId"]!!.jsonPrimitive.content == rootId.toString() }
            assertEquals(1, rootResults.size, "the root must be reported exactly once")

            val rRoot = rootResults[0].jsonObject
            assertTrue(rRoot["skipped"]!!.jsonPrimitive.boolean)
            assertEquals("already terminal", rRoot["skippedReason"]!!.jsonPrimitive.content)
            assertNull(rRoot["gateErrors"])

            val rChild = resultFor(result, childId)
            assertTrue(rChild["applied"]!!.jsonPrimitive.boolean)

            val summary = extractSummary(result)
            assertEquals(2, summary["total"]!!.jsonPrimitive.int)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(1, summary["skipped"]!!.jsonPrimitive.int)
            assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
        }
}
