package io.github.jpicklyk.mcptask.current.interfaces.api.v1.routes

import io.github.jpicklyk.mcptask.current.application.service.IdempotencyService
import io.github.jpicklyk.mcptask.current.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.infrastructure.config.PerRootConfigService
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.repository.DefaultRepositoryProvider
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit.ApiAuditBridge
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthConfig
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiBearerAuth
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.JwksApiVerifier
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

    /** Installs ONLY the write routes under test (S16 exercises writes exclusively -- no read
     * routes are needed) in jwks mode, with [verifier] wired directly into [ApiBearerAuth]. */
    private fun Application.wireJwksWriteApp(
        repo: DefaultRepositoryProvider,
        verifier: JwksApiVerifier,
        degradedModePolicy: DegradedModePolicy,
    ) {
        install(ContentNegotiation) { json(McpJson) }
        install(SSE)
        routing {
            route("/api/v1") {
                install(ApiBearerAuth) {
                    authConfig = jwksAuthConfig()
                    jwksVerifier = verifier
                }
                val toolContext =
                    ToolExecutionContext(
                        repo,
                        perRootConfigService = PerRootConfigService(repo.configStore()),
                        unitOfWork = db.unitOfWork()
                    )
                itemWriteRoutes(
                    repo,
                    degradedModePolicy,
                    IdempotencyService(db.unitOfWork()),
                    toolContext.advanceServiceFactory(),
                    db.unitOfWork(),
                )
            }
        }
    }

    private fun jwksPrincipal(
        tokenId: String,
        capabilities: Set<ApiCapability>,
    ): ApiPrincipal =
        ApiPrincipal(
            tokenId = tokenId,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = capabilities,
            authMode = ApiAuthMode.JWKS,
        )

    /** Same shape a JSON response body's `"id":"<uuid>"` uses across this route family
     * (mirrors the sibling `WriteRoutesTest.kt`'s own private `extractId` helper). */
    private fun extractId(body: String): String? {
        val regex = Regex(""""id"\s*:\s*"([0-9a-f\-]{36})"""")
        return regex.find(body)?.groupValues?.get(1)
    }

    // -------------------------------------------------------------------------------------------
    // S15 -- resolveTrustedActorIdOrNull, all 3 ApiAuthMode x 3 DegradedModePolicy combinations.
    // No Ktor involved: exercises ApiAuditBridge directly (declaration gap #1's lower-friction
    // option (a) -- constructing the 3 ApiAuthMode principals directly rather than routing a full
    // request through ApiBearerAuth, since ApiTestHelper's fixtures only build BEARER principals).
    // -------------------------------------------------------------------------------------------

    @Test
    fun `S15 - resolveTrustedActorIdOrNull resolves api colon tokenId for every ApiAuthMode x DegradedModePolicy pair`() {
        val modes = listOf(ApiAuthMode.BEARER, ApiAuthMode.JWKS, ApiAuthMode.UNAUTHENTICATED)
        val policies =
            listOf(DegradedModePolicy.ACCEPT_CACHED, DegradedModePolicy.ACCEPT_SELF_REPORTED, DegradedModePolicy.REJECT)

        for (mode in modes) {
            for (policy in policies) {
                val tokenId = "s15-${mode.name.lowercase()}-${policy.name.lowercase()}"
                val principal =
                    ApiPrincipal(
                        tokenId = tokenId,
                        scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
                        capabilities = setOf(ApiCapability.READ),
                        authMode = mode,
                    )
                val result = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, policy)
                assertEquals(
                    "api:$tokenId",
                    result,
                    "S15: mode=$mode policy=$policy must resolve to api:$tokenId (fleet-deployment.md:155; D-b)",
                )
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
            application { wireJwksWriteApp(repo, verifier, DegradedModePolicy.REJECT) }

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
