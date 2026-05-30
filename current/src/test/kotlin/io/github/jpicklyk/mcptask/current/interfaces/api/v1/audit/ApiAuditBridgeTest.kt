package io.github.jpicklyk.mcptask.current.interfaces.api.v1.audit

import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiAuthMode
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiCapability
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiPrincipal
import io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth.ApiScope
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for [ApiAuditBridge].
 *
 * Verifies:
 * - Server-synthesized actor claim shape: `"api:<tokenId>"`, `EXTERNAL`, no proof
 * - Client body `actor.*` fields are not read (verified structurally — the bridge never
 *   has access to the request body; it only uses ApiPrincipal)
 * - DegradedModePolicy interaction for bearer and JWKS modes
 * - Bearer mode always trusted regardless of policy
 */
class ApiAuditBridgeTest {
    private fun makePrincipal(
        tokenId: String = "dashboard-editor",
        authMode: ApiAuthMode = ApiAuthMode.BEARER,
    ): ApiPrincipal =
        ApiPrincipal(
            tokenId = tokenId,
            scope = ApiScope(rootIds = null, tagsInclude = emptySet()),
            capabilities = setOf(ApiCapability.READ, ApiCapability.WRITE_ITEMS),
            authMode = authMode,
        )

    @Test
    fun `toActorClaim synthesizes id as api colon tokenId`() {
        val principal = makePrincipal(tokenId = "dashboard-editor")
        val claim = ApiAuditBridge.toActorClaim(principal)
        assertEquals("api:dashboard-editor", claim.id)
    }

    @Test
    fun `toActorClaim sets kind to EXTERNAL`() {
        val principal = makePrincipal()
        val claim = ApiAuditBridge.toActorClaim(principal)
        assertEquals(ActorKind.EXTERNAL, claim.kind)
    }

    @Test
    fun `toActorClaim has no parent and no proof`() {
        val principal = makePrincipal()
        val claim = ApiAuditBridge.toActorClaim(principal)
        assertNull(claim.parent, "parent should be null — API actors have no parent chain")
        assertNull(claim.proof, "proof should be null — bearer token must not be echoed into audit")
    }

    @Test
    fun `toVerificationResult returns UNCHECKED for BEARER mode`() {
        val principal = makePrincipal(authMode = ApiAuthMode.BEARER)
        val result = ApiAuditBridge.toVerificationResult(principal)
        assertEquals(VerificationStatus.UNCHECKED, result.status)
        assertEquals("api-bearer", result.verifier)
    }

    @Test
    fun `toVerificationResult returns VERIFIED for JWKS mode`() {
        val principal = makePrincipal(authMode = ApiAuthMode.JWKS)
        val result = ApiAuditBridge.toVerificationResult(principal)
        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertEquals("api-jwks", result.verifier)
    }

    @Test
    fun `resolveTrustedActorIdOrNull returns non-null for BEARER with REJECT policy`() {
        // Bearer mode is always trusted regardless of degradedModePolicy — no JWKS chain
        val principal = makePrincipal(tokenId = "my-token", authMode = ApiAuthMode.BEARER)
        val result = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, DegradedModePolicy.REJECT)
        assertNotNull(result, "Bearer mode must always return a trusted id even with REJECT policy")
        assertEquals("api:my-token", result)
    }

    @Test
    fun `resolveTrustedActorIdOrNull returns non-null for JWKS with ACCEPT_CACHED policy`() {
        val principal = makePrincipal(tokenId = "jwt-sub-123", authMode = ApiAuthMode.JWKS)
        val result = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, DegradedModePolicy.ACCEPT_CACHED)
        assertNotNull(result, "JWKS + accept-cached should return trusted id")
        assertEquals("api:jwt-sub-123", result)
    }

    @Test
    fun `resolveTrustedActorIdOrNull returns non-null for JWKS with REJECT policy when VERIFIED`() {
        // JWKS toVerificationResult gives VERIFIED — REJECT policy trusts VERIFIED
        val principal = makePrincipal(tokenId = "jwks-user", authMode = ApiAuthMode.JWKS)
        val result = ApiAuditBridge.resolveTrustedActorIdOrNull(principal, DegradedModePolicy.REJECT)
        assertNotNull(result, "JWKS + VERIFIED + REJECT policy should return trusted id")
        assertEquals("api:jwks-user", result)
    }

    @Test
    fun `toActorClaim with UUID-containing tokenId produces correct audit id`() {
        val tokenId = UUID.randomUUID().toString()
        val principal = makePrincipal(tokenId = tokenId)
        val claim = ApiAuditBridge.toActorClaim(principal)
        assertEquals("api:$tokenId", claim.id)
    }
}
