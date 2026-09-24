package io.github.jpicklyk.mcptask.current.application.tools.workflow

import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.Note
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
import kotlinx.serialization.json.JsonArray
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independently authored against the frozen `diagnosis`/`test-plan` notes on item `aa664be1` —
 * scenarios S1-S8, S13, S15-S17 map 1:1 to `test-plan`'s ids; oracles are cited there (D1-D9 =
 * `diagnosis`'s frozen decisions).
 *
 * Harness: a REAL [PerRootConfigService] backed by a REAL [SQLiteProjectConfigRepository] (H2,
 * schema via [DirectDatabaseSchemaManager]) wrapped in [FailableProjectConfigRepository] — the
 * test-plan's "a test ProjectConfigRepository wrapping SQLiteProjectConfigRepository; its reads
 * can switch to Result.Error" harness line, which no existing src/test fixture provides (per the
 * dispatch's gap callout) and is therefore authored here. [AdvanceItemTool] itself runs against an
 * otherwise fully-mocked [RepositoryProvider], mirroring [AdvanceItemToolTest]'s own setup.
 *
 * Shared fixture (per the test-plan harness paragraph): root `rootItemId` has a pushed config for
 * type `T` — required queue note `q1`, required review note `r1` — plus a trait
 * `needs-exclusive-k` declaring one exclusive resource `k` as `T`'s `default_traits`. The GLOBAL
 * layer (the context's default `NoOpNoteSchemaService`) has no `T` and no `default` schema.
 */
class AdvanceItemToolConfigUnavailableTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var wrapperRepo: FailableProjectConfigRepository
    private lateinit var perRootConfigService: PerRootConfigService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var rootItemId: UUID

    private lateinit var tool: AdvanceItemTool
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

            tool = AdvanceItemTool()
            workItemRepo = mockk()
            depRepo = mockk()
            noteRepo = mockk()
            roleTransitionRepo = mockk()
            coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
            coEvery { noteRepo.findByItemId(any(), any()) } returns Result.Success(emptyList())

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

            context = ToolExecutionContext(repoProvider, perRootConfigService = perRootConfigService)
        }

    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        type: String? = null,
        role: Role = Role.QUEUE,
        title: String = "Test Item",
        rootId: UUID? = null
    ): WorkItem = WorkItem(id = id, title = title, type = type, role = role, rootId = rootId)

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

    private fun extractData(result: kotlinx.serialization.json.JsonElement): JsonObject {
        val obj = result as JsonObject
        assertEquals(true, obj["success"]?.jsonPrimitive?.boolean, "Expected success response envelope, got: $obj")
        return obj["data"] as JsonObject
    }

    private fun extractResults(result: kotlinx.serialization.json.JsonElement): JsonArray = extractData(result)["results"]!!.jsonArray

    private fun extractSummary(result: kotlinx.serialization.json.JsonElement): JsonObject = extractData(result)["summary"]!!.jsonObject

    private fun stubHealthyTransition(itemId: UUID) {
        coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        every { depRepo.findByToItemId(itemId) } returns emptyList()
        every { depRepo.findByFromItemId(itemId) } returns emptyList()
    }

    // ──────────────────────────────────────────────
    // Happy — S1-S3
    // ──────────────────────────────────────────────

    @Test
    fun `S1 - a root with no pushed config resolves through to the global layer unaffected by this fix`(): Unit =
        runBlocking {
            val bareRoot = WorkItem(title = "Bare Root S1")
            workItemRepository.create(bareRoot)

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = bareRoot.id)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            stubHealthyTransition(itemId)

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(true, r["applied"]?.jsonPrimitive?.boolean, "no per-root row -> falls through to the global (schema-free) layer")
            assertEquals("work", r["newRole"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S2 - a cached schema survives a read failure and still gates start on the missing note`(): Unit =
        runBlocking {
            assertNotNull(perRootConfigService.getSnapshot(rootItemId), "sanity: warm read must succeed before injecting failures")
            wrapperRepo.failFingerprint = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val params = buildParams(transitionObj(itemId, "start"))
            val result = tool.execute(params, context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(false, r["applied"]?.jsonPrimitive?.boolean)
            val missingNotes = r["missingNotes"]!!.jsonArray
            assertEquals(1, missingNotes.size)
            assertEquals("q1", missingNotes[0].jsonObject["key"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { workItemRepo.update(any()) }

            // D8: LKG has no TTL — a repeat call while reads are still failing serves the same result.
            val result2 = tool.execute(params, context)
            val r2 = extractResults(result2)[0].jsonObject
            assertEquals(false, r2["applied"]?.jsonPrimitive?.boolean)
            assertEquals(1, r2["missingNotes"]!!.jsonArray.size)
        }

    @Test
    fun `S3 - after reads recover a freshly pushed config replaces the previously served LKG`(): Unit =
        runBlocking {
            assertNotNull(perRootConfigService.getSnapshot(rootItemId), "sanity: warm read with q1 required")
            wrapperRepo.failFingerprint = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val duringFailure = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val missingDuringFailure = extractResults(duringFailure)[0].jsonObject["missingNotes"]!!.jsonArray
            assertEquals(
                "q1",
                missingDuringFailure[0].jsonObject["key"]?.jsonPrimitive?.content,
                "sanity: LKG (q1) served while reads fail"
            )

            wrapperRepo.failFingerprint = false
            val pushResult =
                wrapperRepo.upsert(
                    rootItemId,
                    """
                    work_item_schemas:
                      T:
                        notes:
                          - key: q2
                            role: queue
                            required: true
                            description: "Q2"
                    """.trimIndent()
                )
            assertEquals(true, pushResult is Result.Success, "setup precondition: second config push must succeed")

            val afterRecovery = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val missingAfterRecovery = extractResults(afterRecovery)[0].jsonObject["missingNotes"]!!.jsonArray
            assertEquals(1, missingAfterRecovery.size)
            assertEquals(
                "q2",
                missingAfterRecovery[0].jsonObject["key"]?.jsonPrimitive?.content,
                "D8: the next good read must refresh the LKG, not keep serving the stale q1 schema"
            )
        }

    // ──────────────────────────────────────────────
    // Failure (cold cache) — S4-S8
    // ──────────────────────────────────────────────

    @Test
    fun `S4 - a cold getFingerprint read failure with no LKG rejects the transition as transient config_unavailable`(): Unit =
        runBlocking {
            wrapperRepo.failFingerprint = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(false, r["applied"]?.jsonPrimitive?.boolean)
            assertEquals("transient", r["errorKind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", r["errorCode"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { workItemRepo.update(any()) }
            coVerify(exactly = 0) { roleTransitionRepo.create(any()) }
        }

    @Test
    fun `S5 - getFingerprint OK but a cold get read failure also rejects as transient config_unavailable`(): Unit =
        runBlocking {
            wrapperRepo.failGet = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(false, r["applied"]?.jsonPrimitive?.boolean)
            assertEquals("transient", r["errorKind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", r["errorCode"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { workItemRepo.update(any()) }
        }

    @Test
    fun `S6 - a WORK item with its own notes filled still rejects on a cold read failure and stays in WORK`(): Unit =
        runBlocking {
            wrapperRepo.failFingerprint = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.WORK, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            val filledWorkNote = Note(itemId = itemId, key = "some-work-note", role = "work", body = "filled")
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(filledWorkNote))
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(filledWorkNote))
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(false, r["applied"]?.jsonPrimitive?.boolean)
            assertEquals("transient", r["errorKind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", r["errorCode"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { workItemRepo.update(any()) }
        }

    @Test
    fun `S7 - a queue-filled item carrying an exclusive-resource trait rejects before any lease is touched`(): Unit =
        runBlocking {
            wrapperRepo.failGet = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            val filledQueueNote = Note(itemId = itemId, key = "q1", role = "queue", body = "filled")
            coEvery { noteRepo.findByItemId(itemId) } returns Result.Success(listOf(filledQueueNote))
            coEvery { noteRepo.findByItemId(itemId, any()) } returns Result.Success(listOf(filledQueueNote))
            every { depRepo.findByToItemId(itemId) } returns emptyList()
            every { depRepo.findByFromItemId(itemId) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            // Even though q1 (the only required queue note) is filled — meaning without this fix a
            // gate check would have passed and the item would proceed to the resource-lease gate —
            // schema resolution (step 0) throws first, so the outcome is identical to S4-S6 and no
            // lease is ever acquired (proven indirectly: the item is never persisted at all).
            assertEquals(false, r["applied"]?.jsonPrimitive?.boolean)
            assertEquals("transient", r["errorKind"]?.jsonPrimitive?.content)
            assertEquals("config_unavailable", r["errorCode"]?.jsonPrimitive?.content)
            coVerify(exactly = 0) { workItemRepo.update(any()) }
        }

    @Test
    fun `S8 - a batch with one item on a healthy root and one on a failing root rejects only the failing one`(): Unit =
        runBlocking {
            // Both roots share this one wrapper/service instance, so the failure must be scoped to
            // rootItemId only — otherwise the "healthy" root's reads would fail too.
            wrapperRepo.failOnlyForRoot = rootItemId
            wrapperRepo.failFingerprint = true
            val healthyRoot = WorkItem(title = "Healthy Root S8")
            workItemRepository.create(healthyRoot)

            val idA = UUID.randomUUID()
            val idB = UUID.randomUUID()
            val itemA = makeItem(id = idA, type = "T", role = Role.QUEUE, rootId = healthyRoot.id)
            val itemB = makeItem(id = idB, type = "T", role = Role.QUEUE, rootId = rootItemId)

            coEvery { workItemRepo.getById(idA) } returns Result.Success(itemA)
            coEvery { workItemRepo.getById(idB) } returns Result.Success(itemB)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(any()) } returns emptyList()
            every { depRepo.findByFromItemId(any()) } returns emptyList()

            val result = tool.execute(buildParams(transitionObj(idA, "start"), transitionObj(idB, "start")), context)
            val results = extractResults(result)
            val rA = results.first { it.jsonObject["itemId"]?.jsonPrimitive?.content == idA.toString() }.jsonObject
            val rB = results.first { it.jsonObject["itemId"]?.jsonPrimitive?.content == idB.toString() }.jsonObject

            assertEquals(true, rA["applied"]?.jsonPrimitive?.boolean, "A's root has no config row -> global fallback -> succeeds")
            assertEquals(false, rB["applied"]?.jsonPrimitive?.boolean)
            assertEquals("config_unavailable", rB["errorCode"]?.jsonPrimitive?.content)

            val summary = extractSummary(result)
            assertEquals(1, summary["succeeded"]?.jsonPrimitive?.int)
            assertEquals(1, summary["failed"]?.jsonPrimitive?.int)
        }

    // ──────────────────────────────────────────────
    // Edge — S13, S15-S17
    // ──────────────────────────────────────────────

    @Test
    fun `S13 - a per-root read failure during post-commit cascade detection does not undo the primary commit`(): Unit =
        runBlocking {
            // C has rootId=null so its OWN schema resolution never touches the per-root layer at
            // all (per resolveSchema's KDoc: a null rootId skips the per-root layer entirely) — this
            // is what lets C's own "complete" succeed deterministically while root R's reads fail
            // constantly, so the failure is only ever observed by the cascade attempt on P.
            wrapperRepo.failFingerprint = true

            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parentItem = makeItem(id = parentId, type = "orphan-type", role = Role.WORK, title = "P", rootId = rootItemId)
            val childItem =
                WorkItem(id = childId, title = "C", role = Role.WORK, parentId = parentId, rootId = null, depth = 1)

            coEvery { workItemRepo.getById(childId) } returns Result.Success(childItem)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parentItem)
            coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
            coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
            every { depRepo.findByToItemId(any()) } returns emptyList()
            every { depRepo.findByFromItemId(any()) } returns emptyList()
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))

            val result = tool.execute(buildParams(transitionObj(childId, "complete")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(true, r["applied"]?.jsonPrimitive?.boolean, "C's own commit must not be affected by P's cascade failing")
            assertNull(r["errorCode"], "C's own result must not be reported as config_unavailable")

            val cascadeEvents = r["cascadeEvents"]?.jsonArray ?: buildJsonArray { }
            assertEquals(
                false,
                cascadeEvents.any {
                    it.jsonObject["itemId"]?.jsonPrimitive?.content == parentId.toString() &&
                        it.jsonObject["applied"]?.jsonPrimitive?.boolean == true
                },
                "D7: a per-root config read failure while detecting/gating P's cascade must not apply a cascade to P"
            )
            coVerify(exactly = 0) { workItemRepo.update(match { it.id == parentId }) }
        }

    @Test
    fun `S15 - a config row deleted after being cached falls through to the global schema not the stale LKG`(): Unit =
        runBlocking {
            assertNotNull(perRootConfigService.getSnapshot(rootItemId), "sanity: warm read before delete")
            val deleteResult = wrapperRepo.delete(rootItemId)
            assertEquals(true, deleteResult is Result.Success, "setup precondition: delete must succeed, got $deleteResult")

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            stubHealthyTransition(itemId)

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(
                true,
                r["applied"]?.jsonPrimitive?.boolean,
                "genuine absence (not an error) must fall through to global, not keep serving the deleted LKG"
            )
            assertEquals("work", r["newRole"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S16 - a null rootId always uses the global schema even while the per-root layer is failing`(): Unit =
        runBlocking {
            wrapperRepo.failFingerprint = true
            wrapperRepo.failGet = true

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = null)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            stubHealthyTransition(itemId)

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(
                true,
                r["applied"]?.jsonPrimitive?.boolean,
                "a null rootId must skip the per-root layer entirely, per resolveSchema's KDoc"
            )
            assertEquals("work", r["newRole"]?.jsonPrimitive?.content)
        }

    @Test
    fun `S17 - malformed stored YAML falls through to the global schema rather than throwing`(): Unit =
        runBlocking {
            val malformedResult = wrapperRepo.upsert(rootItemId, "work_item_schemas: [\ninvalid yaml: :\n  - broken")
            assertEquals(
                true,
                malformedResult is Result.Success,
                "setup precondition: storing malformed YAML must still succeed (the row itself is valid)"
            )

            val itemId = UUID.randomUUID()
            val item = makeItem(id = itemId, type = "T", role = Role.QUEUE, rootId = rootItemId)
            coEvery { workItemRepo.getById(itemId) } returns Result.Success(item)
            stubHealthyTransition(itemId)

            val result = tool.execute(buildParams(transitionObj(itemId, "start")), context)
            val r = extractResults(result)[0].jsonObject

            assertEquals(true, r["applied"]?.jsonPrimitive?.boolean)
            assertNull(
                r["errorCode"],
                "malformed YAML must not surface as config_unavailable — it is treated as absence, not a read failure"
            )
        }
}

