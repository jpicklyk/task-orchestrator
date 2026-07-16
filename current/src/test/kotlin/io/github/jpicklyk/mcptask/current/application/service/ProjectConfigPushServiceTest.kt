package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [ProjectConfigPushService.push] directly against a real H2-backed
 * [SQLiteProjectConfigRepository] and [SQLiteWorkItemRepository] — mirrors
 * [io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigToolTest]'s DB-backed
 * style. Focused on the embedded `project.rootId` guard added on top of the existing
 * validate-then-persist pipeline (size cap / existence / depth-0 / parse are already covered at the
 * tool and route layers — this file's `basic push still works` case is a smoke check, not a full
 * re-test of that pipeline).
 */
class ProjectConfigPushServiceTest {
    private lateinit var service: ProjectConfigPushService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var rootId: UUID
    private lateinit var otherRootId: UUID

    private val plainYaml =
        """
        work_item_schemas:
          feature-task:
            notes:
              - key: spec
                role: queue
                required: true
        """.trimIndent()

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            service = ProjectConfigPushService(repositoryProvider)

            rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id
            otherRootId = (workItemRepository.create(WorkItem(title = "Other Root", type = "project")) as Result.Success).data.id
        }

    // Deliberately plain concatenation (not a nested trimIndent template) — interpolating an
    // already-trimIndent'd multi-line constant into another trimIndent block would get its
    // indentation re-mangled by the outer trim's common-margin computation.
    private fun yamlWithEmbeddedRootId(embedded: String) = "project:\n  rootId: $embedded\n$plainYaml"

    @Test
    fun `push with no project block succeeds unchanged`() =
        runBlocking {
            val result = service.push(rootId, plainYaml)

            assertTrue(result is ProjectConfigPushResult.Success)
        }

    @Test
    fun `push with embedded rootId matching the target succeeds`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(rootId.toString()))

            assertTrue(result is ProjectConfigPushResult.Success)
        }

    @Test
    fun `push with embedded rootId differing from the target is rejected and stores nothing`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(otherRootId.toString()))

            assertTrue(result is ProjectConfigPushResult.RootIdMismatch)
            val mismatch = result as ProjectConfigPushResult.RootIdMismatch
            assertEquals(rootId, mismatch.targetRootId)
            assertEquals(otherRootId, mismatch.embeddedRootId)

            val stored = projectConfigRepository.get(rootId)
            assertTrue(stored is Result.Success)
            assertNull((stored as Result.Success).data, "mismatched push must not write a row")
        }

    @Test
    fun `push with a malformed non-UUID embedded rootId proceeds unchanged`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId("not-a-uuid"))

            assertTrue(result is ProjectConfigPushResult.Success)
        }

    @Test
    fun `push with force true bypasses a mismatched embedded rootId`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(otherRootId.toString()), force = true)

            assertTrue(result is ProjectConfigPushResult.Success)
            val stored = projectConfigRepository.get(rootId)
            assertTrue(stored is Result.Success)
            assertEquals(rootId, (stored as Result.Success).data?.rootItemId)
        }

    // ──────────────────────────────────────────────
    // fast-forward (known-old) fingerprint guard
    // ──────────────────────────────────────────────

    @Test
    fun `push with an UNKNOWN fingerprint (brand-new content) proceeds normally`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  a: {}\n"
            val yamlB = "work_item_schemas:\n  b: {}\n"
            service.push(rootId, yamlA)

            // yamlB has never been pushed to this root before — UNKNOWN, not SUPERSEDED.
            val result = service.push(rootId, yamlB)

            assertTrue(result is ProjectConfigPushResult.Success)
            val stored = (projectConfigRepository.get(rootId) as Result.Success).data
            assertEquals(yamlB, stored?.configYaml)
        }

    @Test
    fun `push with the CURRENT fingerprint (idempotent re-push) proceeds normally`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  a: {}\n"
            val first = service.push(rootId, yaml)
            assertTrue(first is ProjectConfigPushResult.Success)

            val second = service.push(rootId, yaml)

            assertTrue(second is ProjectConfigPushResult.Success)
            assertEquals(
                (first as ProjectConfigPushResult.Success).fingerprint,
                (second as ProjectConfigPushResult.Success).fingerprint
            )
        }

    @Test
    fun `push with a SUPERSEDED (known-old) fingerprint is rejected naming the server's updatedAt`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  a: {}\n"
            val yamlB = "work_item_schemas:\n  b: {}\n"
            service.push(rootId, yamlA)
            service.push(rootId, yamlB)

            // yamlA is now superseded — pushing it again (e.g. from a stale checkout) must be
            // rejected, not silently accepted as a normal re-push.
            val result = service.push(rootId, yamlA)

            assertTrue(result is ProjectConfigPushResult.Superseded)
            val superseded = result as ProjectConfigPushResult.Superseded
            assertEquals(rootId, superseded.rootItemId)

            val currentStored = (projectConfigRepository.get(rootId) as Result.Success).data
            assertEquals(
                currentStored?.updatedAt,
                superseded.currentUpdatedAt,
                "Superseded.currentUpdatedAt should name the server's current row's updatedAt"
            )

            // The rejected push must not have overwritten the server's current (yamlB) state.
            assertEquals(yamlB, currentStored?.configYaml)
        }

    @Test
    fun `push with force true bypasses a SUPERSEDED fingerprint and overwrites`() =
        runBlocking {
            val yamlA = "work_item_schemas:\n  a: {}\n"
            val yamlB = "work_item_schemas:\n  b: {}\n"
            service.push(rootId, yamlA)
            service.push(rootId, yamlB)

            val result = service.push(rootId, yamlA, force = true)

            assertTrue(result is ProjectConfigPushResult.Success)
            val stored = (projectConfigRepository.get(rootId) as Result.Success).data
            assertEquals(yamlA, stored?.configYaml, "force=true should allow reverting to the known-old content")
        }

    // ──────────────────────────────────────────────
    // t3: ignoredSections
    // ──────────────────────────────────────────────

    @Test
    fun `push reports ignoredSections when the doc has a top-level key not honored per-root`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes: []
                actor_authentication:
                  mode: jwks
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ProjectConfigPushResult.Success)
            assertEquals(listOf("actor_authentication"), (result as ProjectConfigPushResult.Success).ignoredSections)
        }

    @Test
    fun `push omits ignoredSections when the doc only uses honored top-level keys`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes: []
                note_schemas: {}
                traits: {}
                project:
                  name: "Some Project"
                note_limits:
                  mode: reject
                status_labels:
                  start: "custom-started"
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ProjectConfigPushResult.Success)
            assertTrue(
                (result as ProjectConfigPushResult.Success).ignoredSections.isEmpty(),
                "A document that only uses honored keys must report no ignored sections"
            )
        }

    @Test
    fun `push reports an empty ignoredSections list for an empty document`() =
        runBlocking {
            val result = service.push(rootId, "")

            assertTrue(result is ProjectConfigPushResult.Success)
            assertTrue((result as ProjectConfigPushResult.Success).ignoredSections.isEmpty())
        }
}
