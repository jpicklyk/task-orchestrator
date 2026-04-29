package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.RoleTransitionRepository
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Note: UserTrigger is in the domain.model package imported via the wildcard above.

class RoleTransitionHandlerTest {
    private val handler = RoleTransitionHandler()

    // Helper to create a WorkItem with sensible defaults
    private fun testItem(
        role: Role = Role.QUEUE,
        previousRole: Role? = null,
        parentId: UUID? = null,
        id: UUID = UUID.randomUUID()
    ) = WorkItem(
        id = id,
        parentId = parentId,
        title = "Test Item",
        role = role,
        previousRole = previousRole,
        depth = if (parentId != null) 1 else 0
    )

    // -----------------------------------------------------------------------
    // Phase 1: Resolution (pure logic — no I/O)
    // -----------------------------------------------------------------------

    @Nested
    inner class ResolveTransition {
        // --- start trigger ---

        @Test
        fun `start from QUEUE resolves to WORK`() {
            val result = handler.resolveTransition(Role.QUEUE, "start")
            assertTrue(result.success)
            assertEquals(Role.WORK, result.targetRole)
            assertNull(result.statusLabel)
        }

        @Test
        fun `start from WORK resolves to REVIEW`() {
            val result = handler.resolveTransition(Role.WORK, "start")
            assertTrue(result.success)
            assertEquals(Role.REVIEW, result.targetRole)
        }

        @Test
        fun `start from REVIEW resolves to TERMINAL`() {
            val result = handler.resolveTransition(Role.REVIEW, "start")
            assertTrue(result.success)
            assertEquals(Role.TERMINAL, result.targetRole)
        }

        @Test
        fun `start from TERMINAL returns error about already terminal`() {
            val result = handler.resolveTransition(Role.TERMINAL, "start")
            assertFalse(result.success)
            assertNull(result.targetRole)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("terminal", ignoreCase = true))
        }

