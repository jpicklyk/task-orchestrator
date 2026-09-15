package io.github.jpicklyk.mcptask.current.infrastructure.config

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [YamlSchemaParser.parseEntry]'s soft validation warnings on `notes:` entries —
 * an invalid `role` value and a missing `key`/`role` field — for both base schema note lists
 * (`work_item_schemas.<tag>.notes`) and per-trait note lists (`traits.<name>.notes`).
 *
 * Independent test authorship per the `needs-test-author` trait: written against the item's
 * `test-plan` note oracles (config-format.md's documented `key`/`role` requirements) and the
 * public [YamlSchemaParser.ParsedConfig] shape, without reading the implementer's own tests or
 * notes. Parses real YAML text via [SafeConstructor] (mirroring [YamlSchemaParserResourcesTest]
 * and how the production loaders parse the same document) rather than hand-building
 * `Map<String, Any>` literals.
 */
class YamlSchemaParserRoleWarningTest {
    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    // ──────────────────────────────────────────────
    // S3 — invalid role value
    // ──────────────────────────────────────────────

    @Test
    fun `S3 invalid role value produces exactly one warning naming schema key and bad value`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: not-a-role
                """.trimIndent()
            )

        assertEquals(1, parsed.warnings.size, "expected exactly one warning: ${parsed.warnings}")
        val warning = parsed.warnings.single()
        assertTrue(warning.contains("feature-task"), "warning should name the schema: $warning")
        assertTrue(warning.contains("spec"), "warning should name the entry's key: $warning")
        assertTrue(warning.contains("not-a-role"), "warning should name the bad role value: $warning")

        // The invalid entry must be skipped, not stored with a garbage role.
        assertTrue(
            parsed.workItemSchemas["feature-task"]!!.notes.isEmpty(),
            "an entry with an invalid role must not be added to the resolved schema"
        )
    }

    // ──────────────────────────────────────────────
    // S4 — missing key
    // ──────────────────────────────────────────────

    @Test
    fun `S4 missing key produces one warning naming schema, index and field`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - role: queue
                """.trimIndent()
            )

        assertEquals(1, parsed.warnings.size, "expected exactly one warning: ${parsed.warnings}")
        val warning = parsed.warnings.single()
        assertTrue(warning.contains("feature-task"), "warning should name the schema: $warning")
        assertTrue(warning.contains("entry[0]"), "warning should name the entry index: $warning")
        assertTrue(warning.contains("key"), "warning should name the missing field: $warning")
        assertTrue(parsed.workItemSchemas["feature-task"]!!.notes.isEmpty())
    }

    // ──────────────────────────────────────────────
    // S5 — missing role
    // ──────────────────────────────────────────────

    @Test
    fun `S5 missing role produces one warning naming schema, index and field`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                """.trimIndent()
            )

        assertEquals(1, parsed.warnings.size, "expected exactly one warning: ${parsed.warnings}")
        val warning = parsed.warnings.single()
        assertTrue(warning.contains("feature-task"), "warning should name the schema: $warning")
        assertTrue(warning.contains("entry[0]"), "warning should name the entry index: $warning")
        assertTrue(warning.contains("spec"), "warning should name the entry's key: $warning")
        assertTrue(warning.contains("role"), "warning should name the missing field: $warning")
        assertTrue(parsed.workItemSchemas["feature-task"]!!.notes.isEmpty())
    }

    // ──────────────────────────────────────────────
    // S6 — trait note carries trait context
    // ──────────────────────────────────────────────

    @Test
    fun `S6 trait note with invalid role carries trait context in the warning`() {
        val parsed =
            parse(
                """
                traits:
                  needs-migration-review:
                    notes:
                      - key: migration-assessment
                        role: bogus-role
                """.trimIndent()
            )

        assertEquals(1, parsed.warnings.size, "expected exactly one warning: ${parsed.warnings}")
        val warning = parsed.warnings.single()
        assertTrue(warning.contains("trait:needs-migration-review"), "warning should carry trait context: $warning")
        assertTrue(warning.contains("migration-assessment"), "warning should name the entry's key: $warning")
        assertTrue(warning.contains("bogus-role"), "warning should name the bad role value: $warning")

        assertTrue(
            parsed.traits["needs-migration-review"]!!.isEmpty(),
            "an invalid trait note entry must not be added to the trait's resolved notes"
        )
    }

    // ──────────────────────────────────────────────
    // S7 — multiple invalid entries in one notes list, entry order
    // ──────────────────────────────────────────────

    @Test
    fun `S7 two invalid entries (bad role at index 0, missing key at index 2) warn in entry order`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: bad-role-entry
                        role: not-a-role
                      - key: good-entry
                        role: work
                      - role: queue
                """.trimIndent()
            )

        assertEquals(2, parsed.warnings.size, "expected exactly two warnings: ${parsed.warnings}")
        assertTrue(
            parsed.warnings[0].contains("bad-role-entry") && parsed.warnings[0].contains("not-a-role"),
            "first warning should be for entry[0] (bad role): ${parsed.warnings}"
        )
        assertTrue(
            parsed.warnings[1].contains("entry[2]") && parsed.warnings[1].contains("key"),
            "second warning should be for entry[2] (missing key): ${parsed.warnings}"
        )

        // The one well-formed entry (index 1) survives.
        val notes = parsed.workItemSchemas["feature-task"]!!.notes
        assertEquals(1, notes.size)
        assertEquals("good-entry", notes.single().key)
    }

    // ──────────────────────────────────────────────
    // Probe: mixed case — role names are recognized lowercase only
    // ──────────────────────────────────────────────

    @Test
    fun `probe mixed-case role value Queue is rejected -- only lowercase role names are valid`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: Queue
                """.trimIndent()
            )

        assertEquals(1, parsed.warnings.size, "expected exactly one warning: ${parsed.warnings}")
        assertTrue(parsed.warnings.single().contains("Queue"))
        assertTrue(
            parsed.workItemSchemas["feature-task"]!!.notes.isEmpty(),
            "a mixed-case role value must not be silently accepted"
        )
    }

    // ──────────────────────────────────────────────
    // Probe: duplicates — the same bad key appearing twice warns twice
    // ──────────────────────────────────────────────

    @Test
    fun `probe duplicate key across two invalid entries produces two separate warnings`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: dup-key
                        role: not-a-role
                      - key: dup-key
                        role: also-not-a-role
                """.trimIndent()
            )

        assertEquals(2, parsed.warnings.size, "expected exactly two warnings: ${parsed.warnings}")
        assertTrue(parsed.warnings[0].contains("entry[0]") && parsed.warnings[0].contains("not-a-role"))
        assertTrue(parsed.warnings[1].contains("entry[1]") && parsed.warnings[1].contains("also-not-a-role"))
    }
}
