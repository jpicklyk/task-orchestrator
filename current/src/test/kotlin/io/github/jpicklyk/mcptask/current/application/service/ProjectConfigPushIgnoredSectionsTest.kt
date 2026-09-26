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
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `test-plan` note on item `df7d579a` (scenario S13).
 * Mirrors [ProjectConfigPushServiceTest]'s existing H2-backed harness style.
 *
 * Per `task-scope`: C1 deliberately leaves `schema_resolution` OUT of
 * [ConfigDocument.PER_ROOT_HONORED_SECTIONS] ("adding it would change ignoredSections... which is
 * C4's job, not C1's"). This test locks that non-goal down as an observable contract: a push whose
 * document mixes an already-unhonored key (`retrospective`), an honored one
 * (`work_item_schemas`), the always-ignored `actor_authentication` section, and the new
 * `schema_resolution` key must list all three unhonored keys — including `schema_resolution` — in
 * `ignoredSections`, in document order, with no schema-validation warnings.
 */
class ProjectConfigPushIgnoredSectionsTest {
    private lateinit var service: ProjectConfigPushService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var rootId: UUID

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
        }

    @Test
    fun `S13 retrospective, actor_authentication and schema_resolution are all ignored, in document order, with no schema warnings`() =
        runBlocking {
            val yaml =
                """
                retrospective:
                  enabled: true
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: true
                actor_authentication:
                  enabled: true
                schema_resolution: bogus
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ProjectConfigPushResult.Success, "expected Success, got: $result")
            val success = result as ProjectConfigPushResult.Success
            assertEquals(listOf("retrospective", "actor_authentication", "schema_resolution"), success.ignoredSections)
            assertTrue(success.schemaWarnings.isEmpty(), "schemaWarnings: ${success.schemaWarnings}")
        }
}
