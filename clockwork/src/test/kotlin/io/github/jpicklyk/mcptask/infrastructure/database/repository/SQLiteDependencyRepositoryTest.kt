package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.*

class SQLiteDependencyRepositoryTest {
    private lateinit var database: Database
    private lateinit var databaseManager: DatabaseManager
    private lateinit var dependencyRepository: SQLiteDependencyRepository
    private lateinit var taskRepository: SQLiteTaskRepository

    // Pre-created task IDs for use in dependency tests
    private lateinit var taskA: Task
    private lateinit var taskB: Task
    private lateinit var taskC: Task
    private lateinit var taskD: Task

    @BeforeEach
    fun setUp() = runBlocking {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        dependencyRepository = SQLiteDependencyRepository(databaseManager)
        taskRepository = SQLiteTaskRepository(databaseManager)

        // Create tasks that dependencies will reference (foreign key constraint)
        taskA = Task(title = "Task A", summary = "First task")
        taskB = Task(title = "Task B", summary = "Second task")
        taskC = Task(title = "Task C", summary = "Third task")
        taskD = Task(title = "Task D", summary = "Fourth task")

        val resultA = taskRepository.create(taskA)
        val resultB = taskRepository.create(taskB)
        val resultC = taskRepository.create(taskC)
        val resultD = taskRepository.create(taskD)

        assertTrue(resultA is Result.Success, "Failed to create task A")
        assertTrue(resultB is Result.Success, "Failed to create task B")
        assertTrue(resultC is Result.Success, "Failed to create task C")
        assertTrue(resultD is Result.Success, "Failed to create task D")
    }

    @Test
    fun `create dependency with unblockAt should persist and read back correctly`() {
        // Arrange
        val dependency = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )

        // Act
        val created = dependencyRepository.create(dependency)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals("work", retrieved!!.unblockAt)
        assertEquals(taskA.id, retrieved.fromTaskId)
        assertEquals(taskB.id, retrieved.toTaskId)
        assertEquals(DependencyType.BLOCKS, retrieved.type)
    }

    @Test
    fun `create dependency with null unblockAt should persist and read back as null`() {
        // Arrange
        val dependency = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = null
        )

        // Act
        val created = dependencyRepository.create(dependency)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertNull(retrieved!!.unblockAt)
    }

    @Test
    fun `create dependency with terminal unblockAt`() {
        // Arrange
        val dependency = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "terminal"
        )

        // Act
        val created = dependencyRepository.create(dependency)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals("terminal", retrieved!!.unblockAt)
    }

    @Test
    fun `create dependency with review unblockAt`() {
        // Arrange
        val dependency = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "review"
        )

        // Act
        val created = dependencyRepository.create(dependency)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals("review", retrieved!!.unblockAt)
    }

    @Test
    fun `createBatch with mixed unblockAt values should persist all correctly`() {
        // Arrange
        val dep1 = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        val dep2 = Dependency(
            fromTaskId = taskC.id,
            toTaskId = taskD.id,
            type = DependencyType.BLOCKS,
            unblockAt = null
        )
        val dep3 = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskC.id,
            type = DependencyType.BLOCKS,
            unblockAt = "terminal"
        )

        // Act
        val created = dependencyRepository.createBatch(listOf(dep1, dep2, dep3))

        // Assert
        assertEquals(3, created.size)

        val retrieved1 = dependencyRepository.findById(dep1.id)
        val retrieved2 = dependencyRepository.findById(dep2.id)
        val retrieved3 = dependencyRepository.findById(dep3.id)

        assertNotNull(retrieved1)
        assertEquals("work", retrieved1!!.unblockAt)

        assertNotNull(retrieved2)
        assertNull(retrieved2!!.unblockAt)

        assertNotNull(retrieved3)
        assertEquals("terminal", retrieved3!!.unblockAt)
    }

    @Test
    fun `findByTaskId should return dependencies with unblockAt populated`() {
        // Arrange
        val dep1 = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        val dep2 = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskC.id,
            type = DependencyType.BLOCKS,
            unblockAt = "review"
        )
        dependencyRepository.create(dep1)
        dependencyRepository.create(dep2)

        // Act
        val dependencies = dependencyRepository.findByTaskId(taskA.id)

        // Assert
        assertEquals(2, dependencies.size)
        val sorted = dependencies.sortedBy { it.toTaskId.toString() }
        // Both should have their unblockAt values
        assertTrue(sorted.any { it.unblockAt == "work" })
        assertTrue(sorted.any { it.unblockAt == "review" })
    }

    @Test
    fun `findByFromTaskId should return dependencies with unblockAt populated`() {
        // Arrange
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "queue"
        )
        dependencyRepository.create(dep)

        // Act
        val dependencies = dependencyRepository.findByFromTaskId(taskA.id)

        // Assert
        assertEquals(1, dependencies.size)
        assertEquals("queue", dependencies[0].unblockAt)
    }

    @Test
    fun `findByToTaskId should return dependencies with unblockAt populated`() {
        // Arrange
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS,
            unblockAt = "work"
        )
        dependencyRepository.create(dep)

        // Act
        val dependencies = dependencyRepository.findByToTaskId(taskB.id)

        // Assert
        assertEquals(1, dependencies.size)
        assertEquals("work", dependencies[0].unblockAt)
    }

    @Test
    fun `createBatch with all null unblockAt should preserve backward compatibility`() {
        // Arrange - all dependencies with null unblockAt (pre-feature behavior)
        val dep1 = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.BLOCKS
        )
        val dep2 = Dependency(
            fromTaskId = taskC.id,
            toTaskId = taskD.id,
            type = DependencyType.BLOCKS
        )

        // Act
        dependencyRepository.createBatch(listOf(dep1, dep2))

        // Assert
        val retrieved1 = dependencyRepository.findById(dep1.id)
        val retrieved2 = dependencyRepository.findById(dep2.id)

        assertNotNull(retrieved1)
        assertNull(retrieved1!!.unblockAt)

        assertNotNull(retrieved2)
        assertNull(retrieved2!!.unblockAt)
    }

    @Test
    fun `IS_BLOCKED_BY dependency with unblockAt should persist correctly`() {
        // Arrange
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.IS_BLOCKED_BY,
            unblockAt = "work"
        )

        // Act
        val created = dependencyRepository.create(dep)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals(DependencyType.IS_BLOCKED_BY, retrieved!!.type)
        assertEquals("work", retrieved.unblockAt)
    }

    @Test
    fun `RELATES_TO dependency should have null unblockAt`() {
        // RELATES_TO cannot have unblockAt set (domain validation prevents it)
        // So just verify null flows through correctly
        val dep = Dependency(
            fromTaskId = taskA.id,
            toTaskId = taskB.id,
            type = DependencyType.RELATES_TO,
            unblockAt = null
        )

        // Act
        val created = dependencyRepository.create(dep)
        val retrieved = dependencyRepository.findById(created.id)

        // Assert
        assertNotNull(retrieved)
        assertEquals(DependencyType.RELATES_TO, retrieved!!.type)
        assertNull(retrieved.unblockAt)
    }
}
