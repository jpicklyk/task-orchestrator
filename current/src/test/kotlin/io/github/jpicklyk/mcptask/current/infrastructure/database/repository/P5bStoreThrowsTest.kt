package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.error.VersionConflictException
import io.github.jpicklyk.mcptask.current.domain.model.GuardedUpsertOutcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.LeaseAcquireResult
import io.github.jpicklyk.mcptask.current.infrastructure.database.PersistenceFaults
import io.github.jpicklyk.mcptask.current.infrastructure.database.RawWriterLock
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent P5b tests (item c01d2e90) for store-level semantics that changed with "stores throw":
 * S4 (version conflict is a thrown VersionConflictException carrying expected != actual, re-read in the same unit;
 * a missing row is null), S7 (a lease acquire under a held writer never faults), S8 (upsertGuarded makes one attempt
 * and a lost compare-and-set surfaces as a failure, never as "exhausted").
 *
 * Oracles: S4 - task-scope D3 and carry-in F5 (ErrorDetail.VersionConflict requires expected != actual; the re-read
 * happens inside the same unit; "missing row is NotFound today, update returns WorkItem?, null"); S7 - task-scope D6
 * (the store retry and DBError arms are deleted; contention is handled by the unit, so acquire is Success/Contended);
 * S8 - task-scope D6 and carry-in TP3 (one attempt; IllegalStateException out of the store, no "exhausted" text).
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class P5bStoreThrowsTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    // ---------------------------------------------------------------- S4
    @Test
    fun `S4 a stale update inside a unit becomes a VERSION_CONFLICT error carrying expected and the committed actual`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()
            val v1 = repo.create(WorkItem(title = "S4 original", depth = 0))
            val uow = db.unitOfWork()
            val committed =
                assertIs<Outcome.Ok<WorkItem?>>(uow.write("S4.bump") { Outcome.Ok(repo.update(v1.copy(title = "S4 bumped"))) }).value
            assertNotNull(committed, "the first update (current version) must succeed")
            assertNotEquals(v1.version, committed.version, "a successful update advances the stored version")

            val result = uow.write("S4.stale") { Outcome.Ok(repo.update(v1.copy(title = "S4 stale"))) }

            val err =
                assertIs<Outcome.Err>(result, "inside a unit the conflict is translated to a distinct VERSION_CONFLICT error: $result")
            assertEquals(ErrorCode.VERSION_CONFLICT, err.error.code)
            val detail = assertIs<ErrorDetail.VersionConflict>(err.error.detail)
            assertEquals(v1.id.toString(), detail.id)
            assertEquals(v1.version, detail.expected, "expected = the version the caller held")
            assertEquals(committed.version, detail.actual, "actual = the version currently stored, read in the same unit")
            assertNotEquals(detail.expected, detail.actual)
            assertTrue(
                "WorkItem was modified by another transaction (version mismatch)" in err.error.message,
                "3.x MCP text: ${err.error.message}"
            )
            assertEquals("S4 bumped", repo.getById(v1.id)?.title, "the stale write must not land")
        }

    @Test
    fun `S4 the stale update still throws the same conflict when called with no ambient unit`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()
            val v1 = repo.create(WorkItem(title = "S4 outside", depth = 0))
            assertNotNull(repo.update(v1.copy(title = "S4 outside bumped")))
            val e = assertFailsWith<VersionConflictException> { repo.update(v1.copy(title = "S4 outside stale")) }
            assertEquals(v1.version, e.expected)
            assertNotEquals(e.expected, e.actual)
        }

    @Test
    fun `S4 updating a row that does not exist returns null and does not throw`(): Unit =
        runBlocking {
            val repo = db.repositoryProvider().workItemRepository()
            val ghost = WorkItem(id = UUID.randomUUID(), title = "S4 ghost", depth = 0)
            val result = db.unitOfWork().write("S4.missing") { Outcome.Ok(repo.update(ghost)) }
            assertEquals(Outcome.Ok(null), result)
            assertNull(repo.getById(ghost.id))
        }

    @Test
    fun `S4 the persistence translator maps VersionConflictException to VERSION_CONFLICT carrying both versions`() {
        val id = UUID.randomUUID()
        val translated = assertNotNull(PersistenceFaults.translate(VersionConflictException(id, 1L, 2L)))
        assertEquals(ErrorCode.VERSION_CONFLICT, translated.code)
        assertEquals(ErrorDetail.VersionConflict(EntityKind.ITEM, id.toString(), 1L, 2L), translated.detail)
    }

    @Test
    fun `S4 a VersionConflictException with equal versions is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { VersionConflictException(UUID.randomUUID(), 3L, 3L) }
    }

    // ---------------------------------------------------------------- S7
    @Test
    @Tag("serial")
    fun `S7 a lease acquire under a held writer lock ends in Success, never a fault`(): Unit =
        runBlocking {
            val provider = db.repositoryProvider()
            val holder = provider.workItemRepository().create(WorkItem(title = "S7 holder", depth = 0))
            val uow = db.unitOfWork()

            val result =
                RawWriterLock(db.jdbcUrl, 1_500).start().use {
                    uow.write("S7.acquire") {
                        Outcome.Ok(repositories.resourceLeaseRepository().acquireAll(holder.id, "s7-actor", listOf("s7-key" to 900)))
                    }
                }

            val ok = assertIs<Outcome.Ok<LeaseAcquireResult>>(result, "the unit absorbs writer contention: $result")
            val success = assertIs<LeaseAcquireResult.Success>(ok.value, "an uncontended key must be granted: ${ok.value}")
            assertEquals(listOf("s7-key"), success.leases.map { it.resourceKey })
        }

    // ---------------------------------------------------------------- S8
    private suspend fun seedRoot(): UUID =
        db
            .repositoryProvider()
            .workItemRepository()
            .create(WorkItem(title = "S8 root", type = "project"))
            .id

    @Test
    fun `S8 a lost compare-and-set makes exactly one attempt and surfaces a failure that is not exhaustion`(): Unit =
        runBlocking {
            val rootId = seedRoot()
            val seedRepo = SQLiteProjectConfigRepository(dm)
            val fpA = seedRepo.upsert(rootId, "work_item_schemas:\n  a: {}\n").fingerprint
            val hookCalls = AtomicInteger()
            val racing =
                SQLiteProjectConfigRepository(dm) {
                    hookCalls.incrementAndGet()
                    dm.writeTx("S8.tamper") { exec("UPDATE project_config SET fingerprint = 'tampered', config_yaml = 'tampered: true'") }
                }

            val thrown =
                assertFailsWith<IllegalStateException> {
                    db.unitOfWork().write("S8.guarded") {
                        Outcome.Ok(racing.upsertGuarded(rootId, "work_item_schemas:\n  b: {}\n", expectedFingerprint = fpA))
                    }
                }

            assertEquals(1, hookCalls.get(), "the guarded write is attempted exactly once (no retry loop)")
            assertFalse("exhausted" in thrown.message.orEmpty().lowercase(), "no retry-exhaustion text: ${thrown.message}")
            val stored = assertNotNull(seedRepo.get(rootId))
            assertEquals(
                "work_item_schemas:\n  a: {}\n",
                stored.configYaml,
                "the unit rolled back as a whole: neither the competitor's tamper nor the losing write is committed"
            )
        }

    @Test
    fun `S8 control - without interference the same guarded upsert is applied after one hook call`(): Unit =
        runBlocking {
            val rootId = seedRoot()
            val seedRepo = SQLiteProjectConfigRepository(dm)
            val fpA = seedRepo.upsert(rootId, "work_item_schemas:\n  a: {}\n").fingerprint
            val hookCalls = AtomicInteger()
            val observing = SQLiteProjectConfigRepository(dm) { hookCalls.incrementAndGet() }

            val result =
                db.unitOfWork().write("S8.control") {
                    Outcome.Ok(observing.upsertGuarded(rootId, "work_item_schemas:\n  b: {}\n", expectedFingerprint = fpA))
                }

            val applied = assertIs<GuardedUpsertOutcome.Applied>(assertIs<Outcome.Ok<GuardedUpsertOutcome>>(result).value)
            assertEquals("work_item_schemas:\n  b: {}\n", applied.config.configYaml)
            assertEquals(1, hookCalls.get())
        }
}
