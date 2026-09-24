package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.Note
import io.github.jpicklyk.mcptask.current.domain.model.ProofClaims
import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.infrastructure.repository.DefaultRepositoryProvider
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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.ApplicationRequest
import io.ktor.server.testing.testApplication
import io.ktor.util.Attributes
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item 983615e7 -- "Stop persisting raw actor proof JWTs; scrub
 * existing rows". Covers test-plan scenarios S5-S9 and S17 (the REST-layer surface of the fix: the
 * new admin-only `verification.proof` evidence object, the always-null `actor.proof`, and the
 * `?include=proof` deprecation Warning header) plus S6 as a declared regression guard.
 *
 * Uses [transitionRoutes]'s explicit `redactAttribution`/`redactProof` parameters where the
 * scenario needs to control redaction independent of the process environment (S7, S9); uses
 * [noteRoutes] / [itemRoutes] (env-driven, real `AppConfig.fromEnv()`) where the scenario only
 * needs an admin vs. non-admin distinction or the deprecation header (S5, S6, S8, S17), matching
 * [NoteRoutesTest]'s existing convention for those routes.
 *
 * `src/main` was not opened while writing this file. Evidence rows are built directly via the
 * repository (`Note`/`RoleTransition` domain constructors with an explicit `VerificationResult`
 * carrying `proofSha256`/`proofClaims`), the same pattern [NoteRoutesTest.makeItemAndNote] already
 * uses for `actorClaim`/`verification`.
 */
