package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.application.tools.toJson
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerificationResultTest {
    // -------------------------------------------------------------------------
    // fromString — new values round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `fromString round-trips ABSENT`() {
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("ABSENT"))
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("absent"))
    }

    @Test
    fun `fromString round-trips UNCHECKED`() {
        assertEquals(VerificationStatus.UNCHECKED, VerificationStatus.fromString("UNCHECKED"))
        assertEquals(VerificationStatus.UNCHECKED, VerificationStatus.fromString("unchecked"))
    }

    @Test
    fun `fromString round-trips VERIFIED`() {
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("VERIFIED"))
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("verified"))
    }

    @Test
    fun `fromString round-trips REJECTED`() {
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("REJECTED"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("rejected"))
    }

    @Test
    fun `fromString round-trips UNAVAILABLE`() {
        assertEquals(VerificationStatus.UNAVAILABLE, VerificationStatus.fromString("UNAVAILABLE"))
        assertEquals(VerificationStatus.UNAVAILABLE, VerificationStatus.fromString("unavailable"))
    }

    // -------------------------------------------------------------------------
    // fromString — legacy back-compat mapping
    // -------------------------------------------------------------------------

    @Test
    fun `fromString maps legacy unverified to ABSENT`() {
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("unverified"))
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("UNVERIFIED"))
    }

    @Test
    fun `fromString maps legacy failed to REJECTED`() {
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("failed"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("FAILED"))
    }

    @Test
    fun `fromString throws on unknown value`() {
        assertFailsWith<IllegalArgumentException> {
            VerificationStatus.fromString("TOTALLY_UNKNOWN")
        }
    }

    // -------------------------------------------------------------------------
    // toJsonString
    // -------------------------------------------------------------------------

    @Test
    fun `toJsonString returns lowercase for all values`() {
        assertEquals("absent", VerificationStatus.ABSENT.toJsonString())
        assertEquals("unchecked", VerificationStatus.UNCHECKED.toJsonString())
        assertEquals("verified", VerificationStatus.VERIFIED.toJsonString())
        assertEquals("rejected", VerificationStatus.REJECTED.toJsonString())
        assertEquals("unavailable", VerificationStatus.UNAVAILABLE.toJsonString())
    }

    // -------------------------------------------------------------------------
    // VerificationResult data class
    // -------------------------------------------------------------------------

    @Test
    fun `metadata defaults to empty map`() {
        val result = VerificationResult(status = VerificationStatus.VERIFIED)
        assertEquals(emptyMap<String, String>(), result.metadata)
    }

    @Test
    fun `metadata can be populated`() {
        val result =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                metadata = mapOf("failureKind" to "crypto")
            )
        assertEquals("crypto", result.metadata["failureKind"])
    }

    // -------------------------------------------------------------------------
    // toJson — metadata field only emitted when non-empty
    // -------------------------------------------------------------------------

    @Test
    fun `toJson omits metadata field when empty`() {
        val result =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "jwks"
            )
        val json = result.toJson()
        assertFalse(json.containsKey("metadata"), "metadata key should be absent when map is empty")
        assertEquals("verified", json["status"]?.jsonPrimitive?.content)
        assertEquals("jwks", json["verifier"]?.jsonPrimitive?.content)
    }

    @Test
    fun `toJson includes metadata field when non-empty`() {
        val result =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                verifier = "jwks",
                reason = "token expired",
                metadata = mapOf("failureKind" to "claims")
            )
        val json = result.toJson()
        assertTrue(json.containsKey("metadata"), "metadata key should be present")
        val meta = json["metadata"]?.jsonObject
        assertEquals("claims", meta?.get("failureKind")?.jsonPrimitive?.content)
    }

    @Test
    fun `toJson includes multiple metadata entries`() {
        val result =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "jwks",
                metadata =
                    mapOf(
                        "verifiedFromCache" to "true",
                        "cacheAgeSeconds" to "450"
                    )
            )
        val json = result.toJson()
        val meta = json["metadata"]?.jsonObject
        assertEquals("true", meta?.get("verifiedFromCache")?.jsonPrimitive?.content)
        assertEquals("450", meta?.get("cacheAgeSeconds")?.jsonPrimitive?.content)
    }

    @Test
    fun `toJson reason field only emitted when non-null`() {
        val withReason =
            VerificationResult(
                status = VerificationStatus.REJECTED,
                reason = "some reason"
            )
        val withoutReason = VerificationResult(status = VerificationStatus.VERIFIED)
        assertTrue(withReason.toJson().containsKey("reason"))
        assertFalse(withoutReason.toJson().containsKey("reason"))
    }

    @Test
    fun `toJson status uses lowercase toJsonString`() {
        VerificationStatus.entries.forEach { status ->
            val result = VerificationResult(status = status)
            val json = result.toJson()
            assertEquals(
                status.toJsonString(),
                json["status"]?.jsonPrimitive?.content,
                "status JSON value should match toJsonString() for $status"
            )
        }
    }
}
