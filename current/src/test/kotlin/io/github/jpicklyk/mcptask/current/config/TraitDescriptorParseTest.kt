package io.github.jpicklyk.mcptask.current.config

import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 3b7a9ed5 / U2) for parsing the trait `description` and `applies_when` keys:
 * scenarios S7, S8 and S9 of the frozen test-plan.
 *
 * Oracles: task-scope D1 (the exact keys `description` and `applies_when`; any other spelling is an unknown trait key), D3 (a
 * value must be a string, a blank string is absent, the value is trimmed, a non-string is a finding at
 * `/traits/<name>/<key>`) and the ParseMode contract (STRICT admits new config and turns a former soft skip into an error;
 * MIGRATION never rejects, every finding is a warning).
 */
class TraitDescriptorParseTest {
    private val notes = "    notes:\n      - key: n1\n        role: work\n        required: false\n"

    private fun yaml(traitBody: String): String = "traits:\n  t1:\n$traitBody$notes"

    private fun parsed(
        text: String,
        mode: ParseMode
    ): ConfigDocument {
        val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = mode)
        check(outcome is ConfigDocumentParser.Outcome.Parsed) { "expected Parsed, got $outcome" }
        return outcome.document
    }

    private fun strictPaths(text: String): List<String> =
        when (val outcome = YamlConfigDocumentParser.parse(text, warnOnMissingSchemas = false, mode = ParseMode.STRICT)) {
            is ConfigDocumentParser.Outcome.Failed -> outcome.violations.map { it.path }
            is ConfigDocumentParser.Outcome.Parsed -> outcome.document.errors.map { it.path }
        }

    // ------------------------------------------------------------------ S7

    @Test
    fun `S7 MIGRATION parse of a non-string description is one warning at the pointer and the trait serves no description`() {
        val doc = parsed(yaml("    description: 5\n"), ParseMode.MIGRATION)
        assertEquals(emptyList(), doc.errors)
        val hits = doc.warningViolations.filter { it.path == "/traits/t1/description" }
        assertEquals(1, hits.size, "warnings: ${doc.warningViolations}")
        assertNull(doc.traitDescriptors["t1"]?.description)
    }

    @Test
    fun `S7 MIGRATION parse of a non-string applies_when is a warning at its own pointer`() {
        val doc = parsed(yaml("    applies_when: [a]\n"), ParseMode.MIGRATION)
        assertEquals(emptyList(), doc.errors)
        assertEquals(1, doc.warningViolations.count { it.path == "/traits/t1/applies_when" }, "warnings: ${doc.warningViolations}")
        assertNull(doc.traitDescriptors["t1"]?.appliesWhen)
    }

    @Test
    fun `S7 control - STRICT parse of the same non-string description is an error at the pointer`() {
        val paths = strictPaths(yaml("    description: 5\n"))
        assertTrue("/traits/t1/description" in paths, "paths: $paths")
    }

    // ------------------------------------------------------------------ S8

    @Test
    fun `S8 a blank description is absent and a padded one is trimmed`() {
        val blank = parsed(yaml("    description: \"   \"\n"), ParseMode.STRICT)
        assertNull(blank.traitDescriptors["t1"]?.description)
        val padded = parsed(yaml("    description: \"  Text  \"\n    applies_when: \"\\tWhen X \"\n"), ParseMode.STRICT)
        assertEquals("Text", padded.traitDescriptors["t1"]?.description)
        assertEquals("When X", padded.traitDescriptors["t1"]?.appliesWhen)
    }

    @Test
    fun `S8 an empty string applies_when is absent`() {
        val doc = parsed(yaml("    applies_when: \"\"\n"), ParseMode.STRICT)
        assertNull(doc.traitDescriptors["t1"]?.appliesWhen)
    }

    @Test
    fun `S8 probe - a folded block scalar with CRLF line breaks folds to one trimmed line`() {
        val text =
            "traits:\r\n  t1:\r\n    description: >\r\n      line one\r\n      line two\r\n" +
                "    notes:\r\n      - key: n1\r\n        role: work\r\n        required: false\r\n"
        val doc = parsed(text, ParseMode.STRICT)
        assertEquals("line one line two", doc.traitDescriptors["t1"]?.description)
    }

    // ------------------------------------------------------------------ S9

    @Test
    fun `S9 camelCase and capitalised spellings are STRICT errors at the spelled key`() {
        for (spelled in listOf("appliesWhen", "Description", "Applies_when", "DESCRIPTION")) {
            val paths = strictPaths(yaml("    $spelled: x\n"))
            assertTrue("/traits/t1/$spelled" in paths, "$spelled: paths $paths")
        }
    }

    @Test
    fun `S9 control - the exact keys parse clean in STRICT mode`() {
        val doc = parsed(yaml("    description: d\n    applies_when: w\n"), ParseMode.STRICT)
        assertEquals(emptyList(), doc.errors)
        assertEquals("d", doc.traitDescriptors["t1"]?.description)
        assertEquals("w", doc.traitDescriptors["t1"]?.appliesWhen)
    }
}
