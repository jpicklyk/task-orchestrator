package io.github.jpicklyk.mcptask.integration

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ManageContainerTool
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.infrastructure.database.DatabaseManager
import io.github.jpicklyk.mcptask.infrastructure.database.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.util.UUID

/**
 * Comprehensive integration tests for the complete validation pipeline.
 *
 * Tests the integration between ManageContainerTool, StatusValidator,
 * prerequisite validation, error response formatting, and end-to-end workflows.
 *
 * Coverage:
 * - ManageContainerTool integration with StatusValidator (5 tests)
 * - Error response formatting (3 tests)
 * - Parse function tests for new statuses (6 tests)
 * - End-to-end workflow tests (4 tests)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ValidationPipelineIntegrationTest {
    private lateinit var db: Database
    private lateinit var dbManager: DatabaseManager
    private lateinit var projectRepository: SQLiteProjectRepository
    private lateinit var featureRepository: SQLiteFeatureRepository
    private lateinit var taskRepository: SQLiteTaskRepository
    private lateinit var sectionRepository: SQLiteSectionRepository
    private lateinit var dependencyRepository: SQLiteDependencyRepository
    private lateinit var executionContext: ToolExecutionContext
    private lateinit var manageContainerTool: ManageContainerTool

    private val originalUserDir = System.getProperty("user.dir")

    @BeforeAll
    fun setUp(@TempDir tempDir: Path) {
        // Copy default config from main resources to project root
        // This allows StatusValidator to load it while keeping a single source of truth
        val projectRoot = java.nio.file.Paths.get(System.getProperty("user.dir"))
        val configDir = projectRoot.resolve(".taskorchestrator")
        Files.createDirectories(configDir)
        val configFile = configDir.resolve("config.yaml")

        // Load the production default-config.yaml from main resources
        val defaultConfigResource = this::class.java.classLoader.getResourceAsStream("configuration/default-config.yaml")
        if (defaultConfigResource != null) {
            Files.copy(defaultConfigResource, configFile, StandardCopyOption.REPLACE_EXISTING)
            println("Copied default-config.yaml from main resources to $configFile")
        } else {
            throw IllegalStateException("Could not load orchestration/default-config.yaml from classpath")
        }

        println("Config file exists: ${Files.exists(configFile)}")
        println("Config file path: $configFile")
        println("user.dir: ${System.getProperty("user.dir")}")

        // Connect to H2 in-memory database
        db = Database.connect(
            url = "jdbc:h2:mem:validationpipelinetest;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver"
        )

        // Set transaction isolation level
        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_SERIALIZABLE

        // Initialize database with Direct schema manager (Flyway migrations are SQLite-specific)
        // Direct manager uses Exposed DDL which is database-agnostic
        dbManager = DatabaseManager(db)
        dbManager.updateSchema()

        // Initialize real repositories
        projectRepository = SQLiteProjectRepository(dbManager)
        featureRepository = SQLiteFeatureRepository(dbManager)
        taskRepository = SQLiteTaskRepository(dbManager)
        sectionRepository = SQLiteSectionRepository(dbManager)
        dependencyRepository = SQLiteDependencyRepository(dbManager)

        val repositoryProvider = object : RepositoryProvider {
            override fun projectRepository() = projectRepository
            override fun featureRepository() = featureRepository
            override fun taskRepository() = taskRepository
            override fun templateRepository() = throw UnsupportedOperationException("Not used in this test")
            override fun sectionRepository() = sectionRepository
            override fun dependencyRepository() = dependencyRepository
        }

        executionContext = ToolExecutionContext(repositoryProvider)
        manageContainerTool = ManageContainerTool(null, null)
    }

    @AfterAll
    fun tearDown() {
        // Clean up test config file
        try {
            val configFile = java.nio.file.Paths.get(System.getProperty("user.dir"), ".taskorchestrator", "config.yaml")
            Files.deleteIfExists(configFile)
            val configDir = java.nio.file.Paths.get(System.getProperty("user.dir"), ".taskorchestrator")
            Files.deleteIfExists(configDir)
        } catch (e: Exception) {
            // Ignore cleanup errors
        }

        // Restore original user.dir
        System.setProperty("user.dir", originalUserDir)
    }

    // Helper functions to create test entities
    private suspend fun createProject(name: String = "Test Project"): String {
        val response = manageContainerTool.execute(buildJsonObject {
            put("operation", "create")
            put("containerType", "project")
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("name", name)
                    put("summary", "Test project summary")
                })
            })
        }, executionContext)
        val jsonResponse = response as JsonObject
        if (jsonResponse["success"]?.jsonPrimitive?.boolean != true) {
            fail<String>("Failed to create project: ${jsonResponse["message"]?.jsonPrimitive?.content}")
        }
        return jsonResponse["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content!!
    }

    private suspend fun createFeature(projectId: String, name: String = "Test Feature"): String {
        val response = manageContainerTool.execute(buildJsonObject {
            put("operation", "create")
            put("containerType", "feature")
            put("projectId", projectId)
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("name", name)
                    put("summary", "Test feature summary")
                })
            })
        }, executionContext)
        val jsonResponse = response as JsonObject
        if (jsonResponse["success"]?.jsonPrimitive?.boolean != true) {
            fail<String>("Failed to create feature: ${jsonResponse["message"]?.jsonPrimitive?.content}")
        }
        return jsonResponse["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content!!
    }

    private suspend fun createTask(featureId: String, title: String = "Test Task", summary: String = "Short summary"): String {
        val response = manageContainerTool.execute(buildJsonObject {
            put("operation", "create")
            put("containerType", "task")
            put("featureId", featureId)
            put("containers", buildJsonArray {
                add(buildJsonObject {
                    put("title", title)
                    put("summary", summary)
                })
            })
        }, executionContext)
        val jsonResponse = response as JsonObject
        if (jsonResponse["success"]?.jsonPrimitive?.boolean != true) {
            fail<String>("Failed to create task: ${jsonResponse["message"]?.jsonPrimitive?.content}")
        }
        return jsonResponse["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.content!!
    }

    // ========== MANAGE CONTAINER TOOL INTEGRATION TESTS (5 tests) ==========

    @Nested
    inner class ManageContainerToolIntegrationTests {

        @Test
        fun `should call StatusValidator for task status transitions`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            // Create task with short summary - should be allowed now
            val taskId = createTask(featureId, summary = "Short")

            // First transition through intermediate statuses
            // PENDING -> IN_PROGRESS
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "in-progress")
                    })
                })
            }, executionContext)

            // IN_PROGRESS -> TESTING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            // Now attempt TESTING -> COMPLETED with short summary (should succeed with new rules)
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "completed")
                    })
                })
            }

            val setStatusResponse = manageContainerTool.execute(setStatusParams, executionContext)
            val setStatusResult = setStatusResponse as JsonObject

            // Should succeed - short summaries are now allowed
            assertTrue(setStatusResult["success"]?.jsonPrimitive?.boolean == true,
                "Setting task to COMPLETED should succeed with any summary under 500 chars")
        }

        @Test
        fun `should pass context through to StatusValidator for feature prerequisite validation`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)

            // Attempt to transition to IN_DEVELOPMENT without tasks (should fail)
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "in-development")
                    })
                })
            }

            val setStatusResponse = manageContainerTool.execute(setStatusParams, executionContext)
            val setStatusResult = setStatusResponse as JsonObject

            // Should return success=true with failed items
            assertTrue(setStatusResult["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = setStatusResult["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should have 1 failed item")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertTrue(errorDetails?.contains("at least 1 task") == true,
                "Error should mention task requirement")
        }

        @Test
        fun `should handle task IN_PROGRESS dependency check correctly`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val task1Id = createTask(featureId, "Blocking Task")
            val task2Id = createTask(featureId, "Blocked Task")

            // Create blocking dependency
            dependencyRepository.create(
                Dependency(
                    fromTaskId = UUID.fromString(task1Id),
                    toTaskId = UUID.fromString(task2Id),
                    type = DependencyType.BLOCKS
                )
            )

            // Attempt to start task2 while task1 is still PENDING (should fail)
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task2Id)
                        put("status", "in-progress")
                    })
                })
            }

            val setStatusResponse = manageContainerTool.execute(setStatusParams, executionContext)
            val setStatusResult = setStatusResponse as JsonObject

            // Should return success=true with failed items
            assertTrue(setStatusResult["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = setStatusResult["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should have 1 failed item")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertTrue(errorDetails?.contains("blocked by") == true,
                "Error should mention blocking dependencies")
        }

        @Test
        fun `should validate project COMPLETED requires all features completed`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)

            // Transition project to IN_DEVELOPMENT first (prerequisite for COMPLETED)
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", projectId)
                        put("status", "in-development")
                    })
                })
            }, executionContext)

            // Attempt to complete project (should fail because feature is not completed)
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", projectId)
                        put("status", "completed")
                    })
                })
            }

            val setStatusResponse = manageContainerTool.execute(setStatusParams, executionContext)
            val setStatusResult = setStatusResponse as JsonObject

            // Should return success=true with failed items
            assertTrue(setStatusResult["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = setStatusResult["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should have 1 failed item")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertTrue(errorDetails?.contains("feature(s) not completed") == true,
                "Error should mention incomplete features")
        }

        @Test
        fun `should format validation errors with current status, attempted status, and suggestions`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val taskId = createTask(featureId)

            // Transition through intermediate statuses to reach TESTING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "in-progress")
                    })
                })
            }, executionContext)

            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            // Now update the summary to be exactly 500 characters (should be allowed)
            val exactLimitSummary = "A".repeat(500)
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId)
                put("summary", exactLimitSummary)
            }, executionContext)

            // Attempt to complete with summary at exactly 500 chars - should succeed
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "completed")
                    })
                })
            }

            val setStatusResponse = manageContainerTool.execute(setStatusParams, executionContext)
            val setStatusResult = setStatusResponse as JsonObject

            // Verify success - 500 chars is exactly at the limit
            assertTrue(setStatusResult["success"]?.jsonPrimitive?.boolean == true,
                "Should succeed with summary exactly at 500 char limit")
        }
    }

    // ========== ERROR RESPONSE FORMATTING TESTS (3 tests) ==========

    @Nested
    inner class ErrorResponseFormattingTests {

        @Test
        fun `should format prerequisite validation error with reason and suggestions`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)

            // Try to transition to IN_DEVELOPMENT without tasks
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "in-development")
                    })
                })
            }

            val response = manageContainerTool.execute(setStatusParams, executionContext)
            val result = response as JsonObject

            // Verify comprehensive error response
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = result["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should have 1 failed item")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertNotNull(errorDetails, "Should have error details")
            assertTrue(errorDetails?.contains("at least 1 task") == true,
                "Error should explain prerequisite requirement")
        }

        @Test
        fun `should format dependency blocking error with task details`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val task1Id = createTask(featureId, "Blocker Task")
            val task2Id = createTask(featureId, "Dependent Task")

            // Create dependency
            dependencyRepository.create(
                Dependency(
                    fromTaskId = UUID.fromString(task1Id),
                    toTaskId = UUID.fromString(task2Id),
                    type = DependencyType.BLOCKS
                )
            )

            // Try to start dependent task
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task2Id)
                        put("status", "in-progress")
                    })
                })
            }

            val response = manageContainerTool.execute(setStatusParams, executionContext)
            val result = response as JsonObject

            // Verify error includes blocking task details
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = result["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should have 1 failed item")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertTrue(errorDetails?.contains("blocked by") == true,
                "Error should mention blocking")
            assertTrue(errorDetails?.contains("Blocker Task") == true,
                "Error should include blocking task title")
        }

        @Test
        fun `should format summary length validation error with current length`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            // Create task with short summary
            val taskId = createTask(featureId, summary = "Short")

            // Transition through intermediate statuses to reach TESTING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "in-progress")
                    })
                })
            }, executionContext)

            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            // Update task summary to exactly 500 characters (at the limit)
            val atLimitSummary = "A".repeat(500)
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("id", taskId)
                put("summary", atLimitSummary)
            }, executionContext)

            // Try to complete - should succeed
            val setStatusParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "completed")
                    })
                })
            }

            val response = manageContainerTool.execute(setStatusParams, executionContext)
            val result = response as JsonObject

            // Should succeed with 500 char summary
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Should succeed with summary at 500 char limit")
        }
    }

    // ========== PARSE FUNCTION TESTS (6 tests) ==========

    @Nested
    inner class ParseFunctionTests {

        @Test
        fun `should parse new TaskStatus BACKLOG`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)

            val createTaskParams = buildJsonObject {
                put("operation", "create")
                put("containerType", "task")
                put("featureId", featureId)
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("title", "Backlog Task")
                        put("summary", "Task in backlog")
                        put("status", "backlog")
                    })
                })
            }

            val response = manageContainerTool.execute(createTaskParams, executionContext)
            val result = response as JsonObject

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Should successfully create task with BACKLOG status")

            val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
            assertEquals("backlog", status, "Status should be backlog")
        }

        @Test
        fun `should parse new TaskStatus IN_REVIEW with format variations`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val testCases = listOf("in-review", "in_review", "inreview")

            for (statusFormat in testCases) {
                val createTaskParams = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("featureId", featureId)
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Review Task $statusFormat")
                            put("summary", "Task in review")
                            put("status", statusFormat)
                        })
                    })
                }

                val response = manageContainerTool.execute(createTaskParams, executionContext)
                val result = response as JsonObject

                assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                    "Should successfully parse IN_REVIEW status format: $statusFormat")

                val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
                assertEquals("in-review", status, "Status should normalize to in-review")
            }
        }

        @Test
        fun `should parse new TaskStatus CHANGES_REQUESTED with format variations`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val testCases = listOf("changes-requested", "changes_requested", "changesrequested")

            for (statusFormat in testCases) {
                val createTaskParams = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("featureId", featureId)
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Changes Task $statusFormat")
                            put("summary", "Task needs changes")
                            put("status", statusFormat)
                        })
                    })
                }

                val response = manageContainerTool.execute(createTaskParams, executionContext)
                val result = response as JsonObject

                assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                    "Should successfully parse CHANGES_REQUESTED status format: $statusFormat")

                val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
                assertEquals("changes-requested", status, "Status should normalize to changes-requested")
            }
        }

        @Test
        fun `should parse new TaskStatus ON_HOLD with format variations`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val testCases = listOf("on-hold", "on_hold", "onhold")

            for (statusFormat in testCases) {
                val createTaskParams = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "task")
                    put("featureId", featureId)
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("title", "Hold Task $statusFormat")
                            put("summary", "Task on hold")
                            put("status", statusFormat)
                        })
                    })
                }

                val response = manageContainerTool.execute(createTaskParams, executionContext)
                val result = response as JsonObject

                assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                    "Should successfully parse ON_HOLD status format: $statusFormat")

                val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
                assertEquals("on-hold", status, "Status should normalize to on-hold")
            }
        }

        @Test
        fun `should parse new FeatureStatus DRAFT`() = runBlocking {
            val projectId = createProject()

            val createFeatureParams = buildJsonObject {
                put("operation", "create")
                put("containerType", "feature")
                put("projectId", projectId)
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "Draft Feature")
                        put("summary", "Feature in draft")
                        put("status", "draft")
                    })
                })
            }

            val response = manageContainerTool.execute(createFeatureParams, executionContext)
            val result = response as JsonObject

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Should successfully create feature with DRAFT status")

            val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
            assertEquals("draft", status, "Status should be draft")
        }

        @Test
        fun `should parse new FeatureStatus ON_HOLD with format variations`() = runBlocking {
            val projectId = createProject()
            val testCases = listOf("on-hold", "on_hold", "onhold")

            for (statusFormat in testCases) {
                val createFeatureParams = buildJsonObject {
                    put("operation", "create")
                    put("containerType", "feature")
                    put("projectId", projectId)
                    put("containers", buildJsonArray {
                        add(buildJsonObject {
                            put("name", "Hold Feature $statusFormat")
                            put("summary", "Feature on hold")
                            put("status", statusFormat)
                        })
                    })
                }

                val response = manageContainerTool.execute(createFeatureParams, executionContext)
                val result = response as JsonObject

                assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                    "Should successfully parse ON_HOLD status format: $statusFormat")

                val status = result["data"]?.jsonObject?.get("items")?.jsonArray?.get(0)?.jsonObject?.get("status")?.jsonPrimitive?.content
                assertEquals("on-hold", status, "Status should normalize to on-hold")
            }
        }
    }

    // ========== END-TO-END WORKFLOW TESTS (4 tests) ==========

    @Nested
    inner class EndToEndWorkflowTests {

        @Test
        fun `should complete full feature workflow with prerequisite validation`() = runBlocking {
            // 1. Create project
            val projectId = createProject("Workflow Project")

            // 2. Create feature
            val featureId = createFeature(projectId, "Workflow Feature")

            // 3. Create task for feature with valid summary
            val taskId = createTask(featureId, "Workflow Task", "A".repeat(350))

            // 4. Try to transition feature to IN_DEVELOPMENT (should succeed - has task)
            val featureToDevParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "in-development")
                    })
                })
            }

            val featureToDevResponse = manageContainerTool.execute(featureToDevParams, executionContext)
            assertTrue((featureToDevResponse as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Feature should transition to IN_DEVELOPMENT with tasks present")

            // 5. Complete the task (transition through intermediate statuses)
            // PENDING -> IN_PROGRESS
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "in-progress")
                    })
                })
            }, executionContext)

            // IN_PROGRESS -> TESTING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            // TESTING -> COMPLETED
            val completeTaskParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", taskId)
                        put("status", "completed")
                    })
                })
            }

            val completeTaskResponse = manageContainerTool.execute(completeTaskParams, executionContext)
            assertTrue((completeTaskResponse as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Task should complete with valid summary")

            // 6. Complete the feature (transition through intermediate statuses)
            // IN_DEVELOPMENT -> TESTING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            // TESTING -> VALIDATING
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "validating")
                    })
                })
            }, executionContext)

            // VALIDATING -> COMPLETED
            val completeFeatureParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "completed")
                    })
                })
            }

            val completeFeatureResponse = manageContainerTool.execute(completeFeatureParams, executionContext)
            assertTrue((completeFeatureResponse as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Feature should complete with all tasks completed")

            // 7. Complete the project (transition through intermediate statuses)
            // PLANNING -> IN_DEVELOPMENT
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", projectId)
                        put("status", "in-development")
                    })
                })
            }, executionContext)

            // IN_DEVELOPMENT -> COMPLETED (projects have shorter flow)
            val completeProjectParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "project")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", projectId)
                        put("status", "completed")
                    })
                })
            }

            val completeProjectResponse = manageContainerTool.execute(completeProjectParams, executionContext)
            assertTrue((completeProjectResponse as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Project should complete with all features completed")
        }

        @Test
        fun `should handle blocked task scenario with dependency resolution`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            val task1Id = createTask(featureId, "Prerequisite Task", "A".repeat(350))
            val task2Id = createTask(featureId, "Dependent Task", "A".repeat(350))

            // Create blocking dependency
            dependencyRepository.create(
                Dependency(
                    fromTaskId = UUID.fromString(task1Id),
                    toTaskId = UUID.fromString(task2Id),
                    type = DependencyType.BLOCKS
                )
            )

            // Try to start task2 (should fail - blocked)
            val startTask2Params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task2Id)
                        put("status", "in-progress")
                    })
                })
            }

            val startTask2Response = manageContainerTool.execute(startTask2Params, executionContext)
            val result = startTask2Response as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            assertEquals(1, result["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int,
                "Task2 should not start while blocked")

            // Complete task1 (transition through intermediate statuses)
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1Id)
                        put("status", "in-progress")
                    })
                })
            }, executionContext)

            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1Id)
                        put("status", "testing")
                    })
                })
            }, executionContext)

            val completeTask1Params = buildJsonObject {
                put("operation", "update")
                put("containerType", "task")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", task1Id)
                        put("status", "completed")
                    })
                })
            }

            manageContainerTool.execute(completeTask1Params, executionContext)

            // Now try to start task2 (should succeed - blocker resolved)
            val startTask2RetryResponse = manageContainerTool.execute(startTask2Params, executionContext)
            assertTrue((startTask2RetryResponse as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Task2 should start after blocker is completed")
        }

        @Test
        fun `should recover from validation failure after fixing prerequisites`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)

            // Try to transition to IN_DEVELOPMENT (should fail - no tasks)
            val toDevParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "in-development")
                    })
                })
            }

            val toDevResponse1 = manageContainerTool.execute(toDevParams, executionContext)
            val result1 = toDevResponse1 as JsonObject
            assertTrue(result1["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            assertEquals(1, result1["data"]?.jsonObject?.get("failed")?.jsonPrimitive?.int,
                "Should fail without tasks")

            // Fix prerequisite - create a task
            createTask(featureId, "Recovery Task", "A".repeat(350))

            // Retry transition (should succeed now)
            val toDevResponse2 = manageContainerTool.execute(toDevParams, executionContext)
            assertTrue((toDevResponse2 as JsonObject)["success"]?.jsonPrimitive?.boolean == true,
                "Should succeed after creating task")
        }

        @Test
        fun `should validate TESTING status requires all tasks completed`() = runBlocking {
            val projectId = createProject()
            val featureId = createFeature(projectId)
            createTask(featureId, "Test Task", "A".repeat(350))

            // Transition feature to IN_DEVELOPMENT first
            manageContainerTool.execute(buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "in-development")
                    })
                })
            }, executionContext)

            // Try to transition to TESTING (should fail - task not completed)
            val toTestingParams = buildJsonObject {
                put("operation", "update")
                put("containerType", "feature")
                put("containers", buildJsonArray {
                    add(buildJsonObject {
                        put("id", featureId)
                        put("status", "testing")
                    })
                })
            }

            val toTestingResponse = manageContainerTool.execute(toTestingParams, executionContext)
            val result = toTestingResponse as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true,
                "Batch operations always return success=true")
            val data = result["data"]?.jsonObject
            assertEquals(1, data?.get("failed")?.jsonPrimitive?.int,
                "Should fail with incomplete tasks")
            val failures = data?.get("failures")?.jsonArray
            val errorDetails = failures?.get(0)?.jsonObject?.get("error")?.jsonObject?.get("details")?.jsonPrimitive?.content
            assertTrue(errorDetails?.contains("not completed") == true,
                "Error should mention incomplete tasks")
        }
    }
}
