package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
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
 *   `ProjectConfigPushService` do.
 *
 * Each file must load without a throw/Failed outcome, produce zero warnings, and declare at least
 * one work item schema. The golden fixtures under `golden/a1-seatless/fixtures/` are deliberately
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
        when (val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false)) {
            is ConfigDocumentParser.Outcome.Failed -> listOf("parse failed: ${outcome.detail}")
            is ConfigDocumentParser.Outcome.Parsed ->
                outcome.document.warnings +
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
            layer.document.warnings +
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
