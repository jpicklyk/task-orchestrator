package io.github.jpicklyk.mcptask.current.application.config

import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.infrastructure.config.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.infrastructure.config.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.test.StrictConfigCases
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ConfigValidator]: the structural pass (JSON Schema
 * through [NetworkntConfigStructuralValidator]), the parser findings, and the semantic pass.
 *
 * Oracles (test-plan note): P = v4 plan s3.6 / s2 / s4.4 / U11, B = W3 brief s3.4, CF = config-format W1-W6 / F1-F4,
 * R = RFC 6901 (JSON Pointer). Scenario ids S1..S17 are the test-plan ids.
 *
 * Every defect document has a repaired twin ([io.github.jpicklyk.mcptask.current.test.StrictConfigCase.fixedYaml]) that
 * must be admitted, so a rejection cannot come from an unrelated cause.
 */
class ConfigValidatorTest {
    private fun serverLayer(yaml: String): ConfigLayer {
        val parsed =
            YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = ParseMode.MIGRATION)
        check(parsed is ConfigDocumentParser.Outcome.Parsed) { "server-layer fixture must parse: $parsed" }
        return ConfigLayer(parsed.document, "server-fp", ConfigSource.GLOBAL)
    }

    private fun validator(server: ConfigLayer? = null) =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { server })

    private fun validate(
        yaml: String,
        scope: ConfigScope = ConfigScope.ROOT,
        server: ConfigLayer? = null
    ): ConfigValidationResult = validator(server).validate(yaml, YamlConfigDocumentParser, scope)

    private fun ConfigValidationResult.errorPaths(): List<String> = errors.map { it.path }

    private fun ConfigValidationResult.hasErrorAt(
        pointer: String,
        within: Boolean
    ): Boolean = errors.any { it.path == pointer || (within && it.path.startsWith("$pointer/")) }

    // ---------------------------------------------------------------------------------------------
    // S1 - the 4.0 fixtures are clean under STRICT
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 the dogfood 4_0 fixture validates at ROOT scope with zero errors and zero warnings`() {
        val result = validate(StrictConfigCases.dogfoodFixture, ConfigScope.ROOT, serverLayer(StrictConfigCases.floorFixture))

        assertEquals(emptyList(), result.errors, "dogfood-4.0.yaml must have no errors")
        assertEquals(emptyList(), result.warnings, "dogfood-4.0.yaml must have no warnings")
        assertTrue(result.isValid)
    }

    @Test
    fun `S1 the floor 4_0 fixture validates at SERVER scope with zero errors and zero warnings`() {
        val result = validate(StrictConfigCases.floorFixture, ConfigScope.SERVER)

        assertEquals(emptyList(), result.errors, "floor-4.0.yaml must have no errors")
        assertEquals(emptyList(), result.warnings, "floor-4.0.yaml must have no warnings")
        assertTrue(result.isValid)
    }

    @Test
    fun `S1 the fixture checks are not vacuous - an unknown top-level key added to either fixture is rejected`() {
        val dogfood =
            validate(
                StrictConfigCases.dogfoodFixture + "\nbogus_section: 1\n",
                ConfigScope.ROOT,
                serverLayer(StrictConfigCases.floorFixture)
            )
        val floor = validate(StrictConfigCases.floorFixture + "\nbogus_section: 1\n", ConfigScope.SERVER)

        assertTrue("/bogus_section" in dogfood.errorPaths(), "dogfood: ${dogfood.errors}")
        assertTrue("/bogus_section" in floor.errorPaths(), "floor: ${floor.errors}")
    }

    // ---------------------------------------------------------------------------------------------
    // S3 / S4 - each former soft skip is an error at its pointer; the repaired twin is admitted
    // ---------------------------------------------------------------------------------------------

    @TestFactory
    fun `S3 S4 each defect is rejected at its JSON Pointer and its repaired twin is admitted`(): List<DynamicTest> =
        StrictConfigCases.ROOT_CASES.map { case ->
            dynamicTest(case.name) {
                val broken = validate(case.yaml)
                assertFalse(broken.isValid, "the defect document must be rejected: ${case.yaml}")
                assertTrue(
                    broken.hasErrorAt(case.errorPath, case.withinEntry),
                    "expected an error at ${case.errorPath}${if (case.withinEntry) " (or beneath it)" else ""}, got ${broken.errors}"
                )

                val fixed = validate(case.fixedYaml)
                assertEquals(emptyList(), fixed.errors, "the repaired twin must be admitted: ${case.fixedYaml}")
            }
        }

    @Test
    fun `S4 a role Work error names the bad value in its reason`() {
        val case = StrictConfigCases.ROOT_CASES.single { it.name == "note role Work (wrong case)" }

        val result = validate(case.yaml)

        val error = result.errors.single { it.path == case.errorPath }
        assertTrue(error.reason.contains("Work"), "the reason should name the offending value: ${error.reason}")
    }

    @Test
    fun `S4 maxHolders 2 in the server registry is an error at the maxHolders pointer, 1 is admitted`() {
        val broken = validate(StrictConfigCases.SERVER_MAX_HOLDERS_YAML, ConfigScope.SERVER)
        val fixed = validate(StrictConfigCases.SERVER_MAX_HOLDERS_FIXED_YAML, ConfigScope.SERVER)

        assertTrue(StrictConfigCases.SERVER_MAX_HOLDERS_PATH in broken.errorPaths(), "errors: ${broken.errors}")
        assertEquals(emptyList(), fixed.errors)
    }

    @Test
    fun `S4 a document with two defects reports both, and the error list is identical across runs`() {
        val yaml = "schema_resolution: legacy\norchestration:\n  mode: workflow\n" + StrictConfigCases.VALID_ROOT_YAML

        val runs = (1..3).map { validate(yaml) }

        assertTrue("/schema_resolution" in runs[0].errorPaths(), "errors: ${runs[0].errors}")
        assertTrue("/orchestration" in runs[0].errorPaths(), "errors: ${runs[0].errors}")
        assertEquals(runs[0].errors, runs[1].errors, "error order and content must be stable across runs")
        assertEquals(runs[0].errors, runs[2].errors, "error order and content must be stable across runs")
    }

    // ---------------------------------------------------------------------------------------------
    // S5 - ROOT scope rejects server-only sections; SERVER scope accepts them
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S5 a ROOT document with a resources section is rejected at the resources key`() {
        val result = validate("resources:\n  db-cred:\n    description: \"shared\"\n" + StrictConfigCases.VALID_ROOT_YAML)

        assertTrue("/resources" in result.errorPaths(), "errors: ${result.errors}")
    }

    @Test
    fun `S5 the same resources section is admitted at SERVER scope - the ROOT rejection is scope-driven`() {
        val result = validate("resources:\n  db-cred:\n    description: \"shared\"\n", ConfigScope.SERVER)

        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `S5 work_item_schemas together with note_schemas is rejected, each alone is admitted`() {
        val both = validate("work_item_schemas: {}\nnote_schemas: {}\n")
        val onlyWork = validate("work_item_schemas: {}\n")
        val onlyNote = validate("note_schemas: {}\n")

        assertFalse(both.isValid, "both schema sections together must be rejected")
        assertTrue(
            both.errorPaths().any { it == "/note_schemas" || it == "/work_item_schemas" },
            "the conflict is reported at one of the two top-level keys: ${both.errors}"
        )
        assertEquals(emptyList(), onlyWork.errors)
        assertEquals(emptyList(), onlyNote.errors)
    }

    // ---------------------------------------------------------------------------------------------
    // S6 - default_traits resolve against the per-root document plus the server layer
    // ---------------------------------------------------------------------------------------------

    private val ghostTraitSchema = "work_item_schemas:\n  x:\n    default_traits: [ghost]\n    notes: []\n"
    private val ghostTraitDefinition =
        "traits:\n  ghost:\n    notes:\n      - key: ghost-note\n        role: work\n" +
            "        required: false\n"

    @Test
    fun `S6 a default_traits entry naming an unknown trait is an error at its list index`() {
        val result = validate(ghostTraitSchema)

        assertTrue("/work_item_schemas/x/default_traits/0" in result.errorPaths(), "errors: ${result.errors}")
    }

    @Test
    fun `S6 the error index follows the position - only the unknown entry is flagged`() {
        val yaml = "work_item_schemas:\n  x:\n    default_traits: [local, ghost]\n    notes: []\n" + "traits:\n  local:\n    notes: []\n"

        val result = validate(yaml)

        assertTrue("/work_item_schemas/x/default_traits/1" in result.errorPaths(), "errors: ${result.errors}")
        assertFalse(
            "/work_item_schemas/x/default_traits/0" in result.errorPaths(),
            "the declared trait must not be flagged: ${result.errors}"
        )
    }

    @Test
    fun `S6 a trait defined in the per-root document itself resolves`() {
        val result = validate(ghostTraitSchema + ghostTraitDefinition)

        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `S6 a trait defined only in the server layer resolves`() {
        val result = validate(ghostTraitSchema, ConfigScope.ROOT, serverLayer(ghostTraitDefinition))

        assertEquals(emptyList(), result.errors)
    }

    // ---------------------------------------------------------------------------------------------
    // S7 - trait resources keys must be declared in the server registry
    // ---------------------------------------------------------------------------------------------

    private val registryYaml = "resources:\n  db-cred:\n    description: \"shared credential\"\n"

    @Test
    fun `S7 a trait resource key missing from the server registry is an error at the resource entry`() {
        val result = validate("traits:\n  needs-db:\n    resources: [db-cred]\n")

        assertTrue(
            result.hasErrorAt("/traits/needs-db/resources/0", within = true),
            "errors: ${result.errors}"
        )
    }

    @Test
    fun `S7 the long form of a resource entry is checked the same way`() {
        val result = validate("traits:\n  needs-db:\n    resources:\n      - key: db-cred\n        mode: advisory\n")

        assertTrue(
            result.hasErrorAt("/traits/needs-db/resources/0", within = true),
            "errors: ${result.errors}"
        )
    }

    @Test
    fun `S7 a trait resource key declared in the server registry is admitted`() {
        val result = validate("traits:\n  needs-db:\n    resources: [db-cred]\n", ConfigScope.ROOT, serverLayer(registryYaml))

        assertEquals(emptyList(), result.errors)
    }

    @Test
    fun `S7 a different key in the server registry does not satisfy the reference`() {
        val result =
            validate(
                "traits:\n  needs-db:\n    resources: [db-cred]\n",
                ConfigScope.ROOT,
                serverLayer("resources:\n  other-key:\n    description: \"x\"\n")
            )

        assertTrue(result.hasErrorAt("/traits/needs-db/resources/0", within = true), "errors: ${result.errors}")
    }

    // ---------------------------------------------------------------------------------------------
    // S8 - advisory findings are warnings, never errors
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S8 a required note no declared seat owns is a warning and the document is valid`() {
        val yaml =
            "work_item_schemas:\n  feature-task:\n    seats:\n      - { name: impl, phase: work, enters: true }\n" +
                "    notes:\n      - key: spec\n        role: work\n        required: true\n"

        val result = validate(yaml)

        assertTrue(result.isValid, "an advisory finding must not invalidate: ${result.errors}")
        assertEquals(emptyList(), result.errors)
        assertTrue(
            result.warnings.any { (it.path + " " + it.reason).let { text -> text.contains("feature-task") && text.contains("spec") } },
            "a warning must name the schema and the note: ${result.warnings}"
        )
    }

    @Test
    fun `S8 a resource key declared by three traits is a fan-out warning, not an error`() {
        val yaml =
            "resources:\n  shared-key:\n    description: \"shared\"\n" +
                "traits:\n  trait-a:\n    resources: [shared-key]\n  trait-b:\n    resources: [shared-key]\n  trait-c:\n" +
                "    resources: [shared-key]\n"

        val result = validate(yaml, ConfigScope.SERVER)

        assertEquals(emptyList(), result.errors)
        assertTrue(
            result.warnings.any { (it.path + " " + it.reason).contains("shared-key") },
            "the fan-out must be reported as a warning naming the key: ${result.warnings}"
        )
    }

    // ---------------------------------------------------------------------------------------------
    // S11 / pointer helpers - RFC 6901 escaping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 an invalid entry under a key containing slash and tilde reports the escaped segment`() {
        val result = validate("work_item_schemas:\n  \"a/b~c\":\n    lifecycle: bogus\n    notes: []\n")

        assertTrue(
            result.errors.any { it.path.startsWith("/work_item_schemas/a~1b~0c") },
            "the segment a/b~c must be escaped as a~1b~0c: ${result.errors}"
        )
        assertTrue(result.errors.none { it.path.contains("a/b") }, "no raw unescaped segment may appear: ${result.errors}")
    }

    @Test
    fun `S11 escape applies tilde before slash per RFC 6901`() {
        assertEquals("a~1b~0c", ConfigValidator.escape("a/b~c"))
        assertEquals("~01", ConfigValidator.escape("~1"), "a literal ~1 must become ~01, not be re-read as an escaped slash")
        assertEquals("~0~1", ConfigValidator.escape("~/"))
        assertEquals("plain-segment_9", ConfigValidator.escape("plain-segment_9"))
    }

    @Test
    fun `S11 pointer joins escaped segments under a leading slash`() {
        assertEquals("/work_item_schemas/a~1b~0c/notes/0", ConfigValidator.pointer("work_item_schemas", "a/b~c", "notes", "0"))
        assertEquals("/x", ConfigValidator.pointer("x"))
    }

    // ---------------------------------------------------------------------------------------------
    // S17 - empty, comment-only and empty-mapping documents
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S17 an empty document is valid at both scopes`() {
        for (scope in listOf(ConfigScope.ROOT, ConfigScope.SERVER)) {
            val result = validate("", scope)
            assertEquals(emptyList(), result.errors, "scope $scope")
            assertTrue(result.isValid)
        }
    }

    @Test
    fun `S17 a comment-only document is valid`() {
        assertEquals(emptyList(), validate("# nothing configured yet\n# still nothing\n").errors)
    }

    @Test
    fun `S17 an empty mapping is valid`() {
        assertEquals(emptyList(), validate("{}\n").errors)
    }

    // ---------------------------------------------------------------------------------------------
    // extensions: (D5) - must be a mapping, contents opaque, exact key spelling
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S2 extensions with a nested tool namespace is admitted and its contents are never validated`() {
        val yaml =
            "extensions:\n  claude-code:\n    orchestration:\n      mode: workflow\n" +
                "  some-other-tool:\n    work_item_schemas: 5\n    deeply:\n      nested: [1, 2, {a: b}]\n" +
                StrictConfigCases.VALID_ROOT_YAML

        val result = validate(yaml)

        assertEquals(emptyList(), result.errors, "extension contents are opaque to the server")
    }

    @Test
    fun `probe extensions absent and extensions as an empty mapping are both admitted`() {
        assertEquals(emptyList(), validate(StrictConfigCases.VALID_ROOT_YAML).errors)
        assertEquals(emptyList(), validate("extensions: {}\n" + StrictConfigCases.VALID_ROOT_YAML).errors)
    }

    @Test
    fun `probe extensions as null, a scalar, a string or a list is an error at the extensions key`() {
        for (value in listOf("~", "5", "\"text\"", "[a, b]", "true")) {
            val result = validate("extensions: $value\n" + StrictConfigCases.VALID_ROOT_YAML)
            assertTrue("/extensions" in result.errorPaths(), "extensions: $value must be rejected at /extensions, got ${result.errors}")
        }
    }

    @Test
    fun `probe the key spelling is exact - Extensions with a capital E is an unknown top-level key`() {
        val result = validate("Extensions: {}\n" + StrictConfigCases.VALID_ROOT_YAML)

        assertTrue("/Extensions" in result.errorPaths(), "errors: ${result.errors}")
    }

    @Test
    fun `probe extensions is admitted at SERVER scope as well`() {
        val result = validate("extensions:\n  claude-code:\n    anything: true\n", ConfigScope.SERVER)

        assertEquals(emptyList(), result.errors)
    }
}
