package io.github.jpicklyk.mcptask.current.domain.model

import io.github.jpicklyk.mcptask.current.domain.validation.ValidationException
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent tests (test-author seat, item 57d4dfc0, R26) for the typed blocked reason on the domain model: the wire vocabulary of
 * [BlockedReason], the one legacy-NULL rule ([WorkItem.effectiveBlockedReason]) and the three cross-field invariants of
 * [WorkItem.violations] (scenario S19 of the frozen test-plan).
 *
 * Oracles (never read from a run): task-scope D1 (the four wire words `awaiting_decision`, `dependency`, `resource`, `manual`;
 * `fromWire` returns null for anything else; `effectiveBlockedReason` is the stored reason, or `manual` when the role is blocked and
 * none is stored, and null outside blocked) and D2 (a stored reason requires role blocked; a stored ref requires reason
 * awaiting_decision; reason awaiting_decision requires a ref; a legacy blocked row with both NULL is valid). The violation texts are
 * the declared strings.
 *
 * Label: NEW-SURFACE (every declaration is introduced by R26). Probes: the wire words in another case and with another separator;
 * the empty word; every reason on every role; a ref on each non-awaiting reason; the legacy row.
 *
 * Negative-assertion fixture conditions: each "no violation" assertion is paired with a sibling item whose single changed field
 * produces the violation, so a validator that never reports anything fails the pair.
 *
 * Domain invariants satisfied by construction: items are built with the model's own defaults (a depth-0 root) and only the three
 * blocked fields and the role vary.
 *
 * Forbidden-construct declaration: none.
 */
class BlockedReasonDomainTest {
    /** The previous role of a blocked row, so the model's role/previous-role rules hold by construction. */
    private fun previousFor(role: Role): Role? = if (role == Role.BLOCKED) Role.WORK else null

    /** Built with diagnostics so a violating row is constructible (as a rehydrated one is); violations() still lists every violation. */
    private fun item(
        role: Role,
        reason: BlockedReason? = null,
        ref: UUID? = null
    ) = WorkItem(
        title = "blocked probe",
        role = role,
        previousRole = previousFor(role),
        blockedReason = reason,
        blockedRef = ref,
        diagnostics = listOf("rehydrated")
    )

    private fun blockedViolations(item: WorkItem): List<String> =
        item.violations().filter { it.startsWith("blockedReason") || it.startsWith("blockedRef") }

    private val ref: UUID = UUID.fromString("4f6c1c2e-7a53-4d0b-9e1f-0a2b3c4d5e6f")

    @Test
    fun `S19 constructing a violating item without diagnostics fails on the first violation`() {
        val reasonOnWork =
            assertFailsWith<ValidationException> { WorkItem(title = "probe", role = Role.WORK, blockedReason = BlockedReason.MANUAL) }
        assertTrue("blockedReason requires role blocked" in (reasonOnWork.message ?: ""), "got: ${reasonOnWork.message}")
        val awaitingNoRef =
            assertFailsWith<ValidationException> {
                WorkItem(title = "probe", role = Role.BLOCKED, previousRole = Role.WORK, blockedReason = BlockedReason.AWAITING_DECISION)
            }
        assertTrue("blockedReason awaiting_decision requires blockedRef" in (awaitingNoRef.message ?: ""), "got: ${awaitingNoRef.message}")
        // control: the same constructors succeed with a valid combination
        WorkItem(title = "probe", role = Role.BLOCKED, previousRole = Role.WORK, blockedReason = BlockedReason.MANUAL)
        WorkItem(
            title = "probe",
            role = Role.BLOCKED,
            previousRole = Role.WORK,
            blockedReason = BlockedReason.AWAITING_DECISION,
            blockedRef = ref
        )
    }

    @Test
    fun `S19 the wire words are the four reasons and nothing else parses`() {
        assertEquals(
            listOf("awaiting_decision", "dependency", "resource", "manual"),
            BlockedReason.entries.map { it.wire },
            "the vocabulary, in declaration order"
        )
        for (reason in BlockedReason.entries) {
            assertEquals(reason, BlockedReason.fromWire(reason.wire), "${reason.wire} parses to itself")
        }
        for (bad in listOf("Manual", "MANUAL", "awaiting-decision", "awaitingDecision", "", " manual", "manual ", "blocked", "decision")) {
            assertNull(BlockedReason.fromWire(bad), "'$bad' is not a reason")
        }
    }

