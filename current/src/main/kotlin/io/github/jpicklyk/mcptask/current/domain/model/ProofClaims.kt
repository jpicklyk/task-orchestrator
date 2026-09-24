package io.github.jpicklyk.mcptask.current.domain.model

/**
 * Cryptographically verified JWT claims extracted from an [ActorClaim.proof] on a
 * [VerificationStatus.VERIFIED] outcome only.
 *
 * This is forensic evidence about the proof — never the proof itself. It is populated by
 * [io.github.jpicklyk.mcptask.current.infrastructure.config.JwksActorVerifier] from the
 * signed JWT's header (`kid`, `alg`) and claims set (`iss`, `sub`, `aud`, `jti`, `iat`, `exp`)
 * ONLY when the signature and standard claims validated — REJECTED/UNAVAILABLE outcomes carry
 * unverified, attacker-controlled claims and must not surface them here.
 *
 * All fields are nullable because a valid JWT is not required to carry every optional claim
 * (RFC 7519 §4.1 — `iss`/`sub`/`aud`/`exp`/`iat`/`jti` are all OPTIONAL at the spec level, even
 * though this codebase's [JwksActorVerifier] separately requires `exp` to be present).
 *
 * `iat`/`exp` are epoch seconds (as carried in the JWT numeric date claims — RFC 7519 §2), not
 * ISO-8601 strings, to keep round-tripping through JSON storage lossless and unambiguous.
 */
data class ProofClaims(
    val iss: String? = null,
    val sub: String? = null,
    val aud: List<String>? = null,
    val jti: String? = null,
    val iat: Long? = null,
    val exp: Long? = null,
    val kid: String? = null,
    val alg: String? = null,
)
