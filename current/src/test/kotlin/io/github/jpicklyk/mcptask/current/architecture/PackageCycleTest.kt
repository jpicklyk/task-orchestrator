package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Package-cycle ratchet over the production `application/<sub>/` packages (AR-58), rules defined and self-tested in
 * [PackageCycleRules]. Every package cycle must contain at least one edge listed in
 * `current/src/test/resources/architecture/package-cycle-baseline.txt` (one `<path> -> <imported FQN>` per line). Two-way:
 * an unbaselined cycle-closing edge fails, and so does a baseline line whose edge is gone or no longer on a cycle.
 */
class PackageCycleTest {
    private val baselineFile = File("src/test/resources/architecture/package-cycle-baseline.txt")
    private val importLine = Regex("(?m)^import\\s+([\\w.*]+)")

    @Test
    fun `application sub-packages have no undeclared package cycles`() {
        val sources = GuardSupport.productionSources()
        listOf("api", "config", "knowledge", "port", "read", "service", "support", "telemetry").forEach { sub ->
            assertTrue(
                sources.any { it.path.startsWith("application/$sub/") },
                "Expected package 'application/$sub' to have at least one file in scope, found 0.",
            )
        }
        val edges =
            sources.flatMap { src ->
                val imports = importLine.findAll(src.text).map { it.groupValues[1] }.toList()
                PackageCycleRules.edges(src.path, imports, src.text)
            }
        val diff = PackageCycleRules.diff(edges, LayeringRules.parseBaseline(baselineFile.readLines()))

        val messages = mutableListOf<String>()
        if (diff.unbaselined.isNotEmpty()) {
            messages +=
                "New package-cycle edge(s) not in baseline (current/src/test/resources/architecture/package-cycle-baseline.txt):\n" +
                diff.unbaselined.sorted().joinToString("\n") { "  - $it" }
        }
        if (diff.stale.isNotEmpty()) {
            messages +=
                "Baseline entry(ies) no longer match an edge on a cycle - fixed, delete from package-cycle-baseline.txt:\n" +
                diff.stale.sorted().joinToString("\n") { "  - $it" }
        }
        assertTrue(messages.isEmpty(), messages.joinToString("\n\n"))
    }
}
