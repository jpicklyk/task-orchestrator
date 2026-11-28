package io.github.jpicklyk.mcptask.current.architecture

// repo-read: exempt - scans only current/src/test/kotlin; its marker strings are the rule itself

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Keeps the `repo-read` tag honest. CI runs only `:current:repoReadTest` on a pull request that changes no
 * Kotlin source or build input (`.github/workflows/test.yml`, job `changes`), so a test that reads a repo
 * file outside `current/src` without the tag would be skipped exactly when that file changes.
 *
 * Rule, per file under `src/test/kotlin`: a file that reaches outside the module (it calls `repoRoot()`,
 * reads `System.getProperty("user.dir")`, or uses a helper listed in [HELPERS]) carries `@Tag("repo-read")`
 * or a `// repo-read: exempt - <reason>` line saying why the read stays inside `current/src`. A non-test
 * helper that reaches outside the module must be listed in [HELPERS] with the text its callers carry.
 * Limits: the scan is textual, so a read written another way (a literal `"../"` path, a new helper) is
 * caught only by the helper rule or by review; the tag and the exemption cover the whole file, so a
 * new read added to an exempt file is not flagged. Pushes to main always run the full suite.
 */
class RepoReadTagGuardTest {
    companion object {
        private const val TAG = "@Tag(\"repo-read\")"
        private const val EXEMPT = "// repo-read: exempt - "

        /** Direct markers: text that means the file resolves a path outside the module. */
        private val DIRECT = listOf("repoRoot(", "System.getProperty(\"user.dir\")")

        /** Helper file name (no extension) to the text its callers carry. */
        private val HELPERS =
            mapOf(
                "StrictConfigCases" to "repoRoot(",
                "ApiReferenceDocs" to "ApiReferenceDocs",
            )

        private val TEST_ANNOTATION = Regex("""@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b""")
        private const val MIN_FILES = 500

        fun sourceRoot(): File {
            val local = File("src/test/kotlin")
            return if (local.isDirectory) local else File("current/src/test/kotlin")
        }
    }

    private fun files(): List<File> =
        sourceRoot()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun rel(f: File): String = f.relativeTo(sourceRoot()).invariantSeparatorsPath

    @Test
    fun `every test file that reads repo files outside current src carries the repo-read tag or an exemption`() {
        val all = files()
        assertTrue(all.size >= MIN_FILES, "scanned only ${all.size} files under ${sourceRoot().absolutePath}")
        val callerMarkers = DIRECT + HELPERS.values
        val missing =
            all
                .filter { it.nameWithoutExtension !in HELPERS }
                .filter { f ->
                    val text = f.readText()
                    TEST_ANNOTATION.containsMatchIn(text) &&
                        callerMarkers.any { it in text } &&
                        TAG !in text &&
                        EXEMPT !in text
                }.map(::rel)
                .sorted()
        assertTrue(
            missing.isEmpty(),
            "These test files read repo files outside current/src but carry neither $TAG nor '$EXEMPT<reason>'. " +
                "Without the tag CI skips them on a pull request that changes no Kotlin source:\n  " +
                missing.joinToString("\n  "),
        )
    }

    @Test
    fun `every non-test helper that reaches outside the module is listed with its caller marker`() {
        val unlisted =
            files()
                .filter { f ->
                    val text = f.readText()
                    !TEST_ANNOTATION.containsMatchIn(text) &&
                        DIRECT.any { it in text } &&
                        f.nameWithoutExtension !in HELPERS &&
                        EXEMPT !in text
                }.map(::rel)
                .sorted()
        assertTrue(
            unlisted.isEmpty(),
            "These helpers resolve repo paths but are not in RepoReadTagGuardTest.HELPERS, so their callers " +
                "are not checked for the repo-read tag:\n  " + unlisted.joinToString("\n  "),
        )
    }

    @Test
    fun `every exemption states a reason`() {
        val bare =
            files()
                .filter { f -> f.readLines().any { it.trim() == EXEMPT.trim() } }
                .map(::rel)
        assertTrue(bare.isEmpty(), "repo-read exemptions without a reason: $bare")
    }
}
