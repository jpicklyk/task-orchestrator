package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Claim-predicate confinement guard (P7, AR-43): "is this claim still active?" has ONE definition. A comparison of
 * `claimExpiresAt` / `claim_expires_at` (an ordering operator, `isAfter` / `isBefore`, or an Exposed `less` /
 * `greater` call) may appear only in `ClaimState.kt` (the Kotlin predicate) and `ClaimPredicates.kt` (the SQL
 * builder). Anywhere else a freshness decision would drift from the single boundary rule (expiry equal to now is
 * expired). Counted per file against the two-way ratcheted baseline `claim-predicate-baseline.txt`, which is empty.
 */
class ClaimPredicateConfinementTest {
    companion object {
        val ALLOWED = setOf("model/claim/ClaimState.kt", "platform/sqlite/repository/ClaimPredicates.kt")
        const val BASELINE = "claim-predicate-baseline.txt"

        private val FIELD = Regex("""claimExpiresAt|claim_expires_at""")
        private val COMPARISON = Regex("""\s(<=?|>=?)\s|\.isAfter\(|\.isBefore\(|\bless(Eq)?\b|\bgreater(Eq)?\b|compareTo""")

        private val FUN_LINE = Regex("""\bfun\b""")
        private val BINDING = Regex("""\b(?:val|var)\s+(\w+)\b[^=]*=(?!=)(.*)""")

        /**
         * Counts the code lines that compare the claim expiry. A comparison on a line that holds the field directly
         * counts; so does one on a line that holds a local name bound earlier in the same function from an expression
         * containing the field (`val e = item.claimExpiresAt ?: return false` then `e.isAfter(now)`). The taint set
         * resets on every line containing `fun`; a name preceded by `.` or a word character (`other.e`) never counts.
         * Known limits: a lambda parameter bound on another line, and comparisons of `ClaimState.expiresAt` itself.
         */
        fun violations(text: String): Int {
            var tainted = emptySet<String>()
            var count = 0
            for (line in text.lines().filter { GuardSupport.isCodeLine(it) }) {
                if (FUN_LINE.containsMatchIn(line)) tainted = emptySet()
                val holdsTaint =
                    FIELD.containsMatchIn(line) ||
                        tainted.any { name -> Regex("""(?<![.\w])${Regex.escape(name)}\b""").containsMatchIn(line) }
                if (holdsTaint && COMPARISON.containsMatchIn(line)) count++
                val binding = BINDING.find(line)
                if (binding != null && FIELD.containsMatchIn(binding.groupValues[2])) tainted = tainted + binding.groupValues[1]
            }
            return count
        }
    }

    @Test
    fun `claim expiry is compared only in the claim predicate files`() {
        val actual = GuardSupport.countByFile(GuardSupport.productionSources().filter { it.path !in ALLOWED }) { violations(it.text) }
        val message = GuardSupport.ratchet(actual, GuardSupport.readBaseline(BASELINE), BASELINE)
        assertEquals(null, message, message)
    }

    @Test
    fun `the guard flags comparisons and ignores plain reads and assignments`() {
        listOf(
            "if (item.claimExpiresAt.isAfter(now)) {",
            "val ok = item.claimExpiresAt > now",
            "WorkItemsTable.claimExpiresAt lessEq at",
            "WHERE claim_expires_at <= ?"
        ).forEach { assertTrue(violations(it) == 1, "not flagged: $it") }
        listOf(
            "it[claimExpiresAt] = item.claimExpiresAt",
            "val e = row[WorkItemsTable.claimExpiresAt]",
            "claimExpiresAt = if (a == b) null else current.claimExpiresAt"
        ).forEach { assertEquals(0, violations(it), "wrongly flagged: $it") }
    }

    @Test
    fun `the guard follows a local bound from the claim expiry`() {
        val twoLine =
            """
            fun lapsed(item: WorkItem): Boolean {
                val e = item.claimExpiresAt ?: return false
                if (!e.isAfter(now)) return true
                return false
            }
            """.trimIndent()
        assertEquals(1, violations(twoLine))
        val dotted =
            """
            fun f() {
                val e = item.claimExpiresAt
                other.e.isAfter(now)
            }
            """.trimIndent()
        assertEquals(0, violations(dotted))
        val laterFun =
            """
            fun a() {
                val e = item.claimExpiresAt
            }
            fun b() {
                if (e.isAfter(now)) return
            }
            """.trimIndent()
        assertEquals(0, violations(laterFun))
    }

    @Test
    fun `the allowed files exist in production sources`() {
        val paths = GuardSupport.productionSources().map { it.path }.toSet()
        ALLOWED.forEach { assertTrue(it in paths, "allowed file is not a production source: $it") }
    }
}
