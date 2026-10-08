package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.IdempotencyRecord
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.config.AppConfig
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.SqliteUnitOfWork
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item d1cccd1a) for [IdempotencyService] over a real SQLite database.
 *
 * Oracles: plan section 3.9 l.262-285 and the frozen task-scope note section 2 (ONE unit per keyed element: live record
 * + same fingerprint -> stored outcome, replayed, block not run; live + other fingerprint -> IDEMPOTENCY_MISMATCH with
 * detail IdempotencyMismatch(key), nothing written; miss or expired (created_at <= now - ttl) -> run the block; Ok ->
 * upsert in the SAME unit; Err INVALID_REQUEST / UNKNOWN_PARAMETER -> recorded by a follow-up unit after the rollback;
 * any other Err, throw or cancellation -> no record), the test-plan scenarios S3, S6-S10, S12, S15, S16, S17 and the
 * probe list. Time comes from the unit's clock (`SqliteTestDatabase.unitOfWork(clock)`), so every boundary is exact.
 *
 * Vacuity: every "no record" assertion is paired with a control (same call shape that DOES record) in the same file,
 * and every "rolled back" assertion counts work_items rows that the same block wrote.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class IdempotencyServiceTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val t0: Instant = Instant.parse("2026-03-01T10:00:00.123Z")
    private var now: Instant = t0
    private val ttl: Duration = Duration.ofHours(24)

    private val uow: UnitOfWork get() = sqlite.unitOfWork(Clock { now })
    private val service: IdempotencyService get() = IdempotencyService(uow)

    private fun fp(text: String): String = Fingerprint.of(JsonPrimitive(text))

    private fun req(
        key: String = "k1",
        fingerprint: String = fp("a"),
        principal: String = "agent-1"
    ) = IdempotentRequest(principal, key, fingerprint)

    private fun value(text: String): JsonElement = JsonPrimitive(text)

    private fun rawInt(sql: String): Int =
        DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun itemCount(): Int = rawInt("SELECT count(*) FROM work_items")

    private fun recordCount(): Int = sqlite.idempotencyRecordCount()

    private suspend fun storedRecord(
        key: String = "k1",
        op: String = "mcp.test",
        principal: String = "agent-1"
    ): IdempotencyRecord? = uow.read { stores.idempotencyStore().find(principal, op, key) }

    private suspend fun IdempotencyService.runOk(
        op: String = "mcp.test",
        request: IdempotentRequest = req(),
        executions: AtomicInteger? = null,
        result: String = "result-1",
        writeItem: Boolean = true
    ): IdempotentOutcome<JsonElement> =
        execute(op, request, JsonElementCodec) {
            executions?.incrementAndGet()
            if (writeItem) stores.workItemRepository().create(WorkItem(title = "keyed-item"))
            Outcome.Ok(value(result))
        }

    private suspend fun IdempotencyService.runErr(
        error: DomainError,
        op: String = "mcp.test",
        request: IdempotentRequest = req(),
        executions: AtomicInteger? = null,
        writeItem: Boolean = true
    ): IdempotentOutcome<JsonElement> =
        execute(op, request, JsonElementCodec) {
            executions?.incrementAndGet()
            if (writeItem) stores.workItemRepository().create(WorkItem(title = "item-err"))
            Outcome.Err(error)
        }

    private fun errorOf(outcome: IdempotentOutcome<JsonElement>): DomainError {
        val o = outcome.outcome
        assertIs<Outcome.Err>(o, "expected an Err outcome but was $o")
        return o.error
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `S1 first call executes and records, same key and fingerprint replays without running the block`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            val first = service.runOk(executions = executions)
            val second = service.runOk(executions = executions, result = "would-be-different")

            assertFalse(first.replayed)
            assertEquals(Outcome.Ok(value("result-1")), first.outcome)
            assertTrue(second.replayed, "a live record with the same fingerprint must replay")
            assertEquals(Outcome.Ok(value("result-1")), second.outcome, "the stored outcome is returned, not the new block's")
            assertEquals(1, executions.get(), "the block must run exactly once")
            assertEquals(1, itemCount(), "the replay must not write a second item")
            assertEquals(1, recordCount())
        }

    @Test
    fun `S1 the record carries the unit clock time as created_at with millisecond precision`(): Unit =
        runBlocking {
            service.runOk()
            val record = assertNotNull(storedRecord())
            assertEquals(t0, record.createdAt)
            assertEquals("agent-1", record.principalId)
            assertEquals("mcp.test", record.operation)
            assertEquals("k1", record.key)
            assertEquals(fp("a"), record.fingerprint)
        }

    @Test
    fun `S3 a record survives a restart - a new manager and service over the same file replay it`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(executions = executions)

            val second = DatabaseManager(appConfig = AppConfig.fromEnv { null })
            try {
                check(second.initialize(sqlite.jdbcUrl)) { "second manager failed to initialize" }
                val restartedUow = SqliteUnitOfWork(second, DefaultRepositoryProvider(second), Clock { now })
                val replay = IdempotencyService(restartedUow).runOk(executions = executions, result = "after-restart")

                assertTrue(replay.replayed)
                assertEquals(Outcome.Ok(value("result-1")), replay.outcome)
                assertEquals(1, executions.get())
            } finally {
                second.shutdown()
            }
        }

    // ---------------------------------------------------------------- mismatch

    @Test
    fun `S6 same key with another fingerprint is an idempotency mismatch and writes nothing`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(executions = executions)
            val before = storedRecord()

            val mismatch = service.runOk(request = req(fingerprint = fp("b")), executions = executions, result = "other")

            val error = errorOf(mismatch)
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, error.code)
            assertEquals(ErrorDetail.IdempotencyMismatch("k1"), error.detail)
            assertEquals(1, executions.get(), "a mismatch must not run the block")
            assertEquals(1, itemCount(), "a mismatch must not write")
            assertEquals(before, storedRecord(), "the stored record must be untouched by a mismatch")

            val original = service.runOk(executions = executions, result = "again")
            assertTrue(original.replayed, "the original request must still replay after a mismatch")
            assertEquals(Outcome.Ok(value("result-1")), original.outcome)
        }

    @Test
    fun `S6 a mismatch is returned even when the stored record is a recorded payload rejection`(): Unit =
        runBlocking {
            val rejection = ErrorFixtures.error(ErrorCode.INVALID_REQUEST)
            service.runErr(rejection)
            val mismatch = service.runErr(rejection, request = req(fingerprint = fp("b")))
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, errorOf(mismatch).code)
        }

    // ---------------------------------------------------------------- failures are not recorded

    @Test
    fun `S7 state and transient failures are not recorded - retry with the same key executes`(): Unit =
        runBlocking {
            val notRecorded =
                listOf(
                    ErrorCode.GATE_BLOCKED,
                    ErrorCode.NOT_FOUND,
                    ErrorCode.VERSION_CONFLICT,
                    ErrorCode.DEPENDENCY_UNMET,
                    ErrorCode.CLAIM_HELD,
                    ErrorCode.FORBIDDEN,
                    ErrorCode.UNAVAILABLE,
                    ErrorCode.INTERNAL
                )
            notRecorded.forEach { code ->
                val key = "k-${code.wire}"
                val executions = AtomicInteger()
                val first = service.runErr(ErrorFixtures.error(code), request = req(key = key), executions = executions)
                assertEquals(code, errorOf(first).code, "attempt 1 must fail with $code")
                assertEquals(0, itemCount(), "attempt 1 ($code) must roll back what its block wrote")
                assertNull(storedRecord(key = key), "$code must not be recorded")

                val retry = service.runOk(request = req(key = key), executions = executions, result = "fixed-$key")
                assertFalse(retry.replayed, "$code: the retry must execute, not replay")
                assertEquals(Outcome.Ok(value("fixed-$key")), retry.outcome)
                assertEquals(2, executions.get(), "$code: the retry must run the block")
                assertEquals(1, itemCount())
                // reset for the next code
                DriverManager.getConnection(sqlite.jdbcUrl).use { c ->
                    c.createStatement().use {
                        it.executeUpdate("DELETE FROM work_items")
                        it.executeUpdate("DELETE FROM idempotency_records")
                    }
                }
            }
        }

    @Test
    fun `S7 control - a successful result under the same shape IS recorded`(): Unit =
        runBlocking {
            service.runOk(request = req(key = "ctl"))
            assertNotNull(storedRecord(key = "ctl"))
            assertEquals(1, recordCount())
        }

    @Test
    fun `S7 a block that throws propagates, rolls back and records nothing, and the retry executes`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            assertFailsWith<IllegalStateException> {
                service.execute("mcp.test", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    stores.workItemRepository().create(WorkItem(title = "thrown"))
                    throw IllegalStateException("boom")
                }
            }
            assertEquals(0, itemCount(), "the thrown block's write must roll back")
            assertEquals(0, recordCount())

            val retry = service.runOk(executions = executions)
            assertFalse(retry.replayed)
            assertEquals(2, executions.get())
            assertEquals(1, recordCount())
        }

    // ---------------------------------------------------------------- payload rejections are recorded

    @Test
    fun `S8 an INVALID_REQUEST error is recorded and replayed with the same code message and detail`(): Unit =
        runBlocking {
            val rejection = ErrorFixtures.error(ErrorCode.INVALID_REQUEST)
            val executions = AtomicInteger()
            val first = service.runErr(rejection, executions = executions)
            assertFalse(first.replayed)
            assertEquals(ErrorCode.INVALID_REQUEST, errorOf(first).code)
            assertEquals(0, itemCount(), "the rejected attempt's write must be rolled back")
            assertEquals(1, recordCount(), "a payload rejection is recorded")

            val second = service.runOk(executions = executions, result = "would-succeed")
            assertTrue(second.replayed, "the stored rejection replays")
            val replayed = errorOf(second)
            assertEquals(ErrorCode.INVALID_REQUEST, replayed.code)
            assertEquals(rejection.message, replayed.message)
            assertEquals(rejection.detail, replayed.detail)
            assertEquals(1, executions.get(), "a replayed rejection must not run the block")
            assertEquals(0, itemCount())
        }

    @Test
    fun `S8 an UNKNOWN_PARAMETER error is recorded and replayed`(): Unit =
        runBlocking {
            val rejection = ErrorFixtures.error(ErrorCode.UNKNOWN_PARAMETER)
            service.runErr(rejection)
            assertEquals(1, recordCount())
            val second = service.runOk(result = "would-succeed")
            assertTrue(second.replayed)
            val replayed = errorOf(second)
            assertEquals(ErrorCode.UNKNOWN_PARAMETER, replayed.code)
            assertEquals(rejection.detail, replayed.detail)
        }

    @Test
    fun `S8 a recorded rejection with another fingerprint is a mismatch`(): Unit =
        runBlocking {
            service.runErr(ErrorFixtures.error(ErrorCode.INVALID_REQUEST))
            val other = service.runErr(ErrorFixtures.error(ErrorCode.INVALID_REQUEST), request = req(fingerprint = fp("b")))
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, errorOf(other).code)
        }

    // ---------------------------------------------------------------- joined units and rollback

    @Test
    fun `S9 execute joined in an outer unit that returns Err leaves no record and no write`(): Unit =
        runBlocking {
            val unit = uow
            val keyed = IdempotencyService(unit)
            val executions = AtomicInteger()
            val outer =
                unit.write<Unit>("outer.fail") {
                    val inner = keyed.runOk(executions = executions)
                    assertEquals(Outcome.Ok(value("result-1")), inner.outcome, "the keyed element itself succeeded")
                    Outcome.Err(DomainError(ErrorCode.INTERNAL, "outer decides to roll back"))
                }
            assertIs<Outcome.Err>(outer)
            assertEquals(0, recordCount(), "the record must roll back with the outer unit")
            assertEquals(0, itemCount(), "the element's write must roll back with the outer unit")

            val retry = keyed.runOk(executions = executions)
            assertFalse(retry.replayed, "no record means the retry executes")
            assertEquals(2, executions.get())
        }

    @Test
    fun `S9 control - the same joined shape with an Ok outer unit commits the record and the write`(): Unit =
        runBlocking {
            val unit = uow
            val keyed = IdempotencyService(unit)
            unit.write("outer.ok") {
                keyed.runOk()
                Outcome.Ok(Unit)
            }
            assertEquals(1, recordCount())
            assertEquals(1, itemCount())
        }

    @Test
    fun `S10 an Err block rolls back everything it wrote and records nothing`(): Unit =
        runBlocking {
            val result = service.runErr(ErrorFixtures.error(ErrorCode.NOT_FOUND))
            assertEquals(ErrorCode.NOT_FOUND, errorOf(result).code)
            assertEquals(0, itemCount(), "the failed element must leave no partial rows")
            assertEquals(0, recordCount())
            // control: the same block shape returning Ok commits both
            service.runOk(request = req(key = "ctl"))
            assertEquals(1, itemCount())
            assertEquals(1, recordCount())
        }

    // ---------------------------------------------------------------- TTL

    @Test
    fun `S12 a record replays up to 24h minus 1ms and is re-executed from exactly 24h`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(executions = executions, result = "first")

            now = t0.plus(ttl).minusMillis(1)
            val live = service.runOk(executions = executions, result = "second")
            assertTrue(live.replayed, "created_at > now - ttl is live")
            assertEquals(Outcome.Ok(value("first")), live.outcome)
            assertEquals(1, executions.get())

            now = t0.plus(ttl)
            val expired = service.runOk(executions = executions, result = "second")
            assertFalse(expired.replayed, "created_at <= now - ttl is expired")
            assertEquals(Outcome.Ok(value("second")), expired.outcome)
            assertEquals(2, executions.get())
            assertEquals(1, recordCount(), "the expired row is replaced, not duplicated")
            assertEquals(now, assertNotNull(storedRecord()).createdAt, "the replacement carries the new time")

            now = t0.plus(ttl).plusMillis(1)
            val again = service.runOk(executions = executions, result = "third")
            assertTrue(again.replayed)
            assertEquals(Outcome.Ok(value("second")), again.outcome, "the replacement is what replays now")
        }

    @Test
    fun `S12 an expired record with another fingerprint executes instead of mismatching`(): Unit =
        runBlocking {
            service.runOk(result = "first")
            now = t0.plus(ttl)
            val other = service.runOk(request = req(fingerprint = fp("b")), result = "new")
            assertFalse(other.replayed)
            assertEquals(Outcome.Ok(value("new")), other.outcome)
            assertEquals(fp("b"), assertNotNull(storedRecord()).fingerprint)
        }

    @Test
    fun `S12 a configured ttl is honoured at its own boundary`(): Unit =
        runBlocking {
            val short = IdempotencyService(uow, Duration.ofSeconds(10))
            val executions = AtomicInteger()
            short.runOk(executions = executions)
            now = t0.plusSeconds(10).minusMillis(1)
            assertTrue(short.runOk(executions = executions).replayed)
            now = t0.plusSeconds(10)
            assertFalse(short.runOk(executions = executions).replayed)
            assertEquals(2, executions.get())
        }

    // ---------------------------------------------------------------- isolation

    @Test
    fun `S15 the same key under two principals executes twice`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(request = req(principal = "agent-1"), executions = executions)
            val other = service.runOk(request = req(principal = "agent-2"), executions = executions)
            assertFalse(other.replayed)
            assertEquals(2, executions.get())
            assertEquals(2, recordCount())
        }

    @Test
    fun `S15 the same key under two operations executes twice`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(op = "mcp.manage_items.create", executions = executions)
            val other = service.runOk(op = "mcp.manage_notes.upsert", executions = executions)
            assertFalse(other.replayed)
            assertEquals(2, executions.get())
            assertEquals(2, recordCount())
        }

    @Test
    fun `S15 the same operation and key but another index suffix is an independent key`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.runOk(request = req(key = "u:0"), executions = executions)
            val other = service.runOk(request = req(key = "u:1"), executions = executions)
            assertFalse(other.replayed)
            assertEquals(2, executions.get())
        }

    // ---------------------------------------------------------------- concurrency

    @Test
    fun `S16 eight parallel callers with the same key and fingerprint run the block once`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            val keyed = service
            val results =
                (1..8)
                    .map { i ->
                        async(Dispatchers.IO) { keyed.runOk(executions = executions, result = "caller-$i") }
                    }.awaitAll()

            assertEquals(1, executions.get(), "exactly one caller executes")
            assertEquals(1, results.count { !it.replayed })
            assertEquals(7, results.count { it.replayed })
            assertEquals(1, results.map { it.outcome }.toSet().size, "every caller sees the same outcome")
            assertEquals(1, itemCount())
            assertEquals(1, recordCount())
        }

    @Test
    fun `S16 eight parallel callers with the same key and different fingerprints - one executes, seven mismatch`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            val keyed = service
            val results =
                (1..8)
                    .map { i ->
                        async(Dispatchers.IO) {
                            keyed.runOk(request = req(fingerprint = fp("fp-$i")), executions = executions, result = "caller-$i")
                        }
                    }.awaitAll()

            assertEquals(1, executions.get())
            val mismatches = results.count { (it.outcome as? Outcome.Err)?.error?.code == ErrorCode.IDEMPOTENCY_MISMATCH }
            assertEquals(7, mismatches)
            assertEquals(1, recordCount())
        }

    // ---------------------------------------------------------------- probes

    @Test
    fun `probe a stored result with an unknown format version is an internal error and never re-executed`(): Unit =
        runBlocking {
            val unit = uow
            unit.write("seed.record") {
                stores.idempotencyStore().upsert(
                    IdempotencyRecord("agent-1", "mcp.test", "k1", fp("a"), """{"v":2,"ok":true,"value":"x"}""", now)
                )
                Outcome.Ok(Unit)
            }
            val executions = AtomicInteger()
            val result = service.runOk(executions = executions)
            assertEquals(ErrorCode.INTERNAL, errorOf(result).code)
            assertEquals(0, executions.get(), "an undecodable record must never re-run the block")
            assertEquals(0, itemCount())
            assertEquals(1, recordCount())
        }

    @Test
    fun `probe cancellation mid-block leaves no record and no write and a retry executes`(): Unit =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val job =
                launch(Dispatchers.IO) {
                    service.execute("mcp.test", req(), JsonElementCodec) {
                        stores.workItemRepository().create(WorkItem(title = "cancelled"))
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            started.await()
            job.cancelAndJoin()
            assertEquals(0, itemCount(), "a cancelled element rolls back")
            assertEquals(0, recordCount())

            val executions = AtomicInteger()
            val retry = service.runOk(executions = executions)
            assertFalse(retry.replayed)
            assertEquals(1, executions.get())
        }

    @Test
    fun `probe a one megabyte result round-trips through the record`(): Unit =
        runBlocking {
            val big = "x".repeat(1_000_000)
            service.runOk(result = big)
            val replay = service.runOk(result = "small")
            assertTrue(replay.replayed)
            assertEquals(Outcome.Ok(value(big)), replay.outcome)
        }

    @Test
    fun `probe unicode and structured results round-trip exactly`(): Unit =
        runBlocking {
            val text = "caf" + 0xE9.toChar() + " " + String(Character.toChars(0x1F600)) + " q\"uote"
            service.runOk(result = text)
            val replay = service.runOk(result = "other")
            assertEquals(Outcome.Ok(value(text)), replay.outcome)
        }

    // ---------------------------------------------------------------- executeDetached

    @Test
    fun `S17 detached - a successful result is recorded and replayed without running the block`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            val first =
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Ok(value("tree-done"))
                }
            assertFalse(first.replayed)
            assertEquals(1, recordCount())

            val second =
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Ok(value("other"))
                }
            assertTrue(second.replayed)
            assertEquals(Outcome.Ok(value("tree-done")), second.outcome)
            assertEquals(1, executions.get())
        }

    @Test
    fun `S17 detached - another fingerprint is a mismatch and the block does not run`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) { Outcome.Ok(value("tree-done")) }
            val mismatch =
                service.executeDetached("mcp.complete_tree", req(fingerprint = fp("b")), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Ok(value("other"))
                }
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, errorOf(mismatch).code)
            assertEquals(0, executions.get())
            assertEquals(1, recordCount())
        }

    @Test
    fun `S17 detached - a state failure is not recorded and the retry executes`(): Unit =
        runBlocking {
            val executions = AtomicInteger()
            val failed =
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Err(ErrorFixtures.error(ErrorCode.GATE_BLOCKED))
                }
            assertEquals(ErrorCode.GATE_BLOCKED, errorOf(failed).code)
            assertEquals(0, recordCount())

            val retry =
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Ok(value("done"))
                }
            assertFalse(retry.replayed)
            assertEquals(2, executions.get())
            assertEquals(1, recordCount())
        }

    @Test
    fun `S17 detached - a block that throws records nothing`(): Unit =
        runBlocking {
            assertFailsWith<IllegalStateException> {
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) { throw IllegalStateException("boom") }
            }
            assertEquals(0, recordCount())
        }

    @Test
    fun `S17 detached - an expired record is re-executed`(): Unit =
        runBlocking {
            val executions = AtomicInteger()

            suspend fun run() =
                service.executeDetached("mcp.complete_tree", req(), JsonElementCodec) {
                    executions.incrementAndGet()
                    Outcome.Ok(value("done"))
                }
            run()
            now = t0.plus(ttl).minusMillis(1)
            assertTrue(run().replayed)
            now = t0.plus(ttl)
            assertFalse(run().replayed)
            assertEquals(2, executions.get())
            assertEquals(1, recordCount())
        }
}
