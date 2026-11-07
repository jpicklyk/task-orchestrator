package io.github.jpicklyk.mcptask.current.infrastructure.identity.bearer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Independent test-author coverage for item 8847d8fa (S1-S8): the bearer token file is read with strict
 * YAML (a duplicate key at any depth fails startup), only an ABSENT `scope` key is unrestricted (every
 * present-but-empty form fails startup naming the token, the key path and the remedy `delete the key`),
 * and startup errors never carry a token digest or a source snippet.
 *
 * Oracle: the frozen `task-scope` clauses D1, D2, D4 (AC1, AC2, AC4) and the `test-plan` scenarios S1-S8, plus
 * the SnakeYAML `isAllowDuplicateKeys = false` contract (a duplicate key is a hard parse error whose problem
 * mark is the SECOND occurrence). Nothing here is derived from the implementation, and no file under
 * `src/main` was opened: the system under test is built per test from the dispatch declarations
 * (`BearerTokenStore(filePath).loadWithEntries()`, `ApiAuthConfigLoader(envResolver).load()`).
 *
 * Fixture trick: every entry starts with its `token_sha256` line, so a SnakeYAML context snippet (which shows
 * the line at the mapping start) would carry the digest if the error text were taken from the exception message.
 * That is the fixture condition that makes the "no digest" assertions fail without the fix.
 */
class BearerTokenStoreStrictYamlTest {
    @TempDir
    lateinit var tempDir: Path

    private val u1 = "11111111-1111-1111-1111-111111111111"

