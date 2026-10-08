package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P7 (item beeef6f7) domain-level scenarios: the single claim-activity predicate, the claim-status partition and
 * the total WorkItem validation model (diagnostics + violations()).
 *
 * Oracles (frozen test-plan, authored before implementation): plan 3.12 l.359-361 and AR-43 (an instant equal to the
 * claim expiry is EXPIRED: a claim is active only while expiresAt is strictly after the instant asked about);
 * plan 3.11 l.344-345 and AR-45 (a stored row that violates domain rules is returned with its diagnostics instead of
 * being dropped; validation messages are the pre-existing strings).
 *
 * Scenario labels: S3 NEW-SURFACE (ClaimState). S8 domain half NEW-SURFACE (WorkItem.diagnostics / violations()).
 * Fixtures satisfy the WorkItem invariants by construction (claimedAt <= expiresAt, originalClaimedAt <= claimedAt,
 * all four claim fields set together); the partial-claim fixtures that cannot satisfy them are built through the
 * declared `diagnostics` parameter, which is the designed path for a rehydrated invalid row.
 */
class ClaimStatePredicateTest {
    // T is far from wall-clock time on purpose, so a leaked Instant.now() cannot pass by accident.
    private val t: Instant = Instant.parse("2001-02-03T04:05:06.789Z")
    private val expiry: Instant = t.plusSeconds(60) // E

    private fun state() = ClaimState(claimedBy = "agent-a", claimedAt = t, expiresAt = expiry, originalClaimedAt = t)

    private fun claimedItem(
        claimedBy: String = "agent-a",
        expiresAt: Instant = expiry
    ) = WorkItem(
        title = "claimed",
        claimedBy = claimedBy,
        claimedAt = t,
        claimExpiresAt = expiresAt,
        originalClaimedAt = t
    )

    // ---- S3: the predicate boundary --------------------------------------------------------------------------

    @Test
    fun `S3 a claim is active one millisecond before its expiry and expired at and after it`() {
        val state = state()
        assertTrue(state.isActive(expiry.minusMillis(1)), "E-1ms must be active")
        assertFalse(state.isActive(expiry), "an instant equal to E is EXPIRED, not active")
        assertFalse(state.isActive(expiry.plusMillis(1)), "E+1ms must be expired")
    }

    @Test
    fun `S3 probe sub-millisecond distance from the expiry decides the same way`() {
        val state = state()
        assertTrue(state.isActive(expiry.minus(Duration.ofNanos(500_000))), "E-500us is before E, so active")
        assertFalse(state.isActive(expiry.plus(Duration.ofNanos(500_000))), "E+500us is after E, so expired")
        assertTrue(state.isActive(expiry.minusNanos(1)), "E-1ns is still strictly before E")
    }

    @Test
    fun `S3 the item-based predicate agrees with the state-based predicate at the boundary`() {
        val item = claimedItem()
        assertTrue(ClaimState.isActive(item, expiry.minusMillis(1)))
        assertFalse(ClaimState.isActive(item, expiry))
        assertFalse(ClaimState.isActive(item, expiry.plusMillis(1)))
    }

    @Test
    fun `S3 of() returns null for an unclaimed item and a state mirroring the four claim fields for a claimed one`() {
        assertNull(ClaimState.of(WorkItem(title = "unclaimed")))

        val state = assertNotNull(ClaimState.of(claimedItem()))
        assertEquals("agent-a", state.claimedBy)
        assertEquals(t, state.claimedAt)
        assertEquals(expiry, state.expiresAt)
        assertEquals(t, state.originalClaimedAt)
    }

    @Test
    fun `S3 probe a row with claimedBy set but a null expiry is never active`() {
        // Cannot be built through validation (all four claim fields must be set together): it exists only as a
        // rehydrated invalid row, which carries diagnostics.
        val partial =
            WorkItem(
                title = "partial claim",
                claimedBy = "agent-a",
                claimedAt = t,
                claimExpiresAt = null,
                originalClaimedAt = null,
                diagnostics =
                    listOf(
                        "Claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt) must all be set or all be null"
                    )
            )
        assertNull(ClaimState.of(partial), "no expiry means no claim state")
        // Asked at three instants (long before, at claimedAt, long after): never active.
        listOf(t.minus(Duration.ofDays(1)), t, t.plus(Duration.ofDays(1))).forEach { at ->
            assertFalse(ClaimState.isActive(partial, at), "a claim without an expiry is never active (at=$at)")
        }
    }

    @Test
    fun `S3 probe claimedBy null with an expiry present yields no claim state`() {
        val orphanExpiry =
            WorkItem(
                title = "orphan expiry",
                claimedBy = null,
                claimedAt = null,
                claimExpiresAt = expiry,
                originalClaimedAt = null,
                diagnostics =
                    listOf(
                        "Claim fields (claimedBy, claimedAt, claimExpiresAt, originalClaimedAt) must all be set or all be null"
                    )
            )
        assertNull(ClaimState.of(orphanExpiry))
        assertFalse(ClaimState.isActive(orphanExpiry, expiry.minusMillis(1)))
    }

    // ---- ClaimState ordering violations (pre-existing messages) ----------------------------------------------

