package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceConstraint
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceMode
import io.github.jpicklyk.mcptask.current.domain.model.IndependencePolicy
import io.github.jpicklyk.mcptask.current.domain.model.IndependenceViolation
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item a0a47509, R18) for which proofs satisfy `independence.require_verified`:
 * scenarios S16, S17 and S18 of the frozen test-plan, at the [GatePredicate] level directly (all EXISTING-SURFACE: the
 * predicate and its policy predate the change, only the meaning of "verified" moves, so red-proof is a plain revert of the
 * proof test).
 *
 * Oracles (written from the task-scope section E and the plan, never from a run): a note counts as verified for the
 * `require_verified` gate only when its proof is VERIFIED or VERIFIED_STALE (cryptographic proof); ISSUED (a token-file credential
 * the operator handed out), NONE and REJECTED are `unverified`, and so is a note with no verification at all. The stored
 * attribution of a stale-verified principal is `verified` (the stale marker is a per-request fact, task-scope D), so the
 * stale case is built as a VERIFIED record carrying the `verifiedFromCache` metadata and must satisfy the gate exactly as a fresh
 * one does. An `unverified` entry is a violation whose constraint is UNVERIFIED; in REJECT mode an unwaived violation blocks the
 * advance, in WARN mode it never does; mode OFF computes nothing (null). ISSUED is excluded on purpose: attestation wants
 * cryptographic proof.
 *
 * Gap recorded for the orchestrator: the domain enum has no stale member, so the stored form of VERIFIED_STALE cannot be built
 * directly; see the stale tests' comment for what is asserted instead.
 *
 * Negative-assertion conditions: every UNVERIFIED expectation is paired with the same fixture under `require_verified = false`
 * (no violations) and with the fixture whose only change is a VERIFIED proof (no violations), so a predicate that always
 * flagged, or never flagged, fails one side.
 *
 * Domain invariants satisfied by construction: the schema declares the implementer seat and a test-author note that names it
 * in `independent_of`, both WORK-phase; actor ids are distinct per note so that no SAME_ACTOR entry can mask the entry under
 * test; an ISSUED proof is the stored form `UNCHECKED` with verifier `api-bearer` (task-scope D), a NONE proof is `ABSENT`,
 * `UNCHECKED` with another verifier, or `UNAVAILABLE`.
 */
class IndependenceRequireVerifiedProofTest {
    private val itemId: UUID = UUID.randomUUID()

    private val declaringKey = "test-manifest"
    private val conflictingKey = "implementation-notes"

    private val schema =
        WorkItemSchema(
            type = "require-verified-proof",
            notes =
                listOf(
                    NoteSchemaEntry(key = conflictingKey, role = Role.WORK, required = true, seat = "implementer"),
                    NoteSchemaEntry(
                        key = declaringKey,
                        role = Role.WORK,
                        required = true,
                        seat = "test-author",
                        independentOf = listOf("implementer"),
                    ),
                ),
        )

    private fun note(
        key: String,
        actorId: String,
        verification: VerificationResult?,
    ): Note =
        Note(
            itemId = itemId,
            key = key,
            role = "work",
            body = "filled",
            actorClaim = ActorClaim(id = actorId, kind = ActorKind.SUBAGENT),
            verification = verification,
        )

    private val verified = VerificationResult(VerificationStatus.VERIFIED, verifier = "jwks")
    private val staleVerified =
        VerificationResult(
            VerificationStatus.VERIFIED,
            verifier = "jwks",
            metadata = mapOf("verifiedFromCache" to "true", "cacheAgeSeconds" to "100"),
        )
    private val issued = VerificationResult(VerificationStatus.UNCHECKED, verifier = "api-bearer")
    private val rejected = VerificationResult(VerificationStatus.REJECTED, verifier = "jwks", reason = "bad signature")

    private val none =
        listOf(
            "ABSENT" to VerificationResult(VerificationStatus.ABSENT),
            "UNCHECKED local" to VerificationResult(VerificationStatus.UNCHECKED, verifier = "local"),
            "UNCHECKED no verifier" to VerificationResult(VerificationStatus.UNCHECKED),
            "UNAVAILABLE" to VerificationResult(VerificationStatus.UNAVAILABLE, verifier = "jwks"),
        )

    private fun violations(
        declaring: VerificationResult?,
        conflicting: VerificationResult?,
        mode: IndependenceMode = IndependenceMode.WARN,
        requireVerified: Boolean = true,
    ): List<IndependenceViolation>? =
        GatePredicate.violationsForComplete(
            schema,
            listOf(note(conflictingKey, "agent-implementer", conflicting), note(declaringKey, "agent-author", declaring)),
            IndependencePolicy(mode, requireVerified),
        )

    private fun List<IndependenceViolation>?.unverified(): List<IndependenceViolation> =
        orEmpty().filter { it.constraint == IndependenceConstraint.UNVERIFIED }

    // ------------------------------------------------------------------ S16 verified proofs satisfy the gate

