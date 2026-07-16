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
}
