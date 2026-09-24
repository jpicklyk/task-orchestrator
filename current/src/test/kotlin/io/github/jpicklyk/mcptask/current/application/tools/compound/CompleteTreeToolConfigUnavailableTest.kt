package io.github.jpicklyk.mcptask.current.application.tools.compound

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` —
 * S9 maps to this file per the test-plan's file list; oracle is D5 ("advance_item and complete_tree
 * handle it per item and the batch continues (complete_tree: skipped entry, REJECTED, dependents
 * skipped)") plus the `buildConfigUnavailableResult` shape given verbatim in the dispatch
 * declarations: `{ itemId, title, applied=false, skipped=true, skippedReason, error, errorKind:
 * "transient", errorCode: "config_unavailable" }`.
 *
 * Harness mirrors [AdvanceItemToolConfigUnavailableTest]: a real [PerRootConfigService] over a
 * real [SQLiteProjectConfigRepository] wrapped in [FailableProjectConfigRepository] (this file's
 * own copy — not shared across files per this item's file-ownership rule), with [CompleteTreeTool]
 * run against an otherwise mocked [RepositoryProvider], mirroring [CompleteTreeToolTest]'s setup.
 */
class CompleteTreeToolConfigUnavailableTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var wrapperRepo: FailableProjectConfigRepository
    private lateinit var perRootConfigService: PerRootConfigService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    private lateinit var tool: CompleteTreeTool
    private lateinit var context: ToolExecutionContext
    private lateinit var repoProvider: RepositoryProvider
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var noteRepo: NoteRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository

    private val rootConfigYaml =
        """
        work_item_schemas:
          T:
            default_traits:
              - needs-exclusive-k
            notes:
              - key: q1
                role: queue
                required: true
                description: "Q1"
              - key: r1
                role: review
                required: true
                description: "R1"
        traits:
          needs-exclusive-k:
            resources: [k]
        """.trimIndent()

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            wrapperRepo = FailableProjectConfigRepository(SQLiteProjectConfigRepository(databaseManager))
            perRootConfigService = PerRootConfigService(wrapperRepo)

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val root = WorkItem(title = "Root R")
            workItemRepository.create(root)
            rootItemId = root.id

            val upsertResult = wrapperRepo.upsert(rootItemId, rootConfigYaml)
            assertEquals(true, upsertResult is Result.Success, "setup precondition: config push must succeed, got $upsertResult")

            tool = CompleteTreeTool()
            workItemRepo = mockk()
            depRepo = mockk()
            noteRepo = mockk()
            roleTransitionRepo = mockk()

            repoProvider = mockk()
            every { repoProvider.workItemRepository() } returns workItemRepo
            every { repoProvider.dependencyRepository() } returns depRepo
            every { repoProvider.noteRepository() } returns noteRepo
            every { repoProvider.roleTransitionRepository() } returns roleTransitionRepo
            every { repoProvider.resourceLeaseRepository() } returns mockk(relaxed = true)
            coEvery { workItemRepo.dbNow() } returns Instant.now()
            coEvery { workItemRepo.inTransaction(any()) } coAnswers {
                firstArg<suspend () -> Unit>().invoke()
            }
            every { depRepo.findByToItemId(any()) } returns emptyList()
            every { depRepo.findByFromItemId(any()) } returns emptyList()
            coEvery { workItemRepo.countChildrenByRole(any()) } returns Result.Success(emptyMap())

            context = ToolExecutionContext(repoProvider, perRootConfigService = perRootConfigService)
        }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        type: String? = null,
        role: Role = Role.QUEUE,
        title: String = "Test Item",
        rootId: UUID? = null
    ): WorkItem = WorkItem(id = id, title = title, type = type, role = role, rootId = rootId)

    private fun buildItemIdsParams(
        itemIds: List<UUID>,
        trigger: String = "complete"
    ): JsonObject =
        buildJsonObject {
            put("itemIds", buildJsonArray { itemIds.forEach { add(JsonPrimitive(it.toString())) } })
            put("trigger", JsonPrimitive(trigger))
        }

    private fun extractData(result: kotlinx.serialization.json.JsonElement): JsonObject {
        val obj = result as JsonObject
        assertEquals(true, obj["success"]?.jsonPrimitive?.boolean, "Expected success response envelope, got: $obj")
        return obj["data"] as JsonObject
    }

    @Test
    fun `S9 - complete_tree over two children on a cold failing root marks each skipped with config_unavailable`(): Unit =
        runBlocking {
            wrapperRepo.failFingerprint = true

            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            val item1 = makeItem(id = id1, type = "T", role = Role.WORK, title = "Child 1", rootId = rootItemId)
            val item2 = makeItem(id = id2, type = "T", role = Role.WORK, title = "Child 2", rootId = rootItemId)

            coEvery { workItemRepo.getById(id1) } returns Result.Success(item1)
            coEvery { workItemRepo.getById(id2) } returns Result.Success(item2)

            val result = tool.execute(buildItemIdsParams(listOf(id1, id2)), context)
            val data = extractData(result)
            val results = data["results"]!!.jsonArray
            assertEquals(2, results.size)

            results.forEach { entry ->
                val r = entry.jsonObject
                assertEquals(false, r["applied"]?.jsonPrimitive?.boolean, "entry: $r")
                assertEquals(true, r["skipped"]?.jsonPrimitive?.boolean, "entry: $r")
                assertEquals("transient", r["errorKind"]?.jsonPrimitive?.content, "entry: $r")
                assertEquals("config_unavailable", r["errorCode"]?.jsonPrimitive?.content, "entry: $r")
            }

            val summary = data["summary"]!!.jsonObject
            assertEquals(2, summary["total"]?.jsonPrimitive?.int)
            assertEquals(0, summary["completed"]?.jsonPrimitive?.int)

            coVerify(exactly = 0) { workItemRepo.update(any()) }
        }

    @Test
    fun `probe - a sibling item on an unaffected root still completes while the failing item is rejected`(): Unit =
        runBlocking {
            wrapperRepo.failFingerprint = true

            val failingId = UUID.randomUUID()
            val healthyId = UUID.randomUUID()
            val failingItem = makeItem(id = failingId, type = "T", role = Role.WORK, title = "Failing", rootId = rootItemId)
            // rootId = null -> skips the per-root layer entirely, proving D5's "rest of the tree
            // continues" concretely rather than merely by construction of a second failing item.
            val healthyItem = makeItem(id = healthyId, type = "T", role = Role.WORK, title = "Healthy", rootId = null)

            coEvery { workItemRepo.getById(failingId) } returns Result.Success(failingItem)
            coEvery { workItemRepo.getById(healthyId) } returns Result.Success(healthyItem)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())

            val result = tool.execute(buildItemIdsParams(listOf(healthyId, failingId)), context)
            val results = extractData(result)["results"]!!.jsonArray
            val resultMap = results.associate { UUID.fromString(it.jsonObject["itemId"]!!.jsonPrimitive.content) to it.jsonObject }

            assertEquals(true, resultMap[healthyId]?.get("applied")?.jsonPrimitive?.boolean)
            assertEquals(false, resultMap[failingId]?.get("applied")?.jsonPrimitive?.boolean)
            assertEquals("config_unavailable", resultMap[failingId]?.get("errorCode")?.jsonPrimitive?.content)
        }
}

/**
 * Wraps a real [ProjectConfigRepository] and lets tests force [get]/[getFingerprint] to return
 * `Result.Error(RepositoryError.DatabaseError("x"))` on demand. Own copy for this file — see
 * [io.github.jpicklyk.mcptask.current.application.tools.workflow.AdvanceItemToolConfigUnavailableTest]'s
 * identical class for the full rationale (this item's file-ownership rule forbids extracting a
 * shared harness file for it).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) Result.Error(RepositoryError.DatabaseError("x")) else delegate.getFingerprint(rootItemId)

    override suspend fun get(rootItemId: UUID) = if (failGet) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
}
