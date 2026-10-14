package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Top-level actor authentication configuration parsed from `.taskorchestrator/config.yaml`
 * under the `actor_authentication:` key.
 *
 * There is deliberately no `enabled` field: `actor_authentication.enabled` is read only by the
 * client-side plugin hook (`enforce-actor-attribution.mjs`), never by the server, and is ignored here.
 *
 * There is no degraded-mode policy: verification fails closed (an unavailable key set answers 503,
 * never a self-reported identity), so the former `degraded_mode_policy` key is rejected at startup.
 *
 * @param verifier The actor-claim verifier strategy to use.
 */
data class ActorAuthenticationConfig(
    val verifier: VerifierConfig = VerifierConfig.Noop
)

/**
 * Discriminated union describing how actor claims on MCP transitions are verified.
 *
 * - [Noop] — no verification; all claims are accepted as-is.
 * - [Jwks] — validate JWT bearer tokens against a JWKS source.
 */
sealed class VerifierConfig {
    /** No-op verifier: every actor claim passes without inspection. */
    data object Noop : VerifierConfig()

    /**
     * JWKS-backed verifier.
     *
     * Two mutually exclusive trust modes:
     *
     * **Static-JWKS mode:** exactly one of [oidcDiscovery], [jwksUri], or [jwksPath] must be
     * provided; all three DID fields ([didAllowlist], [didPattern]) must be null/empty.
     *
     * **DID-trust mode:** triggered when [didAllowlist] is non-empty OR [didPattern] is non-null.
     * In this mode all of [oidcDiscovery], [jwksUri], and [jwksPath] must be null — the verifier
     * resolves keys from the actor's DID document instead of a pre-configured JWKS endpoint.
     *
     * The service that constructs the verifier enforces the mutual-exclusion constraint at startup.
     *
     * @param oidcDiscovery HTTPS URL of an OIDC Discovery document (`/.well-known/openid-configuration`).
     *   The JWKS URI is fetched from the `jwks_uri` field of the discovery document.
     * @param jwksUri Direct HTTPS URL of a JWKS endpoint.
     * @param jwksPath File-system path to a local JWKS JSON file (resolution done by the provider).
     * @param issuer Expected `iss` claim value; null means the issuer is not checked (any accepted).
     *   The YAML loader logs a startup WARN in static JWKS mode when it is missing (and no
     *   `oidc_discovery` supplies one), and the verifier logs a one-time WARN when OIDC discovery
     *   yields none. Always setting it is recommended.
     * @param audience Expected `aud` claim value; null means the audience is not checked (any
     *   accepted). The YAML loader logs a startup WARN in static JWKS mode when it is missing.
     *   Always setting it is recommended.
     * @param algorithms Allowed signing algorithms (e.g. `["RS256", "ES256"]`).
     *   An empty list means "accept any algorithm supported by the JWKS".
     * @param cacheTtlSeconds How long (in seconds) to cache the fetched JWKS (default 300).
     * @param requireSubMatch When true, the JWT `sub` claim must match the actor id supplied by
     *   the caller (default true).
     * @param staleOnError When true (default), a stale cached key set is served if the JWKS
     *   endpoint is unreachable during a refresh. When false, the fetch exception propagates.
     * @param maxStaleSeconds Config key `max_stale_seconds` (default 3600; the YAML loader defaults
     *   it to `max(3600, cache_ttl_seconds)` and requires it to be positive and at least
     *   [cacheTtlSeconds]). With [staleOnError], the oldest a cached key set may be -- measured from
     *   its real fetch time -- and still be served after a failed refresh; past it the key set is
     *   unavailable and verification reports UNAVAILABLE.
     * @param didAllowlist Explicit list of DID strings trusted as actor identities in DID-trust mode.
     *   Non-empty activates DID-trust mode; must be combined with a null [oidcDiscovery]/[jwksUri]/[jwksPath].
     * @param didPattern Glob or regex pattern for trusted DIDs in DID-trust mode. Non-null activates
     *   DID-trust mode; must be combined with a null [oidcDiscovery]/[jwksUri]/[jwksPath].
     *   Content validation (glob vs regex, compilation) is handled by the verifier layer, not this config class.
     * @param didStrictRelationship When true (default), only verification methods referenced from the
     *   resolved DID document's `assertionMethod` array are eligible for JWT signature verification.
     *   Setting false allows any key present in the DID document.
     * @param didLooseKidMatch When true (default), if the JWT's `kid` header is not found among the
     *   eligible keys in the resolved DID document AND the eligible-key set contains exactly one entry,
     *   that single key is used for verification (single-key guard). Multi-key documents still require
     *   an exact `kid` match regardless of this setting. Set false to require strict `kid` matching
     *   in all cases.
     * @param allowInsecureUrl Opt-in flag (config key `allow_insecure_url`, default false) mirroring
     *   the REST API's `API_JWKS_ALLOW_INSECURE_URL`. When false (default), [jwksUri] and
     *   [oidcDiscovery] — plus a `jwks_uri` discovered via that OIDC document — must use `https`.
     *   When true, `http` is additionally accepted for a literal loopback host
     *   (`localhost`, `127.x.x.x`, `::1`) — local development/testing only. Does not affect
     *   [jwksPath] (a local file) or DID-trust mode, which are always resolved over `https` by the
     *   DID resolver itself.
     * @param maxTokenLifetimeSeconds Config key `max_token_lifetime_seconds` (default 86400 = 24h).
     *   Caps how long a token may remain acceptable from the moment it is presented: a token is
     *   rejected once `exp - now > maxTokenLifetimeSeconds + 60` (60s clock skew), regardless of
     *   `iat`; when `iat` is present, a token is additionally rejected when `iat` is more than 60s
     *   in the future, or when `exp - iat > maxTokenLifetimeSeconds + 60`. Must be a positive
     *   number — enforced at config-parse time, not here.
     * @param jtiReplayProtection Config key `jti_replay_protection` (default false — off).
     *   When enabled, every verified token must carry a non-blank `jti` claim, and the
     *   `(iss, jti)` pair may be presented only once per [JtiReplayCache] retention window
     *   (`exp + 60s`). Off by default because the same proof is legitimately re-verified multiple
     *   times within a single MCP call (idempotency keys, multi-transition batches). No request body carries a
     *   proof since R14 (the call's principal comes from its transport), so nothing re-verifies one per call today.
     */
    data class Jwks(
        val oidcDiscovery: String? = null,
        val jwksUri: String? = null,
        val jwksPath: String? = null,
        val issuer: String? = null,
        val audience: String? = null,
        val algorithms: List<String> = emptyList(),
        val cacheTtlSeconds: Long = 300,
        val requireSubMatch: Boolean = true,
        val staleOnError: Boolean = true,
        val didAllowlist: List<String> = emptyList(),
        val didPattern: String? = null,
        val didStrictRelationship: Boolean = true,
        val didLooseKidMatch: Boolean = true,
        val allowInsecureUrl: Boolean = false,
        val maxTokenLifetimeSeconds: Long = 86400,
        val jtiReplayProtection: Boolean = false,
        val maxStaleSeconds: Long = 3600
    ) : VerifierConfig()
}
