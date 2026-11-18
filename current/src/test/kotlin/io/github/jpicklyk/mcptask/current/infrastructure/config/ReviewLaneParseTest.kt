package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.application.config.ParseMode
import io.github.jpicklyk.mcptask.current.domain.model.LaneOutput
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.ReviewLane
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independently authored tests (test-author seat, item 775f55e6 / U4) for the review-lane declaration on a note entry:
 * scenarios S1, S2, S8 and S13 of the frozen test-plan, plus the CRLF and trait-note probes.
 *
 * Oracle (frozen task-scope "Config parse", recomputed by hand): `lane` is an optional map on a review-role entry of a schema
 * or a trait; absent or YAML null is no lane; `lane: {}` is a declared lane (output verdict, after []); `after` is a string
 * (one element) or a list in order, `[]` is none with no finding. Soft findings are a STRICT error and a MIGRATION warning at
 * the entry pointer plus a suffix: a lane on a queue or work entry or a non-map lane `/lane` (lane ignored); an unknown key
 * `/lane/<key>` (ignored); an `output` that is not exactly `verdict` or `findings` `/lane/output` (verdict); an `after` that
 * is neither string nor list `/lane/after` (none); a blank or non-string element, a duplicate (the later copy) or an element
 * equal to the entry's own key `/lane/after/<i>` (blank or non-string: the whole after ignored; duplicate: first kept; own key:
 * that element dropped). Whether an `after` key exists is not checked here (D2). The JSON Schema `noteEntry.lane` is a closed
 * object whose property keys are ConfigKeyCatalog.NOTE_LANE.
 *
 * Fixture condition for each negative assertion: every case sits next to a control entry or case in this file that does
 * carry the lane, so a parser that always dropped or always kept it fails one of the two assertions.
 * Forbidden-construct declaration: none.
 */
