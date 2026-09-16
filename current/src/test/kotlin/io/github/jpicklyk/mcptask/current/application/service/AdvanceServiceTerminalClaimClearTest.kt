package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.ActorClaim
import io.github.jpicklyk.mcptask.current.domain.model.ActorKind
import io.github.jpicklyk.mcptask.current.domain.model.DegradedModePolicy
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.VerificationResult
import io.github.jpicklyk.mcptask.current.domain.model.VerificationStatus
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.NoteRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bug `3785f37a`: claims are never cleared when a transition reaches TERMINAL, and `reopen`
 * resurrects a stale claim. Fix: [RoleTransitionHandler.applyTransition]'s existing item-copy
 * block now clears all four claim fields (`claimedBy`, `claimedAt`, `claimExpiresAt`,
 * `originalClaimedAt`) whenever `targetRole == TERMINAL` or `previousRole == TERMINAL`.
 *
 * Exercised entirely through [AdvanceService.advance] — the single pipeline shared by the MCP
 * `advance_item` tool (enforceOwnership = true), `complete_tree`, and cascades. All scenarios are
 * EXISTING-SURFACE per the frozen test-plan: no new production signature is introduced by the fix,
 * so a narrowest-revert of the copy-block change alone must turn every clearing assertion below red.
 *
 * Oracles (never derived from the implementation):
 * - O1 `api-reference.md:1727` — terminal items cannot be claimed; QUEUE/WORK/REVIEW/BLOCKED can.
 * - O2 `WorkItem.validate()` — the four claim fields are all-null or all-set together (already
 *   exhaustively probed by `WorkItemTest.kt`'s claim-field suite; not re-probed here).
 * - O3 `advance_item` trigger table — `reopen` TERMINAL->QUEUE; `cancel` -> TERMINAL, label "cancelled".
 * - O4 `api-reference.md:1730` — passive expiry, no reaper: an expired claim is still stored data
 *   until something clears it.
 * - O5 `api-reference.md:1135` / `:599-600` — MCP paths enforce claim ownership on every trigger;
 *   REST does not (covered for REST in `AdvanceRouteClaimClearTest`).
 *
 * Mock harness mirrors [AdvanceServiceTest]: repositories are MockK doubles, `inTransaction`
 * delegates directly to its block (no real DB transaction here).
 */
class AdvanceServiceTerminalClaimClearTest {
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var depRepo: DependencyRepository
    private lateinit var roleTransitionRepo: RoleTransitionRepository
    private lateinit var noteRepo: NoteRepository

    /** Every [WorkItem] passed to `workItemRepo.update(...)` during a test, in call order. */
    private val updatedItems = mutableListOf<WorkItem>()

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        depRepo = mockk()
        roleTransitionRepo = mockk()
        noteRepo = mockk()
        updatedItems.clear()

        coEvery { workItemRepo.dbNow() } returns Instant.now()
        coEvery { workItemRepo.update(any()) } answers {
            val item = firstArg<WorkItem>()
            updatedItems.add(item)
            Result.Success(item)
        }
        coEvery { workItemRepo.inTransaction(any()) } coAnswers {
            firstArg<suspend () -> Unit>().invoke()
        }
        coEvery { roleTransitionRepo.create(any()) } returns Result.Success(mockk())
        coEvery { noteRepo.findByItemId(any()) } returns Result.Success(emptyList())
        every { depRepo.findByToItemId(any()) } returns emptyList()
        every { depRepo.findByFromItemId(any()) } returns emptyList()
    }

    /**
     * Declared by the contract (`AdvanceServiceTest.kt:64-89`) — one captured [Instant] so
     * `claimedAt == originalClaimedAt`, avoiding CI clock drift across two `Instant.now()` calls.
     */
    private fun makeItem(
        id: UUID = UUID.randomUUID(),
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        title: String = "Item",
        parentId: UUID? = null,
        claimedBy: String? = null,
        claimExpiresAt: Instant? = null,
    ): WorkItem {
        val claimInstant = if (claimedBy != null) Instant.now() else null
        return WorkItem(
            id = id,
            title = title,
            role = role,
            previousRole = previousRole,
            parentId = parentId,
            depth = if (parentId != null) 1 else 0,
            claimedBy = claimedBy,
            claimedAt = claimInstant,
            claimExpiresAt = claimExpiresAt,
            originalClaimedAt = claimInstant,
        )
    }

    private fun serviceWith(): AdvanceService =
        AdvanceService(
            workItemRepository = workItemRepo,
            roleTransitionRepository = roleTransitionRepo,
            dependencyRepository = depRepo,
            noteRepository = noteRepo,
            statusLabelService = NoOpStatusLabelService,
            schemaResolver = { null },
        )

    private fun assertAllClaimFieldsNull(item: WorkItem) {
        assertNull(item.claimedBy, "claimedBy must be cleared")
        assertNull(item.claimedAt, "claimedAt must be cleared")
        assertNull(item.claimExpiresAt, "claimExpiresAt must be cleared")
        assertNull(item.originalClaimedAt, "originalClaimedAt must be cleared")
    }

    // ──────────────────────────────────────────────
    // S1 — happy: live claim, complete -> TERMINAL clears all four. O1
    // ──────────────────────────────────────────────

    @Test
    fun `S1 complete on a live-claimed WORK item clears all four claim fields`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.WORK,
                    claimedBy = "agent-live",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            // Sanity: the fixture really is claimed before the transition.
            assertNotNull(item.claimedBy)

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // S2 — happy: after S1, reopen -> QUEUE stays unclaimed. O1+O3
    // ──────────────────────────────────────────────

    @Test
    fun `S2 reopen after a cleared terminal completion leaves the item unclaimed`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.WORK,
                    claimedBy = "agent-live",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val service = serviceWith()
            val completed =
                assertIs<AdvanceOutcome.Success>(
                    service.advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true),
                )
            assertAllClaimFieldsNull(completed.result.appliedItem)

            val reopened =
                assertIs<AdvanceOutcome.Success>(
                    service.advance(
                        completed.result.appliedItem,
                        "reopen",
                        null,
                        null,
                        null,
                        DegradedModePolicy.ACCEPT_CACHED,
                        true,
                    ),
                )
            assertEquals(Role.QUEUE, reopened.result.newRole)
            assertAllClaimFieldsNull(reopened.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // S3 — edge/legacy: item already TERMINAL with a stale live claim (pre-fix row);
    // reopen -> QUEUE must clear it via the previousRole == TERMINAL clause. O1
    // ──────────────────────────────────────────────

    @Test
    fun `S3 reopen on a legacy terminal item that still carries a stale claim clears it`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.TERMINAL,
                    claimedBy = "agent-stale",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            assertNotNull(item.claimedBy, "fixture must model a pre-fix row: terminal but still claimed")

            val outcome =
                serviceWith().advance(item, "reopen", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.QUEUE, success.result.newRole)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // S4 — edge: claim already EXPIRED (passive expiry, O4) at the moment of completion.
    // The clear is unconditional on expiry state — an already-expired claim is cleared exactly
    // like a live one. O4
    // ──────────────────────────────────────────────

    @Test
    fun `S4 complete on an item whose claim already expired still clears all four fields`(): Unit =
        runBlocking {
            // Derive dependent fields from ONE captured instant so claimedAt <= claimExpiresAt and
            // originalClaimedAt <= claimedAt both hold for an already-expired claim (rule 6).
            val past = Instant.now().minusSeconds(1_000)
            val item =
                makeItem(role = Role.WORK).copy(
                    claimedBy = "agent-expired",
                    claimedAt = past,
                    claimExpiresAt = past.plusSeconds(500),
                    originalClaimedAt = past,
                )
            assertTrue(item.claimExpiresAt!!.isBefore(Instant.now()), "fixture must model an already-expired claim")

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // S5 — edge: cancel from QUEUE -> TERMINAL clears the claim AND stamps the "cancelled" label. O3
    // ──────────────────────────────────────────────

    @Test
    fun `S5 cancel on a claimed QUEUE item clears claim fields and stamps the cancelled label`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.QUEUE,
                    claimedBy = "agent-cancel",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )

            val outcome =
                serviceWith().advance(item, "cancel", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
            assertEquals("cancelled", success.result.statusLabel)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // S6 — edge: a claimed PARENT terminalized by CASCADE from its last child also gets cleared.
    // Mirrors AdvanceServiceTest's "terminal cascade emitted when completing the last child". O1
    // ──────────────────────────────────────────────

    @Test
    fun `S6 cascade-terminalized parent has its claim fields cleared`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val childId = UUID.randomUUID()
            val parent =
                makeItem(
                    id = parentId,
                    role = Role.WORK,
                    title = "Parent",
                    claimedBy = "agent-parent",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val child = makeItem(id = childId, role = Role.WORK, title = "Child", parentId = parentId)

            coEvery { workItemRepo.getById(childId) } returns Result.Success(child)
            coEvery { workItemRepo.getById(parentId) } returns Result.Success(parent)
            coEvery { workItemRepo.countChildrenByRole(parentId) } returns Result.Success(mapOf(Role.TERMINAL to 1))

            val outcome =
                serviceWith().advance(child, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(1, success.result.cascadeEvents.size)
            val cascade = success.result.cascadeEvents.first()
            assertEquals(parentId, cascade.itemId)
            assertEquals(Role.TERMINAL, cascade.targetRole)
            assertTrue(cascade.applied)

            // The persisted parent row (captured off workItemRepo.update) must carry the clear —
            // CascadeEvent itself does not expose the post-transition item, so the update capture
            // is the only way to observe it without touching src/main.
            val persistedParent = updatedItems.first { it.id == parentId }
            assertAllClaimFieldsNull(persistedParent)
        }

    // ──────────────────────────────────────────────
    // S7 — negative: start on a claimed QUEUE item (-> WORK, non-terminal) must NOT clear. O5
    // ──────────────────────────────────────────────

    @Test
    fun `S7 start on a claimed QUEUE item leaves all four claim fields unchanged`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.QUEUE,
                    claimedBy = "agent-start",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )

            val outcome =
                serviceWith().advance(item, "start", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.WORK, success.result.newRole)
            val applied = success.result.appliedItem
            assertEquals(item.claimedBy, applied.claimedBy)
            assertEquals(item.claimedAt, applied.claimedAt)
            assertEquals(item.claimExpiresAt, applied.claimExpiresAt)
            assertEquals(item.originalClaimedAt, applied.originalClaimedAt)
        }

    // ──────────────────────────────────────────────
    // S8 — negative: block then resume (never touching TERMINAL) preserves the claim throughout.
    // ──────────────────────────────────────────────

    @Test
    fun `S8 block then resume on a claimed WORK item preserves the claim across both transitions`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.WORK,
                    claimedBy = "agent-block",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val service = serviceWith()

            val blocked =
                assertIs<AdvanceOutcome.Success>(
                    service.advance(item, "block", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true),
                )
            assertEquals(Role.BLOCKED, blocked.result.newRole)
            assertEquals(item.claimedBy, blocked.result.appliedItem.claimedBy)
            assertEquals(item.claimExpiresAt, blocked.result.appliedItem.claimExpiresAt)

            val resumed =
                assertIs<AdvanceOutcome.Success>(
                    service.advance(
                        blocked.result.appliedItem,
                        "resume",
                        null,
                        null,
                        null,
                        DegradedModePolicy.ACCEPT_CACHED,
                        true,
                    ),
                )
            assertEquals(Role.WORK, resumed.result.newRole)
            assertEquals(item.claimedBy, resumed.result.appliedItem.claimedBy)
            assertEquals(item.claimedAt, resumed.result.appliedItem.claimedAt)
            assertEquals(item.claimExpiresAt, resumed.result.appliedItem.claimExpiresAt)
            assertEquals(item.originalClaimedAt, resumed.result.appliedItem.originalClaimedAt)
        }

    // ──────────────────────────────────────────────
    // S10 — failure: advance by a non-holder is REJECTED under enforceOwnership=true, and the
    // rejection must not silently clear or otherwise touch the claim (no persistence call at all).
    // Exercises the same ownership-rejection path CompleteTreeTool/advance_item rely on. O5
    // ──────────────────────────────────────────────

    @Test
    fun `S10 advance by a non-holder under enforceOwnership is rejected and the claim is untouched`(): Unit =
        runBlocking {
            val item =
                makeItem(
                    role = Role.WORK,
                    claimedBy = "agent-A",
                    claimExpiresAt = Instant.now().plusSeconds(600),
                )
            val foreignActor = ActorClaim(id = "agent-B", kind = ActorKind.SUBAGENT)
            val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

            val outcome =
                serviceWith().advance(
                    item,
                    "complete",
                    null,
                    foreignActor,
                    verification,
                    DegradedModePolicy.ACCEPT_CACHED,
                    enforceOwnership = true,
                )
            val failure = assertIs<AdvanceOutcome.Failure>(outcome)
            assertIs<AdvanceFailure.OwnershipRejected>(failure.failure)

            // Hard negative: no update() call means no persisted mutation of any kind occurred —
            // the claim fields were never touched, not merely "still equal to before".
            coVerify(exactly = 0) { workItemRepo.update(any()) }
            assertEquals("agent-A", item.claimedBy)
        }

    // ──────────────────────────────────────────────
    // S11 — edge: an UNCLAIMED item reaching TERMINAL stays null-through and validate() is unaffected.
    // ──────────────────────────────────────────────

    @Test
    fun `S11 complete on an already-unclaimed item leaves all four fields null`(): Unit =
        runBlocking {
            val item = makeItem(role = Role.WORK)
            assertNull(item.claimedBy)

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertEquals(Role.TERMINAL, success.result.newRole)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }

    // ──────────────────────────────────────────────
    // Probe: claimedAt == claimExpiresAt (zero-length claim window, the equality boundary
    // WorkItem.validate() explicitly allows) is cleared identically to any other live claim.
    // Recorded per test-plan §6 boundary probe — finds no distinct behaviour, as expected: the
    // clear is unconditional on the claim's internal timing.
    // ──────────────────────────────────────────────

    @Test
    fun `boundary probe -- a zero-length claim window is cleared like any other live claim`(): Unit =
        runBlocking {
            val now = Instant.now()
            val item =
                makeItem(role = Role.WORK).copy(
                    claimedBy = "agent-zero-window",
                    claimedAt = now,
                    claimExpiresAt = now,
                    originalClaimedAt = now,
                )

            val outcome =
                serviceWith().advance(item, "complete", null, null, null, DegradedModePolicy.ACCEPT_CACHED, true)
            val success = assertIs<AdvanceOutcome.Success>(outcome)
            assertAllClaimFieldsNull(success.result.appliedItem)
        }
}
