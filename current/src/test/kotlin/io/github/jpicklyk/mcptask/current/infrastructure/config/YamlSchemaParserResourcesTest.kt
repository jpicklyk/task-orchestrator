package io.github.jpicklyk.mcptask.current.infrastructure.config

import io.github.jpicklyk.mcptask.current.domain.model.ResourceMode
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [YamlSchemaParser]'s `resources:` parsing — both the per-trait
 * `traits.<name>.resources:` requirement lists (short/long form) and the top-level `resources:`
 * registry.
 *
 * Parses real YAML text via [SafeConstructor] (mirroring how [YamlWorkItemSchemaService] and
 * [PerRootConfigService] parse the same document) rather than hand-building `Map<String, Any>`
 * literals, so these tests exercise the exact same SnakeYAML type coercion production code sees.
 */
class YamlSchemaParserResourcesTest {
    private fun parse(yaml: String): YamlSchemaParser.ParsedConfig {
        @Suppress("UNCHECKED_CAST")
        val root = Yaml(SafeConstructor(LoaderOptions())).load<Map<String, Any>>(yaml) ?: emptyMap()
        return YamlSchemaParser.parseRoot(root, warnOnMissingSchemas = false)
    }

    // ──────────────────────────────────────────────
    // Short form: bare key strings
    // ──────────────────────────────────────────────

    @Test
    fun `short form resources list parses as exclusive mode with null ttl`() {
        val parsed =
            parse(
                """
                traits:
                  needs-staging-db:
                    resources: [staging-db-credential, staging-env]
                """.trimIndent()
            )

        val reqs = parsed.traitResources["needs-staging-db"]
        assertEquals(2, reqs?.size)
        assertEquals("staging-db-credential", reqs!![0].key)
        assertEquals(ResourceMode.EXCLUSIVE, reqs[0].mode)
        assertNull(reqs[0].ttlSeconds)
        assertEquals("staging-env", reqs[1].key)
    }

    // ──────────────────────────────────────────────
    // Long form: maps with key/mode/ttlSeconds
    // ──────────────────────────────────────────────

    @Test
    fun `long form resources entry parses mode and ttlSeconds`() {
        val parsed =
            parse(
                """
                traits:
                  needs-staging-db:
                    resources:
                      - key: staging-db-credential
                        mode: exclusive
                        ttlSeconds: 900
                """.trimIndent()
            )

        val req = parsed.traitResources["needs-staging-db"]!!.single()
        assertEquals("staging-db-credential", req.key)
        assertEquals(ResourceMode.EXCLUSIVE, req.mode)
        assertEquals(900, req.ttlSeconds)
    }

    @Test
    fun `long form mode is case-insensitive`() {
        val parsed =
            parse(
                """
                traits:
                  needs-audit-log:
                    resources:
                      - key: audit-log
                        mode: Advisory
                """.trimIndent()
            )

        val req = parsed.traitResources["needs-audit-log"]!!.single()
        assertEquals(ResourceMode.ADVISORY, req.mode)
    }

