package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.config.internal.wire.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Independent tests (test-author seat, item 34d41fe6) for scenarios S11 and S12 of the frozen test-plan (EXISTING-SURFACE): the
 * config lint and the STRICT parse warning treat an orchestrator seat that labels a note of another phase as owning it.
 *
 * Oracle: the diagnosis "Caller behaviour change" 4 and 5 (the L1 lint no longer fires for an orchestrator seat labelling any
 * note in any phase, and fires with the unchanged reason when no note names it; the required-note-unowned warning stops for such
 * a note). Reason texts are the pre-existing ones recorded in the declarations.
 *
 * Fixtures parse under STRICT without errors (asserted by the helpers); each negative assertion has a twin that differs only in
 * the one condition that produces the finding.
 *
 * Forbidden-construct declaration: none.
 */
class OrchestratorSeatConfigTest {
    private val validator = ConfigValidator(NetworkntConfigStructuralValidator(), GlobalConfigSource { null })

    private fun yaml(vararg lines: String): String = lines.joinToString("\n") + "\n"

    private fun parsed(text: String): ConfigDocumentParser.Outcome.Parsed {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)
        val p = assertIs<ConfigDocumentParser.Outcome.Parsed>(outcome, "fixture must parse under STRICT: $outcome")
        assertEquals(emptyList(), p.document.errors, "fixture must be STRICT-clean")
        return p
    }

    private fun lint(text: String): List<ConfigViolation> {
        val p = parsed(text)
        assertEquals(emptyList(), validator.validate(p, ConfigScope.ROOT, null).errors, "fixture must validate without errors")
        return validator.lint(p, null, emptySet())
    }

    private val withConfirmation =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    seats:",
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true }",
            "      - { name: orchestrator, phase: work }",
            "    notes:",
            "      - key: scope",
            "        role: queue",
            "        required: true",
            "        seat: planner",
            "      - key: impl",
            "        role: work",
            "        required: true",
            "        seat: implementer",
            "      - key: orchestrator-confirmation",
            "        role: review",
            "        required: false",
            "        seat: orchestrator",
        )

    private val withoutConfirmation =
        yaml(
            "work_item_schemas:",
            "  x:",
            "    seats:",
            "      - { name: planner, phase: queue }",
            "      - { name: implementer, phase: work, enters: true }",
            "      - { name: orchestrator, phase: work }",
            "    notes:",
            "      - key: scope",
            "        role: queue",
            "        required: true",
            "        seat: planner",
            "      - key: impl",
            "        role: work",
            "        required: true",
            "        seat: implementer",
        )

    @Test
    fun `S11 an orchestrator seat that labels a review note is not flagged as owning no note`() {
        val seatThree = lint(withConfirmation).filter { it.path == "/work_item_schemas/x/seats/2" }
        assertEquals(emptyList(), seatThree)
    }

    @Test
    fun `S11 twin - without the orchestrator labelled note the seat is flagged with the unchanged reason`() {
        val seatThree = lint(withoutConfirmation).filter { it.path == "/work_item_schemas/x/seats/2" }
        assertEquals(1, seatThree.size, "findings at seats/2: $seatThree")
        assertEquals("seat 'orchestrator' (work) owns no work-phase note in schema 'x'", seatThree.single().reason)
    }

    private fun strictWarnings(ownerOfConfirmation: String): List<String> {
        val text =
            yaml(
                "work_item_schemas:",
                "  x:",
                "    seats:",
                "      - { name: planner, phase: queue }",
                "      - { name: orchestrator, phase: work }",
                "    notes:",
                "      - key: scope",
                "        role: queue",
                "        required: true",
                "        seat: planner",
                "      - key: orchestrator-confirmation",
                "        role: review",
                "        required: true",
                "        seat: $ownerOfConfirmation",
            )
        return validator.validate(parsed(text), ConfigScope.ROOT, null).warnings.map { it.reason }
    }

    @Test
    fun `S12 a required review note labelled orchestrator draws no unowned warning`() {
        val hits = strictWarnings("orchestrator").filter { it.contains("required note 'orchestrator-confirmation'") }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `S12 twin - the same note labelled with a queue seat draws exactly one unowned warning`() {
        val hits = strictWarnings("planner").filter { it.contains("required note 'orchestrator-confirmation'") }
        assertEquals(1, hits.size, "warnings: $hits")
    }
}
