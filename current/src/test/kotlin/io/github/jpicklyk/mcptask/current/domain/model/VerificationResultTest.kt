package io.github.jpicklyk.mcptask.current.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