    @Test
    fun `unknown mode falls back to exclusive with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  needs-x:
                    resources:
                      - key: some-resource
                        mode: bogus-mode
                """.trimIndent()
            )

        val req = parsed.traitResources["needs-x"]!!.single()
        assertEquals(ResourceMode.EXCLUSIVE, req.mode)
        assertTrue(parsed.warnings.any { it.contains("unknown mode") && it.contains("bogus-mode") })
    }

    @Test
    fun `long form entry with no mode defaults to exclusive`() {
        val parsed =
            parse(
                """
                traits:
                  needs-x:
                    resources:
                      - key: some-resource
                """.trimIndent()
            )

        val req = parsed.traitResources["needs-x"]!!.single()
        assertEquals(ResourceMode.EXCLUSIVE, req.mode)
        assertNull(req.ttlSeconds)
    }

    // ──────────────────────────────────────────────
    // Malformed sections — non-fatal
    // ──────────────────────────────────────────────

    @Test
    fun `malformed resources section (not a list) is skipped with a warning, load continues`() {
        val parsed =
            parse(
                """
                work_item_schemas:
                  feature-task:
                    notes: []
                traits:
                  needs-x:
                    resources: "not-a-list"
                """.trimIndent()
            )

        assertTrue(parsed.traitResources["needs-x"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("malformed 'resources' section") })
        // The rest of the config still loaded.
        assertTrue(parsed.workItemSchemas.containsKey("feature-task"))
    }

    @Test
    fun `resources list entry missing key is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  needs-x:
                    resources:
                      - mode: advisory
                """.trimIndent()
            )

        assertTrue(parsed.traitResources["needs-x"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("missing required field 'key'") })
    }

    @Test
    fun `invalid resource key is skipped with a warning`() {
        val parsed =
            parse(
                """
                traits:
                  needs-x:
                    resources: ["Invalid Key With Spaces"]
                """.trimIndent()
            )

        assertTrue(parsed.traitResources["needs-x"].isNullOrEmpty())
        assertTrue(parsed.warnings.any { it.contains("invalid key") })
    }

    // ──────────────────────────────────────────────
    // Top-level registry
    // ──────────────────────────────────────────────

    @Test
    fun `registry parses description defaultTtlSeconds and maxHolders`() {
        val parsed =
            parse(
                """
                resources:
                  staging-db-credential:
                    description: "Shared staging DB credential"
                    defaultTtlSeconds: 1800
                    maxHolders: 1
                """.trimIndent()
            )

        val def = parsed.resourceRegistry["staging-db-credential"]!!
        assertEquals("Shared staging DB credential", def.description)
        assertEquals(1800, def.defaultTtlSeconds)
        assertEquals(1, def.maxHolders)
    }

    @Test
    fun `registry entry with maxHolders 1 loads without error`() {
        val parsed =
            parse(
                """
                resources:
                  ok-resource:
                    maxHolders: 1
                """.trimIndent()
            )

        assertTrue(parsed.resourceRegistry.containsKey("ok-resource"))
        assertTrue(parsed.warnings.none { it.contains("ok-resource") })
    }

    @Test
    fun `registry entry with maxHolders greater than 1 is rejected as an error`() {
        val parsed =
            parse(
                """
                resources:
                  fan-in-resource:
                    maxHolders: 2
                """.trimIndent()
            )

        assertTrue(!parsed.resourceRegistry.containsKey("fan-in-resource"))
        assertTrue(
            parsed.warnings.any { it.contains("fan-in-resource") && it.contains("maxHolders > 1 not yet supported") }
        )
    }

    @Test
    fun `registry entry budget keys are parsed and warned but not stored`() {
        val parsed =
            parse(
                """
                resources:
                  budget-resource:
                    budgetLimit: 100
                    budgetWindowSeconds: 3600
                """.trimIndent()
            )

        assertTrue(parsed.resourceRegistry.containsKey("budget-resource"))
        assertTrue(parsed.warnings.any { it.contains("budgetLimit") && it.contains("reserved for future use") })
        assertTrue(parsed.warnings.any { it.contains("budgetWindowSeconds") && it.contains("reserved for future use") })
    }

    // ──────────────────────────────────────────────
    // Undeclared-key and fan-out warnings
    // ──────────────────────────────────────────────

    @Test
    fun `trait referencing a key absent from the registry is warned but still honored`() {
        val parsed =
            parse(
                """
                traits:
                  needs-x:
                    resources: [unregistered-key]
                """.trimIndent()
            )

        // Warned...
        assertTrue(
            parsed.warnings.any {
                it.contains("undeclared resource") && it.contains("unregistered-key") && it.contains("3600")
            }
        )
        // ...but still present (warn-and-honor, never skipped for being undeclared).
        assertEquals("unregistered-key", parsed.traitResources["needs-x"]!!.single().key)
    }

    @Test
    fun `a resource key declared by 3 or more traits triggers a fan-out warning`() {
        val parsed =
            parse(
                """
                resources:
                  shared-key:
                    description: "shared"
                traits:
                  trait-a:
                    resources: [shared-key]
                  trait-b:
                    resources: [shared-key]
                  trait-c:
                    resources: [shared-key]
                """.trimIndent()
            )

        assertTrue(parsed.warnings.any { it.contains("shared-key") && it.contains("3 traits") })
    }

    @Test
    fun `a resource key declared by only 2 traits does not trigger a fan-out warning`() {
        val parsed =
            parse(
                """
                resources:
                  shared-key:
                    description: "shared"
                traits:
                  trait-a:
                    resources: [shared-key]
                  trait-b:
                    resources: [shared-key]
                """.trimIndent()
            )

        assertTrue(parsed.warnings.none { it.contains("fan-out") })
    }

    // ──────────────────────────────────────────────
    // Traits with no resources key are absent, not empty-listed
    // ──────────────────────────────────────────────

    @Test
    fun `trait with no resources key is absent from traitResources`() {
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

        assertTrue(!parsed.traitResources.containsKey("plain-trait"))
    }

    @Test
    fun `document with no traits or resources keys parses to empty maps`() {
        val parsed = parse("work_item_schemas:\n  feature-task:\n    notes: []")

        assertTrue(parsed.traitResources.isEmpty())
        assertTrue(parsed.resourceRegistry.isEmpty())
    }
}
