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
 * [ProjectConfigPushService.push] precondition-guard tests for item `fab1b3ea` ("Move the
 * project-config fingerprint compare-and-set inside the upsert transaction").
 *
 * Test-plan scenario covered: S5 (`push(..., expectedFingerprint=...)` mismatch ->
 * `PreconditionFailed`). Mirrors [ProjectConfigPushServiceTest]'s H2-backed style — no race
 * harness needed here since S5 is a single-caller precondition check, not a concurrency scenario
 * (those are S3/S4/S8, covered at the repository layer in
 * `SQLiteProjectConfigRepositoryGuardedUpsertTest`).
 *
 * Oracle: the `push` KDoc supplied in the dispatch declarations — "force=true skips the
 * rootId-mismatch and fast-forward (Superseded) guards ONLY; expectedFingerprint is still
 * enforced under force."
 *
 * Arbitration note: the dispatch prompt flagged that the frozen `test-plan`'s S5 text might be
 * read as assuming "force zeroes expectedFingerprint". The delivered S5 text
 * (`push(B, expectedFingerprint="0"x64) -> PreconditionFailed(fp(A)); get -> A`) does not
 * actually set `force` at all, so there is no literal contradiction to resolve — but since no
 * scenario in the frozen test-plan exercises `force=true` together with `expectedFingerprint` at
 * the service layer (S8's force+If-Match combination is REST-layer only, in
 * [io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes.ProjectConfigRoutesGuardOrderTest]),
 * this file adds an explicit S5b sub-case covering exactly that combination, per the dispatch's
 * explicit correction instruction. Both S5 and S5b are `EXISTING-SURFACE`-adjacent in effect (they
 * bind to the new `expectedFingerprint`/`PreconditionFailed` surfaces `fab1b3ea` introduces, so
 * they are `NEW-SURFACE`; a narrowest revert is dropping the `expectedFingerprint` parameter from
 * `push` and its call site here).
 */
class ProjectConfigPushServiceGuardedTest {
    private lateinit var service: ProjectConfigPushService
    private lateinit var projectConfigRepository: SQLiteProjectConfigRepository
    private lateinit var rootId: UUID

    private val yamlA = "work_item_schemas:\n  a: {}\n"
    private val yamlB = "work_item_schemas:\n  b: {}\n"

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            val database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            val databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()

            val workItemRepository = SQLiteWorkItemRepository(databaseManager)
            projectConfigRepository = SQLiteProjectConfigRepository(databaseManager)

            val repositoryProvider = mockk<RepositoryProvider>(relaxed = true)
            every { repositoryProvider.workItemRepository() } returns workItemRepository
            every { repositoryProvider.projectConfigRepository() } returns projectConfigRepository

            service = ProjectConfigPushService(repositoryProvider)

            rootId = (workItemRepository.create(WorkItem(title = "Root", type = "project")) as Result.Success).data.id
            // Establish row A via a normal push so the rest of `push`'s pipeline (size/parse/
            // depth-0) is exercised the same way every other push test exercises it.
            val first = service.push(rootId, yamlA)
            assertTrue(first is ProjectConfigPushResult.Success, "setup push of A must succeed")
        }

    @Test
    fun `S5 push with a mismatched expectedFingerprint is rejected naming the server's current fingerprint`() =
        runBlocking {
            val bogusFingerprint = "0".repeat(64)

            val result = service.push(rootId, yamlB, expectedFingerprint = bogusFingerprint)

            assertTrue(result is ProjectConfigPushResult.PreconditionFailed)
            assertEquals(rootId, result.rootItemId)
            val currentFingerprint = projectConfigRepository.computeFingerprint(yamlA)
            assertEquals(currentFingerprint, result.currentFingerprint)

            val stored = (projectConfigRepository.get(rootId) as Result.Success).data
            assertEquals(yamlA, stored?.configYaml, "a rejected precondition must never overwrite the stored row")
        }

    @Test
    fun `S5b force=true does NOT bypass a mismatched expectedFingerprint`() =
        runBlocking {
            val bogusFingerprint = "0".repeat(64)

            val result = service.push(rootId, yamlB, force = true, expectedFingerprint = bogusFingerprint)

            assertTrue(
                result is ProjectConfigPushResult.PreconditionFailed,
                "force=true skips the rootId-mismatch and Superseded guards only — expectedFingerprint" +
                    " must still be enforced, per the push KDoc",
            )
            val stored = (projectConfigRepository.get(rootId) as Result.Success).data
            assertEquals(yamlA, stored?.configYaml, "force=true must not let a mismatched expectedFingerprint through")
        }
}
