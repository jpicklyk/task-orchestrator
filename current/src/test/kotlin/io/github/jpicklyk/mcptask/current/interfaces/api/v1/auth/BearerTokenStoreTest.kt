package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

class BearerTokenStoreTest {
    @TempDir
    lateinit var tempDir: Path

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun writeYaml(
        name: String = "tokens.yaml",
        content: String,
    ): String {
        val file = tempDir.resolve(name).toFile()
        file.writeText(content)
        return file.absolutePath
    }

    private fun validTokenYaml(
        tokenId: String = "test-token",
        rawToken: String = "my-secret-token",
        capabilities: String = "- read",
        expiresAt: String? = null,
        rootIds: String? = null,
    ): String {
        val hash = sha256Hex(rawToken)
        val expiresLine = if (expiresAt != null) "\n    expires_at: \"$expiresAt\"" else ""
        val rootLine = if (rootIds != null) "\n      root_ids: [$rootIds]" else "\n      root_ids: null"
        return """
            version: 1
            tokens:
              - id: $tokenId
                description: "Test token"
                token_sha256: "$hash"
                scope:$rootLine
                  tags_include: []
                capabilities:
                  $capabilities$expiresLine
        """.trimIndent()
    }

    // -------------------------------------------------------------------------
    // Valid load scenarios
    // -------------------------------------------------------------------------

    @Test
    fun `valid file with one token loads correctly`() {
        val path = writeYaml(content = validTokenYaml())
        val store = BearerTokenStore(path)
        val config = store.load()
        assertEquals(1, config.tokens.size, "Should load exactly one token")
    }

    @Test
    fun `valid file with multiple tokens loads all`() {
        val hash1 = sha256Hex("token-alpha")
        val hash2 = sha256Hex("token-beta")
        val yaml =
            """
            version: 1
            tokens:
              - id: alpha
                token_sha256: "$hash1"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
              - id: beta
                token_sha256: "$hash2"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
                  - write-notes
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val config = BearerTokenStore(path).load()
        assertEquals(2, config.tokens.size)
    }

    @Test
    fun `token with all capabilities loads correctly`() {
        val yaml =
            validTokenYaml(
                capabilities =
                    """
                    - read
                      - write-notes
                      - write-items
                      - advance
                      - manage-dependencies
                      - admin
                    """.trimIndent(),
            )
        // Write a cleaner yaml directly
        val hash = sha256Hex("super-token")
        val fullYaml =
            """
            version: 1
            tokens:
              - id: super
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
                  - write-notes
                  - write-items
                  - advance
                  - manage-dependencies
                  - admin
            """.trimIndent()
        val path = writeYaml(content = fullYaml)
        val config = BearerTokenStore(path).load()
        val principal = config.tokens.values.first()
        assertEquals(6, principal.capabilities.size)
        assertTrue(ApiCapability.ADMIN in principal.capabilities)
    }

    @Test
    fun `token with root_ids scope loads correctly`() {
        val hash = sha256Hex("scoped-token")
        val yaml =
            """
            version: 1
            tokens:
              - id: scoped
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val config = BearerTokenStore(path).load()
        val principal = config.tokens.values.first()
        assertNotNull(principal.scope.rootIds)
        assertEquals(1, principal.scope.rootIds!!.size)
    }

    // -------------------------------------------------------------------------
    // Startup validation — fail-fast cases
    // -------------------------------------------------------------------------

    @Test
    fun `missing file throws with descriptive error`() {
        val store = BearerTokenStore("/nonexistent/path/tokens.yaml")
        val ex = assertThrows<IllegalArgumentException> { store.load() }
        assertTrue(ex.message!!.contains("not found"), "Error should mention 'not found': ${ex.message}")
    }

