package io.github.jpicklyk.mcptask.current.domain.event

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 02f1be4c, scenario S9 plus the payload-level probes of S1/S4/S5/S6) for the
 * widened `item.transitioned` payload as a pure domain value: no database, no recorder.
 *
 * Oracles: the item's frozen task-scope (the six audit keys, D1 key names, D2 every key always present with null / []
 * when absent, D3 never the raw proof, never verifiedSubject / metadata, claims as the non-null ProofClaims fields and
 * null when claims are null or all fields are null, AC3, AC4, AC6) and the declared `ProofClaims` KDoc (iat / exp are
 * epoch seconds as numbers). No expected value is read from the implementation.
 */
class ItemTransitionedPayloadTest {
    private val itemId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    private val rootId: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000b2")

    private val auditKeys =
        setOf("summary", "consumedCredentials", "actorProofSha256", "actorProofClaims", "verifier", "verificationReason")

    private val legacyKeys =
        setOf("trigger", "fromRole", "toRole", "fromStatusLabel", "toStatusLabel", "origin")

    /** The legacy eight-argument positional shape every pre-existing caller uses. */
    private fun legacy(): DomainEvent.ItemTransitioned =
        DomainEvent.ItemTransitioned(itemId, rootId, "start", "queue", "work", "todo", "doing", TransitionOrigin.USER)

    private fun withVerification(verification: VerificationResult?): Map<String, Any?> =
        DomainEvent
            .ItemTransitioned(
                entityId = itemId,
                rootId = rootId,
                trigger = "start",
                fromRole = "queue",
                toRole = "work",
                fromStatusLabel = null,
                toStatusLabel = null,
                origin = TransitionOrigin.USER,
                verification = verification
            ).payload()

    @Suppress("UNCHECKED_CAST")
    private fun claimsOf(payload: Map<String, Any?>): Map<String, Any?>? = payload["actorProofClaims"] as Map<String, Any?>?

    @Test
    fun `S9 the legacy eight-argument positional constructor still compiles and yields all six audit keys as null or empty`() {
        val payload = legacy().payload()

        assertEquals(legacyKeys + auditKeys, payload.keys, "exactly the six legacy keys plus the six audit keys")
        assertNull(payload["summary"])
        assertEquals(emptyList<String>(), payload["consumedCredentials"])
        assertNull(payload["actorProofSha256"])
        assertNull(payload["actorProofClaims"])
        assertNull(payload["verifier"])
        assertNull(payload["verificationReason"])
    }

    @Test
    fun `S9 the legacy payload values are unchanged by the widening`() {
        val payload = legacy().payload()

        assertEquals("start", payload["trigger"])
        assertEquals("queue", payload["fromRole"])
        assertEquals("work", payload["toRole"])
        assertEquals("todo", payload["fromStatusLabel"])
        assertEquals("doing", payload["toStatusLabel"])
        assertEquals("user", payload["origin"])
    }

    @Test
    fun `S9 the origin is carried by its wire name for a cascade too`() {
        val payload =
            DomainEvent.ItemTransitioned(itemId, rootId, "cascade", "queue", "work", null, null, TransitionOrigin.CASCADE).payload()

        assertEquals("cascade", payload["origin"])
    }

    @Test
    fun `S9 a named summary and consumedCredentials appear in the payload under the declared keys`() {
        val payload =
            DomainEvent
                .ItemTransitioned(
                    entityId = itemId,
                    rootId = rootId,
                    trigger = "complete",
                    fromRole = "work",
                    toRole = "terminal",
                    fromStatusLabel = null,
                    toStatusLabel = null,
                    origin = TransitionOrigin.USER,
                    summary = "all done",
                    consumedCredentials = listOf("cred-1", "cred-2")
                ).payload()

        assertEquals("all done", payload["summary"])
        assertEquals(listOf("cred-1", "cred-2"), payload["consumedCredentials"])
    }

    @Test
    fun `S1 a verification contributes its hash verifier and reason and all eight claims with numeric iat and exp`() {
        val payload =
            withVerification(
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    verifier = "jwks",
                    reason = "signature ok",
                    proofSha256 = "abc123hash",
                    proofClaims =
                        ProofClaims(
                            iss = "https://issuer.example",
                            sub = "agent-7",
                            aud = listOf("a1", "a2"),
                            jti = "jti-9",
                            iat = 1_700_000_000L,
                            exp = 1_700_000_300L,
                            kid = "key-1",
                            alg = "RS256"
                        )
                )
            )

