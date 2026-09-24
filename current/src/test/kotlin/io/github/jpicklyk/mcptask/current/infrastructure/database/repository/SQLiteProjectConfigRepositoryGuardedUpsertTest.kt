package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Guarded compare-and-set upsert tests for item `fab1b3ea` ("Move the project-config fingerprint
 * compare-and-set inside the upsert transaction").
 *
 * Test-plan scenarios covered: S1, S2, S3, S4, S8 (see the item's `test-plan` note, queue phase).
 *
 * S3, S4 and S8 use a REAL file-backed SQLite database in WAL mode, per the test-plan's Harness
 * section — NOT H2, NOT the in-memory shared-cache `SQLiteRepositoryTestBase` pattern — because
 * they force a genuine two-connection race via [SQLiteProjectConfigRepository]'s
 * `beforeGuardedWrite` test hook, which runs a competing write on a second real JVM thread between
 * the guard read and the write, inside the transaction under test. Production uses WAL-mode
 * file-backed SQLite (see `SQLiteWorkItemRepositoryClaimTest`'s KDoc for the same distinction on
 * the claim path); an in-memory or H2 harness would not reproduce the lost-race retry path this
 * item's fix introduces.
 *
 * Oracle: `fab1b3ea`'s `diagnosis`/`test-plan` notes, and the `upsertGuarded` KDoc supplied in the
 * dispatch declarations — guard order (rejectSuperseded, then expectedFingerprint, then write) and
 * the lost-race retry re-evaluating both guards against the winner's row.
 */
class SQLiteProjectConfigRepositoryGuardedUpsertTest {
    private val managers = mutableListOf<DatabaseManager>()

    @AfterEach
    fun tearDown() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }

    /** Real file-backed SQLite (WAL) DatabaseManager per the test-plan's Harness section. */
    private fun buildFileBackedManager(tempDir: Path): DatabaseManager {
        val dbPath = tempDir.resolve("cfg-${System.nanoTime()}.db").toString()
        val manager = DatabaseManager()
        assertTrue(manager.initialize(dbPath), "DatabaseManager.initialize() should succeed for a fresh temp file")
        assertTrue(manager.updateSchema(), "DatabaseManager.updateSchema() should succeed")
        managers += manager
        return manager
    }

    private suspend fun createRoot(workItemRepository: SQLiteWorkItemRepository): UUID {
        val created = workItemRepository.create(WorkItem(title = "Project Root", type = "project"))
        assertIs<Result.Success<WorkItem>>(created)
        return created.data.id
    }

    // ──────────────────────────────────────────────
    // S1 / S2 — happy path, no race
    // ──────────────────────────────────────────────

    @Test
    fun `S1 upsertGuarded with a matching expectedFingerprint applies and supersedes the prior content`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manager = buildFileBackedManager(tempDir)
        val workItemRepository = SQLiteWorkItemRepository(manager)
        val repo = SQLiteProjectConfigRepository(manager)
        val rootId = createRoot(workItemRepository)

        val yamlA = "work_item_schemas:\n  a: {}\n"
        val yamlB = "work_item_schemas:\n  b: {}\n"
        val fpA = (repo.upsert(rootId, yamlA) as Result.Success).data.fingerprint

        val result = repo.upsertGuarded(rootId, yamlB, expectedFingerprint = fpA)

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(outcome)
        assertEquals(yamlB, outcome.config.configYaml)

        assertEquals(
            FingerprintRelation.SUPERSEDED,
            (repo.classifyFingerprint(rootId, fpA) as Result.Success).data,
            "the replaced fingerprint must now classify as SUPERSEDED",
        )
    }

    @Test
    fun `S2 upsertGuarded ignores expectedFingerprint on a first push (no row yet)`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manager = buildFileBackedManager(tempDir)
        val workItemRepository = SQLiteWorkItemRepository(manager)
        val repo = SQLiteProjectConfigRepository(manager)
        val rootId = createRoot(workItemRepository)

        val yaml = "work_item_schemas:\n  first: {}\n"
        // Deliberately a bogus expectedFingerprint — a first push must ignore it since no row
        // exists yet (per the upsertGuarded KDoc: "IGNORED when no row exists yet").
        val result = repo.upsertGuarded(rootId, yaml, expectedFingerprint = "0".repeat(64))

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(outcome)
        assertEquals(yaml, outcome.config.configYaml)
    }

    // ──────────────────────────────────────────────
    // S3 — RACE: a lost race under expectedFingerprint retries and re-evaluates against the winner
    // ──────────────────────────────────────────────

    @Test
    fun `S3 a lost race under expectedFingerprint retries and returns PreconditionFailed against the winner`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manager = buildFileBackedManager(tempDir)
        val workItemRepository = SQLiteWorkItemRepository(manager)
        val plainRepo = SQLiteProjectConfigRepository(manager)
        val rootId = createRoot(workItemRepository)

        val yamlA = "work_item_schemas:\n  a: {}\n"
        val yamlB = "work_item_schemas:\n  b: {}\n"
        val yamlC = "work_item_schemas:\n  c: {}\n"
        val fpA = (plainRepo.upsert(rootId, yamlA) as Result.Success).data.fingerprint

        val competingRepo = SQLiteProjectConfigRepository(manager)
        val fired = AtomicBoolean(false)
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                // Fire only on the FIRST guard-read (retries must not re-trigger a nested race).
                if (fired.compareAndSet(false, true)) {
                    thread {
                        runBlocking { competingRepo.upsertGuarded(rid, yamlC, expectedFingerprint = fpA) }
                    }.join()
                }
            }

        val result = repoX.upsertGuarded(rootId, yamlB, expectedFingerprint = fpA)

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.PreconditionFailed>(outcome)
        val fpC = plainRepo.computeFingerprint(yamlC)
        assertEquals(fpC, outcome.currentFingerprint, "X must be told C's fingerprint — the winner's row")

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlC, stored?.configYaml, "C (the winner) must be the row actually stored")

        val fpB = plainRepo.computeFingerprint(yamlB)
        assertEquals(
            FingerprintRelation.UNKNOWN,
            (plainRepo.classifyFingerprint(rootId, fpB) as Result.Success).data,
            "B (X's rejected content) was never written, so it must not appear in history either",
        )
    }

    // ──────────────────────────────────────────────
    // S4 — RACE: a lost race under rejectSuperseded retries and re-evaluates against the winner
    // ──────────────────────────────────────────────

    @Test
    fun `S4 a lost race under rejectSuperseded retries and returns Superseded against the winner`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manager = buildFileBackedManager(tempDir)
        val workItemRepository = SQLiteWorkItemRepository(manager)
        val plainRepo = SQLiteProjectConfigRepository(manager)
        val rootId = createRoot(workItemRepository)

        val yamlA = "work_item_schemas:\n  a: {}\n"
        val yamlB = "work_item_schemas:\n  b: {}\n"
        plainRepo.upsert(rootId, yamlA)

        val competingRepo = SQLiteProjectConfigRepository(manager)
        val fired = AtomicBoolean(false)
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                if (fired.compareAndSet(false, true)) {
                    thread { runBlocking { competingRepo.upsertGuarded(rid, yamlB) } }.join()
                }
            }

        // X re-pushes A's OWN (now stale) bytes with the fast-forward guard on — the guard read
        // sees A as current, but Y commits B before X's write lands.
        val result = repoX.upsertGuarded(rootId, yamlA, rejectSuperseded = true)

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Superseded>(outcome)

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlB, stored?.configYaml, "B (the winner) must be the row actually stored")
        assertEquals(
            stored?.updatedAt,
            outcome.currentUpdatedAt,
            "Superseded.currentUpdatedAt must name the winner's updatedAt",
        )
    }

    // ──────────────────────────────────────────────
    // S8 — RACE, unguarded: no guard means no spurious rejection under contention
    // ──────────────────────────────────────────────

    @Test
    fun `S8 an unguarded write during a race still applies X's own content (no over-rejection)`(
        @TempDir tempDir: Path,
    ) = runBlocking {
        val manager = buildFileBackedManager(tempDir)
        val workItemRepository = SQLiteWorkItemRepository(manager)
        val plainRepo = SQLiteProjectConfigRepository(manager)
        val rootId = createRoot(workItemRepository)

        val yamlA = "work_item_schemas:\n  a: {}\n"
        val yamlB = "work_item_schemas:\n  b: {}\n"
        val yamlC = "work_item_schemas:\n  c: {}\n"
        plainRepo.upsert(rootId, yamlA)

        val competingRepo = SQLiteProjectConfigRepository(manager)
        val fired = AtomicBoolean(false)
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                if (fired.compareAndSet(false, true)) {
                    thread { runBlocking { competingRepo.upsertGuarded(rid, yamlB) } }.join()
                }
            }

        // No guards at all (expectedFingerprint = null, rejectSuperseded = false — the defaults):
        // an unguarded write must not spuriously reject just because a race happened underneath it.
        val result = repoX.upsertGuarded(rootId, yamlC)

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(outcome)
        assertEquals(yamlC, outcome.config.configYaml)

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlC, stored?.configYaml, "X's own content must win — unguarded means unconditional")
        assertNotEquals(yamlB, stored?.configYaml)

        val fpB = plainRepo.computeFingerprint(yamlB)
        assertEquals(
            FingerprintRelation.SUPERSEDED,
            (plainRepo.classifyFingerprint(rootId, fpB) as Result.Success).data,
            "Y's content (B) was written and then overwritten, so it must be SUPERSEDED, not vanish untracked",
        )
    }
}
