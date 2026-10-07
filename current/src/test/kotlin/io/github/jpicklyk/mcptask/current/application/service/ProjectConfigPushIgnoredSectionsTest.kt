package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.repository.RepositoryProvider
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independently authored against the frozen `test-plan` note on item `df7d579a` (scenario S13).
 * Mirrors [ProjectConfigPushServiceTest]'s existing SQLite-backed harness style.
 *
 * Per `task-scope` (item `8879f554`, build 7): C4 adds `schema_resolution` to
 * [ConfigDocument.PER_ROOT_HONORED_SECTIONS] — it is now HONORED, not ignored. A push whose
 * document mixes an already-unhonored key (`retrospective`), an honored one
 * (`work_item_schemas`), the always-ignored `actor_authentication` section, and an INVALID
 * `schema_resolution` value must list only `retrospective` and `actor_authentication` in
 * `ignoredSections` (schema_resolution is honored even though its value is invalid), and the
 * parser's build-8 warning for the invalid value surfaces via `schemaWarnings`.
 */
class ProjectConfigPushIgnoredSectionsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var service: ProjectConfigPushService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            workItemRepository = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
            projectConfigRepository = db.repositoryProvider().projectConfigRepository() as SQLiteProjectConfigRepository

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            service = ProjectConfigPushService(repositoryProvider, YamlConfigDocumentParser)

            rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id
        }

    @Test
    fun `S13 retrospective and actor_authentication are ignored, schema_resolution is honored and its invalid value warns`() =
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
            assertEquals(listOf("retrospective", "actor_authentication"), success.ignoredSections)
            assertEquals(
                1,
                success.schemaWarnings.count {
                    it.contains("schema_resolution") && it.contains("bogus")
                },
                "schemaWarnings: ${success.schemaWarnings}"
            )
        }
}
