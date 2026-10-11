package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 450aa813) for scenario S7 of the frozen test-plan (NEW-SURFACE): every
 * `items_read` scenario of [ProductionParity] runs through the real [ParityHarness] over the production registry, so the MCP
 * leg and the `/ops` leg are proved byte-equal (minus `meta.reqId`) over identically seeded databases, the REST status and
 * error code equal the scenario's literal oracle, and no read changes the database. The coverage rule itself is held by
 * `ParityCoverageGuardTest` (S18); this class adds the execution that the guard's catalog check does not perform.
 *
 * Oracles: the envelope statuses and the task-scope D2 scope rules written into the catalog as literals; the harness
 * assertions documented on [ParityHarness]. Forbidden-construct declaration: none.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class ItemsReadParityTest {
    private val harness = ParityHarness(ToolRegistry(buildToolSpecs()))

    private val mine get() = ProductionParity.scenarios.filter { it.tool == "items_read" }

    @Test
    fun `S7 the catalog holds a happy and a scope-forbidden scenario for each of the five selects`() {
        val selects = setOf("get", "list", "schema", "search", "overview")
        for (kind in listOf(ParityKind.HAPPY, ParityKind.SCOPE_FORBIDDEN)) {
            assertEquals(selects, mine.filter { it.kind == kind }.map { it.member }.toSet(), "$kind scenarios by select")
        }
        assertTrue(mine.filter { it.kind == ParityKind.SCOPE_FORBIDDEN }.all { it.token.isScopeRestricted })
    }

    @Test
    fun `S7 happy scenarios agree across MCP and REST`() {
        for (s in mine.filter { it.kind == ParityKind.HAPPY }) harness.run(s)
    }

    @Test
    fun `S7 scope-forbidden scenarios agree across MCP and REST under the same access`() {
        for (s in mine.filter { it.kind == ParityKind.SCOPE_FORBIDDEN }) harness.run(s)
    }

    @Test
    fun `S7 not found, strictness and capability scenarios agree across MCP and REST`() {
        for (s in mine.filter { it.kind == ParityKind.OTHER }) harness.run(s)
    }
}
