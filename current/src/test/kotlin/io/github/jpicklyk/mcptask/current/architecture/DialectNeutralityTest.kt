package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Dialect-neutrality guard (item b094eb86, charter D20): SQLite and Exposed are confined to
 * `infrastructure/sqlite/`. Outside that prefix, production code must not
 * - R1: import or fully-qualify anything from `org.sqlite.` or `org.jetbrains.exposed.`, or
 * - R2: carry a SQLite-dialect token (or an `org.sqlite.` / `org.jetbrains.exposed.` reflection name) inside a string literal
 * Comments and KDoc are excluded. Violations are counted per file against the two-way ratcheted baseline
 * `dialect-neutrality-baseline.txt` (`<path> <count>`), which holds only `DeferredEventPublisher.kt` until P15 deletes it.
 */
class DialectNeutralityTest {
    /** Source text split into code (comments removed, literal contents blanked) and the contents of each string literal. */
    data class Lexed(
        val code: String,
        val literals: List<String>
    )

    companion object {
        const val SQLITE_ROOT = "infrastructure/sqlite/"

        val R1 = Regex("""\borg\.(sqlite|jetbrains\.exposed)\.""")

        /** One sample per R2 token; every one must be flagged. */
        val TOKEN_SAMPLES =
            listOf(
                "datetime(x)",
                "julianday",
                "strftime",
                "PRAGMA foreign_keys",
                "a MATCH b",
                "randomblob(8)",
                "INSERT OR IGNORE",
                "sqlite_master",
                "jdbc:sqlite:x.db"
            )

        val R2 = Regex("""datetime\(|julianday|strftime|PRAGMA|\bMATCH\b|randomblob|INSERT OR|sqlite_master|jdbc:sqlite:""")

        /** True when [path] lies under the SQLite root (trailing slash, so `infrastructure/sqlitex/` is not exempt). */
        fun isExempt(path: String): Boolean = path.startsWith(SQLITE_ROOT)

        fun lex(text: String): Lexed {
            val code = StringBuilder()
            val literals = mutableListOf<String>()
            val triple = "\"\"\""
            var i = 0
            val n = text.length
            while (i < n) {
                val c = text[i]
                when {
                    text.startsWith("//", i) -> {
                        while (i < n && text[i] != '\n') i++
                    }

                    text.startsWith("/*", i) -> {
                        var depth = 1
                        i += 2
                        while (i < n && depth > 0) {
                            when {
                                text.startsWith("/*", i) -> {
                                    depth++
                                    i += 2
                                }

                                text.startsWith("*/", i) -> {
                                    depth--
                                    i += 2
                                }

                                else -> {
                                    if (text[i] == '\n') code.append('\n')
                                    i++
                                }
                            }
                        }
                    }

                    text.startsWith(triple, i) -> {
                        val end = text.indexOf(triple, i + 3).let { if (it < 0) n else it }
                        val body = text.substring(i + 3, end)
                        literals += body
                        code.append("\"\"").append("\n".repeat(body.count { it == '\n' }))
                        i = minOf(n, end + 3)
                    }

                    c == '"' -> {
                        val sb = StringBuilder()
                        i++
                        while (i < n && text[i] != '"' && text[i] != '\n') {
                            if (text[i] == '\\' && i + 1 < n) {
                                sb.append(text[i]).append(text[i + 1])
                                i += 2
                            } else {
                                sb.append(text[i])
                                i++
                            }
                        }
                        literals += sb.toString()
                        code.append("\"\"")
                        if (i < n && text[i] == '"') i++
                    }

                    c == '\'' -> {
                        val end =
                            if (i + 1 < n && text[i + 1] == '\\') {
                                text.indexOf('\'', i + 3).let { if (it < 0) i else it }
                            } else {
                                i + 2
                            }
                        code.append("''")
                        i = minOf(n - 1, end) + 1
                    }

                    else -> {
                        code.append(c)
                        i++
                    }
                }
            }
            return Lexed(code.toString(), literals)
        }

        /** R1 + R2 violations in one file's text: code lines naming org.sqlite/exposed, plus dialect-token string literals. */
        fun violations(text: String): Int {
            val lexed = lex(text)
            val r1 = lexed.code.lineSequence().count { R1.containsMatchIn(it) }
            val r2 = lexed.literals.count { R2.containsMatchIn(it) || R1.containsMatchIn(it) }
            return r1 + r2
        }
    }

    @Test
    fun `production code outside infrastructure sqlite matches the ratcheted dialect-neutrality baseline`() {
        val all = GuardSupport.productionSources()
        assertTrue(all.any { isExempt(it.path) }, "no infrastructure/sqlite sources in scope: misrooted Konsist scope")
        val actual = GuardSupport.countByFile(all.filterNot { isExempt(it.path) }) { violations(it.text) }
        val message =
            GuardSupport.ratchet(actual, GuardSupport.readBaseline("dialect-neutrality-baseline.txt"), "dialect-neutrality-baseline.txt")
        assertNull(message, "$message\n\nActual set (for re-seeding):\n${GuardSupport.asBaselineText(actual)}")
    }

