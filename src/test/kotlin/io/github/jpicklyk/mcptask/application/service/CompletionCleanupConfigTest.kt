package io.github.jpicklyk.mcptask.application.service

import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.DependencyRepository
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.domain.repository.SectionRepository
import io.github.jpicklyk.mcptask.domain.repository.TaskRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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

class CompletionCleanupConfigTest {

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
            complexity = 3,
            tags = tags,
            featureId = featureId,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class LoadCleanupConfigTests {
        @Test
        fun `should load enabled=true from custom config`() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug]
            """.trimIndent())

            val config = service.loadCleanupConfig()

            assertTrue(config.enabled, "Config should be enabled")
        }

        @Test
        fun `should load enabled=false from custom config`() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: false
            """.trimIndent())

            val config = service.loadCleanupConfig()

            assertFalse(config.enabled, "Config should be disabled")
        }

        @Test
        fun `should load custom retain_tags from config`() {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [security, regression]
            """.trimIndent())

            val config = service.loadCleanupConfig()

            assertEquals(2, config.retainTags.size, "Should have 2 retain tags")
            assertTrue(config.retainTags.contains("security"), "Should contain security tag")
            assertTrue(config.retainTags.contains("regression"), "Should contain regression tag")
        }

        @Test
        fun `should fallback to bundled defaults when completion_cleanup section missing`() {
            writeConfigFile("""
                version: "2.0.0"
            """.trimIndent())

            val config = service.loadCleanupConfig()

            assertFalse(config.enabled, "Should default to disabled to prevent data loss")
            assertTrue(config.retainTags.contains("bug"), "Should have default retain tags")
            assertTrue(config.retainTags.contains("bugfix"), "Should have default retain tags")
            assertTrue(config.retainTags.contains("fix"), "Should have default retain tags")
            assertTrue(config.retainTags.contains("hotfix"), "Should have default retain tags")
            assertTrue(config.retainTags.contains("critical"), "Should have default retain tags")
        }
    }

    @Nested
    inner class LoadFeatureTerminalStatusesTests {
        @Test
        fun `should load custom terminal_statuses from config`() {
            writeConfigFile("""
                version: "2.0.0"
                status_progression:
                  features:
                    terminal_statuses: [completed, archived, deprecated]
            """.trimIndent())

            val statuses = service.loadFeatureTerminalStatuses()

            assertEquals(3, statuses.size, "Should have 3 terminal statuses")
            assertTrue(statuses.contains("completed"), "Should contain completed")
            assertTrue(statuses.contains("archived"), "Should contain archived")
            assertTrue(statuses.contains("deprecated"), "Should contain deprecated")
        }

        @Test
        fun `should fallback to defaults when section missing`() {
            writeConfigFile("""
                version: "2.0.0"
            """.trimIndent())

            val statuses = service.loadFeatureTerminalStatuses()

            assertEquals(2, statuses.size, "Should have 2 default terminal statuses")
            assertTrue(statuses.contains("completed"), "Should contain completed")
            assertTrue(statuses.contains("archived"), "Should contain archived")
        }
    }

    @Nested
    inner class CleanupBehaviorWithConfigTests {
        @Test
        fun `should skip cleanup when enabled=false in config`() = runBlocking {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: false
            """.trimIndent())

            val result = service.cleanupFeatureTasks(featureId, "completed")

            assertFalse(result.performed, "Cleanup should not be performed")
            assertTrue(result.reason.contains("disabled"), "Reason should mention disabled")
            assertEquals(0, result.tasksDeleted, "No tasks should be deleted")
        }

        @Test
        fun `should trigger cleanup for custom terminal status`() = runBlocking {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug]
                status_progression:
                  features:
                    terminal_statuses: [completed, archived, deprecated]
            """.trimIndent())

            val task = createTask(title = "Regular task")

            every { mockTaskRepository.findByFeatureId(featureId) } returns listOf(task)
            every { mockDependencyRepository.deleteByTaskId(any()) } returns 0
            coEvery { mockSectionRepository.getSectionsForEntity(EntityType.TASK, any()) } returns Result.Success(emptyList())
            coEvery { mockTaskRepository.delete(any()) } returns Result.Success(true)

            val result = service.cleanupFeatureTasks(featureId, "deprecated")

            assertTrue(result.performed, "Cleanup should be performed for custom terminal status")
            assertEquals(1, result.tasksDeleted, "One task should be deleted")
        }

        @Test
        fun `should NOT trigger cleanup for status removed from terminal list`() = runBlocking {
            writeConfigFile("""
                version: "2.0.0"
                completion_cleanup:
                  enabled: true
                  retain_tags: [bug]
                status_progression:
                  features:
                    terminal_statuses: [completed]
            """.trimIndent())

            val result = service.cleanupFeatureTasks(featureId, "archived")

            assertFalse(result.performed, "Cleanup should not be performed for non-terminal status")
            assertTrue(result.reason.contains("not a terminal status"), "Reason should mention non-terminal")
            assertEquals(0, result.tasksDeleted, "No tasks should be deleted")
        }
    }
}
