package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.StatusLabelService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.test.TestStatusLabelService
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

        context = ToolExecutionContext(repoProvider)
    }

    /** Build a custom context with a specific StatusLabelService, reusing existing mocked repos. */
    private fun contextWithLabels(labelService: StatusLabelService): ToolExecutionContext =
        ToolExecutionContext(repoProvider, statusLabelService = labelService)

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Item",
        role: Role = Role.QUEUE,
        tags: String? = null,
        parentId: UUID? = null,
        depth: Int = if (parentId != null) 1 else 0
    ): WorkItem =
        WorkItem(
            id = id,
            title = title,
            role = role,
            tags = tags,
            parentId = parentId,
            depth = depth
        )

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

    private fun buildItemIdsParams(
        itemIds: List<UUID>,
        trigger: String = "complete"
    ): JsonObject =
        buildJsonObject {
            put(
                "itemIds",
                buildJsonArray {
                    itemIds.forEach { add(JsonPrimitive(it.toString())) }
                }
            )
            put("trigger", JsonPrimitive(trigger))
        }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success response, got: $obj")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: JsonElement): JsonArray = extractData(result)["results"]!!.jsonArray

    private fun extractSummary(result: JsonElement): JsonObject = extractData(result)["summary"]!!.jsonObject

    // ──────────────────────────────────────────────
    // Test 1: Empty item list returns empty results
    // ──────────────────────────────────────────────

    @Test
    fun `empty item list returns empty results`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(emptyList())

            // includeRoot=false to test descendants-only path with no items
            val params = buildRootIdParams(rootId, includeRoot = false)
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
    fun `single item with no gates completes successfully`(): Unit =
        runBlocking {
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
    fun `single item with required notes missing records gate failure`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Gated Item", role = Role.QUEUE, tags = "feature-task")

            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Acceptance criteria"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
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
    fun `linear chain where middle item gate fails causes downstream to be skipped`(): Unit =
        runBlocking {
            val idA = UUID.randomUUID()
            val idB = UUID.randomUUID()
            val idC = UUID.randomUUID()

            val itemA = makeItem(id = idA, title = "Item A", role = Role.QUEUE)
            val itemB = makeItem(id = idB, title = "Item B (gated)", role = Role.QUEUE, tags = "feature-task")
            val itemC = makeItem(id = idC, title = "Item C", role = Role.QUEUE)

            // Dependency: A -> B -> C (A blocks B, B blocks C)
            val depAtoB = Dependency(fromItemId = idA, toItemId = idB, type = DependencyType.BLOCKS)
            val depBtoC = Dependency(fromItemId = idB, toItemId = idC, type = DependencyType.BLOCKS)

            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Acceptance criteria"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if ("feature-task" in
                            tags
                        ) {
                            schemaEntries
                        } else {
                            null
                        }
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
            val resultMap =
                results.associate {
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
    fun `trigger cancel cancels items`(): Unit =
        runBlocking {
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
            tool.validateParams(
                buildJsonObject {
                    put("trigger", JsonPrimitive("complete"))
                }
            )
        }
    }

    @Test
    fun `providing both rootId and itemIds fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                    put(
                        "itemIds",
                        buildJsonArray {
                            add(JsonPrimitive(UUID.randomUUID().toString()))
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `invalid UUID in rootId fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("rootId", JsonPrimitive("not-a-uuid"))
                }
            )
        }
    }

    @Test
    fun `invalid UUID in itemIds fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put(
                        "itemIds",
                        buildJsonArray {
                            add(JsonPrimitive("not-a-uuid"))
                        }
                    )
                }
            )
        }
    }

    @Test
    fun `invalid trigger fails validation`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                    put("trigger", JsonPrimitive("start"))
                }
            )
        }
    }

    @Test
    fun `valid rootId params pass validation`() {
        // Should not throw
        tool.validateParams(
            buildJsonObject {
                put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                put("trigger", JsonPrimitive("complete"))
            }
        )
    }

    @Test
    fun `valid itemIds params pass validation`() {
        // Should not throw
        tool.validateParams(
            buildJsonObject {
                put(
                    "itemIds",
                    buildJsonArray {
                        add(JsonPrimitive(UUID.randomUUID().toString()))
                        add(JsonPrimitive(UUID.randomUUID().toString()))
                    }
                )
            }
        )
    }

    @Test
    fun `valid rootId with includeRoot param passes validation`() {
        // Should not throw
        tool.validateParams(
            buildJsonObject {
                put("rootId", JsonPrimitive(UUID.randomUUID().toString()))
                put("includeRoot", JsonPrimitive(false))
            }
        )
    }

    // ──────────────────────────────────────────────
    // Test: Terminal items are skipped (cannot transition further)
    // ──────────────────────────────────────────────

    @Test
    fun `already terminal item is skipped`(): Unit =
        runBlocking {
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
    // Test: BLOCKED items are skipped (cannot complete while blocked)
    // ──────────────────────────────────────────────

    @Test
    fun `blocked item is skipped with cannot transition reason`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Blocked Work Item", role = Role.BLOCKED)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId))
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(1, results.size)

            val r = results[0].jsonObject
            assertFalse(r["applied"]!!.jsonPrimitive.boolean)
            assertTrue(r["skipped"]!!.jsonPrimitive.boolean)
            assertTrue(r["skippedReason"]!!.jsonPrimitive.content.contains("blocked", ignoreCase = true))

            val summary = extractSummary(result)
            assertEquals(0, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(1, summary["skipped"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: rootId uses findDescendants
    // ──────────────────────────────────────────────

    @Test
    fun `rootId collects descendants and completes them when includeRoot=false`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val child = makeItem(id = childId, title = "Child Item", role = Role.QUEUE, parentId = rootId, depth = 1)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()

            val params = buildRootIdParams(rootId, includeRoot = false)
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(1, results.size)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: includeRoot=true (default) completes root item after descendants
    // ──────────────────────────────────────────────

    @Test
    fun `rootId with default includeRoot completes root item after descendants`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val root = makeItem(id = rootId, title = "Root Item", role = Role.QUEUE, depth = 0)
            val child = makeItem(id = childId, title = "Child Item", role = Role.QUEUE, parentId = rootId, depth = 1)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
            coEvery { workItemRepo.getById(rootId) } returns Result.Success(root)
            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()

            // No includeRoot param => defaults to true
            val params = buildRootIdParams(rootId)
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(2, results.size, "Should complete both child and root")

            val resultMap =
                results.associate {
                    val obj = it.jsonObject
                    UUID.fromString(obj["itemId"]!!.jsonPrimitive.content) to obj
                }

            val childResult = resultMap[childId]!!
            assertTrue(childResult["applied"]!!.jsonPrimitive.boolean, "Child should be completed")

            val rootResult = resultMap[rootId]!!
            assertTrue(rootResult["applied"]!!.jsonPrimitive.boolean, "Root should be completed")
            assertEquals(
                rootId.toString(),
                results
                    .last()
                    .jsonObject["itemId"]!!
                    .jsonPrimitive.content,
                "Root should be processed last"
            )

            val summary = extractSummary(result)
            assertEquals(2, summary["total"]!!.jsonPrimitive.int)
            assertEquals(2, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(0, summary["skipped"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: includeRoot=false excludes root from processing
    // ──────────────────────────────────────────────

    @Test
    fun `rootId with includeRoot=false excludes root item`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val child = makeItem(id = childId, title = "Child Item", role = Role.QUEUE, parentId = rootId, depth = 1)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()

            val params = buildRootIdParams(rootId, includeRoot = false)
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(1, results.size, "Only child should be in results when includeRoot=false")
            assertEquals(childId.toString(), results[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)

            val summary = extractSummary(result)
            assertEquals(1, summary["total"]!!.jsonPrimitive.int)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: includeRoot=true with cancel trigger cancels root after descendants
    // ──────────────────────────────────────────────

    @Test
    fun `rootId with cancel trigger cancels root item after descendants`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val root = makeItem(id = rootId, title = "Feature Root", role = Role.QUEUE, depth = 0)
            val child = makeItem(id = childId, title = "Task Child", role = Role.WORK, parentId = rootId, depth = 1)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
            coEvery { workItemRepo.getById(rootId) } returns Result.Success(root)
            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()

            val params = buildRootIdParams(rootId, trigger = "cancel")
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(2, results.size)

            results.forEach { r ->
                assertTrue(
                    r.jsonObject["applied"]!!.jsonPrimitive.boolean,
                    "Both items should be cancelled: ${r.jsonObject["itemId"]}"
                )
                assertEquals("cancel", r.jsonObject["trigger"]!!.jsonPrimitive.content)
            }

            val summary = extractSummary(result)
            assertEquals(2, summary["completed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: includeRoot=true, root has gate failure (missing required notes)
    // ──────────────────────────────────────────────

    @Test
    fun `root item gate failure is reported when required notes missing`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val root = makeItem(id = rootId, title = "Gated Root", role = Role.QUEUE, tags = "feature", depth = 0)
            val child = makeItem(id = childId, title = "Child Item", role = Role.QUEUE, parentId = rootId, depth = 1)

            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Acceptance criteria"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if ("feature" in
                            tags
                        ) {
                            schemaEntries
                        } else {
                            null
                        }
                }

            val repoProvider = mockk<RepositoryProvider>()
            every { repoProvider.workItemRepository() } returns workItemRepo
            every { repoProvider.dependencyRepository() } returns depRepo
            every { repoProvider.noteRepository() } returns noteRepo
            every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
            val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(listOf(child))
            coEvery { workItemRepo.getById(rootId) } returns Result.Success(root)
            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(childId) } returns emptyList()
            coEvery { noteRepo.findByItemId(rootId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(rootId, any()) } returns Result.Success(emptyList())

            val params = buildRootIdParams(rootId)
            val result = tool.execute(params, gatedContext)

            val results = extractResults(result)
            assertEquals(2, results.size)

            val resultMap =
                results.associate {
                    val obj = it.jsonObject
                    UUID.fromString(obj["itemId"]!!.jsonPrimitive.content) to obj
                }

            val childResult = resultMap[childId]!!
            assertTrue(childResult["applied"]!!.jsonPrimitive.boolean, "Child should complete")

            val rootResult = resultMap[rootId]!!
            assertFalse(rootResult["applied"]!!.jsonPrimitive.boolean, "Root should have gate failure")
            assertNotNull(rootResult["gateErrors"], "Root should have gateErrors")
            val gateErrors = rootResult["gateErrors"]!!.jsonArray
            assertTrue(gateErrors[0].jsonPrimitive.content.contains("acceptance-criteria"))

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(1, summary["gateFailures"]!!.jsonPrimitive.int)
            assertEquals(0, summary["skipped"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: no descendants, only root — root is still processed with includeRoot=true
    // ──────────────────────────────────────────────

    // ──────────────────────────────────────────────
    // Test: checkGate — complete trigger with all required notes filled passes gate
    // ──────────────────────────────────────────────

    @Test
    fun `complete trigger with all required notes filled passes gate and item completes`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Fully Noted Item", role = Role.QUEUE, tags = "feature")

            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Acceptance criteria"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if ("feature" in
                            tags
                        ) {
                            schemaEntries
                        } else {
                            null
                        }
                }

            val repoProvider = mockk<RepositoryProvider>()
            every { repoProvider.workItemRepository() } returns workItemRepo
            every { repoProvider.dependencyRepository() } returns depRepo
            every { repoProvider.noteRepository() } returns noteRepo
            every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
            val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

            // Note exists with non-blank body — gate should pass
            val note = Note(itemId = itemId, key = "acceptance-criteria", role = "queue", body = "AC content here.")
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(note))
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(note))
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId))
            val result = tool.execute(params, gatedContext)

            val results = extractResults(result)
            assertEquals(1, results.size)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean, "Item should complete when all notes are filled")
            assertNull(results[0].jsonObject["gateErrors"], "No gate errors expected when notes are filled")

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: checkGate — cancel trigger bypasses gate even when notes are missing
    // ──────────────────────────────────────────────

    @Test
    fun `cancel trigger bypasses gate even when required notes are missing`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Gated but Cancelled", role = Role.QUEUE, tags = "feature")

            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Acceptance criteria"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if ("feature" in
                            tags
                        ) {
                            schemaEntries
                        } else {
                            null
                        }
                }

            val repoProvider = mockk<RepositoryProvider>()
            every { repoProvider.workItemRepository() } returns workItemRepo
            every { repoProvider.dependencyRepository() } returns depRepo
            every { repoProvider.noteRepository() } returns noteRepo
            every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
            val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

            // No notes at all — gate would fail for "complete" but should be bypassed by "cancel"
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(emptyList())
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId), trigger = "cancel")
            val result = tool.execute(params, gatedContext)

            val results = extractResults(result)
            assertEquals(1, results.size)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean, "Cancel should bypass gate and succeed")
            assertNull(results[0].jsonObject["gateErrors"], "No gate errors for cancel trigger")
            assertEquals("cancel", results[0].jsonObject["trigger"]!!.jsonPrimitive.content)

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: checkGate — item with unmatched tags passes gate freely
    // ──────────────────────────────────────────────

    @Test
    fun `complete trigger with unmatched tags passes gate freely`(): Unit =
        runBlocking {
            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Unmatched Tags Item", role = Role.QUEUE, tags = "unrelated-tag")

            // Schema only matches "feature" — "unrelated-tag" should return null
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if ("feature" in tags) {
                            listOf(
                                NoteSchemaEntry(
                                    key = "acceptance-criteria",
                                    role = Role.QUEUE,
                                    required = true,
                                    description = "AC"
                                )
                            )
                        } else {
                            null
                        }
                }

            val repoProvider = mockk<RepositoryProvider>()
            every { repoProvider.workItemRepository() } returns workItemRepo
            every { repoProvider.dependencyRepository() } returns depRepo
            every { repoProvider.noteRepository() } returns noteRepo
            every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
            val gatedContext = ToolExecutionContext(repoProvider, noteSchemaService)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId))
            val result = tool.execute(params, gatedContext)

            val results = extractResults(result)
            assertEquals(1, results.size)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean, "Unmatched tags should pass gate freely")

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
            assertEquals(0, summary["gateFailures"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Test: no descendants, only root — root is still processed with includeRoot=true
    // ──────────────────────────────────────────────

    @Test
    fun `rootId with no descendants still processes root when includeRoot=true`(): Unit =
        runBlocking {
            val rootId = UUID.randomUUID()
            val root = makeItem(id = rootId, title = "Lone Root", role = Role.QUEUE, depth = 0)

            coEvery { workItemRepo.findDescendants(rootId) } returns Result.Success(emptyList())
            coEvery { workItemRepo.getById(rootId) } returns Result.Success(root)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())

            val params = buildRootIdParams(rootId)
            val result = tool.execute(params, context)

            val results = extractResults(result)
            assertEquals(1, results.size, "Root should be included when it has no descendants")
            assertEquals(rootId.toString(), results[0].jsonObject["itemId"]!!.jsonPrimitive.content)
            assertTrue(results[0].jsonObject["applied"]!!.jsonPrimitive.boolean)

            val summary = extractSummary(result)
            assertEquals(1, summary["completed"]!!.jsonPrimitive.int)
        }

    // ──────────────────────────────────────────────
    // Custom StatusLabel integration tests
    // ──────────────────────────────────────────────

    @Test
    fun `config-driven custom label applied on complete via itemIds`(): Unit =
        runBlocking {
            val customLabels = TestStatusLabelService(mapOf("complete" to "finished", "cancel" to "dropped"))
            val customContext = contextWithLabels(customLabels)

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Custom Label Item", role = Role.QUEUE)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId))
            val result = tool.execute(params, customContext)

            val results = extractResults(result)
            assertEquals(1, results.size)
            val r = results[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)
            // Custom config label "finished" should be used (resolution.statusLabel is null for complete)
            assertEquals("finished", r["statusLabel"]!!.jsonPrimitive.content)
        }

    @Test
    fun `cancel label precedence in complete_tree - hardcoded cancelled wins over config`(): Unit =
        runBlocking {
            // Config maps cancel→"dropped", but resolution.statusLabel = "cancelled" (hardcoded)
            // effectiveLabel = "cancelled" ?: "dropped" = "cancelled"
            val customLabels = TestStatusLabelService(mapOf("cancel" to "dropped"))
            val customContext = contextWithLabels(customLabels)

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, title = "Cancel Precedence Item", role = Role.WORK)

            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(itemId) } returns emptyList()

            val params = buildItemIdsParams(listOf(itemId), trigger = "cancel")
            val result = tool.execute(params, customContext)

            val results = extractResults(result)
            assertEquals(1, results.size)
            val r = results[0].jsonObject
            assertTrue(r["applied"]!!.jsonPrimitive.boolean)
            // Hardcoded "cancelled" from resolution.statusLabel takes precedence over config "dropped"
            assertEquals("cancelled", r["statusLabel"]!!.jsonPrimitive.content)
        }
}