    @Test
    fun `R1 flags an org sqlite or exposed import and a fully qualified reference`() {
        assertEquals(1, violations("import org.sqlite.SQLiteErrorCode"))
        assertEquals(1, violations("import org.jetbrains.exposed.sql.Database"))
        assertEquals(1, violations("val e: org.jetbrains.exposed.sql.Table? = null"))
        assertEquals(2, violations("import org.sqlite.JDBC\nval x = org.sqlite.SQLiteException::class"))
    }

    @Test
    fun `R1 ignores comments and KDoc`() {
        assertEquals(0, violations("// import org.sqlite.JDBC"))
        assertEquals(0, violations("/** See [org.jetbrains.exposed.sql.Table]. */\nclass A"))
        assertEquals(0, violations("/*\n * org.sqlite.JDBC\n */\nclass A"))
        assertEquals(0, violations("import org.sqlitefoo.X"))
    }

    @Test
    fun `R2 flags each dialect token in a simple literal`() {
        for (token in TOKEN_SAMPLES) {
            assertEquals(1, violations("val q = \"$token\""), token)
        }
    }

    @Test
    fun `R2 flags a token inside a raw multi-line literal`() {
        val q = "\"\"\""
        assertEquals(1, violations("val q = $q\n  SELECT 1\n  PRAGMA table_info(t)\n$q\nval y = 1"))
        assertEquals(2, violations("val a = $q\n x MATCH y\n$q\nval b = \"INSERT OR REPLACE\""))
        for (token in TOKEN_SAMPLES) {
            assertEquals(1, violations("val q = $q\n  SELECT 1\n  $token\n$q"), token)
        }
    }

    @Test
    fun `R1 also flags org sqlite and exposed names inside string literals`() {
        assertEquals(1, violations("Class.forName(\"org.sqlite.JDBC\")"))
        assertEquals(1, violations("val n = \"org.jetbrains.exposed.sql.Table\""))
        assertEquals(0, violations("val n = \"org.sqlitefoo.X\""))
        assertEquals(0, violations("// Class.forName(\"org.sqlite.JDBC\")"))
    }

    @Test
    fun `R2 ignores KDoc comments and ordinary prose that only resembles a token`() {
        assertEquals(0, violations("/** Uses PRAGMA and strftime(). */\nclass A"))
        assertEquals(0, violations("// datetime(x) julianday\nval a = 1"))
        assertEquals(0, violations("val h = \"If-Match\""))
        assertEquals(0, violations("val m = \"does not match\""))
        assertEquals(0, violations("val g = \"**/*.kt\"\nval c = '\"'"))
    }

    @Test
    fun `the sqlite prefix is exempt only with its trailing slash`() {
        assertTrue(isExempt("infrastructure/sqlite/repository/A.kt"))
        assertTrue(!isExempt("infrastructure/sqlitex/A.kt"))
        assertTrue(!isExempt("interfaces/api/v1/events/DeferredEventPublisher.kt"))
        val text = "import org.sqlite.JDBC"
        val sources =
            listOf(
                GuardSupport.Source("infrastructure/sqlite/A.kt", text),
                GuardSupport.Source("infrastructure/sqlitex/B.kt", text)
            )
        val counted = GuardSupport.countByFile(sources.filterNot { isExempt(it.path) }) { violations(it.text) }
        assertEquals(mapOf("infrastructure/sqlitex/B.kt" to 1), counted)
    }

    @Test
    fun `the ratchet fails on a new violation and on a stale or lowered baseline line and passes on equality`() {
        assertNull(GuardSupport.ratchet(mapOf("a.kt" to 4), mapOf("a.kt" to 4), "b.txt"))
        val newer = assertNotNull(GuardSupport.ratchet(mapOf("a.kt" to 5), mapOf("a.kt" to 4), "b.txt"))
        assertTrue("New violation" in newer, newer)
        val unlisted = assertNotNull(GuardSupport.ratchet(mapOf("new.kt" to 1), mapOf("a.kt" to 4), "b.txt"))
        assertTrue("new.kt" in unlisted, unlisted)
        val stale = assertNotNull(GuardSupport.ratchet(emptyMap(), mapOf("a.kt" to 4), "b.txt"))
        assertTrue("fixed" in stale, stale)
        val lowered = assertNotNull(GuardSupport.ratchet(mapOf("a.kt" to 3), mapOf("a.kt" to 4), "b.txt"))
        assertTrue("fixed" in lowered, lowered)
    }
}
