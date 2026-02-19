package io.github.jpicklyk.mcptask.current.infrastructure.database.repository

import io.github.jpicklyk.mcptask.current.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.current.domain.model.WorkItem
import io.github.jpicklyk.mcptask.current.domain.repository.Result
import io.github.jpicklyk.mcptask.current.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.current.infrastructure.database.schema.management.DirectDatabaseSchemaManager
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteRoleTransitionRepository
import io.github.jpicklyk.mcptask.current.infrastructure.repository.SQLiteWorkItemRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteRoleTransitionRepositoryTest {

    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var transitionRepository: SQLiteRoleTransitionRepository
    private lateinit var workItemRepository: SQLiteWorkItemRepository
    private lateinit var testItemId: UUID

    @BeforeEach
    fun setUp() = runBlocking {
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        DirectDatabaseSchemaManager().updateSchema()
        transitionRepository = SQLiteRoleTransitionRepository(databaseManager)
        workItemRepository = SQLiteWorkItemRepository(databaseManager)

        // Create a work item for foreign key references
        val item = WorkItem(title = "Test item")
        workItemRepository.create(item)
        testItemId = item.id
    }

    // --- Create ---

    @Test
    fun `create transition`() = runBlocking {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "queue",
            toRole = "work",
            trigger = "start",
            summary = "Starting work"
        )
        val result = transitionRepository.create(transition)
        assertIs<Result.Success<RoleTransition>>(result)
        assertEquals(transition.id, result.data.id)
        assertEquals("queue", result.data.fromRole)
        assertEquals("work", result.data.toRole)
        assertEquals("start", result.data.trigger)
        assertEquals("Starting work", result.data.summary)
    }

    @Test
    fun `create transition with status labels`() = runBlocking {
        val transition = RoleTransition(
            itemId = testItemId,
            fromRole = "work",
            toRole = "review",
            fromStatusLabel = "in-progress",
            toStatusLabel = "in-review",
            trigger = "complete"
        )
        val result = transitionRepository.create(transition)
        assertIs<Result.Success<RoleTransition>>(result)
        assertEquals("in-progress", result.data.fromStatusLabel)
        assertEquals("in-review", result.data.toStatusLabel)
    }

    // --- findByItemId ---

    @Test
    fun `findByItemId returns transitions ordered by transitionedAt DESC`() = runBlocking {
        val now = Instant.now()
        val t1 = RoleTransition(
            itemId = testItemId,
            fromRole = "queue",
            toRole = "work",
            trigger = "start",
            transitionedAt = now.minus(2, ChronoUnit.HOURS)
        )
        val t2 = RoleTransition(
            itemId = testItemId,
            fromRole = "work",
            toRole = "review",
            trigger = "complete",
            transitionedAt = now.minus(1, ChronoUnit.HOURS)
        )
        val t3 = RoleTransition(
            itemId = testItemId,
            fromRole = "review",
            toRole = "terminal",
            trigger = "complete",
            transitionedAt = now
        )
        transitionRepository.create(t1)
        transitionRepository.create(t2)
        transitionRepository.create(t3)

        val result = transitionRepository.findByItemId(testItemId)
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertEquals(3, result.data.size)
        // Should be newest first
        assertEquals("terminal", result.data[0].toRole)
        assertEquals("review", result.data[1].toRole)
        assertEquals("work", result.data[2].toRole)
    }

    @Test
    fun `findByItemId returns empty for item with no transitions`() = runBlocking {
        val result = transitionRepository.findByItemId(UUID.randomUUID())
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertTrue(result.data.isEmpty())
    }

    @Test
    fun `findByItemId respects limit`() = runBlocking {
        val now = Instant.now()
        for (i in 0..4) {
            transitionRepository.create(
                RoleTransition(
                    itemId = testItemId,
                    fromRole = "queue",
                    toRole = "work",
                    trigger = "start",
                    transitionedAt = now.plus(i.toLong(), ChronoUnit.MINUTES)
                )
            )
        }

        val result = transitionRepository.findByItemId(testItemId, limit = 3)
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertEquals(3, result.data.size)
    }

    // --- findByTimeRange ---

    @Test
    fun `findByTimeRange returns transitions in range`() = runBlocking {
        val now = Instant.now()
        val hourAgo = now.minus(1, ChronoUnit.HOURS)
        val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)
        val threeHoursAgo = now.minus(3, ChronoUnit.HOURS)

        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "queue", toRole = "work",
                trigger = "start", transitionedAt = threeHoursAgo
            )
        )
        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "work", toRole = "review",
                trigger = "complete", transitionedAt = hourAgo
            )
        )

        // Query for range that includes only the second transition
        val result = transitionRepository.findByTimeRange(
            startTime = twoHoursAgo,
            endTime = now
        )
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("review", result.data[0].toRole)
    }

    @Test
    fun `findByTimeRange with role filter`() = runBlocking {
        val now = Instant.now()
        val hourAgo = now.minus(1, ChronoUnit.HOURS)
        val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)

        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "queue", toRole = "work",
                trigger = "start", transitionedAt = hourAgo
            )
        )
        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "work", toRole = "review",
                trigger = "complete", transitionedAt = hourAgo.plus(10, ChronoUnit.MINUTES)
            )
        )

        // Filter for role "review" - should match the second transition (toRole = review)
        val result = transitionRepository.findByTimeRange(
            startTime = twoHoursAgo,
            endTime = now,
            role = "review"
        )
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertEquals(1, result.data.size)
        assertEquals("review", result.data[0].toRole)
    }

    @Test
    fun `findByTimeRange with role filter matches fromRole too`() = runBlocking {
        val now = Instant.now()
        val hourAgo = now.minus(1, ChronoUnit.HOURS)

        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "work", toRole = "terminal",
                trigger = "complete", transitionedAt = hourAgo
            )
        )

        // Filter for role "work" - should match because fromRole = "work"
        val result = transitionRepository.findByTimeRange(
            startTime = hourAgo.minus(1, ChronoUnit.MINUTES),
            endTime = now,
            role = "work"
        )
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertEquals(1, result.data.size)
    }

    @Test
    fun `findByTimeRange returns empty for range with no transitions`() = runBlocking {
        val now = Instant.now()

        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "queue", toRole = "work",
                trigger = "start", transitionedAt = now
            )
        )

        // Query a range before the transition
        val result = transitionRepository.findByTimeRange(
            startTime = now.minus(3, ChronoUnit.HOURS),
            endTime = now.minus(2, ChronoUnit.HOURS)
        )
        assertIs<Result.Success<List<RoleTransition>>>(result)
        assertTrue(result.data.isEmpty())
    }

    // --- deleteByItemId ---

    @Test
    fun `deleteByItemId removes all transitions for item`() = runBlocking {
        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "queue", toRole = "work", trigger = "start"
            )
        )
        transitionRepository.create(
            RoleTransition(
                itemId = testItemId, fromRole = "work", toRole = "review", trigger = "complete"
            )
        )

        val result = transitionRepository.deleteByItemId(testItemId)
        assertIs<Result.Success<Int>>(result)
        assertEquals(2, result.data)

        val findResult = transitionRepository.findByItemId(testItemId)
        assertIs<Result.Success<List<RoleTransition>>>(findResult)
        assertTrue(findResult.data.isEmpty())
    }

    @Test
    fun `deleteByItemId returns 0 for item with no transitions`() = runBlocking {
        val result = transitionRepository.deleteByItemId(UUID.randomUUID())
        assertIs<Result.Success<Int>>(result)
        assertEquals(0, result.data)
    }
}
