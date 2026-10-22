package io.github.jpicklyk.mcptask.current.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Claim-predicate confinement guard (P7, AR-43): "is this claim still active?" has ONE definition. A comparison of
 * `claimExpiresAt` / `claim_expires_at` (an ordering operator, `isAfter` / `isBefore`, or an Exposed `less` /
 * `greater` call) may appear only in `ClaimState.kt` (the Kotlin predicate) and `SqliteClaimStore.kt` (the SQL
 * builder). Anywhere else a freshness decision would drift from the single boundary rule (expiry equal to now is
 * expired). Counted per file against the two-way ratcheted baseline `claim-predicate-baseline.txt`, which is empty.
 */
class ClaimPredicateConfinementTest {
    companion object {
        val ALLOWED = setOf("domain/model/ClaimState.kt", "infrastructure/sqlite/repository/SqliteClaimStore.kt")
        const val BASELINE = "claim-predicate-baseline.txt"

        private val FIELD = Regex("""claimExpiresAt|claim_expires_at""")
        private val COMPARISON = Regex("""\s(<=?|>=?)\s|\.isAfter\(|\.isBefore\(|\bless(Eq)?\b|\bgreater(Eq)?\b|compareTo""")

        fun violations(text: String): Int =
            text
                .lines()
                .filter { GuardSupport.isCodeLine(it) }
                .count { FIELD.containsMatchIn(it) && COMPARISON.containsMatchIn(it) }
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
}
