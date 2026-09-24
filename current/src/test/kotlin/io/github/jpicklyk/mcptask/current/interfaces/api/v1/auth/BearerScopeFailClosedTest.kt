package io.github.jpicklyk.mcptask.current.interfaces.api.v1.auth

import io.github.jpicklyk.mcptask.current.infrastructure.config.ApiAuthConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

/**
 * Independent test-author coverage for item 3a837a3b — "Fail closed on malformed scope claims
 * (JWKS and bearer tokens)". This file covers the bearer side (`BearerTokenStore.load()` /
 * `loadWithEntries()`, plus `ApiAuthConfigLoader`); the JWKS side lives in
 * `JwksScopeClaimFailClosedTest.kt`.
 *
 * Oracle provenance: every expected value traces to the `test-plan` note frozen at queue phase
 * (S2, S3, S10-S15, S17, S18 and their listed probes) and the `diagnosis` note's decisions
 * D1-D12, never to what `BearerTokenStore`/`ScopeClaimParser` currently return. `test-plan` marks
 * every scenario here EXISTING-SURFACE (`load`/`loadWithEntries`/`ApiAuthConfigLoader.load` are
 * pre-existing public entry points; the plan explicitly directs never to reference
 * `ScopeClaimParser` by name), so red-proof for all of them is a plain revert of the fix.
 *
 * Per test-author skill §4, this class was authored from the declarations pasted into the dispatch
 * prompt and the existing sibling fixture `BearerTokenStoreTest.kt` (its `writeYaml`/`sha256Hex`
 * pattern is adapted here) — no file under `src/main` was opened.
 */
class BearerScopeFailClosedTest {
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

    private fun envResolver(vararg pairs: Pair<String, String?>): (String) -> String? {
        val map = mapOf(*pairs)
        return { key -> map[key] }
    }

    // -------------------------------------------------------------------------
    // S2 -- happy: a well-formed scope resolves identically via both loaders
    // -------------------------------------------------------------------------

    @Test
    fun `S2 valid scope loads identically via load and loadWithEntries`() {
        val u1 = "11111111-1111-1111-1111-111111111111"
        val hash = sha256Hex("token-s2")
        val yaml =
            """
            version: 1
            tokens:
              - id: scoped-token
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - "$u1"
                  tags_include:
                    - feature
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)

        val principalFromLoad =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertEquals(setOf(UUID.fromString(u1)), principalFromLoad.scope.rootIds, "S2: load() must resolve root_ids")
        assertEquals(setOf("feature"), principalFromLoad.scope.tagsInclude, "S2: load() must resolve tags_include")

        val entries = BearerTokenStore(path).loadWithEntries()
        val principalFromEntries = entries.values.first().principal
        assertEquals(principalFromLoad.scope, principalFromEntries.scope, "S2: load() and loadWithEntries() must agree")
    }

    // -------------------------------------------------------------------------
    // S3 -- happy: every absent/empty form of scope is unrestricted
    // -------------------------------------------------------------------------

    @Test
    fun `S3a token with no scope key is unrestricted`() {
        val hash = sha256Hex("s3a-no-scope")
        val yaml =
            """
            version: 1
            tokens:
              - id: no-scope
                token_sha256: "$hash"
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertNull(principal.scope.rootIds, "S3a: an absent scope key must be unrestricted")
        assertTrue(principal.scope.tagsInclude.isEmpty(), "S3a: an absent scope key must carry no tag constraint")
    }

    @Test
    fun `S3b bare scope key (YAML null) is unrestricted`() {
        val hash = sha256Hex("s3b-bare-scope")
        val yaml =
            """
            version: 1
            tokens:
              - id: bare-scope
                token_sha256: "$hash"
                scope:
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertNull(principal.scope.rootIds, "S3b: a bare (YAML-null) scope key must be unrestricted (D4)")
        assertTrue(principal.scope.tagsInclude.isEmpty(), "S3b: a bare scope key must carry no tag constraint")
    }

    @Test
    fun `S3c empty scope map is unrestricted`() {
        val hash = sha256Hex("s3c-empty-map")
        val yaml =
            """
            version: 1
            tokens:
              - id: empty-scope-map
                token_sha256: "$hash"
                scope: {}
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertNull(principal.scope.rootIds, "S3c: scope: {} must be unrestricted (D5)")
        assertTrue(principal.scope.tagsInclude.isEmpty(), "S3c: scope: {} must carry no tag constraint")
    }