    @Test
    fun `S19 a blocked item with no stored reason reads as manual and one with a stored reason reads as it`() {
        assertEquals(BlockedReason.MANUAL, item(Role.BLOCKED).effectiveBlockedReason, "the legacy rule: NULL on a blocked row is manual")
        for (reason in listOf(BlockedReason.DEPENDENCY, BlockedReason.RESOURCE, BlockedReason.MANUAL)) {
            assertEquals(reason, item(Role.BLOCKED, reason).effectiveBlockedReason, "a stored $reason is read as stored")
        }
        assertEquals(
            BlockedReason.AWAITING_DECISION,
            item(Role.BLOCKED, BlockedReason.AWAITING_DECISION, ref).effectiveBlockedReason,
            "a stored awaiting_decision is read as stored"
        )
    }

    @Test
    fun `S19 outside blocked there is no effective reason`() {
        for (role in listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)) {
            assertNull(item(role).effectiveBlockedReason, "a $role item has no blocked reason")
            // even a (violating) stored reason is not exposed as the effective one outside blocked
            assertNull(item(role, BlockedReason.RESOURCE).effectiveBlockedReason, "a stored reason on a $role item is not effective")
        }
    }

    @Test
    fun `S19 a stored reason requires role blocked`() {
        for (role in listOf(Role.QUEUE, Role.WORK, Role.REVIEW, Role.TERMINAL)) {
            assertTrue(
                "blockedReason requires role blocked" in blockedViolations(item(role, BlockedReason.MANUAL)),
                "a reason on a $role item is a violation: ${blockedViolations(item(role, BlockedReason.MANUAL))}"
            )
        }
        assertEquals(
            emptyList(),
            blockedViolations(item(Role.BLOCKED, BlockedReason.MANUAL)),
            "control: the same reason on a blocked item is valid"
        )
        assertEquals(emptyList(), blockedViolations(item(Role.WORK)), "control: no reason on a work item is valid")
    }

    @Test
    fun `S19 a stored ref requires reason awaiting_decision`() {
        for (reason in listOf(BlockedReason.MANUAL, BlockedReason.DEPENDENCY, BlockedReason.RESOURCE)) {
            assertTrue(
                "blockedRef requires blockedReason awaiting_decision" in blockedViolations(item(Role.BLOCKED, reason, ref)),
                "a ref with $reason is a violation: ${blockedViolations(item(Role.BLOCKED, reason, ref))}"
            )
        }
        assertTrue(
            "blockedRef requires blockedReason awaiting_decision" in blockedViolations(item(Role.BLOCKED, null, ref)),
            "a ref with no reason is a violation"
        )
        assertEquals(
            emptyList(),
            blockedViolations(item(Role.BLOCKED, BlockedReason.AWAITING_DECISION, ref)),
            "control: the ref with awaiting_decision is valid"
        )
    }

    @Test
    fun `S19 reason awaiting_decision requires a ref`() {
        assertTrue(
            "blockedReason awaiting_decision requires blockedRef" in blockedViolations(item(Role.BLOCKED, BlockedReason.AWAITING_DECISION)),
            "awaiting_decision without a ref is a violation: ${blockedViolations(item(Role.BLOCKED, BlockedReason.AWAITING_DECISION))}"
        )
        assertEquals(
            emptyList(),
            blockedViolations(item(Role.BLOCKED, BlockedReason.AWAITING_DECISION, ref)),
            "control: with a ref it is valid"
        )
    }

    @Test
    fun `S19 a legacy blocked row with both fields NULL is valid and the other reasons need no ref`() {
        assertEquals(emptyList(), blockedViolations(item(Role.BLOCKED)), "the legacy row")
        for (reason in listOf(BlockedReason.MANUAL, BlockedReason.DEPENDENCY, BlockedReason.RESOURCE)) {
            assertEquals(emptyList(), blockedViolations(item(Role.BLOCKED, reason)), "$reason needs no ref")
        }
        assertEquals(emptyList(), item(Role.BLOCKED).violations(), "the legacy row has no violation of any kind")
    }
}
