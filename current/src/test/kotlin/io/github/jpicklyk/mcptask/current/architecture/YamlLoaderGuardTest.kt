package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Independent guard (test-author seat, item 8847d8fa, scenario S16): production code constructs a SnakeYAML
 * `Yaml` or `LoaderOptions` in exactly one file, `infrastructure/config/YamlLoaders.kt`. A plain ban with a fixed
 * allow-list and no ratchet baseline (the EnvAccessTest model): any code line outside that file that calls
 * `LoaderOptions(`, calls `Yaml(` (including the no-arg unsafe-constructor form) or imports `LoaderOptions` fails
 * with the file:line list.
 *
 * Oracle: task-scope D1 ("the only production file that may spell `LoaderOptions(` or `Yaml(`", vacuity check:
 * the factory file hits; look-alikes `parseYaml(`, `YamlSchemaParser.parseRoot(`, comments and string contents
 * must not hit). Every "no hit" below is paired with a spelling that does hit.
 */
class YamlLoaderGuardTest {
    private val factoryFile = "infrastructure/config/YamlLoaders.kt"

    private val construction =
        Regex("""\b(?:LoaderOptions|Yaml)\s*\(|import\s+org\.yaml\.snakeyaml\.LoaderOptions""")

    private val templateBody = Regex("""\$\{([^}]*)\}""")

    /** True when [rawLine] is a code line whose code (strings stripped, trailing comment cut, template bodies kept) builds a Yaml. */
    private fun constructsYaml(rawLine: String): Boolean {
        if (!GuardSupport.isCodeLine(rawLine)) return false
        val templates = templateBody.findAll(rawLine).joinToString(" ") { it.groupValues[1] }
        val code = GuardSupport.stripStrings(rawLine).substringBefore("//")
        return construction.containsMatchIn(code) || construction.containsMatchIn(templates)
    }

    private fun hitLines(source: GuardSupport.Source): List<Int> =
        source.text.lines().mapIndexedNotNull { index, line -> if (constructsYaml(line)) index + 1 else null }

    @Test
    fun `no production source outside YamlLoaders constructs a Yaml or LoaderOptions`() {
        val offenders =
            GuardSupport
                .productionSources()
                .filter { it.path != factoryFile }
                .associate { it.path to hitLines(it) }
                .filterValues { it.isNotEmpty() }

        assertTrue(
            offenders.isEmpty(),
            "Yaml( / LoaderOptions( outside infrastructure/config/YamlLoaders.kt, use YamlLoaders.strict() or tolerant():\n" +
                offenders.entries.joinToString("\n") { (path, lines) -> "  $path: lines $lines" }
        )
    }

    @Test
    fun `the guard is not vacuous - the factory file constructs a Yaml and is found`() {
        val sources = GuardSupport.productionSources().associateBy { it.path }

        val factory =
            assertNotNull(
                sources[factoryFile],
                "YamlLoaders.kt must be among the production sources: ${sources.keys.filter { "Yaml" in it }}"
            )
        assertTrue(hitLines(factory).isNotEmpty(), "YamlLoaders.kt builds the Yaml instances, so the matcher must find it")
    }

    @Test
    fun `the matcher finds every alternate spelling of a Yaml or LoaderOptions construction`() {
        val spellings =
            listOf(
                """fun strict(): Yaml = Yaml(SafeConstructor(LoaderOptions().apply { isAllowDuplicateKeys = false }))""",
                """val opts = LoaderOptions ()""",
                """val y = Yaml()""",
                """val y = Yaml (SafeConstructor(opts))""",
                """import org.yaml.snakeyaml.LoaderOptions""",
                """val s = "value: ${'$'}{Yaml()}"""",
                """    val y = Yaml() // trailing comment""",
                """val y = org.yaml.snakeyaml.Yaml()"""
            )

        val missed = spellings.filterNot { constructsYaml(it) }

        assertEquals(emptyList(), missed, "spellings the guard would let through")
    }

    @Test
    fun `the matcher ignores parseYaml, YamlSchemaParser, comments and string contents`() {
        val lookAlikes =
            listOf(
                """val root = parseYaml(text)""",
                """val root = YamlSchemaParser.parseRoot(text)""",
                """object YamlConfigDocumentParser : ConfigDocumentParser {""",
                """// val y = Yaml()""",
                """ * builds LoaderOptions( here""",
                """/* Yaml( */""",
                """val m = "call Yaml( and LoaderOptions( in this message"""",
                """val x = foo() // Yaml() mentioned in a trailing comment""",
                """val loaders = YamlLoaders.strict()"""
            )

        val flagged = lookAlikes.filter { constructsYaml(it) }

        assertEquals(emptyList(), flagged, "look-alikes that are not a Yaml construction")
    }
}
