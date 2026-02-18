package io.github.jpicklyk.mcptask.application.tools.task

import io.github.jpicklyk.mcptask.application.service.progression.StatusProgressionService
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

class GetNextTaskToolTest {

    private lateinit var tool: GetNextTaskTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockTaskRepository: TaskRepository
    private lateinit var mockFeatureRepository: FeatureRepository
    private lateinit var mockProjectRepository: ProjectRepository
    private lateinit var mockSectionRepository: SectionRepository
    private lateinit var mockDependencyRepository: DependencyRepository
    private lateinit var mockTemplateRepository: TemplateRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val projectId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
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

        // Default context without StatusProgressionService
        context = ToolExecutionContext(mockRepositoryProvider)

        tool = GetNextTaskTool()
    }

    private fun createTask(
        id: UUID = UUID.randomUUID(),
        title: String = "Test Task",
        description: String? = "Test description",
        summary: String = "Test summary",
        status: TaskStatus = TaskStatus.PENDING,
        priority: Priority = Priority.HIGH,
        complexity: Int = 5,
        tags: List<String> = listOf("backend"),
        featureId: UUID? = this.featureId,
        projectId: UUID? = this.projectId
    ): Task {
        val now = Instant.now()
        return Task(
            id = id,
            title = title,
            description = description,
            summary = summary,
            status = status,
            priority = priority,
            complexity = complexity,
            tags = tags,
            featureId = featureId,
            projectId = projectId,
            createdAt = now,
            modifiedAt = now
        )
    }

    private fun createDependency(
        fromTaskId: UUID,
        toTaskId: UUID,
        type: DependencyType = DependencyType.BLOCKS,
        unblockAt: String? = null
    ): Dependency {
        return Dependency(
            id = UUID.randomUUID(),
            fromTaskId = fromTaskId,
            toTaskId = toTaskId,
            type = type,
            unblockAt = unblockAt,
            createdAt = Instant.now()
        )
    }

    private fun parseResult(result: JsonElement): JsonObject = result as JsonObject

    private fun extractData(result: JsonObject): JsonObject = result["data"]!!.jsonObject

    private fun extractRecommendations(result: JsonObject): JsonArray =
        extractData(result)["recommendations"]!!.jsonArray

    @Nested
    inner class RoleBasedBlockingTests {

        private lateinit var mockStatusProgressionService: StatusProgressionService
        private lateinit var roleAwareContext: ToolExecutionContext

        @BeforeEach
        fun setupRoleAware() {
            mockStatusProgressionService = mockk()
            roleAwareContext = ToolExecutionContext(
                mockRepositoryProvider,
                statusProgressionService = mockStatusProgressionService
            )
        }

        @Test
        fun `should recommend task when unblockAt=work and blocker is at in-progress (role=work)`() = runBlocking {
            val blockerTask = createTask(
                title = "Blocker", status = TaskStatus.IN_PROGRESS, tags = listOf("backend")
            )
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = blockerTask.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS, unblockAt = "work"
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)
            every {
                mockStatusProgressionService.getRoleForStatus("in-progress", "task", listOf("backend"))
            } returns "work"

            val result = parseResult(tool.execute(params, roleAwareContext))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            // blockedTask should be recommended (unblocked because blocker is at "work" role)
            assertEquals(1, recommendations.size)
            assertEquals(blockedTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should NOT recommend task when unblockAt=work and blocker is at pending (role=queue)`() = runBlocking {
            val blockerTask = createTask(
                title = "Blocker", status = TaskStatus.PENDING, tags = listOf("backend")
            )
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = blockerTask.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS, unblockAt = "work"
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            // blockerTask has no deps
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)
            every {
                mockStatusProgressionService.getRoleForStatus("pending", "task", listOf("backend"))
            } returns "queue"

            val result = parseResult(tool.execute(params, roleAwareContext))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            // blockedTask should NOT be recommended (blocker is still at "queue", needs "work")
            // Only blockerTask should be recommended (it's unblocked)
            assertEquals(1, recommendations.size)
            assertEquals(blockerTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should NOT recommend task when unblockAt=null (default terminal) and blocker is at in-progress`() = runBlocking {
            val blockerTask = createTask(
                title = "Blocker", status = TaskStatus.IN_PROGRESS, tags = listOf("backend")
            )
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            // No unblockAt, defaults to "terminal"
            val dep = createDependency(
                fromTaskId = blockerTask.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            val params = buildJsonObject { }

            // Only PENDING tasks are candidates for get_next_task
            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)
            every {
                mockStatusProgressionService.getRoleForStatus("in-progress", "task", listOf("backend"))
            } returns "work"

            val result = parseResult(tool.execute(params, roleAwareContext))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            // blockedTask should not be recommended (blocker is "work", needs "terminal")
            assertEquals(0, recommendations.size)
        }

        @Test
        fun `RELATES_TO dependencies should not block task from recommendation`() = runBlocking {
            val relatedTask = createTask(title = "Related", status = TaskStatus.PENDING)
            val task = createTask(title = "Main Task", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = relatedTask.id, toTaskId = task.id,
                type = DependencyType.RELATES_TO
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(relatedTask, task))
            every { mockDependencyRepository.findByToTaskId(task.id) } returns listOf(dep)
            every { mockDependencyRepository.findByToTaskId(relatedTask.id) } returns emptyList()
            // getById should NOT be called for RELATES_TO

            val result = parseResult(tool.execute(params, roleAwareContext))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val data = extractData(result)
            // Both tasks should be unblocked (RELATES_TO doesn't block)
            assertEquals(2, data["totalCandidates"]?.jsonPrimitive?.int)
        }

        @Test
        fun `fallback behavior without StatusProgressionService matches legacy behavior`() = runBlocking {
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.IN_PROGRESS)
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = blockerTask.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            // Use original context without StatusProgressionService
            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            // IN_PROGRESS is not terminal, so blockedTask is still blocked
            assertEquals(0, recommendations.size)
        }

        @Test
        fun `fallback without StatusProgressionService treats completed as unblocking`() = runBlocking {
            val completedBlocker = createTask(title = "Done Blocker", status = TaskStatus.COMPLETED)
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = completedBlocker.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(completedBlocker.id) } returns Result.Success(completedBlocker)

            // Use original context without StatusProgressionService
            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            // COMPLETED is terminal, so blockedTask is unblocked
            assertEquals(1, recommendations.size)
            assertEquals(blockedTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }

        @Test
        fun `fallback without StatusProgressionService treats cancelled as unblocking`() = runBlocking {
            val cancelledBlocker = createTask(title = "Cancelled Blocker", status = TaskStatus.CANCELLED)
            val blockedTask = createTask(title = "Candidate", status = TaskStatus.PENDING)
            val dep = createDependency(
                fromTaskId = cancelledBlocker.id, toTaskId = blockedTask.id,
                type = DependencyType.BLOCKS
            )

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(cancelledBlocker.id) } returns Result.Success(cancelledBlocker)

            // Use original context without StatusProgressionService
            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            assertEquals(1, recommendations.size)
        }
    }

    @Nested
    inner class BasicFunctionalityTests {

        @Test
        fun `should return empty when no tasks exist`() = runBlocking {
            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns Result.Success(emptyList())

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            assertEquals(0, extractData(result)["totalCandidates"]?.jsonPrimitive?.int)
            assertTrue(extractRecommendations(result).isEmpty())
        }

        @Test
        fun `should return unblocked pending tasks sorted by priority then complexity`() = runBlocking {
            val lowPriorityTask = createTask(title = "Low", priority = Priority.LOW, complexity = 1)
            val highPrioritySimple = createTask(title = "High Simple", priority = Priority.HIGH, complexity = 2)
            val highPriorityComplex = createTask(title = "High Complex", priority = Priority.HIGH, complexity = 8)

            val params = buildJsonObject {
                put("limit", 3)
            }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(lowPriorityTask, highPrioritySimple, highPriorityComplex))
            every { mockDependencyRepository.findByToTaskId(any()) } returns emptyList()

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            assertEquals(3, recommendations.size)
            // HIGH priority, lower complexity first
            assertEquals("High Simple", recommendations[0].jsonObject["title"]?.jsonPrimitive?.content)
            assertEquals("High Complex", recommendations[1].jsonObject["title"]?.jsonPrimitive?.content)
            assertEquals("Low", recommendations[2].jsonObject["title"]?.jsonPrimitive?.content)
        }

        @Test
        fun `should exclude blocked tasks from recommendations`() = runBlocking {
            val blockerTask = createTask(title = "Blocker", status = TaskStatus.PENDING)
            val blockedTask = createTask(title = "Blocked", status = TaskStatus.PENDING)
            val dep = createDependency(fromTaskId = blockerTask.id, toTaskId = blockedTask.id)

            val params = buildJsonObject { }

            coEvery { mockTaskRepository.findAll(limit = any()) } returns
                    Result.Success(listOf(blockerTask, blockedTask))
            every { mockDependencyRepository.findByToTaskId(blockerTask.id) } returns emptyList()
            every { mockDependencyRepository.findByToTaskId(blockedTask.id) } returns listOf(dep)
            coEvery { mockTaskRepository.getById(blockerTask.id) } returns Result.Success(blockerTask)

            val result = parseResult(tool.execute(params, context))

            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)
            val recommendations = extractRecommendations(result)
            assertEquals(1, recommendations.size)
            assertEquals(blockerTask.id.toString(), recommendations[0].jsonObject["taskId"]?.jsonPrimitive?.content)
        }
    }
}
