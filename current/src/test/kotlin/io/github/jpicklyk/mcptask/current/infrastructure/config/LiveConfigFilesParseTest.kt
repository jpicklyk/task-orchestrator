package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.application.config.PerRootConfigSource
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import io.github.jpicklyk.mcptask.current.test.TestConfigResolver
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Guards the two LIVE config files against a bad edit that would otherwise ship undetected until
 * config-sync rejects it or the container fails to start:
 * - the global floor, `deploy/global-config/.taskorchestrator/config.yaml`, loaded through
 *   [GlobalConfigFile] exactly as `ServerComposition` does at startup;
 * - the dogfood file, `.taskorchestrator/config.yaml`, parsed through [YamlConfigDocumentParser]
 *   with `warnOnMissingSchemas = false`, exactly as `PerRootConfigService` and
 *   `ConfigService` do.
 *
 * The floor must load WITHOUT a throw/Failed outcome and WITHOUT errors, produce zero warnings, and declare at least
 * one work item schema. The dogfood file is in the 4.0 extensions shape and is read in [ParseMode.STRICT] with zero
 * errors, zero warnings and zero warning violations (S4); it carries no top-level 3.x plugin key (S5) and passes
 * [ConfigValidator] at ROOT scope against the live floor as the server layer (S6). The live floor declares the
 * `project` and `retrospective-trend` process types (S7), the floor-4.0 fixture mirrors them (S8), and a resolver
 * over the live floor offers both on a fresh server (S9) and, with the dogfood config pushed per-root, offers the
 * sorted distinct union of both layers (S10). The 4.0 fixtures under src/test/resources/config/ are read in
 * [ParseMode.STRICT] and must have zero errors and zero warnings.
 * The golden fixtures under `golden/a1-seatless/fixtures/` are deliberately
 * pinned, seat-less snapshots and are NOT checked here; this test is what covers the live files.
 *
 * Oracles (task-scope AC5, AC6, AC8 and the frozen test-plan of item 2953cc30): the acceptance criteria and the
 * precedence-table KDoc, never what the loader currently returns.
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
    fun `S4 live dogfood config parses STRICT with zero errors, warnings and warning violations`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))

        val parsed = parseStrict(live, "live dogfood config")

        assertEquals(emptyList<Any>(), parsed.document.errors, "live dogfood STRICT errors")
        assertEquals(emptyList<String>(), parsed.document.warnings, "live dogfood STRICT warnings")
        assertEquals(emptyList<Any>(), parsed.document.warningViolations, "live dogfood STRICT warning violations")
        assertTrue(parsed.document.workItemSchemas.isNotEmpty(), "live dogfood config must declare schemas")
    }

    @Test
    fun `S4 probe - a CRLF copy of the live dogfood config is also clean under STRICT`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml")).replace("\r\n", "\n").replace("\n", "\r\n")

        val parsed = parseStrict(live, "CRLF live dogfood config")

        assertEquals(emptyList<Any>(), parsed.document.errors, "CRLF dogfood STRICT errors")
        assertEquals(emptyList<String>(), parsed.document.warnings, "CRLF dogfood STRICT warnings")
        assertEquals(emptyList<Any>(), parsed.document.warningViolations, "CRLF dogfood STRICT warning violations")
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
    fun `S5 live dogfood config carries no top-level 3_x plugin key and keeps orchestration and retrospective under extensions`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))
        val keys = topLevelKeys(live)

        for (retired in listOf("orchestration", "retrospective", "actor_attribution", "schema_resolution")) {
            assertFalse(retired in keys, "top-level `$retired` is a 3.x plugin key, found top-level keys $keys")
        }
        val extensions = rawRootOf(live)["extensions"] as? Map<*, *> ?: throw AssertionError("live dogfood config must carry `extensions`")
        val claudeCode = extensions["claude-code"] as? Map<*, *> ?: throw AssertionError("extensions must carry `claude-code`: $extensions")
        assertTrue("orchestration" in claudeCode.keys, "extensions.claude-code must carry orchestration: ${claudeCode.keys}")
        assertTrue("retrospective" in claudeCode.keys, "extensions.claude-code must carry retrospective: ${claudeCode.keys}")
    }

    @Test
    fun `S5 is not vacuous - topLevelKeys reports an appended actor_attribution or orchestration key`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))

        val mutated = topLevelKeys(live + "\nactor_attribution:\n  required: true\norchestration:\n  mode: workflow\n")

        assertTrue("actor_attribution" in mutated, "mutated top-level keys: $mutated")
        assertTrue("orchestration" in mutated, "mutated top-level keys: $mutated")
    }

    @Test
    fun `S6 live dogfood config validates at ROOT scope against the live floor with zero errors and zero warnings`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))

        val result = floorValidator().validate(live, YamlConfigDocumentParser, ConfigScope.ROOT)

        assertEquals(emptyList<Any>(), result.errors, "live dogfood ROOT validation errors")
        assertEquals(emptyList<Any>(), result.warnings, "live dogfood ROOT validation warnings")
        assertTrue(result.isValid)
    }

    @Test
    fun `S6 is not vacuous - the same validator rejects the live dogfood config with an unknown top-level key`() {
        val live = readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml"))

        val result = floorValidator().validate(live + "\nbogus_section: 1\n", YamlConfigDocumentParser, ConfigScope.ROOT)

        assertFalse(result.isValid, "an unknown top-level key must be a validation error")
        assertTrue(result.errors.any { it.path.contains("bogus_section") }, "errors: ${result.errors}")
    }

    @Test
    fun `S7 live floor declares project as permanent with no notes and retrospective-trend as auto with no required note`() {
        val document = floorLayer().document

        assertEquals(emptyList<Any>(), document.errors, "live floor errors")
        assertEquals(emptyList<String>(), document.warnings, "live floor warnings")
        val project =
            document.workItemSchemas["project"]
                ?: throw AssertionError("live floor must declare `project`: ${document.workItemSchemas.keys}")
        assertEquals(LifecycleMode.PERMANENT, project.lifecycleMode)
        assertTrue(project.notes.isEmpty(), "project declares no notes: ${project.notes}")
        val trend =
            document.workItemSchemas["retrospective-trend"]
                ?: throw AssertionError("live floor must declare `retrospective-trend`: ${document.workItemSchemas.keys}")
        assertEquals(LifecycleMode.AUTO, trend.lifecycleMode)
        assertTrue(trend.notes.none { it.required }, "retrospective-trend has no required note: ${trend.notes}")
        assertTrue(trend.notes.isEmpty(), "retrospective-trend declares no notes: ${trend.notes}")
        for (existing in listOf("agent-observation", "improvement-proposal", "session-retrospective", "container")) {
            assertTrue(
                existing in document.workItemSchemas.keys,
                "the existing `$existing` type must stay: ${document.workItemSchemas.keys}"
            )
        }
    }

    @Test
    fun `S8 floor-4_0 fixture mirrors the live floor project and retrospective-trend blocks and its type set`() {
        val live = floorLayer().document
        val fixture = parseStrict(StrictConfigCases.floorFixture, "floor-4.0.yaml").document

        assertEquals(live.workItemSchemas.keys, fixture.workItemSchemas.keys, "fixture and live floor declare the same types")
        for (type in listOf("project", "retrospective-trend")) {
            val fromFixture = fixture.workItemSchemas[type] ?: throw AssertionError("floor-4.0.yaml must declare `$type`")
            val fromLive = live.workItemSchemas[type] ?: throw AssertionError("live floor must declare `$type`")
            assertEquals(fromLive.lifecycleMode, fromFixture.lifecycleMode, "$type lifecycle")
            assertEquals(fromLive.notes, fromFixture.notes, "$type notes")
        }
        assertEquals(LifecycleMode.PERMANENT, fixture.workItemSchemas.getValue("project").lifecycleMode)
        assertEquals(LifecycleMode.AUTO, fixture.workItemSchemas.getValue("retrospective-trend").lifecycleMode)
    }

    @Test
    fun `S9 a resolver over the live floor as the only layer offers project and retrospective-trend`(): Unit =
        runBlocking {
            val floor = floorLayer().document
            val resolver = TestConfigResolver.resolver(floor)

            val withoutRoot = resolver.availableTypes(null)
            val unknownRoot = resolver.availableTypes(UUID.randomUUID())

            for (types in listOf(withoutRoot, unknownRoot)) {
                assertTrue("project" in types, "available types: $types")
                assertTrue("retrospective-trend" in types, "available types: $types")
                assertEquals(
                    floor.workItemSchemas.keys
                        .toSortedSet()
                        .toList(),
                    types,
                    "a floor-only server offers exactly the floor types, sorted"
                )
            }
        }

    @Test
    fun `S10 available types over the live dogfood config are the exact-match sorted distinct union with the floor`(): Unit =
        runBlocking {
            val floor = floorLayer().document
            val dogfood = parseStrict(readUtf8(repoRoot().resolve(".taskorchestrator/config.yaml")), "live dogfood config").document
            val pushedRoot = UUID.fromString("ce064d2c-7c03-4eac-ae7d-89a14c2ba273")
            val perRoot =
                object : PerRootConfigSource {
                    override suspend fun layer(rootId: UUID): ConfigLayer? =
                        if (rootId == pushedRoot) ConfigLayer(dogfood, "dogfood-fp", ConfigSource.PER_ROOT) else null
                }
            val resolver = TestConfigResolver.resolver(floor, perRoot)

            val floorOnly = resolver.availableTypes(null)
            val pushed = resolver.availableTypes(pushedRoot)

            for (near in listOf("Project", "PROJECT", "retro-trend", "retrospective-trends", "retrospective_trend")) {
                assertFalse(near in floorOnly, "type keys match exactly, `$near` must not be offered: $floorOnly")
                assertFalse(near in pushed, "type keys match exactly, `$near` must not be offered: $pushed")
            }
            val expected = (floor.workItemSchemas.keys + dogfood.workItemSchemas.keys).toSortedSet().toList()
            assertEquals(expected, pushed, "per-root and server keys: sorted, distinct")
            assertEquals(1, pushed.count { it == "project" }, "project is declared in both layers and offered once")
            assertTrue("feature-task" in pushed, "dogfood type must be offered: $pushed")
            assertTrue("container" in pushed, "floor type must be offered: $pushed")
            assertTrue("retrospective-trend" in pushed, "floor type must be offered: $pushed")
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
        when (val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)) {
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

    private fun parseStrict(
        text: String,
        label: String
    ): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        return outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("$label must parse STRICT: $outcome")
    }

    /** The raw top-level map of [text], read in MIGRATION so a 3.x top-level key is reported instead of failing the parse. */
    private fun rawRootOf(text: String): Map<String, Any> {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        val parsed = outcome as? ConfigDocumentParser.Outcome.Parsed ?: throw AssertionError("config must parse: $outcome")
        return parsed.rawRoot ?: throw AssertionError("config must have a root mapping")
    }

    private fun topLevelKeys(text: String): Set<String> = rawRootOf(text).keys

    private fun floorLayer(): ConfigLayer =
        GlobalConfigFile(repoRoot().resolve("deploy/global-config/.taskorchestrator/config.yaml")).layer()
            ?: throw AssertionError("the live floor must load")

    private fun floorValidator(): ConfigValidator =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { floorLayer() })

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
