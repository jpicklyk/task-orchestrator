package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeExecutor
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeInput
import io.github.jpicklyk.mcptask.current.application.service.WorkTreeResult
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
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
        createNotes: Boolean? = null,
        notes: JsonArray? = null
    ): JsonObject =
        buildJsonObject {
            put("root", root)
            if (parentId != null) put("parentId", JsonPrimitive(parentId))
            if (children != null) put("children", children)
            if (deps != null) put("deps", deps)
            if (createNotes != null) put("createNotes", JsonPrimitive(createNotes))
            if (notes != null) put("notes", notes)
        }

    private fun extractData(result: JsonElement): JsonObject {
        val obj = result as JsonObject
        assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success=true, got: $result")
        return obj["data"] as JsonObject
    }

    private fun makeChildSpec(
        ref: String,
        title: String,
        tags: String? = null
    ): JsonObject =
        buildJsonObject {
            put("ref", JsonPrimitive(ref))
            put("title", JsonPrimitive(title))
            if (tags != null) put("tags", JsonPrimitive(tags))
        }

    private fun makeDepSpec(
        from: String,
        to: String,
        type: String = "BLOCKS"
    ): JsonObject =
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
    private fun echoResult(
        input: WorkTreeInput,
        deps: List<Dependency> = emptyList()
    ): WorkTreeResult {
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
    fun `basic tree creation returns distinct UUIDs for root and children`(): Unit =
        runBlocking {
            // Mirror the input back as the result
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val children =
                buildJsonArray {
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
    fun `dependency wiring preserves fromRef and toRef in response`(): Unit =
        runBlocking {
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                val refToId = input.refToItem.mapValues { (_, item) -> item.id }
                // Build deps from the spec input
                val deps =
                    input.deps.map { spec ->
                        Dependency(
                            fromItemId = refToId[spec.fromRef]!!,
                            toItemId = refToId[spec.toRef]!!,
                            type = spec.type,
                            unblockAt = spec.unblockAt
                        )
                    }
                echoResult(input, deps)
            }

            val children =
                buildJsonArray {
                    add(makeChildSpec("c1", "Child One"))
                    add(makeChildSpec("c2", "Child Two"))
                }
            val deps =
                buildJsonArray {
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
    fun `createNotes=true creates blank notes for each item matching schema`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true)
                )
            val noteSchemaService =
                object : NoteSchemaService {
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

            val children =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive("c1"))
                            put("title", JsonPrimitive("Child with schema"))
                            put("tags", JsonPrimitive("feature-task"))
                        }
                    )
                }
            val rootSpec =
                buildJsonObject {
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
    fun `root at depth 3 (MAX_DEPTH) succeeds`(): Unit =
        runBlocking {
            val parentItemId = UUID.randomUUID()
            val parentItem =
                WorkItem(
                    id = parentItemId,
                    title = "Deep parent",
                    depth = 2,
                    parentId = UUID.randomUUID() // depth=2 means it has a parent
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
    fun `root at depth 4 exceeds MAX_DEPTH and fails`(): Unit =
        runBlocking {
            val parentItemId = UUID.randomUUID()
            val parentItem =
                WorkItem(
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
    fun `invalid dep ref fails with error response`(): Unit =
        runBlocking {
            // The tool validates dep refs before calling the executor, so execute() is never called

            val children =
                buildJsonArray {
                    add(makeChildSpec("c1", "Child One"))
                }
            val deps =
                buildJsonArray {
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
            tool.validateParams(
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("description", JsonPrimitive("No title here"))
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // 8. validateParams: dep missing 'from' → exception
    // ──────────────────────────────────────────────

    @Test
    fun `dep missing from field throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "deps",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("to", JsonPrimitive("c1"))
                                    // 'from' is missing
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // 9. validateParams: child missing ref → exception
    // ──────────────────────────────────────────────

    @Test
    fun `child missing ref throws ToolValidationException`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("title", JsonPrimitive("Child without ref"))
                                    // 'ref' is missing
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // 10. Root-only tree (no children) succeeds
    // ──────────────────────────────────────────────

    @Test
    fun `root-only tree succeeds with empty children and deps arrays`(): Unit =
        runBlocking {
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
    fun `nonexistent parentId returns error response`(): Unit =
        runBlocking {
            val missingId = UUID.randomUUID()
            coEvery { workItemRepo.getById(missingId) } returns
                Result.Error(
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
    fun `root at depth 1 with children at depth 2 succeeds`(): Unit =
        runBlocking {
            val parentItemId = UUID.randomUUID()
            val parentItem =
                WorkItem(
                    id = parentItemId,
                    title = "Root-level parent",
                    depth = 0
                )
            coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val children =
                buildJsonArray {
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
    fun `root at depth 2 with children at depth 3 (MAX_DEPTH) succeeds`(): Unit =
        runBlocking {
            val parentItemId = UUID.randomUUID()
            val parentItem =
                WorkItem(
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

            val children =
                buildJsonArray {
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
    fun `children at depth 4 exceeds MAX_DEPTH and fails`(): Unit =
        runBlocking {
            val parentItemId = UUID.randomUUID()
            val parentItem =
                WorkItem(
                    id = parentItemId,
                    title = "Depth-3 parent",
                    depth = 2,
                    parentId = UUID.randomUUID()
                )
            coEvery { workItemRepo.getById(parentItemId) } returns Result.Success(parentItem)

            val children =
                buildJsonArray {
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
    fun `userSummary on error result includes error message detail not generic string`(): Unit =
        runBlocking {
            // Trigger a real error: parentId that does not exist
            val missingId = UUID.randomUUID()
            coEvery { workItemRepo.getById(missingId) } returns
                Result.Error(
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
    fun `userSummary on invalid dep ref error includes cause detail`(): Unit =
        runBlocking {
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
    fun `userSummary reflects root title and child count`(): Unit =
        runBlocking {
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                val refToId = input.refToItem.mapValues { (_, item) -> item.id }
                val deps =
                    input.deps.map { spec ->
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
            val params =
                buildParams(
                    root = buildJsonObject { put("title", JsonPrimitive("Epic Feature")) },
                    children = children,
                    deps = deps
                )
            val result = tool.execute(params, context)
            val summary = tool.userSummary(params, result, isError = false)

            assertTrue(summary.contains("Epic Feature"), "Expected root title in summary: $summary")
            assertTrue(summary.contains("1"), "Expected child count in summary: $summary")
        }

    // ──────────────────────────────────────────────
    // expectedNotes: root with schema tag includes expectedNotes
    // ──────────────────────────────────────────────

    @Test
    fun `root with schema tag includes expectedNotes`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "acceptance-criteria",
                        role = Role.QUEUE,
                        required = true,
                        description = "Criteria for acceptance",
                        guidance = "List each criterion as a bullet"
                    ),
                    NoteSchemaEntry(
                        key = "implementation-notes",
                        role = Role.WORK,
                        required = false,
                        description = "Notes on implementation approach"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("feature-task")) schemaEntries else null
                }

            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Feature Root"))
                    put("tags", JsonPrimitive("feature-task"))
                }
            val params = buildParams(root = rootSpec)
            val result = tool.execute(params, contextWithSchema)

            val data = extractData(result)
            val rootJson = data["root"]!!.jsonObject
            assertTrue(rootJson.containsKey("expectedNotes"), "Root JSON should contain 'expectedNotes' key")
            assertTrue(rootJson.containsKey("schemaMatch"), "Root JSON should contain 'schemaMatch' key")
            assertTrue(rootJson["schemaMatch"]!!.jsonPrimitive.boolean, "schemaMatch should be true when schema matched")

            val expectedNotes = rootJson["expectedNotes"]!!.jsonArray
            assertEquals(2, expectedNotes.size, "Expected 2 schema entries in expectedNotes")

            val first = expectedNotes[0].jsonObject
            assertEquals("acceptance-criteria", first["key"]!!.jsonPrimitive.content)
            assertEquals("queue", first["role"]!!.jsonPrimitive.content)
            assertTrue(first["required"]!!.jsonPrimitive.boolean)
            assertEquals("Criteria for acceptance", first["description"]!!.jsonPrimitive.content)
            assertEquals("List each criterion as a bullet", first["guidance"]!!.jsonPrimitive.content)
            assertFalse(first["exists"]!!.jsonPrimitive.boolean)

            val second = expectedNotes[1].jsonObject
            assertEquals("implementation-notes", second["key"]!!.jsonPrimitive.content)
            assertEquals("work", second["role"]!!.jsonPrimitive.content)
            assertFalse(second["required"]!!.jsonPrimitive.boolean)
            assertEquals("Notes on implementation approach", second["description"]!!.jsonPrimitive.content)
            assertFalse(second.containsKey("guidance"), "guidance should be absent when null")
            assertFalse(second["exists"]!!.jsonPrimitive.boolean)
        }

    // ──────────────────────────────────────────────
    // expectedNotes: child with schema tag includes expectedNotes
    // ──────────────────────────────────────────────

    @Test
    fun `child with schema tag includes expectedNotes`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(
                        key = "test-plan",
                        role = Role.REVIEW,
                        required = true,
                        description = "Test plan for this child"
                    )
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("subtask")) schemaEntries else null
                }

            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            // Root has no schema tag; child has "subtask" tag
            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Parent Task"))
                }
            val children =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive("c1"))
                            put("title", JsonPrimitive("Child with subtask tag"))
                            put("tags", JsonPrimitive("subtask"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, children = children)
            val result = tool.execute(params, contextWithSchema)

            val data = extractData(result)

            // Root should have schemaMatch=false and empty expectedNotes (no schema match)
            val rootJson = data["root"]!!.jsonObject
            assertTrue(rootJson.containsKey("schemaMatch"), "Root should always contain 'schemaMatch' key")
            assertFalse(rootJson["schemaMatch"]!!.jsonPrimitive.boolean, "Root schemaMatch should be false when no schema matches")
            assertTrue(rootJson.containsKey("expectedNotes"), "Root should always contain 'expectedNotes' key")
            assertEquals(0, rootJson["expectedNotes"]!!.jsonArray.size, "Root expectedNotes should be empty when no schema matches")

            // Child should have schemaMatch=true and populated expectedNotes
            val childrenArr = data["children"]!!.jsonArray
            assertEquals(1, childrenArr.size)
            val childJson = childrenArr[0].jsonObject
            assertTrue(childJson.containsKey("schemaMatch"), "Child JSON should contain 'schemaMatch' key")
            assertTrue(childJson["schemaMatch"]!!.jsonPrimitive.boolean, "Child schemaMatch should be true when schema matched")
            assertTrue(childJson.containsKey("expectedNotes"), "Child JSON should contain 'expectedNotes' key")

            val expectedNotes = childJson["expectedNotes"]!!.jsonArray
            assertEquals(1, expectedNotes.size)
            val note = expectedNotes[0].jsonObject
            assertEquals("test-plan", note["key"]!!.jsonPrimitive.content)
            assertEquals("review", note["role"]!!.jsonPrimitive.content)
            assertTrue(note["required"]!!.jsonPrimitive.boolean)
            assertEquals("Test plan for this child", note["description"]!!.jsonPrimitive.content)
            assertFalse(note["exists"]!!.jsonPrimitive.boolean)
        }

    // ──────────────────────────────────────────────
    // expectedNotes: item without matching schema omits expectedNotes
    // ──────────────────────────────────────────────

    @Test
    fun `item without matching schema omits expectedNotes`(): Unit =
        runBlocking {
            // NoteSchemaService that never matches any tag
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? = null
                }

            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Untagged Root"))
                    put("tags", JsonPrimitive("some-tag"))
                }
            val children =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive("c1"))
                            put("title", JsonPrimitive("Untagged Child"))
                            put("tags", JsonPrimitive("another-tag"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, children = children)
            val result = tool.execute(params, contextWithSchema)

            val data = extractData(result)

            // Both root and child should have schemaMatch=false and empty expectedNotes when schema returns null
            val rootJson = data["root"]!!.jsonObject
            assertTrue(rootJson.containsKey("schemaMatch"), "Root should always contain 'schemaMatch' key")
            assertFalse(rootJson["schemaMatch"]!!.jsonPrimitive.boolean, "Root schemaMatch should be false when schema returns null")
            assertTrue(rootJson.containsKey("expectedNotes"), "Root should always contain 'expectedNotes' key")
            assertEquals(0, rootJson["expectedNotes"]!!.jsonArray.size, "Root expectedNotes should be empty when schema returns null")

            val childrenArr = data["children"]!!.jsonArray
            assertEquals(1, childrenArr.size)
            val childJson = childrenArr[0].jsonObject
            assertTrue(childJson.containsKey("schemaMatch"), "Child should always contain 'schemaMatch' key")
            assertFalse(childJson["schemaMatch"]!!.jsonPrimitive.boolean, "Child schemaMatch should be false when schema returns null")
            assertTrue(childJson.containsKey("expectedNotes"), "Child should always contain 'expectedNotes' key")
            assertEquals(0, childJson["expectedNotes"]!!.jsonArray.size, "Child expectedNotes should be empty when schema returns null")
        }

    // ──────────────────────────────────────────────
    // Gap M6: schema returns emptyList() (non-null) — expectedNotes omitted
    // ──────────────────────────────────────────────

    @Test
    fun `root with schema returning empty list includes schemaMatch true and empty expectedNotes`(): Unit =
        runBlocking {
            // When getSchemaForTags returns emptyList() (non-null, zero entries),
            // schemaMatch is true and expectedNotes is an empty array.
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("empty-schema")) emptyList() else null
                }

            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Root With Empty Schema"))
                    put("tags", JsonPrimitive("empty-schema"))
                }
            val children =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive("c1"))
                            put("title", JsonPrimitive("Child With Empty Schema"))
                            put("tags", JsonPrimitive("empty-schema"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, children = children)
            val result = tool.execute(params, contextWithSchema)

            val data = extractData(result)

            val rootJson = data["root"]!!.jsonObject
            assertTrue(rootJson["schemaMatch"]!!.jsonPrimitive.boolean, "schemaMatch should be true for non-null schema")
            assertEquals(0, rootJson["expectedNotes"]!!.jsonArray.size, "expectedNotes should be empty array for zero-entry schema")

            val childrenArr = data["children"]!!.jsonArray
            assertEquals(1, childrenArr.size)
            val childJson = childrenArr[0].jsonObject
            assertTrue(childJson["schemaMatch"]!!.jsonPrimitive.boolean, "Child schemaMatch should be true")
            assertEquals(0, childJson["expectedNotes"]!!.jsonArray.size, "Child expectedNotes should be empty array")
        }

    // ──────────────────────────────────────────────
    // Gap M7: exists=false explicitly asserted in expectedNotes entries
    // ──────────────────────────────────────────────

    @Test
    fun `root and child expectedNotes entries all have exists=false`(): Unit =
        runBlocking {
            val rootSchemaEntries =
                listOf(
                    NoteSchemaEntry(key = "spec", role = Role.QUEUE, required = true, description = "Specification")
                )
            val childSchemaEntries =
                listOf(
                    NoteSchemaEntry(key = "impl-notes", role = Role.WORK, required = false, description = "Impl notes")
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        when {
                            tags.contains("root-tag") -> rootSchemaEntries
                            tags.contains("child-tag") -> childSchemaEntries
                            else -> null
                        }
                }

            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Root"))
                    put("tags", JsonPrimitive("root-tag"))
                }
            val children =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("ref", JsonPrimitive("c1"))
                            put("title", JsonPrimitive("Child"))
                            put("tags", JsonPrimitive("child-tag"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, children = children)
            val result = tool.execute(params, contextWithSchema)

            val data = extractData(result)

            // Root: verify exists=false on each expectedNotes entry
            val rootJson = data["root"]!!.jsonObject
            assertTrue(rootJson.containsKey("expectedNotes"), "Root should have expectedNotes")
            val rootNotes = rootJson["expectedNotes"]!!.jsonArray
            assertEquals(1, rootNotes.size)
            assertFalse(
                rootNotes[0].jsonObject["exists"]!!.jsonPrimitive.boolean,
                "Root expectedNotes[0].exists should be false (note has not been written yet)"
            )

            // Child: verify exists=false on each expectedNotes entry
            val childrenArr = data["children"]!!.jsonArray
            assertEquals(1, childrenArr.size)
            val childJson = childrenArr[0].jsonObject
            assertTrue(childJson.containsKey("expectedNotes"), "Child should have expectedNotes")
            val childNotes = childJson["expectedNotes"]!!.jsonArray
            assertEquals(1, childNotes.size)
            assertFalse(
                childNotes[0].jsonObject["exists"]!!.jsonPrimitive.boolean,
                "Child expectedNotes[0].exists should be false (note has not been written yet)"
            )
        }

    // ──────────────────────────────────────────────
    // type field propagation
    // ──────────────────────────────────────────────

    @Test
    fun `type field on root item is preserved in created WorkItem`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Feature Root"))
                    put("type", JsonPrimitive("feature-task"))
                }
            val params = buildParams(root = rootSpec)
            val result = tool.execute(params, context)

            extractData(result) // assert success

            val rootItem = capturedInput!!.items.first()
            assertEquals("feature-task", rootItem.type, "Root item should carry the type field")
        }

    @Test
    fun `type field on child item is preserved in created WorkItem`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val childSpec =
                buildJsonObject {
                    put("ref", JsonPrimitive("c1"))
                    put("title", JsonPrimitive("Task Child"))
                    put("type", JsonPrimitive("sub-task"))
                }
            val params = buildParams(children = JsonArray(listOf(childSpec)))
            val result = tool.execute(params, context)

            extractData(result) // assert success

            val childItem = capturedInput!!.items.drop(1).first()
            assertEquals("sub-task", childItem.type, "Child item should carry the type field")
        }

    @Test
    fun `item without type field has null type`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val params = buildParams()
            val result = tool.execute(params, context)

            extractData(result) // assert success

            val rootItem = capturedInput!!.items.first()
            assertNull(rootItem.type, "Root item without type should have null type")
        }

    @Test
    fun `traits on root item stores traits in properties JSON`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Feature Root"))
                    put("traits", JsonPrimitive("needs-security-review,needs-perf-review"))
                }
            val params = buildParams(root = rootSpec)
            val result = tool.execute(params, context)

            extractData(result) // assert success

            val rootItem = capturedInput!!.items.first()
            assertNotNull(rootItem.properties, "Root item with traits should have non-null properties")
            val traits =
                io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
                    .extractTraits(rootItem.properties)
            assertEquals(listOf("needs-security-review", "needs-perf-review"), traits)
        }

    @Test
    fun `traits on child item stores traits in properties JSON`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val childSpec =
                buildJsonObject {
                    put("ref", JsonPrimitive("c1"))
                    put("title", JsonPrimitive("Child task"))
                    put("traits", JsonPrimitive("needs-perf-review"))
                }
            val params = buildParams(children = JsonArray(listOf(childSpec)))
            val result = tool.execute(params, context)

            extractData(result)

            val childItem = capturedInput!!.items.find { it.depth == 1 }
            assertNotNull(childItem, "Should have a child item")
            assertNotNull(childItem.properties, "Child with traits should have non-null properties")
            val traits =
                io.github.jpicklyk.mcptask.current.application.tools.PropertiesHelper
                    .extractTraits(childItem.properties)
            assertEquals(listOf("needs-perf-review"), traits)
        }

    // ──────────────────────────────────────────────
    // Notes parameter: explicit notes persist with bodies
    // ──────────────────────────────────────────────

    @Test
    fun `explicit notes persist with bodies`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val noteEntry =
                buildJsonObject {
                    put("itemRef", JsonPrimitive("root"))
                    put("key", JsonPrimitive("foo"))
                    put("role", JsonPrimitive("queue"))
                    put("body", JsonPrimitive("hello"))
                }
            val params = buildParams(notes = JsonArray(listOf(noteEntry)))
            val result = tool.execute(params, context)

            val data = extractData(result)
            val notesArr = data["notes"]!!.jsonArray
            assertEquals(1, notesArr.size)
            val noteJson = notesArr[0].jsonObject
            assertEquals("root", noteJson["itemRef"]!!.jsonPrimitive.content)
            assertEquals("foo", noteJson["key"]!!.jsonPrimitive.content)
            assertEquals("queue", noteJson["role"]!!.jsonPrimitive.content)

            // Verify the captured input has the Note with correct body
            val capturedNote = capturedInput!!.notes.first()
            assertEquals("hello", capturedNote.body, "Note body should persist verbatim")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: multiple items with notes targeting each
    // ──────────────────────────────────────────────

    @Test
    fun `multiple items with notes targeting each have correct itemId binding`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val children =
                buildJsonArray {
                    add(makeChildSpec("c1", "Child One"))
                    add(makeChildSpec("c2", "Child Two"))
                }
            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("root-note"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("root body"))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("c1"))
                            put("key", JsonPrimitive("c1-note"))
                            put("role", JsonPrimitive("work"))
                            put("body", JsonPrimitive("c1 body"))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("c2"))
                            put("key", JsonPrimitive("c2-note"))
                            put("role", JsonPrimitive("review"))
                            put("body", JsonPrimitive("c2 body"))
                        }
                    )
                }
            val params = buildParams(children = children, notes = notesArr)
            val result = tool.execute(params, context)

            extractData(result)

            val input = capturedInput!!
            assertEquals(3, input.notes.size, "Expected 3 notes in captured input")

            val rootItem = input.items.first()
            val c1Item = input.items[1]
            val c2Item = input.items[2]

            val rootNote = input.notes.find { it.key == "root-note" }!!
            val c1Note = input.notes.find { it.key == "c1-note" }!!
            val c2Note = input.notes.find { it.key == "c2-note" }!!

            assertEquals(rootItem.id, rootNote.itemId, "root-note should be bound to root item")
            assertEquals(c1Item.id, c1Note.itemId, "c1-note should be bound to c1 item")
            assertEquals(c2Item.id, c2Note.itemId, "c2-note should be bound to c2 item")

            assertEquals("root body", rootNote.body)
            assertEquals("c1 body", c1Note.body)
            assertEquals("c2 body", c2Note.body)
        }

    // ──────────────────────────────────────────────
    // Notes parameter: merge with createNotes=true
    // ──────────────────────────────────────────────

    @Test
    fun `merge with createNotes=true - explicit notes keep bodies, schema gaps get blank, off-schema explicit kept`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true),
                    NoteSchemaEntry(key = "implementation-notes", role = Role.WORK, required = false)
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("feature-task")) schemaEntries else null
                }
            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Feature Root"))
                    put("tags", JsonPrimitive("feature-task"))
                }
            // Provide explicit note for "acceptance-criteria" (schema key) with a non-empty body,
            // and an off-schema key "custom-note". "implementation-notes" is NOT provided explicitly.
            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("acceptance-criteria"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("AC: must pass all tests"))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("custom-note"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("off-schema body"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, createNotes = true, notes = notesArr)
            val result = tool.execute(params, contextWithSchema)

            extractData(result)

            val input = capturedInput!!
            // Should have: acceptance-criteria (explicit), custom-note (off-schema explicit), implementation-notes (blank from schema)
            assertEquals(3, input.notes.size, "Expected 3 notes total")

            val acNote = input.notes.find { it.key == "acceptance-criteria" }!!
            assertEquals("AC: must pass all tests", acNote.body, "Explicit note body should be preserved")

            val customNote = input.notes.find { it.key == "custom-note" }!!
            assertEquals("off-schema body", customNote.body, "Off-schema explicit note should be kept with its body")

            val implNote = input.notes.find { it.key == "implementation-notes" }!!
            assertEquals("", implNote.body, "Schema gap note added by createNotes should have empty body")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: invalid itemRef → validation error
    // ──────────────────────────────────────────────

    @Test
    fun `invalid itemRef in notes returns validation error listing valid refs`(): Unit =
        runBlocking {
            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("nonexistent"))
                            put("key", JsonPrimitive("k"))
                            put("role", JsonPrimitive("queue"))
                        }
                    )
                }
            val params = buildParams(notes = notesArr)
            val result = tool.execute(params, context)

            val obj = result as JsonObject
            assertFalse(obj["success"]!!.jsonPrimitive.boolean, "Expected failure for invalid itemRef")
            val errorMsg = obj["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            assertTrue(
                errorMsg.contains("nonexistent"),
                "Error message should mention invalid ref: $errorMsg"
            )
            assertTrue(
                errorMsg.contains("root"),
                "Error message should list valid refs: $errorMsg"
            )
        }

    // ──────────────────────────────────────────────
    // Notes parameter: invalid role → validateParams exception
    // ──────────────────────────────────────────────

    @Test
    fun `invalid role in notes throws ToolValidationException at validateParams`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("terminal"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // Notes parameter: missing key → validateParams exception
    // ──────────────────────────────────────────────

    @Test
    fun `missing key in notes throws ToolValidationException at validateParams`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("role", JsonPrimitive("queue"))
                                    // key is missing
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // Notes parameter: missing itemRef → validateParams exception
    // ──────────────────────────────────────────────

    @Test
    fun `missing itemRef in notes throws ToolValidationException at validateParams`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("queue"))
                                    // itemRef is missing
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // Notes parameter: body omitted defaults to empty string
    // ──────────────────────────────────────────────

    @Test
    fun `body omitted in notes defaults to empty string`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("no-body-key"))
                            put("role", JsonPrimitive("queue"))
                            // body is intentionally omitted
                        }
                    )
                }
            val params = buildParams(notes = notesArr)
            val result = tool.execute(params, context)

            extractData(result)

            val note = capturedInput!!.notes.first()
            assertEquals("", note.body, "Omitted body should default to empty string")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: duplicate (itemRef, key) — last wins
    // ──────────────────────────────────────────────

    @Test
    fun `duplicate itemRef-key pair - last note wins`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("dup-key"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("first body"))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("dup-key"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("last body"))
                        }
                    )
                }
            val params = buildParams(notes = notesArr)
            val result = tool.execute(params, context)

            extractData(result)

            val notes = capturedInput!!.notes
            assertEquals(1, notes.size, "Duplicate (itemRef, key) should collapse to one note")
            assertEquals("last body", notes[0].body, "Last entry should win")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: empty notes array == omitted
    // ──────────────────────────────────────────────

    @Test
    fun `empty notes array results in no notes created`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val params = buildParams(notes = JsonArray(emptyList()))
            val result = tool.execute(params, context)

            extractData(result)

            assertEquals(0, capturedInput!!.notes.size, "Empty notes array should result in no notes")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: numeric body primitive rejected
    // ──────────────────────────────────────────────

    @Test
    fun `numeric body primitive throws ToolValidationException at validateParams`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive(42))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // Notes parameter: boolean body primitive rejected
    // ──────────────────────────────────────────────

    @Test
    fun `boolean body primitive throws ToolValidationException at validateParams`() {
        assertFailsWith<ToolValidationException> {
            tool.validateParams(
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put(
                        "notes",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("itemRef", JsonPrimitive("root"))
                                    put("key", JsonPrimitive("k"))
                                    put("role", JsonPrimitive("queue"))
                                    put("body", JsonPrimitive(true))
                                }
                            )
                        }
                    )
                }
            )
        }
    }

    // ──────────────────────────────────────────────
    // Notes parameter: explicit JsonNull body accepted (treated as empty)
    //
    // The validator allows null body (alongside omitted body); both default to "".
    // ──────────────────────────────────────────────

    @Test
    fun `explicit null body in notes is accepted and treated as empty`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("null-body-key"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonNull)
                        }
                    )
                }
            val params = buildParams(notes = notesArr)
            val result = tool.execute(params, context)

            extractData(result)

            val note = capturedInput!!.notes.first()
            assertEquals("", note.body, "Explicit null body should default to empty string")
        }

    // ──────────────────────────────────────────────
    // Notes parameter: explicit note with same key but different role
    // suppresses the schema-required entry
    //
    // Documents the dedup-by-(ref, key) contract: explicit notes always win,
    // even if their role differs from the schema declaration. This matches the
    // database unique constraint on (itemId, key) — only one note per key can
    // exist for an item, regardless of role. Callers are responsible for
    // matching schema roles when they intend to satisfy a gate; otherwise the
    // gate-required note will be unfilled at the expected role and gate
    // enforcement will reject the transition later.
    // ──────────────────────────────────────────────

    @Test
    fun `explicit note with same key but different role overrides schema entry`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "acceptance-criteria", role = Role.QUEUE, required = true)
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("feature-task")) schemaEntries else null
                }
            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val rootSpec =
                buildJsonObject {
                    put("title", JsonPrimitive("Feature Root"))
                    put("tags", JsonPrimitive("feature-task"))
                }
            // Schema declares acceptance-criteria at role=queue, but caller submits
            // it at role=work. Dedup-by-(ref, key) suppresses the schema entry.
            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("acceptance-criteria"))
                            put("role", JsonPrimitive("work"))
                            put("body", JsonPrimitive("explicit body at wrong role"))
                        }
                    )
                }
            val params = buildParams(root = rootSpec, createNotes = true, notes = notesArr)
            val result = tool.execute(params, contextWithSchema)

            extractData(result)

            val input = capturedInput!!
            assertEquals(
                1,
                input.notes.size,
                "Schema entry should be suppressed by explicit (ref, key) match — only the explicit note remains"
            )

            val note = input.notes.first()
            assertEquals("acceptance-criteria", note.key)
            assertEquals(
                "work",
                note.role,
                "Caller-supplied role wins over schema role on (ref, key) collision"
            )
            assertEquals("explicit body at wrong role", note.body)
        }

    // ──────────────────────────────────────────────
    // Actor attribution: top-level actor propagates to every explicit note
    //
    // The tool already parses `actor` for idempotency. Those parsed credentials
    // (claim + verification) MUST also flow through to the actorClaim/verification
    // fields on every persisted Note so the audit trail records who wrote each note.
    // ──────────────────────────────────────────────

    @Test
    fun `top-level actor propagates to explicit notes as actorClaim and verification`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val actorObj =
                buildJsonObject {
                    put("id", JsonPrimitive("orchestrator-test"))
                    put("kind", JsonPrimitive("orchestrator"))
                }
            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("k1"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("body1"))
                        }
                    )
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("k2"))
                            put("role", JsonPrimitive("work"))
                            put("body", JsonPrimitive("body2"))
                        }
                    )
                }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put("notes", notesArr)
                    put("actor", actorObj)
                }
            val result = tool.execute(params, context)

            val obj = result as JsonObject
            assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val input = capturedInput!!
            assertEquals(2, input.notes.size, "Expected both notes captured")
            for (note in input.notes) {
                assertNotNull(note.actorClaim, "Note '${note.key}' should have actorClaim attached")
                assertEquals("orchestrator-test", note.actorClaim!!.id)
                assertEquals(
                    io.github.jpicklyk.mcptask.current.domain.model.ActorKind.ORCHESTRATOR,
                    note.actorClaim!!.kind
                )
                assertNotNull(note.verification, "Note '${note.key}' should have verification attached")
                // NoOpActorVerifier returns status=UNCHECKED, verifier="noop"
                assertEquals("noop", note.verification!!.verifier)
            }
        }

    // ──────────────────────────────────────────────
    // Actor attribution: createNotes=true blanks also receive attribution
    //
    // Schema-required notes filled by createNotes=true are written by the same
    // caller as the explicit notes — they should carry the same actor metadata.
    // ──────────────────────────────────────────────

    @Test
    fun `actor propagates to createNotes=true schema blanks`(): Unit =
        runBlocking {
            val schemaEntries =
                listOf(
                    NoteSchemaEntry(key = "auto-blank-note", role = Role.QUEUE, required = true)
                )
            val noteSchemaService =
                object : NoteSchemaService {
                    override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                        if (tags.contains("feature-task")) schemaEntries else null
                }
            val provider2 = mockk<RepositoryProvider>()
            val mockExecutor2 = mockk<WorkTreeExecutor>()
            every { provider2.workItemRepository() } returns workItemRepo
            every { provider2.dependencyRepository() } returns mockk()
            every { provider2.noteRepository() } returns mockk()
            every { provider2.roleTransitionRepository() } returns mockk()
            every { provider2.database() } returns null
            every { provider2.workTreeExecutor() } returns mockExecutor2
            val contextWithSchema = ToolExecutionContext(provider2, noteSchemaService)

            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor2.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("Feature Root"))
                            put("tags", JsonPrimitive("feature-task"))
                        }
                    )
                    put("createNotes", JsonPrimitive(true))
                    put(
                        "actor",
                        buildJsonObject {
                            put("id", JsonPrimitive("subagent-42"))
                            put("kind", JsonPrimitive("subagent"))
                        }
                    )
                }
            val result = tool.execute(params, contextWithSchema)

            val obj = result as JsonObject
            assertTrue(obj["success"]!!.jsonPrimitive.boolean, "Expected success; got: $result")

            val input = capturedInput!!
            assertEquals(1, input.notes.size, "Expected exactly one schema-blank note")
            val note = input.notes.first()
            assertEquals("auto-blank-note", note.key)
            assertEquals("", note.body, "Schema blank should have empty body")
            assertNotNull(note.actorClaim, "Schema blank must also carry actor attribution")
            assertEquals("subagent-42", note.actorClaim!!.id)
            assertEquals(
                io.github.jpicklyk.mcptask.current.domain.model.ActorKind.SUBAGENT,
                note.actorClaim!!.kind
            )
        }

    // ──────────────────────────────────────────────
    // Actor attribution: no actor object → notes have null actorClaim/verification
    // (preserves backward compatibility for callers that don't pass actor)
    // ──────────────────────────────────────────────

    @Test
    fun `notes without top-level actor have null actorClaim and verification`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("k"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("body"))
                        }
                    )
                }
            val params = buildParams(notes = notesArr) // no actor
            val result = tool.execute(params, context)

            val obj = result as JsonObject
            assertTrue(obj["success"]!!.jsonPrimitive.boolean)

            val note = capturedInput!!.notes.first()
            assertNull(note.actorClaim, "Without an actor block, actorClaim must remain null")
            assertNull(note.verification, "Without an actor block, verification must remain null")
        }

    // ──────────────────────────────────────────────
    // Actor attribution: invalid actor block → notes have null actorClaim
    // (matches existing behavior where invalid actor disables idempotency without
    // failing the call — attribution is silently dropped, the call still proceeds)
    // ──────────────────────────────────────────────

    @Test
    fun `notes with invalid actor block have null actorClaim and call still succeeds`(): Unit =
        runBlocking {
            var capturedInput: WorkTreeInput? = null
            coEvery { mockExecutor.execute(any()) } answers {
                val input = firstArg<WorkTreeInput>()
                capturedInput = input
                echoResult(input)
            }

            val notesArr =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("itemRef", JsonPrimitive("root"))
                            put("key", JsonPrimitive("k"))
                            put("role", JsonPrimitive("queue"))
                            put("body", JsonPrimitive("body"))
                        }
                    )
                }
            // Invalid actor: missing 'kind' field
            val invalidActor =
                buildJsonObject {
                    put("id", JsonPrimitive("some-id"))
                    // kind intentionally omitted → ActorParseResult.Invalid
                }
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("Root")) })
                    put("notes", notesArr)
                    put("actor", invalidActor)
                }
            val result = tool.execute(params, context)

            val obj = result as JsonObject
            assertTrue(
                obj["success"]!!.jsonPrimitive.boolean,
                "Invalid actor must not block the call; got: $result"
            )

            val note = capturedInput!!.notes.first()
            assertNull(note.actorClaim, "Invalid actor must not produce a partial actorClaim")
            assertNull(note.verification, "Invalid actor must not produce a partial verification")
        }
}
