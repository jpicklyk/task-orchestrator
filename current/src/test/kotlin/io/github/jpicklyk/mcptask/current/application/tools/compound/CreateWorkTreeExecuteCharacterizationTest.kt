package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.service.NoteSchemaService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.PlanDocumentStatus
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ChildPlacement
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent, characterization-first test authorship for item `fecf7035` (needs-test-author),
 * stream WT, decompose-complexity-hotspots wave: `CreateWorkTreeTool.execute` /
 * `executeCreateWorkTree` — S4 through S14 of the frozen `test-plan` note `042a127d` (queue phase,
 * read before this file was written).
 *
 * This wave is behavior-preserving. `executeCreateWorkTree` does NOT call `validateParams` (it
 * relies on pre-validated input), so every params object below is built already valid against
 * `CreateWorkTreeValidationCharacterizationTest`'s S1 rules; only the scenario's own execution-time
 * behavior is under test. Every scenario is EXISTING-SURFACE — red-proof is a plain revert of any
 * regressing hunk.
 *
 * Oracles: [339]/[343] fix intent, [D] supplied domain invariants (declarations §D), [AR]
 * `create_work_tree`'s response envelope (declarations §A), [C] exact strings frozen in the
 * item's `task-scope` planning-seat corrections (base commit dd26e9e2) — see declarations §E.
 * "Nothing persisted" is checked via `workItemRepository.findByFilters(limit = 500)` lacking the
 * call's attempted titles, per the frozen `test-plan` note.
 *
 * BLINDNESS: authored from `task-scope`/`test-plan` (queue-phase, frozen, `keys`-filtered
 * `query_notes`), the verbatim declarations block supplied in the dispatch prompt, and existing
 * conventions read from `src/test` (`CreateWorkTreeToolIntegrationTest.kt`,
 * `CreateWorkTreeParentPlacementInTxnTest.kt`, `CreateWorkTreeToolTest.kt`) for harness and
 * JSON field-shape conventions only. No `src/main` file, diff, or commit was read. The
 * `MutateOnFirstTransactionRepository` / `WorkItemRepoOverrideProvider` seam below is copied
 * verbatim, per the dispatch's declarations, from `CreateWorkTreeParentPlacementInTxnTest.kt:61-98`.
 */
class CreateWorkTreeExecuteCharacterizationTest {
    /** Copied verbatim from `CreateWorkTreeParentPlacementInTxnTest` (declarations §B). */
    private class MutateOnFirstTransactionRepository(
        private val delegate: WorkItemRepository,
        private val mutate: suspend (WorkItemRepository) -> Unit
    ) : WorkItemRepository by delegate {
        private var hasFired = false

        override suspend fun inTransaction(block: suspend () -> Unit) {
            delegate.inTransaction {
                if (!hasFired) {
                    hasFired = true
                    mutate(delegate)
                }
                block()
            }
        }

        override suspend fun resolveChildPlacement(parentId: UUID): Result<ChildPlacement> =
            when (val parent = getById(parentId)) {
                is Result.Success ->
                    Result.Success(
                        ChildPlacement(
                            parentId = parent.data.id,
                            depth = parent.data.depth + 1,
                            rootId = parent.data.rootId ?: parent.data.id
                        )
                    )
                is Result.Error -> Result.Error(parent.error)
            }
    }

    /** Copied verbatim from `CreateWorkTreeParentPlacementInTxnTest` (declarations §B). */
    private class WorkItemRepoOverrideProvider(
        private val delegate: RepositoryProvider,
        private val workItemRepo: WorkItemRepository
    ) : RepositoryProvider by delegate {
        override fun workItemRepository(): WorkItemRepository = workItemRepo
    }

    private lateinit var tool: CreateWorkTreeTool
    private lateinit var repositoryProvider: DefaultRepositoryProvider
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        val dbName = "create_work_tree_execute_characterization_${System.nanoTime()}"
        val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        val databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        repositoryProvider = DefaultRepositoryProvider(databaseManager)