class ReviewLaneParseTest {
    private fun document(
        yaml: String,
        mode: ParseMode
    ): ConfigDocument =
        assertIs<ConfigDocumentParser.Outcome.Parsed>(
            YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode),
            "expected Parsed for $mode"
        ).document

    /** One place an entry can sit: a schema entry or a trait entry. */
    private class Place(
        val name: String,
        val pointer: String,
        private val wrap: (String) -> String,
        private val pick: (ConfigDocument) -> List<NoteSchemaEntry>
    ) {
        fun yaml(vararg entries: String): String = wrap(entries.joinToString("") { "      - $it\n" })

        fun entries(doc: ConfigDocument): List<NoteSchemaEntry> = pick(doc)
    }

    private val places =
        listOf(
            Place(
                "schema",
                "/work_item_schemas/t/notes/0",
                { "work_item_schemas:\n  t:\n    notes:\n$it" },
                { it.workItemSchemas.getValue("t").notes }
            ),
            Place(
                "trait",
                "/traits/t1/notes/0",
                { "traits:\n  t1:\n    notes:\n$it" },
                { it.traits.getValue("t1") }
            )
        )

    private fun entry(
        lane: String?,
        role: String = "review"
    ): String = "{ key: n, role: $role" + (lane?.let { ", lane: $it" } ?: "") + " }"

    // ------------------------------------------------------------------ S1

    @Test
    fun `S1 a findings lane with after parses on a schema entry and a trait entry, STRICT with no finding`() {
        for (p in places) {
            val doc = document(p.yaml(entry("{ output: findings, after: [a] }")), ParseMode.STRICT)

            assertEquals(emptyList(), doc.errors.map { it.path }, p.name)
            assertEquals(ReviewLane(LaneOutput.FINDINGS, listOf("a")), p.entries(doc).single().lane, p.name)
        }
    }

    @Test
    fun `S1 a verdict lane with a two-key after keeps the order`() {
        for (p in places) {
            val doc = document(p.yaml(entry("{ output: verdict, after: [b, a] }")), ParseMode.STRICT)

            assertEquals(emptyList(), doc.errors.map { it.path }, p.name)
            assertEquals(ReviewLane(LaneOutput.VERDICT, listOf("b", "a")), p.entries(doc).single().lane, p.name)
        }
    }

    // ------------------------------------------------------------------ S2

    @Test
    fun `S2 an empty lane map is a declared verdict lane with no after, and no finding`() {
        for (p in places) {
            for (mode in ParseMode.values()) {
                val doc = document(p.yaml(entry("{}")), mode)

                assertEquals(ReviewLane(LaneOutput.VERDICT, emptyList()), p.entries(doc).single().lane, "${p.name} $mode")
                assertEquals(emptyList(), doc.errors.map { it.path }, "${p.name} $mode")
                assertEquals(emptyList(), doc.warningViolations.map { it.path }, "${p.name} $mode")
            }
        }
    }

    @Test
    fun `S2 after as a string is a one-element list and after as an empty list is none`() {
        for (p in places) {
            val str = document(p.yaml(entry("{ after: a }")), ParseMode.STRICT)
            val empty = document(p.yaml(entry("{ output: findings, after: [] }")), ParseMode.STRICT)

            assertEquals(emptyList(), str.errors.map { it.path }, p.name)
            assertEquals(ReviewLane(LaneOutput.VERDICT, listOf("a")), p.entries(str).single().lane, p.name)
            assertEquals(emptyList(), empty.errors.map { it.path }, p.name)
            assertEquals(ReviewLane(LaneOutput.FINDINGS, emptyList()), p.entries(empty).single().lane, p.name)
        }
    }

    @Test
    fun `S2 an absent lane and a YAML null lane are both no lane with no finding, next to a declared lane`() {
        for (p in places) {
            for (mode in ParseMode.values()) {
                val doc = document(p.yaml(entry(null), entry("~"), entry("{}")), mode)
                val lanes = p.entries(doc).map { it.lane }

                assertEquals(listOf(null, null, ReviewLane()), lanes, "${p.name} $mode")
                assertEquals(emptyList(), doc.errors.map { it.path }, "${p.name} $mode")
            }
        }
    }

    // ------------------------------------------------------------------ S8

    private class Bad(
        val label: String,
        val entry: String,
        val suffix: String,
        val migrationLane: ReviewLane?
    )

    private val bad =
        listOf(
            Bad("lane on a work entry", entry("{ output: findings }", role = "work"), "/lane", null),
            Bad("lane on a queue entry", entry("{ output: findings }", role = "queue"), "/lane", null),
            Bad("lane a string", entry("findings"), "/lane", null),
            Bad("lane a list", entry("[findings]"), "/lane", null),
            Bad(
                "output capitalised",
                entry("{ output: Findings, after: [a] }"),
                "/lane/output",
                ReviewLane(LaneOutput.VERDICT, listOf("a"))
            ),
            Bad("output a number", entry("{ output: 5, after: [a] }"), "/lane/output", ReviewLane(LaneOutput.VERDICT, listOf("a"))),
            Bad("output unknown word", entry("{ output: bogus }"), "/lane/output", ReviewLane(LaneOutput.VERDICT, emptyList())),
            Bad(
                "after with a blank element",
                entry("{ output: findings, after: [a, \"\"] }"),
                "/lane/after/1",
                ReviewLane(LaneOutput.FINDINGS, emptyList())
            ),
            Bad(
                "after with a whitespace element",
                entry("{ output: findings, after: [a, \"  \"] }"),
                "/lane/after/1",
                ReviewLane(LaneOutput.FINDINGS, emptyList())
            ),
            Bad(
                "after with a number element",
                entry("{ output: findings, after: [a, 5] }"),
                "/lane/after/1",
                ReviewLane(LaneOutput.FINDINGS, emptyList())
            ),
            Bad(
                "after with a duplicate",
                entry("{ output: findings, after: [a, a] }"),
                "/lane/after/1",
                ReviewLane(LaneOutput.FINDINGS, listOf("a"))
            ),
            Bad(
                "after naming the entry's own key",
                entry("{ output: findings, after: [n, b] }"),
                "/lane/after/0",
                ReviewLane(LaneOutput.FINDINGS, listOf("b"))
            ),
            Bad("unknown key in lane", entry("{ x: 1 }"), "/lane/x", ReviewLane(LaneOutput.VERDICT, emptyList())),
            Bad(
                "after neither string nor list",
                entry("{ output: findings, after: 5 }"),
                "/lane/after",
                ReviewLane(LaneOutput.FINDINGS, emptyList())
            )
        )

    @Test
    fun `S8 each soft finding is one STRICT error at its path`() {
        for (p in places) {
            for (c in bad) {
                val doc = document(p.yaml(c.entry), ParseMode.STRICT)

                assertEquals(listOf(p.pointer + c.suffix), doc.errors.map { it.path }, "${p.name}: ${c.label}")
            }
        }
    }

    @Test
    fun `S8 each soft finding is one MIGRATION warning at the same path with its fallback lane and no error`() {
        for (p in places) {
            for (c in bad) {
                val doc = document(p.yaml(c.entry), ParseMode.MIGRATION)

                assertEquals(emptyList(), doc.errors.map { it.path }, "${p.name}: ${c.label}")
                assertEquals(listOf(p.pointer + c.suffix), doc.warningViolations.map { it.path }, "${p.name}: ${c.label}")
                assertTrue(doc.warnings.isNotEmpty(), "${p.name}: ${c.label} has a warning text")
                assertEquals(c.migrationLane, p.entries(doc).single().lane, "${p.name}: ${c.label}")
            }
        }
    }

    @Test
    fun `S8 a finding on one entry does not disturb a clean lane on its sibling entry`() {
        for (p in places) {
            val doc =
                document(
                    p.yaml("{ key: n, role: review, lane: { output: Findings } }", "{ key: m, role: review, lane: { output: findings } }"),
                    ParseMode.MIGRATION
                )
            val lanes = p.entries(doc).associate { it.key to it.lane }

            assertEquals(ReviewLane(LaneOutput.VERDICT, emptyList()), lanes["n"])
            assertEquals(ReviewLane(LaneOutput.FINDINGS, emptyList()), lanes["m"])
            assertEquals(1, doc.warningViolations.size)
        }
    }

    // ------------------------------------------------------------------ probes

    @Test
    fun `probe an after naming a key that is in no entry of the document is not a finding`() {
        for (p in places) {
            val doc = document(p.yaml(entry("{ output: findings, after: [a-trait-note] }")), ParseMode.STRICT)

            assertEquals(emptyList(), doc.errors.map { it.path }, p.name)
            assertEquals(
                listOf("a-trait-note"),
                p
                    .entries(doc)
                    .single()
                    .lane
                    ?.after,
                p.name
            )
        }
    }

    @Test
    fun `probe CRLF line endings parse to the same lane`() {
        for (p in places) {
            val doc = document(p.yaml(entry("{ output: findings, after: [a] }")).replace("\n", "\r\n"), ParseMode.STRICT)

            assertEquals(emptyList(), doc.errors.map { it.path }, p.name)
            assertEquals(ReviewLane(LaneOutput.FINDINGS, listOf("a")), p.entries(doc).single().lane, p.name)
        }
    }

    @Test
    fun `probe LaneOutput matches the wire word exactly`() {
        assertEquals(LaneOutput.VERDICT, LaneOutput.fromConfigString("verdict"))
        assertEquals(LaneOutput.FINDINGS, LaneOutput.fromConfigString("findings"))
        assertNull(LaneOutput.fromConfigString("Verdict"))
        assertNull(LaneOutput.fromConfigString(""))
        assertEquals("verdict", LaneOutput.VERDICT.wire)
        assertEquals("findings", LaneOutput.FINDINGS.wire)
    }

    // ------------------------------------------------------------------ S13

    private fun findKey(
        element: JsonElement,
        name: String
    ): JsonElement? {
        if (element is JsonObject) {
            element[name]?.let { return it }
            for (v in element.values) findKey(v, name)?.let { return it }
        } else if (element is kotlinx.serialization.json.JsonArray) {
            for (v in element) findKey(v, name)?.let { return it }
        }
        return null
    }

    @Test
    fun `S13 the JSON Schema noteEntry lane is a closed object whose properties are the lane catalogue`() {
        val text = ReviewLaneParseTest::class.java.getResource("/config/config.schema.json")!!.readText()
        val entryDef = assertNotNull(findKey(Json.parseToJsonElement(text), "noteEntry"), "noteEntry definition").jsonObject
        val lane =
            entryDef
                .getValue("properties")
                .jsonObject
                .getValue("lane")
                .jsonObject

        assertEquals(false, lane.getValue("additionalProperties").jsonPrimitive.booleanOrNull)
        assertEquals(ConfigKeyCatalog.NOTE_LANE, lane.getValue("properties").jsonObject.keys)
        assertEquals(
            listOf("verdict", "findings"),
            lane
                .getValue("properties")
                .jsonObject
                .getValue("output")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }
        )
        assertTrue("lane" in ConfigKeyCatalog.NOTE_ENTRY)
        assertEquals(setOf("output", "after"), ConfigKeyCatalog.NOTE_LANE)
    }
}
