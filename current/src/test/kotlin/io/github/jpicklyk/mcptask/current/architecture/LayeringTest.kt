package io.github.jpicklyk.mcptask.current.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Enforces the layer model documented in CONTRIBUTING.md and CLAUDE.md:
 *
 *   domain -> application -> infrastructure -> interfaces
 *
 * (an outer layer may depend on an inner one; the reverse is forbidden). Concretely:
 *
 * - R1: `domain` must not import from `application`, `infrastructure`, or `interfaces`.
 * - R2: `application` must not import from `infrastructure` or `interfaces`.
 * - R3: `infrastructure` must not import from `interfaces`.
 *
 * `infrastructure -> application` IS allowed (infrastructure adapters implement application-layer
 * ports, e.g. `YamlNoteSchemaService -> WorkItemSchemaService`), and `interfaces -> anything` is
 * unrestricted. The root package (`CurrentMain.kt`, the composition root) is not scanned.
 *
 * This test only scans PRODUCTION source (`Konsist.scopeFromProduction()`): test sources are
 * exempt from layering rules.
 *
 * ## Baseline / ratchet
 *
 * The codebase currently has 14 known layering violations, tracked in the checked-in baseline
 * file `current/src/test/resources/architecture/layering-baseline.txt` (one
 * `<path> -> <imported FQN>` entry per line). This test enforces a two-way ratchet:
 *
 * - Any violation NOT in the baseline file fails the test (new violations are forbidden).
 * - Any baseline line that no longer corresponds to a real violation ALSO fails the test (the
 *   message says "fixed - delete this line"), so the baseline shrinks as violations are resolved
 *   and can never silently regrow past what's listed.
 *
 * ## Secondary check: fully-qualified references in code
 *
 * A forbidden-layer FQN can also appear as a fully-qualified reference in executable code (no
 * import needed), which a pure import-based scan misses. This test also scans each file's
 * non-comment lines for forbidden-layer FQN prefixes. KDoc references (`[the.fqn.Thing]`) are not
 * code and are ignored: comment lines (trimmed lines starting with a line-comment marker, a
 * block-comment opener, or `*`) are skipped entirely, which covers both single-line comments and
 * KDoc/block-comment bodies.
 */
class LayeringTest {
    private val currentRoot = "io.github.jpicklyk.mcptask.current"
    private val domainPrefix = "$currentRoot.domain"
    private val applicationPrefix = "$currentRoot.application"
    private val infrastructurePrefix = "$currentRoot.infrastructure"
    private val interfacesPrefix = "$currentRoot.interfaces"

    private val baselineFile =
        File("src/test/resources/architecture/layering-baseline.txt")

    data class Violation(
        val path: String,
        val importedFqName: String
    ) {
        /** Baseline key: forward-slash-normalized path + " -> " + imported FQN. */
        fun key(): String = "${path.replace('\\', '/')} -> $importedFqName"
    }

    private fun readBaseline(): Set<String> =
        baselineFile
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    private fun forbiddenPrefixesFor(path: String): List<String> =
        when {
            path.startsWith("domain/") ->
                listOf(applicationPrefix, infrastructurePrefix, interfacesPrefix)
            path.startsWith("application/") ->
                listOf(infrastructurePrefix, interfacesPrefix)
            path.startsWith("infrastructure/") ->
                listOf(interfacesPrefix)
            else -> emptyList()
        }

    private fun normalizedPath(path: String): String {
        val marker = "current/"
        val normalized = path.replace('\\', '/')
        val idx = normalized.lastIndexOf(marker)
        return if (idx >= 0) normalized.substring(idx + marker.length) else normalized
    }

    private fun isCodeLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("//") &&
            !trimmed.startsWith("/*") &&
            !trimmed.startsWith("*")
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
        val domainCount = files.count { normalizedPath(it.path).startsWith("domain/") }
        val applicationCount = files.count { normalizedPath(it.path).startsWith("application/") }
        val infrastructureCount = files.count { normalizedPath(it.path).startsWith("infrastructure/") }
        val interfacesCount = files.count { normalizedPath(it.path).startsWith("interfaces/") }
        // (Plain path-substring checks are used here rather than Konsist's `withPath(regex = ...)`
        // -- functionally equivalent for this sanity guard, and keeps the dependency surface used
        // by this test to Konsist.scopeFromProduction()/KoFileDeclaration only.)
        listOf(
            "domain" to domainCount,
            "application" to applicationCount,
            "infrastructure" to infrastructureCount,
            "interfaces" to interfacesCount,
        ).forEach { (layer, count) ->
            assertTrue(count > 0, "Expected layer '$layer' to have at least one file in scope, found 0.")
        }

        val violations = mutableListOf<Violation>()

        files.forEach { file ->
            val path = normalizedPath(file.path)
            val forbidden = forbiddenPrefixesFor(path)
            if (forbidden.isEmpty()) return@forEach

            // Import-based check.
            file.imports.forEach { import ->
                val name = import.name
                if (forbidden.any { name == it || name.startsWith("$it.") }) {
                    violations += Violation(path, name)
                }
            }

            // Fully-qualified-reference check (catches code that skips the import and calls
            // through a fully-qualified name directly). KDoc/comment lines are excluded.
            file.text.lineSequence().forEach { line ->
                if (!isCodeLine(line)) return@forEach
                forbidden.forEach { prefix ->
                    if (line.contains(prefix)) {
                        // Extract the longest fully-qualified-looking token containing the prefix.
                        val regex = Regex(Regex.escape(prefix) + "(\\.[A-Za-z0-9_]+)+")
                        regex.findAll(line).forEach { match ->
                            violations += Violation(path, match.value)
                        }
                    }
                }
            }
        }

        val baseline = readBaseline()
        val violationKeys = violations.map { it.key() }.toSet()

        val unbaselined = violationKeys - baseline
        val stale = baseline - violationKeys

        val messages = mutableListOf<String>()
        if (unbaselined.isNotEmpty()) {
            messages +=
                "New layering violation(s) not in baseline (current/src/test/resources/architecture/layering-baseline.txt):\n" +
                unbaselined.sorted().joinToString("\n") { "  - $it" }
        }
        if (stale.isNotEmpty()) {
            messages +=
                "Baseline entry(ies) no longer match a real violation - fixed, delete this line " +
                "from layering-baseline.txt:\n" +
                stale.sorted().joinToString("\n") { "  - $it" }
        }

        assertTrue(messages.isEmpty(), messages.joinToString("\n\n"))
    }
}
