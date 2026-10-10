package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.Clock
import io.github.jpicklyk.mcptask.current.application.port.UnitOfWork
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyCodec
import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.domain.error.BatchSummary
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.domain.error.ErrorFixtures
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.extension.RegisterExtension
import java.sql.DriverManager
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item f1b003a6) for [BatchRunner] over a real SQLite database, scenarios S15 to S17.
 *
 * Oracles: envelope section 2 "Batch results" and section 8 U18 (positions are never compacted; the default is per element,
 * valid elements commit and invalid ones fail; `atomic: true` rolls the whole batch back and the top-level error is the first
 * failing element's error with its index), the `IdempotencyService` contract (live record + same fingerprint -> stored
 * outcome and replayed; live + other fingerprint -> `idempotency_mismatch`; a non-stored error is not recorded so a retry
 * re-executes; `invalid_request` and `unknown_parameter` rejections are recorded and replayed), `BatchResult` (an atomic
 * failure repeats the failing `Err` for every later index so `failure().index` is the failing index) and task-scope D5 (an
 * `idempotencyKey` is a non-blank string of at most 128 characters; a bad key fails that element with `invalid_request` on
 * field `idempotencyKey` without executing it; keys are scoped by principal and tool).
 *
 * Vacuity: every "no row" and "no record" assertion is paired with a control in the same file that DOES produce a row or a
 * record through the same call shape. NEW-SURFACE: [BatchRunner] does not exist before the item.
 */
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class BatchRunnerTest {
    @RegisterExtension
    @JvmField
    val sqlite = SqliteTestDatabase.perMethod()

    private val now: Instant = Instant.parse("2026-03-01T10:00:00.123Z")

    private val unit: UnitOfWork by lazy { sqlite.unitOfWork(Clock { now }) }
    private val runner: BatchRunner by lazy { BatchRunner(unit, IdempotencyService(unit)) }

    private object StringCodec : IdempotencyCodec<String> {
        override fun encode(value: String): JsonElement = JsonPrimitive(value)

        override fun decode(json: JsonElement): String = json.jsonPrimitive.content
    }

    private val notFound: DomainError = ErrorFixtures.error(ErrorCode.NOT_FOUND)
    private val invalidRequest: DomainError = ErrorFixtures.error(ErrorCode.INVALID_REQUEST)

    private fun el(
        title: String,
        key: String? = null
    ) = BatchElement(title, JsonPrimitive(title), key)

    private fun three(
        k0: String? = null,
        k1: String? = null,
        k2: String? = null
    ) = listOf(el("t0", k0), el("t1", k1), el("t2", k2))

    private suspend fun runBatch(
        elements: List<BatchElement<String>>,
        atomic: Boolean = false,
        tool: String = "items.write",
        principal: String = "agent-1",
        failAt: Set<Int> = emptySet(),
        failWith: DomainError = notFound,
        throwAt: Int? = null,
        executed: MutableList<Int> = mutableListOf(),
        value: (Int) -> String = { "r$it" }
    ): BatchExecution<String> =
        runner.run(tool, principal, elements, atomic, StringCodec) { index, title ->
            executed += index
            stores.workItemRepository().create(WorkItem(title = title))
            if (index == throwAt) throw IllegalStateException("boom-$index")
            if (index in failAt) Outcome.Err(failWith) else Outcome.Ok(value(index))
        }

    private fun titles(): List<String> =
        DriverManager.getConnection(sqlite.jdbcUrl).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT title FROM work_items ORDER BY title").use { rows ->
                    buildList {
                        while (rows.next()) add(rows.getString(1))
                    }
                }
            }
        }

    private fun records(): Int = sqlite.idempotencyRecordCount()

    private fun errorAt(
        execution: BatchExecution<String>,
        index: Int
    ): DomainError {
        val outcome = execution.result.results[index]
        assertIs<Outcome.Err>(outcome, "results[$index] must be an Err but was $outcome")
        return outcome.error
    }

    // ---------------------------------------------------------------- S15 per element (default)

    @Test
    fun `S15 per element - a middle failure rolls back only that element, the others commit and positions are kept`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(three(), failAt = setOf(1), executed = executed)

            assertEquals(false, execution.result.atomic)
            assertEquals(3, execution.result.results.size, "positions are never compacted")
            assertEquals(Outcome.Ok("r0"), execution.result.results[0])
            assertEquals(notFound, errorAt(execution, 1))
            assertEquals(Outcome.Ok("r2"), execution.result.results[2])
            assertEquals(BatchSummary(2, 1), execution.result.summary)
            assertTrue(execution.replayed.isEmpty())
            assertEquals(listOf(0, 1, 2), executed, "a failure does not stop later elements")
            assertEquals(listOf("t0", "t2"), titles(), "the failed element's write is rolled back, its neighbours stay")
        }

    @Test
    fun `S15 per element - a failing first and a failing last element keep their positions`(): Unit =
        runBlocking {
            val execution = runBatch(three(), failAt = setOf(0, 2))

            assertEquals(notFound, errorAt(execution, 0))
            assertEquals(Outcome.Ok("r1"), execution.result.results[1])
            assertEquals(notFound, errorAt(execution, 2))
            assertEquals(BatchSummary(1, 2), execution.result.summary)
            assertEquals(listOf("t1"), titles())
        }

    @Test
    fun `S15 per element - all failing leaves no rows and every position is an Err`(): Unit =
        runBlocking {
            val execution = runBatch(three(), failAt = setOf(0, 1, 2))

            assertEquals(BatchSummary(0, 3), execution.result.summary)
            assertTrue(titles().isEmpty())
        }

    @Test
    fun `S15 per element - all succeeding commits every element and reports an ok batch`(): Unit =
        runBlocking {
            val execution = runBatch(three())

            assertEquals(BatchSummary(3, 0), execution.result.summary)
            assertEquals(listOf(Outcome.Ok("r0"), Outcome.Ok("r1"), Outcome.Ok("r2")), execution.result.results)
            assertNull(execution.result.failure())
            assertEquals(listOf("t0", "t1", "t2"), titles())
        }

    @Test
    fun `S15 each element is executed with its own index and payload`(): Unit =
        runBlocking {
            val seen = mutableListOf<Pair<Int, String>>()
            runner.run("items.write", "agent-1", three(), false, StringCodec) { index, title ->
                seen += index to title
                Outcome.Ok(title)
            }
            assertEquals(listOf(0 to "t0", 1 to "t1", 2 to "t2"), seen)
        }

    @Test
    fun `S15 an empty element list is an IllegalArgumentException`(): Unit =
        runBlocking {
            assertFailsWith<IllegalArgumentException> { runBatch(emptyList()) }
            assertFailsWith<IllegalArgumentException> { runBatch(emptyList(), atomic = true) }
        }

    @Test
    fun `S15 an unexpected exception propagates - earlier elements stay committed and the throwing one writes nothing`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val thrown = assertFailsWith<IllegalStateException> { runBatch(three(), throwAt = 1, executed = executed) }

            assertEquals("boom-1", thrown.message)
            assertEquals(listOf(0, 1), executed, "elements after the throwing one are not executed")
            assertEquals(listOf("t0"), titles(), "element 0 committed, the throwing element rolled back")
        }

    // ---------------------------------------------------------------- S15 atomic

    @Test
    fun `S15 atomic - a failure at index 1 rolls everything back, repeats the Err and never runs the next element`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(three(), atomic = true, failAt = setOf(1), executed = executed)

            assertTrue(execution.result.atomic)
            assertEquals(Outcome.Ok("r0"), execution.result.results[0], "elements before the failure keep their computed Ok")
            assertEquals(notFound, errorAt(execution, 1))
            assertEquals(notFound, errorAt(execution, 2), "later positions repeat the failing Err")
            val failure = execution.result.failure()
            assertEquals(1, failure?.index)
            assertEquals(notFound, failure?.error)
            assertEquals(listOf(0, 1), executed, "the element after the failure is never executed")
            assertTrue(titles().isEmpty(), "element 0's write is rolled back with the batch")
        }

    @Test
    fun `S15 atomic - a failure at the first and at the last index are both rolled back with the right index`(): Unit =
        runBlocking {
            val first = runBatch(three(), atomic = true, failAt = setOf(0))
            assertEquals(0, first.result.failure()?.index)
            assertTrue(titles().isEmpty())

            val executed = mutableListOf<Int>()
            val last = runBatch(three(), atomic = true, failAt = setOf(2), executed = executed)
            assertEquals(2, last.result.failure()?.index)
            assertEquals(listOf(0, 1, 2), executed)
            assertEquals(Outcome.Ok("r0"), last.result.results[0])
            assertEquals(Outcome.Ok("r1"), last.result.results[1])
            assertTrue(titles().isEmpty(), "two succeeded elements are rolled back by the third element's failure")
        }

    @Test
    fun `S15 atomic - two failing elements report the first, lowest-index one and the second is never executed`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(three(), atomic = true, failAt = setOf(1, 2), executed = executed)

            assertEquals(1, execution.result.failure()?.index)
            assertEquals(listOf(0, 1), executed)
        }

    @Test
    fun `S15 atomic - control - an all-ok atomic batch commits every element`(): Unit =
        runBlocking {
            val execution = runBatch(three(), atomic = true)

            assertTrue(execution.result.atomic)
            assertEquals(BatchSummary(3, 0), execution.result.summary)
            assertNull(execution.result.failure())
            assertEquals(listOf("t0", "t1", "t2"), titles())
        }

    @Test
    fun `S15 atomic - an unexpected exception propagates and rolls back the earlier elements too`(): Unit =
        runBlocking {
            assertFailsWith<IllegalStateException> { runBatch(three(), atomic = true, throwAt = 1) }
            assertTrue(titles().isEmpty(), "an atomic batch commits nothing when an element throws")
        }

    @Test
    fun `S15 atomic - idempotency records are rolled back with the batch, a per-element control keeps its records`(): Unit =
        runBlocking {
            val keys = three("k0", "k1", "k2")
            runBatch(keys, atomic = true, failAt = setOf(2))
            assertEquals(0, records(), "records written inside a rolled-back atomic batch must not survive")
            assertTrue(titles().isEmpty())

            // control: the same shape per element records the two good elements
            runBatch(keys, atomic = false, failAt = setOf(2), principal = "agent-control")
            assertEquals(2, records(), "control: the two successful keyed elements are recorded, the failed one is not")
        }

    @Test
    fun `S15 atomic - after a rollback the same keys execute again instead of replaying`(): Unit =
        runBlocking {
            val keys = three("k0", "k1", "k2")
            runBatch(keys, atomic = true, failAt = setOf(2))

            val executed = mutableListOf<Int>()
            val retry = runBatch(keys, atomic = true, executed = executed)
            assertTrue(retry.replayed.isEmpty(), "nothing was recorded by the rolled-back attempt")
            assertEquals(listOf(0, 1, 2), executed)
            assertEquals(3, records())
            assertEquals(listOf("t0", "t1", "t2"), titles())
        }

    @Test
    fun `S16 atomic - a repeated all-ok keyed atomic batch replays every element without executing`(): Unit =
        runBlocking {
            val keys = three("k0", "k1", "k2")
            runBatch(keys, atomic = true)

            val executed = mutableListOf<Int>()
            val again = runBatch(keys, atomic = true, executed = executed, value = { "new$it" })
            assertEquals(setOf(0, 1, 2), again.replayed)
            assertTrue(executed.isEmpty())
            assertEquals(listOf(Outcome.Ok("r0"), Outcome.Ok("r1"), Outcome.Ok("r2")), again.result.results)
        }

    // ---------------------------------------------------------------- S16 keyed replay

    @Test
    fun `S16 a keyed rerun with the same keys and payloads is not executed and reports the replayed indices`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val first = runBatch(three("k0", "k1", "k2"), executed = executed)
            assertTrue(first.replayed.isEmpty(), "the first run executes")
            assertEquals(3, records())

            val second = runBatch(three("k0", "k1", "k2"), executed = executed, value = { "WOULD-BE-DIFFERENT-$it" })
            assertEquals(setOf(0, 1, 2), second.replayed)
            assertEquals(listOf(0, 1, 2), executed, "no element executes the second time")
            assertEquals(
                listOf(Outcome.Ok("r0"), Outcome.Ok("r1"), Outcome.Ok("r2")),
                second.result.results,
                "stored outcomes are returned"
            )
            assertEquals(listOf("t0", "t1", "t2"), titles(), "a replay writes nothing")
        }

    @Test
    fun `S16 only the keyed elements replay - an unkeyed neighbour executes again`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            runBatch(three("k0", null, "k2"), executed = executed)
            executed.clear()

            val second = runBatch(three("k0", null, "k2"), executed = executed)
            assertEquals(setOf(0, 2), second.replayed)
            assertEquals(listOf(1), executed)
            assertEquals(listOf("t0", "t1", "t1", "t2"), titles(), "the unkeyed element wrote a second row")
        }

    @Test
    fun `S16 the same key with another payload is idempotency_mismatch for that element only and writes nothing`(): Unit =
        runBlocking {
            runBatch(listOf(el("t0", "k0"), el("t1", "k1")))
            val executed = mutableListOf<Int>()

            val second = runBatch(listOf(el("t0", "k0"), el("DIFFERENT", "k1"), el("t2", "k2")), executed = executed)

            assertEquals(Outcome.Ok("r0"), second.result.results[0])
            val mismatch = errorAt(second, 1)
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, mismatch.code)
            assertEquals(ErrorDetail.IdempotencyMismatch("k1"), mismatch.detail)
            assertEquals(Outcome.Ok("r2"), second.result.results[2], "a mismatch does not stop the batch")
            assertEquals(setOf(0), second.replayed, "a mismatch is not a replay")
            assertEquals(listOf(2), executed, "only the new key executes")
            assertEquals(listOf("t0", "t1", "t2"), titles())
        }

    @Test
    fun `S16 the same key under another principal executes again`(): Unit =
        runBlocking {
            runBatch(three("k0", "k1", "k2"), principal = "agent-1")
            val executed = mutableListOf<Int>()

            val other = runBatch(three("k0", "k1", "k2"), principal = "agent-2", executed = executed)
            assertTrue(other.replayed.isEmpty(), "keys are scoped by principal")
            assertEquals(listOf(0, 1, 2), executed)
            assertEquals(6, records())
        }

    @Test
    fun `S16 the same key under another tool executes again`(): Unit =
        runBlocking {
            runBatch(three("k0", "k1", "k2"), tool = "items.write")
            val executed = mutableListOf<Int>()

            val other = runBatch(three("k0", "k1", "k2"), tool = "notes.write", executed = executed)
            assertTrue(other.replayed.isEmpty(), "keys are scoped by tool")
            assertEquals(listOf(0, 1, 2), executed)
            assertEquals(6, records())
        }

    @Test
    fun `S16 probe a duplicate key with the same payload inside one request - the second element replays the first`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(listOf(el("t0", "dup"), el("t0", "dup")), executed = executed)

            assertEquals(listOf(0), executed)
            assertEquals(setOf(1), execution.replayed)
            assertEquals(listOf(Outcome.Ok("r0"), Outcome.Ok("r0")), execution.result.results)
            assertEquals(listOf("t0"), titles())
        }

    @Test
    fun `S16 probe a duplicate key with another payload inside one request - the second element is a mismatch`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(listOf(el("t0", "dup"), el("t1", "dup")), executed = executed)

            assertEquals(listOf(0), executed)
            assertEquals(ErrorCode.IDEMPOTENCY_MISMATCH, errorAt(execution, 1).code)
            assertTrue(execution.replayed.isEmpty())
        }

    // ---------------------------------------------------------------- S17 what is recorded

    @Test
    fun `S17 a keyed not_found failure is not recorded and the retry executes`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val first = runBatch(listOf(el("t0", "kx")), failAt = setOf(0), executed = executed)
            assertEquals(ErrorCode.NOT_FOUND, errorAt(first, 0).code)
            assertEquals(0, records(), "a non-stored error is not recorded")
            assertTrue(titles().isEmpty())

            val retry = runBatch(listOf(el("t0", "kx")), executed = executed)
            assertTrue(retry.replayed.isEmpty(), "the retry executes, it does not replay the failure")
            assertEquals(listOf(0, 0), executed)
            assertEquals(Outcome.Ok("r0"), retry.result.results[0])
            assertEquals(1, records(), "control: the successful retry IS recorded")
        }

    @Test
    fun `S17 a keyed invalid_request rejection is recorded and the retry replays it without executing`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val first = runBatch(listOf(el("t0", "kr")), failAt = setOf(0), failWith = invalidRequest, executed = executed)
            assertEquals(ErrorCode.INVALID_REQUEST, errorAt(first, 0).code)
            assertEquals(1, records(), "a payload rejection is recorded")
            assertTrue(titles().isEmpty(), "the rejected element's write is rolled back")

            val retry = runBatch(listOf(el("t0", "kr")), executed = executed)
            assertEquals(setOf(0), retry.replayed)
            assertEquals(listOf(0), executed, "the replay does not execute the element")
            val replayed = errorAt(retry, 0)
            assertEquals(ErrorCode.INVALID_REQUEST, replayed.code)
            assertEquals(invalidRequest.message, replayed.message)
            assertEquals(invalidRequest.detail, replayed.detail)
        }

    @Test
    fun `S17 an unkeyed element never writes an idempotency record, a keyed one does`(): Unit =
        runBlocking {
            runBatch(three())
            assertEquals(0, records())
            assertEquals(3, titles().size)

            runBatch(three("k0", "k1", "k2"), principal = "agent-keyed")
            assertEquals(3, records(), "control: the keyed shape records")
        }

    @Test
    fun `S17 a key of 1 and of 128 characters is accepted`(): Unit =
        runBlocking {
            val execution = runBatch(listOf(el("t0", "k"), el("t1", "k".repeat(128))))
            assertEquals(BatchSummary(2, 0), execution.result.summary)
            assertEquals(2, records())
        }

    @Test
    fun `S17 a blank or over-long key fails only that element as invalid_request on idempotencyKey, without executing it`(): Unit =
        runBlocking {
            val badKeys = listOf("", "   ", "k".repeat(129))
            for ((n, bad) in badKeys.withIndex()) {
                val executed = mutableListOf<Int>()
                val execution = runBatch(listOf(el("t0-$n"), el("t1-$n", bad), el("t2-$n")), executed = executed, principal = "agent-$n")

                assertEquals(Outcome.Ok("r0"), execution.result.results[0], "key '${bad.take(5)}...' (length ${bad.length})")
                val error = errorAt(execution, 1)
                assertEquals(ErrorCode.INVALID_REQUEST, error.code, "key length ${bad.length}")
                val detail = assertIs<ErrorDetail.InvalidRequest>(error.detail)
                assertEquals("idempotencyKey", detail.fields.first().field)
                assertEquals(Outcome.Ok("r2"), execution.result.results[2])
                assertEquals(listOf(0, 2), executed, "the bad-key element is not executed")
                assertTrue(titles().none { it == "t1-$n" }, "no row for the bad-key element")
                assertEquals(0, records(), "a bad key is never recorded")
            }
            assertEquals(6, titles().size)
        }

    @Test
    fun `S17 atomic - a bad key at index 1 fails the whole batch at that index`(): Unit =
        runBlocking {
            val executed = mutableListOf<Int>()
            val execution = runBatch(listOf(el("t0"), el("t1", "k".repeat(129)), el("t2")), atomic = true, executed = executed)

            val failure = execution.result.failure()
            assertEquals(1, failure?.index)
            assertEquals(ErrorCode.INVALID_REQUEST, failure?.error?.code)
            assertEquals(listOf(0), executed)
            assertTrue(titles().isEmpty())
        }
}
