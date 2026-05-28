package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import java.io.FileReader
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Loads API bearer tokens from a YAML secret file and builds an in-memory
 * [ApiAuthConfig.Bearer] ready for the authentication plugin.
 *
 * The secret file is expected at the path provided by [filePath] (typically from
 * the `API_TOKENS_PATH` environment variable, defaulting to `/run/secrets/api-tokens.yaml`).
 *
 * **Fail-fast validation** — every detected misconfig throws [IllegalArgumentException]
 * with a descriptive message.  The server must not start with an invalid token file.
 *
 * Token hashes are stored as [HashBytes] wrapping raw SHA-256 digests.  The plaintext token
 * is never stored or logged.
 *
 * **Expired tokens** are filtered out at lookup time via [lookupPrincipal] — they are loaded
 * into the map but the expiry is checked on each request so clock changes take effect without
 * restart.
 */
class BearerTokenStore(
    private val filePath: String,
    private val clock: () -> Instant = { Instant.now() },
) {
    private val logger = LoggerFactory.getLogger(BearerTokenStore::class.java)

    /**
     * Loads and validates the token file, returning an [ApiAuthConfig.Bearer] instance.
     *
     * @throws IllegalArgumentException on any validation failure (missing file, malformed
     *   YAML, bad hash, duplicate IDs, unknown capability, unsupported version, etc.).
     */
    @Suppress("UNCHECKED_CAST")
    fun load(): ApiAuthConfig.Bearer {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException(
                "API token file not found: '$filePath'. " +
                    "Set API_TOKENS_PATH to the correct path or mount the secret file.",
            )
        }
        if (file.length() == 0L) {
            throw IllegalArgumentException(
                "API token file is empty: '$filePath'. " +
                    "The file must contain at least one token.",
            )
        }

        val yaml = Yaml()
        val root: Map<String, Any> =
            try {
                FileReader(file).use { yaml.load(it) }
                    ?: throw IllegalArgumentException("API token file '$filePath' parsed to null — check YAML structure.")
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to parse API token file '$filePath': ${e.message}",
                )
            }

        // Version check
        val version = root["version"]
        if (version == null || version !is Int || version != 1) {
            throw IllegalArgumentException(
                "API token file '$filePath' has unsupported version '${version ?: "missing"}'. " +
                    "Only version 1 is supported.",
            )
        }

        val rawTokens =
            (root["tokens"] as? List<*>)
                ?: throw IllegalArgumentException(
                    "API token file '$filePath' is missing the 'tokens' list.",
                )

        if (rawTokens.isEmpty()) {
            throw IllegalArgumentException(
                "API token file '$filePath' contains no tokens. " +
                    "At least one token entry is required.",
            )
        }

        val seenIds = mutableSetOf<String>()
        val result = mutableMapOf<HashBytes, TokenEntry>()

        rawTokens.forEachIndexed { index, rawToken ->
            val tokenMap =
                rawToken as? Map<*, *>
                    ?: throw IllegalArgumentException(
                        "API token file: token at index $index is not a mapping.",
                    )

            val tokenId =
                (tokenMap["id"] as? String)?.trim()
                    ?: throw IllegalArgumentException(
                        "API token file: token at index $index is missing required field 'id'.",
                    )
            if (tokenId.isBlank()) {
                throw IllegalArgumentException(
                    "API token file: token at index $index has blank 'id'.",
                )
            }
            if (!seenIds.add(tokenId)) {
                throw IllegalArgumentException(
                    "API token file: duplicate token id '$tokenId' at index $index.",
                )
            }

            val description = tokenMap["description"] as? String
            if (description != null && description.length > 1024) {
                throw IllegalArgumentException(
                    "API token file: token '$tokenId' description exceeds 1024 characters " +
                        "(actual: ${description.length}).",
                )
            }

            val hashHex =
                (tokenMap["token_sha256"] as? String)
                    ?: throw IllegalArgumentException(
                        "API token file: token '$tokenId' is missing required field 'token_sha256'.",
                    )

            validateHashHex(tokenId, hashHex)
            val hashBytes = hexToBytes(hashHex)

            val expiresAt = parseExpiresAt(tokenId, tokenMap)

            val scopeMap = tokenMap["scope"] as? Map<*, *>
            val scope = parseScope(tokenId, scopeMap)

            val rawCapabilities = tokenMap["capabilities"]
            val capabilities = parseCapabilities(tokenId, rawCapabilities)

            val principal =
                ApiPrincipal(
                    tokenId = tokenId,
                    scope = scope,
                    capabilities = capabilities,
                    authMode = ApiAuthMode.BEARER,
                )
            result[HashBytes(hashBytes)] = TokenEntry(principal, expiresAt)
        }

        logger.info("Loaded {} bearer token(s) from '{}'", result.size, filePath)

        // Return a config where each map entry is the digest → principal (no expiry attached).
        // Expiry is checked at lookup time via lookupPrincipal below.
        val tokenMap = result.mapValues { it.value.principal }
        return ApiAuthConfig.Bearer(tokens = tokenMap)
    }

    /**
     * Looks up a presented raw token, applying expiry validation.
     *
     * @param rawToken The plaintext token from the `Authorization: Bearer` header.
     * @param bearer The loaded [ApiAuthConfig.Bearer] config (from [load]).
     * @param entries The internal entry map that includes expiry metadata.  For the
     *   production path, call [loadWithEntries] instead of [load].
     * @return The matching [ApiPrincipal], or null if no match or the token is expired.
     */
    internal fun lookupInEntries(
        rawToken: String,
        entries: Map<HashBytes, TokenEntry>,
    ): ApiPrincipal? {
        val digest = sha256(rawToken)
        val entry = entries[HashBytes(digest)] ?: return null
        val expiry = entry.expiresAt
        if (expiry != null && clock().isAfter(expiry)) {
            logger.debug("Bearer token '{}' is expired (expires_at={})", entry.principal.tokenId, expiry)
            return null
        }
        return entry.principal
    }

    /**
     * Loads the token file returning the internal [TokenEntry] map that includes expiry metadata.
     * Use this when you need expiry-aware lookups via [lookupInEntries].
     */
    @Suppress("UNCHECKED_CAST")
    fun loadWithEntries(): Map<HashBytes, TokenEntry> {
        val file = java.io.File(filePath)
        if (!file.exists()) {
            throw IllegalArgumentException(
                "API token file not found: '$filePath'. " +
                    "Set API_TOKENS_PATH to the correct path or mount the secret file.",
            )
        }
        if (file.length() == 0L) {
            throw IllegalArgumentException(
                "API token file is empty: '$filePath'.",
            )
        }

        val yaml = Yaml()
        val root: Map<String, Any> =
            try {
                FileReader(file).use { yaml.load(it) }
                    ?: throw IllegalArgumentException("API token file '$filePath' parsed to null.")
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Failed to parse API token file '$filePath': ${e.message}",
                )
            }

        val version = root["version"]
        if (version == null || version !is Int || version != 1) {
            throw IllegalArgumentException(
                "API token file '$filePath' has unsupported version '${version ?: "missing"}'.",
            )
        }

        val rawTokens =
            (root["tokens"] as? List<*>)
                ?: throw IllegalArgumentException("API token file '$filePath' missing 'tokens' list.")

        if (rawTokens.isEmpty()) {
            throw IllegalArgumentException("API token file '$filePath' has empty 'tokens' list.")
        }

        val seenIds = mutableSetOf<String>()
        val result = mutableMapOf<HashBytes, TokenEntry>()

        rawTokens.forEachIndexed { index, rawToken ->
            val tokenMap =
                rawToken as? Map<*, *>
                    ?: throw IllegalArgumentException("Token at index $index is not a mapping.")

            val tokenId =
                (tokenMap["id"] as? String)?.trim()
                    ?: throw IllegalArgumentException("Token at index $index missing 'id'.")
            if (tokenId.isBlank()) throw IllegalArgumentException("Token at index $index has blank 'id'.")
            if (!seenIds.add(tokenId)) throw IllegalArgumentException("Duplicate token id '$tokenId'.")

            val description = tokenMap["description"] as? String
            if (description != null && description.length > 1024) {
                throw IllegalArgumentException("Token '$tokenId' description exceeds 1024 chars.")
            }

            val hashHex =
                (tokenMap["token_sha256"] as? String)
                    ?: throw IllegalArgumentException("Token '$tokenId' missing 'token_sha256'.")
            validateHashHex(tokenId, hashHex)
            val hashBytes = hexToBytes(hashHex)

            val expiresAt = parseExpiresAt(tokenId, tokenMap)
            val scopeMap = tokenMap["scope"] as? Map<*, *>
            val scope = parseScope(tokenId, scopeMap)
            val capabilities = parseCapabilities(tokenId, tokenMap["capabilities"])

            val principal =
                ApiPrincipal(
                    tokenId = tokenId,
                    scope = scope,
                    capabilities = capabilities,
                    authMode = ApiAuthMode.BEARER,
                )
            result[HashBytes(hashBytes)] = TokenEntry(principal, expiresAt)
        }

        return result
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /** Validates that [hashHex] is exactly 64 lowercase hex characters. */
    private fun validateHashHex(
        tokenId: String,
        hashHex: String,
    ) {
        if (hashHex.length != 64) {
            throw IllegalArgumentException(
                "API token file: token '$tokenId' has token_sha256 with invalid length " +
                    "${hashHex.length} (expected 64 hex characters).",
            )
        }
        if (!hashHex.matches(Regex("[0-9a-f]{64}"))) {
            throw IllegalArgumentException(
                "API token file: token '$tokenId' has token_sha256 with invalid characters " +
                    "(expected lowercase hex [0-9a-f]{64}).",
            )
        }
    }

    /** Converts a 64-char lowercase hex string to a 32-byte array. */
    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(32)
        for (i in 0 until 32) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }

    /** Parses and validates [expires_at] from the token map.  Returns null if absent. */
    private fun parseExpiresAt(
        tokenId: String,
        tokenMap: Map<*, *>,
    ): Instant? {
        val raw = tokenMap["expires_at"] as? String ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException(
                "API token file: token '$tokenId' has unparseable expires_at '$raw' " +
                    "(expected ISO-8601 UTC instant, e.g. '2026-12-31T23:59:59Z'): ${e.message}",
            )
        }
    }

    /** Parses the [scope] block from the token map. */
    @Suppress("UNCHECKED_CAST")
    private fun parseScope(
        tokenId: String,
        scopeMap: Map<*, *>?,
    ): ApiScope {
        if (scopeMap == null) return ApiScope(rootIds = null, tagsInclude = emptySet())

        val rawRootIds = scopeMap["root_ids"]
        val rootIds: Set<UUID>? =
            when (rawRootIds) {
                null, is List<*> -> {
                    val list = (rawRootIds as? List<*>) ?: emptyList<Any>()
                    if (list.isEmpty()) {
                        null // empty list → unrestricted (same as null per spec)
                    } else {
                        list.map { item ->
                            try {
                                UUID.fromString(item.toString())
                            } catch (e: IllegalArgumentException) {
                                throw IllegalArgumentException(
                                    "API token file: token '$tokenId' scope.root_ids contains " +
                                        "invalid UUID '$item': ${e.message}",
                                )
                            }
                        }.toSet()
                    }
                }
                else -> null
            }

        val rawTags = scopeMap["tags_include"]
        val tagsInclude: Set<String> =
            when (rawTags) {
                null -> emptySet()
                is List<*> -> rawTags.filterIsInstance<String>().toSet()
                else -> emptySet()
            }

        return ApiScope(rootIds = rootIds, tagsInclude = tagsInclude)
    }

    /** Parses the [capabilities] list from the raw YAML value. */
    private fun parseCapabilities(
        tokenId: String,
        rawCapabilities: Any?,
    ): Set<ApiCapability> {
        val capList =
            when (rawCapabilities) {
                null -> emptyList()
                is List<*> -> rawCapabilities.filterIsInstance<String>()
                else ->
                    throw IllegalArgumentException(
                        "API token file: token '$tokenId' capabilities must be a list.",
                    )
            }

        return capList
            .map { capStr ->
                try {
                    ApiCapability.fromConfigString(capStr)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException(
                        "API token file: token '$tokenId' has unknown capability '$capStr'. " +
                            "Valid values: read, write-notes, write-items, advance, manage-dependencies, admin.",
                    )
                }
            }
            .toSet()
    }

    /** Computes SHA-256 of the UTF-8 encoding of [input]. */
    private fun sha256(input: String): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8))
    }

    /**
     * Internal holder that attaches [expiresAt] metadata to the resolved [principal].
     * Used only within this class; [ApiAuthConfig.Bearer.tokens] stores principal-only values.
     */
    data class TokenEntry(
        val principal: ApiPrincipal,
        val expiresAt: Instant?,
    )
}
