package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.FingerprintRelation
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
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

/**
 * Guarded compare-and-set upsert tests for item `fab1b3ea` ("Move the project-config fingerprint
 * compare-and-set inside the upsert transaction").
 *
 * Test-plan scenarios covered: S1, S2, S3, S4, S8 (see the item's `test-plan` note, queue phase).
 *
 * S3, S4 and S8 use a REAL file-backed SQLite database in WAL mode, per the test-plan's Harness
 * section — NOT an in-memory shared-cache database — because
 * they force a genuine two-connection race via [SQLiteProjectConfigRepository]'s
 * `beforeGuardedWrite` test hook, which starts a competing write on a second real JVM thread
 * during X's transaction (after the guard read, before the conditional write). Production uses
 * WAL-mode file-backed SQLite (see `SQLiteWorkItemRepositoryClaimTest`'s KDoc for the same
 * distinction on the claim path); an in-memory harness would not reproduce this race.
 *
 * Since transactions begin IMMEDIATE (item `1a400d81`), X — the writer whose `upsertGuarded` call
 * this test drives directly — always acquires the write lock at BEGIN and so always wins: X's own
 * outcome is `Applied` in every race scenario below (S3, S4, S8). The `beforeGuardedWrite` hook
 * starts the competitor thread but does NOT join it inside X's transaction (joining there would
 * block X on its own lock until the competitor's BEGIN IMMEDIATE times out against
 * `busy_timeout`). Each test joins the competitor thread AFTER X's `upsertGuarded` returns, so the
 * competitor's own guard evaluates against X's already-committed row — that competitor's outcome
 * is what actually exercises `rejectSuperseded`/`expectedFingerprint` rejection in these tests now.
 *
 * Oracle: `fab1b3ea`'s `diagnosis`/`test-plan` notes, and the `upsertGuarded` KDoc supplied in the
 * dispatch declarations — guard order (rejectSuperseded, then expectedFingerprint, then write); and
 * item `1a400d81`'s contract-change sweep, which reordered these scenarios' winner under IMMEDIATE
 * transactions while keeping their guard-evaluation intent (guards checked against the row that
 * actually won).
 */
class SQLiteProjectConfigRepositoryGuardedUpsertTest {
    private val databases = mutableListOf<SqliteTestDatabase>()

    @AfterEach
    fun tearDown() {
        databases.forEach { it.close() }
        databases.clear()
    }

    /** Real file-backed SQLite (WAL, production DatabaseManager) per the test-plan's Harness section. */
    @Suppress("UNUSED_PARAMETER")
    private fun buildFileBackedManager(tempDir: Path): DatabaseManager {
        val db = SqliteTestDatabase.open()
        databases += db
        return db.databaseManager
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
    // S3 — RACE under IMMEDIATE: X holds the write lock from BEGIN and always wins; the late
    // competitor evaluates its guard against X's already-committed row.
    // ──────────────────────────────────────────────

    @Test
    fun `S3 X wins the race under IMMEDIATE, the late competitor under expectedFingerprint gets PreconditionFailed against X's row`(
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
        var competingThread: Thread? = null
        var competingResult: Result<GuardedUpsertOutcome>? = null
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                // Fire only on the FIRST guard-read (retries must not re-trigger a nested race).
                // Start the competitor concurrently but do NOT join it here: under IMMEDIATE, X
                // already holds the write lock from BEGIN (acquired before this hook even runs),
                // so the competitor's own BEGIN IMMEDIATE blocks on busy_timeout until X commits —
                // joining inside X's transaction would just block X on its own lock. Join AFTER
                // X's upsertGuarded returns instead, so the competitor runs against X's committed
                // row.
                if (fired.compareAndSet(false, true)) {
                    competingThread =
                        thread {
                            competingResult =
                                runBlocking { competingRepo.upsertGuarded(rid, yamlC, expectedFingerprint = fpA) }
                        }
                }
            }

        val result = repoX.upsertGuarded(rootId, yamlB, expectedFingerprint = fpA)
        competingThread?.join()

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(outcome, "X holds the write lock from BEGIN under IMMEDIATE, so X always wins")
        assertEquals(yamlB, outcome.config.configYaml)

        val fpB = plainRepo.computeFingerprint(yamlB)
        val loserOutcome = competingResult
        assertIs<Result.Success<GuardedUpsertOutcome>>(loserOutcome, "the late competitor must still complete")
        val loserData = loserOutcome.data
        assertIs<GuardedUpsertOutcome.PreconditionFailed>(loserData)
        assertEquals(fpB, loserData.currentFingerprint, "the competitor must be told B's fingerprint — X's (the winner's) row")

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlB, stored?.configYaml, "B (X's, the winner's content) must be the row actually stored")