class ProofEvidenceRestTest {
    private fun makeItemAndEvidenceNote(
        repo: DefaultRepositoryProvider,
        proofSha256: String,
        claims: ProofClaims?,
        status: VerificationStatus = VerificationStatus.VERIFIED,
        actorId: String = "agent-evidence"
    ): Pair<WorkItem, Note> =
        runBlocking {
            val item = repo.workItemRepository().create(WorkItem(title = "Evidence Item", depth = 0)).getOrNull()!!
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
                    ).getOrNull()!!
            Pair(item, note)
        }

    // ===========================================================================================
    // S5 -- admin GET notes + transitions on a VERIFIED row -> verification.proof fields match,
    // actor.proof absent/null. D§6
    // ===========================================================================================

    @Test
    fun `S5 admin GET notes shows verification proof evidence with matching fields, actor proof absent`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
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
            application { configureTestApp { noteRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"sha256\":\"$sha\""), "expected sha256 evidence: $body")
            assertTrue(body.contains("\"iss\":\"https://test-issuer.example\""), "expected iss claim: $body")
            assertTrue(body.contains("\"sub\":\"agent-evidence\""), "expected sub claim: $body")
            assertTrue(body.contains("\"jti\":\"jti-s5\""), "expected jti claim: $body")
            assertTrue(body.contains("\"kid\":\"kid-s5\""), "expected kid claim: $body")
            assertTrue(body.contains("\"alg\":\"RS256\""), "expected alg claim: $body")
            assertFalse(body.contains("\"proof\":\""), "actor.proof must never carry a value on the wire: $body")
        }

    @Test
    fun `S5 admin GET transitions shows verification proof evidence with matching fields, actor proof absent`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val sha = "beefdead".repeat(8)
            val claims = ProofClaims(iss = "https://test-issuer.example", sub = "agent-evidence-t", kid = "kid-s5b", alg = "RS256")
            val item =
                runBlocking { repo.workItemRepository().create(WorkItem(title = "Evidence transition item", depth = 0)).getOrNull()!! }
            runBlocking {
                repo.roleTransitionRepository().create(
                    RoleTransition(
                        itemId = item.id,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                        actorClaim = ActorClaim(id = "agent-evidence-t", kind = ActorKind.SUBAGENT),
                        verification =
                            VerificationResult(
                                status = VerificationStatus.VERIFIED,
                                verifier = "jwks",
                                proofSha256 = sha,
                                proofClaims = claims
                            )
                    )
                )
            }
            application { configureTestApp { transitionRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/transitions") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"sha256\":\"$sha\""), "expected sha256 evidence: $body")
            assertTrue(body.contains("\"iss\":\"https://test-issuer.example\""), "expected iss claim: $body")
            assertTrue(body.contains("\"sub\":\"agent-evidence-t\""), "expected sub claim: $body")
            assertTrue(body.contains("\"kid\":\"kid-s5b\""), "expected kid claim: $body")
            assertFalse(body.contains("\"proof\":\""), "actor.proof must never carry a value on the wire: $body")
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
            val repo = buildH2RepositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "11".repeat(32), claims = null)
            application { configureTestApp { noteRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(
                body.contains("\"actor\":null") || !body.contains("\"actor\":{"),
                "non-admin must see no actor under default redaction: $body"
            )
            assertTrue(
                body.contains("\"verification\":null") || !body.contains("\"verification\":{"),
                "non-admin must see no verification under default redaction: $body"
            )
        }

    // ===========================================================================================
    // S7 -- non-admin, NOTE_ATTRIBUTION=false -> verification shown, verification.proof null,
    // actor.proof null. D§6
    // ===========================================================================================

    @Test
    fun `S7 non-admin with attribution redaction disabled sees verification but never its proof evidence`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val sha = "cafebabe".repeat(8)
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "S7 item", depth = 0)).getOrNull()!! }
            runBlocking {
                repo.roleTransitionRepository().create(
                    RoleTransition(
                        itemId = item.id,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                        actorClaim = ActorClaim(id = "agent-s7", kind = ActorKind.SUBAGENT),
                        verification =
                            VerificationResult(
                                status = VerificationStatus.VERIFIED,
                                verifier = "jwks",
                                proofSha256 = sha,
                                proofClaims = ProofClaims(iss = "https://test-issuer.example", sub = "agent-s7")
                            )
                    )
                )
            }
            application { configureTestApp { transitionRoutes(repo, redactAttribution = false, redactProof = true) } }

            val response =
                client.get("/api/v1/items/${item.id}/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"status\":\"verified\""), "verification must be shown when attribution redaction is disabled: $body")
            assertFalse(body.contains("\"sha256\":\"$sha\""), "non-admin must never see the proof evidence sha256: $body")
            assertFalse(body.contains("\"proof\":{"), "non-admin must never see a proof evidence object: $body")
            assertFalse(body.contains("\"proof\":\""), "non-admin must never see actor.proof with a value: $body")
        }

    // ===========================================================================================
    // O1 (reviewer-flagged gap) -- the NOTE path's non-admin proof strip was untested (S7 above
    // only exercises the TRANSITION path, via transitionRoutes' explicit redactAttribution/
    // redactProof overrides). noteRoutes takes no such override -- it always reads
    // AttributionRedactor.fromEnv() -- so there is no way to force redactNoteAttribution=false for
    // it through a full HTTP round trip without mutating the real process environment. Instead,
    // this calls AttributionRedactor.of(...).redact() directly on a NoteDto: the exact function
    // noteRoutes' GET /api/v1/items/{id}/notes handler itself calls per note (diagnosis Fix step 6:
    // "Wired into: ... GET /api/v1/items/{id}/notes ..."), so this exercises real production
    // redaction logic for the note path, just without the HTTP/Ktor-routing layer around it -- the
    // same style [AttributionRedactorTest] / [AttributionRedactorProofTest] already use for this
    // exact function. Oracle: diagnosis Fix step 6 / test-plan S7 applied to notes.
    // ===========================================================================================

    private fun makeEvidenceNoteDto(
        sha256: String,
        claims: ProofClaims?,
        actorProof: String? = null
    ): NoteDto =
        NoteDto(
            key = "o1-note",
            role = "queue",
            body = "O1 body",
            createdAt = "2026-01-01T00:00:00Z",
            modifiedAt = "2026-01-01T00:00:00Z",
            etag = "\"v1-1000\"",
            actor = ActorClaimDto(id = "agent-o1", kind = "orchestrator", parent = null, proof = actorProof),
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

    /** Mocked non-admin [ApplicationCall] with a given `include` query value, matching the
     * [AttributionRedactorProofTest] convention for exercising [AttributionRedactor] directly. */
    private fun makeNonAdminCall(includeValue: String? = null): ApplicationCall {
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
        every { request.queryParameters["include"] } returns includeValue

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

        val redactor = AttributionRedactor.of(redactNoteAttribution = false, redactActorProof = true)
        val result = redactor.redact(note, call)

        assertNotNull(result.actor, "actor attribution must be shown when attribution redaction is disabled")
        assertNull(result.actor!!.proof, "actor.proof must remain null")
        assertNotNull(result.verification, "verification must be shown when attribution redaction is disabled")
        assertEquals("verified", result.verification!!.status)
        assertNull(result.verification!!.proof, "non-admin must never receive the note's proof evidence object")
    }

    // ===========================================================================================
    // S8 -- admin ?include=proof -> 200, exactly one Warning header (exact text), actor.proof null.
    // D§7
    // ===========================================================================================

    @Test
    fun `S8 admin GET with include=proof returns 200, exactly one deprecation Warning header, and actor proof stays null`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "22".repeat(32), claims = null)
            application { configureTestApp { noteRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes?include=proof") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val warnings = response.headers.getAll("Warning") ?: emptyList()
            assertEquals(1, warnings.size, "expected exactly one Warning header; got $warnings")
            assertEquals(
                "299 - \"include=proof is deprecated and ignored; actor proofs are no longer stored\"",
                warnings.single()
            )
            val body = response.bodyAsText()
            assertFalse(body.contains("\"proof\":\""), "actor.proof must never carry a value: $body")
        }

    // ===========================================================================================
    // S9 (EXISTING-SURFACE) -- redactAttribution/redactProof=false + a row whose actor_proof was
    // set by raw SQL -> REST actor.proof still null (defense in depth: mapRow never reads
    // actor_proof). D§5-6
    // ===========================================================================================

    @Test
    fun `S9 a transition row whose actor_proof was set by raw SQL still returns actor proof null even with redaction fully disabled`() =
        testApplication {
            val (repo, database) = buildH2RepositoryProviderWithDatabase()
            val item = runBlocking { repo.workItemRepository().create(WorkItem(title = "Raw SQL row", depth = 0)).getOrNull()!! }
            runBlocking {
                repo.roleTransitionRepository().create(
                    RoleTransition(
                        itemId = item.id,
                        fromRole = "queue",
                        toRole = "work",
                        trigger = "start",
                        actorClaim = ActorClaim(id = "agent-raw", kind = ActorKind.SUBAGENT),
                        verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")
                    )
                )
            }
            // Bypass the repository entirely (the fix always nulls actor_proof on write) to
            // simulate a row that somehow still carries a raw value in that column -- e.g. written
            // before this fix. This is the only role_transitions row in this fresh, isolated H2
            // database, so no WHERE clause / id-type assumption is needed.
            transaction(db = database) {
                exec("UPDATE role_transitions SET actor_proof = 'raw-sql-secret-proof'")
            }

            application { configureTestApp { transitionRoutes(repo, redactAttribution = false, redactProof = false) } }

            val response =
                client.get("/api/v1/items/${item.id}/transitions") {
                    header("Authorization", "Bearer $TEST_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertFalse(body.contains("raw-sql-secret-proof"), "a raw-SQL-set actor_proof must never reach the REST response: $body")
            assertFalse(body.contains("\"proof\":\""), "actor.proof must never carry a value even with all redaction disabled: $body")
        }

    // ===========================================================================================
    // S17 -- duplicated ?include=proof, and GET /items/{id}?include=notes,proof -> exactly one
    // Warning; ?include=notes alone -> none. D§7
    // ===========================================================================================

    @Test
    fun `S17 duplicated include=proof still yields exactly one Warning header`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "33".repeat(32), claims = null)
            application { configureTestApp { noteRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}/notes?include=proof&include=proof") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val warnings = response.headers.getAll("Warning") ?: emptyList()
            assertEquals(1, warnings.size, "a duplicated include=proof must still yield exactly one Warning header; got $warnings")
        }

    @Test
    fun `S17 GET items id with include=notes,proof yields exactly one Warning header`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "44".repeat(32), claims = null)
            application { configureTestApp { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}?include=notes,proof") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val warnings = response.headers.getAll("Warning") ?: emptyList()
            assertEquals(1, warnings.size, "expected exactly one Warning header for include=notes,proof; got $warnings")
        }

    @Test
    fun `S17 GET items id with include=notes alone yields no Warning header`() =
        testApplication {
            val repo = buildH2RepositoryProvider()
            val (item, _) = makeItemAndEvidenceNote(repo, proofSha256 = "55".repeat(32), claims = null)
            application { configureTestApp { itemRoutes(repo) } }

            val response =
                client.get("/api/v1/items/${item.id}?include=notes") {
                    header("Authorization", "Bearer $ADMIN_TOKEN")
                }
            assertEquals(HttpStatusCode.OK, response.status)
            val warnings = response.headers.getAll("Warning") ?: emptyList()
            assertTrue(warnings.isEmpty(), "include=notes alone must not trigger the deprecation warning; got $warnings")
        }
}
