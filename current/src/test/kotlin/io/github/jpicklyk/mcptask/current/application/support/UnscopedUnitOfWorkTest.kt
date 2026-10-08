package io.github.jpicklyk.mcptask.current.application.support

import io.github.jpicklyk.mcptask.current.application.port.RepositoryProvider
import io.github.jpicklyk.mcptask.current.domain.error.DomainError
import io.github.jpicklyk.mcptask.current.domain.error.ErrorCode
import io.github.jpicklyk.mcptask.current.domain.error.Outcome
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Review follow-up B4 (item 9343ad8d): the no-transaction default [UnscopedUnitOfWork] honours the WriteScope hook
 * contract. Oracle: the WriteScope KDoc in the P5a declarations: afterCommit runs after commit "in a non-cancellable
 * context", a failure is logged and "does not change the Outcome"; afterRollback gets the translated error or null when a
 * non-persistence exception was rethrown, and is "skipped on cancellation"; UnscopedUnitOfWork KDoc: commit hooks on
 * Outcome.Ok, rollback hooks on Outcome.Err or a throw, "Cancellation is rethrown before rollback hooks run".
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class UnscopedUnitOfWorkTest {
    private val uow = UnscopedUnitOfWork(mockk<RepositoryProvider>(relaxed = true))

    private class Boom : Error("boom")

    @Test
    fun `afterCommit hooks run after an Ok block in registration order`(): Unit =
        runBlocking {
            val order = mutableListOf<String>()
            val result =
                uow.write("t.ok") {
                    afterCommit { order += "a" }
                    afterCommit { order += "b" }
                    afterRollback { order += "rollback" }
                    Outcome.Ok(7)
                }
            assertEquals(Outcome.Ok(7), result)
            assertEquals(listOf("a", "b"), order)
        }

    @Test
    fun `afterCommit hooks still run when the calling coroutine is cancelled right after the block completes`(): Unit =
        runBlocking {
            var ran = false
            // A child job, so cancelling it does not cancel the test body itself.
            val child =
                launch {
                    uow.write("t.cancel.after") {
                        afterCommit {
                            delay(10) // suspends: would throw if the hook ran in the cancelled context
                            ran = true
                        }
                        coroutineContext.job.cancel()
                        Outcome.Ok(1)
                    }
                }
            child.join()
            assertTrue(ran, "the commit hook must run non-cancellably")
        }

    @Test
    fun `a throwing afterCommit hook does not change the returned Outcome and later hooks still run`(): Unit =
        runBlocking {
            var second = false
            val result =
                uow.write("t.hookthrow") {
                    afterCommit { throw IllegalStateException("hook failed") }
                    afterCommit { second = true }
                    Outcome.Ok("value")
                }
            assertEquals(Outcome.Ok("value"), result)
            assertTrue(second, "a failing hook must not stop later hooks")
        }

    @Test
    fun `an Err block returns it unchanged runs afterRollback with that error and no commit hooks`(): Unit =
        runBlocking {
            val error = DomainError(ErrorCode.INTERNAL, "nope")
            var received: DomainError? = null
            var rollbackRuns = 0
            var commitRan = false
            val result =
                uow.write<Int>("t.err") {
                    afterCommit { commitRan = true }
                    afterRollback {
                        rollbackRuns++
                        received = it
                    }
                    Outcome.Err(error)
                }
            assertEquals(Outcome.Err(error), result)
            assertEquals(1, rollbackRuns)
            assertSame(error, received)
            assertFalse(commitRan)
        }

    @Test
    fun `a thrown exception runs afterRollback with null and is rethrown`(): Unit =
        runBlocking {
            var rollbackRuns = 0
            var received: DomainError? = DomainError(ErrorCode.INTERNAL, "sentinel")
            val thrown =
                assertFailsWith<IllegalArgumentException> {
                    uow.write<Int>("t.throw") {
                        afterRollback {
                            rollbackRuns++
                            received = it
                        }
                        throw IllegalArgumentException("bad")
                    }
                }
            assertEquals("bad", thrown.message)
            assertEquals(1, rollbackRuns)
            assertNull(received)
        }

    @Test
    fun `an Error subclass thrown by the block runs afterRollback and is rethrown`(): Unit =
        runBlocking {
            var rollbackRuns = 0
            var commitRan = false
            assertFailsWith<Boom> {
                uow.write<Int>("t.error") {
                    afterCommit { commitRan = true }
                    afterRollback { rollbackRuns++ }
                    throw Boom()
                }
            }
            assertEquals(1, rollbackRuns, "an Error is a non-cancellation Throwable: rollback hooks run")
            assertFalse(commitRan)
        }

    @Test
    fun `a CancellationException from the block is rethrown and afterRollback is skipped`(): Unit =
        runBlocking {
            var rollbackRuns = 0
            assertFailsWith<CancellationException> {
                uow.write<Int>("t.cancelled") {
                    afterRollback { rollbackRuns++ }
                    throw CancellationException("cancelled")
                }
            }
            assertEquals(0, rollbackRuns, "rollback hooks are skipped on cancellation")
        }
}