        val fpC = plainRepo.computeFingerprint(yamlC)
        assertEquals(
            FingerprintRelation.UNKNOWN,
            (plainRepo.classifyFingerprint(rootId, fpC) as Result.Success).data,
            "C (the competitor's rejected content) was never written, so it must not appear in history either",
        )
    }

    // ──────────────────────────────────────────────
    // S4 — RACE under IMMEDIATE: X wins and commits; the late competitor under rejectSuperseded
    // gets Superseded because its own (now stale) content was pushed out of currency by X.
    // ──────────────────────────────────────────────

    @Test
    fun `S4 X wins the race under IMMEDIATE, the late competitor under rejectSuperseded gets Superseded against X's row`(
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
        var competingThread: Thread? = null
        var competingResult: Result<GuardedUpsertOutcome>? = null
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                // See S3's comment: start the competitor but join it only after X returns, so it
                // races against X's committed row rather than blocking X on its own IMMEDIATE lock.
                // The competitor re-pushes A (the ORIGINAL content) with the fast-forward guard on;
                // once X has committed B, A is no longer current but IS in B's fingerprint history
                // (B superseded A), so the guard must reject it as Superseded.
                if (fired.compareAndSet(false, true)) {
                    competingThread =
                        thread {
                            competingResult =
                                runBlocking { competingRepo.upsertGuarded(rid, yamlA, rejectSuperseded = true) }
                        }
                }
            }

        // X writes B, unguarded — it always applies since it holds the lock first.
        val result = repoX.upsertGuarded(rootId, yamlB)
        competingThread?.join()

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(outcome, "X holds the write lock from BEGIN under IMMEDIATE, so X always wins")
        assertEquals(yamlB, outcome.config.configYaml)

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlB, stored?.configYaml, "B (X's, the winner's content) must be the row actually stored")

        val loserOutcome = competingResult
        assertIs<Result.Success<GuardedUpsertOutcome>>(loserOutcome, "the late competitor must still complete")
        val loserData = loserOutcome.data
        assertIs<GuardedUpsertOutcome.Superseded>(loserData)
        assertEquals(
            stored?.updatedAt,
            loserData.currentUpdatedAt,
            "Superseded.currentUpdatedAt must name X's (the winner's) updatedAt",
        )
    }

    // ──────────────────────────────────────────────
    // S8 — RACE under IMMEDIATE, unguarded: the late competitor's unguarded write still applies
    // cleanly after X, and X's superseded content is tracked in history, not lost.
    // ──────────────────────────────────────────────

    @Test
    fun `S8 X applies first under IMMEDIATE, the late unguarded competitor still applies after X and supersedes it`(
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
        var competingThread: Thread? = null
        var competingResult: Result<GuardedUpsertOutcome>? = null
        val repoX =
            SQLiteProjectConfigRepository(manager) { rid ->
                // See S3's comment: start the competitor but join it only after X returns.
                if (fired.compareAndSet(false, true)) {
                    competingThread =
                        thread {
                            competingResult = runBlocking { competingRepo.upsertGuarded(rid, yamlB) }
                        }
                }
            }

        // No guards at all (expectedFingerprint = null, rejectSuperseded = false — the defaults)
        // on either side: neither write may spuriously reject just because a race happened.
        val result = repoX.upsertGuarded(rootId, yamlC)
        competingThread?.join()

        assertIs<Result.Success<GuardedUpsertOutcome>>(result)
        val outcome = result.data
        assertIs<GuardedUpsertOutcome.Applied>(
            outcome,
            "X holds the write lock from BEGIN under IMMEDIATE, so X always wins the race to write first",
        )
        assertEquals(yamlC, outcome.config.configYaml)

        val loserOutcome = competingResult
        assertIs<Result.Success<GuardedUpsertOutcome>>(loserOutcome, "the late competitor must still complete")
        val loserData = loserOutcome.data
        assertIs<GuardedUpsertOutcome.Applied>(loserData, "unguarded means unconditional — Y applies even though it ran after X")
        assertEquals(yamlB, loserData.config.configYaml)

        val stored = (plainRepo.get(rootId) as Result.Success).data
        assertEquals(yamlB, stored?.configYaml, "Y (the late, unguarded write) ends up as the current row — it wrote last")
        assertNotEquals(yamlC, stored?.configYaml)

        val fpC = plainRepo.computeFingerprint(yamlC)
        assertEquals(
            FingerprintRelation.SUPERSEDED,
            (plainRepo.classifyFingerprint(rootId, fpC) as Result.Success).data,
            "X's content (C) was written and then overwritten by Y, so it must be SUPERSEDED, not vanish untracked",
        )
    }
}
