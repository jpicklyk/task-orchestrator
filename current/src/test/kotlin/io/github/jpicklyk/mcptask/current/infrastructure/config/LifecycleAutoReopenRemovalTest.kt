package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.LifecycleMode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent test authorship for item 2cef6ca4 (needs-test-author) — the `auto-reopen` removal
 * decision at the [YamlSchemaParser] surface (S1-S3). The `YamlNoteSchemaService`-level
 * integration check (declared edit to `YamlNoteSchemaServiceTest.kt`) and the delete/lease
 * scenarios (S4-S11, MCP + REST) live elsewhere; this file is the parser-level unit surface plus
 * the enum-level surface, all EXISTING-SURFACE per test-plan (a plain revert compiles and yields
 * behavioural red — `LifecycleMode.AUTO_REOPEN` and the `YamlSchemaParser` special-case both
 * already exist pre-fix, the fix only deletes/rewires them).
 *
 * Oracles (frozen in test-plan note b9109cc0 / diagnosis note ca116121, before implementation was
 * read):
 *  [CF] `config-format.md` "Lifecycle Modes": an `auto-reopen` value is no longer a distinct
 *       mode — it is treated as `auto` (with a load warning); the mode enum is now
 *       {auto, manual, permanent}.
 *  Exact warning text (from the dispatch declarations, verbatim): "Schema '<schemaName>':
 *       lifecycle 'auto-reopen' was removed (it behaved exactly like 'auto'); treating as
 *       'auto'" — distinct from the pre-existing generic "Schema '<schemaName>' has invalid
 *       lifecycle value '<raw>'; defaulting to AUTO" used for every OTHER unrecognized value.
 *
 * Parses real YAML text via [SafeConstructor] (mirroring [YamlSchemaParserRoleWarningTest] and
 * how the production loaders parse the same document) rather than hand-building
 * `Map<String, Any>` literals.
 */
class LifecycleAutoReopenRemovalTest {
    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    // ──────────────────────────────────────────────
    // S1 — happy: auto-reopen (any casing/separator) resolves to AUTO plus the removal warning
    // ──────────────────────────────────────────────

    @Test
    fun `S1 lifecycle auto-reopen in three casings all resolve to AUTO with one removal warning per schema`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  epic:
                    lifecycle: auto-reopen
                    notes: []
                  saga:
                    lifecycle: AUTO_REOPEN
                    notes: []
                  quest:
                    lifecycle: Auto-Reopen
                    notes: []
                """.trimIndent(),
            )

        assertEquals(LifecycleMode.AUTO, parsed.workItemSchemas["epic"]!!.lifecycleMode)
        assertEquals(LifecycleMode.AUTO, parsed.workItemSchemas["saga"]!!.lifecycleMode)
        assertEquals(LifecycleMode.AUTO, parsed.workItemSchemas["quest"]!!.lifecycleMode)

        assertEquals(3, parsed.warnings.size, "expected exactly one removal warning per schema: ${parsed.warnings}")
        assertEquals(
            "Schema 'epic': lifecycle 'auto-reopen' was removed (it behaved exactly like 'auto'); treating as 'auto'",
            parsed.warnings.single { it.contains("epic") },
        )
        assertEquals(
            "Schema 'saga': lifecycle 'auto-reopen' was removed (it behaved exactly like 'auto'); treating as 'auto'",
            parsed.warnings.single { it.contains("saga") },
        )
        assertEquals(
            "Schema 'quest': lifecycle 'auto-reopen' was removed (it behaved exactly like 'auto'); treating as 'auto'",
            parsed.warnings.single { it.contains("quest") },
        )
    }

    // ──────────────────────────────────────────────
    // S2 — failure: a genuinely-unknown value keeps the pre-existing generic warning; `auto`
    // itself produces no lifecycle warning at all
    // ──────────────────────────────────────────────

    @Test
    fun `S2 an unrecognized lifecycle value other than auto-reopen keeps the pre-existing generic warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    lifecycle: bogus
                    notes: []
                """.trimIndent(),
            )

        assertEquals(LifecycleMode.AUTO, parsed.workItemSchemas["feature-task"]!!.lifecycleMode)
        assertEquals(1, parsed.warnings.size)
        val warning = parsed.warnings.single()
        assertTrue(warning.contains("bogus"), "warning should name the bad value: $warning")
        assertTrue(warning.contains("feature-task"), "warning should name the schema: $warning")
        assertTrue(
            !warning.contains("auto-reopen"),
            "a generic invalid value must NOT get the auto-reopen-specific removal wording: $warning",
        )
    }

    @Test
    fun `S2b lifecycle auto produces no lifecycle warning`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    lifecycle: auto
                    notes: []
                """.trimIndent(),
            )

        assertEquals(LifecycleMode.AUTO, parsed.workItemSchemas["feature-task"]!!.lifecycleMode)
        assertEquals(0, parsed.warnings.size, "an explicit, valid 'auto' must not warn: ${parsed.warnings}")
    }

    // ──────────────────────────────────────────────
    // S3 — the enum itself: AUTO_REOPEN no longer exists; fromString("auto-reopen") == null
    // ──────────────────────────────────────────────

    @Test
    fun `S3 LifecycleMode has exactly AUTO, MANUAL, PERMANENT`() {
        assertEquals(
            listOf("AUTO", "MANUAL", "PERMANENT"),
            LifecycleMode.entries.map { it.name },
        )
    }

    @Test
    fun `S3b LifecycleMode fromString returns null for auto-reopen in any casing`() {
        assertNull(LifecycleMode.fromString("auto-reopen"))
        assertNull(LifecycleMode.fromString("AUTO_REOPEN"))
        assertNull(LifecycleMode.fromString("Auto-Reopen"))
    }
}
