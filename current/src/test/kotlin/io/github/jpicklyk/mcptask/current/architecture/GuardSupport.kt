package io.github.jpicklyk.mcptask.current.architecture

import com.lemonappdev.konsist.api.Konsist
import java.io.File

/**
 * Shared machinery for the text-based Konsist guards (ContextSafetyTest, TransactionOpenerTest,
 * CancellationSafetyTest): production file access, comment filtering and the two-way ratchet comparison used by
 * the `path <count>` baselines under src/test/resources/architecture/. Same ratchet model as LayeringTest.
 */
internal object GuardSupport {
    /** A production file: path relative to the `current/` source package root (forward slashes) and its text. */
    data class Source(
        val path: String,
        val text: String
    )

    fun normalizedPath(path: String): String {
        val normalized = path.replace('\\', '/')
        val idx = normalized.lastIndexOf("current/")
        return if (idx >= 0) normalized.substring(idx + "current/".length) else normalized
    }

    fun isCodeLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("//") &&
            !trimmed.startsWith("/*") &&
            !trimmed.startsWith("*")
    }

    private val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")

    /** Replaces the contents of every simple string literal on [line] so words inside messages are not code. */
    fun stripStrings(line: String): String = STRING_LITERAL.replace(line, "\"\"")

    fun productionSources(): List<Source> {
        val files = Konsist.scopeFromProduction().files.toList()
        check(files.size >= 150) {
            "Expected >= 150 production Kotlin files in scope, found ${files.size} - the Konsist scope may be misrooted " +
                "(check the Gradle working directory)."
        }
        return files.map { Source(normalizedPath(it.path), it.text) }
    }

    /** Parses a `<path> <count>` baseline (comments with `#`, blank lines ignored). */
    fun readBaseline(resourceName: String): Map<String, Int> =
        File("src/test/resources/architecture/$resourceName")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .associate { line ->
                val idx = line.lastIndexOf(' ')
                require(idx > 0) { "Malformed baseline line (expected '<path> <count>'): $line" }
                line.substring(0, idx).trim() to line.substring(idx + 1).trim().toInt()
            }

    /**
     * Two-way ratchet. Returns null when [actual] equals [baseline]; otherwise a message listing every file whose
     * count rose above (or is absent from) the baseline, and every baseline entry that is stale (count lower than
     * the baseline, or the file no longer violates) and must be lowered or deleted.
     */
    fun ratchet(
        actual: Map<String, Int>,
        baseline: Map<String, Int>,
        baselineName: String
    ): String? {
        val newer = actual.filter { (p, n) -> n > (baseline[p] ?: 0) }
        val stale = baseline.filter { (p, n) -> (actual[p] ?: 0) < n }
        if (newer.isEmpty() && stale.isEmpty()) return null
        val parts = mutableListOf<String>()
        if (newer.isNotEmpty()) {
            parts +=
                "New violation(s) above the baseline (current/src/test/resources/architecture/$baselineName):\n" +
                newer.toSortedMap().entries.joinToString("\n") { (p, n) -> "  + $p $n (baseline ${baseline[p] ?: 0})" }
        }
        if (stale.isNotEmpty()) {
            parts +=
                "Baseline entry(ies) no longer match - fixed, lower or delete these lines in $baselineName:\n" +
                stale.toSortedMap().entries.joinToString("\n") { (p, n) -> "  - $p $n (actual ${actual[p] ?: 0})" }
        }
        return parts.joinToString("\n\n")
    }

    /** Violation counts per file, files with zero omitted; [count] counts violating code lines in the file text. */
    fun countByFile(
        sources: List<Source>,
        count: (Source) -> Int
    ): Map<String, Int> =
        sources
            .associate { it.path to count(it) }
            .filterValues { it > 0 }
            .toSortedMap()

    /** The full current set as baseline text, for seeding. */
    fun asBaselineText(actual: Map<String, Int>): String = actual.toSortedMap().entries.joinToString("\n") { (p, n) -> "$p $n" }
}
