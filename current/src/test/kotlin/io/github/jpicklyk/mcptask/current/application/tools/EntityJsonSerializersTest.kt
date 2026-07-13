package io.github.jpicklyk.mcptask.current.application.tools

import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exhaustive coverage of [VerificationResult.toJsonOrOmit], the shared helper used by all four
 * emission sites (Note.toJson, ManageNotesTool upsert response, AdvanceItemTool transition
 * results, GetContextTool recentTransitions) to decide whether a verification block is included.
 */
class EntityJsonSerializersTest {
    @Test
    fun `toJsonOrOmit returns null for a no-op verifier regardless of status`() {
        val result = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
        assertNull(result.toJsonOrOmit())
    }

    @Test
    fun `toJsonOrOmit returns null for a no-op verifier even with reason and metadata`() {
        // Guards against a future noop variant that starts attaching a reason/metadata —
        // omission is keyed on verifier identity, not on payload richness.
        val result =
            VerificationResult(
                status = VerificationStatus.UNCHECKED,
                verifier = "noop",
                reason = "should still be omitted",
                metadata = mapOf("k" to "v")
            )
        assertNull(result.toJsonOrOmit())
    }

    @Test
    fun `toJsonOrOmit serializes a non-noop verified result in full`() {
        val result =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "jwks",
                reason = "signature and claims valid",
                metadata = mapOf("verifiedFromCache" to "true", "cacheAgeSeconds" to "12")
            )

        val json = result.toJsonOrOmit()
        assertEquals(result.toJson(), json)
        requireNotNull(json)
        assertEquals("verified", json["status"]!!.jsonPrimitive.content)
        assertEquals("jwks", json["verifier"]!!.jsonPrimitive.content)
        assertEquals("signature and claims valid", json["reason"]!!.jsonPrimitive.content)
        val metadata = json["metadata"]!!.jsonObject
        assertEquals("true", metadata["verifiedFromCache"]!!.jsonPrimitive.content)
        assertEquals("12", metadata["cacheAgeSeconds"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toJsonOrOmit serializes a non-noop rejected result in full`() {
        val result =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                verifier = "jwks",
                reason = "signature invalid",
                metadata = mapOf("failureKind" to "crypto")
            )

        val json = result.toJsonOrOmit()
        assertEquals(result.toJson(), json)
        requireNotNull(json)
        assertEquals("rejected", json["status"]!!.jsonPrimitive.content)
        assertEquals("crypto", json["metadata"]!!.jsonObject["failureKind"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toJsonOrOmit serializes an UNCHECKED status from a non-noop verifier`() {
        // Decision rule: omission is keyed on verifier == "noop", not on status == UNCHECKED —
        // a future verifier could legitimately return UNCHECKED with a meaningful reason.
        val result =
            VerificationResult(
                status = VerificationStatus.UNCHECKED,
                verifier = "jwks-degraded",
                reason = "JWKS endpoint unreachable; degraded mode accepted claim"
            )

        val json = result.toJsonOrOmit()
        requireNotNull(json)
        assertEquals("unchecked", json["status"]!!.jsonPrimitive.content)
        assertEquals("jwks-degraded", json["verifier"]!!.jsonPrimitive.content)
    }

    @Test
    fun `toJsonOrOmit serializes a result with null verifier`() {
        val result = VerificationResult(status = VerificationStatus.ABSENT, verifier = null)
        val json = result.toJsonOrOmit()
        requireNotNull(json)
        assertEquals("absent", json["status"]!!.jsonPrimitive.content)
        assertNull(json["verifier"])
    }
}
