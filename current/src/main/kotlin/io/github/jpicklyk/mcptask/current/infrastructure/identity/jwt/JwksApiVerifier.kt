package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jwt.JWTClaimsSet
import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ScopeClaimParser
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * A successfully verified API JWT: the resolved [Principal] plus the token's own expiry.
 *
 * [expiresAt] is non-null by construction -- the pipeline rejects a JWT without an `exp` claim
 * before this is built -- so callers holding a long-lived connection (the SSE endpoint) always have
 * an instant to schedule an expiry check against.
 *
 * @param principal The principal resolved from the JWT's `sub` / `to_kind` / `to_scope` / `to_capabilities`.
 * @param expiresAt The JWT's `exp` claim, as a whole-second [Instant].
 */
data class VerifiedApiToken(
    val principal: Principal,
    val expiresAt: Instant,
)

/**
 * The verdict on one presented JWT: [Verified] (a principal and its expiry), [Rejected] (the token itself is
 * invalid: the credential proves nothing, answered 401), or [Unavailable] (the verifier could not decide because the
 * key set is unavailable: never fetched, or past `max_stale_seconds`; answered 503, retryable). Fail closed either way.
 */
sealed class ApiTokenVerdict {
    data class Verified(
        val token: VerifiedApiToken
    ) : ApiTokenVerdict()

    data object Rejected : ApiTokenVerdict()

    /** [reason] is the verifier's own description (never the token). */
    data class Unavailable(
        val reason: String
    ) : ApiTokenVerdict()
}

/**
 * Verifies `Authorization: Bearer <jwt>` tokens for the REST API layer through the shared
 * [JwtVerifier] pipeline under [JwtPolicy.forApi]: algorithm allowlist, `kid` key selection,
 * signature, required `exp`, `nbf`, lifetime cap, `iss`, `aud` and a required `sub`. A verified
 * token is then mapped to a [Principal] from its custom claims (`to_kind`, `to_scope.root_ids`,
 * `to_scope.tags_include`, `to_capabilities`), defaulting to read-only unrestricted scope when
 * those claims are absent.
 *
 * Kind: `to_kind` (`human`, `agent` or `service`, trimmed, case-insensitive). Absent (or null), it is
 * [PrincipalKind.SERVICE], except for the actor DID path (`sub == iss` and `sub` starts with `did:`), which is
 * [PrincipalKind.AGENT]. A non-string or unknown `to_kind` rejects the token (fail-closed, like `to_scope`).
 * Proof: [ProofStatus.VERIFIED], or [ProofStatus.VERIFIED_STALE] when the verifying key set was served from the
 * stale cache.
 *
 * `exp` is REQUIRED: RFC 7519 section 4.1.4 makes it optional, but an API credential that never
 * expires cannot be revoked short of key rotation, and a non-null expiry is what lets long-lived
 * connections (SSE) run an expiry watchdog for the life of the stream.
 *
 * Fail-closed: a rejected token and an unavailable key set are distinct [ApiTokenVerdict]s, neither a principal.
 *
 * @param config The JWKS auth configuration (URL, issuer, audience, algorithms, TTL).
 * @param keyProvider The [JwksKeySetProvider] key material is fetched from.
 * @param clock Injectable clock for unit testing of expiry/nbf logic.
 */
