package io.github.jpicklyk.mcptask.application.tools.status

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.RoleTransition
import io.github.jpicklyk.mcptask.domain.repository.*
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.*

class QueryRoleTransitionsToolTest {
    private lateinit var tool: QueryRoleTransitionsTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockRoleTransitionRepository: RoleTransitionRepository
    private lateinit var mockRepositoryProvider: RepositoryProvider

    private val entityId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        mockRoleTransitionRepository = mockk()
        mockRepositoryProvider = mockk()

        every { mockRepositoryProvider.roleTransitionRepository() } returns mockRoleTransitionRepository
        // Mock other repositories that ToolExecutionContext might access
        every { mockRepositoryProvider.taskRepository() } returns mockk()
        every { mockRepositoryProvider.featureRepository() } returns mockk()
        every { mockRepositoryProvider.projectRepository() } returns mockk()
        every { mockRepositoryProvider.sectionRepository() } returns mockk()
        every { mockRepositoryProvider.dependencyRepository() } returns mockk()
        every { mockRepositoryProvider.templateRepository() } returns mockk()

        context = ToolExecutionContext(
            repositoryProvider = mockRepositoryProvider,
            statusProgressionService = null,
            cascadeService = null
        )
        tool = QueryRoleTransitionsTool()
    }

    @Nested
    @DisplayName("Validation Tests")
    inner class ValidationTests {

        @Test
        @DisplayName("Missing entityId should throw validation error")
        fun `missing entityId throws validation error`() {
            val params = buildJsonObject { }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }.also {
                assertTrue(it.message!!.contains("entityId"))
            }
        }

        @Test
        @DisplayName("Invalid entityId format should throw validation error")
        fun `invalid entityId format throws validation error`() {
            val params = buildJsonObject {
                put("entityId", "not-a-uuid")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }.also {
                assertTrue(it.message!!.contains("UUID"))
            }
        }

        @Test
        @DisplayName("Invalid entityType should throw validation error")
        fun `invalid entityType throws validation error`() {
            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("entityType", "invalid")
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }.also {
                assertTrue(it.message!!.contains("entityType"))
            }
        }

        @Test
        @DisplayName("Limit out of range should throw validation error")
        fun `limit out of range throws validation error`() {
            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("limit", 101)
            }

            assertThrows<ToolValidationException> {
                tool.validateParams(params)
            }.also {
                assertTrue(it.message!!.contains("limit"))
            }
        }

        @Test
        @DisplayName("Valid params should pass validation")
        fun `valid params pass validation`() {
            val params = buildJsonObject {
                put("entityId", entityId.toString())
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }

        @Test
        @DisplayName("Valid params with all optional fields should pass validation")
        fun `valid params with optional fields pass validation`() {
            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("entityType", "task")
                put("limit", 25)
                put("fromRole", "queue")
                put("toRole", "work")
            }

            assertDoesNotThrow {
                tool.validateParams(params)
            }
        }
    }

    @Nested
    @DisplayName("Execution Tests")
    inner class ExecutionTests {

        @Test
        @DisplayName("Query transitions for an entity returns transition list")
        fun `query transitions returns transition list`() = runBlocking {
            val transition1 = RoleTransition(
                id = UUID.randomUUID(),
                entityId = entityId,
                entityType = "task",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "pending",
                toStatus = "in-progress",
                transitionedAt = Instant.parse("2026-02-14T10:00:00Z"),
                trigger = "start",
                summary = "Started work"
            )
            val transition2 = RoleTransition(
                id = UUID.randomUUID(),
                entityId = entityId,
                entityType = "task",
                fromRole = "work",
                toRole = "terminal",
                fromStatus = "in-progress",
                toStatus = "completed",
                transitionedAt = Instant.parse("2026-02-14T12:00:00Z"),
                trigger = "complete",
                summary = null
            )

            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Success(listOf(transition1, transition2))

            val params = buildJsonObject {
                put("entityId", entityId.toString())
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(entityId.toString(), data["entityId"]!!.jsonPrimitive.content)
            assertEquals(2, data["transitionCount"]!!.jsonPrimitive.int)

            val transitions = data["transitions"]!!.jsonArray
            assertEquals(2, transitions.size)

            val first = transitions[0].jsonObject
            assertEquals("queue", first["fromRole"]!!.jsonPrimitive.content)
            assertEquals("work", first["toRole"]!!.jsonPrimitive.content)
            assertEquals("pending", first["fromStatus"]!!.jsonPrimitive.content)
            assertEquals("in-progress", first["toStatus"]!!.jsonPrimitive.content)
            assertEquals("start", first["trigger"]!!.jsonPrimitive.content)
            assertEquals("Started work", first["summary"]!!.jsonPrimitive.content)

            val second = transitions[1].jsonObject
            assertEquals("work", second["fromRole"]!!.jsonPrimitive.content)
            assertEquals("terminal", second["toRole"]!!.jsonPrimitive.content)
            assertEquals("complete", second["trigger"]!!.jsonPrimitive.content)
        }

        @Test
        @DisplayName("Query with no transitions returns empty array")
        fun `query with no transitions returns empty array`() = runBlocking {
            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Success(emptyList())

            val params = buildJsonObject {
                put("entityId", entityId.toString())
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(0, data["transitionCount"]!!.jsonPrimitive.int)
            assertEquals(0, data["transitions"]!!.jsonArray.size)
        }

        @Test
        @DisplayName("Query with fromRole filter returns only matching transitions")
        fun `query with fromRole filter returns matching transitions`() = runBlocking {
            val transition1 = RoleTransition(
                entityId = entityId,
                entityType = "task",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "pending",
                toStatus = "in-progress",
                trigger = "start"
            )
            val transition2 = RoleTransition(
                entityId = entityId,
                entityType = "task",
                fromRole = "work",
                toRole = "terminal",
                fromStatus = "in-progress",
                toStatus = "completed",
                trigger = "complete"
            )

            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Success(listOf(transition1, transition2))

            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("fromRole", "work")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(1, data["transitionCount"]!!.jsonPrimitive.int)

            val transitions = data["transitions"]!!.jsonArray
            assertEquals("work", transitions[0].jsonObject["fromRole"]!!.jsonPrimitive.content)
        }

        @Test
        @DisplayName("Query with toRole filter returns only matching transitions")
        fun `query with toRole filter returns matching transitions`() = runBlocking {
            val transition1 = RoleTransition(
                entityId = entityId,
                entityType = "task",
                fromRole = "queue",
                toRole = "work",
                fromStatus = "pending",
                toStatus = "in-progress",
                trigger = "start"
            )
            val transition2 = RoleTransition(
                entityId = entityId,
                entityType = "task",
                fromRole = "work",
                toRole = "terminal",
                fromStatus = "in-progress",
                toStatus = "completed",
                trigger = "complete"
            )

            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Success(listOf(transition1, transition2))

            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("toRole", "terminal")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(1, data["transitionCount"]!!.jsonPrimitive.int)

            val transitions = data["transitions"]!!.jsonArray
            assertEquals("terminal", transitions[0].jsonObject["toRole"]!!.jsonPrimitive.content)
        }

        @Test
        @DisplayName("Query with limit respects the limit")
        fun `query with limit respects limit`() = runBlocking {
            val transitions = (1..10).map { i ->
                RoleTransition(
                    entityId = entityId,
                    entityType = "task",
                    fromRole = "queue",
                    toRole = "work",
                    fromStatus = "pending",
                    toStatus = "in-progress",
                    trigger = "start",
                    summary = "Transition $i"
                )
            }

            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Success(transitions)

            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("limit", 3)
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(3, data["transitionCount"]!!.jsonPrimitive.int)
            assertEquals(3, data["transitions"]!!.jsonArray.size)
        }

        @Test
        @DisplayName("Query with entityType filter passes filter to repository")
        fun `query with entityType filter passes to repository`() = runBlocking {
            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, "task") } returns
                    Result.Success(emptyList())

            val params = buildJsonObject {
                put("entityId", entityId.toString())
                put("entityType", "task")
            }

            val result = tool.execute(params, context) as JsonObject
            assertTrue(result["success"]?.jsonPrimitive?.boolean == true)

            val data = result["data"]!!.jsonObject
            assertEquals(0, data["transitionCount"]!!.jsonPrimitive.int)
        }

        @Test
        @DisplayName("Repository error returns error response")
        fun `repository error returns error response`() = runBlocking {
            val error = RepositoryError.DatabaseError("Database connection failed")
            coEvery { mockRoleTransitionRepository.findByEntityId(entityId, null) } returns
                    Result.Error(error)

            val params = buildJsonObject {
                put("entityId", entityId.toString())
            }

            val result = tool.execute(params, context) as JsonObject
            assertFalse(result["success"]?.jsonPrimitive?.boolean ?: true)
            assertTrue(result["message"]?.jsonPrimitive?.content?.contains("Database connection failed") == true)
        }
    }

    @Nested
    @DisplayName("User Summary Tests")
    inner class UserSummaryTests {

        @Test
        @DisplayName("User summary shows transition count")
        fun `user summary shows transition count`() {
            val result = buildJsonObject {
                put("success", true)
                put("data", buildJsonObject {
                    put("entityId", entityId.toString())
                    put("transitionCount", 3)
                })
            }

            val summary = tool.userSummary(JsonObject(emptyMap()), result, false)
            assertTrue(summary.contains("3 transitions"))
        }

        @Test
        @DisplayName("User summary handles singular transition")
        fun `user summary handles singular transition`() {
            val result = buildJsonObject {
                put("success", true)
                put("data", buildJsonObject {
                    put("entityId", entityId.toString())
                    put("transitionCount", 1)
                })
            }

            val summary = tool.userSummary(JsonObject(emptyMap()), result, false)
            assertTrue(summary.contains("1 transition"))
            assertFalse(summary.contains("1 transitions"))
        }
    }
}
