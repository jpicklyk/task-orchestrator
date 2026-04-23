package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ActorClaimTest {
    // --- ActorKind ---

    @Test
    fun `ActorKind fromString round-trip all values`() {
        assertEquals(ActorKind.ORCHESTRATOR, ActorKind.fromString("ORCHESTRATOR"))
        assertEquals(ActorKind.SUBAGENT, ActorKind.fromString("SUBAGENT"))
        assertEquals(ActorKind.USER, ActorKind.fromString("USER"))
        assertEquals(ActorKind.EXTERNAL, ActorKind.fromString("EXTERNAL"))
    }

    @Test
    fun `ActorKind fromString case insensitive`() {
        assertEquals(ActorKind.ORCHESTRATOR, ActorKind.fromString("orchestrator"))
        assertEquals(ActorKind.SUBAGENT, ActorKind.fromString("subagent"))
        assertEquals(ActorKind.USER, ActorKind.fromString("user"))
        assertEquals(ActorKind.EXTERNAL, ActorKind.fromString("external"))
    }

    @Test
    fun `ActorKind fromString invalid throws`() {
        assertFailsWith<IllegalArgumentException> {
            ActorKind.fromString("INVALID")
        }
    }

    @Test
    fun `ActorKind toJsonString returns lowercase name`() {
        assertEquals("orchestrator", ActorKind.ORCHESTRATOR.toJsonString())
        assertEquals("subagent", ActorKind.SUBAGENT.toJsonString())
        assertEquals("user", ActorKind.USER.toJsonString())
        assertEquals("external", ActorKind.EXTERNAL.toJsonString())
    }

    // --- ActorClaim valid creation ---

    @Test
    fun `ActorClaim valid creation`() {
        val claim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT)
        assertEquals("agent-1", claim.id)
        assertEquals(ActorKind.SUBAGENT, claim.kind)
        assertNull(claim.parent)
        assertNull(claim.proof)
    }

    @Test
    fun `ActorClaim valid creation with all fields`() {
        val claim =
            ActorClaim(
                id = "agent-1",
                kind = ActorKind.SUBAGENT,
                parent = "orchestrator-1",
                proof = "some-proof-token"
            )
        assertEquals("agent-1", claim.id)
        assertEquals(ActorKind.SUBAGENT, claim.kind)
        assertEquals("orchestrator-1", claim.parent)
        assertEquals("some-proof-token", claim.proof)
    }

    // --- ActorClaim validation failures ---

    @Test
    fun `ActorClaim blank id throws ValidationException`() {
        assertFailsWith<ValidationException> {
            ActorClaim(id = "   ", kind = ActorKind.USER)
        }
    }

    @Test
    fun `ActorClaim empty id throws ValidationException`() {
        assertFailsWith<ValidationException> {
            ActorClaim(id = "", kind = ActorKind.USER)
        }
    }

    @Test
    fun `ActorClaim id exceeding 500 chars throws ValidationException`() {
        val longId = "a".repeat(501)
        assertFailsWith<ValidationException> {
            ActorClaim(id = longId, kind = ActorKind.USER)
        }
    }

    @Test
    fun `ActorClaim id at exactly 500 chars is valid`() {
        val id = "a".repeat(500)
        val claim = ActorClaim(id = id, kind = ActorKind.USER)
        assertEquals(500, claim.id.length)
    }

    @Test
    fun `ActorClaim parent exceeding 500 chars throws ValidationException`() {
        val longParent = "p".repeat(501)
        assertFailsWith<ValidationException> {
            ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, parent = longParent)
        }
    }

    @Test
    fun `ActorClaim proof exceeding 10000 chars throws ValidationException`() {
        val longProof = "x".repeat(10001)
        assertFailsWith<ValidationException> {
            ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = longProof)
        }
    }

    @Test
    fun `ActorClaim proof at exactly 10000 chars is valid`() {
        val proof = "x".repeat(10000)
        val claim = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, proof = proof)
        assertEquals(10000, claim.proof!!.length)
    }

    // --- VerificationStatus ---

    @Test
    fun `VerificationStatus fromString round-trip all new values`() {
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("ABSENT"))
        assertEquals(VerificationStatus.UNCHECKED, VerificationStatus.fromString("UNCHECKED"))
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("VERIFIED"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("REJECTED"))
        assertEquals(VerificationStatus.UNAVAILABLE, VerificationStatus.fromString("UNAVAILABLE"))
    }

    @Test
    fun `VerificationStatus fromString legacy back-compat mapping`() {
        // Legacy stored values must map to conservative new values.
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("unverified"))
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("UNVERIFIED"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("failed"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("FAILED"))
    }

    @Test
    fun `VerificationStatus fromString case insensitive for new values`() {
        assertEquals(VerificationStatus.ABSENT, VerificationStatus.fromString("absent"))
        assertEquals(VerificationStatus.UNCHECKED, VerificationStatus.fromString("unchecked"))
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("verified"))
        assertEquals(VerificationStatus.REJECTED, VerificationStatus.fromString("rejected"))
        assertEquals(VerificationStatus.UNAVAILABLE, VerificationStatus.fromString("unavailable"))
    }

    @Test
    fun `VerificationStatus fromString invalid throws`() {
        assertFailsWith<IllegalArgumentException> {
            VerificationStatus.fromString("UNKNOWN")
        }
    }

    @Test
    fun `VerificationStatus toJsonString returns lowercase name`() {
        assertEquals("absent", VerificationStatus.ABSENT.toJsonString())
        assertEquals("unchecked", VerificationStatus.UNCHECKED.toJsonString())
        assertEquals("verified", VerificationStatus.VERIFIED.toJsonString())
        assertEquals("rejected", VerificationStatus.REJECTED.toJsonString())
        assertEquals("unavailable", VerificationStatus.UNAVAILABLE.toJsonString())
    }

    // --- VerificationResult ---

    @Test
    fun `VerificationResult valid creation with all fields`() {
        val result =
            VerificationResult(
                status = VerificationStatus.VERIFIED,
                verifier = "my-verifier",
                reason = "signature matched"
            )
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertEquals("my-verifier", result.verifier)
        assertEquals("signature matched", result.reason)
    }

    @Test
    fun `VerificationResult valid creation with minimal fields`() {
        val result = VerificationResult(status = VerificationStatus.ABSENT)
        assertEquals(VerificationStatus.ABSENT, result.status)
        assertNull(result.verifier)
        assertNull(result.reason)
    }

    @Test
    fun `VerificationResult metadata defaults to empty map`() {
        val result = VerificationResult(status = VerificationStatus.VERIFIED)
        assertEquals(emptyMap<String, String>(), result.metadata)
    }

    @Test
    fun `VerificationResult metadata populated`() {
        val result = VerificationResult(
            status = VerificationStatus.REJECTED,
            metadata = mapOf("failureKind" to "crypto")
        )
        assertEquals("crypto", result.metadata["failureKind"])
    }
}
