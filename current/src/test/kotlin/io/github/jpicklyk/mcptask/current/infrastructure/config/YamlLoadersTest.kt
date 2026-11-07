package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.infrastructure.sqlite.upgrade.TypeBackfillStep
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.error.MarkedYAMLException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Independent test-author coverage for item 8847d8fa (S14, S15, S17): the one YAML factory, the config
 * parser's STRICT-vs-MIGRATION duplicate-key behaviour, the shipped config files, and the tolerant read paths.
 *
 * Oracle: frozen `task-scope` D1 (strict rejects duplicate keys, tolerant never does; MIGRATION reads and the
 * global file reader never reject stored config), D4 (`describe`: `<problem> at line <n>`, 32+ hex runs replaced
 * by `<hex redacted>`, any non-YAML exception reduced to its simple class name) and the SnakeYAML
 * `isAllowDuplicateKeys` contract (default last-wins; `false` throws a MarkedYAMLException whose problem is
 * `found duplicate key <name>`). Built from the dispatch declarations only; no `src/main` file was opened.
 */
class YamlLoadersTest {
    @TempDir
    lateinit var tempDir: Path

    private val duplicateRootKey =
        """
        work_item_schemas:
          demo:
            lifecycle: auto
        work_item_schemas:
          other:
            lifecycle: auto
        """.trimIndent()

    // ---------------------------------------------------------------------
    // the factory itself (D1)
    // ---------------------------------------------------------------------

    @Test
    fun `strict rejects a duplicate key and names it, tolerant keeps the last value`() {
        val text = "a: 1\nb: 2\na: 3\n"

        val ex = assertThrows<MarkedYAMLException> { YamlLoaders.strict().load<Any>(text) }
        assertTrue(ex.problem.orEmpty().contains("duplicate key a"), "strict problem: ${ex.problem}")

        val tolerant = YamlLoaders.tolerant().load<Map<String, Any>>(text)
        assertEquals(3, tolerant["a"], "tolerant is last-wins")
        assertEquals(2, tolerant["b"])
    }

    @Test
    fun `strict rejects a duplicate key nested three levels deep`() {
        val text = "x:\n  y:\n    z:\n      k: 1\n      k: 2\n"

        assertThrows<MarkedYAMLException> { YamlLoaders.strict().load<Any>(text) }
    }

    @Test
    fun `strict and tolerant return a new instance on every call because Yaml is not thread-safe`() {
        assertNotSame(YamlLoaders.strict(), YamlLoaders.strict())
        assertNotSame(YamlLoaders.tolerant(), YamlLoaders.tolerant())
    }

    @Test
    fun `strict does not construct arbitrary types from a tag, the loader is safe`() {
        val text = "x: !!java.net.URL [\"http://example.invalid/\"]\n"

        assertThrows<Exception> { YamlLoaders.strict().load<Any>(text) }
    }

    @Test
    fun `describe reports the duplicate key and a line number, with no source snippet`() {
        val secret = "ab".repeat(32)
        val text = "token_sha256: \"$secret\"\nname: one\nname: two\n"
        val ex = assertThrows<MarkedYAMLException> { YamlLoaders.strict().load<Any>(text) }

        val described = YamlLoaders.describe(ex)

        assertTrue(described.contains("duplicate key name"), "described: $described")
        assertTrue(Regex("line \\d+").containsMatchIn(described), "described: $described")
        assertFalse(described.contains(secret), "described must not carry file content: $described")
    }

    @Test
    fun `describe replaces a 32-plus hex run inside the YAML problem text`() {
        val hex = "c0ffee".repeat(8)
        val ex = assertThrows<MarkedYAMLException> { YamlLoaders.strict().load<Any>("$hex: 1\n$hex: 2\n") }

        val described = YamlLoaders.describe(ex)

        assertFalse(described.contains(hex), "a hex-looking duplicate key must be redacted: $described")
        assertFalse(described.contains(hex.take(32)), "nor a 32-hex prefix of it: $described")
        assertTrue(described.contains("<hex redacted>"), "described: $described")
    }

    @Test
    fun `describe reduces any other exception to its simple class name`() {
        val described = YamlLoaders.describe(IllegalStateException("secret-detail-ab12"))

        assertTrue(described.contains("IllegalStateException"), "described: $described")
        assertFalse(described.contains("secret-detail-ab12"), "exception text must not leak: $described")
    }

    // ---------------------------------------------------------------------
    // S14: config document parser, STRICT vs MIGRATION
    // ---------------------------------------------------------------------

    @Test
    fun `S14 STRICT parse of a duplicate-key document is Failed and names the key`() {
        val outcome = YamlConfigDocumentParser.parse(duplicateRootKey, warnOnMissingSchemas = false, mode = ParseMode.STRICT)

        val failed = outcome as? ConfigDocumentParser.Outcome.Failed ?: throw AssertionError("STRICT must fail, got $outcome")
        assertTrue(failed.detail.contains("work_item_schemas"), "detail must name the duplicate key: ${failed.detail}")
    }

