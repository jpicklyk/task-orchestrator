package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
import io.github.jpicklyk.mcptask.application.service.progression.NextStatusRecommendation
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.domain.model.*
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.*

/**
 * Tests for verification gate behavior in RequestTransitionTool.
 * Verifies that the "complete" trigger respects requiresVerification flag.
 */
class RequestTransitionToolVerificationTest {
    private lateinit var tool: RequestTransitionTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockStatusProgressionService: StatusProgressionService
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val taskId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private lateinit var inProgressTask: Task

    // Summary must be at most 500 characters to pass the StatusValidator completion prerequisite
    private val validSummary = "This is a comprehensive test task summary that provides detailed information about " +
        "the work being performed. It covers the implementation approach, testing strategy, and expected outcomes " +
        "for the verification gate feature. The task involves creating unit tests for the verification service and " +
        "integration tests for the manage container tool and request transition tool."

    @BeforeEach
    fun setup() {
        mockStatusProgressionService = mockk()
        mockTaskRepository = mockk()
        mockFeatureRepository = mockk()
        mockProjectRepository = mockk()
        mockSectionRepository = mockk()
        mockDependencyRepository = mockk()
        mockTemplateRepository = mockk()
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.taskRepository() } returns mockTaskRepository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository
        every { mockRepositoryProvider.sectionRepository() } returns mockSectionRepository
        every { mockRepositoryProvider.dependencyRepository() } returns mockDependencyRepository
        every { mockRepositoryProvider.templateRepository() } returns mockTemplateRepository

        inProgressTask = Task(
            id = taskId,
            title = "Test Task",
            description = "Test description",
            summary = validSummary,
            status = TaskStatus.IN_PROGRESS,
            priority = Priority.HIGH,
            complexity = 5,
            tags = listOf("backend"),
            featureId = featureId,
            projectId = projectId,
            requiresVerification = true,
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )

        // Default mock for role lookups
        every { mockStatusProgressionService.getRoleForStatus(any(), any(), any()) } returns null

