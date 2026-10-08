package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Cancellation-safety guard (item 9343ad8d, S10): in production code a catch-all (`catch (e: Exception)` or
 * `catch (e: Throwable)`) must begin with `e.rethrowIfCancellation()` (or `if (e is CancellationException) throw e`)
 * on its first statement, and `runCatching {` is forbidden (use runCatchingNonCancellation). Oracle: task-scope
 * section 7 (CancellationSafetyTest) and carry-in 3 / 9b: two-way ratcheted baseline
 * `cancellation-safety-baseline.txt` (`<path> <count>`), seeded from today's sites and burned down by P5b; annotated
 * catches such as `catch (@Suppress("TooGenericExceptionCaught") e: Exception)` are detected too. Limits: text
 * based; `catch (e: SQLException)` is not covered; a catch header split across lines is not matched.
 */
class CancellationSafetyTest {
    companion object {
        val CATCH_ALL =
            Regex("""catch\s*\(\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(\w+)\s*:\s*(?:kotlin\.|java\.lang\.)?(Exception|Throwable)\s*\)""")
        val RUN_CATCHING = Regex("""\brunCatching\s*\{""")
        val CANCELLATION_CATCH = Regex("""catch\s*\(\s*\w+\s*:\s*(?:[\w.]*\.)?CancellationException\s*\)""")

        private fun rethrows(
            statement: String,
            name: String
        ): Boolean =
            statement.startsWith("$name.rethrowIfCancellation()") ||
                Regex(
                    """^if\s*\(\s*${Regex.escape(name)}\s+is\s+(?:[\w.]*\.)?CancellationException\s*\)\s*throw\s+${Regex.escape(name)}\b"""
                ).containsMatchIn(statement)

        fun violations(text: String): Int {
            val lines = text.lines()
            var count = 0
            for ((i, line) in lines.withIndex()) {
                if (!GuardSupport.isCodeLine(line)) continue
                var hit = RUN_CATCHING.containsMatchIn(line)
                val m = CATCH_ALL.find(line)
                if (m != null) {
                    val name = m.groupValues[1]
                    var rest =
                        line
                            .substring(m.range.last + 1)
                            .trim()
                            .removePrefix("{")
                            .trim()
                    if (rest.isEmpty()) {
                        val following = lines.drop(i + 1).filter(GuardSupport::isCodeLine).map { it.trim() }
                        rest = following.firstOrNull().orEmpty()
                        if (rest == "{") rest = following.drop(1).firstOrNull().orEmpty()
                    }
                    val earlierClause =
                        lines
                            .subList(
                                maxOf(0, i - 8),
                                i
                            ).reversed()
                            .takeWhile { !it.contains("try") }
                            .any { CANCELLATION_CATCH.containsMatchIn(it) }
                    if (!earlierClause && !rethrows(rest, name)) hit = true
                }
                if (hit) count++
            }
            return count
        }
    }

    @Test
    fun `production code matches the ratcheted cancellation-safety baseline`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { violations(it.text) }
        val message =
            GuardSupport.ratchet(actual, GuardSupport.readBaseline("cancellation-safety-baseline.txt"), "cancellation-safety-baseline.txt")
        assertNull(message, "$message\n\nActual set (for re-seeding):\n${GuardSupport.asBaselineText(actual)}")
    }

    @Test
    fun `a catch-all that rethrows cancellation first is clean`() {
        assertEquals(0, violations("try {\n} catch (e: Exception) {\n    e.rethrowIfCancellation()\n    log(e)\n}"))
        assertEquals(0, violations("try {\n} catch (t: Throwable) {\n    // handled\n\n    t.rethrowIfCancellation()\n}"))
        assertEquals(0, violations("try {\n} catch (e: Exception) {\n    if (e is CancellationException) throw e\n    log(e)\n}"))
        assertEquals(0, violations("try {\n} catch (e: Exception) { e.rethrowIfCancellation(); log(e) }"))
        assertEquals(0, violations("try {\n} catch (e: Exception)\n{\n    e.rethrowIfCancellation()\n}"))
    }

    @Test
    fun `a catch-all without the rethrow is flagged`() {
        assertEquals(1, violations("try {\n} catch (e: Exception) {\n    log(e)\n}"))
        assertEquals(1, violations("try {\n} catch (e: Throwable) {\n    log(e)\n}"))
        assertEquals(1, violations("try {\n} catch (e: Exception) { return null }"))
        assertEquals(1, violations("try {\n} catch (e: Exception) {\n    other.rethrowIfCancellation()\n}"))
        assertEquals(1, violations("try {\n} catch (e: Exception) {\n    log(e)\n    e.rethrowIfCancellation()\n}"))
    }

    @Test
    fun `a catch-all after a CancellationException clause of the same try is clean`() {
        assertEquals(0, violations("try {\n} catch (e: CancellationException) {\n    throw e\n} catch (e: Exception) {\n    log(e)\n}"))
        assertEquals(1, violations("try {\n} catch (e: IOException) {\n    log(e)\n} catch (e: Exception) {\n    log(e)\n}"))
    }

    @Test
    fun `an annotated catch-all is detected (carry-in 9b)`() {
        assertEquals(1, violations("} catch (@Suppress(\"TooGenericExceptionCaught\") e: Exception) {\n    log(e)\n}"))
        assertEquals(0, violations("} catch (@Suppress(\"TooGenericExceptionCaught\") e: Exception) {\n    e.rethrowIfCancellation()\n}"))
    }

    @Test
    fun `runCatching is always flagged but its non-cancellation variant is not`() {
        assertEquals(1, violations("val r = runCatching { work() }"))
        assertEquals(0, violations("val r = runCatchingNonCancellation { work() }"))
        assertEquals(0, violations("// runCatching { work() }"))
    }

    @Test
    fun `narrow catches are outside the guard`() {
        assertEquals(0, violations("try {\n} catch (e: SQLException) {\n    log(e)\n}"))
        assertTrue(CATCH_ALL.find("catch (e: Exception)") != null)
    }
}
