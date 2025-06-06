package io.github.jpicklyk.mcptask.application.tools.project

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Project
import io.github.jpicklyk.mcptask.domain.model.ProjectStatus
import io.github.jpicklyk.mcptask.domain.repository.ProjectRepository
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
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

class SearchProjectsToolTest {
    private lateinit var tool: SearchProjectsTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockProjectRepository: ProjectRepository

    // Test data variables
    private val testProjectId1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
    private val testProjectId2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
    private val testProjectId3 = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")

    // Create project test data
    private val project1 = Project(
        id = testProjectId1,
        name = "Mobile App Redesign",
        summary = "Complete redesign of the mobile application with improved UI/UX",
        status = ProjectStatus.IN_DEVELOPMENT,
        createdAt = Instant.parse("2025-05-01T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-02T11:00:00Z"),
        tags = listOf("mobile", "ui", "redesign")
    )

    private val project2 = Project(
        id = testProjectId2,
        name = "API Modernization",
        summary = "Upgrade backend API architecture to improve performance",
        status = ProjectStatus.PLANNING,
        createdAt = Instant.parse("2025-05-03T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-04T11:00:00Z"),
        tags = listOf("api", "backend", "performance")
    )

    private val project3 = Project(
        id = testProjectId3,
        name = "Data Analytics Dashboard",
        summary = "Create comprehensive analytics dashboard for business metrics",
        status = ProjectStatus.COMPLETED,
        createdAt = Instant.parse("2025-05-05T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-06T11:00:00Z"),
        tags = listOf("analytics", "dashboard", "frontend")
    )

