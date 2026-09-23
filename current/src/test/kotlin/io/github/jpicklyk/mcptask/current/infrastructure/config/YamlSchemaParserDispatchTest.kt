package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.DispatchProfile
import io.github.jpicklyk.mcptask.current.domain.model.Role
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [YamlSchemaParser]'s `dispatch:` parsing — the per-trait, per-phase dispatch
 * profile map (B1, dispatch trait dimension). Mirrors [YamlSchemaParserResourcesTest]'s fixture
 * conventions: real YAML text parsed via [SafeConstructor], exactly as [YamlWorkItemSchemaService]
 * and [PerRootConfigService] parse the same document.
 *
 * Independent test authorship per the `needs-test-author` trait: oracles come from the pinned
 * contract and P1/P2/P6 in the item's `task-scope` note, and from the parse-contract KDoc supplied
 * in the dispatch contract's DECLARATIONS block — never from reading YamlSchemaParser's source.
 * Per that KDoc, warnings are asserted on by substring (trait name / offending phase or field),
 * never on full warning text.
 */
class YamlSchemaParserDispatchTest {
    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    // ──────────────────────────────────────────────
    // S1 — happy path: pinned contract block parses work + review, omits queue
    // ──────────────────────────────────────────────

    @Test
    fun `S1 pinned contract block parses work and review profiles, omits queue`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work:   { agent: task-orchestrator:implementer }
                      review: { agent: task-orchestrator:reviewer, effort: high }
                """.trimIndent()
            )

        val dispatch = parsed.traitDispatch["delegated"]
        assertEquals(2, dispatch?.size, "expected exactly WORK and REVIEW entries: $dispatch")
        assertEquals(DispatchProfile(agent = "task-orchestrator:implementer"), dispatch!![Role.WORK])
        assertEquals(DispatchProfile(agent = "task-orchestrator:reviewer", effort = "high"), dispatch[Role.REVIEW])
        assertNull(dispatch[Role.QUEUE], "queue is not a valid dispatch phase per the pinned contract")
    }

    // ──────────────────────────────────────────────
    // S2 — invalid phase keys (terminal, blocked, unknown) are skipped with a warning; other
    // phases in the same trait are still parsed.
    // ──────────────────────────────────────────────

    @Test
    fun `S2 phase key terminal is skipped with a warning, work is still parsed`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      terminal: { agent: ghost-agent }
                      work: { agent: task-orchestrator:implementer }
                """.trimIndent()
            )

        val dispatch = parsed.traitDispatch["delegated"]
        assertEquals(1, dispatch?.size)
        assertEquals("task-orchestrator:implementer", dispatch!![Role.WORK]?.agent)
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("terminal") })
    }

    @Test
    fun `S2 phase key blocked is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      blocked: { agent: ghost-agent }
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("blocked") })
    }

    @Test
    fun `S2 an unknown phase key is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      foo: { agent: ghost-agent }
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("foo") })
    }

    // Probe: mixed case — phase keys are matched case-sensitively; "Work" is invalid.
    @Test
    fun `probe -- phase key Work (capitalized) is invalid and skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      Work: { agent: task-orchestrator:implementer }
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("Work") })
    }

    // ──────────────────────────────────────────────
    // S3 — invalid effort value is dropped with a warning; agent field on the same phase is kept.
    // An effort-only phase with an invalid value has no valid fields left, so the phase is skipped.
    // ──────────────────────────────────────────────

    @Test
    fun `S3 an invalid effort value is dropped with a warning, the phase's agent field is kept`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { agent: task-orchestrator:implementer, effort: extreme }
                """.trimIndent()
            )

        val profile = parsed.traitDispatch["delegated"]!![Role.WORK]
        assertEquals("task-orchestrator:implementer", profile?.agent)
        assertNull(profile?.effort, "an invalid effort value must be dropped, not stored")
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("extreme") })
    }

    @Test
    fun `S3 a phase with only an invalid effort field is skipped entirely (no valid fields survive)`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { effort: extreme }
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("extreme") })
    }

    // Probe: effort "HIGH" (wrong case) is invalid — effort is matched case-sensitively.
    @Test
    fun `probe -- effort value HIGH (wrong case) is dropped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { agent: task-orchestrator:implementer, effort: HIGH }
                """.trimIndent()
            )

        val profile = parsed.traitDispatch["delegated"]!![Role.WORK]
        assertEquals("task-orchestrator:implementer", profile?.agent)
        assertNull(profile?.effort)
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("HIGH") })
    }

    // ──────────────────────────────────────────────
    // S4 — an empty phase map is skipped with a warning, and a trait whose dispatch yields no
    // valid phase is ABSENT from traitDispatch, never mapped to an empty map (parity with
    // traitResources, per P2).
    // ──────────────────────────────────────────────

    @Test
    fun `S4 an empty phase map is skipped with a warning, and the trait is absent from traitDispatch`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: {}
                """.trimIndent()
            )

        assertTrue(
            !parsed.traitDispatch.containsKey("delegated"),
            "a trait with no valid dispatch entry must be ABSENT, not mapped to an empty map"
        )
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("work") })
    }

    // ──────────────────────────────────────────────
    // Malformed top-level `dispatch:` shape — not a map
    // ──────────────────────────────────────────────

    @Test
    fun `dispatch not a map is skipped with a warning, load continues for the rest of the config`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes: []
                traits:
                  delegated:
                    dispatch: "not-a-map"
                """.trimIndent()
            )

        assertTrue(!parsed.traitDispatch.containsKey("delegated"))
        assertTrue(parsed.warnings.any { it.contains("delegated") })
        assertTrue(parsed.workItemSchemas.containsKey("feature-task"), "rest of the config must still load")
    }

    // Probe: dispatch expressed as a list — not a map — is skipped with a warning.
    @Test
    fun `probe -- dispatch expressed as a list is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch: [work, review]
                """.trimIndent()
            )

        assertTrue(!parsed.traitDispatch.containsKey("delegated"))
        assertTrue(parsed.warnings.any { it.contains("delegated") })
    }

    // Probe: a phase value that is a plain string (not a map) is skipped with a warning.
    @Test
    fun `probe -- a phase value that is a plain string is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: "task-orchestrator:implementer"
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("work") })
    }

    // Probe: a phase map containing only an unknown profile field has no valid fields — skipped.
    @Test
    fun `probe -- a phase map with only an unknown profile field is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { isolation: worktree }
                """.trimIndent()
            )

        assertTrue(parsed.traitDispatch["delegated"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("isolation") })
    }

    @Test
    fun `an unknown profile field is ignored with a warning, valid fields on the same phase are kept`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { agent: task-orchestrator:implementer, isolation: worktree }
                """.trimIndent()
            )

        val profile = parsed.traitDispatch["delegated"]!![Role.WORK]
        assertEquals("task-orchestrator:implementer", profile?.agent)
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("isolation") })
    }

    // Probe: agent: 5 (non-string) is dropped with a warning; model on the same phase is kept.
    @Test
    fun `probe -- a non-string agent field is dropped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { agent: 5, model: opus }
                """.trimIndent()
            )

        val profile = parsed.traitDispatch["delegated"]!![Role.WORK]
        assertNull(profile?.agent, "a non-string field value must be dropped")
        assertEquals("opus", profile?.model)
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("agent") })
    }

    // Probe: agent: "" (blank) is dropped with a warning; model on the same phase is kept.
    @Test
    fun `probe -- a blank agent field is dropped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  delegated:
                    dispatch:
                      work: { agent: "", model: opus }
                """.trimIndent()
            )

        val profile = parsed.traitDispatch["delegated"]!![Role.WORK]
        assertNull(profile?.agent, "a blank field value must be dropped")
        assertEquals("opus", profile?.model)
        assertTrue(parsed.warnings.any { it.contains("delegated") && it.contains("agent") })
    }

    // ──────────────────────────────────────────────
    // Traits with no dispatch key at all are absent, not empty-mapped (parity with traitResources)
    // ──────────────────────────────────────────────

    @Test
    fun `a trait with no dispatch key is absent from traitDispatch`() {
        val parsed =
            parse(
                """
                traits:
                  plain-trait:
                    notes:
                      - key: some-note
                        role: work
                """.trimIndent()
            )

        assertTrue(!parsed.traitDispatch.containsKey("plain-trait"))
    }

    @Test
    fun `a document with no traits key parses traitDispatch to an empty map`() {
        val parsed = parse("work_item_schemas:\n  feature-task:\n    notes: []")

        assertTrue(parsed.traitDispatch.isEmpty())
    }
}