    @Test
    fun `orderingViolations reports each out-of-order pair with the established message`() {
        assertEquals(emptyList(), ClaimState.orderingViolations(t, expiry, t))
        assertEquals(emptyList(), ClaimState.orderingViolations(null, null, null), "absent claim fields have no ordering")

        val claimedAfterExpiry = ClaimState.orderingViolations(expiry.plusMillis(1), expiry, t)
        assertTrue("claimedAt must not be after claimExpiresAt" in claimedAfterExpiry, "got: $claimedAfterExpiry")

        val originalAfterClaimed = ClaimState.orderingViolations(t, expiry, t.plusMillis(1))
        assertTrue("originalClaimedAt must not be after claimedAt" in originalAfterClaimed, "got: $originalAfterClaimed")
    }

    // ---- ClaimStatus partition -------------------------------------------------------------------------------

    @Test
    fun `ClaimStatus partitions an item into claimed, expired and unclaimed with the boundary instant expired`() {
        assertEquals(ClaimStatus.UNCLAIMED, ClaimStatus.of(WorkItem(title = "unclaimed"), expiry))
        val item = claimedItem()
        assertEquals(ClaimStatus.CLAIMED, ClaimStatus.of(item, expiry.minusMillis(1)))
        assertEquals(ClaimStatus.EXPIRED, ClaimStatus.of(item, expiry))
        assertEquals(ClaimStatus.EXPIRED, ClaimStatus.of(item, expiry.plusMillis(1)))
    }

    @Test
    fun `ClaimStatus wire values round trip and an absent wire value parses to null`() {
        assertEquals("claimed", ClaimStatus.CLAIMED.wire)
        assertEquals("unclaimed", ClaimStatus.UNCLAIMED.wire)
        assertEquals("expired", ClaimStatus.EXPIRED.wire)
        ClaimStatus.entries.forEach { status ->
            assertEquals(status, ClaimStatus.fromWire(status.wire), "round trip of ${status.wire}")
        }
        assertNull(ClaimStatus.fromWire(null))
    }

    // ---- S8 (domain half): total validation model ------------------------------------------------------------

    @Test
    fun `S8 an invalid WorkItem still throws on construction when no diagnostics are supplied`() {
        assertFailsWith<ValidationException> { WorkItem(title = "x".repeat(501)) }
    }

    @Test
    fun `S8 an invalid row is constructible with diagnostics and violations lists the established messages`() {
        val longTitle = WorkItem(title = "x".repeat(600), diagnostics = listOf("rehydrated"))
        assertEquals(listOf("rehydrated"), longTitle.diagnostics)
        assertTrue("Title must not exceed 500 characters" in longTitle.violations(), "got: ${longTitle.violations()}")

        val cases: List<Pair<WorkItem, String>> =
            listOf(
                WorkItem(title = "  ", diagnostics = listOf("d")) to "Title must not be blank",
                WorkItem(title = "ok", summary = "s".repeat(2001), diagnostics = listOf("d")) to "Summary must not exceed 2000 characters",
                WorkItem(title = "ok", depth = -1, diagnostics = listOf("d")) to "Depth must be non-negative",
                WorkItem(title = "ok", parentId = null, depth = 2, diagnostics = listOf("d")) to "Root items must have depth 0",
                WorkItem(title = "ok", parentId = UUID.randomUUID(), depth = 0, diagnostics = listOf("d")) to
                    "Child items must have depth >= 1",
                WorkItem(title = "ok", complexity = 11, diagnostics = listOf("d")) to "complexity must be between 1 and 10",
                WorkItem(title = "ok", description = " ", diagnostics = listOf("d")) to "Description, if provided, must not be blank"
            )
        cases.forEach { (item, expectedFragment) ->
            assertTrue(
                item.violations().any { it.contains(expectedFragment) },
                "violations() must contain '$expectedFragment', got ${item.violations()}"
            )
        }
    }

    @Test
    fun `S8 violations is empty for a valid item and validate throws the first violation for an invalid one`() {
        assertEquals(emptyList(), WorkItem(title = "valid").violations())
        assertNull(WorkItem(title = "valid").diagnostics, "a freshly built valid item carries no diagnostics")

        val invalid = WorkItem(title = "x".repeat(600), diagnostics = listOf("rehydrated"))
        val thrown = assertFailsWith<ValidationException> { invalid.validate() }
        assertTrue(
            (thrown.message ?: "").contains("Title must not exceed 500 characters"),
            "validate() must surface the violation message, got: ${thrown.message}"
        )
    }

    @Test
    fun `S8 probe a diagnostics list is carried through copy so an invalid item cannot silently become valid`() {
        val invalid = WorkItem(title = "x".repeat(600), diagnostics = listOf("rehydrated"))
        val renamedOnly = invalid.copy(summary = "changed")
        assertEquals(listOf("rehydrated"), renamedOnly.diagnostics)
        assertTrue(renamedOnly.violations().isNotEmpty(), "the title is still too long after an unrelated copy")

        val repaired = invalid.copy(title = "short", diagnostics = null)
        assertEquals(emptyList(), repaired.violations())
        assertNull(repaired.diagnostics)
    }
}
