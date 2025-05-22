package io.github.jpicklyk.mcptask.infrastructure.database.repository

import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.model.Task
import io.github.jpicklyk.mcptask.domain.model.TaskStatus
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.util.*

/**
 * Simple test to verify the multiple filter bug is fixed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleMultipleFilterTest {
    private lateinit var database: Database
    private lateinit var taskRepository: TaskRepository
    private lateinit var databaseManager: DatabaseManager

    @BeforeEach
    fun setup() {
        // Create a unique in-memory database for each test to ensure isolation
        val dbName = "simple_test_${System.nanoTime()}"
        database = Database.connect("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        databaseManager = DatabaseManager(database)
        databaseManager.updateSchema()

        // Initialize repositories
        taskRepository = SQLiteTaskRepository(databaseManager)
    }

    @Test
    fun `multiple filters should not cause database error`() = runBlocking {
        // Create a simple task
        val task = Task(
            id = UUID.randomUUID(),
            title = "Test Task",
            summary = "A simple test task",
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("test"),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Create the task
        val createResult = taskRepository.create(task)
        assertTrue(createResult.isSuccess())

        // Test multiple filters - this should NOT throw "WHERE clause is specified twice"
        val result = taskRepository.findByFilters(
            projectId = null,
            status = TaskStatus.PENDING,
            priority = Priority.HIGH,
            tags = null,
            textQuery = null,
            limit = 100
        )

        // The key test is that this doesn't throw an exception
        assertTrue(result.isSuccess(), "Multiple filter operation should succeed without database errors")

        // Clean up
        taskRepository.delete(task.id)
    }
}
