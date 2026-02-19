package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
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

class CreateWorkTreeToolTest {

    private lateinit var tool: CreateWorkTreeTool
    private lateinit var context: ToolExecutionContext
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var mockExecutor: WorkTreeExecutor
    private lateinit var repoProvider: RepositoryProvider

    @BeforeEach
    fun setUp() {
        tool = CreateWorkTreeTool()
        workItemRepo = mockk()
        mockExecutor = mockk()

        repoProvider = mockk()
        every { repoProvider.workItemRepository() } returns workItemRepo
        every { repoProvider.dependencyRepository() } returns mockk()
        every { repoProvider.noteRepository() } returns mockk()
        every { repoProvider.roleTransitionRepository() } returns mockk()
        every { repoProvider.database() } returns null
        every { repoProvider.workTreeExecutor() } returns mockExecutor

        context = ToolExecutionContext(repoProvider)
    }

    // ──────────────────────────────────────────────
    // Helper: build params
    // ──────────────────────────────────────────────

    private fun buildParams(
        root: JsonObject = buildJsonObject { put("title", JsonPrimitive("Root Task")) },
        parentId: String? = null,
        children: JsonArray? = null,
        deps: JsonArray? = null,
        createNotes: Boolean? = null
    ): JsonObject = buildJsonObject {
        put("root", root)
        if (parentId != null) put("parentId", JsonPrimitive(parentId))
        if (children != null) put("children", children)
        if (deps != null) put("deps", deps)
        if (createNotes != null) put("createNotes", JsonPrimitive(createNotes))
    }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true, got: $result")
        return obj["data"] as JsonObject
    }

    private fun makeChildSpec(ref: String, title: String, tags: String? = null): JsonObject =
        buildJsonObject {
            put("ref", JsonPrimitive(ref))
            put("title", JsonPrimitive(title))
            if (tags != null) put("tags", JsonPrimitive(tags))
        }

    private fun makeDepSpec(from: String, to: String, type: String = "BLOCKS"): JsonObject =
        buildJsonObject {
            put("from", JsonPrimitive(from))
            put("to", JsonPrimitive(to))
            put("type", JsonPrimitive(type))
        }

    /**
     * Returns a WorkTreeResult that mirrors the input: items and refToId from the input,
     * plus the provided deps and notes. Used to simulate a successful executor that echoes
     * back what the tool built.
     */
    private fun echoResult(input: WorkTreeInput, deps: List<Dependency> = emptyList()): WorkTreeResult {
        val refToId = input.refToItem.mapValues { (_, item) -> item.id }
        return WorkTreeResult(
            items = input.items,
            refToId = refToId,
            deps = deps,
            notes = input.notes
        )
    }

    // ──────────────────────────────────────────────
    // 1. Basic tree creation: root + 2 children
    // ──────────────────────────────────────────────

    @Test
    fun `basic tree creation returns distinct UUIDs for root and children`(): Unit = runBlocking {
        // Mirror the input back as the result
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child One"))
            add(makeChildSpec("c2", "Child Two"))
        }
        val params = buildParams(children = children)
        val result = tool.execute(params, context)

        val data = extractData(result)

        val rootId = data["root"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val childrenArr = data["children"]!!.jsonArray
        assertEquals(2, childrenArr.size)

        val c1Id = childrenArr[0].jsonObject["id"]!!.jsonPrimitive.content
        val c2Id = childrenArr[1].jsonObject["id"]!!.jsonPrimitive.content

        // All UUIDs must be distinct
        assertNotEquals(rootId, c1Id, "Root and c1 should have different UUIDs")
        assertNotEquals(rootId, c2Id, "Root and c2 should have different UUIDs")
        assertNotEquals(c1Id, c2Id, "c1 and c2 should have different UUIDs")

        // Verify ref names preserved
        assertEquals("c1", childrenArr[0].jsonObject["ref"]!!.jsonPrimitive.content)
        assertEquals("c2", childrenArr[1].jsonObject["ref"]!!.jsonPrimitive.content)

        // Depth: root=0, children=1
        assertEquals(0, data["root"]!!.jsonObject["depth"]!!.jsonPrimitive.int)
        assertEquals(1, childrenArr[0].jsonObject["depth"]!!.jsonPrimitive.int)
        assertEquals(1, childrenArr[1].jsonObject["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 2. Dependency wiring: linear chain root → c1 → c2
    // ──────────────────────────────────────────────

    @Test
    fun `dependency wiring preserves fromRef and toRef in response`(): Unit = runBlocking {
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            val refToId = input.refToItem.mapValues { (_, item) -> item.id }
            // Build deps from the spec input
            val deps = input.deps.map { spec ->
                Dependency(
                    fromItemId = refToId[spec.fromRef]!!,
                    toItemId = refToId[spec.toRef]!!,
                    type = spec.type,
                    unblockAt = spec.unblockAt
                )
            }
            echoResult(input, deps)
        }

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child One"))
            add(makeChildSpec("c2", "Child Two"))
        }
        val deps = buildJsonArray {
            add(makeDepSpec("root", "c1"))
            add(makeDepSpec("c1", "c2"))
        }
        val params = buildParams(children = children, deps = deps)
        val result = tool.execute(params, context)

        val data = extractData(result)
        val depsArr = data["dependencies"]!!.jsonArray
        assertEquals(2, depsArr.size)

        val dep0 = depsArr[0].jsonObject
        assertEquals("root", dep0["fromRef"]!!.jsonPrimitive.content)
        assertEquals("c1", dep0["toRef"]!!.jsonPrimitive.content)
        assertEquals("BLOCKS", dep0["type"]!!.jsonPrimitive.content)

        val dep1 = depsArr[1].jsonObject
        assertEquals("c1", dep1["fromRef"]!!.jsonPrimitive.content)
        assertEquals("c2", dep1["toRef"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 3. createNotes=true with a mock schema service
    // ──────────────────────────────────────────────

    @Test
    fun `createNotes=true creates blank notes for each item matching schema`(): Unit = runBlocking {
        val schemaEntries = listOf(
            NoteSchemaEntry(key = "acceptance-criteria", role = "queue", required = true)
        )
        val noteSchemaService = object : NoteSchemaService {
            override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                if (tags.contains("feature-task")) schemaEntries else null
        }
        // Rebuild context with custom NoteSchemaService
        val provider2 = mockk<RepositoryProvider>()
        val mockExecutor2 = mockk<WorkTreeExecutor>()
        every { provider2.workItemRepository() } returns workItemRepo
        every { provider2.dependencyRepository() } returns mockk()
        every { provider2.noteRepository() } returns mockk()
        every { provider2.roleTransitionRepository() } returns mockk()
        every { provider2.database() } returns null
        every { provider2.workTreeExecutor() } returns mockExecutor2
        val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

        // Echo back items + notes from the input
        coEvery { mockExecutor2.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val children = buildJsonArray {
            add(buildJsonObject {
                put("ref", JsonPrimitive("c1"))
                put("title", JsonPrimitive("Child with schema"))
                put("tags", JsonPrimitive("feature-task"))
            })
        }
        val rootSpec = buildJsonObject {
            put("title", JsonPrimitive("Root"))
            // root has no tags → no schema match → no notes
        }
        val params = buildParams(root = rootSpec, children = children, createNotes = true)
        val result = tool.execute(params, contextWithSchema)

        val data = extractData(result)
        val notesArr = data["notes"]!!.jsonArray
        // Only c1 matches the schema, so 1 note
        assertEquals(1, notesArr.size)
        val note = notesArr[0].jsonObject
        assertEquals("c1", note["itemRef"]!!.jsonPrimitive.content)
        assertEquals("acceptance-criteria", note["key"]!!.jsonPrimitive.content)
        assertEquals("queue", note["role"]!!.jsonPrimitive.content)
    }

    // ──────────────────────────────────────────────
    // 4. Depth cap: parentId at depth 2 → root becomes depth 3 (MAX_DEPTH) → success
    // ──────────────────────────────────────────────

    @Test
    fun `root at depth 3 (MAX_DEPTH) succeeds`(): Unit = runBlocking {
        val parentItemId = UUID.randomUUID()
        val parentItem = WorkItem(
            id = parentItemId,
            title = "Deep parent",
            depth = 2,
            parentId = UUID.randomUUID()  // depth=2 means it has a parent
        )
        coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val params = buildParams(parentId = parentItemId.toString())
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertTrue(
            obj["success"]!!.jsonPrimitive.boolean,
            "Expected success for root at depth 3 (MAX_DEPTH), got: $result"
        )
        val data = obj["data"] as JsonObject
        assertEquals(3, data["root"]!!.jsonObject["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 4b. Depth cap violation: parentId at depth 3 → root becomes depth 4 → fail
    // ──────────────────────────────────────────────

    @Test
    fun `root at depth 4 exceeds MAX_DEPTH and fails`(): Unit = runBlocking {
        val parentItemId = UUID.randomUUID()
        val parentItem = WorkItem(
            id = parentItemId,
            title = "Very deep parent",
            depth = 3,
            parentId = UUID.randomUUID()
        )
        coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)

        val params = buildParams(parentId = parentItemId.toString())
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertFalse(obj["success"]!!.jsonPrimitive.boolean, "Expected failure due to depth cap exceeded")
        val errorMsg = obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(
            errorMsg.contains("depth") || errorMsg.contains("maximum") || errorMsg.contains("exceeds"),
            "Expected depth-related error but got: $errorMsg"
        )
    }

    // ──────────────────────────────────────────────
    // 5. Invalid dep ref → validation error returned before execute()
    // ──────────────────────────────────────────────

    @Test
    fun `invalid dep ref fails with error response`(): Unit = runBlocking {
        // The tool validates dep refs before calling the executor, so execute() is never called

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child One"))
        }
        val deps = buildJsonArray {
            // "nonexistent" is not a valid ref
            add(makeDepSpec("root", "nonexistent"))
        }
        val params = buildParams(children = children, deps = deps)
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertFalse(obj["success"]!!.jsonPrimitive.boolean, "Expected failure for invalid ref")
        val errorMsg = obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(
            errorMsg.contains("nonexistent") || errorMsg.contains("ref"),
            "Expected ref-related error but got: $errorMsg"
        )
    }

    // ──────────────────────────────────────────────
    // 6. validateParams: missing root → exception
    // ──────────────────────────────────────────────

    @Test
    fun `missing root parameter throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject { })
        }
    }

    // ──────────────────────────────────────────────
    // 7. validateParams: missing root.title → exception
    // ──────────────────────────────────────────────

    @Test
    fun `missing root title throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("root", buildJsonObject {
                    put("description", JsonPrimitive("No title here"))
                })
            })
        }
    }

    // ──────────────────────────────────────────────
    // 8. validateParams: dep missing 'from' → exception
    // ──────────────────────────────────────────────

    @Test
    fun `dep missing from field throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                put("deps", buildJsonArray {
                    add(buildJsonObject {
                        put("to", JsonPrimitive("c1"))
                        // 'from' is missing
                    })
                })
            })
        }
    }

    // ──────────────────────────────────────────────
    // 9. validateParams: child missing ref → exception
    // ──────────────────────────────────────────────

    @Test
    fun `child missing ref throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(buildJsonObject {
                put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                put("children", buildJsonArray {
                    add(buildJsonObject {
                        put("title", JsonPrimitive("Child without ref"))
                        // 'ref' is missing
                    })
                })
            })
        }
    }

    // ──────────────────────────────────────────────
    // 10. Root-only tree (no children) succeeds
    // ──────────────────────────────────────────────

    @Test
    fun `root-only tree succeeds with empty children and deps arrays`(): Unit = runBlocking {
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val params = buildParams()
        val result = tool.execute(params, context)

        val data = extractData(result)
        assertEquals("Root Task", data["root"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals(0, data["children"]!!.jsonArray.size)
        assertEquals(0, data["dependencies"]!!.jsonArray.size)
        assertEquals(0, data["notes"]!!.jsonArray.size)
    }

    // ──────────────────────────────────────────────
    // 11. Parent not found returns error response
    // ──────────────────────────────────────────────

    @Test
    fun `nonexistent parentId returns error response`(): Unit = runBlocking {
        val missingId = UUID.randomUUID()
        coEvery { workItemRepo.getById(missingId) } returns Result.Error(
            RepositoryError.NotFound(missingId, "WorkItem not found")
        )

        val params = buildParams(parentId = missingId.toString())
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertFalse(obj["success"]!!.jsonPrimitive.boolean)
        val errorMsg = obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(errorMsg.contains("not found"), "Expected not-found error but got: $errorMsg")
    }

    // ──────────────────────────────────────────────
    // 12a. Depth boundary: root at depth 1, children at depth 2 → success
    // ──────────────────────────────────────────────

    @Test
    fun `root at depth 1 with children at depth 2 succeeds`(): Unit = runBlocking {
        val parentItemId = UUID.randomUUID()
        val parentItem = WorkItem(
            id = parentItemId,
            title = "Root-level parent",
            depth = 0
        )
        coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child at depth 2"))
        }
        val params = buildParams(parentId = parentItemId.toString(), children = children)
        val result = tool.execute(params, context)

        val data = extractData(result)
        assertEquals(1, data["root"]!!.jsonObject["depth"]!!.jsonPrimitive.int)
        val childrenArr = data["children"]!!.jsonArray
        assertEquals(1, childrenArr.size)
        assertEquals(2, childrenArr[0].jsonObject["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 12b. Depth boundary: root at depth 2, children at depth 3 (MAX_DEPTH) → success
    // ──────────────────────────────────────────────

    @Test
    fun `root at depth 2 with children at depth 3 (MAX_DEPTH) succeeds`(): Unit = runBlocking {
        val parentItemId = UUID.randomUUID()
        val parentItem = WorkItem(
            id = parentItemId,
            title = "Depth-2 parent",
            depth = 1,
            parentId = UUID.randomUUID()
        )
        coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            echoResult(input)
        }

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child at depth 3"))
        }
        val params = buildParams(parentId = parentItemId.toString(), children = children)
        val result = tool.execute(params, context)

        val data = extractData(result)
        assertEquals(2, data["root"]!!.jsonObject["depth"]!!.jsonPrimitive.int)
        val childrenArr = data["children"]!!.jsonArray
        assertEquals(1, childrenArr.size)
        assertEquals(3, childrenArr[0].jsonObject["depth"]!!.jsonPrimitive.int)
    }

    // ──────────────────────────────────────────────
    // 12c. Depth boundary: root at depth 3 with children → children would be depth 4 → fail
    // ──────────────────────────────────────────────

    @Test
    fun `children at depth 4 exceeds MAX_DEPTH and fails`(): Unit = runBlocking {
        val parentItemId = UUID.randomUUID()
        val parentItem = WorkItem(
            id = parentItemId,
            title = "Depth-3 parent",
            depth = 2,
            parentId = UUID.randomUUID()
        )
        coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)

        val children = buildJsonArray {
            add(makeChildSpec("c1", "Child that would be depth 4"))
        }
        val params = buildParams(parentId = parentItemId.toString(), children = children)
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertFalse(
            obj["success"]!!.jsonPrimitive.boolean,
            "Expected failure when children would exceed depth cap"
        )
        val errorMsg = obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        assertTrue(
            errorMsg.contains("depth") || errorMsg.contains("maximum") || errorMsg.contains("exceeds"),
            "Expected depth-related error but got: $errorMsg"
        )
    }

    // ──────────────────────────────────────────────
    // userSummary error detail tests
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary on error result includes error message detail not generic string`(): Unit = runBlocking {
        // Trigger a real error: parentId that does not exist
        val missingId = UUID.randomUUID()
        coEvery { workItemRepo.getById(missingId) } returns Result.Error(
            RepositoryError.NotFound(missingId, "WorkItem not found: $missingId")
        )

        val params = buildParams(parentId = missingId.toString())
        val result = tool.execute(params, context)

        // Confirm it is an error result
        val obj = result as JsonObject
        assertFalse(obj["success"]!!.jsonPrimitive.boolean)

        val summary = tool.userSummary(params, result, isError = true)
        // Must contain more than the bare generic message
        assertTrue(
            summary.contains("not found") || summary.contains("WorkItem"),
            "Expected error detail in summary but got: $summary"
        )
        assertFalse(
            summary == "create_work_tree failed",
            "userSummary should not be the bare generic message when error detail is available: $summary"
        )
    }

    @Test
    fun `userSummary on invalid dep ref error includes cause detail`(): Unit = runBlocking {
        val children = buildJsonArray { add(makeChildSpec("c1", "Child One")) }
        val deps = buildJsonArray { add(makeDepSpec("root", "bogus_ref")) }
        val params = buildParams(children = children, deps = deps)
        val result = tool.execute(params, context)

        val obj = result as JsonObject
        assertFalse(obj["success"]!!.jsonPrimitive.boolean)

        val summary = tool.userSummary(params, result, isError = true)
        // Must contain ref-related error detail, not just the generic string
        assertTrue(
            summary.contains("bogus_ref") || summary.contains("ref") || summary.contains("not defined"),
            "Expected ref error detail in summary but got: $summary"
        )
        assertFalse(
            summary == "create_work_tree failed",
            "userSummary should include error cause, got: $summary"
        )
    }

    // ──────────────────────────────────────────────
    // 12. userSummary reflects root title and child count
    // ──────────────────────────────────────────────

    @Test
    fun `userSummary reflects root title and child count`(): Unit = runBlocking {
        coEvery { mockExecutor.execute(any()) } answers {
            val input = firstArg<WorkTreeInput>()
            val refToId = input.refToItem.mapValues { (_, item) -> item.id }
            val deps = input.deps.map { spec ->
                Dependency(
                    fromItemId = refToId[spec.fromRef]!!,
                    toItemId = refToId[spec.toRef]!!,
                    type = spec.type,
                    unblockAt = spec.unblockAt
                )
            }
            echoResult(input, deps)
        }

        val children = buildJsonArray { add(makeChildSpec("c1", "Child One")) }
        val deps = buildJsonArray { add(makeDepSpec("root", "c1")) }
        val params = buildParams(
            root = buildJsonObject { put("title", JsonPrimitive("Epic Feature")) },
            children = children,
            deps = deps
        )
        val result = tool.execute(params, context)
        val summary = tool.userSummary(params, result, isError = false)

        assertTrue(summary.contains("Epic Feature"), "Expected root title in summary: $summary")
        assertTrue(summary.contains("1"), "Expected child count in summary: $summary")
    }
}
