package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.current.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.current.domain.repository.Result
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
import kotlin.test.assertTrue

class NextItemRecommenderTest {
    private lateinit var workItemRepo: WorkItemRepository
    private lateinit var dependencyRepo: DependencyRepository
    private lateinit var recommender: NextItemRecommender

    @BeforeEach
    fun setUp() {
        workItemRepo = mockk()
        dependencyRepo = mockk()
        recommender = NextItemRecommender(workItemRepo, dependencyRepo)
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun workItem(
        id: UUID = UUID.randomUUID(),
        title: String = "Item",
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        complexity: Int = 5,
        parentId: UUID? = null,
    ) = WorkItem(id = id, title = title, role = role, priority = priority, complexity = complexity, parentId = parentId)

    private fun blocksDep(fromItemId: UUID, toItemId: UUID, unblockAt: String? = null) =
        Dependency(fromItemId = fromItemId, toItemId = toItemId, type = DependencyType.BLOCKS, unblockAt = unblockAt)

    private fun isBlockedByDep(fromItemId: UUID, toItemId: UUID, unblockAt: String? = null) =
        Dependency(fromItemId = fromItemId, toItemId = toItemId, type = DependencyType.IS_BLOCKED_BY, unblockAt = unblockAt)

    private fun relatesToDep(fromItemId: UUID, toItemId: UUID) =
        Dependency(fromItemId = fromItemId, toItemId = toItemId, type = DependencyType.RELATES_TO)

    /** Stub findClaimable to return a fixed list with no dependency activity. */
    private fun stubFindClaimable(
        items: List<WorkItem>,
        role: Role = Role.QUEUE,
        parentId: UUID? = null,
        tags: List<String>? = null,
        priority: Priority? = null,
        type: String? = null,
        complexityMax: Int? = null,
        createdAfter: Instant? = null,
        createdBefore: Instant? = null,
        roleChangedAfter: Instant? = null,
        roleChangedBefore: Instant? = null,
        orderBy: NextItemOrder = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
    ) {
        coEvery {
            workItemRepo.findClaimable(
                role = role,
                parentId = parentId,
                tags = tags,
                priority = priority,
                type = type,
                complexityMax = complexityMax,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                roleChangedAfter = roleChangedAfter,
                roleChangedBefore = roleChangedBefore,
                orderBy = orderBy,
                limit = 200,
            )
        } returns Result.Success(items)
    }

    /** Stub both dep lookups to return empty lists for all items. */
    private fun stubNoDependencies() {
        every { dependencyRepo.findByToItemId(any()) } returns emptyList()
        every { dependencyRepo.findByFromItemId(any()) } returns emptyList()
    }

    // -----------------------------------------------------------------------
    // Filter pass-through to repository
    // -----------------------------------------------------------------------

    @Test
    fun `criteria fields are passed 1-to-1 to findClaimable`(): Unit =
        runBlocking {
            val parentId = UUID.randomUUID()
            val after = Instant.parse("2024-01-01T00:00:00Z")
            val before = Instant.parse("2024-06-01T00:00:00Z")
            val roleAfter = Instant.parse("2024-02-01T00:00:00Z")
            val roleBefore = Instant.parse("2024-05-01T00:00:00Z")

            val criteria = NextItemRecommender.Criteria(
                role = Role.WORK,
                parentId = parentId,
                tags = listOf("backend", "api"),
                priority = Priority.HIGH,
                type = "feature-task",
                complexityMax = 3,
                createdAfter = after,
                createdBefore = before,
                roleChangedAfter = roleAfter,
                roleChangedBefore = roleBefore,
                orderBy = NextItemOrder.OLDEST_FIRST,
            )

            val item = workItem(role = Role.WORK, priority = Priority.HIGH)
            coEvery {
                workItemRepo.findClaimable(
                    role = Role.WORK,
                    parentId = parentId,
                    tags = listOf("backend", "api"),
                    priority = Priority.HIGH,
                    type = "feature-task",
                    complexityMax = 3,
                    createdAfter = after,
                    createdBefore = before,
                    roleChangedAfter = roleAfter,
                    roleChangedBefore = roleBefore,
                    orderBy = NextItemOrder.OLDEST_FIRST,
                    limit = 200,
                )
            } returns Result.Success(listOf(item))
            every { dependencyRepo.findByToItemId(item.id) } returns emptyList()
            every { dependencyRepo.findByFromItemId(item.id) } returns emptyList()

            val result = recommender.recommend(criteria, limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(1, result.data.size)

            // Verify the repo was called exactly once with the correct args
            coVerify(exactly = 1) {
                workItemRepo.findClaimable(
                    role = Role.WORK,
                    parentId = parentId,
                    tags = listOf("backend", "api"),
                    priority = Priority.HIGH,
                    type = "feature-task",
                    complexityMax = 3,
                    createdAfter = after,
                    createdBefore = before,
                    roleChangedAfter = roleAfter,
                    roleChangedBefore = roleBefore,
                    orderBy = NextItemOrder.OLDEST_FIRST,
                    limit = 200,
                )
            }
        }

    @Test
    fun `default criteria uses QUEUE role and PRIORITY_THEN_COMPLEXITY order`(): Unit =
        runBlocking {
            val criteria = NextItemRecommender.Criteria()
            val item = workItem()

            stubFindClaimable(listOf(item))
            stubNoDependencies()

            val result = recommender.recommend(criteria, limit = 1)
            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(1, result.data.size)

            coVerify(exactly = 1) {
                workItemRepo.findClaimable(
                    role = Role.QUEUE,
                    parentId = null,
                    tags = null,
                    priority = null,
                    type = null,
                    complexityMax = null,
                    createdAfter = null,
                    createdBefore = null,
                    roleChangedAfter = null,
                    roleChangedBefore = null,
                    orderBy = NextItemOrder.PRIORITY_THEN_COMPLEXITY,
                    limit = 200,
                )
            }
        }

    // -----------------------------------------------------------------------
    // Dependency-blocked items excluded
    // -----------------------------------------------------------------------

    @Test
    fun `item blocked by BLOCKS dep with unsatisfied blocker is excluded`(): Unit =
        runBlocking {
            val blocker = workItem(role = Role.QUEUE)  // not yet terminal
            val blocked = workItem()

            stubFindClaimable(listOf(blocked))
            // blocked has an incoming BLOCKS dep from blocker
            every { dependencyRepo.findByToItemId(blocked.id) } returns
                listOf(blocksDep(fromItemId = blocker.id, toItemId = blocked.id))
            every { dependencyRepo.findByFromItemId(blocked.id) } returns emptyList()
            coEvery { workItemRepo.getById(blocker.id) } returns Result.Success(blocker)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty(), "Blocked item should be excluded")
        }

    @Test
    fun `item blocked by IS_BLOCKED_BY dep with unsatisfied blocker is excluded`(): Unit =
        runBlocking {
            val blocked = workItem()
            val blocker = workItem(role = Role.QUEUE)

            stubFindClaimable(listOf(blocked))
            every { dependencyRepo.findByToItemId(blocked.id) } returns emptyList()
            // blocked IS_BLOCKED_BY blocker
            every { dependencyRepo.findByFromItemId(blocked.id) } returns
                listOf(isBlockedByDep(fromItemId = blocked.id, toItemId = blocker.id))
            coEvery { workItemRepo.getById(blocker.id) } returns Result.Success(blocker)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty(), "IS_BLOCKED_BY item should be excluded")
        }

