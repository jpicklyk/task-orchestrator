package io.github.jpicklyk.mcptask.current.identity

import io.github.jpicklyk.mcptask.current.model.error.DomainError
import io.github.jpicklyk.mcptask.current.model.error.ErrorCode
import io.github.jpicklyk.mcptask.current.model.error.ErrorDetail
import io.github.jpicklyk.mcptask.current.model.error.ForbiddenScope
import io.github.jpicklyk.mcptask.current.platform.support.ScopeGuard

/**
 * The [CredentialResolver] of [authConfig]: bearer mode resolves against [tokenEntries] only, JWKS mode through
 * [jwksVerifier] only (null when that verifier is missing, so every JWT fails closed). Null for the modes that
 * resolve no credential.
 */
internal fun credentialResolverFor(
    authConfig: ApiAuthConfig,
    tokenEntries: Map<HashBytes, BearerTokenStore.TokenEntry>,
    jwksVerifier: JwksApiVerifier?,
    nonRootAnchors: suspend (Set<java.util.UUID>) -> Set<java.util.UUID>,
    clock: () -> java.time.Instant
): CredentialResolver? =
    when (authConfig) {
        is ApiAuthConfig.Bearer -> CredentialResolver(tokenEntries, null, nonRootAnchors, clock)
        is ApiAuthConfig.Jwks -> jwksVerifier?.let { CredentialResolver(emptyMap(), it, nonRootAnchors, clock) }
        // The SSE route resolves in Disabled mode too (tests wire it without the REST plugin); it then accepts
        // whichever source is configured, exactly as before the resolver existed.
        is ApiAuthConfig.Disabled -> CredentialResolver(tokenEntries, jwksVerifier, nonRootAnchors, clock)
        is ApiAuthConfig.Unauthenticated -> null
    }

/** True when this credential is confined to the director path (token-file `profile: director`). */
internal fun Resolved.isDirectorOnly(): Boolean = profile == DIRECTOR_CREDENTIAL_PROFILE

/** The `forbidden` error of a director-profile credential used outside the director path. */
internal fun directorOnlyForbidden(): DomainError = ScopeGuard.forbidden(ForbiddenScope.CAPABILITY, DIRECTOR_CREDENTIAL_PROFILE)

/**
 * The `unauthenticated` error (401) of a rejected credential. The RFC 6750 `WWW-Authenticate` challenge the caller sets
 * next to it keeps its `error=` attribute; the body is the 4.0 error envelope.
 */
internal fun unauthenticated(message: String): DomainError = DomainError(ErrorCode.UNAUTHENTICATED, message)

/**
 * The `unavailable` error (503) of a credential that could not be judged because the JWKS key set is unavailable
 * (never fetched, or past `max_stale_seconds`): retryable, carries `retryAfterMs`, and sets NO `WWW-Authenticate`
 * (the credential was not found invalid). Logs one WARN naming the reason, never the token.
 */
internal fun keySetUnavailable(
    decision: TrustDecision.Unavailable,
    logger: org.slf4j.Logger
): DomainError {
    logger.warn("Credential not judged, the JWKS key set is unavailable: {}", decision.reason)
    return DomainError(
        ErrorCode.UNAVAILABLE,
        "Authentication is temporarily unavailable; retry shortly.",
        ErrorDetail.Unavailable(decision.retryAfterMs)
    )
}

/**
 * Parses an `Authorization` header value for the `Bearer` auth-scheme (RFC 7235 §2.1,
 * RFC 6750 §2.1): `credentials = auth-scheme [ 1*SP ( token68 / #auth-param ) ]`.
 *
 * Returns `null` unless [headerValue] starts with the case-insensitive literal `Bearer` followed
 * by at least one space (`SP`, 0x20) — this codebase treats a single literal space as satisfying
 * `1*SP`. A tab, another separator, or no separator at all (bare `"Bearer"`, or a mismatched
 * scheme like `"Basic T"`) does not match and returns `null`.
 *
 * When it matches, returns everything after that one separator, trimmed of surrounding
 * whitespace — this MAY be `""` (e.g. `"Bearer   "` → `""`); callers that require a non-empty
 * credential must check for that themselves.
 *
 * Only ONE `Bearer`/`bearer` prefix is ever stripped: `"Bearer bearer T"` returns `"bearer T"`,
 * not `"T"`. RFC 6750's `b64token` grammar has no internal whitespace, so a value containing a
 * second, un-stripped `bearer ` prefix cannot be a valid token — callers should reject it as
 * `invalid_token` rather than silently authenticating the inner value.
 */
internal fun extractBearerToken(headerValue: String): String? {
    val schemeLength = "Bearer".length
    if (headerValue.length <= schemeLength) return null
    if (!headerValue.regionMatches(0, "Bearer", 0, schemeLength, ignoreCase = true)) return null
    if (headerValue[schemeLength] != ' ') return null
    return headerValue.substring(schemeLength + 1).trim()
}