/**
 * Wraps a real [ProjectConfigRepository] (a [SQLiteProjectConfigRepository] in every test above)
 * and lets tests force [get]/[getFingerprint] to return `Result.Error(RepositoryError.DatabaseError("x"))`
 * on demand. This is the test-plan harness's "test ProjectConfigRepository wrapping
 * SQLiteProjectConfigRepository; its reads can switch to Result.Error" fixture — no such fixture
 * exists anywhere under src/test (confirmed gap in the dispatch's declarations), so it is authored
 * here, scoped to this file only (not extracted to a shared harness, per this item's file-ownership
 * rule).
 */
private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    @Volatile var failGet: Boolean = false

    /**
     * When non-null, [failFingerprint]/[failGet] only apply to reads for this one root — every
     * other root's reads pass straight through to [delegate]. Needed by S8 (one item on a healthy
     * root, one on a failing root): both roots are served by this SAME wrapper/service instance, so
     * an unscoped failure flag would fail the "healthy" root too. Left null (the default), the
     * flags apply to every root, matching every other scenario in this file, which only ever has
     * one root of interest.
     */
    @Volatile var failOnlyForRoot: UUID? = null

    private fun shouldFail(rootItemId: UUID) = failOnlyForRoot == null || failOnlyForRoot == rootItemId

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint && shouldFail(rootItemId)) {
            Result.Error(RepositoryError.DatabaseError("x"))
        } else {
            delegate.getFingerprint(rootItemId)
        }

    override suspend fun get(rootItemId: UUID) =
        if (failGet && shouldFail(rootItemId)) Result.Error(RepositoryError.DatabaseError("x")) else delegate.get(rootItemId)
}
