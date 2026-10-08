package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `task-scope`/`test-plan` notes on item `8879f554`
 * (scenario S10). Mirrors [ProjectConfigPushIgnoredSectionsTest]'s SQLite-backed harness (item
 * `df7d579a`).
 *
 * Oracle: task-scope build 7 ("add `schema_resolution` to PER_ROOT_HONORED_SECTIONS") + build 8
 * (the parser warning surfaces via `schemaWarnings` with no `ProjectConfigPushService` edit).
 * `schema_resolution` is now honored -- a valid value is never in `ignoredSections`; an invalid
 * value is STILL honored (never ignored) but the parser's warning for it surfaces in
 * `schemaWarnings`, and the push still succeeds either way.
 */
class ProjectConfigPushSchemaResolutionTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var service: ProjectConfigPushService
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val workItemRepository = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
            val projectConfigRepository = db.repositoryProvider().projectConfigRepository() as SQLiteProjectConfigRepository

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser, db.unitOfWork())

            rootId = workItemRepository.create(WorkItem(title = "Root", type = "project")).id
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
    fun `S10 - invalid schema_resolution is honored and adds one schemaWarning naming the key and value`(): Unit =
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
