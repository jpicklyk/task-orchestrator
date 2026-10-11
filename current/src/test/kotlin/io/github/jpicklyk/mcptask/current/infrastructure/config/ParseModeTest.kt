package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ParseMode] on [YamlConfigDocumentParser].
 *
 * Oracle P (task-scope build item 3): extraction is identical in both modes; the mode only classifies a finding.
 * STRICT turns each former soft skip into an error with an RFC 6901 path, MIGRATION keeps it a warning at the same
 * path. Advisory findings (resource fan-out, a required note no seat owns) are warnings in both. Seat structure errors
 * F1-F4 are fatal in both, with a path. Scenarios S9, S16 and the decision D1 (reading a stored version never fails on
 * findings).
 */
class ParseModeTest {
    private fun parsed(
        yaml: String,
        mode: ParseMode
    ): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "expected Parsed for $mode: $outcome").document
    }

    private fun failed(
        yaml: String,
        mode: ParseMode
    ): ConfigDocumentParser.Outcome.Failed =
        assertIs<ConfigDocumentParser.Outcome.Failed>(
            YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode),
            "expected Failed for $mode"
        )

    private fun ConfigDocument.errorPaths() = errors.map { it.path }

    private fun ConfigDocument.warningPaths() = warningViolations.map { it.path }

    // ---------------------------------------------------------------------------------------------
    // S9 - the same defect: STRICT error, MIGRATION warning at the same path
    // ---------------------------------------------------------------------------------------------

    @TestFactory
    fun `S9 a former soft skip is an error in STRICT and a warning at the same path in MIGRATION`(): List<DynamicTest> =
        StrictConfigCases.ROOT_CASES.filter { it.parserFinding }.map { case ->
            dynamicTest(case.name) {
                val strict = parsed(case.yaml, ParseMode.STRICT)
                val migration = parsed(case.yaml, ParseMode.MIGRATION)

                assertTrue(strict.errors.isNotEmpty(), "STRICT must record an error for: ${case.yaml}")
                assertEquals(emptyList(), migration.errors, "MIGRATION must record no error for: ${case.yaml}")
                assertTrue(
                    strict.errorPaths().any { it == case.errorPath || (case.withinEntry && it.startsWith(case.errorPath + "/")) },
                    "STRICT error paths ${strict.errorPaths()} should include ${case.errorPath}"
                )
                assertTrue(
                    migration.warningPaths().containsAll(strict.errorPaths()),
                    "every STRICT error path ${strict.errorPaths()} must be a MIGRATION warning path ${migration.warningPaths()}"
                )
            }
        }

    @Test
    fun `S9 the server-registry maxHolders 2 finding follows the same rule`() {
        val strict = parsed(StrictConfigCases.SERVER_MAX_HOLDERS_YAML, ParseMode.STRICT)
        val migration = parsed(StrictConfigCases.SERVER_MAX_HOLDERS_YAML, ParseMode.MIGRATION)

        assertTrue(StrictConfigCases.SERVER_MAX_HOLDERS_PATH in strict.errorPaths(), "STRICT errors: ${strict.errors}")
        assertEquals(emptyList(), migration.errors)
        assertTrue(
            StrictConfigCases.SERVER_MAX_HOLDERS_PATH in migration.warningPaths(),
            "MIGRATION warnings: ${migration.warningViolations}"
        )
    }

    @TestFactory
    fun `S9 the repaired twin of every parser-level defect has no STRICT errors`(): List<DynamicTest> =
        StrictConfigCases.ROOT_CASES.filter { it.parserFinding }.map { case ->
            dynamicTest(case.name) {
                val strict = parsed(case.fixedYaml, ParseMode.STRICT)

                assertEquals(emptyList(), strict.errors, "repaired twin: ${case.fixedYaml}")
            }
        }

    @TestFactory
    fun `S9 plugin keys at the top level are accepted without error in MIGRATION so stored 3_x versions keep loading`(): List<DynamicTest> =
        StrictConfigCases.ROOT_CASES.filter { !it.parserFinding }.map { case ->
            dynamicTest(case.name) {
                val migration = parsed(case.yaml, ParseMode.MIGRATION)

                assertEquals(emptyList(), migration.errors, "a stored 3.x document with ${case.errorPath} must load: ${case.yaml}")
                assertTrue(migration.workItemSchemas.containsKey("feature-task"), "the rest of the document must still be extracted")
            }
        }

    @Test
    fun `S9 the default mode of parse is MIGRATION`() {
        val defaulted =
            YamlConfigDocumentParser.parse(
                StrictConfigCases.ROOT_CASES.first { it.parserFinding }.yaml,
                warnOnMissingSchemas = false
            )

        val document = assertIs<ConfigDocumentParser.Outcome.Parsed>(defaulted).document
        assertEquals(emptyList(), document.errors, "a caller that does not choose a mode gets the lenient reading")
    }

    @Test
    fun `S9 extraction is identical in both modes - the mode only classifies`() {
        val yaml = StrictConfigCases.ROOT_CASES.first { it.name == "lifecycle bogus" }.yaml

        val strict = parsed(yaml, ParseMode.STRICT)
        val migration = parsed(yaml, ParseMode.MIGRATION)

        assertEquals(strict.workItemSchemas, migration.workItemSchemas)
        assertEquals(strict.traits, migration.traits)
    }

    // ---------------------------------------------------------------------------------------------
    // Advisory findings stay warnings in both modes
    // ---------------------------------------------------------------------------------------------

    private val unownedNoteYaml =
        "work_item_schemas:\n  bug-fix:\n    seats:\n" +
            "      - { name: implementer, phase: work }\n      - { name: reviewer, phase: review }\n" +
            "    notes:\n      - { key: q1, role: queue, required: true }\n" +
            "      - { key: w1, role: work, required: true, seat: reviewer }\n" +
            "      - { key: w3, role: work, required: true, seat: implementer }\n"

    @Test
    fun `S8 a required note no seat owns is a warning in STRICT and in MIGRATION, never an error`() {
        for (mode in ParseMode.values()) {
            val document = parsed(unownedNoteYaml, mode)

            assertEquals(emptyList(), document.errors, "mode $mode")
            assertTrue(document.warnings.any { it.contains("q1") }, "mode $mode warnings: ${document.warnings}")
            assertTrue(document.warnings.any { it.contains("w1") }, "mode $mode warnings: ${document.warnings}")
            assertTrue(
                document.warnings.none { it.contains("w3") },
                "the properly owned note must not be named, mode $mode: ${document.warnings}"
            )
        }
    }

    @Test
    fun `S8 a resource key declared by three traits is a warning in both modes`() {
        val yaml =
            "resources:\n  shared-key:\n    description: \"shared\"\n" +
                "traits:\n  trait-a:\n    resources: [shared-key]\n  trait-b:\n    resources: [shared-key]\n  trait-c:\n" +
                "    resources: [shared-key]\n"

        for (mode in ParseMode.values()) {
            val document = parsed(yaml, mode)

            assertEquals(emptyList(), document.errors, "mode $mode")
            assertTrue(
                document.warnings.any { it.contains("shared-key") && it.contains("3 traits") },
                "mode $mode warnings: ${document.warnings}"
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // F1-F4 seat structure errors stay fatal in both modes and carry a path
    // ---------------------------------------------------------------------------------------------

    private val seatFatalCases: Map<String, String> =
        mapOf(
            "F1 two enters seats in one phase" to
                "work_item_schemas:\n  bug-fix:\n    seats:\n" +
                "      - { name: a, phase: work, enters: true }\n      - { name: b, phase: work, enters: true }\n",
            "F2 duplicate seat name" to
                "work_item_schemas:\n  bug-fix:\n    seats:\n      - { name: a, phase: work }\n      - { name: a, phase: review }\n",
            "F3 reserved seat name unowned" to
                "work_item_schemas:\n  bug-fix:\n    seats:\n      - { name: unowned, phase: work }\n",
            "F4 after cycle" to
                "work_item_schemas:\n  bug-fix:\n    seats:\n" +
                "      - { name: a, phase: work, after: [b] }\n      - { name: b, phase: work, after: [a] }\n"
        )

    @TestFactory
    fun `F1 to F4 seat structure errors are fatal in both modes with a path inside the offending schema`(): List<DynamicTest> =
        seatFatalCases.flatMap { (name, yaml) ->
            ParseMode.values().map { mode ->
                dynamicTest("$name ($mode)") {
                    val failure = failed(yaml, mode)

                    assertTrue(failure.violations.isNotEmpty(), "a fatal seat error must carry at least one violation: $failure")
                    assertTrue(
                        failure.violations.any {
                            it.path == "/work_item_schemas/bug-fix" ||
                                it.path.startsWith("/work_item_schemas/bug-fix/")
                        },
                        "the violation path must locate the offending schema: ${failure.violations}"
                    )
                }
            }
        }

    // ---------------------------------------------------------------------------------------------
    // S16 - syntax errors and a non-mapping root are parse failures in both modes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S16 a YAML syntax error is Failed in both modes`() {
        for (mode in ParseMode.values()) {
            failed("work_item_schemas: [this is not, a valid: map", mode)
        }
    }

    @Test
    fun `S16 a non-mapping root is Failed in both modes`() {
        for (mode in ParseMode.values()) {
            failed("- a\n- b\n", mode)
            failed("just a scalar\n", mode)
        }
    }

    @Test
    fun `S16 a disallowed YAML tag is Failed in both modes`() {
        for (mode in ParseMode.values()) {
            failed("work_item_schemas: !!java.net.URL [\"http://evil.example\"]", mode)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // D1 - fromRoot is a read path: it never fails on findings
    // ---------------------------------------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    private fun rootOf(yaml: String): Map<String, Any> = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml)

    @Test
    fun `D1 fromRoot of a stored 3_x document with plugin keys and soft-invalid content returns a document, not a failure`() {
        val yaml =
            "orchestration:\n  mode: workflow\nretrospective:\n  mode: dispatch\nactor_attribution:\n  required: true\n" +
                "work_item_schemas:\n  feature-task:\n    lifecycle: bogus\n    notes:\n      - key: spec\n        role: Work\n"

        val document = YamlConfigDocumentParser.fromRoot(rootOf(yaml), warnOnMissingSchemas = false)

        assertEquals(emptyList(), document.errors, "the default reading is MIGRATION: findings are warnings")
        assertTrue(document.workItemSchemas.containsKey("feature-task"), "the schemas still load")
    }

    @Test
    fun `D1 fromRoot with the STRICT mode classifies the same findings as errors`() {
        val yaml = "work_item_schemas:\n  feature-task:\n    lifecycle: bogus\n    notes: []\n"

        val strict = YamlConfigDocumentParser.fromRoot(rootOf(yaml), warnOnMissingSchemas = false, mode = ParseMode.STRICT)

        assertTrue("/work_item_schemas/feature-task/lifecycle" in strict.errorPaths(), "errors: ${strict.errors}")
    }
}
