package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Determines how the system behaves when actor-claim verification cannot produce a
 * [VerificationStatus.VERIFIED] result.
 *
 * Three-value enum aligned with the `auth.degradedModePolicy` config key described in the
 * v1 implementation plan (issue #117, distributed-correctness contract item 4).
 *
 * Values:
 * - [ACCEPT_CACHED] *(default)* — preserves the v3.3.0 stale-cache fallback.
 *   When verification status is [VerificationStatus.VERIFIED] (whether fresh OR stale-cache
 *   success — `JwksActorVerifier` returns VERIFIED with `metadata["verifiedFromCache"] == "true"`
 *   when a stale cached key successfully validates the JWT during a JWKS fetch failure), the
 *   verified `actor.id` from the JWT is trusted. When verification status is
 *   [VerificationStatus.UNAVAILABLE] (JWKS down with no usable cache), falls back to the
 *   self-reported `actor.id` with a WARN log so operators see the degradation. When verification
 *   status is [VerificationStatus.REJECTED] (verification was attempted and actively failed —
 *   bad signature, wrong issuer/audience, expired, etc.), also falls back to the self-reported
 *   `actor.id`, logging a WARN naming the verifier and reason (never the proof) so operators
 *   running a real verifier under this policy see that a failed verification was still accepted.
 *   For the remaining non-VERIFIED outcomes (ABSENT, UNCHECKED), falls back silently to the
 *   self-reported `actor.id` (same as pre-v3.3 implicit behavior).
 *
 *   Note: `ActorAware.resolveTrustedActorId` also handles a defensive
 *   "UNAVAILABLE + verifiedFromCache=true" branch — this is reserved for future verifier
 *   implementations that might emit UNAVAILABLE for stale-cache success. The current
 *   `JwksActorVerifier` always returns VERIFIED for that case, so the branch is unreachable
 *   today but preserved for forward compatibility.
 * - [ACCEPT_SELF_REPORTED] — always trusts the caller-supplied `actor.id` regardless of
 *   verification outcome. This is the v3.2 implicit behavior; explicitly labeled here so operators
 *   understand they are opting out of JWKS identity guarantees.
 * - [REJECT] — any operation that requires verified identity is rejected when the verification
 *   status is not [VerificationStatus.VERIFIED]. Recommended for cross-org `did:web` deployments.
 *
 * **Downstream integration points (items 4 and 5):**
 * - `claim_item` (item 4): when placing a claim, the trusted actor id is determined by
 *   `DegradedModePolicy.resolveTrustedActorId(actorClaim, verificationResult)`.
 * - `AdvanceItemTool` ownership checks (item 5): claim ownership is validated against the
 *   same trusted actor id resolver to ensure consistent identity resolution.
 */
enum class DegradedModePolicy {
    /** Trust verified `actor.id` from stale-cache fallback; otherwise fall back to self-reported. */
    ACCEPT_CACHED,

    /**
     * Always trust the self-reported `actor.id` from the caller, regardless of verification.
     * Documents the pre-v3.3 implicit behavior explicitly.
     */
    ACCEPT_SELF_REPORTED,

    /** Reject any operation requiring identity when the actor is not fully verified. */
    REJECT;

    fun toConfigString(): String =
        when (this) {
            ACCEPT_CACHED -> "accept-cached"
            ACCEPT_SELF_REPORTED -> "accept-self-reported"
            REJECT -> "reject"
        }

    companion object {
        /**
         * Parse a config-file string to a [DegradedModePolicy].
         * Accepts both hyphenated config-file form (`accept-cached`) and enum name form (`ACCEPT_CACHED`).
         * Defaults to [ACCEPT_CACHED] if the value is unrecognized (with caller responsible for warning).
         */
        fun fromConfigString(value: String): DegradedModePolicy? =
            when (value.lowercase().replace("_", "-")) {
                "accept-cached" -> ACCEPT_CACHED
                "accept-self-reported" -> ACCEPT_SELF_REPORTED
                "reject" -> REJECT
                else -> null
            }
    }
}

/**
 * Top-level actor authentication configuration parsed from `.taskorchestrator/config.yaml`
 * under the `actor_authentication:` key.
 *
 * @param enabled Whether actor authentication is active (default true).
 * @param verifier The actor-claim verifier strategy to use.
 * @param degradedModePolicy Controls what happens when actor verification is not fully successful.
 *   See [DegradedModePolicy] for the three values and their security trade-offs.
 */
data class ActorAuthenticationConfig(
    val enabled: Boolean = true,
    val verifier: VerifierConfig = VerifierConfig.Noop,
    val degradedModePolicy: DegradedModePolicy = DegradedModePolicy.ACCEPT_CACHED
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
     * @param issuer Expected `iss` claim value; null means "any issuer accepted".
     * @param audience Expected `aud` claim value; null means "any audience accepted".
     * @param algorithms Allowed signing algorithms (e.g. `["RS256", "ES256"]`).
     *   An empty list means "accept any algorithm supported by the JWKS".
     * @param cacheTtlSeconds How long (in seconds) to cache the fetched JWKS (default 300).
     * @param requireSubMatch When true, the JWT `sub` claim must match the actor id supplied by
     *   the caller (default true).
     * @param staleOnError When true (default), a stale cached key set is served if the JWKS
     *   endpoint is unreachable during a refresh. When false, the fetch exception propagates.
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
     *   times within a single MCP call (idempotency keys, multi-transition batches); the
     *   per-call memo ([io.github.jpicklyk.mcptask.current.application.service.ActorVerificationScope])
     *   keeps that in-call reuse from tripping the cache even when this is enabled.
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
        val jtiReplayProtection: Boolean = false
    ) : VerifierConfig()
}