    @Test
    fun `S14 MIGRATION parse of the same document is Parsed and the last value wins`() {
        val outcome = YamlConfigDocumentParser.parse(duplicateRootKey, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)

        val parsed = outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("MIGRATION must parse, got $outcome")
        val schemas = parsed.rawRoot?.get("work_item_schemas") as? Map<*, *> ?: throw AssertionError("rawRoot: ${parsed.rawRoot}")
        assertEquals(setOf("other"), schemas.keys, "last occurrence wins in the tolerant read")
    }

    @Test
    fun `S14 the default mode is MIGRATION and never rejects stored config`() {
        val outcome = YamlConfigDocumentParser.parse(duplicateRootKey, warnOnMissingSchemas = false)

        assertTrue(outcome is ConfigDocumentParser.Outcome.Parsed, "default (MIGRATION) must not reject: $outcome")
    }

    @Test
    fun `S14 ConfigValidator reports a duplicate-key document as invalid with a parse error naming the key`() {
        val result = ConfigValidator().validate(duplicateRootKey, YamlConfigDocumentParser)

        assertFalse(result.isValid, "a duplicate key must not validate")
        assertTrue(result.errors.isNotEmpty())
        assertTrue(
            result.errors.any { it.reason.contains("work_item_schemas") || it.path.contains("work_item_schemas") },
            "an error must name the duplicate key: ${result.errors}"
        )
    }

    @Test
    fun `S14 probe - a document without duplicates still validates the same way in STRICT`() {
        val clean = "work_item_schemas:\n  demo:\n    lifecycle: auto\n"

        val outcome = YamlConfigDocumentParser.parse(clean, warnOnMissingSchemas = false, mode = ParseMode.STRICT)

        assertTrue(outcome is ConfigDocumentParser.Outcome.Parsed, "a clean document must not be rejected by the strict flag: $outcome")
    }

    // ---------------------------------------------------------------------
    // S15: the shipped config files load under the strict factory
    // ---------------------------------------------------------------------

    @Test
    fun `S15 the global floor and the dogfood config both load through strict without a duplicate key`() {
        val files =
            listOf(
                repoRoot().resolve("deploy/global-config/.taskorchestrator/config.yaml"),
                repoRoot().resolve(".taskorchestrator/config.yaml")
            )
        for (file in files) {
            val text = String(Files.readAllBytes(file), Charsets.UTF_8)

            val loaded: Any? = YamlLoaders.strict().load<Any>(text)

            assertTrue(loaded is Map<*, *>, "$file must load as a mapping, got ${loaded?.javaClass}")
        }
    }

    @Test
    fun `S15 is not vacuous - the same dogfood text with one key repeated fails strict`() {
        val text = String(Files.readAllBytes(repoRoot().resolve(".taskorchestrator/config.yaml")), Charsets.UTF_8)

        assertThrows<MarkedYAMLException> { YamlLoaders.strict().load<Any>(text + "\nproject:\n  a: 1\nproject:\n  b: 2\n") }
    }

    // ---------------------------------------------------------------------
    // S17: tolerant read paths never reject a duplicate-key document
    // ---------------------------------------------------------------------

    @Test
    fun `S17 GlobalConfigFile layer on a duplicate-key document does not throw and reads the last value`() {
        val yaml =
            """
            work_item_schemas:
              demo:
                lifecycle: auto
                notes:
                  - key: n
                    role: work
                    required: true
            work_item_schemas:
              other:
                lifecycle: auto
                notes:
                  - key: n
                    role: work
                    required: true
            """.trimIndent()
        val dir = Files.createDirectories(tempDir.resolve("floor").resolve(".taskorchestrator"))
        val file = dir.resolve("config.yaml")
        Files.write(file, yaml.toByteArray(Charsets.UTF_8))

        val layer = GlobalConfigFile(file).layer()

        assertNotNull(layer, "a duplicate-key global file still yields a layer")
    }

    @Test
    fun `S17 TypeBackfillStep layerOf on a duplicate-key document does not throw`() {
        val yaml = "work_item_schemas:\n  demo:\n    lifecycle: auto\nwork_item_schemas:\n  other:\n    lifecycle: auto\n"

        assertDoesNotThrow { TypeBackfillStep.layerOf(yaml) }
    }

    private fun repoRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("claude-plugins")) && Files.isDirectory(dir.resolve("current"))) {
                return dir
            }
            dir = dir.parent
        }
        throw AssertionError("could not locate repo root (looking for a dir containing both 'claude-plugins/' and 'current/')")
    }
}
