package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.ActorAuthenticationConfig
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.VerifierConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test-author suite for item b1addc2b -- "Fail closed on unparseable global config and
 * invalid actor_authentication values". Covers test-plan scenarios S2, S5, S7, S8, S10, S11, S12,
 * S15 plus the config-parsing adversarial probes, exercising the two global-config YAML loaders
 * ([YamlActorAuthenticationConfigService], [YamlNoteSchemaService] / [YamlWorkItemSchemaService])
 * directly. Composition-level coverage (build() over a broken global config, the startup WARN) is
 * in `io.github.jpicklyk.mcptask.current.interfaces.mcp.GlobalConfigStartupFailClosedTest`.
 *
 * Oracle source: the item's frozen `diagnosis` note (decisions D1, D2, D4) and `test-plan` note
 * (queue phase), written before this fix's implementation existed -- never this file's own reading
 * of the fixed source. All scenarios here are EXISTING-SURFACE per the test-plan: every type and
 * method touched ([YamlActorAuthenticationConfigService], [YamlNoteSchemaService],
 * [ActorAuthenticationConfig], [VerifierConfig], [DegradedModePolicy]) was already public before
 * this fix -- only the loaders' behavior on bad or absent input changes, from a defaulted,
 * warning-only fallback to a thrown [IllegalArgumentException] naming the config path. A plain
 * revert of the fix therefore yields behavioral red directly; no narrowest-revert recipe is needed.
 *
 * SHA-256 fingerprint oracle: computed independently via [MessageDigest] (see
 * [sha256HexIndependent]) -- never by calling the production `sha256Hex` helper the fix's
 * fingerprinting depends on, so this test's oracle cannot inherit a bug from the code it checks.
 */
class GlobalConfigFailClosedTest {
    @TempDir
    lateinit var tempDir: Path

    private fun writeConfig(
        subdir: String,
        content: String
    ): Path {
        val dir = tempDir.resolve(subdir).resolve(".taskorchestrator")
        Files.createDirectories(dir)
        val file = dir.resolve("config.yaml")
        Files.writeString(file, content)
        return file
    }

    private fun sha256HexIndependent(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    // -------------------------------------------------------------------------
    // S2 -- fingerprint + schema stay pinned to first-read bytes; a new instance sees a rewrite. D4
    // -------------------------------------------------------------------------

    @Test
    fun `S2 fingerprint and schema stay pinned to the bytes read at first access, a new instance sees the rewrite`() {
        val file =
            writeConfig(
                "s2",
                """
                work_item_schemas:
                  type-a:
                    lifecycle: auto
                    notes:
                      - key: spec-a
                        role: queue
                        required: true
                """.trimIndent()
            )
        val service = YamlNoteSchemaService(file)

        val fingerprintBefore = service.getConfigFingerprint()
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), fingerprintBefore)
        val schemaBefore = service.getSchemaForType("type-a")
        assertTrue(schemaBefore != null, "type-a schema must resolve before the rewrite")
        assertEquals(1, schemaBefore.notes.size)
        assertEquals("spec-a", schemaBefore.notes[0].key)

        Files.writeString(
            file,
            """
            work_item_schemas:
              type-b:
                lifecycle: auto
                notes:
                  - key: spec-b
                    role: queue
                    required: true
            """.trimIndent()
        )

        // Same, already-constructed instance: still the FIRST bytes it ever read (`by lazy`).
        assertEquals(fingerprintBefore, service.getConfigFingerprint())
        val schemaStillPinned = service.getSchemaForType("type-a")
        assertTrue(schemaStillPinned != null, "a live instance must stay pinned to its first-read schema")
        assertEquals(1, schemaStillPinned.notes.size)
        assertEquals("spec-a", schemaStillPinned.notes[0].key)
        assertNull(service.getSchemaForType("type-b"), "a live instance must not see the rewrite")

