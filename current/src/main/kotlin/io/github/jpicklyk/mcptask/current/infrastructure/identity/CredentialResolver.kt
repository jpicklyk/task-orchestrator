package io.github.jpicklyk.mcptask.current.infrastructure.identity

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.BearerTokenStore
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.HashBytes
import io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt.JwksApiVerifier
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A resolved credential: the [principal] it establishes, its [expiresAt] (null for a token-file entry without
 * `expires_at`), and the credential [profile] restriction (`director` confines the credential to the director path;
 * null is unrestricted).
 */
data class Resolved(
    val principal: Principal,
    val expiresAt: Instant?,
    val profile: String? = null
)

/**
 * The ONE bearer pipeline: every REST credential (the `Authorization` header and the SSE `?token=`) is resolved
 * here, and nothing else hashes a presented token or calls the JWT verifier.
 *
 * Order: the SHA-256 digest of the raw token is looked up among [tokenEntries] (constant-time key equality via
 * [HashBytes]; an entry past its `expires_at` per [clock] resolves to nothing, it does not fall through to the JWT
 * path); on a miss, the token is verified as a JWT by [jwtVerifier] when one is configured; otherwise null.
 *
 * @param tokenEntries The token file's entries, loaded once at startup ([BearerTokenStore.loadWithEntries]).
 * @param jwtVerifier The REST JWT verifier (JWKS mode), or null.
 * @param nonRootAnchors Returns the subset of a set of `scope.root_ids` anchors that name an existing item which is
 *   not a depth-0 root (anchor validation, R15). A JWT whose anchors include one resolves to null (unauthenticated),
 *   with one WARN per `sub`; a lookup fault resolves to null too (fail closed). Bearer entries are validated once at
 *   startup instead. Defaults to "none".
 * @param clock The time source for entry expiry.
 */
class CredentialResolver(
    private val tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    private val jwtVerifier: JwksApiVerifier?,
    private val nonRootAnchors: suspend (Set<UUID>) -> Set<UUID> = { emptySet() },
    private val clock: () -> Instant = { Instant.now() }
) {
    private val logger = LoggerFactory.getLogger(CredentialResolver::class.java)

    /** Subs already warned about a non-root anchor; bounded, cleared when full, so a hostile issuer cannot grow it. */
    private val warnedSubs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Resolves [raw] (the presented token, already stripped of its scheme), or null when it proves nothing. */
    suspend fun resolve(raw: String): Resolved? {
        if (raw.isEmpty()) return null
        val entry = tokenEntries[HashBytes(sha256(raw))]
        if (entry != null) {
            val expiry = entry.expiresAt
            if (expiry != null && clock().isAfter(expiry)) {
                logger.debug("Bearer token '{}' is expired (expires_at={})", entry.principal.id, expiry)
                return null
            }
            return Resolved(entry.principal, expiry, entry.profile)
        }
        val verified = jwtVerifier?.verifyWithExpiry(raw) ?: return null
        val anchors = verified.principal.scope.rootIds
        if (!anchors.isNullOrEmpty()) {
            val bad =
                try {
                    nonRootAnchors(anchors)
                } catch (e: Exception) {
                    e.rethrowIfCancellation()
                    logger.warn("JWT scope anchor validation failed; the credential is refused: {}", e.message)
                    return null
                }
            if (bad.isNotEmpty()) {
                warnOnce(verified.principal.id, bad.minOf { it.toString() })
                return null
            }
        }
        return Resolved(verified.principal, verified.expiresAt, profile = null)
    }

    private fun warnOnce(
        sub: String,
        anchor: String
    ) {
        if (warnedSubs.size >= MAX_WARNED_SUBS) warnedSubs.clear()
        if (warnedSubs.add(sub)) {
            logger.warn("JWT sub '{}' is refused: scope.root_ids anchor {} is not a depth-0 root", sub, anchor)
        }
    }

    private fun sha256(input: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}

private const val MAX_WARNED_SUBS = 1024

/**
 * The token-file `profile` value that confines a credential to the director paths: `/api/v1/ops/director/{tool}` over
 * REST and `/mcp/director` over MCP.
 */
const val DIRECTOR_CREDENTIAL_PROFILE = "director"

/**
 * The one local principal (V10): every call no credential establishes runs as it. Stdio, HTTP `/mcp` in local mode
 * (`MCP_ALLOW_UNAUTHENTICATED=true` with the API off, or `API_AUTH_MODE=none`) and REST in unauthenticated mode
 * (`API_AUTH_MODE=none` + `API_ALLOW_UNAUTHENTICATED=true`) all attach it, so ownership, claims and idempotency keys
 * meet across hosts. [ApiCapability.ADMIN] and an unrestricted scope, so every capability and scope check passes
 * unchanged. Kind [PrincipalKind.AGENT], proof [ProofStatus.NONE]; audited as the bare id `local` (no `api:` prefix).
 */
val LOCAL_PRINCIPAL =
    Principal(
        id = "local",
        kind = PrincipalKind.AGENT,
        proofStatus = ProofStatus.NONE,
        scope = AccessScope.unrestricted(),
        capabilities = setOf(ApiCapability.ADMIN)
    )