        assertEquals("abc123hash", payload["actorProofSha256"])
        assertEquals("jwks", payload["verifier"])
        assertEquals("signature ok", payload["verificationReason"])
        val claims = claimsOf(payload)
        assertEquals(
            mapOf(
                "iss" to "https://issuer.example",
                "sub" to "agent-7",
                "aud" to listOf("a1", "a2"),
                "jti" to "jti-9",
                "iat" to 1_700_000_000L,
                "exp" to 1_700_000_300L,
                "kid" to "key-1",
                "alg" to "RS256"
            ),
            claims
        )
        assertTrue(claims!!["iat"] is Number && claims["exp"] is Number, "iat and exp are epoch-second numbers, not strings")
    }

    @Test
    fun `S6 claims carry only the non-null ProofClaims fields`() {
        val payload =
            withVerification(
                VerificationResult(status = VerificationStatus.VERIFIED, proofClaims = ProofClaims(iss = "only-iss", exp = 5L))
            )

        assertEquals(mapOf("iss" to "only-iss", "exp" to 5L), claimsOf(payload))
    }

    @Test
    fun `S6 an all-null ProofClaims and absent claims both give null actorProofClaims while the other fields are carried`() {
        val allNull =
            withVerification(
                VerificationResult(
                    status = VerificationStatus.REJECTED,
                    verifier = "jwks",
                    reason = "bad signature",
                    proofSha256 = "h-rejected",
                    proofClaims = ProofClaims()
                )
            )
        val absent =
            withVerification(
                VerificationResult(
                    status = VerificationStatus.REJECTED,
                    verifier = "jwks",
                    reason = "bad signature",
                    proofSha256 = "h-rejected",
                    proofClaims = null
                )
            )

        for (payload in listOf(allNull, absent)) {
            assertTrue(payload.containsKey("actorProofClaims"), "the key is present even when there are no claims")
            assertNull(payload["actorProofClaims"])
            assertEquals("h-rejected", payload["actorProofSha256"])
            assertEquals("jwks", payload["verifier"])
            assertEquals("bad signature", payload["verificationReason"])
        }
    }

    @Test
    fun `S6 probe an empty audience list is a present claim while a null audience is omitted`() {
        val empty =
            withVerification(VerificationResult(VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "s", aud = emptyList())))
        val missing = withVerification(VerificationResult(VerificationStatus.VERIFIED, proofClaims = ProofClaims(sub = "s", aud = null)))

        assertEquals(mapOf("sub" to "s", "aud" to emptyList<String>()), claimsOf(empty))
        assertEquals(mapOf("sub" to "s"), claimsOf(missing))
    }

    @Test
    fun `S6 probe epoch zero and the largest epoch value survive as the same numbers`() {
        val payload =
            withVerification(
                VerificationResult(
                    VerificationStatus.VERIFIED,
                    proofClaims = ProofClaims(iat = 0L, exp = Long.MAX_VALUE)
                )
            )

        assertEquals(mapOf("iat" to 0L, "exp" to Long.MAX_VALUE), claimsOf(payload))
    }

    @Test
    fun `S4 a verification with nothing but a status gives null hash verifier reason and claims`() {
        val payload = withVerification(VerificationResult(status = VerificationStatus.UNCHECKED))

        assertTrue(payload.keys.containsAll(auditKeys))
        assertNull(payload["actorProofSha256"])
        assertNull(payload["verifier"])
        assertNull(payload["verificationReason"])
        assertNull(payload["actorProofClaims"])
    }

    @Test
    fun `S5 the raw actor proof is never in the payload even when the actor carries one`() {
        val secret = "PROOF-SECRET-q28a-domain"
        val payload =
            DomainEvent
                .ItemTransitioned(
                    entityId = itemId,
                    rootId = rootId,
                    trigger = "start",
                    fromRole = "queue",
                    toRole = "work",
                    fromStatusLabel = null,
                    toStatusLabel = null,
                    origin = TransitionOrigin.USER,
                    actor = ActorClaim(id = "agent-1", kind = ActorKind.SUBAGENT, parent = "orch", proof = secret),
                    verification = VerificationResult(VerificationStatus.VERIFIED, proofSha256 = "only-the-hash")
                ).payload()

        assertFalse(payload.toString().contains(secret), "the raw proof must not appear anywhere in the payload: $payload")
        assertEquals("only-the-hash", payload["actorProofSha256"])
    }

    @Test
    fun `S5 a verified subject and verification metadata are not payload content`() {
        val payload =
            withVerification(
                VerificationResult(
                    status = VerificationStatus.VERIFIED,
                    verifier = "jwks",
                    metadata = mapOf("meta-key" to "meta-value-unique"),
                    verifiedSubject = "verified-subject-unique",
                    proofSha256 = "h"
                )
            )

        val text = payload.toString()
        assertFalse(text.contains("verified-subject-unique"), "verifiedSubject is not an audit column: $text")
        assertFalse(text.contains("meta-value-unique"), "metadata is not an audit column: $text")
        assertEquals(legacyKeys + auditKeys, payload.keys, "no key beyond the twelve declared ones")
    }

    @Test
    fun `S4 probe an empty summary stays empty and is distinct from an absent summary`() {
        val empty =
            DomainEvent
                .ItemTransitioned(itemId, rootId, "start", "queue", "work", null, null, TransitionOrigin.USER, summary = "")
                .payload()

        assertEquals("", empty["summary"])
        assertNull(legacy().payload()["summary"])
    }

    @Test
    fun `S1 probe duplicate credential labels and their order are carried as given`() {
        val payload =
            DomainEvent
                .ItemTransitioned(
                    itemId,
                    rootId,
                    "start",
                    "queue",
                    "work",
                    null,
                    null,
                    TransitionOrigin.USER,
                    consumedCredentials = listOf("b", "a", "a")
                ).payload()

        assertEquals(listOf("b", "a", "a"), payload["consumedCredentials"])
    }

    @Test
    fun `S1 probe a non-ASCII summary is carried unchanged`() {
        val text = "R\u00e9sum\u00e9 \u2014 \u65e5\u672c\u8a9e \u2713"
        val payload =
            DomainEvent
                .ItemTransitioned(itemId, rootId, "start", "queue", "work", null, null, TransitionOrigin.USER, summary = text)
                .payload()

        assertEquals(text, payload["summary"])
    }
}
