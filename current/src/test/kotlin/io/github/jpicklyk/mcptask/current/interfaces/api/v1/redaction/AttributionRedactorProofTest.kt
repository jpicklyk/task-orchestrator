package io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction

import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression tests for item d426fbfa / decision D3: a non-admin REST caller must never receive
 * `actor.proof`, regardless of `API_REDACT_NOTE_ATTRIBUTION`. Before the fix, `redact()`'s step-2
 * fall-through (`if (!redactActorProof || !isAdmin) return note`) meant a non-admin caller reached
 * with `redactNoteAttribution=false` kept the full, unredacted actor — proof included.
 *
 * Deliberately independent of [AttributionRedactorTest] — its own DTO/call-mocking helpers — so
 * this file makes no assumption about that file's fixtures.
 */
class AttributionRedactorProofTest {
    private val secret = "SECRET-super-secret-bearer-jwt"

    private fun makeNoteWithActor(proof: String? = null): NoteDto =
        NoteDto(
            key = "spec",
            role = "queue",
            body = "Test body",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            etag = "\"v1-1000\"",
            actor =
                ActorClaimDto(
                    id = "agent-1",
                    kind = "orchestrator",
                    parent = "parent-1",
                    proof = proof,
                ),
            verification =
                VerificationDto(
                    status = "unchecked",
                    verifier = "noop",
                    reason = null,
                ),
        )

    private fun makeCall(
        isAdmin: Boolean,
        includeProof: Boolean
    ): ApplicationCall {
        val capabilities = if (isAdmin) setOf(ApiCapability.READ, ApiCapability.ADMIN) else setOf(ApiCapability.READ)
        val principal =
            ApiPrincipal(
                tokenId = if (isAdmin) "admin" else "reader",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = capabilities,
                authMode = ApiAuthMode.BEARER,
            )

        val attrs = Attributes()
        attrs.put(ApiPrincipalKey, principal)

        val request = mockk<ApplicationRequest>(relaxed = true)
        every { request.queryParameters["include"] } returns if (includeProof) "proof" else null

        val call = mockk<ApplicationCall>(relaxed = true)
        every { call.attributes } returns attrs
        every { call.request } returns request
        return call
    }

    /** Like [makeCall], but sets the raw `include` query value verbatim (for probe variations). */
    private fun makeCallRawInclude(
        isAdmin: Boolean,
        includeValue: String?
    ): ApplicationCall {
        val capabilities = if (isAdmin) setOf(ApiCapability.READ, ApiCapability.ADMIN) else setOf(ApiCapability.READ)
        val principal =
            ApiPrincipal(
                tokenId = if (isAdmin) "admin" else "reader",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = capabilities,
                authMode = ApiAuthMode.BEARER,
            )

        val attrs = Attributes()
        attrs.put(ApiPrincipalKey, principal)

        val request = mockk<ApplicationRequest>(relaxed = true)
        every { request.queryParameters["include"] } returns includeValue

        val call = mockk<ApplicationCall>(relaxed = true)
        every { call.attributes } returns attrs
        every { call.request } returns request
        return call
    }

    // -------------------------------------------------------------------------
    // S6 — redactNoteAttribution=false, redactActorProof=true, non-admin: attribution is shown,
    // but proof is still stripped (the exact wiring bug this item fixes).
    // -------------------------------------------------------------------------

    @Test
    fun `S6 non-admin sees attribution but not proof when only proof redaction is enabled`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = true)
        val note = makeNoteWithActor(proof = secret)
        val call = makeCall(isAdmin = false, includeProof = false)

        val result = redactor.redact(note, call)

        assertNotNull(result.actor, "non-admin should still see attribution when redactNoteAttribution=false")
        assertEquals("agent-1", result.actor.id, "actor id must be kept")
        assertNull(result.actor.proof, "non-admin must never receive proof when redactActorProof=true")
    }

    // -------------------------------------------------------------------------
    // S7 — same wiring, but the non-admin caller also asks for ?include=proof: still null.
    // -------------------------------------------------------------------------

    @Test
    fun `S7 non-admin with include=proof still gets no proof when attribution redaction is disabled`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = true)
        val note = makeNoteWithActor(proof = secret)
        val call = makeCall(isAdmin = false, includeProof = true)

        val result = redactor.redact(note, call)

        assertNotNull(result.actor)
        assertNull(result.actor.proof, "?include=proof must not grant a non-admin caller the raw proof")
    }

    // -------------------------------------------------------------------------
    // admin + ?include=proof: item 983615e7 D6/D7 — proofs are no longer stored, so this path now
    // returns null instead of the (former) raw proof. (Was labelled "S11" for item d426fbfa; that
    // numbering is unrelated to item 983615e7's own test-plan S11.)
    // -------------------------------------------------------------------------

    @Test
    fun `admin with include=proof no longer receives the raw proof`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val note = makeNoteWithActor(proof = secret)
        val call = makeCall(isAdmin = true, includeProof = true)

        val result = redactor.redact(note, call)

        assertNotNull(result.actor)
        assertNull(result.actor.proof, "admin with ?include=proof must no longer receive the raw proof per item 983615e7 D6")
    }

    // -------------------------------------------------------------------------
    // Probes — alternate forms of the `include` query value must not bypass non-admin redaction
    // -------------------------------------------------------------------------

    @Test
    fun `Probe non-admin with mixed-case include=PROOF still gets no proof`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = true)
        val note = makeNoteWithActor(proof = secret)
        val call = makeCallRawInclude(isAdmin = false, includeValue = "PROOF")

        val result = redactor.redact(note, call)

        assertNotNull(result.actor)
        assertNull(result.actor.proof, "mixed-case include value must not bypass non-admin redaction")
    }

    @Test
    fun `Probe non-admin with duplicated include=proof,proof still gets no proof`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = true)
        val note = makeNoteWithActor(proof = secret)
        val call = makeCallRawInclude(isAdmin = false, includeValue = "proof,proof")

        val result = redactor.redact(note, call)

        assertNotNull(result.actor)
        assertNull(result.actor.proof, "a duplicated include value must not bypass non-admin redaction")
    }
}
