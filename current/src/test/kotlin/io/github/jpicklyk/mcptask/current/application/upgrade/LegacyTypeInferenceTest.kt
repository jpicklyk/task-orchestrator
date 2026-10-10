package io.github.jpicklyk.mcptask.current.application.upgrade

import io.github.jpicklyk.mcptask.current.application.config.ConfigDocument
import io.github.jpicklyk.mcptask.current.application.config.ConfigLayer
import io.github.jpicklyk.mcptask.current.application.config.ConfigSource
import io.github.jpicklyk.mcptask.current.application.config.LayerBackedGlobalLookup
import io.github.jpicklyk.mcptask.current.application.config.LayeredConfig
import io.github.jpicklyk.mcptask.current.application.config.SchemaResolutionMode
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.DEFAULT_KEY
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.Layer
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.Mode
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.UNTYPED
import io.github.jpicklyk.mcptask.current.domain.model.NoteSchemaEntry
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItemSchema
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests for the frozen `LegacyTypeInference` rule (item 2c67eeca): scenarios S11 (the rule table) and S12
 * (equivalence with the real 3.x tag resolution) of the frozen test-plan, plus probes.
 *
 * Oracles (frozen task-scope 1d and the documented per-mode table in `config-format.md`, "schema_resolution: choosing how
 * the two layers combine", none read from the implementation):
 * - effective mode = the per-root layer's own mode, else the global layer's mode (a global `isolated` counts as `layered`,
 *   there is nothing to isolate from), else `legacy`;
 * - LEGACY is whole-algorithm-first: the per-root layer's first tag match, then its `default`, run to completion before the
 *   global layer's first tag match and then its `default`; a per-root `default` therefore shadows a global tag match;
 * - LAYERED is exact-first: per-root first tag match, then global first tag match, then per-root `default`, then global
 *   `default`;
 * - ISOLATED consults only the per-root layer: first tag match, then its `default`;
 * - the first TAG in tag order wins within a layer (not the first key in key order); a tag literally named `default`
 *   matches as a tag; no match and no default anywhere is `untyped`.
 * S12's oracle is the invariant itself: resolving an item with no type and these tags through the real [LayeredConfig]
 * gives the same schema as resolving it with the inferred type, in every mode.
 */
class LegacyTypeInferenceTest {
    private data class Row(
        val name: String,
        val perRoot: Layer?,
        val global: Layer?,
        val tags: List<String>,
        val expected: String,
    )

    private fun layer(
        vararg keys: String,
        mode: Mode? = null,
    ) = Layer(keys.toSet(), mode)

    private val rows =
        listOf(
            // LEGACY (absent mode everywhere)
            Row("legacy: per-root tag match", layer("t1", DEFAULT_KEY), layer("t2"), listOf("x", "t1"), "t1"),
            Row("legacy: per-root default shadows a global tag match", layer("t1", DEFAULT_KEY), layer("t2"), listOf("t2"), DEFAULT_KEY),
            Row(
                "legacy: global tag match when per-root has neither tag nor default",
                layer("t1"),
                layer("t2", DEFAULT_KEY),
                listOf("x", "t2"),
                "t2"
            ),
            Row("legacy: global default", layer("t1"), layer("t2", DEFAULT_KEY), listOf("x"), DEFAULT_KEY),
            Row("legacy: nothing matches, no default anywhere", layer("t1"), layer("t2"), listOf("x"), UNTYPED),
            Row("legacy: no per-root layer, global tag", null, layer("t2", DEFAULT_KEY), listOf("x", "t2"), "t2"),
            Row("legacy: no per-root layer, global default", null, layer("t2", DEFAULT_KEY), listOf("x"), DEFAULT_KEY),
            Row("legacy: no per-root layer, global without default", null, layer("t2"), listOf("x"), UNTYPED),
            Row("legacy: no layers at all", null, null, listOf("t1"), UNTYPED),
            Row("legacy: empty tags", layer("t1"), layer("t2"), emptyList(), UNTYPED),
            Row("legacy: empty tags still reach a per-root default", layer("t1", DEFAULT_KEY), layer("t2"), emptyList(), DEFAULT_KEY),
            Row("legacy: first tag in tag order wins, not key order", layer("t1", "t3"), null, listOf("t3", "t1"), "t3"),
            Row("legacy: a tag named default matches as a tag", layer("t1"), layer(DEFAULT_KEY), listOf(DEFAULT_KEY), DEFAULT_KEY),
            Row("legacy: duplicate tags change nothing", layer("t1"), null, listOf("t1", "t1", "x"), "t1"),
            // LAYERED
            Row(
                "layered: global tag match beats per-root default",
                layer("t1", DEFAULT_KEY, mode = Mode.LAYERED),
                layer("t2"),
                listOf("t2"),
                "t2"
            ),
            Row(
                "layered: per-root default when no tag matches anywhere",
                layer("t1", DEFAULT_KEY, mode = Mode.LAYERED),
                layer("t2"),
                listOf("x"),
                DEFAULT_KEY
            ),
            Row(
                "layered: global default when per-root has none",
                layer("t1", mode = Mode.LAYERED),
                layer(DEFAULT_KEY),
                listOf("x"),
                DEFAULT_KEY
            ),
            Row("layered: nothing matches", layer("t1", mode = Mode.LAYERED), layer("t2"), listOf("x"), UNTYPED),
            Row(
                "layered: per-root tags are tried before global tags whatever the tag order",
                layer("t1", mode = Mode.LAYERED),
                layer("t2"),
                listOf("t2", "t1"),
                "t1"
            ),
            Row(
                "layered: a tag named default matches as a tag",
                layer("t1", mode = Mode.LAYERED),
                layer(DEFAULT_KEY),
                listOf(DEFAULT_KEY),
                DEFAULT_KEY
            ),
            // ISOLATED
            Row("isolated: per-root tag match", layer("t1", DEFAULT_KEY, mode = Mode.ISOLATED), layer("t2"), listOf("t1"), "t1"),
            Row(
                "isolated: the global layer is never consulted for tags",
                layer("t1", DEFAULT_KEY, mode = Mode.ISOLATED),
                layer("t2"),
                listOf("t2"),
                DEFAULT_KEY
            ),
            Row(
                "isolated: no per-root default and only a global match is untyped",
                layer("t1", mode = Mode.ISOLATED),
                layer("t2", DEFAULT_KEY),
                listOf("t2"),
                UNTYPED
            ),
            // Mode precedence read through behaviour
            Row(
                "mode: a global isolated counts as layered",
                layer("t1", DEFAULT_KEY),
                layer("t2", mode = Mode.ISOLATED),
                listOf("t2"),
                "t2"
            ),
            Row(
                "mode: the per-root legacy beats a global layered",
                layer("t1", DEFAULT_KEY, mode = Mode.LEGACY),
                layer("t2", mode = Mode.LAYERED),
                listOf("t2"),
                DEFAULT_KEY
            ),
            Row(
                "mode: the per-root isolated beats a global layered",
                layer("t1", DEFAULT_KEY, mode = Mode.ISOLATED),
                layer("t2", mode = Mode.LAYERED),
                listOf("t2"),
                DEFAULT_KEY
            ),
            Row(
                "mode: the per-root layered beats a global legacy",
                layer("t1", DEFAULT_KEY, mode = Mode.LAYERED),
                layer("t2", mode = Mode.LEGACY),
                listOf("t2"),
                "t2"
            ),
            Row(
                "mode: a global layered applies when the per-root layer sets none",
                layer("t1", DEFAULT_KEY),
                layer("t2", mode = Mode.LAYERED),
                listOf("t2"),
                "t2"
            ),
            Row(
                "mode: no per-root layer and a global isolated is layered",
                null,
                layer("t2", DEFAULT_KEY, mode = Mode.ISOLATED),
                listOf("t2"),
                "t2"
            ),
        )

    // ---------------------------------------------------------------------------------------------
    // S11 - the rule table
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `S11 infer resolves every row of the documented table`() {
        val mismatches =
            rows.mapNotNull { row ->
                val actual = LegacyTypeInference.infer(row.tags, row.perRoot, row.global)
                if (actual == row.expected) null else "${row.name}: expected '${row.expected}' but got '$actual'"
            }

        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `S11 the table is not vacuous - it exercises every outcome kind`() {
        val outcomes = rows.map { it.expected }.toSet()

        assertTrue(UNTYPED in outcomes && DEFAULT_KEY in outcomes && outcomes.any { it.startsWith("t") }, "outcomes: $outcomes")
    }

    @Test
    fun `S11 the constants carry the reserved spellings`() {
        assertEquals("untyped", UNTYPED)
        assertEquals("default", DEFAULT_KEY)
    }

    @Test
    fun `S11 effectiveMode follows per-root then global (isolated globals become layered) then legacy`() {
        assertEquals(Mode.LEGACY, LegacyTypeInference.effectiveMode(null, null))
        assertEquals(Mode.LEGACY, LegacyTypeInference.effectiveMode(layer("a"), layer("b")))
        assertEquals(Mode.LAYERED, LegacyTypeInference.effectiveMode(layer("a", mode = Mode.LAYERED), layer("b", mode = Mode.ISOLATED)))
        assertEquals(Mode.LAYERED, LegacyTypeInference.effectiveMode(layer("a"), layer("b", mode = Mode.ISOLATED)))
        assertEquals(Mode.LAYERED, LegacyTypeInference.effectiveMode(null, layer("b", mode = Mode.ISOLATED)))
        assertEquals(Mode.LAYERED, LegacyTypeInference.effectiveMode(null, layer("b", mode = Mode.LAYERED)))
        assertEquals(Mode.LEGACY, LegacyTypeInference.effectiveMode(null, layer("b", mode = Mode.LEGACY)))
        assertEquals(Mode.ISOLATED, LegacyTypeInference.effectiveMode(layer("a", mode = Mode.ISOLATED), layer("b", mode = Mode.LAYERED)))
        assertEquals(Mode.ISOLATED, LegacyTypeInference.effectiveMode(layer("a", mode = Mode.ISOLATED), null))
        assertEquals(Mode.LEGACY, LegacyTypeInference.effectiveMode(layer("a", mode = Mode.LEGACY), layer("b", mode = Mode.LAYERED)))
    }

    @Test
    fun `S11 parseMode accepts exactly the three lowercase spellings`() {
        assertEquals(Mode.LEGACY, LegacyTypeInference.parseMode("legacy"))
        assertEquals(Mode.LAYERED, LegacyTypeInference.parseMode("layered"))
        assertEquals(Mode.ISOLATED, LegacyTypeInference.parseMode("isolated"))
        for (bad in listOf(null, "", " ", "LEGACY", "Layered", " legacy", "isolated ", "legacy\n", "none", "isolate")) {
            assertNull(LegacyTypeInference.parseMode(bad), "'$bad'")
        }
    }

    @Test
    fun `S11 parseTags splits on commas, trims, drops blanks and keeps order and duplicates`() {
        assertEquals(emptyList(), LegacyTypeInference.parseTags(null))
        assertEquals(emptyList(), LegacyTypeInference.parseTags(""))
        assertEquals(emptyList(), LegacyTypeInference.parseTags("   "))
        assertEquals(emptyList(), LegacyTypeInference.parseTags(",, ,"))
        assertEquals(listOf("a"), LegacyTypeInference.parseTags("a"))
        assertEquals(listOf("a", "b"), LegacyTypeInference.parseTags("a,b"))
        assertEquals(listOf("a", "b"), LegacyTypeInference.parseTags("a ,b"))
        assertEquals(listOf("a", "b"), LegacyTypeInference.parseTags(" a , b "))
        assertEquals(listOf("a", "b"), LegacyTypeInference.parseTags(",a,,b,"))
        assertEquals(listOf("b", "a", "b"), LegacyTypeInference.parseTags("b,a,b"))
        assertEquals(listOf("A", "a"), LegacyTypeInference.parseTags("A,a"))
    }

    @Test
    fun `S11 a layer with no mode defaults to null`() {
        assertNull(Layer(setOf("a")).mode)
        assertEquals(setOf("a"), Layer(setOf("a")).schemaKeys)
    }

    // ---------------------------------------------------------------------------------------------
    // S12 - equivalence with the real 3.x tag resolution, in every mode
    // ---------------------------------------------------------------------------------------------

    private fun realMode(mode: Mode?): SchemaResolutionMode? =
        when (mode) {
            null -> null
            Mode.LEGACY -> SchemaResolutionMode.LEGACY
            Mode.LAYERED -> SchemaResolutionMode.LAYERED
            Mode.ISOLATED -> SchemaResolutionMode.ISOLATED
        }

    private fun schemasFor(
        layerName: String,
        keys: Set<String>,
    ): Map<String, WorkItemSchema> =
        keys.associateWith { key ->
            // A distinct note per layer and key, so two layers' schemas for the same key are never equal.
            WorkItemSchema(type = key, notes = listOf(NoteSchemaEntry(key = "$layerName-$key", role = Role.QUEUE)))
        }

    private fun realConfig(
        perRoot: Layer?,
        global: Layer?,
    ): LayeredConfig {
        val globalDocument =
            ConfigDocument(
                workItemSchemas = schemasFor("global", global?.schemaKeys.orEmpty()),
                traits = emptyMap(),
                schemaResolution = realMode(global?.mode),
            )
        val perRootLayer =
            perRoot?.let {
                ConfigLayer(
                    ConfigDocument(
                        workItemSchemas = schemasFor("perroot", it.schemaKeys),
                        traits = emptyMap(),
                        schemaResolution = realMode(it.mode),
                    ),
                    "perroot-fp",
                    ConfigSource.PER_ROOT,
                )
            }
        return LayeredConfig(
            if (perRootLayer != null) UUID.randomUUID() else null,
            perRootLayer,
            LayerBackedGlobalLookup(ConfigLayer(globalDocument, "global-fp", ConfigSource.GLOBAL)),
        )
    }

    private fun resolved(
        config: LayeredConfig,
        type: String?,
        tags: List<String>,
    ) = config.resolveBaseSchema(type, tags)?.let { it.source to it.schema }

    @Test
    fun `S12 the inferred type resolves to the same schema as the tags did, for every row in every mode`() {
        val mismatches = mutableListOf<String>()
        for (row in rows) {
            val config = realConfig(row.perRoot, row.global)
            val before = resolved(config, null, row.tags)
            val inferred = LegacyTypeInference.infer(row.tags, row.perRoot, row.global)
            val after = resolved(config, inferred, row.tags)

            if (before != after) mismatches += "${row.name}: before=$before after=$after (inferred '$inferred')"
            if (row.expected == UNTYPED &&
                before != null
            ) {
                mismatches += "${row.name}: an untyped row must have resolved to no schema before, got $before"
            }
            if (row.expected != UNTYPED && before == null) mismatches += "${row.name}: a typed row must have resolved to a schema before"
        }

        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `S12 control - the real resolution distinguishes a per-root default from a global tag match under legacy`() {
        val config = realConfig(layer("t1", DEFAULT_KEY), layer("t2"))

        val result = resolved(config, null, listOf("t2"))

        assertNotNull(result)
        assertEquals(ConfigSource.PER_ROOT, result.first, "the documented legacy rule: a per-root default wins over a global tag match")
        assertEquals(DEFAULT_KEY, result.second.type)
    }
}