    @Test
    fun `empty file throws`() {
        val path = writeYaml(content = "")
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("empty") || ex.message!!.contains("null") || ex.message!!.lowercase().contains("empty"))
    }

    @Test
    fun `malformed YAML throws`() {
        val path = writeYaml(content = "this: is: invalid: yaml: [[[")
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message != null, "Should throw with a message")
    }

    @Test
    fun `token_sha256 with 63 chars throws`() {
        val shortHash = "a".repeat(63)
        val yaml =
            """
            version: 1
            tokens:
              - id: bad
                token_sha256: "$shortHash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("length") || ex.message!!.contains("64"), "Error: ${ex.message}")
    }

    @Test
    fun `token_sha256 with 65 chars throws`() {
        val longHash = "a".repeat(65)
        val yaml =
            """
            version: 1
            tokens:
              - id: bad
                token_sha256: "$longHash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("length") || ex.message!!.contains("64"), "Error: ${ex.message}")
    }

    @Test
    fun `token_sha256 with non-hex chars throws`() {
        // Uppercase hex — not valid per spec (must be lowercase)
        val upperHex = "A".repeat(64)
        val yaml =
            """
            version: 1
            tokens:
              - id: bad
                token_sha256: "$upperHex"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("invalid") || ex.message!!.contains("character"), "Error: ${ex.message}")
    }

    @Test
    fun `token_sha256 with uppercase chars throws`() {
        val mixedHash = sha256Hex("test").uppercase() // 64 char but uppercase
        val yaml =
            """
            version: 1
            tokens:
              - id: bad
                token_sha256: "$mixedHash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message != null)
    }

    @Test
    fun `duplicate token IDs throws`() {
        val hash1 = sha256Hex("token-one")
        val hash2 = sha256Hex("token-two")
        val yaml =
            """
            version: 1
            tokens:
              - id: duplicate-id
                token_sha256: "$hash1"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
              - id: duplicate-id
                token_sha256: "$hash2"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("duplicate") || ex.message!!.contains("Duplicate"), "Error: ${ex.message}")
    }

    @Test
    fun `unparseable expires_at throws`() {
        val hash = sha256Hex("token")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-expires
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
                expires_at: "not-a-date"
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("expires_at") || ex.message!!.contains("unparseable"), "Error: ${ex.message}")
    }

    @Test
    fun `unknown capability value throws`() {
        val hash = sha256Hex("token")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-cap
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - superpower
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("superpower") || ex.message!!.contains("capability"), "Error: ${ex.message}")
    }

    @Test
    fun `unsupported version throws`() {
        val hash = sha256Hex("token")
        val yaml =
            """
            version: 2
            tokens:
              - id: some-token
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("version") || ex.message!!.contains("unsupported"), "Error: ${ex.message}")
    }

    @Test
    fun `missing token id throws`() {
        val hash = sha256Hex("token")
        val yaml =
            """
            version: 1
            tokens:
              - token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("id") || ex.message!!.contains("missing"), "Error: ${ex.message}")
    }

    @Test
    fun `missing token_sha256 throws`() {
        val yaml =
            """
            version: 1
            tokens:
              - id: some-token
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("token_sha256") || ex.message!!.contains("missing"), "Error: ${ex.message}")
    }

    @Test
    fun `description exceeding 1024 chars throws`() {
        val hash = sha256Hex("token")
        val longDesc = "x".repeat(1025)
        val yaml =
            """
            version: 1
            tokens:
              - id: verbose
                description: "$longDesc"
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("description") || ex.message!!.contains("1024"), "Error: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // Runtime lookup — expiry
    // -------------------------------------------------------------------------

    @Test
    fun `expired token at lookup time returns null`() {
        val rawToken = "expiring-token"
        val hash = sha256Hex(rawToken)
        val pastExpiry = Instant.now().minusSeconds(3600) // already expired
        val yaml =
            """
            version: 1
            tokens:
              - id: expires-soon
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
                expires_at: "${pastExpiry}"
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val store = BearerTokenStore(path)
        val entries = store.loadWithEntries()

        val result = store.lookupInEntries(rawToken, entries)
        assertNull(result, "Expired token should return null at lookup time")
    }

    @Test
    fun `valid non-expired token at lookup time returns principal`() {
        val rawToken = "valid-token-123"
        val hash = sha256Hex(rawToken)
        val futureExpiry = Instant.now().plusSeconds(3600)
        val yaml =
            """
            version: 1
            tokens:
              - id: valid
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
                expires_at: "${futureExpiry}"
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val store = BearerTokenStore(path)
        val entries = store.loadWithEntries()

        val result = store.lookupInEntries(rawToken, entries)
        assertNotNull(result, "Valid non-expired token should return a principal")
        assertEquals("valid", result!!.tokenId)
    }

    @Test
    fun `wrong token returns null`() {
        val rightToken = "correct-secret"
        val hash = sha256Hex(rightToken)
        val yaml =
            """
            version: 1
            tokens:
              - id: correct
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val store = BearerTokenStore(path)
        val entries = store.loadWithEntries()

        val result = store.lookupInEntries("wrong-secret", entries)
        assertNull(result, "Wrong token should return null")
    }
}
