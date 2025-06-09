package io.github.jpicklyk.mcptask.application.tools.base

import io.github.jpicklyk.mcptask.application.service.SimpleLockingService
import io.github.jpicklyk.mcptask.application.service.SimpleSessionManager
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.domain.repository.RepositoryError
import io.github.jpicklyk.mcptask.infrastructure.util.ResponseUtil
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Simple enhanced BaseToolDefinition that maintains backwards compatibility
 * while allowing for future locking integration.
 */
abstract class SimpleLockAwareToolDefinition(
    private val lockingService: SimpleLockingService? = null,
    private val sessionManager: SimpleSessionManager? = null
) : BaseToolDefinition() {
    
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        return try {
            executeInternal(params, context)
        } catch (e: ToolValidationException) {
            ResponseUtil.createErrorResponse(
                e.message ?: "Validation failed",
                "VALIDATION_ERROR"
            )
        } catch (e: Exception) {
            logger.error("Error in tool execution for ${name}", e)
            ResponseUtil.createErrorResponse(
                "Tool execution failed: ${e.message}",
                "INTERNAL_ERROR"
            )
        }
    }
    
    /**
     * Internal execution method that must be implemented by all tools.
     * This maintains backwards compatibility with existing tools.
     */
    protected abstract suspend fun executeInternal(
        params: JsonElement, 
        context: ToolExecutionContext
    ): JsonElement
    
    /**
     * Determines whether this tool should use the locking system in the future.
     * Default is false for backwards compatibility.
     */
    protected open fun shouldUseLocking(): Boolean = false
    
    // Utility methods for entity extraction
    
    protected fun extractUuidFromParameters(parameters: JsonElement, paramName: String): UUID? {
        return try {
            val paramsObj = parameters as? JsonObject ?: return null
            parameters[paramName]?.let { element ->
                if (element is JsonPrimitive && element.isString) {
                    UUID.fromString(element.content)
                } else null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract UUID from parameter ${paramName}", e)
            null
        }
    }
    
    protected fun extractStringFromParameters(parameters: JsonElement, paramName: String): String? {
        return try {
            val paramsObj = parameters as? JsonObject ?: return null
            parameters[paramName]?.let { element ->
                if (element is JsonPrimitive && element.isString) {
                    element.content
                } else null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract string from parameter ${paramName}", e)
            null
        }
    }
    
    /**
     * Helper method to convert repository Result to JsonElement.
     */
    protected fun <T> handleRepositoryResult(
        result: io.github.jpicklyk.mcptask.domain.repository.Result<T>,
        successMessage: String? = null,
        dataTransform: (T) -> JsonElement
    ): JsonElement {
        return when (result) {
            is io.github.jpicklyk.mcptask.domain.repository.Result.Success -> {
                ResponseUtil.createSuccessResponse(
                    successMessage,
                    dataTransform(result.data)
                )
            }
            is io.github.jpicklyk.mcptask.domain.repository.Result.Error -> {
                when (result.error) {
                    is RepositoryError.NotFound -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "RESOURCE_NOT_FOUND"
                    )
                    is RepositoryError.ValidationError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "VALIDATION_ERROR"
                    )
                    is RepositoryError.ConflictError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "CONFLICT_ERROR"
                    )
                    is RepositoryError.DatabaseError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "DATABASE_ERROR"
                    )
                    is RepositoryError.UnknownError -> ResponseUtil.createErrorResponse(
                        result.error.message,
                        "INTERNAL_ERROR"
                    )
                }
            }
        }
    }
}