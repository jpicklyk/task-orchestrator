package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteConfigStore
import io.github.jpicklyk.mcptask.current.test.configYaml
import io.github.jpicklyk.mcptask.current.test.get
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
 * Exercises [ConfigService.push]'s [ConfigPushResult.Success.schemaWarnings] and [ConfigPushResult.Invalid]:
 * advisory findings (a required note no declared seat owns) surface on a successful push WITHOUT rejecting it, while
 * everything the 3.x parser used to skip softly (an invalid note `role`, a note entry missing `key`) now rejects the
 * push with a JSON Pointer path and stores nothing.
 *
 * Mirrors [ConfigServiceTest]'s SQLite-backed harness style; that file already covers the rest of the
 * validate-then-persist pipeline (size cap / existence / depth-0 / parse / rootId guard / fingerprint guard).
 */
class ConfigServiceSchemaWarningsTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var service: ConfigService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SqliteConfigStore
    private lateinit var rootId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            workItemRepository = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
            projectConfigRepository = db.repositoryProvider().configStore() as SqliteConfigStore

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.configStore() } returns projectConfigRepository

            service = ConfigService(repositoryProvider, YamlConfigDocumentParser, db.unitOfWork())

            rootId = workItemRepository.create(WorkItem(title = "Root", type = "project")).id
        }

    /** A seat-aware schema with a required note no seat owns: the one advisory finding a push can carry. */
    private val unownedNoteYaml =
        """
        work_item_schemas:
          feature-task:
            seats:
              - { name: impl, phase: work, enters: true }
            notes:
              - key: spec
                role: work
                required: true
        """.trimIndent()

    @Test
    fun `S1 an all-valid schema pushes successfully with no schema warnings`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: true
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Success)
            assertTrue((result as ConfigPushResult.Success).schemaWarnings.isEmpty())
        }

    @Test
    fun `S2 an advisory finding still succeeds the push and stores the document unchanged`() =
        runBlocking {
            val result = service.push(rootId, unownedNoteYaml)

            assertTrue(result is ConfigPushResult.Success, "an advisory finding must never reject the push: $result")
            val warnings = (result as ConfigPushResult.Success).schemaWarnings
            assertEquals(1, warnings.size, "expected exactly one advisory warning: $warnings")
            assertTrue(warnings.single().contains("feature-task") && warnings.single().contains("spec"), warnings.single())

            val stored = projectConfigRepository.get(rootId)
            assertEquals(unownedNoteYaml, stored?.configYaml, "the document must be stored verbatim")
        }

    @Test
    fun `S7 two unowned required notes produce two warnings in note order`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    seats:
                      - { name: impl, phase: work, enters: true }
                    notes:
                      - key: first-note
                        role: work
                        required: true
                      - key: second-note
                        role: work
                        required: true
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Success)
            val warnings = (result as ConfigPushResult.Success).schemaWarnings
            assertEquals(2, warnings.size, "expected exactly two warnings: $warnings")
            assertTrue(warnings[0].contains("first-note"), "first warning should be for the first note: $warnings")
            assertTrue(warnings[1].contains("second-note"), "second warning should be for the second note: $warnings")
        }

    @Test
    fun `S8 an invalid role and a missing key reject the push with their paths and store nothing`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: bad-role-entry
                        role: not-a-role
                      - key: good-entry
                        role: work
                      - role: queue
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Invalid, "former soft skips must reject the push: $result")
            val errorPaths = (result as ConfigPushResult.Invalid).errors.map { it.path }
            assertTrue("/work_item_schemas/feature-task/notes/0/role" in errorPaths, "$errorPaths")
            assertTrue("/work_item_schemas/feature-task/notes/2" in errorPaths, "$errorPaths")
            assertEquals(null, projectConfigRepository.get(rootId), "a rejected push stores nothing")
        }

    @Test
    fun `S9 a blank document pushes successfully with no schema warnings`() =
        runBlocking {
            val result = service.push(rootId, "")

            assertTrue(result is ConfigPushResult.Success)
            assertTrue((result as ConfigPushResult.Success).schemaWarnings.isEmpty())
        }

    @Test
    fun `S10 re-pushing identical YAML recomputes the same warnings rather than persisting them`() =
        runBlocking {
            val first = service.push(rootId, unownedNoteYaml)
            val second = service.push(rootId, unownedNoteYaml)

            assertTrue(first is ConfigPushResult.Success)
            assertTrue(second is ConfigPushResult.Success)
            val firstWarnings = (first as ConfigPushResult.Success).schemaWarnings
            val secondWarnings = (second as ConfigPushResult.Success).schemaWarnings
            assertTrue(firstWarnings.isNotEmpty(), "sanity: this scenario needs at least one warning to be meaningful")
            assertEquals(firstWarnings, secondWarnings, "the same input YAML must recompute identical warnings on re-push")
        }
}
