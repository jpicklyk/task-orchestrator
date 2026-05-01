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
 *   self-reported `actor.id` with a WARN log so operators see the degradation. For other
 *   non-VERIFIED outcomes (ABSENT, UNCHECKED, REJECTED), falls back silently to the
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
        val didLooseKidMatch: Boolean = true
    ) : VerifierConfig()
}