        @Test
        fun `start from BLOCKED returns error about item blocked`() {
            val result = handler.resolveTransition(Role.BLOCKED, "start")
            assertFalse(result.success)
            assertNull(result.targetRole)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("blocked", ignoreCase = true))
        }

        // --- complete trigger ---

        @Test
        fun `complete from QUEUE resolves to TERMINAL`() {
            val result = handler.resolveTransition(Role.QUEUE, "complete")
            assertTrue(result.success)
            assertEquals(Role.TERMINAL, result.targetRole)
        }

        @Test
        fun `complete from WORK resolves to TERMINAL`() {
            val result = handler.resolveTransition(Role.WORK, "complete")
            assertTrue(result.success)
            assertEquals(Role.TERMINAL, result.targetRole)
        }

        @Test
        fun `complete from REVIEW resolves to TERMINAL`() {
            val result = handler.resolveTransition(Role.REVIEW, "complete")
            assertTrue(result.success)
            assertEquals(Role.TERMINAL, result.targetRole)
        }

        @Test
        fun `complete from TERMINAL returns error`() {
            val result = handler.resolveTransition(Role.TERMINAL, "complete")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("terminal", ignoreCase = true))
        }

        @Test
        fun `complete from BLOCKED returns error`() {
            val result = handler.resolveTransition(Role.BLOCKED, "complete")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("blocked", ignoreCase = true))
        }

        // --- block trigger ---

        @Test
        fun `block from QUEUE resolves to BLOCKED`() {
            val result = handler.resolveTransition(Role.QUEUE, "block")
            assertTrue(result.success)
            assertEquals(Role.BLOCKED, result.targetRole)
        }

        @Test
        fun `block from WORK resolves to BLOCKED`() {
            val result = handler.resolveTransition(Role.WORK, "block")
            assertTrue(result.success)
            assertEquals(Role.BLOCKED, result.targetRole)
        }

        @Test
        fun `block from TERMINAL returns error`() {
            val result = handler.resolveTransition(Role.TERMINAL, "block")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("terminal", ignoreCase = true))
        }

        @Test
        fun `block from BLOCKED returns error about already blocked`() {
            val result = handler.resolveTransition(Role.BLOCKED, "block")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("already blocked", ignoreCase = true))
        }

        // --- cancel trigger ---

        @Test
        fun `cancel from QUEUE resolves to TERMINAL with statusLabel cancelled`() {
            val result = handler.resolveTransition(Role.QUEUE, "cancel")
            assertTrue(result.success)
            assertEquals(Role.TERMINAL, result.targetRole)
            assertEquals("cancelled", result.statusLabel)
        }

        @Test
        fun `cancel from TERMINAL returns error`() {
            val result = handler.resolveTransition(Role.TERMINAL, "cancel")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("terminal", ignoreCase = true))
        }

        // --- resume trigger (simple overload) ---

        @Test
        fun `resume without WorkItem context returns error requiring full context`() {
            val result = handler.resolveTransition(Role.BLOCKED, "resume")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("previousRole", ignoreCase = true))
        }

        // --- resume trigger (full-item overload) ---

        @Test
        fun `resume from BLOCKED with previousRole WORK restores to WORK`() {
            val item = testItem(role = Role.BLOCKED, previousRole = Role.WORK)
            val result = handler.resolveTransition(item, "resume")
            assertTrue(result.success)
            assertEquals(Role.WORK, result.targetRole)
        }

        @Test
        fun `resume from BLOCKED with previousRole QUEUE restores to QUEUE`() {
            val item = testItem(role = Role.BLOCKED, previousRole = Role.QUEUE)
            val result = handler.resolveTransition(item, "resume")
            assertTrue(result.success)
            assertEquals(Role.QUEUE, result.targetRole)
        }

        @Test
        fun `resume from BLOCKED without previousRole returns error`() {
            val item = testItem(role = Role.BLOCKED, previousRole = null)
            val result = handler.resolveTransition(item, "resume")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("previousRole", ignoreCase = true))
        }

        @Test
        fun `resume from non-BLOCKED role returns error`() {
            val item = testItem(role = Role.QUEUE)
            val result = handler.resolveTransition(item, "resume")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("not blocked", ignoreCase = true))
        }

        // --- unknown trigger ---

        @Test
        fun `unknown trigger returns error`() {
            val result = handler.resolveTransition(Role.QUEUE, "bogus")
            assertFalse(result.success)
            assertNotNull(result.error)
            assertTrue(result.error!!.contains("Unknown trigger", ignoreCase = true))
        }
    }

    // -----------------------------------------------------------------------
    // Phase 2: Validation (uses mocked repos)
    // -----------------------------------------------------------------------

    @Nested
    inner class ValidateTransition {
        private val depRepo: DependencyRepository = mockk()
        private val workItemRepo: WorkItemRepository = mockk()

        @Test
        fun `forward transition with no deps validates successfully`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)
                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns emptyList()

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid)
                assertNull(result.error)
                assertTrue(result.blockers.isEmpty())
            }

        @Test
        fun `forward transition with satisfied dep validates successfully`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.TERMINAL)
                val dep =
                    Dependency(
                        fromItemId = blockerId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid)
                assertTrue(result.blockers.isEmpty())
            }

        @Test
        fun `forward transition with unsatisfied dep returns invalid with blockers`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.QUEUE)
                val dep =
                    Dependency(
                        fromItemId = blockerId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid)
                assertEquals(1, result.blockers.size)
                assertEquals(blockerId, result.blockers[0].fromItemId)
                assertEquals(Role.QUEUE, result.blockers[0].currentRole)
                assertEquals("terminal", result.blockers[0].requiredRole)
            }

        @Test
        fun `transition to BLOCKED always validates regardless of deps`() =
            runBlocking {
                val item = testItem(role = Role.WORK)
                // No repo calls should be needed — BLOCKED skips dep check
                val result = handler.validateTransition(item, Role.BLOCKED, depRepo, workItemRepo)
                assertTrue(result.valid)
            }

        @Test
        fun `TERMINAL item returns invalid`() =
            runBlocking {
                val item = testItem(role = Role.TERMINAL)
                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid)
                assertNotNull(result.error)
                assertTrue(result.error!!.contains("terminal", ignoreCase = true))
            }

        @Test
        fun `RELATES_TO dep is ignored in validation`() =
            runBlocking {
                val relatedId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val dep =
                    Dependency(
                        fromItemId = relatedId,
                        toItemId = item.id,
                        type = DependencyType.RELATES_TO
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                // No workItemRepo.getById call should happen for RELATES_TO

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid)
                assertTrue(result.blockers.isEmpty())
            }

        @Test
        fun `missing blocker item treated as unsatisfied`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val dep =
                    Dependency(
                        fromItemId = blockerId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.getById(blockerId) } returns
                    Result.Error(
                        RepositoryError.NotFound(blockerId, "Item not found")
                    )

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid)
                assertEquals(1, result.blockers.size)
                assertEquals(blockerId, result.blockers[0].fromItemId)
                assertEquals(Role.QUEUE, result.blockers[0].currentRole) // assumed worst
            }

        // ----- IS_BLOCKED_BY enforcement (TDD — should fail until fix applied) -----

        @Test
        fun `IS_BLOCKED_BY dep blocks forward transition when blocker has not reached threshold`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.QUEUE)

                // item IS_BLOCKED_BY blockerItem — item is fromItemId, blocker is toItemId
                val dep =
                    Dependency(
                        fromItemId = item.id,
                        toItemId = blockerId,
                        type = DependencyType.IS_BLOCKED_BY
                    )

                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns listOf(dep)
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid, "IS_BLOCKED_BY should block forward transition")
                assertEquals(1, result.blockers.size)
                assertEquals(blockerId, result.blockers[0].fromItemId)
                assertEquals(Role.QUEUE, result.blockers[0].currentRole)
                assertEquals("terminal", result.blockers[0].requiredRole)
            }

        @Test
        fun `IS_BLOCKED_BY dep allows forward transition when blocker has reached threshold`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.TERMINAL)

                val dep =
                    Dependency(
                        fromItemId = item.id,
                        toItemId = blockerId,
                        type = DependencyType.IS_BLOCKED_BY
                    )

                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns listOf(dep)
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid, "IS_BLOCKED_BY with satisfied blocker should allow transition")
                assertTrue(result.blockers.isEmpty())
            }

        @Test
        fun `IS_BLOCKED_BY dep with custom unblockAt blocks when blocker below threshold`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.QUEUE)

                val dep =
                    Dependency(
                        fromItemId = item.id,
                        toItemId = blockerId,
                        type = DependencyType.IS_BLOCKED_BY,
                        unblockAt = "work"
                    )

                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns listOf(dep)
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid, "IS_BLOCKED_BY with unblockAt=work should block when blocker is in QUEUE")
                assertEquals(1, result.blockers.size)
            }

        @Test
        fun `IS_BLOCKED_BY dep with custom unblockAt allows when blocker at threshold`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.WORK)

                val dep =
                    Dependency(
                        fromItemId = item.id,
                        toItemId = blockerId,
                        type = DependencyType.IS_BLOCKED_BY,
                        unblockAt = "work"
                    )

                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns listOf(dep)
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid, "IS_BLOCKED_BY with unblockAt=work should allow when blocker is at WORK")
            }

        @Test
        fun `mixed BLOCKS and IS_BLOCKED_BY both block when unsatisfied`() =
            runBlocking {
                val blockerAId = UUID.randomUUID()
                val blockerBId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerA = testItem(id = blockerAId, role = Role.QUEUE)
                val blockerB = testItem(id = blockerBId, role = Role.WORK)

                // blockerA BLOCKS item (incoming on item)
                val blocksDep =
                    Dependency(
                        fromItemId = blockerAId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS
                    )

                // item IS_BLOCKED_BY blockerB (outgoing from item)
                val isBlockedByDep =
                    Dependency(
                        fromItemId = item.id,
                        toItemId = blockerBId,
                        type = DependencyType.IS_BLOCKED_BY
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(blocksDep)
                every { depRepo.findByFromItemId(item.id) } returns listOf(isBlockedByDep)
                coEvery { workItemRepo.getById(blockerAId) } returns Result.Success(blockerA)
                coEvery { workItemRepo.getById(blockerBId) } returns Result.Success(blockerB)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertFalse(result.valid, "Both BLOCKS and IS_BLOCKED_BY should contribute blockers")
                assertEquals(2, result.blockers.size)
            }

        // ----- End IS_BLOCKED_BY enforcement tests -----

        @Test
        fun `unblockAt work with blocker at WORK validates successfully`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.WORK)
                val dep =
                    Dependency(
                        fromItemId = blockerId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS,
                        unblockAt = "work"
                    )

                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result = handler.validateTransition(item, Role.WORK, depRepo, workItemRepo)
                assertTrue(result.valid)
                assertTrue(result.blockers.isEmpty())
            }
    }

    // -----------------------------------------------------------------------
    // Phase 3: Apply (persists to repos via mocks)
    // -----------------------------------------------------------------------

    @Nested
    inner class ApplyTransition {
        private val workItemRepo: WorkItemRepository = mockk()
        private val roleTransitionRepo: RoleTransitionRepository = mockk()

        @Test
        fun `apply QUEUE to WORK updates item role and creates audit transition`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.WORK,
                        trigger = "start",
                        summary = "Beginning work",
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                assertNotNull(result.item)
                assertEquals(Role.WORK, result.item!!.role)
                assertEquals(Role.QUEUE, result.previousRole)
                assertEquals(Role.WORK, result.newRole)
                assertNotNull(result.transition)
                assertEquals("queue", result.transition!!.fromRole)
                assertEquals("work", result.transition!!.toRole)
                assertEquals("start", result.transition!!.trigger)
                assertEquals("Beginning work", result.transition!!.summary)

                coVerify { workItemRepo.update(match { it.role == Role.WORK }) }
                coVerify { roleTransitionRepo.create(any()) }
            }

        @Test
        fun `apply to BLOCKED saves previousRole on item`() =
            runBlocking {
                val item = testItem(role = Role.WORK)
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.BLOCKED,
                        trigger = "block",
                        summary = null,
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.BLOCKED, result.item!!.role)
                assertEquals(Role.WORK, result.item!!.previousRole)
            }

        @Test
        fun `apply resume from BLOCKED clears previousRole`() =
            runBlocking {
                val item = testItem(role = Role.BLOCKED, previousRole = Role.WORK)
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.WORK,
                        trigger = "resume",
                        summary = "Unblocked",
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.WORK, result.item!!.role)
                // previousRole should be cleared when leaving BLOCKED
                assertNull(result.item!!.previousRole)
            }

        @Test
        fun `apply cancel sets statusLabel to cancelled`() =
            runBlocking {
                val item = testItem(role = Role.WORK)
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.TERMINAL,
                        trigger = "cancel",
                        summary = "No longer needed",
                        statusLabel = "cancelled",
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.TERMINAL, result.item!!.role)
                assertEquals("cancelled", result.item!!.statusLabel)
            }

        @Test
        fun `workItemRepo update failure returns error result`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)
                coEvery { workItemRepo.update(any()) } returns
                    Result.Error(
                        RepositoryError.DatabaseError("Connection lost")
                    )

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.WORK,
                        trigger = "start",
                        summary = null,
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertFalse(result.success)
                assertNotNull(result.error)
                assertTrue(result.error!!.contains("Connection lost"))
                assertNull(result.item)
                assertNull(result.transition)
            }

        @Test
        fun `apply transition with actor claim persists actor on RoleTransition`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)
                val actorClaim = ActorClaim(id = "orchestrator-1", kind = ActorKind.ORCHESTRATOR, parent = null)
                val verification = VerificationResult(status = VerificationStatus.UNCHECKED, verifier = "noop")

                val transitionSlot = slot<RoleTransition>()
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.WORK,
                        trigger = "start",
                        summary = null,
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo,
                        actorClaim = actorClaim,
                        verification = verification
                    )

                assertTrue(result.success)
                val captured = transitionSlot.captured
                assertNotNull(captured.actorClaim)
                assertEquals("orchestrator-1", captured.actorClaim!!.id)
                assertEquals(ActorKind.ORCHESTRATOR, captured.actorClaim!!.kind)
                assertNotNull(captured.verification)
                assertEquals(VerificationStatus.UNCHECKED, captured.verification!!.status)
                assertEquals("noop", captured.verification!!.verifier)
            }

        @Test
        fun `apply transition without actor has null actor on RoleTransition`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)

                val transitionSlot = slot<RoleTransition>()
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

                val result =
                    handler.applyTransition(
                        item = item,
                        targetRole = Role.WORK,
                        trigger = "start",
                        summary = null,
                        statusLabel = null,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                val captured = transitionSlot.captured
                assertNull(captured.actorClaim)
                assertNull(captured.verification)
            }
    }

    // -----------------------------------------------------------------------
    // High-level entry points: userTransition and cascadeTransition
    // -----------------------------------------------------------------------

    @Nested
    inner class EntryPoints {
        private val depRepo: DependencyRepository = mockk()
        private val workItemRepo: WorkItemRepository = mockk()
        private val roleTransitionRepo: RoleTransitionRepository = mockk()

        @Test
        fun `userTransition START moves QUEUE to WORK`() =
            runBlocking {
                val item = testItem(role = Role.QUEUE)
                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.userTransition(
                        item = item,
                        trigger = UserTrigger.START,
                        summary = null,
                        statusLabel = null,
                        hasReviewPhase = true,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo,
                        dependencyRepository = depRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.QUEUE, result.previousRole)
                assertEquals(Role.WORK, result.newRole)
                assertEquals("start", result.trigger)
            }

        @Test
        fun `userTransition CANCEL sets terminal with cancelled statusLabel`() =
            runBlocking {
                val item = testItem(role = Role.WORK)
                every { depRepo.findByToItemId(item.id) } returns emptyList()
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }

                val result =
                    handler.userTransition(
                        item = item,
                        trigger = UserTrigger.CANCEL,
                        summary = null,
                        statusLabel = null,
                        hasReviewPhase = true,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo,
                        dependencyRepository = depRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.TERMINAL, result.newRole)
                assertEquals("cancel", result.trigger)
            }

        @Test
        fun `userTransition returns failure when dependency constraint not satisfied`() =
            runBlocking {
                val blockerId = UUID.randomUUID()
                val item = testItem(role = Role.QUEUE)
                val blockerItem = testItem(id = blockerId, role = Role.QUEUE)
                val dep =
                    Dependency(
                        fromItemId = blockerId,
                        toItemId = item.id,
                        type = DependencyType.BLOCKS
                    )
                every { depRepo.findByToItemId(item.id) } returns listOf(dep)
                every { depRepo.findByFromItemId(item.id) } returns emptyList()
                coEvery { workItemRepo.getById(blockerId) } returns Result.Success(blockerItem)

                val result =
                    handler.userTransition(
                        item = item,
                        trigger = UserTrigger.START,
                        summary = null,
                        statusLabel = null,
                        hasReviewPhase = true,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo,
                        dependencyRepository = depRepo
                    )

                assertFalse(result.success)
                assertNotNull(result.error)
            }

        @Test
        fun `cascadeTransition applies directly to targetRole without ownership check`() =
            runBlocking {
                val item = testItem(role = Role.WORK)
                val transitionSlot = slot<RoleTransition>()
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

                val result =
                    handler.cascadeTransition(
                        item = item,
                        targetRole = Role.TERMINAL,
                        reason = "Auto-cascaded from child completion",
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success)
                assertEquals(Role.WORK, result.previousRole)
                assertEquals(Role.TERMINAL, result.newRole)
                assertEquals("cascade", result.trigger)

                // Verify the audit trail records trigger = "cascade" and null actor
                val captured = transitionSlot.captured
                assertEquals("cascade", captured.trigger)
                assertNull(captured.actorClaim)
            }

        @Test
        fun `cascadeTransition does not run dependency validation`() =
            runBlocking {
                // Even if there were blocking dependencies, cascadeTransition bypasses them
                // (the system is triggering the cascade, not a user agent)
                val item = testItem(role = Role.WORK)
                coEvery { workItemRepo.update(any()) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(any()) } answers { Result.Success(firstArg()) }
                // depRepo is NOT set up — any call to it would throw an exception
                // The test passes only if cascadeTransition never calls depRepo

                val result =
                    handler.cascadeTransition(
                        item = item,
                        targetRole = Role.TERMINAL,
                        reason = "cascade reason",
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                assertTrue(result.success, "cascadeTransition should not run dependency validation")
                verify(exactly = 0) { depRepo.findByToItemId(any()) }
                verify(exactly = 0) { depRepo.findByFromItemId(any()) }
            }

        @Test
        fun `cascadeTransition with different claimedBy on parent and child preserves cascade response shape`() =
            runBlocking {
                // Arrange: parent item claimed by agent-alpha, child item claimed by agent-beta.
                // The cascade fires on the parent as a result of the child reaching terminal.
                // Ownership checks are bypassed for cascades — the parent should advance to
                // TERMINAL regardless of which agent holds the parent claim.
                val now = java.time.Instant.now()
                val parentItem =
                    testItem(role = Role.WORK).copy(
                        claimedBy = "agent-alpha",
                        claimExpiresAt = now.plusSeconds(3600)
                    )

                // Verify the mock wiring captures the exact item update that is persisted
                val updatedItemSlot = slot<WorkItem>()
                val transitionSlot = slot<RoleTransition>()
                coEvery { workItemRepo.update(capture(updatedItemSlot)) } answers { Result.Success(firstArg()) }
                coEvery { roleTransitionRepo.create(capture(transitionSlot)) } answers { Result.Success(firstArg()) }

                // Act: cascade the parent to TERMINAL with the configured label (e.g., "done")
                // The child was completed by agent-beta — the caller here is the cascade system,
                // not the child's claim holder. No actor is involved in cascadeTransition at all.
                val cascadeStatusLabel = "done"
                val result =
                    handler.cascadeTransition(
                        item = parentItem,
                        targetRole = Role.TERMINAL,
                        reason = "Last child reached terminal — auto-cascading parent",
                        statusLabel = cascadeStatusLabel,
                        workItemRepository = workItemRepo,
                        roleTransitionRepository = roleTransitionRepo
                    )

                // Assert: transition succeeds
                assertTrue(result.success, "cascade on parent with active claim should succeed")

                // Assert: response shape is correct
                assertEquals("cascade", result.trigger, "trigger must be the internal cascade sentinel")
                assertEquals(Role.WORK, result.previousRole, "previousRole should be the parent's pre-cascade role")
                assertEquals(Role.TERMINAL, result.newRole, "newRole should be TERMINAL")

                // Assert: the persisted item carries the cascade status label and is in TERMINAL
                val persisted = updatedItemSlot.captured
                assertEquals(Role.TERMINAL, persisted.role)
                assertEquals(
                    cascadeStatusLabel,
                    persisted.statusLabel,
                    "statusLabel on cascaded item must match the label passed to cascadeTransition"
                )

                // Assert: the audit trail records trigger="cascade" with null actor
                // (cascade is system-initiated — no user claim should appear in the trail)
                val auditEntry = transitionSlot.captured
                assertEquals("cascade", auditEntry.trigger)
                assertNull(auditEntry.actorClaim, "cascade audit entry must not carry a user actor claim")

                // Assert: claim fields on the parent are NOT part of the response guard — the
                // cascade path does not inspect or strip claim fields, so the persisted item
                // retains whatever claim was set before the transition (ownership is irrelevant
                // to cascade). This verifies no accidental coupling between claim ownership
                // and the cascade emission path.
                assertEquals(
                    "agent-alpha",
                    persisted.claimedBy,
                    "cascade must not strip the parent's existing claim holder"
                )
            }
    }
}
