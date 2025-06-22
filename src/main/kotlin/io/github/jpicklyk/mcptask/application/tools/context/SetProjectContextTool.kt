package io.github.jpicklyk.mcptask.application.tools.context

import io.github.jpicklyk.mcptask.application.context.ProjectContext
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.ToolValidationException
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*
import java.util.*

/**
 * MCP tool for setting the current project context.
 * 
 * This tool allows users to set a project as the current context for their session.
 * Once set, all subsequent operations will be automatically scoped to this project
 * unless explicitly overridden.
 */
class SetProjectContextTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT
    
    override val name: String = "set_project_context"
    
    override val description: String = """Sets the current project context for this session.
        
        ## Purpose
        Establishes a project as the active context, automatically scoping all subsequent
        operations to this project. This improves security by preventing accidental
        cross-project data access and enhances usability by eliminating the need to
        specify projectId in every operation.
        
        ## Benefits
        - **Security**: Prevents accidental access to data from other projects
        - **Convenience**: No need to specify projectId for every operation
        - **Clarity**: Makes it clear which project you're working on
        - **Efficiency**: Reduces repetitive parameter passing
        
        ## Usage Pattern
        ```
        1. List available projects with search_projects
        2. Set the desired project as context with this tool
        3. All subsequent operations are scoped to this project
        4. Change context or clear it when switching projects
        ```
        
        ## Important Notes
        - The context persists for the entire session until changed or cleared
        - You can override the context by explicitly providing a projectId
        - Use get_project_context to check the current context
        - Use clear_project_context to remove the context
        
        Example successful response:
        {
          "success": true,
          "message": "Project context set successfully",
          "data": {
            "projectId": "550e8400-e29b-41d4-a716-446655440000",
            "projectName": "My Project",
            "previousContext": null
          }
        }
        
        Common error responses:
        - PROJECT_NOT_FOUND: When the specified project doesn't exist
        - VALIDATION_ERROR: When the projectId format is invalid"""
    
    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(
            mapOf(
                "projectId" to JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("string"),
                        "description" to JsonPrimitive("The UUID of the project to set as the current context"),
                        "format" to JsonPrimitive("uuid")
                    )
                )
            )
        ),
        required = listOf("projectId")
    )
    
    override fun validateParams(params: JsonElement) {
        val projectId = requiredString(params, "projectId")
        
        try {
            UUID.fromString(projectId)
        } catch (_: IllegalArgumentException) {
            throw ToolValidationException("Invalid projectId format. Must be a valid UUID")
        }
    }
    
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing set_project_context tool")
        
        try {
            val projectIdStr = requiredString(params, "projectId")
            val projectId = UUID.fromString(projectIdStr)
            
            // Verify the project exists
            val projectResult = context.projectRepository().getById(projectId)
            
            when (projectResult) {
                is Result.Success -> {
                    val project = projectResult.data
                    
                    // Get the previous context if any
                    val sessionId = context.sessionId ?: "default"
                    val previousContext = ProjectContext.getCurrentProject(sessionId)
                    
                    // Set the new context
                    ProjectContext.setCurrentProject(sessionId, projectId)
                    
                    // Create response data
                    val data = buildJsonObject {
                        put("projectId", projectId.toString())
                        put("projectName", project.name)
                        put("previousContext", previousContext?.toString() ?: JsonNull)
                    }
                    
                    logger.info("Project context set to: ${project.name} (${projectId})")
                    
                    return successResponse(
                        data = data,
                        message = "Project context set successfully"
                    )
                }
                
                is Result.Error -> {
                    logger.error("Failed to find project: ${projectResult.error}")
                    return errorResponse(
                        message = "Project not found",
                        code = ErrorCodes.NOT_FOUND,
                        details = "No project exists with ID: $projectId"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error setting project context", e)
            return errorResponse(
                message = "Failed to set project context",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}