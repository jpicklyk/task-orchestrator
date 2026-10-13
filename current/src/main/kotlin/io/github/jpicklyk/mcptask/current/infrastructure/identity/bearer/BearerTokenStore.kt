package io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer

import io.github.jpicklyk.mcptask.current.application.api.ApiCapability
import io.github.jpicklyk.mcptask.current.application.api.Principal
import io.github.jpicklyk.mcptask.current.application.api.PrincipalKind
import io.github.jpicklyk.mcptask.current.application.api.ProofStatus
import io.github.jpicklyk.mcptask.current.application.port.AccessScope
import io.github.jpicklyk.mcptask.current.infrastructure.identity.DIRECTOR_CREDENTIAL_PROFILE
import io.github.jpicklyk.mcptask.current.infrastructure.identity.ScopeClaimParser
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.FileReader
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Loads API bearer tokens from a YAML secret file into the entry map the
 * [CredentialResolver][io.github.jpicklyk.mcptask.current.infrastructure.identity.CredentialResolver] resolves against.
 *
 * The secret file is expected at the path provided by [filePath] (typically from the `API_TOKENS_PATH` environment
 * variable, defaulting to `/run/secrets/api-tokens.yaml`).
 *
 * **Fail-fast validation**: every detected misconfig throws [IllegalArgumentException] with a descriptive message.
 * The server must not start with an invalid token file.
 *
 * Token hashes are stored as [HashBytes] wrapping raw SHA-256 digests. The plaintext token is never stored or logged.
 * Expired tokens are loaded; the resolver checks expiry on each request so clock changes take effect without restart.
 *
 * Every entry resolves to a [Principal] with [ProofStatus.ISSUED]; its `kind` (default `service`) and its optional
 * `profile` (only `director`) are read from the entry.
 */
class BearerTokenStore(
    private val filePath: String,
) {
    private val logger = LoggerFactory.getLogger(BearerTokenStore::class.java)

    /**
     * Loads and validates the token file, returning the entry map keyed by token digest.
     *
     * @throws IllegalArgumentException on any validation failure (missing file, malformed YAML, bad hash, duplicate
     *   ids, unknown key, capability, kind or profile, unsupported version, etc.).
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
                "API token file is empty: '$filePath'. " +
                    "The file must contain at least one token.",
            )
        }

        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val root: Map<String, Any> =
            try {
                (
                    FileReader(file).use { reader -> yaml.load<Any?>(reader) }
                        ?: throw IllegalArgumentException("API token file '$filePath' parsed to null — check YAML structure.")
                )
                    as Map<String, Any>
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
            validateEntryKeys(tokenId, tokenMap)

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

            val principal =
                Principal(
                    id = tokenId,
                    kind = parseKind(tokenId, tokenMap["kind"]),
                    proofStatus = ProofStatus.ISSUED,
                    scope = parseScope(tokenId, tokenMap["scope"]),
                    capabilities = parseCapabilities(tokenId, tokenMap["capabilities"]),
                )
            result[HashBytes(hashBytes)] =
                TokenEntry(
                    principal = principal,
                    expiresAt = parseExpiresAt(tokenId, tokenMap),
                    profile = parseProfile(tokenId, tokenMap["profile"]),
                )
        }

        logger.info("Loaded {} bearer token(s) from '{}'", result.size, filePath)
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

    /**
     * Validates that [tokenMap] contains only recognized keys, so a typo (e.g. `scopes:` instead
     * of `scope:`) fails startup instead of silently leaving the token unrestricted.
     */
    private fun validateEntryKeys(
        tokenId: String,
        tokenMap: Map<*, *>,
    ) {
        val unknownKeys = tokenMap.keys.map { it.toString() }.filter { it !in ALLOWED_ENTRY_KEYS }
        if (unknownKeys.isNotEmpty()) {
            throw IllegalArgumentException(
                "API token file: token '$tokenId' has unknown key(s): ${unknownKeys.joinToString(", ")}. " +
                    "Allowed keys: ${ALLOWED_ENTRY_KEYS.joinToString(", ")}.",
            )
        }
    }

    /** Parses the `scope` block from the token map via [ScopeClaimParser], failing startup on malformed shapes. */
    private fun parseScope(
        tokenId: String,
        rawScope: Any?,
    ): AccessScope =
        try {
            ScopeClaimParser.parse(rawScope)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("API token file: token '$tokenId' ${e.message}")
        }

    /**
     * Parses the entry's `kind`: absent (or null) is [PrincipalKind.SERVICE]; a string is parsed by
     * [PrincipalKind.parse] (trimmed, case-insensitive); anything else fails startup, like an unknown capability.
     */
    private fun parseKind(
        tokenId: String,
        rawKind: Any?,
    ): PrincipalKind {
        if (rawKind == null) return PrincipalKind.SERVICE
        val text =
            rawKind as? String
                ?: throw IllegalArgumentException(
                    "API token file: token '$tokenId' kind must be a string, got: ${rawKind::class.simpleName}.",
                )
        return PrincipalKind.parse(text)
            ?: throw IllegalArgumentException(
                "API token file: token '$tokenId' has unknown kind '$text'. " +
                    "Valid values: ${PrincipalKind.entries.joinToString(", ") { it.wire }}.",
            )
    }

    /**
     * Parses the entry's `profile`: absent (or null) is no restriction; the only valid value is `director`
     * (trimmed, case-insensitive), which confines the credential to the director path. Anything else fails startup.
     */
    private fun parseProfile(
        tokenId: String,
        rawProfile: Any?,
    ): String? {
        if (rawProfile == null) return null
        val text = (rawProfile as? String)?.trim()
        if (text == null || !text.equals(DIRECTOR_CREDENTIAL_PROFILE, ignoreCase = true)) {
            throw IllegalArgumentException(
                "API token file: token '$tokenId' has unknown profile '$rawProfile'. " +
                    "Valid values: $DIRECTOR_CREDENTIAL_PROFILE.",
            )
        }
        return DIRECTOR_CREDENTIAL_PROFILE
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
                            "Valid values: ${ApiCapability.validValues()}.",
                    )
                }
            }.toSet()
    }

    /**
     * One loaded token-file entry: the [principal] it resolves to, its [expiresAt] (null: never expires), and its
     * credential [profile] (`director`, or null for no restriction).
     */
    data class TokenEntry(
        val principal: Principal,
        val expiresAt: Instant?,
        val profile: String? = null,
    )

    companion object {
        /** Recognized keys for a single token entry; anything else fails startup (see D6). */
        private val ALLOWED_ENTRY_KEYS =
            setOf("id", "description", "token_sha256", "expires_at", "scope", "capabilities", "kind", "profile")
    }
}
