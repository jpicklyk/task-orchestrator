package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.*

class CompleteTreeToolTest {

    private lateinit var tool: CompleteTreeTool
    private lateinit var context: ToolExecutionContext
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

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo

        context = ToolExecutionContext(repoProvider)
    }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Item",
        role: Role = Role.QUEUE,
        tags: String? = null,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem {
        return WorkItem(
            id = id,
            title = title,
            role = role,
            tags = tags,
            parentId = parentId,
            depth = depth
        )
    }

    private fun buildRootIdParams(rootId: UUID, trigger: String = "complete"): JsonObject {
        return buildJsonObject {
            put("rootId", JsonPrimitive(rootId.toString()))
            put("trigger", JsonPrimitive(trigger))
        }
    }

    private fun buildItemIdsParams(itemIds: List<UUID>, trigger: String = "complete"): JsonObject {
        return buildJsonObject {
            put("itemIds", buildJsonArray {
                itemIds.forEach { add(JsonPrimitive(it.toString())) }
            })
            put("trigger", JsonPrimitive(trigger))
        }
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response, got: $obj")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray {
        return extractData(result)["results"]!!.jsonArray
    }

    private fun extractSummary(result: JsonElement): JsonObject {
        return extractData(result)["summary"]!!.jsonObject
    }

    // ──────────────────────────────────────────────
    // Test 1: Empty item list returns empty results
    // ──────────────────────────────────────────────

    @Test
    fun `empty item list returns empty results`(): Unit = runBlocking {
        val rootId = UUID.randomUUID()

        coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(emptyList())

        val params = buildRootIdParams(rootId)
        val result = tool.execute(params, context)

        val data = extractData(result)
        val results = data["results"]!!.jsonArray
        assertEquals(0, results.size)

        val summary = data["summary"]!!.jsonObject
        assertEquals(0, summary["total"]!!.jsonPrimitive.int)
        assertEquals(0, summary["completed"]!!.jsonPrimitive.int)
        assertEquals(0, summary["skipped"]!!.jsonPrimitive.int)
        assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test 2: Single item with no gates completes successfully
    // ──────────────────────────────────────────────

    @Test
    fun `single item with no gates completes successfully`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, title = "Simple Item", role = Role.QUEUE)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()

        val params = buildItemIdsParams(listOf(itemId))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(1, results.size)

        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals(itemId.toString(), r["itemId"]!!.jsonPrimitive.content)
        assertEquals("Simple Item", r["title"]!!.jsonPrimitive.content)
        assertEquals("complete", r["trigger"]!!.jsonPrimitive.content)

        val summary = extractSummary(result)
        assertEquals(1, summary["total"]!!.jsonPrimitive.int)
        assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
        assertEquals(0, summary["skipped"]!!.jsonPrimitive.int)
        assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test 3: Single item with required notes missing -> gate failure recorded
    // ──────────────────────────────────────────────

    @Test
    fun `single item with required notes missing records gate failure`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, title = "Gated Item", role = Role.QUEUE, tags = "feature-task")

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria")
        )
        val noteSchemaService = object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.isNotEmpty()) schemaEntries else null
        }

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val params = buildItemIdsParams(listOf(itemId))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        assertEquals(1, results.size)

        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        val gateErrors = r["gateErrors"]!!.jsonArray
        assertEquals(1, gateErrors.size)
        assertTrue(gateErrors[0].jsonPrimitive.content.contains("acceptance-criteria"))

        val summary = extractSummary(result)
        assertEquals(0, summary["completed"]!!.jsonPrimitive.int)
        assertEquals(1, summary["gateFailures"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test 4: Linear chain A->B->C where B has missing required note:
    //         B gate fails, C skipped, A completes
    // ──────────────────────────────────────────────

    @Test
    fun `linear chain where middle item gate fails causes downstream to be skipped`(): Unit = runBlocking {
        val idA = UUID.randomUUID()
        val idB = UUID.randomUUID()
        val idC = UUID.randomUUID()

        val itemA = makeItem(id = idA, title = "Item A", role = Role.QUEUE)
        val itemB = makeItem(id = idB, title = "Item B (gated)", role = Role.QUEUE, tags = "feature-task")
        val itemC = makeItem(id = idC, title = "Item C", role = Role.QUEUE)

        // Dependency: A -> B -> C (A blocks B, B blocks C)
        val depAtoB = Dependency(fromItemId = idA, toItemId = idB, type = DependencyType.BLOCKS)
        val depBtoC = Dependency(fromItemId = idB, toItemId = idC, type = DependencyType.BLOCKS)

        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true,
                description = "Acceptance criteria")
        )
        val noteSchemaService = object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if ("feature-task" in tags) schemaEntries else null
        }

        val repoProvider = mockk<RepositoryProvider>()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns depRepo
        every { repoProvider.noteRepository() } returns noteRepo
        every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
        val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

        coEvery { workItemRepo.getById(idA) } returns Result.Success(itemA)
        coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
        coEvery { workItemRepo.getById(idC) } returns Result.Success(itemC)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())

        // A has no incoming deps in target set; B has A as blocker; C has B as blocker
        every { depRepo.findByToItemId(idA) } returns emptyList()
        every { depRepo.findByToItemId(idB) } returns listOf(depAtoB)
        every { depRepo.findByToItemId(idC) } returns listOf(depBtoC)

        // No notes exist for B (gated item)
        coEvery { noteRepo.findByItemId(idB) } returns Result.Success(emptyList())
        coEvery { noteRepo.findByItemId(idB, any()) } returns Result.Success(emptyList())

        val params = buildItemIdsParams(listOf(idA, idB, idC))
        val result = tool.execute(params, gatedContext)

        val results = extractResults(result)
        assertEquals(3, results.size)

        // Find results by itemId
        val resultMap = results.associate {
            val obj = it.jsonObject
            UUID.fromString(obj["itemId"]!!.jsonPrimitive.content) to obj
        }

        // A should be completed
        val rA = resultMap[idA]!!
        assertTrue(rA["applied"]!!.jsonPrimitive.boolean, "A should be completed")

        // B should have gate failure
        val rB = resultMap[idB]!!
        assertFalse(rB["applied"]!!.jsonPrimitive.boolean, "B should have gate failure")
        assertNotNull(rB["gateErrors"], "B should have gateErrors")

        // C should be skipped due to B's gate failure
        val rC = resultMap[idC]!!
        assertFalse(rC["applied"]!!.jsonPrimitive.boolean, "C should be skipped")
        assertTrue(rC["skipped"]!!.jsonPrimitive.boolean, "C should be marked as skipped")

        val summary = extractSummary(result)
        assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
        assertEquals(1, summary["gateFailures"]!!.jsonPrimitive.int)
        assertEquals(1, summary["skipped"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test 5: trigger="cancel" cancels items (statusLabel="cancelled")
    // ──────────────────────────────────────────────

    @Test
    fun `trigger cancel cancels items`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, title = "Item to Cancel", role = Role.WORK)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val params = buildItemIdsParams(listOf(itemId), trigger = "cancel")
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(1, results.size)

        val r = results[0].jsonObject
        assertTrue(r["applied"]!!.jsonPrimitive.boolean)
        assertEquals("cancel", r["trigger"]!!.jsonPrimitive.content)

        val summary = extractSummary(result)
        assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test 6: Must provide either rootId or itemIds -> validation error otherwise
    // ──────────────────────────────────────────────

    @Test
    fun `missing both rootId and itemIds fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("trigger", JsonPrimitive("complete"))
            })
        }
    }

    @Test
    fun `providing both rootId and itemIds fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                put("itemIds", buildJsonArray {
                    add(JsonPrimitive(UUID.randomUUID().toString()))
                })
            })
        }
    }

    @Test
    fun `invalid UUID in rootId fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("rootId", JsonPrimitive("not-a-uuid"))
            })
        }
    }

    @Test
    fun `invalid UUID in itemIds fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("itemIds", buildJsonArray {
                    add(JsonPrimitive("not-a-uuid"))
                })
            })
        }
    }

    @Test
    fun `invalid trigger fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                put("trigger", JsonPrimitive("start"))
            })
        }
    }

    @Test
    fun `valid rootId params pass validation`() {
        // Should not throw
        tool.validateParams(buildJsonObject {
            put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
            put("trigger", JsonPrimitive("complete"))
        })
    }

    @Test
    fun `valid itemIds params pass validation`() {
        // Should not throw
        tool.validateParams(buildJsonObject {
            put("itemIds", buildJsonArray {
                add(JsonPrimitive(UUID.randomUUID().toString()))
                add(JsonPrimitive(UUID.randomUUID().toString()))
            })
        })
    }

    // ──────────────────────────────────────────────
    // Test: Terminal items are skipped (cannot transition further)
    // ──────────────────────────────────────────────

    @Test
    fun `already terminal item is skipped`(): Unit = runBlocking {
        val itemId = UUID.randomUUID()
        val item = makeItem(id = itemId, title = "Already Done", role = Role.TERMINAL)

        coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
        every { depRepo.findByToItemId(itemId) } returns emptyList()

        val params = buildItemIdsParams(listOf(itemId))
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(1, results.size)

        val r = results[0].jsonObject
        assertFalse(r["applied"]!!.jsonPrimitive.boolean)
        assertTrue(r["skipped"]!!.jsonPrimitive.boolean)

        val summary = extractSummary(result)
        assertEquals(0, summary["completed"]!!.jsonPrimitive.int)
        assertEquals(1, summary["skipped"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // Test: rootId uses findDescendants
    // ──────────────────────────────────────────────

    @Test
    fun `rootId collects descendants and completes them`(): Unit = runBlocking {
        val rootId = UUID.randomUUID()
        val childId = UUID.randomUUID()
        val child = makeItem(id = childId, title = "Child Item", role = Role.QUEUE, parentId = rootId, depth = 1)

        coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
        coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(childId) } returns emptyList()

        val params = buildRootIdParams(rootId)
        val result = tool.execute(params, context)

        val results = extractResults(result)
        assertEquals(1, results.size)
        assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)

        val summary = extractSummary(result)
        assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
    }
}
