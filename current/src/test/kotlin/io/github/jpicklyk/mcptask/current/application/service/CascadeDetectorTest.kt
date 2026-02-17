package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.*
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.domain.repository.WorkItemRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CascadeDetectorTest {

    private lateinit var detector: CascadeDetector
    private lateinit var workItemRepository: WorkItemRepository
    private lateinit var dependencyRepository: DependencyRepository

    @BeforeEach
    fun setUp() {
        detector = CascadeDetector()
        workItemRepository = mockk()
        dependencyRepository = mockk()
    }

    // -----------------------------------------------------------------------
    // Helper factories
    // -----------------------------------------------------------------------

    private fun workItem(
        id: UUID = UUID.randomUUID(),
        parentId: UUID? = null,
        title: String = "Item",
        role: Role = Role.QUEUE,
        depth: Int = if (parentId != null) 1 else 0
    ) = WorkItem(id = id, parentId = parentId, title = title, role = role, depth = depth)

    private fun blocksDep(
        fromItemId: UUID,
        toItemId: UUID,
        unblockAt: String? = null
    ) = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = DependencyType.BLOCKS, unblockAt = unblockAt)

    private fun relatesToDep(
        fromItemId: UUID,
        toItemId: UUID
    ) = Dependency(fromItemId = fromItemId, toItemId = toItemId, type = DependencyType.RELATES_TO)

    // -----------------------------------------------------------------------
    // Cascade Detection Tests
    // -----------------------------------------------------------------------

    @Nested
    inner class DetectCascades {

        @Test
        fun `no cascade when parentId is null (root item)`() = runBlocking {
            val rootItem = workItem(role = Role.TERMINAL)
            val result = detector.detectCascades(rootItem, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `no cascade when not all children are terminal`() = runBlocking {
            val parentId = UUID.randomUUID()
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            // One child is terminal, one is still in WORK
            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Success(
                mapOf(Role.TERMINAL to 1, Role.WORK to 1)
            )

            val result = detector.detectCascades(child, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `cascade fires when all children are terminal`() = runBlocking {
            val parentId = UUID.randomUUID()
            val parent = workItem(id = parentId, role = Role.WORK)
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Success(
                mapOf(Role.TERMINAL to 3)
            )
            coEvery { workItemRepository.getById(parentId) } returns Result.Success(parent)

            val result = detector.detectCascades(child, workItemRepository)
            assertEquals(1, result.size)
            assertEquals(parentId, result[0].itemId)
            assertEquals(Role.WORK, result[0].currentRole)
            assertEquals(Role.TERMINAL, result[0].targetRole)
            assertEquals("cascade", result[0].trigger)
        }

        @Test
        fun `no cascade when parent is already terminal`() = runBlocking {
            val parentId = UUID.randomUUID()
            val parent = workItem(id = parentId, role = Role.TERMINAL)
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Success(
                mapOf(Role.TERMINAL to 2)
            )
            coEvery { workItemRepository.getById(parentId) } returns Result.Success(parent)

            val result = detector.detectCascades(child, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `recursive cascade fires for grandparent`() = runBlocking {
            val grandparentId = UUID.randomUUID()
            val parentId = UUID.randomUUID()
            val grandparent = workItem(id = grandparentId, role = Role.REVIEW)
            val parent = workItem(id = parentId, parentId = grandparentId, role = Role.WORK)
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            // All children of parent are terminal
            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Success(
                mapOf(Role.TERMINAL to 2)
            )
            coEvery { workItemRepository.getById(parentId) } returns Result.Success(parent)

            // All children of grandparent are terminal (the parent will be cascaded)
            coEvery { workItemRepository.countChildrenByRole(grandparentId) } returns Result.Success(
                mapOf(Role.TERMINAL to 1)
            )
            coEvery { workItemRepository.getById(grandparentId) } returns Result.Success(grandparent)

            val result = detector.detectCascades(child, workItemRepository)
            assertEquals(2, result.size)
            // Closest parent first
            assertEquals(parentId, result[0].itemId)
            assertEquals(Role.WORK, result[0].currentRole)
            // Grandparent second
            assertEquals(grandparentId, result[1].itemId)
            assertEquals(Role.REVIEW, result[1].currentRole)
        }

        @Test
        fun `max depth bound stops recursion at depth 3`() = runBlocking {
            // Build a chain: item -> parent -> grandparent -> great-grandparent -> great-great-grandparent
            val ids = (0..4).map { UUID.randomUUID() }

            // ids[0] = child (depth 4)
            // ids[1] = parent (depth 3)
            // ids[2] = grandparent (depth 2)
            // ids[3] = great-grandparent (depth 1)
            // ids[4] = great-great-grandparent (depth 0)

            val child = workItem(id = ids[0], parentId = ids[1], role = Role.TERMINAL, depth = 4)

            // Each parent has all terminal children and is non-terminal itself
            for (i in 1..4) {
                val parentOfThis = if (i < 4) ids[i + 1] else null
                val depth = 4 - i
                val item = workItem(id = ids[i], parentId = parentOfThis, role = Role.WORK, depth = depth)

                coEvery { workItemRepository.countChildrenByRole(ids[i]) } returns Result.Success(
                    mapOf(Role.TERMINAL to 1)
                )
                coEvery { workItemRepository.getById(ids[i]) } returns Result.Success(item)
            }

            val result = detector.detectCascades(child, workItemRepository)

            // MAX_DEPTH = 3, so we get at most 3 cascade events
            assertEquals(3, result.size)
            assertEquals(ids[1], result[0].itemId)
            assertEquals(ids[2], result[1].itemId)
            assertEquals(ids[3], result[2].itemId)
            // ids[4] (great-great-grandparent) is NOT included due to depth bound
        }

        @Test
        fun `no cascade when countChildrenByRole returns error`() = runBlocking {
            val parentId = UUID.randomUUID()
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Error(
                io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError.DatabaseError("DB error")
            )

            val result = detector.detectCascades(child, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `no cascade when children map is empty`() = runBlocking {
            val parentId = UUID.randomUUID()
            val child = workItem(parentId = parentId, role = Role.TERMINAL)

            coEvery { workItemRepository.countChildrenByRole(parentId) } returns Result.Success(emptyMap())

            val result = detector.detectCascades(child, workItemRepository)
            assertTrue(result.isEmpty())
        }
    }

    // -----------------------------------------------------------------------
    // Unblock Detection Tests
    // -----------------------------------------------------------------------

    @Nested
    inner class FindUnblockedItems {

        @Test
        fun `empty dependency list returns no unblocked items`() = runBlocking {
            val item = workItem(role = Role.TERMINAL)

            every { dependencyRepository.findByFromItemId(item.id) } returns emptyList()

            val result = detector.findUnblockedItems(item, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `item becomes unblocked when blocker reaches threshold`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            val blocker = workItem(id = blockerId, role = Role.TERMINAL, title = "Blocker")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            // Outgoing BLOCKS dep from blocker to target
            val dep = blocksDep(fromItemId = blockerId, toItemId = targetId)
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(dep)

            // Only one incoming dep on target (the one from blocker)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep)

            // Blocker is TERMINAL, which satisfies the default "terminal" threshold
            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)
            coEvery { workItemRepository.getById(targetId) } returns Result.Success(target)

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertEquals(1, result.size)
            assertEquals(targetId, result[0].itemId)
            assertEquals("Target", result[0].title)
        }

        @Test
        fun `item stays blocked when one blocker has not reached threshold`() = runBlocking {
            val blocker1Id = UUID.randomUUID()
            val blocker2Id = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            val blocker1 = workItem(id = blocker1Id, role = Role.TERMINAL, title = "Blocker1")
            val blocker2 = workItem(id = blocker2Id, role = Role.WORK, title = "Blocker2")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            // blocker1 has outgoing BLOCKS to target
            val dep1 = blocksDep(fromItemId = blocker1Id, toItemId = targetId)
            every { dependencyRepository.findByFromItemId(blocker1Id) } returns listOf(dep1)

            // Target has TWO incoming blocking deps
            val dep2 = blocksDep(fromItemId = blocker2Id, toItemId = targetId)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep1, dep2)

            // blocker1 is TERMINAL (satisfied), blocker2 is WORK (not terminal yet)
            coEvery { workItemRepository.getById(blocker1Id) } returns Result.Success(blocker1)
            coEvery { workItemRepository.getById(blocker2Id) } returns Result.Success(blocker2)
            coEvery { workItemRepository.getById(targetId) } returns Result.Success(target)

            val result = detector.findUnblockedItems(blocker1, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `RELATES_TO dependencies are ignored in unblock detection`() = runBlocking {
            val itemId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            val item = workItem(id = itemId, role = Role.TERMINAL, title = "Item")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            // Only a RELATES_TO outgoing dep (not BLOCKS)
            val dep = relatesToDep(fromItemId = itemId, toItemId = targetId)
            every { dependencyRepository.findByFromItemId(itemId) } returns listOf(dep)

            val result = detector.findUnblockedItems(item, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `item unblocks with custom unblockAt threshold`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            // Blocker is in WORK role -- and threshold is "work"
            val blocker = workItem(id = blockerId, role = Role.WORK, title = "Blocker")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            val dep = blocksDep(fromItemId = blockerId, toItemId = targetId, unblockAt = "work")
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(dep)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep)

            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)
            coEvery { workItemRepository.getById(targetId) } returns Result.Success(target)

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertEquals(1, result.size)
            assertEquals(targetId, result[0].itemId)
        }

        @Test
        fun `item stays blocked when blocker has not reached custom threshold`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            // Blocker is in QUEUE, threshold is "work" -- not yet reached
            val blocker = workItem(id = blockerId, role = Role.QUEUE, title = "Blocker")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            val dep = blocksDep(fromItemId = blockerId, toItemId = targetId, unblockAt = "work")
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(dep)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep)

            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `BLOCKED role never satisfies unblock threshold`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            // Blocker is BLOCKED -- should never satisfy any threshold
            val blocker = workItem(id = blockerId, role = Role.BLOCKED, title = "Blocker")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            val dep = blocksDep(fromItemId = blockerId, toItemId = targetId, unblockAt = "work")
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(dep)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep)

            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `RELATES_TO incoming deps on target are ignored during unblock check`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val relatedId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            val blocker = workItem(id = blockerId, role = Role.TERMINAL, title = "Blocker")
            val target = workItem(id = targetId, role = Role.QUEUE, title = "Target")

            // Outgoing BLOCKS dep from blocker to target
            val blockDep = blocksDep(fromItemId = blockerId, toItemId = targetId)
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(blockDep)

            // Target has a BLOCKS dep (satisfied) AND a RELATES_TO dep (should be ignored)
            val relatesDep = relatesToDep(fromItemId = relatedId, toItemId = targetId)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(blockDep, relatesDep)

            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)
            coEvery { workItemRepository.getById(targetId) } returns Result.Success(target)

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertEquals(1, result.size)
            assertEquals(targetId, result[0].itemId)
        }

        @Test
        fun `missing blocker item treats target as still blocked`() = runBlocking {
            val blockerId = UUID.randomUUID()
            val targetId = UUID.randomUUID()

            val blocker = workItem(id = blockerId, role = Role.TERMINAL, title = "Blocker")

            val dep = blocksDep(fromItemId = blockerId, toItemId = targetId)
            every { dependencyRepository.findByFromItemId(blockerId) } returns listOf(dep)

            // Target has another dep whose blocker is missing from the repository
            val missingBlockerId = UUID.randomUUID()
            val dep2 = blocksDep(fromItemId = missingBlockerId, toItemId = targetId)
            every { dependencyRepository.findByToItemId(targetId) } returns listOf(dep, dep2)

            coEvery { workItemRepository.getById(blockerId) } returns Result.Success(blocker)
            coEvery { workItemRepository.getById(missingBlockerId) } returns Result.Error(
                io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError.NotFound(
                    missingBlockerId, "WorkItem not found"
                )
            )

            val result = detector.findUnblockedItems(blocker, dependencyRepository, workItemRepository)
            assertTrue(result.isEmpty())
        }
    }
}
