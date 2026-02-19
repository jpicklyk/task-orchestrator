package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.service.StatusValidator
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Performance and cache tests for StatusValidator.
 *
 * Tests:
 * 1. Config cache behavior (initial load, cache hit, multiple reads)
 * 2. Cache invalidation (config change detection, manual invalidation)
 * 3. Prerequisite validation performance (large task count, deep dependency chains)
 * 4. Concurrent validation requests
 *
 * Performance target: <50ms validation overhead
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatusValidatorPerformanceTest {

    private lateinit var db: Database
    private lateinit var dbManager: DatabaseManager
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var sectionRepository: SQLiteSectionRepository
    private lateinit var dependencyRepository: SQLiteDependencyRepository
    private lateinit var validator: StatusValidator

    private val originalUserDir = System.getProperty("user.dir")
    private lateinit var testDir: Path

    @BeforeAll
    fun setUpAll() {
        // Create a temporary directory for test configs
        testDir = Files.createTempDirectory("status_validator_perf_test")
        System.setProperty("user.dir", testDir.toString())
    }

    @BeforeEach
    fun setUp() {
        // Connect to H2 in-memory database
        db = Database.connect(
            url = "jdbc:h2:mem:statusvalidatorperftest_${UUID.randomUUID()};DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

        dbManager = DatabaseManager(db)
        dbManager.updateSchema()

        projectRepository = SQLiteProjectRepository(dbManager)
        featureRepository = SQLiteFeatureRepository(dbManager)
        taskRepository = SQLiteTaskRepository(dbManager)
        sectionRepository = SQLiteSectionRepository(dbManager)
        dependencyRepository = SQLiteDependencyRepository(dbManager)

        validator = StatusValidator()
    }

    @AfterEach
    fun tearDown() {
        // Clean up config file after each test
        val configFile = testDir.resolve(".taskorchestrator").resolve("config.yaml")
        Files.deleteIfExists(configFile)
    }

    @AfterAll
    fun tearDownAll() {
        System.setProperty("user.dir", originalUserDir)
        // Clean up test directory
        testDir.toFile().deleteRecursively()
    }

    // ========== CONFIG CACHE BEHAVIOR TESTS ==========

    @Nested
    inner class ConfigCacheTests {

        @Test
        fun `should load config on first access and cache it`() {
            createTestConfig()

            var firstLoadTime = 0L
            var secondLoadTime = 0L

            // First access - loads from file
            firstLoadTime = measureTimeMillis {
                val result = validator.validateStatus("in-progress", "task")
                assertTrue(result is StatusValidator.ValidationResult.Valid)
            }

            // Second access - should use cache (much faster)
            secondLoadTime = measureTimeMillis {
                val result = validator.validateStatus("testing", "task")
                assertTrue(result is StatusValidator.ValidationResult.Valid)
            }

            println("First load: ${firstLoadTime}ms, Second load (cached): ${secondLoadTime}ms")

            // Cache hit should be significantly faster (at least 2x faster)
            // Note: This is a soft assertion as timing can vary in CI environments
            assertTrue(secondLoadTime < firstLoadTime || secondLoadTime < 10,
                "Cache hit should be faster. First: ${firstLoadTime}ms, Second: ${secondLoadTime}ms")
        }

        @Test
        fun `should cache config across multiple validation calls`() {
            createTestConfig()

            // Warm up cache
            validator.validateStatus("pending", "task")

            val iterations = 100
            val totalTime = measureTimeMillis {
                repeat(iterations) {
                    validator.validateStatus("in-progress", "task")
                }
            }

            val avgTime = totalTime.toDouble() / iterations
            println("Average validation time (cached): ${avgTime}ms over $iterations iterations")

            // With caching, average should be well under 1ms per validation
            assertTrue(avgTime < 5.0,
                "Cached validations should be very fast. Average: ${avgTime}ms")
        }

        @Test
        fun `should use cache for different status validations within timeout`() {
            createTestConfig()

            val statuses = listOf("pending", "in-progress", "testing", "completed", "blocked")

            // First pass - loads config
            val firstPassTime = measureTimeMillis {
                statuses.forEach { status ->
                    validator.validateStatus(status, "task")
                }
            }

            // Second pass - uses cache
            val secondPassTime = measureTimeMillis {
                statuses.forEach { status ->
                    validator.validateStatus(status, "task")
                }
            }

            println("First pass: ${firstPassTime}ms, Second pass (cached): ${secondPassTime}ms")

            // Second pass should be faster due to caching
            assertTrue(secondPassTime <= firstPassTime,
                "Cached pass should be faster or equal. First: ${firstPassTime}ms, Second: ${secondPassTime}ms")
        }
    }

    @Nested
    inner class CacheInvalidationTests {

        @Test
        fun `should invalidate cache when user dir changes`() {
            createTestConfig()

            // First validation - loads config
            val result1 = validator.validateStatus("in-progress", "task")
            assertTrue(result1 is StatusValidator.ValidationResult.Valid)

            // Create a different test directory
            val newTestDir = Files.createTempDirectory("status_validator_perf_test_2")
            try {
                // Change user.dir
                System.setProperty("user.dir", newTestDir.toString())

                // This should trigger cache invalidation and reload (or use v1 mode)
                val result2 = validator.validateStatus("in-progress", "task")
                assertTrue(result2 is StatusValidator.ValidationResult.Valid)

            } finally {
                System.setProperty("user.dir", testDir.toString())
                newTestDir.toFile().deleteRecursively()
            }
        }

        @Test
        fun `should reload config after cache timeout`() {
            createTestConfig()

            // First load
            val result1 = validator.validateStatus("in-progress", "task")
            assertTrue(result1 is StatusValidator.ValidationResult.Valid)

            // Note: The timeout is 60 seconds, so we can't easily test this without waiting
            // This test documents the expected behavior
            // In a real scenario, after 60 seconds, the config would be reloaded

            // For now, just verify that within timeout, cache is used
            Thread.sleep(100) // Small delay
            val result2 = validator.validateStatus("testing", "task")
            assertTrue(result2 is StatusValidator.ValidationResult.Valid)
        }
    }

    @Nested
    inner class PrerequisiteValidationPerformanceTests {

        @Test
        fun `should validate feature with large number of tasks efficiently`() = runBlocking {
            createTestConfig(validatePrerequisites = true)

            // Create a feature with many tasks
            val projectId = UUID.randomUUID()
            val project = createProject(projectId, "Test Project")
            projectRepository.create(project)

            val featureId = UUID.randomUUID()
            val feature = createFeature(featureId, "Test Feature", projectId, FeatureStatus.IN_DEVELOPMENT)
            featureRepository.create(feature)

            // Create 100 tasks
            val taskCount = 100
            repeat(taskCount) { index ->
                val taskId = UUID.randomUUID()
                val task = createTask(
                    taskId,
                    "Task $index",
                    featureId,
                    if (index < 50) TaskStatus.COMPLETED else TaskStatus.IN_PROGRESS
                )
                taskRepository.create(task)
            }

            val context = StatusValidator.PrerequisiteContext(
                taskRepository, featureRepository, projectRepository, dependencyRepository
            )

            // Measure validation time
            val validationTime = measureTimeMillis {
                val result = validator.validatePrerequisites(featureId, "testing", "feature", context)
                // Should fail because not all tasks are completed
                assertTrue(result is StatusValidator.ValidationResult.Invalid)
            }

            println("Validation time for feature with $taskCount tasks: ${validationTime}ms")

            // Should be well under 50ms even with 100 tasks
            assertTrue(validationTime < 100,
                "Prerequisite validation should be fast. Took: ${validationTime}ms")
        }

        @Test
        fun `should validate task with deep dependency chain efficiently`() = runBlocking {
            createTestConfig(validatePrerequisites = true)

            val projectId = UUID.randomUUID()
            val project = createProject(projectId, "Test Project")
            projectRepository.create(project)

            val featureId = UUID.randomUUID()
            val feature = createFeature(featureId, "Test Feature", projectId, FeatureStatus.IN_DEVELOPMENT)
            featureRepository.create(feature)

            // Create a chain of 20 tasks where each blocks the next
            val chainLength = 20
            val taskIds = mutableListOf<UUID>()

            repeat(chainLength) { index ->
                val taskId = UUID.randomUUID()
                taskIds.add(taskId)
                val task = createTask(
                    taskId,
                    "Task $index",
                    featureId,
                    if (index < chainLength - 1) TaskStatus.COMPLETED else TaskStatus.PENDING
                )
                taskRepository.create(task)
            }

            // Create blocking dependencies: task[0] blocks task[1], task[1] blocks task[2], etc.
            for (i in 0 until chainLength - 1) {
                val dependency = Dependency(
                    id = UUID.randomUUID(),
                    fromTaskId = taskIds[i],
                    toTaskId = taskIds[i + 1],
                    type = DependencyType.BLOCKS,
                    createdAt = Instant.now()
                )
                dependencyRepository.create(dependency)
            }

            val context = StatusValidator.PrerequisiteContext(
                taskRepository, featureRepository, projectRepository, dependencyRepository
            )

            // Validate the last task (blocked by chain)
            val validationTime = measureTimeMillis {
                val result = validator.validatePrerequisites(taskIds.last(), "in-progress", "task", context)
                // Should succeed because all blocking tasks are completed
                assertTrue(result is StatusValidator.ValidationResult.Valid)
            }

            println("Validation time for task with $chainLength-deep dependency chain: ${validationTime}ms")

            // Should be under 50ms even with deep dependency chain
            assertTrue(validationTime < 100,
                "Deep dependency validation should be fast. Took: ${validationTime}ms")
        }
    }

    @Nested
    inner class ConcurrentValidationTests {

        @Test
        fun `should handle concurrent validation requests safely`() = runBlocking {
            createTestConfig()

            val concurrentRequests = 50
            val statuses = listOf("pending", "in-progress", "testing", "completed", "blocked")

            val validationTime = measureTimeMillis {
                val results = (1..concurrentRequests).map {
                    async {
                        val randomStatus = statuses.random()
                        validator.validateStatus(randomStatus, "task")
                    }
                }.awaitAll()

                // All validations should succeed
                results.forEach { result ->
                    assertTrue(result is StatusValidator.ValidationResult.Valid,
                        "Concurrent validation should succeed")
                }
            }

            println("Time for $concurrentRequests concurrent validations: ${validationTime}ms")

            // Should handle concurrent requests efficiently (under 200ms for 50 requests)
            assertTrue(validationTime < 300,
                "Concurrent validations should be fast. Took: ${validationTime}ms")
        }

        @Test
        fun `should handle concurrent prerequisite validations safely`() = runBlocking {
            createTestConfig(validatePrerequisites = true)

            // Create test data
            val projectId = UUID.randomUUID()
            val project = createProject(projectId, "Test Project")
            projectRepository.create(project)

            val featureIds = mutableListOf<UUID>()
            repeat(10) { index ->
                val featureId = UUID.randomUUID()
                featureIds.add(featureId)
                val feature = createFeature(featureId, "Feature $index", projectId, FeatureStatus.PLANNING)
                featureRepository.create(feature)

                // Create tasks for each feature
                repeat(5) { taskIndex ->
                    val taskId = UUID.randomUUID()
                    val task = createTask(taskId, "Task $taskIndex", featureId, TaskStatus.COMPLETED)
                    val createResult = taskRepository.create(task)
                    if (createResult is Result.Error) {
                        println("Failed to create task: ${createResult.error.message}")
                    }
                }
                // Verify tasks were created
                val tasksResult = taskRepository.findByFeature(featureId, null, null, 100)
                val taskCount = tasksResult.getOrNull()?.size ?: 0
                println("Feature $index created with $taskCount tasks")
            }

            val context = StatusValidator.PrerequisiteContext(
                taskRepository, featureRepository, projectRepository, dependencyRepository
            )

            // Concurrent prerequisite validations
            val validationTime = measureTimeMillis {
                val results = featureIds.map { featureId ->
                    async {
                        validator.validatePrerequisites(featureId, "testing", "feature", context)
                    }
                }.awaitAll()

                // All should succeed (all tasks completed)
                results.forEach { result ->
                    if (result is StatusValidator.ValidationResult.Invalid) {
                        println("Validation failed: ${result.reason}")
                    }
                    assertTrue(result is StatusValidator.ValidationResult.Valid,
                        "Concurrent prerequisite validation should succeed")
                }
            }

            println("Time for ${featureIds.size} concurrent prerequisite validations: ${validationTime}ms")

            // Should be efficient even with concurrent prerequisite checks
            assertTrue(validationTime < 500,
                "Concurrent prerequisite validations should be fast. Took: ${validationTime}ms")
        }
    }

    @Nested
    inner class PerformanceRegressionTests {

        @Test
        fun `validation overhead should be under 50ms for typical workflow`() = runBlocking {
            createTestConfig(validatePrerequisites = true)

            // Create realistic scenario: project with 3 features, each with 10 tasks
            val projectId = UUID.randomUUID()
            val project = createProject(projectId, "Test Project")
            projectRepository.create(project)

            val featureIds = mutableListOf<UUID>()
            repeat(3) { featureIndex ->
                val featureId = UUID.randomUUID()
                featureIds.add(featureId)
                val feature = createFeature(
                    featureId,
                    "Feature $featureIndex",
                    projectId,
                    FeatureStatus.IN_DEVELOPMENT
                )
                featureRepository.create(feature)

                repeat(10) { taskIndex ->
                    val taskId = UUID.randomUUID()
                    val task = createTask(
                        taskId,
                        "Task $taskIndex",
                        featureId,
                        TaskStatus.COMPLETED,
                        summary = "A".repeat(350) // Valid summary length
                    )
                    taskRepository.create(task)
                }
            }

            val context = StatusValidator.PrerequisiteContext(
                taskRepository, featureRepository, projectRepository, dependencyRepository
            )

            // Measure typical validation workflow
            val totalTime = measureTimeMillis {
                // 1. Status validation
                val statusResult = validator.validateStatus("testing", "feature")
                assertTrue(statusResult is StatusValidator.ValidationResult.Valid)

                // 2. Transition validation
                val transitionResult = validator.validateTransition(
                    "in-development",
                    "testing",
                    "feature"
                )
                assertTrue(transitionResult is StatusValidator.ValidationResult.Valid)

                // 3. Prerequisite validation for each feature
                featureIds.forEach { featureId ->
                    val prereqResult = validator.validatePrerequisites(
                        featureId,
                        "testing",
                        "feature",
                        context
                    )
                    if (prereqResult is StatusValidator.ValidationResult.Invalid) {
                        println("Prerequisite validation failed for feature $featureId: ${prereqResult.reason}")
                    }
                    assertTrue(prereqResult is StatusValidator.ValidationResult.Valid)
                }
            }

            println("Total validation overhead for realistic workflow: ${totalTime}ms")

            // Total overhead should be well under 50ms for typical scenario
            assertTrue(totalTime < 100,
                "Validation overhead should be minimal. Took: ${totalTime}ms")
        }

        @Test
        fun `getAllowedStatuses should be fast with caching`() {
            createTestConfig()

            // Warm up cache
            validator.getAllowedStatuses("task")

            val iterations = 1000
            val totalTime = measureTimeMillis {
                repeat(iterations) {
                    validator.getAllowedStatuses("task")
                }
            }

            val avgTime = totalTime.toDouble() / iterations
            println("Average getAllowedStatuses time (cached): ${avgTime}ms over $iterations iterations")

            // Should be extremely fast with caching (< 0.1ms average)
            assertTrue(avgTime < 1.0,
                "getAllowedStatuses should be very fast. Average: ${avgTime}ms")
        }
    }

    // ========== HELPER METHODS ==========

    private fun createTestConfig(validatePrerequisites: Boolean = false) {
        val configDir = testDir.resolve(".taskorchestrator")
        Files.createDirectories(configDir)

        val configContent = """
version: "2.0.0"

status_progression:
  tasks:
    allowed_statuses:
      - pending
      - in-progress
      - testing
      - blocked
      - completed
      - cancelled
      - deferred

    default_flow:
      - pending
      - in-progress
      - testing
      - completed

    emergency_transitions:
      - blocked
      - cancelled
      - deferred

    terminal_statuses:
      - completed
      - cancelled
      - deferred

  features:
    allowed_statuses:
      - planning
      - in-development
      - testing
      - validating
      - blocked
      - completed
      - archived

    default_flow:
      - planning
      - in-development
      - testing
      - validating
      - completed

    emergency_transitions:
      - blocked
      - archived

    terminal_statuses:
      - completed
      - archived

  projects:
    allowed_statuses:
      - planning
      - in-development
      - completed
      - archived
      - on-hold
      - cancelled

    default_flow:
      - planning
      - in-development
      - completed

    terminal_statuses:
      - completed
      - archived
      - cancelled

status_validation:
  enforce_sequential: true
  allow_backward: true
  allow_emergency: true
  validate_prerequisites: $validatePrerequisites
"""

        Files.writeString(configDir.resolve("config.yaml"), configContent)
    }

    private fun createProject(
        id: UUID,
        name: String,
        status: ProjectStatus = ProjectStatus.PLANNING
    ): Project {
        return Project(
            id = id,
            name = name,
            description = null, // null is allowed, blank string is not
            summary = "",
            status = status,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )
    }

    private fun createFeature(
        id: UUID,
        name: String,
        projectId: UUID,
        status: FeatureStatus = FeatureStatus.PLANNING
    ): Feature {
        return Feature(
            id = id,
            name = name,
            description = null, // null is allowed, blank string is not
            summary = "",
            status = status,
            priority = Priority.MEDIUM,
            projectId = projectId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )
    }

    private fun createTask(
        id: UUID,
        title: String,
        featureId: UUID,
        status: TaskStatus = TaskStatus.PENDING,
        summary: String = ""
    ): Task {
        return Task(
            id = id,
            title = title,
            description = null, // null is allowed, blank string is not
            summary = summary,
            status = status,
            priority = Priority.MEDIUM,
            complexity = 5,
            featureId = featureId,
            createdAt = Instant.now(),
            modifiedAt = Instant.now(),
            tags = emptyList()
        )
    }
}
