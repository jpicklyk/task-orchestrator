package io.github.jpicklyk.mcptask.current.architecture

import io.github.jpicklyk.mcptask.current.architecture.PackageCycleRules.Edge
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-tests of [PackageCycleRules] on synthetic input (test-plan scenarios S1-S12): the edge extractor and the
 * two-way ratchet are each proven on hand-built files and graphs, so a green [PackageCycleTest] means "no undeclared
 * cycle", not "a rule that never fires". S13 (the real tree against the empty baseline) is [PackageCycleTest]'s job.
 *
 * Oracle for every expected value: the task-scope rule definition (an edge is an import or a code-line FQN from one
 * `application/<p>/` sub-package into another `application.<q>.<Type>`; an edge is unbaselined when it is not baselined
 * and its target reaches its source over non-baselined edges; a baseline line is stale when it matches no edge or lies
 * on no cycle of the full graph).
 */
class PackageCycleRulesTest {
    private val root = LayeringRules.CURRENT_ROOT
    private val app = "$root.application"

    /** An edge whose `from`/`to` are derived from its path and FQN tail by construction. [tail] is `<q>.<Type>`. */
    private fun edge(
        path: String,
        tail: String
    ): Edge = Edge(path, "$app.$tail", path.removePrefix("application/").substringBefore('/'), tail.substringBefore('.'))

    private fun key(
        path: String,
        tail: String
    ): String = "$path -> $app.$tail"

    private fun edgesOf(
        path: String,
        imports: List<String> = emptyList(),
        text: String = ""
    ): List<Edge> = PackageCycleRules.edges(path, imports, text)

    // ------------------------------------------------------------------ S1 edges: happy

    @Test
    fun `S1 an import into a sibling sub-package is one edge with the file-import key`() {
        val result = edgesOf("application/port/EventStore.kt", imports = listOf("$app.api.PageRequest"))

        assertEquals(listOf(Edge("application/port/EventStore.kt", "$app.api.PageRequest", "port", "api")), result)
        assertEquals("application/port/EventStore.kt -> $app.api.PageRequest", result.single().key())
    }

    // ------------------------------------------------------------------ S2 edges: things that are not edges

    @Test
    fun `S2 a same sub-package import including a nested one is not an edge`() {
        assertEquals(
            emptyList(),
            edgesOf("application/knowledge/link/A.kt", imports = listOf("$app.knowledge.related.X"))
        )
        assertEquals(emptyList(), edgesOf("application/api/A.kt", imports = listOf("$app.api.X")))
    }

    @Test
    fun `S2 an application-root declaration is not an edge`() {
        assertEquals(emptyList(), edgesOf("application/api/A.kt", imports = listOf("$app.BuildInfo")))
    }

    @Test
    fun `S2 a file outside application and the application root file are out of scope`() {
        assertEquals(emptyList(), edgesOf("interfaces/mcp/A.kt", imports = listOf("$app.api.X")))
        assertEquals(emptyList(), edgesOf("application/BuildInfo.kt", imports = listOf("$app.api.X")))
        assertEquals(emptyList(), edgesOf("domain/model/A.kt", imports = listOf("$app.api.X")))
    }

    @Test
    fun `S2 a look-alike package prefix and other layers are not edges`() {
        assertEquals(emptyList(), edgesOf("application/port/A.kt", imports = listOf("$root.applicationx.api.X")))
        assertEquals(emptyList(), edgesOf("application/port/A.kt", imports = listOf("$root.domain.model.X")))
        assertEquals(emptyList(), edgesOf("application/port/A.kt", imports = listOf("kotlinx.serialization.json.JsonObject")))
    }

    // ------------------------------------------------------------------ S3 edges: comment and string handling

    @Test
    fun `S3 comment lines and string literals do not make edges but a code-line FQN does`() {
        val text =
            """
            /**
             * See [$app.api.A] for the shape.
             */
            // $app.api.B is mentioned here
            private val log = getLogger("$app.api.C")
            val p: $app.api.ListPage<Int> = TODO()
            """.trimIndent()

        val result = edgesOf("application/config/X.kt", text = text)

        assertEquals(listOf("$app.api.ListPage"), result.map { it.importedFqName })
        assertEquals(listOf("config"), result.map { it.from })
        assertEquals(listOf("api"), result.map { it.to })
    }

    // ------------------------------------------------------------------ S4 edges: path, wildcard and duplicate shapes

    @Test
    fun `S4 a backslash path keys with forward slashes`() {
        val result = edgesOf("application\\read\\W.kt", imports = listOf("$app.api.ListPage"))

        assertEquals(listOf("application/read/W.kt -> $app.api.ListPage"), result.map { it.key() })
        assertEquals(listOf("read"), result.map { it.from })
        assertEquals(listOf("api"), result.map { it.to })
    }

    @Test
    fun `S4 a wildcard import is an edge to its sub-package`() {
        val result = edgesOf("application/port/A.kt", imports = listOf("$app.api.*"))

        assertEquals(1, result.size)
        assertEquals("port", result.single().from)
        assertEquals("api", result.single().to)
    }

