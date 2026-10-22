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
 * Verification outcome recorded with an [ActorClaim]; persisted as audit data.
 *
 * @param status Coarse verification outcome.
 * @param verifier Name of the verifier that produced this result (e.g. "jwks", "noop").
 * @param reason Human-readable explanation for non-[VerificationStatus.VERIFIED] outcomes.
 * @param metadata Structured signal bag for downstream consumers. Common keys:
 *   - `failureKind`: one of `crypto`, `claims`, `policy`, `network`, `internal`
 *   - `verifiedFromCache`: `"true"` when the VERIFIED result was produced using a stale JWKS cache
 *   - `cacheAgeSeconds`: seconds since the stale cache entry was fetched
 * @param verifiedSubject Never set by a production path; always null (e.g. the
 *   VERIFIED result built in PrincipalAudit leaves it null). Not persisted or serialized.
 * @param proofSha256 Lowercase hex SHA-256 digest of the raw proof's UTF-8 bytes, computed by
 *   by the code that verified a presented proof whenever a non-blank proof was supplied  -  independent of verification outcome. This is the durable,
 *   non-replayable forensic trace persisted in place of the raw proof (see `NotesTable`
 *   `actor_proof_sha256` and the `item.transitioned` event payload). Null when no proof was supplied.
 * @param proofClaims Verified JWT claims (see [ProofClaims]); populated only on
 *   [VerificationStatus.VERIFIED] by the verifier. Null otherwise.
 */
data class VerificationResult(
    val status: VerificationStatus,
    val verifier: String? = null,
    val reason: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val verifiedSubject: String? = null,
    val proofSha256: String? = null,
    val proofClaims: ProofClaims? = null
)

/**
 * The ONE mapping from a stored verification (`notes.verification_status`, `events.proof_status`,
 * `call_log.proof_status`) to the proof vocabulary, as wire spellings. `application.api.ProofStatus.fromStored` is this
 * mapping typed; the layers that cannot import `application.api` (services, telemetry) use [VerificationResult.proofWire].
 *
 * `verified`, `verified_stale`, `issued`, `none` and `rejected` map to themselves; `unchecked` is `issued` when
 * [verifier] is `api-bearer` (case-insensitive) and `none` for any other verifier; the legacy `absent`, `unverified`
 * and `unavailable` are `none`; the legacy `failed` is `rejected`. [status] is trimmed and case-insensitive.
 */
object ProofVocabulary {
    const val VERIFIED = "verified"
    const val VERIFIED_STALE = "verified_stale"
    const val ISSUED = "issued"
    const val NONE = "none"
    const val REJECTED = "rejected"

    /** The proof wire spelling of the stored [status] under [verifier], or null for a status the vocabulary does not know. */
    fun fromStored(
        status: String,
        verifier: String?
    ): String? =
        when (status.trim().lowercase()) {
            "verified" -> VERIFIED
            "verified_stale" -> VERIFIED_STALE
            "issued" -> ISSUED
            "none" -> NONE
            "rejected" -> REJECTED
            "unchecked" -> if (verifier?.trim().equals("api-bearer", ignoreCase = true)) ISSUED else NONE
            "absent", "unverified", "unavailable" -> NONE
            "failed" -> REJECTED
            else -> null
        }
}

/** This verification in the proof vocabulary, as a wire spelling ([ProofVocabulary.fromStored]); never null for a known status. */
fun VerificationResult.proofWire(): String? = ProofVocabulary.fromStored(status.toJsonString(), verifier)