    @Test
    fun `S16 two VERIFIED notes with distinct actors produce no violation`() {
        assertEquals(emptyList(), violations(declaring = verified, conflicting = verified))
    }

    @Test
    fun `S16 a stale-verified record on the conflicting note or on the declaring note satisfies the gate as a fresh one does`() {
        // The domain enum has no stale member (declared gap): the stored attribution of a stale-verified principal is VERIFIED,
        // so the stale case is a VERIFIED record carrying the verifiedFromCache marker.
        assertEquals(emptyList(), violations(declaring = verified, conflicting = staleVerified), "stale on the conflicting seat")
        assertEquals(emptyList(), violations(declaring = staleVerified, conflicting = verified), "stale on the declaring seat")
        assertEquals(emptyList(), violations(declaring = staleVerified, conflicting = staleVerified), "stale on both")
    }

    @Test
    fun `S16 verified proofs never block in REJECT mode`() {
        val policy = IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)
        val found = violations(declaring = verified, conflicting = staleVerified, mode = IndependenceMode.REJECT)
        assertEquals(emptyList(), found)
        assertFalse(GatePredicate.blocksAdvance(found, policy))
    }

    // ------------------------------------------------------------------ S17 ISSUED is not enough

    @Test
    fun `S17 an ISSUED declaring note is unverified`() {
        val found = violations(declaring = issued, conflicting = verified)
        assertTrue(found.unverified().isNotEmpty(), "an operator-issued credential is not cryptographic proof: $found")
        // controls: the same fixture with a VERIFIED declaring note, and with the gate off
        assertEquals(emptyList(), violations(declaring = verified, conflicting = verified), "control: VERIFIED declaring")
        assertEquals(emptyList(), violations(declaring = issued, conflicting = verified, requireVerified = false), "control: gate off")
    }

    @Test
    fun `S17 an ISSUED conflicting note is unverified and names the implementer seat`() {
        val found = violations(declaring = verified, conflicting = issued)
        assertTrue(
            found.unverified().any { it.conflictingSeat == "implementer" },
            "an ISSUED implementer note must produce an unverified entry naming the seat: $found",
        )
        assertEquals(emptyList(), violations(declaring = verified, conflicting = issued, requireVerified = false), "control: gate off")
    }

    @Test
    fun `S17 an ISSUED proof blocks the advance in REJECT mode and never in WARN mode`() {
        val reject = violations(declaring = issued, conflicting = verified, mode = IndependenceMode.REJECT)
        assertTrue(GatePredicate.blocksAdvance(reject, IndependencePolicy(IndependenceMode.REJECT, requireVerified = true)), "$reject")
        val warn = violations(declaring = issued, conflicting = verified, mode = IndependenceMode.WARN)
        assertTrue(warn.unverified().isNotEmpty(), "WARN still computes the violation: $warn")
        assertFalse(GatePredicate.blocksAdvance(warn, IndependencePolicy(IndependenceMode.WARN, requireVerified = true)))
    }

    // ------------------------------------------------------------------ S18 rejected, none, off

    @Test
    fun `S18 a REJECTED proof on either note is unverified`() {
        assertTrue(violations(declaring = rejected, conflicting = verified).unverified().isNotEmpty(), "rejected declaring")
        assertTrue(
            violations(declaring = verified, conflicting = rejected).unverified().any { it.conflictingSeat == "implementer" },
            "rejected conflicting",
        )
        assertEquals(emptyList(), violations(declaring = verified, conflicting = verified), "control")
    }

    @Test
    fun `S18 every NONE spelling on either note is unverified`() {
        for ((label, proof) in none) {
            assertTrue(violations(declaring = proof, conflicting = verified).unverified().isNotEmpty(), "$label on the declaring note")
            assertTrue(
                violations(declaring = verified, conflicting = proof).unverified().any { it.conflictingSeat == "implementer" },
                "$label on the conflicting note",
            )
        }
        assertEquals(emptyList(), violations(declaring = verified, conflicting = verified), "control")
    }

    @Test
    fun `S18 a note with no verification at all is unverified`() {
        assertTrue(violations(declaring = null, conflicting = verified).unverified().isNotEmpty(), "declaring")
        assertTrue(violations(declaring = verified, conflicting = null).unverified().isNotEmpty(), "conflicting")
    }

    @Test
    fun `S18 mode OFF computes nothing whatever the proofs are`() {
        val policy = IndependencePolicy(IndependenceMode.OFF, requireVerified = true)
        for ((label, proof) in none + listOf("REJECTED" to rejected, "ISSUED" to issued)) {
            assertNull(violations(declaring = proof, conflicting = proof, mode = IndependenceMode.OFF), label)
        }
        assertFalse(GatePredicate.blocksAdvance(null, policy))
    }

    @Test
    fun `S18 probe the gate off ignores every unverified proof`() {
        for ((label, proof) in none + listOf("REJECTED" to rejected, "ISSUED" to issued)) {
            assertEquals(
                emptyList(),
                violations(declaring = proof, conflicting = proof, requireVerified = false),
                "$label with require_verified = false",
            )
        }
    }
}
