package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `8879f554`
 * (scenario S10). Mirrors [ProjectConfigPushIgnoredSectionsTest]'s H2-backed harness (item
 * `df7d579a`).
 *
 * Oracle: task-scope build 7 ("add `schema_resolution` to PER_ROOT_HONORED_SECTIONS") + build 8
 * (the parser warning surfaces via `schemaWarnings` with no `ProjectConfigPushService` edit).
 * `schema_resolution` is now honored -- a valid value is never in `ignoredSections`; an invalid
 * value is STILL honored (never ignored) but the parser's warning for it surfaces in
 * `schemaWarnings`, and the push still succeeds either way.
 */
class ProjectConfigPushSchemaResolutionTest {
    private lateinit var service: ProjectConfigPushService
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            val projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser)

            rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id
        }

    @Test
    fun `S10 - a valid schema_resolution value is honored - never ignored, no schemaWarnings`(): Unit =
        runBlocking {
            val result = service.push(rootId, "schema_resolution: layered\nwork_item_schemas:\n  default:\n    notes: []\n")

            assertTrue(result is ProjectConfigPushResult.Success, "expected Success, got: $result")
            val success = result as ProjectConfigPushResult.Success
            assertFalse(success.ignoredSections.contains("schema_resolution"), "ignoredSections: ${success.ignoredSections}")
            assertTrue(success.schemaWarnings.isEmpty(), "schemaWarnings: ${success.schemaWarnings}")
        }

    @Test
    fun `S10 - an invalid schema_resolution value is still honored but adds exactly one schemaWarning naming the key and the bad value`(): Unit =
        runBlocking {
            val result = service.push(rootId, "schema_resolution: bogus\nwork_item_schemas:\n  default:\n    notes: []\n")

            assertTrue(result is ProjectConfigPushResult.Success, "expected Success, got: $result")
            val success = result as ProjectConfigPushResult.Success
            assertFalse(success.ignoredSections.contains("schema_resolution"), "ignoredSections: ${success.ignoredSections}")
            assertEquals(
                1,
                success.schemaWarnings.count { it.contains("schema_resolution") && it.contains("bogus") },
                "schemaWarnings: ${success.schemaWarnings}"
            )
        }
}
