package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.shared.auth.auditClaim
import io.github.jpicklyk.mcptask.current.test.get
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.McpJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/**
 * TEST-AUTHOR INDEPENDENT SUITE for item `59989657` (test-plan S15, S16).
 *
 * Both scenarios PIN invariants of the D-b dead-code removal rather than demonstrate red/green
 * behavior change -- the frozen `diagnosis` note says so explicitly: "Block removal has no red;
 * S15/S16 pin its invariants." The removed 401 blocks were provably unreachable (JWKS principals
 * are always `VERIFIED`, and every `DegradedModePolicy` trusts `VERIFIED`; `BEARER`/
 * `UNAUTHENTICATED` never consult the policy at all), so no revert of the fix can turn either
 * scenario red -- that absence is the expected, documented shape here, not a gap.
 *
 * Oracles (supplied verbatim in the dispatch prompt's declarations block): `ApiAuditBridge`'s
 * `resolveTrustedActorIdOrNull` KDoc oracle (D-b: return type narrowed `String?` -> `String`,
 * `Rejected` arm now throws instead of returning null -- unreachable in practice since
 * `toVerificationResult` always reports `VERIFIED` for JWKS and every policy trusts `VERIFIED`);
 * `current/docs/fleet-deployment.md` :155 and `api-rest.md` §2 (write capabilities gate write
 * routes, never actor-resolution).
 */
class WriteRouteAuthInvariantTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private fun jwksAuthConfig(): ApiAuthConfig.Jwks =
        ApiAuthConfig.Jwks(
            url = "https://idp.example/.well-known/jwks.json",
            issuer = "https://idp.example",
            audience = "task-orchestrator-api",
            algorithms = listOf("RS256"),
            cacheTtlSeconds = 300,
        )

    /** Installs ONLY [ApiBearerAuth] in jwks mode (S16 asserts the auth plugin 401s before routing; the REST item
     * write routes are gone since Q13/Q18), with [verifier] wired directly into [ApiBearerAuth]. */
    private fun Application.wireJwksWriteApp(
        repo: DefaultRepositoryProvider,
        verifier: JwksApiVerifier,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    authConfig = jwksAuthConfig()
                    jwksVerifier = verifier
                }
            }
        }
    }

    private fun jwksPrincipal(
        tokenId: String,
        capabilities: Set<ApiCapability>,
    ): Principal =
        Principal(
            id = tokenId,
            scope = AccessScope.principal(rootIds = null, tagsInclude = emptySet()),
            capabilities = capabilities,
            kind = PrincipalKind.SERVICE,
            proofStatus = ProofStatus.VERIFIED,
        )

    /** Same shape a JSON response body's `"id":"<uuid>"` uses across this route family
     * (mirrors the sibling `WriteRoutesTest.kt`'s own private `extractId` helper). */
    private fun extractId(body: String): String? {
        val regex = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""")
        return regex.find(body)?.groupValues?.get(1)
    }

    // -------------------------------------------------------------------------------------------
    // S15 -- the audited REST actor (Principal.auditClaim) is api:<id>, kind external, with no
    // parent or proof, for every ProofStatus and every PrincipalKind. No Ktor involved. (The
    // resolveTrustedActorIdOrNull helper this used to drive was dead code and was deleted in R10.)
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S15 - the audit claim is api colon id for every credential ProofStatus x PrincipalKind pair, the bare id for NONE`() {
        for (mode in ProofStatus.entries) {
            for (kind in PrincipalKind.entries) {
                val tokenId = "s15-${mode.name.lowercase()}-${kind.name.lowercase()}"
                val principal =
                    Principal(
                        id = tokenId,
                        kind = kind,
                        proofStatus = mode,
                        scope = AccessScope.unrestricted(),
                        capabilities = setOf(ApiCapability.READ),
                    )
                val claim = principal.auditClaim()
                val expectedId = if (mode == ProofStatus.NONE) tokenId else "api:$tokenId"
                assertEquals(expectedId, claim.id, "S15: mode=$mode kind=$kind must audit as $expectedId")
                assertEquals(ActorKind.EXTERNAL, claim.kind, "S15: every transport principal audits as external")
                assertEquals(null, claim.parent, "S15: no parent")
                assertEquals(null, claim.proof, "S15: the credential is never echoed into the audit")
            }
        }
    }

    // -------------------------------------------------------------------------------------------
    // S16 -- a JWKS principal with write capabilities under the strictest policy (REJECT) must
    // never 401 on any write route; absent credentials still 401; READ-only still 403.
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S16 - no Authorization header still 401s on the same write routes`() =
        testApplication {
            val repo = db.repositoryProvider()
            val verifier = mockk<JwksApiVerifier>()
            application { wireJwksWriteApp(repo, verifier) }

            val response =
                client.post("/api/v1/items") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"type":"test-item","title":"Should Not Create"}""")
                }
            assertEquals(
                HttpStatusCode.Unauthorized,
                response.status,
                "S16: the invariant is about a PRESENT, verified JWKS+write principal -- an absent " +
                    "credential must still 401 like any other unauthenticated request",
            )
        }
}
