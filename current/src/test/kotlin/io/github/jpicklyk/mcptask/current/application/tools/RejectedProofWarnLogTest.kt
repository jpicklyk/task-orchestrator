package io.github.jpicklyk.mcptask.current.application.tools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item b1addc2b -- "Fail closed on unparseable global config and
 * invalid actor_authentication values". Covers test-plan scenarios S4 and S14, plus the
 * "reason=null omits the proof" adversarial probe, for [ActorAware.resolveTrustedActorId] under
 * [DegradedModePolicy.ACCEPT_CACHED].
 *
 * Oracle source: the item's frozen `diagnosis` note (decision D5) and `test-plan` note (queue
 * phase) -- never this file's own reading of the fixed source. Scenario labelled EXISTING-SURFACE
 * per the test-plan: [ActorAware.resolveTrustedActorId]'s signature, [PolicyResolution], and every
 * [VerificationStatus] value are all pre-existing. Only the internal REJECTED branch's behavior
 * changes -- pre-fix, REJECTED silently fell into the same `else` branch as ABSENT/UNCHECKED with
 * no logging at all; post-fix it gets its own WARN naming the verifier and reason (never the
 * bearer proof). A plain revert of the fix (restoring the single silent `else` branch) therefore
 * yields behavioral red directly (`warnings.size` goes from 1 to 0), with no signature change and
 * no narrowest-revert recipe required.
 *
 * [ActorAware]'s logger has no injection seam (`private val logger =
 * LoggerFactory.getLogger(ActorAware::class.java)`), so its WARN output is captured externally via
 * a Logback [ListAppender] attached to that logger name -- the same convention already used by
 * `EnvBooleanTest.captureWarnLogs` and `DidDocumentJwksExtractorTest`.
 */
class RejectedProofWarnLogTest {
    /** Captures WARN-level log records emitted by [ActorAware] during [block]. */
    private fun captureWarnLogs(block: () -> Unit): List<String> {
        val logbackLogger =
            LoggerFactory.getLogger("io.github.jpicklyk.mcptask.current.application.tools.ActorAware") as Logger
        val listAppender =
            ListAppender<ILoggingEvent>().also {
                it.start()
                logbackLogger.addAppender(it)
            }
        val savedLevel = logbackLogger.level
        logbackLogger.level = Level.WARN
        try {
            block()
            return listAppender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logbackLogger.detachAppender(listAppender)
            logbackLogger.level = savedLevel
        }
    }

    // -------------------------------------------------------------------------
    // S4 -- ACCEPT_CACHED + REJECTED: Trusted(self-reported id) + exactly one WARN naming the
    // verifier and reason, and NEVER the bearer proof. D5
    // -------------------------------------------------------------------------

    @Test
    fun `S4 ACCEPT_CACHED REJECTED trusts the self-reported id and logs exactly one WARN naming verifier and reason, never the proof`() {
        val claim = ActorClaim(id = "a", kind = ActorKind.SUBAGENT, proof = "P-secret-proof-token-12345")
        val verification =
            VerificationResult(status = VerificationStatus.REJECTED, verifier = "jwks", reason = "signature mismatch")

        lateinit var result: PolicyResolution
        val warnings =
            captureWarnLogs {
                result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
            }

        assertTrue(result is PolicyResolution.Trusted, "expected Trusted, got $result")
        assertEquals("a", (result as PolicyResolution.Trusted).trustedId)

        assertEquals(1, warnings.size, "exactly one WARN must be logged for a REJECTED verification under accept-cached")
        val message = warnings.single()
        assertTrue(message.contains("jwks"), "WARN must name the verifier: $message")
        assertTrue(message.contains("signature mismatch"), "WARN must include the rejection reason: $message")
        assertFalse(message.contains("P-secret-proof-token-12345"), "WARN must never include the bearer proof: $message")
    }

    // -------------------------------------------------------------------------
    // S14 -- ACCEPT_CACHED + ABSENT / UNCHECKED: Trusted(self-reported id), zero WARNs. D5
    // -------------------------------------------------------------------------

    @Test
    fun `S14 ACCEPT_CACHED ABSENT trusts the self-reported id with zero WARNs`() {
        val claim = ActorClaim(id = "b", kind = ActorKind.SUBAGENT)
        val verification = VerificationResult(status = VerificationStatus.ABSENT, verifier = "jwks")

        lateinit var result: PolicyResolution
        val warnings =
            captureWarnLogs {
                result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
            }

        assertTrue(result is PolicyResolution.Trusted, "expected Trusted, got $result")
        assertEquals("b", (result as PolicyResolution.Trusted).trustedId)
        assertEquals(0, warnings.size, "ABSENT must not log a WARN under accept-cached")
    }

    @Test
    fun `S14 ACCEPT_CACHED UNCHECKED trusts the self-reported id with zero WARNs`() {
        val claim = ActorClaim(id = "c", kind = ActorKind.SUBAGENT)
        val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

        lateinit var result: PolicyResolution
        val warnings =
            captureWarnLogs {
                result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
            }

        assertTrue(result is PolicyResolution.Trusted, "expected Trusted, got $result")
        assertEquals("c", (result as PolicyResolution.Trusted).trustedId)
        assertEquals(0, warnings.size, "UNCHECKED must not log a WARN under accept-cached")
    }

    // -------------------------------------------------------------------------
    // Adversarial probe: a REJECTED verification with a null reason must still log its one WARN
    // and must still never leak the proof.
    // -------------------------------------------------------------------------

    @Test
    fun `probe REJECTED with a null reason still logs exactly one WARN, still omitting the proof`() {
        val claim = ActorClaim(id = "d", kind = ActorKind.SUBAGENT, proof = "another-secret-proof-token")
        val verification = VerificationResult(status = VerificationStatus.REJECTED, verifier = "jwks", reason = null)

        lateinit var result: PolicyResolution
        val warnings =
            captureWarnLogs {
                result = ActorAware.resolveTrustedActorId(claim, verification, DegradedModePolicy.ACCEPT_CACHED)
            }

        assertTrue(result is PolicyResolution.Trusted, "expected Trusted, got $result")
        assertEquals("d", (result as PolicyResolution.Trusted).trustedId)
        assertEquals(1, warnings.size, "a null reason must not suppress the WARN")
        assertFalse(warnings.single().contains("another-secret-proof-token"), "WARN must never include the bearer proof")
    }
}
