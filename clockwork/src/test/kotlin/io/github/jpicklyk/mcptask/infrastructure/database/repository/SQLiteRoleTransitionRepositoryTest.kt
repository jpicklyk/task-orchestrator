package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

class SQLiteRoleTransitionRepositoryTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var repository: SQLiteRoleTransitionRepository

    @BeforeEach
    fun setUp() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repository
        repository = SQLiteRoleTransitionRepository(databaseManager)
    }

    @Test
    fun `create stores and retrieves transition`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()
        val transition = RoleTransition(
            entityId = entityId,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z"),
            trigger = "start",
            summary = "Started work on task"
        )

        // Act
        val createResult = repository.create(transition)

        // Assert
        assertTrue(createResult is Result.Success, "Create should succeed")
        val createdTransition = (createResult as Result.Success).data
        assertEquals(transition.id, createdTransition.id)
        assertEquals(entityId, createdTransition.entityId)
        assertEquals("task", createdTransition.entityType)
        assertEquals("queue", createdTransition.fromRole)
        assertEquals("work", createdTransition.toRole)
        assertEquals("pending", createdTransition.fromStatus)
        assertEquals("in-progress", createdTransition.toStatus)
        assertEquals(Instant.parse("2024-01-01T10:00:00Z"), createdTransition.transitionedAt)
        assertEquals("start", createdTransition.trigger)
        assertEquals("Started work on task", createdTransition.summary)
    }

    @Test
    fun `findByEntityId returns transitions for specific entity`() = runBlocking {
        // Arrange
        val entityId1 = UUID.randomUUID()
        val entityId2 = UUID.randomUUID()

        val transition1 = RoleTransition(
            entityId = entityId1,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val transition2 = RoleTransition(
            entityId = entityId1,
            entityType = "task",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "testing",
            transitionedAt = Instant.parse("2024-01-01T11:00:00Z")
        )

        val transition3 = RoleTransition(
            entityId = entityId2,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T12:00:00Z")
        )

        repository.create(transition1)
        repository.create(transition2)
        repository.create(transition3)

        // Act
        val result = repository.findByEntityId(entityId1)

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertEquals(2, transitions.size)
        assertEquals(transition1.id, transitions[0].id)
        assertEquals(transition2.id, transitions[1].id)
    }

    @Test
    fun `findByEntityId filters by entityType when provided`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()

        val taskTransition = RoleTransition(
            entityId = entityId,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val featureTransition = RoleTransition(
            entityId = entityId,
            entityType = "feature",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planning",
            toStatus = "development",
            transitionedAt = Instant.parse("2024-01-01T11:00:00Z")
        )

        repository.create(taskTransition)
        repository.create(featureTransition)

        // Act
        val result = repository.findByEntityId(entityId, entityType = "task")

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertEquals(1, transitions.size)
        assertEquals("task", transitions[0].entityType)
    }

    @Test
    fun `findByTimeRange returns transitions in range`() = runBlocking {
        // Arrange
        val entityId1 = UUID.randomUUID()
        val entityId2 = UUID.randomUUID()

        val transition1 = RoleTransition(
            entityId = entityId1,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val transition2 = RoleTransition(
            entityId = entityId2,
            entityType = "task",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "testing",
            transitionedAt = Instant.parse("2024-01-01T12:00:00Z")
        )

        val transition3 = RoleTransition(
            entityId = entityId1,
            entityType = "feature",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planning",
            toStatus = "development",
            transitionedAt = Instant.parse("2024-01-01T14:00:00Z")
        )

        repository.create(transition1)
        repository.create(transition2)
        repository.create(transition3)

        // Act
        val result = repository.findByTimeRange(
            startTime = Instant.parse("2024-01-01T09:00:00Z"),
            endTime = Instant.parse("2024-01-01T13:00:00Z")
        )

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertEquals(2, transitions.size)
        assertEquals(transition1.id, transitions[0].id)
        assertEquals(transition2.id, transitions[1].id)
    }

    @Test
    fun `findByTimeRange filters by entityType and role`() = runBlocking {
        // Arrange
        val entityId1 = UUID.randomUUID()
        val entityId2 = UUID.randomUUID()

        val transition1 = RoleTransition(
            entityId = entityId1,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val transition2 = RoleTransition(
            entityId = entityId2,
            entityType = "feature",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planning",
            toStatus = "development",
            transitionedAt = Instant.parse("2024-01-01T11:00:00Z")
        )

        val transition3 = RoleTransition(
            entityId = entityId1,
            entityType = "task",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "testing",
            transitionedAt = Instant.parse("2024-01-01T12:00:00Z")
        )

        repository.create(transition1)
        repository.create(transition2)
        repository.create(transition3)

        // Act - filter by entityType="task" and role="work"
        val result = repository.findByTimeRange(
            startTime = Instant.parse("2024-01-01T09:00:00Z"),
            endTime = Instant.parse("2024-01-01T13:00:00Z"),
            entityType = "task",
            role = "work"
        )

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertEquals(1, transitions.size)
        assertEquals(transition1.id, transitions[0].id)
        assertEquals("task", transitions[0].entityType)
        assertEquals("work", transitions[0].toRole)
    }

    @Test
    fun `deleteByEntityId removes all transitions for entity`() = runBlocking {
        // Arrange
        val entityId = UUID.randomUUID()

        val transition1 = RoleTransition(
            entityId = entityId,
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val transition2 = RoleTransition(
            entityId = entityId,
            entityType = "task",
            fromRole = "work",
            toRole = "terminal",
            fromStatus = "in-progress",
            toStatus = "completed",
            transitionedAt = Instant.parse("2024-01-01T11:00:00Z")
        )

        repository.create(transition1)
        repository.create(transition2)

        // Act
        val deleteResult = repository.deleteByEntityId(entityId)

        // Assert
        assertTrue(deleteResult is Result.Success)
        assertEquals(2, (deleteResult as Result.Success).data)

        // Verify deleted
        val findResult = repository.findByEntityId(entityId)
        assertTrue(findResult is Result.Success)
        assertTrue((findResult as Result.Success).data.isEmpty())
    }

    @Test
    fun `findByEntityId returns empty list for unknown entity`() = runBlocking {
        // Arrange
        val unknownEntityId = UUID.randomUUID()

        // Act
        val result = repository.findByEntityId(unknownEntityId)

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertTrue(transitions.isEmpty())
    }

    @Test
    fun `create transition with null trigger and summary`() = runBlocking {
        // Arrange
        val transition = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "project",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "planning",
            toStatus = "active",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z"),
            trigger = null,
            summary = null
        )

        // Act
        val createResult = repository.create(transition)

        // Assert
        assertTrue(createResult is Result.Success)
        val created = (createResult as Result.Success).data
        assertNull(created.trigger)
        assertNull(created.summary)
    }

    @Test
    fun `findByTimeRange orders by transitionedAt ascending`() = runBlocking {
        // Arrange
        val transition1 = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T12:00:00Z")
        )

        val transition2 = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "task",
            fromRole = "queue",
            toRole = "work",
            fromStatus = "pending",
            toStatus = "in-progress",
            transitionedAt = Instant.parse("2024-01-01T10:00:00Z")
        )

        val transition3 = RoleTransition(
            entityId = UUID.randomUUID(),
            entityType = "task",
            fromRole = "work",
            toRole = "review",
            fromStatus = "in-progress",
            toStatus = "testing",
            transitionedAt = Instant.parse("2024-01-01T11:00:00Z")
        )

        repository.create(transition1)
        repository.create(transition2)
        repository.create(transition3)

        // Act
        val result = repository.findByTimeRange(
            startTime = Instant.parse("2024-01-01T09:00:00Z"),
            endTime = Instant.parse("2024-01-01T13:00:00Z")
        )

        // Assert
        assertTrue(result is Result.Success)
        val transitions = (result as Result.Success).data
        assertEquals(3, transitions.size)
        assertEquals(transition2.id, transitions[0].id) // 10:00
        assertEquals(transition3.id, transitions[1].id) // 11:00
        assertEquals(transition1.id, transitions[2].id) // 12:00
    }
}
