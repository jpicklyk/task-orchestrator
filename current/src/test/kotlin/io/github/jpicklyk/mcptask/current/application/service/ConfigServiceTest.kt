package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.SqliteConfigStore
import io.github.jpicklyk.mcptask.current.test.configYaml
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.rootItemId
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.github.jpicklyk.mcptask.current.test.updatedAt
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises [ConfigService.push] directly against a real SQLite-backed
 * [SqliteConfigStore] and [SQLiteWorkItemRepository] — mirrors
 * [io.github.jpicklyk.mcptask.current.application.tools.config.ManageProjectConfigToolTest]'s DB-backed
 * style. Focused on the embedded `project.rootId` guard added on top of the existing
 * validate-then-persist pipeline (size cap / existence / depth-0 / parse are already covered at the
 * tool and route layers — this file's `basic push still works` case is a smoke check, not a full
 * re-test of that pipeline).
 */
class ConfigServiceTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private lateinit var service: ConfigService
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var projectConfigRepository: SqliteConfigStore
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
            workItemRepository = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
            projectConfigRepository = db.repositoryProvider().configStore() as SqliteConfigStore

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.configStore() } returns projectConfigRepository

            service = ConfigService(repositoryProvider, YamlConfigDocumentParser, db.unitOfWork())

            rootId = workItemRepository.create(WorkItem(title = "Root", type = "project")).id
            otherRootId = workItemRepository.create(WorkItem(title = "Other Root", type = "project")).id
        }

    // Deliberately plain concatenation (not a nested trimIndent template) — interpolating an
    // already-trimIndent'd multi-line constant into another trimIndent block would get its
    // indentation re-mangled by the outer trim's common-margin computation.
    private fun yamlWithEmbeddedRootId(embedded: String) = "project:\n  rootId: $embedded\n$plainYaml"

    @Test
    fun `push with no project block succeeds unchanged`() =
        runBlocking {
            val result = service.push(rootId, plainYaml)

            assertTrue(result is ConfigPushResult.Success)
        }

    @Test
    fun `push with embedded rootId matching the target succeeds`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(rootId.toString()))

            assertTrue(result is ConfigPushResult.Success)
        }

    @Test
    fun `push with embedded rootId differing from the target is rejected and stores nothing`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(otherRootId.toString()))

            assertTrue(result is ConfigPushResult.RootIdMismatch)
            val mismatch = result as ConfigPushResult.RootIdMismatch
            assertEquals(rootId, mismatch.targetRootId)
            assertEquals(otherRootId, mismatch.embeddedRootId)

            val stored = projectConfigRepository.get(rootId)
            assertNull(stored, "mismatched push must not write a row")
        }

    @Test
    fun `push with a malformed non-UUID embedded rootId proceeds unchanged`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId("not-a-uuid"))

            assertTrue(result is ConfigPushResult.Success)
        }

    @Test
    fun `push with force true bypasses a mismatched embedded rootId`() =
        runBlocking {
            val result = service.push(rootId, yamlWithEmbeddedRootId(otherRootId.toString()), force = true)

            assertTrue(result is ConfigPushResult.Success)
            val stored = projectConfigRepository.get(rootId)
            assertNotNull(stored)
            assertEquals(rootId, stored?.rootItemId)
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

            assertTrue(result is ConfigPushResult.Success)
            val stored = projectConfigRepository.get(rootId)
            assertEquals(yamlB, stored?.configYaml)
        }

    @Test
    fun `push with the CURRENT fingerprint (idempotent re-push) proceeds normally`() =
        runBlocking {
            val yaml = "work_item_schemas:\n  a: {}\n"
            val first = service.push(rootId, yaml)
            assertTrue(first is ConfigPushResult.Success)

            val second = service.push(rootId, yaml)

            assertTrue(second is ConfigPushResult.Success)
            assertEquals(
                (first as ConfigPushResult.Success).fingerprint,
                (second as ConfigPushResult.Success).fingerprint
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

            assertTrue(result is ConfigPushResult.Superseded)
            val superseded = result as ConfigPushResult.Superseded
            assertEquals(rootId, superseded.rootItemId)

            val currentStored = projectConfigRepository.get(rootId)
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

            assertTrue(result is ConfigPushResult.Success)
            val stored = projectConfigRepository.get(rootId)
            assertEquals(yamlA, stored?.configYaml, "force=true should allow reverting to the known-old content")
        }

    // ──────────────────────────────────────────────
    // t3: strict validation of the top-level keys
    // ──────────────────────────────────────────────

    @Test
    fun `push rejects a server-only top-level key with its path`() =
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

            assertTrue(result is ConfigPushResult.Invalid, "a server-only key must reject the push: $result")
            assertEquals(listOf("/actor_authentication"), (result as ConfigPushResult.Invalid).errors.map { it.path })
            assertEquals(null, projectConfigRepository.get(rootId), "nothing is stored for a rejected push")
        }

    @Test
    fun `push accepts a document that only uses per-root top-level keys`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  feature-task:
                    notes: []
                traits: {}
                project:
                  name: "Some Project"
                note_limits:
                  mode: reject
                status_labels:
                  start: "custom-started"
                independence:
                  require_verified: false
                """.trimIndent()

            val result = service.push(rootId, yaml)

            assertTrue(result is ConfigPushResult.Success, "a document of per-root keys is accepted: $result")
        }

    @Test
    fun `push accepts an empty document`() =
        runBlocking {
            val result = service.push(rootId, "")

            assertTrue(result is ConfigPushResult.Success)
            assertTrue((result as ConfigPushResult.Success).schemaWarnings.isEmpty())
        }
}
