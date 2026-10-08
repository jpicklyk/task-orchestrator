package io.github.jpicklyk.mcptask.current.infrastructure.database

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.EffectiveConfigResolver
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ServiceBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.service.NoOpNoteSchemaService
import io.github.jpicklyk.mcptask.current.application.service.NoOpStatusLabelService
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.EntityKind
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixTemplates
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.PerRootConfigUnavailableException
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.ProjectConfigRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.writeTx
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Independent tests for the UnitRunner boundary (item 9343ad8d): BUSY retry (S3), error translation (S9), config
 * inside a unit (S12), the outside-unit write counter (S13), the held-writer checkout timeout (S15) and the nullable
 * Duplicate/NotFound details (carry-in 1). Oracle: plans/v4-phase1-core.md section 3.2 lines 69-101 and the
 * task-scope translation table (section 4 of the declarations); the pools use a FIXED 1 s busy_timeout and a 2 s
 * connection checkout (carry-in 4), the unit deadline is adjustable through UnitRunner.deadline.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class UnitRunnerTranslationTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val db get() = sqlite.db
    private val dm get() = db.databaseManager

    // ---------------------------------------------------------------- S3
    @Test
    fun `S3 a write blocked by a raw lock shorter than the deadline retries and commits`(): Unit =
        runBlocking {
            db.createProbeTables()
            val uow = db.uow()
            RawWriterLock(db.jdbcUrl, holdMs = 1_500).start().use {
                val t0 = System.nanoTime()
                val result =
                    uow.write("S3.retry") {
                        dm.writeTx("S3.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        Outcome.Ok("committed")
                    }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                assertEquals(Outcome.Ok("committed"), result)
                // The first BEGIN IMMEDIATE waits the pool's fixed 1 s busy_timeout and fails; only a retry can succeed.
                assertTrue(elapsedMs >= 900, "the write cannot start before the lock is released, took ${elapsedMs}ms")
            }
            assertEquals(1, dm.countRows("p5a_probe"))
        }

    @Test
    fun `S3 a raw lock held past the unit deadline yields UNAVAILABLE with a retry hint`(): Unit =
        runBlocking {
            db.createProbeTables()
            dm.units.deadline = 500.milliseconds
            val uow = db.uow()
            RawWriterLock(db.jdbcUrl, holdMs = 3_500).start().use {
                val t0 = System.nanoTime()
                val result =
                    uow.write("S3.deadline") {
                        dm.writeTx("S3.touch") { exec("SELECT 1") { } }
                        Outcome.Ok(Unit)
                    }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val err = assertIs<Outcome.Err>(result).error
                assertEquals(ErrorCode.UNAVAILABLE, err.code)
                val detail = assertIs<ErrorDetail.Unavailable>(err.detail)
                assertEquals(1_000L, detail.retryAfterMs, "BUSY exhaustion hint is 1000 ms (task-scope section 4)")
                assertTrue(elapsedMs < 3_000, "must give up near the deadline, not wait for the lock: ${elapsedMs}ms")
            }
        }

    @Test
    fun `S3 a unit that keeps hitting BUSY is retried until the deadline then UNAVAILABLE`(): Unit =
        runBlocking {
            dm.units.deadline = 300.milliseconds
            val attempts = AtomicInteger()
            val result =
                db.uow().write<Unit>("S3.always") {
                    attempts.incrementAndGet()
                    throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                }
            val err = assertIs<Outcome.Err>(result).error
            assertEquals(ErrorCode.UNAVAILABLE, err.code)
            assertEquals(1_000L, assertIs<ErrorDetail.Unavailable>(err.detail).retryAfterMs)
            assertTrue(attempts.get() >= 2, "BUSY re-runs the whole unit, saw ${attempts.get()} attempt(s)")
        }

    @Test
    fun `S3 SQLITE_LOCKED is retried like BUSY and succeeds on the next attempt`(): Unit =
        runBlocking {
            val attempts = AtomicInteger()
            val result =
                db.uow().write("S3.locked") {
                    if (attempts.incrementAndGet() == 1) throw SQLiteException("simulated locked", SQLiteErrorCode.SQLITE_LOCKED)
                    Outcome.Ok("second attempt")
                }
            assertEquals(Outcome.Ok("second attempt"), result)
            assertEquals(2, attempts.get())
        }

    @Test
    fun `cancelling while the unit waits between BUSY attempts rethrows CancellationException promptly`(): Unit =
        runBlocking {
            val uow = db.uow()
            val firstAttempt = CompletableDeferred<Unit>()
            var outcome: Any? = null
            var caught: Throwable? = null
            val job =
                launch(Dispatchers.Default) {
                    try {
                        outcome =
                            uow.write<Unit>("S3.cancelDelay") {
                                firstAttempt.complete(Unit)
                                throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                            }
                    } catch (e: CancellationException) {
                        caught = e
                        throw e
                    }
                }
            firstAttempt.await()
            withTimeout(3_000) { job.cancelAndJoin() }
            assertNull(outcome, "a cancelled unit must not surface an Err")
            assertTrue(caught is CancellationException, "expected CancellationException, got $caught")
            assertEquals(Outcome.Ok("free"), uow.write("S3.after") { Outcome.Ok("free") })
        }

    // ---------------------------------------------------------------- S9
    @Test
    fun `S9 a duplicate primary key becomes DUPLICATE keeping the SQL message`(): Unit =
        runBlocking {
            db.createProbeTables()
            val result =
                db.uow().write<Unit>("S9.dup") {
                    dm.writeTx("S9.dup.1") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                    dm.writeTx("S9.dup.2") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 2)") }
                    Outcome.Ok(Unit)
                }
            val err = assertIs<Outcome.Err>(result).error
            assertEquals(ErrorCode.DUPLICATE, err.code)
            assertIs<ErrorDetail.Duplicate>(err.detail)
            assertNull(
                (err.detail as ErrorDetail.Duplicate).existingId,
                "a raw constraint translation cannot know the existing id (carry-in 1)"
            )
            assertTrue("constraint" in err.message.lowercase(), "carry-in 9a: the innermost SQL message is kept, got: ${err.message}")
            assertEquals(0, dm.countRows("p5a_probe"), "the unit rolled back, including the first insert")
        }

    @Test
    fun `S9 a missing foreign key target becomes NOT_FOUND`(): Unit =
        runBlocking {
            db.createProbeTables()
            val result =
                db.uow().write("S9.fk") {
                    dm.writeTx("S9.fk.row") { exec("INSERT INTO p5a_child (id, pid) VALUES (1, 99)") }
                    Outcome.Ok(Unit)
                }
            val err = assertIs<Outcome.Err>(result).error
            assertEquals(ErrorCode.NOT_FOUND, err.code)
            val detail = assertIs<ErrorDetail.NotFound>(err.detail)
            assertEquals(EntityKind.ITEM, detail.kind)
            assertNull(detail.id, "a raw FK translation cannot know the id (carry-in 1)")
            assertTrue("foreign key" in err.message.lowercase(), "carry-in 9a: the innermost SQL message is kept, got: ${err.message}")
        }

    @Test
    fun `S9 a READONLY fault becomes INTERNAL and keeps the innermost message through the cause chain`(): Unit =
        runBlocking {
            val result =
                db.uow().write<Unit>("S9.ro") {
                    throw SQLException("outer wrapper", SQLiteException("innermost-readonly-marker", SQLiteErrorCode.SQLITE_READONLY))
                }
            val err = assertIs<Outcome.Err>(result).error
            assertEquals(ErrorCode.INTERNAL, err.code)
            assertTrue("innermost-readonly-marker" in err.message, "innermost SQL message must survive, got: ${err.message}")
        }

    @Test
    fun `S9 any other SQL exception becomes INTERNAL`(): Unit =
        runBlocking {
            val result = db.uow().write<Unit>("S9.other") { throw SQLException("plain sql failure") }
            assertEquals(ErrorCode.INTERNAL, assertIs<Outcome.Err>(result).error.code)
        }

    @Test
    fun `S9 a pool checkout timeout becomes UNAVAILABLE at once with a 2000 ms hint`(): Unit =
        runBlocking {
            val attempts = AtomicInteger()
            val t0 = System.nanoTime()
            val result =
                db.uow().write<Unit>("S9.pool") {
                    attempts.incrementAndGet()
                    throw SQLTransientConnectionException("to-writer - Connection is not available, request timed out")
                }
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            val err = assertIs<Outcome.Err>(result).error
            assertEquals(ErrorCode.UNAVAILABLE, err.code)
            assertEquals(2_000L, assertIs<ErrorDetail.Unavailable>(err.detail).retryAfterMs)
            assertEquals(1, attempts.get(), "a pool timeout is not retried")
            assertTrue(elapsedMs < 2_000, "UNAVAILABLE is immediate, took ${elapsedMs}ms")
        }

    @Test
    fun `S9 a non-persistence exception rolls back and is rethrown unchanged`(): Unit =
        runBlocking {
            db.createProbeTables()
            val boom = IllegalStateException("not a persistence fault")
            val thrown =
                assertFailsWith<IllegalStateException> {
                    db.uow().write<Unit>("S9.ise") {
                        dm.writeTx("S9.ise.row") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
                        throw boom
                    }
                }
            // kotlinx.coroutines stack-trace recovery may copy the instance; type and message must be unchanged.
            assertEquals(boom.message, thrown.message)
            assertEquals(0, dm.countRows("p5a_probe"))
        }

    @Test
    fun `S9 a CancellationException thrown by the block is rethrown never translated`(): Unit =
        runBlocking {
            val thrown =
                assertFailsWith<CancellationException> {
                    db.uow().write<Unit>("S9.cancel") { throw CancellationException("stop") }
                }
            assertEquals("stop", thrown.message)
        }

    @Test
    fun `nullable Duplicate existingId and NotFound id are valid details (carry-in 1)`() {
        val dup =
            DomainError(ErrorCode.DUPLICATE, "duplicate", ErrorDetail.Duplicate(EntityKind.ITEM, null, null), slots(ErrorCode.DUPLICATE))
        assertNull((dup.detail as ErrorDetail.Duplicate).existingId)
        val nf = DomainError(ErrorCode.NOT_FOUND, "missing", ErrorDetail.NotFound(EntityKind.ITEM, null), slots(ErrorCode.NOT_FOUND))
        assertNull((nf.detail as ErrorDetail.NotFound).id)
        val known = ErrorDetail.NotFound(EntityKind.ITEM, "abc")
        assertEquals("abc", known.id)
    }

    private fun slots(code: ErrorCode): Map<String, String> = ErrorFixTemplates.slots(code).associateWith { "val-$it" }

    // ---------------------------------------------------------------- S12
    private class CountingSource : PerRootConfigSource {
        val reads = AtomicInteger()

        override suspend fun layer(rootId: UUID): ConfigLayer? {
            reads.incrementAndGet()
            return ConfigLayer(ConfigDocument(workItemSchemas = emptyMap(), traits = emptyMap()), "fp", ConfigSource.PER_ROOT)
        }
    }

    @Test
    fun `S12 each BUSY attempt gets a fresh config session so the source is read once per attempt`(): Unit =
        runBlocking {
            val source = CountingSource()
            val resolver = EffectiveConfigResolver(ServiceBackedGlobalLookup(NoOpNoteSchemaService, NoOpStatusLabelService), source)
            val root = UUID.randomUUID()
            val attempts = AtomicInteger()
            val result =
                db.uow().write("S12.session") {
                    resolver.layered(root)
                    resolver.layered(root)
                    if (attempts.incrementAndGet() == 1) throw SQLiteException("simulated busy", SQLiteErrorCode.SQLITE_BUSY)
                    Outcome.Ok(Unit)
                }
            assertEquals(Outcome.Ok(Unit), result)
            assertEquals(2, attempts.get())
            assertEquals(2, source.reads.get(), "memoized within an attempt (1 read each), fresh session per attempt (2 total)")
        }

    private class FailableProjectConfigRepository(
        private val delegate: ProjectConfigRepository,
        private val cause: Throwable?
    ) : ProjectConfigRepository by delegate {
        @Volatile var failFingerprint: Boolean = false

        override suspend fun getFingerprint(rootItemId: UUID) =
            if (failFingerprint) {
                Result.Error(RepositoryError.DatabaseError("fingerprint read failed", cause))
            } else {
                delegate.getFingerprint(rootItemId)
            }
    }

    private suspend fun warmService(cause: Throwable?): Triple<PerRootConfigService, FailableProjectConfigRepository, UUID> {
        val real = db.repositoryProvider().projectConfigRepository() as SQLiteProjectConfigRepository
        val items = db.repositoryProvider().workItemRepository() as SQLiteWorkItemRepository
        val wrapper = FailableProjectConfigRepository(real, cause)
        val service = PerRootConfigService(wrapper)
        val root = WorkItem(title = "S12 root")
        items.create(root)
        real.upsert(root.id, "work_item_schemas:\n  feature-task:\n    notes: []\n")
        assertNotNull(service.getSchemas(root.id), "sanity: the cache is warm")
        wrapper.failFingerprint = true
        return Triple(service, wrapper, root.id)
    }

    @Test
    fun `S12 outside a unit a failing repository still serves the cached last-known-good document`(): Unit =
        runBlocking {
            val (service, _, rootId) = warmService(cause = null)
            assertNotNull(service.getSchemas(rootId), "outside a unit the warm cache is served")
        }

    @Test
    fun `S12 inside a unit a failing repository never serves the cache and throws PerRootConfigUnavailableException`(): Unit =
        runBlocking {
            val (service, _, rootId) = warmService(cause = null)
            val thrown =
                assertFailsWith<PerRootConfigUnavailableException> {
                    db.uow().write("S12.inUnit") {
                        service.getSchemas(rootId)
                        Outcome.Ok(Unit)
                    }
                }
            assertEquals(rootId, thrown.rootId)
        }

    @Test
    fun `S12 a PerRootConfigUnavailableException with a SQL cause is translated by its cause`(): Unit =
        runBlocking {
            val (service, _, rootId) =
                warmService(
                    cause = SQLiteException("UNIQUE constraint failed: x", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE)
                )
            val result =
                db.uow().write("S12.translate") {
                    service.getSchemas(rootId)
                    Outcome.Ok(Unit)
                }
            assertEquals(ErrorCode.DUPLICATE, assertIs<Outcome.Err>(result).error.code)
        }

    // ---------------------------------------------------------------- S13
    @Test
    fun `S13 writeTx outside a unit is counted per op and inside a unit it is not`(): Unit =
        runBlocking {
            db.createProbeTables()
            val runner = dm.units
            val before = runner.outsideUnitWrites["S13.outside"] ?: 0L
            dm.writeTx("S13.outside") { exec("INSERT INTO p5a_probe (id, v) VALUES (1, 1)") }
            assertEquals(before + 1, runner.outsideUnitWrites["S13.outside"])
            dm.writeTx("S13.outside") { exec("INSERT INTO p5a_probe (id, v) VALUES (2, 2)") }
            assertEquals(before + 2, runner.outsideUnitWrites["S13.outside"])

            db.uow().write("S13.unit") {
                dm.writeTx("S13.inside") { exec("INSERT INTO p5a_probe (id, v) VALUES (3, 3)") }
                Outcome.Ok(Unit)
            }
            assertNull(runner.outsideUnitWrites["S13.inside"], "a write joined to a unit is not an outside-unit write")
            assertNull(runner.outsideUnitWrites["S13.unit"], "an explicit unit is not an outside-unit write")
        }

    @Test
    fun `S13 a repository write outside a unit bumps the counter and inside a unit leaves it unchanged`(): Unit =
        runBlocking {
            val runner = dm.units
            val repo = db.repositoryProvider().workItemRepository()
            val base = runner.outsideUnitWrites.values.sum()
            assertIs<Result.Success<WorkItem>>(repo.create(WorkItem(title = "outside")))
            val afterOutside = runner.outsideUnitWrites.values.sum()
            assertTrue(afterOutside >= base + 1, "repository write outside a unit must be counted ($base -> $afterOutside)")
            assertTrue(
                runner.outsideUnitWrites.keys.any {
                    "WorkItemRepository" in it
                },
                "op label is <Repo>.<method>: ${runner.outsideUnitWrites.keys}"
            )

            db.uow().write("S13.repo") {
                assertIs<Result.Success<WorkItem>>(repositories.workItemRepository().create(WorkItem(title = "inside")))
                Outcome.Ok(Unit)
            }
            assertEquals(afterOutside, runner.outsideUnitWrites.values.sum(), "a repository write inside a unit is not counted")
        }

    // ---------------------------------------------------------------- S15
    @Test
    fun `S15 a held writer connection yields UNAVAILABLE after the 2 s checkout instead of hanging`(): Unit =
        runBlocking {
            val holding = CountDownLatch(1)
            val release = CountDownLatch(1)
            val holder =
                thread(isDaemon = true) {
                    transaction(db = dm.writer()) {
                        exec("SELECT 1") { }
                        holding.countDown()
                        release.await()
                    }
                }
            try {
                assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "holder must acquire the writer connection")
                val t0 = System.nanoTime()
                val result =
                    db.uow().write("S15.held") {
                        dm.writeTx("S15.touch") { exec("SELECT 1") { } }
                        Outcome.Ok(Unit)
                    }
                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                val err = assertIs<Outcome.Err>(result).error
                assertEquals(ErrorCode.UNAVAILABLE, err.code)
                assertEquals(2_000L, assertIs<ErrorDetail.Unavailable>(err.detail).retryAfterMs)
                assertTrue(elapsedMs in 1_500..3_000, "checkout timeout is 2 s (carry-in 4), took ${elapsedMs}ms")
            } finally {
                release.countDown()
                holder.join(10_000)
            }
        }
}
