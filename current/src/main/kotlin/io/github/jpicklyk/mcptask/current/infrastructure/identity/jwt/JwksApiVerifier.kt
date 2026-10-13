package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jwt.JWTClaimsSet
import io.github.jpicklyk.mcptask.current.application.support.rethrowIfCancellation
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiAuthMode
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiCapability
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiPrincipal
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ApiScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ScopeClaimParser
import io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer.ApiAuthConfig
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant

/**
 * A successfully verified API JWT: the resolved [ApiPrincipal] plus the token's own expiry.
 *
 * [expiresAt] is non-null by construction -- the pipeline rejects a JWT without an `exp` claim
 * before this is built -- so callers holding a long-lived connection (the SSE endpoint) always have
 * an instant to schedule an expiry check against.
 *
 * @param principal The principal resolved from the JWT's `sub` / `to_scope` / `to_capabilities`.
 * @param expiresAt The JWT's `exp` claim, as a whole-second [Instant].
 */
data class VerifiedApiToken(
    val principal: ApiPrincipal,
    val expiresAt: Instant,
)

/**
 * Verifies `Authorization: Bearer <jwt>` tokens for the REST API layer through the shared
 * [JwtVerifier] pipeline under [JwtPolicy.forApi]: algorithm allowlist, `kid` key selection,
 * signature, required `exp`, `nbf`, lifetime cap, `iss`, `aud` and a required `sub`. A verified
 * token is then mapped to an [ApiPrincipal] from its custom claims (`to_scope.root_ids`,
 * `to_scope.tags_include`, `to_capabilities`), defaulting to read-only unrestricted scope when
 * those claims are absent.
 *
 * `exp` is REQUIRED: RFC 7519 section 4.1.4 makes it optional, but an API credential that never
 * expires cannot be revoked short of key rotation, and a non-null expiry is what lets long-lived
 * connections (SSE) run an expiry watchdog for the life of the stream.
 *
 * Fail-closed: a rejected token and an unavailable key set both yield null.
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
     * Verifies [jwtString] and returns the resolved [ApiPrincipal] on success, or null on any
     * failure. Delegates to [verifyWithExpiry] and discards the expiry.
     */
    suspend fun verify(jwtString: String): ApiPrincipal? = verifyWithExpiry(jwtString)?.principal

    /**
     * Verifies [jwtString] and returns the resolved principal together with the token's `exp`,
     * or null on any failure (invalid signature, missing/past `exp`, wrong iss/aud, unavailable
     * key set, malformed `to_scope`, etc.).
     *
     * Rejections are logged at DEBUG to avoid leaking detail in production logs; an unavailable
     * key set is logged at WARN.
     */
    suspend fun verifyWithExpiry(jwtString: String): VerifiedApiToken? =
        try {
            verifyInternal(jwtString)
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            logger.debug("Unexpected exception during API JWT verification: {}", e.message)
            null
        }

    private suspend fun verifyInternal(jwtString: String): VerifiedApiToken? =
        when (val verdict = jwtVerifier.verify(jwtString)) {
            is JwtVerdict.Rejected -> {
                logger.debug("API JWT rejected ({}): {}", verdict.failureKind.wire, verdict.reason)
                null
            }
            is JwtVerdict.Unavailable -> {
                logger.warn("API JWT verification unavailable: {}", verdict.reason)
                null
            }
            is JwtVerdict.Verified -> toVerifiedApiToken(verdict.claims)
        }

    private fun toVerifiedApiToken(claims: JWTClaimsSet): VerifiedApiToken? {
        // The pipeline guarantees sub (requireSub) and exp are present on a Verified verdict.
        val sub = claims.subject ?: return null
        val expiry = claims.expirationTime ?: return null

        val scope =
            extractScope(claims) ?: run {
                logger.debug("JWT rejected: malformed to_scope claim")
                return null
            }
        val capabilities = extractCapabilities(claims)

        return VerifiedApiToken(
            principal =
                ApiPrincipal(
                    tokenId = sub,
                    scope = scope,
                    capabilities = capabilities,
                    authMode = ApiAuthMode.JWKS,
                ),
            expiresAt = expiry.toInstant(),
        )
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
    private fun extractScope(claims: JWTClaimsSet): ApiScope? {
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
}
