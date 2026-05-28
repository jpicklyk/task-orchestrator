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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [AttributionRedactor].
 *
 * Test coverage:
 * - Default behaviour (redact=true): non-admin callers see null actor/verification
 * - Admin callers see actor/verification
 * - Proof redacted unless admin AND ?include=proof
 * - When redactNoteAttribution=false: all callers see actor/verification
 */
class AttributionRedactorTest {

    private fun makeNoteWithActor(proof: String? = null): NoteDto = NoteDto(
        key = "spec",
        role = "queue",
        body = "Test body",
        createdAt = "2026-01-01T00:00:00Z",
        modifiedAt = "2026-01-01T00:00:00Z",
        etag = "\"v1-1000\"",
        actor = ActorClaimDto(
            id = "agent-1",
            kind = "orchestrator",
            parent = "parent-1",
            proof = proof,
        ),
        verification = VerificationDto(
            status = "unchecked",
            verifier = "noop",
            reason = null,
        ),
    )

    private fun makeReadCall(isAdmin: Boolean, includeProof: Boolean = false): ApplicationCall {
        val capabilities = if (isAdmin) setOf(ApiCapability.READ, ApiCapability.ADMIN) else setOf(ApiCapability.READ)
        val principal = ApiPrincipal(
            tokenId = if (isAdmin) "admin" else "reader",
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = capabilities,
            authMode = ApiAuthMode.BEARER,
        )

        val attrs = io.ktor.util.Attributes()
        attrs.put(ApiPrincipalKey, principal)

        val request = mockk<ApplicationRequest>(relaxed = true)
        every { request.queryParameters["include"] } returns if (includeProof) "proof" else null

        val call = mockk<ApplicationCall>(relaxed = true)
        every { call.attributes } returns attrs
        every { call.request } returns request

        return call
    }

    // ─── Default redaction (redactNoteAttribution=true) ──────────────────────

    @Test
    fun `non-admin caller receives null actor when redaction enabled`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val note = makeNoteWithActor(proof = "jwt-abc")
        val call = makeReadCall(isAdmin = false)
        val result = redactor.redact(note, call)
        assertNull(result.actor, "Expected actor to be null for non-admin: ${result.actor}")
        assertNull(result.verification, "Expected verification to be null for non-admin: ${result.verification}")
    }

    @Test
    fun `admin caller sees actor when redaction enabled`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = false)
        val note = makeNoteWithActor()
        val call = makeReadCall(isAdmin = true)
        val result = redactor.redact(note, call)
        assertNotNull(result.actor, "Expected actor to be present for admin")
        assertEquals("agent-1", result.actor!!.id)
    }

    @Test
    fun `proof is redacted for admin without include=proof`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val note = makeNoteWithActor(proof = "super-secret-jwt")
        val call = makeReadCall(isAdmin = true, includeProof = false)
        val result = redactor.redact(note, call)
        assertNotNull(result.actor, "Expected actor for admin")
        assertNull(result.actor!!.proof, "Expected proof to be null without ?include=proof: ${result.actor!!.proof}")
    }

    @Test
    fun `proof is shown for admin with include=proof`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val note = makeNoteWithActor(proof = "super-secret-jwt")
        val call = makeReadCall(isAdmin = true, includeProof = true)
        val result = redactor.redact(note, call)
        assertNotNull(result.actor, "Expected actor for admin")
        assertEquals("super-secret-jwt", result.actor!!.proof, "Expected proof to be present with ?include=proof")
    }

    // ─── Redaction disabled ──────────────────────────────────────────────────

    @Test
    fun `non-admin sees actor when redaction disabled globally`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = false)
        val note = makeNoteWithActor()
        val call = makeReadCall(isAdmin = false)
        val result = redactor.redact(note, call)
        assertNotNull(result.actor, "Expected actor when attribution redaction disabled")
    }

    // ─── redactAll ───────────────────────────────────────────────────────────

    @Test
    fun `redactAll applies redaction to all notes`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val notes = listOf(makeNoteWithActor(), makeNoteWithActor())
        val call = makeReadCall(isAdmin = false)
        val results = redactor.redactAll(notes, call)
        assertEquals(2, results.size)
        results.forEach { assertNull(it.actor, "Expected null actor for non-admin") }
    }

    // ─── Note without actor ──────────────────────────────────────────────────

    @Test
    fun `note without actor is unchanged by redactor`() {
        val redactor = AttributionRedactor.of(redactNoteAttribution = true, redactActorProof = true)
        val note = NoteDto(
            key = "spec",
            role = "queue",
            body = "No actor note",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            etag = "\"v1-1000\"",
            actor = null,
            verification = null,
        )
        val call = makeReadCall(isAdmin = false)
        val result = redactor.redact(note, call)
        assertEquals(note, result, "Expected note to be unchanged when no actor present")
    }
}
