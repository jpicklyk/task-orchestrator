package io.github.jpicklyk.mcptask.current.infrastructure.config

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
 * Independent test-author suite for item b1addc2b -- "Fail closed on unparseable global config".
 * Covers test-plan scenarios S2, S5, S10, S11 plus the fingerprint probe, exercising the global-config
 * YAML loader ([YamlWorkItemSchemaService]) directly. Composition-level coverage (build() over a broken global
 * config) is in `io.github.jpicklyk.mcptask.current.interfaces.mcp.GlobalConfigStartupFailClosedTest`.
 *
 * Oracle source: the item's frozen `diagnosis` note (decisions D1, D4) and `test-plan` note
 * (queue phase), written before this fix's implementation existed -- never this file's own reading
 * of the fixed source.
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
        val service = YamlWorkItemSchemaService(file)

        val fingerprintBefore = service.getConfigFingerprint()
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), fingerprintBefore)
        val schemaBefore = service.getAllSchemas()["type-a"]
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
        val schemaStillPinned = service.getAllSchemas()["type-a"]
        assertTrue(schemaStillPinned != null, "a live instance must stay pinned to its first-read schema")
        assertEquals(1, schemaStillPinned.notes.size)
        assertEquals("spec-a", schemaStillPinned.notes[0].key)
        assertNull(service.getAllSchemas()["type-b"], "a live instance must not see the rewrite")

        // A NEW instance over the same path reflects the rewritten bytes.
        val serviceAfterRewrite = YamlWorkItemSchemaService(file)
        val fingerprintAfter = serviceAfterRewrite.getConfigFingerprint()
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), fingerprintAfter)
        assertNotEquals(fingerprintBefore, fingerprintAfter)
        assertNull(serviceAfterRewrite.getAllSchemas()["type-a"])
        val schemaB = serviceAfterRewrite.getAllSchemas()["type-b"]
        assertTrue(schemaB != null, "a new instance must see the rewritten type-b schema")
        assertEquals("spec-b", schemaB.notes[0].key)
    }

    // -------------------------------------------------------------------------
    // S5 -- unparseable YAML throws IAE naming the path. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S5 pure garbage YAML throws IAE naming the config path`() {
        val file = writeConfig("s5-garbage", "{{{{invalid yaml!!!")

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlWorkItemSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true, "Expected the config path in: ${exSchema.message}")
    }

    @Test
    fun `S5 a valid block followed by a YAML syntax error throws IAE naming the config path`() {
        // Mirrors diagnosis R1's reproduction: a well-formed block plus an unrelated syntax error elsewhere in the
        // same file.
        val file =
            writeConfig(
                "s5-trailing-syntax-error",
                """
                note_limits:
                  mode: reject
                traits: [
                """.trimIndent()
            )

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlWorkItemSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true, "Expected the config path in: ${exSchema.message}")
    }

    // -------------------------------------------------------------------------
    // S10 -- absent-content states (empty / comment-only) keep coded defaults, no throw. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S10 empty config file yields defaults with no throw, and a fingerprint over its (empty) bytes`() {
        val file = writeConfig("s10-empty", "")

        val schemaService = YamlWorkItemSchemaService(file)
        assertTrue(schemaService.getLoadWarnings().isEmpty())
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), schemaService.getConfigFingerprint())
    }

    @Test
    fun `S10 comment-only config file yields defaults with no throw, and a fingerprint over its bytes`() {
        val content = "# nothing but a comment\n# another comment line\n"
        val file = writeConfig("s10-comment", content)

        val schemaService = YamlWorkItemSchemaService(file)
        assertTrue(schemaService.getLoadWarnings().isEmpty())
        assertEquals(sha256HexIndependent(Files.readAllBytes(file)), schemaService.getConfigFingerprint())
    }

    // -------------------------------------------------------------------------
    // S11 -- a non-mapping YAML root throws IAE naming the path. D1
    // -------------------------------------------------------------------------

    @Test
    fun `S11 a list-rooted YAML document throws IAE naming the path`() {
        val file = writeConfig("s11-list", "- a\n- b")

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlWorkItemSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true)
    }

    @Test
    fun `S11 a scalar-rooted YAML document throws IAE naming the path`() {
        val file = writeConfig("s11-scalar", "hello")

        val exSchema = assertFailsWith<IllegalArgumentException> { YamlWorkItemSchemaService(file).getLoadWarnings() }
        assertTrue(exSchema.message?.contains(file.toString()) == true)
    }

    @Test
    fun `probe fingerprint normalizes CRLF to LF before hashing, and is idempotent across repeated calls`() {
        // Plain (non-triple-quoted) string literal so the \r\n escapes are real CR-LF bytes, not
        // literal backslashes. As of the config-fingerprint BOM/CRLF normalization (14067aab), the
        // fingerprint is computed over the CRLF-normalized (-> LF) text, not the raw bytes, so this
        // must equal the fingerprint of the LF-equivalent content, NOT a raw hash of the CRLF bytes.
        val content = "note_schemas:\r\n  default:\r\n    - key: x\r\n      role: queue\r\n      required: false\r\n"
        val file = writeConfig("probe-crlf", content)
        val lfEquivalent = content.replace("\r\n", "\n")
        val expected = sha256HexIndependent(lfEquivalent.toByteArray(Charsets.UTF_8))
        val rawHash = sha256HexIndependent(Files.readAllBytes(file))

        val service = YamlWorkItemSchemaService(file)
        val first = service.getConfigFingerprint()
        val second = service.getConfigFingerprint()

        assertEquals(expected, first, "fingerprint must be computed over the CRLF-normalized text")
        assertNotEquals(rawHash, first, "fingerprint must NOT be a raw hash of the CRLF bytes")
        assertEquals(first, second, "fingerprint must be idempotent across repeated calls on the same instance")
    }
}
