package io.github.jpicklyk.mcptask.current.application.upgrade

import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.DEFAULT_KEY
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.Layer
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.Mode
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.Resolved
import io.github.jpicklyk.mcptask.current.application.upgrade.LegacyTypeInference.UNTYPED
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (item 934b25e4) for `LegacyTypeInference.resolve`, the layer-reporting twin of `infer` that the
 * `pin-backfill` step uses to learn WHICH layer a type resolved in.
 *
 * Oracles (the frozen LEGACY rule documented in `config-format.md`, "schema_resolution: choosing how the two layers
 * combine", and the already-green rows of `LegacyTypeInferenceTest`; none read from the implementation):
 * - the resolved KEY is exactly what `infer` returns for every input, and `resolve` returns null exactly when `infer`
 *   returns the residue `untyped` (task-scope: `infer` becomes `resolve(...)?.key ?: UNTYPED`);
 * - `Resolved.perRoot` is true exactly when the per-root layer supplied the match (its tag match or its `default`), false
 *   when the global layer did;
 * - effective mode = the per-root layer's own mode, else the global layer's (a global `isolated` counts as `layered`),
 *   else `legacy`; LEGACY runs the whole per-root algorithm (tag match, then `default`) before the global layer's;
 *   LAYERED tries per-root tags, global tags, per-root `default`, global `default`; ISOLATED consults the per-root layer
 *   only. Within a layer the first TAG in tag order wins.
 */
class LegacyTypeInferenceResolveTest {
    private data class Row(
        val name: String,
        val perRoot: Layer?,
        val global: Layer?,
        val tags: List<String>,
        val expected: Resolved?
    )

    private fun layer(
        vararg keys: String,
        mode: Mode? = null
    ) = Layer(keys.toSet(), mode)

    private fun root(key: String) = Resolved(key, true)

    private fun server(key: String) = Resolved(key, false)

    private val rows =
        listOf(
            // LEGACY (absent mode everywhere)
            Row("legacy: per-root tag match", layer("t1", DEFAULT_KEY), layer("t2"), listOf("x", "t1"), root("t1")),
            Row(
                "legacy: per-root default shadows a global tag match",
                layer("t1", DEFAULT_KEY),
                layer("t2"),
                listOf("t2"),
                root(DEFAULT_KEY)
            ),
            Row(
                "legacy: global tag when per-root has neither tag nor default",
                layer("t1"),
                layer("t2", DEFAULT_KEY),
                listOf("x", "t2"),
                server("t2")
            ),
            Row("legacy: global default", layer("t1"), layer("t2", DEFAULT_KEY), listOf("x"), server(DEFAULT_KEY)),
            Row("legacy: nothing matches, no default anywhere", layer("t1"), layer("t2"), listOf("x"), null),
            Row("legacy: no per-root layer, global tag", null, layer("t2", DEFAULT_KEY), listOf("x", "t2"), server("t2")),
            Row("legacy: no per-root layer, global default", null, layer("t2", DEFAULT_KEY), listOf("x"), server(DEFAULT_KEY)),
            Row("legacy: no per-root layer, global without default", null, layer("t2"), listOf("x"), null),
            Row("legacy: no layers at all", null, null, listOf("t1"), null),
            Row("legacy: empty tags", layer("t1"), layer("t2"), emptyList(), null),
            Row("legacy: empty tags still reach a per-root default", layer("t1", DEFAULT_KEY), layer("t2"), emptyList(), root(DEFAULT_KEY)),
            Row("legacy: empty tags still reach a global default", layer("t1"), layer("t2", DEFAULT_KEY), emptyList(), server(DEFAULT_KEY)),
            Row("legacy: first tag in tag order wins, not key order", layer("t1", "t3"), null, listOf("t3", "t1"), root("t3")),
            Row(
                "legacy: a per-root tag named default matches as a tag",
                layer("t1", DEFAULT_KEY),
                null,
                listOf(DEFAULT_KEY),
                root(DEFAULT_KEY)
            ),
            Row(
                "legacy: a global tag named default matches as a tag",
                layer("t1"),
                layer(DEFAULT_KEY),
                listOf(DEFAULT_KEY),
                server(DEFAULT_KEY)
            ),
            Row("legacy: duplicate tags change nothing", layer("t1"), null, listOf("t1", "t1", "x"), root("t1")),
            Row(
                "legacy: the same key in both layers is reported as the per-root match",
                layer("t1"),
                layer("t1"),
                listOf("t1"),
                root("t1")
            ),
            // LAYERED
            Row(
                "layered: global tag match beats a per-root default",
                layer("t1", DEFAULT_KEY, mode = Mode.LAYERED),
                layer("t2"),
                listOf("t2"),
                server("t2")
            ),
            Row(
                "layered: per-root default when no tag matches anywhere",
                layer("t1", DEFAULT_KEY, mode = Mode.LAYERED),
                layer("t2"),
                listOf("x"),
                root(DEFAULT_KEY)
            ),
            Row(
                "layered: global default when per-root has none",
                layer("t1", mode = Mode.LAYERED),
                layer(DEFAULT_KEY),
                listOf("x"),
                server(DEFAULT_KEY)
            ),
            Row("layered: nothing matches", layer("t1", mode = Mode.LAYERED), layer("t2"), listOf("x"), null),
            Row(
                "layered: per-root tags are tried before global tags whatever the tag order",
                layer("t1", mode = Mode.LAYERED),
                layer("t2"),
                listOf("t2", "t1"),
                root("t1")
            ),
            // ISOLATED
            Row("isolated: per-root tag match", layer("t1", DEFAULT_KEY, mode = Mode.ISOLATED), layer("t2"), listOf("t1"), root("t1")),
            Row(
                "isolated: the global layer is never consulted for tags",
                layer("t1", DEFAULT_KEY, mode = Mode.ISOLATED),
                layer("t2"),
                listOf("t2"),
                root(DEFAULT_KEY)
            ),
            Row(
                "isolated: no per-root default and only a global match resolves nothing",
                layer("t1", mode = Mode.ISOLATED),
                layer("t2", DEFAULT_KEY),
                listOf("t2"),
                null
            ),
            // Mode precedence read through behaviour
            Row(
                "mode: a global isolated counts as layered",
                layer("t1", DEFAULT_KEY),
                layer("t2", mode = Mode.ISOLATED),
                listOf("t2"),
                server("t2")
            ),
            Row(
                "mode: the per-root legacy beats a global layered",
                layer("t1", DEFAULT_KEY, mode = Mode.LEGACY),
                layer("t2", mode = Mode.LAYERED),
                listOf("t2"),
                root(DEFAULT_KEY)
            ),
            Row(
                "mode: a global layered applies when the per-root layer sets none",
                layer("t1", DEFAULT_KEY),
                layer("t2", mode = Mode.LAYERED),
                listOf("t2"),
                server("t2")
            ),
            Row(
                "mode: no per-root layer and a global isolated is layered",
                null,
                layer("t2", DEFAULT_KEY, mode = Mode.ISOLATED),
                listOf("t2"),
                server("t2")
            )
        )

    @Test
    fun `resolve reports the key and the supplying layer for every row of the documented table`() {
        val mismatches =
            rows.mapNotNull { row ->
                val actual = LegacyTypeInference.resolve(row.tags, row.perRoot, row.global)
                if (actual == row.expected) null else "${row.name}: expected ${row.expected} but got $actual"
            }

        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `infer is resolve key or the untyped residue for every row`() {
        val mismatches =
            rows.mapNotNull { row ->
                val inferred = LegacyTypeInference.infer(row.tags, row.perRoot, row.global)
                val viaResolve = LegacyTypeInference.resolve(row.tags, row.perRoot, row.global)?.key ?: UNTYPED
                if (inferred == viaResolve) null else "${row.name}: infer '$inferred' vs resolve '$viaResolve'"
            }

        assertEquals(emptyList(), mismatches)
    }

    @Test
    fun `the table is not vacuous - both layers, the default key and the null outcome are all exercised`() {
        val outcomes = rows.map { it.expected }

        assertTrue(outcomes.any { it?.perRoot == true && it.key != DEFAULT_KEY }, "a per-root tag match")
        assertTrue(outcomes.any { it?.perRoot == false && it.key != DEFAULT_KEY }, "a global tag match")
        assertTrue(outcomes.any { it?.perRoot == true && it.key == DEFAULT_KEY }, "a per-root default")
        assertTrue(outcomes.any { it?.perRoot == false && it.key == DEFAULT_KEY }, "a global default")
        assertTrue(outcomes.any { it == null }, "an unresolved type")
    }

    @Test
    fun `an empty tag list can only reach a default step`() {
        assertEquals(root(DEFAULT_KEY), LegacyTypeInference.resolve(emptyList(), layer("a", DEFAULT_KEY), layer("b", DEFAULT_KEY)))
        assertEquals(server(DEFAULT_KEY), LegacyTypeInference.resolve(emptyList(), layer("a"), layer("b", DEFAULT_KEY)))
        assertNull(LegacyTypeInference.resolve(emptyList(), layer("a"), layer("b")))
        assertNull(LegacyTypeInference.resolve(emptyList(), null, null))
    }
}
