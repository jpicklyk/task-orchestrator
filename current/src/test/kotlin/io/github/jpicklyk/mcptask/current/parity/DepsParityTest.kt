package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.Timeout
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 54f3a1ce, Q12) for `deps_read` / `deps_write`, scenarios S1-S22 of the frozen
 * test-plan. Every catalog scenario of [DepsParityScenarios] runs through [ParityHarness] (the same arguments over MCP and over
 * `POST /api/v1/ops/{tool}`, byte-equal bodies, equal database snapshots, literal status oracles); the tests below read the
 * outcome of selected runs for the facts the catalog's substring checks cannot state (stored rows, event rows, ordering,
 * levels).
 *
 * Oracles: the response-envelope spec (statuses, shapes), the `DependencyCommandService` policy and `Dependency` invariants
 * (declared KDoc), the task-scope list/graph/delete rules (order created_at ASC then id ASC, exact total, level = longest
 * blocker path, RELATES_TO ignored by the graph, cyclic nodes carry no level), `EventCoverageTest`'s dependency event contract
 * (`dependency.added` / `dependency.removed` per row, entity id = the edge id, cause `explicit`). Generated ids reach this
 * file as positional tokens (`<ID1>`) because the harness normalizes them per leg.
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
class DepsParityTest {
    private val harness = ParityHarness(ToolRegistry(buildToolSpecs()))

    private val a = DepsParityScenarios.a
    private val b = DepsParityScenarios.b
    private val c = DepsParityScenarios.c
    private val d = DepsParityScenarios.d
    private val d1 = DepsParityScenarios.d1
    private val d2 = DepsParityScenarios.d2
    private val d3 = DepsParityScenarios.d3
    private val d4 = DepsParityScenarios.d4
    private val d5 = DepsParityScenarios.d5

    private fun edge(
        id: UUID,
        from: UUID,
        to: UUID,
        type: DependencyType = DependencyType.BLOCKS
    ) = DepsParityScenarios.Edge(id, from, to, type)

    private fun execute(
        name: String,
        tool: String,
        member: String,
        arguments: String,
        status: Int = 200,
        code: String? = null,
        token: ParityToken = DepsParityScenarios.admin,
        vararg edges: DepsParityScenarios.Edge
    ): ParityOutcome =
        harness.run(
            ParityScenario(
                name = name,
                tool = tool,
                member = member,
                kind = ParityKind.OTHER,
                token = token,
                arguments = arguments,
                expectStatus = status,
                expectCode = code,
                seed = DepsParityScenarios.tree(*edges)
            )
        )

    private fun write(
        name: String,
        element: String,
        vararg edges: DepsParityScenarios.Edge,
        status: Int = 200,
        code: String? = null,
        token: ParityToken = DepsParityScenarios.admin
    ) = execute(name, DepsParityScenarios.WRITE_TOOL, "x", DepsParityScenarios.ops(element), status, code, token, *edges)

    private fun hex(id: UUID) = id.toString().replace("-", "")

    private fun data(o: ParityOutcome): JsonObject =
        Json
            .parseToJsonElement(o.body)
            .jsonObject
            .getValue("data")
            .jsonObject

    private fun items(o: ParityOutcome): List<JsonObject> = data(o).getValue("items").jsonArray.map { it.jsonObject }

    private fun results(o: ParityOutcome): List<JsonObject> = data(o).getValue("results").jsonArray.map { it.jsonObject }

    private fun str(
        o: JsonObject,
        key: String
    ): String = o.getValue(key).jsonPrimitive.content

    /** The stored dependency rows as (from, to, type) with the ends as dash-free lower-case hex. */
    private fun stored(o: ParityOutcome): Set<Triple<Any?, Any?, Any?>> =
        o.snapshot
            .getValue("dependencies")
            .map { Triple(it["from_item_id"], it["to_item_id"], it["type"]) }
            .toSet()

    private fun eventsOf(
        o: ParityOutcome,
        type: String
    ): List<Map<String, Any?>> = o.snapshot.getValue("events").filter { it["type"] == type }

    // ------------------------------------------------------------------ the catalog

    @TestFactory
    fun `every deps scenario agrees across MCP and REST and meets its literal oracle`(): List<DynamicTest> =
        DepsParityScenarios.all.map { s -> DynamicTest.dynamicTest(s.name) { harness.run(s) } }

