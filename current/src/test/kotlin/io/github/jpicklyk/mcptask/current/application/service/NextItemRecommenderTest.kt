package io.github.jpicklyk.mcptask.current.application.service

import io.github.jpicklyk.mcptask.current.application.port.DependencyStore
import io.github.jpicklyk.mcptask.current.application.port.EligibilityQuery
import io.github.jpicklyk.mcptask.current.application.port.ItemSlice
import io.github.jpicklyk.mcptask.current.application.port.WorkItemRepository
import io.github.jpicklyk.mcptask.current.domain.model.Dependency
import io.github.jpicklyk.mcptask.current.domain.model.DependencyType
import io.github.jpicklyk.mcptask.current.domain.model.NextItemOrder
import io.github.jpicklyk.mcptask.current.domain.model.Priority
import io.github.jpicklyk.mcptask.current.domain.model.Role
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.test.sqlite.SqliteTestDatabase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * NextItemRecommender over the SQL eligibility (Q14). The criteria pass-through scenarios stay MockK-based (they
 * verify the 1:1 mapping onto the store's [EligibilityQuery]); every behavior scenario runs on a real SQLite
 * database, since eligibility (claims, ancestor claims, dependency blocking, order, limit) is now decided in SQL.
 */
class NextItemRecommenderTest {
    @RegisterExtension
    val db = SqliteTestDatabase.perMethod()

    private val repo: WorkItemRepository get() = db.repositoryProvider().workItemRepository()
    private val deps: DependencyStore get() = db.repositoryProvider().dependencyRepository()
    private val recommender: NextItemRecommender get() = NextItemRecommender(repo)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private suspend fun item(
        title: String = "Item",
        role: Role = Role.QUEUE,
        priority: Priority = Priority.MEDIUM,
        complexity: Int? = 5,
    ): WorkItem = repo.create(WorkItem(title = title, role = role, priority = priority, complexity = complexity))

    private suspend fun link(
        from: UUID,
        to: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ) {
        deps.create(Dependency(fromItemId = from, toItemId = to, type = type, unblockAt = unblockAt))
    }

    private fun mockedRecommender(): Pair<WorkItemRepository, NextItemRecommender> {
        val workItemRepo = mockk<WorkItemRepository>()
        coEvery { workItemRepo.findEligible(any(), any(), any()) } returns ItemSlice(emptyList(), 0)
        return workItemRepo to NextItemRecommender(workItemRepo)
    }

    // -----------------------------------------------------------------------
    // Filter pass-through to the store
    // -----------------------------------------------------------------------

    @Test
    fun `criteria fields are passed 1-to-1 to findEligible`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()
            val parentId = UUID.randomUUID()
            val after = Instant.parse("2024-01-01T00:00:00Z")
            val before = Instant.parse("2024-06-01T00:00:00Z")
            val roleAfter = Instant.parse("2024-02-01T00:00:00Z")
            val roleBefore = Instant.parse("2024-05-01T00:00:00Z")
            val modAfter = Instant.parse("2024-03-01T00:00:00Z")
            val modBefore = Instant.parse("2024-04-01T00:00:00Z")
            val criteria =
                NextItemRecommender.Criteria(
                    role = Role.WORK,
                    parentId = parentId,
                    tags = listOf("backend", "api"),
                    priority = Priority.HIGH,
                    type = "feature-task",
                    complexityMax = 3,
                    createdAfter = after,
                    createdBefore = before,
                    modifiedAfter = modAfter,
                    modifiedBefore = modBefore,
                    roleChangedAfter = roleAfter,
                    roleChangedBefore = roleBefore,
                    orderBy = NextItemOrder.OLDEST_FIRST,
                )

            recommender.recommend(criteria, limit = 10)

            coVerify(exactly = 1) {
                workItemRepo.findEligible(
                    EligibilityQuery(
                        role = Role.WORK,
                        parentId = parentId,
                        tags = listOf("backend", "api"),
                        priority = Priority.HIGH,
                        type = "feature-task",
                        complexityMax = 3,
                        createdAfter = after,
                        createdBefore = before,
                        modifiedAfter = modAfter,
                        modifiedBefore = modBefore,
                        roleChangedAfter = roleAfter,
                        roleChangedBefore = roleBefore,
                        orderBy = NextItemOrder.OLDEST_FIRST,
                        requestingAgentId = null,
                        rootIds = null,
                        includeClaimed = false,
                    ),
                    10,
                    0,
                )
            }
        }

    @Test
    fun `default criteria uses QUEUE role and PRIORITY_THEN_COMPLEXITY order`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()

            recommender.recommend(NextItemRecommender.Criteria(), limit = 1)

            coVerify(exactly = 1) {
                workItemRepo.findEligible(EligibilityQuery(role = Role.QUEUE, orderBy = NextItemOrder.PRIORITY_THEN_COMPLEXITY), 1, 0)
            }
        }

    // -----------------------------------------------------------------------
    // Subtree scope (ancestorIds -> rootIds) forwarding
    // -----------------------------------------------------------------------

    @Test
    fun `criteria ancestorIds is forwarded to findEligible as rootIds`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()
            val scopeRoot = UUID.randomUUID()

            recommender.recommend(NextItemRecommender.Criteria(role = Role.WORK, ancestorIds = setOf(scopeRoot)), limit = 1)

            coVerify(exactly = 1) {
                workItemRepo.findEligible(EligibilityQuery(role = Role.WORK, rootIds = setOf(scopeRoot)), 1, 0)
            }
        }

    @Test
    fun `default criteria ancestorIds is null - findEligible called with rootIds null`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()

            recommender.recommend(NextItemRecommender.Criteria(), limit = 1)

            coVerify(exactly = 1) { workItemRepo.findEligible(match { it.rootIds == null }, 1, 0) }
        }

    // -----------------------------------------------------------------------
    // Dependency-blocked items excluded (real SQLite)
    // -----------------------------------------------------------------------

    @Test
    fun `item blocked by BLOCKS dep with unsatisfied blocker is excluded`(): Unit =
        runBlocking {
            val blocker = item("Blocker", role = Role.WORK) // not yet terminal, and not a QUEUE candidate itself
            val blocked = item("Blocked")
            link(blocker.id, blocked.id)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertTrue(result.isEmpty(), "Blocked item should be excluded: $result")
        }

    @Test
    fun `item blocked by IS_BLOCKED_BY dep with unsatisfied blocker is excluded`(): Unit =
        runBlocking {
            val blocked = item("Blocked")
            val blocker = item("Blocker", role = Role.WORK)
            // blocked IS_BLOCKED_BY blocker: stored normalized as blocker BLOCKS blocked
            link(blocked.id, blocker.id, DependencyType.IS_BLOCKED_BY)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertTrue(result.isEmpty(), "IS_BLOCKED_BY item should be excluded: $result")
        }

    @Test
    fun `item with satisfied BLOCKS dep (blocker at TERMINAL) is included`(): Unit =
        runBlocking {
            val blocker = item("Blocker", role = Role.TERMINAL)
            val subject = item("Subject")
            link(blocker.id, subject.id)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertEquals(listOf(subject.id), result.map { it.id }, "Item with satisfied dep should be included")
        }

    @Test
    fun `RELATES_TO dep does not block item`(): Unit =
        runBlocking {
            val related = item("Related", role = Role.WORK)
            val subject = item("Subject")
            link(related.id, subject.id, DependencyType.RELATES_TO)

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertEquals(listOf(subject.id), result.map { it.id }, "RELATES_TO dep should not block item")
        }

    @Test
    fun `custom unblockAt work is satisfied when blocker is at WORK`(): Unit =
        runBlocking {
            val blocker = item("Blocker", role = Role.WORK)
            val subject = item("Subject")
            link(blocker.id, subject.id, unblockAt = "work")

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 10)

            assertEquals(listOf(subject.id), result.map { it.id }, "Dep with unblockAt=work satisfied when blocker is WORK")
        }

    // -----------------------------------------------------------------------
    // Limit applied AFTER the blocking filter
    // -----------------------------------------------------------------------

    @Test
    fun `limit applied after blocking filter - 5 candidates, 3 blocked, limit=2 returns the 2 unblocked`(): Unit =
        runBlocking {
            // The blocked candidates rank FIRST (high priority), so a limit applied before the filter would starve.
            val blockedC = item("Blocked C", priority = Priority.HIGH)
            val blockedD = item("Blocked D", priority = Priority.HIGH)
            val blockedE = item("Blocked E", priority = Priority.HIGH)
            val unblockedA = item("Unblocked A", priority = Priority.MEDIUM)
            val unblockedB = item("Unblocked B", priority = Priority.LOW)
            listOf(blockedC, blockedD, blockedE).forEach { link(item("Blocker for ${it.title}", role = Role.WORK).id, it.id) }

            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 2)

            assertEquals(listOf(unblockedA.id, unblockedB.id), result.map { it.id })
        }

    // -----------------------------------------------------------------------
    // Every candidate blocked (formerly OVER_FETCH_LIMIT exhaustion)
    // -----------------------------------------------------------------------

    @Test
    fun `all candidates blocked returns empty without crash`(): Unit =
        runBlocking {
            val blocker = item("Blocker (not yet terminal)", role = Role.WORK)
            repeat(10) { i -> link(blocker.id, item("Blocked #$i").id) }

            val counted = recommender.recommendCounted(NextItemRecommender.Criteria(), limit = 5)

            assertTrue(counted.items.isEmpty(), "All candidates blocked must yield an empty list: ${counted.items}")
            assertEquals(0, counted.candidateCount, "the eligible count is exact: nothing is eligible")
        }

    // -----------------------------------------------------------------------
    // Empty result
    // -----------------------------------------------------------------------

    @Test
    fun `empty repository result returns empty (not error)`(): Unit =
        runBlocking {
            val result = recommender.recommend(NextItemRecommender.Criteria(), limit = 5)

            assertTrue(result.isEmpty())
        }

    // -----------------------------------------------------------------------
    // Store error propagation
    // -----------------------------------------------------------------------

    @Test
    fun `store error propagates unchanged`(): Unit =
        runBlocking {
            // P5b: stores throw; the store fault propagates out of recommend() unchanged.
            val workItemRepo = mockk<WorkItemRepository>()
            val repoError = IllegalStateException("connection failed")
            coEvery { workItemRepo.findEligible(any(), any(), any()) } throws repoError

            val thrown = assertFails { NextItemRecommender(workItemRepo).recommend(NextItemRecommender.Criteria(), limit = 5) }

            assertEquals(repoError, thrown)
        }

    // -----------------------------------------------------------------------
    // requestingAgentId round-trips through recommend()
    // -----------------------------------------------------------------------

    @Test
    fun `requestingAgentId in Criteria is forwarded to findEligible`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()
            val agentId = "agent-fleet-worker-1"

            recommender.recommend(NextItemRecommender.Criteria(requestingAgentId = agentId), limit = 1)

            coVerify(exactly = 1) { workItemRepo.findEligible(EligibilityQuery(requestingAgentId = agentId), 1, 0) }
        }

    @Test
    fun `null requestingAgentId in Criteria forwards null to findEligible (strict exclusion mode)`(): Unit =
        runBlocking {
            val (workItemRepo, recommender) = mockedRecommender()

            recommender.recommend(NextItemRecommender.Criteria(), limit = 1)

            coVerify(exactly = 1) { workItemRepo.findEligible(match { it.requestingAgentId == null && !it.includeClaimed }, 1, 0) }
        }
}
