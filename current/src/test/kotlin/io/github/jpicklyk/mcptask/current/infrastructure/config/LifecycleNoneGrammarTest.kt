package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.application.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.application.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.domain.model.ConfigScope
import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 9e5707b3 / Q31b) for the `lifecycle: none` grammar: scenarios S1 and S10 of the
 * frozen test-plan and the grammar probes.
 *
 * Oracles (frozen task-scope Build 1 and plan 3.12): `lifecycle: none` is a valid value of `work_item_schemas.<type>.lifecycle`
 * under STRICT validation (the JSON Schema enum gains `none`) and parses to [LifecycleMode.NONE]; the STRICT enum is lowercase
 * only (the existing STRICT cases reject `bogus` and a wrong-case `Work` at the value's pointer); a `lifecycle: none` schema may
 * not declare its own `seats` (error at `/work_item_schemas/<type>/seats`) nor a note with `required: true` (error at
 * `/work_item_schemas/<type>/notes/<i>/required`), because no gate ever runs for a lifecycle-free item; the same declarations
 * on any other lifecycle stay valid. [LifecycleMode.fromString] keeps its documented contract (case-insensitive, trimmed).
 *
 * Every rejected document has a repaired twin that must be admitted, so a rejection cannot come from an unrelated cause.
 */
class LifecycleNoneGrammarTest {
    private fun validate(
        yaml: String,
        scope: ConfigScope = ConfigScope.ROOT
    ): ConfigValidationResult =
        ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null }).validate(yaml, YamlConfigDocumentParser, scope)

    private fun ConfigValidationResult.paths(): List<String> = errors.map { it.path }

    private fun parsed(
        yaml: String,
        mode: ParseMode
    ): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "the document must parse: $outcome")
    }

    private fun schemaYaml(
        lifecycleLine: String?,
        body: String = "    notes: []\n"
    ): String = "work_item_schemas:\n  page:\n" + (lifecycleLine?.let { "    lifecycle: $it\n" } ?: "") + body

    // ---------------------------------------------------------------------------------------------
    // S1 - the value is valid and parses to NONE
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S1 lifecycle none validates under STRICT at both scopes with no error`() {
        val yaml = schemaYaml("none")

        for (scope in listOf(ConfigScope.ROOT, ConfigScope.SERVER)) {
            val result = validate(yaml, scope)
            assertEquals(emptyList(), result.errors, "scope $scope: lifecycle none must be admitted")
            assertTrue(result.isValid, "scope $scope")
        }
    }

    @Test
    fun `S1 the lifecycle none document parses to NONE in both parse modes and raises no lifecycle warning`() {
        for (mode in listOf(ParseMode.STRICT, ParseMode.MIGRATION)) {
            val document = parsed(schemaYaml("none"), mode).document

            assertEquals(LifecycleMode.NONE, document.workItemSchemas.getValue("page").lifecycleMode, "mode $mode")
            assertTrue(document.warnings.none { it.contains("lifecycle") }, "mode $mode: ${document.warnings}")
        }
    }

    @Test
    fun `S1 each lifecycle keeps its own mode when none sits next to the others in one document`() {
        val yaml =
            "work_item_schemas:\n" +
                "  a:\n    lifecycle: auto\n    notes: []\n" +
                "  m:\n    lifecycle: manual\n    notes: []\n" +
                "  p:\n    lifecycle: permanent\n    notes: []\n" +
                "  n:\n    lifecycle: none\n    notes: []\n"

        val schemas = parsed(yaml, ParseMode.STRICT).document.workItemSchemas

        assertEquals(
            mapOf("a" to LifecycleMode.AUTO, "m" to LifecycleMode.MANUAL, "p" to LifecycleMode.PERMANENT, "n" to LifecycleMode.NONE),
            schemas.mapValues { it.value.lifecycleMode }
        )
        assertEquals(emptyList(), validate(yaml).errors)
    }

    @Test
    fun `S1 LifecycleMode has a NONE entry and fromString resolves none under the existing case and trim contract`() {
        assertTrue("NONE" in LifecycleMode.entries.map { it.name })
        assertEquals(4, LifecycleMode.entries.size, "auto, manual, permanent and none")
        assertEquals(LifecycleMode.NONE, LifecycleMode.fromString("none"))
        assertEquals(LifecycleMode.NONE, LifecycleMode.fromString("NONE"))
        assertEquals(LifecycleMode.NONE, LifecycleMode.fromString("  None  "))
        assertEquals(LifecycleMode.AUTO, LifecycleMode.fromString("auto"), "control: the neighbouring value is unchanged")
    }

    // ---------------------------------------------------------------------------------------------
    // Probes - spelling, empty and absent
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `probe - STRICT is lowercase only, so None, NONE and a padded none are errors at the lifecycle pointer`() {
        val pointer = "/work_item_schemas/page/lifecycle"

        for (spelling in listOf("None", "NONE", "\" none \"", "\"none \"")) {
            val result = validate(schemaYaml(spelling))
            assertTrue(pointer in result.paths(), "spelling $spelling must be rejected at $pointer, got ${result.errors}")
        }
        assertEquals(emptyList(), validate(schemaYaml("none")).errors, "control: the lowercase spelling is admitted")
    }

    @Test
    fun `probe - an absent lifecycle is auto and never none, and an empty lifecycle is never none`() {
        val absent = parsed(schemaYaml(null), ParseMode.MIGRATION).document.workItemSchemas.getValue("page")
        val empty =
            parsed("work_item_schemas:\n  page:\n    lifecycle:\n    notes: []\n", ParseMode.MIGRATION)
                .document
                .workItemSchemas
                .getValue("page")

        assertEquals(LifecycleMode.AUTO, absent.lifecycleMode, "absent means the documented default")
        assertNotEquals(LifecycleMode.NONE, empty.lifecycleMode, "an empty value must not be read as none")
        assertEquals(emptyList(), validate(schemaYaml(null)).errors, "control: an absent lifecycle is valid")
    }

    // ---------------------------------------------------------------------------------------------
    // S10 - a none schema declares no seats and no required note
    // ---------------------------------------------------------------------------------------------

    private val seatsBlock = "    seats:\n      - { name: impl, phase: work, enters: true }\n"

    @Test
    fun `S10 a none schema that declares seats is an error at its seats pointer, without seats or on another lifecycle it is admitted`() {
        val broken = validate(schemaYaml("none", seatsBlock + "    notes: []\n"))
        val repaired = validate(schemaYaml("none"))
        val otherLifecycle = validate(schemaYaml("auto", seatsBlock + "    notes: []\n"))

        assertTrue("/work_item_schemas/page/seats" in broken.paths(), "errors: ${broken.errors}")
        assertEquals(emptyList(), repaired.errors, "the repaired twin is admitted")
        assertEquals(emptyList(), otherLifecycle.errors, "the same seats on an auto schema stay valid")
    }

    @Test
    fun `S10 a none schema with a required note is an error at that note's required pointer and not at an optional one`() {
        val notes =
            "    notes:\n" +
                "      - { key: optional-note, role: queue, required: false }\n" +
                "      - { key: gate-note, role: queue, required: true }\n"

        for (scope in listOf(ConfigScope.ROOT, ConfigScope.SERVER)) {
            val broken = validate(schemaYaml("none", notes), scope)

            assertTrue("/work_item_schemas/page/notes/1/required" in broken.paths(), "scope $scope: ${broken.errors}")
            assertTrue("/work_item_schemas/page/notes/0/required" !in broken.paths(), "scope $scope: the optional note is fine")
        }
        val repaired = validate(schemaYaml("none", notes.replace("required: true", "required: false")))
        val otherLifecycle = validate(schemaYaml("manual", notes))
        assertEquals(emptyList(), repaired.errors, "the repaired twin is admitted")
        assertEquals(emptyList(), otherLifecycle.errors, "the same required note on a manual schema stays valid")
    }

    @Test
    fun `S10 probe - a note that omits required on a none schema is admitted`() {
        val notes = "    notes:\n      - { key: plain-note, role: queue }\n"

        assertEquals(emptyList(), validate(schemaYaml("none", notes)).errors)
    }

    @Test
    fun `S10 a none schema with both seats and a required note reports both pointers`() {
        val yaml = schemaYaml("none", seatsBlock + "    notes:\n      - { key: gate-note, role: queue, required: true }\n")

        val result = validate(yaml)

        assertTrue("/work_item_schemas/page/seats" in result.paths(), "errors: ${result.errors}")
        assertTrue("/work_item_schemas/page/notes/0/required" in result.paths(), "errors: ${result.errors}")
    }
}
