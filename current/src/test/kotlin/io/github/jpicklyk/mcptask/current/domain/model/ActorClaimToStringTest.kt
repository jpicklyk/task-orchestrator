package io.github.jpicklyk.mcptask.current.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for item 983615e7 (diagnosis Fix step 9, closing item 3dcfcbab's finding 3):
 * [ActorClaim.toString] must never leak the raw bearer proof — e.g. into a log line or exception
 * message that happens to interpolate an `ActorClaim` via its default/overridden `toString()`.
 *
 * Test-plan scenario S10 (EXISTING-SURFACE: `ActorClaim`'s constructor and fields already existed;
 * only `toString()` is overridden by this fix).
 *
 * Oracle: dispatch declaration for `ActorClaim.toString` — exactly
 * `"ActorClaim(id=$id, kind=$kind, parent=$parent, proof=<redacted>)"` when `proof != null`, and
 * `"...proof=null)"` when `proof == null`.
 */
class ActorClaimToStringTest {
    @Test
    fun `S10 toString with a non-null proof redacts the proof and omits its content`() {
        val claim =
            ActorClaim(
                id = "agent-1",
                kind = ActorKind.SUBAGENT,
                parent = "orchestrator-1",
                proof = "eyJ.SECRET.sig"
            )

        val rendered = claim.toString()

        assertFalse(rendered.contains("SECRET"), "toString() must never contain the raw proof content: $rendered")
        assertFalse(rendered.contains("eyJ.SECRET.sig"), "toString() must never contain the raw proof verbatim: $rendered")
        assertEquals(
            "ActorClaim(id=agent-1, kind=SUBAGENT, parent=orchestrator-1, proof=<redacted>)",
            rendered
        )
    }

    @Test
    fun `S10 toString with a null proof renders proof=null`() {
        val claim = ActorClaim(id = "agent-2", kind = ActorKind.ORCHESTRATOR)

        val rendered = claim.toString()

        assertEquals("ActorClaim(id=agent-2, kind=ORCHESTRATOR, parent=null, proof=null)", rendered)
    }

    @Test
    fun `S10 toString with a blank (non-null) proof still redacts rather than rendering blank`() {
        // A blank proof is still non-null, so per the declared contract it takes the
        // proof=<redacted> branch rather than proof=null — guards against a `proof.isNullOrBlank()`
        // shortcut being used instead of a plain null check.
        val claim = ActorClaim(id = "agent-3", kind = ActorKind.USER, proof = "   ")

        val rendered = claim.toString()

        assertTrue(rendered.endsWith("proof=<redacted>)"), "a non-null (even blank) proof must redact, not render null: $rendered")
    }

    /**
     * Independent test-author addition for item 3dcfcbab, test-plan scenario S21: a real
     * JWT-shaped proof (three base64url segments, header.payload.signature) must never appear -
     * whole or by any segment - in [ActorClaim.toString]. Regression lock: expected green both
     * before and after 3dcfcbab, since this fix does not touch [ActorClaim.toString] itself.
     */
    @Test
    fun `S21 toString with a real JWT-shaped proof leaks no segment of the token`() {
        // header.payload.signature - three distinct, non-trivial base64url segments, matching the
        // real shape a bearer JWT proof takes on the wire.
        val header = "eyJhbGciOiJSUzI1NiIsImtpZCI6InMyMS10ZXN0LWtleSJ9"
        val payload = "eyJzdWIiOiJhZ2VudC1zMjEiLCJpc3MiOiJodHRwczovL3Rlc3QtaXNzdWVyLmV4YW1wbGUifQ"
        val signature = "S21FAKESIGNATUREsegmentThatMustNeverAppearInToString"
        val realJwt = "$header.$payload.$signature"

        val claim = ActorClaim(id = "agent-s21", kind = ActorKind.SUBAGENT, proof = realJwt)

        val rendered = claim.toString()

        assertFalse(rendered.contains(realJwt), "toString() must never contain the full JWT verbatim: $rendered")
        assertFalse(rendered.contains(header), "toString() must never leak the JWT header segment: $rendered")
        assertFalse(rendered.contains(payload), "toString() must never leak the JWT payload segment: $rendered")
        assertFalse(rendered.contains(signature), "toString() must never leak the JWT signature segment: $rendered")
        assertEquals(
            "ActorClaim(id=agent-s21, kind=SUBAGENT, parent=null, proof=<redacted>)",
            rendered
        )
    }
}
