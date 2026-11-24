package io.github.jpicklyk.mcptask.current.config.internal.app

import io.github.jpicklyk.mcptask.current.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.config.ConfigDocumentParser
import io.github.jpicklyk.mcptask.current.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.config.ConfigSource
import io.github.jpicklyk.mcptask.current.config.ConfigValidationResult
import io.github.jpicklyk.mcptask.current.config.ConfigValidator
import io.github.jpicklyk.mcptask.current.config.GlobalConfigSource
import io.github.jpicklyk.mcptask.current.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.config.ParseMode
import io.github.jpicklyk.mcptask.current.config.internal.wire.NetworkntConfigStructuralValidator
import io.github.jpicklyk.mcptask.current.config.internal.wire.YamlConfigDocumentParser
import io.github.jpicklyk.mcptask.current.model.config.ConfigScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests T6, T7 and T11 of item 6aa122da, plus the config side of AC6: the `builds` flag on a schema and on a trait.
 *
 * Oracle: task-scope R3. An item builds unless its base schema or any of its traits (item traits, then `default_traits`) sets
 * `builds: false`; false wins, so `builds: true` changes nothing; unknown traits are skipped. A per-root trait entry replaces
 * the server's `builds` as it replaces any other dimension: when it omits the key, the value is unset. A non-boolean value is a
 * soft finding (an error under STRICT) and is ignored.
 */
class BuildsConfigTest {
    private fun parse(
        yaml: String,
        mode: ParseMode = ParseMode.MIGRATION
    ): ConfigDocument {
        val parsed = YamlConfigDocumentParser.parse(yaml, warnOnMissingSchemas = false, mode = mode)
        return assertIs<ConfigDocumentParser.Outcome.Parsed>(parsed, "fixture must parse: $parsed").document
    }

    private fun layered(
        serverYaml: String,
        perRootYaml: String? = null
    ): LayeredConfig =
        LayeredConfig(
            UUID.randomUUID(),
            perRootYaml?.let { ConfigLayer(parse(it), "per-root-fp", ConfigSource.PER_ROOT) },
            LayerBackedGlobalLookup(ConfigLayer(parse(serverYaml), "server-fp", ConfigSource.GLOBAL))
        )

    private fun builds(
        config: LayeredConfig,
        type: String,
        traits: List<String> = emptyList()
    ): Boolean {
        val base = checkNotNull(config.resolveTypeSchema(type)) { "type $type resolves" }.schema
        return TraitResolver(config).mergeSchema(base, traits).builds
    }

    private fun validate(yaml: String): ConfigValidationResult =
        ConfigValidator(
            NetworkntConfigStructuralValidator(),
            GlobalConfigSource {
                null
            }
        ).validate(yaml, YamlConfigDocumentParser, ConfigScope.ROOT)

    private val serverYaml =
        "work_item_schemas:\n" +
            "  build:\n    notes: []\n" +
            "  plan:\n    builds: false\n    notes: []\n" +
            "  built:\n    builds: true\n    notes: []\n" +
            "  defaulted:\n    default_traits: [no-build]\n    notes: []\n" +
            "traits:\n" +
            "  no-build:\n    builds: false\n" +
            "  does-build:\n    builds: true\n" +
            "  notes-only:\n    notes:\n      - key: n\n        role: queue\n"

    @Test
    fun `R3 a schema builds by default, builds false clears it and builds true changes nothing`() {
        val config = layered(serverYaml)
        assertEquals(true, builds(config, "build"))
        assertEquals(false, builds(config, "plan"))
        assertEquals(true, builds(config, "built"))
        assertEquals(false, builds(config, "plan", listOf("does-build")), "false wins over a trait's true")
    }

    @Test
    fun `T6 a trait carrying only builds false, with no notes or seats, makes the item not build`() {
        val config = layered(serverYaml)
        assertEquals(false, builds(config, "build", listOf("no-build")))
        assertEquals(false, builds(config, "defaulted"), "a default trait applies the same way")
        assertEquals(true, builds(config, "build", listOf("notes-only")), "control: a trait without builds leaves it true")
        assertEquals(true, builds(config, "build", listOf("unknown-trait")), "an unknown trait is skipped")
    }

    @Test
    fun `T7 a per-root trait entry without builds shadows the server's false, so the item builds`() {
        val perRoot = "traits:\n  no-build:\n    notes:\n      - key: m\n        role: queue\n"
        val config = layered(serverYaml, perRoot)
        assertNull(config.traitBuilds("no-build"), "the per-root entry replaces every dimension, builds included")
        assertEquals(true, builds(config, "build", listOf("no-build")))
        assertEquals(false, layered(serverYaml).traitBuilds("no-build"), "control: the server entry alone says false")
    }

    @Test
    fun `T11 a non-boolean builds is a soft finding and is ignored`() {
        val yaml =
            "work_item_schemas:\n  odd:\n    builds: maybe\n    notes: []\n" +
                "traits:\n  odd-trait:\n    builds: 3\n"
        val doc = parse(yaml)
        assertEquals(true, doc.workItemSchemas.getValue("odd").builds, "the bad value is ignored")
        assertNull(doc.traitBuilds["odd-trait"])
        assertTrue(doc.warnings.any { "builds" in it && "odd" in it }, "${doc.warnings}")
        assertTrue(doc.warnings.any { "builds" in it && "odd-trait" in it }, "${doc.warnings}")

        val strict = parse(yaml, ParseMode.STRICT)
        assertTrue(strict.errors.any { it.path == "/work_item_schemas/odd/builds" }, "${strict.errors}")
        assertTrue(strict.errors.any { it.path == "/traits/odd-trait/builds" }, "${strict.errors}")
    }

    @Test
    fun `AC6 config validate accepts builds on a schema and on a trait and rejects a non-boolean`() {
        val ok = validate("work_item_schemas:\n  plan:\n    builds: false\n    notes: []\ntraits:\n  t:\n    builds: false\n")
        assertEquals(emptyList(), ok.errors)
        assertTrue(ok.isValid)

        val bad = validate("work_item_schemas:\n  plan:\n    builds: \"no\"\n    notes: []\n")
        assertTrue(bad.errors.any { it.path.startsWith("/work_item_schemas/plan/builds") }, "${bad.errors}")
    }
}
