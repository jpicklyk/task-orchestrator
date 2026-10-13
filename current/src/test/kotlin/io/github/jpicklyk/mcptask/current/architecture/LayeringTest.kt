package io.github.jpicklyk.mcptask.current.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Enforces the layer model documented in CONTRIBUTING.md and CLAUDE.md over the PRODUCTION source
 * (`Konsist.scopeFromProduction()`; test sources are exempt): rules R1-R5 as defined, and self-tested, in
 * [LayeringRules] (R1-R3 the inward-only layer edges, R4 no MCP SDK or Ktor in domain and application, R5 no layer
 * imports the `bootstrap` composition root). The root package (`CurrentMain.kt`) and `bootstrap/` are not checked.
 *
 * ## Baseline / ratchet
 *
 * The known layering violations are tracked in the checked-in baseline file
 * `current/src/test/resources/architecture/layering-baseline.txt` (one `<path> -> <imported FQN>` entry per line). This
 * test enforces a two-way ratchet:
 *
 * - Any violation NOT in the baseline file fails the test (new violations are forbidden).
 * - Any baseline line that no longer corresponds to a real violation ALSO fails the test (the
 *   message says "fixed - delete this line"), so the baseline shrinks as violations are resolved
 *   and can never silently regrow past what's listed.
 *
 * Both an import and a fully-qualified reference on a code line count; KDoc and comment lines do not.
 */
class LayeringTest {
    private val baselineFile =
        File("src/test/resources/architecture/layering-baseline.txt")

    private fun normalizedPath(path: String): String {
        val marker = "current/"
        val normalized = path.replace('\\', '/')
        val idx = normalized.lastIndexOf(marker)
        return if (idx >= 0) normalized.substring(idx + marker.length) else normalized
    }

    @Test
    fun `production source has no undeclared layering violations`() {
        val scope = Konsist.scopeFromProduction()
        val files = scope.files

        // Sanity guard: a misrooted scope (e.g. wrong Gradle working directory) would pass
        // vacuously with zero files to check. Fail loudly instead.
        assertTrue(
            files.count() >= 150,
            "Expected >= 150 production Kotlin files in scope, found ${files.count()} - " +
                "the Konsist scope may be misrooted (check the Gradle working directory).",
        )
        listOf("domain", "application", "infrastructure", "interfaces", "bootstrap").forEach { layer ->
            val count = files.count { normalizedPath(it.path).startsWith("$layer/") }
            assertTrue(count > 0, "Expected package '$layer' to have at least one file in scope, found 0.")
        }

        val violations =
            files.flatMap { file ->
                LayeringRules.violations(normalizedPath(file.path), file.imports.map { it.name }, file.text)
            }

        val diff = LayeringRules.diff(violations.map { it.key() }.toSet(), LayeringRules.parseBaseline(baselineFile.readLines()))

        val messages = mutableListOf<String>()
        if (diff.unbaselined.isNotEmpty()) {
            messages +=
                "New layering violation(s) not in baseline (current/src/test/resources/architecture/layering-baseline.txt):\n" +
                diff.unbaselined.sorted().joinToString("\n") { "  - $it" }
        }
        if (diff.stale.isNotEmpty()) {
            messages +=
                "Baseline entry(ies) no longer match a real violation - fixed, delete this line " +
                "from layering-baseline.txt:\n" +
                diff.stale.sorted().joinToString("\n") { "  - $it" }
        }

        assertTrue(messages.isEmpty(), messages.joinToString("\n\n"))
    }

    @Test
    fun `the baseline is empty`() {
        val baseline = LayeringRules.parseBaseline(baselineFile.readLines())
        kotlin.test.assertEquals(
            emptySet<String>(),
            baseline,
            "R1 moved the identity code into infrastructure/identity, clearing the last AR-94 lines; the ratchet only shrinks",
        )
    }
}