    @Test
    fun `the catalog names are unique so a failure points at one scenario`() {
        val names = DepsParityScenarios.all.map { it.name }
        assertEquals(
            names.size,
            names.toSet().size,
            "duplicate scenario names: ${names.groupingBy { it }.eachCount().filter { it.value > 1 }}"
        )
    }

    // ------------------------------------------------------------------ S1-S5 stored rows and events

    @Test
    fun `S1 create stores exactly one blocks row and one dependency added whose entity id is the id in the body`() {
        val o = write("S1 detail", DepsParityScenarios.create(a, b))
        assertEquals(setOf(Triple<Any?, Any?, Any?>(hex(a), hex(b), "BLOCKS")), stored(o))
        val added = eventsOf(o, "dependency.added")
        assertEquals(1, added.size, "one dependency.added")
        assertTrue(eventsOf(o, "dependency.removed").isEmpty())
        val bodyId = str(results(o).single(), "id")
        assertTrue(bodyId.startsWith("<ID"), "the edge id is generated: $bodyId")
        assertEquals(bodyId, added.single()["entity_id"], "the event is about the edge whose id the body reports")
    }

    @Test
    fun `S1 create with an unblockAt stores that threshold`() {
        val o = write("S1 threshold", DepsParityScenarios.create(a, b, ""","unblockAt":"review""""))
        val row = o.snapshot.getValue("dependencies").single()
        assertEquals("review", row["unblock_at"])
        assertEquals("review", str(results(o).single(), "unblockAt"))
    }

    @Test
    fun `S2 linear stores the two chain edges and records one dependency added per edge`() {
        val o = write("S2 detail", DepsParityScenarios.linear(a, b, c))
        assertEquals(
            setOf<Triple<Any?, Any?, Any?>>(Triple(hex(a), hex(b), "BLOCKS"), Triple(hex(b), hex(c), "BLOCKS")),
            stored(o)
        )
        assertEquals(2, eventsOf(o, "dependency.added").size)
        val created = results(o).single().getValue("created").jsonArray
        assertEquals(2, created.size)
        assertEquals(listOf(a.toString(), b.toString()), created.map { str(it.jsonObject, "from") })
        assertEquals(listOf(b.toString(), c.toString()), created.map { str(it.jsonObject, "to") })
    }

    @Test
    fun `S3 fan_out stores one edge per target and S4 fan_in one edge per source`() {
        val out = write("S3 detail", DepsParityScenarios.fanOut(a, b, c))
        assertEquals(
            setOf<Triple<Any?, Any?, Any?>>(Triple(hex(a), hex(b), "BLOCKS"), Triple(hex(a), hex(c), "BLOCKS")),
            stored(out)
        )
        val inn = write("S4 detail", DepsParityScenarios.fanIn(a, b, to = c))
        assertEquals(
            setOf<Triple<Any?, Any?, Any?>>(Triple(hex(a), hex(c), "BLOCKS"), Triple(hex(b), hex(c), "BLOCKS")),
            stored(inn)
        )
        assertEquals(2, eventsOf(out, "dependency.added").size)
        assertEquals(2, eventsOf(inn, "dependency.added").size)
    }

    @Test
    fun `S5 delete by id removes the row and records one dependency removed with cause explicit`() {
        val o = write("S5 detail", DepsParityScenarios.delete(""","id":"$d1""""), edge(d1, a, b), edge(d2, b, c))
        assertEquals(setOf<Triple<Any?, Any?, Any?>>(Triple(hex(b), hex(c), "BLOCKS")), stored(o), "only the other edge remains")
        val removed = eventsOf(o, "dependency.removed")
        assertEquals(1, removed.size)
        val payload = Json.parseToJsonElement(removed.single()["data"] as String).jsonObject
        assertEquals("explicit", str(payload, "cause"))
        assertEquals(1, str(results(o).single(), "deleted").toInt())
    }

    @Test
    fun `delete by item records one dependency removed per deleted row`() {
        val o =
            write(
                "delete item detail",
                DepsParityScenarios.delete(""","item":"$a""""),
                edge(d1, a, b),
                edge(d2, c, a),
                edge(d3, b, d)
            )
        assertEquals(setOf<Triple<Any?, Any?, Any?>>(Triple(hex(b), hex(d), "BLOCKS")), stored(o))
        assertEquals(2, eventsOf(o, "dependency.removed").size)
    }

    @Test
    fun `delete by id removes a relates_to edge too`() {
        val o = write("delete relates detail", DepsParityScenarios.delete(""","id":"$d1""""), edge(d1, a, b, DependencyType.RELATES_TO))
        assertTrue(stored(o).isEmpty())
        assertEquals(1, eventsOf(o, "dependency.removed").size)
    }

    // ------------------------------------------------------------------ failures leave no trace

    @Test
    fun `a rejected pattern records no dependency event at all`() {
        val dup = write("dup no events", DepsParityScenarios.linear(a, b, a, b), status = 207, code = "partial_failure")
        assertTrue(eventsOf(dup, "dependency.added").isEmpty(), "no added event for a rejected pattern")
        assertTrue(stored(dup).isEmpty())
        val cycle =
            write(
                "cycle no events",
                DepsParityScenarios.linear(a, b, c),
                edge(d1, c, a),
                status = 207,
                code = "partial_failure"
            )
        assertEquals(setOf<Triple<Any?, Any?, Any?>>(Triple(hex(c), hex(a), "BLOCKS")), stored(cycle), "only the seeded edge")
        assertTrue(eventsOf(cycle, "dependency.added").isEmpty())
    }

    @Test
    fun `S10 a differently spelled reference to the same items is still a duplicate`() {
        val upper =
            write(
                "dup upper case",
                DepsParityScenarios.create(a.toString().uppercase(), b.toString().uppercase()),
                edge(d1, a, b),
                status = 207,
                code = "partial_failure"
            )
        assertTrue("\"existingId\":\"$d1\"" in upper.body, upper.body)
        val prefix =
            write(
                "dup prefix",
                DepsParityScenarios.create("a1000000", "b1000000"),
                edge(d1, a, b),
                status = 207,
                code = "partial_failure"
            )
        assertTrue("\"existingId\":\"$d1\"" in prefix.body, prefix.body)
    }

    @Test
    fun `S12 the two relates_to edges are stored while the invalid third is not`() {
        val o =
            execute(
                "S12 detail",
                DepsParityScenarios.WRITE_TOOL,
                "create",
                DepsParityScenarios.ops(
                    DepsParityScenarios.create(a, b, ""","type":"relates_to""""),
                    DepsParityScenarios.create(b, a, ""","type":"relates_to""""),
                    DepsParityScenarios.create(a, c, ""","type":"relates_to","unblockAt":"work"""")
                ),
                207,
                "partial_failure"
            )
        assertEquals(
            setOf<Triple<Any?, Any?, Any?>>(Triple(hex(a), hex(b), "RELATES_TO"), Triple(hex(b), hex(a), "RELATES_TO")),
            stored(o)
        )
        val rows = results(o)
        assertEquals(
            listOf(true, true, false),
            rows.map {
                it
                    .getValue("ok")
                    .jsonPrimitive.content
                    .toBoolean()
            }
        )
    }

    // ------------------------------------------------------------------ null versus absent

    @Test
    fun `an explicit null unblockAt behaves exactly like an absent one`() {
        val absent = write("unblock absent", DepsParityScenarios.create(a, b))
        val explicitNull = write("unblock null", DepsParityScenarios.create(a, b, ""","unblockAt":null"""))
        assertEquals(absent.snapshot.getValue("dependencies"), explicitNull.snapshot.getValue("dependencies"))
        assertEquals(absent.body, explicitNull.body, "the same body once generated ids are tokenized")
    }

    // ------------------------------------------------------------------ S6 list

    @Test
    fun `S6 list orders edges by creation time then id and reports full ids and the wire type`() {
        val o =
            execute(
                "S6 detail",
                DepsParityScenarios.READ_TOOL,
                "list",
                DepsParityScenarios.list(b, ""","direction":"incoming""""),
                200,
                null,
                DepsParityScenarios.reader,
                edge(d3, a, b),
                edge(d1, c, b),
                edge(d2, d, b)
            )
        val rows = items(o)
        // all three rows share the fixed seed instant, so the order is the id tiebreak: d1, d2, d3
        assertEquals(listOf(d1, d2, d3).map { it.toString() }, rows.map { str(it, "id") })
        assertEquals(listOf(c, d, a).map { it.toString() }, rows.map { str(it, "from") })
        assertTrue(rows.all { str(it, "to") == b.toString() && str(it, "type") == "blocks" })
        val page = data(o).getValue("page").jsonObject
        assertEquals(3, page.getValue("total").jsonPrimitive.int)
        assertFalse(
            page
                .getValue("truncated")
                .jsonPrimitive.content
                .toBoolean()
        )
    }

    @Test
    fun `S6 list reports a relates_to edge with the relates_to wire type`() {
        val o =
            execute(
                "list relates type",
                DepsParityScenarios.READ_TOOL,
                "list",
                DepsParityScenarios.list(a),
                200,
                null,
                DepsParityScenarios.reader,
                edge(d1, a, b, DependencyType.RELATES_TO)
            )
        assertEquals("relates_to", str(items(o).single(), "type"))
    }

    // ------------------------------------------------------------------ S7 graph

    private fun graphOf(
        name: String,
        subject: UUID,
        vararg edges: DepsParityScenarios.Edge
    ): List<JsonObject> =
        items(
            execute(
                name,
                DepsParityScenarios.READ_TOOL,
                "graph",
                DepsParityScenarios.graph(subject),
                200,
                null,
                DepsParityScenarios.reader,
                *edges
            )
        )

    @Test
    fun `S7 graph of a chain lists its nodes with levels 0 1 2 in blocker order`() {
        val nodes = graphOf("S7 chain", a, edge(d1, a, b), edge(d2, b, c))
        assertEquals(listOf(a, b, c).map { it.toString() }, nodes.map { str(it, "id") })
        assertEquals(listOf(0, 1, 2), nodes.map { it.getValue("level").jsonPrimitive.int })
    }

    @Test
    fun `S7 graph from a middle node reaches blockers and blocked items alike`() {
        val nodes = graphOf("S7 middle", b, edge(d1, a, b), edge(d2, b, c))
        assertEquals(
            mapOf(a.toString() to 0, b.toString() to 1, c.toString() to 2),
            nodes.associate { str(it, "id") to it.getValue("level").jsonPrimitive.int }
        )
    }

    @Test
    fun `S7 level is the longest blocker path and the order puts every blocker before the items it blocks`() {
        val edges = listOf(edge(d1, a, b), edge(d2, a, c), edge(d3, b, d), edge(d4, c, d), edge(d5, a, d))
        val nodes = graphOf("S7 diamond", d, *edges.toTypedArray())
        assertEquals(
            mapOf(a.toString() to 0, b.toString() to 1, c.toString() to 1, d.toString() to 2),
            nodes.associate { str(it, "id") to it.getValue("level").jsonPrimitive.int },
            "d is one hop from a directly but two hops through b and c: the longest path wins"
        )
        val position = nodes.map { str(it, "id") }.withIndex().associate { it.value to it.index }
        for (e in edges) {
            assertTrue(
                position.getValue(e.from.toString()) < position.getValue(e.to.toString()),
                "${e.from} must be listed before ${e.to}"
            )
        }
    }

    @Test
    fun `graph lists the nodes of a cycle as cyclic without a level, after the ordered nodes`() {
        val nodes = graphOf("graph cyclic", a, edge(d1, a, b), edge(d2, b, c), edge(d3, c, b))
        assertEquals(a.toString(), str(nodes.first(), "id"))
        assertEquals(
            0,
            nodes
                .first()
                .getValue("level")
                .jsonPrimitive.int
        )
        assertNull(nodes.first()["cyclic"])
        val cyclic = nodes.drop(1)
        assertEquals(setOf(b.toString(), c.toString()), cyclic.map { str(it, "id") }.toSet())
        assertTrue(
            cyclic.all {
                it
                    .getValue("cyclic")
                    .jsonPrimitive.content
                    .toBoolean() &&
                    "level" !in it
            }
        )
    }

    @Test
    fun `graph of an item with no blocking edge is that item alone at level 0`() {
        val nodes = graphOf("graph alone", a)
        assertEquals(1, nodes.size)
        assertEquals(a.toString(), str(nodes.single(), "id"))
        assertEquals(
            0,
            nodes
                .single()
                .getValue("level")
                .jsonPrimitive.int
        )
    }
}
