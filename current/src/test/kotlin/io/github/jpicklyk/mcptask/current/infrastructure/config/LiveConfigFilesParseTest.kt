package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Guards the two LIVE config files against a bad edit that would otherwise ship undetected until
 * config-sync rejects it or the container fails to start:
 * - the global floor, `deploy/global-config/.taskorchestrator/config.yaml`, loaded through
 *   [GlobalConfigFile] exactly as `ServerComposition` does at startup;
 * - the dogfood file, `.taskorchestrator/config.yaml`, parsed through [YamlConfigDocumentParser]
 *   with `warnOnMissingSchemas = false`, exactly as `PerRootConfigService` and
 *   `ConfigService` do.
 *
 * Each file must load WITHOUT a throw/Failed outcome and WITHOUT errors, produce zero warnings, and declare at least
 * one work item schema. The live files are still the 3.x-format files, so they are read in
 * [ParseMode.MIGRATION] (the reading used for stored 3.x versions); the 4.0 fixtures under
 * src/test/resources/config/ are read in [ParseMode.STRICT] and must have zero errors and zero warnings.
 * The golden fixtures under `golden/a1-seatless/fixtures/` are deliberately
 * pinned, seat-less snapshots and are NOT checked here; this test is what covers the live files.
 *
 * The problem-finding helpers take YAML text so negative self-tests can prove the guard is not
 * vacuous.
 */
class LiveConfigFilesParseTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `live global floor config loads with zero warnings`() {
        val path = repoRoot().resolve("deploy/global-config/.taskorchestrator/config.yaml")
        val problems = floorProblems(readUtf8(path), tempDir)
        assertTrue(problems.isEmpty(), "Live global floor config has problems ($path): $problems")
    }

    @Test
    fun `live dogfood config parses with zero warnings`() {
        val path = repoRoot().resolve(".taskorchestrator/config.yaml")
        val problems = dogfoodProblems(readUtf8(path))
        assertTrue(problems.isEmpty(), "Live dogfood config has problems ($path): $problems")
    }

    @Test
    fun `4_0 dogfood fixture parses STRICT with zero errors and zero warnings`() {
        val outcome =
            YamlConfigDocumentParser.parse(
                StrictConfigCases.dogfoodFixture,
                warnOnMissingSchemas = false,
                mode = ParseMode.STRICT
            )

        val parsed = outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("dogfood-4.0.yaml must parse: $outcome")
        assertEquals(emptyList<Any>(), parsed.document.errors, "dogfood-4.0.yaml STRICT errors")
        assertEquals(emptyList<String>(), parsed.document.warnings, "dogfood-4.0.yaml STRICT warnings")
        assertEquals(emptyList<Any>(), parsed.document.warningViolations, "dogfood-4.0.yaml STRICT warning violations")
        assertTrue(parsed.document.workItemSchemas.isNotEmpty(), "dogfood-4.0.yaml must declare schemas")
    }

    @Test
    fun `4_0 floor fixture parses STRICT with zero errors and zero warnings`() {
        val outcome = YamlConfigDocumentParser.parse(StrictConfigCases.floorFixture, warnOnMissingSchemas = false, mode = ParseMode.STRICT)

        val parsed = outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("floor-4.0.yaml must parse: $outcome")
        assertEquals(emptyList<Any>(), parsed.document.errors, "floor-4.0.yaml STRICT errors")
        assertEquals(emptyList<String>(), parsed.document.warnings, "floor-4.0.yaml STRICT warnings")
        assertEquals(emptyList<Any>(), parsed.document.warningViolations, "floor-4.0.yaml STRICT warning violations")
        assertTrue(parsed.document.workItemSchemas.isNotEmpty(), "floor-4.0.yaml must declare schemas")
    }

    @Test
    fun `the STRICT fixture check is not vacuous - a top-level plugin key is an error in STRICT and tolerated in MIGRATION`() {
        val mutated = StrictConfigCases.dogfoodFixture + "\norchestration:\n  mode: workflow\n"

        val strict = YamlConfigDocumentParser.parse(mutated, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        val migration = YamlConfigDocumentParser.parse(mutated, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)

        val strictDoc = (strict as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("must parse: $strict")).document
        val migrationDoc = (migration as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("must parse: $migration")).document
        assertTrue(strictDoc.errors.any { it.path == "/orchestration" }, "STRICT errors: ${strictDoc.errors}")
        assertEquals(emptyList<Any>(), migrationDoc.errors, "MIGRATION errors")
    }

    @Test
    fun `live dogfood config has no MIGRATION errors even though it still carries 3_x plugin keys`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))

        val outcome = YamlConfigDocumentParser.parse(live, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)

        val parsed = outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("live dogfood config must load: $outcome")
        assertEquals(emptyList<Any>(), parsed.document.errors, "live dogfood config MIGRATION errors")
    }

    @Test
    fun `guard detects an unknown top-level key in the dogfood config`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))
        val problems = dogfoodProblems(live + "\nbogus_section: 1\n")
        assertTrue(problems.any { it.contains("bogus_section") }, "an unknown key must be reported: $problems")
    }

    @Test
    fun `guard detects a second enters seat in one phase`() {
        val yaml =
            """
            work_item_schemas:
              demo:
                lifecycle: auto
                seats:
                  - { name: a, phase: work, enters: true }
                  - { name: b, phase: work, enters: true }
                notes:
                  - key: n
                    role: work
                    required: true
                    seat: a
            """.trimIndent()
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false)
        assertTrue(outcome is ConfigDocumentParser.Outcome.Failed, "duplicate enters must fail, got $outcome")
        assertTrue(dogfoodProblems(yaml).isNotEmpty())
    }

    @Test
    fun `guard detects a mutated floor config`() {
        val live = readUtf8(repoRoot().resolve("deploy/global-config/.taskorchestrator/config.yaml"))
        val problems = floorProblems(live + "\nbogus_section: 1\n", tempDir)
        assertTrue(problems.any { it.contains("bogus_section") }, "an unknown key in the floor must be reported: $problems")
    }

    private fun dogfoodProblems(text: String): List<String> =
        when (val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)) {
            is ConfigDocumentParser.Outcome.Failed -> listOf("parse failed: ${outcome.detail}")
            is ConfigDocumentParser.Outcome.Parsed ->
                outcome.document.errors.map { "error ${it.path}: ${it.reason}" } + outcome.document.warnings +
                    (if (outcome.document.workItemSchemas.isEmpty()) listOf("no work_item_schemas loaded") else emptyList())
        }

    private fun floorProblems(
        text: String,
        dir: Path
    ): List<String> {
        val configDir = Files.createDirectories(dir.resolve("floor-${System.nanoTime()}").resolve(".taskorchestrator"))
        val file = configDir.resolve("config.yaml")
        Files.write(file, text.toByteArray(Charsets.UTF_8))
        return try {
            val layer = GlobalConfigFile(file).layer() ?: return listOf("no layer loaded")
            layer.document.errors.map { "error ${it.path}: ${it.reason}" } + layer.document.warnings +
                (if (layer.document.workItemSchemas.isEmpty()) listOf("no work_item_schemas loaded") else emptyList())
        } catch (e: IllegalArgumentException) {
            listOf("load failed: ${e.message}")
        }
    }

    private fun readUtf8(path: Path): String = String(Files.readAllBytes(path), Charsets.UTF_8)

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
