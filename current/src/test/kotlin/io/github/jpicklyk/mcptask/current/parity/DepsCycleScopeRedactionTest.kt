package io.github.jpicklyk.mcptask.current.parity

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Item 54f3a1ce r2 (review finding B1): a `cycle_detected` path must not name an item outside a restricted caller's
 * scope. Seed a -> x and x -> b, where x sits under R2 (tag beta) and the scoped tokens admit only R1 / tag alpha; creating
 * b -> a closes the cycle b, a, x, b. Each scenario runs through [ParityHarness], so MCP and REST `/ops` agree too.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class DepsCycleScopeRedactionTest {
    private val harness = ParityHarness(ToolRegistry(buildToolSpecs()))

    private val a = DepsParityScenarios.a
    private val b = DepsParityScenarios.b
    private val x = DepsParityScenarios.x

    private fun closeCycle(
        name: String,
        token: ParityToken,
        contains: List<String>,
        absent: List<String> = emptyList()
    ) {
        harness.run(
            ParityScenario(
                name = name,
                tool = DepsParityScenarios.WRITE_TOOL,
                member = "create",
                kind = ParityKind.OTHER,
                token = token,
                arguments = DepsParityScenarios.ops(DepsParityScenarios.create(b, a)),
                expectStatus = 207,
                expectCode = "partial_failure",
                expectContains = listOf("\"code\":\"cycle_detected\"") + contains,
                expectAbsent = absent,
                expectDbChanged = false,
                seed =
                    DepsParityScenarios.tree(
                        DepsParityScenarios.Edge(DepsParityScenarios.d1, a, x),
                        DepsParityScenarios.Edge(DepsParityScenarios.d2, x, b)
                    )
            )
        )
    }

    @Test
    fun `a root scoped caller sees the cycle path without the out of scope hop`() {
        closeCycle(
            "cycle path root scoped hides x",
            DepsParityScenarios.rootScoped,
            contains = listOf("\"path\":[\"$b\",\"$a\",\"$b\"]"),
            absent = listOf(x.toString())
        )
    }

    @Test
    fun `a tag scoped caller sees the cycle path without the out of scope hop`() {
        closeCycle(
            "cycle path tag scoped hides x",
            DepsParityScenarios.tagScoped,
            contains = listOf("\"path\":[\"$b\",\"$a\",\"$b\"]"),
            absent = listOf(x.toString())
        )
    }

    @Test
    fun `an unrestricted caller sees the full cycle path`() {
        closeCycle(
            "cycle path unrestricted names x",
            DepsParityScenarios.admin,
            contains = listOf("\"path\":[\"$b\",\"$a\",\"$x\",\"$b\"]")
        )
    }
}
