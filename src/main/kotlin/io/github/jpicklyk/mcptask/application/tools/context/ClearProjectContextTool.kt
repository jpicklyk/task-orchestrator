package io.github.jpicklyk.mcptask.application.tools.context

import io.github.jpicklyk.mcptask.application.context.ProjectContext
import io.github.jpicklyk.mcptask.application.tools.ToolCategory
import io.github.jpicklyk.mcptask.application.tools.ToolExecutionContext
import io.github.jpicklyk.mcptask.application.tools.base.BaseToolDefinition
import io.github.jpicklyk.mcptask.infrastructure.util.ErrorCodes
import io.modelcontextprotocol.kotlin.sdk.Tool
import kotlinx.serialization.json.*

/**
 * MCP tool for clearing the current project context.
 * 
 * This tool allows users to remove the active project context, returning to
 * a state where no default project is set.
 */
class ClearProjectContextTool : BaseToolDefinition() {
    override val category: ToolCategory = ToolCategory.PROJECT_MANAGEMENT
    
    override val name: String = "clear_project_context"
    
    override val description: String = """Clears the current project context for this session.
        
        ## Purpose
        Removes the active project context, allowing operations to work across all projects
        again. This is useful when switching between multiple projects or when you want
        to work without a default project scope.
        
        ## When to Use
        - Before switching to work on a different project
        - When you need to perform cross-project operations
        - To reset the session to a clean state
        - When finishing work on a specific project
        
        ## Usage
        This tool takes no parameters. Simply call it to clear the current context.
        
        Example response:
        {
          "success": true,
          "message": "Project context cleared successfully",
          "data": {
            "previousContext": "550e8400-e29b-41d4-a716-446655440000"
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
        logger.info("Executing clear_project_context tool")
        
        try {
            val sessionId = context.sessionId ?: "default"
            val previousContext = ProjectContext.getCurrentProject(sessionId)
            
            // Clear the context
            ProjectContext.clearCurrentProject(sessionId)
            
            val data = buildJsonObject {
                put("previousContext", previousContext?.toString() ?: JsonNull)
            }
            
            val message = if (previousContext != null) {
                "Project context cleared successfully"
            } else {
                "No project context was set"
            }
            
            logger.info("Project context cleared. Previous: $previousContext")
            
            return successResponse(
                data = data,
                message = message
            )
        } catch (e: Exception) {
            logger.error("Error clearing project context", e)
            return errorResponse(
                message = "Failed to clear project context",
                code = ErrorCodes.INTERNAL_ERROR,
                details = e.message ?: "Unknown error"
            )
        }
    }
}