    private val allProjects = listOf(project1, project2, project3)

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockProjectRepository = mockk<ProjectRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.projectRepository() } returns mockProjectRepository

        // Setup default repository responses
        coEvery { mockProjectRepository.findAll(any()) } returns Result.Success(allProjects)

        // Mock basic filtering
        coEvery {
            mockProjectRepository.findByFilters(
                projectId = any(),
                status = any(),
                priority = null,
                tags = any(),
                textQuery = any(),
                limit = any(),
            )
        } answers {
            // Extract arguments
            val projectId = arg<UUID?>(0)
            val status = arg<ProjectStatus?>(1)
            val tags = arg<List<String>?>(3)
            val textQuery = arg<String?>(4)

            // Filter projects based on the provided criteria
            val filteredProjects = allProjects.filter { project ->
                (projectId == null || project.id == projectId) &&
                        (textQuery == null ||
                                project.name.contains(textQuery, ignoreCase = true) ||
                                project.summary.contains(textQuery, ignoreCase = true)) &&
                        (tags == null || tags.isEmpty() || project.tags.any { tag -> tags.contains(tag) }) &&
                        (status == null || project.status == status)
            }

            Result.Success(filteredProjects)
        }

        // Specific mock for API text search
        coEvery {
            mockProjectRepository.findByFilters(
                projectId = any(),
                status = any(),
                priority = null,
                tags = any(),
                textQuery = eq("API"),
                limit = any(),
            )
        } returns Result.Success(listOf(project2))

        // Mock for nonexistent search
        coEvery {
            mockProjectRepository.findByFilters(
                projectId = any(),
                status = any(),
                priority = null,
                tags = any(),
                textQuery = eq("nonexistent"),
                limit = any(),
            )
        } returns Result.Success(emptyList())

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = SearchProjectsTool()
    }

    @Nested
    inner class ParameterValidationTests {
        @Test
        fun `validateParams with valid parameters should not throw exceptions`() {
            val validParams = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("API"),
                    "status" to JsonPrimitive("planning"),
                    "tag" to JsonPrimitive("api"),
                    "createdAfter" to JsonPrimitive("2025-05-01T00:00:00Z"),
                    "createdBefore" to JsonPrimitive("2025-05-10T00:00:00Z"),
                    "sortBy" to JsonPrimitive("name"),
                    "sortDirection" to JsonPrimitive("asc"),
                    "limit" to JsonPrimitive(10),
                    "offset" to JsonPrimitive(0)
                )
            )

            // Should not throw an exception
            assertDoesNotThrow { tool.validateParams(validParams) }
        }

        @Test
        fun `validateParams with empty parameters should not throw exceptions`() {
            val emptyParams = JsonObject(mapOf())
            assertDoesNotThrow { tool.validateParams(emptyParams) }
        }

        @Test
        fun `validateParams with invalid status should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("invalid-status")
                )
            )

            // Should throw an exception for invalid status
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid status"))
        }

        @Test
        fun `validateParams with invalid date formats should throw validation exception`() {
            // Test with invalid createdAfter
            val invalidCreatedAfterParams = JsonObject(
                mapOf(
                    "createdAfter" to JsonPrimitive("not-a-date")
                )
            )

            val exception1 = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(invalidCreatedAfterParams)
            }
            assertTrue(exception1.message!!.contains("Invalid createdAfter format"))

            // Test with invalid createdBefore
            val invalidCreatedBeforeParams = JsonObject(
                mapOf(
                    "createdBefore" to JsonPrimitive("not-a-date")
                )
            )

            val exception2 = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(invalidCreatedBeforeParams)
            }
            assertTrue(exception2.message!!.contains("Invalid createdBefore format"))
        }

        @Test
        fun `validateParams with invalid sortBy should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "sortBy" to JsonPrimitive("invalid-field")
                )
            )

            // Should throw an exception for invalid sortBy
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid sortBy value"))
        }

        @Test
        fun `validateParams with invalid sortDirection should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "sortDirection" to JsonPrimitive("invalid-direction")
                )
            )

            // Should throw an exception for invalid sortDirection
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid sortDirection value"))
        }

        @Test
        fun `validateParams with out-of-range limit should throw validation exception`() {
            // Test with limit below minimum
            val belowMinParams = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(0)
                )
            )

            var exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(belowMinParams)
            }
            assertTrue(exception.message!!.contains("limit must be at least 1"))

            // Test with limit above maximum
            val aboveMaxParams = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(101)
                )
            )

            exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(aboveMaxParams)
            }
            assertTrue(exception.message!!.contains("limit cannot exceed 100"))
        }

        @Test
        fun `validateParams with negative offset should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "offset" to JsonPrimitive(-1)
                )
            )

            // Should throw an exception for negative offset
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("offset must be at least 0"))
        }
    }

    @Nested
    inner class BasicSearchTests {
        @Test
        fun `execute with no parameters should return all projects`() = runBlocking {
            val emptyParams = JsonObject(mapOf())

            val response = tool.execute(emptyParams, context)
            assertTrue(response is JsonObject)

            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data)

            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(allProjects.size, total)

            // Success message should indicate found projects
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("Found"))
        }

        @Test
        fun `execute with text query should return matching projects`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("API")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data)

            // Check the total count
            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(1, total)

            // Check that the items contain the right project
            val items = data["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(1, items!!.size)

            // Verify the result contains "API" in name or summary
            val firstItem = items[0].jsonObject
            val name = firstItem["name"]?.jsonPrimitive?.content ?: ""
            val summary = firstItem["summary"]?.jsonPrimitive?.content ?: ""
            assertTrue(
                name.contains("API", ignoreCase = true) ||
                        summary.contains("API", ignoreCase = true)
            )
        }

        @Test
        fun `execute with empty search results should return appropriate message`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("nonexistent")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val message = responseObj["message"]?.jsonPrimitive?.content
            assertEquals("Found 0 projects", message)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(0, total)

            val items = data["items"]?.jsonArray
            assertEquals(0, items!!.size)
        }
    }

    @Nested
    inner class FilteringTests {
        @Test
        fun `execute with status filter should return projects with matching status`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("completed")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(1, total)

            val items = data["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(1, items!!.size)

            // Verify all returned items have "completed" status
            val item = items[0].jsonObject
            val status = item["status"]?.jsonPrimitive?.content
            assertEquals("completed", status)
        }

        @Test
        fun `execute with tag filter should return projects with matching tag`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "tag" to JsonPrimitive("api")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(1, total)

            val items = data["items"]?.jsonArray
            assertNotNull(items)
            assertEquals(1, items!!.size)

            // Verify returned item has the api tag
            val item = items[0].jsonObject
            val tags = item["tags"]?.jsonPrimitive?.content ?: ""
            assertTrue(tags.contains("api", ignoreCase = true))
        }

        @Test
        fun `execute with date filters should return projects matching date criteria`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "createdAfter" to JsonPrimitive("2025-05-04T00:00:00Z"),
                    "createdBefore" to JsonPrimitive("2025-05-06T00:00:00Z")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int

            // Only project3 was created in this date range
            assertEquals(1, total)

            val items = data["items"]?.jsonArray
            assertEquals(1, items!!.size)

            // Verify it's the right project
            val item = items[0].jsonObject
            val name = item["name"]?.jsonPrimitive?.content
            assertEquals("Data Analytics Dashboard", name)
        }

        @Test
        fun `execute with combined filters should return projects matching all criteria`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("in-development"),
                    "tag" to JsonPrimitive("mobile")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(1, total)

            val items = data["items"]?.jsonArray
            assertEquals(1, items!!.size)

            // Verify all criteria match
            val item = items[0].jsonObject
            val status = item["status"]?.jsonPrimitive?.content
            val tags = item["tags"]?.jsonPrimitive?.content ?: ""

            assertEquals("in-development", status)
            assertTrue(tags.contains("mobile"))
        }
    }

    @Nested
    inner class SortingAndPaginationTests {
        @Test
        fun `execute with custom sorting should return properly sorted results`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "sortBy" to JsonPrimitive("name"),
                    "sortDirection" to JsonPrimitive("asc")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val items = data!!["items"]?.jsonArray

            // Check if items are sorted by name in ascending order
            for (i in 0 until items!!.size - 1) {
                val currentName = items[i].jsonObject["name"]?.jsonPrimitive?.content ?: ""
                val nextName = items[i + 1].jsonObject["name"]?.jsonPrimitive?.content ?: ""
                assertTrue(currentName <= nextName)
            }
        }

        @Test
        fun `execute with pagination should return the correct page of results`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(2),
                    "offset" to JsonPrimitive(2)
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject

            // Check pagination info
            assertEquals(2, data!!["limit"]?.jsonPrimitive?.int)
            assertEquals(2, data["offset"]?.jsonPrimitive?.int)
            assertEquals(allProjects.size, data["total"]?.jsonPrimitive?.int)

            // Check items
            val items = data["items"]?.jsonArray
            assertEquals(1, items!!.size) // Should return 1 item (the 3rd of 3 total)

            // Check hasMore flag
            val hasMore = data["hasMore"]?.jsonPrimitive?.boolean ?: false
            assertFalse(hasMore) // No more pages since we have 3 items, on offset 2
        }

        @Test
        fun `execute with partial page should return correct pagination information`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(2),
                    "offset" to JsonPrimitive(0)
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject

            // Check pagination info
            assertEquals(2, data!!["limit"]?.jsonPrimitive?.int)
            assertEquals(0, data["offset"]?.jsonPrimitive?.int)
            assertEquals(allProjects.size, data["total"]?.jsonPrimitive?.int)

            // Check items
            val items = data["items"]?.jsonArray
            assertEquals(2, items!!.size) // Should return 2 items

            // Check hasMore flag - should be true since we have 3 items total, and only showing 2
            val hasMore = data["hasMore"]?.jsonPrimitive?.boolean ?: false
            assertTrue(hasMore)
        }
    }

    @Nested
    inner class ErrorHandlingTests {
        @Test
        fun `execute should handle database errors gracefully`() = runBlocking {
            // Mock a database error
            coEvery {
                mockProjectRepository.findByFilters(
                    projectId = any(),
                    status = any(),
                    priority = null,
                    tags = any(),
                    textQuery = any(),
                    limit = any(),
                )
            } returns Result.Error(RepositoryError.DatabaseError("Database connection failed"))

            val emptyParams = JsonObject(mapOf())
            val response = tool.execute(emptyParams, context)
            val responseObj = response as JsonObject

            // Verify error response
            assertFalse(responseObj["success"]?.jsonPrimitive?.boolean ?: true)
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertEquals("Failed to search projects", message)

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
        }

        @Test
        fun `execute should handle unexpected exceptions gracefully`() = runBlocking {
            // Mock an unexpected exception
            coEvery {
                mockProjectRepository.findByFilters(
                    projectId = any(),
                    status = any(),
                    priority = null,
                    tags = any(),
                    textQuery = any(),
                    limit = any(),
                )
            } throws RuntimeException("Unexpected error")

            val emptyParams = JsonObject(mapOf())
            val response = tool.execute(emptyParams, context)
            val responseObj = response as JsonObject

            // Verify error response
            assertFalse(responseObj["success"]?.jsonPrimitive?.boolean ?: true)
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertEquals("Failed to search projects", message)

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("INTERNAL_ERROR", error!!["code"]?.jsonPrimitive?.content)
            assertEquals("Unexpected error", error["details"]?.jsonPrimitive?.content)
        }
    }
}