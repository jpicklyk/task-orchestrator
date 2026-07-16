package io.github.jpicklyk.mcptask.current.infrastructure.security

import java.security.MessageDigest

/**
 * Computes the SHA-256 digest of [bytes] and returns it as a lowercase hex string.
 *
 * This is the single server-side definition of the config-fingerprint hash: config fingerprints
 * are compared for equality between the client (`config-sync.mjs`'s `createHash('sha256')...digest('hex')`)
 * and the server ([io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteProjectConfigRepository.computeFingerprint]
 * and [io.github.jpicklyk.mcptask.current.infrastructure.config.YamlWorkItemSchemaService.getConfigFingerprint]) —
 * every server-side call site MUST route through this function so the two sides can never silently
 * diverge (e.g. a stray uppercase-hex or different digest algorithm on one side only).
 *
 * Scoped to the config subsystem: other pre-existing SHA-256/hex call sites in this codebase
 * (`BearerTokenStore`, `AuthenticationPlugin`, `EventRoutes`) are tracked separately and are not
 * converted to this utility here.
 */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
