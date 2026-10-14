package io.github.jpicklyk.mcptask.current.application.api

import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.telemetry.CallTelemetry
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.toCallPrincipal
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Independent tests (test-author seat, item b9527ef9) for [CallContext.provenance] and [AnswerProvenance] (scenario S13 of the
 * frozen test-plan, unit seam; NEW-SURFACE).
 *
 * Oracles: test-plan S13 (the provenance of a REST call is `(api:<id>, kind, proofStatus, host, sessionId null, runId/seat from
 * telemetry, served profile)`), task-scope item 9 (`CallPrincipal` gains `principal: Principal?`, null on MCP's lifted path
 * until R14; `CallContext` gains `profile` and `sessionId`; `host` is `Principal.host`), and the nullable return type of
 * `provenance()` (declared `AnswerProvenance?`): a call with no [Principal] has no provenance. Expected values are literals.
 *
 * `runId` and `seat` are read from [CallTelemetry] (first `setRun` wins); covered by the S13 telemetry tests below.
 *
 * Negative-assertion conditions: each `null` expectation is paired with a context that differs only by carrying a Principal and
 * does return a value, so a `provenance()` that always returned null (or never returned null) fails one of the pair.
 *
 * Domain invariants satisfied by construction: [Principal] has no validation beyond its types; the [CallPrincipal] is built by
 * the production `toCallPrincipal()` from the same [Principal], so claim, verification and principal agree by construction.
 */
class AnswerProvenanceTest {
    private fun principal(
        id: String = "svc-1",
        kind: PrincipalKind = PrincipalKind.SERVICE,
        proof: ProofStatus = ProofStatus.ISSUED,
        host: String? = null,
    ) = Principal(
        id = id,
        kind = kind,
        proofStatus = proof,
        scope = AccessScope.unrestricted(),
        capabilities = setOf(ApiCapability.READ),
        host = host,
    )

    private fun context(
        principal: CallPrincipal,
        profile: String? = null,
        sessionId: String? = null,
    ) = CallContext(
        reqId = "k7f3q9ab",
        surface = "rest",
        principal = principal,
        profile = profile,
        sessionId = sessionId,
    )

    @Test
    fun `S13 a context with a principal returns its provenance`() =
        runBlocking {
            val p = principal(id = "dash-1", kind = PrincipalKind.AGENT, proof = ProofStatus.VERIFIED, host = "dashboard")
            val prov = assertNotNull(context(p.toCallPrincipal(), profile = "rest").provenance())
            assertEquals("api:dash-1", prov.principalId)
            assertEquals(PrincipalKind.AGENT, prov.kind)
            assertEquals(ProofStatus.VERIFIED, prov.proofStatus)
            assertEquals("dashboard", prov.host)
            assertEquals("rest", prov.profile)
            assertNull(prov.sessionId)
            assertNull(prov.runId)
            assertNull(prov.seat)
        }

    @Test
    fun `S13 the MCP lifted path carries a claim but no Principal and has no provenance`() =
        runBlocking {
            val lifted =
                CallPrincipal(
                    claim = ActorClaim(id = "orchestrator", kind = ActorKind.SUBAGENT),
                    verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop"),
                )
            assertNull(lifted.principal, "fixture: the default Principal of a CallPrincipal is null")
            assertNull(context(lifted).provenance())
        }

    @Test
    fun `S13 every kind and proof status passes through unchanged`() =
        runBlocking {
            for (kind in PrincipalKind.entries) {
                for (proof in ProofStatus.entries) {
                    val prov = assertNotNull(context(principal("p-${kind.wire}-${proof.wire}", kind, proof).toCallPrincipal()).provenance())
                    assertEquals(kind, prov.kind, "kind of $kind/$proof")
                    assertEquals(proof, prov.proofStatus, "proof of $kind/$proof")
                    val id = "p-${kind.wire}-${proof.wire}"
                    assertEquals(if (proof == ProofStatus.NONE) id else "api:$id", prov.principalId, "NONE audits as the bare id")
                }
            }
        }

