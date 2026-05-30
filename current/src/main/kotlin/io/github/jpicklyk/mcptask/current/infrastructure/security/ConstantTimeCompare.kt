package io.github.jpicklyk.mcptask.current.infrastructure.security

import java.security.MessageDigest

/**
 * Constant-time byte array comparison utility.
 *
 * Uses [MessageDigest.isEqual] which is specified by the JCA to run in constant time,
 * preventing timing side-channels when comparing cryptographic material such as
 * pre-computed token hashes.
 *
 * This is intentionally a singleton object rather than a free function to make
 * call sites explicit and searchable for security audit purposes.
 */
object ConstantTimeCompare {
    /**
     * Returns true if [a] and [b] are equal, comparing all bytes in constant time.
     *
     * When the arrays have different lengths [MessageDigest.isEqual] returns false
     * without short-circuiting — the comparison time is still bounded by the shorter
     * array's length, so callers should ensure both arrays are the same length
     * (e.g., both are SHA-256 digests) when using this for token verification.
     */
    fun equal(
        a: ByteArray,
        b: ByteArray,
    ): Boolean = MessageDigest.isEqual(a, b)
}