    @Test
    fun `S4 the same FQN imported and also referenced yields one key`() {
        val fq = "$app.api.PageRequest"
        val result = edgesOf("application/port/A.kt", imports = listOf(fq), text = "val p: $fq = TODO()")

        assertEquals(setOf("application/port/A.kt -> $fq"), result.map { it.key() }.toSet())
    }

    // ------------------------------------------------------------------ edge probes

    @Test
    fun `probe an uppercase segment after application is not a sub-package`() {
        assertEquals(emptyList(), edgesOf("application/port/A.kt", imports = listOf("$app.Api.X")))
    }

    @Test
    fun `probe a bare sub-package name with no further segment is not an edge`() {
        assertEquals(emptyList(), edgesOf("application/port/A.kt", imports = listOf("$app.api")))
    }

    @Test
    fun `probe a deeply nested file belongs to its first sub-package segment`() {
        val result = edgesOf("application/knowledge/link/x/Y.kt", imports = listOf("$app.api.Z"))

        assertEquals(listOf("knowledge"), result.map { it.from })
        assertEquals(listOf("api"), result.map { it.to })
    }

    @Test
    fun `probe one file reaching two sub-packages yields one edge per target`() {
        val result = edgesOf("application/config/X.kt", imports = listOf("$app.api.A", "$app.service.B"))

        assertEquals(setOf("api", "service"), result.map { it.to }.toSet())
        assertEquals(setOf("config"), result.map { it.from }.toSet())
    }

    @Test
    fun `probe an empty file has no edges`() {
        assertEquals(emptyList(), edgesOf("application/config/X.kt"))
    }

    // ------------------------------------------------------------------ S5 diff: failure, two-way cycle

