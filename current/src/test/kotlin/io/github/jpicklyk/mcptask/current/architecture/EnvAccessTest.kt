package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent guard (test-author seat, item ad684e46 / Q17, scenario S11): production code reads the process environment in
 * exactly three files, `infrastructure/config/AppConfig.kt`, `infrastructure/config/EnvBoolean.kt` and
 * `infrastructure/logging/LogFileAppenderInstaller.kt`. A plain ban with a fixed allow-list and no ratchet baseline (decision
 * D11): any code line outside those files that mentions `System.getenv` or `System::getenv` fails with the file list.
 *
 * Oracle: task-scope section 1.7 ("Production `System.getenv` / `System::getenv` survives only in ..."), the guard description
 * ("scans every production source's code lines (strings stripped) ... a vacuity self-check asserts `AppConfig.kt` is a hit")
 * and decision D11.
 *
 * Adversarial cases: the matcher is exercised on alternate spellings (whitespace, a fully qualified call, a static import, a
 * `${...}` template body) that a naive `contains("System.getenv")` would miss, and on look-alikes it must ignore (comments,
 * string contents, other receivers, `System.getProperty`). Every "no hit" below is paired with a spelling that does hit.
 */
class EnvAccessTest {
    private val allowListed =
        setOf(
            "infrastructure/config/AppConfig.kt",
            "infrastructure/config/EnvBoolean.kt",
            "infrastructure/logging/LogFileAppenderInstaller.kt"
        )

    private val envAccess =
        Regex("""\bSystem\s*(?:\.\s*getenv\b|::\s*getenv\b)|import\s+java\.lang\.System\.(?:getenv\b|\*)""")

    private val templateBody = Regex("""\$\{([^}]*)\}""")

    /** True when [rawLine] is a code line whose code (strings stripped, trailing comment cut, template bodies kept) reads the env. */
    private fun readsEnvironment(rawLine: String): Boolean {
        if (!GuardSupport.isCodeLine(rawLine)) return false
        val templates = templateBody.findAll(rawLine).joinToString(" ") { it.groupValues[1] }
        val code = GuardSupport.stripStrings(rawLine).substringBefore("//")
        return envAccess.containsMatchIn(code) || envAccess.containsMatchIn(templates)
    }

    private fun hitLines(source: GuardSupport.Source): List<Int> =
        source.text.lines().mapIndexedNotNull { index, line -> if (readsEnvironment(line)) index + 1 else null }

    @Test
    fun `no production source outside the three allow-listed files reads the process environment`() {
        val offenders =
            GuardSupport
                .productionSources()
                .filter { it.path !in allowListed }
                .associate { it.path to hitLines(it) }
                .filterValues { it.isNotEmpty() }

        assertTrue(
            offenders.isEmpty(),
            "System.getenv / System::getenv outside AppConfig.kt, EnvBoolean.kt and LogFileAppenderInstaller.kt:\n" +
                offenders.entries.joinToString("\n") { (path, lines) -> "  $path: lines $lines" }
        )
    }

    @Test
    fun `the guard is not vacuous - AppConfig reads the environment and is found, and every allow-listed file exists`() {
        val sources = GuardSupport.productionSources().associateBy { it.path }

        val appConfig =
            assertNotNull(
                sources["infrastructure/config/AppConfig.kt"],
                "AppConfig.kt must be among the production sources: ${sources.keys.filter { "AppConfig" in it }}"
            )
        assertTrue(hitLines(appConfig).isNotEmpty(), "AppConfig.kt reads the environment, so the matcher must find it")
        for (path in allowListed) assertTrue(path in sources, "allow-listed file is gone, shrink the allow-list: $path")
    }

    @Test
    fun `the matcher finds every alternate spelling of an environment read`() {
        val spellings =
            listOf(
                """val v = System.getenv("X")""",
                """    val all = System.getenv()""",
                """fun fromEnv(env: (String) -> String? = System::getenv): AppConfig""",
                """val v = java.lang.System.getenv("A")""",
                """val v = System . getenv("A")""",
                """val f = System ::getenv""",
                """import java.lang.System.getenv""",
                """import java.lang.System.*""",
                """val s = "value: ${'$'}{System.getenv("A")}"""",
                """    System.getenv("X") // trailing comment"""
            )

        val missed = spellings.filterNot { readsEnvironment(it) }

        assertEquals(emptyList(), missed, "spellings the guard would let through")
    }

    @Test
    fun `the matcher ignores comments, string contents, other receivers and System getProperty`() {
        val lookAlikes =
            listOf(
                """// System.getenv("X")""",
                """ * reads System.getenv at startup""",
                """/* System::getenv */""",
                """val m = "uses System.getenv in this message"""",
                """val x = foo() // System.getenv mentioned in a trailing comment""",
                """val d = System.getProperty("user.dir")""",
                """val x = Other.getenv("X")""",
                """val x = MySystem.getenv("X")"""
            )

        val flagged = lookAlikes.filter { readsEnvironment(it) }

        assertEquals(emptyList(), flagged, "look-alikes that are not environment reads")
    }
}
