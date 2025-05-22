package io.github.jpicklyk.mcptask.application.tools.feature

import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.Feature
import io.github.jpicklyk.mcptask.domain.model.FeatureStatus
import io.github.jpicklyk.mcptask.domain.model.Priority
import io.github.jpicklyk.mcptask.domain.repository.FeatureRepository
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

class SearchFeaturesToolTest {
    private lateinit var tool: SearchFeaturesTool
    private lateinit var context: ToolExecutionContext
    private lateinit var mockFeatureRepository: FeatureRepository

    // Test data variables
    private val testFeatureId1 = UUID.fromString("550e8400-e29b-41d4-a716-446655440001")
    private val testFeatureId2 = UUID.fromString("550e8400-e29b-41d4-a716-446655440002")
    private val testFeatureId3 = UUID.fromString("550e8400-e29b-41d4-a716-446655440003")
    private val testProjectId = UUID.fromString("661f9511-f30c-52e5-b827-557766551111")

    // Create feature test data
    private val feature1 = Feature(
        id = testFeatureId1,
        name = "UI Development",
        summary = "Frontend features development",
        status = FeatureStatus.IN_DEVELOPMENT,
        priority = Priority.HIGH,
        createdAt = Instant.parse("2025-05-01T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-02T11:00:00Z"),
        tags = listOf("ui", "frontend", "high-priority")
    )

    private val feature2 = Feature(
        id = testFeatureId2,
        name = "API Integration",
        summary = "Backend API integrations",
        status = FeatureStatus.PLANNING,
        priority = Priority.MEDIUM,
        createdAt = Instant.parse("2025-05-03T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-04T11:00:00Z"),
        tags = listOf("api", "backend")
    )

    private val feature3 = Feature(
        id = testFeatureId3,
        name = "Documentation",
        summary = "Project documentation",
        status = FeatureStatus.COMPLETED,
        priority = Priority.LOW,
        createdAt = Instant.parse("2025-05-05T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-06T11:00:00Z"),
        tags = listOf("docs", "wiki")
    )

    private val feature4 = Feature(
        id = UUID.randomUUID(),
        name = "Project Feature",
        summary = "Feature associated with a project",
        status = FeatureStatus.IN_DEVELOPMENT,
        priority = Priority.HIGH,
        createdAt = Instant.parse("2025-05-07T10:00:00Z"),
        modifiedAt = Instant.parse("2025-05-08T11:00:00Z"),
        tags = listOf("project", "feature"),
        projectId = testProjectId
    )

    private val allFeatures = listOf(feature1, feature2, feature3, feature4)

    @BeforeEach
    fun setup() {
        // Create mock repositories
        mockFeatureRepository = mockk<FeatureRepository>()
        val mockRepositoryProvider = mockk<RepositoryProvider>()

        // Configure the repository provider to return the mock repository
        every { mockRepositoryProvider.featureRepository() } returns mockFeatureRepository

        // Setup default repository responses
        coEvery { mockFeatureRepository.findAll() } returns Result.Success(allFeatures)

        // Mock basic filtering
        coEvery {
            mockFeatureRepository.findByFilters(
                projectId = any(),
                status = any(),
                priority = any(),
                tags = any(),
                textQuery = any(),
                limit = any(),
            )
        } answers {
            // Extract arguments
            val projectId = arg<UUID?>(0)
            val status = arg<FeatureStatus?>(1)
            val priority = arg<Priority?>(2)
            val tags = arg<List<String>?>(3)
            val textQuery = arg<String?>(4)

            // Filter features based on the provided criteria
            val filteredFeatures = allFeatures.filter { feature ->
                (projectId == null || feature.projectId == projectId) &&
                        (textQuery == null ||
                                feature.name.contains(textQuery, ignoreCase = true) ||
                                feature.summary.contains(textQuery, ignoreCase = true)) &&
                        (tags == null || tags.isEmpty() || feature.tags.any { tag -> tags.contains(tag) }) &&
                        (status == null || feature.status == status) &&
                        (priority == null || feature.priority == priority)
            }

            Result.Success(filteredFeatures)
        }

        // Specific mock for API text search
        coEvery {
            mockFeatureRepository.search(
                query = eq("API"),
                limit = any(),
            )
        } returns Result.Success(listOf(feature2))

        // Mock for nonexistent search
        coEvery {
            mockFeatureRepository.search(
                query = eq("nonexistent"),
                limit = any(),
            )
        } returns Result.Success(emptyList())

        // Mock for project-specific filtering
        coEvery {
            mockFeatureRepository.findByFilters(
                projectId = eq(testProjectId),
                status = any(),
                priority = any(),
                tags = any(),
                textQuery = any(),
                limit = any(),
            )
        } returns Result.Success(listOf(feature4))

        // Create the execution context with the mock repository provider
        context = ToolExecutionContext(mockRepositoryProvider)
        tool = SearchFeaturesTool()
    }

