package io.github.jpicklyk.mcptask.current.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plan v4-phase1-core 3.10: "Konsist rule bans SchemaUtils". Flyway is the only schema path, so no
 * production source may import or reference Exposed's `SchemaUtils` (the table-creating helper that
 * the deleted Direct mode used). Test sources are exempt: the test-only fixture bridge legitimately
 * uses it.
 */
class NoSchemaUtilsInProductionTest {
    private val token = Regex("\\bSchemaUtils\\b")

    private fun isCodeLine(rawLine: String): Boolean {
        val trimmed = rawLine.trim()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("//") &&
            !trimmed.startsWith("/*") &&
            !trimmed.startsWith("*")
    }

    private fun offendingLines(text: String): List<String> =
        text.lineSequence().filter { isCodeLine(it) && token.containsMatchIn(it) }.toList()

    @Test
    fun `production source never imports or references SchemaUtils`() {
        val files = Konsist.scopeFromProduction().files
        assertTrue(
            files.count() >= 150,
            "Expected >= 150 production Kotlin files in scope, found ${files.count()} - the Konsist scope may be misrooted."
        )

        val violations =
            files.flatMap { file ->
                val imports = file.imports.filter { token.containsMatchIn(it.name) }.map { "import ${it.name}" }
                (imports + offendingLines(file.text)).map { "${file.path.replace('\\', '/')}: ${it.trim()}" }
            }

        assertEquals(emptyList(), violations, "SchemaUtils must not appear in production code")
    }

    @Test
    fun `the detector flags an import, a qualified call and a bare call but ignores comments`() {
        assertEquals(1, offendingLines("import org.jetbrains.exposed.v1.jdbc.SchemaUtils").size)
        assertEquals(1, offendingLines("    org.jetbrains.exposed.v1.jdbc.SchemaUtils.create(T)").size)
        assertEquals(1, offendingLines("    SchemaUtils.createMissingTablesAndColumns(T)").size)
        assertEquals(0, offendingLines("    // SchemaUtils was removed").size)
        assertEquals(0, offendingLines("     * see SchemaUtils").size)
        assertEquals(0, offendingLines("    val x = MySchemaUtilsHelper()").size)
    }
}
