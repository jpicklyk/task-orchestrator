package io.github.jpicklyk.mcptask.application.tools.base

import io.github.jpicklyk.mcptask.application.service.*
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.model.EntityType
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.repository.RepositoryProvider
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.*
import java.util.*

class SimpleLockAwareToolDefinitionTest {

    private lateinit var mockLockingService: SimpleLockingService
    private lateinit var mockSessionManager: SimpleSessionManager
    private lateinit var mockErrorHandler: LockErrorHandler
    private lateinit var mockRepositoryProvider: RepositoryProvider
    private lateinit var testTool: TestLockAwareTool
    private lateinit var context: ToolExecutionContext

    @BeforeEach
    fun setUp() {
        mockLockingService = mock()
        mockSessionManager = mock()
        mockErrorHandler = mock()
        mockRepositoryProvider = mock()
        
        testTool = TestLockAwareTool(mockLockingService, mockSessionManager, mockErrorHandler)
        context = ToolExecutionContext(mockRepositoryProvider)

        // Setup default mock behaviors
        runBlocking {
            whenever(mockSessionManager.getCurrentSession()).thenReturn("test-session-123")
            whenever(mockLockingService.canProceed(any())).thenReturn(true)
            whenever(mockLockingService.recordOperationStart(any())).thenReturn("op-123")
        }
    }

    @Test
    fun `should execute successfully when no locking is used`() = runBlocking {
        // Given
        testTool.shouldUseLockingValue = false
        val params = JsonObject(mapOf("test" to JsonPrimitive("value")))

        // When
        val result = testTool.execute(params, context)

        // Then
        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(true, resultObj["success"]?.jsonPrimitive?.boolean)
        
        // Verify no locking interactions
        verify(mockLockingService, never()).canProceed(any())
        verify(mockLockingService, never()).recordOperationStart(any())
    }

