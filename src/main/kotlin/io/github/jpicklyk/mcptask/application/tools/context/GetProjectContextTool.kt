package io.github.jpicklyk.mcptask.application.tools.context

import io.github.jpicklyk.mcptask.application.context.ProjectContext
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.domain.repository.Result
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for getting the current project context.
 * 
 * This tool allows users to check which project is currently set as the active context
 * for their session.
 */
class GetProjectContextTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT
    
    override val name: String = "get_project_context"
    
    override val description: String = """Gets the current project context for this session.
        
        ## Purpose
        Retrieves information about the currently active project context, if any.
        This helps users understand which project their operations are scoped to.
        
        ## Usage
        This tool takes no parameters. Simply call it to get the current context.
        
        ## Response Details
        - If a context is set: Returns project details
        - If no context is set: Returns null for projectId
        
        Example response with context:
        {
          "success": true,
          "message": "Current project context retrieved",
          "data": {
            "projectId": "550e8400-e29b-41d4-a716-446655440000",
            "projectName": "My Project",
            "projectDescription": "Project description",
            "projectStatus": "active"
          }
        }
        
        Example response without context:
        {
          "success": true,
          "message": "No project context is currently set",
          "data": {
            "projectId": null
          }
        }"""
    
    override val parameterSchema: Tool.Input = Tool.Input(
        properties = JsonObject(emptyMap()),
        required = listOf()
    )
    
    override fun validateParams(params: JsonElement) {
        // No parameters to validate
    }
    
    override suspend fun execute(params: JsonElement, context: ToolExecutionContext): JsonElement {
        logger.info("Executing get_project_context tool")
        
        try {
            val sessionId = context.sessionId ?: "default"
            val currentProjectId = ProjectContext.getCurrentProject(sessionId)
            
            if (currentProjectId == null) {
                val data = buildJsonObject {
                    put("projectId", JsonNull)
                }
                
                return successResponse(
                    data = data,
                    message = "No project context is currently set"
                )
            }
            
            // Get project details
            val projectResult = context.projectRepository().getById(currentProjectId)
            
            when (projectResult) {
                is Result.Success -> {
                    val project = projectResult.data
                    
                    val data = buildJsonObject {
                        put("projectId", currentProjectId.toString())
                        put("projectName", project.name)
                        put("projectDescription", project.description)
                        put("projectStatus", project.status.name.lowercase())
                    }
                    
                    return successResponse(
                        data = data,
                        message = "Current project context retrieved"
                    )
                }
                
                is Result.Error -> {
                    // Project was deleted or is inaccessible, clear the context
                    ProjectContext.clearCurrentProject(sessionId)
                    
                    val data = buildJsonObject {
                        put("projectId", JsonNull)
                        put("note", "Previously set project no longer exists")
                    }
                    
                    return successResponse(
                        data = data,
                        message = "Project context was cleared (project no longer exists)"
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("Error getting project context", e)
            return errorResponse(
                message = "Failed to get project context",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}