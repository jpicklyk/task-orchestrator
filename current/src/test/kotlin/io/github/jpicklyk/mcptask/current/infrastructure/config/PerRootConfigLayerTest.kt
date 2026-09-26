package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independently authored against the frozen `test-plan` note on item `df7d579a` (scenarios S6,
 * S11). Exercises [PerRootConfigService]'s new [PerRootConfigService.layer] port method — the
 * DB-backed harness style mirrors [PerRootConfigServiceTest]'s existing H2 setup. S11's
 * cold-read-error / warm-LKG cases reuse the `FailableProjectConfigRepository` wrapper technique
 * from `AdvanceItemToolConfigUnavailableTest` (a `ProjectConfigRepository` delegate whose
 * `getFingerprint` can be switched to fail), authored fresh here since that fixture is file-private
 * there and not a shared/importable fake.
 */
class PerRootConfigLayerTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteProjectConfigRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var service: PerRootConfigService
    private lateinit var rootItemId: UUID

    @BeforeEach
    fun setUp() =
        runBlocking {
            val dbName = "test_${System.nanoTime()}"
            database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
            databaseManager = DatabaseManager(database)
            DirectDatabaseSchemaManager().updateSchema()
            repository = SQLiteProjectConfigRepository(databaseManager)
            workItemRepository = SQLiteWorkItemRepository(databaseManager)
            service = PerRootConfigService(repository)

            val root = WorkItem(title = "Project Root")
            workItemRepository.create(root)
            rootItemId = root.id
        }

    // ──────────────────────────────────────────────
    // S6 — getNoteLimitsMode via the per-root doc
    // ──────────────────────────────────────────────

    @Test
    fun `S6 getNoteLimitsMode is null when no config row exists`() =
        runBlocking {
            assertNull(service.getNoteLimitsMode(rootItemId))
        }

    @Test
    fun `S6 getNoteLimitsMode is warn when note_limits is present as an empty map`() =
        runBlocking {
            repository.upsert(rootItemId, "note_limits: {}\n")
            assertEquals("warn", service.getNoteLimitsMode(rootItemId))
        }

    // ──────────────────────────────────────────────
    // S6 — layer() single-pass view
    // ──────────────────────────────────────────────

    @Test
    fun `S6 layer returns null when no config row exists`() =
        runBlocking {
            assertNull(service.layer(rootItemId))
        }

    @Test
    fun `S6 layer returns PER_ROOT source with the row fingerprint and the parsed schemas`() =
        runBlocking {
            val yaml =
                """
                work_item_schemas:
                  bug-fix:
                    notes:
                      - key: repro-steps
                        role: queue
                        required: true
                        description: "Repro steps"
                """.trimIndent()
            repository.upsert(rootItemId, yaml)

            val layer = service.layer(rootItemId)
            assertNotNull(layer)
            assertEquals(ConfigSource.PER_ROOT, layer.source)
            assertEquals(service.getFingerprint(rootItemId), layer.fingerprint)
            assertEquals(
                "repro-steps",
                layer.document.workItemSchemas["bug-fix"]
                    ?.notes
                    ?.get(0)
                    ?.key
            )
        }

    // ──────────────────────────────────────────────
    // S11 — layer() failure modes
    // ──────────────────────────────────────────────

    @Test
    fun `S11 layer returns null for unparseable per-root YAML`() =
        runBlocking {
            repository.upsert(rootItemId, "work_item_schemas: [\ninvalid yaml: :\n  - broken")
            assertNull(service.layer(rootItemId))
        }

    @Test
    fun `S11 layer throws PerRootConfigUnavailableException on a cold read error`() =
        runBlocking {
            val wrapper = FailableProjectConfigRepository(repository)
            wrapper.failFingerprint = true
            val failingService = PerRootConfigService(wrapper)

            val ex = assertFailsWith<PerRootConfigUnavailableException> { failingService.layer(rootItemId) }
            assertEquals(rootItemId, ex.rootId)
        }

    @Test
    fun `S11 layer serves the last-known-good layer when a warm cache hits a read error`() =
        runBlocking {
            val wrapper = FailableProjectConfigRepository(repository)
            val warmService = PerRootConfigService(wrapper)

            repository.upsert(
                rootItemId,
                """
                work_item_schemas:
                  bug-fix:
                    notes:
                      - key: repro-steps
                        role: queue
                        required: true
                        description: "Repro steps"
                """.trimIndent(),
            )
            val warm = warmService.layer(rootItemId)
            assertNotNull(warm, "sanity: warm cache must be populated before the failure is injected")

            wrapper.failFingerprint = true
            val duringFailure = warmService.layer(rootItemId)

            assertNotNull(duringFailure, "a warm cache must serve the last-known-good layer, not throw or go null")
            assertEquals(warm.document, duringFailure.document)
            assertEquals(warm.fingerprint, duringFailure.fingerprint)
        }
}

private class FailableProjectConfigRepository(
    private val delegate: ProjectConfigRepository,
) : ProjectConfigRepository by delegate {
    @Volatile var failFingerprint: Boolean = false

    override suspend fun getFingerprint(rootItemId: UUID) =
        if (failFingerprint) {
            Result.Error(RepositoryError.DatabaseError("x"))
        } else {
            delegate.getFingerprint(rootItemId)
        }
}