    private fun sha256Hex(input: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun writeYaml(
        content: String,
        name: String = "tokens.yaml"
    ): String {
        val file = tempDir.resolve(name).toFile()
        file.writeText(content)
        return file.absolutePath
    }

    /** A one-entry token file; [entryLines] are appended inside the entry, already indented by four spaces. */
    private fun tokenFile(
        id: String,
        hash: String,
        entryLines: String = "",
        lineEnding: String = "\n"
    ): String {
        val body =
            buildString {
                append("version: 1\n")
                append("tokens:\n")
                append("  - token_sha256: \"$hash\"\n")
                append("    id: $id\n")
                if (entryLines.isNotEmpty()) append(entryLines.trimEnd('\n')).append('\n')
                append("    capabilities:\n")
                append("      - read\n")
            }
        return if (lineEnding == "\n") body else body.replace("\n", lineEnding)
    }

    private fun env(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    private fun lineOf(
        text: String,
        needle: String,
        occurrence: Int
    ): Int {
        var seen = 0
        text.lines().forEachIndexed { index, line ->
            if (line.trim().startsWith(needle)) {
                seen++
                if (seen == occurrence) return index + 1
            }
        }
        throw AssertionError("occurrence $occurrence of '$needle' not found")
    }

    private fun failureOf(path: String): IllegalArgumentException =
        assertThrows<IllegalArgumentException> {
            BearerTokenStore(path).loadWithEntries()
        }

    // ---------------------------------------------------------------------
    // S1-S3: duplicate keys at any depth
    // ---------------------------------------------------------------------

    @Test
    fun `S1 a scope key twice in one entry fails startup naming the key and the second line, never the digest`() {
        val hash = sha256Hex("s1")
        val yaml =
            tokenFile(
                "dup-scope",
                hash,
                "    scope:\n      root_ids:\n        - \"$u1\"\n    scope: ~\n"
            )
        val ex = failureOf(writeYaml(yaml))
        val message = ex.message.orEmpty()

        assertTrue(message.contains("duplicate key scope"), "S1: message must name the duplicate key. Got: $message")
        val secondScopeLine = lineOf(yaml, "scope:", 2)
        assertTrue(
            message.contains("line $secondScopeLine"),
            "S1: message must carry the line of the duplicate ($secondScopeLine). Got: $message"
        )
        assertFalse(message.contains(hash), "S1: the token digest must never reach the startup error. Got: $message")
        assertFalse(message.contains(hash.take(32)), "S1: no 32-hex prefix of the digest either. Got: $message")
    }

    @Test
    fun `S1 probe - the same duplicate in a CRLF file fails the same way`() {
        val hash = sha256Hex("s1-crlf")
        val yaml =
            tokenFile(
                "dup-scope-crlf",
                hash,
                "    scope:\n      root_ids:\n        - \"$u1\"\n    scope: ~\n",
                lineEnding = "\r\n"
            )
        val message = failureOf(writeYaml(yaml)).message.orEmpty()

        assertTrue(message.contains("duplicate key scope"), "S1 CRLF: message must name the duplicate key. Got: $message")
        assertFalse(message.contains(hash), "S1 CRLF: no digest. Got: $message")
    }

    @Test
    fun `S2 root_ids twice inside scope fails startup naming root_ids`() {
        val hash = sha256Hex("s2")
        val yaml =
            tokenFile(
                "dup-root-ids",
                hash,
                "    scope:\n      root_ids:\n        - \"$u1\"\n      root_ids: ~\n"
            )
        val message = failureOf(writeYaml(yaml)).message.orEmpty()

        assertTrue(message.contains("duplicate key root_ids"), "S2: message must name root_ids. Got: $message")
        assertFalse(message.contains(hash), "S2: no digest. Got: $message")
    }

    @Test
    fun `S2 probe - tags_include twice inside scope fails startup too`() {
        val hash = sha256Hex("s2-tags")
        val yaml =
            tokenFile(
                "dup-tags",
                hash,
                "    scope:\n      tags_include:\n        - a\n      tags_include:\n        - b\n"
            )
        val message = failureOf(writeYaml(yaml)).message.orEmpty()

        assertTrue(message.contains("duplicate key tags_include"), "S2 probe: message must name tags_include. Got: $message")
    }

    @Test
    fun `S3 a top-level tokens key twice fails startup`() {
        val hash = sha256Hex("s3")
        val yaml =
            """
            version: 1
            tokens:
              - token_sha256: "$hash"
                id: first
            tokens:
              - token_sha256: "$hash"
                id: second
            """.trimIndent()
        val message = failureOf(writeYaml(yaml)).message.orEmpty()

        assertTrue(message.contains("duplicate key tokens"), "S3: message must name tokens. Got: $message")
        assertFalse(message.contains(hash), "S3: no digest. Got: $message")
    }

    // ---------------------------------------------------------------------
    // S4: every present-but-empty scope form fails startup
    // ---------------------------------------------------------------------

    @Test
    fun `S4 every present-but-empty scope form fails startup naming token key path and the remedy`() {
        // label to (YAML under the entry, expected key path in the message)
        val cases =
            listOf(
                Triple("scope-tilde", "    scope: ~\n", "scope"),
                Triple("scope-bare", "    scope:\n", "scope"),
                Triple("scope-empty-map", "    scope: {}\n", "scope"),
                Triple("root-ids-null", "    scope:\n      root_ids: null\n", "scope.root_ids"),
                Triple("root-ids-empty", "    scope:\n      root_ids: []\n", "scope.root_ids"),
                Triple("tags-null", "    scope:\n      tags_include: null\n", "scope.tags_include"),
                Triple("tags-empty", "    scope:\n      tags_include: []\n", "scope.tags_include")
            )
        for ((label, lines, path) in cases) {
            val id = "s4-$label"
            val file = writeYaml(tokenFile(id, sha256Hex(id), lines), "tokens-$label.yaml")
            val message = failureOf(file).message.orEmpty()

            assertTrue(message.contains(id), "S4[$label]: message must name the token id. Got: $message")
            assertTrue(message.contains(path), "S4[$label]: message must name the key path '$path'. Got: $message")
            assertTrue(message.contains("delete the key"), "S4[$label]: message must give the remedy 'delete the key'. Got: $message")
        }
    }

    @Test
    fun `S4 probe - a non-empty companion key does not rescue an empty root_ids list`() {
        val id = "s4-mixed"
        val file = writeYaml(tokenFile(id, sha256Hex(id), "    scope:\n      root_ids: []\n      tags_include:\n        - a\n"))
        val message = failureOf(file).message.orEmpty()

        assertTrue(message.contains("scope.root_ids"), "S4 probe: empty root_ids is still rejected. Got: $message")
    }

    // ---------------------------------------------------------------------
    // S5: absent vs present-and-valid
    // ---------------------------------------------------------------------

    @Test
    fun `S5 an absent scope key is unrestricted`() {
        val file = writeYaml(tokenFile("s5-absent", sha256Hex("s5-absent")))
        val scope =
            BearerTokenStore(file)
                .loadWithEntries()
                .values
                .single()
                .principal.scope

        assertNull(scope.rootIds, "S5: absent scope -> no root restriction")
        assertTrue(scope.tagsInclude.isEmpty(), "S5: absent scope -> no tag restriction")
        assertTrue(scope.isUnrestricted)
    }

    @Test
    fun `S5 root_ids alone restricts roots and leaves tags unrestricted`() {
        val file = writeYaml(tokenFile("s5-roots", sha256Hex("s5-roots"), "    scope:\n      root_ids:\n        - \"$u1\"\n"))
        val scope =
            BearerTokenStore(file)
                .loadWithEntries()
                .values
                .single()
                .principal.scope

        assertEquals(setOf(UUID.fromString(u1)), scope.rootIds)
        assertTrue(scope.tagsInclude.isEmpty(), "S5: no tags_include key -> no tag restriction")
    }

    @Test
    fun `S5 tags_include alone restricts tags and leaves roots unrestricted`() {
        val file = writeYaml(tokenFile("s5-tags", sha256Hex("s5-tags"), "    scope:\n      tags_include:\n        - a\n"))
        val scope =
            BearerTokenStore(file)
                .loadWithEntries()
                .values
                .single()
                .principal.scope

        assertNull(scope.rootIds, "S5: no root_ids key -> no root restriction")
        assertEquals(setOf("a"), scope.tagsInclude)
    }

    // ---------------------------------------------------------------------
    // S6: unknown keys, hex-looking keys are redacted
    // ---------------------------------------------------------------------

    @Test
    fun `S6 an unknown entry key that is a hex digest is redacted from the startup error`() {
        val hash = sha256Hex("s6")
        val misplaced = sha256Hex("s6-misplaced")
        val file = writeYaml(tokenFile("s6-hex", hash, "    $misplaced: read\n"))
        val message = failureOf(file).message.orEmpty()

        assertFalse(message.contains(misplaced), "S6: the hex-looking key must not be echoed. Got: $message")
        assertFalse(message.contains(misplaced.take(32)), "S6: nor a 32-hex prefix of it. Got: $message")
        assertTrue(message.contains("redacted"), "S6: the error must say the key was redacted. Got: $message")
    }

    @Test
    fun `S6 probe - hex-looking keys of 32 64 and 65 characters are redacted, upper case included`() {
        val cases =
            mapOf(
                "h32" to "a" + "0b1c2d3e4f".repeat(4).take(31),
                "h64" to "a" + "0b1c2d3e4f".repeat(7).take(63),
                "h65" to "a" + "0b1c2d3e4f".repeat(7).take(64),
                "h64-upper" to ("A" + "0B1C2D3E4F".repeat(7).take(63))
            )
        for ((label, key) in cases) {
            val file = writeYaml(tokenFile("s6-$label", sha256Hex("s6-$label"), "    $key: read\n"), "tokens-$label.yaml")
            val message = failureOf(file).message.orEmpty()

            assertFalse(message.contains(key), "S6[$label] (${key.length} chars): key must be redacted. Got: $message")
            assertTrue(message.contains("redacted"), "S6[$label]: must say redacted. Got: $message")
        }
    }

    @Test
    fun `S6 probe - a 31 character hex-looking key is below the redaction floor and is still echoed`() {
        val key = "a" + "0b1c2d3e4f".repeat(4).take(30)
        assertEquals(31, key.length)
        val file = writeYaml(tokenFile("s6-h31", sha256Hex("s6-h31"), "    $key: read\n"))
        val message = failureOf(file).message.orEmpty()

        assertTrue(message.contains(key), "S6: a key shorter than 32 hex characters is echoed so the operator can find it. Got: $message")
    }

    @Test
    fun `S6 a mistyped scopes key is still echoed so the operator can see the typo`() {
        val file = writeYaml(tokenFile("s6-typo", sha256Hex("s6-typo"), "    scopes:\n      root_ids:\n        - \"$u1\"\n"))
        val message = failureOf(file).message.orEmpty()

        assertTrue(message.contains("scopes"), "S6: a non-hex unknown key is still named. Got: $message")
    }

    @Test
    fun `S6 probe - Scope with a capital S is an unknown key, not the scope key`() {
        val file = writeYaml(tokenFile("s6-case", sha256Hex("s6-case"), "    Scope:\n      root_ids:\n        - \"$u1\"\n"))
        val message = failureOf(file).message.orEmpty()

        assertTrue(message.contains("Scope"), "S6: key matching is case-sensitive, 'Scope' is echoed as unknown. Got: $message")
    }

    // ---------------------------------------------------------------------
    // S7: parse errors name a line, never file content
    // ---------------------------------------------------------------------

    @Test
    fun `S7 an unterminated quote on the digest line fails with a line number and no digest`() {
        val hash = sha256Hex("s7")
        val yaml =
            """
            version: 1
            tokens:
              - token_sha256: "$hash
                id: s7-unterminated
                capabilities:
                  - read
            """.trimIndent()
        val message = failureOf(writeYaml(yaml)).message.orEmpty()

        assertFalse(message.contains(hash), "S7: the digest must not reach the startup error. Got: $message")
        assertFalse(message.contains(hash.take(32)), "S7: nor a 32-hex prefix. Got: $message")
        assertTrue(Regex("line \\d+").containsMatchIn(message), "S7: the error must carry a line number. Got: $message")
    }

    // ---------------------------------------------------------------------
    // S8: the production entry point routes through the same loader
    // ---------------------------------------------------------------------

    @Test
    fun `S8 ApiAuthConfigLoader load fails with the duplicate-key error for the S1 file`() {
        val hash = sha256Hex("s8")
        val yaml =
            tokenFile(
                "s8-dup",
                hash,
                "    scope:\n      root_ids:\n        - \"$u1\"\n    scope: ~\n"
            )
        val path = writeYaml(yaml)
        val loader =
            ApiAuthConfigLoader(
                env(
                    "API_ENABLED" to "true",
                    "API_AUTH_MODE" to "bearer",
                    "API_TOKENS_PATH" to path
                )
            )

        val message = assertThrows<IllegalArgumentException> { loader.load() }.message.orEmpty()

        assertTrue(message.contains("duplicate key scope"), "S8: loader must surface the duplicate key. Got: $message")
        assertFalse(message.contains(hash), "S8: no digest through the loader either. Got: $message")
    }

    @Test
    fun `S8 ApiAuthConfigLoader load fails for a present-but-empty scope and names the token`() {
        val id = "s8-empty"
        val path = writeYaml(tokenFile(id, sha256Hex(id), "    scope: {}\n"))
        val loader =
            ApiAuthConfigLoader(
                env(
                    "API_ENABLED" to "true",
                    "API_AUTH_MODE" to "bearer",
                    "API_TOKENS_PATH" to path
                )
            )

        val message = assertThrows<IllegalArgumentException> { loader.load() }.message.orEmpty()

        assertTrue(message.contains(id) && message.contains("delete the key"), "S8: Got: $message")
    }
}