    @Test
    fun `item with satisfied BLOCKS dep (blocker at TERMINAL) is included`(): Unit =
        runBlocking {
            val blocker = workItem(role = Role.TERMINAL)
            val item = workItem()

            stubFindClaimable(listOf(item))
            every { dependencyRepo.findByToItemId(item.id) } returns
                listOf(blocksDep(fromItemId = blocker.id, toItemId = item.id))
            every { dependencyRepo.findByFromItemId(item.id) } returns emptyList()
            coEvery { workItemRepo.getById(blocker.id) } returns Result.Success(blocker)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(1, result.data.size, "Item with satisfied dep should be included")
        }

    @Test
    fun `RELATES_TO dep does not block item`(): Unit =
        runBlocking {
            val related = workItem(role = Role.QUEUE)
            val item = workItem()

            stubFindClaimable(listOf(item))
            every { dependencyRepo.findByToItemId(item.id) } returns
                listOf(relatesToDep(fromItemId = related.id, toItemId = item.id))
            every { dependencyRepo.findByFromItemId(item.id) } returns emptyList()

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(1, result.data.size, "RELATES_TO dep should not block item")
        }

    @Test
    fun `custom unblockAt work is satisfied when blocker is at WORK`(): Unit =
        runBlocking {
            val blocker = workItem(role = Role.WORK)
            val item = workItem()

            stubFindClaimable(listOf(item))
            every { dependencyRepo.findByToItemId(item.id) } returns
                listOf(blocksDep(fromItemId = blocker.id, toItemId = item.id, unblockAt = "work"))
            every { dependencyRepo.findByFromItemId(item.id) } returns emptyList()
            coEvery { workItemRepo.getById(blocker.id) } returns Result.Success(blocker)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(1, result.data.size, "Dep with unblockAt=work satisfied when blocker is WORK")
        }

