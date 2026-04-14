package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Result of verifying an actor claim.
 */
enum class VerificationStatus {
    UNVERIFIED,
    VERIFIED,
    FAILED;

    fun toJsonString(): String = name.lowercase()

    companion object {
        fun fromString(value: String): VerificationStatus =
            entries.find { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown VerificationStatus: $value")
    }
}

/**
 * Outcome of running an [ActorVerifier] against an [ActorClaim].
 */
data class VerificationResult(
    val status: VerificationStatus,
    val verifier: String? = null,
    val reason: String? = null
)
