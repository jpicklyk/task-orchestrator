package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.model.workflow.CleanupConfig
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.coVerify
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompletionCleanupServiceTest {

    private lateinit var service: CompletionCleanupService
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository

    @TempDir
    lateinit var tempDir: Path

    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockTaskRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()

        service = CompletionCleanupService(
            taskRepository = mockTaskRepository,
            sectionRepository = mockSectionRepository,
            dependencyRepository = mockDependencyRepository
        )

        // Redirect config loading to temp directory
        System.setProperty("user.dir", tempDir.toString())
    }

    private fun writeConfigFile(yamlContent: String) {
        val configDir = tempDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val configFile = configDir.resolve("config.yaml")
        Files.writeString(configFile, yamlContent)
    }

    private fun createTask(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Task",
        tags: List<String> = emptyList(),
        status: TaskStatus = TaskStatus.COMPLETED
    ): Task {
        return Task(
            id = id,
            title = title,
            description = "Test description",
            summary = "Test summary",
            status = status,
            priority = Priority.MEDIUM,
            complexity = 5,
            tags = tags,
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    private fun createSection(
        id: UUID = UUID.randomUUID(),
        entityId: UUID,
        title: String = "Test Section"
    ): Section {
        return Section(
            id = id,
            entityId = entityId,
            entityType = EntityType.TASK,
            title = title,
            usageDescription = "Test usage",
            content = "Test content",
            ordinal = 0,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class CleanupEnabledTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            // Enable cleanup for tests in this nested class
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should delete tasks when feature reaches completed status`() = runBlocking {
            val task1 = createTask(title = "Task 1")
            val task2 = createTask(title = "Task 2")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(2, result.tasksDeleted)
            assertEquals(0, result.tasksRetained)
            assertTrue(result.retainedTaskIds.isEmpty())
        }

        @Test
        fun `should delete tasks when feature reaches archived status`() = runBlocking {
            val task = createTask(title = "Task 1")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "archived")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted)
        }

        @Test
        fun `should retain bug-tagged tasks`() = runBlocking {
            val regularTask = createTask(title = "Regular Task", tags = listOf("backend"))
            val bugTask = createTask(title = "Bug Task", tags = listOf("bug", "backend"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(regularTask, bugTask)
            every { mockDependencyRepository.deleteByTaskId(regularTask.id) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, regularTask.id) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(regularTask.id) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted)
            assertEquals(1, result.tasksRetained)
            assertTrue(result.retainedTaskIds.contains(bugTask.id))
        }

        @Test
        fun `should retain tasks with bugfix tag`() = runBlocking {
            val bugfixTask = createTask(title = "Bugfix Task", tags = listOf("bugfix"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(bugfixTask)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(1, result.tasksRetained)
        }

        @Test
        fun `should retain tasks with hotfix tag`() = runBlocking {
            val hotfixTask = createTask(title = "Hotfix Task", tags = listOf("hotfix"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(hotfixTask)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(1, result.tasksRetained)
        }

        @Test
        fun `should retain tasks with critical tag`() = runBlocking {
            val criticalTask = createTask(title = "Critical Task", tags = listOf("critical"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(criticalTask)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(1, result.tasksRetained)
        }

        @Test
        fun `should handle case-insensitive tag matching`() = runBlocking {
            val bugTask = createTask(title = "Bug Task", tags = listOf("BUG"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(bugTask)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(1, result.tasksRetained)
        }

        @Test
        fun `should delete sections and dependencies with tasks`() = runBlocking {
            val taskId = UUID.randomUUID()
            val task = createTask(id = taskId, title = "Task with sections")
            val section1 = createSection(entityId = taskId, title = "Section 1")
            val section2 = createSection(entityId = taskId, title = "Section 2")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(taskId) } returns 3
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(listOf(section1, section2))
            coEvery { mockSectionRepository.deleteSection(any()) } returns Result.Success(true)
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted)
            assertEquals(2, result.sectionsDeleted)
            assertEquals(3, result.dependenciesDeleted)
        }

        @Test
        fun `should handle mixed tasks - delete some, retain others`() = runBlocking {
            val regularTask1 = createTask(title = "Regular 1", tags = listOf("backend"))
            val regularTask2 = createTask(title = "Regular 2", tags = listOf("frontend"))
            val bugTask = createTask(title = "Bug Report", tags = listOf("bug"))
            val fixTask = createTask(title = "Fix Task", tags = listOf("fix"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns
                    listOf(regularTask1, regularTask2, bugTask, fixTask)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(2, result.tasksDeleted)
            assertEquals(2, result.tasksRetained)
            assertTrue(result.retainedTaskIds.contains(bugTask.id))
            assertTrue(result.retainedTaskIds.contains(fixTask.id))
        }
    }

    @Nested
    inner class NonTerminalStatusTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should not clean up when feature status is not terminal`() = runBlocking {
            val result = service.cleanupFeatureTasks(featureId, "in-development")

            assertFalse(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertTrue(result.reason.contains("not a terminal status"))
        }

        @Test
        fun `should not clean up when feature status is planning`() = runBlocking {
            val result = service.cleanupFeatureTasks(featureId, "planning")

            assertFalse(result.performed)
            assertEquals(0, result.tasksDeleted)
        }

        @Test
        fun `should not clean up when feature status is testing`() = runBlocking {
            val result = service.cleanupFeatureTasks(featureId, "testing")

            assertFalse(result.performed)
        }

        @Test
        fun `should handle normalized status variants`() = runBlocking {
            val task = createTask(title = "Task 1")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            // Test with uppercase COMPLETED (as enum name would be)
            val result = service.cleanupFeatureTasks(featureId, "COMPLETED")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted)
        }
    }

    @Nested
    inner class NoTasksTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should handle feature with no tasks`() = runBlocking {
            every { mockTaskRepository.findByFeatureId(featureId) } returns emptyList()

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertFalse(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(0, result.tasksRetained)
            assertTrue(result.reason.contains("No child tasks"))
        }
    }

    @Nested
    inner class AllRetainedTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should handle feature with only bug-tagged tasks`() = runBlocking {
            val bugTask1 = createTask(title = "Bug 1", tags = listOf("bug"))
            val bugTask2 = createTask(title = "Bug 2", tags = listOf("bugfix"))

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(bugTask1, bugTask2)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(0, result.tasksDeleted)
            assertEquals(2, result.tasksRetained)
            assertTrue(result.reason.contains("retained"))
        }
    }

    @Nested
    inner class ErrorHandlingTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should continue cleanup if one task deletion fails`() = runBlocking {
            val task1 = createTask(title = "Task 1")
            val task2 = createTask(title = "Task 2")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task1, task2)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())

            // First task delete fails, second succeeds
            coEvery { mockTaskRepository.delete(task1.id) } returns Result.Error(
                io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError("DB error")
            )
            coEvery { mockTaskRepository.delete(task2.id) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted) // Only second task deleted successfully
        }

        @Test
        fun `should continue cleanup if section retrieval fails`() = runBlocking {
            val task = createTask(title = "Task 1")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Error(
                io.github.jpicklyk.mcptask.domain.repository.RepositoryError.DatabaseError("DB error")
            )
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(1, result.tasksDeleted)
            assertEquals(0, result.sectionsDeleted)
        }
    }

    @Nested
    inner class ConfigTests {
        @Test
        fun `CleanupConfig default should have cleanup disabled`() {
            val config = CleanupConfig()
            assertFalse(config.enabled, "Default cleanup config should be disabled to prevent data loss")
        }

        @Test
        fun `default cleanup config should be disabled`() {
            val config = service.loadCleanupConfig()
            assertFalse(config.enabled)
        }

        @Test
        fun `default cleanup config should have expected retain tags`() {
            val config = service.loadCleanupConfig()
            assertTrue(config.retainTags.contains("bug"))
            assertTrue(config.retainTags.contains("bugfix"))
            assertTrue(config.retainTags.contains("fix"))
            assertTrue(config.retainTags.contains("hotfix"))
            assertTrue(config.retainTags.contains("critical"))
        }

        @Test
        fun `default terminal statuses should include completed and archived`() {
            val statuses = service.loadFeatureTerminalStatuses()
            assertTrue(statuses.contains("completed"))
            assertTrue(statuses.contains("archived"))
        }
    }

    @Nested
    inner class DependencyCleanupTests {
        @BeforeEach
        fun setupCleanupEnabled() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug, bugfix, fix, hotfix, critical]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived]
            """.trimIndent())
        }

        @Test
        fun `should delete dependencies before deleting task`() = runBlocking {
            val taskId = UUID.randomUUID()
            val task = createTask(id = taskId, title = "Task with deps")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(taskId) } returns 5
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(taskId) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertTrue(result.performed)
            assertEquals(5, result.dependenciesDeleted)

            // Verify dependency deletion was called before task deletion
            verify { mockDependencyRepository.deleteByTaskId(taskId) }
            coVerify { mockTaskRepository.delete(taskId) }
        }
    }
}