        tool = CreateWorkTreeTool()
        context = ToolExecutionContext(repositoryProvider)
    }

    private fun contextWith(workItemRepo: WorkItemRepository) =
        ToolExecutionContext(WorkItemRepoOverrideProvider(repositoryProvider, workItemRepo))

    private fun childSpec(
        ref: String,
        title: String,
        tags: String? = null
    ) = buildJsonObject {
        put("ref", JsonPrimitive(ref))
        put("title", JsonPrimitive(title))
        if (tags != null) put("tags", JsonPrimitive(tags))
    }

    private fun depSpec(
        from: String,
        to: String,
        type: String = "BLOCKS",
        unblockAt: String? = null
    ) = buildJsonObject {
        put("from", JsonPrimitive(from))
        put("to", JsonPrimitive(to))
        put("type", JsonPrimitive(type))
        if (unblockAt != null) put("unblockAt", JsonPrimitive(unblockAt))
    }

    private suspend fun titlesInDb(): List<String> {
        val result = repositoryProvider.workItemRepository().findByFilters(limit = 500)
        assertTrue(result is Result.Success, "findByFilters should succeed")
        return (result as Result.Success).data.items.map { it.title }
    }

    private suspend fun create(item: WorkItem): WorkItem = (repositoryProvider.workItemRepository().create(item) as Result.Success).data

    private suspend fun stampSelfRoot(item: WorkItem): WorkItem =
        (repositoryProvider.workItemRepository().update(item.copy(rootId = item.id)) as Result.Success).data

    private fun errorOf(result: JsonElement): JsonObject = (result as JsonObject)["error"]!!.jsonObject

    // ─────────────────────────────────────────────────────────────────────
    // S4 — invalid dependency type is rejected; the hyphenated alias normalizes and is accepted
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 invalid dependency type is rejected with exact message and nothing persists`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S4 Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpec("c1", "C1"))
                            add(childSpec("c2", "C2"))
                        }
                    )
                    put("deps", buildJsonArray { add(depSpec("c1", "c2", type = "DEPENDS")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertEquals(
                "deps[0]: invalid type 'DEPENDS'. Valid: BLOCKS, IS_BLOCKED_BY, RELATES_TO",
                error["message"]!!.jsonPrimitive.content
            )
            assertFalse("S4 Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    @Test
    fun `probe S4 hyphenated dependency type is-blocked-by normalizes and is accepted`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S4 Probe Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpec("c1", "C1"))
                            add(childSpec("c2", "C2"))
                        }
                    )
                    put("deps", buildJsonArray { add(depSpec("c1", "c2", type = "is-blocked-by")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val depsArr = (result["data"] as JsonObject)["dependencies"] as JsonArray
            assertEquals(1, depsArr.size)
            assertEquals("IS_BLOCKED_BY", depsArr[0].jsonObject["type"]!!.jsonPrimitive.content)

            val c1Id =
                UUID.fromString(
                    ((result["data"] as JsonObject)["children"] as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content
                )
            val persisted = repositoryProvider.dependencyRepository().findByItemId(c1Id)
            assertEquals(1, persisted.size)
            assertEquals(DependencyType.IS_BLOCKED_BY, persisted[0].type)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S5 — dependency 'to' ref not defined
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 dependency to ref not defined is rejected with exact message and nothing persists`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S5 Root")) })
                    put("children", buildJsonArray { add(childSpec("c1", "C1")) })
                    put("deps", buildJsonArray { add(depSpec("root", "ghost")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertEquals(
                "deps[0]: 'to' ref 'ghost' is not defined. Valid refs: root, c1",
                error["message"]!!.jsonPrimitive.content
            )
            assertFalse("S5 Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S6 — response dependency shape: unblockAt present vs. absent
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 response dependencies carry unblockAt only when provided`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S6 Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpec("c1", "C1"))
                            add(childSpec("c2", "C2"))
                            add(childSpec("c3", "C3"))
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray {
                            add(depSpec("c1", "c2", unblockAt = "work"))
                            add(depSpec("c2", "c3"))
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val depsArr = (result["data"] as JsonObject)["dependencies"] as JsonArray
            assertEquals(2, depsArr.size)

            val dep0 = depsArr[0].jsonObject
            assertEquals("c1", dep0["fromRef"]!!.jsonPrimitive.content)
            assertEquals("c2", dep0["toRef"]!!.jsonPrimitive.content)
            assertEquals("BLOCKS", dep0["type"]!!.jsonPrimitive.content)
            assertEquals("work", dep0["unblockAt"]!!.jsonPrimitive.content)

            val dep1 = depsArr[1].jsonObject
            assertFalse("unblockAt" in dep1, "dep[1] must lack the unblockAt key entirely: $dep1")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S7 — in-transaction rollback: RELATES_TO + unblockAt violates the domain invariant
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S7 RELATES_TO with unblockAt fails atomically inside the write transaction`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S7 Root")) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpec("c1", "C1"))
                            add(childSpec("c2", "C2"))
                        }
                    )
                    put(
                        "deps",
                        buildJsonArray { add(depSpec("c1", "c2", type = "RELATES_TO", unblockAt = "work")) }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("INTERNAL_ERROR", error["code"]!!.jsonPrimitive.content)
            val msg = error["message"]!!.jsonPrimitive.content
            assertTrue(msg.startsWith("Work tree creation failed: "), "actual: $msg")
            assertTrue(
                msg.contains("RELATES_TO dependencies cannot have an unblockAt threshold (no blocking semantics)"),
                "actual: $msg"
            )
            assertFalse("S7 Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S8 — invalid tags fail item construction, naming the offending item
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S8 invalid child tags fail to build the child item and nothing persists`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S8 Child Root")) })
                    put("children", buildJsonArray { add(childSpec("c1", "C1", tags = "Bad Tag")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertTrue(
                error["message"]!!.jsonPrimitive.content.contains("Failed to build child item 'c1'"),
                "actual: ${error["message"]}"
            )
            assertFalse("S8 Child Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    @Test
    fun `S8 invalid root tags fail to build the root item and nothing persists`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("S8 Root Root"))
                            put("tags", JsonPrimitive("Bad Tag"))
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertTrue(
                error["message"]!!.jsonPrimitive.content.contains("Failed to build root item"),
                "actual: ${error["message"]}"
            )
            assertFalse("S8 Root Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S9 — [339] the anchor item is deleted inside the write transaction (create + attach modes)
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S9 create mode anchored at a parent deleted inside the write transaction fails without persisting anything`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "S9 create R", depth = 0)))
            val p = create(WorkItem(title = "S9 create P (will be deleted)", parentId = root.id, depth = 1, rootId = root.id))
            val wrapped = MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d -> d.delete(p.id) }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S9 Orphan Root")) })
                    put("parentId", JsonPrimitive(p.id.toString()))
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("RESOURCE_NOT_FOUND", error["code"]!!.jsonPrimitive.content)
            assertTrue(
                error["message"]!!.jsonPrimitive.content.startsWith("Parent item '${p.id}' not found"),
                "actual: ${error["message"]}"
            )
            assertFalse("S9 Orphan Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    @Test
    fun `S9 attach mode anchored at a leaf deleted inside the write transaction fails without persisting children`() =
        runBlocking {
            val root = stampSelfRoot(create(WorkItem(title = "S9 attach R", depth = 0)))
            val a = create(WorkItem(title = "S9 attach A (will be deleted)", parentId = root.id, depth = 1, rootId = root.id))
            val wrapped = MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d -> d.delete(a.id) }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(a.id.toString())) })
                    put("children", buildJsonArray { add(childSpec("c1", "S9 Orphan Child")) })
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("RESOURCE_NOT_FOUND", error["code"]!!.jsonPrimitive.content)
            assertTrue(
                error["message"]!!.jsonPrimitive.content.startsWith("Root item '${a.id}' not found"),
                "actual: ${error["message"]}"
            )
            assertFalse("S9 Orphan Child" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S10 — [339; AR depth] attach mode reflects the LIVE (in-transaction) placement of a
    // 3-level chain that is concurrently reparented under an unrelated root, across a grandchild
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S10 attach mode under a reparented-in-transaction leaf stamps depth and rootId from the LIVE placement, through a grandchild`() =
        runBlocking {
            val r = stampSelfRoot(create(WorkItem(title = "S10 R", depth = 0)))
            val b = create(WorkItem(title = "S10 B", parentId = r.id, depth = 1, rootId = r.id))
            val a = create(WorkItem(title = "S10 A", parentId = b.id, depth = 2, rootId = r.id))
            val q = stampSelfRoot(create(WorkItem(title = "S10 Q", depth = 0)))
            val wrapped =
                MutateOnFirstTransactionRepository(repositoryProvider.workItemRepository()) { d ->
                    d.update(a.copy(parentId = q.id, depth = 1, rootId = q.id))
                }

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(a.id.toString())) })
                    put(
                        "children",
                        buildJsonArray {
                            add(childSpec("c1", "S10 C1"))
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("g1"))
                                    put("title", JsonPrimitive("S10 G1"))
                                    put("parentRef", JsonPrimitive("c1"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, contextWith(wrapped)) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")

            val data = result["data"] as JsonObject
            val rootJson = data["root"] as JsonObject
            assertEquals(1, rootJson["depth"]!!.jsonPrimitive.int, "root (A) must reflect its LIVE depth under Q")

            val childrenArr = data["children"] as JsonArray
            val c1Json = childrenArr[0].jsonObject
            val g1Json = childrenArr[1].jsonObject
            assertEquals(2, c1Json["depth"]!!.jsonPrimitive.int)
            assertEquals(3, g1Json["depth"]!!.jsonPrimitive.int)

            val c1Id = UUID.fromString(c1Json["id"]!!.jsonPrimitive.content)
            val g1Id = UUID.fromString(g1Json["id"]!!.jsonPrimitive.content)
            val persistedC1 = (repositoryProvider.workItemRepository().getById(c1Id) as Result.Success).data
            val persistedG1 = (repositoryProvider.workItemRepository().getById(g1Id) as Result.Success).data

            assertEquals(2, persistedC1.depth)
            assertEquals(q.id, persistedC1.rootId)
            assertEquals(3, persistedG1.depth)
            assertEquals(q.id, persistedG1.rootId)
            assertEquals(c1Id, persistedG1.parentId)
        }

    // ─────────────────────────────────────────────────────────────────────
    // S11 — [AR Atomicity] docRef failure paths: unknown slug, anchor miss, already-adopted
    // ─────────────────────────────────────────────────────────────────────

    private val planBody = "# Overview\nO.\n# Task 1\nTask 1 detail text."

    private suspend fun createProjectRoot(title: String = "Project"): UUID =
        (repositoryProvider.workItemRepository().create(WorkItem(title = title, type = "project")) as Result.Success).data.id

    @Test
    fun `S11 unknown docRef slug fails with RESOURCE_NOT_FOUND and nothing persists`() =
        runBlocking {
            val projectRootId = createProjectRoot()

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S11 Slug Root")) })
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("nope")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("RESOURCE_NOT_FOUND", error["code"]!!.jsonPrimitive.content)
            assertEquals(
                "Plan document not found: rootId=$projectRootId, slug='nope'",
                error["message"]!!.jsonPrimitive.content
            )
            assertFalse("S11 Slug Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    @Test
    fun `S11 docRef anchor miss fails with VALIDATION_ERROR and nothing persists`() =
        runBlocking {
            val projectRootId = createProjectRoot()
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)

            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("S11 Anchor Root"))
                            put(
                                "noteAnchors",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("noteKey", JsonPrimitive("requirements"))
                                            put("role", JsonPrimitive("queue"))
                                            put("anchor", JsonPrimitive("does-not-exist"))
                                        }
                                    )
                                }
                            )
                        }
                    )
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertEquals(
                "noteAnchors: anchor 'does-not-exist' not found in plan document 'my-plan' (itemRef 'root', noteKey 'requirements')",
                error["message"]!!.jsonPrimitive.content
            )
            assertFalse("S11 Anchor Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")

            val doc = (repositoryProvider.planDocumentRepository().get(projectRootId, "my-plan") as Result.Success).data
            assertEquals(PlanDocumentStatus.PENDING, doc!!.status)
        }

    @Test
    fun `S11 docRef against an already-adopted document fails with VALIDATION_ERROR and nothing persists`() =
        runBlocking {
            val projectRootId = createProjectRoot()
            val earlierAdopterId = createProjectRoot("S11 Earlier Adopter")
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)
            val adopted = repositoryProvider.planDocumentRepository().markAdopted(projectRootId, "my-plan", earlierAdopterId)
            assertTrue(adopted is Result.Success, "setup: markAdopted must succeed; got: $adopted")

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("title", JsonPrimitive("S11 Adopted Root")) })
                    put("parentId", JsonPrimitive(projectRootId.toString()))
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            assertTrue(
                error["message"]!!.jsonPrimitive.content.startsWith("Plan document 'my-plan' (root $projectRootId) is already adopted"),
                "actual: ${error["message"]}"
            )
            assertFalse("S11 Adopted Root" in titlesInDb(), "nothing must persist: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S12 / S13 — [AR precedence, docRef default, strict role] anchor-materialized note vs.
    // createNotes=true schema fill; strict role enforcement on schema-declared keys
    // ─────────────────────────────────────────────────────────────────────

    private fun taskSchemaContext(): ToolExecutionContext {
        val schemaEntries = listOf(NoteSchemaEntry(key = "task-scope", role = Role.QUEUE, required = true))
        val noteSchemaService =
            object : NoteSchemaService {
                override fun getSchemaForTags(tags: List<String>): List<NoteSchemaEntry>? =
                    if (tags.contains("task")) schemaEntries else null
            }
        return ToolExecutionContext(repositoryProvider, noteSchemaService)
    }

    @Test
    fun `S12 anchor-materialized note wins over createNotes schema fill and adopts the document`() =
        runBlocking {
            val projectRootId = createProjectRoot("S12 Project")
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)
            val existingE = create(WorkItem(title = "S12 E", parentId = projectRootId, depth = 1, rootId = projectRootId))

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingE.id.toString())) })
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put("createNotes", JsonPrimitive(true))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("S12 C1"))
                                    put("tags", JsonPrimitive("task"))
                                    put(
                                        "noteAnchors",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("noteKey", JsonPrimitive("task-scope"))
                                                    put("role", JsonPrimitive("queue"))
                                                    put("anchor", JsonPrimitive("task-1"))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, taskSchemaContext()) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")

            val data = result["data"] as JsonObject
            val c1Id = UUID.fromString((data["children"] as JsonArray)[0].jsonObject["id"]!!.jsonPrimitive.content)

            val notes = (repositoryProvider.noteRepository().findByItemId(c1Id) as Result.Success).data
            val taskScopeNotes = notes.filter { it.key == "task-scope" }
            assertEquals(1, taskScopeNotes.size, "must not duplicate: anchor content wins over the createNotes blank fill")
            assertEquals("# Task 1\nTask 1 detail text.", taskScopeNotes[0].body)

            val doc = (repositoryProvider.planDocumentRepository().get(projectRootId, "my-plan") as Result.Success).data
            assertEquals(PlanDocumentStatus.ADOPTED, doc!!.status)
            assertEquals(existingE.id, doc.adoptedByItemId)
        }

    @Test
    fun `S13 anchor role mismatching the schema-declared role is rejected and the document stays pending`() =
        runBlocking {
            val projectRootId = createProjectRoot("S13 Project")
            repositoryProvider.planDocumentRepository().stash(projectRootId, "my-plan", planBody)
            val existingE = create(WorkItem(title = "S13 E", parentId = projectRootId, depth = 1, rootId = projectRootId))

            val params =
                buildJsonObject {
                    put("root", buildJsonObject { put("id", JsonPrimitive(existingE.id.toString())) })
                    put("docRef", buildJsonObject { put("slug", JsonPrimitive("my-plan")) })
                    put("createNotes", JsonPrimitive(true))
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("S13 C1"))
                                    put("tags", JsonPrimitive("task"))
                                    put(
                                        "noteAnchors",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("noteKey", JsonPrimitive("task-scope"))
                                                    put("role", JsonPrimitive("work"))
                                                    put("anchor", JsonPrimitive("task-1"))
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, taskSchemaContext()) as JsonObject
            assertFalse(result["success"]!!.jsonPrimitive.boolean, "actual: $result")
            val error = errorOf(result)
            assertEquals("VALIDATION_ERROR", error["code"]!!.jsonPrimitive.content)
            val msg = error["message"]!!.jsonPrimitive.content
            assertTrue(msg.contains("task-scope"), "actual: $msg")
            assertTrue(msg.contains("queue"), "actual: $msg")
            assertTrue(msg.contains("work"), "actual: $msg")

            val doc = (repositoryProvider.planDocumentRepository().get(projectRootId, "my-plan") as Result.Success).data
            assertEquals(PlanDocumentStatus.PENDING, doc!!.status)
            assertFalse("S13 C1" in titlesInDb(), "child must not be persisted: ${titlesInDb()}")
        }

    // ─────────────────────────────────────────────────────────────────────
    // S14 — [AR priority] priority coercion is persisted correctly for root and every child
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S14 priority is coerced and persisted for root and each child`() =
        runBlocking {
            val params =
                buildJsonObject {
                    put(
                        "root",
                        buildJsonObject {
                            put("title", JsonPrimitive("S14 Root"))
                            put("priority", JsonPrimitive("HIGH"))
                        }
                    )
                    put(
                        "children",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c1"))
                                    put("title", JsonPrimitive("S14 C1"))
                                    put("priority", JsonPrimitive("  "))
                                }
                            )
                            add(
                                buildJsonObject {
                                    put("ref", JsonPrimitive("c2"))
                                    put("title", JsonPrimitive("S14 C2"))
                                    put("priority", JsonPrimitive("low"))
                                }
                            )
                        }
                    )
                }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]!!.jsonPrimitive.boolean, "actual: $result")

            val data = result["data"] as JsonObject
            val rootId = UUID.fromString((data["root"] as JsonObject)["id"]!!.jsonPrimitive.content)
            val childrenArr = data["children"] as JsonArray
            val c1Id = UUID.fromString(childrenArr[0].jsonObject["id"]!!.jsonPrimitive.content)
            val c2Id = UUID.fromString(childrenArr[1].jsonObject["id"]!!.jsonPrimitive.content)

            val root = (repositoryProvider.workItemRepository().getById(rootId) as Result.Success).data
            val c1 = (repositoryProvider.workItemRepository().getById(c1Id) as Result.Success).data
            val c2 = (repositoryProvider.workItemRepository().getById(c2Id) as Result.Success).data

            assertEquals(Priority.HIGH, root.priority)
            assertEquals(Priority.MEDIUM, c1.priority, "blank priority defaults to MEDIUM")
            assertEquals(Priority.LOW, c2.priority)
        }
}