class JwksApiVerifier(
    private val config: ApiAuthConfig.Jwks,
    private val keyProvider: JwksKeySetProvider,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(JwksApiVerifier::class.java)
    private val jwtVerifier = JwtVerifier(JwtPolicy.forApi(config), keyProvider, clock, logger = logger)

    /**
     * Verifies [jwtString] and returns the resolved [Principal] on success, or null on any
     * failure (rejected or unavailable). Delegates to [verifyWithExpiry] and discards the expiry and the distinction.
     * Production resolves REST credentials through `CredentialResolver` only.
     */
    suspend fun verify(jwtString: String): Principal? = (verifyWithExpiry(jwtString) as? ApiTokenVerdict.Verified)?.token?.principal

    /**
     * Verifies [jwtString]: [ApiTokenVerdict.Verified] with the resolved principal and the token's `exp`;
     * [ApiTokenVerdict.Rejected] on any token defect (invalid signature, missing/past `exp`, wrong iss/aud,
     * malformed `to_scope` or `to_kind`, an unexpected verifier fault, etc.); [ApiTokenVerdict.Unavailable] when the
     * key set cannot be had (never fetched, or past `max_stale_seconds`).
     *
     * Rejections are logged at DEBUG to avoid leaking detail in production logs; the caller logs an unavailable
     * key set once at WARN (it answers 503).
     */
    suspend fun verifyWithExpiry(jwtString: String): ApiTokenVerdict =
        try {
            verifyInternal(jwtString)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.debug("Unexpected exception during API JWT verification: {}", e.message)
            ApiTokenVerdict.Rejected
        }

    private suspend fun verifyInternal(jwtString: String): ApiTokenVerdict =
        when (val verdict = jwtVerifier.verify(jwtString)) {
            is JwtVerdict.Rejected -> {
                logger.debug("API JWT rejected ({}): {}", verdict.failureKind.wire, verdict.reason)
                ApiTokenVerdict.Rejected
            }
            is JwtVerdict.Unavailable -> ApiTokenVerdict.Unavailable(verdict.reason)
            is JwtVerdict.Verified ->
                toVerifiedApiToken(verdict.claims, verdict.cacheState.fromStaleCache)
                    ?.let { ApiTokenVerdict.Verified(it) }
                    ?: ApiTokenVerdict.Rejected
        }

    private fun toVerifiedApiToken(
        claims: JWTClaimsSet,
        fromStaleCache: Boolean
    ): VerifiedApiToken? {
        // The pipeline guarantees sub (requireSub) and exp are present on a Verified verdict.
        val sub = claims.subject ?: return null
        val expiry = claims.expirationTime ?: return null

        val scope =
            extractScope(claims) ?: run {
                logger.debug("JWT rejected: malformed to_scope claim")
                return null
            }
        val kind = extractKind(claims, sub) ?: return null
        val capabilities = extractCapabilities(claims)

        return VerifiedApiToken(
            principal =
                Principal(
                    id = sub,
                    kind = kind,
                    proofStatus = if (fromStaleCache) ProofStatus.VERIFIED_STALE else ProofStatus.VERIFIED,
                    scope = scope,
                    capabilities = capabilities,
                ),
            expiresAt = expiry.toInstant(),
        )
    }

    /**
     * The principal kind from the `to_kind` claim. Absent or null: [PrincipalKind.AGENT] on the actor DID path
     * (`sub == iss` and `sub` starts with `did:`), else [PrincipalKind.SERVICE]. A non-string or unknown value
     * returns null so the caller rejects the whole JWT; a WARN names the claim, never the JWT.
     */
    private fun extractKind(
        claims: JWTClaimsSet,
        sub: String
    ): PrincipalKind? {
        val raw = claims.getClaim(TO_KIND)
        if (raw == null) {
            return if (sub == claims.issuer && sub.startsWith(DID_PREFIX)) PrincipalKind.AGENT else PrincipalKind.SERVICE
        }
        val text =
            raw as? String ?: run {
                logger.warn("Rejecting JWT: {} claim is not a string", TO_KIND)
                return null
            }
        return PrincipalKind.parse(text) ?: run {
            logger.warn("Rejecting JWT: {} claim has an unknown value", TO_KIND)
            null
        }
    }

    /**
     * Extracts scope from the `to_scope` JWT claim via [ScopeClaimParser].
     *
     * If `to_scope` is absent, returns unrestricted scope (null rootIds, empty tags). If
     * `to_scope` is present but malformed (not a JSON object, an unknown key, or a malformed
     * `root_ids`/`tags_include` shape -- see [ScopeClaimParser]), returns null so the caller
     * fails the whole JWT closed rather than silently widening access. A single WARN is logged
     * naming the claim path and the defect; it never includes the JWT itself.
     */
    private fun extractScope(claims: JWTClaimsSet): AccessScope? {
        val toScope =
            try {
                claims.getJSONObjectClaim("to_scope")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.warn("Rejecting JWT: to_scope claim is not a JSON object: {}", e.message)
                return null
            }

        return try {
            ScopeClaimParser.parse(toScope)
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejecting JWT: to_scope {}", e.message)
            null
        }
    }

    /**
     * Extracts capabilities from the `to_capabilities` JWT claim.
     *
     * If absent, returns `[READ]` as per the plan's default (section 4.3). Unknown values are still
     * dropped (not fail-closed -- capability semantics are unchanged), but each drop and a
     * non-string-list claim log a WARN naming `to_capabilities` so the widening is visible.
     */
    private fun extractCapabilities(claims: JWTClaimsSet): Set<ApiCapability> {
        val rawCaps =
            try {
                claims.getStringListClaim("to_capabilities")
            } catch (e: Exception) {
                e.rethrowIfCancellation()
                logger.warn("JWT to_capabilities claim is present but is not a string list: {}", e.message)
                null
            }

        if (rawCaps.isNullOrEmpty()) {
            return setOf(ApiCapability.READ)
        }

        return rawCaps
            .mapNotNull { capStr ->
                try {
                    ApiCapability.fromConfigString(capStr)
                } catch (e: IllegalArgumentException) {
                    logger.warn("Ignoring unknown value in JWT to_capabilities: '{}'", capStr)
                    null
                }
            }.toSet()
            .ifEmpty { setOf(ApiCapability.READ) }
    }

    private companion object {
        const val TO_KIND = "to_kind"
        const val DID_PREFIX = "did:"
    }
}