    @Test
    fun `S3d explicit null root_ids with empty tags_include is unrestricted`() {
        val hash = sha256Hex("s3d-null-and-empty")
        val yaml =
            """
            version: 1
            tokens:
              - id: null-and-empty
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertNull(principal.scope.rootIds, "S3d: explicit null root_ids must be unrestricted (D4)")
        assertTrue(principal.scope.tagsInclude.isEmpty(), "S3d: tags_include: [] is the canonical no-constraint form (D3)")
    }

    // -------------------------------------------------------------------------
    // S10 -- failure: malformed root_ids shapes fail startup
    // -------------------------------------------------------------------------

    @Test
    fun `S10 scalar root_ids value fails startup`() {
        val hash = sha256Hex("s10-scalar")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-scalar
                token_sha256: "$hash"
                scope:
                  root_ids: "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("bad-scalar"), "S10: message must name the token id. Got: ${ex.message}")
        assertTrue(ex.message!!.contains("root_ids"), "S10: message must name root_ids. Got: ${ex.message}")
    }

    @Test
    fun `S10 empty root_ids list fails startup`() {
        val hash = sha256Hex("s10-empty")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-empty
                token_sha256: "$hash"
                scope:
                  root_ids: []
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("bad-empty"), "S10: message must name the token id. Got: ${ex.message}")
        assertTrue(
            ex.message!!.contains("root_ids"),
            "S10: message must name root_ids -- [] widens per D2, unlike tags_include. Got: ${ex.message}"
        )
    }

    // -------------------------------------------------------------------------
    // S11 -- failure: malformed tags_include shapes fail startup
    // -------------------------------------------------------------------------

    @Test
    fun `S11 malformed tags_include values fail startup`() {
        val cases =
            listOf(
                "scalar string" to "feature",
                "non-string integer element" to "[123]",
                "non-string boolean element" to "[true]",
                "blank string element" to "[\"\"]",
            )
        for ((label, tagsIncludeYamlValue) in cases) {
            val hash = sha256Hex("s11-$label")
            val yaml =
                """
                version: 1
                tokens:
                  - id: bad-tags
                    token_sha256: "$hash"
                    scope:
                      root_ids: null
                      tags_include: $tagsIncludeYamlValue
                    capabilities:
                      - read
                """.trimIndent()
            val path = writeYaml(content = yaml)
            val ex = assertThrows<IllegalArgumentException>("S11[$label] must fail startup") { BearerTokenStore(path).load() }
            assertTrue(ex.message!!.contains("tags_include"), "S11[$label]: message must name tags_include. Got: ${ex.message}")
        }
    }

    // -------------------------------------------------------------------------
    // S12 -- failure: malformed scope shape (not a map, or an unknown key inside it)
    // -------------------------------------------------------------------------

    @Test
    fun `S12 non-map scope value fails startup naming scope`() {
        val cases = listOf("scalar" to "all", "list" to "[x]")
        for ((label, scopeValue) in cases) {
            val hash = sha256Hex("s12-$label")
            val yaml =
                """
                version: 1
                tokens:
                  - id: bad-scope-shape
                    token_sha256: "$hash"
                    scope: $scopeValue
                    capabilities:
                      - read
                """.trimIndent()
            val path = writeYaml(content = yaml)
            val ex = assertThrows<IllegalArgumentException>("S12[$label]") { BearerTokenStore(path).load() }
            assertTrue(ex.message!!.contains("scope"), "S12[$label]: message must name scope. Got: ${ex.message}")
        }
    }

    @Test
    fun `S12 unknown key inside scope (root_id typo) fails startup`() {
        val u2 = "22222222-2222-2222-2222-222222222222"
        val hash = sha256Hex("s12-typo")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-scope-key
                token_sha256: "$hash"
                scope:
                  root_id:
                    - "$u2"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("root_id"), "S12: message must name the unknown key. Got: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S13 -- failure: unknown entry-level key (scopes typo) fails startup
    // -------------------------------------------------------------------------

    @Test
    fun `S13 unknown entry-level key (scopes typo) fails startup`() {
        val hash = sha256Hex("s13")
        val yaml =
            """
            version: 1
            tokens:
              - id: bad-entry-key
                token_sha256: "$hash"
                scopes:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("scopes"), "S13: message must name the unknown entry key. Got: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S14 -- failure: ApiAuthConfigLoader propagates the bearer file's validation error unchanged
    // -------------------------------------------------------------------------

    @Test
    fun `S14 ApiAuthConfigLoader propagates the bearer file's scope validation failure`() {
        val hash = sha256Hex("s14")
        val yaml =
            """
            version: 1
            tokens:
              - id: s14-bad
                token_sha256: "$hash"
                scope:
                  root_ids: "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val resolver =
            envResolver(
                "API_ENABLED" to "true",
                "API_AUTH_MODE" to "bearer",
                "API_TOKENS_PATH" to path,
            )
        val loader = ApiAuthConfigLoader(resolver)
        val ex = assertThrows<IllegalArgumentException> { loader.load() }
        assertTrue(
            ex.message!!.contains("s14-bad"),
            "S14: ApiAuthConfigLoader must propagate BearerTokenStore's error unchanged. Got: ${ex.message}",
        )
    }

    // -------------------------------------------------------------------------
    // S15 -- failure: a multi-token file names the offending token, not the first one
    // -------------------------------------------------------------------------

    @Test
    fun `S15 second token's malformed scope is named, not the first`() {
        val hash1 = sha256Hex("s15-first")
        val hash2 = sha256Hex("s15-second")
        val yaml =
            """
            version: 1
            tokens:
              - id: first-good
                token_sha256: "$hash1"
                scope:
                  root_ids: null
                  tags_include: []
                capabilities:
                  - read
              - id: second-bad
                token_sha256: "$hash2"
                scope:
                  root_ids: "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(ex.message!!.contains("second-bad"), "S15: error must name the second token's id. Got: ${ex.message}")
        assertTrue(!ex.message!!.contains("first-good"), "S15: error must not name the first (valid) token's id. Got: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S17 -- edge: duplicate root_ids collapse to a single entry
    // -------------------------------------------------------------------------

    @Test
    fun `S17 duplicate root_ids collapse to a single entry (Bearer)`() {
        val u1 = "33333333-3333-3333-3333-333333333333"
        val hash = sha256Hex("s17")
        val yaml =
            """
            version: 1
            tokens:
              - id: dup-root-ids
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - "$u1"
                    - "$u1"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertEquals(setOf(UUID.fromString(u1)), principal.scope.rootIds, "S17: duplicate root_ids must collapse into one entry")
    }

    // -------------------------------------------------------------------------
    // S18 -- edge: uppercase UUID text parses to the same UUID (RFC 4122 §3)
    // -------------------------------------------------------------------------

    @Test
    fun `S18 uppercase UUID text is accepted as the same root id (Bearer)`() {
        val u1 = UUID.randomUUID()
        val upper = u1.toString().uppercase()
        val hash = sha256Hex("s18")
        val yaml =
            """
            version: 1
            tokens:
              - id: upper-root-id
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - "$upper"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertEquals(setOf(u1), principal.scope.rootIds, "S18: an uppercase UUID string must parse to the same UUID")
    }

    // -------------------------------------------------------------------------
    // Adversarial probes (test-author skill §6)
    // -------------------------------------------------------------------------

    @Test
    fun `Probe -- unquoted YAML boolean tag element is rejected`() {
        val hash = sha256Hex("probe-yes-tag")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-yes
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include: [yes]
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("tags_include"),
            "Probe: an unquoted 'yes' parses as a YAML boolean, not a String -- malformed per D3. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- brace-wrapped root_ids string is a scalar, not a list`() {
        val hash = sha256Hex("probe-braces")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-braces
                token_sha256: "$hash"
                scope:
                  root_ids: "{11111111-1111-1111-1111-111111111111}"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("root_ids"),
            "Probe: a quoted brace-wrapped string is a scalar -- malformed per D2. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- root_ids element with leading whitespace is rejected`() {
        val hash = sha256Hex("probe-space")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-space
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - " 11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("root_ids"),
            "Probe: whitespace-padded UUID text must not parse. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- wrong-case key name ROOT_IDS is an unknown key`() {
        val hash = sha256Hex("probe-upper-key")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-upper-key
                token_sha256: "$hash"
                scope:
                  ROOT_IDS:
                    - "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("ROOT_IDS"),
            "Probe: scope keys are case-sensitive; ROOT_IDS is unknown per D5. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- nested list root_ids is rejected`() {
        val hash = sha256Hex("probe-nested")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-nested
                token_sha256: "$hash"
                scope:
                  root_ids:
                    - - "11111111-1111-1111-1111-111111111111"
                  tags_include: []
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("root_ids"),
            "Probe: a nested list element is not a String -- malformed per D2. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- tags_include as a map instead of a list is rejected`() {
        val hash = sha256Hex("probe-map-tags")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-map-tags
                token_sha256: "$hash"
                scope:
                  root_ids: null
                  tags_include:
                    a: b
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val ex = assertThrows<IllegalArgumentException> { BearerTokenStore(path).load() }
        assertTrue(
            ex.message!!.contains("tags_include"),
            "Probe: tags_include must be a list -- a map is malformed per D3. Got: ${ex.message}",
        )
    }

    @Test
    fun `Probe -- bare scope with YAML tilde null is unrestricted`() {
        val hash = sha256Hex("probe-tilde")
        val yaml =
            """
            version: 1
            tokens:
              - id: probe-tilde
                token_sha256: "$hash"
                scope: ~
                capabilities:
                  - read
            """.trimIndent()
        val path = writeYaml(content = yaml)
        val principal =
            BearerTokenStore(path)
                .load()
                .tokens.values
                .first()
        assertNull(principal.scope.rootIds, "Probe: scope: ~ (YAML null) must mean unrestricted, same as an absent scope")
        assertTrue(principal.scope.tagsInclude.isEmpty(), "Probe: scope: ~ must carry no tag constraint")
    }
}