    // -----------------------------------------------------------------------
    // Limit applied AFTER blocking filter (over-fetch scenario)
    // -----------------------------------------------------------------------

    @Test
    fun `limit applied after blocking filter — over-fetch returns 5 items, 3 blocked, limit=2 returns top 2 unblocked`(): Unit =
        runBlocking {
            val unblockedA = workItem(title = "Unblocked A", priority = Priority.HIGH)
            val unblockedB = workItem(title = "Unblocked B", priority = Priority.MEDIUM)
            val blockerX = workItem(role = Role.QUEUE, title = "Blocker X")
            val blockerY = workItem(role = Role.QUEUE, title = "Blocker Y")
            val blockerZ = workItem(role = Role.QUEUE, title = "Blocker Z")
            val blockedC = workItem(title = "Blocked C")
            val blockedD = workItem(title = "Blocked D")
            val blockedE = workItem(title = "Blocked E")

            // Repository returns 5 candidates: 2 unblocked + 3 blocked
            stubFindClaimable(listOf(unblockedA, unblockedB, blockedC, blockedD, blockedE))

            // No incoming/outgoing deps for unblocked items
            every { dependencyRepo.findByToItemId(unblockedA.id) } returns emptyList()
            every { dependencyRepo.findByFromItemId(unblockedA.id) } returns emptyList()
            every { dependencyRepo.findByToItemId(unblockedB.id) } returns emptyList()
            every { dependencyRepo.findByFromItemId(unblockedB.id) } returns emptyList()

            // Each blocked item has a BLOCKS dep from an unsatisfied blocker
            every { dependencyRepo.findByToItemId(blockedC.id) } returns
                listOf(blocksDep(fromItemId = blockerX.id, toItemId = blockedC.id))
            every { dependencyRepo.findByFromItemId(blockedC.id) } returns emptyList()
            coEvery { workItemRepo.getById(blockerX.id) } returns Result.Success(blockerX)

            every { dependencyRepo.findByToItemId(blockedD.id) } returns
                listOf(blocksDep(fromItemId = blockerY.id, toItemId = blockedD.id))
            every { dependencyRepo.findByFromItemId(blockedD.id) } returns emptyList()
            coEvery { workItemRepo.getById(blockerY.id) } returns Result.Success(blockerY)

            every { dependencyRepo.findByToItemId(blockedE.id) } returns
                listOf(blocksDep(fromItemId = blockerZ.id, toItemId = blockedE.id))
            every { dependencyRepo.findByFromItemId(blockedE.id) } returns emptyList()
            coEvery { workItemRepo.getById(blockerZ.id) } returns Result.Success(blockerZ)

            // limit=2 → should return exactly the 2 unblocked items, not the blocked ones
            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 2)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertEquals(2, result.data.size, "Should return exactly 2 items after blocking filter")
            val ids = result.data.map { it.id }
            assertTrue(ids.contains(unblockedA.id), "unblockedA should be in result")
            assertTrue(ids.contains(unblockedB.id), "unblockedB should be in result")
        }

    // -----------------------------------------------------------------------
    // Empty result
    // -----------------------------------------------------------------------

    @Test
    fun `empty repository result returns empty success (not error)`(): Unit =
        runBlocking {
            stubFindClaimable(emptyList())

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 5)

            assertIs<Result.Success<List<WorkItem>>>(result)
            assertTrue(result.data.isEmpty())
        }

    // -----------------------------------------------------------------------
    // Repository error propagation
    // -----------------------------------------------------------------------

    @Test
    fun `repository error propagates as Result Error`(): Unit =
        runBlocking {
            val repoError = RepositoryError.DatabaseError("connection failed")
            coEvery {
                workItemRepo.findClaimable(
                    role = any(),
                    parentId = any(),
                    tags = any(),
                    priority = any(),
                    type = any(),
                    complexityMax = any(),
                    createdAfter = any(),
                    createdBefore = any(),
                    roleChangedAfter = any(),
                    roleChangedBefore = any(),
                    orderBy = any(),
                    limit = any(),
                )
            } returns Result.Error(repoError)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 5)

            assertIs<Result.Error>(result)
            assertEquals(repoError, result.error)
        }
}
