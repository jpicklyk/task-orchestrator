package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * P5b guard (task-scope section 6): production code never selects [OutsideUnitPolicy.IMPLICIT]. Every write
 * site runs inside a unit of work, the production default is FAIL, and only the test fixture may opt into
 * IMPLICIT (store-level seeding). Text based, code lines only (comments and KDoc are ignored).
 */
class OutsideUnitPolicyGuardTest {
    private val implicit = Regex("""\bOutsideUnitPolicy\.IMPLICIT\b""")

    fun violations(text: String): Int =
        text.lines().count { GuardSupport.isCodeLine(it) && implicit.containsMatchIn(GuardSupport.stripStrings(it)) }

    @Test
    fun `production code never selects OutsideUnitPolicy IMPLICIT`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources()) { violations(it.text) }
        assertEquals(emptyMap(), actual, "OutsideUnitPolicy.IMPLICIT is test-only (production default is FAIL)")
    }

    @Test
    fun `the detector flags code and ignores comments`() {
        assertEquals(1, violations("val p = OutsideUnitPolicy.IMPLICIT"))
        assertEquals(0, violations("// OutsideUnitPolicy.IMPLICIT"))
        assertEquals(0, violations(" * [OutsideUnitPolicy.IMPLICIT]"))
        assertEquals(0, violations("val p = OutsideUnitPolicy.FAIL"))
    }
}