    @Nested
    inner class ParameterValidationTests {
        @Test
        fun `validateParams with valid parameters should not throw exceptions`() {
            val validParams = JsonObject(
                mapOf(
                    "query" to JsonPrimitive("API"),
                    "status" to JsonPrimitive("planning"),
                    "priority" to JsonPrimitive("medium"),
                    "projectId" to JsonPrimitive(testProjectId.toString()),
                    "tag" to JsonPrimitive("api"),
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
        fun `validateParams with invalid priority should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "priority" to JsonPrimitive("invalid-priority")
                )
            )

            // Should throw an exception for invalid priority
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid priority"))
        }

        @Test
        fun `validateParams with invalid projectId should throw validation exception`() {
            val params = JsonObject(
                mapOf(
                    "projectId" to JsonPrimitive("not-a-uuid")
                )
            )

            // Should throw an exception for invalid projectId
            val exception = assertThrows(ToolValidationException::class.java) {
                tool.validateParams(params)
            }
            assertTrue(exception.message!!.contains("Invalid projectId format"))
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
    }

    @Nested
    inner class BasicSearchTests {
        @Test
        fun `execute with no parameters should return all features`() = runBlocking {
            val emptyParams = JsonObject(mapOf())

            val response = tool.execute(emptyParams, context)
            assertTrue(response is JsonObject)

            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            assertNotNull(data)

            val total = data!!["total"]?.jsonPrimitive?.int
            assertEquals(allFeatures.size, total)

            // Success message should indicate found features
            val message = responseObj["message"]?.jsonPrimitive?.content
            assertTrue(message!!.contains("Found"))
        }

        @Test
        fun `execute with text query should return matching features`() = runBlocking {
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

            // Check that the items contain the right feature
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
            assertEquals("Found 0 features", message)

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
        fun `execute with status filter should return features with matching status`() = runBlocking {
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
        fun `execute with priority filter should return features with matching priority`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "priority" to JsonPrimitive("high")
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject
            val total = data!!["total"]?.jsonPrimitive?.int

            // Should return features with HIGH priority (feature1 and feature4)
            assertEquals(2, total)

            val items = data["items"]?.jsonArray
            assertNotNull(items)

            // Verify all returned items have "high" priority
            items!!.forEach { item ->
                val priority = item.jsonObject["priority"]?.jsonPrimitive?.content
                assertEquals("high", priority)
            }
        }

        @Test
        fun `execute with tag filter should return features with matching tag`() = runBlocking {
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
        fun `execute with projectId filter should return features for the project`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "projectId" to JsonPrimitive(testProjectId.toString())
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

            // Verify returned item has the project ID
            val item = items[0].jsonObject
            val projectId = item["projectId"]?.jsonPrimitive?.content
            assertEquals(testProjectId.toString(), projectId)
        }

        @Test
        fun `execute with date filters should return features matching date criteria`() = runBlocking {
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

            // Only feature3 was created in this date range
            assertEquals(1, total)

            val items = data["items"]?.jsonArray
            assertEquals(1, items!!.size)

            // Verify it's the right feature
            val item = items[0].jsonObject
            val name = item["name"]?.jsonPrimitive?.content
            assertEquals("Documentation", name)
        }

        @Test
        fun `execute with combined filters should return features matching all criteria`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("in-development"),
                    "priority" to JsonPrimitive("high"),
                    "tag" to JsonPrimitive("ui")
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
            val priority = item["priority"]?.jsonPrimitive?.content
            val tags = item["tags"]?.jsonPrimitive?.content ?: ""

            assertEquals("in-development", status)
            assertEquals("high", priority)
            assertTrue(tags.contains("ui"))
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
            assertEquals(allFeatures.size, data["total"]?.jsonPrimitive?.int)

            // Check items
            val items = data["items"]?.jsonArray
            assertEquals(2, items!!.size) // Should return 2 items

            // Check hasMore flag
            val hasMore = data["hasMore"]?.jsonPrimitive?.boolean ?: false
            assertFalse(hasMore) // No more pages since we have 4 items, 2 per page, and we're on the second page
        }

        @Test
        fun `execute with partial page should return correct pagination information`() = runBlocking {
            val params = JsonObject(
                mapOf(
                    "limit" to JsonPrimitive(3),
                    "offset" to JsonPrimitive(0)
                )
            )

            val response = tool.execute(params, context)
            val responseObj = response as JsonObject
            val success = responseObj["success"]?.jsonPrimitive?.boolean ?: false
            assertTrue(success)

            val data = responseObj["data"]?.jsonObject

            // Check pagination info
            assertEquals(3, data!!["limit"]?.jsonPrimitive?.int)
            assertEquals(0, data["offset"]?.jsonPrimitive?.int)
            assertEquals(allFeatures.size, data["total"]?.jsonPrimitive?.int)

            // Check items
            val items = data["items"]?.jsonArray
            assertEquals(3, items!!.size) // Should return 3 items

            // Check hasMore flag - should be true since we have 4 items total, and only showing 3
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
                mockFeatureRepository.findByFilters(
                    projectId = any(),
                    status = any(),
                    priority = any(),
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
            assertEquals("Failed to search features", message)

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("DATABASE_ERROR", error!!["code"]?.jsonPrimitive?.content)
        }

        @Test
        fun `execute should handle unexpected exceptions gracefully`() = runBlocking {
            // Mock an unexpected exception
            coEvery {
                mockFeatureRepository.findByFilters(
                    projectId = any(),
                    status = any(),
                    priority = any(),
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
            assertEquals("Failed to search features", message)

            val error = responseObj["error"]?.jsonObject
            assertNotNull(error)
            assertEquals("INTERNAL_ERROR", error!!["code"]?.jsonPrimitive?.content)
            assertEquals("Unexpected error", error["details"]?.jsonPrimitive?.content)
        }
    }
}