    @Test
    fun `S5 a two-package cycle with nothing baselined reports both edges`() {
        val edges = listOf(edge("application/port/A.kt", "api.X"), edge("application/api/B.kt", "port.Y"))

        val diff = PackageCycleRules.diff(edges, emptySet())

        assertEquals(setOf(key("application/port/A.kt", "api.X"), key("application/api/B.kt", "port.Y")), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
    }

    @Test
    fun `S5 baselining one edge of the two-package cycle makes it clean`() {
        val edges = listOf(edge("application/port/A.kt", "api.X"), edge("application/api/B.kt", "port.Y"))

        val diff = PackageCycleRules.diff(edges, setOf(key("application/port/A.kt", "api.X")))

        assertEquals(emptySet(), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
        assertTrue(diff.clean)
    }

    // ------------------------------------------------------------------ S6 diff: three-package cycle

    @Test
    fun `S6 a three-package cycle with nothing baselined reports all three edges`() {
        val diff = PackageCycleRules.diff(threeCycle(), emptySet())

        assertEquals(
            setOf(
                key("application/api/A.kt", "service.X"),
                key("application/service/B.kt", "port.Y"),
                key("application/port/C.kt", "api.Z")
            ),
            diff.unbaselined
        )
        assertEquals(emptySet(), diff.stale)
    }

    @Test
    fun `S6 baselining any single edge of the three-package cycle makes it clean`() {
        val keys =
            listOf(
                key("application/api/A.kt", "service.X"),
                key("application/service/B.kt", "port.Y"),
                key("application/port/C.kt", "api.Z")
            )
        for (baselined in keys) {
            val diff = PackageCycleRules.diff(threeCycle(), setOf(baselined))
            assertTrue(diff.clean, "baselining $baselined should make the cycle clean but got $diff")
        }
    }

    private fun threeCycle() =
        listOf(
            edge("application/api/A.kt", "service.X"),
            edge("application/service/B.kt", "port.Y"),
            edge("application/port/C.kt", "api.Z")
        )

    // ------------------------------------------------------------------ S7 diff: baseline is per file-import

    @Test
    fun `S7 a baseline line covers one file-import not the whole package edge`() {
        val edges =
            listOf(
                edge("application/port/A.kt", "api.X"),
                edge("application/port/B.kt", "api.Y"),
                edge("application/api/C.kt", "port.Z")
            )

        val diff = PackageCycleRules.diff(edges, setOf(key("application/port/A.kt", "api.X")))

        assertEquals(setOf(key("application/port/B.kt", "api.Y"), key("application/api/C.kt", "port.Z")), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
    }

    // ------------------------------------------------------------------ S8 diff: acyclic graph

    @Test
    fun `S8 an acyclic package graph is clean with an empty baseline`() {
        val edges =
            listOf(
                edge("application/api/A.kt", "service.X"),
                edge("application/service/B.kt", "port.Y"),
                edge("application/api/C.kt", "port.Z")
            )

        val diff = PackageCycleRules.diff(edges, emptySet())

        assertEquals(emptySet(), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
        assertTrue(diff.clean)
    }

    // ------------------------------------------------------------------ S9 / S10 diff: stale lines

    @Test
    fun `S9 a baseline line that matches no edge is stale`() {
        val edges = listOf(edge("application/api/A.kt", "service.X"))
        val ghost = key("application/port/Gone.kt", "api.Old")

        val diff = PackageCycleRules.diff(edges, setOf(ghost))

        assertEquals(setOf(ghost), diff.stale)
        assertEquals(emptySet(), diff.unbaselined)
    }

    @Test
    fun `S10 a baselined edge that lies on no cycle is stale`() {
        val readEdge = edge("application/read/W.kt", "api.L")
        val diff = PackageCycleRules.diff(listOf(readEdge), setOf(key("application/read/W.kt", "api.L")))

        assertEquals(setOf(key("application/read/W.kt", "api.L")), diff.stale)
        assertEquals(emptySet(), diff.unbaselined)
    }

    // ------------------------------------------------------------------ S11 / S12 diff: baselined edges on a cycle

    @Test
    fun `S11 two baselined edges on a three-package cycle are not stale and the free edge is not reported`() {
        val edges =
            listOf(
                edge("application/telemetry/T.kt", "port.C"),
                edge("application/port/E.kt", "api.P"),
                edge("application/api/K.kt", "telemetry.F")
            )
        val baseline = setOf(key("application/telemetry/T.kt", "port.C"), key("application/port/E.kt", "api.P"))

        val diff = PackageCycleRules.diff(edges, baseline)

        assertEquals(emptySet(), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
        assertTrue(diff.clean)
    }

    @Test
    fun `S12 a new edge whose only cycle runs through a baselined edge passes as an accepted limitation`() {
        val edges =
            listOf(
                edge("application/telemetry/T.kt", "port.C"),
                edge("application/port/N.kt", "telemetry.F")
            )

        val diff = PackageCycleRules.diff(edges, setOf(key("application/telemetry/T.kt", "port.C")))

        assertEquals(emptySet(), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
        assertTrue(diff.clean)
    }

    // ------------------------------------------------------------------ diff probes

    @Test
    fun `probe duplicate edges in the input do not change the verdict`() {
        val a = edge("application/port/A.kt", "api.X")
        val b = edge("application/api/B.kt", "port.Y")

        val unbaselined = PackageCycleRules.diff(listOf(a, a, b), emptySet())
        assertEquals(setOf(key("application/port/A.kt", "api.X"), key("application/api/B.kt", "port.Y")), unbaselined.unbaselined)

        val baselined = PackageCycleRules.diff(listOf(a, a, b), setOf(key("application/port/A.kt", "api.X")))
        assertTrue(baselined.clean, baselined.toString())
    }

    @Test
    fun `probe input order does not change the diff`() {
        val edges =
            listOf(
                edge("application/port/A.kt", "api.X"),
                edge("application/port/B.kt", "api.Y"),
                edge("application/api/C.kt", "port.Z"),
                edge("application/read/W.kt", "api.L")
            )
        val baseline = setOf(key("application/port/A.kt", "api.X"), key("application/ghost/G.kt", "api.Q"))

        val forward = PackageCycleRules.diff(edges, baseline)

        assertEquals(forward, PackageCycleRules.diff(edges.reversed(), baseline))
        assertEquals(forward, PackageCycleRules.diff(edges.shuffled(java.util.Random(7)), baseline))
        assertEquals(setOf(key("application/ghost/G.kt", "api.Q")), forward.stale)
    }

    @Test
    fun `probe no edges and no baseline is clean`() {
        val diff = PackageCycleRules.diff(emptyList(), emptySet())

        assertEquals(emptySet(), diff.unbaselined)
        assertEquals(emptySet(), diff.stale)
        assertTrue(diff.clean)
    }

    @Test
    fun `probe no edges with a non-empty baseline marks every line stale`() {
        val baseline = setOf(key("application/port/A.kt", "api.X"), key("application/api/B.kt", "port.Y"))

        val diff = PackageCycleRules.diff(emptyList(), baseline)

        assertEquals(baseline, diff.stale)
        assertEquals(emptySet(), diff.unbaselined)
    }

    @Test
    fun `probe a stale line and an unbaselined cycle are reported together`() {
        val edges = listOf(edge("application/port/A.kt", "api.X"), edge("application/api/B.kt", "port.Y"))
        val ghost = key("application/read/W.kt", "api.L")

        val diff = PackageCycleRules.diff(edges, setOf(ghost))

        assertEquals(setOf(key("application/port/A.kt", "api.X"), key("application/api/B.kt", "port.Y")), diff.unbaselined)
        assertEquals(setOf(ghost), diff.stale)
    }

    @Test
    fun `probe baselining both edges of a two-package cycle is clean and neither line is stale`() {
        val edges = listOf(edge("application/port/A.kt", "api.X"), edge("application/api/B.kt", "port.Y"))
        val baseline = setOf(key("application/port/A.kt", "api.X"), key("application/api/B.kt", "port.Y"))

        val diff = PackageCycleRules.diff(edges, baseline)

        assertTrue(diff.clean, diff.toString())
    }
}
