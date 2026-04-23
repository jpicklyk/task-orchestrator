package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Result of verifying an actor claim.
 *
 * Values:
 * - [ABSENT] — no proof was supplied by the caller (previously "unverified" in storage).
 * - [UNCHECKED] — a noop/bypass verifier was active; the claim was accepted without inspection.
 * - [VERIFIED] — proof was cryptographically valid and all claims passed.
 * - [REJECTED] — proof was present but failed validation (signature, claims, policy).
 * - [UNAVAILABLE] — verification could not be completed due to a transient infrastructure error
 *   (e.g., JWKS endpoint unreachable).
 *
 * Legacy string values stored in the database are mapped on read:
 * - `"unverified"` → [ABSENT]
 * - `"failed"` → [REJECTED]
 */
enum class VerificationStatus {
    ABSENT,
    UNCHECKED,
    VERIFIED,
    REJECTED,
    UNAVAILABLE;

    fun toJsonString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): VerificationStatus {
            // Back-compat mapping for legacy stored values.
            return when (value.lowercase()) {
                "unverified" -> ABSENT
                "failed" -> REJECTED
                else ->
                    entries.find { it.name.equals(value, ignoreCase = true) }
                        ?: throw IllegalArgumentException("Unknown VerificationStatus: $value")
            }
        }
    }
}

/**
 * Outcome of running an [ActorVerifier] against an [ActorClaim].
 *
 * @param status Coarse verification outcome.
 * @param verifier Name of the verifier that produced this result (e.g. "jwks", "noop").
 * @param reason Human-readable explanation for non-[VerificationStatus.VERIFIED] outcomes.
 * @param metadata Structured signal bag for downstream consumers. Common keys:
 *   - `failureKind`: one of `crypto`, `claims`, `policy`, `network`, `internal`
 *   - `verifiedFromCache`: `"true"` when the VERIFIED result was produced using a stale JWKS cache
 *   - `cacheAgeSeconds`: seconds since the stale cache entry was fetched
 */
data class VerificationResult(
    val status: VerificationStatus,
    val verifier: String? = null,
    val reason: String? = null,
    val metadata: Map<String, String> = emptyMap()
)