    @Test
    fun `S13 the served profile and the session id come from the context`() =
        runBlocking {
            val cp = principal().toCallPrincipal()
            val director = assertNotNull(context(cp, profile = "director", sessionId = "s-1").provenance())
            assertEquals("director", director.profile)
            assertEquals("s-1", director.sessionId)

            val rest = assertNotNull(context(cp, profile = "rest", sessionId = null).provenance())
            assertEquals("rest", rest.profile)
            assertNull(rest.sessionId)

            val unprofiled = assertNotNull(context(cp, profile = null, sessionId = null).provenance())
            assertNull(unprofiled.profile)
        }

    @Test
    fun `S13 the host comes from the Principal and is null when the Principal has none`() =
        runBlocking {
            assertEquals("pi", context(principal(host = "pi").toCallPrincipal()).provenance()?.host)
            assertNull(assertNotNull(context(principal(host = null).toCallPrincipal()).provenance()).host)
        }

    @Test
    fun `S13 two contexts answer independently`() =
        runBlocking {
            val a =
                assertNotNull(
                    context(principal("one", PrincipalKind.HUMAN, ProofStatus.VERIFIED).toCallPrincipal(), "knowledge").provenance()
                )
            val b = assertNotNull(context(principal("two", PrincipalKind.SERVICE, ProofStatus.NONE).toCallPrincipal(), "rest").provenance())
            assertEquals("api:one", a.principalId)
            assertEquals("two", b.principalId, "a NONE principal audits as the bare id")
            assertEquals(PrincipalKind.HUMAN, a.kind)
            assertEquals(PrincipalKind.SERVICE, b.kind)
            assertEquals("knowledge", a.profile)
            assertEquals("rest", b.profile)
        }

    @Test
    fun `S13 an installed telemetry element without run or seat leaves both null`() =
        runBlocking {
            val telemetry = CallTelemetry(reqId = "k7f3q9ab", surface = "rest", sessionId = null)
            val prov =
                withContext(telemetry) {
                    assertNotNull(context(principal().toCallPrincipal()).provenance())
                }
            assertNull(prov.runId)
            assertNull(prov.seat)
        }

    private fun telemetry() = CallTelemetry(reqId = "k7f3q9ab", surface = "rest", sessionId = null)

    private val runA = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val runB = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @Test
    fun `S13 a recorded run and seat come from the telemetry`() =
        runBlocking {
            val t = telemetry()
            t.setRun(runA, "implementer")
            val prov = withContext(t) { assertNotNull(context(principal().toCallPrincipal()).provenance()) }
            assertEquals(runA, prov.runId)
            assertEquals("implementer", prov.seat)
        }

    @Test
    fun `S13 a recorded run with a null seat reports the run and no seat`() =
        runBlocking {
            val t = telemetry()
            t.setRun(runA, null)
            val prov = withContext(t) { assertNotNull(context(principal().toCallPrincipal()).provenance()) }
            assertEquals(runA, prov.runId)
            assertNull(prov.seat)
        }

    @Test
    fun `S13 the first recorded run wins over a second setRun`() =
        runBlocking {
            val t = telemetry()
            t.setRun(runA, "implementer")
            t.setRun(runB, "reviewer")
            val prov = withContext(t) { assertNotNull(context(principal().toCallPrincipal()).provenance()) }
            assertEquals(runA, prov.runId)
            assertEquals("implementer", prov.seat)
        }

    @Test
    fun `S13 no telemetry element leaves run and seat null`() =
        runBlocking {
            val prov = assertNotNull(context(principal().toCallPrincipal()).provenance())
            assertNull(prov.runId)
            assertNull(prov.seat)
        }

    @Test
    fun `the CallPrincipal built from a Principal carries that same Principal`() {
        val p = principal(id = "carry-1", kind = PrincipalKind.HUMAN, proof = ProofStatus.VERIFIED_STALE, host = "pi")
        assertEquals(p, p.toCallPrincipal().principal)
    }
}
