package io.github.jpicklyk.mcptask.current.application.telemetry

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Independent tests for item 8abb69e2 (P10): the per-call [CallTelemetry] coroutine element and its no-op-when-absent
 * recorders. Oracle: task-scope What-to-build item 3 (frozen): the element holds reqId, surface and sessionId plus
 * thread-safe accumulators (principal: first recorded wins; retry count; replayed flag; target versions; result and
 * eligible counts), `currentCallTelemetry()` returns it or null, and every setter is a no-op when absent.
 */
class CallTelemetryTest {
    private fun telemetry() = CallTelemetry(reqId = "k7f3q9ab", surface = "mcp", sessionId = "session-1")

    @Test
    fun `a fresh element carries its identity and empty accumulators`() {
        val t = telemetry()
        assertEquals("k7f3q9ab", t.reqId)
        assertEquals("mcp", t.surface)
        assertEquals("session-1", t.sessionId)
        assertNull(t.principal)
        assertEquals(0, t.retries)
        assertFalse(t.replayed)
        assertTrue(t.targetVersions.isEmpty())
        assertNull(t.resultCount)
        assertNull(t.eligibleCount)
    }

    @Test
    fun `the principal is first-recorded-wins`() {
        val t = telemetry()
        t.setPrincipal(CallTelemetry.Principal("first", "subagent", "absent"))
        t.setPrincipal(CallTelemetry.Principal("second", "user", "verified"))
        assertEquals(CallTelemetry.Principal("first", "subagent", "absent"), t.principal)
    }

    @Test
    fun `retries replayed versions and counts accumulate`() {
        val t = telemetry()
        t.incrementRetry()
        t.incrementRetry()
        t.setReplayed()
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        t.setTargetVersion(a, 3)
        t.setTargetVersion(b, 7)
        t.setResultCounts(4, 9)
        assertEquals(2, t.retries)
        assertTrue(t.replayed)
        assertEquals(mapOf(a to 3L, b to 7L), t.targetVersions)
        assertEquals(4, t.resultCount)
        assertEquals(9, t.eligibleCount)
    }

    @Test
    fun `concurrent retry increments are not lost`() {
        val t = telemetry()
        runBlocking {
            (1..8)
                .map { async(Dispatchers.Default) { repeat(1000) { t.incrementRetry() } } }
                .awaitAll()
        }
        assertEquals(8000, t.retries)
    }

    @Test
    fun `currentCallTelemetry and currentReqId are null outside the element and the installed instance inside it`() =
        runBlocking {
            assertNull(currentCallTelemetry())
            assertNull(currentReqId())
            val t = telemetry()
            withContext(t) {
                assertSame(t, currentCallTelemetry())
                assertEquals("k7f3q9ab", currentReqId())
                withContext(Dispatchers.IO) { assertSame(t, currentCallTelemetry(), "survives a dispatcher hop") }
            }
            assertNull(currentCallTelemetry(), "the element does not leak out of withContext")
        }

    @Test
    fun `every recorder is a silent no-op when no element is installed`() =
        runBlocking {
            recordCallRetry()
            recordCallReplayed()
            recordCallTargetVersion(UUID.randomUUID(), 1)
            recordCallResultCounts(1, 2)
            recordCallPrincipal(
                ActorClaim(id = "a1", kind = ActorKind.SUBAGENT),
                VerificationResult(status = VerificationStatus.ABSENT, verifier = "none")
            )
            assertNull(currentCallTelemetry())
        }

    @Test
    fun `the recorders write into the installed element`() =
        runBlocking {
            val t = telemetry()
            val id = UUID.randomUUID()
            withContext(t) {
                recordCallRetry()
                recordCallRetry()
                recordCallReplayed()
                recordCallTargetVersion(id, 5)
                recordCallResultCounts(0, 3)
                recordCallPrincipal(
                    ActorClaim(id = "a1", kind = ActorKind.SUBAGENT),
                    VerificationResult(status = VerificationStatus.ABSENT, verifier = "none")
                )
                recordCallPrincipal(
                    ActorClaim(id = "a2", kind = ActorKind.USER),
                    VerificationResult(status = VerificationStatus.VERIFIED, verifier = "jwks")
                )
            }
            assertEquals(2, t.retries)
            assertTrue(t.replayed)
            assertEquals(mapOf(id to 5L), t.targetVersions)
            assertEquals(0, t.resultCount)
            assertEquals(3, t.eligibleCount)
            val principal = t.principal
            assertEquals("a1", principal?.id, "first claim wins")
            assertEquals("subagent", principal?.kind)
            assertEquals(VerificationStatus.ABSENT.toJsonString(), principal?.proofStatus)
        }
}
