package io.github.jpicklyk.mcptask.current.domain.model

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Independent tests (test-author seat, item f65fef01, R29) for the one shared fold of a legacy blocked reason: scenario S3, the pure
 * half (EXISTING-SURFACE behaviour behind NEW functions, so a plain revert is a compile failure here and the behavioural proof lives in
 * `BlockedReasonFoldRollupTest`, which drives the read paths that used to fold on their own).
 *
 * Oracles (never read from a run): the KDoc of [BlockedReason] ("the stored and on-the-wire spelling" is [BlockedReason.wire]) and the
 * V37 / R26 rule the task-scope D2 restates: a BLOCKED row or event that names no reason reads as `manual`, and so does a wire word
 * that is not one of the four spellings; a stored reason is returned unchanged.
 *
 * Negative-assertion fixture conditions: every "reads manual" assertion is paired with the reasons that must NOT collapse to manual
 * (the three others) so a fold that always answers manual fails the pair.
 *
 * Domain invariants satisfied by construction: inputs are the enum's own entries and their wire spellings.
 *
 * Forbidden-construct declaration: none. No assumeTrue, no @Disabled, no swallowed assertions.
 */
class BlockedReasonFoldTest {
    @Test
    fun `S3 effective keeps every stored reason and reads an absent one as manual`() {
        for (reason in BlockedReason.entries) {
            assertEquals(reason, BlockedReason.effective(reason), "a stored ${reason.wire} is unchanged")
        }
        assertEquals(BlockedReason.MANUAL, BlockedReason.effective(null), "a legacy NULL reads manual")
    }

    @Test
    fun `S3 effectiveOfWire maps each of the four spellings to its reason`() {
        assertEquals(BlockedReason.AWAITING_DECISION, BlockedReason.effectiveOfWire("awaiting_decision"))
        assertEquals(BlockedReason.DEPENDENCY, BlockedReason.effectiveOfWire("dependency"))
        assertEquals(BlockedReason.RESOURCE, BlockedReason.effectiveOfWire("resource"))
        assertEquals(BlockedReason.MANUAL, BlockedReason.effectiveOfWire("manual"))
        for (reason in BlockedReason.entries) {
            assertEquals(reason, BlockedReason.effectiveOfWire(reason.wire), "${reason.wire} round-trips")
            assertEquals(BlockedReason.effective(BlockedReason.fromWire(reason.wire)), BlockedReason.effectiveOfWire(reason.wire))
        }
    }

    @Test
    fun `S3 effectiveOfWire reads an absent or unknown word as manual`() {
        assertEquals(BlockedReason.MANUAL, BlockedReason.effectiveOfWire(null), "absent")
        for (unknown in listOf("", "bogus", "awaiting-decision", "resource_", "blocked", "none", "NULL")) {
            assertEquals(BlockedReason.MANUAL, BlockedReason.effectiveOfWire(unknown), "'$unknown' is not a wire spelling")
        }
    }
}
