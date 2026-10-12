package io.github.jpicklyk.mcptask.current.architecture

import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.ToolRegistry
import io.github.jpicklyk.mcptask.current.interfaces.shared.tools.buildToolSpecs
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Registry guard (item 4162566c): every field a tool's select or op request class declares (and every field of a
 * project-owned class nested in it) is read by production code. Request schemas are generated from the request
 * serializers, so declared and decoded fields cannot drift; what can drift is a decoded field no handler ever uses,
 * which silently accepts a parameter. Static bytecode analysis of the compiled main classes: see
 * [RequestFieldConsumption] for the read rule and its limits.
 *
 * Exemptions live only in `request-field-consumption-baseline.txt` as `<owner>#<field>  # <reason>` (two-way ratchet:
 * a new unread field fails, so does a stale line). A false positive is fixed in the engine, never baselined.
 */
class RequestFieldConsumptionGuardTest {
    private val baselineFile = File("src/test/resources/architecture/request-field-consumption-baseline.txt")

    @Test
    fun `every declared request field is read by production code`() {
        val classRoot =
            Path.of(
                ToolRegistry::class.java.protectionDomain.codeSource.location
                    .toURI()
            )
        val actual =
            RequestFieldConsumption.unreadFields(
                RequestFieldConsumption.roots(buildToolSpecs()),
                classRoot
            ) { it.startsWith("io.github.jpicklyk.mcptask.") }

        val message = RequestFieldConsumption.ratchet(actual, readBaseline())
        assertNull(
            message,
            (message ?: "") +
                "\n\nFix: wire the field into its handler, remove it from the request class, or (with a reason) add " +
                "'<owner>#<field>  # <reason>' to src/test/resources/architecture/request-field-consumption-baseline.txt."
        )
    }

    @Test
    fun `every request root walks at least one owner class`() {
        val classRoot =
            Path.of(
                ToolRegistry::class.java.protectionDomain.codeSource.location
                    .toURI()
            )
        val walked =
            RequestFieldConsumption.walkedOwners(
                RequestFieldConsumption.roots(buildToolSpecs()),
                classRoot
            ) { it.startsWith("io.github.jpicklyk.mcptask.") }
        val skipped = walked.filterValues { it.isEmpty() }.keys.sorted()
        assertTrue(skipped.isEmpty(), "Request root(s) the guard silently skipped (no owner walked): $skipped")
        assertTrue(walked.isNotEmpty(), "No request roots found; the guard would prove nothing.")
    }

    private fun readBaseline(): Set<String> =
        baselineFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val match = BASELINE_LINE.matchEntire(line)
                requireNotNull(match) { "Malformed baseline line (expected '<owner>#<field>  # <reason>', reason mandatory): $line" }
                match.groupValues[1]
            }.toSet()

    private companion object {
        val BASELINE_LINE = Regex("""^(\S+#\S+)\s+#\s+(\S.*)$""")
    }
}
