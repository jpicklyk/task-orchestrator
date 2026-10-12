package io.github.jpicklyk.mcptask.current.application.support

import java.security.MessageDigest

/**
 * Computes the SHA-256 digest of [bytes] and returns it as a lowercase hex string.
 *
 * This is the single server-side definition of the raw SHA-256 hex primitive. Config fingerprints
 * specifically MUST route through `configFingerprint` (which normalizes before hashing), not this
 * function directly (see `configFingerprint`'s KDoc in infrastructure/security).
 *
 * Scoped to the config subsystem: other pre-existing SHA-256/hex call sites in this codebase
 * (`BearerTokenStore`, `AuthenticationPlugin`, `EventRoutes`) are tracked separately and are not
 * converted to this utility here.
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
