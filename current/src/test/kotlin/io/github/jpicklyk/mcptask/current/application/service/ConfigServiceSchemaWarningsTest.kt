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
 * Exercises [ConfigService.push]'s [ConfigPushResult.Success.schemaWarnings] --
 * the soft schema/trait parse warnings collected by
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlSchemaParser.parseRoot] (an
 * invalid note `role`, a note entry missing `key`) surfaced on the push result WITHOUT rejecting
 * the push; the config is still parsed, stored and read back verbatim.
 *
 * Independent test authorship per the `needs-test-author` trait: written against the item's
 * `test-plan` note oracles and the public [ConfigService.push] /
 * [ConfigPushResult.Success] shapes, without reading the implementer's own tests or
 * notes. Mirrors [ConfigServiceTest]'s SQLite-backed harness style; that file already
 * covers the rest of the validate-then-persist pipeline (size cap / existence / depth-0 / parse /
 * rootId guard / fingerprint guard) so this file focuses solely on `schemaWarnings`.
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

    // ──────────────────────────────────────────────
    // S1 — all-valid schema: no warnings
    // ──────────────────────────────────────────────

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

    // ──────────────────────────────────────────────
    // S2 — a bad role still succeeds and stores unchanged
    // ──────────────────────────────────────────────

    @Test
    fun `S2 a bad role still succeeds the push and stores the document unchanged`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: not-a-role
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Success, "a soft schema warning must never reject the push")
            assertTrue((result as ConfigPushResult.Success).schemaWarnings.isNotEmpty())

            val stored = projectConfigRepository.get(rootId)
            assertEquals(yaml, stored?.configYaml, "the document must be stored verbatim despite the soft warning")
        }

    // ──────────────────────────────────────────────
    // S7 — multiple invalid entries, entry order preserved
    // ──────────────────────────────────────────────

    @Test
    fun `S7 two invalid entries in one notes list produce two warnings in entry order`() =
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

            assertTrue(result is ConfigPushResult.Success)
            val warnings = (result as ConfigPushResult.Success).schemaWarnings
            assertEquals(2, warnings.size, "expected exactly two warnings: $warnings")
            assertTrue(
                warnings[0].contains("bad-role-entry"),
                "first warning should be for the bad-role entry (index 0): $warnings"
            )
            assertTrue(
                warnings[1].contains("key"),
                "second warning should be for the missing-key entry (index 2): $warnings"
            )
        }

    // ──────────────────────────────────────────────
    // S8 — schemaWarnings AND ignoredSections both present
    // ──────────────────────────────────────────────

    @Test
    fun `S8 an invalid entry alongside an unhonored top-level key surfaces both schemaWarnings and ignoredSections`() =
        runBlocking {
            // Deliberately plain concatenation, not a nested trimIndent template (see
            // ConfigServiceTest) -- interpolating an already-trimIndent'd multi-line
            // constant into another trimIndent block would get its indentation re-mangled.
            val yaml =
                "work_item_schemas:\n" +
                    "  feature-task:\n" +
                    "    notes:\n" +
                    "      - key: spec\n" +
                    "        role: not-a-role\n" +
                    "actor_authentication:\n" +
                    "  mode: jwks\n"

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Success)
            val success = result as ConfigPushResult.Success
            assertTrue(success.schemaWarnings.isNotEmpty(), "expected a schema warning for the bad role")
            assertEquals(listOf("actor_authentication"), success.ignoredSections)
        }

    // ──────────────────────────────────────────────
    // S9 — blank document: Success, no schema warnings
    // ──────────────────────────────────────────────

    @Test
    fun `S9 a blank document pushes successfully with no schema warnings`() =
        runBlocking {
            val result = service.push(rootId, "")

            assertTrue(result is ConfigPushResult.Success)
            assertTrue((result as ConfigPushResult.Success).schemaWarnings.isEmpty())
        }

    // ──────────────────────────────────────────────
    // S10 — re-push (idempotent) recomputes the same warnings
    // ──────────────────────────────────────────────

    @Test
    fun `S10 re-pushing identical bad YAML recomputes the same warnings rather than persisting them`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: not-a-role
                """.trimIndent()

            val first = service.push(rootId, yaml)
            val second = service.push(rootId, yaml)

            assertTrue(first is ConfigPushResult.Success)
            assertTrue(second is ConfigPushResult.Success)
            val firstWarnings = (first as ConfigPushResult.Success).schemaWarnings
            val secondWarnings = (second as ConfigPushResult.Success).schemaWarnings
            assertTrue(firstWarnings.isNotEmpty(), "sanity: this scenario needs at least one warning to be meaningful")
            assertEquals(firstWarnings, secondWarnings, "the same input YAML must recompute identical warnings on re-push")
        }
}
