package io.github.jpicklyk.mcptask.current.application.config

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item edae92cb / Q16) for [ConfigDiff.between], scenario S12 (the diff half).
 *
 * Oracle P (v4 plan s3.6, task-scope build item 6): the diff is `{types, traits, settings} x {added, removed, changed}`
 * where `types` are the `work_item_schemas` entries, `traits` the `traits` entries, and `changed` means the canonical
 * JSON of the entry differs. Identical documents give an empty diff; with no head, everything proposed is added.
 *
 * Only the named-entry deltas (types, traits) are asserted for content: which top-level keys count as `settings` is not
 * part of the frozen oracle, so settings are asserted only where the whole diff is empty or the documents differ in
 * types and traits alone.
 */
class ConfigDiffTest {
    @Suppress("UNCHECKED_CAST")
    private fun load(yaml: String): Map<String, Any?> = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any?>>(yaml) ?: emptyMap()

    private val head =
        load(
            """
            work_item_schemas:
              feature-task:
                notes:
                  - key: spec
                    role: queue
                    required: true
              bug-fix:
                notes:
                  - key: diagnosis
                    role: queue
                    required: true
            traits:
              needs-review:
                notes:
                  - key: review-note
                    role: review
                    required: false
              needs-docs:
                notes:
                  - key: docs-note
                    role: work
                    required: false
            """.trimIndent()
        )

    @Test
    fun `S12 an added type, a removed trait and a changed type each land in their own delta`() {
        val proposed =
            load(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: false
                  bug-fix:
                    notes:
                      - key: diagnosis
                        role: queue
                        required: true
                  chore:
                    notes: []
                traits:
                  needs-review:
                    notes:
                      - key: review-note
                        role: review
                        required: false
                """.trimIndent()
            )

        val diff = ConfigDiff.between(head, proposed)

        assertEquals(listOf("chore"), diff.types.added)
        assertEquals(emptyList(), diff.types.removed)
        assertEquals(listOf("feature-task"), diff.types.changed, "only the type whose required flag flipped is changed")
        assertEquals(emptyList(), diff.traits.added)
        assertEquals(listOf("needs-docs"), diff.traits.removed)
        assertEquals(emptyList(), diff.traits.changed)
        assertFalse(diff.isEmpty)
    }

    @Test
    fun `S12 a removed type and an added trait are reported in the opposite deltas`() {
        val proposed =
            load(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: true
                traits:
                  needs-review:
                    notes:
                      - key: review-note
                        role: review
                        required: false
                  needs-docs:
                    notes:
                      - key: docs-note
                        role: work
                        required: false
                  needs-perf:
                    notes: []
                """.trimIndent()
            )

        val diff = ConfigDiff.between(head, proposed)

        assertEquals(listOf("bug-fix"), diff.types.removed)
        assertEquals(emptyList(), diff.types.added)
        assertEquals(emptyList(), diff.types.changed)
        assertEquals(listOf("needs-perf"), diff.traits.added)
        assertEquals(emptyList(), diff.traits.removed)
    }

    @Test
    fun `S12 a changed trait is reported as changed, not as added plus removed`() {
        val proposed =
            load(
                """
                work_item_schemas:
                  feature-task:
                    notes:
                      - key: spec
                        role: queue
                        required: true
                  bug-fix:
                    notes:
                      - key: diagnosis
                        role: queue
                        required: true
                traits:
                  needs-review:
                    notes:
                      - key: review-note
                        role: review
                        required: true
                  needs-docs:
                    notes:
                      - key: docs-note
                        role: work
                        required: false
                """.trimIndent()
            )

        val diff = ConfigDiff.between(head, proposed)

        assertEquals(listOf("needs-review"), diff.traits.changed)
        assertEquals(emptyList(), diff.traits.added)
        assertEquals(emptyList(), diff.traits.removed)
        assertEquals(emptyList(), diff.types.added + diff.types.removed + diff.types.changed)
    }

    @Test
    fun `S12 identical documents give an empty diff`() {
        val diff = ConfigDiff.between(head, head)

        assertTrue(diff.isEmpty, "identical documents: $diff")
        assertTrue(diff.types.isEmpty)
        assertTrue(diff.traits.isEmpty)
        assertTrue(diff.settings.isEmpty)
    }

    @Test
    fun `S12 a document rewritten with different key order and no content change gives an empty diff`() {
        val reordered =
            load(
                """
                traits:
                  needs-docs:
                    notes:
                      - required: false
                        role: work
                        key: docs-note
                  needs-review:
                    notes:
                      - role: review
                        key: review-note
                        required: false
                work_item_schemas:
                  bug-fix:
                    notes:
                      - role: queue
                        required: true
                        key: diagnosis
                  feature-task:
                    notes:
                      - required: true
                        key: spec
                        role: queue
                """.trimIndent()
            )

        val diff = ConfigDiff.between(head, reordered)

        assertTrue(diff.isEmpty, "changed is defined by canonical JSON, so key order alone must not count: $diff")
    }

    @Test
    fun `S12 with no head every proposed type and trait is added`() {
        val diff = ConfigDiff.between(null, head)

        assertEquals(setOf("feature-task", "bug-fix"), diff.types.added.toSet())
        assertEquals(setOf("needs-review", "needs-docs"), diff.traits.added.toSet())
        assertEquals(emptyList(), diff.types.removed + diff.types.changed)
        assertEquals(emptyList(), diff.traits.removed + diff.traits.changed)
    }

    @Test
    fun `S12 an empty head behaves like no head for types and traits`() {
        val diff = ConfigDiff.between(emptyMap(), head)

        assertEquals(setOf("feature-task", "bug-fix"), diff.types.added.toSet())
        assertEquals(setOf("needs-review", "needs-docs"), diff.traits.added.toSet())
    }

    @Test
    fun `S12 an empty proposed document removes every type and trait of the head`() {
        val diff = ConfigDiff.between(head, emptyMap())

        assertEquals(setOf("feature-task", "bug-fix"), diff.types.removed.toSet())
        assertEquals(setOf("needs-review", "needs-docs"), diff.traits.removed.toSet())
        assertEquals(emptyList(), diff.types.added + diff.types.changed)
    }

    @Test
    fun `S12 two empty sides give an empty diff`() {
        assertTrue(ConfigDiff.between(null, null).isEmpty)
        assertTrue(ConfigDiff.between(emptyMap(), emptyMap()).isEmpty)
    }

    @Test
    fun `S12 a change outside work_item_schemas and traits does not touch the type and trait deltas`() {
        val withProject = load("project:\n  name: alpha\n")
        val renamedProject = load("project:\n  name: beta\n")

        val diff = ConfigDiff.between(withProject, renamedProject)

        assertTrue(diff.types.isEmpty)
        assertTrue(diff.traits.isEmpty)
    }
}