        // A NEW instance over the same path reflects the rewritten bytes.
        val serviceAfterRewrite = YamlNoteSchemaService(file)
        val fingerprintAfter = serviceAfterRewrite.getConfigFingerprint()
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), fingerprintAfter)
        assertNotEquals(fingerprintBefore, fingerprintAfter)
        assertNull(serviceAfterRewrite.getSchemaForType("type-a"))
        val schemaB = serviceAfterRewrite.getSchemaForType("type-b")
        assertTrue(schemaB != null, "a new instance must see the rewritten type-b schema")
        assertEquals("spec-b", schemaB.notes[0].key)
    }

    // -------------------------------------------------------------------------
    // S5 -- unparseable YAML throws IAE naming the path, from BOTH loaders. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S5 pure garbage YAML throws IAE naming the config path from both loaders`() {
        val file = writeConfig("s5-garbage", "{{{{invalid yaml!!!")

        val exActor = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(exActor.message?.contains(file.toString()) == true, "Expected the config path in: ${exActor.message}")

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlNoteSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true, "Expected the config path in: ${exSchema.message}")
    }

    @Test
    fun `S5 a valid reject block followed by a YAML syntax error throws IAE naming the config path from both loaders`() {
        // Mirrors diagnosis R1's reproduction: a well-formed, security-relevant
        // actor_authentication block plus an unrelated syntax error elsewhere in the same file.
        val file =
            writeConfig(
                "s5-trailing-syntax-error",
                """
                actor_authentication:
                  degraded_mode_policy: reject
                  verifier:
                    type: jwks
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                traits: [
                """.trimIndent()
            )

        val exActor = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(exActor.message?.contains(file.toString()) == true, "Expected the config path in: ${exActor.message}")

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlNoteSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true, "Expected the config path in: ${exSchema.message}")
    }

    // -------------------------------------------------------------------------
    // S7 -- IAE naming the offending value, for each fatal-but-well-formed-YAML case. D2
    // -------------------------------------------------------------------------

    @Test
    fun `S7 degraded_mode_policy banana throws IAE naming the value`() {
        val file =
            writeConfig(
                "s7-policy-banana",
                """
                actor_authentication:
                  degraded_mode_policy: banana
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("banana") == true, "Expected 'banana' in: ${ex.message}")
    }

    @Test
    fun `S7 verifier type magic-unicorn throws IAE naming the value`() {
        val file =
            writeConfig(
                "s7-type-magic-unicorn",
                """
                actor_authentication:
                  verifier:
                    type: magic-unicorn
                """.trimIndent()
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("magic-unicorn") == true, "Expected 'magic-unicorn' in: ${ex.message}")
    }

    @Test
    fun `S7 verifier as a bare scalar (not a mapping) throws IAE naming the value`() {
        val file = writeConfig("s7-verifier-scalar", "actor_authentication:\n  verifier: jwks")
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("jwks") == true, "Expected 'jwks' in: ${ex.message}")
        assertTrue(ex.message?.contains("mapping") == true, "Expected 'mapping' in: ${ex.message}")
    }

    @Test
    fun `S7 verifier type jwks with only issuer configured (no key source) throws IAE`() {
        val file =
            writeConfig(
                "s7-jwks-issuer-only",
                """
                actor_authentication:
                  verifier:
                    type: jwks
                    issuer: "https://accounts.example.com"
                """.trimIndent()
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("requires one of") == true, "Expected 'requires one of' in: ${ex.message}")
    }

    @Test
    fun `S7 actor_authentication as a bare scalar (not a mapping) throws IAE naming the value`() {
        val file = writeConfig("s7-actor-auth-scalar", "actor_authentication: reject")
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("reject") == true, "Expected 'reject' in: ${ex.message}")
        assertTrue(ex.message?.contains("mapping") == true, "Expected 'mapping' in: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S8 -- fatal even with a valid env DEGRADED_MODE_POLICY override. D2
    // -------------------------------------------------------------------------

    @Test
    fun `S8 invalid YAML degraded_mode_policy throws IAE even with a valid env override`() {
        val file =
            writeConfig(
                "s8",
                """
                actor_authentication:
                  degraded_mode_policy: banana
                  verifier:
                    type: noop
                """.trimIndent()
            )
        val service =
            YamlActorAuthenticationConfigService(
                file,
                envResolver = { name -> if (name == "DEGRADED_MODE_POLICY") "reject" else null }
            )
        val ex = assertFailsWith<IllegalArgumentException> { service.getConfig() }
        assertTrue(ex.message?.contains("banana") == true, "Expected 'banana' in: ${ex.message}")
    }

    // -------------------------------------------------------------------------
    // S10 -- absent-content states (empty / comment-only) keep coded defaults, no throw. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S10 empty config file yields defaults with no throw, and a fingerprint over its (empty) bytes`() {
        val file = writeConfig("s10-empty", "")

        val actorConfig = YamlActorAuthenticationConfigService(file).getConfig()
        assertEquals(ActorAuthenticationConfig(), actorConfig)

        val schemaService = YamlNoteSchemaService(file)
        assertTrue(schemaService.getLoadWarnings().isEmpty())
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), schemaService.getConfigFingerprint())
    }

    @Test
    fun `S10 comment-only config file yields defaults with no throw, and a fingerprint over its bytes`() {
        val content = "# nothing but a comment\n# another comment line\n"
        val file = writeConfig("s10-comment", content)

        val actorConfig = YamlActorAuthenticationConfigService(file).getConfig()
        assertEquals(ActorAuthenticationConfig(), actorConfig)

        val schemaService = YamlNoteSchemaService(file)
        assertTrue(schemaService.getLoadWarnings().isEmpty())
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), schemaService.getConfigFingerprint())
    }

    // -------------------------------------------------------------------------
    // S11 -- a non-mapping YAML root throws IAE naming the path, from BOTH loaders. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S11 a list-rooted YAML document throws IAE naming the path from both loaders`() {
        val file = writeConfig("s11-list", "- a\n- b")

        val exActor = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(exActor.message?.contains(file.toString()) == true)

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlNoteSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true)
    }

    @Test
    fun `S11 a scalar-rooted YAML document throws IAE naming the path from both loaders`() {
        val file = writeConfig("s11-scalar", "hello")

        val exActor = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(exActor.message?.contains(file.toString()) == true)

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlNoteSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true)
    }

    // -------------------------------------------------------------------------
    // S12 -- an explicit YAML null on a present key is a legitimate "nothing configured" state,
    // distinct from a malformed value; it must NOT throw. D2
    // -------------------------------------------------------------------------

    @Test
    fun `S12 explicit null actor_authentication section yields defaults`() {
        val file = writeConfig("s12-actor-null", "actor_authentication: null")
        assertEquals(ActorAuthenticationConfig(), YamlActorAuthenticationConfigService(file).getConfig())
    }

    @Test
    fun `S12 explicit null verifier yields defaults`() {
        val file = writeConfig("s12-verifier-null", "actor_authentication:\n  verifier: null")
        assertEquals(ActorAuthenticationConfig(), YamlActorAuthenticationConfigService(file).getConfig())
    }

    @Test
    fun `S12 explicit null degraded_mode_policy yields ACCEPT_CACHED`() {
        val file =
            writeConfig(
                "s12-policy-null",
                "actor_authentication:\n  degraded_mode_policy: null\n  verifier:\n    type: noop"
            )
        assertEquals(
            DegradedModePolicy.ACCEPT_CACHED,
            YamlActorAuthenticationConfigService(file).getConfig().degradedModePolicy
        )
    }

    @Test
    fun `S12 explicit null verifier type yields Noop`() {
        val file = writeConfig("s12-type-null", "actor_authentication:\n  verifier:\n    type: null")
        assertEquals(VerifierConfig.Noop, YamlActorAuthenticationConfigService(file).getConfig().verifier)
    }

    // -------------------------------------------------------------------------
    // S15 -- case/underscore variants of a valid degraded_mode_policy value parse without throwing.
    // -------------------------------------------------------------------------

    @Test
    fun `S15 degraded_mode_policy REJECT (uppercase) parses without throwing`() {
        val file =
            writeConfig(
                "s15-upper",
                "actor_authentication:\n  degraded_mode_policy: REJECT\n  verifier:\n    type: noop"
            )
        val service = YamlActorAuthenticationConfigService(file)
        assertEquals(DegradedModePolicy.REJECT, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    @Test
    fun `S15 degraded_mode_policy accept_cached (underscore variant) parses without throwing`() {
        val file =
            writeConfig(
                "s15-underscore",
                "actor_authentication:\n  degraded_mode_policy: accept_cached\n  verifier:\n    type: noop"
            )
        val service = YamlActorAuthenticationConfigService(file)
        assertEquals(DegradedModePolicy.ACCEPT_CACHED, service.getConfig().degradedModePolicy)
        assertTrue(service.getWarnings().isEmpty())
    }

    // -------------------------------------------------------------------------
    // Adversarial probes (test-author skill Sec.6). Every probe attempted is recorded here, in
    // the manifest, including clean (no-finding) results.
    // -------------------------------------------------------------------------

    @Test
    fun `probe degraded_mode_policy near-miss 'rejected' is not recognized and throws IAE`() {
        val file =
            writeConfig(
                "probe-rejected",
                "actor_authentication:\n  degraded_mode_policy: rejected\n  verifier:\n    type: noop"
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("rejected") == true, "Expected 'rejected' in: ${ex.message}")
    }

    @Test
    fun `probe degraded_mode_policy with a trailing space is not trimmed and throws IAE`() {
        val file =
            writeConfig(
                "probe-trailing-space",
                "actor_authentication:\n  degraded_mode_policy: \"reject \"\n  verifier:\n    type: noop"
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("reject ") == true, "Expected 'reject ' (with trailing space) in: ${ex.message}")
    }

    @Test
    fun `probe empty-string degraded_mode_policy is distinct from absent and throws IAE`() {
        val file =
            writeConfig(
                "probe-empty-policy",
                "actor_authentication:\n  degraded_mode_policy: \"\"\n  verifier:\n    type: noop"
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("Unknown") == true, "Expected 'Unknown' in: ${ex.message}")
        assertTrue(ex.message?.contains(file.toString()) == true)
    }

    @Test
    fun `probe empty-string verifier type is distinct from absent and throws IAE`() {
        val file = writeConfig("probe-empty-type", "actor_authentication:\n  verifier:\n    type: \"\"")
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("Unknown") == true, "Expected 'Unknown' in: ${ex.message}")
        assertTrue(ex.message?.contains(file.toString()) == true)
    }

    @Test
    fun `probe YAML-boolean degraded_mode_policy (not a string) throws IAE`() {
        val file =
            writeConfig(
                "probe-bool-policy",
                "actor_authentication:\n  degraded_mode_policy: true\n  verifier:\n    type: noop"
            )
        val ex = assertFailsWith<IllegalArgumentException> { YamlActorAuthenticationConfigService(file).getConfig() }
        assertTrue(ex.message?.contains("must be a string") == true, "Expected 'must be a string' in: ${ex.message}")
    }

    @Test
    fun `probe verifier type JWKS (uppercase) is treated case-insensitively and resolves without throwing`() {
        val file =
            writeConfig(
                "probe-jwks-upper",
                """
                actor_authentication:
                  verifier:
                    type: JWKS
                    jwks_uri: "https://accounts.example.com/.well-known/jwks.json"
                    algorithms:
                      - RS256
                """.trimIndent()
            )
        val verifier = YamlActorAuthenticationConfigService(file).getConfig().verifier
        assertTrue(verifier is VerifierConfig.Jwks, "expected an uppercase 'JWKS' type to resolve to a Jwks verifier")
    }

    @Test
    fun `probe fingerprint hashes the exact file bytes including CRLF endings, and is idempotent across repeated calls`() {
        // Plain (non-triple-quoted) string literal so the \r\n escapes are real CR-LF bytes, not
        // literal backslashes, confirming the fingerprint hashes raw bytes rather than a
        // line-ending-normalized re-encoding of the content.
        val content = "note_schemas:\r\n  default:\r\n    - key: x\r\n      role: queue\r\n      required: false\r\n"
        val file = writeConfig("probe-crlf", content)
        val expected = sha256HexIndependent(Files.readAllBytes(file))

        val service = YamlNoteSchemaService(file)
        val first = service.getConfigFingerprint()
        val second = service.getConfigFingerprint()

        assertEquals(expected, first)
        assertEquals(first, second, "fingerprint must be idempotent across repeated calls on the same instance")
    }

    // Probe: encoded/UNC/alternate-separator forms of the config PATH itself, and duplicate-entry
    // handling, are N/A here -- configPath is an operator-supplied Path (AGENT_CONFIG_DIR), never
    // parsed from untrusted request input, so there is no attacker-controlled path-encoding surface
    // to probe. Recorded in test-manifest per the probe catalog's N/A convention.
}
