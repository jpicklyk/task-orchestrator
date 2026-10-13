package io.github.jpicklyk.mcptask.current.infrastructure.identity.jwt

import com.nimbusds.jwt.JWTClaimsSet

/** Why a token was rejected; [wire] is the `failureKind` value surfaced in verification metadata. */
enum class FailureKind(
    val wire: String,
) {
    /** Parse, key selection, key type or signature failure. */
    CRYPTO("crypto"),

    /** A claim is missing, malformed, out of time bounds or mismatched. */
    CLAIMS("claims"),

    /** A configured policy refused the token (algorithm, untrusted issuer, replayed jti). */
    POLICY("policy"),

    /** An unexpected exception inside the verifier. */
    INTERNAL("internal"),
}

/** Outcome of [JwtVerifier.verify]. */
sealed class JwtVerdict {
    /**
     * Every check passed.
     *
     * @param claims The verified claims set.
     * @param kid The token's `kid` header (null when absent).
     * @param alg The token's signing algorithm name.
     * @param cacheState How the key set that verified the token was served.
     */
    data class Verified(
        val claims: JWTClaimsSet,
        val kid: String?,
        val alg: String,
        val cacheState: CacheState,
    ) : JwtVerdict()

    /** The token is bad (or the verifier failed unexpectedly, [FailureKind.INTERNAL]). */
    data class Rejected(
        val reason: String,
        val failureKind: FailureKind,
    ) : JwtVerdict()

    /** No key set could be obtained; the token itself was not judged. */
    data class Unavailable(
        val reason: String,
    ) : JwtVerdict()
}
