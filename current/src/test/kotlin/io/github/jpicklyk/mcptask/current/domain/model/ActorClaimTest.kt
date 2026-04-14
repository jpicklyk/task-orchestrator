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
        val claim = ActorClaim(
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
    fun `VerificationStatus fromString round-trip all values`() {
        assertEquals(VerificationStatus.UNVERIFIED, VerificationStatus.fromString("UNVERIFIED"))
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("VERIFIED"))
        assertEquals(VerificationStatus.FAILED, VerificationStatus.fromString("FAILED"))
    }

    @Test
    fun `VerificationStatus fromString case insensitive`() {
        assertEquals(VerificationStatus.UNVERIFIED, VerificationStatus.fromString("unverified"))
        assertEquals(VerificationStatus.VERIFIED, VerificationStatus.fromString("verified"))
        assertEquals(VerificationStatus.FAILED, VerificationStatus.fromString("failed"))
    }

    @Test
    fun `VerificationStatus fromString invalid throws`() {
        assertFailsWith<IllegalArgumentException> {
            VerificationStatus.fromString("UNKNOWN")
        }
    }

    @Test
    fun `VerificationStatus toJsonString returns lowercase name`() {
        assertEquals("unverified", VerificationStatus.UNVERIFIED.toJsonString())
        assertEquals("verified", VerificationStatus.VERIFIED.toJsonString())
        assertEquals("failed", VerificationStatus.FAILED.toJsonString())
    }

    // --- VerificationResult ---

    @Test
    fun `VerificationResult valid creation with all fields`() {
        val result = VerificationResult(
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
        val result = VerificationResult(status = VerificationStatus.UNVERIFIED)
        assertEquals(VerificationStatus.UNVERIFIED, result.status)
        assertNull(result.verifier)
        assertNull(result.reason)
    }
}