        context = ToolExecutionContext(mockRepositoryProvider)
        tool = RequestTransitionTool(mockStatusProgressionService)
    }

    private fun createVerificationSection(
        entityType: EntityType,
        entityId: UUID,
        content: String
    ): Section {
        return Section(
            id = UUID.randomUUID(),
            entityType = entityType,
            entityId = entityId,
            title = "Verification",
            usageDescription = "Acceptance criteria",
            content = content,
            contentFormat = ContentFormat.JSON,
            ordinal = 0,
            tags = emptyList(),
            createdAt = Instant.now(),
            modifiedAt = Instant.now()
        )
    }

    @Nested
    inner class CompleteTriggerVerificationGateTests {

        @Test
        fun `complete trigger with requiresVerification true and no section is blocked`() = runBlocking {
            // Task fetched for entity details
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            // No verification section
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
            } returns Result.Success(emptyList())

            // Mock dependency check for status validation
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("Completion blocked"))

            // Verify gate data is in the error's additionalData
            val error = result["error"]?.jsonObject
            assertNotNull(error)
            val additionalData = error?.get("additionalData")?.jsonObject
            assertNotNull(additionalData)
            assertEquals("verification", additionalData?.get("gate")?.jsonPrimitive?.content)
        }

        @Test
        fun `complete trigger with requiresVerification true and all criteria passing is allowed`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            // Verification section with all criteria passing
            val verificationSection = createVerificationSection(
                EntityType.TASK, taskId,
                """[{"criteria": "Tests pass", "pass": true}, {"criteria": "Reviewed", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.TASK, taskId)
            } returns Result.Success(listOf(verificationSection))

            // Mock dependency check for status validation
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock the update
            val completedTask = inProgressTask.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(completedTask)

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("completed", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
        }

        @Test
        fun `complete trigger with requiresVerification false skips gate`() = runBlocking {
            // Task without verification requirement
            val taskNoVerification = inProgressTask.copy(requiresVerification = false)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(taskNoVerification)

            // Mock dependency check
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock the update
            val completedTask = taskNoVerification.copy(status = TaskStatus.COMPLETED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(completedTask)

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("completed", data["newStatus"]!!.jsonPrimitive.content)
        }
    }

    @Nested
    inner class OtherTriggersUnaffectedTests {

        @Test
        fun `cancel trigger with requiresVerification true is allowed`() = runBlocking {
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(inProgressTask)

            // Mock the update
            val cancelledTask = inProgressTask.copy(status = TaskStatus.CANCELLED)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(cancelledTask)

            // Mock dependency check
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "cancel")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("cancelled", data["newStatus"]!!.jsonPrimitive.content)
        }

        @Test
        fun `start trigger is unaffected by verification`() = runBlocking {
            val pendingTask = inProgressTask.copy(status = TaskStatus.PENDING)
            coEvery { mockTaskRepository.getById(taskId) } returns Result.Success(pendingTask)

            // Mock status progression
            coEvery { mockStatusProgressionService.getNextStatus(
                currentStatus = any(),
                containerType = any(),
                tags = any(),
                containerId = any()
            ) } returns NextStatusRecommendation.Ready(
                recommendedStatus = "in-progress",
                activeFlow = "default_flow",
                flowSequence = listOf("backlog", "pending", "in-progress", "testing", "completed"),
                currentPosition = 1,
                matchedTags = emptyList(),
                reason = "Next status in default_flow workflow"
            )

            // Mock the update
            val updatedTask = pendingTask.copy(status = TaskStatus.IN_PROGRESS)
            coEvery { mockTaskRepository.update(any()) } returns Result.Success(updatedTask)

            // Mock dependency check
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val params = buildJsonObject {
                put("containerId", taskId.toString())
                put("containerType", "task")
                put("trigger", "start")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("in-progress", data["newStatus"]!!.jsonPrimitive.content)
            assertTrue(data["applied"]!!.jsonPrimitive.boolean)
        }
    }

    @Nested
    inner class FeatureCompleteTriggerTests {

        @Test
        fun `complete trigger on feature with requiresVerification true and no section is blocked`() = runBlocking {
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                summary = "Test feature summary",
                status = FeatureStatus.VALIDATING,
                priority = Priority.HIGH,
                projectId = projectId,
                requiresVerification = true
            )
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // No verification section
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId)
            } returns Result.Success(emptyList())

            // Mock dependency check
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock task prerequisite check - feature completion requires all tasks completed
            val completedTask = Task(
                id = UUID.randomUUID(),
                title = "Completed Task",
                summary = validSummary,
                status = TaskStatus.COMPLETED,
                featureId = featureId
            )
            coEvery {
                mockTaskRepository.findByFeature(featureId, null, null, 1000)
            } returns Result.Success(listOf(completedTask))

            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject

            assertFalse(result["success"]!!.jsonPrimitive.boolean)
            assertTrue(result["message"]!!.jsonPrimitive.content.contains("Completion blocked"))
        }

        @Test
        fun `complete trigger on feature with requiresVerification true and criteria passing is allowed`() = runBlocking {
            val feature = Feature(
                id = featureId,
                name = "Test Feature",
                summary = "Test feature summary",
                status = FeatureStatus.VALIDATING,
                priority = Priority.HIGH,
                projectId = projectId,
                requiresVerification = true
            )
            coEvery { mockFeatureRepository.getById(featureId) } returns Result.Success(feature)

            // Verification section with all criteria passing
            val verificationSection = createVerificationSection(
                EntityType.FEATURE, featureId,
                """[{"criteria": "All tasks done", "pass": true}]"""
            )
            coEvery {
                mockSectionRepository.getSectionsForEntity(EntityType.FEATURE, featureId)
            } returns Result.Success(listOf(verificationSection))

            // Mock dependency check
            every { mockDependencyRepository.findByTaskId(any()) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            // Mock task prerequisite check - feature completion requires all tasks completed
            val completedTask = Task(
                id = UUID.randomUUID(),
                title = "Completed Task",
                summary = validSummary,
                status = TaskStatus.COMPLETED,
                featureId = featureId
            )
            coEvery {
                mockTaskRepository.findByFeature(featureId, null, null, 1000)
            } returns Result.Success(listOf(completedTask))

            // Mock the update
            val completedFeature = feature.update(status = FeatureStatus.COMPLETED)
            coEvery { mockFeatureRepository.update(any()) } returns Result.Success(completedFeature)

            val params = buildJsonObject {
                put("containerId", featureId.toString())
                put("containerType", "feature")
                put("trigger", "complete")
            }

            val result = tool.execute(params, context) as JsonObject

            assertTrue(result["success"]!!.jsonPrimitive.boolean)
            val data = result["data"]!!.jsonObject
            assertEquals("completed", data["newStatus"]!!.jsonPrimitive.content)
        }
    }
}