    @Test
    fun `should handle validation exceptions correctly`() = runBlocking {
        // Given
        testTool.shouldThrowValidation = true
        val params = JsonObject(mapOf("invalid" to JsonPrimitive("data")))

        // When
        val result = testTool.execute(params, context)

        // Then
        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(false, resultObj["success"]?.jsonPrimitive?.boolean)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Validation failed") ?: false)
        assertEquals("VALIDATION_ERROR", resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `should handle general exceptions correctly`() = runBlocking {
        // Given
        testTool.shouldThrowGeneral = true
        val params = JsonObject(mapOf("test" to JsonPrimitive("value")))

        // When
        val result = testTool.execute(params, context)

        // Then
        assertTrue(result is JsonObject)
        val resultObj = result as JsonObject
        assertEquals(false, resultObj["success"]?.jsonPrimitive?.boolean)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Tool execution failed") ?: false)
        assertEquals("INTERNAL_ERROR", resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `should extract UUID parameters correctly`() {
        // Given
        val testUuid = UUID.randomUUID()
        val params = JsonObject(mapOf(
            "validUuid" to JsonPrimitive(testUuid.toString()),
            "invalidUuid" to JsonPrimitive("not-a-uuid"),
            "notString" to JsonPrimitive(123)
        ))

        // When
        val validResult = testTool.testExtractUuidFromParameters(params, "validUuid")
        val invalidResult = testTool.testExtractUuidFromParameters(params, "invalidUuid")
        val notStringResult = testTool.testExtractUuidFromParameters(params, "notString")
        val missingResult = testTool.testExtractUuidFromParameters(params, "missing")

        // Then
        assertEquals(testUuid, validResult)
        assertNull(invalidResult)
        assertNull(notStringResult)
        assertNull(missingResult)
    }

    @Test
    fun `should extract string parameters correctly`() {
        // Given
        val params = JsonObject(mapOf(
            "validString" to JsonPrimitive("test-value"),
            "emptyString" to JsonPrimitive(""),
            "notString" to JsonPrimitive(123)
        ))

        // When
        val validResult = testTool.testExtractStringFromParameters(params, "validString")
        val emptyResult = testTool.testExtractStringFromParameters(params, "emptyString")
        val notStringResult = testTool.testExtractStringFromParameters(params, "notString")
        val missingResult = testTool.testExtractStringFromParameters(params, "missing")

        // Then
        assertEquals("test-value", validResult)
        assertEquals("", emptyResult)
        assertNull(notStringResult)
        assertNull(missingResult)
    }

    @Test
    fun `should handle repository success results correctly`() {
        // Given
        val testData = "test-data"
        val successResult = Result.Success(testData)

        // When
        val jsonResult = testTool.testHandleRepositoryResult(
            successResult,
            "Operation successful"
        ) { data -> JsonPrimitive(data) }

        // Then
        assertTrue(jsonResult is JsonObject)
        val resultObj = jsonResult as JsonObject
        assertEquals(true, resultObj["success"]?.jsonPrimitive?.boolean)
        assertEquals("Operation successful", resultObj["message"]?.jsonPrimitive?.content)
        assertEquals("test-data", resultObj["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should handle repository error results correctly`() {
        // Given
        val errorResult = Result.Error(RepositoryError.NotFound(UUID.randomUUID(), EntityType.TASK, "Task not found"))

        // When
        val jsonResult = testTool.testHandleRepositoryResult(
            errorResult,
            "This should not appear"
        ) { data -> JsonPrimitive(data.toString()) }

        // Then
        assertTrue(jsonResult is JsonObject)
        val resultObj = jsonResult as JsonObject
        assertEquals(false, resultObj["success"]?.jsonPrimitive?.boolean)
        assertTrue(resultObj["message"]?.jsonPrimitive?.content?.contains("Task not found") ?: false)
        assertEquals("RESOURCE_NOT_FOUND", resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun `should handle different repository error types`() {
        val testCases = mapOf(
            RepositoryError.ValidationError("Validation failed") to "VALIDATION_ERROR",
            RepositoryError.ConflictError("Conflict occurred") to "CONFLICT_ERROR", 
            RepositoryError.DatabaseError("Database error") to "DATABASE_ERROR",
            RepositoryError.UnknownError("Unknown error") to "INTERNAL_ERROR"
        )

        testCases.forEach { (error, expectedCode) ->
            // Given
            val errorResult = Result.Error(error)

            // When
            val jsonResult = testTool.testHandleRepositoryResult(errorResult) { JsonPrimitive(it.toString()) }

            // Then
            assertTrue(jsonResult is JsonObject)
            val resultObj = jsonResult as JsonObject
            assertEquals(false, resultObj["success"]?.jsonPrimitive?.boolean)
            assertEquals(expectedCode, resultObj["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `should create lock error context correctly`() {
        // Given
        val operationName = "test-operation"
        val entityType = EntityType.TASK
        val entityId = UUID.randomUUID()
        val sessionId = "test-session"
        val additionalMetadata = mapOf("key" to "value")

        // When
        val context = testTool.testCreateLockErrorContext(
            operationName, entityType, entityId, sessionId, additionalMetadata
        )

        // Then
        assertEquals(operationName, context.operationName)
        assertEquals(entityType, context.entityType)
        assertEquals(entityId, context.entityId)
        assertEquals(sessionId, context.sessionId)
        assertEquals("mcp-task-orchestrator", context.userAgent)
        assertEquals(additionalMetadata, context.additionalMetadata)
        assertNotNull(context.requestTimestamp)
    }

    @Test
    fun `should handle non-JSON parameters gracefully`() {
        // Given
        val nonJsonParams = JsonPrimitive("not-an-object")

        // When & Then
        assertNull(testTool.testExtractUuidFromParameters(nonJsonParams, "any"))
        assertNull(testTool.testExtractStringFromParameters(nonJsonParams, "any"))
    }

    // Test tool implementation for testing purposes
    class TestLockAwareTool(
        lockingService: SimpleLockingService? = null,
        sessionManager: SimpleSessionManager? = null,
        errorHandler: LockErrorHandler = DefaultLockErrorHandler()
    ) : SimpleLockAwareToolDefinition(lockingService, sessionManager, errorHandler) {
        
        var shouldUseLockingValue = false
        var shouldThrowValidation = false
        var shouldThrowGeneral = false

        override val name: String = "test-tool"
        override val description: String = "Test tool for locking behavior"
        override val category: ToolCategory = ToolCategory.SYSTEM
        override val parameterSchema: Tool.Input = Tool.Input(
            properties = JsonObject(mapOf()),
            required = emptyList()
        )

        override fun shouldUseLocking(): Boolean = shouldUseLockingValue

        override suspend fun executeInternal(params: JsonElement, context: ToolExecutionContext): JsonElement {
            when {
                shouldThrowValidation -> throw ToolValidationException("Validation failed")
                shouldThrowGeneral -> throw RuntimeException("General error")
            }

            return JsonObject(mapOf(
                "success" to JsonPrimitive(true),
                "message" to JsonPrimitive("Test execution successful"),
                "data" to JsonObject(mapOf(
                    "toolName" to JsonPrimitive(name),
                    "params" to params
                ))
            ))
        }

        override fun validateParams(params: JsonElement) {
            // Basic validation for testing
            if (shouldThrowValidation) {
                throw ToolValidationException("Validation failed")
            }
        }

        // Public test methods to access protected functionality
        fun testExtractUuidFromParameters(parameters: JsonElement, paramName: String) = 
            extractUuidFromParameters(parameters, paramName)
            
        fun testExtractStringFromParameters(parameters: JsonElement, paramName: String) = 
            extractStringFromParameters(parameters, paramName)
            
        fun <T> testHandleRepositoryResult(
            result: Result<T>,
            successMessage: String? = null,
            dataTransform: (T) -> JsonElement
        ) = handleRepositoryResult(result, successMessage, dataTransform)
        
        fun testCreateLockErrorContext(
            operationName: String,
            entityType: EntityType,
            entityId: UUID,
            sessionId: String,
            additionalMetadata: Map<String, String> = emptyMap()
        ) = createLockErrorContext(operationName, entityType, entityId, sessionId, additionalMetadata)
    }
}