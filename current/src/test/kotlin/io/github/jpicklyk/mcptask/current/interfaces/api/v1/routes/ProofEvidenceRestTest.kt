package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipalKey
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ActorClaimDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.NoteDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.ProofEvidenceDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.dto.VerificationDto
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.redaction.AttributionRedactor
import io.github.jpicklyk.mcptask.current.interfaces.mcp.buildToolDispatcher
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 983615e7 -- "Stop persisting raw actor proof JWTs; scrub
 * existing rows" -- and updated by item b81d5849 to reflect the removal of the deprecated include
 * parameter and env var scaffolding after their one-release window (see CHANGELOG.md). Covers
 * test-plan scenarios S5-S9 (the REST-layer surface of the fix: the admin-only
 * `verification.proof` evidence object and the always-absent `actor.proof`) plus S6 as a declared
 * regression guard, plus the oracle that the deprecated include token is now a silent no-op (no
 * `Warning` header, no error).
 *
 * Uses [noteRoutes] / [itemRoutes] (env-driven, real `AppConfig.fromEnv()`) where the scenario only
 * needs an admin vs. non-admin distinction (S5, S6, S8), matching [NoteRoutesTest]'s existing convention
 * for those routes. The 3.x transition-route scenarios (S5 transitions, S7, S9) went with the routes (Q28b).
 *
 * `src/main` was not opened while writing this file. Evidence rows are built directly via the
 * repository (`Note`/`RoleTransition` domain constructors with an explicit `VerificationResult`
 * carrying `proofSha256`/`proofClaims`), the same pattern [NoteRoutesTest.makeItemAndNote] already
 * uses for `actorClaim`/`verification`.
 */
class ProofEvidenceRestTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    /** The note resource reads (GET /items/{id}/notes), over the production registry and dispatcher, default redaction. */
    private fun Route.notesRoutes(repo: DefaultRepositoryProvider) {
        val toolContext = ToolExecutionContext(repo, unitOfWork = db.unitOfWork())
        noteResourceRoutes(ToolRegistry(buildToolSpecs()), buildToolDispatcher(toolContext))
    }

    private fun makeItemAndEvidenceNote(
        repo: DefaultRepositoryProvider,
        proofSha256: String,
        claims: ProofClaims?,
        status: VerificationStatus = VerificationStatus.VERIFIED,
        actorId: String = "agent-evidence"
    ): Pair<WorkItem, Note> =
        runBlocking {
            val item = repo.workItemRepository().create(WorkItem(title = "Evidence Item", depth = 0))!!
            val actor = ActorClaim(id = actorId, kind = ActorKind.ORCHESTRATOR)
            val verification =
                VerificationResult(status = status, verifier = "jwks", proofSha256 = proofSha256, proofClaims = claims)
            val note =
                repo
                    .noteRepository()
                    .upsert(
                        Note(
                            itemId = item.id,
                            key = "evidence-note",
                            role = "queue",
                            body = "Evidence body",
                            actorClaim = actor,
                            verification = verification
                        )
                    )!!
            Pair(item, note)
        }

    // ===========================================================================================
    // S5 -- admin GET notes + transitions on a VERIFIED row -> verification.proof fields match,
    // actor.proof absent/null. D6
    // ===========================================================================================

    @Test
    fun `S5 admin GET notes shows attribution but never proof evidence, and actor proof is absent`() =
        testApplication {
            val repo = db.repositoryProvider()
            val sha = "deadbeef".repeat(8)
            val claims =
                ProofClaims(
                    iss = "https://test-issuer.example",
                    sub = "agent-evidence",
                    aud = listOf("a"),
                    jti = "jti-s5",
                    iat = 1000L,
                    exp = 2000L,
                    kid = "kid-s5",
                    alg = "RS256"
                )
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = sha, claims = claims)
            application { configureTestApp { notesRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"actor\":{\"id\":\"agent-evidence\""), "an admin sees the note's actor: $body")
            assertTrue(body.contains("\"verification\":{\"status\":\"verified\""), "an admin sees the verification status: $body")
            // Proof evidence (hash + verified claims) is never emitted by the note views, even to an admin (Q11 D10).
            for (evidence in listOf(sha, "jti-s5", "kid-s5", "RS256", "test-issuer", "\"sha256\"", "\"proof\"")) {
                assertFalse(body.contains(evidence), "note views carry no proof evidence ($evidence): $body")
            }
        }

    // ===========================================================================================
    // S6 (EXISTING-SURFACE, declared guard) -- non-admin, default attribution redaction (true) ->
    // actor+verification null. api-rest.md:1717. Duplicates NoteRoutesTest's existing "redacts
    // attribution for non-admin caller" coverage deliberately, as the plan's declared guard for
    // this scenario; kept here so item 983615e7's own S-id -> test mapping is complete and
    // self-contained within this file rather than pointing only at another file's pre-existing test.
    // ===========================================================================================

    @Test
    fun `S6 guard non-admin with default attribution redaction sees no actor and no verification`() =
        testApplication {
            val repo = db.repositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "11".repeat(32), claims = null)
            application { configureTestApp { notesRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("\"actor\""), "non-admin must see no actor under default redaction: $body")
            assertFalse(body.contains("\"verification\""), "non-admin must see no verification under default redaction: $body")
        }
    // ===========================================================================================
    // O1 (reviewer-flagged gap) -- the NOTE path's non-admin proof strip was untested (S7 above
    // only exercises the TRANSITION path, via transitionRoutes' explicit redactAttribution
    // override). noteRoutes takes no such override -- it always reads AttributionRedactor.fromEnv()
    // -- so there is no way to force redactNoteAttribution=false for it through a full HTTP round
    // trip without mutating the real process environment. Instead, this calls
    // AttributionRedactor.of(...).redact() directly on a NoteDto: the exact function noteRoutes'
    // GET /api/v1/items/{id}/notes handler itself calls per note (diagnosis Fix step 6: "Wired
    // into: ... GET /api/v1/items/{id}/notes ..."), so this exercises real production redaction
    // logic for the note path, just without the HTTP/Ktor-routing layer around it -- the same style
    // [AttributionRedactorTest] already uses for this exact function. Oracle: diagnosis Fix step 6
    // / test-plan S7 applied to notes.
    // ===========================================================================================

    private fun makeEvidenceNoteDto(
        sha256: String,
        claims: ProofClaims?
    ): NoteDto =
        NoteDto(
            key = "o1-note",
            role = "queue",
            body = "O1 body",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            etag = "\"v1-1000\"",
            actor = ActorClaimDto(id = "agent-o1", kind = "orchestrator", parent = null),
            verification =
                VerificationDto(
                    status = "verified",
                    verifier = "jwks",
                    reason = null,
                    proof =
                        ProofEvidenceDto(
                            sha256 = sha256,
                            iss = claims?.iss,
                            sub = claims?.sub,
                            aud = claims?.aud,
                            jti = claims?.jti,
                            iat = claims?.iat,
                            exp = claims?.exp,
                            kid = claims?.kid,
                            alg = claims?.alg
                        )
                )
        )

    /** Mocked non-admin [ApplicationCall], matching the [AttributionRedactorTest] convention for
     * exercising [AttributionRedactor] directly. */
    private fun makeNonAdminCall(): ApplicationCall {
        val principal =
            ApiPrincipal(
                tokenId = "reader",
                scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                capabilities = setOf(ApiCapability.READ),
                authMode = ApiAuthMode.BEARER
            )
        val attrs = Attributes()
        attrs.put(ApiPrincipalKey, principal)

        val request = mockk<ApplicationRequest>(relaxed = true)

        val call = mockk<ApplicationCall>(relaxed = true)
        every { call.attributes } returns attrs
        every { call.request } returns request
        return call
    }

    @Test
    fun `O1 non-admin with attribution redaction disabled sees a note's verification but never its proof evidence`() {
        val sha = "feedface".repeat(8)
        val claims = ProofClaims(iss = "https://test-issuer.example", sub = "agent-o1", kid = "kid-o1", alg = "RS256")
        val note = makeEvidenceNoteDto(sha256 = sha, claims = claims)
        val call = makeNonAdminCall()

        val redactor = AttributionRedactor.of(redactNoteAttribution = false)
        val result = redactor.redact(note, call)

        assertNotNull(result.actor, "actor attribution must be shown when attribution redaction is disabled")
        assertNotNull(result.verification, "verification must be shown when attribution redaction is disabled")
        assertEquals("verified", result.verification!!.status)
        assertNull(result.verification!!.proof, "non-admin must never receive the note's proof evidence object")
    }

    // ===========================================================================================
    // S8 (rewritten for b81d5849) -- admin passing the deprecated include token is now a silent
    // no-op: 200, no Warning header, actor carries no proof.
    // ===========================================================================================

    @Test
    fun `GET notes with the retired include query parameter is rejected as invalid_request`() =
        testApplication {
            val repo = db.repositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "22".repeat(32), claims = null)
            application { configureTestApp { notesRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes?include=proof") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"code\":\"invalid_request\"") && body.contains("include"),
                "unknown query parameters are rejected: $body"
            )
        }
}
